package com.papertrail.api.infrastructure.providers

import com.fasterxml.jackson.databind.JsonNode

class ProviderCallPayload(contentByCategory: Map<DataCategory, JsonNode>) {
    val contentByCategory: Map<DataCategory, JsonNode> = contentByCategory.mapValues { (_, content) -> content.deepCopy() }
    val dataCategories: Set<DataCategory> = this.contentByCategory.keys
}

/** The adapter serializes only this categorized payload after the gate approves it. */
