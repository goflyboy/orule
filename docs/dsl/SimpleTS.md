# SimpleTS：面向 orule 的 TS 面向对象定制子集

> **文档版本**：v0.1 · 2026-09-10
> **状态**：草案 · 与 ADR-003 / ADR-002 配合阅读
> **适用读者**：架构师、规则引擎开发者、规则编写者（业务 + 工程师）

---

## 1. 背景与定位

orule 的中间态表示已确定为 **TS-AST / JS 语义层**（ADR-003）。但通用 TS / JS 表达力过强、副作用过多，**不适合作为"业务规则源语言"直接面向业务人员**。

为此，orule 引入 **SimpleTS** —— TS 的**最小安全子集**：

- 看上去几乎就是 TS，业务 / 工程师**零学习成本**；
- 运行前必须通过**白名单语法剪枝 + 元数据校验**，复杂写法**编译期直接拒绝**；
- 编译产物是受控 AST，进一步生成 Groovy / DRL / JS，**多引擎执行**。

> **SimpleTS 不是替代 TS-AST**，而是 TS-AST 的**"开发期源码形态"**——简化"写"的体验，仍保留 TS-AST 在运行期的全部优势。

```
┌──────────┐    ┌──────────┐    ┌──────────────┐    ┌────────┐
│ NL / UI  │ →  │ SimpleTS │ →  │ SimpleTS-AST │ →  │ TS-AST │
└──────────┘    └──────────┘    └──────────────┘    └────────┘
                  ↑                   ↓
              上下文已声明         静态检查 + 拒绝
              (DomainMeta)
```

---

## 2. 设计原则

| 原则 | 含义 |
|------|------|
| **P1：安全优先** | 任何可能引发副作用、不可序列化、不可静态分析的语法一律禁止 |
| **P2：上下文封闭** | 入口变量在 `DomainMeta.context` 中**预先声明**，运行时**作用域是封闭的** |
| **P3：编译期拒绝** | 不支持 = 编译错误，绝不"运行时回退"或"宽松解释" |
| **P4：人机同源** | 源码、可执行目标（Groovy）**视觉上几乎一致**，降低心智负担 |
| **P5：可追溯** | 任何 AST 节点都能映射回源码行号，便于审计与 diff |

---

## 3. 一个完整示例

### 3.1 业务诉求

> VIP 客户满 200 减 30。

### 3.2 SimpleTS 源码

```ts
// rules/rule_vip_200_30.ts
if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
    order.discount = 30
}
```

### 3.3 对应 DomainMeta（元数据）

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
    ]},
    { id: 'Order', fields: [
        { name: 'id',          type: 'string' },
        { name: 'totalAmount', type: 'number' },
        { name: 'discount',    type: 'number' }
    ]}
  ],
  context: [
    { name: 'customer', type: { entity: 'Customer' } },
    { name: 'order',    type: { entity: 'Order' } }
  ]
}
```

### 3.4 编译后产物

#### SimpleTS-AST（中间表示）

```ts
{
  kind: 'Program',
  body: [{
    kind: 'IfStmt',
    branches: [{
      test: {
        kind: 'BinaryExpr', op: '&&',
        left: {
          kind: 'BinaryExpr', op: '==',
          left:  { kind: 'MemberAccess', root: 'customer', path: ['tier'] },
          right: { kind: 'EnumRef', enumId: 'CustomerTier', value: 'VIP' }
        },
        right: {
          kind: 'BinaryExpr', op: '>=',
          left:  { kind: 'MemberAccess', root: 'order', path: ['totalAmount'] },
          right: { kind: 'Literal', value: 200 }
        }
      },
      body: {
        kind: 'Block',
        body: [{
          kind: 'AssignStmt',
          target: { kind: 'MemberAccess', root: 'order', path: ['discount'] },
          value:  { kind: 'Literal', value: 30 }
        }]
      }
    }]
  }]
}
```

#### Groovy 代码生成（与源码视觉一致）

```groovy
if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
    order.discount = 30
}
```

---

## 4. 文法定义（EBNF）

```
program        ::= statement*
statement      ::= ifStmt | forStmt | declare | assign | exprStmt | block
block          ::= "{" statement* "}"
ifStmt         ::= "if" "(" expr ")" block
                  ("else" "if" "(" expr ")" block)*
                  ("else" block)?
