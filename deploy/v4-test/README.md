# v4-test/

V4 验证脚手架：**file: 协议跨仓本地消费 z-schedule-component**。

这不是生产代码，是 V4 验收用的**一次性测试**。验证完成后可以删除整个目录。

## 它验证什么

- 业务仓（如未来 z-opc）可以通过 `file:` 协议在**不 npm publish** 的前提下引用 z-schedule 顶层仓的组件层
- 修改 z-schedule-component 源码 → Vite HMR / 直接 bundle → 立刻在消费方可见（**秒级反馈**，避免 npm publish 流程）

## 跑法

```bash
cd deploy/v4-test

# 1. 安装依赖（file: 协议 + react + esbuild）
npm install

# 2. 跑验证
npm test

# 输出预期：
#   === V4 验证输出 ===
#   组件 HTML: ...
#   === 断言 ===
#     ✓ 包含组件签名
#     ✓ 包含 Job 1
#     ✓ 包含 Job 2
#     ✓ 包含骨架标记
#   ✓ V4 验证通过：file: 协议跨仓消费 z-schedule-component 生效（4/4）
```

## 预期机制

- `npm install` 在 `node_modules/@yuku123/z-schedule-component` 创建一个**软链**指向
  `../_frontend/z-schedule-component`（file: 协议标准行为）
- `npm test` 用 esbuild 把 react + 组件层 + 测试代码 bundle 到 `test-v4.bundle.mjs`
- `node test-v4.bundle.mjs` 跑 bundle，调用 react-dom/server 的 `renderToString`
- 输出 HTML 包含组件签名 + 测试数据 + V2 骨架标记

## 关键代码

`test-v4.mjs`：
```js
import { JobListView } from '@yuku123/z-schedule-component';
const html = renderToString(React.createElement(JobListView, { jobs: [...] }));
```

`package.json`：
```jsonc
"dependencies": {
    "@yuku123/z-schedule-component": "file:../_frontend/z-schedule-component"
}
```

## 未来 z-opc 集成时

z-opc 仓（业务方）的 `_frontend/package.json` 同样用 `file:` 协议：

```jsonc
{
  "dependencies": {
    "@yuku123/z-schedule-component": "file:../../z-schedule/_frontend/z-schedule-component",
    "@yuku123/z-mist-frontend-component": "file:../../z-mist/_frontend/z-mist-frontend-component",
    // ... 其他中间件
  }
}
```

业务方 `npm run dev` 时改任一组件层源码 → Vite HMR 立即生效。

## 许可

MIT