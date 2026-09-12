# ADR-012：GraalJS Polyglot 替代方案选型（用于 RFC-0018 SimpleTS 解析器）

> 状态：DRAFT（待评审）
> 日期：2026-09-12（v2 修订）
> 决策者：架构组
> 相关：RFC-0018 §3.9、RFC-0019、RFC-0020、ADR-003、ADR-006、ADR-009、`tmpdocs/TDD推进RFC0018-20-待确认问题.md`

> **修订说明**：v1（2026-09-12 早）选 **方案 A（Node.js 子进程 + JSON-RPC）**；
> v2（2026-09-12 晚）经评审升级为 **方案 A'（本地 Agent 环境 + Skill 化）**——
> 识别出"orule-llm-studio 本地已有 Node.js + Cursor/OpenCode Agent"，新方案能复用现有 Skill 体系
> 把整条 NL→SimpleTS→Groovy 链路下沉到本地编辑器进程，**orule-server 完全不引入 Node.js 依赖**。
> A 方案保留为"纯服务端 jar 部署"的回退实现（详细见 §13.1 附录）。

---

## 1. 背景

RFC-0018 §3.9 `TsAstParser` 原方案使用 **GraalVM Polyglot + GraalJS** 在 JVM 内嵌入 JavaScript
引擎，通过 `require('typescript')` 调用 TypeScript Compiler API，把 SimpleTS 源码解析为 TS AST
（JSON 形态）。在当前实施阶段识别到以下问题，决定启动替代方案选型：

| 问题 | 详情 |
|------|------|
| **体积** | `polyglot` + `js` 两个 jar 合计 ~30 MB，进入 `orule-common` jar 后所有下游模块都得背 |
| **启动** | JVM 内首次创建 `Context` 约 200~500 ms，拖慢规则编译吞吐（参见 `tmpdocs/TDD推进RFC0018-20-待确认问题.md` D1） |
| **沙箱配置面** | `HostAccess.NONE` + `allowCreateThread(false)` + `allowIO(false)` 等多项必须**同时**配对，缺一项即破沙箱（RFC-0018 §6 风险表标红"GraalJS 沙箱配置错误 🔴 高"） |
| **依赖管理** | `typescript` npm 包需要 npm 资源，离线部署 / 部分国产化环境拉取不稳 |
| **链路割裂** | TS 解析在服务端、Groovy codegen 在服务端，但二者本应在同一上下文（共享 RuleType 元数据 + 错误位置），跨类跨包调用冗余 |

> **范围澄清**：本仓库当前 GraalJS 引用**仅在 RFC-0018 §3.9 一处**（grep 结果显示 Java 源码尚无 GraalJS 引用）。
> RFC-0020 Groovy 沙箱用的是 Groovy 自带的 `SecureASTCustomizer` + `SecureClassLoader`，**不**依赖 GraalJS，
> 不在本 ADR 范围内。

---

## 2. 当前 GraalJS 使用范围盘点

| RFC | 使用点 | 角色 | 是否替换 |
|-----|--------|------|----------|
| RFC-0018 §3.9 | `TsAstParser`（编译期） | JVM 内 require typescript，把 SimpleTS 源码 → TS AST JSON | ✅ 替换 |
| RFC-0019 §3.x | `GroovyCodeGen`（编译期） | SimpleTS-AST → Groovy 源码 | ✅ 一并迁移 |
| RFC-0020 §3.3 | `GroovySandbox`（运行期） | Groovy SecureASTCustomizer + SecureClassLoader | ❌ 不涉及 |

---

## 3. 核心需求（不变式）

| 编号 | 需求 | 说明 |
|------|------|------|
| **F1** | 调用 TypeScript Compiler API | `ts.createSourceFile` / `ts.SyntaxKind` / `tsNodeToJson` 等 |
| **F2** | 进程内低延迟解析 | 单条规则解析应在 50 ms 量级，避免跨主机 / 跨进程 50 ms+ 通信损耗 |
| **F3** | 沙箱化 | 编译期禁止 IO、网络、反射；运行期 Groovy 沙箱不变（RFC-0020） |
| **F4** | 部署友好 | orule-server 单一可执行 jar 优先（外部进程方案仅作为回退） |
| **F5** | 高活跃度 | 避开弃用 / 归档 / 长期未维护的项目 |
| **F6** | 开源无风险 | 宽松许可证（MIT / Apache-2.0 / BSD 优先），无 CLA / 商业限制 / 国别出口管制问题 |
| **F7** | TS 语法跟进 | TS 5.x 新语法能拿到（白名单剪枝兜底） |
| **F8** | Skill 体系复用 | 与 orule-llm-studio 已有 `.agents/skills/` 基础设施共用 |

---

## 4. 候选方案

> 2026-09-12 评审说明：本节按"推荐 → 回退 → 长期路线"组织；
> 方案 A 的原始细节（ts-parser.js + JSON-RPC 协议）保留在 §13.1 附录。

