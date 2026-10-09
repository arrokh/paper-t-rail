package com.papertrail.api.scholarly.references.service

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import com.papertrail.api.utils.JsonUtil
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class RecordedFixtureScholarlyMetadataLookupFactory(
    @Value("\${paper-trail.providers.recorded-fixtures.scholarly-works-resource}")
    resourcePath: String = "provider-fixtures/recorded-scholarly-works.json",
) : ScholarlyMetadataLookupFactory {
    private val works: List<ScholarlyWork> = JsonUtil.parseTree(ClassPathResource(resourcePath).inputStream)
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
