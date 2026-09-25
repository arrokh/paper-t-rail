package com.papertrail.api.scholarly.references.report

import java.util.UUID

data class ReportCanonicalPaper(
    val id: UUID,
    val doi: String?,
    val title: String,
    val authors: List<String>,
    val year: Int?,
)
