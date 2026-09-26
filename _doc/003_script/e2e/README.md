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

| 项 | 值（09-27 00:42 实测，随每次换构件会变） | 怎么复现这个读数 |
|---|---|---|
| jar | `~/z-schedule-e2e/z-schedule-admin-svc-7ea14eb-exec.jar`，md5 `90ead3a693034dbb60b49960a1e701f3`，56,234,848 B（含 #19 的登录态 + #28 的铸权闸 + `/jobinfo/*`、`/joblog/*` 两套按组收口 + 逐组合并 + **§10 那段顶层 `management.*` 探针配置**；`a16473a`（`100082780a…`）与 `44acc07`/`baa4458`/`0f1248b`/`7c9de99` 等旧版仍在同目录，别拿文件名当版本）。**编号从这一版起取 HEAD**：原先的口径是"最后一次改动 starter 源码的提交"（`git log -1 --format=%h -- z-schedule-spring-boot-starter/src/main/java`），那个号自 `a16473a` 起就没再动，而这一格改的是 **admin 的 `application.yml`** ⇒ 旧口径下两版会撞名。判据永远是 md5 ＋ **从 jar 里读出的字节**：这次不是 `javap` 而是 `unzip -p <jar> BOOT-INF/classes/application.yml \| grep -c probes`（旧构件 0、新构件 1，一条负向一条正向） | `md5sum z-schedule-admin-svc-*.jar`（**文件名不算证据**，见上一节） |
| 端口 | `18098`（**故意不用 18086**：那是 `p16/p20` 的性能台架端口，撞上就会量到一个"我没控制、不知道配置"的实例——坑 14） | `ss -ltnp \| grep 18098` |
| 存活判据 | `GET /` → 200；`GET /jobinfo/list` → 200；`GET /joblog/list` → 200；`GET /actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}`（body 里没有 `components`，明细不外铺）；`/actuator/health/liveness`、`/actuator/health/readiness` → 200。**换构件前同一台同一端口这两条都是 404**（00:40:01 实测），所以它们是这一格的读数不是背景 | `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18098/joblog/list` |
| 真跑一次 | `./svc_smoke.sh`（播种 → 数 `handle_code=200` → 停用） | 见第 3 节 |

换构件重跑的完整动作（`0b9ac0f` → `7c9de99`、`44acc07` → `a16473a` 都是这么走的；
"全程多少秒"这句原先写的是 25 s，那是估的、没有任何尺量过整段，已删——分段的两步有实测：
端口 1 s 内空出来、新进程 6.3–6.7 s 到 `Started`，剩下的都是 `md5sum`/`javap` 这类一次性核对）：

本轮两条实测：旧进程 `kill -TERM` 后 **1 s** 端口空出来、`logs/service_18098.out` 里 `Stepped down`
共 3 行（#18 那格的兑现面每次换构件都会被重打一遍）；新进程从 `setsid` 到
`Started ZScheduleAdminApplication` **6.3–6.7 s**（原先这里写的是"约 20 s"——那是一个没有任何尺会读的数，
七次真启动的回读把它打掉了：`grep -ao "Started ZScheduleAdminApplication in [0-9.]* seconds" logs/service_18098*.out*`
⇒ 6.317 / 6.458 / 6.499 / 6.501 / 6.61 / 6.693，**外加一次 12.745**：那一回是紧接三轮 p20（库里刚灌完
1,600 个任务、12.9 MB 日志）之后起的 ⇒ 启动耗时在这台机器上会翻倍，写"6.5 s"要连着写它的条件）。**日志本轮改成一构件一文件**（`service_18098_a16473a.out`）：
沿用同一个文件时，"等因果那行"的 `grep -q` 会先命中上一版的启动行，把一次没起来的实例读成起来了。

09-27 00:40 第三次走这四步（`a16473a` → `7ea14eb`，就是 §10 那格换构件）：`kill -TERM` 后**第一次采样端口就已无人监听**
（循环步长 0.5 s，所以读数只能说 "<0.5 s"，别写成"1 s"——那是上一轮的读数不是这一轮的），
旧日志 `Stepped down from LEADER` 1 行；新进程 `Started ZScheduleAdminApplication in 6.865 seconds`
（第 8 个读数，仍落在上面那条区间内），`Became LEADER` 在 Started 之后 **61 ms**
（00:40:45.551 → 00:40:45.612）⇒ 等旧租约的时间依旧是 0。换完当场复验身份：`/proc/<pid>/cmdline` 里
是 `z-schedule-admin-svc-7ea14eb-exec.jar`、`md5sum` 与本机逐字节相同、`deploy: JAR=… db=127.0.0.1:33060/zschedule_e2e`
那行连的库没变。**换构件后 `./svc_smoke.sh` 与 `./p23.sh` 各跑一遍**（前者证"还会真执行任务"，后者证探针面），
读数记在 §10。

```bash
# 1) 本机出构件并一对一拷过去（多源 scp 到同一目录会造出同名影子文件）
mvn -q -pl z-schedule-admin -am package -DskipTests
scp z-schedule-admin/target/z-schedule-admin-1.0.0-exec.jar 250:~/z-schedule-e2e/z-schedule-admin-svc-<sha>-exec.jar
#    两端 md5 必须逐字节相同；再用字节码确认修复在 jar 里（不只看文件名）
#    unzip -p <exec.jar> BOOT-INF/lib/z-schedule-spring-boot-starter*.jar → javap -c | grep setBroadcastTotal
# 2) 停旧：SIGTERM（会走 @PreDestroy ⇒ "Stepped down from LEADER"，新实例不必等 30 s 租约）
#    pid 只从端口取——~/z-schedule-e2e/app.pid 是历史遗留，run.sh 从不写它（坑 21）
PID=$(ss -ltnp | grep ':18098' | sed -n 's/.*pid=\([0-9]*\).*/\1/p'); kill -TERM $PID
#    等到 ss -ltn 上 18098 空出来（实测 1 s）
# 3) 起新：setsid 脱离 ssh 会话，日志沿用同一个文件（进程持的是 inode）
setsid env JAR=z-schedule-admin-svc-<sha>-exec.jar PORT=18098 ./run.sh >logs/service_18098.out 2>&1 < /dev/null &
# 4) 只等因果那行：Started ZScheduleAdminApplication（单次 grep 会在 Tomcat 刚绑端口时误判，见坑 14）
```

> 从工作站一条命令做完 2)+3)：把上面两段包进 `ssh 250 'cd ~/z-schedule-e2e && …'`。
> 口令只走环境变量、绝不进 argv（第 2 节），`setsid` 是为了让它活过 ssh 会话结束。

起一台**关了门**的实例（`p22.sh` 的 B 段就是这么起的）只需要多一个环境变量：

```bash
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
setsid env Z_SCHEDULE_ACCESSTOKEN="$SECRET" JAR="$JAR" PORT="$PORT" ./run.sh >logs/x.out 2>&1 </dev/null &
```

`Z_SCHEDULE_ACCESSTOKEN` 能喂进 `z.schedule.accessToken`，靠的是 Spring 的 relaxed binding
比较属性名时去掉 `-` 并统一大小写（`access-token` 与 `accesstoken` 的 uniform 形式相同）。
**别用 `run.sh` 的 `ACCESS_TOKEN=`**：它把值拼成 `--z.schedule.access-token=<值>`，
于是同机任何人 `ps` 一下就拿到口令（实测条数：`pgrep -af '[j]ava' | grep -c 'access-token='`）。

**"第一个管理员从哪来"（#28 之后改了答案，部署前必须先读这条）**：`/user/add` 的 `role` 是请求体自填的，
#28 给"把账号变成 ADMIN"这一步加了一道闸——只有 **ADMIN 会话**或**共享密钥放行的请求**铸得出来。
后果是演示模式（没配 `accessToken`）下**任何人都铸不出第一个管理员**，两条路可选：

```bash
# 路 1（p22.sh 的 S 段实测走的这条）：配了 accessToken 的实例上，用密钥铸
curl -s -X POST "http://127.0.0.1:$PORT/user/add?accessToken=$SECRET" \
  -H 'Content-Type: application/json' -d '{"username":"root","password":"***","role":"ADMIN"}'
# 路 2：直接写库。password 列存的是**无盐 MD5 的 32 位十六进制**（见 §9′ 的 S.9 读数），
#       照抄这个形状才能登录：echo -n "$PW" | md5sum
```