### 方案 A'：本地 Agent 环境 + Skill 化（**推荐**）

**核心思路**：

> 把"NL → SimpleTS → Groovy"这条翻译链**整体下沉到本地 Agent 环境**（Cursor / OpenCode 的 Node.js 进程）。
> 本仓库 orule-llm-studio 已是 Electron 桌面应用（Node.js 运行时已在 Electron Main Process 中）；
> 再**新增一个 skill** 承载"TS 解析 + 白名单剪枝 + 字段校验 + Groovy 代码生成 + 调 MCP 上传"五件事，
> orule-server 只承担"接收翻译产物 + 持久化 RuleArtifact + 沙箱执行 Groovy"。

#### 4.A'.1 整体架构

```
┌────────────────────────────────────────────────────────────────────┐
│  用户编辑器（Cursor / OpenCode / orule-llm-studio 内的编辑器）           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Agent 环境（Node.js 进程：Cursor MCP Server / OpenCode Agent）│  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  🆕 新增 skill：simplets-compile-and-save              │  │  │
│  │  │  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │  │  │
│  │  │  │ TS 解析     │→ │ SimpleTS   │→ │ Groovy codegen   │  │  │  │
│  │  │  │ (typescript│  │ 校验 + 白名 │  │ (RFC-0019 实现   │  │  │  │
│  │  │  │  npm 包)   │  │ 单剪枝      │  │  移植到 Node)     │  │  │  │
│  │  │  └────────────┘  └────────────┘  └──────────────────┘  │  │  │
│  │  │       ↓ SimpleTS-AST JSON ↓ Groovy 源码                │  │  │
│  │  │  ┌──────────────────────────────────────────────────┐  │  │  │
│  │  │  │ 调 orule-server MCP/REST：上传 Groovy 制品         │  │  │  │
│  │  │  │ POST /api/v1/rules/{id}/artifact                  │  │  │  │
│  │  │  └──────────────────────────────────────────────────┘  │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
                              │ HTTPS
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│  orule-server（JVM，**完全无 GraalJS / 无 Node.js 依赖**）           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  POST /api/v1/rules/{ruleId}/artifact                       │  │
│  │    - 接收已编译的 Groovy 源码 + RuleArtifact metadata         │  │
│  │    - 写入 rule_artifact 表 + 调 ArtifactStorage 保存产物      │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
                              │ 加载 RuleArtifact
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│  orule-runtime（JVM）                                              │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  RFC-0020 GroovySandbox（4 层防护，不变）                     │  │
│  │  SecureASTCustomizer / SecureClassLoader / 超时 / -Xmx       │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

#### 4.A'.2 关键依赖（许可证 + 活跃度核查）

| 依赖 | 版本（2026-09） | 许可证 | 活跃度 | 开源风险 |
|------|----------------|--------|--------|----------|
| **Node.js**（Electron 自带） | LTS 22.x / 24.x 双轨 | MIT | 极高（OpenJS Foundation 顶级项目，月度发版） | **无**（MIT 宽松，无 CLA） |
| **typescript**（npm） | 5.5.x 系列 | Apache-2.0 | 极高（Microsoft 主力维护，月度发版） | **无**（Apache-2.0 宽松） |
| **Cursor / OpenCode Agent** | 用户编辑器自带 | — | 极高（Cursor 月度 / OpenCode 持续活跃） | **无**（用户编辑器） |
| **MCP SDK（@modelcontextprotocol/sdk）** | 1.x | MIT | 极高（Anthropic 主导 + 社区） | **无**（MIT，无 CLA） |
| **仓库内已有 Skill 框架** | `.agents/skills/` | — | 仓库自维护 | — |

#### 4.A'.3 Skill 实现结构

```
.agents/skills/simplets-compile-and-save/
├── SKILL.md                    # 用户在编辑器内 @ 该 skill 时自动加载
├── package.json                # 依赖 typescript / MCP SDK
├── src/
│   ├── ts-parser.ts            # ts.createSourceFile → SimpleTS-AST JSON
│   ├── pruner.ts               # RFC-0018 §3.5 白名单剪枝（移植到 TypeScript）
│   ├── field-validator.ts      # RFC-0018 §3.6 DomainMeta 校验
│   ├── groovy-codegen.ts       # RFC-0019 实现（移植到 TypeScript）
│   ├── type-checker.ts         # RFC-0018 §3.6 类型对齐
│   ├── lvalue-checker.ts       # RFC-0018 §3.7 左值合法性
│   └── mcp-client.ts           # 调 orule-server REST 上传制品
└── test/                       # vitest
```

**关键实现（伪代码）**：

```typescript
// .agents/skills/simplets-compile-and-save/src/index.ts
import * as ts from 'typescript';
import { RuleType } from './schema';
import { prune } from './pruner';
import { validateFields } from './field-validator';
import { typeCheck } from './type-checker';
import { checkLvalue } from './lvalue-checker';
import { generateGroovy } from './groovy-codegen';
import { uploadArtifact } from './mcp-client';

