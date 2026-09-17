# RFC-0043: 嵌套对象 / List / Map 上下文绑定与属性赋值

> **状态**：DRAFT · **优先级**：P1 · **预计工作量**：1.0d（系统测试 + Groovy 类型前缀 + List/Map 普通函数；SimpleTS / Type 系统配套已抽出到 RFC-0044）
> **关联 RFC**：[RFC-0044 SimpleTS 与 Type 系统配套（嵌套对象 List/Map 上下文绑定）](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)（SimpleTS 配套 + Type 配套已抽出）、[RFC-0040 规则执行服务](RFC-0040-规则执行服务.md)、[RFC-0042 系统测试框架](RFC-0042-系统测试框架.md)、[RFC-0020 Groovy 沙箱](RFC-0020-Groovy沙箱单条规则执行器.md)、[RFC-0018 SimpleTS 解析器](RFC-0018-SimpleTS解析器.md)、[RFC-0019 SimpleTS 转 Groovy](RFC-0019-SimpleTS转Groovy代码生成器.md)、[RFC-0032 ObjectType 枚举化](RFC-0032-ObjectType枚举化与Type系统简化.md)
>
> **v0.2 范围**：规则要写 `customer.tier` / `Customer vip = customersById["alice"]` / `customers.get(i)`，就不能再假装「只是系统测试」。本 RFC 仅改执行器契约 + 写回语义 + 沙箱收紧，并用独立测试类 `ComplexServiceFrameworkedSystemTest` 验收。SimpleTS / Type 配套见 [RFC-0044](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)。**不修改** RFC-0041 / RFC-0042 原版测试。

---

## A. 模块所有权

| 区域 | 范围 |
| --- | --- |
| 顶层 | 文档：`docs/rfcs/RFC-0043-*.md`、`docs/dsl/SimpleTS.md`、`docs/rfcs/README.md`。本仓库无 `cruleengine` / `crulemgr`。 |
| orule-rule-execution-service | Groovy 执行器：脚本前缀注入领域 class/enum；JSON → Map 绑定；`outputContext` 写回。系统测试：`ComplexServiceFrameworkedSystemTest`。沙箱：允许 List/Map 普通方法，**禁止** closure / lambda。 |
| orule-common / orule-server | Type 配套：`map<string, Customer>`（关闭 RFC-0032 TD-003 的本 RFC 切片）；List/Map 普通函数种子（`size`/`get`/`contains`/`containsKey`/`keySet`/`values`）。不改 CRUD API 形态。 |
| SimpleTS（尚未独立模块） | 文法、AST、FieldValidator、Whitelist、codegen 配套：下标、`Customer vip = ...`、`for` 遍历、禁止箭头函数/闭包。当前解析器/codegen 仍是 RFC 草稿，「必须改什么」已抽出到 [RFC-0044 §4.6](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)。 |
| cruleengine / crulemgr | **不涉及**。 |
| 跨模块边界 | 执行服务仍 mock `RuleManagermentApiClient`。规则源码仍是 Groovy 字符串。领域 class 不从 rule-management HTTP 拉取，由执行器按约定前缀注入（见 §4.2）。 |

**结论（推翻 v0.1）**：用户要的 `Customer vip = customersById["alice"]` 和 List/Map 普通函数，已经越出「纯测试 RFC」。本 RFC **就是** Type / SimpleTS / 执行器的配套修改入口，不再把这些推给未写的 RFC。[COMPUTED, 置信度 HIGH]

---

## B. 复用优先

- 现有入口点：`POST /api/v1/rule-executions`；`JavaSourceExecutor` 把 `context` `Binding.setVariable`。
- 现有 helper：`ExecutionInputBuilder.put(key, value)`（嵌套对象必须用它，禁止 `also()`）。
- 现有测试基类：`RuleExecutionSystemTestBase` + `RuleExecutionResult.field(...)`。**不新增基类**。
- 现有 DSL：`mockRule(...).withSource(...)`、`executeRule(...)`、`input().put(...)`。
- 现有 Type 工厂：`TypeFactory.buildMap` 已允许 value 为 object programCode；TD-003 主要是文档/种子缺口，不是工厂缺口。[KNOWN]
- 现有白名单：`simplets-whitelist.json` 已有 `size`/`contains`/`isEmpty`/`len`；RFC-0018 文本白名单还有 `List.get` / `Map.get`。本 RFC 把两边对齐，并补 `containsKey`/`keySet`/`values`。
- 不新增：不新增测试基类；不把 demo POJO 提升为生产 SDK；不引入箭头函数 / Groovy closure 作为规则语法。
- 可由上下文推导的字段：`taskId` / `executedAt` / `durationMs` 仍由服务生成。
- 相似测试：`CustomerServiceFrameworkedSystemTest` 只保留拍平 demo。复杂对象另开 `ComplexServiceFrameworkedSystemTest`。

