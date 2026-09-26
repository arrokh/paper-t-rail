package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText

interface CitedPaperTextExtractor {
    fun extract(fullText: AcquiredFullText): String
}
