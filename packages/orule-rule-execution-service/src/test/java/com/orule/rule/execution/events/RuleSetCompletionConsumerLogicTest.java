package com.orule.rule.execution.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0040 §16.5 + TASK-2.3.4 reference-consumer logic tests.
 *
 * <p>Exercises {@link RuleSetCompletionConsumer#onCompleted(String)} directly
 * with hand-crafted JSON payloads, without spinning up an EmbeddedKafka
 * broker. The full producer-to-broker-to-consumer flow is covered by the
 * end-to-end {@code RuleSetCompletionConsumerTest} (currently {@code @Disabled}
 * pending TASK-3.4.1 CI hardening — see class javadoc for details).
 *
 * <p>This split lets the consumer's logic — payload deserialization,
 * status dispatch, and compensation-hint capture — be asserted in isolation,
 * which is what TASK-2.3.4 actually requires.
 */
@DisplayName("RuleSetCompletionConsumer logic")
class RuleSetCompletionConsumerLogicTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("PARTIAL_SUCCESS payload → compensation triggered with failedRuleCodes")
    void partialSuccessTriggersCompensation() throws Exception {
        RuleSetCompletionConsumer consumer = new RuleSetCompletionConsumer();

        RuleSetExecutionCompletedEvent event = RuleSetExecutionCompletedEvent.partialSuccess(
                "evt-1", "task-1", "ORDER_PROMOTION_SUITE", "tenant-1",
                3, 2, 1, 380L, "trace-1", Instant.now(),
                new RuleSetExecutionCompletedEvent.CompensationHints(
                        List.of("PROMOTION_FULL_REDUCTION"),
                        List.of("order.discount"),
                        false,
                        "NOTIFY_USER_AND_RETRY"));

        consumer.onCompleted(MAPPER.writeValueAsString(event));

        assertThat(consumer.getCapturedFailedRuleCodes()).hasSize(1);
        assertThat(consumer.getCapturedFailedRuleCodes().get(0))
                .containsExactly("PROMOTION_FULL_REDUCTION");
    }

    @Test
    @DisplayName("SUCCESS payload → no compensation triggered")
    void successDoesNotTriggerCompensation() throws Exception {
        RuleSetCompletionConsumer consumer = new RuleSetCompletionConsumer();

        RuleSetExecutionCompletedEvent event = RuleSetExecutionCompletedEvent.success(
                "evt-2", "task-2", "ORDER_PROMOTION_SUITE", "tenant-1",
                3, 100L, "trace-2", Instant.now());

        consumer.onCompleted(MAPPER.writeValueAsString(event));

        assertThat(consumer.getCapturedFailedRuleCodes()).isEmpty();
    }

    @Test
    @DisplayName("FAILED payload → no compensation (only PARTIAL_SUCCESS triggers it)")
    void failedDoesNotTriggerCompensation() throws Exception {
        RuleSetCompletionConsumer consumer = new RuleSetCompletionConsumer();

        RuleSetExecutionCompletedEvent event = RuleSetExecutionCompletedEvent.failed(
                "evt-3", "task-3", "ORDER_PROMOTION_SUITE", "tenant-1",
                3, 100L, "trace-3", Instant.now(),
                new RuleSetExecutionCompletedEvent.CompensationHints(
                        List.of("RULE_X"),
                        List.of(),
                        false,
                        "NOTIFY_USER_AND_RETRY"));

        consumer.onCompleted(MAPPER.writeValueAsString(event));

        assertThat(consumer.getCapturedFailedRuleCodes()).isEmpty();
    }

    @Test
    @DisplayName("null payload → silently dropped, no exception")
    void nullPayloadIsTolerated() {
        RuleSetCompletionConsumer consumer = new RuleSetCompletionConsumer();
        consumer.onCompleted(null);
        assertThat(consumer.getCapturedFailedRuleCodes()).isEmpty();
    }

    @Test
    @DisplayName("malformed JSON → silently dropped (no NPE, no compensation)")
    void malformedPayloadIsTolerated() {
        RuleSetCompletionConsumer consumer = new RuleSetCompletionConsumer();
        consumer.onCompleted("this is not JSON");
        assertThat(consumer.getCapturedFailedRuleCodes()).isEmpty();
    }

    @Test
    @DisplayName("reset() clears captured list (test-only helper)")
    void resetClears() throws Exception {
        RuleSetCompletionConsumer consumer = new RuleSetCompletionConsumer();
        RuleSetExecutionCompletedEvent event = RuleSetExecutionCompletedEvent.partialSuccess(
                "evt-4", "task-4", "X", "tenant",
                1, 0, 1, 10L, "trace", Instant.now(),
                new RuleSetExecutionCompletedEvent.CompensationHints(
                        List.of("R"), List.of(), false, "NOTIFY_USER_AND_RETRY"));
        consumer.onCompleted(MAPPER.writeValueAsString(event));
        assertThat(consumer.getCapturedFailedRuleCodes()).isNotEmpty();

        consumer.reset();
        assertThat(consumer.getCapturedFailedRuleCodes()).isEmpty();
    }
}
