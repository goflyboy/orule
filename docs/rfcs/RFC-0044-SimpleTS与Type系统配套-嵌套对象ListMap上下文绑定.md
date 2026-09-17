# RFC-0044: SimpleTS 与 Type 系统配套（嵌套对象 / List / Map 上下文绑定）

> **状态**：DRAFT · **优先级**：P1 · **预计工作量**：0.7d（文档先行；SimpleTS 解析器/codegen 落地随 SimpleTS 模块出现，Type 种子随 RFC-0032 IMPLEMENTING 落）
> **关联 RFC**：[RFC-0043 嵌套对象 / List / Map 上下文绑定与属性赋值](RFC-0043-嵌套对象List与Map上下文绑定.md)（本 RFC 的执行侧母 RFC）、[RFC-0040 规则执行服务](RFC-0040-规则执行服务.md)、[RFC-0018 SimpleTS 解析器](RFC-0018-SimpleTS解析器.md)、[RFC-0019 SimpleTS 转 Groovy](RFC-0019-SimpleTS转Groovy代码生成器.md)、[RFC-0020 Groovy 沙箱](RFC-0020-Groovy沙箱单条规则执行器.md)、[RFC-0032 ObjectType 枚举化](RFC-0032-ObjectType枚举化与Type系统简化.md)、[RFC-0033 元数据管理（2）— RuleSetType 与 RuleType](RFC-0033-元数据管理2-RuleSetType与RuleType.md)
>
> **范围**：RFC-0043 v0.2 把"SimpleTS 配套 + Type 系统配套"抽出，统一在本文档表述。所有 SimpleTS 文法、白名单、FieldValidator、codegen 映射、Type 种子改动都按本 RFC 落地。RFC-0043 仅保留「执行契约 + 写回语义 + 系统测试」。

---

## A. 模块所有权

| 区域 | 范围 |
| --- | --- |
| 顶层 | 文档：本 RFC 自身、`docs/dsl/SimpleTS.md`、`docs/rfcs/README.md`。本仓库无 `cruleengine` / `crulemgr`。 |
| orule-common | 白名单 JSON：`simplets-whitelist.json` 的 `globalFunctions` 与 `methodSignatures`；`SimpletsWhitelistLoaderTest` 单测扩 case。Type 工厂种子加载路径不动。 |
| orule-server | Flyway V5 种子补：`customers: list<Customer>`、`customersById: map<string, Customer>`；不新增 CRUD API 形态。 |
| SimpleTS（尚未独立模块） | 文法（Map 下标 + typed declare）、AST 新增 `IndexAccess`、FieldValidator 改造、Whitelist 增函数、codegen 映射、class 包装策略确认。本 RFC 把"必须改什么"写死；代码随模块出现时按本文档 §4 实施。 |
| orule-rule-execution-service | **只读**：沙箱策略由 RFC-0043 §4.6.4 / RFC-0020 锁定；本 RFC 不引入新执行器契约。 |
| cruleengine / crulemgr | **不涉及**。 |
| 跨模块边界 | SimpleTS 代码合并到独立模块时复用本 RFC §4.6 的语法补丁；现有 RuleType JSON 不破，本 RFC 只补种子与 FieldValidator 分支。 |

---

## B. 复用优先

- 现有白名单文件：`packages/orule-common/src/main/resources/simplets-whitelist.json`，已含 `size` / `len` / `contains` / `isEmpty`。本 RFC 补 `get` / `containsKey` / `containsValue` / `keySet` / `values`，并补 `methodSignatures`。
- 现有单测：`SimpletsWhitelistLoaderTest`，本 RFC 扩 case 覆盖新增函数。
- 现有 Type 工厂：`TypeFactory.buildMap` 已允许 value 为 object programCode；TD-003 主要是文档/种子缺口。本 RFC 关闭 TD-003 的"map.value=object"切片。
- 现有 RFC-0018：FieldValidator 现在 `arg.getObjectType()`，会把 list/map 根参数判死，本 RFC 改为 `arg.getType()` 按 variant 分支。
- 现有 RFC-0019：示例仍可能把脚本包成 `class RuleGroovy { static Object execute(Map context) }`；本 RFC 明确：执行器走脚本顶层，不包 class，那是"独立 Groovy 文件"形态。
- 不新增：不新增 SimpleTS 模块；不做通用 ObjectInst 框架；不引入新 Type variant。
- 可由上下文推导的字段：白名单 JSON 的 schema 版本号仍由 loader 推导；本 RFC 不引入 schema 升级。

