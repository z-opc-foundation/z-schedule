#!/bin/bash
# p18.sh — 297 次/s 的天花板到底压在哪一层？
#
# p17 把两个假设都打掉了：tick 线程 parked、200 条 worker 里 182 条空等、CPU 78% idle。
# 也就是说：既不是派发单线程，也不是线程池不够，更不是 MySQL 服务端算不过来。
# 那剩下两条腿：
#   A) 连接池——每次执行要 3~5 条语句，池子只有几条连接的话，400 个任务在同一秒抢的就是它；
#      而且 persist 线程每 2s 还要串行刷 400 条 UPDATE，和派发线程抢同一批连接。
#   B) 提交持久化——p15 量的是 performance_schema 的"服务端语句耗时"(14 µs)，
#      那个数不含 autocommit 的 redo fsync；每次提交若真花 1~2 ms，
#      400×4 条语句/秒 就正好卡在几百次/s 上。TLS 连接（p17 抓到 SSLSocket read）再加一层。
# 所以这里两份读数都要：连接数从 processlist 直接数（不猜配置），
# 提交成本用"同一张表 200 条 autocommit UPDATE vs 一个事务里 200 条"对比出来。
set -u
cd "$(dirname "$0")"

PORT="${PORT:-18086}"
N="${N:-400}"
PERF_GROUP=90016
LOGF=logs/perf18.out

log() { echo "[$(date +%H:%M:%S)] $*"; }
q() { ./q.sh -N -B --skip-column-names "$@" 2>&1; }
qt() { ./q.sh -t "$@" 2>&1; }   # -t: 输出带表格，给人看的

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
    rows="$rows($PERF_GROUP,'p18-$i','','p18','','$i','FIRST','SERIAL_EXECUTION',0,0,1,0,0,'FIX_RATE',1000,'DO_NOTHING','',NOW(),NOW()),"
  done
  q -e "INSERT INTO z_schedule_job_info (job_group,job_desc,job_cron,author,executor_handler,executor_param,executor_route_strategy,executor_block_strategy,executor_timeout,executor_fail_retry_count,trigger_status,trigger_last_time,trigger_next_time,trigger_type,fix_interval,misfire_strategy,child_job_id,add_time,update_time) VALUES ${rows%,}"
}

echo "===================== p18 天花板归因 ====================="
echo "--- 0) 与提交成本相关的服务端变量（先确认量的是哪台尺）---"
qt -e "SHOW VARIABLES WHERE Variable_name IN
 ('innodb_flush_log_at_trx_commit','sync_binlog','autocommit','have_ssl','tls_version','version','max_allowed_packet')"

wipe   # 不启动 app 时先量纯客户端侧的提交成本，免得和派发抢连接

echo
echo "--- 1) 200 条 autocommit 单行 UPDATE vs 一个事务里 200 条 ---"
# 自建 200 行热点表数据：job_group=1 只有 2 行，拿它做基准会只跑 2 条就"完成"，
# 那样量出来的 ms/条是建连成本不是提交成本。
BENCH_GROUP=90018
rows=""
for ((i=0; i<200; i++)); do rows="$rows($i,$BENCH_GROUP,NOW(),0,'p18bench'),"; done
q -e "DELETE FROM z_schedule_job_log WHERE job_group=$BENCH_GROUP"
q -e "INSERT INTO z_schedule_job_log (job_id,job_group,trigger_time,trigger_code,trigger_msg) VALUES ${rows%,}"
IDS=$(q -e "SELECT id FROM z_schedule_job_log WHERE job_group=$BENCH_GROUP ORDER BY id")
SQLS=$(awk '{printf "UPDATE z_schedule_job_log SET handle_msg=%c p18b %c WHERE id=%s;", 39, 39, $1}' <<< "$IDS")
N200=$(grep -c '^[0-9]' <<< "$IDS")
echo "  基准行数=$N200（不足 200 则本对比作废）"
echo "  口径提醒：这一段是容器内 mysql 客户端（本地 socket、无 TLS、autocommit 默认开），"
echo "            量的是「提交+fsync」那一层，不含 app 侧 TLS 与应用线程调度 ⇒ 是下界。"
A0=$(date +%s%N); printf '%s' "$SQLS" | ./q.sh -N -B >/dev/null 2>&1; A1=$(date +%s%N)
B0=$(date +%s%N); printf 'START TRANSACTION;%sCOMMIT;' "$SQLS" | ./q.sh -N -B >/dev/null 2>&1; B1=$(date +%s%N)
awk -v a="$(( (A1-A0)/1000000 ))" -v b="$(( (B1-B0)/1000000 ))" -v n="$N200" 'BEGIN{
  printf "  autocommit×%d: %d ms ⇒ %.2f ms/条\n", n, a, a/n;
  printf "  单事务×%d   : %d ms ⇒ %.3f ms/条\n", n, b, b/n;
  printf "  ⇒ 每条语句的提交净成本 ≈ %.2f ms\n", (a-b)/n}'
