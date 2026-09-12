# RFC-0019: SimpleTS → Groovy 代码生成器

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：4d · **阶段**：S3

---

## 1. 摘要

依据 `docs/dsl/SimpleTS.md §10.2` 的 Groovy 路径，把 SimpleTS-AST 转换为 Groovy 源码，产物视觉与 SimpleTS 源码一致；落库到 RuleVersion.groovy_source + RuleArtifact。

---

## 2. 动机

- SimpleTS-AST 需要一个落地目标（依据 ADR-009 星型架构，Groovy 是第一目标）
- Groovy 沙箱 + Groovy 编译产物是 orule-runtime 的执行入口（依据 `06-运行视图 §6.3.3`）
- "视觉一致"原则降低业务/工程师心智负担（依据 `docs/dsl/SimpleTS.md §10.2`）

---

## 3. 详细设计

### 3.1 模块位置

```
packages/orule-common/src/main/java/com/orule/dsl/codegen/
├── GroovyCodeGen.java        # 入口
├── GroovyWriter.java         # 代码写入器（带缩进）
└── CodeGenContext.java       # 上下文（缩进级别、字段映射）
```

### 3.2 入口

```java
package com.orule.dsl.codegen;

import com.orule.dsl.ast.*;
import com.orule.common.storage.ArtifactStorage;

/**
 * SimpleTS-AST → Groovy 代码生成器。
 * 依据 docs/dsl/SimpleTS.md §10.2。
 */
public class GroovyCodeGen {

    private final ArtifactStorage storage;

    public GroovyCodeGen(ArtifactStorage storage) {
        this.storage = storage;
    }

    /**
     * 编译入口。
     * @param ast SimpleTS-AST（RFC-0018 产物）
     * @param ruleCode 规则标识
     * @param version  版本号
     * @return Groovy 源码
     */
    public String generate(Program ast, String ruleCode, int version) {
        StringBuilder sb = new StringBuilder();
        CodeGenContext ctx = new CodeGenContext(sb);
        
        // 头部注释
        sb.append("// Auto-generated from SimpleTS by orule GroovyCodeGen\n");
        sb.append("// Rule: ").append(ruleCode).append("\n");
        sb.append("// Version: ").append(version).append("\n\n");
        
        // 主体
        for (Node stmt : ast.body()) {
            generateStatement(stmt, ctx);
            sb.append("\n");
        }
        
        return sb.toString();
    }

    private void generateStatement(Node stmt, CodeGenContext ctx) {
        if (stmt instanceof IfStmt ifStmt) {
            generateIf(ifStmt, ctx);
        } else if (stmt instanceof ForStmt forStmt) {
            generateFor(forStmt, ctx);
        } else if (stmt instanceof DeclareStmt decl) {
            generateDeclare(decl, ctx);
        } else if (stmt instanceof AssignStmt assign) {
            generateAssign(assign, ctx);
        } else if (stmt instanceof ExprStmt exprStmt) {
            generateExpr(exprStmt.expr(), ctx);
            ctx.write(";\n");
        } else if (stmt instanceof Block block) {
            generateBlock(block, ctx);
        }
    }

    private void generateIf(IfStmt stmt, CodeGenContext ctx) {
        for (int i = 0; i < stmt.branches().size(); i++) {
            IfStmt.Branch branch = stmt.branches().get(i);
            if (i == 0) {
                ctx.write("if (");
            } else {
                ctx.write("else if (");
            }
            generateExpr(branch.test(), ctx);
            ctx.write(") ");
            generateStatement(branch.body(), ctx);
        }
        if (stmt.elseBody() != null) {
            ctx.write("else ");
            generateStatement(stmt.elseBody(), ctx);
        }
    }

    private void generateAssign(AssignStmt stmt, CodeGenContext ctx) {
        // 左值：MemberAccess
        generateMemberAccess(stmt.target(), ctx);
        ctx.write(" = ");
        generateExpr(stmt.value(), ctx);
        ctx.write("\n");
    }

    private void generateExpr(Expr expr, CodeGenContext ctx) {
        if (expr instanceof BinaryExpr binary) {
            ctx.write("(");
            generateExpr(binary.left(), ctx);
            ctx.write(" ").append(binary.op()).append(" ");
            generateExpr(binary.right(), ctx);
            ctx.write(")");
        } else if (expr instanceof UnaryExpr unary) {
            ctx.write("(").append(unary.op());
            generateExpr(unary.arg(), ctx);
            ctx.write(")");
        } else if (expr instanceof Literal literal) {
            generateLiteral(literal, ctx);
        } else if (expr instanceof MemberAccess member) {
            generateMemberAccess(member, ctx);
        } else if (expr instanceof CallExpr call) {
            generateCall(call, ctx);
        } else if (expr instanceof EnumRef enumRef) {
            // CustomerTier.VIP → 直接写出 EnumId.Value
            ctx.write(enumRef.enumId()).append(".").append(enumRef.value());
        }
    }

    private void generateMemberAccess(MemberAccess ma, CodeGenContext ctx) {
        ctx.write(ma.root());
        for (String seg : ma.path()) {
            ctx.write(".").append(seg);
        }
    }

    private void generateCall(CallExpr call, CodeGenContext ctx) {
        generateMemberAccess(call.callee(), ctx);
        ctx.write("(");
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) ctx.write(", ");
            generateExpr(call.args().get(i), ctx);
        }
        ctx.write(")");
    }

    private void generateLiteral(Literal literal, CodeGenContext ctx) {
        Object v = literal.value();
        if (v instanceof String s) {
            ctx.write("\"").append(escapeString(s)).append("\"");
        } else if (v instanceof Boolean b) {
            ctx.write(b ? "true" : "false");
        } else {
            ctx.write(v.toString());
        }
    }

    private String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
```

