# z-schedule

分布式任务调度系统（对标 XXL-Job）。调度中枢以 **Spring Boot Starter** 的形式嵌进宿主应用，
内部是三件事：`LeaderElector`（DB 抢锁选主、续约）、`ScheduleRing`（时间环排期）、
`JobScheduleEngine`（到点触发 + 执行结论落库）。`z-schedule-admin` 是一个自带前端外壳的单体演示应用，
`java -jar` 起来就是一个能点的调度中心。

## 构件

| 模块 | 坐标 | 上 Maven Central |
|---|---|---|
| `z-schedule-core` | `io.github.yuku123:z-schedule-core` | ✅ |
| `z-schedule-spring-boot-starter` | `io.github.yuku123:z-schedule-spring-boot-starter` | ✅ |
| `z-schedule-admin` | 演示应用 / 镜像素材，**没人 import** | ❌ `maven.deploy.skip=true` |

- 版本单一来源是根 `pom.xml` 的 `<revision>`，当前 **1.0.4**；`z-schedule-admin/target/` 下的
  jar 名跟着它走，所以任何地方都不该抄死版本号（抄死的那份在抬版后怎么坏，五种形状实测见
  [`_doc/003_script/e2e/README.md`](_doc/003_script/e2e/README.md) §14）。
- 构建要求：Java 8（`maven.compiler.source/target`）、Spring Boot 2.7.12 —— 两个值同样只在根 pom 里。
- 仓库已发布到 Maven Central 的坐标、签名与三方字节对账过程记在
  [`_doc/003_script/deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) 这一侧。

## 接入一个 Spring Boot 应用

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-schedule-spring-boot-starter</artifactId>
    <version>1.0.4</version>
</dependency>
```

自动装配类是 `ZScheduleAutoConfiguration`（`META-INF/spring.factories`），**生效的配置前缀只有
`z.schedule.*` 与 `z.base.db.schedule.*`**：前者是调度面（含 `z.schedule.accessToken`），
后者是调度引擎自己那个连接池（`dataSourceSchedule`）。
`spring.datasource.*` 建的是**另一个**池（给 actuator 的 db 健康检查用），改它对吞吐无关
—— 这条是 250 真机量出来的，写进了
[`_doc/001_arch/z-schedule-admin.md`](_doc/001_arch/z-schedule-admin.md)。

库要先有：建表脚本 [`_doc/004_sql/z-schedule.sql`](_doc/004_sql/z-schedule.sql)（6 张表：
`z_schedule_job_info` / `_job_log` / `_job_group` / `_job_registry` / `_job_leader` / `_user`；非注释行里
零条 `DROP`，`_doc/003_script/e2e/bootstrap_mysql.sh` 建库前会先数这条，不为 0 直接拒）。

## 三条"跑起来"的路，各自验到哪一步

| 路 | 命令在哪 | 实测状态 |
|---|---|---|
| 容器（Mode 1 合体 / Mode 2 前后端分体 / Mode 3 集群） | [`deploy/README.md`](deploy/README.md) | 三种模式在 250 真机各起过，`/api/` 反代与探针路径逐条验过（`_doc/003_script/e2e/p24.sh` 臂 B、`p25.sh`） |
| 真机 E2E 量具（自建 MySQL + 逐个接口打） | [`_doc/003_script/e2e/README.md`](_doc/003_script/e2e/README.md) 顶部 quick-start | 250 在跑；静态臂 `P24_A_ONLY=1 bash p24.sh` 不碰 docker，任何机器都能跑 |
| 本机直跑（`mvn spring-boot:run` 或 `java -jar`） | [`_doc/001_arch/z-schedule-admin.md`](_doc/001_arch/z-schedule-admin.md)「本地启动」 | ⚠ **两条独立的前置**：① 要显式给 `--z.base.db.schedule.disabled=true` 才起得来，而那份 H2 是空库 ⇒ 数据接口 500（**不是零依赖可玩**）；② 要看界面还得给 `--server.servlet.context-path=/meta` —— 前端资源基路径 `/meta/` 是构建时烤进 jar 的，不给就是**白屏**（页面 200、它自己声明的两条资源 404，实测见 `_doc/003_script/e2e/README.md` §18）。四条命令逐条实测的读数在那一节 |

## 文档目录

本项目文档统一收口在 `_doc/` 下:

- [`_doc/001_arch/`](_doc/001_arch/) — 架构与模块说明:
  - [`z-schedule-admin.md`](_doc/001_arch/z-schedule-admin.md) — 演示应用的定位、本地启动的真实前置、鉴权面、探针面
- [`_doc/003_script/`](_doc/003_script/) — 运维脚本:
  - [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) — 发 Central（不可逆，需单独授权）
  - [`install-settings.sh`](_doc/003_script/install-settings.sh) — 本机 Maven settings/凭证骨架
  - [`e2e/`](_doc/003_script/e2e/) — 端到端量具与验收档（`README.md` 是台账，`pNN.sh` 是分档的臂）
- [`_doc/004_sql/`](_doc/004_sql/) — 建表:
  - [`z-schedule.sql`](_doc/004_sql/z-schedule.sql) — 全量建表脚本（不含 DROP）

部署面另在 [`deploy/`](deploy/)（compose / k8s / `bin/start-mode*.sh` / `Makefile`），
前端工程在 [`_frontend/`](_frontend/)（两个 npm 项目，`file:` 协议互相消费）。

## 许可

MIT，见 [`LICENSE`](LICENSE)。
