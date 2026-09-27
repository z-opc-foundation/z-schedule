#!/usr/bin/env python3
"""tally_surefire.py — 把一次 maven 运行的 surefire XML 收成"可跨机比对的清单"。

为什么要有这么一支东西（2026-09-27，136 跨机复验那一跑）：
  1. **只看 `Tests run:` 那一行不足以对账**——两台机器都报 329 只能说明总数相同，
     说明不了"是同样那 329 条"（少跑一个类、另一个类多跑一条，总数可以不变）。
  2. **耗时绝不能进清单**——同一份代码在两台机器上差几倍是常态（本机 SSD vs 136 的
     LV，JIT 冷热也不同），带耗时的清单永远比不出等号，于是"不一致"会被误读成缺陷。
  3. **陈旧 XML 会抬分母**（§19.4-④：一次聚合读出 340/3 而构建日志真值 328/0，差的
     12 例 3 红正好是两次已经被删掉的运行留下的文件）。所以按 `--since` 的 mtime 卡
     本次运行起点，并把"被卡掉的份数"印出来——那份数本身就是"上次有没有清干净"的读数。

用法：
  python3 tally_surefire.py <repo根> --since <epoch秒>        # 打印清单 + TOTAL + 清单 md5
  python3 tally_surefire.py <repo根> --since <epoch秒> --quiet # 只打 TOTAL 与 md5（对账用）
"""
import glob
import hashlib
import os
import sys
import time
import xml.etree.ElementTree as ET


def main():
    argv = sys.argv[1:]
    if not argv:
        sys.exit("FATAL: 少参数（用法见文件头 docstring）")
    root = argv[0]
    since = 0
    quiet = False
    if "--since" in argv:
        i = argv.index("--since")
        if i + 1 >= len(argv):
            sys.exit("FATAL: --since 后面没有值")
        since = float(argv[i + 1])
    quiet = "--quiet" in argv

    if not os.path.isdir(root):
        sys.exit("FATAL: 目录不存在 %s" % root)
    files = glob.glob(os.path.join(root, "*/target/surefire-reports/TEST-*.xml"))
    if not files:
        # 空输入必须响：找不到文件既可能是"没跑测试"也可能是路径写错，两者都不能记成 0 例通过
        sys.exit("FATAL: %s 下一份 TEST-*.xml 都没有 ⇒ 本次没测到东西（跑过 mvn test 吗？路径对吗？）" % root)

    fresh, stale, rows, skipped_by_module = [], [], [], {}
    for f in sorted(files):
        mod = os.path.relpath(f, root).split(os.sep)[0]
        if os.path.getmtime(f) < since:
            stale.append(f)
            skipped_by_module[mod] = skipped_by_module.get(mod, 0) + 1
            continue
        fresh.append(f)
        r = ET.parse(f).getroot()
        rows.append("%s :: %s tests=%s failures=%s errors=%s skipped=%s" % (
            mod, r.get("name"), r.get("tests"), r.get("failures"),
            r.get("errors"), r.get("skipped")))

    rows.sort()
    if not rows:
        sys.exit("FATAL: %d 份 XML 全部比 --since 旧 ⇒ 本次没测到东西（陈旧份数=%d）"
                 % (len(files), len(stale)))

    total = sum(int(x.split(" tests=")[1].split()[0]) for x in rows)
    bad = sum(int(x.split(" failures=")[1].split()[0])
              + int(x.split(" errors=")[1].split()[0]) for x in rows)
    pass_total = sum(int(x.split(" skipped=")[1].split()[0]) for x in rows)
    digest = hashlib.md5(("\n".join(rows).encode("utf-8"))).hexdigest()

    if not quiet:
        for x in rows:
            print(x)
        if skipped_by_module:
            print("# 陈旧（比 --since 旧，已排除）: "
                  + " ".join("%s=%d" % kv for kv in sorted(skipped_by_module.items())))
    print("CLASSES=%d TOTAL=%d FAIL_OR_ERR=%d SKIPPED=%d STALE_EXCLUDED=%d"
          % (len(rows), total, bad, pass_total, len(stale)))
    print("TALLY_MD5=%s" % digest)
    print("# 清单口径：每行 `模块 :: 类名 tests/failures/errors/skipped`，按整行排序；"
          "不含任何耗时字段（同一份代码两机耗时可差数倍，进清单就比不出等号）")


if __name__ == "__main__":
    main()
