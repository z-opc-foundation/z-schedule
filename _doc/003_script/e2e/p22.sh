#!/bin/bash
# p22.sh — #19 的「登录态」+#28 的「铸权闸」+「按 jobGroup 分权」在真机上到底兑现到哪一步（250，MySQL 8，两个实例两种模式）
#
# 为什么必须上真机、H2 那 297 例不够：#19/#28 改的都是**跨进程边界的凭证语义**——登录发什么、
# 过滤器认什么、controller 认哪种凭证、改角色/删账号之后旧令牌还在不在。单测里的过滤器与签发方
# 是我自己 new 的对象，真机上是 Spring 装配出来的两个 bean；这两件事的差别在 #17/#18 已经付过学费
# （「测试里接上了、生产里没人接线」）。
#
# 为什么要**两个实例**：会话撤销（logout / 改角色 / 删账号）在未配置 accessToken 的演示模式下
# **观察不到**——令牌一旦被撤销，出示它就等于没出示，而「没出示」在演示模式下是放行。
# 于是「撤销生效」与「撤销没生效」在 18098 上返回一模一样。唯一分开两者的办法是把管理面
# 关起来再验，所以：
#   S) 临时关门实例（bind(0) 挑的空闲口，accessToken 只走环境变量）→ 先起，用它种账号
#   A) 常驻演示实例（18098，无 accessToken）→ 令牌形状 + 角色闸 + 铸权闸 + 演示模式的可见性
#   B) 那台关门实例 → 三种撤销各钉一条 + 铸权闸的另一半（共享密钥/ADMIN 会话铸得出来）
#
# 为什么关门实例必须**先起**（这一版把顺序反了过来）：#28 之后匿名的 /user/add 铸不出 ADMIN 了，
# 而 A 段一开始就需要库里有 ADMIN + NORMAL 各一个。于是"第一个管理员从哪来"这个 runbook 问题
# 在 S 段被真实走通了一次：握有 accessToken 的人铸出来（另一条路是直接写库，见 README 已知坑）。
#
# 口令：脚本里的 p22-probe-only 是**探针账号**的口令（跑完连账号一起删），不是数据库口令；
# 数据库口令仍由 mysql.env 经环境变量注入，本脚本一次都不打印它。
# S 段用「ps 里 access-token= 出现几条」判断 accessToken 有没有漏进 argv——只数条数，
# 绝不 grep 它的值（这条脚本的输出会落进日志文件）。
#
# D 段与 E 段量的是两张表：D 判 z_schedule_job_info.job_group（任务那一行），E 判
# z_schedule_job_log.job_group（日志那一行）。日志侧没有别的依据，所以 E 先钉写侧（派发行
# 带的是任务真正的组、库里没任务就不落行），再钉读侧；读侧的期望序由 MySQL 现算，与 Java
# 侧的合并排序对拍，而不是把 id 写死在脚本里。
set -u
cd "$(dirname "$0")"

RESIDENT_PORT="${RESIDENT_PORT:-18098}"
JAR_EXPECT="${JAR_EXPECT:-z-schedule-admin-svc-a16473a-exec.jar}"
PROBE_PW="p22-probe-only"
LOG_DIR="${LOG_DIR:-logs}"
OUT="$LOG_DIR/p22.txt"
mkdir -p "$LOG_DIR"; : > "$OUT"

PASS=0; FAIL=0; OBS=0
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT"; }
ok()  { PASS=$((PASS+1)); say "  PASS $*"; }
bad() { FAIL=$((FAIL+1)); say "  FAIL $*"; }
obs() { OBS=$((OBS+1));   say "  观察 $*"; }
q()   { ./q.sh -N -B --skip-column-names "$@" 2>&1; }

