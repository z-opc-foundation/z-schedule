#!/bin/bash
# p20.sh — 连接池是不是那堵墙？只改 Druid max-active，看吞吐跟不跟。需求量由 N 给（默认 800）。
#
# N 必须**大于**想量的供给上限，否则量到的是需求不是上限：N=800/池 80 那档达成 101 %，
# 读数 808 是需求；把 N 抬到 1600 后同一池出 904.9（达成 56.6 %），905 才是池 80 的供给上限。
#
# 为什么要有这一支：p16 的三级阶梯（N=400/800/1600）实测都停在 346 次/s，
# 说明 ~346 是**供给侧天花板**而不是需求没打满；p19 又数出忙连接峰值正好等于池上限 20、
# 且忙时间里 85 % 卡在 `waiting for handler commit`。于是"上限 = 连接数 ÷ 每条语句墙钟 ÷ 每次语句数"
# 这条公式里的两个量都还没被独立量过：
#   · 连接数：真的是按池上限在跑吗（放宽到 40/80，忙连接会不会跟着上去）；
#   · 每条语句墙钟：20 条并发下它是 16.7 ms（p15 的孤立值）还是被提交队列拉长了。
# 判定口径（三种结果都算答案）：
#   吞吐随 max-active 上升 ⇒ 墙就是池，给嵌入方的建议是"抬池 + 抬 MySQL max_connections"；
#   吞吐不动而忙连接也不动 ⇒ 池不是约束，约束在派发/提交串行段，抬池无用；
#   吞吐不动但忙连接上去了 ⇒ 每条语句的墙钟随并发变长（组提交排队），得减语句数而不是加连接。
set -u
cd "$(dirname "$0")"; . ./mysql.env

PORT="${PORT:-18086}"
JAR="${JAR:?必须显式指定 JAR，性能结论要能对上构件 md5}"
N="${N:-800}"                    # 固定需求量
W="${W:-30}"                     # 每档窗口（秒）
MAS="${MAS:-20 40 80}"           # 要扫的池上限
PERF_GROUP=90020
LOGF=logs/perf20.out
POOLCSV=logs/pool20.csv

log() { echo "[$(date +%H:%M:%S)] $*"; }
q() { ./q.sh -N -B "$@" 2>&1; }

wipe() {
  # 必须走 -e：mysql 的位置参数是"库名"不是 SQL，裸传语句会被当成库名而静默不执行
  q -e "DELETE FROM z_schedule_job_log WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_group=$PERF_GROUP)"
  q -e "DELETE FROM z_schedule_job_info WHERE job_group=$PERF_GROUP"
}

seed() { # 与 p16 同形状：空 handler ⇒ 走 INVALID_PARAM，一次执行只有 1 插 + 1 改
  local n="$1" rows="" i
  wipe
  for ((i = 0; i < n; i++)); do
    rows="$rows($PERF_GROUP,'p20-$i','','p20','','$i','FIRST','SERIAL_EXECUTION',0,0,1,0,0,'FIX_RATE',1000,'DO_NOTHING','',NOW(),NOW()),"
  done
  rows="${rows%,}"
  # 必须走 stdin：Linux 的 MAX_ARG_STRLEN 把**单个** argv 字符串限在 128 KB，
  # 一条播种 INSERT 就是一个字符串——N=800（≈96 KB）能过，N=1600（≈192 KB）报
  # `Argument list too long`。而 p16 用 heredoc 所以 1600 从来没挂过：两把尺因为传法不同
  # 在一个需求量上直接不守恒，这是尺的缺陷不是系统的（挂掉时本脚本确实报了"读数不可信"，没编数）。
  q <<SQL
INSERT INTO z_schedule_job_info
 (job_group,job_desc,job_cron,author,executor_handler,executor_param,executor_route_strategy,
  executor_block_strategy,executor_timeout,executor_fail_retry_count,trigger_status,
  trigger_last_time,trigger_next_time,trigger_type,fix_interval,misfire_strategy,child_job_id,add_time,update_time)
VALUES $rows;
SQL
  q -e "SELECT CONCAT('  播种=', COUNT(*), ' 个, 启用=', SUM(trigger_status))
       FROM z_schedule_job_info WHERE job_group=$PERF_GROUP"
}

