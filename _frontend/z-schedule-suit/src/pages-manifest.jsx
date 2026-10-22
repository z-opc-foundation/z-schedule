import { ClockCircleOutlined, HomeOutlined, ReadOutlined } from '@ant-design/icons'
import HomePage from './HomePage'
import JobsPage from './JobsPage'
// 组件文档页在 component 包（DOC.md 与薄包装都归它），suit 只负责挂菜单与路由。
import { ComponentDocPage } from '@yuku123/z-schedule-component/pages'

/** 菜单 + 路由清单（lead 008 §10/§14/§16）。 */
export const menuItems = [
    { key: '/z-schedule/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-schedule/jobs', label: '调度任务', icon: <ClockCircleOutlined /> },
    { key: '/z-schedule/docs', label: '组件文档', icon: <ReadOutlined /> },
]

export const routes = [
    { path: '/z-schedule/home', Component: HomePage },
    { path: '/z-schedule/jobs', Component: JobsPage },
    { path: '/z-schedule/docs', Component: ComponentDocPage },
]
