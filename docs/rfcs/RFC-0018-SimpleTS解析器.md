# RFC-0018: SimpleTS 解析器（白名单剪枝 + 字段校验）

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：5d · **阶段**：S3
> **RFC-0031 修订**：DomainMeta 采用 RFC-0031 的 5 个 Variant；`FieldValidator` 需新增
> "ObjectType/ListType/MapType 字段不可继续访问内部属性" 的校验（详见 RFC-0031 §3.5.2）。

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

### 3.3 DomainMeta（Java 版 — RFC-0031 同步）

```java
package com.orule.dsl;

import com.orule.common.model.type.Type;

import java.util.List;

/**
 * 领域元数据（与 docs/dsl/SimpleTS.md §7 DomainMeta 对应）。
 *
 * <p><b>RFC-0031 修订</b>：
 * <ul>
 *   <li>Field 的 type 改为 {@link Type}（5 个 Variant 之一），不再是 RFC-0031 之前的
 *       {@code sealed interface FieldType permits PrimitiveType, EntityRef, EnumRef}。</li>
 *   <li><b>删除顶层 {@code enums} 字段</b>：enum 定义完全内联在 {@code AttributeField.type}
 *       的 {@code EnumType.values} 中，无需独立的 enum_value 表（详见 RFC-0031 §3.1）。</li>
 *   <li>{@link ContextVar} 的 type 固定为 {@link com.orule.common.model.type.ObjectType}
 *       （context 入口必须是对象引用，不能是 primitive / enum / list / map）。</li>
 * </ul>
 *
 * <p>由 orule-server 在编译时根据 RFC-0015 的元数据 API 拼装。
 */
public record DomainMeta(
        String id,
        List<EntityDef> entities,
        List<ContextVar> context
) {
    /**
     * 实体字段定义。
     *
     * <p>type 是 RFC-0031 的 5 个 Variant 之一（{@link Type}）：
     * <ul>
     *   <li>{@code PrimitiveType}：原子类型（string/number/boolean/date）</li>
     *   <li>{@code EnumType}：内联枚举（含 values 列表）</li>
     *   <li>{@code ObjectType}：对象引用（指向同 DomainMeta 下的另一个 EntityDef）</li>
     *   <li>{@code ListType}：列表（elementType 嵌套任意 Type）</li>
     *   <li>{@code MapType}：字典（keyType / valueType 嵌套）</li>
     * </ul>
     */
    public record EntityField(
            String name,
            Type type,
            boolean nullable,
            boolean writable
    ) {}

    public record EntityDef(String id, List<EntityField> fields) {}

    /**
     * Context 入口变量。
     *
     * <p>MVP 约束：type 必须是 {@link com.orule.common.model.type.ObjectType}，
     * 不允许 primitive / enum / list / map 作为 context 入口（详见 SimpleTS.md §7）。
     */
    public record ContextVar(
            String name,
            com.orule.common.model.type.ObjectType type,
            boolean nullable
    ) {}
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

import com.orule.common.model.type.Type;
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
 *
 * <p>依据 docs/dsl/SimpleTS.md §9.1。
 *
 * <p><b>方法/类白名单统一从 {@link com.orule.dsl.SimpleTSWhitelist} 取</b>，
 * 与 RFC-0020 的 Groovy 沙箱白名单保持单一来源（详见 RFC-0018 §8 关联 + RFC-0020 §3.2）。
 */
public class WhitelistPruner {

    /** TS SyntaxKind 白名单（详见 SimpleTS.md §6 受限 AST 节点） */
    private static final Set<String> ALLOWED_KINDS = Set.of(
        "IfStatement", "ForStatement",
        "VariableStatement", "VariableDeclarationList", "VariableDeclaration",
        "ExpressionStatement",
        "BinaryExpression", "PrefixUnaryExpression",
        "PropertyAccessExpression", "CallExpression",
        "NumericLiteral", "StringLiteral", "NoSubstitutionTemplateLiteral",
        "TrueKeyword", "FalseKeyword", "NullKeyword",
        "Block", "ParenthesizedExpression",
        "FirstStatement", "LastStatement"  // 用于 if 链
    );

    private static final Set<String> ALLOWED_BINARY_OPS = Set.of(
        "||", "&&", "==", "!=", ">", ">=", "<", "<=", "+", "-", "*", "/", "%"
    );

    private static final Set<String> ALLOWED_UNARY_OPS = Set.of("!", "-");

    /**
     * 白名单内置方法（与 RFC-0020 §3.2 的 defaultAllowedMethods() 保持一致，
     * 单一来源在 com.orule.dsl.SimpleTSWhitelist）。
     */
    private static final Set<String> ALLOWED_CALL_METHODS =
            SimpleTSWhitelist.allowedMethodNames();

    /**
     * statement 总数上限（与 SimpleTS.md §7.1 "statement 总数" 校验项一致）。
     *
     * <p>注意：仅统计 <b>成功转换</b> 的语句，被拒绝的非法语句不计入，
     * 避免错误信息虚高导致正常规则被拒。
     */
    private static final int MAX_STATEMENT_COUNT = 200;

    private final List<CompileError> errors = new ArrayList<>();
    private int statementCount = 0;

    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<CompileError> getErrors() { return List.copyOf(errors); }

    public Program convert(Node tsAst, DomainMeta meta) {
        List<Node> body = new ArrayList<>();
        for (Node stmt : tsAst.statements) {
            Node converted = convertStatement(stmt, meta);
            if (converted != null) {
                body.add(converted);
                statementCount++;
            }
        }

        if (statementCount > MAX_STATEMENT_COUNT) {
            errors.add(new CompileError(0, 0,
                "单条规则 statement 数超过上限 " + MAX_STATEMENT_COUNT
                    + " (当前: " + statementCount + ")"));
        }

        return new Program(body, 1, 1);
    }

    private Node convertStatement(Node ts, DomainMeta meta) {
        // 不在 switch 顶部 ++，因为被 default 拒绝时不应计入 statement 数
        return switch (ts.kind) {
            case "IfStatement" -> convertIf(ts, meta);
            case "ForStatement" -> convertFor(ts, meta);
            case "VariableStatement" -> convertDeclare(ts, meta);
            case "ExpressionStatement" -> {
                Node expr = convertExpr(ts.expression, meta);
                if (expr instanceof Expr e) {
                    yield new ExprStmt(e, ts.line, ts.column);
                }
                yield null;
            }
            case "Block" -> convertBlock(ts, meta);
            default -> {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的语句类型: " + ts.kind
                        + "（参见 docs/dsl/SimpleTS.md §5 砍掉的语法）"));
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
                        "不允许的二元运算符: " + ts.operatorToken
                            + "（参见 SimpleTS.md §5 砍掉的语法）"));
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
            case "NumericLiteral", "StringLiteral", "NoSubstitutionTemplateLiteral" ->
                new Literal(parseLiteral(ts), ts.line, ts.column);
            case "TrueKeyword" -> new Literal(true, ts.line, ts.column);
            case "FalseKeyword" -> new Literal(false, ts.line, ts.column);
            case "NullKeyword" -> new Literal(null, ts.line, ts.column);
            case "ThisKeyword", "SuperKeyword" -> {
                // RFC-0018 §3.5: 显式拒绝 this / super
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的访问: '" + ts.kind + "'（SimpleTS 不支持 this/super）"));
                yield null;
            }
            case "ParenthesizedExpression" -> convertExpr(ts.expression, meta);
            default -> {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的表达式类型: " + ts.kind
                        + "（参见 SimpleTS.md §5 砍掉的语法）"));
                yield null;
            }
        };
    }

    private MemberAccess convertMemberAccess(Node ts, DomainMeta meta) {
        List<String> path = new ArrayList<>();
        Node current = ts;
        // 解析 customer.tier.length → root="customer", path=["tier", "length"]
        // 注意：底层的 identifier（最左侧）一定不是 PropertyAccessExpression，
        // 由 TS 编译器保证。
        while (current.kind.equals("PropertyAccessExpression")) {
            path.add(0, current.name.text);
            current = current.expression;
        }

        // 拒绝 this.xxx / super.xxx
        if ("ThisKeyword".equals(current.kind) || "SuperKeyword".equals(current.kind)) {
            errors.add(new CompileError(ts.line, ts.column,
                "不允许的访问: '" + current.kind + "'（SimpleTS 不支持 this/super）"));
            return null;
        }

        String root = current.text;
        return new MemberAccess(root, path, ts.line, ts.column);
    }

    private CallExpr convertCall(Node ts, DomainMeta meta) {
        MemberAccess callee = convertMemberAccess(ts.expression, meta);
        if (callee == null) return null;

        // 白名单方法检查：取 path 末位
        if (!callee.path().isEmpty()) {
            String method = callee.path().get(callee.path().size() - 1);
            if (!ALLOWED_CALL_METHODS.contains(method)) {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的方法调用: '" + method + "'"
                        + "（不在 SimpleTS 内置白名单，参见 SimpleTS.md §8.1）"));
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

依据 `docs/dsl/SimpleTS.md §7.1`。**RFC-0031 同步**：字段 type 是 5 个 Variant 之一，
需新增"object/list/map 字段不可继续访问内部属性"校验（RFC-0031 §3.5.2 MVP 约束）。

```java
package com.orule.dsl.validate;

