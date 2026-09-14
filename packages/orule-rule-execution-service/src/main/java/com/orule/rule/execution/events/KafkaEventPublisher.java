package com.orule.rule.execution.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Low-level Kafka publisher for rule-set execution completion events
 * (RFC-0040 §3.12 / TASK-2.3.1).
 *
 * <p>Topic: {@code rule-set-execution-completed} (configured in application.yml).
 *
 * <p>This class is intentionally a thin wrapper around {@link KafkaTemplate} that
 * (a) serializes via Jackson, (b) swallows non-fatal errors so the calling
 * worker thread is not aborted by broker hiccups. Higher-level policy —
 * eventId minting, status mapping, compensation hints — lives in
 * {@link RuleSetCompletionPublisher}.
 *
 * <p>v0.7 NOTE: this is a thin wrapper around {@link KafkaTemplate} that
 * blocks the calling thread on send so any upstream caller can detect
 * back-pressure. Production should switch to the reactive variant; that work
 * belongs to TASK-3.5.1.
 */
@Component
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               @Value("${orule.execution.events.rule-set-completed-topic:rule-set-execution-completed}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        // Defensive: Spring Boot's auto-configured ObjectMapper already has
        // JavaTimeModule (it ships with the web starter), but unit tests pass a
        // bare `new ObjectMapper()`. Register the module unconditionally and
        // idempotently — registering twice is a no-op.
        try {
            Class<?> jtm = Class.forName("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
            if (!objectMapper.getRegisteredModuleIds().contains(jtm.getName())) {
                objectMapper.registerModule((com.fasterxml.jackson.databind.Module) jtm.getDeclaredConstructor().newInstance());
            }
        } catch (ReflectiveOperationException e) {
            // JavaTimeModule is part of the Jackson distribution; absence is
            // a deployment error rather than a runtime condition.
            throw new IllegalStateException(
                    "JavaTimeModule not available on classpath; add jackson-datatype-jsr310", e);
        }
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /**
     * Publish a completion event. The {@code taskId} is used as the message key
     * so the partition remains stable across retries.
     */
    public void publishRuleSetCompleted(RuleSetExecutionCompletedEvent event) {
        if (event == null || event.taskId() == null) {
            log.warn("Dropping event with null taskId");
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, event.taskId(), payload);
            log.info("Published rule-set-completed event: eventId={}, taskId={}, ruleSetCode={}, status={}",
                    event.eventId(), event.taskId(), event.ruleSetCode(), event.status());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize event: taskId={}, error={}", event.taskId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Kafka send failed: taskId={}, error={}", event.taskId(), e.getMessage());
        }
    }
}