# req <method> <url> [json-body]  ⇒  "http_code<TAB>body(≤220 字符)"
# 状态码和响应体都要：只判 200 会把「403 但页面照样渲染」混过去，只判 body 会把 curl
# 自己的失败当成应用回答（坑 19）。
req() {
  local m="$1" u="$2" d="${3:-}" body code
  if [ -n "$d" ]; then
    body=$(curl -s -m 15 -X "$m" -H 'Content-Type: application/json' -d "$d" -w $'\n%{http_code}' "$u")
  else
    body=$(curl -s -m 15 -X "$m" -w $'\n%{http_code}' "$u")
  fi
  code=${body##*$'\n'}
  printf '%s\t%s' "$code" "$(printf '%s' "$body" | sed '$d' | tr -d '\n' | cut -c1-220)"
}
content_of() { printf '%s' "$1" | sed -n 's/.*"content":"\([^"]*\)".*/\1/p' | head -1; }
msg_of()     { printf '%s' "$1" | sed -n 's/.*"msg":"\([^"]*\)".*/\1/p' | head -1; }
login() {  # login <port> <user> ⇒ 令牌（失败则空）
  local r; r=$(req POST "http://127.0.0.1:$1/user/login" "{\"username\":\"$2\",\"password\":\"$PROBE_PW\"}")
  content_of "$r"
}
# add_user <port> <user> <role> <cred> ⇒ "code<TAB>body"；cred 传空串＝匿名
add_user() {
  local cred="$4"
  if [ -z "$cred" ]; then
    req POST "http://127.0.0.1:$1/user/add" "{\"username\":\"$2\",\"password\":\"$PROBE_PW\",\"role\":\"$3\"}"
  else
    req POST "http://127.0.0.1:$1/user/add?accessToken=$cred" "{\"username\":\"$2\",\"password\":\"$PROBE_PW\",\"role\":\"$3\"}"
  fi
}
rowcount() { q -e "SELECT COUNT(*) FROM z_schedule_user WHERE username='$1'" | tr -d '[:space:]'; }
usersql() { q -e "SELECT id,username,role FROM z_schedule_user WHERE username LIKE 'p22\_%' ORDER BY id"; }
uid()     { q -e "SELECT id FROM z_schedule_user WHERE username='$1'" | tr -d '[:space:]'; }
gid()     { q -e "SELECT id FROM z_schedule_job_group WHERE app_name='$1'" | tr -d '[:space:]'; }
jid()     { q -e "SELECT id FROM z_schedule_job_info WHERE job_desc='$1' ORDER BY id DESC LIMIT 1" | tr -d '[:space:]'; }
logrows() { q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_id=$1" | tr -d '[:space:]'; }
status_of() { q -e "SELECT trigger_status FROM z_schedule_job_info WHERE id=$1" | tr -d '[:space:]'; }
# raw <method> <url> [body] ⇒ "http_code<TAB>完整响应体"（不截断）。
# D 段要在一份列表里找某一行的 id，而 req 把体切到 220 字符——"没找到"可能只是被切掉了，
# 那会把一次成功的裁剪读成失败。需要看全文的用 raw，日志里另外打截断版。
raw() {
  local m="$1" u="$2" d="${3:-}" body code
  if [ -n "$d" ]; then
    body=$(curl -s -m 15 -X "$m" -H 'Content-Type: application/json' -d "$d" -w $'\n%{http_code}' "$u")
  else
    body=$(curl -s -m 15 -X "$m" -w $'\n%{http_code}' "$u")
  fi
  code=${body##*$'\n'}
  printf '%s\t%s' "$code" "$(printf '%s' "$body" | sed '$d' | tr -d '\n')"
}
# 列表里有没有这一行：后面必须不是数字，否则 "id":4 会命中 "id":41
has_row() { printf '%s' "$1" | grep -Eq "\"id\":$2([^0-9]|$)"; }

echo "===================== p22 登录态 + 铸权闸真机验 =====================" | tee -a "$OUT"

echo "--- 0) 构件身份：只认 argv + md5，不认文件名 ---" | tee -a "$OUT"
CAND=$(pgrep -af "[j]ava" | grep -- "-jar [^ ]*z-schedule-admin" | grep -- "--server.port=$RESIDENT_PORT" | head -1 || true)
[ -z "$CAND" ] && { bad "0.1 端口 $RESIDENT_PORT 上没有 java 进程，A 段无从开始"; exit 1; }
RUNJAR=$(printf '%s' "$CAND" | grep -oE '\-jar [^ ]+' | awk '{print $2}')
say "  0.1 常驻实例 pid=$(printf '%s' "$CAND" | awk '{print $1}') jar=$RUNJAR md5=$(md5sum "$RUNJAR" | awk '{print $1}')"
[ "$(basename "$RUNJAR")" = "$JAR_EXPECT" ] && ok "0.2 跑的是预期构件 $JAR_EXPECT" \
  || { bad "0.2 预期 $JAR_EXPECT，实际 $(basename "$RUNJAR")"; exit 1; }
# 阳性对照：#19 的类必须在**这个文件**里；#28 的字符串常量也得在（否则 A.1 红的是旧字节）
unzip -p "$RUNJAR" 'BOOT-INF/lib/z-schedule-spring-boot-starter*.jar' > "$LOG_DIR/p22-starter.jar"
if unzip -l "$LOG_DIR/p22-starter.jar" 'com/zifang/z/schedule/web/auth/*' | grep -q LoginSessionStore; then
  ok "0.3 嵌套 starter 里有 LoginSessionStore.class ⇒ #19 的字节码在跑的构件里"
else
  bad "0.3 嵌套 starter 里没有 auth/ 包 ⇒ 这个 jar 不含 #19，读数无意义"; rm -f "$LOG_DIR/p22-starter.jar"; exit 1
fi
mkdir -p "$LOG_DIR/p22-cls" && unzip -o -q "$LOG_DIR/p22-starter.jar" -d "$LOG_DIR/p22-cls" 'com/zifang/z/schedule/web/*'
if javap -c -p -classpath "$LOG_DIR/p22-cls" com.zifang.z.schedule.web.filter.TokenAuthFilter | grep -q 'z.schedule.fullAuthority'; then
  ok "0.4 TokenAuthFilter 的常量池里有 z.schedule.fullAuthority ⇒ #28 的「这次是共享密钥放进来的」标记在跑的构件里"
else
  bad "0.4 构件里没有 #28 的标记属性 ⇒ A.1 会红在旧语义上，读数无意义"; exit 1
fi
if javap -c -p -classpath "$LOG_DIR/p22-cls" com.zifang.z.schedule.web.auth.GroupAccess | grep -q 'z_schedule_user.permission'; then
  ok "0.4b GroupAccess 的常量池里有那一列的列名 ⇒ D 段量的这道按组收口在跑的构件里（不是旧字节在演示「已经收口了」）"
else
  bad "0.4b 构件里没有 GroupAccess 的那句理由串 ⇒ D 段会红在旧语义上，读数无意义"; exit 1
fi
if javap -p -classpath "$LOG_DIR/p22-cls" com.zifang.z.schedule.web.auth.LoginSession | grep -q 'permits(int)'; then
  ok "0.4c LoginSession 有 permits(int) ⇒ 身份带着 permission 这一维"
else
  bad "0.4c LoginSession 没有 permits ⇒ 这一列还没进身份，D 段无从收口"; exit 1
fi
# E 段的构件身份闸：newestAcross（逐组合并）与 effectiveLimit（合并方必须按同一个数裁）都是
# a16473a 才有的符号。少了这一眼，E.5 会拿着旧字节的"全局一页再裁"读出一个形状，而那条写法
# 正是 E.5 要否证的。
if javap -p -classpath "$LOG_DIR/p22-cls" com.zifang.z.schedule.web.controller.JobLogController | grep -q newestAcross; then
  ok "0.4d JobLogController 有 newestAcross ⇒ E 段量的是逐组合并那一版，不是旧的全局一页"
else
  bad "0.4d 构件里没有 newestAcross ⇒ E 段会红在旧语义上，读数无意义"; exit 1
fi
if javap -p -classpath "$LOG_DIR/p22-cls" com.zifang.z.schedule.web.service.JobLogService | grep -q effectiveLimit; then
  ok "0.4e JobLogService 有 effectiveLimit ⇒ 上限收口只剩一处，合并方与查询方用的是同一个数"
else
  bad "0.4e JobLogService 没有 effectiveLimit ⇒ 上限还是两处各写各的，E 段的 limit 断言无意义"; exit 1
fi
rm -rf "$LOG_DIR/p22-starter.jar" "$LOG_DIR/p22-cls"
# 干净起点：上一轮崩在中间留下的行会让 S.9/A.3 的行数断言毫无意义
q -e "DELETE FROM z_schedule_user WHERE username LIKE 'p22\_%'" >/dev/null
# D 段的探针数据也一起清：它按 job_desc/app_name 取回自己那两行，多留一行就取错
q -e "DELETE FROM z_schedule_job_log WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_desc LIKE 'p22\_%')" >/dev/null
q -e "DELETE FROM z_schedule_job_info WHERE job_desc LIKE 'p22\_%'" >/dev/null
q -e "DELETE FROM z_schedule_job_group WHERE app_name LIKE 'p22\_%'" >/dev/null
[ "$(q -e "SELECT COUNT(*) FROM z_schedule_user WHERE username LIKE 'p22\_%'" | tr -d '[:space:]')" = "0" ] \
  && ok "0.5 探针账号已从库里清干净（LIKE 用了反斜杠转义，p22x 这类名字不会被误删）" \
  || { bad "0.5 清场后仍有残留：$(usersql | tr '\n' ' ')"; exit 1; }

echo "--- S) 临时关门实例：先起它，因为只有它能种第一个管理员 ---" | tee -a "$OUT"
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
ss -ltn 2>/dev/null | grep -q ":$PORT " && { bad "S.0 bind(0) 挑到的 $PORT 已被占"; exit 1; }
say "  S.0 端口 $PORT 由 bind(0) 现挑（不复用 18086 台架口，也不复用 18098 常驻口，见坑 14）"
SECRET=$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')
# Z_SCHEDULE_ACCESSTOKEN → z.schedule.accessToken：Spring 比较属性名时去掉 - 与大小写，
# 所以 accesstoken 与 access-token 的 uniform 形式相同。走这条路就不必用 run.sh 的
# ACCESS_TOKEN（它把口令拼进 --z.schedule.access-token=，同机任何人 ps 可见）。
setsid env Z_SCHEDULE_ACCESSTOKEN="$SECRET" JAR="$JAR_EXPECT" PORT="$PORT" \
  ./run.sh > "$LOG_DIR/p22_b_instance.out" 2>&1 < /dev/null &
disown || true
for i in $(seq 1 60); do
  sleep 1
  grep -q "Started ZScheduleAdminApplication" "$LOG_DIR/p22_b_instance.out" 2>/dev/null && break
done
BPID=$(pgrep -af "[j]ava" | grep -- "-jar [^ ]*$JAR_EXPECT" | grep -- "--server.port=$PORT" | awk '{print $1}' | head -1)
[ -z "$BPID" ] && { bad "S.1 临时实例没起来（tail 见下）"; tail -6 "$LOG_DIR/p22_b_instance.out" | tee -a "$OUT"; exit 1; }
ok "S.1 临时实例 pid=$BPID 端口 $PORT（同一份 run.sh、同一个库，只差一个环境变量）"
[ "$(pgrep -af "[j]ava" | grep -c 'access-token=' || true)" = "0" ] && ok "S.2 argv 里没有 access-token ⇒ 这条部署路径不把口令泄给同机 ps" \
  || bad "S.2 口令进了 argv"

BB="http://127.0.0.1:$PORT"
NOAUTH=$(req GET "$BB/jobinfo/list?start=0&length=1")
case "$NOAUTH" in
  403*) ok "S.3 门真的关上了 ⇒ 后面的「撤销」与「铸权」才有观察面" ;;
  *) bad "S.3 关门失败：$(printf '%s' "$NOAUTH" | cut -f1)（环境变量那套绑定不成立？S/B 段全部读数作废）"; exit 1 ;;
esac
ST=$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$SECRET")
case "$ST" in 200*) ok "S.4 共享密钥进得去（它不带身份，因此不受角色闸约束——这是设计，不是漏网）";; *) bad "S.4 共享密钥进不去：$(printf '%s' "$ST" | cut -f1)";; esac
WT=$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=${SECRET}nope")
case "$WT" in 403*) ok "S.5 错密钥仍 403 ⇒ S.4 通过不是因为「任何串都放行」";; *) bad "S.5 错密钥竟然 $(printf '%s' "$WT" | cut -f1)";; esac

SEED_A=$(add_user "$PORT" p22_admin ADMIN "$SECRET")
SEED_N=$(add_user "$PORT" p22_normal NORMAL "$SECRET")
case "$SEED_A" in *'"code":200'*) ok "S.6 第一个 ADMIN 由**共享密钥**铸出来（#28 之后这就是关门实例的唯一种子路；另一条是直接写库）";; *) bad "S.6 共享密钥铸 ADMIN 被拒：$(printf '%s' "$SEED_A" | cut -f1) $(msg_of "$SEED_A")";; esac
case "$SEED_N" in *'"code":200'*) ok "S.7 同理种出 NORMAL 探针账号";; *) bad "S.7 建 NORMAL 失败：$(msg_of "$SEED_N")" ;; esac
[ "$(usersql | grep -c .)" = "2" ] && ok "S.8 两行真落库：$(usersql | tr '\n' ' ')" || bad "S.8 账号数不是 2：$(usersql | tr '\n' ' ')"
PWCOL=$(q -e "SELECT password FROM z_schedule_user WHERE username='p22_admin'" | tr -d '[:space:]')
MD5PW=$(printf '%s' "$PROBE_PW" | md5sum | awk '{print $1}')
[ "$PWCOL" = "$PROBE_PW" ] && bad "S.9 库里存的是明文口令！"
if [ "$PWCOL" = "$MD5PW" ]; then
  ok "S.9 password 列 = md5(登录口令)（32 位十六进制，不是明文）⇒ 登录比对的是散列相等，库被读走看不到明文"
