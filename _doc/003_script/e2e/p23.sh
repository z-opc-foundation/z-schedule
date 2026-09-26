#!/usr/bin/env bash
# p23.sh — 运维探针面（/actuator/health 与两条探针组）在真机上的兑现
#
# 为什么单独一档：deploy/k8s/01-deployment-backend.yaml 的 livenessProbe / readinessProbe
# 指的就是 /actuator/health/{liveness,readiness}，而这两条路径在 2026-09-26 之前的构件上
# **根本不存在**：整块 actuator 配置写在 spring.actuator.* 下，而 Boot 读的是顶层 management.*，
# 于是 include / show-details 全部静默失效；而 Boot 又只在"检测到 Kubernetes 平台"时才自动建这两个组。
# 第二条更要紧：Boot 自动建的 readiness 组**不含数据源**——把 spring.datasource.url 指到没人听的
# 端口，顶层 health 正确 503 DOWN，readiness 仍回 200 UP ⇒ 照 manifest 部署，库死了 pod 继续"就绪"接流量。
#
# 这两条都不是"看一眼 200"能验的：必须**把库弄坏一次**看 readiness 会不会跟着倒。
# 但常驻服务挂在 250 上是共享的，不能拿它做破坏性实验 ⇒ 本档全程只起临时实例，
# 且第 2 臂只伤这台临时实例的 Spring 池、第 3 臂只伤它的引擎池（两臂各伤一侧，
# 才知道 readiness 守的到底是哪一侧；常驻那台一直连真库、照常跑任务）。
#
# 三臂共 19 条判据：健康臂 8 条、Spring 池故障臂 6 条、引擎池故障臂 5 条。
#
# 用法：./p23.sh                     默认验常驻实例正在跑的那个构件（从 argv 取，不认文件名）
#       JAR=z-schedule-admin-svc-xxx-exec.jar ./p23.sh   点名验另一个构件（A/B 用）
set -uo pipefail
cd "$(dirname "$0")"

PASS=0; FAIL=0; OBS=0
OUT="${OUT:-logs/p23.txt}"; : > "$OUT"
ts() { date +%H:%M:%S; }
note() { echo "[$(ts)] $*" | tee -a "$OUT"; }
ok()  { PASS=$((PASS+1)); note "  PASS $*"; }
bad() { FAIL=$((FAIL+1)); note "  FAIL $*"; }
obs() { OBS=$((OBS+1));   note "  OBS  $*"; }
# 取响应 body 的纯文本（去换行、截断），用于判形状和把原样贴进 FAIL 消息
flat() { tr -d "\n" < "$1" | head -c 200; }

BASE_PORT="${BASE_PORT:-18098}"
PORT=$(python3 -c "import socket;s=socket.socket();s.bind(('127.0.0.1',0));print(s.getsockname()[1]);s.close()")
[ -n "$PORT" ] || { echo "FATAL: 拿不到空闲端口"; exit 1; }
note "p23 用临时端口 $PORT；常驻实例在 $BASE_PORT，本档不碰它"

# 构件身份只认 argv（文件名、mtime 都不算证据）
if [ -n "${JAR:-}" ]; then
  RUNJAR="$JAR"
else
  RPID=$(ss -ltnp 2>/dev/null | grep ":$BASE_PORT " | sed "s/.*pid=\([0-9]*\).*/\1/" | head -1)
  RUNJAR=$(tr "\0" "\n" < "/proc/$RPID/cmdline" 2>/dev/null | grep -o "[^ ]*exec.jar" | head -1)
fi
if [ -z "$RUNJAR" ] || [ ! -f "$RUNJAR" ]; then
  note "FATAL: 定位不到待验构件（常驻 $BASE_PORT 没在跑？还是它不是 exec jar？）"; exit 1
fi
note "0) 待验构件 = $RUNJAR  md5=$(md5sum "$RUNJAR" | cut -d' ' -f1)"

PIDS=""
cleanup() {
  for p in $PIDS; do kill "$p" 2>/dev/null; done
  sleep 2
  note "  收尾：临时实例已停（残留监听=$(ss -ltn 2>/dev/null | grep -c ":$PORT ")）"
}
trap cleanup EXIT INT TERM

# boot <额外参数> —— 起一台临时实例并等 Started；成功时把 java pid 追加进 PIDS，并留在 LASTPID 里
# （LASTPID 是给"argv 里到底有没有我那条 override"这种自证用的——见 3.2）
BOOTLOG=""
LASTPID=""
boot() {
  BOOTLOG="logs/p23_instance_$(date +%s).out"
  local UP="" i
  APP_ARGS="$*" JAR="$RUNJAR" PORT="$PORT" setsid nohup ./run.sh > "$BOOTLOG" 2>&1 < /dev/null &
  for i in $(seq 1 60); do
    grep -aq "Started ZScheduleAdminApplication" "$BOOTLOG" 2>/dev/null && { UP=yes; break; }
    sleep 1
  done
  local pid
  pid=$(ss -ltnp 2>/dev/null | grep ":$PORT " | sed "s/.*pid=\([0-9]*\).*/\1/" | head -1)
  [ -n "$pid" ] && PIDS="$PIDS $pid"
  LASTPID="$pid"
  [ "$UP" = "yes" ] && [ -n "$pid" ]
}
stopall() { for p in $PIDS; do kill "$p" 2>/dev/null; done; sleep 3; PIDS=""; }
code() { curl -s -m 25 -o "$2" -w "%{http_code}" "http://127.0.0.1:$PORT$1"; }
BODY=logs/p23_body.txt

