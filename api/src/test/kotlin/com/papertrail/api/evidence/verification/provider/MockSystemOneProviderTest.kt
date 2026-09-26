package com.papertrail.api.evidence.verification.provider

import com.papertrail.api.evidence.verification.domain.AtomicClaimForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class MockSystemOneProviderTest {
    private val provider = MockSystemOneProvider()

    @Test
    fun `explicit fixture markers produce deterministic semantic judgements`() {
        val result = provider.evaluate(
            SemanticJudgementRequest(
                atomicClaim = AtomicClaimForJudgement(UUID.randomUUID(), "Fixture claim"),
                evidencePassages = listOf(
                    passage(MockSystemOneProvider.DIRECT_SUPPORT_FIXTURE_MARKER),
                    passage(MockSystemOneProvider.CONTRADICTION_FIXTURE_MARKER),
                    passage("ordinary evidence text"),
                ),
            ),
        )

        assertEquals(
            listOf(
                EvidenceJudgementKind.DIRECT_SUPPORT,
                EvidenceJudgementKind.CONTRADICTS,
                EvidenceJudgementKind.INSUFFICIENT,
            ),
            result.evidenceJudgements.map { it.judgement },
        )
        assertEquals(0.96, result.evidenceJudgements[0].confidence)
        assertEquals(0.96, result.evidenceJudgements[1].confidence)
        assertEquals(0.0, result.evidenceJudgements[2].confidence)
    }

    @Test
    fun `unmarked passages never receive a positive mock assessment`() {
        val result = provider.evaluate(
            SemanticJudgementRequest(
                atomicClaim = AtomicClaimForJudgement(UUID.randomUUID(), "Claim"),
                evidencePassages = listOf(passage("A study reports a result.")),
            ),
        )

        assertTrue(result.evidenceJudgements.all { it.judgement == EvidenceJudgementKind.INSUFFICIENT })
        assertTrue(result.evidenceJudgements.all { it.confidence == 0.0 })
    }

    private fun passage(text: String) = EvidencePassageForJudgement(
        id = UUID.randomUUID(),
        text = text,
        sectionHeading = "Results",
    )
}
