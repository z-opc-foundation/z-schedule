import { Card } from 'antd'
import { JobListView } from '@yuku123/z-schedule-component'

const SAMPLE_JOBS = [
    { id: 1, name: 'Sample Job A' },
    { id: 2, name: 'Sample Job B' },
]

export default function JobsPage() {
    return (
        <Card title="调度任务">
            <JobListView jobs={SAMPLE_JOBS} />
        </Card>
    )
}
