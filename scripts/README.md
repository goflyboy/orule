# scripts/ 工具脚本

> 本目录放置仓库级轻量校验脚本。所有脚本用 PowerShell 编写，本地运行命令：`pwsh -File scripts/<script>.ps1`。
> 配套：[AGENTS.md §2](../AGENTS.md) · [ARCHITECTURE.md](../ARCHITECTURE.md)

---

## 0. 脚本清单

| 脚本 | 校验对象 | 对应规则 |
|------|----------|----------|
| `lint-imports.ps1` | Java 源码中是否使用 inline 限定符（pkg.Class） | [AGENTS.md §STY-J001](../AGENTS.md) |
| `check-test-public.ps1` | JUnit 5 测试类是否 `public class` | [AGENTS.md §STY-J002](../AGENTS.md) |

## 1. 通用参数

| 参数 | 含义 | 默认 |
|------|------|------|
| `-Root <path>` | 仓库根路径 | 当前 cwd |
| `-DryRun` | 仅输出，不返回非零退出码 | false |
| `-Allow <list>` | 白名单，逗号分隔的文件 / 行 | 空 |
| `-Help` | 显示帮助 | — |

## 2. 使用示例

```powershell
# 跑全部校验
pwsh -File scripts/lint-imports.ps1
pwsh -File scripts/check-test-public.ps1

# 仅看，不失败
pwsh -File scripts/lint-imports.ps1 -DryRun

# 对单条已知违规加白名单
pwsh -File scripts/lint-imports.ps1 -Allow "packages/orule-common/src/main/java/com/orule/common/storage/LocalStorage.java:27"
```

## 3. 接 CI

建议在 PR 检查中执行：

```yaml
- name: 校验 import 风格
  run: pwsh -File scripts/lint-imports.ps1
- name: 校验测试类可见性
  run: pwsh -File scripts/check-test-public.ps1
```

## 4. 添加新脚本

1. 在本目录创建 `<verb>-<noun>.ps1`。
2. 遵守 PowerShell 核心 cmdlet（不依赖第三方模块）。
3. 在本文件 §0 / §1 注册。
4. 跑 `audit-docs` 自检本 README 的链接。
