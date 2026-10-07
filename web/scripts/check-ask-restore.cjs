// 提问卡「持久化 + 恢复」真浏览器校验：playwright-core 驱动系统 Edge，412×916 触屏视口跑 dist 产物。
// 为什么单独一个脚本：移动壳（mobileShell）要 pointer:coarse 才启用，内置浏览器模拟不出触屏，
// 而恢复链路有两端差异（H5 有自己的答题面板与倒计时表），必须触屏真浏览器 + mock 接口才验得到。
//
// 前置：先构建产物到 web/dist
//   npx vite build --outDir dist --emptyOutDir
// 依赖：playwright-core（不装进项目，临时用）：
//   mkdir -p /tmp/wq-verify2 && cd /tmp/wq-verify2 && npm i playwright-core
//   NODE_PATH=/tmp/wq-verify2/node_modules node scripts/check-ask-restore.cjs
// 无 playwright-core 时会给出提示并跳过（exit 0），不阻塞其它校验。
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过提问卡恢复验证')
  console.log('      安装：cd /tmp/wq-verify2 && npm i playwright-core')
  process.exit(0)
}
const path = require('path')
const fs = require('fs')

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
if (!fs.existsSync(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE + '（可用 WQ_BROWSER 指定）'); process.exit(0) }
const DIST_DIR = path.resolve(__dirname, '../dist')
if (!fs.existsSync(path.join(DIST_DIR, 'index.html'))) { console.log('SKIP  未找到 dist 产物（先 npx vite build）'); process.exit(0) }
const ORIGIN = 'http://ask.local'
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' }

// 一卡三问（与后端 askQuestionToMap 同形），remainingMs 故意给 7 分钟：验证倒计时按服务端剩余时限走
const QUESTIONS = [
  { topic: '出差交通', question: '明天出差，交通怎么走？', options: ['高铁：准点可控', '飞机：省时但易延误', '自驾：灵活但堵'] },
  { topic: '住宿', question: '住哪种酒店？', options: ['会场附近：省通勤', '车站附近：返程从容', '不住：当天往返'] },
  { topic: '午饭', question: '午饭吃什么？', options: ['食堂简餐：不挤行程', '商务工作餐：顺便谈事', '自带：最省时间'] }
]
// live=false 分支（进程重启后唤醒句柄不在）：卡片不该重建出一个点了没反应的输入框
let askLive = true
let historyGets = 0
let postBody = null
const posts = []

