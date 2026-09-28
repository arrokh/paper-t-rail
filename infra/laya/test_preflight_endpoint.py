import json
import unittest

from api_key_auth import ApiKeyMiddleware
from laya_serve_preflight import install_context_guard
from preflight_endpoint import PREFLIGHT_PATH, PreflightEndpoint, REQUIRED_QUESTION_IDS
from test_context_guard import FakeRouter


class PreflightEndpointTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.router = FakeRouter()
        agent = install_context_guard(self.router)
        self.endpoint = PreflightEndpoint(self.downstream, agent)

    async def downstream(self, scope, receive, send):
        raise AssertionError("Preflight requests must not reach the inference app.")

    async def request(self, payload, *, path=PREFLIGHT_PATH, method="POST", app=None, authorization=None):
        raw = json.dumps(payload).encode("utf-8")
        messages = [{"type": "http.request", "body": raw, "more_body": False}]
        sent = []

        async def receive():
            return messages.pop(0)

        async def send(message):
            sent.append(message)

        headers = [] if authorization is None else [(b"authorization", authorization.encode("utf-8"))]
        await (app or self.endpoint)(
            {"type": "http", "path": path, "method": method, "headers": headers},
            receive,
            send,
        )
        return sent

    def valid_request(self):
        return {
            "model": "typed-decisions",
            "state": {"claim": "claim", "evidence": "evidence", "section": "Results"},
            "questions": {
                question_id: {"type": "choice", "instructions": question_id, "criteria": {"a": "yes", "b": "no"}}
                for question_id in REQUIRED_QUESTION_IDS
            },
        }

    async def test_preflight_route_is_protected_by_the_laya_api_key(self):
        request = self.valid_request()
        request["state"]["claim"] = "private claim text"
        request["state"]["evidence"] = "private evidence text"
        app = ApiKeyMiddleware(self.endpoint, "test-sidecar-key")

        result = await self.request(request, app=app)
        response = json.loads(result[-1]["body"])

        self.assertEqual(401, result[0]["status"])
        self.assertNotIn("private claim text", json.dumps(response))
        self.assertNotIn("private evidence text", json.dumps(response))
        self.assertEqual([], self.router.calls)

    async def test_reports_all_six_pinned_token_sequences_without_inference(self):
        request = self.valid_request()
        result = await self.request(request)
        response = json.loads(result[-1]["body"])

        self.assertEqual(200, result[0]["status"])
        self.assertEqual(1024, response["contextLimit"])
        self.assertEqual(6, len(response["tokenCounts"]))
        self.assertTrue(all(count <= 1024 for count in response["tokenCounts"]))
        self.assertEqual([], self.router.calls)

    async def test_rejects_missing_question_sequence_without_echoing_evidence(self):
        request = self.valid_request()
        request["state"]["evidence"] = "private evidence text"
        request["questions"].pop("relevance")

        result = await self.request(request)
        response = json.loads(result[-1]["body"])

        self.assertEqual(422, result[0]["status"])
        self.assertNotIn("private evidence text", json.dumps(response))
        self.assertEqual([], self.router.calls)

    async def test_reports_over_limit_counts_without_invoking_inference(self):
        request = self.valid_request()
        request["state"]["evidence"] = "evidence " * 2500

        result = await self.request(request)
        response = json.loads(result[-1]["body"])

        self.assertEqual(200, result[0]["status"])
        self.assertTrue(any(count > 1024 for count in response["tokenCounts"]))
        self.assertEqual([], self.router.calls)


if __name__ == "__main__":
    unittest.main()
