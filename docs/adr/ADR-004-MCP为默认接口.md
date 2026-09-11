# ADR-004：与 Agent 融合的接口默认 MCP，REST 作辅助

- 状态：已接受
- 日期：2026-09-10
- 决策人：架构组
- 相关章节：01-边界与目标 §1.4.1 / 08-开发视图

## 背景

AI Agent 是 orule 的核心消费者，需要为 Agent 提供标准化、可发现、可调用的工具集。决策者要求 MCP 协议为默认。

## 候选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| A：**MCP 协议为主 + REST 辅助** | 与 Cursor/Claude Desktop 等 Agent 原生集成；本地 Skill 通过 MCP 回传自然；行业趋势 | MCP 工具发现/鉴权模型需设计 |
| B：纯 REST API | 通用、好实现 | Agent 需写胶水代码；无法复用 MCP 生态 |
| C：纯 gRPC | 高性能、强类型 | Agent 集成门槛高 |

## 决策

采用方案 A：**MCP 为默认，REST 为辅助**。
- MCP Server 暴露核心工具：rule_create_from_nl、rule_list、rule_get、rule_execute
- REST API 暴露 CRUD 工具给开发者手动调用
- 本地 Skill 通过 MCP 协议回传结果到 server

## 后果

- 正面影响：与主流 Agent 生态无缝集成；"本地生成、云端保存"的回路实现成本最低。
- 负面影响 / 成本：MCP 工具命名/鉴权需设计；MCP SDK 当前版本稳定性需关注 [推断 中]。
- 回退方案：MCP 与 REST 双协议并存，MCP 故障不影响 REST 路径。

## 备注

[OPEN-Q3] MCP 工具集精确清单与参数契约 —— 进入用例视图决策。