---

## 1. 摘要

为支持 RFC-0043 的执行契约——`Customer vip = customersById["alice"]`、`size(customers)`、`get(customers, i)`、`customersById["alice"]` 下标访问——SimpleTS 表面与 Type 元数据必须配套放开。本 RFC 单独承载这部分改动，与 RFC-0043（执行契约 + 写回语义 + 系统测试）解耦。

```text
SimpleTS:    let vip: Customer = customersById["alice"]
              for (let i = 0; i < size(customers); i = i + 1) { ... }

Type:        customers: List<Customer>
             customersById: Map<string, Customer>

白名单:      get / size / len / contains / containsKey / containsValue / keySet / values
methodSignatures: List.get / Map.get / Map.containsKey / Map.containsValue / Map.keySet / Map.values
```

---

## 2. 动机与反驳

**反驳（先说）**：v0.1 把"SimpleTS 改 + Type 改"塞进"系统测试 RFC"是错的。执行契约（RFC-0043）和语言配套（本文档）应当分开。[COMPUTED, 置信度 HIGH]

| 事实 | 来源 |
| --- | --- |
| RFC-0043 要 `Customer vip = ...` 成立，必须 SimpleTS 允许 typed declare 引 ObjectType | RFC-0043 §4.2 |
| RFC-0043 要 `customersById["alice"]` 成立，必须 SimpleTS 恢复 Map 下标 | RFC-0043 §4.4 G-5 |
| RFC-0043 要 `size(customers)` / `get(customers, i)` / `keySet(map)` / `containsKey`，必须白名单与 `methodSignatures` 补齐 | RFC-0043 §4.4 函数表 |
| RFC-0043 要 `customers: List<Customer>` / `customersById: Map<string, Customer>`，必须 Type 种子支持 list/map 根参数、map.value=object | RFC-0043 §4.7 |
| SimpleTS 文法现在砍掉数组下标，FieldValidator 禁止访问 list/map 内部元素 | RFC-0018 / SimpleTS.md |
| `TypeFactory.buildMap` 已能表达 `map<string, Customer>` | TypeFactory.java |

正确范围：**SimpleTS 语法补丁（§4.6）+ Type 系统配套（§4.7）放本 RFC；执行契约（§4.1~§4.5、§4.8~§4.10）放 RFC-0043。**

---

## 3. 设计目标

| 编号 | 目标 | 验收 |
| --- | --- | --- |
| **G-1** | Map 下标语法可用 | SimpleTS 解析 `customersById["alice"]` 通过；codegen 输出 `customersById["alice"]` |
| **G-2** | typed declare 可引用 ObjectType / Enum | `let vip: Customer = ...` 解析通过；codegen 输出 `Customer vip = ...` |
| **G-3** | §4.4 函数白名单齐 | `simplets-whitelist.json` `globalFunctions` 含 §4.4 全部函数；`methodSignatures` 补 List/Map 必要方法 |
| **G-4** | FieldValidator 支持 list/map 根 | RFC-0043 场景 B、C 在 SimpleTS 源码形态下不报"不能访问 list/map 内部" |
| **G-5** | codegen 产出不含闭包、不含 `def`/ `let` 关键字 | 与 RFC-0043 §4.6.3 表一致；用户侧文档只展示 SimpleTS 形态 |
| **G-6** | Type 根参数支持 ListType/MapType | RFC-0018 FieldValidator 改为 `arg.getType()` 按 variant 分支；RFC-0032 §3.8 TD-003 改为"已由 RFC-0043/0044 放开 map.value=object" |
| **G-7** | Type 种子含 list/map 根参数 | V5 种子补 `customers: list<Customer>` 与 `customersById: map<string, Customer>` |

