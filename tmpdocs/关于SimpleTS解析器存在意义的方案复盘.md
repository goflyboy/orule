# 关于"SimpleTS 解析器的存在意义"的方案复盘

> **触发**：与用户在 `RFC-0018-SimpleTS解析器.md` 上的对话
> **时间**：2026-09-12
> **关联**：
> - [RFC-0018-SimpleTS解析器.md](../rfcs/RFC-0018-SimpleTS解析器.md)
> - [RFC-0019-SimpleTS转Groovy代码生成器.md](../rfcs/RFC-0019-SimpleTS转Groovy代码生成器.md)
> - [docs/dsl/SimpleTS.md](../dsl/SimpleTS.md)
> - [ADR-003 中间态DSL采用SimpleTS.md](../adr/ADR-003-中间态DSL采用SimpleTS.md)
> - [ADR-006 规则源语言采用SimpleTS.md](../adr/ADR-006-规则源语言采用SimpleTS.md)
> - [ADR-009 SimpleTS为中心的星型转换架构.md](../adr/ADR-009-SimpleTS为中心的星型转换架构.md)

**结论先行**：

> 用户提出的质疑**部分成立、部分不成立**。
>
> - 成立的部分：**"我们已经在 TS 生态里，为什么不直接复用通用 TS 解析器"**这个直觉方向是对的，RFC-0018 §3.9 实际上就是按这个方向做的（GraalJS 调 TypeScript Compiler API）。
> - 不成立的部分：**"解析器的核心目的只是为了对接 Groovy 代码生成器"**。SimpleTS 解析器的真正价值不是"TS→Groovy 翻译"，而是 **"在 TS 语法上强制加一层 DomainMeta 语义约束 + 多引擎 codegen 的中间 IR"**。这一步不能由任何通用解析器替代，是 SimpleTS 这门"受限 DSL"成立的根。
>
> 下面把这条推理链完整展开。**本文不改 RFC，仅供方案讨论**。

---

## 1. 把用户的两个疑问拆开

用户的两个问题其实是两件事，需要分别回答：

| 编号 | 用户的原话 | 实际指向的问题 |
|------|----------|----------------|
| Q1 | "选 SimpleTS 是想用 TS 技术栈，为什么还需要解析器？" | **既然语法就是 TS，TypeScript Compiler 本身不就是'解析器'吗？还要自己再写一遍？** |
| Q2 | "解析器的核心目的是不是对接 Groovy 代码生成器？为什么不用通用开源翻译器？" | **既然目标产物是 Groovy，为什么不直接用一个 TS→Groovy 翻译器，把 SimpleTS 当透明层省掉？** |

两条问题叠加在一起，背后的潜台词其实是：

> "能不能把 RFC-0018 + RFC-0019 合并成一个工具？直接 TS 源码进来，Groovy 代码出去，中间不落 SimpleTS-AST？"

下面分别拆这两条。

---

## 2. Q1 拆解：TS 技术栈已经有了，"解析器"到底在解什么？

### 2.1 用户没说错的部分

RFC-0018 §3.9 已经承认了这一点：**SimpleTS 的词法/语法解析不是自己实现的**，是 **通过 GraalJS 桥接 TypeScript Compiler API** 拿到的 TS AST（`ts.SyntaxKind` 树）。换句话说：

- 词法 → **TypeScript Compiler 干**
- 语法树 → **TypeScript Compiler 干**
- SimpleTSParser **不重新发明这两个轮子**。

所以用户说"既然选了 TS 技术栈，词法/语法不该自己写"——这条 **RFC 已经做到了**。这部分没有争议。

### 2.2 但"解析器"这一层远不止词法/语法

RFC-0018 §3.2 的入口 `SimpleTSParser.parse()` 完整流程是：

```java
// 1. 词法 / 语法（基于 TypeScript Compiler API 通过 GraalJS 调用）
TsSourceFile tsAst = parseToTsAst(source);

// 2. 白名单剪枝 ← 这步是 SimpleTS 专属
Program program = pruner.convert(tsAst, meta);

// 3. 标识符作用域解析
resolver.resolve(program, meta);

// 4. 字段存在性 + 枚举值校验
fieldValidator.validate(program, meta);

// 5. 类型对齐
typeChecker.check(program, meta);

// 6. 左值合法性
lvalueChecker.check(program);
```

