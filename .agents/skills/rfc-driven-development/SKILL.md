---
name: rfc-driven-development
description: RFC 驱动开发工作流。用户要求根据 RFC/设计文档实现代码、提到 RFC-XXXX，或要求"RFC 批准后开发"时使用。流程：读 RFC → 划分 `orule-common / orule-rule-execution-service / orule-server / 跨模块` 归属 → 实现 → 测试 → 验证 → 提交，并在用户要求时推送。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# RFC 驱动开发

> 配套：[`.agents/README.md`](../../README.md) · [`.agents/skills/interactive-rfc-generation/SKILL.md`](../interactive-rfc-generation/SKILL.md) · [`.agents/skills/rfc-push/SKILL.md`](../rfc-push/SKILL.md) · [`.agents/skills/audit-docs/SKILL.md`](../audit-docs/SKILL.md)
> 上游：`interactive-rfc-generation`（已完成 RFC 评审）
> 下游：`rfc-push`（实现完成要提交时调我）

## 0. 何时使用 / 何时不使用

**使用**：用户说"按 RFC-XXXX 实现"、"RFC 批准后开发"、"实现这份 RFC"。

**不使用**：

- 仅生成 / 修改 RFC（用 `interactive-rfc-generation`）。
- 体检 RFC 文本（用 `audit-docs` 或 `interactive-rfc-generation/REVIEW_PROMPT.md`）。
- 提交已写好的代码（用 `rfc-push`）。

## 1. 阶段 1：理解范围

1. 完整读取用户引用的 RFC。
2. 编辑代码前填写实现所有权卡片：

```text
模块归属：
- orule-common：
- orule-rule-execution-service：
- orule-server：
- 跨模块边界：

预计修改文件：
- orule-common 代码/测试/文档：
- orule-rule-execution-service 代码/测试/文档：
- orule-server 代码/测试/文档：
- 顶层文档/规则：

优先复用：
- 现有入口点：
- 现有 helper：
- 现有测试基类：
- 现有 DSL / 注解：
- 不新增：
- 可由上下文推导的字段：
```

3. 按归属读规则文件：
   - 任何模块：必读 [AGENTS.md](../../../AGENTS.md)（含 STY-J001 / STY-J002）。
   - 跨模块：另读 [ARCHITECTURE.md §0 依赖方向](../../../ARCHITECTURE.md)，确认不引入反向依赖。
4. 实现前搜索相似测试和 helper：

```powershell
# PowerShell 等价
rg "inferRecommendModule|printSimpleSolutions|assertSoluContain" packages/orule-rule-execution-service/src/test/java packages/orule-server/src/test/java
rg "RuleTrans|RuleUnit" packages
```

## 2. 阶段 2：保守实现

- 优先沿用现有模块模式，不急于创建新抽象。
- 只有无法从上下文推导时才新增字段。
- 只有现有测试基类或 DSL 无法表达行为时才新增 helper。
- 如果给大型协调器添加非平凡逻辑，应抽取模块本地类并增加聚焦单元测试。
- 保持依赖方向：`orule-server ⟂ orule-rule-execution-service`（互不依赖；如需联动走 HTTP / Kafka）。
- `tests/scripts` 不依赖业务模块；只测脚本。

## 3. 各模块实现约束

### orule-common

- 仅放 DTO / 接口 / Util，不引入 Spring Web / Spring Data JPA 等业务框架依赖。
- 测试基类保持简单，不引入业务上下文。

### orule-rule-execution-service

- Groovy 沙箱、Kafka 事件、HTTP API、Caffeine 缓存、Resilience4j 限流。
- 与管控侧联动走 OpenFeign / Kafka；不直接依赖 `orule-server`。
- 测试路径：`packages/orule-rule-execution-service/src/test/java/...`

### orule-server

- REST + 进程内 MCP；元数据 CRUD、RuleSet 状态机、规则源 intake。
- 测试路径：`packages/orule-server/src/test/java/...`

### 跨模块

- 禁止反向依赖（见 [ARCHITECTURE.md §0 依赖方向](../../../ARCHITECTURE.md)）。
- 联动以 HTTP / Kafka 异步消息为契约；不要共享内部类。

## 4. 阶段 3：测试

先运行目标测试，再运行必要边界检查。

### 单模块测试示例

```bash
mvn -pl packages/orule-rule-execution-service -am test "-Dtest=NewTest,RelatedTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl packages/orule-rule-execution-service -am test
```

### 跨模块或依赖敏感修改的边界检查

```bash
mvn -pl packages/orule-rule-execution-service,packages/orule-server -am test "-Dtest=ModuleBoundaryDependencyTest" "-Dsurefire.failIfNoSpecifiedTests=false"
# 检查反向依赖
rg "packages.orule.server" packages/orule-rule-execution-service/src/main/java
rg "packages.orule.rule.execution" packages/orule-server/src/main/java
```

### 校验脚本（来自 AGENTS.md）

```powershell
pwsh scripts/lint-imports.ps1
pwsh scripts/check-test-public.ps1
```

任一项失败必须先修，再继续。

### 日志语言检查

```bash
rg -n "log\.(info|warn|error|debug)\([^\n]*[\x{4e00}-\x{9fff}]" packages/orule-common/src/main/java packages/orule-rule-execution-service/src/main/java packages/orule-server/src/main/java
```

> 项目偏好：日志消息统一英文，避免跨进程 / 跨语言检索失配。

## 5. 阶段 4：提交

- 除非用户要求其他分支，默认在 feature/<RFC-XXXX> 分支上开发。
- 默认**不**含 `.specstory/`，由 `rfc-push` Skill 单独提交日志。
- 无关的未跟踪文件不要放入提交，除非用户要求。
- 提交信息：`RFC-XXXX: <description>` 或 `<type>(<scope>): RFC-XXXX <description>`。

```bash
git add -A
git reset .specstory
git commit -m "RFC-XXXX: <description>"
```

随后调 `rfc-push` Skill 完成推送 / PR 流程。

## 6. 行为约束（精简版）

- **禁止**编造类名、方法名、字段名、路径或命令。
- **禁止**在日志消息中使用中文（`log.info(...)` 等必须仅使用英文）。
- **禁止**在断言、测试用例或文档中捏造不存在的测试场景。
- **禁止**使用未核实的依赖或 API。
- 不知道某个类或方法是否存在时，先 `rg` 搜索再引用；不要凭印象编造包名或类名。
- 实现中若发现 RFC 与实际代码的偏差，应明确报告，而不是"假装一致"。
- 输出中如违反上述约束，应在末尾追加 `[RULES I BROKE]: ...`。
