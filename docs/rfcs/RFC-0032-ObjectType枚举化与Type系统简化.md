# RFC-0032: ObjectType 枚举化 + Type 系统简化（5 Variant → 4 Variant）

> **状态**：APPROVED · **优先级**：P0 · **预计工作量**：2d · **阶段**：S1 增强
> **作者**：架构组 · **日期**：2026-09-12
> **相关 ADR**：ADR-012 enum 视为 ObjectType 的特殊形态
> **影响范围**：RFC-0031（Type 系统重构）、RFC-0014（数据库迁移）、RFC-0015（元数据 API）、RFC-0018（SimpleTS 解析器）、RFC-0019（SimpleTS→Groovy 代码生成器）
> **前置 RFC**：[RFC-0031-Type系统重构](RFC-0031-Type系统重构.md)（本 RFC 在其基础上调整）

---

## 1. 摘要

将 RFC-0031 中的 `EnumType` 视为 `ObjectType` 的一种特殊形态（enum 即"值集合固定的对象"），从而：

1. **Type Variant 从 5 个减为 4 个** —— 删 `EnumType` variant，`ObjectType` 用 `kind` 区分
2. **新增 `object_type.kind`（`CLASS` / `ENUM`）+ `object_type.enum_values`（JSON）** —— enum 值挂在 ObjectType 上，可跨 attribute 复用
3. **`attribute_type.type_json` 拆为 3 列**（`data_type` + `sub_data_type_program_code` + `sub_data_type_program_code_2`）—— 摆脱 JSON 列
4. **统一字段命名**：`code` → `programCode`（Java 字段与列名同步），语义清晰
5. **DomainMeta 直接使用 `entity.ObjectType`** —— 暂保留 JPA 依赖，记技术债

**目标**：
- **enum 跨 attribute 复用**：当前内联不可复用，多个 field 共享 CustomerTier 时必须重复 values
- **Type 系统扁平化**：attribute_type 不再依赖 JSON 列，关系化更彻底
- **减少 1 个 Type variant**：sealed interface 更简单，未来加 Set/Tuple 边际成本更低
- **API/DTO 命名一致**：`programCode` 语义比 `code` 更明确（强调"可编程代码"）

---

## 2. 动机

### 2.1 RFC-0031 的遗留问题

RFC-0031 已将 enum 内联到 `attribute_type.type_json`，解决了"enum 独立表冗余"的问题，但仍存在：

| 问题 | RFC-0031 现状 | 影响 |
|------|----------------|------|
| **enum 不能跨 attribute 复用** | 每个 attribute 单独内联 `EnumType.values` | 同一 enum 被多个字段引用时（如 CustomerTier 被 customer.tier 和 order.customerTier 同时引用），必须复制 values |
| **Type Variant 多 1 个** | 5 个 variant：`PrimitiveType / EnumType / ObjectType / ListType / MapType` | sealed interface 复杂度略高；"enum 是 type 还是 attribute 的属性"语义不直观 |
| **attribute_type 用 JSON 列存 Type** | `attribute_type.type_json` 列 | 失去 SQL 约束；无法做 SQL JOIN |
| **`code` 命名歧义** | entity 用 `code`，SimpleTS 用 `name` | SimpleTS → Java 实体字段映射容易混淆（`entity.code` vs `dsl.name`） |

### 2.2 enum 的本质：特殊对象

主流语言（Java/Kotlin/TypeScript）的一致定义：**enum 是一种特殊的类**，值的集合封闭。
- Java `enum CustomerTier { VIP, GOLD, SILVER, BRONZE }` —— 编译为 `final class CustomerTier extends java.lang.Enum<CustomerTier>`
- TypeScript `enum CustomerTier { VIP = 'VIP' }` —— 编译为对象

**结论**：在数据模型里，enum 就是 ObjectType 的一种（kind=ENUM）。AttributeType 通过 `sub_data_type_program_code` 引用 `object_type.program_code`，不管是 CustomerTier（ENUM）还是 Customer（CLASS）。

```
CustomerTier   (object_type.kind='ENUM')
  enumValues: [{code:"VIP", label:"VIP 客户", sortOrder:1}, ...]

Customer       (object_type.kind='CLASS')
  attributes:
    - id    (primitive/string)
    - tier  (object/CustomerTier)  ← 直接通过 program_code 引用 enum 类型的 ObjectType
```

---

## 3. 详细设计

### 3.1 Type 模型（Java）

放在 `packages/orule-common/src/main/java/com/orule/common/model/type/`：

```java
package com.orule.common.model.type;

/**
 * Type 系统的根接口。4 个 Variant 用 sealed 子类型表达。
 * RFC-0032 修订：删除 EnumType（合入 ObjectType.kind=ENUM）。
 */
public sealed interface Type permits
        PrimitiveType, ObjectRef, ListType, MapType {

    /** Type 的判别字段。 */
    String kind();
}
```