**第 2 步到第 6 步才是 SimpleTS 的真东西**，也是任何通用 TS 解析器都替代不了的部分。展开：

#### 2.2.1 白名单剪枝（RFC-0018 §3.5 / SimpleTS §5 §6）

依据 SimpleTS.md §5 的"砍掉的语法"清单，把完整 TS AST 砍成受限 AST 子集：

- 砍 `var` / `const` / `class` / `extends` / `this` / `super` / `new`
- 砍 `try` / `async` / `await` / `Promise`
- 砍 对象字面量、数组字面量、箭头函数、解构、可选链 `?.`、空合并 `??`
- 砍 `import` / `export` / `module`
- 砍 `while` / `do-while` / `switch` / `break` / `continue`
- 砍 三元 `?:`、位运算、`typeof` / `instanceof` / `in`
- 砍 `delete` / `with`

**这件事没有任何通用 TS 解析器会替你做**。它们的目标是"完整 TS 兼容"，你得自己实现剪枝逻辑。这块代码量不算小（RFC-0018 §3.5 的 `WhitelistPruner` 大约 100~150 行 Java）。

#### 2.2.2 基于 DomainMeta 的语义校验（RFC-0018 §3.6）

这一层是 SimpleTS **作为 DSL 而非编程语言** 的本质差异。SimpleTS.md §7.1 的校验项：

| 校验 | 数据源 |
|------|--------|
| 标识符未声明 | `DomainMeta.context` 入口变量清单 |
| 字段不存在 | `EntityDef.fields` |
| 字段只读 | `EntityField.writable === false` |
| 枚举值非法 | `EnumType.values`（内联在 AttributeField.type 中，RFC-0031） |
| object/list/map 字段下钻拦截 | `EntityField.type.kind` |
| 类型不匹配 | RFC-0031 的 5 个 Variant 推导 |
| 方法不在白名单 | `SimpleTSWhitelist.allowedMethodNames()` |

**DomainMeta 是元数据 API（RFC-0015）按规则版本动态注入的**。换句话说，每条规则的"它能写什么"不一样：VIP 满减规则看的是 order 域，规则 A 看的是 risk 域。**通用 TS 解析器根本不知道 DomainMeta 是什么，更不会拿它做字段校验**。

#### 2.2.3 产出 SimpleTS-AST（受限、语义锚定）

经过剪枝和校验后，产出的是 **SimpleTS 专有的 13 个 AST 节点**（`Program / Block / IfStmt / ForStmt / DeclareStmt / AssignStmt / ExprStmt / BinaryExpr / UnaryExpr / Literal / MemberAccess / CallExpr / EnumRef`，见 SimpleTS §6）。

这个 AST **不是 TS AST 的子集**——它丢了几乎所有 TS 语义信息，**绑死了 SimpleTS 这门 DSL 的语义模型**。比如：

- `AssignStmt.target` 在类型层钉死为 `MemberAccess`（杜绝 `x = 1` / `a[0] = 1`）
- `MemberAccess.root` 必须是 `DomainMeta.context` 声明的入口变量
- `EnumRef` 单独抽出来，跟 TS 的 `PropertyAccessExpression` 区分

**这种"语义锚定的 AST"，是任何通用 TS 解析器都不可能给你的**——因为它根本不是 TS 的合法形态。

### 2.3 Q1 的最终回答

> 选 TS 技术栈 → 词法/语法用 TypeScript Compiler API（GraalJS 桥接），**自己不需要重写**。
> 但 "SimpleTS 解析器" 的本质是 **TS AST → 受限 SimpleTS-AST 的剪枝器 + 语义校验器**，这一步 **没有通用替代**。
>
> 命名上的歧义可能是这次误会的源头：把它叫"parser"容易让人误以为是"语法分析器"。准确的命名应该是 **"SimpleTS 编译器前端"**（剪枝 + 校验），而 `parseToTsAst` 那一步才是真正意义的 parser。

---

## 3. Q2 拆解：既然目标是 Groovy，为什么不直接 TS→Groovy 翻译？

### 3.1 这是最关键的设计岔路，必须严肃回答

用户的潜台词：**"SimpleTS → Groovy" 不就是"语法翻译"吗？直接用 TS 编译器把 TS 编译成 JS，再用一个 JS→Groovy 的翻译器不就行了？"**

如果这条路能走通，SimpleTS-AST、WhitelistPruner、FieldValidator 全都可以省掉。但实际上**这条路走不通**，有三个根因。

