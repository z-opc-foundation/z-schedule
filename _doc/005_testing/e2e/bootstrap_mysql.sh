#!/usr/bin/env bash
# 在一台干净机器上把 z-schedule 的真机验证环境拉起来：MySQL 8 容器 + 建表 + 部署目录。
#
# 三条不可商量的规矩（都是今天踩过之后写死的）：
#   1) 口令只放在 $E2E_HOME/mysql.env（600 权限，仓库外），经 --env-file 交给 docker，
#      绝不出现在 argv / shell history / ps 里；
#   2) 3306 只绑 127.0.0.1 —— 测试库不许对网口开放；
#   3) 建表只用仓内 _doc/002_deploy/init/z-schedule.sql（6 CREATE + 1 种子，零 DROP）。
#      任何带 DROP 的历史脚本都不许照跑，本脚本会在真跑之前先数一遍 DROP 条数。
#
# 用法:  E2E_HOME=~/z-schedule-e2e ./bootstrap_mysql.sh
set -euo pipefail

# 脚本自身位置必须先在这里定死：下面会 cd 到 $E2E_HOME，
# 之后再取 "$(dirname "$0")" 就会指到部署目录去（第一次演练就是这么挂的）。
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

E2E_HOME="${E2E_HOME:-$HOME/z-schedule-e2e}"
CONTAINER="${CONTAINER:-z-schedule-e2e-mysql}"
IMAGE="${IMAGE:-mysql:8.0.26}"
HOST_PORT="${HOST_PORT:-33060}"
# 仓内建表脚本；默认按本脚本的位置反推，换机器不用改
REPO="${REPO:-$(cd "$SCRIPT_DIR/../../.." && pwd)}"
DDL="$REPO/_doc/002_deploy/init/z-schedule.sql"

[ -f "$DDL" ] || { echo "FATAL: 找不到建表脚本 $DDL"; exit 1; }

mkdir -p "$E2E_HOME/logs"
cd "$E2E_HOME"

if [ ! -f mysql.env ]; then
  [ -f "$SCRIPT_DIR/mysql.example.env" ] || { echo "FATAL: 找不到 $SCRIPT_DIR/mysql.example.env"; exit 1; }
  ROOT_PW=$(python3 -c 'import secrets;print(secrets.token_urlsafe(18))')
  APP_PW=$(python3 -c 'import secrets;print(secrets.token_urlsafe(18))')
  # 口令只写进这个 600 文件，不回显
  sed "s|__ROOT__|$ROOT_PW|; s|__APP__|$APP_PW|" "$SCRIPT_DIR/mysql.example.env" > mysql.env
  chmod 600 mysql.env
  echo "已生成随机口令的 $E2E_HOME/mysql.env（600，值不回显）；不要提交、不要贴进任何输出"
fi
[ "$(stat -c '%a' mysql.env 2>/dev/null || stat -f '%Lp' mysql.env)" = "600" ] \
  || { echo "!! mysql.env 权限不是 600，先 chmod 600"; exit 1; }

set -a; . ./mysql.env; set +a
: "${MYSQL_ROOT_PASSWORD:?mysql.env 缺 MYSQL_ROOT_PASSWORD}"
: "${MYSQL_DATABASE:?}" ; : "${MYSQL_USER:?}" ; : "${MYSQL_PASSWORD:?}"

echo "=== 1) MySQL 8 容器 ==="
if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "  容器 $CONTAINER 已存在，复用（不动数据）"
  docker start "$CONTAINER" >/dev/null 2>&1 || true
else
  docker run -d --name "$CONTAINER" --restart unless-stopped \
    --env-file "$E2E_HOME/mysql.env" \
    -e TZ=UTC \
    -p 127.0.0.1:"$HOST_PORT":3306 \
    "$IMAGE" \
    --default-time-zone=+00:00 \
    --character-set-server=utf8mb4 \
    --collation-server=utf8mb4_general_ci >/dev/null
  echo "  已创建：$IMAGE，端口只绑 127.0.0.1:$HOST_PORT"
fi

echo "  等就绪…"
for i in $(seq 1 60); do
  if docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
       mysqladmin ping -h127.0.0.1 -uroot --silent >/dev/null 2>&1; then
    echo "  ready（第 ${i} 次探测）"; break
  fi
  [ "$i" = 60 ] && { echo "FATAL: 60 次探测仍未就绪，看 docker logs $CONTAINER"; exit 1; }
  sleep 2
done

echo "  持久化档位（性能结论只在同一档位下可比）:"
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" mysql -h127.0.0.1 -uroot -N -B -e \
  "SELECT CONCAT('    innodb_flush_log_at_trx_commit=', @@innodb_flush_log_at_trx_commit,
                 '  sync_binlog=', @@sync_binlog)"

echo "=== 2) 建表（幂等，零 DROP） ==="
DROPS=$(grep -ci '^[[:space:]]*DROP' "$DDL" || true)
if [ "$DROPS" != "0" ]; then
  echo "FATAL: $DDL 里数到 $DROPS 条 DROP，本脚本的设计前提被破坏，拒绝执行"
  exit 1
fi
echo "  $DDL 的 DROP 条数 = 0（阳性对照 CREATE TABLE 条数 = $(grep -c '^[[:space:]]*CREATE TABLE' "$DDL")）"
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
  mysql -h127.0.0.1 -uroot "$MYSQL_DATABASE" < "$DDL"

echo "=== 3) 回读：表齐不齐 + Leader 种子行 ==="
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
  mysql -h127.0.0.1 -uroot -t "$MYSQL_DATABASE" -e \
  "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='$MYSQL_DATABASE' AND TABLE_NAME LIKE 'z\\_schedule%' ORDER BY TABLE_NAME;
   SELECT COUNT(*) AS leader_seed_rows FROM \`$MYSQL_DATABASE\`.z_schedule_job_leader WHERE id=1;"

echo
echo "完成。下一步："
echo "  把 run.sh 与本次的构件 jar 放进 $E2E_HOME，然后 JAR=<那份 jar> ./run.sh（凭据全部来自 mysql.env，不进 argv）"
echo "  盘面恰好只有一份 *-exec.jar 时也可以省掉 JAR=，run.sh 会用它；0 份或 ≥2 份都会当场拒，不给默认名"
echo "  真值一律用 ./q.sh 从 MySQL 裸读，不要用被测服务自己的 mapper 回读"