elif printf '%s' "$PWCOL" | grep -Eq '^[0-9a-f]{32}$'; then
  obs "S.9 password 列是 32 位十六进制但**不等于** md5(探针口令)：$PWCOL ⇒ 建号路径与登录路径用的散列函数不同，S.6 建的账号会永远登不上"
else
  bad "S.9 password 列既不是明文也不是十六进制散列（长度 ${#PWCOL}）：认不出这是什么"
fi
obs "S.9b 这一列是**无盐** MD5：同口令必然同散列，能整库反查彩虹表。换 bcrypt/argon2 是独立的一格，#28 没有动它"
ANON_CLOSED=$(add_user "$PORT" p22_gate NORMAL "")
say "  S.10 关门实例上的匿名 /user/add ⇒ $(printf '%s' "$ANON_CLOSED" | cut -f1) $(msg_of "$ANON_CLOSED")"
case "$ANON_CLOSED" in
  403*不合法*) ok "S.10 匿名在关门实例上连门都进不来（403 来自过滤器的「accessToken 不合法」，不是 controller 的铸权闸）⇒ 铸权闸真正补的是演示模式那台敞开的" ;;
  *) bad "S.10 匿名请求形状不对：$(printf '%s' "$ANON_CLOSED" | cut -f1) $(msg_of "$ANON_CLOSED")" ;;
esac
[ "$(rowcount p22_gate)" = "0" ] && ok "S.11 被拒的那次一个字都没写进库（403 不是「先写再告诉你不该写」）" || bad "S.11 被拒之后库里竟有 p22_gate"

echo "--- A) 常驻实例：演示模式（未配置 accessToken）---" | tee -a "$OUT"
BASE="http://127.0.0.1:$RESIDENT_PORT"
A_ADMIN=$(add_user "$RESIDENT_PORT" p22_hate ADMIN "")
say "  A.1 演示模式下匿名铸 ADMIN ⇒ $(printf '%s' "$A_ADMIN" | cut -f1) | $(msg_of "$A_ADMIN")"
case "$A_ADMIN" in
  *'"code":500'*accessToken*)
    ok "A.1 演示模式下匿名铸不出 ADMIN 了：拒绝理由点名要 accessToken（改之前这条是 200，库里真多出一个管理员——p22 上一版把这句话写成 PASS 的 A.1，就是 #28 那格）" ;;
  *) bad "A.1 匿名铸 ADMIN 没被铸权闸拦住：$(printf '%s' "$A_ADMIN" | cut -f1) | $(msg_of "$A_ADMIN")" ;;
esac
[ "$(rowcount p22_hate)" = "0" ] && ok "A.2 被拦的这次没有落库（p22_hate 行数 0）⇒ 拒绝发生在写库之前" || bad "A.2 拒绝理由给了，账号却也建出来了"
A_CTL=$(add_user "$RESIDENT_PORT" p22_ctl NORMAL "")
case "$A_CTL" in
  *'"code":200'*) ok "A.3 阳性对照：同一条匿名请求只要 role=NORMAL 就照样建得成 ⇒ A.1 的红不是「/user/add 整个坏了」" ;;
  *) bad "A.3 匿名建 NORMAL 竟然失败：$(msg_of "$A_CTL") ⇒ 分不清是闸在起作用还是 add 本身坏了" ;;
esac
[ "$(usersql | grep -c .)" = "3" ] && ok "A.4 此刻库里正好 3 行：$(usersql | tr '\n' ' ')" || bad "A.4 行数不是 3：$(usersql | tr '\n' ' ')"

T_ADMIN=$(login "$RESIDENT_PORT" p22_admin)
# 形状 = base64url(32 字节) = 43 字符（不是十六进制的 64 位；上一版在这里断错了自己红）
printf '%s' "$T_ADMIN" | grep -Eq '^[A-Za-z0-9_-]{43}$' \
  && ok "A.5 登录换回 base64url 43 字符（= 32 字节 = 256 bit 熵，字符集里没有 + / =）" \
  || bad "A.5 令牌形状不对：长度=$(printf '%s' "$T_ADMIN" | wc -c | tr -d ' ') 前缀=$(printf '%s' "$T_ADMIN" | cut -c1-6)"
[ -n "$T_ADMIN" ] && [ "$T_ADMIN" != "p22_admin" ] && ok "A.6 令牌不是用户名（改之前 login 返回的就是它，而 /user/list 公开列得出用户名）" \
  || bad "A.6 令牌仍是用户名 ⇒ 等于没鉴权"
[ "$T_ADMIN" != "$(printf '%s' "p22_admin" | md5sum | awk '{print $1}')" ] \
  && [ "$T_ADMIN" != "$(printf '%s' "p22_admin$PROBE_PW" | md5sum | awk '{print $1}')" ] \
  && ok "A.7 令牌不是 md5(用户名) / md5(用户名+口令) 这类能推算出来的定值" || bad "A.7 令牌能从账号信息推出来"
T2=$(login "$RESIDENT_PORT" p22_admin)
[ -n "$T2" ] && [ "$T2" != "$T_ADMIN" ] && ok "A.8 同账号第二次登录得到**不同**令牌（每次签发都新，不是把用户映射成一串常量）" \
  || bad "A.8 二次登录没拿到新令牌"
BAD=$(req POST "$BASE/user/login" "{\"username\":\"p22_admin\",\"password\":\"wrong-$PROBE_PW\"}")
case "$BAD" in
  *"$T_ADMIN"*) bad "A.9 错口令回的内容里出现了正确令牌" ;;
  *'"code":200'*) bad "A.9 错口令竟然 200：$BAD" ;;
  *) ok "A.9 错口令被拒（http=$(printf '%s' "$BAD" | cut -f1)）" ;;
esac

LIST_OK=$(req GET "$BASE/jobinfo/list?start=0&length=1&accessToken=$T_ADMIN")
say "  A.10 出示会话令牌打 /jobinfo/list ⇒ $(printf '%s' "$LIST_OK" | cut -f1)"
case "$LIST_OK" in 200*'"code":200'*) ok "A.11 会话令牌本身就是凭证（过得了过滤器并拿到列表）";; *) bad "A.11 会话令牌过不了：$LIST_OK";; esac

T_NORM=$(login "$RESIDENT_PORT" p22_normal)
ADD_N=$(add_user "$RESIDENT_PORT" p22_gate NORMAL "$T_NORM")
ADD_A=$(add_user "$RESIDENT_PORT" p22_gate NORMAL "")
say "  A.12 NORMAL 会话 /user/add ⇒ $(printf '%s' "$ADD_N" | cut -f1) | $(msg_of "$ADD_N")"
say "  A.13 同一条请求、不出示身份 ⇒ $(printf '%s' "$ADD_A" | cut -f1) | $(msg_of "$ADD_A")"
case "$ADD_N" in
  403*) case "$ADD_A" in
          *'"code":200'*) ok "A.14 角色闸有牙：普通会话 403、匿名同一条 200。两者只差「有没有自报身份」，所以红不是接口坏了" ;;
          *) bad "A.14 匿名那次也非 200（$(printf '%s' "$ADD_A" | cut -f1)）⇒ 分不清是闸在起作用还是 add 本身坏了" ;;
        esac ;;
  *) bad "A.14 普通会话没被拒：$ADD_N" ;;
esac
printf '%s' "$ADD_N" | grep -q '需要管理员角色' && ok "A.15 拒绝理由写的是「需要管理员角色」，不是笼统一句 403" || bad "A.15 拒绝理由缺失"
MINT_A=$(add_user "$RESIDENT_PORT" p22_mint ADMIN "$T_ADMIN")
case "$MINT_A" in
  *'"code":200'*) [ "$(rowcount p22_mint)" = "1" ] && ok "A.16 ADMIN 会话照样铸得出 ADMIN（闸只收窄「谁能造管理员」，没把管理员自己关在门外）" \
                    || bad "A.16 说成功却没落库" ;;
  *) bad "A.16 管理员会话铸 ADMIN 被误伤：$(msg_of "$MINT_A")" ;;
