package com.papertrail.api.document.service

import java.util.UUID

fun interface SourceDocumentEgressHandoffLock {
    fun acquireForRun(analysisRunId: UUID): AutoCloseable
}
