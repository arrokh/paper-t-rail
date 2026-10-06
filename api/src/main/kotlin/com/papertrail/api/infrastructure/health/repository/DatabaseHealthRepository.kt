package com.papertrail.api.infrastructure.health.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class DatabaseHealthRepository(
    private val jdbc: JdbcTemplate,
) {
    fun checkConnectivity() {
        jdbc.queryForObject("SELECT 1", Int::class.java)
    }
}
