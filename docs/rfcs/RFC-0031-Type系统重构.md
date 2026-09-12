# RFC-0031: Type 系统重构 — JSON 树扁平化 + 5 个 Variant

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：3d · **阶段**：S1 增强
> **作者**：架构组 · **日期**：2026-09-12
> **相关 ADR**：ADR-011-Type 系统重构为 JSON 树
> **影响范围**：RFC-0014（数据库迁移）、RFC-0015（元数据 API）、RFC-0018（SimpleTS 解析器）、RFC-0019（SimpleTS→Groovy 代码生成器）

---

## 1. 摘要

将 orule 的 Type 系统从"独立 enum 表 + data_type 字符串"重构为**单一 JSON 树**，支撑 5 种类型 Variant（primitive / enum / object / list / map），并删除 `enum_type` / `enum_value` 两张表。enum 定义完全内联到 `attribute_type.type_json` 中。

**目标**：
- **扁平化**：消除独立 enum 表，减少 JOIN
- **可扩展**：未来加新类型（如 Set、Tuple）无 schema 迁移
- **统一**：Type 系统既供 SimpleTS 静态校验，也供运行时算法接口
- **一层嵌套**：MVP 期间限制 ObjectType 在 SimpleTS 中不可深度访问，强制业务扁平化

---

## 2. 动机

### 2.1 当前痛点

| 问题 | 现状 | 影响 |
|------|------|------|
| enum 表冗余 | `enum_type` + `enum_value` 两张表，单独 CRUD API | 增加 4 个 Controller/Service/Repository；查询要 JOIN |
| 类型表达力不足 | `data_type VARCHAR(32)` 只能存 `string/number/boolean/date/enum/object` | 表达 List/Map 必须借助多个 attribute 拆分 |
| enum 独立于 attribute | `enum_type` 独立定义，attribute 通过 `enum_type_id` 引用 | 跨域 enum 引用需要全局命名；难以做类型树校验 |
| 类型签名不统一 | `function_lib.signature VARCHAR(255)` 是字符串 | 难以做参数类型校验；运行时才知道类型对不对 |
| SimpleTS 不支持 List/Map | 当前 SimpleTS 只有 primitive + entity + enumRef | 未来写算法（如 "order.items.length"）需要 list/map 支持 |

### 2.2 设计目标

1. **类型即数据**：Type 是一个完整的、可序列化的树形结构（JSON）
2. **Variant 化**：通过 sealed interface 表达 5 种类型，未来加 Set/Tuple 只需新增 variant
3. **内联 enum**：enum 值随 AttributeType 定义一起存，无需独立表
4. **共用 Type 系统**：SimpleTS 解析器、FunctionLib 签名、运行时算法都基于同一 Type 树
5. **一层嵌套**：MVP 期间，ObjectType 内的字段在 SimpleTS 中**不可继续访问**，保持 SimpleTS 简单

---

## 3. 详细设计

### 3.1 Type 模型（Java）

放在 `packages/orule-common/src/main/java/com/orule/common/model/type/`：

```java
package com.orule.common.model.type;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Type 系统的根接口。5 个 Variant 用 sealed 子类型表达，
 * Jackson 多态序列化（kind 字段）持久化到 attribute_type.type_json。
 */
public sealed interface Type permits
        PrimitiveType, EnumType, ObjectType, ListType, MapType {

    /** Type 的判别字段，用于 JSON 多态反序列化 */
    String kind();

    /** Jackson 多态配置（注解附着） */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PrimitiveType.class, name = "primitive"),
        @JsonSubTypes.Type(value = EnumType.class,      name = "enum"),
        @JsonSubTypes.Type(value = ObjectType.class,    name = "object"),
        @JsonSubTypes.Type(value = ListType.class,      name = "list"),
        @JsonSubTypes.Type(value = MapType.class,       name = "map"),
    })
    @interface PolymorphicConfig {}
}
```

