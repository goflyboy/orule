package com.orule.rule.execution.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Kafka payload published when an async rule-set execution completes
 * (RFC-0040 §3.12 / §16.5 / TASK-2.3.1 / TASK-2.3.2).
 *
 * <p>Topic: {@code rule-set-execution-completed} (configured in application.yml).
 *
 * <p>Schema covers the §16.5 Avro-style JSON contract:
 * <pre>
 * {
 *   "eventId":            "evt-7a3b2c1d-9e8f",
 *   "eventType":          "RULE_SET_EXECUTION_COMPLETED",
 *   "occurredAt":         "2026-09-13T17:55:02.485Z",
 *   "taskId":             "rs-exec-20260913-175502-1d8e9f0a",
 *   "ruleSetCode":        "ORDER_PROMOTION_SUITE",
 *   "tenantId":           "tenant-001",
 *   "status":             "PARTIAL_SUCCESS",
 *   "totalCount":         3,
 *   "successCount":       2,
 *   "failedCount":        1,
 *   "durationMs":         380,
 *   "traceId":            "trace-xyz-789",
 *   "compensationHints":  { ... }
 * }
 * </pre>
 */
@Schema(description = "Rule-set execution completion event (Kafka)")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleSetExecutionCompletedEvent(
        @Schema(description = "Globally-unique event ID", example = "evt-7a3b2c1d-9e8f")
        String eventId,

        @Schema(description = "Event type discriminator",
                example = "RULE_SET_EXECUTION_COMPLETED",
                allowableValues = {"RULE_SET_EXECUTION_COMPLETED"})
        String eventType,

        @Schema(description = "When the event was emitted (UTC, ISO 8601)")
        Instant occurredAt,

        @Schema(description = "Task ID")
        String taskId,

        @Schema(description = "Rule-set code")
        String ruleSetCode,

        @Schema(description = "Tenant ID")
        String tenantId,

        @Schema(description = "Final execution status",
                allowableValues = {"SUCCESS", "FAILED", "PARTIAL_SUCCESS"})
        String status,

        @Schema(description = "Total rule count in the set")
        int totalCount,

        @Schema(description = "Number of rules that succeeded")
        int successCount,

        @Schema(description = "Number of rules that failed")
        int failedCount,

        @Schema(description = "Total duration in milliseconds")
        long durationMs,

        @Schema(description = "OpenTelemetry trace ID")
        String traceId,

        @Schema(description = "Compensation hints for partial-success handling")
        CompensationHints compensationHints
) {
    public static final String EVENT_TYPE = "RULE_SET_EXECUTION_COMPLETED";

    /**
     * Compensation hints (§16.5). Populated when {@code status=PARTIAL_SUCCESS};
     * {@code null} otherwise.
     */
    @Schema(description = "Compensation hints for PARTIAL_SUCCESS events")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompensationHints(
            @Schema(description = "Rule codes that failed (PARTIAL_SUCCESS only)")
            List<String> failedRuleCodes,

            @Schema(description = "Context fields that already mutated and may need rollback")
            List<String> compensableFields,

            @Schema(description = "Whether automatic rollback is available")
            boolean rollbackAvailable,

            @Schema(description = "Suggested action for downstream consumers",
                    allowableValues = {"NOTIFY_USER_AND_RETRY", "INVOKE_COMPENSATION", "NONE"})
            String suggestedAction
    ) {
    }

    /**
     * Build the canonical SUCCESS event (no compensation hints needed).
     */
    public static RuleSetExecutionCompletedEvent success(String eventId,
                                                         String taskId,
                                                         String ruleSetCode,
                                                         String tenantId,
                                                         int totalCount,
                                                         long durationMs,
                                                         String traceId,
                                                         Instant occurredAt) {
        return new RuleSetExecutionCompletedEvent(
                eventId, EVENT_TYPE, occurredAt,
                taskId, ruleSetCode, tenantId,
                "SUCCESS",
                totalCount, totalCount, 0,
                durationMs, traceId,
                null);
    }

    /**
     * Build the FAILED event (all rules failed).
     */
    public static RuleSetExecutionCompletedEvent failed(String eventId,
                                                        String taskId,
                                                        String ruleSetCode,
                                                        String tenantId,
                                                        int totalCount,
                                                        long durationMs,
                                                        String traceId,
                                                        Instant occurredAt,
                                                        CompensationHints hints) {
        return new RuleSetExecutionCompletedEvent(
                eventId, EVENT_TYPE, occurredAt,
                taskId, ruleSetCode, tenantId,
                "FAILED",
                totalCount, 0, totalCount,
                durationMs, traceId,
                hints);
    }

    /**
     * Build the PARTIAL_SUCCESS event with compensation hints.
     */
    public static RuleSetExecutionCompletedEvent partialSuccess(String eventId,
                                                                String taskId,
                                                                String ruleSetCode,
                                                                String tenantId,
                                                                int totalCount,
                                                                int successCount,
                                                                int failedCount,
                                                                long durationMs,
                                                                String traceId,
                                                                Instant occurredAt,
                                                                CompensationHints hints) {
        return new RuleSetExecutionCompletedEvent(
                eventId, EVENT_TYPE, occurredAt,
                taskId, ruleSetCode, tenantId,
                "PARTIAL_SUCCESS",
                totalCount, successCount, failedCount,
                durationMs, traceId,
                hints);
    }
}