esac
case "$(req GET "$BASE/user/list?accessToken=$T_NORM")" in
  200*) ok "A.17 普通会话读 /user/list 不受闸约束（闸只在建/改/删三口 + 铸管理员；读侧收口是另一格，别把这条当已交付）" ;;
  *) bad "A.17 读侧被误伤：角色闸扩到列表了" ;;
esac

LO=$(req POST "$BASE/user/logout?accessToken=$T_NORM" '{}')
say "  A.18 logout ⇒ $(printf '%s' "$LO" | cut -f1) | $(msg_of "$LO")"
case "$LO" in *'"code":200'*) ok "A.19 logout 返回成功";; *) bad "A.19 logout 失败：$(printf '%s' "$LO" | cut -f2)" ;; esac
ADD_AFTER=$(add_user "$RESIDENT_PORT" p22_gate2 NORMAL "$T_NORM")
obs "A.20 演示模式下「撤销」观察不到：登出后同一条普通令牌打 /user/add 变成 $(printf '%s' "$ADD_AFTER" | cut -f1)"
obs "A.20b 原因：撤销 = 解析不出身份 = 等同于没出示凭证，而演示模式对没出示是放行。⇒ 撤销类断言只能在关了门的实例上量（B 段），这条不是缺陷而是量具的可见性"
LOA=$(req POST "$BASE/user/logout?accessToken=$T_ADMIN" '{}')
case "$LOA" in *'"code":200'*) ok "A.21 管理员会话登出成功";; *) bad "A.21 登出失败：$(msg_of "$LOA")" ;; esac
REV_A=$(add_user "$RESIDENT_PORT" p22_hate ADMIN "$T_ADMIN")
case "$REV_A" in
  *'"code":500'*accessToken*) ok "A.22 铸权闸不依赖「撤销有没有生效」：已登出的 ADMIN 令牌在演示模式里退化成匿名，而匿名本来就铸不出 ADMIN ⇒ 第二道网不指望第一道网兜得住" ;;
  *) bad "A.22 已登出的 ADMIN 令牌铸出了 ADMIN：$(printf '%s' "$REV_A" | cut -f1) | $(msg_of "$REV_A")" ;;
esac
[ "$(rowcount p22_hate)" = "0" ] && ok "A.23 A.22 那次也没落库" || bad "A.23 被拒的账号出现在库里"
RE2=$(req POST "$BASE/user/logout?accessToken=$T_NORM" '{}')
case "$RE2" in
  *'没有登录态'*) ok "A.24 演示模式里重复登出得到「当前请求没有登录态」⇒ 那句恒成功的「退出成功」不回「令牌不存在」（那是一个「哪些令牌正在用」的探针）" ;;
  *) bad "A.24 演示模式的重复登出形状不对：$(printf '%s' "$RE2" | cut -f2)" ;;
esac

echo "--- B) 关门实例上的会话语义（实例已在 S 段起好）---" | tee -a "$OUT"
TB_N=$(login "$PORT" p22_normal); TB_A=$(login "$PORT" p22_admin)
[ -n "$TB_N" ] && [ -n "$TB_A" ] && ok "B.1 关门实例上 /user/login 仍然公开可换令牌（否则谁也进不来）" || bad "B.1 关门后登录拿不到令牌"
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TB_N")" in
  200*) ok "B.2 会话令牌在关门实例上同样能过 ⇒ 两种凭证并存，不是「只有共享密钥能用」" ;;
  *) bad "B.2 会话令牌被拒" ;;
esac
BAN=$(add_user "$PORT" p22_gate3 ADMIN "$TB_N")
case "$BAN" in
  403*) printf '%s' "$BAN" | grep -q '需要管理员角色' \
          && ok "B.3 普通会话连「自己填 role=ADMIN」这条路都是 403，而且红在**过滤器**那句（角色闸在 controller 之前，铸权闸是它后面的第二道）" \
          || bad "B.3 403 但不是角色闸的理由：$(msg_of "$BAN")" ;;
  *) bad "B.3 普通会话建出了 ADMIN：$(printf '%s' "$BAN" | cut -f2)" ;;
esac
[ "$(rowcount p22_gate3)" = "0" ] && ok "B.4 B.3 被拒的那次没落库" || bad "B.4 被拒之后 p22_gate3 在库里"
MINT_S=$(add_user "$PORT" p22_mint2 ADMIN "$SECRET")
case "$MINT_S" in
  *'"code":200'*) ok "B.5 共享密钥在关门实例上铸得出 ADMIN ⇒ #28 没有把「握着口令的人」一起锁死（它仍是全权）" ;;
  *) bad "B.5 共享密钥铸 ADMIN 被误伤：$(printf '%s' "$MINT_S" | cut -f1) | $(msg_of "$MINT_S")" ;;
esac
case "$(req POST "$BB/user/remove?id=$(uid p22_normal)&accessToken=$TB_N")" in
  403*) ok "B.6 普通会话删不掉账号" ;; *) bad "B.6 普通会话能删账号" ;;
esac

LO=$(req POST "$BB/user/logout?accessToken=$TB_N" '{}')
case "$LO" in *'"code":200'*) ok "B.7 logout 成功";; *) bad "B.7 logout 失败：$(printf '%s' "$LO" | cut -f2)";; esac
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TB_N")" in
  403*) ok "B.8 **撤销真生效**：登出后的令牌既解析不出身份、又不等于共享密钥 ⇒ 403（A.20 在演示模式量不到的就是这一条）" ;;
  *) bad "B.8 登出后令牌仍能用" ;;
esac
RE=$(req POST "$BB/user/logout?accessToken=$TB_N" '{}')
case "$RE" in
  403*) ok "B.9 重复登出在**关门**实例上是 403：撤销后的令牌既不是会话也不等于共享密钥，根本走不进 controller（那句「当前请求没有登录态」在这台上是不可达分支）" ;;
  *) bad "B.9 重复登出竟然 $(printf '%s' "$RE" | cut -f1)：撤销没落地？" ;;
esac

TN2=$(login "$PORT" p22_normal)
ID_N=$(uid p22_normal)
UPD=$(req POST "$BB/user/update?accessToken=$TB_A" "{\"id\":$ID_N,\"role\":\"ADMIN\"}")
case "$UPD" in
  *'"code":200'*) ok "B.10 管理员会话改角色成功（p22_normal → ADMIN）；这条走的是铸权闸的 ADMIN 会话分支" ;;
  *) bad "B.10 改角色失败：$(printf '%s' "$UPD" | cut -f1) | $(msg_of "$UPD")" ;;
esac
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TN2")" in
  403*) ok "B.11 改角色 ⇒ 该用户**已签发的**会话全部作废（不等 30 min 过期；降权不落地的洞就堵在这里）" ;;
  *) bad "B.11 改完角色旧令牌还在用" ;;
esac
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TB_A")" in
  200*) ok "B.12 别人改角色不动我的会话（invalidateUser 按 userId 精确撤销，没有顺手清库）" ;;
  *) bad "B.12 改一个用户把管理员会话也踢了" ;;
esac
TN3=$(login "$PORT" p22_normal)
DUP=$(add_user "$PORT" p22_dup ADMIN "$TN3")
case "$DUP" in
  *'"code":200'*) ok "B.13 重新登录得到的是**新角色**的会话（作废 ≠ 把账号锁死），而且它现在铸得出 ADMIN；p22_dup 已建，留给 C 段删" ;;
  *) bad "B.13 提权后重新登录铸不出 ADMIN：http=$(printf '%s' "$DUP" | cut -f1) | $(msg_of "$DUP")" ;;
esac
ID_G=$(uid p22_gate); TA2=$(login "$PORT" p22_gate)
say "  B.14 p22_gate id=$ID_G 令牌长度=${#TA2}（A.14 里匿名建的那个 NORMAL 账号，用来验删除）"
case "$(req POST "$BB/user/remove?id=$ID_G&accessToken=$TB_A")" in
  *'"code":200'*) ok "B.15 管理员删掉账号" ;; *) bad "B.15 删账号失败" ;;
esac
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TA2")" in
  403*) ok "B.16 删账号 ⇒ 他的会话一并撤销（否则「人已删、身份还能活 30 min」）" ;;
  *) bad "B.16 已删账号的令牌仍在用" ;;
