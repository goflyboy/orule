# ADR-012-A'：将 SimpleTS → Groovy 编译迁出 orule-server，改用本地 Skill（Node.js 版）+ MCP 落库

> **状态**：**已采纳** ✅
> 日期：2026-09-12
> 决策者：架构组
> **取代**：[ADR-012-GraalJS替代方案选型.md](ADR-012-GraalJS替代方案选型.md) 方案 A 的进一步演进（保留 A/B/C 作为备选记录）
> 相关：RFC-0018 §3.9、RFC-0019、ADR-003、ADR-006、ADR-009、`tmpdocs/TDD推进RFC0018-20-待确认问题.md`

---

## 1. 背景与动机

### 1.1 ADR-012 方案 A 的痛点再盘点

ADR-012 推荐 **方案 A：Node.js 子进程 + JSON-RPC** 作为 RFC-0018 §3.9 的替换实现，但仍有以下不足：

| 痛点 | 影响 |
|------|------|
| orule-server 仍然要拉一个常驻 Node.js 进程 | 增加运维点（每实例 1 个常驻进程），心跳/崩溃自愈/优雅关闭都要写 |
| SimpleTS → Groovy 编译链路在服务端实现 | 占用 orule-server CPU，且编译结果必须再上传/落库，两跳延迟 |
| SimpleTS 校验器（白名单剪枝 / FieldValidator）仍在服务端 | 服务端依然要保留 `SimpleTSParser.parse()`，仍然要承担解析职责 |
| 本地已有 Node.js + LLM Agent 环境（Electron 应用） | 重复装 Node 资源没意义 |

### 1.2 A' 的核心洞察

**既然 NL → SimpleTS 翻译本来就是在本地 LLM-Studio（Electron + Node.js）里通过 Skill 完成的，SimpleTS → Groovy 编译为什么不能在同一个 Node.js 进程里做完？**

```
【原方案】                  【A' 方案】
NL → SimpleTS  ┐ 都在本地 LLM-Studio（Electron + Node.js）
                ├→ 调本地 Skill 一次性产出 Groovy 源码
SimpleTS → TS  │
SimpleTS → Groovy
                       └→ POST MCP 接口 把 Groovy 落 orule-server
                          (orule-server 只负责"接 + 存"，不解析任何 DSL)
```

**结果**：orule-server 不再持有任何 TS / Groovy 代码生成代码，不再持有 GraalJS，不再持有 SimpleTS 解析器。**只负责存数据、调沙箱执行**。

---

## 2. 决策

采用 **A' 变种方案**：

1. **新增 2 个本地 Skill（Node.js 版）**，跑在 orule-llm-studio（Electron + Node.js）本地环境：
   - Skill #1：`nl-to-simplets` — NL → SimpleTS 翻译（对应 RFC-0023）
   - **Skill #2：`simplets-to-groovy`** — SimpleTS → Groovy 编译（本 ADR 核心新增）

2. **新增 1 个 MCP 落库端点**（orule-server 侧）：`POST /api/v1/rules/{ruleVersionId}/groovy-source`
   - 入参：`{ groovySource: string, compileLog?: string, sha256: string }`
   - 行为：写入 `RuleVersion.groovy_source` + 创建 `RuleArtifact` 记录 + 上传 `ArtifactStorage`
   - **不**做任何 TS/SimpleTS/Groovy 校验（仅校验 SHA256 + 字段非空）

3. **MVP 删除 RFC-0018 §3.9 `TsAstParser` 与 §3.2 `SimpleTSParser.parse()`**
   - 服务端不再产出 SimpleTS-AST
   - 服务端**不**做白名单剪枝 / FieldValidator / 标识符解析 / 类型检查
   - 这些责任全部下放到 Skill #2 的 Node.js 实现里

4. **触发时机**：编辑器点"保存" → 本地 Skill 实时编译 → 成功后 MCP 落库
   - 失败 → MCP 落库携带 `compileLog`，`RuleArtifact.compileStatus = FAILED`

---

## 3. 链路对比

### 3.1 原方案（A：服务端编译）

