# orule 现有 Skill vs Superpowers 对比与引入建议

> 状态：草稿（Draft）
> 日期：2026-09-12
> 适用：orule 仓库维护者
> 关联：`tmpdocs/AI时代的规则系统构想.md`、`.agents/skills/`、`docs/rfcs/README.md`

---

## 一、问题背景

orule 仓库已经在 `.agents/skills/` 下沉淀了 8 个 RFC 工作流 Skill，覆盖 RFC 生成、架构设计、驱动开发、文档审查、复盘、RuleTrans 调试。每一份 Skill 写得都偏细，反谄媚、来源标注（`[KNOWN]/[COMPUTED]/[INFERRED]/[GUESS]`）、模块边界（cruleengine / crulemgr）都覆盖了。

但是在和 `https://github.com/obra/superpowers`（obra/superpowers，285k stars，Jesse Vincent / Prime Radiant 维护，跨 12 个 harness 通用）做完一次端到端对照之后，能清楚看到两件事：

1. **现有的 Skill 有真实的护栏优势，但缺骨架**（没有强制 TDD、没有 worktree、没有代码审查、没有子智能体协作）。
2. **Superpowers 有现成的方法论骨架，但缺项目特异性**（不认 cruleengine/crulemgr/SimpleTS/RuleTrans 这些本仓库的硬约束）。

结论不是二选一，是 hybrid。下面的对比和引入建议都基于这个立场。

---

## 二、Superpowers 是什么

### 2.1 一句话定义

Superpowers 不是一份孤立 Skill 集合，而是一套 **codegen agent 的完整 SDLC（软件开发生命周期）方法论**，包含 7 步标准工作流 + 4 条哲学 + 14 个 Skill + 跨 12 个 harness 的 plugin 适配层。

### 2.2 它和你现有体系的根本差异

**强制触发机制**——Superpowers 的核心护栏是 hooks + bootstrap prompt，agent 每个回合开始都被强制要求"先看相关 skill，再决定怎么干"。具体方式：

- 通过 marketplace 安装后，Cursor 会注册 `using-superpowers` 这个 meta skill 在每个会话开头自动加载。
- AI 的每一步动作前会自检"有没有相关 Skill 要用"，并强制执行。
- README 关键原话："The agent checks for relevant skills before any task. Mandatory workflows, not suggestions."

你仓库里现在的触发是软性的：AI 看 `description` 字段后自选 Skill。**这种机制不能保证一致执行。**

---

## 三、Superpowers 的 7 步工作流

来自 README "The Basic Workflow"：

| 步骤 | 触发 Skill | 对应你现有的什么 | 缺口 |
|---|---|---|---|
| 1. 头脑风暴 | `brainstorming` | 你的 `interactive-rfc-generation` 阶段 1（问答修订） | 基本对齐，但缺少工作树隔离 |
| 2. Git 工作树 | `using-git-worktrees` | 你**没有** | ⚠️ 缺口（直接 main 风险大） |
| 3. 写计划 | `writing-plans` | 你**没有**独立 Skill，混合在 `rfc-driven-development` | ⚠️ 缺口（粒度偏粗） |
| 4. 子智能体执行 | `subagent-driven-development` 或 `executing-plans` | 你**没有** | ⚠️ 缺口（核心差异） |
| 5. TDD（测试驱动开发） | `test-driven-development` | 你**没有**强制 TDD，只有"先写后写" | ⚠️ 缺口 |
| 6. 代码审查 | `requesting-code-review` + `receiving-code-review` | 你有 `rfc-doc-review` 但**没有 code review** | ⚠️ 缺口 |
| 7. 完成开发分支 | `finishing-a-development-branch` | 你的 `rfc-push` 只覆盖了"推送"，没合并/PR/保留/丢弃决策 | ⚠️ 缺口 |

---

## 四、Superpowers 的 4 条哲学

| Superpowers 哲学 | 你仓库里对应的约束 |
|---|---|
| **TDD（测试驱动开发）**——测试先行 | 你没强制 |
| **系统化优于临时**——流程而非猜测 | 你的 SKILL.md 里写"流程化" |
| **降低复杂度**——简单是首要目标 | 你的"保守实现 / 复用优先 / 不新增" |
| **证据优于声明**——完成前必须验证 | 你的 `[KNOWN]/[COMPUTED]/[INFERRED]/[GUESS]` |

**重要发现**：你的"反谄媚 / 来源标注 / 不编造类名"和 Superpowers 的 evidence-based 是**同一思路的不同表达**。你们走的是同一条路，Superpowers 用了更成熟的方法论把它系统化了。

---

## 五、Superpowers 的核心机制差异：子智能体驱动开发（subagent-driven-development）

