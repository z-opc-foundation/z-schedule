#!/usr/bin/env bash
# p44.sh — 干净机器彩排：从"检出"走到"有一个真在应答 HTTP 的 admin 进程"，中间每一步要么产出读数要么红。
#
# 为什么有这一档（2026-09-27 在 136（zifang002，全新装 openjdk-8 + maven 3.8.7、无 ~/.m2）上实测的三条，本机全看不见）：
#   1) 顶层 pom 从没钉 maven-surefire-plugin ⇒ 哪些测试会跑由构建机的 Maven 决定：本机 3.9.14 打
#      surefire:3.5.4，136 的 3.8.7 打 2.12.4（后者没有 JUnit-Platform provider）。
#   2) admin 依赖 com.zifang:z-agent-llm-gateway-core:1.0.0-SNAPSHOT，而全仓没有任何 <repositories>
#      ⇒ 干净机器 mvn 在依赖解析处 BUILD FAILURE（前面 746 次下载白跑，admin 那 6 例根本不跑）。
#      本机跑得绿只因为 ~/.m2 里躺着一份手工装进去的件；那条依赖声称的调用方 TokenBurnJobHandler
#      全仓只有 pom 提到它（grep -rln 命中数 1 = pom 自己）。
#   3) dev profile 下"引擎池"与"Spring 池"是两个不同的 H2 内存库，且引擎那一份的 URL 是
#      DevDataSourceConfig 里的编译期常量 ⇒ 想靠 --spring.datasource.url 塞 INIT=RUNSCRIPT
#      把建表脚本喂给调度引擎，结构上做不到（C5 只记读数不判红，等 #32 拍板）。
#   4) 本机装好之后又发现的第二类"只有读交付物字节才看得见"的断口（#46）：admin 里留的是 SLF4J
#      **2.x** 那一代绑定（log4j-slf4j2-impl），而 slf4j-api 是 Boot 2.7 管的 1.7.36 ⇒ 一支绑定都
#      接不上，运行时日志静默 NOP。C6 就是把这条钉成结构判据：判据**不是**"绑定恰好一条"（坏件里
#      恰好只有一条，那样会当场放行），而是"与 slf4j-api 同代的绑定恰好一条"。
#
# 用法：bash p44.sh                全跑（C1 会下 node 并跑 npm，首次可达数分钟）
#       P44_SKIP_PACKAGE=1 bash p44.sh   复用 target 里现成的 exec jar（只跑 C0、C2、C5、C6、C3—C4）
#       P44_INJECT=1 bash p44.sh         自检：临时塞一条 SNAPSHOT 依赖，C0 必须变红
# 退出码：0=断言全成立；1=有断言不成立；2=前置不满足（没量到，不等于通过）
set -u
cd "$(dirname "$0")/../../.." || exit 2
REPO="$(pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  [PASS] $*"; }
bad() { FAIL=$((FAIL+1)); echo "  [FAIL] $*"; }
FATAL(){ echo "FATAL: $*"; exit 2; }

# zip 读取一律走 python3：136 上没装 unzip/strings，而这台机器上 tally 也靠 python3，不能假设有。
zx_list() { python3 -c 'import sys,zipfile; [print(n) for n in zipfile.ZipFile(sys.argv[1]).namelist()]' "$1"; }
zx_cat()  { python3 -c 'import sys,zipfile; print(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]).decode("utf-8","replace"))' "$1" "$2"; }
# 从 .class 字节里挑出可打印串（等价 strings，但不依赖 binutils）
zx_strings() { zx_cat "$1" "$2" | tr -c '[:print:]' '\n'; }

# 起进程必须显式挑 JDK：PATH 上第一个 java 在本机是 25（Boot 2.7 在它上面起不来），
# 在 136 上是 1.8。跟着 PATH 走会把"这棵树起不来"误记成环境问题。
JAVA="java"
if [ -x /usr/libexec/java_home ]; then
  JH=$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)
  [ -n "$JH" ] && JAVA="$JH/bin/java"
fi

echo "repo = $REPO"
echo "java = $(java -version 2>&1 | head -1)"
echo "JAVA(用来起进程) = $JAVA"
echo "mvn  = $(mvn -v 2>/dev/null | sed -n '2p')"

# ---------- C0 从干净检出可构建的结构判据：依赖坐标里不许出现 -SNAPSHOT ----------
snapshot_hits() {
  grep -rn "<version>[^<]*-SNAPSHOT</version>" --include="pom.xml" "$REPO" 2>/dev/null \
    | grep -v '\${revision}'
}
SNAPHITS=$(snapshot_hits | grep -c . || true)

