# z-schedule-component

z-schedule（调度中心）跨仓唯一前端面：把「执行器分组 / 调度日志 / 任务列表骨架」这一整套调度页面
收敛成一个可复用的 React 组件包；`z-schedule-suit` 是它的独立运行壳。

- **npm 包名**：`@yuku123/z-schedule-component`
- **宿主接入**：`@yuku123/z-schedule-component`（主入口）/ `@yuku123/z-schedule-component/pages`（manifest 桶）
- **suit**：`@yuku123/z-schedule-suit`（private，不发布 npm）
- **后端**：z-schedule admin 的 `/api/schedule/**`（`JobGroupController` / `JobLogController`）
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + TypeScript + vite（library mode，双入口 `.` / `./pages`）

> **两套包的分工**：`z-schedule-component` 放**文档与可复用页面**；`z-schedule-suit` 放**应用层**
> （登录、首页、菜单、路由表）。菜单数组在 suit 侧，不在 component 侧。

---

## 一、能力概览

### 1.1 component 包页面（`z-schedule-component/src/pages/`）

| 页面 | 路由 | 源文件 | 能力 |
|------|------|--------|------|
| 执行器分组 | `group` | `pages/JobGroupList.tsx` | 执行器（JobGroup）全量列表 + 新增 / 编辑 / 删除 + 在线节点展示 |
| 调度日志 | `log` | `pages/JobLogList.tsx` | 调度任务执行历史，按执行器 / 状态过滤 + 执行日志查看 + 清空 |
| 任务列表（骨架） | `jobs` | `index.jsx` 的 `JobListView` | **占位组件**，只渲染传入的 `jobs` 数组，不发请求 |

### 1.2 suit 包页面（`z-schedule-suit/src/`）

| 页面 | 路由（相对 `/z-schedule/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 登录 | `login` | `LoginPage.jsx` | mock 登录（admin / 123456），CTC/SSO 接入后替换 |
| 首页 | `home` | `HomePage.jsx` | 欢迎语 + 按 `menuItems` 生成的快捷入口卡 |
| 调度任务 | `jobs` | `JobsPage.jsx` | 用 `JobListView` 渲染 `SAMPLE_JOBS` 样例数据 |
| 组件文档 | `docs` | 来自 component 包的 `ComponentDocPage` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

### 1.3 子路由挂载点

`z-schedule-component/src/pages/ScheduleApp.jsx` 是域内相对路由挂载点：
`jobs`（`JobListView`）、`group`（`JobGroupList`）、`log`（`JobLogList`），index 重定向到 `jobs`。
它从包根具名导出（`ScheduleApp`），宿主可按自己选的前缀挂载。

## 二、导出面

```js
// 主入口（. 桶）
import {JobListView, ScheduleApp, ComponentDocPage} from '@yuku123/z-schedule-component'

// manifest 桶（./pages）
import {routes, appMeta, ComponentDocPage} from '@yuku123/z-schedule-component/pages'

// API（仓内相对路径引用）
import {jobGroupApi, jobLogApi, configureScheduleBaseURL} from '@yuku123/z-schedule-component/src/schedule/services/job'
```

> ⚠ `jobGroupApi` / `jobLogApi` 目前**只在仓内被页面相对路径引用**（`../schedule/services/job`），
> 没有从包根再导出。要在包外用，需要补一行具名再导出。

## 三、API 端点

请求实例由基座 `createRequest` 工厂创建（`src/schedule/services/job.ts`），超时 15s，
`baseURL` 由 `configureScheduleBaseURL(baseURL)` 注入（默认空串，路径自带 `/api` 前缀）。
基座已在响应拦截器里解包 `ReturnT<T>`，业务层拿到的就是内层 `data`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/schedule/jobgroup/list` | 执行器列表（Map 直出，全量一次给完） |
| GET | `/api/schedule/jobgroup/get?id=` | 单个执行器 |
| GET | `/api/schedule/jobgroup/registryNodes?appName=` | 某应用下的注册节点 |
| POST | `/api/schedule/jobgroup/add` | 新增执行器（body 驼峰） |
| POST | `/api/schedule/jobgroup/update` | 修改执行器（body 驼峰，带 `id`） |
| POST | `/api/schedule/jobgroup/remove?id=` | 删除执行器（query `id`） |
| GET | `/api/schedule/joblog/list?jobId=&jobGroup=&status=&limit=` | 调度日志列表 |
| GET | `/api/schedule/joblog/executionLog?logId=&fromLineNum=` | 执行日志（返回 `{content, fromLineNum, toLineNum, isEnd}`） |
| POST | `/api/schedule/joblog/clear` | 清空日志（body `{type: 0}` 全清 / `{type: 1, jobId}` 按 jobId；分权会话只允许 type=1） |

