package com.orule.rule.execution.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Detailed execution log returned by
 * {@code GET /api/v1/executions/{taskId}/logs}
 * (RFC-0040 §3.4 / §16.4 / TASK-2.2.3).
 *
 * <p>Includes both the aggregated execution_log row and the per-rule
 * execution breakdown (serialized as JSON in the {@code perRuleExecution}
 * field for v1 schema simplicity — v2 splits into a child table).
 */
@Schema(description = "Detailed execution log")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionLogDetail(
        @Schema(description = "Task ID")
        String taskId,

        @Schema(description = "Execution type: RULE | RULE_SET")
        String executionType,

        @Schema(description = "Rule code (RULE only)")
        String ruleCode,

        @Schema(description = "Rule-set code (RULE_SET only)")
        String ruleSetCode,

        @Schema(description = "Executor type identifier")
        String executorType,

        @Schema(description = "Status: PENDING / RUNNING / SUCCESS / FAILED / PARTIAL_SUCCESS")
        String status,

        @Schema(description = "Input context (ObjectInst collection)")
        Map<String, Object> inputContext,

        @Schema(description = "Output context (ObjectInst collection, accumulated mutations)")
        Map<String, Object> outputContext,

        @Schema(description = "Total rule count (RULE_SET only)")
        Integer totalCount,

        @Schema(description = "Success count")
        Integer successCount,

        @Schema(description = "Failed count")
        Integer failedCount,

        @Schema(description = "Error code (FAILED/PARTIAL_SUCCESS only)")
        String errorCode,

        @Schema(description = "Error details (FAILED/PARTIAL_SUCCESS only)")
        String errorMessage,

        @Schema(description = "Per-rule execution breakdown (v1 inlined JSON)")
        List<PerRuleExecution> perRuleExecution,

        @Schema(description = "OpenTelemetry trace ID")
        String traceId,

        @Schema(description = "Operator (user) ID")
        String operatorId,

        @Schema(description = "Tenant ID")
        String tenantId,

        @Schema(description = "Started timestamp")
        Instant startedAt,

        @Schema(description = "Finished timestamp")
        Instant finishedAt,

        @Schema(description = "Duration in milliseconds")
        Long durationMs,

        @Schema(description = "Created timestamp")
        Instant createdAt
) {
    /**
     * One row of the per-rule execution breakdown (RFC §16.4).
     *
     * @param ruleCode the rule's programCode
     * @param status SUCCESS / FAILED / SKIPPED
     * @param success whether the rule succeeded
     * @param errorCode non-null when status=FAILED
     * @param errorMessage non-null when status=FAILED
     * @param durationMs measured duration in milliseconds
     * @param skipReason when status=SKIPPED, the reason
     */
    @Schema(description = "Per-rule execution row")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PerRuleExecution(
            @Schema(description = "Rule programCode")
            String ruleCode,
            @Schema(description = "Per-rule status")
            String status,
            @Schema(description = "Whether the rule succeeded")
            boolean success,
            @Schema(description = "Error code on FAILED")
            String errorCode,
            @Schema(description = "Error message on FAILED")
            String errorMessage,
            @Schema(description = "Duration in milliseconds")
            Long durationMs,
            @Schema(description = "Skip reason when status=SKIPPED")
            String skipReason
    ) {
    }
}