```
┌────────────────── orule-llm-studio（Electron）─────────────────┐
│  NL → SimpleTS (Skill)                                          │
│      ↓                                                          │
│  POST /api/v1/rules/{id}/simplets  (保存 SimpleTS 源码)        │
└────────────────────────────────────────────────────────────────┘
                              ↓ HTTP
┌──────────────────────── orule-server (JDK) ─────────────────────┐
│  RFC-0018 SimpleTSParser.parse()                                │
│    1. TsAstParser（GraalJS）+ typescript  ←─── ADR-012 替换目标  │
│    2. WhitelistPruner（白名单剪枝）                              │
│    3. IdentifierResolver                                         │
│    4. FieldValidator                                             │
│    5. TypeChecker                                                │
│    6. LValueChecker                                              │
│      ↓                                                          │
│  RFC-0019 GroovyCodeGen.generate()                              │
│      ↓                                                          │
│  RFC-0019 CompileService.compile()                              │
│      ↓                                                          │
│  落 RuleVersion.groovy_source + RuleArtifact                    │
└────────────────────────────────────────────────────────────────┘
                              ↓ HTTP
                       ArtifactStorage
```

### 3.2 A' 方案（本地 Skill 编译 + MCP 落库）

```
┌────────────────── orule-llm-studio（Electron + Node.js）─────────────────┐
│  NL → SimpleTS (Skill #1: nl-to-simplets)                               │
│      ↓                                                                  │
│  SimpleTS → Groovy (Skill #2: simplets-to-groovy)   ★ 本 ADR 新增       │
│      ↓                                                                  │
│  POST /api/v1/rules/{id}/groovy-source  (MCP 落库)                      │
│    body: { groovySource, compileLog?, sha256 }                          │
└─────────────────────────────────────────────────────────────────────────┘
                              ↓ HTTP
┌──────────────────────── orule-server (JDK) ────────────────────────────┐
│  GroovySourceIntakeController (新)                                     │
│    1. SHA256 校验                                                       │
│    2. 入参非空校验（groovySource 不能为空）                              │
│    3. 落 RuleVersion.groovy_source                                      │
│    4. 落 RuleArtifact (compileStatus = SUCCESS / FAILED)                │
│    5. 上传 ArtifactStorage                                              │
│      ↓                                                                  │
│  【不做】 TS / SimpleTS / Groovy 任何校验                                │
└────────────────────────────────────────────────────────────────┘
                              ↓ HTTP
                       ArtifactStorage
```

**关键变化**：

- orule-server **完全不再持有 `SimpleTSParser` / `GroovyCodeGen` / `TsAstParser`**
- orule-server jar 体积节省：GraalJS (-30MB) + SimpleTSParser (-5KB) + GroovyCodeGen (-3KB) = -30 MB
- 编译耗时从服务端 100~500ms 移到本地，与编辑器保存同步；网络只剩一次 POST
- 编译失败实时可见（编辑器侧 UI 直接拿到 `compileLog`）

---

## 4. Skill #2：`simplets-to-groovy` 设计（核心新增）

### 4.1 入口

```javascript
// simplets-to-groovy/skill.ts   (Node.js + typescript)
import { Skill, SkillContext } from '@orule/skills-runtime';
import * as ts from 'typescript';
import { generateGroovy } from './groovy-codegen';

export default new Skill({
  name: 'simplets-to-groovy',
  version: '0.1.0',
  description: '把 SimpleTS 源码 + DomainMeta 编译为 Groovy 源码',
  inputs: {
    simpleTs: { type: 'string', required: true, maxLen: 100_000 },
    domainMeta: { type: 'object', required: true },  // RFC-0015 元数据 API 形态
  },
  outputs: {
    groovySource: { type: 'string' },
    compileLog: { type: 'string', nullable: true },
    sha256: { type: 'string' },
    durationMs: { type: 'number' },
  },
  async run(ctx: SkillContext) {
    const t0 = Date.now();
    try {
      // 1. typescript 解析（零外部依赖，直接 require typescript）
      const sf = ts.createSourceFile('rule.ts', ctx.input.simpleTs,
        ts.ScriptTarget.ES2020, true);

      // 2. 简化版 SimpleTS-AST：剪枝 + 校验一次性合并
      const program = buildSimpleTsAst(sf, ctx.input.domainMeta);

      // 3. 生成 Groovy
      const groovySource = generateGroovy(program, ctx.input.domainMeta);

      return {
        groovySource,
        compileLog: null,
        sha256: sha256(groovySource),
        durationMs: Date.now() - t0,
      };
    } catch (e) {
      return {
        groovySource: '',
        compileLog: e instanceof TssCompileError ? e.formatAll() : String(e),
        sha256: '',
        durationMs: Date.now() - t0,
      };
    }
  },
});
```

