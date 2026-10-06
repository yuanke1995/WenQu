// 移动壳（/m/chat）的真浏览器校验：playwright-core 驱动系统 Edge，412×916 触屏视口跑 dist 产物。
// 与 check-browser.cjs 分工：那个验「PC 壳（含鼠标用户拖窄窗口的窄屏路径）」，这个验「手机进移动壳」。
//
// 前置：先构建产物到 web/dist
//   npx vite build --outDir dist --emptyOutDir
// 依赖：playwright-core（不装进项目，临时用）：
//   mkdir -p /tmp/wq-verify && cd /tmp/wq-verify && npm i playwright-core
//   NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-mshell.cjs
// 无 playwright-core 时会给出提示并跳过（exit 0），不阻塞其它校验。
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过移动壳浏览器验证')
  console.log('      安装：cd /tmp/wq-verify && npm i playwright-core')
  process.exit(0)
}
const path = require('path')
const fs = require('fs')

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
if (!fs.existsSync(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE + '（可用 WQ_BROWSER 指定）'); process.exit(0) }
const DIST_DIR = path.resolve(__dirname, '../dist')
if (!fs.existsSync(path.join(DIST_DIR, 'index.html'))) { console.log('SKIP  未找到 dist 产物（先 npx vite build）'); process.exit(0) }
const ORIGIN = 'http://m.local'
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' }
// 静态服务 + mock 身份（路由守卫要 /auth/me；其余接口掐断 → 页面落到「无会话」的空态）
function serveStatic (page) {
  return page.route('**/*', route => {
    const u = new URL(route.request().url())
    if (u.pathname === '/api/ai/auth/me') {
      return route.fulfill({ status: 200, contentType: 'application/json',
        headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify({ success: true, data: { user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: [] } }) })
    }
    // 通知/审批接口给真实 mock：铃铛角标、通知 sheet、审批恢复横幅的断言需要数据（其余接口仍掐断）
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data }) })
    if (u.pathname === '/api/ai/notification/unread-count') return json({ count: 3 })
    if (u.pathname === '/api/ai/notification/list') {
      return json({ items: [
        { id: 1, type: 'tool.approval', title: '工具审批待处理', content: '请求执行「联网搜索」', refId: 's-appr', refSub: 'ap-1', readFlag: 0, createTime: Date.now() - 60000 },
        { id: 2, type: 'parse.done', title: '文档解析完成', content: '手册.pdf', refType: 'kb', refId: 'kb-1', readFlag: 0, createTime: Date.now() - 3600000 },
        { id: 3, type: 'schedule.done', title: '定时任务完成', refType: 'session', refId: 's-9', readFlag: 1, createTime: Date.now() - 7200000 }
      ], nextCursor: 0, hasMore: false, total: 3, unreadCount: 3 })
    }
    if (u.pathname === '/api/ai/notification/read' || u.pathname === '/api/ai/notification/read-all') return json({})
    if (u.pathname.startsWith('/api/ai/chat/tool-approval/')) {
      return json({ id: 'ap-1', toolName: '联网搜索', requestArgs: '{"q":"测试"}', status: 'PENDING' })
    }
    if (u.host !== 'm.local') return route.abort()
    let p = decodeURIComponent(u.pathname)
    let f = path.join(DIST_DIR, p)
    if (p === '/' || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST_DIR, 'index.html')
    try {
      const buf = fs.readFileSync(f)
      route.fulfill({ status: 200, body: buf, headers: { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' } })
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
  // iQOO Neo11 档位：412×916、DPR2、触屏
  const ctx = await browser.newContext({
    viewport: { width: 412, height: 916 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    userAgent: 'Mozilla/5.0 (Linux; Android 15; V2318A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
  })
  const page = await ctx.newPage()
  await serveStatic(page)
  const errors = []
  page.on('pageerror', e => errors.push(String(e.message)))
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

  await page.goto(ORIGIN + '/login', { waitUntil: 'networkidle' })
  await page.evaluate(() => { localStorage.setItem('ai_token', 'mock-token-for-verify') })
  // ---- 设备重定向：手机访问 /chat 必须落到 /m/chat（带 sid 等查询串原样透传）----
  await page.goto(ORIGIN + '/chat?sid=abc123', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1400)
  const r1 = await page.evaluate(() => ({ path: location.pathname, search: location.search }))
  check(r1.path === '/m/chat', '手机访问 /chat 重定向到 /m/chat', `path=${r1.path}`)
  check(r1.search === '?sid=abc123', '查询串原样透传（深链/分享不丢）', `search=${r1.search}`)

  // ---- 移动壳结构 ----
  const m1 = await page.evaluate(() => {
    const de = document.documentElement
    const bar = document.querySelector('.m-bar')
    const composer = document.querySelector('.m-composer')
    const ta = document.querySelector('.m-ta')
    const send = document.querySelector('.m-send')
    const btns = [...(bar ? bar.querySelectorAll('.m-bar-btn') : [])]
    return {
      hasBar: !!bar, hasList: !!document.querySelector('.m-list'), hasComposer: !!composer,
      hasWelcome: !!document.querySelector('.m-welcome'),
      scrollW: de.scrollWidth, clientW: de.clientWidth,
      barTop: bar ? Math.round(bar.getBoundingClientRect().top) : -1,
      taFont: ta ? parseFloat(getComputedStyle(ta).fontSize) : 0,
      sendSize: send ? Math.round(send.getBoundingClientRect().width) : 0,
      minBarBtn: btns.length ? Math.min(...btns.map(b => Math.round(b.getBoundingClientRect().width))) : 0,
      chatH: document.querySelector('.m-chat') ? Math.round(document.querySelector('.m-chat').getBoundingClientRect().height) : 0
    }
  })
  check(m1.hasBar && m1.hasList && m1.hasComposer, '移动壳三件套（顶栏/消息流/输入区）齐备')
  check(m1.hasWelcome, '无会话时渲染空态欢迎区')
  check(m1.scrollW <= m1.clientW + 1, '无横向溢出', `scrollW=${m1.scrollW} clientW=${m1.clientW}`)
  check(m1.barTop === 0, '顶栏贴顶（安全区 padding 生效，无溢出留白）', `top=${m1.barTop}`)
  check(m1.taFont >= 16, '输入框字号 ≥16px（否则 iOS 聚焦会 zoom-in 不复位）', `font=${m1.taFont}`)
  check(m1.sendSize >= 40, '发送键触控热区 ≥40px', `w=${m1.sendSize}`)
  check(m1.minBarBtn >= 40, '顶栏按钮触控热区 ≥40px', `minW=${m1.minBarBtn}`)
  check(m1.chatH >= 800, '满高容器（--app-vh 生效）', `h=${m1.chatH}`)

  // ---- 各 sheet 开合 ----
  const openSheet = async (sel, waitMs = 900) => {
    await page.locator(sel).first().dispatchEvent('click')
    await page.waitForTimeout(waitMs)
    return page.evaluate(() => !!document.querySelector('.bs-sheet'))
  }
  check(await openSheet('.m-bar-btn[title="会话列表"]'), '会话列表 sheet 可打开')
  await page.locator('.bs-close').first().dispatchEvent('click'); await page.waitForTimeout(500)
  check(!(await page.evaluate(() => !!document.querySelector('.bs-sheet'))), '会话列表 sheet 可关闭')
  check(await openSheet('.m-tool[title="模型与思考"]', 1200), '模型与思考 sheet 可打开')
  await page.locator('.bs-close').first().dispatchEvent('click'); await page.waitForTimeout(500)
  check(await openSheet('.m-tool[title^="添加"]'), '「+」内容 sheet 可打开')
  const tabs = await page.evaluate(() => [...document.querySelectorAll('.as-tab')].map(x => x.textContent.trim()))
  check(tabs.length === 4, '「+」sheet 四页签（技能/引用/历史/附件）', tabs.join('/'))
  await page.locator('.as-done').first().dispatchEvent('click'); await page.waitForTimeout(500)
  check(await openSheet('.m-bar-title'), '状态与来源 sheet 可打开')
  await page.locator('.bs-close').first().dispatchEvent('click'); await page.waitForTimeout(400)

  // ---- 输入框可用（发送键由 canSend 驱动：空内容禁用、有内容启用）----
  const sendState = await page.evaluate(async () => {
    const btn = document.querySelector('.m-send')
    const before = btn ? btn.disabled : null
    const ta = document.querySelector('.m-ta')
    if (ta) { ta.value = '你好'; ta.dispatchEvent(new Event('input', { bubbles: true })) }
    await new Promise(r => setTimeout(r, 300))
    const after = document.querySelector('.m-send') ? document.querySelector('.m-send').disabled : null
    return { before, after }
  })
  check(sendState.before === true && sendState.after === false, '发送键空内容禁用、有内容启用', JSON.stringify(sendState))

  // ---- 通知（M4 壳外闭环）：顶栏铃铛角标 + sheet 列表 + 深链落点 ----
  const bell = await page.evaluate(() => {
    const b = document.querySelector('.m-bar-btn[title="通知"]')
    const badge = b ? b.querySelector('.m-bar-badge') : null
    return { has: !!b, w: b ? Math.round(b.getBoundingClientRect().width) : 0, badge: badge ? badge.textContent.trim() : '' }
  })
  check(bell.has && bell.w >= 40, '顶栏通知铃铛存在且热区 ≥40px', JSON.stringify(bell))
  check(bell.badge === '3', '未读角标显示未读数（mock 3 条）', `badge=${bell.badge}`)

  check(await openSheet('.m-bar-btn[title="通知"]', 1200), '通知 sheet 可打开')
  const nt = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.nt-item')]
    return { n: items.length, unread: items.filter(x => x.classList.contains('unread')).length,
             sub: (document.querySelector('.bs-sub') || {}).textContent || '' }
  })
  check(nt.n === 3, '通知 sheet 列出 3 条通知', `n=${nt.n}`)
  check(nt.unread === 2, '未读样式标出 2 条未读', `unread=${nt.unread}`)

  // 会话类通知：跳回会话（query 带 sid）且 sheet 自收
  await page.locator('.nt-item').nth(2).dispatchEvent('click')
  await page.waitForTimeout(900)
  const rNotif = await page.evaluate(() => ({ path: location.pathname, search: location.search, sheet: !!document.querySelector('.bs-sheet') }))
  check(rNotif.path === '/m/chat' && rNotif.search === '?sid=s-9', '点会话类通知跳回会话（?sid=）', `→ ${rNotif.path}${rNotif.search}`)
  check(!rNotif.sheet, '点开通知后 sheet 自动收起')

  // 审批类通知：深链带 sid+approval，且顶部恢复横幅按 approvalId 重建（M4 核心闭环）
  check(await openSheet('.m-bar-btn[title="通知"]', 1200), '通知 sheet 可再次打开')
  await page.locator('.nt-item').nth(0).dispatchEvent('click')
  await page.waitForTimeout(1400)
  const rAppr = await page.evaluate(() => ({
    path: location.pathname, search: location.search,
    banner: document.querySelector('.m-rec-title') ? document.querySelector('.m-rec-title').textContent.trim() : '',
    actions: [...document.querySelectorAll('.m-rec-btn')].map(b => b.textContent.trim())
  }))
  check(rAppr.path === '/m/chat' && rAppr.search.includes('approval=ap-1') && rAppr.search.includes('sid=s-appr'),
    '审批通知深链带 sid+approval（引擎切会话不再抹掉 approval）', `search=${rAppr.search}`)
  check(rAppr.banner.includes('联网搜索'), '审批恢复横幅按 approvalId 重建', rAppr.banner)
  check(rAppr.actions.includes('批准执行') && rAppr.actions.includes('拒绝'), '横幅给出批准/拒绝', rAppr.actions.join('/'))

  // ---- 壳外入口：会话 sheet 底部四项（个人设置/帮助/主题/退出）----
  check(await openSheet('.m-bar-btn[title="会话列表"]'), '会话 sheet 可再次打开')
  const foot = await page.evaluate(() => [...document.querySelectorAll('.ss-foot-btn')].map(b => b.textContent.trim()))
  check(foot.length === 4 && foot[0].includes('个人设置') && foot[1].includes('帮助中心')
    && foot[2].includes('主题') && foot[3].includes('退出登录'), '会话 sheet 底部四项壳外入口', foot.join('/'))
  await page.locator('.ss-foot-btn').first().dispatchEvent('click')
  await page.waitForTimeout(1200)
  const rProf = await page.evaluate(() => ({ path: location.pathname, topbar: !!document.querySelector('.m-topbar'), shell: !!document.querySelector('.m-chat') }))
  check(rProf.path === '/profile', '点「个人设置」进入 /profile', `path=${rProf.path}`)
  check(!rProf.shell && rProf.topbar, '个人设置走窄屏工作台（顶栏在、移动壳不在）', JSON.stringify(rProf))

  // ---- PC 回归：桌面（无触屏）访问 /m/chat 应被弹回 /chat，且不渲染移动壳 ----
  const desktop = await ctx.newPage()
  await serveStatic(desktop)
  await desktop.setViewportSize({ width: 1280, height: 800 })
  await desktop.goto(ORIGIN + '/login', { waitUntil: 'networkidle' })
  await desktop.evaluate(() => { localStorage.setItem('ai_token', 'mock-token-for-verify') })
  await desktop.goto(ORIGIN + '/m/chat', { waitUntil: 'networkidle' })
  await desktop.waitForTimeout(1200)
  const d1 = await desktop.evaluate(() => ({ path: location.pathname, hasShell: !!document.querySelector('.m-chat'), hasSide: !!document.querySelector('.side') }))
  check(d1.path === '/chat', '桌面访问 /m/chat 弹回 /chat', `path=${d1.path}`)
  check(!d1.hasShell && d1.hasSide, '桌面渲染工作台（侧栏在、移动壳不在）', JSON.stringify(d1))

  const real = errors.filter(e => !/mock-token-for-verify|401|Failed to load resource|ERR_FAILED/i.test(e))
  check(real.length === 0, '无 JS 运行时错误', real.slice(0, 3).join(' | '))

  await browser.close()
  console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
  process.exit(bad ? 1 : 0)
})().catch(e => { console.error('验证脚本异常：', e.message); process.exit(2) })