forStmt        ::= "for" "(" (declare | assign)? ";" expr? ";" assign? ")" block
declare        ::= "let" IDENT (":" typeRef)? ("=" expr)?
assign         ::= memberAccess "=" expr
exprStmt       ::= expr ";"

expr           ::= orExpr
orExpr         ::= andExpr ("||" andExpr)*
andExpr        ::= cmpExpr ("&&" cmpExpr)*
cmpExpr        ::= addExpr (cmpOp addExpr)?
cmpOp          ::= "==" | "!=" | ">" | ">=" | "<" | "<="
addExpr        ::= mulExpr (("+" | "-") mulExpr)*
mulExpr        ::= unary (("*" | "/" | "%") unary)*
unary          ::= ("!" | "-")? primary
primary        ::= literal | memberAccess | call | "(" expr ")"

literal        ::= NUMBER | STRING | BOOLEAN | enumRef
enumRef        ::= IDENT ("." IDENT)*            // CustomerTier.VIP
memberAccess   ::= IDENT ("." IDENT)*
call           ::= memberAccess "(" args? ")"
args           ::= expr ("," expr)*

typeRef        ::= "string" | "number" | "boolean" | "date" | IDENT
```

---

## 5. 与"完整 TS"相比砍掉的语法

| 类别 | 砍掉的语法 |
|------|-----------|
| **声明** | `var` / `const`、函数声明、类、接口、type、enum 关键字、import / export / module |
| **类型** | 联合类型、交叉类型、泛型、条件类型、装饰器、`as` 断言、`!` 非空断言 |
| **OOP** | class / extends / implements / `this` / `super` / `new` |
| **函数式** | 箭头函数、匿名函数、闭包、`function` 声明 |
| **异步** | `async` / `await` / `Promise` / `setTimeout` / `setInterval` |
| **数组** | 数组字面量 `[1,2]`、解构、展开 `...`、下标访问 `a[0]` |
| **异常** | `try` / `catch` / `throw` |
| **对象** | 对象字面量 `{a:1}`、动态键、可选链 `?.`、空值合并 `??` |
| **模块** | `import` / `export` / `namespace` |
| **控制流** | `while`、`do-while`、`switch`、`break` / `continue` |
| **运算符** | 位运算、`typeof`、`instanceof`、`in`、`void`、三元 `?:` |
| **副作用** | `delete`、`with` |
| **作用域** | 全局作用域访问（仅允许 `context` + 白名单内置） |

---

## 6. 受限 AST 节点定义

```ts
export type TSSNode =
  | Program | IfStmt | ForStmt | DeclareStmt | AssignStmt | ExprStmt | Block
  | BinaryExpr | UnaryExpr | Literal | MemberAccess | CallExpr | EnumRef;

export interface Program    { kind: 'Program';    body: TSSNode[]; }
export interface Block      { kind: 'Block';      body: TSSNode[]; }

export interface IfStmt     {
  kind: 'IfStmt';
  branches: { test: Expr; body: Block }[];   // 末位可选 else
}
export interface ForStmt    {
  kind: 'ForStmt';
  init?: DeclareStmt | AssignStmt;
  test?: Expr;
  update?: AssignStmt;
  body: Block;
}
export interface DeclareStmt {
  kind: 'DeclareStmt';
  name: string;
  typeRef?: string;
  init?: Expr;
}
export interface AssignStmt {
  kind: 'AssignStmt';
  target: MemberAccess;             // 严格限制：左值只能是上下文变量属性链
  value: Expr;
}
export interface ExprStmt   { kind: 'ExprStmt';  expr: Expr; }

export type Expr =
  | BinaryExpr | UnaryExpr | Literal | MemberAccess | CallExpr | EnumRef;

