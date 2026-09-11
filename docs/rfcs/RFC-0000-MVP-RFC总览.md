# RFC-0000: MVP 阶段 RFC 总览（RFC Index）

> **状态**：DRAFT · **优先级**：最高 · **预计工作量**：~80 人天

---

## 0. 摘要

本文档是 **MVP 阶段（M1，3 个月）**所有 RFC 的索引与依赖关系图。每条 RFC 对应一个可独立交付的子任务，按依赖顺序编号 RFC-0011 ~ RFC-0030。

---

## 1. MVP 范围回顾

依据 `09-收口与风险.md §9.3.1`，MVP 必须完成 14 项功能：

```
1.  元数据管理（DomainType / ObjectType / AttributeType / EnumType / FunctionLib）
2.  规则管理（Rule / RuleVersion 状态机）
3.  规则集（RuleSet / RuleSetArtifact）
4.  NL → SimpleTS（LLM 调用）
5.  SimpleTS 解析器（白名单剪枝 + 字段校验）
6.  SimpleTS → Groovy（代码生成）
7.  Groovy 沙箱（白名单 + 超时 + 内存）
8.  规则执行（单条 / 批量）
9.  测试用例（RuleTestCase）
10. 多视图编辑器（NL / SimpleTS / 表格）
11. orule-web（React + Ant Design）
12. orule-server（REST + 进程内 MCP）
13. orule-runtime（独立部署）
14. ArtifactStorage（默认本地）
```

---

## 2. RFC 编号清单

| RFC | 标题 | 模块 | 阶段 | 依赖 | 工作量 | 优先级 |
|-----|------|------|------|------|--------|--------|
| **RFC-0011** | Monorepo 仓库初始化 | 顶层 | S0 | — | 1d | P0 |
| **RFC-0012** | Maven 多模块骨架 | 顶层 | S0 | RFC-0011 | 1d | P0 |
| **RFC-0013** | 本地一键启动脚本 | 顶层 | S0 | RFC-0011, RFC-0012 | 1d | P0 |
| **RFC-0014** | 数据库 Flyway 迁移基线（V1~V5） | server | S1 | RFC-0012 | 2d | P0 |
| **RFC-0015** | 元数据域 CRUD API（DomainType 等 5 表） | server | S1 | RFC-0014 | 3d | P0 |
| **RFC-0016** | 规则域 + 状态机 API（Rule / RuleVersion） | server | S2 | RFC-0014 | 3d | P0 |
| **RFC-0017** | ArtifactStorage 接口 + LocalStorage 实现 | server | S2 | RFC-0014 | 2d | P0 |
| **RFC-0018** | SimpleTS 解析器（白名单剪枝 + 字段校验） | server | S3 | RFC-0015 | 5d | P0 |
| **RFC-0019** | SimpleTS → Groovy 代码生成器 | server | S3 | RFC-0018 | 4d | P0 |
| **RFC-0020** | Groovy 沙箱 + 单条规则执行器 | runtime | S4 | RFC-0019 | 5d | P0 |
| **RFC-0021** | 批量规则执行 + RuleSetArtifact | runtime | S4 | RFC-0020 | 3d | P0 |
| **RFC-0022** | 测试用例（RuleTestCase）+ 通过率统计 | server+runtime | S5 | RFC-0021 | 3d | P0 |
| **RFC-0023** | NL → SimpleTS LLM 调用 + Schema 注入 | server | S5 | RFC-0018 | 4d | P0 |
| **RFC-0024** | 多视图编辑器（NL / SimpleTS / 表格） | web | S6 | RFC-0016, RFC-0023 | 5d | P0 |
| **RFC-0025** | orule-web 列表/详情/执行监控页面 | web | S6 | RFC-0016 | 4d | P0 |
| **RFC-0026** | 进程内 MCP 服务（MCP Tools） | server | S7 | RFC-0016 | 3d | P0 |
| **RFC-0027** | 可观测性（日志 + X-Trace-Id + Actuator + Micrometer） | server+runtime | S7 | RFC-0013 | 2d | P1 |
| **RFC-0028** | 端到端集成测试（MVP 验收用例） | 测试 | S8 | RFC-0024~0026 | 3d | P0 |
| **RFC-0029** | Docker Compose MVP 部署 | 顶层 | S8 | RFC-0013 | 2d | P1 |
| **RFC-0030** | MVP 验收 + 演示 Demo | 顶层 | S9 | 全部 | 2d | P0 |

**总计**：~59 人天（含缓冲后 ~80 人天，约 3 个月 / 5 人团队）

---

## 3. 阶段划分（甘特图）

```
        第 1 周   第 2 周   第 3 周   第 4 周   第 5 周   第 6 周   第 7 周   第 8 周   第 9 周   第 10 周  第 11 周  第 12 周
        ━━━━━━━━━┳━━━━━━━━┳━━━━━━━━┳━━━━━━━━┳━━━━━━━━┳━━━━━━━━┳━━━━━━━━┳━━━━━━━━┳━━━━━━━━┳━━━━━━━━━┳━━━━━━━━━┳━━━━━━━━━┛
S0 骨架  ████ 0011 0012 0013
S1 元数据                            ████████ 0014 0015
S2 规则                                                    ██████ 0016 0017
S3 SimpleTS                                                          ████████ 0018 0019
S4 执行                                                                              ██████ 0020 0021
S5 测试+LLM                                                                                ████ 0022 0023
S6 前端                                                                                          ████████ 0024 0025
S7 MCP+观测                                                                                            ████ 0026 0027
S8 验收                                                                                                  ████ 0028 0029
S9 演示                                                                                                        ██ 0030
```

