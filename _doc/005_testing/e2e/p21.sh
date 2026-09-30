#!/bin/bash
# p21.sh — 每次执行的 2 条语句里，到底哪一层在花时间？
#
# 背景：p15 量到每次执行落 2 条语句；digest 里那条 UPDATE 是**12 列全量**写回
# （765,531 次执行，见 _q_digest.sql），INSERT 是 10 列（711,260 次）。
# 于是"把 2 条压成 1 条"看起来是下一层性能。但这句话里藏了两个完全不同的假设：
#   (a) 语句**形状**贵 —— 12 列 SET 比 4 列 SET 慢 ⇒ 该收窄 SET 列表；
#   (b) 语句**条数**贵 —— 每条 autocommit 各付一次 redo/binlog fsync ⇒ 该合并提交。
# 如果是 (b)，收窄 SET 一点收益都没有；唯一有收益的是把两次提交并成一次，
# 而并提交要付的代价是 INSERT 不再单独可见 ⇒ 运行中的日志行没了（kill 靠的就是它）。
# 所以四臂都要，且同口径：
#   narrow / wide   —— 同一批 id、同样真实变化的值，只差 SET 列表 ⇒ 判 (a)
#   pair2           —— 一对写 = 2 次提交（app 现状：INSERT 后独立 UPDATE）
#   pair1           —— 一对写 = 1 次提交（③ 想要的上限）        ⇒ 判 (b) 的收益上限
# 不启动 app：这里问的是提交层单价，TLS 与线程调度对四臂是同一个加数 ⇒ 差值仍成立，
# 绝对值是下界（同 p18 的口径声明）。
set -u
cd "$(dirname "$0")"

BENCH_GROUP="${BENCH_GROUP:-90021}"
# pair 两臂会**插入**新行，如果和 narrow/wide 共用 job_group，那一轮结束后的"值真落库了吗"
# 计数会把两批行混在一起（首次实跑就打出 400/200 ⇒ 这条对照对 narrow/wide 失效）。分开。
PAIR_GROUP="${PAIR_GROUP:-90022}"
N="${N:-200}"
ROUNDS="${ROUNDS:-5}"
OUT="${OUT:-logs/p21.tsv}"
mkdir -p logs

log() { echo "[$(date +%H:%M:%S)] $*"; }
q() { ./q.sh -N -B --skip-column-names "$@" 2>&1; }
qt() { ./q.sh -t "$@" 2>&1; }   # -t: 带表格，给人看

# 单引号一律由 awk 的 -v q 注入并做**字符串拼接**：awk 程序本身在 shell 单引号里写不进
# 字面 '，而 p18 那种 printf "%c" 39 的写法要精确数 specifier 个数——多一个少一个都是
# 静默产出坏 SQL（少一个引号 ⇒ 语法错；多一个 ⇒ 值被吃掉）。
Q="'"
TAG="p21-$RANDOM$RANDOM"

