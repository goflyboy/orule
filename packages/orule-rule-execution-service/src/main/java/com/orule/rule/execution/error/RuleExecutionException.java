package com.orule.rule.execution.error;

/**
 * Base business exception (RFC-0040 §16.6 error code internal carrier).
 *
 * <p>All business / rule layer thrown exceptions extend this class;
 * {@link com.orule.rule.execution.api.error.GlobalExceptionHandler} will catch
 * and convert to RFC §16.6 standard HTTP response body.
 */
public abstract class RuleExecutionException extends RuntimeException {

    private final String errorCode;

    protected RuleExecutionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected RuleExecutionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** RFC-0040 §16.6 error code (e.g. RULE_NOT_FOUND / EVAL_FAILED). */
    public String getErrorCode() {
        return errorCode;
    }
}