### 3.3 关键示例

输入 SimpleTS-AST（VIP 满减）：

```
Program {
    IfStmt {
        branches: [
            Branch {
                test: BinaryExpr(&&, 
                    BinaryExpr(==, MemberAccess(customer, [tier]), EnumRef(CustomerTier, VIP)),
                    BinaryExpr(>=, MemberAccess(order, [totalAmount]), Literal(200))
                ),
                body: Block {
                    AssignStmt(MemberAccess(order, [discount]), Literal(30))
                }
            }
        ]
    }
}
```

输出 Groovy：

```groovy
// Auto-generated from SimpleTS by orule GroovyCodeGen
// Rule: ORDER_DISCOUNT
// Version: 1

if ((customer.tier == CustomerTier.VIP) && (order.totalAmount >= 200)) {
    order.discount = 30
}
```

> **视觉一致性验证**：与 SimpleTS 源码几乎一致，仅多了括号（Groovy 语法要求）。

### 3.4 EnumRef 处理（特例）

SimpleTS 源码：`customer.tier == CustomerTier.VIP`

**RFC-0031 修订**：枚举定义已内联到 `AttributeField.type.kind === 'enum'` 的 `EnumType.values` 中。
DomainMeta 顶层已无 `enums` 字段；提取枚举值需要遍历所有 entity 的所有 field。

Groovy enum 语法仅支持 `code` 列表，无法携带 `label` / `sortOrder`。
**MVP 策略**：仅输出 `code` 到 Groovy enum 定义；`label` / `sortOrder` 在 UI 渲染时通过 RFC-0015
元数据 API 查询。

**方案 A**（推荐）：自动生成 Enum 包装类（每个 Rule 一个内部 Enum 类）

```groovy
class RuleGroovy {
    enum CustomerTier { VIP, GOLD, SILVER, BRONZE }

    static Object execute(Map context) {
        def customer = context.customer
        def order = context.order

        if ((customer.tier == CustomerTier.VIP) && (order.totalAmount >= 200)) {
            order.discount = 30
        }

        return context
    }
}
```

> **优势**：枚举类型在 Groovy 中可被沙箱校验（未知枚举值编译错误）。
> **劣势**：每条规则带自己的 Enum 定义，无法共享（可接受）。

