package com.papertrail.api.utils

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.io.InputStream

object JsonUtil {
    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .findModulesViaServiceLoader(true)
        .featuresToDisable(
            MapperFeature.DEFAULT_VIEW_INCLUSION,
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
        )
        .build()

    @JvmStatic
    fun toJson(value: Any?): String = objectMapper.writeValueAsString(value)

    @JvmStatic
    fun toJsonBytes(value: Any?): ByteArray = objectMapper.writeValueAsBytes(value)

    @JvmStatic
    fun <T> fromJson(json: String, targetType: Class<T>): T = objectMapper.readValue(json, targetType)

    @JvmStatic
    fun <T> fromJson(json: String, targetType: JavaType): T = objectMapper.readValue(json, targetType)

    @JvmStatic
    fun <T> fromJson(json: String, targetType: TypeReference<T>): T = objectMapper.readValue(json, targetType)

    @JvmStatic
    fun collectionType(collectionType: Class<out Collection<*>>, elementType: Class<*>): JavaType =
        objectMapper.typeFactory.constructCollectionType(collectionType, elementType)

    @JvmStatic
    inline fun <reified T> fromJson(json: String): T = fromJson(json, object : TypeReference<T>() {})

    @JvmStatic
    fun parseTree(json: String): JsonNode = objectMapper.readTree(json)

    @JvmStatic
    fun parseTree(json: ByteArray): JsonNode = objectMapper.readTree(json)

    @JvmStatic
    fun parseTree(json: InputStream): JsonNode = objectMapper.readTree(json)

    @JvmStatic
    fun parseStrictTree(json: String): JsonNode? = objectMapper.reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .readTree(json)

    @JvmStatic
    fun toTree(value: Any?): JsonNode = objectMapper.valueToTree(value)

    @JvmStatic
    fun objectNode(): ObjectNode = objectMapper.createObjectNode()

    @JvmStatic
    fun arrayNode(): ArrayNode = objectMapper.createArrayNode()

    @JvmStatic
    fun nullNode(): JsonNode = objectMapper.nullNode()

    @JvmStatic
    fun textNode(value: String): JsonNode = objectMapper.nodeFactory.textNode(value)
}
