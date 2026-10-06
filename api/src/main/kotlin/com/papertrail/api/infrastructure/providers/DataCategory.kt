package com.papertrail.api.infrastructure.providers

enum class DataCategory(val id: String, val label: String, val description: String) {
    SOURCE_DOCUMENT_TEXT("source_document_text", "Source Document text", "Text extracted from the uploaded document."),
    BIBLIOGRAPHIC_METADATA("bibliographic_metadata", "Bibliographic metadata", "DOIs and the minimum title, author, year, or reference fields used for lookup."),
    CITATION_CONTEXT("citation_context", "Citation Context", "The citation-bearing clause or sentence submitted for Atomic Claim extraction and Citation Target selection."),
    CITED_PAPER_CHUNKS("cited_paper_chunks", "Cited Paper chunks", "Text chunks from an acquired Cited Paper."),
    ATOMIC_CLAIMS("atomic_claims", "Atomic Claims", "Individual propositions submitted for assessment."),
    EVIDENCE_PASSAGES("evidence_passages", "Evidence Passages", "Passages from a Cited Paper submitted for assessment."),
    EMBEDDING_INPUT("embedding_input", "Embedding input", "Text submitted to a provider to calculate embeddings."),
    PROVIDER_CONTACT_EMAIL("provider_contact_email", "Provider contact email", "An operator contact email required or configured for a provider request."),
    CITED_PAPER_LOCATION("cited_paper_location", "Cited Paper location", "A discovered full-text URL used to request an openly licensed Cited Paper; Paper T-Rail does not send Source Document text.");

    companion object {
        fun fromId(id: String): DataCategory? = entries.firstOrNull { it.id == id }
    }
}
