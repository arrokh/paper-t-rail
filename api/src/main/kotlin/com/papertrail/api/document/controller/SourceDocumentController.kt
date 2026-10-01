package com.papertrail.api.document.controller

import com.papertrail.api.document.service.SourceDocumentDeletionService
import com.papertrail.api.http.ApiError
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Source Documents", description = "Explicitly delete Source Documents and their document-scoped data.")
class SourceDocumentController(
    private val deletionService: SourceDocumentDeletionService,
) {
    @Operation(
        summary = "Delete a Source Document and all document-scoped data",
        description = "Tombstones the Source Document before invalidating pending work, removes its Analysis Runs and derived data, and deletes unshared stored assets. A content-free tombstone remains so retries and queued workers cannot restore the data. Data already sent to external providers cannot be retracted.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Source Document and document-scoped data deleted"),
            ApiResponse(responseCode = "404", description = "Source Document does not exist", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "503", description = "Deletion is incomplete; retry the same request safely", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @DeleteMapping("/{documentId}")
    fun delete(@PathVariable documentId: UUID): ResponseEntity<Void> {
        deletionService.delete(documentId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