非目标：

- 不实现 RFC-0040 ObjectInst 深拷贝框架。
- 不实现 RFC-0032 TD-001 / TD-002（key=object、value=list）。
- 不引入箭头函数 / Groovy closure 作为规则语言。
- 不把 SimpleTS 解析器/codegen 本 RFC 落地为代码模块；只在 SimpleTS 模块出现时按 §4 实施。

---

## 4. 详细设计

### 4.1 改动总览

| 模块 | 文件 | 性质 |
| --- | --- | --- |
| 顶层 | `docs/dsl/SimpleTS.md` | 文档补丁（§4.6.1） |
| 顶层 | `docs/rfcs/README.md` | 索引加 RFC-0044 一行 |
| 顶层 | `docs/rfcs/RFC-0018-SimpleTS解析器.md` | FieldValidator / AST / 文法补丁（§4.6.2） |
| 顶层 | `docs/rfcs/RFC-0019-SimpleTS转Groovy代码生成器.md` | codegen 映射表（§4.6.3）；class 包装策略澄清 |
| 顶层 | `docs/rfcs/RFC-0032-ObjectType枚举化与Type系统简化.md` | §3.8 TD-003 关闭本切片（§4.7） |
| 顶层 | `docs/rfcs/RFC-0020-Groovy沙箱单条规则执行器.md` | 沙箱一致性（§4.6.4）；本 RFC 不改沙箱，仅同步 |
| orule-common | `simplets-whitelist.json` | `globalFunctions` 补 `get`/`containsKey`/`containsValue`/`keySet`/`values`；`methodSignatures` 补 List/Map 方法 |
| orule-common | `SimpletsWhitelistLoaderTest.java` | 单测扩 case 覆盖新增函数 |
| orule-server | `db/migration/V5__init_seed.sql` | 种子补 ListType/MapType 根参数 |
| SimpleTS 模块（尚未独立） | 未来落地的解析器 / codegen | 按 §4.6.2 / §4.6.3 实施 |

### 4.6 SimpleTS 表面补丁

> 与 RFC-0043 §4.6 内容一致。从 RFC-0043 v0.2 抽出到本 RFC 后，RFC-0043 中相关子节替换为指向本 RFC 的引用块。

#### 4.6.1 `docs/dsl/SimpleTS.md`

1. **恢复下标，但只给 Map，不给 List 字面量。**
   - §5"砍掉的语法"把"下标访问 `a[0]`"从"数组"行拆开。
   - 文法：

   ```
   primary        ::= literal | memberAccess | indexAccess | call | "(" expr ")"
   indexAccess    ::= (IDENT | memberAccess | call) "[" expr "]"
   ```

   - Map：`customersById["alice"]`、`customersById[k]` 合法。
   - List：`customers[0]` **仍非法**；必须 `get(customers, 0)`。
   - 仍禁止数组字面量 `[1,2]`、解构、展开。

2. **typed declare 的 typeRef 可引用 ObjectType.programCode。**
   - 现有：`declare ::= "let" IDENT (":" typeRef)? ("=" expr)?`
   - 规则作者也可写 TS 风格 `let vip: Customer = customersById["alice"]`。
   - Groovy 视觉一致输出：`Customer vip = customersById["alice"]`（用户点名要这种，不保留 `let`/`def`）。
   - `typeRef ::= "string" | "number" | "boolean" | "date" | IDENT`
   - IDENT 必须是当前 RuleType 可见的 ObjectType / Enum programCode。未知类型 → 编译错误 `未声明的类型 'Foo'`。

3. **§5 函数式砍掉项保持**：箭头函数、匿名函数、闭包继续禁止。本 RFC 不放松。

