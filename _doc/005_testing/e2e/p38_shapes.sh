#!/usr/bin/env bash
# p38_shapes.sh — 取证：deploy 面上 `JAR_FILE` 写成"字面版本号"与"通配"各自的失败形状。
#
# 为什么占一个文件（README §14 有全部读数，这里是让读者能自己复跑的那条命令）：
#   抬版后字面量最坏的那一格**不会红**——rc=0、tag 是新版本、镜像里是 target/ 那份别的 jar。
#   所有下游尺量的都是名字，所以这一格只能靠"把形状量出来"来记账，不能靠门禁。
#   通配把这一格换成"要么唯一、要么当场失败"；改完之后 A6c(p24) 钉文件、B0(p26) 钉插值结果。
#
# 只碰两处真实构件：$DEPLOY/Dockerfile.backend 与 $DEPLOY/docker-compose.split.yml，
# 且都是"现造副本、只改一行"。上下文里放的是 36 字节的假 jar，不重复 103 MB 的真构建。
# 全程用 tag 前缀 p38prey: 与 project 名 p38probe，收尾自证两者都归零 ⇒ 不会碰到别人的容器。
#
# 用法（250 或任何有 docker 的机器）：bash p38_shapes.sh
set -u
cd "$(dirname "$0")"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  [PASS] $*"; }
bad() { FAIL=$((FAIL+1)); echo "  [FAIL] $*"; }
FATAL(){ echo "FATAL: $*"; exit 1; }

DEPLOY=""
for c in deploy ../../deploy ../../../deploy; do
  [ -f "$c/Dockerfile.backend" ] && { DEPLOY="$(cd "$c" && pwd)"; break; }
done
[ -n "$DEPLOY" ] || FATAL "找不到 deploy/Dockerfile.backend（在 e2e 目录里跑，或把 DEPLOY 指对）"
command -v python3 >/dev/null || FATAL "没有 python3"
docker info >/dev/null 2>&1 || FATAL "docker 不可用"
CCLI="docker compose"; docker compose version >/dev/null 2>&1 || CCLI="docker-compose"

W="$(pwd)/logs/p38_shapes.$$"; mkdir -p "$W" || FATAL "建不了 $W"
trap 'rm -rf "$W"' EXIT
CTXT="$W/ctx"; mkdir -p "$CTXT/z-schedule-admin/target"

echo "deploy = $DEPLOY"
echo "compose = $CCLI"

echo ""
echo "=== 1) 造两份只差 ARG 默认值一行的 Dockerfile（其余字节逐字相同）==="
python3 - "$DEPLOY/Dockerfile.backend" "$W/Dockerfile.old" "$W/Dockerfile.new" <<'PY' || FATAL "猎物 Dockerfile 造不出来"
import sys
src, old, new = sys.argv[1:4]
txt = open(src, encoding="utf-8").read()
glob_line = "ARG JAR_FILE=z-schedule-admin/target/*-exec.jar"
lit_line = "ARG JAR_FILE=z-schedule-admin/target/z-schedule-admin-1.0.0-exec.jar"
assert glob_line in txt, "工作树这份已经不是通配了，两份造不出唯一差异"
open(new, "w", encoding="utf-8").write(txt)
open(old, "w", encoding="utf-8").write(txt.replace(glob_line, lit_line))
PY
DOLD=$(grep -c '^ARG JAR_FILE=z-schedule-admin/target/z-schedule-admin-1\.0\.0-exec\.jar$' "$W/Dockerfile.old")
DNEW=$(grep -c '^ARG JAR_FILE=z-schedule-admin/target/\*-exec\.jar$' "$W/Dockerfile.new")
DIFF=$(diff <(sed 's|^ARG JAR_FILE=.*|ARG JAR_FILE=<X>|' "$W/Dockerfile.old") \
            <(sed 's|^ARG JAR_FILE=.*|ARG JAR_FILE=<X>|' "$W/Dockerfile.new") | grep -c '^[<>]' || true)
if [ "$DOLD" = "1" ] && [ "$DNEW" = "1" ] && [ "$DIFF" = "0" ]; then
  ok "两份猎物只在 ARG 默认值一处不同（把该行抹平后 diff 0 行）"
else
  bad "两份不是唯一差异：old=$DOLD new=$DNEW 抹平后 diff=$DIFF 行 ⇒ 下面的形状不能归因到字面量"
fi

