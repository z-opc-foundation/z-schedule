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

### 1″. 常驻实例：挂在 250 上的那个进程

口径是"服务常驻 250"。常驻意味着**别人会拿它当"能用"的证据**，所以它的身份要写死在这里：

| 项 | 值（09-26 20:1x 实测，随每次换构件会变） | 怎么复现这个读数 |
|---|---|---|
| jar | `~/z-schedule-e2e/z-schedule-admin-svc-7c9de99-exec.jar`，md5 `f000e2f684b0fc769bf6bee6a85cbd78` | `md5sum z-schedule-admin-svc-*.jar`（**文件名不算证据**，见上一节） |
| 端口 | `18098`（**故意不用 18086**：那是 `p16/p20` 的性能台架端口，撞上就会量到一个"我没控制、不知道配置"的实例——坑 14） | `ss -ltnp \| grep 18098` |
| 存活判据 | `GET /` → 200；`GET /jobinfo/list` → 200 | `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18098/jobinfo/list` |
| 真跑一次 | `./svc_smoke.sh`（播种 → 数 `handle_code=200` → 停用） | 见第 3 节 |

换构件重跑的完整动作（`0b9ac0f` → `7c9de99` 就是这么走的，全程 25 s 内完成）：

```bash
# 1) 本机出构件并一对一拷过去（多源 scp 到同一目录会造出同名影子文件）
mvn -q -pl z-schedule-admin -am package -DskipTests
scp z-schedule-admin/target/z-schedule-admin-1.0.0-exec.jar 250:~/z-schedule-e2e/z-schedule-admin-svc-<sha>-exec.jar
#    两端 md5 必须逐字节相同；再用字节码确认修复在 jar 里（不只看文件名）
#    unzip -p <exec.jar> BOOT-INF/lib/z-schedule-spring-boot-starter*.jar → javap -c | grep setBroadcastTotal
# 2) 停旧：SIGTERM（会走 @PreDestroy ⇒ "Stepped down from LEADER"，新实例不必等 30 s 租约）
kill -TERM <pid>; 等到 ss -ltn 上 18098 空出来
# 3) 起新：setsid 脱离 ssh 会话，日志沿用同一个文件（进程持的是 inode）
setsid env JAR=z-schedule-admin-svc-<sha>-exec.jar PORT=18098 ./run.sh >logs/service_18098.out 2>&1 < /dev/null &
# 4) 只等因果那行：Started ZScheduleAdminApplication（单次 grep 会在 Tomcat 刚绑端口时误判，见坑 14）
```

> 从工作站一条命令做完 2)+3)：把上面两段包进 `ssh 250 'cd ~/z-schedule-e2e && …'`。
> 口令只走环境变量、绝不进 argv（第 2 节），`setsid` 是为了让它活过 ssh 会话结束。

**为什么"端口 200"不够**：常驻实例平时 ring 里 0 个任务，日志每 15 s 只打一条
`Engine loaded 0 jobs into ring`——那是 reconcile 在跑，不是"能执行任务"。
`svc_smoke.sh` 就是把"活着"定义成**一次真实执行把结论写回库**，并且走 `0b9ac0f` 修好的那条
`IJobHandler.execute(TriggerParam)` 派发支（此前引擎只找 `execute(String)`，接口版 handler 永远走不到，
而演示应用里连一个 handler bean 都没有 ⇒ 整条"派发 → 执行 → 结论"链在真机上从没被观察过）。
它默认跑完把演示任务**停用**而不是删掉：1 Hz 常驻写负载 ≈ 每天 52 万行日志，证明过一次就够，
但留行还能回答"上次冒烟是什么时候"。

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
| `p21.sh` | **一次执行的 2 条语句里钱花在哪**：同一批 id 上 narrow(4 列) / wide(12 列) 两臂判"形状"，pair2(2 次提交) / pair1(1 次提交) 两臂判"次数" | ③ 的前提：收窄 SET 到底值不值（答案：不值，见 §4.4） |
| `svc_smoke.sh` | **常驻实例现在还活着吗**：播种 3 个 2 s 任务 → 数 `handle_code=200` → 用 handler 自己那行日志做阳性对照 → 停用。两处基线（`job_log` 的 `MAX(id)` 与日志文件行数）把**上一次运行**的行排除在外——不加基线时实测过 `成功=72`，其中 42 行是历史 | 0b9ac0f 的 `IJobHandler` 派发支要在真机上被观察到；陈旧正对照/陈旧计数 |

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

