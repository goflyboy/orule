package com.orule.rule.execution.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * RuleSet async execution request (RFC-0040 §3.4). POST /api/v1/rule-set-executions body.
 *
 * <p>After submit, clients should poll GET /api/v1/rule-set-executions/{taskId}
 * or subscribe to Kafka {@code rule-set-execution-completed}.
 */
@Schema(description = "RuleSet async execution request")
public record RuleSetExecutionRequest(
        @Schema(description = "RuleSet code (RuleSetType.programCode)",
                example = "ORDER_PROMOTION_SUITE",
                requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128)
        String ruleSetCode,

        @Schema(description = "Input context",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, Object> context
) {
}
