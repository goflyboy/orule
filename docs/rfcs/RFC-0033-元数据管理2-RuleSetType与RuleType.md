# RFC-0033: 元数据管理（2）— RuleSetType 与 RuleType（RuleType 元数据层）

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：4d · **阶段**：S1/S2 增强
> **作者**：架构组 · **日期**：2026-09-12
> **前置 RFC**：[RFC-0015 元数据 CRUD API](RFC-0015-元数据域CRUD-API.md)（SUPERSEDED）、
> [RFC-0031 Type 系统重构](RFC-0031-Type系统重构.md)、
> [RFC-0032 ObjectType 枚举化 + Type 系统简化](RFC-0032-ObjectType枚举化与Type系统简化.md)、
> [RFC-0018 SimpleTS 解析器（含 RFC-0018-bis）](RFC-0018-SimpleTS解析器.md)
> **关联 RFC**：[RFC-0016 规则域 + 状态机 API](RFC-0016-规则域状态机API.md)、
> [RFC-0014 数据库 Flyway 迁移基线](RFC-0014-数据库Flyway迁移基线.md)、
> [RFC-0019 SimpleTS → Groovy](RFC-0019-SimpleTS转Groovy代码生成器.md)

---

## 1. 摘要

在 [RFC-0015](RFC-0015-元数据域CRUD-API.md) / [RFC-0031](RFC-0031-Type系统重构.md) /
[RFC-0032](RFC-0032-ObjectType枚举化与Type系统简化.md) 已建立的"ObjectType / AttributeType / FuntionType / DomainType"基础上，
**新增 `RuleSetType` 与 `RuleType` 两个元数据实体**，补齐"规则类型 / 规则集类型"层。

| 新增实体 | 含义 | 与现有关系 |
|---------|------|-----------|
| **RuleSetType** | 规则集**类型定义**（元数据层，无状态、无 owner_code）| 与 `DomainType` 1:N；与现有 `RuleSet` 实例层平级 |
| **RuleType** | 规则**类型模板**（空函数定义：arguments + returnType + functionTypes）| 隶属 RuleSetType；与现有 `Rule` 实例层平级 |
| **FuntionType**（重命名自 FunctionLib）| 函数 SDK 类型 | V7 重命名 `function_lib` → `funtion_types`，新增 RuleSetType 关联 |

**核心变更**：
1. **三层模型确立**：`RuleSetType / RuleType`（元数据）→ `RuleSet / Rule`（实例）→ `RuleSetArtifact`（制品）
2. **V7 Flyway 迁移**：新增 2 张主表（`rule_set_type / rule_type`），FuntionType 重命名 + 新增 RuleSetType 关联列
3. **RuleType.arguments / returnType / functionTypes / excludeFunctionTypes / validatable 全部 JSON 拍平**进 `rule_type.arguments` 列，
   不建独立表（符合"不搞太多表"的诉求）
4. **完整 REST API**：RuleSetType CRUD + RuleType 嵌套提交
5. **不变式保证**：`RuleType.arguments[*].objectType ∈ enclosing RuleSetType.objectTypes` 等 4 条不变量

---

## 2. 动机

### 2.1 RFC-0015 / RFC-0031 / RFC-0032 已覆盖的事

```text
DomainType          ← 元数据：领域定义
├─ ObjectType       ← 元数据：对象类型（CLASS / ENUM / VOID）
│  └─ AttributeType ← 元数据：属性
├─ FuntionType      ← 元数据：函数 SDK 类型（原 FunctionLib）
└─ Rule             ← 实例：具体规则
   ├─ RuleVersion   ← 实例：版本快照
   └─ RuleSet       ← 实例：具体规则集（含 status / owner_code）
      └─ RuleSetArtifact ← 制品：编译产物
```

### 2.2 缺什么

RFC-0018-bis 提出"RuleType 作为空函数"概念，但**实体层、CRUD API、DDL 都没有 RFC 设计**，是个设计空白。

具体缺失：
1. **"规则集类型定义"概念缺失**：`RuleSet` 是实例（含 status / owner_code），但"规则集类型"（如"订单规则集"、"风控规则集"）作为模板/类型层没有独立表达
2. **RuleType 入参出参没有落库形式**：当前 `MemberAccess.root` 校验需要查 `RuleType.arguments`，但没有表存它
3. **FuntionType 与 RuleSetType / RuleType 的关系不清**：是 1:N 还是 N:N？FK 列放哪一侧？
4. **REST 端点缺失**：无法通过 API 创建 RuleSetType / RuleType，只能通过 SQL

### 2.3 为什么是 RFC-0033 而非追加在 RFC-0018 / RFC-0015

