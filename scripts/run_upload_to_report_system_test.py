#!/usr/bin/env python3
"""Run the isolated issue #55 web-to-report system test."""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
import json
import os
import random
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILE = ROOT / "infra" / "docker-compose.system-test.yml"
SOURCE_FIXTURE = ROOT / "api/src/main/resources/provider-fixtures/cited-full-text/issue-55-upload-to-report.pdf"
PDF_GENERATOR = ROOT / "scripts/generate_issue55_fixture_pdf.py"
PARSER_FIXTURE = ROOT / "scripts/system_test_parser_fixtures.py"
TERMINAL_RUN_STATUSES = {"COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED"}
LOCAL_TEST_PROVIDER_SELECTIONS = {
    "claimExtractor": "heuristic",
    "embedding": "local",
    "systemOne": "laya",
    "scholarlyMetadata": "recorded-fixtures",
    "openAccess": "recorded-fixtures",
}
MINIO_ACCESS_KEY = "system-test-access"
MINIO_SECRET_KEY = "system-test-secret"
MINIO_BUCKET = "system-test-source"
MINIO_REGION = "us-east-1"


class SystemTestFailure(RuntimeError):
    pass


def run_command(
    arguments: list[str],
    *,
    cwd: Path = ROOT,
    env: dict[str, str] | None = None,
    timeout: int = 120,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        arguments,
        cwd=cwd,
        env=env,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        detail = "\n".join(part for part in (result.stdout, result.stderr) if part).strip()
        raise SystemTestFailure(
            f"Command failed ({result.returncode}): {' '.join(arguments)}"
            + (f"\n{detail[-6000:]}" if detail else "")
        )
    return result


def available_host_ports(count: int) -> list[int]:
    sockets: list[socket.socket] = []
    try:
        ports: list[int] = []
        for _ in range(count):
            candidate = socket.socket()
            candidate.bind(("127.0.0.1", 0))
            sockets.append(candidate)
            ports.append(candidate.getsockname()[1])
        return ports
    finally:
        for candidate in sockets:
            candidate.close()


def docker_network_subnets() -> list[ipaddress.IPv4Network | ipaddress.IPv6Network]:
    network_ids = run_command(["docker", "network", "ls", "--quiet"]).stdout.split()
    if not network_ids:
        return []
    result = run_command(["docker", "network", "inspect", *network_ids], timeout=30)
    subnets: list[ipaddress.IPv4Network | ipaddress.IPv6Network] = []
    for network in json.loads(result.stdout):
        for config in network.get("IPAM", {}).get("Config") or []:
            value = config.get("Subnet")
            if value:
                subnets.append(ipaddress.ip_network(value, strict=False))
    return subnets


def free_test_subnet() -> ipaddress.IPv4Network:
    occupied = docker_network_subnets()
    chooser = random.SystemRandom()
    for _ in range(1024):
        candidate = ipaddress.ip_network(f"10.250.{chooser.randrange(1, 255)}.0/24")
        if not any(candidate.overlaps(existing) for existing in occupied):
            return candidate
    raise SystemTestFailure("Could not find an unused private Docker subnet for the isolated system test.")


def compose_command(
    project: str,
    environment: dict[str, str],
    *arguments: str,
    timeout: int = 300,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    return run_command(
        [
            "docker",
            "compose",
            "--env-file",
            os.devnull,
            "--project-name",
            project,
            "--file",
            str(COMPOSE_FILE),
            *arguments,
        ],
        env=environment,
        timeout=timeout,
        check=check,
    )


def fetch_bytes(url: str, timeout: int = 5) -> tuple[int, bytes]:
    with urlopen(url, timeout=timeout) as response:
        return response.status, response.read()


def create_minio_bucket(host_port: int) -> None:
    timestamp = datetime.now(timezone.utc)
    amz_date = timestamp.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = timestamp.strftime("%Y%m%d")
    host = f"127.0.0.1:{host_port}"
    payload_hash = hashlib.sha256(b"").hexdigest()
    signed_headers = "host;x-amz-content-sha256;x-amz-date"
    canonical_request = "\n".join(
        (
            "PUT",
            f"/{MINIO_BUCKET}",
            "",
            f"host:{host}",
            f"x-amz-content-sha256:{payload_hash}",
            f"x-amz-date:{amz_date}",
            "",
            signed_headers,
            payload_hash,
        )
    )
    scope = f"{date_stamp}/{MINIO_REGION}/s3/aws4_request"
    string_to_sign = "\n".join(
        (
            "AWS4-HMAC-SHA256",
            amz_date,
            scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        )
    )

    def sign(key: bytes, value: str) -> bytes:
        return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()

    date_key = sign(f"AWS4{MINIO_SECRET_KEY}".encode("utf-8"), date_stamp)
    region_key = sign(date_key, MINIO_REGION)
    service_key = sign(region_key, "s3")
    signing_key = sign(service_key, "aws4_request")
    signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    authorization = (
        "AWS4-HMAC-SHA256 "
        f"Credential={MINIO_ACCESS_KEY}/{scope}, "
        f"SignedHeaders={signed_headers}, Signature={signature}"
    )
    request = Request(
        f"http://{host}/{MINIO_BUCKET}",
        data=b"",
        method="PUT",
        headers={
            "Authorization": authorization,
            "Host": host,
            "x-amz-content-sha256": payload_hash,
            "x-amz-date": amz_date,
        },
    )
    try:
        with urlopen(request, timeout=10) as response:
            if response.status not in {200, 409}:
                raise SystemTestFailure(f"MinIO returned HTTP {response.status} while creating the test bucket.")
    except HTTPError as error:
        if error.code != 409:
            raise SystemTestFailure(f"MinIO returned HTTP {error.code} while creating the test bucket.") from None


def wait_for_http(url: str, description: str, timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            status, _ = fetch_bytes(url)
            if 200 <= status < 300:
                return
        except (OSError, URLError, TimeoutError):
            pass
        time.sleep(1)
    raise SystemTestFailure(f"{description} did not become ready within {timeout_seconds} seconds.")


def get_json(url: str) -> dict:
    _, body = fetch_bytes(url, timeout=10)
    return json.loads(body)


def wait_for_database_and_redis(project: str, environment: dict[str, str]) -> None:
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        postgres = compose_command(
            project,
            environment,
            "exec",
            "--no-TTY",
            "postgres",
            "pg_isready",
            "-U",
            "papertrail",
            "-d",
            "papertrail",
            timeout=15,
            check=False,
        )
        redis = compose_command(
            project,
            environment,
            "exec",
            "--no-TTY",
            "redis",
            "redis-cli",
            "ping",
            timeout=15,
            check=False,
        )
        if postgres.returncode == 0 and redis.returncode == 0 and "PONG" in redis.stdout:
            return
        time.sleep(1)
    raise SystemTestFailure("The isolated PostgreSQL/Redis services did not become ready within 120 seconds.")


def browser_command(session: str, engine: str, *arguments: str) -> str:
    result = run_command(
        ["agent-browser", "--session", session, "--engine", engine, *arguments],
        timeout=90,
        check=False,
    )
    if result.returncode != 0:
        raise SystemTestFailure(f"agent-browser {arguments[0] if arguments else 'command'} failed: {result.stderr[-2000:]}")
    return result.stdout.strip()


def browser_has_text(session: str, engine: str, expected: str) -> bool:
    result = browser_command(
        session,
        engine,
        "eval",
        f"document.body.innerText.includes({json.dumps(expected)})",
    )
    return result.strip().lower().strip('"') == "true"


def wait_for_browser_text(session: str, engine: str, expected: str, timeout_seconds: int = 90) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            if browser_has_text(session, engine, expected):
                return
        except SystemTestFailure:
            pass
        time.sleep(1)
    raise SystemTestFailure(f"The workspace did not display the expected state within {timeout_seconds} seconds.")


def wait_for_browser_link(session: str, engine: str, accessible_name: str, timeout_seconds: int = 180) -> None:
    expression = (
        "Array.from(document.querySelectorAll('a')).some((link) => "
        f"link.getAttribute('aria-label') === {json.dumps(accessible_name)})"
    )
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            found = browser_command(session, engine, "eval", expression).strip().lower().strip('"') == "true"
            if found:
                return
            if browser_has_text(session, engine, "Could not continue"):
                raise SystemTestFailure("The workspace showed an upload error instead of creating an Analysis Run.")
        except SystemTestFailure as error:
            if "upload error" in str(error):
                raise
        time.sleep(1)
    diagnostic = browser_command(
        session,
        engine,
        "eval",
        "JSON.stringify({path: location.pathname, dialogOpen: Boolean(document.querySelector('[role=dialog]')), submitLabel: document.querySelector('button[type=submit]')?.innerText.trim().replace(/\\s+/g, ' '), alertTitles: Array.from(document.querySelectorAll('[role=alert] [data-slot=alert-title]')).map((title) => title.textContent.trim()), apiPaths: performance.getEntriesByType('resource').map((entry) => new URL(entry.name).pathname).filter((path) => path.startsWith('/api/')).slice(-8)})",
    )
    raise SystemTestFailure(
        f"The new Analysis Run link did not appear within {timeout_seconds} seconds. "
        f"Sanitized workspace state: {diagnostic}"
    )


def wait_for_report(api_origin: str, run_id: str, timeout_seconds: int = 900) -> tuple[dict, dict]:
    deadline = time.monotonic() + timeout_seconds
    run_url = f"{api_origin}/api/v1/analysis-runs/{run_id}"
    report_url = f"{run_url}/report"
    run: dict = {}
    report: dict = {}
    while time.monotonic() < deadline:
        try:
            run = get_json(run_url)
            report = get_json(report_url)
        except HTTPError as error:
            if error.code not in {404, 409}:
                raise SystemTestFailure(f"The API returned HTTP {error.code} while waiting for the report.") from None
            time.sleep(1)
            continue
        except (OSError, URLError, TimeoutError, json.JSONDecodeError):
            time.sleep(1)
            continue
        if run.get("status") in TERMINAL_RUN_STATUSES and report.get("runStatus") in TERMINAL_RUN_STATUSES:
            return run, report
        time.sleep(1)
    progress = run.get("progress") or {}
    pipeline = [
        {
            "id": stage.get("id"),
            "status": stage.get("status"),
            "counts": stage.get("counts"),
            "steps": [
                {
                    "id": step.get("id"),
                    "status": step.get("status"),
                    "counts": step.get("counts"),
                    "items": [
                        {"id": item.get("id"), "status": item.get("status"), "reasonCode": item.get("reasonCode")}
                        for item in step.get("items", [])
                    ],
                }
                for step in stage.get("steps", [])
            ],
        }
        for stage in (run.get("pipeline") or {}).get("stages", [])
    ]
    progress_counts = {
        key: value
        for key, value in progress.items()
        if key == "percent" or key.endswith("Count")
    }
    coverage = report.get("evidenceCoverage") or {}
    diagnostic = {
        "runId": run_id,
        "runStatus": run.get("status"),
        "reportStatus": report.get("runStatus"),
        "progressStage": progress.get("stage"),
        "progressCounts": progress_counts,
        "pipelineStages": pipeline,
        "evidenceExecutionStatus": coverage.get("executionStatus"),
        "evidenceSummary": coverage.get("summary"),
    }
    raise SystemTestFailure(
        f"Analysis Run {run_id} did not reach a report-ready state within {timeout_seconds} seconds; "
        f"sanitized status/counts: {json.dumps(diagnostic, sort_keys=True)}"
    )


def safe_failure_category(value: object) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    message = value.lower()
    if "sha-256" in message or "sha256" in message:
        return "source-hash-integrity"
    if "provenance" in message:
        return "run-source-provenance"
    if "provider" in message:
        return "provider-validation"
    if any(marker in message for marker in ("s3", "object store", "no such key", "nosuchkey", "bucket")):
        return "object-store"
    if "grobid" in message or "scientific parser" in message:
        return "scientific-parser"
    if "parsable state" in message:
        return "run-state-transition"
    if "missing" in message:
        return "missing-record"
    exception_type = value.split(":", 1)[0]
    return exception_type if exception_type.endswith(("Exception", "Error")) else "other"


def assert_report(
    run: dict,
    report: dict,
    source_sha256: str,
    fixture_calls: dict[str, int],
    worker_error_types: list[str],
) -> tuple[int, str]:
    if run.get("status") != "COMPLETED" or report.get("runStatus") != "COMPLETED":
        stages = [
            {
                "id": stage.get("id"),
                "status": stage.get("status"),
                "counts": stage.get("counts"),
                "steps": [
                    {
                        "id": step.get("id"),
                        "status": step.get("status"),
                        "counts": step.get("counts"),
                        "items": [
                            {
                                "id": item.get("id"),
                                "status": item.get("status"),
                                "reasonCode": item.get("reasonCode"),
                            }
                            for item in step.get("items", [])
                        ],
                    }
                    for step in stage.get("steps", [])
                ],
            }
            for stage in (run.get("pipeline") or {}).get("stages", [])
        ]
        coverage = report.get("evidenceCoverage") or {}
        diagnostic = {
            "runStatus": run.get("status"),
            "reportStatus": report.get("runStatus"),
            "evidenceExecutionStatus": coverage.get("executionStatus"),
            "evidenceSummary": coverage.get("summary"),
            "pipelineStages": stages,
            "fixtureCalls": fixture_calls,
            "failureCategory": safe_failure_category(run.get("failureReason")),
            "workerErrorTypes": worker_error_types,
        }
        raise SystemTestFailure(
            "The synthetic fixture Analysis Run did not complete without warnings; "
            f"sanitized status/counts: {json.dumps(diagnostic, sort_keys=True)}"
        )

    run_id = run.get("id")
    if not run_id or report.get("analysisRunId") != run_id:
        raise SystemTestFailure("The report is not scoped to the uploaded Analysis Run.")

    configuration = run.get("configuration") or {}
    system_one = configuration.get("systemOne") or {}
    if system_one.get("provider") != "laya" or system_one.get("trustBoundary") != "LOCAL":
        raise SystemTestFailure("The fixture run did not use the deterministic local System One API fixture.")
    if (configuration.get("openAccess") or {}).get("provider") != "recorded-fixtures":
        raise SystemTestFailure("The fixture run did not use the local recorded open-access provider.")
    for role, provider_id in LOCAL_TEST_PROVIDER_SELECTIONS.items():
        if role == "scholarlyMetadata":
            provider = ((configuration.get("referenceResolution") or {}).get("provider") or {}).get("provider")
        else:
            provider = (configuration.get(role) or {}).get("provider")
        if provider != provider_id:
            raise SystemTestFailure(f"The fixture run did not use the expected local {role} provider.")
    if configuration.get("externalProviderConsents"):
        raise SystemTestFailure("The fixture run unexpectedly recorded external-provider consent.")
    if fixture_calls.get("systemOne", 0) < 1 or fixture_calls.get("systemOnePreflight", 0) < 1:
        raise SystemTestFailure("The queued run did not exercise the deterministic System One API fixture.")

    coverage = report.get("evidenceCoverage") or {}
    summary = coverage.get("summary") or {}
    if coverage.get("executionStatus") != "COMPLETED":
        raise SystemTestFailure("The Evidence Coverage report is not in its completed state.")
    if summary.get("completedVerifications", 0) < 1 or summary.get("supported", 0) < 1:
        raise SystemTestFailure("The report does not contain the expected completed Claim–Paper outcome.")

    entries = ((report.get("referenceResolution") or {}).get("entries") or [])
    entry = next(
        (
            candidate
            for candidate in entries
            if candidate.get("doi") == "10.5555/papertrail.system-test.upload-report.2026"
        ),
        None,
    )
    if entry is None or entry.get("status") != "RESOLVED":
        raise SystemTestFailure("The report does not contain the resolved synthetic cited paper.")

    access = entry.get("citedPaperAccess") or {}
    if access.get("accessStatus") != "FULL_TEXT_AVAILABLE":
        raise SystemTestFailure("The synthetic cited-paper asset was not recorded as available full text.")
    asset_sha256 = access.get("contentSha256")
    if not isinstance(asset_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", asset_sha256):
        raise SystemTestFailure("The cited-paper access report is missing its content hash.")
    if asset_sha256 != source_sha256:
        raise SystemTestFailure("The source and fixture cited-paper asset hashes do not match the checked-in PDF.")
    evidence_indexing = access.get("evidenceIndexing") or {}
    asset_id = evidence_indexing.get("assetId")
    if evidence_indexing.get("status") != "COMPLETED" or not asset_id:
        raise SystemTestFailure("The cited-paper access report is missing its completed evidence asset provenance.")
    if evidence_indexing.get("contentSha256") != asset_sha256:
        raise SystemTestFailure("The indexed cited-paper asset hash does not match the access record.")

    outcomes = entry.get("verificationOutcomes") or []
    outcome = next(
        (
            candidate
            for candidate in outcomes
            if candidate.get("processingStatus") == "COMPLETED"
            and candidate.get("finalStatus") == "SUPPORTED"
        ),
        None,
    )
    if outcome is None:
        raise SystemTestFailure("The report does not contain the fixture's expected completed outcome.")
    if not outcome.get("atomicClaimId") or not outcome.get("claimText") or not outcome.get("citationMarkers"):
        raise SystemTestFailure("The Claim–Paper outcome is missing its claim or citation provenance.")

    passages = outcome.get("evidencePassages") or []
    passage = next(
        (
            candidate
            for candidate in passages
            if candidate.get("contentSha256") == asset_sha256
            and candidate.get("parserProvider") == "docling"
            and candidate.get("parserVersion") == "issue-55-fixture-v1"
            and candidate.get("evidenceJudgement") is not None
            and candidate["evidenceJudgement"].get("providerId") == "laya"
            and candidate["evidenceJudgement"].get("judgement") == "DIRECT_SUPPORT"
        ),
        None,
    )
    if passage is None or passage.get("sourceAssetId") != asset_id or not passage.get("id"):
        raise SystemTestFailure("The Claim–Paper outcome is not traceable to its cited-paper asset and evidence provenance.")
    return len(outcomes), outcome["finalStatus"]


def run_browser_upload(source_pdf: Path, web_origin: str, session: str, engine: str) -> str:
    browser_command(session, engine, "open", web_origin)
    wait_for_browser_text(session, engine, "Analysis Runs")
    browser_command(session, engine, "find", "role", "button", "click", "--name", "New Analysis Run", "--exact")
    wait_for_browser_text(session, engine, "Choose your services")
    for role, provider_id in LOCAL_TEST_PROVIDER_SELECTIONS.items():
        browser_command(session, engine, "select", f"#provider-{role}", provider_id)
    browser_command(session, engine, "find", "role", "button", "click", "--name", "Continue", "--exact")
    wait_for_browser_text(session, engine, "No external providers selected")
    browser_command(session, engine, "find", "role", "button", "click", "--name", "Continue", "--exact")
    wait_for_browser_text(session, engine, "Source Document PDF")
    browser_command(session, engine, "upload", "#source-file", str(source_pdf))
    wait_for_browser_text(session, engine, source_pdf.name)
    browser_command(
        session,
        engine,
        "find",
        "role",
        "button",
        "click",
        "--name",
        "Upload & start Analysis Run",
        "--exact",
    )
    wait_for_browser_link(session, engine, f"Open Analysis Run for {source_pdf.name}")
    browser_command(
        session,
        engine,
        "find",
        "role",
        "link",
        "click",
        "--name",
        f"Open Analysis Run for {source_pdf.name}",
        "--exact",
    )
    deadline = time.monotonic() + 60
    detail_url = ""
    while time.monotonic() < deadline:
        try:
            detail_url = browser_command(session, engine, "eval", "window.location.pathname")
            match = re.search(r"/analysis-runs/([0-9a-fA-F-]{36})", detail_url)
            if match is not None:
                return match.group(1)
        except SystemTestFailure:
            pass
        time.sleep(1)
    raise SystemTestFailure(
        "The workspace did not navigate to the created Analysis Run detail page "
        f"(observed path: {detail_url or '<unavailable>'})."
    )


def main() -> None:
    if not COMPOSE_FILE.is_file() or not PARSER_FIXTURE.is_file() or not SOURCE_FIXTURE.is_file():
        raise SystemTestFailure("The issue #55 Compose, parser, or PDF fixture is missing.")
    run_command(["docker", "compose", "version"], timeout=30)
    browser_version = run_command(["agent-browser", "--version"], timeout=30).stdout.strip()
    run_command(["agent-browser", "doctor"], timeout=60)

    ports = available_host_ports(3)
    subnet = free_test_subnet()
    project = f"papertrail-issue55-{uuid.uuid4().hex[:10]}"
    environment = os.environ.copy()
    environment.update(
        {
            "API_HOST_PORT": str(ports[0]),
            "WEB_HOST_PORT": str(ports[1]),
            "MINIO_HOST_PORT": str(ports[2]),
            "SYSTEM_TEST_SUBNET": str(subnet),
            "SYSTEM_TEST_API_IP": str(subnet.network_address + 10),
            "SYSTEM_TEST_WORKER_IP": str(subnet.network_address + 11),
        }
    )

    session = ""
    # File upload is required and the installed Lightpanda engine lacks that capability.
    browser_engine = os.environ.get("PAPER_T_RAIL_TEST_BROWSER", "chrome")
    if browser_engine not in {"lightpanda", "chrome"}:
        raise SystemTestFailure("PAPER_T_RAIL_TEST_BROWSER must be lightpanda or chrome.")

    with tempfile.TemporaryDirectory(prefix="paper-trail-issue55-") as temporary_directory:
        source_pdf = Path(temporary_directory) / "issue-55-source.pdf"
        run_command([sys.executable, str(PDF_GENERATOR), str(source_pdf)])
        if source_pdf.read_bytes() != SOURCE_FIXTURE.read_bytes():
            raise SystemTestFailure("The generated source PDF differs from the recorded synthetic cited-paper fixture.")
        source_sha256 = hashlib.sha256(source_pdf.read_bytes()).hexdigest()
        api_origin = f"http://127.0.0.1:{ports[0]}"
        web_origin = f"http://127.0.0.1:{ports[1]}"

        try:
            print(f"Starting isolated system-test project {project} ({browser_version}, {browser_engine}).", flush=True)
            compose_command(
                project,
                environment,
                "up",
                "--detach",
                "--build",
                "postgres",
                "redis",
                "minio",
                "minio-proxy",
                "parser-fixtures",
                timeout=1800,
            )
            wait_for_database_and_redis(project, environment)
            wait_for_http(
                f"http://127.0.0.1:{ports[2]}/minio/health/ready",
                "Isolated MinIO",
                120,
            )
            create_minio_bucket(ports[2])
            compose_command(
                project,
                environment,
                "--profile",
                "migration",
                "run",
                "--rm",
                "sqitch",
                "deploy",
                "db:pg://papertrail:system-test-only@postgres:5432/papertrail",
                timeout=600,
            )
            compose_command(
                project,
                environment,
                "up",
                "--detach",
                "--build",
                "api",
                "worker",
                "web",
                "system-test-proxy",
                timeout=1800,
            )
            wait_for_http(f"{api_origin}/api/v1/health", "Isolated API", 600)
            wait_for_http(web_origin, "Isolated web workspace", 600)

            session_result = run_command(
                [
                    "agent-browser",
                    "session",
                    "id",
                    "--scope",
                    "worktree",
                    "--prefix",
                    f"paper-trail-issue-55-{uuid.uuid4().hex[:8]}",
                ],
                timeout=30,
            )
            session = session_result.stdout.strip()
            if not session:
                raise SystemTestFailure("agent-browser did not create a named issue-specific browser session.")

            run_id = run_browser_upload(source_pdf, web_origin, session, browser_engine)
            try:
                run, report = wait_for_report(api_origin, run_id, timeout_seconds=300)
            except SystemTestFailure as error:
                if "did not reach a report-ready state" not in str(error):
                    raise
                fixture_result = compose_command(
                    project,
                    environment,
                    "exec",
                    "--no-TTY",
                    "parser-fixtures",
                    "python",
                    "-c",
                    "import json,urllib.request; print(json.dumps(json.load(urllib.request.urlopen('http://127.0.0.1:8070/calls'))))",
                    timeout=30,
                    check=False,
                )
                fixture_counts = json.loads(fixture_result.stdout.strip()) if fixture_result.returncode == 0 else {}
                worker_logs = compose_command(
                    project,
                    environment,
                    "logs",
                    "--no-color",
                    "--tail",
                    "80",
                    "worker",
                    timeout=30,
                    check=False,
                )
                error_types = sorted(set(re.findall(r'\"errorType\":\"([^\"]+)\"', worker_logs.stdout)))
                raise SystemTestFailure(
                    f"{error}; fixtureCalls={json.dumps(fixture_counts, sort_keys=True)} "
                    f"workerErrorTypes={json.dumps(error_types)}"
                ) from None
            parser_calls = compose_command(
                project,
                environment,
                "exec",
                "--no-TTY",
                "parser-fixtures",
                "python",
                "-c",
                "import json,urllib.request; print(json.dumps(json.load(urllib.request.urlopen('http://127.0.0.1:8070/calls'))))",
                timeout=30,
            )
            calls = json.loads(parser_calls.stdout.strip())
            worker_logs = compose_command(
                project,
                environment,
                "logs",
                "--no-color",
                "--tail",
                "80",
                "worker",
                timeout=30,
                check=False,
            )
            worker_error_types = sorted(set(re.findall(r'\"errorType\":\"([^\"]+)\"', worker_logs.stdout)))
            outcome_count, final_status = assert_report(run, report, source_sha256, calls, worker_error_types)
            if calls.get("grobid", 0) < 1 or calls.get("docling", 0) < 2:
                raise SystemTestFailure("The queued run did not exercise both deterministic parser fixtures.")

            wait_for_browser_text(session, browser_engine, source_pdf.name)

            print(
                "PASS issue #55: synthetic fixture upload through the real web flow reached COMPLETED; "
                f"report contains {outcome_count} Claim–Paper outcome(s), finalStatus={final_status}, "
                f"assetSha256={source_sha256[:12]}…, GROBID fixture calls={calls['grobid']}, "
                f"Docling fixture calls={calls['docling']}, System One fixture calls={calls['systemOne']}.",
                flush=True,
            )
        finally:
            original_failure_active = sys.exc_info()[0] is not None
            cleanup_errors: list[str] = []

            if session:
                try:
                    browser_close = run_command(
                        ["agent-browser", "--session", session, "--engine", browser_engine, "close"],
                        timeout=30,
                        check=False,
                    )
                    if browser_close.returncode != 0:
                        cleanup_errors.append("browser session close failed")
                except (OSError, subprocess.SubprocessError):
                    cleanup_errors.append("browser session close could not be verified")

            try:
                teardown = compose_command(
                    project,
                    environment,
                    "down",
                    "--volumes",
                    "--remove-orphans",
                    timeout=300,
                    check=False,
                )
                if teardown.returncode != 0:
                    cleanup_errors.append("Compose teardown failed")
            except (OSError, subprocess.SubprocessError):
                cleanup_errors.append("Compose teardown could not be verified")

            if shutil.which("docker"):
                try:
                    residual_containers = compose_command(
                        project, environment, "ps", "--all", "--quiet", timeout=30, check=False,
                    )
                    residual_networks = run_command(
                        ["docker", "network", "ls", "--filter", f"name={project}", "--format", "{{.Name}}"],
                        timeout=30,
                        check=False,
                    )
                    residual_volumes = run_command(
                        ["docker", "volume", "ls", "--filter", f"name={project}", "--format", "{{.Name}}"],
                        timeout=30,
                        check=False,
                    )
                    if any(result.returncode != 0 for result in (residual_containers, residual_networks, residual_volumes)):
                        cleanup_errors.append("Compose resource cleanup could not be verified")
                    if residual_containers.stdout.strip():
                        cleanup_errors.append("Compose containers remain")
                    if residual_networks.stdout.strip():
                        cleanup_errors.append("Compose networks remain")
                    if residual_volumes.stdout.strip():
                        cleanup_errors.append("Compose volumes remain")
                except (OSError, subprocess.SubprocessError):
                    cleanup_errors.append("Compose resource cleanup could not be verified")

            if cleanup_errors:
                message = "System-test cleanup incomplete: " + ", ".join(cleanup_errors)
                if original_failure_active:
                    print(f"WARNING {message}", file=sys.stderr)
                else:
                    raise SystemTestFailure(message)


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.SubprocessError, SystemTestFailure) as error:
        print(f"FAIL issue #55 system test: {error}", file=sys.stderr)
        raise SystemExit(1) from None
