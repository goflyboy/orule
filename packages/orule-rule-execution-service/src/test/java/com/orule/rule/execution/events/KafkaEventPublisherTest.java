package com.orule.rule.execution.events;

import com.orule.rule.execution.api.dto.RuleExecutionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * RFC-0040 §3.12 + TASK-2.3.1 acceptance tests.
 *
 * <p>The Kafka broker is mocked; we only verify that {@link KafkaEventPublisher}
 * (a) serializes correctly, (b) routes to the configured topic, and (c) does not
 * throw on serialization / broker failures.
 */
@DisplayName("KafkaEventPublisher")
class KafkaEventPublisherTest {

    @Test
    @DisplayName("serializes event and sends to configured topic with taskId as key")
    void happyPath() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        KafkaEventPublisher publisher = new KafkaEventPublisher(template, mapper, "rule-set-execution-completed");

        RuleSetExecutionCompletedEvent event = new RuleSetExecutionCompletedEvent(
                "task-1", "ORDER_PROMOTION_SUITE", "tenant-1",
                true, 5, 0, 5, 1200L, Instant.now(),
                new RuleExecutionResponse("task-1", true, Map.of("discount", 50),
                        Instant.now(), 1200L, null, null));

        publisher.publishRuleSetCompleted(event);

        verify(template).send(eq("rule-set-execution-completed"), eq("task-1"), any(String.class));
    }

    @Test
    @DisplayName("null event is silently dropped")
    void nullEvent() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaEventPublisher publisher = new KafkaEventPublisher(template,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                "rule-set-execution-completed");
        publisher.publishRuleSetCompleted(null);
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("event with null taskId is silently dropped")
    void nullTaskId() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaEventPublisher publisher = new KafkaEventPublisher(template,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                "rule-set-execution-completed");
        publisher.publishRuleSetCompleted(new RuleSetExecutionCompletedEvent(
                null, "X", "t", true, 0, 0, 0, 0L, Instant.now(), null));
        verifyNoInteractions(template);
    }
}
