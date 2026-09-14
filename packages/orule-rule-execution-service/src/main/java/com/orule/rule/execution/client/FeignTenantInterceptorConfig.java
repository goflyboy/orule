package com.orule.rule.execution.client;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Forward tenant + operator headers from the current request to Feign calls
 * (RFC-0040 §3.5 / TASK-1.3.3).
 *
 * <p>Reads from {@link com.orule.rule.execution.security.TenantContextHolder}
 * (populated by {@link com.orule.rule.execution.security.TenantInterceptor}).
 *
 * <p>If the holder is empty (e.g. background scheduled job), no headers are
 * appended — the upstream service is then expected to use default service
 * credentials or fail loudly (TASK-1.3.2 tests cover this path).
 */
@Configuration
public class FeignTenantInterceptorConfig {

    @Bean
    public RequestInterceptor tenantForwardingInterceptor() {
        return template -> {
            var ctx = com.orule.rule.execution.security.TenantContextHolder.current();
            if (ctx == null) {
                return;
            }
            if (ctx.tenantId() != null) {
                template.header("X-Tenant-Id", ctx.tenantId());
            }
            if (ctx.operatorId() != null) {
                template.header("X-Operator-Id", ctx.operatorId());
            }
            if (ctx.traceId() != null) {
                template.header("X-Trace-Id", ctx.traceId());
            }
        };
    }
}
