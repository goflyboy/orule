---
name: ruletrans-system-test-debugging
description: RuleTrans 系统测试调试规约。调试或修改 RuleTrans 端到端/系统测试时使用，尤其是 SmartHomeAssignmentRuleTransSystemTest、RuleTransPipelineTestBase、BusinessCaseTextTestBase、RuleTestCaseGenerator、test_case_prompt.jtl、DiagnosticLlmInvoker、模型/缓存切换、业务 JSON 用例生成、生成的 Java 断言，或 Codex 可能改错层级的失败。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# RuleTrans 系统测试调试

## 概览

使用此 Skill 保证 RuleTrans 系统测试修复落在正确层级。默认姿态是先诊断，再修改真正拥有失败的最小层：测试期望、业务 JSON prompt/generator、规则生成、pipeline 诊断，或底层 executor 行为。

在 `crulemgr` / `cruleengine` 拆分后，RuleTrans、RuleUnit、Prompt 模板、LLM/cache 代码和业务 JSON 诊断属于 `crulemgr`。引擎 executor 和场景回归属于 `cruleengine`；只有在生成的 Java 和业务用例已经正确、失败层确实是运行时 executor 时，才修改引擎代码。

## 证据优先

编辑前先收集当前证据：

1. 阅读失败测试及其基类：
   - `crulemgr/src/test/java/com/jmix/ruletrans/*SystemTest.java`
   - `crulemgr/src/test/java/com/jmix/ruletrans/RuleTransPipelineTestBase.java`
   - `crulemgr/src/test/java/com/jmix/ruletrans/BusinessCaseTextTestBase.java`
2. 如果失败涉及业务测试用例，阅读相关 generator/prompt：
   - `crulemgr/src/main/java/com/jmix/ruletrans/testgen/RuleTestCaseGenerator.java`
   - `crulemgr/src/main/resources/ruletrans/test_case_prompt.jtl`
3. 当输出、缓存或模型行为不清楚时，阅读诊断 helper：
   - `crulemgr/src/test/java/com/jmix/ruletrans/DiagnosticLlmInvoker.java`
   - `crulemgr/src/main/java/com/jmix/ruletrans/RuleTransPipelineOptions.java`
4. 先运行或检查窄范围失败测试。优先使用：
   ```bash
   mvn -pl crulemgr -am -Dtest=SmartHomeAssignmentRuleTransSystemTest#testName test
   ```
5. 猜测前先检查 `crulemgr/target/ruletrans-pipeline-system/...`。重点看最终方法体、生成的业务用例、组装后的 Java 源码路径、`print-detail.txt`、分阶段 LLM prompt/response 文件。

## 失败分流

决定修改哪里之前，先给失败分类：

- `Generated Java mismatch`：Java 方法体缺少必需 SDK 调用或目标变量错误。检查 `methodBody`、规则生成 prompt 和生成源码。
- `Business case mismatch`：生成的 JSON `given`/`expect` 违反 service-method contract。检查 `test_case_prompt.jtl` 和 `RuleTestCaseGenerator` 规范化逻辑。
- `RuleUnit execution mismatch`：生成的 Java 和用例看起来正确，但执行结果错误。检查 `RuleUnitCaseExecutionProcessor`、模型克隆/挂载和 executor 行为。
- `Underlying engine bug`：pipeline 输入正确，但底层 executor 无法解析或执行目标表达式。修复 executor 并补充聚焦回归。近期例子是逗号分隔过滤条件属于 `FilterExpressionExecutor`，不属于测试期望。
- `Diagnostics gap`：真实问题难以看清。先改进 `print`、`printDetail`、断言消息或 LLM 阶段标签，再修改逻辑。
- `Model/cache issue`：切换模型或禁用缓存后行为变化。先使用 fresh/refreshed model helper 和带阶段名的 prompt 文件，再修改期望。

不要把最终 `true/false` 断言失败当成充分证据。它只说明症状出现的位置。

## Service Method 契约

业务 JSON 用例必须明确 service-method contract：

