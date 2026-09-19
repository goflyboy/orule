package com.orule.rule.execution.execution.java.metadata;

import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectTypeMapper;
import com.orule.common.dto.AttributeTypeDto;
import com.orule.common.dto.ObjectTypeDto;
import com.orule.common.dto.ObjectTypeDto.EnumValueDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0045 §4.5 unit tests for upstream DTO → ResolvedObjectType mapping.
 */
@DisplayName("ResolvedObjectTypeMapper")
class ResolvedObjectTypeMapperTest {

    @Test
    @DisplayName("project maps CLASS ObjectType with attributes and slotName override")
    void projectClass() {
        ObjectTypeDto dto = new ObjectTypeDto(
                "id1", "dom1", "Customer", "Customer",
                "CLASS",
                List.of(new AttributeTypeDto("a1", "id1", "name", "name", "primitive", "string", null, false, null, null, null),
                        new AttributeTypeDto("a2", "id1", "tier", "tier", "object",    "CustomerTier", null, false, null, null, null)),
                List.of(),
                null, null, null);
        ResolvedObjectType ot = ResolvedObjectTypeMapper.project(dto, "customer");
        assertThat(ot.slotName()).isEqualTo("customer");
        assertThat(ot.programCode()).isEqualTo("Customer");
        assertThat(ot.kind()).isEqualTo("CLASS");
        assertThat(ot.attributes()).hasSize(2);
        assertThat(ot.attributes().get(0).programCode()).isEqualTo("name");
        assertThat(ot.attributes().get(1).dataType()).isEqualTo("object");
        assertThat(ot.attributes().get(1).refCode()).isEqualTo("CustomerTier");
    }

    @Test
    @DisplayName("project maps ENUM ObjectType with enumValues")
    void projectEnum() {
        ObjectTypeDto dto = new ObjectTypeDto(
                "id2", "dom1", "CustomerTier", "Customer Tier",
                "ENUM",
                List.of(),
                List.of(new EnumValueDto("VIP", "VIP", 1), new EnumValueDto("GOLD", "Gold", 2)),
                null, null, null);
        ResolvedObjectType ot = ResolvedObjectTypeMapper.project(dto, null);
        assertThat(ot.isEnum()).isTrue();
        assertThat(ot.enumValues()).hasSize(2);
        assertThat(ot.enumValues().get(0).code()).isEqualTo("VIP");
    }

    @Test
    @DisplayName("projectAll preserves order and handles empty input")
    void projectAll() {
        assertThat(ResolvedObjectTypeMapper.projectAll(null, null)).isEmpty();
        assertThat(ResolvedObjectTypeMapper.projectAll(List.of(), null)).isEmpty();
        ObjectTypeDto e = new ObjectTypeDto("e", "d", "X", "X", "ENUM", List.of(),
                List.of(new EnumValueDto("A", "A", 1)), null, null, null);
        ObjectTypeDto c = new ObjectTypeDto("c", "d", "Y", "Y", "CLASS", List.of(), List.of(),
                null, null, null);
        List<ResolvedObjectType> all = ResolvedObjectTypeMapper.projectAll(List.of(e, c), "y");
        assertThat(all).hasSize(2);
        assertThat(all.get(0).isEnum()).isTrue();
        assertThat(all.get(1).slotName()).isEqualTo("y");
    }
}
