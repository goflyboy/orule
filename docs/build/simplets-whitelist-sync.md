# SimpleTS 白名单：跨仓构建期单向同步契约（RFC-0018 §3.10 + ADR-012-Aprime §4.3）

> **状态**：草稿，待 C 路径 PR 合并后冻结
> **作者**：路径 C（`feature/c-whitelist`）
> **目标读者**：路径 A 实现方（`orule-llm-studio` 仓 owner）

---

## 1. 概述

SimpleTS 白名单是 A' 方案的**配置数据单一来源**，被两端消费：

- **源端**：`orule-llm-studio` 仓 Skill #2 `simplets-to-groovy/simplets-whitelist.ts`
  - 用作 TypeScript AST 剪枝与白名单校验
  - 通过 `exportToJSON()` 输出 JSON 字面量
- **目标端**：本仓 `orule-common/src/main/resources/simplets-whitelist.json`
  - 由 orule-runtime Groovy 沙箱启动期加载
  - 由 orule-server 业务校验逻辑消费

**同步方向**：源 → 目标，**单向**。目标端**永远不能覆盖**源端。

---

## 2. JSON Schema（v1）

> ⚠️ **字段稳定原则**：本 Schema 是 A' 方案的关键契约。任何字段新增 / 删除 / 重命名
> 必须在 PR 中同步两侧 + bump `version` 字段（semver）。

```typescript
interface SimpletsWhitelist {
  /** semver 字符串（源端 WHITELIST_VERSION） */
  version: string;

  /** ISO-8601 UTC，源端 exportToJSON() 时取 now() */
  updatedAt: string;

  /** 允许的 SimpleTS 语句类型（IfStmt / ForStmt / DeclareStmt / AssignStmt / ExprStmt / Block） */
  statementKinds: string[];

  /** 允许的 SimpleTS 表达式类型（BinaryExpr / UnaryExpr / Literal / MemberAccess / CallExpr / EnumRef / Identifier） */
  expressionKinds: string[];

  /** 允许的二元运算符（+ - * / % == != < <= > >= && ||） */
  binaryOps: string[];

  /** 允许的一元运算符（- !） */
  unaryOps: string[];

  /** SimpleTS 全局函数（无类前缀，沙箱侧允许的方法名集合） */
  globalFunctions: string[];

  /** 全局函数的方法签名（沙箱 deny-unless-allow 依据） */
  methodSignatures: MethodSignature[];
}

interface MethodSignature {
  methodName: string;        // 必须 ∈ globalFunctions
  returnType: string;        // 完全限定 Java 类型
  paramTypes: string[];      // 完全限定 Java 类型列表
}
```

**约束**（目标端 SimpletsWhitelistLoader 强制校验）：
- `methodSignatures[].methodName` ⊆ `globalFunctions`
- 所有 list 字段必须非空
- `version` 必须非空
- `updatedAt` 必须是合法 ISO-8601

---

## 3. 同步流程

```
┌─────────────────────────┐                ┌─────────────────────────┐
│ orule-llm-studio 仓      │                │ orule 仓                │
│ (源端)                   │   单向         │ (目标端)                │
│                         │   ─────────►   │                         │
│ simplets-whitelist.ts   │   CI / PR       │ simplets-whitelist.json │
│ (TypeScript)            │   手动         │ (JSON 资源)             │
│                         │                │                         │
│ exportToJSON()          │                │ SimpletsWhitelistLoader │
│                         │                │ .fromClasspath()        │
└─────────────────────────┘                └─────────────────────────┘
```

### 3.1 同步命令

**源端**（手动 / CI）：
```bash
# 在 orule-llm-studio 仓根目录
npm install
Orule_COMMON_DIR=../orule-feature-c-whitelist/packages/orule-common \
  npm run whitelist:sync
# 等价于：node scripts/sync-whitelist.mjs
```

### 3.2 失败处理

- **两端字段不一致**：CI 失败并报错 `whitelist drift`，**不自动覆盖**
- **目标端 SimpletsWhitelistLoader 加载失败**：`IllegalStateException`（fail-fast）
  - 资源缺失（同步未跑）
  - JSON 格式错误（源端 exportToJSON 改了 schema 但目标端没跟进）
  - 必需字段缺失
  - version 为空

---

## 4. 版本管理

| version | 含义 | 兼容策略 |
|---------|------|----------|
| MAJOR | Schema 不兼容变更（字段重命名 / 删除） | 目标端 Loader 必须强制 bump；orule-runtime 必须重新部署 |
| MINOR | 新增字段（向后兼容） | 目标端 record 必须允许 null |
| PATCH | 字段值调整（方法签名补充等） | 无需任何动作 |

> **约束**：bump version 必须在 PR description 里写明"影响哪些消费方"。

---

## 5. 责任分工

| 方 | 责任 |
|---|------|
| **路径 A（orule-llm-studio）** | 维护 `simplets-whitelist.ts`；CI 跑 `sync-whitelist.mjs`；在 PR 中 review JSON diff |
| **路径 C（本仓）** | 维护 `SimpletsWhitelistLoader`；维护本契约文档；写 JSON schema 兼容测试 |
| **路径 D（cleanup）** | 不涉及白名单（A' 方案后 SimpleTSParser 删除） |
| **orule-runtime 团队** | 启动期 `SimpletsWhitelistLoader.fromClasspath()` 加载；传给 `SandboxConfig` |

---

## 6. 关联文档

- RFC-0018 §3.10：https://github.com/orule/orule/blob/main/docs/rfcs/RFC-0018-SimpleTS解析器.md#310-simplets-白名单单一来源
- ADR-012-Aprime §4.3：https://github.com/orule/orule/blob/main/docs/adr/ADR-012-Aprime-本地Skill编译与MCPLangLib库.md
- A 仓 TS 端：`../orule-orule-llm-studio-temp/src/skills/simplets-to-groovy/simplets-whitelist.ts`
- A 仓同步脚本：`../orule-orule-llm-studio-temp/scripts/sync-whitelist.mjs`
