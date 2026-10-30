import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

// Z-Schedule 组件层 — Vite library mode 配置
// 产物：dist/index.js（ES Module 格式，外部化 react/react-dom）
// 引用方：同仓 _frontend/z-schedule-suit/（file: 协议）或外部项目（npm install）
export default defineConfig({
    plugins: [safeEmptyOutDir(), react()],
    resolve: {
        // src/pages/*.tsx 用 @/schedule/... 与 @/common/... 引本仓内部模块。
        // library mode 的 rollup 不读 tsconfig 的 paths，alias 必须配在这里，否则解析不了直接构建失败。
        alias: { '@': path.resolve(__dirname, 'src') },
        dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
    },
    build: {
        lib: {
            // 两个 entry 对应 exports 两张脸: './dist/index.js'(.) 与 './dist/pages.js'(./pages,
            // lead 005 §8.2/§8.6 #3)。只声明 index 时产物里没有 pages.js, 而 exports 还指着它。
            entry: {
                index: path.resolve(__dirname, 'src/index.jsx'),
                pages: path.resolve(__dirname, 'src/pages.js'),
            },
            name: 'ZScheduleFrontendComponent',
            formats: ['es'],
        },
        outDir: 'dist',
        emptyOutDir: false,
        sourcemap: false,
        rollupOptions: {
            // 框架本体全部走 external（§8.4 版本线：react 19 / antd 6 / rrd 7）。
            // 原先只 external react 一家、把 antd+icons+z-frontend-common 打进产物，理由写的是
            // 「suit 的 devDeps 与本包 peerDeps 是两套版本线」—— 实测不成立：suit 用 antd ^6.3.3，
            // 本包 peer 写 ^6.0.0，完全被满足。代价却是产物 1.5MB 且自带一份 antd：
            // antd 的 ConfigProvider/主题是 React context，两份 antd 各读各的 context，
            // 主壳的 zhCN locale 与主题对组件层这份完全不生效（表现为 locale 不跟随、
            // token 对不上），这是「declare 成 peer 却又打进产物」的自相矛盾。
            external: [
                'react', 'react-dom', 'react-dom/client', 'react/jsx-runtime',
                'react-router-dom', 'antd', '@ant-design/icons', 'axios',
                '@yuku123/z-frontend-common', '@yuku123/z-frontend-common/ui',
                // 基座的 /ui/echarts 子路径也要 external：rollup 的 external 是精确字符串匹配
                // 而不是前缀匹配，漏掉子路径就会把基座 ECharts 组件复制进本包 dist。
                '@yuku123/z-frontend-common/ui/echarts',
            ],
        },
    },
});