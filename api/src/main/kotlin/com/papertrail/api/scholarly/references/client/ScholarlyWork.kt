package com.papertrail.api.scholarly.references.client

data class ScholarlyWork(
    val doi: String?,
    val title: String,
    val authors: List<String>,
    val year: Int?,
)
