#!/bin/bash
# svc_smoke.sh — 常驻在 250 的 z-schedule 服务"现在还活着吗"的可重复证据
#
# 为什么需要它：常驻实例平时 ring 里 0 个任务，日志只会每 15 s 打一条
# "Engine loaded 0 jobs into ring"——那是 reconcile 在跑，不是"能执行任务"。
# 判"活着"要看见**一次真实执行把结论写回库**，而且要走 0b9ac0f 修好的那条
# IJobHandler.execute(TriggerParam) 派发支（此前引擎只找 execute(String)，
# 接口版 handler 永远走不到 ⇒ 演示应用里也一个 handler bean 都没有）。
#
# 默认跑完把任务**停用**（trigger_status=0）而不是删掉：1 Hz 常驻写负载会让共享库
# 每天多约 52 万行日志，证明过一次就够，但留行能回答"上次冒烟是什么时候"。
#   ./svc_smoke.sh            播种 → 数成功行 → 停用
#   KEEP=1 ./svc_smoke.sh      播种后不停用（要看 UI 时用，记得自己关）
#   ./svc_smoke.sh status      只读：ring 里的演示任务状态 + 最近一次成功结论时间
set -u
cd "$(dirname "$0")"

GROUP="${GROUP:-90098}"
PORT="${PORT:-18098}"
N="${N:-3}"
WINDOW="${WINDOW:-25}"
# 日志路径不硬编码、也不从命名约定推，而是问进程本身：`/proc/<pid>/fd/1` 指向哪份就数哪份。
# 原先写作 LOG=logs/service_${PORT}.out，而这一轮起日志改成一构件一文件 ⇒ 默认路径指向的是
# **上一个构件**的日志：09-27 换构件后首次实跑，库侧 21 条 handle_code=200（真执行了）而 handler 侧证 0 行，
# 冒烟把自己的量具判成红。见 _doc/003_script/e2e/README.md §10。
LOG="${LOG:-}"

q() { ./q.sh -N -B --skip-column-names "$@" 2>&1; }

if [ "${1:-}" = "status" ]; then
  echo "--- 演示任务（group=$GROUP）---"
  q -e "SELECT id, executor_handler, trigger_status, trigger_type, fix_interval FROM z_schedule_job_info WHERE job_group=$GROUP ORDER BY id" \
    | awk -F'\t' '{printf "  job=%s handler=%s status=%s type=%s interval=%sms\n", $1,$2,$3,$4,$5}'
  echo "--- 最近 5 条结论 ---"
  q -e "SELECT id, handle_code, LEFT(handle_msg,28), trigger_time FROM z_schedule_job_log WHERE job_group=$GROUP ORDER BY id DESC LIMIT 5" \
    | awk -F'\t' '{printf "  log=%s code=%s msg=%s at %s\n", $1,$2,$3,$4}'
  echo "--- 进程 ---"
  pgrep -af "[j]ava" | grep -- "-jar [^ ]*z-schedule-admin" | cut -c1-110
  exit 0
fi

echo "=== 1) 服务侧确认（端口 $PORT 必须是本冒烟认识的那个 jar）==="
# 两处都得防"自己匹配自己"：pgrep -f 会把调用它的 bash -c 命令行也算进去，
# 而那条命令行里带着 JAR=... 的字面量——首次实跑就是 head -1 取到它，
# 于是 grep -oE "\-jar " 落空，把活得好好的服务判成"端口上没有 java 进程"。
# [j]ava 这个方括号写法让模式字符串本身不再匹配自己的文本。
CAND=$(pgrep -af "[j]ava" | grep -- "-jar [^ ]*z-schedule-admin" | grep -- "--server.port=$PORT" | head -1)
if [ -z "$CAND" ]; then
  echo "FATAL: 端口 $PORT 上没有 z-schedule-admin 的 java 进程 ⇒ 判不了活着，别继续"
  echo "       （现在的 java 进程：）"; pgrep -af "[j]ava .*(-jar|classpath)" | grep -oE "[^ ]*z-schedule[^ ]*" | head -3
  exit 1
fi
JARLINE=$(sed -E 's/^[0-9]+ //; s/(--server\.port[= ]*)([0-9]+).*/jar 见 argv, port=\2/' <<< "$CAND")
echo "  $JARLINE"
if [ -z "$LOG" ]; then
  # 只信这个进程自己说它往哪写：fd/1 是指向 stdout 落点的符号链接，
  # 文件被改名/被截过会带 " (deleted)" 尾巴，那种情况下数不出对照 ⇒ 直接 FATAL，不猜。
  LOGPID=$(sed -E 's/ .*//' <<< "$CAND")
  LOG=$(readlink "/proc/$LOGPID/fd/1" 2>/dev/null | sed 's/ (deleted)$//')
  if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then
    echo "FATAL: 拿不到进程 $LOGPID 的 stdout 落点（/proc/$LOGPID/fd/1 → '${LOG:-空}'）⇒ handler 侧证无从数起"
    echo "       logs/ 下现有的：$(ls -1 logs 2>/dev/null | grep "service_${PORT}" | tr '\n' ' ')"
    exit 1
  fi
  echo "  日志落点取自 fd/1：$LOG"