---

## 1. 摘要

规则作者要写的是对象规则，不是拍平字段：

```text
输入:  customer: Customer
      order: Order
      customers: List<Customer>
      customersById: Map<string, Customer>

规则:  if (customer.tier == CustomerTier.VIP) { order.discount = 30 }
       Customer vip = customersById["alice"]
       for (let i = 0; i < size(customers); i = i + 1) { ... }

输出:  同一个嵌套结构，被改过的字段出现在 outputContext
```

v0.2 同时锁定三件事（本 RFC 不承载语言配套）：

1. HTTP JSON 进来后，嵌套对象先是 `Map`；要让 `Customer vip = ...` 成立，Groovy 脚本必须带领域 class/enum 前缀。
2. 规则 **禁止** `any { }` / 箭头函数 / Groovy closure；List/Map 只用普通函数 + `for`。
3. 系统测试独立为 `ComplexServiceFrameworkedSystemTest`，不污染 RFC-0042 的 Customer demo。

SimpleTS / Type 配套改动抽出到 [RFC-0044](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)，本 RFC 不再承载。

---

## 2. 动机与反驳

**反驳（先说）**：v0.1「只测不改」是错的。[COMPUTED, 置信度 HIGH]

| 事实 | 来源 |
| --- | --- |
| `JavaSourceExecutor` 只把 JSON 反序列化后的 Map 放进 Binding | 现有代码 |
| 没有领域 class 时，`Customer vip = map` 无法编译 | Groovy 语义 |
| 脚本里有 `class Customer` 后，`Customer vip = customersById["alice"]` **可以**把 Map 强制成 POJO，且 `tier` 会变成 enum | 本次探针 |
| 强制得到的 POJO **不是**原 Map；`vip.tagged = true` **不会**写回 `customersById` | 本次探针 |
| 直接改 Map（`order.discount = 30`、`customersById["alice"].tagged = true`）会写回 | 本次探针 |
| Groovy closure `customers.any { ... }` 能跑，但用户明确禁止 | 用户指令 |
| SimpleTS 文法现在砍掉数组下标，FieldValidator 禁止访问 list/map 内部元素 | RFC-0018 / SimpleTS.md |
| `TypeFactory.buildMap` 已能表达 `map<string, Customer>` | `TypeFactory.java` |

因此正确范围是：**执行器注入类型前缀 + 规则只用普通 List/Map 函数 + SimpleTS/Type 放开必要表面 + 独立系统测试把契约钉死**。

---

## 3. 设计目标

| 编号 | 目标 | 验收 |
| --- | --- | --- |
| **G-1** | 对象整体进入 binding | `input().put("customer", alice).put("order", order)` |
| **G-2** | 规则可读/写对象属性 | `customer.tier`、`order.discount = 30` |
| **G-3** | 局部变量必须带类型 | `Customer vip = customersById["alice"]`，禁止规则里 `def vip = ...` |
| **G-4** | List 用普通函数，不用 lambda | `size(customers)` / `customers.get(i)` / `contains(...)` + `for` |
| **G-5** | Map 用普通函数 + 下标 + 遍历 | `customersById["alice"]`、`containsKey`、`keySet`/`values` + `for` |
| **G-6** | 枚举比较按生成类的 enum 身份 | 有类型前缀后 `vip.tier == CustomerTier.VIP` 为 true |
| **G-7** | 独立系统测试类 | `ComplexServiceFrameworkedSystemTest`；不改 `CustomerServiceFrameworkedSystemTest` |
| **G-8** | SimpleTS 配套在 [RFC-0044](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md) 写完 | 下标、typed declare、List/Map 函数、禁止闭包 |
| **G-9** | Type 配套在 [RFC-0044](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md) 写完 | 种子含 `List<Customer>`、`Map<string, Customer>`；补齐函数 |