```java
package com.orule.common.model.type;

/**
 * 原子类型：string / number / boolean / date
 */
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

/**
 * 对象引用：指向同一 DomainType 下的某个 ObjectType.programCode。
 * RFC-0032 修订：替代 RFC-0031 的 ObjectType record；ObjectType 升格为 entity 实体，
 * Type variant 中仅保留 programCode 引用（不再嵌套 fields）。
 *
 * MVP 约束：SimpleTS 表达式不允许继续访问 ObjectType 内部属性（见 RFC-0018 修订）。
 */
public record ObjectRef(String programCode) implements Type {
    @Override public String kind() { return "object"; }
}
```

```java
package com.orule.common.model.type;

/**
 * 列表类型：elementType 可以是任意 Type（含嵌套）。
 * 例：list<string> → ListType(PrimitiveType("string"))
 */
public record ListType(Type elementType) implements Type {
    @Override public String kind() { return "list"; }
}
```

```java
package com.orule.common.model.type;

/**
 * 字典类型：keyType 与 valueType 均为 Type。
 * 例：map<string,number> → MapType(PrimitiveType("string"), PrimitiveType("number"))
 */
public record MapType(Type keyType, Type valueType) implements Type {
    @Override public String kind() { return "map"; }
}
```

```java
package com.orule.common.model.type;

import java.util.List;

/** 函数签名：参数列表 + 返回类型（保持 RFC-0031 不变） */
public record FunctionSignature(List<Type> params, Type returnType) {}
```

### 3.2 数据库迁移（V1 重写）

```sql
-- V1: 元数据域（DomainType / ObjectType / AttributeType / FunctionLib）
-- RFC-0032 §3.2 重构版：Type 扁平化为 3 列；enum 升格为 ObjectType.kind=ENUM

CREATE TABLE domain_type (
    id              VARCHAR(36)  NOT NULL,
    program_code    VARCHAR(64)  NOT NULL,           -- RFC-0032 重命名
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    owner_code      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_domain_program_code (program_code)
);

CREATE TABLE object_type (
    id              VARCHAR(36)  NOT NULL,
    domain_id       VARCHAR(36)  NOT NULL,
    program_code    VARCHAR(64)  NOT NULL,           -- RFC-0032 重命名
    name            VARCHAR(128) NOT NULL,
    -- RFC-0032 新增：ObjectType 种类
    kind            VARCHAR(16)  NOT NULL,           -- CLASS | ENUM
    -- RFC-0032 新增：仅 kind=ENUM 时使用，存 [{code, label, sortOrder}, ...]
    enum_values     JSON,
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_domain_program_code (domain_id, program_code),
    CONSTRAINT fk_object_domain FOREIGN KEY (domain_id) REFERENCES domain_type(id)
);

CREATE INDEX idx_object_kind ON object_type(kind);

CREATE TABLE attribute_type (
    id                          VARCHAR(36)  NOT NULL,
    object_id                   VARCHAR(36)  NOT NULL,
    program_code                VARCHAR(64)  NOT NULL,   -- RFC-0032 重命名
    name                        VARCHAR(128) NOT NULL,
    -- RFC-0032 重构：type_json 拆为 3 列
    data_type                   VARCHAR(32)  NOT NULL,   -- primitive|object|list|map
    sub_data_type_program_code  VARCHAR(64),             -- primitive.name 或 object.list.map 的目标 programCode
    sub_data_type_program_code2 VARCHAR(64),             -- 仅 map 使用（value 类型的 programCode）
    is_required                 BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value               VARCHAR(255),
    description                 TEXT,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attr_object_program_code (object_id, program_code),
    CONSTRAINT fk_attr_object FOREIGN KEY (object_id) REFERENCES object_type(id)
);

CREATE INDEX idx_attr_data_type ON attribute_type(data_type);

-- function_lib.signature 改为 JSON（沿用 RFC-0031，不变）
CREATE TABLE function_lib (
    id              VARCHAR(36)  NOT NULL,
    program_code    VARCHAR(64)  NOT NULL,               -- RFC-0032 重命名
    name            VARCHAR(128) NOT NULL,
    signature       JSON         NOT NULL,
    description     TEXT,
    category        VARCHAR(64),
    is_builtin      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_func_program_code (program_code)
);

-- 删除 RFC-0031 仍保留的 enum_type / enum_value 表
-- （已在 RFC-0031 中删除，本 RFC 不再保留）
```

**`data_type` × `sub_data_type_program_code` 矩阵**：

