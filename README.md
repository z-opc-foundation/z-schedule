# z-schedule

> 分布式任务调度中枢（对标 XXL-Job）—— Leader 选主 + 时间环排期 + 到点触发，以 **Spring Boot Starter** 的形式嵌进宿主应用

一人公司基座的调度中心：`z-schedule-spring-boot-starter` 把调度引擎（`LeaderElector` 抢 DB 锁选主并续约、
`ScheduleRing` 时间环排期、`JobScheduleEngine` 到点触发并把执行结论落库）连同 7 个 REST Controller 直接装进宿主
的 Spring 上下文，宿主不必再跑一个独立的 admin 进程；`z-schedule-admin` 则是自带 React 外壳的单体演示应用，
`java -jar` 起来就是一个能点的调度中心。

**读之前先钉一句边界**：当前版本的触发全部发生在**承载调度引擎的那个 JVM 内**（`JobTriggerServiceImpl`
从 `ApplicationContext` 取 handler bean），"按注册地址把任务 POST 给远端执行器"这条**出站**派发链尚未实现
——详见下面「能力边界」。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-schedule` |
| **Maven 坐标** | `io.github.yuku123:z-schedule:${revision}`（聚合）· `-core` · `-spring-boot-starter` |
| **当前版本** | `1.0.6`（根 POM `<revision>`，CI-friendly versions + flatten-maven-plugin `oss` 模式） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘） |
| **Maven Central** | 已发布：`z-schedule` / `z-schedule-core` / `z-schedule-spring-boot-starter` 的 **1.0.4 / 1.0.5 / 1.0.6** 从 repo1 取 `.pom` 均回 200；`z-schedule-admin` 三个版本全 404（设计上不发布） |
| **默认端口** | 本地直跑 **8080**、context-path 为空（`application.yml` 从没设过 `server.*`）；容器面才是 `18086` + `/meta` |
| **运行口径** | Java 8（口径由父链下发，根 POM 已删 `maven.compiler.*`）· Spring Boot **2.7.18**（由地板 `z-boot-dependencies` 供，根 POM 已删 `spring-boot.version` 键） |
| **最近更新** | 2026-09-30 |

⚠ 关于"版本单一来源"：根 `<revision>` 只管聚合/core/starter 三件。`z-schedule-admin` 有自己的
`<version>1.0.0</version>`（**不跟 revision 走**），产物是 `z-schedule-admin/target/z-schedule-admin-1.0.0-exec.jar`；
它另用一条 `<z-schedule.version>` 属性（现值 `1.0.6`）来消费本仓 starter —— 抬版时这两处都得对上，
抄死版本号会怎么坏，五种形状实测见 [`_doc/005_testing/e2e/README.md`](_doc/005_testing/e2e/README.md) §14。

---

## 🎯 能力清单

每条都能对应到 `z-schedule-spring-boot-starter/src/main/java/com/zifang/z/schedule/web/` 里的实现：

| 能力 | 入口 | 说明 |
|------|------|------|
| 选主 | `cluster/LeaderElector` | 单行表 `z_schedule_job_leader`（固定 `id=1`）作行锁竞争目标，租约 TTL **30s**，`@Scheduled` 续约，失败即降级 follower；`@PreDestroy` → `stepDown()` 主动让位，接管方不必等满租约 |
| 排期 | `cluster/ScheduleRing` | 时间环：`push(jobId, triggerTimeMs)` / `remove` / `poll`，超窗落 overflow 槽 |
| 触发 | `cluster/JobScheduleEngine` | 时间轮 + 快/慢双线程池 + 每任务串行链；**tick 线程不做任何 I/O**，`trigger_last_time/trigger_next_time` 由独立 flush 线程批量落库 |
| 日志清理 | `cluster/LogCleanupScheduler` | 按 `z.schedule.logRetentionDays` 周期清理 |
| 任务管理 | `controller/JobInfoController` | 增删改查、启停、手动触发、下次触发时间预览 |
| 执行组 | `controller/JobGroupController` | 分组增删改查、注册节点列表 |
| 调度日志 | `controller/JobLogController` | 列表/详情/执行日志/清空，按组收口鉴权 |
| 执行器入站协议 | `controller/ExecutorCallbackController` | `beat` 心跳、`run` 记一行"已下发"并回 `logId`、`callback` 回写 `handle_*` 三列、`kill`、`log`、`activeCount` |
| GLUE 在线改脚本 | `controller/GlueController` | 保存 / 读取 / 版本列表 |
| 仪表盘 | `controller/ScheduleDashboardController` | 概览、`stats`、`scheduleRecords`、`executorLoad`、`successRateTrend` |
| 用户与登录 | `controller/UserController` + `auth/LoginSessionStore` | 用户 CRUD、login/logout，会话身份与共享密钥双轨 |
| 鉴权 | `filter/TokenAuthFilter` | `urlPattern=/*`，除 `/`、`/index.html`、`/favicon.ico`、`/error`、`/user/login` 与静态前缀 `/assets/`、`/static/`、`/public/` 外全部校验 |
| 路由策略库 | `z-schedule-core` `core/route/impl` | **10 个** `ExecutorRouter` 实现（轮询/随机/一致性哈希/LRU/LFU/首个/末个/忙转移/故障转移/分片广播） |

自动装配类是 `ZScheduleAutoConfiguration`（`META-INF/spring.factories`，Boot 2.7 仍读这一份），
`@ComponentScan("com.zifang.z.schedule.web")` + `@EnableScheduling` + `@MapperScan` 挂到
`sqlSessionFactorySchedule`。它自带 `@EnableScheduling`，所以宿主记得
`spring.task.scheduling.pool.size >= 2`（Leader 续约 30s 与周期 reconcile 会互相排队，admin 的 yml 就是设成 2 的）。

### 能力边界（不要照"分布式"两个字expect 远端派发）

`z-schedule-core` 里 10 个 router 是**已发布 API，不删**，但派发路径一次都没问过它们。本仓根目录实测：

- `grep -rn "JobGroupServiceImpl\.route" . --include=\*.java | grep -c .` ⇒ **0**（含 `src/test` 零调用方）
- `grep -rn "RestTemplate\|HttpClient\|openConnection\|HttpURLConnection" */src/main/java | grep -c .` ⇒ **0**（无任何出站 HTTP 能力）

⇒ 任务上的 `executor_route_strategy` 只被存下来、被界面显示出来，**不参与"谁执行"**；
`JobTriggerServiceImpl` 只从当前 JVM 的 `ApplicationContext` 取 bean。已经通的是**入站**那一半
（外部应用 POST `/executor/beat` 注册进 `z_schedule_job_registry`）；缺的是出站那一半。
配 `SHARDING_BROADCAST` 的任务当前**只执行 1 次**且 `broadcastTotal=1 / broadcastIndex=0`
（`JobTriggerServiceImplBehaviorTest` 钉着；此前 `broadcastTotal` 是默认值 0，按分片写的 handler 会一行都不做）。
取证与三处联动改造点记在 [`_doc/005_testing/e2e/README.md`](_doc/005_testing/e2e/README.md) §7。

**i18n 子系统整体未接线** —— 配 `z.schedule.i18n=en` 没有任何效果，接口文案恒为中文。四条独立实测：

- `getI18n()` 在 `z-schedule-core` / `z-schedule-spring-boot-starter` / `z-schedule-admin`
  的**生产代码里零调用**，只有 `JobInfoTest` 读它；
- 全仓 grep `MessageSource` / `LocaleResolver` / `basename` 在 `*.java` 与 `*.yml` 里
  **零命中** —— 没有任何 bean 去加载那两个资源包；
- `i18n/messages_zh_CN.properties` 与 `messages_en.properties` 各 6 个 key，
  这 6 个 key 在任何 `.java` 里**零引用**；
- 用户实际看到的中文是**写死在代码里**的：`JobInfoServiceImpl:98/144/152/213`
  （`"Cron表达式格式错误: "`、`"请先停止任务再修改Cron表达式"`、`"请先停止任务再删除"`）、
  `TriggerCodeEnum:31`（`EXECUTOR_BLOCKED(503, "执行器阻塞")`）。

资源包与代码里的文案并**不是 1:1**：`schedule.job.running=请先停止任务` 对应的代码文案是
`请先停止任务再修改Cron表达式` / `请先停止任务再删除`（多了后缀）；而
`schedule.alarm.success` / `schedule.alarm.fail` 对应的告警功能本身是桩 ——
`DefaultAlarmService:69` 的日志就写着「配置了告警邮箱，但内置实现未接入邮件通道」，
直接置 `alarmStatus=3`。

⇒ 要真接上 i18n，需要先定：语言按配置固定还是按 `Accept-Language` 协商、回退到哪种语言、
6 条文案以哪一份为准；且改完会动到现有断言（`JobInfoServiceImplH2Test:379`、
`JobInfoControllerGroupAccessTest:199` 都在断言中文串）。这是产品决策，未擅自改。

---

## 🏗️ 项目结构

```
z-schedule/
├── pom.xml                          # 根聚合 POM：继承 z-boot-parent:1.0.21，<revision>=1.0.6，常开 flatten(oss)
├── z-schedule-core/                 # 无 Spring 依赖的底层：entity/model、10 个 ExecutorRouter、
│                                    #   枚举（阻塞/路由/过期/GLUE/状态/触发码）、IJobHandler、CronExpression、
│                                    #   ScheduleProperties（z.schedule.* 的载体）
├── z-schedule-spring-boot-starter/  # 调度中枢 + HTTP 层：cluster(Leader/Ring/Engine/LogCleanup)、
│                                    #   web/controller(7 支)、service(+impl)、domain/entity+mapper(6 张表)、
│                                    #   auth、filter、config(ZScheduleAutoConfiguration)
├── z-schedule-admin/                # 可启动演示应用（永不上 Maven Central）
│                                    #   只有 3 个类：ZScheduleAdminApplication / DevDataSourceConfig / DemoJobHandler
├── _frontend/                       # 容器目录（自身无 package.json），两个 npm 项目经 `file:` 互相消费
│   ├── z-schedule-frontend/                  # @yuku123/z-schedule-frontend（SPA 外壳，React 18 + Vite 5）
│   └── z-schedule-frontend-component/        # @yuku123/z-schedule-frontend-component（library mode）
├── deploy/                          # 三种模式 + k8s（见文末「部署」）
└── _doc/                            # 文档，见文末「文档目录」
```

`z-schedule-admin` **确实在** `<modules>` 里（reactor 第 `[4/4]` 个模块，享受统一构建），
"不发"与"不在 reactor"是两件事：它设了 `maven.deploy.skip=true`，且 `<parent>` 是
`spring-boot-starter-parent:2.7.12`（不继承本 pom 的 `central` profile），另外在 `central-publishing-maven-plugin`
里被显式列进 `excludeArtifacts`（该插件不认 `maven.deploy.skip`）。三重机制都实测过，产物只作 Docker 镜像源或本地
`java -jar` 演示。

顶层 `<dependencyManagement>` 把自家三个坐标逐条钉 `${project.version}`：继承来的 fleet 把它们钉在 repo1 的发布件，
仓内一旦领先于发布件，不改写就会把旧兄弟字节码打进新包里。

---

## 🔧 技术栈

| 层级 | 技术（全部取自 pom / 源码实测） |
|------|------|
| 语言 / 运行时 | Java 8（class-file major 52；口径由父链 pluginManagement 下发） |
| 框架 | Spring Boot 2.7.18（地板 `z-boot-dependencies` 供；**仅 `z-schedule-admin` 自己的 parent 仍钉 2.7.12**） |
| 父链 | `z-boot-parent:1.0.21` → 地板 `z-boot-dependencies:1.0.20` + 兄弟仓权威表 `z-boot-fleet:1.0.1`（三件均在 repo1，现读其 pom 得下面各格） |
| 持久层 | MyBatis-Plus 3.5.7（`mybatis-plus-extension`）+ Druid 1.2.23（两格均由地板供） |
| 数据库 | MySQL 8（`com.mysql:mysql-connector-j:8.0.33`，刻意留 8.0.33 不跟地板的 8.4.0；带 Boot 那条 `protobuf-java` exclusion）/ H2（dev profile 与单测） |
| 日志 | log4j2（api/core/jul/slf4j-impl 钉 **2.17.2**，`log4j-bom` 管 slf4j2-impl=2.20.0）+ slf4j |
| 工具库 | `z-util-core`、`z-boot-web-starter`、`z-boot-datasource-starter`（`ModuleDataSourceTemplate` 建 `dataSourceSchedule`） |
| 接口文档 | 无（本仓没引 springdoc/Knife4j，别按 z-ctc 那套找 `/doc.html`） |
| 前端 | React 18 + Ant Design 生态 + Vite 5（`_frontend/`，独立 npm 工程） |
| 构建 | Maven（flatten-maven-plugin `oss` + surefire **本地钉 3.5.4**）· frontend-maven-plugin 1.15.0（node v18.17.0 / npm 9.6.7）· Docker / k8s |

---

## 🚀 快速开始

### 编译

```bash
mvn clean install -DskipTests
```

第三方版本一律由 `z-boot-parent` → `z-boot-dependencies`（地板）+ `z-boot-fleet`（兄弟仓权威表）供给，
模块 POM 里不应再出现字面版本钉；若报找不到版本，先确认本地/镜像能解析到
`io.github.yuku123:z-boot-parent:1.0.21`。

### 作为依赖接入一个 Spring Boot 应用

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-schedule-spring-boot-starter</artifactId>
    <version>1.0.6</version>
</dependency>
```

