package com.papertrail.api.scholarly.acquisition.domain

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

object PublicInternetAddressPolicy {
    // Exclude special-use, shared, documentation, benchmark, multicast, and reserved IPv4 networks.
    private val nonPublicIpv4Ranges = listOf(
        cidr("0.0.0.0", 8),
        cidr("10.0.0.0", 8),
        cidr("100.64.0.0", 10),
        cidr("127.0.0.0", 8),
        cidr("169.254.0.0", 16),
        cidr("172.16.0.0", 12),
        cidr("192.0.0.0", 24),
        cidr("192.0.2.0", 24),
        cidr("192.88.99.0", 24),
        cidr("192.168.0.0", 16),
        cidr("198.18.0.0", 15),
        cidr("198.51.100.0", 24),
        cidr("203.0.113.0", 24),
        cidr("224.0.0.0", 4),
        cidr("240.0.0.0", 4),
    )
    // Accept global unicast IPv6 only, excluding documentation, protocol-assignment, and 6to4 ranges.
    private val nonPublicIpv6Ranges = listOf(
        cidr("2001:0::", 23),
        cidr("2001:db8::", 32),
        cidr("2002::", 16),
    )

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false

        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }
    }

    private fun isPublicIpv4(address: ByteArray): Boolean =
        address.size == IPV4_BYTE_COUNT && nonPublicIpv4Ranges.none { it.contains(address) }

    private fun isPublicIpv6(address: ByteArray): Boolean {
        if (address.size != IPV6_BYTE_COUNT) return false
        if (isIpv4MappedAddress(address)) {
            return isPublicIpv4(address.copyOfRange(IPV4_MAPPED_ADDRESS_BYTES, IPV6_BYTE_COUNT))
        }
        if ((address[0].toInt() and IPV6_GLOBAL_UNICAST_MASK) != IPV6_GLOBAL_UNICAST_PREFIX) return false
        return nonPublicIpv6Ranges.none { it.contains(address) }
    }

    private fun isIpv4MappedAddress(address: ByteArray): Boolean =
        address.take(IPV4_MAPPED_PREFIX_BYTES).all { it == 0.toByte() } &&
            address[IPV4_MAPPED_PREFIX_BYTES] == 0xff.toByte() &&
            address[IPV4_MAPPED_PREFIX_BYTES + 1] == 0xff.toByte()

    private fun cidr(networkAddress: String, prefixLength: Int): Cidr = Cidr(
        network = InetAddress.getByName(networkAddress).address,
        prefixLength = prefixLength,
    )

    private data class Cidr(val network: ByteArray, val prefixLength: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            val fullBytes = prefixLength / BITS_PER_BYTE
            if ((0 until fullBytes).any { address[it] != network[it] }) return false
            val remainingBits = prefixLength % BITS_PER_BYTE
            if (remainingBits == 0) return true

            val mask = (0xff shl (BITS_PER_BYTE - remainingBits)) and 0xff
            return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
        }
    }

    private const val BITS_PER_BYTE = 8
    private const val IPV4_BYTE_COUNT = 4
    private const val IPV6_BYTE_COUNT = 16
    private const val IPV4_MAPPED_PREFIX_BYTES = 10
    private const val IPV4_MAPPED_ADDRESS_BYTES = 12
    private const val IPV6_GLOBAL_UNICAST_MASK = 0xe0
    private const val IPV6_GLOBAL_UNICAST_PREFIX = 0x20
}
