# _frontend

> **前端容器**：按规范 §1.2 命名布局，**不带 package.json**，是下划线开头的容器目录。
> 内部两个 npm 项目通过 `file:` 协议互相消费，未来多端扩展（mobile/desktop/openapi-docs）
> 在本目录下加子项目即可。

## 子项目布局

```
_frontend/
├── z-schedule-suit/              ← 应用层（SPA，admin UI）
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   └── src/
└── z-schedule-component/    ← 组件层（library mode，可复用 React 组件）
    ├── package.json
    ├── vite.config.js
    └── src/
```

## 都不是 Maven 模块

`_frontend/` 容器**不进** z-schedule 顶层 `pom.xml` 的 `<modules>`。
两个子项目**也不进**。

只有 Java module（z-schedule-core / z-schedule-spring-boot-starter / z-schedule-admin）进 reactor。

## 与后端集成

`z-schedule-admin/pom.xml` 的 `frontend-maven-plugin` 配置指向
`_frontend/z-schedule-suit/`，在 process-resources 阶段：

1. `npm install`（如 node_modules 不存在）
2. `npm run build` → 产物 `dist/`
3. `maven-resources-plugin` 把 `dist/` 复制到 `../z-schedule-admin/src/main/resources/static/`
4. `spring-boot-maven-plugin:repackage` 打成 -exec.jar
5. 内嵌前端跟着 `java -jar` 一起跑

## .gitignore 策略

- `_frontend/.gitignore`：npm 通用 + 容器级构建产物
- 子项目 `.gitignore`：只声明特有项（`.vite`、`*.tsbuildinfo` 等）

## 许可

MIT