// Skill 主入口
export async function compileAndSave(input: {
  source: string;          // SimpleTS 源码
  ruleType: RuleType;      // 元数据（来自 MCP tool: get_rule_type）
  ruleId: string;          // 规则 ID（用于 artifact 关联）
}): Promise<{ artifactUri: string; simpletsAst: object; groovySource: string }> {
  const startMs = Date.now();

  // 1. TS 解析（typescript 官方 npm 包）
  const sf = ts.createSourceFile('rule.ts', input.source,
    ts.ScriptTarget.ES2020, /*setParentNodes*/ true);

  // 2. 白名单剪枝（RFC-0018 §3.5，移植到 TypeScript）
  const program = prune(sf, input.ruleType);

  // 3. 字段 + 枚举校验（RFC-0018 §3.6）
  validateFields(program, input.ruleType);

  // 4. 类型对齐
  if (input.ruleType.validatable) typeCheck(program, input.ruleType);

  // 5. 左值合法性
  checkLvalue(program);

  // 6. Groovy codegen（RFC-0019，移植到 TypeScript）
  const groovySource = generateGroovy(program);

  // 7. 调 orule-server REST 上传制品
  const artifactUri = await uploadArtifact({
    ruleId: input.ruleId,
    engineType: 'GROOVY',
    compiledCode: groovySource,
    metadata: {
      sourceHash: sha256(input.source),
      simpletsAst: program,                    // 落 RuleArtifact.debugInfo
      compileDurationMs: Date.now() - startMs,
    },
  });

  return { artifactUri, simpletsAst: program, groovySource };
}
```

#### 4.A'.4 orule-server 变更

orule-server **完全不再实现 TS 解析 + Groovy codegen**。原 RFC-0018 §3.9 `TsAstParser`、
RFC-0019 整个 `com.orule.dsl.codegen` 包**全部从 orule-common 移除**。orule-server 只新增一个
接收端点：

```java
// packages/orule-server/src/main/java/com/orule/server/controller/RuleArtifactController.java
@RestController
@RequestMapping("/api/v1/rules")
public class RuleArtifactController {

