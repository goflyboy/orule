---
name: rfc-driven-retrospective
description: RFC 驱动复盘工作流。RFC 生成或实现后，用户要求 review、retrospective、postmortem、复盘或流程改进时使用。重点区分顶层/cruleengine/crulemgr 的知识缺口、用户偏好和流程护栏，并提出 Skill、模块规则和项目知识的改进建议。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# RFC 驱动复盘

在 RFC 或基于 RFC 的实现完成后使用此 Skill，尤其适用于用户要求审查、复盘、总结失误或改进流程的场景。

## 目标

回答两个问题：

1. 下一份 RFC 如何更贴近项目现实？
2. 下一次实现如何更准确地遵守 RFC、模块边界、测试习惯和用户偏好？

目标不是追责，而是把遗漏的知识转化为规则、Skill、文档和检查项。

## 需要检查的输入

只检查与当前问题相关的子集：

- `doc/RFC-*.md` 下的 RFC 文档。
- 相关代码和测试。
- 最近提交和 diff。
- 对话中的用户纠偏。
- 顶层 `CLAUDE.md` 和 `.cursorrules`。
- 引擎工作参考 `cruleengine/CLAUDE.md` 和 `cruleengine/acceptance_core.md`。
- 管理器工作参考 `crulemgr/CLAUDE.md` 和 `crulemgr/acceptance.md`。
- 提出 Skill 改进时参考现有 RFC 工作流 Skill。

## 必需输出结构

结论必须按落点拆开：

```markdown
## 复盘结论

...

## 顶层规则

- ...

## cruleengine

- ...

## crulemgr

- ...

## 跨模块边界

- ...

## Skill 改进

- interactive-rfc-generation:
- rfc-driven-development:
- rfc-driven-retrospective:

## 生成时检查

- ...

## 生成后检查

- ...

## 建议下一步

- ...
```

## 归属分类规则

使用以下所有权模型：

- 顶层：语言、日志、编码、Git 策略、跨模块边界策略、RFC 工作流。
- `cruleengine`：运行时执行、求解、领域模型、规则 Schema、南向 API、北向执行 API、引擎语义/场景测试。
- `crulemgr`：RuleTrans、RuleUnit、LLM、Prompt、调试包、管理器 packer、管理器集成测试。
- 跨模块：依赖边界、构件 API、公共运行时 API，以及证明两个模块互不泄漏的测试。

## 常见问题检查

`cruleengine` 重点检查：

- 运行时行为是否放在正确阶段，尤其是过滤或求解之前。
- 大型协调器是否被塞入业务逻辑，而不是抽取聚焦 helper 类。
- 测试是否复用 `inferRecommendModule(...)`、`printSimpleSolutions()` 和 `assertSoluContain(...)`。
- 引擎主代码是否意外依赖管理器、RuleTrans、LLM 或 prompt 代码。

`crulemgr` 重点检查：

- RuleTrans、RuleUnit、LLM、prompt、调试包代码是否留在管理器。
- 测试默认是否保持确定性和零 token。
- 调试包行为是否保留原始 pipeline 失败。
- 管理器测试是否避免依赖 `cruleengine` test-jar。

RFC 生成重点检查：

- RFC 是否包含模块所有权表。
- RFC 是否包含复用优先清单。
- RFC 是否识别可推导字段并避免冗余输入。
- RFC 是否使用真实的项目测试 helper 和 DSL。

## 验证时机

生成时检查应捕获便宜、机械的问题：

- 缺少模块所有权。
- 缺少模块规则文件。
- 未搜索相似测试。
- 忽略现有 helper。
- 不必要地发明测试 helper。
- 违反 engine/mgr 边界。

生成后检查应捕获更依赖判断的问题：

- 过度抽象。
- 错误的运行时阶段。
- 验收覆盖不完整。
- RFC 和实现偏移。
- 缺少 Skill 或规则文件更新。

## 输出语言

除非用户另有要求，复盘使用简体中文。
代码标识符和命令保持原文。

## 行为约束（基于李开复 Claude 全局提示词）

复盘过程中必须遵循以下约束：

### 来源标注

| 标签 | 含义 |
| --- | --- |
| `[KNOWN]` | 明确的事实、RFC 原文、代码证据 |
| `[COMPUTED]` | 跨维度关联分析 |
| `[INFERRED]` | 未直接说明的隐含逻辑 |
| `[GUESS]` | 未经核实的猜测，置信度上限 LOW |

禁止未标注的类名、方法名、引用或断言。

### 角色行为

- 复盘时不回避矛盾，直言不讳。
- 以反驳视角审视过去工作：哪些判断是错的？哪些遗漏了？
- 不知道某个决策的背景时，先搜索 SpecStory 和代码，不要凭印象编造。
- 区分"解释"与"预测"：事后解释不具备预测价值，应标注 `[INFERRED, post-hoc]`。

### 反谄媚约束

复盘时不得：

- 圆滑措辞美化失败。
- 把错误归因于外部因素而不指出内部决策失误。
- 对不确定的决策背景捏造细节。

如发现上述信号，添加 `[GUESS]` 标注并说明置信度。