### 3.2 根因 1：TS 与 Groovy 的语义不对齐（"翻译器视角"看不见的差异）

通用翻译器的假设是"源语言 ⊇ 目标语言"。**TS ⊉ Groovy**，至少有以下不对齐：

| TS 语义 | Groovy 现实 | 翻译器坑 |
|---------|-------------|----------|
| `null` / `undefined` | Groovy 用 `null`，但有 **NullPointerException 守卫风格差异** | 编译期允许，运行期 NPE |
| `==` | Groovy `==` 走 `equals()`；TS `==` 走 `===` 严格相等 | **VIP 满减 `customer.tier == CustomerTier.VIP` 会变成 enum 引用相等**，实际可以；但 `null == undefined` 这种就崩 |
| 闭包 / 箭头函数 | Groovy 闭包语义不同（`owner` / `delegate` / `thisObject`） | SimpleTS 砍掉了闭包，但通用翻译器不砍 |
| 对象字面量 `{a:1}` | Groovy 用 `[a:1]`（Map 字面量） | **结构不对**，需要手工映射 |
| 模板字符串 `` `${x}` `` | Groovy `"${x}"` 写法相近，但 GString 不可哈希 | 类型系统差异 |
| `instanceof` / `typeof` | Groovy 没 `typeof`，`instanceof` 走 `isInstance()` | 行为差异 |
| `switch` / 三元 `?:` | 语法对应但语义有微差 | 翻译器需要做"形态变换" |

**通用翻译器在每个坑点都会给你一个"编译通过但语义错"的结果**。SimpleTS 通过白名单剪枝 **从源头就砍掉了绝大多数坑**——`switch` / 三元 / 模板字符串 / 对象字面量 全部不允许，根本不需要翻译器处理。

### 3.3 根因 2：Groovy 沙箱要求"生成代码可被静态审计"

RFC-0020（Groovy 沙箱单条规则执行器）的核心是 **SecureASTCustomizer / deny-unless-allow**：

- 只允许调用 `SimpleTSWhitelist.allowedMethodSignatures()` 里的方法签名
- 拒绝 `System.exit` / `Runtime.exec` / `Class.forName` / `ProcessBuilder`
- 拒绝 `java.io.File` / `java.net.Socket` 等危险包
- 拒绝 import `java.lang.reflect.` / `java.nio.file.` / `sun.` 等

**这条白名单必须在"源码"层就对得上**。RFC-0018 §3.10 引入了 `SimpleTSWhitelist` 作为单一来源，解析器和沙箱共享同一份白名单：

```java
// RFC-0018 §3.5 用 SimpleTSWhitelist.allowedMethodNames() 检查方法调用
// RFC-0020 §3.2 用 SimpleTSWhitelist.allowedMethodSignatures() 配置沙箱
```

如果跳过 SimpleTS-AST、直接用通用翻译器，**翻译产物的 Groovy 代码可能含翻译器自己注入的 helper 方法**（比如 `Objects.equals()`、`Optional.ofNullable()`、`Preconditions.checkNotNull()`），这些会撞穿沙箱。

**SimpleTS-AST 的价值**在于：它的形态是**纯白名单可控的**——剪枝后的 AST 只含 `IfStmt / ForStmt / BinaryExpr / MemberAccess / CallExpr`，没有 helper、没有隐式装箱，**codegen 出来的 Groovy 代码是确定可控的**，可以过沙箱审计。

### 3.4 根因 3：多引擎 codegen 共享同一个 IR

ADR-009 已经明确 SimpleTS 是**星型中心**，目标产物不止 Groovy：

```
SimpleTS-AST ─→ Groovy   （RFC-0019，第一目标）
              ─→ DRL      （Drools，二期）
              ─→ Python   （未来）
              ─→ 决策日志   （trace JSON）
```

**SimpleTS-AST 是这 N 条 codegen 路径共享的 IR**。如果跳过 AST 直接做 TS→Groovy 翻译：

- DRL 路径需要重新写一个 TS→DRL 翻译器（if 链 + 赋值 → when/then）
- Python 路径再写一个 TS→Python 翻译器
- 每多一个引擎 = 多一份翻译器维护成本

