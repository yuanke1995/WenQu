// 运行中插话（steer）的真浏览器校验。用桩接口而不是真模型：要的是「框架在哪一刻把这句收进上下文」
// 这个确定形态，真跑撞步数上限成本高且不稳。
//
// 钉住三件事：
//  1) 插话落在<b>回答的时间线中间</b>——位置＝后端那条 steer 帧（框架 inbox/applied 那一刻），
//     所以它夹在两批工具之间，而不是整段回答的上面或下面；
//  2) 界面上只有一处：进了这一轮的那句不再另推一颗独立用户气泡（库里那条用户行由引擎折叠）；
//  3) 没赶上这一轮的那句不许静默丢掉——收尾随 done.pendingSteers 交回，作为新一轮自动补发
//     （带 fromSteer，后端跳过重复落库），并且全界面只有它一颗气泡。
//
// 前置：先构建产物到 web/dist
//   npx vite build --outDir dist --emptyOutDir
// 依赖：playwright-core（不装进仓库依赖）：
//   mkdir -p /tmp/wq-verify && cd /tmp/wq-verify && npm i playwright-core
//   ln -s /tmp/wq-verify/node_modules/playwright-core web/node_modules/playwright-core
//   cd web && node scripts/check-steer-resend.cjs
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过插话验证')
  process.exit(0)
}
const path = require('path')
const fs = require('fs')

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
if (!fs.existsSync(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE); process.exit(0) }
const DIST_DIR = path.resolve(__dirname, '../dist')
if (!fs.existsSync(path.join(DIST_DIR, 'index.html'))) { console.log('SKIP  未找到 dist 产物'); process.exit(0) }
const ORIGIN = 'http://steer.local'
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' }

const APPLIED = '插进去的那句：只写两行'      // 框架收进上下文 → 长在回答时间线里
const MISSED = '没赶上这一轮的那句：加个目录'  // 收尾交回 → 作为新一轮补发
const sleep = ms => new Promise(r => setTimeout(r, ms))
const frame = o => 'data: ' + JSON.stringify(o) + '\n\n'
const tool = (name, status) => frame({ type: 'tool_status', content: { name, status, elapsedMs: 300 } })

function mock (page, cap) {
  return page.route('**/*', async route => {
    const u = new URL(route.request().url())
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, code: 200, data }) })
    const sse = frames => route.fulfill({ status: 200, contentType: 'text/event-stream',
      headers: { 'access-control-allow-origin': '*', 'cache-control': 'no-cache' }, body: frames.join('') })
    const p = u.pathname
    if (p === '/api/ai/auth/me') return json({ user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: [] })
    if (p === '/api/ai/notification/unread-count') return json({ count: 0 })
    if (p === '/api/ai/notification/list') return json({ items: [], nextCursor: 0, hasMore: false, total: 0, unreadCount: 0 })
    if (p === '/api/ai/config') return json([])
    if (p === '/api/ai/config/public') return json({ upload: { maxFileSize: 209715200, allowedExts: ['docx', 'pdf'] } })
    if (p === '/api/ai/provider/available') {
      return json([{ providerId: 'pv', name: '平台内置', models: [
        { ref: 'pv/chat-1', modelId: 'chat-1', displayName: '演示聊天模型', type: 'chat', toolCapable: true } ] }])
    }
    if (p === '/api/ai/user/preference') return json({ defaultModel: 'pv/chat-1' })
    if (p === '/api/ai/agent/available' || p === '/api/ai/agent') return json([])
    if (p === '/api/ai/sessions') return json({ items: [], nextCursor: null, hasMore: false, groupCounts: {}, total: 0 })
    if (p.startsWith('/api/ai/session/')) return json(cap.history)
    if (p === '/api/ai/skill' || p.startsWith('/api/ai/skill/')) return json([])
    if (p === '/api/ai/chat/steer') {
      let body = {}
      try { body = JSON.parse(route.request().postData() || '{}') } catch (e) { /* 非 JSON */ }
      cap.steers.push(body)
      // 与后端同形：收下并给出那条用户消息的 ID（前端按它认队列里那一条）
      return json({ accepted: true, messageId: 'm-steer-' + cap.steers.length })
    }
    if (p === '/api/ai/chat') {
      let body = {}
      try { body = JSON.parse(route.request().postData() || '{}') } catch (e) { /* 非 JSON */ }
      cap.chatBodies.push(body)
      if (cap.chatBodies.length === 1) {
        // 第一轮：慢一拍收尾，好让两句插话都赶在 done 之前点出去。
        // 帧序就是这一轮的时序：两批工具之间夹一条 steer 帧（框架把那句写进上下文的那一刻），
        // 收尾只把「没赶上」的那句交回。
        await sleep(12000)
        const text = '这就分六个小节展开讲。'
        return sse([
          tool('search_knowledge', 'start'), tool('search_knowledge', 'done'),
          frame({ type: 'steer', content: { text: APPLIED, messageId: 'm-steer-1' } }),
          tool('run_code', 'start'), tool('run_code', 'done'),
          frame({ type: 'token', content: text }),
          frame({ type: 'done', sessionId: 's-1', content: JSON.stringify({
            finalContent: text, messageId: 'm-ai-1', userMessageId: 'm-q-1',
            turnId: 't-1', sources: [], related: [], degradations: [], toolCalls: [], tokens: {},
            pendingSteers: [{ text: MISSED, messageId: 'm-steer-2' }] }) })
        ])
      }
      const text2 = '第一行结论。第二行依据。'
      return sse([
        frame({ type: 'token', content: text2 }),
        frame({ type: 'done', sessionId: 's-1', content: JSON.stringify({
          finalContent: text2, messageId: 'm-ai-2', turnId: 't-2',
          sources: [], related: [], degradations: [], toolCalls: [], tokens: {} }) })
      ])
    }
    if (p.startsWith('/api/ai/')) return json([])
    let f = path.join(DIST_DIR, decodeURIComponent(p))
    if (p === '/' || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST_DIR, 'index.html')
    try {
      route.fulfill({ status: 200, body: fs.readFileSync(f),
        headers: { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' } })
    } catch (e) { route.abort() }
  })
}

