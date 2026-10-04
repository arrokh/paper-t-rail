package com.papertrail.api.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ProductionProviderDefaultsTest {
    @Test
    fun `production profile defaults to Laya with System One aggregation enabled`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=production")
            .run { context ->
                assertEquals("true", context.environment.getProperty("paper-trail.providers.laya.enabled"))
                assertEquals("laya", context.environment.getProperty("paper-trail.providers.system-one.default-provider"))
                assertEquals("true", context.environment.getProperty("paper-trail.analysis.system-one-aggregation.enabled"))
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
                "SYSTEM_ONE_AGGREGATION_ENABLED=false",
            )
            .run { context ->
                assertEquals("false", context.environment.getProperty("paper-trail.providers.laya.enabled"))
                assertEquals("mock", context.environment.getProperty("paper-trail.providers.system-one.default-provider"))
                assertEquals("false", context.environment.getProperty("paper-trail.analysis.system-one-aggregation.enabled"))
            }
    }

    @Test
    fun `retrieval candidate pools default to three and accept deployment overrides`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .run { context ->
                assertEquals("3", context.environment.getProperty("paper-trail.analysis.retrieval.vector-candidates"))
                assertEquals("3", context.environment.getProperty("paper-trail.analysis.retrieval.lexical-candidates"))
                assertEquals("3", context.environment.getProperty("paper-trail.analysis.retrieval.final-candidates"))
            }

        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                "PAPER_RETRIEVAL_VECTOR_CANDIDATES=7",
                "PAPER_RETRIEVAL_LEXICAL_CANDIDATES=4",
                "PAPER_RETRIEVAL_FINAL_CANDIDATES=6",
            )
            .run { context ->
                assertEquals("7", context.environment.getProperty("paper-trail.analysis.retrieval.vector-candidates"))
                assertEquals("4", context.environment.getProperty("paper-trail.analysis.retrieval.lexical-candidates"))
                assertEquals("6", context.environment.getProperty("paper-trail.analysis.retrieval.final-candidates"))
            }
    }

    @Test
    fun `local default remains Laya with System One aggregation enabled`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .run { context ->
                assertEquals("true", context.environment.getProperty("paper-trail.providers.laya.enabled"))
                assertEquals("laya", context.environment.getProperty("paper-trail.providers.system-one.default-provider"))
                assertEquals("true", context.environment.getProperty("paper-trail.analysis.system-one-aggregation.enabled"))
            }
    }
}
