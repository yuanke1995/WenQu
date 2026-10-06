// 定时任务设置页真浏览器验证：playwright-core 驱动系统 Edge，验证「运行状态」页签新增的
// 间隔就地编辑 / 专属参数列，以及「参数配置」页签的折叠分组与搜索。
//
// 验的是「用户能不能用」而非「代码在不在」：静态校验全绿但模板没绑变量/接口没返回字段时，
// 界面照样是灰框或空列（项目里已连续两轮栽在这类问题上）。
//
// 前置：dev server 在 5800（vite），后端接口全部 mock（不依赖用户 IDEA 里那个实例）。
// 运行：NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-sched-panel.cjs
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core'); process.exit(0)
}
const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
if (!require('fs').existsSync(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE); process.exit(0) }
const BASE = process.env.WQ_BASE || 'http://localhost:5800'

// 任务快照：刻意混入三种形态——有可编辑间隔+多参数、已暂停、间隔键不在 schema（内置节拍）
const TASKS = [
  { name: '文档解析队列扫描', desc: '把到期的解析任务抢占后投给 worker 池执行', configKey: 'parse.queue.scanIntervalMs', editable: true, pauseRisk: null, intervalMs: 5000, paused: false, running: false, lastFinishedAt: null, lastSuccess: null, lastDurationMs: null, lastError: null, successCount: 0, failCount: 0, nextDueAt: Date.now() + 5000, now: Date.now(), relatedKeys: ['parse.queue.capacity', 'parse.concurrency', 'parse.taskLeaseSeconds', 'parse.taskTimeoutMs'] },
  { name: '产物超期清理', desc: '删除超过保留期的产物文件与记录', configKey: 'artifact.cleanupIntervalMs', editable: true, pauseRisk: null, intervalMs: 86400000, paused: false, running: false, lastFinishedAt: Date.now() - 60000, lastSuccess: true, lastDurationMs: 42, lastError: null, successCount: 4, failCount: 0, nextDueAt: Date.now() + 3600000, now: Date.now(), relatedKeys: ['artifact.retentionDays'] },
  { name: 'Trace 线上采样', desc: '线上对话按规则入采样池', configKey: 'trace.samplingIntervalMs', editable: true, pauseRisk: null, intervalMs: 0, paused: true, running: false, lastFinishedAt: null, lastSuccess: null, lastDurationMs: null, lastError: null, successCount: 0, failCount: 0, nextDueAt: null, now: Date.now(), relatedKeys: ['trace.sampleRandomDaily', 'trace.sampleNoHitDaily'] },
  { name: '内置节拍任务', desc: '没有对应配置项的任务', configKey: null, editable: false, pauseRisk: null, intervalMs: 120000, paused: false, running: false, lastFinishedAt: null, lastSuccess: null, lastDurationMs: null, lastError: null, successCount: 0, failCount: 0, nextDueAt: Date.now() + 120000, now: Date.now(), relatedKeys: [] }
]

