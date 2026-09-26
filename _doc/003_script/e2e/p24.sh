#!/usr/bin/env bash
# p24.sh — 部署面彩排：deploy/ 下的清单与镜像，从"能不能解析"一路查到"照清单真起得来、真执行一次"
#
# 为什么有这一档（2026-09-27 本轮查出的五条，都在提交树里躺了很久）：
#   1) deploy/bin/ 五个脚本里的变量全被写成 "$ VAR"（$ 与名字之间多一个空格）。bash 不报语法错
#      （bash -n rc=0），但展开成字面量 "$ VAR"：k8s-apply.sh 拿不到渲染目录、build-images.sh 的
#      ROOT 指错一级、start-mode*.sh 的 cd 落到父目录。这条从 c8e3a8f（09-17）起就在树里 ⇒ 三个
#      "一键"入口从未跑通过。
#   2) 后端清单只给 SPRING_DATASOURCE_PASSWORD + SPRING_PROFILES_ACTIVE=k8s。调度引擎跑的池是
#      starter 的 dataSourceSchedule，键在 z.base.db.schedule.*，读不到就退回内置默认
#      jdbc:mysql://localhost:3306/（库名为空）；而 spring.datasource.* 建的是另一个池。
#      照改前的清单起，引擎池连的是本机不存在的库。
#   3) 清单 image: 多写一段命名空间（ghcr.io/yuku123/yuku123/z-schedule-admin），而
#      build-images.sh 打的是 ghcr.io/yuku123/z-schedule-admin ⇒ pod 拉的是从没构建过的名字。
#   4) nginx 把 /api/ 反代到 /meta/api/，而后端没有任何 /api/** 控制器 ⇒ runbook 里
#      "验证反代：curl http://localhost/api/actuator/health" 必 404。
#   5) compose 三种模式与 Makefile 都写 ${DB_HOST:-mysql} / DB_HOST ?= mysql，而**没有任何一种模式
#      自带叫 mysql 的服务**（Mode 1 只有一个容器）；同时 compose 只自动读 deploy/.env，本仓模板在
#      deploy/env/ 下 ⇒ 照 README 走的人 DB_* 根本读不到。现在五个 DB_* 键都没有默认值，缺了当场拒（B13；
#      守卫补成整套是在 #31 那一格，形状与逐变量双向覆盖见 p25.sh 的 P2d/P18）。
#
# 臂 A 不碰 docker，只问"清单自己前后一致吗"；臂 B 在 250 上用 docker 跑真容器，并带一条
# **负对照**：把改前那套 env（只喂 Spring 池 + 幻影 profile）原样喂进去，引擎池必须当场坏给用户看（B12）；B13 再拿 compose
# 的插值守卫做双向（五个键全不给必须非 0 且报 required variable DB_*，给齐必须两个池同源）。
#
# ⚠ 本档不是在真集群里 apply：250 的 k3s 建不出任何 pod 沙箱（pause 镜像拉不到，见 README §11），
#   所以臂 B 是"把清单渲染出来的 env 与探针路径原样交给 docker run"，集群侧的 kubelet 行为未验。
#
# 用法（在 250）：./p24.sh            全跑（A+B）
#                 P24_A_ONLY=1 ./p24.sh   只跑静态臂
set -u
cd "$(dirname "$0")"

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $*"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $*"; }
FATAL(){ echo "FATAL: $*"; exit 1; }
W="$$"; WORK="$(pwd)/logs/p24_$W"; mkdir -p "$WORK"

DEPLOY=""
for c in deploy ../deploy ../../deploy ../../../deploy; do
  [ -d "$c/k8s" ] && { DEPLOY="$(cd "$c" && pwd)"; break; }
done
[ -n "$DEPLOY" ] || FATAL "找不到 deploy/ 目录（DEPLOY=... 手动指）"
E2E="${E2E:-$(pwd)}"
command -v python3 >/dev/null || FATAL "没有 python3"

echo "deploy = $DEPLOY"
echo "=== 臂 A：清单静态一致性（不碰 docker）==="

