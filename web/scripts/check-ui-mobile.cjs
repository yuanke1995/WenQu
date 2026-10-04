// 窄屏 UI 布局断言：验「PC UI 压缩到手机」这类症状是否复发。
// 与 check-browser.cjs 的分工：那个验「能不能用」，这个验「布局对不对」——
// 工具条是否单行、文字是否竖排、卡宽是否横滑、双顶栏是否消除、宽屏是否回归。
// 依赖 playwright-core（不装进项目）：
//   mkdir -p /tmp/wq-verify2 && cd /tmp/wq-verify2 && npm i playwright-core
//   npx vite build --outDir dist --emptyOutDir
//   NODE_PATH=/tmp/wq-verify2/node_modules node scripts/check-ui-mobile.cjs
'use strict'
// 窄屏 UI 布局断言（第二轮：PC UI 压缩到手机的症状）
// 验的是**具体布局指标**而非「元素在不在」：
//   双顶栏：窄屏 .chat-head 必须不渲染；顶栏与动作行不重叠
//   工具条：整条高度 ≈ 单行高（>60px 说明换行）；「思考」不竖排
//   模型名：compact 后宽度受限、走省略号
//   欢迎卡：横滑容器可滚、卡宽介于半屏与整屏之间、启用 x 轴 snap
//   宽屏回归：PC 的 chat-head / 网格卡 / 智能体名+箭头 全部保留
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过窄屏 UI 布局验证')
  console.log('      安装：cd /tmp/wq-verify2 && npm i playwright-core')
  process.exit(0)
}
const fs = require('fs')
const path = require('path')

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
const DIST_DIR = process.env.WQ_DIST || '/Users/yuki/IdeaProjects/wenqu/web/dist'
const ORIGIN = 'http://h5.local'
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png' }

let bad = 0
const check = (ok, label, detail = '') => { if (!ok) bad++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  →  ' + detail : ''}`) }

function serve (page) {
  return page.route('**/*', r => {
    const u = new URL(r.request().url())
    // mock 管理员身份：否则 ensureAuth 失败会把 ai_role 覆写成 user，守卫先弹回 /chat
    if (u.pathname === '/api/ai/auth/me') {
      return r.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data: { user: 'a', username: 'a', role: 'admin', admin: true, menus: [] } }) })
    }
    if (u.host !== 'h5.local') return r.abort()
    let p = decodeURIComponent(u.pathname)
    let f = path.join(DIST_DIR, p)
    if (p === '/' || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST_DIR, 'index.html')
    r.fulfill({ status: 200, body: fs.readFileSync(f), headers: { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' } })
  })
}

