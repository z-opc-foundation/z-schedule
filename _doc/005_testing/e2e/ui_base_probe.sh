#!/usr/bin/env bash
# ui_base_probe.sh —— 量"前端资源基路径"与"后端 context-path"这一对耦合，两条本地路各跑一遍。
#
# 为什么要有这支（#42 实测）：admin 的 jar 里那份 index.html 引用的是绝对路径
# `<BASE>/assets/index-*.js`（vite `base` 默认 '/meta/'，而 admin pom 的 frontend-maven-plugin
# 不传 VITE_BASE ⇒ 每次 package 都把这个前缀烤进 jar）。而本仓文档里的两条本地命令
# （`mvn spring-boot:run` / `java -jar *-exec.jar`）都不设 context-path ⇒ 进程挂在 `/` 上，
# 于是页面 200、它自己声明的两条资源 404 —— **界面是白的**。这不是"数据接口 500"的连带症状：
# 这里一条库都没问，纯静态资源。
#
# 前缀是从 **jar 里读回来的**，不是从 vite.config.ts 猜的：源码可以改，交付物才是事实。
#
# 用法（无参数）：bash _doc/005_testing/e2e/ui_base_probe.sh
# 前置：target 里恰好一个 *-exec.jar（没有就先 `mvn -B -pl z-schedule-admin -am package -DskipTests`）
# 退出码：0=全部断言成立；1=有断言不成立（耦合的形状变了）；2=前置不满足（没量到，不是通过）
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
JAR_DIR="$REPO/z-schedule-admin/target"

# 不用数组：本机 bash 3.2 在 set -u 下读空数组的 ${#a[@]} 会直接 unbound 退出。
JARS="$(ls "$JAR_DIR"/*-exec.jar 2>/dev/null | sed -n '1,5p')"
NJARS=$(printf '%s' "$JARS" | grep -c . )
if [ "$NJARS" -ne 1 ]; then
  echo "FATAL target 里 *-exec.jar 命中 $NJARS 个（要恰好 1 个）：0 个=没构建，≥2 个=版本漂移，两种都不许猜"
  exit 2
fi
JAR="$(printf '%s\n' "$JARS" | grep . | head -1)"

JH=""
if [ -x /usr/libexec/java_home ]; then JH=$(/usr/libexec/java_home -v 1.8 2>/dev/null || true); fi
if [ -z "$JH" ]; then JH="${JAVA_HOME:-}"; fi
JAVA="java"; [ -n "$JH" ] && JAVA="$JH/bin/java"
echo "jar   = $JAR"
echo "java  = $JAVA"

# 从 jar 里读回前缀与两条资源路径（交付物自证）
RES=$(unzip -p "$JAR" BOOT-INF/classes/static/index.html \
        | grep -oE '(src|href)="[^"]+"' | sed -E 's/.*"([^"]*)"/\1/' | grep '^/')
if [ -z "$RES" ]; then echo "FATAL jar 内 index.html 里没有绝对路径的资源引用（形状变了，这把尺要改）"; exit 2; fi
BASE=$(printf '%s\n' "$RES" | head -1 | sed -E 's#^(/[^/]+)/.*#\1#')
echo "base  = $BASE  （jar 内 index.html 声明的前缀）"
echo "res   = $(printf '%s' "$RES" | tr '\n' ' ')"

TMP=$(mktemp -d)
trap 'if [ -s "$TMP/pid" ]; then kill -9 "$(cat "$TMP/pid")" 2>/dev/null; fi; rm -rf "$TMP"' EXIT INT TERM

free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
code() { curl -s -o /dev/null -w '%{http_code}' "$1" 2>/dev/null; }

