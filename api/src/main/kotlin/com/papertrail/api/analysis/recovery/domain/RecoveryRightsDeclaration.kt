package com.papertrail.api.analysis.recovery.domain

data class RecoveryRightsDeclaration(
    val version: String,
    val text: String,
) {
    companion object {
        val CURRENT = RecoveryRightsDeclaration(
            version = "recovery-rights-v1",
            text = "I have the right to upload this PDF to this Paper T-Rail workspace for local processing. This is my declaration; it does not grant publication or redistribution rights and does not authorize sending the PDF to an external provider.",
        )
    }
}
