#!/usr/bin/env bash
# p26.sh — Mode 2（分体：前端 nginx + 后端 jar）/ Mode 3（集群：1 前端 + N 后端）部署彩排
#
# 为什么有这一档：p25 把 Mode 1 那条入口路从头到尾跑通了，而它在收尾处留了一句实话——
#   "Mode 2/3 的前端是装饰（_frontend 那个桩里一次 API 调用都没有）"。
# 问题是"装饰"这两个字把两种完全不同的东西混在一起了：
#   a) 前端页面本身没接 API（这是产品进度，不是缺陷）；
#   b) **前端镜像根本没被构建过一次**（这才是缺陷：deploy/Dockerfile.frontend 从 250 到今天
#      第一次真跑就是红的，Mode 2/3 的整条路对谁都没兑现过）。
# 这一档只管 b：把 README/清单里写出去的每条 Mode 2/3 主张，在真机上按字面敲一遍。
#
# 首跑（09-27 04:0x，250）实测出来的两条，都不是"文档措辞"级别的问题：
#   1) Dockerfile.frontend 只 COPY _frontend/z-schedule-frontend/，而 app 的 `npm run build`
#      第一步是 `cd ../z-schedule-frontend-component`（package.json 也以 file:../ 依赖它）⇒
#      `sh: cd: line 0: can't cd to ../z-schedule-frontend-component: No such file or directory`，
#      rc=2，镜像建不出来。修法：builder 里摆成兄弟目录（S1/S1b 钉形状，B1 钉真构建）。
#   2) app 的 vite base 写死 '/meta/'（Mode 1 那份 dist 由 Spring 挂在 /meta context-path 下才对），
#      而这个镜像是 nginx 在 `/` 上服务 SPA（Mode 2/3 的 ${HTTP_PORT}:80、k8s ingress 的 path: / 都是根）
#      ⇒ index.html 里的 /meta/assets/*.js 在容器里没有那份文件。这一条光是"修好 1)"不会显形，
#      所以 B7c 拿同一个 Dockerfile 只翻 FRONTEND_BASE 这个旋钮，重造一份"改前形状"的镜像，
#      当场量那份的资产请求探到的是什么（读数长在 B7c 上，不在这里断言）——尺的猎物与缺陷的证词
#      来自同一次构建，而不是来自"我记得旧形状会白屏"。
#
# 用法（在 250 的 ~/z-schedule-e2e 里，与 p25 同一套素材）：
#   ./p26.sh                      全跑（静态 + Mode 2 真起 + Mode 3 真起）
#   P26_STATIC_ONLY=1 ./p26.sh    只跑静态那半（不碰 docker）
#   P26_SKIP_CLUSTER=1 ./p26.sh   跳过 Mode 3（只彩排分体）
#
# ⚠ 这一档不碰常驻服务：宿主 18098 的 pid 在开头结尾各读一次，变了就是这一档伤了不该伤的东西（Z2）。
#   库是本档自带的临时容器（随机 root 口令，只落 0600 的临时文件，不进 argv、不进输出）。
set -u
cd "$(dirname "$0")"

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $*"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $*"; }
FATAL(){ echo "FATAL: $*"; exit 1; }
W="$$"; WORK="$(pwd)/logs/p26_$W"; mkdir -p "$WORK"

DEPLOY=""
for c in deploy ../deploy ../../deploy ../../../deploy; do
  [ -d "$c/k8s" ] && { DEPLOY="$(cd "$c" && pwd)"; break; }
done
[ -n "$DEPLOY" ] || FATAL "找不到 deploy/ 目录（DEPLOY=... 手动指）"
E2E="${E2E:-$(pwd)}"
FRONT="${FRONT:-$E2E/_frontend}"
command -v python3 >/dev/null || FATAL "没有 python3"
[ -d "$FRONT/z-schedule-frontend" ] || FATAL "前端源码不在：FRONT=$FRONT（这一档要用它现构镜像）"
[ -d "$FRONT/z-schedule-frontend-component" ] || FATAL "组件层源码不在：$FRONT/z-schedule-frontend-component"

NGX="$DEPLOY/nginx.conf.template"
DFE="$DEPLOY/Dockerfile.frontend"
VCFG="$FRONT/z-schedule-frontend/vite.config.js"
CLUSTER="$DEPLOY/docker-compose.cluster.yml"
SPLIT="$DEPLOY/docker-compose.split.yml"

echo "deploy = $DEPLOY"
echo "frontend source = $FRONT"
echo "=== 静态：前端镜像的两条构建前提 + nginx 契约 ==="

# S1 builder 阶段必须把组件层摆成 app 的兄弟目录（两个 `../` 指同一个父目录 ⇒ 只能同级）。
#    判据认三件事：组件层有 COPY、WORKDIR 是 /src、--from 取的是 /src/z-schedule-frontend/dist。
#    阳性对照：同一条尺跑在"改前那份"（只 COPY app、WORKDIR /build、--from /build/dist）上必须报缺。
s1_lines() {
  local F="$1"
  local C1 C2 C3
  C1=$(grep -cE '^COPY[[:space:]]+_frontend/z-schedule-frontend-component/' "$F" 2>/dev/null || true)
  C2=$(grep -cE '^WORKDIR[[:space:]]+/src$' "$F" 2>/dev/null || true)
  C3=$(grep -cE '^COPY[[:space:]]+--from=builder[[:space:]]+/src/z-schedule-frontend/dist' "$F" 2>/dev/null || true)
  [ "${C1:-0}" = "1" ] && [ "${C2:-0}" = "1" ] && [ "${C3:-0}" = "1" ]
}
if s1_lines "$DFE"; then
  printf 'FROM node:18-alpine AS builder\nWORKDIR /build\nCOPY _frontend/z-schedule-frontend/package.json ./\nCOPY --from=builder /build/dist /usr/share/nginx/html\n' > "$WORK/prey_s1"
  if s1_lines "$WORK/prey_s1"; then
    FATAL "S1 的尺没有牙：'改前形状'的猎物也判通过，后面这条不许信"
  else
    ok "S1 Dockerfile.frontend 的 builder 是兄弟目录布局（组件层 COPY / WORKDIR /src / --from=/src/z-schedule-frontend/dist 各 1 处；同一把尺在改前猎物上报缺 ⇒ 有牙）"
  fi
else
  bad "S1 builder 不是兄弟目录布局（组件层 $(grep -cE '^COPY[[:space:]]+_frontend/z-schedule-frontend-component/' "$DFE" 2>/dev/null) 处 / 真 build 死在 cd ../z-schedule-frontend-component）"
fi

# S1b 不许留下第二个 COPY 目标的旧路径：/build 这个 WORKDIR 一旦残留，--from 与产物路径会分家
#     （多阶段构建里两边各自成立、镜像里却是空目录——这种形状只看"有没有那行"是抓不到的）。
#     尺只看指令行：模板注释里写着 bin/build-images.sh，那 substring 也含 "/build"，
#     第一版就是这么把一条健康的 Dockerfile 判红的（数到 1 处，而那 1 处是文件名）。
old_paths() { grep -cE '^(WORKDIR|COPY).*[[:space:]]/build' "$1" 2>/dev/null || true; }
LEFTOLD=$(old_paths "$DFE")
printf 'WORKDIR /build\nCOPY _frontend/z-schedule-frontend-component/ ./z-schedule-frontend-component/\nCOPY --from=builder /build/dist /usr/share/nginx/html\n' > "$WORK/prey_s1b"
if [ "${LEFTOLD:-0}" = "0" ] && [ "$(old_paths "$WORK/prey_s1b")" = "2" ]; then
  ok "S1b Dockerfile 的指令行里 /build 零残留（同一把尺在旧路径猎物上数到 2 处：WORKDIR 与 --from 各一 ⇒ 不是恒零）"
else
  bad "S1b 仍有 ${LEFTOLD:-?} 处指令行引用 /build，与 --from=/src/... 不同源 ⇒ 拷进去的可能是空目录（猎物对照=$(old_paths "$WORK/prey_s1b")/2）"
fi

