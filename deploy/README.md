# deploy/

> z-schedule 仓的**部署资源目录**。按规范 §4 三种模式组织：
> Mode 1（合体）、Mode 2（分体）、Mode 3（集群）+ K8s 一键部署。

## 目录结构

```
deploy/
├── docker-compose.yml              # Mode 1：合体（默认，最快上手）
├── docker-compose.split.yml        # Mode 2：分体（前端独立部署）
├── docker-compose.cluster.yml      # Mode 3：集群（1 前端 + N 后端）
├── Dockerfile.backend              # 后端 jar 镜像（Mode 1/2/3 共用）
├── Dockerfile.frontend             # 前端 nginx 镜像（Mode 2/3 用）
├── nginx.conf.template             # nginx 配置模板（envsubst 注入 BACKEND_SERVICE）
├── Makefile                        # make dev/split/cluster/build/k8s-apply/clean
├── bin/
│   ├── build-images.sh             # 一键构建两个镜像（支持 --push）
│   ├── start-mode1.sh              # Mode 1 启动
│   ├── start-mode2.sh              # Mode 2 启动
│   ├── start-mode3.sh [N]          # Mode 3 启动（默认 N=2）
│   └── k8s-apply.sh                # K8s 一键部署（占位符渲染 + kubectl apply）
├── env/
│   └── .env.example                # 环境变量模板（.env 自己创建，gitignore）
└── k8s/
    ├── 00-namespace.yaml           # Namespace + ServiceAccount + RBAC
    ├── 01-deployment-backend.yaml  # 后端 Deployment（replicas=2，HPA 友好）
    ├── 02-deployment-frontend.yaml # 前端 Deployment（replicas=1）
    ├── 03-service-backend.yaml     # 后端 ClusterIP Service
    ├── 04-service-frontend.yaml    # 前端 ClusterIP Service
    └── 05-ingress.yaml             # Ingress（HTTPS + 自动证书）
```

## 三种模式对比

| 模式 | 启动命令 | 容器数 | 访问地址 | 适用场景 |
|---|---|---|---|---|
| Mode 1 合体 | `make dev` | 1 | http://localhost:18086/meta | 试用 / PoC / 个人开发 |
| Mode 2 分体 | `make split` | 2 | http://localhost | 前端频繁迭代 / CDN |
| Mode 3 集群 | `make cluster N=3` | 1+N | http://localhost | 生产 / 多副本高可用 |

三种模式**都需要一个可达的 MySQL**，且必须先把 `DB_*` 五个键给全（见"数据库"一节）。
`make dev` 不会自带数据库：Mode 1 只有一个容器，库得在外面。
如果库也跑在容器里：**必须与 app 在同一张 compose 网络上**，`DB_HOST` 用服务名/网络别名而不是裸 IP
（跨 bridge 的 IP 静默不可达，取证见 `_doc/003_script/e2e/README.md` §12.2 的 P8f）。

## 快速上手

```bash
# 0. 先备库：建库 + 跑建表脚本（脚本本身不含 DROP，见 _doc/004_sql/z-schedule.sql）
#    然后把坐标写进 deploy/env/.env（从 .env.example 复制，别提交）
cd deploy
cp env/.env.example env/.env && vi env/.env

# 1. 启动 Mode 1（缺 DB_HOST/DB_PORT/DB_NAME/DB_USER 或 DB_PASSWORD 整行没写，compose 会当场报错，
#    不会起一个永不 Ready 的容器；为什么这五个键不能省，见下面"数据库"一节）
make dev
#    没有 make 的机器（比如最小装的 Ubuntu）直接敲真身，效果相同：
bash bin/start-mode1.sh

# 2. 浏览器访问
open http://localhost:18086/meta

# 3. 看日志
make logs

# 4. 停止
make down
```

### 绕过 make 直接敲 compose：`--env-file` 不是可选项

守卫里的 `${DB_HOST:?…}` 是在 compose 的**解析阶段**生效的，所以 `ps` / `logs` / `down` 这些"善后"命令
**同样**要带 env 文件，否则照 README 起得来的容器照 README 停不掉：

```bash
cd deploy
docker-compose --env-file env/.env -f docker-compose.yml ps
docker-compose --env-file env/.env -f docker-compose.yml logs
docker-compose --env-file env/.env -f docker-compose.yml down
```

