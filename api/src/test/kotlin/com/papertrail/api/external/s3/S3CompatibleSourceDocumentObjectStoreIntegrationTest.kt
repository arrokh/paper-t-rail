package com.papertrail.api.external.s3

import com.papertrail.api.infrastructure.storage.SourceObjectMetadata
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.yaml.snakeyaml.Yaml
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Set PAPER_TRAIL_STORAGE_TEST_ENDPOINT to run against an already-started isolated service. */
class S3CompatibleSourceDocumentObjectStoreIntegrationTest {
    @Test
    fun `configured object storage supports source document upload download metadata deletion and presigned response overrides`() {
        val endpoint = System.getenv(STORAGE_TEST_ENDPOINT_ENV)
            ?: objectStorage?.let { "http://${it.host}:${it.getMappedPort(S3_PORT)}" }
            ?: error("The external object-storage test endpoint is missing.")
        val publicEndpoint = System.getenv(STORAGE_TEST_PUBLIC_ENDPOINT_ENV) ?: endpoint
        val objectKey = "source/document-${System.nanoTime()}/fixture.pdf"
        val content = FIXTURE_CONTENT.toByteArray(Charsets.UTF_8)
        S3CompatibleSourceDocumentObjectStore(
            endpoint = endpoint,
            region = REGION,
            accessKey = TEST_ACCESS_KEY,
            secretKey = TEST_SECRET_KEY,
            publicEndpoint = publicEndpoint,
            pathStyleAccess = true,
            bucket = BUCKET,
        ).use { store ->
            store.put(objectKey, content)

            assertArrayEquals(content, store.get(objectKey))
            assertEquals(
                SourceObjectMetadata(content.size.toLong(), FIXTURE_SHA256),
                store.stat(objectKey),
            )

            val url = store.presignGet(
                objectKey = objectKey,
                responseContentDisposition = "inline; filename=\"paper.pdf\"",
                expirySeconds = EXPIRY_SECONDS,
            )
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

            assertEquals(200, response.statusCode())
            assertArrayEquals(content, response.body())
            assertEquals("application/pdf", response.headers().firstValue("Content-Type").orElse(null))
            assertEquals("inline; filename=\"paper.pdf\"", response.headers().firstValue("Content-Disposition").orElse(null))
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
            assertEquals(EXPIRY_SECONDS.toString(), queryParameters(URI.create(url))["X-Amz-Expires"])

            store.delete(objectKey)
            assertThrows(S3Exception::class.java) { store.stat(objectKey) }
        }
    }

    private fun queryParameters(uri: URI): Map<String, String> = uri.rawQuery
        .split('&')
        .associate { parameter ->
            val (key, value) = parameter.split('=', limit = 2)
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    companion object {
        private const val S3_PORT = 8333
        private const val STORAGE_TEST_ENDPOINT_ENV = "PAPER_TRAIL_STORAGE_TEST_ENDPOINT"
        private const val STORAGE_TEST_PUBLIC_ENDPOINT_ENV = "PAPER_TRAIL_STORAGE_TEST_PUBLIC_ENDPOINT"
        private const val REGION = "us-east-1"
        private const val TEST_ACCESS_KEY = "object-storage-integration-access"
        private const val TEST_SECRET_KEY = "object-storage-integration-secret"
        private const val BUCKET = "source-documents"
        private const val EXPIRY_SECONDS = 600
        private const val FIXTURE_CONTENT = "S3 storage compatibility fixture"
        private const val FIXTURE_SHA256 = "05ed02aca5a0918839efd6ccb8965b7429e3248c99c19b901ea92f87e77f87a6"

        private val composeSettings = objectStorageComposeSettings()

        private val objectStorage = if (System.getenv(STORAGE_TEST_ENDPOINT_ENV) == null) {
            GenericContainer<Nothing>(DockerImageName.parse(composeSettings.image)).apply {
                withCommand(*composeSettings.command.toTypedArray())
                withExposedPorts(S3_PORT)
                withEnv(mapOf(
                    composeSettings.accessKeyVariable to TEST_ACCESS_KEY,
                    composeSettings.secretKeyVariable to TEST_SECRET_KEY,
                    composeSettings.bucketVariable to BUCKET,
                ))
                waitingFor(Wait.forHttp("/status").forPort(S3_PORT).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))
            }
        } else {
            null
        }

        @BeforeAll
        @JvmStatic
        fun startObjectStorageWhenNeeded() {
            objectStorage?.start()
        }

        @AfterAll
        @JvmStatic
        fun stopObjectStorageWhenNeeded() {
            objectStorage?.stop()
        }

        private fun objectStorageComposeSettings(): ObjectStorageComposeSettings {
            val workingDirectory = Path.of(System.getProperty("user.dir"))
            val composeFile = listOf(
                workingDirectory.resolve("../infra/docker-compose.yml").normalize(),
                workingDirectory.resolve("infra/docker-compose.yml").normalize(),
            ).firstOrNull(Files::isRegularFile)
                ?: error("Could not locate infra/docker-compose.yml for the object-storage integration test.")
            val compose = Yaml().load<Map<String, Any>>(Files.readString(composeFile))
            val services = compose["services"] as? Map<*, *>
                ?: error("Compose configuration has no services map.")
            val service = services["object-storage"] as? Map<*, *>
                ?: error("Compose configuration has no object-storage service.")
            val image = service["image"] as? String
                ?: error("Compose object-storage service has no pinned image.")
            val commandItems = service["command"] as? List<*>
                ?: error("Compose object-storage service has no command.")
            val command = commandItems.map { item ->
                item as? String ?: error("Compose object-storage service command contains a non-string value.")
            }
            val environment = service["environment"] as? Map<*, *>
                ?: error("Compose object-storage service has no S3 credentials environment.")
            val environmentNames = environment.keys.filterIsInstance<String>()
            val accessKeyVariable = environmentNames.singleOrNull { it.endsWith("ACCESS_KEY_ID") }
                ?: error("Compose object-storage service has no access-key environment mapping.")
            val secretKeyVariable = environmentNames.singleOrNull { it.endsWith("SECRET_ACCESS_KEY") }
                ?: error("Compose object-storage service has no secret-key environment mapping.")
            val bucketVariable = environmentNames.singleOrNull { it.endsWith("_BUCKET") }
                ?: error("Compose object-storage service has no bucket environment mapping.")
            return ObjectStorageComposeSettings(image, command, accessKeyVariable, secretKeyVariable, bucketVariable)
        }

        private data class ObjectStorageComposeSettings(
            val image: String,
            val command: List<String>,
            val accessKeyVariable: String,
            val secretKeyVariable: String,
            val bucketVariable: String,
        )
    }
}
