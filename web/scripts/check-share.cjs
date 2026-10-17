// 分享阅读侧（/shared/:token 只读会话分享 + /s/:token 智能体对话）的真浏览器校验。
// 为什么单独一个脚本：分享链接大量在手机/微信里打开，是传播转化的产品门面（roadmap §14），
// 但这两个页面免登录、不走 AppLayout，check-mshell（移动壳）与 check-browser（PC 壳）都不经过它们
// ——check-h5 只断言「@media 存在」，测不出真渲染里的溢出/热区/键盘细节。
//
// 前置：先构建产物到 web/dist
//   npx vite build --outDir dist --emptyOutDir
// 依赖：playwright-core（不装进仓库依赖，临时用）：
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
const AGENT = { name: '客服助手', description: '回答产品与订单问题', icon: '📦', isBuiltin: false,
  // 游客多模态能力位与限额（发布者在「公开发布 → 游客输入」里打开后由 /info 下发）。
  // 断言里的数字刻意与前端默认值不同：这样"前端有没有真的用服务端下发的值"才测得出来
  guestImages: true, guestFiles: true,
  maxImages: 3, maxImageMb: 10, maxAttachments: 2, maxAttachmentMb: 5, maxQuestionChars: 8000 }
/** 未开放多模态的分享：附件入口必须**根本不出现**（摆一个点了报错的按钮等于骗用户） */
const AGENT_PLAIN = { name: '纯文字助手', description: '只能文字提问', icon: '🤖', isBuiltin: false,
  guestImages: false, guestFiles: false,
  maxImages: 9, maxImageMb: 10, maxAttachments: 5, maxAttachmentMb: 15, maxQuestionChars: 8000 }

// 游客多会话：一个访客在同一链接下可以有多段对话（列表 + 各自历史）。
// 时间**相对当前时刻**生成，不写死日期：断言的正是「今天 HH:MM」那一档文案，
// 写死 2026-10-07 的后果是过了那天这条用例必然失败——而它当时看起来完全正常。
// 现在第一条=今天（走「今天」分支），第二条=昨天（走「昨天」分支），两档文案都被覆盖。
// 锚在「当地某天的固定钟点」而不是「现在往前推 N 小时」：后者在凌晨 0–2 点跑会把
// "往前推 2 小时"推到昨天，于是断言里的「今天 HH:MM」必然落空（实测踩过，00:30 跑的）。
const atLocal = (dayOffset, hh, mm) => {
  const d = new Date()
  d.setDate(d.getDate() + dayOffset)
  d.setHours(hh, mm, 0, 0)
  return d.toISOString()
}
const SESSIONS = [
  { id: 's-share-1', title: '你好', updateTime: atLocal(0, 10, 20), messageCount: 2 },
  { id: 's-share-0', title: '退款要几天到账', updateTime: atLocal(-1, 9, 0), messageCount: 4 }
]
const HISTORIES = {
  's-share-1': [{ role: 'user', content: '你好' }, { role: 'ai', content: '你好呀，有什么可以帮你？' }],
  's-share-0': [{ role: 'user', content: '退款要几天到账' }, { role: 'ai', content: '一般 1-3 个工作日到账。' }],
  // 评价状态回填：后端（ShareController.history 与 ChatController.history 同口径）会给已评过的
  // 消息带上 fb。前端不回填的话，刷新后按钮显示成没评过，游客会以为反馈丢了再点一次
  's-share-fb': [
    { role: 'user', content: '这个怎么算' },
    { role: 'ai', content: '按天算。', messageId: 'm-fb-1', fb: 0 }
  ]
}

