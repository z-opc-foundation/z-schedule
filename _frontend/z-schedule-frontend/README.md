# z-schedule-frontend

> **应用层**：Z-Schedule admin UI，按规范 §1.2 放于 `_frontend/z-schedule-frontend/`。
> 产物 `dist/` 由 `admin/pom.xml` 的 frontend-maven-plugin 在 process-resources 阶段
> 复制到 `z-schedule-admin/src/main/resources/static/`，内嵌进 admin 的 exec jar。

## 它是什么

SPA 形态的 admin 控制台。本仓独立运行模式：

```bash
npm run dev    # localhost:5173，proxy /api → localhost:18086
npm run build  # 产物 dist/
```

## 与组件层关系

```jsonc
// package.json
"dependencies": {
    "@yuku123/z-schedule-frontend-component": "file:../z-schedule-frontend-component"
}
```

`file:` 协议让 npm 把同仓组件层**软链**到 node_modules，
改组件层源码 → Vite HMR 立即生效（**秒级反馈**，无需 publish，遵循规范 §3）。

## 与 admin jar 集成

`mvn -pl z-schedule-admin -am package` 触发：

```
process-resources 阶段
  → frontend-maven-plugin 在 _frontend/z-schedule-frontend/ 跑 npm install + npm run build
  → 产物 dist/
  → maven-resources-plugin 把 dist/ 复制到 ../z-schedule-admin/src/main/resources/static/
  → spring-boot-maven-plugin:repackage 把整个 jar 打成 -exec.jar
  → java -jar z-schedule-admin-{ver}-exec.jar 内嵌前端一起跑
```

## 当前状态

- **V2 骨架**：仅顶部 header + `JobListView` 占位组件
- 私有（`"private": true`）：不发布
- 验证目标：`mvn package` 后 jar 内的 static/ 包含 index.html + assets/*

## 许可

MIT