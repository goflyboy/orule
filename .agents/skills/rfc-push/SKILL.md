---
name: rfc-push
description: 完成 RFC 实现后，分两次提交代码与 `.specstory/` AI 交互日志。默认走 PR 流程；仅在用户明确说"直推 main"时才用 `--direct` 标记强推 main。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# RFC 分步提交

> 配套：`.agents/skills/interactive-rfc-generation/SKILL.md`
> 上游完成后调我。**不在 RFC 实现过程中调**。

---

## 0. 决策树（先看这张图）

```
你当前在哪个分支？
├── 默认 main（feature/* 已合并）
│   └── 走"默认"流程：开 PR → review → merge；不在本 Skill 范围
├── feature/<RFC-XXXX>
│   ├── 含 .specstory 相关文件？
│   │   ├── 是 → 走 §1（PR 模式 + 两次 commit）
│   │   └── 否 → 仅 §1 的"代码提交"部分
│   └── 含无关未跟踪文件？
│       └── 是 → 先 git stash / 单独提交，不混入本次
└── 用户明确说"直推 main"（含 --direct 标记）
    └── 走 §2
```

---

## 1. 默认：PR 模式（feature/<RFC-XXXX>）

### 1.1 第一次提交：代码提交

```bash
git add -A
git reset .specstory                # 先排除日志
git status                          # 必须仅含本次 RFC 相关代码变更
```

commit message 格式：

```text
RFC-XXXX: <description>
```

或兼容 conventional commit：

```text
<type>(<scope>): RFC-XXXX <description>
```

常用类型：`feat` / `refactor` / `fix` / `chore`。

```bash
git commit -m "RFC-XXXX: <description>"
```

### 1.2 第二次提交：日志提交（仅当有 .specstory 变更）

```bash
git add .specstory
git commit -m "chore(rfc): logs-<RFC-XXXX>-<description>"
```

`<description>` 与 §1.1 的 description 语义一致；保持简短，避免 100+ 字符。

### 1.3 推送与开 PR

```bash
git push origin feature/<RFC-XXXX>
gh pr create --title "RFC-XXXX: <title>" --body "见 docs/rfcs/RFC-XXXX-*.md"
```

---

## 2. 直推 main 模式（需用户明确指令 / --direct 标记）

仅当用户说"直推 main"、"no PR"、"--direct"时才走本节。

### 2.1 第一次提交：代码

```bash
git add -A
git reset .specstory
git commit -m "<type>(<scope>): <description>"
```

### 2.2 第二次提交：日志

```bash
git add .specstory
git commit -m "chore(rfc): logs-<description>"
```

### 2.3 推送

```bash
git push origin main
```

---

## 3. 约束（无论走 §1 还是 §2）

1. 两次提交必须严格分开，禁止把代码与 `.specstory` 合到同一次 commit。
2. 日志 commit message 格式 `chore(rfc): logs-<description>`，与代码 commit 语义对应。
3. 无关的未跟踪文件不要放入提交，除非用户明确要求。
4. RFC 编号必须出现在第一次 commit message 中（便于历史溯源）。
5. 默认走 PR；仅在用户显式指令下直推 main。

---

## 4. 与其他 Skill 的关系

- **上游**：`interactive-rfc-generation` 完成 RFC 评审后才能进入本 Skill。
- **验证**：若 AGENTS.md §STY-J001 / STY-J002 校验脚本失败，**禁止**进入本流程，先修代码。
- **复用**：若同分支还有别的改动（lint 修复 / 文档 typo），拆成独立 commit，不与 RFC-XXXX 混。
