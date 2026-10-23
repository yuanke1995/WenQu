// 真浏览器实测：流式重渲染期间 echarts 图表是不是**一直**在屏上。
// 静态守卫（check-ec-flicker.cjs）只能钉住「搬回挂在哪、写没写 done」，钉不出「闪不闪」——
// 而这条链坏起来是静默的：代码全在、监视器改成 flush:'pre' 也照样绿，屏幕上却是一闪一闪
// （实测 pre 与 post 的差别：181 帧里 154 帧空 vs 0 帧空）。所以要有这一层。
//
// 跑法（需要 vite dev 在跑，脚本会临时往 vite 根目录落一个夹具页、跑完删掉）：
//   cd web && npx vite
//   mkdir -p /tmp/wq-verify && cd /tmp/wq-verify && npm i playwright-core
//   NODE_PATH=/tmp/wq-verify/node_modules npm run check:ecflicker:live
// 缺 playwright-core / 缺浏览器 / vite 没起 → 给出提示并跳过（exit 0），不阻塞其它校验。
//
// 阳性对照（改坏了必须报红）：把 AnswerBody 里的 flush:'post' 临时改成 'pre' 再跑一次。
'use strict'
let chromium
try { ({ chromium } = require('playwright-core')) } catch (e) {
  console.log('SKIP  未安装 playwright-core，跳过 echarts 闪烁实测')
  console.log('      安装：mkdir -p /tmp/wq-verify && cd /tmp/wq-verify && npm i playwright-core')
  process.exit(0)
}
const fs = require('fs')
const path = require('path')

const WEB = path.join(__dirname, '..')
const BASE = (process.env.WQ_WEB_URL || 'http://localhost:5800').replace(/\/$/, '')
const FIXTURE = 'tmp-check-ec-flicker.html'
const FIXTURE_URL = `${BASE}/${FIXTURE}`
const CHARTS = 2
const SAMPLE_MS = 3000

const FIXTURE_HTML = `<!DOCTYPE html>
<html lang="zh-CN" data-theme="light">
<head><meta charset="utf-8"><title>ec flicker fixture</title></head>
<body style="margin:0;padding:24px;width:760px">
<div id="app"></div>
<script type="module">
import '/src/views/app.css'
import '/src/md.css'
import { createApp, ref, h } from 'vue'
import AnswerBody from '/src/components/AnswerBody.vue'
const BT = String.fromCharCode(96)
const fence = opt => BT.repeat(3) + 'echarts\\n' + JSON.stringify(opt) + '\\n' + BT.repeat(3)
const CH1 = fence({
  title: { text: '会话数构成（堆叠）与对话轮数（折线）', subtext: '2026-09-24 ~ 2026-10-23' },
  legend: { data: ['会话数·未删除', '会话数·已删除', '对话轮数'] },
  xAxis: { type: 'category', data: ['09-24','09-25','09-26','09-27','09-28','09-29','09-30','10-01'] },
  yAxis: [{ type: 'value', name: '会话数' }, { type: 'value', name: '对话轮数' }],
  series: [
    { name: '会话数·未删除', type: 'bar', stack: 's', data: [88,95,72,60,44,110,86,50] },
    { name: '会话数·已删除', type: 'bar', stack: 's', data: [12,8,14,6,10,9,7,11] },
    { name: '对话轮数', type: 'line', yAxisIndex: 1, data: [24,31,22,18,15,40,28,19] }
  ]
})
const CH2 = fence({
  title: { text: '每日对话轮数' },
  xAxis: { type: 'category', data: ['10-17','10-18','10-19','10-20','10-21','10-22','10-23'] },
  yAxis: { type: 'value' },
  series: [{ name: '轮数', type: 'bar', data: [31,0,22,45,38,12,26] }]
})
const HEAD = '下面把图直接画出来。\\n\\n' + CH1 + '\\n\\n第二张：\\n\\n' + CH2 + '\\n\\n后面的正文还在继续长：\\n\\n'
const Root = {
  setup () {
    const content = ref('')
    const streaming = ref(true)
    window.__probe = {
      // 每 120ms 换一次正文（与流式渲染节流同拍）：图表源码不变，只有图后面的文字在长
      start () {
        let n = 0
        content.value = HEAD
        const t = setInterval(() => {
          n += 1
          content.value = HEAD + '补充说明第 ' + n + ' 句，正文一直在长。\\n\\n'
          if (n >= 80) clearInterval(t)
        }, 120)
      }
    }
    return () => h(AnswerBody, { content: content.value, streaming: streaming.value, msgIndex: -1, viewer: false })
  }
}
createApp(Root).mount('#app')
<\/script>
</body>
</html>
`

