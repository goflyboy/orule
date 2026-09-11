# ADR-003：中间态采用 SimpleTS + TS-AST 双层（开发期源码 + 运行期 IR）

- 状态：已接受
- 日期：2026-09-10
- 决策人：架构组
- 相关章节：01-边界与目标 §1.1 / 04-数据模型 / 05-技术模型 / [ADR-006](../adr/ADR-006-规则源语言采用SimpleTS.md) / [SimpleTS.md](../../dsl/SimpleTS.md)

## 背景

"一种语义、多种表现形态"需要中间态表示层。ADR-003 早期版本仅定义了 TS-AST 作为单一中间态，但实际开发中暴露出：
- TS-AST 是结构化 JSON，对业务人员/工程师**写规则**的认知负担偏高
- LLM 直出 TS-AST 易错、不稳定
- 业务人员实际写出来的代码形态更像受限 TS 子集，而非 AST

## 决策更新

采用**双层中间态**：

1. **SimpleTS**（开发期源码）= TS 的最小安全子集，看上去几乎就是 TS
   - 业务/工程师**直接写**
   - 通过白名单语法剪枝 + DomainMeta 校验，**编译期拒绝**复杂写法
   - 详见 [SimpleTS.md](../../dsl/SimpleTS.md) 与 [ADR-006](../adr/ADR-006-规则源语言采用SimpleTS.md)

2. **TS-AST**（运行期 IR）= 结构化中间表示
   - 编译产物，**LLM 转换器、表格、决策日志**统一消费 TS-AST
   - 多引擎代码生成（Groovy / cruleengine / JS）的统一输入

## 编译链路

```
NL / UI
  ↓
SimpleTS 源码         ← 业务人员、工程师直接写
  │ parse + validate (白名单 + DomainMeta)
  ↓
SimpleTS-AST
  │ transform (语义提升 + 行号映射)
  ↓
TS-AST               ← LLM、转换器、表格、决策日志统一消费
  │ codegen
  ↓
{ Groovy, DRL, JS, 决策日志 }
```

## 候选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| A：**SimpleTS + TS-AST 双层**（本方案） | 人写源码、机器跑 AST；LLM 生成稳定；编译期拦截风险；与 TS-AST 体系完全兼容 | 需 SimpleTS 编译器骨架（W1 必须先搭） |
| B：纯 TS-AST | 单层简单 | 业务人员写不出；LLM 不稳定 |
| C：完整 TS + 沙箱执行 | 上手最快 | 安全风险高；无法静态分析；不支持多形态 |
| D：DMN/FEEL | 标准化 | 与 TS-AST 体系脱节；学习成本高 |

## 决策

采用方案 A：**SimpleTS（开发期源码）+ TS-AST（运行期 IR）双层**。
- 业务/工程师写 SimpleTS 源码
- SimpleTS 编译器产出 SimpleTS-AST → TS-AST → 多引擎代码
- LLM 转换器只接受 SimpleTS 源码或 TS-AST，二者均需过 schema

## 后果

- 正面影响：单点编辑、多点查看/执行；业务认知零成本；LLM 输出更稳定；编译期拦截安全风险。
- 负面影响 / 成本：需 SimpleTS 编译器骨架；元数据维护成本；W1 必须先搭 SimpleTS parser。
- 回退方案：保留 TS-AST 单独可用的接口；元数据层抽象后，未来可切换。

## 备注

[OPEN-Q1] TS-AST 是否需要兜底一个 JSON 视图（用于非 JS 环境下的纯展示）—— 进入逻辑视图决策。[推断 中] 不紧迫，MVP 暂不需要。
