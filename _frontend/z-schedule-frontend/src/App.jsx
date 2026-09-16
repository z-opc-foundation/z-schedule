import React from 'react';
import { JobListView } from '@yuku123/z-schedule-frontend-component';

const SAMPLE_JOBS = [
    { id: 1, name: 'Sample Job A' },
    { id: 2, name: 'Sample Job B' },
];

export default function App() {
    return (
        <div className="z-schedule-admin-app">
            <header style={{ padding: 16, background: '#001529', color: '#fff' }}>
                <h1 style={{ margin: 0, fontSize: 20 }}>Z-Schedule 调度中心</h1>
                <p style={{ fontSize: 12, opacity: 0.6, margin: '4px 0 0 0' }}>
                    V2 骨架 — @yuku123/z-schedule-frontend-component 已生效（file: 协议本地消费）
                </p>
            </header>
            <main style={{ padding: 24, maxWidth: 960, margin: '0 auto' }}>
                <JobListView jobs={SAMPLE_JOBS} />
            </main>
        </div>
    );
}