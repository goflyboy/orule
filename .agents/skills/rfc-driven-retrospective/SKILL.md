---
name: rfc-driven-retrospective
description: RFC 驱动复盘工作流。RFC 生成或实现后，用户要求 review / retrospective / postmortem / 复盘 / 流程改进时使用。重点区分 `orule-common / orule-rule-execution-service / orule-server / 跨模块` 的知识缺口、用户偏好和流程护栏，并提出 Skill、模块规则和项目知识的改进建议。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# RFC 驱动复盘

> 配套：[`.agents/README.md`](../../README.md) · [`.agents/skills/audit-docs/SKILL.md`](../audit-docs/SKILL.md)
> 目的：把遗漏的知识转化为规则、Skill、文档和检查项；不追责。

## 0. 何时使用 / 何时不使用

**使用**：

- 用户说"复盘 / retrospective / postmortem / 流程改进"。
- 一组 RFC / 一次实现完成后做整体回顾。

**不使用**：

- 单份 RFC 的体检（用 `audit-docs`）。
- 单份 RFC 的评审（用 `interactive-rfc-generation/REVIEW_PROMPT.md`）。

## 1. 需要检查的输入

只检查与当前问题相关的子集：

- `docs/rfcs/RFC-*.md` 下的 RFC 文档。
- 相关代码与测试（`packages/orule-*/src/`）。
- 最近提交与 diff。
- 对话中的用户纠偏。
- 顶层 [`AGENTS.md`](../../../AGENTS.md) 与 [`ARCHITECTURE.md`](../../../ARCHITECTURE.md)。
- 提出 Skill 改进时参考现有 RFC 工作流 Skill（见 `.agents/README.md` §1 互链图）。

## 2. 归属分类规则

使用以下所有权模型：

- **顶层**：语言 / 日志 / 编码 / Git 策略 / 跨模块边界策略 / RFC 工作流。
- **`orule-common`**：DTO / 异常 / `ArtifactStorage` 接口 / 公共 Util。
- **`orule-rule-execution-service`**：Groovy 沙箱 / Kafka 事件 / HTTP API / Caffeine 缓存 / Resilience4j 限流 / 执行日志。
- **`orule-server`**：REST + 进程内 MCP / 元数据 CRUD / RuleSet 状态机 / 规则源 intake。
- **跨模块**：依赖边界、构件 API、公共运行时 API，以及证明两个模块互不泄漏的测试。

> **禁止**继续使用 `cruleengine / crulemgr / 顶层` 作为 RFC 章节归属名（这三个命名在本仓库不存在）。

## 3. 必需输出结构

```markdown
## 复盘结论

...

## 顶层规则

- ...

## orule-common

- ...

## orule-rule-execution-service

- ...

## orule-server

- ...

## 跨模块边界

- ...

## Skill 改进

- interactive-rfc-generation：
- rfc-driven-development：
- rfc-doc-review：
- rfc-driven-retrospective：
- audit-docs：
- rfc-push：

## 生成时检查

- ...

## 生成后检查

- ...

## 建议下一步

- ...
```

## 4. 常见问题检查

### orule-common 重点

- 是否意外引入业务框架依赖（Spring Web / Data JPA）。
- 是否出现重复 DTO / Util。

### orule-rule-execution-service 重点

- 运行时行为是否放在正确阶段（沙箱 / 事件 / API）。
- 大型协调器是否被塞入业务逻辑，而不是抽取聚焦 helper 类。
- 测试是否复用现有基类。
- 是否意外依赖 `orule-server`。

### orule-server 重点

- RuleSet 状态机 / RuleTrans / LLM / prompt / 调试包代码是否留在管控侧。
- 测试默认是否保持确定性（不依赖外部 HTTP）。
- 是否避免依赖 `orule-rule-execution-service` test-jar。

### RFC 生成重点

- RFC 是否包含模块归属表（指向本仓库真实模块）。
- RFC 是否包含复用优先清单。
- RFC 是否识别可推导字段并避免冗余输入。
- RFC 是否使用真实的项目测试 helper 和 DSL。

## 5. 验证时机

### 生成时（便宜、机械）

- 缺少模块归属。
- 缺少 STY-J / STY-D / STY-WF 引用。
- 未搜索相似测试。
- 忽略现有 helper。
- 不必要地发明测试 helper。
- 违反模块依赖方向。

### 生成后（依赖判断）

- 过度抽象。
- 错误的运行时阶段。
- 验收覆盖不完整。
- RFC 与实现偏移。
- 缺少 Skill / 规则文件更新。

## 6. 输出语言

除非用户另有要求，复盘使用简体中文。
代码标识符与命令保持原文。

## 7. 行为约束

- 复盘时不回避矛盾，直言不讳。
- 以反驳视角审视过去工作：哪些判断是错的？哪些遗漏了？
- 不知道某个决策的背景时，先搜索 SpecStory 与代码，不要凭印象编造。
- 区分"解释"与"预测"：事后解释不具备预测价值，应标注 `[INFERRED, post-hoc]`。
- 不得把错误归因于外部因素而不指出内部决策失误。
- 不得对不确定的决策背景捏造细节。如发现上述信号，添加 `[GUESS]` 标注并说明置信度。