| `data_type` | `sub_data_type_program_code` | `sub_data_type_program_code2` | 构造出的 Type |
|-------------|------------------------------|-------------------------------|---------------|
| `primitive` | `"string"` / `"number"` / `"boolean"` / `"date"` | NULL | `PrimitiveType(name)` |
| `object`    | 目标 `object_type.program_code`（CLASS 或 ENUM） | NULL | `ObjectRef(programCode)` |
| `list`      | 元素类型的 `program_code` 或 primitive name | NULL | `ListType(buildType(...))`（递归） |
| `map`       | key 类型（通常 primitive） | value 类型的 programCode 或 primitive name | `MapType(buildType(...), buildType(...))` |

### 3.3 实体类

#### `ObjectType.java`（重构）

```java
package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * ObjectType 元数据。
 * RFC-0032 修订：
 * - code → programCode
 * - 新增 kind（CLASS | ENUM）
 * - 新增 enumValues（仅 kind=ENUM 时使用）
 * - RFC-0018/0019 中 DomainMeta 直接使用本实体（暂保留 JPA 依赖，记技术债）
 */
@Entity
@Table(name = "object_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_object_domain_program_code",
                       columnNames = {"domain_id", "program_code"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ObjectType {

    /** ObjectType 种类：CLASS（普通对象）/ ENUM（枚举） */
    public enum Kind { CLASS, ENUM }

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_id", nullable = false)
    private DomainType domain;

    @Column(name = "program_code", nullable = false, length = 64)
    private String programCode;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Kind kind = Kind.CLASS;

    /** 仅 kind=ENUM 时使用，存 [{code, label, sortOrder}, ...] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enum_values", columnDefinition = "JSON")
    private List<EnumValue> enumValues;

    @OneToMany(mappedBy = "object", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AttributeType> attributes = new java.util.ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** enum 值定义（嵌入 ObjectType） */
    public record EnumValue(String code, String label, Integer sortOrder) {}
}
```

#### `AttributeType.java`（重构）

```java
package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * AttributeType 元数据。
 * RFC-0032 重构：
 * - code → programCode
 * - type_json 拆为 3 列（data_type + sub_data_type_program_code + sub_data_type_program_code2）
 * - 删除 typeJson 字段
 */
@Entity
@Table(name = "attribute_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_attr_object_program_code",
                       columnNames = {"object_id", "program_code"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AttributeType {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "object_id", nullable = false)
    private ObjectType object;

    @Column(name = "program_code", nullable = false, length = 64)
    private String programCode;

    @Column(nullable = false, length = 128)
    private String name;

    /** Type 判别标签：primitive | object | list | map */
    @Column(name = "data_type", nullable = false, length = 32)
    private String dataType;

    /** primitive.name 或 object/list/map 的目标 programCode */
    @Column(name = "sub_data_type_program_code", length = 64)
    private String subDataTypeProgramCode;

    /** 仅 map 使用（value 类型的 programCode） */
    @Column(name = "sub_data_type_program_code2", length = 64)
    private String subDataTypeProgramCode2;

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

#### `FunctionLib.java`、`DomainType.java`（仅重命名 `code` → `programCode`）

略，沿用 RFC-0031 结构。

#### 删除

- `packages/orule-common/src/main/java/com/orule/common/model/type/EnumType.java` —— **删除**
- `packages/orule-common/src/main/java/com/orule/common/model/type/EnumValue.java`（若独立文件）—— **删除**
- `packages/orule-common/src/main/java/com/orule/common/model/type/ObjectType.java` —— **重命名**为 `ObjectRef.java`（内容改为 §3.1 中的 record）

### 3.4 DTO 改造

#### `ObjectTypeDto.java`（重构）

```java
package com.orule.common.dto;

import com.orule.common.entity.ObjectType;

import java.util.List;

public record ObjectTypeDto(
    String id,
    String domainId,
    String programCode,
    String name,
    ObjectType.Kind kind,              // CLASS | ENUM
    List<ObjectType.EnumValue> enumValues,  // 仅 kind=ENUM
    String description
) {
    public static ObjectTypeDto from(ObjectType entity) {
        return new ObjectTypeDto(
            entity.getId(),
            entity.getDomain().getId(),
            entity.getProgramCode(),
            entity.getName(),
            entity.getKind(),
            entity.getEnumValues(),
            entity.getDescription()
        );
    }
}
```

#### `AttributeTypeDto.java`（重构）

```java
package com.orule.common.dto;

import com.orule.common.model.type.Type;

public record AttributeTypeDto(
    String id,
    String objectId,
    String programCode,
    String name,
    String dataType,                   // primitive | object | list | map
    String subDataTypeProgramCode,
    String subDataTypeProgramCode2,
    boolean required,
    String defaultValue,
    String description,
    /** 由 Server 端根据 3 列 + object_type 表组装出来的完整 Type 树 */
    Type type
) {}
```

#### `CreateObjectTypeRequest.java`（重构）

```java
package com.orule.common.dto;