printf 'decoy-bytes-p38-not-a-real-artifact\n' > "$CTXT/decoy.jar"
DECOY=$(md5sum "$CTXT/decoy.jar" | awk '{print $1}')
DSIZE=$(wc -c <"$CTXT/decoy.jar" | tr -d ' ')
echo "  假 jar：$DSIZE 字节，md5=$DECOY"

build() { # $1=tag后缀 $2=Dockerfile ⇒ 全局 RC / OUT
  OUT=$(docker build -f "$2" -t "p38prey:$1" "$CTXT" 2>&1); RC=$?
  printf '%s\n' "$OUT" | grep -E "^COPY failed|^When using COPY|Successfully built|file does not exist|no source files" \
      | head -2 | sed 's/^/      /'
  echo "      rc=$RC"
}
inside() { # 镜像里 /app/app.jar 的 md5 —— "tag 写着新版本、里面是谁" 只能这么问
  docker run --rm --entrypoint md5sum "p38prey:$1" /app/app.jar 2>/dev/null | awk '{print $1}'
}

echo ""
echo "=== 2) 五种形状的原文读数 ==="
rm -f "$CTXT/z-schedule-admin/target"/*.jar

# 形状 1：字面量 + 那份 jar 不在盘上
build 1 "$W/Dockerfile.old"
R1=$RC
if [ "$R1" != "0" ] && printf '%s' "$OUT" | grep -q "file does not exist"; then
  ok "形状 1 字面量+缺文件：rc=$R1，COPY failed: … file does not exist（会响，算客气）"
else
  bad "形状 1 读数不符（rc=$R1）：字面量缺文件应当场失败，否则这一格是静默的"
fi

# 形状 2：字面量 + 同名位置放了一份假 jar —— 这一格是改动的全部理由
cp "$CTXT/decoy.jar" "$CTXT/z-schedule-admin/target/z-schedule-admin-1.0.0-exec.jar"
build 2 "$W/Dockerfile.old"
M2=$(inside 2)
if [ "$RC" = "0" ] && [ "$M2" = "$DECOY" ]; then
  ok "形状 2 字面量+同名放一份别的 jar：**rc=0**，而镜像里 /app/app.jar 的 md5 就是那份假 jar（$M2）⇒ 所有量名字的尺全绿"
else
  bad "形状 2 读数不符（rc=$RC 镜像内 md5=${M2:-读不到}）⇒ 静默那一格没被复现，这一档的结论要重估"
fi

# 形状 3：通配 + 一份都不匹配
rm -f "$CTXT/z-schedule-admin/target"/*.jar
build 3 "$W/Dockerfile.new"
if [ "$RC" != "0" ] && printf '%s' "$OUT" | grep -q "no source files were specified"; then
  ok "形状 3 通配+0 命中：rc=$RC，COPY failed: no source files were specified"
else
  bad "形状 3 读数不符（rc=$RC）"
fi

# 形状 4：通配 + 恰好一份
cp "$CTXT/decoy.jar" "$CTXT/z-schedule-admin/target/only-exec.jar"
build 4 "$W/Dockerfile.new"
M4=$(inside 4)
if [ "$RC" = "0" ] && [ "$M4" = "$DECOY" ]; then
  ok "形状 4 通配+唯一命中：rc=0 且镜像里就是那一份（$M4）⇒ 没有第二次选择"
else
  bad "形状 4 读数不符（rc=$RC md5=${M4:-读不到}）⇒ 通配连'唯一'都喂不进去，改法本身有问题"
fi

# 形状 5：通配 + 两份（歧义不许它自选）
cp "$CTXT/z-schedule-admin/target/only-exec.jar" "$CTXT/z-schedule-admin/target/second-exec.jar"
build 5 "$W/Dockerfile.new"
if [ "$RC" != "0" ] && printf '%s' "$OUT" | grep -q "destination must be a directory"; then
  ok "形状 5 通配+2 命中：rc=$RC，'…destination must be a directory…' ⇒ 歧义当场失败，不猜"
else
  bad "形状 5 读数不符（rc=$RC）⇒ 若通配会自选一份，那它和字面量一样会静默"
fi
N=$(ls "$CTXT/z-schedule-admin/target" | wc -l | tr -d ' ')
echo "      形状 5 时盘上有 $N 份：$(ls "$CTXT/z-schedule-admin/target" | tr '\n' ' ')"

echo ""
echo "=== 3) 为什么不用 \${JAR_FILE:?} 必填守卫（compose 对整份文档提前插值）==="
PROBE="$W/probe.split.yml"
ENVF="$W/probe.env"
grep -q 'JAR_FILE: "${JAR_FILE:-z-schedule-admin/target/\*-exec\.jar}"' "$DEPLOY/docker-compose.split.yml" \
  || FATAL "split 清单里那条通配默认的形状变了，守卫探针要跟着改"
sed -E -e 's|^DB_HOST=.*|DB_HOST=127.0.0.1|' -e 's|^DB_PASSWORD=.*|DB_PASSWORD=probe-not-real|' \
    "$DEPLOY/env/.env.example" | grep -v '^JAR_FILE=' > "$ENVF"
NJ=$(grep -c '^JAR_FILE=' "$ENVF" || true); ND=$(grep -c '^DB_' "$ENVF")
python3 - "$DEPLOY/docker-compose.split.yml" "$PROBE" <<'PY' || FATAL "守卫探针写不出来"
import sys
src, dst = sys.argv[1], sys.argv[2]
t = open(src, encoding="utf-8").read()
old = 'JAR_FILE: "${JAR_FILE:-z-schedule-admin/target/*-exec.jar}"'
new = 'JAR_FILE: "${JAR_FILE:?p38 探针：必须显式给 JAR_FILE}"'
assert old in t
open(dst, "w", encoding="utf-8").write(t.replace(old, new))
PY
echo "  env 模板：DB_* $ND 项给齐、JAR_FILE $NJ 处（就是要它缺）"
GR=""
for cmd in config ps down; do
  MSG=$( (cd "$DEPLOY" && env -u JAR_FILE $CCLI --env-file "$ENVF" -p p38probe -f "$PROBE" $cmd 2>&1) | head -1)
  if printf '%s' "$MSG" | grep -q "required variable JAR_FILE is missing"; then
    GR="$GR $cmd"
    echo "      $cmd：$MSG"
  else
    bad "守卫探针在 $cmd 上没有报 JAR_FILE（读到：${MSG:-空}）⇒ 这一档的理由要重估"
  fi
done
if [ "$(echo $GR | wc -w)" = "3" ]; then
  ok "带 :? 守卫时 config/ps/down 三条**全部**拒绝（同一句 required variable JAR_FILE）⇒ 停容器的人也得先知道自己的 jar 叫什么"
fi
# 阳性对照：同一 env，守卫退回通配默认 ⇒ config 必须过
sed -i 's|\${JAR_FILE:?p38 探针：必须显式给 JAR_FILE}|${JAR_FILE:-z-schedule-admin/target/*-exec.jar}|' "$PROBE"
CTRL=$( (cd "$DEPLOY" && env -u JAR_FILE $CCLI --env-file "$ENVF" -p p38probe -f "$PROBE" config 2>&1) | grep -E 'JAR_FILE' | head -1)
if printf '%s' "$CTRL" | grep -q '\*-exec\.jar'; then
  ok "同一条 env 下通配默认渲染照过（'$(printf '%s' "$CTRL" | sed 's/^ *//')'）⇒ 上面三条红是守卫造成的，不是 env 模板坏了"
