# z-schedule 真机部署 + 验证 runbook（250 演练记录，可在新机重放）

这条链路的意义不是"有个脚本"，而是：**本地 H2 那套测试全绿的产品，在真 MySQL 8 上有三条缺陷是它
量不出来的**（FIX_DELAY 建不出来、执行结论不落库、`trigger_type` 列宽截断）。所以发布前的
"真机一遍"是必经步骤，而这个目录就是那一遍的全部工具。

演练环境：`250`（Ubuntu + docker + JDK 8），MySQL `8.0.26` 容器，库 `zschedule_e2e`。

---

## 0. 前置

| 需要 | 校验命令 | 不合格会怎样 |
|---|---|---|
| docker 可用 | `docker ps` | 起不了库，只能退回 H2（性能与 MySQL 方言结论全部作废） |
| JDK 8 与 Maven | `java -version && mvn -v` | 模块按 `source/target 8` 编译，高版本 JDK 会给出与发布件不同的字节码 |
| 端口 33060/18086 空闲 | `ss -ltnp \| grep -E '33060\|18086'` | 容器或应用起不来，且容易连到别人留下的实例上 |

## 1. 一次完整演练

```bash
# 1) 建环境（幂等：容器已存在就复用，绝不清库）
E2E_HOME=~/z-schedule-e2e ./bootstrap_mysql.sh

# 2) 出构件（reactor 内打包，不 mvn install —— 避免污染本机 m2 的已发布坐标）
cd <repo>/z-schedule && mvn -pl z-schedule-admin -am package -DskipTests
scp z-schedule-admin/target/z-schedule-admin-*-exec.jar 250:~/z-schedule-e2e/z-schedule-admin-fix-exec.jar

# 3) 起应用（口令只经环境变量进 JVM，见下节）
ssh 250 'cd ~/z-schedule-e2e && JAR=z-schedule-admin-fix-exec.jar PORT=18086 \
         Z_SCHEDULE_ACCESSTOKEN=... nohup ./run.sh >logs/run.out 2>&1 &'

# 4) 验（每支尺做什么见第 3 节）
ssh 250 'cd ~/z-schedule-e2e && ./e2e.sh && ./p11.sh && ./p13.sh && ./p14.sh'

# 5) 收：停应用、还原被改过的服务端参数、清掉自己种的数据（第 4 节）
```

### 1′. 第二次演练：从"全新库"整链重放，抓到四条脚本缺陷

上面第 1 节那次是"在已有 rig 上跑"。第二次刻意换了**一套并行的新坐标**
（`E2E_HOME=~/z-schedule-drill`、`CONTAINER=z-schedule-drill-mysql`、`HOST_PORT=33062`、`PORT=18087`），
从 `bootstrap_mysql.sh` 建库开始重放，为的就是让"脚本其实没接上环境"这类问题显形。抓到的四条都已在
本目录修掉：

| 症状 | 真因 | 现在的做法 |
|---|---|---|
| `./bootstrap_mysql.sh: Permission denied` | `scp` 不带 `-p` 会丢可执行位；而 **git 本身就存 mode**，所以从仓库检出也是 644 | 脚本 `chmod 755` 入库；传输一律 `scp -p` |
| `sed: can't read ./mysql.example.env` | 脚本先 `cd $E2E_HOME` 再取 `$(dirname "$0")` ⇒ 自己的目录已经被 cd 换掉了 | `SCRIPT_DIR` 在任何 cd 之前定死 |
| 应用 `Access denied for user 'zschedule'@'172.17.0.1'`，看着像口令错 | `run.sh` 把 `127.0.0.1:33060/zschedule_e2e` 写死 ⇒ 它带着**新库的口令**去敲了**旧容器**。写死坐标不报错，只是静默连到"上一个"库，于是后面所有结论都是从那个库量出来的 | 库坐标改从 `mysql.env` + `DB_HOST/DB_PORT` 取，并在日志首行打印 `deploy: JAR=… db=… user=…`（不打口令） |
| `e2e.sh` 一边对新实例下请求、一边读旧库；而且把 root 口令放进了 argv | `BASE=` 与 `docker exec … -p"$MYSQL_ROOT_PASSWORD"` 都是写死的；`-p` 形式**同机任何人 `ps` 可见** | `BASE`/`PORT`/`CONTAINER` 全部可覆盖；裸读统一走 `q.sh`（`MYSQL_PWD` 进 env，不进 argv） |

外加两条 API 命名坑，不在脚本里、在请求体里，写在这里省后来人一小时：

* 触发类型字段是 **`triggerType`**（不是 `scheduleType`）。传错名的表现是"FIX_DELAY 任务被
  `Cron表达式不能为空` 挡回"——因为 `triggerType` 为空时服务端按 CRON 兜底，看着像校验多此一举。
