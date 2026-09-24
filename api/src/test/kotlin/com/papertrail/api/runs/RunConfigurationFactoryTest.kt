package com.papertrail.api.runs

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunConfigurationFactoryTest {
    private val factory = RunConfigurationFactory(
        objectMapper = jacksonObjectMapper(),
        parserId = "grobid",
        parserVersion = "0.9.1-crf",
        languageDetectorVersion = "0.6",
        limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
    )

    @Test
    fun `snapshots the selected safe local default configuration and parser limits`() {
        val request = factory.parseRequest(null)
        val snapshot = factory.from(request)

        assertEquals("heuristic", snapshot.claimExtractor.provider)
        assertEquals("local", snapshot.embedding.provider)
        assertEquals("mock", snapshot.systemOne.provider)
        assertEquals("grobid", snapshot.sourceParser.provider)
        assertEquals("0.9.1-crf", snapshot.sourceParser.version)
        assertEquals(52_428_800, snapshot.validationLimits.maxUploadBytes)
        assertEquals(100_000, snapshot.validationLimits.maxExtractedCharactersPerPage)
        assertEquals(100, snapshot.validationLimits.minimumExtractedCharacters)
        assertEquals("NOT_RUN", snapshot.referenceResolution.executionStatus)
        assertEquals("NOT_RUN", snapshot.aggregation.executionStatus)
        assertEquals(0, snapshot.externalProviderConsents.size)
        val json = jacksonObjectMapper().readTree(factory.toJson(snapshot))
        assertTrue(json["referenceResolution"].has("confidenceThreshold"))
        assertTrue(json["referenceResolution"]["confidenceThreshold"].isNull)
        assertTrue(json["aggregation"].has("thresholds"))
        assertTrue(json["aggregation"]["thresholds"].isNull)
    }

    @Test
    fun `rejects external or disabled provider selections`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.from(RunConfigurationRequest(systemOneProvider = "external-vendor"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.from(RunConfigurationRequest(embeddingProvider = "remote"))
        }
    }

    @Test
    fun `rejects unknown configuration fields instead of silently ignoring them`() {
        val configuration = jacksonObjectMapper().readTree("""{"claimExtractorProvider":"heuristic","unexpected":true}""")
        assertThrows(IllegalArgumentException::class.java) { factory.parseRequest(configuration) }
    }
}
