# RFC-0012: Maven 多模块骨架

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：1d · **阶段**：S0

---

## 1. 摘要

建立 Maven 多模块项目结构，包括顶层 `pom.xml` 和三个子模块：`orule-common`、`orule-server`、`orule-runtime`。

---

## 2. 动机

- 依据 `08-开发视图 §8.1.2` 的顶层 Maven POM 定义
- 三个 Java 模块是 MVP 的核心：common（DTO/异常/接口）、server（管控）、runtime（执行）
- 为后续所有 RFC 提供 Java 编译 / 测试 / 打包基础

---

## 3. 详细设计

### 3.1 模块结构

```
packages/
├── orule-common/         # 公共模块（无 Spring 依赖）
│   ├── pom.xml
│   └── src/main/java/com/orule/common/
│       ├── dto/          # DTO 类（RuleDto 等）
│       ├── exception/    # 业务异常
│       └── util/         # 工具类
│
├── orule-server/         # 管控服务（Spring Boot）
│   ├── pom.xml
│   └── src/main/java/com/orule/server/
│       └── OruleServerApplication.java
│
└── orule-runtime/        # 执行服务（Spring Boot）
    ├── pom.xml
    └── src/main/java/com/orule/runtime/
        └── OruleRuntimeApplication.java
```

### 3.2 顶层 `pom.xml`

依据 `08-开发视图 §8.1.3`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.orule</groupId>
  <artifactId>orule-parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <name>orule-parent</name>
  <description>orule 顶层父 POM</description>

  <modules>
    <module>packages/orule-common</module>
    <module>packages/orule-server</module>
    <module>packages/orule-runtime</module>
  </modules>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <spring-boot.version>3.3.4</spring-boot.version>
    <typescript.version>5.6.0</typescript.version>
    <graaljs.version>24.2.0</graaljs.version>
    <groovy.version>4.0.24</groovy.version>
    <h2.version>2.3.232</h2.version>
    <mysql.version>8.0.33</mysql.version>
    <flyway.version>10.20.0</flyway.version>
    <lombok.version>1.18.34</lombok.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- Spring Boot BOM -->
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>

      <!-- 内部模块 -->
      <dependency>
        <groupId>com.orule</groupId>
        <artifactId>orule-common</artifactId>
        <version>${project.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <version>${spring-boot.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.13.0</version>
          <configuration>
            <source>21</source>
            <target>21</target>
            <annotationProcessorPaths>
              <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
              </path>
            </annotationProcessorPaths>
          </configuration>
        </plugin>
        <plugin>
          <groupId>com.diffplug.spotless</groupId>
          <artifactId>spotless-maven-plugin</artifactId>
          <version>2.43.0</version>
          <configuration>
            <java>
              <palantirJavaFormat/>
              <importOrder>
                <order>com.orule,org.springframework,com.google,java,javax</order>
              </importOrder>
              <removeUnusedImports/>
            </java>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

### 3.3 子模块 `pom.xml`

#### `packages/orule-common/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.orule</groupId>
    <artifactId>orule-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>orule-common</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
  </dependencies>
</project>
```

#### `packages/orule-server/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.orule</groupId>
    <artifactId>orule-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>orule-server</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <dependency>
      <groupId>com.orule</groupId>
      <artifactId>orule-common</artifactId>
    </dependency>

    <!-- Spring Boot Starters -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- 数据库 -->
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>

    <!-- Flyway -->
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>

    <!-- Test -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>mysql</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <mainClass>com.orule.server.OruleServerApplication</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

#### `packages/orule-runtime/pom.xml`

类似 `orule-server`，但：
- 依赖 `orule-server` 提供的 API（HTTP 调用）
- 不需要 Flyway / 数据库
- 不需要 spring-boot-starter-data-jpa

### 3.4 启动类

#### `OruleServerApplication.java`

```java
package com.orule.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication(scanBasePackages = {"com.orule.server", "com.orule.common"})
@EntityScan(basePackages = "com.orule.common.entity")
public class OruleServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OruleServerApplication.class, args);
    }
}
```

#### `OruleRuntimeApplication.java`

```java
package com.orule.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.orule.runtime")
public class OruleRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OruleRuntimeApplication.class, args);
    }
}
```

---

## 4. 影响面

- 新增 3 个 Java 模块 + 顶层 `pom.xml`
- 不涉及数据库表 / API

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| Maven 编译 | `mvn clean compile` 无错误 |
| Maven 单元测试 | `mvn test` 通过 |
| Spring Boot 启动 | `mvn -pl packages/orule-server spring-boot:run` 成功 |
| H2 数据库连接 | 启动日志显示 Flyway 初始化 |
| Actuator 健康检查 | `curl http://localhost:8080/actuator/health` 返回 UP |

---

## 6. 风险

- **R1**：Spring Boot 3 + Java 21 兼容性 → 缓解：使用官方支持的 Spring Boot 3.3.x
- **R2**：Lombok + Java 21 兼容性 → 缓解：使用 Lombok 1.18.34+
- **R3**：Hibernate 6 + H2/MySQL 方言差异 → 缓解：用 `ddl-auto: validate` 严格校验，由 Flyway 控制 schema

---

## 7. 实施步骤

```
1. 创建顶层 pom.xml
2. 创建 packages/orule-common/pom.xml + 基础类
3. 创建 packages/orule-server/pom.xml + OruleServerApplication
4. 创建 packages/orule-runtime/pom.xml + OruleRuntimeApplication
5. 创建 application.yml（H2 + JPA + Flyway 占位）
6. mvn clean install 验证
7. mvn spring-boot:run 验证启动
```

---

## 8. 关联

- 上游：RFC-0011
- 下游：RFC-0013（一键启动）、RFC-0014（Flyway）、RFC-0015（元数据 API）
- ADR：—
