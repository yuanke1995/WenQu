// 分享阅读侧（/shared/:token 只读会话分享 + /s/:token 智能体对话）的真浏览器校验。
// 为什么单独一个脚本：分享链接大量在手机/微信里打开，是传播转化的产品门面（roadmap §14），
// 但这两个页面免登录、不走 AppLayout，check-mshell（移动壳）与 check-browser（PC 壳）都不经过它们
// ——check-h5 只断言「@media 存在」，测不出真渲染里的溢出/热区/键盘细节。
//
// 前置：先构建产物到 web/dist
//   npx vite build --outDir dist --emptyOutDir
// 依赖：playwright-core（不装进项目，临时用）：
//   mkdir -p /tmp/wq-verify && cd /tmp/wq-verify && npm i playwright-core
//   NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-share.cjs
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过分享页浏览器验证')
  console.log('      安装：cd /tmp/wq-verify && npm i playwright-core')
  process.exit(0)
}
const path = require('path')
const fs = require('fs')

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
if (!fs.existsSync(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE + '（可用 WQ_BROWSER 指定）'); process.exit(0) }
const DIST_DIR = path.resolve(__dirname, '../' + (process.env.WQ_DIST || 'dist'))
if (!fs.existsSync(path.join(DIST_DIR, 'index.html'))) { console.log('SKIP  未找到 dist 产物（先 npx vite build）'); process.exit(0) }
const ORIGIN = 'http://share.local'
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' }

// 分享接口 mock：/shared 用 getSharedSession（含产物与两类来源），/s 用 getShareInfo
const SHARED = {
  title: '季度复盘讨论',
  sharedAt: '2026-10-06T09:00:00',
  messages: [
    { role: 'user', content: '帮我把本季度的结论整理成一页周报' },
    { role: 'ai', content: '## 本季度结论\n\n1. 检索质量达标\n2. 引用可溯源', artifacts: [
      { filename: '季度周报.md', size: 2048, seq: 1, description: '一页纸周报' }
    ], sources: [
      { ref: '1', fileName: '产品手册.pdf', title: '指标口径' },
      { ref: '2', origin: 'WEB', siteName: 'example.com', title: '行业报告', url: 'https://example.com/report' }
    ] }
  ]
}
const AGENT = { name: '客服助手', description: '回答产品与订单问题', icon: '📦', isBuiltin: false }

// 游客多会话：一个访客在同一链接下可以有多段对话（列表 + 各自历史）
const SESSIONS = [
  { id: 's-share-1', title: '你好', updateTime: '2026-10-07T10:20:00', messageCount: 2 },
  { id: 's-share-0', title: '退款要几天到账', updateTime: '2026-10-06T09:00:00', messageCount: 4 }
]
const HISTORIES = {
  's-share-1': [{ role: 'user', content: '你好' }, { role: 'ai', content: '你好呀，有什么可以帮你？' }],
  's-share-0': [{ role: 'user', content: '退款要几天到账' }, { role: 'ai', content: '一般 1-3 个工作日到账。' }]
}