import com.orule.common.entity.ObjectType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateObjectTypeRequest(
    @NotBlank String domainId,
    @NotBlank String programCode,
    @NotBlank String name,
    @NotNull  ObjectType.Kind kind,                 // CLASS | ENUM
    List<ObjectType.EnumValue> enumValues,           // kind=ENUM 时必填
    String description
) {}
```

#### `CreateAttributeTypeRequest.java`（重构）

```java
package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAttributeTypeRequest(
    @NotBlank String objectId,
    @NotBlank String programCode,
    @NotBlank String name,
    @NotBlank String dataType,                  // primitive | object | list | map
    String subDataTypeProgramCode,              // primitive.name 或目标 programCode
    String subDataTypeProgramCode2,             // 仅 map
    Boolean required,
    String defaultValue,
    String description
) {}
```

#### `UpdateAttributeTypeRequest.java`（重构）

```java
package com.orule.common.dto;

public record UpdateAttributeTypeRequest(
    String name,
    String dataType,
    String subDataTypeProgramCode,
    String subDataTypeProgramCode2,
    Boolean required,
    String defaultValue,
    String description
) {}
```

#### 删除

- `EnumTypeDto.java`、`EnumValueDto.java`、`CreateEnumTypeRequest.java`、`UpdateEnumTypeRequest.java`、`CreateEnumValueRequest.java` —— **删除**

### 3.5 DomainMeta 投影

**设计选择**（已与用户确认）：DomainMeta 直接使用 `entity.ObjectType` / `entity.AttributeType`，**不引入独立的 `model.domain.ObjectType`**。

**对应关系**：

| DomainMeta 元素（RFC-0018） | 数据来源 |
|------------------------------|----------|
| `DomainMeta` | `entity.DomainType` |
| `DomainMeta.EntityDef` ← **重命名为 `DomainMeta.ObjectTypeDef`** | `entity.ObjectType` |
| `DomainMeta.EntityField` ← **重命名为 `DomainMeta.AttributeDef`** | `entity.AttributeType` |
| `DomainMeta.ContextVar` | `entity.ObjectType`（kind=CLASS 且为 context 入口） |
| `DomainMeta.FunctionDef` | `entity.FunctionLib` |

```java
package com.orule.dsl;

import com.orule.common.entity.DomainType;
import com.orule.common.entity.ObjectType;
import com.orule.common.entity.AttributeType;
import com.orule.common.entity.FunctionLib;

import java.util.List;

/**
 * SimpleTS 编译期使用的领域元数据。
 * RFC-0032 修订：
 * - 直接复用 entity.*（保留 JPA 依赖，记技术债 §3.8）
 * - EntityDef → ObjectTypeDef；EntityField → AttributeDef（命名统一）
 */
public record DomainMeta(
    DomainType domain,
    List<ObjectTypeDef> objects,
    List<FunctionDef> functions
) {
    public record ObjectTypeDef(
        ObjectType object,                         // 直接使用 entity
        List<AttributeDef> attributes
    ) {}

    public record AttributeDef(
        AttributeType attribute,                  // 直接使用 entity
        com.orule.common.model.type.Type type     // 由 Service 端根据 3 列组装
    ) {}

    public record ContextVar(
        String name,
        ObjectType object                         // context 入口的 ObjectType
    ) {}

    public record FunctionDef(
        FunctionLib function,
        com.orule.common.model.type.FunctionSignature signature
    ) {}
}
```

**Type 组装工厂**：

```java
package com.orule.dsl;

import com.orule.common.entity.AttributeType;
import com.orule.common.entity.ObjectType;
import com.orule.common.model.type.*;

/**
 * 将 AttributeType 的 3 列结构组装成完整 Type 树。
 * RFC-0032 §3.5 新增。
 */
public final class TypeFactory {

    private TypeFactory() {}

    /**
     * 根据 attribute 的 data_type + sub_data_type_program_code(_2) 构造 Type。
     * list/map 的 elementType / keyType / valueType 也递归构造。
     */
    public static Type buildType(AttributeType attr, java.util.Map<String, ObjectType> objectsByCode) {
        return switch (attr.getDataType()) {
            case "primitive" -> new PrimitiveType(attr.getSubDataTypeProgramCode());
            case "object"    -> new ObjectRef(attr.getSubDataTypeProgramCode());
            case "list"      -> buildList(attr.getSubDataTypeProgramCode(), objectsByCode);
            case "map"       -> buildMap(attr.getSubDataTypeProgramCode(), attr.getSubDataTypeProgramCode2(), objectsByCode);
            default -> throw new IllegalStateException("Unknown data_type: " + attr.getDataType());
        };
    }

    private static Type buildList(String programCode, java.util.Map<String, ObjectType> map) {
        // 可能是 primitive.name 或 object.program_code
        if (isPrimitive(programCode)) return new ListType(new PrimitiveType(programCode));
        return new ListType(new ObjectRef(programCode));
    }