# A1 "$ " 碎变量清零，且这把尺有牙（同模式在人造猎物上必须命中）
printf 'cd "$(dirname "$ 0")/.."\n' > "$WORK/prey.sh"
if grep -qE '\$ [0-9A-Za-z_@*]' "$WORK/prey.sh"; then
  HIT=$(grep -rlE '\$ [0-9A-Za-z_@*]' "$DEPLOY"/bin/*.sh 2>/dev/null | tr '\n' ' ')
  [ -z "$HIT" ] && ok "A1 deploy/bin/*.sh 里没有 \"\$ \" 碎变量（人造猎物对照命中，尺有牙）" \
                || bad "A1 仍有碎变量：$HIT"
else
  FATAL "A1 尺自身坏了：连人造的 \"\$ 0\" 都没命中，后面别信"
fi

# A2 bash -n 全过；负对照：坏语法必须让 bash -n 红
NERR=0
for f in "$DEPLOY"/bin/*.sh; do bash -n "$f" 2>/dev/null || { echo "    bash -n 红：$f"; NERR=$((NERR+1)); }; done
if bash -n <<< 'if true; then' >/dev/null 2>&1; then
  FATAL "A2 bash -n 对坏语法也返回 0 ⇒ 这一条判据无意义"
elif [ "$NERR" = "0" ]; then
  ok "A2 五个入口脚本语法都过（同尺在 'if true; then' 上确实报红）"
else
  bad "A2 有 $NERR 个脚本语法不过"
fi

# A3 渲染：与 bin/k8s-apply.sh 同一份变量名单，用 250 的真库坐标渲染
export NAMESPACE=z-schedule-rehearsal INGRESS_DOMAIN=schedule.test TLS_SECRET_NAME=z-schedule-tls
export OCI_REGISTRY=ghcr.io/yuku123 IMAGE_VERSION=1.0.4
export DB_HOST=127.0.0.1 DB_PORT=33060 DB_NAME=zschedule_p24 DB_USER=root DB_POOL_MAX_ACTIVE=40
VARS='$NAMESPACE $INGRESS_DOMAIN $TLS_SECRET_NAME $OCI_REGISTRY $IMAGE_VERSION $DB_HOST $DB_PORT $DB_NAME $DB_USER $DB_POOL_MAX_ACTIVE'
R="$WORK/rendered"; mkdir -p "$R"
for f in "$DEPLOY"/k8s/*.yaml; do envsubst "$VARS" < "$f" > "$R/$(basename "$f")"; done
LEFT=$(grep -ho '\${[A-Za-z_][A-Za-z_]*}' "$R"/*.yaml 2>/dev/null | sort -u | tr '\n' ' ')
[ -z "$LEFT" ] && ok "A3 六份清单渲染后不留未替换占位符" || bad "A3 渲染后仍有占位符：$LEFT"

# A4 尺有牙：名单里故意抽掉 DB_NAME，检测必须叫
envsubst '$NAMESPACE $OCI_REGISTRY $IMAGE_VERSION $DB_HOST $DB_PORT $DB_USER $DB_POOL_MAX_ACTIVE' \
  < "$DEPLOY/k8s/01-deployment-backend.yaml" > "$WORK/short.yaml"
if grep -q '\${DB_NAME}' "$WORK/short.yaml"; then
  ok "A4 少给一个变量时渲染确实留下 \${DB_NAME}（A3 那条不是空跑）"
else
  FATAL "A4 抽掉 DB_NAME 后不留占位符 ⇒ A3 的判据没牙"
fi

# A5 每份都解析得出文档
DOCS=$(python3 - "$R" <<'PY'
import sys, glob, yaml
n = 0
for p in sorted(glob.glob(sys.argv[1] + "/*.yaml")):
    docs = [d for d in yaml.safe_load_all(open(p, encoding="utf-8")) if d]
    n += len(docs)
    if not docs:
        print("EMPTY " + p); sys.exit(0)
print(n)
PY
)
case "$DOCS" in (''|*[!0-9]*) bad "A5 yaml 解析异常：$DOCS";; *) ok "A5 六份清单共解析出 $DOCS 个对象，无一空文档";; esac

# A6 镜像名：清单要的 == build-images.sh 打的（逐字比名字，不比记忆）
python3 - "$DEPLOY/bin/build-images.sh" "$DEPLOY/bin/k8s-apply.sh" "$DEPLOY/Makefile" \
          "$DEPLOY/env/.env.example" \
          "$DEPLOY/k8s/01-deployment-backend.yaml" "$DEPLOY/k8s/02-deployment-frontend.yaml" <<'PY' > "$WORK/img.txt" 2>&1 || true
import sys, re, yaml
BI, KA, MK, EX = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]


def grab(path, pat):
    m = re.search(pat, open(path, encoding="utf-8").read())
    return m.group(1) if m else "?"


b = (grab(BI, r'OCI_REGISTRY="\$\{OCI_REGISTRY:-([^}]*)\}"'), grab(BI, r'IMAGE_VERSION="\$\{IMAGE_VERSION:-([^}]*)\}"'))
k = (grab(KA, r'OCI_REGISTRY:-([^}]*)\}"'), grab(KA, r'IMAGE_VERSION:-([^}]*)\}"'))
m = (grab(MK, r'OCI_REGISTRY \?= (\S+)'), grab(MK, r'IMAGE_VERSION \?= (\S+)'))
# .env.example 是模板里的手抄值：抬版本时最容易忘的就是它，所以一并进对账
e = (grab(EX, r'(?m)^OCI_REGISTRY=(\S*)$'), grab(EX, r'(?m)^IMAGE_VERSION=(\S*)$'))
print("DEFAULTS build=%s k8s-apply=%s make=%s env-example=%s" % (b, k, m, e))
print("DEFAULTS_SAME=%s" % ("yes" if b == k == m == e else "no"))
# 构建脚本会打出的两个 tag（从它自己的 -t 串里取名字，不靠记忆）
built = {"%s/%s:%s" % (b[0], n, b[1]) for n in re.findall(r'OCI_REGISTRY\}*/([a-z0-9-]+):', open(BI, encoding="utf-8").read())}
want = set()
got = set()
for p in (sys.argv[5], sys.argv[6]):
    for d in yaml.safe_load_all(open(p, encoding="utf-8")):
        if not d or d.get("kind") != "Deployment":
            continue
        for c in d["spec"]["template"]["spec"]["containers"]:
            img = c["image"].replace("${OCI_REGISTRY}", k[0]).replace("${IMAGE_VERSION}", k[1])
            got.add(img)
            name = img.rsplit("/", 1)[-1].split(":")[0]
            want.add("%s/%s:%s" % (b[0], name, b[1]))
print("BUILT=%s" % " ".join(sorted(built)))
print("GOT=%s" % " ".join(sorted(got)))
print("MATCH=%s" % ("yes" if got and got == want and built and built == want else "no"))
PY
sed -i.bak 's/^/    /' "$WORK/img.txt" 2>/dev/null || true
cat "$WORK/img.txt"
GOTIMG=$(sed -n 's/^ *GOT=//p' "$WORK/img.txt" | tr '\n' ' ')
grep -q 'MATCH=yes' "$WORK/img.txt" \
  && ok "A6 清单 image == 构建脚本默认参数会打的 tag：$GOTIMG" \
  || bad "A6 清单要的镜像不是构建脚本打出来的名字（清单要 $GOTIMG）"
grep -q 'DEFAULTS_SAME=yes' "$WORK/img.txt" && ok "A6b 四处默认 registry/version（构建/k8s-apply/Makefile/.env.example）互相一致" \
  || bad "A6b 默认值漂了：$(sed -n 's/^ *DEFAULTS //p' "$WORK/img.txt")"

# A6c exec jar 在哪一处都别写死版本号。为什么这一条值得占一格：字面量在抬版后的形状，250 上
#     逐格量过（五种形状的原文读数与复跑命令见 README §14）——
#       缺文件 → rc=1，COPY failed: … file does not exist（会响，算客气）
#       同名位置放了一份别的 jar → **rc=0**，tag 是新版本而 /app/app.jar 是那份的字节（实测：36 字节的
#       假 jar 进得去，镜像里读回的 md5 就是它的 md5）
#       通配 0 命中 / ≥2 命中 → rc=1（no source files / destination must be a directory）
#     也就是说只有通配那种"要么唯一、要么失败"，而字面量恰好喂"静默错字节"那一格——它不会红，
#     只会交出去一个错镜像。
#     bin/build-images.sh 从一开始就是 `ls target/*-exec.jar`，它是这份档里唯一一直对的那处。
A6C_FILES="$DEPLOY/Dockerfile.backend $DEPLOY/docker-compose.yml $DEPLOY/docker-compose.split.yml \
$DEPLOY/docker-compose.cluster.yml $DEPLOY/env/.env.example"
a6c_scan() {   # $@=文件；打印 "<缺通配的文件名…>|<写死版本号的命中文件数>"
  local miss="" lit f
  for f in "$@"; do
    grep -qE 'target/\*-exec\.jar' "$f" 2>/dev/null || miss="$miss $(basename "$f")"
  done
  lit=$(grep -rIlE 'z-schedule-admin-[0-9]+\.[0-9]+\.[0-9]+-exec\.jar' "$@" 2>/dev/null | wc -l | tr -d ' ')
  echo "${miss# }|$lit"
}
A6C=$(a6c_scan $A6C_FILES)
mkdir -p "$WORK/prey_a6c"
for f in $A6C_FILES; do cp "$f" "$WORK/prey_a6c/"; done
# 猎物：把其中一处退回"写死 z-schedule-admin-<ver>-exec.jar"的旧形状
sed -i.bak 's|z-schedule-admin/target/\*-exec\.jar|z-schedule-admin/target/z-schedule-admin-1.0.4-exec.jar|' \
    "$WORK/prey_a6c/docker-compose.cluster.yml" && rm -f "$WORK/prey_a6c/*.bak"
A6CP=$(a6c_scan "$WORK/prey_a6c"/*)
if [ "$A6C" = "|0" ] && [ "$A6CP" != "|0" ]; then
  ok "A6c 五处 exec jar 面全走通配、零写死版本号；同一把尺在退回写死形状的那份猎物上点名 [${A6CP%|*}] 并数到 ${A6CP##*|} 处字面量 ⇒ 这一臂认得那种病"
else
  bad "A6c 尺或面不对：真值[$A6C] 猎物[$A6CP]（期望 真值=|0 且猎物非 |0）⇒ 要么还有写死的版本号，要么这把尺看不见它"
fi

# A7 两个池的坐标都必须在容器 env 里（ConfigMap data ∪ env 名）
python3 - "$R/01-deployment-backend.yaml" <<'PY' > "$WORK/env.txt" || true
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1], encoding="utf-8")) if d]
cm = {d["metadata"]["name"]: d.get("data", {}) for d in docs if d["kind"] == "ConfigMap"}
dep = next(d for d in docs if d["kind"] == "Deployment")
c = dep["spec"]["template"]["spec"]["containers"][0]
names = set()
for e in c.get("env", []):
    names.add(e["name"])
for f in c.get("envFrom", []):
    ref = f.get("configMapRef", {}).get("name")
    if ref:
        names |= set(cm.get(ref, {}))
    s = f.get("secretRef", {}).get("name")
    if s:
        print("ENVFROM_SECRET %s" % s)
ENG = ["Z_BASE_DB_SCHEDULE_HOST", "Z_BASE_DB_SCHEDULE_PORT", "Z_BASE_DB_SCHEDULE_DATABASE",
       "Z_BASE_DB_SCHEDULE_USERNAME", "Z_BASE_DB_SCHEDULE_PASSWORD"]
SPR = ["SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"]
print("ENVNAMES=%s" % " ".join(sorted(names)))
for tag, keys in (("ENGINE", ENG), ("SPRING", SPR)):
    miss = [k for k in keys if k not in names]
    print("%s_MISSING=%s" % (tag, ",".join(miss) or "-"))
pw = [e for e in c.get("env", []) if "PASSWORD" in e["name"]]
print("PW_FROM_SECRET=%s" % ("yes" if len(pw) == 2 and all(
    e.get("valueFrom", {}).get("secretKeyRef", {}).get("name") for e in pw) else "no"))
prof = [e for e in c.get("env", []) if e["name"] == "SPRING_PROFILES_ACTIVE"]
from_cm = [v for k, v in (cm.get(next((f.get('configMapRef', {}).get('name') for f in c.get('envFrom', []) if f.get('configMapRef')), ""), {}) or {}).items() if k == "SPRING_PROFILES_ACTIVE"]
print("PROFILE=%s" % (prof[0]["value"] if prof else (from_cm[0] if from_cm else "-")))
PY
sed -i 's/^/    /' "$WORK/env.txt" 2>/dev/null || true
cat "$WORK/env.txt"
grep -q 'ENGINE_MISSING=-' "$WORK/env.txt" && grep -q 'SPRING_MISSING=-' "$WORK/env.txt" \
  && ok "A7 引擎池五把键与 Spring 池三把键都在容器 env 上" || bad "A7 有池喂不到：$(grep MISSING "$WORK/env.txt" | grep -v '=-' | tr -s ' ')"
grep -q 'PW_FROM_SECRET=yes' "$WORK/env.txt" && ok "A7b 两个密码都走 secretKeyRef，明文不入 Git" \
                                             || bad "A7b 密码来源不对（应两个都指 Secret）"

# A8 profile 指的文件得真的存在
PROF=$(sed -n 's/^ *PROFILE=\([^ ]*\).*/\1/p' "$WORK/env.txt" | head -1)
RES="$(cd "$DEPLOY/.." && pwd)/z-schedule-admin/src/main/resources"
if [ "$PROF" = "default" ] || [ "$PROF" = "-" ]; then
  ok "A8 SPRING_PROFILES_ACTIVE=$PROF（改前那份设的是 k8s，而仓库里没有 application-k8s.yml）"
elif [ -f "$RES/application-$PROF.yml" ]; then
  ok "A8 profile=$PROF 且 $RES/application-$PROF.yml 真在"
else
  bad "A8 profile=$PROF 是幻影：$RES 下没有 application-$PROF.yml"
fi
# 阳性对照：把幻影那条按原样再判一次，尺必须报红
if [ -f "$RES/application-k8s.yml" ]; then
  echo "    （注：本机现在真有 application-k8s.yml，A8 的幻影判据这轮对它不适用）"
else
  ok "A8b 尺有牙：同一判据对 k8s 这个值就是'没有对应文件'（改前清单设的正是它）"
fi

# A9 compose 三种模式同样要喂到引擎池
for f in docker-compose.yml docker-compose.split.yml docker-compose.cluster.yml; do
  M=$(python3 - "$DEPLOY/$f" <<'PY'
import sys, yaml
d = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
env = {}
for s in d["services"].values():
    if "admin" in s.get("image", "") or "backend" in str(s.get("container_name", "")) \
       or "backend" in str(s.get("image", "")):
        e = s.get("environment") or {}
        env = e if isinstance(e, dict) else dict(x.split("=", 1) for x in e)
        break
need = ["Z_BASE_DB_SCHEDULE_HOST", "Z_BASE_DB_SCHEDULE_DATABASE", "Z_BASE_DB_SCHEDULE_PASSWORD",
        "Z_BASE_DB_SCHEDULE_USERNAME", "SPRING_DATASOURCE_URL"]
print(",".join(k for k in need if k not in env) or "-")
PY
)
  [ "$M" = "-" ] && ok "A9 $f 两个池都喂到了" || bad "A9 $f 缺键：$M"
done

# A10 nginx 的 /api 反代不能再自造前缀
NG=$(grep -A2 "location /api/" "$DEPLOY/nginx.conf.template" | grep proxy_pass | head -1)
case "$NG" in
  *"/meta/api/"*) bad "A10 nginx 仍把 /api/ 转成 /meta/api/（后端没有 /api/** 控制器）";;
  *"/meta/"*)      ok "A10 nginx 剥掉 /api 落到后端 context-path 根：$(echo "$NG" | sed 's/^ *//')";;
  *)               bad "A10 nginx /api 的 proxy_pass 看不懂：$NG";;
esac
CTRL=$(grep -rhoE '@RequestMapping\("/jobinfo"' "$DEPLOY/../z-schedule-spring-boot-starter/src/main/java" 2>/dev/null | head -1)
[ -n "$CTRL" ] && ok "A10b 对照：控制器确实挂在根上（$CTRL），没有任何 /api 前缀可转" \
               || echo "    （A10b 对照没取到控制器串，A10 的结论只看 nginx 自身）"

