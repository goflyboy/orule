---
name: rfc-driven-development
description: RFC 驱动开发工作流。用户要求根据 RFC/设计文档实现代码、提到 RFC-XXXX，或要求 RFC 批准后开发时使用。流程包括读取 RFC、划分顶层/cruleengine/crulemgr 归属、实现代码、编写测试、验证、提交，并在用户要求时推送。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# RFC 驱动开发

在本仓库根据 RFC 实现功能时使用此 Skill。

## 阶段 1：理解范围

1. 完整读取用户引用的 RFC。
2. 编辑代码前填写实现所有权卡片：

```text
模块所有权:
- 顶层:
- cruleengine:
- crulemgr:
- 跨模块边界:

预计修改文件:
- 顶层文档/规则:
- cruleengine 代码/测试/文档:
- crulemgr 代码/测试/文档:

优先复用:
- 现有入口点:
- 现有 helper:
- 现有测试基类:
- 现有 DSL/注解:
- 不新增:
- 可由上下文推导的字段:

来源标注与置信度:
- [KNOWN] 训练数据确定事实:
- [COMPUTED] 本次推导:
- [INFERRED] 逻辑推断:
- [GUESS] 无依据猜测（置信度上限 LOW）:
```

3. 按归属读取规则文件：
   - `cruleengine`：`cruleengine/CLAUDE.md`、`cruleengine/acceptance_core.md`
   - `crulemgr`：`crulemgr/CLAUDE.md`、`crulemgr/acceptance.md`
   - 跨模块：两个模块规则集和顶层 `CLAUDE.md`
4. 实现前搜索相似测试和 helper：

```bash
rg "inferRecommendModule|printSimpleSolutions|assertSoluContain" cruleengine/src/test/java crulemgr/src/test/java
rg "RuleTransPipeline|RuleUnit|DebugPackage|LlmCassette" crulemgr/src/test/java
```

## 阶段 2：保守实现

- 优先沿用现有模块模式，不急于创建新抽象。
- 只有无法从上下文推导时才新增字段。
- 只有现有测试基类或 DSL 无法表达行为时才新增 helper。
- 如果给大型协调器添加非平凡逻辑，应抽取模块本地类并增加聚焦单元测试。
- 保持 `cruleengine` 不依赖 RuleTrans、RuleUnit、LLM、Prompt、Spring AI、FreeMarker 和 `crulemgr`。
- 管理器测试夹具保留在 `crulemgr/src/test`；不要依赖 `cruleengine` test-jar。

## cruleengine 实现约束

- 运行时客户需求重写应发生在过滤前，通常从 `ModuleConstraintExecutorImpl.normalizePartConstraint(...)` 调度。
- `PartCategory.filterClone(...)` 应消费已经规范化的 where 条件，不读取规则元数据做重写。
- `FilterExpressionExecutor` 的操作符语义保持稳定；兼容行为应体现在显式重写后的表达式中。
- 场景测试应复用 `inferRecommendModule(...)`、`printSimpleSolutions()` 和 `assertSoluContain(...)`。

## crulemgr 实现约束

- RuleTrans、RuleUnit、LLM、Prompt 和调试包功能属于 `crulemgr`。
- 普通管理器测试必须确定性且零 token。
- 除非测试明确是 live/golden，否则使用 fake、stub、fixture 或 cassette replay。
- 调试包失败不得隐藏原始 RuleTrans pipeline 失败。
- 优先测试官方 pipeline，不另写平行 runner。

## 阶段 3：测试

先运行目标测试，再运行必要边界检查。

引擎示例：

```bash
mvn -pl cruleengine "-Dtest=NewEngineTest,RelatedEngineTest" test
mvn -pl cruleengine test
```

管理器示例：

```bash
mvn -pl crulemgr -am "-Dtest=NewManagerTest,RelatedManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl crulemgr -am test
```

跨模块或依赖敏感修改的边界检查：

```bash
mvn "-pl=cruleengine,crulemgr" "-Dtest=ModuleBoundaryDependencyTest,CruleMgrBoundaryDependencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg "com\\.jmix\\.ruletrans|com\\.jmix\\.ruleunit|com\\.jmix\\.tool\\.impl|org\\.springframework|freemarker|org\\.junit\\.platform" cruleengine/src/main/java
rg "com\\.jmix\\.executor\\.impl\\." crulemgr/src/main/java
```

日志语言检查：

```bash
rg -n "log\\.(info|warn|error|debug)\\([^\\n]*[\\u4e00-\\u9fff]" cruleengine/src/main/java crulemgr/src/main/java
```

## 阶段 4：提交

- 除非用户要求其他分支，否则直接提交到 `main`。
- 默认包含 `.specstory/`，除非明显无关或用户要求排除。
- 无关的未跟踪文件不要放入提交，除非用户要求。
- 如果用户要求推送，提交后推送 `main`。

使用简洁的 conventional commit message，例如：

```text
docs(rfc): clarify module-specific development rules
feat(rfc): implement RFC-0022 option compatibility rewrite
refactor(rfc): extract option compatibility requirement rewriter
```

## 行为约束（基于李开复 Claude 全局提示词）

在代码实现和测试过程中，始终遵循以下约束：

### 禁止事项

- 禁止编造类名、方法名、字段名、路径或命令。
- 禁止在日志消息中使用中文（`log.info(...)`、`log.warn(...)`、`log.error(...)` 等必须仅使用英文）。
- 禁止在断言、测试用例或文档中捏造不存在的测试场景。
- 禁止使用未核实的依赖或 API。

### 来源标注与置信度

- 代码决策前标注来源类型：`[KNOWN]` 现有代码事实、`[COMPUTED]` 推导结论、`[INFERRED]` 逻辑推断、`[GUESS]` 猜测（置信度上限 LOW）。
- 对于 `[GUESS]` 类推断，应明确标注"需验证"，而不是以确定语气陈述。

### 角色行为

- 以反驳视角审视现有代码和 RFC 设计：现有方案有哪些漏洞？有哪些边界条件未覆盖？
- 不知道某个类或方法是否可用时，先搜索再假设，不要凭直觉编造包名或类名。
- 若对模块边界或依赖关系不确定，先运行边界测试，而不是凭印象断言。

### 反谄媚预警

如果发现自己倾向于直接接受用户或 RFC 的方案而不加质疑，应：

1. 主动提出至少一个替代方案或风险点。
2. 对方案中的不确定部分添加 `[GUESS]` 标注。
3. 在实现前说明置信度等级。

### 透明度

- 实现中若发现 RFC 与实际代码的偏差，应明确报告，而不是"假装一致"。
- 修改立场时应说明理由，不要假装"一直这么想"。
- 输出中如违反上述约束，应在末尾追加 `[RULES I BROKE]: ...`。
