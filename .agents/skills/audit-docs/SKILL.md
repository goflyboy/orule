---
name: audit-docs
description: 对仓库内任意 `*.md` / Skill / 模板做系统性体检。用户在以下场景触发："体检 / audit / review / 评审这份文档"、"检查这份 RFC"、"看 AGENTS.md 有没有问题"、"看这份 Skill 有没有问题"。报告落到 `tempdocs/audit-<日期>/`。流程：识别文档类型 → 按对应章节跑检查项 → 输出"严重 / 建议 / 已通过"三档。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# 文档体检

> 配套：[AGENTS.md](../../../AGENTS.md) · [.agents/README.md](../../README.md)
> 与 `interactive-rfc-generation` 的区别：那边负责"生成新 RFC"；本 Skill 负责"对已存在文档做体检"。

---

## 0. 何时使用 / 何时不使用

**使用**：

- 用户说"体检 / audit / 评审 AGENTS.md"或某份 RFC。
- RFC 提交前的自评（也可走 `interactive-rfc-generation/REVIEW_PROMPT.md`）。
- Skill 重构前后对比（参见 `tempdocs/audit-2026-09-19/01-AGENTS与Skill系统性体检报告.md` 的体检模板）。

**不使用**：

- 用户要求改文档内容（用普通 edit 即可，不要顺手出一份报告）。
- 用户要求生成新文档（用 `interactive-rfc-generation`）。

---

## 1. 通用体检项（所有 .md 适用）

按顺序检查，任一项未过即标 🔴：

1. **链接可达**：所有相对 / 绝对链接 `Glob` 后必须存在；`http://` / `https://` 链接允许外部，但需说明"未实时校验"。
2. **模块名一致**：提到的模块名必须在本仓库 `packages/` 下真实存在（`orule-common / orule-rule-execution-service / orule-server / tests`）。文件中出现的 `cruleengine / crulemgr / 顶层` 等不在本仓库的命名 → 🔴。
3. **规则可追溯**：每条编号规则（如 STY-J001）必须能指向一个具体违规文件 / 行号（现状违规登记段）或指向一个可机械验证的脚本。
4. **互链骨架**：核心入口文件（AGENTS.md / ARCHITECTURE.md / 本 README）必须互相链接。
5. **职责单一**：单文件不超过 300 行；超过则建议拆分（🟡）。
6. **AI / 人类视角分离**：核心入口文件如有 AI 专用段，必须用 `<!-- for-agents-start -->` / `<!-- for-agents-end -->` 包裹。

## 2. AGENTS.md / ARCHITECTURE.md 专项体检

在 §1 基础上加查：

- [ ] "速查表"段（如有）覆盖所有编号规则
- [ ] 模块依赖方向与 ARCHITECTURE.md §0 一致
- [ ] 现状违规段（若有）每条都有 owner 或 TBD 占位

## 3. RFC 体检

使用 `interactive-rfc-generation/REVIEW_PROMPT.md` 的 §1–§5 维度；在本 Skill 中：

- 报告落到 `tempdocs/audit-<日期>/RFC-XXXX-review.md`
- 报告引用 `REVIEW_PROMPT.md §6` 的输出格式

## 4. Skill 体检

在 §1 基础上加查：

- [ ] `name` / `description` frontmatter 完整
- [ ] description 精准，不含"完善 / refine"等模糊触发词
- [ ] 不夹带与 Skill 主题无关的"全局提示词"
- [ ] 必读文件路径在本仓库 `Glob` 后非空
- [ ] 与上游 / 下游 Skill 显式互链

---

## 5. 报告输出格式

```markdown
# <被体检文件名> 体检报告

> 日期：YYYY-MM-DD
> 范围：<文件路径>

## 整体评价

[优秀 / 良好 / 需要改进]

## 🔴 严重问题

1. [问题描述]
   - 证据：[文件:行号 / grep 结果]
   - 修复建议：[具体改动]

## 🟡 建议改进

1. [改进建议]

## ✅ 已通过

- [列出通过的项目，对照 §1–§4]

## 与上一份体检对比（如有）

- [指标前后对比]
```

---

## 6. 输出约束

- 默认报告写到 `tempdocs/audit-<日期>/<被体检文件名>-体检.md`。
- 同一日期对同一文件多次体检，文件名加序号 `-1 / -2`。
- 报告本身不得引入新的死链。
