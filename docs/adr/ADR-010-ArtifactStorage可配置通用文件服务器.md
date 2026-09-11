# ADR-010：RuleArtifact / RuleSetArtifact 制品存储采用可配置通用文件服务器

> 状态：已通过
> 日期：2026-09-11
> 决策者：架构组
> 相关：04-数据模型、05-技术模型、06-运行视图

---

## 决策

引入 **可配置的通用文件服务器（Artifact Storage）** 作为 RuleArtifact / RuleSetArtifact 制品的统一存储层。

**核心特征**：

1. **可配置**：通过 Spring Boot 配置切换存储后端（不修改业务代码）
2. **可替换**：未来支持 S3 / OSS / MinIO 等云存储
3. **接口抽象**：业务层只依赖 `ArtifactStorage` 接口，不关心具体实现
4. **MVP 默认**：本地文件系统（路径可配置）

---

## 接口设计

```java
// orule-common/artifact/ArtifactStorage.java
public interface ArtifactStorage {
    /**
     * 上传制品文件
     * @param storageKey 全局唯一 key（如 "rulesets/ORDER_DISCOUNT/v1.jar"）
     * @param content 制品二进制内容
     * @param metadata 制品元数据（contentType、checksum、engineType 等）
     * @return 上传后的完整 URI（相对路径，由 baseUrl 拼接）
     */
    StoredArtifact upload(String storageKey, byte[] content, ArtifactMetadata metadata);

    /**
     * 下载制品文件
     */
    InputStream download(String artifactUri);

    /**
     * 删除制品文件（仅 RETIRED 状态允许）
     */
    void delete(String artifactUri);

    /**
     * 列出制品（按前缀过滤）
     */
    List<StoredArtifact> list(String prefix);

    /**
     * 检查制品是否存在
     */
    boolean exists(String artifactUri);
}

public record ArtifactMetadata(
    String contentType,      // "application/java-archive" / "text/x-groovy" 等
    String checksum,         // SHA-256
    String engineType,       // GROOVY / CRULEENGINE / PYTHON
    Long sizeBytes,
    Map<String, String> customTags
) {}

public record StoredArtifact(
    String storageKey,
    String artifactUri,      // 相对路径，如 "rulesets/ORDER_DISCOUNT/v1.jar"
    String absoluteUri,      // 完整 URL（含 baseUrl）
    Long sizeBytes,
    Instant uploadedAt
) {}
```

---

## MVP 实现：本地文件系统

```java
// orule-server/infrastructure/storage/LocalFileSystemStorage.java
@Component
@ConditionalOnProperty(name = "orule.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileSystemStorage implements ArtifactStorage {

    @Value("${orule.storage.local.base-path:/var/orule/artifacts}")
    private String basePath;

    @Value("${orule.storage.local.base-url:http://localhost:8080/api/v1/artifacts}")
    private String baseUrl;

    @Override
    public StoredArtifact upload(String storageKey, byte[] content, ArtifactMetadata metadata) {
        // 1. 防止路径穿越（storageKey 必须是相对路径，不允许 ../）
        if (storageKey.contains("..") || storageKey.startsWith("/")) {
            throw new IllegalArgumentException("非法 storageKey: " + storageKey);
        }

        Path target = Paths.get(basePath, storageKey);
        Files.createDirectories(target.getParent());

        // 2. 写入文件
        Files.write(target, content);

        // 3. 写入 metadata sidecar 文件（.meta.json）
        Path metaFile = Paths.get(basePath, storageKey + ".meta.json");
        ObjectMapper.writeValue(metaFile.toFile(), metadata);

        // 4. 返回
        String artifactUri = storageKey;  // 相对路径
        String absoluteUri = baseUrl + "/" + storageKey;
        return new StoredArtifact(storageKey, artifactUri, absoluteUri, (long) content.length, Instant.now());
    }

    @Override
    public InputStream download(String artifactUri) {
        Path target = Paths.get(basePath, artifactUri);
        return Files.newInputStream(target);
    }

    @Override
    public void delete(String artifactUri) {
        Path target = Paths.get(basePath, artifactUri);
        Files.deleteIfExists(target);
        Files.deleteIfExists(Paths.get(basePath, artifactUri + ".meta.json"));
    }

    @Override
    public List<StoredArtifact> list(String prefix) {
        // 遍历 basePath + prefix 下的所有文件
        ...
    }

    @Override
    public boolean exists(String artifactUri) {
        return Files.exists(Paths.get(basePath, artifactUri));
    }
}
```

---

## 未来扩展实现：S3 / OSS / MinIO

```java
// orule-server/infrastructure/storage/S3ArtifactStorage.java
@Component
@ConditionalOnProperty(name = "orule.storage.type", havingValue = "s3")
public class S3ArtifactStorage implements ArtifactStorage {

    @Autowired private S3Client s3Client;
    @Value("${orule.storage.s3.bucket}")  private String bucket;
    @Value("${orule.storage.s3.region}")  private String region;
    @Value("${orule.storage.s3.base-url}") private String baseUrl;

    @Override
    public StoredArtifact upload(String storageKey, byte[] content, ArtifactMetadata metadata) {
        PutObjectRequest req = PutObjectRequest.builder()
            .bucket(bucket)
            .key(storageKey)
            .contentType(metadata.contentType())
            .metadata(Map.of(
                "checksum", metadata.checksum(),
                "engine-type", metadata.engineType()
            ))
            .build();
        s3Client.putObject(req, RequestBody.fromBytes(content));

        return new StoredArtifact(storageKey, storageKey, baseUrl + "/" + storageKey, ...);
    }
    ...
}
```

