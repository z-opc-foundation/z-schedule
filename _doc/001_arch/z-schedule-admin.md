# z-schedule-admin

> **Standalone Demo App + Docker 镜像源 —— 永远不上 Maven Central**
>
> 详见 [`lead/005_技术架构/005_前端工程与中间件部署架构规范.md`](../../z-opc-foundation-lead/005_技术架构/005_前端工程与中间件部署架构规范.md) §2

## 它是什么

一个**最小可运行单体应用**，把 `z-schedule-spring-boot-starter` 装进来，
附上 Spring Web + Actuator + H2/Log4j2 + 内嵌前端（`src/main/resources/static/`，由
`_frontend/z-schedule-suit/dist` 注入），跑起来就是一个完整的「Z-Schedule 调度中心 UI」。

业务方**永远不会**在自己的项目里 `import io.github.yuku123:z-schedule-admin`。
它存在的目的是：

1. **本地 `java -jar` / `mvn spring-boot:run` 演示** —— 让开发者不写一行代码就能跑起来
2. **Docker 镜像素材** —— `deploy/Dockerfile.backend` 把 exec jar 打成
   `ghcr.io/yuku123/z-schedule-admin:1.0.0`，作为 006_部署方案 中 L3 中间件镜像
3. **本地部署 / 试用 / PoC** —— 中小团队不想折腾分体 / 集群，直接 `docker run` 一个容器

## 为什么不上 Maven Central

| 产物 | 谁来 import | 发 Central |
|---|---|---|
| `z-schedule-core` | 同仓 starter | ✅ |
| `z-schedule-spring-boot-starter` | **业务方 import** | ✅ |
| `z-schedule-admin` | **没人 import**，是 `java -jar` 入口 | ❌ |

中央发布只会污染搜索（`<name>` 含「Standalone」字样）+ 占用 namespace + 没任何引用方。

## 本地启动