### 4.2 那堵墙是连接池（p20，只改 `--z.base.db.schedule.max-active`）

| 池上限 | 需求 800 次/s 时 | 需求 1600 次/s 时 | 忙连接 峰值/均值（N=1600） |
|---|---|---|---|
| 20 | 316.1 次/s | 346.3 次/s（p16 的 1600 档） | — |
| 40 | 540.2 次/s | **545.5 次/s（达成 34.1 %）** | 40 / 31.1 |
| 80 | 808.1 次/s（= 需求 101 %） | **904.9 次/s（达成 56.6 %）** | 80 / 63.6 |

忙连接峰值**每次都正好等于池上限** ⇒ 池是被顶住的。N=800 那列在池 80 时已经把需求全部满足，
所以它量到的是需求；N=1600 那列才是**供给上限**本身（545 / 905）。

两条要更正的口径：

* **每翻倍只涨 1.6–1.7 倍**（20→40：316→545 = 1.72×；40→80：545→905 = 1.66×），两档需求下结论一致
  ⇒ 次线性成立。但"每条语句墙钟"这一列**只有在池真被占满时才可信**：它是拿**峰值**连接数除出来的，
  池 80 / N=800 那一格均值只有 47.0，峰值 80 是偶发 ⇒ 49.5 ms 是除以峰值的假影，别拿它当墙钟。
  按均值算（N=1600）：池 40 ⇒ 31.1/(545.5×2) = 28.5 ms，池 80 ⇒ 63.6/(904.9×2) = 35.1 ms。
* 拿 p15 那个孤立值 16.7 ms、按 2.05 条/次去算：20 连接 ÷ 16.7 ms ÷ 2.05 = **584 次/s**，
  而池 20 的**供给上限**实测是 346 次/s（N=800/1600 都停在 340±6）⇒ **高估 69 %**
  （反过来按"预测值的 41 % 没兑现"说也行，别把两种口径混着引）——
  **孤立测出来的提交成本不能直接代进并发模型**。

**这一层已经没有多少可抬的了**：这台容器 `max_connections=151`，池 80 的均值就占掉 63.6 条，
再翻倍要动服务端；而收益是 1.6× 而不是 2×。⇒ 下一层只能打**每次执行的语句数**（现在 2.00 条/次 =
1 插 + 1 改），见任务 #24。

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


### 4.4 剩下那 2 条/次：钱花在**形状**还是**提交次数**（p21，四臂同轮轮转）

抬池这条路在这台机器上快见底（`max_connections=151`），下一层只能打"每次执行 2 条语句"。
但"把 2 条压成 1 条"里藏着两个完全不同的假设，得先分清再决定改不改代码：

* **(a) 形状贵**：线上那条结论 UPDATE 是 12 列全量写回（`_q_digest.sql` 从 digest 取的原话：
  `SET job_group=?,job_id=?,executor_handler=?,executor_param=?,executor_fail_retry_count=?,
  trigger_time=?,trigger_code=?,trigger_msg=?,handle_code=?,handle_msg=?,alarm_status=? WHERE id=?`，
  765,531 次执行；对应 INSERT 10 列 711,260 次）⇒ 若形状贵，该收窄成 4 列；
* **(b) 次数贵**：每条 autocommit 各付一次 redo+binlog fsync ⇒ 若次数贵，收窄 SET 一点收益都没有，
  唯一有收益的是把两次提交并成一次。

| 臂 | 5 轮 min..max (ms) | 中位 ⇒ 单价 | 对照 |
|---|---|---|---|
| `narrow`（4 列 SET，200 条） | 5041 .. 6106 | **28.570 ms/条** | 每轮 `落库=200/200` |
| `wide`（12 列 SET，200 条，同一批 id） | 5442 .. 6197 | **30.105 ms/条** | 每轮 `落库=200/200` |
| `pair2`（INSERT+UPDATE = 2 次提交，app 现状） | 10854 .. 11894 | **56.115 ms/对** | 每轮 `落库=200/200` |
| `pair1`（同一对写放进一个事务 = 1 次提交） | 4137 .. 6172 | **30.230 ms/对** | 每轮 `落库=200/200` |

* **(a) 证伪**：wide 比 narrow 贵 5.4%，而两臂各自的 min..max 跨度就有 21% / 14% ⇒ 差值完全落在抖动里。
  **收窄 SET 列表无收益**，那条全量 UPDATE 不用改（它是 MyBatis-Plus `updateById` 的整体写回，
  想收窄得自己写 `UpdateWrapper`——纯增加代码，换不到钱）。
