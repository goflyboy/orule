# RFC-0021: 批量规则执行 + RuleSetArtifact

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：3d · **阶段**：S4
> **关联数据模型**：[RFC-0032 ObjectType 枚举化 + Type 系统简化](RFC-0032-ObjectType枚举化与Type系统简化.md)、[RFC-0033 RuleSetType 与 RuleType](RFC-0033-元数据管理2-RuleSetType与RuleType.md)

---

## 1. 摘要

实现规则集批量执行（单 RuleSet 内多条规则 + 并发控制）和 RuleSetArtifact 打包/下载。
**RFC-0033 修订**：RuleSetArtifact 打包时按 `RuleSetType` 元数据维度聚合 RuleType，
每条规则的沙箱白名单仍由其所属 `RuleType.functionTypeCodes` 独立控制（RFC-0020 §3.6）。

---

## 2. 动机

- 单规则执行是 RFC-0020，本 RFC 扩展到规则集级别（依据 `06-运行视图 §6.1.1` Step 10~14）
- RuleSetArtifact 是并发执行单元（依据 `06-运行视图 §6.2.3`）
- 支撑 RFC-0022（测试用例批量执行）
- **RFC-0033 修订**：RuleSetArtifact 打包需消费 `RuleSetType` 元数据，校验"实例层 RuleSet.type_code ∈ RuleSetType.code"（RFC-0033 §3.7 不变式 #5）

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
└── service/   (packages/orule-server 内)
    ├── RuleSetArtifactService.java  # 规则集打包服务
    └── RuleSetTypeMetaProvider.java # RFC-0033 §3.2 RuleSetType 元数据加载器