# A11（#39）每一条 ./run.sh 调用都必须显式点名构件。为什么要钉这一格：run.sh 的默认值是
#     `z-schedule-admin-1.0.0-exec.jar` 这样一个**名字**，而这名字在 250 上先后对应过 4 份不同字节
#     （README 那条"同一个名字对应过 4 份字节"的坑）。裸调 ⇒ 脚本今天绿、明天起来的可能仍是那个
#     名字下的另一代字节，而结论全记在"这一版修好了"名下——和 #38 那格"字面量喂静默错字节"同一类病，
#     只是这里没有 docker 会替我响。
# ⚠ 两处我自己踩过的口径：
#   ① 扫描必须折叠续行：p20/p22 的 `JAR=` 在上一行、行尾带 `\`。第一版用单行 grep 判"谁裸调"，
#      把这两条读成裸调用，报出"6 个裸调用方"，真值 4 个。
#   ② 不能拿 `glob p*.sh` 当分母：本尺自己的源码里就写着 './run.sh' 这个串（判定条件 + 那条 ok 消息），
#      自指被数成调用、分母从 11 涨到 13。⇒ 分母 = 本目录除尺以外的全部 *.sh + README 的代码块，
#      排除项只有两个且各有理由：p24.sh 是尺（它写着那个串）、run.sh 是被调方（不是调用方）。
#   ③ 上一版的"覆盖守卫"glob 也是 `p*.sh` ⇒ 目录里两个非 p* 的调用方**根本不在它的视野里**：
#      run_p20_and_restore.sh:50 的真调用、bootstrap_mysql.sh:97 广告给人抄的那句 echo。
#      那句"清单外命中 0 处"是**在它看不见的范围里**数为 0 的 ⇒ 守卫换成"分母自己就是全目录"，
#      再补一条**阳性对照**：下面 A11_REQ 这些文件必须各自至少贡献一条命中，少一个即分母塌了
#      （改名、漏拷、glob 坏掉都会红；这一条本身就是被 ③ 逼出来的——只有"该看见的确实看见了"
#      才顶得住"glob 悄悄少了一批文件"）。
# 为什么 README 只数 ```bash 围栏内的行：`./run.sh` 也出现在讲坑的正文里（"以前裸调"），那是被讨论
#   的对象不是可敲的命令；围栏正好把"教人敲的"与"讲道理的"分开，不靠猜。quick-start 那条的 `JAR=`
#   在上一行、行尾带 `\`（README:30→31）⇒ 文档臂必须走同一个续行折叠，否则它会被读成裸调，
#   第 ① 条那一枪在文档里会再响一次。
A11_FILES="$(ls *.sh | grep -vE '^(run|p24)\.sh$' | tr '\n' ' ') README.md"
A11_REQ="p10.sh p11.sh p13.sh p14.sh p15.sh p16.sh p17.sh p18.sh p20.sh p22.sh p23.sh run_p20_and_restore.sh bootstrap_mysql.sh README.md"
runsh_scan() {  # $1=目录 $2=空格分隔的文件名 ⇒ "<调用条数>|<裸调用条数>|<点名>|<贡献命中的文件>"
  python3 - "$1" "$2" <<'PY'
import re, os, sys
d, names = sys.argv[1], sys.argv[2].split()
tot, bare, hits = 0, [], []
def logical(path):
    """产出 (原始行号, 折叠续行之后的逻辑行)。.md 只数 ``` 围栏内的行。
    ⚠ 行号必须是**原始**的：折叠之后再 enumerate 会整片前移（bootstrap_mysql.sh 那句 echo
      原本在 97 行，折叠版报成 84），报出来的点名指不到文件里那一行——尺给的锚点得能回读。"""
    raw = open(path, encoding='utf-8').read().split('\n')
    if path.endswith('.md'):
        keep, fence = [], False
        for i, ln in enumerate(raw):
            if ln.lstrip().startswith('```'):
                fence = not fence; keep.append('')
            else:
                keep.append(ln if fence else '')
        raw = keep
    out, buf, start = [], '', None
    for i, ln in enumerate(raw, 1):
        if start is None:
            start = i
        if ln.endswith('\\'):
            buf += ln[:-1] + ' '
            continue
        out.append((start, buf + ln)); buf, start = '', None
    if start is not None:
        out.append((start, buf))          # 文件以续行收尾（畸形，但别漏数）
    return out
for nm in names:
    f = os.path.join(d, nm)
    if not os.path.isfile(f):
        bare.append('%s:文件不在' % nm); continue
    got = 0
    for n, ln in logical(f):
        s = ln.strip()
        if s.startswith('#') or './run.sh' not in s:
            continue
        tot += 1; got += 1
        if 'JAR=' not in s and '${JAR' not in s:
            bare.append('%s:%d' % (nm, n))
    if got:
        hits.append('%s(%d)' % (nm, got))
print('%d|%d|%s|%s' % (tot, len(bare), ' '.join(bare) or '-', ' '.join(hits) or '-'))
PY
}
A11=$(runsh_scan "$(pwd)" "$A11_FILES")
A11_TOT=${A11%%|*}; A11_REST=${A11#*|}; A11_BARE=${A11_REST%%|*}; A11_REST2=${A11_REST#*|}
A11_NAMES=${A11_REST2%%|*}; A11_HITS=${A11_REST2#*|}
A11_MISS=""
for req in $A11_REQ; do
  printf '%s' " $A11_HITS " | grep -qF " $req(" || A11_MISS="$A11_MISS $req"
done
# 猎物：拿一份**当下合规**的显式调用（p20 那条带 JAR="$JAR"），只把 JAR 那一截摘掉 ⇒ 必须被点名
mkdir -p "$WORK/prey_a11"
python3 - "$(pwd)/p20.sh" "$WORK/prey_a11/p20.sh" <<'PY'
import sys
t = open(sys.argv[1], encoding='utf-8').read()
p = t.replace('JAR="$JAR" ', '', 1)
assert p != t, '猎物注入没改动任何字节（说明 p20 里那串 JAR= 不是我以为的样子）'
open(sys.argv[2], 'w', encoding='utf-8').write(p)
PY
A11P=$(runsh_scan "$WORK/prey_a11" "p20.sh")
A11_NF=$(printf '%s' "$A11_FILES" | wc -w | tr -d ' ')
if [ -n "$A11_MISS" ]; then
  bad "A11 分母塌了：这些文件里本该有 ./run.sh 的调用，这一遍一条都没数到 [$A11_MISS]（分母 $A11_NF 个文件，命中集 [$A11_HITS]）⇒ 文件或尺变了，别把'没看见'读成'没有'"
elif [ "$A11_TOT" != "0" ] && [ "$A11_BARE" = "0" ] \
   && [ "${A11P%%|*}" = "1" ] && [ "$(printf '%s' "$A11P" | awk -F'|' '{print $2}')" != "0" ]; then
  ok "A11 分母 $A11_NF 个文件数到 $A11_TOT 条 ./run.sh 调用，全部显式点名构件、裸调用 0 条（命中集 $A11_HITS）；同一把尺在摘掉 p20 那截 JAR= 的猎物上把裸调用数到 $(printf '%s' "$A11P" | awk -F'|' '{print $2}') 条并点名 $(printf '%s' "$A11P" | awk -F'|' '{print $3}') ⇒ 不是恒零；阳性对照 $A11_REQ 全部在场"
else
  bad "A11 尺或面不对：真值 调用=$A11_TOT 裸=$A11_BARE [$A11_NAMES]，猎物[$A11P]（期望 真值裸=0 且调用>0 且猎物被点名）⇒ 要么还有裸调用，要么这把尺看不见它"
fi

# A12（#39 下半）run.sh 的默认构件规则本身的行为：盘面恰好一份 *-exec.jar 才用它，
#   0 份 / ≥2 份都得当场 FATAL。上一臂（A11）管的是"仓里没人裸调"，这一臂管的是"裸调真发生了
#   会怎样"——两半合起来才是这一格：A11 绿 + A12 缺 ⇒ 下一个人裸调仍会静默起来一份旧字节。
# 量法：每个臂造一个临时盘相（jar 文件名是真的、内容是假的），把 PATH 上的 `java` 换成替身，
#   量的是"递给 java 的 -jar 参数是哪个文件"，不真起 JVM（真起来要库要端口，那是臂 B 在 250 的事）。
# 牙（teeth_*）：把 0 份那一臂的处置原地换回**改前形状**（盘面没 jar 就用写死的名字）⇒ 必须
#   看到"不失败 + 把那个不存在的名字递给 java"。少了这一臂，上面那四句 FATAL 有可能只是我抄的字符串。
# ⚠ 顺带被这一臂撞出来的第二条（真缺陷，已修）：run.sh 原来以 `"${EXTRA_ARGS[@]}"` 收尾，而本机
#   bash 3.2 在 `set -u` 下把**空数组**判为未绑定变量（`a=(); echo "${a[@]}"` → `a[@]: unbound
#   variable`，rc=1），于是不设 ACCESS_TOKEN / APP_ARGS 时脚本在 exec java **之前**就死了。
#   250 是 bash 5 才一直躲过这一枪 ⇒ 改成 ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}，非空时逐字同形。
A12_DIR="$WORK/a12"
mkdir -p "$A12_DIR/bin"
printf '#!/bin/sh\necho "SHIM-JAVA-ARGV: $*"\n' > "$A12_DIR/bin/java"
chmod +x "$A12_DIR/bin/java"
a12_case() {  # $1=臂名 $2=要造的 jar(空格分隔,可空) $3=VAR=val 前缀 $4=用哪份 run.sh ⇒ "rc|首行"
  local d="$A12_DIR/$1" jar rc line
  mkdir -p "$d"; cp "${4:-$(pwd)/run.sh}" "$d/run.sh"; chmod +x "$d/run.sh"
  # A12 不碰真库：mysql.env 只为过掉 run.sh 里那两个 ${MYSQL_DATABASE:?} / ${MYSQL_USER:?}，
  # 值随脚本一起生在 $WORK 里、跑完即弃，所以这里出现的不是任何环境的口令。
  printf 'MYSQL_DATABASE=a12db\nMYSQL_USER=a12user\nMYSQL_PASSWORD=a12pw\n' > "$d/mysql.env"
  ( cd "$d" && for jar in ${2:-}; do printf 'not-a-jar' > "$jar"; done )
  # ${3:-} 是必需的：本脚本 set -u，而"零个前缀 / 零个 jar"正是第一个臂要量的形状
  # （第一遍就死在这里的 `$3: unbound variable` 上，五臂里三臂根本没跑成，读出的 [1|] 是尺的错不是面的）。
  ( cd "$d" && PATH="$A12_DIR/bin:$PATH" PORT=18999 env ${3:-} ./run.sh > out.log 2>&1 ); rc=$?
  line=$(grep -m1 -E 'FATAL|SHIM-JAVA-ARGV' "$d/out.log" || true)
  printf '%s|%s' "$rc" "$line"
}
# 改前形状的猎物：只动 0 份那一支的处置，别的字一个字不变
python3 - "$(pwd)/run.sh" "$A12_DIR/pre_fix_run.sh" <<'PY'
import sys
t = open(sys.argv[1], encoding='utf-8').read()
old = '    0) echo "FATAL: $PWD 下没有 *-exec.jar，也没有 JAR= 点名 ⇒ 先把构件拷进来（不给默认名：那名字对应过 4 份字节）" >&2; exit 1 ;;'
new = '    0) JAR="z-schedule-admin-1.0.0-exec.jar" ;;'
assert old in t, '猎物注入找不到那一行（run.sh 的形状和我以为的不一样 ⇒ 先重读再改尺）'
open(sys.argv[2], 'w', encoding='utf-8').write(t.replace(old, new, 1))
PY
A12_ZERO=$(a12_case zero "")
A12_ONE=$(a12_case one "z-schedule-admin-svc-abc1234-exec.jar")
A12_TWO=$(a12_case two "a-exec.jar b-exec.jar")
A12_NAMED=$(a12_case named "a-exec.jar b-exec.jar" "JAR=b-exec.jar")
A12_TEETH=$(a12_case teeth_zero "" "" "$A12_DIR/pre_fix_run.sh")
if [ "${A12_ZERO%%|*}" = "1" ] && printf '%s' "$A12_ZERO" | grep -qF '下没有 *-exec.jar' \
   && [ "${A12_ONE%%|*}" = "0" ] && printf '%s' "$A12_ONE" | grep -qF 'SHIM-JAVA-ARGV: -Xms256m -Xmx768m -jar ./z-schedule-admin-svc-abc1234-exec.jar' \
   && [ "${A12_TWO%%|*}" = "1" ] && printf '%s' "$A12_TWO" | grep -qF '二义不猜' \
   && [ "${A12_NAMED%%|*}" = "0" ] && printf '%s' "$A12_NAMED" | grep -qF ' -jar b-exec.jar' \
   && [ "${A12_TEETH%%|*}" = "0" ] && printf '%s' "$A12_TEETH" | grep -qF ' -jar z-schedule-admin-1.0.0-exec.jar'; then
  ok "A12 run.sh 的默认构件规则五臂齐：0 份 ⇒ rc=1 且报'没有 *-exec.jar'；1 份 ⇒ rc=0 且 java 拿到 ./z-schedule-admin-svc-abc1234-exec.jar；2 份 ⇒ rc=1 且报'二义不猜'；2 份+JAR= 点名 ⇒ rc=0 且 java 拿到被点名的 b-exec.jar（显式覆盖赢过二义）；猎物（0 份那支换回改前处置）⇒ rc=0 且 java 拿到从没存在过的 z-schedule-admin-1.0.0-exec.jar ⇒ 这四条判据分得开改前改后，不是恒真"
else
  bad "A12 形状不对：zero[$A12_ZERO] one[$A12_ONE] two[$A12_TWO] named[$A12_NAMED] teeth[$A12_TEETH]"
fi

# A13（#37 的静态半边）deploy/README.md 教人敲的东西，逐条对仓内实物。
#   三件事各自一条判据：① 围栏里出现的每个 `make X`（含 `make a/b/c` 这种斜杠清单）都得是 Makefile
#   真有的目标；② 那棵目录树里声明的每个路径，按缩进推出来的位置得真在那儿；③ compose 用 ${V:?}
#   硬要求的键，必须在 env/.env.example 里以**未注释**的形式存在——文档路径就是
#   `cp env/.env.example env/.env && make dev`，示例文件缺一个硬要求键，第一条命令就死。
# ⚠ ③ 必须是"剥掉 YAML 注释行之后再扫"：手算这一格时不剥注释，`${V:?…}` 与 `${BACKEND_SERVICE}`
#   这两个只存在于注释里的写法被读成两个硬要求键，差点据此报"示例文件缺两项"要去返工
#   （实情：docker-compose.split.yml:63 是注释，Makefile 里那句 `${V:?}` 也在注释里）。
#   所以这一臂除了猎物，还带一条**反向对照**：真文件扫出来的硬要求集合里不许出现 V / BACKEND_SERVICE，
#   哪天有人把注释扫回来了，这条会先红，不会先把假缺陷送进 README。
cat > "$WORK/a13_docface.py" <<'PY'
import os, re, sys
dep, readme, makefile, envex = sys.argv[1:5]
mk = set(re.findall(r'^([A-Za-z0-9_.-]+):', open(makefile, encoding='utf-8').read(), re.M))
toks, bad_make = [], []
tree = []
fence = False
for i, ln in enumerate(open(readme, encoding='utf-8').read().split('\n'), 1):
    if ln.lstrip().startswith('```'):
        fence = not fence; continue
    if not fence:
        continue
    for m in re.finditer(r'make\s+([A-Za-z0-9_./-]+)', ln):
        for t in m.group(1).split('/'):
            if t:
                toks.append(t)
                if t not in mk:
                    bad_make.append('%d:make %s' % (i, t))
    if '──' in ln:
        idx = ln.find('──')
        depth = (idx - 1) // 4 if idx >= 4 else 0
        name = re.split(r'──\s*', ln.strip(), maxsplit=1)[1].split()[0]
        tree.append((i, depth, name))
cur = {}
bad_tree = []
for i, depth, name in tree:
    base = cur.get(depth - 1, '')
    path = os.path.join(dep, base, name.rstrip('/')) if base else os.path.join(dep, name.rstrip('/'))
    ok = os.path.isdir(path) if name.endswith('/') else os.path.isfile(path)
    if name.endswith('/'):
        cur[depth] = os.path.join(base, name.rstrip('/')) if base else name.rstrip('/')
    if not ok:
        bad_tree.append('%d:%s' % (i, os.path.relpath(path, dep)))
yreq = set()
for f in sorted(os.listdir(dep)):
    if not f.startswith('docker-compose') or not f.endswith(('.yml', '.yaml')):
        continue
    for ln in open(os.path.join(dep, f), encoding='utf-8'):
        if ln.lstrip().startswith('#'):
            continue
        yreq |= set(re.findall(r'\$\{([A-Z0-9_]+):\?', ln))
ex = open(envex, encoding='utf-8').read()
provided = set(re.findall(r'^([A-Z][A-Z0-9_]*)=', ex, re.M))
bad_env = sorted(yreq - provided)
print('MAKE|%d|%s' % (len(toks), ' '.join(bad_make) or '-'))
print('TREE|%d|%s' % (len(tree), ' '.join(bad_tree) or '-'))
print('ENV|%d|%s|%s' % (len(yreq), ' '.join(sorted(yreq)), ' '.join(bad_env) or '-'))
PY
a13_run() { python3 "$WORK/a13_docface.py" "$DEPLOY" "$1" "$DEPLOY/Makefile" "${2:-$DEPLOY/env/.env.example}"; }
A13=$(a13_run "$DEPLOY/README.md")
A13_MAKE=$(printf '%s\n' "$A13" | grep '^MAKE' | head -1)
A13_TREE=$(printf '%s\n' "$A13" | grep '^TREE' | head -1)
A13_ENV=$(printf '%s\n' "$A13" | grep '^ENV' | head -1)
# 猎物：一份改过的 README（多一个假 make 目标 + 树里多一个不存在的脚本）+ 一份抽掉 DB_USER 的示例
mkdir -p "$WORK/prey_a13"
python3 - "$DEPLOY/README.md" "$WORK/prey_a13/README.md" "$DEPLOY/env/.env.example" "$WORK/prey_a13/env.example" <<'PY'
import re, sys
t = open(sys.argv[1], encoding='utf-8').read()
# 塞进第一个 bash 围栏里，保证走的是和真判据完全相同的那条路
i = t.index('```bash')
j = t.index('```', i + 7)
t2 = t[:j] + 'make frobnicate\n' + t[j:]
k = t2.index('├── bin/')
t2 = t2[:k] + '│   ├── start-mode7.sh\n' + t2[k:]
assert t2 != t and 'frobnicate' in t2 and 'start-mode7.sh' in t2, 'A13 猎物没注入成功'
open(sys.argv[2], 'w', encoding='utf-8').write(t2)
e = open(sys.argv[3], encoding='utf-8').read()
e2 = re.sub(r'(?m)^DB_USER=.*\n', '', e, count=1)
assert e2 != e, 'A13 的 env 猎物没抽掉 DB_USER（示例文件的写法和我以为的不一样）'
open(sys.argv[4], 'w', encoding='utf-8').write(e2)
PY
A13P=$(a13_run "$WORK/prey_a13/README.md" "$WORK/prey_a13/env.example")
A13_MK_N=$(printf '%s' "$A13_MAKE" | cut -d'|' -f2)
A13_TR_N=$(printf '%s' "$A13_TREE" | cut -d'|' -f2)
A13_ENV_N=$(printf '%s' "$A13_ENV" | cut -d'|' -f2)
A13_YREQ=" $(printf '%s' "$A13_ENV" | cut -d'|' -f3) "
# 反向对照用 case 而不是 grep：`grep -qwV V` 这种"选项字母恰好等于被搜单词"的写法在 BSD grep 上
# 把 -V 当成选项（打 usage、退出码 1），判据的形状就取决于 ! 落在哪，不再是"注释里的键不许算硬要求"。
A13_BLIND=ok
for k in V BACKEND_SERVICE; do
  case "$A13_YREQ" in *" $k "*) A13_BLIND="把注释里的键 $k 当成了硬要求";; esac
done
if [ "${A13_MK_N:-0}" -gt 0 ] && [ "${A13_TR_N:-0}" -gt 0 ] && [ "${A13_ENV_N:-0}" -gt 0 ] \
   && printf '%s' "$A13_MAKE" | grep -q '|-$' && printf '%s' "$A13_TREE" | grep -q '|-$' \
   && printf '%s' "$A13_ENV" | grep -q '|-$' \
   && [ "$A13_BLIND" = ok ] \
   && [ "$(printf '%s' "$A13P" | grep -c 'frobnicate')" = "1" ] \
   && [ "$(printf '%s' "$A13P" | grep -c 'start-mode7.sh')" = "1" ] \
   && [ "$(printf '%s' "$A13P" | grep -c 'DB_USER')" = "1" ]; then
  ok "A13 文档广告面三查全绿：README 围栏里 $A13_MK_N 个 make 目标全在 Makefile（不存在的目标 0 个）、树里 $A13_TR_N 条路径按缩进逐个存在、compose 硬要求 :? 的 $A13_ENV_N 个键[$A13_YREQ] 在 .env.example 里都未注释地给了；反向对照：只写在注释里的 \${V:?}/\${BACKEND_SERVICE} 没被当成硬要求；同一份尺对猎物点名 3 处 [make frobnicate / bin/start-mode7.sh / DB_USER 缺]"
else
  bad "A13 形状不对：[$A13_MAKE] [$A13_TREE] [$A13_ENV] 猎物[$A13P]（期望 三个分母都 >0 且三处都 '-' 且猎物三处各命中 1）"
fi

# ---------- A14 / A15：根 README 的广告面，与"文档教的命令写法本身跑不跑得起来" ----------
# A14（#40 那一格顺手撞出来的）：根 README 是别人 clone 后看到的第一屏，它当时写的是
#   `# z-wf`（模板遗留，而这个仓的根 pom artifactId 是 z-schedule）、并且只索引了 _doc/003_script/
#   一格（001_arch、004_sql 两个目录盘上就有，前页不提）。这类"抄来的数与名"没有尺就永远漂：
#   标题、构件模块名、_doc 一级目录、每个相对链接、版本字面量、建表张数与表名，六项逐个对实物。
#   版本那一支与 #38 同源（抄死版本号在抬版后必坏），但它量的是文档，改不动 pom 的单一来源。
#   ⚠ revision 形状若不再是 1.0.N，VER 那一支会自己报"尺不适用"而不是静默放行。
# A15：文档注释里教人 `mvn spring-boot:run \ --server.port=18086` —— 那是把应用参数递给
#   **Maven**，Maven 当场拒（`Unable to parse command line options: Unrecognized option`，实测 rc=1，
#   应用一次都没起来）。判据只认"长选项名里含点"这一种形状（Maven 自己的长选项没有点：
#   --also-make / --no-transfer-progress），并先摘掉 -Dspring-boot.run.arguments=/jvmArguments=
#   的取值段 ⇒ 合法写法不误伤。必须按**逻辑行**量：那份原文的 `--server.port` 在反斜杠续行的
#   下一行，逐行扫会结构性漏掉（与 A11 同一课）。
#   第二支 PHANTOM 量 javadoc 里的 {@link #方法} —— DevDataSourceConfig 的注释列了一个
#   `removeStarterDataSourceBean()`（说它用 BeanDefinitionRegistryPostProcessor 提前摘 starter 的
#   bean），而那方法**从建文件那一版起就没写进过这个文件**，连带四个 import 是空口机制。
#   javac 不查 @link 的目标 ⇒ 只有把"注释承诺的机制"当断言量，它才不会继续骗人。
REPO="$(dirname "$DEPLOY")"
cat > "$WORK/a14_docface.py" <<'PY'
#!/usr/bin/env python3
"""A14/A15 尺的开发副本：先在这份上把当前树量绿，再嵌进 p24.sh。"""
import os
import re
import sys

root = sys.argv[1]
mode = sys.argv[2] if len(sys.argv) > 2 else 'a14'

if mode == 'a14':
    readme = sys.argv[3] if len(sys.argv) > 3 else os.path.join(root, 'README.md')
    txt = open(readme, encoding='utf-8').read()
    pom = open(os.path.join(root, 'pom.xml'), encoding='utf-8').read()
    artifact = re.search(r'<artifactId>([^<]+)</artifactId>\s*<version>\$\{revision\}</version>', pom)
    artifact = artifact.group(1) if artifact else re.search(r'<artifactId>([^<]+)</artifactId>', pom).group(1)
    rev = re.search(r'<revision>([^<]+)</revision>', pom).group(1)

    heads = re.findall(r'(?m)^#\s+(.*)$', txt)
    first = heads[0] if heads else ''
    print('HEAD|%s|%s' % (artifact, 'ok' if artifact in first else 'first-heading=[%s]' % first))

    mods = re.findall(r'<module>([^<]+)</module>', pom)
    miss = [m for m in mods if m not in txt]
    print('MODULE|%d|%s' % (len(mods), ' '.join(miss) or '-'))

    docdirs = sorted(d for d in os.listdir(os.path.join(root, '_doc'))
                     if os.path.isdir(os.path.join(root, '_doc', d)))
    # "提到"不等于"索引了"：只认 ](_doc/<d>/) 这一种形状（目录链接带尾斜杠且紧跟右括号），
    # 否则正文里一句 `](_doc/004_sql/z-schedule.sql)` 就能冒充整条目录索引项。
    missd = [d for d in docdirs if ('](_doc/' + d + '/)') not in txt]
    print('DOCIDX|%d|%s' % (len(docdirs), ' '.join(missd) or '-'))

    links = re.findall(r'\[[^\]]*\]\(([^)\s]+)\)', txt)
    rel = [l for l in links if not re.match(r'^(https?:|mailto:|#|/)', l)]
    bad = []
    for l in rel:
        p = l.split('#')[0]
        if not p:
            continue
        if not os.path.exists(os.path.join(root, p)):
            bad.append(l)
    print('LINK|%d/%d|%s' % (len(rel), len(links), ' '.join(bad) or '-'))

    if not re.fullmatch(r'1\.0\.\d+', rev):
        print('VER|N/A|revision 形状不是 1.0.N（%s）⇒ 这把尺不适用，请改尺' % rev)
    else:
        lits = sorted(set(re.findall(r'(?<![\d.])1\.0\.\d+(?![\d.])', txt)))
        stale = [v for v in lits if v != rev]
        print('VER|%d|%s|cur=%s want=%s' % (len(lits), ' '.join(stale) or '-', ','.join(lits), rev))

    sq = os.path.join(root, '_doc/004_sql/z-schedule.sql')
    # 只数"语句起始行"：这份 DDL 第 7 行的注释里就写着"不含任何 DROP"，同理 CREATE TABLE 也会
    # 在注释里出现 ⇒ 不剥注释就数会得到 7（真表 6），这一格本来就是给"抄来的张数"设的闸。
    body = [ln for ln in open(sq, encoding='utf-8') if not ln.lstrip().startswith('--')]
    ncreate = sum(1 for ln in body if re.match(r'\s*CREATE\s+TABLE', ln, re.I))
    names = re.findall(r'(?mi)^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`\"]?([A-Za-z0-9_]+)',
                       '\n'.join(body))
    raw = sum(1 for ln in open(sq, encoding='utf-8') if re.search(r'(?i)CREATE\s+TABLE', ln))
    claim = re.findall(r'(\d+)\s*张表', txt)
    # 表名也钉：README 用共同前缀 + `_job_log` 的简写列名，所以判"去掉 z_schedule 前缀后的那一截在不在正文里"
    missname = [n for n in names if n.replace('z_schedule_', '_') not in txt and n not in txt]
    print('SQL|%d|%d|%s|%s|%s' % (ncreate, raw, ','.join(names), ','.join(claim) or '-',
                                  'ok' if claim and int(claim[0]) == ncreate and not missname
                                  else '张数(%s)≠%d 或表名缺 %s' % (','.join(claim), ncreate, missname)))
    sys.exit(0)

# A15：文档/注释里"给 Maven 传应用参数"的写法 + javadoc 里指向不存在成员的 {@link #x}
mvn_bad = []
mvn_n = 0
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in ('target', 'node_modules', '.git', 'dist', 'logs')]
    for fn in filenames:
        if fn == 'p24.sh':          # 尺自己的源码里有坏形状的字面量，它不是"给人敲的命令"
            continue
        if not fn.endswith(('.md', '.yml', '.sh', '.java')):
            continue
        p = os.path.join(dirpath, fn)
        lines = open(p, encoding='utf-8').read().split('\n')
        if fn.endswith('.md'):
            # 只量"能被复制粘贴执行"的那部分：markdown 里只有 ``` 围栏内是命令。
            # 围栏外（正文/表格）允许描述坏形状 —— 那正是本节要写下来的东西，
            # 拿它当广告判红，等于逼文档删掉证据。
            out, infence = [], False
            for ln in lines:
                if ln.lstrip().startswith('```'):
                    infence = not infence
                    out.append('')
                    continue
                out.append(ln if infence else '')
            lines = out
        # 必须按逻辑行量：反斜杠续行时 `--server.port=` 常在下一行，逐行扫会结构性漏掉
        # （application.yml 被改掉的那份原文就是 `\` + 换行 + `#     --spring.profiles.active=dev`）
        start, buf, logical = 0, [], []
        for i, ln in enumerate(lines):
            if not buf:
                start = i + 1
            buf.append(ln)
            if not ln.rstrip().endswith('\\'):
                logical.append((start, ' '.join(x.rstrip().rstrip('\\') for x in buf)))
                buf = []
        if buf:
            logical.append((start, ' '.join(buf)))
        for ln_no, s in logical:
            if 'mvn ' not in s:
                continue
            mvn_n += 1
            # 先摘掉合法的 spring-boot.run.arguments=/jvmArguments= 取值段，剩下的才是真递给 Maven 的
            s = re.sub(r'-Dspring-boot\.run\.(arguments|jvmArguments)=\S+', '', s)
            # Maven 自己的长选项名里绝不含点（--also-make / --no-transfer-progress），
            # 含点的那个形状一定是"想给应用传属性却写在 mvn 后面" ⇒ 只按这条判，不误伤
            if re.search(r'\s--(?!\-)[A-Za-z0-9._-]*\.[A-Za-z0-9._-]*=', s):
                mvn_bad.append('%s:%d' % (os.path.relpath(p, root), ln_no))
