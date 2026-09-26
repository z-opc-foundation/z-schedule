#!/bin/bash
# q.sh [mysql args...] — query the e2e MySQL from inside the container.
# MYSQL_PWD keeps the password out of argv/ps and suppresses the "-p on the command line" warning,
# so no pipe/grep is needed here and the mysql exit code reaches the caller intact.
#
# CONTAINER 必须与 bootstrap_mysql.sh / run.sh 用的是同一套环境变量：真演练里我用
# HOST_PORT=33062 CONTAINER=z-schedule-drill-mysql 起了第二个库，而这里把容器名写死成
# z-schedule-e2e-mysql —— 数据落在 drill 库、回读却读了 e2e 库，量具会对不上。
. "$(dirname "$0")/mysql.env"
CONTAINER="${CONTAINER:-z-schedule-e2e-mysql}"
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
  bash -c 'mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" "$@"' _ "$@"
