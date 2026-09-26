#!/bin/bash
# p22.sh — #19 的「登录态」在真机上到底兑现到哪一步（250，MySQL 8，两个实例两种模式）
#
# 为什么必须上真机、H2 那 278 例不够：#19 改的是**跨进程边界的凭证语义**——登录发什么、
# 过滤器认什么、改角色/删账号之后旧令牌还在不在。单测里的过滤器与签发方是我自己 new 的
# 对象，真机上是 Spring 装配出来的两个 bean；这两件事的差别在 #17/#18 已经付过学费
# （「测试里接上了、生产里没人接线」）。
#
# 为什么要**两个实例**：会话撤销（logout / 改角色 / 删账号）在未配置 accessToken 的演示
# 模式下**观察不到**——令牌一旦被撤销，出示它就等于没出示，而「没出示」在演示模式下是放行。
# 于是「撤销生效」与「撤销没生效」在 18098 上返回一模一样。唯一分开两者的办法是把管理面
# 关起来再验，所以：
#   A) 常驻实例（18098，无 accessToken）→ 令牌形状 + 角色闸 + 演示模式的真实行为
#   B) 临时实例（bind(0) 挑的空闲口，accessToken 只走环境变量）→ 三种撤销各钉一条
#
# 口令：脚本里的 p22-probe-only 是**探针账号**的口令（跑完连账号一起删），不是数据库口令；
# 数据库口令仍由 mysql.env 经环境变量注入，本脚本一次都不打印它。
# B 段用「ps 里 access-token= 出现几条」判断 accessToken 有没有漏进 argv——只数条数，
# 绝不 grep 它的值（这条脚本的输出会落进日志文件）。
set -u
cd "$(dirname "$0")"

RESIDENT_PORT="${RESIDENT_PORT:-18098}"
JAR_EXPECT="${JAR_EXPECT:-z-schedule-admin-svc-baa4458-exec.jar}"
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
login() {  # login <port> <user> ⇒ 令牌（失败则空）
  local r; r=$(req POST "http://127.0.0.1:$1/user/login" "{\"username\":\"$2\",\"password\":\"$PROBE_PW\"}")
  content_of "$r"
}
usersql() { q -e "SELECT id,username,role FROM z_schedule_user WHERE username LIKE 'p22\_%' ORDER BY id"; }
uid()     { q -e "SELECT id FROM z_schedule_user WHERE username='$1'" | tr -d '[:space:]'; }

echo "===================== p22 登录态真机验 =====================" | tee -a "$OUT"

echo "--- 0) 构件身份：只认 argv + md5，不认文件名 ---" | tee -a "$OUT"
CAND=$(pgrep -af "[j]ava" | grep -- "-jar [^ ]*z-schedule-admin" | grep -- "--server.port=$RESIDENT_PORT" | head -1 || true)
[ -z "$CAND" ] && { bad "0.1 端口 $RESIDENT_PORT 上没有 java 进程，A 段无从开始"; exit 1; }
RUNJAR=$(printf '%s' "$CAND" | grep -oE '\-jar [^ ]+' | awk '{print $2}')
say "  0.1 常驻实例 pid=$(printf '%s' "$CAND" | awk '{print $1}') jar=$RUNJAR md5=$(md5sum "$RUNJAR" | awk '{print $1}')"
[ "$(basename "$RUNJAR")" = "$JAR_EXPECT" ] && ok "0.2 跑的是预期构件 $JAR_EXPECT" \
  || { bad "0.2 预期 $JAR_EXPECT，实际 $(basename "$RUNJAR")"; exit 1; }
# 阳性对照：#19 才有的类必须在**这个文件**里（否则 A/B 两段测的是旧字节）
unzip -p "$RUNJAR" 'BOOT-INF/lib/z-schedule-spring-boot-starter*.jar' > "$LOG_DIR/p22-starter.jar"
if unzip -l "$LOG_DIR/p22-starter.jar" 'com/zifang/z/schedule/web/auth/*' | grep -q LoginSessionStore; then
  ok "0.3 嵌套 starter 里有 LoginSessionStore.class ⇒ #19 的字节码在跑的构件里"