**EnumType.values → Groovy enum 转换**：

```java
/**
 * 提取 Groovy enum 定义（仅取 code）。
 * 多个 EnumType 重名时按 enumCode 去重，取首个（DomainMeta 应保证不重名）。
 */
private String generateEnumBlock(List<DomainMeta.EntityDef> entities) {
    Set<String> seen = new HashSet<>();
    StringBuilder sb = new StringBuilder();
    for (DomainMeta.EntityDef entity : entities) {
        for (DomainMeta.EntityField field : entity.fields()) {
            if (field.type() instanceof EnumType et) {
                if (seen.add(et.enumCode())) {
                    sb.append("enum ").append(et.enumCode()).append(" { ");
                    sb.append(et.values().stream()
                        .map(EnumValue::code)
                        .collect(Collectors.joining(", ")));
                    sb.append(" }\n\n");
                }
            }
        }
    }
    return sb.toString();
}
```

### 3.5 完整包装模板

> **修订**：原 §3.5 中 `generateStatementWithIndent(stmt, indent)` 重新实现一套缩进
> 与 `GroovyWriter` 重复。本版本统一用 `GroovyWriter` 的 indent 管理（enter/exit），
> 不再硬编码 4 空格字符串。

```java
public class GroovyCodeGen {

    /**
     * 生成完整可执行的 Groovy 脚本（含 Enum 定义 + execute 方法 + 输入参数定义）。
     *
     * @param ast      SimpleTS-AST（RFC-0018 产物）
     * @param meta     领域元数据（RFC-0031 同步）
     * @param ruleCode 规则标识
     * @param version  版本号
     * @return Groovy 源码（视觉与 SimpleTS 一致）
     */
    public String generateExecutable(Program ast, DomainMeta meta, String ruleCode, int version) {
        GroovyWriter w = new GroovyWriter();

        // 1. 头注释
        w.writeLine("// Auto-generated from SimpleTS by orule GroovyCodeGen");
        w.writeLine("// Rule: " + ruleCode);
        w.writeLine("// Version: " + version);
        w.writeLine("// DO NOT EDIT - 修改 SimpleTS 源码后重新编译");
        w.writeLine("");

        // 2. Enum 定义（从 meta 提取，遍历所有 entity 的所有 field）
        for (DomainMeta.EntityDef entity : meta.entities()) {
            for (DomainMeta.EntityField field : entity.fields()) {
                if (field.type() instanceof EnumType et) {
                    w.writeLine("enum " + et.enumCode() + " { "
                        + et.values().stream()
                            .map(EnumValue::code)
                            .collect(Collectors.joining(", "))
                        + " }");
                }
            }
        }
        w.writeLine("");

        // 3. execute 方法
        w.writeLine("def execute(Map context) {");
        w.enterIndent();
        // 把 context.* 解构到局部变量（def customer = context.customer）
        for (DomainMeta.ContextVar ctx : meta.context()) {
            w.writeLine("def " + ctx.name() + " = context." + ctx.name());
        }
        w.writeLine("");

        // 4. 主体（用 w.writeLine 走统一的 indent 管理）
        for (Node stmt : ast.body()) {
            generateStatement(stmt, w);  // 这里 w 已经处于 +1 indent
            w.writeLine("");
        }

        // 5. 返回 context
        w.writeLine("return context");
        w.exitIndent();
        w.writeLine("}");

        return w.toString();
    }

    // generateStatement / generateIf / generateAssign / generateExpr /
    // generateMemberAccess / generateCall / generateLiteral 在这里用 GroovyWriter：
    // - 用 w.write("...") / w.writeLine("...") 输出片段；
    // - Block / If / For 进入时调 w.enterIndent()，退出前调 w.exitIndent()；
    // - 不再传 indent 字符串，避免双 indent 系统。
}
```

### 3.6 落库服务（MCP 端点）

