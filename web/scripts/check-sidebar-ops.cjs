// 真浏览器验证：侧栏「形态操作」的悬浮显隐 + 长按菜单区进编辑态（AppLayout.vue）
// 验的是**用户能不能用**，不是「代码在不在」：
//   1. 展开态默认隐身（opacity 0 且不占 logo 行排版）；鼠标移到 **logo 行**才浮现
//      （无边框无底色）、离开即隐；编辑态常显（pinned）——鼠标一移开就消失的工具条等于点不到「保存」；
//   2. 长按菜单区 0.5s 进编辑态，且**松手不触发那一项原本的跳转**（吞 click）；
//      普通单击仍照旧跳转（这条是回归：长按判定绝不能吃掉正常点击）；
//   3. 折叠态长按先把侧栏展开再进编辑态（56px 图标条里放不下编辑行）。
// 默认桌面视口 1280×800 + 鼠标（悬浮形态只在 hover:hover/pointer:fine 下生效）。
//
// 前置：先构建产物到 web/dist
//   npm run build
// 依赖：playwright-core（不装进项目，临时用）：
//   mkdir -p /tmp/wq-verify && cd /tmp/wq-verify && npm i playwright-core
//   NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-sidebar-ops.cjs
// 无 playwright-core / 无浏览器时会给出提示并跳过（exit 0），不阻塞其它校验。
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过真浏览器验证')
  console.log('      安装：cd /tmp/wq-verify && npm i playwright-core')
  console.log('      运行：NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-sidebar-ops.cjs')
  process.exit(0)
}
const path = require('path')
const fs = require('fs')

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
if (!fs.existsSync(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE + '（可用 WQ_BROWSER 指定）'); process.exit(0) }
const DIST_DIR = path.join(__dirname, '..', 'dist')
const ORIGIN = 'http://wq.local'
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' }

// 菜单树：够渲染出「新建对话 + 若干可点项 + 一个分组标题」即可（分组标题用于验证长按覆盖面）
const MENUS = [
  { id: 'm-chat', name: '对话', icon: 'MessageOutlined', path: '/chat', renderAs: 'sidebar' },
  { id: 'm-agents', name: '智能体', icon: 'RobotOutlined', path: '/agents', renderAs: 'sidebar' },
  { id: 'm-kb', name: '知识库', icon: 'DatabaseOutlined', path: '/knowledge', renderAs: 'sidebar' },
  { id: 'm-artifacts', name: '我的产物', icon: 'FolderOutlined', path: '/artifacts', renderAs: 'sidebar' },
  { id: 'g-admin', name: '管理', renderAs: 'group' },
  { id: 'm-members', name: '成员管理', icon: 'TeamOutlined', path: '/members', renderAs: 'sidebar' }
]

function serveStatic (page) {
  return page.route('**/*', route => {
    const u = new URL(route.request().url())
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data }) })
    if (u.pathname === '/api/ai/auth/me') {
      return json({ user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: MENUS })
    }
    // 编辑态载入的布局全集（含隐藏项）：结构与后端 menu/my-layout 一致
    if (u.pathname === '/api/ai/menu/my-layout') return json({ menus: MENUS, hidden: [], order: [] })
    if (u.pathname === '/api/ai/session/list') return json({ items: [], nextCursor: 0, hasMore: false, total: 0 })
    if (u.pathname === '/api/ai/notification/unread-count') return json({ count: 0 })
    if (u.pathname === '/api/ai/config/public') return json({ upload: { maxFileSize: 1, allowedExts: [] } })
    if (u.host !== 'wq.local') return route.abort()      // 其余外部请求掐断
    let p = decodeURIComponent(u.pathname)
    let f = path.join(DIST_DIR, p)
    if (p === '/' || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST_DIR, 'index.html')
    try {
      route.fulfill({ status: 200, body: fs.readFileSync(f),
        headers: { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' } })
    } catch (e) { route.abort() }
  })
}