```java
package com.orule.common.model.type;

import java.util.Set;

/**
 * 原子类型：string / number / boolean / date
 */
@Type.PolymorphicConfig
public record PrimitiveType(String name) implements Type {
    public static final PrimitiveType STRING  = new PrimitiveType("string");
    public static final PrimitiveType NUMBER  = new PrimitiveType("number");
    public static final PrimitiveType BOOLEAN = new PrimitiveType("boolean");
    public static final PrimitiveType DATE    = new PrimitiveType("date");

    public PrimitiveType {
        if (!Set.of("string", "number", "boolean", "date").contains(name)) {
            throw new IllegalArgumentException("Unknown primitive type: " + name);
        }
    }
    @Override public String kind() { return "primitive"; }
}
```

```java
package com.orule.common.model.type;

import java.util.List;
import java.util.Optional;

/**
 * 内联枚举：值随类型定义一起存储，无需独立的 enum_value 表。
 * JSON 形态：
 *   {kind:"enum", enumCode:"CustomerTier", values:[
 *     {code:"VIP", label:"VIP 客户", sortOrder:1}, ...]}
 */
@Type.PolymorphicConfig
public record EnumType(
        String enumCode,
        List<EnumValue> values
) implements Type {
    public record EnumValue(String code, String label, Integer sortOrder) {}

    public EnumType {
        values = List.copyOf(values); // 不可变
    }
    public Optional<EnumValue> findByCode(String code) {
        return values.stream().filter(v -> v.code().equals(code)).findFirst();
    }
    @Override public String kind() { return "enum"; }
}
```

```java
package com.orule.common.model.type;

/**
 * 对象引用：指向同一 DomainType 下的另一个 ObjectType.code。
 * MVP 约束（见 §3.5）：SimpleTS 表达式不允许继续访问 ObjectType 内部属性，
 * 但 ObjectType 内部仍有完整 fields 结构，供运行时类型检查和算法使用。
 */
@Type.PolymorphicConfig
public record ObjectType(String objectCode) implements Type {
    @Override public String kind() { return "object"; }
}
```

```java
package com.orule.common.model.type;

/**
 * 列表类型：elementType 可以是任意 Type（含嵌套）。
 * 例：{kind:"list", elementType:{kind:"primitive",name:"string"}}
 */
@Type.PolymorphicConfig
public record ListType(Type elementType) implements Type {
    @Override public String kind() { return "list"; }
}
```

```java
package com.orule.common.model.type;

/**
 * 字典类型：keyType 与 valueType 均为 Type。
 * 例：{kind:"map", keyType:{kind:"primitive",name:"string"},
 *      valueType:{kind:"object",objectCode:"Order"}}
 */
@Type.PolymorphicConfig
public record MapType(Type keyType, Type valueType) implements Type {
    @Override public String kind() { return "map"; }
}
```

```java
package com.orule.common.model.type;

import java.util.List;

/** 函数签名：参数列表 + 返回类型 */
public record FunctionSignature(List<Type> params, Type returnType) {}
```

### 3.2 数据库迁移

**重写 V1__init_metadata.sql**（因为 MVP 尚未上线，schema 改造可在 V1 直接落地）：

```sql
-- V1: 元数据域（DomainType / ObjectType / AttributeType / FunctionLib）
-- RFC-0031 §3.2 重构版

CREATE TABLE domain_type (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    owner_code      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_domain_code (code)
);

CREATE TABLE object_type (
    id              VARCHAR(36)  NOT NULL,
    domain_id       VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_domain_code (domain_id, code),
    CONSTRAINT fk_object_domain FOREIGN KEY (domain_id) REFERENCES domain_type(id)
);

CREATE TABLE attribute_type (
    id              VARCHAR(36)  NOT NULL,
    object_id       VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    data_type       VARCHAR(32)  NOT NULL,           -- kind 标签: primitive|enum|object|list|map
    type_json       JSON         NOT NULL,            -- 完整 Type 结构
    is_required     BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value   VARCHAR(255),
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attr_object_code (object_id, code),
    CONSTRAINT fk_attr_object FOREIGN KEY (object_id) REFERENCES object_type(id)
);

CREATE INDEX idx_attr_data_type ON attribute_type(data_type);

-- function_lib.signature 改为 JSON，存储完整 Type 树签名
-- 例：{"params":[{"kind":"primitive","name":"number"}, ...], "return":{"kind":"primitive","name":"number"}}
CREATE TABLE function_lib (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    signature       JSON         NOT NULL,            -- 函数签名（参数 + 返回类型的 Type 树）
    description     TEXT,
    category        VARCHAR(64),
    is_builtin      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_func_code (code)
);

-- 删除：enum_type 与 enum_value 表（enum 定义内联到 attribute_type.type_json 中）
```