if [ "${P44_INJECT:-0}" = "1" ]; then
  echo "=== 自检（P44_INJECT=1）：C0 有没有牙？ ==="
  TARGET="$REPO/z-schedule-admin/pom.xml"
  BAK="/tmp/p44_pom_bak_$$"
  [ -f "$TARGET" ] || FATAL "注入目标不存在：$TARGET"
  cp "$TARGET" "$BAK" || FATAL "备份失败"
  MD5_BEFORE=$( (md5sum "$TARGET" 2>/dev/null || md5 -q "$TARGET") | awk '{print $1}')
  python3 - "$TARGET" <<'PY' || FATAL "注入失败（锚点没找到）"
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
needle = "        <!-- Actuator (健康检查) -->"
if needle not in s:
    sys.exit(1)
inject = ("        <dependency>\n            <groupId>com.example</groupId>\n"
          "            <artifactId>p44-injected</artifactId>\n"
          "            <version>9.9.9-SNAPSHOT</version>\n        </dependency>\n\n")
open(p, 'w', encoding='utf-8').write(s.replace(needle, inject + needle, 1))
PY
  AFTER=$(snapshot_hits | grep -c . || true)
  cp "$BAK" "$TARGET"; rm -f "$BAK"
  MD5_AFTER=$( (md5sum "$TARGET" 2>/dev/null || md5 -q "$TARGET") | awk '{print $1}')
  [ "$MD5_BEFORE" = "$MD5_AFTER" ] || FATAL "还原失败：md5 不一致 $MD5_BEFORE -> $MD5_AFTER"
  if [ "$AFTER" -ge 1 ]; then ok "注入一条 SNAPSHOT 依赖后 C0 数到 $AFTER 处命中（有牙，且已按字节还原）"; else
    bad "注入后 C0 仍数到 0 ⇒ 这把尺是空的"; fi
  echo "PASS=$PASS FAIL=$FAIL"
  [ "$FAIL" -eq 0 ] || exit 1
  exit 0
fi

if [ "$SNAPHITS" -eq 0 ]; then
  ok "C0 依赖坐标零 SNAPSHOT ⇒ 干净机器不会卡在解析上（阳性对照：见 P44_INJECT=1 那一支）"
else
  bad "C0 有 $SNAPHITS 处 -SNAPSHOT 依赖，而仓里没有 <repositories> 供得到："; snapshot_hits | sed 's/^/         /'
fi

