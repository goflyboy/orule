# RFC-0015: 元数据域 CRUD API

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：3d · **阶段**：S1

---

## 1. 摘要

实现元数据域 5 张表的 CRUD REST API：`domain_type`、`object_type`、`attribute_type`、`enum_type`、`function_lib`。基于 Spring Data JPA + Spring Web。

---

## 2. 动机

- 元数据是规则的"字典"，必须先于规则 API 实现
- 依据 `02-用例视图 §2.4.1` 的"元数据管理"用例
- 后续 RFC-0018（SimpleTS 解析器）依赖 attribute_type 的 schema 注入

---

## 3. 详细设计

### 3.1 REST API 端点

依据 `03-逻辑视图 §3.5.2` 的接口契约：

#### DomainType

```
GET    /api/v1/domain-types               # 列表
GET    /api/v1/domain-types/{id}          # 详情
POST   /api/v1/domain-types               # 创建
PUT    /api/v1/domain-types/{id}          # 更新
DELETE /api/v1/domain-types/{id}          # 删除
GET    /api/v1/domain-types/by-code/{code}  # 按 code 查
```

#### ObjectType

```
GET    /api/v1/object-types               # 列表（支持 ?domainId=xxx）
GET    /api/v1/object-types/{id}
POST   /api/v1/object-types
PUT    /api/v1/object-types/{id}
DELETE /api/v1/object-types/{id}
GET    /api/v1/object-types/{id}/with-attributes  # 详情（含属性）
```

#### AttributeType

```
GET    /api/v1/attribute-types?objectId=xxx
GET    /api/v1/attribute-types/{id}
POST   /api/v1/attribute-types
PUT    /api/v1/attribute-types/{id}
DELETE /api/v1/attribute-types/{id}
```

#### EnumType + EnumValue

```
GET    /api/v1/enum-types
GET    /api/v1/enum-types/{id}
POST   /api/v1/enum-types
PUT    /api/v1/enum-types/{id}
DELETE /api/v1/enum-types/{id}
GET    /api/v1/enum-types/{id}/values   # 含枚举值列表
```

#### FunctionLib

```
GET    /api/v1/function-libs?category=xxx
GET    /api/v1/function-libs/{id}
POST   /api/v1/function-libs
PUT    /api/v1/function-libs/{id}
DELETE /api/v1/function-libs/{id}
```

### 3.2 DTO 设计

```java
// packages/orule-common/src/main/java/com/orule/common/dto/MetadataDto.java

public record DomainTypeDto(
    String id,
    String code,
    String name,
    String description,
    String ownerCode,
    Instant createdAt,
    Instant updatedAt
) {}

public record ObjectTypeDto(
    String id,
    String domainId,
    String code,
    String name,
    String description,
    List<AttributeTypeDto> attributes  // 仅详情时填充
) {}

public record AttributeTypeDto(
    String id,
    String objectId,
    String code,
    String name,
    String dataType,    // string/int/decimal/boolean/date/enum/object/array
    boolean required,
    String defaultValue,
    String description
) {}

public record EnumTypeDto(
    String id,
    String code,
    String name,
    String description,
    List<EnumValueDto> values
) {}

public record EnumValueDto(
    String id,
    String enumId,
    String code,
    String name,
    int sortOrder
) {}

public record FunctionLibDto(
    String id,
    String code,
    String name,
    String signature,
    String description,
    String category,
    boolean builtin
) {}
```

### 3.3 JPA Entity

```java
// packages/orule-common/src/main/java/com/orule/common/entity/DomainType.java
@Entity
@Table(name = "domain_type")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DomainType {
    @Id
    private String id;
    
    @Column(nullable = false, unique = true, length = 64)
    private String code;
    
    @Column(nullable = false, length = 128)
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "owner_code", length = 64)
    private String ownerCode;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

> 其他 5 个 Entity 类似，省略。

### 3.4 Repository

```java
// packages/orule-server/src/main/java/com/orule/server/repository/DomainTypeRepository.java
public interface DomainTypeRepository extends JpaRepository<DomainType, String> {
    Optional<DomainType> findByCode(String code);
    boolean existsByCode(String code);
}
```

### 3.5 Service

```java
// packages/orule-server/src/main/java/com/orule/server/service/DomainTypeService.java
@Service
@RequiredArgsConstructor
public class DomainTypeService {

    private final DomainTypeRepository repository;