- `testAssignment`：约束赋值用例。`given` 使用 `parameters` 和/或 `parts`；不要要求 `given.partCategories`。`expect` 只包含变化后的 `parameters` 和/或 `parts`；不要保留 `solutions`。
- `testPostAssignment`：非约束后置赋值用例。输出形状和 assignment 相同，但环境是 `NON_CONSTRAINT`。
- `testCompatibility`：兼容性/推荐用例。可能需要 `given.partCategories`，允许且经常需要 `expect.solutions`。
- `testPriority`：优先级/推荐用例。当排序或多解有意义时，`expect.solutions` 必需。

如果 LLM 输出违反契约的多余字段，优先在 `RuleTestCaseGenerator` 做确定性规范化，并在 `test_case_prompt.jtl` 中收紧提示词。不要放宽系统测试断言去接受错误契约。

## 编辑决策树

按以下顺序处理：

1. 如果断言隐藏了差异，先改进 `BusinessCaseTextTestBase` 或局部期望消息。
2. 如果业务 JSON 形状不符合 service method，更新 `test_case_prompt.jtl` 和 `RuleTestCaseGenerator` 规范化。
3. 如果生成的 Java 语义错误，调整规则 prompt/context/generation 层，不要改业务用例断言。
4. 如果生成的 Java 和用例正确但执行失败，调试 `RuleUnitCaseExecutionProcessor` 和底层 executor。
5. 如果只有一个具体测试反复出现样板代码，确认至少还有一个 RuleTrans 系统测试能复用后，再把断言/打印 helper 移到基类。
6. 如果模型行为改变，先在失败测试附近用 `runRuleTransWithFreshModel(...)` 或 `runRuleTransWithRefreshedModel(...)` 复现，再改生产逻辑。

当 owner 是引擎时，在 `cruleengine/src/test/java` 下新增或更新聚焦回归，并运行 `mvn -pl cruleengine -Dtest=YourEngineTest test`。管理端 RuleTrans 测试留在 `crulemgr`。

## 调试输出规则

保留两种输出模式：

- `print(result, true)`：展示最终方法体和业务测试用例，打印组装后的 Java 源码路径，并把详细 trace 写入文件。
- `printDetail(result, true)`：必要时写入或展示完整 pipeline trace。

诊断不足时，改进基类/诊断层，不要在每个测试里添加临时 `System.out`。LLM prompt/response 文件必须包含阶段名，例如 `RULE_GENERATION`、`BUSINESS_CASE_GENERATION`、`CATEGORY_IDENTIFICATION`、`COMPILATION_CORRECTION` 或 `TEST_CORRECTION`；纯数字文件名太难调试。

## 模型与缓存

LLM 支持的测试遵守以下规则：

- CASE 测试使用当前注解驱动 cassette 风格：类级 `@LlmCassetteTestSuite`、方法级 `@LlmCassetteTest`，以及适用时的 `CassetTestBase` / `cassettePipelineOptions()`。
- 优先使用推导名称。suite 名和 case 名尽量来自测试类和方法；只有稳定共享 cassette 名有价值时才显式指定 `name`。
- 正常 replay 不得调用真实模型。调试或录制 cassette 时，只改变方法级 run mode（`REPLAY`、`RECORD`、`REFRESH`）。
- 当用户明确要求重录 cassette，或 Prompt/上下文契约变化导致 `LLM cassette cache miss` 时，可以临时把相关方法级 `@LlmCassetteTest` 从 `REPLAY` 改为 `RECORD` 或 `REFRESH` 并运行真实模型。录制、调试和回归通过后，必须把 run mode 改回 `REPLAY`，防止普通回归重复录制或隐式调用真实模型。不要把旧 response 手工挂到新 hash 上来掩盖 Prompt 变化。
- 不要重新引入巨型内联 fixture response、`replayFixtureOptions(...)`、旧 harness 层、`serviceMethod` 残留，或绕生产 LLM 路径的本地 wrapper。
- 新模型失败时，先按阶段比较 prompt/response 输出，再修改期望。目标是保持跨模型质量，而不是固化某个模型的偶然输出。

## 测试分层

用能证明行为的最小测试层：

