# 项目总览

## 1. 项目简介

z-schedule 是 z-opc-foundation 的**分布式任务调度中枢**（对标 XXL-Job），以 **Spring Boot Starter** 的形式嵌进宿主应用：
`z-schedule-spring-boot-starter` 把调度引擎（`LeaderElector` 抢 DB 锁选主并续约、`ScheduleRing` 时间环排期、
`JobScheduleEngine` 到点触发并把执行结论落库）连同 7 个 REST Controller 一起装进宿主的 Spring 上下文，
宿主**不必再跑一个独立的 admin 进程**；`z-schedule-admin` 则是自带 React 外壳的单体演示应用，
`java -jar` 起来就是一个能点的调度中心。

给它用的人：需要在自己应用里做定时任务编排的团队（多环境发布、批量作业、周期对账）。

**能力边界必须先钉一句**：当前版本的触发全部发生在**承载调度引擎的那个 JVM 内**
（`JobTriggerServiceImpl` 从 `ApplicationContext` 取 handler bean），
"按注册地址把任务 POST 给远端执行器"这条**出站**派发链**尚未实现**；
已经打通的是**入站**那一半（外部应用 POST `/executor/beat` 注册进 `z_schedule_job_registry`）。

**技术栈：** Java 8 · Spring Boot 2.7.18（父链 `z-boot-parent` → `z-boot-dependencies` 地板供给）·
MyBatis-Plus 3.5.7 + Druid 1.2.23 + MySQL 8 / H2 · 自研 `CronExpression` 与 10 个 `ExecutorRouter` ·
React 19 + Ant Design 6 + Vite 6 前端 · Docker / docker-compose / k8s 清单。

## 2. 模块架构

```
z-schedule/
├── pom.xml                          # 根聚合 POM：parent z-boot-parent:1.1.0，<revision>=1.0.6，常开 flatten(oss)
├── z-schedule-core/                 # 无 Spring 依赖的底层：model / enums / handler / route(10 个 router) / CronExpression
├── z-schedule-spring-boot-starter/  # 调度中枢 + HTTP 层：cluster(4) / controller(7) / service(9) / domain(6+6) / auth / filter / config
├── z-schedule-admin/                # 可启动演示应用（永不上 Maven Central）
├── _frontend/                       # 两个 npm 工程：z-schedule-component（library）+ z-schedule-suit（运行壳）
├── deploy/                          # 三种模式 + k8s（Dockerfile.backend / Dockerfile.frontend / Makefile / 3 份 compose / k8s 00-05）
└── _doc/                            # 文档收口目录
```

`z-schedule-core`（包名 `com.zifang.z.schedule.core`）：

- `model/` — `JobInfo` / `JobGroup` / `JobLog` / `JobRegistry` / `User` / `ReturnT`
- `enums/` — 7 个枚举：`ExecutorBlockStrategyEnum` / `ExecutorRouteStrategyEnum` / `GlueTypeEnum` /
  `JobStatusEnum` / `MisfireStrategyEnum` / `TriggerCodeEnum` / `TriggerTypeEnum`
- `handler/IJobHandler.java` — 业务方实现的作业入口接口
- `route/` — `ExecutorRouter` 接口 + `route/impl/` 下 **10 个**实现
- `util/CronExpression.java` — 自研 Cron 解析
- `config/ScheduleProperties.java` — `z.schedule.*` 的载体

`z-schedule-spring-boot-starter`（包名 `com.zifang.z.schedule.web`）：

- `cluster/` — `LeaderElector` / `ScheduleRing` / `JobScheduleEngine` / `LogCleanupScheduler`
- `controller/` — 7 个 Controller（见 §4）
- `service/` + `service/impl/` — 8 个 Service 与 8 个实现（`impl/` 下另有 `DoMapper` 一支 MyBatis Mapper）
- `domain/entity/` + `domain/mapper/` — 6 个 DO 与 6 个 Mapper（对应 6 张表）
- `auth/` — `LoginSession` / `LoginSessionStore` / `GroupAccess`
- `filter/TokenAuthFilter.java`
- `config/ZScheduleAutoConfiguration.java` — 自动装配入口

## 3. 核心功能

### 3.1 选主（Leader）

- `LeaderElector` 用单行表 `z_schedule_job_leader`（固定 `id=1`）作行锁竞争目标，
  任期 `LEADER_TTL_SECONDS = 30` 秒；续约成功时写 `expire_time = NOW() + 30s`，
  续约由 `@Scheduled(fixedDelay = ELECT_INTERVAL_MS)` 驱动，失主即降级为 follower。
- `@PreDestroy` → `stepDown()` 主动让位：否则优雅关停与崩溃关停没有区别，接管方必须等满 30s 租约才选得出主。

### 3.2 排期（时间环）

