#!/usr/bin/env bash
# P14 — 管理面鉴权（#12）在真机上的兑现面：配了 accessToken 之后，
# 除了登录口和静态外壳，其余入口必须一律 403；带上 token 才恢复可用。
# 旧实现里过滤器只挂在 /executor/*，管理面（建任务/删任务/看日志/改用户/actuator）全裸。
set -uo pipefail
cd ~/z-schedule-e2e
BASE="${BASE:-http://127.0.0.1:18086}"
# #39：鉴权面是**按构件**兑现的（#12 那条修复就在 jar 里），run.sh 的默认值只是个被复用过 4 次的
# 名字 ⇒ 复跑必须点名，否则"其余入口一律 403"可能量的是旧字节。
JAR="${JAR:?必须显式指定 JAR（#39：run.sh 的默认构件是个被复用过 4 次的名字）}"
TOK="e2e-$(date +%s)-p14"
q() { ./q.sh -N -B -e "$1"; }
code() { curl -s -m 8 -o /dev/null -w '%{http_code}' "$@"; }

OLD=$(cat app.pid 2>/dev/null || true)
[ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null && {
  kill "$OLD"; for _ in $(seq 30); do kill -0 "$OLD" 2>/dev/null || break; sleep 1; done
  kill -0 "$OLD" 2>/dev/null && { echo "FATAL: 旧进程未退, 拒绝双实例"; exit 1; }
}
rm -f logs/boot5.out
# 两个改动一次做掉（#39）：
#   · JAR 必须点名（见上面那条 guard）。
#   · token 从 ACCESS_TOKEN= 换成 Z_SCHEDULE_ACCESSTOKEN=：前者 run.sh 会把它拼成
#     `--z.schedule.access-token=…` 进 java argv，同机任何人 ps 就读走了（p22 早就走 env 这条路，
#     本脚本是漏改的那一处）。测的还是同一件事：同一个 token，只是换了搬运方式。
Z_SCHEDULE_ACCESSTOKEN="$TOK" PORT="${PORT:-18086}" JAR="$JAR" setsid nohup ./run.sh > logs/boot5.out 2>&1 < /dev/null &
echo $! > app.pid
for i in $(seq 60); do
  [ "$(code "$BASE/user/login" -X POST -H 'Content-Type: application/json' -d '{}')" != "000" ] && { echo "UP after ${i}s"; break; }
  sleep 1
done
echo "启动日志里的鉴权自述: $(grep -ao 'TokenAuthFilter.*' logs/boot5.out | head -1)"

echo
echo "### 匿名请求（不带任何 token）"
printf '%-34s %s\n' "GET /jobinfo/list"        "$(code "$BASE/jobinfo/list?start=0&length=1")"
printf '%-34s %s\n' "GET /joblog/list"         "$(code "$BASE/joblog/list?start=0&length=1")"
printf '%-34s %s\n' "GET /user/list"           "$(code "$BASE/user/list")"
printf '%-34s %s\n' "GET /dashboard/stats"     "$(code "$BASE/dashboard/stats")"
printf '%-34s %s\n' "GET /actuator/health"     "$(code "$BASE/actuator/health")"
printf '%-34s %s\n' "POST /jobinfo/add"        "$(code -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/jobinfo/add")"
printf '%-34s %s\n' "POST /executor/callback"  "$(code -X POST -d 'logId=1' "$BASE/executor/callback")"
echo "登录口必须免鉴权（否则谁也进不来）: $(code -X POST -H 'Content-Type: application/json' -d '{"username":"nobody","password":"x"}' "$BASE/user/login")  # 期望 200"
echo "静态外壳(本应用 static 是空的, 判据是 404 而不是 403): index.html=$(code "$BASE/index.html") assets/app.js=$(code "$BASE/assets/app.js")"

echo
echo "### 带 X-Access-Token 之后应当恢复可用"
printf '%-34s %s\n' "GET /jobinfo/list"  "$(code -H "X-Access-Token: $TOK" "$BASE/jobinfo/list?start=0&length=1")"
printf '%-34s %s\n' "GET /user/list"     "$(code -H "X-Access-Token: $TOK" "$BASE/user/list")"
printf '%-34s %s\n' "GET /actuator/health" "$(code -H "X-Access-Token: $TOK" "$BASE/actuator/health")"
printf '%-34s %s\n' "GET /jobinfo/list 错token" "$(code -H "X-Access-Token: wrong-$TOK" "$BASE/jobinfo/list?start=0&length=1")"
echo "带 token 建任务: $(curl -s -m 8 -H "X-Access-Token: $TOK" -H 'Content-Type: application/json' -X POST "$BASE/jobinfo/add" -d '{"jobGroup":1,"jobDesc":"e2e-p14-authed","jobCron":"","executorHandler":"demoHandler","triggerType":"FIX_RATE","fixInterval":5000,"misfireStrategy":"DO_NOTHING"}')"
echo "参数 accessToken 仍可用: $(code "$BASE/jobinfo/list?start=0&length=1&accessToken=$TOK")"

echo
echo "### 匿名写请求有没有真的落到库里（负向断言要带猎物）"
echo "库里 jobDesc 含 e2e-p14: $(q "SELECT COUNT(*) FROM z_schedule_job_info WHERE job_desc LIKE 'e2e-p14-anon%'")"
echo "带 token 建的那条:        $(q "SELECT COUNT(*) FROM z_schedule_job_info WHERE job_desc='e2e-p14-authed'")"
echo "被拦下的 403 计数:        $(grep -ac 'accessToken 不合法' logs/boot5.out)"

echo
echo "### 清理"
q "DELETE FROM z_schedule_job_info WHERE job_desc LIKE 'e2e-p14%'"
echo "剩余 p14 任务: $(q "SELECT COUNT(*) FROM z_schedule_job_info WHERE job_desc LIKE 'e2e-p14%'")"
kill "$(cat app.pid)" 2>/dev/null; for _ in $(seq 20); do kill -0 "$(cat app.pid)" 2>/dev/null || break; sleep 1; done
echo "关停后租约: $(q "SELECT CONCAT_WS(' | ','owner',IFNULL(owner,'NULL'),'expire',IFNULL(CAST(expire_time AS CHAR),'NULL')) FROM z_schedule_job_leader WHERE id=1")"