;(async () => {
  const browser = await chromium.launch({ executablePath: EDGE, headless: true })
  const ctx = await browser.newContext({ viewport: { width: 375, height: 667 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
  const page = await ctx.newPage()
  await serve(page)
  const errs = []
  page.on('pageerror', e => errs.push(e.message))
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()) })

  await page.goto(ORIGIN + '/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.setItem('ai_token', 'mock'))
  await page.goto(ORIGIN + '/chat', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)

  // ---- ① 双顶栏 ----
  const bars = await page.evaluate(() => {
    const box = e => { const r = e.getBoundingClientRect(); return { h: Math.round(r.height), top: Math.round(r.top) } }
    const head = document.querySelector('.chat-head')
    const mh = document.querySelector('.m-chat-head')
    const top = document.querySelector('.m-topbar')
    return {
      pcHead: !!head,
      mobileHead: !!mh, mobileHeadBox: mh ? box(mh) : null,
      topbar: !!top, topbarBox: top ? box(top) : null
    }
  })
  check(!bars.pcHead, '窄屏不再渲染 PC 的 .chat-head（双顶栏根因）')
  check(bars.mobileHead, '窄屏渲染 MobileChatHead 动作行', bars.mobileHead ? `h=${bars.mobileHeadBox.h}` : '')
  if (bars.mobileHeadBox && bars.topbarBox) {
    check(bars.mobileHeadBox.top >= bars.topbarBox.top + bars.topbarBox.h - 1,
      '顶栏与动作行不重叠',
      `topbar[top=${bars.topbarBox.top},h=${bars.topbarBox.h}] actions[top=${bars.mobileHeadBox.top},h=${bars.mobileHeadBox.h}]`)
  }

  // ---- ② 工具条：不换行、不竖排 ----
  const bar = await page.evaluate(() => {
    const tb = document.querySelector('.input-toolbar')
    if (!tb) return null
    const cs = getComputedStyle(tb)
    const r = tb.getBoundingClientRect()
    const think = tb.querySelector('.think-entry')
    const txt = think ? think.querySelector('.think-entry-txt') : null
    const model = tb.querySelector('.ms-compact')
    return {
      h: Math.round(r.height), wrap: cs.flexWrap,
      hasAsMobile: tb.classList.contains('as-mobile'),
      // 内容是否真在 DOM 里（防「具名 slot 丢内容」那类空壳）
      innerTextLen: tb.innerText.replace(/\s+/g, '').length,
      childCount: tb.querySelectorAll('button, .ant-select').length,
      thinkW: think ? Math.round(think.getBoundingClientRect().width) : 0,
      thinkH: think ? Math.round(think.getBoundingClientRect().height) : 0,
      txtH: txt ? Math.round(txt.getBoundingClientRect().height) : 0,
      modelW: model ? Math.round(model.getBoundingClientRect().width) : 0
    }
  })
  check(!!bar, '工具条存在')
  if (bar) {
    check(bar.hasAsMobile, '窄屏工具条带 as-mobile 类')
    check(bar.wrap === 'nowrap', '工具条 flex-wrap:nowrap', `wrap=${bar.wrap}`)
    check(bar.h <= 44, '工具条为单行（未换行）', `h=${bar.h}`)
    // 关键回归：曾因具名 slot 包裹导致内容全被丢弃，编译与静态校验都测不出
    check(bar.childCount >= 2, '工具条内控件真实存在（防 slot 丢内容）', `children=${bar.childCount} textLen=${bar.innerTextLen}`)
    if (bar.thinkW) {
      check(bar.thinkW >= 34, '「思考」按钮宽度 ≥34px', `w=${bar.thinkW}`)
      check(bar.thinkH <= 40 && bar.thinkH >= 30, '「思考」按钮单行高度', `h=${bar.thinkH}`)
      check(bar.txtH <= 24, '「思考」文字未竖排', `textH=${bar.txtH}`)
    } else console.log('SKIP  未渲染「思考」按钮（依赖模型就绪状态）')
    if (bar.modelW) check(bar.modelW <= 120, '模型选择器已收窄（compact）', `w=${bar.modelW}`)
    else console.log('SKIP  未渲染模型选择器（依赖模型列表）')
  }

  // ---- ③ 智能体胶囊窄屏只留头像 ----
  const pill = await page.evaluate(() => {
    const p = document.querySelector('.agent-pill.as-icon')
    if (!p) return null
    const r = p.getBoundingClientRect()
    return { w: Math.round(r.width), h: Math.round(r.height), hasName: !!p.querySelector('.agent-pill-name') }
  })
  if (!pill) console.log('SKIP  未渲染 as-icon 智能体胶囊（依赖智能体列表/登录态）')
  else {
    check(pill.w <= 40, '智能体胶囊窄屏只留头像（宽 ≤40）', `w=${pill.w}`)
    check(!pill.hasName, '窄屏不显示智能体名称')
    check(pill.h >= 32, '智能体胶囊触控热区 ≥32', `h=${pill.h}`)
  }

  // ---- ④ 空态：横滑示例卡或引导卡（两者都合法，取决于 setupGuide.loaded）----
  const samples = await page.evaluate(() => {
    const m = document.querySelector('.m-samples')
    const g = document.querySelector('.welcome-samples')
    const guide = document.querySelector('.welcome-guide')
    if (!m) return { hasMobile: false, hasGrid: !!g, hasGuide: !!guide }
    const cs = getComputedStyle(m)
    const card = m.querySelector('.ms-card')
    return {
      hasMobile: true, hasGrid: !!g, hasGuide: !!guide,
      display: cs.display,
      scrollable: m.scrollWidth > m.clientWidth + 4,
      cardW: card ? Math.round(card.getBoundingClientRect().width) : 0,
      viewW: m.clientWidth, snap: cs.scrollSnapType
    }
  })
  check(!samples.hasGrid, '窄屏不渲染 PC 网格示例卡')
  check(samples.hasMobile || samples.hasGuide, '窄屏空态为横滑示例卡或引导卡', `mobile=${samples.hasMobile} guide=${samples.hasGuide}`)
  if (samples.hasMobile) {
    check(samples.display === 'flex', '示例卡为 flex 横排', `display=${samples.display}`)
    check(samples.scrollable, '示例卡可横向滚动')
    check(samples.cardW > samples.viewW * 0.5 && samples.cardW < samples.viewW, '卡宽介于半屏与整屏之间（横滑而非塞满）', `cardW=${samples.cardW} viewW=${samples.viewW}`)
    check(String(samples.snap).includes('x'), '启用 x 轴 scroll-snap', `snap=${samples.snap}`)
  }

  // ---- ⑤ placeholder 无键盘提示 ----
  const ph = await page.evaluate(() => { const t = document.querySelector('.input-area'); return t ? (t.getAttribute('placeholder') || '') : '' })
  check(ph.length > 0, 'placeholder 非空')
  check(!/Enter|Shift/.test(ph), '窄屏 placeholder 不含 Enter/Shift 键提示', `"${ph}"`)

  // ---- ⑥ 无横向溢出 ----
  const ov = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }))
  check(ov.s <= ov.c + 1, '对话页无横向溢出', `scrollW=${ov.s} clientW=${ov.c}`)

  // ---- ⑦ 宽屏回归 ----
  const wide = await ctx.newPage()
  await serve(wide)
  await wide.goto(ORIGIN + '/login', { waitUntil: 'domcontentloaded' })
  await wide.evaluate(() => localStorage.setItem('ai_token', 'mock'))
  await wide.setViewportSize({ width: 1280, height: 800 })
  await wide.goto(ORIGIN + '/chat', { waitUntil: 'domcontentloaded' })
  await wide.waitForTimeout(2000)
  const w = await wide.evaluate(() => {
    const tb = document.querySelector('.input-toolbar')
    const p = document.querySelector('.agent-pill')
    const name = p ? p.querySelector('.agent-pill-name') : null
    return {
      hasPcHead: !!document.querySelector('.chat-head'),
      hasMobileHead: !!document.querySelector('.m-chat-head'),
      hasAsMobile: tb ? tb.classList.contains('as-mobile') : false,
      hasMobileSamples: !!document.querySelector('.m-samples'),
      hasGrid: !!document.querySelector('.welcome-samples'),
      hasGuide: !!document.querySelector('.welcome-guide'),
      pillNameVisible: name ? getComputedStyle(name).display !== 'none' : false,
      pillHasCaret: !!(p && p.querySelector('.agent-pill-caret')),
      barWrap: tb ? getComputedStyle(tb).flexWrap : ''
    }
  })
  check(w.hasPcHead, '宽屏渲染 PC 的 .chat-head')
  check(!w.hasMobileHead, '宽屏不渲染窄屏动作行')
  check(!w.hasAsMobile, '宽屏工具条不带 as-mobile 类')
  check(!w.hasMobileSamples, '宽屏不渲染横滑示例卡')
  check(w.hasGrid || w.hasGuide, '宽屏空态为网格示例卡或引导卡')
  check(w.pillNameVisible && w.pillHasCaret, '宽屏智能体胶囊显示名称与箭头（PC 未变）')
  // 注意：flex 容器的 flex-wrap 计算值默认就是 nowrap（基础规则未声明），
  // 所以宽屏看到 nowrap 是正常的，不能用它判断 as-mobile 是否泄漏——
  // 判据是 as-mobile 类本身（上面已断言），不是 wrap 值
  console.log(`INFO  宽屏工具条 flex-wrap=${w.barWrap}（flex 容器默认值，非泄漏）`)

  const real = errs.filter(e => !/mock|401|Failed to load resource|ERR_/i.test(e))
  check(real.length === 0, '无 JS 运行时错误', real.slice(0, 3).join(' | '))

  await browser.close()
  console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
  process.exit(bad ? 1 : 0)
})().catch(e => { console.error('异常：', e.message); process.exit(2) })
