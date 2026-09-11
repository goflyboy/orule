# RFC-0011: Monorepo 仓库初始化

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：1d · **阶段**：S0

---

## 1. 摘要

初始化 orule monorepo，建立顶层目录结构、根 `pom.xml`、`pnpm-workspace.yaml`、`.gitignore`、`.editorconfig`、README 等基础设施。

---

## 2. 动机

- 当前仓库仅有 `docs/` 和 `.agents/`，无代码骨架
- 需要一个清晰的目录结构，支撑后续 20+ RFC 的并行开发
- 依据 `08-开发视图 §8.1` 的仓库结构定义

---

## 3. 详细设计

### 3.1 目录结构

按 `08-开发视图 §8.1.1` 实施：

```
orule/
├── docs/                  # 已存在 ✅
├── packages/              # 新建（Java 模块）
│   ├── orule-common/      # 见 RFC-0012
│   ├── orule-server/      # 见 RFC-0012
│   └── orule-runtime/     # 见 RFC-0012
├── orule-web/             # 见 RFC-0024
├── orule-llm-studio/      # 二期（MVP 仅放目录占位）
├── skills/                # 新建（Python Skill 目录占位）
├── config/                # 新建（共享配置）
│   ├── db/migration/      # 见 RFC-0014
│   └── prometheus/        # 占位
├── scripts/               # 新建（运维脚本）
├── .github/workflows/     # 新建（CI）
├── pom.xml                # 见 RFC-0012
├── pnpm-workspace.yaml    # 本 RFC 创建
├── .gitignore             # 本 RFC 创建
├── .editorconfig          # 本 RFC 创建
├── .gitattributes         # 本 RFC 创建
├── README.md              # 本 RFC 创建
├── LICENSE                # 本 RFC 创建（Apache 2.0）
└── ARCHITECTURE.md        # 本 RFC 创建（链接到 docs/）
```

### 3.2 关键文件内容

#### `.gitignore`

```gitignore
# Java
target/
*.class
*.jar
*.war
.mvn/
.m2/

# Node
node_modules/
dist/
out/
*.log

# IDE
.idea/
*.iml
.vscode/
!.vscode/settings.json
!.vscode/extensions.json
.cursor/
.DS_Store

# 本地数据
data/
*.local.yml
*.local.yaml
.env
.env.local

# 日志
logs/
*.log

# OS
Thumbs.db

# SpecStory（保留）
.specstory/

# Agents
.agents/
```

> **特别说明**：`.specstory/` 必须被 git 跟踪（按 Skill 默认行为），但本仓库为新仓库，先 gitignore，待首次提交后开启跟踪。

#### `.editorconfig`

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 2

[*.{java,kt}]
indent_size = 4
max_line_length = 120

[*.{yml,yaml,xml}]
indent_size = 2

[*.md]
trim_trailing_whitespace = false
```

#### `.gitattributes`

```gitattributes
# 防止 CRLF 混淆
*.java text eol=lf
*.ts text eol=lf
*.tsx text eol=lf
*.js text eol=lf
*.json text eol=lf
*.xml text eol=lf
*.yml text eol=lf
*.yaml text eol=lf
*.md text eol=lf

# 二进制文件
*.jar binary
*.png binary
*.jpg binary
```

#### `pnpm-workspace.yaml`

```yaml
packages:
  - 'orule-web'
  - 'orule-llm-studio'
  - 'skills/*'
```

#### `README.md`

```markdown
# orule

> 规则全生命周期管理平台

## 快速开始

\`\`\`bash
# 克隆仓库
git clone https://github.com/orule/orule.git
cd orule

# 启动本地一体化开发环境
./scripts/dev-start.sh
\`\`\`

访问：
- 🌐 orule-web: http://localhost:5173
- 🔧 orule-server: http://localhost:8080
- ⚡ orule-runtime: http://localhost:8081

## 架构文档

完整 4+1 架构视图见 [docs/](docs/README.md)。

## 贡献指南

参见 [docs/08-开发视图.md](docs/08-开发视图.md)。

## 许可证

Apache 2.0
```

#### `ARCHITECTURE.md`

```markdown
# 架构总览

orule 的完整 4+1 架构视图：

- [01 边界与目标](docs/01-边界与目标.md)
- [02 用例视图](docs/02-用例视图.md)
- [03 逻辑视图](docs/03-逻辑视图.md)
- [04 数据模型](docs/04-数据模型.md)
- [05 技术模型](docs/05-技术模型.md)
- [06 运行视图](docs/06-运行视图.md)
- [07 部署视图](docs/07-部署视图.md)
- [08 开发视图](docs/08-开发视图.md)
- [09 收口与风险](docs/09-收口与风险.md)

## 关键决策

[ADR 列表](docs/adr/)

## 路线图

[MVP 阶段 RFC 总览](docs/rfcs/RFC-0000-MVP-RFC总览.md)
```

#### `LICENSE`（Apache 2.0）

标准 Apache 2.0 全文。

---

## 4. 影响面

- 新增 9 个顶层文件 + 5 个目录
- 无破坏性影响
- 不涉及数据库表 / API

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| 目录结构完整 | `tree -L 2 -a` 比对 |
| `.gitignore` 生效 | 测试文件不进入 `git add` |
| `.editorconfig` 生效 | IDE 测试 |
| `pnpm-workspace.yaml` 解析 | `pnpm install --dry-run` |
| README 可读性 | 人工 review |

---

## 6. 风险

- **R1**：目录结构后期需要调整 → 缓解：在 RFC-0000 阶段提前定稿
- **R2**：Maven vs Gradle 选择 → 决策：Maven（与 `08-开发视图` 一致）

---

## 7. 实施步骤

```
1. 创建目录：packages/, skills/, config/, scripts/, .github/workflows/
2. 创建 .gitignore
3. 创建 .editorconfig
4. 创建 .gitattributes
5. 创建 pnpm-workspace.yaml
6. 创建 README.md
7. 创建 ARCHITECTURE.md
8. 创建 LICENSE
9. git init + 首次提交
```

---

## 8. 关联

- 上游：—
- 下游：RFC-0012（Maven 多模块骨架）、RFC-0013（一键启动）
- ADR：—
