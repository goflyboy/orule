package com.orule.rule.execution.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * RuleSet execution result (GET async query, RFC-0040 §3.4).
 *
 * <p>GET /api/v1/rule-set-executions/{taskId} response body.
 * Covers all 5 §16.6 status values; totalCount/successCount/failedCount
 * are only meaningful when status=PARTIAL_SUCCESS (see §16.4 note).
 */
@Schema(description = "RuleSet execution result")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleSetExecutionResultResponse(
        @Schema(description = "Task ID")
        String taskId,

        @Schema(description = "Execution status",
                allowableValues = {"PENDING", "RUNNING", "SUCCESS", "FAILED", "PARTIAL_SUCCESS"})
        String status,

        @Schema(description = "Started timestamp")
        Instant startedAt,

        @Schema(description = "Finished timestamp (null when PENDING/RUNNING)")
        Instant finishedAt,

        @Schema(description = "Duration in milliseconds (null when PENDING/RUNNING)")
        Long durationMs,

        @Schema(description = "Total rule count (RULE_SET only)")
        Integer totalCount,

        @Schema(description = "Success count")
        Integer successCount,

        @Schema(description = "Failed count")
        Integer failedCount,

        @Schema(description = "Output ObjectInst collection (accumulated mutations)")
        Map<String, Object> outputContext,

        @Schema(description = "Error details (non-null when FAILED/PARTIAL_SUCCESS)")
        String errorMessage
) {
}