    @PostMapping("/{ruleId}/artifact")
    @PreAuthorize("hasAuthority('SCOPE_rule:write')")
    @Transactional
    public RuleArtifactDto uploadArtifact(
            @PathVariable String ruleId,
            @Valid @RequestBody UploadArtifactRequest req) {

        // 1. 鉴权已在 @PreAuthorize 完成
        // 2. 校验 RuleVersion 存在 + 状态为 PUBLISHED
        // 3. 校验 Groovy 源码长度 ≤ 100 KB（与 RFC-0020 沙箱上限对齐）
        if (req.compiledCode().length() > 100_000) {
            throw new BadRequestException("GROOVY_TOO_LONG",
                "Groovy 源码长度 " + req.compiledCode().length() + " 超过上限 100KB");
        }

        // 4. SHA-256 校验 + 落 RuleArtifact + 调 ArtifactStorage
        RuleArtifact artifact = artifactService.upload(ruleId, req);
        return RuleArtifactDto.from(artifact);
    }
}
```

**影响面（重要）**：

- orule-common **移除**：`com.orule.dsl.ts.TsAstParser` + `com.orule.dsl.codegen.*` 全包
- orule-common **移除依赖**：`typescript` npm 包（不再需要）、`org.graalvm.polyglot:*`（不再引入）
- orule-common jar 体积 **-30 MB**（GraalJS 本就未引入，仅预案移除）
- orule-server 新增 1 个 REST 端点（~30 行 Java）

#### 4.A'.5 沙箱

A' **天然沙箱**：

1. **编译期**：skill 在用户编辑器进程内运行，遵循用户 OS 权限；不能访问 orule-server 内网（除非网络可达）
2. **运行期**：orule-runtime 调 `GroovySandbox`（RFC-0020 §3.3），4 层防护**不变**
3. **传输期**：HTTPS + `@PreAuthorize("hasAuthority('SCOPE_rule:write')")` + 上传 Groovy 源码
4. **审计**：orule-server 落 RuleArtifact 时记录 `compileDurationMs / sourceHash / simpletsAst`，
   与 RFC-0020 `ExecutionLog` 闭环（同一 ruleId 全链路可追溯）

#### 4.A'.6 优缺点

**优点**：

1. **翻译链路整体下沉到 Node.js**：TS 解析 + 白名单剪枝 + 字段校验 + Groovy codegen 都在同一 Node.js 进程内完成，
   不再"解析在服务端、Groovy 拼装在服务端"两段式
2. **零新增运行时进程**：复用 Electron Main Process 已持有的 Node.js（不引入 ts-parser.js 常驻进程）
3. **零服务端 Node.js 依赖**：orule-server JVM 端不需要预装 Node.js、typescript npm 包
4. **orule-common jar 体积最优**：完全移除 GraalJS / ts-parser / JSON-RPC 客户端代码
5. **Skill 体系复用**：与 orule-llm-studio 已有 skill 体系共用基础设施（详见 `.agents/skills/` 现有 skills）
6. **实施成本可控**：3~5 人天（skill 框架接入 + RFC-0018/0019 移植 + 联调）
7. **TS 5.x 跟进最及时**：升级 `typescript` npm 包即可；与 RFC-0018 §3.5 白名单解耦
8. **TDD 友好**：skill 内部可用 vitest 单测；联调用 orule-server 的 Testcontainers 集成测试
9. **沙箱强度高**：编译期在用户进程（用户 OS 权限隔离）；运行期 Groovy 沙箱 4 层防护不变

**缺点**：

1. **必须依赖本地 Agent 环境**：用户编辑器必须是 Cursor / OpenCode / orule-llm-studio，
   纯服务端 jar 部署场景需回退到方案 A
2. **RFC-0018/0019 实现需移植**：原 Java 版（WhitelistPruner / FieldValidator / GroovyCodeGen）需重写为 TypeScript
3. **跨编辑器兼容**：skill 框架需在 Cursor / OpenCode / orule-llm-studio 三处都注册（详见 §9 风险表）
4. **RuleArtifact.debugInfo 含 SimpleTS-AST JSON**：略增大 artifact 存储量（~5~20 KB / 规则，可接受）

---

### 方案 A：**回退实现**（服务端自管 Node.js 子进程）

> 2026-09-12 v2 评审升级：原推荐方案 A 降级为"无 Electron / 纯服务端 jar 部署"场景的回退实现。
> 详细设计见 §13.1 附录（保留完整 JSON-RPC 协议 + ts-parser.js 代码 + 沙箱配置）。

A 方案的核心特征：

- orule-server JVM 端通过 `ProcessBuilder` 拉 Node.js 子进程
- 子进程跑 `ts-parser.js`（~80 行 Node.js），require `typescript`
- 双方走 stdin/stdout **JSON-RPC 2.0** 协议
- 沙箱 = 进程隔离（`--disallow-code-generation-from-strings`）

依赖清单（许可证 + 活跃度 + 开源风险）：

| 依赖 | 许可证 | 活跃度 | 开源风险 |
|------|--------|--------|----------|
| Node.js | MIT | 极高 | 无 |
| typescript（npm） | Apache-2.0 | 极高 | 无 |

适用：A' 不可用（用户没用 Cursor/OpenCode）的纯服务端部署场景。

---

### 方案 B：JVM 内嵌 JS 引擎替代品（**保留作为回退**）

候选引擎盘点：

| 引擎 | 许可证 | 活跃度（2026-09） | 评价 |
|------|--------|------------------|------|
| GraalVM JS（Polyglot） | GPL-2.0 with Classpath Exception | 🟢 高（GraalVM 22~25） | 当前方案，问题已知 |
| J2V8（eclipsesource/j2v8） | EPL-1.0 | 🔴 **低**（最后发版 2020，无后续） | 维护停滞，TS 集成需自维护 |
| JDK Nashorn（`jdk.scripting.nashorn`） | GPL-2.0 | ❌ **已弃用**（JDK 15 移除） | 不可用 |
| OpenJDK Nashorn standalone | GPL-2.0 | 🟡 中（社区 fork） | 同 Nashorn，无 Java 11+ 长期路线 |

→ **JVM 内嵌 JS 引擎这条路，社区目前没有"无开源风险 + 高活跃度"的成熟替代品**。
J2V8 不满足 F5（活跃度）；Nashorn 不满足 F5（弃用）；OpenJDK Nashorn 不满足 F5（路线不明）。
GraalJS 在活跃度上仍是最优 JVM 内嵌选项，但本 ADR 的目标是"替换"而非"同类替换"。

---

### 方案 C：ANTLR4 + TypeScript Grammar 自研（**长期路线，本期不做**）

**思路**：用 ANTLR4 把 [`antlr/grammars-v4`](https://github.com/antlr/grammars-v4) 仓库下的
`typescript/TypeScriptLexer.g4` / `typescript/TypeScriptParser.g4` 包装为 Java 解析器，
ParseTree → 简化的 `TsAst` JSON，替换 §3.9 的"GraalJS→JSON"。

#### 4.3.1 关键依赖

| 依赖 | 许可证 | 活跃度（2026-09） |
|------|--------|------------------|
| ANTLR4 Runtime（`org.antlr:antlr4-runtime`） | BSD-3-Clause | 极高（4.13.x 系列） |
| `antlr/grammars-v4` (typescript/) | Apache-2.0 | 🟡 中（社区维护，TS 5.x 跟进滞后） |

#### 4.3.2 优缺点（简版）

**优点**：零外部依赖（纯 JVM）、单 jar 可运行、离线 / 国产化部署最友好
**缺点**：实施成本 5~8 人天；TS 5.x 跟进滞后；并非"免费午餐"——SimpleTSParser 仍需实现白名单剪枝 / DomainMeta 校验

**本期决策**：**仅作为长期路线记录，不实施**（用户决策 2026-09-12：暂不搞）。

---

## 5. 方案对比

| 维度 | **A'：本地 Agent + Skill（推荐）** | A：Node.js 子进程 + JSON-RPC | B：JVM 内嵌 JS | C：ANTLR4 自研（长期） |
|------|-----------------------------------|------------------------------|----------------|------------------------|
| TS 语法支持 | ✅ 5.x 即时可用（官方 typescript 包） | ✅ 同 A' | ✅ 同 A' | 🟡 滞后于官方 |
| **沙箱强度** | ✅ **编译期用户进程 + 运行期 Groovy 沙箱** | 🟡 JVM 沙箱配置（曾翻车） | 🟡 JVM 沙箱配置（曾翻车） | ✅ JVM 进程内（高） |
| 启动开销 | ✅ 0（复用 Electron Node） | 🟡 进程常驻后 ~0；首次 ~200 ms | 🟡 首次 Context 创建 ~300 ms | ✅ < 50 ms |
| 单次解析开销 | < 5 ms（Node 内） | ~5 ms（含 JSON 序列化） | ~3 ms（JVM 内） | < 5 ms |
| **部署依赖** | ✅ **orule-server 无 Node.js** | 🟡 orule-server 需 Node.js 18+ | ✅ 无（GraalJS Polyglot jar） | ✅ 无（纯 JVM） |
| **实施成本** | 🟡 **3~5 人天**（含 skill 框架接入） | ✅ 1~2 人天 | ✅ 0（保留 GraalJS 即可） | 🟡 5~8 人天 |
| 维护成本 | ✅ 低（Skill 框架已成熟） | 🟡 中（JSON-RPC 协议 + 进程管理） | 🟡 中（GraalVM 版本漂移） | 🟡 中（grammar 跟进） |
| **开源风险** | ✅ **零**（MIT / Apache-2.0，无 CLA） | ✅ 同 A' | 🟡 中（GPL-2.0 w/ CPE 边界） | ✅ 极低（ANTLR BSD-3 / grammar Apache-2.0） |
| **活跃度** | ✅ **极高**（Node.js LTS 22/24 + TS 月度） | ✅ 极高 | 🟢 高（GraalVM 22~25） | 🟢 高（ANTLR 4.13+）/ grammar 🟡 中 |
| TDD 友好 | ✅ 高（vitest + 集成 MCP） | ✅ 高（ProcessBuilder + JSON-RPC） | ✅ 高（GraalJS Context） | 🟡 中（grammar → AST 测试路径长） |
| orule-common jar 体积影响 | ✅ **-30 MB**（移除全部解析/编码代码） | 🟢 +30 MB（保留 GraalJS） | 🟢 +30 MB（保留 GraalJS） | ✅ -30 MB |
| 翻译链路整体性 | ✅ **一段式**（Node 内完整完成） | 🟡 分两段（解析 + codegen） | 🟡 分两段（解析 + codegen） | 🟡 分两段 |
| Skill 体系复用 | ✅ **复用 `.agents/skills/` 基础设施** | ❌ 不复用 | ❌ 不复用 | ❌ 不复用 |

---

## 6. 决策

采用 **方案 A'：本地 Agent 环境 + Skill 化** 作为 RFC-0018 + RFC-0019 的替换实现。

### 6.1 选择理由

1. **翻译链路整体下沉到 Node.js**：TS 解析 + 白名单剪枝 + 字段校验 + 类型对齐 + Groovy codegen 在同一 Node.js 进程完成，
   不再"解析在服务端、Groovy 拼装在服务端"两段式；同一 RuleType 上下文内执行，错误位置 / 共享 AST 一气呵成
2. **零服务端 Node.js 依赖**：orule-server JVM 端不需要预装 Node.js / typescripts npm 包；
   部署仍然是单一 jar，运维最简
3. **复用已有 Skill 基础设施**：本仓库 `.agents/skills/` 已有 skill 体系（`interactive-rfc-generation`、
   `rfc-push` 等），新增 `simplets-compile-and-save` skill 直接复用其加载机制
4. **沙箱强度高**：编译期在用户编辑器进程（OS 权限隔离）；运行期 Groovy 沙箱 4 层防护不变
5. **TS 5.x 跟进最及时**：升级 `typescript` npm 包即可
6. **实施成本可控**：3~5 人天（skill 框架接入 + RFC-0018/0019 移植 + 联调）

### 6.2 配套决策

- **方案 A（Node.js 子进程 + JSON-RPC）** 保留为"无 Electron / 纯服务端 jar 部署"场景的回退实现（详细见 §13.1）
- **方案 B（GraalJS 退化）** 通过 `@ConditionalOnProperty` 切换实现，作为"客户 JDK-only 容器"场景的二次回退
- **方案 C（ANTLR4 自研）** 作为"未来去 Node.js 依赖"的长期路线，**仅做记录，本期不实施**

---

## 7. 实施步骤

```
1. 在 .agents/skills/ 新增 simplets-compile-and-save skill
   - package.json: 依赖 typescript 5.5.x + MCP SDK 1.x
   - src/ts-parser.ts: 移植 RFC-0018 §3.9 的 ts.createSourceFile 调用
   - src/pruner.ts: 移植 RFC-0018 §3.5 WhitelistPruner
   - src/field-validator.ts: 移植 RFC-0018 §3.6 FieldValidator
   - src/type-checker.ts: 移植 RFC-0018 §3.6 TypeChecker
   - src/lvalue-checker.ts: 移植 RFC-0018 §3.7 LValueChecker
   - src/groovy-codegen.ts: 移植 RFC-0019 GroovyCodeGen
   - src/mcp-client.ts: 调 orule-server REST POST /api/v1/rules/{id}/artifact
