import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

// Z-Schedule 应用层 — Vite SPA 配置
// dev 端口 5173，proxy /api → 后端 admin (18086)
// build 产物 dist/ 由 admin pom 的 frontend-maven-plugin 在 process-resources 阶段复制到 admin/src/main/resources/static/
//
// base 默认 '/' —— 这是"什么都不设"时唯一正确的值。
//   Mode 1（admin pom 的 frontend-maven-plugin 把 dist 复制进 jar，本地 `java -jar` 起）：
//     application.yml 不设 server.servlet.context-path ⇒ context-path 是空，资源在 /assets/。
//   Mode 2/3（deploy/Dockerfile.frontend + k8s ingress）：nginx/Ingress 的 path 是 /，同样要 /assets/。
// 旧默认值是 '/meta/'，注释说"Mode 1 挂在 /meta context-path 下"——**这句是错的**：
//   /meta 只来自 deploy/Dockerfile.backend 的 ENV，本地 java -jar 拿不到；而 Docker 那条路
//   自己钉了 FRONTEND_BASE=/ （ARG 默认值就是 /），压根不吃这里的默认值。
//   2026-10-15 实测：/meta/ 那份 dist 打进 jar 后 index.html 引 /meta/assets/*.js，
//   白屏；而 SPA 回落还会把那个 404 变成 200 text/html 的 index.html，"全 200"判据照样通过。
//   ⇒ 默认值取对，需要非根挂载时用 VITE_BASE 显式覆盖，别把坑种在默认值里。
export default defineConfig({
    base: process.env.VITE_BASE || '/',
    plugins: [safeEmptyOutDir(), react()],
    resolve: {
    dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
        alias: { '@': path.resolve(__dirname, 'src'), ...(process.env.LOCAL_SIBLINGS === '1' ? { '@yuku123/z-schedule-component': '../z-schedule-component/src' } : {}) },
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
        emptyOutDir: false,
        sourcemap: false,
    },
});