# `.agents/` 目录总览

> 给 AI Agent 5 秒内决定"该用哪份 Skill"。
> 配套：[AGENTS.md](../../AGENTS.md) · [ARCHITECTURE.md](../../ARCHITECTURE.md)

---

## 0. Agent 决策树

用户给你的请求是 → 用哪份 Skill？

| 用户意图 | 用什么 |
|----------|--------|
| 生成或重大修订 RFC | `skills/interactive-rfc-generation/SKILL.md` |
| 评审已有 RFC（自评 / Reviewer） | `skills/interactive-rfc-generation/REVIEW_PROMPT.md` |
| 提交 RFC 实现（commit / push） | `skills/rfc-push/SKILL.md` |
| 体检 AGENTS.md / 任意 Skill / 任意 RFC | `skills/audit-docs/SKILL.md` |
| 仅改文档 typo / 链接 / 格式 | 不走 Skill，直接 edit |

## 1. 技能清单与互链

```
audit-docs
  ├── 上游：任意 Skill（被体检时）
  └── 下游：—

interactive-rfc-generation
  ├── 上游：—
  ├── 同目录：TEMPLATE.md（起草模板） · REVIEW_PROMPT.md（评审清单）
  └── 下游：rfc-push（实现完成后）

rfc-push
  ├── 上游：interactive-rfc-generation
  └── 下游：—
```

## 2. 文件清单

```
.agents/
├── README.md                                  # 本文件
└── skills/
    ├── audit-docs/
    │   └── SKILL.md
    ├── interactive-rfc-generation/
    │   ├── SKILL.md
    │   ├── TEMPLATE.md
    │   └── REVIEW_PROMPT.md
    └── rfc-push/
        └── SKILL.md
```

## 3. 与顶层文档的关系

- **AGENTS.md**：项目级入口规约，AI / 人类双视角。
- **ARCHITECTURE.md**：模块结构 + 模块依赖方向。
- 本 README：Skill 入口与决策树。

三者必须互相链接。如果发现断链，立刻跑 `audit-docs` 体检。

## 4. 添加新 Skill 的流程

1. 在 `skills/<skill-name>/` 创建 `SKILL.md`，附 `name` / `description` frontmatter。
2. 在本文件 §0 决策树加一行；§1 互链图加节点。
3. 跑 `audit-docs` 自检新 Skill。
4. 在 `AGENTS.md` 或合适位置引用（如适用）。
