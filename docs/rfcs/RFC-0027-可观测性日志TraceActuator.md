# RFC-0027: 可观测性（日志 + Trace + Actuator + Micrometer）

> **状态**：DRAFT · **优先级**：P1 · **预计工作量**：2d · **阶段**：S7

---

## 1. 摘要

实现 orule 的可观测性基础设施：JSON 结构化日志、X-Trace-Id 全链路追踪、Spring Actuator 端点、Micrometer 指标。

---

## 2. 动机

- 可观测性是 MVP 运维底线（依据 `06-运行视图 §6.5`）
- 支撑 RFC-0028 E2E 测试的日志验证
- 支撑二期 Prometheus + Grafana（对应风险 T8 线程池耗尽）

---

## 3. 详细设计

### 3.1 结构化 JSON 日志

```xml
<!-- logback-spring.xml -->
<encoder class="ch.qos.logback.core.encoder.JsonEncoder">
    <includeMdcKeyName>traceId</includeMdcKeyName>
    <includeMdcKeyName>userId</includeMdcKeyName>
</encoder>
```

JSON 日志格式：

```json
{
  "timestamp": "2026-09-11T22:00:00.123Z",
  "level": "INFO",
  "logger": "com.orule.server.service.RuleService",
  "message": "Rule compiled successfully",
  "traceId": "abc123",
  "ruleId": "rule-001",
  "durationMs": 456
}
```

### 3.2 X-Trace-Id 追踪

```java
// 全局 TraceId 过滤器
@Component
public class TraceIdFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) 
            throws ServletException, IOException {
        String traceId = req.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put("traceId", traceId);
        res.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

### 3.3 Actuator 端点

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      show-details: when_authorized
  metrics:
    tags:
      application: orule-server
```

```
GET /actuator/health        # 健康检查
GET /actuator/metrics      # 指标列表
GET /actuator/prometheus    # Prometheus 格式
GET /actuator/info          # 应用信息
```

### 3.4 Micrometer 指标

```java
// 关键指标埋点
public class RuleMetrics {
    
    private final MeterRegistry registry;
    
    public void recordCompile(String ruleId, long durationMs, boolean success) {
        Timer.builder("rule.compile")
            .tag("rule_id", ruleId)
            .tag("success", String.valueOf(success))
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }
    
    public void recordExecution(String ruleId, long durationMs, boolean success) {
        Counter.builder("rule.execution")
            .tag("rule_id", ruleId)
            .tag("success", String.valueOf(success))
            .register(registry)
            .increment();
        
        Timer.builder("rule.execution.duration")
            .tag("rule_id", ruleId)
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }
    
    public void recordLlmCall(String model, long durationMs, int tokens) {
        Timer.builder("llm.call")
            .tag("model", model)
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
        
        registry.summary("llm.tokens", Tags.of("model", model))
            .record(tokens);
    }
}
```

---

## 4. 关联

- 上游：RFC-0013（启动脚本）
- 下游：RFC-0029（Docker 部署）
- ADR：—
