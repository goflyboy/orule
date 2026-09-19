# 架构总览

> 本文件给人类贡献者阅读，给 AI Agent 提供"模块归属判断"信号。
> 配套：[AGENTS.md](../../AGENTS.md) · [.agents/README.md](../../.agents/README.md)

---

## 0. 仓库结构

orule 是 Maven monorepo，由以下模块组成：

| 模块 | 路径 | 责任 | 入口 |
|------|------|------|------|
| `orule-common` | `packages/orule-common/` | 公共 DTO / 异常 / `ArtifactStorage` 接口 / Util | — |
| `orule-rule-execution-service` | `packages/orule-rule-execution-service/` | 规则执行服务：Groovy 沙箱、Kafka 事件、HTTP API | `com.orule.rule.execution.RuleExecutionServiceApplication` |
| `orule-server` | `packages/orule-server/` | 管控服务：REST + 进程内 MCP | `com.orule.server.OruleServerApplication` |
| `tests/scripts` | `tests/scripts/` | 启动脚本与脚本自身测试 | — |

依赖方向（**禁止反向**）：

```
orule-server ──► orule-common
orule-rule-execution-service ──► orule-common
orule-server ⟂ orule-rule-execution-service   （互不依赖；如需联调用 HTTP / Kafka）
tests/scripts ──► （不依赖业务模块；只测试脚本）
```

<!-- for-agents-start -->

## 0.1 Agent 决策指引

Agent 在判断"这次改动落在哪一层"时，按下面三问依次决策：

1. **是否只动 `orule-common`**？纯 DTO / 接口 / Util 改动 → 仅修改 `orule-common`，不写 RFC（小修）或写 RFC-XXXX（新增公共 API）。
2. **是否动 `orule-rule-execution-service` 或 `orule-server`**？业务逻辑 / HTTP / Kafka 改动 → 写 RFC-XXXX，落在对应模块的 RFC 段。
3. **是否同时改动两个业务模块或新增跨包依赖**？→ 跨模块 RFC，必须显式声明"跨模块边界"段，并在 PR 描述里 @ 两个模块 owner。

更细的模板见 `.agents/skills/interactive-rfc-generation/SKILL.md`。

<!-- for-agents-end -->

---

## 1. 4+1 视图

- [01 边界与目标](../docs/01-边界与目标.md)
- [02 用例视图](../docs/02-用例视图.md)
- [03 逻辑视图](../docs/03-逻辑视图.md)
- [04 数据模型](../docs/04-数据模型.md)
- [05 技术模型](../docs/05-技术模型.md)
- [06 运行视图](../docs/06-运行视图.md)
- [07 部署视图](../docs/07-部署视图.md)
- [08 开发视图](../docs/08-开发视图.md)
- [09 收口与风险](../docs/09-收口与风险.md)

## 2. 关键决策

[ADR 列表](../docs/adr/)

## 3. 路线图

[MVP 阶段 RFC 总览](../docs/rfcs/RFC-0000-MVP-RFC总览.md)
