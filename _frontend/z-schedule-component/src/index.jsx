import React from 'react';

/**
 * V2 骨架占位组件 — 验证组件层 → 应用层 → admin jar 的链路。
 *
 * 接 props 而非直接 fetch（遵循规范 §5），让父项目自由装配数据源：
 * <JobListView jobs={myJobs} />
 */
export function JobListView({ jobs = [] }) {
    return (
        <div className="z-schedule-job-list-view">
            <h3 style={{ marginTop: 0 }}>Z-Schedule Job List</h3>
            {jobs.length === 0 ? (
                <p style={{ color: '#999' }}>暂无任务</p>
            ) : (
                <ul>
                    {jobs.map((job, i) => (
                        <li key={job.id ?? i}>
                            {job.name ?? `Job ${i + 1}`}
                        </li>
                    ))}
                </ul>
            )}
            <p style={{ fontSize: 12, color: '#999', marginTop: 16, borderTop: '1px dashed #ddd', paddingTop: 8 }}>
                from <code>@yuku123/z-schedule-component</code> — V2 骨架验证
            </p>
        </div>
    );
}

export default JobListView;
// §8.7 域目录清退：schedule 域 App 挂载点
export { default as ScheduleApp } from './pages/ScheduleApp.jsx'