### 4.2 关键依赖（许可证 + 活跃度核查）

| 依赖 | 版本（2026-09） | 许可证 | 活跃度 | 开源风险 |
|------|----------------|--------|--------|----------|
| **Node.js**（Electron 内含） | 22.x / 24.x | MIT | 极高（OpenJS Foundation 顶级项目） | **无** |
| **typescript** | 5.5.x | Apache-2.0 | 极高（Microsoft 月度发版） | **无** |
| **@orule/skills-runtime**（本仓库） | 自研 | Apache-2.0（拟） | 与仓库同步 | **无** |

**与 ADR-012 方案 A 的依赖完全一致**，零新增外部依赖。

### 4.3 与原 SimpleTSParser 的对应关系

| 原 SimpleTSParser 步骤（Java） | A' Skill 实现位置 |
|--------------------------------|---------------------|
| §3.9 TsAstParser（GraalJS 桥接） | `ts.createSourceFile` 直接调用（Node.js 同进程） |
| §3.5 WhitelistPruner | `buildSimpleTsAst` 内的 `prune(node, allowedKinds)` |
| §3.6 FieldValidator | `validateField(node, domainMeta)` |
| §3.7 IdentifierResolver | `resolveIdentifiers(program, domainMeta.context)` |
| §3.8 TypeChecker | `checkTypes(program, domainMeta)` |
| §3.9 LValueChecker | `checkLValue(stmt)` |
| RFC-0019 GroovyCodeGen | `generateGroovy(program, meta)` |
| RFC-0018 §3.10 SimpleTSWhitelist | `simplets-whitelist.ts`（单一来源，与 Groovy 沙箱白名单共享） |

**SimpleTSWhitelist 单一来源**：Skill 端定义 `simplets-whitelist.ts`，**JSON 导出**为 RFC-0018 §3.10 的等价签名集；orule-server 端 Groovy 沙箱 `SandboxConfig.forbiddenMethods()` 在 CI 时通过 npm script 把同一份 JSON 拉到 `orule-common/src/main/resources/simplets-whitelist.json`（构建期单向同步）。

### 4.4 Skill 沙箱（Node.js 进程内约束）

```javascript
// simplets-to-groovy/skill.config.ts
export default {
  sandbox: {
    // 1. 禁止 child_process（不能 fork / spawn）
    allowChildProcess: false,
    // 2. 禁止 fs（不能读写文件）
    allowFs: false,
    // 3. 禁止 net（不能开 socket）
    allowNet: false,
    // 4. 限制内存（512 MB）
    maxMemoryMb: 512,
    // 5. 限制执行时间（5 s）
    timeoutMs: 5_000,
    // 6. 禁止 require 敏感模块
    forbiddenModules: [
      'fs', 'child_process', 'cluster', 'worker_threads',
      'dgram', 'dns', 'http', 'https', 'net', 'tls', 'url',
    ],
  },
};
```

**沙箱强度**：Node.js Electron 渲染进程隔离 + `@orule/skills-runtime` 拦截模块加载 + 资源上限。**强于 RFC-0018 §3.9 的 GraalJS Context 配置**（GraalJS 沙箱配置项有 5 项必须同时配对，遗漏即破；Node.js 模块系统天然隔离）。

---

## 5. orule-server 侧改动

### 5.1 新增接口：`GroovySourceIntakeController`

```java
// packages/orule-server/src/main/java/com/orule/server/controller/GroovySourceIntakeController.java
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class GroovySourceIntakeController {

    private final GroovySourceIntakeService intakeService;

    /**
     * 接收本地 Skill 编译产出的 Groovy 源码，落库 + 上传 Artifact。
     *
     * <p>MCP 落库端点；不做任何 DSL 校验。
     *
     * <p>本接口替代 RFC-0019 §3.7 CompileService 的服务端编译职责。
     */
    @PostMapping("/{ruleVersionId}/groovy-source")
    public GroovySourceIntakeResponse intake(
        @PathVariable String ruleVersionId,
        @Valid @RequestBody GroovySourceIntakeRequest req
    ) {
        return intakeService.intake(ruleVersionId, req);
    }
}
```