2. 新增 orule-server REST 端点
   - POST /api/v1/rules/{ruleId}/artifact（~30 行 Java）
   - 鉴权 SCOPE_rule:write + Groovy 长度 ≤ 100KB 校验
   - 落 RuleArtifact + ArtifactStorage
3. orule-common 清理
   - 移除 com.orule.dsl.ts.TsAstParser（不再需要）
   - 移除 com.orule.dsl.codegen.* 包
   - 移除 RFC-0018 §3.10 SimpleTSWhitelist 中服务端侧的引用
4. RFC 修订
   - RFC-0018 §3.2 入口保留 parse(source, RuleType) 形状，但标注"实现位置：skill/.agents/skills/simplets-compile-and-save"
   - RFC-0019 整篇修订为"在 Node.js skill 中实现，orule-server 只接收产物"
   - 新增 RFC-0018-bis-rev2 修订日志说明本次迁移
5. 单元测试（vitest）
   - pruner / field-validator / type-checker / lvalue-checker 各 20+ 用例
   - groovy-codegen 30+ 用例（含完整 VIP 示例）
   - SimpleTSWhitelist 在 JS 侧重建（与 Java 侧语义一致）
6. 集成测试
   - 完整 SimpleTS 源码 → skill → Groovy 源码 → orule-server 接收 → RuleArtifact 落库
   - Testcontainers 跑 MySQL + MinIO 模拟 orule-server 全栈