function serve (page) {
  return page.route('**/*', route => {
    const u = new URL(route.request().url())
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data }) })
    if (u.pathname === '/api/ai/auth/me') {
      return json({ user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: [] })
    }
    if (u.pathname === '/api/ai/notification/unread-count') return json({ count: 1 })
    if (u.pathname === '/api/ai/notification/list') return json({ items: [], nextCursor: 0, hasMore: false, total: 0, unreadCount: 0 })
    if (u.pathname === '/api/ai/notification/read' || u.pathname === '/api/ai/notification/read-all') return json({})
    if (u.pathname === '/api/ai/config') return json({})
    if (u.pathname === '/api/ai/config/public') return json({ upload: { maxFileSize: 209715200, allowedExts: ['docx', 'pdf'] } })
    if (u.pathname === '/api/ai/provider/available') {
      return json([{ name: '平台内置', models: [{ ref: 'chat-1', displayName: '演示聊天模型', type: 'chat' }] }])
    }
    if (u.pathname === '/api/ai/user/preference') return json({ defaultModel: 'chat-1' })
    if (u.pathname === '/api/ai/agent/available') return json([])
    if (u.pathname === '/api/ai/sessions') return json({ items: [], nextCursor: null, hasMore: false, groupCounts: {}, total: 0 })
    // 这一轮的助手消息要等回答完成才落库：恢复场景下历史里只有用户那句
    if (u.pathname === '/api/ai/session/s-ask') {
      historyGets++
      return json([{ messageId: 'q-1', role: 'user', content: '明天出差帮我安排一下', createTime: Date.now() - 120000 }])
    }
    // 卡片恢复入口（GET /ask-user/pending?sessionId=）：口径与 RagService.listPendingAsks 一致
    if (u.pathname === '/api/ai/ask-user/pending') {
      return json({ items: [{ askId: 'ask-1', questions: QUESTIONS, timeoutMs: 600000,
        remainingMs: 420000, createdAt: Date.now() - 180000, expired: false, live: askLive }] })
    }
    if (u.pathname.startsWith('/api/ai/ask-user/') && route.request().method() === 'POST') {
      postBody = JSON.parse(route.request().postData() || '{}')
      posts.push(u.pathname)
      return json('已提交')
    }
    if (u.host !== 'ask.local') return route.abort()
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
  const ctx = await browser.newContext({
    viewport: { width: 412, height: 916 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    userAgent: 'Mozilla/5.0 (Linux; Android 15; V2318A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
  })
  const page = await ctx.newPage()
  await serve(page)
  const errors = []
  page.on('pageerror', e => errors.push(String(e.message)))
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

  await page.goto(ORIGIN + '/login', { waitUntil: 'networkidle' })
  await page.evaluate(() => { localStorage.setItem('ai_token', 'mock-token-for-verify') })

  // ==================== 一、刷新后卡片重建 ====================
  await page.goto(ORIGIN + '/m/chat?sid=s-ask', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  const panel = await page.evaluate(() => {
    const q = document.querySelector('.m-askp-q')
    const t = document.querySelector('.m-askp-timer')
    const hint = document.querySelector('.m-askp-hint')
    return {
      hasPanel: !!document.querySelector('.m-askp'),
      question: q ? q.textContent.trim() : '',
      timer: t ? t.textContent.trim() : '',
      hint: hint ? hint.textContent.trim() : '',
      opts: [...document.querySelectorAll('.m-askp-opt')].map(b => b.textContent.trim()),
      pager: (document.querySelector('.m-askp-page-num') || {}).textContent || '',
      body: document.body.innerText
    }
  })
  check(panel.hasPanel, '刷新后提问卡从服务端待答记录重建（移动壳底部面板）')
  check(/交通怎么走/.test(panel.question), '卡面题干是落库的那一题', `question=${panel.question}`)
  check(/^0[67]:\d\d$/.test(panel.timer), '倒计时按后端 remainingMs 走（420s→约 07:00），不是本地重算满窗口', `timer=${panel.timer}`)
  check(/不替你选/.test(panel.hint) && !/推荐项/.test(panel.hint), '卡面口径改为「没答的题不替你选」', `hint=${panel.hint}`)
  check(panel.opts.length === 4, '候选 3 项 + 自由输入行齐活', `opts=${panel.opts.length}`)
  check(String(panel.pager).trim() === '1 / 3', '一卡三问的翻页计数正确', `pager=${String(panel.pager).trim()}`)

  // ==================== 二、作答提交：答案数组与问题下标对齐 ====================
  await page.evaluate(() => { document.querySelector('.m-askp-opt').click() })
  await page.waitForTimeout(400)
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.m-rec-btn')].find(x => x.textContent.includes('提交'))
    if (b) b.click()
  })
  await page.waitForTimeout(900)
  const after = await page.evaluate(() => ({
    hasPanel: !!document.querySelector('.m-askp'),
    body: document.body.innerText
  }))
  check(posts.length === 1 && posts[0] === '/api/ai/ask-user/ask-1', '提交打到 POST /ask-user/{askId}（用落库的 askId，不是新号）', `posts=${posts.join(',')}`)
  const ans = (postBody && postBody.answers) || []
  check(ans.length === 3, '答案数组按问题数对齐（3 题给 3 个槽位，不截断）', `len=${ans.length}`)
  check(/高铁/.test(String(ans[0] || '')), '第一题回的是用户点选的候选', `a0=${ans[0]}`)
  check(ans[1] === '' && ans[2] === '', '没答的两题留空送后端（由后端如实回「用户未回答」，不代答）', `a1=${ans[1]},a2=${ans[2]}`)
  check(!after.hasPanel, '恢复态提交后撤下答题面板（本设备没有这一轮的流，终态事件不会来）')
  check(/回答生成中/.test(after.body), '气泡转入「回答生成中，稍后自动刷新…」的后台续跑态')

  // ==================== 三、轮询取回后台生成的答案 ====================
  const firstGets = historyGets
  await page.waitForTimeout(4500)
  check(historyGets - firstGets >= 1, '恢复态答完后轮询会话历史取回答案（不靠用户手动刷新）', `historyGets=${firstGets}→${historyGets}`)

  // ==================== 四、唤醒句柄已不在（live=false）：不给静默失败的输入框 ====================
  askLive = false
  await page.goto(ORIGIN + '/login', { waitUntil: 'networkidle' })
  await page.goto(ORIGIN + '/m/chat?sid=s-ask', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  const dead = await page.evaluate(() => ({ hasPanel: !!document.querySelector('.m-askp'), body: document.body.innerText }))
  check(!dead.hasPanel, 'live=false 时不重建答题面板（点了也没人接）')
  check(/提问已失效|服务已重启/.test(dead.body), 'live=false 时明确出声：这一轮已失效', dead.body.slice(0, 0))

  check(errors.length === 0, '全程无页面脚本报错', errors.slice(0, 2).join(' | '))
  await browser.close()
  console.log(bad ? `\n${bad} 项未通过` : '\n全部通过')
  process.exit(bad ? 1 : 0)
})()
