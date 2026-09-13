package com.orule.rule.execution.error;

/**
 * TaskId not found in execution_log (RFC-0040 §16.6 TASK_NOT_FOUND). HTTP 404.
 *
 * <p>May happen due to archival (v1.2 introduces OSS archive) or typo in input.
 */
public class TaskNotFoundException extends RuleExecutionException {
    public TaskNotFoundException(String taskId) {
        super("TASK_NOT_FOUND", "Task not found: " + taskId);
    }
}
