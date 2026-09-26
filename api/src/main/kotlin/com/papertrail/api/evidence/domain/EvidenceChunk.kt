package com.papertrail.api.evidence.domain

data class EvidenceChunk(
    val chunkOrder: Int,
    val sectionOrder: Int,
    val sectionHeading: String?,
    val paragraphStart: Int,
    val paragraphEnd: Int,
    val text: String,
)
