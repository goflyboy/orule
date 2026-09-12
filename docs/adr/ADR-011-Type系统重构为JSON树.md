# ADR-011：Type 系统重构为 JSON 树

> 状态：已通过
> 日期：2026-09-12
> 决策者：架构组
> 相关：RFC-0031、ADR-009（SimpleTS 为中心）、RFC-0014/0015/0018/0019

---

## 决策

将 orule 的 Type 系统重构为**单一 JSON 树 + sealed interface（5 个 Variant）**：

1. 删除 `enum_type` / `enum_value` 两张表
2. `attribute_type` 表新增 `type_json JSON` 列，存储完整 Type 结构
3. `function_lib.signature` 改为 JSON，存储参数 + 返回类型的 Type 树
4. Type 系统放在 `orule-common` 模块，跨 server / runtime / dsl 共享
5. 一层嵌套：MVP 期间 `ObjectType` 在 SimpleTS 中不可继续访问内部属性

---

## 上下文

### 当前痛点

| 问题 | 现状 | 痛点 |
|------|------|------|
| enum 表冗余 | 独立 `enum_type` + `enum_value` 表 + 4 个 CRUD 类 | 增加维护成本；查询要 JOIN |
| 类型表达力不足 | `data_type VARCHAR(32)` 只能存基础类型名 | 无法表达 List/Map；type 树扁平 |
| enum 独立于 attribute | `enum_type` 全局独立；attribute 通过 FK 引用 | 跨域命名冲突；类型树校验难 |
| 类型签名不统一 | `function_lib.signature VARCHAR(255)` 字符串 | 运行时才知道类型对不对 |
| SimpleTS 不支持 List/Map | 只有 primitive + entity + enumRef | 写算法受限 |

### 未来诉求

- 算法的 Type 签名：函数库需要清晰的 Type 树签名（`(Map<String, Order>) -> number`）
- 运行时类型检查：算法运行时能基于 Type 树校验参数
- 嵌套类型：未来业务需要 `List<Order>`、`Map<String, Item>` 等结构化类型

---

## 备选方案

### 备选 A：多列 + 判别列

```sql
kind VARCHAR(20) NOT NULL
type_ref_id VARCHAR(36)     -- enum/object 引用
element_type_id VARCHAR(36) -- list/map 元素
```

- 优点：保留 FK 约束
- 缺点：复杂、嵌套受限、未来加新类型需要改 schema；不优雅

### 备选 B：保持 enum 独立表

- 优点：enum 可复用、单一来源
- 缺点：仍有独立 enum 表，违反"扁平化"诉求；用户明确不接受

### 备选 C（采纳）：JSON 列 + 5 个 Variant

- 优点：
  - 单一字段、无 JOIN、原生嵌套
  - 未来加新类型（如 Set、Tuple）无 schema 迁移，只需要新增 Variant
  - Jackson 多态序列化机制成熟
- 缺点：
  - 失去 SQL 约束（应用层校验）
  - 反序列化错误风险（启动期校验可缓解）

---

## 决策理由

采纳备选 C，理由：

1. **扁平化**：删除独立 enum 表，与"扁平通用"诉求一致
2. **可扩展**：未来加 Set/Tuple/Record 等类型只需新增 Variant；schema 稳定
3. **统一**：Type 系统既供 SimpleTS 静态校验，也供 FunctionLib 签名，也供运行时算法
4. **MVP 可接受**：MVP 未上线，可直接重写 V1 migration；如有线上数据需增量迁移

### 5 个 Variant 设计

| Variant | 用途 | 示例 |
|---------|------|------|
| `PrimitiveType` | string/number/boolean/date | `{kind:"primitive",name:"string"}` |
| `EnumType` | 内联枚举 | `{kind:"enum",enumCode:"CustomerTier",values:[...]}` |
| `ObjectType` | 对象引用 | `{kind:"object",objectCode:"Address"}` |
| `ListType` | 列表 | `{kind:"list",elementType:{...}}` |
| `MapType` | 字典 | `{kind:"map",keyType:{...},valueType:{...}}` |

### 一层嵌套约束

MVP 期间 `ObjectType` 在 SimpleTS 表达式里**不能继续访问内部属性**：

- 简单业务：直接拍平所有字段到顶级 entity
- 复杂业务：未来通过 RFC-0032 扩展 SimpleTS 支持嵌套访问
- 算法调用：通过 FunctionLib 签名（Type 树）实现，与 SimpleTS 无关

---

## 实施影响

### 数据库

| 表 | 变化 |
|----|------|
| `attribute_type` | 新增 `type_json JSON` 列；`data_type` 保留作 kind 标签 |
| `function_lib` | `signature VARCHAR(255)` → `signature JSON` |
| `enum_type` | **删除** |
| `enum_value` | **删除** |

### 代码

| 模块 | 变化 |
|------|------|
| `orule-common.model.type` | 新增 5 个 Variant + FunctionSignature |
| `orule-common.entity` | 修改 `AttributeType`、`FunctionLib`；删除 `EnumType`、`EnumValue` |
| `orule-common.dto` | 修改 AttributeType/FunctionLib DTO；删除 EnumType/EnumValue DTO |
| `orule-server.service` | 修改 `MetadataService`、`FunctionLibService`；删除 `EnumTypeService` |
| `orule-server.controller` | 删除 `EnumTypeController` |
| `orule-server.repository` | 删除 `EnumTypeRepository` |
| `orule-server.test` | 修改 `MetadataApiIntegrationTest`，移除 enum 流程 |

### 文档

- 新增 `docs/rfcs/RFC-0031-Type系统重构.md`
- 修改 `docs/04-数据模型.md` §4.2
- 修改 `docs/dsl/SimpleTS.md` §7 DomainMeta 形态
- 修改 `docs/rfcs/README.md` 索引
- 在 RFC-0014/0015/0018/0019 中追加"RFC-0031 修订"章节

---

## 决策日志

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-12 | sealed interface + 5 个 Variant | 扩展性 + 可读性 |
| 2026-09-12 | JSON 列存 type | 嵌套支持 + 无 JOIN |
| 2026-09-12 | 删除 enum_type/enum_value 表 | 扁平化诉求 |
| 2026-09-12 | Type 系统放 orule-common | 跨模块共享 |
| 2026-09-12 | ObjectType 在 SimpleTS 限制一层嵌套 | MVP 保持 SimpleTS 简单 |

---

## 后续 RFC

- RFC-0032：SimpleTS 支持 list/map/object 嵌套访问
- RFC-0033：enum 全局唯一字典（避免重复内联）
- RFC-0034：运行时类型检查与算法 API
