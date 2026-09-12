package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * CreateAttributeTypeRequest（RFC-0032 §3.4）。
 *
 * <p>3 列结构（无 type 字段）：
 * <ul>
 *   <li>primitive 时 sub 填 "string"/"number"/"boolean"/"date"</li>
 *   <li>object 时 sub 填目标 ObjectType.programCode（可为 ENUM 类型）</li>
 *   <li>list 时 sub 填元素类型 code</li>
 *   <li>map 时 sub 填 key 类型；sub2 填 value 类型</li>
 * </ul>
 */
public record CreateAttributeTypeRequest(
    @NotBlank String objectId,
    @NotBlank String programCode,
    @NotBlank String name,
    @NotBlank String dataType,
    String subDataTypeProgramCode,
    String subDataTypeProgramCode2,
    Boolean required,
    String defaultValue,
    String description
) {}
