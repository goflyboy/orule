# RFC-0023: NL → SimpleTS LLM 调用 + Schema 注入

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：4d · **阶段**：S5

---

## 1. 摘要

实现自然语言（NL）转 SimpleTS 的 LLM 调用服务，含 DomainMeta Schema 注入、Prompt 工程、Schema 校验和结果回退。

---

## 2. 动机

- NL→SimpleTS 是 MVP 核心差异化功能（依据 `06-运行视图 §6.1.1` Step 1~4）
- Schema 注入是防止 LLM 幻觉（T2 风险缓解）的关键
- 依据 ADR-009 星型架构，SimpleTS = 唯一中间态

---

## 3. 详细设计

### 3.1 模块位置

```
packages/orule-server/src/main/java/com/orule/server/
├── llm/
│   ├── NlToSimpleTsService.java   # NL→SimpleTS 服务
│   ├── SimpleTsPromptBuilder.java # Prompt 构建
│   ├── LlmClient.java            # LLM HTTP 客户端（统一封装）
│   ├── LlmConfig.java            # LLM 配置
│   └── exception/
│       └── LlmException.java
└── controller/
    └── ConvertController.java    # NL/SimpleTS/Table 互转
```

### 3.2 LLM 配置

```java
@ConfigurationProperties(prefix = "orule.llm")
public record LlmConfig(
    String provider,     // openai / azure-openai / anthropic / ollama
    String baseUrl,     // API Base URL
    String model,       // 模型名称
    String apiKey,      // API Key（建议通过环境变量注入）
    Double temperature,  // 温度，默认 0.1（低幻觉）
    Integer maxTokens,  // 最大 Token，默认 4096
    Double topP,
    Integer timeoutMs   // 超时，默认 30000
) {
    public LlmConfig {
        if (temperature == null) temperature = 0.1;
        if (maxTokens == null) maxTokens = 4096;
        if (timeoutMs == null) timeoutMs = 30_000;
    }
}
```

### 3.3 Prompt 构建器

```java
package com.orule.server.llm;

import com.orule.dsl.DomainMeta;

/**
 * NL → SimpleTS 的 Prompt 构建器。
 * 核心：Schema 强制注入（防止 LLM 幻觉）。
 */
public class SimpleTsPromptBuilder {

    private static final String SYSTEM_PROMPT = """
        You are an expert rule engineer. Your task is to convert natural language descriptions
        into **SimpleTS** code (a TypeScript subset for business rules).

        ## SimpleTS Rules (MUST follow):
        - Only use: if/else, for loops, let declarations, assignments, expressions
        - Context variables are already declared: you can directly use them
        - Enum values: `EnumName.Value` (e.g., `CustomerTier.VIP`)
        - No objects, arrays, functions, try/catch, async/await
        - No ternary operator `?:`
        - Comparison operators: == != > >= < <=
        - Boolean operators: && || !
        - Assignments: only to context variable properties (e.g., `order.discount = 30`)
        - NEVER use console.log, Math.random, JSON.parse, or System.*
        - Wrap condition expressions in parentheses for clarity

        ## Output Format:
        Return ONLY the SimpleTS code. No explanations, no markdown fences.
        """;

    /**
     * 构建完整的 Prompt。
     * 关键：Schema 注入在 system prompt 中强制声明。
     */
    public String build(DomainMeta meta, String naturalLanguage, String ruleCode) {
        return buildSystemPrompt(meta) + "\n\n" + buildUserPrompt(meta, naturalLanguage, ruleCode);
    }

    private String buildSystemPrompt(DomainMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT).append("\n\n");
        sb.append("## Domain Metadata (MUST use exactly these types):\n\n");

        // 1. 枚举
        if (!meta.enums().isEmpty()) {
            sb.append("### Enums:\n");
            for (DomainMeta.EnumDef e : meta.enums()) {
                sb.append("- ").append(e.id()).append(": ")
                  .append(String.join(" / ", e.values())).append("\n");
            }
            sb.append("\n");
        }

        // 2. 实体
        if (!meta.entities().isEmpty()) {
            sb.append("### Entities:\n");
            for (DomainMeta.EntityDef entity : meta.entities()) {
                sb.append("- ").append(entity.id()).append(":\n");
                for (DomainMeta.EntityField f : entity.fields()) {
                    sb.append("    - ").append(f.name())
                      .append(": ").append(typeToString(f.type()));
                    if (!f.writable()) sb.append(" [readonly]");
                    if (f.nullable()) sb.append(" [nullable]");
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        // 3. 入口变量
        sb.append("### Available Context Variables (use directly):\n");
        for (DomainMeta.ContextVar ctx : meta.context()) {
            sb.append("- ").append(ctx.name()).append(": ")
              .append(ctx.entityId());
            if (ctx.nullable()) sb.append(" [nullable]");
            sb.append("\n");
        }

        // 4. 内置函数
        sb.append("""
            ### Built-in Functions (available):
            - Math.abs / Math.min / Math.max / Math.floor / Math.ceil / Math.round
            - String.length / startsWith / endsWith / includes / toUpperCase / toLowerCase
            - LocalDate.now() / plusDays(n) / minusDays(n) / getYear() / isAfter() / isBefore()
            """);

        return sb.toString();
    }

    private String buildUserPrompt(DomainMeta meta, String nl, String ruleCode) {
        return String.format("""
            ## Task:
            Write SimpleTS code for the rule: "%s"

            ## Natural Language Description:
            %s

            ## Output:
            Write the SimpleTS code (only the code, no markdown):
            """, ruleCode, nl);
    }

    private String typeToString(DomainMeta.FieldType type) {
        return switch (type) {
            case DomainMeta.PrimitiveType p -> p.name();
            case DomainMeta.EntityRef e -> e.entityId();
            case DomainMeta.EnumRef e -> "enum " + e.enumId();
        };
    }
}
```

