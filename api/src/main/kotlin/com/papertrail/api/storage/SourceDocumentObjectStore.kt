package com.papertrail.api.storage

import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

interface SourceDocumentObjectStore {
    fun put(objectKey: String, content: ByteArray)
    fun get(objectKey: String): ByteArray
    fun delete(objectKey: String)
}

@Component
class MinioSourceDocumentObjectStore(
    @Value("\${paper-trail.storage.endpoint}") endpoint: String,
    @Value("\${paper-trail.storage.region}") region: String,
    @Value("\${paper-trail.storage.access-key}") accessKey: String,
    @Value("\${paper-trail.storage.secret-key}") secretKey: String,
    @Value("\${paper-trail.storage.bucket}") private val bucket: String,
) : SourceDocumentObjectStore {
    private val client = MinioClient.builder()
        .endpoint(endpoint)
        .region(region)
        .credentials(accessKey, secretKey)
        .build()

    @Volatile
    private var bucketReady = false

    override fun put(objectKey: String, content: ByteArray) {
        ensureBucket()
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .`object`(objectKey)
                .stream(ByteArrayInputStream(content), content.size.toLong(), -1)
                .contentType("application/pdf")
                .build(),
        )
    }

    override fun get(objectKey: String): ByteArray {
        ensureBucket()
        client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(objectKey).build()).use { input ->
            return input.readBytes()
        }
    }

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