* **(b) 成立**：`pair2/pair1 = 1.86×`，即把每次执行的 2 次提交并成 1 次，**每次执行少持连接 46%**。
  这才是"2 条压成 1 条"真正值钱的形状。
* **绝对单价在分钟级漂**：同脚本第一次跑 narrow 得 18.185 ms/条，第二次得 28.570 ms/条（漂 +57%）。
  ⇒ 这台容器上**只有同轮内的臂间比值可用**，这正是四臂每轮轮转顺序、且必须同一次运行出结论的理由。
  （另注：单臂跨轮 min..max 有 20% 抖动，所以对照必须逐臂逐轮做——首轮我的对照把基准行和 pair
  新行混在一起数，打出 `400/200`，那条判据对 narrow/wide 是失效的，第二版按 `handle_msg` 前缀
  分臂 + 分 `job_group` 才真正拿到 20/20 的 `落库=200/200`。）

**为什么仍然不改代码（③ 的取舍，价格标签在这里）**：并提交要付的代价不是抽象的——
`JobLogServiceImpl.query()` 的 `handleCode=0` 那一支（`eq(handle_code, 0)`）**就是"进行中"日志视图**，
并把 INSERT 挪进事务后，这一支会**恒空**；崩溃时也再没有"开始执行"的痕迹可查。
⇒ 拿 1.86× 换一层用户可见能力，是产品决定不是性能决定。要真做，需要点头。
更便宜的替代路径（不改语义）：`trx_commit=2` 能省一半单价（见 §4.3 的 16.70 → 8.89 ms），
但那是**牺牲崩溃持久性**，同样要点头。

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
15. **一条播种 INSERT 就是一个 argv，Linux 把单个 argv 字符串限在 128 KB**：p20 用 `q -e "$SQL"` 传整条
    多行 INSERT，N=800（≈96 KB）能过、N=1600（≈192 KB）报 `Argument list too long` ⇒ 装载 0 个、
    吞吐 0.0、墙钟 `inf`；而 p16 用 heredoc（stdin）所以同一个 N 从来没挂过。**两把尺因为传法不同
    在一个需求量上不守恒**——已经改成 heredoc。好消息是脚本当时报的是 `!! 装载数≠1600，本档读数不可信`
    而不是编一个数出来。
16. **`mvn -q test` 把 surefire 的 "Tests run" 汇总一起静音了**：日志 9,671 行里 0 条汇总，退出码 0
    只说明没挂，不说明跑了多少例。数测试一律从 `*/surefire-reports/*.txt` 聚合（本次提交树
    `8b21da3`：22 份报告 / **250 例 / 0 失败 / 0 错 / 0 跳过**。这条数字会过期，以第 8 节为准）。
17. **库里的时间戳比宿主墙钟慢 8 小时，但两个写入者之间是一致的**：`docker exec z-schedule-e2e-mysql date`
    读的是 **UTC**，250 宿主是 CST（实测同一时刻 `12:18:51 UTC` / `20:18:51 CST`）。
    ⇒ 差 8 小时的是"库 vs 宿主"，不是"JVM vs MySQL"：脚本里 `NOW()` 种的 `add_time` 与
    JVM 写的 `trigger_time` 相减得到的是真秒数（实测首拍延迟 5 s，不是 −8 h）。
    别把 `serverTimezone=UTC` 当成因——它只是让 JVM 写的字面量和容器时钟对齐。
18. **直接写库种的任务，第一拍要等一次 reconcile（0–15 s），走 API 才立刻装载**：
    `JobScheduleEngine` 只在"启动/修改"时增量 `loadJob`，周期 `reloadJobs()` 才认 DB 里新出现的行。
    `svc_smoke.sh` 是裸 INSERT ⇒ 同一个脚本两次实测 `本次日志行=15` 与 `=36`（3 个 2 s 任务、25 s 窗口，
    满载应 36 ⇒ 第一次是插在周期尾巴上）。一旦开始，节拍严格 `2.0000 s`（`LAG(trigger_time)` 逐拍实测）。
    ⇒ 冒烟的计数只回答"有没有真执行"，**不要拿它当速率或达成率**。
19. **独立 admin 的端点在根路径、是 `/jobinfo/*` 这种风格，不是 `/api/schedule/*`**：
    照 z-opc 的模块前缀约定猜路径会拿到 404，而 404 很容易被读成"服务没起"。
    实测（18098）：`/`=200、`/jobinfo/list`=200、`/api/schedule/job/list`=404、`/schedule/`=404。
    宿主应用里才带前缀（`z-opc` 用 `--server.servlet.context-path=/meta`）。
    ⇒ 判存活要拿 200 且**形状对**的响应（坑见 `feedback-service-probe-status-code`）。