let bad = 0
const check = (ok, label, detail = '') => {
  if (!ok) bad++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  →  ' + detail : ''}`)
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

/** 操作组当前形态：opacity / position / 是否浮在 logo 行尾（且无边框底色）/ 两个按钮的可见性 */
const opsState = page => page.evaluate(() => {
  const el = document.querySelector('.side-logo-ops')
  if (!el) return null
  const cs = getComputedStyle(el)
  const r = el.getBoundingClientRect()
  const logo = document.querySelector('.side-logo').getBoundingClientRect()
  const name = document.querySelector('.logo-name')
  const nr = name ? name.getBoundingClientRect() : null
  const fold = el.querySelector('.fold')
  const edit = el.querySelector('button:not(.fold)')
  const fcs = fold ? getComputedStyle(fold) : null
  return {
    opacity: parseFloat(cs.opacity), position: cs.position, pointerEvents: cs.pointerEvents,
    border: cs.borderTopWidth + ' ' + cs.borderTopStyle,
    background: cs.backgroundColor, shadow: cs.boxShadow,
    right: Math.round(logo.right - r.right), top: Math.round(r.top - logo.top),
    width: Math.round(r.width), buttons: el.querySelectorAll('button').length,
    hasFold: !!fold, hasEdit: !!edit,
    foldVisibility: fcs ? fcs.visibility : '', foldPointer: fcs ? fcs.pointerEvents : '',
    // 编辑按钮左缘：进/出编辑态不该挪位（挪位就等于给「点别处」送了一个退出热区）
    editX: edit ? Math.round(edit.getBoundingClientRect().left) : null,
    foldBox: fold ? { x: Math.round(fold.getBoundingClientRect().left + fold.getBoundingClientRect().width / 2),
                      y: Math.round(fold.getBoundingClientRect().top + fold.getBoundingClientRect().height / 2) } : null,
    // 与品牌名是否重叠（浮出的图标不该压住「问渠」）
    overlapName: nr ? !(r.left >= nr.right || r.right <= nr.left) : false
  }
})
const navState = page => page.evaluate(() => ({
  url: location.pathname + location.search,
  editRows: document.querySelectorAll('.nav-edit-row').length,
  editBar: !!document.querySelector('.nav-edit-bar'),
  sideW: Math.round(document.querySelector('.side').getBoundingClientRect().width)
}))
;(async () => {
  const browser = await chromium.launch({ executablePath: EDGE, headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 })
  const page = await ctx.newPage()
  await serveStatic(page)
  const errors = []
  page.on('pageerror', e => errors.push(String(e.message)))
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

  const mq = await page.evaluate(() => ({
    hover: matchMedia('(hover: hover)').matches,
    fine: matchMedia('(pointer: fine)').matches,
    coarse: matchMedia('(any-pointer: coarse)').matches
  })).catch(() => null)
  await page.goto(ORIGIN + '/login', { waitUntil: 'networkidle' })
  const media = mq || await page.evaluate(() => ({
    hover: matchMedia('(hover: hover)').matches,
    fine: matchMedia('(pointer: fine)').matches,
    coarse: matchMedia('(any-pointer: coarse)').matches
  }))
  console.log(`环境：hover=${media.hover} pointer-fine=${media.fine} any-coarse=${media.coarse}`)
  if (!media.hover || !media.fine || media.coarse) {
    console.log('SKIP  本环境不是「纯鼠标」媒体特征，悬浮形态不适用（触屏走恒显分支）')
    await browser.close()
    process.exit(0)
  }

  // 注入令牌让守卫放行（假令牌即可：真实鉴权在后端，这里只查本地是否存在）
  await page.evaluate(() => { localStorage.setItem('ai_token', 'mock-token-for-verify') })
  await page.goto(ORIGIN + '/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)

  // ---- ① 默认隐身 + 不占位 ----
  const hidden = await opsState(page)
  check(!!hidden && hidden.buttons === 2, '形态操作组存在且含 编辑 + 折叠 两个按钮',
    hidden ? `buttons=${hidden.buttons}` : '未找到 .side-logo-ops')
  check(hidden && hidden.opacity === 0, '默认不可见（opacity 0）', `opacity=${hidden && hidden.opacity}`)
  check(hidden && hidden.position === 'absolute', '悬浮态脱离文档流（absolute）', `position=${hidden && hidden.position}`)
  check(hidden && hidden.pointerEvents === 'none', '隐身时不吸指针（不会误点）', `pointer-events=${hidden && hidden.pointerEvents}`)
  check(hidden && Math.abs(hidden.right) <= 1 && Math.abs(hidden.top) <= 1, '浮出位置贴 logo 行右上角',
    hidden ? `right=${hidden.right} top=${hidden.top}` : '')
  check(hidden && !hidden.overlapName, '浮层与品牌名不重叠（展开态 logo 行平时只有「问渠」）')

  // ---- ② 触发区只在 logo 行：扫过会话列表不浮出，移到 logo 行才浮出 ----
  await page.mouse.move(120, 500)          // 侧栏深处（会话列表区）
  await page.waitForTimeout(400)
  const deep = await opsState(page)
  check(deep && deep.opacity === 0, '鼠标扫过会话列表时不浮出（触发区不是整列侧栏）',
    `opacity=${deep && deep.opacity}`)

  /** 把鼠标移到 logo 行（行的左半：压着品牌标，不碰图标本身） */
  const hoverLogoRow = async () => {
    const b = await page.locator('.side-logo').boundingBox()
    await page.mouse.move(b.x + 20, b.y + b.height / 2)
    await page.waitForTimeout(320)
  }
  await hoverLogoRow()
  const shown = await opsState(page)
  check(shown && shown.opacity === 1, '鼠标移到 logo 行后浮出', `opacity=${shown && shown.opacity}`)
  check(shown && shown.pointerEvents === 'auto', '浮出后可点（pointer-events auto）')
  check(shown && shown.border.startsWith('0px') && shown.shadow === 'none' &&
        /rgba\(0, 0, 0, 0\)|transparent/.test(shown.background),
    '浮出的是无边框、无底色、无阴影的裸图标',
    shown ? `border=${shown.border} bg=${shown.background} shadow=${shown.shadow}` : '')
  const editX0 = shown.editX          // 非编辑态下编辑按钮的位置，用来验「进编辑态不挪位」

  await page.mouse.move(900, 400)          // 主内容区
  await page.waitForTimeout(400)
  const rehidden = await opsState(page)
  check(rehidden && rehidden.opacity === 0, '鼠标离开 logo 行后隐去', `opacity=${rehidden && rehidden.opacity}`)

  // ---- ③ 入口仍可用：悬浮 → 点编辑 → 进编辑态；编辑态常显 ----
  await hoverLogoRow()
  await page.click('.side-logo-ops button:not(.fold)')
  await page.waitForTimeout(600)
  let st = await navState(page)
  check(st.editRows >= 5 && st.editBar, '点悬浮的编辑按钮能进编辑态',
    `rows=${st.editRows} bar=${st.editBar}`)
  await page.mouse.move(900, 400)          // 鼠标移开
  await page.waitForTimeout(400)
  const pinned = await opsState(page)
  check(pinned && pinned.opacity === 1, '编辑态常显（鼠标移开也不消失，否则点不到「保存」）',
    `opacity=${pinned && pinned.opacity}`)
  check(pinned && pinned.hasEdit && pinned.foldVisibility === 'hidden' && pinned.foldPointer === 'none',
    '编辑态里折叠图标隐身且不吸指针（按了等于取消编辑，留着只会误点）',
    pinned ? `fold=${pinned.foldVisibility}/${pinned.foldPointer}` : '')
  check(pinned && pinned.editX === editX0, '进编辑态「完成编辑」不挪位（不补进折叠原来的格子）',
    pinned ? `editX=${pinned.editX}（原 ${editX0}）` : '')
  // 最容易被误点的那一格：折叠图标原来的位置。编辑态下这里必须是死区，点了不能退出编辑态
  await page.mouse.click(pinned.foldBox.x, pinned.foldBox.y)
  await page.waitForTimeout(450)
  let afterSlot = await navState(page)
  check(afterSlot.editRows >= 5, '编辑态下点折叠图标原来的格子不会退出编辑（死区，不是热区）',
    `rows=${afterSlot.editRows}`)
  afterSlot = await opsState(page)
  check(afterSlot.opacity === 1, '点过那一格后「完成编辑」仍在', `opacity=${afterSlot.opacity}`)

  // 退出编辑态
  await hoverLogoRow()
  await page.click('.side-logo-ops button:not(.fold)')
  await page.waitForTimeout(400)
  const afterEdit = await opsState(page)
  check(afterEdit && afterEdit.hasFold && afterEdit.foldVisibility === 'visible', '退出编辑态后折叠图标回来',
    afterEdit ? `fold=${afterEdit.foldVisibility}` : '')
  check(afterEdit && afterEdit.editX === editX0, '退出编辑态编辑按钮回到原位', `editX=${afterEdit && afterEdit.editX}`)

  // 关键回归（2026-10-16 实测踩过）：点「✓」是鼠标点击，焦点会留在按钮身上。
  // 用 :focus-within 显形的话，退出编辑态后整组被这个焦点钉在屏幕上——
  // 用户看到的是「点了✓图标还常驻」，再去点别处才消失，像退出没生效。
  await page.mouse.move(900, 400)
  await page.waitForTimeout(450)
  const afterBlur = await opsState(page)
  check(afterBlur && afterBlur.opacity === 0, '点「✓」退出后鼠标移开，图标真的隐去（不被按钮焦点钉住）',
    afterBlur ? `opacity=${afterBlur.opacity}` : '')

  // 键盘通道不能被上一条误伤：Tab 走到按钮上时必须显形（看不见的按钮等于没入口）
  let kbShown = false
  await page.evaluate(() => { if (document.activeElement) document.activeElement.blur() })
  for (let i = 0; i < 30 && !kbShown; i++) {
    await page.keyboard.press('Tab')
    await page.waitForTimeout(240)      // 浮出有 .16s 过渡：取早了会读到 0.95，误判成没显形
    const s = await page.evaluate(() => {
      const ops = document.querySelector('.side-logo-ops')
      const btn = ops && ops.querySelector('button:not(.fold)')
      return { op: ops ? parseFloat(getComputedStyle(ops).opacity) : 0, onBtn: btn === document.activeElement }
    })
    if (s.onBtn && s.op > 0.5) kbShown = true
  }
  check(kbShown, '键盘 Tab 到按钮时浮出（鼠标点击不显形，但键盘仍要有入口）')
  await page.evaluate(() => { if (document.activeElement) document.activeElement.blur() })
  await page.waitForTimeout(300)
  st = await navState(page)
  check(st.editRows === 0 && !st.editBar, '再点一次退出编辑态', `rows=${st.editRows}`)

  // ---- ④ 长按菜单区 → 直接进编辑态，且不触发该项跳转 ----
  const before = (await navState(page)).url
  const item = await page.locator('.side-nav .nav-item', { hasText: '知识库' }).boundingBox()
  check(!!item, '菜单项「知识库」已渲染（长按目标存在）')
  await page.mouse.move(item.x + item.width / 2, item.y + item.height / 2)
  await page.mouse.down()
  await page.waitForTimeout(650)           // > 500ms 长按阈值
  await page.mouse.up()
  await page.waitForTimeout(500)
  st = await navState(page)
  check(st.editRows >= 5 && st.editBar, '长按菜单项 0.5s 进入编辑态', `rows=${st.editRows}`)
  check(st.url === before, '长按松手不跳转（吞掉那次 click）', `url=${st.url}（长按前 ${before}）`)

  // 长按「新建对话」同样只进编辑态，不会新开一轮对话
  await page.click('.side-logo-ops button:not(.fold)')       // 先退出编辑态
  await page.waitForTimeout(400)
  const nurl = (await navState(page)).url
  const plus = await page.locator('.side-nav .nav-item', { hasText: '新建对话' }).boundingBox()
  await page.mouse.move(plus.x + plus.width / 2, plus.y + plus.height / 2)
  await page.mouse.down()
  await page.waitForTimeout(650)
  await page.mouse.up()
  await page.waitForTimeout(500)
  st = await navState(page)
  check(st.editRows >= 5, '长按「新建对话」也进编辑态', `rows=${st.editRows}`)
  check(st.url === nurl, '长按「新建对话」不会顺手新开对话', `url=${st.url}`)

  // ---- ⑤ 回归：普通单击照旧跳转（长按判定不能吃掉正常点击）----
  await page.click('.side-logo-ops button:not(.fold)')       // 退出编辑态
  await page.waitForTimeout(400)
  await page.click('.side-nav .nav-item:has-text("知识库")')
  await page.waitForTimeout(700)
  st = await navState(page)
  check(st.url.startsWith('/knowledge'), '普通单击菜单项仍正常跳转', `url=${st.url}`)

  // 快速点击（<500ms）同理，不能被误判成长按
  await page.goto(ORIGIN + '/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(600)
  const ag = await page.locator('.side-nav .nav-item', { hasText: '智能体' }).boundingBox()
  await page.mouse.move(ag.x + ag.width / 2, ag.y + ag.height / 2)
  await page.mouse.down()
  await page.waitForTimeout(60)
  await page.mouse.up()
  await page.waitForTimeout(700)
  st = await navState(page)
  check(st.url.startsWith('/agents') && st.editRows === 0, '短按（60ms）不误判为长按：正常跳转且不进编辑态',
    `url=${st.url} rows=${st.editRows}`)

  // ---- ⑥ 折叠态长按：先展开再进编辑态 ----
  await page.goto(ORIGIN + '/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(600)
  await hoverLogoRow()
  await page.click('.side-logo-ops .fold')
  await page.waitForTimeout(500)
  st = await navState(page)
  check(st.sideW <= 60, '折叠按钮仍可用（侧栏收成图标条）', `width=${st.sideW}`)
  const icon = await page.locator('.side-nav .nav-item').first().boundingBox()
  await page.mouse.move(icon.x + icon.width / 2, icon.y + icon.height / 2)
  await page.mouse.down()
  await page.waitForTimeout(650)
  await page.mouse.up()
  await page.waitForTimeout(700)
  st = await navState(page)
  check(st.sideW >= 150 && st.editRows >= 5, '折叠态长按：自动展开并进编辑态',
    `width=${st.sideW} rows=${st.editRows}`)
  // 复原：退出编辑态
  await hoverLogoRow()
  await page.click('.side-logo-ops button:not(.fold)')
  await page.waitForTimeout(400)
  check((await navState(page)).editRows === 0, '折叠态长按进入后仍能正常退出编辑态')

  // ---- ⑦ 无脚本报错 ----
  check(errors.length === 0, '全程无页面脚本错误', errors.slice(0, 2).join(' | '))

  await browser.close()
  console.log(bad === 0 ? '\n全部通过' : `\n${bad} 项失败`)
  process.exit(bad === 0 ? 0 : 1)
})().catch(e => { console.error('异常：', e); process.exit(1) })