export interface BinaryExpr {
  kind: 'BinaryExpr';
  op: '||' | '&&' | '==' | '!=' | '>' | '>=' | '<' | '<=' | '+' | '-' | '*' | '/' | '%';
  left: Expr; right: Expr;
}
export interface UnaryExpr  { kind: 'UnaryExpr';  op: '!' | '-'; arg: Expr; }
export interface Literal    { kind: 'Literal';    value: string | number | boolean; }
export interface EnumRef    { kind: 'EnumRef';    enumId: string; value: string; }
export interface MemberAccess {
  kind: 'MemberAccess';
  root: string;             // 必须是 DomainMeta.context 声明的入口变量
  path: string[];           // 后续属性链，如 ['tier'] 或 ['items', 'length']
}
export interface CallExpr   { kind: 'CallExpr'; callee: MemberAccess; args: Expr[]; }
```

> **关键约束**：`AssignStmt.target` 在类型层面钉死为 `MemberAccess`，从源头杜绝 `x = 1`（裸标识符赋值）、`a[0] = 1`（下标赋值）等危险写法。

---

## 7. 元数据 DomainMeta

SimpleTS 的"上下文已限定"靠 `DomainMeta` 落地：

```ts
export type PrimitiveType = 'string' | 'number' | 'boolean' | 'date';

export interface EnumDef {
  kind: 'Enum';
  id: string;                              // "CustomerTier"
  values: readonly string[];               // ["VIP", "NORMAL"]
}

export interface EntityField {
  name: string;
  type: PrimitiveType | { entity: string } | { enumRef: string };
  nullable?: boolean;
  writable?: boolean;                      // 默认 true；false 表示只读字段
}

export interface EntityDef {
  kind: 'Entity';
  id: string;                              // "Customer"
  fields: readonly EntityField[];
}

export interface ContextVar {
  name: string;                            // "customer"
  type: { entity: string };                // -> EntityDef.id
  nullable?: boolean;
}

export interface DomainMeta {
  id: string;
  enums:    readonly EnumDef[];
  entities: readonly EntityDef[];
  context:  readonly ContextVar[];         // 入口变量清单
}
```

### 7.1 校验项

| 校验 | 来源 | 失败示例 |
|------|------|---------|
| 标识符未声明 | `context` + `enums` + 白名单内置 | `未声明的标识符 'invoice'` |
| 字段不存在 | `entities[].fields` | `实体 Order 上不存在字段 'discountX'` |
| 字段只读 | `writable: false` | `字段 'id' 不可写` |
| 枚举值非法 | `enums[].values` | `枚举 CustomerTier 不含值 'GOD'` |
| 类型不匹配 | 表达式两侧类型 | `类型不匹配: number 与 string 不能比较` |
| 写入非常量 | AST 类型 | `禁止对算术表达式赋值` |
| 方法未白名单 | 内置白名单 | `不允许的方法调用 'log'` |
| 表达式嵌套过深 | 静态检查 | `表达式嵌套超过 32 层` |
| statement 总数 | 静态检查 | `单条规则 statement 数超过上限 200` |

---

## 8. 不支持的写法清单（**命中即编译错误**）

```ts
// 1. 不允许的对象字面量
const x = { a: 1 }                         // ❌

// 2. 不允许的数组字面量
const xs = [1, 2, 3]                        // ❌

// 3. 不允许的箭头函数
const f = (x) => x + 1                      // ❌

// 4. 不允许的 this / new
this.foo                                    // ❌
new Date()                                  // ❌（new Date 调用整体禁用）

// 5. 不允许的可选链 / 空合并
order.customer?.name                        // ❌
order.discount ?? 0                         // ❌

// 6. 不允许的解构 / 展开
const { a, b } = order                      // ❌
const x = { ...order }                      // ❌

// 7. 不允许的全局对象访问
Math.random()                               // ❌（Math.random 不在白名单）
console.log("debug")                        // ❌
JSON.parse(s)                               // ❌

// 8. 不允许的类型转换 / 断言
order.totalAmount as string                 // ❌
order.totalAmount!                          // ❌

// 9. 不允许的 try / async / await
try { ... } catch(e) { ... }                // ❌
await fetch('...')                          // ❌

// 10. 不允许的模块化
import { x } from 'lodash'                  // ❌
export const r = ...                        // ❌