---

## 4. 依赖关系图

```
RFC-0011 (Monorepo)
    │
    ├── RFC-0012 (Maven 多模块)
    │       │
    │       └── RFC-0013 (一键启动)
    │
    └── RFC-0014 (Flyway V1~V5)
            │
            ├── RFC-0015 (元数据 API) ──────────┐
            │                                   │
            ├── RFC-0016 (规则 + 状态机) ───────┼──┐
            │                                   │  │
            ├── RFC-0017 (ArtifactStorage)      │  │
            │                                   │  │
            └── RFC-0018 (SimpleTS 解析器) ◀────┘  │
                    │                              │
                    ├── RFC-0019 (SimpleTS→Groovy)│
                    │       │                      │
                    │       └── RFC-0020 (沙箱 + 执行) ◀── RFC-0021 (批量执行)
                    │                                       │
                    │                                       ├── RFC-0022 (测试用例)
                    │                                       │
                    └── RFC-0023 (NL→SimpleTS LLM)         │
                                                            │
                            ┌───────────────────────────────┘
                            │
                            ├── RFC-0024 (多视图编辑器)
                            │
                            └── RFC-0025 (列表/详情/监控)
                                    │
                            ┌───────┘
                            │
                            ├── RFC-0026 (MCP Tools)
                            │
                            └── RFC-0027 (可观测性)
                                    │
                            ┌───────┘
                            │
                            ├── RFC-0028 (E2E 验收)
                            │
                            └── RFC-0029 (Docker Compose)
                                    │
                            ┌───────┘
                            │
                            └── RFC-0030 (MVP 演示)
```

---

## 5. 人员分配建议

| 人员 | 角色 | 主要负责 RFC |
|------|------|--------------|
| **A**（架构师） | 后端 Tech Lead | 0011, 0012, 0014, 0026, 0027, 0030 |
| **B**（后端 1） | 元数据 + 规则 | 0015, 0016, 0017 |
| **C**（后端 2） | SimpleTS + 沙箱 | 0018, 0019, 0020, 0021 |
| **D**（前端） | orule-web + 编辑器 | 0024, 0025 |
| **E**（LLM / 测试） | LLM + 集成 | 0022, 0023, 0028, 0029 |

> 5 人小团队，3 个月 MVP。

---

## 6. 验收标准（汇总）

来自 `09-收口与风险.md §9.3.3`，本索引 RFC 全部完成后必须满足：

| 维度 | 标准 | 验证方式 |
|------|------|----------|
| 功能完整性 | 14 项 MVP 功能 100% 完成 | RFC-0028 E2E 测试通过 |
| 代码质量 | 单元测试覆盖率 ≥ 60%；关键模块 100% | `mvn jacoco:report` |
| 文档完整 | 9 份视图 + 9 份 ADR + README | 已完成 ✅ |
| 可演示 | 跑通 NL→SimpleTS→Groovy→执行 | RFC-0030 演示脚本 |
| 性能基线 | 单条执行 P95 < 200ms | RFC-0028 性能测试 |
| 稳定性 | 72 小时无崩溃 | RFC-0028 长稳测试 |
| 安全性 | 沙箱通过基础攻击测试 | RFC-0020 安全测试 |

---

## 7. 风险与缓解（与 `09-收口与风险 §9.4` 对应）

| 风险 | 影响 RFC | 缓解措施 |
|------|----------|----------|
| T1 沙箱绕过 | RFC-0020 | 白名单 + 多重校验 + 安全测试 |
| T2 LLM 幻觉 | RFC-0023 | Schema 注入 + SimpleTS 校验 + 测试用例 |
| T3 解析性能 | RFC-0018 | AST 缓存（RFC-0018 内） |
| T4 单点故障 | MVP 接受 | 二期部署多副本 |
| T5 数据库迁移 | RFC-0014 | Flyway 校验；失败时回滚 |
| P1 文档脱节 | 全部 | 每个 PR 必须包含文档更新 |
| P3 工期延误 | 全部 | 严格 MVP 范围；二期功能剥离 |

---

## 8. 推进方式

每条 RFC 独立走流程：

```
RFC 提案（RFC-XXXX.md）
    ↓
团队评审（24h 内）
    ↓
合并到 develop
    ↓
按 `.agents/skills/rfc-driven-development` Skill 实现
    ↓
PR + Code Review + CI
    ↓
合并到 main
    ↓
更新本索引状态：DRAFT → REVIEWING → IMPLEMENTING → DONE
```

---

## 9. RFC 状态跟踪

| RFC | 状态 | 责任人 | 计划完成 | 实际完成 |
|-----|------|--------|----------|----------|
| RFC-0011 | DRAFT | — | W1-D2 | |
| RFC-0012 | DRAFT | — | W1-D4 | |
| ... | ... | ... | ... | ... |

---

## 10. 变更记录

| 版本 | 日期 | 主要变化 |
|------|------|----------|
| v0.1 | 2026-09-11 | 初稿：MVP 阶段 RFC 索引（RFC-0011 ~ RFC-0030，共 20 条） |
