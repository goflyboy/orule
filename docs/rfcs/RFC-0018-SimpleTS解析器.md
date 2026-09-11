# RFC-0018: SimpleTS 解析器（白名单剪枝 + 字段校验）

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：5d · **阶段**：S3

---

## 1. 摘要

实现 SimpleTS 解析器，依据 `docs/dsl/SimpleTS.md` 第 4、6、7、8 节的定义，把 SimpleTS 源码编译成 SimpleTS-AST；含白名单剪枝、字段校验、错误行号、错误信息模板。

---

## 2. 动机

- SimpleTS 是 MVP 中间态（依据 ADR-003 / ADR-009）
- 解析器是 NL→SimpleTS→Groovy 链路的中心环节（依据 `06-运行视图 §6.3`）
- 当前仓库仅有 `docs/dsl/SimpleTS.md` 规范，无实现

---

## 3. 详细设计

### 3.1 模块位置

```
packages/orule-common/src/main/java/com/orule/dsl/
├── SimpleTSParser.java        # 入口
├── DomainMeta.java            # 元数据 DTO
├── ast/                       # SimpleTS-AST 节点（与 docs/dsl/SimpleTS.md §6 对应）
│   ├── Node.java              # 公共接口
│   ├── Program.java
│   ├── Block.java
│   ├── IfStmt.java
│   ├── ForStmt.java
│   ├── DeclareStmt.java
│   ├── AssignStmt.java
│   ├── ExprStmt.java
│   ├── BinaryExpr.java
│   ├── UnaryExpr.java
│   ├── Literal.java
│   ├── MemberAccess.java
│   ├── CallExpr.java
│   └── EnumRef.java
├── error/
│   ├── TssCompileError.java
│   └── CompileError.java
└── validate/
    ├── WhitelistPruner.java    # §9.1 白名单剪枝
    ├── IdentifierResolver.java # §7.1 标识符作用域
    ├── FieldValidator.java     # 字段存在性 + 枚举值
    ├── TypeChecker.java        # 类型对齐
    └── LValueChecker.java      # 左值合法性
```

### 3.2 入口 SimpleTSParser

```java
package com.orule.dsl;

import com.orule.dsl.ast.Program;
import com.orule.dsl.error.TssCompileError;
import com.orule.dsl.validate.*;

import java.util.ArrayList;
import java.util.List;

/**
 * SimpleTS 解析器入口。
 * 流程：词法 → TS AST → 白名单剪枝 → 标识符解析 → 字段校验 → 类型检查 → 产出 SimpleTS-AST
 */
public class SimpleTSParser {

    private final WhitelistPruner pruner = new WhitelistPruner();
    private final IdentifierResolver resolver = new IdentifierResolver();
    private final FieldValidator fieldValidator = new FieldValidator();
    private final TypeChecker typeChecker = new TypeChecker();
    private final LValueChecker lvalueChecker = new LValueChecker();

    /**
     * 编译入口。
     * @param source SimpleTS 源码
     * @param meta   领域元数据
     * @return  SimpleTS-AST
     * @throws TssCompileError 编译失败（含完整错误列表）
     */
    public Program parse(String source, DomainMeta meta) {
        // 1. 词法 / 语法（基于 TypeScript Compiler API 通过 GraalJS 调用）
        TsSourceFile tsAst = parseToTsAst(source);
        
        // 2. 白名单剪枝
        Program program = pruner.convert(tsAst, meta);
        if (pruner.hasErrors()) {
            throw new TssCompileError(pruner.getErrors());
        }
        
        // 3. 标识符作用域解析
        resolver.resolve(program, meta);
        if (resolver.hasErrors()) {
            throw new TssCompileError(resolver.getErrors());
        }
        
        // 4. 字段存在性 + 枚举值校验
        fieldValidator.validate(program, meta);
        if (fieldValidator.hasErrors()) {
            throw new TssCompileError(fieldValidator.getErrors());
        }
        
        // 5. 类型对齐
        typeChecker.check(program, meta);
        if (typeChecker.hasErrors()) {
            throw new TssCompileError(typeChecker.getErrors());
        }
        
        // 6. 左值合法性
        lvalueChecker.check(program);
        if (lvalueChecker.hasErrors()) {
            throw new TssCompileError(lvalueChecker.getErrors());
        }
        
        return program;
    }
}
```

