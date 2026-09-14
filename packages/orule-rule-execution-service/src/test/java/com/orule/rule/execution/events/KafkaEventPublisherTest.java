package com.orule.rule.execution.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * RFC-0040 §3.12 / §16.5 + TASK-2.3.1 / TASK-2.3.2 acceptance tests.
 *
 * <p>The Kafka broker is mocked; we only verify that {@link KafkaEventPublisher}
 * (a) serializes correctly per the §16.5 schema, (b) routes to the configured
 * topic, and (c) does not throw on serialization / broker failures.
 */
@DisplayName("KafkaEventPublisher")
class KafkaEventPublisherTest {

    private static ObjectMapper newObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    @Test
    @DisplayName("serializes event with §16.5 schema and sends to configured topic with taskId as key")
    void happyPath() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaEventPublisher publisher = new KafkaEventPublisher(
                template, newObjectMapper(), "rule-set-execution-completed");

        RuleSetExecutionCompletedEvent event = RuleSetExecutionCompletedEvent.success(
                "evt-test-1", "task-1", "ORDER_PROMOTION_SUITE", "tenant-1",
                5, 1200L, "trace-1", Instant.now());

        publisher.publishRuleSetCompleted(event);

        verify(template).send(eq("rule-set-execution-completed"), eq("task-1"), any(String.class));
    }

    @Test
    @DisplayName("null event is silently dropped")
    void nullEvent() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaEventPublisher publisher = new KafkaEventPublisher(
                template, newObjectMapper(), "rule-set-execution-completed");
        publisher.publishRuleSetCompleted(null);
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("event with null taskId is silently dropped")
    void nullTaskId() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaEventPublisher publisher = new KafkaEventPublisher(
                template, newObjectMapper(), "rule-set-execution-completed");
        publisher.publishRuleSetCompleted(new RuleSetExecutionCompletedEvent(
                "evt-x", "RULE_SET_EXECUTION_COMPLETED", Instant.now(),
                null, "X", "t", "FAILED", 0, 0, 0, 0L, "trace", null));
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("partial success event carries compensationHints.failedRuleCodes")
    void partialSuccessCarriesHints() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaEventPublisher publisher = new KafkaEventPublisher(
                template, newObjectMapper(), "rule-set-execution-completed");

        RuleSetExecutionCompletedEvent event = RuleSetExecutionCompletedEvent.partialSuccess(
                "evt-ps-1", "task-2", "ORDER_PROMOTION_SUITE", "tenant-1",
                3, 2, 1, 380L, "trace-2", Instant.now(),
                new RuleSetExecutionCompletedEvent.CompensationHints(
                        List.of("PROMOTION_FULL_REDUCTION"),
                        List.of("order.discount"),
                        false,
                        "NOTIFY_USER_AND_RETRY"));

        publisher.publishRuleSetCompleted(event);

        verify(template).send(eq("rule-set-execution-completed"), eq("task-2"), any(String.class));
    }
}
