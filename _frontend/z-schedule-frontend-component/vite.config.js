import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// Z-Schedule 组件层 — Vite library mode 配置
// 产物：dist/index.js（ES Module 格式，外部化 react/react-dom）
// 引用方：同仓 _frontend/z-schedule-frontend/（file: 协议）或外部项目（npm install）
export default defineConfig({
    plugins: [react()],
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
            external: ['react', 'react-dom', 'react-dom/client'],
            output: {
                globals: {
                    react: 'React',
                    'react-dom': 'ReactDOM',
                    'react-dom/client': 'ReactDOMClient',
                },
            },
        },
    },
});