### 3.3 DomainMeta（Java 版）

```java
package com.orule.dsl;

import java.util.List;
import java.util.Map;

/**
 * 领域元数据（与 docs/dsl/SimpleTS.md §7 DomainMeta 对应）。
 * 由 orule-server 在编译时根据 RFC-0015 的元数据 API 拼装。
 */
public record DomainMeta(
    String id,
    List<EnumDef> enums,
    List<EntityDef> entities,
    List<ContextVar> context
) {
    public record EnumDef(String id, List<String> values) {}
    
    public record EntityField(
        String name,
        FieldType type,
        boolean nullable,
        boolean writable
    ) {
        public sealed interface FieldType permits
            PrimitiveType, EntityRef, EnumRef {}
        public record PrimitiveType(String name) implements FieldType {} // "string"|"number"|"boolean"|"date"
        public record EntityRef(String entityId) implements FieldType {}
        public record EnumRef(String enumId) implements FieldType {}
    }
    
    public record EntityDef(String id, List<EntityField> fields) {}
    
    public record ContextVar(String name, String entityId, boolean nullable) {}
}
```

### 3.4 AST 节点（核心样例）

```java
package com.orule.dsl.ast;

public sealed interface Node permits
    Program, Block, IfStmt, ForStmt, DeclareStmt, AssignStmt, ExprStmt,
    BinaryExpr, UnaryExpr, Literal, MemberAccess, CallExpr, EnumRef {

    /** 源码位置（用于错误信息和 trace） */
    int line();
    int column();
}

public record Program(List<Node> body, int line, int column) implements Node {}

public record IfStmt(
    List<Branch> branches,  // 末位可选 else
    int line, int column
) implements Node {
    public record Branch(BinaryExpr test, Block body) {}
}

public record AssignStmt(
    MemberAccess target,    // AST 类型层钉死，杜绝裸标识符赋值
    Node value,
    int line, int column
) implements Node {}

public sealed interface Expr permits
    BinaryExpr, UnaryExpr, Literal, MemberAccess, CallExpr, EnumRef {}

public record BinaryExpr(
    String op,  // "||" "&&" "==" "!=" ">" ">=" "<" "<=" "+" "-" "*" "/" "%"
    Expr left, Expr right,
    int line, int column
) implements Expr {}

public record MemberAccess(
    String root,           // 必须是 DomainMeta.context 中的入口变量
    List<String> path,     // 后续属性链
    int line, int column
) implements Expr, com.orule.dsl.ast.LValue {}  // 既是 Expr 又是合法左值

public record CallExpr(
    MemberAccess callee,
    List<Expr> args,
    int line, int column
) implements Expr {}
```

### 3.5 白名单剪枝（核心）

依据 `docs/dsl/SimpleTS.md §5` 砍掉的语法：

