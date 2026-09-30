package com.papertrail.api.analysis.service

data class AnalysisRunSourcePdf(
    val filename: String,
    val content: ByteArray,
)
