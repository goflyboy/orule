package com.orule.rule.execution.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Single rule execution request (RFC-0040 §3.4). POST /api/v1/rule-executions body.
 *
 * <p>Note: ObjectInst placeholder is Map&lt;String, Object&gt; until RFC-0032 lands.
 *
 * @param ruleCode RuleType.programCode
 * @param context  input context (key=variable name, value=ObjectInst)
 */
@Schema(description = "Single rule synchronous execution request")
public record RuleExecutionRequest(
        @Schema(description = "Rule code (RuleType.programCode)", example = "ORDER_VIP_DISCOUNT",
                requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128)
        String ruleCode,

        @Schema(description = "Input context (key=variable name, value=ObjectInst)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, Object> context
) {
}
