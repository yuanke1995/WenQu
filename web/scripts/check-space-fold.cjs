// 真浏览器验证：侧栏「工作区」区的两级折叠 + 表头/工作区行操作图标的 hover 显隐（AppLayout.vue）
// 验的是**用户能不能用**，不是「代码在不在」：
//   1. 有鼠标时，表头右侧那一组（折叠全部 / 新建工作区）与工作区行右侧的 ⋯/＋ 平时隐身，
//      鼠标移到**所在那一行**才浮现、离开即隐（触发区不是整列侧栏）。
//   2. 点「工作区 (N)」这一行 = 整段收起/展开（连工作区名一起藏掉）；箭头常驻——
//      这一行能不能点靠的就是它告知，跟着 hover 才出现就等于没有提示；
//      点这一行里的 ＋ 只开「新建工作区」弹窗，不会顺手把整段展开（.stop 必须生效，
//      否则一次点击做两件事）。
//   3. 「折叠全部工作区」只藏会话、工作区名仍在；图标与 title 随当前态翻转，
//      混合态（折了一个、另一个还开着）一律给「展开全部」。
//   4. 两种折叠都跨刷新记住；搜索态一律强制展开且不给「折叠全部」按钮
//      （搜到却看不见是死胡同）。
// 默认桌面视口 1280×800 + 鼠标（悬浮形态只在 hover:hover / pointer:fine 下生效）。
//
// 前置：先构建产物到 web/dist
//   npm run build
// 依赖：playwright-core（不装进仓库依赖，临时用）：
//   mkdir -p /tmp/wq-verify && cd /tmp/wq-verify && npm i playwright-core
//   NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-space-fold.cjs
// 无 playwright-core / 无浏览器时会给出提示并跳过（exit 0），不阻塞其它校验。
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过真浏览器验证')
  console.log('      安装：cd /tmp/wq-verify && npm i playwright-core')
  console.log('      运行：NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-space-fold.cjs')
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

const MENUS = [
  { id: 'm-chat', name: '对话', icon: 'MessageOutlined', path: '/chat', renderAs: 'sidebar' },
  { id: 'm-kb', name: '知识库', icon: 'DatabaseOutlined', path: '/knowledge', renderAs: 'sidebar' }
]
// 两个真实工作区 + 一段「不属于任何工作区」的会话。每个工作区都给够 2 条，
// 这样「整段收起」和「只收起会话」两种状态在 DOM 上一眼能分开。
const PROJECTS = [
  { id: 'p-a', name: 'p-a', displayName: '问渠·AI', status: 'active', isImplicit: false },
  { id: 'p-b', name: 'p-b', displayName: '问渠·智能体平台', status: 'active', isImplicit: false }
]
const SESS = {
  'p-a': [['a1', '工作区A的会话一'], ['a2', '工作区A的会话二']],
  'p-b': [['b1', '工作区B的会话一'], ['b2', '工作区B的会话二'], ['b3', '工作区B的会话三']],
  '__nospace__': [['n1', '没挂工作区的会话']]
}
const sessList = pid => (SESS[pid] || []).map(([id, title], i) => ({
  id, title, messageCount: 3, isPinned: 0, isFavorite: 0, shared: false,
  updateTime: `2026-10-${17 - i}T10:00:00`
}))

function serveStatic (page) {
  return page.route('**/*', route => {
    const u = new URL(route.request().url())
    const json = data => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ success: true, data }) })
    const p = u.pathname
    if (p === '/api/ai/auth/me') return json({ user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: MENUS })
    if (p === '/api/ai/project/list') return json(PROJECTS)
    if (p === '/api/ai/sessions') return json({ items: sessList(u.searchParams.get('projectId') || ''), nextCursor: '', hasMore: false, total: (SESS[u.searchParams.get('projectId')] || []).length })
    if (p === '/api/ai/scheduled/list') return json([])
    if (p === '/api/ai/notification/unread-count') return json({ count: 0 })
    if (p === '/api/ai/config/public') return json({ upload: { maxFileSize: 1, allowedExts: [] } })
    if (p.startsWith('/api/ai/')) return json({})           // 其余接口给空壳，别让页面报网络错
    if (u.host !== 'wq.local') return route.abort()          // 外部请求掐断
    let f = path.join(DIST_DIR, decodeURIComponent(p))
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

