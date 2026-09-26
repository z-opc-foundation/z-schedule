#!/usr/bin/env bash
# p25.sh — Mode 1 入口彩排：README 写的 `make dev` / `make down` 这条路，第一次真的走一遍
#
# 为什么有这一档：p24 的臂 B 用的是 `docker run` + 渲染出来的 ConfigMap env——那是**清单**那条路。
# 而 deploy/README.md 让人敲的是 `make dev`，这台机器上从来没执行过。一跑就露出三条：
#   1) 入口与 Makefile 把命令行写死成 `docker compose`，而 250 只有 `docker-compose` 二进制
#      （Docker 20.10.21，没有 compose 插件）⇒ "一键"死在命令行本身，报的错还不是部署的错。
#   2) compose 文件里的 ${DB_HOST:?} 守卫会在**解析阶段**就拒 ⇒ 原先 `make ps` / `make down`
#      不带 --env-file，等于"照 README 起得来的容器，照 README 停不掉"。
#   3) 仓库根没有 .dockerignore ⇒ 构建上下文把 .git、各模块 target/、_frontend/node_modules
#      全打给 daemon（本机 ~265 MiB；250 这种"一个目录既放仓库又放 mysql-data"的演练机是 GiB 级），
#      而 Dockerfile 实际只 COPY 一个 jar 与一份 nginx 模板。
# 三条都是"跑一次就有读数"的，不需要猜。
#
# ⚠ 这一档只走 Mode 1。Mode 2/3 的前端是装饰（_frontend 那个桩里一次 API 调用都没有），
#   真集群 apply 也不是这里的事（见 p24 与 README §11.2）。
#
# 用法（在 250）：./p25.sh
#                 P25_STATIC_ONLY=1 ./p25.sh   只跑静态那半（不碰 docker）
#
# ⚠ 250 上没有 make（Ubuntu 18.04 最小装），所以"README 让人敲的那一层"在这台机器上跑不了：
#   运行时臂走 make dev 的真身 bin/start-mode1.sh，make 那一层由 P2c 在有 make 的机器上验。
#   两层的读数分开记，别把脚本层的绿引用成 make dev 的绿。
set -u
cd "$(dirname "$0")"

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $*"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $*"; }
FATAL(){ echo "FATAL: $*"; exit 1; }
W="$$"; WORK="$(pwd)/logs/p25_$W"; mkdir -p "$WORK"

DEPLOY=""
for c in deploy ../deploy ../../deploy ../../../deploy; do
  [ -d "$c/k8s" ] && { DEPLOY="$(cd "$c" && pwd)"; break; }
done
[ -n "$DEPLOY" ] || FATAL "找不到 deploy/ 目录（DEPLOY=... 手动指）"
E2E="${E2E:-$(pwd)}"
command -v python3 >/dev/null || FATAL "没有 python3"

echo "deploy = $DEPLOY"
echo "=== 静态：compose 命令行与构建上下文 ==="

