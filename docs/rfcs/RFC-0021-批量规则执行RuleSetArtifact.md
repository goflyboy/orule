# RFC-0021: 批量规则执行 + RuleSetArtifact

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：3d · **阶段**：S4

---

## 1. 摘要

实现规则集批量执行（单 RuleSet 内多条规则 + 并发控制）和 RuleSetArtifact 打包/下载。

---

## 2. 动机

- 单规则执行是 RFC-0020，本 RFC 扩展到规则集级别（依据 `06-运行视图 §6.1.1` Step 10~14）
- RuleSetArtifact 是并发执行单元（依据 `06-运行视图 §6.2.3`）
- 支撑 RFC-0022（测试用例批量执行）

---

## 3. 详细设计

### 3.1 模块位置

```
packages/orule-runtime/src/main/java/com/orule/runtime/
├── execution/
│   ├── RuleSetExecutor.java        # 规则集执行器
│   ├── BatchExecutionContext.java  # 批量执行上下文
│   ├── RuleSetArtifactLoader.java  # 从 ArtifactStorage 加载制品
│   └── ConcurrencyController.java  # 并发控制
└── service/
    └── RuleSetArtifactService.java  # 规则集打包服务（放在 server）
```

### 3.2 并发控制

```java
package com.orule.runtime.execution;

import java.util.concurrent.*;

/**
 * 规则集并发控制器。
 * 依据 docs/adr/ADR-006-fix2：RuleSetArtifact.concurrency。
 */
public class ConcurrencyController {
    
    private final Semaphore semaphore;
    private final ExecutorService executor;
    
    /**
     * @param maxConcurrency 最大并发数（来自 RuleSetArtifact.concurrency）
     */
    public ConcurrencyController(int maxConcurrency) {
        this.semaphore = new Semaphore(maxConcurrency, true);  // 公平锁
        this.executor = Executors.newWorkStealingPool(maxConcurrency);
    }
    
    /**
     * 在并发控制下执行任务。
     */
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(() -> {
            semaphore.acquire();
            try {
                return task.call();
            } finally {
                semaphore.release();
            }
        });
    }
    
    public void shutdown() {
        executor.shutdownNow();
    }
    
    /** 当前等待中的任务数 */
    public int getQueueSize() {
        return semaphore.getQueueLength();
    }
    
    /** 剩余可用并发槽位 */
    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }
}
```

