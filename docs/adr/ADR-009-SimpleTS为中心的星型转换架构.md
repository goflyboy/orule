# ADR-009：SimpleTS 为中心的星型转换架构

> 状态：已通过
> 日期：2026-09-11
> 决策者：架构组
> 相关：03-逻辑视图、04-数据模型、05-技术模型、06-运行视图

---

## 决策

**SimpleTS 是唯一的语义中间态**。所有视图转换都以 SimpleTS 为中心，形成星型结构：

```
                      ┌─────────────────┐
                      │    SimpleTS     │ ← 唯一的语义中间态
                      │   （语义 DSL）    │
                      └────────┬────────┘
                               │
            ┌──────────────────┼──────────────────┐
            │                  │                  │
            ▼                  ▼                  ▼
      用户视图（3 种）       用户视图（1 种）       执行态（N 种）
       ┌─────────┐          ┌─────────┐       ┌─────────┐
       │   NL    │          │ Table   │       │ Groovy  │
       │自然语言  │          │ 表格    │       │  DRL    │
       └─────────┘          └─────────┘       │  Python │
                                              └─────────┘
```

---

## 转换关系（白名单）

### ✅ 允许的转换

| 转换 | 方向 | 执行方 | 说明 |
|------|------|--------|------|
| NL → SimpleTS | 单向 | orule-llm-studio（LLM） | LLM 通过 Cursor/OpenCode 转换 |
| SimpleTS → NL | 单向 | orule-llm-studio（LLM） | LLM 转换（可选功能） |
| Table → SimpleTS | 单向 | orule-server | 服务端结构化转换 |
| SimpleTS → Table | 单向 | orule-server | 服务端结构化转换 |
| SimpleTS → Groovy | 单向 | orule-server（编译） | 编译产物落 RuleArtifact |
| SimpleTS → DRL | 单向 | orule-server（编译） | 二期支持 |
| SimpleTS → Python | 单向 | orule-server（编译） | 未来支持 |

### ❌ 不允许的转换（被架构禁止）

| 不允许 | 原因 |
|--------|------|
| NL → Table | 绕过 SimpleTS 语义层，破坏语义一致性 |
| Table → NL | 同上 |
| NL → Groovy（直转） | 必须经 SimpleTS |
| Table → Groovy（直转） | 必须经 SimpleTS |
| TS-AST JSON 作为用户视图 | TS-AST 是 SimpleTS 的**内部编译产物**，不是用户可见视图 |

---

## 用户可见视图（4 个 Tab，3 个用户视图 + 2 个只读）

```
┌─────────────────────────────────────────────────────────────┐
│  RuleEditor（5 个 Tab）                                      │
│  ┌─────────┬─────────┬─────────┬─────────┬─────────┐        │
│  │ NL 编辑 │SimpleTS │ 表格    │ TS-AST  │ Groovy  │        │
│  │ (用户)  │ (用户)  │ (用户)  │ (只读)   │ (只读)  │        │
│  └────┬────┴────┬────┴────┬────┴────┬────┴────┬────┘        │
│       │         │         │         │         │              │
│       │ NL↔SimpleTS│         │Table↔  │ 只读    │ 只读        │
│       │   (LLM)  │  中心   │SimpleTS │ (TS-AST)│ (Groovy)    │
│       │         │         │(服务端) │         │             │
└─────────────────────────────────────────────────────────────┘
```

| Tab | 类型 | 用途 | 是否可编辑 |
|-----|------|------|-----------|
| NL 编辑 | 用户视图 | 自然语言描述规则 | ✅ |
| SimpleTS | 用户视图 | 语义层源码（真值） | ✅ |
| 表格 | 用户视图 | 表格形式展示规则 | ✅ |
| TS-AST | 只读视图 | SimpleTS 编译内部表示 | ❌（调试用） |
| Groovy | 只读视图 | SimpleTS 编译产物 | ❌（调试用） |

---

## 同步规则

### 中心法则

**SimpleTS = source of truth**，所有其他视图都从 SimpleTS 派生：

```
SimpleTS 改变
    │
    ├─→ NL 视图刷新（LLM 转换 SimpleTS → NL，可选）
    ├─→ Table 视图刷新（orule-server 转换）
    ├─→ TS-AST 视图刷新（重新编译，只读）
    └─→ Groovy 视图刷新（重新编译，只读）
```

### 反向编辑

- **NL 编辑 → SimpleTS**：触发 LLM 转换 → SimpleTS 改变 → 其他视图刷新
- **Table 编辑 → SimpleTS**：触发服务端转换 → SimpleTS 改变 → 其他视图刷新
- **SimpleTS 直接编辑**：作为权威修改，其他视图被动刷新

### 锁定与提示

- 用户在 NL 视图编辑但未触发 LLM 转换时，**不立即同步到 SimpleTS**
- 用户在 Table 视图编辑但未触发保存时，**不立即同步到 SimpleTS**
- 切换 Tab 时提示"当前视图有未保存修改"

---

## 转换实现的架构边界

| 转换 | 实施位置 | 调用方 |
|------|----------|--------|
| NL ↔ SimpleTS | **Electron Main Process → Cursor/OpenCode → LLM** | orule-llm-studio Local Server（端口 5174） |
| Table ↔ SimpleTS | **orule-server（Java）** | REST API `/api/v1/rules/convert/table-to-simplets` |
| SimpleTS → Groovy | **orule-server（GraalJS 编译）** | 编译流程内部 |
| SimpleTS → DRL | **orule-server（未来）** | 编译流程内部 |

> **关键**：Table ↔ SimpleTS **不需要 LLM**，由 orule-server 结构化转换完成（性能高、确定性强）。
> NL ↔ SimpleTS **必须经过 LLM**（LLM 是唯一能做自然语言语义理解的工具）。

---

## 架构意义

### 1. 单一中间态

- **优势**：所有视图一致；语义唯一；调试简单
- **对比"两两互转"**：N² 个转换关系（4 视图 = 12 对）→ N 个转换关系（1 中心 + 4 边 = 4 条）

### 2. Table 视图的特殊地位

Table **不经过 LLM**，是结构化视图：
- 性能：毫秒级响应
- 确定性：相同输入产生相同输出
- 不消耗 LLM Token
- 可离线工作（不需要 Cursor/OpenCode）

### 3. 未来扩展性

新增执行态（如 Python、WASM）只需：
1. SimpleTS → Python 编译器
2. RuleArtifact.engineType 增加枚举
3. orule-runtime 增加 Executor

不需要修改任何视图/转换关系。

---

## 备选方案

### 备选 1：两两互转（4 视图 N² 关系）
- 缺点：12 个转换器、维护成本高、易出现语义不一致

### 备选 2：以 TS-AST JSON 为中心
- 缺点：TS-AST 是实现细节，不是用户可见视图；用 TS-AST 作为中心不直观

### 备选 3：以 SimpleTS 为中心（本方案）
- **采纳**：单一中间态、用户可见、语义清晰、扩展性强

---

## 决策日志

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-11 | 以 SimpleTS 为中心的星型转换 | 用户明确约束：语义层是 SimpleTS，不是 TS-AST |