fi
HTTP=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT/")
echo "  HTTP=$HTTP（000/403 都不算通过）"
[ "$HTTP" = "200" ] || { echo "FATAL: 管理面没答 200"; exit 1; }

echo "=== 2) 播种 $N 个 FIX_RATE 任务，handler=demoHandler（IJobHandler 接口版）==="
# 日志行基线：上一次的行必须排除在外，否则"成功=72"会包含历史、而 0 次成功的老行
# 也能凑出一个 VERDICT: OK（首次实跑就是这个现象：日志行=72 里只有 30 行是本次的）
LOGID0=$(q -e "SELECT COALESCE(MAX(id),0) FROM z_schedule_job_log WHERE job_group=$GROUP")
echo "  job_log 基线 id>$LOGID0 才算本次"
q -e "UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group=$GROUP" >/dev/null
q -e "DELETE FROM z_schedule_job_info WHERE job_group=$GROUP" >/dev/null
rows=""
for ((i = 0; i < N; i++)); do
  # 'p$i' 而不是 'p'$i：后者展开成 'p'0，引号被拆开会变成 1064 语法错
  rows="$rows($GROUP,'svc-smoke-$i','','svc','demoHandler','p$i','ROUND','SERIAL_EXECUTION',0,0,1,0,0,'FIX_RATE',2000,'DO_NOTHING','',NOW(),NOW()),"
done
q <<SQL
INSERT INTO z_schedule_job_info
 (job_group, job_desc, job_cron, author, executor_handler, executor_param, executor_route_strategy,
  executor_block_strategy, executor_timeout, executor_fail_retry_count, trigger_status,
  trigger_last_time, trigger_next_time, trigger_type, fix_interval, misfire_strategy, child_job_id,
  add_time, update_time)
 VALUES ${rows%,};
SQL
SEED=$(q -e "SELECT COUNT(*) FROM z_schedule_job_info WHERE job_group=$GROUP AND trigger_status=1")
echo "  已启用任务数=$SEED/$N"
[ "$SEED" -lt "$N" ] && { echo "FATAL: 任务没建出来"; exit 1; }
# 阳性对照必须从"我播种之后"那一行开始数：日志文件是跨重启沿用的，
# 从文件头数会把**上一个构件**留下的 demoHandler 行算进来——那正是"陈旧的正对照"。
[ -f "$LOG" ] || { echo "FATAL: 找不到服务日志 $LOG（对照拿不到，别说跑通了）"; exit 1; }
BASE=$(wc -l < "$LOG")
echo "  日志基线 $LOG 第 $BASE 行之后才算本次"

echo "=== 3) 等 ${WINDOW}s，数成功结论（只认 handle_code=200 的行）==="
sleep "$WINDOW"
OK=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$GROUP AND id>$LOGID0 AND handle_code=200")
ANY=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$GROUP AND id>$LOGID0")
# handle_code=0 是"进行中"（那一行刚被 INSERT，结论还没回写），不是失败：
# 旧口径 handle_code<>200 会把一次正常派发中途采到的行算成非成功 ⇒ 自己造红
ERR=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$GROUP AND id>$LOGID0 AND handle_code NOT IN (0,200)")
echo "  本次日志行=$ANY，其中成功=$OK，非成功=$ERR（每个任务 2 s 一次 ⇒ 期望约 $((N * WINDOW / 2)) 行）"
q -e "SELECT id, job_id, handle_code, LEFT(handle_msg,40) FROM z_schedule_job_log WHERE job_group=$GROUP AND id>$LOGID0 ORDER BY id DESC LIMIT 3" \
  | awk -F'\t' '{printf "    log=%s job=%s code=%s msg=%s\n", $1,$2,$3,$4}'
# 阳性对照：demoHandler 里那行 logger.info 必须真的出现过，否则"200"可能是别处写进去的
LOGHITS=$(tail -n +$((BASE + 1)) "$LOG" | grep -ac "demoHandler 执行 jobId")
echo "  [对照] 本次新增日志里 'demoHandler 执行 jobId' 行数=$LOGHITS（基线第 $BASE 行之后）"
if [ "$OK" -lt "$N" ] || [ "$ERR" -gt 0 ] || [ "$LOGHITS" -lt 1 ]; then
  echo "VERDICT: FAIL —— 本次成功行=$OK（至少要有 $N，每个任务各一次）非成功=$ERR handler 侧证=$LOGHITS"
  echo "         看 $LOG 与 'GROUP=$GROUP ./svc_smoke.sh status'"
  exit 1
fi
echo "VERDICT: OK —— IJobHandler 派发支在真机上跑通，结论确实落库"

if [ "${KEEP:-0}" = "1" ]; then
  echo "=== 4) KEEP=1：任务保持启用（记得自己关：q.sh -e \"UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group=$GROUP\"）"
else
  q -e "UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group=$GROUP" >/dev/null
  echo "=== 4) 已停用演示任务（保留行以便回看）：启用中=$(q -e "SELECT COUNT(*) FROM z_schedule_job_info WHERE job_group=$GROUP AND trigger_status=1")"
fi
