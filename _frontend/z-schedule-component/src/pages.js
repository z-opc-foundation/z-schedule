/** ./pages 入口（lead 005 §8.2/§8.6 #3）。重导出 routes + 页面组件。 */
export { routes, appMeta } from './pages-manifest.jsx'
// 文档页从主入口同名导出（suit 侧按这个名字 import）
export { default as ComponentDocPage } from './pages/ComponentDoc'
