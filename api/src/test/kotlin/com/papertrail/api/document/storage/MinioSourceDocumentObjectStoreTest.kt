package com.papertrail.api.document.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class MinioSourceDocumentObjectStoreTest {
    @Test
    fun `presigns a browser reachable PDF URL with response headers and expiry`() {
        val store = MinioSourceDocumentObjectStore(
            endpoint = "http://minio:9000",
            region = "us-east-1",
            accessKey = "local-access-key",
            secretKey = "local-secret-key",
            publicEndpoint = "https://files.example.test",
            bucket = "source-documents",
        )

        val url = store.presignGet(
            objectKey = "source/document-1/abc123.pdf",
            responseContentDisposition = "inline; filename=\"paper.pdf\"",
            expirySeconds = 3_600,
        )
        val uri = URI(url)
        val query = uri.rawQuery.split('&').associate { parameter ->
            val (key, value) = parameter.split('=', limit = 2)
            URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

        assertEquals("https", uri.scheme)
        assertEquals("files.example.test", uri.host)
        assertTrue(uri.rawPath.endsWith("/source-documents/source/document-1/abc123.pdf"))
        assertEquals("application/pdf", query["response-content-type"])
        assertEquals("inline; filename=\"paper.pdf\"", query["response-content-disposition"])
        assertEquals("no-store", query["response-cache-control"])
        assertEquals("3600", query["X-Amz-Expires"])
        assertTrue("X-Amz-Signature" in query)
    }
}
