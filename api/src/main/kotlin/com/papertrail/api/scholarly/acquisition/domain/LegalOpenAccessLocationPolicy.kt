package com.papertrail.api.scholarly.acquisition.domain

import java.net.InetAddress
import java.net.URI

class LegalOpenAccessLocationPolicy {
    fun isUsable(location: OpenAccessLocation): Boolean {
        if (normalizeLicense(location.license) !in PROCESSING_LICENSES) return false
        if (location.providerId == RECORDED_FIXTURES_PROVIDER) return location.url.startsWith("fixture://")

        val uri = runCatching { URI(location.url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
            uri.rawQuery != null || location.url.length > MAX_URL_LENGTH
        ) return false
        val host = uri.host.removeSurrounding("[", "]")
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost", ignoreCase = true) ||
            host.endsWith(".local", ignoreCase = true) || host.endsWith(".internal", ignoreCase = true)
        ) return false
        if (host.contains(':') || host.matches(IPV4_LITERAL)) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (!isPublicAddress(address)) return false
        }
        return true
    }

    private fun normalizeLicense(license: String?): String {
        val normalized = license
            ?.trim()
            ?.lowercase()
            ?.removePrefix("https://creativecommons.org/licenses/")
            ?.removeSuffix("/")
            ?.replace(Regex("[-/ ]+v?[0-9]+(?:\\.[0-9]+)*$"), "")
            ?.replace(Regex("[^a-z0-9]"), "")
            .orEmpty()
        return if (normalized == "by") "ccby" else normalized
    }

    private fun isPublicAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !address.isMulticastAddress

    companion object {
        const val RECORDED_FIXTURES_PROVIDER = "recorded-fixtures"
        private val PROCESSING_LICENSES = setOf("cc0", "ccby", "publicdomain")
        private val IPV4_LITERAL = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private const val MAX_URL_LENGTH = 4096
    }
}
