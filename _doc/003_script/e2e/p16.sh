#!/bin/bash
# p16.sh — 真机吞吐阶梯：1 秒级 FIX_RATE 任务从 20 个加到 400 个，触发得出来吗？
#
# 为什么要有这条尺：p15 量出"单次执行服务端只花 31 µs"，也就是说 MySQL 根本不是瓶颈。
# 那么瓶颈在哪？两种嫌疑：
#   1) 每次失败都 logger.error(..., e) 打整摞堆栈 ⇒ log4j 同步 appender + 磁盘写；
#   2) 派发线程池 / 串行队列 / 60 格秒轮。
# 所以阶梯要跑两种任务：A 组（空 handler，走 INVALID_PARAM，一行 ERROR 都不打）
# 和 B 组（不存在的 bean，每次执行一条带堆栈的 ERROR）。同一台机器、同一份 jar，
# 只有"打不打堆栈"这一个变量不同 —— 谁的曲线先塌，瓶颈就在谁身上。
#
# 除语句数以外还量日志字节：Δbytes/次 才是"堆栈日志到底多贵"的直接答案。
set -u
cd "$(dirname "$0")"

PORT="${PORT:-18086}"
W="${W:-30}"                    # 每个台阶的测量窗口（秒）
SIZES="${SIZES:-20 100 400}"    # 任务数台阶
PERF_GROUP=90016
LOGF=logs/perf16.out

log() { echo "[$(date +%H:%M:%S)] $*"; }
q() { ./q.sh -N -B --skip-column-names "$@" 2>&1; }

snap() {
  q -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_insert','Com_update')" \
    | awk 'NF==2 && $1!="Variable_name"{print "st|"$1"|"$2}'
  q <<'SQL'
SELECT CONCAT('dg|', tbl, '|', kind, '|', SUM(c))
FROM (
  SELECT CASE WHEN DIGEST_TEXT LIKE '%z_schedule_job_log%' THEN 'job_log' ELSE 'other' END AS tbl,
         SUBSTRING(DIGEST_TEXT, 1, 6) AS kind, COUNT_STAR AS c
  FROM performance_schema.events_statements_summary_by_digest
  WHERE SCHEMA_NAME = 'zschedule_e2e'
) x WHERE tbl='job_log' GROUP BY tbl, kind;
SQL
}
# 取快照里某个量的值：dg 类按语句头($3)取条数($4)，st 类按变量名($2)取值($3)
g() { awk -F'|' -v t="$1" -v k="$2" '$1==t && (t!="dg" ? $2==k : $3==k) {print (t=="dg"?$4:$3); exit}' "$3"; }

wipe() {
  q <<SQL
DELETE FROM z_schedule_job_log WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_group=$PERF_GROUP);
DELETE FROM z_schedule_job_info WHERE job_group=$PERF_GROUP;
SQL
}

seed() { # $1=任务数 $2=executor_handler 字面值（空串 ⇒ INVALID_PARAM 路径；乱写的名字 ⇒ EXECUTOR_NOT_FOUND 路径）
  local n="$1" h="$2" rows="" i
  wipe
  for ((i=0; i<n; i++)); do
    rows="$rows($PERF_GROUP,'p16-$i','','p16','$h','$i','FIRST','SERIAL_EXECUTION',0,0,1,0,0,'FIX_RATE',1000,'DO_NOTHING','',NOW(),NOW()),"
  done
  rows="${rows%,}"
  q <<SQL
INSERT INTO z_schedule_job_info
 (job_group,job_desc,job_cron,author,executor_handler,executor_param,executor_route_strategy,
  executor_block_strategy,executor_timeout,executor_fail_retry_count,trigger_status,
  trigger_last_time,trigger_next_time,trigger_type,fix_interval,misfire_strategy,child_job_id,add_time,update_time)
VALUES $rows;
SQL
  q -e "SELECT CONCAT('  播种=', COUNT(*), ' 个, trigger_status=1 的=', SUM(trigger_status), ', handler 空串=', SUM(executor_handler='')) FROM z_schedule_job_info WHERE job_group=$PERF_GROUP"
}

echo "===================== p16 吞吐阶梯 ====================="

# P16_SELFTEST=1：不启动 app，只验"播种 → 快照 → 埋一条 → 差分"这条链。
# 埋 1 条 job_log INSERT，Δ 必须是 1；Δ 是 0 说明 g()/快照字段错位，
# Δ 大于 1 说明有别的写入者在动这张表（那阶梯的读数就不可信，得先找到它）。
if [ "${P16_SELFTEST:-0}" = "1" ]; then
  seed 3 selftestHandler
  snap > /tmp/p16.s0
  q -e "INSERT INTO z_schedule_job_log (job_id,job_group,trigger_time,trigger_code,trigger_msg) VALUES ($PERF_GROUP,$PERF_GROUP,NOW(),0,'selftest')"
  snap > /tmp/p16.s1
  echo "  埋 1 条 INSERT ⇒ Δjob_log/INSERT = $(( $(g dg INSERT /tmp/p16.s1) - $(g dg INSERT /tmp/p16.s0) ))  (期望 1)"
  echo "  g st Com_insert: $(g st Com_insert /tmp/p16.s0) → $(g st Com_insert /tmp/p16.s1)"
  echo "  g dg UPDATE 基线: $(g dg UPDATE /tmp/p16.s0) → $(g dg UPDATE /tmp/p16.s1)"
  echo "  snap 原始形状:"; sed 's/^/    /' /tmp/p16.s1
  # 埋的那行 job_id=PERF_GROUP 不在播种出来的 id 里，wipe() 的按 id 删除捞不到它，必须单独清掉
  q -e "DELETE FROM z_schedule_job_log WHERE trigger_msg='selftest'"
  wipe
  q -e "SELECT CONCAT('  清理后 selftest 残留=', COUNT(*)) FROM z_schedule_job_log WHERE trigger_msg='selftest'"
  exit 0
