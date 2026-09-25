package com.papertrail.api.http

import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/health", produces = [MediaType.APPLICATION_JSON_VALUE])
class HealthController(private val jdbc: JdbcTemplate) {
    @GetMapping
    fun checkDatabase(): Map<String, String> {
        jdbc.queryForObject("SELECT 1", Int::class.java)
        return mapOf("status" to "ok")
    }
}
