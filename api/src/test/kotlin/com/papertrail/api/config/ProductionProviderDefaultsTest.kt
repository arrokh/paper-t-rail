package com.papertrail.api.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ProductionProviderDefaultsTest {
    @Test
    fun `production profile defaults to Laya with experimental aggregation enabled`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=production")
            .run { context ->
                assertEquals("true", context.environment.getProperty("paper-trail.providers.laya.enabled"))
                assertEquals("laya", context.environment.getProperty("paper-trail.providers.system-one.default-provider"))
                assertEquals("true", context.environment.getProperty("paper-trail.analysis.local-laya-aggregation.enabled"))
            }
    }

    @Test
    fun `production profile still honors explicit provider opt outs`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                "spring.profiles.active=production",
                "LAYA_ENABLED=false",
                "SYSTEM_ONE_DEFAULT_PROVIDER=mock",
                "LOCAL_LAYA_AGGREGATION_ENABLED=false",
            )
            .run { context ->
                assertEquals("false", context.environment.getProperty("paper-trail.providers.laya.enabled"))
                assertEquals("mock", context.environment.getProperty("paper-trail.providers.system-one.default-provider"))
                assertEquals("false", context.environment.getProperty("paper-trail.analysis.local-laya-aggregation.enabled"))
            }
    }

    @Test
    fun `local default remains Laya with experimental aggregation enabled`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .run { context ->
                assertEquals("true", context.environment.getProperty("paper-trail.providers.laya.enabled"))
                assertEquals("laya", context.environment.getProperty("paper-trail.providers.system-one.default-provider"))
                assertEquals("true", context.environment.getProperty("paper-trail.analysis.local-laya-aggregation.enabled"))
            }
    }
}
