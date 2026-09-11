# RFC-0029: Docker Compose MVP 部署

> **状态**：DRAFT · **优先级**：P1 · **预计工作量**：2d · **阶段**：S8

---

## 1. 摘要

提供 Docker Compose 配置，支持本地一体化部署（server + runtime + MySQL + 可选 MinIO）。

---

## 2. 动机

- 替代 RFC-0013 的 Maven 启动脚本，提供容器化部署选项
- 支撑 MVP 演示的快速环境搭建

---

## 3. 详细设计

### 3.1 Dockerfile

#### orule-server

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY packages/orule-server/target/orule-server-*.jar app.jar
COPY packages/orule-common/target/orule-common-*.jar libs/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

#### orule-runtime

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY packages/orule-runtime/target/orule-runtime-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 3.2 docker-compose.yml

```yaml
version: '3.9'

services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: orule
      MYSQL_USER: orule
      MYSQL_PASSWORD: orule123
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./config/db/init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  orule-server:
    build: ./packages/orule-server
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/orule
      SPRING_DATASOURCE_USERNAME: orule
      SPRING_DATASOURCE_PASSWORD: orule123
      ORULE_STORAGE_TYPE: local
      ORULE_STORAGE_LOCAL_BASEPATH: /data/artifacts
      ORULE_LLM_APIKEY: ${OPENAI_API_KEY:-demo}
    depends_on:
      mysql:
        condition: service_healthy
    volumes:
      - artifact_data:/data/artifacts
      - ./logs/server:/logs
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  orule-runtime:
    build: ./packages/orule-runtime
    ports:
      - "8081:8081"
    environment:
      ORULE_RUNTIME_SANDBOX_TIMEOUT_MS: 30000
      ORULE_RUNTIME_STORAGE_TYPE: local
    volumes:
      - artifact_data:/data/artifacts
    depends_on:
      orule-server:
        condition: service_healthy

  # orule-web（前端开发服务器）
  orule-web:
    image: node:20-alpine
    working_dir: /app
    command: sh -c "npm install && pnpm dev --host"
    ports:
      - "5173:5173"
    volumes:
      - ./orule-web:/app
    environment:
      VITE_API_BASE: http://localhost:8080

volumes:
  mysql_data:
  artifact_data:
```

### 3.3 环境变量文件

```bash
# .env
OPENAI_API_KEY=sk-xxxx
SPRING_PROFILES_ACTIVE=docker
```

---

## 4. 关联

- 上游：RFC-0013（启动脚本）
- 下游：—
- ADR：—
