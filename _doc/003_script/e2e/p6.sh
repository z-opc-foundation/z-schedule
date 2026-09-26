#!/usr/bin/env bash
# P6..P9 — 250 真机续跑阶段：FIX_DELAY 真库写入、管理面鉴权普查、停删清理。
# 真值一律从 MySQL 裸读（q.sh），不用被测服务自己的 mapper 回读。
set -uo pipefail
cd "$(dirname "$0")"
BASE="${BASE:-http://127.0.0.1:18086}"
q() { ./q.sh -N -B -e "$1"; }
hit() { curl -s -m 10 -H 'Content-Type: application/json' -X POST "$BASE$2" -d "$3"; echo; }

echo "### P6 FIX_DELAY（trigger_type 9 字节）必须能进真库并真的按延迟调度"
ADD=$(hit POST /jobinfo/add '{"jobGroup":1,"jobDesc":"e2e-fix-delay","jobCron":"","author":"e2e","executorRouteStrategy":"ROUND","executorBlockStrategy":"SERIAL_EXECUTION","executorHandler":"demoHandler","executorTimeout":0,"executorFailRetryCount":0,"triggerType":"FIX_DELAY","fixInterval":3000,"misfireStrategy":"DO_NOTHING"}')
echo "add -> $ADD"
DID=$(echo "$ADD" | sed -n 's/.*"content":"\([0-9]*\)".*/\1/p')
if [ -z "$DID" ]; then echo "FATAL: 没拿到 jobId"; else
  echo "落库: $(q "SELECT id,trigger_type,CHAR_LENGTH(trigger_type) len,fix_interval,trigger_status FROM z_schedule_job_info WHERE id=$DID")"
  echo "start -> $(hit POST "/jobinfo/start?id=$DID" '')"
  echo "--- 等 12s 看是否按延迟成行 ---"; sleep 12
  echo "行数 + 触发秒间隔: $(q "SELECT COUNT(*) rows_, MIN(trigger_time) first_t, MAX(trigger_time) last_t FROM z_schedule_job_log WHERE job_id=$DID")"
  echo "相邻间隔(秒): $(q "SELECT GROUP_CONCAT(d ORDER BY id) FROM (SELECT id, TIMESTAMPDIFF(SECOND, LAG(t) OVER (ORDER BY id), t) d FROM (SELECT id, trigger_time t FROM z_schedule_job_log WHERE job_id=$DID) x) y WHERE d IS NOT NULL")"
  echo "stop -> $(hit POST "/jobinfo/stop?id=$DID" '')"
  echo "停后: $(q "SELECT trigger_status,trigger_last_time,trigger_next_time FROM z_schedule_job_info WHERE id=$DID")"
  echo "$DID" > .last_delay_id
fi

echo
echo "### P7 管理面鉴权普查（不带任何 cookie/token 直接打）"
for ep in "/jobinfo/list?jobGroup=0&triggerStatus=-1&start=0&length=5" "/jobgroup/list" "/user/list" "/joblog/list?jobId=0&logDateTim=&start=0&length=5"; do
  body=$(curl -s -m 10 "$BASE$ep")
  code=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$BASE$ep")
  printf 'GET %-62s -> HTTP %s  %.90s\n' "$ep" "$code" "$body"
done
echo "--- 写接口也不带凭证试一次（删除类只打到 /jobinfo/stop 的假 id，不动真数据）---"
echo "POST /jobinfo/start?id=999999 -> $(curl -s -m 10 -X POST "$BASE/jobinfo/start?id=999999")"
echo "POST /user/add -> $(hit POST /user/add '{"userName":"e2e-noauth","password":"x","role":"ADMIN","permission":"*"}')"
echo "user 表: $(q "SELECT COUNT(*) FROM z_schedule_user")"

echo
echo "### P8  executor 侧（无 token 时 /executor/* 是否放行）"
echo "POST /executor/beat -> $(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "$BASE/executor/beat")"
echo "POST /executor/callback -> $(curl -s -m 10 -X POST -H 'Content-Type: application/json' "$BASE/executor/callback" -d '{"id":1,"handleCode":500,"handleMsg":"probe"}')"
echo "回调后 handle_msg: $(q "SELECT id,handle_code,handle_msg FROM z_schedule_job_log WHERE id=1")"

echo
echo "### P9 清理：停掉并删除 P1 的 FIX_RATE 任务"
RID=$(cat .last_job_id 2>/dev/null || echo "")
if [ -n "$RID" ]; then
  echo "stop $RID -> $(hit POST "/jobinfo/stop?id=$RID" '')"
  echo "delete $RID -> $(hit POST "/jobinfo/remove?id=$RID" '')"
  echo "剩余 job_info: $(q "SELECT id,job_desc,trigger_status FROM z_schedule_job_info")"
fi