function serveStatic (page) {
  // cap：把浏览器发出去的游客请求回传给断言（新对话必须清空 sessionId；清除必须真的发删除）
  const cap = { chatBodies: [], deletes: [] }
  page.route('**/*', route => {
    const u = new URL(route.request().url())
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data }) })
    // 免登录页不走 /auth/me；分享接口按 token 给 mock
    if (u.pathname === '/api/ai/share/session/tok-1') return json(SHARED)
    if (u.pathname === '/api/ai/share/tok-2/info') return json(AGENT)
    if (u.pathname === '/api/ai/share/tok-2/sessions') {
      if (route.request().method() === 'DELETE') {
        cap.deletes.push(u.pathname + u.search)
        return json({ deleted: SESSIONS.length })
      }
      return json({ items: SESSIONS })
    }
    if (u.pathname === '/api/ai/share/tok-2/history') {
      return json(HISTORIES[u.searchParams.get('sessionId')] || [])
    }
    // tok-4：会话列表接口 500。断言失败必须显式报错并可重试，不能显示成"还没有历史对话"
    if (u.pathname === '/api/ai/share/tok-4/sessions') {
      return route.fulfill({ status: 500, contentType: 'application/json',
        headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify({ success: false, msg: '历史对话加载失败' }) })
    }
    if (u.pathname === '/api/ai/share/tok-4/info') return json(AGENT)
    // 游客流式对话 mock（两个 token 各测一个场景）。
    // 存在意义是**抓阶段提示重复渲染**：真实 SSE 会连发多条 stage，而模板曾同时渲染
    // 「气泡内 m.stage」与「底部 sending && lastAiStage」两个同源节点 ⇒ 同一句话出现两次。
    //   tok-3 = 只发 stage 不发 token/done：响应体结束后前端 pump() 停在 done 分支，
    //           sending 仍是 true ⇒ 页面冻结在「进行中」，正好采样重复渲染。
    //   tok-2 = 完整流：验证结束后阶段提示已撤掉、行数不重复。
    if (u.pathname === '/api/ai/share/tok-3/chat' || u.pathname === '/api/ai/share/tok-2/chat') {
      try { cap.chatBodies.push(JSON.parse(route.request().postData() || '{}')) } catch (e) { /* 非 JSON 请求体 */ }
      const tok3 = u.pathname.includes('tok-3')
      const frames = [
        'data: ' + JSON.stringify({ type: 'stage', content: '正在检索资料…' }) + '\n\n',
        'data: ' + JSON.stringify({ type: 'stage', content: '正在检索资料…' }) + '\n\n'
      ]
      if (!tok3) frames.push(
        'data: ' + JSON.stringify({ type: 'token', content: '根据文档，' }) + '\n\n',
        'data: ' + JSON.stringify({ type: 'token', content: '答案是 5。' }) + '\n\n',
        'data: ' + JSON.stringify({ type: 'done', sessionId: 's-share-1' }) + '\n\n')
      return route.fulfill({ status: 200, contentType: 'text/event-stream',
        headers: { 'access-control-allow-origin': '*', 'cache-control': 'no-cache' },
        body: frames.join('') })
    }
    if (u.pathname === '/api/ai/share/tok-3/info') return json(AGENT)
    if (u.host !== 'share.local') return route.abort()
    let p = decodeURIComponent(u.pathname)
    let f = path.join(DIST_DIR, p)
    if (p === '/' || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST_DIR, 'index.html')
    try {
      const buf = fs.readFileSync(f)
      route.fulfill({ status: 200, body: buf, headers: { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' } })
    } catch (e) { route.abort() }
  })
  return cap
}

let bad = 0
const check = (ok, label, detail = '') => {
  if (!ok) bad++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  →  ' + detail : ''}`)
}