## 四、页面说明

### 4.1 执行器分组（`JobGroupList.tsx`）

执行器全量在前端（接口一次给全量）⇒ 过滤 / 分页 / 空态三件套交给基座 `ListTable`。
行内操作：编辑、删除（`Popconfirm` 二次确认）。加载失败用基座 `ErrorState`（标题「加载执行器失败」）。

### 4.2 调度日志（`JobLogList.tsx`）

按执行器与状态过滤的执行历史（同样是「整表在前端」形态，过滤/分页/空态交给 `ListTable`），
支持查看单条执行日志与清空日志。页头用基座 `PageHeader`（标题「调度日志」）。

### 4.3 任务列表骨架（`JobListView`）

`index.jsx` 里的占位组件：**接 props 而非直接 fetch**（规范 §5），让父项目自由装配数据源
（`<JobListView jobs={myJobs} />`）。suit 的 `JobsPage` 现在传的是 `SAMPLE_JOBS` 样例数据。

### 4.4 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `configureScheduleBaseURL(baseURL)` | `''` | 改写请求实例的 `baseURL`（改的是模块级变量，下次 `makeRequest()` 生效） |
| 请求超时 | 15000ms | `makeRequest()` 里写死 |
| vite `base` | `process.env.VITE_BASE \|\| '/'` | suit 侧；需要非根挂载时用 `VITE_BASE` 显式覆盖 |
| router basename | `BASE \|\| undefined` | suit 侧，`BASE` 来自 `src/routes-base.js` |
| 登录态判据 | localStorage `token` | suit 的 `ProtectedShell` 用；登录页当前是 mock（admin / 123456） |

## 六、已知限制

- **菜单数组在 suit 侧**（`z-schedule-suit/src/pages-manifest.jsx`），component 的
  `pages-manifest.jsx` 只有 `routes` / `appMeta`，**没有 `menuItems`** —— 加菜单项要改 suit。
- component 的 `pages-manifest.jsx` 里那条 `/z-schedule/jobs` 路由挂的是**骨架** `JobListView`，
  与 suit 里的 `JobsPage`（传样例数据）不是同一个东西。component 的 manifest 定位是
  「供主壳探测 domainRoutes 的骨架清单」。
- `z-schedule-frontend/` 与 `z-schedule-frontend-component/` 两个目录**只剩 `dist/` 与
  `node_modules/`，没有源码** —— 是历史残留，不要往里加文件。
- 组件包必须 external 基座；`vite.config.js` 的 `external` 是精确字符串匹配，
  `@yuku123/z-frontend-common/ui` 与 `/ui/echarts` 两个子路径必须逐个列出，漏了基座会被复制进 dist。
- 组件包**不 external 基座不行**：antd 的主题 / locale 是 React context，
  两份 antd 各读各的 context，表现为 locale 不跟随、token 对不上。
- `jobGroupApi` / `jobLogApi` 未从包根导出，仓外用不了。
- `joblog/clear` 的 `type=0`（清空全部）只对管理员开放，分权会话会被拒。
- 键名口径：`/api/schedule/jobgroup/list` 是 Map 直出，列表项**本来就是驼峰**，
  且字段叫 `order` 而不是 DO 里的 `orderNum` —— 别按 DO 想当然。
- 组件包页面是 `.tsx`，基座只发 JS 不发 `.d.ts`，类型声明靠
  `src/z-frontend-common-ui.d.ts`（本仓手写维护）。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.3 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装；suit 菜单与路由接上 |
| 0.1.3 | `z-frontend-common-ui.d.ts` 追加 `ComponentDoc` 与 `*.md?raw` 模块声明 |
| 0.1.2 | `ScheduleApp` 去掉从未存在的 `ScheduleDashboard` / `JobList` 两条路由 |
| 0.1.0 | 首版：执行器 / 调度日志页面 + `jobGroupApi` / `jobLogApi` |