---

## 配置（application.yml）

```yaml
orule:
  storage:
    type: local   # local | s3 | oss | minio
    local:
      base-path: /var/orule/artifacts
      base-url: http://localhost:8080/api/v1/artifacts
    # s3:
    #   type: s3
    #   bucket: orule-artifacts
    #   region: us-east-1
    #   base-url: https://orule-artifacts.s3.amazonaws.com
    #   credentials:
    #     access-key: ${AWS_ACCESS_KEY}
    #     secret-key: ${AWS_SECRET_KEY}
```

---

## 下载端点

orule-server 暴露统一的下载端点，**对客户端透明**（不暴露存储后端细节）：

```java
// orule-server/interfaces/rest/ArtifactDownloadController.java
@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactDownloadController {

    @Autowired private ArtifactStorage storage;

    @GetMapping("/**")
    public ResponseEntity<Resource> download(HttpServletRequest request) {
        // 1. 提取相对 URI（去掉 /api/v1/artifacts 前缀）
        String artifactUri = extractRelativeUri(request);

        // 2. 鉴权（检查调用方是否有权限下载该 RuleArtifact）
        //    - 内部 orule-runtime 调用：放行
        //    - 外部调用：检查权限（租户隔离）
        authorize(artifactUri);

        // 3. 从存储读取
        InputStream in = storage.download(artifactUri);

        // 4. 返回
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new InputStreamResource(in));
    }
}
```

**两种调用方式**：

1. **通过 orule-server 转发**（默认）：
   - 客户端 → `GET http://orule-server:8080/api/v1/artifacts/{artifactUri}`
   - orule-server 鉴权 + 读取 + 返回
   - 适用：跨网络、安全要求高

2. **直连存储后端**（未来）：
   - 客户端 → `GET https://s3.amazonaws.com/{bucket}/{artifactUri}`（预签名 URL）
   - 适用：内网环境、大文件、减少 orule-server 流量

---

## RuleSetArtifact.concurrency 并发度

> OPEN-Q29 决策

```sql
-- rule_set_artifact 表新增字段
ALTER TABLE rule_set_artifact ADD COLUMN concurrency INT DEFAULT 1;
```

```java
// orule-runtime/application/BatchExecutionService.java
@Service
public class BatchExecutionService {

    public BatchExecutionResult batchExecute(RuleSetArtifact artifact, Map<String, Object> facts) {
        int concurrency = artifact.getConcurrency() != null ? artifact.getConcurrency() : 1;

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<ExecutionResult>> futures = new ArrayList<>();

        for (RuleArtifact ruleArtifact : artifact.getRuleArtifacts()) {
            futures.add(executor.submit(() -> {
                return executionService.execute(ruleArtifact, facts);
            }));
        }

        // 收集结果
        ...
    }
}
```

**并发度说明**：

| 并发度 | 行为 | 适用场景 |
|--------|------|----------|
| 1 | 顺序执行 | 规则有依赖关系（A 改 facts 后 B 才执行） |
| N (2~10) | 并行执行 | 规则独立；CPU 密集；提高吞吐 |
| -1 | 全并行 | 规则完全独立；最大吞吐（注意线程池上限） |

---

## TraceId 传递

> OPEN-Q31 决策

采用**自定义 Header** `X-Trace-Id`（不引入 W3C Trace Context 规范）。

```java
// orule-common/trace/TraceIdFilter.java
@Component
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        String traceId = req.getHeader(HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }
        try {
            MDC.put(MDC_KEY, traceId);
            res.setHeader(HEADER, traceId);  // 回传给调用方
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```

```java
// orule-runtime 调用 orule-server 时传递 traceId
// orule-runtime/infrastructure/client/ArtifactClient.java
public byte[] downloadArtifact(String artifactUri) {
    return restClient.get()
        .uri(oruleServerUrl + "/api/v1/artifacts/" + artifactUri)
        .header(TraceIdFilter.HEADER, MDC.get(TraceIdFilter.MDC_KEY))  // 传递 traceId
        .retrieve()
        .body(byte[].class);
}
```

**为什么不采用 W3C Trace Context**：

| 维度 | 自定义 X-Trace-Id | W3C Trace Context |
|------|-------------------|-------------------|
| 实现成本 | 1 个 Filter + Header | 需要 traceparent / tracestate 解析 |
| 跨语言支持 | 任意语言都支持 | 需要实现 W3C 标准 |
| 当前阶段 MVP | 够用 | 过度设计 |
| 未来升级 | 可平滑迁移到 W3C | — |

> **未来升级路径**：等接入 Jaeger / Zipkin 等 APM 时再切换到 W3C Trace Context（保持 traceId 作为 baggage）。

---

## 决策日志

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-11 | 引入可配置 ArtifactStorage 接口（默认本地文件系统） | 用户明确要求"可配置的通用文件服务器"作为扩展点 |
| 2026-09-11 | RuleSetArtifact.concurrency 字段控制批量执行并发度 | 用户同意 OPEN-Q29 |
| 2026-09-11 | TraceId 传递用自定义 X-Trace-Id Header | 用户同意 OPEN-Q31；MVP 阶段实现最简 |
