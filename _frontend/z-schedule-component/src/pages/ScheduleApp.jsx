import {Navigate, Route, Routes} from 'react-router-dom'
import {JobList, ScheduleDashboard} from '../'
import JobGroupList from '../pages/JobGroupList'
import JobLogList from '../pages/JobLogList'

export default function ScheduleIndex() {
    return (
        <Routes>
            <Route index element={<Navigate to="dashboard" replace/>}/>
            <Route path="dashboard" element={<ScheduleDashboard/>}/>
            <Route path="job" element={<JobList/>}/>
            <Route path="group" element={<JobGroupList/>}/>
            <Route path="log" element={<JobLogList/>}/>
        </Routes>
    )
}
