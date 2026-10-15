import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// Z-Schedule 组件层 — Vite library mode 配置
// 产物：dist/index.js（ES Module 格式，外部化 react/react-dom）
// 引用方：同仓 _frontend/z-schedule-suit/（file: 协议）或外部项目（npm install）
export default defineConfig({
    plugins: [react()],
    resolve: {
        // src/pages/*.tsx 用 @/schedule/... 与 @/common/... 引本仓内部模块。
        // library mode 的 rollup 不读 tsconfig 的 paths，alias 必须配在这里，否则解析不了直接构建失败。
        alias: { '@': path.resolve(__dirname, 'src') },
        dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
    },
    build: {
        lib: {
            entry: path.resolve(__dirname, 'src/index.jsx'),
            name: 'ZScheduleFrontendComponent',
            formats: ['es'],
            fileName: () => 'index.js',
        },
        outDir: 'dist',
        emptyOutDir: true,
        sourcemap: false,
        rollupOptions: {
            // 只外部化框架本体：antd / icons / router / z-frontend-common 必须打进产物，
            // 否则消费方（suit）还得自己保证版本一致，而 suit 的 devDeps 与本包 peerDeps 是两套版本线。
            external: ['react', 'react-dom', 'react-dom/client', 'react-router-dom'],
            output: {
                globals: {
                    react: 'React',
                    'react-dom': 'ReactDOM',
                    'react-dom/client': 'ReactDOMClient',
                    'react-router-dom': 'ReactRouterDOM',
                },
            },
        },
    },
});