esac
case "$(req POST "$BB/user/login" "{\"username\":\"p22_gate\",\"password\":\"$PROBE_PW\"}")" in
  *'"code":200'*) bad "B.17 已删账号还能登录" ;;
  *) ok "B.17 已删账号登录被拒 ⇒ 与 B.16 是两个独立的闸（一个在签发侧、一个在已发令牌上），少一条另一条都能绿" ;;
esac

echo "--- D) 按 jobGroup 收口：permission 这一列第一次有读者（关门实例）---" | tee -a "$OUT"
# 为什么只能在关了门的这台量：演示模式下匿名请求没有身份，GroupAccess.restrictable 直接回 null，
# 收口在结构上不可能显形——和 A.20 那格是同一个可见性问题，不是重复。
# 两个探针组、每组一个任务；peon 的 permission 先给 A 组。
GRA=$(req POST "$BB/jobgroup/add?accessToken=$SECRET" '{"appName":"p22_grp_a","title":"p22 组A","addressType":0,"addressList":""}')
GRB=$(req POST "$BB/jobgroup/add?accessToken=$SECRET" '{"appName":"p22_grp_b","title":"p22 组B","addressType":0,"addressList":""}')
GA=$(gid p22_grp_a); GB=$(gid p22_grp_b)
[ -n "$GA" ] && [ -n "$GB" ] && [ "$GA" != "$GB" ] && ok "D.1 两个执行器分组落库：A=$GA B=$GB" \
  || bad "D.1 建组没成：$(msg_of "$GRA") / $(msg_of "$GRB")"
new_job() {   # new_job <组> <描述>
  req POST "$BB/jobinfo/add?accessToken=$SECRET" "{\"jobGroup\":$1,\"jobDesc\":\"$2\",\"author\":\"p22\",\"executorHandler\":\"demoHandler\",\"executorRouteStrategy\":\"ROUND\",\"executorBlockStrategy\":\"SERIAL_EXECUTION\",\"triggerType\":\"FIX_RATE\",\"fixInterval\":60000,\"jobCron\":\"\",\"misfireStrategy\":\"DO_NOTHING\",\"executorTimeout\":0,\"executorFailRetryCount\":0}"
}
JA_R=$(new_job "$GA" "p22_任务A"); JB_R=$(new_job "$GB" "p22_任务B")
JA=$(jid p22_任务A); JB=$(jid p22_任务B)
[ -n "$JA" ] && [ -n "$JB" ] && ok "D.2 两行任务落库：A组 $JA / B组 $JB（共享密钥建任务不受收口影响，它就是全权）" \
  || bad "D.2 建任务没成：$(msg_of "$JA_R") / $(msg_of "$JB_R")"
add_user "$PORT" p22_peon NORMAL "$SECRET" >/dev/null
ID_P=$(uid p22_peon)
SCOPE_R=$(req POST "$BB/user/update?accessToken=$SECRET" "{\"id\":$ID_P,\"permission\":\"$GA\"}")
case "$SCOPE_R" in
  *'"code":200'*) ok "D.3 给 p22_peon 写上 permission=$GA（这一列从此不是装饰，写它的人也不多）" ;;
  *) bad "D.3 写 permission 失败：$(msg_of "$SCOPE_R")" ;;
esac
[ "$(q -e "SELECT permission FROM z_schedule_user WHERE id=$ID_P" | tr -d '[:space:]')" = "$GA" ] \
  && ok "D.3b 库里那一列读回来正是 $GA" || bad "D.3b 库里那一列不是 $GA"
T_P=$(login "$PORT" p22_peon)
[ -n "$T_P" ] && ok "D.3c peon 登录拿到令牌（收口不拦登录，/user/login 本来就是公开口）" || bad "D.3c peon 登录失败"

if [ -z "$GA" ] || [ -z "$GB" ] || [ -z "$JA" ] || [ -z "$JB" ] || [ -z "$T_P" ]; then
  obs "D.4 起跳过：前置数据没齐（组=$GA/$GB 任务=$JA/$JB 令牌=${#T_P} 字符）⇒ 后面的按组收口无从判"
else
  PP="accessToken=$T_P"
  RL=$(raw GET "$BB/jobinfo/list?$PP")
  case "$RL" in 200*) : ;; *) bad "D.4 peon 打 /jobinfo/list 连门都没进：http=$(printf '%s' "$RL" | cut -f1)";; esac
  if has_row "$(printf '%s' "$RL" | cut -f2-)" "$JA" && ! has_row "$(printf '%s' "$RL" | cut -f2-)" "$JB"; then
    ok "D.4 列表被裁到自己那一组：有 $JA、没有 $JB（裁剪发生在返回前，不是替数据库少查）"
  else
    bad "D.4 列表形状不对：有 $JA=$(has_row "$(printf '%s' "$RL" | cut -f2-)" "$JA" && echo y || echo n) 有 $JB=$(has_row "$(printf '%s' "$RL" | cut -f2-)" "$JB" && echo y || echo n)"
  fi

  RB=$(raw GET "$BB/jobinfo/list?jobGroup=$GB&$PP")
  case "$RB" in
    200*'"code":500'*) printf '%s' "$RB" | grep -q "jobGroup=$GB" \
        && ok "D.5 点名要 B 组是**拒绝**而不是一张空表，理由里带着组号（HTTP 200 + code:500 ⇒ 这是 controller 的第二层，过滤器的 403 在前面，见坑 23）" \
        || bad "D.5 拒绝理由没指名组号：$(printf '%s' "$RB" | cut -c1-200)" ;;
    403*) bad "D.5 这条被过滤器拦了（403）⇒ 收口没走到，说明角色闸比它更宽，读数无意义" ;;
    *) bad "D.5 peon 竟然看得见 B 组的整页：$(printf '%s' "$RB" | cut -c1-200)" ;;
  esac
  RA=$(raw GET "$BB/jobinfo/list?jobGroup=$GA&$PP")
  case "$RA" in 200*'"code":200'*) ok "D.6 阳性对照：同一个人点名要 A 组照样给 ⇒ D.5 的红不是「list 这个口坏了」";; *) bad "D.6 自己的组也被拒：$(printf '%s' "$RA" | cut -c1-200)";; esac

  RG=$(raw GET "$BB/jobinfo/get?id=$JB&$PP"); RR=$(raw GET "$BB/jobinfo/get?id=$JA&$PP")
  case "$RG" in *'"code":500'*) ok "D.7 读单行同样认库里那一行的组（$JB 在 B 组，读不到）";; *) bad "D.7 按 id 直读绕过了收口：$(printf '%s' "$RG" | cut -c1-200)";; esac
  # 模式里要带 $JA 的**值**，所以这一段不能用单引号包；改用 has_row（双引号 + 尾随非数字）
  if printf '%s' "$RR" | grep -q '"code":200' && has_row "$(printf '%s' "$RR" | cut -f2-)" "$JA"; then
    ok "D.7b 阳性对照：A 组那行按 id 读得到"
  else
    bad "D.7b 自己的行按 id 读不到：$(printf '%s' "$RR" | cut -c1-200)"
  fi

  LB0=$(logrows "$JB")
  RT=$(req POST "$BB/jobinfo/trigger?id=$JB&$PP" '{}')
  case "$RT" in
    *'"code":500'*) [ "$(logrows "$JB")" = "$LB0" ] \
        && ok "D.8 越组触发被拒，而且库里 B 组那行的日志行数没变（$LB0 → $(logrows "$JB")）⇒ 闸落在写之前" \
        || bad "D.8 说了拒绝却已经把任务跑了一遍：日志行数 $LB0 → $(logrows "$JB")" ;;
    *) bad "D.8 越组触发竟然放行：$(printf '%s' "$RT" | cut -c1-200)" ;;
  esac
  req POST "$BB/jobinfo/trigger?id=$JA&$PP" '{}' >/dev/null
  if [ "$(logrows "$JA")" -gt 0 ]; then
    ok "D.9 阳性对照：同一条请求换成 A 组那行，触发真的落了库（日志行数 $(logrows "$JA")）⇒ D.8 的零不是「触发这个口从来不写日志」"
  else
    bad "D.9 自己那组的触发也没留下日志行 ⇒ 这条量具看不见写侧，D.8 的结论无效"
  fi

  ST0=$(status_of "$JB")
  for op in stop start remove; do
    R=$(req POST "$BB/jobinfo/$op?id=$JB&$PP" '{}')
    case "$R" in
      *'"code":500'*) : ;;
      *) bad "D.10 越组 $op 竟然放行：$(printf '%s' "$R" | cut -c1-160)";;
    esac
  done
  [ "$(status_of "$JB")" = "$ST0" ] && [ -n "$(jid p22_任务B)" ] \
    && ok "D.10 越组的 stop/start/remove 三支都被拒，B 组那行既没被停也没被删（trigger_status 仍 $ST0）" \
    || bad "D.10 被拒的写侧改动了库：status $(status_of "$JB")（原本 $ST0）/ 行还在吗 $([ -n "$(jid p22_任务B)" ] && echo y || echo n)"
  req POST "$BB/jobinfo/start?id=$JA&$PP" '{}' >/dev/null
  S1=$(status_of "$JA"); req POST "$BB/jobinfo/stop?id=$JA&$PP" '{}' >/dev/null; S0=$(status_of "$JA")
  [ "$S1" = "1" ] && [ "$S0" = "0" ] && ok "D.11 阳性对照：peon 对自己那组启停真的生效（$JA trigger_status 0→1→0）⇒ D.10 不是「启停口全坏了」" \
    || bad "D.11 自己组的启停没落地：$S1/$S0（期望 1/0）"

  RADM=$(raw GET "$BB/jobinfo/list?accessToken=$TB_A")
  if has_row "$(printf '%s' "$RADM" | cut -f2-)" "$JA" && has_row "$(printf '%s' "$RADM" | cut -f2-)" "$JB"; then
    ok "D.12 管理员会话一份列表看得见两组（收口只针对普通身份，没把管理员一起锁小）"
  else
    bad "D.12 管理员会话也被裁了：$(printf '%s' "$RADM" | cut -c1-200)"
  fi
  RGP=$(raw GET "$BB/jobgroup/list?$PP")
  if printf '%s' "$RGP" | grep -q "p22_grp_b"; then
    obs "D.13 已知非交付：/jobgroup/list 仍把两个组都吐给 peon。这一份是执行器下拉的数据源，裁掉名字会让管理页只剩数字；要收口得连下拉一起改，不在本格范围"
  else
    ok "D.13 /jobgroup/list 也一并收了口（比本格的承诺更多）"
  fi

  # 改列要立刻生效：会话里带着 permission，不撤销就等于"收口要等它自己过期"
  req POST "$BB/user/update?accessToken=$SECRET" "{\"id\":$ID_P,\"permission\":\"$GB\"}" >/dev/null
  case "$(req GET "$BB/jobinfo/list?$PP")" in
    403*) ok "D.14 改 permission ⇒ 已签发的会话立刻作废（关门实例上 403 看得见；和 B.11 改角色同一类，只是这一列以前没人读）" ;;
    *) bad "D.14 改完分组，旧令牌还带着旧分组在跑：$(raw GET "$BB/jobinfo/list?$PP" | cut -c1-160)" ;;
  esac
  T_P2=$(login "$PORT" p22_peon)
  RC=$(raw GET "$BB/jobinfo/list?accessToken=$T_P2")
  if has_row "$(printf '%s' "$RC" | cut -f2-)" "$JB" && ! has_row "$(printf '%s' "$RC" | cut -f2-)" "$JA"; then
    ok "D.15 重新登录之后跟着新值走：看得见 B 组、看不见 A 组 ⇒ 这一列是真的判据，不是写进去好看的"
  else
    bad "D.15 重新登录后收口没跟着新值：$(printf '%s' "$RC" | cut -c1-200)"
  fi
  case "$(req GET "$BB/jobinfo/list?accessToken=$TB_A")" in
    200*) ok "D.16 收口一个人不把别人一并踢下线（invalidateUser 按 userId 精确撤销）" ;;
    *) bad "D.16 改 peon 的分组把管理员会话也踢了" ;;
  esac