**生效的配置前缀只有 `z.schedule.*` 与 `z.base.db.schedule.*`**：

| 前缀 | 载体 | 键 |
|------|------|-----|
| `z.schedule.*` | `@ConfigurationProperties("z.schedule")` → `ScheduleProperties` | `accessToken`（默认空）、`triggerPoolFastMax=200`、`triggerPoolSlowMax=200`、`triggerPoolSlowThreshold=5000`、`logRetentionDays=30`、`executorTimeout=0`；`i18n=zh_CN` **配了不生效**（见下方「能力边界」） |
| `z.base.db.schedule.*` | `ModuleDataSourceTemplate` 自建 `dataSourceSchedule` | `host`、`port`、`database`、`username`、`password`、`initial-size`、`min-idle`、`max-active`、`max-wait`、`connect-timeout-millis`、`socket-timeout-millis`、`disabled` |

两个容易踩的点：

1. `spring.datasource.*` 建的是**另一个**池（给 actuator 的 db 健康检查用），改它对吞吐无关 —— 引擎池是
   `dataSourceSchedule`，只读 `z.base.db.schedule.max-active`。250 真机 N=800 个 1Hz 任务 / 30s 窗口实测：
   `max-active` 20 ⇒ 316 次/s，40 ⇒ 540 次/s，80 ⇒ 808 次/s，忙连接峰值每次都正好等于上限 ⇒ 池就是那堵墙。
   admin 的 yml 因此显式给 40，但 starter 默认仍是 20，**嵌入方按自己库容量决定，抬之前先确认 MySQL 侧
   `max_connections` 有余量**。
