# RFC 索引

> orule 项目的所有 RFC 提案集中索引。

## 阶段划分

| 阶段 | RFC 范围 | 主题 |
|------|----------|------|
| **S0 基础设施** | RFC-0011 ~ RFC-0013 | Monorepo / Maven / 一键启动 |
| **S1 数据层** | RFC-0014 ~ RFC-0015 | Flyway 迁移 / 元数据 API |
| **S2 规则层** | RFC-0016 ~ RFC-0017 | 规则状态机 / ArtifactStorage |
| **S3 编译层** | RFC-0018 ~ RFC-0019 | SimpleTS 解析 / SimpleTS→Groovy |
| **S4 执行层** | RFC-0020 ~ RFC-0021 | Groovy 沙箱 / 批量执行 |
| **S5 测试+LLM** | RFC-0022 ~ RFC-0023 | 测试用例 / NL→SimpleTS |
| **S6 前端** | RFC-0024 ~ RFC-0025 | 多视图编辑器 / 列表监控 |
| **S7 MCP+观测** | RFC-0026 ~ RFC-0027 | MCP Tools / 可观测性 |
| **S8 验收** | RFC-0028 ~ RFC-0029 | E2E 测试 / Docker Compose |
| **S9 演示** | RFC-0030 | MVP 演示 |

## 总览

[RFC-0000 MVP 阶段 RFC 总览](RFC-0000-MVP-RFC总览.md)

## S0 基础设施

- [RFC-0011 Monorepo 仓库初始化](RFC-0011-Monorepo仓库初始化.md) — DRAFT
- [RFC-0012 Maven 多模块骨架](RFC-0012-Maven多模块骨架.md) — DRAFT
- [RFC-0013 本地一键启动脚本](RFC-0013-本地一键启动脚本.md) — DRAFT

## S1 数据层

- [RFC-0014 数据库 Flyway 迁移基线（V1~V5）](RFC-0014-数据库Flyway迁移基线.md) — **SUPERSEDED**（被 RFC-0033 取代）
- [RFC-0015 元数据域 CRUD API](RFC-0015-元数据域CRUD-API.md) — **SUPERSEDED**（被 RFC-0033 取代）

## S2 规则层

- [RFC-0016 规则域 + 状态机 API](RFC-0016-规则域状态机API.md) — DRAFT
- [RFC-0017 ArtifactStorage 接口 + LocalStorage 实现](RFC-0017-ArtifactStorage接口LocalStorage实现.md) — DRAFT

## S3 编译层

- [RFC-0018 SimpleTS 解析器（白名单剪枝 + 字段校验）](RFC-0018-SimpleTS解析器.md) — DRAFT
- [RFC-0019 SimpleTS → Groovy 代码生成器](RFC-0019-SimpleTS转Groovy代码生成器.md) — DRAFT

## S4 执行层

- [RFC-0020 Groovy 沙箱 + 单条规则执行器](RFC-0020-Groovy沙箱单条规则执行器.md) — DRAFT
- [RFC-0021 批量规则执行 + RuleSetArtifact](RFC-0021-批量规则执行RuleSetArtifact.md) — DRAFT

## S5 测试 + LLM

- [RFC-0022 测试用例 + 通过率统计](RFC-0022-测试用例通过率统计.md) — DRAFT
- [RFC-0023 NL → SimpleTS LLM 调用 + Schema 注入](RFC-0023-NL转SimpleTS-LLM调用.md) — DRAFT

## S6 前端

- [RFC-0024 多视图编辑器（NL / SimpleTS / 表格）](RFC-0024-多视图编辑器.md) — DRAFT
- [RFC-0025 orule-web 列表/详情/监控页面](RFC-0025-orule-web列表详情监控页面.md) — DRAFT

## S7 MCP + 可观测性

- [RFC-0026 进程内 MCP 服务（MCP Tools）](RFC-0026-进程内MCP服务.md) — DRAFT
- [RFC-0027 可观测性（日志 + Trace + Actuator + Micrometer）](RFC-0027-可观测性日志TraceActuator.md) — DRAFT

## S8 验收

- [RFC-0028 端到端集成测试（MVP 验收用例）](RFC-0028-端到端集成测试MVP验收.md) — DRAFT
- [RFC-0029 Docker Compose MVP 部署](RFC-0029-Docker-Compose-MVP部署.md) — DRAFT

## S9 演示

- [RFC-0030 MVP 验收 + 演示 Demo](RFC-0030-MVP验收演示.md) — DRAFT

## S10 Type 系统增强（MVP 后）

- [RFC-0031 Type 系统重构 — JSON 树扁平化 + 5 个 Variant](RFC-0031-Type系统重构.md) — **SUPERSEDED**（被 RFC-0032 取代）
- [RFC-0032 ObjectType 枚举化 + Type 系统简化（5 Variant → 4 Variant）](RFC-0032-ObjectType枚举化与Type系统简化.md) — **IMPLEMENTING**
- [RFC-0033 元数据管理（2）— RuleSetType 与 RuleType（JSON 拍平）](RFC-0033-元数据管理2-RuleSetType与RuleType.md) — DRAFT

## 规则执行服务扩展

- [RFC-0040 规则执行服务](RFC-0040-规则执行服务.md) — DRAFT
- [RFC-0041 系统级测试（System-Level Test） — 真实 HTTP 服务 + Java 执行器](RFC-0041-系统级测试与执行日志.md) — DRAFT
- [RFC-0042 系统测试框架（System-Test Framework） — Fluent DSL + 流式比较器](RFC-0042-系统测试框架.md) — DRAFT
- [RFC-0043 嵌套对象 / List / Map 上下文绑定与属性赋值](RFC-0043-嵌套对象List与Map上下文绑定.md) — DRAFT
- [RFC-0044 SimpleTS 与 Type 系统配套（嵌套对象 List/Map 上下文绑定）](RFC-0044-SimpleTS与Type系统配套-嵌套对象ListMap上下文绑定.md) — DRAFT（RFC-0043 的语言配套子 RFC）
- [RFC-0045 元数据驱动的执行器领域类型注入](RFC-0045-元数据驱动的执行器领域类型注入.md) — DRAFT（落地 RFC-0043 §4.2 与 §9 T-5；`DomainTypePrefix.CUSTOMER_ORDER` 标 `@Deprecated`；`orule-server` 同期新增 `GET /api/v1/object-types/by-program-code`；`RuleMetadataResponse` 升 v2 record）

## 状态说明

| 状态 | 含义 |
|------|------|
| DRAFT | 提案阶段，尚未评审 |
| REVIEWING | 团队评审中 |
| APPROVED | 已批准，待实现 |
| IMPLEMENTING | 实现中 |
| DONE | 已完成 |
| REJECTED | 已拒绝 |
| SUPERSEDED | 已被新 RFC 取代 |

TODO：
RFC-0034：运行时类型检查与算法 API

---

## 修订说明

- **2026-09-13**：RFC-0014/0015 标 SUPERSEDED（被 RFC-0033 取代）；RFC-0031 标 SUPERSEDED（被 RFC-0032 取代）；RFC-0032 标 IMPLEMENTING。RFC-0018/0019 状态保持 DRAFT，标注"实施路径已通过 ADR-012-Aprime 确定（详见 ADR 索引），代码合并在 main #c579b17 中（仅 MCP 端点 + 白名单 Loader 部分；解析器/Skill 化仍在外部 skill 仓实施）"。新增 [ADR 索引](../adr/README.md)。