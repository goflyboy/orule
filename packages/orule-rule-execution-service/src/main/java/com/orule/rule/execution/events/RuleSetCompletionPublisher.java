package com.orule.rule.execution.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * High-level publisher that wraps {@link KafkaEventPublisher} and produces
 * RFC-0040 §16.5 schema-conformant {@link RuleSetExecutionCompletedEvent}s
 * (TASK-2.3.3).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Mint {@code eventId} / {@code eventType} / {@code occurredAt} envelope fields.</li>
 *   <li>Translate terminal status into the appropriate factory
 *       ({@code success} / {@code failed} / {@code partialSuccess}) so the wire
 *       payload matches §16.5 byte-for-byte.</li>
 *   <li>Synthesize {@link RuleSetExecutionCompletedEvent.CompensationHints} for
 *       {@code PARTIAL_SUCCESS} by listing the failed rule codes.</li>
 * </ul>
 *
 * <p>The lower-level Kafka wire format (Jackson serialization, partition key,
 * error handling) remains the responsibility of {@link KafkaEventPublisher}.
 */
@Component
public class RuleSetCompletionPublisher {

    private static final Logger log = LoggerFactory.getLogger(RuleSetCompletionPublisher.class);

    private final KafkaEventPublisher kafka;

    public RuleSetCompletionPublisher(KafkaEventPublisher kafka) {
        this.kafka = kafka;
    }

    /**
     * Publish a completion event for the given rule-set execution summary.
     *
     * @param taskId       the rule-set task ID
     * @param ruleSetCode  the rule-set code
     * @param tenantId     tenant scope
     * @param totalCount   number of rules attempted
     * @param successCount number that succeeded
     * @param failedCount  number that failed
     * @param durationMs   total measured duration
     * @param traceId      OTel trace ID (may be null)
     * @param failedRuleCodes rule codes that failed; used to build compensation hints
     */
    public void publish(String taskId,
                       String ruleSetCode,
                       String tenantId,
                       int totalCount,
                       int successCount,
                       int failedCount,
                       long durationMs,
                       String traceId,
                       List<String> failedRuleCodes) {
        if (taskId == null) {
            log.warn("Dropping completion event with null taskId");
            return;
        }
        String eventId = "evt-" + UUID.randomUUID().toString().substring(0, 12);
        Instant occurredAt = Instant.now();

        RuleSetExecutionCompletedEvent event;
        if (failedCount == 0) {
            event = RuleSetExecutionCompletedEvent.success(
                    eventId, taskId, ruleSetCode, tenantId,
                    totalCount, durationMs, traceId, occurredAt);
        } else if (successCount == 0) {
            event = RuleSetExecutionCompletedEvent.failed(
                    eventId, taskId, ruleSetCode, tenantId,
                    totalCount, durationMs, traceId, occurredAt,
                    buildHints(failedRuleCodes));
        } else {
            event = RuleSetExecutionCompletedEvent.partialSuccess(
                    eventId, taskId, ruleSetCode, tenantId,
                    totalCount, successCount, failedCount,
                    durationMs, traceId, occurredAt,
                    buildHints(failedRuleCodes));
        }
        kafka.publishRuleSetCompleted(event);
    }

    /**
     * Build compensation hints (§16.5).
     *
     * <p>v0.7 simplification: rollbackAvailable=false (no automatic rollback
     * implemented), suggestedAction=NOTIFY_USER_AND_RETRY. compensableFields
     * is empty because the per-rule output context is not yet projected.
     */
    private static RuleSetExecutionCompletedEvent.CompensationHints buildHints(
            List<String> failedRuleCodes) {
        return new RuleSetExecutionCompletedEvent.CompensationHints(
                failedRuleCodes == null ? List.of() : List.copyOf(failedRuleCodes),
                List.of(),
                false,
                "NOTIFY_USER_AND_RETRY"
        );
    }
}
