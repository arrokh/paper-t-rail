"""Authenticated ASGI endpoint exposing pinned-tokenizer preflight counts."""

import json
from typing import Any

from laya_serve_preflight import PINNED_CONTEXT_TOKENS, PINNED_MODEL_ALIAS, measure_request_tokens

PREFLIGHT_PATH = "/v1/systemone/preflight"
MAX_PREFLIGHT_BODY_BYTES = 4 * 1024 * 1024
REQUIRED_QUESTION_IDS = {
    "judgement",
    "evidence_role",
    "directness",
    "claim_scope_match",
    "study_design_quality",
    "relevance",
}


class PreflightEndpoint:
    def __init__(self, app: Any, agent: Any) -> None:
        self.app = app
        self.agent = agent

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope.get("type") != "http" or scope.get("path") != PREFLIGHT_PATH:
            await self.app(scope, receive, send)
            return
        if scope.get("method") != "POST":
            await self._respond(send, 405, {"detail": "Method not allowed."})
            return

        body = await self._read_body(receive)
        if body is None:
            await self._respond(send, 413, {"detail": "Preflight request is too large."})
            return
        try:
            request = json.loads(body)
            counts = self._measure(request)
        except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError, KeyError):
            await self._respond(send, 422, {"detail": "Invalid System One preflight request."})
            return

        await self._respond(send, 200, {"tokenCounts": counts, "contextLimit": PINNED_CONTEXT_TOKENS})

    def _measure(self, request: Any) -> list[int]:
        if not isinstance(request, dict) or request.get("model") != PINNED_MODEL_ALIAS:
            raise ValueError("Unsupported model.")
        state = request.get("state")
        questions = request.get("questions")
        if not isinstance(state, dict) or set(state) - {"claim", "evidence", "section"}:
            raise ValueError("Invalid state.")
        if not isinstance(state.get("claim"), str) or not isinstance(state.get("evidence"), str):
            raise ValueError("Invalid state.")
        if "section" in state and not isinstance(state["section"], str):
            raise ValueError("Invalid state.")
        if not isinstance(questions, dict) or set(questions) != REQUIRED_QUESTION_IDS:
            raise ValueError("Invalid questions.")
        return measure_request_tokens(self.agent, state, questions)

    async def _read_body(self, receive: Any) -> bytes | None:
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return None
            body.extend(message.get("body", b""))
            if len(body) > MAX_PREFLIGHT_BODY_BYTES:
                return None
            if not message.get("more_body", False):
                return bytes(body)

    async def _respond(self, send: Any, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        await send({
            "type": "http.response.start",
            "status": status,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(body)).encode("ascii")),
                (b"cache-control", b"no-store"),
            ],
        })
        await send({"type": "http.response.body", "body": body})
