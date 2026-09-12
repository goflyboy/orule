# orule

> 规则全生命周期管理平台

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/orule/orule.git
cd orule

# 启动本地一体化开发环境
./scripts/dev-start.sh   # Linux / macOS
.\scripts\dev-start.ps1   # Windows PowerShell
```

访问：

- 📘 orule-server:  http://localhost:8080
- ⚡ orule-runtime: http://localhost:8081
- 🌐 orule-web:     http://localhost:5173（如已实现）

## 本地数据库查看（orule-server）

`orule-server` 默认使用 **H2 内存数据库**（`MODE=MySQL`），数据存在 Spring Boot 进程里。
开发环境自带 **H2 Web Console**，最简单的方式是一键打开：

```powershell
.\scripts\dev-start.ps1 db-console
```

该命令会：

1. 若 `orule-server` 未启动则自动拉起（端口 8080）。
2. 在默认浏览器打开 H2 Console 登录页（`http://localhost:8080/h2-console`）。
3. 打印登录表单所需的 JDBC URL / Driver / 用户名密码（见下方）。

> ⚠️ 内存库的生命周期与 JVM 进程绑定 —— 重启 `dev-start.ps1 server` 后数据会清空。
> 想持久化请自行切换为文件型 URL（不推荐本地开发使用）。

### 登录表单

| 字段 | 值 |
|---|---|
| Driver Class | `org.h2.Driver`（下拉默认） |
| JDBC URL | `jdbc:h2:mem:orule;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1` |
| User Name | `sa` |
| Password | （留空） |

### 常用查询示例

```sql
SHOW TABLES;

SELECT * FROM FLYWAY_SCHEMA_HISTORY;   -- Flyway 迁移记录
SELECT * FROM RULE_DOMAIN;              -- 规则域
SELECT * FROM ARTIFACT;                -- 制品
```

> 表名取决于当前 schema，`SHOW TABLES` 会列出实际存在的全部表。
> 由于 `DATABASE_TO_LOWER=TRUE`，H2 内统一小写存储，写 SQL 时按业务里的大小写访问即可。

### 关闭

浏览器里看完后释放端口：

```powershell
.\scripts\dev-start.ps1 stop
```

## 架构文档

完整 4+1 架构视图见 [docs/](docs/)。

## 贡献指南

参见 [docs/08-开发视图.md](docs/08-开发视图.md)。

## 许可证

Apache 2.0 — 详见 [LICENSE](LICENSE)。
