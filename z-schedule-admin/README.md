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

java -jar z-schedule-admin/target/z-schedule-admin-1.0.0-exec.jar
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