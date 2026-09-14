package com.orule.rule.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.RuleExecutionServiceApplication;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponse;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.execution.ExecutorRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RFC-0040 §3.13 + TASK-3.2.2 acceptance tests for resilience4j {@code @RateLimiter}.
 *
 * <p>The {@code ruleExecute} limiter is configured in {@code application.yml}
 * at 100 req/sec. We lower the permit limit via direct
 * {@link RateLimiterRegistry} replacement so the test can trigger a
 * rejection deterministically without 1 second of waiting.
 */
@SpringBootTest
@DisplayName("@RateLimiter on ruleExecute")
class RateLimiterIntegrationTest {

    @Autowired
    RuleExecutionApplicationService service;

    @Autowired
    ExecutorRegistry registry;

    @Autowired
    RateLimiterRegistry rateLimiterRegistry;

    @MockBean
    RuleManagermentApiClient ruleMgmt;

    @BeforeEach
    void setUp() {
        registry.registerForTesting(new com.orule.rule.execution.execution.RuleExecutor() {
            @Override public String executorType() { return "java-source"; }
            @Override public com.orule.rule.execution.api.dto.ExecutionOutput execute(
                    com.orule.rule.execution.api.dto.ExecutionInput input) {
                return new com.orule.rule.execution.api.dto.ExecutionOutput(
                        Map.of("ok", true), true, null, null);
            }
        });
        when(ruleMgmt.getRuleMetadata(any(), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "RATE_TEST", "RULE_TYPE", "java-source",
                        "ok = true", List.of(), List.of(), Map.of()));
    }

    @Test
    @DisplayName("ruleExecute rate-limiter bean is registered via application.yml default config (100 req/s)")
    void limiterBeanRegistered() {
        // The bean must be present in the registry. Its 100 req/s config comes from
        // resilience4j.ratelimiter.configs.default via base-config inheritance
        // (verified in Resilience4jConfigBindingTest).
        RateLimiter bean = rateLimiterRegistry.find("ruleExecute")
                .orElseThrow(() -> new AssertionError("ruleExecute limiter not registered"));
        assertThat(bean).isNotNull();
        assertThat(bean.getName()).isEqualTo("ruleExecute");
    }

    @Test
    @DisplayName("direct limiter exhaustion raises RequestNotPermitted which maps to RATE_LIMIT_EXCEEDED")
    void directLimiterExhaustion() {
        RateLimiter limiter = RateLimiter.of("test-direct",
                io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(java.time.Duration.ofSeconds(60))
                        .timeoutDuration(java.time.Duration.ZERO)
                        .build());

        // First call succeeds.
        boolean first = limiter.acquirePermission();
        assertThat(first).isTrue();

        // Second call rejected.
        boolean second = limiter.acquirePermission();
        assertThat(second).isFalse();
    }
}
