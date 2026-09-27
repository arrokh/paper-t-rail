#!/usr/bin/env python3
"""Run reproducible, local-only processing benchmarks for representative licensed PDFs."""

from __future__ import annotations

import hashlib
import json
import os
import re
import socket
import subprocess
import tempfile
import time
import uuid
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILE = ROOT / "infra" / "docker-compose.yml"
SCALE_TO_BYTES = {
    "B": 1,
    "kB": 1_000,
    "MB": 1_000_000,
    "GB": 1_000_000_000,
    "KiB": 1_024,
    "MiB": 1_048_576,
    "GiB": 1_073_741_824,
}
DOCUMENTS = (
    {
        "filename": "plosone-research-article.pdf",
        "url": "https://journals.plos.org/plosone/article/file?id=10.1371/journal.pone.0301382&type=printable",
        "sha256": "933f18e446243abddf324da189e8d97241c501d2193509c96196230c68fbde4e",
    },
    {
        "filename": "qucosa-dissertation.pdf",
        "url": "https://ul.qucosa.de/api/qucosa%3A100361/attachment/ATT-0/",
        "sha256": "9b12e029f1e4cfb51c297ed4f21dded6f56b3c9c553d689a519ac2db2da2995e",
    },
)


def command(*arguments: str, env: dict[str, str] | None = None, check: bool = True) -> str:
    result = subprocess.run(
        arguments,
        cwd=ROOT,
        env=env,
        check=False,
        capture_output=True,
        text=True,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(arguments)}\n{result.stdout}\n{result.stderr}"
        )
    return result.stdout.strip()


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


def download_documents(directory: Path) -> list[Path]:
    paths: list[Path] = []
    for document in DOCUMENTS:
        path = directory / document["filename"]
        command("curl", "--fail", "--location", "--silent", "--show-error", "--retry", "2", "--connect-timeout", "20", "--max-time", "240", document["url"], "--output", str(path))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != document["sha256"]:
            raise RuntimeError(f"SHA-256 mismatch for {document['filename']}: {digest}")
        paths.append(path)
    return paths


def docker_memory_bytes(value: str) -> int:
    match = re.fullmatch(r"([\d.]+)([A-Za-z]+)", value.strip())
    if match is None or match.group(2) not in SCALE_TO_BYTES:
        raise RuntimeError(f"Unrecognized Docker memory value: {value}")
    return int(float(match.group(1)) * SCALE_TO_BYTES[match.group(2)])


def docker_stats(project: str) -> dict[str, tuple[int, float]]:
    output = command(
        "docker",
        "stats",
        "--no-stream",
        "--format",
        "{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}",
    )
    stats: dict[str, tuple[int, float]] = {}
    prefix = f"{project}-"
    for line in output.splitlines():
        name, memory, cpu = line.split("\t")
        if not name.startswith(prefix):
            continue
        stats[name.removeprefix(prefix)] = (
            docker_memory_bytes(memory.split("/", maxsplit=1)[0]),
            float(cpu.removesuffix("%")),
        )
    return stats


def get_json(url: str) -> dict:
    with urlopen(url, timeout=10) as response:
        return json.load(response)


def compose_command(project: str, environment: dict[str, str], *arguments: str, check: bool = True) -> str:
    return command(
        "docker",
        "compose",
        "--env-file",
        os.devnull,
        "--project-name",
        project,
        "--file",
        str(COMPOSE_FILE),
        *arguments,
        env=environment,
        check=check,
    )


def wait_for_api(base_url: str) -> None:
    deadline = time.monotonic() + 180
    last_error = "no response"
    while time.monotonic() < deadline:
        try:
            if get_json(f"{base_url}/health").get("status") == "ok":
                return
        except Exception as error:  # API startup is asynchronous; report the terminal blocker below.
            last_error = type(error).__name__
        time.sleep(1)
    raise RuntimeError(f"API did not become healthy within 180 seconds ({last_error}).")