# 起一个进程（pid 落文件 ⇒ 调用方在子 shell 里也能拿到），等它任何一次 HTTP 应答，
# 打 "<port> <首次码>"；起不来打 "DEAD"。
# 不用 /actuator/health 判起住：dev 的 H2 是空库、readiness 组含 db ⇒ 整体 503，
# 健康码非 200 而进程其实好着，拿它当就绪信号会白等 90s 再误判成"起不来"。
boot() {  # $1=日志 $2... = 额外应用参数
  local log=$1; shift
  local p; p=$(free_port)
  "$JAVA" -jar "$JAR" --spring.profiles.active=dev --z.base.db.schedule.disabled=true \
      --server.port="$p" "$@" > "$log" 2>&1 &
  echo "$!" > "$TMP/pid"
  local i c=""
  for i in $(seq 1 90); do
    c=$(code "http://127.0.0.1:$p/")
    if [ -n "$c" ] && [ "$c" != 000 ]; then printf '%s %s\n' "$p" "$c"; return 0; fi
    kill -0 "$(cat "$TMP/pid")" 2>/dev/null || { echo DEAD; return 1; }
    sleep 1
  done
  echo DEAD
}

PASS=0; FAIL=0
want() {  # $1=实际 $2=期望 $3=说明
  if [ "$1" = "$2" ]; then PASS=$((PASS+1)); printf '  ok   %-46s %s（期望 %s）\n' "$3" "$1" "$2"
  else FAIL=$((FAIL+1));                       printf '  FAIL %-46s %s（期望 %s）\n' "$3" "$1" "$2"; fi
}
stop() { [ -s "$TMP/pid" ] && { kill -9 "$(cat "$TMP/pid")" 2>/dev/null; wait "$(cat "$TMP/pid")" 2>/dev/null; : > "$TMP/pid"; }; }

echo
echo "=== 路 1：文档里那条不设 context-path 的命令（进程挂在 /）==="
R1=$(boot "$TMP/no_ctx.log")
if [ "$R1" = DEAD ]; then echo "FAIL 路 1 起不来"; tail -20 "$TMP/no_ctx.log"; FAIL=$((FAIL+1));
else
  set -- $R1; P1=$1; echo "port=$P1 ctx='' 首次应答=$2"
  want "$(code "http://127.0.0.1:$P1/")" 200 'GET /  （index.html 在）'
  while IFS= read -r r; do
    [ -z "$r" ] && continue
    want "$(code "http://127.0.0.1:$P1$r")" 404 "GET $r  （页面自己声明的资源）"
    want "$(code "http://127.0.0.1:$P1${r#$BASE}")" 200 "GET ${r#$BASE}  （同一文件剥掉前缀）"
  done <<< "$RES"
fi
stop

echo
echo "=== 路 2：补 --server.servlet.context-path=$BASE ==="
R2=$(boot "$TMP/with_ctx.log" "--server.servlet.context-path=$BASE")
if [ "$R2" = DEAD ]; then echo "FAIL 路 2 起不来"; tail -20 "$TMP/with_ctx.log"; FAIL=$((FAIL+1));
else
  set -- $R2; P2=$1; echo "port=$P2 ctx='$BASE' 首次应答=$2"
  want "$(code "http://127.0.0.1:$P2$BASE/")" 200 "GET $BASE/  （index.html）"
  while IFS= read -r r; do
    [ -z "$r" ] && continue
    want "$(code "http://127.0.0.1:$P2$r")" 200 "GET $r"
    want "$(code "http://127.0.0.1:$P2${r#$BASE}")" 404 "GET ${r#$BASE}  （剥掉前缀反而不在）"
  done <<< "$RES"
fi
stop

echo
echo "PASS=$PASS FAIL=$FAIL"
echo '读法：路 1 里"页面 200、它自己声明的资源 404、同一个文件在剥掉前缀的路径上 200"'
echo '      三条同时成立，才证明白屏的原因是 base 前缀与 context-path 不一致 ——'
echo '      而不是前端产物没进 jar（那种形状下连 /assets/* 也是 404）。'
[ "$FAIL" -eq 0 ] || exit 1
