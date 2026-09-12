# Workspace 推进总览：A' 方案并行 worktree

> **更新时间**：2026-09-12
> **总指挥**：`feature/a-prime`（含 ADR-012-Aprime + RFC-0018-bis-tris + RFC-0019-bis）
> **目标**：将 ADR-012-Aprime 落地为代码
> **分支状态**：

| 工作树 | 分支 | 路径 | 进度 | 状态 |
|--------|------|------|------|------|
| 主仓 | `feature/a-prime` | `orule/` | ✅ 文档完成 | 已提交 f700a1e |
| **B** · GroovySourceIntake 落库服务 | `feature/b-controller` | `orule-feature-b-controller/` | 📋 TASKS.md 已写 | 待实现 |
| **C** · white list 跨端同步 | `feature/c-whitelist` | `orule-feature-c-whitelist/` | 📋 TASKS.md 已写 | 待实现 |
| **D** · 清理 SimpleTSParser + GraalJS | `feature/d-cleanup` | `orule-feature-d-cleanup/` | 📋 TASKS.md 已写 | 待实现 |
| **A** · Skill #2 simplets-to-groovy | 待开 | 待 orule-llm-studio 路径给出 | — | 阻塞 |

---

## 并行性分析

### 可并行的工作

```
        ┌─────────────────────────────────────────────────────┐
        │                feature/a-prime (基线)                │
        │           ADR-012-Aprime + RFC-0018/0019 修订        │
        └─────────────────────────────────────────────────────┘
                              │  fork
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
       ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
       │   B · 落库  │ │  C · 白名单 │ │  D · 清理    │
       │  Controller │ │   Loader    │ │  删除旧代码 │
       └─────────────┘ └─────────────┘ └─────────────┘
              ▲               ▲               ▲
              │           (merge 顺序)        │
              │     b → c → d → feature/a-prime │
              └───────────────────────────────┘
```

**冲突域分析**（基于 ADR-012-Aprime §4.3 / §5 / §8 与各 RFC）：

| 冲突域 | 涉及文件 | B | C | D |
|--------|----------|---|---|---|
| `com.orule.dsl.*` 包 | orule-common | — | 保留 `whitelist/` 子包 | **删除其他** |
| `com.orule.dsl.codegen.*` | orule-common | — | — | **删除** |
| `CompileService` | orule-server | 用 `GroovySourceIntakeService` 替代 | — | **删除** |
| `GroovySourceIntake*` 新增 | orule-server | **新增** 4 文件 | — | 保留 |
| `simplets-whitelist.json` | orule-common | — | **新增** | 保留 |
| `pom.xml` org.graalvm.polyglot | orule-server/orule-common | — | — | **删除** |

> **结论**：B / C / D 几乎**不互相冲突**（除 D 会删旧代码，要小心 SimpleTSParser 删完后 GroovySourceIntakeService 引用不到）。C 保留 `whitelist/` 子包是 D 不会删的安全锚点。

### 合并顺序

```
[Step 1] feature/b-controller ──merge──▶ feature/a-prime   # B 先合：新增 Controller 落地
[Step 2] feature/c-whitelist  ──merge──▶ feature/a-prime   # C 次合：白名单 JSON 落地
[Step 3] feature/d-cleanup    ──merge──▶ feature/a-prime   # D 最后合：删 SimpleTSParser
                                                    （此时 GroovySourceIntakeService 已落地，无引用悬挂）
[Step 4] feature/a-prime      ──merge──▶ main              # 总线推主线
[Step 5] CI / smoke test / 合并冲突自动解决
```

---

## 每个 worktree 的任务清单索引

| Worktree | 任务文档 | 核心工作 |
|----------|----------|----------|
| B | `TASKS.md` | `GroovySourceIntakeController/Service/Request/Response` + 单测 + 集成测试 |
| C | `TASKS.md` | `simplets-whitelist.json` 资源 + `SimpletsWhitelistLoader` + 跨仓同步契约文档 |
| D | `TASKS.md` | 删除 `com.orule.dsl.*` + `CompileService` + GraalJS 依赖 |

