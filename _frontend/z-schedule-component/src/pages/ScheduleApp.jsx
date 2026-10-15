import { Navigate, Route, Routes } from 'react-router-dom'
import { JobListView } from '../'
import JobGroupList from './JobGroupList'
import JobLogList from './JobLogList'

/**
 * z-schedule 域路由挂载点（相对路由，宿主自行决定挂到哪个前缀下）。
 *
 * 只列真正存在的页面。原先这里还挂着 dashboard / job 两条，分别引用
 * `ScheduleDashboard` 与 `JobList` —— 两个导出从未存在过（`src/index.jsx` 只有
 * `JobListView`），所以这个文件从来没被构建过一次。
 * dashboard 的后端 `/api/schedule/stats` 一直在，缺的是前端页面，属于独立的一件事。
 */
export default function ScheduleApp() {
    return (
        <Routes>
            <Route index element={<Navigate to="jobs" replace />} />
            <Route path="jobs" element={<JobListView jobs={[]} />} />
            <Route path="group" element={<JobGroupList />} />
            <Route path="log" element={<JobLogList />} />
        </Routes>
    )
}
