package com.papertrail.api.analysis.service

import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import com.papertrail.api.infrastructure.storage.SourceObjectMetadata
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.nio.charset.StandardCharsets
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class CitedPaperAssetPdfAccessServiceTest {
    private val repository = Mockito.mock(CitedPaperAccessRepository::class.java)
    private val objectStore = Mockito.mock(SourceDocumentObjectStore::class.java)
    private val service = CitedPaperAssetPdfAccessService(repository, objectStore)

    @Test
    fun `presigns only the hash-verified object for the run-scoped asset`() {
        val runId = UUID.randomUUID()
        val assetId = UUID.randomUUID()
        val objectKey = "cited-paper/exact-asset.pdf"
        val hash = "a".repeat(64)
        Mockito.`when`(repository.storedPdfAsset(runId, assetId))
            .thenReturn(CitedPaperAccessRepository.StoredCitedPaperPdfAsset(objectKey, hash))
        Mockito.`when`(objectStore.stat(objectKey)).thenReturn(SourceObjectMetadata(42, hash))
        val viewDisposition = ContentDisposition.inline().filename(CitedPaperAssetPdfAccessService.FILENAME, StandardCharsets.UTF_8).build().toString()
        val downloadDisposition = ContentDisposition.attachment().filename(CitedPaperAssetPdfAccessService.FILENAME, StandardCharsets.UTF_8).build().toString()
        val expiry = CitedPaperAssetPdfAccessService.PRESIGNED_URL_EXPIRY_SECONDS
        Mockito.`when`(objectStore.presignGet(objectKey, viewDisposition, expiry)).thenReturn("https://objects.test/exact-view")
        Mockito.`when`(objectStore.presignGet(objectKey, downloadDisposition, expiry)).thenReturn("https://objects.test/exact-download")

        val result = service.access(runId, assetId)

        assertEquals(assetId, result.assetId)
        assertEquals(hash, result.contentSha256)
        assertEquals("https://objects.test/exact-view", result.viewUrl)
        assertEquals("https://objects.test/exact-download", result.downloadUrl)
        Mockito.verify(objectStore).presignGet(objectKey, viewDisposition, expiry)
        Mockito.verify(objectStore).presignGet(objectKey, downloadDisposition, expiry)
    }

    @Test
    fun `hashes the stored bytes when object metadata has no digest`() {
        val runId = UUID.randomUUID()
        val assetId = UUID.randomUUID()
        val objectKey = "cited-paper/exact-asset.pdf"
        val bytes = "exact PDF bytes".toByteArray()
        val hash = "bd89d3ac637d91a688d54bcc3b69871a266f59bde6590460540372ff0daf2cb4"
        Mockito.`when`(repository.storedPdfAsset(runId, assetId))
            .thenReturn(CitedPaperAccessRepository.StoredCitedPaperPdfAsset(objectKey, hash))
        Mockito.`when`(objectStore.stat(objectKey)).thenReturn(SourceObjectMetadata(bytes.size.toLong(), null))
        Mockito.`when`(objectStore.get(objectKey)).thenReturn(bytes)
        val viewDisposition = ContentDisposition.inline().filename(CitedPaperAssetPdfAccessService.FILENAME, StandardCharsets.UTF_8).build().toString()
        val downloadDisposition = ContentDisposition.attachment().filename(CitedPaperAssetPdfAccessService.FILENAME, StandardCharsets.UTF_8).build().toString()
        val expiry = CitedPaperAssetPdfAccessService.PRESIGNED_URL_EXPIRY_SECONDS
        Mockito.`when`(objectStore.presignGet(objectKey, viewDisposition, expiry)).thenReturn("https://objects.test/exact-view")
        Mockito.`when`(objectStore.presignGet(objectKey, downloadDisposition, expiry)).thenReturn("https://objects.test/exact-download")

        val result = service.access(runId, assetId)

        assertEquals(hash, result.contentSha256)
        Mockito.verify(objectStore).get(objectKey)
        Mockito.verify(objectStore).presignGet(objectKey, viewDisposition, expiry)
        Mockito.verify(objectStore).presignGet(objectKey, downloadDisposition, expiry)
    }

    @Test
    fun `does not presign bytes whose computed digest differs from the pinned asset`() {
        val runId = UUID.randomUUID()
        val assetId = UUID.randomUUID()
        val objectKey = "cited-paper/wrong-bytes.pdf"
        Mockito.`when`(repository.storedPdfAsset(runId, assetId))
            .thenReturn(CitedPaperAccessRepository.StoredCitedPaperPdfAsset(objectKey, "a".repeat(64)))
        Mockito.`when`(objectStore.stat(objectKey)).thenReturn(SourceObjectMetadata(15, null))
        Mockito.`when`(objectStore.get(objectKey)).thenReturn("exact PDF bytes".toByteArray())

        val exception = assertThrows(ResponseStatusException::class.java) { service.access(runId, assetId) }

        assertEquals(HttpStatus.CONFLICT, exception.statusCode)
        val viewDisposition = ContentDisposition.inline().filename(CitedPaperAssetPdfAccessService.FILENAME, StandardCharsets.UTF_8).build().toString()
        val downloadDisposition = ContentDisposition.attachment().filename(CitedPaperAssetPdfAccessService.FILENAME, StandardCharsets.UTF_8).build().toString()
        val expiry = CitedPaperAssetPdfAccessService.PRESIGNED_URL_EXPIRY_SECONDS
        Mockito.verify(objectStore, Mockito.never()).presignGet(objectKey, viewDisposition, expiry)
        Mockito.verify(objectStore, Mockito.never()).presignGet(objectKey, downloadDisposition, expiry)
    }

    @Test
    fun `rejects an object whose bytes do not match the pinned asset hash`() {
        val runId = UUID.randomUUID()
        val assetId = UUID.randomUUID()
        val objectKey = "cited-paper/wrong-bytes.pdf"
        Mockito.`when`(repository.storedPdfAsset(runId, assetId))
            .thenReturn(CitedPaperAccessRepository.StoredCitedPaperPdfAsset(objectKey, "a".repeat(64)))
        Mockito.`when`(objectStore.stat(objectKey)).thenReturn(SourceObjectMetadata(42, "b".repeat(64)))

        val exception = assertThrows(ResponseStatusException::class.java) { service.access(runId, assetId) }

        assertEquals(HttpStatus.CONFLICT, exception.statusCode)
        val viewDisposition = ContentDisposition.inline().filename(CitedPaperAssetPdfAccessService.FILENAME, StandardCharsets.UTF_8).build().toString()
        val downloadDisposition = ContentDisposition.attachment().filename(CitedPaperAssetPdfAccessService.FILENAME, StandardCharsets.UTF_8).build().toString()
        Mockito.verify(objectStore, Mockito.never()).presignGet(objectKey, viewDisposition, CitedPaperAssetPdfAccessService.PRESIGNED_URL_EXPIRY_SECONDS)
        Mockito.verify(objectStore, Mockito.never()).presignGet(objectKey, downloadDisposition, CitedPaperAssetPdfAccessService.PRESIGNED_URL_EXPIRY_SECONDS)
    }

    @Test
    fun `does not access storage for an unknown run-scoped asset`() {
        val runId = UUID.randomUUID()
        val assetId = UUID.randomUUID()
        Mockito.`when`(repository.storedPdfAsset(runId, assetId)).thenReturn(null)

        val exception = assertThrows(ResponseStatusException::class.java) { service.access(runId, assetId) }

        assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
        Mockito.verifyNoInteractions(objectStore)
    }
}