print('MVNOPT|%d|%s' % (mvn_n, ' '.join(mvn_bad) or '-'))

link_n = 0
phantom = []
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in ('target', 'node_modules', '.git', 'dist', 'logs')]
    for fn in filenames:
        if not fn.endswith('.java') or 'src/main' not in dirpath.replace(os.sep, '/'):
            continue
        p = os.path.join(dirpath, fn)
        raw = open(p, encoding='utf-8').read()
        names = set(re.findall(r'\{@link\s+#([A-Za-z0-9_]+)', raw))
        code = re.sub(r'/\*.*?\*/', '', raw, flags=re.S)
        code = re.sub(r'(?m)//.*$', '', code)
        for n in sorted(names):
            link_n += 1
            if not re.search(r'(?m)^[^\n]*\b%s\b\s*[;=(]' % re.escape(n), code):
                phantom.append('%s:#%s' % (os.path.relpath(p, root), n))
print('PHANTOM|%d|%s' % (link_n, ' '.join(phantom) or '-'))
PY
a14_scan() { python3 "$WORK/a14_docface.py" "$REPO" a14 "${1:-$REPO/README.md}"; }
A14=$(a14_scan)
A14_HEAD=$(printf '%s\n' "$A14" | grep '^HEAD' | head -1)
A14_MOD=$(printf '%s\n' "$A14" | grep '^MODULE' | head -1)
A14_IDX=$(printf '%s\n' "$A14" | grep '^DOCIDX' | head -1)
A14_LINK=$(printf '%s\n' "$A14" | grep '^LINK' | head -1)
A14_VER=$(printf '%s\n' "$A14" | grep '^VER' | head -1)
A14_SQL=$(printf '%s\n' "$A14" | grep '^SQL' | head -1)
A14_MODN=$(printf '%s' "$A14_MOD" | cut -d'|' -f2)
A14_IDXN=$(printf '%s' "$A14_IDX" | cut -d'|' -f2)
A14_LINKN=$(printf '%s' "$A14_LINK" | cut -d'|' -f2 | cut -d'/' -f1)
mkdir -p "$WORK/prey_a14"
python3 - "$REPO/README.md" "$WORK/prey_a14/README.md" <<'PY'
import sys
t = open(sys.argv[1], encoding='utf-8').read()
t = t.replace('# z-schedule\n', '# z-wf\n', 1)                      # 标题退回模板遗留
t = t.replace('<version>1.0.4</version>', '<version>1.0.0</version>', 1)  # 抄死一个旧版本
t = t.replace('[`LICENSE`](LICENSE)', '[`LICENSE`](LICENSE-typo)', 1)      # 死链
t = t.replace('- [`_doc/004_sql/`](_doc/004_sql/) — 建表:',
              '- [`_doc/00X/`](_doc/00X/) — 建表:')                 # 索引漏一个真实目录
