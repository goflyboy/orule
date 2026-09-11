# ADR-006：规则源语言采用 SimpleTS（TS 面向对象定制子集）

- 状态：已接受
- 日期：2026-09-10
- 决策人：架构组
- 相关章节：01-边界与目标 §5 / 04-数据模型 / 05-技术模型
- 配套文档：[SimpleTS.md](../dsl/SimpleTS.md)

## 背景

ADR-003 已确定中间态 DSL 采用 **TS-AST / JS 语义层**。但 TS-AST 是结构化 JSON，对业务人员 / 工程师**写规则的认知负担仍然偏高**；同时 LLM 直出 TS-AST 易错、不稳定。

业务人员实际写出来的代码形态更像：

```ts
if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
    order.discount = 30
}
```

这种"看起来就是 TS，但又不希望它是完整 TS"的诉求，需要一种**最小安全子集**作为开发期源语言。

## 候选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| A：完整 TS 脚本 + 沙箱执行 | 上手最快（写啥跑啥） | 安全风险高（`eval`/`process`）；无法静态分析；不支持多形态 |
| B：DMN XML / FEEL | 标准化、可审计 | 表达力弱，业务人员学习成本高，与 TS-AST 体系脱节 |
| C：**SimpleTS（TS 安全子集）+ DomainMeta**（本方案） | 业务认知零成本；编译期拒绝复杂写法；与 TS-AST 天然衔接；支持多引擎 codegen | 需要写一套 parser 骨架；元数据维护成本 |
| D：纯表达式语言（CEL / Spring EL） | 表达力强、有沙箱 | 与 TS-AST 体系脱节；与"JS 生态"心智冲突 |

## 决策

采用方案 C：**SimpleTS（TS 面向对象定制子集）+ DomainMeta**。

- **SimpleTS 源码** = TS 的最小安全子集，看上去几乎就是 TS。
- **DomainMeta** = 入口变量 + 实体 + 枚举的元数据，封闭作用域。
- **编译器** = 用 TypeScript Compiler API 做白名单剪枝 + 静态校验，产出 SimpleTS-AST，再转换为 TS-AST。
- SimpleTS-AST / TS-AST 不落业务库；真正落库的是**多引擎产物**（Groovy / DRL）与 TS-AST，决策日志可追溯。

## 设计原则

1. **安全优先**：任何可能引发副作用、不可序列化、不可静态分析的语法一律禁止。
2. **上下文封闭**：入口变量由 DomainMeta 预先声明，运行期作用域封闭。
3. **编译期拒绝**：不支持 = 编译错误，绝不"运行时回退"。
4. **人机同源**：源码与 Groovy 产物视觉一致。
5. **可追溯**：任何 AST 节点都能映射回源码行号。

## 与既有 ADR 的关系

- **不替代 ADR-003**：ADR-003 定义的是**中间态表示**；SimpleTS 是其**开发期源码形态**。SimpleTS 编译产物就是 TS-AST，链路完全一致。
- **不替代 ADR-002**：执行引擎仍是双引擎（Groovy + cruleengine），SimpleTS 编译到两端。
- **不替代 ADR-005**：DomainMeta 持久化在 orule 的元数据库，与规则绑定校验。

## 影响

- 新增包：`packages/rule-tss/`（SimpleTS 编译器）
- 新增类型：`packages/rule-meta/`（DomainMeta + Zod schema）
- 编辑器：VS Code / Web 编辑器需要"SimpleTS 语法高亮 + 实时校验"插件
- 文档：`docs/dsl/SimpleTS.md` 作为权威定义
- LLM：MCP 接口的"规则生成"工具改为接受 SimpleTS 源码 + DomainMeta，产出 SimpleTS-AST

## 不在范围内

- 复杂业务函数 / 算法（>20 statement）→ 建议拆成多条规则或服务代码
- 异步 / IO 操作 → 不在 SimpleTS 范畴
- 跨实体关联查询 → 通过 `DomainMeta.context` 注入预查好的对象，不在规则内做 join

## 风险与护栏

详见 `SimpleTS.md §14`。核心三点：

1. **业务字段边界** → DomainMeta 加 `range` / 约束，校验与运行期双重把关。
2. **元数据漂移** → 规则加载时强制 `revalidate(meta)`。
3. **LLM 直出 JSON** → MCP 入口只接受 SimpleTS 源码或 TS-AST，二者均需过 schema。

## 决策摘要

> 用 SimpleTS 把"写规则的体验"和"运行规则的体验"打通：开发期像写 TS，运行期仍走 TS-AST + 多引擎 + 可追溯。复杂度集中在编译器，业务层零负担。
