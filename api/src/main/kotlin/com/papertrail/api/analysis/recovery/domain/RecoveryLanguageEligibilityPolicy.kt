package com.papertrail.api.analysis.recovery.domain

import com.papertrail.api.infrastructure.crypto.sha256Hex
import java.nio.charset.StandardCharsets

/** Immutable inputs that determine whether extracted PDF text is English-eligible. */
data class RecoveryLanguageEligibilityPolicy(
    val detectorId: String,
    val detectorVersion: String,
    val minimumExtractedCharacters: Int,
    val minimumConfidence: Double,
) {
    init {
        require(detectorId.isNotBlank()) { "Language detector ID must not be blank." }
        require(detectorVersion.isNotBlank()) { "Language detector version must not be blank." }
        require(minimumExtractedCharacters >= 0) { "Minimum extracted character count must not be negative." }
        require(minimumConfidence in 0.0..1.0) { "Minimum language confidence must be between zero and one." }
    }

    val fingerprint: String
        get() = sha256Hex(
            listOf(
                POLICY_VERSION,
                "${detectorId.length}:$detectorId",
                "${detectorVersion.length}:$detectorVersion",
                minimumExtractedCharacters.toString(),
                minimumConfidence.toRawBits().toString(),
            ).joinToString("\n").toByteArray(StandardCharsets.UTF_8),
        )

    companion object {
        const val POLICY_VERSION = "recovery-language-eligibility-v1"
    }
}

internal fun RecoveryUploadValidationContext.languageEligibilityPolicy() = RecoveryLanguageEligibilityPolicy(
    detectorId = configuration.languageDetector.provider,
    detectorVersion = configuration.languageDetector.version,
    minimumExtractedCharacters = configuration.validationLimits.minimumExtractedCharacters,
    minimumConfidence = configuration.validationLimits.minimumLanguageConfidence,
)