这是它和现有体系**最大的差异点**：

**你现在的流程**：
```
用户 → AI 读 RFC → AI 实现 → AI 写测试 → AI 提交
```

**Superpowers 的流程**：
```
用户 → AI 读 RFC → AI 拆 plan
    → 主 agent dispatch sub-agent
    → sub-agent 实现
    → review agent 1 查 spec 合规
    → review agent 2 查代码质量
    → 主 agent 接受 / 打回
```

**优势**：每个 task 独立上下文 + 独立 reviewer，主 agent 不会被"自己写的代码"的偏见影响。

**代价**：token 消耗 ×3~4 倍、流程变长、需要 sub-agent 工具支持（Cursor 是否允许长跑 sub-agent 需要先验证）。

---

## 六、14 个维度对比表

| # | 维度 | 你现在 | Superpowers | 谁更优 |
|---|---|---|---|---|
| 1 | 设计理论骨架 | rfc-arch 用 4+1+ADD+ADR+DDD+RFC 迭代 | brainstorming + writing-plans，无显式骨架 | 你更严谨，Superpowers 更轻 |
| 2 | 触发机制 | 软触发，AI 读 description 后自选 | 硬触发，session-start hook 强制自检 | Superpowers 远胜 |
| 3 | 共享底层规则 | 重复且分散 | 集中——`using-superpowers` 是入口，每个 Skill 引用它 | Superpowers 远胜 |
| 4 | 子智能体协作 | 单线程 | subagent-driven-development + 两阶段审查 | Superpowers 远胜 |
| 5 | Plan 粒度 | 实现所有权卡片（粗粒度） | 每个 task 2-5 分钟、含完整代码和验证 | Superpowers 远胜 |
| 6 | TDD 强制 | 无 | test-driven-development 强制 红-绿-重构 | Superpowers 远胜 |
| 7 | 系统化调试 | ruletrans-system-test-debugging（项目专用） | systematic-debugging 4-phase + 根因追踪 + 防御深度 | 平手，你的项目专用更细 |
| 8 | Git 工作树隔离 | 无 | using-git-worktrees | Superpowers 胜 |
| 9 | 代码审查 | rfc-doc-review 偏文档 | requesting-code-review + receiving-code-review（双向） | Superpowers 胜 |
| 10 | 完成收口 | rfc-push（在 main 直推） | finishing-a-development-branch（合并/PR/保留/丢弃四选） | Superpowers 远胜 |
| 11 | 跨 harness（适配环境）支持 | 只为 Claude/Cursor 写 | 同时支持 12 个 harness | Superpowers 胜 |
| 12 | 项目特异性 | 模块边界、cruleengine/crulemgr、SimpleTS、RuleTrans | 通用 | 你远胜（不可替换） |
| 13 | 来源标注 + 反谄媚 | 严谨 | ⚠️ 无显式标注，靠 evidence over claims 隐含 | 你更严 |
| 14 | 测试金字塔 | 部分覆盖（场景、单元、cassette、live） | TDD + 系统化调试 + verification-before-completion | Superpowers 更系统 |

---

## 七、立刻能用上的 Superpowers Skill（5 个）

通用最佳实践，接入成本低，立刻能补齐你的短板：

### 7.1 `test-driven-development`

**你的现状**：没强制 TDD。

**建议**：直接 copy `skills/test-driven-development/SKILL.md` 到 `.agents/skills/tdd/SKILL.md`，强制要求"测试先于实现"。

### 7.2 `systematic-debugging`

**你的现状**：有 `ruletrans-system-test-debugging`（项目专用），但没有通用调试流程。

**建议**：补通用调试流程，4-phase root cause。你的 `ruletrans-system-test-debugging` 叠加在上面，形成"通用 + 专用"两层。

### 7.3 `verification-before-completion`

**你的现状**：`[KNOWN]/[GUESS]` 是标注体系，但没强制"完成前必须验证"。

**建议**：直接和 `[KNOWN]/[GUESS]` 配套——每个任务末尾强制跑一遍 verification 清单。

### 7.4 `using-git-worktrees`

**你的现状**：没有。RFC 实现现在直 main。

**建议**：强烈建议 RFC 一旦批准，先开 worktree（隔离开发分支）。

### 7.5 `finishing-a-development-branch`

**你的现状**：`rfc-push` 只覆盖了"推送"，没覆盖"merge / PR / 保留 / 丢弃"决策。

**建议**：补齐后端。

---

## 八、不要替换的 Skill（3 个）

**不要替换**：

