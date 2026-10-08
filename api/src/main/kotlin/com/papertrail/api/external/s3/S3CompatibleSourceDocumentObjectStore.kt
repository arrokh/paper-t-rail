package com.papertrail.api.external.s3

import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.storage.PresignedObjectUpload
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import com.papertrail.api.infrastructure.storage.SourceObjectMetadata
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CORSConfiguration
import software.amazon.awssdk.services.s3.model.CORSRule
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration
import java.util.Base64

/** Implements Source Document operations through the configured S3 API endpoint. */
@Component
class S3CompatibleSourceDocumentObjectStore(
    @Value("\${paper-trail.storage.endpoint}") endpoint: String,
    @Value("\${paper-trail.storage.region}") region: String,
    @Value("\${paper-trail.storage.access-key}") accessKey: String,
    @Value("\${paper-trail.storage.secret-key}") secretKey: String,
    @Value("\${paper-trail.storage.public-endpoint}") publicEndpoint: String,
    @Value("\${paper-trail.storage.path-style-access}") pathStyleAccess: Boolean,
    @Value("\${paper-trail.storage.bucket}") private val bucket: String,
) : SourceDocumentObjectStore, AutoCloseable {
    private val credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
    private val serviceConfiguration = S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build()
    private val client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(credentialsProvider)
        .serviceConfiguration(serviceConfiguration)
        .build()
    private val presigner = S3Presigner.builder()
        .endpointOverride(URI.create(publicEndpoint))
        .region(Region.of(region))
        .credentialsProvider(credentialsProvider)
        .serviceConfiguration(serviceConfiguration)
        .build()

    override fun configureBrowserUploadCors(allowedOrigins: Collection<String>) {
        val origins = allowedOrigins.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(origins.isNotEmpty() && origins.all(::isExactBrowserOrigin)) { "Browser upload CORS requires exact HTTP(S) origins without paths." }
        client.putBucketCors(
            PutBucketCorsRequest.builder()
                .bucket(bucket)
                .corsConfiguration(
                    CORSConfiguration.builder()
                        .corsRules(
                            CORSRule.builder()
                                .id("paper-trail-recovery-pdf-upload")
                                .allowedOrigins(origins)
                                .allowedMethods("PUT")
                                .allowedHeaders("Content-Type", "x-amz-checksum-sha256")
                                .maxAgeSeconds(CORS_PREFLIGHT_MAX_AGE_SECONDS)
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
    }

    private fun isExactBrowserOrigin(origin: String): Boolean {
        val uri = runCatching { URI.create(origin) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") &&
            uri.host != null &&
            uri.rawUserInfo == null &&
            uri.rawPath.isNullOrEmpty() &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            origin == "${uri.scheme}://${uri.rawAuthority}"
    }

    override fun put(objectKey: String, content: ByteArray, contentType: String) {
        client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .metadata(mapOf("sha256" to sha256Hex(content)))
                .build(),
            RequestBody.fromBytes(content),
        )
    }

    override fun get(objectKey: String): ByteArray {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build()).asByteArray()
    }

    override fun stat(objectKey: String): SourceObjectMetadata {
        val response = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build())
        val checksum = response.metadata().entries
            .firstOrNull { (name, _) ->
                name.equals("sha256", ignoreCase = true) || name.equals("x-amz-meta-sha256", ignoreCase = true)
            }
            ?.value
        return SourceObjectMetadata(size = response.contentLength(), sha256 = checksum)
    }

    override fun presignGet(objectKey: String, responseContentDisposition: String, expirySeconds: Int): String =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expirySeconds.toLong()))
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .responseContentType("application/pdf")
                        .responseContentDisposition(responseContentDisposition)
                        .responseCacheControl("no-store")
                        .build(),
                )
                .build(),
        ).url().toString()

    override fun presignPutPdf(
        objectKey: String,
        expectedSize: Long,
        expectedSha256: String,
        expirySeconds: Int,
    ): PresignedObjectUpload {
        require(expectedSize > 0) { "Presigned PDF uploads must have a positive content length." }
        require(expectedSha256.matches(Regex("^[0-9a-f]{64}$"))) { "Presigned PDF uploads require a lowercase SHA-256 digest." }
        require(expirySeconds > 0) { "Presigned PDF upload expiry must be positive." }

        val checksum = Base64.getEncoder().encodeToString(expectedSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val request = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expirySeconds.toLong()))
                .putObjectRequest(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(PDF_CONTENT_TYPE)
                        .contentLength(expectedSize)
                        .checksumSHA256(checksum)
                        .build(),
                )
                .build(),
        )
        val signedHeaders = request.signedHeaders().keys.map { it.lowercase() }.toSet()
        require("content-length" in signedHeaders && "content-type" in signedHeaders && "x-amz-checksum-sha256" in signedHeaders) {
            "Configured S3 signer did not bind the PDF size, content type, and SHA-256 checksum."
        }
        return PresignedObjectUpload(request.url().toString(), PDF_CONTENT_TYPE, checksum)
    }

    override fun delete(objectKey: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build())
    }

    companion object {
        private const val PDF_CONTENT_TYPE = "application/pdf"
        private const val CORS_PREFLIGHT_MAX_AGE_SECONDS = 300
    }

    @PreDestroy
    override fun close() {
        client.close()
        presigner.close()
    }
}
