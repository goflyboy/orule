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

## 5. 测试计划（占位 — 下次会话补完）

> 见下次会话。本次仅保留 §1~§4。

## 6. 风险（占位 — 下次会话补完）

## 7. 实施步骤（占位 — 下次会话补完）

## 8. 关联（占位 — 下次会话补完）

---

### 8.1 修订日志

| 日期 | 修订内容 |
|------|---------|
| 2026-09-12 | 新增 RFC-0033：RuleSetType / RuleType 元数据层；V7 DDL 设计；RuleType JSON 拍平；FuntionType 重命名；REST API 设计；不变式 6 条 |
