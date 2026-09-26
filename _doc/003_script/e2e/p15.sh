#!/bin/bash
# p15.sh — 真机取证：一次执行到底往 MySQL 写几条语句，每条多少钱。
#
# 为什么要这条尺：JobTriggerServiceImpl.finishFailure() 先 jobLogService.update(log)，
# 再在 alarmService.sendAlarm() 之后**无条件**又 update 一次 —— 而 DefaultAlarmService
# 在 alarm_email 为空时是 early return，什么都没改。失败任务因此比成功任务多一条
# UPDATE（1 insert + 2 update vs 1 insert + 1 update）。
# 读代码只能提出怀疑，落库次数要用服务端计数器实测。
#
# 为什么用计数器而不是 z_schedule_job_log 的 trigger_time/handle_time 相减：
# 那两列在 DDL 里是 datetime（秒级，无小数秒），亚秒耗时会被量化成 0。
# 所以语句数走 SHOW GLOBAL STATUS，语句耗时走 performance_schema 的 digest 累计量。
#
# 任务是用 SQL 直接播种的（绕开 /jobinfo/add），因为这一段要量的是"引擎触发→落库"，
# 不是建任务接口。
set -u
cd "$(dirname "$0")"

W_IDLE="${W_IDLE:-15}"   # 空载窗口：计数器噪声地板，也是"这条尺读得到东西"的阳性对照
W_RUN="${W_RUN:-25}"     # 每个执行窗口时长（秒），任务固定 1 秒触发一次
PORT="${PORT:-18086}"
PERF_GROUP=90015

log() { echo "[$(date +%H:%M:%S)] $*"; }
q() { ./q.sh -N -B --skip-column-names "$@" 2>&1; }

# ---- 计数器快照：全局语句计数 + 各表 digest 的 (语句数, 累计耗时) ----
# 注意 1：MySQL 8 已删掉 information_schema.GLOBAL_STATUS，只能用 SHOW GLOBAL STATUS。
# 注意 2：digest 的 SUM_TIMER_WAIT 单位是 fs ⇒ /1e12 才是 ms、/1e9 才是 µs。
#        第一版把 /1e9 当 ms 打印，冒出"单条 insert 24 ms"这种荒谬读数；换算后是
#        24 µs 才合理 ⇒ 反证单位是 fs。荒谬读数先怀疑尺。
# 注意 3：GROUP BY 的序号只能引 SELECT 的输出列。第一版只有 1 个输出列却 GROUP BY 2，
#        MySQL 报 Unknown column '2'，dg 行于是静默为 0 —— 分组键改到子查询里。
snap() {
  q -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_insert','Com_update','Com_commit','Innodb_rows_inserted')" \
    | awk 'NF==2 && $1!="Variable_name"{print "st|"$1"|"$2}'
  q <<'SQL'
SELECT CONCAT('dg|', tbl, '|', kind, '|', SUM(c), '|', ROUND(SUM(t) / 1000000000000, 4))
FROM (
  SELECT CASE
           WHEN DIGEST_TEXT LIKE '%z_schedule_job_log%'      THEN 'job_log'
           WHEN DIGEST_TEXT LIKE '%z_schedule_job_info%'     THEN 'job_info'
           WHEN DIGEST_TEXT LIKE '%z_schedule_job_leader%'   THEN 'leader'
           WHEN DIGEST_TEXT LIKE '%z_schedule_job_registry%' THEN 'registry'
         END AS tbl,
         SUBSTRING(DIGEST_TEXT, 1, 6) AS kind,
         COUNT_STAR AS c, SUM_TIMER_WAIT AS t
  FROM performance_schema.events_statements_summary_by_digest
  WHERE SCHEMA_NAME = 'zschedule_e2e'
) x
WHERE tbl IS NOT NULL
GROUP BY tbl, kind;
SQL
}

diffsnap() { # $1=后快照文件；基线取 $SNAP0
  # 快照行格式 dg|表|语句头|条数|累计ms ⇒ $2=表 $3=语句头 $4=条数 $5=累计ms
  awk -F'|' '
    NR==FNR { if ($1=="st") a[$2]=$3; else if ($1=="dg") {dc[$2"/"$3]+=$4; dt[$2"/"$3]+=$5} ; next }
    { if ($1=="st") { d=$3-a[$2]; if (d!=0) printf "    %-22s Δ%d\n", $2, d }
      else if ($1=="dg") { k=$2"/"$3; c=$4-dc[k]; t=$5-dt[k]; if (c>0)
        printf "    %-18s 语句 %d 条, 合计 %.2f ms, 平均 %.1f µs\n", k, c, t, (c?t*1000/c:0) } }
  ' "$SNAP0" "$1"
}

