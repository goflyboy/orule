package com.orule.rule.execution.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Feign client for {@code rule-management-service} (RFC-0040 §3.5 / TASK-1.3.3).
 *
 * <p>Endpoint: {@code GET /api/v1/rules/{ruleCode}} → {@link RuleMetadataResponse}.
 *
 * <p>The base URL is supplied via {@code rule-management.base-url} in
 * {@code application.yml}; the connection / read timeouts come from the
 * default Feign client config.
 */
@FeignClient(
        name = "rule-management-service",
        url = "${rule-management.base-url:http://rule-management-service:8080}"
)
public interface RuleManagermentApiClient {

    /**
     * Fetch the published-rule metadata for {@code ruleCode}.
     *
     * <p>Required headers (forwarded by Feign request interceptor in production,
     * wired in TASK-1.3.2 via {@code FeignRequestInterceptor}:
     * {@code X-Tenant-Id}, {@code X-Operator-Id}, {@code X-Trace-Id}.
     */
    @GetMapping("/api/v1/rules/{ruleCode}")
    RuleMetadataResponse getRuleMetadata(
            @PathVariable("ruleCode") String ruleCode,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Operator-Id") String operatorId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    );
}
