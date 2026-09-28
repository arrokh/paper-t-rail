import unittest

from laya_serve_preflight import measure_request_tokens, install_context_guard


class FakeTokenizer:
    mask_token = "[MASK]"
    mask_token_id = 999

    def __call__(self, text, *, add_special_tokens=False, **_kwargs):
        # Deterministic stand-in for the pinned tokenizer. Punctuation and words
        # each count as one token so question/state boundary cases are testable.
        import re

        return {"input_ids": re.findall(r"\w+|[^\w\s]", text)}


class FakeAgent:
    cfg = {"max_len": 1024, "head_max_len": 256}
    tok = FakeTokenizer()

    def _check_question(self, question_id, definition):
        if not definition.get("instructions"):
            raise ValueError("invalid question")

    def _to_internal(self, definition):
        return {
            "t": definition["type"],
            "ins": definition["instructions"],
            "crit": definition.get("criteria"),
        }


class FakeRouter:
    def __init__(self):
        self.agent = FakeAgent()
        self.calls = []

    def load(self, model):
        self.asserted_model = model
        return self.agent

    def predict(self, state, questions, model=None):
        self.calls.append((state, questions, model))
        return {"answers": {}, "usage": {"input_tokens": 0, "output_tokens": 0}}


class LayaContextGuardTest(unittest.TestCase):
    def setUp(self):
        self.router = FakeRouter()
        install_context_guard(self.router)
        self.questions = {
            "judgement": {"type": "choice", "instructions": "Assess the passage.", "criteria": {"a": "direct support", "b": "no support"}},
            "relevance": {"type": "score", "instructions": "Rate relevance.", "criteria": ["low", "high"]},
        }

    def test_complete_sequence_at_1024_tokens_is_allowed(self):
        base_tokens = measure_request_tokens(self.router.agent, "", self.questions)[0]
        state = "word " * (1024 - base_tokens)

        self.router.predict(state, self.questions, model="typed-decisions")

        self.assertEqual(1, len(self.router.calls))
        self.assertEqual("typed-decisions", self.router.asserted_model)

    def test_oversized_sequence_is_rejected_before_inference(self):
        base_tokens = measure_request_tokens(self.router.agent, "", self.questions)[0]
        state = "word " * (1025 - base_tokens)

        with self.assertRaisesRegex(ValueError, "1024-token context limit"):
            self.router.predict(state, self.questions, model="typed-decisions")

        self.assertEqual([], self.router.calls)

    def test_every_question_is_measured_against_the_complete_state(self):
        questions = dict(self.questions)
        questions["long_question"] = {
            "type": "choice",
            "instructions": "Assess " + "long " * 300,
            "criteria": {"a": "yes", "b": "no"},
        }

        with self.assertRaisesRegex(ValueError, "would be truncated"):
            self.router.predict("short state", questions, model="typed-decisions")

        self.assertEqual([], self.router.calls)

    def test_only_the_explicit_candidate_checkpoint_can_be_served(self):
        with self.assertRaisesRegex(ValueError, "explicit typed-decisions checkpoint"):
            self.router.predict("small state", self.questions)

        self.assertEqual([], self.router.calls)

    def test_runtime_context_must_match_the_pinned_checkpoint(self):
        router = FakeRouter()
        router.agent.cfg = {"max_len": 512}

        with self.assertRaisesRegex(RuntimeError, "pinned checkpoint context"):
            install_context_guard(router)

        self.assertEqual([], router.calls)


if __name__ == "__main__":
    unittest.main()