const allPainted = count => {
  const els = [...document.querySelectorAll('.md-diagram')]
  if (els.length < count) return false
  return els.every(el => {
    const cv = el.querySelector('canvas')
    const r = cv && cv.getBoundingClientRect()
    return !!(r && r.width > 20 && r.height > 20)
  })
}

const SAMPLE = async ({ ms, charts }) => {
  const t0 = performance.now()
  const seen = new Set()
  const frames = []
  await new Promise(resolve => {
    const tick = () => {
      const els = [...document.querySelectorAll('.md-diagram')]
      let present = 0
      for (const el of els) {
        const cv = el.querySelector('canvas')
        const r = cv && cv.getBoundingClientRect()
        if (cv && cv.isConnected && r && r.width > 20 && r.height > 20) { present++; seen.add(cv) }
      }
      frames.push({ t: Math.round(performance.now() - t0), present, total: els.length })
      if (performance.now() - t0 < ms) requestAnimationFrame(tick)
      else resolve()
    }
    requestAnimationFrame(tick)
  })
  const blank = frames.filter(f => f.total >= charts && f.present < f.total)
  const runs = []
  let cur = null
  for (const f of frames) {
    if (f.total >= charts && f.present < f.total) { if (cur) cur.n++; else cur = { n: 1, at: f.t, missing: f.total - f.present } }
    else if (cur) { runs.push(cur); cur = null }
  }
  if (cur) runs.push(cur)
  return { charts, frames: frames.length, blankFrames: blank.length, blankRuns: runs, canvases: seen.size }
}

;(async () => {
  fs.writeFileSync(path.join(WEB, FIXTURE), FIXTURE_HTML)
  const failures = []
  let browser
  try {
    browser = await chromium.launch({ channel: process.env.WQ_BROWSER_CHANNEL || 'msedge', headless: true })
    const page = await browser.newPage({ viewport: { width: 900, height: 1200 }, deviceScaleFactor: 2 })
    await page.goto(FIXTURE_URL, { waitUntil: 'domcontentloaded' })
    await page.waitForFunction(() => !!window.__probe, null, { timeout: 15000 })
    await page.evaluate(() => window.__probe.start())
    await page.waitForFunction(allPainted, CHARTS, { timeout: 20000 })
    const r = await page.evaluate(SAMPLE, { ms: SAMPLE_MS, charts: CHARTS })
    console.log(`实测 ${r.frames} 帧（${CHARTS} 张图，正文每 120ms 重渲染一次）`)
    if (r.blankFrames > 0) {
      failures.push(`${r.blankFrames}/${r.frames} 帧上图表不在屏上（最长一段 ${Math.max(...r.blankRuns.map(x => x.n))} 帧）——流式期间会一闪一闪`)
      for (const run of r.blankRuns.slice(0, 6)) failures.push(`  t=${run.at}ms 起 ${run.n} 帧缺 ${run.missing} 张`)
    }
    if (r.canvases > CHARTS) {
      failures.push(`期间出现过 ${r.canvases} 个 canvas（每张图只该 1 个）——搬回没生效，图在被反复重画`)
    }
    if (!failures.length) console.log('PASS  每一帧两张图都在屏上，且全程只有 2 个 canvas（零离场、零重画）')
  } catch (e) {
    const msg = String(e && e.message || e)
    if (/ECONNREFUSED|net::|Timeout|waitForFunction|waitForSelector/i.test(msg)) {
      console.log('SKIP  打不开 ' + FIXTURE_URL + '（vite dev 没起？）：' + msg.split('\n')[0])
      await browser?.close()
      fs.rmSync(path.join(WEB, FIXTURE), { force: true })
      process.exit(0)
    }
    failures.push('跑挂了：' + msg.split('\n')[0])
  }
  await browser?.close()
  fs.rmSync(path.join(WEB, FIXTURE), { force: true })
  if (failures.length) {
    console.log('\n' + failures.map(f => 'FAIL  ' + f).join('\n'))
    process.exit(1)
  }
})().catch(e => {
  try { fs.rmSync(path.join(WEB, FIXTURE), { force: true }) } catch (err) { /* 已删过 */ }
  console.error('FAILED', e)
  process.exit(1)
})
