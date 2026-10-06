package com.papertrail.api.analysis.http

data class ParsedBibliographyEntryView(val entryOrder: Int, val localReferenceKey: String, val rawText: String, val title: String?, val authors: List<String>, val year: Int?, val doi: String?, val referenceType: String, val resolutionStatus: String)
