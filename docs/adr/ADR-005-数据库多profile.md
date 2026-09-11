# ADR-005：数据库默认支持 MySQL + PostgreSQL + H2 三 profile

- 状态：已接受
- 日期：2026-09-10
- 决策人：架构组
- 相关章节：01-边界与目标 §1.4.1 / 04-数据模型 / 07-部署视图

## 背景

为了支持混合部署（云端 + 桌面端）+ 一键容器化部署 + 自测试，需要数据库无关的灵活 profile。

## 候选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| A：**MySQL + PostgreSQL + H2 三 profile** | 满足主流生产场景；H2 支持自测试；容器化部署成熟 | 三套 dialect 兼容性测试工作量大 [推断 中] |
| B：仅 MySQL + PostgreSQL | 简化测试 | 桌面端本地 Skill 部署与自测试受限 |
| C：抽象 Database SPI + 默认 MySQL | 解耦彻底 | MVP 周期内 SPI 设计成本高 |

## 决策

采用方案 A：三 profile 同时支持。
- 通过 Spring Boot Profile 或类似机制切换
- ORM 层封装 dialect 差异
- Flyway/Liquibase 维护三套 schema migration（MVP 可用 H2 先跑、MySQL/PostgreSQL 后验证）
- Docker Compose 提供 MySQL + PostgreSQL 容器化启动；H2 嵌入式用于自测试

## 后果

- 正面影响：满足"数据库无关"需求；桌面端本地 Skill 可零外部依赖运行。
- 负面影响 / 成本：migration 兼容性需端到端验证；CI 需跑三套数据库的集成测试。
- 回退方案：若兼容性问题严重，第二期退化到双 profile。
