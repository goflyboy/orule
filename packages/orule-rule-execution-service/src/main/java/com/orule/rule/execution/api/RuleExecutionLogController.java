package com.orule.rule.execution.api;

import com.orule.rule.execution.api.error.ExecutionLogDetail;
import com.orule.rule.execution.service.ExecutionLogApplicationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synchronous single-rule execution log query controller
 * (RFC-0041 §3.1 / §5.1).
 *
 * <p>GET /api/v1/rule-executions/{taskId}/logs — returns the
 * {@link ExecutionLogDetail} for a synchronous task. Mirrors the
 * async-path {@code GET /api/v1/executions/{taskId}/logs} (RFC-0040 §16.4).
 *
 * <p>Headers (enforced by
 * {@link com.orule.rule.execution.security.TenantInterceptor}): X-Tenant-Id,
 * X-Operator-Id, X-Trace-Id (optional).
 *
 * <p>Errors:
 * <ul>
 *   <li>404 {@code TASK_NOT_FOUND} — no execution_log row matches the taskId</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/rule-executions")
public class RuleExecutionLogController {

    private final ExecutionLogApplicationService executionLogService;

    public RuleExecutionLogController(ExecutionLogApplicationService executionLogService) {
        this.executionLogService = executionLogService;
    }

    @GetMapping(path = "/{taskId}/logs",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExecutionLogDetail queryExecutionLog(@PathVariable("taskId") String taskId) {
        return executionLogService.findByTaskId(taskId);
    }
}