// 11. 不允许的三元 / switch
a > 0 ? 1 : 2                               // ❌（用 if 表达）
switch (a) { case 1: ... }                  // ❌（用 if 链表达）

// 12. 不允许的位运算
a & b                                       // ❌

// 13. 不允许的 typeof / in / instanceof
typeof a                                    // ❌
a in b                                      // ❌
```

### 8.1 白名单内置

| 类别 | 名称 |
|------|------|
| 数学 | `Math.abs / min / max / floor / ceil / round` |
| 字符串 | `String(x).length / startsWith / endsWith / includes / toUpperCase / toLowerCase` |
| 日期 | `Date.now() / new Date(x).getFullYear() / getMonth / getDate`（仅白名单方法） |

---

## 9. 解析器实现策略

### 9.1 不写完整 parser

直接复用 **TypeScript Compiler API**，把 SimpleTS 源码解析成完整 TS AST，再用 visitor 对每个节点做**白名单剪枝**：

- ✅ 节点类型在白名单 → 递归转换；
- ❌ 节点类型不在白名单 → 收集错误并拒绝整条规则。

```ts
import ts from 'typescript';
import type { DomainMeta } from './meta';
import type { TSSNode } from './ast';

const ALLOWED = new Set<ts.SyntaxKind>([
  ts.SyntaxKind.IfStatement,
  ts.SyntaxKind.ForStatement,
  ts.SyntaxKind.VariableDeclarationList,
  ts.SyntaxKind.VariableDeclaration,
  ts.SyntaxKind.VariableStatement,
  ts.SyntaxKind.ExpressionStatement,
  ts.SyntaxKind.BinaryExpression,
  ts.SyntaxKind.PrefixUnaryExpression,
  ts.SyntaxKind.PropertyAccessExpression,
  ts.SyntaxKind.CallExpression,
  ts.SyntaxKind.NumericLiteral,
  ts.SyntaxKind.StringLiteral,
  ts.SyntaxKind.TrueKeyword,
  ts.SyntaxKind.FalseKeyword,
  ts.SyntaxKind.Block,
  ts.SyntaxKind.ParenthesizedExpression,
  // ... 详细清单见 reference parser
]);

export function parseTSS(source: string, meta: DomainMeta): TSSNode {
  const sf = ts.createSourceFile('rule.ts', source, ts.ScriptTarget.ES2020, true);
  const errors: string[] = [];
  const program = convert(sf, meta, errors);
  if (errors.length) throw new TssCompileError(errors);
  return program;
}

export class TssCompileError extends Error {
  constructor(public readonly errors: readonly string[]) {
    super(errors.join('\n'));
  }
}
```

### 9.2 静态校验顺序

```
1. 词法 / 语法白名单
2. 标识符作用域
3. 字段存在性
4. 枚举值合法性
5. 类型对齐
6. 左值合法性
7. 规模上限（嵌套深度、statement 数）
```

---

## 10. SimpleTS → TS-AST → 多引擎

### 10.1 编译路径

```
SimpleTS 源码
   │  parse + validate
   ▼
SimpleTS-AST
   │  transform (语义提升 + 副作用识别)
   ▼
TS-AST（ADR-003 定义的 IR）
   │  codegen
   ▼
{ Groovy 脚本, DRL 规则, JS 评估器, 决策日志格式 }
```

### 10.2 目标产物对比

| 目标 | 一致性 | 说明 |
|------|--------|------|
| **Groovy** | ✅ 视觉与源码一致 | 第一目标引擎，业务 / 工程师都能读 |
| **DRL** | ⚠️ 结构化转换 | if 链 + 赋值 → `when` / `then` |
| **JS 评估器** | ✅ 几乎一致 | 浏览器端 dry-run / 单测 |
| **决策日志** | 派生 | 每个节点带行号 → JSON trace |

---

## 11. 错误信息样板（面向开发者）

```
TSS 编译失败（rule_vip_200_30）:

  ✗ 第 3 行: 不允许的方法调用 'log'（console 不在白名单）
       3 |     console.log("debug");

  ✗ 第 5 行: 未声明的标识符 'invoice'
       5 |     if (invoice.amount > 0) { ... }

  ✗ 第 8 行: 实体 Order 上不存在字段 'discountX'
       8 |         order.discountX = 10

  ✗ 第 12 行: 类型不匹配: number 与 string 不能比较
      12 |     if (order.totalAmount == "200") { ... }

  ✗ 第 15 行: 禁止给常量赋值
      15 |     200 = order.totalAmount;
