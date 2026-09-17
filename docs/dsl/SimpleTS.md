# SimpleTS：面向 orule 的 TS 面向对象定制子集

> **文档版本**：v0.3 · 2026-09-16（RFC-0043：Map 下标 / typed declare / List 普通函数；仍禁止 lambda）
> **状态**：草案 · 与 ADR-003 / ADR-002 配合阅读
> **适用读者**：架构师、规则引擎开发者、规则编写者（业务 + 工程师）
> **RFC-0032 同步**：§7 新增 ObjectInst（运行时实例）定义

---

## 1. 背景与定位

orule 的中间态表示已确定为 **SimpleTS(参考：ADR-003-中间态DSL采用SimpleTS）**。但通用 TS / JS 表达力过强、副作用过多，**不适合作为"业务规则源语言"直接面向业务人员**。

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


| 原则           | 含义                                                  |
| ------------ | --------------------------------------------------- |
| **P1：安全优先**  | 任何可能引发副作用、不可序列化、不可静态分析的语法一律禁止                       |
| **P2：上下文封闭** | 入口变量在 `DomainMeta.context` 中**预先声明**，运行时**作用域是封闭的** |
| **P3：编译期拒绝** | 不支持 = 编译错误，绝不"运行时回退"或"宽松解释"                         |
| **P4：人机同源**  | 源码、可执行目标（Groovy）**视觉上几乎一致**，降低心智负担                  |
| **P5：可追溯**   | 任何 AST 节点都能映射回源码行号，便于审计与 diff                       |


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

> **RFC-0031 修订**：enum 定义**不再顶层声明**，而是通过 AttributeType 的 `type.kind === 'enum'` 内联；
> DomainMeta 不再有 `enums` 字段。Type 系统采用 RFC-0031 的 5 个 Variant（primitive / enum / object / list / map）。

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
        } }
    ]},
    { id: 'Order', fields: [
        { name: 'id',          type: { kind: 'primitive', name: 'string' } },
        { name: 'totalAmount', type: { kind: 'primitive', name: 'number' } },
        { name: 'discount',    type: { kind: 'primitive', name: 'number' } }
    ]}
  ],
  context: [
    { name: 'customer', type: { kind: 'object', objectCode: 'Customer' } },
    { name: 'order',    type: { kind: 'object', objectCode: 'Order' } }
  ]
}
```

> **MVP 嵌套约束**（RFC-0031 §3.5.2，**RFC-0043 收窄**）：`object` 类型字段仍不能继续点内部属性（禁止 `customer.address.city`）。
> RFC-0043 放开：context 根为 `List`/`Map` 时可用普通函数 `get`/`size`/`containsKey`/`keySet` 以及 Map 下标 `customersById["alice"]`；禁止 lambda / `any{}`。
> 局部变量必须带 ObjectType：`let vip: Customer = customersById["alice"]` → Groovy `Customer vip = ...`。细节见 [RFC-0043](../rfcs/RFC-0043-嵌套对象List与Map上下文绑定.md)。



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


| 类别      | 砍掉的语法                                                            |
| ------- | ---------------------------------------------------------------- |
| **声明**  | `var` / `const`、函数声明、类、接口、type、enum 关键字、import / export / module |
| **类型**  | 联合类型、交叉类型、泛型、条件类型、装饰器、`as` 断言、`!` 非空断言                           |
| **OOP** | class / extends / implements / `this` / `super` / `new`          |
| **函数式** | 箭头函数、匿名函数、闭包、`function` 声明                                       |
| **异步**  | `async` / `await` / `Promise` / `setTimeout` / `setInterval`     |
| **数组**  | 数组字面量 `[1,2]`、解构、展开 `...`、List 下标 `a[0]`（改用 `get(a, i)`，见 RFC-0043） |
| **异常**  | `try` / `catch` / `throw`                                        |
| **对象**  | 对象字面量 `{a:1}`、动态键、可选链 `?.`、空值合并 `??`                             |
| **模块**  | `import` / `export` / `namespace`                                |
| **控制流** | `while`、`do-while`、`switch`、`break` / `continue`                 |
| **运算符** | 位运算、`typeof`、`instanceof`、`in`、`void`、三元 `?:`                    |
| **副作用** | `delete`、`with`                                                  |
| **作用域** | 全局作用域访问（仅允许 `context` + 白名单内置）                                   |


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



## 7. 元数据（元数据模型）

> **RFC-0018-bis 修订**：DomainMeta 概念已废弃。入口变量（context）下沉到 RuleType.arguments。
> RuleSet 本身就是领域代表（元数据索引）。
>
> - 入口变量定义 → RuleType.arguments
> - 函数签名定义 → FuntionType（entity 层）
> - 返回值类型 → ReturnType（VOID 时 objectType = null）

### 7.1 RuleSet / RuleType（元数据索引）

```ts
// ===== 元数据索引：RuleSet = 领域代表 =====

