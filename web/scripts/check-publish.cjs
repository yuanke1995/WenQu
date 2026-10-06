// 智能体「公开发布」弹窗的真浏览器校验（playwright-core + 系统 Edge）。
// 为什么必须有这一层：这一屏全是"按钮被挤变形 / 弹窗撑到一屏半 / 页签切了内容没换"类的
// 布局与交互问题，静态校验全绿也照样能在真浏览器里破版——本项目已连续两轮栽在这上面。
//
// 前置：npx vite build --outDir dist-ui-verify
//   （不要用 --emptyOutDir：会触发批量删除守卫；校验产物目录名可用 WQ_DIST 覆盖）
// 依赖：cd /tmp/wq-verify && npm i playwright-core
//   NODE_PATH=/tmp/wq-verify/node_modules WQ_DIST=dist-ui-verify node scripts/check-publish.cjs
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过发布弹窗浏览器验证')
  console.log('      安装：cd /tmp/wq-verify && npm i playwright-core')
  process.exit(0)
}
const path = require('path')
const fs = require('fs')

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
if (!fs.existsSync(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE); process.exit(0) }
const DIST_DIR = path.resolve(__dirname, '../' + (process.env.WQ_DIST || 'dist'))
if (!fs.existsSync(path.join(DIST_DIR, 'index.html'))) { console.log('SKIP  未找到构建产物（先 npx vite build）'); process.exit(0) }
const ORIGIN = 'http://pub.local'
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' }

// 一个「已发布 + MCP 已开」的智能体（覆盖信息最全的形态）
const AGENTS = [
  { id: 'a-1', name: '问渠', description: '内置的知识库问答助手', icon: 'wenqu', isBuiltin: 1, manageable: 1,
    published: 1,
    shareConfig: '', workflowId: '', toolKnowledge: 1, createTime: '2026-10-01T10:00:00' }
]
const PUBLISH = { enabled: true, mcpEnabled: true, token: 'tok-abc123', modelRef: '' }

function serveStatic (page) {
  return page.route('**/*', route => {
    const u = new URL(route.request().url())
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data }) })
    if (u.pathname === '/api/ai/auth/me') {
      return route.fulfill({ status: 200, contentType: 'application/json',
        headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify({ success: true, data: { user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: [] } }) })
    }
    if (u.pathname === '/api/ai/agent/list') return json(AGENTS)
    if (u.pathname === '/api/ai/agent/a-1/publish') {
      // POST=弹窗里拨开关（savePublish）：按请求体回写 enabled，模拟后端真实落库
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON() || {}
        PUBLISH.enabled = !!body.enabled
        PUBLISH.mcpEnabled = !!body.mcpEnabled
        return json({ enabled: PUBLISH.enabled, mcpEnabled: PUBLISH.mcpEnabled, token: 'tok-abc123', modelRef: '' })
      }
      return json(PUBLISH)
    }
    if (u.pathname === '/api/ai/kb/list') return json([])
    if (u.pathname === '/api/ai/skill/list' || u.pathname === '/api/ai/skills') return json([])
    if (u.pathname === '/api/ai/mcp/status') return json({ enabled: false })
    if (u.pathname === '/api/ai/agent/sub') return json([])
    if (u.pathname === '/api/ai/kb/param-defaults') return json({})
    if (u.pathname === '/api/ai/workflow/list') return json({ mine: [], shared: [] })
    if (u.host !== 'pub.local') return route.abort()
    let p = decodeURIComponent(u.pathname)
    let f = path.join(DIST_DIR, p)
    if (p === '/' || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST_DIR, 'index.html')
    try {
      route.fulfill({ status: 200, body: fs.readFileSync(f), headers: { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' } })
    } catch (e) { route.abort() }
  })
}

let bad = 0
const check = (ok, label, detail = '') => {
  if (!ok) bad++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  →  ' + detail : ''}`)
}

