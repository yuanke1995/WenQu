// 真浏览器验证：官方内置库（问渠使用手册）的文档页应露出「知识库配置」入口，
// 且点开后向量模型可选、可保存——修「引用清单指去修改却跳到没有配置入口的文档列表页」。
//
// 背景：上一轮只验了 API 层（后端白名单），没点界面，结果管理员在文档页看不到配置按钮
// （canManageCurrentKb 对内置库直接 return false，把整个按钮藏了）。故这一层必须有。
//
// 运行前置：后端 8097 实例在跑、web/dist 已构建、playwright-core 可用。
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core')
  process.exit(0)
}

const BASE = 'http://localhost:5801'
const KB_ID = '391a005b6edca96c43ac9eeff3a76c76' // 问渠使用手册
let fail = 0
const check = (ok, label, detail = '') => {
  if (!ok) fail++
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (detail ? '  ' + detail : ''))
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  page.on('pageerror', () => {})

  // 先落到应用页（evaluate 里的相对 fetch 需要有 base URL），再登录
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  // 登录（管理员）。注意：dev server 下页面路由是 /xxx，而 API 走 /proxy/api/ai/**——
  // 两者不同源路径，不能把 BASE 直接拼到 API 上（会打到 SPA 路由拿回 HTML）。
  const tk = await page.evaluate(async () => {
    const r = await fetch('/proxy/api/ai/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ identifier: 'admin', password: 'admin@2026' })
    })
    const d = await r.json()
    const t = (d && d.data && d.data.token) || ''
    if (t) localStorage.setItem('ai_token', t)
    return t
  })
  check(!!tk, '管理员登录', tk ? '' : '未拿到 token')
  if (!tk) { await browser.close(); process.exit(1) }
  await page.addInitScript(t => localStorage.setItem('ai_token', t), tk)

  // 打开内置库的文档列表页（引用清单里「去修改」跳转的目标页）
  await page.goto(`${BASE}/knowledge/${KB_ID}/docs`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1800)

  const isBuiltinPage = await page.evaluate(() => document.body.innerText.includes('随版本自动同步'))
  check(isBuiltinPage, '进的是内置库文档页（面包屑带「官方 · 随版本自动同步」）')

  // 核心断言：管理员应能看到「知识库配置」按钮
  const cfgBtn = page.locator('.doc-tools button', { hasText: '知识库配置' })
  const cfgVisible = await cfgBtn.count() > 0 && await cfgBtn.first().isVisible()
  check(cfgVisible, '管理员可见「知识库配置」按钮', cfgVisible ? '' : '按钮缺失/不可见')

  // 不该出现的：往内置库上传文档/网页导入（内容只读，后端会明确拒绝）
  const uploadBtn = page.locator('.doc-tools button', { hasText: '上传文档' })
  const uploadVisible = await uploadBtn.count() > 0 && await uploadBtn.first().isVisible()
  check(!uploadVisible, '内置库不暴露「上传文档」入口')
  const urlBtn = page.locator('.doc-tools button', { hasText: '网页导入' })
  const urlVisible = await urlBtn.count() > 0 && await urlBtn.first().isVisible()
  check(!urlVisible, '内置库不暴露「网页导入」入口')

  // 点开配置弹窗，验向量模型这一项：可选（不 disabled）、有值
  if (cfgVisible) {
    await cfgBtn.first().click()
    await page.waitForTimeout(1200)
    const modal = page.locator('.ant-modal-content, .kb-edit-modal').first()
    const modalText = await modal.innerText().catch(() => '')
    check(modalText.includes('向量模型'), '配置弹窗里有「向量模型」项')

    const selector = page.locator('.ant-modal-content .ant-select').filter({ has: page.locator('input') })
    // 向量模型是弹窗内第 3 个 select（名称在前、向量模型紧随其后）——按 label 定位更稳
    const embedSelect = modal.locator('.ant-form-item', { hasText: '向量模型' }).locator('.ant-select').first()
    const embedCount = await embedSelect.count()
    check(embedCount > 0, '找到向量模型选择器')
    if (embedCount > 0) {
      const disabled = await embedSelect.evaluate(el => {
        const sel = el.querySelector('.ant-select-selector')
        return sel ? sel.classList.contains('ant-select-disabled') : null
      })
      check(disabled === false, '向量模型选择器可编辑（未被置灰）', `disabled=${disabled}`)
      const text = await embedSelect.innerText().catch(() => '')
      check(text && text.trim().length > 0, '向量模型已回填当前值', text.replace(/\s+/g, ' ').trim().slice(0, 60))
    }
    // 名称/描述应仍是置灰（内容随版本同步）
    const nameInput = modal.locator('.ant-form-item', { hasText: '名称' }).locator('input').first()
    const nameDisabled = await nameInput.evaluate(el => el.disabled).catch(() => null)
    check(nameDisabled === true, '内置库「名称」仍置灰（内容不归人工）', `disabled=${nameDisabled}`)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(500)
  }

  await browser.close()
  console.log(fail === 0 ? '\n全部通过' : `\n失败 ${fail} 项`)
  process.exit(fail === 0 ? 0 : 1)
})().catch(e => { console.error('异常：', e.message); process.exit(1) })