### 3.3 规则集执行器

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleSetExecutor {

    private final GroovySandbox sandbox;
    private final ArtifactStorage artifactStorage;
    private final ConcurrencyController concurrencyController;
    private final ExecutionRepository executionRepo;
    private final ExecutionLogRepository logRepo;
    private final RuleSetArtifactRepository artifactRepo;

    /**
     * 执行规则集（完整执行流程）。
     * 依据 06-运行视图 §6.1.1 Step 10~14。
     */
    @Transactional
    public BatchExecutionResult executeRuleSet(
            String ruleSetArtifactId,
            Map<String, Object> inputContext,
            String triggerType,      // MANUAL / TEST / SCHEDULED
            String traceId) {
        
        // 1. 获取制品
        RuleSetArtifact artifact = artifactRepo.findById(ruleSetArtifactId)
            .orElseThrow(() -> new NotFoundException(
                "RuleSetArtifact", ruleSetArtifactId));
        
        // 2. 创建 Execution 记录
        Execution execution = Execution.builder()
            .id(UUID.randomUUID().toString())
            .ruleSetId(artifact.getRuleSetId())
            .ruleSetArtifactId(artifact.getId())
            .triggerType(triggerType)
            .status(ExecutionStatus.PENDING)
            .inputDataJson(toJson(inputContext))
            .traceId(traceId)
            .startedAt(Instant.now())
            .build();
        executionRepo.save(execution);
        
        // 3. 更新状态为 RUNNING
        execution.setStatus(ExecutionStatus.RUNNING);
        executionRepo.save(execution);
        
        // 4. 下载规则集制品
        try {
            List<RuleScript> scripts = downloadScripts(artifact);
            
            // 5. 获取并发配置
            int concurrency = artifact.getConcurrency() > 0 
                ? artifact.getConcurrency() 
                : 1;
            
            // 6. 创建并发控制器
            try (ConcurrencyController controller = 
                    new ConcurrencyController(concurrency)) {
                
                BatchExecutionResult result = doExecute(execution, scripts, 
                    inputContext, traceId, controller);
                
                // 7. 更新 Execution 记录
                execution.setStatus(result.finalStatus());
                execution.setSuccessCount(result.successCount());
                execution.setFailedCount(result.failedCount());
                execution.setTotalCount(result.totalCount());
                execution.setOutputDataJson(toJson(result.outputContext()));
                execution.setErrorMessage(result.hasErrors() 
                    ? result.errorSummary() : null);
                execution.setFinishedAt(Instant.now());
                execution.setDurationMs(result.totalDurationMs());
                executionRepo.save(execution);
                
                return result;
            }
            
        } catch (Exception e) {
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setErrorMessage("规则集执行失败: " + e.getMessage());
            execution.setFinishedAt(Instant.now());
            executionRepo.save(execution);
            
            return BatchExecutionResult.failed(List.of(
                new RuleExecutionResult(null, "FAILED", e.getMessage(), 0)
            ));
        }
    }
    
    private BatchExecutionResult doExecute(
            Execution execution,
            List<RuleScript> scripts,
            Map<String, Object> inputContext,
            String traceId,
            ConcurrencyController controller) {
        
        List<RuleExecutionResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(scripts.size());
        AtomicLong totalDuration = new AtomicLong(0);
        
        for (RuleScript script : scripts) {
            controller.submit(() -> {
                try {
                    long start = System.currentTimeMillis();
                    ExecutionResult result = sandbox.execute(
                        script.groovyScript(), inputContext);
                    long duration = System.currentTimeMillis() - start;
                    totalDuration.addAndGet(duration);
                    
                    RuleExecutionResult ruleResult = new RuleExecutionResult(
                        script.ruleId(),
                        result.success() ? "SUCCESS" : "FAILED",
                        result.errorMessage(),
                        duration
                    );
                    results.add(ruleResult);
                    
                    // 记录 ExecutionLog
                    saveExecutionLog(script, inputContext, result, traceId);
                    
                } finally {
                    latch.countDown();
                }
                return null;
            });
        }
        
        // 等待全部完成（带超时保护）
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        if (!completed) {
            log.warn("Batch execution timeout, some rules may not have executed");
        }
        
        // 计算汇总状态
        int successCount = (int) results.stream()
            .filter(r -> "SUCCESS".equals(r.status())).count();
        int failedCount = results.size() - successCount;
        
        ExecutionStatus finalStatus = 
            failedCount == 0 ? ExecutionStatus.SUCCESS :
            successCount == 0 ? ExecutionStatus.FAILED :
                                 ExecutionStatus.PARTIAL;
        
        return new BatchExecutionResult(
            results,
            finalStatus,
            successCount,
            failedCount,
            results.size(),
            totalDuration.get(),
            inputContext  // 规则按顺序修改 context
        );
    }
    
    private List<RuleScript> downloadScripts(RuleSetArtifact artifact) {
        try (InputStream is = artifactStorage.download(artifact.getStoragePath())) {
            String content = new String(is.readAllBytes(), UTF_8);
            
            // 制品格式：JSON Lines，每行一个规则
            // {"ruleId": "xxx", "version": 1, "groovy": "..."}
            return Arrays.stream(content.split("\n"))
                .filter(line -> !line.isBlank())
                .map(line -> {
                    JsonNode node = new ObjectMapper().readTree(line);
                    return new RuleScript(
                        node.get("ruleId").asText(),
                        node.get("version").asInt(),
                        node.get("groovy").asText()
                    );
                })
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("下载规则集制品失败: " + artifact.getStoragePath(), e);
        }
    }
    
    private void saveExecutionLog(RuleScript script, Map<String, Object> input,
            ExecutionResult result, String traceId) {
        ExecutionLog logEntry = ExecutionLog.builder()
            .id(String.valueOf(System.nanoTime()))
            .ruleId(script.ruleId())
            .ruleVersionId(null)  // 批量执行不关联特定版本
            .inputDataJson(toJson(input))
            .outputDataJson(result.success() ? toJson(result.outputContext()) : null)
            .status(result.success() ? "SUCCESS" : "FAILED")
            .errorMessage(result.errorMessage())
            .durationMs(result.durationMs())
            .traceId(traceId)
            .build();
        logRepo.save(logEntry);
    }
    
    private String toJson(Object obj) {
        try { return new ObjectMapper().writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "{}"; }
    }
    
    private record RuleScript(String ruleId, int version, String groovyScript) {}
}
```

### 3.4 批量执行结果

```java
public record BatchExecutionResult(
    List<RuleExecutionResult> ruleResults,
    ExecutionStatus finalStatus,
    int successCount,
    int failedCount,
    int totalCount,
    long totalDurationMs,
    Map<String, Object> finalContext   // 最后一次执行后的 context
) {
    public boolean hasErrors() { return failedCount > 0; }
    
    /** 错误汇总（最多前 5 条） */
    public String errorSummary() {
        return ruleResults.stream()
            .filter(r -> !"SUCCESS".equals(r.status()))
            .limit(5)
            .map(r -> r.ruleId() + ": " + r.errorMessage())
            .collect(Collectors.joining("; "));
    }
}