7. 文档
   - .agents/skills/simplets-compile-and-save/SKILL.md（含调用示例）
   - docs/03-deployment/ 增补"Node.js 依赖说明"（仅编辑器侧，orule-server 无）
   - docs/dsl/SimpleTS.md §13 实施备注更新
```

---

## 8. 影响

- **新增外部依赖（编辑器侧）**：Node.js 18+（由 Cursor / OpenCode / orule-llm-studio 提供）+ typescript npm 包
- **移除依赖**：orule-server 完全不引入 Node.js / GraalJS / typescript
- **新增 Skill**：`.agents/skills/simplets-compile-and-save`
- **不影响**：RFC-0018 §3.5 ~ §3.10 SimpleTS-AST / WhitelistPruner / FieldValidator / SimpleTSWhitelist 算法本身（仅实现位置变化）；
  RFC-0020 Groovy 沙箱（运行期）；ADR-009 星型架构
- **orule-common jar 体积**：方案 A' 实施后 **-30 MB**

---

## 9. 风险与护栏

| 风险 | 等级 | 缓解 |
|------|------|------|
| **跨编辑器兼容**（Cursor / OpenCode / orule-llm-studio 三处 skill 注册） | 🟡 中 | skill 框架基于 MCP 协议（Anthropic 标准），三家编辑器均原生支持；CI 矩阵三平台联动测试 |
| **RFC-0018 / RFC-0019 实现需从 Java 移植到 TypeScript** | 🟡 中 | 算法层 1:1 移植（WhitelistPruner / FieldValidator / GroovyCodeGen 均为确定算法，无平台差异）；TDD 先写测试再移植，保证行为一致 |
| **必须依赖本地 Agent 环境**（纯服务端 jar 部署无法使用 A'） | 🟡 中 | 保留方案 A 作为回退；通过 `@ConditionalOnProperty(orule.ts.parser=nodejs-process)` 切换 |
| **RuleArtifact.debugInfo 增大存储**（SimpleTS-AST JSON ~5~20 KB / 规则） | 🟢 低 | 与 RFC-0017 ArtifactStorage 设计一致；可配置是否落 debugInfo（默认 true） |
| **TypeScript skill 在编辑器 OOM / 崩溃** | 🟢 低 | skill 进程生命周期由编辑器管理；失败可由编辑器重启恢复；落库已带 SHA-256 + sourceHash，幂等 |
| **TLS / 鉴权穿透**：skill 调 orule-server REST 需 access token | 🟡 中 | 通过 OAuth2 client credentials 流程；token 由 orule-llm-studio 持有（已在 RFC-0026 设计范围内） |
| **TS 5.x 语法增量** | 🟢 低 | 升级 `typescript` npm 包即可；与 RFC-0018 §3.5 白名单解耦（新语法默认被白名单拒绝） |
| **SimpleTSWhitelist 单一来源分裂风险**（JS 侧重建一份） | 🟡 中 | 同 RFC-0018 §3.10 约束；CI 断言"JS 侧与 Java 侧方法签名集合 1:1 对齐"；新增方法需走 RFC |

---

## 10. 决策日志

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-12 | v1：替代 GraalJS，选 **方案 A（Node.js 子进程 + JSON-RPC）** | GraalJS 在体积 / 启动 / 沙箱配置面风险已识别；Node.js + TypeScript 活跃度最高、开源零风险、进程隔离天然沙箱 |
| 2026-09-12 | 保留 GraalJS 作为 `@ConditionalOnProperty` 退化实现（方案 B） | 部分环境无法安装 Node.js；保留实现比临时重构更稳 |
| 2026-09-12 | 写入技术债：**ANTLR4 自研**（方案 C）作为长期路线 | "未来去 Node.js 依赖"的可选路径；本期实施成本 5~8 人天，仅记录 |
| **2026-09-12** | **v2 升级：选 **方案 A'（本地 Agent 环境 + Skill 化）** 取代方案 A** | 识别出 orule-llm-studio 本地已有 Node.js + Cursor/OpenCode Agent；新方案能复用 Skill 基础设施，把 NL→SimpleTS→Groovy 整条链路下沉到编辑器进程，orule-server 完全不引入 Node.js 依赖 |
| 2026-09-12 | 方案 A 降级为"纯服务端 jar 部署"回退实现 | 仍有无 Electron 的部署场景 |
| 2026-09-12 | 方案 C（ANTLR4）确认本期不实施 | 用户决策"暂不搞"；作为长期路线记录 |

---

## 11. 关联

- **RFC**：[RFC-0018-SimpleTS解析器.md §3.9](../../rfcs/RFC-0018-SimpleTS解析器.md)、[RFC-0019-SimpleTS转Groovy代码生成器.md](../../rfcs/RFC-0019-SimpleTS转Groovy代码生成器.md)
- **ADR**：ADR-003（中间态 DSL）/ ADR-006（SimpleTS 源语言）/ ADR-009（SimpleTS 星型架构）
- **TDD 阻塞**：[tmpdocs/TDD推进RFC0018-20-待确认问题.md](../../../tmpdocs/TDD推进RFC0018-20-待确认问题.md)（D1：模块归属 / GraalJS 依赖）
- **Skill 体系**：[`.agents/skills/`](../../../.agents/skills/)（interactive-rfc-generation、rfc-push 等已有 skill）

---

## 12. 用户原话摘要

> v2 升级触发用户原话：
>
> *"如果用方案一里面本地 Node.js 进程来做的话，我们可不可以这么做？因为我们本地本身就要写 skill，
> 用本地 Agent 环境来做自然语言到 TS、再到 Java 语言 Groovy 的翻译。那我们能不能再新增一个 skill，
> 把当前自然语言生成 TS、再翻译成 Groovy 的过程都放到本地的 skill 里用 Node.js 来做？
> 这样是不是简单一点？最后直接调我们本地的 MCP 接口把它存到服务器上，是不是就完事了？
> 至于方案 C，先作为长期方案，暂时先不搞。"*

---

## 13. 附录

### 13.1 方案 A 详细设计（回退实现：Node.js 子进程 + JSON-RPC）

> v2 评审升级为回退实现，但完整设计保留作为"无 Electron 部署"的备选。

#### 13.1.1 关键依赖（许可证 + 活跃度核查）

| 依赖 | 版本（2026-09） | 许可证 | 活跃度 | 开源风险 |
|------|----------------|--------|--------|----------|
| **Node.js** | LTS 22.x / 24.x 双轨 | MIT | 极高（OpenJS Foundation 顶级项目，月度发版） | **无**（MIT 宽松，无 CLA） |
| **typescript**（npm） | 5.5.x 系列 | Apache-2.0 | 极高（Microsoft 主力维护，月度发版） | **无**（Apache-2.0 宽松） |

#### 13.1.2 关键实现：`ts-parser.js`

```javascript
// ts-parser.js  (Node.js, ~80 行)
const ts = require('typescript');
const pending = new Map();
let nextId = 1;

