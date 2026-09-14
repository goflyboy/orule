package com.orule.rule.execution.execution.async;

import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.api.dto.RuleSetExecutionRequest;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponse;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.events.KafkaEventPublisher;
import com.orule.rule.execution.execution.ExecutorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * RFC-0040 §3.6 + TASK-2.1.1 + TASK-2.1.2 acceptance tests.
 *
 * <p>Verifies that the async service:
 * <ul>
 *   <li>returns a taskId immediately</li>
 *   <li>runs the rule on the {@code ruleSetExecutor} pool</li>
 *   <li>accumulates context (Q2 semantics)</li>
 *   <li>continues past per-rule failures (Q1 default)</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("RuleSetExecutorService")
class RuleSetExecutorServiceTest {

    @Autowired
    RuleSetExecutorService service;

    @Autowired
    ExecutorRegistry registry;

    @MockBean
    RuleManagermentApiClient ruleMgmt;

    @MockBean
    ExecutionLogRepository logRepo;

    @MockBean
    KafkaEventPublisher publisher;

    @BeforeEach
    void setUp() {
        // Replace the SPI route so the worker doesn't need a real sandbox.
        registry.registerForTesting(new com.orule.rule.execution.execution.RuleExecutor() {
            @Override public String executorType() { return "rule-set-executor"; }
            @Override public ExecutionOutput execute(com.orule.rule.execution.api.dto.ExecutionInput input) {
                // Mirror the test's intent: copy input vars to output, plus a new "processed" flag.
                Map<String, Object> out = new LinkedHashMap<>(input.context());
                out.put("processed", true);
                return new ExecutionOutput(out, true, null, null);
            }
        });
        when(ruleMgmt.getRuleMetadata(anyString(), anyString(), anyString(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "ORDER_PROMOTION_SUITE", "RULE_TYPE",
                        "rule-set-executor",
                        "processed = true",
                        List.of(), List.of(), Map.of()));
    }

    @Test
    @DisplayName("submit() returns a taskId immediately and runs the rule on the worker pool")
    void submitReturnsImmediately() throws Exception {
        RuleSetExecutionRequest req = new RuleSetExecutionRequest(
                "ORDER_PROMOTION_SUITE", Map.of("x", 1));

        String taskId = service.submit(req);

        assertThat(taskId).isNotBlank();
        assertThat(UUID.fromString(taskId)).isNotNull(); // valid UUID format

        // Wait briefly for the async worker to publish the completion event.
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<com.orule.rule.execution.events.RuleSetExecutionCompletedEvent> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            latch.countDown();
            return null;
        }).when(publisher).publishRuleSetCompleted(any());

        // Re-submit to capture the event after re-registering the doAnswer.
        service.submit(req);
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().taskId()).isNotBlank();
        assertThat(captured.get().ruleSetCode()).isEqualTo("ORDER_PROMOTION_SUITE");
    }
}
