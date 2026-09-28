"""Fail closed when the complete Laya state/question sequence exceeds its limits.

This adapter targets the pinned Laya 0.3.20 Agent tokenizer/sequence format. Keep
its rendering logic in sync with that source revision and contract-test it before
changing the runtime pin.
"""

import json
from typing import Any

PINNED_MODEL_ALIAS = "typed-decisions"
PINNED_CONTEXT_TOKENS = 1024
MAX_OPTION_TOKENS = 48
MIN_OPTION_BUDGET = 16


def install_context_guard(router: Any) -> None:
    """Reject requests before Router inference if any full sequence exceeds 1024 tokens."""
    agent = router.load(PINNED_MODEL_ALIAS)
    context_limit = int(agent.cfg.get("max_len", 0))
    if context_limit != PINNED_CONTEXT_TOKENS:
        raise RuntimeError("Laya runtime does not match the pinned checkpoint context length.")

    original_predict = router.predict

    def guarded_predict(state: Any, questions: dict[str, dict[str, Any]], model: str | None = None):
        if model != PINNED_MODEL_ALIAS:
            raise ValueError("System One requests require the explicit typed-decisions checkpoint.")
        token_counts = measure_request_tokens(agent, state, questions)
        if any(count > context_limit for count in token_counts):
            raise ValueError("Complete state and questions exceed the 1024-token context limit.")
        return original_predict(state, questions, model=model)

    router.predict = guarded_predict


def measure_request_tokens(agent: Any, state: Any, questions: dict[str, dict[str, Any]]) -> list[int]:
    """Count each exact Laya sequence using the checkpoint tokenizer, without truncation."""
    tokenizer = agent.tok
    context_limit = int(agent.cfg.get("max_len", 0))
    head_limit = int(agent.cfg.get("head_max_len", 192))
    if context_limit != PINNED_CONTEXT_TOKENS or head_limit <= 0:
        raise RuntimeError("Laya runtime does not match the pinned checkpoint token limits.")

    state_text = state if isinstance(state, str) else json.dumps(state, ensure_ascii=False)
    state_tokens = len(tokenizer(state_text.replace(tokenizer.mask_token, " "), add_special_tokens=False)["input_ids"])
    token_counts = []
    for question_id, definition in questions.items():
        agent._check_question(question_id, definition)
        question = agent._to_internal(definition)
        options = _render_options(question)
        option_lengths = [
            1 + len(tokenizer(" " + option.replace(tokenizer.mask_token, " "), add_special_tokens=False)["input_ids"])
            for option in options
        ]
        if any(length - 1 > MAX_OPTION_TOKENS for length in option_lengths):
            raise ValueError("A complete System One question option would be truncated by Laya.")

        option_tokens = sum(option_lengths)
        option_budget = head_limit - option_tokens
        if option_budget < MIN_OPTION_BUDGET:
            per_option_limit = max(4, (head_limit - MIN_OPTION_BUDGET) // max(1, len(options)))
            if any(length > per_option_limit for length in option_lengths):
                raise ValueError("Complete System One question options would be truncated by Laya.")
            option_budget = head_limit - option_tokens
        if option_budget < MIN_OPTION_BUDGET:
            raise ValueError("Complete System One question options exceed Laya's question token budget.")

        instructions = str(question["ins"]).replace(tokenizer.mask_token, " ")
        head_text = "%s question: %s" % (question["t"], instructions)
        head_tokens = len(tokenizer(head_text, add_special_tokens=False)["input_ids"])
        if head_tokens > max(8, option_budget):
            raise ValueError("Complete System One question instructions would be truncated by Laya.")

        # Matches laya.common.build_sequence: CLS + question + SEP + option
        # markers/options + SEP + full state + SEP. Do not rely on its default
        # truncation, which would otherwise hide an over-limit input.
        token_counts.append(1 + head_tokens + 1 + option_tokens + 1 + state_tokens + 1)
    return token_counts


def _render_options(question: dict[str, Any]) -> list[str]:
    """Render the choice/score options supported by this provider's fixed questions."""
    question_type = question["t"]
    criteria = question.get("crit")
    if question_type == "choice" and isinstance(criteria, dict):
        return [
            str(label) if value is None or value == "" else "%s: %s" % (label, _render_criterion(value))
            for label, value in criteria.items()
        ]
    if question_type == "score" and isinstance(criteria, list):
        return ["level %d: %s" % (index, _render_criterion(value)) for index, value in enumerate(criteria)]
    raise ValueError("Laya System One supports only the configured choice and score question shapes.")


def _render_criterion(value: Any) -> str:
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, separators=(", ", ": "), default=str)
