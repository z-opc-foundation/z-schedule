import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout, ErrorBoundary } from '@yuku123/z-frontend-common'
import { menuItems, routes } from './pages-manifest'
import LoginPage from './LoginPage'
import { Result, Button } from 'antd'

import { at, BASE } from './routes-base'
// 版本号只认 package.json，避免顶栏版本与发版号两处各写一遍慢慢漂移。
import { version as appVersion } from '../package.json'

// vite base（默认 /meta/，Spring context-path）作为 router basename；
// Mode 2 nginx 根路径部署时 VITE_BASE='/'，basename 退化为 undefined。
const ROUTER_BASENAME = BASE || undefined

function NotFound() {
    return (
        <Result
            status="404"
            title="页面不存在"
            subTitle="路由表里没有这条路径。"
            extra={<Button type="primary" onClick={() => { window.location.href = at('/z-schedule/home') }}>去首页</Button>}
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
            window.location.replace(at('/z-schedule/login'))
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
            appVersion={appVersion}
            appIcon={{
                icon: <img src="/icon.png" alt="Z-Schedule 调度中心" style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8 }} />,
                color: '#10b981',
                label: 'Z-Schedule 调度中心',
            }}
            appUser={user}
        />
    )
}

/** lead 008 §16 suit 一次整合：登录路由 + 鉴权壳 + URL 即状态（§11/§14）。 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter basename={ROUTER_BASENAME}>
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