非目标：

- 不把 Groovy closure / Java lambda / TS 箭头函数纳入规则语言。
- 不实现完整 RFC-0040 ObjectInst 深拷贝框架；本 RFC 只做「脚本前缀 + Map 绑定 + 写回规则」。
- 不修改 RFC-0042 拍平 `also()` demo。
- 不解决 RFC-0032 TD-001/TD-002。

---

## 4. 详细设计

### 4.1 运行时数据路径

```
测试 POJO / List / Map
    → input().put(key, value)                 // 保持嵌套
    → HTTP JSON
    → context: Map<String,Object>             // 嵌套仍是 LinkedHashMap
    → JavaSourceExecutor 注入领域 class/enum 前缀
    → Binding.setVariable
    → 规则：类型声明会把 Map 强制成 POJO；直接属性赋值改的是 Map
    → outputContext JSON
```

[COMPUTED, 置信度 HIGH] Jackson 默认：

| 测试侧 | JSON | Binding 实际类型 |
| --- | --- | --- |
| `Customer` POJO | object | `LinkedHashMap` |
| `CustomerTier.VIP` | `"VIP"` | `String`（强制成 `Customer` 后变成 enum） |
| `List<Customer>` | array | `ArrayList<LinkedHashMap>` |
| `Map<String, Customer>` | object | `LinkedHashMap<String, LinkedHashMap>` |

### 4.2 领域类型前缀（执行器必须改）

`Customer vip = ...` 要编译，脚本 classpath/源码里必须有 `Customer`。v0.2 采用 **源码前缀**，不引入独立编译的 SDK jar：

```groovy
enum CustomerTier { VIP, GOLD, SILVER, BRONZE }

class Customer {
    String name
    CustomerTier tier
    Boolean tagged
}

class Order {
    Integer totalAmount
    Integer discount
}

// ---- 规则体开始 ----
Customer vip = customersById["alice"]
if (vip.tier == CustomerTier.VIP && order.totalAmount >= 200) {
    order.discount = 20
    customersById["alice"] = vip
}
```

约束：

1. v0.2 系统测试把前缀写在 `withSource(...)` 里，与规则体一起 mock。这先锁定「类型前缀是执行契约」。
2. 生产路径（非本测试 RFC 的后续切片，但仍属本 RFC 实施 T-2）：`JavaSourceExecutor` 按规则用到的 ObjectType/Enum 拼同样前缀。测试阶段可用固定 Customer/Order 前缀；不要为此先做通用代码生成框架。
3. 沙箱必须允许这些合成 class。它们不是 `java.util.*` import，而是脚本顶层 class。`SandboxPolicy.setMethodDefinitionAllowed(true)` 已允许方法定义；需确认顶层 class/enum 不被 SecureAST 拒绝。若拒绝，本 RFC 允许放宽「脚本内 class/enum」，**不允许**放开 `groovy.lang.GroovyShell`。[INFERRED, 置信度 MED]
4. 禁止规则作者自己写 `class` / `enum`。前缀由执行器/测试夹具注入。SimpleTS 仍砍掉用户侧 `class`/`enum` 关键字。

### 4.3 写回语义（必须写进契约）

探针结论，升格为规范：

| 写法 | 是否写回 `outputContext` 原槽位 |
| --- | --- |
| `order.discount = 30`（`order` 仍是 Map） | 是 |
| `customersById["alice"].tagged = true`（不经过类型局部变量） | 是 |
| `Customer vip = customersById["alice"]; vip.tagged = true` | **否**。`vip` 是拷贝出来的 POJO |
| `Customer vip = ...; vip.tagged = true; customersById["alice"] = vip` | 是，但该槽位变成 POJO；JSON 输出仍是 object |

因此规则作者规范：

- 只改顶层对象字段：直接 `order.discount = 30`。
- 从 List/Map 取出并改字段：改完必须赋回，或直接对 `list.get(i).field` / `map[key].field` 赋值，不要指望类型局部变量自动写回。
- 系统测试两个都要覆盖。

Jackson 输出 POJO 时走 getter，和 Map 一样变成 JSON object，断言仍用 JSON pointer。

