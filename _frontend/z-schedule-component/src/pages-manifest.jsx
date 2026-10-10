/**
 * z-schedule-component 路由清单（lead 005 §8.6 #3：./pages 命名导出 routes，非空数组）
 *
 * 占位说明：z-schedule 的页面代码现仍住在 z-z-schedule-suit/src/ 下（manifest + page 文件），
 * 本 manifest 现阶段只列骨架路由供主壳的 domainRoutes 探测；正式消费请走 suit。
 * 下次重构把 page 文件搬入 component 后，Component 字段直接换成同模块 import 即可。
 */
import { JobListView } from '.';

export const appMeta = { title: 'z-schedule 控制台', short: 'z-schedule' }

export const routes = [
    { path: '/z-schedule/jobs', title: 'Job List', order: 1, Component: JobListView },
]
