package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import org.springframework.stereotype.Service

@Service
class RecoveryUploadCorsPolicy(
    private val objectStore: SourceDocumentObjectStore,
    private val settings: RecoveryStagingSettings,
) {
    @Volatile
    private var configured = false

    @Synchronized
    fun ensureConfigured() {
        if (configured) return
        try {
            objectStore.configureBrowserUploadCors(settings.browserUploadOrigins)
            configured = true
        } catch (_: Exception) {
            throw RecoveryStagingException("UPLOAD_STORAGE_UNAVAILABLE", 503, "Browser upload storage is not available.")
        }
    }
}
