// 用真实数据量三个页签的滚动高度（maintenance 49 字段 / 17 任务）。
// 先跑 scripts/gen-sched-mock.cjs 生成 /tmp/s.json、/tmp/t.json。
// 判据：.set-content 的 scrollHeight 与视口比 —— 超出即整页要滚。
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) { console.log('SKIP  未安装 playwright-core'); process.exit(0) }
const fs = require('fs')
const BASE = process.env.WQ_BASE || 'http://localhost:5800'
const json = d => ({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(d) })
if (!fs.existsSync('/tmp/s.json') || !fs.existsSync('/tmp/t.json')) {
  console.log('SKIP  缺 /tmp/s.json 或 /tmp/t.json，先跑 scripts/gen-sched-mock.cjs'); process.exit(0)
}
const SCHEMA = JSON.parse(fs.readFileSync('/tmp/s.json', 'utf8'))
const TASKS = JSON.parse(fs.readFileSync('/tmp/t.json', 'utf8'))
const VH = 900

;(async () => {
  const b = await chromium.launch({ channel: 'msedge', headless: true })
  const c = await b.newContext({ viewport: { width: 1600, height: VH } })
  await c.addInitScript(() => {
    localStorage.setItem('ai_token', 'v')
    localStorage.setItem('ai_user', JSON.stringify({ user: 'admin', role: 'superadmin', admin: true }))
  })
  const p = await c.newPage()
  p.on('pageerror', e => console.log('PAGEERR', e.message.slice(0, 200)))
  await p.route('**/*', r => {
    const pa = new URL(r.request().url()).pathname
    if (pa.endsWith('/auth/me')) return r.fulfill(json({ success: true, data: { user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: [] } }))
    if (pa.endsWith('/config/schema')) return r.fulfill(json({ success: true, data: SCHEMA }))
    if (pa.endsWith('/config')) return r.fulfill(json({ success: true, data: {} }))
    if (pa.endsWith('/schedule/tasks')) return r.fulfill(json({ success: true, data: TASKS }))
    if (pa.includes('/api/ai/')) return r.fulfill(json({ success: true, data: [] }))
    return r.continue()
  })
  await p.goto(BASE + '/settings', { waitUntil: 'networkidle' })
  await p.waitForTimeout(1500)
  if (await p.locator('.adv-toggle .ant-switch').count()) { await p.locator('.adv-toggle .ant-switch').first().click(); await p.waitForTimeout(400) }
  await p.locator('.set-nav-item', { hasText: '定时任务' }).first().click()
  await p.waitForTimeout(1200)

  let bad = 0
  const meas = async label => {
    // 真实判据：滚动容器里还能不能滚。scrollHeight vs clientHeight，
    // 而不是 scrollHeight vs 视口——.set-content 的高度本身不受视口约束，比视口没有意义
    const m = await p.evaluate(() => { const e=document.querySelector('.set-content'); return { sh: e.scrollHeight, ch: e.clientHeight, over: e.scrollHeight - e.clientHeight } })
    const sh = m.sh; const over = m.over
    console.log(`${label.padEnd(24)} 内容高 ${String(sh).padStart(5)}px  整页需滚 ${String(over).padStart(4)}px  ${over === 0 ? '✅ 一屏' : '⚠️'}`)
    return over
  }
  console.log(`视口 ${VH}px ｜ 真实数据：${SCHEMA.fields.length} 字段 / ${TASKS.length} 任务\n`)
  bad += await meas('参数配置（默认收起）')
  await p.locator('.sched-tabs .ant-tabs-tab', { hasText: '运行状态' }).click()
  await p.waitForTimeout(1200)
  bad += await meas('运行状态（17 行表格）')
  // 表格是否内部滚动。真正滚动的容器是 .ant-table-body（只有设了 scroll.y 才生成），
  // 不是外层 .ant-table——取错元素会得"不可滚"的假结论。
  const bodyScroll = await p.evaluate(() => {
    for (const s of ['.sched-table .ant-table-body', '.sched-table .ant-table']) {
      const el = document.querySelector(s)
      if (el && el.scrollHeight > el.clientHeight + 1) return { sel: s, client: el.clientHeight, scroll: el.scrollHeight }
    }
    return null
  })
  console.log('  表格内部滚动:', bodyScroll ? `${bodyScroll.sel} ${bodyScroll.scroll}>${bodyScroll.client} ✅` : '未发生滚动 ⚠️')
  if (!bodyScroll) bad += 100

  // 防"假优化"：页面不滚了，但表格内容被父层 overflow 裁掉、既看不见也滚不到。
  // 严格判据 = 表格可滚 **且** 滚到底后末行落在表格可视区内。
  const vis = await p.evaluate(() => {
    const wrap = document.querySelector('.sched-table .ant-table-body')
    const rows = [...document.querySelectorAll('.ant-table-row')]
    if (!rows.length || !wrap) return { ok: false, why: 'no rows/body' }
    wrap.scrollTop = wrap.scrollHeight
    return { rows: rows.length, tableH: wrap.clientHeight, contentH: wrap.scrollHeight }
  })
  await p.waitForTimeout(300)
  const after = await p.evaluate(() => {
    const rows = [...document.querySelectorAll('.ant-table-row')]
    const wrap = document.querySelector('.sched-table .ant-table-body')
    const r = rows[rows.length - 1].getBoundingClientRect()
    const b = wrap.getBoundingClientRect()
    return {
      atBottom: Math.abs(wrap.scrollTop + wrap.clientHeight - wrap.scrollHeight) < 4,
      lastRow: rows[rows.length - 1].innerText.replace(/\s+/g, ' ').trim().slice(0, 24),
      withinTable: r.bottom <= b.bottom + 4 && r.top >= b.top - 4
    }
  })
  console.log(`  滚到底: ${vis.rows} 行，末行「${after.lastRow}」${after.atBottom && after.withinTable ? '完整可见 ✅' : '不可达 ⚠️'}`)
  if (!after.atBottom || !after.withinTable) bad += 100
  // 展开参数最多的行
  let best = 0, bestN = -1
  TASKS.forEach((t, i) => { if (t.relatedKeys.length > bestN) { bestN = t.relatedKeys.length; best = i } })
  const rowSel = `.ant-table-row:nth-of-type(${best + 1})`
  await p.locator(`${rowSel} .ant-table-row-expand-icon`).click()
  await p.waitForTimeout(700)
  console.log(`  （展开「${TASKS[best].name}」${bestN} 项参数）`)
  bad += await meas('运行状态（展开一行）')
  await p.locator(`${rowSel} .ant-table-row-expand-icon`).click()
  await p.waitForTimeout(400)

  // 多分辨率：表格高度是按容器真实 clientHeight 算的，不同视口下可见行数应自适应，
  // 且**任何分辨率下整页都不应出现滚动**（曾出现 800px 视口下固定 581px 尚可、
  // 1440px 下白留大片空白）。每次都要先切回运行状态页——表格不在日志页 DOM 里。
  console.log('  --- 多分辨率 ---')
  for (const h of [800, 900, 1080, 1440]) {
    await p.setViewportSize({ width: 1600, height: h })
    await p.waitForTimeout(500)
    await p.locator('.sched-tabs .ant-tabs-tab', { hasText: '运行状态' }).click()
    await p.waitForTimeout(700)
    const r = await p.evaluate(() => {
      const body = document.querySelector('.sched-table .ant-table-body')
      const sc = document.querySelector('.set-content')
      const rowH = document.querySelector('.ant-table-row')?.getBoundingClientRect().height || 58
      const rows = [...document.querySelectorAll('.ant-table-row')]
      return {
        bodyH: body ? body.clientHeight : 0,
        over: sc ? sc.scrollHeight - sc.clientHeight : 0,
        visible: body ? Math.floor(body.clientHeight / rowH) : 0,
        total: rows.length
      }
    })
    const okH = r.over === 0 && r.bodyH > 0
    console.log(`    视口 ${h}: 表格体 ${r.bodyH}px ≈ ${r.visible}/${r.total} 行可见, 整页溢出 ${r.over}px ${okH ? '✅' : '⚠️'}`)
    if (!okH) bad += 100
  }
  await p.setViewportSize({ width: 1600, height: VH })
  await p.waitForTimeout(400)
  await p.locator('.sched-tabs .ant-tabs-tab', { hasText: '执行日志' }).click()
  await p.waitForTimeout(1000)
  bad += await meas('执行日志')
  await b.close()
  console.log(bad === 0 ? '\n三个页签均无需整页滚动' : `\n仍有 ${bad}px 整页滚动`)
})()