### 4.4 禁止 lambda；List/Map 只用普通函数

规则语言 **不允许**：

```groovy
customers.any { it.tier == "VIP" }      // 禁止
customers.findAll { ... }               // 禁止
customersById.each { k, v -> ... }      // 禁止
(x) => x.tier == "VIP"                  // 禁止
```

允许的普通函数（v0.2 最小集）：

| 函数 | 作用对象 | 语义 | Groovy 落地 |
| --- | --- | --- | --- |
| `size(x)` / `len(x)` | List / Map / String | 元素个数 | `x.size()` |
| `isEmpty(x)` | List / Map / String | 是否空 | `x.isEmpty()` |
| `get(list, i)` | List | 按下标取元素，越界运行时错误 | `list.get(i)` |
| `contains(list, item)` | List | 是否包含（item 为标量时可用） | `list.contains(item)` |
| `containsKey(map, key)` | Map | 是否含键 | `map.containsKey(key)` |
| `containsValue(map, value)` | Map | 是否含值（value 建议标量） | `map.containsValue(value)` |
| `keySet(map)` | Map | 键列表 | `new ArrayList(map.keySet())` |
| `values(map)` | Map | 值列表 | `new ArrayList(map.values())` |

下标是语法不是函数：

- List：v0.2 **不**开放 `customers[i]`（避免和 SimpleTS 数组字面量一次全放开）。只用 `get(customers, i)` 或 `customers.get(i)`。
- Map：开放 `customersById["alice"]` 和 `customersById.get("alice")`。

遍历只用已有 `for`：

```ts
for (let i = 0; i < size(customers); i = i + 1) {
    Customer c = get(customers, i)
    if (c.tier == CustomerTier.VIP) {
        order.discount = 30
    }
}
```

```ts
let keys = keySet(customersById)
for (let i = 0; i < size(keys); i = i + 1) {
    let k = get(keys, i)
    Customer c = customersById[k]
    if (c.tier == CustomerTier.VIP) {
        c.tagged = true
        customersById[k] = c
    }
}
```

沙箱：`setClosuresAllowed(false)`。这是对 RFC-0020/0040 现状的收紧。`CustomerServiceFrameworkedSystemTest` 现有源码没有 closure，应收紧后仍绿。[INFERRED, 置信度 HIGH]

`for`：RFC-0020 把死循环列为风险，但 SimpleTS 文法已有 `forStmt`，RFC-0019 也会 `generateFor`。v0.2 **允许** C 风格 `for`，靠执行超时（已有 `RuleExecutorService` timeout）而不是禁 `for`。禁止 `while` 仍可保留。

### 4.5 场景与独立测试类

新文件：

```text
packages/orule-rule-execution-service/src/test/java/com/orule/rule/execution/systemtest/ComplexServiceFrameworkedSystemTest.java
```

包、基类、注解风格对齐 `CustomerServiceFrameworkedSystemTest`。POJO 放这个类内部：`Customer`、`Order`、`CustomerTier`。**不要**复用 RFC-0042 那个拍平 `Customer(name, age, vip)`。

#### 场景 A：嵌套对象读写

```java
@Test
@DisplayName("nested object: read customer.tier, write order.discount")
void nested_object_readAndWrite() {
    mockRule("ORDER_VIP_DISCOUNT").withSource(prefix() + """
            if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                order.discount = 30
            }
            """);
    RuleExecutionResult r = executeRule("ORDER_VIP_DISCOUNT",
            input().put("customer", new Customer("alice", CustomerTier.VIP))
                   .put("order", new Order(250, 0)));
    r.isOk()
     .field("/outputContext/order/discount", is(30))
     .field("/outputContext/order/totalAmount", is(250))
     .fieldString("/outputContext/customer/tier", equalTo("VIP"));
}
```

`customer.tier` 在未类型化时是 `"VIP"` 字符串；有前缀 class 且 Groovy 对顶层 Map 做属性读时，`== CustomerTier.VIP` **仍可能是 false**（String vs enum）。[COMPUTED, 置信度 HIGH]

所以场景 A 的比较必须先局部类型化，或执行器在 bind 前把已知 object 槽位 hydrate 成 POJO。v0.2 选后者作为生产目标，测试分两档：

