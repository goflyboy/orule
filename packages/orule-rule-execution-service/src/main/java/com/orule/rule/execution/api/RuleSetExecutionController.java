package com.orule.rule.execution.api;

import com.orule.rule.execution.api.dto.RuleSetExecutionRequest;
import com.orule.rule.execution.api.dto.RuleSetExecutionResultResponse;
import com.orule.rule.execution.api.dto.RuleSetExecutionSubmitResponse;
import com.orule.rule.execution.api.error.ExecutionLogDetail;
import com.orule.rule.execution.execution.async.RuleSetExecutorService;
import com.orule.rule.execution.service.ExecutionLogApplicationService;
import com.orule.rule.execution.service.RuleSetQueryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asynchronous rule-set execution controllers (RFC-0040 §3.5 / TASK-2.2.1 ~ 2.2.3).
 *
 * <p>Three endpoints:
 * <ul>
 *   <li>POST /api/v1/rule-set-executions — submit a rule-set, return taskId (TASK-2.2.1)</li>
 *   <li>GET  /api/v1/rule-set-executions/{taskId} — poll for aggregated result (TASK-2.2.2)</li>
 *   <li>GET  /api/v1/executions/{taskId}/logs — fetch detailed execution log (TASK-2.2.3)</li>
 * </ul>
 *
 * <p>Headers (enforced by
 * {@link com.orule.rule.execution.security.TenantInterceptor}): X-Tenant-Id,
 * X-Operator-Id, X-Trace-Id (optional).
 */
@RestController
@RequestMapping("/api/v1")
public class RuleSetExecutionController {

    private final RuleSetExecutorService ruleSetExecutor;
    private final RuleSetQueryService ruleSetQueryService;
    private final ExecutionLogApplicationService executionLogService;

    public RuleSetExecutionController(RuleSetExecutorService ruleSetExecutor,
                                      RuleSetQueryService ruleSetQueryService,
                                      ExecutionLogApplicationService executionLogService) {
        this.ruleSetExecutor = ruleSetExecutor;
        this.ruleSetQueryService = ruleSetQueryService;
        this.executionLogService = executionLogService;
    }

    // ===================================================================
    // TASK-2.2.1 — POST /api/v1/rule-set-executions
    // ===================================================================
    @PostMapping(path = "/rule-set-executions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RuleSetExecutionSubmitResponse> submitRuleSet(
            @Valid @RequestBody RuleSetExecutionRequest request) {
        String taskId = ruleSetExecutor.submit(request);
        return ResponseEntity.ok(new RuleSetExecutionSubmitResponse(taskId, "PENDING"));
    }

    // ===================================================================
    // TASK-2.2.2 — GET /api/v1/rule-set-executions/{taskId}
    // ===================================================================
    @GetMapping(path = "/rule-set-executions/{taskId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RuleSetExecutionResultResponse queryRuleSetResult(@PathVariable("taskId") String taskId) {
        return ruleSetQueryService.queryResult(taskId);
    }

    // ===================================================================
    // TASK-2.2.3 — GET /api/v1/executions/{taskId}/logs
    // ===================================================================
    @GetMapping(path = "/executions/{taskId}/logs",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExecutionLogDetail queryExecutionLog(@PathVariable("taskId") String taskId) {
        return executionLogService.findByTaskId(taskId);
    }
}
