package com.orule.rule.execution.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * Single rule execution response (RFC-0040 §3.4). POST /api/v1/rule-executions response body.
 *
 * <p>success=false still returns HTTP 200; errorCode conveys the real failure reason.
 * HTTP 5xx is reserved for this service's own failures (see §16.6 error code table).
 */
@Schema(description = "Single rule synchronous execution response")
public record RuleExecutionResponse(
        @Schema(description = "Task ID (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef0123456789")
        String taskId,

        @Schema(description = "Business success flag", example = "true")
        boolean success,

        @Schema(description = "Output ObjectInst collection (accumulated mutations)")
        Map<String, Object> outputContext,

        @Schema(description = "Execution completion timestamp (UTC ISO-8601)")
        Instant executedAt,

        @Schema(description = "Duration in milliseconds", example = "42")
        long durationMs,

        @Schema(description = "Error code (null on success)",
                allowableValues = {"EVAL_FAILED", "RUNTIME_ERROR", "RULE_NOT_FOUND"})
        String errorCode,

        @Schema(description = "Error details (null on success)")
        String errorMessage
) {
}