# P1 三支入口 + Makefile 里不许有裸写的 `docker compose`（必须过解析函数/变量）。
#    两种形状都算裸写：行首调用 `docker compose …`，和变量赋值 `COMPOSE_x = docker compose`
#    （Makefile 那三条就是后一种，改前它把命令行钉死成新版插件，250 上没有那个插件）。
#    解析器自己那行 `if docker compose version …` 与注释不算命中——否则这条尺会把修复本身判成缺陷。
#    阳性对照：同一条尺先在人造的旧形状上命中，才许它报绿。
BARE='^[[:space:]]*docker compose[[:space:]]|^[[:space:]]*COMPOSE_[A-Z]+[[:space:]]*=[[:space:]]*docker compose'
printf 'docker compose $ENVFILE up -d\nCOMPOSE_BASE = docker compose\nCOMPOSE_SPLIT = docker compose -f x.yml\n' > "$WORK/prey1.sh"
PREYN=$(grep -cE "$BARE" "$WORK/prey1.sh")
if [ "${PREYN:-0}" = "3" ]; then
  PREY=$(grep -lE "$BARE" "$DEPLOY"/bin/*.sh 2>/dev/null | tr '\n' ' ')
  # Makefile：注释行不算（解析器旁边的说明里就会出现这个词）
  MKHIT=$(grep -vE '^[[:space:]]*#' "$DEPLOY/Makefile" | grep -cE "$BARE" || true)
  if [ -z "$PREY" ] && [ "${MKHIT:-1}" = "0" ]; then
    ok "P1 compose 命令行两支都解析（脚本无行首裸调用、Makefile 无钉死的赋值；尺在 $PREYN 行旧形状上命中过）"
  else
    bad "P1 仍有裸 docker compose：脚本[$PREY ] Makefile 命中=$MKHIT"
  fi
else
  FATAL "P1 尺自身坏了：人造的两种旧形状应各命中一次（实得 ${PREYN:-?}），后面别信"
fi

# P2 守卫会在**解析阶段**就拒 ⇒ ps/down/logs 这三条善后路必须也带 --env-file，
#    否则用户"照 README 起得来、照 README 停不掉"。
#    这条尺必须证明自己数的是"带没带 ENVARG"：先拿人造的旧形状（COMPOSE_BASE = docker compose）
#    验它数到 0，再拿真文件验它数到 3。
NBASE=$(grep -cE '^[[:space:]]*COMPOSE_(BASE|SPLIT|CLUSTER)[[:space:]]*=[[:space:]]*\$\(COMPOSE\)[[:space:]]+\$\(ENVARG\)' "$DEPLOY/Makefile")
HASARG=$(grep -cE '^ENVARG[[:space:]]*:=' "$DEPLOY/Makefile")
printf 'COMPOSE_BASE = docker compose\nCOMPOSE_SPLIT = docker compose -f x.yml\n' > "$WORK/prey2.mk"
PREY2=$(grep -cE '^[[:space:]]*COMPOSE_(BASE|SPLIT|CLUSTER)[[:space:]]*=[[:space:]]*\$\(COMPOSE\)[[:space:]]+\$\(ENVARG\)' "$WORK/prey2.mk")
if [ "${PREY2:-1}" = "0" ] && [ "${NBASE:-0}" = "3" ] && [ "${HASARG:-0}" = "1" ]; then
  ok "P2 ps/down/logs 三条善后路都带 --env-file（三个 COMPOSE_* 各 1 处、ENVARG 定义 1 处；旧形状对照数到 $PREY2 ⇒ 尺有牙）"
else
  bad "P2 善后路没配齐：COMPOSE_* 带 ENVARG=$NBASE/3，ENVARG 定义=$HASARG（假文件=$PREY2）"
fi

# P2c Makefile 那一层**展开成什么**：$(shell …) 的两个变量只有在 make 真解析时才落地。
#     250 上没装 make（见 P0），那里这一臂按"未测"记账，不许悄悄算通过。
if command -v make >/dev/null 2>&1; then
  # 只为让 ENVARG 非空——但**不许**覆盖用户已有的 env/.env（量具毁配置比不改更糟）：先备份再还原。
  ENVSAVED=""
  if [ -f "$DEPLOY/env/.env" ]; then ENVSAVED="$WORK/env.orig"; cp -p "$DEPLOY/env/.env" "$ENVSAVED"; fi
  printf 'DB_HOST=probe-only-for-make-n\n' > "$DEPLOY/env/.env"
  MKN=$( cd "$DEPLOY" && make -n dev 2>&1 )
  MKD=$( cd "$DEPLOY" && make -n down 2>&1 )
  if [ -n "$ENVSAVED" ]; then cp -p "$ENVSAVED" "$DEPLOY/env/.env"; else rm -f "$DEPLOY/env/.env"; fi
  CLI=$(echo "$MKN$MKD" | grep -oE 'docker-compose|[d]ocker compose' | head -1)
  ARG=$(echo "$MKD" | grep -c -- '--env-file env/.env')
  DEVSCRIPT=$(echo "$MKN" | grep -c 'bin/start-mode1.sh')
  if [ -n "$CLI" ] && [ "$ARG" -ge 1 ] && [ "$DEVSCRIPT" = "1" ]; then
    ok "P2c make 层落地：dev 展开成 '$CLI …'→bin/start-mode1.sh（1 处），down 带 --env-file（$ARG 处），解析出的 CLI=$CLI"
  else
    bad "P2c make 层没落地：dev 里脚本行=$DEVSCRIPT，down 里 --env-file 行=$ARG，CLI='${CLI:-无}'"
  fi
else
  echo "  [SKIP] P2c 这台机器没有 make ⇒ Makefile 那一层未测（不算通过），由有 make 的机器另跑"
fi

# P3 构建上下文：仓库根要有 .dockerignore，且必须排掉 .git / node_modules / .gnupg / mysql-data，
#    同时**不能**排掉 jar 所在的 z-schedule-admin/target（Dockerfile.backend 就 COPY 它）。
IGN="$(cd "$DEPLOY/.." && pwd)/.dockerignore"
if [ -f "$IGN" ]; then
  MISS=""
  for pat in '\.git' 'node_modules' '\.gnupg' 'mysql-data'; do
    grep -qE "$pat" "$IGN" || MISS="$MISS $pat"
  done
  # 阳性对照：把"什么都没排除"的假 .dockerignore 喂同一条尺，必须报缺四项
  : > "$WORK/prey3.ignore"
  MISSPREY=""
  for pat in '\.git' 'node_modules' '\.gnupg' 'mysql-data'; do
    grep -qE "$pat" "$WORK/prey3.ignore" || MISSPREY="$MISSPREY $pat"
  done
  EXCLJAR=$(grep -cE '^!?z-schedule-admin/target' "$IGN")
  if [ -z "$MISS" ] && [ -n "$MISSPREY" ] && [ "$EXCLJAR" = "0" ]; then
    ok "P3 根 .dockerignore 排掉 .git/node_modules/.gnupg/mysql-data（假文件对照报缺 4 项，尺有牙；且没排除 z-schedule-admin/target ⇒ jar 还 COPY 得到）"
  else
    bad "P3 .dockerignore 不合格：缺[$MISS ] 假文件缺[$MISSPREY ]（假文件必须有缺才说明尺有牙）jar相关行=$EXCLJAR"
  fi
else
  bad "P3 仓库根没有 $IGN ⇒ make dev 会把 .git/target/node_modules 全打给 daemon"
fi

# P2d 三种模式的 compose 守卫必须成套且形状一致。为什么钉形状而不是"有没有守卫"：
#     ${V:?} 拒"未设置"也拒"设为空"，${V?} 只拒"未设置"（这一条是在 compose v5.0.2 上实测的，不是抄文档）。
#     所以 DB_HOST/PORT/NAME/USER 用 :?（空 DB_PORT 让两个池连不上；空 DB_NAME 让引擎池落到
#     …:3306/?serverTimezone…，坏相见 p24 的 B12），而 DB_PASSWORD 只用 ?——显式留空的无口令库是合法形状，
#     整行没写才是漏配。这个不对称不能只写在注释里：blanket 上 :? 会把无口令库拒在解析阶段，
#     所以反方向也得有一支（PASSWORD 出现 :? 本身就是缺陷）。
guard_shape() {   # $1=compose 文件；有问题逐条打印，没问题不打印
  local f="$1" n m
  for v in DB_HOST DB_PORT DB_NAME DB_USER; do
    n=$(grep -oF "\${$v:?" "$f" | grep -c . || true)
    [ "${n:-0}" -ge 2 ] || echo "$v 缺 :? 守卫（$n/2 处：引擎池与 spring 池各一处）"
    n=$(grep -oF "\${$v}" "$f" | grep -c . || true)
    m=$(grep -oF "\${$v:-" "$f" | grep -c . || true)
    [ "$(( ${n:-0} + ${m:-0} ))" = "0" ] || echo "$v 还有静默默认/裸引用（默认 $m 处、裸 $n 处）"
  done
  n=$(grep -oF '${DB_PASSWORD?' "$f" | grep -c . || true)
  [ "${n:-0}" -ge 2 ] || echo "DB_PASSWORD 缺 ? 守卫（$n/2 处）"
  n=$(grep -oF '${DB_PASSWORD:?' "$f" | grep -c . || true)
  [ "${n:-0}" = "0" ] || echo "DB_PASSWORD 写成了 :?（$n 处）⇒ 无口令库会被拒在解析阶段"
}
# 阳性对照：一份种了三种病的假文件，必须精确点出 DB_PORT/DB_NAME/DB_PASSWORD 三项，
# 且不许把形状正确的 DB_USER/DB_HOST 也报出来（否则"三个真文件全绿"这句话没有牙）。
cat > "$WORK/prey2d.yml" <<'YML'
services:
  app:
    environment:
      A: "${DB_PORT:-3306}"
      B: "jdbc:mysql://h/${DB_NAME}"
      C: "${DB_PASSWORD:?不该这么写}"
      D: "${DB_USER:?整行没写才是漏配}"
      E: "${DB_USER:?整行没写才是漏配}"
      F: "${DB_HOST:?整行没写才是漏配}"
      G: "${DB_HOST:?整行没写才是漏配}"
YML
PREY2D=$(guard_shape "$WORK/prey2d.yml")
P2D_OK=1; P2D_MSG=""
for f in docker-compose.yml docker-compose.split.yml docker-compose.cluster.yml; do
  [ -f "$DEPLOY/$f" ] || { P2D_OK=0; P2D_MSG="$P2D_MSG ${f}(文件不在)"; continue; }
  H=$(guard_shape "$DEPLOY/$f")
  [ -n "$H" ] && { P2D_OK=0; P2D_MSG="$P2D_MSG [${f}: $(echo "$H" | tr '\n' ';')]"; }
done
NAME3=$(echo "$PREY2D" | grep -oE 'DB_[A-Z]+' | sort -u | tr '\n' ',')
WRONG=$(echo "$PREY2D" | grep -c 'DB_USER\|DB_HOST')
if [ "$NAME3" = "DB_NAME,DB_PASSWORD,DB_PORT," ] && [ "$WRONG" = "0" ] && [ "$P2D_OK" = "1" ]; then
  ok "P2d 三个 compose 的守卫成套（HOST/PORT/NAME/USER 各 2 处 :?、PASSWORD 2 处 ? 且零处 :?；同一把尺在种了 3 病的假文件上精确点出 [$NAME3]，没冤枉形状正确的 DB_USER/DB_HOST）"
else
  echo "    假文件读数：点到 [$NAME3] 项、误报 $WRONG 项 ⇒ $(echo "$PREY2D" | tr '\n' '|')"
  bad "P2d 守卫不成套：$P2D_MSG（假文件对照应精确点出 DB_NAME,DB_PASSWORD,DB_PORT，实得 [$NAME3]）"
fi

if [ "${P25_STATIC_ONLY:-0}" = "1" ]; then
  echo "P25_STATIC_ONLY=1：跳过运行时"
  echo "总判：PASS=$PASS FAIL=$FAIL"
  [ "$FAIL" = "0" ] || exit 1; exit 0
fi

command -v docker >/dev/null || FATAL "运行时臂要 docker"
CCLI="docker compose"; docker compose version >/dev/null 2>&1 || CCLI="docker-compose"
# README 的入口是 make，但这台机器可能根本没装（250 就没有）⇒ 没 make 时走 make dev 的真身
# （bin/start-mode1.sh）与 make down 展开后的那条命令，并把"make 层有没有被真跑"记成读数，
# 别把脚本层的绿说成 make dev 的绿。
if command -v make >/dev/null 2>&1; then
  run_start() { ( cd "$DEPLOY" && make dev 2>&1 ); }
  run_stop()  { ( cd "$DEPLOY" && make down 2>&1 ); }
  ENTRY_NAME="make dev / make down"
else
  run_start() { ( cd "$DEPLOY" && bash bin/start-mode1.sh 2>&1 ); }
  run_stop()  { ( cd "$DEPLOY" && $CCLI --env-file env/.env down 2>&1 ); }
  ENTRY_NAME="bin/start-mode1.sh + '$CCLI --env-file env/.env down'（这台没有 make）"
  echo "  [note] 没有 make ⇒ 运行时走 '$ENTRY_NAME'；make 那一层由 P2c 在有 make 的机器上另验"
fi
RUNJAR="${RUNJAR:-$E2E/z-schedule-admin-svc-7ea14eb-exec.jar}"
DDL="${DDL:-$E2E/z-schedule.sql}"
[ -f "$RUNJAR" ] || FATAL "构件不存在：RUNJAR=$RUNJAR"
[ -f "$DDL" ] || FATAL "建表脚本不存在：DDL=$DDL"
DBIMAGE="${DBIMAGE:-mysql:8.0.26}"
docker image inspect "$DBIMAGE" >/dev/null 2>&1 || FATAL "本机没有 $DBIMAGE 镜像 ⇒ 这一档自带临时库，不去拉公网"
DB25=zschedule_p25
DBC="p25db$W"; DBVOL="p25mysqldata$W"
IMG="ghcr.io/yuku123/z-schedule-admin:1.0.4"
# 库口令随机生成，只落进 0600 的临时文件：不进 argv（共享机上 ps 看得见）、不进输出。
# 这一档不碰那台共享的 e2e 库，也不碰常驻服务在用的库。
DBPW=$(python3 -c "import secrets,string;print(''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range(18)))")
MYENV="$WORK/mypwd"; printf 'MYSQL_PWD=%s\n' "$DBPW" > "$MYENV"; chmod 600 "$MYENV"
mysql_root() { docker exec -i --env-file "$MYENV" "$DBC" mysql -uroot --skip-column-names -B "$@" 2>/dev/null; }

cleanup() {
  run_stop > "$WORK/stop.log" 2>&1 || true
  docker rm -f "$DBC" >/dev/null 2>&1
  docker volume rm "$DBVOL" >/dev/null 2>&1
  docker network rm "p25other$W" >/dev/null 2>&1
  docker rmi "$IMG" >/dev/null 2>&1
  if [ "${KEEP_ENV:-0}" = "1" ]; then :
  elif [ -f "$WORK/env.p8bak" ]; then cp -p "$WORK/env.p8bak" "$DEPLOY/env/.env"
  else rm -f "$DEPLOY/env/.env"
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

echo ""
echo "=== 运行时前置（端口与建表脚本）==="

# P4 宿主 18086 必须空着（Mode 1 把它钉成发布端口）；常驻那台在 18098，记 pid 备查
PID18098=$(ss -ltnp 2>/dev/null | grep ':18098 ' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
FREE18086=$(python3 - <<'PYBIND'
import socket
s = socket.socket()
try:
    s.bind(("0.0.0.0", 18086)); print("yes")
except OSError:
    print("no")
finally:
    s.close()
PYBIND
)
if [ "$FREE18086" = "yes" ]; then
  ok "P4 宿主 18086 空闲（常驻服务在 18098，pid=${PID18098:-?}，这一档不碰它）"
else
  FATAL "18086 已被占（Mode 1 的发布端口）⇒ 别动别人的端口，这一档不跑了"
fi

# P6 DDL 无 DROP。尺要同时满足两端：
#   a) 注释行不许计入——那份脚本第 7 行的注释自己写着"不含任何 DROP"，含注释的 grep 会把自己判死；
#   b) **行首**的 DROP 必须计入——DDL 里 DROP 只会以 `DROP TABLE …` 出现在行首，
#      而 p24 那一版数的是 `[[:space:]]drop[[:space:]]`（要求词前有空格），对行首形状结构性失明，
#      且它没有阳性对照 ⇒ 那条"零 DROP"从前是空跑。这一档的第一遍就是被自己的猎物抓出来的（FATAL）。
DROPPAT='(^|[^[:alnum:]_])drop[[:space:]]'
count_drop() { grep -vE '^[[:space:]]*--' "$1" | grep -ciE "$DROPPAT" || true; }
printf 'DROP DATABASE IF EXISTS zschedule;\nDROP TABLE whatever;\n-- 注释里也写一个 DROP TABLE 试尺\nx INT NULL; DROP TABLE inline_after;\n' > "$WORK/prey6.sql"
NP=$(count_drop "$WORK/prey6.sql")
NPRAW=$(grep -ciE "$DROPPAT" "$WORK/prey6.sql" || true)          # 含注释的那一把（应当比 NP 多 1）
NOLD=$(grep -vE '^[[:space:]]*--' "$WORK/prey6.sql" | grep -ciE '[[:space:]]drop[[:space:]]' || true)  # p24 旧尺
NDROP=$(count_drop "$DDL")
if [ "${NP:-0}" = "3" ] && [ "${NPRAW:-0}" = "4" ] && [ "${NDROP:-0}" = "0" ]; then
  ok "P6 DDL 非注释行零 DROP（同一把尺在两种形状的猎物上精确数到 3：2 处行首 + 1 处行中；不剥注释数到 $NPRAW ⇒ 剥注释那半也有牙。p24 旧尺 '[[:space:]]drop[[:space:]]' 在同一猎物上只数到 $NOLD ⇒ 那一条从前是空跑）"
else
  FATAL "P6 判据不可信（猎物应=3（含注释 4），实得 ${NP:-?}/${NPRAW:-?}；真文件数到 ${NDROP:-?}）"
fi
echo "=== 照 README 的入口敲：$ENTRY_NAME ==="

# P7 负向：没有 env/.env 时，整条链（make → start-mode1.sh → compose）必须非 0、消息必须点名某个 DB_* 键，
#    并且**不许留下半截容器**（这才是"不会起一个永不 Ready 的容器"那句话的兑现面）。
#    这一步在**写 env 之前**做，所以它同时也是"配置文件还没造出来"这个前提的证据。
#    09-27 run8 这一臂红过一次，红得对但病不在仓库：compose 的插值按 Go map 顺序走，
#    同一条命令两遍分别点名 DB_NAME 与 DB_HOST（当场各跑一次取证）⇒ "只认 DB_HOST"的判据会随运气翻判。
#    逐变量的覆盖归 P18（三个文件 × 五个键双向），这一臂只管整条链拒不拒、留没留容器。
rm -f "$DEPLOY/env/.env"
NOUT=$(run_start); NRC=$?
echo "$NOUT" > "$WORK/nodev_neg.log"
NVAR=$(echo "$NOUT" | grep -oE 'required variable DB_[A-Z_]+ is missing' | head -1)
LEFT=$(docker ps -a --format '{{.Names}}' | grep -c '^z-schedule-admin$' || true)
if [ "$NRC" != "0" ] && [ -n "$NVAR" ] && [ "${LEFT:-1}" = "0" ]; then
  ok "P7 无 env 时入口（$ENTRY_NAME）rc=$NRC、消息 '$NVAR'、残留容器=0 ⇒ 守卫在整条链上有效且拒在解析阶段（先被点名的键由 compose 的 map 顺序决定，这一遍是 ${NVAR#required variable }；逐变量覆盖见 P18）"
else
  tail -4 "$WORK/nodev_neg.log" | sed 's/^/    /'
  bad "P7 无 env 的入口（$ENTRY_NAME）没按形状拒：rc=$NRC 点名='${NVAR:-无}' 残留容器=${LEFT:-?} ⇒ 要么静默起了个连不上库的容器，要么这一臂没跑到点"
fi

# P8 写 env（口令只在文件里，不进 argv、不进输出）。用户已有 env/.env 先备份，收口还原。
if [ -f "$DEPLOY/env/.env" ]; then cp -p "$DEPLOY/env/.env" "$WORK/env.p8bak"; fi
python3 - "$DBPW" "$DB25" > "$DEPLOY/env/.env" <<'PYENV'
import sys
print("DB_HOST=db")                      # 网络别名：库容器挂在 compose 那张网上（见 P8c/P8d）
print("DB_PORT=3306")
print("DB_NAME=%s" % sys.argv[2])
print("DB_USER=root")
print("DB_PASSWORD=%s" % sys.argv[1])
print("DB_POOL_MAX_ACTIVE=20")
print("OCI_REGISTRY=ghcr.io/yuku123")
print("IMAGE_VERSION=1.0.4")
print("JAR_FILE=z-schedule-admin/target/p25-exec.jar")
print("JAVA_OPTS=-Xms256m -Xmx512m")
PYENV
chmod 600 "$DEPLOY/env/.env"
KCOUNT=$(grep -cE '^[A-Z]' "$DEPLOY/env/.env")
[ "$KCOUNT" -ge 9 ] && ok "P8 写好 $KCOUNT 个键的 env/.env（0600，口令不回显；.gitignore 第 52 行的 .env 规则覆盖它）" \
                    || bad "P8 env/.env 键数=$KCOUNT，不像能撑起 Mode 1"
git -C "$DEPLOY/.." check-ignore -q env/.env 2>/dev/null && ok "P8b env/.env 确实被 gitignore 覆盖（不会被误提交）" \
  || echo "    [note] 这台机器上 deploy/.. 不是 git 仓库 ⇒ 误提交这一项由本机 .gitignore 规则另证"

# P8c 构件先就位。env/.env 里的 JAR_FILE 指的是 z-schedule-admin/target/p25-exec.jar，而 compose
#     v5 的 create 就会触发 build（镜像不在本地时）⇒ COPY 的是这一步放进去的那个文件。
#     第一遍把 cp 放在 P9 之前一行，create 就死在 "COPY failed: file not found in build context"，
#     而它后面那句 `echo rc=$?` 因为接了管道，读到的还是 tail 的 0（同一个坑，见 P6 的教训）。
mkdir -p "$E2E/z-schedule-admin/target" 2>/dev/null
cp "$RUNJAR" "$E2E/z-schedule-admin/target/p25-exec.jar" 2>/dev/null || FATAL "放不进构件（$E2E/z-schedule-admin/target）"
JAROK=$(python3 -c "import os;p='$E2E/z-schedule-admin/target/p25-exec.jar';print(os.path.getsize(p) if os.path.isfile(p) else 0)")
[ "${JAROK:-0}" -gt 1000000 ] && ok "P8c 构件就位：$E2E/z-schedule-admin/target/p25-exec.jar（$JAROK 字节）" \
                             || FATAL "构件只有 ${JAROK:-0} 字节 ⇒ 不是可运行的 exec jar，别继续"

# P8d 先只**建容器不启动**（compose create），为的是拿到它会给容器挂上的那张自定义网络的名字——
#     库必须挂在同一张网上，Mode 1 才可能连到它（跨网形状由 P8f 现测）。
( cd "$DEPLOY" && $CCLI --env-file env/.env create > "$WORK/create.log" 2>&1 ) \
  || { tail -6 "$WORK/create.log" | sed 's/^/    /'; FATAL "compose create 失败（CCLI=$CCLI，详见 create.log）"; }
NET=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' z-schedule-admin | awk '{print $1}')
[ -n "$NET" ] || FATAL "读不出 z-schedule-admin 会挂的网络"
ok "P8d compose（$CCLI）create 出的网络叫 $NET；DB_HOST=db 要靠这张网的内嵌 DNS 解析"

# P8e 临时库：库名与建表脚本交给 mysql 官方入口点的 initdb（脚本只读挂载，不改宿主端口绑定）
docker volume create "$DBVOL" >/dev/null 2>&1 || FATAL "建不出卷 $DBVOL"
docker run -d --name "$DBC" --network "$NET" --network-alias db --volume "$DBVOL:/var/lib/mysql" \
    -e MYSQL_ROOT_PASSWORD="$DBPW" -e MYSQL_DATABASE="$DB25" \
    -v "$DDL:/docker-entrypoint-initdb.d/60-z-schedule.sql:ro" "$DBIMAGE" >/dev/null 2>&1 \
  || FATAL "临时库容器起不来"
ALIVE=""
TABLES=0
# 轮询钉的是"表建出来了"，不是"mysqladmin ping 通了"：官方入口点在 initdb 期间会起一个
# 临时实例（同一 socket、跳过授权），那时 ping 已经通、而 $DB25 还是空的 ⇒ 拿 ping 当就绪
# 会让下一次读数变成随运气翻的假红。
for i in $(seq 1 40); do
  TABLES=$(mysql_root -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB25'" | tr -d '[:space:]')
  [ "${TABLES:-0}" -ge 6 ] && { ALIVE=yes; break; }
  sleep 3
done
[ "$ALIVE" = "yes" ] || { docker logs --tail 15 "$DBC" 2>&1 | sed 's/^/    /'; FATAL "临时库 120 s 内没建出 $DB25 的表（最后读数 tables=${TABLES:-?}）"; }
[ "${TABLES:-0}" -ge 6 ] && ok "P8e 临时库就绪，initdb 在 $DB25 里建出 $TABLES 张表（就绪判据＝information_schema 里的表数，不看日志也不拿 ping 当就绪）" \
                         || FATAL "initdb 只建出 ${TABLES:-0} 张表 ⇒ 建表脚本没被执行，后面全是假绿"
mysql_root -e "INSERT INTO $DB25.z_schedule_job_group (id, app_name, title, order_num, address_type) VALUES (1,'default','p25 rehearsal',0,0)" >/dev/null 2>&1

# P8f 通路形状：这条既决定彩排怎么搭，也决定 README 该怎么写。跨自定义网络在这台 docker 上是被
#     **过滤**（超时）而不是拒绝 ⇒ 拿默认 bridge 上某个容器的裸 IP 当 DB_HOST，得到的就是
#     p24/B12 那种"端口不 bind、不退出、日志无限刷"的容器。两个方向都现测。
DBIP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' "$DBC" | awk '{print $1}')
SAME=$(docker run --rm --network "$NET" --env-file "$MYENV" --entrypoint mysqladmin "$DBIMAGE" \
        --connect-timeout=8 -h "$DBIP" -uroot ping 2>&1 | grep -c "is alive" || true)
docker network create "p25other$W" >/dev/null 2>&1
OTHER=$(docker run --rm --network "p25other$W" --env-file "$MYENV" --entrypoint mysqladmin "$DBIMAGE" \
         --connect-timeout=8 -h "$DBIP" -uroot ping 2>&1 | tail -1)
docker network rm "p25other$W" >/dev/null 2>&1
if [ "${SAME:-0}" -ge 1 ]; then
  ok "P8f 同网（$NET）里容器视角连得上库；另一张网上读到的是 '$OTHER' ⇒ 库与 app 必须同网，DB_HOST 用别名不用裸 IP"
else
  FATAL "同网都连不上（$NET / $DBIP）⇒ 这一档的通路假设不成立，别继续"
fi

# P9 正向：README 那条入口必须 0 退出；同时把 daemon 收到的构建上下文字节数取出来（P10 用）。
#     两种打印形状都收：BuildKit 是 "transferring context: 155.99MiB"，而 250 上是 compose v5 走
#     legacy builder，打的是 "Sending build context to Docker daemon  827MB"（第二遍实测的）。
BUILD=$(run_start); BRC=$?
echo "$BUILD" > "$WORK/dev.log"
# 两种打印形状都收：BuildKit 是 "transferring context: 155.99MiB"，250 上是 compose v5 走 legacy
# builder，打 "Sending build context to Docker daemon  827MB"（数字与单位之间没有空格）。
# 两条 grep 不能共用同一个 stdin：第一条会读到 EOF，第二条拿到空输入 ⇒ legacy 那一支结构上永远不命中
# （第一版就是这么把 build 输出里明明存在的计数行读成"读不到"的）。所以先把输入收进变量，各喂各的。
ctx_line() {
  local IN; IN=$(cat)
  { printf '%s\n' "$IN" | grep -oE 'transferring context: *[0-9.]+ ?(KiB|MiB|GiB|kB|MB|GB)'
    printf '%s\n' "$IN" | grep -oE 'Sending build context to Docker daemon +[0-9.]+ ?(kB|MB|GB|KiB|MiB|GiB)'
  } | tail -1
}
ctx_mib() { python3 -c 'import sys,re
m=re.search(r"([0-9.]+) ?(kB|MB|GB|KiB|MiB|GiB)", sys.stdin.read())
if not m: print(""); raise SystemExit
v=float(m.group(1)); u=m.group(2)
print(round(v/1024 if u in ("kB","KiB") else (v if u in ("MB","MiB") else v*1024), 1))'
}
CTXLINE=$(ctx_line <<<"$BUILD")
CTXMIB=$(ctx_mib <<<"$CTXLINE")
if [ "$BRC" = "0" ]; then
  ok "P9 入口 $ENTRY_NAME rc=0（README 那条命令第一次真跑通）"
else
  tail -12 "$WORK/dev.log" | sed 's/^/    /'
  bad "P9 入口 $ENTRY_NAME rc=$BRC ⇒ 这一档的主张不成立"
fi

# P10 上下文体积：.dockerignore 的作用面在这里可量化（daemon 自己数出来的字节数）。
#     判据钉"上下文 < 300 MiB"——真实仓库 jar 就有 50 MiB 级构件，再加 .git/node_modules 会翻上去。
#     取数不能只从 P9 的输出里找：compose 只在**真的构建**时才打那行，而 P8d 的 create 已经把镜像
#     建好了 ⇒ 这一遍的 up 根本不构建，第一遍就是这么读不到数、判成红（量具的错，不是仓库的错）。
#     所以取数顺序：这一遍 up 的输出 → create 的输出 → 都没有就显式 build 一次再读。
CTXSRC="up"
[ -n "${CTXMIB:-}" ] || { CTXLINE=$(ctx_line < "$WORK/create.log"); CTXMIB=$(ctx_mib <<<"$CTXLINE"); CTXSRC="create"; }
if [ -z "${CTXMIB:-}" ]; then
  BTXT=$( cd "$DEPLOY" && $CCLI --env-file env/.env build 2>&1 )
  CTXLINE=$(ctx_line <<<"$BTXT"); CTXMIB=$(ctx_mib <<<"$CTXLINE"); CTXSRC="显式 build（前两处都没打计数行）"
fi
if [ -n "${CTXMIB:-}" ] && python3 -c "import sys; sys.exit(0 if float('$CTXMIB') < 300 else 1)"; then
  ok "P10 构建上下文 ${CTXMIB} MiB < 300 MiB（计数行取自 $CTXSRC；改前那一份同一台机器实测 827 MiB，取证见 README §12）"
else
  bad "P10 构建上下文 ${CTXMIB:-读不到} MiB（取自 $CTXSRC）⇒ .dockerignore 没起作用，或者根本没读到 daemon 的计数行"
fi

# P11 容器与镜像身份：认字节不认名字
CID=$(docker ps --format '{{.ID}} {{.Names}}' | awk '$2=="z-schedule-admin"{print $1}')
[ -n "$CID" ] || { docker ps -a --format '{{.Names}} {{.Status}}' | sed 's/^/    /'; FATAL "z-schedule-admin 容器不在跑"; }
IMGSHA=$(docker exec "$CID" sha256sum /app/app.jar 2>/dev/null | cut -c1-16)
HOSTSHA=$(sha256sum "$E2E/z-schedule-admin/target/p25-exec.jar" | cut -c1-16)
[ "$IMGSHA" = "$HOSTSHA" ] && ok "P11 容器里 /app/app.jar 的 sha256 与宿主构件一致（$HOSTSHA…）" \
                           || bad "P11 镜像里的 jar 与宿主构件不是一份东西（容器 $IMGSHA vs 宿主 $HOSTSHA）"
SVCCR=$(docker exec "$CID" sh -c 'command -v wget >/dev/null && echo yes || echo no')
[ "$SVCCR" = "yes" ] && ok "P11b 镜像里有 wget ⇒ compose 与 Dockerfile 那两条 healthcheck 用的是个存在的命令" \
                     || bad "P11b 镜像里没有 wget ⇒ 两条 healthcheck 永远红，容器看着'起不来'"

# P12 compose 自己的 healthcheck 第一次被执行：必须变 healthy（不是"端口通了"，是那条 wget 探的）
HSTAT=""
for i in $(seq 1 30); do
  HSTAT=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CID")
  [ "$HSTAT" = "healthy" ] && break
  sleep 4
done
NCHECK=$(docker inspect -f '{{if .State.Health}}{{len .State.Health.Log}}{{else}}0{{end}}' "$CID")
if [ "$HSTAT" = "healthy" ]; then
  ok "P12 compose 声明的 healthcheck 真跑起来了：状态 healthy，已执行 $NCHECK 次探测（这条 healthcheck 是 p24 的 docker run 路不会执行的）"
else
  bad "P12 healthcheck 状态=$HSTAT（探了 $NCHECK 次）⇒ README 说的'等健康检查通过'永远到不了"
fi

# P13 走宿主发布端口（不是 --network host）：/meta 前缀 + 内嵌前端 + 探针
for p in "/meta/actuator/health" "/meta/jobgroup/list"; do
  RC=$(curl -s -m 20 -o /dev/null -w "%{http_code}" "http://127.0.0.1:18086$p")
  [ "$RC" = "200" ] && ok "P13 宿主 18086 上 $p 返回 200（走 compose 的端口映射）" \
                    || bad "P13 宿主 18086 上 $p 返回 $RC"
done
HTML=$(curl -s -m 20 "http://127.0.0.1:18086/meta/")
HASAPP=$(echo "$HTML" | grep -c 'id="app"\|<script' || true)
[ "${HASAPP:-0}" -ge 1 ] && ok "P13b Mode 1 广告的能力兑现了：/meta/ 回的是内嵌前端页面（HTML 里 $HASAPP 行带 app/script 标记）" \
                        || bad "P13b /meta/ 没回前端页面 ⇒ '后端 jar 内嵌前端'这句是装饰，Mode 1 得改文档"

# P14 两个池都落在 p25 这个库：回显 P6 插进去的分组行
GRP=$(curl -s -m 20 "http://127.0.0.1:18086/meta/jobgroup/list" | grep -c 'p25 rehearsal')
DBGRP=$(mysql_root -e "SELECT COUNT(*) FROM $DB25.z_schedule_job_group WHERE title='p25 rehearsal'")
if [ "${GRP:-0}" -ge 1 ] && [ "${DBGRP:-0}" = "1" ]; then
  ok "P14 列表接口回显了库里那一行（title 命中 $GRP 处 / 库里 $DBGRP 行）⇒ 引擎池连的就是 $DB25"
else
  bad "P14 对不上：接口命中 ${GRP:-0} 处，库里 $DBGRP 行"
fi

# P15 建一个 FIX_RATE 任务、启用、数一次真实执行（Mode 1 起的东西到底能不能干活）
#     请求体照 p24/B10 那份**跑通过**的形状来（triggerType / fixInterval 才是这个 DTO 的键）。
#     第一遍这里用的是 scheduleType / scheduleConf ⇒ add() 没建成任务、content 是空的，
#     读数表现为"库里查不到那一行 + 100 s 无结论"，看着像产品不干活。所以这一档现在把
#     HTTP 状态码与响应原文一起打出来：建不出来时读数要说得出是几号响应，不能只说"没有行"。
ADD=$(curl -s -m 25 -w '\n%{http_code}' -H 'Content-Type: application/json' -X POST \
      "http://127.0.0.1:18086/meta/jobinfo/add" \
      -d '{"jobGroup":1,"jobDesc":"p25 mode1","author":"p25","executorRouteStrategy":"ROUND","executorBlockStrategy":"SERIAL_EXECUTION","executorHandler":"demoHandler","executorParam":"p25","triggerType":"FIX_RATE","fixInterval":2000,"misfireStrategy":"DO_NOTHING"}')
ACODE=$(echo "$ADD" | tail -1)
ABODY=$(echo "$ADD" | sed '$d' | tr -d '\n' | cut -c1-160)
JOB=$(echo "$ABODY" | sed -n 's/.*"content":"\{0,1\}\([0-9]\{1,\}\).*/\1/p' | head -1)
[ -n "$JOB" ] && ok "P15 HTTP $ACODE 建出 FIX_RATE 任务 jobId=$JOB（响应原文：$ABODY）" \
             || bad "P15 建任务失败：HTTP $ACODE 响应=$ABODY"
[ -n "$JOB" ] || FATAL "P15 没建出任务，P15b 无从执行"
ST0=$(mysql_root -e "SELECT trigger_status FROM $DB25.z_schedule_job_info WHERE id=$JOB")
[ "${ST0:-x}" = "0" ] && ok "P15 建出来即停用（库里 trigger_status=$ST0，与 add() 强制置 0 一致）" \
                     || bad "P15 建出来的任务 trigger_status=$ST0（预期 0）⇒ 这条产品语义变了，别按旧形状判"
BASE=$(docker logs "$CID" 2>&1 | wc -l)   # 只有这一行之后的日志属于"启用之后"
START=$(curl -s -m 25 -X POST "http://127.0.0.1:18086/meta/jobinfo/start?id=$JOB" | tr -d '\n ' | cut -c1-60)
echo "$START" | grep -q '"code":200' \
  && ok "P15c HTTP start 被接受（$START）" \
  || bad "P15c start 失败：$START"
STS=$(mysql_root -e "SELECT trigger_status FROM $DB25.z_schedule_job_info WHERE id=$JOB")
[ "${STS:-x}" = "1" ] && ok "P15d 库里 trigger_status 已置 1（引擎的装载条件满足了）" \
                     || bad "P15d start 之后 trigger_status=$STS，装载条件仍不满足"
HIT=0
for i in $(seq 1 25); do
  N=$(mysql_root -e "SELECT COUNT(*) FROM $DB25.z_schedule_job_log WHERE handle_code=200")
  [ "${N:-0}" -ge 1 ] && { HIT=1; break; }
  sleep 4
done
RING=$(docker logs "$CID" 2>&1 | tail -n +$((BASE + 1)) | grep -ac "已挂入时间轮")
LOGHITS=$(docker logs "$CID" 2>&1 | tail -n +$((BASE + 1)) | grep -ac "demoHandler 执行 jobId")
if [ "$HIT" = "1" ]; then
  ok "P15b 一次真实执行落库：handle_code=200 行=$(mysql_root -e "SELECT COUNT(*) FROM $DB25.z_schedule_job_log WHERE handle_code=200")（装载日志 $RING 行、handler 侧证 $LOGHITS 行，均取第 $BASE 行之后）"
else
  bad "P15b 等了 100 s 仍没有 handle_code=200 的行 ⇒ Mode 1 起的东西不干活（装载日志 $RING 行）"
fi

# P16 文档里的停止命令必须能用（这是 P2 那条修复的兑现面：带 --env-file 的 down）。
#     第一遍这一臂红过（rc=1 残留容器=1 端口=busy），而 $WORK 已被 trap 删掉 ⇒ 没有证据可看，
#     只有猜测。现在把 down 的输出留在日志里，并且端口/容器两项各给 20 s 落定时间再判。
DOWN=$(run_stop); DRC=$?
echo "$DOWN" > "$WORK/p16_down.log"
GONE=1; PORTBACK=busy
for i in $(seq 1 10); do
  GONE=$(docker ps -a --format '{{.Names}}' | grep -c '^z-schedule-admin$' || true)
  PORTBACK=$(python3 - <<'PY'
import socket
s = socket.socket()
try:
    s.bind(("0.0.0.0", 18086)); print("free")
except OSError:
    print("busy")
finally:
    s.close()
PY
)
  [ "${GONE:-1}" = "0" ] && [ "$PORTBACK" = "free" ] && break
  sleep 2
done
if [ "$DRC" = "0" ] && [ "${GONE:-1}" = "0" ] && [ "$PORTBACK" = "free" ]; then
  ok "P16 停止（$ENTRY_NAME 的 stop 侧）rc=0 ⇒ 容器没了、18086 释放（起与停用的是同一套 CLI 解析）"
else
  echo "$DOWN" | tail -8 | sed 's/^/    /'
  bad "P16 停止之后 rc=$DRC 残留容器=$GONE 端口=$PORTBACK ⇒ 停不掉，或停的路径与起的不一套（down 的原文已原样贴在上面：工作目录收口会被 trap 删掉，证据只能留在这份日志里）"
fi

# P17  collateral：常驻那台不该被这一档碰到
PIDNOW=$(ss -ltnp 2>/dev/null | grep ':18098 ' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
if [ -n "${PID18098:-}" ] && [ "$PIDNOW" = "$PID18098" ]; then
  ok "P17 常驻服务仍在一个都没换的 pid=$PIDNOW 上听 18098（彩排没碰它）"
else
  bad "P17 常驻服务变了：开始 pid=${PID18098:-?}，现在 pid=${PIDNOW:-无} ⇒ 这一档伤到了不该伤的东西"
fi

# P18 守卫在**真 compose 解析器**上逐变量双向走一遍（三种模式各一遍），P2d 只证了字面形状。
#     这一臂用的是假坐标（dbhost.p18 / dbn.p18 / p18-not-a-secret）：既不碰 P8 写的真 env，
#     渲染输出也允许原样打印——真口令绝不进这一臂。
#     修复前这台机器上的实测（09-27 03:1x）：删 DB_HOST 才 rc=1，删 DB_PORT / DB_NAME / DB_USER /
#     DB_PASSWORD **全 rc=0** ⇒ "五种键都有守卫"这句话从前只对五个里的一个成立。
#     所以这一臂带一支"改前形状"的猎物（只有 DB_HOST 带 :?、DB_PORT 退回 :-3306）：
#     删掉 DB_PORT 时它必须 rc=0，尺才证明它认得这种病，而不是只会跟着"rc=0"点头。
P18ENV="$WORK/p18.env"; P18TMP="$WORK/p18tmp.env"
printf 'DB_HOST=dbhost.p18\nDB_PORT=33060\nDB_NAME=dbn.p18\nDB_USER=dbu.p18\nDB_PASSWORD=p18-not-a-secret\nDB_POOL_MAX_ACTIVE=40\n' > "$P18ENV"
chmod 600 "$P18ENV"
cat > "$WORK/p18_prefix.yml" <<'YML'
services:
  app:
    image: busybox
    environment:
      Z_BASE_DB_SCHEDULE_HOST: "${DB_HOST:?缺 DB_HOST}"
      Z_BASE_DB_SCHEDULE_PORT: "${DB_PORT:-3306}"
YML
grep -v '^DB_PORT=' "$P18ENV" > "$P18TMP"
( cd "$DEPLOY" && $CCLI --env-file "$P18TMP" -f "$WORK/p18_prefix.yml" config -q >/dev/null 2>&1 )
P18TEETH=$?

P18BAD=""
for f in docker-compose.yml docker-compose.split.yml docker-compose.cluster.yml; do
  ( cd "$DEPLOY" && $CCLI --env-file "$P18ENV" -f "$f" config -q >/dev/null 2>&1 ) \
    || P18BAD="$P18BAD ${f}[齐 env 却 rc≠0]"
  for v in DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD; do
    grep -v "^$v=" "$P18ENV" > "$P18TMP"
    OUT=$( cd "$DEPLOY" && $CCLI --env-file "$P18TMP" -f "$f" config 2>&1 ); rc=$?
    if [ "$rc" = "0" ]; then P18BAD="$P18BAD ${f}[${v} 删了却 rc=0]"
    elif ! echo "$OUT" | grep -q "required variable $v is missing"; then P18BAD="$P18BAD ${f}[${v} rc=$rc 但消息没点名它]"
    fi
  done
  # 空值这一支只管 :? 的那四个键：空 DB_PORT 会让两个池都连不上，空 DB_NAME 让引擎池落到 …:3306/?serverTimezone…
  for v in DB_HOST DB_PORT DB_NAME DB_USER; do
    sed "s|^$v=.*|$v=|" "$P18ENV" > "$P18TMP"
    ( cd "$DEPLOY" && $CCLI --env-file "$P18TMP" -f "$f" config -q >/dev/null 2>&1 ) \
      && P18BAD="$P18BAD ${f}[${v}= 空值却过了 ⇒ :? 没管空值]"
  done
  # 反方向：显式留空的无口令库是合法形状，被拒就是过定
  sed "s|^DB_PASSWORD=.*|DB_PASSWORD=|" "$P18ENV" > "$P18TMP"
  ( cd "$DEPLOY" && $CCLI --env-file "$P18TMP" -f "$f" config -q >/dev/null 2>&1 ) \
    || P18BAD="$P18BAD ${f}[空口令被拒 ⇒ ? 写成了 :?]"
done
# 正向对照：齐 env 时**两个池**必须落在同一组坐标上（这是 §11.1 第 2 条那个"只喂一个池"的缺陷的反面）
REND=$( cd "$DEPLOY" && $CCLI --env-file "$P18ENV" -f docker-compose.yml config 2>&1 )
ZBH=$(echo "$REND" | grep -c 'Z_BASE_DB_SCHEDULE_HOST: dbhost.p18')
ZBD=$(echo "$REND" | grep -c 'Z_BASE_DB_SCHEDULE_DATABASE: dbn.p18')
SPU=$(echo "$REND" | grep -c 'jdbc:mysql://dbhost.p18:33060/dbn.p18')
PWW=$(echo "$REND" | grep -c 'p18-not-a-secret')
if [ "$P18TEETH" = "0" ] && [ -z "$P18BAD" ] && [ "${ZBH:-0}" -ge 1 ] && [ "${ZBD:-0}" -ge 1 ] \
   && [ "${SPU:-0}" -ge 1 ] && [ "${PWW:-0}" -ge 2 ]; then
  ok "P18 守卫逐变量双向成套（三个文件 × 5 个删除 + 4 个空值 + 1 个空口令合法形状全按预期；渲染回读：引擎池 Z_BASE_DB_SCHEDULE_HOST/DATABASE 各 1、spring 池 URL 1 处、口令 2 处 ⇒ 两个池同一组坐标。猎物对照：改前形状删 DB_PORT 确实 rc=$P18TEETH 通过 ⇒ 这一臂认得那种病）"
else
  echo "    猎物 rc=$P18TEETH（应为 0）；回读 ZBH=${ZBH:-?} ZBD=${ZBD:-?} URL=${SPU:-?} PW=${PWW:-?}"
  bad "P18 守卫不成套：$P18BAD"
fi

echo ""
echo "总判：PASS=$PASS FAIL=$FAIL  （Mode 1 入口路；Mode 2/3 与真集群 apply 不在这一档，见 README §12）"
if [ "$FAIL" = "0" ]; then
  echo "VERDICT: OK —— 入口 $ENTRY_NAME 在真机上首尾各跑得通一次"
else
  echo "VERDICT: NOT OK"
  exit 1
fi