    private static Type buildMap(String keyCode, String valueCode, java.util.Map<String, ObjectType> map) {
        // key 通常是 primitive
        if (!isPrimitive(keyCode)) throw new IllegalStateException("Map key must be primitive, got: " + keyCode);
        Type keyType = new PrimitiveType(keyCode);
        Type valueType;
        if (isPrimitive(valueCode)) valueType = new PrimitiveType(valueCode);
        else valueType = new ObjectRef(valueCode);
        return new MapType(keyType, valueType);
    }

    private static boolean isPrimitive(String name) {
        return "string".equals(name) || "number".equals(name) || "boolean".equals(name) || "date".equals(name);
    }
}
```

### 3.6 enum 引用语义

**SimpleTS 语法**：保持 `CustomerTier.VIP` 不变（已与用户确认）。

```ts
if (customer.tier == CustomerTier.VIP) {
    order.discount = 30
}
```

**编译期校验**（RFC-0018 `FieldValidator` 修订）：

```java
// RFC-0032 §3.6 修订
void validateEnumRef(ObjectTypeDef enumObject, String valueCode) {
    if (enumObject.object().getKind() != ObjectType.Kind.ENUM) {
        throw new TssCompileError("ENUM_REF_NOT_ENUM",
            "'%s' 不是 enum 类型", enumObject.object().getProgramCode());
    }
    boolean found = enumObject.object().getEnumValues().stream()
        .anyMatch(v -> v.code().equals(valueCode));
    if (!found) {
        throw new TssCompileError("ENUM_VALUE_NOT_FOUND",
            "enum '%s' 中没有值 '%s'", enumObject.object().getProgramCode(), valueCode);
    }
}
```

**Groovy codegen**（RFC-0019 §3.4 修订）：

```java
// RFC-0032 修订：枚举提取路径变化
// 旧（RFC-0031）：遍历 entity.fields，提取 type.kind === 'enum' 的 EnumType.values
// 新（RFC-0032）：遍历所有 kind=ENUM 的 ObjectType，提取 enumValues
private String generateEnumBlock(DomainMeta meta) {
    StringBuilder sb = new StringBuilder();
    for (ObjectTypeDef obj : meta.objects()) {
        if (obj.object().getKind() == ObjectType.Kind.ENUM) {
            sb.append("enum ").append(obj.object().getProgramCode()).append(" { ");
            sb.append(obj.object().getEnumValues().stream()
                .map(ObjectType.EnumValue::code)
                .collect(Collectors.joining(", ")));
            sb.append(" }\n\n");
        }
    }
    return sb.toString();
}
```

**优势**：同一个 `CustomerTier` enum ObjectType，无论被多少 attribute 引用，Groovy 仅生成一份 enum 定义。

### 3.7 seed 数据修订

```sql
-- V5: 种子数据
-- RFC-0032 §3.7 重构版：CustomerTier 是 ObjectType(kind=ENUM)，不再内联到 attribute

INSERT INTO domain_type (id, program_code, name, description, owner_code) VALUES
  ('dom-order', 'ORDER', 'ORDER Domain', 'Order related rules', 'system'),
  ('dom-customer', 'CUSTOMER', 'CUSTOMER Domain', 'Customer related rules', 'system');

-- CustomerTier：ObjectType(kind=ENUM)
INSERT INTO object_type (id, domain_id, program_code, name, kind, enum_values) VALUES
  ('obj-customer-tier', 'dom-customer', 'CustomerTier', 'Customer Tier', 'ENUM',
   JSON_ARRAY(
     JSON_OBJECT('code', 'VIP',    'label', 'VIP 客户',    'sortOrder', 1),
     JSON_OBJECT('code', 'GOLD',   'label', '金卡客户',    'sortOrder', 2),
     JSON_OBJECT('code', 'SILVER', 'label', '银卡客户',    'sortOrder', 3),
     JSON_OBJECT('code', 'BRONZE', 'label', '普通客户',    'sortOrder', 4)
   ));

-- Customer：ObjectType(kind=CLASS)
INSERT INTO object_type (id, domain_id, program_code, name, kind) VALUES
  ('obj-customer', 'dom-customer', 'Customer', 'Customer Entity', 'CLASS'),
  ('obj-order',    'dom-order',    'Order',    'Order Entity',    'CLASS');

-- Customer attributes：tier 字段引用 CustomerTier（通过 program_code）
INSERT INTO attribute_type (id, object_id, program_code, name, data_type, sub_data_type_program_code, is_required) VALUES
  ('attr-cust-id',   'obj-customer', 'id',   'Customer ID',   'primitive', 'string',  TRUE),
  ('attr-cust-name', 'obj-customer', 'name', 'Customer Name', 'primitive', 'string',  TRUE),
  ('attr-cust-tier', 'obj-customer', 'tier', 'Customer Tier', 'object',    'CustomerTier', TRUE);

