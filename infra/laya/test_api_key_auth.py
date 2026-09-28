import asyncio
import unittest

from api_key_auth import ApiKeyMiddleware


class ApiKeyAuthTest(unittest.TestCase):
    def test_authorized_request_reaches_the_application(self):
        app, middleware = self.make_middleware()

        status, body = self.request(middleware, path="/v1/systemone", authorization=b"Bearer secret")

        self.assertEqual(200, status)
        self.assertEqual(b"ok", body)
        self.assertEqual(1, app.calls)

    def test_missing_or_invalid_key_is_rejected_without_reaching_the_application(self):
        for authorization in (None, b"", b"Basic secret", b"Bearer wrong", (b"Bearer secret", b"Bearer wrong")):
            with self.subTest(authorization=authorization):
                app, middleware = self.make_middleware()

                status, body = self.request(middleware, path="/v1/systemone", authorization=authorization)

                self.assertEqual(401, status)
                self.assertEqual(b'{"detail":"Unauthorized"}', body)
                self.assertEqual(0, app.calls)

    def test_only_health_check_is_available_without_authentication(self):
        app, middleware = self.make_middleware()

        status, body = self.request(middleware, path="/health", authorization=None)

        self.assertEqual(200, status)
        self.assertEqual(b"ok", body)
        self.assertEqual(1, app.calls)

    def test_websocket_routes_are_also_protected(self):
        app, middleware = self.make_middleware()
        sent = []

        async def receive():
            return {"type": "websocket.connect"}

        async def send(message):
            sent.append(message)

        asyncio.run(middleware({"type": "websocket", "path": "/v1/systemone", "headers": []}, receive, send))

        self.assertEqual([{"type": "websocket.close", "code": 4401}], sent)
        self.assertEqual(0, app.calls)

    def make_middleware(self):
        app = CountingApp()
        return app, ApiKeyMiddleware(app, "secret")

    def request(self, middleware, path, authorization):
        if authorization is None:
            headers = []
        elif isinstance(authorization, tuple):
            headers = [(b"authorization", value) for value in authorization]
        else:
            headers = [(b"authorization", authorization)]
        scope = {"type": "http", "path": path, "headers": headers}
        sent = []

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message):
            sent.append(message)

        asyncio.run(middleware(scope, receive, send))
        status = next(message["status"] for message in sent if message["type"] == "http.response.start")
        body = b"".join(message.get("body", b"") for message in sent if message["type"] == "http.response.body")
        return status, body


class CountingApp:
    def __init__(self):
        self.calls = 0

    async def __call__(self, scope, receive, send):
        self.calls += 1
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})


if __name__ == "__main__":
    unittest.main()