- `ScheduleRing` 维护时间环：`push(jobId, triggerTimeMs)` / `remove` / `poll`，超窗的落 overflow 槽。

### 3.3 触发（引擎）

- `JobScheduleEngine` 组合 4 个池：`schedulePool`（tick，1 线程）/ `persistPool`（落库，1 线程）/
  `fastPool`（快任务，默认上限 200）/ `slowPool`（慢任务，默认上限 200，慢任务阈值 5000ms）。
- **tick 线程不做任何 I/O**，`trigger_last_time` / `trigger_next_time` 由独立 persist 线程批量落库。
- 每个任务一条串行链，避免同一任务并发触发。

### 3.4 日志清理

- `LogCleanupScheduler` 按 `z.schedule.logRetentionDays`（缺省 30）周期清理调度日志。

### 3.5 任务 / 执行组 / 日志管理

- `JobInfoController`（`/jobinfo`）：增删改查、启停、手动触发、下次触发时间预览。
- `JobGroupController`（`/jobgroup`）：分组增删改查、注册节点列表。
- `JobLogController`（`/joblog`）：列表 / 详情 / 执行日志 / 清空，**按组收口鉴权**。

### 3.6 执行器入站协议

- `ExecutorCallbackController`（`/executor`）：`POST beat`（心跳注册）、`POST run`（记一行"已下发"并回 `logId`）、
  `POST callback`（回写 `handle_*` 三列）、`POST kill`、`GET log`、`GET activeCount`。

### 3.7 GLUE 在线改脚本

- `GlueController`（`/glue`）：`POST save` / `GET get` / `GET versions`。

### 3.8 仪表盘

- `ScheduleDashboardController`：概览、`stats`、`scheduleRecords`、`executorLoad`、`successRateTrend`。

### 3.9 用户、登录与鉴权

- `UserController`（`/user` 与 `/api/schedule/user` 两条等价路径）：用户 CRUD + `POST login` / `logout`；
  会话身份由 `LoginSessionStore` 管，另有共享密钥双轨。
- `TokenAuthFilter` 的 `urlPattern=/*`：除 `/`、`/index.html`、`/favicon.ico`、`/error`、`/user/login`
  与静态前缀 `/assets/`、`/static/`、`/public/` 外全部校验；
  `accessToken` 来源优先级：请求参数 `accessToken` > 请求头 `X-Access-Token`。
- **不配 `z.schedule.accessToken` ⇒ `/api/schedule/**` 与 `/dashboard`、`/actuator` 对网络内任何人敞开**，只打一条 warn。
- **根级裸路径（`/jobinfo`、`/jobgroup`、`/joblog`、`/glue`、`/user`）默认关闭**
  （`z.schedule.expose-bare-paths`，默认 `false`）：它们挂在根级，而**宿主的登录闸门按路径前缀匹配**
  （z-opc 那种聚合进程只挡 `api` 那一段）⇒ 根级裸路径不在射程内，匿名直达业务层，含写端点。
  闸门只拦**匿名**请求（会话令牌/共享密钥两条分支在它之前就放行了）；
  `/executor/**`（M2M）与 `/dashboard*` 不受它影响。独立部署的 `z-schedule-admin` 显式置
  `z.schedule.expose-bare-paths=true`（裸路径是它的正式 API 形状）。

### 3.10 路由策略库（已发布 API，但当前不参与派发）

- `z-schedule-core` 的 `route/impl/` 下 **10 个** `ExecutorRouter` 实现：
  `RoundRobinRouter` / `RandomRouter` / `ConsistentHashRouter` / `LruRouter` / `LfuRouter` /
  `FirstRouter` / `LastRouter` / `BusyoverRouter` / `FailoverRouter` / `ShardingBroadcastRouter`。
- 但派发路径一次都没问过它们：任务上的 `executor_route_strategy` 只被存下来、被界面显示出来，
  **不参与"谁执行"**——`JobTriggerServiceImpl` 只从当前 JVM 的 `ApplicationContext` 取 bean。
  配 `SHARDING_BROADCAST` 的任务当前只执行 1 次（`broadcastTotal=1` / `broadcastIndex=0`）。
- 仓内实测：主代码 grep `RestTemplate` / `HttpClient` / `HttpURLConnection` 命中 0 ⇒ **无任何出站 HTTP 能力**。

## 4. 对外集成 / 上下游

**上游（谁调它）：**

- 业务方在自己的 Spring Boot 应用里 import `io.github.yuku123:z-schedule-spring-boot-starter`，
  实现 `IJobHandler` 即可；starter 经 `META-INF/spring.factories` 自动装配。
- 外部执行器经 `/executor/beat` 等入站端点注册与回报。
- 前端 SPA（`_frontend/z-schedule-suit`）直连各 Controller 路径。

