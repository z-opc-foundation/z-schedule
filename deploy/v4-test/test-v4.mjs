// V4 验证脚本：跨仓 file: 协议 + import React 组件
// 用 esbuild bundle 整个依赖图（react + 组件层 + 测试代码），跑在 Node 里
import React from 'react';
import { renderToString } from 'react-dom/server';
import { JobListView } from '@yuku123/z-schedule-component';

const jobs = [
    { id: 1, name: '跨仓测试 Job 1' },
    { id: 2, name: '跨仓测试 Job 2' },
    { id: 3, name: '跨仓测试 Job 3' },
];

const html = renderToString(React.createElement(JobListView, { jobs }));

console.log('=== V4 验证输出 ===');
console.log('组件 HTML:', html);
console.log('');
console.log('=== 断言 ===');
const assertions = [
    ['包含组件签名', html.includes('z-schedule-job-list-view')],
    ['包含 Job 1', html.includes('跨仓测试 Job 1')],
    ['包含 Job 2', html.includes('跨仓测试 Job 2')],
    ['包含骨架标记', html.includes('V2 骨架验证')],
];
let pass = 0;
for (const [name, ok] of assertions) {
    console.log(`  ${ok ? '✓' : '✗'} ${name}`);
    if (ok) pass++;
}
console.log('');
if (pass === assertions.length) {
    console.log(`✓ V4 验证通过：file: 协议跨仓消费 z-schedule-component 生效（${pass}/${assertions.length}）`);
    process.exit(0);
} else {
    console.error(`✗ V4 验证失败（${pass}/${assertions.length}）`);
    process.exit(1);
}