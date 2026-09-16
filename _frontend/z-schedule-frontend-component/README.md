# z-schedule-frontend-component

> **组件层**：可复用 React 组件包，按规范 §1.2 放于 `_frontend/z-schedule-frontend-component/`。
> **本仓 admin** 通过 `file:` 协议本地消费；**外部项目**（如 z-opc）未来可通过 `npm publish` 后安装。

## 它是什么

纯组件库，**无路由、无业务 fetch**。所有视图组件接 props（遵循规范 §5），
让上层（z-schedule admin / z-opc 重组页）自由装配数据源。

## 当前状态

- **V2 骨架**：仅 `JobListView` 占位组件 + Vite library mode 配置
- 私有（`"private": true`）：**不发布**，仅本仓 admin 引用
- 验证链路：组件层 build → 应用层 import → admin jar 内嵌 → 浏览器加载

## build

```bash
cd _frontend/z-schedule-frontend-component
npm install
npm run build
# 产物：dist/index.js（ES Module）
```

## 何时改 public + npm publish

- 当 z-opc 等**仓外项目**需要 import 这些组件时
- 操作：改 `private: false` → `npm login` → `npm publish --access public`
- 当前 V2 阶段**不要 publish**，避免污染 npmjs

## 与应用层关系

```
_frontend/
├── z-schedule-frontend/                      ← 应用层（admin UI）
│   └── package.json 依赖：
│       "@yuku123/z-schedule-frontend-component": "file:../z-schedule-frontend-component"
└── z-schedule-frontend-component/            ← 当前目录（你在这里）
    └── 产物 dist/index.js 被应用层 import
```

## 许可

MIT