09-26 22:08 换构件（`0f1248b` → `44acc07`）实测的时间线，`logs/service_18098.out` 的行号都是真的：
22:08:20.433 旧进程打出 `Stepped down from LEADER`（第 404 行）并退出，**1 s** 后端口空出来；
`setsid … run.sh` 的标记行在第 409 行，`Started ZScheduleAdminApplication in 6.499 seconds` 在第 447 行
（22:08:37.707），第 448 行 `Became LEADER: instanceId=3f63264d…`（22:08:37.756）——
**启动后 49 ms 就拿到租约**，全程 kill→LEADER ≈17.3 s——这 17.3 s 全是新进程的启动开销
（`Started … (JVM running for 7.554)`），**等旧租约的时间是 0**（这就是 #18 的兑现面：不释放的话这里要白等 30 s）。
两次换构件的读数一致（上一次 21:35 那轮是 6.61 s / 47 ms）。
判据的两条纪律：**"抢到 LEADER"要按 `deploy: JAR=` 那行划窗口再 grep**（坑 24——这份日志是追加的，
整文件 grep 会命中上一次启动）；而且日志里的字面量是 `Became LEADER` / `Stepped down from LEADER`，
拿 `stepDown` 或 `BECOME LEADER` 去 grep 会命中 0 条从而误判成"#18 没生效"。

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
| `p22.sh` | **登录态 + 铸权闸 + 按组分权在真机上兑现到哪一步**：S 段先起一台配了 `accessToken` 的关门实例（`Z_SCHEDULE_ACCESSTOKEN` 走环境变量、argv 0 命中）并用共享密钥种下第一个 ADMIN，A 段打常驻那台（演示模式：签发形状 / 角色闸 / **匿名铸 ADMIN 被拒**＋NORMAL 阳性对照），B 段回到关门实例逐条验三种撤销，**D 段在关门实例上验 `permission` 那一列真的参与判定**（建两个分组 + 两行任务 ⇒ 列表裁剪、点名要别组被拒、读/触发/停/启/删五支越组被拒且**库里那一行没被写过**、ADMIN 不受约束、改 `permission` 会踢掉旧会话），**E 段把同一套收口打到日志那一侧**（`job_log.job_group` 是另一张表：派发行的组号由写侧兑现、`/joblog/*` 四口都认行上那一组、不限组时逐组合并重排并与 MySQL 现算的期望序对拍、`clear` 的"没删"配一支"会删"的猎物、`limit` 远超上限时收窄到 1000），收尾数库、验租约 | #19、#28（见 §9′；两台是必需的——撤销在演示模式下观察不到，而种子账号在演示模式下已经铸不出来） |
| `p23.sh` | **运维探针面在真机上兑现到哪一步**（三臂 19 条，全程只起**临时实例**：常驻那台是共享的，不能拿它做破坏性实验）。待验构件取自常驻进程的 `/proc/<pid>/cmdline`（不认文件名）。**1) 健康臂**：`health` 200 且 body 含 `groups[liveness,readiness]`、`info` 200、两条组路径各 200、`env`/`metrics` 404（暴露面白名单的反向对照）、匿名拿不到组件明细。**2) 只坏 Spring 池**（`--spring.datasource.url` → 没人听的 33999）：`health` 503 / `readiness` 503 / `liveness` 仍 200 且明细里没有 `db`；收尾 2.6 要求同一故障下两组**返回不同码**——相等就意味着要么没造出故障、要么两组同形。**3) 只坏引擎池**（`--z.base.db.schedule.port` → 33999，也就是调度本体那一侧）：3.2 先证那条 override 真的进了 argv（防被引号吃掉），3.3 判 `readiness` 503，3.4 判 body 里 `dataSourceSchedule=DOWN` 而 `dataSource=UP`（两池分开倒，red 才说明得了是什么），3.5 判 `liveness` 仍 200 | #29（见 §10：键位失配 + Boot 自动 `readiness` 不含数据源；2.6 与 3.2 存在的意义都是"本档不许在从未红过的情况下绿"） |
| `run_p20_and_restore.sh` | **带复原义务的测量包装**：p20 必须在常驻实例停掉时跑（一台库只有一个 leader），而"停了忘了起"是这类脚本最容易犯的错 ⇒ 关停、量、起回、复验写死在同一条脚本里，中途任一步失败也往下走到复原段 | 服务挂在 250 上不是为这次测量服务的（见 §4 开头那段） |
| `svc_smoke.sh` | **常驻实例现在还活着吗**：播种 3 个 2 s 任务 → 数 `handle_code=200` → 用 handler 自己那行日志做阳性对照 → 停用。两处基线（`job_log` 的 `MAX(id)` 与日志文件的行号）把**上一次运行**的行排除在外——不加基线时实测过 `成功=72`，其中 42 行是历史；日志落点现在从 `/proc/<pid>/fd/1` 取而不是拼路径（拼死的那版在换构件后把自己判红了，见 §10.4） | 0b9ac0f 的 `IJobHandler` 派发支要在真机上被观察到；陈旧正对照/陈旧计数 |

## 4. 天花板到底压在哪一层

> **这一节的构件身份，以及"能不能就地重跑"**（09-26 23:4x 复查）：下面三张表的吞吐数是 jar md5
> `270625b5…` 上量的，而常驻那台现在是 `a16473a`（`100082780a…`）。**结构那一半已确认跟到了现在跑的字节**：
> 从常驻 jar 里 `unzip -p` 出嵌套 starter（`9fb0ee0e…`），`javap -c` 的 `finishFailure` 里
> `JobLogService.update` 仍有两处调用，而第二处在 `log.getAlarmStatus() != statusBeforeAlarm` 的判断里
> ⇒ 本节反复引用的"2.00 条/次 = 1 插 + 1 改"量的不是旧构件。这一条在 H2 套件里是**成对**钉住的
> （`告警没有改写状态时不得再发第二条UPDATE` 与 `告警改写了状态就必须把那一条补上`）：把两条 UPDATE 一起删掉，
> 后一支必红——所以"字节里有那个判断"和"判断真的参与判定"两边都有尺。
>
> **耗时那一半不能就地重跑**：`z_schedule_job_leader` 全库只有 1 行 ⇒ 一台库只允许一个节点装载调度环。
> 常驻实例活着的时候另起一台，它整轮都是 follower、环是空的（p16/p20 会自己打 `!! 装载数≠N，本档读数不可信`，
> p15 只把 `ring:` 那行原样打出来，得自己看）。⇒ 重跑 §4.1–4.3 的正当顺序是：**先优雅停掉常驻实例**
> （#18 补的 `stepDown()` 让租约当场释放，不用干等旧租约 30 s，这一格由此从"要不要做"变成"值多少"），
> 跑完立刻启回来并复验 `/joblog/list` → 200。这是一次**有计划的服务中断**，不是能顺手挂后台的事。
> 09-26 23:51 与 23:53–23:58 用脚本走了两遍这个顺序（先一遍单档、专门验"起不起来得回来"，
> 再一遍完整三档），停/量/起回写在同一条 `run_p20_and_restore.sh` 里，量完的数在 §4.2 那一小节。
> 更早的 23:46–23:49 那一次是**手写的顺序**：那次不仅白中断了服务一分钟，还当场产出一个假读数
> ——"租约 ≤0.2 s 释放"来自一句 `IFNULL(MAX(expire_time)<NOW(),1)`，而 `stepDown()` 是把这一行的
> `owner/host/expire_time` **置 NULL、不删行**（`COUNT(*)` 恒为 1），所以那个"1"到底是"已释放"还是
> "默认成立"，句式上分不开 ⇒ 复原段改的是判据（`IFNULL(SUM(expire_time>NOW()),0)=0`），
> 真实释放用时只报上界（≤150/174 ms，含每轮 `docker exec` + mysql 往返），精确值归 p13 那把尺。
> 另一条同因的坑：p15 的 `wipe()` 带 `UPDATE z_schedule_job_info SET trigger_status=0 WHERE job_group<>90015`
> ——它会把**别人**已启动的任务一并停掉，是四把性能尺里唯一跨组改状态的那把（p16/p18/p20 只删自己那一组）。

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

#### 在现在跑着的那个构件上重打了一遍（`a16473a`，09-26 23:53–23:56，`N=1600 W=30`）

跑法是 `run_p20_and_restore.sh`（它负责把常驻实例停掉、量完再起来并复验，见 §1″ 与第 3 节）：

| 池上限 | `a16473a` 本轮 | `270625b5` 旧轮 | 差 | 忙连接 峰值/均值 |
|---|---|---|---|---|
| 20 | 323.2 次/s（达成 20.2 %） | 346.3 | −6.7 % | 20 / 15.2 |
| 40 | 543.1 次/s（33.9 %） | 545.5 | −0.4 % | 40 / 31.1 |
| 80 | 834.6 次/s（52.2 %） | 904.9 | −7.8 % | 80 / 54.1 |

⇒ 那条结论跟着构件过来了，而不是靠旧构件才成立：**三档忙连接峰值都正好等于池上限**，
翻倍收益 1.68× / 1.54×（旧轮 1.72× / 1.66×）⇒ 仍然次线性。绝对吞吐比旧轮低 0.4–7.8 %，
**这句话不许写成"新构件变慢了"**：同一台容器的分钟级漂移实测就有 ±20 %（§4.4 那把尺量到 57 %），
要证成回退必须同轮双臂，本轮没有那个形状。

顺带抓到两支量具的错，都改在脚本里了：

* arm 3 打印了 `!! 装载数≠1600，本档读数不可信`，**而这条判决是尺自己的错**。它靠
  `grep -o "Engine loaded …" | tail -1` 取装载行；grep 3.1 判"是不是二进制"是**按它这次读到的那一块**决定的，
  日志正被应用续写时这个判定会漂——一旦判成二进制，它只回一行 `Binary file … matches`，
  `tail -1` 里就永远不含 `loaded 1600 jobs` ⇒ 一次好读数被判成不可信。
  机制实测（09-27 00:1x，250 上合成日志，一次塞一个 NUL）：**只有和 NUL 落进同一个 32 KiB 读块的匹配会丢**——
  目标行放在干净块里，不带 `-a` 照样命中（我第一遍探针就是这样，没复现出来）；把目标行挪进脏块，
  不带 `-a` 只回一行 `Binary file probe_nul.log matches`，带 `-a` 回 2 条真匹配。
  这也解释了为什么闭着的日志复跑多少次都不红：NUL 在哪一块取决于当时写入的切分，事后那份文件的块边界已经不是那个了。
  ⇒ p13/p14/p15/p16/p17/p18/p20/p22/复原包装里**凡是读日志文件的 grep 一律带 `-a`**（`javap`/`unzip -l` 走管道的那些不算，它们读的不是被续写的文件）。
* "这一档可不可信"其实有一条**不依赖任何 grep** 的算法：达成率 = 实得 ÷ (任务数 × 窗口)，
  1 Hz 的任务不可能超过 100 %。三档是 20.2 % / 33.9 % / 52.2 %，全部 ≤100 % 且随池上限单调升
  ⇒ 环里就是 1600 个，arm 3 的 834.6 次/s 作数。（p20 本来就把达成率打出来了，只是我原先没把它当前置检查用。）

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
21. **`~/z-schedule-e2e/app.pid` 是历史遗留，`run.sh` 从来不通它**：09-26 换构件时
    `kill -TERM $(cat app.pid)` 报 `No such process`，而 `ss -ltnp` 明明白白写着 18098 上是 pid 6208
    （文件里那个数对不上）。⇒ 停实例只从端口取 pid。这比"脚本不好用"更糟的地方是
    **pid 号会被复用**：拿着一个过期 pid 去 kill，杀掉的可能是毫无关系的进程。
