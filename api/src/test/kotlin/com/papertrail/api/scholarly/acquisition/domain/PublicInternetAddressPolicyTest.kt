package com.papertrail.api.scholarly.acquisition.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class PublicInternetAddressPolicyTest {
    @Test
    fun `accepts public IPv4 and global unicast IPv6 addresses`() {
        assertTrue(PublicInternetAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8")))
        assertTrue(PublicInternetAddressPolicy.isPublic(InetAddress.getByName("2001:4860:4860::8888")))
    }

    @Test
    fun `rejects non-global IPv4 ranges and IPv6 special-purpose ranges`() {
        listOf(
            "0.0.0.1",
            "100.100.100.200",
            "192.0.2.1",
            "198.18.0.1",
            "2001:db8::1",
            "2002:c000:0201::1",
        ).forEach { address ->
            assertFalse(PublicInternetAddressPolicy.isPublic(InetAddress.getByName(address)), address)
        }
    }

    @Test
    fun `rejects IPv4-mapped IPv6 addresses when the mapped IPv4 address is non-public`() {
        val mappedPrivateAddress = InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 100.toByte(), 100.toByte(), 100.toByte(), 200.toByte()),
        )

        assertFalse(PublicInternetAddressPolicy.isPublic(mappedPrivateAddress))
    }
}