fi

echo "--- E) /joblog/* 的按组收口：判据换成日志行那一列（真 MySQL 8）---" | tee -a "$OUT"
# D 段判的是任务那一行（z_schedule_job_info.job_group），这一段判的是日志行自己那一列。
# 不是同一个东西：/joblog/* 三道口只认 log.job_group，所以写侧一旦把这一列写成死值 0，读侧会
# "很严格地"把所有行都滤掉——空表在界面上看不出异常，在替身里甚至还是绿的。
# 所以格子的顺序是固定的：先量写侧（E.2/E.3），再量读侧；读侧的期望值一律由 SQL 现算（D.9 之类
# 真实触发写过什么行、写了几个，取决于前面几段跑到哪，把 id 写死在脚本里就是猜）。
GC=""; JC=""; T_PE=""
# e_seed <组> <任务> <trigger_time 表达式> <handle_code> ⇒ 新行 id（handler 打标记，收尾按它清）
e_seed() {
  q -e "INSERT INTO z_schedule_job_log (job_group, job_id, executor_handler, trigger_time, trigger_code, handle_code, handle_msg) VALUES ($1, $2, 'p22_e', $3, 200, $4, 'p22 e2e 播种行')" >/dev/null
  q -e "SELECT MAX(id) FROM z_schedule_job_log WHERE executor_handler='p22_e'" | tr -d '[:space:]'
}
# e_expect <limit> <组列表> ⇒ 期望的 id 序：让 MySQL 按"空时间最后、时间倒序、同刻 id 倒序"排一次，
# 与 controller 里那段 Java 合并对拍。两套实现同序才算数——只信一边就是让被测者自己出题。
e_expect() {
  q -e "SELECT GROUP_CONCAT(id ORDER BY tt_null, tt DESC, id DESC) FROM (SELECT id, trigger_time IS NULL AS tt_null, trigger_time AS tt FROM z_schedule_job_log WHERE job_group IN ($2) ORDER BY tt_null, tt DESC, id DESC LIMIT $1) x" \
    | tr -d '[:space:]' | tr ',' ' '
}
# ids_of <响应体> ⇒ 按响应里的出现顺序取 id，空格分隔且**不带尾空格**
# （"jobId" 里有大写 I，不会被这条正则吃掉。带上尾空格的话整串比较会永远不等：
#  e_expect 那条 SQL 出来的串没有尾空格 —— 第一版就是这么把两条真绿读成 FAIL 的）
ids_of() { printf '%s' "$1" | grep -oE '"id":[0-9]+' | cut -d: -f2 | paste -sd' ' -; }
has_id() { printf '%s' "$1" | grep -Eq "(^| )$2( |$)"; }

if [ -z "$GA" ] || [ -z "$GB" ] || [ -z "$JA" ] || [ -z "$ID_P" ]; then
  obs "E.1 起跳过：D 段的探针组/任务/账号没齐（A=$GA B=$GB 任务=$JA peon=$ID_P）⇒ 读侧的格子没有承载体"