> ⚠ 本节 2026-09-27 之前写的是"方式 A 最快，默认端口 18086，context-path /meta"。
> 那两句**都是假的**，四条命令逐条实测过（读数是 `bash p24.sh` 静态臂之外的手工跑，
> 复跑命令与原文输出见 `_doc/005_testing/e2e/README.md` §17.2）：
>
> | 命令 | 实测结果 |
> |---|---|
> | `mvn spring-boot:run --spring.profiles.active=dev --server.port=18086 …` | `Unable to parse command line options: Unrecognized option: --spring.profiles.active=dev`，rc=1，**Maven 自己就拒了**，应用从未启动（18086/8080 都没人监听） |
> | `mvn spring-boot:run`（原"方式 A"，什么都不加） | 走 `default` profile ⇒ 连 `jdbc:mysql://localhost:3306`。90 s 内日志 6820 行、`Connection refused` 命中 800 次、`Tomcat started` **0 次**，8080 与 18086 `curl` 均 rc=7（连不上） |
> | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` | `APPLICATION FAILED TO START`：`The bean 'dataSourceSchedule', defined in class path resource [.../ZScheduleAutoConfiguration.class], could not be registered. A bean with that name has already been defined`（见下面"dev profile 的两条前置"） |
> | `… -Dspring-boot.run.arguments=--z.base.db.schedule.disabled=true` | 起来了：`Tomcat started on port(s): 8080 (http) with context path ''` ⇒ **端口是 8080、context-path 是空**，`/meta/**` 实测 404 |
>
> 18086 与 `/meta` 这两个值**在本仓的任何 yml/properties 里都不存在**（`grep -rn 'server.port\|context-path' src/main/resources` 只命中注释）。
> 它们是**部署面**的值，来源各有一处设定 + 若干抄件：
> `SERVER_PORT=18086` 只在 `deploy/Dockerfile.backend` 的 `ENV` 里设过一次（k8s 的 ConfigMap 都没设它），
> `SERVER_SERVLET_CONTEXT_PATH=/meta` 则同时写在 Dockerfile 的 `ENV`、`deploy/k8s/01-deployment-backend.yaml`
> 的 ConfigMap 和三份 compose 的 env 里 ⇒ **照文档在本机 `mvn` 起的人拿不到那个地址**，而
> `deploy/docker-compose.yml` 的 healthcheck 却写死了 `http://127.0.0.1:18086/meta/...`
> （那条在容器里成立，因为 env 给过）。这几处抄件现在由 `p24.sh` 的 A16 逐字比着，漂一处即红一处。
>
> ⚠ 上面那四格的**字节**都是 `~/.m2` 里解析到的已发布件（表头那条命令没带 `-am`，见
> `_doc/005_testing/e2e/README.md` §17.1 末尾的边界说明）。第三格那条"同名 bean 必崩"在 #41
> 之后**只对这已发布件成立**：换成 `mvn -pl z-schedule-admin -am package` 出的 jar，不给旗也起得来
> （实测 08:28:47 `Tomcat started on port(s): 50974 (http) with context path '/meta'`）。
> 成对的前/后读数与为什么修完还有第三条死路，记在 §19。

### dev profile 的两条前置（缺一条就起不来）

1. **`z.base.db.schedule.disabled=true` 要不要给，取决于你跑的是哪份字节。**
   `DevDataSourceConfig`（`@Profile("dev")`）与 starter 的 `ZScheduleAutoConfiguration` 注册**同名**
   bean（`dataSourceSchedule` / `sqlSessionFactorySchedule`），而 Boot 2.1+ 默认禁止同名覆盖：

   | 你跑的 starter 字节 | 不给旗 | 依据 |
   |---|---|---|
   | 已发布的 1.0.4（= 在 `z-schedule-admin/` 里单跑 `mvn spring-boot:run`，依赖由 `~/.m2` 解析） | **崩**：`The bean 'dataSourceSchedule', defined in class path resource […], could not be registered` | §17.1 m3（06:42:54） |
   | 本树（`mvn -B -pl z-schedule-admin -am package` 出的 exec jar） | **起得来**，starter 那两支按名退让给 admin 的 H2 | §19.1 B3、§19.3 A 组、上面方式 B 的 08:28:47 |

   也就是说 admin 的 dev 路现在**两条都通**：给旗（走"starter 不注册"）或不给（走"starter 按名退让"）。
   旗**不是**万能钥匙：它一次摘掉两支 `@Bean`，而类上那句
   `@MapperScan(sqlSessionFactoryRef = "sqlSessionFactorySchedule")` 是无条件的 ⇒
   "设了 `disabled=true` 但宿主只补一支 `DataSource`" 这一格两版字节下都起不来
   （`NoSuchBeanDefinitionException: No bean named 'sqlSessionFactorySchedule' available`，§19.4-②）。
   另一格修不掉的残余：宿主用 `@Import(ZScheduleAutoConfiguration.class)` 当 `spring.factories`
   的备胎时，条件判定早于宿主自己的 `@Bean`，被拒的反而是宿主那一条（§19.4-①，已写成用例）。
2. **H2 里没有表。** dev profile 的 URL 是 `jdbc:h2:mem:zschedule_dev`，而本模块没有任何
   `schema.sql` / 建表初始化 ⇒ 外壳起得来（`/` 200、`/actuator/health` 200）但**每个数据接口 500**：
   实测 `/jobinfo/list`、`/dashboard/stats` 均 500，日志里 65 条 table-not-found。
   也就是说"dev = 零依赖能玩"目前**不成立**，它只是"进程起得来"。
   要不要把这条路补成真正可玩（自带建表，或明确必须先有库）是 #32 那一格，等拍板。

### 起得来 ≠ 看得见：还差一条 context-path（#42）

上面第 4 条那格"起来了"只量到进程与接口，界面这一层是 2026-09-27 第二次量才露出来的：
`target/classes/static/index.html`（以及 exec jar 里那份）引用的是**绝对路径** `/meta/assets/index-*.js`
—— vite 的 `base` 默认 `'/meta/'`，而 admin pom 的 frontend-maven-plugin 不传 `VITE_BASE`，
所以每次 `package` 都把这个前缀烤进 jar。进程挂在 `/` 上时，页面本身 200、它自己声明的两条资源 404
⇒ **浏览器里就是一片白**，而这两个文件在 `/assets/...` 上是 200（东西在，前缀不对）。

`bash _doc/005_testing/e2e/ui_base_probe.sh` 一次跑两条路、10 条断言（八条"该 404/200"配上"同一个文件
在剥掉前缀的路径上是 200"这一对反向对照），逐格读数与复跑命令见 `_doc/005_testing/e2e/README.md` §18。
所以本地要看界面就得把它钉成同一个前缀：`--server.servlet.context-path=/meta`（下面方式 A/B 都这么写了）。
反过来，`base` 想改，就得连 `deploy/` 那几处 `SERVER_SERVLET_CONTEXT_PATH` 一起改 —— A16 那把尺会点名。

### 方式 A：`mvn spring-boot:run`（起得来，但默认那条命令下界面是白的）

```bash
cd z-schedule/z-schedule-admin
mvn -B spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--z.base.db.schedule.disabled=true --server.servlet.context-path=/meta"
# 实测（我跑的那条多带了一句 --server.port=<空闲端口>，其余逐字相同）：
#   Tomcat started on port(s): 62442 (http) with context path '/meta'
# 路径级读数在下面「方式 B」那条测量里给（同一个 jar、同一个前缀 ⇒ 挂出来的路径一样）。
# 不带 server.port 时就是上面 m4 那格量到的 8080（那一格只差 context-path）。
#
# ⚠ 这一条里的 disabled=true 现在**摘不掉**，而且摘的原因跟 starter 有没有修没关系：
# 命令是在 admin 模块目录里单跑的，依赖由 ~/.m2 解析 ⇒ 跑的是已发布那份 starter 字节，
# 它没有 @ConditionalOnMissingBean(name=…)。#41 修的是本树，所以"不给旗也能起"目前
# 只在下面方式 B 那条（-am 从源码 package）成立。发布 + 抬版之后这一格可以重写。
```

给 Maven 传应用参数只能走 `-Dspring-boot.run.*`；把 `--server.port=…` 直接跟在 `mvn` 后面
是 Maven 的命令行而不是应用参数，它会拒（见上面的表）。端口要换就写进 arguments 里。

⚠ **`arguments` 的多个参数只能用空格分隔，不能用逗号**（这条是 2026-09-27 现场量出来的）：
`-Dspring-boot.run.arguments="--a=1,--b=2"` 会整串当一个参数送进 JVM，子进程 argv 实测是
`--z.base.db.schedule.disabled=true,--server.servlet.context-path=/meta,--server.port=59835`，
于是三条一句都没生效 —— 日志仍是 `Tomcat started on port(s): 8080 … with context path ''`，
而**进程起得来、不报任何错**。更阴的一层：那个逗号串被绑成属性值 `"true,--…"`，
它不等于 `"false"` ⇒ starter 的同名 `@Bean` 照样退让 ⇒ 连"缺 disabled 就崩"那条症状都不响。
所以这一格不能靠"起来了"当证据，得回读日志里那行 `Tomcat started … with context path`。

### 方式 B：编出 exec jar 后启动

```bash
cd z-schedule
mvn -B -pl z-schedule-admin -am package -DskipTests

# jar 名跟着 admin 模块自己的 <version> 走（当前是字面 1.0.0，**不是**根 pom 的 <revision> 1.0.4——
# 见 #38；所以这里让 shell 去匹配，命中 0 个或多于 1 个都会响）
java -jar "$(ls z-schedule-admin/target/*-exec.jar)" \
  --spring.profiles.active=dev \
  --server.servlet.context-path=/meta
# 实测（端口由 bind(("127.0.0.1",0)) 现取，08:28:47）：**没给 disabled=true** 也起得来 ——
# `Tomcat started on port(s): 50974 (http) with context path '/meta'` +
# `Started ZScheduleAdminApplication in 2.208 seconds`；逐路径：`/meta/` 200 len=417、
# `/meta/actuator/health` 200 len=49、`/meta/jobinfo/list` 500 len=118、
# `/meta/dashboard/stats` 500 len=121、`/` 404。
# 路径面在 #41 之前那次量的（07:43:01，端口 58255，同样的前置**多带** disabled=true）：
# `/meta/` 200 len=417、`/meta/actuator/health` 200 len=49、两条 `/meta/assets/*` 200、
# `/meta/jobinfo/list` 500 len=118、`/meta/dashboard/stats` 500 len=121，
# **剥掉前缀的那几条全 404**（`/`、`/actuator/health`）—— 与上面这一跑逐格相同 ⇒ 旗给不给
# 不影响路径面（§18.2 有全表）。

# 反过来，把 `-cp` 里的本树 starter 换成 ~/.m2 那份已发布件、同样不给旗 ⇒
# `APPLICATION FAILED TO START / The bean 'dataSourceSchedule', … could not be registered`
# （§19.3 的 A/B 两组）。所以这一格"不用给旗"的**唯一**依据是那行 `Tomcat started …`，
# 别拿"进程没报错"当证据。
# 可复跑的形态在 `_doc/005_testing/e2e/ui_base_probe.sh`（两臂各 5 格、PASS=10 FAIL=0，
# 含"同一个文件在剥掉前缀的路径上 200 vs 404"这对反向对照），逐格读数在 §18.2。
# ⚠ 那个脚本自己**仍带着** disabled=true —— 它量的是路径面，带着旗对两份字节都成立，
# 这样它在"jar 来自 m2"的机器上也不会误红。
# 这条的缺口与方式 A 相同（H2 无表）。要真跑调度链路，得先有一个建好 6 张表的库，
# 键给 z.base.db.schedule.*（不是 spring.datasource.*，那是另一个池），见 deploy/README.md「数据库」一节
```

### 方式 C：docker（与 deploy/ 配合）

```bash
make -C z-schedule/deploy dev
# 或
cd z-schedule/deploy && docker compose up
```

## 凭证管理

- **不在 `application.yml` 写明文密码**
- 读环境变量 `SPRING_DATASOURCE_PASSWORD`
- 本地开发用 `src/main/resources/application-local.yml`（**gitignored**，仓库里只有 `.example`）
- 凭证只经环境变量注入，不进 `java` 参数：同机任何人 `ps` 就能看到命令行

### 访问令牌 `z.schedule.accessToken`

配了它，**整个 HTTP 面**都要带 token（请求头 `X-Access-Token` 或参数 `accessToken`，参数优先），
只有 `POST /user/login` 与静态外壳（`/`、`/index.html`、`/favicon.ico`、`/assets/`、`/static/`、
`/public/`、`/error`）免鉴权，其余一律 403——包括 `/actuator/*`。

括号里那段理由原先写的是"本应用开了 `show-details=always`，health 会把数据源信息吐给匿名访问者"。
**这句是错的，而且错的方式正是本档要记的东西**：那段配置写在 `application.yml` 的 `spring.actuator.*`
下（缩进在 `spring:` 里），而 Boot 读的是顶层 `management.*`，所以**整块配置从未生效过**——
250 真机在构件 `a16473a` 上实测 `/actuator/health` 只回 `{"status":"UP"}`（没有明细）、
`/actuator/info` 是 404（`include` 也没生效）。配置没生效不等于过滤器有洞：
`/actuator/*` 的 403 由 `TokenAuthFilter` 兜着，与这段 yml 无关，所以那条 403 一直是真的。
现在 `show-details` 显式定为 `when-authorized`，理由换成一条量过的：故障时 detail 里是
连接池内部状态与驱动报错原文（`wait millis 3000, active 0, maxActive 20, creating 4`），
而演示模式（未配 `accessToken`）整个 HTTP 面敞开——明细就等于是给匿名看的。

### 探针面（`/actuator/health` 与两条探针组）

`deploy/k8s/01-deployment-backend.yaml` 的 `livenessProbe` / `readinessProbe` 指的是
`/actuator/health/{liveness,readiness}`。这两条路径在 2026-09-26 之前的构件上**根本不存在**（404），
因为 Boot 只在检测到 Kubernetes 平台时才自动建这两个组，而块内的 `include` 又因上面那个缩进错而没生效
⇒ 部署文件承诺的探测在离集群的任何场合（本机、250、docker-compose）一次都没被验过。
现在 `management.endpoint.health.probes.enabled=true` 让它在任何平台都存在，`_doc/005_testing/e2e/p23.sh`
就是在真机上验这两条的档。

第二个缺陷是这次真跑出来的：**Boot 自动建的 `readiness` 组不含数据源**。把这台实例的
`spring.datasource.url` 指到没人听的端口后，顶层 `/actuator/health` 正确 503 DOWN，
而 `/actuator/health/readiness` 仍回 **200 UP** ⇒ 照 manifest 部署时库死了 pod 依然"就绪"、继续接流量。
现在 `readiness` 显式含 `readinessState,db`、`liveness` 显式只含 `ping,livenessState`：
库抖动不该把进程重启掉（重启只会让 reconcile 更糟）。`z-schedule-admin` 里那条 yml 键位守卫
（`ManagementConfigBindingTest`）钉的就是这个分工，5 支变异自证见 `_doc/005_testing/e2e/README.md`。

没配它则整面敞开（自带的演示 UI 才能直接用），但启动时会打一条 warn 把这件事说出来，
不再当成静默默认。

250 真机实测（配了 token）：匿名打 `/jobinfo/list`、`/joblog/list`、`/user/list`、
`/dashboard/stats`、`/actuator/health`、`POST /jobinfo/add`、`POST /executor/callback` 全部 403，
且 `POST /jobinfo/add` 在库里零落地；带 token 后同一批请求回到 200、任务真的建出来。
错 token 仍 403（`MessageDigest.isEqual` 定长比较，不按字节短路）。

**仍未做的**：`z_schedule_user.role` / `permission` 两列只有写入和搬运，没有任何判定读它们；
共享 token 也不产生"这次请求是谁"的身份，所以按用户分权还缺"登录发身份"那一步。

## 模块管理特性

本 module **保留在 reactor**（顶层 `pom.xml` `<modules>` 已启用），所以：

| 命令 | 是否参与 |
|---|---|
| `mvn clean package` | ✅ 编 + 出 exec jar |
| `mvn install` | ✅ 装到 `~/.m2/repository`（docker 构建要用） |
| `mvn -pl z-schedule-admin -am package` | ✅ 只编 admin + 其依赖 |
| `mvn deploy -Pcentral` | ❌ **自动跳过**（`maven.deploy.skip=true`） |
| `mvn verify -Pcentral-dryrun` | ✅ 编 + 校验元信息，但不 deploy |

deploy 脚本（`deploy_maven_center.sh`）显式 `-pl '!z-schedule-admin'` 做双保险。

## 与前端工程的关系

未来 V2 落地后，本目录的 `src/main/resources/static/` 将**由 `_frontend/z-schedule-suit/` 的 build 产物自动注入**：
- `_frontend/z-schedule-suit/dist/` → `z-schedule-admin/src/main/resources/static/`
- 触发时机：`mvn package` 的 `process-resources` 阶段（通过 `frontend-maven-plugin`）
- 当前 `static/` 下手动放的 dist 是临时状态，V2 会被替换

## 不写 4 个发版元信息

`url` / `licenses` / `developers` / `scm` —— 这 4 个是中央要求。
admin 不 deploy → **完全不需要写**，继承 parent 即可。

## 许可

MIT