# ── 1) 健康臂：探针面存在、暴露面是白名单、明细不铺给匿名 ───────────────────────
note "--- 1) 库正常时 ---"
if boot ""; then
  ok "1.1 临时实例起来了（不带任何 override，验的就是构件里那份 application.yml）"
else
  bad "1.1 临时实例没起来，后面全废（tail 见下）"; tail -6 "$BOOTLOG" | tee -a "$OUT"; exit 1
fi

H=$(code /actuator/health "$BODY"); SHAPED=$(flat "$BODY")
if [ "$H" = "200" ] && [ "${SHAPED#*groups}" != "$SHAPED" ] \
   && [ "${SHAPED#*liveness}" != "$SHAPED" ] && [ "${SHAPED#*readiness}" != "$SHAPED" ]; then
  ok "1.2 /actuator/health 200 且把两个探针组报了出来：$SHAPED"
else
  bad "1.2 期望 /actuator/health 200 且 body 含 groups[liveness,readiness]，实得 code=$H body=$SHAPED"
fi

I=$(code /actuator/info "$BODY")
if [ "$I" = "200" ]; then
  ok "1.3 /actuator/info 200（include 生效；它写作 spring.actuator.* 时这一格是 404）"
else
  bad "1.3 /actuator/info 期望 200，实得 $I ⇒ 暴露面的 include 又没生效"
fi

for u in liveness readiness; do
  C=$(code "/actuator/health/$u" "$BODY")
  if [ "$C" = "200" ]; then
    ok "1.4 /actuator/health/$u 200（k8s manifest 探的就是这条，离集群平台也得存在）"
  else
    bad "1.4 /actuator/health/$u 期望 200，实得 $C body=$(flat "$BODY")"
  fi
done

# 白名单的反向对照：env/metrics 不在 include 里，必须 404；否则 include 是摆设
for u in env metrics; do
  C=$(code "/actuator/$u" "$BODY")
  if [ "$C" = "404" ]; then
    ok "1.5 /actuator/$u 404（include 真当白名单用）"
  else
    bad "1.5 /actuator/$u 期望 404，实得 $C ⇒ 暴露面比配置写的宽"
  fi
done

code /actuator/health "$BODY" >/dev/null
if grep -aq components "$BODY"; then
  bad "1.6 匿名拿到了组件明细（show-details 应为 when-authorized）：$(flat "$BODY")"
else
  ok "1.6 匿名只看到 status/groups，拿不到组件明细"
fi

# ── 2) 故障臂：只伤这台临时实例的 Spring 池，看 readiness 会不会跟着倒 ────────────
note "--- 2) 把 spring.datasource 指到没人听的 33999（引擎池仍指向真库） ---"
stopall
if boot "--spring.datasource.url=jdbc:mysql://127.0.0.1:33999/nope?connectTimeout=2000 --spring.datasource.druid.max-wait=3000"; then
  ok "2.1 故障臂起来了（这池坏掉不影响启动，正是探针该说话的场合）"
else
  bad "2.1 故障臂没起来，这一组无法判定（tail 见下）"; tail -6 "$BOOTLOG" | tee -a "$OUT"; exit 1
fi

C=$(code /actuator/health "$BODY")
if [ "$C" = "503" ]; then ok "2.2 顶层 /actuator/health 503 DOWN（它一直是对的，下面那条才是缺陷）"
else bad "2.2 顶层 health 期望 503，实得 $C body=$(flat "$BODY")"; fi

C=$(code /actuator/health/readiness "$BODY")
if [ "$C" = "503" ]; then
  ok "2.3 readiness 503 DOWN ⇒ 数据源确实在 readiness 的判定集里"
else
  bad "2.3 readiness 期望 503，实得 $C body=$(flat "$BODY")"
  note "        这就是原缺陷的形状：Boot 自动建的 readiness 组不含 db，库死了 pod 仍报"就绪""
fi

# 2.4/2.5 共用这一次读取的 body：判 UP 之外还要看它的明细里有没有 db
code /actuator/health/liveness "$BODY" >/dev/null
if grep -aq '"status":"UP"' "$BODY"; then
  ok "2.4 liveness 仍 UP ⇒ 库抖动不把进程重启掉（两组的分工成立）"
else
  bad "2.4 liveness 期望 UP（不该含 db），实得 $(flat "$BODY")"
fi

# 互补的另一层：liveness 的明细里不许出现 db 组件——否则 2.4 的 UP 可能只是它没被判过
# 这一支原先只 grep `"db"`，于是**空 body 也算绿**（旧构件上 404、body 什么都没有，它就 PASS 了，
# 而那正是它该说话的时候）。现在先要求这一份 body 真的答了状态，再去判里面有没有 db。
if ! grep -aq '"status"' "$BODY"; then
  bad "2.5 liveness 那一份 body 里连 status 都没有，无从判定分组：$(flat "$BODY")"
