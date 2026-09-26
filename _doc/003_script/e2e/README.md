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
| `p19.sh` | **同一时刻有几条连接在忙**（忙=`COMMAND='Query'`）＋ STATE 直方图；自带 `preytest`：埋 6 条并发 `SELECT SLEEP(4)`，尺数不到 6 就 FATAL | 把"连接数"这个量从猜测变成读数 |
| `p20.sh` | **固定需求只改池上限**（`max-active` 20/40/80），同时量吞吐与忙连接 ⇒ 池是不是那堵墙 | 天花板归属（见第 4 节，答案是"是"） |

## 4. 天花板到底压在哪一层

### 4.1 三级需求阶梯（同一把尺 p16，窗口 30 s，`trx_commit=1`，`max-active=20`）

| N（1 Hz 任务数） | A 组达成率 | 实测吞吐 | B 组吞吐 | 语句/次 | 日志字节/次 |
|---|---|---|---|---|---|
| 400 | 77.5 % | 310.1 次/s | 345.0 次/s | 1.99 | 155 B |
| 800 | 43.3 % | 346.3 次/s | 342.3 次/s | 2.00 | 156 B |
| 1600 | 21.6 % | 346.3 次/s | 338.7 次/s | 2.00 | 155 B |

**需求翻四倍，吞吐纹丝不动** ⇒ 之前那句"第三档 408 次/s 已经是需求没打满，要再看天花板得加到
N=800/1600"是**错的**：加上去之后读数仍是 340±6 次/s，这是一条**供给侧平线**，不是需求侧欠账。
（另一条同时显形的结论：写放大修复之后 A/B 两组的"打不打堆栈"差异已经归零——B 组的
带堆栈 ERROR 恒为 0，p16 那两把梯子的第二条从此只是重复第一条。）

### 4.2 那堵墙是连接池（p20，固定 N=800，只改 `--z.base.db.schedule.max-active`）

| 池上限 | 吞吐 | 忙连接 峰值/均值 | 反推每条语句墙钟 |
|---|---|---|---|
| 20 | 316.1 次/s | 20 / 15.4 | 31.6 ms |
| 40 | 540.2 次/s（**+71 %**） | 40 / 30.0 | 37.0 ms |
| 80 | **808.1 次/s = 需求的 101 %** | 80 / 47.0 | 49.5 ms |

忙连接峰值**每次都正好等于池上限** ⇒ 池是被顶住的；放到 80 时 800 次/s 的需求已经全部满足，
天花板又回到需求侧。

于是原来的公式要改口径：**`上限 = 连接数 ÷ 每条语句墙钟 ÷ 每次执行语句数` 里的"墙钟"不是常数**——
它随并发变长（31.6 → 49.5 ms，因为提交在组提交队列上排队），所以吞吐随连接数**次线性**增长
（2×→1.71×，再 2×→1.50×）。拿 p15 那个孤立值 16.7 ms 去乘 20 条连接会得到 584 次/s，
比实测高 42 %——**孤立测出来的提交成本不能直接代进并发模型**。

复跑（`application.yml` 把默认值改成 40 之后，同一个 jar md5 `270625b5…`，`SIZES=800 W=30`）：

| 池 | A 组 | B 组 | 忙连接峰值 |
|---|---|---|---|
| `Z_SCHEDULE_DB_MAX_ACTIVE=20`（对照） | 318.2 次/s | 340.7 次/s | **正好 20** |
| yml 默认 40 | 501.6 次/s | 549.5 次/s | **正好 40** |

两档都落在 p20 那条曲线内（316 / 540），**峰值随配置搬家**说明 env 覆盖真的进了 JVM——
判"改没改生效"不靠日志里的配置回显，靠这个结构性读数。

### 4.3 服务端设定与它之外的余量

| 状态 | 吞吐 | 语句/次 | 单次提交 |
|---|---|---|---|
| 已发布 1.0.4 字节 | 297.4 次/s | 3.07 | 16.70 ms |
| + 写放大修复（3.07 → 2.00 条/次） | 336.7 次/s | 2.05 | 16.70 ms |
| + `trx_commit=2`（`sync_binlog` 仍 1）、池仍是 20 | 408.6 次/s | 2.00 | 8.89 ms |
| + 池放到 80、`trx_commit=1`（durability 不降级） | **808.1 次/s** | 2.00 | 墙钟 49.5 ms |

