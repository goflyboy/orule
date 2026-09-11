# RFC-0017: ArtifactStorage 接口 + LocalStorage 实现

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：2d · **阶段**：S2

---

## 1. 摘要

实现 `ArtifactStorage` 抽象接口（依据 ADR-010），提供 `LocalStorage` 默认实现，支持 upload/download/delete/list 操作。

---

## 2. 动机

- MVP 必须能保存 Groovy 编译产物（依据 ADR-010 默认本地）
- 接口抽象允许二期无缝切到 S3/OSS
- 依据 `06-运行视图 §6.3.3` 的 ArtifactStorage 设计

---

## 3. 详细设计

### 3.1 接口定义

```java
// packages/orule-common/src/main/java/com/orule/common/storage/ArtifactStorage.java
package com.orule.common.storage;

import java.io.InputStream;

public interface ArtifactStorage {

    /**
     * 上传制品
     * @param key 相对路径，如 "rules/ORDER_DISCOUNT/v1.jar"
     * @param content 制品内容
     * @return 上传结果（含 URL、SHA256）
     */
    UploadResult upload(String key, byte[] content);

    /**
     * 下载制品
     */
    InputStream download(String key);

    /**
     * 删除制品
     */
    void delete(String key);

    /**
     * 检查制品是否存在
     */
    boolean exists(String key);

    /**
     * 列出某前缀下的所有制品
     */
    List<String> list(String prefix);

    /**
     * 获取可访问 URL（仅 HTTP 后端；本地后端返回 null）
     */
    String getUrl(String key);
}
```

```java
public record UploadResult(
    String storagePath,  // 实际存储路径
    String url,          // 可下载 URL（可选）
    long fileSize,       // 文件大小
    String sha256        // 校验和
) {}
```

### 3.2 配置类

```java
// packages/orule-common/src/main/java/com/orule/common/storage/StorageConfig.java
@ConfigurationProperties(prefix = "orule.storage")
public record StorageConfig(
    StorageType type,
    LocalConfig local,
    S3Config s3,
    OssConfig oss,
    MinioConfig minio
) {
    public enum StorageType { local, s3, oss, minio }

    public record LocalConfig(String basePath, String baseUrl) {}
    public record S3Config(String bucket, String region, String baseUrl,
                          String accessKey, String secretKey) {}
    public record OssConfig(String bucket, String endpoint, String baseUrl,
                           String accessKey, String secretKey) {}
    public record MinioConfig(String bucket, String endpoint, String baseUrl,
                             String accessKey, String secretKey) {}
}
```

### 3.3 LocalStorage 实现

```java
// packages/orule-common/src/main/java/com/orule/common/storage/LocalStorage.java
@Slf4j
public class LocalStorage implements ArtifactStorage {

    private final Path basePath;
    private final String baseUrl;

    public LocalStorage(LocalConfig config) {
        this.basePath = Paths.get(config.basePath()).toAbsolutePath();
        this.baseUrl = config.baseUrl();
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new RuntimeException("无法创建存储目录: " + basePath, e);
        }
    }

    @Override
    public UploadResult upload(String key, byte[] content) {
        // 路径安全检查：禁止 ../ 
        validateKey(key);
        Path target = basePath.resolve(key).normalize();
        if (!target.startsWith(basePath)) {
            throw new IllegalArgumentException("非法的存储路径: " + key);
        }
        
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            
            String sha256 = DigestUtils.sha256Hex(content);
            long size = content.length;
            String url = baseUrl != null ? baseUrl + "/" + key : null;
            
            log.info("Uploaded artifact: key={}, size={}, sha256={}", key, size, sha256);
            return new UploadResult(key, url, size, sha256);
        } catch (IOException e) {
            throw new RuntimeException("上传失败: " + key, e);
        }
    }

    @Override
    public InputStream download(String key) {
        validateKey(key);
        Path target = basePath.resolve(key).normalize();
        if (!target.startsWith(basePath)) {
            throw new IllegalArgumentException("非法的存储路径: " + key);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new RuntimeException("下载失败: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        Path target = basePath.resolve(key).normalize();
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new RuntimeException("删除失败: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        validateKey(key);
        return Files.exists(basePath.resolve(key));
    }

    @Override
    public List<String> list(String prefix) {
        Path dir = basePath.resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .map(p -> basePath.relativize(p).toString().replace('\\', '/'))
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("列出失败: " + prefix, e);
        }
    }

    @Override
    public String getUrl(String key) {
        return baseUrl != null ? baseUrl + "/" + key : null;
    }

    private void validateKey(String key) {
        if (key == null || key.contains("..") || key.startsWith("/") || key.contains("\\")) {
            throw new IllegalArgumentException("非法的存储 key: " + key);
        }
    }
}
```