4. **§7.1 MVP 嵌套约束收窄，不再一刀切。**
   - 仍禁止：`customer.address.city`（object 字段继续点属性）——除非该字段类型是 object **并且**作为局部变量取出后再点。本 RFC 采用更可执行的规则：
     - 允许：`customer.tier`（object 的 primitive/enum 字段）
     - 允许：`customersById["alice"]`（context 根如果本身是 map）
     - 允许：`get(customers, i)`（context 根如果本身是 list）
     - 禁止：`order.items[0].price`（object 字段是 list/map 后再下钻）。取出后必须先放到局部变量。
   - 改 RFC-0018 FieldValidator：最后一段是 List/Map 不再一律报错；若表达式是 `indexAccess` 或 `get(...)`/`containsKey(...)` 的调用目标，则放行。
   - `customer.address.city` 仍报"不能继续访问内部属性"。

5. **§7.2 ObjectInst**：补充 ListInst / MapInst 不必新类型。运行时 list 就是 JSON array，map 就是 JSON object。`_type` 只出现在 object 元素上，不出现在 list/map 容器上。

#### 4.6.2 RFC-0018 解析器

| 点 | 改动 |
| --- | --- |
| AST | 新增 `IndexAccess { object: Expr, index: Expr }`。加入 whitelist `expressionKinds`。 |
| 文法 | Map 下标；List 下标在校验期拒绝，错误：`List 不支持下标，请使用 get(list, i)` |
| DeclareStmt | `typeRef` 解析为 Primitive 或 ObjectType；生成 Groovy 时输出 `Type name = expr` |
| FieldValidator | context 根可以是 object / list / map，不再假设根一定是 ObjectType。RuleType.arguments 的 Type 可以是 ListType/MapType |
| 调用校验 | 允许 §4.4 函数表；拒绝未知方法；拒绝任何 `CallExpr` 的最后一个参数是闭包/箭头（解析期就应失败，因为文法没有这些节点） |
| 白名单 JSON | `globalFunctions` 增加：`get`、`containsKey`、`containsValue`、`keySet`、`values`（`size`/`len`/`contains`/`isEmpty` 已有） |
| `methodSignatures` | 补 `List.get(int)`、`Map.get(Object)`、`Map.containsKey`、`Map.containsValue`、`Map.keySet`、`Map.values` |

#### 4.6.3 RFC-0019 codegen

| SimpleTS | Groovy |
| --- | --- |
| `let vip: Customer = customersById["alice"]` | `Customer vip = customersById["alice"]` |
| `customersById["alice"]` | `customersById["alice"]` |
| `get(customers, i)` | `customers.get(i)` |
| `size(customers)` | `customers.size()` |
| `keySet(customersById)` | `new ArrayList(customersById.keySet())` |
| `for (let i = 0; i < size(xs); i = i + 1) { ... }` | `for (int i = 0; i < xs.size(); i = i + 1; ) { ... }` |

脚本最前面插入 RFC-0043 §4.2 类型前缀。`class RuleGroovy { static Object execute(Map context) }` 是否保留：本 RFC 系统测试路径是 `JavaSourceExecutor` 直接跑脚本体，**不要**包一层 class。RFC-0019 示例若仍用 class 包装，需注明那是"独立 Groovy 文件形态"，与 execution-service 脚本形态不是同一产物。本 RFC 以 execution-service 为准。

禁止生成 `.any{}` / `.each{}` / `.findAll{}`。

#### 4.6.4 RFC-0020 / SandboxPolicy 同步

- `setClosuresAllowed(false)`
- 允许脚本顶层 `class`/`enum`（执行器注入的前缀）
- 允许 `for`；继续禁止 `while`
- 允许 `java.util.ArrayList`（已在 ALLOWED_PACKAGES）
- `keySet()` 返回 `Set`；codegen 立刻包成 ArrayList，避免规则直接操作 Set 迭代器

> 沙箱实际开关由 RFC-0020 / RFC-0040 维护；本 RFC 只同步策略文本，不引入新开关。

### 4.7 Type 系统配套

