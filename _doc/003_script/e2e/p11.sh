#!/usr/bin/env bash
# P11 — 换上带今天修复的 jar，在真 MySQL 上把三件事一次跑穿：
#   1) #16 FIX_DELAY 不带 cron 能不能建（列是 NOT NULL 且无默认值，H2 验不出 MySQL 的 1364）
#   2) #15 执行结论（handle_code/handle_msg/handle_time/alarm_status）到底落没落库
#   3) 真实时钟下的调度与重试节拍
set -uo pipefail
cd ~/z-schedule-e2e
BASE="${BASE:-http://127.0.0.1:18086}"
q() { ./q.sh -N -B -e "$1"; }
hit() { curl -s -m 10 -H 'Content-Type: application/json' -X POST "$BASE$2" -d "$3"; echo; }

OLD=$(cat app.pid 2>/dev/null || true)
if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
  kill "$OLD"; for _ in $(seq 30); do kill -0 "$OLD" 2>/dev/null || break; sleep 1; done
  kill -0 "$OLD" 2>/dev/null && { echo "FATAL: 旧进程未退, 拒绝双实例"; exit 1; }
fi
rm -f logs/boot3.out
PORT="${PORT:-18086}" setsid nohup ./run.sh > logs/boot3.out 2>&1 < /dev/null &
echo $! > app.pid
for i in $(seq 90); do
  [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$BASE/jobinfo/list?start=0&length=1" 2>/dev/null || echo 0)" = "200" ] && { echo "UP after ${i}s"; break; }
  sleep 1
done

echo
echo "### 1) FIX_DELAY 只给间隔、cron 留空（真库 job_cron NOT NULL）"
D=$(hit POST /jobinfo/add '{"jobGroup":1,"jobDesc":"e2e-delay-nocron","jobCron":"","author":"e2e","executorHandler":"demoHandler","executorRouteStrategy":"ROUND","executorBlockStrategy":"SERIAL_EXECUTION","triggerType":"FIX_DELAY","fixInterval":4000,"misfireStrategy":"DO_NOTHING","executorTimeout":0,"executorFailRetryCount":0}')
echo "add -> $D"
DID=$(echo "$D" | sed -n 's/.*"content":"\([0-9]*\)".*/\1/p')
if [ -z "$DID" ]; then echo "!! 建不出来，#16 在真库上仍未闭合"; else
  echo "落库: $(q "SELECT id,trigger_type,CHAR_LENGTH(trigger_type),fix_interval,IFNULL(CONCAT('[',job_cron,']'),'NULL-CRON') FROM z_schedule_job_info WHERE id=$DID")"
  echo "start -> $(hit POST "/jobinfo/start?id=$DID" '')"
fi

echo
echo "### 2) FIX_RATE 带重试，等真实时钟跑几轮"
R=$(hit POST /jobinfo/add '{"jobGroup":1,"jobDesc":"e2e-rate-writeback","jobCron":"","author":"e2e","executorHandler":"demoHandler","executorRouteStrategy":"ROUND","executorBlockStrategy":"SERIAL_EXECUTION","triggerType":"FIX_RATE","fixInterval":5000,"misfireStrategy":"DO_NOTHING","executorTimeout":0,"executorFailRetryCount":1}')
echo "add -> $R"
RID=$(echo "$R" | sed -n 's/.*"content":"\([0-9]*\)".*/\1/p')
[ -n "$RID" ] && echo "start -> $(hit POST "/jobinfo/start?id=$RID" '')"
echo "--- 等 24s ---"; sleep 24

echo
echo "### 3) 执行结论是否落库（#15 的判据）"
echo "按 job 汇总(job_id|行数|有结论|有msg|有结束时刻|已告警):"
q "SELECT CONCAT(job_id,' | ',COUNT(*),' | ',SUM(handle_code<>0),' | ',SUM(handle_msg IS NOT NULL),' | ',SUM(handle_time IS NOT NULL),' | ',SUM(alarm_status=1)) FROM z_schedule_job_log GROUP BY job_id ORDER BY job_id" | sed 's/^/  /'
echo "结论分布(handle_code|行数):"; q "SELECT CONCAT(handle_code,' | ',COUNT(*)) FROM z_schedule_job_log GROUP BY handle_code ORDER BY handle_code" | sed 's/^/  /'
echo "最近 4 行:"; q "SELECT CONCAT(id,'|job',job_id,'|',handle_code,'|',IFNULL(handle_msg,'-'),'|',IFNULL(CAST(handle_time AS CHAR),'-')) FROM z_schedule_job_log ORDER BY id DESC LIMIT 4" | sed 's/^/  /'
echo "调度指针: $(q "SELECT CONCAT(id,' ',trigger_type,' status=',trigger_status,' last=',trigger_last_time,' next=',trigger_next_time) FROM z_schedule_job_info WHERE id IN ($DID,$RID)")"
echo
echo "### 4) 清理"
[ -n "$RID" ] && echo "stop $RID -> $(hit POST "/jobinfo/stop?id=$RID" '')"
[ -n "$DID" ] && echo "stop $DID -> $(hit POST "/jobinfo/stop?id=$DID" '')"