2. admin `application.yml` 里那一段 `zschedule.*`（`admin.addresses` / `executor.appname` / `cluster.enabled` …）
   是从 xxl-job 风格样例抄来的**死配置**，本工程没有任何代码读它，不参与启动；留着只因它记着"远程派发"这个
   还没实现的意图。别照它配。

`z.schedule.accessToken` 不配 ⇒ `TokenAuthFilter` 跳过校验，整个管理面（`/jobinfo`、`/joblog`、`/jobgroup`、
`/user`…）对网络内任何人敞开，只打一条 warn。生产必须配，且**只经环境变量注入**。

### 库要先有

建表脚本 [`_doc/002_deploy/init/z-schedule.sql`](_doc/002_deploy/init/z-schedule.sql)：6 张表
`z_schedule_job_info` / `_job_log` / `_job_group` / `_job_registry` / `_job_leader` / `_user`，
全部 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`；实测非注释行里 **0 条 DROP**，
[`_doc/005_testing/e2e/bootstrap_mysql.sh`](_doc/005_testing/e2e/bootstrap_mysql.sh) 建库前会先数这一条，不为 0 直接拒。

### 本地跑起 admin（三条路，实测状态各不相同）

`application.yml` 从不设 `server.*`，所以本机直跑绑的是 **8080 + 空前缀**；`18086` 与 `/meta` 只来自
`deploy/Dockerfile.backend` 的 ENV 与 compose 的环境变量。

```bash
# 路 1：dev profile（H2）跑源码 —— 必须显式关掉引擎自带的那支同名池
cd z-schedule-admin
mvn -B spring-boot:run -Dspring-boot.run.profiles=dev \
    -Dspring-boot.run.arguments=--z.base.db.schedule.disabled=true
