// Markdown 导出里「图表变成一张图」的静态守卫（纯读源码，跑在 npm run check 里，不需要浏览器）。
// 起因：导出的 .md 里 ```echarts 围栏原样落盘，而 echarts 是本项目的自造语言标，交给 GitHub /
// IDEA / Typora 就是一份裸 JSON——用户看到的「图」是一屏花括号。修法是导出前离屏重画成 PNG 内嵌。
// 这四条漂了都不报错，且漂了就回到「导出的 MD 没有图」：
//   1) 快照必须关动画：setOption 后同步取位图拿到的是第 0 帧，柱子和扇形还没长出来
//      （真机量过：开着动画差异像素 4.6%，关掉 21.4%；饼图 0.1% → 11.4%）；
//   2) 屏上那条绘制线不能被连坐——正文里的图是要动的，animation:false 只许待在快照函数里；
//   3) 单轮与会话两条导出路都得过 embedMdCharts，漏一条就是「这轮的图没了」；
//   4) 快照失败要保留原围栏，且正文里的源码必须挪进文末附录，不能两头都没有（数据只有一个出处）。
// 反向口径：mermaid 围栏**刻意不转图**——GitHub/IDEA/Typora 原生就能画 mermaid，转成位图是降级。
// 阳性对照（改坏了必须报红，别信「永远绿」的守卫）：
//   WQ_MDCHART_ROOT=/tmp/wq-mdchart-probe node scripts/check-md-chart-export.cjs
'use strict'
const fs = require('fs')
const path = require('path')

const WEB = process.env.WQ_MDCHART_ROOT || path.join(__dirname, '..')
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

const md = read('src/utils/markdown.js')
const ex = read('src/views/exportMd.js')

// 1) 快照函数：关动画 + 烘背景 + 失败返回 null
const snapStart = md.indexOf('export const snapshotEChart')
const snapBody = snapStart >= 0 ? md.slice(snapStart, md.indexOf('\n}\n', snapStart) + 3) : ''
ok('导出层真的接上了快照入口（改名/断链都要报红）',
  /export const snapshotEChart =/.test(md) && /await snapshotEChart\(/.test(ex) && /getDataURL/.test(snapBody))
ok('快照关掉动画（否则截到第 0 帧的空图）', /animation\s*=\s*false/.test(snapBody))
ok('快照把面板底色烘进位图（深色主题不至于白底隐形）', /backgroundColor/.test(snapBody))
ok('画不出来返回 null，不抛异常', /return null/.test(snapBody))

// 2) 屏上绘制线不受连坐
const paintStart = md.indexOf('const paintEChart')
const paintBody = paintStart >= 0 ? md.slice(paintStart, md.indexOf('\n}\n', paintStart) + 3) : ''
ok('正文里的图表仍走动画（animation:false 不许漏进屏上那条线）', paintStart >= 0 && !/animation/.test(paintBody))

// 3) 两条导出路都换图
ok('导出层按围栏匹配 echarts 并换成图', /embedMdCharts/.test(ex) && /echarts/i.test(ex.match(/const ECHARTS_FENCE = .*/)?.[0] || ''))
ok('单轮导出走了换图这一步', /buildAnswerMd[\s\S]*?await embedMdCharts\(/.test(ex))
ok('整会话导出走了换图这一步', /buildSessionMd[\s\S]*?await embedMdCharts\(/.test(ex))
ok('快照失败时原围栏留在正文', /hit\.url \? .*: m\[0\]/.test(ex))

// 4) 源码不丢：正文里的围栏挪进文末附录
ok('附录把图表源码收在文末', /chartAppendix/.test(ex) && /## 图表源码/.test(ex))
ok('附录只收快照成功的那几张', /\.filter\(h => h\.url\)/.test(ex))
const appendixBody = ex.slice(ex.indexOf('const chartAppendix'))
ok('附录逐张落回 echarts 围栏（在问渠里仍是一张图）', /```echarts/.test(appendixBody.slice(0, 900)))
ok('mermaid 围栏不转图（外部阅读器原生能画）', !/mermaid/i.test(ex.match(/const ECHARTS_FENCE = .*/)?.[0] || ''))

for (const p of passes) console.log('PASS  ' + p)
if (failures.length) {
  console.log('\n' + failures.map(f => 'FAIL  ' + f).join('\n'))
  console.log('\n共 ' + failures.length + ' 条不通过：导出的 .md 里图表会退回成一份裸 JSON')
  process.exit(1)
}
console.log('\n全部通过')
