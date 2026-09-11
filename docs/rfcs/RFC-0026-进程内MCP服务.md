# RFC-0026: 进程内 MCP 服务（MCP Tools）

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：3d · **阶段**：S7

---

## 1. 摘要

在 orule-server 内部实现进程内 MCP 服务，注册 rule_engine 相关 Tools（list_rules / get_rule / execute_rule / compile_rule 等），供 LLM Studio / Cursor 等 MCP Client 调用。

---

## 2. 动机

- MCP 是 orule 的 LLM 接口协议（依据 ADR-004）
- 进程内 MCP 让 LLM Studio 可以直接调用 orule-server 的能力（依据 `02-用例视图 §2.3.3`）

---

## 3. 详细设计

### 3.1 模块位置

```
packages/orule-server/src/main/java/com/orule/server/mcp/
├── McpServer.java            # MCP 服务入口
├── tools/
│   ├── ListRulesTool.java
│   ├── GetRuleTool.java
│   ├── ExecuteRuleTool.java
│   ├── CompileRuleTool.java
│   ├── ListDomainsTool.java
│   └── RunTestsTool.java
└── McpConfig.java
```

### 3.2 MCP Server

```java
package com.orule.server.mcp;

import io.github.mcp4eclipse.McpServer;
import io.github.mcp4eclipse.McpServerOptions;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 进程内 MCP 服务。
 * MVP 使用 mcp4eclipse（https://github.com/modelcontextprotocol/java-sdk）实现。
 * 二期可切换到 SSE/Stdio 模式。
 */
@Component
public class McpServer {

    @PostConstruct
    public void init() {
        McpServerOptions options = McpServerOptions.builder()
            .withPort(8082)   // MCP 专用端口
            .withHost("localhost")
            .build();

        McpServer server = McpServer.create(options);
        
        // 注册 Tools
        server.registerTool(new ListRulesTool());
        server.registerTool(new GetRuleTool());
        server.registerTool(new ExecuteRuleTool());
        server.registerTool(new CompileRuleTool());
        server.registerTool(new ListDomainsTool());
        server.registerTool(new RunTestsTool());
        
        server.start();
    }
}
```

### 3.3 示例 Tool

```java
@McpTool(name = "list_rules", description = "列出规则集下的所有规则")
public record ListRulesTool(
    McpService mcpService
) implements Callable<McpResult> {
    
    @Override
    public McpResult call(ListRulesRequest request) {
        try {
            List<RuleDto> rules = mcpService.listRules(request.ruleSetId());
            return McpResult.success(rules);
        } catch (Exception e) {
            return McpResult.error(e.getMessage());
        }
    }
    
    public record ListRulesRequest(String ruleSetId) {}
}
```

### 3.4 配置

```yaml
orule:
  mcp:
    enabled: true
    port: 8082
    tools:
      - list_rules
      - get_rule
      - execute_rule
      - compile_rule
      - list_domains
      - run_tests
```

---

## 4. 测试计划

| 测试 | 方式 |
|------|------|
| MCP 连接 | MCP Inspector 验证 |
| Tool 调用 | 各 Tool 正确返回 |
| 错误处理 | Tool 异常 → McpResult.error |

---

## 5. 关联

- 上游：RFC-0016（规则 API）
- 下游：orule-llm-studio 集成
- ADR：**ADR-004 MCP 为默认接口**
