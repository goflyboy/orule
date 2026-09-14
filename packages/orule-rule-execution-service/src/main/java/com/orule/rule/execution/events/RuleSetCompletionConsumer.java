package com.orule.rule.execution.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sample downstream consumer for {@link RuleSetExecutionCompletedEvent}
 * (RFC-0040 §16.5 / TASK-2.3.4).
 *
 * <p>This is a v0.7 reference consumer that business teams can clone and adapt.
 * It listens on {@code rule-set-execution-completed} and triggers compensation
 * whenever {@code status=PARTIAL_SUCCESS}, capturing the
 * {@code failedRuleCodes} list for the compensator to act on.
 *
 * <p>Receives raw JSON strings rather than deserialized records to keep this
 * reference decoupled from the upstream serializer; the JSON parsing here uses
 * Jackson directly. Production consumers should configure a JsonDeserializer
 * with the upstream schema in their own configuration.
 *
 * <p>The captured list is exposed via {@link #getCapturedFailedRuleCodes()}
 * so tests can assert on what was actually delivered.
 */
@Component
public class RuleSetCompletionConsumer {

    private static final Logger log = LoggerFactory.getLogger(RuleSetCompletionConsumer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final List<List<String>> capturedFailedRuleCodes = Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(topics = "${orule.execution.events.rule-set-completed-topic:rule-set-execution-completed}",
            groupId = "rule-set-completion-consumer-test")
    public void onCompleted(String payload) {
        // Stdout for surefire visibility (so test failures show root cause).
        System.out.println("[RuleSetCompletionConsumer] received payload: "
                + (payload == null ? "null" : payload.substring(0, Math.min(payload.length(), 200))));
        if (payload == null) {
            log.warn("Received null payload on rule-set-completed; dropping");
            return;
        }
        RuleSetExecutionCompletedEvent event;
        try {
            event = MAPPER.readValue(payload, RuleSetExecutionCompletedEvent.class);
        } catch (Exception e) {
            System.out.println("[RuleSetCompletionConsumer] deserialize FAILED: " + e.getMessage());
            log.warn("Failed to deserialize event payload: {}", e.getMessage());
            return;
        }
        System.out.println("[RuleSetCompletionConsumer] event ok: taskId=" + event.taskId()
                + " status=" + event.status()
                + " hints=" + (event.compensationHints() != null
                        ? event.compensationHints().failedRuleCodes() : "null"));
        log.info("Consumer received: eventId={}, taskId={}, status={}",
                event.eventId(), event.taskId(), event.status());
        if ("PARTIAL_SUCCESS".equals(event.status())) {
            List<String> failed = event.compensationHints() != null
                    ? event.compensationHints().failedRuleCodes()
                    : List.of();
            log.info("PARTIAL_SUCCESS detected; triggering compensation for failedRuleCodes={}", failed);
            capturedFailedRuleCodes.add(failed);
        }
    }

    /** Test-only access to the captured compensation triggers. */
    public List<List<String>> getCapturedFailedRuleCodes() {
        return capturedFailedRuleCodes;
    }

    /** Test-only reset. */
    public void reset() {
        capturedFailedRuleCodes.clear();
    }
}