# S2 vite 的 base 必须从 env 进来且默认值不变（Mode 1 那份 dist 依赖 '/meta/'）。
s2_hit() { grep -c "process.env.VITE_BASE" "$1" 2>/dev/null || true; }
if [ "$(s2_hit "$VCFG")" = "1" ] && grep -q "|| '/meta/'" "$VCFG"; then
  printf "export default defineConfig({\n    base: '/meta/',\n});\n" > "$WORK/prey_s2.js"
  [ "$(s2_hit "$WORK/prey_s2.js")" = "0" ] \
    && ok "S2 vite base 走 VITE_BASE、默认仍 '/meta/'（尺在写死 '/meta/' 的猎物上数到 0 ⇒ 认得改前形状）" \
    || FATAL "S2 的尺没有牙：写死 base 的猎物也命中，这条不许信"
else
  bad "S2 vite base 没接 VITE_BASE（命中 $(s2_hit "$VCFG")/1，默认值 $(grep -c "|| '/meta/'" "$VCFG")/1）⇒ nginx 在根上服务时资源前缀没法按镜像切"
fi
# S2b Dockerfile 侧必须把这个旋钮拧到 '/'，并且是以 ARG 暴露（写死 ENV 的话，B7c 造"改前形状"
#     的镜像就没法只翻旋钮——尺的猎物与缺陷证词同一次构建这件事，依赖这一条成立）。
FBASE=$(grep -cE '^ARG[[:space:]]+FRONTEND_BASE=/$' "$DFE" 2>/dev/null || true)
FENV=$(grep -cE '^ENV[[:space:]]+VITE_BASE=\$\{FRONTEND_BASE\}$' "$DFE" 2>/dev/null || true)
[ "${FBASE:-0}" = "1" ] && [ "${FENV:-0}" = "1" ] \
  && ok "S2b Dockerfile 暴露 ARG FRONTEND_BASE=/ 并注入 ENV VITE_BASE（各 1 处 ⇒ B7c 能用 --build-arg 复现改前形状）" \
  || bad "S2b ARG FRONTEND_BASE=${FBASE:-0}/1、ENV VITE_BASE=${FENV:-0}/1 ⇒ 旋钮没暴露，Mode 2 的资源前缀改不动也测不了"

# S3 nginx 契约的三条字面量。反代那条必须落在 /meta/ 根上：后端控制器没有一个挂在 /api/** 下
#    （/jobinfo /joblog /user /jobgroup /executor /glue 全在 context-path 下），多写一层 api 就全 404。
#    数"错前缀"必须剥注释：模板里那段历史说明原样写着 "/meta/api/"，第一版就是这么把一份正确的
#    模板判成缺陷的（错前缀数到 1，而那 1 处是注释）。猎物对照同一条尺跑在真写错的那份上。
ngx_effective() { grep -vE '^[[:space:]]*#' "$1" 2>/dev/null; }
PP=$(ngx_effective "$NGX" | grep -cF 'proxy_pass         http://${BACKEND_SERVICE}:18086/meta/;' || true)
BADPP=$(ngx_effective "$NGX" | grep -cF '/meta/api/' || true)
HLZ=$(ngx_effective "$NGX" | grep -c 'location = /healthz' || true)
SPA=$(ngx_effective "$NGX" | grep -cF 'try_files $uri $uri/ /index.html;' || true)
sed 's#18086/meta/#18086/meta/api/#' "$NGX" > "$WORK/prey_s3"
PPT=$(ngx_effective "$WORK/prey_s3" | grep -cF 'proxy_pass         http://${BACKEND_SERVICE}:18086/meta/;' || true)
BADT=$(ngx_effective "$WORK/prey_s3" | grep -cF '/meta/api/' || true)
if [ "${PP:-0}" = "1" ] && [ "${BADPP:-0}" = "0" ] && [ "${HLZ:-0}" = "1" ] && [ "${SPA:-0}" = "1" ] \
   && [ "${PPT:-0}" = "0" ] && [ "${BADT:-0}" = "1" ]; then
  ok "S3 nginx 模板三条字面量齐（剥注释后：/api/ → :18086/meta/ 1 处、/meta/api/ 0 处、= /healthz 1 处、SPA 回退 1 处；把 proxy_pass 改成旧形状的那份猎物上，同一把尺 good=$PPT/坏=$BADT ⇒ 认得这种病）"
else
  bad "S3 nginx 模板不成套：proxy_pass=${PP:-?}/1 错前缀=${BADPP:-?}/0 healthz=${HLZ:-?}/1 SPA回退=${SPA:-?}/1（猎物 good=${PPT:-?}/坏=${BADT:-?}，应 0/1；尺不剥注释的话这份模板会被误判成缺陷——历史说明里原样写着 /meta/api/）"
fi

# S4 两份清单的镜像名必须与构建产物的 tag 同源，否则清单永远拉一个没构建过的名字。
IMGLINE='image: "${OCI_REGISTRY:-ghcr.io/yuku123}/z-schedule-frontend:${IMAGE_VERSION:-1.0.4}"'
S4BAD=""
for f in "$SPLIT" "$CLUSTER"; do
  grep -qF "$IMGLINE" "$f" || S4BAD="$S4BAD $(basename "$f")[前端 image: 与 build-images.sh 的 tag 不同源]"
  grep -qF '"${HTTP_PORT:-80}:80"' "$f" || S4BAD="$S4BAD $(basename "$f")[宿主端口没走 HTTP_PORT]"
done
grep -qF 'FRONT_TAG="$OCI_REGISTRY/z-schedule-frontend:$IMAGE_VERSION"' "$DEPLOY/bin/build-images.sh" \
  || S4BAD="$S4BAD build-images.sh[tag 形状变了，S4 的对照要跟着改]"
# 集群那份不许钉 container_name：compose 对 --scale 的服务会直接拒（名字会撞），而 Mode 3 的
# 卖点是副本数由命令行决定。分体那份钉了是对的（正好用它当这一臂的阳性对照——同一把尺两处读数不同）。
CNCLUS=$(grep -cE '^\s*container_name:' "$CLUSTER" 2>/dev/null || true)
CNSPLIT=$(grep -cE '^\s*container_name:' "$SPLIT" 2>/dev/null || true)
if [ -z "$S4BAD" ] && [ "${CNCLUS:-0}" = "0" ] && [ "${CNSPLIT:-0}" -ge 2 ]; then
  ok "S4 两份清单与构建同源（前端 image: 逐字等于 build-images.sh 的 FRONT_TAG、HTTP_PORT 可配；cluster 里 container_name 命中 $CNCLUS 处才敢 --scale，同一把尺在 split 上数到 $CNSPLIT ⇒ 这一臂不是恒零）"
else
  bad "S4 清单不同源或有隐患：${S4BAD:-} cluster container_name=${CNCLUS:-?}（应 0，split=${CNSPLIT:-?} 用于对照）"
fi

[ "${P26_STATIC_ONLY:-0}" = "1" ] && { echo ""; echo "总判（只跑静态）：PASS=$PASS FAIL=$FAIL"; [ "$FAIL" = "0" ] && exit 0 || exit 1; }

command -v docker >/dev/null || FATAL "运行时臂要 docker"
CCLI="docker compose"; docker compose version >/dev/null 2>&1 || CCLI="docker-compose"
RUNJAR="${RUNJAR:-$E2E/z-schedule-admin-svc-7ea14eb-exec.jar}"
DDL="${DDL:-$E2E/z-schedule.sql}"
[ -f "$RUNJAR" ] || FATAL "构件不存在：RUNJAR=$RUNJAR"
[ -f "$DDL" ] || FATAL "建表脚本不存在：DDL=$DDL"
DBIMAGE="${DBIMAGE:-mysql:8.0.26}"
docker image inspect "$DBIMAGE" >/dev/null 2>&1 || FATAL "本机没有 $DBIMAGE 镜像 ⇒ 这一档自带临时库，不去拉公网"
DB26=zschedule_p26
DBC="p26db$W"; DBVOL="p26mysqldata$W"
ADMIN_IMG="ghcr.io/yuku123/z-schedule-admin:1.0.4"
FRONT_IMG="ghcr.io/yuku123/z-schedule-frontend:1.0.4"
DBPW=$(python3 -c "import secrets,string;print(''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range(18)))")
MYENV="$WORK/mypwd"; printf 'MYSQL_PWD=%s\n' "$DBPW" > "$MYENV"; chmod 600 "$MYENV"
mysql_root() { docker exec -i --env-file "$MYENV" "$DBC" mysql -uroot --skip-column-names -B "$@" 2>/dev/null; }