> **RFC-0019-bis 修订（2026-09-12 晚）**：依据 [ADR-012-Aprime-本地Skill编译与MCPLangLib库.md](../../adr/ADR-012-Aprime-本地Skill编译与MCPLangLib库.md)，
> SimpleTS → Groovy 编译已迁出 orule-server。本节原 `CompileService`（服务端编译）作废，
> 替换为 `GroovySourceIntakeService`（接收本地 Skill 编译产物落库）。
>
> §3.1 ~ §3.5 的 `GroovyCodeGen` / `GroovyWriter` / `CodeGenContext` **作为 Skill 实现规范保留**（详见 RFC-0018 §10）；
> 本仓库不实现。

#### 3.6.1 模块位置

```
packages/orule-server/src/main/java/com/orule/server/
├── controller/
│   └── GroovySourceIntakeController.java    # MCP 端点入口
├── service/
│   └── GroovySourceIntakeService.java       # 落库业务逻辑
└── dto/
    ├── GroovySourceIntakeRequest.java       # 入参
    └── GroovySourceIntakeResponse.java      # 出参
```

#### 3.6.2 入参 DTO

```java
package com.orule.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * MCP 工具 orule.rule.publishCompiledGroovy 入参。
 * 由 orule-llm-studio Skill #2 simplets-to-groovy 产出。
 *
 * <p>字段稳定原则：新增字段必须可空；删除字段需走 RFC。
 */
public record GroovySourceIntakeRequest(

    /** 编译产物 Groovy 源码。失败时为空串。最大 100 KB（与 RFC-0020 §3.3 MAX_SCRIPT_LENGTH 一致）。 */
    @NotBlank @Size(max = 100_000)
    String groovySource,

    /** 编译失败时的错误信息模板。成功时为 null。 */
    String compileLog,

    /** sha256(groovySource)。服务端校验一致性。 */
    @NotBlank
    String sha256,

    /** Skill 端编译耗时（毫秒），审计用。 */
    @Min(0)
    long durationMs
) {}
```

#### 3.6.3 出参 DTO

```java
package com.orule.server.dto;

import java.time.Instant;

/**
 * MCP 工具 orule.rule.publishCompiledGroovy 出参。
 */
public record GroovySourceIntakeResponse(

    /** RuleVersion 主键（与入参路径一致） */
    String ruleVersionId,

    /** RuleArtifact 主键（UUID，新生成） */
    String artifactId,

    /** SUCCESS / FAILED */
    String compileStatus,

    /** 服务端落库时间 */
    Instant storedAt
) {}
```

#### 3.6.4 服务实现

```java
package com.orule.server.service;

import com.orule.common.storage.ArtifactStorage;
import com.orule.common.storage.UploadResult;
import com.orule.server.dto.GroovySourceIntakeRequest;
import com.orule.server.dto.GroovySourceIntakeResponse;
import com.orule.server.entity.RuleVersion;
import com.orule.server.entity.RuleArtifact;
import com.orule.server.entity.CompileStatus;
import com.orule.server.repository.RuleVersionRepository;
import com.orule.server.repository.RuleArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 接收本地 Skill 编译产物（Groovy 源码），落 RuleVersion + RuleArtifact + ArtifactStorage。
 *
 * <p><b>不做</b>任何 TS / SimpleTS / Groovy 校验 — 仅做 SHA256 一致性校验 + 字段非空校验。
 * 编译期校验由 orule-llm-studio Skill #2 simplets-to-groovy 负责。
 *
 * <p>运行期校验由 orule-runtime Groovy 沙箱（RFC-0020 SecureASTCustomizer）兜底。
 */
@Service
@RequiredArgsConstructor
public class GroovySourceIntakeService {

    private final RuleVersionRepository versionRepo;
    private final RuleArtifactRepository artifactRepo;
    private final ArtifactStorage storage;

    @Transactional
    public GroovySourceIntakeResponse intake(String ruleVersionId, GroovySourceIntakeRequest req) {
        // 1. 查 RuleVersion
        RuleVersion version = versionRepo.findById(ruleVersionId)
            .orElseThrow(() -> new NotFoundException("RuleVersion", ruleVersionId));

        // 2. SHA256 校验
        String actual = sha256Hex(req.groovySource());
        if (!actual.equalsIgnoreCase(req.sha256())) {
            throw new IllegalArgumentException(
                "sha256 mismatch: declared=" + req.sha256() + " actual=" + actual);
        }

        // 3. 判定状态（compileLog 为空 = 成功）
        boolean success = req.compileLog() == null || req.compileLog().isBlank();
        CompileStatus status = success ? CompileStatus.SUCCESS : CompileStatus.FAILED;

        // 4. 成功时：落 RuleVersion.groovy_source（失败保留旧值）
        if (success) {
            version.setGroovySource(req.groovySource());
            versionRepo.save(version);
        }

        // 5. 上传 ArtifactStorage（仅成功）
        UploadResult upload = success
            ? storage.upload(
                String.format("rules/%s/v%d.groovy",
                    version.getRule().getCode(), version.getVersion()),
                req.groovySource().getBytes(StandardCharsets.UTF_8))
            : UploadResult.empty();

        // 6. 落 RuleArtifact（成功 / 失败均记录）
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

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

#### 3.6.5 控制器（MCP 端点）

```java
package com.orule.server.controller;

