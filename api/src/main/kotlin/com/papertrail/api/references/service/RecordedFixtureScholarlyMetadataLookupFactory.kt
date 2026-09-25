package com.papertrail.api.references.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.references.client.BibliographyReference
import com.papertrail.api.references.client.ScholarlyMetadataLookup
import com.papertrail.api.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.references.client.ScholarlyWork
import com.papertrail.api.references.normalization.DoiNormalizer
import com.papertrail.api.runs.AnalysisConfigurationSnapshot
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class RecordedFixtureScholarlyMetadataLookupFactory(
    objectMapper: ObjectMapper,
) : ScholarlyMetadataLookupFactory {
    private val works: List<ScholarlyWork> = objectMapper.readTree(ClassPathResource("provider-fixtures/recorded-scholarly-works.json").inputStream)
        .map { node ->
            ScholarlyWork(
                doi = node.path("doi").takeIf(JsonNode::isTextual)?.asText(),
                title = node.path("title").asText(),
                authors = node.path("authors").map(JsonNode::asText),
                year = node.path("year").takeIf(JsonNode::isIntegralNumber)?.asInt(),
            )
        }

    override val providerId: String = "recorded-fixtures"

    override fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup = object : ScholarlyMetadataLookup {
        override fun byDoi(doi: String): ScholarlyWork? = works.firstOrNull { DoiNormalizer.normalize(it.doi) == doi }

        override fun search(reference: BibliographyReference): List<ScholarlyWork> = works
    }
}
