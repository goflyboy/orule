package com.orule.rule.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orule.rule.execution.api.error.ExecutionLogDetail;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.domain.ExecutionType;
import com.orule.rule.execution.error.TaskNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RFC-0040 §3.6 / §16.4 + TASK-2.2.3 unit tests.
 */
@DisplayName("ExecutionLogApplicationService")
class ExecutionLogApplicationServiceTest {

    private final ExecutionLogRepository logRepo = mock(ExecutionLogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ExecutionLogApplicationService service = new ExecutionLogApplicationService(logRepo, objectMapper);

    @Test
    @DisplayName("findByTaskId: full detail projection with inputContext deserialized")
    void fullDetail() {
        ExecutionLog row = ExecutionLog.pending(
                "t1", ExecutionType.RULE_SET, null, "RS",
                "rule-set-executor", "trace-1", "user-1", "tenant-1");
        row.setInputContext("{\"order\":{\"total\":1000}}");
        row.markRunning();
        row.markPartialSuccess(3, 2, 1, "{\"order\":{\"discount\":200}}");
        when(logRepo.findByTaskId("t1")).thenReturn(Optional.of(row));

        ExecutionLogDetail d = service.findByTaskId("t1");
        assertThat(d.taskId()).isEqualTo("t1");
        assertThat(d.executionType()).isEqualTo("RULE_SET");
        assertThat(d.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(d.inputContext()).containsKey("order");
        assertThat(d.outputContext()).containsKey("order");
        assertThat(d.totalCount()).isEqualTo(3);
        assertThat(d.successCount()).isEqualTo(2);
        assertThat(d.failedCount()).isEqualTo(1);
        assertThat(d.traceId()).isEqualTo("trace-1");
        assertThat(d.perRuleExecution()).isEmpty(); // v0.7 placeholder
    }

    @Test
    @DisplayName("findByTaskId: RULE row → executionType=RULE")
    void singleRule() {
        ExecutionLog row = ExecutionLog.pending(
                "t2", ExecutionType.RULE, "ORDER_VIP_DISCOUNT", null,
                "java-source", null, "user-1", "tenant-1");
        row.markRunning();
        row.markSuccess("{\"x\":1}");
        when(logRepo.findByTaskId("t2")).thenReturn(Optional.of(row));

        ExecutionLogDetail d = service.findByTaskId("t2");
        assertThat(d.executionType()).isEqualTo("RULE");
        assertThat(d.ruleCode()).isEqualTo("ORDER_VIP_DISCOUNT");
        assertThat(d.ruleSetCode()).isNull();
    }

    @Test
    @DisplayName("findByTaskId: missing → TaskNotFoundException")
    void missing() {
        when(logRepo.findByTaskId("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findByTaskId("missing"))
                .isInstanceOf(TaskNotFoundException.class);
    }
}