```

⚠ 给 Maven 传应用参数只能走 `-Dspring-boot.run.*`；把 `--server.port=18086`、`--server.servlet.context-path=/meta`
直接跟在 `mvn` 后面会被 Maven 当场拒（`Unrecognized option`，rc=1，应用一次都没起来）—— 实测见
[`_doc/005_testing/e2e/README.md`](_doc/005_testing/e2e/README.md) §17。

```bash
# 路 2：用本树 package 出的 exec jar —— 不必再给 disabled=true
mvn -q -pl z-schedule-admin -am package -DskipTests
java -jar z-schedule-admin/target/z-schedule-admin-1.0.0-exec.jar
```

`#41` 之后 starter 的 `dataSourceSchedule` / `sqlSessionFactorySchedule` 各带一支
`@ConditionalOnMissingBean(name = …)`，改成按 **bean 名**退让，所以本树构件不给那句也起得来
（实测 `Tomcat started on port(s): 50974 (http) with context path '/meta'`，前后读数成对记在 §19）。
但 `~/.m2` 里那份已发布的 1.0.6 starter 与路 1 无关 —— 路 1 不带 `-am` 时依赖由 `~/.m2` 解析，
**仍要给**那句。

```bash
# 路 3：看界面
# 前端资源基路径 /meta/ 是构建时烤进 jar 的（vite base 默认 '/meta/'），
# 不给 --server.servlet.context-path=/meta 就是白屏：页面 200、它自己声明的两条资源 404（实测见 §18）
```