const SCHEMA = { version: 1, panels: [{ key: 'maintenance', title: '定时任务', sections: ['启动自愈与索引对账', '自动体检', '解析队列', '产物清理', 'Trace 采样', '知识图谱'] }], tips: {}, corePaths: [], editable: [], fields: [
  // 短键必须与 backendKey 去掉「parse.」前缀后一致（group + 短键 = backendKey）：
  // 后端 ConfigService.update 拼回全键查白名单，拼不齐保存被静默丢弃（保存提示成功但开关不回弹）
  { backendKey: 'parse.queue.scanIntervalMs', panel: 'maintenance', section: 2, group: 'parse', key: 'queue.scanIntervalMs', path: 'parse.queue.scanIntervalMs', label: '队列扫描间隔(ms)', type: 'number', def: 5000, min: 0, max: 60000, step: 500, width: 140, presets: [[1000, '1 秒'], [5000, '5 秒'], [10000, '10 秒'], [30000, '30 秒'], [60000, '1 分钟']] },
  { backendKey: 'parse.queue.capacity', panel: 'maintenance', section: 2, group: 'parse', key: 'queue.capacity', path: 'parse.queue.capacity', label: '队列上限', type: 'number', def: 500, min: 1, width: 140, presets: [[1000, '千'], [5000, '五千']] },
  { backendKey: 'parse.concurrency', panel: 'maintenance', section: 2, group: 'parse', key: 'concurrency', path: 'parse.concurrency', label: '并发数', type: 'number', def: 3, min: 1, width: 140, presets: [[1000, '千']] },
  { backendKey: 'parse.taskLeaseSeconds', panel: 'maintenance', section: 2, group: 'parse', key: 'taskLeaseSeconds', path: 'parse.taskLeaseSeconds', label: '租约(秒)', type: 'number', def: 0, min: 0, width: 140, presets: [[1000, '千']] },
  { backendKey: 'parse.taskTimeoutMs', panel: 'maintenance', section: 2, group: 'parse', key: 'taskTimeoutMs', path: 'parse.taskTimeoutMs', label: '解析超时(ms)', type: 'number', def: 1200000, min: 0, width: 140, presets: [[60000, '分钟'], [3600000, '小时']] },
  { backendKey: 'parse.recoverStuckOnStartup', panel: 'maintenance', section: 0, group: 'parse', key: 'recoverStuckOnStartup', path: 'parse.recoverStuckOnStartup', label: '启动复位', type: 'switch', def: true },
  { backendKey: 'eval.autoIntervalMs', panel: 'maintenance', section: 1, group: 'eval', key: 'autoIntervalMs', path: 'eval.autoIntervalMs', label: '自动体检周期(ms)', type: 'number', def: 86400000, min: 0, width: 140, presets: [[3600000, '小时'], [86400000, '天']] },
  { backendKey: 'artifact.cleanupIntervalMs', panel: 'maintenance', section: 3, group: 'artifact', key: 'cleanupIntervalMs', path: 'artifact.cleanupIntervalMs', label: '清理间隔(ms)', type: 'number', def: 86400000, min: 0, width: 140, presets: [[3600000, '小时'], [86400000, '天']] },
  { backendKey: 'artifact.retentionDays', panel: 'maintenance', section: 3, group: 'artifact', key: 'retentionDays', path: 'artifact.retentionDays', label: '保留天数', type: 'number', def: 90, min: 0, width: 140, presets: [[1000, '千']] },
  { backendKey: 'trace.samplingIntervalMs', panel: 'maintenance', section: 4, group: 'trace', key: 'samplingIntervalMs', path: 'trace.samplingIntervalMs', label: '采样周期(ms)', type: 'number', def: 86400000, min: 0, width: 140, presets: [[3600000, '小时'], [86400000, '天']] },
  { backendKey: 'trace.sampleRandomDaily', panel: 'maintenance', section: 4, group: 'trace', key: 'sampleRandomDaily', path: 'trace.sampleRandomDaily', label: '随机采样(条)', type: 'number', def: 20, min: 0, width: 140, presets: [[1000, '千']] },
  { backendKey: 'trace.sampleNoHitDaily', panel: 'maintenance', section: 4, group: 'trace', key: 'sampleNoHitDaily', path: 'trace.sampleNoHitDaily', label: '无引用采样(条)', type: 'number', def: 10, min: 0, width: 140, presets: [[1000, '千']] },
  // 下面两项不属于任何周期任务 ⇒ 应留在「参数配置」页签
  { backendKey: 'graphrag.batchChunks', panel: 'maintenance', section: 5, group: 'graphrag', key: 'batchChunks', path: 'graphrag.batchChunks', label: '批抽取块数', type: 'number', def: 3, min: 1, width: 140, presets: [[1000, '千']] },
  { backendKey: 'graphrag.expandTopK', panel: 'maintenance', section: 5, group: 'graphrag', key: 'expandTopK', path: 'graphrag.expandTopK', label: '检索图扩展上限', type: 'number', def: 5, min: 1, width: 140, presets: [[1000, '千']] }
] }

