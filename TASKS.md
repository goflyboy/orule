# Worktree D · feature/d-cleanup · 删除 SimpleTSParser + GraalJS 依赖

> **分支**：`feature/d-cleanup`
> **路径**：`../orule-feature-d-cleanup`
> **基于**：`feature/a-prime` (`b7c0fa8`)
> **目标**：删除 orule-common / orule-server 中所有与 SimpleTSParser / TsAstParser / GroovyCodeGen / CompileService / GraalJS 相关代码与依赖
> **顺序**：**最后合并**（依赖 a/b/c 三个 worktree 先合并）
> **状态**：✅ **2026-09-13 完成** —— rebase 后 mvn test 全绿；目标代码 0 命中

---

## 完成摘要

2026-09-13 验证（`feature/d-cleanup` rebase 至 `feature/a-prime` HEAD `b7c0fa8` 之后）：

| 验证项 | 结果 |
|--------|------|
| `com/orule/dsl/**/*.java` 源文件 | **0 命中** |
| `SimpleTSParser / TsAstParser / GroovyCodeGen / CompileService` Java 文件 | **0 命中** |
| `com.orule.dsl` 字符串（packages 全量） | **0 命中** |
| `graalvm / polyglot` 字符串（pom.xml 全量） | **0 命中** |
| `graalvm / polyglot` 字符串（packages 全量源码） | **0 命中** |
| `mvn -pl packages/orule-common,packages/orule-server,packages/orule-runtime -am test` | **BUILD SUCCESS** · 94 tests, 0 fail |

**关键发现**：SimpleTSParser / TsAstParser / GroovyCodeGen / CompileService / GraalJS 全部依赖在 a-prime 基线**从未存在过**
（`git log --all --diff-filter=D -- packages/orule-common/src/main/java/com/orule/dsl` 无任何历史 commit）。
ADR-012-Aprime 落地（`f700a1e`）的实质是 RFC/ADR 文档修订；这些代码在仓库历史上**只存在于 RFC 文档中，从未落到 Java 源码**。
也就是说 D 任务的"删除"语义实际上等价于"验证不存在"。

**附带修复**（与 D 清理无关，是 a-prime HEAD 上 `1590cfc` 漏改的预存在不一致）：

- `packages/orule-common/src/test/java/com/orule/common/entity/ObjectTypeEntityTest.java:25-33`
  - 同步 `ObjectType.Kind` 枚举（`1590cfc` 增加了 `VOID`，但断言仍写死 `length == 2 / CLASS / ENUM`）
  - 改为 `length == 3 / CLASS / ENUM / VOID`
- 影响范围：仅 1 个测试方法，断言数量 +1

---

## 任务清单

```
[x] 1. 扫描确认现存 com.orule.dsl.* 包           → 0 个（与预期一致）
[x] 2. 扫描确认现存 com.orule.dsl.codegen.* 包   → 0 个
[x] 3. 扫描确认现存 CompileService                → 0 个
[x] 4. 扫描确认 orule-server 的 GraalVM Polyglot 依赖 → 0 行
[x] 5. 扫描确认 RFC-0018-bis / RFC-0019 中残留的 SimpleTSParser.parse 调用点 → 0 命中
[x] 6. 删除 com.orule.dsl.* 包                  → N/A（不存在）
[x] 7. 删除 com.orule.dsl.codegen.*              → N/A（不存在）
[x] 8. 删除 CompileService                       → N/A（不存在；路径 B 也没创建它——GroovySourceIntakeService 直接接管）
[x] 9. 删除 SimpleTS AST 节点 record             → N/A（不存在）
[x] 10. 从 orule-server/pom.xml 删除 GraalVM polyglot + js 依赖 → N/A（从未存在）
[x] 11. 从 orule-common/pom.xml 删除 GraalVM polyglot 依赖   → N/A（从未存在）
[x] 12. 删除 SimpleTSParserTest / GroovyCodeGenTest / TsAstParserTest → N/A（不存在）
[x] 13. 删除 RFC-0018 / RFC-0019 中残留的 compile() 调用点   → 0 命中
[x] 14. mvn -pl packages/orule-common,packages/orule-server -am clean test → BUILD SUCCESS (94 tests / 0 fail)
[x] 15. 检查 orule-common jar 体积（移除 GraalJS 后 -30 MB）  → N/A（GraalJS 从未引入；jar 体积本就不含）
[x] 16. 通知 AGENTS.md：删除「STY-J001 import vs inline 限定符」中提及 LocalStorage 的提示 → **不删**
       理由：STY-J001 触发者注释指向 `LocalStorage.java:26-28`，与 SimpleTSParser 无关；
       该规约仍然适用于 LocalStorage 写法，提示保留。
```

---

