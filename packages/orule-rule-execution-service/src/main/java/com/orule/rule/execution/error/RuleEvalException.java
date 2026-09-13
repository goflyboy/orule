package com.orule.rule.execution.error;

/**
 * Rule code compile/run exception (RFC-0040 §16.6 EVAL_FAILED).
 *
 * <p>Returns HTTP 200 with success=false + errorCode=EVAL_FAILED in the response body.
 * This represents a "code-level problem", not a downstream system failure; do not
 * trigger alerts/escalation.
 */
public class RuleEvalException extends RuleExecutionException {
    public RuleEvalException(String message) {
        super("EVAL_FAILED", message);
    }

    public RuleEvalException(String message, Throwable cause) {
        super("EVAL_FAILED", message, cause);
    }
}