# HTTP_PORT：Mode 2/3 只由前端这一个口对外（后端只 expose）。250 上宿主 :80 被一个宿主进程占着、
#            没有任何容器 claim 它 ⇒ 照清单默认的 80 起必失败在 "port is already allocated"。
#            绑不到就退，不复用别人的端口。
HTTP_PORT=$(python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("0.0.0.0", 0))
print(s.getsockname()[1]); s.close()
PY
) || FATAL "取不到空闲端口"
[ "${HTTP_PORT:-0}" -gt 1024 ] || FATAL "空闲端口 $HTTP_PORT 太低，别用它当发布口"

cleanup() {
  ( cd "$DEPLOY" && $CCLI --env-file env/.env -f docker-compose.cluster.yml down ) >/dev/null 2>&1
  ( cd "$DEPLOY" && $CCLI --env-file env/.env -f docker-compose.split.yml down ) >/dev/null 2>&1
  docker rm -f "$DBC" >/dev/null 2>&1
  docker volume rm "$DBVOL" >/dev/null 2>&1
  for n in $(docker ps -aq --filter "name=p26prey" 2>/dev/null); do docker rm -f "$n" >/dev/null 2>&1; done
  docker rmi -f "$ADMIN_IMG" "$FRONT_IMG" local/z-schedule-frontend:p26prey >/dev/null 2>&1
  if [ "${KEEP_ENV:-0}" = "1" ]; then :
  elif [ -f "$WORK/env.pbak" ]; then cp -p "$WORK/env.pbak" "$DEPLOY/env/.env"
  else rm -f "$DEPLOY/env/.env"
  fi
  rm -rf "$WORK"
  # 收口对账必须在**删完之后**做：本档自己的临时库/卷要到 trap 才消失，放在臂里数必然虚报残留。
  RES=$(docker ps -aq --filter 'name=p26' | wc -l | tr -d ' ')
  VRES=$(docker volume ls --format '{{.Name}}' | grep -c "p26" || true)
  NETS=$(docker network ls --format '{{.Name}}' | grep -c "p26" || true)
  if [ "${RES:-1}" = "0" ] && [ "${VRES:-1}" = "0" ] && [ "${NETS:-1}" = "0" ]; then
    echo "收口对账：本档的容器/卷/网络残留 = 0/0/0"
  else
    echo "收口对账：残留 容器=${RES:-?} 卷=${VRES:-?} 网络=${NETS:-?} ⇒ 有东西没清干净（工作目录已删，证据去 docker ls 里找）"
    exit 1
  fi
}
trap cleanup EXIT