新版是 `docker compose`（插件）、老版只有 `docker-compose`（二进制，Docker 20.10.x 就是这一种）都行：
`Makefile` 与三个 `bin/start-mode*.sh` 都会先探一次 `docker compose version` 再落地，
判据在 `_doc/003_script/e2e/p25.sh` 的 P1/P2/P2c。compose 只会自动读 `deploy/.env`，
**不会**自动读 `deploy/env/.env`（本仓模板在后者），这就是上面那句 `--env-file` 的由来。

## 构建 + 推送镜像

```bash
# 仅构建（本地测试用）
make build

# 构建并 push 到 ghcr.io
make push
```

## K8s 部署

```bash
cd deploy
make k8s-apply INGRESS_DOMAIN=schedule.example.com
```

前置：
- 已有 k8s/k3s 集群 + kubectl 已配置
- 镜像已 push 到 OCI_REGISTRY（默认 ghcr.io/yuku123）
- 一个集群内可达的 MySQL，库已建好表（`_doc/004_sql/z-schedule.sql`）
- 占位符（默认值与 `Makefile`、`bin/k8s-apply.sh`、`env/.env.example` 三处逐字一致，
  由 `_doc/003_script/e2e/p24.sh` 的 A6b 判据钉住）：
  - `NAMESPACE`：默认 `z-schedule`
  - `INGRESS_DOMAIN`：必填（如 `schedule.example.com`）
  - `IMAGE_VERSION`：默认 `1.0.4`
  - `OCI_REGISTRY`：默认 `ghcr.io/yuku123`
  - `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_POOL_MAX_ACTIVE`：必填的是前四个，
    后两个有默认；`bin/k8s-apply.sh` 不接受缺 DB 坐标（缺了会渲染出没替换的 `${DB_NAME}`，
    那份清单 apply 上去就是一个连不上库的 pod）

## 占位符渲染