```

---

## 12. SimpleTS 与 TS-AST 的关系

| 维度 | SimpleTS 源码 | TS-AST |
|------|--------------|--------|
| **输入形式** | TS 子集源代码 | 结构化 JSON |
| **适合谁写** | 业务 + 工程师 | LLM 生成 / 程序化编辑 |
| **可视化编辑** | ⚠️ 代码编辑器 + 校验 | ✅ 直接绑 schema |
| **diff 友好** | ⚠️ 源码 diff | ✅ 结构化 diff |
| **运行** | 编译到 Groovy / DRL / JS | 转 SimpleTS 或直接求值 |
| **学习成本** | 极低（TS 开发者） | 中（要学 AST 概念） |

**推荐组合**：**SimpleTS 源码作为"开发期权威"**，编译产物（TS-AST + Groovy + DRL）作为"运行期权威"。

---

## 13. 落地路径

| 步骤 | 产出 | 工时（估） |
|------|------|----------|
| 1. 解析器骨架（白名单剪枝） | `parseTSS()` + 错误列表 | 0.5 人天 |
| 2. `DomainMeta` schema + 字段校验 | Zod schema + 错误模板 | 0.5 人天 |
| 3. SimpleTS → Groovy 代码生成器 | `codegenGroovy(ast)` | 1–2 人天 |
| 4. 决策日志 trace | 每个节点带行号 | 0.5 人天 |
| 5. SimpleTS → DRL 生成器 | `codegenDRL(ast)` | 1 人天 |
| 6. 表格 / NL 编辑器接入 TS-AST | 多形态切换 | 后续迭代 |

---

## 14. 风险与护栏

| 风险 | 缓解 |
|------|------|
| 业务人员写出"合法但不语义正确"的代码（如 `order.discount = -9999`） | DomainMeta 加字段 `range` / 业务约束；执行期再做边界校验 |
| 元数据漂移（实体改了，规则没改） | 规则加载时强制 `revalidate(meta)`，失败即拒绝加载 |
| 性能：每条规则都解析一次 | 编译产物落库，运行期直接读缓存；增量校验 |
| LLM 生成时跳过 SimpleTS 直接吐 JSON | MCP / 编辑器入口只接受 SimpleTS 源码或 TS-AST；二者均需过 schema |
| 与 ADR-003 冲突 | 本方案**不替代** ADR-003，而是其"源码层实现"，定位一致 |

---

## 15. 决策建议

> **采用 SimpleTS 作为规则"开发期源语言"**。SimpleTS 源码经编译器产出 TS-AST 与多引擎代码，**与 ADR-003 完全兼容**，且显著降低业务人员 / 工程师的编写与阅读成本。

详细决策理由见配套 ADR：`ADR-006-规则源语言采用SimpleTS.md`。

---

## 附录 A：完整 VIP 规则示例代码

```ts
// === DomainMeta（元数据） ===
export const orderDiscountMeta: DomainMeta = {
  id: 'order-discount',
  enums: [{ id: 'CustomerTier', values: ['VIP', 'NORMAL'] }],
  entities: [
    { id: 'Customer', fields: [
        { name: 'id',   type: 'string' },
        { name: 'tier', type: { enumRef: 'CustomerTier' } }
    ]},
    { id: 'Order', fields: [
        { name: 'id',          type: 'string' },
        { name: 'totalAmount', type: 'number' },
        { name: 'discount',    type: 'number' }
    ]}
  ],
  context: [
    { name: 'customer', type: { entity: 'Customer' } },
    { name: 'order',    type: { entity: 'Order' } }
  ]
};

// === SimpleTS 源码（业务 / 工程师直接写） ===
// rules/rule_vip_200_30.ts
if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
    order.discount = 30
}

// === 编译产物（自动生成，不手写） ===
// build/rule_vip_200_30.groovy
if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
    order.discount = 30
}
```
