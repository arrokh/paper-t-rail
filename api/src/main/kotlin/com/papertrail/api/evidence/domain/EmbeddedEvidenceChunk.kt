package com.papertrail.api.evidence.domain

data class EmbeddedEvidenceChunk(
    val chunk: EvidenceChunk,
    val vector: FloatArray,
)
