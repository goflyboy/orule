# EntityField/EntityDef vs ObjectType/AttributeType：概念边界澄清

> **触发**：与用户在 `RFC-0018-SimpleTS解析器.md` 上的二次对话
> **时间**：2026-09-12
> **关联**：
> - [RFC-0018-SimpleTS解析器.md §3.3](../rfcs/RFC-0018-SimpleTS解析器.md)（`DomainMeta` 定义）
> - [RFC-0031-Type系统重构.md](../rfcs/RFC-0031-Type系统重构.md)（Type 5 个 Variant）
> - [ADR-011-Type系统重构为JSON树.md](../adr/ADR-011-Type系统重构为JSON树.md)
> - [docs/dsl/SimpleTS.md §7](../dsl/SimpleTS.md)（DomainMeta 形态）
> - [docs/04-数据模型.md §4.2](../04-数据模型.md)（数据库实体）

**结论先行**：

> 这两组概念 **不在同一个抽象层**。看起来名字像，是因为都描述"对象 + 字段"，但它们的 **职责完全不同**：
>
> | 维度 | `AttributeType` / `ObjectType` | `EntityField` / `EntityDef` |
> |------|------------------------------|----------------------------|
> | **归属** | 元数据持久层（JPA Entity + 数据库表） | SimpleTS 编译期运行时模型（in-memory DTO） |
> | **谁定义** | RFC-0014 + RFC-0015 + RFC-0031 | RFC-0018 + SimpleTS.md §7 |
> | **生命周期** | 跨进程持久化，REST CRUD 管理 | 单次编译请求的入参，用完即弃 |
> | **关注点** | 怎么存、怎么查、怎么校验 schema | 怎么让 SimpleTS 解析器跑得动 |
> | **关系** | 数据库 1 条记录 = 1 个字段定义 | 由元数据 **拼装** 而来的"运行时视图" |
>
> 简单一句话：**AttributeType/ObjectType 是"字典"，EntityDef/EntityField 是"按规则拼装出来的工具箱"**。

下面把这条推理链完整展开。

---

## 1. 先把命名冲突摆到桌面上

用户的疑问精准地命中了"命名撞车"。先做一次"翻译"，把容易混淆的概念对齐：

| 用户口中 | 实际指 | 出处 |
|---------|--------|------|
| `EntityField` | `EntityDef.EntityField` 嵌套 record（`name + Type + nullable + writable`） | RFC-0018 §3.3 / SimpleTS §7 |
| `EntityDef` | `DomainMeta.EntityDef` record（`id + List<EntityField>`） | RFC-0018 §3.3 / SimpleTS §7 |
| `ObjectType` | RFC-0031 的 5 个 Variant 之一（`{kind:"object", objectCode:"Customer"}`），**是 Type 系统里的一个类型形态** | RFC-0031 §3.1 / SimpleTS §7 |
| `AttributeType` | 数据库实体（JPA `@Entity`），对应 `attribute_type` 表 | RFC-0015 §3.3 / 04-数据模型 §4.2.2 |

命名上的两个易混点：
1. **`EntityField` vs `AttributeType`**：字面上都是"对象的字段定义"，但前者是 SimpleTS 编译期的 record，后者是数据库实体。
2. **`ObjectType` 这个名字同时出现**：在 RFC-0031 里它是 Type 系统的一个 variant（"这是个对象引用"）；在 `04-数据模型` 里它是数据库实体（`object_type` 表，对应"业务对象类型"）。**这两个 ObjectType 不是一回事**，详见 §4。

---

## 2. AttributeType/ObjectType 在 RFC-0031 后到底是什么

RFC-0031 重构后，Type 系统变成 **单一 JSON 树 + sealed interface（5 个 Variant）**：

```java
public sealed interface Type permits
    PrimitiveType, EnumType, ObjectType, ListType, MapType {

    String kind();  // primitive | enum | object | list | map
}
```

数据库侧，AttributeType 表长这样（RFC-0031 §3.2）：

```sql
CREATE TABLE attribute_type (
    id              VARCHAR(36)  PRIMARY KEY,
    object_id       VARCHAR(36)  NOT NULL,         -- FK → object_type.id
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    data_type       VARCHAR(32)  NOT NULL,         -- kind 标签
    type_json       JSON         NOT NULL,         -- 完整 Type 结构（Jackson 多态）
    is_required     BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value   VARCHAR(255),
    description     TEXT,
    ...
);
```

