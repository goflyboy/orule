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

> **RFC-0018-bis / RFC-0033 修订**：旧的 `DomainMeta` 模型已废弃。RuleSetType 本身就是领域
> 类型定义（"领域下高内聚的规则集合"的类型模板），DSL 编译入口收窄为 `parse(source, RuleType)`。
>
> **实体层权威定义见 [RFC-0033-元数据管理2-RuleSetType与RuleType.md](RFC-0033-元数据管理2-RuleSetType与RuleType.md)**
> （RuleSetType / RuleType / ArgumentType / ReturnType / RuleTypeFuntion / RuleTypeExcludeFuntion
> / FuntionType 的 DDL + 实体 + API + 不变式，统一在 RFC-0033 维护）。
>
> 删除内容：
> - `DomainMeta` record（含 `objects / functions / context` 字段）
> - `DomainMeta.ObjectTypeDef` / `AttributeDef` / `EnumValue` / `FunctionDef` / `ContextVar`
> - `MemberAccess.root` 注释中的 "DomainMeta.context" 引用
> - `WhitelistPruner.convert(tsAst, DomainMeta)` 方法签名
> - `FieldValidator.validateWritable` / `validateEnumRef` 中对 `DomainMeta.entities()` 的调用

### 3.3.1 RuleType / RuleSetType（仅声明引用 — 详见 RFC-0033）

> 本 RFC 仅在编译期消费 `RuleType` 实体的 4 个字段：
>
> | RFC-0018 读取字段 | 作用 |
> |-------------------|------|
> | `RuleType.arguments` | SimpleTS 入口变量集（context 锚点下沉到这里）|
> | `RuleType.returnType` | 校验返回类型（VOID 时 objectType=null） |
> | `RuleType.functionTypes` | 白名单函数 SDK 集（编译期 SDK 调用许可） |
> | `RuleType.validatable` | false 时跳过 §3.6 字段校验 |
>
> 实体定义、DDL、REST API、不变式、级联策略、版本审计等设计**全部位于 RFC-0033**。

### 3.3.2 已迁移到 RFC-0033

> 详细定义请参阅 [RFC-0033](RFC-0033-元数据管理2-RuleSetType与RuleType.md)：
> - §3.2 RuleSetType（元数据层，无状态）
> - §3.3 RuleType（元数据层）
> - §3.4 ArgumentType / §3.5 ReturnType（合并入 RuleType JSON）
> - §3.6 RuleTypeFuntion / RuleTypeExcludeFuntion（合并入 RuleType JSON）
> - §3.7 FuntionType（原 FunctionLib 重命名 + 新增 RuleSetType 关联）


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
- 平级：**RFC-0033（元数据层 2：RuleSetType / RuleType 元数据管理）** — 本 RFC 实体定义权威来源在此
- 平级：RFC-0020（Groovy 沙箱）— **共用 §3.10 SimpleTSWhitelist 白名单单一来源**
- ADR：**ADR-003 中间态 DSL 采用 SimpleTS**、**ADR-006 规则源语言采用 SimpleTS**、**ADR-009 SimpleTS 为中心的星型转换架构**、**ADR-012 enum 视为 ObjectType 特殊形态**
- 规范：[docs/dsl/SimpleTS.md](../../dsl/SimpleTS.md)

---

## 8.1 RFC-0018-bis 修订日志

| 日期 | 修订内容 |
|------|---------|
| 2026-09-12 | RFC-0018-bis：废弃 DomainMeta；RuleSet 直接作为领域代表；SimpleTSParser 入口改为 `parse(source, RuleType)`；新增 RuleType/ArgumentType/ReturnType/RuleTypeFuntion/RuleTypeExcludeFuntion 实体；FieldValidator 锚点从 DomainMeta.context 下沉到 RuleType.arguments；ObjectType.Kind 新增 VOID（内置） |
| 2026-09-12 | RFC-0018-bis-bis（RFC-0033 协作）：§3.3 实体定义迁移到 [RFC-0033](RFC-0033-元数据管理2-RuleSetType与RuleType.md)；本 RFC 仅声明 RuleType 4 个字段的编译期用途 |
