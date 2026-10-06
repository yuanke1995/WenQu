// 真浏览器窄屏验证：playwright-core 驱动系统 Edge（Chromium 内核），375×667 视口 + 触屏语义。
// 验的是「用户能不能用」而不是「代码在不在」——H5 第一版有过 4 个静态校验全绿、真机却坏的 bug
// （守卫 slot 没绑、调的 antd 方法不存在、mPanelOpen 没绑模板、sheet 无关闭口），故必须有这一层。
//
// 前置：先构建产物到 web/dist
//   npx vite build --outDir web/dist --emptyOutDir
// 依赖：playwright-core（不装进项目，临时用）：
//   mkdir -p /tmp/wq-verify && cd /tmp/wq-verify && npm i playwright-core
//   NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-browser.cjs
// 无 playwright-core 时会给出提示并跳过（exit 0），不阻塞其它校验。
//
'use strict'
// 真浏览器窄屏验证：用 playwright-core 驱动系统 Edge（Chromium 内核），
// 在 375×667（iPhone SE 尺寸）视口下打开构建产物，验证移动端适配的实际行为。
//
// 验的是**用户能不能用**，不是「代码在不在」：
//   - 侧栏是否真的变抽屉（隐藏到屏外 + 可点开）
//   - 顶栏是否存在、按钮是否够大（触控热区 ≥34px）
//   - 触摸热区尺寸
//   - 管理页是否给引导卡、对话页是否放行（真渲染，不是查源码）
//   - 横向是否溢出（375px 下出现横向滚动 = 破版）
//   - 点「状态」右栏是否真能打开（这条曾因模板没绑 mPanelOpen 而永远打不开）
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过真浏览器验证')
  console.log('      安装：cd /tmp/wq-verify && npm i playwright-core')
  console.log('      运行：npx vite build --outDir dist --emptyOutDir')
  console.log('            NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-browser.cjs')
  process.exit(0)
}
const path = require('path')
const fs = require('fs')

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
const exists = p => { try { return require('fs').existsSync(p) } catch (e) { return false } }
if (!exists(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE + '（可用 WQ_BROWSER 指定）'); process.exit(0) }
const DIST_DIR = '/Users/yuki/IdeaProjects/wenqu/web/dist'
const ORIGIN = 'http://h5.local'
const MIME = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.json':'application/json',
  '.webmanifest':'application/manifest+json', '.svg':'image/svg+xml', '.png':'image/png', '.ico':'image/x-icon' }