20. **注入脚本用 `shutil.copy2` 还原源码 = 把 mtime 一起倒回变异之前**：被变异编过的那份
    `.class` 反而比还原后的 `.java` 新，下一次 `mvn test`（不带 `clean`）**不重编它**，
    于是你测的是上一支变异的字节码。09-26 实测：8 支全 KILLED-exact 之后跑全量，
    `过滤器必须注册在整个应用入口上` 红在"两个不同的 store 实例"上——磁盘上的源码是对的，
    红的是 M8 留下的旧 class。⇒ 还原后必须 `os.utime(path, None)` 抬时间戳，
    或者干脆 `mvn clean test`。**"文件已按字节还原"和"下一次量的是还原后的字节"是两件事。**

## 7. 路由策略：广告与兑现的差（④ 的收口）

界面上"路由策略"给了 6 个选项（轮询 / 随机 / 一致性哈希 / LRU / 故障转移 / 分片广播），
后端 `z-schedule-core` 确实有 10 个 `ExecutorRouter` 实现。问题是**派发路径一次都没问过它们**。

取证（在本仓根目录跑，两条都是 0 命中才算成立）：

```bash
# (a) 全仓有没有人调用选节点的那个静态入口（含测试）
grep -rn "JobGroupServiceImpl\.route" . --include=\*.java | grep -c .        # 实测 0
# (b) 有没有任何"出站"能力（admin → 注册上来的执行器地址）
grep -rn "RestTemplate\|HttpClient\|openConnection\|HttpURLConnection" \
     z-schedule-core/src/main/java z-schedule-spring-boot-starter/src/main/java \
     z-schedule-admin/src/main/java | grep -c .                              # 实测 0
```

两条都得 0 才算成立（`| grep -c .` 而不是 `| head`：后者会把退出码换成 `head` 的 0，看不出有没有命中）。
实测读数（09-26 提交树）：`JobGroupServiceImpl.route(...)` 在全仓（含 `src/test`）**没有任何调用方**
——连按名字引用它的测试都没有；10 个 router 的 `route(List,int)` 只被 `JobGroupServiceImpl:71`
那一行转发和 `RouterTest` 调过。
⇒ **任务上的 `executor_route_strategy` 只是被存下来、被界面显示出来，不参与"谁执行"**：
`JobTriggerServiceImpl` 只从当前 JVM 的 `ApplicationContext` 取 bean。

已经通的那一半是**入站**：外部应用 POST `/executor/beat` → `register()` 写
`z_schedule_job_registry` 并把 `addressList` 聚回分组（`/jobgroup/registryList` 能看到节点）。
缺的是出站那一半——按地址把 `TriggerParam` POST 给被选中的节点。这条链一补，
`/executor/run`（记一行"已下发"并回 `logId`）+ `/executor/callback`（回写 `handle_*` 三列）
这两个现成的端点就是它的协议，所以缺口不是"不知道怎么做"，而是**没做**：
需要一个额外的决定（谁来鉴权、注册上来的地址能不能被内网任意 POST），因此不在性能这条线上顺手补。

本次收口做了三件事，让"广告"与"兑现"不再互相圆场：

1. **兑现侧钉住语义**：`JobTriggerServiceImplBehaviorTest.分片广播也只在本机执行一次且分片信息为唯一一片`
   —— 配 `SHARDING_BROADCAST` 的任务只执行 1 次，且 `TriggerParam.broadcastTotal=1/broadcastIndex=0`。
   `broadcastTotal` 此前是默认值 **0**，任何按分片写的 handler（`for (i=index; i<total; i+=total)`）
   会**一行都不做**；现在是"你是唯一那一片"这个真话。
   注入实测：删掉 `param.setBroadcastTotal(1)` ⇒ 该例红（`expected:<1> but was:<0>`），还原后 md5 逐字节一致。
2. **代码侧说清"没人调"**：`JobGroupServiceImpl.route()` 上加了说明（零生产调用方、缺出站那一半、
   改之前先读本节）。10 个 router 是已发布 API（1.0.4 在 Central），**不删**。
3. **界面侧不再暗示**：`z-opc` 的 `schedule/pages/JobList` 里该表单项加了 `extra`
   —— "当前版本任务固定在本 JVM 内执行，此选项不改变执行节点（多执行器远程派发尚未实现）"。
   真要实现远程派发，需要点头的是这三处一起改（出站调用 + `broadcastTotal` 按节点数算 + 界面去掉那句话）。