**下游（本仓调谁）：**

- **MySQL**：`ZScheduleAutoConfiguration extends ModuleDataSourceTemplate`，
  `dataSourceSchedule` 只读 `z.base.db.schedule.*`（host / port / database / username / password /
  initial-size / min-idle / max-active / max-wait / connect-timeout-millis / socket-timeout-millis / disabled）。
  **注意**：`spring.datasource.*` 建的是**另一个**池（给 actuator 的 db 健康检查用），改它对引擎吞吐无关。
- **本地文件系统**：`/glue` 的脚本版本落库，宿主侧读取。

**HTTP 边界（实测）：**

| 边界 | 值 | 出处 |
|---|---|---|
| 本地直跑端口 | `8080`、context-path 为空 | `application.yml` 从没设过 `server.*` |
| 容器端口 | `18086` + context-path `/meta` | `deploy/Dockerfile.backend` 的 `ENV SERVER_PORT=18086` / `ENV SERVER_SERVLET_CONTEXT_PATH=/meta`，以及 `EXPOSE 18086` |
| compose 映射 | `18086:18086` | `deploy/docker-compose.yml` |
| k8s 容器端口 | `18086` | `deploy/k8s/01-deployment-backend.yaml`（ConfigMap 同源） |

**REST 前缀（实测）：**

| 路径 | Controller | 主要端点 |
|---|---|---|
| `/jobinfo` | `JobInfoController` | `list`、`get`/`{id}`、`add`、`update`、`remove`、`start`、`stop`、`trigger`、`nextTriggerTime` |
| `/joblog` | `JobLogController` | `list`、`get`/`{id}`、`executionLog`、`clear` |
| `/jobgroup` | `JobGroupController` | `list`、`get`/`{id}`、`add`、`update`、`remove`、`registryNodes` |
| `/executor` | `ExecutorCallbackController` | POST `beat`/`run`/`kill`/`callback`、GET `log`/`activeCount` |
| `/glue` | `GlueController` | POST `save`、GET `get`/`versions` |
| `/user` | `UserController` | `list`、`add`、`update`、`remove`、POST `login`/`logout` |
| `/dashboard`、`/api/schedule`、`/schedule` | `ScheduleDashboardController` | `""`、`stats`、`scheduleRecords`、`executorLoad`、`successRateTrend` |

（仪表盘这一个 Controller 同时挂在 3 条路径上，后两条是给 vite dev proxy 与前端 SPA 用的别名。）

**数据表（6 张）**：`z_schedule_job_info` / `z_schedule_job_log` / `z_schedule_job_group` /
`z_schedule_job_registry` / `z_schedule_job_leader` / `z_schedule_user`；
建表脚本 `_doc/002_deploy/init/z-schedule.sql` 全部 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，非注释行里 0 条 DROP。

## 5. 技术特点

1. **001_arch 是唯一强制桶之外，本仓文档按八类编号收口**：001_arch（admin 说明）、002_deploy（建表 SQL）、
   003_script（发布脚本）、005_testing（e2e 量具）、008_troubleshooting（踩坑记录）共 5 个桶。
2. **选主靠 DB 行锁，不引入 ZooKeeper / etcd**：`z_schedule_job_leader` 单行 + 30s 租约 + 主动 `stepDown()`，
   让"优雅关停"与"崩溃关停"对接管方是一回事。
3. **tick 线程零 I/O**：`trigger_last_time` / `trigger_next_time` 由独立 persist 线程批量落库，
   把排期环的延迟与数据库写入解耦。
4. **双数据源的口径要分清**：`dataSourceSchedule`（引擎池，只读 `z.base.db.schedule.*`）与
   `spring.datasource`（Boot 自己的池，给 actuator 探针用）；改后者对吞吐无关。
5. **自带 `@EnableScheduling`，因此宿主要给 `spring.task.scheduling.pool.size >= 2`**：
   Leader 续约（30s）与周期 reconcile（15s）都挂在 `@Scheduled` 上，只给 1 条调度线程会互相排队。
6. **dev profile 不是"零依赖可玩"**：H2 里没有任何表（admin 无 `schema.sql`）⇒ 外壳 `/` 与 `/actuator/health` 是 200，
   而 `/jobinfo/list`、`/dashboard/stats` 实测 500（table not found）。
7. **i18n 子系统整体未接线**：`i18n/messages_zh_CN.properties` 与 `messages_en.properties` 各 6 个 key，
   但没有任何 bean 去加载这两个资源包，6 个 key 在任何 `.java` 里零引用；用户看到的中文是写死在代码里的。
8. **admin 永不上 Maven Central**：它设 `maven.deploy.skip=true`，`<parent>` 是 `spring-boot-starter-parent:2.7.12`
   （不继承本 pom 的 central profile），且在 `central-publishing-maven-plugin` 里被显式列进 `excludeArtifacts`
   —— 三重机制。产物只作 Docker 镜像源 / 本地 `java -jar` 演示。