- **A1（hydrate 后）**：`customer` 已是 `Customer`，`customer.tier == CustomerTier.VIP` 为 true。这是目标契约。
- **A0（未 hydrate 对照）**：若 T-2 尚未做 hydrate，只注入 class 前缀、context 仍是 Map，则规则写成：

```groovy
Customer c = customer
if (c.tier == CustomerTier.VIP && order.totalAmount >= 200) {
    order.discount = 30
}
```

实施顺序：先让 A0 绿，再做 bind 前 hydrate 让 A1 绿。不要把 A1 在 hydrate 完成前标成已完成。

#### 场景 B：List，无 lambda

```java
@Test
@DisplayName("list of objects: for + get, no closure")
void list_customers_forGet() {
    mockRule("ORDER_LIST_VIP_DISCOUNT").withSource(prefix() + """
            for (int i = 0; i < customers.size(); i = i + 1) {
                Customer c = customers.get(i)
                if (c.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                    order.discount = 30
                }
            }
            """);
    RuleExecutionResult r = executeRule("ORDER_LIST_VIP_DISCOUNT",
            input().put("customers", List.of(
                            new Customer("alice", CustomerTier.VIP),
                            new Customer("bob", CustomerTier.GOLD)))
                   .put("order", new Order(250, 0)));
    r.isOk()
     .field("/outputContext/order/discount", is(30))
     .fieldString("/outputContext/customers/0/name", equalTo("alice"));
}
```

负例：源码含 `customers.any { ... }` → 编译失败（closure 关闭）或测试框架断言 EVAL_FAILED。

#### 场景 C：Map 取值 + 遍历，无 lambda

```java
@Test
@DisplayName("map of objects: typed local + keySet for")
void map_customersById_typedLocalAndTraverse() {
    mockRule("ORDER_MAP_VIP_DISCOUNT").withSource(prefix() + """
            Customer vip = customersById["alice"]
            if (vip.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                order.discount = 20
                vip.tagged = true
                customersById["alice"] = vip
            }
            def keys = new ArrayList(customersById.keySet())
            for (int i = 0; i < keys.size(); i = i + 1) {
                String k = keys.get(i)
                Customer c = customersById[k]
                if (c.tier == CustomerTier.GOLD) {
                    c.tagged = false
                    customersById[k] = c
                }
            }
            """);
    RuleExecutionResult r = executeRule("ORDER_MAP_VIP_DISCOUNT",
            input().put("customersById", Map.of(
                            "alice", new Customer("alice", CustomerTier.VIP),
                            "bob", new Customer("bob", CustomerTier.GOLD)))
                   .put("order", new Order(250, 0)));
    r.isOk()
     .field("/outputContext/order/discount", is(20))
     .field("/outputContext/customersById/alice/tagged", is(true))
     .field("/outputContext/customersById/bob/tagged", is(false));
}
```

系统测试源码可以暂时是 Groovy（`int i`、`ArrayList`），因为当前执行入口就是 Groovy 字符串。SimpleTS 对应源码见 §4.6，codegen 落地后应生成与上等价、且 **不含** `def` 的代码。测试在 codegen 未落地前允许 Groovy 形态；注释标明「SimpleTS 目标形态见 RFC-0044 §4.6」。

### 4.6 SimpleTS 表面补丁（指向 RFC-0044）

> SimpleTS 文法、AST、FieldValidator、白名单、codegen 配套见 [RFC-0044 §4.6](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)。本节仅保留执行器侧依赖的关键决定。

- 文法放开 Map 下标 `customersById["alice"]`；List 下标仍被拒，仅 `get(customers, i)`。
- `let vip: Customer = ...` 经 codegen 输出 `Customer vip = ...`。
- `simplets-whitelist.json` 增 `get` / `containsKey` / `containsValue` / `keySet` / `values`；`methodSignatures` 补 List/Map 方法。
- Sandbox 一致性：`setClosuresAllowed(false)`；允许脚本顶层 `class`/`enum`（前缀）；允许 `for`、禁止 `while`。


### 4.7 Type 系统配套（指向 RFC-0044）
本节仅说明执行器侧为何需要这些 Type 改动。详细清单见 [RFC-0044 §4.7](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)。

