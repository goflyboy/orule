package com.orule.rule.execution.api;

import com.orule.rule.execution.api.dto.RuleExecutionRequest;
import com.orule.rule.execution.api.dto.RuleExecutionResponse;
import com.orule.rule.execution.service.RuleExecutionApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synchronous single-rule execution controller (RFC-0040 §3.5 / TASK-1.3.1).
 *
 * <p>POST /api/v1/rule-executions
 * <p>Headers (enforced by {@link com.orule.rule.execution.security.TenantInterceptor}):
 * X-Tenant-Id, X-Operator-Id, X-Trace-Id (optional).
 *
 * <p>Always returns HTTP 200 on the success path; business failures (EVAL_FAILED /
 * RUNTIME_ERROR) are conveyed in the response body's {@code success=false +
 * errorCode} fields.
 */
@RestController
@RequestMapping("/api/v1/rule-executions")
public class RuleExecutionController {

    private final RuleExecutionApplicationService service;

    public RuleExecutionController(RuleExecutionApplicationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public RuleExecutionResponse execute(@Valid @RequestBody RuleExecutionRequest request) {
        return service.execute(request);
    }
}
