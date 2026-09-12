# Worktree C · feature/c-whitelist · SimpleTSWhitelist 跨端同步脚本

> **分支**：`feature/c-whitelist`
> **路径**：`../orule-feature-c-whitelist`
> **基于**：`feature/a-prime`
> **目标**：在 orule-common 维护 `simplets-whitelist.ts → simplets-whitelist.json` 单向同步；JSON 给 Groovy 沙箱启动期加载
> **范围**：本仓内仅做"消费侧 + 验证"，"源端 TS" 在 orule-llm-studio Skill 仓（路径 a）

---

## 任务清单

```
[ ] 1. 新增或ule-common/src/main/resources/simplets-whitelist.json（占位 / 当前快照）
       - 字段参考 RFC-0018 §3.10 与 ADR-012-Aprime §4.3
       - 结构示意：
         {
           "methodSignatures": [{ "name": "...", "params": [...], "return": "..." }],
           "methodNames": ["contains", "startsWith", "endsWith", "size", ...],
           "objectConstructors": [...],
           "version": "1.0.0",
           "updatedAt": "2026-09-12T..."
         }
[ ] 2. 在 docs/03-deployment/ 或 docs/build/ 下新增说明文档（cross-build/simplets-whitelist-sync.md）：
       - 源端：orule-llm-studio 仓 Skill #2 simplets-to-groovy/skill/whitelist.ts
       - 目标端：本仓 orule-common/src/main/resources/simplets-whitelist.json
       - 触发：CI（npm script 或 Gradle/Maven plugin）单向同步
       - 校验：两端 methodNames set + methodSignatures schema 一致
[ ] 3. 新增或ule-common/src/main/java/com/orule/dsl/whitelist/SimpletsWhitelistLoader.java：
       - 从 classpath:simplets-whitelist.json 加载
       - 提供 List<String> methodNames() + List<MethodSignature> methodSignatures()
[ ] 4. 单测：SimpletsWhitelistLoaderTest（资源文件可读 + 字段完整性）
[ ] 5. 自检：当前 orule-common 包不持有 SimpleTSParser，所以 white list 仅作 orule-runtime 沙箱配置来源，
       在路径 c 中确认谁使用该 JSON（RFC-0020 §3.3 SecureASTCustomizer.allowMethods 是消费者）
[ ] 6. 通知路径 a：告诉他们目标 JSON schema 与加载路径
```

---

## 设计要点

### 单一来源原则

- **源端权威**：orule-llm-studio 仓 Skill #2 simplets-to-groovy/src/simplets-whitelist.ts（Node.js）
- **目标端镜像**：orule-common/src/main/resources/simplets-whitelist.json（资源文件）
- **同步方向**：源 → 目标（单向）
- **触发点**：CI（PR / nightly job）
- **失败处理**：两端 JSON 不一致 → CI 失败并报错"whitelist drift"，人工裁决（不自动覆盖）

### 谁消费

- **orule-runtime**：Groovy 沙箱 `SandboxConfig.forbiddenMethods()` 启动期读 simplets-whitelist.json 的 `methodNames`
- **Service**：未来 `Service` 用 `simplets-whitelist.version` 判断缓存兼容性

### JSON Schema（v1）

```json
{
  "version": "1.0.0",
  "updatedAt": "2026-09-12T20:00:00Z",
  "methodNames": [
    "contains", "startsWith", "endsWith", "size", "isEmpty",
    "toString", "equals", "hashCode",
    "...（来自 SimpleTS 白名单）"
  ],
  "methodSignatures": [
    {
      "methodName": "contains",
      "returnType": "boolean",
      "paramTypes": ["java.lang.CharSequence"]
    }
  ],
  "objectConstructors": [],
  "globalFunctions": ["now", "today", "len", "min", "max"]
}
```

---

## 与 a/b/d 的边界

- **a (Skill)**：源端 TS 在另一仓；本路径只定义 schema + 消费侧 + 同步契约文档
- **b (Controller)**：本仓 orule-server 接收 Skill 编译产物，**不消费** white list
- **d (Cleanup)**：老 SimpleTSParser 删除后，本路径是 white list 的**唯一存留**

---

## 落点文件清单

```
# 本仓
packages/orule-common/
├── src/main/resources/
│   └── simplets-whitelist.json           ★ 新增
├── src/main/java/com/orule/dsl/whitelist/
│   ├── SimpletsWhitelistLoader.java      ★ 新增
│   └── MethodSignature.java              ★ 新增（record）
└── src/test/java/com/orule/dsl/whitelist/
    └── SimpletsWhitelistLoaderTest.java  ★ 新增

docs/build/
└── simplets-whitelist-sync.md            ★ 新增

# 跨仓文档（提示路径 a 实现方）
docs/integration/
└── simplets-whitelist-cross-build.md     ★ 新增（作为路径 a 同步契约）
```

---

## 提交流程

```
1. 在 ../orule-feature-c-whitelist 工作
2. 完成后 git add + commit 到 feature/c-whitelist
3. 不推远端（WT3 = WT3A）
4. 完成后通知主仓，与 b/d 合并；发 RFC-0018 §3.10 跨端同步版给路径 a
```

---

## 不在范围（推迟）

- 实际双向同步脚本（CI 跑 / 增量检测 / merge conflict auto-resolve）
- 版本兼容性 schema migration
- 白名单热更新（不走 HTTP 推送，启动期一次加载）
