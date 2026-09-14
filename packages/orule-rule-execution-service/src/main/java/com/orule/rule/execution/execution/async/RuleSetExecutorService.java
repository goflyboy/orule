package com.orule.rule.execution.execution.async;

import com.orule.rule.execution.api.dto.RuleSetExecutionRequest;

/**
 * Async rule-set dispatch (RFC-0040 §3.6 / TASK-2.1.1).
 *
 * <p>The interface is intentionally narrow: {@code submit(...)} returns immediately
 * with a {@code taskId}; the actual execution happens on the {@code ruleSetExecutor}
 * pool.  Result polling and Kafka completion events are wired in TASK-2.1.2+
 * together with {@link RuleSetExecutorServiceImpl}.
 *
 * <p>This v0.7 preview provides the interface only; concrete impl is TASK-2.1.2.
 */
public interface RuleSetExecutorService {

    /**
     * Submit a rule-set for asynchronous execution.
     *
     * <p>Per RFC §3.6 Q1: a single rule failure does NOT abort the set unless
     * the upstream caller requested fail-fast (default: partial-success).
     *
     * @return taskId (UUID) usable to poll {@code GET /api/v1/rule-set-executions/{taskId}}.
     */
    String submit(RuleSetExecutionRequest request);
}