也就是说，**一个 AttributeType 数据库行 = 一个字段定义**，它的 `type_json` 列里塞了一棵完整的 Type 树，比如 `tier` 字段可能存：

```json
{
  "kind": "enum",
  "enumCode": "CustomerTier",
  "values": [
    {"code": "VIP", "label": "VIP 客户", "sortOrder": 1},
    {"code": "GOLD", "label": "金牌客户", "sortOrder": 2}
  ]
}
```

或者 `address` 字段：

```json
{
  "kind": "object",
  "objectCode": "Address"
}
```

注意 **`objectCode: "Address"` 是一个引用**——它指向另一个 `object_type` 记录。ObjectType 本身**不携带自己的字段信息**，它只是个 ID 占位符。

---

## 3. EntityField/EntityDef 在 SimpleTS 编译视角是什么

SimpleTS 解析器（RFC-0018）需要一个"按规则版本拼装好的、能直接驱动解析流程的"内存模型。它长这样（RFC-0018 §3.3）：

```java
public record DomainMeta(
    String id,
    List<EntityDef> entities,
    List<ContextVar> context
) {
    public record EntityField(
        String name,
        Type type,           // RFC-0031 的 5 个 Variant 之一
        boolean nullable,
        boolean writable
    ) {}

    public record EntityDef(String id, List<EntityField> fields) {}
}
```

这个 DomainMeta 是 **入参**——SimpleTSParser.parse(source, meta) 接收它，然后做剪枝 + 校验。

关键点：

### 3.1 DomainMeta 不携带元数据的"CRUD 能力"

它没有 `id`（数据库 PK）、没有 `createdAt` / `updatedAt`、没有 `description`、没有 `isBuiltin`、没有 `defaultValue`。它是 **为编译流程量身定制的工作台**，不是要落库的对象。

### 3.2 DomainMeta 是从元数据 API "拼装"出来的

RFC-0019 §3.6 的 `CompileService.buildDomainMeta(...)` 描述了拼装流程：

```java
private DomainMeta buildDomainMeta(String domainId) {
    // 1. 取 DomainType（一个域的所有业务对象归属）
    DomainType domain = domainTypeRepo.findById(domainId);

    // 2. 取该域下所有 ObjectType（数据库实体）
    List<com.orule.common.entity.ObjectType> objects =
        objectTypeRepo.findByDomainId(domainId);

    // 3. 拼装 EntityDef：每个 ObjectType 的 attributes 列表 → EntityField 列表
    List<EntityDef> entities = objects.stream().map(obj -> {
        List<AttributeType> attrs = attributeTypeRepo.findByObjectId(obj.getId());
        List<EntityField> fields = attrs.stream().map(attr ->
            new EntityField(
                attr.getCode(),
                attr.getType(),           // AttributeType.type (Type 树) → EntityField.type
                attr.getIsRequired(),
                attr.getWritable()
            )
        ).toList();
        return new EntityDef(obj.getCode(), fields);
    }).toList();

    // 4. context 入口变量从 domain_type 的 RuleForm / contextVariables 配置来
    List<ContextVar> context = ...;

    return new DomainMeta(domain.getCode(), entities, context);
}
```

**这一步的产物才是 DomainMeta**，它 **不是数据库实体的替代品**，而是 **数据库实体的"投影"**。

### 3.3 EntityField 把"字段定义 + 解析器关心的元信息"打包成一行

对比一下 AttributeType（数据库实体）和 EntityField（编译期 record）：

| 字段 | AttributeType（数据库） | EntityField（编译期） | 差异原因 |
|------|----------------------|----------------------|---------|
| `id` | ✅ UUID 主键 | ❌ 无 | 数据库需要 PK |
| `code` | ✅ `attr-cust-tier` | ✅ `tier` | 名字相同但语义不同（PK vs 字段名） |
| `name` | ✅ "客户等级"（展示用） | ❌ 无 | 编译不需要展示名 |
| `dataType` | ✅ kind 标签 | ❌ 无 | 已被 `type.kind()` 覆盖 |
| `type` (Type 树) | ✅ `type_json` 反序列化得到 | ✅ 直接引用 | 同一个 Type 实例 |
| `isRequired` | ✅ | ✅ 转 `nullable`（取反） | 命名习惯对齐 SimpleTS.md |
| `writable` | ❌ 原 RFC 没有（按 ADR-011 §4 加入了） | ✅ | **编译期校验左值需要这个** |
| `description` | ✅ | ❌ | 编译不需要 |
| `createdAt` / `updatedAt` | ✅ | ❌ | 编译不需要 |