public record RuleExecutionResult(
    String ruleId,
    String status,     // SUCCESS / FAILED
    String errorMessage,
    long durationMs
) {}
```

### 3.5 规则集打包服务（orule-server）

```java
@Service
@RequiredArgsConstructor
public class RuleSetArtifactService {

    private final RuleSetRepository ruleSetRepo;
    private final RuleVersionRepository versionRepo;
    private final ArtifactStorage storage;
    private final RuleSetArtifactRepository artifactRepo;
    private final RuleRepository ruleRepo;
    
    /**
     * 打包规则集（生成 RuleSetArtifact）。
     * 依据 06-运行视图 §6.2.3 RuleSetArtifact 生命周期。
     */
    @Transactional
    public RuleSetArtifact packageRuleSet(String ruleSetId) {
        RuleSet ruleSet = ruleSetRepo.findById(ruleSetId)
            .orElseThrow(() -> new NotFoundException("RuleSet", ruleSetId));
        
        // 1. 获取所有规则
        List<Rule> rules = ruleRepo.findByRuleSetId(ruleSetId);
        
        // 2. 获取每个规则的 PUBLISHED 版本
        List<String> scriptLines = new ArrayList<>();
        for (Rule rule : rules) {
            RuleVersion published = versionRepo
                .findByRuleIdAndStatus(rule.getId(), RuleStatus.PUBLISHED)
                .orElse(null);
            
            if (published != null && published.getGroovySource() != null) {
                String line = new ObjectMapper().writeValueAsString(Map.of(
                    "ruleId", rule.getId(),
                    "ruleCode", rule.getCode(),
                    "version", published.getVersion(),
                    "groovy", published.getGroovySource()
                ));
                scriptLines.add(line);
            }
        }
        
        // 3. 打包为 JSON Lines 格式
        String content = String.join("\n", scriptLines);
        byte[] bytes = content.getBytes(UTF_8);
        
        // 4. 生成版本号
        int nextVersion = artifactRepo.findMaxVersionByRuleSetId(ruleSetId).orElse(0) + 1;
        
        // 5. 上传
        String key = String.format("rulesets/%s/v%d.jsonl", ruleSet.getCode(), nextVersion);
        UploadResult upload = storage.upload(key, bytes);
        
        // 6. 创建 RuleSetArtifact 记录
        RuleSetArtifact artifact = RuleSetArtifact.builder()
            .id(UUID.randomUUID().toString())
            .ruleSetId(ruleSetId)
            .version(nextVersion)
            .storageType(upload.storagePath())
            .storagePath(upload.storagePath())
            .storageUrl(upload.url())
            .fileSize(upload.fileSize())
            .sha256(upload.sha256())
            .ruleCount(scriptLines.size())
            .includedVersionsJson(toJson(extractIncludedVersions(scriptLines)))
            .concurrency(1)  // 默认并发度 1
            .build();
        artifactRepo.save(artifact);
        
        return artifact;
    }
    
