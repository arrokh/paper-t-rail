package com.papertrail.api.document.repository

import com.papertrail.api.document.service.SourceDocumentEgressHandoffLock
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executor
import javax.sql.DataSource

@Component
class PostgresSourceDocumentEgressHandoffLock(
    private val dataSource: DataSource,
) : SourceDocumentEgressHandoffLock {
    override fun acquireForRun(analysisRunId: UUID): AutoCloseable {
        val connection = dataSource.connection
        try {
            connection.autoCommit = true
            val documentId = connection.prepareStatement(ANALYSIS_RUN_DOCUMENT_QUERY).use { statement ->
                statement.setObject(1, analysisRunId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getObject("document_id", UUID::class.java) else null
                }
            }
            if (documentId == null) {
                connection.close()
                return AutoCloseable {}
            }

            connection.prepareStatement(ACQUIRE_SHARED_LOCK_QUERY).use { statement ->
                statement.setString(1, documentId.toString())
                statement.executeQuery().use { resultSet -> check(resultSet.next()) }
            }
            return SharedDocumentLock(connection, documentId)
        } catch (failure: Exception) {
            discard(connection)
            throw failure
        }
    }

    private class SharedDocumentLock(
        private val connection: Connection,
        private val documentId: UUID,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true

            val released = runCatching {
                connection.prepareStatement(RELEASE_SHARED_LOCK_QUERY).use { statement ->
                    statement.setString(1, documentId.toString())
                    statement.executeQuery().use { resultSet -> resultSet.next() && resultSet.getBoolean(1) }
                }
            }.getOrDefault(false)

            if (!released) discard(connection) else runCatching { connection.close() }
        }
    }

    companion object {
        private const val ANALYSIS_RUN_DOCUMENT_QUERY = "SELECT document_id FROM analysis_runs WHERE id = ?"
        private const val ACQUIRE_SHARED_LOCK_QUERY =
            "SELECT pg_advisory_lock_shared(hashtextextended(?::text, 0))"
        private const val RELEASE_SHARED_LOCK_QUERY =
            "SELECT pg_advisory_unlock_shared(hashtextextended(?::text, 0))"

        private val directExecutor = Executor { command -> command.run() }

        private fun discard(connection: Connection) {
            runCatching { connection.abort(directExecutor) }
            runCatching { connection.close() }
        }
    }
}