import com.orule.server.dto.GroovySourceIntakeRequest;
import com.orule.server.dto.GroovySourceIntakeResponse;
import com.orule.server.service.GroovySourceIntakeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收本地 Skill 编译产物的 MCP 端点。
 *
 * <p>MCP 工具名：{@code orule.rule.publishCompiledGroovy}
 * <p>调用方：orule-llm-studio Skill #2 simplets-to-groovy
 *
 * <p><b>替代</b> RFC-0019 §3.6 原 CompileService（服务端编译）。
 * 本控制器不做任何 DSL 校验。
 */
@RestController
@RequestMapping("/mcp/tools")
@RequiredArgsConstructor
public class GroovySourceIntakeController {

    private final GroovySourceIntakeService intakeService;

    @PostMapping("/orule.rule.publishCompiledGroovy")
    public GroovySourceIntakeResponse publishCompiledGroovy(
        @PathVariable(required = false) String placeholder,    // MCP 协议兼容占位
        @Valid @RequestBody GroovySourceIntakeRequest req
    ) {
        // 实际路径：/mcp/tools/orule.rule.publishCompiledGroovy?ruleVersionId=xxx
        // req.ruleVersionId 通过 header 或 query 传入（MCP 协议约定）
        return intakeService.intake(extractRuleVersionId(req), req);
    }
}
```

> **MCP 协议说明**：MCP 工具调用约定把 `ruleVersionId` 作为工具调用参数（query string 或 MCP envelope params），而非 URL path。
> 实际实现按 MCP 框架（Spring AI MCP Server / 官方 MCP Java SDK）的入参约定对齐，本 RFC 不固化。

---

### 3.7 REST API

```
# MCP 工具（编辑器 Skill 调用）
POST /mcp/tools/orule.rule.publishCompiledGroovy    # 落库 + 上传 Artifact（取代原 /compile）

# 只读端点（保留）
GET  /api/v1/rule-versions/{id}/artifact            # 查看编译产物
GET  /api/v1/rule-versions/{id}/compile-log         # 查看编译日志（失败时）

# 【已废弃】原 /compile 端点
# POST /api/v1/rule-versions/{id}/compile           # DEPRECATED：A' 之后不再接受服务端编译请求
```

#### 3.7.1 端点契约（完整）

```http
POST /mcp/tools/orule.rule.publishCompiledGroovy?ruleVersionId=rv-001
Authorization: Bearer <mcp_token>
Content-Type: application/json

{
  "groovySource": "def execute(Map context) { ... }",
  "sha256": "abc123...",
  "compileLog": null,
  "durationMs": 42
}

→ 200 OK
{
  "ruleVersionId": "rv-001",
  "artifactId": "ra-001",
  "compileStatus": "SUCCESS",
  "storedAt": "2026-09-12T20:30:00Z"
}
```

```http
POST /mcp/tools/orule.rule.publishCompiledGroovy?ruleVersionId=rv-002
Authorization: Bearer <mcp_token>
Content-Type: application/json