```java
package com.orule.dsl.validate;

import com.orule.dsl.ast.*;
import com.orule.dsl.DomainMeta;
import com.orule.dsl.error.CompileError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ts.SyntaxKind;
import ts.Node;

/**
 * 白名单剪枝：把 TS AST 转换为 SimpleTS-AST。
 * 依据 docs/dsl/SimpleTS.md §9.1。
 */
public class WhitelistPruner {

    private static final Set<String> ALLOWED_KINDS = Set.of(
        "IfStatement", "ForStatement",
        "VariableStatement", "VariableDeclarationList", "VariableDeclaration",
        "ExpressionStatement",
        "BinaryExpression", "PrefixUnaryExpression",
        "PropertyAccessExpression", "CallExpression",
        "NumericLiteral", "StringLiteral", "TrueKeyword", "FalseKeyword",
        "Block", "ParenthesizedExpression",
        "FirstStatement", "LastStatement"  // 用于 if 链
    );
    
    private static final Set<String> ALLOWED_BINARY_OPS = Set.of(
        "||", "&&", "==", "!=", ">", ">=", "<", "<=", "+", "-", "*", "/", "%"
    );
    
    private static final Set<String> ALLOWED_UNARY_OPS = Set.of("!", "-");
    
    private static final Set<String> ALLOWED_CALL_METHODS = Set.of(
        // 数学
        "abs", "min", "max", "floor", "ceil", "round",
        // 字符串
        "startsWith", "endsWith", "includes", "toUpperCase", "toLowerCase",
        "length",
        // 日期
        "now", "getFullYear", "getMonth", "getDate"
    );
    
    private final List<CompileError> errors = new ArrayList<>();
    private int statementCount = 0;
    
    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<CompileError> getErrors() { return List.copyOf(errors); }
    
    public Program convert(Node tsAst, DomainMeta meta) {
        List<Node> body = new ArrayList<>();
        for (Node stmt : tsAst.statements) {
            Node converted = convertStatement(stmt, meta);
            if (converted != null) body.add(converted);
        }
        
        if (statementCount > 200) {
            errors.add(new CompileError(0, 0, 
                "单条规则 statement 数超过上限 200 (当前: " + statementCount + ")"));
        }
        
        return new Program(body, 1, 1);
    }
    
    private Node convertStatement(Node ts, DomainMeta meta) {
        statementCount++;
        return switch (ts.kind) {
            case "IfStatement" -> convertIf(ts, meta);
            case "ForStatement" -> convertFor(ts, meta);
            case "VariableStatement" -> convertDeclare(ts, meta);
            case "ExpressionStatement" -> {
                Node expr = convertExpr(ts.expression, meta);
                yield new ExprStmt((Expr) expr, ts.line, ts.column);
            }
            case "Block" -> convertBlock(ts, meta);
            default -> {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的语句类型: " + ts.kind));
                yield null;
            }
        };
    }
    
    private Node convertIf(Node ts, DomainMeta meta) {
        // 处理 if / else if / else 链
        List<IfStmt.Branch> branches = new ArrayList<>();
        Node current = ts;
        
        while (current.kind.equals("IfStatement")) {
            Expr test = (Expr) convertExpr(current.expression, meta);
            Block body = (Block) convertStatement(current.thenStatement, meta);
            branches.add(new IfStmt.Branch(asBinary(test), body));
            current = current.elseStatement;
            if (current == null) break;
        }
        
        // else 块
        Block elseBody = null;
        if (current != null && current.kind.equals("Block")) {
            elseBody = (Block) convertStatement(current, meta);
        }
        
        // IfStmt 用 branches + elseBody 表示
        return new IfStmt(branches, elseBody, ts.line, ts.column);
    }
    
    private Node convertExpr(Node ts, DomainMeta meta) {
        return switch (ts.kind) {
            case "BinaryExpression" -> {
                if (!ALLOWED_BINARY_OPS.contains(ts.operatorToken)) {
                    errors.add(new CompileError(ts.line, ts.column,
                        "不允许的二元运算符: " + ts.operatorToken));
                    yield null;
                }
                Expr left = (Expr) convertExpr(ts.left, meta);
                Expr right = (Expr) convertExpr(ts.right, meta);
                yield new BinaryExpr(ts.operatorToken, left, right, ts.line, ts.column);
            }
            case "PrefixUnaryExpression" -> {
                if (!ALLOWED_UNARY_OPS.contains(ts.operator)) {
                    errors.add(new CompileError(ts.line, ts.column,
                        "不允许的一元运算符: " + ts.operator));
                    yield null;
                }
                Expr arg = (Expr) convertExpr(ts.operand, meta);
                yield new UnaryExpr(ts.operator, arg, ts.line, ts.column);
            }
            case "PropertyAccessExpression" -> convertMemberAccess(ts, meta);
            case "CallExpression" -> convertCall(ts, meta);
            case "NumericLiteral", "StringLiteral" -> 
                new Literal(parseLiteral(ts), ts.line, ts.column);
            case "TrueKeyword" -> new Literal(true, ts.line, ts.column);
            case "FalseKeyword" -> new Literal(false, ts.line, ts.column);
            case "ParenthesizedExpression" -> convertExpr(ts.expression, meta);
            default -> {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的表达式类型: " + ts.kind));
                yield null;
            }
        };
    }
    
    private MemberAccess convertMemberAccess(Node ts, DomainMeta meta) {
        List<String> path = new ArrayList<>();
        Node current = ts;
        // 解析 customer.tier.length → root="customer", path=["tier", "length"]
        while (current.kind.equals("PropertyAccessExpression")) {
            path.add(0, current.name.text);
            current = current.expression;
        }
        String root = current.text;
        return new MemberAccess(root, path, ts.line, ts.column);
    }
    
    private CallExpr convertCall(Node ts, DomainMeta meta) {
        MemberAccess callee = convertMemberAccess(ts.expression, meta);
        
        // 白名单方法检查：取 path 末位
        if (!callee.path().isEmpty()) {
            String method = callee.path().get(callee.path().size() - 1);
            if (!ALLOWED_CALL_METHODS.contains(method)) {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的方法调用: '" + method + "'"));
            }
        }
        
        List<Expr> args = new ArrayList<>();
        for (Node arg : ts.arguments) {
            args.add((Expr) convertExpr(arg, meta));
        }
        return new CallExpr(callee, args, ts.line, ts.column);
    }
    
    // ... 其他转换方法
}
```

