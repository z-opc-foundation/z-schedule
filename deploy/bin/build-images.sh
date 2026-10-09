#!/usr/bin/env bash
# 一键构建 z-schedule 后端 + 前端镜像
#
# 前置：
#   - docker 可用；本机 mvn 能出 admin exec jar（本脚本会自己跑）
#   - push 时需要已登录 ghcr.io
#
# 用法：
#   bash bin/build-images.sh                 # 构建本地镜像（不 push）
#   bash bin/build-images.sh --push          # 构建并 push
#   NO_CACHE=1 bash bin/build-images.sh      # 强制不用层缓存
#
# 可配 env：IMAGE_VERSION / OCI_REGISTRY / SPRING_REVISION（jar 的版本号，默认从 pom 读）

set -euo pipefail

# bin/ 在 deploy/ 下，而 mvn 与 docker build  context 都要仓库根 —— 差两级
ROOT="$(cd "$(dirname "$0")/../../" && pwd)"
cd "$ROOT"

PUSH=false
NO_CACHE=""
[ "${NO_CACHE:-}" = "1" ] && NO_CACHE="--no-cache"
IMAGE_VERSION="${IMAGE_VERSION:-1.0.4}"
OCI_REGISTRY="${OCI_REGISTRY:-ghcr.io/yuku123}"

for arg in "$@"; do
    case "$arg" in
        --push) PUSH=true ;;
        --no-cache) NO_CACHE="--no-cache" ;;
        *) echo "未知参数：$arg" >&2; exit 2 ;;
    esac
done

echo "=== Step 1/3: 编 Java admin jar ==="
mvn -B -q -DskipTests -pl z-schedule-admin -am package

# jar 名跟着 admin 的 <version> 走，写死 1.0.0 会在抬版本后当场 COPY 失败
JAR="$(ls -1 z-schedule-admin/target/*-exec.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || { echo "FATAL: mvn 没产出 *-exec.jar" >&2; exit 1; }
echo "  jar: $JAR ($(stat -c%s "$JAR" 2>/dev/null || stat -f%z "$JAR") B)"

ADMIN_TAG="$OCI_REGISTRY/z-schedule-admin:$IMAGE_VERSION"
FRONT_TAG="$OCI_REGISTRY/z-schedule-suit:$IMAGE_VERSION"

echo ""
echo "=== Step 2/3: 构建后端镜像（$ADMIN_TAG）==="
docker build $NO_CACHE \
    -f deploy/Dockerfile.backend \
    --build-arg JAR_FILE="$JAR" \
    -t "$ADMIN_TAG" \
    .

echo ""
echo "=== Step 3/3: 构建前端镜像（$FRONT_TAG）==="
docker build $NO_CACHE \
    -f deploy/Dockerfile.frontend \
    -t "$FRONT_TAG" \
    .

if [ "$PUSH" = "true" ]; then
    echo ""
    echo "=== Pushing to $OCI_REGISTRY ==="
    docker push "$ADMIN_TAG"
    docker push "$FRONT_TAG"
fi

echo ""
echo "✓ 构建完成。本地镜像："
docker images | grep z-schedule || true
echo ""
echo "  k8s 清单里 image: 用的也是 \$OCI_REGISTRY/z-schedule-admin:\$IMAGE_VERSION，"
echo "  与上面两个 tag 必须逐字相同，否则 pod 拉的是从没构建过的名字。"
