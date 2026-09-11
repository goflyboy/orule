# RFC-0016: 规则域 + 状态机 API

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：3d · **阶段**：S2

---

## 1. 摘要

实现 Rule / RuleVersion 的 CRUD + 状态机 API（MAINTENANCE → PUBLISHED → RETIRED）。

---

## 2. 动机

- 规则是 MVP 核心实体（依据 `02-用例视图 §2.4.2` "规则编辑"）
- 状态机保证规则的发布流程安全（依据 `04-数据模型 §4.4.5`）
- 支撑后续 RFC-0019（SimpleTS→Groovy）、RFC-0020（执行）

---

## 3. 详细设计

### 3.1 REST API 端点

#### RuleSet

```
GET    /api/v1/rule-sets
GET    /api/v1/rule-sets/{id}
POST   /api/v1/rule-sets
PUT    /api/v1/rule-sets/{id}
DELETE /api/v1/rule-sets/{id}
GET    /api/v1/rule-sets/{id}/with-rules  # 含 Rule 列表
```

#### Rule

```
GET    /api/v1/rules?ruleSetId=xxx
GET    /api/v1/rules/{id}
POST   /api/v1/rules
PUT    /api/v1/rules/{id}
DELETE /api/v1/rules/{id}
GET    /api/v1/rules/{id}/with-versions  # 含所有 RuleVersion
```

#### RuleVersion

```
GET    /api/v1/rule-versions/{id}
GET    /api/v1/rules/{ruleId}/versions       # 某 Rule 的所有版本
GET    /api/v1/rules/{ruleId}/versions/latest  # 最新版本
POST   /api/v1/rule-versions                  # 新建版本（默认 MAINTENANCE）
PUT    /api/v1/rule-versions/{id}             # 编辑 MAINTENANCE 版本
POST   /api/v1/rule-versions/{id}/publish     # MAINTENANCE → PUBLISHED
POST   /api/v1/rule-versions/{id}/retire      # PUBLISHED → RETIRED
POST   /api/v1/rule-versions/{id}/clone       # 从任意版本克隆新版本
DELETE /api/v1/rule-versions/{id}
```

### 3.2 状态机

依据 `04-数据模型 §4.4.5`：

```
┌────────────┐
│ MAINTENANCE│  ← 创建 / 克隆时默认
└──────┬─────┘
       │ POST /publish
       ▼
┌────────────┐
│  PUBLISHED │  ← 至少一个 PUBLISHED 版本才能被执行
└──────┬─────┘
       │ POST /retire
       ▼
┌────────────┐
│   RETIRED  │  ← 不可再被编辑或发布
└────────────┘
```

**状态机约束**：

| 当前状态 | 可转移目标 |
|----------|------------|
| MAINTENANCE | PUBLISHED（通过 publish）/ 删除 |
| PUBLISHED | RETIRED（通过 retire）/ 克隆出新 MAINTENANCE |
| RETIRED | 只能查看；不能编辑、发布、再次转移 |

> **同时刻只能有一个 PUBLISHED 版本**：每个 Rule 在某时刻最多一个 RuleVersion 是 PUBLISHED 状态。新版本 publish 时，旧 PUBLISHED 自动 retire。

### 3.3 关键实现

#### DTO

```java
public record RuleSetDto(
    String id, String code, String name, String description,
    String domainId, String ownerCode, String status,
    Instant createdAt, Instant updatedAt,
    List<RuleDto> rules  // 可选填充
) {}

public record RuleDto(
    String id, String ruleSetId, String code, String name,
    String description, int sortOrder, String ownerCode,
    Instant createdAt, Instant updatedAt
) {}

public record RuleVersionDto(
    String id, String ruleId, int version, String status,
    String description, String simpleTs, String groovySource,
    String changelog, String createdBy,
    Instant createdAt, Instant publishedAt, Instant retiredAt
) {}

public record CreateRuleSetRequest(
    @NotBlank String code, @NotBlank String name,
    String description, @NotBlank String domainId, String ownerCode
) {}

public record CreateRuleRequest(
    @NotBlank String ruleSetId, @NotBlank String code,
    @NotBlank String name, String description, int sortOrder, String ownerCode
) {}

public record CreateRuleVersionRequest(
    @NotBlank String ruleId, String description, String changelog
) {}

public record UpdateRuleVersionRequest(
    String description, String simpleTs, String changelog
) {}
```

#### Service（核心方法）