- `interactive-rfc-generation` 的"模块所有权 + 复用优先"——Superpowers 没有这种护栏（cruleengine/crulemgr 是项目专有的）。
- `ruletrans-system-test-debugging`——Superpowers 完全无法生成这种级别，它的失败分流（Java 错配 / 业务用例错配 / 引擎 bug / 诊断不足）是项目级 know-how。
- 你的"反谄媚 / 来源标注"——比 Superpowers 的 evidence-based 更细。

---

## 九、值得参考但需要本地化的 Skill（3 个）

### 9.1 `brainstorming`

Superpowers 的 Socratic design refinement，但没有你的"必填模块所有权章节"和"复用优先章节"。

**建议**：把它的方法论合到你现有的 `interactive-rfc-generation`，不要直接替换。

### 9.2 `writing-plans`

把你的"实现所有权卡片"细化到"每个 task 2-5 分钟，含完整代码"。

**建议**：大改你的 `rfc-driven-development`。

### 9.3 `subagent-driven-development`

架构级变化。要不要引入，取决于团队是否愿意接受 sub-agent 审查带来的延迟和 token 成本。

**建议**：短期不建议，长期值得试点。

---

## 十、行动建议（按风险递增）

### 步骤 1：立即可做（5 分钟）

```bash
# 在 Cursor Agent 聊天里直接运行
/add-plugin superpowers
```

这是 README 给的命令。装好后 Superpowers 的 hooks 立即接管你的 agent。两边可以共存——你的 Skill 在 `.agents/skills/`，Superpowers 的 Skill 在它的 marketplace 里。先跑一两个 RFC，看实际体验。

**注意**：Superpowers README 里写了一个遥测细节（"Prime Radiant logo on brainstorming's optional visual companion"）。如果对隐私敏感，先设环境变量 `SUPERPOWERS_DISABLE_TELEMETRY=1`。

### 步骤 2：强烈建议（先内部重构，再外接）

你的 8 个 skill **重复和层次混乱**。理由：

- Superpowers 装上后，它会读你仓库的 `AGENTS.md`、`.cursor/rules/`、`.agents/skills/`。
- **重复内容 = AI 上下文污染 = 越执行越乱**。

**重构清单**：

| 动作 | 目标 |
|---|---|
| 把 `[KNOWN]/[GUESS]` / 反谄媚封装到 `.cursor/rules/_shared-quality.mdc` | 统一来源标注 |
| 把模块边界封装到 `.cursor/rules/_shared-boundaries.mdc`（cruleengine/crulemgr/STY-J001/J002） | 统一项目护栏 |
| 把 `interactive-rfc-generation` 和 `rfc-doc-review` 合并成单一 RFC 工作流 | 消除 Skill 间"谁有权改 RFC"冲突 |
| 在每个 Skill 顶部引用 `_shared-quality.mdc` 和 `_shared-boundaries.mdc`，不再内联 | 避免规则漂移 |
| 删除 `rfc-arch` 的 `assets/` 占位 | 清理空目录 |

### 步骤 3：中期试验（1~2 周）

1. 把 Superpowers 的 `test-driven-development` 作为 RFC 模板的硬约束（写进 `docs/rfcs/RFC-0000`）。
2. 把 `using-git-worktrees` 接到 `rfc-driven-development`——RFC 批准后立即开 worktree。
3. 试点 1~2 个 RFC 跑完 TDD Skill，对比历史 RFC 的 bug 密度。

### 步骤 4：长期赌注（评估期 1 个月）

如果团队愿意承担 token 成本，试点 `subagent-driven-development` 在 RFC-0030（你已经有 MVP 验收演示，跨全栈）这个**最大、最复杂**的 RFC 上。这是验证 sub-agent 工作流对项目价值的最好试验场。

---

## 十一、未决事项

- Superpowers 的 sub-agent 工具支持在你的 Cursor 版本下是否能稳定跑——未实测，建议先跑步骤 1 看实际体验再决定步骤 4。
- 你现有的 `interactive-rfc-generation` 和 `rfc-doc-review` 之间有"谁有权改 RFC"的命名冲突（`rfc-doc-review` 里写"以本 Skill 为准"）。这次重构要不要顺便收敛，需要你定。
- `_shared-quality.mdc` 和 `_shared-boundaries.mdc` 的初稿要不要我帮你写——我已经在三个对话轮次里提了两次，需要你点头我才会落盘。

---

## 十二、参考资料

- `https://github.com/obra/superpowers` —— Superpowers 主页，285k stars，MIT 协议。
- `tmpdocs/AI时代的规则系统构想.md` —— 姊妹文档，本仓库规则系统构想。
- `.agents/skills/` —— 现有 8 个 Skill。
- `docs/rfcs/README.md` —— RFC 索引。
- `AGENTS.md` —— 现有 STY-J001 / STY-J002 风格规约。

