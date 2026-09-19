package com.orule.rule.execution.client;

import com.orule.common.dto.ObjectTypeDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Feign client for {@code rule-management-service} (RFC-0040 §3.5 / RFC-0045 §4.3).
 *
 * <p>v2 endpoints (RFC-0045):
 * <ul>
 *   <li>{@code GET /api/v1/rules/{ruleCode}} → {@link RuleMetadataResponseV2}.</li>
 *   <li>{@code GET /api/v1/object-types/by-program-code} → {@link ObjectTypeDto}.</li>
 * </ul>
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

    /** RFC-0045 §4.4: v2 metadata — adds domainCode + objectTypeCodes. */
    @GetMapping("/api/v1/rules/{ruleCode}")
    RuleMetadataResponseV2 getRuleMetadata(
            @PathVariable("ruleCode") String ruleCode,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Operator-Id") String operatorId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    );

    /** RFC-0045 §4.3: pull ObjectType + attributes + enumValues by composite key. */
    @GetMapping("/api/v1/object-types/by-program-code")
    ObjectTypeDto getObjectTypeByProgramCode(
            @RequestParam("domainCode") String domainCode,
            @RequestParam("programCode") String programCode,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Operator-Id") String operatorId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId);
}