def page_count(project: str, environment: dict[str, str], document_id: str) -> int:
    database = environment["POSTGRES_DB"]
    user = environment["POSTGRES_USER"]
    query = f"select page_count from source_documents where id='{document_id}'"
    result = compose_command(
        project,
        environment,
        "exec",
        "--no-TTY",
        "postgres",
        "psql",
        "-U",
        user,
        "-d",
        database,
        "-Atc",
        query,
    )
    return int(result)


def benchmark_document(path: Path, api_url: str, project: str, environment: dict[str, str]) -> dict:
    baseline = docker_stats(project)
    samples = [baseline]
    started = time.monotonic()
    response = command(
        "curl",
        "--fail",
        "--silent",
        "--show-error",
        "--write-out",
        "\n__PAPER_T_RAIL_UPLOAD_SECONDS__%{time_total}",
        "--form",
        f"file=@{path};type=application/pdf",
        f"{api_url}/analysis-runs",
    )
    body, upload_seconds = response.rsplit("\n__PAPER_T_RAIL_UPLOAD_SECONDS__", maxsplit=1)
    created = json.loads(body)
    uploaded = time.monotonic()
    run_id = created["analysisRunId"]
    terminal: dict | None = None
    deadline = uploaded + 900

    while time.monotonic() < deadline:
        samples.append(docker_stats(project))
        current = get_json(f"{api_url}/analysis-runs/{run_id}")
        if current["status"] in {"PARSED", "COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED"}:
            terminal = current
            break
        time.sleep(0.5)

    finished = time.monotonic()
    if terminal is None:
        raise RuntimeError(f"Analysis Run {run_id} did not reach a terminal status within 900 seconds.")
    if terminal["status"] not in {"PARSED", "COMPLETED", "COMPLETED_WITH_WARNINGS"}:
        raise RuntimeError(f"{path.name} failed processing: {terminal.get('failureReason')}")

    parsed = get_json(f"{api_url}/analysis-runs/{run_id}/parsed-document")
    contexts = parsed["citationContexts"]
    claims = [claim for context in contexts for claim in context["atomicClaims"]]
    pair_count = sum(len(claim["citationTargets"]) for claim in claims)
    resource_peaks: dict[str, dict[str, float]] = {}
    names = {name for sample in samples for name in sample}
    for name in sorted(names):
        observed = [sample[name] for sample in samples if name in sample]
        baseline_bytes = baseline.get(name, (0, 0.0))[0]
        peak_bytes = max(value[0] for value in observed)
        resource_peaks[name] = {
            "baseline_memory_mib": round(baseline_bytes / 1_048_576, 1),
            "peak_memory_mib": round(peak_bytes / 1_048_576, 1),
            "memory_increase_mib": round((peak_bytes - baseline_bytes) / 1_048_576, 1),
            "maximum_sampled_cpu_percent": round(max(value[1] for value in observed), 1),
        }

    return {
        "filename": path.name,
        "upload_bytes": path.stat().st_size,
        "pages": page_count(project, environment, created["documentId"]),
        "citation_occurrences": sum(len(context["occurrences"]) for context in contexts),
        "bibliography_entries": len(parsed["bibliographyEntries"]),
        "atomic_claims": len(claims),
        "claim_citation_pairs": pair_count,
        "upload_seconds": round(float(upload_seconds), 3),
        "upload_to_parsed_seconds": round(finished - uploaded, 3),
        "total_seconds": round(finished - started, 3),
        "terminal_status": terminal["status"],
        "container_resource_peaks": resource_peaks,
    }


