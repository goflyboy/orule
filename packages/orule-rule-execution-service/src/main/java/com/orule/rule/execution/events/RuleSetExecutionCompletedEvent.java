package com.orule.rule.execution.events;

import com.orule.rule.execution.api.dto.RuleExecutionResponse;

import java.time.Instant;

/**
 * Kafka payload published when an async rule-set execution completes
 * (RFC-0040 §3.12 / TASK-2.3.1).
 *
 * <p>Topic: {@code rule-set-execution-completed} (configured in application.yml).
 *
 * <p>The Kafka producer is wired in {@link KafkaEventPublisher}.
 */
public record RuleSetExecutionCompletedEvent(
        String taskId,
        String ruleSetCode,
        String tenantId,
        boolean success,
        int successCount,
        int failedCount,
        int totalCount,
        long durationMs,
        Instant completedAt,
        RuleExecutionResponse snapshot
) {
}