而 SimpleTS-AST 把"翻译一次"变成"语义剪枝一次 + N 个 codegen"，**N 个引擎共享剪枝与校验**，这是架构上的关键选择。ADR-009 的"备选方案 2：以 TS-AST 为中心"也明确否决了类似思路（理由：TS-AST 是实现细节，不是用户可见视图；TS-AST 翻译 Groovy 同样会引入 helper 污染沙箱）。

### 3.5 Q2 的最终回答

> SimpleTS→Groovy 翻译器**可以做**，但**做了之后还有 3 个东西绕不开**：
> 1. **白名单剪枝**（拒绝 switch / 三元 / 对象字面量 / 箭头函数...）—— 这是 TS 解析器不会替你做的；
> 2. **DomainMeta 语义校验**（字段存在性 / 枚举值 / 只读 / object 下钻拦截）—— 这是 TS 解析器根本不知道的；
> 3. **沙箱安全审计**（生成 Groovy 代码必须能被 SecureASTCustomizer 静态审计）—— 通用翻译器的 helper 注入会击穿沙箱。
>
> 这 3 件事合并起来，就是 RFC-0018 的 SimpleTSParser。**它不是为了"对接 Groovy"而存在的，它是为了"在 TS 上强制加 SimpleTS 语义约束"而存在的**。Groovy 只是它的下游受益者之一。

---

## 4. 把"重新设计"的可能性摆出来

如果坚持要把 RFC-0018 + RFC-0019 合并成一个工具，可以走两条路。下面把权衡摆给你看。

### 4.1 路径 A：**保留 SimpleTS-AST**，优化 codegen（推荐，零架构改动）

- 现状：RFC-0018 解析 → SimpleTS-AST → RFC-0019 codegen → Groovy
- 优化方向：**不合并 RFC**，只把 RFC-0019 的 GroovyCodeGen 做得更"翻译器化"（比如借鉴 JS→Groovy 翻译器的策略）
- 优点：架构稳定；SimpleTS-AST 是多引擎共享 IR，未来加 DRL/Python 不重写
- 缺点：模块切分看起来"啰嗦"，但符合 ADR-009 的星型架构

### 4.2 路径 B：**绕开 SimpleTS-AST，做 TS→Groovy 单翻译器**（不推荐，但可论证）

- 假设：用 TypeScript Compiler API 把 SimpleTS 源码编译成 JS IR，再用一个 TS→Groovy 翻译器直接出 Groovy
- 问题：
  - **白名单剪枝谁来做？** 翻译器自己没法判断"原 SimpleTS 源码是否含 switch"，因为它看到的是 JS IR，不是源码
  - **DomainMeta 校验谁来做？** 翻译器拿不到 DomainMeta（它只懂 JS→Groovy 映射）
  - **沙箱审计怎么保？** 翻译器会注入 helper（`Objects.equals()` / `Optional.ofNullable()`），沙箱会拒
- 结论：这条路**走回 SimpleTS-AST 是必然的**，因为翻译器视角下根本没有"白名单剪枝"和"DomainMeta 校验"这两个概念

### 4.3 路径 C：**完全砍掉解析器，直接用 TypeScript 编译器 + 沙箱执行 TS**（违背 ADR-006）

- 假设：完全用 TypeScript Compiler API 解析 + GraalJS 沙箱执行 TS，不做 codegen
- 问题：
  - **Groovy 是当前唯一可靠的沙箱执行环境**（Java 生态成熟，GraalJS 沙箱是侧信道攻击面，RFC-0018 §6 风险表里标红）
  - **执行性能**：GraalJS 解释执行 TS 比 Groovy 编译产物慢一个数量级
  - **DRL / Python 引擎无法复用** TS 执行环境
  - **架构上违反 ADR-006**（"SimpleTS = 唯一的语义层中间态" + "编译产物落 RuleArtifact"）
- 结论：**违背既有 ADR**，需要重新走架构决策流程

### 4.4 路径 D（折中）：**解析器仅做剪枝，校验下沉到 codegen 前的 last-mile**（可考虑，但有代价）

- 假设：把 RFC-0018 §3.6 的 FieldValidator 等下沉到 RFC-0019 之前，作为 codegen 的前置 gate
- 问题：
  - SimpleTS-AST 仍然是产出的中间表示（剪枝就要落到 SimpleTS-AST），只是"校验"步骤物理位置挪动
  - **AST 节点语义不绑定校验结果**：codegen 时如果忘了跑 FieldValidator，错误代码就溜过去了
  - **审计与错误定位变差**：错误信息模板（RFC-0018 §3.8）和 SimpleTS 源码行号绑定，下沉后定位变难