{
  "groovySource": "",
  "sha256": "",
  "compileLog": "TSS 编译失败:\n\n  ✗ 第 3 行 第 5 列: 未声明的标识符 'invoice'\n  ✗ 第 7 行 第 12 列: 对象 order 上不存在属性 'discountX'\n",
  "durationMs": 35
}

→ 200 OK
{
  "ruleVersionId": "rv-002",
  "artifactId": "ra-002",
  "compileStatus": "FAILED",
  "storedAt": "2026-09-12T20:31:15Z"
}
```

---

## 4. 影响面

- **删除**：`com.orule.dsl.codegen` 包（GroovyCodeGen / GroovyWriter / CodeGenContext）+ `CompileService`
- **新增**（orule-server）：`GroovySourceIntakeController` + `GroovySourceIntakeService` + `GroovySourceIntakeRequest/Response`（共 4 个文件）
- **新增**（orule-llm-studio）：Skill #2 `simplets-to-groovy`（含 §3.1 ~ §3.5 的全部实现，作为本 RFC 规范的落地）
- **变更**：原 `POST /api/v1/rule-versions/{id}/compile` 端点标记 DEPRECATED（保留但不接受服务端编译请求）
- **新增 MCP 端点**：`POST /mcp/tools/orule.rule.publishCompiledGroovy`
- **数据库 schema 不变**（依赖 RFC-0014 已建表）
- **不涉及**：orule-runtime（Groovy 沙箱 RFC-0020 与本 RFC 无关）

---

## 5. 测试计划

### 5.1 orule-server 侧（GroovySourceIntakeService）

| 测试 | 方式 |
|------|------|
| 入参校验：groovySource 缺失 / 空 | 400 Bad Request |
| 入参校验：groovySource 超过 100 KB | 400 Bad Request |
| 入参校验：sha256 缺失 | 400 Bad Request |
| SHA256 mismatch | 422 Unprocessable Entity |
| **成功落库** | RuleVersion.groovy_source 写入；RuleArtifact.compileStatus=SUCCESS；Artifact 上传成功 |
| **失败落库** | RuleVersion.groovy_source 保留旧值；RuleArtifact.compileStatus=FAILED；Artifact 不上传 |
| RuleVersion 不存在 | 404 Not Found |

### 5.2 orule-server 侧（GroovySourceIntakeController）

| 测试 | 方式 |
|------|------|
| MCP 工具调用成功 | 200 OK + GroovySourceIntakeResponse |
| MCP 工具调用缺 ruleVersionId | 400 Bad Request |
| MCP 鉴权失败 | 401 Unauthorized |

### 5.3 orule-llm-studio Skill #2 侧（覆盖原 §3.1 ~ §3.5 全部 case）

| 测试 | 方式 |
|------|------|
| **单元测试：节点级 codegen** | 每个 AST 节点类型生成（vitest） |
| **单元测试：字符串转义** | `"` `\` `\n` 正确转义 |
| **集成测试：VIP 满减** | SimpleTS→Groovy 完整链路（含 sha256 计算） |
| **集成测试：枚举** | EnumRef 正确生成 enum 类 |
| **集成测试：编译失败** | SimpleTS 语法错误 → 输出 compileLog + 空 groovySource |
| **视觉一致性** | Groovy 与 SimpleTS 视觉一致 |
| **MCP 落库契约** | 模拟 MCP server，断言 POST 入参字段稳定 |
| 性能 | Skill 编译 < 50ms |

---

