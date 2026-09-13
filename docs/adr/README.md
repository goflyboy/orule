# ADR 索引

> orule 项目的所有架构决策记录（Architecture Decision Records）集中索引。
>
> ADR 编号规则：稳定递增 + 单调编号；状态在文档头部声明。
>
> 相关索引：[RFC 索引](../rfcs/README.md) · [文档目录](../README.md)

## 总览

| 范围 | ADR | 主题 |
|------|------|------|
| **命名与定位** | ADR-001 | 系统命名与定位 |
| **MVP 范围** | ADR-002 | MVP 双展现态 + 双执行引擎 |
| **DSL 选型** | ADR-003 | 中间态 DSL 采用 SimpleTS |
| **接口形态** | ADR-004 | MCP 为默认接口 |
| **数据层** | ADR-005 | 数据库多 profile |
| **源语言** | ADR-006 | 规则源语言采用 SimpleTS |
| **进程架构** | ADR-007 | 四进程架构 |
| **前端集成** | ADR-008 | LLM 服务端原生双前端嵌入 |
| **星型架构** | ADR-009 | SimpleTS 为中心的星型转换架构 |
| **存储抽象** | ADR-010 | ArtifactStorage 可配置通用文件服务器 |
| **Type 系统** | ADR-011 | Type 系统重构为 JSON 树 |
| **解析器选型** | ADR-012 + ADR-012-Aprime | GraalJS 替代方案（A/B/C/A'） |

---

## 命名与定位

- [ADR-001 系统命名与定位](ADR-001-系统命名与定位.md) — **已接受**

## MVP 范围

- [ADR-002 MVP 双展现态 + 双执行引擎](ADR-002-MVP双展现态双执行引擎.md) — **已接受**

## DSL 选型

- [ADR-003 中间态 DSL 采用 SimpleTS](ADR-003-中间态DSL采用SimpleTS.md) — **已更新（2026-09-11 第 6 轮修正）**

## 接口形态

- [ADR-004 MCP 为默认接口](ADR-004-MCP为默认接口.md) — **已接受**

## 数据层

- [ADR-005 数据库多 profile](ADR-005-数据库多profile.md) — **已接受**

## 源语言

- [ADR-006 规则源语言采用 SimpleTS](ADR-006-规则源语言采用SimpleTS.md) — **已接受**

## 进程架构

- [ADR-007 四进程架构](ADR-007-四进程架构.md) — **已通过**

## 前端集成

- [ADR-008 LLM 服务端原生双前端嵌入](ADR-008-LLM服务端原生双前端嵌入.md) — **已通过**

## 星型架构

- [ADR-009 SimpleTS 为中心的星型转换架构](ADR-009-SimpleTS为中心的星型转换架构.md) — **已通过**

## 存储抽象

- [ADR-010 ArtifactStorage 可配置通用文件服务器](ADR-010-ArtifactStorage可配置通用文件服务器.md) — **已通过**

## Type 系统

- [ADR-011 Type 系统重构为 JSON 树](ADR-011-Type系统重构为JSON树.md) — **已通过**
- 配套 RFC：[RFC-0031](../rfcs/RFC-0031-Type系统重构.md)（SUPERSEDED）→ [RFC-0032](../rfcs/RFC-0032-ObjectType枚举化与Type系统简化.md)（IMPLEMENTING）→ [RFC-0033](../rfcs/RFC-0033-元数据管理2-RuleSetType与RuleType.md)（DRAFT）

## 解析器选型（GraalJS 替代方案）

- [ADR-012 GraalJS 替代方案选型](ADR-012-GraalJS替代方案选型.md) — **SUPERSEDED**（方案 A/B/C 已被 A' 取代）
- [ADR-012-Aprime 本地 Skill 编译与 MCP LangLib 库](ADR-012-Aprime-本地Skill编译与MCPLangLib库.md) — **已采纳** ✅
  - **关联 RFC**：[RFC-0018](../rfcs/RFC-0018-SimpleTS解析器.md)（DRAFT）/[RFC-0019](../rfcs/RFC-0019-SimpleTS转Groovy代码生成器.md)（DRAFT）
  - **实施记录**：2026-09-12 通过 4 个 worktree 并行推进（A/B/C/D）合并入 `main`（commit `c579b17`），详见 [`docs/04-WS-orchestration-2026-09-12.md`](../../WS-orchestration.md)

---

## 状态说明

| 状态 | 含义 |
|------|------|
| 已接受 / 已通过 | 决策有效，正在指导实施 |
| 已更新 | 决策有效，已被更新版本取代（但旧版本仍有参考价值） |
| SUPERSEDED | 已被新 ADR 取代，仅作历史参考 |
| REJECTED | 已拒绝 |

## 修订说明

- **2026-09-13**：新增本索引；同时 ADR-012 标 SUPERSEDED，新增 ADR-012-Aprime 作为最终采纳方案
