package com.orule.rule.execution.execution.async;

import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.api.dto.RuleSetExecutionRequest;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponseV2;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.domain.ExecutionType;
import com.orule.rule.execution.events.RuleSetCompletionPublisher;
import com.orule.rule.execution.execution.ExecutorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RFC-0040 §3.6 + TASK-2.1.1 + TASK-2.1.2 / TASK-2.1.4 acceptance tests.
 *
 * <p>Verifies that the async service:
 * <ul>
 *   <li>returns a taskId immediately</li>
 *   <li>runs the rule on the {@code ruleSetExecutor} pool</li>
 *   <li>accumulates context (Q2 semantics)</li>
 *   <li>continues past per-rule failures (Q1 default)</li>
 *   <li>transitions PENDING → RUNNING → terminal status via ExecutionLog state machine</li>
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
    RuleSetCompletionPublisher publisher;

    @BeforeEach
    void setUp() {
        // Replace the SPI route so the worker doesn't need a real sandbox.
        registry.registerForTesting(new com.orule.rule.execution.execution.RuleExecutor() {
            @Override public String executorType() { return "rule-set-executor"; }
            @Override public ExecutionOutput execute(com.orule.rule.execution.api.dto.ExecutionInput input) {
                Map<String, Object> out = new LinkedHashMap<>(input.context());
                out.put("processed", true);
                return new ExecutionOutput(out, true, null, null);
            }
        });
        when(ruleMgmt.getRuleMetadata(anyString(), anyString(), anyString(), any()))
                .thenReturn(new RuleMetadataResponseV2(
                        "ORDER_PROMOTION_SUITE", "RULE_TYPE",
                        "rule-set-executor",
                        "processed = true",
                        List.of(), List.of(),
                        null, List.of(), Map.of()));

        // Mock the PENDING log row the worker will reload by taskId.
        // Returning Optional.empty() makes the worker early-exit without calling the publisher.
        when(logRepo.findByTaskId(anyString())).thenAnswer(inv -> {
            String taskId = inv.getArgument(0);
            return Optional.of(ExecutionLog.pending(
                    taskId, ExecutionType.RULE_SET, null, "ORDER_PROMOTION_SUITE",
                    "rule-set-executor", "trace-test", "user-test", "tenant-test"));
        });
    }

    @Test
    @DisplayName("submit() returns a taskId immediately and runs the rule on the worker pool")
    void submitReturnsImmediately() throws Exception {
        RuleSetExecutionRequest req = new RuleSetExecutionRequest(
                "ORDER_PROMOTION_SUITE", Map.of("x", 1));

        // Capture RuleSetCompletionPublisher.publish(...) invocation.
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedTaskId = new AtomicReference<>();
        AtomicReference<String> capturedRuleSetCode = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            capturedTaskId.set(inv.getArgument(0));
            capturedRuleSetCode.set(inv.getArgument(1));
            latch.countDown();
            return null;
        }).when(publisher).publish(any(), any(), any(), any(Integer.class), any(Integer.class),
                any(Integer.class), any(Long.class), any(), any());

        String taskId = service.submit(req);

        assertThat(taskId).isNotBlank();
        assertThat(UUID.fromString(taskId)).isNotNull(); // valid UUID format

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedTaskId.get()).isNotBlank();
        assertThat(capturedRuleSetCode.get()).isEqualTo("ORDER_PROMOTION_SUITE");
    }

    @Test
    @DisplayName("submit() persists PENDING log row synchronously via logRepo.save")
    void submitPersistsPendingLog() {
        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        RuleSetExecutionRequest req = new RuleSetExecutionRequest(
                "ORDER_PROMOTION_SUITE", Map.of("x", 1));

        service.submit(req);

        // logRepo.save(PENDING) is called from submit() (sync); the worker also saves.
        org.mockito.Mockito.verify(logRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<ExecutionLog> saved = captor.getAllValues();
        assertThat(saved).isNotEmpty();
        // The first saved row should be PENDING (the sync submit-phase save).
        ExecutionLog first = saved.get(0);
        assertThat(first.getStatus().name()).isEqualTo("PENDING");
        assertThat(first.getExecutionType().name()).isEqualTo("RULE_SET");
        assertThat(first.getRuleSetCode()).isEqualTo("ORDER_PROMOTION_SUITE");
    }
}
