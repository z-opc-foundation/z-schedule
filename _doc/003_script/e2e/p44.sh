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
#
# 用法：bash p44.sh                全跑（C1 会下 node 并跑 npm，首次可达数分钟）
#       P44_SKIP_PACKAGE=1 bash p44.sh   复用 target 里现成的 exec jar（只跑 C0、C2—C5）
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