else
  bad "0.3 嵌套 starter 里没有 auth/ 包 ⇒ 这个 jar 不含 #19，读数无意义"; rm -f "$LOG_DIR/p22-starter.jar"; exit 1
fi
rm -f "$LOG_DIR/p22-starter.jar"

echo "--- A) 常驻实例：演示模式（未配置 accessToken）---" | tee -a "$OUT"
BASE="http://127.0.0.1:$RESIDENT_PORT"
for u in p22_admin p22_normal p22_gate; do
  i=$(uid "$u"); [ -n "$i" ] && req POST "$BASE/user/remove?id=$i" >/dev/null
done
say "  A.0 清理后库里的探针账号 $(usersql | grep -c . ) 行（期望 0）"
A_ADMIN=$(req POST "$BASE/user/add" "{\"username\":\"p22_admin\",\"password\":\"$PROBE_PW\",\"role\":\"ADMIN\"}")
A_NORM=$(req POST "$BASE/user/add" "{\"username\":\"p22_normal\",\"password\":\"$PROBE_PW\",\"role\":\"NORMAL\"}")
case "$A_ADMIN$A_NORM" in
  *'"code":200'*'"code":200'*) ok "A.1 匿名建账号在演示模式下真的能建（这就是「谁来种第一个管理员」的真答案：现在任何能连上端口的人都能种，且能自填 role=ADMIN）" ;;
  *) bad "A.1 建账号失败 admin=$(content_of "$A_ADMIN") normal=$(content_of "$A_NORM")" ;;
esac
say "  A.2 探针账号：$(usersql | tr '\n' ' ')"
[ "$(usersql | grep -c .)" = "2" ] && ok "A.3 两行真落库（ADMIN + NORMAL）" || bad "A.3 账号数不是 2"

T_ADMIN=$(login "$RESIDENT_PORT" p22_admin)
T_NORM=$(login "$RESIDENT_PORT" p22_normal)
# 形状 = base64url(32 字节) = 43 字符（不是十六进制的 64 位；上一版在这里断错了自己红）
printf '%s' "$T_ADMIN" | grep -Eq '^[A-Za-z0-9_-]{43}$' \
  && ok "A.4 登录换回 base64url 43 字符（= 32 字节 = 256 bit 熵，字符集里没有 + / =）" \
  || bad "A.4 令牌形状不对：长度=$(printf '%s' "$T_ADMIN" | wc -c | tr -d ' ') 前缀=$(printf '%s' "$T_ADMIN" | cut -c1-6)"
[ -n "$T_ADMIN" ] && [ "$T_ADMIN" != "p22_admin" ] && ok "A.5 令牌不是用户名（改之前 login 返回的就是它，而 /user/list 公开列得出用户名）" \
  || bad "A.5 令牌仍是用户名 ⇒ 等于没鉴权"
[ "$T_ADMIN" != "$(printf '%s' "p22_admin" | md5sum | awk '{print $1}')" ] \
  && [ "$T_ADMIN" != "$(printf '%s' "p22_admin$PROBE_PW" | md5sum | awk '{print $1}')" ] \
  && ok "A.6 令牌不是 md5(用户名) / md5(用户名+口令) 这类能推算出来的定值" || bad "A.6 令牌能从账号信息推出来"
T2=$(login "$RESIDENT_PORT" p22_admin)
[ -n "$T2" ] && [ "$T2" != "$T_ADMIN" ] && ok "A.7 同账号第二次登录得到**不同**令牌（每次签发都新，不是把用户映射成一串常量）" \
  || bad "A.7 二次登录没拿到新令牌"
BAD=$(req POST "$BASE/user/login" "{\"username\":\"p22_admin\",\"password\":\"wrong-$PROBE_PW\"}")
case "$BAD" in
  *"$T_ADMIN"*) bad "A.8 错口令回的内容里出现了正确令牌" ;;
  *'"code":200'*) bad "A.8 错口令竟然 200：$BAD" ;;
  *) ok "A.8 错口令被拒（http=$(printf '%s' "$BAD" | cut -f1)）" ;;
esac