22. **一条 URL 里放两个 `?`，第二个会被并进第一个参数的值**：`/user/remove?id=11?accessToken=xxx`
    的 id 成了字符串 `11?accessToken=xxx`，于是"我删了探针账号"其实是**没删**——旧构件那次控制组
    跑完库里就剩了 1 行，而 HTTP 层完全看不出异常。⇒ 多参数一律 `&`；判"清干净"要 `SELECT COUNT(*)`，
    不能拿"接口回了 200"当清理完成（同第 5 节"200 不算证据"）。
23. **"被拒"有两层，两层的 HTTP 状态码不一样**：过滤器的闸（角色、token）回 **403** + `{"code":403,"msg":…}`，
    controller 里的铸权闸回 **HTTP 200** + `{"code":500,"msg":"创建或提升为 ADMIN 需要…"}`（`ReturnT` 就是这么设计的）。
    所以 `p22.sh` 的 `A.1` 是"200 但 code 500"，`S.10` 才是 403——写判据时只看状态码会把两道闸混成一个，
    而 `req` 助手之所以坚持"状态码 + body 一起返回"（坑 19）就是为了这里能分辨。
    同理：任何只做 `curl -o /dev/null -w '%{http_code}'` 的探针对这一整格都是**色盲**的。
24. **往一个不截断的日志文件里 `grep Started` = 会命中上一次启动**：`run.sh` 是 `>>` 追加，
    换构件后"等启动完成"的轮询第二次就在那份旧文件里匹配到了**上一次启动**留下的
    `Started ZScheduleAdminApplication in … seconds`，于是报出"启动耗时=1.0 s"（本次真值 6.499 s）。
    界面上一切正常，只是把一个慢启动量成了秒起。⇒ 要么每次启停换一个新日志文件名，
    要么从**本次运行**写下的那行标记（`deploy: JAR=…`）往后 `awk` 再找，别对整文件 grep。
    这条和 `svc_smoke.sh` 那行讲的"陈旧正对照/陈旧计数"（第 3 节）是同一类错误：
    **日志在增长，而判据没有划窗口**。

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

09-27 提交树 `7ea14eb`（§10 那一格）`mvn -o clean package` 全量实测：
**28 份报告 / 320 例 / 0 失败 / 0 错 / 0 跳过**（core 5 份 45 + starter 22 份 269 + **admin 1 份 6**）。
314 → 320 恰好是新增的 `ManagementConfigBindingTest` 那 6 例，而 `z-schedule-admin/target/surefire-reports/`
里那份报告是**这个模块的第一份**——在此之前 admin 一条测试都没有，所有测试都住在 starter 里
（这也是为什么 §10.1 那条键位缺陷能安静存活：admin 的 yml 从来没有被判过）。

09-26 提交树 `04326ac`（`/joblog/*` 四口按组收口 + 逐组合并落地之后）`mvn test` 实测：
**27 份报告 / 314 例 / 0 失败 / 0 错 / 0 跳过**（core 5 份 45 例 + starter 22 份 269 例）。
上一格是 `44acc07` 的 26 份 / 297 例（再往前 25 份 / 285 例），多出来的 17 例分三处、两处各量一遍对得上：
`JobLogControllerGroupAccessTest` 13 例（新报告，`H1`–`H14` 的承载体）、
`LoginSessionStoreTest` 10→12（`展开的组集合与点名的判据是同一份`、`带前导零的组号两边给同一个答案`）、
`ExecutorCallbackControllerH2Test` 16→18（派发行的组号那两例）。
**核对方法**：`git ls-tree` 两棵树各自 `grep -c @Test` 求和 = 297 → 314，与 surefire 报告的总数逐位相同；
只认其中一把尺的话，报告目录没清干净就会 +1 类（下面那条）。
其中 `JobTriggerServiceImplBehaviorTest` 15 例（含钉住分片广播语义的那 1 例）、
`ZSchedulePoolDefaultTest` 4 例（② 的池默认）。
先 `rm -rf surefire-reports` 再数：不清会把你**本轮没跑到的**类的旧报告一起加进来（历史上报出过 +1 类）。
**跑过注入脚本之后必须 `mvn clean`**：还原只写回字节不改 mtime，增量编译会接着用上变异体的 class（坑 20）。

## 9. 登录态、角色与分组：这一格兑现到哪一步（#19 + #28 + `permission` 收口）

改之前要知道的现状，全部由代码 + 测试钉住，不靠这段话：

| 事实 | 落在哪里 | 谁来红 |
|---|---|---|
| `/user/login` 换回的是一串 256 bit 不透明令牌，**不再是用户名** | `UserServiceImpl.login` → `LoginSessionStore.issue` | `登录用明文密码比对库里的散列`（M3 摘掉即红） |
| 令牌解析回 `{userId, username, role, permission}`，挂成请求属性 `z.schedule.loginSession` | `TokenAuthFilter.doFilter` | `会话身份挂进请求而共享密钥不挂`（M2） |
| 建/改/删账号三个口只认 ADMIN 会话；普通会话 403 且理由写"需要管理员角色" | `TokenAuthFilter.ADMIN_ONLY_PATHS` | `普通会话改不了账号而管理员会话能`（M1） |
| **"把某个账号变成 ADMIN"另有一道闸**：只认 ADMIN 会话或共享密钥放行的请求；演示模式下匿名只能建 NORMAL | `UserController.canMintAdmin` ← `TokenAuthFilter.presentedSharedSecret`（新属性 `z.schedule.fullAuthority`） | `匿名与普通会话都铸不出 ADMIN，而 ADMIN 会话与共享密钥铸得出`（#28 的 N1–N5，见下） |
| 会话 30 min 过期、上限 1000 条按"最久没被出示"逐出 | `LoginSessionStore` | `过期令牌解析为空并且不再占位`（M6）、`容量满时逐出最久没被出示的那条`（M7） |
| 改角色 / 删账号 ⇒ 该用户的会话立刻全部作废 | `UserServiceImpl.update/delete` | `改角色会把该用户的旧会话踢下线`（M4）、`删账号会把他的会话一并撤销`（M5） |
| **`permission` 这一列第一次有了读者**：签发时一起带进身份，`/jobinfo/*` 按 jobGroup 判它 | `LoginSession.permits`（6 参构造的第 5 参 ← `LoginSessionStore.issue(user)`） | `permission列进了身份就参与判定`（`G5` 摘掉签发那一参即红）、`这一列是人手填的所以解析只容错不抛` |
| `/jobinfo/*` 八支端点按组收口：普通会话只见/只动 `permission` 里列出的组。**留空 = 不限**（不把既有账号一夜锁死）；ADMIN / 共享密钥 / 匿名结构上不受约束（`restrictable` 提前返回 `null`，它们连"为判组多打一次库"都不发生）。`update` 判**两道**：库里那一行的组 + 请求体里的组 | `GroupAccess.restrictable/narrow/denialReason` ← `JobInfoController` 的 `list/getById/add/update/remove/stop/start/trigger` | `JobInfoControllerGroupAccessTest` 10 例（`G1`–`G4`、`G6`、`G7`、`G9` 逐支红在具名判据上；`G8` 只能红在身份层，见下面那段）、真机 `p22` 的 `D` 段 |
| 改 `permission` ⇒ 同样作废会话（**新值要重新登录才生效**：旧令牌带着旧的一组组号就是隐身入口） | `UserServiceImpl.update` 的 `permissionChanged`（在合并进库里那行**之前**算） | `改分组会把该用户的旧会话踢下线`（`G10` 摘掉即红、`G11` 放宽成"填了就算变"也即红）、真机 `D.14`/`D.15`/`D.16` |
| **日志侧判的是日志行自己那一列**（`job_log.job_group`），与任务那一行不是一张表：`/joblog/*` 四口都收口。`list` 四分支：点名别组 = 拒绝（不是空表）、点名 `jobId` = 先问任务现在那行、什么都没点名 = 见下一行、`clear` 的 `type=0` 对分权会话直接拒绝 | `GroupAccess.restrictable/denialReason` ← `JobLogController` 的 `list/getById/executionLog/clear` | `JobLogControllerGroupAccessTest` 13 例里的 `H4`/`H5`/`H6`/`H7`/`H8`/`H9`、真机 `p22` 的 `E.7`/`E.9`/`E.10`/`E.12` |
| 不限组的列表是**逐组各查一页再合并重排**，截断按 `JobLogService.effectiveLimit(limit)`（不是原始 `limit`）——"先全局查一页再裁"在别的组更忙时会把手里那页整个挤空 | `JobLogController.newestAcross` + `NEWEST_FIRST`（空 `trigger_time` 视最旧、同刻按 id 倒序，与 `query` 的 `ORDER BY` 同规则） | `H1`（改成全局查一页再裁）、`H2`（合并后按原始 `limit` 截）、`H3`（合并后不排序）；真机 `E.5`（合并等于 SQL 现算的期望序）与 `E.6`（空时间末位 + 同刻 id 倒序，逐位对拍）、`E.15`（一个组灌到 1,207 行时 `limit=5000` 收窄到 1000，合并侧也是 1000） |
| **写侧那一列不能是死值**：派发落库时组号从库里那一行取，库里没有的任务不落行 | `ExecutorCallbackController.run`（`job.getJobGroup()` + `job == null` 先拒） | `E1`–`E3` 三支注入（写死 0 / 摘空值守卫 / 取错字段）、真机 `E.2`（`job_group` 回读 = 任务的组）与 `E.3`（全表行数不变） |
| `/user/logout` 幂等：不回答"这把令牌先前在不在用" | `UserController.logout` | `令牌能过过滤器而登出后过不了` |
| 过滤器与签发方共用**同一个** store 实例 | `ZScheduleAutoConfiguration.tokenAuthFilterRegistration` | `过滤器必须注册在整个应用入口上`（M8，就是坑 20 那条红） |

