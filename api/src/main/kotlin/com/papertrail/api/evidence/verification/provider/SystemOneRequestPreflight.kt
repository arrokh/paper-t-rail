package com.papertrail.api.evidence.verification.provider

import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement

/** Counts every complete provider sequence with the tokenizer pinned to the selected System One model. */
interface SystemOneRequestPreflight {
    fun tokenCounts(claim: String, passage: EvidencePassageForJudgement): List<Int>
}