# ---------- C1 出构件 ----------
JAR_DIR="$REPO/z-schedule-admin/target"
PKGLOSS="/tmp/p44_pkg_$$.log"
if [ "${P44_SKIP_PACKAGE:-0}" != "1" ]; then
  echo "=== C1 构建 exec jar（-am 从源码，不吃本机 ~/.m2 里任何 z-schedule 件）==="
  rm -f "$JAR_DIR"/*-exec.jar 2>/dev/null
  T0=$(date +%s)
  mvn -B package -DskipTests -pl z-schedule-admin -am > "$PKGLOSS" 2>&1; PKG_RC=$?
  T1=$(date +%s)
  if [ "$PKG_RC" -ne 0 ]; then
    grep -E "^\[ERROR\]" "$PKGLOSS" | head -5 | sed 's/^/         /'
    bad "C1 package rc=$PKG_RC（$((T1-T0))s）—— 干净机器连构件都出不来"
    exit 1
  fi
  ok "C1 package rc=0，用时 $((T1-T0))s；npm 那一段是否真跑了：$(grep -cE 'install-node-and-npm|npm (install|run build)|frontend-maven-plugin' "$PKGLOSS") 行日志"
fi
NJ=$(ls "$JAR_DIR"/*-exec.jar 2>/dev/null | grep -c . || true)
[ "$NJ" -eq 1 ] || FATAL "target 里 *-exec.jar 命中 $NJ 个（要恰好 1 个：0=没构建，≥2=版本漂移）"
JAR=$(ls "$JAR_DIR"/*-exec.jar | head -1)
echo "jar = $(basename "$JAR")  bytes=$(wc -c < "$JAR" | tr -d ' ')"

# ---------- C2 回归护栏：那条已摘的 SNAPSHOT 不许回到交付物里 ----------
LIBS=$(zx_list "$JAR" | grep -c "^BOOT-INF/lib/.*\.jar$" || true)
HASLIB=$(zx_list "$JAR" | grep -c "z-agent-llm-gateway-core" || true)
if [ "$HASLIB" -eq 0 ] && [ "$LIBS" -gt 10 ]; then
  ok "C2 交付物里没有 z-agent-llm-gateway-core（阳性对照：BOOT-INF/lib 共 $LIBS 个 jar，其中有 h2：$(zx_list "$JAR" | grep -c "BOOT-INF/lib/h2-")）"
elif [ "$HASLIB" -gt 0 ]; then
  bad "C2 交付物里仍有 z-agent-llm-gateway-core ⇒ 依赖被加回来了"
else
  FATAL "C2 BOOT-INF/lib 只数到 $LIBS 个 jar（<10）⇒ 这不是一个 fat jar，尺读不到东西"
fi

# ---------- C5 字节取证：dev 的两个池是不是同一个库，引擎那一份能不能配 ----------
SPRING_URL=$(zx_cat "$JAR" "BOOT-INF/classes/application-dev.yml" | grep -oE 'jdbc:h2:[^" ]+' | head -1)
ENGINE_URL=$(zx_strings "$JAR" "BOOT-INF/classes/com/zifang/z/schedule/admin/DevDataSourceConfig.class" | grep -oE 'jdbc:h2:[^;"]+' | head -1)
[ -n "$SPRING_URL" ] || FATAL "C5 从 jar 里读不到 application-dev.yml 的 H2 URL（形状变了，这把尺要改）"
[ -n "$ENGINE_URL" ] || FATAL "C5 从 jar 里读不到 DevDataSourceConfig 的 H2 常量（形状变了）"
echo "         Spring 池 URL（能被 --spring.datasource.url 覆盖）= $SPRING_URL"
echo "         引擎池 URL（class 常量，覆盖不到）              = $ENGINE_URL"
SDBIND=$(zx_strings "$JAR" "BOOT-INF/classes/com/zifang/z/schedule/admin/DevDataSourceConfig.class" | grep -c "spring\.datasource" || true)
if [ "$SDBIND" -eq 0 ]; then
  ok "C5 引擎池 URL 不来自任何 spring.datasource.* 键（常量池命中 0；阳性对照：同一次读取里 jdbc:h2 命中 $(zx_strings "$JAR" "BOOT-INF/classes/com/zifang/z/schedule/admin/DevDataSourceConfig.class" | grep -c 'jdbc:h2')）⇒ 建表脚本喂不进调度引擎"
else
  bad "C5 引擎池开始读 spring.datasource.*（命中 $SDBIND）⇒ 行为变了，README §21 要跟着改"
fi

# ---------- C6 SLF4J 绑定的"代"必须与被解析到的 slf4j-api 对得上 ----------
# 为什么不能只数"有几条绑定"：#46 那一份坏件里 BOOT-INF/lib 恰好只有 **一条** 绑定
# （log4j-slf4j2-impl-2.26.1），"恰好一条"这种判据会当场放行它——而 slf4j-api 是 1.7.36，
# 1.7 只认 StaticLoggerBinder、不认 2.x 的 ServiceLoader Provider ⇒ 可用绑定数其实是 0，
# 运行时静默 NOP。所以尺读的是"与 api 同代的绑定恰好一条"，而不是"绑定恰好一条"。
# jul-to-slf4j / log4j-to-slf4j 是**路由器**不是绑定，混进来的话每支都会假红，故显式排掉。
c6_scan() {
  python3 - "$1" <<'PY'
import sys, zipfile, re
BIND_1X = {"log4j-slf4j-impl", "slf4j-simple", "slf4j-log4j12", "slf4j-nop",
           "slf4j-jdk14", "slf4j-reload4j", "logback-classic"}
BIND_2X = {"log4j-slf4j2-impl"}
ROUTER  = {"jul-to-slf4j", "log4j-to-slf4j", "log4j-to-slf4j12"}

def parts(name):
    b = name.split("/")[-1][:-4]
    m = re.match(r"^(.*?)-(\d[\w.\-]*)$", b)
    return (m.group(1), m.group(2)) if m else (b, "")

def vtuple(v):
    out = []
    for seg in re.split(r"[.\-]", v):
        out.append(int(seg) if seg.isdigit() else 0)
    return tuple(out) + (0,)

def gen(art, ver):
    # 代**先按构件名判**：log4j-slf4j-impl / log4j-slf4j2-impl 的版本号跟的是 log4j2 的版本
    # （2.17.2、2.26.1），拿它跟 slf4j 的 2.0 比会把 1.x 的桥接件误判成 2.x —— 第一版就踩了，
    # 是"应当判绿的那支猎物"当场把它红出来的（见下面 fixed-shape）。
    if art == "log4j-slf4j-impl":
        return 1
    if art == "log4j-slf4j2-impl":
        return 2
    if art == "logback-classic":          # 1.3+ 才供 SLF4J 2.x Provider
        return 2 if vtuple(ver) >= (1, 3) else 1
    # slf4j-api / slf4j-simple / slf4j-nop / slf4j-jdk14 / slf4j-log4j12：版本跟 slf4j 自己走
    return 2 if vtuple(ver) >= (2, 0) else 1

api_ver, bindings, core_ver, api2_ver = "", [], "", ""
for n in zipfile.ZipFile(sys.argv[1]).namelist():
    if not re.match(r"BOOT-INF/lib/.*\.jar$", n):
        continue
    art, ver = parts(n)
    if art == "slf4j-api":
        api_ver = ver
    elif art == "log4j-core":
        core_ver = ver
    elif art == "log4j-api":
        api2_ver = ver
    elif art in (BIND_1X | BIND_2X) and art not in ROUTER:
        bindings.append((art, ver))

print("API|%s|gen%s" % (api_ver or "ABSENT", gen("slf4j-api", api_ver) if api_ver else "?"))
print("BIND|%d|%s" % (len(bindings), ",".join("%s-%s" % b for b in bindings) or "-"))
if not api_ver:
    print("VERDICT|bad|没有 slf4j-api ⇒ 尺读不到 api 的代，不能判绿")
    sys.exit(0)
g = gen("slf4j-api", api_ver)
usable = [b for b in bindings if gen(*b) == g]
print("USABLE|%d" % len(usable))
if len(usable) == 0:
    print("VERDICT|bad|slf4j-api %s 要 gen%d 绑定，可用 0 条（在场绑定 %s）⇒ 运行时静默 NOP"
          % (api_ver, g, [("%s-%s" % b) for b in bindings] or "无"))
elif len(usable) > 1:
    print("VERDICT|bad|%d 条同代绑定 %s ⇒ SLF4J 任取一支，谁赢由 classpath 顺序决定"
          % (len(usable), [("%s-%s" % b) for b in usable]))
elif usable[0][0].startswith("log4j-slf4j") and core_ver and vtuple(usable[0][1])[:2] != vtuple(core_ver)[:2]:
    print("VERDICT|bad|绑定 %s-%s 与被解析到的 log4j-core %s 不同代（NoSuchMethodError 那一档）"
          % (usable[0][0], usable[0][1], core_ver))
else:
    print("VERDICT|ok|api %s + 绑定 %s-%s 同代%s" % (
        api_ver, usable[0][0], usable[0][1],
        "，log4j-core 同代 " + core_ver if usable[0][0].startswith("log4j-slf4j") and core_ver else ""))
PY
}
c6_make_prey() {  # c6_make_prey <名> <jar1> <jar2> ... —— 只装 BOOT-INF/lib 条目，C6 读的就是条目名
  python3 - "$@" <<'PY'
import sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(out, "w") as z:
    z.writestr("BOOT-INF/lib/", "")
    for j in sys.argv[2:]:
        z.writestr("BOOT-INF/lib/" + j, b"")
PY
}
WORK44="/tmp/p44_work_$$"; mkdir -p "$WORK44" || FATAL "C6 建不了工作目录"
C6=$(c6_scan "$JAR" 2>/dev/null) || FATAL "C6 扫描器在真件上没跑起来（python3 或 zip 形状坏了）"
C6_API=$(printf '%s\n' "$C6" | grep '^API|' | head -1)
C6_BIND=$(printf '%s\n' "$C6" | grep '^BIND|' | head -1)
C6_USE=$(printf '%s\n' "$C6" | grep '^USABLE|' | head -1)
C6_V=$(printf '%s\n' "$C6" | grep '^VERDICT|' | head -1)
C6_KIND=$(printf '%s' "$C6_V" | cut -d'|' -f2)
echo "         $C6_API  $C6_BIND  $C6_USE"
if [ "$C6_KIND" = ok ]; then
  ok "C6 真件交付物：与 slf4j-api 同代的绑定恰好一条（$(printf '%s' "$C6_V" | cut -d'|' -f3)）"
else
  bad "C6 真件交付物绑定不成立：$(printf '%s' "$C6_V" | cut -d'|' -f3-)"
fi
# 五支猎物：三支必须点名（含 #46 那一支"只有一条绑定但代不对"），两支必须不误伤
c6_prey() {  # c6_prey <期望 ok|bad> <标签> <prey jars...>
  local want="$1" name="$2"; shift 2
  local f="$WORK44/$name.jar"
  c6_make_prey "$f" "$@" || { bad "C6 猎物 $name 造不出来"; return; }
  local v; v=$(c6_scan "$f" | grep '^VERDICT|' | head -1)
  local k; k=$(printf '%s' "$v" | cut -d'|' -f2)
  if [ "$k" = "$want" ]; then ok "C6 猎物 $name 判为 $want（与预期同）：$(printf '%s' "$v" | cut -d'|' -f3-)"; else
    bad "C6 猎物 $name 期望 $want，实际 $k ⇒ 尺判据坏了：$(printf '%s' "$v" | cut -d'|' -f3-)"; fi
}
c6_prey bad gen-mismatch-46-shape        slf4j-api-1.7.36.jar log4j-slf4j2-impl-2.26.1.jar log4j-core-2.17.2.jar log4j-api-2.17.2.jar jul-to-slf4j-1.7.36.jar
c6_prey bad two-usable                   slf4j-api-1.7.36.jar log4j-slf4j-impl-2.17.2.jar logback-classic-1.2.11.jar log4j-core-2.17.2.jar
c6_prey bad core-generation-skew         slf4j-api-1.7.36.jar log4j-slf4j-impl-2.26.1.jar log4j-core-2.17.2.jar
c6_prey bad api2-with-only-1x-binding    slf4j-api-2.0.13.jar log4j-slf4j-impl-2.17.2.jar log4j-core-2.17.2.jar
c6_prey ok fixed-shape                   slf4j-api-1.7.36.jar log4j-slf4j-impl-2.17.2.jar log4j-core-2.17.2.jar log4j-api-2.17.2.jar jul-to-slf4j-1.7.36.jar
c6_prey ok api2-with-2x-binding          slf4j-api-2.0.13.jar log4j-slf4j2-impl-2.26.1.jar log4j-core-2.26.1.jar log4j-api-2.26.1.jar
rm -rf "$WORK44"

# ---------- C3/C4 起进程并逐路径量码：整条命令里不给 disabled=true ----------
free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
code() { curl -s -o "$2" -w '%{http_code}' "$1" 2>/dev/null; }
PORT=$(free_port); LOG="/tmp/p44_boot_$$.log"; BODY="/tmp/p44_body_$$.txt"
PID=""
cleanup() { [ -n "$PID" ] && kill -9 "$PID" 2>/dev/null; rm -f "$LOG" "$BODY" "$PKGLOSS"; return 0; }
trap cleanup EXIT INT TERM
"$JAVA" -jar "$JAR" --spring.profiles.active=dev --server.port="$PORT" \
     --server.servlet.context-path=/meta > "$LOG" 2>&1 &
PID=$!
I=0; C=""
while [ $I -lt 120 ]; do
  C=$(code "http://127.0.0.1:$PORT/meta/" "$BODY")
  [ -n "$C" ] && [ "$C" != "000" ] && break
  kill -0 "$PID" 2>/dev/null || { C=DEAD; break; }
  I=$((I+1)); sleep 1
done
if [ "$C" = "DEAD" ]; then
  bad "C3 只给 --spring.profiles.active=dev 起不来（进程已退出）"; tail -25 "$LOG" | sed 's/^/         /'
elif [ -z "$C" ] || [ "$C" = "000" ]; then
  bad "C3 120s 内没有任何 HTTP 应答（没量到，不是通过）"
else
  ok "C3 不给 --z.base.db.schedule.disabled=true 也起来了（第 $((I+1))s 应答 $C，端口 $PORT）"
  if grep -q "DevDataSourceConfig.dataSourceSchedule() creating H2" "$LOG"; then
    ok "C3b 日志确认引擎池是 admin 那份 H2 替身，不是 starter 的 MySQL 模板"
  else
    bad "C3b 日志里没有 DevDataSourceConfig 建 H2 那行 ⇒ 按名退让没发生"
  fi
  for p in /meta/ /meta/actuator/health /meta/jobinfo/list /meta/dashboard/stats; do
    CC=$(code "http://127.0.0.1:$PORT$p" "$BODY"); LL=$(wc -c < "$BODY" | tr -d ' ')
    echo "         $p -> $CC len=$LL"
  done
  DL=$(code "http://127.0.0.1:$PORT/meta/jobinfo/list" "$BODY")
  if [ "$DL" = "500" ]; then
    ok "C4 无库时数据接口是 500 而不是假 200 ⇒ 干净机器的天花板就是天花板（#32 的那条路还没修）"
  else
    bad "C4 /meta/jobinfo/list 不再是 500（现在 $DL）⇒ 要么 #32 那条零依赖试用路已修，要么尺坏了，README §21 要改"
  fi
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
