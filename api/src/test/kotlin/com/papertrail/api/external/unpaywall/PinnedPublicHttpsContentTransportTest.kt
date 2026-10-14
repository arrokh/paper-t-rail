package com.papertrail.api.external.unpaywall

import com.papertrail.api.scholarly.acquisition.domain.UnsafeOpenAccessLocationException
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SNIHostName
import java.security.cert.X509Certificate

class PinnedPublicHttpsContentTransportTest {
    @Test
    fun `pins content connection to the validated DNS answer while preserving TLS and HTTP host identity`() {
        val serverContext = testServerSslContext()
        val connection = AtomicReference<ConnectionCapture>()
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverContext)
            createContext("/paper.pdf") { exchange ->
                val httpsExchange = exchange as HttpsExchange
                val session = httpsExchange.sslSession as ExtendedSSLSession
                connection.set(
                    ConnectionCapture(
                        peerAddress = httpsExchange.remoteAddress.address,
                        hostHeader = httpsExchange.requestHeaders.getFirst("Host"),
                        serverNames = session.requestedServerNames.filterIsInstance<SNIHostName>().map(SNIHostName::getAsciiName),
                    ),
                )
                val body = "%PDF-1.4 controlled test asset".toByteArray()
                httpsExchange.responseHeaders.add("Content-Type", "application/pdf")
                httpsExchange.sendResponseHeaders(200, body.size.toLong())
                httpsExchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        try {
            val firstAddress = InetAddress.getByName("127.0.0.1")
            val changedPrivateAnswer = InetAddress.getByName("10.23.45.67")
            val dnsLookups = AtomicInteger()
            val transport = PinnedPublicHttpsContentTransport(
                resolveAddresses = {
                    when (dnsLookups.getAndIncrement()) {
                        0 -> listOf(firstAddress)
                        else -> listOf(changedPrivateAnswer)
                    }
                },
                isPublicAddress = { it == firstAddress },
                socketFactory = clientTrustAllSocketFactory(),
            )
            val url = "https://rebind.test:${server.address.port}/paper.pdf"

            val response = transport.fetch(url, 1_000, setOf("application/pdf", "text/plain"))

            assertEquals(200, response.statusCode)
            assertEquals("application/pdf", response.mediaType)
            assertEquals("%PDF-1.4 controlled test asset", response.bytes.toString(Charsets.UTF_8))
            assertEquals(1, dnsLookups.get(), "The connector must use the address validated before connecting, not re-resolve the host.")
            val observed = requireNotNull(connection.get())
            assertEquals(firstAddress, observed.peerAddress)
            assertEquals("rebind.test:${server.address.port}", observed.hostHeader)
            assertEquals(listOf("rebind.test"), observed.serverNames)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `TLS hostname verification rejects a certificate that does not match the original URL host`() {
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(testServerSslContext())
            createContext("/paper.pdf") { exchange ->
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val transport = PinnedPublicHttpsContentTransport(
                resolveAddresses = { listOf(loopback) },
                isPublicAddress = { it == loopback },
                socketFactory = clientTrustAllSocketFactory(),
            )

            assertThrows(UnsafeOpenAccessLocationException::class.java) {
                transport.fetch("https://wrong-host.test:${server.address.port}/paper.pdf", 1_000, setOf("application/pdf"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `content transport decodes chunked responses within the existing byte ceiling`() {
        val body = "Small chunked text fixture".toByteArray()
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(testServerSslContext())
            createContext("/paper.txt") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, 0)
                runCatching { exchange.responseBody.use { it.write(body) } }
                exchange.close()
            }
        }
        server.start()
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val transport = PinnedPublicHttpsContentTransport(
                resolveAddresses = { listOf(loopback) },
                isPublicAddress = { it == loopback },
                socketFactory = clientTrustAllSocketFactory(),
            )

            val response = transport.fetch(
                "https://rebind.test:${server.address.port}/paper.txt",
                1_000,
                setOf("text/plain"),
            )

            assertEquals(body.toString(Charsets.UTF_8), response.bytes.toString(Charsets.UTF_8))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `content transport reads only one byte beyond the configured limit`() {
        val body = ByteArray(128) { 'x'.code.toByte() }
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(testServerSslContext())
            createContext("/paper.txt") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, body.size.toLong())
                runCatching { exchange.responseBody.use { it.write(body) } }
                exchange.close()
            }
        }
        server.start()
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val transport = PinnedPublicHttpsContentTransport(
                resolveAddresses = { listOf(loopback) },
                isPublicAddress = { it == loopback },
                socketFactory = clientTrustAllSocketFactory(),
            )

            val response = transport.fetch(
                "https://rebind.test:${server.address.port}/paper.txt",
                10,
                setOf("text/plain"),
            )

            assertEquals(11, response.bytes.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects a response that ends before its declared content length`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = testServerSslContext().serverSocketFactory
            .createServerSocket(0, 1, loopback) as SSLServerSocket
        val body = "Incomplete controlled asset".toByteArray()
        val response = (
            "HTTP/1.1 200 OK\\r\\n" +
                "Content-Type: text/plain\\r\\n" +
                "Content-Length: ${body.size + 10}\\r\\n" +
                "Connection: close\\r\\n\\r\\n"
            ).toByteArray(Charsets.US_ASCII) + body
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    val request = socket.inputStream.bufferedReader(Charsets.ISO_8859_1)
                    while (request.readLine().isNotEmpty()) Unit
                    socket.outputStream.apply {
                        write(response)
                        flush()
                    }
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            val transport = PinnedPublicHttpsContentTransport(
                resolveAddresses = { listOf(loopback) },
                isPublicAddress = { it == loopback },
                socketFactory = clientTrustAllSocketFactory(),
            )

            assertThrows(IOException::class.java) {
                transport.fetch(
                    "https://rebind.test:${server.localPort}/truncated.txt",
                    1_000,
                    setOf("text/plain"),
                )
            }
        } finally {
            server.close()
            serverThread.join(5_000)
        }
        assertEquals(null, serverFailure.get())
    }

    @Test
    fun `content transport does not follow redirects`() {
        val redirectedRequests = AtomicInteger()
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(testServerSslContext())
            createContext("/redirect.pdf") { exchange ->
                exchange.responseHeaders.add("Location", "/paper.pdf")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/paper.pdf") { exchange ->
                redirectedRequests.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val transport = PinnedPublicHttpsContentTransport(
                resolveAddresses = { listOf(loopback) },
                isPublicAddress = { it == loopback },
                socketFactory = clientTrustAllSocketFactory(),
            )

            val response = transport.fetch(
                "https://rebind.test:${server.address.port}/redirect.pdf",
                1_000,
                setOf("application/pdf"),
            )

            assertEquals(302, response.statusCode)
            assertEquals(0, redirectedRequests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a private DNS answer is rejected before a content connection starts`() {
        val requests = AtomicInteger()
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(testServerSslContext())
            createContext("/paper.pdf") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val transport = PinnedPublicHttpsContentTransport(resolveAddresses = { listOf(loopback) })

            assertThrows(UnsafeOpenAccessLocationException::class.java) {
                transport.fetch("https://rebind.test:${server.address.port}/paper.pdf", 1_000, setOf("application/pdf"))
            }
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a host whose DNS answer cannot be safely validated is rejected before connecting`() {
        val transport = PinnedPublicHttpsContentTransport(
            resolveAddresses = { throw UnknownHostException("synthetic DNS failure") },
        )

        assertThrows(UnsafeOpenAccessLocationException::class.java) {
            transport.fetch("https://unresolvable.test/paper.pdf", 1_000, setOf("application/pdf", "text/plain"))
        }
    }

    private fun testServerSslContext(): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/tls/dns-rebinding-test.p12").use { stream ->
            keyStore.load(stream, TEST_KEYSTORE_PASSWORD)
        }
        val keyManagerFactory = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, TEST_KEYSTORE_PASSWORD)
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, SecureRandom())
        }
    }

    private fun clientTrustAllSocketFactory(): SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }.socketFactory
    }

    private data class ConnectionCapture(
        val peerAddress: InetAddress,
        val hostHeader: String?,
        val serverNames: List<String>,
    )

    companion object {
        private val TEST_KEYSTORE_PASSWORD = "changeit".toCharArray()
    }
}