八支注入的读数：`8/8 KILLED-exact`（每支都只红在预期的那条具名判据上，还原后 md5 逐支对账）。
#28 的闸另打五支（量具 `~/.cache/zsched_28_mut.py`）：摘掉 `add` 的闸、摘掉 `update` 的闸、
把 `presentedSharedSecret` 改成恒假（防"共享密钥也被锁死"）、把 `equalsIgnoreCase` 换成 `equals`
（防 `"admin"` 小写绕过）、去掉会话那一支——**5/5 KILLED-exact**，还原后整族重跑 `mvn clean package` 全绿。
按 jobGroup 这一层再打十一支（量具 `~/.cache/zsched_grp_mut.py`，台账 `zsched_grp_mut_ledger.txt`，逐支 md5 还原对账）：
`G1` `permits` 恒真、`G2` `restrictable` 恒 `null`（结构上没人受约束）、`G3` `narrow` 不裁剪、
`G4` `update` 只判请求体那一侧的组（少掉"库里那一行"的第二道判据）、`G5` 签发时不带 `permission`（把列丢回装饰）、
`G6` 四支按 id 的端点的闸摘掉、`G7` 点名要别组时只裁剪不给拒绝、`G8` `permits` 里管理员那一支不优先、
`G9` 留空当成"一组都不碰"（向后兼容那半支摘掉）、`G10` 改 `permission` 不再作废会话、
`G11` 只要请求里带了 `permission` 就算变了（误踢）——**11/11 KILLED-exact**。
其中 `G8` 值得记一笔：摘掉 `permits` 开头的 `isAdmin()` 之后，`JobInfoControllerGroupAccessTest` **十例全绿**——
不是代码有洞，而是 `restrictable` 对 ADMIN 提前短路，那一支在 `/jobinfo/*` 上结构上不可达。
第一版把预期红挂在 controller 层，量到的是 `SURVIVED`；改挂到身份层
（`LoginSessionStoreTest` 的"ADMIN 带着 `permission=1` 仍然要 permits(2)"）才红得下来。
**⇒ 预期红集要按"谁读这个值"来派生，不能按主题挂。**

日志那一层再打十四支（量具 `~/.cache/zsched_joblog_mut.py`，台账 `~/.cache/zsched_joblog_ledger.txt`，
每支还原都只从**本次运行**的 `.h-base` 副本 `cp` 回来并 `os.utime` 到现在，逐支 md5 对账）。
名字照台账原文：读侧五支 —— `H1` 全局查一页再裁剪（分权会话会少给行）、`H2` 合并后按原始 limit 截、
`H3` 逐组合并结果不排序、`H8` 点名组那道闸摘掉、`H9` 按 jobId 过滤解析不出组也给看；
单条与清理四支 —— `H4` 单条读取那道闸摘掉（`getById` 与 `executionLog` 共用）、`H5` 清空全部对分权会话开放、
`H6` 按 jobId 清理时任务不存在就放行（fail-open）、`H7` 按 jobId 清理不判组；
身份层五支 —— `H10` 留空的账号也被当成需要收口、`H11` 需要收口的身份不短路（每个请求多打一次库）、
`H12` `permits` 退回字符串比对（与前导零的展开分家）、`H13` 展开不去重、`H14` 错字 token 当成第 0 组。
**十四支在台账里都有一条 `KILLED-exact`**，但这不是一遍跑出来的：首跑是 9 精确红 + 3 合法多红
（`H1`/`H2`/`H8` 各多红一条，原因是那几条断言也读同一个符号，把预期集补全后复跑归入精确）
+ 2 支 `SURVIVED`，而那两支才是这一族的收获：

- **`H3` 的 `SURVIVED` 是测试的洞，不是产品的洞**：那例的替身 `FakeJobLogService` 自己按同一规则
  排过序，空时间的样本行又恰好落在"后查的那个组"，于是"逐组拼起来的原始顺序"本来就等于合并后的正确
  顺序——摘掉 controller 的排序，断言照样绿。**替身替你做了的事，你的断言就没在测它**；把两行空时间
  挪进**先查的那个组**（拼起来就是错的序）之后 `H3` 精确红。
- **`H11` 的"预期红"是我写宽的**：摘掉 `restrictable` 的短路，只有 3 例真会反应（其余各例走的是匿名/
  密钥/留空那几支，短路前后行为一致）。判据按"谁读这个值"派生这条在 `G8` 已经记过一遍，
  这次是同一个错第二次长在我自己身上。
- 顺带一个量具自己的 bug：子集过滤写成 `name.startswith('H1')` ⇒ 选 `H1` 会把 `H10`–`H14` 一起捞进来，
  改成按 token 精确匹配（复跑那一遍报的"9/9"就是这么来的：5 个号 + 前缀捞到的 4 个兄弟）。

### 9′. 真机读数：`p22.sh`（250，MySQL 8，构件 `a16473a` / md5 `100082780a5f4ea35bc5922c5a01010a`）

上表那张"谁来红"是 H2 + 手写替身级别的证据；`p22.sh` 把同一批主张拿到真进程上重打一遍，
**同时用两台**：常驻的 18098 没配 `accessToken`（演示模式），临时那台配了（把门关起来）。
必须两台的原因是这一格最反直觉的一条：

> **撤销在演示模式下观察不到。** 令牌被撤销 = 解析不出身份 = 等同"没出示凭证"，
> 而演示模式对"没出示"是放行的。于是"logout 生效"与"logout 完全没写"在 18098 上返回一模一样
> （实测 `A.20`：登出后的普通令牌打 `/user/add` 从 403 变回 200 —— 撤销反而**放宽**了它）。
> 只有关了门的那台能把两者分开（`B.8` 登出后 403、`B.11` 改角色后 403、`B.16` 删账号后 403）。

**为什么这一版把关门那台挪到最前面起（新增 S 段）**：#28 之后匿名的 `/user/add` 铸不出 ADMIN，
所以 A 段要用的那两个探针账号（ADMIN + NORMAL）**没法再由 A 段自己种了**。
于是脚本顺序 = S（起关门实例 → 用共享密钥种账号）→ A（打常驻演示实例）→ B（回到关门实例验撤销）→ C（收尾）。
这个顺序变化本身就是 #28 的兑现面：它把"第一个管理员从哪来"从一句口头答案变成了一条**每天真跑的路径**（S.6）。

09-26 22:14、22:15、22:30 连跑三次（构件 `44acc07`）：**76 条 PASS / 0 FAIL / 4 条观察**（全文落 `logs/p22.txt`；
那次跑的是两端 md5 逐字节相同的 `9c6a8d0dab20f9881c17b02277bfa2f3`）。上一格是 55 条 / 0 / 3，
多出来的 21 条 = `D` 段的 19 条 PASS（`D.13` 是观察不是判红）+ 两支构件形状判据 `0.4b`/`0.4c`。

09-26 23:24、23:26、23:27 又连跑三次（构件 `a16473a`，脚本两端 md5 `8a03ba96065ed374e655e78e92f2fb70`）：
**92 条 PASS / 0 FAIL / 4 条观察**，分段数 `0`=8、`S`=11、`A`=19、`B`=16、`D`=19、`E`=14、`C`=5。
76 → 92 那 16 条 = `E` 段 14 条 + 新加的两支构件形状判据 `0.4d`/`0.4e`；4 条观察一字未变
（`S.9b` 无盐 MD5、`A.20`/`A.20b` 演示模式看不见撤销、`D.13` `/jobgroup/list` 仍不裁）。

09-26 23:33、23:38、23:39 再连跑三次（构件仍是 `a16473a`，脚本两端 md5 `76f17d399274aa7ecc33f54da60cb1a0`）：
**93 条 PASS / 0 FAIL / 4 条观察**。92 → 93 只多一条，因为 `E` 段这一版只加了 `E.15`（硬上限那一格，
见下表），原来的收尾断言从 `E.14` 改号为 `E.16` 不是新增。顺带一个运维读数：一轮 28 s（`S` 段那台
临时实例 `Started ... in 6.591 seconds`），所以"连跑三遍看是不是稳定"在这台机器上不到两分钟——
便宜的复跑没有理由不跑——上一格那两条 FAIL 之所以能定性成"量具的错"而不是"产品的错"，靠的就是
FAIL 消息把两侧原样贴出来 + 改完复跑立刻归零（下一段记的是同一条）。

09-27 00:07:54–00:08:21 为**新字节**再跑一次（`p22.sh` 两端 md5 `5c05b6c88f7bcf330eb89e0821cbdb0a`，就是给
读日志的 grep 补 `-a` 之后那一版）：**93 条 PASS / 0 FAIL / 4 条观察**，一轮 27 s。之所以要再跑，是因为
上一条记的 `76f17d39…` 已经不代表盘上这份脚本了——**尺改过之后旧读数不能再挂到新尺名下**。
诚实边界：同批补 `-a` 的另外 6 支（`p13`/`p14`/`p15`/`p16`/`p17`/`p18`）只过了 `bash -n` + 两端逐字节对账，
**没有在新字节上重跑**——`p13`/`p14` 开头就 `kill $(cat app.pid)` 再另起一台，跑它们等于把挂在 250 的常驻
实例换掉，这一格留给"下次真要动它们的时候"。

**首跑那次是 90 PASS / 2 FAIL，而两条 FAIL 全长在量具上**：`ids_of` 拿 `tr '\n' ' '` 收尾留了个尾空格，
`e_expect` 那条 SQL 出来的串没有 ⇒ 整串比较永远不等；两条 FAIL 消息里贴的"实得"与"期望"id 序列逐位相同。
改的是脚本不是产品，复跑即 92/0。**⇒ 整串比较的两侧必须归一到同一条成形规则**；而 FAIL 消息把两边都
原样贴出来这一步救了场——只贴"不等"的话，这一格就只能靠重跑猜是谁的错。
挑几条只有真机才给得出的：