v0.1 说"不是 Type 系统 RFC"不成立，因为：

- `Customer vip = ...` 需要 ObjectType `Customer`
- `customers: List<Customer>` 需要 ListType(ObjectRef("Customer"))
- `customersById: Map<string, Customer>` 需要 MapType(Primitive("string"), ObjectRef("Customer"))

配套最小集：

1. **关闭 TD-003 本切片。** `TypeFactory.buildMap` 已支持 value 为 object。补：
   - 元数据种子 / demo Domain 增加 `customers: list<Customer>`、`customersById: map<string, Customer>`
   - RFC-0032 §3.8 TD-003 改为"已由 RFC-0043 放开 map.value=object；仍不支持 map.value=list 或 map.key=object"
2. **RuleType.arguments 允许根为 list/map。** RFC-0018 FieldValidator 现在一上来 `arg.getObjectType()`，会把 `customers` 这种根参数判死。改为 `arg.getType()`，按 Type variant 分支。
3. **FunctionType 种子**增加 RFC-0043 §4.4 函数。签名用现有 `FunctionSignature` JSON，不碰 TD-002 扁平化。
4. **不新增 Type variant。** List/Map/Object/Primitive/Enum(ObjectType.kind) 足够。

### 4.8 与 RFC-0043 的边界

| 主题 | RFC-0043（执行侧） | 本 RFC（语言侧） |
| --- | --- | --- |
| 领域类型前缀 | §4.2 注入契约（test prefix、hydrate T-5） | §4.6.3 codegen 前缀插入调用点 |
| 写回语义 | §4.3 contract + 系统测试覆盖 | 不涉及 |
| List/Map 普通函数语义 | §4.4 函数表 + Sandbox 关 closure | §4.6.2 白名单 + `methodSignatures`；§4.6.3 codegen 映射 |
| Sandbox 关 closure / 允许 class/for | §4.6.4 一致性引用 | §4.6.4 文本同步 |
| `customers: List<Customer>` 等根参数 | 用 RFC-0043 场景 B、C 验证 SimpleTS 形态可解析 | §4.7 TypeFactory + RuleType.arguments 改造 |

---

## 5. 与现有 RFC 的边界

| RFC / 文档 | 本 RFC 的关系 |
| --- | --- |
| RFC-0043 | 本 RFC 是它的语言配套子 RFC。执行契约在 RFC-0043；语法补丁在本 RFC。两边互相引用、互不复制。 |
| RFC-0042 | 不涉及 |
| RFC-0041 | 不涉及 |
| RFC-0040 | 不新增执行器契约；仅在 §4.6.4 同步沙箱文本 |
| RFC-0018 | 本 RFC 给出 FieldValidator / 文法 / 白名单补丁（§4.6.2） |
| RFC-0019 | 本 RFC 给出 declare/index/for/函数的 Groovy 映射（§4.6.3） |
| SimpleTS.md | 本 RFC 给出文法与嵌套约束修订（§4.6.1） |
| RFC-0032 | §3.8 TD-003 关闭本切片（§4.7） |
| RFC-0033 | RuleType.arguments 字段不变；本 RFC 不改 metadata API |

---

## 6. 兼容性

- HTTP API 无变更。
- SimpleTS 旧约束"不能访问 list/map 内部"对 **object 字段** 仍成立；对 **context 根 list/map** 放开。这是语言破坏性变更，必须在 SimpleTS.md 版本记 v0.3。
- 白名单 JSON 字段名、函数签名 schema 不破；新增函数作为既有 key 下的扩展。
- V5 种子迁移：追加新行（list/map 根参数），不回填旧 demo，不破坏既有 `put` 校验。
- 旧规则若使用 `.any{}`，收紧沙箱后会红（由 RFC-0043 §6 负责）。

---

## 7. 测试策略