function serveStatic (page) {
  // cap：把浏览器发出去的游客请求回传给断言（新对话必须清空 sessionId；清除必须真的发删除；
  // 多模态要看发送体里到底带了什么；评价/重新生成要看打没打到服务端、带没带对凭据）
  const cap = { chatBodies: [], deletes: [], uploads: [], feedbacks: [] }
  page.route('**/*', route => {
    const u = new URL(route.request().url())
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data }) })
    // 免登录页不走 /auth/me；分享接口按 token 给 mock
    if (u.pathname === '/api/ai/share/session/tok-1') return json(SHARED)
    if (u.pathname === '/api/ai/share/tok-2/info') return json(AGENT)
    if (u.pathname === '/api/ai/share/tok-5/info') return json(AGENT_PLAIN)
    // 游客附件上传：按 visitorId 记账，回一个 fileId（服务端真实实现按访客隔离落盘）
    if (u.pathname === '/api/ai/share/tok-2/attachment') {
      cap.uploads.push({ visitorId: u.searchParams.get('visitorId') || '' })
      return json({ fileId: 'f-mock-1', name: 'note.txt', mime: 'text/plain', size: 5 })
    }
    // 游客评价：把请求体回传给断言（messageId/rating/visitorId 三样都必须带对）
    if (u.pathname === '/api/ai/share/tok-2/feedback') {
      try { cap.feedbacks.push(JSON.parse(route.request().postData() || '{}')) } catch (e) { /* 非 JSON */ }
      return json('感谢反馈')
    }
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
    // 存在意义是**抓进行中提示重复渲染**：一轮里用户可见的「正在…」只许有一条（TurnBar 的
    // 「正在溯源，用时 N …」），历史上它和阶段脚注、底部独立气泡各说各话，同一句话出现两次。
    //   tok-3 = 一帧都不发也不收尾：响应体结束后前端 pump() 等不到 done，sending 仍是 true
    //           ⇒ 页面稳定停在「进行中」，可重复采样，不怕时序抖动。
    //   tok-2 = 完整流（token→done 带 elapsedMs）：验证正文出来后进行中那行换成整轮总览。
    if (u.pathname === '/api/ai/share/tok-3/chat' || u.pathname === '/api/ai/share/tok-2/chat') {
      try { cap.chatBodies.push(JSON.parse(route.request().postData() || '{}')) } catch (e) { /* 非 JSON 请求体 */ }
      const tok3 = u.pathname.includes('tok-3')
      const frames = []
      if (!tok3) frames.push(
        'data: ' + JSON.stringify({ type: 'token', content: '根据文档，' }) + '\n\n',
        'data: ' + JSON.stringify({ type: 'token', content: '答案是 5。' }) + '\n\n',
        // done.content 是本轮的落库版汇总（与服务端 done 信封同形）。这里刻意带上
        // messageId 与 degradations：前者是评价/重新生成的凭据，后者是"答了但打了折扣"的说明，
        // 两样都是后端**早就在下发**、而分享页此前整个丢掉的东西。
        // elapsedMs 同样是后端一直在发的整轮墙钟：分享页那行「已完成，用时 …」读的就是它。
        'data: ' + JSON.stringify({
          type: 'done', sessionId: 's-share-1',
          content: JSON.stringify({
            finalContent: '根据文档，答案是 5。',
            messageId: 'm-ai-1',
            elapsedMs: 12000,
            sources: [],
            degradations: [{ code: 'modelFallback', msg: '本轮按备用模型作答' }]
          })
        }) + '\n\n')
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

// 输入区已抽成 chat/ChatInput.vue（与主对话页共用）：正文是 contenteditable 的 .composer-edit，
// 附件/图片是同一条文字流里的 .cchip 节点。于是 page.fill('.sc-textarea') 那套不再适用，
// 这里补两个与真实键入同路径的助手。
//
// 为什么用 execCommand('insertText') 而不是直接改 textContent：
// 后者不触发 input 事件，Composer 不知道正文变了，组件也不会把值同步出去——
// 那样测到的是「我改了 DOM」而不是「用户打字后页面怎么反应」。
const typeDraft = async (page, text) => {
  await page.evaluate(t => {
    const el = document.querySelector('.composer-edit')
    el.focus()
    // 光标落到**正文末尾**再插字：绝不能 selectAll + insertText ——
    // chip 是编辑区里的原子节点，全选会被这次插入一起替换掉，
    // 于是测到的变成「把材料删光之后发送」，而不是「带着材料打完字发送」（实测踩过）
    const range = document.createRange()
    range.selectNodeContents(el)
    range.collapse(false)
    const sel = window.getSelection()
    sel.removeAllRanges(); sel.addRange(range)
    document.execCommand('insertText', false, t)
  }, text)
  await page.waitForTimeout(150)
}
// 正文（不含 chip）：chip 按设计「不产出字符」（见 Composer 的三条边界），
// 所以读的时候要把它们摘掉再取文本，否则断言测的是渲染而不是序列化口径
const draftText = page => page.evaluate(() => {
  const el = document.querySelector('.composer-edit')
  if (!el) return ''
  const c = el.cloneNode(true)
  c.querySelectorAll('.cchip').forEach(n => n.remove())
  return c.textContent || ''
})

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
      inLayout, scrollW: de.scrollWidth, clientW: de.clientWidth,
      // 右边缘对齐：用户气泡贴右、回答卡却短一截（.sh-bubble 上的 max-width 误伤 AI 侧）
      colRight: Math.round(document.querySelector('.sh-list').getBoundingClientRect().right),
      aiRight: Math.round(document.querySelector('.sh-bubble.ai').getBoundingClientRect().right),
      userRight: Math.round(document.querySelector('.sh-bubble.user').getBoundingClientRect().right)
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
  // 2026-10 用户截图报「回答区域没对齐」：AI 回答卡的右边缘与用户气泡/标题栏差 98px。
  // 根因是 .sh-bubble 上的 max-width: 88%（本意只约束用户气泡）把 width:100% 的 AI 卡也卡窄了。
  // AI 卡右边缘必须与用户气泡右边缘、居中栏右边缘三者重合（1px 容差给取整）。
  check(Math.abs(sh.userRight - sh.colRight) <= 1 && Math.abs(sh.aiRight - sh.colRight) <= 1,
    '回答卡右边缘与用户气泡/居中栏对齐（.sh-bubble 的 max-width 只约束用户侧）',
    `col=${sh.colRight} ai=${sh.aiRight} user=${sh.userRight}`)

  // ---- /s/:token（智能体对话分享，可续聊）----
  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1100)
  const sc = await page.evaluate(() => {
    const de = document.documentElement
    const ta = document.querySelector('.composer-edit')
    const send = document.querySelector('.ci-send')
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

  // ---- 真发一条消息：进行中提示只有一条，答完收成整轮总览 ----
  // 2026-10 用户截图报「正在检索资料…」出现两次（气泡内阶段脚注 + 底部独立气泡）。
  // 现在分享页与主对话页共用同一个 TurnBar：一轮只有一行状态，收尾就换成「已完成，用时 …」。
  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await typeDraft(page, '你好')
  await page.click('.ci-send')
  await page.waitForTimeout(1500)
  const streamed = await page.evaluate(() => ({
    turnN: document.querySelectorAll('.turn-bar').length,
    turnText: (document.querySelector('.turn-bar') || {}).textContent?.trim() || '',
    // 退役的阶段提示行不该再有节点（.tm-typing / .tm-stage 整块随通道删除）
    legacyN: document.querySelectorAll('.tm-typing, .tm-stage').length,
    // 遗留文案一个字都不许出现在屏幕上
    stale: ['正在检索资料', '正在理解问题', '正在执行工作流', '框架引擎']
      .filter(s => (document.body.innerText || '').includes(s)),
    body: (document.querySelector('.bubble.ai') || {}).textContent || '',
    bubbles: document.querySelectorAll('.row').length
  }))
  check(streamed.body.includes('答案是 5'), '分享页 SSE 正文正常渲染（token→done 全链路）', streamed.body.slice(0, 40))
  check(streamed.turnN === 1 && streamed.turnText === '已完成，用时 12 秒',
    '收尾：整轮总览只有一条，用时读 done 带回的墙钟', `n=${streamed.turnN} "${streamed.turnText}"`)
  check(streamed.legacyN === 0 && streamed.stale.length === 0,
    '阶段提示行与遗留文案全页为零', `legacy=${streamed.legacyN} stale=${streamed.stale.join('/')}`)
  check(streamed.bubbles === 2, '一轮问答只渲染 2 条（用户 1 + AI 1，无重复的状态气泡）', `rows=${streamed.bubbles}`)
  // 灰底时代浅灰用户气泡几乎隐形（"看着不一样"的主因）——有消息后再取样比对
  const contrast = await page.evaluate(() => {
    const p = document.querySelector('.sc-page'), u = document.querySelector('.bubble.user')
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
    rows: document.querySelectorAll('.row').length,
    hero: !!document.querySelector('.sc-welcome'),
    stored: localStorage.getItem('share_session_tok-2')
  }))
  check(afterNew.rows === 0 && afterNew.hero && !afterNew.stored,
    '新对话：清空页面并放弃旧会话指针（回到空态，而不是接着往旧对话后面追加）', JSON.stringify(afterNew))

  await typeDraft(page, '第二段的问题')
  await page.click('.ci-send')
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
    rows: document.querySelectorAll('.row').length,
    first: (document.querySelector('.bubble.user') || {}).textContent || '',
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
    rows: document.querySelectorAll('.row').length,
    first: (document.querySelector('.bubble.user') || {}).textContent || ''
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
    rows: document.querySelectorAll('.row').length,
    hero: !!document.querySelector('.sc-welcome'),
    popOpen: (() => { const e = document.querySelector('.sc-hist'); return !!e && e.getBoundingClientRect().height > 0 })()
  }))
  check(cap.deletes.length === 1 && cap.deletes[0].startsWith('/api/ai/share/tok-2/sessions')
    && /visitorId=/.test(cap.deletes[0]),
    '确认后才真的发删除（带访客标识，服务端按 uid+智能体圈定范围）', JSON.stringify(cap.deletes))
  check(!cleared.stored && cleared.rows === 0 && cleared.hero && !cleared.popOpen,
    '清除后：当前线程清空回到空态、指针作废、面板收起', JSON.stringify(cleared))

  // ---- 游客多模态输入（图片 / 文档附件，发布者可配开关）----
  // 背景：分享页此前在构造上就是纯文字的——ShareController.chat 写死
  // userImages(List.of()).attachments(List.of())，前端也没有任何取材入口。
  // 这一段把「能力位 → 入口 → 取材 → 发送载荷」整条链路走一遍。
  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.evaluate(() => { try { localStorage.clear() } catch (e) { /* ignore */ } })
  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)

  const att0 = await page.evaluate(() => {
    const btn = document.querySelector('.ci-attach')
    const r = btn ? btn.getBoundingClientRect() : null
    return {
      has: !!btn,
      w: r ? Math.round(r.width) : 0, h: r ? Math.round(r.height) : 0,
      // 接收类型必须只覆盖能力位允许的那几类
      accept: (document.querySelector('.ci-file') || {}).accept || '',
      placeholder: (document.querySelector('.composer-edit') || {}).getAttribute?.('data-ph') || ''
    }
  })
  check(att0.has, '开放了多模态的分享：输入框左侧出现附件入口')
  check(att0.w >= 36 && att0.h >= 36, '附件入口触控热区 ≥36px', `${att0.w}×${att0.h}`)
  check(att0.accept.includes('image/*') && att0.accept.includes('.pdf'),
    '选择器接收类型按能力位生成（图片 + 受支持文档格式）', att0.accept.slice(0, 40))
  check(att0.placeholder.includes('图片'), '输入框占位文案点出能带图片/文件', att0.placeholder)

  // 图片走**粘贴**这条路（截图是分享页最高频的多模态入口）：在页面里用 canvas 造一张真 PNG，
  // 组一个带文件的 paste 事件派发出去——比 setInputFiles 更接近真实用户动作，也顺带验证
  // "粘贴必须 preventDefault"这条（否则浏览器会把文件名当文本插进正文）
  await page.evaluate(async () => {
    const c = document.createElement('canvas')
    c.width = 8; c.height = 8
    const ctx = c.getContext('2d')
    ctx.fillStyle = '#e33'; ctx.fillRect(0, 0, 8, 8)
    const blob = await new Promise(r => c.toBlob(r, 'image/png'))
    const file = new File([blob], 'shot.png', { type: 'image/png' })
    const dt = new DataTransfer()
    dt.items.add(file)
    document.querySelector('.composer-edit').dispatchEvent(
      new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  })
  await page.waitForTimeout(600)
  const imgChip = await page.evaluate(() => ({
    n: document.querySelectorAll('.cchip-img').length,
    // 压缩后是 JPEG data URL（1280px / q0.85），不是原图
    src: (document.querySelector('.cchip-thumb') || {}).src || '',
    ta: null   // 下面用 draftText 单独取（contenteditable 没有 .value）
  }))
  check(imgChip.n === 1, '粘贴图片进入待发送区（缩略图 chip）', `n=${imgChip.n}`)
  check(imgChip.src.startsWith('data:image/jpeg'), '图片已压缩为 JPEG data URL（与主聊天页同一管线的参数）', imgChip.src.slice(0, 24))
  const afterPasteDraft = await draftText(page)
  check(afterPasteDraft === '', '带文件的粘贴被截断默认行为（文件名没被当文本插进正文）', JSON.stringify(afterPasteDraft))

  // 附件走「选文件」这条路：.txt 在白名单内，上传换 fileId
  await page.setInputFiles('.ci-file', { name: 'note.txt', mimeType: 'text/plain', buffer: Buffer.from('hello') })
  await page.waitForTimeout(700)
  const fileChip = await page.evaluate(() => {
    const chip = document.querySelector('.cchip-file')
    return {
      n: document.querySelectorAll('.cchip-file').length,
      name: (document.querySelector('.cchip-file .cchip-name') || {}).textContent || '',
      sub: (document.querySelector('.cchip-file .cchip-sub') || {}).textContent || '',
      err: chip ? chip.classList.contains('err') : false
    }
  })
  check(fileChip.n === 1 && fileChip.name === 'note.txt', '选中附件进入待发送区（文件名可见）', JSON.stringify(fileChip))
  check(!fileChip.err && /B|KB|MB/.test(fileChip.sub), '附件上传完成后显示体积（不是停在「上传中…」）', fileChip.sub)

  // 移除入口：发错了要能撤回（发送前）
  await page.click('.cchip-img .cchip-del')
  await page.waitForTimeout(200)
  const afterRemove = await page.evaluate(() => document.querySelectorAll('.cchip-img').length)
  check(afterRemove === 0, '待发送图片可移除', `n=${afterRemove}`)

  // 再挂一张图后发送，断言载荷形状：images 是 data URL 数组、attachments 只带 fileId（+显示用元信息）
  await page.evaluate(async () => {
    const c = document.createElement('canvas')
    c.width = 8; c.height = 8
    c.getContext('2d').fillRect(0, 0, 8, 8)
    const blob = await new Promise(r => c.toBlob(r, 'image/png'))
    const dt = new DataTransfer()
    dt.items.add(new File([blob], 'shot2.png', { type: 'image/png' }))
    document.querySelector('.composer-edit').dispatchEvent(
      new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  })
  await page.waitForTimeout(500)
  await typeDraft(page, '这张图里是什么')
  await page.click('.ci-send')
  await page.waitForTimeout(1600)
  const sent = cap.chatBodies[cap.chatBodies.length - 1] || {}
  check(Array.isArray(sent.images) && sent.images.length === 1 && String(sent.images[0]).startsWith('data:image/'),
    '发送载荷带上了图片（data URL 数组）', `images=${(sent.images || []).length}`)
  check(Array.isArray(sent.attachments) && sent.attachments.length === 1
        && sent.attachments[0].fileId === 'f-mock-1' && sent.attachments[0].name === 'note.txt',
    '发送载荷附带 fileId（服务端按访客目录复核归属）', JSON.stringify(sent.attachments))
  check(cap.uploads.length === 1 && /^[a-zA-Z0-9-]{8,64}$/.test(cap.uploads[0].visitorId),
    '上传打在游客通道上并带合法访客标识（按访客隔离落盘的前提）', JSON.stringify(cap.uploads))

  // 提问气泡要能看见自己发了什么（图 + 附件），否则游客发完图只看到一句文字
  const echo = await page.evaluate(() => ({
    imgs: document.querySelectorAll('.msg-imgs .msg-img').length,
    atts: [...document.querySelectorAll('.msg-files .msg-file-name')].map(e => e.textContent.trim()),
    chipLeft: document.querySelectorAll('.cchip').length
  }))
  check(echo.imgs === 1, '提问气泡里能看到自己发的图', `n=${echo.imgs}`)
  check(echo.atts.includes('note.txt'), '提问气泡里能看到自己发的附件名', JSON.stringify(echo.atts))
  check(echo.chipLeft === 0, '发送后待发送区已清空（否则会以为发了两遍）', `n=${echo.chipLeft}`)

  // 降级提示：后端一直在 done 里下发 degradations，分享页此前整个丢掉
  const deg = await page.evaluate(() => ({
    n: document.querySelectorAll('.degradation-bar').length,
    txt: (document.querySelector('.degradation-bar') || {}).textContent || ''
  }))
  check(deg.n === 1 && deg.txt.includes('备用模型'), '降级提示已渲染（模型回退/工作流不收图片这类原因不再静默）', deg.txt.trim())

  // 回答动作行：复制 / 有帮助 / 没帮助 / 重新生成
  const acts = await page.evaluate(() => ({
    n: document.querySelectorAll('.fb-row .app-icon-btn').length,
    labels: [...document.querySelectorAll('.fb-row .app-icon-btn')].map(e => e.getAttribute('title') || '')
  }))
  check(acts.n === 4, '回答下方出现四个动作（复制/有帮助/没帮助/重新生成）', JSON.stringify(acts.labels))

  // 评价：一击提交，服务端要能收到 messageId + rating + visitorId 三样
  // 复制先桩掉剪贴板（无头 + 非安全上下文下 navigator.clipboard 根本不存在），
  // 否则"点了没报错"什么也证明不了——要断的是复制到的到底是哪段文字
  await page.evaluate(() => {
    window.__copied = null
    try {
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: t => { window.__copied = t; return Promise.resolve() } },
        configurable: true
      })
    } catch (e) { window.__copied = 'DEFINE_FAILED' }
  })
  await page.click('.fb-row .app-icon-btn:nth-child(1)')
  await page.waitForTimeout(400)
  const copied = await page.evaluate(() => window.__copied)
  check(copied === '根据文档，答案是 5。', '整段复制拿到的就是回答正文', JSON.stringify(copied))
  await page.click('.fb-row .app-icon-btn:nth-child(2)')
  await page.waitForTimeout(700)
  const fbk = cap.feedbacks[cap.feedbacks.length - 1] || {}
  check(fbk.messageId === 'm-ai-1' && fbk.rating === 1 && /^[a-zA-Z0-9-]{8,64}$/.test(String(fbk.visitorId || '')),
    '评价打到服务端且带上落库消息 ID（游客反馈的唯一凭据）', JSON.stringify(fbk))
  const fbState = await page.evaluate(() => {
    const a = document.querySelectorAll('.fb-row .app-icon-btn')[1]
    return { on: a.classList.contains('fb-active'), disabled: a.disabled }
  })
  check(fbState.on && fbState.disabled, '评价后按钮置为已评态并禁用（同一条不重复评）', JSON.stringify(fbState))

  // 重新生成：不带重复落库、带被替换的回答 ID（后者是归属校验的落点）
  await page.click('.fb-row .app-icon-btn:nth-child(4)')
  await page.waitForTimeout(1600)
  const regen = cap.chatBodies[cap.chatBodies.length - 1] || {}
  check(regen.regenerate === true && regen.replaceMessageId === 'm-ai-1',
    '重新生成带 regenerate + 被替换回答 ID（不重复落库、旧回答可被替换）', JSON.stringify({ r: regen.regenerate, id: regen.replaceMessageId }))
  check(regen.message === '这张图里是什么', '重新生成复用原问题原文', String(regen.message))
  const rowsAfterRegen = await page.evaluate(() => document.querySelectorAll('.row').length)
  check(rowsAfterRegen === 2, '重新生成在原气泡上重跑（不新增一条回答气泡）', `rows=${rowsAfterRegen}`)

  // 评价状态回填：刷新后已评过的按钮必须还是锁定态（否则游客以为反馈丢了、会重复点）
  await page.evaluate(() => { try { localStorage.setItem('share_session_tok-2', 's-share-fb') } catch (e) { /* ignore */ } })
  await page.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1100)
  const fbRestored = await page.evaluate(() => {
    const acts = [...document.querySelectorAll('.fb-row .app-icon-btn')]
    const like = acts[1]   // 次第：复制 / 有帮助 / 没帮助 /（重新生成）——图标按钮靠位置认，不再靠文案
    const dislike = acts[2]
    return {
      likeOn: like ? like.classList.contains('fb-active') : null,
      dislikeOn: dislike ? dislike.classList.contains('fb-active') : null,
      dislikeDisabled: dislike ? dislike.disabled : null
    }
  })
  check(fbRestored.dislikeOn === true && fbRestored.likeOn === false && fbRestored.dislikeDisabled === true,
    '刷新后既有评价锁定态被恢复（后端回填 fb，游客不会重复点）', JSON.stringify(fbRestored))

  // 未开放多模态的分享：入口必须根本不出现，而不是点了才报错
  await page.goto(ORIGIN + '/s/tok-5', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const plain = await page.evaluate(() => ({
    attach: !!document.querySelector('.ci-attach'),
    ph: (document.querySelector('.composer-edit') || {}).getAttribute?.('data-ph') || '',
    hero: (document.querySelector('.sc-welcome p') || {}).textContent || ''
  }))
  check(!plain.attach, '未开放多模态的分享：附件入口不出现（能力没开就不摆一个点了报错的按钮）')
  check(!plain.ph.includes('图片'), '未开放多模态时占位文案不提图片/文件', plain.ph)
  check(!plain.hero.includes('图片'), '未开放多模态时空态引导不提拖入图片/文件', plain.hero.slice(0, 30))

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

  // 进行中（一帧没到、正文未到）：整轮的进行中提示**全页只能有一条**，说的是「正在溯源」。
  // tok-3 的 mock 什么都不发，响应体结束后前端 pump() 等不到 done ⇒ 页面稳定停在「进行中」，
  // 可重复采样，不怕时序抖动。
  await page.goto(ORIGIN + '/s/tok-3', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await typeDraft(page, '你好')
  await page.click('.ci-send')
  await page.waitForTimeout(2600)
  const during = await page.evaluate(() => {
    const tip = document.querySelector('.turn-bar')
    const r = tip ? tip.getBoundingClientRect() : { width: 0, height: 0 }
    return {
      // 提示行唯一（不多处渲染同一条）
      tipN: document.querySelectorAll('.turn-bar').length,
      legacyN: document.querySelectorAll('.tm-typing, .tm-stage').length,
      tipText: tip ? tip.textContent.trim() : '',
      // 单行：高度不应超过 ~34px（折成两行会翻倍）
      tipH: Math.round(r.height), tipW: Math.round(r.width),
      // 生成中不该有气泡壳：没有边框/底色，也就没有"框中框"
      // 「无气泡壳」= 元素可存在（内容容器），但不得有边框/底色/内边距。
      // 断元素个数会把"改成 transparent 但保留容器"这种正确实现误判成 bug。
      bubbleStyle: (() => {
        const e = document.querySelector('.bubble.ai')
        if (!e) return null
        const cs = getComputedStyle(e)
        return { bg: cs.backgroundColor, border: cs.borderTopWidth, pad: cs.paddingTop }
      })(),
      rows: document.querySelectorAll('.row').length,
      sending: !!document.querySelector('.ci-send.stop')
    }
  })
  check(during.sending, '流未结束时发送键处于「停止」态（确在生成中）')
  check(during.tipN === 1 && during.legacyN === 0,
    '进行中：提示只出现一次（阶段提示行已退役）', `turn=${during.tipN} legacy=${during.legacyN} "${during.tipText}"`)
  check(/^正在溯源，用时 \d+ 秒 …$/.test(during.tipText),
    '进行中：说的是「正在溯源」并带秒数', `"${during.tipText}"`)
  // 秒针真的在走：分享页没有问答引擎去开那张全局表，靠进行中这一行自己挂表。
  // 单次采样证不了「在走」——隔两秒再读一次，数字必须变大。
  const secOf = t => { const m = /用时 (\d+) 秒/.exec(t); return m ? Number(m[1]) : -1 }
  await page.waitForTimeout(2200)
  const again = await page.evaluate(() => {
    const e = document.querySelector('.turn-bar')
    return e ? e.textContent.trim() : ''
  })
  check(secOf(again) > secOf(during.tipText),
    '进行中：分享页的秒针在自己走（两次采样数字变大）', `"${during.tipText}" → "${again}"`)
  check(during.tipH > 0 && during.tipH <= 34,
    '进行中：提示是单行（不被挤成两行）', `h=${during.tipH} "${during.tipText}"`)
  // AI 侧**本就没有气泡壳**（对齐 ChatPage .bubble.ai：transparent + padding:0）。
  // 断"背景透明 + 无边框 + 无内边距"三项，而不是断元素个数——元素是内容容器，本就该存在。
  const bs = during.bubbleStyle
  check(bs && bs.bg === 'rgba(0, 0, 0, 0)' && parseFloat(bs.border) === 0 && parseFloat(bs.pad) === 0,
    '进行中：AI 侧无气泡壳（背景透明/无边框/无内边距）', JSON.stringify(bs))
  check(during.rows === 2, '进行中：一轮问答仍只渲染 2 条（无多余的状态气泡）', `rows=${during.rows}`)

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
    const ta = document.querySelector('.composer-edit')
    const th = document.querySelector('.msg-block')
    const hero = document.querySelector('.sc-welcome')
    const inp = document.querySelector('.ci-box')
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
