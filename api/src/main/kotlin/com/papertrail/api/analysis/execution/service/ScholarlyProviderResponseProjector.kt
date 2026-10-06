package com.papertrail.api.analysis.execution.service

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.utils.JsonUtil
import com.fasterxml.jackson.databind.node.ObjectNode

/** Projects external scholarly responses to bounded, operation-relevant metadata before generic redaction. */
class ScholarlyProviderResponseProjector {
    fun crossref(body: ByteArray): ObjectNode? {
        val response = parseJson(body) ?: return null
        val message = response.path("message")
        if (!response.isObject || !message.isObject) return null

        val projected = JsonUtil.objectNode()
        copyText(response, projected, "status", 32)
        copyText(response, projected, "message-type", 64)
        copyText(response, projected, "message-version", 32)

        val projectedMessage = JsonUtil.objectNode()
        val items = message.path("items")
        if (items.isArray) {
            copyNonNegativeLong(message, projectedMessage, "total-results")
            copyNonNegativeLong(message, projectedMessage, "items-per-page")
            val projectedItems = JsonUtil.arrayNode()
            items.take(MAX_METADATA_ITEMS).forEach { item -> projectCrossrefWork(item)?.let(projectedItems::add) }
            projectedMessage.set<JsonNode>("items", projectedItems)
        } else {
            val work = projectCrossrefWork(message) ?: return null
            projectedMessage.setAll(work)
        }
        projected.set<ObjectNode>("message", projectedMessage)
        return projected
    }

    fun unpaywall(body: ByteArray): ObjectNode? {
        val response = parseJson(body) ?: return null
        val doi = response.path("doi").takeIf(JsonNode::isTextual)?.asText()
        if (!response.isObject || doi == null || !DOI.matches(doi)) return null

        val projected = JsonUtil.objectNode()
            .put("doi", doi)
            .put("abstractAvailable", response.path("abstract").takeIf(JsonNode::isTextual)?.asText()?.isNotBlank() == true)
        copyBoolean(response, projected, "is_oa")
        response.path("oa_status").takeIf(JsonNode::isTextual)?.asText()
            ?.takeIf { it in UNPAYWALL_OA_STATUSES }
            ?.let { projected.put("oa_status", it) }
        copyBoolean(response, projected, "journal_is_oa")
        copyBoolean(response, projected, "journal_is_in_doaj")
        copyBoolean(response, projected, "has_repository_copy")
        projectUnpaywallLocation(response.path("best_oa_location"))
            ?.let { projected.set<ObjectNode>("best_oa_location", it) }
        val locations = response.path("oa_locations")
        if (locations.isArray) {
            val safeLocations = JsonUtil.arrayNode()
            locations.take(MAX_OA_LOCATIONS).forEach { location ->
                projectUnpaywallLocation(location)?.let(safeLocations::add)
            }
            projected.set<JsonNode>("oa_locations", safeLocations)
        }
        return projected
    }