### 3.4 LLM 客户端

```java
package com.orule.server.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 统一 LLM HTTP 客户端（支持 OpenAI-compatible API）。
 * MVP 支持 OpenAI / Azure OpenAI / Ollama / Anthropic。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final LlmConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlmClient(LlmConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.timeoutMs()))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用 LLM 生成 SimpleTS。
     * @param prompt 完整 Prompt
     * @return LLM 输出文本
     */
    public String chat(String prompt) throws LlmException {
        // 1. 构建请求体
        Map<String, Object> requestBody = buildRequestBody(prompt);

        // 2. 发送请求
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(requestBody)))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            // 3. 解析响应
            if (response.statusCode() != 200) {
                log.error("LLM API error: {} {}", response.statusCode(), response.body());
                throw new LlmException("LLM API 错误: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();

            // 4. Token 使用量（可选，记录日志）
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                log.info("LLM token usage: prompt={}, completion={}, total={}",
                    usage.path("prompt_tokens").asInt(),
                    usage.path("completion_tokens").asInt(),
                    usage.path("total_tokens").asInt());
            }

            return content != null ? content.trim() : "";

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM 调用失败", e);
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        // OpenAI Chat Completions API 格式
        return Map.of(
            "model", config.model(),
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            ),
            "temperature", config.temperature(),
            "max_tokens", config.maxTokens()
        );
    }
}
```

