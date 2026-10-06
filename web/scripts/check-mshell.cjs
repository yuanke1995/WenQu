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
// 配置引导断言用开关：先给"未就绪"（无模型）看引导卡，断言后翻成"就绪"看示例卡
let setupUnready = true
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
    // 单轮操作（P1）断言所需：一段可加载的历史 + 管理员调试开关 + 调试/评测/删除接口
    if (u.pathname === '/api/ai/config') return json({ chat: { retrievalDebugEnabled: { value: 'true' } } })
    // 我的产物（P4）：壳外入口直达 /artifacts 的断言需要列表数据
    if (u.pathname === '/api/ai/artifact/list') {
      return json([
        { id: 11, filename: '周报.md', ext: 'md', size: 2048, createTime: '2026-10-06T09:00:00', description: '项目周报', url: '/files/11.md', expireTime: '' },
        { id: 12, filename: '清单.csv', ext: 'csv', size: 512, createTime: '2026-10-05T18:30:00', description: '', url: '/files/12.csv', expireTime: '' }
      ])
    }
    // 知识库只读浏览（P5）：库卡片 + 文档列表（页头统计与上传配置也在此 mock）
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
    // 配置引导（setupGuide）的两个数据源：模型可用列表与个人偏好（defaultModel）
    // 就绪态的供应商带 quotaBlocked：模型 sheet 的额度标记/拦截断言用
    if (u.pathname === '/api/ai/provider/available') {
      return json(setupUnready ? [] : [{ name: '平台内置', quotaBlocked: true, quotaMessage: '余额不足', models: [{ ref: 'chat-1', displayName: '演示聊天模型', type: 'chat' }] }])
    }
    if (u.pathname === '/api/ai/user/preference') return json({ defaultModel: setupUnready ? '' : 'chat-1' })
    if (u.pathname === '/api/ai/session/s-round') {
      return json([
        { messageId: 'q-1', role: 'user', content: '登录步骤是什么？', createTime: Date.now() - 120000 },
        { messageId: 'a-1', role: 'assistant', content: '按下面三步登录：① 打开登录页 ② 输入账号 ③ 提交。', createTime: Date.now() - 60000,
          sources: [{ knowledgeId: 'k-1', ref: '1', fileName: '操作手册.pdf', title: '登录', snippet: '登录流程说明…', score: 0.82 }],
          tokens: { prompt: 1200, context: 8, budget: 8000, window: 8000, output: 300, total: 1500, cached: 600,
                    parts: { messages: 900, system: 200, input: 100 } } }
      ])
    }
    if (u.pathname === '/api/ai/debug/retrieval') {
      return json({ keywordTerms: ['登录', '步骤'], rerankApplied: false, rerankSkipReason: '未启用重排',
        keywordHits: [{ title: '登录', docName: '操作手册.pdf', snippet: '关键词命中片段', hitRate: 0.9 }],
        vectorHits: [{ title: '登录', docName: '操作手册.pdf', snippet: '向量命中片段', score: 0.77 }],
        merged: [{ title: '登录', docName: '操作手册.pdf', score: 0.8 }],
        reranked: [], finalContext: [{ title: '登录', docName: '操作手册.pdf', score: 0.8 }], excluded: [] })
    }
    if (u.pathname === '/api/ai/eval/case') return json({ added: true, expected: 1 })
    if (u.pathname.startsWith('/api/ai/message-group/')) return json({})
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

  // ---- 空态配置引导（P2）：模型未就绪 → 配置引导卡；就绪 → 回到示例卡（与 PC 欢迎区同一门控）----
  const guideState = await page.evaluate(() => ({
    sg: !!document.querySelector('.m-welcome .sg'),
    samples: !!document.querySelector('.m-samples'),
    title: (document.querySelector('.m-welcome-title') || {}).textContent || ''
  }))
  check(guideState.sg && !guideState.samples, '模型未就绪时空态显示配置引导卡（不再显示点了必失败的示例）', JSON.stringify(guideState))
  check(guideState.title.includes('欢迎使用问渠'), '引导态标题切换', guideState.title)
  setupUnready = false
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(1300)
  const readyState = await page.evaluate(() => ({
    sg: !!document.querySelector('.m-welcome .sg'),
    samples: !!document.querySelector('.m-samples'),
    title: (document.querySelector('.m-welcome-title') || {}).textContent || ''
  }))
  check(!readyState.sg && readyState.samples, '模型就绪后空态回到示例卡', JSON.stringify(readyState))

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

  // ---- 额度不足对齐（与 PC ModelSelect 同一打标）：组头/行标 + 点拦截 + 说明 toast ----
  const quota = await page.evaluate(() => ({
    flags: document.querySelectorAll('.ms-quota-flag').length,
    row: !!document.querySelector('.ms-row .ms-quota-flag'),
    note: (document.querySelector('.ms-note.warn') || {}).textContent || ''
  }))
  check(quota.flags >= 2, '额度不足标记渲染（组头 + 模型行）', `flags=${quota.flags}`)
  check(quota.note.includes('额度不足') && quota.note.includes('换用其他模型'), '当前生效模型被欠费拦下时就地提示', quota.note.trim().slice(0, 40))
  await page.locator('.ms-row .ms-quota-flag').first().dispatchEvent('click')
  await page.waitForTimeout(700)
  const quotaToast = await page.evaluate(() => (document.querySelector('.ant-message-notice-content') || {}).textContent || '')
  check(quotaToast.includes('额度不足') && quotaToast.includes('充值'), '点欠费模型弹说明 toast（不选中）', quotaToast.trim().slice(0, 40))
  const notSelected = await page.evaluate(() => !document.querySelector('.ms-row.on .ms-quota-flag'))
  check(notSelected, '欠费行不可被选中（无 on 态）')
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
  // 入口按文案断言（顺序不重要）：安装到桌面仅"可安装"时出现，headless 里通常没有 beforeinstallprompt
  const needFoot = ['个人设置', '帮助中心', '我的产物', '主题', '退出登录']
  check(needFoot.every(t => foot.some(x => x.includes(t))), '会话 sheet 底部壳外入口齐全（含我的产物）', foot.join('/'))
  await page.locator('.ss-foot-btn').first().dispatchEvent('click')
  await page.waitForTimeout(1200)
  const rProf = await page.evaluate(() => ({ path: location.pathname, topbar: !!document.querySelector('.m-topbar'), shell: !!document.querySelector('.m-chat') }))
  check(rProf.path === '/profile', '点「个人设置」进入 /profile', `path=${rProf.path}`)
  check(!rProf.shell && rProf.topbar, '个人设置走窄屏工作台（顶栏在、移动壳不在）', JSON.stringify(rProf))

  // ---- 我的产物（P4）：壳外入口直达 + 真页面（白名单）+ 触摸热区 + 无横向溢出 ----
  await page.goto(ORIGIN + '/m/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  check(await openSheet('.m-bar-btn[title="会话列表"]'), '会话 sheet 可再次打开（产物入口）')
  await page.locator('.ss-foot-btn', { hasText: '我的产物' }).first().dispatchEvent('click')
  await page.waitForTimeout(1300)
  const art = await page.evaluate(() => {
    const de = document.documentElement
    const acts = [...document.querySelectorAll('.art-acts > .app-btn')].map(b => Math.round(b.getBoundingClientRect().height))
    return { path: location.pathname, rows: document.querySelectorAll('.art-row').length,
      card: !!document.querySelector('.dg-card'), scrollW: de.scrollWidth, clientW: de.clientWidth,
      minAct: acts.length ? Math.min(...acts) : 0 }
  })
  check(art.path === '/artifacts', '点「我的产物」进入 /artifacts', art.path)
  check(art.rows === 2 && !art.card, '产物页真渲染（未被引导卡拦，mock 两行）', JSON.stringify(art))
  check(art.scrollW <= art.clientW + 1, '产物页无横向溢出', `scrollW=${art.scrollW} clientW=${art.clientW}`)
  check(art.minAct >= 44, '产物操作按钮触摸热区 ≥44px', `minH=${art.minAct}`)

  // ---- 知识库只读浏览（P5）：入口 → 库卡片 → 文档列表（行重排 + 无溢出）----
  await page.goto(ORIGIN + '/m/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  check(await openSheet('.m-bar-btn[title="会话列表"]'), '会话 sheet 可再次打开（知识库入口）')
  await page.locator('.ss-foot-btn', { hasText: '知识库' }).first().dispatchEvent('click')
  await page.waitForTimeout(1300)
  const kb = await page.evaluate(() => ({
    path: location.pathname, cards: document.querySelectorAll('.kb-card').length,
    card: !!document.querySelector('.dg-card'), name: (document.querySelector('.kb-name') || {}).textContent || '',
    scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth
  }))
  check(kb.path === '/knowledge', '点「知识库」进入 /knowledge', kb.path)
  check(kb.cards === 1 && !kb.card, '库卡片真渲染（未被引导卡拦）', JSON.stringify(kb))
  check(kb.scrollW <= kb.clientW + 1, '知识库列表页无横向溢出', `scrollW=${kb.scrollW} clientW=${kb.clientW}`)
  await page.locator('.kb-card').first().dispatchEvent('click')
  await page.waitForTimeout(1300)
  const docs = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.doc-row:not(.head-row)')]
    const first = rows[0]
    const name = first ? first.querySelector('.col-name') : null
    const act = first ? first.querySelector('.col-act') : null
    return {
      path: location.pathname, rows: rows.length,
      headHidden: !document.querySelector('.head-row') || document.querySelector('.head-row').offsetHeight === 0,
      // 重排生效：文件名行在操作行上方（同一行内 name 顶满、act 换到下一行）
      actBelow: !!(name && act) && Math.round(act.getBoundingClientRect().top) > Math.round(name.getBoundingClientRect().top) + 6,
      statusShown: first ? getComputedStyle(first.querySelector('.col-status')).display !== 'none' : false,
      scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth
    }
  })
  check(docs.path === '/knowledge/kb-1/docs', '点库卡片进入文档列表', docs.path)
  check(docs.rows === 2, '文档列表真渲染（mock 两行）', `rows=${docs.rows}`)
  check(docs.headHidden, '≤560px 表头行隐藏（栅格已重排）')
  check(docs.actBelow, '操作区换到文件名下一行', JSON.stringify(docs))
  check(docs.statusShown, '状态列恢复可见（解析中/失败是只读浏览的关键状态）')
  check(docs.scrollW <= docs.clientW + 1, '文档列表页无横向溢出', `scrollW=${docs.scrollW} clientW=${docs.clientW}`)

  // ---- 单轮操作 sheet（P1）：历史加载 → 「⋯」→ 导出/评测/调试/删除 ----
  // 这一段用 mock 的历史（s-round）驱动真渲染：操作行只对"最新一条"或选中态出现，
  // 静态断言测不到这层，必须真点开。
  await page.goto(ORIGIN + '/m/chat?sid=s-round', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  const loaded = await page.evaluate(() => ({ rows: document.querySelectorAll('.mrow').length,
    more: !!document.querySelector('.ai-actions .act-btn[title="更多操作"]') }))
  check(loaded.rows === 2, 'mock 历史渲染出两行消息', `rows=${loaded.rows}`)
  check(loaded.more, '最新一条 AI 消息的操作行有「更多操作」入口')

  // ---- 上下文容量（P2）：填充段 + 窗口占用 + 缓存命中率（mock tokens 带 parts/window/cached）----
  check(await openSheet('.m-bar-title'), '状态与来源 sheet 可打开（容量断言用）')
  const capState = await page.evaluate(() => ({
    segs: document.querySelectorAll('.cap-bar .cap-seg').length,
    meta: (document.querySelector('.cap-meta') || {}).textContent.replace(/\s+/g, ' ').trim(),
    rows: [...document.querySelectorAll('.rs-kv .cap-label')].map(x => x.textContent.trim()).join('/')
  }))
  check(capState.segs === 3, '容量填充条按分类分段（mock 三类）', `segs=${capState.segs}`)
  check(/缓存命中 50%/.test(capState.meta), '窗口用量与缓存命中率已展示', capState.meta)
  check(capState.rows.includes('消息') && capState.rows.includes('系统提示词'), '分类明细带标签', capState.rows)
  await page.locator('.bs-close').first().dispatchEvent('click')
  await page.waitForTimeout(500)
  await page.locator('.ai-actions .act-btn[title="更多操作"]').dispatchEvent('click')
  await page.waitForTimeout(900)
  const rdMenu = await page.evaluate(() => [...document.querySelectorAll('.rd-btn')].map(b => b.textContent.trim()))
  check(rdMenu.some(t => t.includes('导出这轮问答')) && rdMenu.some(t => t.includes('加入评测集'))
    && rdMenu.some(t => t.includes('检索调试')) && rdMenu.some(t => t.includes('删除本轮对话')),
    '单轮操作 sheet 四项齐全（导出/评测/调试/删除）', rdMenu.join('/'))

  // 导出：双通道（保存文件 + 复制全文）
  await page.locator('.rd-btn', { hasText: '导出这轮问答' }).first().dispatchEvent('click')
  await page.waitForTimeout(900)
  const rdExport = await page.evaluate(() => [...document.querySelectorAll('.rd-row .rd-btn')].map(b => b.textContent.trim()))
  check(rdExport.length === 2 && rdExport[0].includes('保存文件') && rdExport[1].includes('复制全文'),
    '导出后给出保存/复制双通道', rdExport.join('/'))

  // 检索调试：分阶段结果（六段）+ 返回
  await page.locator('.rd-btn', { hasText: '检索调试' }).first().dispatchEvent('click')
  await page.waitForTimeout(1000)
  const rdDebug = await page.evaluate(() => ({ stages: document.querySelectorAll('.rd-stage').length,
    terms: document.querySelectorAll('.rd-term').length,
    title: (document.querySelector('.bs-title') || {}).textContent || '' }))
  check(rdDebug.title.includes('检索调试'), '调试视图标题切换', rdDebug.title)
  check(rdDebug.stages === 6, '调试结果分六段展示', `stages=${rdDebug.stages}`)
  check(rdDebug.terms === 2, '关键词词条已展示', `terms=${rdDebug.terms}`)
  await page.locator('.rd-btn', { hasText: '返回操作' }).first().dispatchEvent('click')
  await page.waitForTimeout(600)
  check(await page.evaluate(() => [...document.querySelectorAll('.rd-btn')].some(b => b.textContent.includes('删除本轮对话'))),
    '「返回操作」回到菜单视图')

  // 删除本轮：确认弹窗（Modal.confirm）→ 消息移除
  await page.locator('.rd-btn', { hasText: '删除本轮对话' }).first().dispatchEvent('click')
  await page.waitForTimeout(700)
  const confirmShown = await page.evaluate(() => !!document.querySelector('.ant-modal-confirm'))
  check(confirmShown, '删除本轮弹确认框（Modal.confirm，触屏可靠）')
  await page.locator('.ant-modal-confirm-btns .ant-btn-dangerous').first().dispatchEvent('click')
  await page.waitForTimeout(1200)
  const afterDel = await page.evaluate(() => ({ rows: document.querySelectorAll('.mrow').length,
    welcome: !!document.querySelector('.m-welcome') }))
  check(afterDel.rows === 0 && afterDel.welcome, '删除本轮后消息流移除该轮（回到空态）', JSON.stringify(afterDel))

  // ---- PWA 安装入口（P3）：iOS 无 beforeinstallprompt，给图文步骤；应用内浏览器不给 ----
  const iosCtx = await browser.newContext({
    viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true,
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'
  })
  const iosPage = await iosCtx.newPage()
  await serveStatic(iosPage)
  await iosPage.goto(ORIGIN + '/login', { waitUntil: 'networkidle' })
  await iosPage.evaluate(() => { localStorage.setItem('ai_token', 'mock-token-for-verify') })
  await iosPage.goto(ORIGIN + '/m/chat', { waitUntil: 'networkidle' })
  await iosPage.waitForTimeout(1400)
  await iosPage.locator('.m-bar-btn[title="会话列表"]').first().dispatchEvent('click')
  await iosPage.waitForTimeout(800)
  const iosFoot = await iosPage.evaluate(() => [...document.querySelectorAll('.ss-foot-btn')].map(b => b.textContent.trim()))
  check(iosFoot.some(t => t.includes('安装到桌面')), 'iOS 上壳外入口给出「安装到桌面」', iosFoot.join('/'))
  await iosPage.locator('.ss-foot-btn', { hasText: '安装到桌面' }).first().dispatchEvent('click')
  await iosPage.waitForTimeout(800)
  const iosModal = await iosPage.evaluate(() => (document.querySelector('.ant-modal-confirm') || {}).innerText || '')
  check(/添加到主屏幕/.test(iosModal), 'iOS 给出图文安装步骤', iosModal.replace(/\s+/g, ' ').slice(0, 50))
  await iosCtx.close()

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