else
  GRC=$(req POST "$BB/jobgroup/add?accessToken=$SECRET" '{"appName":"p22_grp_c","title":"p22 组C","addressType":0,"addressList":""}')
  GC=$(gid p22_grp_c)
  JCR=$(new_job "$GC" "p22_任务C"); JC=$(jid p22_任务C)
  if [ -z "$GC" ] || [ -z "$JC" ]; then
    bad "E.1 第三个组或它的任务没建出来：$(msg_of "$GRC") / $(msg_of "$JCR")"
  else
    ok "E.1 第三个组 p22_grp_c=$GC 及其任务 $JC 落库 ⇒ 读侧终于有一个'不是我的组'当猎物"

    # 写侧第一格：派发行带的是任务真正的组。这一列是读侧三道闸唯一的依据，它错了后面全白量。
    E0=$(logrows "$JA")
    RUN_R=$(req POST "$BB/executor/run?accessToken=$SECRET" "{\"jobId\":$JA,\"executorHandler\":\"demoHandler\",\"executorParams\":\"\",\"executorFailRetryCount\":0}")
    case "$RUN_R" in
      *'"code":200'*)
        NEWG=$(q -e "SELECT job_group FROM z_schedule_job_log WHERE job_id=$JA ORDER BY id DESC LIMIT 1" | tr -d '[:space:]')
        NEWT=$(q -e "SELECT IFNULL(trigger_time,'NULL') FROM z_schedule_job_log WHERE job_id=$JA ORDER BY id DESC LIMIT 1" | tr -d '[:space:]')
        if [ "$(logrows "$JA")" -gt "$E0" ] && [ "$NEWG" = "$GA" ] && [ "$NEWT" != "NULL" ]; then
          ok "E.2 派发落库 job_group=$NEWG（=任务那一行的组 $GA）、trigger_time=$NEWT ⇒ 读侧有据可判；且组号是从库里取的，不是拿请求体里的数凑的"
        else
          bad "E.2 派发行的组号是 '$NEWG'（期望 $GA）、时间 '$NEWT'、日志行数 $E0→$(logrows "$JA") ⇒ 收口依据本身是坏的，E.4 之后的读数无意义"
        fi
        ;;
      *) bad "E.2 /executor/run 没成功：$(printf '%s' "$RUN_R" | cut -f1) $(msg_of "$RUN_R")" ;;
    esac
    # 写侧第二格：库里没有的任务不再顺手落一行。孤儿行按 job_id 反查删不掉，是清理口的死角。
    TOT0=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log" | tr -d '[:space:]')
    ORPH=$(raw POST "$BB/executor/run?accessToken=$SECRET" '{"jobId":99999999,"executorHandler":"demoHandler"}')
    TOT1=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log" | tr -d '[:space:]')
    if printf '%s' "$ORPH" | grep -q '"code":500'; then
      [ "$TOT0" = "$TOT1" ] && ok "E.3 不存在的任务：拒绝且全表行数仍 $TOT0 ⇒ 派发口不产孤儿行（否则 join 式清理留一堆无人可判组的行）" \
        || bad "E.3 说了拒绝还是落了一行：$TOT0 → $TOT1"
    else
      bad "E.3 不存在的任务竟然算派发成功：$(printf '%s' "$ORPH" | cut -c1-160)"
    fi

    # permission 换成逗号列表：读侧要的是"我有两组"这个形状，先看任务列表认不认（不认则 E.5 的空表
    # 会被读成"收口生效"，而真相是逗号根本没拆开）。
    req POST "$BB/user/update?accessToken=$SECRET" "{\"id\":$ID_P,\"permission\":\"$GA,$GB\"}" >/dev/null
    T_PE=$(login "$PORT" p22_peon)
    RL=$(raw GET "$BB/jobinfo/list?accessToken=$T_PE")
    if [ -n "$T_PE" ] && has_row "$(printf '%s' "$RL" | cut -f2-)" "$JA" && has_row "$(printf '%s' "$RL" | cut -f2-)" "$JB"; then
      ok "E.4 permission='$GA,$GB' 拆成了两组：任务列表里 $JA、$JB 都看得见 ⇒ 下面按'我的两组'量合并"
    else
      bad "E.4 逗号列表没展开成两组（令牌 ${#T_PE} 字符）：$(printf '%s' "$RL" | cut -c1-200)"
    fi
    EP="accessToken=$T_PE"

    # 播种。C 组（不是 peon 的组）刻意造得又新又多："全局查一页再按组裁"的写法会先被它挤空；
    # 时间用 +3600/+60/+30 秒这种分得开的值，而并列那一对待会儿用字面量（datetime 只到秒，
    # 两条 NOW() 跨秒就不并列了——测"同刻按 id 倒序"必须钉同一个值）。
    for off in 3600 3601 3602 3603; do e_seed "$GC" "$JC" "DATE_ADD(NOW(), INTERVAL $off SECOND)" 0 >/dev/null; done
    EA=$(e_seed "$GA" "$JA" "DATE_ADD(NOW(), INTERVAL 60 SECOND)" 0)
    EB=$(e_seed "$GB" "$JB" "DATE_ADD(NOW(), INTERVAL 30 SECOND)" 0)
    TIE_LITERAL="'2026-09-26 12:00:00'"
    ET1=$(e_seed "$GA" "$JA" "$TIE_LITERAL" 0)
    ET2=$(e_seed "$GA" "$JA" "$TIE_LITERAL" 0)
    ENULL=$(e_seed "$GA" "$JA" "NULL" 0)
    EC1=$(q -e "SELECT id FROM z_schedule_job_log WHERE job_group=$GC ORDER BY id DESC LIMIT 1" | tr -d '[:space:]')

    R3=$(raw GET "$BB/joblog/list?limit=3&$EP")
    GOT3=$(ids_of "$(printf '%s' "$R3" | cut -f2-)")
    EXP3=$(e_expect 3 "$GA,$GB")
    if printf '%s' "$R3" | grep -q '"code":200' && [ -n "$GOT3" ] \
       && ! has_id "$GOT3" "$EC1" && [ "$GOT3" = "$EXP3" ]; then
      ok "E.5 不限组时是自己那几组各取一页再合并：limit=3 拿到 $GOT3（SQL 侧同规则期望 $EXP3），C 组那四行更新的行一条没进来"
    else
      bad "E.5 合并形状不对：http=$(printf '%s' "$R3" | cut -f1) 实得 [$GOT3] 期望 [$EXP3] C组最新行=$EC1"
    fi

    R9=$(raw GET "$BB/joblog/list?limit=9&$EP")
    GOT9=$(ids_of "$(printf '%s' "$R9" | cut -f2-)")
    EXP9=$(e_expect 9 "$GA,$GB")
    if [ "$GOT9" = "$EXP9" ] && has_id "$GOT9" "$ET2" && has_id "$GOT9" "$ENULL"; then
      ok "E.6 合并后重排序在真库上成立：空 trigger_time 的 $ENULL 排最后、同刻的 $ET2/$ET1 按 id 倒序，整串与 MySQL 自己排的 $EXP9 逐位相同"
    else
      bad "E.6 顺序不一致：实得 [$GOT9] 期望 [$EXP9]（空时间行 $ENULL 应在末位，同刻对 $ET2>$ET1）"
    fi

    RS=$(raw GET "$BB/joblog/list?jobGroup=$GC&$EP")
    RSA=$(raw GET "$BB/joblog/list?jobGroup=$GA&limit=9&$EP")
    if printf '%s' "$RS" | grep -q '"code":500' && printf '%s' "$RS" | grep -q "jobGroup=$GC" \
       && printf '%s' "$RSA" | grep -q '"code":200' && has_id "$(ids_of "$(printf '%s' "$RSA" | cut -f2-)")" "$EA"; then
      ok "E.7 点名 C 组是拒绝（理由里带着 $GC），点名 A 组照给且 $EA 在里面 ⇒ E.5 的'没有 C 组行'是裁掉的，不是查不出来"
    else
      bad "E.7 点名单组这格读不开：C=$(printf '%s' "$RS" | cut -c1-140) / A=$(printf '%s' "$RSA" | cut -c1-140)"
    fi

    EF500=$(e_seed "$GA" "$JA" "DATE_ADD(NOW(), INTERVAL 90 SECOND)" 500)
    EF200=$(e_seed "$GA" "$JA" "DATE_ADD(NOW(), INTERVAL 91 SECOND)" 200)
    RH0b=$(raw GET "$BB/joblog/list?jobGroup=$GA&status=2&limit=99&$EP")
    RH1b=$(raw GET "$BB/joblog/list?jobGroup=$GA&status=1&limit=99&$EP")
    I0=$(ids_of "$(printf '%s' "$RH0b" | cut -f2-)"); I1=$(ids_of "$(printf '%s' "$RH1b" | cut -f2-)")
    if has_id "$I0" "$EF500" && ! has_id "$I0" "$EF200" && ! has_id "$I0" "$EA" && has_id "$I1" "$EF200" && ! has_id "$I1" "$EF500"; then
      ok "E.8 status 的两个挡位在真库上互斥：status=2 有 $EF500 没 $EF200 也没 handle_code=0 的 $EA，status=1 反过来 ⇒ 0（未执行）没被算成失败"
    else
      bad "E.8 状态挡位不对：status=2 [$I0] / status=1 [$I1]（播种 $EF500/$EF200，未执行样本 $EA）"
    fi

    RG1=$(raw GET "$BB/joblog/get?id=$EC1&$EP"); RG2=$(raw GET "$BB/joblog/executionLog?logId=$EC1&$EP")
    RG3=$(raw GET "$BB/joblog/get?id=$EA&$EP");  RG4=$(raw GET "$BB/joblog/executionLog?logId=$EA&$EP")
    if printf '%s' "$RG1" | grep -q '"code":500' && printf '%s' "$RG2" | grep -q '"code":500' \
       && printf '%s' "$RG3" | grep -q '"code":200' && printf '%s' "$RG4" | grep -q '"code":200' \
       && printf '%s' "$RG4" | grep -q 'p22 e2e 播种行'; then
      ok "E.9 单行两口认行上那一组：C 组的 $EC1 详情与执行日志都被拒，A 组的 $EA 给详情且执行日志里真是那一行的 handleMsg"
    else
      bad "E.9 单行口形状不对：get越组=$(printf '%s' "$RG1" | cut -c1-120) | 执行日志越组=$(printf '%s' "$RG2" | cut -c1-120) | get本组=$(printf '%s' "$RG3" | cut -c1-120) | 执行日志本组=$(printf '%s' "$RG4" | cut -c1-160)"
    fi

    RJ1=$(raw GET "$BB/joblog/list?jobId=$JC&$EP")
    RJ2=$(raw GET "$BB/joblog/list?jobId=$JA&limit=99&$EP")
    RJ3=$(raw GET "$BB/joblog/list?jobId=99999999&$EP")
    IJ2=$(ids_of "$(printf '%s' "$RJ2" | cut -f2-)")
    if printf '%s' "$RJ1" | grep -q '"code":500' && printf '%s' "$RJ3" | grep -q '"code":500' \
       && printf '%s' "$RJ3" | grep -q '不存在' && printf '%s' "$RJ2" | grep -q '"code":200' \
       && has_id "$IJ2" "$EA" && ! has_id "$IJ2" "$EB"; then
      ok "E.10 按 jobId 过滤：别人的任务（$JC）拒、库里没有的任务（99999999）也拒而不是一张空表、自己的任务只回自己那些行（$EA 在、$EB 不在）"
    else
      bad "E.10 jobId 三形状不对：JC=$(printf '%s' "$RJ1" | cut -c1-120) 不存在=$(printf '%s' "$RJ3" | cut -c1-120) JA=[$IJ2]"
    fi

    RA=$(raw GET "$BB/joblog/list?limit=999&accessToken=$TB_A")
    IAS=$(ids_of "$(printf '%s' "$RA" | cut -f2-)")
    if printf '%s' "$RA" | grep -q '"code":200' && has_id "$IAS" "$EA" && has_id "$IAS" "$EB" && has_id "$IAS" "$EC1"; then
      ok "E.11 管理员会话一份列表里 A/B/C 三组的行都在（'不受约束'那一支在真库上也没顺手裁自己）"
    else
      bad "E.11 管理员会话被裁了：[$IAS]（期望含 $EA $EB $EC1）"
    fi

    # 清理口三挡 + 一支猎物。"没删掉"必须与"这个口会删"同框，否则零改动可以是任何一件事。
    CJA=$(logrows "$JA")
    q -e "INSERT INTO z_schedule_job_log (job_group, job_id, executor_handler, trigger_code, handle_code) VALUES ($GC, $JC, 'p22_e', 200, 0)" >/dev/null
    CEC=$(logrows "$JC")
    K0=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log" | tr -d '[:space:]')
    CK1=$(raw POST "$BB/joblog/clear?$EP" '{"type":0}')
    K1=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log" | tr -d '[:space:]')
    CK2=$(raw POST "$BB/joblog/clear?$EP" "{\"type\":1,\"jobId\":$JC}")
    CK3=$(raw POST "$BB/joblog/clear?$EP" '{"type":1,"jobId":99999999}')
    if printf '%s' "$CK1" | grep -q '"code":500' && [ "$K0" = "$K1" ] \
       && printf '%s' "$CK2" | grep -q '"code":500' && [ "$(logrows "$JC")" = "$CEC" ] \
       && printf '%s' "$CK3" | grep -q '"code":500'; then
      ok "E.12 清理口对分权会话三挡都拦：清空全部（全表仍 $K1 行）、越组按任务清（$JC 仍 $CEC 行）、组都解析不出的任务也拦"
    else
      bad "E.12 清理口没全拦：type0=$(printf '%s' "$CK1" | cut -c1-120) 全表 $K0→$K1 / type1越组=$(printf '%s' "$CK2" | cut -c1-120) $JC 行数 $CEC→$(logrows "$JC") / 无组=$(printf '%s' "$CK3" | cut -c1-120)"
    fi
    CK4=$(raw POST "$BB/joblog/clear?accessToken=$SECRET" "{\"type\":1,\"jobId\":$JC}")
    AFTER=$(logrows "$JC")
    if printf '%s' "$CK4" | grep -q '"code":200' && [ "$AFTER" = "0" ] && [ "$(logrows "$JA")" = "$CJA" ]; then
      ok "E.13 猎物：同一个口换成共享密钥清 $JC 真的删了（$CEC→0），而 $JA 的行数没被牵连（$CJA）⇒ E.12 的'没删'是闸拦的，不是这个口从来不删"
    else
      bad "E.13 该删的没删干净：$(printf '%s' "$CK4" | cut -c1-140) $JC 行数 $CEC→$AFTER，$JA=$CJA→$(logrows "$JA")"
    fi

    q -e "DELETE FROM z_schedule_job_log WHERE executor_handler='p22_e'" >/dev/null
    LEFT_E=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE executor_handler='p22_e'" | tr -d '[:space:]')
    [ "$LEFT_E" = "0" ] && ok "E.14 播种行（handler='p22_e'）已清完 ⇒ 下一轮 E.5 的行数断言不被上一轮污染" \
      || bad "E.14 还剩 $LEFT_E 行播种数据"
  fi