LIST_OK=$(req GET "$BASE/jobinfo/list?start=0&length=1&accessToken=$T_ADMIN")
say "  A.9 出示会话令牌打 /jobinfo/list ⇒ $(printf '%s' "$LIST_OK" | cut -f1)"
case "$LIST_OK" in 200*'"code":200'*) ok "A.10 会话令牌本身就是凭证（过得了过滤器并拿到列表）";; *) bad "A.10 会话令牌过不了：$LIST_OK";; esac

ADD_N=$(req POST "$BASE/user/add?accessToken=$T_NORM" "{\"username\":\"p22_gate\",\"password\":\"$PROBE_PW\",\"role\":\"NORMAL\"}")
ADD_A=$(req POST "$BASE/user/add" "{\"username\":\"p22_gate\",\"password\":\"$PROBE_PW\",\"role\":\"NORMAL\"}")
say "  A.11 NORMAL 会话 /user/add ⇒ $(printf '%s' "$ADD_N" | cut -f1) | $(content_of "$ADD_N")"
say "  A.12 同一条请求、不出示身份 ⇒ $(printf '%s' "$ADD_A" | cut -f1) | $(content_of "$ADD_A")"
case "$ADD_N" in
  403*) case "$ADD_A" in 200*) ok "A.13 角色闸有牙：普通会话 403、匿名同一条 200。两者只差「有没有自报身份」，所以红不是接口坏了";;
            *) bad "A.13 匿名那次也非 200（$(printf '%s' "$ADD_A" | cut -f1)）⇒ 分不清是闸在起作用还是 add 本身坏了" ;; esac ;;
  *) bad "A.13 普通会话没被拒：$ADD_N" ;;
esac
printf '%s' "$ADD_N" | grep -q '需要管理员角色' && ok "A.14 拒绝理由写的是「需要管理员角色」，不是笼统一句 403" || bad "A.14 拒绝理由缺失"
case "$(req GET "$BASE/user/list?accessToken=$T_NORM")" in
  200*) ok "A.15 普通会话读 /user/list 不受闸约束（闸只在建/改/删三口；读侧收口是另一格，别把这条当已交付）" ;;
  *) bad "A.15 读侧被误伤：角色闸扩到列表了" ;;
esac

LO=$(req POST "$BASE/user/logout?accessToken=$T_NORM" '{}')
say "  A.16 logout ⇒ $(printf '%s' "$LO" | cut -f1) | $(content_of "$LO")"
case "$LO" in *'"code":200'*) ok "A.17 logout 返回成功";; *) bad "A.17 logout 失败：$(printf '%s' "$LO" | cut -f2)" ;; esac
ADD_AFTER=$(req POST "$BASE/user/add?accessToken=$T_NORM" "{\"username\":\"p22_gate2\",\"password\":\"$PROBE_PW\",\"role\":\"NORMAL\"}")
obs "A.18 演示模式下「撤销」观察不到：登出后同一条普通令牌打 /user/add 变成 $(printf '%s' "$ADD_AFTER" | cut -f1)"
obs "A.18b 原因：撤销 = 解析不出身份 = 等同于没出示凭证，而演示模式对没出示是放行。⇒ 撤销类断言只能在关了门的实例上量（B 段），这条不是缺陷而是量具的可见性"
req POST "$BASE/user/add" "{\"username\":\"p22_gate\",\"password\":\"$PROBE_PW\",\"role\":\"NORMAL\"}" >/dev/null 2>&1 || true

echo "--- B) 临时实例：把管理面关上（accessToken 只经环境变量）---" | tee -a "$OUT"
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
ss -ltn 2>/dev/null | grep -q ":$PORT " && { bad "B.0 bind(0) 挑到的 $PORT 已被占"; exit 1; }
say "  B.0 端口 $PORT 由 bind(0) 现挑（不复用 18086 台架口，也不复用 18098 常驻口，见坑 14）"
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
[ -z "$BPID" ] && { bad "B.1 临时实例没起来（tail 见下）"; tail -6 "$LOG_DIR/p22_b_instance.out" | tee -a "$OUT"; exit 1; }
ok "B.1 临时实例 pid=$BPID 端口 $PORT（同一份 run.sh、同一个库，只差一个环境变量）"
DEPLOYLINES=$(grep -c "deploy: JAR" "$LOG_DIR/p22_b_instance.out")
say "  B.2 run.sh 的坐标行 $DEPLOYLINES 条（它打的是库坐标，不打口令）"
[ "$(pgrep -af "[j]ava" | grep -c 'access-token=' || true)" = "0" ] && ok "B.3 argv 里没有 access-token ⇒ 这条部署路径不把口令泄给同机 ps" \
  || bad "B.3 口令进了 argv"