t = t.replace('6 张表', '7 张表', 1)                                # 抄来的数（不剥注释正好数到 7）
t = t.replace('z-schedule-core', 'z-schedule-gone')                 # 模块名对不上 <modules>
for k in ('z-wf', '1.0.0', 'LICENSE-typo', '00X', '7 张表', 'z-schedule-gone'):
    assert k in t, 'A14 猎物没注入：' + k
open(sys.argv[2], 'w', encoding='utf-8').write(t)
PY
A14P=$(a14_scan "$WORK/prey_a14/README.md")
A14_SQLN=$(printf '%s' "$A14_SQL" | cut -d'|' -f2)
A14_SQLRAW=$(printf '%s' "$A14_SQL" | cut -d'|' -f3)
if printf '%s' "$A14_HEAD" | grep -qF '|ok' \
   && [ "${A14_MODN:-0}" -gt 0 ] && [ "${A14_IDXN:-0}" -gt 0 ] && [ "${A14_LINKN:-0}" -gt 0 ] \
   && printf '%s' "$A14" | grep -qF 'MODULE|'"$A14_MODN"'|-' \
   && printf '%s' "$A14" | grep -qF 'DOCIDX|'"$A14_IDXN"'|-' \
   && printf '%s' "$A14" | grep -qF 'LINK|'"$A14_LINKN"'/' \
   && printf '%s' "$A14_LINK" | grep -qF '|-' \
   && printf '%s' "$A14_VER" | grep -qF '|-|cur=' \
   && printf '%s' "$A14_SQL" | grep -qF '|ok' \
   && printf '%s' "$A14P" | grep -qF 'first-heading=[z-wf]' \
   && printf '%s' "$A14P" | grep -qF 'MODULE|'"$A14_MODN"'|z-schedule-core' \
   && printf '%s' "$A14P" | grep -qF 'DOCIDX|'"$A14_IDXN"'|004_sql' \
   && printf '%s' "$A14P" | grep -qF 'LICENSE-typo' \
   && printf '%s' "$A14P" | grep -qF '1.0.0' \
   && printf '%s' "$A14P" | grep -qF '张数(7)'; then
  ok "A14 根 README 六项对实物全绿（标题含根 pom artifactId、模块 $A14_MODN 个逐个在场、_doc 一级目录 $A14_IDXN 个逐个被索引、相对链接 $A14_LINKN 个逐个存在、版本字面量只有 pom 的那一个、建表 $A14_SQLN 张与脚本一致〔不剥注释会数到 $A14_SQLRAW，那一档就是给抄来的数设的〕）；同一份尺在猎物上点名的正是这六类：first-heading=[z-wf]、缺模块 z-schedule-core、缺索引项 004_sql、死链 LICENSE-typo（连同 _doc/00X/）、旧版本字面量 1.0.0、张数 7≠6"