**修改 V5__init_seed.sql**（移除 enum 种子数据，将示例 enum 内联到 attribute）：

```sql
-- V5: 种子数据（内置 DomainType / ObjectType / AttributeType / FunctionLib 示例）
-- RFC-0031 §3.2 重构版：enum 全部内联到 attribute_type.type_json

INSERT INTO domain_type (id, code, name, description, owner_code) VALUES
  ('dom-order', 'ORDER', 'ORDER Domain', 'Order related rules', 'system'),
  ('dom-customer', 'CUSTOMER', 'CUSTOMER Domain', 'Customer related rules', 'system');

INSERT INTO object_type (id, domain_id, code, name, description) VALUES
  ('obj-customer', 'dom-customer', 'Customer', 'Customer Entity', 'Customer metadata'),
  ('obj-order',    'dom-order',    'Order',    'Order Entity',    'Order metadata');

-- 注意：每个 attribute 的 type_json 必须包含完整的 type 结构
INSERT INTO attribute_type (id, object_id, code, name, data_type, type_json, is_required) VALUES
  ('attr-cust-id',
   'obj-customer', 'id', 'Customer ID', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'string'),
   TRUE),
  ('attr-cust-name',
   'obj-customer', 'name', 'Customer Name', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'string'),
   TRUE),
  ('attr-cust-tier',
   'obj-customer', 'tier', 'Customer Tier', 'enum',
   JSON_OBJECT(
     'kind', 'enum',
     'enumCode', 'CustomerTier',
     'values', JSON_ARRAY(
       JSON_OBJECT('code', 'VIP',    'label', 'VIP Customer',    'sortOrder', 1),
       JSON_OBJECT('code', 'GOLD',   'label', 'Gold Customer',   'sortOrder', 2),
       JSON_OBJECT('code', 'SILVER', 'label', 'Silver Customer', 'sortOrder', 3),
       JSON_OBJECT('code', 'BRONZE', 'label', 'Bronze Customer', 'sortOrder', 4)
     )
   ),
   TRUE),
  ('attr-order-id',
   'obj-order', 'id', 'Order ID', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'string'),
   TRUE),
  ('attr-order-total',
   'obj-order', 'totalAmount', 'Total Amount', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'number'),
   TRUE),
  ('attr-order-discount',
   'obj-order', 'discount', 'Discount', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'number'),
   FALSE),
  ('attr-order-prices',
   'obj-order', 'itemPrices', 'Item Prices', 'list',
   JSON_OBJECT(
     'kind', 'list',
     'elementType', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   FALSE),
  ('attr-order-tax',
   'obj-order', 'taxBreakdown', 'Tax Breakdown', 'map',
   JSON_OBJECT(
     'kind', 'map',
     'keyType', JSON_OBJECT('kind', 'primitive', 'name', 'string'),
     'valueType', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   FALSE);

INSERT INTO function_lib (id, code, name, signature, description, category, is_builtin) VALUES
  ('func-max',
   'max', 'Maximum',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Return the larger of two values', 'math', TRUE),
  ('func-min',
   'min', 'Minimum',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Return the smaller of two values', 'math', TRUE),
  ('func-abs',
   'abs', 'Absolute Value',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'number')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Return absolute value', 'math', TRUE),
  ('func-round',
   'round', 'Round',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Round to n decimal places', 'math', TRUE),
  ('func-upper',
   'upper', 'Upper Case',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'string')
   ),
   'Convert to upper case', 'string', TRUE),
  ('func-lower',
   'lower', 'Lower Case',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'string')
   ),
   'Convert to lower case', 'string', TRUE),
  ('func-contains',
   'contains', 'Contains',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'string'),
       JSON_OBJECT('kind', 'primitive', 'name', 'string')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'boolean')
   ),
   'Check if string contains substring', 'string', TRUE),
  ('func-now',
   'now', 'Current Time',
   JSON_OBJECT('params', JSON_ARRAY(), 'return', JSON_OBJECT('kind', 'primitive', 'name', 'date')),
   'Return current timestamp', 'date', TRUE),
  ('func-days',
   'days', 'Days Between',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'date'),
       JSON_OBJECT('kind', 'primitive', 'name', 'date')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Return number of days between two dates', 'date', TRUE);

INSERT INTO artifact_storage_config (id, code, storage_type, is_default, config_json, is_active) VALUES
  ('storage-local', 'local-default', 'local', TRUE,
   '{"basePath":"C:/Users/Administrator/orule/data/artifacts","baseUrl":"http://localhost:8080/api/v1/artifacts"}',
   TRUE);
```