```java
// packages/orule-server/src/main/java/com/orule/server/dto/GroovySourceIntakeRequest.java
public record GroovySourceIntakeRequest(
    @NotBlank @Size(max = 100_000) String groovySource,    // 与 RFC-0020 §3.3 MAX_SCRIPT_LENGTH 一致
    String compileLog,                                     // 失败时携带；成功为 null
    @NotBlank String sha256,                               // sha256(groovySource)
    @Min(0) long durationMs                                // Skill 端编译耗时，便于审计
) {}
```

```java
// packages/orule-server/src/main/java/com/orule/server/dto/GroovySourceIntakeResponse.java
public record GroovySourceIntakeResponse(
    String ruleVersionId,
    String artifactId,
    String compileStatus,    // SUCCESS / FAILED
    Instant storedAt
) {}
```

### 5.2 新增服务：`GroovySourceIntakeService`

```java
// packages/orule-server/src/main/java/com/orule/server/service/GroovySourceIntakeService.java
@Service
@RequiredArgsConstructor
public class GroovySourceIntakeService {

    private final RuleVersionRepository versionRepo;
    private final RuleArtifactRepository artifactRepo;
    private final ArtifactStorage storage;

    @Transactional
    public GroovySourceIntakeResponse intake(String ruleVersionId, GroovySourceIntakeRequest req) {
        RuleVersion version = versionRepo.findById(ruleVersionId)
            .orElseThrow(() -> new NotFoundException("RuleVersion", ruleVersionId));

        // 1. SHA256 校验
        String actual = sha256Hex(req.groovySource());
        if (!actual.equalsIgnoreCase(req.sha256())) {
            throw new IllegalArgumentException("sha256 mismatch: declared=" + req.sha256()
                + " actual=" + actual);
        }

        // 2. 失败 / 成功分支
        boolean success = req.compileLog() == null || req.compileLog().isBlank();
        CompileStatus status = success ? CompileStatus.SUCCESS : CompileStatus.FAILED;

        // 3. 落 RuleVersion（成功才写 groovy_source；失败保留旧值）
        if (success) {
            version.setGroovySource(req.groovySource());
            versionRepo.save(version);
        }

        // 4. 落 RuleArtifact
        String storageKey = String.format("rules/%s/v%d.groovy",
            version.getRule().getCode(), version.getVersion());
        UploadResult upload = success
            ? storage.upload(storageKey, req.groovySource().getBytes(UTF_8))
            : UploadResult.empty();

        RuleArtifact artifact = RuleArtifact.builder()
            .id(UUID.randomUUID().toString())
            .ruleVersionId(ruleVersionId)
            .storageType(storage.getType())
            .storagePath(upload.storagePath())
            .storageUrl(upload.url())
            .fileSize(upload.fileSize())
            .sha256(req.sha256())
            .compileStatus(status)
            .compileLog(req.compileLog())
            .build();
        artifactRepo.save(artifact);

        return new GroovySourceIntakeResponse(
            ruleVersionId, artifact.getId(), status.name(), Instant.now());
    }
}
```

### 5.3 删除内容

| 删除 | 说明 |
|------|------|
| `com.orule.dsl`（含 `SimpleTSParser` / `TsAstParser` / `WhitelistPruner` / `FieldValidator` / `TypeChecker` / `IdentifierResolver` / `LValueChecker`） | 全部迁到 Skill #2（Node.js 版） |
| `com.orule.dsl.codegen.GroovyCodeGen` | 迁到 Skill #2 |
| RFC-0019 `CompileService` | 替换为 `GroovySourceIntakeService`（不再做编译，只接产物） |
| `org.graalvm.polyglot:polyglot` 与 `org.graalvm.polyglot:js` 依赖 | 不再引入 |
| RFC-0018 §3.9 整节 | 改写为 Skill #2 描述 |

### 5.4 保留内容

