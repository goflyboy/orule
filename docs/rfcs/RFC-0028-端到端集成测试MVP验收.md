# RFC-0028: 端到端集成测试（MVP 验收用例）

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：3d · **阶段**：S8

---

## 1. 摘要

实现 MVP 端到端验收测试，覆盖完整链路 NL→SimpleTS→Groovy→执行，并验证 MVP 验收标准（功能、性能、安全）。

---

## 2. 动机

- E2E 测试是 MVP 验收的最后一道关（依据 `09-收口与风险 §9.3.3`）
- 支撑 RFC-0000 总览的"验收标准"验证

---

## 3. 详细设计

### 3.1 测试用例清单

```java
// packages/orule-server/src/test/java/com/orule/e2e/MvpE2eTest.java

@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
class MvpE2eTest {

    @Autowired RuleService ruleService;
    @Autowired TestCaseService testCaseService;
    @Autowired NlToSimpleTsService nlService;
    @Autowired RuleSetArtifactService artifactService;
    @Autowired RuleExecutor ruleExecutor;

    // ========== 功能验收 ==========

    @Test
    @DisplayName("MVP-F1: 完整链路 NL→SimpleTS→Groovy→执行")
    void fullPipeline_vipDiscount() {
        // 1. 创建规则
        RuleDto rule = createRule("ORDER_DISCOUNT", "VIP 满减");

        // 2. NL → SimpleTS
        var convertResult = nlService.convert(rule.id(), "VIP 客户满 200 减 30");
        assertTrue(convertResult.success());
        assertTrue(convertResult.simpleTs().contains("customer.tier == CustomerTier.VIP"));

        // 3. 保存 SimpleTS
        ruleService.updateVersion(rule.id(), convertResult.simpleTs());

        // 4. 编译（SimpleTS → Groovy）
        var compileResult = ruleService.compileVersion(rule.id());
        assertTrue(compileResult.success());

        // 5. 发布
        ruleService.publish(rule.id());

        // 6. 执行
        Map<String, Object> input = Map.of(
            "customer", Map.of("tier", "VIP"),
            "order", Map.of("totalAmount", 300, "discount", 0)
        );
        ExecutionResult exec = ruleExecutor.executeRule(rule.id(), input, "test");
        assertTrue(exec.success());
        assertEquals(30, ((Map) exec.outputContext().get("order")).get("discount"));
    }

    @Test
    @DisplayName("MVP-F2: 规则集批量执行")
    void batchExecution_3rules() {
        // 1. 创建 3 条规则
        // 2. 打包规则集
        // 3. 执行
        // 4. 验证全部成功
    }

    @Test
    @DisplayName("MVP-F3: 测试用例通过率统计")
    void testCase_passRate() {
        // 1. 创建 5 个测试用例（3 通过 + 2 失败）
        // 2. 批量运行
        // 3. 验证通过率 60%
    }

    @Test
    @DisplayName("MVP-F4: 规则状态机（MAINTENANCE → PUBLISHED → RETIRED）")
    void ruleLifecycle() {
        // 验证状态转移正确
        // PUBLISHED 时只能有一个
    }

    // ========== 性能验收 ==========

    @Test
    @DisplayName("MVP-P1: 单条规则执行 P95 < 200ms")
    void performance_singleRuleExecution() {
        // 循环 100 次，计算 P95
    }

    @Test
    @DisplayName("MVP-P2: 编译 P95 < 2s")
    void performance_compile() {
        // 循环 50 次，计算 P95
    }

    // ========== 安全验收 ==========

    @Test
    @DisplayName("MVP-S1: Groovy 沙箱阻止 System.exit")
    void security_sandboxSystemExit() {
        ExecutionResult r = executeRuleWithGroovy("System.exit(1)");
        assertFalse(r.success());
        assertEquals("SANDBOX_VIOLATION", r.errorCode());
    }

    @Test
    @DisplayName("MVP-S2: Groovy 沙箱阻止文件操作")
    void security_sandboxFileOperation() {
        ExecutionResult r = executeRuleWithGroovy("new File('/etc/passwd').text");
        assertFalse(r.success());
        assertEquals("SANDBOX_VIOLATION", r.errorCode());
    }

    @Test
    @DisplayName("MVP-S3: Groovy 沙箱超时保护")
    void security_sandboxTimeout() {
        ExecutionResult r = executeRuleWithGroovy("while(true) {}");
        assertFalse(r.success());
        assertEquals("TIMEOUT", r.errorCode());
    }

    // ========== 稳定性验收 ==========

    @Test
    @DisplayName("MVP-R1: 72 小时长稳测试")
    void stability_72hours() {
        // @SlowTest，跳过 CI；仅在发布前手动执行
    }
}
```

### 3.2 测试配置

```yaml
# application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate

orule:
  llm:
    # 测试环境使用 Mock
    provider: mock
  runtime:
    sandbox:
      timeout-ms: 5000  # 测试环境缩短超时
```

### 3.3 Mock LLM

```java
// 测试用的假 LLM Client
@TestConfiguration
public class MockLlmClient implements LlmClient {
    @Override
    public String chat(String prompt) {
        if (prompt.contains("VIP") && prompt.contains("200") && prompt.contains("30")) {
            return "if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {\n    order.discount = 30\n}";
        }
        return "if (order.totalAmount > 0) { order.discount = 0; }";
    }
}
```

---

## 4. 关联

- 上游：全部 RFC（0020~0025）
- 下游：—
- ADR：—