q -e "DELETE FROM z_schedule_job_log WHERE job_group=$BENCH_GROUP"

echo
echo "--- 2) 带负载时数连接：app 起的连接池到底几条 ---"
if [ -f app.pid ] && kill -0 "$(cat app.pid)" 2>/dev/null; then
  log "关停旧实例 $(cat app.pid)"; kill "$(cat app.pid)"; sleep 3
fi
# JAR 必须显式给：这四个脚本以前都是裸 `./run.sh`，于是静默用了 run.sh 的默认构件
# （`z-schedule-admin-1.0.0-exec.jar`，在本机上它是写放大修复**之前**那一版）。
# 性能读数要能对上一个具体的 md5，jar 文件名不算证据。
JAR="${JAR:?必须显式指定 JAR，性能结论要能对上构件 md5}"
nohup env JAR="$JAR" PORT="$PORT" ./run.sh > "$LOGF" 2>&1 & echo $! > app.pid
for i in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
log "就绪 pid=$(cat app.pid)"
seed "$N"
for i in $(seq 1 10); do
  sleep 5
  grep -o "Engine loaded [0-9]* jobs into ring" "$LOGF" | tail -1 | grep -q "loaded $N jobs" && break
done
grep -o "Engine loaded [0-9]* jobs into ring (ringTotal=[0-9]*, overflow=[0-9]*, dropped=[0-9]*)" "$LOGF" | tail -1 | sed 's/^/  /'
sleep 3
echo "  [本 app 的连接数（按用户/库聚合）]"
qt -e "SELECT USER, DB, COMMAND, STATE, COUNT(*) AS conns
      FROM information_schema.processlist GROUP BY 1,2,3,4 ORDER BY conns DESC"
echo "  [连接是否真走了 TLS]"
qt -e "SELECT COUNT(*) AS ssl_conns FROM performance_schema.session_connect_attrs
      WHERE ATTR_NAME='ssl_cipher'"
qt -e "SELECT VARIABLE_VALUE AS ssl_finished FROM performance_schema.global_status
      WHERE VARIABLE_NAME='Ssl_finished_accepts'"
R0=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$PERF_GROUP"); T0=$(date +%s)
sleep 20
R1=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$PERF_GROUP"); T1=$(date +%s)
qt -e "SELECT USER, COMMAND, STATE, COUNT(*) AS conns FROM information_schema.processlist
      WHERE DB='zschedule_e2e' GROUP BY 1,2,3 ORDER BY conns DESC"
awk -v a="$R0" -v b="$R1" -v s="$((T1-T0))" 'BEGIN{printf "  窗口 %ds: 落库 %d 行 ⇒ %.1f 次/s\n", s, b-a, (b-a)/s}'

q -e "UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group=$PERF_GROUP" >/dev/null
wipe
kill "$(cat app.pid)" 2>/dev/null
echo "===================== p18 结束 ====================="
