import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

// node --test /absolute/path/to/vite.config.test.mjs (no npm install required).
const suit = fileURLToPath(new URL('.', import.meta.url))
const targets = {
    '@yuku123/z-schedule-component/pages': '../z-schedule-component/src/pages.js',
    '@yuku123/z-schedule-component': '../z-schedule-component/src/index.jsx',
    '@yuku123/z-frontend-common/ui': '../../../z-frontend-common/src/components/ui/index.js',
    '@yuku123/z-frontend-common': '../../../z-frontend-common/src/index.js',
}
let serial = 0
async function config(local, base) {
    const previous = { LOCAL_SIBLINGS: process.env.LOCAL_SIBLINGS, VITE_BASE: process.env.VITE_BASE }
    try {
        for (const [key, value] of Object.entries({ LOCAL_SIBLINGS: local, VITE_BASE: base })) {
            if (value === undefined) delete process.env[key]
            else process.env[key] = value
        }
        return (await import(`./vite.config.js?regression=${++serial}`)).default
    } finally {
        for (const [key, value] of Object.entries(previous)) {
            if (value === undefined) delete process.env[key]
            else process.env[key] = value
        }
    }
}
const matches = (entry, id) => entry.find instanceof RegExp
    ? entry.find.test(id) : id === entry.find || id.startsWith(`${entry.find}/`)

for (const local of [undefined, '0', '1', 'true']) {
    test(`LOCAL_SIBLINGS=${local ?? '<unset>'}: aliases, fs scope, proxy and defaults`, async () => {
        const c = await config(local)
        assert.equal(c.base, '/')
        assert.equal(c.build.outDir, 'dist')
        assert.equal(c.build.emptyOutDir, false)
        assert.deepEqual(c.server.proxy, { '/api': { target: 'http://localhost:18086', changeOrigin: true } })
        assert.equal(c.server.port, 5173)
        assert.deepEqual(c.server.fs.allow, [fileURLToPath(new URL(local === '1' ? '../../../' : '../', import.meta.url))])
        assert.deepEqual(c.resolve.dedupe, ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'])
        const at = c.resolve.alias.find((a) => a.find === '@')
        assert.equal(at.replacement, path.join(suit, 'src'))
        assert.ok(matches(at, '@/pages-manifest'))
        assert.equal(c.resolve.alias.length, local === '1' ? 5 : 1)
        for (const [id, relative] of Object.entries(targets)) {
            const entries = c.resolve.alias.filter((a) => matches(a, id))
            assert.equal(entries.length, local === '1' ? 1 : 0, id)
            if (local !== '1') continue
            const entry = entries[0]
            assert.ok(path.isAbsolute(entry.replacement), id)
            assert.equal(entry.replacement, path.resolve(suit, relative), id)
            assert.ok(existsSync(entry.replacement), id)
            assert.equal(matches(entry, `${id}/unexpected-subpath`), false, id)
        }
        for (const id of ['@yuku123/z-frontend-common/ui/echarts', '@yuku123/z-schedule-component/pages-extra']) {
            assert.equal(c.resolve.alias.some((a) => matches(a, id)), false, id)
        }
    })
}

for (const local of [undefined, '1']) {
    test(`explicit base preserved without changing backend proxy (LOCAL=${local ?? '<unset>'})`, async () => {
        for (const base of ['/review/', '/nested/review/', '/']) {
            const c = await config(local, base)
            assert.equal(c.base, base)
            assert.deepEqual(c.server.proxy, { '/api': { target: 'http://localhost:18086', changeOrigin: true } })
            assert.equal(c.build.assetsDir, 'assets')
            assert.equal(c.build.sourcemap, false)
        }
    })
}
