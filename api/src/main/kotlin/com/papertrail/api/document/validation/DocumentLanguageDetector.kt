package com.papertrail.api.document.validation

interface DocumentLanguageDetector {
    fun detect(text: String): LanguageDetection
}