* **抬池比降 durability 划算**：不牺牲崩溃丢日志的语义就能拿到 2 倍以上吞吐；
  只调 `trx_commit=2` 不能翻倍（余下的墙钟是 `sync_binlog=1` 那次 binlog fsync）。
* **但抬池不是 starter 该替宿主做的决定**：默认 `max-active=20` 在 `z-boot` 的
  `ModuleDataSourceTemplate:30`，而 MySQL 侧的 `max_connections` 是被所有共用这套库的服务分摊的。
  这台 250 容器实测 `max_connections=151`（09-26 18:0x 回读，同一条里 `trx_commit=1`/`sync_binlog=1`
  确认已还原）⇒ 单实例 80 条能装，两个调度实例同时抬到 80 就要撞库侧上限，先量服务端再抬客户端。
  ⇒ 已验证的口径是"**告诉运维：这个量级下把 `z.base.db.schedule.max-active` 抬到 40/80，
  并确认 MySQL `max_connections` 有余量**"，改默认值等用户点头。
* 服务端 `performance_schema` 的语句计时器**结构上看不见提交等待**（只报 10–25 µs 的服务端 CPU 时间），
  墙钟成本在 `information_schema.processlist` 的 `waiting for handler commit` 里——p19 全程直方图
  5,976 / 7,000 忙连接·次都落在这一个状态上。
  ⇒ 用 µs 级读数判"落库不是问题"是错的（我今天就这样误判过一次，把修复贬成"值 8.5 µs"）。
* 堆栈日志的代价是**体积**而不是耗时：修复前 1,767–1,792 B/次（≈11 倍），N=400 时 30 s 写 20 MB。


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
9. **`p15`–`p20` 以前裸调 `./run.sh` ⇒ 静默用了默认构件**（`z-schedule-admin-1.0.0-exec.jar`，在本机上
   它是写放大修复**之前**那一版）。现在这四个脚本都要求显式 `JAR=`，性能读数才有构件身份可对 md5。
10. **`pkill -f "p20.sh"` 会杀掉我自己**：同条 ssh 命令的 argv 里就含有这个字符串（我栽了两次，
    现象是 ssh 返回 255 而远端什么都没做完）。要么用字符类打断（`[p]20[.]sh`，且**整条命令里别出现
    裸文件名**），要么分两次调用。
11. **250 的 awk 是 mawk**：`strftime()` 不存在；多一个右括号是 `awk: extra ')'` 而不是"整段不打印"，
    所以聚合逻辑要单独喂假数据自测一次（p19 的 `preytest`、p20 的 awk 块都是这么验的）。
12. **`mysql` 的位置参数是库名不是 SQL**：把语句直接当参数传给它会报 `Unknown column` 或静默不执行；
    本目录一律 `-e` 或 heredoc。
13. **bash 双引号里的 `\$i` 是字面 `$i`**，会带着进 SQL 变成列名（p20 第一次跑就是这么挂的）；
    想要 shell 展开就别加反斜杠。
14. **端口被上一个陌生实例占住时，阶梯照样出数**：我手工起的实例占着 18086，`p16.sh` 自己那个在 bind
    处死掉，而 `curl /` 返回 200（是那个陌生实例答的）、`job_log` 照样在涨、`app.pid` 只记自己起的进程
    ——于是整条阶梯量的是一个我**没控制、不知道配置**的实例，读数还印着 `!! 装载数≠800`。
    `p16.sh` 现在只认自己日志里的 `Started ZScheduleAdminApplication`（**等**这行而不是单次 grep：Tomcat
    在 refresh 中途就绑了端口，单次 grep 会把健康实例误判成 FATAL）。反向实测：让 decoy 占住端口后跑
    p16 ⇒ `already-in-use 行数=2`、**0 个台阶执行**、退出码 1。取退出码要 `>file` 再看 `$?`，
    `./p16.sh | tail` 拿到的是 tail 的 0（坑 6 本人）。
