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

### 3.6 落库 + 上传 Artifact

```java
@Service
@RequiredArgsConstructor
public class CompileService {

    private final GroovyCodeGen codeGen;
    private final ArtifactStorage storage;
    private final RuleVersionRepository versionRepo;
    private final RuleArtifactRepository artifactRepo;

    @Transactional
    public CompileResult compile(String ruleVersionId) {
        RuleVersion version = versionRepo.findById(ruleVersionId)
            .orElseThrow(() -> new NotFoundException("RuleVersion", ruleVersionId));

        try {
            // 1. 拼装 DomainMeta（RFC-0031 同步：从 RFC-0015 元数据 API 取 DomainType + ObjectType + AttributeType + FunctionLib）
            DomainMeta meta = buildDomainMeta(version.getRule().getRuleSet().getDomainId());

            // 2. 解析 SimpleTS → AST
            Program ast = parser.parse(version.getSimpleTs(), meta);

            // 3. 生成 Groovy
            String groovySource = codeGen.generateExecutable(
                ast, meta, version.getRule().getCode(), version.getVersion());

            // 4. 更新 RuleVersion
            version.setGroovySource(groovySource);
            versionRepo.save(version);

            // 5. 上传 Artifact
            String key = String.format("rules/%s/v%d.groovy",
                version.getRule().getCode(), version.getVersion());
            UploadResult upload = storage.upload(key, groovySource.getBytes(UTF_8));

            // 6. 创建 RuleArtifact 记录
            RuleArtifact artifact = RuleArtifact.builder()
                .id(UUID.randomUUID().toString())
                .ruleVersionId(version.getId())
                .storageType(storage.getType())
                .storagePath(upload.storagePath())
                .storageUrl(upload.url())
                .fileSize(upload.fileSize())
                .sha256(upload.sha256())
                .compileStatus(CompileStatus.SUCCESS)
                .build();
            artifactRepo.save(artifact);

            return new CompileResult(true, groovySource, null);

        } catch (TssCompileError e) {
            // 记录编译失败
            RuleArtifact failed = RuleArtifact.builder()
                .id(UUID.randomUUID().toString())
                .ruleVersionId(version.getId())
                .storageType("none")
                .storagePath("")
                .fileSize(0)
                .sha256("")
                .compileStatus(CompileStatus.FAILED)
                .compileLog(e.getMessage())
                .build();
            artifactRepo.save(failed);

            return new CompileResult(false, null, e.getMessage());
        }
    }

    /**
     * 从 RFC-0015 元数据 API 拼装 DomainMeta。
     *
     * <p>RFC-0031 修订：
     * <ul>
     *   <li>遍历 {@code ObjectType.fields} → AttributeType，type 是 RFC-0031 的 5 Variant 之一；</li>
     *   <li>枚举定义从 AttributeType.type.kind === 'enum' 的 {@code EnumType.values} 提取；</li>
     *   <li>context 入口来自 DomainType.contextVariables（ObjectType）。</li>
     * </ul>
     */
    private DomainMeta buildDomainMeta(String domainId) {
        // 1. 取 DomainType
        // 2. 取所有 ObjectType + AttributeType
        // 3. 取 FunctionLib（其 signature 用 RFC-0031 FunctionSignature）
        // 4. 拼装 DomainMeta
        // ...（具体实现依赖 RFC-0015 的 service）
    }
}
```

### 3.7 REST API

```
POST /api/v1/rule-versions/{id}/compile     # 触发编译
GET  /api/v1/rule-versions/{id}/artifact    # 查看编译产物
GET  /api/v1/rule-versions/{id}/compile-log  # 查看编译日志（失败时）
```

---

## 4. 影响面

- 新增 `com.orule.dsl.codegen` 包（3 个类）
- 新增 `CompileService`（与 RFC-0016、0017、0018 配合）
- 新增 3 个 REST 端点
- 数据库 schema 不变（依赖 RFC-0014 已建表）

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| 单元测试：节点级 codegen | 每个 AST 节点类型生成 |
| 单元测试：字符串转义 | `"` `\` `\n` 正确转义 |
| 集成测试：VIP 满减 | SimpleTS→Groovy 完整链路 |
| 集成测试：枚举 | EnumRef 正确生成 enum 类 |
| 集成测试：Artifact 上传 | 文件存在 + SHA256 校验 |
| 集成测试：编译失败 | SimpleTS 语法错误 → 失败记录 |
| 视觉一致性 | Groovy 与 SimpleTS 视觉一致 |
| 性能 | 单条规则编译 < 2s |

### 关键测试用例

```java
@Test
@DisplayName("VIP 满减规则生成 Groovy")
void vipDiscount_generateGroovy() {
    String simpleTs = """
        if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
            order.discount = 30
        }
        """;
    DomainMeta meta = TestMetaFactory.orderDiscount();
    
    Program ast = parser.parse(simpleTs, meta);
    String groovy = codeGen.generateExecutable(ast, meta, "ORDER_DISCOUNT", 1);
    
    assertThat(groovy).contains("enum CustomerTier { VIP, GOLD, SILVER, BRONZE }");
    assertThat(groovy).contains("def execute(Map context)");
    assertThat(groovy).contains("if ((customer.tier == CustomerTier.VIP) && (order.totalAmount >= 200))");
    assertThat(groovy).contains("order.discount = 30");
    assertThat(groovy).contains("return context");
}

@Test
@DisplayName("字符串字面量正确转义")
void stringLiteral_escape() {
    String simpleTs = """
        order.note = "Hello \"World\"\n"
        """;
    
    String groovy = codeGen.generate(/* ... */);
    assertThat(groovy).contains("\"Hello \\\"World\\\"\\n\"");
}
```

---

## 6. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| Groovy 与 TS 语法差异（括号、分号） | 🟢 低 | 测试覆盖视觉一致性 |
| Enum 包装类爆炸 | 🟢 低 | 每规则一份独立 enum，可接受 |
| 编译性能 | 🟡 中 | AST 缓存 + 并行编译 |
| 大规则生成慢 | 🟡 中 | 流式输出；不一次性加载全树 |
| Groovy 沙箱拒绝 | 🟡 中 | 白名单方法（RFC-0020 配合） |

---

## 7. 实施步骤

```
1. 创建 com.orule.dsl.codegen 包
2. 实现 GroovyWriter
3. 实现节点级 codegen（If / For / Declare / Assign / Expr / Block）
4. 实现 EnumRef 包装
5. 实现完整可执行模板
6. 实现 CompileService
7. 实现 REST 端点
8. 单元测试（30+ 用例）
9. 集成测试（完整链路）
10. 性能测试
11. 视觉一致性 review
```

---

## 8. 关联

- 上游：RFC-0018（SimpleTS 解析器）、RFC-0017（ArtifactStorage）、**RFC-0031（Type 系统，5 Variant）**
- 下游：RFC-0020（Groovy 沙箱执行）、RFC-0022（测试用例）、RFC-0023（NL→SimpleTS）
- ADR：**ADR-009 SimpleTS 为中心的星型转换架构**
- 规范：[docs/dsl/SimpleTS.md §10.2](../../dsl/SimpleTS.md)
