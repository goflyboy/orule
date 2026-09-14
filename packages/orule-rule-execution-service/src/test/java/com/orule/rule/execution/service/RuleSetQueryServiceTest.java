package com.orule.rule.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orule.rule.execution.api.dto.RuleSetExecutionResultResponse;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.domain.ExecutionLogStatus;
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
 * RFC-0040 §3.6 / §16.3 + TASK-2.2.2 unit tests.
 */
@DisplayName("RuleSetQueryService")
class RuleSetQueryServiceTest {

    private final ExecutionLogRepository logRepo = mock(ExecutionLogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final RuleSetQueryService service = new RuleSetQueryService(logRepo, objectMapper);

    @Test
    @DisplayName("queryResult: pending row → status=PENDING")
    void pending() {
        ExecutionLog row = ExecutionLog.pending(
                "t1", ExecutionType.RULE_SET, null, "RS",
                "rule-set-executor", "trace", "op", "tenant");
        when(logRepo.findByTaskId("t1")).thenReturn(Optional.of(row));

        RuleSetExecutionResultResponse r = service.queryResult("t1");
        assertThat(r.taskId()).isEqualTo("t1");
        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.finishedAt()).isNull();
    }

    @Test
    @DisplayName("queryResult: success row → status=SUCCESS + deserialized outputContext")
    void success() {
        ExecutionLog row = ExecutionLog.pending(
                "t2", ExecutionType.RULE_SET, null, "RS",
                "rule-set-executor", "trace", "op", "tenant");
        row.markRunning();
        row.markSuccess("{\"order\":{\"discount\":200}}");
        when(logRepo.findByTaskId("t2")).thenReturn(Optional.of(row));

        RuleSetExecutionResultResponse r = service.queryResult("t2");
        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(r.outputContext()).containsKey("order");
        assertThat(r.finishedAt()).isNotNull();
        assertThat(r.durationMs()).isNotNull();
    }

    @Test
    @DisplayName("queryResult: partial → status=PARTIAL_SUCCESS with success/failed counts")
    void partial() {
        ExecutionLog row = ExecutionLog.pending(
                "t3", ExecutionType.RULE_SET, null, "RS",
                "rule-set-executor", "trace", "op", "tenant");
        row.markRunning();
        row.markPartialSuccess(3, 2, 1, "{}");
        when(logRepo.findByTaskId("t3")).thenReturn(Optional.of(row));

        RuleSetExecutionResultResponse r = service.queryResult("t3");
        assertThat(r.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(r.totalCount()).isEqualTo(3);
        assertThat(r.successCount()).isEqualTo(2);
        assertThat(r.failedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("queryResult: missing taskId → TaskNotFoundException")
    void notFound() {
        when(logRepo.findByTaskId("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.queryResult("missing"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    @DisplayName("queryResult: corrupt JSON in output_context → empty map (graceful)")
    void corruptJson() {
        ExecutionLog row = ExecutionLog.pending(
                "t4", ExecutionType.RULE_SET, null, "RS",
                "rule-set-executor", "trace", "op", "tenant");
        row.markRunning();
        row.markSuccess("not-json-{");
        when(logRepo.findByTaskId("t4")).thenReturn(Optional.of(row));

        RuleSetExecutionResultResponse r = service.queryResult("t4");
        assertThat(r.outputContext()).isEmpty();
        assertThat(r.status()).isEqualTo(ExecutionLogStatus.SUCCESS.name());
    }
}