### 3.4 工厂 + Spring 配置

```java
// packages/orule-server/src/main/java/com/orule/server/config/StorageConfig.java
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(prefix = "orule.storage", name = "type", havingValue = "local", matchIfMissing = true)
    public ArtifactStorage localStorage(StorageProperties props) {
        return new LocalStorage(props.getLocal());
    }

    // 二期：S3 / OSS / Minio bean
}
```

### 3.5 application.yml（MVP 默认配置）

```yaml
orule:
  storage:
    type: local   # MVP 默认
    local:
      base-path: ~/orule/data/artifacts
      base-url: http://localhost:8080/api/v1/artifacts
```

### 3.6 服务端下载端点

```java
// orule-server 提供一个文件下载端点，仅本地存储时使用
@RestController
@RequestMapping("/api/v1/artifacts")
@RequiredArgsConstructor
public class ArtifactDownloadController {

    private final ArtifactStorage storage;

    @GetMapping("/**")
    public ResponseEntity<Resource> download(HttpServletRequest request) {
        String key = extractKey(request);
        if (!storage.exists(key)) {
            return ResponseEntity.notFound().build();
        }
        InputStream stream = storage.download(key);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new InputStreamResource(stream));
    }
}
```

---

## 4. 影响面

- 新增 `ArtifactStorage` 接口 + `LocalStorage` 实现
- 新增 `StorageConfig` 配置类
- 新增下载端点 `/api/v1/artifacts/**`
- 数据库 schema 不变（依据 ADR-010）

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| 上传下载一致性 | upload 后 download 字节相同 |
| 路径穿越防护 | `../etc/passwd` 等被拒绝 |
| 删除幂等 | 重复 delete 不抛异常 |
| 列出文件 | list 正确返回 |
| 大文件 | 100 MB 文件 upload/download |
| 并发安全 | 多线程同时 upload 同一目录 |
| 集成测试 | 通过 REST 上传 → rule_artifact 记录 → 下载 |

---

## 6. 风险

- **R1**：路径穿越漏洞 → 缓解：`validateKey()` + `normalize().startsWith(basePath)` 双重校验
- **R2**：大文件内存爆炸 → 缓解：MVP 限制 ≤ 10 MB；二期流式
- **R3**：磁盘满 → 缓解：监控 + 告警（RFC-0027）

---

## 7. 实施步骤

```
1. 在 orule-common 创建 ArtifactStorage 接口 + UploadResult
2. 在 orule-common 创建 StorageConfig 配置类
3. 在 orule-common 创建 LocalStorage 实现
4. 在 orule-server 创建 Spring Config
5. 在 orule-server 创建 ArtifactDownloadController
6. 单元测试（关键：路径安全 + 字节一致性）
7. 集成测试（与 RFC-0019 配合：编译产物上传）
```

---

## 8. 关联

- 上游：RFC-0014
- 下游：RFC-0019（编译产物上传）、RFC-0020（执行时下载）
- ADR：**ADR-010 ArtifactStorage 可配置通用文件服务器**
