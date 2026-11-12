/**
 * H5 P2（H5-12）白名单页面窄屏复核：本机 Playwright 缓存 Chromium + CDP，
 * 逐页 × 逐宽度检查横向溢出与主操作热区（<44px）。
 *
 * 用法：WENQU_TOKEN=<jwt> node scripts/mac/check-h5-pages.mjs
 * 环境：WEB_BASE（默认 5801）、WIDTHS、ROUTES、MIN_HIT（默认 44）。
 */
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const CACHE = join(homedir(), 'Library/Caches/ms-playwright')
const CHROME = [
  join(CACHE, 'chromium-1247/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'),
  join(CACHE, 'chromium-1247/chrome-mac-arm64/Chromium.app/Contents/MacOS/Chromium'),
].find(existsSync) || ''
const PORT = 9224
const BASE = process.env.WEB_BASE || 'http://127.0.0.1:5801'
const TOKEN = process.env.WENQU_TOKEN || ''
const ANON = process.env.ANON === '1'
const SIZES = (process.env.SIZES || '').split(',').filter(Boolean).map(x => {
  const [w, h] = x.split('x').map(Number)
  return { w, h }
})
const WIDTHS = SIZES.length ? SIZES.map(s => s.w) : (process.env.WIDTHS || '375,390,412,768,1024').split(',').map(Number)
const SCHEME = process.env.SCHEME || ''
const A11Y = process.env.A11Y === '1'
const ROUTES = (process.env.ROUTES || '/artifacts,/knowledge,/project,/profile,/help').split(',')
const MIN_HIT = Number(process.env.MIN_HIT || 44)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

if (!existsSync(CHROME)) { console.error(`[h5] 找不到 Chromium：${CHROME}`); process.exit(2) }
if (!TOKEN && !ANON) { console.error('[h5] 缺少 WENQU_TOKEN（匿名页可设 ANON=1）'); process.exit(2) }

const json = async (path, opts) => (await fetch(`http://127.0.0.1:${PORT}${path}`, opts)).json()

function connect(wsUrl) {
  const ws = new WebSocket(wsUrl)
  let nextId = 1
  const pending = new Map()
  ws.addEventListener('message', (e) => {
    const msg = JSON.parse(e.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
    }
  })
  const ready = new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', reject)
  })
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = nextId++
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
  return { ready, send, close: () => ws.close() }
}

async function main() {
  const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${join('/tmp', 'wenqu-h5-profile')}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', 'about:blank'], { stdio: 'ignore' })
  let failed = 0
  try {
    let version = null
    for (let i = 0; i < 40 && !version; i++) {
      try { version = await json('/json/version') } catch { await sleep(250) }
    }
    if (!version) throw new Error('Chromium CDP 没起来')
    const target = await json('/json/new?about:blank', { method: 'PUT' })
    const cdp = connect(target.webSocketDebuggerUrl)
    await cdp.ready
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')
    await cdp.send('Page.navigate', { url: `${BASE}/` })
    await sleep(1200)
    if (!ANON) {
      await cdp.send('Runtime.evaluate', {
        expression: `localStorage.setItem('ai_token', ${JSON.stringify(TOKEN)}); 'ok'`,
      })
    }

    if (SCHEME) {
      await cdp.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: SCHEME }] })
    }
    const sizeOf = w => SIZES.find(s => s.w === w) || { w, h: w >= 768 ? 1024 : 844 }
    for (const w of WIDTHS) {
      const { h } = sizeOf(w)
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: w, height: h, deviceScaleFactor: 1, mobile: w < 768, screenWidth: w, screenHeight: h,
      })
      for (const route of ROUTES) {
        await cdp.send('Page.navigate', { url: BASE + route })
        await sleep(1400)
        const r = await cdp.send('Runtime.evaluate', {
          returnByValue: true,
          expression: `(() => {
            const vw = innerWidth
            const scrollW = Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)
            const bad = []
            const seen = new Set()
            for (const el of document.querySelectorAll('button, a[href], [role="button"], input[type="button"], input[type="submit"], select')) {
              // 输入框内的清除图标不是主操作，不计入 44px 热区
              if (el.closest('.ant-input-clear-icon') || el.classList.contains('ant-input-clear-icon')) continue
              // 装饰性轮播圆点与开关属于紧凑控件，不计入「主操作」44px 清单
              if (el.classList.contains('hero-dot') || el.closest('.ant-switch') || el.classList.contains('ant-switch')) continue
              const rc = el.getBoundingClientRect()
              if (rc.width <= 0 || rc.height <= 0) continue
              // 只算视口内真正可见的：PC 侧栏在窄屏是画到屏外的，别把屏外热区算进 H5 清单
              if (rc.right <= 0 || rc.bottom <= 0 || rc.left >= innerWidth || rc.top >= innerHeight) continue
              const st = getComputedStyle(el)
              if (st.visibility === 'hidden' || st.display === 'none' || Number(st.opacity) === 0) continue
              if (rc.height >= ${MIN_HIT} && rc.width >= ${MIN_HIT}) continue
              const key = (el.className || '') + '|' + (el.textContent || '').trim().slice(0, 16)
              if (seen.has(key)) continue
              seen.add(key)
              bad.push({ tag: el.tagName.toLowerCase(), cls: String(el.className || '').slice(0, 40),
                text: (el.textContent || '').trim().slice(0, 20), w: Math.round(rc.width), h: Math.round(rc.height) })
            }
            const unnamed = [...document.querySelectorAll('button')].filter(el => {
              const rc = el.getBoundingClientRect()
              if (rc.width <= 0 || rc.height <= 0) return false
              if (rc.right <= 0 || rc.bottom <= 0 || rc.left >= innerWidth || rc.top >= innerHeight) return false
              return !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby') && !(el.textContent || '').trim() && !el.getAttribute('title')
            }).map(el => String(el.outerHTML || '').replace(/\s+/g, ' ').slice(0, 140))
            return { vw, scrollW, title: document.title, badCount: bad.length, bad: bad.slice(0, 6), unnamed }
          })()`,
        })
        const v = r.result.value
        const problems = []
        if (v.scrollW > v.vw + 1) problems.push(`横向溢出 ${v.scrollW}>${v.vw}`)
        if (v.badCount) problems.push(`热区<${MIN_HIT}px ×${v.badCount}`)
        if (A11Y && v.unnamed.length) problems.push(`无名按钮 ×${v.unnamed.length}: ${v.unnamed.slice(0,4).join(',')}`)
        if (problems.length) {
          failed++
          console.log(`[h5] ✗ ${w}px ${route}: ${problems.join('；')} ${JSON.stringify(v.bad)}`)
        } else {
          console.log(`[h5] ✓ ${w}px ${route}`)
        }
      }
    }
    cdp.close()
    return failed === 0
  } finally {
    chrome.kill('SIGTERM')
  }
}
main().then(ok => process.exit(ok ? 0 : 1)).catch(e => { console.error('[h5] 失败：', e.message); process.exit(1) })
