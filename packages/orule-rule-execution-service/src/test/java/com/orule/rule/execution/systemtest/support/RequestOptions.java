package com.orule.rule.execution.systemtest.support;

/**
 * Per-request options for {@link RuleExecutionSystemTestBase#executeRule} and
 * {@code executeRuleSet}. Immutable record.
 *
 * <p>Defaults: {@code sendTenant=true}.
 * Use {@link RuleExecutionSystemTestBase#withoutTenantHeader()} to flip the flag
 * for negative-path tests.
 */
public record RequestOptions(boolean sendTenant) {

    public static RequestOptions defaults() {
        return new RequestOptions(true);
    }

    public static RequestOptions withoutTenant() {
        return new RequestOptions(false);
    }
}
