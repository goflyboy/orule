package com.orule.rule.execution.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.common.dto.ObjectTypeDto;
import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionMetadata;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.api.dto.RuleExecutionRequest;
import com.orule.rule.execution.api.dto.RuleExecutionResponse;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponseV2;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.domain.ExecutionType;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleNotFoundException;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.execution.RuleExecutorService;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectTypeMapper;
import com.orule.rule.execution.security.TenantContext;
import com.orule.rule.execution.security.TenantContextHolder;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronous single-rule execution orchestrator
 * (RFC-0040 §3.5 / TASK-1.3.1 + RFC-0041 §3.2 + RFC-0045 §4.3).
 *
 * <p>Flow:
 * <ol>
 *   <li>Resolve rule metadata via {@link RuleManagermentApiClient#getRuleMetadata} (v2).</li>
 *   <li>For each {@code objectTypeCodes} entry, fetch the ObjectType+attributes via
 *       {@link RuleManagermentApiClient#getObjectTypeByProgramCode}. Project each
 *       result to a {@link ResolvedObjectType} via
 *       {@link ResolvedObjectTypeMapper#project}.</li>
 *   <li>Persist a PENDING {@link ExecutionLog} row (RFC-0041 §3.2).</li>
 *   <li>Build {@link ExecutionInput} with the resolved metadata + context.</li>
 *   <li>Delegate to {@link RuleExecutorService#execute(String, ExecutionInput)}.</li>
 *   <li>Transition the log row to SUCCESS or FAILED.</li>
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
    private final ExecutionLogRepository logRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate pendingTxTemplate;

    public RuleExecutionApplicationService(RuleExecutorService executorService,
                                           RuleManagermentApiClient ruleMgmt,
                                           ExecutionLogRepository logRepo,
                                           ObjectMapper objectMapper,
                                           PlatformTransactionManager txManager) {
        this.executorService = executorService;
        this.ruleMgmt = ruleMgmt;
        this.logRepo = logRepo;
        this.objectMapper = objectMapper;
        // REQUIRES_NEW so the PENDING row is committed even if the outer
        // transactional context later rolls back. Mirrors RuleSetExecutorServiceImpl.
        this.pendingTxTemplate = new TransactionTemplate(txManager);
        this.pendingTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

        String taskId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        // 1. Fetch upstream rule metadata (v2).
        RuleMetadataResponseV2 meta;
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

        // 2. Fetch ObjectType metadata (RFC-0045 §4.3 secondary Feign).
        List<ResolvedObjectType> resolved = fetchResolvedObjectTypes(meta, tenant);

        // 3. Persist PENDING ExecutionLog row (RFC-0041 §3.2) in a brand-new transaction.
        ExecutionLog pending = ExecutionLog.pending(
                taskId,
                ExecutionType.RULE,
                request.ruleCode(),
                /* ruleSetCode */ null,
                meta.executorType(),
                tenant.traceId(),
                tenant.operatorId(),
                tenant.tenantId());
        try {
            pending.setInputContext(serializeContext(request.context()));
            saveInNewTx(pending);
        } catch (RuntimeException e) {
            // Log persistence must never block business execution (RFC-0041 §6).
            log.warn("Failed to persist PENDING execution_log for task {}: {}",
                    taskId, e.getMessage());
        }

        // 4. Mark RUNNING before delegating.
        try {
            pending.markRunning();
            logRepo.save(pending);
        } catch (RuntimeException e) {
            log.warn("Failed to mark RUNNING execution_log for task {}: {}", taskId, e.getMessage());
        }

        // 5. Build ExecutionInput (with resolvedObjectTypes).
        Map<String, Object> ctx = request.context() != null ? request.context() : new LinkedHashMap<>();
        ExecutionMetadata metadata = new ExecutionMetadata(tenant.traceId(), tenant.operatorId(), tenant.tenantId());
        ExecutionInput input = new ExecutionInput(meta.executorType(), meta.sourceCode(), ctx, metadata, resolved);

        // 6. Delegate to the executor service (timeout + SPI routing).
        try {
            ExecutionOutput out = executorService.execute(meta.executorType(), input);

            // 7a. SUCCESS terminal state.
            try {
                pending.markSuccess(serializeContext(out.context()));
                logRepo.save(pending);
            } catch (RuntimeException e) {
                log.warn("Failed to persist SUCCESS execution_log for task {}: {}", taskId, e.getMessage());
            }

            Instant executedAt = Instant.now();
            long durationMs = executedAt.toEpochMilli() - startedAt.toEpochMilli();

            return new RuleExecutionResponse(
                    taskId,
                    out.success(),
                    out.context(),
                    executedAt,
                    durationMs,
                    out.success() ? null : out.errorCode(),
                    out.success() ? null : out.errorMessage()
            );
        } catch (RuleEvalException | RuleRuntimeException | RuleNotFoundException ex) {
            // 7b. FAILED terminal state — preserve original exception for the handler.
            try {
                pending.markFailed(ex.getErrorCode() != null ? ex.getErrorCode() : "RUNTIME_ERROR",
                        ex.getMessage());
                logRepo.save(pending);
            } catch (RuntimeException e) {
                log.warn("Failed to persist FAILED execution_log for task {}: {}", taskId, e.getMessage());
            }
            throw ex;
        } catch (RuntimeException ex) {
            // 7c. Any other unexpected exception — mark FAILED and rethrow.
            try {
                pending.markFailed("RUNTIME_ERROR", ex.getMessage());
                logRepo.save(pending);
            } catch (RuntimeException e) {
                log.warn("Failed to persist FAILED execution_log for task {}: {}", taskId, e.getMessage());
            }
            throw ex;
        }
    }

    /**
     * Fetch each ObjectType referenced by the rule metadata and project to
     * {@link ResolvedObjectType}. Missing or failed fetches are logged and
     * skipped — the executor falls back to the verbatim source (RFC-0043
     * compatibility path) when the list is empty.
     */
    List<ResolvedObjectType> fetchResolvedObjectTypes(RuleMetadataResponseV2 meta,
                                                      TenantContext tenant) {
        List<String> codes = meta.objectTypeCodes();
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        if (meta.domainCode() == null || meta.domainCode().isBlank()) {
            log.warn("Rule metadata has objectTypeCodes but missing domainCode; skip prefix generation (ruleCode={})",
                    meta.ruleCode());
            return List.of();
        }
        // De-duplicate while preserving order (mirrors upstream contract).
        Set<String> uniq = new LinkedHashSet<>(codes);
        List<ResolvedObjectType> resolved = new ArrayList<>(uniq.size());
        for (String code : uniq) {
            try {
                ObjectTypeDto dto = ruleMgmt.getObjectTypeByProgramCode(
                        meta.domainCode(), code,
                        tenant.tenantId(), tenant.operatorId(), tenant.traceId());
                if (dto == null) {
                    log.warn("ObjectType lookup returned null for domainCode={} programCode={}",
                            meta.domainCode(), code);
                    continue;
                }
                resolved.add(ResolvedObjectTypeMapper.project(dto, /* slotName= */ code));
            } catch (RuntimeException fe) {
                log.warn("ObjectType lookup failed for domainCode={} programCode={}: {}",
                        meta.domainCode(), code, fe.getMessage());
                // RFC-0045 §8: degrade gracefully; do not block execution.
            }
        }
        return resolved;
    }

    private void saveInNewTx(ExecutionLog row) {
        pendingTxTemplate.executeWithoutResult(status -> logRepo.save(row));
    }

    private String serializeContext(Map<String, Object> ctx) {
        if (ctx == null || ctx.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize context: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(Collections.emptyMap());
            } catch (JsonProcessingException fatal) {
                return "{}";
            }
        }
    }
}