else
  bad "通配对照没渲染出通配（读到：${CTRL:-空}）⇒ 第三节的对照无效"
fi

echo ""
echo "=== 4) 自证不伤别人 ==="
PIMG=$(docker images -a -q p38prey 2>/dev/null | wc -l | tr -d ' ')
PCON=$(docker ps -a --format '{{.Names}}' | grep -c p38probe || true)
[ "$PIMG" != "0" ] && docker rmi -f $(docker images -a -q p38prey | sort -u | tr '\n' ' ') >/dev/null 2>&1
PIMG=$(docker images -a -q p38prey 2>/dev/null | wc -l | tr -d ' ')
if [ "$PIMG" = "0" ] && [ "$PCON" = "0" ]; then
  ok "自证：p38prey 镜像残留 $PIMG、p38probe 容器残留 $PCON（工作文件全在 $W，退出即删）"
else
  bad "自证失败：镜像 $PIMG 容器 $PCON ⇒ 这一档留下过东西，去清"
fi
echo "  deploy 面未被本脚本写入：$(grep -c 'JAR_FILE: "${JAR_FILE:-z-schedule-admin/target/\*-exec\.jar}"' "$DEPLOY/docker-compose.split.yml") 处通配默认仍在原位"

echo ""
echo "总判：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ] && { echo "VERDICT: OK —— 五种形状与守卫探针都按读数复现（README §14 的每一条都出自这个 rc=0）"; exit 0; } \
                  || { echo "VERDICT: FAIL —— 上面点名的形状与 §14 不符，先改文档再重跑"; exit 1; }
