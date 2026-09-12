# Worktree B · feature/b-controller · GroovySourceIntake 落库服务

> **分支**：`feature/b-controller`
> **路径**：`../orule-feature-b-controller`
> **基于**：`feature/a-prime`（含 ADR-012-Aprime + RFC-0018/0019 修订）
> **目标**：在 orule-server 实现 `GroovySourceIntakeController` / `GroovySourceIntakeService`，接收本地 Skill 编译产物落库
> **范围**：orule-server 新增 4 个文件 + 单测

---

## 任务清单

```
[ ] 1. 新增 DTO: GroovySourceIntakeRequest（record，含 jakarta.validation）
[ ] 2. 新增 DTO: GroovySourceIntakeResponse（record）
[ ] 3. 新增 Service: GroovySourceIntakeService
       - intake(ruleVersionId, request) 入口
       - SHA256 一致性校验
       - 成功 / 失败分支（成功：落 RuleVersion.groovy_source + 上传 Artifact + 落 RuleArtifact.SUCCESS）
       - 失败：保留旧值 + 落 RuleArtifact.FAILED（不传 ArtifactStorage）
[ ] 4. 新增 Controller: GroovySourceIntakeController
       - POST /mcp/tools/orule.rule.publishCompiledGroovy
       - 复用 RFC-0018 §3.6 ExecutionResponse 出参约定
[ ] 5. 自定义异常：RuleVersionNotFoundException（如有 NotFoundException 可复用）
[ ] 6. 单测（WebMvcTest + Mockito）：
       - 成功用例：完整落库 + Artifact 上传
       - 失败用例：compileLog 非空 → FAILED，不上传
       - SHA256 mismatch → 422 Unprocessable Entity
       - groovySource 长度上限 → 400
       - sha256 缺失 → 400
       - ruleVersionId 不存在 → 404
[ ] 7. 集成测试（Testcontainers MySQL + LocalStorage）：
       - 端到端落库后查 RuleArtifact.compileStatus = SUCCESS
       - 失败用例：RuleArtifact.compileStatus = FAILED + compileLog 非空
[ ] 8. 更新 docs/04-数据模型.md（如有新增列）
[ ] 9. 自检：mvn -pl packages/orule-server -am clean test
```

---

## 设计要点

### 必看文档
- ADR-012-Aprime §5（controller/service/dto 三件套）
- RFC-0019 §3.6（落地设计，含 SHA256 + 字段非空校验）
- RFC-0020 §3.3 MAX_SCRIPT_LENGTH = 100KB（与 groovySource 上限对齐）

### 不做
- ❌ 不实现 SimpleTSParser / GroovyCodeGen / TsAstParser（迁到 Skill）
- ❌ 不实现 DomainMeta 拼装
- ❌ 不引入 org.graalvm.polyglot 依赖

### 依赖新增
- 无新增（沿用 orule-server 现有依赖：jakarta.validation、lombok、spring-boot-starter-web、spring-boot-starter-test）

### 与路径 c/d 的边界
- **与 c（whitelist）**：本仓内或ule-runtime 沙箱启动期读 `simplets-whitelist.json` — 路径 c 实现
- **与 d（cleanup）**：本仓先实现新增服务；d 完成后**不删除**本服务，是负责删除 SimpleTSParser + GraalJS

---

## 落点文件清单

```
packages/orule-server/src/main/java/com/orule/server/
├── controller/
│   └── GroovySourceIntakeController.java     ★ 新增
├── service/
│   └── GroovySourceIntakeService.java        ★ 新增
└── dto/
    ├── GroovySourceIntakeRequest.java        ★ 新增
    └── GroovySourceIntakeResponse.java       ★ 新增

packages/orule-server/src/test/java/com/orule/server/
├── controller/
│   └── GroovySourceIntakeControllerTest.java ★ 新增（WebMvcTest）
└── service/
    └── GroovySourceIntakeServiceTest.java    ★ 新增（Mockito）
```

---

## 提交流程

```
1. 在 ../orule-feature-b-controller 工作
2. 完成后 git add + commit 到 feature/b-controller
3. 不推远端（WT3 = WT3A）
4. 完成后通知主仓，准备与 feature/c-whitelist / feature/d-cleanup 合并
```

---

## 不在范围（推迟）

- 完整 EF Core / JPA 测试（用 H2 内存库即可）
- MCP Server 框架选型（先用原生 REST POST，符合 RFC-0019 §3.7）
- TLS / mTLS 配置（推迟到部署 RFC）