import com.orule.common.model.type.Type;
import com.orule.common.model.type.PrimitiveType;
import com.orule.common.model.type.EnumType;
import com.orule.common.model.type.ObjectType;
import com.orule.common.model.type.ListType;
import com.orule.common.model.type.MapType;
import com.orule.dsl.ast.*;
import com.orule.dsl.DomainMeta;
import com.orule.dsl.error.CompileError;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FieldValidator {

    /** 表达式嵌套深度上限（与 SimpleTS.md §7.1 "表达式嵌套过深" 一致） */
    private static final int MAX_EXPR_DEPTH = 32;

    private final List<CompileError> errors = new ArrayList<>();

    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<CompileError> getErrors() { return List.copyOf(errors); }

    public void validate(Program program, DomainMeta meta) {
        new NodeVisitor<Void>().visit(program, node -> {
            if (node instanceof MemberAccess ma) {
                validateMemberAccess(ma, meta);
            } else if (node instanceof EnumRef er) {
                validateEnumRef(er, meta);
            } else if (node instanceof AssignStmt assign) {
                validateWritable(assign, meta);
            }
            return null;
        });
    }

    /**
     * 校验 {@link MemberAccess} 链：
     * <ol>
     *   <li>root 必须在 {@link DomainMeta#context()} 中；</li>
     *   <li>每跳字段必须存在于当前 entity；</li>
     *   <li>中间跳的字段必须是 {@link ObjectType} 才能继续访问内部属性
     *       （RFC-0031 §3.5.2 MVP 嵌套约束：list/map 也不能下钻）；</li>
     *   <li>最后一跳的字段类型必须是 primitive / enum / object（与 SimpleTS §6
     *       MemberAccess 语义一致，不能是 list/map 的元素访问）。</li>
     * </ol>
     */
    private void validateMemberAccess(MemberAccess ma, DomainMeta meta) {
        // 1. root 必须在 context 中
        DomainMeta.ContextVar contextVar = meta.context().stream()
                .filter(c -> c.name().equals(ma.root()))
                .findFirst()
                .orElse(null);

        if (contextVar == null) {
            errors.add(new CompileError(ma.line(), ma.column(),
                "未声明的标识符: '" + ma.root() + "'"
                    + "（所有入口变量必须在 DomainMeta.context 中声明）"));
            return;
        }

        // 2. 从 context 的 objectType.objectCode 起步
        DomainMeta.EntityDef entity = findEntity(meta, contextVar.type().objectCode());

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

            boolean isLast = (i == ma.path().size() - 1);

            // 3. 最后一跳：检查类型必须是可作"值"的类型（primitive/enum/object）
            //    list/map 字段在 SimpleTS 表达式中不能直接访问元素（MVP 嵌套约束）。
            if (isLast) {
                Type t = field.type();
                if (t instanceof ListType || t instanceof MapType) {
                    errors.add(new CompileError(ma.line(), ma.column(),
                        "字段 '" + fieldName + "' 是 " + kindName(t)
                            + " 类型，SimpleTS 不支持直接访问内部元素"
                            + "（参见 RFC-0031 §3.5.2 MVP 嵌套约束）"));
                    return;
                }
                // primitive / enum / object 都允许作值
            } else {
                // 4. 非最后一跳：必须是 object 才能继续
                if (!(field.type() instanceof ObjectType ot)) {
                    errors.add(new CompileError(ma.line(), ma.column(),
                        "字段 '" + fieldName + "' 是 " + kindName(field.type())
                            + " 类型，不能继续访问内部属性"
                            + "（参见 RFC-0031 §3.5.2 MVP 嵌套约束）"));
                    return;
                }
                entity = findEntity(meta, ot.objectCode());
            }
        }
    }

    /**
     * 校验 {@link AssignStmt} 左值的最后一个字段是否 writable（DomainMeta 控制）。
     */
    private void validateWritable(AssignStmt assign, DomainMeta meta) {
        MemberAccess target = assign.target();
        DomainMeta.ContextVar contextVar = meta.context().stream()
                .filter(c -> c.name().equals(target.root()))
                .findFirst()
                .orElse(null);
        if (contextVar == null) return; // 已由 validateMemberAccess 报错

        DomainMeta.EntityDef entity = findEntity(meta, contextVar.type().objectCode());
        for (int i = 0; i < target.path().size(); i++) {
            String fieldName = target.path().get(i);
            DomainMeta.EntityField field = entity.fields().stream()
                    .filter(f -> f.name().equals(fieldName))
                    .findFirst()
                    .orElse(null);
            if (field == null) return;

            boolean isLast = (i == target.path().size() - 1);
            if (isLast && !field.writable()) {
                errors.add(new CompileError(target.line(), target.column(),
                    "字段 '" + fieldName + "' 是只读字段，不可赋值"
                        + "（参见 SimpleTS.md §7.1 "字段只读" 校验项）"));
                return;
            }

            if (!isLast && field.type() instanceof ObjectType ot) {
                entity = findEntity(meta, ot.objectCode());
            }
        }
    }

    /**
     * 校验 {@link EnumRef}：枚举类型必须存在，且取值在合法 values 中。
     *
     * <p>枚举定义已 RFC-0031 内联到字段的 {@code type.kind === 'enum'}：
     * 需要遍历所有 entity 的所有 field，找到 enumCode 匹配的 EnumType.values。
     */
    private void validateEnumRef(EnumRef er, DomainMeta meta) {
        Optional<EnumType> matchedEnum = meta.entities().stream()
                .flatMap(e -> e.fields().stream())
                .map(f -> f.type())
                .filter(t -> t instanceof EnumType)
                .map(t -> (EnumType) t)
                .filter(et -> et.enumCode().equals(er.enumId()))
                .findFirst();

        if (matchedEnum.isEmpty()) {
            errors.add(new CompileError(er.line(), er.column(),
                "未定义的枚举: '" + er.enumId() + "'"
                    + "（DomainMeta 中未找到 enumCode='" + er.enumId() + "' 的字段）"));
            return;
        }

        EnumType enumDef = matchedEnum.get();
        boolean valueExists = enumDef.values().stream()
                .anyMatch(v -> v.code().equals(er.value()));
        if (!valueExists) {
            errors.add(new CompileError(er.line(), er.column(),
                "枚举 " + er.enumId() + " 不含值 '" + er.value() + "'"));
        }
    }

    private DomainMeta.EntityDef findEntity(DomainMeta meta, String entityId) {
        return meta.entities().stream()
                .filter(e -> e.id().equals(entityId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "DomainMeta 引用了不存在的 entity: " + entityId
                        + "（数据一致性问题，非 SimpleTS 源代码错误）"));
    }

    private static String kindName(Type t) {
        if (t instanceof PrimitiveType) return "primitive";
        if (t instanceof EnumType)      return "enum";
        if (t instanceof ObjectType)    return "object";
        if (t instanceof ListType)      return "list";
        if (t instanceof MapType)       return "map";
        return t.getClass().getSimpleName();
    }
}
```

### 3.7 左值检查器

**职责**：检查 `AssignStmt` 的左值在 AST 类型层是合法的 `MemberAccess`
（裸标识符、下标、算术表达式都已被 AST 类型层钉死；这里做二次防御）。

**字段只读校验**已在 §3.6 `FieldValidator.validateWritable` 完成；本类不重复。

```java
package com.orule.dsl.validate;

