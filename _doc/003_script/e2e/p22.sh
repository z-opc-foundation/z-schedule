#!/bin/bash
# p22.sh — #19 的「登录态」+#28 的「铸权闸」在真机上到底兑现到哪一步（250，MySQL 8，两个实例两种模式）
#
# 为什么必须上真机、H2 那 285 例不够：#19/#28 改的都是**跨进程边界的凭证语义**——登录发什么、
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
set -u
cd "$(dirname "$0")"

RESIDENT_PORT="${RESIDENT_PORT:-18098}"
JAR_EXPECT="${JAR_EXPECT:-z-schedule-admin-svc-0f1248b-exec.jar}"
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
rm -rf "$LOG_DIR/p22-starter.jar" "$LOG_DIR/p22-cls"
# 干净起点：上一轮崩在中间留下的行会让 S.9/A.3 的行数断言毫无意义
q -e "DELETE FROM z_schedule_user WHERE username LIKE 'p22\_%'" >/dev/null
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

echo "--- C) 收尾：库、常驻实例、leader 归属 ---" | tee -a "$OUT"
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