**两条路给的 H2 都是空库**（admin 无 `schema.sql`）⇒ 外壳 `/` 与 `/actuator/health` 是 200，
而 `/jobinfo/list`、`/dashboard/stats` 实测 500（table not found）。**dev profile 不是"零依赖可玩"。**

连真实 MySQL 时，所有凭据必须经环境变量注入，禁止写进 yml/jar/镜像层：

| 环境变量（admin `application.yml` 读） | 用途 |
|------|------|
| `SPRING_PROFILES_ACTIVE` | `default`（MySQL）/ `dev`（H2）/ `local`（MySQL + 本机私密 yml） |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Boot 自己的池（actuator 健康检查用） |
| `Z_BASE_DB_SCHEDULE_HOST` / `_PORT` / `_DATABASE` / `_USERNAME` / `_PASSWORD` | 引擎池 `dataSourceSchedule`（Spring 宽松绑定到 `z.base.db.schedule.*`） |
| `Z_SCHEDULE_DB_MAX_ACTIVE` | 引擎池上限（admin 默认 40） |
| `ZSCHEDULE_LOG_PATH` | 日志文件路径 |

`local` profile 的本机真实值放 `z-schedule-admin/src/main/resources/application-local.yml`（gitignored，
仓内只提供 [`application-local.yml.example`](z-schedule-admin/src/main/resources/application-local.yml.example)）。

---

## 🔌 API 一览

Controller 上没有 `/api/xxx` 这种统一服务前缀，实际路径是 **context-path + 下面的类级前缀**
（本机直跑前缀为空，容器上是 `/meta`）。仪表盘额外挂在 `/api/schedule` 上，供 vite dev proxy（`/api` → `:18086`）与
前端 SPA 用。

| 路径 | Controller | 主要端点 |
|------|------------|----------|
| `/jobinfo` | `JobInfoController` | `list`、`get`/`{id}`、`add`、`update`、`remove`、`start`、`stop`、`trigger`、`nextTriggerTime` |
| `/joblog` | `JobLogController` | `list`、`get`/`{id}`、`executionLog`、`clear` |
| `/jobgroup` | `JobGroupController` | `list`、`get`/`{id}`、`add`、`update`、`remove`、`registryNodes` |
| `/executor` | `ExecutorCallbackController` | `POST beat`/`run`/`kill`/`callback`、`GET log`/`activeCount` |
| `/glue` | `GlueController` | `POST save`、`GET get`/`versions` |
| `/user` | `UserController` | `list`、`add`、`update`、`remove`、`POST login`/`logout` |
| `/dashboard`（= `/api/schedule`） | `ScheduleDashboardController` | `""`、`stats`、`scheduleRecords`、`executorLoad`、`successRateTrend` |
| `/actuator/*` | Spring Boot | `health`、`info`，以及显式打开的 `health/liveness`、`health/readiness` |

`accessToken` 来源优先级：请求参数 `accessToken` > 请求头 `X-Access-Token`。

