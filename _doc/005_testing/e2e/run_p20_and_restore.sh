#!/bin/bash
# run_p20_and_restore.sh — 量完 §4.2 那把池梯子，**必须**把常驻实例放回原处。
#
# 为什么需要这个包装：一台库只允许一个 leader（z_schedule_job_leader 是单例行），p20 自己起的实例
# 在常驻实例活着的时候永远只是 follower、调度环永远是空的 ⇒ 要重跑性能基线就得先把 18098 停掉。
# 而"停下来忘了起"是这类脚本最容易犯的错：这条服务挂在 250 上不是为我这次测量服务的。
# 所以关停与复原写死在同一条脚本里，任何一步失败都往下走到复原段（不做 `set -e` 提前退出）。
#
# 判"租约已释放"那一手只认 owner IS NULL / expire_time 过期，**不数行数**：stepDown() 是把
# owner/host/expire_time 置 NULL 的 UPDATE，行永远还在（实测 COUNT(*) 恒为 1），拿"行数=0"当判据
# 会一直等到超时。
set -u
cd "$(dirname "$0")"

JAR="${JAR:?必须显式指定 JAR，性能结论要能对上构件 md5}"
RESIDENT_PORT="${RESIDENT_PORT:-18098}"
SHA=$(printf '%s' "$JAR" | sed -n 's/.*svc-\(.*\)-exec\.jar$/\1/p')
LOG="logs/service_${RESIDENT_PORT}_${SHA:-unknown}"

resident_pid() { ss -ltnp 2>/dev/null | grep ":$RESIDENT_PORT " | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1; }

PID=$(resident_pid)
if [ -z "$PID" ]; then
  echo "!! 没找到常驻实例（端口 $RESIDENT_PORT 上没进程）⇒ 跳过关停，直接量"
else
  echo "关停常驻实例 pid=$PID（argv: $(tr '\0' ' ' < /proc/$PID/cmdline | cut -c1-70)）"
  T_KILL=$(date +%s%3N)
  kill -TERM "$PID"
  LEASE=""
  for i in $(seq 1 200); do
    # 0 = 没人持有租约（stepDown 把 expire_time 置 NULL ⇒ SUM 为 NULL，这里才是"已释放"）
    HELD=$(./q.sh -N -B --skip-column-names -e \
      "SELECT IFNULL(SUM(expire_time > NOW()),0) FROM z_schedule_job_leader")
    if [ "$HELD" = "0" ]; then LEASE=$(( $(date +%s%3N) - T_KILL )); break; fi
    sleep 0.05
  done
  # 每轮要过一次 docker exec + mysql 往返，所以这是"上界"不是精确值；精确的那把尺是 p13。
  echo "租约释放：≤ ${LEASE:->10000} ms（轮询含查询往返，只能当上界）"
  ./q.sh -N -B --skip-column-names -e \
    "SELECT CONCAT('  释放后那一行：count=', COUNT(*), ' owner_is_null=', ISNULL(MIN(owner)), ' 未过期条数=', IFNULL(SUM(expire_time > NOW()),0)) FROM z_schedule_job_leader"
fi

echo "=========== p20 开始（$JAR）==========="
JAR="$JAR" N="${N:-1600}" W="${W:-30}" MAS="${MAS:-20 40 80}" ./p20.sh
RC=$?
echo "=========== p20 结束 rc=$RC ==========="

echo "复原常驻实例"
mv -f "$LOG.out" "$LOG.out.boot-prev" 2>/dev/null
nohup env JAR="$JAR" PORT="$RESIDENT_PORT" ./run.sh > "$LOG.out" 2>&1 </dev/null &
UP=""
for i in $(seq 1 40); do
  grep -aq "Started ZScheduleAdminApplication" "$LOG.out" 2>/dev/null && { UP=yes; break; }
  sleep 1
done
[ -z "$UP" ] && { echo "RESTORE-FAIL: 60s 内没等到 Started"; tail -5 "$LOG.out"; exit 1; }
grep -ao "Started ZScheduleAdminApplication in .*" "$LOG.out"
NP=$(resident_pid)
echo "RESTORE pid=$NP  jar=$(tr '\0' '\n' < /proc/$NP/cmdline | grep -E '\.jar$')"
md5sum "$(tr '\0' '\n' < /proc/$NP/cmdline | grep -E '\.jar$' | head -1)"
for p in / /jobinfo/list /joblog/list; do
  printf "  %s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$RESIDENT_PORT$p")"
done
./q.sh -N -B --skip-column-names -e \
  "SELECT CONCAT('  租约：持有中的条数=', IFNULL(SUM(expire_time > NOW()),0), ' owner=', IFNULL(MAX(owner),'(null)')) FROM z_schedule_job_leader"
echo "P20_RC=$RC"