-- Order attributes
INSERT INTO attribute_type (id, object_id, program_code, name, data_type, sub_data_type_program_code, is_required) VALUES
  ('attr-order-id',       'obj-order', 'id',           'Order ID',       'primitive', 'string',  TRUE),
  ('attr-order-total',    'obj-order', 'totalAmount',  'Total Amount',   'primitive', 'number',  TRUE),
  ('attr-order-discount', 'obj-order', 'discount',     'Discount',       'primitive', 'number',  FALSE),
  ('attr-order-prices',   'obj-order', 'itemPrices',   'Item Prices',    'list',      'number',  FALSE),
  ('attr-order-tax',      'obj-order', 'taxBreakdown', 'Tax Breakdown',  'map',       'string',  FALSE);

-- taxBreakdown 的 value 类型是 number，需要建单独的 attribute 行存储 sub2
--（实际方案：sub2 也通过单独 attribute 行存，但 map 形式只表达 value 的 primitive
--  —— 若 value 是 object，需在 Order 增加一个 map-value 类型的 ObjectType 引用，MVP 不支持）

INSERT INTO function_lib (id, program_code, name, signature, description, category, is_builtin) VALUES
  ('func-max', 'max', 'Maximum',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')),
   'Return the larger of two values', 'math', TRUE),
  -- ...（其余 max/min/abs/round/upper/lower/contains/now/days 与 RFC-0031 相同，省略）
  ('func-now', 'now', 'Current Time',
   JSON_OBJECT('params', JSON_ARRAY(),
               'return', JSON_OBJECT('kind', 'primitive', 'name', 'date')),
   'Return current timestamp', 'date', TRUE);
