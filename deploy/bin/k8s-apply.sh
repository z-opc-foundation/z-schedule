#!/usr/bin/env bash
# k8s 一键部署（占位符渲染 + kubectl apply）
#
# 占位符（envsubst 风格，全部从环境读；本脚本只渲染列在下面这些，多余的 ${X} 会原样留着报错）：
#   NAMESPACE            默认 z-schedule
#   INGRESS_DOMAIN       必填，如 schedule.example.com
#   TLS_SECRET_NAME      默认 z-schedule-tls
#   OCI_REGISTRY         默认 ghcr.io/yuku123
#   IMAGE_VERSION        默认 1.0.4
#   DB_HOST DB_PORT DB_NAME DB_USER       两个池共用的库坐标
#   DB_POOL_MAX_ACTIVE   引擎池上限，默认 40
#
# 凭证不进 Git：密码要事先建好 Secret
#   kubectl -n "$NAMESPACE" create secret generic z-schedule-db-credentials \
#       --from-literal=password='***'
# （两个池用同一份账号，所以只有一个 key。）

set -euo pipefail

cd "$(dirname "$0")/.."

export NAMESPACE="${NAMESPACE:-z-schedule}"
export INGRESS_DOMAIN="${INGRESS_DOMAIN:?必须设置 INGRESS_DOMAIN（如 schedule.example.com）}"
export TLS_SECRET_NAME="${TLS_SECRET_NAME:-z-schedule-tls}"
export OCI_REGISTRY="${OCI_REGISTRY:-ghcr.io/yuku123}"
export IMAGE_VERSION="${IMAGE_VERSION:-1.0.4}"
export DB_HOST="${DB_HOST:?必须设置 DB_HOST（引擎池与 Spring 池都要）}"
export DB_PORT="${DB_PORT:-3306}"
export DB_NAME="${DB_NAME:?必须设置 DB_NAME}"
export DB_USER="${DB_USER:-root}"
export DB_POOL_MAX_ACTIVE="${DB_POOL_MAX_ACTIVE:-40}"

# 只认这几个：写死名单，防止 ${PATH} 这类无关变量被 envsubst 吃掉
VARS='$NAMESPACE $INGRESS_DOMAIN $TLS_SECRET_NAME $OCI_REGISTRY $IMAGE_VERSION $DB_HOST $DB_PORT $DB_NAME $DB_USER $DB_POOL_MAX_ACTIVE'

echo "=== 渲染占位符 ==="
RENDERED_DIR="$(mktemp -d)"
trap 'rm -rf "$RENDERED_DIR"' EXIT
for f in k8s/*.yaml; do
    out="$RENDERED_DIR/$(basename "$f")"
    envsubst "$VARS" < "$f" > "$out"
    # 渲染不完整过去会静默把 ${X} 送进集群，现在当场拦
    if grep -q '\${' "$out"; then
        echo "FATAL: $(basename "$f") 渲染后仍有未替换占位符：" >&2
        grep -o '\${[A-Za-z_][A-Za-z_]*}' "$out" | sort -u >&2
        exit 1
    fi
    echo "  rendered: $(basename "$f")"
done

echo ""
echo "=== kubectl apply ==="
for f in 00-namespace 01-deployment-backend 02-deployment-frontend 03-service-backend 04-service-frontend 05-ingress; do
    kubectl apply -f "$RENDERED_DIR/$f.yaml"
done

if ! kubectl -n "$NAMESPACE" get secret z-schedule-db-credentials >/dev/null 2>&1; then
    echo ""
    echo "!! 命名空间 $NAMESPACE 里没有 z-schedule-db-credentials Secret。"
    echo "   pod 会卡在 CreateContainerConfigError，先建再等："
    echo "   kubectl -n $NAMESPACE create secret generic z-schedule-db-credentials --from-literal=password='***'"
fi

echo ""
echo "=== 等 pod Ready ==="
kubectl -n "$NAMESPACE" get pods -w

echo ""
echo "✓ 部署完成。访问：https://schedule.$INGRESS_DOMAIN"