import com.orule.dsl.ast.*;
import com.orule.dsl.error.CompileError;

import java.util.ArrayList;
import java.util.List;

public class LValueChecker {

    private final List<CompileError> errors = new ArrayList<>();

    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<CompileError> getErrors() { return List.copyOf(errors); }

    public void check(Program program) {
        new NodeVisitor<Void>().visit(program, node -> {
            if (node instanceof AssignStmt assign) {
                // AST 类型层已经钉死 AssignStmt.target: MemberAccess，
                // 这里做"运行时"二次防御（应对 AST 构造器被误用的场景）。
                if (!(assign.target() instanceof MemberAccess)) {
                    errors.add(new CompileError(assign.line(), assign.column(),
                        "禁止给算术表达式赋值（仅允许上下文变量属性赋值）"));
                }
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
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import ts.SyntaxKind;
import ts.Node;

import java.io.IOException;

/**
 * 通过 GraalJS 调用 TypeScript Compiler API。
 * 把 SimpleTS 源码解析为 TS AST（JSON 形态），再转 Java 对象。
 *
 * <p><b>安全</b>：GraalJS Context 必须以 <b>最小权限</b> 配置
 * （{@code HostAccess.NONE} + {@code allowCreateThread(false)} + 禁止 IO），
 * 否则 GraalJS 本身就是一个侧信道攻击面（沙箱被一个错误配置击穿）。
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
            // 递归遍历子节点（property / element 区分）
            // ... (实现略)
            return result;
        }
        parse;
        """;

    private final Context jsContext;
    private final Value parseFn;  // 缓存 parse 函数，避免每次重新加载

    public TsAstParser() {
        this.jsContext = Context.newBuilder("js")
                .allowHostAccess(HostAccess.NONE)        // 禁止 Java 反射访问
                .allowHostClassLookup(null)               // 禁止 host class lookup
                .allowCreateThread(false)                  // 禁止创建线程
                .allowIO(false)                            // 禁止文件 / 网络 IO
                .allowNativeAccess(false)                  // 禁止 native access
                .build();
        // 仅 require TypeScript，不暴露任何 Java 绑定
        this.jsContext.eval("js", "const ts = require('typescript');");
        this.parseFn = jsContext.eval(Source.newBuilder("js", TS_PARSER_JS, "ts-parser.js").buildLiteral());
    }

    public Node parse(String source) {
        Value result = parseFn.execute(source);
        return convertFromJson(result);
    }

    public void close() {
        jsContext.close();
    }

    // ... JSON → Java Node 转换
}
```

> **说明**：
> 1. MVP 采用 GraalJS 桥接 TypeScript Compiler API（参考 `05-技术模型 §5.x` Groovy 沙箱方案），
>    避免重复造 TS 解析器的轮子。
> 2. parseFn 在构造时一次性编译缓存，每次 {@code parse(source)} 只传入 source、复用 parseFn，
>    性能开销 ≈ 一次函数调用（详见 §6 性能风险）。
> 3. 如未来切到 jts / ANTLR，本类是唯一改动点（适配器模式）。

---

## 4. 影响面

- 新增整个 `com.orule.dsl` 包（~15 个类）
- 不涉及数据库表
- 不涉及外部 API
- 依赖 GraalJS + TypeScript npm 包

---

## 3.10 SimpleTS 白名单（单一来源）

> **新增小节**：解决 RFC-0018 §3.5 与 RFC-0020 §3.2 白名单方法表重复、且不一致的问题。

```java
package com.orule.dsl;

/**
 * SimpleTS 内置白名单：白名单方法、保留字、允许的 Type 形态。
 *
 * <p><b>单一来源原则</b>：
 * <ul>
 *   <li>RFC-0018 §3.5 WhitelistPruner 用 {@link #allowedMethodNames()} 检查方法调用；</li>
 *   <li>RFC-0020 §3.2 SandboxConfig.defaultAllowedMethods() 用
 *       {@link #allowedMethodSignatures()} 配置 Groovy 沙箱。</li>
 * </ul>
 *
 * <p>两个方法必须保持语义一致：方法名集合 ⊆ 方法签名集合的前缀。
 */
public final class SimpleTSWhitelist {

    /** 允许的内置方法（方法名，不含类前缀）。Groovy 沙箱按签名匹配。 */
    public static Set<String> allowedMethodNames() {
        return Set.of(
            // 数学
            "abs", "min", "max", "floor", "ceil", "round", "sqrt", "pow",
            // 字符串
            "length", "startsWith", "endsWith", "includes",
            "toUpperCase", "toLowerCase", "trim", "substring", "indexOf",
            "replace", "split", "valueOf",
            // 数字解析
            "parseInt", "parseLong", "parseDouble",
            // 集合
            "size", "isEmpty", "get", "contains",
            // 日期
            "now", "getFullYear", "getMonthValue", "getDayOfMonth",
            "plusDays", "minusDays", "isAfter", "isBefore",
            "getHour", "getMinute"
        );
    }

    /** 允许的内置方法（完全限定签名）。Groovy 沙箱的 deny-unless-allow 依据。 */
    public static Set<String> allowedMethodSignatures() {
        return Set.of(
            // 数学
            "java.lang.Math.abs(double)", "java.lang.Math.abs(int)",
            "java.lang.Math.min(double,double)", "java.lang.Math.min(int,int)",
            "java.lang.Math.max(double,double)", "java.lang.Math.max(int,int)",
            "java.lang.Math.floor(double)", "java.lang.Math.ceil(double)",
            "java.lang.Math.round(double)", "java.lang.Math.round(float)",
            "java.lang.Math.sqrt(double)", "java.lang.Math.pow(double,double)",
            // 字符串
            "java.lang.String.length()", "java.lang.String.startsWith(java.lang.String)",
            "java.lang.String.endsWith(java.lang.String)",
            "java.lang.String.contains(java.lang.CharSequence)",
            "java.lang.String.toUpperCase()", "java.lang.String.toLowerCase()",
            "java.lang.String.trim()",
            "java.lang.String.substring(int,int)", "java.lang.String.substring(int)",
            "java.lang.String.indexOf(java.lang.String)",
            "java.lang.String.replace(java.lang.CharSequence,java.lang.CharSequence)",
            "java.lang.String.split(java.lang.String)",
            "java.lang.String.valueOf(...)",
            // 数字解析
            "java.lang.Integer.parseInt(java.lang.String)",
            "java.lang.Long.parseLong(java.lang.String)",
            "java.lang.Double.parseDouble(java.lang.String)",
            // 集合
            "java.util.List.size()", "java.util.List.isEmpty()", "java.util.List.get(int)",
            "java.util.Map.size()", "java.util.Map.isEmpty()", "java.util.Map.get(java.lang.Object)",
            "java.util.Set.size()", "java.util.Set.contains(java.lang.Object)",
            // 日期
            "java.time.LocalDate.now()",
            "java.time.LocalDateTime.now()",
            "java.time.LocalDate.getYear()", "java.time.LocalDate.getMonthValue()",
            "java.time.LocalDate.getDayOfMonth()",
            "java.time.LocalDate.plusDays(long)", "java.time.LocalDate.minusDays(long)",
            "java.time.LocalDate.isAfter(java.time.chrono.ChronoLocalDate)",
            "java.time.LocalDate.isBefore(java.time.chrono.ChronoLocalDate)",
            "java.time.LocalDateTime.getHour()", "java.time.LocalDateTime.getMinute()"
        );
    }

    /** 不允许的方法（即使能编译也禁止调用，含绕过沙箱的高危 API） */
    public static Set<String> forbiddenMethods() {
        return Set.of(
            "java.lang.System.exit", "java.lang.Runtime.getRuntime",
            "java.lang.Class.forName",
            "java.io.File.<init>", "java.net.Socket.<init>", "java.net.URL.<init>",
            "java.lang.ProcessBuilder.<init>", "java.lang.ProcessBuilder.start",
            "java.lang.Thread.start", "java.lang.Thread.sleep",
            "groovy.lang.GroovyShell.evaluate", "groovy.lang.GroovyShell.parse",
            "groovy.lang.MetaClass.setProperty",
            "java.lang.ClassLoader.loadClass"
        );
    }

    /** 不允许 import 的包前缀（与 RFC-0020 §3.2 forbiddenPackages 保持一致） */
    public static Set<String> forbiddenPackages() {
        return Set.of(
            "java.lang.reflect.", "java.io.", "java.nio.file.",
            "java.net.", "java.rmi.", "java.lang.invoke.",
            "sun.", "jdk.internal."
        );
    }

    private SimpleTSWhitelist() {}
}
```

> **维护约定**：当 RFC-0018 / RFC-0020 需要新增方法时，**先改本类的 4 个 Set**，
> WhitelistPruner 与 SandboxConfig 在构造时引用本类，禁止在两处各写一份。
> CI 会在 RFC-0020 沙箱测试中断言"方法签名集合 ⊇ 方法名集合前缀映射"，防止漂移。

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
| GraalJS + TypeScript 性能 | 🟡 中 | 编译产物缓存（规则版本不变则复用）+ parseFn 单次编译缓存 |
| GraalJS 集成复杂度 | 🟡 中 | 用官方 polyglot 包装器；二期可考虑换 jts 或 ANTLR |
| GraalJS 沙箱配置错误（allowAllAccess） | 🔴 高 | **强制使用 HostAccess.NONE + allowIO(false) + allowCreateThread(false)**（§3.9） |
| TS 解析器未捕获边界场景 | 🟢 低 | 大量测试用例覆盖（参考 §5） |
| 白名单跨 RFC 漂移 | 🟡 中 | §3.10 SimpleTSWhitelist 单一来源；CI 断言方法名集合 ⊆ 签名集合 |

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

- 上游：RFC-0015（元数据 API，提供 DomainMeta 拼装数据源）、**RFC-0031（Type 系统 5 Variant + enum 内联）**
- 下游：RFC-0019（SimpleTS→Groovy 代码生成器）、RFC-0023（NL→SimpleTS）
- 平级：RFC-0020（Groovy 沙箱）— **共用 §3.10 SimpleTSWhitelist 白名单单一来源**
- ADR：**ADR-003 中间态 DSL 采用 SimpleTS**、**ADR-006 规则源语言采用 SimpleTS**、**ADR-009 SimpleTS 为中心的星型转换架构**
- 规范：[docs/dsl/SimpleTS.md](../../dsl/SimpleTS.md)