| 保留 | 说明 |
|------|------|
| `orule-runtime` 的 Groovy 沙箱（RFC-0020） | **与本 ADR 无关**，继续用 Groovy SecureASTCustomizer |
| `RuleVersion.groovy_source` / `RuleArtifact` 表结构 | 复用，字段语义不变 |
| `ArtifactStorage` 接口 | 复用 |
| RFC-0023（NL→SimpleTS）的 Skill #1 | 与本 ADR 并列存在 |

---

## 6. 触发流程

### 6.1 编辑器点"保存" → MCP 落库

```
┌──── orule-llm-studio (Electron) ────────────────────────────────────┐
│                                                                      │
│  1. 用户在 SimpleTS 编辑器按 Ctrl+S                                 │
│  2. 保存 SimpleTS 源码到本地草稿                                     │
│  3. 【自动触发】Skill #2 simplets-to-groovy                          │
│     - 输入: simpleTsSource + domainMeta                             │
│     - 输出: { groovySource, compileLog, sha256, durationMs }         │
│  4. 如果 compileLog 为空 → MCP 落库                                  │
│     POST /api/v1/rules/{ruleVersionId}/groovy-source                 │
│     body: { groovySource, sha256, durationMs }                       │
│     --------------------------------------------------------         │
│     200: { compileStatus: SUCCESS, artifactId }                     │
│     编辑器 UI: ✓ 已编译                                              │
│  5. 如果 compileLog 非空 → 仍落库（compileStatus=FAILED）             │
│     POST /api/v1/rules/{ruleVersionId}/groovy-source                 │
│     body: { groovySource:"", compileLog, sha256:"", durationMs }    │
│     --------------------------------------------------------         │
│     200: { compileStatus: FAILED, artifactId }                       │
│     编辑器 UI: ✗ 第 3 行第 5 列: 未声明的标识符 'invoice'             │
└──────────────────────────────────────────────────────────────────────┘
```

### 6.2 失败可见性

**对比原方案**：

| 维度 | 原方案（服务端编译） | A' 方案（Skill 编译） |
|------|----------------------|------------------------|
| 失败信息回到编辑器 | 异步轮询 `/compile-log` 或 WebSocket | 同步 HTTP 响应直接拿到 |
| 失败定位 | 服务端日志 + 客户端轮询 | 编辑器即时显示，**毫秒级反馈** |
| 失败时 RuleArtifact 记录 | 服务端记录 | 服务端记录（compileStatus=FAILED） |

---

## 7. 与 ADR-012 方案 A 的对比

| 维度 | **A'：本地 Skill + MCP 落库（推荐）** | A：Node.js 子进程 + JSON-RPC | B：GraalJS 退化 | C：ANTLR4 自研 |
|------|---------------------------------------|------------------------------|------------------|------------------|
| orule-server 是否持有 Node.js 常驻进程 | ✅ **不持有** | 🟡 持有 1 个常驻进程 | ✅ 不持有（GraalJS jar） | ✅ 不持有 |
| orule-server 是否持有 SimpleTSParser | ✅ **不持有（MVP）** | 🟡 持有（GraalJS 调用 TS Compiler） | 🟡 持有 | ✅ 持有（ANTLR 自研） |
| orule-server 是否持有 GroovyCodeGen | ✅ **不持有** | 🟡 持有 | 🟡 持有 | 🟡 持有 |
| orule-server jar 体积 | ✅ **-30 MB（移除 GraalJS、解析器、codegen）** | 🟡 -30 MB（移除 GraalJS） | ❌ +30 MB | ✅ -30 MB |
| **编译耗时（编辑器视角）** | ✅ **本地同步（< 50ms + 网络）** | 🟡 服务端异步（100~500ms） | 🟡 服务端异步（300ms） | 🟢 服务端异步（< 100ms） |
| **编译失败反馈速度** | ✅ **毫秒级**（同步响应） | 🟡 秒级（轮询/WebSocket） | 🟡 秒级 | 🟡 秒级 |
| TS 语法支持 | ✅ 5.x 即时可用（typescript npm） | ✅ 同 A' | ✅ 同 A' | 🟡 滞后 |
| **沙箱强度** | ✅ **Node.js 模块系统隔离 + Skill Runtime** | ✅ 进程隔离 | 🟡 JVM 配置依赖 | ✅ JVM 进程内 |
| **实施成本** | ✅ **3~5 人天**（2 Skill + 1 接口） | ✅ 1~2 人天 | ✅ 0 | 🟡 5~8 人天 |
| 维护成本 | 🟡 Skill 库单独维护 | ✅ 低（JSON-RPC） | 🟡 中（GraalVM 版本漂移） | 🟡 中（grammar 跟进） |
| **部署依赖** | ✅ **零新增（Electron 已含 Node.js）** | 🟡 + Node.js 18+ | ✅ 无 | ✅ 无 |
| **开源风险** | ✅ **零**（MIT + Apache-2.0） | ✅ 同 A' | 🟡 中（GPL-2.0 CPE） | ✅ 低（BSD-3 / Apache-2.0） |
| **活跃度** | ✅ **极高** | ✅ 极高 | 🟢 高 | 🟢 / 🟡 中 |
| **E2E 链路缩短** | ✅ **本地编译 + 1 次 HTTP 落库** | 🟡 1 次 HTTP 编译 + 1 次落库（合并为 1 次） | 🟡 编译落库一体化 | 🟡 同 B |