| 判据 | 读数 |
|---|---|
| `0.4` #28 在**跑着的那个文件**里（不看文件名、不看 mtime） | 从常驻实例 argv 取 jar → `unzip -p` 出嵌套 starter → `javap -c` 的常量池里有 `z.schedule.fullAuthority`（`0.3` 是 #19 的 `LoginSessionStore.class`） |
| `A.1` 演示模式下匿名铸不出 ADMIN 了 | 匿名 POST `role=ADMIN` ⇒ HTTP 200 而 `code:500`，理由点名要 `accessToken`；`A.2` 再查一次库确认**一个字节都没写**。（改之前这条是 `code:200` 且库里真多出一个管理员——p22 上一版把它写成 PASS，那句 PASS 就是 #28 那格） |
| `A.3` 阳性对照 | 同一条匿名请求只要 `role=NORMAL` 照样 200 ⇒ `A.1` 的红不是"`/user/add` 整个坏了" |
| `A.22` 两道网互不依赖 | 已登出的 ADMIN 令牌在演示模式里退化成匿名 ⇒ 铸 ADMIN 仍被拒。这一条**不证明撤销生效**（撤销在演示模式量不到，见上面的 blockquote），它证明的是铸权闸不把安全性押在"撤销有没有落地"上 |
| `S.6` / `S.10` 两道闸的红长得不一样 | 关门实例上匿名 = **403 `accessToken 不合法`**（过滤器，压根进不了 controller）；演示实例上匿名 = **200 + `code:500` 铸权理由**（controller）。读消息后缀就能分清是哪一道闸在起作用 |
| `S.9` 库里那一列到底是什么 | `password = md5(登录口令)` 的 32 位十六进制，与登录侧算出的散列逐字节相等 ⇒ 比对的是散列。**观察 `S.9b`**：这是**无盐** MD5——同口令必同散列，整库可反查彩虹表；换 bcrypt/argon2 是独立的一格，#28 没动它 |
| `0.4b`/`0.4c` 按组分权也**在跑着的那个文件**里 | 同 `0.3`/`0.4` 那套路子：`javap` 出来的 `GroupAccess` 常量池里有 `z_schedule_user.permission`、`LoginSession` 有 `permits(int)`。没有这两支，`D` 段红了也无法排除"量的是旧字节"（判据见 §1′ 的"只认 argv + md5"） |
| `0.4d`/`0.4e` `E` 段量的不是旧字节 | `JobLogController` 有 `newestAcross`、`JobLogService` 有 `effectiveLimit`——两个都是 `a16473a` 才有的符号。少了这一眼，`E.5` 会拿着旧字节的"全局一页再裁"读出一个形状，而那条写法正是 `E.5` 要否证的 |
| `E.2` 读侧收口的依据先由写侧兑现 | `/executor/run` 派发已有任务 ⇒ 库里新那一行 `job_group=13` **等于任务那一行的组**、`trigger_time` 非空。这一列历史上写过死值 `0`：真写成 0 的话读侧三道闸会"很严格地"把所有行滤掉，界面是一片空日志而不报任何错 |
| `E.3` 库里没有的任务不落行 | 派发给 `jobId=99999999` ⇒ `code:500` 且**全表 151973 行一条没多**。孤儿行的代价在清理口：`clearByJobId` 按 `job_id` 反查组，反查不到的行既删不掉也判不了组 |
| `E.5` 逐组合并 ≠ 全局一页再裁 | peon 有 `permission='13,14'` 两组，第三组（`15`）刻意灌了 4 行**未来 1 小时**的日志（比 A/B 的都新）。`limit=3` 实得 `725534 725535 725529`，与 SQL 现算的期望序逐位相同、第三组一行不进来。若是"先全局查一页再按组裁"，这份数据形状下返回的是**空**——所以这条的绿不是"裁到 3 行"读出来的 |
| `E.6` 排序交给两套实现各算一次 | 空 `trigger_time` 那行排末位、同一字面量时刻的两行按 id 倒序，整串 7 位与 MySQL 自己 `ORDER BY trigger_time IS NULL, trigger_time DESC, id DESC` 一致。**Java 的 comparator 与 SQL 同序才判绿**，只信一边等于让被测者自己出题（并列那对用字面量而不是 `NOW()`：列是 `datetime` 只到秒，两次 `NOW()` 跨秒就不并列了，那格会随机绿） |
| `E.7`/`E.11` 阴性读数的阳性对照 | 点名第三组 = 拒绝且理由带着组号，点名自己那组照给；管理员会话一份列表里三组的行**都在**（含那四行"比 peon 的两组都新"的）⇒ `E.5` 的"没有它的行"是被裁掉，不是那几行查不出来 |
| `E.12`+`E.13` 清理口的"没删"与"会删"同框 | 分权会话三挡都拦：`type=0` 全表仍 151985 行、越组 `type=1` 那任务仍 5 行、组解析不出的任务也拦；**同一个口换成共享密钥清它 ⇒ 5→0**，而另一个任务的 8 行不受牵连 ⇒ 前面三个"没删"是闸拦的，不是这个口从来不删 |
| `E.8` `status` 两挡在真库上互斥 | `status=2` 有 `handle_code=500` 那行、没有 `=200` 那行、也没有 `=0`（未执行）那行；`status=1` 反过来。**`0` 不能被算成失败**——否则每一条刚派发还没跑的行都会进"失败日志"列表 |
| `E.15` 硬上限只有真库给得出 | 往 A 组灌到 1,207 行（比 `MAX_PAGE_SIZE` 多），`limit=5000` 点名单组实得 **1000** 行、不限组（两组合并）也是 **1000** 而不是 1200/2000、`limit=50` 照旧 50 ⇒ 收窄发生在查询侧，且**合并方用的是同一个 `effectiveLimit` 而不是原始 `limit`**。H2 那 13 例（`H1`/`H2`）里行数是我自己造的，造不出"一个组里比上限还多"这种数据形状 |
| `D.4` 列表被裁到自己那一组 | peon 的 `permission=3`：`/jobinfo/list` 里有 A 组那行、没有 B 组那行。**裁剪发生在返回前，不是替数据库少查**——所以 `D.13` 那份没裁的组列表才是真漏，不是读数误差 |
| `D.5` 点名要别的组是**拒绝**而不是一张空表 | HTTP 200 + `code:500`，理由里带着组号（controller 那一层，见坑 23）；`D.6` 阳性对照：同一个人点名要自己那组照样给 |
| `D.8`/`D.9`/`D.10`/`D.11` 闸落在写之前 | 越组的 trigger/stop/start/remove 全被拒，而且**库里那一行没动过**：B 组那行的日志行数 `0 → 0`、`trigger_status` 不变、行还在。两支阳性对照（同请求换 A 组那行：触发真落库=1 行、启停真把 `0→1→0`）证明那些零不是"这个口从来不写" |
| `D.12` 管理员不受约束 | ADMIN 会话一份列表看得见两组；共享密钥建的任务照建（`D.2`）——收口只针对普通身份 |
| `D.14`/`D.15`/`D.16` 改 `permission` 会踢掉旧会话 | 改完旧令牌 403（关门实例上看得见）、重新登录后**跟着新值走**（看得见 B 组、看不见 A 组），且别人那条会话不动。这一条是这一格顺手补上的：`permission` 一旦成为判据，"改了不撤销"就等于降权要等它自己过期 |
| `D.13` 已知非交付（观察） | `/jobgroup/list` 仍把两个组都吐给受限会话：这份是执行器下拉的数据源，裁掉名字会让管理页只剩数字，要收口得连下拉一起改 |
| `A.5` 令牌形状 | `base64url(32 字节)` = **43 字符**。（第一版在这里断成"64 位十六进制"，把自己跑红了：形状是 Base64 URL 安全集，不是 hex） |
| `A.14` 角色闸真的有牙 | 同一条 `/user/add`、同一个请求体：普通会话 403，匿名 200。这两次只差"有没有自报身份" |
| `S.2` 口令不进 argv | `pgrep -af '[j]ava' \| grep -c 'access-token=' = 0`（走 `Z_SCHEDULE_ACCESSTOKEN` 环境变量；`run.sh` 的 `ACCESS_TOKEN=` 那条路会把口令写进 argv） |
| `C.6` 两台启停之后集群没人丢租约 | `z_schedule_job_leader.expire_time` 领先 `NOW()` 27–30 s（这张表**没有**"最后续约时间"列，列名以 `DESC` 为准，坑 22 的 SQL 版本） |

控制组（同一台机器、同一个库，只换构件）：拿 #19 **之前**的 `7c9de99` 起重实例，
`/user/login` 回的是 `{"msg":"登录成功","content":"p23_pre"}` —— **content 就是用户名**（7 字符），
而把它当凭证打 `/jobinfo/list` 得到 **403**，同一条口用共享密钥是 200。
⇒ 改之前"登录"这件事不是"弱"，是**换回来的东西过不了任何门**：一句成功的 `msg` 加一串公开可查的用户名。
这条也就是 `A.5`/`A.6` 的猎物（同一棵旧字节上两支都红：7 字符过不了 43 位的形状判据，
而它的值正好等于用户名）。

**这一格没有做完的部分，别当成已经生效**：

1. `permission` 这一列（逗号分隔的 jobGroup id）**现在真的有读者了**（`LoginSession.permits` ← `GroupAccess`，
   见上面的 `D` 段），但它此刻只管 `/jobinfo/*`：`/jobgroup/list` 仍把全部组吐给受限会话（观察 `D.13`）——
   这一份是执行器下拉的数据源，裁掉名字会让管理页只剩一组数字，要收口得连下拉一起改，不在本格范围。
   还有两条边界要说清：收口挂在**会话身份**上，共享密钥与匿名请求走 `restrictable` 的提前 `return null`，
   结构上不受约束（这是刻意的：密钥=全权，匿名=演示模式），所以**演示模式下看不见按组分权**，
   `D` 段整段跑在关了门的那台上；而 `permission` 留空 = 不限，是为了不把既有账号一夜之间锁死。