else
  bad "A14 形状不对：绿侧[$A14_HEAD][$A14_MOD][$A14_IDX][$A14_LINK][$A14_VER][$A14_SQL] 猎物[$A14P]"
fi

a15_scan() { python3 "$WORK/a14_docface.py" "$1" a15; }
A15=$(a15_scan "$REPO")
A15_MVN=$(printf '%s\n' "$A15" | grep '^MVNOPT' | head -1)
A15_LINK=$(printf '%s\n' "$A15" | grep '^PHANTOM' | head -1)
A15_MVNN=$(printf '%s' "$A15_MVN" | cut -d'|' -f2)
A15_LINKN=$(printf '%s' "$A15_LINK" | cut -d'|' -f2)
mkdir -p "$WORK/prey_a15/_doc/004_sql" "$WORK/prey_a15/app/src/main/java/x"
# 猎物树的写法要点（都是尺自己的反向对照，所以树里【不能】出现能被 shell 展开的形状）：
#   坏形状只按"围栏内 + 续行"注入；围栏外同一串字面量是描述，必须【不】被点名；
#   -Dspring-boot.run.arguments= 的取值段里有 --server.port=18999，也必须【不】被点名。
python3 - "$WORK/prey_a15" <<'PY'
import os, sys
d = sys.argv[1]
BS, DD, MM = chr(92), '-D', '--'
md = "\n".join([
  '#### 坏形状（围栏内 + 续行）', '', '```bash',
  'mvn -B spring-boot:run ' + BS,
  '  ' + MM + 'spring.profiles.active=dev ' + BS,
  '  ' + MM + 'server.port=18086',
  '```', '',
  '围栏外同样的串是【描述】不是广告，不该被点名：`mvn spring-boot:run ' + MM + 'server.port=18086`。', '',
  '合法形状：', '', '```bash',
  'mvn -B spring-boot:run ' + DD + 'spring-boot.run.profiles=dev '
  + DD + 'spring-boot.run.arguments=' + MM + 'z.base.db.schedule.disabled=true,'
  + MM + 'server.port=18999',
  '```', ''])
open(os.path.join(d, 'bad.md'), 'w', encoding='utf-8').write(md)
open(os.path.join(d, 'a.yml'), 'w', encoding='utf-8').write(
  "\n".join(['# y:', '#   mvn spring-boot:run ' + BS,
             '#     ' + MM + 'server.servlet.context-path=/meta', 'key: 1', '']))
open(os.path.join(d, 'app/src/main/java/x/A.java'), 'w', encoding='utf-8').write(
  "class A {\n  /** 见 {@link #real()} 与 {@link #noSuchMethod()} */\n  void real() {}\n}\n")
PY
A15P=$(a15_scan "$WORK/prey_a15")
if [ "${A15_MVNN:-0}" -gt 0 ] && [ "${A15_LINKN:-0}" -gt 0 ] \
   && printf '%s' "$A15_MVN" | grep -qF '|-' && printf '%s' "$A15_LINK" | grep -qF '|-' \
   && printf '%s' "$A15P" | grep -qF 'MVNOPT|3|a.yml:2 bad.md:4' \
   && printf '%s' "$A15P" | grep -qF 'PHANTOM|2|app/src/main/java/x/A.java:#noSuchMethod'; then
  ok "A15 两支都有牙：真树 $A15_MVNN 条 mvn 逻辑行里含点长选项 0 处、$A15_LINKN 个 {@link #成员} 逐个能在同一文件里找到声明；猎物树上坏形状两处各点名一次（围栏内含续行的 mvn + yml 注释同款），而围栏外那串同样的字面量与 -Dspring-boot.run.arguments= 取值段里的 --server.port 都【没】被误伤，{@link #real()} 也没被当成幽灵"
else
  bad "A15 形状不对：[$A15_MVN][$A15_LINK] 猎物[$A15P]（期望 MVNOPT 分母>0 且两处 '-'，猎物恰好 a.yml:2 bad.md:4 与 A.java:#noSuchMethod）"
fi

echo ""
echo "PASS=$PASS FAIL=$FAIL（臂 A 结束）"
if [ "${P24_A_ONLY:-0}" = "1" ]; then
  echo "P24_A_ONLY=1：跳过臂 B"; [ "$FAIL" = "0" ] || exit 1; exit 0
fi
command -v docker >/dev/null || FATAL "臂 B 要 docker，本机没有"
[ -f "$E2E/mysql.env" ] || FATAL "臂 B 要 $E2E/mysql.env（库口令只从它来，不回显）"
RUNJAR="${RUNJAR:-$E2E/z-schedule-admin-svc-7ea14eb-exec.jar}"
DDL="${DDL:-$E2E/z-schedule.sql}"
[ -f "$RUNJAR" ] || FATAL "臂 B 要有构件：RUNJAR=$RUNJAR 不存在"
[ -f "$DDL" ] || FATAL "臂 B 要建表脚本：DDL=$DDL 不存在"
# 只数非注释行里的 DROP（这份 DDL 第 7 行的注释本身就写着"不含任何 DROP"，直接 grep 会自己判死）。
# 词形要能抓**行首**的 `DROP TABLE …`——那正是 DDL 里唯一会出现的形状；旧尺 '[[:space:]]drop[[:space:]]'
# 要求词前有一个空格，对行首形状结构性失明，而且这一档当时没有阳性对照 ⇒ 这条"零 DROP"是空跑。
# 是 p25 的第一遍（它的猎物就数到 0）把这条抓出来的，修法与判据形状一起搬过来。
DROPPAT='(^|[^[:alnum:]_])drop[[:space:]]'
count_drop() { grep -vE '^[[:space:]]*--' "$1" | grep -ciE "$DROPPAT" || true; }
printf 'DROP DATABASE IF EXISTS zschedule;\nDROP TABLE whatever;\n-- 注释里也写一个 DROP TABLE 试尺\nx INT NULL; DROP TABLE inline_after;\n' > "$WORK/prey_drop.sql"
B4PREY=$(count_drop "$WORK/prey_drop.sql")
B4OLD=$(grep -vE '^[[:space:]]*--' "$WORK/prey_drop.sql" | grep -ciE '[[:space:]]drop[[:space:]]' || true)
NDROP=$(count_drop "$DDL")
[ "${B4PREY:-0}" = "3" ] || FATAL "DROP 计数尺自身坏了：猎物应有 3 处（2 行首 + 1 行中，注释不计），实得 ${B4PREY:-?}"
[ "${NDROP:-0}" = "0" ] || FATAL "$DDL 非注释行里有 $NDROP 处 DROP，不用它建库"
echo "  [note] B4 的 DDL 前置闸改用能抓行首的形状：猎物上旧尺只数到 $B4OLD/3 ⇒ 旧尺确实在漏"
MYSQLC="${MYSQLC:-z-schedule-e2e-mysql}"
docker ps --format '{{.Names}}' | grep -qx "$MYSQLC" || FATAL "MySQL 容器 $MYSQLC 不在跑"

echo ""
echo "=== 臂 B：真容器（docker），照渲染出来的清单起 ==="
set -a; . "$E2E/mysql.env"; set +a
: "${MYSQL_ROOT_PASSWORD:?}"
ROOTPW="$MYSQL_ROOT_PASSWORD"
APPID=$(md5sum "$RUNJAR" | cut -d' ' -f1)
MYENV="$WORK/mypwd"; printf 'MYSQL_PWD=%s\n' "$ROOTPW" > "$MYENV"; chmod 600 "$MYENV"
echo "  构件 $RUNJAR md5=$APPID"

CTX="$WORK/ctx"; mkdir -p "$CTX/z-schedule-admin/target"
cp -R "$DEPLOY" "$CTX/deploy"
ln -f "$RUNJAR" "$CTX/z-schedule-admin/target/app-exec.jar" 2>/dev/null || cp "$RUNJAR" "$CTX/z-schedule-admin/target/app-exec.jar"
TAG="zsched-p24:$W"
if docker build -q -f "$CTX/deploy/Dockerfile.backend" \
      --build-arg JAR_FILE=z-schedule-admin/target/app-exec.jar -t "$TAG" "$CTX" > "$WORK/build.log" 2>&1; then
  ok "B1 用提交树里的 Dockerfile.backend 建出镜像 $TAG"
else
  tail -12 "$WORK/build.log" | sed 's/^/    /'
  FATAL "docker build 失败"
