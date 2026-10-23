// echarts 图表「一闪一闪」的静态守卫（纯读源码，跑在 npm run check 里，不需要浏览器）。
// 起因：流式期间 v-html 每 120ms 重建正文，图表容器随之变成空节点，而补画挂在 300ms 节流上
// （实测约 600ms 落一拍）——中间这段窗口图根本不在屏上。真机量过：一屏 181 帧里 159 帧是空的，
// canvas 全程只有一个，所以闪的不是重画，是**节点离场**。修法是在 DOM 更新后、浏览器绘制前的
// 同一个任务里同步把画好的宿主搬回来（AnswerBody 的 post 监视器 → markdown.js 的 reattachCharts）。
// 这四条关系散在两个文件里，任何一处漂了都不报错，且漂了就回到「图一闪一闪」：
//   1) reattachCharts 必须真的被 AnswerBody 调用，且监视的是喂给 v-html 的那份 html；
//   2) 必须是 flush:'post'——pre 监视器跑在 DOM 更新之前，搬回的就是个旧容器；
//   3) 搬回时必须写 data-done，否则节流那一拍再跑到会重新 init 一遍（闪得更狠）；
//   4) 只搬「已离场」的宿主，连着别处的不能抢（同一份源码画两处时互抢会来回闪）。
// 阳性对照（改坏了必须报红，别信「永远绿」的守卫）：
//   WQ_ECFLICKER_ROOT=/tmp/wq-ecflicker-probe node scripts/check-ec-flicker.cjs
'use strict'
const fs = require('fs')
const path = require('path')

const WEB = process.env.WQ_ECFLICKER_ROOT || path.join(__dirname, '..')
const failures = []
const passes = []
const ok = (name, cond, got) => (cond ? passes : failures).push(name + (got ? '  →  ' + got : ''))

const read = rel => {
  const full = path.join(WEB, rel)
  if (!fs.existsSync(full)) {
    failures.push(`找不到 ${rel}——本校验本身失效`)
    return ''
  }
  return fs.readFileSync(full, 'utf8')
}

const body = read('src/components/AnswerBody.vue')
const md = read('src/utils/markdown.js')

// 1) 调用点：搬回必须挂在正文每次重渲染上
const callRe = /watch\(\s*(\[[^\]]*\]|[^,)]+)\s*,\s*\(\)\s*=>\s*\{[^}]*reattachCharts\(/
const call = body.match(callRe)
ok('AnswerBody 每帧把画好的图表搬回容器', !!call)
ok('搬回监视的是喂 v-html 的那份 html', !!call && /\bhtml\b/.test(call[1]), call && call[1].trim())
ok('v-html 绑的也是 html（监视对象与渲染对象同源）', /v-html="html"/.test(body))

// 2) 同一个任务内：post 监视器（pre 跑在 DOM 更新之前，那时新容器还不存在）
const callIdx = body.indexOf('reattachCharts(')
const tail = callIdx >= 0 ? body.slice(callIdx, callIdx + 300).split('\nwatch(')[0] : ''
ok('搬回跑在 DOM 更新之后、绘制之前（flush:post）', /flush:\s*'post'/.test(tail))

// 3) 搬回即写 done：节流那一拍据此跳过，不会二次 init
const fnStart = md.indexOf('const adoptEChartHost')
const fnBody = fnStart >= 0 ? md.slice(fnStart, md.indexOf('\n}\n', fnStart) + 3) : ''
ok('搬回时写 data-done（节流那一拍不再重画）', /dataset\.done\s*=\s*code/.test(fnBody))
ok('搬回时把宿主接回容器', /appendChild\(host\)/.test(fnBody))

// 4) 不抢连着的宿主
const raStart = md.indexOf('export const reattachCharts')
const raBody = raStart >= 0 ? md.slice(raStart, md.indexOf('\n}\n', raStart) + 3) : ''
ok('只搬已离场的宿主（在别处连着的不能抢）', /host\.isConnected/.test(raBody) && /continue/.test(raBody))
ok('搬回入口按 echarts 围栏筛容器', /\[data-md-echart\]/.test(raBody))

// 反向：节流补画那条路还在（首画与半截 JSON 自愈靠它，搬回只负责已画好的）
ok('流式节流补画仍在（首画不靠搬回）', /ENHANCE_GAP/.test(body) && /enhanceDiagrams\(rootEl\.value/.test(body))

for (const p of passes) console.log('PASS  ' + p)
if (failures.length) {
  console.log('\n' + failures.map(f => 'FAIL  ' + f).join('\n'))
  console.log('\n共 ' + failures.length + ' 条不通过：图表会在流式期间一闪一闪')
  process.exit(1)
}
console.log('\n全部通过')
