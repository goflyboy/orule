package com.orule.rule.execution.error;

/**
 * Rule runtime exception (RFC-0040 §16.6 RUNTIME_ERROR).
 *
 * <p>Returns HTTP 200. Differs from {@link RuleEvalException} (compile/syntax):
 * <ul>
 *   <li>{@link RuleEvalException} — static failure; rule code does not pass AST check</li>
 *   <li>{@link RuleRuntimeException} — dynamic failure; compiles but NPE/OOM/business assertion fails
 *       (or sand box-policy violations caught at runtime).</li>
 * </ul>
 *
 * <p>Supports caller-specified errorCode (e.g. TIMEOUT, EXECUTOR_BUSY) for variants that
 * map to HTTP 503 (see GlobalExceptionHandler).
 */
public class RuleRuntimeException extends RuleExecutionException {
    public RuleRuntimeException(String message) {
        super("RUNTIME_ERROR", message);
    }

    public RuleRuntimeException(String message, Throwable cause) {
        super("RUNTIME_ERROR", message, cause);
    }

    public RuleRuntimeException(String message, String errorCode, Throwable cause) {
        super(errorCode, message, cause);
    }
}