**A' vs A 的根本差异**：

- A 把 Node.js 推到 **服务端**，作为常驻子进程
- A' 把 Node.js 留在 **本地 LLM-Studio**，作为已有 Electron 环境的复用
- A' 进一步**迁出了 SimpleTSParser / GroovyCodeGen**，让 orule-server 退化为"纯数据 + 执行"服务

---

## 8. 实施步骤

```
1. 在 orule-llm-studio 仓库新增 skill 目录：
   simplets-to-groovy/
     ├─ skill.ts                    # Skill 入口
     ├─ ts-parser.ts                # ts.createSourceFile 封装
     ├─ simplets-whitelist.ts       # 白名单（导出 JSON）
     ├─ simplets-pruner.ts          # 剪枝（替代原 WhitelistPruner）
     ├─ simplets-validator.ts       # 校验（替代原 FieldValidator / IdentifierResolver）
     ├─ groovy-codegen.ts           # codegen（替代原 GroovyCodeGen）
     ├─ errors.ts                   # 错误模板
     ├─ tests/                      # vitest 单测（覆盖原 RFC-0018/0019 全部 case）
     └─ package.json                # typescript ^5.5, vitest ^2.0

2. 新增 orule-llm-studio Skill #1 依赖：simplets-to-groovy（workspace:*）

3. orule-server 侧新增：
   - DTO: GroovySourceIntakeRequest / GroovySourceIntakeResponse
   - Service: GroovySourceIntakeService
   - Controller: GroovySourceIntakeController
   - 测试: WebMvcTest + Mockito（覆盖成功 / 失败 / SHA256 mismatch / 长度上限）

4. orule-common 侧删除：
   - com.orule.dsl（SimpleTSParser 及全部相关类）
   - com.orule.dsl.codegen（GroovyCodeGen 及全部相关类）
   - SimpleTSWhitelist → 改为 simplets-whitelist.ts JSON 在 orule-runtime 构建期单向同步

5. orule-server/pom.xml 删除：
   - org.graalvm.polyglot:polyglot
   - org.graalvm.polyglot:js

6. 单元测试（orule-server）：
   - SHA256 mismatch 抛 IllegalArgumentException
   - compileLog 非空 → compileStatus = FAILED
   - compileLog 为空 → compileStatus = SUCCESS + 落 groovy_source + 上传 Artifact

7. 集成测试：
   - 起 orule-server（Testcontainers）
   - Skill #2 编译 SimpleTS → Groovy
   - POST MCP 落库 → 查 RuleArtifact.compileStatus
   - 失败用例：未声明标识符 → 错误信息带行号

8. 文档更新：
   - RFC-0018：删除 §3.9 TsAstParser；改写为"由 Skill #2 在 LLM-Studio 完成"
   - RFC-0019：删除 §3.6 CompileService；改写为 GroovySourceIntakeService
   - docs/dsl/SimpleTS.md §13 实施备注：注明"编译在本地 Skill 完成"
   - ADR-012 备注 A' 方案为最终决策（保留 A/B/C 作为备选记录）
```

---

## 9. 风险与护栏

