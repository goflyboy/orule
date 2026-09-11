---
name: rfc-push
description: 分两次提交 RFC 实现变更。第一次提交代码，第二次提交 `.specstory` 等大模型交互日志。日志提交信息格式为 `chore(rfc): logs-<代码提交描述>`，与代码提交语义对应。默认直接推送到远程。编码提示：Windows PowerShell 读取本仓库中文 Skill 时使用 `Get-Content -Raw -Encoding UTF8`。
---

# RFC 分步提交

在完成 RFC 实现后，使用此 Skill 将代码和日志分两次提交到目标分支，保证提交历史清晰可追溯。

## 流程

### 1. 确认提交范围

1. 确认当前分支为 `main` 或用户指定的目标分支。
2. 确认工作区仅包含本次 RFC 相关的代码变更和 `.specstory` 日志文件，无无关变更。
3. 确认 `.specstory` 目录下存在需要提交的日志文件。

### 2. 第一次提交：代码提交

1. 暂存所有变更后，排除 `.specstory` 目录：
   ```bash
   git add -A
   git reset .specstory
   ```
2. 生成代码提交信息，格式为：
   ```text
   <type>(<scope>): <description>
   ```
   常用类型：`feat`、`refactor`、`fix`、`chore` 等。
3. 执行提交：
   ```bash
   git commit -m "<type>(<scope>): <description>"
   ```

### 3. 第二次提交：日志提交

1. 暂存所有 `.specstory` 相关文件：
   ```bash
   git add .specstory
   ```
2. 生成日志提交信息，格式为：
   ```text
   chore(rfc): logs-<代码提交描述>
   ```
   其中 `<代码提交描述>` 为第一次代码提交信息的简短摘要（去掉类型前缀如 `feat(rfc):`），确保日志提交与代码提交一一对应且语义清晰。
3. 执行提交并推送：
   ```bash
   git commit -m "chore(rfc): logs-<代码提交描述>"
   ```

### 4. 推送到远程

默认推送到 `main` 分支：
```bash
git push origin main
```

## 约束

1. 两次提交必须严格分开，禁止将代码和日志合并到同一次提交。
2. 日志提交信息格式为 `chore(rfc): logs-<代码提交描述>`，确保与代码提交语义对应。
3. 默认推送到 `main` 分支，除非用户明确指定其他分支。
4. 无关的未跟踪文件不要放入提交，除非用户明确要求。