;(async () => {
  const browser = await chromium.launch({ executablePath: EDGE, headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  // 发布弹窗在 AppLayout 内，走路由守卫：无 ai_token 会被弹到 /login（2026-10 实测）
  await ctx.addInitScript(() => {
    try {
      localStorage.setItem('ai_token', 'verify-token')
      localStorage.setItem('ai_role', 'admin')
    } catch (e) { /* ignore */ }
  })
  const page = await ctx.newPage()
  await serveStatic(page)
  const errors = []
  page.on('pageerror', e => errors.push(String(e.message)))
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

  await page.goto(ORIGIN + '/agents', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)

  // 卡片级发布状态（本次新增）：列表接口带 published，发布中的智能体卡片标「已发布」
  const chipOn = await page.evaluate(() =>
    [...document.querySelectorAll('.ap-card .ap-chip')].some(c => c.textContent.trim() === '已发布'))
  check(chipOn, '列表卡片对发布中的智能体标「已发布」')

  // 从卡片点「发布」进弹窗（这条入口曾因 @click.stop 缺失而打不开）
  const cardBtns = await page.$$('.ap-card-foot .app-link-btn')
  let opened = false
  for (const b of cardBtns) {
    if ((await b.textContent()).trim() === '发布') { await b.click(); opened = true; break }
  }
  check(opened, '智能体卡片上「发布」入口可点（@click.stop 生效，不误触发进配置）')
  await page.waitForSelector('.pub-status', { timeout: 5000 }).catch(() => {})
  await page.waitForTimeout(700)

  const m = await page.evaluate(() => {
    const de = document.documentElement
    const modal = document.querySelector('.ap-pub-modal') || document.querySelector('.ant-modal-content')
    const mr = modal ? modal.getBoundingClientRect() : { height: 0, top: 0 }
    const status = document.querySelector('.pub-status')
    const tabs = [...document.querySelectorAll('.pub-tabs .ant-tabs-tab')]
    const active = document.querySelector('.pub-tabs .ant-tabs-tab-active')
    const code = document.querySelector('.pub-code')
    const btns = [...document.querySelectorAll('.pub-code-act .pub-icon-btn')]
    // 「复 制」竖排是 flex 压缩的典型症状：按钮被压到容不下内容时，中文逐字折行。
    // 现在是方块图标按钮（内含单个图标字符、绝不折行），故判据从「宽度阈值」改成
    // 「宽高都必须是方形且不小于设计尺寸」——压扁会表现为宽高不等。
    const squeezed = btns.filter(b => {
      const r = b.getBoundingClientRect()
      return Math.abs(r.width - r.height) > 2 || r.width < 26
    }).length
    // 按钮必须浮在代码框**内部**（此前独占一列，把单行 URL 挤成两行）
    const boxes = [...document.querySelectorAll('.pub-code-box')]
    const insideAll = boxes.every(bx => {
      const br = bx.getBoundingClientRect()
      return [...bx.querySelectorAll('.pub-icon-btn')].every(btn => {
        const r = btn.getBoundingClientRect()
        return r.left >= br.left - 1 && r.right <= br.right + 1 && r.top >= br.top - 1
      })
    })
    // 代码框占满整行：右边界贴近弹窗内容区（独占一列时右边会空出一列按钮的宽度）
    const codeVsBox = boxes.length ? Math.round(boxes[0].getBoundingClientRect().width) : 0
    const tabsW = (() => { const t = document.querySelector('.pub-tabs .ant-tabs-tabpane-active'); return t ? Math.round(t.getBoundingClientRect().width) : 0 })()
    return {
      hasStatus: !!status, statusOn: status ? status.classList.contains('on') : false,
      statusText: status ? (status.querySelector('.pub-status-t') || {}).textContent || '' : '',
      tabs: tabs.map(t => t.textContent.trim()),
      activeTab: active ? active.textContent.trim() : '',
      codeText: code ? code.textContent.trim() : '',
      btnN: btns.length, squeezed, insideAll, codeVsBox, tabsW,
      btnW: btns.length ? Math.round(btns[0].getBoundingClientRect().width) : 0,
      modalH: Math.round(mr.height), modalTop: Math.round(mr.top),
      avatarInTitle: !!document.querySelector('.pub-title svg'),
      warn: !!document.querySelector('.pub-warn'),
      revoke: !!document.querySelector('.pub-foot .danger'),
      scrollW: de.scrollWidth, clientW: de.clientWidth
    }
  })
  check(m.hasStatus && m.statusOn && m.statusText.includes('已发布'), '状态卡显示「已发布」（一眼看出发没发）', m.statusText)
  check(m.avatarInTitle, '弹窗标题带智能体头像（品牌标 SVG）')
  check(m.tabs.length === 3 && m.activeTab === '分享链接', '三种分发形态收进页签，默认停在分享链接', m.tabs.join('/'))
  check(m.codeText.includes('/s/tok-abc123'), '分享链接页签给出真实链接', m.codeText)
  check(m.squeezed === 0 && m.btnW >= 28, '代码框内按钮未被挤成竖排单字', `btn=${m.btnN} squeezed=${m.squeezed} w=${m.btnW}`)
  check(m.insideAll, '复制/打开按钮浮在代码框内部（不再独占一列）')
  check(m.codeVsBox > 0 && m.tabsW - m.codeVsBox < 4, '代码框占满整行宽度（右侧不空出一列）', `code=${m.codeVsBox} pane=${m.tabsW}`)
  check(!m.warn, '已发布态不再展示「MCP 依赖公开分享」的警告条')
  check(m.revoke, '底部有独立的「撤销分享」')
  check(m.scrollW <= m.clientW + 1, '弹窗无横向溢出', `scrollW=${m.scrollW} clientW=${m.clientW}`)
  // 弹窗高度：此前平铺三段形态撑到一屏半；页签化后应收敛到一屏内
  check(m.modalH <= 900, '弹窗高度收敛在一屏内（不再撑到一屏半）', `h=${m.modalH} top=${m.modalTop}`)

  // 切到 iframe / MCP 页签：内容必须真的换掉。
  // 断言只取**可见**的 .pub-code：antd Tabs 默认销毁未激活页签的 DOM，
  // 但相邻页签若已挂载（forceRender），全局 querySelector 会先撞到隐藏那份。
  let tabs2 = await page.$$('.pub-tabs .ant-tabs-tab')
  await tabs2[1].click(); await page.waitForTimeout(600)
  const iframeTab = await page.evaluate(() => {
    const pane = document.querySelector('.pub-tabs .ant-tabs-tabpane-active')
    const code = pane && pane.querySelector('.pub-code')
    return { text: code ? code.textContent.trim() : '', isIframe: !!(code && code.textContent.includes('<iframe')) }
  })
  check(iframeTab.isIframe, '切到「嵌入网页」页签内容随之切换（iframe 代码）', iframeTab.text.slice(0, 46))

  // 尺寸可调：改宽高后代码里的 style 必须跟着变（此前 420×640 写死，用户只能复制出去手改）
  const sizeInputs = await page.$$('.pub-size .ant-input-number-input')
  if (sizeInputs.length >= 2) {
    await sizeInputs[0].fill('600')
    // blur 不是 ElementHandle 方法；按 Tab 移开焦点提交 v-model。
    // 不要用 mouse.click 提交——落点很容易在遮罩上，点一下就把弹窗关了。
    await page.keyboard.press('Tab')
    await page.waitForTimeout(500)
  }
  const resized = await page.evaluate(() => {
    const pane = document.querySelector('.pub-tabs .ant-tabs-tabpane-active')
    const code = pane && pane.querySelector('.pub-code')
    return { text: code ? code.textContent : '' }
  })
  check(resized.text.includes('width:600px'), '改嵌入宽度后代码里的 style 同步更新', resized.text.match(/width:[^;]+/)?.[0] || 'n/a')

  tabs2 = await page.$$('.pub-tabs .ant-tabs-tab')
  await tabs2[2].click(); await page.waitForTimeout(600)
  const mcpTab = await page.evaluate(() => {
    const pane = document.querySelector('.pub-tabs .ant-tabs-tabpane-active')
    const codes = pane ? [...pane.querySelectorAll('.pub-code')].map(c => c.textContent.trim()) : []
    return { n: codes.length, hasUrl: codes.some(c => c.includes('/ai/mcp/tok-abc123')),
      hasJson: codes.some(c => c.includes('mcpServers')) }
  })
  check(mcpTab.hasUrl && mcpTab.hasJson, 'MCP 页签同时给出端点地址与 mcp.json（复制按钮各一份）', JSON.stringify(mcpTab))

  // 窄屏（412）下 /agents 整页走 check-guard 的「建议用电脑访问」引导卡，弹窗不存在，
  // 在这里断言弹窗尺寸是测一个永远为 0 的元素。真正会破版的是**空间紧张但仍是 PC 壳**的宽度
  // （768–1024：平板横屏 / 分屏 / 侧栏挤压），这里钉 820px。
  await page.setViewportSize({ width: 820, height: 900 })
  await page.waitForTimeout(600)
  const narrow = await page.evaluate(() => {
    const de = document.documentElement
    const el = document.querySelector('.ap-pub-modal')
    const mr = el ? el.getBoundingClientRect() : { width: 0 }
    // 可见页签内取：antd Tabs 会销毁未激活页签的 DOM
    const pane = document.querySelector('.pub-tabs .ant-tabs-tabpane-active')
    const btn = (pane || document).querySelector('.pub-code-act .pub-icon-btn')
    return { w: Math.round(mr.width || 0), scrollW: de.scrollWidth, clientW: de.clientWidth,
      pane: !!pane, rowN: document.querySelectorAll('.pub-code-box').length,
      btnW: btn ? Math.round(btn.getBoundingClientRect().width) : 0 }
  })
  check(narrow.scrollW <= narrow.clientW + 1, '窄宽度（820）弹窗无横向溢出', `scrollW=${narrow.scrollW} clientW=${narrow.clientW}`)
  check(narrow.btnW >= 28, '窄宽度下框内复制按钮仍是可点尺寸（不被压扁）', `w=${narrow.btnW} pane=${narrow.pane} boxN=${narrow.rowN}`)

  const real = errors.filter(e => !/Failed to load resource|ERR_FAILED|401|Failed to fetch/i.test(e))
  check(real.length === 0, '无 JS 运行时错误', real.slice(0, 3).join(' | '))

  // 关掉总开关（savePublish 走 POST）：卡片上的「已发布」必须即时消失——本次修的正是
  // 「发布后界面无感知」：弹窗里改态后同步列表行，不重拉、不刷新。
  await page.click('.pub-status .ant-switch')
  await page.waitForTimeout(800)
  const chipAfter = await page.evaluate(() =>
    [...document.querySelectorAll('.ap-card .ap-chip')].some(c => c.textContent.trim() === '已发布'))
  check(!chipAfter, '弹窗里停用发布后，卡片「已发布」标记即时消失')

  await browser.close()
  console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
  process.exit(bad ? 1 : 0)
})().catch(e => { console.error('验证脚本异常：', e.message); process.exit(2) })