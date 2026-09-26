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
#      deploy/env/ 下 ⇒ 照 README 走的人 DB_* 根本读不到。现在 DB_HOST 没有默认值，缺了当场拒（B13）。
#
# 臂 A 不碰 docker，只问"清单自己前后一致吗"；臂 B 在 250 上用 docker 跑真容器，并带一条
# **负对照**：把改前那套 env（只喂 Spring 池 + 幻影 profile）原样喂进去，引擎池必须当场坏给用户看（B12）；B13 再拿 compose
# 的插值守卫做双向（缺 DB_HOST 必须非 0，给了必须两个池同源）。
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
  # env -u 是必需的：A3 里 export 过 DB_HOST=127.0.0.1，不剥掉守卫永远不会响，正向侧也就测不出东西
  NOUT=$(env -u DB_HOST -u DB_PORT -u DB_NAME "$COMPOSE" --env-file "$WORK/empty.env" \
           -f "$DEPLOY/docker-compose.yml" config 2>&1)
  NRC=$?
  if [ "$NRC" != "0" ] && echo "$NOUT" | grep -q "DB_HOST"; then
    ok "B13 守卫会响：缺 DB_HOST 时 compose rc=$NRC，消息点名 DB_HOST"
  else
    bad "B13 守卫没响：rc=$NRC 输出=$(echo "$NOUT" | head -2 | tr '\n' ' ')"
  fi
  POUT=$(env -u DB_PASSWORD DB_HOST=127.0.0.1 DB_PORT=33060 DB_NAME=zschedule_p24 DB_USER=root \
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
