# Makefile — orule 顶层便捷命令
# 用法：make help

.PHONY: help build server runtime web start stop clean test

help:
	@echo "orule 开发命令："
	@echo "  make build    - 编译全部模块"
	@echo "  make test     - 运行全部测试"
	@echo "  make server   - 启动 orule-server"
	@echo "  make runtime  - 启动 orule-runtime"
	@echo "  make start    - 一键启动全部"
	@echo "  make stop     - 停止全部"
	@echo "  make clean    - 清理构建产物"

build:
	mvn -B -DskipTests clean install

test:
	mvn -B test

server:
	./scripts/dev-start.sh server

runtime:
	./scripts/dev-start.sh runtime

start:
	./scripts/dev-start.sh

stop:
	./scripts/dev-start.sh stop

clean:
	mvn -B clean
	rm -rf packages/*/target