2. 共享密钥 `z.schedule.accessToken` 不区分"是谁"，因此**不受角色闸约束**（它本来就是全权，
   #28 也刻意让它在铸管理员这一关上通过——`B.5`，配套注入 N3）。未配置它的演示模式下管理面对匿名敞开，
   但**出示了的**普通会话照样被拦（`演示模式下匿名全开但出示的会话仍被限角色`、`A.14`）。
3. 会话只在本进程内存里：重启即全员重新登录，多实例部署下 A 机签的令牌在 B 机验不过。
   要跨实例得注册一个自己的 `LoginSessionStore`（那个 `@ConditionalOnMissingBean` 就是留给这事的口子）。
4. 读侧只收口了一半：`/jobinfo/*` 现在按组裁剪（`D.4`/`D.7`），但 `/user/list`、`/joblog/*`
   仍不按角色或分组收口（`C.7` 实测：常驻那台的 `/user/list` 对匿名仍是 200；`A.17` 同一条对普通会话也是 200）。
   界面侧还没有登录入口——z-opc 的 schedule 页面从来没调过 `/user/login`（实测该目录 `login|accessToken` 零命中），
   所以这一格交付的是**后端的身份载体 + 任务面的分权**，不是"用户能登录"这件事。
5. `password` 列是**无盐 MD5**（`S.9`/`S.9b`）：库被读走看不到明文，但同口令同散列、可整库反查。
   换 bcrypt/argon2 会改动已发布的 `UserService` 语义与既有账号行，是独立的一格。
6. 铸权闸只管 `role=ADMIN` 这一个值（`N4` 钉住"只有真的 ADMIN 才算提权"）。
   `VIEWER` 之类的自定义角色仍然没有语义（`permission` 现在有了，见上面第 1 条；它只被 `/jobinfo/*` 读）。
7. **这一列在有这张表的库里要靠 `ALTER`，而仓库里没有任何脚本做这件事**：`_doc/004_sql/z-schedule.sql:120`
   把 `permission` 写在 `z_schedule_user` 的**建表语句**里（`varchar(512) NOT NULL DEFAULT ''`），
   所以新建库天然就有（250 那台 E2E 库就是 `bootstrap_mysql.sh` 新建的，`D` 段的读数走的是这一条）；
   而**已经建过这张表的库**（比如线上 `oc`）不会自动多出一列，`bootstrap_mysql.sh` 只做"DROP 条数必须为 0"
   那道守卫再跑整份 DDL，**不 ALTER 既有表**。线上 `oc` 有没有这一列本轮**未复核**（我不拿仓库里的明文口令当凭证源）——
   所以"收口在线上生效了没有"这个问题**别引用本节当答案**，先 `SHOW COLUMNS FROM z_schedule_user LIKE 'permission'`。

---

## 10. 探针面：一块从没生效的 yml，和两条只在真机上才红的缺陷（#29）

这一格两条缺陷的共同点是**都不会让启动失败**：第一条是配置写在没人读的前缀下，第二条是探测路径答 200
而这个 200 一个字都没问过库。所以单测抓不到它（admin 模块此前**一条测试都没有**，见 §8），
"服务起来了、页面能开"也抓不到——只有把探针面本身当成被测对象、并且**故意把库弄坏**才现形。

### 10.1 键位：`spring.actuator.*` 里整块是死配置

`application.yml` 把暴露面 / 明细 / 探针组写在 `spring:` 底下（属性名于是成了 `spring.actuator.*`），
而 Boot 读的是顶层 `management.*`。250 真机、跑着的构件 `a16473a`，不带任何 override：

| 路径 | 改前（`a16473a`） | 改后（`7ea14eb`） | 这一格说明什么 |
|---|---|---|---|
| `/actuator/health` | 200 `{"status":"UP"}` | 200 `{"status":"UP","groups":["liveness","readiness"]}` | 探针组根本没建 ⇒ 组那几行是死的 |
| `/actuator/info` | **404** | 200 | `exposure.include` 从未生效（默认只暴露 health） |
| `/actuator/health/liveness` | **404** | 200 | `deploy/k8s/01-deployment-backend.yaml` 的 `livenessProbe` 探的就是这条 |
| `/actuator/health/readiness` | **404** | 200 | 同上，`readinessProbe` |
| `/actuator/env`、`/actuator/metrics` | 404 | 404 | 白名单的反向对照：改后**仍然** 404，才证明 `include` 是白名单而不是摆设 |

同一族的还有 `logging.level.io.github.yuku123.…`：包名 1.0.x 改成 `com.zifang.z.schedule` 时 yml 没跟着改，
引擎 debug 日志哑了两周。`ManagementConfigBindingTest` 的 `everyLoggingLevelPackageIsReachable()`
钉的就是这一族——每个 `logging.level.<pkg>` 都得能在 classpath 上解析出目录，解析不到就红。

顺带纠一处**文档里的假话**：`_doc/001_arch/z-schedule-admin.md` 原先写"本应用开了 `show-details=always`，
health 会把数据源信息吐给匿名访问者"。前半是错的（那段配置从未生效，实测 health 里没有 `components`）；
后半之所以也没发生，是 `TokenAuthFilter` 把 `/actuator/*` 403 掉了——过滤器与这段 yml 无关，那条 403 一直是真的。
**把"没发生的风险"写成"已经泄露"比漏写更糟**：它会让人以为这件事已经处置过了。现在 `show-details` 显式定为
`when-authorized`，理由换成一条量过的：故障时 detail 里是连接池内部状态与驱动报错原文
（实测含 `GetConnectionTimeoutException: wait millis 3000, active 0, maxActive 20, creating 4`），
而演示模式（未配 `z.schedule.accessToken`）整个 HTTP 面对匿名敞开。

### 10.2 `readiness` 不含数据源：200 不等于问过库

三臂各起一台临时实例（`p23.sh` 的 1)/2)/3) 段；常驻那台不动，破坏性实验不能落在共享服务上）：

| 臂 | `/actuator/health` | `…/readiness` | `…/liveness` |
|---|---|---|---|
| 库正常 | 200 UP | 200 UP | 200 UP |
| 只坏 Spring 池（`--spring.datasource.url` → 没人听的 33999） | 503 DOWN | **503 DOWN**（改前：`a16473a` 是 404，因为组根本不存在；把键位修好而组还没显式写时它是 **200 UP**——那才是缺陷本体） | 200 UP，明细里没有 `db` |
| 只坏引擎池（`--z.base.db.schedule.port` → 33999） | 503 DOWN（`dataSource` UP、`dataSourceSchedule` **DOWN**） | **503 DOWN** | 200 UP |

第三臂是这一格最要紧的一读，它问的是"**产品本体那一侧的库**在不在 readiness 的判定集里"：
调度引擎读写任务用的是 starter 自建的 `dataSourceSchedule`（`z.base.db.schedule.*`），
而第二臂坏掉的 Spring 池平时根本不干活（§4 量过它空转）。`show-details=always` 取回的 body 给出分工：
`db` 是 composite，`dataSource` 与 `dataSourceSchedule` **两个子项都在 readiness 的判定集里**
⇒ 引擎池单独坏时 readiness 一样倒，不是只盯着那个空转池。这条判据现在是 `p23.sh` 的 3.3/3.4——
**写第三臂之前它并不成立**：只有第二臂的话，全绿也证明不了 readiness 守的是哪一侧。

机制上两条都得显式写：Boot 只在检测到 Kubernetes 平台时才自动建 `liveness`/`readiness` 两个组
（⇒ 离集群它们天生 404，而部署文件承诺的探测一次都没被验过），而它自动建的 `readiness` 只含
`readinessState`/`ping`、**不含 `db`**。于是 `probes.enabled=true` 让组在任何平台都存在，
`group.readiness.include=readinessState,db` 把库拉进来，`group.liveness.include=ping,livenessState`
**故意不含库**——库短暂抖动不该把进程重启掉（重启只会让 reconcile 更糟）。

### 10.3 两支判据，和它们自己的自证

`ManagementConfigBindingTest`（`z-schedule-admin` 的第一份测试；6 例，不起 Spring 上下文，
`YamlPropertySourceLoader` 把 classpath 上那份 yml 读成 `MapPropertySource` 再按键判）。
5 支注入变异逐支点名转红，**实际红集与期望红集逐字相等**（台账 `~/.cache/zsched_mgmt/ledger_7ea14eb.log`；
变异只打在 `target/classes/application.yml` 那份被测副本上，每支跑完按 md5 双向对账还原）：

| 变异 | 期望红 = 实际红 |
|---|---|
| M1 整块搬回 `spring:` 底下并改名 `actuator:`（= 原缺陷的形状） | `probeConfigSitsWhereBootReadsIt` + `exposureIsWhitelisted` + `probeGroupsExistOffCluster` + `readinessAsksTheDbAndLivenessDoesNot` + `detailsAreNotPublic`（5 支，不多不少） |
| M2 `readiness` 组摘掉 `db`（回到 Boot 的自动组） | `readinessAsksTheDbAndLivenessDoesNot` |
| M3 `liveness` 组塞进 `db`（库一抖就重启） | `readinessAsksTheDbAndLivenessDoesNot`（同一支，因为它判的是**分工**不是单侧） |
| M4 `logging.level` 指回改名前的旧包 | `everyLoggingLevelPackageIsReachable` |
| M5 `show-details` 改回 `always` | `detailsAreNotPublic` |

`p23.sh` **同一份字节**（md5 `f7316ad7…`，两端 `md5sum` 相同）对两个构件各跑一遍——这是 A/B，不是复跑：

| 待验构件（取自常驻进程 argv，不认文件名） | 结果 | 用时 | rc |
|---|---|---|---|
| `z-schedule-admin-svc-7ea14eb-exec.jar`（`90ead3a6…`） | **PASS=19 FAIL=0 OBS=0** | 81 s（00:58:10→00:59:31） | 0 |
| `z-schedule-admin-svc-a16473a-exec.jar`（`100082780a…`，上一版） | **PASS=8 FAIL=11 OBS=0** | 61 s（00:59:31→01:00:32） | 1 |