/** 「工作区」表头 + 其下工作区行的当前形态 */
const read = page => page.evaluate(() => {
  const head = [...document.querySelectorAll('.side-sec-head')]
    .find(h => h.querySelector('.side-sec-title')?.textContent.trim() === '工作区')
  if (!head) return null
  const sec = head.closest('.side-sec')
  const spaces = [...sec.querySelectorAll('.space:not(.bare)')]
  const ops = head.querySelector('.side-sec-ops')
  const foldBtn = ops ? [...ops.querySelectorAll('button')]
    .find(b => b.querySelector('.anticon-arrows-alt, .anticon-shrink')) : null
  const addBtn = ops ? [...ops.querySelectorAll('button')]
    .find(b => b.querySelector('.anticon-plus')) : null
  const caret = head.querySelector('.sec-caret')
  const op = spaces[0]?.querySelector('.space-op')
  const add = spaces[0]?.querySelector('.space-add')
  const o = el => (el ? parseFloat(getComputedStyle(el).opacity) : null)
  return {
    count: head.querySelector('.side-sec-count')?.textContent.trim(),
    cursor: getComputedStyle(head).cursor,
    opsOpacity: o(ops), opsBtns: ops ? ops.querySelectorAll('button').length : 0,
    foldTitle: foldBtn ? foldBtn.title : '', foldIcon: foldBtn ? (foldBtn.querySelector('.anticon-arrows-alt') ? 'expand' : 'collapse') : 'none',
    addTitle: addBtn ? addBtn.title : '',
    caretOpacity: o(caret), caretOpen: caret ? caret.classList.contains('open') : null,
    spaceNames: spaces.map(s => s.querySelector('.space-name')?.textContent.trim()),
    rowsPerSpace: spaces.map(s => s.querySelectorAll('.sess-item').length),
    spaceOpOpacity: o(op), spaceAddOpacity: o(add),
    // 两本账分开读：单个工作区的折叠集合 / 整段折叠的分区 key 列表
    foldedJson: localStorage.getItem('app_space_collapsed') || '[]',
    foldedSecJson: localStorage.getItem('app_side_section_folded') || '[]'
  }
})
/** 悬浮目标：表头行的左半（压着「工作区」两个字，不碰右侧图标） */
const headBox = page => page.evaluate(() => {
  const head = [...document.querySelectorAll('.side-sec-head')]
    .find(h => h.querySelector('.side-sec-title')?.textContent.trim() === '工作区')
  const r = head.getBoundingClientRect()
  return { x: r.left + 24, y: r.top + r.height / 2 }
})
const spaceHeadBox = page => page.evaluate(() => {
  const r = document.querySelector('.space:not(.bare) .space-head').getBoundingClientRect()
  return { x: r.left + 24, y: r.top + r.height / 2 }
})
;(async () => {
  const browser = await chromium.launch({ executablePath: EDGE, headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 })
  const page = await ctx.newPage()
  await serveStatic(page)
  const errors = []
  page.on('pageerror', e => errors.push(String(e.message)))
  page.on('console', m => {
    if (m.type() !== 'error') return
    const t = m.text()
    if (/failed to fetch|json|network|404|abort/i.test(t)) return   // 桩接口的噪音不算
    errors.push(t)
  })

  const media = await page.evaluate(() => ({
    hover: matchMedia('(hover: hover)').matches,
    fine: matchMedia('(pointer: fine)').matches,
    coarse: matchMedia('(any-pointer: coarse)').matches
  })).catch(() => null)
  await page.goto(ORIGIN + '/login', { waitUntil: 'networkidle' })
  const mq = media || await page.evaluate(() => ({
    hover: matchMedia('(hover: hover)').matches,
    fine: matchMedia('(pointer: fine)').matches,
    coarse: matchMedia('(any-pointer: coarse)').matches
  }))
  console.log(`环境：hover=${mq.hover} pointer-fine=${mq.fine} any-coarse=${mq.coarse}`)
  if (!mq.hover || !mq.fine || mq.coarse) {
    console.log('SKIP  本环境不是「纯鼠标」媒体特征，悬浮形态不适用（触屏走常显分支）')
    await browser.close()
    process.exit(0)
  }

  await page.evaluate(() => {
    localStorage.clear()
    localStorage.setItem('ai_token', 'mock-token-for-verify')
  })
  await page.goto(ORIGIN + '/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)

  // ---- ① 默认态：图标隐身、箭头常驻、两个工作区都开着 ----
  let st = await read(page)
  check(!!st && st.count === '(2)', '「工作区 (2)」表头已渲染', st ? `count=${st.count}` : '未找到表头')
  check(st && st.rowsPerSpace.join(',') === '2,3', '两个工作区默认各铺出自己的会话', st && st.rowsPerSpace.join(','))
  check(st && st.opsOpacity === 0, '表头右侧图标默认隐身', `opacity=${st && st.opsOpacity}`)
  check(st && st.spaceOpOpacity === 0 && st.spaceAddOpacity === 0,
    '工作区行右侧 ⋯/＋ 默认隐身', st && `⋯=${st.spaceOpOpacity} ＋=${st.spaceAddOpacity}`)
  check(st && st.caretOpacity === 1 && st.caretOpen === true, '折叠箭头常驻且当前为展开态（向下）',
    st && `opacity=${st.caretOpacity} open=${st.caretOpen}`)
  check(st && st.cursor === 'pointer', '表头这一行给出可点光标', st && `cursor=${st.cursor}`)

  // ---- ② 触发区只在所在那一行：扫过会话列表不浮出 ----
  await page.mouse.move(120, 560)
  await page.waitForTimeout(400)
  let deep = await read(page)
  check(deep && deep.opsOpacity === 0 && deep.spaceOpOpacity === 0,
    '鼠标停在会话列表里时两组图标都不浮出', st && `ops=${deep.opsOpacity} space=${deep.spaceOpOpacity}`)

  // ---- ③ hover 表头行 → 图标浮出；离开 → 隐去 ----
  let b = await headBox(page)
  await page.mouse.move(b.x, b.y)
  await page.waitForTimeout(360)
  let shown = await read(page)
  check(shown && shown.opsOpacity === 1, '鼠标移到「工作区」这一行，右侧图标浮出', `opacity=${shown && shown.opsOpacity}`)
  check(shown && shown.opsBtns === 2 && shown.foldIcon === 'collapse' && shown.foldTitle === '折叠全部工作区',
    '浮出的是「折叠全部工作区」+「新建工作区」两个（全展开态给折叠）',
    shown && `btns=${shown.opsBtns} icon=${shown.foldIcon} title=${shown.foldTitle}`)
  check(shown && shown.addTitle === '新建工作区', '＋ 仍是新建工作区', shown && `title=${shown.addTitle}`)
  await page.mouse.move(900, 400)
  await page.waitForTimeout(400)
  check((await read(page)).opsOpacity === 0, '鼠标离开这一行后图标隐去')

  // ---- ④ hover 工作区行 → ⋯/＋ 浮出 ----
  b = await spaceHeadBox(page)
  await page.mouse.move(b.x, b.y)
  await page.waitForTimeout(360)
  st = await read(page)
  check(st && st.spaceOpOpacity === 1 && st.spaceAddOpacity === 1,
    '鼠标移到工作区名那一行，⋯ 与 ＋ 浮出', st && `⋯=${st.spaceOpOpacity} ＋=${st.spaceAddOpacity}`)
  await page.mouse.move(900, 400)
  await page.waitForTimeout(400)
  st = await read(page)
  check(st && st.spaceOpOpacity === 0, '鼠标离开后 ⋯/＋ 隐去（只提亮改成隐身，回归点）', `⋯=${st.spaceOpOpacity}`)

  // ---- ⑤ 折叠全部工作区：只藏会话，工作区名还在；图标翻转 ----
  b = await headBox(page)
  await page.mouse.move(b.x, b.y)
  await page.waitForTimeout(360)
  await page.click('.side-sec-ops .anticon-arrows-alt, .side-sec-ops .anticon-shrink')
  await page.waitForTimeout(450)
  st = await read(page)
  check(st && st.spaceNames.length === 2 && st.rowsPerSpace.join(',') === '0,0',
    '点「折叠全部工作区」：工作区名都还在，下面的会话全收起', st && `${JSON.stringify(st.spaceNames)} rows=${st.rowsPerSpace}`)
  check(st && st.foldIcon === 'expand' && st.foldTitle === '展开全部工作区',
    '图标与提示翻成「展开全部工作区」', st && `icon=${st.foldIcon} title=${st.foldTitle}`)
  check(st && st.caretOpen === true, '整段没被误伤（箭头仍是展开态）', st && `open=${st.caretOpen}`)
  check(st && JSON.parse(st.foldedJson).length === 2,
    '折叠状态写进 localStorage', st && st.foldedJson)

  // ---- ⑥ 混合态：手动展开一个工作区 → 表头给「展开全部」 ----
  await page.evaluate(() => {
    const h = [...document.querySelectorAll('.space:not(.bare) .space-head')][0]
    h.click()
  })
  await page.waitForTimeout(450)
  st = await read(page)
  check(st && st.rowsPerSpace.join(',') === '2,0', '点单个工作区名只展开它自己', st && `rows=${st.rowsPerSpace}`)
  check(st && st.foldIcon === 'expand' && st.foldTitle === '展开全部工作区',
    '混合态一律给「展开全部工作区」', st && `icon=${st.foldIcon} title=${st.foldTitle}`)
  await page.click('.side-sec-ops .anticon-arrows-alt')
  await page.waitForTimeout(450)
  st = await read(page)
  check(st && st.rowsPerSpace.join(',') === '2,3' && st.foldIcon === 'collapse',
    '点「展开全部工作区」把两个工作区都铺回来', st && `rows=${st.rowsPerSpace} icon=${st.foldIcon}`)

  // ---- ⑦ 整段折叠：点「工作区 (N)」这一行 ----
  b = await headBox(page)
  await page.mouse.move(b.x, b.y)
  await page.waitForTimeout(360)
  await page.mouse.down(); await page.mouse.up()
  await page.waitForTimeout(450)
  st = await read(page)
  check(st && st.spaceNames.length === 0, '点表头这一行：整段收起，连工作区名一起藏掉', st && `names=${JSON.stringify(st.spaceNames)}`)
  check(st && st.caretOpen === false && st.caretOpacity === 1, '箭头转回 > 且仍常驻', st && `open=${st.caretOpen} opacity=${st.caretOpacity}`)
  check(st && st.foldIcon === 'none', '整段收起时不给「折叠全部工作区」（那时要的是展开这一段）', st && `icon=${st.foldIcon}`)
  const solo = await page.evaluate(() => {
    const head = [...document.querySelectorAll('.side-sec-head')]
      .find(h => h.querySelector('.side-sec-title')?.textContent.trim() === '会话')
    const sec = head?.closest('.side-sec')
    return { rows: sec ? sec.querySelectorAll('.sess-item').length : -1, open: head?.querySelector('.sec-caret')?.classList.contains('open') }
  })
  check(solo.rows === 1 && solo.open === true, '收起「工作区」不牵连「会话」两段各记各的账',
    `rows=${solo.rows} open=${solo.open}`)
  check(st && JSON.parse(st.foldedJson).length === 0,
    '整段折叠不写单个工作区的折叠状态（两件事各记各的账）', st && st.foldedJson)

  // ---- ⑧ 收起态点 ＋：只开弹窗，不顺手展开整段（.stop 回归） ----
  b = await headBox(page)
  await page.mouse.move(b.x, b.y)
  await page.waitForTimeout(360)
  st = await read(page)
  check(st && st.opsOpacity === 1 && st.opsBtns === 1, '收起态浮出的是单个 ＋', st && `opacity=${st.opsOpacity} btns=${st.opsBtns}`)
  await page.click('.side-sec-ops .anticon-plus')
  await page.waitForTimeout(450)
  const modal = await page.evaluate(() => {
    const t = document.querySelector('.ant-modal-title')
    return { title: t ? t.textContent.trim() : '', names: document.querySelectorAll('.space:not(.bare)').length }
  })
  check(modal.title === '新建工作区', '点 ＋ 打开「新建工作区」弹窗', modal.title)
  check(modal.names === 0, '点 ＋ 没有顺手把整段展开（事件不冒泡到表头）', `names=${modal.names}`)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  // ---- ⑨ 跨刷新记住：整段收起 + 单个工作区折叠都各自持久化 ----
  // 当前是「整段收起、两个工作区都开着」→ 先展开整段、只折 p-b、再收起整段，
  // 刷新后应看到「整段仍收起」且「展开整段后只有 p-b 折着」。
  await page.evaluate(() => {
    const head = [...document.querySelectorAll('.side-sec-head')]
      .find(h => h.querySelector('.side-sec-title')?.textContent.trim() === '工作区')
    head.click()
  })
  await page.waitForTimeout(450)
  await page.evaluate(() => { document.querySelectorAll('.space:not(.bare) .space-head')[1].click() })
  await page.waitForTimeout(450)
  const persisted = await page.evaluate(() => localStorage.getItem('app_space_collapsed'))
  await page.evaluate(() => {
    const head = [...document.querySelectorAll('.side-sec-head')]
      .find(h => h.querySelector('.side-sec-title')?.textContent.trim() === '工作区')
    head.click()
  })
  await page.waitForTimeout(450)
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  st = await read(page)
  check(st && st.spaceNames.length === 0 && JSON.parse(st.foldedSecJson).includes('spaces'),
    '刷新后整段仍是收起', st && `names=${JSON.stringify(st.spaceNames)} secs=${st.foldedSecJson}`)
  check(st && st.rowsPerSpace.length === 0, '整段收起时不渲染工作区行')
  check(persisted === JSON.stringify(['p-b']), '单个工作区的折叠各自记住', persisted)
  await page.evaluate(() => {                       // 展开整段，验证记住的是「p-b 折着」
    const head = [...document.querySelectorAll('.side-sec-head')]
      .find(h => h.querySelector('.side-sec-title')?.textContent.trim() === '工作区')
    head.click()
  })
  await page.waitForTimeout(450)
  st = await read(page)
  check(st && st.rowsPerSpace.join(',') === '2,0', '展开整段后，之前折着的工作区仍折着', st && `rows=${st.rowsPerSpace}`)

  // ---- ⑩ 搜索态：一律强制展开，且不给「折叠全部」 ----
  await page.fill('.sess-search', '会话')
  await page.waitForTimeout(900)
  st = await read(page)
  check(st && st.spaceNames.length === 2 && st.rowsPerSpace.join(',') === '2,3',
    '搜索态把整段和工作区都强制展开（搜到却看不见是死胡同）', st && `names=${st.spaceNames.length} rows=${st.rowsPerSpace}`)
  check(st && st.foldIcon === 'none', '搜索态不给「折叠全部工作区」按钮', st && `icon=${st.foldIcon}`)
  await page.click('.sess-search-clear')
  await page.waitForTimeout(900)
  st = await read(page)
  check(st && st.rowsPerSpace.join(',') === '2,0', '清空搜索后回到记住的折叠状态', st && `rows=${st.rowsPerSpace}`)

  // ---- ⑪ 无脚本报错 ----
  check(errors.length === 0, '全程无页面脚本错误', errors.slice(0, 3).join(' | '))

  await browser.close()
  console.log(bad === 0 ? '\n全部通过' : `\n${bad} 项失败`)
  process.exit(bad === 0 ? 0 : 1)
})().catch(e => { console.error('异常：', e); process.exit(1) })