k8s/*.yaml 用 **`${XXX}`**（envsubst 语法，不是 `{{ }}`），`bin/k8s-apply.sh` 只把它白名单里
那几个变量交给 envsubst，渲染完若还残留 `${...}` 就直接退出非 0：

```bash
# 手动渲染查看（变量名单与 k8s-apply.sh 保持一致，否则渲染不完整）
cd deploy/k8s
NAMESPACE=z-schedule INGRESS_DOMAIN=schedule.example.com TLS_SECRET_NAME=z-schedule-tls \
OCI_REGISTRY=ghcr.io/yuku123 IMAGE_VERSION=1.0.4 \
DB_HOST=mysql.db.svc DB_PORT=3306 DB_NAME=zschedule DB_USER=zschedule DB_POOL_MAX_ACTIVE=40 \
envsubst '$NAMESPACE $INGRESS_DOMAIN $TLS_SECRET_NAME $OCI_REGISTRY $IMAGE_VERSION $DB_HOST $DB_PORT $DB_NAME $DB_USER $DB_POOL_MAX_ACTIVE' \
  < 01-deployment-backend.yaml > /tmp/01.rendered.yaml
```

数据库密码**不走清单**：两个池的密码都从 Secret `z-schedule-db-credentials` 的 `password` 键取，
所以上面的渲染命令里没有、也不该有密码。

## 数据库

- **五个键都要给，形状不一样**（`env/.env`，模板见 `env/.env.example`）：

  | 键 | compose 里的形状 | 为什么是这个形状 |
  |---|---|---|
  | `DB_HOST` | `${DB_HOST:?…}` | 拒"未设置"也拒"设为空"。没有它连的是不存在的库 |
  | `DB_PORT` | `${DB_PORT:?…}` | 空端口让两个池都连不上；退回静默默认 `3306` 会起一个"端口不 bind、不退出、日志无限刷"的容器（取证：`p24.sh` 的 B12） |
  | `DB_NAME` | `${DB_NAME:?…}` | 空库名让引擎池连上 `jdbc:mysql://…:3306/?serverTimezone…`，一个不存在的库 |
  | `DB_USER` | `${DB_USER:?…}` | 不填就退回 starter 内置的 `root` |
  | `DB_PASSWORD` | `${DB_PASSWORD?…}`（**没有冒号**） | 只拒"整行没写"。**显式留空是合法的**（无口令库），写成 `:?` 会把这种合法形状拒在解析阶段 |
  | `DB_POOL_MAX_ACTIVE` | `${DB_POOL_MAX_ACTIVE:-40}` | 唯一允许静默默认的一个：40 是量过吞吐之后的合理值，漏配不影响可用性 |

  三个 compose 文件（Mode 1/2/3）形状必须逐条一致，且这一组守卫是在 compose **v5.0.2** 上逐变量双向量过的
  （删掉必须拒、给了必须让**两个池**渲染出同一组坐标）：判据 `_doc/003_script/e2e/p25.sh` 的 P2d + P18。

- **两个池都要喂**：调度引擎跑在 starter 自建的 `dataSourceSchedule` 上，键是
  `Z_BASE_DB_SCHEDULE_{HOST,PORT,DATABASE,USERNAME,PASSWORD}`；`SPRING_DATASOURCE_*` 建的是
  另一个池。只喂后者的话引擎池退回内置默认 `jdbc:mysql://localhost:3306/`（**库名为空**），
  实测那个容器端口都不 bind、日志以约 86 行/s 刷 `Communications link failure` 且不退出
  （`p24.sh` 的 B12 就是这条的负对照）。
- **库与 app 必须同网**：库也跑容器时，`DB_HOST` 用同一张 compose 网络上的服务名/别名。
  实测把它放到另一张网上，`mysqladmin` 只读到 `You can check this by doing 'telnet 172.26.0.2 3306'`，
  而 IP 本身是"能 ping 到的形状"——跨 bridge 静默不可达（取证：`p25.sh` 的 P8d/P8f）。
- **建库用哪份脚本**：`_doc/004_sql/z-schedule.sql`（提交树那份，**不含任何 DROP**）。
  历史 `init.sql` 那类脚本里有 15 条 DROP，**不许照跑**；`bootstrap_mysql.sh` 与 e2e 的 P6/B4
  都带"非注释行 DROP 计数必须为 0"的前置。
- **没有 H2 这条路**：镜像里 h2 依赖虽然在 classpath 上，但仓库里没有 `schema.sql` /
  `data.sql`，建表脚本只在 `_doc/004_sql/` 下面向 MySQL；`dev` profile 的
  `DevDataSourceConfig` 是给 IDE 里跑 admin 用的，不覆盖容器入口。所以"零依赖试用"这句话
  从来兑现不了，别再照它部署。
- **生产 / K8s**：密码通过 Secret 注入
  ```bash
  kubectl -n z-schedule create secret generic z-schedule-db-credentials \
      --from-literal=password='YOUR_PASSWORD'
  ```

## 构建上下文：仓库根那份 `.dockerignore`

`build.context` 是**仓库根**，而两个 Dockerfile 实际只取三样东西：后端要 `${JAR_FILE}` 那一个 jar，
前端要 `_frontend/z-schedule-frontend/` 与 `nginx.conf.template`。没有排除清单时 `make dev` 会把
`.git`、各模块 `target/`、`_frontend/*/node_modules` 全打成 tar 送给 daemon。演练机实测（250，
`docker build` 自己打的计数行）：

| 状态 | daemon 收到 |
|---|---|
| 改前（无 `.dockerignore`） | **827 MiB** |
| 加了排除清单，但根目录还躺着改名的备份 jar | 225.6 MB |
| 干净树 | **56.93 MB**（其中 53.6 MB 就是那个 jar 本身） |

两个坑值得记住：`*` 不跨 `/`，所以 `*.jar` 只挡根目录那些散落的构件，
`z-schedule-admin/target/*.jar`（Dockerfile 要 COPY 的）仍然进得来；而**改名的备份**
（`xxx-exec.jar.pre-fix` 这种）`*.jar` 挡不住——演练机上那 160 MB 就是这么来的。
判据在 `p25.sh` 的 P3（清单内容）与 P10（直接读 daemon 的计数行，钉 "< 300 MiB"）。


## 与 lead 部署文档的关系

按规范 §4.5，本目录是**单中间件**部署模板；lead/006_部署方案 是**全集**部署编排。
本目录落地后，lead/006 应反向引用本仓 `deploy/` 而非重复维护 yaml。

## 许可

MIT