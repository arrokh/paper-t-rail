package com.papertrail.api.infrastructure.storage

interface SourceDocumentObjectStore {
    fun put(objectKey: String, content: ByteArray, contentType: String = "application/pdf")
    fun get(objectKey: String): ByteArray
    fun stat(objectKey: String): SourceObjectMetadata
    fun presignGet(objectKey: String, responseContentDisposition: String, expirySeconds: Int): String
    fun presignPutPdf(objectKey: String, expectedSize: Long, expectedSha256: String, expirySeconds: Int): PresignedObjectUpload
    fun configureBrowserUploadCors(allowedOrigins: Collection<String>)
    fun delete(objectKey: String)
}
