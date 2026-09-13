package com.orule.rule.execution.error;

/**
 * Rate limit triggered (RFC-0040 §16.6 RATE_LIMIT_EXCEEDED). HTTP 429.
 *
 * <p>Source: Resilience4j RateLimiter / CircuitBreaker.
 * Carries {@code retryAfterMs} so GlobalExceptionHandler can write Retry-After header.
 */
public class RateLimitExceededException extends RuleExecutionException {

    private final long retryAfterMs;

    public RateLimitExceededException(String message, long retryAfterMs) {
        super("RATE_LIMIT_EXCEEDED", message);
        this.retryAfterMs = retryAfterMs;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