- 结论：可作为微优化，但**不解决"解析器是否必要"这个根本问题**

---

## 5. 回到 SimpleTS 选型的初衷：为什么是 SimpleTS？

用户说"选 SimpleTS 就是想用 TS 技术栈"——这是对的，但**选 SimpleTS 不只是为了"语法像 TS"**，而是为了这 4 件事：

1. **业务/工程师认知零成本**（ADR-006 备选方案的对比）—— 看起来像 TS，写起来像 TS
2. **语法受限 = 编译期拦截风险**（SimpleTS.md §2 P1 安全优先）—— 不是完整 TS，是 TS 安全子集
3. **封闭作用域 = DomainMeta 注入**（SimpleTS.md §2 P2 上下文封闭）—— 入口变量预先声明，运行时不能 scope creep
4. **星型架构中心**（ADR-009）—— SimpleTS 是唯一的语义层中间态，多视图 / 多引擎都从它派生

**第 2 和第 3 条决定了"SimpleTS 必须是受限 DSL，不是完整 TS"**。一旦是受限 DSL，就必然有一个"剪枝 + 校验"的环节——这就是 RFC-0018 在做的事。**它不是"对接 Groovy 的翻译器"，它是"让 TS 变受限 DSL 的编译器前端"**。

---

## 6. 一个小小的命名建议（可选）

如果一定要让"解析器"这个名词不再引发误会，可以考虑在 RFC-0018 的开头加一段 **术语澄清**：

> **"解析器"在本 RFC 中是历史延续命名，实际指 SimpleTS 编译器前端**（lexer/parser + 白名单剪枝 + DomainMeta 语义校验）。其产物 SimpleTS-AST 是受限、语义锚定的 AST 子集，与 TypeScript Compiler API 产出的 TS AST **不是同一棵树的子集**。
>
> 本 RFC 不重新实现词法/语法分析（复用 TypeScript Compiler API，§3.9），重点在 §3.5 的白名单剪枝与 §3.6 的 DomainMeta 语义校验。

这段话写进 RFC 摘要之后，下次再有人问"既然是 TS 技术栈为什么还要解析器"，答案就是一句话：**因为 SimpleTS 是受限 DSL，受限就意味着剪枝，剪枝就需要一个编译器前端**。

---

## 7. 给用户的最终答复（结构化）

| 疑问 | 答复 |
|------|------|
| 选 SimpleTS 是为了 TS 技术栈，为什么还要解析器？ | 词法/语法**已经复用 TypeScript Compiler API**（GraalJS 桥接）。"解析器"实际指编译器前端（剪枝 + 校验），不是重新发明词法/语法。 |
| 解析器的核心目的是不是对接 Groovy？ | **不是**。它的核心目的是 **"在 TS 语法上强制加 SimpleTS 受限语义 + DomainMeta 字段校验"**。Groovy 只是下游受益者之一（还有 DRL / Python / 决策日志）。 |
| 能不能用通用 TS→Groovy 翻译器替代？ | **不能**。通用翻译器 ① 不会做白名单剪枝；② 拿不到 DomainMeta；③ 注入的 helper 会击穿沙箱审计。SimpleTS-AST 是多引擎共享 IR，是星型架构的中心。 |
| 那 RFC-0018 + RFC-0019 是不是该合并？ | **不建议**。它们是星型架构的"剪枝 + codegen"两端，物理位置分开是合理的。如果觉得模块切分啰嗦，可优化 §3.10 的 SimpleTSWhitelist 单一来源声明（已有），让两端更显式共享。 |

---

## 8. 后续动作建议

如果用户认可上述判断，可继续 TDD 推进：

1. **不修改 RFC-0018 / RFC-0019**：当前架构合理，命名歧义通过 §6 的术语澄清小段补充即可
2. **推进 [tmpdocs/TDD推进RFC0018-20-待确认问题.md](../tmpdocs/TDD推进RFC0018-20-待确认问题.md) 中已列的 4 个阻塞问题**（D1~D4）
3. **如果用户希望命名变更**：单独发一个 RFC-0018-bis 或者在 RFC-0018 摘要补一段术语澄清小节

如果用户希望走 §4.2 的"合并方案"，建议**先开 ADR 决策会**，因为这会推翻 ADR-009 的星型架构决策，需要架构组重新审过。
