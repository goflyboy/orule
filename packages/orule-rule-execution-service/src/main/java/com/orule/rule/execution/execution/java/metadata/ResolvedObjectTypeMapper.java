package com.orule.rule.execution.execution.java.metadata;

import com.orule.common.dto.ObjectTypeDto;

import java.util.List;

/**
 * Maps upstream metadata DTOs into executor-side {@link ResolvedObjectType}s.
 *
 * <p>Why a dedicated mapper (RFC-0045 §B 复用优先): we deliberately avoid
 * shipping the {@link ObjectTypeDto} record across the Feign boundary; instead
 * we project only the fields we need into {@link ResolvedObjectType} so the
 * executor SPI stays independent of the metadata wire format.
 *
 * <p>The mapper is a thin facade over {@link ResolvedObjectType#fromDto} so
 * tests can override individual projections (e.g. swap in a slotName override).
 */
public final class ResolvedObjectTypeMapper {

    private ResolvedObjectTypeMapper() {}

    /** Project one ObjectType DTO into a ResolvedObjectType bound to a slot. */
    public static ResolvedObjectType project(ObjectTypeDto dto, String slotName) {
        return ResolvedObjectType.fromDto(dto, slotName);
    }

    /** Project a list of DTOs, preserving order. */
    public static List<ResolvedObjectType> projectAll(List<ObjectTypeDto> dtos, String defaultSlot) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        return dtos.stream()
                .map(d -> project(d, defaultSlot))
                .toList();
    }
}
