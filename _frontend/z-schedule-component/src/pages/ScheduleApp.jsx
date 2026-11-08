import { Navigate, Route, Routes } from 'react-router-dom'
import JobList from './JobList'
import JobGroupList from './JobGroupList'
import JobLogList from './JobLogList'

/**
 * z-schedule 域路由挂载点（相对路由，宿主自行决定挂到哪个前缀下）。
 *
 * ⚠ 2026-11-09：jobs 这一条原先渲染 `JobListView jobs={[]}` —— 那是组件层的
 *   骨架占位（只把 props.jobs 列出来），而这里**写死传了空数组** ⇒ 页面永远
 *   「暂无任务 / V2 骨架验证」，后端真实任务一条都显示不出来。
 *   换成 JobList（自持 fetch /api/schedule/jobinfo/list，含启停/编辑/删除）。
 *
 * 变更史：更早这里还挂着 dashboard / job 两条，分别引用从未存在过的导出
 *   `ScheduleDashboard` / `JobList` —— 那个 JobList 与本次新增的不是同一个东西。
 *   后端 `/api/schedule/stats`（今日触发数 / 成功率 / 执行器负载）一直可用，
 *   前端统计页仍未做，属独立待办。
 */
export default function ScheduleApp() {
    return (
        <Routes>
            <Route index element={<Navigate to="jobs" replace />} />
            <Route path="jobs" element={<JobList />} />
            <Route path="group" element={<JobGroupList />} />
            <Route path="log" element={<JobLogList />} />
        </Routes>
    )
}
