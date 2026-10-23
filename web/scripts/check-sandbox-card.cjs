// 真机验收：对话右栏「沙盒文件」卡（刀2 的前端那一段）
// 跑法：NODE_PATH=/tmp/wq-verify/node_modules node scripts/check-sandbox-card.cjs
//   （需要 8099 旁路后端 + 5801 vite 在前台）
const { chromium } = require('playwright-core')
const EDGE = process.env.WQ_BROWSER || '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'

const ORIGIN = process.env.WQ_ORIGIN || 'http://localhost:5801'
const API = ORIGIN + '/proxy/api/ai'
const SID = process.env.WQ_SID || 'e0cfd40bd47645a295ac8af4c4db9204'
const OUT = process.env.WQ_OUT || '/tmp/shot-sandbox-card.png'
const UID = process.env.WQ_UID || 'admin'
const PWD = process.env.WQ_PWD || 'admin@2026'

let failed = 0
const check = (ok, name, info) => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${info ? '  — ' + info : ''}`)
  if (!ok) failed++
}

async function main() {
  const login = await fetch(API + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ identifier: UID, password: PWD })
  }).then(r => r.json())
  const token = login?.data?.token
  if (!token) throw new Error('登录失败：' + JSON.stringify(login).slice(0, 200))

  const browser = await chromium.launch({ executablePath: EDGE, headless: true })
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
  await page.addInitScript(t => { localStorage.setItem('ai_token', t) }, token)
  await page.goto(`${ORIGIN}/chat?sid=${SID}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)

  const state = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find(b => /状态|隐藏状态/.test(b.textContent))
    const panel = document.querySelector('.right-panel')
    return { btnText: btn ? btn.textContent.trim() : '', panelOpen: !!panel }
  })
  if (!state.panelOpen && state.btnText) {
    await page.getByRole('button', { name: state.btnText }).click()
    await page.waitForTimeout(800)
  }

  const card = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.right-panel .rp-card')]
    const el = cards.find(c => /沙盒文件/.test(c.querySelector('.rp-label')?.textContent || ''))
    if (!el) return { found: false, labels: cards.map(c => c.querySelector('.rp-label')?.textContent?.trim()) }
    const rows = [...el.querySelectorAll('.rp-art')]
    return {
      found: true,
      label: el.querySelector('.rp-label').textContent.trim(),
      rows: rows.map(r => ({
        name: r.querySelector('.rp-art-name')?.textContent?.trim(),
        view: !!r.querySelector('a[href*="project-files"]'),
        acts: r.querySelectorAll('.rp-art-act').length
      })),
      // 截断提示有没有被静默吞掉
      err: el.querySelector('.rp-sb-err')?.textContent?.trim() || ''
    }
  })

  check(card.found, '右栏出现「沙盒文件」卡', card.found ? card.label : 'labels=' + JSON.stringify(card.labels))
  if (card.found) {
    check(card.err === '', '没有把读取失败静默成空列表', card.err)
    check(card.rows.length >= 8, '列出会话沙盒目录里的文件（含中间件，整条相对路径露出）',
      card.rows.map(r => r.name).join(' | '))
    check(card.rows.every(r => r.view), '可预览的类型都挂了签名预览地址')
    check(card.rows.every(r => r.acts === 3), '每条三个动作（预览/下载/存为产物）',
      'acts=' + card.rows.map(r => r.acts).join(','))
    const box = await page.locator('.right-panel').boundingBox()
    await page.screenshot({ path: OUT, clip: box })
    console.log('SHOT', OUT)
  }

  // 点第一条的「存为产物」，看按钮改口（不重复邀请点击）
  if (card.found) {
    const first = page.locator('.right-panel .rp-card', { hasText: '沙盒文件' }).locator('.rp-art').first()
    await first.locator('button').nth(1).click()
    await page.waitForTimeout(1200)
    const after = await first.evaluate(el => ({
      taken: !!el.querySelector('.rp-sb-taken'),
      acts: el.querySelectorAll('.rp-art-act').length,
      toast: document.querySelector('.ant-message')?.textContent?.trim() || ''
    }))
    check(after.taken, '存为产物后按钮改口为「已存为」', JSON.stringify(after))
    check(/已存为产物/.test(after.toast), '给出成功回执', after.toast)
  }

  await browser.close()
  console.log(failed ? `\n${failed} 条未过` : '\n全部通过')
  process.exit(failed ? 1 : 0)
}

main().catch(e => { console.error('ERROR', e.message); process.exit(1) })