| 方案 | 不选的理由 |
|------|----------|
| 追加在 RFC-0018（§3.11 已有的"实体设计"）| RFC-0018 是 **DSL 解析器**设计；元数据 CRUD + DDL 不是它的职责，会让 RFC 越写越肥，违反单一职责 |
| 修订 RFC-0015（SUPERSEDED 复活）| RFC-0015 已被 RFC-0031 / 0032 部分覆盖；再叠加 RFC-0033 会破坏"已作废 RFC 不再维护"原则 |
| **新增 RFC-0033（独立承载元数据层 2 设计）** | ✅ 与 RFC-0015 / 0031 / 0032 一脉相承；保持"每 RFC 一个职责"；DDL / 实体 / API 集中管理 |

---

## 3. 详细设计

### 3.1 三层模型（核心架构）

```
┌─────────────────────────────────────────────────────────────────┐
│                     元数据层（Meta-Layer）                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  DomainType                                                       │
│   └─ ObjectType  (kind: CLASS | ENUM)                             │
│      └─ AttributeType                                              │
│   └─ RuleSetType ◀── NEW（领域内"高内聚的规则集合"类型模板）          │
│      └─ RuleType  ◀── NEW（规则空函数：arguments/returnType/...)    │
│      └─ ObjectType  ◀── RuleSetType 关联（其下可用对象集合）           │
│      └─ FuntionType ◀── RuleSetType 关联（SDK 全集）                │
│                                                                  │
│  FuntionType  ◀── 独立存在，供 RuleType.functionTypes 白名单引用      │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                     实例层（Instance-Layer）                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  RuleSet    ◀── 实例（具体规则集）                                  │
│   └─ RuleSetArtifact（编译制品）                                   │
│  RuleSetType.code === RuleSet.type_code（弱关联）                   │
│                                                                  │
│  Rule      ◀── 实例（具体规则）                                    │
│   └─ RuleVersion（版本快照）                                       │
│   └─ RuleType.code === Rule.type_code（弱关联）                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**关键区别**：
- **RuleSetType** = 元数据定义（无 status、无 owner_code、无 createdAt 业务状态）
- **RuleSet** = 实例（含 status、owner_code、创建人、生命周期）
- **RuleSetType.code 与 RuleSet.type_code 通过字符串弱关联**（不强 FK，方便 RuleSetType 升级不影响现存 RuleSet 实例）

### 3.2 RuleSetType（元数据实体 — 新增）

```java
package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RuleSetType：规则集类型定义（RFC-0033 §3.2）。
 *
 * <p>业务含义：领域下"高内聚的规则集合"的类型模板。
 * 运行时一起打包/一起执行（计算引擎可调整执行顺序），业务概念高内聚，便于业务人员管理。
 *
 * <p>主要定义：
 * <ol>
 *   <li>本类型使用的领域对象集合（{@code objectTypes}）；</li>
 *   <li>本类型可用的函数 SDK 全集（{@code functionTypes}）；</li>
 *   <li>本类型下的具体规则类型（{@code ruleTypes}）。</li>
 * </ol>
 *
 * <p>与 {@link RuleSet} 实例层的区别：
 * <ul>
 *   <li>RuleSetType = 类型定义（无 status、无 owner_code 业务字段）</li>
 *   <li>RuleSet    = 实例（含 status / owner_code / 生命周期）</li>
 * </ul>
 */