elif grep -aq '"db"' "$BODY"; then
  bad "2.5 liveness 明细里出现了 db（两组分工被改坏）：$(flat "$BODY")"
else
  ok "2.5 liveness 答的是 status 且明细里没有 db，与 readiness 的判定集不同形"
fi

# 阳性对照总闸：故障臂上 readiness 与 liveness 必须给出**不同**的状态码，
# 否则要么两组同形（那 readiness 含 db 也白写），要么本档根本没把库弄坏过
R=$(code /actuator/health/readiness /dev/null)
L=$(code /actuator/health/liveness /dev/null)
if [ "$R" != "$L" ]; then
  ok "2.6 同一故障下 readiness=$R 而 liveness=$L，两码不同 ⇒ 这档确实分得开两组"
else
  bad "2.6 readiness 与 liveness 同为 $R：判不出分工（要么没造出故障，要么两组同形）"
fi

# ── 3) 引擎池臂：只坏 starter 自建的 dataSourceSchedule，Spring 池仍指真库 ──────────
# 这一臂才是产品本体那一侧：调度引擎读写任务用的是 `dataSourceSchedule`（`z.base.db.schedule.*`），
# 而 2) 坏掉的是 Spring 那个空转池。如果 readiness 只认后者，第 2 节全绿也等于没守住——
# 实测结论（构件 7ea14eb，show-details=always 才看得见子项）：`db` 是 composite，
# 两个子项 `dataSource` / `dataSourceSchedule` 都在里面，引擎池单独坏时 readiness 一样 503。
note "--- 3) 只坏引擎池（--z.base.db.schedule.port=33999），Spring 池仍指真库 ---"
stopall
if boot "--z.base.db.schedule.port=33999 --z.base.db.schedule.max-wait=3000 --management.endpoint.health.show-details=always"; then
  ok "3.1 引擎池臂起来了"
else
  bad "3.1 引擎池臂没起来，这一组无法判定（tail 见下）"; tail -6 "$BOOTLOG" | tee -a "$OUT"; exit 1
fi
# 3.2 先证这条 override 真的进了 argv——上一轮就是因为 ssh 外层引号把 `--spring.datasource.url`
#     带成了字面 `"…"`，override 从未应用，我差点据此写下"spring.datasource.* 是惰性的"。
NA=$(tr "\0" "\n" < "/proc/$LASTPID/cmdline" 2>/dev/null | grep -c 'z\.base\.db\.schedule\.port=33999')
if [ "$NA" = "1" ]; then
  ok "3.2 override 确实在 argv 里（1 条，不是被引号吃掉的字面量）"
else
  bad "3.2 argv 里 $NA 条 override ⇒ 本臂根本没把引擎池弄坏，下面三条不作数"
fi
# jget <点分路径>：从上一次取回的 body 里按路径取值。body 是空的（404 就是这么回事）也要老实
# 报一个 - 而不是抛 traceback——红消息必须可读，否则"实得"那一格是空的，读的人分不出
# "组件是 UP"和"这条路径根本不存在"。
jget() { python3 - "$BODY" "$1" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print('-'); raise SystemExit(0)
for k in sys.argv[2].split('.'):
    d = d.get(k) if isinstance(d, dict) else None
    if d is None:
        break
print('-' if d is None else (d if isinstance(d, str) else 'OBJ'))
PY
}

R=$(code /actuator/health/readiness "$BODY")
SCHED=$(jget components.db.components.dataSourceSchedule.status)
SPRING=$(jget components.db.components.dataSource.status)
if [ "$R" = "503" ]; then
  ok "3.3 引擎池坏了 readiness 也 503 ⇒ readiness 守的确实是调度引擎那一侧（不只是 Spring 那个空转池）"
else
  bad "3.3 期望 readiness 503，实得 $R body=$(flat "$BODY")"
fi
if [ "$SCHED" = "DOWN" ] && [ "$SPRING" = "UP" ]; then
  ok "3.4 db 的两个子项分开倒了：dataSourceSchedule=$SCHED 而 dataSource=$SPRING ⇒ 这一臂坏的只有引擎池"
else
  bad "3.4 期望 dataSourceSchedule=DOWN 且 dataSource=UP，实得 $SPRING / $SCHED（两池同倒或压根没分开判，readiness 的红就说明不了是什么）"
fi
L=$(code /actuator/health/liveness "$BODY")
LDB=$(jget components.db.status)
if [ "$L" = "200" ] && [ "$LDB" = "-" ]; then
  ok "3.5 引擎池坏了 liveness 仍 200 且明细里没有 db ⇒ 库坏了进程不会被重启掉（两组的分工在这一臂也成立）"
else
  bad "3.5 期望 liveness 200 且无 db 组件，实得 code=$L db=$LDB body=$(flat "$BODY")"
fi

echo "===================== p23 结果：PASS=$PASS FAIL=$FAIL OBS=$OBS（全文 $OUT）=====================" | tee -a "$OUT"
[ "$FAIL" = "0" ]