echo ""
echo "=== 运行时前置 ==="
PID18098=$(ss -ltnp 2>/dev/null | grep ':18098 ' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
[ -n "${PID18098:-}" ] && ok "Z0 常驻服务在 pid=$PID18098 上听 18098（本档只在结尾对账它没变）" \
                       || echo "    [note] 这台没读到 18098 的常驻服务 ⇒ Z2 那一臂按'无可对账'记账"
echo "  本档的发布口 HTTP_PORT=$HTTP_PORT（清单默认 80，被宿主进程占着）"

# 写 env：与 p25 同一套键，DB_HOST 留到 create 之后按网络填（这里先占位，B5 会重写）。
[ -f "$DEPLOY/env/.env" ] && cp -p "$DEPLOY/env/.env" "$WORK/env.pbak"
write_env() {   # $1 = DB_HOST 值
  python3 - "$DBPW" "$DB26" "$1" "$HTTP_PORT" > "$DEPLOY/env/.env" <<'PYENV'
import sys
print("DB_HOST=%s" % sys.argv[3])
print("DB_PORT=3306")
print("DB_NAME=%s" % sys.argv[2])
print("DB_USER=root")
print("DB_PASSWORD=%s" % sys.argv[1])
print("DB_POOL_MAX_ACTIVE=20")
print("OCI_REGISTRY=ghcr.io/yuku123")
print("IMAGE_VERSION=1.0.4")
print("JAR_FILE=z-schedule-admin/target/p26-exec.jar")
print("HTTP_PORT=%s" % sys.argv[4])
print("JAVA_OPTS=-Xms256m -Xmx512m")
PYENV
  chmod 600 "$DEPLOY/env/.env"
}

# B0 照 README 第 0 步（`cp env/.env.example env/.env`）起出来的那份 env，看 compose 把 JAR_FILE 渲染成
#     什么。这一格要的不是"构建成功"，是**默认值本身**：静态臂 A6c 只钉"字面量不许回来"，而这里量的是
#     真解析结果（字面量的两种形状：缺文件 rc=1 会响、同名留一份旧 jar 则 rc=0 而镜像里是旧字节；
#     通配的三种形状：0 命中响、唯一命中过、多命中歧义响 —— 读数在 README §14）。阳性对照：
#     显式给一个具体 jar，渲染必须跟着变，否则这条臂只是在读自己写的字符串。
TPL="$WORK/env_from_template"
sed -E -e 's|^DB_HOST=.*|DB_HOST=127.0.0.1|' -e 's|^DB_PORT=.*|DB_PORT=3306|' \
       -e 's|^DB_NAME=.*|DB_NAME=p26b0|' -e 's|^DB_USER=.*|DB_USER=p26b0|' \
    "$DEPLOY/env/.env.example" > "$TPL" || FATAL "B0 复制不出 env 模板"
grep -q '^DB_NAME=p26b0$' "$TPL" || FATAL "B0 模板里没有 DB_NAME 这一行（形状变了，这条臂得跟着改）"
B0DEF=$($CCLI --env-file "$TPL" -f "$DEPLOY/docker-compose.split.yml" config 2>/dev/null \
        | grep -oE 'JAR_FILE: +[^ ]+\*-exec\.jar' | head -1)
B0OVR=$(JAR_FILE=z-schedule-admin/target/whatever-exec.jar \
        $CCLI --env-file "$TPL" -f "$DEPLOY/docker-compose.split.yml" config 2>/dev/null \
        | grep -E 'JAR_FILE:' | head -1)
if [ -n "$B0DEF" ] && ! echo "$B0OVR" | grep -q '\*-exec\.jar'; then
  ok "B0 模板渲染出的 JAR_FILE 是通配（'${B0DEF#*:} '），显式指一份具体 jar 时渲染跟着换成 '$B0OVR' ⇒ 数的是插值结果，不是恒串"
else
  bad "B0 模板渲染不对：默认='${B0DEF:-无通配命中}' 覆盖='$B0OVR'（期望默认渲染出 target/*-exec.jar 且覆盖时改变）⇒ 照 README 第 0 步做的人拿不到一条唯一命中的路径"
fi

echo ""
echo "=== B1 前端镜像：照清单的 build 真跑一次 ==="
# 构件先就位（后端那一半的 COPY 目标；compose v5 的 build 就在这一遍发生）。
mkdir -p "$E2E/z-schedule-admin/target" 2>/dev/null
cp "$RUNJAR" "$E2E/z-schedule-admin/target/p26-exec.jar" 2>/dev/null || FATAL "放不进构件"
write_env db   # B1 只解析镜像，不连库：DB_HOST 指不存在的别名也照样 build
( cd "$DEPLOY" && $CCLI --env-file env/.env -f docker-compose.split.yml build z-schedule-frontend \
    > "$WORK/build_front.log" 2>&1 )
BRC=$?
NBUILD=$(grep -c '^vite v' "$WORK/build_front.log" 2>/dev/null || true)
VCNT=$(grep -c 'z-schedule-frontend-component@' "$WORK/build_front.log" 2>/dev/null || true)
CTX=$(grep -oE 'Sending build context to Docker daemon +[0-9.]+ ?(kB|MB|GB|KiB|MiB|GiB)' "$WORK/build_front.log" | tail -1)
if [ "$BRC" = "0" ]; then
  ok "B1 前端镜像构建 rc=0（改前这一条 rc=2 死在 cd ../z-schedule-frontend-component；构建上下文 $CTX）"
else
  tail -14 "$WORK/build_front.log" | sed 's/^/    /'
  bad "B1 前端镜像构建 rc=$BRC ⇒ Mode 2/3 整条路对谁都没兑现过"
fi
# B1b 一次构建里必须有**两次** vite build：组件层（lib 模式）与应用层。只有一次说明组件层被跳过，
#     而 app 的 src/App.jsx 是从组件层 import JobListView 的——那种"绿"下次就会漂回 B1 的红。
if [ "${NBUILD:-0}" -ge 2 ] && [ "${VCNT:-0}" -ge 1 ]; then
  ok "B1b 构建日志里有 $NBUILD 次 vite build，其中 $VCNT 处署名组件层包名 ⇒ 组件层真的被编了"
else
  bad "B1b vite build=$NBUILD/组件层署名=$VCNT ⇒ 组件层没编进产物（App.jsx 从它 import）"
fi

echo ""
echo "=== B2 镜像产物：base 必须落在根上 ==="
IDX=$(docker run --rm --entrypoint cat "$FRONT_IMG" /usr/share/nginx/html/index.html 2>/dev/null)
ROOTJS=$(echo "$IDX" | grep -c 'src="/assets/' || true)
METAPFX=$(echo "$IDX" | grep -c '/meta/assets/' || true)
ASSETNAMES=$(docker run --rm --entrypoint sh "$FRONT_IMG" -c 'ls /usr/share/nginx/html/assets' 2>/dev/null | tr '\n' ' ')
if [ "${ROOTJS:-0}" = "1" ] && [ "${METAPFX:-0}" = "0" ] && [ -n "${ASSETNAMES// /}" ]; then
  ok "B2 index.html 引用的是 /assets/*（$ASSETNAMES），/meta/assets/ 命中 $METAPFX 处 ⇒ 与 nginx 的根服务同侧"
else
  bad "B2 index.html 的 base 不对：/assets/ 命中 ${ROOTJS:-?}、/meta/assets/ 命中 ${METAPFX:-?}、assets 目录内容=[$ASSETNAMES]"
fi
# B2b 镜像里必须有 wget——两份清单给前端写的 healthcheck 是 `wget -qO- http://127.0.0.1/healthz`，
#     命令不存在的话容器会永远"起不来"，而报出来的原因是健康检查超时，不是配置错。
FWGET=$(docker run --rm --entrypoint sh "$FRONT_IMG" -c 'command -v wget >/dev/null && echo yes || echo no' 2>/dev/null)
[ "$FWGET" = "yes" ] && ok "B2b 前端镜像里有 wget ⇒ 清单里那条 healthcheck 用的是个存在的命令" \
                     || bad "B2b 前端镜像里没有 wget ⇒ healthcheck 永远红（清单说的'两个容器都 healthy'到不了）"

echo ""
echo "=== B3 起 Mode 2（临时库 + 两个容器）==="
# 先 create 拿网络名，再把临时库挂到那张网上并用别名 db 顶上 DB_HOST（跨网形状 p25/P8f 已实测：
# 跨自定义网络是**超时**不是拒绝，拿裸 IP 会得到一个"端口不 bind、日志无限刷"的容器）。
( cd "$DEPLOY" && $CCLI --env-file env/.env -f docker-compose.split.yml create > "$WORK/create.log" 2>&1 ) \
  || { tail -8 "$WORK/create.log" | sed 's/^/    /'; FATAL "compose create 失败"; }
NET=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' z-schedule-backend | awk '{print $1}')
[ -n "$NET" ] || FATAL "读不出 z-schedule-backend 会挂的网络"
docker volume create "$DBVOL" >/dev/null 2>&1 || FATAL "建不出卷 $DBVOL"
docker run -d --name "$DBC" --network "$NET" --network-alias db --volume "$DBVOL:/var/lib/mysql" \
    -e MYSQL_ROOT_PASSWORD="$DBPW" -e MYSQL_DATABASE="$DB26" \
    -v "$DDL:/docker-entrypoint-initdb.d/60-z-schedule.sql:ro" "$DBIMAGE" >/dev/null 2>&1 \
  || FATAL "临时库容器起不来"
TABLES=0
for i in $(seq 1 40); do
  TABLES=$(mysql_root -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB26'" | tr -d '[:space:]')
  [ "${TABLES:-0}" -ge 6 ] && break
  sleep 3
done
[ "${TABLES:-0}" -ge 6 ] || { docker logs --tail 15 "$DBC" 2>&1 | sed 's/^/    /'; FATAL "临时库 120 s 内没建出表（tables=${TABLES:-?}）"; }
mysql_root -e "INSERT INTO $DB26.z_schedule_job_group (id, app_name, title, order_num, address_type) VALUES (1,'default','p26 rehearsal',0,0)" >/dev/null 2>&1
write_env db
ok "B3 临时库就绪（网络 $NET / 别名 db / $DB26 里 $TABLES 张表），env/.env 的 HTTP_PORT=$HTTP_PORT"

UP=$( cd "$DEPLOY" && $CCLI --env-file env/.env -f docker-compose.split.yml up -d 2>&1 ); URC=$?
echo "$UP" > "$WORK/up.log"
[ "$URC" = "0" ] && ok "B4 '$CCLI -f docker-compose.split.yml up -d' rc=0（改前这一条根本走不到：镜像建不出来）" \
                 || { echo "$UP" | tail -10 | sed 's/^/    /'; bad "B4 Mode 2 up rc=$URC"; }

HCHECK() {   # $1 = 容器名，$2 = 最长等待秒
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    local st; st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1" 2>/dev/null)
    [ "$st" = "healthy" ] && { echo "healthy"; return 0; }
    [ "$st" = "unhealthy" ] && { echo "$st"; return 1; }
    sleep 4
  done
  echo "${st:-读不到}"
}
SB=$(HCHECK z-schedule-backend 180); SF=$(HCHECK z-schedule-frontend 120)
NB=$(docker inspect -f '{{if .State.Health}}{{len .State.Health.Log}}{{else}}0{{end}}' z-schedule-backend 2>/dev/null)
NF=$(docker inspect -f '{{if .State.Health}}{{len .State.Health.Log}}{{else}}0{{end}}' z-schedule-frontend 2>/dev/null)
if [ "$SB" = "healthy" ] && [ "$SF" = "healthy" ]; then
  ok "B5 两个容器的 healthcheck 各自变 healthy（backend 探过 $NB 次、frontend $NF 次 ⇒ 清单那两条 wget 都在真执行，不是'端口通了'的另一种说法）"
else
  docker ps -a --format '{{.Names}} {{.Status}}' | sed 's/^/    /'
  bad "B5 健康状态 backend=$SB frontend=$SF（探测次数 $NB/$NF）⇒ README 的'两个容器都 healthy'到不了"
fi

echo ""
echo "=== B6–B12 前端这一层的对外契约（README/清单里写出去的那几步，逐条按字面敲）==="

# B5b 清单和 start-mode2.sh 一起教人敲 `docker exec -it z-schedule-backend curl …`。命令在不在镜像里
#     不是文档的事——两个都要有，或者文档改口。p25/P11b 只证过 wget 存在，curl 从来没人探过。
#     光"curl 存在"还不够：这一条的验收是那串 URL 真答 UP，否则文档给的验证步骤是一句空话。
#     -it 在这里不能照抄（非 TTY 下 docker exec -t 直接报错，与镜像无关），所以只去掉 -it。
BACK_C=$(docker ps --format '{{.Names}}' | grep 'z-schedule-backend' | head -1)
[ -n "$BACK_C" ] || FATAL "B5b 认不出后端容器（Mode 2 没起成？）"
HASBW=$(docker exec "$BACK_C" sh -c 'command -v wget >/dev/null && echo yes || echo no')
HASBC=$(docker exec "$BACK_C" sh -c 'command -v curl >/dev/null && echo yes || echo no')
DOCURL=$(grep -chE 'docker exec[^`]*curl ' "$SPLIT" "$CLUSTER" "$DEPLOY/bin/start-mode2.sh" \
         2>/dev/null | awk '{s+=$1} END{print s+0}')
# 三处各自的命中数从尺里取，不手抄进消息（写死的拆分一定会和实测脱钩）。
DOC3=$(grep -cE 'docker exec[^`]*curl ' "$SPLIT" 2>/dev/null || true)
DOC4=$(grep -cE 'docker exec[^`]*curl ' "$CLUSTER" 2>/dev/null || true)
DOC5=$(grep -cE 'docker exec[^`]*curl ' "$DEPLOY/bin/start-mode2.sh" 2>/dev/null || true)
DOCB=$(docker exec "$BACK_C" curl -s -m 15 http://127.0.0.1:18086/meta/actuator/health 2>&1 | head -c 200)
DOCUP=$(printf '%s' "$DOCB" | grep -c '"status":"UP"' || true)
if [ "${DOCUP:-0}" = "1" ]; then
  ok "B5b 照文档敲这条验证命令真得到 UP（镜像里 curl=$HASBC / wget=$HASBW；教它写 curl 的地方 $DOCURL 处 = 分体清单 $DOC3 + 集群清单 $DOC4 + start-mode2.sh $DOC5）"
else
  bad "B5b 文档教 curl 而实测拿不到 UP（curl=$HASBC / wget=$HASBW，写 curl 的地方 $DOCURL 处 = 分体 $DOC3 + 集群 $DOC4 + 脚本 $DOC5，读出=${DOCB:-空}）⇒ 照文档做验收会撞 'curl: not found' 或一句错误，不是一个健康读数"
fi

H="http://127.0.0.1:$HTTP_PORT"
get() { curl -s -m 25 -o "$1" -w '%{http_code} %{content_type}' "$2"; }   # $1=落体文件 $2=URL

# B6 GET / ：必须是 SPA 页面本体（不是 50x.html、不是 nginx 默认页）
CODE=$(get "$WORK/root.html" "$H/")
SPAROOT=$(grep -c '<div id="root">' "$WORK/root.html" 2>/dev/null || true)
ROOTASSET=$(grep -c 'src="/assets/' "$WORK/root.html" 2>/dev/null || true)
if [ "${CODE%% *}" = "200" ] && [ "${SPAROOT:-0}" = "1" ] && [ "${ROOTASSET:-0}" = "1" ]; then
  ok "B6 GET / 返回 $CODE、页面里有 #root 与 /assets/ 引用 ⇒ 发布口上给的是这个应用的前端"
else
  head -c 200 "$WORK/root.html" | tr '\n' ' ' | sed 's/^/    body: /'; echo
  bad "B6 GET / 不是 SPA 本体：code+type='$CODE' #root=${SPAROOT:-?} /assets/=${ROOTASSET:-?}"
fi

# B7 页面引用的那个 js 必须真取得到且是 javascript。这是"白屏"与"能打开"的分界：
#    模板里 location ~* \.(js|css|...)$ 那条不带 try_files，命不中文件就是 404；
#    而 base 若还写着 /meta/，请求会先落进 SPA 回退那条、拿回一份 HTML 当模块脚本（页面同样白）。
JS=$(grep -oE 'src="/assets/[^"]+\.js"' "$WORK/root.html" | head -1 | sed 's/^src="//; s/"$//')
[ -n "$JS" ] || FATAL "B7 从页面里取不出 js 路径（页面形状变了，先修尺）"
JCODE=$(get "$WORK/app.js" "$H$JS")
JSIZE=$(wc -c < "$WORK/app.js" 2>/dev/null || echo 0)
if [ "${JCODE%% *}" = "200" ] && echo "$JCODE" | grep -qi 'javascript'; then
  ok "B7 GET $JS → ${JCODE%% *}（${JCODE#* }，$JSIZE 字节）⇒ 页面加载后真的有脚本可执行"
else
  bad "B7 GET $JS → '$JCODE'（$JSIZE 字节）⇒ 白屏：页面在、脚本取不到"
fi
CSS=$(grep -oE 'href="/assets/[^"]+\.css"' "$WORK/root.html" | head -1 | sed 's/^href="//; s/"$//')
CCODE=$(get /dev/null "$H$CSS")
[ "${CCODE%% *}" = "200" ] && ok "B7b GET $CSS → 200（样式那条 location 的 root 也对）" \
                           || bad "B7b GET $CSS → '$CCODE'"

# B7c 反方向的实测（尺的猎物，也是缺陷的证词）：同一个 Dockerfile 只翻 FRONTEND_BASE 这个旋钮，
#     造一份"改前形状"的镜像，量它的资产请求到底是什么。不重新跑产品链路——差别只在 base。
#     ⚠ 这一臂的第一遍是假红：prey 容器被 docker run 扔到默认 bridge 上，那里没有按名字的 DNS，
#     nginx 在**解析配置阶段**就起不来（见 B7d），我读到的"路径为空 + code=000"是它根本没在服务，
#     不是"探不到 javascript"。所以这里显式挂进 Mode 2 那张网、并等它真答一次再判。
#     三个 rc（build / run / 首答）分开记：混成一个"猎物没复现"就又变成猜。
PPORT=$(python3 - <<'PY'
import socket
s = socket.socket(); s.bind(("0.0.0.0", 0)); print(s.getsockname()[1]); s.close()
PY
)
( cd "$DEPLOY/.." && docker build --build-arg FRONTEND_BASE=/meta/ \
      -f deploy/Dockerfile.frontend -t local/z-schedule-frontend:p26prey . ) \
      > "$WORK/build_prey.log" 2>&1
PREYB=$?
PREYRETRY=0
# 猎物构建第一遍就红过：250 出网抖了一下，npm ci 拿不到包（`non-zero code: 146`），
# 而我把那条读成"猎物没复现 ⇒ 尺没有牙"。构建失败不是测量结果 ⇒ 认出网络形状就重试一次，
# 再失败就照"没量成"报，绝不报成"形状不对"。
if [ "$PREYB" != "0" ] && grep -qE 'npm error|Client\.Timeout|registry-1|ETIMEDOUT|ECONNRESET|non-zero code: (137|143|146)' \
     "$WORK/build_prey.log"; then
  PREYRETRY=1
  ( cd "$DEPLOY/.." && docker build --build-arg FRONTEND_BASE=/meta/ \
        -f deploy/Dockerfile.frontend -t local/z-schedule-frontend:p26prey . ) \
        > "$WORK/build_prey_retry.log" 2>&1
  PREYB=$?
fi
# 这一臂的尺伤另有一处：猎物构建**不该**联网跑 npm ci（Dockerfile 里 ARG/ENV 必须在 npm ci 之后）。
# 复用了 deps 层 ⇒ 只有 vite 重跑；没复用 ⇒ 这一臂又回到"形状读数受出网影响"，当场说清。
# ⚠ 读数只能来自**产出这个镜像的那一遍**：早先的重试是 `>>` 追加进同一份日志的，两遍混成一个文件去数
#   `Using cache` 就分不清"一遍冷构建"与"第一遍死在 npm ci、第二遍才建成"（run8/run9 的 `cache=0 vite=2`
#   就是这两种叠在一起的样子）⇒ 现在两遍各写一份文件。断点也一起打出来：第一个未命中缓存的 Step
#   才是这条链真正的分叉处（run10/run11 靠它把冷指到 Step 6 `RUN npm ci`，进而查到同档 B1c 那发
#   `--no-cache` 是作废者；run12 也读到过命中，但那是位置运气——同样顺序的 run13 又冷在 Step 6，
#   见 B1c 那段的两支探针）。
prey_readings() {  # $1=最后一遍构建的日志
  PREYDEP=$(grep -A1 'RUN cd z-schedule-frontend && npm ci' "$1" | grep -c 'Using cache' || true)
  PREYVITE=$(grep -c 'building for production' "$1" || true)
  PREYBREAK=$(awk '/^Step [0-9]+\//{s=$0} /---> Running in/{print s; exit}' "$1" | cut -c1-78)
  echo "    构建臂：最后一遍=[$(basename "$1")] retry=$PREYRETRY deps 层 Using cache=$PREYDEP vite 重跑=$PREYVITE 首个未命中=[$PREYBREAK]"
}
if [ "$PREYRETRY" = "1" ]; then
  prey_readings "$WORK/build_prey_retry.log"
else
  prey_readings "$WORK/build_prey.log"
fi
docker run -d --name "p26prey$W" --network "$NET" -p "$PPORT:80" \
       -e BACKEND_SERVICE=z-schedule-backend local/z-schedule-frontend:p26prey \
      > "$WORK/prey_run.log" 2>&1
PRER=$?
PREYJS=""
for i in $(seq 1 10); do
  PREYJS=$(curl -s -m 5 "http://127.0.0.1:$PPORT/" | grep -oE 'src="[^"]+\.js"' | head -1 | sed 's/^src="//; s/"$//')
  [ -n "$PREYJS" ] && break
  sleep 2
done
PREYCODE=$(get /dev/null "http://127.0.0.1:$PPORT$PREYJS")
PREYBODY=$(curl -s -m 15 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PPORT$PREYJS")
PREYSTATE=$(docker inspect -f '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}nohc{{end}}' "p26prey$W" 2>/dev/null)
docker rm -f "p26prey$W" >/dev/null 2>&1
docker rmi -f local/z-schedule-frontend:p26prey >/dev/null 2>&1
if [ "$PREYB" != "0" ]; then
  tail -6 "$WORK/build_prey$( [ "$PREYRETRY" = "1" ] && echo _retry ).log" 2>/dev/null | sed 's/^/    构建: /'
  bad "B7c **没量成**：猎物镜像构建 rc=$PREYB（网络形状重试 $PREYRETRY 次之后仍失败）⇒ 这一格记环境的账，不能记成'形状没复现'更不能记成'尺没有牙'"
elif [ -n "$PREYJS" ] && ! echo "$PREYCODE" | grep -qi 'javascript'; then
  ok "B7c 猎物实测：base 退回 '/meta/' 那一份镜像里，页面要的是 $PREYJS，探它得到 '$PREYCODE'（http $PREYBODY）⇒ 拿不回 javascript。这一遍的构建：deps 层 Using cache=$PREYDEP、vite 重跑=$PREYVITE 次（flip 的只有 base）"
else
  echo "    build rc=$PREYB run rc=$PRER 容器状态=${PREYSTATE:-读不到} Using cache=$PREYDEP vite=$PREYVITE"
  [ "$PRER" != "0" ] && tail -5 "$WORK/prey_run.log" | sed 's/^/    启动: /'
  bad "B7c 猎物没能复现白屏形状（路径=[$PREYJS] 读数='$PREYCODE'）⇒ B7 的判据没有牙，先修尺再谈绿"
fi

# B1c 尺的牙齿：同一条构建在**去掉组件层 COPY**的猎物上必须 rc≠0（只翻 S1 那一条，别的都不动）。
# ⚠ 这一臂**不带** `--no-cache`，这是实测出来的结论，不是省时间：
#   · 牙齿不需要它——删掉 COPY 那一行本身就改了那条指令的缓存键，构建必然走到那一步才发现
#     目录不在。取证（250，05:35:11，`~/.cache/nocache_probe/runner2.log` 第 ② 段）：不带
#     `--no-cache` 建 teeth ⇒ rc=2 且报 `sh: cd: line 0: can't cd to ../z-schedule-frontend-component: No ...`。
#   · 加上它反而伤人——`--no-cache` 会把整条链共享的那条 `RUN npm ci` 缓存记录顶掉。同一支探针
#     三段时间线：① 05:34:49 基线（冷，Step 6 真跑 npm ci）⇒ ② 05:35:11 不带 `--no-cache` 的 teeth
#     ⇒ ③ 05:35:13 同一条基线命令 `rc=0 Step6=CACHED 耗时=1s`，链是热的。而上一版探针
#     （05:15:49/05:16:05，`runner.log`）里 ② 是**带** `--no-cache` 的 teeth ⇒ ③ 冷、npm ci 重跑 20 s。
#   ⇒ 两遍只差那一个开关，作废者就是它。原先我写的"B1c 挪到 B7c 之后就好了"是 n=1 的归纳：
#     run13 换了顺序仍在 Step 6 读到 `deps 层 Using cache=0 vite 重跑=2`，被这一对探针否掉了。
#     顺序保留（teeth 放在 B7c 之后），但它不是修法，删掉 `--no-cache` 才是。
sed -E '/^COPY _frontend\/z-schedule-frontend-component\//d' "$DFE" > "$WORK/prey_dockerfile"
( cd "$DEPLOY/.." && docker build -f "$WORK/prey_dockerfile" -t local/z-schedule-frontend:p26teeth . \
    > "$WORK/build_teeth.log" 2>&1 )
TEETH=$?
if [ "$TEETH" != "0" ] && grep -q "can't cd to ../z-schedule-frontend-component" "$WORK/build_teeth.log"; then
  ok "B1c 猎物对照成立：删掉组件层那一行 COPY，构建当场退回 rc=$TEETH 并报 'can't cd to ../z-schedule-frontend-component'（这条修复是被实测钉住的，不是把错误信息抄进注释）"
else
  bad "B1c 猎物没退回（rc=$TEETH）⇒ B1 的绿说明不了什么，先怀疑尺"
fi
docker rmi -f local/z-schedule-frontend:p26teeth >/dev/null 2>&1

# B7d 这一臂是给"nginx 什么时候解析 proxy_pass 里的名字"取证：给一个当下不存在的服务名，
#     看它是**启动即退出**还是"先起着、等请求来了再报错"。答案决定 README 里那条注意怎么写
#     （前者⇒副本必须早于前端起来；后两者⇒扩副本能被看见）。不猜，问它一次。
docker run --rm --network "$NET" -e BACKEND_SERVICE=p26-no-such-service \
       --entrypoint /bin/sh "$FRONT_IMG" -c \
       "envsubst '\$BACKEND_SERVICE' < /etc/nginx/templates/default.conf.template > /etc/nginx/conf.d/default.conf; nginx -t 2>&1 | tail -3" \
       > "$WORK/b7d.out" 2>&1
B7DMSG=$(grep -o "host not found in upstream[^,]*" "$WORK/b7d.out" | head -1)
if [ -n "$B7DMSG" ]; then
  ok "B7d nginx 在配置阶段就拒：'$B7DMSG' ⇒ proxy_pass 的名字是启动时解析一次的（副本必须先于前端存在；之后新增的副本它看不见），README §13 那条注意按这一条写"
else
  bad "B7d 没读到预期形状，原文：$(cut -c1-200 "$WORK/b7d.out") ⇒ '启动时解析一次'这句本档没证到，别照抄"
fi

# B8 清单/K8s 探针用的那个口：/healthz 必须是 nginx 自己答（不依赖后端活着）
ZCODE=$(get "$WORK/healthz" "$H/healthz")
ZBODY=$(cat "$WORK/healthz" 2>/dev/null | tr -d '\n')
if [ "${ZCODE%% *}" = "200" ] && [ "$ZBODY" = "ok" ]; then
  ok "B8 GET /healthz → 200 'ok'（模板里那条 return 生效 ⇒ 清单/K8s 那三条 healthcheck 探的是 nginx 本体）"
else
  bad "B8 GET /healthz → '$ZCODE' body='$ZBODY'"
fi

# B9 README 的验收第 3 步：curl http://localhost/api/actuator/health 应返回 UP。
#    这一条同时钉住两件事：/api 前缀被剥掉、且落点正好是后端的 context-path 根（多一层就 404）。
ACODE=$(get "$WORK/apihealth" "$H/api/actuator/health")
AUP=$(grep -c '"status":"UP"' "$WORK/apihealth" 2>/dev/null || true)
if [ "${ACODE%% *}" = "200" ] && [ "${AUP:-0}" = "1" ]; then
  ok "B9 GET /api/actuator/health → 200 + status=UP（$(cat "$WORK/apihealth" | head -c 90)）⇒ /api → :18086/meta/ 这条反代兑现了"
else
  head -c 200 "$WORK/apihealth" | sed 's/^/    body: /'; echo
  bad "B9 GET /api/actuator/health → '$ACODE' UP命中=${AUP:-0} ⇒ 反代没落到后端根上"
fi

# B10 穿过反代读到一个**库里的**行：这才说明前端那一层连到的是这个彩排自己的库，
#     而不是碰巧活着的一个后端（引擎池与 Spring 池同坐标这件事，p25/P18 只在渲染层证过）。
LCODE=$(get "$WORK/grouplist" "$H/api/jobgroup/list")
LHIT=$(grep -c 'p26 rehearsal' "$WORK/grouplist" 2>/dev/null || true)
DBHIT=$(mysql_root -e "SELECT COUNT(*) FROM $DB26.z_schedule_job_group WHERE title='p26 rehearsal'" | tr -d '[:space:]')
if [ "${LCODE%% *}" = "200" ] && [ "${LHIT:-0}" -ge 1 ] && [ "${DBHIT:-0}" = "1" ]; then
  ok "B10 /api/jobgroup/list 回显了库里那一行（接口命中 $LHIT 处 / 库里 $DBHIT 行）⇒ 前端 → 后端 → 临时库整条通"
else
  bad "B10 对不上：'$LCODE' 接口命中 ${LHIT:-0}、库里 ${DBHIT:-?} 行"
fi

# B11 两个方向一起测：/api/** 不许被 SPA 回退吞掉，非 /api 的前端路由必须回 index.html。
#     只看其中一个都不够——只看 404 会放过"整站都 404"，只看回退会放过"/api 也回 HTML"。
SPA_CODE=$(get "$WORK/sparoute" "$H/jobinfo/list")   # 一个根本不存在于 nginx 静态目录的路径
SPA_IS=$(grep -c '<div id="root">' "$WORK/sparoute" 2>/dev/null || true)
NRC=$(get "$WORK/nope" "$H/api/__p26_nope__" | cut -d' ' -f1)
NHTML=$(grep -c '<div id="root">' "$WORK/nope" 2>/dev/null || true)
if [ "${SPA_CODE%% *}" = "200" ] && [ "${SPA_IS:-0}" = "1" ] && [ "$NRC" != "200" ] && [ "${NHTML:-0}" = "0" ]; then
  ok "B11 SPA 回退与反代互不越界：$H/jobinfo/list → 200 且是 index.html；$H/api/__p26_nope__ → HTTP $NRC 且 body 里没有 #root（是后端答的 404，不是 nginx 的回退页）"
else
  bad "B11 形状不对：回退 $SPA_CODE/#root=${SPA_IS:-?}；/api 探不到的那条 HTTP=$NRC body 里 #root=${NHTML:-?}"
fi

# B12 后端只 expose 不 publish：宿主上不该有 18086。同一把尺在网内再探一次当对照，
#     否则"宿主连不上"和"服务根本没起"这两件事分不开。
P18086=$(python3 - <<'PY'
import socket
s = socket.socket(); s.settimeout(2)
try:
    s.connect(("127.0.0.1", 18086)); print("open")
except OSError:
    print("closed")
finally:
    s.close()
PY
)
INNET=$(docker exec z-schedule-frontend wget -qO- -T 10 "http://z-schedule-backend:18086/meta/actuator/health" 2>&1 | grep -c '"status":"UP"' || true)
if [ "$P18086" = "closed" ] && [ "${INNET:-0}" -ge 1 ]; then
  ok "B12 宿主 18086 closed 而网内同一条 URL 返回 UP（命中 $INNET 处）⇒ '仅 internal network 访问'这句是真的，不是服务没起"
else
  bad "B12 后端暴露形状不对：宿主 18086=$P18086、网内 UP=$INNET ⇒ 要么端口漏到宿主，要么网内根本不通"
fi

echo ""
echo "=== B13 Mode 2 收口 ==="
( cd "$DEPLOY" && $CCLI --env-file env/.env -f docker-compose.split.yml down > "$WORK/down2.log" 2>&1 )
DRC=$?
GONE=1; PORTBACK=busy
for i in $(seq 1 12); do
  GONE=$(docker ps -a --format '{{.Names}}' | grep -cE '^(z-schedule-backend|z-schedule-frontend)$' || true)
  PORTBACK=$(python3 - <<PY
import socket
s = socket.socket(); s.settimeout(2)
print("free" if s.connect_ex(("127.0.0.1", $HTTP_PORT)) else "busy"); s.close()
PY
)
  [ "${GONE:-1}" = "0" ] && [ "$PORTBACK" = "free" ] && break
  sleep 2
done
# rc 只作读数不作判据：Makefile 的 down 三条都挂着 `|| true`，恒 0，拿它当证据等于没证据。
if [ "${GONE:-1}" = "0" ] && [ "$PORTBACK" = "free" ]; then
  ok "B13 Mode 2 down 之后容器残留=0、发布口 $HTTP_PORT 已释放（down rc=$DRC 只是读数：Makefile 那条挂着 || true，恒 0）"
else
  tail -8 "$WORK/down2.log" | sed 's/^/    /'
  bad "B13 Mode 2 停不干净：残留=${GONE:-?} 端口=$PORTBACK down rc=$DRC"
fi

[ "${P26_SKIP_CLUSTER:-0}" = "1" ] && { echo ""; echo "总判（跳过 Mode 3）：PASS=$PASS FAIL=$FAIL"; [ "$FAIL" = "0" ] && exit 0 || exit 1; }

echo ""
echo "=== C1–C5 Mode 3（README 写的那条 --scale 路）==="
AVAIL_KB=$(awk '/MemAvailable/{print $2}' /proc/meminfo 2>/dev/null || echo 0)
REPLICAS=3
[ "${AVAIL_KB:-0}" -lt 4000000 ] && REPLICAS=2
echo "  MemAvailable=$((AVAIL_KB/1024)) MiB ⇒ 后端副本数取 $REPLICAS"
C3=$( cd "$DEPLOY" && bash bin/start-mode3.sh "$REPLICAS" 2>&1 ); C3RC=$?
echo "$C3" > "$WORK/up3.log"
NAMES=$(docker ps --format '{{.Names}}' | grep -E 'z-schedule-(backend|frontend)' | sort | tr '\n' ' ')
NBK=$(docker ps --format '{{.Names}}' | grep -c 'z-schedule-backend' || true)
NFRT=$(docker ps --format '{{.Names}}' | grep -c 'z-schedule-frontend' || true)
if [ "$C3RC" = "0" ] && [ "${NBK:-0}" = "$REPLICAS" ] && [ "${NFRT:-0}" = "1" ]; then
  ok "C1 'bash bin/start-mode3.sh $REPLICAS'（= make cluster N=$REPLICAS 的真身）rc=0 ⇒ $NBK 个后端 + $NFRT 个前端在跑：$NAMES"
else
  echo "$C3" | tail -10 | sed 's/^/    /'
  bad "C1 Mode 3 没起成：rc=$C3RC 后端=${NBK:-?}/$REPLICAS 前端=${NFRT:-?}（真实容器名：$NAMES）"
fi
HAPPY=0
for i in $(seq 1 20); do
  HAPPY=$(docker ps --format '{{.Names}} {{.Status}}' | grep 'z-schedule-' | grep -c 'healthy' || true)
  [ "${HAPPY:-0}" = "$((REPLICAS + 1))" ] && break
  sleep 6
done
[ "${HAPPY:-0}" = "$((REPLICAS + 1))" ] \
  && ok "C2 $HAPPY/$((REPLICAS + 1)) 个容器 healthy（多副本共用一个库、各自跑 healthcheck，这条不是'起了'而是'每条清单声明的探针都过了'）" \
  || { docker ps -a --format '{{.Names}} {{.Status}}' | grep 'z-schedule-' | sed 's/^/    /'; bad "C2 只有 ${HAPPY:-0}/$((REPLICAS + 1)) 个容器 healthy"; }

# C3 Leader 唯一：日志与库两边对账。日志只说明"我以为我是"，库里的租约行才是裁决面；
#    而库那一面单独也不够（表本来就只有一行，数行数恒等于 1）⇒ 判据是"打过 Became LEADER 的容器
#    只有一个，且租约行里的 host 就是它"。逐容器打印计数本身就是非空跑的证词（1/0/0，不是 0/0/0）。
LEADCOUNTS=""; LEADHOST=""
for n in $(docker ps --format '{{.Names}}' | grep 'z-schedule-backend' | sort); do
  c=$(docker logs "$n" 2>&1 | grep -c 'Became LEADER' || true)
  LEADCOUNTS="$LEADCOUNTS ${n##*-}=$c"
  [ "${c:-0}" -ge 1 ] && LEADHOST="$LEADHOST $(docker exec "$n" hostname 2>/dev/null)"
done
NLEAD=$(echo "$LEADCOUNTS" | tr ' ' '\n' | grep -c '=[1-9]' || true)
LEASE=$(mysql_root -e "SELECT CONCAT(IFNULL(host,'NULL'),'|',IF(owner IS NULL,'free','held')) FROM $DB26.z_schedule_job_leader WHERE id=1")
LBHOST=$(echo "${LEASE:-}" | cut -d'|' -f1)
LSTATE=$(echo "${LEASE:-}" | cut -d'|' -f2)
EXPTS=$(mysql_root -e "SELECT CONCAT('db_now=',NOW(),' lease_expire=',IFNULL(expire_time,'NULL')) FROM $DB26.z_schedule_job_leader WHERE id=1")
if [ "${NLEAD:-0}" = "1" ] && [ "$LSTATE" = "held" ] && echo " $LEADHOST " | grep -q " $LBHOST "; then
  ok "C3 $REPLICAS 个后端里只有一个 Leader（逐容器 'Became LEADER' 计数：$LEADCOUNTS ），且库里的租约行 host=$LBHOST 正是它 ⇒ 副本数不放大调度（$EXPTS ⇒ 租约判断用的是同一个时钟，跨时区漂移会在这里显形）"
else
  mysql_root -e "SELECT id, owner, host, expire_time FROM $DB26.z_schedule_job_leader" | sed 's/^/    /'
  bad "C3 Leader 不唯一或对不上账：逐容器计数=$LEADCOUNTS、打过日志的 host=[$LEADHOST ]、库里 host=$LBHOST 状态=$LSTATE（$EXPTS）"
fi

# C4 反代在服务仍被声明为"负载均衡到 N 个后端"之前，至少要能容纳死掉一个：
#    清单里那条验证步骤就是这么写的（原文还把容器名写成 z-schedule-backend-z-schedule-backend-1，
#    这个名字在这台机器上不存在——真实的带 project 前缀，见 C1 打印的 $NAMES，所以这里按实际名字停）。
VICTIM=$(docker ps --format '{{.Names}}' | grep 'z-schedule-backend' | head -1)
docker stop "$VICTIM" >/dev/null 2>&1
OKN=0
for i in 1 2 3 4 5; do
  code=$(curl -s -m 10 -o "$WORK/h_after" -w '%{http_code}' "$H/api/actuator/health")
  [ "$code" = "200" ] && grep -q '"status":"UP"' "$WORK/h_after" && { OKN=$i; break; }
  sleep 3
done
if [ "$OKN" -ge 1 ]; then
  ok "C4 停掉 $VICTIM 之后第 $OKN 次探测 /api/actuator/health 仍是 200 UP ⇒ 剩下 $((REPLICAS - 1)) 个后端接得住（这一臂只在 nginx 已解析出的 upstream 上成立：它启动时解析一次，之后扩副本不会被看见，见 README §13 的那条注意）"
else
  bad "C4 停一个后端后连续 5 次探不通 ⇒ 清单说的'nginx 自动跳过挂了的后端'没兑现"
fi
docker start "$VICTIM" >/dev/null 2>&1

# C5 副本解析：nginx 那句 proxy_pass 用的是服务名，Docker DNS 该给出 $REPLICAS 个地址。
#    这一臂钉的是"多个后端确实共用一个名字"，不钉轮询——响应体里没有任何实例身份，
#    拿 /api/actuator/health 数不出命中了谁（要数得先有身份，那是 #35 那一格的事）。
#    第一版这里连错两处，都是尺的错：① 容器名抄的是 Mode 2 的 z-schedule-frontend，而 Mode 3 的
#    前端叫 <project>-z-schedule-frontend-1（清单里没钉 container_name），探一个不存在的容器恒为 0；
#    ② 数地址用的是 `^Address: `，busybox nslookup 在这台机器上打的形状不是它。
#    ⇒ 现在从 docker ps 现取名字，数法退到"抽所有非回环 IPv4 去重"，并拿前端自己的服务名当对照
#    （那个必须恒为 1：一把尺若在两个名字上都数到同一个数，它数的就不是地址）。
F3=$(docker ps --format '{{.Names}}' | grep 'z-schedule-frontend' | head -1)
[ -n "$F3" ] || FATAL "C5 认不出 Mode 3 的前端容器（docker ps 里没有任何 z-schedule-frontend）"
ipcount() { docker exec "$1" nslookup "$2" 2>/dev/null \
            | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' | grep -v '^127\.' | sort -u | wc -l | tr -d ' '; }
HAVENS=$(docker exec "$F3" sh -c 'command -v nslookup >/dev/null && echo yes || echo no' 2>/dev/null)
RFB=$(ipcount "$F3" z-schedule-backend)
RFF=$(ipcount "$F3" z-schedule-frontend)
if [ "$HAVENS" = "yes" ] && [ "${RFB:-0}" -ge 2 ] && [ "${RFF:-1}" = "1" ]; then
  ok "C5 服务名 z-schedule-backend 在前端容器里解析出 $RFB 个地址（副本声明 $REPLICAS），而同一条尺数前端自己的名字得 $RFF 个 ⇒ 数的是地址不是噪声：反代那一跳面对的是一份真列表"
else
  bad "C5 读不通：容器=$F3 nslookup 存在=$HAVENS backend地址=${RFB:-?}（应 ≥2）frontend地址=${RFF:-?}（应 1，对照）"
fi

( cd "$DEPLOY" && $CCLI --env-file env/.env -f docker-compose.cluster.yml down >/dev/null 2>&1 )
# 残留只数本档起的那两个服务。第一版这里写的是 --filter 'name=z-schedule-'，它把共享机上
# 一个跑了 15 小时的 z-schedule-e2e-mysql 也算进来了——那既不是本档的容器、更不该被本档停掉。
LEFT=$(docker ps -aq --filter 'name=z-schedule-backend' --filter 'name=z-schedule-frontend' | wc -l | tr -d ' ')
OTHERS=$(docker ps --format '{{.Names}}' | grep -c 'z-schedule' || true)
if [ "${LEFT:-1}" = "0" ]; then
  ok "C6 Mode 3 down 之后本档的 backend/frontend 容器残留=0（盘上另有 $OTHERS 个带 z-schedule 字样的**别人的**容器，本档一条命令都没对它们下过）"
else
  docker ps -a --format '{{.Names}} {{.Status}}' | grep -E 'z-schedule-(backend|frontend)' | sed 's/^/    /'
  bad "C6 残留 $LEFT 个本档容器"
fi

echo ""
echo "=== Z2 附带伤害对账 ==="
PIDNOW=$(ss -ltnp 2>/dev/null | grep ':18098 ' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
if [ -z "${PID18098:-}" ]; then
  echo "  [SKIP] 开工时就没读到常驻服务 ⇒ 这一臂未测，不算通过"
elif [ "$PIDNOW" = "$PID18098" ]; then
  ok "Z2 常驻服务仍在一个都没换的 pid=$PIDNOW 上听 18098（彩排没碰它）"
else
  bad "Z2 常驻服务变了：开始 pid=$PID18098，现在 pid=${PIDNOW:-无} ⇒ 这一档伤到了不该伤的东西"
fi
# 本档自己的容器/卷/网络清没清干净，只能在 trap 里数（临时库到收口才消失），见 cleanup() 末尾的"收口对账"。

echo ""
echo "总判：PASS=$PASS FAIL=$FAIL  （Mode 2 分体 + Mode 3 集群；Mode 1 见 p25.sh，k8s 真集群 apply 不在这一档）"
if [ "$FAIL" = "0" ]; then
  echo "VERDICT: OK —— 前端镜像能建、SPA 在根上服务、/api 反代到后端根并读得到库里的行、N 副本只有一个 Leader（收口对账在下一行，非 0 会把这条翻成失败）"
else
  echo "VERDICT: NOT OK"
  exit 1
fi