```

> **RFC-0033 修订说明**：
> - 新增 `RuleSetTypeMetaProvider`：依据 `RuleSet.type_code` 拉取 `RuleSetType` 及其关联的 `ObjectType` / `FuntionType` / `RuleType`。
> - `RuleSetArtifactService.packageRuleSet` 在打包时校验 RuleSetType 元数据完整性（RFC-0033 §3.7 不变式 #5 #6）。
> - `RuleSetExecutor` 在执行前按每条 Rule 的 `RuleType` 独立构造沙箱（继承自 RFC-0020 §3.6 WhitelistBuilder）。

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

    private final GroovySandbox defaultSandbox;              // 兜底沙箱（用于紧急情况）
    private final ArtifactStorage artifactStorage;
    private final ConcurrencyController concurrencyController;
    private final ExecutionRepository executionRepo;
    private final ExecutionLogRepository logRepo;
    private final RuleSetArtifactRepository artifactRepo;
    private final RuleTypeMetaProvider ruleTypeMetaProvider; // RFC-0033 §3.3
    private final WhitelistBuilder whitelistBuilder;         // RFC-0020 §3.6

    /**
     * 执行规则集（完整执行流程）。
     * 依据 06-运行视图 §6.1.1 Step 10~14。
     *
     * <p><b>RFC-0033 修订</b>：执行前校验 RuleSet.type_code 在 RuleSetType.code 中存在；
     * 每条规则按其 RuleType 独立沙箱（不能跨 RuleType 共享 SDK 白名单）。
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

        // 2. RFC-0033：校验 RuleSet.type_code 在 RuleSetType.code 中存在（不变式 #5）
        RuleSet ruleSet = artifact.getRuleSet();
        RuleSetType ruleSetType = ruleSetTypeMetaProvider.getByCode(ruleSet.getTypeCode())
            .orElseThrow(() -> new NotFoundException(
                "RuleSetType " + ruleSet.getTypeCode() + " 未定义（RFC-0033 §3.7 不变式 #5）"));

        // 3. 创建 Execution 记录
        Execution execution = Execution.builder()
            .id(UUID.randomUUID().toString())
            .ruleSetId(artifact.getRuleSetId())
            .ruleSetArtifactId(artifact.getId())
            .ruleSetTypeId(ruleSetType.getId())          // RFC-0033 补充字段
            .triggerType(triggerType)
            .status(ExecutionStatus.PENDING)
            .inputDataJson(toJson(inputContext))
            .traceId(traceId)
            .startedAt(Instant.now())
            .build();
        executionRepo.save(execution);

        // 4. 更新状态为 RUNNING
        execution.setStatus(ExecutionStatus.RUNNING);
        executionRepo.save(execution);

        // 5. 下载规则集制品
        try {
            List<RuleScript> scripts = downloadScripts(artifact);

            // 6. 获取并发配置
            int concurrency = artifact.getConcurrency() > 0
                ? artifact.getConcurrency()
                : 1;

            // 7. 创建并发控制器
            try (ConcurrencyController controller =
                    new ConcurrencyController(concurrency)) {

                BatchExecutionResult result = doExecute(execution, scripts,
                    inputContext, traceId, controller);

                // 8. 更新 Execution 记录
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

                    // RFC-0033：按 Rule 的 typeCode 拉取 RuleType 元数据，构造专属沙箱
                    RuleType ruleType = ruleTypeMetaProvider.getByCode(script.typeCode())
                        .orElseThrow(() -> new NotFoundException(
                            "RuleType " + script.typeCode() + " 未定义"));
                    SandboxConfig scopedConfig = whitelistBuilder.buildFor(ruleType);
                    GroovySandbox scopedSandbox = new GroovySandbox(scopedConfig);

                    ExecutionResult result = scopedSandbox.execute(
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

                    // 记录 ExecutionLog（含 ruleTypeId，RFC-0033）
                    saveExecutionLog(script, inputContext, result, traceId, ruleType.getId());

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
            // RFC-0033 修订：增加 typeCode 字段（RuleType.programCode 引用），用于执行期按 RuleType 维度构造沙箱
            // {"ruleId": "xxx", "version": 1, "typeCode": "ORDER_DISCOUNT_VALIDATE", "groovy": "..."}
            return Arrays.stream(content.split("\n"))
                .filter(line -> !line.isBlank())
                .map(line -> {
                    JsonNode node = new ObjectMapper().readTree(line);
                    return new RuleScript(
                        node.get("ruleId").asText(),
                        node.get("version").asInt(),
                        node.get("typeCode").asText(),  // RFC-0033：RuleType.programCode
                        node.get("groovy").asText()
                    );
                })
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("下载规则集制品失败: " + artifact.getStoragePath(), e);
        }
    }

    private void saveExecutionLog(RuleScript script, Map<String, Object> input,
            ExecutionResult result, String traceId, String ruleTypeId) {
        ExecutionLog logEntry = ExecutionLog.builder()
            .id(String.valueOf(System.nanoTime()))
            .ruleId(script.ruleId())
            .ruleVersionId(null)  // 批量执行不关联特定版本
            .ruleTypeId(ruleTypeId)  // RFC-0033：补充 RuleType 关联字段
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

    /**
     * RFC-0033 修订：增加 typeCode 字段（RuleType.programCode 引用）。
     * 执行期 RuleSetExecutor 据此拉取 RuleType 元数据，构造该规则的沙箱白名单。
     */
    private record RuleScript(String ruleId, int version, String typeCode, String groovyScript) {}
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
    private final RuleSetTypeRepository ruleSetTypeRepo;   // RFC-0033 §3.2
    private final RuleTypeRepository ruleTypeRepo;         // RFC-0033 §3.3

    /**
     * 打包规则集（生成 RuleSetArtifact）。
     * 依据 06-运行视图 §6.2.3 RuleSetArtifact 生命周期。
     *
     * <p><b>RFC-0033 修订</b>：
     * <ol>
     *   <li>校验 RuleSet.type_code ∈ RuleSetType.code（不变式 #5）</li>
     *   <li>校验 Rule.type_code ∈ RuleType.code（在该 RuleSetType 范围内，不变式 #6）</li>
     *   <li>制品 JSON Lines 每行增加 typeCode 字段</li>
     *   <li>RuleSetArtifact 元数据增加 rule_set_type_id 字段</li>
     * </ol>
     */
    @Transactional
    public RuleSetArtifact packageRuleSet(String ruleSetId) {
        RuleSet ruleSet = ruleSetRepo.findById(ruleSetId)
            .orElseThrow(() -> new NotFoundException("RuleSet", ruleSetId));

        // 1. RFC-0033：校验 RuleSet.type_code 在 RuleSetType.code 中存在（不变式 #5）
        RuleSetType ruleSetType = ruleSetTypeRepo.findByCode(ruleSet.getTypeCode())
            .orElseThrow(() -> new NotFoundException(
                "RuleSetType " + ruleSet.getTypeCode() + " 未定义（RFC-0033 §3.7 不变式 #5）"));

        // 2. 获取所有规则
        List<Rule> rules = ruleRepo.findByRuleSetId(ruleSetId);

        // 3. 获取每个规则的 PUBLISHED 版本
        List<String> scriptLines = new ArrayList<>();
        List<Map<String, Object>> includedVersions = new ArrayList<>();
        for (Rule rule : rules) {

            // RFC-0033：校验 Rule.type_code 在 RuleSetType 范围内（不变式 #6）
            String ruleTypeCode = rule.getTypeCode();
            RuleType ruleType = ruleTypeRepo.findByCode(ruleTypeCode)
                .orElseThrow(() -> new NotFoundException(
                    "RuleType " + ruleTypeCode + " 未定义（RFC-0033 §3.7 不变式 #6）"));
            if (!ruleType.getRuleSetType().getId().equals(ruleSetType.getId())) {
                throw new MetadataInvariantViolationException(
                    "RuleType " + ruleTypeCode + " 不属于 RuleSetType " + ruleSetType.getCode());
            }

            RuleVersion published = versionRepo
                .findByRuleIdAndStatus(rule.getId(), RuleVersionStatus.PUBLISHED)
                .orElse(null);

            if (published != null && published.getGroovySource() != null) {
                // RFC-0033 制品格式修订：增加 typeCode 字段
                String line = new ObjectMapper().writeValueAsString(Map.of(
                    "ruleId",   rule.getId(),
                    "ruleCode", rule.getCode(),
                    "version",  published.getVersion(),
                    "typeCode", ruleTypeCode,                  // RFC-0033
                    "groovy",   published.getGroovySource()
                ));
                scriptLines.add(line);
                includedVersions.add(Map.of(
                    "ruleId",   rule.getId(),
                    "version",  published.getVersion(),
                    "typeCode", ruleTypeCode                   // RFC-0033
                ));
            }
        }

        // 4. 打包为 JSON Lines 格式
        String content = String.join("\n", scriptLines);
        byte[] bytes = content.getBytes(UTF_8);

        // 5. 生成版本号
        int nextVersion = artifactRepo.findMaxVersionByRuleSetId(ruleSetId).orElse(0) + 1;

        // 6. 上传
        String key = String.format("rulesets/%s/v%d.jsonl", ruleSet.getCode(), nextVersion);
        UploadResult upload = storage.upload(key, bytes);

        // 7. 创建 RuleSetArtifact 记录
        RuleSetArtifact artifact = RuleSetArtifact.builder()
            .id(UUID.randomUUID().toString())
            .ruleSetId(ruleSetId)
            .ruleSetTypeId(ruleSetType.getId())              // RFC-0033 补充字段
            .version(nextVersion)
            .storageType(upload.storagePath())
            .storagePath(upload.storagePath())
            .storageUrl(upload.url())
            .fileSize(upload.fileSize())
            .sha256(upload.sha256())
            .ruleCount(scriptLines.size())
            .includedVersionsJson(toJson(includedVersions))
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
- 新增 `com.orule.server.service.RuleSetArtifactService`（**含 RuleSetTypeMetaProvider**）
- 新增 4 个 REST 端点
- 不涉及新数据库表（复用 RFC-0014 的 execution / execution_log 表）
- **execution_log 表新增 `rule_type_id` 字段**（与 RFC-0020 同步）
- **execution 表新增 `rule_set_type_id` 字段**
- **rule_set_artifact 表新增 `rule_set_type_id` 字段**
- **元数据层依赖**：RFC-0033 §3.2 `rule_set_type` / §3.3 `rule_type` / §3.7 不变式 #5 #6
- **Type 系统依赖**：RFC-0032 §3.1 4 Variant Type

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
| **RFC-0033** RuleSet.type_code 缺失 | 抛 `NotFoundException("RuleSetType xxx 未定义")` |
| **RFC-0033** Rule.type_code 缺失或不属于 RuleSetType | 抛 `MetadataInvariantViolationException` |
| **RFC-0033** 多 RuleType 共存（同一 RuleSet 内） | 各规则按其 RuleType 独立沙箱，白名单不互通 |
| **RFC-0032** 规则集内 RuleType 含 ENUM | enum 块由 GroovyCodeGen 按 kind=ENUM 注入；执行期引用正常 |
| **RFC-0033** execution.rule_set_type_id 写入 | 启动期 MetadataIntegrityChecker 自检 |

---

## 6. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 并发修改共享变量 | 🟡 中 | context 只读，规则只能写自己的字段；测试覆盖 |
| ConcurrencyController 泄露 | 🟢 低 | try-with-resources 自动关闭 |
| 大文件制品（10000 规则） | 🟡 中 | 超时保护；内存流式处理 |
| 包格式解析失败 | 🟢 低 | JSON Lines 格式简单；异常处理 |
| **RFC-0033** RuleSet.type_code 引用不存在的 RuleSetType | 🟠 中-高 | packageRuleSet 启动期校验（不变式 #5）；MetadataIntegrityChecker 自检 |
| **RFC-0033** Rule.type_code 不在 RuleSetType 范围内 | 🟡 中 | packageRuleSet 按不变式 #6 校验，抛 MetadataInvariantViolationException |
| **RFC-0033** 制品升级向后兼容（V1 制品无 typeCode 字段）| 🟡 中 | downloadScripts 对 typeCode 缺失做兼容：默认空字符串 + 启动期校验 Rule.type_code |

## 7. 实施步骤

```
1. 创建 ConcurrencyController
2. 实现 RuleSetTypeMetaProvider（RFC-0033 §3.2 元数据加载器，带 5min 缓存）
3. 实现 RuleSetExecutor
   **3a. 接入 RuleTypeMetaProvider + WhitelistBuilder（按每条 Rule 的 RuleType 独立构造沙箱）**
   **3b. downloadScripts 解析 typeCode 字段；saveExecutionLog 写入 rule_type_id**
