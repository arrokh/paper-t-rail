package com.papertrail.api.scholarly.acquisition.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegalOpenAccessLocationPolicyTest {
    private val policy = LegalOpenAccessLocationPolicy()

    @Test
    fun `accepts only HTTPS full-text locations with a processing-permitting license`() {
        assertTrue(policy.isUsable(location("https://8.8.8.8/article.pdf", "https://creativecommons.org/licenses/by/4.0/")))
        assertTrue(policy.isUsable(location("https://8.8.8.8/article.pdf", "CC0-1.0")))
        assertFalse(policy.isUsable(location("http://8.8.8.8/article.pdf", "CC-BY")))
        assertFalse(policy.isUsable(location("https://8.8.8.8/article.pdf", "CC-BY-NC")))
        assertFalse(policy.isUsable(location("https://8.8.8.8/article.pdf", null)))
    }

    @Test
    fun `rejects credentials fragments and private content hosts`() {
        assertFalse(policy.isUsable(location("https://user:secret@8.8.8.8/article.pdf", "CC-BY")))
        assertFalse(policy.isUsable(location("https://8.8.8.8/article.pdf#section", "CC-BY")))
        assertFalse(policy.isUsable(location("https://8.8.8.8/article.pdf?token=secret", "CC-BY")))
        assertFalse(policy.isUsable(location("https://127.0.0.1/article.pdf", "CC-BY")))
        assertFalse(policy.isUsable(location("https://10.0.0.1/article.pdf", "CC-BY")))
        assertFalse(policy.isUsable(location("https://169.254.169.254/latest/meta-data", "CC-BY")))
        assertFalse(policy.isUsable(location("https://100.100.100.200/article.pdf", "CC-BY")))
        assertFalse(policy.isUsable(location("https://198.18.0.1/article.pdf", "CC-BY")))
        assertFalse(policy.isUsable(location("https://[::1]/article.pdf", "CC-BY")))
        assertFalse(policy.isUsable(location("https://[2001:db8::1]/article.pdf", "CC-BY")))
        assertTrue(policy.isUsable(location("https://[2001:4860:4860::8888]/article.pdf", "CC-BY")))
        assertFalse(policy.isUsable(location("https://localhost/article.pdf", "CC-BY")))
    }

    @Test
    fun `fixture locations are usable only for the local recorded provider`() {
        assertTrue(policy.isUsable(location("fixture://recorded/paper", "CC0-1.0", "recorded-fixtures")))
        assertFalse(policy.isUsable(location("fixture://recorded/paper", "CC0-1.0", "unpaywall")))
    }

    private fun location(url: String, license: String?, providerId: String = "unpaywall") = OpenAccessLocation(
        url = url,
        license = license,
        version = "publishedVersion",
        hostType = "repository",
        providerId = providerId,
    )
}
