# ADR-008：LLM 通过本地 Agent 环境接入 + LLM Studio 为 Electron 桌面应用

> 状态：已通过
> 日期：2026-09-11
> 决策者：架构组
> 相关：03-逻辑视图、05-技术模型

---

## 决策

**Phase 1 不存在服务端 LLM 调用**。LLM 能力通过**本地 Cursor/OpenCode Agent 环境**提供：

| 项 | Phase 1 值 |
|----|-----------|
| LLM 调用方 | **本地 Cursor/OpenCode Agent 环境**（不经过任何后端服务） |
| LLM API Key | 配置在 Cursor/OpenCode 中（不在任何服务端） |
| LLM Studio | **Electron 桌面应用** + 本地 Local Server（端口 5174） |
| Phase 2 | 可扩展为独立的 `orule-llm` 服务（远程 LLM Server） |

---

## 原因

1. **Phase 1 策略**：快速验证 MVP，不自建 LLM 服务，复用开发者已有的 Cursor/OpenCode 环境
2. **Electron 优势**：能读写本地文件系统、调用本地子进程（Cursor/OpenCode MCP Server）
3. **解耦**：LLM Studio 是独立桌面应用，可单独部署，不依赖 orule-web
4. **Phase 2 扩展**：未来可增加独立 LLM 服务，供无 Cursor/OpenCode 环境的机器使用

---

## Phase 1 架构

```
同一台开发机器：

  ┌─────────────────────────────────────────────────────┐
  │  浏览器（orule-web，5173）                           │
  │  ├── 规则编辑器                                      │
  │  └── <iframe src="http://localhost:5174">          │
  │        LLM Studio 卡片                              │
  └─────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────┐
  │  Electron（orule-llm-studio，5174）                │
  │  ├── Local Server（Express REST API）              │
  │  └── Main Process（MCP IPC → Cursor/OpenCode）     │
  └─────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────┐
  │  Cursor / OpenCode（本地 Agent，MCP Server）        │
  │  └── 已配置 LLM API Key（OpenAI/Claude）            │
  └─────────────────────────────────────────────────────┘
```

---

## Phase 2 扩展架构（可选）

```
无 Cursor/OpenCode 的机器：

  orule-llm-studio（Electron）
    │
    │ HTTP REST
    ▼
  orule-llm（独立 Java 服务，Phase 2 新增）
    │
    │ OpenAI / Anthropic SDK
    ▼
  OpenAI / Anthropic API
```

---

## 关键设计点

### 1. Electron Main Process 职责

```typescript
// electron/main.ts 核心职责
- 启动本地 Local Server（Express，端口 5174）
- 管理 Cursor/OpenCode 子进程（MCP Server）
- IPC 通道（主进程 ↔ 渲染进程）：
  - llm:chat（MCP 调用）
  - file:read（读取工作空间）
  - mcp:call（通用 MCP 工具调用）
```

### 2. Local Server REST API

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/chat` | 通用 chat（MCP 调用 Cursor/OpenCode） |
| POST | `/api/v1/convert/nl-to-simplets` | NL → SimpleTS |
| POST | `/api/v1/convert/simplets-to-nl` | SimpleTS → NL |
| GET  | `/api/v1/health` | 健康检查 |

### 3. orule-web 嵌入方式

```tsx
// orule-web/src/components/LlmStudioIframe.tsx
export function LlmStudioIframe() {
  return (
    <iframe
      src={import.meta.env.VITE_LLM_STUDIO_URL || 'http://localhost:5174'}
      width="100%"
      height="600"
      style={{ border: 'none' }}
      title="LLM Studio"
    />
  );
}
```

> **前提**：LLM Studio 和 orule-web 必须在同一台机器上运行（Electron 需要访问本地 Cursor/OpenCode）。

### 4. 部署说明

| 组件 | 部署方式 |
|------|----------|
| `orule-server` | Docker 容器 |
| `orule-runtime` | Docker 容器 |
| `orule-web` | Docker 容器 |
| `orule-llm-studio` | **本地安装**（Electron 桌面应用） |
| `orule-mcp-server` | 本地安装或 Docker |

---

## 决策日志

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-11 | Phase 1 不设 LLM 服务，直接调本地 Cursor/OpenCode | 快速 MVP；复用开发者环境 |
| 2026-09-11 | LLM Studio = Electron 桌面应用 | 可调用本地进程；独立部署 |
| 2026-09-11 | Phase 2 可扩展独立 LLM 服务 | 供无 Cursor/OpenCode 的机器使用 |
