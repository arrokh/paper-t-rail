#!/usr/bin/env python3
"""Serve deterministic parser and System One API responses for the isolated system test."""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from threading import Lock, Thread

DOI = "10.5555/papertrail.system-test.upload-report.2026"
TITLE = "Synthetic upload-to-report fixture study"

TEI = f"""<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<TEI xmlns=\"http://www.tei-c.org/ns/1.0\" xmlns:xml=\"http://www.w3.org/XML/1998/namespace\">
  <text>
    <body><div><head>Results</head><p>Conservative reference resolution prevents ambiguous citations from being assigned unsupported paper identities <ref type=\"bibr\" target=\"#fixture-reference\">[1]</ref>.</p></div></body>
    <back><listBibl><biblStruct xml:id=\"fixture-reference\">
      <analytic><title level=\"a\">{TITLE}</title>
        <author><persName><forename>Riley</forename><surname>Example</surname></persName></author>
        <author><persName><forename>Jordan</forename><surname>Researcher</surname></persName></author>
      </analytic>
      <monogr><title level=\"j\">Synthetic Journal</title><imprint><date when=\"2026\"/></imprint></monogr>
      <idno type=\"DOI\">{DOI}</idno>
    </biblStruct></listBibl></back>
  </text>
</TEI>
""".encode("utf-8")

DOCLING_RESPONSE = json.dumps(
    {
        "status": "success",
        "document": {
            "md_content": (
                f"# {TITLE}\n\n"
                "Riley Example and Jordan Researcher\n\n"
                f"DOI: {DOI}\n\n"
                "## Results\n\n"
                "Conservative reference resolution prevents ambiguous citations from being assigned "
                "unsupported paper identities.\n\n"
                "This synthetic passage exercises the local report plumbing only."
            ),
            "json_content": {
                "texts": [
                    {
                        "self_ref": "#/texts/0",
                        "label": "title",
                        "text": TITLE,
                        "prov": [{"page_no": 1, "charspan": [0, len(TITLE)]}],
                    },
                    {
                        "self_ref": "#/texts/1",
                        "label": "text",
                        "text": "Riley Example and Jordan Researcher",
                        "prov": [{"page_no": 1, "charspan": [0, 36]}],
                    },
                    {
                        "self_ref": "#/texts/2",
                        "label": "text",
                        "text": f"DOI: {DOI}",
                        "prov": [{"page_no": 1, "charspan": [0, len(DOI) + 5]}],
                    },
                ]
            },
        },
    },
    separators=(",", ":"),
).encode("utf-8")

JUDGEMENT_VALUES = ("DIRECT_SUPPORT", "PARTIAL_SUPPORT", "CONTRADICTS", "UNRELATED", "INSUFFICIENT")
ROLE_VALUES = ("PRIMARY_FINDING", "AUTHOR_SYNTHESIS", "SECONDARY_REPORT")
SCORE_LEVELS = ("none", "low", "moderate", "high", "complete")


def one_hot(selected: str, values: tuple[str, ...]) -> dict[str, float]:
    return {value: float(value == selected) for value in values}


def choice_answer(selected: str, values: tuple[str, ...], *, judgement: bool = False) -> dict:
    answer = {
        "type": "choice",
        "choice": selected,
        "probabilities": one_hot(selected, values),
        "confidence": 1.0,
    }
    if judgement:
        answer["answer_confidence"] = 1.0
    return answer


def score_answer() -> dict:
    return {
        "type": "score",
        "score": float(len(SCORE_LEVELS) - 1),
        "legend": {str(index): level for index, level in enumerate(SCORE_LEVELS)},
        "probabilities": one_hot(str(len(SCORE_LEVELS) - 1), tuple(str(index) for index in range(len(SCORE_LEVELS)))),
        "confidence": 1.0,
    }