## 6. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| Groovy 与 TS 语法差异（括号、分号） | 🟢 低 | Skill 端 vitest 测试覆盖视觉一致性 |
| Enum 包装类爆炸 | 🟢 低 | 每规则一份独立 enum，可接受 |
| ~~编译性能~~ | ~~🟡 中~~ | **A' 已迁移**：编译在本地 Skill，不再占服务端 CPU |
| ~~大规则生成慢~~ | ~~🟡 中~~ | **A' 已迁移**：与编辑器同一进程，本地资源可承受 |
| Groovy 沙箱拒绝 | 🟡 中 | 白名单方法（RFC-0020 配合；白名单构建期同步自 Skill） |
| **恶意 Groovy 上传**（服务端无 DSL 校验） | 🟡 中 | MCP 入口加 IP 白名单 / mTLS；执行期由 Groovy 沙箱（RFC-0020 SecureASTCustomizer）兜底 |
| Skill 升级导致 Groovy 产物形态变化 | 🟡 中 | RuleArtifact 记录 sha256 + compileStatus；客户端拉新版本强制重编译 |
| MCP 端点契约漂移 | 🟢 低 | 字段稳定原则（与 §3.6 ExecutionResponse 同款约束） |

---

## 7. 实施步骤

```
# A' 后路径（拆分两个仓）

## orule-llm-studio 仓
1. 初始化 skill 目录：simplets-to-groovy/
2. 实现 ts-parser.ts（require typescript + ts.createSourceFile）
3. 实现 simplets-pruner.ts（RFC-0018 §3.5）
4. 实现 simplets-validator.ts（RFC-0018 §3.6 / §3.7）
5. 实现 errors.ts（RFC-0018 §3.8）
6. 实现 groovy-codegen.ts（§3.2 ~ §3.5）
7. 实现 skill.ts（入口，封装输入输出契约）
8. 配置 skill.config.ts（沙箱：forbiddenModules + timeout + memory）
9. vitest 单测（80+ 用例，覆盖原 §5 全部 case）
10. 集成测试：完整 VIP 示例编译

## orule-common 仓（构建脚本）
11. simplets-whitelist.ts → simplets-whitelist.json 单向同步脚本
12. CI 断言两端字段一致

## orule-server 仓
13. 新增 dto/GroovySourceIntakeRequest.java + GroovySourceIntakeResponse.java
14. 新增 service/GroovySourceIntakeService.java（含 SHA256 校验）
15. 新增 controller/GroovySourceIntakeController.java
16. WebMvcTest 单测（成功 / 失败 / SHA256 mismatch / 长度上限）
17. 集成测试：MCP 端点契约
18. 删除 com.orule.dsl.codegen 包 + CompileService
19. orule-server/pom.xml 删除 org.graalvm.polyglot 依赖（与 RFC-0018 §4 同步）
```

---

## 8. 关联

- 上游：RFC-0018（SimpleTS 解析器，§3.9 已改写为 Skill 化）、RFC-0017（ArtifactStorage）、**RFC-0031（Type 系统重构）**、**RFC-0032（ObjectType 枚举化 + Type 系统 4 Variant）**
- 下游：RFC-0020（Groovy 沙箱执行，运行期校验）、RFC-0022（测试用例）、RFC-0023（NL→SimpleTS，Skill #1）
- 平级：RFC-0018 §3.10 SimpleTSWhitelist → 构建期同步到本仓沙箱
- ADR：**ADR-009 SimpleTS 为中心的星型转换架构**、**ADR-012 enum 视为 ObjectType 特殊形态**、**[ADR-012-Aprime 本地 Skill 编译 + MCP 落库（A'，已采纳）](../../adr/ADR-012-Aprime-本地Skill编译与MCPLangLib库.md)**
- 规范：[docs/dsl/SimpleTS.md §10.2](../../dsl/SimpleTS.md)

---

## 8.1 RFC-0019-bis 修订日志

| 日期 | 修订内容 |
|------|---------|
| 2026-09-12 | **RFC-0019-bis（A' 采纳）**：§3.6 原 CompileService（服务端编译）作废；替换为 GroovySourceIntakeService（MCP 落库）；§3.7 新增 MCP 端点 `POST /mcp/tools/orule.rule.publishCompiledGroovy`；原 `/api/v1/rule-versions/{id}/compile` 端点 DEPRECATED；§3.1 ~ §3.5 GroovyCodeGen 全部作为 Skill 实现规范保留 |
