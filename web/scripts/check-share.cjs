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

function serveStatic (page) {
  return page.route('**/*', route => {
    const u = new URL(route.request().url())
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data }) })
    // 免登录页不走 /auth/me；分享接口按 token 给 mock
    if (u.pathname === '/api/ai/share/session/tok-1') return json(SHARED)
    if (u.pathname === '/api/ai/share/tok-2/info') return json(AGENT)
    // 游客流式对话 mock（两个 token 各测一个场景）。
    // 存在意义是**抓阶段提示重复渲染**：真实 SSE 会连发多条 stage，而模板曾同时渲染
    // 「气泡内 m.stage」与「底部 sending && lastAiStage」两个同源节点 ⇒ 同一句话出现两次。
    //   tok-3 = 只发 stage 不发 token/done：响应体结束后前端 pump() 停在 done 分支，
    //           sending 仍是 true ⇒ 页面冻结在「进行中」，正好采样重复渲染。
    //   tok-2 = 完整流：验证结束后阶段提示已撤掉、行数不重复。
    if (u.pathname === '/api/ai/share/tok-3/chat' || u.pathname === '/api/ai/share/tok-2/chat') {
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
  await serveStatic(page)
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
    const heroRect = document.querySelector('.sc-hero') ? document.querySelector('.sc-hero').getBoundingClientRect() : null
    return {
      name: (document.querySelector('.sc-name') || {}).textContent || '',
      hero: !!document.querySelector('.sc-hero'),
      // 空态必须垂直居中：此前顶在上方，下面留一大片空白，页面显得空且廉价
      heroTopGap: heroRect ? Math.round(heroRect.top - listRect.top) : -1,
      heroBotGap: heroRect && listRect.height ? Math.round(listRect.top + listRect.height - heroRect.bottom) : -1,
      headAvatarEmoji: (document.querySelector('.sc-avatar') || {}).textContent || '',
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
  // 空态居中：上下留白差不超过 60px 才算真的居中（此前 top≈24 / bot≈700）
  check(sc.heroTopGap > 0 && sc.heroBotGap > 0 && Math.abs(sc.heroTopGap - sc.heroBotGap) < 60,
    '空态在消息区垂直居中（非顶靠）', `top=${sc.heroTopGap} bottom=${sc.heroBotGap}`)
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
      bubbleN: document.querySelectorAll('.sc-bubble.ai').length,
      rows: document.querySelectorAll('.sc-row').length,
      sending: !!document.querySelector('.sc-send.stop')
    }
  })
  check(during.sending, '流未结束时发送键处于「停止」态（确在生成中）')
  check(during.tipN === 1 && during.stageN === 0,
    '进行中：提示只出现一次（不多处渲染同一条）', `typing=${during.tipN} stage=${during.stageN} "${during.tipText}"`)
  check(during.tipH > 0 && during.tipH <= 30,
    '进行中：提示是单行（不被挤成两行）', `h=${during.tipH} "${during.tipText}"`)
  check(during.bubbleN === 0,
    '进行中：不画空气泡壳（此前是「空气泡里再套虚线小框」的框中框）', `bubble=${during.bubbleN}`)
  check(during.rows === 2, '进行中：一轮问答仍只渲染 2 条（无多余的阶段气泡）', `rows=${during.rows}`)

  // ---- 桌面回归：1280 下分享页保持居中栏与 PC 字号（窄屏补丁不得外溢到桌面）----
  const dpage = await ctx.newPage()
  await dpage.setViewportSize({ width: 1280, height: 800 })
  await serveStatic(dpage)
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
  await dpage.goto(ORIGIN + '/s/tok-2', { waitUntil: 'networkidle' })
  await dpage.waitForTimeout(900)
  const dsc = await dpage.evaluate(() => {
    const ta = document.querySelector('.sc-textarea')
    const th = document.querySelector('.sc-thread') || document.querySelector('.sc-hero')
    const hero = document.querySelector('.sc-hero')
    const inp = document.querySelector('.sc-input')
    return {
      taFont: ta ? getComputedStyle(ta).fontSize : '',
      // 桌面宽度下线程/输入区必须限宽居中，否则 1280 宽的气泡行长失控。
      // 空态量 maxWidth 而非实际宽：hero 是 flex 居中子项，按内容收缩是 flex 的正常行为，
      // 真正要卡的是「它最多能有多宽」——CSS 错写成 max-width 缺失时才会真的铺满。
      heroMax: hero ? Math.round(parseFloat(getComputedStyle(hero).maxWidth) || 9999) : 0,
      threadW: th ? Math.round(th.getBoundingClientRect().width) : 0,
      inputW: inp ? Math.round(inp.getBoundingClientRect().width) : 0,
      vw: window.innerWidth
    }
  })
  check(parseFloat(dsc.taFont) < 16, '桌面输入框保持 14px（16px 规则只在 ≤768 补丁内）', `font=${dsc.taFont}`)
  check(dsc.heroMax > 0 && dsc.heroMax <= 640, '桌面空态限宽（≤640px，不铺满视口）', `max=${dsc.heroMax}/${dsc.vw}`)
  check(dsc.inputW > 0 && dsc.inputW <= 840, '桌面输入区限宽居中（≤840px）', `w=${dsc.inputW}`)

  const real = errors.filter(e => !/Failed to load resource|ERR_FAILED|401/i.test(e))
  check(real.length === 0, '无 JS 运行时错误', real.slice(0, 3).join(' | '))

  await browser.close()
  console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
  process.exit(bad ? 1 : 0)
})().catch(e => { console.error('验证脚本异常：', e.message); process.exit(2) })