derived() { # $1=后快照文件 $2=行数交叉核对值
  awk -F'|' -v rowcnt="$2" '
    NR==FNR { if ($1=="dg") {dc[$2"/"$3]+=$4; dt[$2"/"$3]+=$5}; next }
    { if ($1=="dg") { k=$2"/"$3; d[k]+=$4-dc[k]; t[k]+=$5-dt[k] } }
    END {
      # 执行次数直接取 Δjob_log INSERT —— 每次执行恰好插一条，和语句数来自同一对快照，
      # 不需要拿 trigger_time 去凑窗口（那样会有"快照之后还在触发"的错位）。
      f = d["job_log/INSERT"] + 0
      if (f <= 0) { print "    (窗口内没有 job_log 插入，折算无意义)"; exit }
      printf "    每次执行: job_log INSERT %.2f | job_log UPDATE %.2f | job_info UPDATE %.2f | leader UPDATE %.2f\n",
             d["job_log/INSERT"]/f, d["job_log/UPDATE"]/f, d["job_info/UPDATE"]/f, d["leader/UPDATE"]/f
      printf "    ⇒ 结论落库共 %.2f 条 job_log 语句/次（2=一次 insert + 一次 update 干净；3=sendAlarm 后又无条件 update）\n",
             (d["job_log/INSERT"]+d["job_log/UPDATE"])/f
      printf "    ⇒ 服务端落库 %.3f ms/次（job_log 全部语句合计 %.2f ms）\n",
             (t["job_log/INSERT"]+t["job_log/UPDATE"])/f, t["job_log/INSERT"]+t["job_log/UPDATE"]
      printf "    交叉核对: 独立量具（按 trigger_time 数行）= %s 行，Δinsert=%d ⇒ %s\n",
             rowcnt, f, (rowcnt+0==f ? "一致" : "不一致，窗口边界有偏移，别信速率")
    }' "$SNAP0" "$1"
}

wipe() {
  q <<SQL
DELETE FROM z_schedule_job_log WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_group=$PERF_GROUP);
DELETE FROM z_schedule_job_info WHERE job_group=$PERF_GROUP;
UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group<>$PERF_GROUP;
SQL
}

seed() { # $1 = executor_handler（'' 走 INVALID_PARAM，非空且无此 bean 走 EXECUTOR_NOT_FOUND）
  q <<SQL
DELETE FROM z_schedule_job_log WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_group=$PERF_GROUP);
DELETE FROM z_schedule_job_info WHERE job_group=$PERF_GROUP;
INSERT INTO z_schedule_job_info
 (job_group, job_desc, job_cron, author, alarm_email, executor_route_strategy,
  executor_handler, executor_param, executor_block_strategy, executor_timeout,
  executor_fail_retry_count, trigger_status, trigger_last_time, trigger_next_time,
  trigger_type, fix_interval, misfire_strategy, child_job_id, add_time, update_time)
VALUES
 ($PERF_GROUP, 'p15 perf', '', 'p15', NULL, 'FIRST',
  '$1', NULL, 'SERIAL_EXECUTION', 0,
  0, 1, 0, 0,
  'FIX_RATE', 1000, 'DO_NOTHING', '', NOW(), NOW());
SQL
  q -e "SELECT CONCAT('seeded jobId=', id) FROM z_schedule_job_info WHERE job_group=$PERF_GROUP"
}

fires_in_window() { # $1=起始epoch秒 $2=结束epoch秒（与 snap 同一时刻取，边界才对得上）
  q <<SQL
SELECT CONCAT('FIRES=', COUNT(*),
  ' 首个=', IFNULL(MIN(trigger_time),'-'),
  ' 末个=', IFNULL(MAX(trigger_time),'-'),
  ' 实际跨度s=', IFNULL(TIMESTAMPDIFF(SECOND, MIN(trigger_time), MAX(trigger_time)), 0),
  ' 速率=', IFNULL(ROUND((COUNT(*) - 1) / NULLIF(TIMESTAMPDIFF(SECOND, MIN(trigger_time), MAX(trigger_time)), 0), 2), 0), '/s')
FROM z_schedule_job_log
WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_group=$PERF_GROUP)
  AND UNIX_TIMESTAMP(trigger_time) BETWEEN $1 AND $2;
SQL
}

echo "===================== p15 落库次数与语句耗时 ====================="

