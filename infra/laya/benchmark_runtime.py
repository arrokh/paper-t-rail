"""One-pair synthetic latency smoke test for the local laya-serve sidecar."""

import json
import os
import time
import urllib.request

BASE_URL = os.environ.get("LAYA_BASE_URL", "http://127.0.0.1:8000").rstrip("/")
API_KEY = os.environ["LAYA_API_KEY"]

STATE = {
    "claim": "In adults with condition A, intervention B reduced the measured outcome compared with usual care over twelve weeks.",
    "section": "Results",
    "evidence": "In this randomized, parallel-group study, 184 adults with condition A were assigned to intervention B or usual care. Participants receiving intervention B followed the specified treatment protocol for twelve weeks, while the comparison group continued usual care. The primary outcome was measured at baseline and at the end of follow-up using the same prespecified instrument in both groups. The analysis included participants according to their assigned group, and missing outcome measurements were described separately. At twelve weeks, the mean outcome score in the intervention group was lower than the mean score in the usual-care group. The adjusted between-group difference was reported with a confidence interval that excluded no difference, and the authors described the result as statistically significant. The direction of the difference was consistent in the prespecified sensitivity analysis. A secondary analysis restricted to participants who completed all visits produced a similar estimate, although that analysis was not the primary basis for the conclusion. The study did not include participants without condition A, did not compare intervention B with other active treatments, and did not follow participants beyond twelve weeks. The authors noted that the sample came from a limited number of clinical sites and that the findings may not apply to populations with different baseline characteristics. No outcome data were reported for people who did not meet the study's eligibility criteria. The paper presents this result as evidence about the enrolled adult population under the study conditions; it does not claim that every subgroup experienced the same magnitude of change. The analysis plan identified the primary outcome before the trial results were examined. Allocation was concealed during enrollment, and outcome assessors were unaware of group assignment. Participants and treating staff could not be blinded because the interventions were visibly different. The report describes the number of withdrawals in each group and gives reasons when known. It also reports that no serious adverse event was judged related to intervention B during the twelve-week observation period. The authors caution that the study was not large enough to rule out uncommon harms and recommend replication with longer follow-up. These details describe the study's own comparison and do not refer to a result from another paper."
}

SCORE_LABELS = ["none", "low", "moderate", "high", "complete"]
QUESTIONS = {
    "judgement": {
        "type": "choice",
        "instructions": "How does the evidence passage relate to the atomic claim, considering every material qualifier?",
        "criteria": {
            "DIRECT_SUPPORT": "The passage directly reports evidence supporting the claim and its material qualifiers.",
            "PARTIAL_SUPPORT": "The passage supports only part of the claim or misses a material qualifier.",
            "CONTRADICTS": "The passage reports evidence that materially conflicts with the claim.",
            "UNRELATED": "The passage has no material bearing on the claim.",
            "INSUFFICIENT": "The passage is ambiguous or does not permit a supported judgement.",
        },
    },
    "evidence_role": {
        "type": "choice",
        "instructions": "What is the evidentiary role of this passage within the cited paper?",
        "criteria": {
            "PRIMARY_FINDING": "The cited paper's own methods, data, or results report this finding.",
            "AUTHOR_SYNTHESIS": "The cited paper's authors interpret, summarize, or synthesize evidence across works.",
            "SECONDARY_REPORT": "The passage attributes a finding to another cited work rather than reporting this paper's own result.",
        },
    },
    "directness": {
        "type": "score",
        "instructions": "How directly does the passage answer the atomic claim? Use this ordered scale: 0=No evidence addresses the claim.; 1=Only an indirect or weak connection is present.; 2=The passage is relevant but does not directly answer the claim.; 3=The passage directly addresses most of the claim.; 4=The passage directly reports evidence for the complete claim.",
        "criteria": SCORE_LABELS,
    },
    "claim_scope_match": {
        "type": "score",
        "instructions": "How closely does the passage match the claim's population, conditions, outcome, and other material qualifiers? Use this ordered scale: 0=The population, conditions, or outcome do not match.; 1=A major material qualifier is missing or conflicts.; 2=The core claim matches, but at least one material qualifier is uncertain.; 3=The claim scope and nearly all material qualifiers match.; 4=The population, conditions, outcome, and material qualifiers match.",
        "criteria": SCORE_LABELS,
    },
    "study_design_quality": {
        "type": "score",
        "instructions": "How strong is the study design described in the passage for assessing this claim? Use this ordered scale: 0=No study design or method is described.; 1=The described design provides very weak evidence for this claim.; 2=The design provides limited or observational evidence.; 3=The design provides reasonably strong evidence for this claim.; 4=The design is rigorous and directly suited to assess this claim.",
        "criteria": SCORE_LABELS,
    },
    "relevance": {
        "type": "score",
        "instructions": "How relevant is the passage to the atomic claim? Use this ordered scale: 0=The passage is unrelated to the claim.; 1=The passage has only a slight topical connection.; 2=The passage is relevant but only partly addresses the claim.; 3=The passage is strongly relevant to the claim.; 4=The passage is directly and fully relevant to the claim.",
        "criteria": SCORE_LABELS,
    },
}


def process_memory_mib(field: str) -> float:
    with open("/proc/1/status", encoding="utf-8") as status:
        for line in status:
            if line.startswith(field + ":"):
                return int(line.split()[1]) / 1024
    raise RuntimeError("Laya sidecar process memory data is unavailable.")


def main() -> None:
    request_body = json.dumps({"model": "typed-decisions", "state": STATE, "questions": QUESTIONS}).encode()
    request = urllib.request.Request(
        BASE_URL + "/v1/systemone",
        data=request_body,
        headers={"Authorization": "Bearer " + API_KEY, "Content-Type": "application/json"},
    )
    start = time.perf_counter()
    with urllib.request.urlopen(request, timeout=600) as response:
        result = json.load(response)
    elapsed_ms = (time.perf_counter() - start) * 1000
    print(json.dumps({
        "elapsed_ms": round(elapsed_ms, 1),
        "server_peak_rss_mib": round(process_memory_mib("VmHWM"), 1),
        "server_rss_mib_after_request": round(process_memory_mib("VmRSS"), 1),
        "input_tokens": result["usage"]["input_tokens"],
        "output_tokens": result["usage"]["output_tokens"],
        "reported_model": result["model"],
        "checkpoint": result.get("routing", {}).get("model"),
        "judgement": result["answers"]["judgement"]["choice"],
    }, sort_keys=True))


if __name__ == "__main__":
    main()