sql_narrow() {
  awk -v r="$1" -v tag="$TAG" -v q="$Q" '{
    print "UPDATE z_schedule_job_log SET handle_code=200,handle_time=NOW(),handle_msg=" q "okN-" tag "-" r q ",alarm_status=0 WHERE id=" $1 ";"
  }'
}
# 与线上 digest 逐列同形：12 列 SET（app 走 MyBatis-Plus updateById，整个 DTO 写回）
sql_wide() {
  awk -v r="$1" -v tag="$TAG" -v q="$Q" -v g="$BENCH_GROUP" '{
    print "UPDATE z_schedule_job_log SET job_group=" g ",job_id=" $2 ",executor_handler=" q "p21h" q ",executor_param=" q "p21p" q ",executor_fail_retry_count=0,trigger_time=NOW(),trigger_code=0,trigger_msg=" q tag "-" r q ",handle_code=200,handle_msg=" q "okW-" tag "-" r q ",alarm_status=0 WHERE id=" $1 ";"
  }'
}
# 一对写、两次提交（app 现状）
sql_pair2() {
  awk -v r="$1" -v tag="$TAG" -v q="$Q" -v g="$PAIR_GROUP" '{
    print "INSERT INTO z_schedule_job_log (job_group,job_id,executor_handler,executor_param,executor_fail_retry_count,trigger_time,trigger_code,trigger_msg,handle_code,alarm_status) VALUES (" g "," $1 "," q "h" q "," q "p" $1 q ",0,NOW(),0," q "pair2-" tag "-" r q ",0,0);"
    print "UPDATE z_schedule_job_log SET handle_code=200,handle_time=NOW(),handle_msg=" q "okP2-" tag "-" r q ",alarm_status=0 WHERE id=LAST_INSERT_ID();"
  }' <<< "$(seq 0 $((N - 1)))"
}
# 同一对写、一次提交（③ 的上限）。
# 刻意"每对一个事务"而不是"N 对塞一个大事务"：后者量的是 1 次提交摊 N 对，会系统性高估收益。
sql_pair1() {
  awk -v r="$1" -v tag="$TAG" -v q="$Q" -v g="$PAIR_GROUP" '{
    print "START TRANSACTION;"
    print "INSERT INTO z_schedule_job_log (job_group,job_id,executor_handler,executor_param,executor_fail_retry_count,trigger_time,trigger_code,trigger_msg,handle_code,alarm_status) VALUES (" g "," $1 "," q "h" q "," q "p" $1 q ",0,NOW(),0," q "pair1-" tag "-" r q ",0,0);"
    print "UPDATE z_schedule_job_log SET handle_code=200,handle_time=NOW(),handle_msg=" q "okP1-" tag "-" r q ",alarm_status=0 WHERE id=LAST_INSERT_ID();"
    print "COMMIT;"
  }' <<< "$(seq 0 $((N - 1)))"
}

# 每臂写完后**立刻**只数自己那批行：跨臂不共用判据，才分得开"这一臂真改了"还是"上一臂留的"
check_arm() {   # check_arm <prefix> <round> <group>  ⇒ 打印命中行数
  local prefix="$1" r="$2" grp="$3"
  q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$grp AND handle_msg LIKE '$prefix-$TAG-$r'" | tail -1
}

ms() { local t0 t1; t0=$(date +%s%N); printf '%s\n' "$1" | ./q.sh -N -B >/dev/null 2>&1; t1=$(date +%s%N); echo $(( (t1 - t0) / 1000000 )); }

clean_pair() { q -e "DELETE FROM z_schedule_job_log WHERE job_group=$PAIR_GROUP" >/dev/null; }

stat_arm() {   # stat_arm <arm> <每轮行数的除数> —— 从 $OUT 取该臂各轮 ms，报 min/median/max
  local arm="$1" div="$2"
  awk -F'\t' -v a="$arm" -v div="$div" '$1==a{print $3}' "$OUT" | sort -n |
    awk -v a="$arm" -v div="$div" '{n++; v[n]=$1}
      END{
        if (!n) { printf "  %-8s 无读数\n", a; exit }
        med = (n%2) ? v[(n+1)/2] : (v[n/2]+v[n/2+1])/2
        printf "  %-8s rounds=%-2d  %s .. %s ms   中位=%s ms   ⇒ %.3f ms/单位\n", a, n, v[1], v[n], med, med/div
      }'
}

echo "===================== p21 语句形状 vs 提交次数 ====================="
echo "--- 0) 服务端持久化配置（决定每次提交要不要 fsync）---"
qt -e "SHOW VARIABLES WHERE Variable_name IN
 ('innodb_flush_log_at_trx_commit','sync_binlog','innodb_flush_method','version')"

echo
echo "--- 1) 建基准：$N 行，narrow/wide 两臂只改这批同一 id ---"
q -e "DELETE FROM z_schedule_job_log WHERE job_group=$BENCH_GROUP" >/dev/null
clean_pair
# heredoc 而不是 -e "$rows"：200 行拼成**单个** argv 时会在 ~1600 行规模撞上
# Linux MAX_ARG_STRLEN=128KB（p20 在 N=1600 上就是这么 0 行入库却照出读数的）。
rows=""
for ((i = 0; i < N; i++)); do
  rows="$rows($BENCH_GROUP,$i,'','',0,NOW(),0,'',0,0,''),"
