---
name: interactive-rfc-generation
description: 生成或重大修订 RFC 时使用。轻量场景（仅改标题 / 补一段参考）跳过此 Skill，直接改文件即可。本 Skill 适用场景：新增 RFC、跨模块变更、新增公共 API、引入新依赖、修改模块依赖方向。流程：模块归属判断 → 读 `AGENTS.md` §0.1 → 按 `TEMPLATE.md` 起草 → 用 `REVIEW_PROMPT.md` 自评 → 用户审 → 合入。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# 交互式 RFC 生成

> 配套：[AGENTS.md](../../../AGENTS.md) · [ARCHITECTURE.md](../../../ARCHITECTURE.md) · [`.agents/README.md`](../../README.md)
> 配套模板：本目录下 `TEMPLATE.md` · 评审：本目录下 `REVIEW_PROMPT.md`

## 0. 何时使用 / 何时不使用

**使用**：新增 RFC；对既有 RFC 做重大修订（新增章节、改模块归属、影响 API）；跨模块变更。

**不使用**（直接改文件即可）：

- 仅修改 RFC 标题 / 编号 / 日期。
- 仅补充"参考资料"中的死链。
- 仅修 typo / 格式。
- 评审 RFC（用本目录的 `REVIEW_PROMPT.md`，不走本 Skill）。

## 1. 核心流程（5 步）

1. **识别 RFC 范围**：读用户请求，区分"新增 / 修订 / 重写"；明确触发场景（见 description）。
2. **判断模块归属**：按 [ARCHITECTURE.md §0.1](../../../ARCHITECTURE.md) 三问决策，落到 `orule-common / orule-rule-execution-service / orule-server / 跨模块` 之一。
3. **读模块规则**：写代码段前必读 [AGENTS.md §2 Java 规约](../../../AGENTS.md)。写文档段前必读 §3 文档规约。
4. **起草**：按 `TEMPLATE.md` 写 RFC，落到 `docs/rfcs/RFC-XXXX-<title>.md`。
5. **自评**：用 `REVIEW_PROMPT.md` 自评；任一项 🔴 严重问题未修复前不提交。

## 2. 必需的模块归属章节

每份 RFC 都必须包含：

```markdown
### 模块归属

- 范围：orule-common | orule-rule-execution-service | orule-server | 跨模块（同时指明哪两个）
- 模块责任：1 句话说清本次改动在该模块的哪一块
- 跨模块依赖：列出新增 / 修改的对其他模块的依赖方向；若为"跨模块"必填
```

## 3. 复用优先章节

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

跨模块 RFC 按模块拆分该章节。

## 4. RFC 质量门（提交前必须满足）

- [ ] RFC 明确说明属于 `orule-common / orule-rule-execution-service / orule-server` 或跨模块（"顶层"是 ARCHITECTURE.md 已废用语，禁止使用）。
- [ ] 没有引入反向依赖（见 [ARCHITECTURE.md §0 依赖方向](../../../ARCHITECTURE.md)）。
- [ ] 没有引入与 `AGENTS.md` §2 Java 规约冲突的写法。
- [ ] 测试示例优先使用项目已有测试基类与 helper。
- [ ] 新字段有明确理由；可推导字段不要求用户重复填写。
- [ ] RFC 包含具体可执行的验收命令（`mvn -pl <module> test -Dtest=...`）和模块专属测试。

## 5. 输出要求

除非用户另有要求，解释和 RFC 正文使用简体中文。
代码标识符、命令和日志字符串保持原文。
链接必须真实可达——起草后跑一次 `audit-docs` Skill 自检。