fires() { q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$PERF_GROUP"; }

# 采样窗口内的忙连接数：与 p19 同一口径（COMMAND='Query'），但只在本档窗口里跑
pool_sampler() {
  printf 'epoch\tbusy\n' > "$POOLCSV"
  while :; do
    B=$(q -e "SELECT COUNT(*) FROM information_schema.PROCESSLIST
              WHERE USER='$MYSQL_USER' AND DB='$MYSQL_DATABASE' AND COMMAND='Query'") || B=0
    printf '%s\t%s\n' "$(date +%s)" "${B:-0}" >> "$POOLCSV"
    sleep 0.5
  done
}

echo "===================== p20 连接池扫描 ====================="
for MA in $MAS; do
  echo
  log "--- max-active=$MA，N=$N，窗口 ${W}s ---"
  if [ -f app.pid ] && kill -0 "$(cat app.pid)" 2>/dev/null; then
    kill "$(cat app.pid)"; sleep 3
  fi
  APP_ARGS="--z.base.db.schedule.max-active=$MA" JAR="$JAR" PORT="$PORT" \
    nohup ./run.sh > "$LOGF" 2>&1 & echo $! > app.pid
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
  PID=$(cat app.pid)
  # 应用真按这个档位起了吗：从 processlist 反查连接数上限不可能，只能确认它在跑
  kill -0 "$PID" 2>/dev/null || { echo "  FATAL: 实例没起来（$LOGF）"; continue; }

  seed "$N"
  LOADED=""
  for _ in $(seq 1 12); do
    sleep 5
    LOADED=$(grep -o "Engine loaded [0-9]* jobs into ring (ringTotal=[0-9]*, overflow=[0-9]*, dropped=[0-9]*)" "$LOGF" | tail -1)
    echo "$LOADED" | grep -q "loaded $N jobs" && break
  done
  echo "  $LOADED"
  echo "$LOADED" | grep -q "loaded $N jobs" || echo "  !! 装载数≠$N，本档读数不可信"

  ( pool_sampler ) & SP=$!
  F0=$(fires); T0=$(date +%s)
  sleep "$W"
  F1=$(fires); T1=$(date +%s)
  kill "$SP" 2>/dev/null; wait "$SP" 2>/dev/null

  PEAK=$(awk -F'\t' 'NR > 1 { if ($2 > p) p = $2; s += $2; n++ } END { printf "%d %.1f", p, (n ? s / n : 0) }' "$POOLCSV")
  awk -v ma="$MA" -v n="$N" -v w="$((T1 - T0))" -v f="$((F1 - F0))" -v pk="$PEAK" 'BEGIN{
    rate = f / w; split(pk, a, " ");
    printf "  实得 %d 次 / %ds = %.1f 次/s（达成 %.1f%%）\n", f, w, rate, 100 * rate / n;
    printf "  忙连接 峰值=%d 均值=%.1f（池上限 %d）\n", a[1], a[2], ma;
    printf "  反推每条语句墙钟 = %d 连接 / (%.1f 次/s x 2.00 条) = %.1f ms\n", ma, rate, 1000 * ma / (rate * 2);
  }'
  echo "  采样行数=$(($(wc -l < "$POOLCSV") - 1))，其中忙连接>0 的行=$(awk -F'\t' 'NR > 1 && $2 > 0' "$POOLCSV" | wc -l)（阳性对照）"
  q -e "UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group=$PERF_GROUP"
  sleep 20
done

wipe
kill "$(cat app.pid)" 2>/dev/null
echo
echo "===================== p20 结束 ====================="