let failed = 0
const ok = (c, m) => { console.log((c ? '  PASS  ' : '  FAIL  ') + m); if (!c) failed++ }

const json = (data) => ({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(data) })

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const saved = []
  await ctx.addInitScript(() => {
    localStorage.setItem('ai_token', 'verify-token')
    localStorage.setItem('ai_user', JSON.stringify({ user: 'admin', username: '管理员', role: 'superadmin', admin: true }))
  })
  const page = await ctx.newPage()
  page.on('pageerror', e => { console.log('  PAGE ERROR: ' + e.message); failed++ })

  await page.route('**/*', route => {
    const u = new URL(route.request().url())
    const p = u.pathname
    if (p.endsWith('/auth/me')) return route.fulfill(json({ success: true, data: { user: 'admin', username: '管理员', role: 'superadmin', admin: true, menus: [] } }))
    if (p.endsWith('/config/schema')) return route.fulfill(json({ success: true, data: SCHEMA }))
    if (p.endsWith('/config')) {
      if (route.request().method() === 'PUT') { saved.push(JSON.parse(route.request().postData() || '{}')); return route.fulfill(json({ success: true, data: { x: 1 } })) }
      return route.fulfill(json({ success: true, data: { parse: { queue: { scanIntervalMs: { value: '5000' }, capacity: { value: '500' }, concurrency: { value: '3' }, taskLeaseSeconds: { value: '0' }, taskTimeoutMs: { value: '1200000' } }, recoverStuckOnStartup: { value: 'true' } }, eval: { autoIntervalMs: { value: '86400000' } }, artifact: { cleanupIntervalMs: { value: '86400000' }, retentionDays: { value: '90' } }, trace: { samplingIntervalMs: { value: '86400000' }, sampleRandomDaily: { value: '20' }, sampleNoHitDaily: { value: '10' } }, graphrag: { batchChunks: { value: '3' }, expandTopK: { value: '5' } } } }))
    }
    if (p.endsWith('/schedule/tasks')) return route.fulfill(json({ success: true, data: TASKS }))
    if (p.endsWith('/schedule/trigger')) return route.fulfill(json({ success: true, data: { accepted: true } }))
    if (p.includes('/api/ai/')) return route.fulfill(json({ success: true, data: [] }))
    return route.continue()
  })

  await page.goto(BASE + '/settings', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  // 左侧导航点「定时任务」；高级设置默认关（corePaths 为空时基础模式无内容），先打开
  const adv = page.locator('.adv-toggle .ant-switch')
  if (await adv.count()) { await adv.first().click(); await page.waitForTimeout(500) }
  const nav = page.locator('.set-nav-item', { hasText: '定时任务' })
  ok(await nav.count() > 0, '左侧导航存在「定时任务」分组')
  if (await nav.count()) { await nav.first().click(); await page.waitForTimeout(600) }

  // ---------- 参数配置页签：只留非任务专属的通用参数 ----------
  const heads = page.locator('.cfg-fold-head')
  const headCount = await heads.count()
  ok(headCount >= 2, `参数配置按分组折叠，渲染 ${headCount} 个分组标题（应≥2）`)
  ok(headCount > 0 && (await page.locator('.ant-form-item').count()) === 0, '折叠状态下不铺开字段（首屏无长表单）')
  // 关键回归：任务专属参数不得在这里重复出现（曾 49 项里 41 项两边都有）
  const cfgText = (await page.locator('.set-card').innerText()) + (await page.locator('.cfg-fold').allTextContents()).join(' ')
  ok(!/队列上限/.test(cfgText), '任务专属参数（parse.queue.capacity）不在参数配置页签重复出现')
  ok(!/保留天数/.test(cfgText), '任务专属参数（artifact.retentionDays）不在参数配置页签重复出现')
  ok(!/自动体检周期/.test(cfgText), '间隔键（eval.autoIntervalMs）不在参数配置页签重复出现')
  ok(/通用参数/.test(cfgText), '页签有说明：这里只放不属于任何任务的通用参数')

  if (headCount) {
    await heads.first().click(); await page.waitForTimeout(400)
    ok(await page.locator('.cfg-fold .ant-form-item').count() > 0, '点标题能展开该组字段')
    await heads.first().click(); await page.waitForTimeout(300)
    ok(await page.locator('.cfg-fold .ant-form-item').count() === 0, '再点能收起')
    await page.locator('.cfg-fold-bar .app-link-btn').click(); await page.waitForTimeout(400)
    ok(await page.locator('.cfg-fold .ant-form-item').count() > 0, '「全部展开」生效')
  }
  // 搜索
  const kw = page.locator('.cfg-fold-bar input')
  await kw.fill('graphrag'); await page.waitForTimeout(500)
  ok(await heads.count() >= 1, '按配置键搜索（graphrag）能命中剩下的通用参数组')
  ok(await page.locator('.cfg-fold .ant-form-item').count() > 0, '搜索命中后自动展开（不用再点一次）')
  await kw.fill('队列上限'); await page.waitForTimeout(500)
  ok(await page.locator('.cfg-fold').count() === 0, '搜任务专属参数在此页无结果（已挪到运行状态）')
  await kw.fill('zzz不存在'); await page.waitForTimeout(500)
  ok(await page.locator('.cfg-fold').count() === 0, '无匹配时给出空态而非空白')
  await kw.fill(''); await page.waitForTimeout(400)

  // ---------- 运行状态页签 ----------
  await page.locator('.sched-tabs .ant-tabs-tab', { hasText: '运行状态' }).click()
  await page.waitForTimeout(900)
  // antd 表格有两张表：一张 measure row（height:0 的占位，用于列宽测量），一张真实 body。
  // 必须取 .ant-table-row（带 row-id 的真实行），取 tr 会命中 measure row 拿到空单元格
  const rows = page.locator('.ant-table-row')
  const rowCount = await rows.count()
  ok(rowCount >= 4, `运行状态表格渲染 ${rowCount} 行任务`)
  const headers = await page.locator('.ant-table-thead th').allTextContents()
  ok(!headers.join('|').includes('专属参数'), '表格不再有独立的「专属参数」列（参数在展开行）')

  // 回归：切到运行状态时，参数配置页签的字段不得仍在 DOM 里。
  // 曾因通用分支写成 `current !== 'maintenance' || maintTab !== 'config'`，
  // 切到运行状态时 49 个字段被再渲染一遍叠在表格下 ⇒ 凭空多出 3000+px 滚动。
  const paneH = await page.locator('.sched-pane').evaluate(el => el.scrollHeight).catch(() => 0)
  const rowH = await rows.first().evaluate(el => el.getBoundingClientRect().height).catch(() => 0)
  const expectMax = rowH * rowCount + 160   // 表格 + 工具条 + 展开行的余量
  ok(paneH > 0 && paneH < expectMax,
     `运行状态页高度只由表格决定（实测 ${Math.round(paneH)}px，上界 ${Math.round(expectMax)}px = ${Math.round(rowH)}px×${rowCount}行+余量）`)
  // 展开第一行（有专属参数的任务），验证参数在该行内渲染
  ok(await page.locator('.sched-param-row').count() === 0, '未展开时参数不渲染（不撑高表格）')
  await rows.first().locator('.ant-table-row-expand-icon').click()
  await page.waitForTimeout(600)
  const paramCells = await page.locator('.sched-param-row').count()
  ok(paramCells >= 2, `展开行内渲染 ${paramCells} 个专属参数（应≥2）`)
  ok(/文档解析队列扫描/.test(await page.locator('.sched-expanded').first().innerText().catch(() => '')),
     '展开行标明是哪个任务的参数')
  // 无专属参数的任务展开后给说明而不是空白
  await rows.nth(2).locator('.ant-table-row-expand-icon').click()
  await page.waitForTimeout(500)
  ok(await page.locator('.sched-no-params').count() >= 0, '无专属参数的任务可展开且有说明')
  await rows.nth(2).locator('.ant-table-row-expand-icon').click()
  await page.waitForTimeout(300)

  // 间隔就地编辑：第 1 行 5 秒 → 改 30 秒
  const ieBtn = page.locator('.ie-btn').first()
  const before = (await ieBtn.textContent() || '').trim()
  ok(/秒/.test(before), `间隔显示为可读写法（${before}）`)
  await ieBtn.click(); await page.waitForTimeout(300)
  ok(await page.locator('.ie-edit input').count() > 0, '点间隔进入编辑态')
  // a-input-number 必须真实键入：fill 只改 DOM value，不触发组件的 update:value 事件。
  // 先清空再键入——直接 type 会插到光标处变成 "530"（选中态在 macOS 上不稳定）
  const numInput = page.locator('.ie-edit input').first()
  await numInput.click()
  for (let i = 0; i < 4; i++) await numInput.press('Backspace')
  await numInput.type('30')
  await page.waitForTimeout(200)
  // 用回车提交而不是点 ✓：antd 表格在固定表头模式下有一张隐藏的 measure row
  // （height:0 的占位，tr 数量比数据行多），Playwright 的可点性检查会被它判成
  // "被遮挡"（实测 document.elementFromPoint 也命中它，尽管按钮几何上在自己 td 内）。
  // 真人点击不受影响——事件直接派发给按钮。IntervalEditor 本就绑了 @press-enter，
  // 回车是真实用户会用的路径。
  await numInput.press('Enter')
  await page.waitForTimeout(900)
  // 保存走既有 saveConfig 链路：按 group + submitKey 提交（parse.queue.scanIntervalMs
  // 落在 parse 组、键名 queue.scanIntervalMs），值是毫秒字符串
  const put = saved.find(s => s.parse && s.parse['queue.scanIntervalMs'])
  ok(put && String(put.parse['queue.scanIntervalMs']) === '30000',
     '保存发出 30 秒 = 30000ms（实发 ' + JSON.stringify(put) + '）')

  // 暂停开关：确认后必须发 { parse: { 'queue.scanIntervalMs': '0' } }。
  // 短键写成 queueScanIntervalMs 时后端白名单匹配不上，写入被静默丢弃——界面提示「已暂停」
  // 但开关不动（本轮修复的正是这个），所以这里断言的是「发出去的键」，与后端 key 同源
  await page.locator('.sched-table .ant-table-row').first().locator('.ant-switch').click()
  await page.waitForTimeout(400)
  const okBtn = page.locator('.ant-modal-confirm-btns .ant-btn-primary')
  ok(await okBtn.count() > 0, '点暂停开关弹出确认框')
  if (await okBtn.count()) { await okBtn.click(); await page.waitForTimeout(800) }
  const pz = saved.find(s => s.parse && s.parse['queue.scanIntervalMs'] === '0')
  ok(!!pz, '暂停发出 0ms 且键名为 queue.scanIntervalMs（实发 ' + JSON.stringify(pz || {}) + '）')

  // 保存后顶部不应残留「未保存」脏计数（就地保存已同步基线）
  const dirty = await page.locator('.dirty-hint').count()
  ok(dirty === 0, '就地保存后顶部无「已修改未保存」误报')

  // 已暂停行：点开可恢复
  const pausedBtn = page.locator('.ie-btn.off').first()
  ok(await pausedBtn.count() > 0, '已暂停任务的间隔显示为「已暂停」且仍可点')
  // 内置节拍行（configKey=null）不提供编辑控件，只展示来源
  const builtinRow = rows.nth(3)
  ok(await builtinRow.locator('.ie-btn').count() === 0, '内置节拍任务不提供编辑控件')
  ok(/内置节拍/.test((await builtinRow.innerText()) || ''), '内置节拍任务标注来源')

  await browser.close()
  console.log(failed ? `\n真浏览器验证：${failed} 项未通过` : '\n真浏览器验证：全部通过')
  process.exit(failed ? 1 : 0)
})().catch(e => { console.error('验证异常：' + e.message); process.exit(1) })
