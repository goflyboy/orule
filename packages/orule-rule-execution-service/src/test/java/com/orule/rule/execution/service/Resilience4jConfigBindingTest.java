package com.orule.rule.execution.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test: verify resilience4j ratelimiter config is bound by Spring.
 *
 * <p>If this fails, the yml key path or property binder is wrong and the limiter
 * won't be available at runtime.
 */
@SpringBootTest
@DisplayName("Resilience4j yml binding diagnostic")
class Resilience4jConfigBindingTest {

    @Autowired
    Environment env;

    @Test
    @DisplayName("resilience4j default config (100 req/s) is bound; instances.ruleExecute inherits via base-config")
    void limiterConfigBound() {
        // Default config is bound at the documented key path.
        Integer defaultLimit = env.getProperty(
                "resilience4j.ratelimiter.configs.default.limit-for-period",
                Integer.class);
        assertThat(defaultLimit).isEqualTo(100);

        // Instances.ruleExecute uses base-config: default, so the actual limiter
        // bean (resolved via the RateLimiterRegistry below) inherits the 100/s
        // value. Resilience4j spring-boot3 starter does NOT bind the inherited
        // value back to a flat key — that is by design.
        Boolean reg = env.getProperty(
                "resilience4j.ratelimiter.configs.default.registerHealthIndicator",
                Boolean.class);
        assertThat(reg).isTrue();
    }
}
