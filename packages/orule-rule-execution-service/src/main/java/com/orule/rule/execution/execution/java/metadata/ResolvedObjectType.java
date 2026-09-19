package com.orule.rule.execution.execution.java.metadata;

import com.orule.common.dto.AttributeTypeDto;
import com.orule.common.dto.ObjectTypeDto;
import com.orule.common.dto.ObjectTypeDto.EnumValueDto;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolved view of one ObjectType used by {@code DomainTypePrefixGenerator}
 * and {@code ContextHydrator} (RFC-0045 §4.2 / §4.5).
 *
 * <p>This is a flat, executor-side projection of {@link ObjectTypeDto}. We do
 * <b>not</b> reuse {@link ObjectTypeDto} directly because (a) we want a stable
 * shape that survives wire-format churn in the metadata API, and (b) we add a
 * {@code slotName} that maps to the business entry variable name in
 * {@code RuleType.arguments[*].programCode} (RFC-0033 §3.4) — that information
 * is upstream of the ObjectType itself.
 *
 * <p>{@code slotName == null} means the ObjectType is a free-floating enum
 * referenced by another class attribute (e.g. {@code Customer.tier : CustomerTier})
 * rather than a top-level binding slot.
 */
public record ResolvedObjectType(
        String slotName,
        String programCode,
        String kind,                       // CLASS | ENUM | VOID
        List<ResolvedAttribute> attributes,
        List<EnumValue> enumValues) {

    public ResolvedObjectType {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    public boolean isEnum() { return "ENUM".equals(kind); }
    public boolean isClass() { return "CLASS".equals(kind); }

    /** Domain type reference from {@code AttributeTypeDto.subDataTypeProgramCode}. */
    public record ResolvedAttribute(
            String programCode,
            String name,
            String dataType,                          // primitive | object | list | map
            String refCode,                           // primitive.name or object/list/map target programCode
            String refCode2) {                        // map value target only

        public static ResolvedAttribute of(String programCode, String name,
                                           String dataType, String refCode, String refCode2) {
            return new ResolvedAttribute(programCode, name, dataType, refCode, refCode2);
        }
    }

    /** Enum value entry, projected from {@link EnumValueDto}. */
    public record EnumValue(String code, String label, Integer sortOrder) {}

    // === Factory helpers (RFC-0045 §4.7 fixtures) ===

    public static ResolvedObjectType enumOf(String programCode, List<EnumValue> values) {
        return new ResolvedObjectType(null, programCode, "ENUM", List.of(), values);
    }

    public static ResolvedObjectType classOf(String slotName, String programCode,
                                              List<ResolvedAttribute> attrs) {
        return new ResolvedObjectType(slotName, programCode, "CLASS", attrs, List.of());
    }

    public static ResolvedObjectType voidOf() {
        return new ResolvedObjectType(null, "Void", "VOID", List.of(), List.of());
    }

    public static ResolvedAttribute attr(String programCode, String dataType,
                                          String refCode, String refCode2) {
        return new ResolvedAttribute(programCode, programCode, dataType, refCode, refCode2);
    }

    // === Mapper from upstream DTO ===

    /** Project an upstream {@link ObjectTypeDto} into the executor-side shape. */
    public static ResolvedObjectType fromDto(ObjectTypeDto dto, String slotName) {
        if (dto == null) {
            return null;
        }
        List<EnumValue> enums = dto.enumValues() == null
                ? List.of()
                : dto.enumValues().stream()
                    .map(e -> new EnumValue(e.code(), e.label(), e.sortOrder()))
                    .toList();
        Map<String, ResolvedAttribute> attrs = dto.attributes() == null
                ? Collections.emptyMap()
                : dto.attributes().stream()
                    .collect(Collectors.toMap(
                            AttributeTypeDto::programCode,
                            ResolvedObjectType::fromAttrDto,
                            (a, b) -> a,
                            java.util.LinkedHashMap::new));
        return new ResolvedObjectType(
                slotName,
                dto.programCode(),
                dto.kind() == null ? "CLASS" : dto.kind(),
                List.copyOf(attrs.values()),
                enums);
    }

    private static ResolvedAttribute fromAttrDto(AttributeTypeDto a) {
        return new ResolvedAttribute(
                a.programCode(),
                a.name(),
                a.dataType(),
                a.subDataTypeProgramCode(),
                a.subDataTypeProgramCode2());
    }
}
