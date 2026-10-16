package com.papertrail.api.external.unpaywall

import com.papertrail.api.scholarly.acquisition.domain.PublicInternetAddressPolicy
import com.papertrail.api.scholarly.acquisition.domain.UnsafeOpenAccessLocationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.security.cert.CertificateException
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class PinnedPublicHttpsContentTransport(
    private val resolveAddresses: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val isPublicAddress: (InetAddress) -> Boolean = PublicInternetAddressPolicy::isPublic,
    private val socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : OpenAccessContentTransport {
    override fun fetch(url: String, maximumBytes: Int, acceptedMediaTypes: Set<String>): OpenAccessContentResponse {
        require(maximumBytes in 1 until Int.MAX_VALUE) { "Open-access content limit is outside the supported range." }
        require(acceptedMediaTypes.isNotEmpty() && acceptedMediaTypes.all(SAFE_MEDIA_TYPE::matches)) {
            "Open-access accepted media types must be safe MIME types."
        }
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw UnsafeOpenAccessLocationException("Open-access content URL cannot be safely validated.")
        val originalHost = uri.host?.removeSurrounding("[", "]")
        if (uri.scheme != "https" || originalHost.isNullOrBlank() || uri.userInfo != null || uri.rawQuery != null || uri.fragment != null) {
            throw UnsafeOpenAccessLocationException("Open-access content URL cannot be safely validated.")
        }
        if (!isIpLiteral(originalHost) && runCatching { SNIHostName(originalHost) }.isFailure) {
            throw UnsafeOpenAccessLocationException("Open-access content host cannot be safely validated.")
        }
        val validatedAddresses = try {
            resolveAddresses(originalHost)
        } catch (_: Exception) {
            throw UnsafeOpenAccessLocationException("Open-access content host could not be safely validated.")
        }
        val allAddressesArePublic = runCatching {
            validatedAddresses.isNotEmpty() && validatedAddresses.all(isPublicAddress)
        }.getOrDefault(false)
        if (!allAddressesArePublic) {
            throw UnsafeOpenAccessLocationException("Open-access content host must resolve only to public addresses.")
        }

        val port = uri.port.takeIf { it >= 0 } ?: HTTPS_PORT
        val socket = Socket(Proxy.NO_PROXY)
        try {
            socket.connect(InetSocketAddress(validatedAddresses.first(), port), connectTimeoutMillis)
            val connectedPeer = socket.inetAddress
            if (connectedPeer !in validatedAddresses || runCatching { !isPublicAddress(connectedPeer) }.getOrDefault(true)) {
                throw UnsafeOpenAccessLocationException("Open-access content connection did not reach a validated public address.")
            }
            socket.soTimeout = readTimeoutMillis
            val tlsSocket = socketFactory.createSocket(socket, originalHost, port, true) as SSLSocket
            val sslParameters = tlsSocket.sslParameters
            sslParameters.endpointIdentificationAlgorithm = HTTPS_ENDPOINT_IDENTIFICATION
            sslParameters.serverNames = if (isIpLiteral(originalHost)) emptyList() else listOf(SNIHostName(originalHost))
            tlsSocket.sslParameters = sslParameters
            try {
                tlsSocket.startHandshake()
            } catch (failure: SSLHandshakeException) {
                if (hasCertificateValidationFailure(failure)) {
                    throw UnsafeOpenAccessLocationException("Open-access content host could not be verified for its original URL hostname.")
                }
                throw failure
            }
            return tlsSocket.use { readResponse(it, uri, acceptedMediaTypes, maximumBytes) }
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }

    private fun readResponse(
        socket: SSLSocket,
        uri: URI,
        acceptedMediaTypes: Set<String>,
        maximumBytes: Int,
    ): OpenAccessContentResponse {
        val output = socket.outputStream
        val host = requireNotNull(uri.rawAuthority)
        val path = URI(uri.toASCIIString()).rawPath.takeIf(String::isNotEmpty) ?: "/"
        output.write(
            (
                "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Accept: ${acceptedMediaTypes.joinToString(", ")}\r\n" +
                    "Accept-Encoding: identity\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        output.flush()

        val input = socket.inputStream.buffered()
        val headerReader = BoundedLineReader(input, MAX_RESPONSE_HEADER_BYTES)
        val status = parseStatus(headerReader.readLine())
        val headers = readHeaders(headerReader)
        val mediaType = headers.singleHeaderValue(CONTENT_TYPE_HEADER)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (status !in 200..299 || status in BODYLESS_STATUSES || mediaType !in acceptedMediaTypes) {
            return OpenAccessContentResponse(status, mediaType, ByteArray(0))
        }

        val contentEncoding = headers.singleHeaderValue(CONTENT_ENCODING_HEADER)?.trim()
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
            throw IOException("Open-access content host returned an unsupported content encoding.")
        }
        val bodyLimit = maximumBytes + 1
        val contentLengthHeader = headers.singleHeaderValue(CONTENT_LENGTH_HEADER)
        val contentLength = contentLengthHeader?.toLongOrNull()
            ?: if (contentLengthHeader == null) null else throw IOException("Open-access content response has an invalid length.")
        if (contentLength != null && contentLength < 0) throw IOException("Open-access content response has an invalid length.")
        val transferEncoding = headers.singleHeaderValue(TRANSFER_ENCODING_HEADER)
            ?.trim()?.lowercase(Locale.ROOT)
        if (headers.containsKey(TRANSFER_ENCODING_HEADER) && contentLength != null) {
            throw IOException("Open-access content response has ambiguous body framing.")
        }
        val body = when (transferEncoding) {
            null -> {
                val bytesToRead = (contentLength?.coerceAtMost(bodyLimit.toLong()) ?: bodyLimit.toLong()).toInt()
                input.readNBytes(bytesToRead).also { bytes ->
                    if (contentLength != null && bytes.size != bytesToRead) {
                        throw IOException("Open-access content response ended before its declared length.")
                    }
                }
            }
            "chunked" -> readChunkedBody(input, bodyLimit)
            else -> throw IOException("Open-access content host returned unsupported transfer encoding.")
        }
        return OpenAccessContentResponse(status, mediaType, body)
    }

    private fun parseStatus(line: String): Int {
        val match = STATUS_LINE.matchEntire(line) ?: throw IOException("Open-access content response has an invalid status line.")
        return match.groupValues[1].toInt()
    }

    private fun readHeaders(reader: BoundedLineReader): Map<String, List<String>> {
        val headers = linkedMapOf<String, MutableList<String>>()
        while (true) {
            val line = reader.readLine()
            if (line.isEmpty()) return headers
            if (line.first().isWhitespace()) throw IOException("Open-access content response has a malformed header.")
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("Open-access content response has a malformed header.")
            val name = line.substring(0, colon).lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            headers.getOrPut(name, ::mutableListOf).add(value)
        }
    }

    private fun readChunkedBody(input: InputStream, byteLimit: Int): ByteArray {
        val body = ByteArrayOutputStream(minOf(byteLimit, 8_192))
        var framingBytes = 0
        while (body.size() < byteLimit) {
            val chunkLine = readBoundedLine(input, MAX_RESPONSE_HEADER_LINE_BYTES)
            framingBytes += chunkLine.length + CRLF_BYTES
            if (framingBytes > MAX_CHUNK_FRAMING_BYTES) throw IOException("Open-access content response framing is too large.")
            val chunkSize = chunkLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Open-access content response has invalid chunk framing.")
            if (chunkSize < 0) throw IOException("Open-access content response has invalid chunk framing.")
            if (chunkSize == 0L) {
                var trailerBytes = 0
                while (true) {
                    val trailer = readBoundedLine(input, MAX_RESPONSE_HEADER_LINE_BYTES)
                    trailerBytes += trailer.length + CRLF_BYTES
                    framingBytes += trailer.length + CRLF_BYTES
                    if (trailerBytes > MAX_RESPONSE_HEADER_BYTES || framingBytes > MAX_CHUNK_FRAMING_BYTES) {
                        throw IOException("Open-access content response trailers are too large.")
                    }
                    if (trailer.isEmpty()) break
                    if (trailer.first().isWhitespace() || trailer.indexOf(':') <= 0) {
                        throw IOException("Open-access content response has malformed trailers.")
                    }
                }
                return body.toByteArray()
            }
            val remaining = byteLimit - body.size()
            val bytesToRead = minOf(chunkSize, remaining.toLong()).toInt()
            val chunk = input.readNBytes(bytesToRead)
            if (chunk.size != bytesToRead) throw IOException("Open-access content response ended inside a chunk.")
            body.write(chunk)
            if (chunkSize > bytesToRead) return body.toByteArray()
            framingBytes += CRLF_BYTES
            if (framingBytes > MAX_CHUNK_FRAMING_BYTES) throw IOException("Open-access content response framing is too large.")
            if (input.read() != CARRIAGE_RETURN || input.read() != LINE_FEED) {
                throw IOException("Open-access content response has invalid chunk framing.")
            }
        }
        return body.toByteArray()
    }

    private fun readBoundedLine(input: InputStream, maximumBytes: Int): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= maximumBytes) {
            val current = input.read()
            if (current < 0) throw IOException("Open-access content response ended inside a header line.")
            if (current == LINE_FEED) {
                val line = bytes.toByteArray()
                if (line.isEmpty() || line.last() != CARRIAGE_RETURN.toByte()) {
                    throw IOException("Open-access content response requires CRLF line endings.")
                }
                return line.copyOf(line.size - 1).toString(Charsets.ISO_8859_1)
            }
            bytes.write(current)
        }
        throw IOException("Open-access content response header line is too large.")
    }

    private fun Map<String, List<String>>.singleHeaderValue(name: String): String? {
        val values = this[name] ?: return null
        if (values.size != 1) throw IOException("Open-access content response has duplicate $name headers.")
        return values.single()
    }

    private fun hasCertificateValidationFailure(failure: Throwable): Boolean {
        var cause: Throwable? = failure
        while (cause != null) {
            if (cause is CertificateException) return true
            val next = cause.cause
            if (next === cause) break
            cause = next
        }
        return false
    }

    private fun isIpLiteral(host: String): Boolean = host.contains(':') || IPV4_LITERAL.matches(host)

    private inner class BoundedLineReader(
        private val input: InputStream,
        private val maximumTotalBytes: Int,
    ) {
        private var totalBytes = 0

        fun readLine(): String {
            val line = readBoundedLine(input, MAX_RESPONSE_HEADER_LINE_BYTES)
            totalBytes += line.length + CRLF_BYTES
            if (totalBytes > maximumTotalBytes) throw IOException("Open-access content response headers are too large.")
            return line
        }
    }

    companion object {
        private const val HTTPS_PORT = 443
        private const val HTTPS_ENDPOINT_IDENTIFICATION = "HTTPS"
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        private const val MAX_RESPONSE_HEADER_BYTES = 32 * 1_024
        private const val MAX_RESPONSE_HEADER_LINE_BYTES = 8 * 1_024
        private const val MAX_CHUNK_FRAMING_BYTES = 32 * 1_024
        private const val CRLF_BYTES = 2
        private const val CARRIAGE_RETURN = 13
        private const val LINE_FEED = 10
        private val IPV4_LITERAL = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private val STATUS_LINE = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*)?")
        private val SAFE_MEDIA_TYPE = Regex("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$")
        private val BODYLESS_STATUSES = setOf(204, 205, 304)
        private const val CONTENT_TYPE_HEADER = "content-type"
        private const val CONTENT_LENGTH_HEADER = "content-length"
        private const val CONTENT_ENCODING_HEADER = "content-encoding"
        private const val TRANSFER_ENCODING_HEADER = "transfer-encoding"
    }
}
