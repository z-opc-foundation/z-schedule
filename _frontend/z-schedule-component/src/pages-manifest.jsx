/**
 * z-schedule-component 路由清单（lead 005 §8.6 #3：./pages 命名导出 routes，非空数组）
 *
 * 占位说明：z-schedule 的页面代码现仍住在 z-z-schedule-suit/src/ 下（manifest + page 文件），
 * 本 manifest 现阶段只列骨架路由供主壳的 domainRoutes 探测；正式消费请走 suit。
 * 下次重构把 page 文件搬入 component 后，Component 字段直接换成同模块 import 即可。
 */
import { JobListView } from '.';
import ComponentDocPage from './pages/ComponentDoc';

export const appMeta = { title: 'z-schedule 控制台', short: 'z-schedule' }

// 菜单清单（按 routes 派生；页面实体仍在 suit 侧，见上方占位说明）
export const menuItems = [
    { key: '/z-schedule/jobs', label: 'Job List' },
    { key: '/z-schedule/docs', label: '组件文档' },
]

export const routes = [
    { path: '/z-schedule/jobs', title: 'Job List', order: 1, Component: JobListView },
    // 组件文档页（lead 008 §7）。路由在这里补一条，主壳按 /z-schedule/docs 挂载即可。
    { path: '/z-schedule/docs', title: '组件文档', order: 2, Component: ComponentDocPage },
]
