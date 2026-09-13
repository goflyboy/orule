package com.orule.rule.execution.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * RuleSet async submit response (RFC-0040 §3.4). POST /api/v1/rule-set-executions response body.
 *
 * @param taskId task UUID
 * @param status always "PENDING" right after submit
 */
@Schema(description = "RuleSet async submit response")
public record RuleSetExecutionSubmitResponse(
        @Schema(description = "Task ID (UUID)", example = "f1e2d3c4-b5a6-7890-1234-567890abcdef")
        String taskId,

        @Schema(description = "Initial status", example = "PENDING", allowableValues = {"PENDING"})
        String status
) {
}
