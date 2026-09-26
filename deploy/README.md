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

三种模式**都需要一个可达的 MySQL**，且必须先把 `DB_*` 给全（见"数据库"一节）。
`make dev` 不会自带数据库：Mode 1 只有一个容器，库得在外面。

## 快速上手

```bash
# 0. 先备库：建库 + 跑建表脚本（脚本本身不含 DROP，见 _doc/004_sql/z-schedule.sql）
#    然后把坐标写进 deploy/env/.env（从 .env.example 复制，别提交）
cp env/.env.example env/.env && vi env/.env

# 1. 启动 Mode 1（DB_HOST 缺失时 compose 会当场报错，不会起一个永不 Ready 的容器）
cd deploy
make dev

# 2. 浏览器访问
open http://localhost:18086/meta

# 3. 看日志
make logs

# 4. 停止
make down
```

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

- **两个池都要喂**：调度引擎跑在 starter 自建的 `dataSourceSchedule` 上，键是
  `Z_BASE_DB_SCHEDULE_{HOST,PORT,DATABASE,USERNAME,PASSWORD}`；`SPRING_DATASOURCE_*` 建的是
  另一个池。只喂后者的话引擎池退回内置默认 `jdbc:mysql://localhost:3306/`（**库名为空**），
  实测那个容器端口都不 bind、日志以约 86 行/s 刷 `Communications link failure` 且不退出
  （`p24.sh` 的 B12 就是这条的负对照）。
- **没有 H2 这条路**：镜像里 h2 依赖虽然在 classpath 上，但仓库里没有任何 `schema.sql` /
  `data.sql`，建表脚本只在 `_doc/004_sql/` 下面向 MySQL；`dev` profile 的
  `DevDataSourceConfig` 是给 IDE 里跑 admin 用的，不覆盖容器入口。所以"零依赖试用"这句话
  从来兑现不了，别再照它部署。
- **生产 / K8s**：密码通过 Secret 注入
  ```bash
  kubectl -n z-schedule create secret generic z-schedule-db-credentials \
      --from-literal=password='YOUR_PASSWORD'
  ```

## 与 lead 部署文档的关系

按规范 §4.5，本目录是**单中间件**部署模板；lead/006_部署方案 是**全集**部署编排。
本目录落地后，lead/006 应反向引用本仓 `deploy/` 而非重复维护 yaml。

## 许可

MIT