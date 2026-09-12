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
├── SimpleTSParser.java        # 入口（RFC-0018-bis：parse(source, RuleType)）
├── SimpleTSWhitelist.java     # 白名单单一来源（与 RFC-0020 共用）

packages/orule-common/src/main/java/com/orule/common/entity/
├── RuleType.java              # 规则类型（RFC-0018-bis 新增）
├── ArgumentType.java          # 入参定义（RFC-0018-bis 新增）
├── ReturnType.java           # 出参定义（RFC-0018-bis 新增）
├── RuleTypeFuntion.java      # 规则函数白名单（RFC-0018-bis 新增）
└── RuleTypeExcludeFuntion.java  # 排除函数（审计注释，RFC-0018-bis 新增）

packages/orule-common/src/main/java/com/orule/dsl/ast/  # SimpleTS-AST 节点
packages/orule-common/src/main/java/com/orule/dsl/error/  # 错误模板
packages/orule-common/src/main/java/com/orule/dsl/validate/  # 校验器
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
     *
     * <p><b>RFC-0018-bis 修订</b>：入口从 {@code parse(source, DomainMeta)} 收窄为
     * {@code parse(source, RuleType)}。RuleType 持有 arguments / returnType / functionTypes，
     * DomainMeta 概念已废弃。
     *
     * @param source   SimpleTS 源码
     * @param ruleType 该条规则所属的 RuleType（含 arguments / returnType / functionTypes）
     * @return         SimpleTS-AST
     * @throws TssCompileError 编译失败（含完整错误列表）
     */
    public Program parse(String source, RuleType ruleType) {
        // 1. 词法 / 语法（基于 TypeScript Compiler API 通过 GraalJS 调用）
        TsSourceFile tsAst = parseToTsAst(source);

        // 2. 白名单剪枝（白名单取自 ruleType.functionTypes ∪ SimpleTSWhitelist 内置方法）
        Program program = pruner.convert(tsAst, ruleType);
        if (pruner.hasErrors()) {
            throw new TssCompileError(pruner.getErrors());
        }

        // 3. 标识符作用域解析（从 ruleType.arguments 起算）
        resolver.resolve(program, ruleType);
        if (resolver.hasErrors()) {
            throw new TssCompileError(resolver.getErrors());
        }

        // 4. 字段存在性 + 枚举值校验
        if (ruleType.getValidatable()) {
            fieldValidator.validate(program, ruleType);
            if (fieldValidator.hasErrors()) {
                throw new TssCompileError(fieldValidator.getErrors());
            }

            // 5. 类型对齐
            typeChecker.check(program, ruleType);
            if (typeChecker.hasErrors()) {
                throw new TssCompileError(typeChecker.getErrors());
            }
        }

        // 6. 左值合法性（与 meta 解耦，签名不含 ruleType）
        lvalueChecker.check(program);
        if (lvalueChecker.hasErrors()) {
            throw new TssCompileError(lvalueChecker.getErrors());
        }

        return program;
    }
}
```

### 3.3 删除

> **RFC-0018-bis 修订**：旧的 `DomainMeta` 模型已废弃。RuleSet 本身就代表一个领域
>（"领域下高内聚的规则集合"），DSL 编译入口收窄为 `parse(source, RuleType)`。
>
> 详细定义见 §3.11。
>
> 删除内容：
> - `DomainMeta` record（含 `objects / functions / context` 字段）
> - `DomainMeta.ObjectTypeDef` / `AttributeDef` / `EnumValue` / `FunctionDef` / `ContextVar`
> - `MemberAccess.root` 注释中的 "DomainMeta.context" 引用
> - `WhitelistPruner.convert(tsAst, DomainMeta)` 方法签名
> - `FieldValidator.validateWritable` / `validateEnumRef` 中对 `DomainMeta.entities()` 的调用

### 3.3.1 RuleSet（元数据实体 — 复用现有 entity.RuleSet）

> **复用说明**：仓库已有 `entity.RuleSet`（`rule_set` 表），对应 RuleSetType 概念。
> RuleSet 持有 `domain`（ManyToOne → `DomainType`）和 `rules`（OneToMany → `Rule`），
> 通过新增的 `RuleType` 中间层建立规则类型元数据。

### 3.3.2 RuleType（元数据实体 — 新增）

> **RFC-0018-bis 新增**：规则类型。RuleType 本质上是一个空函数，
> 业务人员填写的 SimpleTS 源码 = 函数体 = 规则。

```java
package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * RuleType：规则类型（RFC-0018-bis §3.3.2）。
 *
 * <p>RuleType 本质上是一个空函数，业务人员填写的 SimpleTS 源码即函数体。
 * 主要定义：
 * <ol>
 *   <li>函数的入参（arguments）和出参（returnType）；</li>
 *   <li>函数体内可调用的 SDK 白名单（functionTypes，声明式）。</li>
 * </ol>
 *
 * <p>不变式（由拼装层保证）：
 * <ul>
 *   <li>RuleType.arguments[*].objectType ∈ enclosing RuleSet.objectTypes</li>
 *   <li>RuleType.returnType.objectType ∈ enclosing RuleSet.objectTypes ∪ {VOID}</li>
 *   <li>RuleType.functionTypes ⊆ enclosing RuleSet.functionTypes</li>
 * </ul>
 */
