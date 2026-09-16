import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// Z-Schedule 应用层 — Vite SPA 配置
// dev 端口 5173，proxy /api → 后端 admin (18086)
// build 产物 dist/ 由 admin pom 的 frontend-maven-plugin 在 process-resources 阶段复制到 admin/src/main/resources/static/
//
// ⚠️ base 必须设为 '/meta/' —— Spring Boot 把 admin 挂在 /meta/ context-path 下，
// 否则 index.html 引用的 /assets/xxx.js 会 404（应为 /meta/assets/xxx.js）。
export default defineConfig({
    base: '/meta/',
    plugins: [react()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, 'src'),
        },
    },
    server: {
        port: 5173,
        host: '0.0.0.0',
        proxy: {
            '/api': {
                target: 'http://localhost:18086',
                changeOrigin: true,
            },
        },
    },
    build: {
        outDir: 'dist',
        assetsDir: 'assets',
        emptyOutDir: true,
        sourcemap: false,
    },
});