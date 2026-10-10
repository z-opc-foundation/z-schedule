import { ClockCircleOutlined, HomeOutlined } from '@ant-design/icons'
import HomePage from './HomePage'
import JobsPage from './JobsPage'

/** 菜单 + 路由清单（lead 008 §10/§14/§16）。 */
export const menuItems = [
    { key: '/z-schedule/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-schedule/jobs', label: '调度任务', icon: <ClockCircleOutlined /> },
]

export const routeTable = [
    { path: '/z-schedule/home', Component: HomePage },
    { path: '/z-schedule/jobs', Component: JobsPage },
]
