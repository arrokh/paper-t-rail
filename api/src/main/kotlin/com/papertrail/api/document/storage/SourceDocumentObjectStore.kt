package com.papertrail.api.document.storage

import com.papertrail.api.infrastructure.crypto.sha256Hex
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.http.Method
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

interface SourceDocumentObjectStore {
    fun put(objectKey: String, content: ByteArray, contentType: String = "application/pdf")
    fun get(objectKey: String): ByteArray
    fun stat(objectKey: String): SourceObjectMetadata
    fun presignGet(objectKey: String, responseContentDisposition: String, expirySeconds: Int): String
    fun delete(objectKey: String)
}

@Component
class MinioSourceDocumentObjectStore(
    @Value("\${paper-trail.storage.endpoint}") endpoint: String,
    @Value("\${paper-trail.storage.region}") region: String,
    @Value("\${paper-trail.storage.access-key}") accessKey: String,
    @Value("\${paper-trail.storage.secret-key}") secretKey: String,
    @Value("\${paper-trail.storage.public-endpoint}") publicEndpoint: String,
    @Value("\${paper-trail.storage.bucket}") private val bucket: String,
) : SourceDocumentObjectStore {
    private val client = MinioClient.builder()
        .endpoint(endpoint)
        .region(region)
        .credentials(accessKey, secretKey)
        .build()
    private val presigner = MinioClient.builder()
        .endpoint(publicEndpoint)
        .region(region)
        .credentials(accessKey, secretKey)
        .build()

    @Volatile
    private var bucketReady = false

    override fun put(objectKey: String, content: ByteArray, contentType: String) {
        ensureBucket()
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .`object`(objectKey)
                .stream(ByteArrayInputStream(content), content.size.toLong(), -1)
                .contentType(contentType)
                .userMetadata(mapOf("sha256" to sha256Hex(content)))
                .build(),
        )
    }

    override fun get(objectKey: String): ByteArray {
        ensureBucket()
        client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(objectKey).build()).use { input ->
            return input.readBytes()
        }
    }

    override fun stat(objectKey: String): SourceObjectMetadata {
        ensureBucket()
        val response = client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(objectKey).build())
        val checksum = response.userMetadata().entries
            .firstOrNull { (name, _) -> name.equals("sha256", ignoreCase = true) || name.equals("x-amz-meta-sha256", ignoreCase = true) }
            ?.value
        return SourceObjectMetadata(size = response.size(), sha256 = checksum)
    }

    override fun presignGet(objectKey: String, responseContentDisposition: String, expirySeconds: Int): String =
        presigner.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .`object`(objectKey)
                .expiry(expirySeconds)
                .extraQueryParams(
                    mapOf(
                        "response-content-type" to "application/pdf",
                        "response-content-disposition" to responseContentDisposition,
                        "response-cache-control" to "no-store",
                    ),
                )
                .build(),
        )

    override fun delete(objectKey: String) {
        ensureBucket()
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(objectKey).build())
    }

    @Synchronized
    private fun ensureBucket() {
        if (bucketReady) return
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
            } catch (exception: Exception) {
                // API and worker can race to initialize the shared development bucket.
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) throw exception
            }
        }
        bucketReady = true
    }
}
