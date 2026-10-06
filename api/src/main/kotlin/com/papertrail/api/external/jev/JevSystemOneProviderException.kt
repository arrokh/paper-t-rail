package com.papertrail.api.external.jev

import com.papertrail.api.evidence.verification.provider.SystemOneEvidenceJudgementContract
import com.papertrail.api.evidence.verification.provider.SystemOneProviderException

class JevSystemOneProviderException(
    message: String,
    failureReasonCode: String = PROVIDER_ERROR,
    diagnosticField: String? = null,
    diagnosticReasonCode: String? = null,
    val retryable: Boolean = false,
) : SystemOneProviderException(message, failureReasonCode) {
    val diagnosticField: String? = diagnosticField?.takeIf(SAFE_DIAGNOSTIC_FIELDS::contains)
    val diagnosticReasonCode: String? = diagnosticReasonCode?.takeIf(SAFE_DIAGNOSTIC_REASON_CODES::contains)

    companion object {
        const val PROVIDER_ERROR = "SYSTEM_ONE_PROVIDER_ERROR"
        const val NOT_CONFIGURED = "SYSTEM_ONE_NOT_CONFIGURED"
        const val INVALID_INPUT = "SYSTEM_ONE_INVALID_INPUT"
        const val REQUEST_ENCODING_FAILED = "SYSTEM_ONE_REQUEST_ENCODING_FAILED"
        const val TIMEOUT = "SYSTEM_ONE_TIMEOUT"
        const val UNAVAILABLE = "SYSTEM_ONE_UNAVAILABLE"
        const val INTERRUPTED = "SYSTEM_ONE_INTERRUPTED"
        const val RESPONSE_TOO_LARGE = "SYSTEM_ONE_RESPONSE_TOO_LARGE"
        const val INVALID_RESPONSE = "SYSTEM_ONE_RESPONSE_INVALID"
        const val MALFORMED_JSON = "SYSTEM_ONE_RESPONSE_MALFORMED_JSON"
        const val ENVELOPE_INVALID = "SYSTEM_ONE_RESPONSE_ENVELOPE_INVALID"
        const val MODEL_INVALID = "SYSTEM_ONE_RESPONSE_MODEL_INVALID"
        const val USAGE_INVALID = "SYSTEM_ONE_RESPONSE_USAGE_INVALID"
        const val ANSWER_SET_INVALID = "SYSTEM_ONE_RESPONSE_ANSWER_SET_INVALID"
        const val CHOICE_INVALID = "SYSTEM_ONE_RESPONSE_CHOICE_INVALID"
        const val PROBABILITIES_INVALID = "SYSTEM_ONE_RESPONSE_PROBABILITIES_INVALID"
        const val CONFIDENCE_INVALID = "SYSTEM_ONE_RESPONSE_CONFIDENCE_INVALID"
        const val SCORE_LEGEND_INVALID = "SYSTEM_ONE_RESPONSE_SCORE_LEGEND_INVALID"
        const val SCORE_INVALID = "SYSTEM_ONE_RESPONSE_SCORE_INVALID"
        const val SCORE_ANSWER_INVALID = "SCORE_ANSWER_INVALID"
        const val SCORE_TYPE_INVALID = "SCORE_TYPE_INVALID"
        const val SCORE_VALUE_NOT_NUMERIC = "SCORE_VALUE_NOT_NUMERIC"
        const val SCORE_VALUE_NOT_FINITE = "SCORE_VALUE_NOT_FINITE"
        const val SCORE_VALUE_OUT_OF_RANGE = "SCORE_VALUE_OUT_OF_RANGE"
        const val SCORE_WEIGHTED_MEAN_MISMATCH = "SCORE_WEIGHTED_MEAN_MISMATCH"

        private val SAFE_DIAGNOSTIC_REASON_CODES = setOf(
            SCORE_ANSWER_INVALID,
            SCORE_TYPE_INVALID,
            SCORE_VALUE_NOT_NUMERIC,
            SCORE_VALUE_NOT_FINITE,
            SCORE_VALUE_OUT_OF_RANGE,
            SCORE_WEIGHTED_MEAN_MISMATCH,
        )

        private val SAFE_DIAGNOSTIC_FIELDS = setOf("response", "model", "usage", "answers") +
            SystemOneEvidenceJudgementContract.EXPECTED_ANSWER_IDS.flatMap { questionId ->
                listOf(
                    "answers.$questionId",
                    "answers.$questionId.type",
                    "answers.$questionId.choice",
                    "answers.$questionId.confidence",
                    "answers.$questionId.probabilities",
                    "answers.$questionId.score",
                    "answers.$questionId.legend",
                )
            }

        fun httpFailureReasonCode(statusCode: Int): String = "SYSTEM_ONE_HTTP_$statusCode"
    }
}