// 与真实键入同路径：contenteditable 的正文要靠 execCommand 才触发 input 事件
const typeDraft = async (page, text) => {
  await page.click('.composer-edit')
  await page.evaluate(t => {
    const el = document.querySelector('.composer-edit')
    el.focus()
    document.execCommand('insertText', false, t)
  }, text)
}

/** 生成中插一句：Enter 入队 → 点这一条上的「立即发送」。返回那一刻队列条的文案 */
const sendSteer = async (page, text, nth) => {
  await typeDraft(page, text)
  await page.keyboard.press('Enter')
  await page.waitForSelector('.steer-item', { timeout: 5000 })
  await page.locator('.steer-item').nth(nth).locator('button.steer-act.primary').click()
  await sleep(400)
  return page.locator('.steer-item').nth(nth).innerText()
}

let failed = 0
const ok = (name, cond, extra) => {
  console.log((cond ? 'PASS  ' : 'FAIL  ') + name + (extra ? '  →  ' + extra : ''))
  if (!cond) failed++
}

;(async () => {
  const browser = await chromium.launch({ executablePath: EDGE, viewport: { width: 1440, height: 900 } })
  const page = await browser.newPage()
  const cap = { steers: [], chatBodies: [], history: [] }
  await page.addInitScript(() => { localStorage.setItem('ai_token', 'probe-token') })
  await mock(page, cap)
  await page.goto(ORIGIN + '/chat?sid=s-1', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.composer-edit', { timeout: 15000 })

  await typeDraft(page, '请分六个小节写一篇 1200 字的说明')
  await page.keyboard.press('Enter')
  await sleep(1500)

  const chip1 = await sendSteer(page, APPLIED, 0)
  const chip2 = await sendSteer(page, MISSED, 1)
  // 送出后这句仍挂在队列条上（等这一轮听到），但流里不许先冒出一颗独立气泡
  const rowWhileRunning = await page.evaluate(t =>
    [...document.querySelectorAll('.messages .row.user')].some(r => (r.innerText || '').includes(t)), APPLIED)
  ok('点「立即发送」把这句标成「等这一轮听到」而不是立刻消失', /等这一轮听到/.test(chip1), chip1.replace(/\s+/g, ' '))
  ok('还没进模型前，流里不许先长出一颗独立气泡', rowWhileRunning === false)

  await page.waitForFunction(() => !document.querySelector('.tb-text.running'), { timeout: 30000 })
  // 过程默认收起，工具块看不见——点开第一轮那一轮的过程，插话段该夹在两批工具之间。
  // 认第一颗回答气泡：没赶上那句已经补发出第二轮了，最后一颗不是要看的那一轮
  await page.locator('.msg-block.ai').first().locator('.turn-bar .tb-btn').click()
  await sleep(300)
  const blocks = await page.evaluate(() => {
    const md = document.querySelector('.msg-block.ai .md')
    return md ? [...md.children].map(e => e.className.split(' ')[0]) : []
  })
  const at = blocks.indexOf('tl-steer')
  ok('插话长在回答的时间线里', at >= 0, blocks.join(' | '))
  ok('它夹在工具块之间（不是整段回答的上面或下面）',
    at > 0 && at < blocks.length - 1 && /^tl-(group|cluster)/.test(blocks[at - 1]) && /^tl-(group|cluster)/.test(blocks[at + 1]),
    blocks.join(' | '))
  const dup = await page.evaluate(t =>
    ((document.querySelector('.messages')?.innerText || '').match(new RegExp(t, 'g')) || []).length, APPLIED)
  ok('进了这一轮的那句全界面只出现一遍', dup === 1, 'count=' + dup)
  ok('它不再是一颗独立的用户气泡（库里那条用户行已折叠）',
    await page.evaluate(t => ![...document.querySelectorAll('.messages .row.user')]
      .some(r => (r.innerText || '').includes(t)), APPLIED))
  ok('两条都真发出去了 /chat/steer', cap.steers.length === 2, JSON.stringify(cap.steers.map(s => s.text)))
  ok('收尾后队列条清空（没有悬着的「等这一轮听到」）',
    (await page.locator('.steer-item').count()) === 0, chip2.replace(/\s+/g, ' '))

  // 没赶上这一轮的那句：done 交回后必须自动作为新一轮补发，且全界面只有它一颗气泡
  await sleep(3000)
  const missedRows = await page.evaluate(t => [...document.querySelectorAll('.messages .row.user')]
    .filter(r => (r.innerText || '').includes(t)).length, MISSED)
  ok('没赶上的那句自动补发了新一轮', cap.chatBodies.length === 2, 'chat 请求数=' + cap.chatBodies.length)
  ok('补发那一轮带 fromSteer（不重复落库）', cap.chatBodies[1] && cap.chatBodies[1].fromSteer === true,
    'fromSteer=' + (cap.chatBodies[1] || {}).fromSteer)
  ok('补发的是同一句话', cap.chatBodies[1] && cap.chatBodies[1].question === MISSED,
    'question=' + (cap.chatBodies[1] || {}).question)
  ok('补发的那句在流里只有一颗气泡', missedRows === 1, 'count=' + missedRows)
  await page.screenshot({ path: '/tmp/wq-steer-resend.png', fullPage: false })

  // ===== 刷新后必须同形：库里那句既是一条独立用户消息（sequence 排在回答之前），又是当轮回答
  // 时间线里的一段（框架把它写进上下文那一刻留的痕）。界面只认后者。
  const text1 = '这就分六个小节展开讲。'
  cap.history = [
    { messageId: 'm-q-1', role: 'user', content: '请分六个小节写一篇 1200 字的说明', turnId: 't-1' },
    { messageId: 'm-steer-1', role: 'user', content: APPLIED, turnId: 't-1' },
    { messageId: 'm-ai-1', role: 'assistant', content: text1, turnId: 't-1', toolCalls: [
      { name: 'search_knowledge', status: 'done', elapsedMs: 300 },
      { name: 'run_code', status: 'done', elapsedMs: 300 } ],
      timeline: [{ kind: 'tool', i: 0 }, { kind: 'steer', text: APPLIED, messageId: 'm-steer-1' },
        { kind: 'tool', i: 1 }, { kind: 'text', from: 0, to: text1.length }] },
    { messageId: 'm-steer-2', role: 'user', content: MISSED, turnId: 't-1' },
    { messageId: 'm-ai-2', role: 'assistant', content: '第一行结论。第二行依据。', turnId: 't-2' }
  ]
  await page.goto(ORIGIN + '/chat?sid=s-1', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.messages .row.ai', { timeout: 15000 })
  const after = await page.evaluate(([a, b]) => {
    const rows = [...document.querySelectorAll('.messages .row')]
    const userTexts = rows.filter(r => r.classList.contains('user')).map(r => r.innerText.replace(/\s+/g, ' '))
    const md = document.querySelector('.msg-block.ai .md')
    return { folded: !userTexts.some(t => t.includes(a)), kept: userTexts.some(t => t.includes(b)),
      blocks: md ? [...md.children].map(e => e.className.split(' ')[0]) : [],
      times: ((document.querySelector('.messages')?.innerText || '').match(new RegExp(a, 'g')) || []).length }
  }, [APPLIED, MISSED])
  ok('刷新后进了那一轮的那句不再单列成用户行', after.folded, JSON.stringify(after))
  ok('收起过程时它也还在（那是用户自己说的话，不跟着工具卡一起藏）',
    after.blocks.join('|') === 'tl-steer|md', after.blocks.join(' | '))
  ok('刷新后它全界面只出现一遍', after.times === 1, 'count=' + after.times)
  ok('没赶上那一轮的那句刷新后照旧是普通用户行（不许消失）', after.kept, JSON.stringify(after))
  await page.locator('.msg-block.ai').first().locator('.turn-bar .tb-btn').click()
  await sleep(300)
  const opened = await page.evaluate(() => {
    const md = document.querySelector('.msg-block.ai .md')
    return md ? [...md.children].map(e => e.className.split(' ')[0]) : []
  })
  ok('刷新后它仍在那一轮回答的时间线里，夹在两批工具之间',
    opened.join('|') === 'tl-group|tl-steer|tl-group|md', opened.join(' | '))
  await page.screenshot({ path: '/tmp/wq-steer-refresh.png', fullPage: false })
  await browser.close()
  console.log(failed ? 'FAIL  共 ' + failed + ' 条不过' : '全部通过')
  process.exit(failed ? 1 : 0)
})().catch(e => { console.log('FAIL  探针异常: ' + e); process.exit(1) })
