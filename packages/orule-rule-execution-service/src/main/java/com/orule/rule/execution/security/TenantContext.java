package com.orule.rule.execution.security;

/**
 * Per-request tenant + operator context (RFC-0040 §3.5 / TASK-1.3.2).
 *
 * <p>Populated by {@link TenantInterceptor} from the {@code X-Tenant-Id} /
 * {@code X-Operator-Id} headers (validated by JWT, see TASK-1.3.2 wiring).
 *
 * <p>Accessible via {@link TenantContextHolder#current()} from any layer
 * downstream of the controller (service / repository / executor).
 *
 * <p>NOT thread-safe to mutate; use {@link TenantContextHolder#set(TenantContext)}
 * once at request entry and {@link TenantContextHolder#clear()} on exit.
 */
public record TenantContext(
        String tenantId,
        String operatorId,
        String traceId
) {
}
