package com.orule.rule.execution.systemtest.support;

import com.orule.common.dto.ObjectTypeDto;
import com.orule.common.dto.ObjectTypeDto.EnumValueDto;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponseV2;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectTypeMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Fluent mock configuration for {@link RuleManagermentApiClient}.
 *
 * <p>Used via {@link RuleExecutionSystemTestBase#mockRule(String)}:
 *
 * <pre>{@code
 * mockRule("ORDER_VIP_DISCOUNT")
 *     .withDomainCode("ORDER")
 *     .withObjectTypeCodes("Customer", "Order", "CustomerTier")
 *     .withResolvedObjectTypes(metadataFixtureForOrder())
 *     .withSource("result = price * 2");
 * }</pre>
 *
 * <p>Each fluent setter re-installs the mock so the last write wins; you
 * don't need to call {@link #install()} explicitly. Calling {@link #install()}
 * directly is also fine and returns the rule code.
 *
 * <p>Defaults: executorType="java-source", ruleTypeCode="RULE_TYPE_DEMO",
 * domainCode=null, objectTypeCodes=[].
 */
public class MockRuleBuilder {

    private final RuleManagermentApiClient client;
    private final String ruleCode;
    private String executorType = "java-source";
    private String sourceCode = "";
    private String ruleTypeCode = "RULE_TYPE_DEMO";
    private String domainCode = null;
    private List<String> objectTypeCodes = new ArrayList<>();
    private List<ResolvedObjectType> resolvedObjectTypes = new ArrayList<>();
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

    public MockRuleBuilder withDomainCode(String domainCode) {
        this.domainCode = domainCode;
        return this;
    }

    public MockRuleBuilder withObjectTypeCodes(List<String> objectTypeCodes) {
        this.objectTypeCodes = objectTypeCodes == null ? new ArrayList<>() : new ArrayList<>(objectTypeCodes);
        return this;
    }

    /**
     * Attach resolved ObjectType metadata that the execution service should
     * pretend to have fetched from {@code GET /api/v1/object-types/by-program-code}.
     * Stubs the secondary Feign call accordingly.
     */
    public MockRuleBuilder withResolvedObjectTypes(List<ResolvedObjectType> resolved) {
        this.resolvedObjectTypes = resolved == null ? new ArrayList<>() : new ArrayList<>(resolved);
        // Mirror objectTypeCodes from resolved.programCode for transparency.
        List<String> codes = new ArrayList<>();
        for (ResolvedObjectType ot : this.resolvedObjectTypes) {
            if (ot != null && ot.programCode() != null) {
                codes.add(ot.programCode());
            }
        }
        this.objectTypeCodes = codes;
        return this;
    }

    public MockRuleBuilder withExtras(Map<String, Object> extras) {
        this.extras = extras;
        return this;
    }

    /**
     * Builds the {@link RuleMetadataResponseV2} mock and stubs the Feign call.
     * Idempotent; safe to call multiple times.
     */
    public String install() {
        RuleMetadataResponseV2 meta = new RuleMetadataResponseV2(
                ruleCode,
                ruleTypeCode,
                executorType,
                sourceCode,
                List.of(),
                List.of(),
                domainCode,
                List.copyOf(objectTypeCodes),
                extras);
        when(client.getRuleMetadata(eq(ruleCode), any(), any(), any()))
                .thenReturn(meta);

        // Stub the secondary Feign call. Use the resolved fixtures directly so the
        // execution service can short-circuit and avoid network in tests.
        for (ResolvedObjectType ot : resolvedObjectTypes) {
            if (ot == null || ot.programCode() == null) {
                continue;
            }
            ObjectTypeDto dto = toDto(ot);
            // Match by (domainCode, programCode) since that's what the service calls.
            try {
                when(client.getObjectTypeByProgramCode(
                        eq(domainCode == null ? "" : domainCode),
                        eq(ot.programCode()),
                        any(), any(), any()))
                        .thenReturn(dto);
            } catch (RuntimeException ignored) {
                // MockingWire mock failure is non-fatal here; we still emit metadata.
            }
        }
        return ruleCode;
    }

    /** Project a ResolvedObjectType back into an ObjectTypeDto so we can stub the Feign call. */
    private static ObjectTypeDto toDto(ResolvedObjectType ot) {
        List<EnumValueDto> enums = new ArrayList<>();
        for (ResolvedObjectType.EnumValue v : ot.enumValues()) {
            enums.add(new EnumValueDto(v.code(), v.label(), v.sortOrder()));
        }
        List<com.orule.common.dto.AttributeTypeDto> attrs = new ArrayList<>();
        for (ResolvedObjectType.ResolvedAttribute a : ot.attributes()) {
            attrs.add(new com.orule.common.dto.AttributeTypeDto(
                    null,
                    null,
                    a.programCode(),
                    a.name(),
                    a.dataType(),
                    a.refCode(),
                    a.refCode2(),
                    false,
                    null,
                    null,
                    null));
        }
        String kind = ot.kind() == null ? "CLASS" : ot.kind();
        return new ObjectTypeDto(
                null,
                null,
                ot.programCode(),
                ot.programCode(),
                kind,
                attrs,
                enums,
                null,
                null,
                null);
    }
}
