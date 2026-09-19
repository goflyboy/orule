# RFC 标准模板

> 本模板以 [Rust RFC Format](https://github.com/rust-lang/rfcs) 为参考，针对本仓库（orule Maven monorepo）做了适配。
> 使用流程见 `.agents/skills/interactive-rfc-generation/SKILL.md`。

---

## 0. 起草前自检

- [ ] 已在 `.agents/skills/interactive-rfc-generation/SKILL.md` §0 判断"使用 / 不使用"。
- [ ] 已读 [AGENTS.md §0.1 模块归属决策](../../../AGENTS.md) 并确定归属。
- [ ] 已通读模板与"必需章节检查清单"。

---

## 1. 完整模板

```markdown
# RFC-XXXX: <标题>

> 状态：草案（Draft）
> 日期：YYYY-MM-DD
> 模块归属：orule-common | orule-rule-execution-service | orule-server | 跨模块（详 §2）

---

## 1. 摘要

[简要描述：问题和解决方案，用 1-3 句话说明]

---

## 2. 模块归属

- 范围：orule-common | orule-rule-execution-service | orule-server | 跨模块
- 模块责任：[1 句话说清本次改动在该模块的哪一块]
- 跨模块依赖：[列出新增 / 修改的对其他模块的依赖方向；如不涉及则填"无"]
- 依赖方向合规：[对照 ARCHITECTURE.md §0 依赖方向确认无反向依赖]

---

## 3. 动机

### 3.1 问题背景

[描述当前系统的行为和问题所在]

### 3.2 具体场景

[用具体的例子说明问题，包含输入输出]

```
示例输入:
  condition: value

当前行为:
  [描述]

期望行为:
  [描述]
```

### 3.3 为什么需要改变

[解释为什么现有方案不满足需求]

---

## 4. 设计方案

### 4.1 核心思路

[高层次的解决方案概述]

### 4.2 详细设计

[技术细节]

#### 4.2.1 数据模型扩展

```java
// 新增或修改的类/字段
public class Example {
    private int newField;
}
```

#### 4.2.2 接口变更

```java
// 接口方法签名变更
public interface IExample {
    void newMethod();
}
```

#### 4.2.3 流程图

```
[使用文字描述流程，或用 mermaid 图]

A --> B --> C
```

### 4.3 关键代码改造

#### 4.3.1 [改造点 1]

```java
// 改造前
[旧代码]

// 改造后
[新代码]
```

> 提醒：所有 Java 写法必须符合 [AGENTS.md §STY-J001](../../../AGENTS.md)（import 风格）。

---

## 5. 复用优先

- 现有入口点：
- 现有 helper：
- 现有测试基类：
- 现有 DSL 或注解：
- 不新增：
- 可由上下文推导的字段：
- 相似测试：

---

## 6. 验收准则

### 6.1 功能验收用例

#### AC-001: [用例名称]

**目的**：[验证什么功能]

**测试数据**：

```java
[使用注解驱动方式定义测试模块；引用既有测试基类]
```

**测试用例**：

| ID | 输入 | 预期行为 |
|----|------|----------|
| AC-001-1 | X | Y |

**验证逻辑**：

```java
@Test
public void testAC001() {
    // 测试代码
}
```

> 提醒：测试类必须 `public class`，见 [AGENTS.md §STY-J002](../../../AGENTS.md)。

### 6.2 边界条件

| 条件 | 输入 | 预期行为 |
|------|------|----------|
| [边界 1] | [输入] | [行为] |

### 6.3 回归测试

- [ ] 确保现有功能不受影响
- [ ] 列出需要回归的测试用例

### 6.4 验收命令

```bash
mvn -pl <module> -am test -Dtest=<TestClass>#<testMethod>
```

---

## 7. 实现计划

| 阶段 | 任务 | 优先级 | 状态 |
|------|------|--------|------|
| 1 | [任务描述] | P0 | [待开始/进行中/已完成] |
| 2 | [任务描述] | P1 | [待开始] |

---

## 8. 参考资料

- [AGENTS.md](../../../AGENTS.md)
- [ARCHITECTURE.md](../../../ARCHITECTURE.md)
- [RFC-0000 MVP 阶段总览](../RFC-0000-MVP-RFC总览.md)
- [Rust RFC Format](https://github.com/rust-lang/rfcs)
- [相关 ADR（如有）](../../adr/)
```

---

## 9. 必需章节检查清单

- [ ] 摘要：1-3 句话描述问题和解决方案
- [ ] 模块归属：明确指出属于哪个模块 + 跨模块依赖方向
- [ ] 动机：问题背景、具体场景、为什么需要改变
- [ ] 设计方案：核心思路、详细设计、关键代码改造
- [ ] 复用优先：现有入口 / helper / 测试基类 / DSL
- [ ] 验收准则：可执行的测试用例 + 具体验收命令
- [ ] 实现计划：分阶段任务和优先级
- [ ] 参考资料：链接必须真实可达（起草后跑一次 `audit-docs` 自检）

---

## 10. 模块测试路径速查

| 改动落在 | 测试落点 |
|----------|----------|
| `orule-common` | `packages/orule-common/src/test/java/...` |
| `orule-rule-execution-service` | `packages/orule-rule-execution-service/src/test/java/...` |
| `orule-server` | `packages/orule-server/src/test/java/...` |
| 跨模块 | 各自模块分别加测试；禁止跨包共享 test fixture |
