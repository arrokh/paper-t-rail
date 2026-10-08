package com.papertrail.api.infrastructure.http

import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.http.ApiError
import com.papertrail.api.document.validation.DocumentValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
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

    @ExceptionHandler(RecoveryStagingException::class)
    fun recoveryStaging(exception: RecoveryStagingException): ResponseEntity<ApiError> = ResponseEntity
        .status(exception.statusCode)
        .body(ApiError(exception.code, exception.message ?: "The Recovery Upload request could not be completed."))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidRequest(): ResponseEntity<ApiError> = ResponseEntity
        .badRequest()
        .body(ApiError("INVALID_REQUEST", "The request contains invalid fields."))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableRequest(): ResponseEntity<ApiError> = ResponseEntity
        .badRequest()
        .body(ApiError("INVALID_REQUEST", "The request body could not be read."))

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