    private fun projectCrossrefWork(work: JsonNode): ObjectNode? {
        if (!work.isObject) return null
        val doi = work.path("DOI").takeIf(JsonNode::isTextual)?.asText() ?: return null
        if (!DOI.matches(doi)) return null

        val projected = JsonUtil.objectNode().put("DOI", doi)
        val titles = work.path("title")
        if (titles.isArray) {
            val safeTitles = JsonUtil.arrayNode()
            titles.take(MAX_TITLES).forEach { title ->
                title.takeIf(JsonNode::isTextual)?.asText()?.takeIf(::isSafeTitle)?.let(safeTitles::add)
            }
            if (!safeTitles.isEmpty) projected.set<JsonNode>("title", safeTitles)
        }
        val authors = work.path("author")
        if (authors.isArray) {
            val safeAuthors = JsonUtil.arrayNode()
            authors.take(MAX_AUTHORS).forEach { author ->
                if (!author.isObject) return@forEach
                val safeAuthor = JsonUtil.objectNode()
                listOf("name", "given", "family").forEach { field ->
                    copyText(author, safeAuthor, field, MAX_AUTHOR_NAME_LENGTH)
                }
                if (!safeAuthor.isEmpty) safeAuthors.add(safeAuthor)
            }
            if (!safeAuthors.isEmpty) projected.set<JsonNode>("author", safeAuthors)
        }

        val publicationYear = sequenceOf("published-print", "published-online", "issued")
            .mapNotNull { field -> work.path(field).path("date-parts").firstOrNull()?.firstOrNull() }
            .firstOrNull { it.isIntegralNumber && it.canConvertToInt() && it.asInt() in MIN_PUBLICATION_YEAR..MAX_PUBLICATION_YEAR }
        publicationYear?.let { projected.put("publicationYear", it.asInt()) }

        listOf(
            "publisher" to MAX_TITLE_LENGTH,
            "volume" to MAX_SHORT_TEXT_LENGTH,
            "issue" to MAX_SHORT_TEXT_LENGTH,
            "page" to MAX_SHORT_TEXT_LENGTH,
            "type" to MAX_SHORT_TEXT_LENGTH,
            "subtype" to MAX_SHORT_TEXT_LENGTH,
        ).forEach { (field, maximumLength) -> copyText(work, projected, field, maximumLength) }
        val containers = work.path("container-title")
        if (containers.isArray) {
            val safeContainers = JsonUtil.arrayNode()
            containers.take(MAX_TITLES).forEach { container ->
                container.takeIf(JsonNode::isTextual)?.asText()?.takeIf(::isSafeTitle)?.let(safeContainers::add)
            }
            if (!safeContainers.isEmpty) projected.set<JsonNode>("container-title", safeContainers)
        }
        return projected
    }

    private fun projectUnpaywallLocation(location: JsonNode): ObjectNode? {
        if (!location.isObject) return null
        val projected = JsonUtil.objectNode()
        listOf("url_for_pdf", "url").forEach { field ->
            location.path(field).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)
                ?.let { projected.put(field, it) }
        }
        listOf(
            "license" to MAX_TITLE_LENGTH,
            "version" to MAX_SHORT_TEXT_LENGTH,
            "host_type" to MAX_SHORT_TEXT_LENGTH,
        ).forEach { (field, maximumLength) -> copyText(location, projected, field, maximumLength) }
        return projected.takeUnless(ObjectNode::isEmpty)
    }

    private fun copyText(source: JsonNode, target: ObjectNode, field: String, maxLength: Int) {
        source.path(field).takeIf(JsonNode::isTextual)?.asText()
            ?.takeIf { it.isNotBlank() && it.length <= maxLength }
            ?.let { target.put(field, it) }
    }

    private fun copyNonNegativeLong(source: JsonNode, target: ObjectNode, field: String) {
        source.path(field).takeIf { it.isIntegralNumber && it.canConvertToLong() && it.asLong() >= 0 }
            ?.let { target.put(field, it.asLong()) }
    }

    private fun copyBoolean(source: JsonNode, target: ObjectNode, field: String) {
        source.path(field).takeIf(JsonNode::isBoolean)?.let { target.set<JsonNode>(field, it) }
    }

    private fun isSafeTitle(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_TITLE_LENGTH

    private fun parseJson(body: ByteArray): JsonNode? = runCatching { JsonUtil.parseTree(body) }.getOrNull()

    companion object {
        private val DOI = Regex("^10\\.[0-9]{4,9}/[-._;()/:A-Z0-9]+$", RegexOption.IGNORE_CASE)
        private val UNPAYWALL_OA_STATUSES = setOf("gold", "green", "hybrid", "bronze", "closed")
        private const val MAX_METADATA_ITEMS = 10
        private const val MAX_OA_LOCATIONS = 50
        private const val MAX_TITLES = 3
        private const val MAX_AUTHORS = 50
        private const val MAX_TITLE_LENGTH = 500
        private const val MAX_AUTHOR_NAME_LENGTH = 200
        private const val MAX_SHORT_TEXT_LENGTH = 100
        private const val MIN_PUBLICATION_YEAR = 1400
        private const val MAX_PUBLICATION_YEAR = 2200
    }
}