旧构件那 11 条红在 1.2 / 1.3 / 1.4×2 / 2.3 / 2.4 / 2.5 / 2.6 / 3.3 / 3.4 / 3.5；8 条绿是 1.1、1.5×2、1.6、
2.1、2.2、3.1、3.2。**其中 1.6 与 2.2 在旧构件上也是绿的，各自都有理由**：1.6 判"匿名拿不到组件明细"，
而旧构件本来就没明细 ⇒ 它不是这一格的判据，是防"改完反而把明细铺出去"的守卫；2.2 判顶层 health 503，
顶层一直是对的（**缺陷在组路径那一侧**）。这一格踩到的一次真·判据空转也长在这里：
`2.5` 原先只 grep body 里有没有 `"db"`，于是**404 的空 body 也算绿**——而 404 正是它该说话的时候。
现在它先要求这份 body 真答了 `status` 再判明细（旧构件在 2.5 上由绿转红，就是上面那 11 条里的一条）。

### 10.4 三条自伤：都是量具的错，记下来免得再犯

1. **`APP_ARGS=\"…\"` 把字面引号送进了 argv**。在一条 `ssh '…'` 的单引号参数里写 `APP_ARGS="…"`，
   那对引号不是 shell 语法而是**字符**，于是 `--spring.datasource.url=…` 带着 `"` 进 JVM、谁也不认识它
   ⇒ override 从未应用，而 ORM 照常工作，我差点据此写下"`spring.datasource.*` 是惰性的"这条大结论。
   `cat -A /proc/<pid>/cmdline` 一眼定性（能看见参数里的 `"`）。改成 `export APP_ARGS="…"` 后拿到了真读数。
   **凡是"我改了参数而行为没变"的读数，先证明那条参数进了 argv**——这条现在写在 `p23.sh` 的 3.2 里，
   不用等下一次踩到。
2. **`svc_smoke.sh` 的日志路径写死 `logs/service_${PORT}.out`**。本轮起日志一构件一文件（§1″），
   那个默认路径指向的是**上一个构件**的日志 ⇒ 库侧 21 条 `handle_code=200`（真执行了）而 handler 侧证 0 行，
   冒烟把自己判红。现在落点从 `/proc/<pid>/fd/1` 读（带 ` (deleted)` 尾巴或拿不到就 FATAL，不猜路径），
   当场打印"日志落点取自 fd/1"，重跑即 **VERDICT: OK**（36 行 / 36 成功 / handler 侧证 39 行，00:45:26→00:45:52）。
3. **管道退出码遮蔽，第二次踩**：`md5sum 不存在的文件 | cut -d' ' -f1 || 备选支`——管道 rc 取的是
   **最后一个命令**的（`cut` 恒 0），所以"备选支"永远不会跑，我据此差点把"远端脚本字节不一致"写成结论。
   同一批里 `grep -c` 数为 0 时 rc=1 会**掐断 `&&` 链**（这次正好当正向对照用：旧构件那条链停在哪，
   就说明它数到了 0）。判"文件不存在"的读数一律单独一条命令取。

顺带一条**读数而不是缺陷**：同一份 `svc_smoke.sh` 两次跑出的成功行是 21 与 36（窗口都写死 25 s）。
差的那些落在"新播种的任务要等下一轮 reconcile 才进 ring"这段，脚本的判据是"≥ `$N` 行"而不是"≥ 期望行数"，
所以这个波动造不出假红——但打印里那句"期望约 37 行"是提示不是判据，别引用它当证据。

### 10.5 这一格的换构件动作（09-27 00:40，`a16473a` → `7ea14eb`）

照 §1″ 那四步走，读数：`kill -TERM` 后**第一次采样端口已无人监听**（循环步长 0.5 s，所以读数只能说
"<0.5 s"，别抄上一轮的"1 s"）、旧日志 `Stepped down from LEADER` 1 行；新进程
`Started ZScheduleAdminApplication in 6.865 seconds`，`Became LEADER` 在其后 **61 ms**
（00:40:45.551 → 00:40:45.612）⇒ 等旧租约的时间仍是 0（#18 的兑现面每次换构件都被重打一遍）。
换完复验身份：argv 里是 `z-schedule-admin-svc-7ea14eb-exec.jar`、两端 md5 `90ead3a6…` 逐字节相同、
`deploy: JAR=… db=127.0.0.1:33060/zschedule_e2e` 那行连的库没变、`/` 与 `/jobinfo/list` 与 `/joblog/list` 照旧 200。
**键位这件事也在构件字节里复验了**：`unzip -p <jar> BOOT-INF/classes/application.yml | grep -c probes`
旧 0 / 新 1（一负一正两条对照，不靠文件名、不靠 mtime）。

### 10.6 这一格没做的

1. **`deploy/k8s/01-deployment-backend.yaml` 的 env 喂不到引擎池**：它只给了 `SPRING_DATASOURCE_PASSWORD`
   （secretKeyRef）+ `SPRING_PROFILES_ACTIVE: "k8s"`，既没有 `SPRING_DATASOURCE_URL` / `USERNAME`，
   也没有 `Z_BASE_DB_SCHEDULE_*`。而 `ModuleDataSourceTemplate` 的默认值是 `localhost:3306` + **空库名**
   （`z.base.db.<module>.{host,port,username,password,database}`，逐个回落 `z.base.db.default.*` 再回落内置默认）
   ⇒ 照这份 manifest 起，引擎池连的是 `jdbc:mysql://localhost:3306/`。`run.sh`（250 那条演练路径）两套都喂了，
   所以这个缺口只在集群那条路上。**本轮仍未在真集群里验过**，manifest 那一半是下一格。
2. `SPRING_PROFILES_ACTIVE=k8s` 在 `src/main/resources/` 里**没有对应文件**（只有 `application.yml`、
   `application-dev.yml`、`application-local.yml.example`）——Boot 不报错，所以这行现在是惰性的，
   而它给人"集群有一套专门配置"的错觉，那份配置并不存在。
3. 探针面只到 `health` / `info`。`metrics` 是**刻意不暴露**的，代价是调度器没有指标出口，
   §4 那几格吞吐全靠外部脚本数库；要接 Prometheus 得先决定暴露哪几个 gauge，是独立的一格。
4. 常驻那台是演示模式（没配 `accessToken`），它的 `/actuator/health` 因此对匿名可探——这是 k8s 探测的
   既成约束（探测不带 token），也正是 `show-details` 必须留在 `when-authorized` 的原因。
   要连状态码都不给匿名，得为探针口单独定策略，本轮没动它。

## 11. 部署面彩排：`deploy/` 那套清单到底起不起得来（#30，`p24.sh`）

§10.6 的第 1、2 条当时记的是"下一格"。这一格把它做完了，结论是**那一半也不能算"知道"**：
`deploy/` 下的入口脚本、清单、compose、nginx 模板、README 一起数下来有**五条能让部署直接失败**的缺陷，
全部在提交树里躺了两周到一个月，而且**每一条都能被一条不起集群的机械判据抓到**——之前没人写而已。
尺在 `_doc/003_script/e2e/p24.sh`（臂 A 静态 16 项 + 臂 B 真容器 23 项，跑法见档头）。
250 上 09-27 02:05:42 的收口读数（时刻取自日志 mtime，`stat -c %y`，不是推的）：
**`总判：PASS=39 FAIL=0`**，臂 A 那 16 项在这遍里于 250 同跑也全绿；此前臂 A 在 macOS 单跑也是 16/0，
静态那半不挑机器。

### 11.1 五条提交树里的缺陷（都不在"跑着的那个进程"的路径上，所以从没现形）

| # | 缺陷 | 后果 | 怎么被抓到的 |
|---|---|---|---|
| 1 | `deploy/bin/*.sh` 五个入口脚本里的变量全写成 `"$ VAR"`（`$` 与名字之间多一个空格） | `bash -n` 不报错（rc=0），但展开成字面量 `$ VAR`：`k8s-apply.sh` 拿不到渲染目录、`build-images.sh` 的 `ROOT` 指错一级、`start-mode*.sh` 的 `cd` 落到父目录 ⇒ 三个"一键"入口**从未跑通过**。最早的那次提交是 `c8e3a8f`（09-17） | A1：扫 `deploy/bin/*.sh`，且**同一条尺先在人造猎物上命中**才许它报绿 |
| 2 | 后端清单只喂 `SPRING_DATASOURCE_PASSWORD` + `SPRING_PROFILES_ACTIVE: "k8s"`（而仓库里没有 `application-k8s.yml`） | 引擎池（starter 的 `dataSourceSchedule`，键在 `z.base.db.schedule.*`）退回内置默认 `jdbc:mysql://localhost:3306/`、**库名为空**；照这份清单起的 pod 连的是一个不存在的库 | A7/A8 判清单，B12 用**改前那份 env** 真起一个容器，让它当场坏 |
| 3 | 清单 `image:` 多写一段命名空间（`ghcr.io/yuku123/yuku123/z-schedule-admin`） | pod 拉的是**从没构建过的名字**，`ImagePullBackOff` | A6：拿 `build-images.sh` 自己那串 `-t` 与清单里的 image **逐字比名字** |
| 4 | `nginx.conf.template` 把 `/api/` 反代到 `/meta/api/` | 后端没有任何 `/api/**` 控制器 ⇒ runbook 里"验证反代：`curl http://localhost/api/actuator/health`"必 404，而这条是**验收步骤** | A10 判 proxy_pass 的落点，A10b 反向对照：控制器确实挂在 `@RequestMapping("/jobinfo")` 上，没有 `/api` 前缀可转 |
| 5 | 三种模式的 compose 与 `Makefile` 都写 `${DB_HOST:-mysql}` / `DB_HOST ?= mysql`，而**没有任何一种模式自带叫 `mysql` 的服务**；同时 compose 只自动读 `deploy/.env`，本仓模板在 `deploy/env/` 下 | 照 README 敲 `make dev` 的人：`DB_*` 一条都读不到，`DB_HOST` 静默落到一个不存在的主机名 ⇒ 起得来容器、连不上库，坏相正好是 B12 取证到的那一种（端口不 bind、不退出、日志无限刷） | B13 双向：缺 `DB_HOST` 时 compose `config` 必须 rc≠0 且消息点名它；给了则 rc=0 且**两个池**从同一份 `DB_*` 渲染出来（A6b 另外钉住默认值在四处手抄必须逐字相等） |