### 3.6 字段校验器

依据 `docs/dsl/SimpleTS.md §7.1`：

```java
package com.orule.dsl.validate;

public class FieldValidator {
    
    private final List<CompileError> errors = new ArrayList<>();
    
    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<CompileError> getErrors() { return List.copyOf(errors); }
    
    public void validate(Program program, DomainMeta meta) {
        // 收集所有 MemberAccess 节点
        new NodeVisitor<Void>().visit(program, node -> {
            if (node instanceof MemberAccess ma) {
                validateMemberAccess(ma, meta);
            } else if (node instanceof EnumRef er) {
                validateEnumRef(er, meta);
            }
            return null;
        });
    }
    
    private void validateMemberAccess(MemberAccess ma, DomainMeta meta) {
        // 1. root 必须在 context 中
        DomainMeta.ContextVar contextVar = meta.context().stream()
            .filter(c -> c.name().equals(ma.root()))
            .findFirst()
            .orElse(null);
        
        if (contextVar == null) {
            errors.add(new CompileError(ma.line(), ma.column(),
                "未声明的标识符: '" + ma.root() + "'"));
            return;
        }
        
        // 2. 解析属性链
        DomainMeta.EntityDef entity = findEntity(meta, contextVar.entityId());
        for (int i = 0; i < ma.path().size(); i++) {
            String fieldName = ma.path().get(i);
            DomainMeta.EntityField field = entity.fields().stream()
                .filter(f -> f.name().equals(fieldName))
                .findFirst()
                .orElse(null);
            
            if (field == null) {
                errors.add(new CompileError(ma.line(), ma.column(),
                    "实体 " + entity.id() + " 上不存在字段 '" + fieldName + "'"));
                return;
            }
            
            // 3. 最后一跳允许访问白名单方法（length / startsWith 等）
            // 由 WhitelistPruner 处理
            
            // 4. 推进 entity（处理 object 类型字段）
            if (field.type() instanceof DomainMeta.EntityRef er) {
                entity = findEntity(meta, er.entityId());
            } else if (i < ma.path().size() - 1) {
                errors.add(new CompileError(ma.line(), ma.column(),
                    "字段 '" + fieldName + "' 不是对象类型，不能继续访问"));
                return;
            }
        }
    }
    
    private void validateEnumRef(EnumRef er, DomainMeta meta) {
        DomainMeta.EnumDef enumDef = meta.enums().stream()
            .filter(e -> e.id().equals(er.enumId()))
            .findFirst()
            .orElse(null);
        
        if (enumDef == null) {
            errors.add(new CompileError(er.line(), er.column(),
                "未定义的枚举: '" + er.enumId() + "'"));
            return;
        }
        
        if (!enumDef.values().contains(er.value())) {
            errors.add(new CompileError(er.line(), er.column(),
                "枚举 " + er.enumId() + " 不含值 '" + er.value() + "'"));
        }
    }
    
    private DomainMeta.EntityDef findEntity(DomainMeta meta, String entityId) {
        return meta.entities().stream()
            .filter(e -> e.id().equals(entityId))
            .findFirst()
            .orElseThrow();
    }
}
```