@Entity
@Table(name = "rule_set_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_rule_set_type_program_code",
                      columnNames = {"domain_id", "code"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RuleSetType {

    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /** 关联 DomainType（领域代表） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_id", nullable = false)
    private DomainType domain;

    /** 类型 code（业务语义，例如"ORDER_DISCOUNT"） */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 本类型使用的领域对象集合。
     * 不变式：M:N 关联表 ({@code rule_set_type_object_type})
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "rule_set_type_object_type",
        joinColumns = @JoinColumn(name = "rule_set_type_id"),
        inverseJoinColumns = @JoinColumn(name = "object_type_id")
    )
    @Builder.Default
    private List<ObjectType> objectTypes = new ArrayList<>();

    /**
     * 本类型可用的函数 SDK 全集。
     * 不变式：M:N 关联表 ({@code rule_set_type_funtion_type})
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "rule_set_type_funtion_type",
        joinColumns = @JoinColumn(name = "rule_set_type_id"),
        inverseJoinColumns = @JoinColumn(name = "funtion_type_id")
    )
    @Builder.Default
    private List<FuntionType> functionTypes = new ArrayList<>();

    /**
     * 本类型下的具体规则类型。
     * 详见 {@link RuleType}。
     */
    @OneToMany(mappedBy = "ruleSetType", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RuleType> ruleTypes = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

### 3.3 RuleType（元数据实体 — 新增）

```java
package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RuleType：规则类型（RFC-0033 §3.3）。
 *
 * <p>本质是一个空函数模板，业务人员填写的 SimpleTS 源码 = 函数体 = 规则。
 *
 * <p>JSON 拍平说明：本 RFC 将 arguments / returnType / functionTypes / excludeFunctionTypes
 * 全部 JSON 化进 {@code arguments} JSON 列（不建独立小表），符合"不搞太多表"诉求。
 * 拍平后的 JSON schema 见 {@link RuleTypeArguments}。
 */
@Entity
@Table(name = "rule_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_rule_type_code", columnNames = "code")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RuleType {

    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_set_type_id", nullable = false)
    private RuleSetType ruleSetType;

    /** 规则类型 code（业务语义，例如"ORDER_DISCOUNT_VALIDATE"） */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * validatable（默认 true）。为 false 时跳过 RFC-0018 §3.6 字段校验（实验豁免）。
     */
    @Column(name = "is_validatable", nullable = false)
    @Builder.Default
    private Boolean validatable = true;

    /**
     * 入参/出参/白名单的 JSON 拍平。
     * 单条 RuleType 仅一份完整的"空函数定义"。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", nullable = false, columnDefinition = "JSON")
    private RuleTypeArguments arguments;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

/**
 * RuleType 的"空函数"完整定义（JSON 拍平 schema）。
 *
 * <p>拍平策略：单条 RuleType 用一份 JSON 描述 arguments / returnType /
 * functionTypes / excludeFunctionTypes，避免 4 张小表的关联开销。
 */
public record RuleTypeArguments(
        List<ArgumentType> arguments,
        ReturnType returnType,
        List<String> functionTypeCodes,    // 引用 FuntionType.programCode（字符串，不 FK）
        List<String> excludeFunctionTypeCodes   // 同上
) {

    /**
     * 入参定义（RFC-0033 §3.4）。
     * objectType 通过程序代码引用 ObjectType.programCode（FK 在 Entity 关系层维护）。
     */
    public record ArgumentType(
            String programCode,   // 参数名（业务语义，对应 SimpleTS 中的入口变量名）
            String objectTypeCode, // → ObjectType.programCode（强引用，保持类型一致性）
            boolean nullable,
            boolean writable,
            int sortOrder
    ) {}

    /**
     * 出参定义（RFC-0033 §3.5）。
     * objectTypeCode = null 表示 VOID（ObjectType.Kind.VOID，内置）。
     */
    public record ReturnType(
            String objectTypeCode, // null = VOID
            boolean nullable
    ) {}
}
```

### 3.4 完整 ER 图

```
┌────────────────┐
│  domain_type   │  (RFC-0015/0032：领域代表)
└───────┬────────┘
        │ 1:N
        ▼
┌─────────────────────────┐         ┌─────────────────────┐
│    rule_set_type         │◀────────│   object_type       │
│  (RFC-0033 §3.2 新)       │ M:N    │  (RFC-0032：领域对象) │
└────┬──────┬──────┬───────┘         └─────────────────────┘
     │      │      │
     │ 1:N  │      │ M:N
     ▼      │      ▼
┌──────────┐│   ┌─────────────────────────────┐
│rule_type ││   │    funtion_type              │
│(§3.3 新) ││   │  (V7 重命名自 function_lib)   │
└──────────┘│   └─────────────────────────────┘
            │
            │ 弱关联（字符串）
            ▼
      ┌─────────────────────────────┐
      │  rule / rule_set（实例层）    │   (RFC-0016，已存在)
      │  rule_set.type_code ─┐       │
      │  rule.type_code    ──┤       │
      │                     │       │
      │  通过字符串程序代码  │       │
      │  关联到上述元数据    │       │
      └─────────────────────┴───────┘

中间表：
- rule_set_type_object_type  (RuleSetType M:N ObjectType)
- rule_set_type_funtion_type (RuleSetType M:N FuntionType)
```

### 3.5 DDL（Flyway V7）

```sql
-- V7__rule_set_type_and_rule_type.sql
-- 新增元数据层 2：RuleSetType + RuleType
-- FuntionType 重命名自 FunctionLib（SQL 表名 function_lib 保持不变，
--   但 fk 列名调整以体现与 RuleSetType 的关系）

-- ============================================================
-- 1. RuleSetType（新增）
-- ============================================================
CREATE TABLE rule_set_type (
    id            VARCHAR(36)  NOT NULL,
    domain_id     VARCHAR(36)  NOT NULL,
    code          VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_set_type_domain_code (domain_id, code),
    CONSTRAINT fk_rule_set_type_domain
        FOREIGN KEY (domain_id) REFERENCES domain_type(id)
);

CREATE INDEX idx_rule_set_type_domain ON rule_set_type(domain_id);

-- ============================================================
-- 2. RuleSetType.ObjectTypes（M:N 中间表）
-- ============================================================
CREATE TABLE rule_set_type_object_type (
    rule_set_type_id  VARCHAR(36) NOT NULL,
    object_type_id    VARCHAR(36) NOT NULL,
    PRIMARY KEY (rule_set_type_id, object_type_id),
    CONSTRAINT fk_rsot_rs  FOREIGN KEY (rule_set_type_id) REFERENCES rule_set_type(id) ON DELETE CASCADE,
    CONSTRAINT fk_rsot_obj FOREIGN KEY (object_type_id)   REFERENCES object_type(id)  ON DELETE RESTRICT
);

-- ============================================================
-- 3. RuleSetType.FunctionTypes（M:N 中间表）
-- ============================================================
CREATE TABLE rule_set_type_funtion_type (
    rule_set_type_id  VARCHAR(36) NOT NULL,
    funtion_type_id   VARCHAR(36) NOT NULL,
    PRIMARY KEY (rule_set_type_id, funtion_type_id),
    CONSTRAINT fk_rsft_rs   FOREIGN KEY (rule_set_type_id) REFERENCES rule_set_type(id)     ON DELETE CASCADE,
    CONSTRAINT fk_rsft_func FOREIGN KEY (funtion_type_id)  REFERENCES function_lib(id)     ON DELETE RESTRICT
);

-- ============================================================
-- 4. RuleType（新增；arguments JSON 拍平，无中间表）
-- ============================================================
CREATE TABLE rule_type (
    id              VARCHAR(36)  NOT NULL,
    rule_set_type_id VARCHAR(36) NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    is_validatable  BOOLEAN      NOT NULL DEFAULT TRUE,
    -- arguments JSON 拍平：arguments[] / returnType / functionTypeCodes[] / excludeFunctionTypeCodes[]
    arguments       JSON         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_type_code (code),
    CONSTRAINT fk_rule_type_rs
        FOREIGN KEY (rule_set_type_id) REFERENCES rule_set_type(id) ON DELETE CASCADE,
    -- JSON 内 programCode 引用 ObjectType.programCode / FuntionType.programCode
    -- 在 application 层校验（不变式 §3.7），不在 DDL 层级强 FK
);

CREATE INDEX idx_rule_type_rs ON rule_type(rule_set_type_id);

-- ============================================================
-- 5. Rule / RuleSet 实例层弱关联（仅文档，不改 DDL）
-- ============================================================
-- 现有 rule_set 表有 type_code（如果已有）/ 没有则 V7 不加
-- 现有 rule 表保留 type_code 字段（如果已有）或 V7 加 ALTER
-- 弱关联：通过字符串程序代码在应用层校验类型存在
-- 见 §3.7 不变式 #5

-- ============================================================
-- 6. function_lib 表注释更新（rename 是 Java 层，SQL 表保留）
-- ============================================================
-- function_lib 表名保留（向后兼容）；表注释改为"funtion_type 表（V7 语义重命名）"
ALTER TABLE function_lib COMMENT = 'funtion_type (V7 重命名，类名层面 FuntionType)';

-- ============================================================
-- 7. V7 种子数据（RuleSetType 示例）
-- ============================================================
INSERT INTO rule_set_type (id, domain_id, code, name, description) VALUES
  ('rst-order',    'dom-order',    'ORDER_DISCOUNT', '订单满减规则集类型', '订单与顾客同时存在的规则场景'),
  ('rst-customer', 'dom-customer', 'CUSTOMER_TIER',  '客户分级规则集类型', '客户层级相关规则场景');

INSERT INTO rule_set_type_object_type (rule_set_type_id, object_type_id) VALUES
  ('rst-order',    'obj-customer'),
  ('rst-order',    'obj-order'),
  ('rst-order',    'obj-customer-tier'),
  ('rst-customer', 'obj-customer');

-- 顺手在 V7 内补一条 rule_type 演示数据（V1 已有的 INSERT INTO function_lib 等保持）
-- INSERT INTO rule_type (...) 在 metadata seed 时由 service 层写
```

### 3.6 REST API

#### RuleSetType 端点

```
GET    /api/v1/rule-set-types                       # 列表（支持 ?domainId=xxx）
GET    /api/v1/rule-set-types/{id}                  # 详情（含 ruleTypes / objectTypes / functionTypes）
POST   /api/v1/rule-set-types                       # 创建
PUT    /api/v1/rule-set-types/{id}                  # 更新
DELETE /api/v1/rule-set-types/{id}                  # 删除（cascade rule_types）
GET    /api/v1/rule-set-types/by-domain-code/{code} # 按 domain.code + ruleSetType.code 查
```

#### RuleType 嵌套资源

```
GET    /api/v1/rule-set-types/{rstId}/rule-types                    # 列某 RuleSetType 下的 RuleType
POST   /api/v1/rule-set-types/{rstId}/rule-types                    # 创建 RuleType
GET    /api/v1/rule-set-types/{rstId}/rule-types/{rtId}              # 详情
PUT    /api/v1/rule-set-types/{rstId}/rule-types/{rtId}             # 更新
DELETE /api/v1/rule-set-types/{rstId}/rule-types/{rtId}             # 删除
```

#### FuntionType 端点（V7 同步）

```
GET    /api/v1/funtion-types                  # 列表（原 /function-libs 已重命名）
GET    /api/v1/funtion-types/{id}
POST   /api/v1/funtion-types
PUT    /api/v1/funtion-types/{id}
DELETE /api/v1/funtion-types/{id}
GET    /api/v1/funtion-types/{id}/rule-set-types  # 反向：哪些 RuleSetType 用到我
```

### 3.7 不变式（由 MetadataService 拼接层保证）

1. **RuleType.arguments[*].objectTypeCode ∈ enclosing RuleSetType.objectTypes[*].programCode**
   （入参引用必须在 RuleSetType 范围内）
2. **RuleType.returnType.objectTypeCode ∈ enclosing RuleSetType.objectTypes[*].programCode ∪ {VOID}**
   （出参引用同上，VOID 单独允许）
3. **RuleType.functionTypeCodes ⊆ enclosing RuleSetType.functionTypes[*].programCode**
   （白名单 SDK 引用必须在 RuleSetType 范围内）
4. **RuleType.functionTypeCodes ∩ RuleType.excludeFunctionTypeCodes = ∅**
   （不变量，加 DSL 编译期 warning）
5. **RuleSet.type_code（实例层） ∈ RuleSetType.code ∪ {legacy}**
   （允许实例强引用类型不存在——升级类型不影响旧实例；但创建时校验）
6. **Rule.type_code（实例层） ∈ RuleType.code（在该 RuleSet 对应的 RuleSetType 范围内）∪ {legacy}**

校验时机：
- 创建 / 更新时 MetadataService 强制断言
- 删除 RuleSetType 时，关联 Rule 报错而非强 cascade
- 软删除 / 弃用另起机制（不在本 RFC 范围）

### 3.8 现有 entity 调整

| 现有 entity | V7 调整 |
|-------------|---------|
| `RuleSet` | 不变（实例层，与 RuleSetType 通过 type_code 弱关联） |
| `Rule` | 不变（实例层，与 RuleType 通过 type_code 弱关联） |
| `FuntionType`（原 FunctionLib）| 类名变更；表名 `function_lib` 保留；新增 `ManyToMany ruleSetTypes` 反向引用 |
| `RuleSetType` | 新增 |
| `RuleType` | 新增 |
| `RuleTypeArguments`（嵌套 record）| 新增 |

### 3.9 RFC-0018-bis 同步修订

| 修订项 | 内容 |
|--------|------|
| §3.3 删除说明 | 增加 RFC-0033 引用 |
| §3.3.1 仅声明 RuleType 4 个字段的作用 | DSL 编译期只读 `arguments / returnType / functionTypes / validatable` |
| §3.3.2 占位 | "已迁移到 RFC-0033" |
| 关联 | §8 增加 RFC-0033 条目 |

---

## 4. 影响面

### 4.1 新增

- 2 张数据库表：`rule_set_type / rule_type`
- 2 张 M:N 中间表：`rule_set_type_object_type / rule_set_type_funtion_type`
- 4 个新实体（Java + JPA）：`RuleSetType / RuleType / ArgumentType / ReturnType`（后两者作为嵌套 record）
- 1 个重命名实体：`FunctionLib.java` → `FuntionType.java`（已完成于 RFC-0018-bis 提交 1590cfc）
- 6 个新 REST 端点（RuleSetType 6 + RuleType 嵌套 5）
- FuntionType 端点同步重命名（`/function-libs` → `/funtion-types`，已完成于 RFC-0018-bis 提交 1590cfc）

### 4.2 不改动

- `RuleSet / Rule / RuleVersion / RuleSetArtifact` 实例层（弱关联，不强 FK）
- `DomainType / ObjectType / AttributeType / EnumValue`（仅被新增实体 FK 引用）
- RFC-0019 / RFC-0020 的下游逻辑（已通过 RuleType.arguments JSON 形式自然适配）

### 4.3 依赖项

- **RFC-0014**：`V1__init_metadata.sql` 已存在 `object_type / function_lib / attribute_type`；V7 在 V1 基础上 ADD（不需要重写 V1）
- **RFC-0015**：SUPERSEDED 状态不变，V7 端点是 0015 的复活延展
- **RFC-0016**：`rule_set / rule / rule_version` 表保持不变；仅需在 RuleSet 上加 `type_code` 字段（如已有则不动）
- **RFC-0031 / RFC-0032**：已落地的 Type 系统保持兼容，RuleSetType 不引入新 Type 形态
- **RFC-0018**：仅消费 RuleType.arguments / .returnType / .functionTypes / .validatable

---

## 5. 测试计划

### 5.1 单元测试（实体层）

| 测试目标 | 用例 | 期望 |
|---------|------|------|
| `RuleSetType` 实体构造 | `ruleSetTypeBuilder_shouldBuild` | 必填字段齐备时构造成功 |
| `RuleSetType` 唯一约束 | `duplicateCodeInSameDomain_shouldFail` | 同 DomainType 下重复 code 抛 `DataIntegrityViolationException` |
| `RuleType` 实体构造 | `ruleTypeBuilder_shouldBuild` | 必填字段齐备时构造成功 |
| `RuleType` JSON 拍平序列化 | `argumentsJson_shouldRoundTrip` | `RuleTypeArguments` → JSON → 反序列化结果一致 |
| `RuleTypeArguments.ArgumentType` 内嵌 record | `nestedRecords_shouldSerializeCorrectly` | 嵌套 record 序列化为嵌套 JSON 对象 |
| `FuntionType` 重命名后兼容 | `funtionTypeMapper_legacyDataShouldWork` | V1 种子数据能通过新实体类读取 |

### 5.2 Repository 测试

| 测试 | 期望 |
|------|------|
| `RuleSetTypeRepository.findByDomainId` | 返回该 domain 下所有 RuleSetType |
| `RuleSetTypeRepository.findByDomainIdAndCode` | 唯一返回匹配项 |
| `RuleTypeRepository.findByRuleSetTypeId` | 返回该 RuleSetType 下所有 RuleType |
| `RuleTypeRepository.findByCode` | 跨 RuleSetType 全局唯一 code 查 |
| `FuntionTypeRepository.findByRuleSetTypeId`（@Query 跨中间表）| 返回 RuleSetType 已用的 SDK 集合 |

### 5.3 Service 测试（不变式校验）

| 不变式 | 测试用例 | 期望 |
|--------|---------|------|
| #1 arguments.objectTypeCode ⊆ RuleSetType.objectTypes | `createRuleType_argObjectTypeNotInRs_shouldFail` | 抛 `MetadataInvariantViolationException("ArgumentType xxx.objectTypeCode 未在 RuleSetType.objectTypes 中")` |
| #2 returnType.objectTypeCode ⊆ objectTypes ∪ {VOID} | `createRuleType_returnTypeUnknown_shouldFail` | 抛异常 |
| #2 returnType VOID | `createRuleType_voidReturn_shouldPass` | null objectTypeCode 通过 |
| #3 functionTypeCodes ⊆ RuleSetType.functionTypes | `createRuleType_funcTypeNotInRs_shouldFail` | 抛异常 |
| #4 functionTypeCodes ∩ excludeFunctionTypeCodes = ∅ | `createRuleType_excludeAndIncludeIntersect_shouldFail` | 抛异常 |
| #5 RuleSet.type_code 存在性 | `createRuleSet_unknownTypeCode_shouldFail` | 抛异常（创建时校验，运行时不阻塞） |
| #6 Rule.type_code 在 RuleSet 对应的 RuleSetType 范围内 | `createRule_ruleTypeNotInRs_shouldFail` | 抛异常 |

### 5.4 REST API 集成测试（MockMvc）

| 端点 | 用例 | 期望 |
|------|------|------|
| `POST /api/v1/rule-set-types` | 完整 RuleSetType payload | 返回 200 + 实体 JSON；DB 持久化；rule_set_type_object_type / rule_set_type_funtion_type 中间表写入 |
| `POST /api/v1/rule-set-types` | 缺 `domain_id` | 返回 400 ValidationError |
| `POST /api/v1/rule-set-types` | arguments 引用未注册的 ObjectType | 返回 422 UnprocessableEntity（业务校验失败） |
| `PUT /api/v1/rule-set-types/{id}` | 修改关联 objectTypes 列表（删除仍在 RuleType 引用的） | 返回 409 Conflict（级联保护） |
| `DELETE /api/v1/rule-set-types/{id}` | 已有 RuleSet 引用 | 返回 409 Conflict（不允许 cascade） |
| `DELETE /api/v1/rule-set-types/{id}` | 无 RuleSet 引用 | 返回 200，cascade 删除 rule_type + 中间表 |
| `POST /api/v1/rule-set-types/{rstId}/rule-types` | 嵌套创建 RuleType | 返回 201 + 实体 |
| `GET /api/v1/rule-set-types/{rstId}/rule-types` | 列表 | 返回 RuleType 列表，arguments JSON 自动反序列化 |
| `GET /api/v1/funtion-types/{id}/rule-set-types` | 反向资源 | 返回引用该 FuntionType 的 RuleSetType 列表 |
| `GET /api/v1/funtion-types?category=math` | 列表过滤 | 返回该 category 下的所有 FuntionType |

### 5.5 JSON 拍平反序列化测试

| 场景 | 用例 | 期望 |
|------|------|------|
| 合法 JSON | `deserialize_validArgumentsJson_shouldMap` | 全部字段映射正确 |
| 缺 arguments | `deserialize_missingArgumentsField_shouldFail` | 抛 `JsonMappingException` |
| arguments 类型错误 | `deserialize_argumentsIsString_shouldFail` | 抛 `JsonMappingException` |
| arguments 数组为空 | `deserialize_emptyArgumentsArray_shouldPass` | 入参可为空（规则无入参合法） |
| functionTypeCodes 引用不存在的 code | Service 层 `validateInvariants` | 抛 `MetadataInvariantViolationException` |

### 5.6 性能 / 压力

| 场景 | 期望 |
|------|------|
| 单次 RuleSetType 查询（含 objectTypes / functionTypes）< 50ms | 1000 RuleSetType 数据集下 |
| RuleType JSON 字段查询 | MySQL JSON 函数 `JSON_CONTAINS` / `->>` 性能可接受 |
| V7 迁移脚本在 1 万条 function_lib 数据下完成时间 | < 5s（rename 仅改注释，不动数据） |

---

## 6. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| **JSON 拍平导致 SQL JOIN 困难**（RuleType.arguments[*].objectTypeCode 字符串引用，无 FK）| 🟡 中 | 应用层不变式校验保证一致性；运营后台 / 报表用反规范化视图；MVP 数据量小（百级 RuleType），后期引入 ES 反查 |
| **V7 Flyway ADD 表后，旧 Rule 引用 RuleSetType.code 缺失导致启动失败** | 🔴 高 | V7 同时 INSERT 2 条种子 RuleSetType 演示数据；CI 测试 0→N RuleSetType 启动 |
| **RuleSetType / RuleType 弱关联（字符串 code）与 Rule / RuleSet 实例层的 type_code 长期漂移** | 🟠 中-高 | 启动期 `MetadataIntegrityChecker` 自检；DB 视图 `v_orphan_rules` 显示孤儿 Rule，运营工具每月巡检 |
| **JSON 列 schema 演进复杂**（加字段要兼容老数据）| 🟡 中 | `RuleTypeArguments` 用 Jackson `@JsonIgnoreProperties(ignoreUnknown = true)`；版本字段 `schema_version` 显式标注 |
| **不变式校验逻辑 bug 导致脏数据** | 🟠 中-高 | Service 层校验 + Repository 层 `@Check` 注解双重保护；测试覆盖所有 6 条不变式 |
| **RuleSetType 删除时 cascade 影响** | 🟡 中 | 默认 ON DELETE RESTRICT（不允许 cascade）；必须先解除 Rule 关联才能删除 RuleSetType |
| **FuntionType 重命名后旧 API 路径 `/function-libs` 客户端未同步** | 🟢 低 | RFC-0018-bis 提交 1590cfc 已改；本 RFC 仅作为记录，不再二次重命名 |

---

## 7. 实施步骤

### 7.1 步骤总览（约 4d）

```
Day 1：V7 DDL + FuntionType 实体调整
  1.1 写 V7__rule_set_type_and_rule_type.sql（含种子数据）
  1.2 验证：mvn flyway:migrate 在 dev DB 上无错
  1.3 FuntionType 实体加 @ManyToMany ruleSetTypes（反向） + 注释更新

Day 2：RuleSetType / RuleType 实体 + Repository
  2.1 RuleSetType.java + 唯一约束测试
  2.2 RuleType.java + RuleTypeArguments 嵌套 record + JSON 列映射
  2.3 RuleSetTypeRepository / RuleTypeRepository / 中间表 JPQL @Query

Day 3：Service 层 + 不变式校验
  3.1 RuleSetTypeService CRUD + 嵌套 RuleType 写入
  3.2 InvariantValidator（6 条不变式断言器，独立类便于复用）
  3.3 MetadataIntegrityChecker 启动期自检（弱关联漂移检查）
  3.4 MetadataInvariantViolationException + RFC 0015 的 Result 包装

Day 4：REST API + 集成测试
  4.1 RuleSetTypeController（6 个端点）
  4.2 RuleTypeController（嵌套 5 个端点，复用 RuleSetTypeRepository）
  4.3 FuntionType 反向资源端点
  4.4 IntegrationTest 覆盖所有 §5.4 用例
  4.5 RFC-0018-bis §3.3.1 字段引用回归
```

### 7.2 任务拆解

| # | 任务 | 估算 | 依赖 |
|---|------|------|------|
| 1 | V7__rule_set_type_and_rule_type.sql | 0.3d | RFC-0014 |
| 2 | RuleSetType 实体 | 0.3d | 1 |
| 3 | RuleType 实体 + RuleTypeArguments record | 0.4d | 1 |
| 4 | 3 个 Repository + JPQL @Query | 0.3d | 2, 3 |
| 5 | InvariantValidator + 6 条不变式 | 0.5d | 2, 3 |
| 6 | RuleSetTypeService + RuleTypeService | 0.4d | 4, 5 |
| 7 | MetadataIntegrityChecker 启动期 | 0.3d | 4 |
| 8 | RuleSetTypeController + RuleTypeController | 0.3d | 6 |
| 9 | DTO + Request/Response | 0.3d | 6 |
| 10 | 集成测试覆盖 §5.4 | 0.4d | 8, 9 |
| 11 | 单元测试覆盖 §5.1~§5.3 | 0.3d | 5 |
| 12 | RFC-0018-bis §3.3 引用回归 + 编译通过 | 0.2d | 全部 |
| **总计** | | **4.0d** | |

### 7.3 提交拆分建议

| PR | 内容 | 关联 issue |
|----|------|-----------|
| PR-1 | V7 SQL + RuleSetType/RuleType 实体 + Repository | RFC-0033 §3.2 §3.3 §3.5 |
| PR-2 | InvariantValidator + Service 层 | RFC-0033 §3.7 |
| PR-3 | Controller + DTO + 集成测试 | RFC-0033 §3.6 §5.4 |
| PR-4 | MetadataIntegrityChecker 启动期自检 | RFC-0033 §3.7（不变式 #5 #6） |

---

## 8. 关联

### 8.1 上游（前置 / 依赖）

- **[RFC-0014 数据库 Flyway 迁移基线](RFC-0014-数据库Flyway迁移基线.md)** — V7 在 V1~V5 基础上 ADD；不重写 V1
- **[RFC-0015 元数据域 CRUD API](RFC-0015-元数据域CRUD-API.md)** — SUPERSEDED；V7 端点是 RFC-0015 的复活延展
- **[RFC-0031 Type 系统重构](RFC-0031-Type系统重构.md)** — Type 4 Variant 落地，本 RFC 不引入新 Type 形态
- **[RFC-0032 ObjectType 枚举化 + Type 系统简化](RFC-0032-ObjectType枚举化与Type系统简化.md)** — ObjectType.Kind 加 VOID，本 RFC §3.3 RuleTypeArguments.ReturnType.objectTypeCode=null 对应 VOID
- **[RFC-0018 SimpleTS 解析器（含 RFC-0018-bis）](RFC-0018-SimpleTS解析器.md)** — 仅消费 RuleType.arguments / .returnType / .functionTypes / .validatable

### 8.2 下游（被依赖）

- **RFC-0016 规则域 + 状态机 API** — `Rule.type_code` 与 `RuleSet.type_code` 通过本 RFC §3.7 不变式 #5 #6 与 RuleType / RuleSetType 弱关联
- **RFC-0019 SimpleTS → Groovy 代码生成器** — 通过 RuleType.arguments 拼装 Groovy 函数签名
- **RFC-0020 Groovy 沙箱** — 通过 RuleType.functionTypeCodes 控制可用 SDK 白名单
- **RFC-0023 NL → SimpleTS LLM 调用** — 通过 RuleType.arguments Schema 注入 LLM prompt

### 8.3 平级

- **RFC-0000 MVP RFC 总览** — RFC-0033 已加入总览表
- **ADR-012 enum 视为 ObjectType 特殊形态** — RuleTypeArguments.ReturnType.objectTypeCode=null 对应 VOID 形态

### 8.4 ADR 引用

- **ADR-006 规则源语言采用 SimpleTS** — RuleType 是 SimpleTS 编译入口的元数据依据
- **ADR-009 SimpleTS 为中心的星型转换架构** — RuleType.arguments 是 NL↔SimpleTS↔Groovy 链路的关键类型签名

### 8.5 文档引用

- [docs/dsl/SimpleTS.md §7 元数据（元数据模型）](../../dsl/SimpleTS.md) — 同步记录 RuleSetType / RuleType 概念
- [docs/04-数据模型.md §4.2](../../04-数据模型.md) — 元数据域章节补充 RFC-0033 的 ER 关系
- [docs/05-技术模型.md §5.x](../../05-技术模型.md) — RuleType JSON 拍平的存储权衡说明

---

## 9. 决策点（待最终确认）

> 以下决策点当前按本 RFC 设计推荐方案落实。如有变更需在 RFC-0034 中补充。

| 决策点 | 当前选择 | 替代方案 |
|--------|---------|---------|
| RuleSetType / RuleSet 的关系 | 弱关联（字符串 type_code）| 强 FK（删除 RuleSetType 时 cascade）|
| RuleType 字段拍平形式 | JSON 单列（不建独立小表）| 4 张小表（arguments / returnType / functionTypes / excludeFunctionTypes）|
| RuleSetType 与 DomainType 关系 | 1:N（必须挂领域）| 独立（跨领域共享 RuleSetType 模板）|
| FuntionType 重命名是否同时改 SQL 表名 | 改类名不改表名 | 一并改表名（破坏性更大）|
| RuleSet / Rule 上是否加 FK 强引用 RuleSetType / RuleType | 弱引用（允许孤儿）| 强引用（必须先有类型才能建规则）|
| arguments JSON 拍平是否分版本字段 | 暂不（用 Jackson `@JsonIgnoreProperties`）| 显式 schema_version 列 |

---

## 10. 修订日志

| 日期 | 修订内容 |
|------|---------|
| 2026-09-12 | 新增 RFC-0033 §1~§4：摘要 + 动机 + 详细设计（实体 + ER 图 + V7 DDL + REST API + 不变式 + 影响面）|