- `Customer vip = customersById["alice"]` 要求 ObjectType `Customer` 已声明；白名单要看到 programCode。
- `customers: List<Customer>` / `customersById: Map<string, Customer>` 要求 RuleType.arguments 接受 ListType/MapType 作为根类型。
- `map<string, Customer>` 价值缺口由 RFC-0032 §3.8 TD-003 在 RFC-0044 中关闭本切片；map.value=list、map.key=object 仍不放开。

### 4.8 Demo POJO（只存在于 Complex 测试类）
```java
public enum CustomerTier { VIP, GOLD, SILVER, BRONZE }
public static class Customer {
    private String name;
    private CustomerTier tier;
    private Boolean tagged;
    public Customer() {}
    public Customer(String name, CustomerTier tier) {
        this.name = name; this.tier = tier;
    }
    public String getName() { return name; }
    public CustomerTier getTier() { return tier; }
    public Boolean getTagged() { return tagged; }
}
public static class Order {
    private int totalAmount;
    private int discount;
    public Order() {}
    public Order(int totalAmount, int discount) {
        this.totalAmount = totalAmount; this.discount = discount;
    }
    public int getTotalAmount() { return totalAmount; }
    public int getDiscount() { return discount; }
}
```
`prefix()` 测试 helper 返回与这些字段同构的 Groovy class/enum 字符串。不要和 RFC-0042 `Customer(name,age,vip)` 混名。
### 4.9 断言
- 只用 `/outputContext/...` JSON pointer。
- 不要对整个 object 做 `is(...)`。
- 数字先 `is(30)`；若 Jackson 把整数变成 Long 再改 `coerce`。
- 枚举在 JSON 里是 `"VIP"`，用 `fieldString`。
### 4.10 可选单元测试
`JavaSourceExecutorTest`：
1. 前缀 + `Customer vip = map` 后 `vip.tier == CustomerTier.VIP`
2. 无赋回时 `vip.tagged = true` 不污染原 map
3. 含 closure 的源码编译失败
不替代系统测试。
---
## 5. 与现有 RFC 的边界
| RFC / 文档 | 本 RFC 的关系 |
| --- | --- |
| RFC-0042 | 复用 framework；**不往** `CustomerServiceFrameworkedSystemTest` 加复杂场景 |
| RFC-0041 | 不改原版测试 |
| RFC-0040 | 补上「领域类型进入执行」的最小实现；仍不做通用 ObjectInst 框架 |
| RFC-0044 | 本 RFC 的语言配套子 RFC；SimpleTS 表面、Type 系统配套见该 RFC §4.6/§4.7 |
| RFC-0018 / RFC-0019 / RFC-0032 / SimpleTS.md / RFC-0020 | 由 RFC-0044 给出具体补丁；RFC-0044 §5 列出对应关系 |
---
## 6. 兼容性
- HTTP API 无变更。`context` 仍是 JSON object。
- 拍平 `also()` 保留。
- 旧规则若使用 `.any{}`，收紧沙箱后会红。当前仓库系统测试没有这种规则。[KNOWN]
- SimpleTS 旧约束「不能访问 list/map 内部」对 **object 字段** 仍成立；对 **context 根 list/map** 放开。这是语言破坏性变更，必须在 SimpleTS.md 版本记 v0.3。
---
## 7. 测试策略
| 层 | 文件 | 用例 |
| --- | --- | --- |
| 系统 | `ComplexServiceFrameworkedSystemTest` | A0/A1 嵌套对象；B List+for+get；C Map 类型局部变量 + keySet 遍历；C' closure 被拒 |
| 回归 | `CustomerServiceFrameworkedSystemTest` | 必须仍绿 |
| 单元 | `JavaSourceExecutorTest` / `SandboxPolicyTest` | 前缀强制、写回、closure 拒绝 |
| 文档 | SimpleTS.md / RFC-0018 / RFC-0019 / RFC-0032 / RFC-0044 | 同步 RFC-0044 配套补丁 |
验收：
```bash
mvn -pl packages/orule-rule-execution-service "-Dtest=ComplexServiceFrameworkedSystemTest,CustomerServiceFrameworkedSystemTest,SandboxPolicyTest" test
```
SimpleTS 解析器/codegen 尚未独立模块时，语言配套先改文档；代码落地随这些模块出现时按 RFC-0044 §4.6 实施。
---
## 8. 风险
| 风险 | 缓解 |
| --- | --- |
| 类型局部变量不写回，业务以为改了 list/map 元素 | §4.3 写成契约；C 场景强制赋回；另做「直接 `map[k].field =`」对照 |
| `customer.tier == CustomerTier.VIP` 在未 hydrate 时为 false | A0/A1 分阶段；禁止再把字符串比较当目标契约 |
| 顶层 class 被 SecureAST 拒绝 | 先用 SandboxPolicyTest 探针；只放宽 class/enum |
| 放开 `for` 导致死循环 | 已有执行超时；禁止 `while` |
| `keySet` 返回 Set，规则不好用 | codegen 包 ArrayList |
| `contains(list, customerPojo)` 因 Map vs POJO 永远 false | 文档写明 contains 只保证标量；对象成员用 for + 字段比较 |
| 通用 hydrate 做成小框架 | 禁止。v0.2 只服务 Customer/Order 测试域 + 通用「按 ObjectType 生成 class 文本」函数，不要 BeanMapper |
| SimpleTS 一次放开 List 下标和数组字面量 | 明确只放开 Map `[]` 和 `get(list,i)` |
---
## 9. 实施路径
| 任务 | 工作量 | 状态 |
| --- | --- | --- |
| T-1 修订本 RFC：抽 §4.6 / §4.7 到 [RFC-0044](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)；更新 README 索引 | 0.1d | DONE（v0.3） |
| T-2 `ComplexServiceFrameworkedSystemTest`：A0、B、C、closure 负例 | 0.4d | TODO |
| T-3 `SandboxPolicy`：关 closure；确认 class/enum；补 List/Map 方法 | 0.2d | TODO |
| T-4 执行器类型前缀（测试域 Customer/Order；可内联在测试 source） | 0.2d | TODO |
| T-5 bind 前 hydrate（A1）：JSON Map → 前缀 class 实例 | 0.4d | TODO，可后于 T-2 |
| T-6 Type 种子：list/map 根参数 + 函数 | 0.2d | TODO |
| T-7 回归 RFC-0042 测试 | 0.1d | TODO |
| T-8 SimpleTS 解析器/codegen 代码 | 随模块出现 | 文档先行 |
默认先 T-2 用「source 内含 prefix」让系统测试绿，再决定 T-5 hydrate 是否同一提交。没有 hydrate 时不得声称 A1 已完成。
---
## 10. 开放问题
1. **hydrate 是否进 v0.2 同一提交？** 建议：T-2 先绿（A0），T-5 紧随其后。A1 是产品目标，不能永远停在 `Customer c = customer`。
2. **Groovy 源码要不要继续允许 `customers.get(i)` 这种 Java 方法形态？** 建议允许，作为 SimpleTS `get(customers, i)` 的 codegen 结果；规则作者文档只展示 SimpleTS 形态。
3. **Map 下标的 key 是否只允许 string？** 建议是。与 TypeFactory「map key 必须 primitive」一致；demo 只用 string。
4. **`tagged` 是否要进正式 Customer ObjectType？** 测试可以有；种子 ObjectType 不必为了 demo 加无关字段。C 场景可改为写已有字段，或只改 `order.discount`。
已拍板（用户本轮）：
- 需要改 Type / SimpleTS 时，配套写在 [RFC-0044](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)。
- 规则禁止 lambda / `any{}`；List/Map 用普通函数。
- `def vip = ...` 改为 `Customer vip = ...`。
- 新测试类 `ComplexServiceFrameworkedSystemTest`。
---
## 11. 修订历史
| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v0.1 | 2026-09-16 | 初稿。误判为纯系统测试 RFC；List 示例用了 `any{}`。 |
| v0.2 | 2026-09-16 | 推翻「不是 Type/SimpleTS RFC」。补领域类型前缀、写回语义、List/Map 普通函数、SimpleTS/Type 配套、独立 `ComplexServiceFrameworkedSystemTest`。 |
| v0.3 | 2026-09-17 | §4.6 SimpleTS 配套 / §4.7 Type 系统配套抽出到 [RFC-0044](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md)；本 RFC 仅保留执行契约 + 写回语义 + 系统测试。 |