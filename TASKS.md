# Worktree D · feature/d-cleanup · 删除 SimpleTSParser + GraalJS 依赖

> **分支**：`feature/d-cleanup`
> **路径**：`../orule-feature-d-cleanup`
> **基于**：`feature/a-prime`
> **目标**：删除 orule-common / orule-server 中所有与 SimpleTSParser / TsAstParser / GroovyCodeGen / CompileService / GraalJS 相关代码与依赖
> **顺序**：**最后合并**（依赖 a/b/c 三个 worktree 先合并）

---

## 任务清单

```
[ ] 1. 扫描确认现存 com.orule.dsl.* 包
       $ find packages/orule-common packages/orule-server -path '*/com/orule/dsl/*' -name '*.java'
[ ] 2. 扫描确认现存 com.orule.dsl.codegen.* 包
[ ] 3. 扫描确认现存 CompileService
       $ find packages/orule-server -name 'CompileService*.java'
[ ] 4. 扫描确认 orule-server 的 GraalVM Polyglot 依赖
       grep 'graalvm' packages/orule-server/pom.xml
       grep 'graalvm' packages/orule-common/pom.xml
[ ] 5. 扫描确认 RFC-0018-bis / RFC-0019 中残留的 SimpleTSParser.parse 调用点
       grep 'SimpleTSParser' packages/ -r
[ ] 6. 删除 com.orule.dsl（含 SimpleTSParser / TsAstParser / WhitelistPruner / FieldValidator / TypeChecker / IdentifierResolver / LValueChecker / SimpleTSError 等）
[ ] 7. 删除 com.orule.dsl.codegen（含 GroovyCodeGen / GroovyWriter / CodeGenContext）
[ ] 8. 删除 CompileService（若已存在）；若与路径 b 的 GroovySourceIntakeService 冲突，由路径 d 后合者删除
[ ] 9. 删除 SimpleTS AST 节点 record（com.orule.dsl.ast.*）— 全部属于 §3.1
[ ] 10. 从 orule-server/pom.xml 删除：
        <dependency>
            <groupId>org.graalvm.polyglot</groupId>
            <artifactId>polyglot</artifactId>
        </dependency>
        <dependency>
            <groupId>org.graalvm.polyglot</groupId>
            <artifactId>js</artifactId>
        </dependency>
[ ] 11. 从 orule-common/pom.xml 删除 org.graalvm.polyglot 依赖（如果存在）
[ ] 12. 删除单元测试中的 SimpleTSParserTest / GroovyCodeGenTest / TsAstParserTest（与代码同步删除）
[ ] 13. 删除 RFC-0018 / RFC-0019 中残留的 compile() 调用点（如有）
[ ] 14. mvn -pl packages/orule-common,packages/orule-server -am clean test
[ ] 15. 检查 orule-common jar 体积（移除 GraalJS 后 -30 MB）
        mvn -pl packages/orule-common clean package
        ls -lh packages/orule-common/target/*.jar
[ ] 16. 通知 AGENTS.md：删除「STY-J001 import vs inline 限定符」中提及 LocalStorage 的提示（只与当前 SimpleTSParser 间接相关，看是否还要保留）
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

### 删除前的依赖图

```
SimpleTSParser (com.orule.dsl)
    ├── TsAstParser          ← 需删除
    ├── WhitelistPruner      ← 需删除
    ├── FieldValidator       ← 需删除
    ├── IdentifierResolver   ← 需删除
    ├── TypeChecker          ← 需删除
    ├── LValueChecker        ← 需删除
    └── ast.* (16 records)   ← 需删除

CompileService (com.orule.server.service)  ← 需删除（由 GroovySourceIntakeService 替代）

或ule-server/pom.xml
    ├── org.graalvm.polyglot:polyglot  ← 需删除
    └── org.graalvm.polyglot:js       ← 需删除

或ule-common/pom.xml
    └── org.graalvm.polyglot 依赖（如有） ← 需删除
```

### 不删

- ❌ 不删 `com.orule.dsl.simplets-whitelist.SimpletsWhitelistLoader`（路径 c 新增，是 white list 唯一存留）
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

## 验证清单

```
[ ] grep -r 'SimpleTSParser' packages/                          → 仅允许路径 c 的 Loader / 文档引用
[ ] grep -r 'TsAstParser' packages/                             → 0 命中
[ ] grep -r 'com.orule.dsl' packages/orule-server               → 0 命中
[ ] grep -r 'GraalJS\|graalvm' packages/orule-server/pom.xml   → 0 命中
[ ] grep -r 'CompileService' packages/                          → 0 命中
[ ] mvn -pl packages/orule-common,packages/orule-server -am clean test → 全部 PASS
[ ] 启动 orule-server，确认无 ClassNotFoundException / GraalJS 日志
```

---

## 落点文件清单

```
# 删除清单（d 全部移除）
packages/orule-common/src/main/java/com/orule/dsl/                ← 整体目录
packages/orule-server/src/main/java/com/orule/server/service/CompileService.java  ← 单文件
packages/orule-server/pom.xml                                      ← 移除 2 个 GraalJS 依赖
packages/orule-common/pom.xml                                      ← 移除 GraalJS 依赖（如有）
packages/orule-common/src/test/java/com/orule/dsl/                 ← 整体目录（如果存在）
packages/orule-server/src/test/java/com/orule/server/service/CompileServiceTest.java  ← 删除

# 保留清单（白名单）
packages/orule-common/src/main/java/com/orule/dsl/whitelist/      ← 路径 c 新增
packages/orule-common/src/main/resources/simplets-whitelist.json ← 路径 c 新增
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