| 风险 | 等级 | 缓解 |
|------|------|------|
| **Skill 不在本地跑**（未来服务端主动 compile 怎么办？） | 🟡 中 | RFC-0019 §3.7 `POST /rule-versions/{id}/compile` 端点保留（标记 DEPRECATED）；调用方 = Service 触发"重编译"时，本 ADR 推荐**改为客户端引导用户到编辑器触发 Skill** |
| Skill 沙箱被绕过 | 🟡 中 | Node.js 模块系统 + Skill Runtime 拦截；CI fuzz 测试 |
| Skill 升级后 Groovy 产物形态变化 | 🟡 中 | RuleArtifact 记录 sha256 + compileStatus；客户端拉新版本时强制重编译 |
| 服务端无校验，恶意 Groovy 上传 | 🔴 高（但） | **契约约束**：MCP 端点仅接受本地 Skill 上传；生产环境 MCP 入口加 IP 白名单 / mTLS；Groovy 沙箱（RFC-0020）在 orule-runtime 执行时再做二次校验（沙箱被绕过时也只能拿到执行权，不能拿存储权） |
| SHA256 不一致 | 🟢 低 | 入参校验失败 → 422 Unprocessable Entity |
| 传输层 TLS | 🟢 低 | HTTPS 标配；与 MCP 其他端点一致 |
| Skill 编译失败但仍 POST 落库（compileLog 非空） | 🟢 低 | 这是设计行为：保留失败记录供审计；RuleArtifact.compileStatus = FAILED |
| **Node.js 版本兼容性**（Electron 内含 Node 版本 vs Skill 期望版本） | 🟡 中 | Skill 声明 `engines.node = ">=18"`；Electron 30+ 默认 Node 20+，满足 |
| SimpleTSWhitelist 跨端漂移（Skill TS 版 vs orule-runtime Groovy 版） | 🟡 中 | **构建期单向同步**：`simplets-whitelist.ts` 导出 JSON；npm script 在 CI 时把 JSON 拷到 `orule-common/src/main/resources/simplets-whitelist.json`；Groovy `SandboxConfig` 启动时读此 JSON |
| RFC-0018 §3.2 `SimpleTSParser.parse(source, RuleType)` 删除后，下游还有引用？ | 🟢 低 | grep 全仓 `SimpleTSParser.parse`；删除前先扫一遍；测试代码同步迁移到 Skill #2 |

---

## 10. 决策日志

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-12 | 选定 **A'：本地 Skill + MCP 落库** | 进一步缩短链路：orule-server 退化为"纯数据 + 执行"，编译迁出；本仓库已隐含 Node.js（Electron），零新增外部依赖；失败反馈毫秒级 |
| 2026-09-12 | **MVP 不做服务端 SimpleTS 校验** | orule-server 彻底不持有 DSL 解析职责；服务端只负责"接 + 存 + 跑沙箱" |
| 2026-09-12 | **触发时机 = 编辑器实时保存** | 与 §6.1 编辑器集成最自然；失败即时可见 |
| 2026-09-12 | ADR-012 方案 A / B / C 保留为备选记录 | 若未来 Skill 路线被否决（如 LLM-Studio 拆分为纯 Web 应用），可回退到 ADR-012 方案 A |

---

## 11. 关联

- **RFC**：[RFC-0018-SimpleTS解析器.md §3.9](../../rfcs/RFC-0018-SimpleTS解析器.md)（将被改写）、[RFC-0019-SimpleTS转Groovy代码生成器.md](../../rfcs/RFC-0019-SimpleTS转Groovy代码生成器.md)（§3.6 CompileService 替换）
- **ADR**：[ADR-012-GraalJS替代方案选型.md](ADR-012-GraalJS替代方案选型.md)（A' 是 ADR-012 方案 A 的进一步演进；A/B/C 保留为备选）
- **Skill 仓库**：orule-llm-studio（Electron + Node.js）— Skill #2 `simplets-to-groovy` 新增；Skill #1 `nl-to-simplets` 沿用
- **TDD 阻塞**：[tmpdocs/TDD推进RFC0018-20-待确认问题.md](../../../tmpdocs/TDD推进RFC0018-20-待确认问题.md)（D1~D5 全部受本 ADR 影响：D1 模块归属、D2 Groovy 依赖、D3 codegen 归属、D4 安全测试、D5 TestMetaFactory）