探针面两条真实缺陷（原文读数见 [`_doc/001_arch/z-schedule-admin.md`](_doc/001_arch/z-schedule-admin.md) 与
e2e README §10）：这段配置原先写作 `spring.actuator.*`（缩进在 `spring:` 之下），而 Boot 读顶层 `management.*`
⇒ 整块死配置，`/actuator/health/liveness`、`readiness` 天生 404，而 `deploy/k8s/01-deployment-backend.yaml` 的
探针指的就是这两条；另外 Boot 自动建的 readiness 组**不含数据源**（库挂了仍回 200 UP），现在 yml 里显式把
`db` 拉进 readiness、`liveness` 故意不含 db，`show-details` 收为 `when-authorized`。

---

## 🧪 测试

```bash
rm -rf */target/surefire-reports && mvn test
```

静态数 `@Test` 注解（本树实测）：`z-schedule-core` 45 + `starter` 283 + `admin` 6 = **334**。
surefire 实跑数以现跑为准，计数只吃 `*/target/surefire-reports/*.xml`、不吃 stdout；目录没清干净会多算一类。

已知两格必须知道的：

1. **surefire 版本决定"哪些测试会跑"**。不钉版本时 Maven 3.8.7 默认 surefire 2.12.4 没有 JUnit-Platform provider，
   admin 那 6 例 jupiter 用例会被**静默跳过**（不红、不报，只是计数少一截）；本机 3.9.14 默认 3.5.4 才跑。
   2026-09-27 两机实测：同一提交 **323 vs 329**。根 POM 因此**刻意本地覆盖**成 `3.5.4`（父链下发的是 2.22.2）。
   跨机对账量具是 [`_doc/005_testing/e2e/tally_surefire.py`](_doc/005_testing/e2e/tally_surefire.py)。
2. 绝大多数用例走 H2，**不需要外部 MySQL**；真机 E2E（自建 MySQL + 逐个接口打）是另一套量具，
   静态臂 `P24_A_ONLY=1 bash _doc/005_testing/e2e/p24.sh` 不碰 docker、任何机器都能跑。

---

## 🐳 部署

```bash
cd deploy
make help            # 查看全部目标
make dev             # Mode 1 合体（单容器，jar 内嵌前端）
make split           # Mode 2 分体（前端 nginx + 后端 jar）
make cluster         # Mode 3 集群（1 前端 + N=2 后端，可调 N=5）
make build           # 本地构建前后端镜像（不 push）
make push            # 构建并 push 到 ghcr.io
make k8s-apply       # K8s 一键部署（需 INGRESS_DOMAIN 与 DB_HOST/DB_NAME）
```

三种模式各对应一份 compose：`docker-compose.yml` / `docker-compose.split.yml` / `docker-compose.cluster.yml`；
k8s 清单在 `k8s/`，按 `00-namespace` → `05-ingress` 顺序 apply；分模式启动脚本在 `bin/start-mode1.sh` ~
`start-mode3.sh`，镜像构建在 `bin/build-images.sh`，反代模板是 `nginx.conf.template`。
镜像：`Dockerfile.backend`（来自 `z-schedule-admin` 的 exec jar，`ARG JAR_FILE` 是**通配**
`z-schedule-admin/target/*-exec.jar`，不是抄死版本号的字面量）+ `Dockerfile.frontend`（nginx）。

库坐标**五项都不许有静默默认值**：`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` 用 `${V:?…}`（没写或留空都当场拒），
`DB_PASSWORD` 用 `${V?…}`（没写拒、显式留空放行 —— "无口令库"是合法形状）。这个不对称是在 250 的 compose 上实测
出来的判据，不是抄文档；理由与取证见 e2e README §11.5 / §12.3。样例只给键名：[`env/.env.example`](deploy/env/.env.example)。

⚠ 一个现在仍存在的坑：三份 compose 的镜像 tag 默认值是 `${IMAGE_VERSION:-1.0.4}`，
即 `.env` 里不设 `IMAGE_VERSION` 就会拉到 **1.0.4** 而不是当前版本 —— 部署前显式钉住。

前端外壳由 `frontend-maven-plugin` 在 `generate-resources` 跑 `_frontend/z-schedule-frontend` 的 `npm run build`，
再由 `maven-resources-plugin` 把 `dist/` 复制到 `target/classes/static/`（不写回源码目录）。

---

## 📄 License

MIT，见根 [`LICENSE`](LICENSE)（`Copyright (c) 2026 z-opc-foundation`）；根 POM `<licenses>` 同样声明 MIT License。

---

## 文档目录

本项目文档统一收口在 `_doc/` 下：

