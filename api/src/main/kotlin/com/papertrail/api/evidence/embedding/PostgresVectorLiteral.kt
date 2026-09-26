package com.papertrail.api.evidence.embedding

fun FloatArray.toPostgresVectorLiteral(): String = joinToString(prefix = "[", postfix = "]") { value ->
    require(value.isFinite()) { "Embedding vector contains a non-finite value." }
    value.toString()
}