BB="http://127.0.0.1:$PORT"
NOAUTH=$(req GET "$BB/jobinfo/list?start=0&length=1")
say "  B.4 无凭证 /jobinfo/list ⇒ $(printf '%s' "$NOAUTH" | cut -f1)"
case "$NOAUTH" in
  403*) ok "B.5 门真的关上了 ⇒ 后面的「撤销」才有观察面" ;;
  *) bad "B.5 关门失败：$(printf '%s' "$NOAUTH" | cut -f1)（环境变量那套绑定不成立？B 段全部读数作废）"; exit 1 ;;
esac
ST=$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$SECRET")
case "$ST" in 200*) ok "B.6 共享密钥进得去（它不带身份，因此不受角色闸约束——这是设计，不是漏网）";; *) bad "B.6 共享密钥进不去：$(printf '%s' "$ST" | cut -f1)";; esac
WT=$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=${SECRET}nope")
case "$WT" in 403*) ok "B.7 错密钥仍 403 ⇒ B.6 通过不是因为「任何串都放行」";; *) bad "B.7 错密钥竟然 $(printf '%s' "$WT" | cut -f1)";; esac

TB_N=$(login "$PORT" p22_normal); TB_A=$(login "$PORT" p22_admin)
[ -n "$TB_N" ] && [ -n "$TB_A" ] && ok "B.8 关门实例上 /user/login 仍然公开可换令牌（否则谁也进不来）" || bad "B.8 关门后登录拿不到令牌"
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TB_N")" in
  200*) ok "B.9 会话令牌在关门实例上同样能过 ⇒ 两种凭证并存，不是「只有共享密钥能用」" ;;
  *) bad "B.9 会话令牌被拒" ;;
esac
case "$(req POST "$BB/user/add?accessToken=$TB_N" "{\"username\":\"p22_x\",\"password\":\"$PROBE_PW\",\"role\":\"ADMIN\"}")" in
  403*) ok "B.10 普通会话连「自己填 role=ADMIN」这条路都是 403（注意：role 自填这个洞的真身在 A.1——演示模式下匿名照样建得出 ADMIN，那条今天没补）" ;;
  *) bad "B.10 普通会话建出了 ADMIN" ;;
esac
case "$(req POST "$BB/user/remove?id=$(uid p22_normal)&accessToken=$TB_N")" in
  403*) ok "B.11 普通会话删不掉账号" ;; *) bad "B.11 普通会话能删账号" ;;
esac

LO=$(req POST "$BB/user/logout?accessToken=$TB_N" '{}')
case "$LO" in *'"code":200'*) ok "B.12 logout 成功";; *) bad "B.12 logout 失败：$(printf '%s' "$LO" | cut -f2)";; esac
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TB_N")" in
  403*) ok "B.13 **撤销真生效**：登出后的令牌既解析不出身份、又不等于共享密钥 ⇒ 403（A.18 在演示模式量不到的就是这一条）" ;;
  *) bad "B.13 登出后令牌仍能用" ;;
esac
RE=$(req POST "$BB/user/logout?accessToken=$TB_N" '{}')
case "$RE" in
  403*) ok "B.14 重复登出在**关门**实例上是 403：撤销后的令牌既不是会话也不等于共享密钥，根本走不进 controller（那句「当前请求没有登录态」在这台上是不可达分支——上一版把 A 段的形状写到了 B 段，所以红）" ;;
  *) bad "B.14 重复登出竟然 $(printf '%s' "$RE" | cut -f1)：撤销没落地？" ;;