## 8. 测试基线（每次改完重跑，别引用历史值）

```bash
cd <repo>/z-schedule && rm -rf */target/surefire-reports && mvn test
# 计数只吃报告文件，不吃 mvn 的 stdout（坑 16）；输入为空必须 FATAL
```

09-26 提交树（#19 的登录态那一格之后）`mvn clean test` 实测：**24 份报告 / 278 例 / 0 失败 / 0 错 / 0 跳过**，
`BUILD SUCCESS`（core 45 + starter 233）。上一格是 23 份 / 259 例，多出来的一报告是
`LoginSessionStoreTest`（9 例），`TokenAuthFilterTest` 从 14 涨到 20、`UserServiceImplH2Test` 从 18 涨到 22。
其中 `JobTriggerServiceImplBehaviorTest` 15 例（含钉住分片广播语义的那 1 例）、
`ZSchedulePoolDefaultTest` 4 例（② 的池默认）。
先 `rm -rf surefire-reports` 再数：不清会把你**本轮没跑到的**类的旧报告一起加进来（历史上报出过 +1 类）。
**跑过注入脚本之后必须 `mvn clean`**：还原只写回字节不改 mtime，增量编译会接着用上变异体的 class（坑 20）。

## 9. 登录态与角色：这一格兑现到哪一步（#19）

改之前要知道的现状，全部由代码 + 测试钉住，不靠这段话：

| 事实 | 落在哪里 | 谁来红 |
|---|---|---|
| `/user/login` 换回的是一串 256 bit 不透明令牌，**不再是用户名** | `UserServiceImpl.login` → `LoginSessionStore.issue` | `登录用明文密码比对库里的散列`（M3 摘掉即红） |
| 令牌解析回 `{userId, username, role}`，挂成请求属性 `z.schedule.loginSession` | `TokenAuthFilter.doFilter` | `会话身份挂进请求而共享密钥不挂`（M2） |
| 建/改/删账号三个口只认 ADMIN 会话；普通会话 403 且理由写"需要管理员角色" | `TokenAuthFilter.ADMIN_ONLY_PATHS` | `普通会话改不了账号而管理员会话能`（M1） |
| 会话 30 min 过期、上限 1000 条按"最久没被出示"逐出 | `LoginSessionStore` | `过期令牌解析为空并且不再占位`（M6）、`容量满时逐出最久没被出示的那条`（M7） |
| 改角色 / 删账号 ⇒ 该用户的会话立刻全部作废 | `UserServiceImpl.update/delete` | `改角色会把该用户的旧会话踢下线`（M4）、`删账号会把他的会话一并撤销`（M5） |
| `/user/logout` 幂等：不回答"这把令牌先前在不在用" | `UserController.logout` | `令牌能过过滤器而登出后过不了` |
| 过滤器与签发方共用**同一个** store 实例 | `ZScheduleAutoConfiguration.tokenAuthFilterRegistration` | `过滤器必须注册在整个应用入口上`（M8，就是坑 20 那条红） |

八支注入的读数：`8/8 KILLED-exact`（每支都只红在预期的那条具名判据上，还原后 md5 逐支对账）。

**这一格没有做完的部分，别当成已经生效**：

1. `permission` 列（逗号分隔的 jobGroup id）仍然**零读者**。`LoginSession` 刻意不带它——
   一个没人读的字段就是第二个装饰。按 jobGroup 收口是独立的一格。
2. 共享密钥 `z.schedule.accessToken` 不区分"是谁"，因此**不受角色闸约束**（它本来就是全权）。
   未配置它的演示模式下管理面对匿名敞开，但**出示了的**普通会话照样被拦（`演示模式下匿名全开但出示的会话仍被限角色`）。
3. 会话只在本进程内存里：重启即全员重新登录，多实例部署下 A 机签的令牌在 B 机验不过。
   要跨实例得注册一个自己的 `LoginSessionStore`（那个 `@ConditionalOnMissingBean` 就是留给这事的口子）。
4. 读侧（`/user/list`、`/jobinfo/*`、`/joblog/*`）暂不按角色收口。
   界面侧还没有登录入口——z-opc 的 schedule 页面从来没调过 `/user/login`（实测该目录 `login|accessToken` 零命中），
   所以这一格交付的是**后端的身份载体**，不是"用户能登录"这件事。
