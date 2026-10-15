package com.papertrail.api.analysis.recovery.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

class RecoverySubmissionDefaultTest {
    @Test
    fun `application configuration defaults recovery submissions to disabled`() {
        val context = SpringApplicationBuilder(RecoverySubmissionGate::class.java)
            .web(WebApplicationType.NONE)
            .properties("spring.main.banner-mode=off")
            .run()

        try {
            assertFalse(context.getBean(RecoverySubmissionGate::class.java).isEnabled)
        } finally {
            context.close()
        }
    }
}
