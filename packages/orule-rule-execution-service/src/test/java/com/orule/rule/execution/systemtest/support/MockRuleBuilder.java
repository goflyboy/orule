package com.orule.rule.execution.systemtest.support;

import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponse;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Fluent mock configuration for {@link RuleManagermentApiClient#getRuleMetadata}.
 *
 * <p>Used via {@link RuleExecutionSystemTestBase#mockRule(String)}:
 *
 * <pre>{@code
 * mockRule("ORDER_VIP_DISCOUNT")
 *     .withType("java-source")
 *     .withSource("result = price * 2");
 * }</pre>
 *
 * <p>Each fluent setter re-installs the mock so the last write wins; you
 * don't need to call {@link #install()} explicitly. Calling {@link #install()}
 * directly is also fine and returns the rule code.
 *
 * <p>Defaults: executorType="java-source", ruleTypeCode="RULE_TYPE_DEMO",
 * paramMetadata and dependencies empty.
 */
public class MockRuleBuilder {

    private final RuleManagermentApiClient client;
    private final String ruleCode;
    private String executorType = "java-source";
    private String sourceCode = "";
    private String ruleTypeCode = "RULE_TYPE_DEMO";
    private List<?> paramMetadata = List.of();
    private List<?> dependencies = List.of();
    private Map<String, Object> extras = Map.of();

    MockRuleBuilder(RuleManagermentApiClient client, String ruleCode) {
        this.client = client;
        this.ruleCode = ruleCode;
    }

    public MockRuleBuilder withType(String executorType) {
        this.executorType = executorType;
        return this;
    }

    public MockRuleBuilder withRuleType(String ruleTypeCode) {
        this.ruleTypeCode = ruleTypeCode;
        return this;
    }

    public MockRuleBuilder withSource(String groovySource) {
        this.sourceCode = groovySource;
        return this;
    }

    public MockRuleBuilder withParamMetadata(List<?> paramMetadata) {
        this.paramMetadata = paramMetadata;
        return this;
    }

    public MockRuleBuilder withDependencies(List<?> dependencies) {
        this.dependencies = dependencies;
        return this;
    }

    public MockRuleBuilder withExtras(Map<String, Object> extras) {
        this.extras = extras;
        return this;
    }

    /**
     * Builds the {@link RuleMetadataResponse} mock and stubs the Feign call.
     * Idempotent; safe to call multiple times.
     */
    public String install() {
        RuleMetadataResponse meta = new RuleMetadataResponse(
                ruleCode,
                ruleTypeCode,
                executorType,
                sourceCode,
                (List<String>) paramMetadata,
                (List<String>) dependencies,
                extras);
        when(client.getRuleMetadata(eq(ruleCode), any(), any(), any()))
                .thenReturn(meta);
        return ruleCode;
    }
}
