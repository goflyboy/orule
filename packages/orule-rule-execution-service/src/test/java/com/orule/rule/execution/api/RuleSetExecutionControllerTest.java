package com.orule.rule.execution.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.api.dto.RuleSetExecutionRequest;
import com.orule.rule.execution.api.error.ExecutionLogDetail;
import com.orule.rule.execution.api.error.GlobalExceptionHandler;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.domain.ExecutionLogStatus;
import com.orule.rule.execution.domain.ExecutionType;
import com.orule.rule.execution.error.TaskNotFoundException;
import com.orule.rule.execution.execution.async.RuleSetExecutorService;
import com.orule.rule.execution.service.ExecutionLogApplicationService;
import com.orule.rule.execution.service.RuleSetQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RFC-0040 §3.5 / §16.2 / §16.3 / §16.4 + TASK-2.2.1 ~ 2.2.3 acceptance tests.
 *
 * <p>Exercises the three rule-set endpoints end-to-end through MockMvc:
 * <ul>
 *   <li>POST /api/v1/rule-set-executions — submit → 200, {taskId, status:"PENDING"}</li>
 *   <li>GET /api/v1/rule-set-executions/{taskId} — query result → 200, status field</li>
 *   <li>GET /api/v1/executions/{taskId}/logs — fetch detailed log → 200, full ExecutionLogDetail</li>
 *   <li>GET on missing taskId → 404 TASK_NOT_FOUND</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RuleSet HTTP endpoints")
class RuleSetExecutionControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @MockBean
    RuleSetExecutorService ruleSetExecutor;

    @MockBean
    ExecutionLogRepository logRepo;

    @Autowired
    RuleSetQueryService ruleSetQueryService;

    @Autowired
    ExecutionLogApplicationService executionLogService;

    @Test
    @DisplayName("POST /api/v1/rule-set-executions → 200 + {taskId, status:PENDING}")
    void submitRuleSet() throws Exception {
        when(ruleSetExecutor.submit(any())).thenReturn("task-mock-001");

        RuleSetExecutionRequest req = new RuleSetExecutionRequest("ORDER_PROMOTION_SUITE", Map.of("order", 1));
        mvc.perform(post("/api/v1/rule-set-executions")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Operator-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-mock-001"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(ruleSetExecutor).submit(any());
    }

    @Test
    @DisplayName("GET /api/v1/rule-set-executions/{taskId} → 200 with status when row exists")
    void queryRuleSetResult_success() throws Exception {
        ExecutionLog row = ExecutionLog.pending(
                "task-q-1", ExecutionType.RULE_SET, null, "ORDER_PROMOTION_SUITE",
                "rule-set-executor", "trace-1", "user-1", "tenant-1");
        row.markRunning();
        row.markSuccess("{\"order\":{\"discount\":200}}");
        when(logRepo.findByTaskId("task-q-1")).thenReturn(Optional.of(row));

        mvc.perform(get("/api/v1/rule-set-executions/task-q-1")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Operator-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-q-1"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.outputContext.order.discount").value(200));
    }

    @Test
    @DisplayName("GET /api/v1/rule-set-executions/{taskId} → 404 TASK_NOT_FOUND when missing")
    void queryRuleSetResult_notFound() throws Exception {
        when(logRepo.findByTaskId("missing")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/rule-set-executions/missing")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Operator-Id", "user-1"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TASK_NOT_FOUND")));
    }

    @Test
    @DisplayName("GET /api/v1/executions/{taskId}/logs → 200 with full ExecutionLogDetail")
    void queryExecutionLogs_success() throws Exception {
        ExecutionLog row = ExecutionLog.pending(
                "task-l-1", ExecutionType.RULE_SET, null, "ORDER_PROMOTION_SUITE",
                "rule-set-executor", "trace-1", "user-1", "tenant-1");
        row.markRunning();
        row.markPartialSuccess(3, 2, 1, "{\"order\":{\"discount\":200}}");
        when(logRepo.findByTaskId("task-l-1")).thenReturn(Optional.of(row));

        mvc.perform(get("/api/v1/executions/task-l-1/logs")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Operator-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-l-1"))
                .andExpect(jsonPath("$.executionType").value("RULE_SET"))
                .andExpect(jsonPath("$.status").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.perRuleExecution").isArray())
                .andExpect(jsonPath("$.traceId").value("trace-1"));
    }

    @Test
    @DisplayName("GET /api/v1/executions/{taskId}/logs → 404 TASK_NOT_FOUND when missing")
    void queryExecutionLogs_notFound() throws Exception {
        when(logRepo.findByTaskId("missing")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/executions/missing/logs")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Operator-Id", "user-1"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TASK_NOT_FOUND")));
    }

    @Test
    @DisplayName("missing tenant header on POST → 400 MISSING_TENANT_HEADER")
    void missingTenantHeaderOnSubmit() throws Exception {
        RuleSetExecutionRequest req = new RuleSetExecutionRequest("X", Map.of());
        mvc.perform(post("/api/v1/rule-set-executions")
                        .header("X-Operator-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MISSING_TENANT_HEADER")));
    }
}