* 分组字段是 **`appName`**（不是 `appDesc`），报的也是 `AppName 不能为空`。

重放结果（新库、真 MySQL 8.0.26，全部只从 MySQL 裸读回证）：`bootstrap` → 建分组 → `e2e.sh` P1–P5
全绿（`FIX_RATE/5000/120/2` 落库形状正确；P3 partial update 不抹列；P4 非法间隔被拒；P5 显式 0 真落 0），
启动后 `z_schedule_job_leader` 被本实例持有并续约，`z_schedule_job_log` 在 5 秒间隔下出现
`首次执行 → 重试第1次 → 重试第2次` 三行——**`executorFailRetryCount=2` 在真库上兑现成了恰好两次重试**，
这一条 H2 那套是量不出"引擎真的在跑"的（`handle_code=404` 是 `demoHandler` 这个 bean 在本次部署里不存在，
不是派发缺陷）。

### 判"现在跑的是哪一版"——只认两处，不认文件名

`run.sh` 的 `JAR=` 决定了加载哪个文件；再用 md5 对上构件本身。**jar 文件名不算证据**
（这里踩过：`z-schedule-admin-1.0.0-exec.jar` 这个名字在 250 上先后对应过 4 个不同字节，
`pre-fix` / `p1-no-predestroy` / `p2-no-adminauth` 三版都是靠改名而不是改版本号区分的）。

要看某个修复在不在跑着的进程里，用字节码，不看 mtime、不看日志：

```bash
unzip -p <jar> BOOT-INF/classes/.../ZScheduleAutoConfiguration.class > /tmp/x.class  # starter 在 lib/ 里，不是 BOOT-INF/classes
javap -v /tmp/x.class | grep -c 'PUBLIC_PATHS'      # 例：#12 的鉴权面收敛
javap -c  /tmp/x.class | grep -c 'stepDown'         # 例：#18 的关停释放租约
```

## 2. 凭据纪律

* 真值只在 `$E2E_HOME/mysql.env`（**600，仓库外，永不提交**）。`bootstrap_mysql.sh` 缺文件时用
  `secrets.token_urlsafe` 随机生成，正常流程不需要人填。
* 交给容器用 `docker run --env-file`；交给 JVM 用环境变量。
  **命令行 argv 会被同机任何人 `ps` 看到** ⇒ 密码绝不写进 `java` 参数。
  Spring 的 relaxed binding 把 `Z_BASE_DB_SCHEDULE_*` 映射到 `z.base.db.schedule.*`
  （starter 自建 Druid 走这套），`Z_SCHEDULE_ACCESSTOKEN` 映射到 `z.schedule.accessToken`。
* 端口只绑 `127.0.0.1`：`-p 127.0.0.1:33060:3306`。
* 建表只用仓内 `_doc/004_sql/z-schedule.sql`（6 CREATE + 1 种子，**零 DROP**）。
  `bootstrap_mysql.sh` 在真跑之前会先数一遍 DROP 条数，非 0 直接拒绝——
  历史脚本（含 z-opc 的 `init.sql`）里有 15 条 DROP，照跑会删库。

## 3. 尺子清单

真值一律从 MySQL 裸读（`q.sh`），**不用被测服务自己的 mapper 回读**——映射层的 bug 会和被测代码互相圆场。

| 脚本 | 量什么 | 钉住的缺陷 |
|---|---|---|
| `q.sh` | 容器内 mysql 客户端（口令走 `MYSQL_PWD`，退出码原样传给调用方） | — |
| `e2e.sh` | 新增 FIX_RATE → 启动 → 只带 id+jobDesc 的 partial update → 非法间隔 → 显式 0 | #16 #11 |
| `p6.sh` | FIX_DELAY 真库写入、管理面端点普查、停删清理 | #16 |
| `p10.sh` | `accessToken` 是否真绑定进 `ScheduleProperties`（重启一次，看行为不是看 yml 里那行字） | #12 |
| `p11.sh` | 换修复后的 jar，一次跑穿 #16 / #15 + 真实时钟节拍 | #15 #16 |
| `p12.sh` | p11 零日志的真相：那 24 s 本节点还不是 Leader，`start` 走了 Follower 分支 | #18 |
| `p13.sh` | "重启后一个已 start 的任务多久真开始执行"（修复前必等旧租约 30 s） | #18 |
| `p14.sh` | 鉴权兑现面：配 token 后除登录口和静态外壳外一律 403，带 token 恢复 | #12 |
| `p15.sh` | **一次执行往 MySQL 写几条语句、每条多少钱** | 写放大（今天从 3.07 → 2.05 条/次） |
| `p16.sh` | 吞吐阶梯 20/100/400 个 1 Hz 任务：达成率、语句/次、ERROR/s、**日志字节/次** | 性能基线 |
| `p17.sh` | 归因：`top -H` + `jstack` 数线程在干什么（tick 池 / 快慢池 worker 状态分布） | 排除"派发单线程/池太小" |
| `p18.sh` | **提交成本**：200 条单行 UPDATE 的 autocommit vs 单事务 + 连接状态普查 + TLS 计数 | 天花板真因 |

