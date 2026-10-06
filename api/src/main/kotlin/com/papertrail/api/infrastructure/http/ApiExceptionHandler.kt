package com.papertrail.api.infrastructure.http

import com.papertrail.api.http.ApiError
import com.papertrail.api.document.validation.DocumentValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(DocumentValidationException::class)
    fun documentValidation(exception: DocumentValidationException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError(exception.code, exception.message))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun uploadTooLarge(): ResponseEntity<ApiError> = ResponseEntity
        .status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(ApiError("UPLOAD_TOO_LARGE", "The upload exceeds the configured request-size limit."))

    @ExceptionHandler(MissingServletRequestPartException::class)
    fun missingPart(exception: MissingServletRequestPartException): ResponseEntity<ApiError> = ResponseEntity
        .badRequest()
        .body(ApiError("MISSING_UPLOAD", "The request must include a PDF file in the 'file' field."))

    @ExceptionHandler(ResponseStatusException::class)
    fun status(exception: ResponseStatusException): ResponseEntity<ApiError> {
        val status = exception.statusCode
        val code = when (status) {
            HttpStatus.NOT_FOUND -> "NOT_FOUND"
            HttpStatus.UNAUTHORIZED -> "UNAUTHORIZED"
            HttpStatus.CONFLICT -> "CONFLICT"
            HttpStatus.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            else -> "REQUEST_REJECTED"
        }
        return ResponseEntity.status(status).body(ApiError(code, exception.reason ?: "The request could not be completed."))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(exception: IllegalArgumentException): ResponseEntity<ApiError> = ResponseEntity
        .badRequest()
        .body(ApiError("INVALID_REQUEST", exception.message ?: "The request is invalid."))
}
