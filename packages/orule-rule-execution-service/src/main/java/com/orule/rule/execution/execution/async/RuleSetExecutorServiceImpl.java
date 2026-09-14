package com.orule.rule.execution.execution.async;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionMetadata;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.api.dto.RuleSetExecutionRequest;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponse;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleNotFoundException;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.events.KafkaEventPublisher;
import com.orule.rule.execution.events.RuleSetExecutionCompletedEvent;
import com.orule.rule.execution.execution.RuleExecutorService;
import com.orule.rule.execution.security.TenantContext;
import com.orule.rule.execution.security.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sequential rule-set executor (RFC-0040 §3.6 / TASK-2.1.2).
 *
 * <p>For each rule in the set, the executor:
 * <ol>
 *   <li>Resolves metadata via {@link RuleManagermentApiClient}.</li>
 *   <li>Calls {@link RuleExecutorService#execute(String, ExecutionInput)} sequentially.</li>
 *   <li>Accumulates output context (Q2 semantics: partial success preserves mutations).</li>
 *   <li>On rule failure, applies Q1 decision (default: continue; configurable fail-fast).</li>
 * </ol>
 *
 * <p>v0.7 NOTE: this implementation does NOT yet consult a
 * {@code RuleSetArtifact} repository — rules are listed as a hard-coded
 * placeholder list keyed by {@code ruleSetCode} (one rule per set).  Real
 * artifact lookup belongs to TASK-2.2.1 once RFC-0021 lands.
 */
@Service
public class RuleSetExecutorServiceImpl implements RuleSetExecutorService {

    private static final Logger log = LoggerFactory.getLogger(RuleSetExecutorServiceImpl.class);

    private final RuleExecutorService ruleExecutorService;
    private final RuleManagermentApiClient ruleMgmt;
    private final ExecutionLogRepository logRepo;
    private final KafkaEventPublisher publisher;

    public RuleSetExecutorServiceImpl(RuleExecutorService ruleExecutorService,
                                      RuleManagermentApiClient ruleMgmt,
                                      ExecutionLogRepository logRepo,
                                      KafkaEventPublisher publisher) {
        this.ruleExecutorService = ruleExecutorService;
        this.ruleMgmt = ruleMgmt;
        this.logRepo = logRepo;
        this.publisher = publisher;
    }

    @Override
    public String submit(RuleSetExecutionRequest request) {
        String taskId = UUID.randomUUID().toString();
        // Capture the tenant context for use on the worker thread.
        TenantContext tenant = TenantContextHolder.current();
        runAsync(taskId, request, tenant);
        return taskId;
    }

    /**
     * Worker thread entry point. Runs on the {@code ruleSetExecutor} pool.
     */
    @Async("ruleSetExecutor")
    public void runAsync(String taskId, RuleSetExecutionRequest request, TenantContext tenant) {
        // Re-establish tenant context on the worker thread (Spring's MVC filter
        // cleared it after the request completed).
        if (tenant != null) {
            TenantContextHolder.set(tenant);
        }
        Instant startedAt = Instant.now();
        try {
            doRun(taskId, request, tenant, startedAt);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void doRun(String taskId, RuleSetExecutionRequest request, TenantContext tenant, Instant startedAt) {
        Map<String, Object> accumulatedContext = new LinkedHashMap<>();
        if (request.context() != null) {
            accumulatedContext.putAll(request.context());
        }

        int successCount = 0;
        int failedCount = 0;
        int totalCount = 0;

        // v0.7 placeholder: real ruleSet metadata lookup is TASK-2.2.1.
        // Here we treat the request as a single-rule set to keep the wiring end-to-end.
        List<String> rulesInSet = List.of(request.ruleSetCode());

        for (String ruleCode : rulesInSet) {
            totalCount++;
            try {
                RuleMetadataResponse meta = ruleMgmt.getRuleMetadata(
                        ruleCode,
                        tenantOrEmpty(tenant, "tenantId"),
                        tenantOrEmpty(tenant, "operatorId"),
                        tenantOrEmpty(tenant, "traceId"));
                if (meta == null || meta.sourceCode() == null) {
                    failedCount++;
                    continue;
                }
                ExecutionMetadata md = new ExecutionMetadata(
                        tenantOrEmpty(tenant, "traceId"),
                        tenantOrEmpty(tenant, "operatorId"),
                        tenantOrEmpty(tenant, "tenantId"));
                ExecutionInput input = new ExecutionInput(
                        meta.executorType(), meta.sourceCode(), accumulatedContext, md);
                ExecutionOutput out = ruleExecutorService.execute(meta.executorType(), input);
                if (out.success()) {
                    successCount++;
                    if (out.context() != null) {
                        accumulatedContext.putAll(out.context());
                    }
                } else {
                    failedCount++;
                    // Q1 default: continue. Future TASK-2.1.3 will respect a fail-fast flag.
                }
            } catch (RuleEvalException | RuleRuntimeException | RuleNotFoundException e) {
                log.warn("Rule {} failed in set {}: {}", ruleCode, request.ruleSetCode(), e.getMessage());
                failedCount++;
            } catch (RuntimeException e) {
                log.warn("Unexpected failure for rule {}: {}", ruleCode, e.getMessage());
                failedCount++;
            }
        }

        Instant completedAt = Instant.now();
        long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();

        // 1. Persist execution_log row.
        try {
            ExecutionLog logEntry = ExecutionLog.pending(
                    taskId,
                    com.orule.rule.execution.domain.ExecutionType.RULE_SET,
                    /* ruleCode */ null,
                    request.ruleSetCode(),
                    "rule-set-executor",
                    tenantOrEmpty(tenant, "traceId"),
                    tenantOrEmpty(tenant, "operatorId"),
                    tenantOrEmpty(tenant, "tenantId"));
            if (failedCount == 0) {
                logEntry.markSuccess("{}");
            } else if (successCount == 0) {
                logEntry.markFailed("RULE_SET_FAILED", failedCount + " rules failed");
            } else {
                logEntry.markPartialSuccess(totalCount, successCount, failedCount, "{}");
            }
            logRepo.save(logEntry);
        } catch (RuntimeException e) {
            log.warn("Failed to persist execution_log for task {}: {}", taskId, e.getMessage());
        }

        // 2. Publish Kafka event.
        publisher.publishRuleSetCompleted(new RuleSetExecutionCompletedEvent(
                taskId, request.ruleSetCode(), tenantOrEmpty(tenant, "tenantId"),
                failedCount == 0, successCount, failedCount, totalCount, durationMs, completedAt,
                null));
    }

    private static String tenantOrEmpty(TenantContext ctx, String field) {
        if (ctx == null) return null;
        return switch (field) {
            case "tenantId" -> ctx.tenantId();
            case "operatorId" -> ctx.operatorId();
            case "traceId" -> ctx.traceId();
            default -> null;
        };
    }
}
