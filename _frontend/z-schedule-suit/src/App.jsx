import { Component, useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from '../../../../_shared/z-frontend-common-local/dist/z-frontend-common.es.js'
import { menuItems, routes } from './pages-manifest'
import LoginPage from './LoginPage'
import { Result, Button } from 'antd'

// vite base（默认 /meta/，Spring context-path）作为 router basename；
// Mode 2 nginx 根路径部署时 VITE_BASE='/'，basename 退化为 undefined。
const BASE = import.meta.env.BASE_URL && import.meta.env.BASE_URL !== '/'
    ? import.meta.env.BASE_URL.replace(/\/$/, '')
    : undefined

class ErrorBoundary extends Component {
    constructor(props) { super(props); this.state = { err: null } }
    static getDerivedStateFromError(err) { return { err } }
    componentDidCatch(err, info) { console.error('[App ErrorBoundary]', err, info) }
    render() {
        if (this.state.err) return <pre style={{ padding: 24, color: 'red', whiteSpace: 'pre-wrap' }}>{String(this.state.err?.stack || this.state.err)}</pre>
        return this.props.children
    }
}

function NotFound() {
    return (
        <Result
            status="404"
            title="页面不存在"
            subTitle="路由表里没有这条路径。"
            extra={<Button type="primary" onClick={() => { window.location.href = '/z-schedule/home' }}>去首页</Button>}
        />
    )
}

function LoginRoute() {
    return <LoginPage />
}

function ProtectedShell() {
    const [user, setUser] = useState(null)
    const [ready, setReady] = useState(false)

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            window.location.replace('/z-schedule/login')
            return
        }
        const raw = localStorage.getItem('userInfo')
        if (raw) { try { setUser(JSON.parse(raw)) } catch { setUser({ name: raw }) } }
        setReady(true)
    }, [])

    if (!ready) return null

    return (
        <AppLayout
            menuItems={menuItems}
            appTitle="Z-Schedule 调度中心"
            appShort="ZS"
            appVersion="0.1.0"
            appUser={user}
        />
    )
}

/** lead 008 §16 suit 一次整合：登录路由 + 鉴权壳 + URL 即状态（§11/§14）。 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter basename={BASE}>
                <Routes>
                    <Route path="/z-schedule/login" element={<LoginRoute />} />
                    <Route element={<ProtectedShell />}>
                        <Route path="/" element={<Navigate to={menuItems[0].key} replace />} />
                        {routes.map((r) => (
                            <Route key={r.path} path={r.path} element={<r.Component />} />
                        ))}
                        <Route path="*" element={<NotFound />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </ErrorBoundary>
    )
}