    @Transactional(readOnly = true)
    public List<DomainTypeDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public DomainTypeDto findById(String id) {
        return repository.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new NotFoundException("DomainType", id));
    }

    @Transactional
    public DomainTypeDto create(CreateDomainTypeRequest request) {
        if (repository.existsByCode(request.code())) {
            throw new ConflictException("DomainType code 已存在: " + request.code());
        }
        DomainType entity = DomainType.builder()
            .id(UUID.randomUUID().toString())
            .code(request.code())
            .name(request.name())
            .description(request.description())
            .ownerCode(request.ownerCode())
            .build();
        return toDto(repository.save(entity));
    }

    @Transactional
    public DomainTypeDto update(String id, UpdateDomainTypeRequest request) {
        DomainType entity = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("DomainType", id));
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setOwnerCode(request.ownerCode());
        return toDto(repository.save(entity));
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("DomainType", id);
        }
        // 检查是否被引用（ObjectType）
        // ... 二期：增加引用检查
        repository.deleteById(id);
    }

    private DomainTypeDto toDto(DomainType entity) {
        return new DomainTypeDto(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            entity.getDescription(),
            entity.getOwnerCode(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
```

### 3.6 Controller

```java
// packages/orule-server/src/main/java/com/orule/server/controller/DomainTypeController.java
@RestController
@RequestMapping("/api/v1/domain-types")
@RequiredArgsConstructor
public class DomainTypeController {

    private final DomainTypeService service;

    @GetMapping
    public Result<List<DomainTypeDto>> list() {
        return Result.success(service.findAll());
    }

    @GetMapping("/{id}")
    public Result<DomainTypeDto> get(@PathVariable String id) {
        return Result.success(service.findById(id));
    }

    @GetMapping("/by-code/{code}")
    public Result<DomainTypeDto> getByCode(@PathVariable String code) {
        return Result.success(service.findByCode(code));
    }

    @PostMapping
    public Result<DomainTypeDto> create(@Valid @RequestBody CreateDomainTypeRequest request) {
        return Result.success(service.create(request));
    }

    @PutMapping("/{id}")
    public Result<DomainTypeDto> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateDomainTypeRequest request) {
        return Result.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.delete(id);
        return Result.success();
    }
}
```

> 其他 4 个 Controller（ObjectType / AttributeType / EnumType / FunctionLib）类似，省略。

### 3.7 统一响应格式

```java
// packages/orule-common/src/main/java/com/orule/common/dto/Result.java
public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> success(T data) {
        return new Result<>(0, "success", data);
    }
    public static <T> Result<T> success() {
        return new Result<>(0, "success", null);
    }
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
```

### 3.8 全局异常处理

```java
// packages/orule-server/src/main/java/com/orule/server/exception/GlobalExceptionHandler.java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(404)
            .body(Result.error(404, e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Result<Void>> handleConflict(ConflictException e) {
        return ResponseEntity.status(409)
            .body(Result.error(409, e.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Result<Void>> handleValidation(ValidationException e) {
        return ResponseEntity.status(400)
            .body(Result.error(400, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("Internal error", e);
        return ResponseEntity.status(500)
            .body(Result.error(500, "Internal server error"));
    }
}
```

---

## 4. 影响面

- 新增 5 套 Entity / Repository / Service / Controller
- 新增 6 个 DTO + 3 个 Request + Result / 异常体系
- 数据库 schema 不变（依赖 RFC-0014）

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| 单元测试 | Service 层 Mockito 覆盖（≥80%） |
| 集成测试 | @SpringBootTest + Testcontainers |
| API 测试 | MockMvc 全端点验证 |
| 字段校验 | `@Valid` 测试 |
| 异常路径 | 404 / 409 / 400 / 500 全覆盖 |
| 性能 | 列表查询 < 100ms |

---

## 6. 风险

- **R1**：5 套 CRUD 工作量大 → 缓解：抽公共 BaseCrudService
- **R2**：循环引用（DomainType 删除检查 ObjectType）→ 缓解：二期再实现级联检查
- **R3**：Entity 扫描配置 → 缓解：统一 `@EntityScan("com.orule.common.entity")`

---

## 7. 实施步骤

```
1. 在 orule-common 创建 6 个 Entity
2. 在 orule-server 创建 5 个 Repository
3. 在 orule-server 创建 5 个 Service（含 BaseCrudService 抽象）
4. 在 orule-server 创建 5 个 Controller
5. 创建 Result / 全局异常处理
6. 单元测试
7. 集成测试（Testcontainers + MySQL）
8. API 文档（OpenAPI 注解）
```

---

## 8. 关联

- 上游：RFC-0014（Flyway schema）
- 下游：RFC-0018（SimpleTS Schema 注入）、RFC-0024（编辑器元数据选择）
- ADR：—
