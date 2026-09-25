package com.papertrail.api.infrastructure.providers

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/providers", produces = [MediaType.APPLICATION_JSON_VALUE])
class ProviderController(private val providerCatalog: ProviderCatalog) {
    @GetMapping
    fun listProviders() = providerCatalog.directory()
}
