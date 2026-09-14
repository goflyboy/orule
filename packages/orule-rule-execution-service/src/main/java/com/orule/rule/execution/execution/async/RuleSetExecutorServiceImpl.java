package com.orule.rule.execution.execution.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionMetadata;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.api.dto.RuleSetExecutionRequest;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponse;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.domain.ExecutionType;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleNotFoundException;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.events.RuleSetCompletionPublisher;
import com.orule.rule.execution.execution.RuleExecutorService;
import com.orule.rule.execution.security.TenantContext;
import com.orule.rule.execution.security.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sequential rule-set executor with full task lifecycle (RFC-0040 §3.6 / §3.12,
 * TASK-2.1.1 / TASK-2.1.2 / TASK-2.1.4).
 *
 * <p>Lifecycle for each submitted rule-set:
 * <ol>
 *   <li>{@link #submit(RuleSetExecutionRequest)} (sync, @Transactional):
 *       mints a {@code taskId}, creates an {@link ExecutionLog} row with
 *       {@code PENDING} status, saves it, then fires {@link #runAsync}.</li>
 *   <li>{@link #runAsync} (worker thread, @Async("ruleSetExecutor")):
 *       re-loads the log, calls {@link ExecutionLog#markRunning()}, iterates
 *       rules, then transitions to
 *       {@code SUCCESS} / {@code PARTIAL_SUCCESS} / {@code FAILED}.</li>
 *   <li>Worker finally publishes a {@link RuleSetExecutionCompletedEvent}
 *       via {@link KafkaEventPublisher}.</li>
 * </ol>
 *
 * <p>Q1 semantics (default): on per-rule failure, continue executing remaining
 * rules; final status is {@code PARTIAL_SUCCESS} when mixed, {@code FAILED} when
 * all rules fail, {@code SUCCESS} when none fail.
 *
 * <p>v0.7 NOTE: rules are listed as a hard-coded placeholder list keyed by
 * {@code ruleSetCode} (one rule per set). Real artifact lookup belongs to
 * TASK-2.2.1 once RFC-0021 lands.
 */
@Service
public class RuleSetExecutorServiceImpl implements RuleSetExecutorService {

    private static final Logger log = LoggerFactory.getLogger(RuleSetExecutorServiceImpl.class);

    private final RuleExecutorService ruleExecutorService;
    private final RuleManagermentApiClient ruleMgmt;
    private final ExecutionLogRepository logRepo;
    private final RuleSetCompletionPublisher publisher;
    private final ObjectMapper objectMapper;

    public RuleSetExecutorServiceImpl(RuleExecutorService ruleExecutorService,
                                      RuleManagermentApiClient ruleMgmt,
                                      ExecutionLogRepository logRepo,
                                      RuleSetCompletionPublisher publisher,
                                      ObjectMapper objectMapper) {
        this.ruleExecutorService = ruleExecutorService;
        this.ruleMgmt = ruleMgmt;
        this.logRepo = logRepo;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Synchronous entry point. Mint a taskId, persist a PENDING log row in
     * its own transaction, then dispatch to the async worker.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String submit(RuleSetExecutionRequest request) {
        String taskId = UUID.randomUUID().toString();
        TenantContext tenant = TenantContextHolder.current();

        ExecutionLog pending = ExecutionLog.pending(
                taskId,
                ExecutionType.RULE_SET,
                /* ruleCode */ null,
                request.ruleSetCode(),
                "rule-set-executor",
                tenantOrEmpty(tenant, "traceId"),
                tenantOrEmpty(tenant, "operatorId"),
                tenantOrEmpty(tenant, "tenantId"));
        try {
            pending.setInputContext(serializeContext(request.context()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize input context for task {}: {}", taskId, e.getMessage());
        }
        logRepo.save(pending);

        runAsync(taskId, request, tenant);
        return taskId;
    }

    /**
     * Worker thread entry point. Runs on the {@code ruleSetExecutor} pool.
     */
    @Async("ruleSetExecutor")
    public void runAsync(String taskId, RuleSetExecutionRequest request, TenantContext tenant) {
        if (tenant != null) {
            TenantContextHolder.set(tenant);
        }
        try {
            doRun(taskId, request, tenant);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * Worker body: load PENDING log → mark RUNNING → execute rules →
     * mark terminal state → save → publish Kafka event.
     */
    private void doRun(String taskId, RuleSetExecutionRequest request, TenantContext tenant) {
        ExecutionLog logEntry;
        try {
            logEntry = logRepo.findByTaskId(taskId).orElseThrow(
                    () -> new IllegalStateException("execution_log missing for taskId=" + taskId));
        } catch (RuntimeException e) {
            log.warn("Cannot load execution_log for task {}: {}", taskId, e.getMessage());
            return;
        }

        try {
            logEntry.markRunning();
            logRepo.save(logEntry);
        } catch (IllegalStateException e) {
            log.warn("Cannot transition to RUNNING for task {}: {}", taskId, e.getMessage());
            return;
        }

        Instant startedAt = logEntry.getStartedAt();
        Map<String, Object> accumulatedContext = new LinkedHashMap<>();
        if (request.context() != null) {
            accumulatedContext.putAll(request.context());
        }

        int successCount = 0;
        int failedCount = 0;
        int totalCount = 0;

        // v0.7 placeholder: real ruleSet metadata lookup is TASK-2.2.1.
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
                    // Q1 default: continue. fail-fast flag is a future Sprint task.
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
        long durationMs = (startedAt != null)
                ? completedAt.toEpochMilli() - startedAt.toEpochMilli()
                : 0L;

        String outputJson;
        try {
            outputJson = objectMapper.writeValueAsString(accumulatedContext);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize output context for task {}: {}", taskId, e.getMessage());
            outputJson = "{}";
        }

        try {
            if (failedCount == 0) {
                logEntry.markSuccess(outputJson);
            } else if (successCount == 0) {
                logEntry.markFailed("RULE_SET_FAILED", failedCount + " rules failed");
            } else {
                logEntry.markPartialSuccess(totalCount, successCount, failedCount, outputJson);
            }
            logRepo.save(logEntry);
        } catch (RuntimeException e) {
            log.warn("Failed to persist terminal execution_log for task {}: {}", taskId, e.getMessage());
        }

        publisher.publish(
                taskId,
                request.ruleSetCode(),
                tenantOrEmpty(tenant, "tenantId"),
                totalCount,
                successCount,
                failedCount,
                durationMs,
                tenantOrEmpty(tenant, "traceId"),
                List.of(request.ruleSetCode())  // v0.7 placeholder: failed rule codes = ruleSetCode
        );
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

    private String serializeContext(Map<String, Object> ctx) throws JsonProcessingException {
        if (ctx == null) return "{}";
        return objectMapper.writeValueAsString(ctx);
    }
}
