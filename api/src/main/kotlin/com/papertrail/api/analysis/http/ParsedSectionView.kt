package com.papertrail.api.analysis.http

import java.util.UUID

data class ParsedSectionView(val id: UUID, val sectionOrder: Int, val heading: String?, val text: String, val startOffset: Int, val endOffset: Int)
