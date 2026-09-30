#!/usr/bin/env bash
# P13 — 量 #18 的验收指标："重启之后，一个被 start 的任务多久才真的开始执行"。
#   p11 那轮：13:34:59 引擎已启动 → 13:35:03 两次 start 都返回 success → 13:35:27 才 Became LEADER，
#   24 秒窗口里零执行（旧 jar 关停不释放租约，接管方只能等满 30s TTL）。
#   带 @PreDestroy 的新 jar 应当把这段压到一次选举间隔（5s）以内。
#   用法: ./p13.sh [最长等待秒=75]
set -uo pipefail
cd ~/z-schedule-e2e
BASE="${BASE:-http://127.0.0.1:18086}"
# #39：这一轮的"多久才真的开始执行"是拿**某一个构件**量的，而 run.sh 的默认值只是个名字
# （`z-schedule-admin-1.0.0-exec.jar`，250 上先后对应过 4 份不同字节）⇒ 复跑必须点名，否则
# 24 s → 5 s 那个差值说不清是谁跑出来的。
JAR="${JAR:?必须显式指定 JAR（#39：run.sh 的默认构件是个被复用过 4 次的名字）}"
MAXWAIT=${1:-75}
q() { ./q.sh -N -B -e "$1"; }
hit() { curl -s -m 10 -H 'Content-Type: application/json' -X POST "$BASE$2" -d "${3:-}"; echo; }

BEFORE=$(q "SELECT IFNULL(MAX(id),0) FROM z_schedule_job_log")
echo "日志基数 id>$BEFORE"

OLD=$(cat app.pid 2>/dev/null || true)
if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
  KS=$(date +%s); kill "$OLD"
  for _ in $(seq 30); do kill -0 "$OLD" 2>/dev/null || break; sleep 1; done
  kill -0 "$OLD" 2>/dev/null && { echo "FATAL: 旧进程未退, 拒绝双实例"; exit 1; }
  echo "旧进程 $OLD 退出耗时 $(( $(date +%s) - KS ))s"
fi
rm -f logs/boot4.out
PORT="${PORT:-18086}" JAR="$JAR" setsid nohup ./run.sh > logs/boot4.out 2>&1 < /dev/null &
echo $! > app.pid

UP=0
for i in $(seq 60); do
  [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$BASE/jobinfo/list?start=0&length=1" 2>/dev/null || echo 0)" = "200" ] && { UP=$i; break; }
  sleep 1
done
echo "HTTP UP after ${UP}s"
[ "$UP" = "0" ] && { echo "FATAL: 起不来"; tail -20 logs/boot4.out; exit 1; }

D=$(hit POST /jobinfo/add '{"jobGroup":1,"jobDesc":"e2e-p13-delay","jobCron":"","author":"e2e","executorHandler":"demoHandler","executorRouteStrategy":"ROUND","executorBlockStrategy":"SERIAL_EXECUTION","triggerType":"FIX_DELAY","fixInterval":4000,"misfireStrategy":"DO_NOTHING","executorTimeout":0,"executorFailRetryCount":0}')
DID=$(echo "$D" | sed -n 's/.*"content":"\([0-9]*\)".*/\1/p')
R=$(hit POST /jobinfo/add '{"jobGroup":1,"jobDesc":"e2e-p13-rate","jobCron":"","author":"e2e","executorHandler":"demoHandler","executorRouteStrategy":"ROUND","executorBlockStrategy":"SERIAL_EXECUTION","triggerType":"FIX_RATE","fixInterval":5000,"misfireStrategy":"DO_NOTHING","executorTimeout":0,"executorFailRetryCount":1}')
RID=$(echo "$R" | sed -n 's/.*"content":"\([0-9]*\)".*/\1/p')
echo "add: delay=$DID($D) rate=$RID($R)"
[ -z "$DID" ] || [ -z "$RID" ] && { echo "FATAL: 建任务失败, #16 没闭合"; exit 1; }

T0=$(date +%s)
echo "start 2 -> $(hit POST "/jobinfo/start?id=$DID")"
echo "start 3 -> $(hit POST "/jobinfo/start?id=$RID")"
FIRST=0
for w in $(seq "$MAXWAIT"); do
  N=$(q "SELECT COUNT(*) FROM z_schedule_job_log WHERE id>$BEFORE")
  [ "${N:-0}" -gt 0 ] && { FIRST=$w; break; }
  sleep 1
done
echo "### 从 start 到第一条执行日志: ${FIRST}s (等待上限 ${MAXWAIT}s)"

echo "--- 再等 22s 看节拍 ---"; sleep 22
echo "选主时间线: $(grep -aoE 'Engine started.*|Became LEADER.*' logs/boot4.out | head -3 | tr '\n' ';')"
echo "按 job 汇总(job_id|行数|有结论|有msg|有结束时刻|已告警):"
q "SELECT CONCAT(job_id,' | ',COUNT(*),' | ',SUM(handle_code<>0),' | ',SUM(handle_msg IS NOT NULL),' | ',SUM(handle_time IS NOT NULL),' | ',SUM(alarm_status=1)) FROM z_schedule_job_log WHERE id>$BEFORE GROUP BY job_id ORDER BY job_id" | sed 's/^/  /'
echo "结论分布(handle_code|行数):"
q "SELECT CONCAT(handle_code,' | ',COUNT(*)) FROM z_schedule_job_log WHERE id>$BEFORE GROUP BY handle_code ORDER BY handle_code" | sed 's/^/  /'
echo "逐行(id|job|handle_code|handle_msg|触发时刻):"
q "SELECT CONCAT_WS(' | ',id,job_id,handle_code,IFNULL(handle_msg,'NULL-MSG'),DATE_FORMAT(trigger_time,'%H:%i:%s')) FROM z_schedule_job_log WHERE id>$BEFORE ORDER BY id" | sed 's/^/  /'
echo "调度指针: $(q "SELECT CONCAT_WS(' ',id,trigger_type,CONCAT('status=',trigger_status),CONCAT('last=',trigger_last_time),CONCAT('next=',trigger_next_time)) FROM z_schedule_job_info WHERE id IN ($DID,$RID)")"

echo
echo "### 清理 + 关停后租约是否被释放"
echo "stop $RID -> $(hit POST "/jobinfo/stop?id=$RID")"
echo "stop $DID -> $(hit POST "/jobinfo/stop?id=$DID")"
echo "remove $RID -> $(hit POST "/jobinfo/remove?id=$RID")"
echo "remove $DID -> $(hit POST "/jobinfo/remove?id=$DID")"
echo "租约行(关停前): $(q "SELECT CONCAT_WS(' | ','owner',IFNULL(owner,'NULL'),'expire',IFNULL(CAST(expire_time AS CHAR),'NULL')) FROM z_schedule_job_leader WHERE id=1")"
KS=$(date +%s); STOPPID=$(cat app.pid)
kill "$STOPPID" 2>/dev/null
for _ in $(seq 20); do kill -0 "$STOPPID" 2>/dev/null || break; sleep 1; done
echo "关停耗时 $(( $(date +%s) - KS ))s, 关停后租约: $(q "SELECT CONCAT_WS(' | ','owner',IFNULL(owner,'NULL'),'expire',IFNULL(CAST(expire_time AS CHAR),'NULL')) FROM z_schedule_job_leader WHERE id=1")"
grep -anE "step down|Stepped down|ERROR" logs/boot4.out | tail -5 | sed 's/^/  boot4: /'
