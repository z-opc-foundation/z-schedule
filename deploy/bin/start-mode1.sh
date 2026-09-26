#!/usr/bin/env bash
# Mode 1 合体启动：单容器，后端 jar 内嵌前端
# 详见 deploy/docker-compose.yml

set -euo pipefail

cd "$(dirname "$0")/.."

# compose 只自动读 deploy/.env，而本仓的 env 文件在 deploy/env/.env ⇒ 显式指过去。
# 没有它就不用指：DB_* 缺失会被 compose 文件里的守卫当场拒（非 0 退出、带消息，这响是有意的，
# 比起出一个"端口不 bind、日志无限刷、又不退出"的容器好得多——机理见 p24.sh 的 B12）。
ENVFILE=""
[ -f env/.env ] && ENVFILE="--env-file env/.env"

# 老一点的管理机只有 docker-compose 二进制、没有 compose 插件（250 就是 Docker 20.10.21 + v5.0.2），
# 写死 `docker compose` 会让"一键"死在命令行本身，连报错都不是部署的错。
compose() {
    if docker compose version >/dev/null 2>&1; then docker compose "$@"
    else docker-compose "$@"; fi
}

echo "=== 启动 Mode 1（合体）==="
compose $ENVFILE up -d

echo ""
echo "✓ 启动完成。"
echo "  访问：http://localhost:18086/meta"
echo "  日志：make logs"
echo "  停止：make down"