A6b 另外钉住一件事：`IMAGE_VERSION` / `OCI_REGISTRY` 的默认值在**四处**手抄
（`build-images.sh`、`k8s-apply.sh`、`Makefile`、`env/.env.example`），抬版本时最容易漏的就是后两处，
所以四处必须逐字相等。`deploy/README.md` 里原先写的 `1.0.1` 与"`{{XXX}}` 占位符（envsubst 风格）"
都是假话——清单用的是 `${XXX}`，而照那段手抄命令渲染出来的文件必然带着没替换的 `${DB_NAME}`，
已按 `k8s-apply.sh` 的真实变量名单改写。

### 11.2 彩排为什么不是 `kubectl apply`

250 上有 k3s，但**建不出任何 pod 沙箱**：pause 镜像（`mirrored-pause:3.6`）拉不到，本地 registry 里也没有，
任何 Deployment 都停在 `ContainerCreating`。所以臂 B 是"**把清单渲染出来的 env 与探针路径原样交给
`docker run`**"：ConfigMap 由 B5 机械转成 `--env-file`（不是手抄），探针路径由 B6 从渲染后的
`livenessProbe` / `readinessProbe` 里取。**集群侧 kubelet 的行为（探针失败N次重启、Secret 挂载、Ingress）这一格没验**，
别把 B 臂的绿读成"这套清单能上集群"。

### 11.3 臂 B 读到的东西（250，MySQL 8 在 `127.0.0.1:33060`，构件 `7ea14eb`）

一条链走完：**建镜像 → 字节对账 → 建独立库 → 照清单起 → 探针 → 走产品自己的 HTTP 建任务并启用 → 数一次真实执行**。

- B2 镜像内 `/app/app.jar` 的 sha256 与宿主机构件相同（不看文件名）；B3 从**发布件里**取
  `BOOT-INF/classes/application.yml` 数 `probes`，1 命中 ⇒ §10 那块键位修复确实在镜像里。
- B4 建 `zschedule_p24` 独立库，**必须先证明 DDL 不含 DROP**（只数非注释行——那份脚本自己写着
  "不含任何 DROP"，`grep -c DROP` 会命中它自己的注释）；用独立库的原因是
  `z_schedule_job_leader` 的租约是**按库**的，共用 `zschedule_e2e` 时容器永远抢不到 Leader，
  也就永远不派发——彩排会变成"两个进程互相以为对方在干活"。
- B6/B7/B8：清单声明的两条探针路径都答 200，`/actuator/metrics` 仍 404（暴露面白名单有牙）。
- B9：`/meta/jobgroup/list` 回显 B4 插进去的分组行 ⇒ 引擎池连的确实是 `zschedule_p24`，不是"看着像连上了"。
- B10 → B11 这一段是这一格最值钱的部分，它把"部署出来的东西**能不能真执行一次任务**"变成读数：
  HTTP 建出 FIX_RATE 任务（#16 修好的那条：不带 cron 也建得出来）→ **库里 `trigger_status=0`**
  （B10b，产品语义 `add()` 硬置 0）→ HTTP `start` → **库里 `trigger_status=1`**（B10d）→
  容器日志 `[Leader] jobId=1 已挂入时间轮`（B11b）→ 库里出现 `handle_code=200`（B11）→
  下一轮 reconcile 的 `Engine loaded 1 jobs` 与 `trigger_status=1` 的行数**相等**（B11d，防的就是 #10 那一族
  "reconcile 把已排期任务冲掉"→ 容器日志里 `demoHandler 执行 jobId`（B11c，handler 侧证；这一行是"200 到底是不是这个进程产的"唯一直接证据，少了它，库里那行 `handle_code=200` 可以是被谁写进去的）。
  B11c 的判据是"基线行之后 ≥1 行"，**不是**等于某个定值——两跑实测 7 行与 8 行（窗口固定在启用之后，
  多的那一行落在 break 之前还是之后随时序漂），所以别把任何一个具体行数当证据抄。
  `z_schedule_job_registry` 是 0 行——那是**远程 executor 的心跳表**，进程内派发不读它，0 是预期（第一次读的时候我差点把它当成因）。

### 11.4 B12：改前那份 env 坏成什么样（取证 240 s，判据只写读到的形状）

| 读数 | 值 | 为什么不是"我猜的" |
|---|---|---|
| `…/actuator/health/readiness` | **000**（不是 503） | Druid 在 **init 阶段**就卡在引擎池上，Tomcat 从没起 ⇒ 端口根本没 bind。我先按 503 写判据，第一次跑就是红的 |
| 容器状态 | 一直 `running`，240 s 内既不 Ready 也不退出 | 集群里 `restartPolicy` 永远不会被触发，pod 就那样挂着 |
| 日志量 | 取证那遍 **20 760 行 / 240 s ≈ 86 行/s** | 全是 `Communications link failure` + `Connection refused` 栈。**断言那遍读到的少得多**（443 / 上一遍 572 行）——因为它在"形状一出现"就收工，采样点只有几秒；两个数说的是同一个刷屏速率，差别只在采样时长。读数一栏里 `$NLINES` 只是被打印出来的旁证，不参与判定 |
| 引擎池 URL | `jdbc:mysql://localhost:3306/?serverTimezone=UTC…` | 斜杠后**直接跟问号**＝库名为空，只有"没读到 `Z_BASE_DB_SCHEDULE_DATABASE`"产得出这一串 ⇒ 判据钉它而不是钉 HTTP 码。B12c 再拿改后那份清单的日志当反向对照（0 命中），证明这把尺两边可区分 |

这条形状本身是**产品行为**，不是彩排的错：连不上库时既不 fail-fast 也不退出，而是无声刷日志。
要不要改成"引擎池初始化失败即退出（让集群去重启）"是独立的一格，本轮只把它记成读数——
没有判据支撑的启动策略改动，比不改更糟。

### 11.5 compose 侧：把"静默起一个连不上库的容器"改成当场拒绝

三种模式的 compose 文件原先都写 `${DB_HOST:-mysql}`，而**没有任何一种模式自带叫 `mysql` 的服务**
（Mode 1 只有一个容器），`Makefile` 里也是 `DB_HOST ?= mysql` ⇒ `make k8s-apply` 会把一个不可达的宿主名渲染进
ConfigMap，绕过 `k8s-apply.sh` 自己的 `${DB_HOST:?}` 守卫。现在：DB_HOST **没有默认值**，compose 文件里换成
带消息的 `${DB_HOST:?…}`，模板 `env/.env.example` 的 `DB_HOST=` 留空，入口脚本显式 `--env-file env/.env`
（compose 只自动读 `deploy/.env`，本仓的模板在 `deploy/env/` 下——不指过去的话 `DB_*` 永远读不到）。
B13 两边都测：不给 `DB_HOST` ⇒ compose `config` 非 0 且消息点名它；给了 ⇒ rc=0 且**两个池**从同一份坐标渲染出来
（`Z_BASE_DB_SCHEDULE_HOST` 与 `SPRING_DATASOURCE_URL` 的串都在输出里）。
这套 `:?` 语义在 250 的 `docker-compose` 上实测过双向，不是照文档抄的。

### 11.6 三条自伤：全是量具的错，而且都是"照猜的形状写判据"

1. **B11 第一遍红**（`Engine loaded 0 jobs into ring` 刷 90 s）：不是产品缺陷，是彩排漏了 `start`——
   `add()` 强制 `trigger_status=0`，而 `listRunning()` 只认 `=1`。修法不是把判据调松，而是把这条语义**也**钉成判据（B10b/B10d）。
2. **B11b 第一遍红**（"库里有结论行却没有装载行"）：装载有**两条不同的日志串**——
   `start()` 走 `registerJob()` 打 `[Leader] jobId=N 已挂入时间轮`，`"Engine loaded N jobs"` 是 15 s 一次的
   reconcile 才打的，而我的轮询在第一个结论行落下时就 break 了。现在两形都收，且必须至少等到 reconcile 那一形一次。
3. **B12 第一遍红**：预期 503，真实是 000（见 11.4）。负对照的"应该坏成什么样"不能靠推理，
   先取证（240 s 时间线）再写判据，这次是照这条顺序做对的。

### 11.7 这一格没做的

1. **`make dev` 仍然需要一个外部 MySQL**：镜像里没有 H2 那条路可走——仓库里没有 `schema.sql` / `data.sql`，
   建表脚本只面向 MySQL；`DevDataSourceConfig` 是 `dev` profile 下给 IDE 里跑 admin 用的，不覆盖容器入口。
   `deploy/README.md` 那句"本地试用：默认用 H2 内存模式"已删，但**"零依赖试用"这个能力本身还是没有的**：
   要兑现，得决定是"容器里带一个初始化 Job"还是"文档明确说必须先有库"。
2. **Mode 2/3 的前端是装饰**：`_frontend` 那个桩里一次 API 调用都没有（§前端另有一格），
   所以"nginx 反代到后端"在 Mode 2/3 只能验到 `/api/actuator/health` 这类直接路径，验不到页面真的能用。
3. **真集群 apply 仍未做**（11.2）。另外这台机器只有 `docker-compose` 二进制、没有 `docker compose` 插件
   （Docker 20.10.21），而入口脚本写的是后者 ⇒ 在老一点的管理机上 `make dev` 会死在命令行本身。本轮只在 250
   上取证，没有为它加兼容分支。
4. 线上 `oc` 库的 `permission` 列还没逐列对过账（§9 那条链在演示库上验的）。
