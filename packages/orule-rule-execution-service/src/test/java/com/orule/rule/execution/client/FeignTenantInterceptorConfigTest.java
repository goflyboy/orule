package com.orule.rule.execution.client;

import com.orule.rule.execution.security.TenantContext;
import com.orule.rule.execution.security.TenantContextHolder;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0040 TASK-1.3.3 acceptance tests for tenant header propagation.
 *
 * <p>Covers only the {@code FeignTenantInterceptorConfig#tenantForwardingInterceptor}
 * lambda.  Full {@code @FeignClient} integration is wired at TASK-2.3.x.
 */
@DisplayName("Feign tenant header interceptor")
class FeignTenantInterceptorConfigTest {

    private final FeignTenantInterceptorConfig config = new FeignTenantInterceptorConfig();

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("forwards X-Tenant-Id, X-Operator-Id, X-Trace-Id when context is present")
    void forwardsAll() {
        TenantContextHolder.set(new TenantContext("tenant-1", "user-1", "trace-1"));
        RequestTemplate tpl = new RequestTemplate().method("GET").uri("/api/v1/rules/{ruleCode}", true);
        config.tenantForwardingInterceptor().apply(tpl);

        Collection<String> tenants = tpl.headers().get("X-Tenant-Id");
        Collection<String> operators = tpl.headers().get("X-Operator-Id");
        Collection<String> traces = tpl.headers().get("X-Trace-Id");

        assertThat(tenants).containsExactly("tenant-1");
        assertThat(operators).containsExactly("user-1");
        assertThat(traces).containsExactly("trace-1");
    }

    @Test
    @DisplayName("no headers appended when holder is empty (background task path)")
    void noHeadersWhenEmpty() {
        // No set() call → holder is null
        RequestTemplate tpl = new RequestTemplate().method("GET").uri("/api/v1/rules/X", true);
        config.tenantForwardingInterceptor().apply(tpl);

        assertThat(tpl.headers().get("X-Tenant-Id")).isNull();
        assertThat(tpl.headers().get("X-Operator-Id")).isNull();
    }

    @Test
    @DisplayName("missing traceId header is omitted (allowed optional)")
    void missingTraceId() {
        TenantContextHolder.set(new TenantContext("tenant-1", "user-1", null));
        RequestTemplate tpl = new RequestTemplate().method("GET").uri("/api/v1/rules/X", true);
        config.tenantForwardingInterceptor().apply(tpl);

        assertThat(tpl.headers().get("X-Tenant-Id")).containsExactly("tenant-1");
        assertThat(tpl.headers().get("X-Operator-Id")).containsExactly("user-1");
        assertThat(tpl.headers().get("X-Trace-Id")).isNull();
    }
}