fi

# B2 镜像里那份 jar 就是给进去的那份（字节对账，不看文件名）
INJAR=$(docker run --rm --entrypoint sha256sum "$TAG" /app/app.jar 2>/dev/null | cut -d' ' -f1)
ONHOST=$(sha256sum "$RUNJAR" | cut -d' ' -f1)
[ "$INJAR" = "$ONHOST" ] && ok "B2 镜像内 app.jar sha256 与构件一致（${ONHOST:0:12}…）" \
                         || bad "B2 镜像里的 jar 变了：$INJAR != $ONHOST"

# B3 镜像内 application.yml 仍带着 management.* 那块（探针面的根）
docker create --name "p24cp$W" "$TAG" >/dev/null 2>&1
docker cp "p24cp$W":/app/app.jar "$WORK/in.jar" >/dev/null 2>&1; docker rm "p24cp$W" >/dev/null 2>&1
PROBES=$(unzip -p "$WORK/in.jar" BOOT-INF/classes/application.yml 2>/dev/null | grep -c "probes" || true)
[ "${PROBES:-0}" -ge 1 ] && ok "B3 发布件内的 application.yml 含 probes 组配置（grep -c probes=$PROBES）" \
                        || bad "B3 镜像里的 application.yml 没有 probes ⇒ 探针面又成死配置"

# B4 建独立库（只动 zschedule_p24，不碰共享的 zschedule_e2e）
DBP24=zschedule_p24
case "$DB_NAME" in *"$DBP24") : ;; *) FATAL "A3 用的库名不是 $DBP24，别往下走" ;; esac
mysql_root() { docker exec -i --env-file "$MYENV" "$MYSQLC" mysql -uroot --skip-column-names -B "$@" 2>/dev/null; }
mysql_root -e "DROP DATABASE IF EXISTS $DBP24; CREATE DATABASE $DBP24 DEFAULT CHARACTER SET utf8mb4" \
  || FATAL "建 $DBP24 失败"
docker exec -i --env-file "$MYENV" "$MYSQLC" mysql -uroot "$DBP24" < "$DDL" > "$WORK/ddl.log" 2>&1 \
  || { sed -n '1,8p' "$WORK/ddl.log" | sed 's/^/    /'; FATAL "建表脚本执行失败"; }
TABLES=$(mysql_root -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DBP24'")
[ "${TABLES:-0}" -ge 6 ] && ok "B4 $DBP24 建出 $TABLES 张表（DDL 无 DROP，计数从 information_schema 取）" \
                         || FATAL "$DBP24 只有 $TABLES 张表，臂 B 无从执行"
mysql_root -e "INSERT INTO $DBP24.z_schedule_job_group (id, app_name, title, order_num, address_type) VALUES (1,'default','p24 rehearsal',0,0)" >/dev/null 2>&1

# B5 把渲染出的 ConfigMap 机械转成 --env-file（凭证不进 argv，也不回显）
python3 - "$R/01-deployment-backend.yaml" "$ROOTPW" "$DBP24" > "$WORK/envfile" <<'PY' || FATAL "取 env 失败"
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1], encoding="utf-8")) if d]
dep = next(d for d in docs if d["kind"] == "Deployment")
c = dep["spec"]["template"]["spec"]["containers"][0]
ref = next(f["configMapRef"]["name"] for f in c["envFrom"] if "configMapRef" in f)
cm = next(d for d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == ref)
out = dict(cm["data"])
out["SPRING_DATASOURCE_PASSWORD"] = sys.argv[2]
out["Z_BASE_DB_SCHEDULE_PASSWORD"] = sys.argv[2]
# 端口留给 docker run -e 覆盖（bind(0) 选的），这里只发非端口项
for k, v in out.items():
    print("%s=%s" % (k, v))
PY
chmod 600 "$WORK/envfile"
KEYS=$(grep -cE '^[A-Z]' "$WORK/envfile")
grep -q '^Z_BASE_DB_SCHEDULE_DATABASE='"$DBP24" "$WORK/envfile" \
  && ok "B5 从清单机械取出 $KEYS 个 env，库名落在 $DBP24（不是手抄）" \
  || bad "B5 取出的 env 里库名不对"

PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
CN="p24run$W"
cleanup() {
  for n in "p24run$W" "p24neg$W"; do docker rm -f "$n" >/dev/null 2>&1; done
  docker rmi "$TAG" >/dev/null 2>&1
  mysql_root -e "DROP DATABASE IF EXISTS $DBP24" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

docker run -d --name "$CN" --network host --env-file "$WORK/envfile" \
  -e SERVER_PORT="$PORT" "$TAG" > "$WORK/run.log" 2>&1 || { cat "$WORK/run.log"; FATAL "docker run 失败"; }
UP=""
for i in $(seq 1 60); do
  docker logs "$CN" 2>&1 | grep -q "Started ZScheduleAdminApplication" && { UP=yes; break; }
  docker inspect -f '{{.State.Status}}' "$CN" 2>/dev/null | grep -qx exited && { UP=dead; break; }
  sleep 2
done
[ "$UP" = "yes" ] || { docker logs --tail 25 "$CN" 2>&1 | sed 's/^/    /'; FATAL "容器里进程没起来（状态=${UP:-超时}）"; }

code() { curl -s -m 25 -o "$2" -w "%{http_code}" "http://127.0.0.1:$PORT/meta$1"; }
flat() { tr -d '\n ' < "$1" | cut -c1-200; }
BODY="$WORK/body.json"

# 探针路径就取清单里写的那两条，逐条问容器
PP=$(python3 - "$R/01-deployment-backend.yaml" <<'PY'
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1], encoding="utf-8")) if d]
c = next(d for d in docs if d["kind"] == "Deployment")["spec"]["template"]["spec"]["containers"][0]
print(" ".join(p["httpGet"]["path"] for k in ("livenessProbe", "readinessProbe")
               for p in [c.get(k)] if p))
PY
)
echo "  清单声明的探针路径：$PP"
NPROBE=0
for path in $PP; do
  NPROBE=$((NPROBE+1))
  # 前缀必须是 context-path，余下部分是 actuator 的组路径
  rest="/${path#*/meta/}"
  C=$(code "$rest" "$BODY")
  [ "$C" = "200" ] && ok "B6 清单声明的 $path 在容器里真答 200" \
                   || bad "B6 清单声明的 $path 实得 $C body=$(flat "$BODY")"
done
[ "$NPROBE" = "2" ] || bad "B6 只取到 $NPROBE 条探针路径，应有两条"

H=$(code /actuator/health "$BODY")
grep -q '"status"' "$BODY" && grep -q 'liveness' "$BODY" && grep -q 'readiness' "$BODY" \
  && ok "B7 /meta/actuator/health $H 且报出两个组：$(flat "$BODY")" \
  || bad "B7 /meta/actuator/health $H body 里没有 groups：$(flat "$BODY")"
M=$(code /actuator/metrics "$BODY")
[ "$M" = "404" ] && ok "B8 /meta/actuator/metrics 404（exposure 白名单真收口）" \
                 || bad "B8 /meta/actuator/metrics 实得 $M ⇒ 暴露面比配置宽"
GL=$(code "/jobgroup/list?limit=5" "$BODY")
JG=$(grep -c '"default"' "$BODY" || true)
[ "$GL" = "200" ] && [ "${JG:-0}" -ge 1 ] \
  && ok "B9 /meta/jobgroup/list 200 且回显 B4 插进去的分组行 ⇒ 引擎池连的就是 $DBP24" \
  || bad "B9 读不到刚写的分组：code=$GL body=$(flat "$BODY")"

# B10 真执行一次：走产品自己的 HTTP 建任务，再走产品自己的启用，等结论回落到同一个库
ADD=$(curl -s -m 25 -H 'Content-Type: application/json' -X POST "http://127.0.0.1:$PORT/meta/jobinfo/add" \
  -d '{"jobGroup":1,"jobDesc":"p24 部署彩排","author":"p24","executorRouteStrategy":"ROUND","executorBlockStrategy":"SERIAL_EXECUTION","executorHandler":"demoHandler","executorParam":"p24","triggerType":"FIX_RATE","fixInterval":2000,"misfireStrategy":"DO_NOTHING"}')
JID=$(echo "$ADD" | sed -n 's/.*"content":"\{0,1\}\([0-9]\{1,\}\).*/\1/p' | head -1)
[ -n "$JID" ] && ok "B10 HTTP 建出 FIX_RATE 任务 jobId=$JID（无需 cron，#16 那条修复在件里）" \
             || bad "B10 建任务失败：$ADD"
[ -n "$JID" ] || FATAL "B10 没建出任务，B11 无从执行"

# B10b 产品语义是"新建即停用"：add() 里硬 setTriggerStatus(0)（JobInfoServiceImpl:75），
#      而引擎只装 trigger_status=1 的行（listRunning 里那条 eq）。这一臂第一次跑红
#      （"Engine loaded 0 jobs into ring" 刷了 90 s）就是彩排漏了 start 这一步 ⇒ 把语义钉成判据。
ST0=$(mysql_root -e "SELECT trigger_status FROM $DBP24.z_schedule_job_info WHERE id=$JID")
[ "${ST0:-x}" = "0" ] && ok "B10b 建出来即停用（库里 trigger_status=$ST0，与 add() 强制置 0 一致）" \
                     || bad "B10b 建出来是 trigger_status=$ST0（不是 0）⇒ 要么 add() 改了语义，要么这条判据过时了"

BASE=$(docker logs "$CN" 2>&1 | wc -l)   # 只有这一行之后的日志属于"启用之后"
START=$(curl -s -m 25 -X POST "http://127.0.0.1:$PORT/meta/jobinfo/start?id=$JID")
echo "$START" | grep -q '"code":200' \
  && ok "B10c HTTP start 被接受（$(tr -d '\n ' <<<"$START" | cut -c1-40)）" \
  || bad "B10c start 失败：$START"
STS=$(mysql_root -e "SELECT trigger_status FROM $DBP24.z_schedule_job_info WHERE id=$JID")
[ "${STS:-x}" = "1" ] && ok "B10d 库里 trigger_status 已置 1（引擎的装载条件满足了）" \
                      || bad "B10d start 之后 trigger_status=$STS，装载条件仍不满足"

SEEN=0; NOK=0
for i in $(seq 1 30); do
  sleep 3
  NOK=$(mysql_root -e "SELECT COUNT(*) FROM $DBP24.z_schedule_job_log WHERE handle_code=200" 2>/dev/null)
  [ "${NOK:-0}" -ge 1 ] && { SEEN=1; break; }
done
[ "$SEEN" = "1" ] && ok "B11 容器把一次真实执行的结论写回库：handle_code=200 的行=$NOK（Leader 是容器自己，$DBP24 只有一个进程）" \
                  || { docker logs --tail 15 "$CN" 2>&1 | sed 's/^/    /'; bad "B11 等 90s 没等到成功结论"; }