;(async () => {
  const browser = await chromium.launch({ executablePath: EDGE, headless: true })
  // ---- 手机上下文：412×916 触屏（iQOO Neo11 档，与 check-mshell 同口径）----
  const ctx = await browser.newContext({
    viewport: { width: 412, height: 916 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    userAgent: 'Mozilla/5.0 (Linux; Android 15; V2318A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
  })
  const page = await ctx.newPage()
  const cap = serveStatic(page)
  const errors = []
  page.on('pageerror', e => errors.push(String(e.message)))
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

  // ---- /shared/:token（只读会话分享）----
  await page.goto(ORIGIN + '/shared/tok-1', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1100)
  const sh = await page.evaluate(() => {
    const de = document.documentElement
    const rows = [...document.querySelectorAll('.sh-row')]
    const art = document.querySelector('.sh-art')
    const brand = document.querySelector('.sh-brand')
    const inLayout = !!document.querySelector('.content-app')   // pageFlow：不该进 AppLayout 外壳
    return {
      title: (document.querySelector('.sh-title') || {}).textContent || '',
      rows: rows.length, userBubble: !!document.querySelector('.sh-bubble.user'),
      // markdown-it 配置把标题层级整体下移一级（## → h3），断言跟着真实渲染走
      mdRendered: !!document.querySelector('.sh-bubble.ai .md h3, .sh-bubble.ai .md h2'),
      artH: art ? Math.round(art.getBoundingClientRect().height) : 0,
      srcN: document.querySelectorAll('.sh-src-item').length,
      webLink: !!document.querySelector('.sh-src-link'),
      brandH: brand ? Math.round(brand.getBoundingClientRect().height) : 0,
      inLayout, scrollW: de.scrollWidth, clientW: de.clientWidth
    }
  })
  check(sh.title.includes('季度复盘'), '只读分享页标题渲染', sh.title)
  check(sh.rows === 2 && sh.userBubble && sh.mdRendered, '消息渲染且 AI 侧 markdown 已排版', JSON.stringify(sh))
  check(sh.artH >= 44, '产物卡触摸热区 ≥44px（分享页主要交互目标）', `h=${sh.artH}`)
  check(sh.srcN === 2, '引用来源两条（库内 + 联网）', `n=${sh.srcN}`)
  check(sh.webLink, '联网来源给「打开原网页」链接（库内来源不给，内容不外发）')
  check(sh.brandH >= 44, '页脚品牌链接触摸热区 ≥44px', `h=${sh.brandH}`)
  check(!sh.inLayout, 'pageFlow 生效：不进 AppLayout 外壳（否则 100vh 截断滚不动）')
  check(sh.scrollW <= sh.clientW + 1, '只读分享页无横向溢出', `scrollW=${sh.scrollW} clientW=${sh.clientW}`)

  // ---- /s/:token（智能体对话分享，可续聊）----
  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1100)
  const sc = await page.evaluate(() => {
    const de = document.documentElement
    const ta = document.querySelector('.sc-textarea')
    const send = document.querySelector('.sc-send')
    const page_ = document.querySelector('.sc-page')
    const list = document.querySelector('.sc-list')
    const listRect = list ? list.getBoundingClientRect() : { height: 0 }
    const heroRect = document.querySelector('.sc-welcome') ? document.querySelector('.sc-welcome').getBoundingClientRect() : null
    // 标题顶边（不是 .sc-welcome 盒子顶边——后者含 72px padding，量盒子会低估留白）
    const heroTitle = document.querySelector('.sc-welcome h2')
    const heroTitleTop = heroTitle ? Math.round(heroTitle.getBoundingClientRect().top) : -1
    return {
      name: (document.querySelector('.sc-name') || {}).textContent || '',
      hero: !!document.querySelector('.sc-welcome'),
      // 空态必须垂直居中：此前顶在上方，下面留一大片空白，页面显得空且廉价
      heroTopGap: heroTitleTop,
      heroBotGap: heroRect && listRect.height ? Math.round(listRect.top + listRect.height - heroRect.bottom) : -1,
      headAvatarEmoji: (document.querySelector('.sc-avatar') || {}).textContent || '',
      // 整页底色必须是白（--app-panel）。此前用 --app-bg 灰底时，用户气泡的
      // --app-panel-2 浅灰几乎与背景同色、输入框阴影浮在灰底上，整页观感偏暗，
      // 就是用户说的"看着不一样"。与 ChatPage .chat2 同为 --app-panel。
      pageBg: (() => { const e = document.querySelector('.sc-page')
        return e ? getComputedStyle(e).backgroundColor : '' })(),
      // 用户气泡底色必须与页面底色**可区分**：灰底+浅灰气泡 = 隐形

      brand: !!document.querySelector('.sc-brand'),
      taFont: ta ? parseFloat(getComputedStyle(ta).fontSize) : 0,
      taEnterhint: ta ? (ta.getAttribute('enterkeyhint') || '') : '',
      sendSize: send ? Math.round(send.getBoundingClientRect().width) : 0,
      vhH: page_ ? Math.round(page_.getBoundingClientRect().height) : 0,
      scrollW: de.scrollWidth, clientW: de.clientWidth
    }
  })
  check(sc.name.includes('客服助手') && sc.hero, '智能体分享页头部与空态渲染', sc.name)
  check(sc.headAvatarEmoji.includes('📦'), '头部用智能体自身图标（icon 字段透传到 AgentAvatar）', sc.headAvatarEmoji)
  check(sc.brand, '头部右侧有「问渠 WenQu」品牌标识（访客知道这是谁家的机器人）')
  check(sc.pageBg === 'rgb(255, 255, 255)',
    '整页白底（对齐 ChatPage .chat2；灰底会让浅灰用户气泡隐形）', `bg=${sc.pageBg}`)

  // 空态改为**靠上**（对齐 ChatPage .welcome 的 padding:72px 起）。
  // 曾改成垂直居中、后又试过"消息贴底"，两次都被判定为更糟并回退 —— 这里是回归保护。
  // 断标题顶边：.sc-welcome 盒子含 72px padding，量盒子会低估实际留白。
  check(sc.heroTopGap >= 100,
    '空态靠上且留出呼吸位（对齐 ChatPage .welcome：72px padding + 44px 标 + 14px 间距）', `标题top=${sc.heroTopGap}`)
  check(sc.taFont >= 16, '输入框字号 ≥16px（iOS 聚焦不缩放）', `font=${sc.taFont}`)
  check(sc.taEnterhint === 'send', '软键盘回车键显「发送」（enterkeyhint=send）', `enterkeyhint=${sc.taEnterhint}`)
  check(sc.sendSize >= 44, '发送键触摸热区 ≥44px', `w=${sc.sendSize}`)
  check(sc.vhH >= 800 && sc.vhH <= 916, '满高容器消费 --app-vh（键盘弹起收缩的前提）', `h=${sc.vhH}`)
  check(sc.scrollW <= sc.clientW + 1, '智能体分享页无横向溢出', `scrollW=${sc.scrollW} clientW=${sc.clientW}`)

  // ---- 真发一条消息：阶段提示必须只出现一次 ----
  // 2026-10 用户截图报「正在检索资料…」出现两次。根因是模板同时渲染了两处同源状态：
  // 气泡内的 m.stage 与底部一条 sending && lastAiStage 的独立气泡。
  // 这里让 SSE 连发两次相同 stage（真实链路就是会重复推同一条阶段），
  // 再断言全页 .sc-stage 只有 1 个。
  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await page.fill('.sc-textarea', '你好')
  await page.click('.sc-send')
  await page.waitForTimeout(1500)
  const streamed = await page.evaluate(() => ({
    // 正文已开始（token 已到）⇒ 阶段提示应已撤掉（m.stage 随正文开始被清）
    stageN: document.querySelectorAll('.sc-stage').length,
    typingN: document.querySelectorAll('.sc-typing').length,
    body: (document.querySelector('.sc-bubble.ai') || {}).textContent || '',
    bubbles: document.querySelectorAll('.sc-row').length
  }))
  check(streamed.body.includes('答案是 5'), '分享页 SSE 正文正常渲染（stage→token→done 全链路）', streamed.body.slice(0, 40))
  check(streamed.stageN === 0 && streamed.typingN === 0,
    '正文出来后阶段提示已消失（既无脚注也无「正在思考」行）', `stage=${streamed.stageN} typing=${streamed.typingN}`)
  check(streamed.bubbles === 2, '一轮问答只渲染 2 条（用户 1 + AI 1，无重复的阶段气泡）', `rows=${streamed.bubbles}`)
  // 灰底时代浅灰用户气泡几乎隐形（"看着不一样"的主因）——有消息后再取样比对
  const contrast = await page.evaluate(() => {
    const p = document.querySelector('.sc-page'), u = document.querySelector('.sc-bubble.user')
    if (!p || !u) return null
    return { page: getComputedStyle(p).backgroundColor, user: getComputedStyle(u).backgroundColor }
  })
  check(contrast && contrast.user && contrast.user !== contrast.page,
    '用户气泡底色与页面底色可区分（灰底 + 浅灰气泡 = 隐形）', JSON.stringify(contrast))

  // ---- 游客多会话：聊完一段能开第二段，也能切回上一段 ----
  // 此前分享页把一个 sessionId 长期钉在 localStorage，同一浏览器永远只在那一条会话里追加：
  // 上下文越滚越长（每轮带最近 20 轮），之前那段也回不去。建会话后端一直支持
  // （sessionId 传空即新建，见 ShareController.chat），缺的是前端"清空指针 + 找回旧会话"的入口。
  const tools = await page.evaluate(() => {
    const e = document.querySelector('.sc-tool-hist')
    const b = e ? e.getBoundingClientRect() : null
    return {
      hist: !!e,
      newDisabled: document.querySelector('.sc-tool-new') ? document.querySelector('.sc-tool-new').disabled : true,
      hitH: b ? Math.round(b.height) : 0,
      stored: localStorage.getItem('share_session_tok-2')
    }
  })
  check(tools.hist && !tools.newDisabled, '聊过一轮后「新对话」可用（此前页面上没有任何建会话入口）')
  check(tools.stored === 's-share-1', '首轮 done 的 sessionId 已记忆（刷新能接着聊）', String(tools.stored))
  check(tools.hitH >= 28, '历史对话按钮可点区域够高', `h=${tools.hitH}`)

  await page.click('.sc-tool-new')
  await page.waitForTimeout(300)
  const afterNew = await page.evaluate(() => ({
    rows: document.querySelectorAll('.sc-row').length,
    hero: !!document.querySelector('.sc-welcome'),
    stored: localStorage.getItem('share_session_tok-2')
  }))
  check(afterNew.rows === 0 && afterNew.hero && !afterNew.stored,
    '新对话：清空页面并放弃旧会话指针（回到空态，而不是接着往旧对话后面追加）', JSON.stringify(afterNew))

  await page.fill('.sc-textarea', '第二段的问题')
  await page.click('.sc-send')
  await page.waitForTimeout(1200)
  const lastBody = cap.chatBodies[cap.chatBodies.length - 1] || {}
  check(lastBody.sessionId === '', '新对话首条消息不带旧 sessionId（后端据此开第二段）', JSON.stringify(lastBody.sessionId))

  await page.click('.sc-tool-hist')
  await page.waitForTimeout(800)
  const pop = await page.evaluate(() => ({
    n: document.querySelectorAll('.sc-hist-item').length,
    titles: [...document.querySelectorAll('.sc-hist-title')].map(e => e.textContent.trim()),
    metas: [...document.querySelectorAll('.sc-hist-meta')].map(e => e.textContent.trim()),
    empty: !!document.querySelector('.sc-hist-empty'),
    err: !!document.querySelector('.sc-hist-err')
  }))
  check(pop.n === 2 && !pop.empty && !pop.err, '历史对话列出自己的两段会话', JSON.stringify(pop.titles))
  check(pop.metas.some(t => /今天 \d{2}:\d{2}/.test(t)) && pop.metas.some(t => /4 条/.test(t)),
    '会话行带时间与条数（游客就靠这两个认出是哪一段）', JSON.stringify(pop.metas))

  await page.locator('.sc-hist-item').nth(1).click()
  await page.waitForTimeout(900)
  const switched = await page.evaluate(() => ({
    rows: document.querySelectorAll('.sc-row').length,
    first: (document.querySelector('.sc-bubble.user') || {}).textContent || '',
    stored: localStorage.getItem('share_session_tok-2'),
    // 面板收起不能断"节点不存在"——antd 默认不销毁浮层节点，收起后它只是零高
    popOpen: (() => { const e = document.querySelector('.sc-hist'); return !!e && e.getBoundingClientRect().height > 0 })()
  }))
  check(switched.rows === 2 && switched.first.includes('退款要几天'),
    '切回上一段：正文换成那一段的消息', `rows=${switched.rows} first=${switched.first}`)
  check(switched.stored === 's-share-0' && !switched.popOpen,
    '切换后指针跟着走并收起面板', String(switched.stored))

  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  const restored = await page.evaluate(() => ({
    rows: document.querySelectorAll('.sc-row').length,
    first: (document.querySelector('.sc-bubble.user') || {}).textContent || ''
  }))
  check(restored.rows === 2 && restored.first.includes('退款要几天'),
    '刷新后回到切换后的那一段（不是最早记住的那段）', JSON.stringify(restored))

  // 失败必须显式报错并可重试：显示成空列表会被读成"我之前那段没保存"
  await page.goto(ORIGIN + '/s/tok-4', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await page.click('.sc-tool-hist')
  await page.waitForTimeout(800)
  const failed = await page.evaluate(() => ({
    err: (document.querySelector('.sc-hist-err') || {}).textContent || '',
    empty: !!document.querySelector('.sc-hist-empty')
  }))
  check(/加载失败/.test(failed.err) && !failed.empty,
    '会话列表拉取失败：报错 + 重试入口（不许伪装成"还没有历史对话"）', failed.err)

  // ---- 清除我的对话记录（公用电脑上的收尾动作）----
  // 这段是这次加"历史列表"必然带出来的反面：记录能被下一一个人点开了，就必须能一次抹掉。
  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await page.click('.sc-tool-hist')
  await page.waitForTimeout(800)
  const beforeClear = await page.evaluate(() => ({
    clear: !!document.querySelector('.sc-hist-clear'),
    yes: !!document.querySelector('.sc-hist-yes')
  }))
  check(beforeClear.clear && !beforeClear.yes, '历史面板底部有「清除我的对话记录」')
  await page.click('.sc-hist-clear')
  await page.waitForTimeout(300)
  const asked = await page.evaluate(() => ({
    ask: (document.querySelector('.sc-hist-ask') || {}).textContent || '',
    yes: !!document.querySelector('.sc-hist-yes'),
    sent: 0
  }))
  check(asked.yes && /找不回/.test(asked.ask),
    '清除先就地二次确认（一按就删掉全部记录太危险）', asked.ask)
  await page.click('.sc-hist-yes')
  await page.waitForTimeout(900)
  const cleared = await page.evaluate(() => ({
    stored: localStorage.getItem('share_session_tok-2'),
    rows: document.querySelectorAll('.sc-row').length,
    hero: !!document.querySelector('.sc-welcome'),
    popOpen: (() => { const e = document.querySelector('.sc-hist'); return !!e && e.getBoundingClientRect().height > 0 })()
  }))
  check(cap.deletes.length === 1 && cap.deletes[0].startsWith('/api/ai/share/tok-2/sessions')
    && /visitorId=/.test(cap.deletes[0]),
    '确认后才真的发删除（带访客标识，服务端按 uid+智能体圈定范围）', JSON.stringify(cap.deletes))
  check(!cleared.stored && cleared.rows === 0 && cleared.hero && !cleared.popOpen,
    '清除后：当前线程清空回到空态、指针作废、面板收起', JSON.stringify(cleared))

  // 嵌入紧凑模式没有头部，会话工具必须还在（收成一行小条）
  await page.goto(ORIGIN + '/s/tok-2?embed=1', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const emb = await page.evaluate(() => {
    const head = document.querySelector('.sc-head')
    const label = document.querySelector('.sc-tool-t')
    return {
      mini: !!head && head.classList.contains('mini'),
      tools: document.querySelectorAll('.sc-tool').length,
      brand: !!document.querySelector('.sc-brand'),
      name: !!document.querySelector('.sc-name'),
      headH: head ? Math.round(head.getBoundingClientRect().height) : 0,
      labelDisplay: label ? getComputedStyle(label).display : ''
    }
  })
  check(emb.mini && emb.tools === 2 && !emb.brand && !emb.name,
    '嵌入模式保留会话工具（小条，不带头部信息）', JSON.stringify(emb))
  check(emb.labelDisplay === 'none' && emb.headH > 0 && emb.headH <= 56,
    '窄屏会话按钮收成图标（不挤掉智能体名称）', `h=${emb.headH} label=${emb.labelDisplay}`)

  // 进行中（只收到 stage、还没正文）：阶段提示**全页只能有一条**。
  // tok-3 的 mock 只发 stage 帧，响应体结束后前端 pump() 走 done 分支只 settle 不改 sending
  // ⇒ 页面稳定停在「进行中」，可重复采样，不怕时序抖动。
  await page.goto(ORIGIN + '/s/tok-3', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await page.fill('.sc-textarea', '你好')
  await page.click('.sc-send')
  await page.waitForTimeout(1600)
  const during = await page.evaluate(() => {
    const tip = document.querySelector('.sc-typing')
    const r = tip ? tip.getBoundingClientRect() : { width: 0, height: 0 }
    return {
      // 提示行唯一（不多处渲染同一条）
      tipN: document.querySelectorAll('.sc-typing').length,
      stageN: document.querySelectorAll('.sc-stage').length,
      tipText: tip ? tip.textContent.trim() : '',
      // 单行：高度不应超过 ~28px（折成两行会翻倍）
      tipH: Math.round(r.height), tipW: Math.round(r.width),
      // 生成中不该有气泡壳：没有边框/底色，也就没有"框中框"
      // 「无气泡壳」= 元素可存在（内容容器），但不得有边框/底色/内边距。
      // 断元素个数会把"改成 transparent 但保留容器"这种正确实现误判成 bug。
      bubbleStyle: (() => {
        const e = document.querySelector('.sc-bubble.ai')
        if (!e) return null
        const cs = getComputedStyle(e)
        return { bg: cs.backgroundColor, border: cs.borderTopWidth, pad: cs.paddingTop }
      })(),
      rows: document.querySelectorAll('.sc-row').length,
      sending: !!document.querySelector('.sc-send.stop')
    }
  })
  check(during.sending, '流未结束时发送键处于「停止」态（确在生成中）')
  check(during.tipN === 1 && during.stageN === 0,
    '进行中：提示只出现一次（不多处渲染同一条）', `typing=${during.tipN} stage=${during.stageN} "${during.tipText}"`)
  check(during.tipH > 0 && during.tipH <= 30,
    '进行中：提示是单行（不被挤成两行）', `h=${during.tipH} "${during.tipText}"`)
  // AI 侧**本就没有气泡壳**（对齐 ChatPage .bubble.ai：transparent + padding:0）。
  // 断"背景透明 + 无边框 + 无内边距"三项，而不是断元素个数——元素是内容容器，本就该存在。
  const bs = during.bubbleStyle
  check(bs && bs.bg === 'rgba(0, 0, 0, 0)' && parseFloat(bs.border) === 0 && parseFloat(bs.pad) === 0,
    '进行中：AI 侧无气泡壳（背景透明/无边框/无内边距）', JSON.stringify(bs))
  check(during.rows === 2, '进行中：一轮问答仍只渲染 2 条（无多余的阶段气泡）', `rows=${during.rows}`)

  // ---- 桌面回归：1280 下分享页保持居中栏与 PC 字号（窄屏补丁不得外溢到桌面）----
  const dpage = await ctx.newPage()
  await dpage.setViewportSize({ width: 1280, height: 800 })
  serveStatic(dpage)
  await dpage.goto(ORIGIN + '/shared/tok-1', { waitUntil: 'networkidle' })
  await dpage.waitForTimeout(900)
  const dsh = await dpage.evaluate(() => {
    const head = document.querySelector('.sh-head')
    const ta = null
    return { headW: head ? Math.round(head.getBoundingClientRect().width) : 0,
      artH: document.querySelector('.sh-art') ? Math.round(document.querySelector('.sh-art').getBoundingClientRect().height) : 0 }
  })
  check(dsh.headW >= 700 && dsh.headW <= 820, '桌面只读分享页保持 820px 居中栏', `w=${dsh.headW}`)
  check(dsh.artH > 0 && dsh.artH < 44, '桌面产物卡保持紧凑行（44px 热区仅触屏）', `h=${dsh.artH}`)
  // 上面的多会话用例把会话指针写进了 localStorage（同一浏览器上下文共享），
  // 不清掉就会恢复成有消息的线程，量不到空态排版。
  await dpage.evaluate(() => { try { localStorage.clear() } catch (e) { /* ignore */ } })
  await dpage.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await dpage.waitForTimeout(900)
  const dsc = await dpage.evaluate(() => {
    const ta = document.querySelector('.sc-textarea')
    const th = document.querySelector('.sc-msg-block')
    const hero = document.querySelector('.sc-welcome')
    const inp = document.querySelector('.sc-input')
    return {
      taFont: ta ? getComputedStyle(ta).fontSize : '',
      // 桌面宽度下线程/输入区必须限宽居中，否则 1280 宽的气泡行长失控。
      // 空态量 maxWidth 而非实际宽：hero 是 flex 居中子项，按内容收缩是 flex 的正常行为，
      // 真正要卡的是「它最多能有多宽」——CSS 错写成 max-width 缺失时才会真的铺满。
      // .sc-welcome 无 max-width（块级靠父 padding 控宽），断 max-width 会读到 9999。
      // 断**内容实际宽度**：不铺满视口即可。
      heroW: hero ? Math.round(hero.getBoundingClientRect().width) : 0,
      threadW: th ? Math.round(th.getBoundingClientRect().width) : 0,
      inputW: inp ? Math.round(inp.getBoundingClientRect().width) : 0,
      vw: window.innerWidth
    }
  })
  check(parseFloat(dsc.taFont) < 16, '桌面输入框保持 14px（16px 规则只在 ≤768 补丁内）', `font=${dsc.taFont}`)
  check(dsc.heroW > 0 && dsc.heroW < dsc.vw, '桌面空态内容不铺满视口（左右有留白）', `w=${dsc.heroW}/${dsc.vw}`)
  check(dsc.inputW > 0 && dsc.inputW <= 880, '桌面输入区限宽居中（与 ChatPage 同为 860）', `w=${dsc.inputW}`)

  const real = errors.filter(e => !/Failed to load resource|ERR_FAILED|401/i.test(e))
  check(real.length === 0, '无 JS 运行时错误', real.slice(0, 3).join(' | '))

  await browser.close()
  console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
  process.exit(bad ? 1 : 0)
})().catch(e => { console.error('验证脚本异常：', e.message); process.exit(2) })
