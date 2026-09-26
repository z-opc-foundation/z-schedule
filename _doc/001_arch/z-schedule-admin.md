# z-schedule-admin

> **Standalone Demo App + Docker 镜像源 —— 永远不上 Maven Central**
>
> 详见 [`lead/005_技术架构/005_前端工程与中间件部署架构规范.md`](../../z-opc-foundation-lead/005_技术架构/005_前端工程与中间件部署架构规范.md) §2

## 它是什么

一个**最小可运行单体应用**，把 `z-schedule-spring-boot-starter` 装进来，
附上 Spring Web + Actuator + H2/Log4j2 + 内嵌前端（`src/main/resources/static/`，由
`_frontend/z-schedule-frontend/dist` 注入），跑起来就是一个完整的「Z-Schedule 调度中心 UI」。

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

### 方式 A：`mvn spring-boot:run`（最快）

```bash
cd z-schedule/z-schedule-admin
mvn spring-boot:run
# 默认端口 18086，context-path /meta
# 访问 http://localhost:18086/meta
```

### 方式 B：编出 exec jar 后启动

```bash
cd z-schedule
mvn -pl z-schedule-admin -am package -DskipTests

# jar 名跟着 <revision> 走，别抄版本号：这里让 shell 去匹配，命中 0 个或多于 1 个都会响
java -jar "$(ls z-schedule-admin/target/*-exec.jar)"
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
现在 `management.endpoint.health.probes.enabled=true` 让它在任何平台都存在，`_doc/003_script/e2e/p23.sh`
就是在真机上验这两条的档。

第二个缺陷是这次真跑出来的：**Boot 自动建的 `readiness` 组不含数据源**。把这台实例的
`spring.datasource.url` 指到没人听的端口后，顶层 `/actuator/health` 正确 503 DOWN，
而 `/actuator/health/readiness` 仍回 **200 UP** ⇒ 照 manifest 部署时库死了 pod 依然"就绪"、继续接流量。
现在 `readiness` 显式含 `readinessState,db`、`liveness` 显式只含 `ping,livenessState`：
库抖动不该把进程重启掉（重启只会让 reconcile 更糟）。`z-schedule-admin` 里那条 yml 键位守卫
（`ManagementConfigBindingTest`）钉的就是这个分工，5 支变异自证见 `_doc/003_script/e2e/README.md`。

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

未来 V2 落地后，本目录的 `src/main/resources/static/` 将**由 `_frontend/z-schedule-frontend/` 的 build 产物自动注入**：
- `_frontend/z-schedule-frontend/dist/` → `z-schedule-admin/src/main/resources/static/`
- 触发时机：`mvn package` 的 `process-resources` 阶段（通过 `frontend-maven-plugin`）
- 当前 `static/` 下手动放的 dist 是临时状态，V2 会被替换

## 不写 4 个发版元信息

`url` / `licenses` / `developers` / `scm` —— 这 4 个是中央要求。
admin 不 deploy → **完全不需要写**，继承 parent 即可。

## 许可

MIT