### 3.5 NL → SimpleTS 服务

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class NlToSimpleTsService {

    private final LlmClient llmClient;
    private final SimpleTsPromptBuilder promptBuilder;
    private final SimpleTSParser parser;        // RFC-0018
    private final DomainMetaService domainMetaService;  // RFC-0015

    private static final int MAX_RETRIES = 3;

    /**
     * NL → SimpleTS 转换。
     * 流程：构建 Prompt → 调用 LLM → 解析 SimpleTS → 校验 → 返回
     */
    public ConversionResult convert(String ruleId, String naturalLanguage) {
        // 1. 获取 DomainMeta
        Rule rule = ruleRepo.findById(ruleId)
            .orElseThrow(() -> new NotFoundException("Rule", ruleId));
        RuleSet ruleSet = ruleSetRepo.findById(rule.getRuleSetId())
            .orElseThrow(() -> new NotFoundException("RuleSet", rule.getRuleSetId()));

        DomainMeta meta = domainMetaService.buildDomainMeta(ruleSet.getDomainId());

        // 2. 构建 Prompt
        String prompt = promptBuilder.build(meta, naturalLanguage, rule.getCode());

        // 3. 调用 LLM（含重试）
        String rawOutput = null;
        String error = null;

        for (int i = 1; i <= MAX_RETRIES; i++) {
            try {
                rawOutput = llmClient.chat(prompt);
                if (rawOutput != null && !rawOutput.isBlank()) break;
            } catch (LlmException e) {
                error = e.getMessage();
                log.warn("LLM 调用失败（第 {} 次）: {}", i, e.getMessage());
                if (i < MAX_RETRIES) {
                    try { Thread.sleep(1000L * i); }   // 指数退避
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }

        if (rawOutput == null || rawOutput.isBlank()) {
            return ConversionResult.failed(
                "LLM 调用失败（重试 " + MAX_RETRIES + " 次）: " + error,
                null
            );
        }

        // 4. 清洗 LLM 输出（去掉 markdown fences）
        String simpleTs = cleanOutput(rawOutput);

        // 5. 校验 SimpleTS（RFC-0018）
        try {
            Program ast = parser.parse(simpleTs, meta);
            return ConversionResult.success(simpleTs, null);
        } catch (TssCompileError e) {
            // 校验失败 → 返回错误和 LLM 原始输出（供用户修正）
            return ConversionResult.failed(
                "SimpleTS 校验失败:\n" + e.getMessage(),
                simpleTs  // 仍返回，供用户手动修正
            );
        }
    }

    /**
     * 清洗 LLM 输出（去掉 markdown 代码块）。
     */
    private String cleanOutput(String raw) {
        String result = raw.trim();
        // 去掉 ```ts ``` ```groovy ``` 等标记
        result = result.replaceAll("```typescript\\s*", "");
        result = result.replaceAll("```ts\\s*", "");
        result = result.replaceAll("```groovy\\s*", "");
        result = result.replaceAll("```\\s*", "");
        return result.trim();
    }

    public record ConversionResult(
        boolean success,
        String simpleTs,
        String errorMessage
    ) {
        public static ConversionResult success(String simpleTs, String warning) {
            return new ConversionResult(true, simpleTs, warning);
        }
        public static ConversionResult failed(String error, String partialResult) {
            return new ConversionResult(false, partialResult, error);
        }
    }
}
```

### 3.6 REST API

```
# NL → SimpleTS 转换
POST /api/v1/convert/nl-to-simplets
Content-Type: application/json

{
    "ruleId": "xxx",
    "naturalLanguage": "VIP 客户满 200 减 30"
}

→ 200 OK
{
    "success": true,
    "simpleTs": "if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {\n    order.discount = 30\n}",
    "errorMessage": null
}

→ 200 OK（失败，仍返回 partial）
{
    "success": false,
    "simpleTs": "if (customer.tier == CustomerTier.VIP) {\n    order.discount = 30\n}",  ← 校验失败但有内容
    "errorMessage": "SimpleTS 校验失败: 第 1 行: 类型不匹配: number 与 string 不能比较"
}
```

### 3.7 配置（application.yml）

```yaml
orule:
  llm:
    provider: openai           # openai / azure-openai / anthropic / ollama
    base-url: https://api.openai.com/v1
    model: gpt-4o-mini
    api-key: ${OPENAI_API_KEY}  # 从环境变量注入
    temperature: 0.1            # 低幻觉
    max-tokens: 4096
    timeout-ms: 30000
```

---

## 4. 影响面

- 新增 `com.orule.server.llm` 包（5 个类）
- 新增 `/api/v1/convert/nl-to-simplets` 端点
- 不涉及数据库新表
- 依赖 LLM API（OpenAI / Azure / Ollama）

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| Prompt 构建完整性 | Schema 包含 enums / entities / context |
| LLM 调用成功 | Mock LLM 返回 SimpleTS |
| LLM 输出清洗 | ` ```ts ` 等被正确去掉 |
| 校验失败时 partial 返回 | 即使校验失败也返回 LLM 原始输出 |
| 重试 + 指数退避 | LLM 超时 → 重试 3 次 |
| Token 计量 | 日志记录 usage |
| 空输出处理 | 返回错误而不是空字符串 |

---

## 6. 风险

| 风险 | 缓解 |
|------|------|
| T2 LLM 幻觉 | Schema 注入 + SimpleTS 校验 + partial 返回 |
| API Key 安全 | 环境变量注入；日志脱敏 |
| Token 成本 | 温度 0.1 + maxTokens 限制 + 使用量日志 |
| LLM 服务不可用 | 重试 3 次 + 指数退避 + 错误返回 |
| Prompt 注入 | NL 输入不过滤（LLM 自主处理） |

---

## 7. 实施步骤

```
1. 创建 com.orule.server.llm 包
2. 实现 LlmConfig
3. 实现 LlmClient
4. 实现 SimpleTsPromptBuilder
5. 实现 NlToSimpleTsService
6. 实现 ConvertController
7. 单元测试（Prompt 构建）
8. 集成测试（Mock LLM + 完整链路）
```

---

## 8. 关联

- 上游：RFC-0015（元数据 API）、RFC-0018（SimpleTS 解析器）
- 下游：RFC-0024（多视图编辑器接入）
- ADR：**ADR-009 SimpleTS 为中心的星型转换架构**
- 对应风险：T2（LLM 幻觉）—— 高优先级
