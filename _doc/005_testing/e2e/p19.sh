#!/bin/bash
# p19.sh — 吞吐缺口的归因采样器：天花板公式里的"连接数"实际用到几条？
#
# 为什么要这支尺：模型的两条腿都量过了——每次执行 2.05 条语句、autocommit 提交 16.7 ms
# （p15/p16/p18），按 `max-active: 20` 算出的上限是 20 ÷ 0.0167 ÷ 2.05 ≈ 584 次/s，
# 而 N=400 那档实测只有 336.7 / 342.9 次/s（达成率 84~86 %）。模型与实测差 42 %，三种可能：
#   A) 池子根本没在用满 20 条（派发并发度不够，或 reconcile 批量刷 UPDATE 把串行段挡住了）；
#   B) 每条语句占连接的墙钟比 16.7 ms 大（借还连接、Druid 等待、网络往返都在模型外）；
#   C) 上限不是线性的，被某个单线程步骤串起来了。
# 判 A/B/C 只需要一个量：**同一时刻有几条连接在同时忙**。
# 只数 processlist 的连接总数会骗人——空闲连接也在表里，所以"忙"取 COMMAND='Query'，
# STATE 另出一份直方图（其中 'waiting for handler commit' 正是提交成本本身，不能排除）。
#
# 用法（与 p16 并跑，p16 每档窗口的时间戳打在台阶标题上）:
#   ./p19.sh sample 300 logs/pool.csv     # 采样 300s，每 ~500ms 一行
#   ./p19.sh report logs/pool.csv          # 按 30s 桶打印峰值/均值并发 + STATE 直方图
set -u
cd "$(dirname "$0")"; . ./mysql.env

MODE="${1:-sample}"
CSV="${3:-logs/pool.csv}"
CONTAINER="${CONTAINER:-z-schedule-e2e-mysql}"

busy_hist() { # 桶 $1 ⇒ 该桶内每行的 "epoch busy" 打到 stdout（$1=epoch, $2=busy, $3=states）
  awk -F'\t' -v b="$1" 'NR > 1 && $1 >= b && $1 < b + 30 {print $1 "\t" $2}' "$CSV"
}

sample() {
  local secs="$1" i ts
  printf 'epoch\tbusy\tstates\n' > "$CSV"
  for ((i = 0; i < secs * 2; i++)); do
    # MYSQL_PWD 走环境，不进 argv；一次查询同时取"忙连接数"和 STATE 分布
    ROWS=$(./q.sh -N -B -e \
      "SELECT IFNULL(STATE,'-') AS st, COUNT(*) AS c, SUM(COMMAND='Query') AS q
       FROM information_schema.PROCESSLIST
       WHERE USER='$MYSQL_USER' AND DB='$MYSQL_DATABASE' GROUP BY st") || return 1
    ts=$(date +%s)
    printf '%s\t%s\t%s\n' "$ts" \
      "$(printf '%s\n' "$ROWS" | awk -F'\t' '{s += $3} END{print s + 0}')" \
      "$(printf '%s\n' "$ROWS" | awk -F'\t' '{printf "%s=%d;", $1, $2}' | sed 's/;$//')" >> "$CSV"
    sleep 0.5
  done
  echo "采样结束：$(( $(wc -l < "$CSV") - 1 )) 行 ⇒ $CSV"
}

report() {
  local buckets
  buckets=$(awk -F'\t' 'NR > 1 {print int($1 / 30) * 30}' "$CSV" | sort -un)
  [ -n "$buckets" ] || { echo "FATAL: $CSV 里没有数据行（尺空跑，别把空表当结论）"; return 1; }
  printf '%-10s %8s %8s\n' '桶起点' '峰值并发' '均值并发'
  for b in $buckets; do
    busy_hist "$b" | awk -F'\t' -v b="$b" 'NR > 0 { if ($2 > p) p = $2; s += $2; n++ }
      END { if (n) printf "%-10s %8d %8.1f\n", b, p, s / n }'
  done
  echo '--- STATE 直方图（连接·次，全程） ---'
  awk -F'\t' 'NR > 1 { print $3 }' "$CSV" | tr ';' '\n' | awk -F= '{ h[$1] += $2 }
    END { for (k in h) printf "  %-38s %d\n", k, h[k] }' | sort -k2 -nr
  echo "阳性对照：并发>0 的采样行数 = $(awk -F'\t' 'NR > 1 && $2 > 0' "$CSV" | wc -l)" \
       "/ 总行数 $(($(wc -l < "$CSV") - 1))（若前者为 0 则本尺什么都没看见，别引用它的数）"
}

preytest() { # 已知猎物：埋 $1 条并发 SELECT SLEEP(4)，本尺必须数到 ≥$1
  local n="$1" i peak
  for ((i = 0; i < n; i++)); do
    docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" "$CONTAINER" \
      mysql -h127.0.0.1 -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B -e "SELECT SLEEP(4)" >/dev/null &
  done
  sleep 1.5
  peak=$(./q.sh -N -B -e "SELECT COUNT(*) FROM information_schema.PROCESSLIST
         WHERE USER='$MYSQL_USER' AND DB='$MYSQL_DATABASE' AND COMMAND='Query'")
  wait
  echo "  埋了 $n 条并发查询 ⇒ 尺数到 $peak"
  [ "${peak:-0}" -ge "$n" ] || { echo "FATAL: 尺数不到已知并发 ⇒ 后面所有『峰值并发』读数作废"; return 1; }
  echo "  PASS：并发计数可信（有猎物在跑，所以『数到 0』一定是尺坏了而不是没负载）"
}

case "$MODE" in
  sample) sample "${2:-60}" ;;
  preytest) preytest "${2:-6}" ;;
  # report 的 CSV 是位置参数：漏了这行赋值，报告就会去读默认路径的旧文件（自测时就是这么露馅的）
  report) CSV="${2:?用法: ./p19.sh report <csv>}"; report ;;
  *) echo "未知模式：$MODE（sample|report）"; exit 1 ;;
esac
