package com.orule.common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 元数据 DTO 字段校验（RFC-0032 §3.4）。
 *
 * <p>RFC-0032 修订：
 * <ul>
 *   <li>元数据域 DTO 字段 code → programCode</li>
 *   <li>ObjectTypeDto 加 kind + enumValues 字段</li>
 *   <li>AttributeTypeDto 用 3 列结构（dataType + subDataTypeProgramCode + sub2）</li>
 *   <li>CreateAttributeTypeRequest 用 3 列结构，移除 type 字段</li>
 * </ul>
 */
public class MetadataDtoTest {

    @Test
    @DisplayName("DomainTypeDto 字段 programCode（非 code）")
    void domainTypeDto_programCodeField() {
        DomainTypeDto dto = new DomainTypeDto(
            "dom-1", "ORDER", "ORDER Domain",
            "desc", "system", null, null);
        assertEquals("ORDER", dto.programCode());
    }

    @Test
    @DisplayName("ObjectTypeDto 字段 programCode + kind + enumValues")
    void objectTypeDto_rfc0032Fields() {
        ObjectTypeDto dto = new ObjectTypeDto(
            "obj-1", "dom-1", "CustomerTier", "Customer Tier", "ENUM",
            null, null, "desc", null, null);
        assertEquals("CustomerTier", dto.programCode());
        assertEquals("ENUM", dto.kind());
        assertEquals(null, dto.enumValues());
    }

    @Test
    @DisplayName("CreateObjectTypeRequest 含 kind + enumValues 字段")
    void createObjectTypeRequest_kindEnumValues() {
        CreateObjectTypeRequest req = new CreateObjectTypeRequest(
            "dom-1", "CustomerTier", "Customer Tier",
            "ENUM", null, "desc");
        assertEquals("CustomerTier", req.programCode());
        assertEquals("ENUM", req.kind());
    }

    @Test
    @DisplayName("AttributeTypeDto 字段 programCode + 3 列 Type 表达")
    void attributeTypeDto_threeColumns() {
        AttributeTypeDto dto = new AttributeTypeDto(
            "a-1", "obj-1", "tier", "Customer Tier",
            "object", "CustomerTier", null,
            true, null, "desc", null);
        assertEquals("tier", dto.programCode());
        assertEquals("object", dto.dataType());
        assertEquals("CustomerTier", dto.subDataTypeProgramCode());
        assertEquals(null, dto.subDataTypeProgramCode2());
    }

    @Test
    @DisplayName("CreateAttributeTypeRequest 用 3 列结构（移除 type 字段）")
    void createAttributeTypeRequest_threeColumns() {
        CreateAttributeTypeRequest req = new CreateAttributeTypeRequest(
            "obj-1", "tier", "Customer Tier",
            "object", "CustomerTier", null,
            true, null, "desc");
        assertEquals("tier", req.programCode());
        assertEquals("object", req.dataType());
        assertEquals("CustomerTier", req.subDataTypeProgramCode());
        assertEquals(null, req.subDataTypeProgramCode2());
    }

    @Test
    @DisplayName("UpdateAttributeTypeRequest 含 3 列字段")
    void updateAttributeTypeRequest_threeColumns() {
        UpdateAttributeTypeRequest req = new UpdateAttributeTypeRequest(
            "Customer Tier", "object", "CustomerTier", null,
            true, null, "desc");
        assertEquals("CustomerTier", req.subDataTypeProgramCode());
        assertEquals(null, req.subDataTypeProgramCode2());
    }

    @Test
    @DisplayName("FuntionTypeDto 字段 programCode")
    void funtionTypeDto_programCodeField() {
        FuntionTypeDto dto = new FuntionTypeDto(
            "f-1", "max", "Maximum", null, "desc", "math", true);
        assertEquals("max", dto.programCode());
    }

    @Test
    @DisplayName("CreateFuntionTypeRequest 字段 programCode")
    void createFuntionTypeRequest_programCodeField() {
        CreateFuntionTypeRequest req = new CreateFuntionTypeRequest(
            "max", "Maximum", null, "desc", "math", true);
        assertEquals("max", req.programCode());
    }
}