# 装载侧证有**两条形态**，缺一不算钉住：
#   start() → registerJob() 打 "[Leader] jobId=N 已挂入时间轮"（即时那一下）；
#   每 15 s 的 reconcile 才打 "Engine loaded N jobs into ring"（从 DB 全量重算那一次）。
# 只等后一条会在第一个结论行刚落下时就判红（实测第一遍就是这样），只等前一条又证明不了
# "DB 里这一行真的被全量装载认下"——而 #10 那一族缺陷正是 reconcile 把已排期任务冲掉。
RINGHIT=$(docker logs "$CN" 2>&1 | tail -n +$((BASE + 1)) | grep -c "\[Leader\] jobId=$JID 已挂入时间轮")
[ "${RINGHIT:-0}" -ge 1 ] && ok "B11b start 当场挂轮：容器日志有 [Leader] jobId=$JID 已挂入时间轮（$RINGHIT 行）" \
                         || bad "B11b 没有'已挂入时间轮'行 ⇒ start() 走的是 Follower 支或 scheduleJob 返回了 false"
ELIG=$(mysql_root -e "SELECT COUNT(*) FROM $DBP24.z_schedule_job_info WHERE trigger_status=1")
RECON=""
for i in $(seq 1 12); do      # 等至少一次 reconcile 落在基线之后（fixedDelay 15 s，留足余量）
  RECON=$(docker logs "$CN" 2>&1 | tail -n +$((BASE + 1)) | grep -oE "Engine loaded [0-9]+ jobs" | tail -1 | grep -oE "[0-9]+")
  [ "${RECON:-0}" -ge 1 ] && break
  sleep 4
done
if [ "${RECON:-0}" = "${ELIG:-x}" ]; then
  ok "B11d reconcile 从库里全量装载出 $RECON 个，与 trigger_status=1 的行数($ELIG)相等 ⇒ 没被 15 s 那一轮冲掉"
elif [ -z "$RECON" ]; then
  bad "B11d 基线之后 48 s 内没有 Engine loaded 行 ⇒ reconcile 没跑（@Scheduled 没接上？），结论行来源无从归因"
else
  bad "B11d 全量装载 $RECON 个而库里 eligible=$ELIG ⇒ 装载集与 DB 不一致"
fi
# 进程内执行的阳性对照：handler 自己那行 logger 必须出现，否则"200"可能是别处写进库的
LOGHITS=$(docker logs "$CN" 2>&1 | tail -n +$((BASE + 1)) | grep -ac "demoHandler 执行 jobId")
[ "${LOGHITS:-0}" -ge 1 ] && ok "B11c 容器日志里有 handler 侧证（demoHandler 执行 jobId 行数=$LOGHITS，基线第 $BASE 行之后）" \
                         || bad "B11c 容器日志里没有 handler 侧证 ⇒ 那个 200 不是这个进程执行的"
LG=$(mysql_root -e "SELECT COUNT(*) FROM $DBP24.z_schedule_job_registry" 2>/dev/null)
echo "    （对照：registry 行数=$LG —— 那是远程 executor 的心跳表，进程内派发不读它，0 是预期）"
docker logs "$CN" > "$WORK/pos.log" 2>&1

# B12 负对照：把改前清单的 env 形状原样喂进去（只喂 Spring 池 + 幻影 profile k8s），
#      引擎池必须当场露出来。240 s 取证到的真实形状（判据不许照猜的 HTTP 码写）：
#        - 端口从未 bind ⇒ readiness=000 而不是 503：Druid 在 init 阶段就卡住，Tomcat 还没起
#        - 容器状态一直 running（既不 Ready 也不退出 ⇒ 集群里 restartPolicy 永远轮不到）
#        - 日志以约 86 行/s 刷 Communications link failure，240 s 刷出 20 760 行
#      所以钉成因串：引擎池 URL 必须是内置默认那条 jdbc:mysql://localhost:3306/?
#      （斜杠后直接跟问号 = 库名为空），只有"没读到 Z_BASE_DB_SCHEDULE_DATABASE"产得出它。
NEGP=$(python3 - "$ROOTPW" "$DBP24" <<'PYNEGENV'
import sys
print("JAVA_OPTS=-Xms256m -Xmx512m")
print("SPRING_PROFILES_ACTIVE=k8s")
print("SERVER_SERVLET_CONTEXT_PATH=/meta")
print("SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:33060/%s?serverTimezone=UTC" % sys.argv[2])
print("SPRING_DATASOURCE_USERNAME=root")
print("SPRING_DATASOURCE_PASSWORD=%s" % sys.argv[1])
PYNEGENV
)
NEG="p24neg$W"
PORT2=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
echo "$NEGP" > "$WORK/negenv"; chmod 600 "$WORK/negenv"
docker run -d --name "$NEG" --network host --env-file "$WORK/negenv" -e SERVER_PORT="$PORT2" "$TAG" >/dev/null 2>&1 \
  || FATAL "负对照容器起不来"
for i in $(seq 1 30); do
  sleep 3
  docker logs "$NEG" 2>&1 | grep -q 'jdbc:mysql://localhost:3306/?' && break
done
docker logs "$NEG" > "$WORK/neg.log" 2>&1
NR=$(curl -s -m 20 -o "$WORK/neg.json" -w "%{http_code}" "http://127.0.0.1:$PORT2/meta/actuator/health/readiness" || true)
ST=$(docker inspect -f '{{.State.Status}}' "$NEG" 2>/dev/null)
NEGM=$(grep -o 'jdbc:mysql://localhost:3306/?' "$WORK/neg.log" | head -1)
NREF=$(grep -c 'Connection refused' "$WORK/neg.log" || true)
NSTART=$(grep -c 'Started ZScheduleAdminApplication' "$WORK/neg.log" || true)
NLINES=$(wc -l < "$WORK/neg.log")
POSNEG=$(grep -c 'jdbc:mysql://localhost:3306/?' "$WORK/pos.log" || true)
if [ "$NR" != "200" ] && [ -n "$NEGM" ] && [ "$NSTART" = "0" ]; then
  ok "B12 负对照成立：只喂 Spring 池时 readiness=$NR 状态=$ST，$NLINES 行日志里从未 Started，引擎池落点正是 $NEGM"
  ok "B12b 那条 URL 的库名为空（'3306/?' 只有内置默认产得出），拒绝连接 $NREF 次"
  [ "$POSNEG" = "0" ] && ok "B12c 同一条尺在改后清单的日志上 0 命中 ⇒ 两侧可区分，不是尺见谁都命中" \
                      || bad "B12c 正向侧也命中了 $POSNEG 次 localhost:3306 ⇒ 两侧无法区分，B12 的结论不算数"
  echo "    （记录：容器全程 running 而不退出 ⇒ 集群里 restartPolicy 不会被触发，Ready 恒假、日志无限刷；见 README §11）"
else
  bad "B12 负对照没红：readiness=$NR 状态=$ST started=$NSTART 引擎池串='${NEGM:-无}' ⇒ 要么改前的清单其实能用，要么这一臂没跑到点"
fi
docker rm -f "$NEG" >/dev/null 2>&1

# B13 compose 入口的守卫与 env 路径：这一臂管的是"人照着 README 敲 make dev 会发生什么"。
#      负向：缺 DB_HOST 必须**非 0 退出并带消息**。改前写的是 ${DB_HOST:-mysql} ⇒ 静默起一个
#      连不上库的容器，形状就是 B12 取到的那个（端口不 bind、不退出、日志无限刷）。
#      正向：给了坐标后两个池必须从同一份 DB_* 渲染出来——臂 A 只看原始 yaml，看不见插值结果。
COMPOSE=""
if docker compose version >/dev/null 2>&1; then COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then COMPOSE="docker-compose"; fi
if [ -z "$COMPOSE" ]; then
  echo "  [SKIP] B13 这台机器两个 compose CLI 都没有 ⇒ 这一臂未测（不算通过）"
else
  : > "$WORK/empty.env"
  # env -u 是必需的：A3 里 export 过 DB_HOST=127.0.0.1，不剥掉守卫永远不会响，正向侧也就测不出东西。
  # 五个键一起剥：#31 那一格把守卫补成套（PORT/NAME/USER 也带 :?、PASSWORD 带 ?），只剥 DB_HOST 的
  # 这一臂就从"证明守卫会响"变成了"跟着 compose 的 map 顺序赌运气"。
  NOUT=$(env -u DB_HOST -u DB_PORT -u DB_NAME -u DB_USER -u DB_PASSWORD "$COMPOSE" \
           --env-file "$WORK/empty.env" -f "$DEPLOY/docker-compose.yml" config 2>&1)
  NRC=$?
  NHIT=$(echo "$NOUT" | grep -oE 'required variable DB_[A-Z_]+ is missing' | head -1)
  if [ "$NRC" != "0" ] && [ -n "$NHIT" ]; then
    ok "B13 守卫会响：五个 DB_* 全不给时 compose rc=$NRC，消息 '$NHIT'（先撞哪个键由 compose 的 map 顺序定，逐变量覆盖见 p25.sh 的 P18）"
  else
    bad "B13 守卫没响：rc=$NRC 输出=$(echo "$NOUT" | head -2 | tr '\n' ' ')"
  fi
  # 正向侧要给满五个键：DB_PASSWORD 现在带 ${DB_PASSWORD?}（整行没写就是漏配），
  # 从前那一版用 env -u DB_PASSWORD 恰好踩在这条上——那是量具没跟着修复走，不是仓库坏了。
  POUT=$(DB_HOST=127.0.0.1 DB_PORT=33060 DB_NAME=zschedule_p24 DB_USER=root DB_PASSWORD=p24-render-only-not-a-secret \
           "$COMPOSE" --env-file "$WORK/empty.env" -f "$DEPLOY/docker-compose.yml" config 2>/dev/null)
  PRC=$?
  ENG=$(echo "$POUT" | grep -c "Z_BASE_DB_SCHEDULE_HOST: 127.0.0.1" || true)
  SPRLINE=$(echo "$POUT" | grep -c "jdbc:mysql://127.0.0.1:33060/zschedule_p24" || true)
  if [ "$PRC" = "0" ] && [ "$ENG" -ge 1 ] && [ "$SPRLINE" -ge 1 ]; then
    ok "B13b 正向侧 rc=0，引擎池与 Spring 池从同一份 DB_* 渲染（各 $ENG / $SPRLINE 行）"
  else
    bad "B13b 正向侧渲染不合格：rc=$PRC 引擎池命中=$ENG Spring 池命中=$SPRLINE"
  fi
fi

echo ""
echo "总判：PASS=$PASS FAIL=$FAIL  （臂 A+B；未在真集群 apply，见档头与 README §11）"
[ "$FAIL" = "0" ] || exit 1
echo "VERDICT: OK —— 部署面这两条路（清单渲染 / 照清单起容器）都在真机上说得通"