**EntityField 比 AttributeType 少了 5~6 个字段**，都是 SimpleTS 解析器用不到的"管理性"字段。**这是有意识的设计**，不是冗余。

---

## 4. 核心：为什么要单独搞一层 EntityDef？

### 4.1 名字撞车的根因：SimpleTS 借用"Entity"这个词描述"业务对象"

SimpleTS 源码里出现的是 `customer.tier`、`order.totalAmount`——这里的 `customer` 和 `order` 在语义上是"业务对象"，所以 SimpleTS 用 `Entity` 来命名它。但 **SimpleTS 不直接读写数据库**，它看到的"实体"是 **编译期一次性快照**：

- `EntityDef.id = "Customer"` ← 来自 `object_type.code`
- `EntityDef.fields = [EntityField(name="tier", type=EnumType...), ...]` ← 来自 `attribute_type` 行

**Entity = 数据库 ObjectType 的"按规则版本快照"**。

### 4.2 用 ObjectType/AttributeType 直接当 DomainMeta 行不行？

理论上"可以"，但有 5 个实际痛点。这才是用户问的本质。

#### 痛点 1：ObjectType 在 RFC-0031 里只是个引用，不携带字段

```java
// RFC-0031 §3.1
public record ObjectType(String objectCode) implements Type {
    @Override public String kind() { return "object"; }
}
```

它 **没有 fields 列表**。当解析器需要查 `customer.tier` 是否合法时，它得：

1. 拿 `customer` 的 ObjectType，知道 `objectCode = "Customer"`
2. 去 `meta.entities` 里找 `id == "Customer"` 的 EntityDef
3. 在 EntityDef.fields 里找 `name == "tier"` 的 EntityField
4. 拿到 EntityField.type 做类型校验

**如果用 ObjectType 替代 EntityDef，步骤 2 就跑不通了**——ObjectType 是个 Type variant，不是结构定义。

#### 痛点 2：DomainMeta 不能直接持有 JPA 实体

DomainMeta 在 orule-common 里，被 orule-server（拼装）/ orule-runtime（执行，理论上未来也可能 dry-run）共享。如果 DomainMeta 直接持有 `AttributeType`（JPA `@Entity`）：

- orule-common 就要依赖 Hibernate 注解 + JPA 拦截器（虽然它本身已经在 orule-common，但会让"领域模型"和"持久化"耦合）
- JSON 序列化会出现 Hibernate 代理类问题（`AttributeType_HibernateProxy`）
- Lazy 加载在跨层传递时会爆炸（解析器根本不需要 lazy 字段）

**EntityField 是 immutable record，不携带 JPA 代理，是干净的"内存值对象"**。

#### 痛点 3：EntityField 加了"解析器专属"字段

```java
public record EntityField(
    String name,
    Type type,
    boolean nullable,
    boolean writable       // ← 这个 AttributeType 之前没有（ADR-011 §4 才加入）
) {}
```

`writable` 字段是 RFC-0018 §3.6 `validateWritable()` 用来校验"禁止给只读字段赋值"的。如果直接用 AttributeType：

- AttributeType 已经有 `writable`（ADR-011 §4 加的，OK，名字一致）
- 但 AttributeType 还有 `isRequired`、`defaultValue`、`description` 等 SimpleTS 用不到的字段
- 解析器读 AttributeType 就会拿到 10 个字段，**只用其中 4 个**，剩下 6 个字段是污染

#### 痛点 4：拼装边界需要"业务规则专属"的上下文

EntityField 把 `isRequired`（数据库字段）转成了 `nullable`（SimpleTS 语义）。两者语义相反：
- `isRequired = true` 表示"必填"（数据库约束）
- `nullable = false` 表示"非空"（SimpleTS 校验语义）