```

### 3.8 技术债（TODO）

下列 3 项暂不实现，记入遗留问题：

| TODO | 描述 | 影响 | 触发条件 |
|------|------|------|----------|
| **TD-001：JPA 依赖解耦** | DomainMeta 直接使用 `entity.ObjectType` / `entity.AttributeType`，违反分层原则。SimpleTS 编译器 / Groovy codegen 通过 transitive 依赖引入 Hibernate。 | SimpleTS 模块和 Groovy 模块本不该依赖 JPA，但目前必须依赖。 | 当 SimpleTS 模块被独立复用（如作为 SDK 提供给外部）时启动 |
| **TD-002：FunctionLib signature 字段扁平化** | `function_lib.signature` 仍是 JSON 列（沿用 RFC-0031），与 `attribute_type` 的 3 列设计不一致。 | 同一种 Type 在两个表中存储形态不同。 | 当 MVP 稳定后重构 |
| **TD-003：map.value 为 object 的支持** | 当前 `sub_data_type_program_code2` 只能表达 primitive value；若 map 的 value 是 object（如 `map<string, Customer>`），需要增加嵌套表达。 | MVP 限制业务建模。 | RFC-0034（运行时类型检查）一并讨论 |

---

## 4. 备选方案

### 备选 A：保留 RFC-0031 的 5 个 Variant

- 优点：维持 RFC-0031 设计不变。
- 缺点：Type variant 仍多 1 个；enum 不能跨 attribute 复用；attribute_type.type_json 仍是 JSON 列。

### 备选 B：enum 完全独立表（回到 RFC-0030 之前）

- 优点：enum 单一来源，最强复用性。
- 缺点：3 张 enum 相关表，CRUD 复杂；4 个 enum Controller/Service；与 RFC-0031 简化方向冲突。

### 备选 C（采纳）：enum 是 ObjectType 的特殊形态

- 优点：4 个 variant；enum 跨 attribute 复用；attribute_type 关系化；命名统一。
- 缺点：entity.ObjectType 既承载 CLASS 又承载 ENUM，需新增 kind 字段；TypeFactory 增加递归构造复杂度（可接受）。

---

## 5. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| **TD-001 JPA 依赖跨层** | 🟡 中 | 文档记录；通过 feature flag 或独立模块化拆分待 MVP 后处理 |
| **enum 命名冲突** | 🟢 低 | object_type 表已有 `uk_object_domain_program_code` 唯一约束；同 domain 下 CustomerTier 只能存在一份 |
| **TypeFactory 递归构造性能** | 🟢 低 | Type 树深度 ≤ 2（MVP 约束），构造 O(1) |
| **JSON 列（enum_values）查询效率** | 🟡 中 | MVP 数据量小（百级 ObjectType）；MySQL 5.7+ JSON 列有索引支持；后续若热点查询可加 `idx_object_kind` |
| **map value 是 object 的场景** | 🟠 中-高 | MVP 不支持；记 TD-003，文档明示 |
| **已有种子数据** | 🟢 低 | MVP 尚未上线，V1 重写即可 |

---

## 6. 实施步骤

| Step | 内容 | 工作量 |
|------|------|--------|
| 1 | Type 模型：删除 `EnumType`；将 `model.type.ObjectType` 重命名为 `ObjectRef` | 0.1d |
| 2 | 重写 V1__init_metadata.sql（attribute_type 拆 3 列；object_type 加 kind/enum_values；code → program_code） | 0.4d |
| 3 | 修改 V5__init_seed.sql（CustomerTier 升格为 ObjectType(kind=ENUM)；attribute_type 引用方式变化） | 0.3d |
| 4 | 修改 entity：`ObjectType.java`（加 kind + enumValues + OneToMany attributes）；`AttributeType.java`（3 列 + program_code）；`FunctionLib/DomainType` 改 programCode | 0.4d |
| 5 | 修改 DTO：`ObjectTypeDto/CreateObjectTypeRequest`；`AttributeTypeDto/Create/UpdateAttributeTypeRequest`（加 server-side Type 组装） | 0.3d |
| 6 | 删除 enum 相关 DTO：`EnumTypeDto/CreateEnumTypeRequest/UpdateEnumTypeRequest/CreateEnumValueRequest` 等 | 0.1d |
| 7 | 新增 `TypeFactory.java`：根据 3 列 + object_type 表构造 Type 树 | 0.2d |
| 8 | 修改 `MetadataService.java`：CRUD 逻辑适配新 schema；CRUD 时同步组装/校验 Type 树 | 0.3d |
| 9 | 修改 `DomainMeta.java`：EntityDef/EntityField → ObjectTypeDef/AttributeDef；直接持有 entity.* | 0.2d |
| 10 | 修改 RFC-0018 `FieldValidator.java`：enum 校验改为查 `object_type(kind=ENUM).enum_values` | 0.1d |
| 11 | 修改 RFC-0019 `GroovyCodeGen.java`：枚举提取路径改为遍历所有 kind=ENUM 的 ObjectType | 0.1d |
| 12 | 修改 `MetadataApiIntegrationTest.java`：移除 enum 流程；新增 enum-as-object-type 流程 | 0.3d |
| 13 | 单元测试：TypeFactory 各种组合；enum 引用校验；JPA entity 关系 | 0.3d |
| 14 | 集成测试：完整 CRUD 链路 + SimpleTS 编译 + Groovy codegen | 0.2d |
| **总计** | | **3.3d** |

---

## 7. 影响的 RFC / ADR / 文档

### 7.1 直接修改

- **RFC-0014**：数据库迁移基线 → V1 部分章节重写（attribute_type 拆 3 列；object_type 加 kind/enum_values；code → program_code）
- **RFC-0015**：元数据域 CRUD API → ObjectTypeDto / AttributeTypeDto 调整；删除 enum 相关 API
- **RFC-0018**：SimpleTS 解析器 → DomainMeta 改用 entity.*；FieldValidator enum 校验路径变化
- **RFC-0019**：SimpleTS → Groovy 代码生成器 → 枚举提取路径变化
- **RFC-0031**：Type 系统重构 → 标注本 RFC 为其"修订 2"（本 RFC 在 0031 基础上调整）

### 7.2 直接新增

- **RFC-0032**：本文档
- **ADR-012**：enum 视为 ObjectType 的特殊形态

### 7.3 同步更新

- `docs/04-数据模型.md` §4.2 attribute_type 表说明 + §4.3 object_type 表说明
- `docs/dsl/SimpleTS.md` §7 DomainMeta 形态（EntityDef → ObjectTypeDef；EntityField → AttributeDef）
- `docs/rfcs/README.md` 索引新增 RFC-0032；TODO 列表移除 RFC-0033（enum 全局唯一字典）—— 本 RFC 通过 ObjectType(kind=ENUM) 已实现 enum 复用

---

## 8. 决策日志

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-12 | enum 视为 ObjectType 的特殊形态（kind=ENUM） | 符合主流语言定义；Type variant 从 5 减为 4；enum 可跨 attribute 复用 |
| 2026-09-12 | 删 `model.type.EnumType`；`model.type.ObjectType` 重命名为 `ObjectRef` | 命名与实体 ObjectType 区分；Type variant 只保留引用 |
| 2026-09-12 | attribute_type 拆为 3 列（data_type + sub_data_type_program_code + sub_data_type_program_code2） | 摆脱 JSON 列；关系化更彻底；可 SQL JOIN |
| 2026-09-12 | enum_values 用 JSON 列存放在 object_type 表 | 与"enum 是 ObjectType 的一种"心智模型一致；MVP 数据量小 |
| 2026-09-12 | `code` → `programCode` 统一重命名 | 语义清晰（强调"可编程代码"）；与 SimpleTS 中的 name 区分 |
| 2026-09-12 | DomainMeta 直接使用 entity.* | MVP 简化；记技术债 TD-001，未来解耦 |
| 2026-09-12 | EntityDef → ObjectTypeDef；EntityField → AttributeDef | 命名与 entity 层对齐 |
| 2026-09-12 | 保留 SimpleTS 中 `CustomerTier.VIP` 引用语法 | 编译期类型校验；语义直观 |
| 2026-09-12 | MVP 不支持 map value 为 object 的场景 | 复杂度控制；记 TD-003 |
| 2026-09-12 | function_lib.signature 仍用 JSON 列 | 与 attribute_type 设计不一致；记 TD-002 |
| 2026-09-12 | RFC-0032 状态推进 APPROVED（基于已落地的 entity + TypeFactory 实施） | Phase 1 实施完成，5 测试类 GREEN（commit `f8e6c62`） |
| 2026-09-12 | Phase 1 实施已完成：Type 系统 4 Variant（删 EnumType / ObjectType→ObjectRef）；entity 加 Kind+enumValues；AttributeType 3 列；TypeFactory | 见 §10 实施状态 |

---

## 9. 未来扩展（不在本 RFC 范围）

- TD-001 解决：SimpleTS 编译器 / Groovy codegen 与 JPA 解耦（独立模块化）
- TD-002 解决：function_lib.signature 与 attribute_type 扁平化对齐
- TD-003 解决：map value 为 object 的支持（需要嵌套 attribute 表达）
- RFC-0034：运行时类型检查与算法 API

---

## 10. 实施状态（RFC 进度跟踪）

### 10.1 Phase 1 已完成（2026-09-12, commit `f8e6c62`）

| Step | 内容 | 状态 |
|------|------|------|
| 1 | Type 模型：删 `EnumType`；`model.type.ObjectType` → `ObjectRef` | ✅ |
| 4 | entity：`ObjectType` 加 Kind+enumValues；`AttributeType` 拆 3 列；`DomainType`/`FunctionLib` 改 programCode | ✅ |
| 7 | 新增 `TypeFactory`：根据 3 列组装 Type 树 | ✅ |
| 13 | 单元测试：TypeFactory + entity 字段 + 集成场景 | ✅ |

### 10.2 测试覆盖（29+ 用例，5 测试类）

| 测试类 | 用例数 | 覆盖 |
|--------|--------|------|
| `TypeVariantsTest` | 8 | Type 系统 4 Variant 校验 |
| `ObjectTypeEntityTest` | 7 | entity.ObjectType 字段（Kind / enumValues / attributes 默认值） |
| `AttributeTypeEntityTest` | 6 | entity.AttributeType 3 列 + 反射校验 typeJson 字段已删 |
| `TypeFactoryTest` | 10 | TypeFactory 各种组合（含错误边界：未知 dataType / map 缺 sub2 / map key 非 primitive） |
| `Rfc0032ScenariosTest` | 2 | RFC-0032 关键场景（enum 跨 attribute 复用 / Order 含 list/map/object） |

### 10.3 Phase 2 待实施

| Step | 内容 | 工作量 |
|------|------|--------|
| 2 | V1__init_metadata.sql 重写（attribute_type 拆 3 列；object_type 加 kind/enum_values；code → program_code） | 0.4d |
| 3 | V5__init_seed.sql 修改（CustomerTier 升格 ObjectType kind=ENUM） | 0.3d |
| 5 | DTO 改造：`ObjectTypeDto` / `AttributeTypeDto` / `CreateObjectTypeRequest` / `UpdateAttributeTypeRequest`（DTO 当前未建，可与 step 6 合并） | 0.3d |
| 6 | 删除 enum 相关 DTO/Service/Controller/Repository | 0.1d |
| 8 | `MetadataService.java` CRUD 逻辑适配新 schema（Service 当前未建，需新建） | 0.3d |
| 9 | `DomainMeta.java` 改造：EntityDef/EntityField → ObjectTypeDef/AttributeDef；直接持有 entity.* | 0.2d |
| 10 | RFC-0018 `FieldValidator.java` enum 引用校验路径变化 | 0.1d |
| 11 | RFC-0019 `GroovyCodeGen.java` 枚举提取路径变化 | 0.1d |
| 12 | 集成测试 `MetadataApiIntegrationTest` | 0.3d |
| 14 | 端到端集成测试（CRUD + SimpleTS 编译 + Groovy codegen） | 0.2d |
| **总计** | | **2.3d** |

### 10.4 实施原则

- **TDD 严格执行**：每个切片先 RED（写失败测试）→ GREEN（最小代码）→ REFACTOR
- **下游兼容**：每次提交后必须跑 `mvn -pl packages/orule-server,packages/orule-runtime -am compile` 验证下游无遗留引用
- **测试先于 SQL**：DB migration 用 SQL 测试（H2 / Testcontainers）覆盖，关键索引/约束单测验证
- **每 commit 独立可回滚**：Phase 2 内部按 Step 拆 commit
