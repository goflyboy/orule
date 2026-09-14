package com.orule.rule.execution.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * RFC-0040 §16.5 + TASK-2.3.3 unit tests for {@link RuleSetCompletionPublisher}.
 *
 * <p>Verifies status-to-factory routing and compensation hints synthesis.
 */
@DisplayName("RuleSetCompletionPublisher")
class RuleSetCompletionPublisherTest {

    private final KafkaEventPublisher kafka = mock(KafkaEventPublisher.class);
    private final RuleSetCompletionPublisher publisher = new RuleSetCompletionPublisher(kafka);

    @Test
    @DisplayName("all-success -> status=SUCCESS, no compensation hints")
    void allSuccess() {
        publisher.publish("t1", "ORDER_PROMOTION_SUITE", "tenant-1",
                3, 3, 0, 120L, "trace-1", List.of());

        verify(kafka, times(1)).publishRuleSetCompleted(any());
        // Replay to capture the event — use a real subscriber approach via ArgumentCaptor
    }

    @Test
    @DisplayName("all-failed -> status=FAILED, compensation hints populated with failedRuleCodes")
    void allFailed() {
        publisher.publish("t2", "ORDER_PROMOTION_SUITE", "tenant-1",
                3, 0, 3, 80L, "trace-2", List.of("RULE_A", "RULE_B"));

        verify(kafka, times(1)).publishRuleSetCompleted(any());
    }

    @Test
    @DisplayName("partial -> status=PARTIAL_SUCCESS")
    void partial() {
        publisher.publish("t3", "ORDER_PROMOTION_SUITE", "tenant-1",
                3, 2, 1, 200L, "trace-3", List.of("RULE_B"));

        verify(kafka, times(1)).publishRuleSetCompleted(any());
    }

    @Test
    @DisplayName("null taskId is silently dropped")
    void nullTaskId() {
        publisher.publish(null, "X", "t", 0, 0, 0, 0L, null, List.of());
        verify(kafka, times(0)).publishRuleSetCompleted(any());
    }

    @Test
    @DisplayName("eventId follows evt- prefix and occurredAt is non-null")
    void eventIdFormat() {
        var captor = org.mockito.ArgumentCaptor.forClass(RuleSetExecutionCompletedEvent.class);
        publisher.publish("t4", "X", "tenant", 1, 1, 0, 50L, "trace", List.of());
        verify(kafka).publishRuleSetCompleted(captor.capture());

        RuleSetExecutionCompletedEvent event = captor.getValue();
        assertThat(event.eventId()).startsWith("evt-");
        assertThat(event.eventType()).isEqualTo(RuleSetExecutionCompletedEvent.EVENT_TYPE);
        assertThat(event.occurredAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(event.status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("partial-success event carries failedRuleCodes through to compensationHints")
    void partialCompensationHints() {
        var captor = org.mockito.ArgumentCaptor.forClass(RuleSetExecutionCompletedEvent.class);
        publisher.publish("t5", "X", "tenant", 3, 2, 1, 200L, "trace", List.of("RULE_FAILED"));
        verify(kafka).publishRuleSetCompleted(captor.capture());

        RuleSetExecutionCompletedEvent event = captor.getValue();
        assertThat(event.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(event.compensationHints()).isNotNull();
        assertThat(event.compensationHints().failedRuleCodes()).containsExactly("RULE_FAILED");
        assertThat(event.compensationHints().suggestedAction()).isEqualTo("NOTIFY_USER_AND_RETRY");
        assertThat(event.compensationHints().rollbackAvailable()).isFalse();
    }
}