- 单元测试覆盖确定性逻辑、分支、规范化、解析、后处理和失败包装。可以使用 mock/fake invoker 或固定数据，但不得调用真实 LLM。
- Cassette CASE 测试通过生产流程覆盖每个独立 LLM 功能。至少为每个完整能力保留一个 cassette CASE，例如分类识别、规则片段生成、业务用例生成或完整 RuleTrans 场景。
- 完整场景 cassette 测试覆盖 assignment、compatibility、priority、post-assignment 等端到端业务路径。大型场景应通过增加业务用例成长，而不是复制 harness 代码。
- Live/golden 测试是稀疏质量门禁。除非用户明确要求更广泛 live evaluation，否则每个重要家族只保留 1-2 个正向用例。

当完整 CASE 测试打印了有用的 prompt、response、method body 或业务 JSON，应让这些输出可以干净复制到单元测试输入和期望中。如果不能干净复制，先改进诊断/基类层。

## 测试风格护栏

- 代表项目真实中文环境的自然语言业务规则应使用中文。`homeMode`、`cameraOutdoor4k` 等代码标识符保持不变。
- 优先使用 `BusinessCaseTextTestBase` 的语义 token 断言：`caseContain`、`givenEqual`、`givenContain`、`expectEqual`、`expectContain`。
- Assignment 用例只断言变化后的输出。如果期望值重复输入值，应在 generator 规范化中移除重复值，而不是在测试期望中保留。
- 测试方法保持短小。可复用 helper 放到 `RuleTransPipelineTestBase` 或 `BusinessCaseTextTestBase`；场景专属数据留在具体系统测试里。
- 不要为了掩盖业务不匹配而把 `equal` 改成 `contain`，除非生成用例确实有额外相关事实。
- 不要为了让一个测试更容易而添加未使用字段、价格、无关部件或局部 helper DSL。
- 从用户/业务视角编写测试。输入和断言应提到业务可见规则文本、参数、部件、分类和预期行为；除非测试目标就是相关契约，否则避免内部 ID、cache key、生成文件名或执行 service method 名称。
- 每个 CASE 方法的业务输入/输出代码尽量通过现有 fluent helper 控制在 3-5 行左右。若测试需要更多脚手架，先找现有基类 helper，再考虑新增抽象。
- RuleTrans LLM cassette 测试应为实质不同上下文增加独立 CASE 方法，例如产品级规则生成和单个 `PartCategory` 规则生成。

## 最终检查

报告完成前：

1. 重新运行窄范围失败测试，或说明无法运行的原因。
2. 如果修改了底层 executor，在 `cruleengine` 中运行或新增聚焦回归测试。
3. 检查 `git diff`，确认修改停留在诊断出的层级。
4. 如果 executor 行为变化，运行模块边界测试：
   ```bash
   mvn "-pl=cruleengine,crulemgr" "-Dtest=ModuleBoundaryDependencyTest,CruleMgrBoundaryDependencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
   ```
5. 明确说明修复层级：断言诊断、prompt/generator 契约、规则生成、pipeline 诊断、模型/缓存控制，或 executor 行为。

## 行为约束（基于李开复 Claude 全局提示词）

在调试过程中必须遵循以下约束：

### 来源标注

| 标签 | 含义 |
| --- | --- |
| `[KNOWN]` | 现有测试代码、断言、日志输出 |
| `[COMPUTED]` | 通过计算推导的期望值 |
| `[INFERRED]` | 从失败原因推断的根因 |
| `[GUESS]` | 未经证实的根因假设，置信度上限 LOW |

禁止未标注的类名、方法名、路径或命令。

### 调试角色行为

- 调试时不回避矛盾：先挑毛病，再给结论。
- 对根因判断不确定时，标注 `[GUESS]` 并说明置信度等级。
- 不知道某个方法或路径是否存在时，先搜索再引用，不要凭印象。
- 不知道就说不知道，不要含糊其辞、不要编造失败原因。
- 如果某个修改只是猜测，应明确标注并说明需要验证。

### 反谄媚预警

调试时不得：

- 为迎合用户预期而隐藏真实问题。
- 把失败归因于外部因素（模型、环境）而不指出代码问题。
- 对未验证的修复方案以确定语气陈述。

发现上述信号时，添加 `[GUESS]` 标注，说明置信度，并建议验证步骤。

### 透明度

- 修复后若发现之前判断有误，应公开说明。
- 输出末尾可追加 `[RULES I BROKE]: ...` 说明违反的约束。
