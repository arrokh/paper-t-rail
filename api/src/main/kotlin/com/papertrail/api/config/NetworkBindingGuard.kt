package com.papertrail.api.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress

/** Prevents an unauthenticated instance from binding to a public or wildcard address. */
@Component
class NetworkBindingGuard(
    @Value("\${server.address}") private val bindAddress: String,
) {
    @PostConstruct
    fun requireTrustedBindAddress() {
        val address = try {
            InetAddress.getByName(bindAddress)
        } catch (exception: Exception) {
            throw IllegalStateException("APP_BIND_ADDRESS must be a loopback or private-network address", exception)
        }
        require(!address.isAnyLocalAddress && (address.isLoopbackAddress || address.isSiteLocalAddress)) {
            "Unauthenticated Paper T-Rail may bind only to localhost or a trusted private-network address"
        }
    }
}