## 6. 目录结构

```
z-schedule/
├── pom.xml                                    # 根聚合 POM：<revision>1.0.6</revision>，4 个 module
├── z-schedule-core/src/main/java/com/zifang/z/schedule/core/
│   ├── model/                                 # JobInfo / JobGroup / JobLog / JobRegistry / User / ReturnT
│   ├── enums/                                 # 7 个枚举（阻塞/路由/过期/GLUE/状态/触发码/触发类型）
│   ├── handler/IJobHandler.java               # 业务方作业入口接口
│   ├── route/ExecutorRouter.java              # 路由策略接口
│   ├── route/impl/                            # 10 个实现：RoundRobin / Random / ConsistentHash / Lru / Lfu /
│   │                                          #   First / Last / Busyover / Failover / ShardingBroadcast
│   ├── util/CronExpression.java               # 自研 Cron 解析
│   └── config/ScheduleProperties.java         # z.schedule.* 的载体
├── z-schedule-spring-boot-starter/src/main/java/com/zifang/z/schedule/web/
│   ├── cluster/                               # LeaderElector / ScheduleRing / JobScheduleEngine / LogCleanupScheduler
│   ├── controller/                            # 7 个 Controller
│   ├── service/ + service/impl/               # 8 个 Service 与 8 个实现（另有 DoMapper 一支 Mapper）
│   ├── domain/entity/ + domain/mapper/        # 6 个 DO 与 6 个 Mapper（6 张表）
│   ├── auth/                                  # LoginSession / LoginSessionStore / GroupAccess
│   ├── filter/TokenAuthFilter.java
│   └── config/ZScheduleAutoConfiguration.java # @ComponentScan + @EnableScheduling + @MapperScan + DataSource
├── z-schedule-spring-boot-starter/src/main/resources/
│   ├── META-INF/spring.factories              # 自动装配入口
│   └── i18n/                                  # messages_zh_CN.properties / messages_en.properties（当前未接线）
├── z-schedule-admin/src/main/java/com/zifang/z/schedule/admin/
│   ├── ZScheduleAdminApplication.java         # 演示应用启动类
│   ├── DevDataSourceConfig.java               # dev profile 的 H2 替身
│   ├── DemoJobHandler.java                    # 演示作业
│   └── config/ScheduleWebMvcConfig.java
├── z-schedule-admin/src/main/resources/
│   ├── application.yml                        # 数据源 / actuator 探针 / z.base.db.schedule.max-active
│   ├── application-dev.yml                    # dev profile（H2）
│   └── application-local.yml.example          # 本机私密配置的模板（真值文件 gitignored）
├── _frontend/
│   ├── z-schedule-component/                  # library 工程 @yuku123/z-schedule-component 0.1.2
│   └── z-schedule-suit/                       # 运行壳工程 @yuku123/z-schedule-suit 0.0.0
├── deploy/
│   ├── Dockerfile.backend                     # 后端镜像：ARG JAR_FILE=z-schedule-admin/target/*-exec.jar（通配）
│   ├── Dockerfile.frontend                    # 前端镜像（nginx）
│   ├── nginx.conf.template                    # 反代模板
│   ├── Makefile                               # dev / split / cluster / build / push / k8s-apply
│   ├── docker-compose.yml / .split.yml / .cluster.yml
│   ├── k8s/00-namespace.yaml … 05-ingress.yaml
│   └── bin/                                   # start-mode1/2/3.sh / build-images.sh / k8s-apply.sh
└── _doc/
    ├── 001_arch/01-admin.md                   # 演示应用定位、本地启动前置、鉴权面、探针面
    ├── 002_deploy/init/z-schedule.sql         # 6 张表的建表脚本
    ├── 003_script/                            # deploy_maven_center.sh / install-settings.sh
    ├── 005_testing/e2e/                       # 台账 README.md + 20 支分档臂 + bootstrap_mysql.sh / tally_surefire.py
    └── 008_troubleshooting/01-pool-timeout-measurement.md
```

**测试**：`@Test` 静态计数 `z-schedule-core` 45 + `z-schedule-spring-boot-starter` 284 + `z-schedule-admin` 6 = **335**；
绝大多数用例走 H2，不需要外部 MySQL。

**部署形态**：`deploy/Makefile` 的 6 个目标对应 3 种模式 —— `make dev`（Mode 1 合体：单容器，jar 内嵌前端）、
`make split`（Mode 2 分体：前端 nginx + 后端 jar）、`make cluster`（Mode 3 集群：1 前端 + N 个后端），
另有 `make build` / `make push` / `make k8s-apply`。