esac
RE2=$(req POST "$BASE/user/logout?accessToken=$T_NORM" '{}')
case "$RE2" in
  *'没有登录态'*) ok "B.14b 演示模式里同一条令牌第二次登出得到「当前请求没有登录态」⇒ 那句恒成功的「退出成功」不回「令牌不存在」（那是一个「哪些令牌正在用」的探针）" ;;
  *) bad "B.14b 演示模式的重复登出形状不对：$(printf '%s' "$RE2" | cut -f2)" ;;
esac

TN2=$(login "$PORT" p22_normal)
ID_N=$(uid p22_normal)
case "$(req POST "$BB/user/update?accessToken=$TB_A" "{\"id\":$ID_N,\"role\":\"ADMIN\"}")" in
  *'"code":200'*) ok "B.15 管理员会话改角色成功（p22_normal → ADMIN）" ;; *) bad "B.15 改角色失败" ;;
esac
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TN2")" in
  403*) ok "B.16 改角色 ⇒ 该用户**已签发的**会话全部作废（不等 30 min 过期；降权不落地的洞就堵在这里）" ;;
  *) bad "B.16 改完角色旧令牌还在用" ;;
esac
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TB_A")" in
  200*) ok "B.17 别人改角色不动我的会话（invalidateUser 按 userId 精确撤销，没有顺手清库）" ;;
  *) bad "B.17 改一个用户把管理员会话也踢了" ;;
esac
TN3=$(login "$PORT" p22_normal)
case "$(req POST "$BB/user/add?accessToken=$TN3" "{\"username\":\"p22_dup\",\"password\":\"$PROBE_PW\",\"role\":\"NORMAL\"}")" in
  *'"code":200'*) ok "B.18 重新登录得到的是**新角色**的会话（作废 ≠ 把账号锁死）；p22_dup 已建，留给 C 段删" ;;
  *) bad "B.18 提权后重新登录仍进不去：$(printf '%s' "$TN3" | cut -c1-12)" ;;
esac
ID_G=$(uid p22_gate); TA2=$(login "$PORT" p22_gate)
say "  B.19 p22_gate id=$ID_G 令牌长度=${#TA2}（A 段建的那个 NORMAL 账号，用来验删除）"
case "$(req POST "$BB/user/remove?id=$ID_G&accessToken=$TB_A")" in
  *'"code":200'*) ok "B.20 管理员删掉账号" ;; *) bad "B.20 删账号失败" ;;
esac
case "$(req GET "$BB/jobinfo/list?start=0&length=1&accessToken=$TA2")" in
  403*) ok "B.21 删账号 ⇒ 他的会话一并撤销（否则「人已删、身份还能活 30 min」）" ;;
  *) bad "B.21 已删账号的令牌仍在用" ;;
esac
case "$(req POST "$BB/user/login" "{\"username\":\"p22_gate\",\"password\":\"$PROBE_PW\"}")" in
  *'"code":200'*) bad "B.22 已删账号还能登录" ;;
  *) ok "B.22 已删账号登录被拒 ⇒ 与 B.21 是两个独立的闸（一个在签发侧、一个在已发令牌上），少一条另一条都能绿" ;;
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
for u in p22_admin p22_normal p22_gate p22_gate2 p22_dup; do
  i=$(uid "$u"); [ -n "$i" ] && req POST "$BASE/user/remove?id=$i" >/dev/null
done
LEFT=$(usersql | grep -c . )
say "  C.7 剩余探针账号 $LEFT 行（期望 0；不清会污染下一次 /user/list 与账号计数）"
[ "$LEFT" = "0" ] && ok "C.8 E2E 库已还原" || { obs "C.8 还有行没删掉：$(usersql | tr '\n' ' ')"; q -e "DELETE FROM z_schedule_user WHERE username LIKE 'p22\_%'"; obs "C.8b SQL 兜底后剩余=$(usersql | grep -c . )"; }

echo "===================== p22 结果：PASS=$PASS FAIL=$FAIL OBS=$OBS（全文 $OUT）=====================" | tee -a "$OUT"
[ "$FAIL" -eq 0 ]
