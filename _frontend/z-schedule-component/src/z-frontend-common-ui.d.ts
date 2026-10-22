/**
 * 基座 UI 桶（@yuku123/z-frontend-common/ui）的类型声明。
 *
 * 【为什么手写而不是等基座出 d.ts】
 * 基座是纯 JS 包（dist 里只有 js，没有 .d.ts），exports 的 './ui' 子路径也没配 types，
 * TS 直接 import 会报 TS7016 "Could not find a declaration file"。本仓是 TS 仓，
 * 所以只能像 z-surl / z-kb 那样手写一份最小声明。
 *
 * 【维护约定】
 * 这里只声明本仓真正用到的组件；**props 以基座源码 src/components/ui/*.jsx 为准**，
 * 基座升版改了签名要回来同步（尤其是 ErrorState 的 variant / EmptyState 的 size /
 * ListTable 的 rows 而非 antd 的 dataSource）。
 */
declare module '@yuku123/z-frontend-common/ui' {
  import type { CSSProperties, ReactElement, ReactNode } from 'react'

  export function EmptyState(props: {
    icon?: ReactNode
    title?: string
    description?: ReactNode
    actionText?: string
    onAction?: () => void
    size?: 'sm' | 'md' | 'lg'
    style?: CSSProperties
  }): ReactElement

  export function ErrorState(props: {
    error?: unknown
    title?: string
    description?: ReactNode
    onRetry?: () => void
    retryText?: string
    variant?: 'inline' | 'full'
    style?: CSSProperties
  }): ReactElement

  export function PageHeader(props: {
    title?: string
    subtitle?: string
    breadcrumb?: Array<{ label: string; href?: string }>
    extra?: ReactNode
    back?: boolean
    onBack?: () => void
  }): ReactElement

  /**
   * §2 列表页成品件：分页 + 查询 + 空态三件套一次到位。
   * 注意数据入口是 rows（不是 antd 的 dataSource）。
   */
  export function ListTable(props: {
    rows?: any[]
    columns?: any[]
    rowKey?: string | ((row: any, index: number) => string)
    loading?: boolean
    searchKeys?: string | string[]
    searchPlaceholder?: string
    searchWidth?: number
    cardTitle?: ReactNode
    cardExtra?: ReactNode
    emptyTitle?: string
    emptyDescription?: ReactNode
    emptyActionText?: string
    emptyOnAction?: () => void
    pagination?: any
    cardProps?: any
    tableProps?: any
    [key: string]: any
  }): ReactElement

  /**
   * 组件文档页（lead 008 §7）：左侧 240px TOC + 右侧正文。
   * markdown 是编译期 `?raw` 拿到的字符串，不是运行时 fetch。
   */
  export function ComponentDoc(props: {
    markdown: string
    title?: string
    tocWidth?: number
    tocLevels?: number[]
    height?: string
  }): ReactElement
}

declare module '@yuku123/z-frontend-common/ui/*' {
  const x: any
  export default x
}

/**
 * vite `?raw` 导入声明 —— 编译期把文件内容内联成字符串，不是 fetch。
 * DOC.md 就是这么进包的。
 */
declare module '*.md?raw' {
  const content: string
  export default content
}
