/**
 * H5-11 知识图谱移动化实测：登录后打开指定知识库卡片菜单 →「知识图谱」，
 * 在给定宽度断言弹窗全屏、无横向溢出、三栏纵向堆叠。
 * 用法：WENQU_TOKEN=<jwt> KB_NAME=图谱测试库 WIDTHS=390,1024 node scripts/mac/check-graph-mobile.mjs
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
const PORT = 9225
const BASE = process.env.WEB_BASE || 'http://127.0.0.1:5801'
const TOKEN = process.env.WENQU_TOKEN || ''
const KB = process.env.KB_NAME || '图谱测试库'
const WIDTHS = (process.env.WIDTHS || '390,1024').split(',').map(Number)
const sleep = ms => new Promise(r => setTimeout(r, ms))
if (!existsSync(CHROME)) { console.error('no chromium'); process.exit(2) }
if (!TOKEN) { console.error('no token'); process.exit(2) }
const json = async (p, o) => (await fetch(`http://127.0.0.1:${PORT}${p}`, o)).json()
function connect(u) {
  const ws = new WebSocket(u); let id = 1; const pend = new Map()
  ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { const { res, rej } = pend.get(m.id); pend.delete(m.id); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result) } })
  const ready = new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j) })
  const send = (method, params = {}) => new Promise((res, rej) => { const i = id++; pend.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method, params })) })
  return { ready, send, close: () => ws.close() }
}
const evalJs = async (cdp, expression) => (await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result.value
async function main() {
  const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, '--user-data-dir=/tmp/wenqu-graph-profile', '--no-first-run', '--disable-gpu', 'about:blank'], { stdio: 'ignore' })
  let fail = 0
  try {
    let v = null
    for (let i = 0; i < 40 && !v; i++) { try { v = await json('/json/version') } catch { await sleep(250) } }
    const target = await json('/json/new?about:blank', { method: 'PUT' })
    const cdp = connect(target.webSocketDebuggerUrl); await cdp.ready
    await cdp.send('Page.enable'); await cdp.send('Runtime.enable')
    await cdp.send('Page.navigate', { url: `${BASE}/` }); await sleep(1200)
    await evalJs(cdp, `localStorage.setItem('ai_token', ${JSON.stringify(TOKEN)}); 'ok'`)
    for (const w of WIDTHS) {
      const h = w >= 768 ? 1024 : 844
      await cdp.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 768, screenWidth: w, screenHeight: h })
      await cdp.send('Page.navigate', { url: `${BASE}/knowledge` }); await sleep(1600)
      const clicked = await evalJs(cdp, `(() => {
        const card = [...document.querySelectorAll('.kb-card')].find(c => c.textContent.includes(${JSON.stringify(KB)}))
        const btn = card && card.querySelector('.menu-wrap button')
        if (btn) btn.click(); return !!btn
      })()`)
      if (!clicked) { const t = await evalJs(cdp, 'document.body.innerText.slice(0, 400)'); console.log(`[graph] ✗ ${w}px 找不到知识库卡片 ${KB}；页面文本=${JSON.stringify(t)}`); fail++; continue }
      await sleep(400)
      const opened = await evalJs(cdp, `(() => {
        const it = [...document.querySelectorAll('.ant-dropdown-menu-item')].find(e => e.textContent.includes('知识图谱'))
        if (it) it.click(); return !!it
      })()`)
      if (!opened) { console.log(`[graph] ✗ ${w}px 找不到「知识图谱」菜单`); fail++; continue }
      await sleep(1200)
      const r = await evalJs(cdp, `(() => {
        const modal = document.querySelector('.kb-graph-workspace .ant-modal')
        if (!modal) return { open: false }
        const rc = modal.getBoundingClientRect()
        const body = modal.querySelector('.ant-modal-body')
        const shell = modal.querySelector('.gx-body')
        return { open: true, width: Math.round(rc.width), scrollW: document.documentElement.scrollWidth, vw: innerWidth,
          bodyH: body ? Math.round(body.getBoundingClientRect().height) : 0,
          stacked: !!shell && getComputedStyle(shell).flexDirection === 'column' }
      })()`)
      const problems = []
      if (!r.open) problems.push('弹窗未打开')
      if (r.open && r.width < r.vw - 2) problems.push(`未全屏 ${r.width}<${r.vw}`)
      if (r.scrollW > r.vw + 1) problems.push(`横向溢出 ${r.scrollW}>${r.vw}`)
      if (w < 768 && r.open && !r.stacked) problems.push('三栏未纵向堆叠')
      console.log(`[graph] ${problems.length ? '✗' : '✓'} ${w}px ${JSON.stringify(r)} ${problems.join('；')}`)
      if (problems.length) fail++
    }
    cdp.close()
    return fail === 0
  } finally { chrome.kill('SIGTERM') }
}
main().then(ok => process.exit(ok ? 0 : 1)).catch(e => { console.error('[graph] 失败：', e.message); process.exit(1) })
