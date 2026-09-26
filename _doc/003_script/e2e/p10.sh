#!/usr/bin/env bash
# P10 — 重启一次，钉住 accessToken 是否真的绑定进 ScheduleProperties（而不是只有 yml 里那行没人读的字）。
# token 走环境变量 Z_SCHEDULE_ACCESSTOKEN（和 DB 凭证同一套 relaxed binding），不进 java argv：
# 同机任何人 ps 就能看到启动参数，这是我自己给 run.sh 定的规矩。
set -uo pipefail
cd ~/z-schedule-e2e
TOKEN="e2e-token-$(date +%s)"
BASE="${BASE:-http://127.0.0.1:18086}"

stop_app() {
  local old
  old=$(cat app.pid 2>/dev/null || true)
  [ -n "$old" ] && kill -0 "$old" 2>/dev/null || return 0
  echo "停掉 JVM pid=$old"; kill "$old"
  for _ in $(seq 30); do kill -0 "$old" 2>/dev/null || return 0; sleep 1; done
  echo "FATAL: 旧进程没退, 拒绝双实例"; exit 1
}

start_app() {
  rm -f logs/boot2.out
  PORT="${PORT:-18086}" setsid nohup ./run.sh > logs/boot2.out 2>&1 < /dev/null &
  echo $! > app.pid
  for i in $(seq 90); do
    code=$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$BASE/jobinfo/list?start=0&length=1" 2>/dev/null || echo 000)
    case "$code" in 200|401) echo "UP after ${i}s (pid $(cat app.pid))"; return 0;; esac
    sleep 1
  done
  echo "FATAL: 90s 内端口没起来"; tail -25 logs/boot2.out; exit 1
}

probe() {
  printf '  无 token=%s  错token=%s  对的query=%s  对的header=%s   (期望 401 401 200 200)\n' \
    "$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "$BASE/executor/beat")" \
    "$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "$BASE/executor/beat?accessToken=wrong-token")" \
    "$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "$BASE/executor/beat?accessToken=$TOKEN")" \
    "$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST -H "X-Access-Token: $TOKEN" "$BASE/executor/beat")"
}

stop_app
echo "=== A. token 走环境变量 Z_SCHEDULE_ACCESSTOKEN ==="
export Z_SCHEDULE_ACCESSTOKEN="$TOKEN"
unset ACCESS_TOKEN
start_app
probe
FIRST_NOTOKEN=$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "$BASE/executor/beat")

if [ "$FIRST_NOTOKEN" = "200" ]; then
  echo "!! env 形式没生效，用 --z.schedule.access-token 复测以归因（区分"绑定不上"与"过滤器根本没装"）"
  stop_app
  unset Z_SCHEDULE_ACCESSTOKEN
  export ACCESS_TOKEN="$TOKEN"
  start_app
  probe
fi

echo "=== 管理面在配了 token 之后是否仍然全开（#12 复现）==="
for ep in "/jobinfo/list?start=0&length=1" "/joblog/list?start=0&length=1" "/user/list"; do
  printf '  GET %-38s -> %s\n' "$ep" "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$BASE$ep")"
done
echo "=== 启动日志里的过滤器痕迹 ==="
grep -aoE "TokenAuthFilter 初始化[^ ]*|Unmapped URL|Completed initialization" logs/boot2.out | sort -u | head -5
echo "=== 端口/进程自证（这个 pid 真的是 java 吗）==="
ps -o pid=,cmd= -p "$(cat app.pid)" | cut -c1-70
