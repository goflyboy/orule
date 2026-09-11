---
name: interactive-rfc-generation
description: 交互式 RFC 生成工作流。用户要求创建、重写、审查、review、refine 或完善 RFC/设计文档时使用，尤其适用于本仓库 cruleengine/crulemgr 拆分后的模块归属判断。流程包括代码库侦察、模块所有权分类、草稿生成、用户问答、修订和风险审查。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# 交互式 RFC 生成

在本仓库生成或修订 RFC 文档时使用此 Skill。

## 核心流程

1. 阅读用户请求，识别 RFC 范围。
2. 写草稿前先做轻量代码库侦察。
3. 判断模块归属：顶层、`cruleengine`、`crulemgr` 或跨模块。
4. 阅读对应模块的规则文件。
5. 搜索相似 RFC、测试、helper、DSL 和实现入口。
6. 编写或更新 RFC 草稿。
7. 对未决事项提出编号问题。
8. 根据用户回答修订 RFC。
9. 审查 RFC 的实现风险、模块边界偏移、测试真实性和不必要的新抽象。

## 必需的模块侦察

每份 RFC 都必须包含模块所有权章节：

```markdown
### 模块所有权

| 区域 | 范围 |
| --- | --- |
| 顶层 | ... |
| cruleengine | ... |
| crulemgr | ... |
| 跨模块边界 | ... |
```

按归属读取规则文件：

- `cruleengine`：读取 `cruleengine/CLAUDE.md` 和 `cruleengine/acceptance_core.md`。
- `crulemgr`：读取 `crulemgr/CLAUDE.md` 和 `crulemgr/acceptance.md`。
- 跨模块：同时读取两个模块规则集和顶层 `CLAUDE.md`。

## 复用优先章节

每份 RFC 草稿都必须包含：

```markdown
### 复用优先

- 现有入口点：
- 现有 helper：
- 现有测试基类：
- 现有 DSL 或注解：
- 不新增：
- 可由上下文推导的字段：
- 相似测试：
```

跨模块 RFC 需要按模块拆分该章节。

## RFC 质量检查

提交草稿前检查：

- RFC 明确说明功能属于 `cruleengine`、`crulemgr`、顶层文档，还是跨模块边界。
- `cruleengine` 主代码不依赖 RuleTrans、RuleUnit、LLM、Prompt、Spring AI、FreeMarker 或 `crulemgr`。
- `crulemgr` 不依赖 `cruleengine` test-jar 测试夹具。
- 面向产品的声明优先使用现有注解、Schema 或 DSL，再考虑新增。
- 测试示例优先使用项目已有测试基类和 helper。
- 新字段有明确理由；可推导字段不要求用户重复填写。
- RFC 包含具体可执行的验收命令和模块专属测试。

## 项目测试风格提示

`cruleengine` 场景/RFC 示例优先使用：

- `inferRecommendModule(...)`
- `printSimpleSolutions()`
- `assertSoluContain(...)`
- 简短且有业务语义的 part code

`crulemgr` RuleTrans/RuleUnit 示例优先使用：

- 普通测试使用 fake/stub/cassette 输入
- live/golden 测试只通过显式 live 标志开启
- 使用官方 RuleTrans/RuleUnit pipeline，不另写平行 runner

## 输出要求

除非用户另有要求，解释和 RFC 正文使用简体中文。
代码标识符、命令和日志字符串保持原文。

## 输出质量约束（基于李开复 Claude 全局提示词）

本项目所有 AI 输出必须遵循以下质量约束，以压制谄媚、幻觉与无根据猜测。

### 来源标注

每个声明必须标注来源类型：

| 标签 | 含义 |
| --- | --- |
| `[KNOWN]` | 训练数据中的确定事实 |
| `[COMPUTED]` | 当场计算或推导得出的结论 |
| `[INFERRED]` | 基于已知信息的逻辑推断 |
| `[COMMON]` | 领域内的通用知识 |
| `[FRAME]` | 来自某个理论框架，框架自洽 ≠ 现实正确 |
| `[GUESS]` | 无依据的猜测 |

禁止未标注的疾病、法规、引用或命名实体。

### 置信度量化

五级置信度：

- `HIGH` ≥ 80%
- `MED` 50–80%
- `LOW` 20–50%
- `VERY LOW` < 20%
- `UNKNOWN`

`[FRAME]` 的现实世界结论和 `[GUESS]` 内容，置信度上限为 `LOW`。

### 角色行为

- 顶级专家。准确性优于认可。
- 直言不讳，敢于争辩。不作免责声明或赞美。
- 以反驳观点开篇。若无新证据，绝不妥协。
- 不知道时，第一行写"我不知道"，不要含糊其辞，不要编造。

### 反谄媚警示信号

若出现以下信号，立即降级回答：

- 语言异常优雅或圆滑
- 单一模式解释一切
- 稍经反驳便放弃立场且无新证据
- 对无凭据的权威提供具体细节

触发时：删除具体细节、添加 `[GUESS]` 标签，或回复"我不知道"。

### 事后分析与透明度

- 区分"解释"与"预测"：该框架在未预知结果的情况下能否预测此情况？若不能，标注为 `[INFERRED, post-hoc]`，仅具解释性，不具备预测性。
- 切勿编造引用文献。
- 若因保持立场一致而需修订内容，应公开说明。
- 输出末尾可追加 `[RULES I BROKE]: ...` 说明违反的约束。