```java
@Service
@RequiredArgsConstructor
public class RuleVersionService {

    private final RuleVersionRepository versionRepo;
    private final RuleRepository ruleRepo;

    @Transactional
    public RuleVersionDto create(String ruleId, CreateRuleVersionRequest req, String createdBy) {
        Rule rule = ruleRepo.findById(ruleId)
            .orElseThrow(() -> new NotFoundException("Rule", ruleId));
        
        int nextVersion = versionRepo.findMaxVersionByRuleId(ruleId).orElse(0) + 1;
        
        RuleVersion entity = RuleVersion.builder()
            .id(UUID.randomUUID().toString())
            .ruleId(ruleId)
            .version(nextVersion)
            .status(RuleStatus.MAINTENANCE)
            .description(req.description())
            .changelog(req.changelog())
            .createdBy(createdBy)
            .build();
        
        return toDto(versionRepo.save(entity));
    }

    @Transactional
    public RuleVersionDto publish(String versionId) {
        RuleVersion version = versionRepo.findById(versionId)
            .orElseThrow(() -> new NotFoundException("RuleVersion", versionId));
        
        if (version.getStatus() != RuleStatus.MAINTENANCE) {
            throw new ConflictException("只有 MAINTENANCE 状态可以发布，当前: " + version.getStatus());
        }
        
        if (version.getSimpleTs() == null || version.getSimpleTs().isBlank()) {
            throw new ConflictException("SimpleTS 为空，无法发布");
        }
        
        // 自动 retire 当前 PUBLISHED 版本
        versionRepo.findByRuleIdAndStatus(version.getRuleId(), RuleStatus.PUBLISHED)
            .ifPresent(old -> {
                old.setStatus(RuleStatus.RETIRED);
                old.setRetiredAt(Instant.now());
            });
        
        version.setStatus(RuleStatus.PUBLISHED);
        version.setPublishedAt(Instant.now());
        
        return toDto(versionRepo.save(version));
    }

    @Transactional
    public RuleVersionDto retire(String versionId) {
        RuleVersion version = versionRepo.findById(versionId)
            .orElseThrow(() -> new NotFoundException("RuleVersion", versionId));
        
        if (version.getStatus() != RuleStatus.PUBLISHED) {
            throw new ConflictException("只有 PUBLISHED 状态可以退役，当前: " + version.getStatus());
        }
        
        version.setStatus(RuleStatus.RETIRED);
        version.setRetiredAt(Instant.now());
        
        return toDto(versionRepo.save(version));
    }

    @Transactional
    public RuleVersionDto clone(String sourceVersionId, String changelog, String createdBy) {
        RuleVersion source = versionRepo.findById(sourceVersionId)
            .orElseThrow(() -> new NotFoundException("RuleVersion", sourceVersionId));
        
        int nextVersion = versionRepo.findMaxVersionByRuleId(source.getRuleId()).orElse(0) + 1;
        
        RuleVersion newVersion = RuleVersion.builder()
            .id(UUID.randomUUID().toString())
            .ruleId(source.getRuleId())
            .version(nextVersion)
            .status(RuleStatus.MAINTENANCE)
            .description("Cloned from v" + source.getVersion())
            .simpleTs(source.getSimpleTs())
            .groovySource(source.getGroovySource())  // 编译产物可复用
            .changelog(changelog)
            .createdBy(createdBy)
            .build();
        
        return toDto(versionRepo.save(newVersion));
    }
}
```

---

## 4. 影响面

- 新增 3 套 Entity / Repository / Service / Controller
- 新增 7 个 DTO + 4 个 Request
- 数据库 schema 不变

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| CRUD 单元测试 | Service 层 Mockito |
| 状态机测试 | 全部合法/非法转移 |
| 并发 publish | 同时 publish 同一 Rule 的不同版本（应自动 retire 旧的） |
| 自动版本号 | 创建/克隆时版本号自增 |
| 删除保护 | 有 RuleVersion 时不能删 Rule |
| API 文档 | OpenAPI |

---

## 6. 风险

- **R1**：状态机并发问题（同时 publish 两个版本）→ 缓解：数据库乐观锁 `@Version`
- **R2**：删除 Rule 时未级联删除 RuleVersion → 缓解：REST 返回 409，提示用户先删版本
- **R3**：版本号 gap（手动指定）→ 缓解：版本号严格自增，不接受外部指定

---

## 7. 实施步骤

```
1. 创建 Entity（RuleSet / Rule / RuleVersion）
2. 创建 Repository（带分页 + 自定义查询）
3. 创建 Service（含状态机校验）
4. 创建 Controller
5. 单元测试
6. 集成测试
7. OpenAPI 注解
```

---

## 8. 关联

- 上游：RFC-0014、RFC-0015
- 下游：RFC-0019（编译生成 SimpleTS）、RFC-0020（执行）
- ADR：—