export interface RuleSet {           // entity.RuleSet
  id: string;
  code: string;                     // 规则集类型 code
  name: string;
  objectTypes: ObjectType[];        // 本规则集可用的领域对象
  functionTypes: FuntionType[];     // 本规则集可用的函数 SDK 全集
  ruleTypes: RuleType[];            // 本规则集下的规则类型
}

// ===== 规则类型：本质是一个空函数 =====

export interface RuleType {         // entity.RuleType
  id: string;
  code: string;                     // 规则类型 code（如"订单校验规则"）
  name: string;
  description: string;
  validatable: boolean;             // 默认 true；false 时跳过 RFC-0018 §3.6 字段校验
  arguments: ArgumentType[];         // 函数入参（对应 SimpleTS 中的入口变量）
  returnType: ReturnType;           // 函数出参
  functionTypes: FuntionType[];     // 允许调用的函数 SDK（声明式，不隐式继承）
  excludeFunctionTypes: string[];    // 审计注释：应被排除的函数列表
}

// ===== 入参定义 =====

export interface ArgumentType {     // entity.ArgumentType
  id: string;
  programCode: string;              // 参数名（SimpleTS 中的入口变量名）
  objectType: ObjectType;           // 类型（必须是 RuleSet.objectTypes 中的元素）
  nullable: boolean;                // null 是否合法
  writable: boolean;                // 是否可被 SimpleTS 赋值
}

// ===== 出参定义 =====

export interface ReturnType {      // entity.ReturnType
  id: string;
  objectType: ObjectType | null;    // null 表示 VOID（无返回值）
  nullable: boolean;               // nullable=false 时返回 null 视为校验失败
}
```

### 7.2 Type 系统（RFC-0032，不变）

```ts
// ===== Type 4 个 Variant（RFC-0032）=====

export interface PrimitiveType { kind: 'primitive'; name: 'string'|'number'|'boolean'|'date'; }
export interface ObjectType    { kind: 'object';   objectCode: string; }
export interface ListType      { kind: 'list';     elementType: Type; }
export interface MapType      { kind: 'map';      keyType: Type; valueType: Type; }
export type Type = PrimitiveType | ObjectType | ListType | MapType;
```

> **MVP 嵌套约束**（RFC-0031 §3.5.2，**RFC-0043 收窄**）：仍禁止 `customer.address.city`（object 字段继续点属性）和 List 下标 `items[0]`。
> 允许 context 根 List/Map 的普通函数与 Map 下标；见 [RFC-0043 §4.6](../rfcs/RFC-0043-嵌套对象List与Map上下文绑定.md)。

### 7.2 ObjectInst（运行时实例）

**ObjectInst** 是 ObjectType 的运行时实例，承载规则执行时的具体业务数据。

```ts
/**
 * 运行时对象实例。
 * 对应元数据层的 ObjectType，是规则执行时的输入/输出数据载体。
 */
export interface ObjectInst {
  /** 实例 ID（UUID），用于追踪和调试 */
  _id: string;
  /** 引用的 ObjectType.programCode，如 "Customer"、"Order" */
  _type: string;
  /** 属性值：fieldName → value */
  [fieldName: string]: any;
}
```

**ObjectInst 与 ObjectType 的对应关系**：

| 元数据层（ObjectType） | 运行时实例层（ObjectInst） |
|---------------------|--------------------------|
| `ObjectType.programCode` | `ObjectInst._type` |
| `AttributeType.name` | `ObjectInst[fieldName]` |

**示例**：

```ts
// ObjectType 定义
const customerType = {
  id: 'Customer',
  fields: [
    { name: 'id',   type: { kind: 'primitive', name: 'string' } },
    { name: 'tier', type: { kind: 'enum', enumCode: 'CustomerTier', values: [...] } }
  ]
};