- [`_doc/001_arch/`](_doc/001_arch/) — 架构与模块说明：
  - [`z-schedule-admin.md`](_doc/001_arch/z-schedule-admin.md) — 演示应用的定位、本地启动的真实前置、鉴权面、探针面
- [`_doc/002_deploy/`](_doc/002_deploy/) — 目前为空目录（部署 SQL 走的是下面的 `004_sql/`，部署资产在仓根 `deploy/`）
- [`_doc/003_script/`](_doc/003_script/) — 运维脚本：
  - [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) — 发 Central（不可逆，需单独授权）
  - [`install-settings.sh`](_doc/003_script/install-settings.sh) — 本机 Maven settings/凭证骨架
  - [`e2e/`](_doc/005_testing/e2e/) — 端到端量具与验收档，`README.md` 是台账、`pNN.sh` 是分档的臂：
    - [`README.md`](_doc/005_testing/e2e/README.md) — 250 演练记录（可在新机重放）；§7 路由策略兑现差、§8 测试基线、
      §10 探针面、§11–§13 三种模式彩排、§14 抄死版本号、§17–§19 本地启动、§21–§22 跨机对账
    - 环境准备与跑法：[`bootstrap_mysql.sh`](_doc/005_testing/e2e/bootstrap_mysql.sh)、
      [`mysql.example.env`](_doc/005_testing/e2e/mysql.example.env)、
      [`run.sh`](_doc/005_testing/e2e/run.sh)、[`e2e.sh`](_doc/005_testing/e2e/e2e.sh)、
      [`svc_smoke.sh`](_doc/005_testing/e2e/svc_smoke.sh)、
      [`run_p20_and_restore.sh`](_doc/005_testing/e2e/run_p20_and_restore.sh)
    - 分档臂：[`p6.sh`](_doc/005_testing/e2e/p6.sh)、[`p10.sh`](_doc/005_testing/e2e/p10.sh)、
      [`p11.sh`](_doc/005_testing/e2e/p11.sh)、[`p12.sh`](_doc/005_testing/e2e/p12.sh)、
      [`p13.sh`](_doc/005_testing/e2e/p13.sh)、[`p14.sh`](_doc/005_testing/e2e/p14.sh)、
      [`p15.sh`](_doc/005_testing/e2e/p15.sh)、[`p16.sh`](_doc/005_testing/e2e/p16.sh)、
      [`p17.sh`](_doc/005_testing/e2e/p17.sh)、[`p18.sh`](_doc/005_testing/e2e/p18.sh)、
      [`p19.sh`](_doc/005_testing/e2e/p19.sh)、[`p20.sh`](_doc/005_testing/e2e/p20.sh)、
      [`p21.sh`](_doc/005_testing/e2e/p21.sh)、[`p22.sh`](_doc/005_testing/e2e/p22.sh)、
      [`p23.sh`](_doc/005_testing/e2e/p23.sh)、[`p24.sh`](_doc/005_testing/e2e/p24.sh)、
      [`p25.sh`](_doc/005_testing/e2e/p25.sh)、[`p26.sh`](_doc/005_testing/e2e/p26.sh)、
      [`p38_shapes.sh`](_doc/005_testing/e2e/p38_shapes.sh)、[`p44.sh`](_doc/005_testing/e2e/p44.sh)
    - 辅助尺与量具：[`q.sh`](_doc/005_testing/e2e/q.sh)、[`_q_digest.sql`](_doc/005_testing/e2e/_q_digest.sql)、
      [`ui_base_probe.sh`](_doc/005_testing/e2e/ui_base_probe.sh)、
      [`tally_surefire.py`](_doc/005_testing/e2e/tally_surefire.py)
- `_doc/004_skill/` — AI skill 定义（目前为空目录，暂无 skill）
- [`_doc/002_deploy/init/`](_doc/002_deploy/init/) — 建表（**本仓用的是这个非标准槽位，SQL 不在 `002_deploy/` 里**）：
  - [`z-schedule.sql`](_doc/002_deploy/init/z-schedule.sql) — 全量建表脚本（6 张表，不含 DROP）

部署面另在 [`deploy/`](deploy/)（compose / k8s / `bin/start-mode*.sh` / `Makefile`，
说明文档 [`deploy/README.md`](deploy/README.md)），前端工程在 [`_frontend/`](_frontend/)
（两个 npm 项目经 `file:` 协议互相消费，见 [`_frontend/README.md`](_frontend/README.md)）。

_Maintained by the z-opc-foundation organization._
