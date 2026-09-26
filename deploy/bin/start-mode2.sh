#!/usr/bin/env bash
# Mode 2 分体启动：前端 nginx + 后端 jar 两个容器
# 详见 deploy/docker-compose.split.yml

set -euo pipefail

cd "$(dirname "$0")/.."

# compose 只自动读 deploy/.env，而本仓的 env 文件在 deploy/env/.env ⇒ 显式指过去。
# 没有它就不用指：DB_* 缺失会被 compose 文件里的守卫当场拒（非 0 退出、带消息，这响是有意的，
# 比起出一个"端口不 bind、日志无限刷、又不退出"的容器好得多——机理见 p24.sh 的 B12）。
ENVFILE=""
[ -f env/.env ] && ENVFILE="--env-file env/.env"

# 两支 compose CLI 都能解析（机理见 start-mode1.sh 的注释）。
compose() {
    if docker compose version >/dev/null 2>&1; then docker compose "$@"
    else docker-compose "$@"; fi
}

echo "=== 启动 Mode 2（分体）==="
compose $ENVFILE -f docker-compose.split.yml up -d

echo ""
echo "✓ 启动完成。"
echo "  访问前端：http://localhost"
echo "  访问后端（需 exec 进容器）：docker exec -it z-schedule-backend curl http://127.0.0.1:18086/meta/actuator/health"
echo "  验证反代：curl http://localhost/api/actuator/health"
echo "  停止：make down"