// ObjectInst 实例
const customer = {
  _id: 'C001',
  _type: 'Customer',
  id: 'C001',
  tier: 'VIP'
};
```

### 7.3 规则执行上下文（Context）

规则执行时，Context 是一个 Map，key 是变量名（如 `customer`、`order`），value 是 ObjectInst：

```ts
// 规则执行上下文示例
const inputContext = {
  customer: { _id: 'C001', _type: 'Customer', id: 'C001', tier: 'VIP', name: '张三' },
  order:    { _id: 'O001', _type: 'Order',    id: 'O001', totalAmount: 250, discount: 0 }
};
```

### 7.4 校验项


| 校验           | 来源                          | 失败示例                          |
| ------------ | --------------------------- | ----------------------------- |
| 标识符未声明       | `context` + 白名单内置          | `未声明的标识符 'invoice'`           |
| 字段不存在        | `entities[].fields`         | `实体 Order 上不存在字段 'discountX'` |
| 字段只读         | `writable: false`           | `字段 'id' 不可写`                 |
| 枚举值非法        | 属性 type.kind=='enum' 的 values | `枚举 CustomerTier 不含值 'GOD'`   |
| 字段是 object/list/map | entity.fields | `字段 'address' 是 object 类型，SimpleTS 不支持继续访问内部属性` |
| 类型不匹配        | 表达式两侧类型                     | `类型不匹配: number 与 string 不能比较` |
| 写入非常量        | AST 类型                      | `禁止对算术表达式赋值`                  |
| 方法未白名单       | 内置白名单                       | `不允许的方法调用 'log'`              |
| 表达式嵌套过深      | 静态检查                        | `表达式嵌套超过 32 层`                |
| statement 总数 | 静态检查                        | `单条规则 statement 数超过上限 200`    |


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


| 类别  | 名称                                                                                |
| --- | --------------------------------------------------------------------------------- |
| 数学  | `Math.abs / min / max / floor / ceil / round`                                     |
| 字符串 | `String(x).length / startsWith / endsWith / includes / toUpperCase / toLowerCase` |
| 日期  | `Date.now() / new Date(x).getFullYear() / getMonth / getDate`（仅白名单方法）             |


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


| 目标         | 一致性       | 说明                          |
| ---------- | --------- | --------------------------- |
| **Groovy** | ✅ 视觉与源码一致 | 第一目标引擎，业务 / 工程师都能读          |
| **DRL**    | ⚠️ 结构化转换  | if 链 + 赋值 → `when` / `then` |
| **JS 评估器** | ✅ 几乎一致    | 浏览器端 dry-run / 单测           |
| **决策日志**   | 派生        | 每个节点带行号 → JSON trace        |


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


| 维度          | SimpleTS 源码           | TS-AST           |
| ----------- | --------------------- | ---------------- |
| **输入形式**    | TS 子集源代码              | 结构化 JSON         |
| **适合谁写**    | 业务 + 工程师              | LLM 生成 / 程序化编辑   |
| **可视化编辑**   | ⚠️ 代码编辑器 + 校验         | ✅ 直接绑 schema     |
| **diff 友好** | ⚠️ 源码 diff            | ✅ 结构化 diff       |
| **运行**      | 编译到 Groovy / DRL / JS | 转 SimpleTS 或直接求值 |
| **学习成本**    | 极低（TS 开发者）            | 中（要学 AST 概念）     |


**推荐组合**：**SimpleTS 源码作为"开发期权威"**，编译产物（TS-AST + Groovy + DRL）作为"运行期权威"。

---



## 13. 落地路径


| 步骤                            | 产出                   | 工时（估）  |
| ----------------------------- | -------------------- | ------ |
| 1. 解析器骨架（白名单剪枝）               | `parseTSS()` + 错误列表  | 0.5 人天 |
| 2. `DomainMeta` schema + 字段校验 | Zod schema + 错误模板    | 0.5 人天 |
| 3. SimpleTS → Groovy 代码生成器    | `codegenGroovy(ast)` | 1–2 人天 |
| 4. 决策日志 trace                 | 每个节点带行号              | 0.5 人天 |
| 5. SimpleTS → DRL 生成器         | `codegenDRL(ast)`    | 1 人天   |
| 6. 表格 / NL 编辑器接入 TS-AST       | 多形态切换                | 后续迭代   |


---