| 层 | 文件 | 用例 |
| --- | --- | --- |
| 白名单 | `SimpletsWhitelistLoaderTest` | 新增 `get` / `containsKey` / `containsValue` / `keySet` / `values` 反序列化 & 唯一性 |
| 系统（执行侧，归属 RFC-0043） | `ComplexServiceFrameworkedSystemTest` | §4.5 场景 B、C 跑通 SimpleTS 形态（codegen 后） |
| 文档 | SimpleTS.md / RFC-0018 / RFC-0019 / RFC-0032 | 同步本 RFC 补丁（人工 review，不跑测试） |

验收：

```bash
mvn -pl packages/orule-common "-Dtest=SimpletsWhitelistLoaderTest" test
```

SimpleTS 解析器/codegen 代码落地由独立 SimpleTS 模块负责；本 RFC 在该模块出现前只承载文档与白名单改动。

---

## 8. 风险

| 风险 | 缓解 |
| --- | --- |
| SimpleTS 一次放开 List 下标和数组字面量 | 明确只放开 Map `[]` 和 `get(list,i)`（§4.6.1） |
| Map 下标 key 误用为对象 | 与 TypeFactory"map key 必须 primitive"对齐；文档与 FieldValidator 同步（§4.6.2） |
| typed declare 未知类型 | 编译错误 `未声明的类型 'Foo'`；FieldValidator 给出对应 programCode 候选 |
| RuleType.arguments 改 `getType()` 与既有 `getObjectType()` 调用方冲突 | 仅在 FieldValidator 内部替换；其它调用点保留 |
| V5 种子追加 list/map 根参数与既有 demo Key 命名冲突 | 用 RFC-0043 §4.8 demo 命名（`customers` / `customersById`），先勘察 V5 已占用 key |
| `contains(list, customerPojo)` 因 Map vs POJO 永远 false | 与 RFC-0043 §4.4 一致：contains 只保证标量；对象成员用 for + 字段比较 |

---

## 9. 实施路径

| 任务 | 工作量 | 状态 |
| --- | --- | --- |
| T-1 修订 SimpleTS.md / RFC-0018 / RFC-0019 / RFC-0032 / RFC-0020 交叉引用 | 0.3d | 本草稿 |
| T-2 `simplets-whitelist.json` 补 `globalFunctions` 与 `methodSignatures` | 0.1d | TODO |
| T-3 `SimpletsWhitelistLoaderTest` 扩 case | 0.1d | TODO |
| T-4 V5 种子新增 list/map 根参数 | 0.1d | TODO |
| T-5 RFC-0043 §4.6 / §4.7 抽到本 RFC 后，原位置改为引用块 | 0.1d | 本 RFC 起草时同提交 |
| T-6 SimpleTS 解析器/codegen 代码（独立模块出现时） | 随模块 | 文档先行 |

默认先 T-2 / T-3 / T-4 把白名单与种子落到位，T-5 同 RFC-0043 v0.2 → v0.3 提交一起做。

---

## 10. 开放问题

1. **typed declare 的 typeRef 是否允许 enum 类型名？** 建议允许，与 ObjectType programCode 同处理。`let tier: CustomerTier = ...` 应能解析。
2. **白名单 JSON 是否要在 loader 中加 schema 版本字段？** 建议暂不加；本 RFC 字段是纯扩展，未来如破 schema 升级时再处理。
3. **`get(list, i)` 的第二个参数类型是否要校验？** 建议仅校验 `int`，越界交给运行时；与 RFC-0043 §4.4 G-4 对齐。

已拍板（与 RFC-0043 一致）：

- SimpleTS 配套 + Type 系统配套在本 RFC 集中表述。
- 规则禁止 lambda / `any{}`；List/Map 用普通函数。
- `def vip = ...` 改为 `Customer vip = ...`。
- `customers[0]` 仍非法；只能 `get(customers, i)`。
- Map 下标 `customersById["alice"]` 合法。
- 不引入新 Type variant。

---

## 11. 修订历史

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v0.1 | 2026-09-17 | 从 RFC-0043 v0.2 §4.6 + §4.7 抽出，作为独立语言配套 RFC。 |
