/**
 * 移动端（390×844）真渲染检查：用本机 Playwright 缓存里的 Chromium 走 CDP，
 * 不装新依赖（Node 20 的 `--experimental-websocket` 提供全局 WebSocket）。
 *
 * 查什么：
 *   1. 页面在 390px 宽下**没有横向滚动**（`scrollWidth <= innerWidth + 1`）；
 *   2. 已登录会话能正常回显：正文、工具卡、终止提示都在，提示只出现一次；
 *   3. 分享页在移动端只出答案 + 中止提示，没有工具卡/过程。
 *
 * 前置：8091 后端 + 5801 vite（`VITE_PROXY_TARGET=http://localhost:8091/ai`）；会话 id 与分享 token 由参数给。
 *
 * 用法：
 *   node scripts/mac/check-mobile-layout.mjs --session <sid> [--share <token>] [--shot /tmp/mobile.png]
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const CACHE = join(homedir(), 'Library/Caches/ms-playwright')
/** Playwright 缓存里的 Chromium 名字随版本变过：按候选顺序找一个存在的。 */
const CHROME = [
  join(CACHE, 'chromium-1247/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'),
  join(CACHE, 'chromium-1247/chrome-mac-arm64/Chromium.app/Contents/MacOS/Chromium'),
].find(existsSync) || ''
const PORT = 9223
const BASE = process.env.WEB_BASE || 'http://127.0.0.1:5801'
const arg = (name, dft) => {
  const i = process.argv.indexOf(`--${name}`)
  return i > 0 ? process.argv[i + 1] : dft
}
const SESSION = arg('session')
const SHARE = arg('share')
const SHOT = arg('shot', '/tmp/wenqu-mobile.png')
const DUMP = arg('dump', '')
/** 不给 --expect 时按「停止轮」断言（历史默认）；给了就只断言这段话出现且正文够长，可配 --width/--height 看桌面宽度。 */
const EXPECT = arg('expect', '')
const W = Number(arg('width', 390))
const H = Number(arg('height', 844))
const MOBILE = arg('mobile', '1') !== '0'

if (!existsSync(CHROME)) {
  console.error(`[mobile] 找不到 Chromium：${CHROME}`)
  process.exit(2)
}
if (!SESSION) {
  console.error('[mobile] 缺少 --session <会话 id>')
  process.exit(2)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function json(path, opts) {
  const res = await fetch(`http://127.0.0.1:${PORT}${path}`, opts)
  return res.json()
}

/** 极简 CDP 客户端：发命令、等回包、收事件。 */
function connect(wsUrl) {
  const ws = new WebSocket(wsUrl)
  let nextId = 1
  const pending = new Map()
  const events = []
  ws.addEventListener('message', (e) => {
    const msg = JSON.parse(e.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
    } else if (msg.method) {
      events.push(msg)
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
  return { ready, send, close: () => ws.close(), events }
}

/** 轮询直到表达式为真，返回它的值；超时返回 null。 */
async function poll(cdp, expression, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true })
    if (r?.result?.value) return r.result.value
    await sleep(300)
  }
  return null
}

const LAUNCH_ARGS = [
  '--headless=new', `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${join('/tmp', 'wenqu-mobile-profile')}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu', 'about:blank',
]

async function main() {
  const chrome = spawn(CHROME, LAUNCH_ARGS, { stdio: 'ignore', detached: false })
  try {
    // 等 CDP 端口就绪
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
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: W, height: H, deviceScaleFactor: MOBILE ? 2 : 1, mobile: MOBILE,
      screenWidth: W, screenHeight: H,
    })

    const token = process.env.WENQU_TOKEN || ''
    if (!token) throw new Error('缺少 WENQU_TOKEN 环境变量（本地自签 token）')
    await cdp.send('Page.navigate', { url: `${BASE}/` })
    await sleep(1200)
    await cdp.send('Runtime.evaluate', {
      expression: `localStorage.setItem('ai_token', ${JSON.stringify(token)}); 'ok'`,
    })

    const check = async (name, url, predicate) => {
      await cdp.send('Page.navigate', { url })
      const ok = await poll(cdp, `(() => { ${predicate} })()`, 20000)
      const metrics = await cdp.send('Runtime.evaluate', {
        expression: `({ w: innerWidth, scrollW: document.documentElement.scrollWidth, bodyW: document.body.scrollWidth })`,
        returnByValue: true,
      })
      const m = metrics.result.value
      const problems = []
      if (m.w !== W) problems.push(`视口宽 ${m.w} ≠ ${W}`)
      if (m.scrollW > m.w + 1) problems.push(`横向溢出：scrollWidth=${m.scrollW} > ${m.w}`)
      if (!ok) problems.push('页面断言未通过（内容没渲染出来或被裁掉）')
      console.log(`[mobile] ${name}: ${JSON.stringify(m)} ${problems.length ? '✗ ' + problems.join('；') : '✓'}`)
      return problems.length === 0
    }

    const bubble = `const b = [...document.querySelectorAll('.bubble.ai')].pop();
       const bt = b ? b.innerText : '';`
    const chatPredicate = EXPECT
      ? `${bubble}
         return bt.includes(${JSON.stringify(EXPECT)}) && bt.replace(/\\s/g,'').length > 200
           && !!document.querySelector('textarea, [contenteditable="true"]')`
      : `${bubble}
         return bt.includes('已停止本轮') && (bt.match(/已停止本轮/g) || []).length === 1
           && bt.replace(/\\s/g,'').length > 20
           && !!document.querySelector('textarea, [contenteditable="true"]')`
    const one = await check(EXPECT ? '主对话(真实轮)' : '主对话(停止轮)',
      `${BASE}/chat?sid=${encodeURIComponent(SESSION)}`, chatPredicate)

    let two = true
    if (SHARE) {
      const sharePredicate = EXPECT
        ? `const t = document.body.innerText;
           return t.includes('对话分享') && t.includes(${JSON.stringify(EXPECT)})
             && !/已调用工具|向用户提问|过程/.test(t)`
        : `const t = document.body.innerText;
           return t.includes('对话分享') && t.includes('已停止本轮')
             && !/已调用工具|向用户提问|过程/.test(t)`
      two = await check('分享页', `${BASE}/shared/${encodeURIComponent(SHARE)}`, sharePredicate)
    }

    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
    mkdirSync(join(SHOT, '..'), { recursive: true })
    writeFileSync(SHOT, Buffer.from(shot.data, 'base64'))
    console.log(`[mobile] 截图 → ${SHOT}`)
    if (DUMP) {
      const text = await cdp.send('Runtime.evaluate', { expression: 'document.body.innerText', returnByValue: true })
      writeFileSync(DUMP, text.result.value || '')
      console.log(`[mobile] 文本 → ${DUMP}`)
    }
    cdp.close()
    return one && two
  } finally {
    chrome.kill('SIGTERM')
  }
}

main().then((ok) => process.exit(ok ? 0 : 1)).catch((e) => {
  console.error('[mobile] 失败：', e.message)
  process.exit(1)
})
