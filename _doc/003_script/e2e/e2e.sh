#!/usr/bin/env bash
# z-schedule 250 真机 E2E：所有断言的"真值"都从 MySQL 裸读，不用被服务自己的 mapper 回读
set -uo pipefail
cd "$(dirname "$0")"; . ./mysql.env
# BASE / q.sh 的 CONTAINER 都可覆盖：演练用了第二个库（端口 18087 + 容器 33062），
# 写死 18086/z-schedule-e2e-mysql 的脚本会一边对着旧库下断言、一边把请求发给新实例。
BASE="${BASE:-http://127.0.0.1:18086}"
# 口令不经 argv：`-p"$X"` 会让同机任何人 `ps` 看到 root 口令，q.sh 用 MYSQL_PWD 就是为了躲开这条
q() { ./q.sh -N -B "$@"; }
sql() { echo "$1" | q; }
hit() { curl -s -m 10 -H 'Content-Type: application/json' -X POST "$BASE$2" -d "$3"; echo; }

echo "### P1 新增 FIX_RATE 任务（超时120 / 重试2 / 间隔5000）"
ADD=$(hit POST /jobinfo/add '{"jobGroup":1,"jobDesc":"e2e-fix-rate","jobCron":"0/5 * * * * ?","author":"e2e","executorRouteStrategy":"ROUND","executorBlockStrategy":"SERIAL_EXECUTION","executorHandler":"demoHandler","executorTimeout":120,"executorFailRetryCount":2,"triggerType":"FIX_RATE","fixInterval":5000,"misfireStrategy":"DO_NOTHING"}')
echo "add -> $ADD"
ID=$(echo "$ADD" | sed -n 's/.*"content":"\([0-9]*\)".*/\1/p')
[ -z "$ID" ] && { echo "FATAL: 没拿到 jobId, 后续阶段无猎物"; exit 1; }
echo "jobId=$ID"
echo "落库形状: $(sql "SELECT id,trigger_type,fix_interval,executor_timeout,executor_fail_retry_count,trigger_status FROM z_schedule_job_info WHERE id=$ID")"

echo "### P2 启动"
echo "start -> $(hit POST "/jobinfo/start?id=$ID" '')"
echo "status: $(sql "SELECT trigger_status,trigger_last_time,trigger_next_time FROM z_schedule_job_info WHERE id=$ID")"

echo "### P3 只带 id+jobDesc 的 partial update（真实前端就是只发改动列）"
echo "update -> $(hit POST /jobinfo/update "{\"id\":$ID,\"jobDesc\":\"e2e-patched\"}")"
echo "更新后: $(sql "SELECT job_desc,trigger_type,fix_interval,executor_timeout,executor_fail_retry_count FROM z_schedule_job_info WHERE id=$ID")"

echo "### P4 显式给非法间隔：FIX_RATE + fixInterval=0 必须被拒绝而不是被当成没带"
echo "update -> $(hit POST /jobinfo/update "{\"id\":$ID,\"triggerType\":\"FIX_RATE\",\"fixInterval\":0}")"
echo "列值: $(sql "SELECT fix_interval FROM z_schedule_job_info WHERE id=$ID")"

echo "### P5 显式给 0 必须真的落 0（装箱不是永不写）"
echo "update -> $(hit POST /jobinfo/update "{\"id\":$ID,\"executorTimeout\":0}")"
echo "列值: $(sql "SELECT executor_timeout,executor_fail_retry_count FROM z_schedule_job_info WHERE id=$ID")"
echo "$ID" > .last_job_id
