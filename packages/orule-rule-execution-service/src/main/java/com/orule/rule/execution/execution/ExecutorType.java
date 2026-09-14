package com.orule.rule.execution.execution;

/**
 * Executor type enumeration (RFC-0040 §3.7 SPI route key).
 *
 * <p>Each concrete executor is identified by a unique {@link #name()} value.
 * The {@link ExecutorRegistry} resolves {@link RuleExecutor} / {@link RuleSetExecutor}
 * by this type.
 *
 * <p>Known executor types:
 * <ul>
 *   <li>{@link #JAVA_SOURCE} — Groovy-based Java source sandbox (default, RFC-0020)</li>
 *   <li>{@link #PYTHON}      — GraalVM Python sandbox (extension example, RFC-0040 §3.7)</li>
 * </ul>
 *
 * @see RuleExecutor
 * @see RuleSetExecutor
 * @see ExecutorRegistry
 */
public enum ExecutorType {

    /** Default Groovy sandbox executor. */
    JAVA_SOURCE("java-source"),

    /** GraalVM Python sandbox (extension sample, not yet implemented). */
    PYTHON("python"),

    /** Unknown / unregistered executor. */
    UNKNOWN("unknown");

    private final String typeId;

    ExecutorType(String typeId) {
        this.typeId = typeId;
    }

    /** Canonical string identifier used in SPI lookup and execution_log.executor_type. */
    public String typeId() {
        return typeId;
    }

    /**
     * Parse from string, returning {@link #UNKNOWN} for unrecognized values
     * (never throws, safe for database rows written by future executor types).
     */
    public static ExecutorType from(String typeId) {
        for (ExecutorType t : values()) {
            if (t.typeId.equals(typeId)) {
                return t;
            }
        }
        return UNKNOWN;
    }
}
