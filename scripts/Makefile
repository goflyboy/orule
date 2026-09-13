# Makefile — orule 顶层便捷命令
# 用法：make help

.PHONY: help build server rule-execution-service runtime start stop clean test

help:
	@echo "orule 开发命令："
	@echo "  make build                   - 编译全部模块"
	@echo "  make test                    - 运行全部测试"
	@echo "  make server                  - 启动 orule-server"
	@echo "  make rule-execution-service  - 启动 orule-rule-execution-service (was: runtime)"
	@echo "  make runtime                 - 别名，等同于 make rule-execution-service"
	@echo "  make start                   - 一键启动全部"
	@echo "  make stop                    - 停止全部"
	@echo "  make clean                   - 清理构建产物"

build:
	mvn -B -DskipTests clean install

test:
	mvn -B test

server:
	./scripts/dev-start.sh server

rule-execution-service:
	./scripts/dev-start.sh rule-execution-service

# 兼容旧名（RFC-0040 v0.6 之前）
runtime:
	./scripts/dev-start.sh rule-execution-service

start:
	./scripts/dev-start.sh

stop:
	./scripts/dev-start.sh stop

clean:
	mvn -B clean
	rm -rf packages/*/target
