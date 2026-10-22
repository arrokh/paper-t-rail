package com.papertrail.api.analysis.service

import com.papertrail.api.analysis.http.AnalysisRunCitedPaperPdfAccess
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import org.slf4j.LoggerFactory
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@Service
class CitedPaperAssetPdfAccessService(
    private val citedPaperAccessRepository: CitedPaperAccessRepository,
    private val objectStore: SourceDocumentObjectStore,
) {
    fun access(analysisRunId: UUID, assetId: UUID): AnalysisRunCitedPaperPdfAccess {
        val asset = citedPaperAccessRepository.storedPdfAsset(analysisRunId, assetId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Stored Cited Paper PDF asset not found.")
        val metadata = try {
            objectStore.stat(asset.objectKey)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("analysisRunId", analysisRunId)
                .addKeyValue("assetId", assetId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Stored Cited Paper PDF unavailable for viewing")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The stored Cited Paper PDF is temporarily unavailable.")
        }

        val actualHash = metadata.sha256 ?: try {
            sha256Hex(objectStore.get(asset.objectKey))
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("analysisRunId", analysisRunId)
                .addKeyValue("assetId", assetId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Stored Cited Paper PDF unavailable for integrity verification")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The stored Cited Paper PDF is temporarily unavailable.")
        }
        if (actualHash != asset.contentSha256) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The stored Cited Paper PDF does not match this Analysis Run asset.")
        }

        val expiresAt = Instant.now().plusSeconds(PRESIGNED_URL_EXPIRY_SECONDS.toLong())
        val viewDisposition = ContentDisposition.inline().filename(FILENAME, StandardCharsets.UTF_8).build().toString()
        val downloadDisposition = ContentDisposition.attachment().filename(FILENAME, StandardCharsets.UTF_8).build().toString()
        val viewUrl: String
        val downloadUrl: String
        try {
            viewUrl = objectStore.presignGet(asset.objectKey, viewDisposition, PRESIGNED_URL_EXPIRY_SECONDS)
            downloadUrl = objectStore.presignGet(asset.objectKey, downloadDisposition, PRESIGNED_URL_EXPIRY_SECONDS)
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("analysisRunId", analysisRunId)
                .addKeyValue("assetId", assetId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Could not create temporary Cited Paper PDF links")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The exact stored Cited Paper PDF is temporarily unavailable.")
        }

        return AnalysisRunCitedPaperPdfAccess(
            assetId = assetId,
            contentSha256 = asset.contentSha256,
            filename = FILENAME,
            viewUrl = viewUrl,
            downloadUrl = downloadUrl,
            expiresAt = expiresAt,
        )
    }

    companion object {
        const val PRESIGNED_URL_EXPIRY_SECONDS = 15 * 60
        const val FILENAME = "cited-paper.pdf"
        private val logger = LoggerFactory.getLogger(CitedPaperAssetPdfAccessService::class.java)
    }
}