**这是同一个事实的不同命名空间**。EntityField 在拼装时做这次翻译，让下游解析器只看 SimpleTS 自己的语义。如果直接用 AttributeType，解析器就要理解数据库的"必填"概念——**抽象泄漏**。

#### 痛点 5：MVP "一层嵌套"约束的承载位置

RFC-0031 §3.5.2 规定 MVP 期间 `ObjectType` 字段在 SimpleTS 中**不可继续访问**。这条规则的承载实体是 EntityField：

```java
// RFC-0018 §3.6 FieldValidator.validateMemberAccess()
if (!(field.type() instanceof ObjectType ot)) {
    errors.add("字段 '" + fieldName + "' 是 " + kindName(field.type())
        + " 类型，不能继续访问内部属性");
}
```

判断"这是不是最后一跳"时，解析器直接读 `field.type()`，类型是 RFC-0031 的 ObjectType，**没有歧义**。如果用 AttributeType 替代 EntityField，`attribute_type` 是 JPA 实体，`type` 是 JSON 反序列化得到的 Type——要穿透两层访问 `field.type() instanceof ObjectType` 是 OK 的，但 **泛型擦除 + JPA 代理** 会有边角问题。

### 4.3 一句话总结

> **AttributeType/ObjectType 是"数据怎么存"的字典；EntityDef/EntityField 是"编译怎么跑"的工具箱。**
>
> 字典可以在多个工具箱之间共享，但工具箱会按工具的形态重新打包字段。SimpleTS 编译工具箱只装它真正要用的 4 个字段（name/type/nullable/writable），其余的从字典里裁掉。

---

## 5. 把"概念地图"画出来

把仓库里相关的几个核心模型放在一张图上：

```
┌──────────────────────────────────────────────────────────────────┐
│ 元数据持久层（JPA Entity + 数据库表）                                  │
│                                                                      │
│  domain_type     ──FK──→  object_type     ──FK──→  attribute_type  │
│  (域)                          (业务对象)            (字段)             │
│                                       │                  │             │
│                                       │                  │             │
│                                       ▼                  ▼             │
│                                  [object_id]      [id, code,         │
│                                                   data_type,         │
│                                                   type_json,         │
│                                                   is_required,       │
│                                                   writable, ...]     │
└──────────────────────────────────────────────────────────────────┘
                                    │
                                    │  buildDomainMeta()  ← RFC-0019 §3.6
                                    │  （一次 JOIN，拼装一份"按规则版本的快照"）
                                    ▼
┌──────────────────────────────────────────────────────────────────┐
│ SimpleTS 编译期运行时模型（in-memory record）                          │
│                                                                      │
│  DomainMeta                                                           │
│  ├── id: String                                                       │
│  ├── entities: List<EntityDef>     ← 来自 object_type + attributes   │
│  │      └── EntityDef                                               │
│  │             ├── id: String                  ← object_type.code     │
│  │             └── fields: List<EntityField>   ← attribute_type 行   │
│  │                    └── EntityField                                   │
│  │                           ├── name: String      ← attribute.code  │
│  │                           ├── type: Type        ← type_json        │
│  │                           ├── nullable: boolean ← !is_required    │
│  │                           └── writable: boolean                    │
│  └── context: List<ContextVar>     ← domain_type 的入口变量配置        │
│         └── ContextVar                                                │
│                ├── name: String                                       │
│                ├── type: ObjectType ← 只能是 object 引用              │
│                └── nullable: boolean                                   │
└──────────────────────────────────────────────────────────────────┘
                                    │
                                    │  SimpleTSParser.parse(source, meta)
                                    ▼
┌──────────────────────────────────────────────────────────────────┐
│ SimpleTS-AST（编译产物）                                              │
│                                                                      │
│  Program → IfStmt → BinaryExpr → MemberAccess(root, path)            │
│                                                                      │
│  MemberAccess.root 必须在 DomainMeta.context 中                       │
│  MemberAccess.path  每一跳按 EntityDef.fields 校验                    │
└──────────────────────────────────────────────────────────────────┘
```

关键箭头只有一条：**元数据 → DomainMeta（投影）→ SimpleTS-AST（编译产物）**。

---

## 6. 给用户的三条具体回答