### 3.7 左值检查器

```java
public class LValueChecker {
    
    private final List<CompileError> errors = new ArrayList<>();
    
    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<CompileError> getErrors() { return List.copyOf(errors); }
    
    public void check(Program program) {
        new NodeVisitor<Void>().visit(program, node -> {
            if (node instanceof AssignStmt assign) {
                Node target = assign.target();
                
                // 1. 必须是 MemberAccess（AST 类型层已保证，再加运行时校验）
                if (!(target instanceof MemberAccess ma)) {
                    errors.add(new CompileError(assign.line(), assign.column(),
                        "禁止给算术表达式赋值（仅允许上下文变量属性赋值）"));
                    return null;
                }
                
                // 2. 必须有 root
                if (ma.root() == null || ma.root().isBlank()) {
                    errors.add(new CompileError(assign.line(), assign.column(),
                        "赋值左值必须以 context 变量开头"));
                }
                
                // 3. 末位字段必须 writable（DomainMeta 控制）
                // 留给 FieldValidator 配合处理
            }
            return null;
        });
    }
}
```

### 3.8 错误信息模板

依据 `docs/dsl/SimpleTS.md §11`：

```java
public record CompileError(int line, int column, String message) {
    
    /** 格式化错误信息（参考 §11 样板） */
    public String format() {
        return String.format("第 %d 行 第 %d 列: %s", line, column, message);
    }
    
    /** 多错误聚合 */
    public static String formatAll(List<CompileError> errors) {
        StringBuilder sb = new StringBuilder("TSS 编译失败:\n\n");
        for (CompileError e : errors) {
            sb.append("  ✗ ").append(e.format()).append("\n");
        }
        return sb.toString();
    }
}

public class TssCompileError extends RuntimeException {
    private final List<CompileError> errors;
    
    public TssCompileError(List<CompileError> errors) {
        super(CompileError.formatAll(errors));
        this.errors = List.copyOf(errors);
    }
    
    public List<CompileError> getErrors() { return errors; }
}
```

### 3.9 TS AST 解析（GraalJS 桥接）

```java
package com.orule.dsl;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import ts.SyntaxKind;
import ts.Node;

/**
 * 通过 GraalJS 调用 TypeScript Compiler API。
 * 把 SimpleTS 源码解析为 TS AST（JSON 形态），再转 Java 对象。
 */
public class TsAstParser {
    
    private static final String TS_PARSER_JS = """
        const ts = require('typescript');
        function parse(source) {
            const sf = ts.createSourceFile('rule.ts', source, ts.ScriptTarget.ES2020, true);
            return tsNodeToJson(sf);
        }
        function tsNodeToJson(node) {
            const result = {
                kind: ts.SyntaxKind[node.kind],
                line: sf.getLineAndCharacterOfPosition(node.getStart()).line + 1,
                column: sf.getLineAndCharacterOfPosition(node.getStart()).character + 1,
            };
            // 递归遍历子节点
            // ...
            return result;
        }
        parse;
        """;
    
    private final Context jsContext;
    
    public TsAstParser() {
        this.jsContext = Context.newBuilder("js")
            .allowAllAccess(true)
            .build();
        // 加载 TypeScript lib（npm 包）
        this.jsContext.eval("js", "const ts = require('typescript');");
    }
    
    public Node parse(String source) {
        Value parseFn = jsContext.eval("js", TS_PARSER_JS);
        Value result = parseFn.execute(source);
        return convertFromJson(result);
    }
    
    // ... JSON → Java Node 转换
}
```

> **说明**：MVP 采用 GraalJS 桥接 TypeScript Compiler API（参考 `05-技术模型 §5.x` Groovy 沙箱方案），避免重复造 TS 解析器的轮子。

---

## 4. 影响面

