package com.orule.rule.execution.service;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionMetadata;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.api.dto.RuleExecutionRequest;
import com.orule.rule.execution.api.dto.RuleExecutionResponse;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponse;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleNotFoundException;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.execution.RuleExecutorService;
import com.orule.rule.execution.security.TenantContextHolder;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous single-rule execution orchestrator (RFC-0040 §3.5 / TASK-1.3.1).
 *
 * <p>Flow:
 * <ol>
 *   <li>Resolve rule metadata via {@link RuleManagermentApiClient} (Feign).</li>
 *   <li>Build {@link ExecutionInput} with the published source code + context.</li>
 *   <li>Delegate to {@link RuleExecutorService#execute(String, ExecutionInput)}.</li>
 *   <li>Map {@link ExecutionOutput} back to {@link RuleExecutionResponse}.</li>
 * </ol>
 *
 * <p>The {@link RateLimiter} annotation (TASK-3.2.2) caps calls at the
 * configured per-second rate; rejected requests surface as
 * {@link RuleRuntimeException} with errorCode {@code "RATE_LIMIT_EXCEEDED"} which
 * the {@code GlobalExceptionHandler} maps to HTTP 429.
 */
@Service
public class RuleExecutionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RuleExecutionApplicationService.class);

    private final RuleExecutorService executorService;
    private final RuleManagermentApiClient ruleMgmt;

    public RuleExecutionApplicationService(RuleExecutorService executorService,
                                           RuleManagermentApiClient ruleMgmt) {
        this.executorService = executorService;
        this.ruleMgmt = ruleMgmt;
    }

    @RateLimiter(name = "ruleExecute")
    public RuleExecutionResponse execute(RuleExecutionRequest request) {
        if (request == null || request.ruleCode() == null || request.ruleCode().isBlank()) {
            throw new RuleEvalException("ruleCode is required");
        }

        var tenant = TenantContextHolder.current();
        if (tenant == null) {
            // Defence-in-depth: controller filter should have caught this, but never trust.
            throw new RuleRuntimeException("No tenant context bound to request",
                    "MISSING_TENANT_CONTEXT", null);
        }

        Instant startedAt = Instant.now();

        // 1. Fetch upstream rule metadata.
        RuleMetadataResponse meta;
        try {
            meta = ruleMgmt.getRuleMetadata(request.ruleCode(),
                    tenant.tenantId(), tenant.operatorId(), tenant.traceId());
        } catch (RuntimeException fe) {
            log.warn("Upstream rule-mgmt call failed for ruleCode={}: {}",
                    request.ruleCode(), fe.getMessage());
            throw new RuleNotFoundException("rule " + request.ruleCode() + " not published");
        }
        if (meta == null || meta.sourceCode() == null || meta.sourceCode().isBlank()) {
            throw new RuleNotFoundException("rule " + request.ruleCode() + " has no published source");
        }

        // 2. Build ExecutionInput.
        Map<String, Object> ctx = request.context() != null ? request.context() : new LinkedHashMap<>();
        ExecutionMetadata metadata = new ExecutionMetadata(tenant.traceId(), tenant.operatorId(), tenant.tenantId());
        ExecutionInput input = new ExecutionInput(meta.executorType(), meta.sourceCode(), ctx, metadata);

        // 3. Delegate to the executor service (timeout + SPI routing).
        ExecutionOutput out = executorService.execute(meta.executorType(), input);

        Instant executedAt = Instant.now();
        long durationMs = executedAt.toEpochMilli() - startedAt.toEpochMilli();

        return new RuleExecutionResponse(
                UUID.randomUUID().toString(),
                out.success(),
                out.context(),
                executedAt,
                durationMs,
                out.success() ? null : out.errorCode(),
                out.success() ? null : out.errorMessage()
        );
    }
}