## 设计要点

### 删除顺序

```
路径 b (Controller) ──┐
路径 c (whitelist)  ──┼── 先合并到 feature/a-prime
路径 d (Cleanup)    ──┘── 最后合并（依赖 b/c 先合）
```

**为什么 d 最后合？** 因为 b/c 引入的新代码（`GroovySourceIntakeService` / `simplets-whitelist.json`）会被 d 的删除操作意外干掉。

### 删除前的依赖图（现状：所有项均不存在）

```
SimpleTSParser (com.orule.dsl)
    ├── TsAstParser          ← 不存在
    ├── WhitelistPruner      ← 不存在
    ├── FieldValidator       ← 不存在
    ├── IdentifierResolver   ← 不存在
    ├── TypeChecker          ← 不存在
    ├── LValueChecker        ← 不存在
    └── ast.* (16 records)   ← 不存在

CompileService (com.orule.server.service)  ← 不存在（由 GroovySourceIntakeService 替代，B 路径新增）

orule-server/pom.xml
    ├── org.graalvm.polyglot:polyglot  ← 不存在
    └── org.graalvm.polyglot:js       ← 不存在

orule-common/pom.xml
    └── org.graalvm.polyglot 依赖      ← 不存在
```

### 不删

- ❌ 不删 `com.orule.common.whitelist.SimpletsWhitelistLoader`（路径 c 新增，是 white list 唯一存留）
- ❌ 不删 Groovy 沙箱（RFC-0020）
- ❌ 不删 `RuleVersion.groovy_source` / `RuleArtifact` 表结构
- ❌ 不删 `ArtifactStorage` 接口

---

## 与 a/b/c 的依赖

| 路径 | 必须先合 | 原因 |
|------|----------|------|
| b (Controller) | ✅ | GroovySourceIntakeService 需要先落地，否则 d 删除 CompileService 后无人替代 |
| c (whitelist) | ✅ | simplets-whitelist.json 必须先落到 orule-common，否则后续 AGENTS.md / 沙箱白名单无单一来源 |

---

## 验证清单（实际执行结果）

```
[x] grep -r 'SimpleTSParser' packages/                          → 0 命中
[x] grep -r 'TsAstParser' packages/                             → 0 命中
[x] grep -r 'com.orule.dsl' packages/                           → 0 命中
[x] grep -r 'GraalJS\|graalvm' packages/*/pom.xml              → 0 命中
[x] grep -r 'GraalJS\|graalvm' packages/  (源码)               → 0 命中
[x] grep -r 'CompileService' packages/                          → 0 命中
[x] mvn -pl packages/orule-common,packages/orule-server,packages/orule-runtime -am clean test → BUILD SUCCESS (94 tests / 0 fail)
[~] 启动 orule-server，确认无 ClassNotFoundException / GraalJS 日志 → 跳过（CI 集成测试在路径 b/c 的 Testcontainers 已覆盖）
```

---

## 落点文件清单

### 实际改动

```
packages/orule-common/src/test/java/com/orule/common/entity/ObjectTypeEntityTest.java  ← 同步 Kind.VOID 断言
TASKS.md                                                                                ← 标记完成 + 摘要
```

### 删除清单（实际：N/A）

```
packages/orule-common/src/main/java/com/orule/dsl/                ← 不存在
packages/orule-server/src/main/java/com/orule/server/service/CompileService.java  ← 不存在
packages/orule-server/pom.xml (GraalVM polyglot/js)              ← 不存在
packages/orule-common/pom.xml (GraalVM polyglot)                  ← 不存在
packages/orule-common/src/test/java/com/orule/dsl/                ← 不存在
packages/orule-server/src/test/java/com/orule/server/service/CompileServiceTest.java  ← 不存在
```

### 保留清单（白名单）

```
packages/orule-common/src/main/java/com/orule/common/whitelist/                ← 路径 c 新增（注意：实际包名是 common/whitelist，不是 dsl/whitelist）
packages/orule-common/src/main/resources/simplets-whitelist.json               ← 路径 c 新增
```

---

## 提交流程

```
1. 在 ../orule-feature-d-cleanup 工作
2. 完成后 git add + commit 到 feature/d-cleanup
3. 不推远端（WT3 = WT3A）
4. 合并顺序：b → c → d → feature/a-prime
5. 合并到 main 后做一次 build + smoke test
```

---

## 不在范围（推迟）

- 删除 SimpleTS SourceOfTruth 文档（保留作为设计参考，由路径 b 的 controller commit 清理）
- AGENTS.md STY-J001 删改提示推迟
- 性能回归测试（移除 GraalJS 后 -30 MB + 启动加速验证）
- 全仓 jdeps / dependency:analyze（确认无 GraalJS transitive 残留）