### 3.3 实体类

#### `AttributeType.java`（改造）

```java
package com.orule.common.entity;

import com.orule.common.model.type.Type;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "attribute_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_attr_object_code", columnNames = {"object_id", "code"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AttributeType {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "object_id", nullable = false)
    private ObjectType object;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** Type 判别标签：primitive | enum | object | list | map */
    @Column(name = "data_type", nullable = false, length = 32)
    private String dataType;

    /** 完整 Type 结构（JSON 树） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "type_json", nullable = false, columnDefinition = "JSON")
    private Type type;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private Boolean isRequired = false;

    @Column(name = "default_value", length = 255)
    private String defaultValue;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

#### `FunctionLib.java`（改造）

```java
package com.orule.common.entity;

import com.orule.common.model.type.FunctionSignature;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "function_lib")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class FunctionLib {
    @Id
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 函数签名（参数 + 返回类型的 Type 树，JSON） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSON")
    private FunctionSignature signature;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String category;

    @Column(name = "is_builtin", nullable = false)
    @Builder.Default
    private Boolean isBuiltin = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

#### 删除

- `EnumType.java`
- `EnumValue.java`
- `EnumTypeRepository.java`
- `EnumTypeService.java`
- `EnumTypeController.java`

### 3.4 DTO 改造

#### `AttributeTypeDto.java`（改造）

```java
package com.orule.common.dto;

import com.orule.common.model.type.Type;

public record AttributeTypeDto(
    String id,
    String objectId,
    String code,
    String name,
    String dataType,          // kind: primitive|enum|object|list|map
    Type type,                // 完整 Type 树
    boolean required,
    String defaultValue,
    String description
) {}
```

#### 删除

- `EnumTypeDto.java`
- `EnumValueDto.java`
- `CreateEnumTypeRequest.java`
- `UpdateEnumTypeRequest.java`
- `CreateEnumValueRequest.java`

#### `CreateAttributeTypeRequest.java`（改造）

```java
package com.orule.common.dto;

import com.orule.common.model.type.Type;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAttributeTypeRequest(
    @NotBlank String objectId,
    @NotBlank String code,
    @NotBlank String name,
    @NotBlank String dataType,    // primitive|enum|object|list|map
    @NotNull  Type type,          // 完整 Type 结构
    Boolean required,
    String defaultValue,
    String description
) {}
```

#### `UpdateAttributeTypeRequest.java`（改造）

```java
package com.orule.common.dto;

import com.orule.common.model.type.Type;

public record UpdateAttributeTypeRequest(
    String name,
    String dataType,
    Type type,                    // 完整 Type 结构（可选更新）
    Boolean required,
    String defaultValue,
    String description
) {}
```

#### `FunctionLibDto.java`（改造）

```java
package com.orule.common.dto;

import com.orule.common.model.type.FunctionSignature;

public record FunctionLibDto(
    String id,
    String code,
    String name,
    FunctionSignature signature, // 函数签名（含 params + return）
    String description,
    String category,
    boolean builtin
) {}
```

### 3.5 SimpleTS 语法调整

参考 `docs/dsl/SimpleTS.md` 第 7 节（DomainMeta 形态）和 RFC-0018（解析器）。

#### 3.5.1 取消顶层 enums 声明

**之前**：
```ts
{
  id: 'order-discount',
  enums: [
    { id: 'CustomerTier', values: ['VIP', 'NORMAL'] }
  ],
  entities: [
    { id: 'Customer', fields: [
      { name: 'id',   type: 'string' },
      { name: 'tier', type: { enumRef: 'CustomerTier' } }
    ]}
  ]
}
```

**改造后**：
```ts
{
  id: 'order-discount',
  entities: [
    { id: 'Customer', fields: [
      { name: 'id',   type: { kind: 'primitive', name: 'string' } },
      { name: 'tier', type: {
        kind: 'enum',
        enumCode: 'CustomerTier',
        values: [
          { code: 'VIP',    label: 'VIP 客户', sortOrder: 1 },
          { code: 'NORMAL', label: '普通客户', sortOrder: 2 }
        ]
      }}
    ]}
  ]
}
```

#### 3.5.2 一层嵌套约束（Q5）

`ObjectType` 在 SimpleTS 表达式里**不能继续访问内部属性**。

| 表达式 | 允许 | 说明 |
|--------|------|------|
| `customer.name` | ✅ | name 是 primitive(string) |
| `order.totalAmount` | ✅ | totalAmount 是 primitive(number) |
| `customer.tier == CustomerTier.VIP` | ✅ | tier 是 enum |
| `order.itemPrices.length` | ❌ | itemPrices 是 list，**SimpleTS 不支持 list 内部访问** |
| `customer.address.city` | ❌ | address 是 object，**SimpleTS 不支持跨 ObjectType 嵌套** |
| `order.taxBreakdown["VAT"]` | ❌ | taxBreakdown 是 map，**SimpleTS 不支持 map 访问** |

**实现思路**（在 RFC-0018 的 `FieldValidator.java` 校验逻辑中加入）：
```java
// FieldValidator 中
if (field.type() instanceof ObjectType) {
    // 仅允许将该字段作为整体读取（暂 MVP 不支持）
    // 编译报错：字段 'address' 是 object 类型，SimpleTS 不支持继续访问内部属性
    throw new TssCompileError(
        "FIELD_OBJECT_NOT_ACCESSIBLE",
        "字段 '%s' 是 object 类型，SimpleTS 不支持继续访问内部属性（RFC-0031）",
        fieldName
    );
}
```

**MVP 期间**：
- 简单业务：直接拍平所有字段到顶级 entity
- 复杂业务（list/map/object 嵌套）：在 RFC-0032 中讨论是否放开 SimpleTS 限制
- 算法调用：通过 FunctionLib 签名（Type 树）实现，与 SimpleTS 无关

#### 3.5.3 enum 引用保持现有语法

```ts
if (customer.tier == CustomerTier.VIP) { ... }
```

白名单剪枝时校验 `CustomerTier` 是某个 enum attribute 中 `enumCode` 的值，且 `VIP` 在该 enum 的 values 中。

---

## 4. 备选方案

### 备选 A：多列 + 判别列

```sql
kind VARCHAR(20) NOT NULL
type_ref_id VARCHAR(36)     -- enum/object 引用
element_type_id VARCHAR(36) -- list/map 元素
```

- 优点：保留 FK 约束
- 缺点：复杂、嵌套受限、未来加新类型需要改 schema

### 备选 B：保持 enum 独立表

- 优点：enum 可复用、单一来源
- 缺点：仍有独立 enum 表，违反"扁平化"诉求

### 备选 C（采纳）：JSON 列 + 5 个 Variant

- 优点：单一字段、无 JOIN、原生嵌套、未来加新类型无 schema 迁移
- 缺点：失去 SQL 约束（应用层校验）

---

## 5. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| **数据迁移丢失** | 🟠 中-高 | MVP 尚未上线，重写 V1 直接替换；如有种子数据需要保留，先 git stash 再重写 |
| **Type 反序列化错误** | 🟡 中 | 启动时校验 `attribute_type.type_json` 反序列化；失败 → 明确错误日志 + 应用启动失败 |
| **SimpleTS 表达式跨 ObjectType 访问报错信息不友好** | 🟢 低 | 增强 `FieldValidator` 错误信息（包含 RFC-0031 §3.5.2 引用） |
| **FunctionLib signature JSON 迁移** | 🟡 中 | V1 重写时同步更新 function_lib.signature；Type 反序列化逻辑复用 |
| **外部客户端不兼容** | 🟢 低 | MVP 未上线；API 路径不变，仅 DTO 内部字段调整 |

---

## 6. 实施步骤

| Step | 内容 | 工作量 |
|------|------|--------|
| 1 | 新增 Type 模型类（5 个 record + Jackson 配置 + PolymorphicConfig） | 0.5d |
| 2 | 重写 V1__init_metadata.sql（attribute_type 加 type_json，删除 enum 表） | 0.5d |
| 3 | 修改 V5__init_seed.sql（移除 enum 种子，enum 内联到 attribute） | 0.5d |
| 4 | 修改 `AttributeType.java` 实体（加 type_json + type 字段） | 0.5d |
| 5 | 修改 `FunctionLib.java` 实体（signature 改为 FunctionSignature） | 0.1d |
| 6 | 删除 `EnumType.java` / `EnumValue.java` 实体 | 0.1d |
| 7 | 修改 AttributeType DTO（AttributeTypeDto / Create / Update） | 0.2d |
| 8 | 删除 EnumType DTO / Service / Repository / Controller | 0.1d |
| 9 | 修改 FunctionLib DTO + Request + Service | 0.2d |
| 10 | 修改 `MetadataService.java` | 0.3d |
| 11 | 修改 `MetadataApiIntegrationTest.java`（移除 enum 流程） | 0.3d |
| 12 | 单元测试：Type 序列化/反序列化、嵌套类型、错误恢复 | 0.5d |
| 13 | 集成测试：API 端到端 | 0.3d |
| **总计** | | **3.6d** |

---

## 7. 影响的 RFC / ADR / 文档

### 7.1 直接修改

- **RFC-0014**：数据库迁移基线 → 重写 V1 部分章节，删除 enum 表描述
- **RFC-0015**：元数据域 CRUD API → 修改 AttributeType API 部分，删除 EnumType API 部分
- **RFC-0018**：SimpleTS 解析器 → DomainMeta 改用 Type 树；FieldValidator 加 ObjectType 嵌套限制
- **RFC-0019**：SimpleTS → Groovy 代码生成器 → FunctionLib signature 改用 Type 树

### 7.2 直接新增

- **RFC-0031**：本文档（Type 系统重构）
- **ADR-011**：Type 系统重构为 JSON 树

### 7.3 同步更新

- `docs/04-数据模型.md` §4.2 attribute_type 表说明
- `docs/dsl/SimpleTS.md` §7 DomainMeta 形态
- `docs/rfcs/README.md` 索引新增 RFC-0031

---

## 8. 决策日志

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-12 | Type 系统采用 sealed interface + 5 个 Variant | 扩展性、可读性、未来加 Set/Tuple 无需改 schema |
| 2026-09-12 | Type 存储采用 JSON 列 | 嵌套支持、无 JOIN、单一字段 |
| 2026-09-12 | 完全删除 enum_type / enum_value 表 | 扁平化诉求；MVP 未上线可重写 V1 |
| 2026-09-12 | Type 系统放在 orule-common | 跨模块共享（server 解析、runtime 执行、FunctionLib 签名） |
| 2026-09-12 | enum 定义完全内联（不复用） | MVP 期间优先简单；如未来需要复用，通过 enumId 全局字典处理（独立 RFC） |
| 2026-09-12 | ObjectType 在 SimpleTS 中限制一层嵌套 | MVP 保持 SimpleTS 简单；复杂场景通过 FunctionLib + 拍平字段 |
| 2026-09-12 | FunctionLib 签名采用 Type 树 | 与 Type 系统统一；运行时类型检查可行 |

---

## 9. 未来扩展（不在本 RFC 范围）

- RFC-0032：SimpleTS 支持 list/map/object 嵌套访问
- RFC-0033：enum 全局唯一字典（避免重复内联）
- RFC-0034：运行时类型检查与算法 API
