# RFC 标准模板

参考 [Rust RFC Format](https://github.com/rust-lang/rfcs) 设计的标准模板。

---

## 完整模板

```markdown
# RFC-XXXX: <标题>

> 状态：草案（Draft）
> 日期：YYYY-MM-DD

---

## 1. 摘要

[简要描述：问题和解决方案，用1-3句话说明]

---

## 2. 动机

### 2.1 问题背景

[描述当前系统的行为和问题所在]

### 2.2 具体场景

[用具体的例子说明问题，包含输入输出]

```
示例输入:
  condition: value

当前行为:
  [描述]

期望行为:
  [描述]
```

### 2.3 为什么需要改变

[解释为什么现有方案不满足需求]

---

## 3. 设计方案

### 3.1 核心思路

[高层次的解决方案概述]

### 3.2 详细设计

[技术细节，包括：]

#### 3.2.1 数据模型扩展

```java
// 新增或修改的类/字段
public class Example {
    private int newField;
}
```

#### 3.2.2 接口变更

```java
// 接口方法签名变更
public interface IExample {
    void newMethod();
}
```

#### 3.2.3 流程图

```
[使用文字描述流程，或用 mermaid 图]

A --> B --> C
```

### 3.3 关键代码改造

#### 3.3.1 [改造点1]

```java
// 改造前
[旧代码]

// 改造后
[新代码]
```

---

## 4. 验收准则

### 4.1 功能验收用例

#### AC-001: [用例名称]

**目的**: [验证什么功能]

**测试数据**:
```java
[使用注解驱动方式定义测试模块]
```

**测试用例**:

| ID | 输入 | 预期行为 |
|----|------|----------|
| AC-001-1 | X | Y |

**验证逻辑**:
```java
@Test
public void testAC001() {
    // 测试代码
}
```

### 4.2 边界条件

| 条件 | 输入 | 预期行为 |
|------|------|----------|
| [边界1] | [输入] | [行为] |

### 4.3 回归测试

- [ ] 确保现有功能不受影响
- [ ] 列出需要回归的测试用例

---

## 5. 实现计划

| 阶段 | 任务 | 优先级 | 状态 |
|------|------|--------|------|
| 1 | [任务描述] | P0 | [待开始/进行中/已完成] |
| 2 | [任务描述] | P1 | [待开始] |

---

## 6. 参考资料

- [项目设计文档](doc/CORE-DESIGN.md)
- [项目级验收原则](doc/ACCEPTANCE.md)
- [cruleengine 验收入口](cruleengine/acceptance_core.md)（引擎侧或跨模块 RFC 必选）
- [crulemgr 验收入口](crulemgr/acceptance.md)（管理侧或跨模块 RFC 必选）
- [Rust RFC Format](https://github.com/rust-lang/rfcs)
- [其他相关文档]
```

---

## 必需章节检查清单

- [ ] 摘要：1-3句话描述问题和解决方案
- [ ] 动机：包含问题背景、具体场景、为什么需要改变
- [ ] 设计方案：核心思路、详细设计、关键代码改造
- [ ] 验收准则：包含可执行的测试用例（必须）
- [ ] 实现计划：分阶段任务和优先级
- [ ] 参考资料：引用的文档

---

## 测试用例格式规范

使用注解驱动方式定义测试模块：

```java
@ModuleAnno(code = "test")
@ParaAnno(code = "p1", value = "1")
@PartAnno(code = "part1")
public class MyTest extends ConstraintAlgImplTestBase {
    
    @Test
    public void testScenario() {
        inferRecommendModule("...");
        resultAssert().assertCode(Result.SUCCESS);
    }
}
```

模块路径提示：

- engine 语义、场景、优化、冲突诊断和 runtime jar 加载测试放在 `cruleengine/src/test/java`；jar 加载测试可使用 engine test scope 自持的 `ModulePacker` 夹具。
- RuleTrans、RuleUnit、LLM、prompt、打包发布和管理侧集成测试放在 `crulemgr/src/test/java`；manager 保留生产 `ModulePacker` 与本地 packer 测试。
- manager 测试如需 engine 场景 fixture，不使用 `cruleengine` test-jar；在 `crulemgr/src/test` 自持最小 fixture 副本。