process.stdin.setEncoding('utf8');
let buf = '';
process.stdin.on('data', chunk => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, nl);
    buf = buf.slice(nl + 1);
    if (!line.trim()) continue;
    handle(JSON.parse(line));
  }
});

function handle(req) {
  try {
    if (req.method === 'parse') {
      const sf = ts.createSourceFile('rule.ts', req.params.source,
        ts.ScriptTarget.ES2020, /*setParentNodes*/ true);
      respond(req.id, { ast: serialize(sf) });
    } else if (req.method === 'ping') {
      respond(req.id, { pong: Date.now() });
    } else {
      respond(req.id, undefined, { code: -32601, message: 'Method not found' });
    }
  } catch (e) {
    respond(req.id, undefined, { code: -32000, message: e.message });
  }
}

function respond(id, result, error) {
  const msg = { jsonrpc: '2.0', id };
  if (error) msg.error = error; else msg.result = result;
  process.stdout.write(JSON.stringify(msg) + '\n');
}

function serialize(node) {
  // 递归遍历 TS AST → JSON（包含 kind/line/column + 子节点）
  // 与 RFC-0018 §3.9 `parseToTsAst` 输出的 JSON 结构对齐
  // ... (实现略)
}
```

#### 13.1.3 关键实现：`TsAstParser.java`

```java
// packages/orule-common/src/main/java/com/orule/dsl/TsAstParser.java
public class TsAstParser implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final Map<Long, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(
        r -> { Thread t = new Thread(r, "ts-parser-reader"); t.setDaemon(true); return t; });
    private final AtomicLong nextId = new AtomicLong(1);

    public TsAstParser(String nodeBin, String scriptPath) throws IOException {
        this.process = new ProcessBuilder(nodeBin, scriptPath)
            .redirectErrorStream(false).start();
        this.stdin  = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
        reader.submit(this::readLoop);
    }

    public JsonNode parse(String source) {
        long id = nextId.getAndIncrement();
        CompletableFuture<String> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            synchronized (stdin) {
                stdin.write("{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"method\":\"parse\",\"params\":{\"source\":"
                    + JsonString.escape(source) + "}}\n");
                stdin.flush();
            }
            String resp = f.get(5, TimeUnit.SECONDS);
            return new ObjectMapper().readTree(resp).get("result").get("ast");
        } catch (Exception e) {
            pending.remove(id);
            throw new TsParseException("TS 解析失败: " + e.getMessage(), e);
        }
    }

    private void readLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                JsonNode r = new ObjectMapper().readTree(line);
                long id = r.get("id").asLong();
                CompletableFuture<String> f = pending.remove(id);
                if (f != null) {
                    if (r.has("error")) f.completeExceptionally(
                        new TsParseException(r.get("error").get("message").asText()));
                    else f.complete(r.get("result").toString());
                }
            }
            pending.values().forEach(f -> f.completeExceptionally(
                new TsParseException("ts-parser 进程退出")));
        } catch (Exception e) {
            pending.values().forEach(f -> f.completeExceptionally(e));
        }
    }

    @Override public void close() {
        process.destroy();
        reader.shutdownNow();
    }
}
```

#### 13.1.4 沙箱

进程隔离天然就是沙箱：

- 启动：`node --disallow-code-generation-from-strings --no-deprecation ts-parser.js`
- stdin/stdout 仅 JSON-RPC，无任何文件系统入口
- 不挂载宿主机目录；通过 Docker 镜像预装 node + tsc

#### 13.1.5 实施步骤（A 方案，作为回退）

```
1. 在 packages/orule-common/src/main/resources/ 添加 ts-parser.js
2. orule-server/pom.xml 增加 frontend-maven-plugin 或在 Docker 镜像预装 node + tsc
3. 重写 packages/orule-common/src/main/java/com/orule/dsl/TsAstParser.java
   - 构造时 ProcessBuilder 启 ts-parser.js
   - stdin 写 JSON-RPC 请求，stdout 读 JSON-RPC 响应
   - 实现心跳 ping/pong（每 30 s）+ 崩溃自愈（连续 3 次失败 → 重建进程）