// SPA 回退：文件不存在就回 index.html（等价生产 nginx try_files）
function serveStatic (page) {
  return page.route('**/*', route => {
    const u = new URL(route.request().url())
    // mock 管理员身份：路由守卫的 requiresAdmin 依赖 /auth/me 的结果，
    // 不 mock 时 ensureAuth 失败会把 ai_role 覆写成 user → /settings 被弹回 /chat
    if (u.pathname === '/api/ai/auth/me') {
      return route.fulfill({ status: 200, contentType: 'application/json',
        headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify({ success: true, data: { user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: [] } }) })
    }
    // 通知接口给真实 mock：铃铛角标/弹层/深链的断言需要数据（其余接口仍掐断）
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
    // 我的产物：窄屏白名单与删除确认口径的断言需要列表数据
    if (u.pathname === '/api/ai/artifact/list') {
      return json([
        { id: 11, filename: '周报.md', ext: 'md', size: 2048, createTime: '2026-10-06T09:00:00', description: '项目周报', url: '/files/11.md', expireTime: '' },
        { id: 12, filename: '清单.csv', ext: 'csv', size: 512, createTime: '2026-10-05T18:30:00', description: '', url: '/files/12.csv', expireTime: '' }
      ])
    }
    // 知识库只读浏览：库卡片 + 文档列表
    if (u.pathname === '/api/ai/kb/list') {
      return json([{ id: 'kb-1', name: '产品手册', description: '产品操作与常见问题', docCount: 2, isDefault: 0, builtin: 0, mixedScope: false }])
    }
    if (u.pathname === '/api/ai/document/list') {
      return json([
        { id: 'd-1', kbId: 'kb-1', fileName: '安装指南.pdf', fileType: 'pdf', fileSize: 1048576, chunkCount: 12, status: 0, createdBy: 'nobody', createTime: '2026-10-06T09:00:00' },
        { id: 'd-2', kbId: 'kb-1', fileName: '接口说明.docx', fileType: 'docx', fileSize: 4096, chunkCount: 5, status: 2, parseProgress: 60, parseDesc: '解析中', createdBy: 'nobody', createTime: '2026-10-06T08:00:00' }
      ])
    }
    if (u.pathname === '/api/ai/document/queue/stats') return json({ pending: 0, running: 1 })
    if (u.pathname === '/api/ai/config/public') return json({ upload: { maxFileSize: 209715200, allowedExts: ['docx', 'pdf', 'xlsx'] } })
    if (u.pathname.startsWith('/api/ai/chat/tool-approval/')) {
      return json({ id: 'ap-1', toolName: '联网搜索', requestArgs: '{"q":"测试"}', status: 'PENDING' })
    }
    if (u.host !== 'h5.local') return route.abort()      // 其余外部请求掐断
    let p = decodeURIComponent(u.pathname)
    let f = path.join(DIST_DIR, p)
    if (p === '/' || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST_DIR, 'index.html')
    try {
      const buf = fs.readFileSync(f)
      route.fulfill({ status: 200, body: buf,
        headers: { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' } })
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
  // 375×667 但**不开触屏语义**：模拟「鼠标用户把桌面窗口拖窄」——这条路径必须留在 PC 布局
  //（路由守卫按 preferMobileShell=触屏判据分流，触屏 375 会进 /m/chat，见 check-mshell.cjs）。
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 667 },
    deviceScaleFactor: 2
  })
  const page = await ctx.newPage()
  await serveStatic(page)
  await serveStatic(page)
  const errors = []
  page.on('pageerror', e => errors.push(String(e.message)))
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

  // ---- 登录页（未登录会被路由守卫弹到 /login，正好先看它）----
  await page.goto(ORIGIN + '/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(600)

  const h1 = await page.evaluate(() => {
    const w = document.querySelector('.login-wrap')
    const c = document.querySelector('.login-card')
    const inputs = [...document.querySelectorAll('.login-card input')]
    return {
      wrapH: w ? Math.round(w.getBoundingClientRect().height) : 0,
      cardW: c ? Math.round(c.getBoundingClientRect().width) : 0,
      docScrollW: document.documentElement.scrollWidth,
      docClientW: document.documentElement.clientWidth,
      inputCount: inputs.length,
      inputH: inputs[0] ? Math.round(inputs[0].getBoundingClientRect().height) : 0,
      inputFont: inputs[0] ? getComputedStyle(inputs[0]).fontSize : ''
    }
  })
  check(h1.wrapH > 0 && h1.wrapH <= 700, '登录页在 375px 下有高度且不超视口', `wrapH=${h1.wrapH}`)
  check(h1.cardW > 0 && h1.cardW <= 375, '登录卡片不溢出 375px', `cardW=${h1.cardW}`)
  check(h1.docScrollW <= h1.docClientW + 1, '登录页无横向溢出', `scrollW=${h1.docScrollW} clientW=${h1.docClientW}`)
  // iOS 聚焦自动缩放：字号 <16px 会被放大且不自动复位
  check(parseFloat(h1.inputFont) >= 16, '登录输入框字号 ≥16px（否则 iOS 聚焦会 zoom-in 不复位）', `font=${h1.inputFont}`)
  check(h1.inputH >= 38, '登录输入框高度 ≥38px（触控热区）', `h=${h1.inputH}`)

  // ---- 注入令牌让守卫放行，直接进 /chat 看对话页 ----
  // 用假令牌即可：路由守卫只查本地是否存在，真实鉴权在 /auth/me 与接口层
  await page.evaluate(() => { localStorage.setItem('ai_token', 'mock-token-for-verify') })
  await page.goto(ORIGIN + '/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)

  const chat = await page.evaluate(() => {
    const side = document.querySelector('.side')
    const topbar = document.querySelector('.m-topbar')
    const sideCs = side ? getComputedStyle(side) : null
    const r = side ? side.getBoundingClientRect() : null
    const btns = topbar ? [...topbar.querySelectorAll('.m-tb-btn')] : []
    return {
      hasSide: !!side,
      sidePos: sideCs ? sideCs.position : '',
      sideRight: r ? Math.round(r.right) : null,
      sideW: r ? Math.round(r.width) : null,
      hasTopbar: !!topbar,
      topbarH: topbar ? Math.round(topbar.getBoundingClientRect().height) : 0,
      btnCount: btns.length,
      minBtn: btns.length ? Math.min(...btns.map(b => Math.round(b.getBoundingClientRect().height))) : 0,
      docScrollW: document.documentElement.scrollWidth,
      docClientW: document.documentElement.clientWidth
    }
  })
  check(chat.hasSide && chat.sidePos === 'fixed', '侧栏窄屏为 fixed（抽屉形态）', `position=${chat.sidePos}`)
  check(chat.hasSide && chat.sideRight <= 0, '侧栏默认滑出屏外（translateX(-100%) 生效）', `right=${chat.sideRight}`)
  check(chat.sideW > 200 && chat.sideW <= 375, '抽屉宽度合理（min(84vw,320px)）', `w=${chat.sideW}`)
  check(chat.hasTopbar, '移动顶栏已渲染')
  check(chat.topbarH >= 44, '顶栏高度 ≥44px', `h=${chat.topbarH}`)
  check(chat.btnCount >= 3, '顶栏按钮齐全（菜单/搜索/新建/帮助）', `count=${chat.btnCount}`)
  check(chat.minBtn >= 34, '顶栏按钮触控热区 ≥34px', `minH=${chat.minBtn}`)
  check(chat.docScrollW <= chat.docClientW + 1, '对话页无横向溢出', `scrollW=${chat.docScrollW} clientW=${chat.docClientW}`)

  // ---- 遮罩与开抽屉 ----
  await page.evaluate(() => {
    const b = document.querySelector('.m-tb-btn')
    if (b) b.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
  await page.waitForTimeout(500)
  const opened = await page.evaluate(() => {
    const side = document.querySelector('.side')
    const mask = document.querySelector('.side-mask')
    const r = side ? side.getBoundingClientRect() : null
    return { right: r ? Math.round(r.right) : null, hasMask: !!mask }
  })
  check(opened.right > 0, '点汉堡按钮后抽屉滑入', `right=${opened.right}`)
  check(opened.hasMask, '抽屉打开时有遮罩（可点关闭）')

  // ---- 宽屏回归：1280 下不应有顶栏、侧栏应常驻 ----
  const wide = await ctx.newPage()
  await serveStatic(wide)
  await serveStatic(wide)
  await wide.setViewportSize({ width: 1280, height: 800 })
  await wide.goto(ORIGIN + '/chat', { waitUntil: 'networkidle' })
  await wide.waitForTimeout(700)
  const w = await wide.evaluate(() => {
    const side = document.querySelector('.side')
    const topbar = document.querySelector('.m-topbar')
    const r = side ? side.getBoundingClientRect() : null
    return {
      hasTopbar: !!topbar,
      sidePos: side ? getComputedStyle(side).position : '',
      sideLeft: r ? Math.round(r.left) : null,
      sideW: r ? Math.round(r.width) : null
    }
  })
  check(!w.hasTopbar, '宽屏不渲染移动顶栏')
  check(w.sidePos !== 'fixed', '宽屏侧栏非 fixed（回归 PC 布局）', `position=${w.sidePos}`)
  check(w.sideLeft === 0 && w.sideW >= 190, '宽屏侧栏常驻 200px', `left=${w.sideLeft} w=${w.sideW}`)

  // ---- PC 铃铛（取数单元与移动壳共用）：角标 / 弹层 / 深链 ----
  // 这三条同时守着一次重构：通知逻辑从 AppLayout 抽到 chat/useNotifications.js 后，
  // 模板绑定的名字若没对上，弹层会渲染成空壳而不报错（只有真点开才看得见）。
  const bell = await wide.evaluate(() => {
    const b = document.querySelector('.notif-bell')
    const cnt = b ? b.closest('.ant-badge') : null
    return { has: !!b, count: cnt && cnt.querySelector('.ant-badge-count') ? cnt.querySelector('.ant-badge-count').textContent.trim() : '' }
  })
  check(bell.has, '侧栏 foot 通知铃铛存在')
  check(bell.count === '3', '铃铛角标显示未读数（mock 3）', `count=${bell.count}`)
  await wide.locator('.notif-bell').dispatchEvent('click')
  await wide.waitForTimeout(900)
  const pop = await wide.evaluate(() => ({
    n: document.querySelectorAll('.notif-panel .notif-item').length,
    unread: document.querySelectorAll('.notif-panel .notif-item.unread').length,
    time: (document.querySelector('.notif-panel .notif-time') || {}).textContent || ''
  }))
  check(pop.n === 3, '铃铛弹层列出 3 条通知', `n=${pop.n}`)
  check(pop.unread === 2, '未读样式标出 2 条', `unread=${pop.unread}`)
  check(/\S/.test(pop.time), '相对时间已格式化（notifTime 生效）', `time=${pop.time}`)

  // 会话类通知 → /chat?sid=…
  await wide.locator('.notif-panel .notif-item').nth(2).dispatchEvent('click')
  await wide.waitForTimeout(900)
  check(await wide.evaluate(() => location.pathname + location.search) === '/chat?sid=s-9',
    '点会话类通知直达会话', await wide.evaluate(() => location.pathname + location.search))
  // 审批类通知 → /chat?sid=…&approval=…，且顶部恢复横幅按 approvalId 重建
  await wide.locator('.notif-bell').dispatchEvent('click')
  await wide.waitForTimeout(800)
  await wide.locator('.notif-panel .notif-item').nth(0).dispatchEvent('click')
  await wide.waitForTimeout(1300)
  const rec = await wide.evaluate(() => ({
    url: location.pathname + location.search,
    banner: document.querySelector('.approval-recovery') ? document.querySelector('.approval-recovery').innerText.replace(/\s+/g, ' ').slice(0, 40) : '',
    btns: [...document.querySelectorAll('.approval-recovery button')].map(b => b.textContent.trim())
  }))
  check(rec.url.includes('approval=ap-1') && /sid=s-appr/.test(rec.url), '审批通知深链带 sid+approval', rec.url)
  check(rec.banner.includes('联网搜索'), 'PC 恢复横幅按 approvalId 重建', rec.banner)
  check(rec.btns.includes('批准执行') && rec.btns.includes('拒绝'), '横幅给出批准/拒绝', rec.btns.join('/'))

  // ---- 我的产物（P4）：白名单页在窄屏真渲染 + 操作区换行 + 删除确认走 Modal.confirm ----
  await page.goto(ORIGIN + '/artifacts', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const artN = await page.evaluate(() => {
    const de = document.documentElement
    const acts = [...document.querySelectorAll('.art-acts > .app-btn')].map(b => Math.round(b.getBoundingClientRect().height))
    const firstAct = document.querySelector('.art-acts')
    const row = document.querySelector('.art-row')
    return { rows: document.querySelectorAll('.art-row').length, card: !!document.querySelector('.dg-card'),
      scrollW: de.scrollWidth, clientW: de.clientWidth, minAct: acts.length ? Math.min(...acts) : 0,
      // 操作区是否真的换到了下一行（窄屏整组右对齐）：它在首行之下
      actsBelow: !!(firstAct && row) ? Math.round(firstAct.getBoundingClientRect().top) > Math.round(row.getBoundingClientRect().top) + 10 : false }
  })
  check(!artN.card && artN.rows === 2, '窄屏 /artifacts 渲染真实页面（白名单生效，mock 两行）', JSON.stringify(artN))
  check(artN.scrollW <= artN.clientW + 1, '窄屏产物页无横向溢出', `scrollW=${artN.scrollW} clientW=${artN.clientW}`)
  check(artN.minAct >= 44, '窄屏产物操作按钮触摸热区 ≥44px', `minH=${artN.minAct}`)
  check(artN.actsBelow, '窄屏操作区整组换行（不与文件名挤在一行）')
  await page.locator('.art-acts .art-del').first().dispatchEvent('click')
  await page.waitForTimeout(700)
  const artModal = await page.evaluate(() => (document.querySelector('.ant-modal-confirm') || {}).innerText || '')
  check(/删除/.test(artModal) && /不可恢复/.test(artModal), '删除确认走 Modal.confirm（触屏/iab 可靠）', artModal.replace(/\s+/g, ' ').slice(0, 36))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  // ---- 知识库只读浏览（P5）：白名单 + 行重排 ----
  await page.goto(ORIGIN + '/knowledge', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const kbN = await page.evaluate(() => ({
    cards: document.querySelectorAll('.kb-card').length, card: !!document.querySelector('.dg-card'),
    scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth
  }))
  check(!kbN.card && kbN.cards === 1, '窄屏 /knowledge 渲染库卡片（白名单生效）', JSON.stringify(kbN))
  check(kbN.scrollW <= kbN.clientW + 1, '窄屏知识库列表无横向溢出', `scrollW=${kbN.scrollW} clientW=${kbN.clientW}`)
  await page.goto(ORIGIN + '/knowledge/kb-1/docs', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  const docsN = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.doc-row:not(.head-row)')]
    const first = rows[0]
    const name = first ? first.querySelector('.col-name') : null
    const act = first ? first.querySelector('.col-act') : null
    const headRow = document.querySelector('.head-row')
    return {
      rows: rows.length, card: !!document.querySelector('.dg-card'),
      headHidden: !headRow || headRow.offsetHeight === 0,
      actBelow: !!(name && act) && Math.round(act.getBoundingClientRect().top) > Math.round(name.getBoundingClientRect().top) + 6,
      scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth
    }
  })
  check(!docsN.card && docsN.rows === 2, '窄屏文档列表真渲染（前缀放行 + mock 两行）', JSON.stringify(docsN))
  check(docsN.headHidden, '窄屏文档表头隐藏（行重排生效）')
  check(docsN.actBelow, '窄屏文档操作区换到文件名下一行')
  check(docsN.scrollW <= docsN.clientW + 1, '窄屏文档列表无横向溢出', `scrollW=${docsN.scrollW} clientW=${docsN.clientW}`)

  // ---- 引导卡：非白名单页面窄屏应给引导卡，且真实页面**未渲染** ----
  // 前提：/auth/me 被 mock 成管理员（见 serveStatic），否则守卫会先弹回 /chat
  // 前提：/auth/me 被 mock 成管理员（见 serveStatic），否则守卫会先弹回 /chat
  await page.goto(ORIGIN + '/settings', { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  const guard = await page.evaluate(() => {
    const card = document.querySelector('.dg-card')
    // 关键：引导卡出现时，被拦页面的真实 DOM 不该存在（否则滚动/点击会穿透）
    const mainBody = document.querySelector('.main-body')
    return {
      hasCard: !!card,
      title: card ? (card.querySelector('.dg-title') || {}).textContent : '',
      mainChildCount: mainBody ? mainBody.children.length : -1,
      mainTextLen: mainBody ? mainBody.innerText.trim().length : -1
    }
  })
  check(guard.hasCard, '窄屏访问 /settings 显示引导卡')
  check(/电脑/.test(guard.title), '引导卡文案正确', guard.title)
  check(guard.mainTextLen > 0 && guard.mainTextLen < 80, '被拦页面的真实内容未渲染（不穿透）', `textLen=${guard.mainTextLen}`)

  // ---- JS 运行时错误汇总 ----
  const real = errors.filter(e => !/mock-token-for-verify|401|Failed to load resource/i.test(e))
  check(real.length === 0, '无 JS 运行时错误', real.slice(0, 3).join(' | '))

  await browser.close()
  console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
  process.exit(bad ? 1 : 0)
})().catch(e => { console.error('验证脚本异常：', e.message); process.exit(2) })
