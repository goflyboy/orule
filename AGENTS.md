# orule 仓库编码规约（AGENTS.md）

本文件记录仓库级别的编码约定，AI 助手和贡献者应共同遵守。
规则编号稳定，便于在 RFC / ADR / Review 中引用。

## Java

### STY-J001 — import vs inline 限定符

Java 代码中，类型引用一律走 `import`。**禁止在代码体内使用带包路径的 inline 限定符**
（如 `org.slf4j.Logger`、`com.foo.Bar`），即便只是为了少写一行 import。

- 反例（禁止）：
  ```java
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(X.class);
  org.springframework.util.StringUtils.hasText(...);
  ```
- 正例（推荐）：
  ```java
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.util.StringUtils;

  private static final Logger log = LoggerFactory.getLogger(X.class);
  StringUtils.hasText(...);
  ```

例外（明确允许使用 inline 限定符）：

- `package` 与 `import` 语句本身
- 注解处理器 / 注解类自身的反射常量等少量框架强制的场景（出现时需在 PR 描述里说明）

适用范围：整个仓库的 Java 源代码（包括 `src/main` 与 `src/test`）。

> 触发者：本条由 `LocalStorage.java:26-28` 的写法收敛而来。该处尚未整改，
> 后续由独立任务统一迁移到 import 风格。

### STY-J002 — JUnit 5 测试类可见性

测试类（任何带 `@Test` 方法的类）必须声明为 `public`，否则 JUnit 5 平台
以及主流 IDE（VS Code / Cursor 的 `vscjava.vscode-java-test`，IntelliJ）的
测试发现器会跳过该类，导致 `No tests found in the selected file or folder`。

- 反例：`class LocalStorageTest { ... }`
- 正例：`public class LocalStorageTest { ... }`

适用范围：所有 `**/src/test/java/**/*.java`。