---

## 跨 worktree 的契约约定

### 1. MCP 端点路径（路径 B 写，C/D 不动）

```
POST /mcp/tools/orule.rule.publishCompiledGroovy?ruleVersionId=rv-XXX
```

### 2. JSON Schema（路径 C 写，B/D 不动）

`orule-common/src/main/resources/simplets-whitelist.json` v1 schema 见 `TASKS.md` 路径 C 章节

### 3. GroovySourceIntakeRequest 字段（路径 B 写，C/D 不动）

```java
public record GroovySourceIntakeRequest(
    @NotBlank @Size(max = 100_000) String groovySource,
    String compileLog,
    @NotBlank String sha256,
    @Min(0) long durationMs
) {}
```

### 4. 跨仓契约（路径 C 写，路径 a 用）

`docs/integration/simplets-whitelist-cross-build.md` — orule-llm-studio 的 Skill #2 simplets-to-groovy 维护 `simplets-whitelist.ts`，**单向同步**到本仓 `orule-common/src/main/resources/simplets-whitelist.json`

---

## 阻塞与解除

```
[A 路径] 当前阻塞：orule-llm-studio 仓本地路径未给出
        解除条件：你回复路径后立即开 worktree + 写 TS TASKS.md
        推荐对话格式："路径：D:/path/to/orule-llm-studio"

[B 路径] 当前状态：可立即开始实现
       等待：你说"开始 B" / "先做 B"

[C 路径] 当前状态：可立即开始实现
       等待：你说"开始 C" / "先做 C"

[D 路径] 当前状态：可立即开始（但建议等 B/C 先合）
       等待：你说"开始 D" + 已确认 B/C 合并完成
```

---

## 每 worktree 的退出标准

### B 完成标准

- [ ] `packages/orule-server/src/main/java/com/orule/server/{controller,service,dto}/GroovySource*` 4 文件已存在
- [ ] `mvn -pl packages/orule-server -am clean test` 全部 PASS
- [ ] WebMvcTest 覆盖：成功 / 失败 / SHA256 mismatch / 长度上限
- [ ] 集成测试（Testcontainers MySQL）端到端通过
- [ ] 提交到 `feature/b-controller`

### C 完成标准

- [ ] `simplets-whitelist.json` 在 orule-common resources 下存在
- [ ] `SimpletsWhitelistLoader` 类已实现 + 单测
- [ ] 跨仓契约文档 `docs/integration/simplets-whitelist-cross-build.md` 已写
- [ ] 提交到 `feature/c-whitelist`

### D 完成标准

- [ ] `com.orule.dsl.*`（除 `whitelist/` 子包）已全部删除
- [ ] `CompileService.java` 已删除
- [ ] `org.graalvm.polyglot:polyglot` + `js` 依赖已删除
- [ ] `mvn -pl packages/orule-common,packages/orule-server -am clean test` 全部 PASS
- [ ] orule-common jar 体积减少 ~30 MB
- [ ] 提交到 `feature/d-cleanup`

---

## 关键文档索引（已存在）

- **ADR**：
  - `docs/adr/ADR-012-GraalJS替代方案选型.md` — SUPERSEDED（保留备选）
  - `docs/adr/ADR-012-Aprime-本地Skill编译与MCPLangLib库.md` — **采纳 ✅**
- **RFC**：
  - `docs/rfcs/RFC-0018-SimpleTS解析器.md` — §3.9 改为 Skill 化
  - `docs/rfcs/RFC-0019-SimpleTS转Groovy代码生成器.md` — §3.6 改为 GroovySourceIntakeService
- **上游**：RFC-0015（元数据 API）、RFC-0017（ArtifactStorage）、RFC-0020（Groovy 沙箱）
