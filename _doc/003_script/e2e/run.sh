#!/usr/bin/env bash
# z-schedule-admin 真机部署（250:/home/zifang/z-schedule-e2e）
# 1) MySQL 8 由本机 docker 容器 z-schedule-e2e-mysql 提供, 只绑 127.0.0.1:33060
# 2) 凭证一律走环境变量: 命令行 argv 会被同机任何人 ps 看到, 所以密码绝不写在 java 参数里
#    Spring 的 relaxed binding 把 Z_BASE_DB_SCHEDULE_* 映射到 z.base.db.schedule.*（starter 自建 Druid 走这套）
set -euo pipefail
cd "$(dirname "$0")"
umask 077
set -a; . ./mysql.env; set +a

PORT="${PORT:-18086}"
# JAR 可覆盖 ⇒ 同一份部署脚本下能 A/B 两个构件（判"在跑哪一版"只认这里 + md5，别认文件名）
#
# 默认值不再写死名字（#39）。以前这里是 `z-schedule-admin-1.0.0-exec.jar`——**一个名字**，
# 而这名字在 250 的 ~/z-schedule-e2e 下先后对应过 4 份不同字节（README 坑清单里那条）。
# 写死名字的默认值不会为"名字下的字节换了"这件事报错，只会安静地把下一轮结论记在旧字节名下。
# 换成一条按当下盘面算的规则，三种盘面各自有明确行为：
#   恰好 1 份 *-exec.jar ⇒ 用它（新拷进来的构件，名字是什么不重要）
#   0 份                 ⇒ 失败：构件还没拷进来 / 拷到别处了，没有"默认"可退
#   ≥2 份                ⇒ 失败：二义，脚本不猜，必须由人 JAR= 点名
if [ -z "${JAR:-}" ]; then
  _cands=()
  shopt -s nullglob
  for _f in ./*-exec.jar; do _cands+=("$_f"); done
  shopt -u nullglob
  case "${#_cands[@]}" in
    1) JAR="${_cands[0]}" ;;
    0) echo "FATAL: $PWD 下没有 *-exec.jar，也没有 JAR= 点名 ⇒ 先把构件拷进来（不给默认名：那名字对应过 4 份字节）" >&2; exit 1 ;;
    *) echo "FATAL: $PWD 下有 ${#_cands[@]} 份 *-exec.jar，二义不猜 ⇒ 用 JAR=<路径> 点名其一：${_cands[*]}" >&2; exit 1 ;;
  esac
fi
# 库坐标也来自 mysql.env（MYSQL_DATABASE/MYSQL_USER），端口主机可用 DB_HOST/DB_PORT 覆盖。
# 这里原来把 127.0.0.1:33060/zschedule_e2e/zschedule 写死在脚本里：第一次真演练时
# bootstrap_mysql.sh 起了一个新端口的干净库，run.sh 却带着新口令去敲旧容器，
# 报的是 Access denied ——看着像口令错，实际是脚本连库连错了地方。写死的坐标会静默地把
# 应用指向"上一个"库，而所有后续结论都是从那个库量出来的，所以必须参数化。
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-33060}"
DB_NAME="${MYSQL_DATABASE:?mysql.env 缺 MYSQL_DATABASE}"
DB_USER="${MYSQL_USER:?mysql.env 缺 MYSQL_USER}"
export SPRING_DATASOURCE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?serverTimezone=UTC&useUnicode=true&characterEncoding=utf-8"
export SPRING_DATASOURCE_USERNAME="$DB_USER"
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PASSWORD"
export Z_BASE_DB_SCHEDULE_HOST="$DB_HOST"
export Z_BASE_DB_SCHEDULE_PORT="$DB_PORT"
export Z_BASE_DB_SCHEDULE_DATABASE="$DB_NAME"
export Z_BASE_DB_SCHEDULE_USERNAME="$DB_USER"
export Z_BASE_DB_SCHEDULE_PASSWORD="$MYSQL_PASSWORD"
export ZSCHEDULE_LOG_PATH="$PWD/logs/z-schedule-admin.log"
# Leader 续约与周期 reconcile 都挂在 @Scheduled 上, Spring 默认只有 1 条调度线程会互相排队
EXTRA_ARGS=()
if [ -n "${ACCESS_TOKEN:-}" ]; then
  EXTRA_ARGS+=("--z.schedule.access-token=${ACCESS_TOKEN}")
fi
# APP_ARGS：透传任意 `--key=value` 给应用，用来 A/B 服务端旋钮而不改代码
# （例：APP_ARGS="--z.base.db.schedule.max-active=40" 量连接池对吞吐的影响）。
# 按空格切分，所以值里不能带空格；带空格的参数请另写脚本，别塞这里。
if [ -n "${APP_ARGS:-}" ]; then
  read -r -a _app_args <<< "$APP_ARGS"
  EXTRA_ARGS+=("${_app_args[@]}")
fi

mkdir -p logs
# 连的是哪个库要打在日志里（口令不打）——上一轮就是靠这行才把"连错容器"和"口令不对"分开的
echo "deploy: JAR=$JAR port=$PORT db=${DB_HOST}:${DB_PORT}/${DB_NAME} user=${DB_USER}"
# ${a[@]+"${a[@]}"} 而不是 "${a[@]}"：本机 bash 3.2 在 `set -u` 下把**空数组**当未绑定变量
# （2026-09-27 实测：`a=(); echo "${a[@]}"` → `a[@]: unbound variable`，rc=1），于是不设
# ACCESS_TOKEN / APP_ARGS 时这份脚本在 exec java **之前**就死了。250 是 bash 5 才躲过这一枪，
# 而 README 的 quick-start 是给人照着敲的，两台都得能走。加这一层判断不改语义：数组非空时
# 展开结果与原来逐字相同，仍然一项一个 argv、仍然带各自的引号。
exec java -Xms256m -Xmx768m \
  -jar "$JAR" \
  --server.port="$PORT" \
  ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