SYSTEM_ONE_RESPONSE = json.dumps(
    {
        "model": "laya-rl-agent",
        "routing": {"model": "typed-decisions", "reason": "synthetic system-test fixture"},
        "answers": {
            "judgement": choice_answer("DIRECT_SUPPORT", JUDGEMENT_VALUES, judgement=True),
            "evidence_role": choice_answer("PRIMARY_FINDING", ROLE_VALUES),
            "directness": score_answer(),
            "claim_scope_match": score_answer(),
            "study_design_quality": score_answer(),
            "relevance": score_answer(),
        },
    },
    separators=(",", ":"),
).encode("utf-8")
SYSTEM_ONE_PREFLIGHT_RESPONSE = json.dumps(
    {"contextLimit": 1024, "tokenCounts": [64, 64, 64, 64, 64, 64]},
    separators=(",", ":"),
).encode("utf-8")
SYSTEM_ONE_FIXTURE_AUTHORIZATION = "Bearer system-test-fixture-only"

call_counts = {"grobid": 0, "docling": 0, "systemOne": 0, "systemOnePreflight": 0}
request_counts: dict[str, int] = {}
call_lock = Lock()


class FixtureHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        self._record_request()
        path = urlsplit(self.path).path
        if path == "/api/isalive":
            self._send(200, b"true", "text/plain")
        elif path == "/ready":
            self._send(200, b"ready", "text/plain")
        elif path == "/calls":
            with call_lock:
                body = json.dumps(
                    {**call_counts, "requests": request_counts},
                    separators=(",", ":"),
                ).encode("utf-8")
            self._send(200, body, "application/json")
        else:
            self._send(404, b"not found", "text/plain")

    def do_POST(self) -> None:
        self._record_request()
        path = urlsplit(self.path).path
        if not self._discard_request_body():
            self._send(400, b"request body required", "text/plain")
            return
        if path in {"/v1/systemone", "/v1/systemone/preflight"}:
            if self.headers.get("Authorization") != SYSTEM_ONE_FIXTURE_AUTHORIZATION:
                self._send(401, b"unauthorized", "text/plain")
                return
            if path == "/v1/systemone/preflight":
                with call_lock:
                    call_counts["systemOnePreflight"] += 1
                self._send(200, SYSTEM_ONE_PREFLIGHT_RESPONSE, "application/json")
            else:
                with call_lock:
                    call_counts["systemOne"] += 1
                self._send(200, SYSTEM_ONE_RESPONSE, "application/json")
        elif path == "/api/processFulltextDocument":
            with call_lock:
                call_counts["grobid"] += 1
            self._send(200, TEI, "application/tei+xml; charset=utf-8")
        elif path == "/v1/convert/file":
            with call_lock:
                call_counts["docling"] += 1
            self._send(200, DOCLING_RESPONSE, "application/json")
        else:
            self._send(404, b"not found", "text/plain")

    def _record_request(self) -> None:
        path = urlsplit(self.path).path
        key = f"{self.command} {path}"
        with call_lock:
            request_counts[key] = request_counts.get(key, 0) + 1

    def _discard_request_body(self) -> bool:
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if "chunked" in transfer_encoding:
            while True:
                size_line = self.rfile.readline()
                if not size_line:
                    return False
                try:
                    size = int(size_line.split(b";", 1)[0].strip(), 16)
                except ValueError:
                    return False
                if size == 0:
                    while True:
                        trailer = self.rfile.readline()
                        if not trailer or trailer in {b"\x0d\x0a", b"\x0a"}:
                            return bool(trailer)
                remaining = size
                while remaining > 0:
                    chunk = self.rfile.read(min(remaining, 8192))
                    if not chunk:
                        return False
                    remaining -= len(chunk)
                if self.rfile.read(2) != b"\x0d\x0a":
                    return False
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return False
        remaining = length
        while remaining > 0:
            chunk = self.rfile.read(min(remaining, 8192))
            if not chunk:
                return False
            remaining -= len(chunk)
        return True

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        # Never log request bodies or query strings; the API sends PDFs to these local endpoints.
        print(f"parser-fixtures {self.server.server_port} {urlsplit(self.path).path}", flush=True)


def main() -> None:
    servers = [ThreadingHTTPServer(("0.0.0.0", port), FixtureHandler) for port in (8070, 5001, 8000)]
    threads = [Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()
    try:
        for thread in threads:
            thread.join()
    except KeyboardInterrupt:
        pass
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
