package com.papertrail.api.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NetworkBindingGuardTest {
    @Test
    fun `allows a localhost-only bind`() {
        NetworkBindingGuard("127.0.0.1").requireTrustedBindAddress()
    }

    @Test
    fun `allows a specific private-network bind`() {
        NetworkBindingGuard("172.30.0.10").requireTrustedBindAddress()
    }

    @Test
    fun `rejects wildcard binds without authentication`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkBindingGuard("0.0.0.0").requireTrustedBindAddress()
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkBindingGuard("::").requireTrustedBindAddress()
        }
    }

    @Test
    fun `rejects public addresses without authentication`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkBindingGuard("8.8.8.8").requireTrustedBindAddress()
        }
    }
}