fi

# 探针数据自己收走：任务、日志、分组都按 p22_ 前缀删，账号留给 C 段
q -e "DELETE FROM z_schedule_job_log WHERE job_id IN (SELECT id FROM z_schedule_job_info WHERE job_desc LIKE 'p22\_%')" >/dev/null
q -e "DELETE FROM z_schedule_job_info WHERE job_desc LIKE 'p22\_%'" >/dev/null
q -e "DELETE FROM z_schedule_job_group WHERE app_name LIKE 'p22\_%'" >/dev/null
DLEFT=$(q -e "SELECT (SELECT COUNT(*) FROM z_schedule_job_info WHERE job_desc LIKE 'p22\_%') + (SELECT COUNT(*) FROM z_schedule_job_group WHERE app_name LIKE 'p22\_%')" | tr -d '[:space:]')
[ "$DLEFT" = "0" ] && ok "D.17 探针任务与探针分组已清干净（$DLEFT）" || bad "D.17 还剩 $DLEFT 行探针数据，会污染下一轮的行数断言"

kill -TERM "$BPID" 2>/dev/null || true
for i in $(seq 1 30); do sleep 1; kill -0 "$BPID" 2>/dev/null || { say "  C.1 临时实例 ${i}s 内退出"; break; }; done
say "  C.2 临时实例日志里的 stepDown 行数=$(grep -icE 'stepped down' "$LOG_DIR/p22_b_instance.out")（它是 follower 时应为 0，抢到过一次 leader 则必须有）"
ss -ltn 2>/dev/null | grep -q ":$PORT " && bad "C.3 端口 $PORT 仍被占" || ok "C.3 端口 $PORT 已释放"
ALIVE=$(pgrep -af "[j]ava" | grep -- "-jar [^ ]*z-schedule-admin" | grep -c -- "--server.port=$RESIDENT_PORT" || true)
[ "$ALIVE" = "1" ] && ok "C.4 常驻实例还活着（B 段没把它挤掉，服务仍挂在 250）" || bad "C.4 常驻实例不在了"
# 列名以 DESC 为准：这张表是 (id, owner, host, expire_time)，没有"最后续约时间"这一列
say "  C.5 leader 归属：$(q -e "SELECT id, owner, host, expire_time, TIMESTAMPDIFF(SECOND, NOW(), expire_time) FROM z_schedule_job_leader" | tr '\n' ' ')"
FRESH=$(q -e "SELECT COUNT(*) FROM z_schedule_job_leader WHERE expire_time > DATE_ADD(NOW(), INTERVAL 5 SECOND)" | tr -d '[:space:]')
[ "$FRESH" = "1" ] && ok "C.6 租约仍是活的（expire_time 领先 NOW() 5 s 以上）⇒ 两个实例的启停没把集群留在无人续约的状态" \
  || bad "C.6 租约已过期（新鲜行数=$FRESH，期望 1）⇒ 常驻实例可能没在续约"
case "$(req GET "$BASE/user/list?start=0&length=1")" in
  200*) ok "C.7 演示模式仍对匿名敞开读侧（这一格交付的是「铸管理员」收口，不是「管理面关门」）" ;;
  *) bad "C.7 常驻实例不再应答：$(printf '%s' "$(req GET "$BASE/user/list?start=0&length=1")" | cut -f1)" ;;
esac
q -e "DELETE FROM z_schedule_user WHERE username LIKE 'p22\_%'" >/dev/null
LEFT=$(usersql | grep -c . )
say "  C.8 剩余探针账号 $LEFT 行（期望 0；不清会污染下一次 /user/list 与账号计数）"
[ "$LEFT" = "0" ] && ok "C.8 E2E 库已还原" || { obs "C.8 还有行没删掉：$(usersql | tr '\n' ' ')"; q -e "DELETE FROM z_schedule_user WHERE username LIKE 'p22\_%'"; obs "C.8b SQL 兜底后剩余=$(usersql | grep -c . )"; }

echo "===================== p22 结果：PASS=$PASS FAIL=$FAIL OBS=$OBS（全文 $OUT）=====================" | tee -a "$OUT"
[ "$FAIL" -eq 0 ]