## 4. 今天量到的结论（同一把尺 p16，N=400，窗口 30 s）

| 状态 | A 组（不打堆栈） | B 组（每次打整摞堆栈） | 语句/次 | 单次提交 |
|---|---|---|---|---|
| 已发布 1.0.4 字节 + `trx_commit=1` | 297.4 次/s（74.3 %） | 291.6 次/s（72.9 %） | 3.07 | 16.70 ms |
| + 今天的写放大修复 | 336.7 次/s（84.2 %） | 342.9 次/s（85.7 %） | 2.05 | 16.70 ms |
| + `innodb_flush_log_at_trx_commit=2`（`sync_binlog` 仍 1） | **408.6 次/s（102.2 %）** | 377.2 次/s（94.3 %） | 2.00 | 8.89 ms |

* **上限 = 连接数 ÷ 提交成本 ÷ 每次执行语句数**。25 ÷ 16.7 ms ÷ 3.07 ≈ 390，量到 297——同数量级，
  模型成立；剩下的差是需求侧没打满（第三档 408 次/s 已经是"要多少给多少"，要再看天花板得加到 N=800/1600）。
* **堆栈日志不是瓶颈**（297 vs 292 是同一条曲线）；它的真实代价是**体积**：155 B/次 → 1767–1792 B/次
  （≈11 倍，N=400 时 30 s 写 20 MB）。
* 服务端 `performance_schema` 的语句计时器**结构上看不见提交等待**（只报 10–25 µs 的服务端 CPU 时间），
  墙钟成本在 `information_schema.processlist` 的 `waiting for handler commit` 状态里。
  ⇒ 用 µs 级读数去判"落库不是问题"是错的（我今天就是这样误判过一次，把修复贬成"值 8.5 µs"）。
* 只调 `trx_commit=2` 不能翻倍：余下的墙钟是 `sync_binlog=1` 的那次 binlog fsync。

## 5. 收口纪律（跑完必须还原，否则下一个人量到的是你的现场）

```bash
# 服务端参数：改过就要改回，并回读确认
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" z-schedule-e2e-mysql mysql -h127.0.0.1 -uroot -e \
  "SET GLOBAL innodb_flush_log_at_trx_commit=1; SET GLOBAL sync_binlog=1;"
# 自己种的数据：按 group 清，别全表删（p16/p18 用 90016 / 90018）
# 进程：pgrep -af "java .*z-schedule-admin"（不要用 pgrep -f "z-schedule-admin"——它会匹配到你自己那条检查命令）
```

## 6. 已知坑（每一条都对应一次返工）

1. **重启后 24–30 s 零执行**：旧实例不释放 Leader 租约，新实例必须等 TTL 过期。#18 已修（`@PreDestroy` →
   `stepDown()`）；判"修没修"用 `p13.sh`，别用"日志里看到了 BECOME LEADER"。
2. **Follower 的 `start` 会静默返回 success**：`[Follower] skip registerJob` —— 界面 `trigger_status=1`，
   实际没排上。验调度类修复前先确认本节点是 Leader。
3. **H2 验不出 MySQL 方言**：`trigger_type` 是 `NOT NULL` 且无默认值时，MySQL 严格模式报 1364，H2 不报；
   列宽 `varchar(8)` 放不下 `FIX_DELAY` 时，非严格模式**静默截断**成 `FIX_DELA` ⇒ 枚举匹配不上，任务按
   cron 分支跑，FIX_DELAY 从来没生效过，而界面上一切正常。
4. **孤立日志行会骗过"落库行数"**：`z_schedule_job_log` 里 `job_info` 父行已删的孤儿，join 式清理删不掉，
   于是阶梯跑完报 `本组落库行数=2097 而 Δ=0`。窗口速率只认 digest 差值。
5. **量具别放 `/tmp`**：同机的其他会话会扫 `/tmp`（连进程带日志）。放 `~/.cache`。
6. **`cmd | tail` 吞掉退出码**；判"有没有命中"要 `grep -c` 并自带一个必然非 0 的阳性对照。
7. **mtime 新 ≠ 内容新**；部署语义读字节码（第 1 节）。
8. **本机 macOS 的 H2 计时器做不了性能结论**（200k 宽表全扫 0.03 ms），量具对这个问题是瞎的。