# REPLAY=1：只拿盘上已有的 snap0/snap1 重算，不再跑活窗口。
# 用途：改过 awk 后先让它复现已知真值（窗口 B 真值 = job_log UPDATE 50 / INSERT 25 /
# job_info UPDATE 13 / leader UPDATE 5 / Com_update Δ68），对不上就是尺错，不用等 4 分钟。
if [ "${REPLAY:-0}" = "1" ]; then
  SNAP0=/tmp/p15.snap0
  echo "--- 重放窗口 B 的快照对（不重启、不播种）---"
  diffsnap /tmp/p15.snap1
  derived /tmp/p15.snap1 "${REPLAY_FIRES:-26}"
  exit 0
fi

# ---- 干净起点：全停 + 清 perf 数据 + 重启（重启后 ring 里只有本窗口的任务）----
wipe
if [ -f app.pid ] && kill -0 "$(cat app.pid)" 2>/dev/null; then
  log "关停旧实例 $(cat app.pid)"; kill "$(cat app.pid)"; sleep 3
fi
log "启动实例（性能窗口不需要 token，故不带 ACCESS_TOKEN）"
nohup ./run.sh > logs/perf15.out 2>&1 & echo $! > app.pid
for i in $(seq 1 40); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1
done
log "已就绪 (pid=$(cat app.pid))，等首次 reconcile"
sleep 18

SNAP0=/tmp/p15.snap0

echo; echo "--- 窗口 0：空载 ${W_IDLE}s（噪声地板；这里若也 Δ 很大，说明计数器被别的东西污染，后面的数不可信）---"
snap > "$SNAP0"; T0=$(date +%s); sleep "$W_IDLE"; snap > /tmp/p15.snap1
diffsnap /tmp/p15.snap1
echo "    ↑ 期望只看到 leader 续约（5s 一次 ⇒ 约 $(( W_IDLE / 5 )) 条 UPDATE）和 registry 心跳，不该有 job_log"

run_case() { # $1=handler $2=标签
  echo
  echo "--- 窗口：$2 handler='$1'，${W_RUN}s ---"
  seed "$1"
  # FIX_RATE 任务由 15s 一次的 reconcile 装载进环；等它真的开始触发，再对齐窗口
  sleep 20
  grep -o "Engine loaded [0-9]* jobs into ring (ringTotal=[0-9]*, overflow=[0-9]*, dropped=[0-9]*)" logs/perf15.out | tail -1 | sed 's/^/    ring: /'
  q -e "SELECT CONCAT('status=', trigger_status, ' next_in_future=', (trigger_next_time > UNIX_TIMESTAMP()*1000)) FROM z_schedule_job_info WHERE job_group=$PERF_GROUP" | sed 's/^/    /'
  snap > "$SNAP0"; T0=$(date +%s)
  sleep "$W_RUN"
  T1=$(date +%s); snap > /tmp/p15.snap1
  FIRES=$(fires_in_window "$T0" "$T1" | sed 's/^FIRES=//; s/ .*//')
  fires_in_window "$T0" "$T1" | sed 's/^/    /'
  diffsnap /tmp/p15.snap1
  derived /tmp/p15.snap1 "$FIRES"
  q <<SQL | sed 's/^/    /'
SELECT CONCAT('handle_code=', handle_code, ' alarm_status=', alarm_status,
              ' 条数=', COUNT(*), ' 样本 handle_msg="', LEFT(MAX(handle_msg), 60), '"')
FROM z_schedule_job_log
WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_group=$PERF_GROUP)
GROUP BY handle_code, alarm_status;
SQL
  echo "    该窗口 ERROR 行数（含堆栈的失败日志）: $(grep -c 'Job execution failed' logs/perf15.out)"
  q "UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group=$PERF_GROUP" >/dev/null
}

run_case ""                        "A 空 handler → INVALID_PARAM（不抛异常的最短失败路径）"
run_case "p15NoSuchHandler"        "B 不存在的 bean → EXECUTOR_NOT_FOUND（今天真机一直在走的路径）"

echo
echo "--- 让位验证（#18 的现场）---"
BEFORE=$(date +%s)
q <<'SQL' | sed 's/^/    关停前租约: /'
SELECT CONCAT('owner|', IFNULL(owner,'NULL'), '|expire|', IFNULL(CAST(expire_time AS CHAR),'NULL'))
FROM z_schedule_job_leader WHERE id=1;
SQL
kill "$(cat app.pid)"; sleep 2
q <<'SQL' | sed 's/^/    关停后租约: /'
SELECT CONCAT('owner|', IFNULL(owner,'NULL'), '|expire|', IFNULL(CAST(expire_time AS CHAR),'NULL'))
FROM z_schedule_job_leader WHERE id=1;
SQL
echo "    关停耗时 $(( $(date +%s) - BEFORE ))s"
grep -o "Stepped down from LEADER: instanceId=[a-f0-9]*" logs/perf15.out | tail -1 | sed 's/^/    /'

wipe
echo
echo "===================== p15 结束 ====================="
