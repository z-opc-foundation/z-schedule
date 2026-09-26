#!/usr/bin/env bash
# Mode 3 集群启动：1 前端 nginx + N 后端 jar（默认 N=2，可用位置参数调整）
# 详见 deploy/docker-compose.cluster.yml

set -euo pipefail

cd "$(dirname "$0")/.."

# compose 只自动读 deploy/.env，而本仓的 env 文件在 deploy/env/.env ⇒ 显式指过去。
# 没有它就不用指：DB_* 缺失会被 compose 文件里的守卫当场拒（非 0 退出、带消息，这响是有意的，
# 比起出一个"端口不 bind、日志无限刷、又不退出"的容器好得多——机理见 p24.sh 的 B12）。
ENVFILE=""
[ -f env/.env ] && ENVFILE="--env-file env/.env"

REPLICAS="${1:-2}"

# 两支 compose CLI 都能解析（机理见 start-mode1.sh 的注释）。
compose() {
    if docker compose version >/dev/null 2>&1; then docker compose "$@"
    else docker-compose "$@"; fi
}

echo "=== 启动 Mode 3（集群，backend replicas=$REPLICAS）==="
compose $ENVFILE -f docker-compose.cluster.yml up -d --scale z-schedule-backend="$REPLICAS"

echo ""
echo "✓ 启动完成。"
compose $ENVFILE -f docker-compose.cluster.yml ps
echo ""
echo "  访问前端：http://localhost"
echo "  验证反代：curl http://localhost/api/actuator/health（nginx 把 /api/ 剥掉转到后端 /meta/）"
echo "  调整副本：bash bin/start-mode3.sh 5（启动 5 个后端）"
echo "  停止：make down"