- 新增整个 `com.orule.dsl` 包（~15 个类）
- 不涉及数据库表
- 不涉及外部 API
- 依赖 GraalJS + TypeScript npm 包

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| 词法/语法白名单 | 命中各非法语法应拒绝 |
| 标识符未声明 | `invoice.amount` → 报错 |
| 字段不存在 | `order.discountX` → 报错 |
| 字段只读 | `order.id = "x"` → 报错 |
| 枚举值非法 | `CustomerTier.GOD` → 报错 |
| 类型不匹配 | `order.totalAmount == "200"` → 报错 |
| 左值非法 | `200 = x` → 报错 |
| 表达式嵌套 | 32 层 → 报错 |
| 语句总数 | 200+ 条 → 报错 |
| 完整 VIP 示例 | 编译成功 |
| 错误信息模板 | 格式与样板一致 |
| 性能 | 单条规则编译 < 200ms |

### 关键测试用例

```java
@Test
@DisplayName("VIP 满减规则编译成功")
void vipDiscount_compileSucceed() {
    String source = """
        if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
            order.discount = 30
        }
        """;
    DomainMeta meta = TestMetaFactory.orderDiscount();
    Program ast = parser.parse(source, meta);
    
    assertThat(ast.body()).hasSize(1);
    assertThat(ast.body().get(0)).isInstanceOf(IfStmt.class);
}

@Test
@DisplayName("不允许对象字面量")
void objectLiteral_shouldFail() {
    String source = """
        let x = { a: 1 }
        """;
    DomainMeta meta = TestMetaFactory.simple();
    
    assertThatThrownBy(() -> parser.parse(source, meta))
        .isInstanceOf(TssCompileError.class)
        .hasMessageContaining("不允许");
}

@Test
@DisplayName("枚举值非法报错")
void invalidEnumValue_shouldFail() {
    String source = """
        if (customer.tier == CustomerTier.GOD) { ... }
        """;
    DomainMeta meta = TestMetaFactory.orderDiscount();
    
    assertThatThrownBy(() -> parser.parse(source, meta))
        .isInstanceOf(TssCompileError.class)
        .hasMessageContaining("枚举 CustomerTier 不含值 'GOD'");
}

@Test
@DisplayName("未声明标识符报错")
void undeclaredIdentifier_shouldFail() {
    String source = """
        if (invoice.amount > 0) { ... }
        """;
    DomainMeta meta = TestMetaFactory.orderDiscount();
    
    assertThatThrownBy(() -> parser.parse(source, meta))
        .isInstanceOf(TssCompileError.class)
        .hasMessageContaining("未声明的标识符 'invoice'");
}
```

---

## 6. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| GraalJS + TypeScript 性能 | 🟡 中 | 编译产物缓存（规则版本不变则复用） |
| GraalJS 集成复杂度 | 🟡 中 | 用官方 polyglot 包装器；二期可考虑换 jts 或 ANTLR |
| TS 解析器未捕获边界场景 | 🟢 低 | 大量测试用例覆盖（参考 §5） |
| 错误信息不友好 | 🟢 低 | 参考 §11 样板 + 团队评审 |

---

## 7. 实施步骤

```
1. 创建 com.orule.dsl 包
2. 实现 AST 节点（~13 个 record）
3. 实现 DomainMeta（Java record）
4. 实现 TsAstParser（GraalJS 桥接）
5. 实现 WhitelistPruner（白名单剪枝）
6. 实现 IdentifierResolver（标识符作用域）
7. 实现 FieldValidator（字段 + 枚举）
8. 实现 TypeChecker（类型对齐）
9. 实现 LValueChecker（左值合法性）
10. 实现错误信息模板
11. 单元测试（80+ 用例）
12. 集成测试（完整 VIP 示例）
13. 性能测试
```

---

## 8. 关联

- 上游：RFC-0015（元数据 API，提供 DomainMeta 拼装数据源）
- 下游：RFC-0019（SimpleTS→Groovy 代码生成器）、RFC-0023（NL→SimpleTS）
- ADR：**ADR-003 中间态 DSL 采用 SimpleTS**、**ADR-006 规则源语言采用 SimpleTS**、**ADR-009 SimpleTS 为中心的星型转换架构**
- 规范：[docs/dsl/SimpleTS.md](../../dsl/SimpleTS.md)
