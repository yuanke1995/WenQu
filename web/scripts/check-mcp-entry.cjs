// D2 真浏览器校验：API 密钥页的「平台级 MCP 接入点」是否真的看得见、拿得到、能复制。
//
// 为什么必须有这一层：本次改动全是"界面上多一块东西"，静态校验只能证明代码在位。
// 本仓库已连续栽在"静态全绿、用户点不到"上（抽屉 v-model 同名 ref、面板数据源用错两次）。
//
// 真后端（8090）+ 真浏览器（系统 Edge）+ dev server（5800），用 admin 登录取真 Key 列表，
// 因此验的是"用户真能看到什么"，而不是 mock 出来的形状。
//
// 运行：NODE_PATH=/tmp/wq-verify/node_modules node web/scripts/check-mcp-entry.cjs
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过 MCP 接入点浏览器验证')
  console.log('      cd /tmp/wq-verify && npm i playwright-core')
  process.exit(0)
}

const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
const fs = require('fs')
if (!fs.existsSync(EDGE)) { console.log('SKIP  未找到浏览器：' + EDGE); process.exit(0) }
const BASE = process.env.WQ_BASE || 'http://localhost:5800'
const UID = process.env.WQ_UID || 'admin'
const PWD = process.env.WQ_PWD || 'admin@2026'

let fail = 0
const check = (ok, label, detail = '') => {
  if (!ok) fail++
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (detail ? '  ' + detail : ''))
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await ctx.newPage()
  const jsErrors = []
  page.on('pageerror', e => jsErrors.push(e.message))

  // 1) 登录取真令牌，写进 localStorage（前端读 ai_token）
  // 注意：dev server 只代理 /proxy（不是 /api）——/proxy/api/ai/* 会被转发到后端 /ai/api/ai/*
  const login = await page.request.post(`${BASE}/proxy/api/ai/auth/login`, {
    data: { identifier: UID, password: PWD }
  })
  const lj = await login.json()
  check(lj.success === true && lj.data && lj.data.token, '登录取到令牌', lj.message || '')
  if (!lj.success) { await browser.close(); process.exit(1) }

  await page.goto(BASE + '/login')
  await page.evaluate(t => localStorage.setItem('ai_token', t), lj.data.token)

  // 2) 进设置页 → API 密钥面板
  await page.goto(BASE + '/settings', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)

  // 面板入口：左侧分组导航里的「API 密钥」
  const entry = page.locator('.set-nav-item', { hasText: 'API 密钥' }).first()
  check(await entry.count() > 0, '设置页有「API 密钥」分组')
  if (await entry.count() === 0) {
    const navs = await page.locator('.set-nav-item').allInnerTexts().catch(() => [])
    console.log('       实际分组：' + navs.join(' / '))
    await browser.close(); process.exit(1)
  }
  await entry.click()
  await page.waitForTimeout(1200)

  // 3) 展开「如何使用」
  const usage = page.locator('text=如何使用').first()
  check(await usage.count() > 0, '「如何使用」折叠区在位')
  await usage.click()
  await page.waitForTimeout(500)

  // 4) 核心断言：接入点区块、地址、mcp.json
  const urlText = await page.locator('.mcp-entry code').first().textContent().catch(() => '')
  check(!!urlText && urlText.includes('/ai/mcp'), '接入点地址可见且形如 …/ai/mcp', String(urlText))

  const jsonText = await page.locator('.mcp-entry .key-code-pre').first().textContent().catch(() => '')
  let parsed = null
  try { parsed = JSON.parse(jsonText) } catch (e) {}
  check(!!parsed, 'mcp.json 是合法 JSON（可直接粘贴）', String(jsonText).slice(0, 60).replace(/\n/g, ' '))
  check(!!parsed && !!parsed.mcpServers && !!parsed.mcpServers.wenqu, 'mcp.json 含 mcpServers.wenqu')
  check(!!parsed && !!parsed.mcpServers.wenqu.url && parsed.mcpServers.wenqu.url.endsWith('/ai/mcp'),
    'mcp.json 的 url 指向 /ai/mcp', parsed ? parsed.mcpServers.wenqu.url : '')
  check(!!parsed && !!(parsed.mcpServers.wenqu.headers || {}).Authorization,
    'mcp.json 带 Authorization 头（否则客户端连不上）')

  // 5) 关键提示必须在场：总开关 + Origin（两处最容易卡住人的地方）
  const blockText = await page.locator('.mcp-entry').first().innerText().catch(() => '')
  // 注意：文案里「MCP」被 <strong> 包着，innerText 会在标签边界插入空白
  //   → 断言不能写「MCP 开关」（中间隔着 strong，永远不匹配）
  check(blockText.includes('MCP') && blockText.includes('开关'), '提示了「该 Key 需打开 MCP 开关」')
  check(blockText.includes('对外提供 MCP 端点'), '提示了管理员总开关')
  check(blockText.includes('Origin'), '提示了对外域名的 Origin 白名单（否则 403 查不出原因）')
  check(blockText.includes('/ai/mcp/{token}'), '说明了与智能体端点的区分（凭据不同）')

  // 6) 复制按钮真的可点（点了要出"已复制"态）——"有按钮但点不动"是这一屏的经典坑
  const copyBtn = page.locator('.mcp-entry .key-copy').first()
  check(await copyBtn.count() > 0, '接入点地址旁有复制按钮')
  if (await copyBtn.count() > 0) {
    await ctx.grantPermissions(['clipboard-read', 'clipboard-write'])
    await copyBtn.click()
    await page.waitForTimeout(400)
    const clip = await page.evaluate(() => navigator.clipboard.readText().catch(() => '')).catch(() => '')
    check(!!clip && clip.includes('/ai/mcp'), '点复制后剪贴板拿到地址', String(clip))
  }

  // 7) 几何断言：这块不能破版/溢出（本仓库排版问题只有量尺寸才看得出来）
  const box = await page.locator('.mcp-entry').first().boundingBox()
  check(!!box && box.width > 300, '区块有实际尺寸（没被压成 0）', box ? `${Math.round(box.width)}×${Math.round(box.height)}` : 'null')
  const overflow = await page.evaluate(sel => {
    const el = document.querySelector(sel)
    if (!el) return 'no-node'
    return el.scrollWidth > el.clientWidth + 2 ? `溢出 ${el.scrollWidth}>${el.clientWidth}` : ''
  }, '.mcp-entry')
  check(overflow === '', '区块无横向溢出', overflow)

  check(jsErrors.length === 0, '无 JS 异常', jsErrors.slice(0, 2).join(' | '))

  await page.screenshot({ path: '/tmp/wq-verify/mcp-entry.png', fullPage: false })
  console.log('\n截图：/tmp/wq-verify/mcp-entry.png')
  console.log(fail === 0 ? '\n全部通过' : `\n失败 ${fail} 项`)
  await browser.close()
  process.exit(fail === 0 ? 0 : 1)
})().catch(e => { console.error('异常：', e.message); process.exit(1) })
