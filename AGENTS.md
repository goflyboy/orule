# orule 仓库编码规约（AGENTS.md）

> 本文件是 AI Agent 与人类贡献者**共同**遵守的入口规约。
> 所有规则编号稳定，便于在 RFC / ADR / Code Review 中引用。
> 配套索引：[ARCHITECTURE.md](../../ARCHITECTURE.md) · [.agents/README.md](../../.agents/README.md)
> 配套模板：`.agents/skills/interactive-rfc-generation/TEMPLATE.md`
> 配套体检：`.agents/skills/audit-docs/SKILL.md`

---

## 0. 速查表

| 编号 | 主题 | 强制等级 | 验证方式 |
|------|------|----------|----------|
| STY-J001 | Java 类型引用一律走 `import`，禁止 inline 限定符 | 强制 | `scripts/lint-imports.ps1` |
| STY-J002 | JUnit 5 测试类必须 `public class` | 强制 | `scripts/check-test-public.ps1` |
| STY-D001 | 文档改动先于代码改动 | 强制 | RFC 编号 + Review 流程 |
| STY-D002 | 文档内链接必须可解析（无死链） | 强制 | `audit-docs` Skill |
| STY-WF001 | RFC 配套规范：模板 / 编号 / 评审 / 提交 | 强制 | rfc-driven-development Skill |
| STY-WF002 | 两次提交：代码与 `.specstory` 日志分两次 commit | 强制 | rfc-push Skill |

<!-- for-humans-start -->

## 1. 项目结构（人类贡献者必读）

本仓库是 monorepo，由 Maven 多模块组成：

| 模块 | 路径 | 责任 |
|------|------|------|
| `orule-common` | `packages/orule-common/` | 公共 DTO / 异常 / Storage 接口 / Util |
| `orule-rule-execution-service` | `packages/orule-rule-execution-service/` | 规则执行服务（Groovy 沙箱、Kafka 事件、HTTP API） |
| `orule-server` | `packages/orule-server/` | 管控服务（REST + 进程内 MCP） |
| `tests/scripts` | `tests/scripts/` | 开发脚本与启动器测试 |

完整 4+1 视图见 [ARCHITECTURE.md](../../ARCHITECTURE.md)。

<!-- for-humans-end -->

## 2. Java 编码规约（STY-J）

### STY-J001 — import vs inline 限定符

**规则**：Java 代码中，类型引用一律走 `import`。**禁止在代码体内使用带包路径的 inline 限定符**（如 `org.slf4j.Logger`、`com.foo.Bar`），即便只是为了少写一行 import。

**反例（禁止）**：

```java
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(X.class);
org.springframework.util.StringUtils.hasText(...);
```

**正例（推荐）**：

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

private static final Logger log = LoggerFactory.getLogger(X.class);
StringUtils.hasText(...);
```

**例外（明确允许 inline 限定符）**：

- `package` 与 `import` 语句本身。
- 注解处理器 / 注解类自身的反射常量等少量框架强制的场景（出现时需在 PR 描述里说明）。
- 同名类消歧义（如 `java.util.Date` 与 `java.sql.Date`）。

**验证**：`pwsh scripts/lint-imports.ps1`。

**范围**：整个仓库 Java 源代码（含 `src/main` 与 `src/test`）。

> **现状违规登记**（本条规则发布时的存量违规）：
>
> | 文件 | 行 | 违规片段 | owner | 关联 RFC |
> |------|----|---------|-------|----------|
> | `packages/orule-common/src/main/java/com/orule/common/storage/LocalStorage.java` | 27 | `org.slf4j.LoggerFactory.getLogger(LocalStorage.class)` | TBD | RFC-0017 |
>
> owner 由维护者指派，未指派前视为"默认 @maintainers"。

### STY-J002 — JUnit 5 测试类可见性

**规则**：测试类（任何带 `@Test` 方法的类）必须声明为 `public class`，否则 JUnit 5 平台与主流 IDE（VS Code / Cursor 的 `vscjava.vscode-java-test`、IntelliJ）的测试发现器可能跳过该类。

**反例**：`class LocalStorageTest { ... }`

**正例**：`public class LocalStorageTest { ... }`

**验证**：`pwsh scripts/check-test-public.ps1`。

**范围**：所有 `**/src/test/java/**/*.java`。

> **现状违规登记**（重构前全仓命中 34 处）：owner 待指派；统一迁移到 `public class` 后清零。
>
> 备注：JUnit Jupiter 引擎在测试方法 `public` 且类可见性宽松时也可能发现，但 IDE 默认扫描行为依赖类可见性，故本条按 IDE 友好优先。

---

## 3. 文档规约（STY-D）

<!-- for-agents-start -->

### STY-D001 — 文档改动先于代码改动

任何**会改变外部行为**或**新增模块边界**的代码改动，**必须**先有对应的 RFC / ADR / `docs/*.md` 章节更新，并附 RFC 编号。

> 这是 Agent 必读段：用户给的"做这个改动"如果是行为级，先问"RFC 在哪"，没有就提示走 `interactive-rfc-generation` Skill。

### STY-D002 — 文档内链接必须可解析

`*.md` 内所有相对 / 绝对链接都必须指向真实存在的文件。Agent 改完文档后必须 `Glob` 验证。

> 这是 Agent 必读段：在 `Write` / `StrReplace` 文档类文件后，跑一次 audit-docs Skill 自检。

<!-- for-agents-end -->

<!-- for-humans-start -->

完整规约与例外见 `docs/adr/` 与 `docs/rfcs/RFC-0000-MVP-RFC总览.md`。

<!-- for-humans-end -->

---

## 4. 工程流规约（STY-WF）

### STY-WF001 — RFC 配套流程

任何跨模块 / 跨包 / 新增公共 API / 引入新依赖的改动，**必须**经过：

1. 读 `.agents/skills/interactive-rfc-generation/SKILL.md` 决定 RFC 类型。
2. 按 `TEMPLATE.md` 起草 RFC，落到 `docs/rfcs/`。
3. 用 `REVIEW_PROMPT.md` 自评。
4. 提交 PR → Review → 合入。

### STY-WF002 — 两次提交切分

代码与 `.specstory/` AI 交互日志必须分两次 commit，日志 commit 信息格式 `chore(rfc): logs-<代码提交描述>`。详见 `.agents/skills/rfc-push/SKILL.md`。

<!-- for-agents-start -->

## 5. AI Agent 必读

- **进来先读** [`.agents/README.md`](../../.agents/README.md) 决定走哪份 Skill。
- **写文档前** 先 `audit-docs` 体检现有相关文档。
- **写代码前** 先看 `STY-J` 段；写完后**主动跑**对应校验脚本。
- **不确定时**：用 AskQuestion 问用户，不臆测。

<!-- for-agents-end -->

---

## 6. 规则的添加与修订

新增规则必须满足：

1. 提供至少 1 条 `[KNOWN]` 或 `[COMPUTED]` 的事实证据（grep 行号 / 文件路径）。
2. 提供 ≤ 50 行的验证脚本（若不能验证，降级为"建议"）。
3. 在 `## 0. 速查表` 中登记。
4. 在 PR 描述中关联至少 1 份 RFC / ADR（无则先开 RFC）。