| 用户的疑问 | 答复 |
|-----------|------|
| "为什么单独搞个 Entity，感觉是另一个语义？" | **不是另一个语义，是同一语义的不同抽象层**。`EntityDef` 描述的是"按规则版本快照出来的、业务侧理解的业务对象"，`ObjectType` 描述的是"数据库里持久化的一行"——它们是 **数据库 → 编译期** 的两次投影。 |
| "为什么不能用 ObjectType + AttributeType 直接代替？" | **不是不能，是不应该**。ObjectType 在 RFC-0031 里是 Type variant，不携带 fields；AttributeType 是 JPA Entity，带 5~6 个 SimpleTS 用不到的字段。直接用会出现抽象泄漏、JPA 代理污染、命名空间混淆（isRequired vs nullable）等问题。 |
| "那为什么不把 EntityDef 直接复用 AttributeType，把没用到的字段删掉？" | **抽象层错位**。AttributeType 在 orule-common.entity 包，是持久化层；EntityDef 在 com.orule.dsl 包，是编译期运行时模型。两个包 **不应该互相 import**（编译期不应依赖持久化实体）。 |

---

## 7. 如果未来真的想合并，可走的路

### 7.1 路径 A：保持现状（推荐）

- `AttributeType` 在 `orule-common.entity`（持久化）
- `EntityField`/`EntityDef`/`DomainMeta` 在 `com.orule.dsl`（编译期）
- 通过 `MetadataService.buildDomainMeta(domainId)` 在边界做投影

**优点**：抽象清晰，编译期无 JPA 依赖。

### 7.2 路径 B：把 EntityDef/EntityField 上移到 orule-common.model.domain（可考虑）

- 移动到 `packages/orule-common/src/main/java/com/orule/common/model/domain/`
- 与 RFC-0031 的 `orule.common.model.type` 平行
- **保持 entity ↔ domain 包的边界**，编译期 from common.model.domain import，不直接依赖 entity 包

**优点**：跨模块共享更明确（server / runtime 都能直接用 DomainMeta）。
**代价**：RFC-0018 §3.3 描述要更新（包路径 + §3.10 SimpleTSWhitelist 不受影响）。

### 7.3 路径 C：完全合并到 AttributeType（不推荐）

- 让 AttributeType 同时承担"持久化"和"编译期"两个职责
- 增加 `getSimpleTSView()` 方法返回 EntityField

**问题**：
- 抽象泄漏：AttributeType 出现在编译期调用栈上，污染 SimpleTS-AST 序列化
- JPA 代理在跨层传递的边角问题
- RFC-0018 §3.6 `validateWritable` 等逻辑被迫写在 entity 包，违反"entity 包只是数据"的惯例

**结论**：技术上能跑，但工程上不优雅。

### 7.4 路径 D：完全砍掉 EntityField，DomainMeta 直接持有 ObjectType/Type（不推荐）

- DomainMeta 直接持有 `List<Pair<String /*entityCode*/, List<Pair<String /*fieldCode*/, Type>>>>`

**问题**：
- 失去 `nullable` / `writable` 字段（这两个 SimpleTS 校验必需）
- 类型系统失去 `EntityDef` 的"业务对象"概念，解析器逻辑变碎
- 与 SimpleTS.md §7 的现有定义脱节，所有引用 RFC-0018 §3.3 的地方都要重写

**结论**：技术上能跑，但与已落地的 5 个 RFC/RFC-0031 全部冲突。

---

## 8. 后续动作建议

如果用户认可上述判断（即 EntityField/EntityDef 与 ObjectType/AttributeType 是 **不同抽象层，不应合并**），可继续 TDD 推进：

1. **不修改 RFC-0018 §3.3 / RFC-0031**：当前分层合理
2. **可选微优化**：把 §7.2 的包路径上移单独发一个 RFC（影响面小，提升跨模块共享清晰度）
3. **继续 [tmpdocs/TDD推进RFC0018-20-待确认问题.md](../tmpdocs/TDD推进RFC0018-20-待确认问题.md) 的 D1~D4 阻塞问题**

如果用户希望走 §7.3 / §7.4 的合并方案，建议 **先开 ADR 决策会**，因为：
- §7.3 会让 SimpleTS 编译期依赖 JPA 实体
- §7.4 会推翻 SimpleTS.md §7 已落地的定义，并连带修改 RFC-0015/0018/0019/0020/0031
