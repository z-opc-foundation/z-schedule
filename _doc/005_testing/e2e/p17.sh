#!/bin/bash
# p17.sh — 吞吐天花板归因：400 个 1Hz 任务只跑得出 297 次/s，是谁在饱和？
#
# p16 已经排除两个嫌疑：
#   · MySQL 不是瓶颈（p15：单次执行服务端 31 µs）
#   · 堆栈日志也不是（A 组一次不打 / B 组每次打一整摞，297 vs 292 次/s 是同一条曲线）
# 而 297 ≈ 400 / 1.35s，这个形状太像"单条 tick 线程一秒派不完 400 次发"了。
# 猜测不算证据，所以这里同时取两份线程级读数：
#   top -H   ⇒ 哪个线程名在烧 CPU
#   jstack   ⇒ tick 线程停在哪个调用上、worker 是不是全在 queue.take() 空等
# 判定口径：worker 全空等 + tick 满转 ⇒ 瓶颈在单线程派发；反之在池或 DB。
set -u
cd "$(dirname "$0")"

PORT="${PORT:-18086}"
N="${N:-400}"
SAMPLES="${SAMPLES:-3}"
PERF_GROUP=90016
LOGF=logs/perf17.out
JS=logs/js

log() { echo "[$(date +%H:%M:%S)] $*"; }
q() { ./q.sh -N -B --skip-column-names "$@" 2>&1; }
fires() { q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$PERF_GROUP"; }

wipe() {
  q <<SQL
DELETE FROM z_schedule_job_log WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_group=$PERF_GROUP);
DELETE FROM z_schedule_job_info WHERE job_group=$PERF_GROUP;
SQL
}

seed() {
  local n="$1" rows="" i
  wipe
  for ((i=0; i<n; i++)); do
    rows="$rows($PERF_GROUP,'p17-$i','','p17','','$i','FIRST','SERIAL_EXECUTION',0,0,1,0,0,'FIX_RATE',1000,'DO_NOTHING','',NOW(),NOW()),"
  done
  q -e "INSERT INTO z_schedule_job_info (job_group,job_desc,job_cron,author,executor_handler,executor_param,executor_route_strategy,executor_block_strategy,executor_timeout,executor_fail_retry_count,trigger_status,trigger_last_time,trigger_next_time,trigger_type,fix_interval,misfire_strategy,child_job_id,add_time,update_time) VALUES ${rows%,}"
}

probe() { # $1=序号
  local s="$1" f=logs/js.$s
  jstack "$PID" > "$f" 2>/dev/null || echo "    !! jstack 取不到（rc=$?，JDK 还是 JRE？）"
  echo "  [top -H 前 8 名线程]"
  top -H -b -n 1 -p "$PID" 2>/dev/null | awk 'NR<=7 || /z-schedule/ {print "    " substr($0,1,116)}' | head -16
  if [ -s "$f" ]; then
    echo "  [引擎线程名分布]"
    grep '^"' "$f" | sed -E 's/^"([a-zA-Z0-9-]+).*/\1/' | grep -E '^z-schedule' \
      | sed -E 's/-[0-9]+$//' | sort | uniq -c | sed 's/^/      /'
    echo "  [tick / persist 线程在干什么]"
    grep -A 9 '^"z-schedule-tick' "$f" | sed 's/^/      /' | head -12
    grep -A 6 '^"z-schedule-persist' "$f" | sed 's/^/      /' | head -8
    echo "  [fast/slow worker 状态分布]"
    grep -A 1 '^"z-schedule-\(fast\|slow\)' "$f" | grep 'Thread.State' \
      | awk '{print $3}' | sort | uniq -c | sed 's/^/      /'
  fi
}

echo "===================== p17 天花板归因 (N=$N) ====================="
wipe
if [ -f app.pid ] && kill -0 "$(cat app.pid)" 2>/dev/null; then
  log "关停旧实例 $(cat app.pid)"; kill "$(cat app.pid)"; sleep 3
fi
log "启动实例"
# JAR 必须显式给：这四个脚本以前都是裸 `./run.sh`，于是静默用了 run.sh 的默认构件
# （`z-schedule-admin-1.0.0-exec.jar`，在本机上它是写放大修复**之前**那一版）。
# 性能读数要能对上一个具体的 md5，jar 文件名不算证据。
JAR="${JAR:?必须显式指定 JAR，性能结论要能对上构件 md5}"
nohup env JAR="$JAR" PORT="$PORT" ./run.sh > "$LOGF" 2>&1 & echo $! > app.pid
for i in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
PID=$(cat app.pid)
log "就绪 pid=$PID"

seed "$N"
LOADED=""
for i in $(seq 1 10); do
  sleep 5
  LOADED=$(grep -ao "Engine loaded [0-9]* jobs into ring (ringTotal=[0-9]*, overflow=[0-9]*, dropped=[0-9]*)" "$LOGF" | tail -1)
  echo "$LOADED" | grep -q "loaded $N jobs" && break
done
echo "  $LOADED"
echo "$LOADED" | grep -q "loaded $N jobs" || echo "  !! 装载数≠$N，读数不可信"

F0=$(fires); T0=$(date +%s)
for s in $(seq 1 "$SAMPLES"); do
  echo "--- 采样 $s ---"
  probe "$s"
  sleep 2
done
F1=$(fires); T1=$(date +%s)
SPAN=$((T1-T0)); [ "$SPAN" -lt 1 ] && SPAN=1
awk -v f0="$F0" -v f1="$F1" -v span="$SPAN" -v n="$N" 'BEGIN{
  f=f1-f0; printf "--- 采样窗口 ---\n  %ds 内落库 %d 行 ⇒ %.1f 次/s（请求 %d 次/s，达成 %.1f%%）\n", span, f, f/span, n, 100*(f/span)/n}'
echo "  （注意：行数差含装载等待期的触发，只当速率用，别和 p16 的窗口差对齐）"
grep -aoE "Engine loaded [0-9]* jobs into ring \(ringTotal=[0-9]*, overflow=[0-9]*, dropped=[0-9]*\)" "$LOGF" | tail -3 | sed 's/^/  /'
grep -acE "serial queue full|DISCARD_LATER" "$LOGF" | sed 's/^/  累计丢火行=/'

q -e "UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group=$PERF_GROUP" >/dev/null
wipe
kill "$PID" 2>/dev/null
echo "===================== p17 结束 ====================="