4. 单元测试：JSON-RPC 协议往返 / 心跳 / 崩溃恢复 / 沙箱（ts-parser.js 拒绝 fs / net）
5. 集成测试：完整 SimpleTS VIP 示例解析成功
6. 文档：docs/03-deployment/ 增补"Node.js 运行时依赖说明"
```

#### 13.1.6 风险（A 方案）

| 风险 | 等级 | 缓解 |
|------|------|------|
| 部署环境无 Node.js（受限 JDK-only 容器） | 🟡 中 | Docker 镜像预装；CI/CD 校验 `node -v`；退化实现 `@ConditionalOnProperty(orule.ts.parser=graaljs)` |
| Node.js 进程崩溃 / OOM | 🟡 中 | heartbeat ping/pong 每 30 s；连续 3 次失败 → 重建进程 |
| stdin/stdout 缓冲区阻塞 | 🟢 低 | `BufferedWriter` + 显式 `flush()`；输入长度上限 100 KB |
| 跨平台 IO 差异（Windows CRLF / Linux LF） | 🟢 低 | 协议层强制 `\n` 分隔 + UTF-8 显式编码；CI 同时跑 Windows / Linux 矩阵 |
| TS 5.x 语法增量 | 🟢 低 | 升级 `typescript` npm 包即可 |
| 进程常驻内存占用 | 🟢 低 | Node.js 常驻 ~50~80 MB |
| 多 orule-server 实例并发 | 🟢 低 | 每实例独立持有 1 个 Node.js 进程 |
| typescript npm 包供应链风险 | 🟢 低 | 锁定具体版本（package-lock.json） |
