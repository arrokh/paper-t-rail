"""Small ASGI bearer-token guard for every Laya route except its local health check."""

import hmac
import json
from typing import Any


class ApiKeyMiddleware:
    def __init__(self, app: Any, api_key: str) -> None:
        if not api_key:
            raise ValueError("Laya API key must not be blank.")
        self.app = app
        self.expected_authorization = ("Bearer " + api_key).encode("utf-8")

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        scope_type = scope.get("type")
        if scope_type == "lifespan":
            await self.app(scope, receive, send)
            return
        if scope_type == "http" and scope.get("path") == "/health":
            await self.app(scope, receive, send)
            return
        if self._is_authorized(scope):
            await self.app(scope, receive, send)
            return
        if scope_type == "websocket":
            await send({"type": "websocket.close", "code": 4401})
            return
        if scope_type != "http":
            return

        body = json.dumps({"detail": "Unauthorized"}, separators=(",", ":")).encode("utf-8")
        await send({
            "type": "http.response.start",
            "status": 401,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(body)).encode("ascii")),
                (b"cache-control", b"no-store"),
            ],
        })
        await send({"type": "http.response.body", "body": body})

    def _is_authorized(self, scope: dict[str, Any]) -> bool:
        authorizations = [
            value for name, value in scope.get("headers", []) if name.lower() == b"authorization"
        ]
        return len(authorizations) == 1 and hmac.compare_digest(
            authorizations[0], self.expected_authorization,
        )