@Entity
@Table(name = "rule_type")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RuleType {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_set_id", nullable = false)
    private RuleSet ruleSet;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * validatable（默认 true）。
     * 为 false 时跳过 RFC-0018 §3.6 字段校验（实验用临时豁免）。
     */
    @Column(name = "is_validatable", nullable = false)
    @Builder.Default
    private Boolean validatable = true;

    /**
     * 入参定义（ArgumentType）。
     * 对应函数的入参列表。
     */
    @OneToMany(mappedBy = "ruleType", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ArgumentType> arguments = new ArrayList<>();

    /**
     * 出参定义（ReturnType）。
     * MVP 一条 RuleType 仅一个 Return。
     */
    @OneToOne(mappedBy = "ruleType", cascade = CascadeType.ALL, orphanRemoval = true)
    private ReturnType returnType;

    /**
     * 允许调用的函数 SDK 白名单（声明式，不隐式继承）。
     * 为空表示"无 SDK 可用"。
     */
    @OneToMany(mappedBy = "ruleType", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RuleTypeFuntion> functionTypes = new ArrayList<>();

    /**
     * 审计注释：应被排除但仍被允许的函数列表。
     * 不变量：functionTypes ∩ excludeFunctionTypes = ∅
     * DSL 编译期仅产生 warning，不报错。
     */
    @OneToMany(mappedBy = "ruleType", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RuleTypeExcludeFuntion> excludeFunctionTypes = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

### 3.3.3 ArgumentType（元数据实体 — 新增）

> **RFC-0018-bis 新增**：入参定义（类比函数参数）。

```java
package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * ArgumentType：规则类型的入参定义（RFC-0018-bis §3.3.3）。
 *
 * <p>对应函数的参数列表。每个 ArgumentType 描述一个入口变量的类型约束。
 */
@Entity
@Table(name = "rule_argument")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ArgumentType {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_type_id", nullable = false)
    private RuleType ruleType;

    /**
     * 参数名（业务语义），对应 SimpleTS 中的入口变量名。
     * 例如："customer"、"order"。
     */
    @Column(name = "program_code", nullable = false, length = 64)
    private String programCode;

    /**
     * 参数的领域对象类型。
     * 必须是 enclosing RuleSet.objectTypes 中的元素。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "object_type_id", nullable = false)
    private ObjectType objectType;

    /**
     * null 是否合法。
     * 为 true 时生成的 Groovy 代码需有非空判断。
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean nullable = false;

    /**
     * 是否可被 SimpleTS 赋值。
     * 为 true 时该参数可以作为 AssignStmt.target。
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean writable = false;

    /** 排序序号（决定参数顺序） */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
```

### 3.3.4 ReturnType（元数据实体 — 新增）

> **RFC-0018-bis 新增**：出参定义（类比函数返回）。
>
> `objectType = null` 表示 VOID（`ObjectType.Kind.VOID`，内置，不入库）。

```java
package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * ReturnType：规则类型的出参定义（RFC-0018-bis §3.3.4）。
 *
 * <p>对应函数的返回值。
 * <ul>
 *   <li>objectType = null → VOID（ObjectType.Kind.VOID，内置）</li>
 *   <li>objectType ≠ null 且 nullable = false → 返回 null 视为校验失败</li>
 *   <li>objectType ≠ null 且 nullable = true → 返回 null 合法</li>
 * </ul>
 */
@Entity
@Table(name = "rule_return")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ReturnType {

    @Id
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_type_id", nullable = false, unique = true)
    private RuleType ruleType;

    /**
     * 返回值类型。
     * null 表示 VOID（无返回值）。
     * 必须是 enclosing RuleSet.objectTypes 中的元素，或 VOID。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "object_type_id", nullable = true)
    private ObjectType objectType;

    /** nullable = false 时返回 null 视为校验失败 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean nullable = false;

    /** 排序序号（MVP 一条 RuleType 仅一个 Return，始终为 0） */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
```

### 3.3.5 RuleTypeFuntion / RuleTypeExcludeFuntion（元数据实体 — 新增）

> **RFC-0018-bis 新增**：规则类型的函数白名单关联表。

```java
package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * RuleTypeFuntion：规则类型允许调用的函数 SDK（RFC-0018-bis §3.3.5）。
 *
 * <p>声明式白名单。functionTypes 为空表示"无 SDK 可用"。
 * 不隐式继承 RuleSet.functionTypes。
 */
@Entity
@Table(name = "rule_type_funtion")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RuleTypeFuntion {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_type_id", nullable = false)
    private RuleType ruleType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funtion_type_id", nullable = false)
    private FuntionType funtionType;      // 重命名自 FunctionLib
}

/**
 * RuleTypeExcludeFuntion：规则类型的排除函数 SDK（RFC-0018-bis §3.3.5）。
 *
 * <p>审计注释字段。DSL 编译期仅产生 warning。
 * 不变量：RuleType.functionTypes ∩ RuleType.excludeFunctionTypes = ∅
 */
@Entity
@Table(name = "rule_type_exclude_funtion")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RuleTypeExcludeFuntion {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_type_id", nullable = false)
    private RuleType ruleType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funtion_type_id", nullable = false)
    private FuntionType funtionType;
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
    String root,           // 必须是 RuleType.arguments 中的入口变量
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
import com.orule.common.entity.RuleType;
import com.orule.dsl.ast.*;
import com.orule.dsl.error.CompileError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ts.SyntaxKind;
import ts.Node;

/**
 * 白名单剪枝：把 TS AST 转换为 SimpleTS-AST。
 *
 * <p><b>RFC-0018-bis 修订</b>：参数从 DomainMeta 改为 RuleType。
 * 白名单来源 = RuleType.functionTypes ∪ SimpleTSWhitelist 内置方法。
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
     */
    private static final int MAX_STATEMENT_COUNT = 200;

    private final List<CompileError> errors = new ArrayList<>();
    private int statementCount = 0;

    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<CompileError> getErrors() { return List.copyOf(errors); }

    public Program convert(Node tsAst, RuleType ruleType) {
        List<Node> body = new ArrayList<>();
        for (Node stmt : tsAst.statements) {
            Node converted = convertStatement(stmt, ruleType);
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

    private Node convertStatement(Node ts, RuleType ruleType) {
        return switch (ts.kind) {
            case "IfStatement" -> convertIf(ts, ruleType);
            case "ForStatement" -> convertFor(ts, ruleType);
            case "VariableStatement" -> convertDeclare(ts, ruleType);
            case "ExpressionStatement" -> {
                Node expr = convertExpr(ts.expression, ruleType);
                if (expr instanceof Expr e) {
                    yield new ExprStmt(e, ts.line, ts.column);
                }
                yield null;
            }
            case "Block" -> convertBlock(ts, ruleType);
            default -> {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的语句类型: " + ts.kind
                        + "（参见 docs/dsl/SimpleTS.md §5 砍掉的语法）"));
                yield null;
            }
        };
    }

    private Node convertIf(Node ts, RuleType ruleType) {
        List<IfStmt.Branch> branches = new ArrayList<>();
        Node current = ts;

        while (current.kind.equals("IfStatement")) {
            Expr test = (Expr) convertExpr(current.expression, ruleType);
            Block body = (Block) convertStatement(current.thenStatement, ruleType);
            branches.add(new IfStmt.Branch(asBinary(test), body));
            current = current.elseStatement;
            if (current == null) break;
        }

        Block elseBody = null;
        if (current != null && current.kind.equals("Block")) {
            elseBody = (Block) convertStatement(current, ruleType);
        }

        return new IfStmt(branches, elseBody, ts.line, ts.column);
    }

    private Node convertExpr(Node ts, RuleType ruleType) {
        return switch (ts.kind) {
            case "BinaryExpression" -> {
                if (!ALLOWED_BINARY_OPS.contains(ts.operatorToken)) {
                    errors.add(new CompileError(ts.line, ts.column,
                        "不允许的二元运算符: " + ts.operatorToken
                            + "（参见 SimpleTS.md §5 砍掉的语法）"));
                    yield null;
                }
                Expr left = (Expr) convertExpr(ts.left, ruleType);
                Expr right = (Expr) convertExpr(ts.right, ruleType);
                yield new BinaryExpr(ts.operatorToken, left, right, ts.line, ts.column);
            }
            case "PrefixUnaryExpression" -> {
                if (!ALLOWED_UNARY_OPS.contains(ts.operator)) {
                    errors.add(new CompileError(ts.line, ts.column,
                        "不允许的一元运算符: " + ts.operator));
                    yield null;
                }
                Expr arg = (Expr) convertExpr(ts.operand, ruleType);
                yield new UnaryExpr(ts.operator, arg, ts.line, ts.column);
            }
            case "PropertyAccessExpression" -> convertMemberAccess(ts, ruleType);
            case "CallExpression" -> convertCall(ts, ruleType);
            case "NumericLiteral", "StringLiteral", "NoSubstitutionTemplateLiteral" ->
                new Literal(parseLiteral(ts), ts.line, ts.column);
            case "TrueKeyword" -> new Literal(true, ts.line, ts.column);
            case "FalseKeyword" -> new Literal(false, ts.line, ts.column);
            case "NullKeyword" -> new Literal(null, ts.line, ts.column);
            case "ThisKeyword", "SuperKeyword" -> {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的访问: '" + ts.kind + "'（SimpleTS 不支持 this/super）"));
                yield null;
            }
            case "ParenthesizedExpression" -> convertExpr(ts.expression, ruleType);
            default -> {
                errors.add(new CompileError(ts.line, ts.column,
                    "不允许的表达式类型: " + ts.kind
                        + "（参见 SimpleTS.md §5 砍掉的语法）"));
                yield null;
            }
        };
    }

    private MemberAccess convertMemberAccess(Node ts, RuleType ruleType) {
        List<String> path = new ArrayList<>();
        Node current = ts;
        while (current.kind.equals("PropertyAccessExpression")) {
            path.add(0, current.name.text);
            current = current.expression;
        }

        if ("ThisKeyword".equals(current.kind) || "SuperKeyword".equals(current.kind)) {
            errors.add(new CompileError(ts.line, ts.column,
                "不允许的访问: '" + current.kind + "'（SimpleTS 不支持 this/super）"));
            return null;
        }

        String root = current.text;
        return new MemberAccess(root, path, ts.line, ts.column);
    }

    private CallExpr convertCall(Node ts, RuleType ruleType) {
        MemberAccess callee = convertMemberAccess(ts.expression, ruleType);
        if (callee == null) return null;

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
            args.add((Expr) convertExpr(arg, ruleType));
        }
        return new CallExpr(callee, args, ts.line, ts.column);
    }

    // ... 其他转换方法
}
```
### 3.6 字段校验器

依据 `docs/dsl/SimpleTS.md §7.1`。**RFC-0018-bis 同步**：参数从 DomainMeta 改为 RuleType；
字段校验锚点从"领域全局 context"下沉到"RuleType.arguments"；
枚举值从 `RuleSet.objectTypes`（kind=ENUM）查找。

```java
package com.orule.dsl.validate;

import com.orule.common.model.type.Type;
import com.orule.common.model.type.PrimitiveType;
import com.orule.common.model.type.ObjectType;
import com.orule.common.model.type.ListType;
import com.orule.common.model.type.MapType;
import com.orule.common.entity.RuleType;
import com.orule.common.entity.ArgumentType;
import com.orule.dsl.ast.*;
import com.orule.dsl.error.CompileError;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FieldValidator {

    private static final int MAX_EXPR_DEPTH = 32;
    private final List<CompileError> errors = new ArrayList<>();

    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<CompileError> getErrors() { return List.copyOf(errors); }

    public void validate(Program program, RuleType ruleType) {
        new NodeVisitor<Void>().visit(program, node -> {
            if (node instanceof MemberAccess ma) {
                validateMemberAccess(ma, ruleType);
            } else if (node instanceof EnumRef er) {
                validateEnumRef(er, ruleType);
            } else if (node instanceof AssignStmt assign) {
                validateWritable(assign, ruleType);
            }
            return null;
        });
    }

    private void validateMemberAccess(MemberAccess ma, RuleType ruleType) {
        // 1. root 必须在 ruleType.arguments 中
        ArgumentType arg = ruleType.getArguments().stream()
                .filter(a -> a.getProgramCode().equals(ma.root()))
                .findFirst()
                .orElse(null);

        if (arg == null) {
            errors.add(new CompileError(ma.line(), ma.column(),
                "未声明的标识符: '" + ma.root()
                    + "'（所有入口变量必须在 RuleType.arguments 中声明）"));
            return;
        }

        ObjectType current = arg.getObjectType();
        for (int i = 0; i < ma.path().size(); i++) {
            String fieldName = ma.path().get(i);
            var field = current.getAttributes().stream()
                    .filter(f -> f.getProgramCode().equals(fieldName))
                    .findFirst()
                    .orElse(null);

            if (field == null) {
                errors.add(new CompileError(ma.line(), ma.column(),
                    "对象 " + current.getProgramCode() + " 上不存在属性 '" + fieldName + "'"));
                return;
            }

            boolean isLast = (i == ma.path().size() - 1);
            Type t = field.getType();

            if (isLast) {
                if (t instanceof ListType || t instanceof MapType) {
                    errors.add(new CompileError(ma.line(), ma.column(),
                        "属性 '" + fieldName + "' 是 " + kindName(t)
                            + " 类型，SimpleTS 不支持直接访问内部元素"));
                    return;
                }
            } else {
                if (!(t instanceof ObjectType ot)) {
                    errors.add(new CompileError(ma.line(), ma.column(),
                        "属性 '" + fieldName + "' 是 " + kindName(t)
                            + " 类型，不能继续访问内部属性"));
                    return;
                }
                current = findObjectType(ruleType, ot);
            }
        }
    }

    private void validateWritable(AssignStmt assign, RuleType ruleType) {
        MemberAccess target = assign.target();
        ArgumentType arg = ruleType.getArguments().stream()
                .filter(a -> a.getProgramCode().equals(target.root()))
                .findFirst()
                .orElse(null);
        if (arg == null) return;

        ObjectType current = arg.getObjectType();
        for (int i = 0; i < target.path().size(); i++) {
            String fieldName = target.path().get(i);
            var field = current.getAttributes().stream()
                    .filter(f -> f.getProgramCode().equals(fieldName))
                    .findFirst()
                    .orElse(null);
            if (field == null) return;

            boolean isLast = (i == target.path().size() - 1);
            if (isLast && !field.getWritable()) {
                errors.add(new CompileError(target.line(), target.column(),
                    "属性 '" + fieldName + "' 是只读属性，不可赋值"));
                return;
            }
            if (!isLast && field.getType() instanceof ObjectType ot) {
                current = findObjectType(ruleType, ot);
            }
        }
    }

    private void validateEnumRef(EnumRef er, RuleType ruleType) {
        var enumObjects = ruleType.getRuleSet().getDomain().getRuleSets().stream()
                .flatMap(rs -> rs.getObjectTypes().stream())
                .filter(o -> o.getKind() == ObjectType.Kind.ENUM)
                .toList();

        Optional<ObjectType> matchedEnum = enumObjects.stream()
                .filter(o -> o.getProgramCode().equals(er.enumId()))
                .findFirst();

        if (matchedEnum.isEmpty()) {
            errors.add(new CompileError(er.line(), er.column(),
                "未定义的枚举: '" + er.enumId() + "'"));
            return;
        }

        ObjectType enumDef = matchedEnum.get();
        boolean valueExists = enumDef.getEnumValues().stream()
                .anyMatch(v -> v.getCode().equals(er.value()));
        if (!valueExists) {
            errors.add(new CompileError(er.line(), er.column(),
                "枚举 " + er.enumId() + " 不含值 '" + er.value() + "'"));
        }
    }

    private ObjectType findObjectType(RuleType ruleType, ObjectType ot) {
        return ruleType.getRuleSet().getObjectTypes().stream()
                .filter(o -> o.getProgramCode().equals(ot.getProgramCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "RuleType 引用的 objectCode='" + ot.getProgramCode() + "' 在 RuleSet 中不存在"));
    }

    private static String kindName(Type t) {
        if (t instanceof PrimitiveType) return "primitive";
        if (t instanceof ObjectType ot) {
            return ot.getKind() == ObjectType.Kind.ENUM ? "enum" : "object";
        }
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

- 新增 `com.orule.dsl` 包（~15 个类）+ `com.orule.common.entity` 下 5 个新实体
- 新增 5 张数据库表：`rule_type` / `rule_argument` / `rule_return` / `rule_type_funtion` / `rule_type_exclude_funtion`
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
    RuleType ruleType = TestMetaFactory.orderDiscountRuleType();
    Program ast = parser.parse(source, ruleType);

    assertThat(ast.body()).hasSize(1);
    assertThat(ast.body().get(0)).isInstanceOf(IfStmt.class);
}

@Test
@DisplayName("不允许对象字面量")
void objectLiteral_shouldFail() {
    String source = """
        let x = { a: 1 }
        """;
    RuleType ruleType = TestMetaFactory.simpleRuleType();

    assertThatThrownBy(() -> parser.parse(source, ruleType))
        .isInstanceOf(TssCompileError.class)
        .hasMessageContaining("不允许");
}

@Test
@DisplayName("枚举值非法报错")
void invalidEnumValue_shouldFail() {
    String source = """
        if (customer.tier == CustomerTier.GOD) { ... }
        """;
    RuleType ruleType = TestMetaFactory.orderDiscountRuleType();

    assertThatThrownBy(() -> parser.parse(source, ruleType))
        .isInstanceOf(TssCompileError.class)
        .hasMessageContaining("枚举 CustomerTier 不含值 'GOD'");
}

@Test
@DisplayName("未声明标识符报错")
void undeclaredIdentifier_shouldFail() {
    String source = """
        if (invoice.amount > 0) { ... }
        """;
    RuleType ruleType = TestMetaFactory.orderDiscountRuleType();

    assertThatThrownBy(() -> parser.parse(source, ruleType))
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
1. 创建 com.orule.dsl 包 + AST 节点（~13 个 record）
2. 实现 RuleType / ArgumentType / ReturnType / RuleTypeFuntion / RuleTypeExcludeFuntion 实体
3. 实现 TsAstParser（GraalJS 桥接）
4. 实现 WhitelistPruner（白名单剪枝）
5. 实现 IdentifierResolver（标识符作用域）
6. 实现 FieldValidator（字段 + 枚举，RFC-0018-bis）
7. 实现 TypeChecker（类型对齐）
8. 实现 LValueChecker（左值合法性）
9. 实现 SimpleTSWhitelist（单一来源白名单）
10. 实现错误信息模板
11. 实现 DomainMeta 删除 + RuleSet/RuleType 拼装层
12. 单元测试（80+ 用例）
13. 集成测试（完整 VIP 示例）
14. 性能测试
```

---

## 8. 关联

- 上游：RFC-0015（元数据 API，提供 RuleSet/RuleType 拼装数据源）、**RFC-0031（Type 系统重构）**、**RFC-0032（ObjectType 枚举化 + Type 系统 4 Variant）**
- 下游：RFC-0019（SimpleTS→Groovy 代码生成器）、RFC-0023（NL→SimpleTS）
- 平级：RFC-0020（Groovy 沙箱）— **共用 §3.10 SimpleTSWhitelist 白名单单一来源**
- ADR：**ADR-003 中间态 DSL 采用 SimpleTS**、**ADR-006 规则源语言采用 SimpleTS**、**ADR-009 SimpleTS 为中心的星型转换架构**、**ADR-012 enum 视为 ObjectType 特殊形态**
- 规范：[docs/dsl/SimpleTS.md](../../dsl/SimpleTS.md)

---

## 8.1 RFC-0018-bis 修订日志

| 日期 | 修订内容 |
|------|---------|
| 2026-09-12 | RFC-0018-bis：废弃 DomainMeta；RuleSet 直接作为领域代表；SimpleTSParser 入口改为 `parse(source, RuleType)`；新增 RuleType/ArgumentType/ReturnType/RuleTypeFuntion/RuleTypeExcludeFuntion 实体；FieldValidator 锚点从 DomainMeta.context 下沉到 RuleType.arguments；ObjectType.Kind 新增 VOID（内置） |