def main() -> None:
    ports = available_host_ports(5)
    environment = os.environ.copy()
    environment.update(
        {
            "API_HOST_PORT": str(ports[0]),
            "POSTGRES_HOST_PORT": str(ports[1]),
            "POSTGRES_DB": "papertrail_benchmark",
            "POSTGRES_USER": "papertrail",
            "POSTGRES_PASSWORD": "benchmark-local-only",
            "REDIS_HOST_PORT": str(ports[2]),
            "S3_HOST_PORT": str(ports[3]),
            "S3_CONSOLE_HOST_PORT": str(ports[4]),
            "REDIS_PASSWORD": "benchmark-local-only",
            "S3_ACCESS_KEY": "benchmark-local-only",
            "S3_SECRET_KEY": "benchmark-local-only-secret",
            "S3_BUCKET": "benchmark-source-documents",
            "GROBID_IMAGE": "grobid/grobid:0.9.1-crf",
            "GROBID_PARSER_VERSION": "0.9.1-crf",
            "GROBID_BASE_URL": "http://grobid:8070",
            "OLLAMA_IMAGE": "ollama/ollama:0.34.4",
            "OLLAMA_MODEL": "nomic-embed-text:v1.5",
            "OLLAMA_ENABLED": "false",
            "OLLAMA_BASE_URL": "http://ollama:11434",
            "OLLAMA_DIMENSION": "768",
            "OLLAMA_API_KEY": "",
            "OLLAMA_TRUSTED_HOSTS": "ollama,localhost,127.0.0.1",
            "OLLAMA_REQUEST_TIMEOUT_MILLIS": "60000",
            "OLLAMA_EXTERNAL_ENABLEMENT_REVIEWED": "false",
            "OLLAMA_EXTERNAL_RETENTION_DISCLOSURE": "benchmark-disabled",
            "CROSSREF_ENABLED": "false",
            "CROSSREF_ENABLEMENT_REVIEWED": "false",
            "CROSSREF_RETENTION_DISCLOSURE": "benchmark-disabled",
            "CROSSREF_CONTACT_EMAIL": "benchmark@example.invalid",
            "UNPAYWALL_ENABLED": "false",
            "UNPAYWALL_ENABLEMENT_REVIEWED": "false",
            "UNPAYWALL_RETENTION_DISCLOSURE": "benchmark-disabled",
            "UNPAYWALL_CONTACT_EMAIL": "benchmark@example.invalid",
            "PAPER_REFERENCE_RESOLUTION_CONFIDENCE_THRESHOLD": "0.9",
            "PAPER_RETRIEVAL_PROFILE_ID": "postgres-hybrid-rrf-v1",
            "PAPER_RETRIEVAL_VECTOR_CANDIDATES": "10",
            "PAPER_RETRIEVAL_LEXICAL_CANDIDATES": "10",
            "PAPER_RETRIEVAL_FINAL_CANDIDATES": "5",
            "PAPER_RETRIEVAL_RRF_CONSTANT": "60",
            "HOSTNAME": "paper-t-rail-benchmark",
            "PAPER_MAX_UPLOAD_BYTES": "52428800",
            "PAPER_MAX_REQUEST_SIZE": "51MB",
            "PAPER_MAX_PAGES": "500",
            "PAPER_MAX_CLAIM_CITATION_PAIRS": "5000",
            "PAPER_MAX_EXTRACTED_CHARACTERS": "5000000",
            "PAPER_MAX_EXTRACTED_CHARACTERS_PER_PAGE": "100000",
            "PAPER_MAX_GROBID_RESPONSE_BYTES": "67108864",
            "PAPER_MIN_EXTRACTED_CHARACTERS": "100",
            "PAPER_MIN_LANGUAGE_CONFIDENCE": "0.65",
        }
    )
    project = f"paper-trail-benchmark-issue12-{uuid.uuid4().hex[:8]}"
    api_url = f"http://127.0.0.1:{ports[0]}/api/v1"
    started_compose = False

    try:
        with tempfile.TemporaryDirectory(prefix="paper-trail-benchmark-") as temporary_directory:
            documents = download_documents(Path(temporary_directory))
            started_compose = True
            compose_command(
                project,
                environment,
                "--profile",
                "migration",
                "run",
                "--rm",
                "sqitch",
                "deploy",
                f"db:pg://{environment['POSTGRES_USER']}:{environment['POSTGRES_PASSWORD']}@postgres:5432/{environment['POSTGRES_DB']}",
            )
            compose_command(project, environment, "up", "--detach", "--build", "api", "worker")
            wait_for_api(api_url)
            for document in documents:
                print(json.dumps(benchmark_document(document, api_url, project, environment), indent=2), flush=True)
    finally:
        if started_compose:
            compose_command(project, environment, "down", "--volumes", "--remove-orphans", check=False)
            for service in ("api", "worker"):
                command("docker", "image", "rm", f"{project}-{service}", check=False)


if __name__ == "__main__":
    main()