4. 实现 RuleSetArtifactService
   **4a. 接入 RuleSetTypeRepository + RuleTypeRepository；校验不变式 #5 #6**
   **4b. 制品 JSON Lines 每行增加 typeCode 字段**
5. 实现 REST 端点
6. RFC-0014 V8 ALTER TABLE：
   - execution_log ADD COLUMN rule_type_id VARCHAR(36)
   - execution ADD COLUMN rule_set_type_id VARCHAR(36)
   - rule_set_artifact ADD COLUMN rule_set_type_id VARCHAR(36)
7. 单元测试（并发安全 + RFC-0033 不变式校验）
8. 集成测试（完整打包→执行链路）
9. 性能测试（大批量规则）
10. 并发压测
```

---

## 8. 关联

- 上游：RFC-0019（Groovy 源码）、RFC-0020（单条规则执行）、RFC-0014（数据库 schema + V8 ALTER）
- 下游：RFC-0022（测试用例批量执行）
- **关键依赖**：
  - **RFC-0020 §3.6 WhitelistBuilder** —— 批量执行继承同一构造逻辑
  - **RFC-0032 ObjectType 枚举化** §3.1（4 Variant Type）/ §3.3（entity.ObjectType.Kind=ENUM）
  - **RFC-0033 RuleSetType 与 RuleType** §3.2（RuleSetType 元数据）/ §3.3（RuleType.arguments / functionTypeCodes）/ §3.7（不变式 #5 #6）/ §3.8（FuntionType 重命名）
- ADR：**ADR-006 规则源语言采用 SimpleTS**、**ADR-012 enum 视为 ObjectType 特殊形态**

---

## 9. 修订日志

| 日期 | 修订内容 | 关联 RFC |
|------|---------|---------|
| 2026-09-12 | 状态：DRAFT 重新审视 | — |
| 2026-09-12 | RuleSetExecutor 接入 `RuleTypeMetaProvider` + `WhitelistBuilder`：每条规则按其 RuleType 独立沙箱 | RFC-0033 §3.3 + RFC-0020 §3.6 |
| 2026-09-12 | RuleSetArtifactService 校验 RuleSet.type_code ∈ RuleSetType.code（不变式 #5） | RFC-0033 §3.7 |
| 2026-09-12 | RuleSetArtifactService 校验 Rule.type_code 在 RuleSetType 范围内（不变式 #6） | RFC-0033 §3.7 |
| 2026-09-12 | 制品 JSON Lines 每行增加 `typeCode` 字段（RuleType.programCode 引用） | RFC-0033 §3.3 |
| 2026-09-12 | `Execution` / `ExecutionLog` / `RuleSetArtifact` 三表新增元数据关联字段（`rule_set_type_id` / `rule_type_id`） | RFC-0033 §3.3 |
| 2026-09-12 | RuleSetExecutor 修复 `RuleStatus` → `RuleVersionStatus` 类型枚举误用 | RFC-0014 |