fi

wipe
if [ -f app.pid ] && kill -0 "$(cat app.pid)" 2>/dev/null; then
  log "关停旧实例 $(cat app.pid)"; kill "$(cat app.pid)"; sleep 3
fi
log "启动实例（无 token，纯性能窗口）"
nohup ./run.sh > "$LOGF" 2>&1 & echo $! > app.pid
for i in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
log "就绪 pid=$(cat app.pid)"

ladder() { # $1=handler 字面值 $2=这一组的说明
  local h="$1" label="$2" n i LOADED s0 s1
  for n in $SIZES; do
    echo
    echo "--- [$label] N=$n，窗口 ${W}s ---"
    seed "$n" "$h"
    # 等 reconcile 真的把 n 个装进轮（reconcile 15s 一次）；装不满就别测，
    # 带着脏轮跑下一档只会把"没装上"误读成"扛不住"。
    LOADED=""
    for i in $(seq 1 10); do
      sleep 5
      LOADED=$(grep -o "Engine loaded [0-9]* jobs into ring (ringTotal=[0-9]*, overflow=[0-9]*, dropped=[0-9]*)" "$LOGF" | tail -1)
      echo "$LOADED" | grep -q "loaded $n jobs" && break
    done
    echo "  $LOADED"
    echo "$LOADED" | grep -q "loaded $n jobs" || echo "  !! 装载数≠$n，本台阶读数不可信"

    snap > /tmp/p16.s0
    E0=$(grep -c 'Job execution failed' "$LOGF"); B0=$(stat -c %s "$LOGF"); T0=$(date +%s)
    sleep "$W"
    snap > /tmp/p16.s1
    E1=$(grep -c 'Job execution failed' "$LOGF"); B1=$(stat -c %s "$LOGF"); T1=$(date +%s)

    FIRES=$(( $(g dg INSERT /tmp/p16.s1) - $(g dg INSERT /tmp/p16.s0) ))
    UPDJ=$(( $(g dg UPDATE /tmp/p16.s1) - $(g dg UPDATE /tmp/p16.s0) ))
    CI=$(( $(g st Com_insert /tmp/p16.s1) - $(g st Com_insert /tmp/p16.s0) ))
    CU=$(( $(g st Com_update /tmp/p16.s1) - $(g st Com_update /tmp/p16.s0) ))
    SPAN=$((T1 - T0)); [ "$SPAN" -lt 1 ] && SPAN=1
    ERR=$((E1 - E0)); BYTES=$((B1 - B0))
    awk -v n="$n" -v span="$SPAN" -v f="$FIRES" -v upd="$UPDJ" -v ci="$CI" -v cu="$CU" \
        -v err="$ERR" -v bytes="$BYTES" 'BEGIN{
      rate = f/span;
      printf "  实得 %d 次 / %ds = %.1f 次/s（请求 %d 次/s）⇒ 达成率 %.1f%%\n", f, span, rate, n, 100*rate/n;
      printf "  全局语句: Com_insert Δ%d, Com_update Δ%d；job_log 侧 %d 插 + %d 改 ⇒ %.2f 条/次\n", ci, cu, f, upd, (f?((f+upd)/f):0);
      printf "  带堆栈 ERROR %d 条 (%.1f 条/s)，日志增 %d 字节 ⇒ %.0f B/次\n", err, err/span, bytes, (f?bytes/f:0);
    }'
    echo "  累计丢火行(串行队列满+DISCARD_LATER)= $(grep -cE 'serial queue full|DISCARD_LATER' "$LOGF")"
    q -e "SELECT CONCAT('  本组落库行数=', COUNT(*)) FROM z_schedule_job_log WHERE job_group=$PERF_GROUP"
    q -e "UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group=$PERF_GROUP" >/dev/null
    sleep 20   # 留一个 reconcile 周期，确保上一档任务真的从轮里摘干净再播下一档
  done
}

ladder ""              "空 handler ⇒ INVALID_PARAM，每次不打堆栈"
ladder "p16NoSuchBean" "缺 bean ⇒ EXECUTOR_NOT_FOUND，每次打一整摞堆栈"

wipe
kill "$(cat app.pid)" 2>/dev/null
echo
echo "===================== p16 结束 ====================="