    /**
     * 更新规则集制品并发度。
     */
    @Transactional
    public RuleSetArtifact updateConcurrency(String artifactId, int concurrency) {
        RuleSetArtifact artifact = artifactRepo.findById(artifactId)
            .orElseThrow(() -> new NotFoundException("RuleSetArtifact", artifactId));
        artifact.setConcurrency(concurrency);
        return artifactRepo.save(artifact);
    }
    
    private List<Map<String, Object>> extractIncludedVersions(List<String> lines) {
        return lines.stream()
            .map(line -> {
                try {
                    JsonNode node = new ObjectMapper().readTree(line);
                    return Map.of(
                        "ruleId", node.get("ruleId").asText(),
                        "version", node.get("version").asInt()
                    );
                } catch (JsonProcessingException e) {
                    return Map.<String, Object>of();
                }
            })
            .toList();
    }
}
```

### 3.6 REST API

```
# 打包规则集
POST /api/v1/rule-sets/{id}/package

# 触发执行
POST /api/v1/rule-set-artifacts/{artifactId}/execute
Content-Type: application/json
X-Trace-Id: <uuid>

{
    "customer": { "tier": "VIP" },
    "order": { "totalAmount": 300, "discount": 0 }
}

→ 200 OK
{
    "executionId": "xxx",
    "status": "SUCCESS",
    "totalCount": 5,
    "successCount": 5,
    "failedCount": 0,
    "totalDurationMs": 1234,
    "finalContext": { ... }
}

# 查询执行结果
GET /api/v1/executions/{executionId}
GET /api/v1/executions/{executionId}/logs  # 分页 ExecutionLog
GET /api/v1/executions/{executionId}/logs?ruleId=xxx  # 单规则日志

# 更新并发度
PUT /api/v1/rule-set-artifacts/{artifactId}/concurrency
{ "concurrency": 4 }
```

---

## 4. 影响面

- 新增 `com.orule.runtime.execution` 类（RuleSetExecutor / ConcurrencyController 等）
- 新增 `com.orule.server.service.RuleSetArtifactService`
- 新增 4 个 REST 端点
- 不涉及新数据库表（复用 RFC-0014 的 execution / execution_log 表）

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| 单规则集执行 | 5 条规则全部执行成功 |
| 部分失败（PARTIAL） | 1 条失败不影响其他 |
| 全部失败 | FAILED 状态 |
| 并发 1 | 串行执行 |
| 并发 4 | 4 条并行 |
| 并发冲突 | 共享变量被并发修改 |
| 超时保护 | 某条规则超时不影响其他 |
| 执行日志完整性 | 每条规则的 input/output/error 都有日志 |
| RuleSetArtifact 打包 | 上传 + 下载一致性（SHA256） |

---

## 6. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 并发修改共享变量 | 🟡 中 | context 只读，规则只能写自己的字段；测试覆盖 |
| ConcurrencyController 泄露 | 🟢 低 | try-with-resources 自动关闭 |
| 大文件制品（10000 规则） | 🟡 中 | 超时保护；内存流式处理 |
| 包格式解析失败 | 🟢 低 | JSON Lines 格式简单；异常处理 |

---

## 7. 实施步骤

```
1. 创建 ConcurrencyController
2. 实现 RuleSetExecutor
3. 实现 RuleSetArtifactService
4. 实现 REST 端点
5. 单元测试（并发安全）
6. 集成测试（完整打包→执行链路）
7. 性能测试（大批量规则）
8. 并发压测
```

---

## 8. 关联

- 上游：RFC-0019（Groovy 源码）、RFC-0020（单条规则执行）
- 下游：RFC-0022（测试用例批量执行）
- ADR：—
