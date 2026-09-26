package com.papertrail.api.scholarly.acquisition.client

import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.references.client.BibliographyReference

interface OpenAccessProvider {
    fun discover(reference: BibliographyReference): OpenAccessDiscovery?
    fun fetch(location: OpenAccessLocation): AcquiredFullText
}