done
q <<SQL
INSERT INTO z_schedule_job_log
 (job_group, job_id, executor_handler, executor_param, executor_fail_retry_count,
  trigger_time, trigger_code, trigger_msg, handle_code, alarm_status, handle_msg)
 VALUES ${rows%,};
SQL
IDS=$(q -e "SELECT id, job_id FROM z_schedule_job_log WHERE job_group=$BENCH_GROUP ORDER BY id")
GOT=$(grep -c '^[0-9]' <<< "$IDS")
echo "  实际建出行数=$GOT / 要求 $N"
[ "$GOT" -lt "$N" ] && { echo "FATAL: 基准行不足，读数无意义"; exit 1; }
: > "$OUT"

for ((r = 1; r <= ROUNDS; r++)); do
  # 轮转臂序：同一轮里先跑的臂会顺手预热 buffer pool，固定顺序让固定排前面的臂一直吃亏
  case $(( (r - 1) % 4 )) in
    0) ORDER="narrow wide pair2 pair1";;
    1) ORDER="wide pair2 pair1 narrow";;
    2) ORDER="pair2 pair1 narrow wide";;
    3) ORDER="pair1 narrow wide pair2";;
  esac
  for arm in $ORDER; do
    case "$arm" in
      narrow) T=$(ms "$(sql_narrow "$r" <<< "$IDS")"); C=$(check_arm okN "$r" "$BENCH_GROUP");;
      wide)   T=$(ms "$(sql_wide   "$r" <<< "$IDS")"); C=$(check_arm okW "$r" "$BENCH_GROUP");;
      pair2)  clean_pair; T=$(ms "$(sql_pair2 "$r")"); C=$(check_arm okP2 "$r" "$PAIR_GROUP");;
      pair1)  clean_pair; T=$(ms "$(sql_pair1 "$r")"); C=$(check_arm okP1 "$r" "$PAIR_GROUP");;
    esac
    # 阳性对照：这一臂的值必须真落库 $N 行。若不等，则该臂"快"是因为 MySQL 发现新旧值
    # 相同而空跑（不写 undo、不置脏页）——那是假速度，读数作废。
    printf '%s\t%s\t%s\n' "$arm" "$r" "$T" >> "$OUT"
    if [ "$C" -lt "$N" ]; then
      log "round$r $arm: ${T} ms  [对照] 落库=$C/$N ⇒ 作废"
      printf 'INVALID\t%s\t%s\n' "$arm" "$C" >> "$OUT"
    else
      log "round$r $arm: ${T} ms  [对照] 落库=$C/$N"
    fi
  done
done

echo
echo "--- 2) 每臂汇总（读自 $OUT；pair 两臂的除数是 $N 对=2$N 条语句）---"
stat_arm narrow "$N"
stat_arm wide   "$N"
stat_arm pair2  "$N"
stat_arm pair1  "$N"

echo
echo "--- 3) 结论口径（要人对着上一节读数判定，脚本不替下结论）---"
echo "  narrow vs wide ：差值落在 min..max 抖动内 ⇒ 形状不花钱、只有提交次数花钱 ⇒ 收窄 SET 无收益。"
echo "  pair2 vs pair1 ：差值 = 把每次执行的 2 次提交并成 1 次能省下的时间上限；"
echo "                   除以池内连接数才是吞吐增益，别拿孤立 ms/对 直接推吞吐（p18 已证高估 69%）。"

clean_pair
q -e "DELETE FROM z_schedule_job_log WHERE job_group=$BENCH_GROUP" >/dev/null
echo "  清理后 基准组剩余=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$BENCH_GROUP")，pair 组剩余=$(q -e "SELECT COUNT(*) FROM z_schedule_job_log WHERE job_group=$PAIR_GROUP")"
echo "===================== p21 结束 ====================="
