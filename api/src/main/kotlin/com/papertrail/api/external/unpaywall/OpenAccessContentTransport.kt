package com.papertrail.api.external.unpaywall

interface OpenAccessContentTransport {
    fun fetch(url: String, maximumBytes: Int, acceptedMediaTypes: Set<String>): OpenAccessContentResponse
}
