import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
// 先让 Vue 在没有 document 的环境里完成初始化，下面再装浏览器替身；
// 否则 runtime-dom 会把替身当成真 document，找它要 createElement。
import 'vue'

// authTransition 只碰 document.documentElement / matchMedia：给一组最小浏览器替身，
// 让状态机的相位切换与清理可以脱离浏览器验证（真正的动画帧由 CSS 静态断言兜底）。
const root = {
  dataset: {},
  style: {
    props: {},
    setProperty (k, v) { this.props[k] = v },
    removeProperty (k) { delete this.props[k] }
  }
}
globalThis.window = {
  innerWidth: 1440,
  innerHeight: 900,
  matchMedia: () => ({ matches: false })
}
globalThis.document = {
  documentElement: root,
  querySelectorAll: () => [],
  querySelector: () => null
}

const { authVeil, VEIL_MS, coverAuthVeil, revealAuthVeil, beginAuthEnter } = await import('../src/utils/authTransition.js')

const reset = () => {
  for (const k of Object.keys(root.dataset)) delete root.dataset[k]
  for (const k of Object.keys(root.style.props)) delete root.style.props[k]
  authVeil.visible = false
  authVeil.phase = 'idle'
}

test('in：铺遮罩时定圆心/挂 push，落定后清理属性与圆点变量', async () => {
  reset()
  const covering = coverAuthVeil('in')
  assert.equal(authVeil.visible, true)
  assert.equal(authVeil.phase, 'cover')
  assert.equal(root.dataset.leave, 'push')
  // 没有 [data-brand-anchor] 时落点取屏幕中心
  assert.equal(root.style.props['--wq-push-x'], '720px')
  assert.equal(root.style.props['--wq-push-y'], '450px')
  await covering

  const revealing = revealAuthVeil()
  assert.equal(authVeil.phase, 'reveal')
  assert.equal(root.dataset.enter, 'push')
  await revealing
  assert.equal(authVeil.visible, false)
  assert.equal(authVeil.phase, 'idle')
  assert.equal(root.dataset.leave, undefined)
  assert.deepEqual(root.style.props, {})
})

test('out：不取品牌标圆心，挂 pull 并触发登录页 land', async () => {
  reset()
  const covering = coverAuthVeil('out')
  assert.equal(authVeil.phase, 'cover')
  assert.equal(root.dataset.leave, 'pull')
  assert.deepEqual(root.style.props, {})
  await covering

  const revealing = revealAuthVeil()
  assert.equal(root.dataset.enter, 'land')
  await revealing
  assert.equal(authVeil.visible, false)
  assert.equal(root.dataset.leave, undefined)
})

test('落位动画在揭罩前起跑，重复的 afterEach 不会重启动画', async () => {
  reset()
  await coverAuthVeil('out')
  beginAuthEnter()
  assert.equal(root.dataset.enter, 'land')
  // 守卫内部重定向会再走一次 afterEach：属性还在时就别再设置一遍
  beginAuthEnter()
  assert.equal(root.dataset.enter, 'land')
  await revealAuthVeil()
  assert.equal(authVeil.visible, false)
  assert.equal(authVeil.phase, 'idle')
})

test('旧一代的 reveal 不能把新一代过渡的遮罩关掉', async () => {
  reset()
  await coverAuthVeil('out')
  const stale = revealAuthVeil()
  await coverAuthVeil('in')
  await stale
  assert.equal(authVeil.visible, true)
  assert.equal(authVeil.phase, 'cover')
  assert.equal(root.dataset.leave, 'push')
  await revealAuthVeil()
  assert.equal(authVeil.visible, false)
})

test('非 cover 相位重复 reveal 不重复挂动画', async () => {
  reset()
  await coverAuthVeil('in')
  const first = revealAuthVeil()
  assert.equal(authVeil.phase, 'reveal')
  await revealAuthVeil()
  assert.equal(authVeil.phase, 'reveal')
  await first
  assert.equal(authVeil.phase, 'idle')
})

test('水波分层与登录页 hero-waves 一致，时长成对，揭罩只整层淡出', () => {
  const veil = readFileSync('src/components/AuthVeil.vue', 'utf8')
  const app = readFileSync('src/views/app.css', 'utf8')
  const login = readFileSync('src/views/LoginPage.vue', 'utf8')

  // 关键：过场水波的三层颜色/透明度必须与登录页底部 .hero-waves 完全一致
  const heroMap = new Map()
  for (const m of login.matchAll(/\.hero-waves \.w(\d)\s*\{[^}]*fill:\s*(rgba\([^)]*\))/g)) {
    if (!heroMap.has(m[1])) heroMap.set(m[1], m[2].replace(/\s+/g, ' ').trim())  // 亮色版（首个）优先
  }
  const heroFills = [...heroMap].map(([n, fill]) => ({ n, fill }))
  assert.equal(heroFills.length, 3, '登录页 hero-waves 缺三层')
  for (const { n, fill } of heroFills) {
    const re = new RegExp(`\\.w${n} path\\s*\\{\\s*fill:\\s*${fill.replace(/[()]/g, '\\$&').replace(/\s/g, '\\s*')}`)
    assert.match(veil, re, `遮罩 w${n} 颜色与 hero-waves 不一致（应 ${fill}）`)
  }

  // reveal 整层淡出时长与状态机对齐，且放开点击
  const fade = veil.match(/\.auth-veil\.reveal\.in\s*\{\s*animation:\s*wq-drain-down\s+([\d.]+)s/)
  assert.ok(fade, 'reveal 缺整层淡出')
  assert.equal(Math.round(parseFloat(fade[1]) * 1000), VEIL_MS.reveal.in)
  assert.equal(VEIL_MS.reveal.in, VEIL_MS.reveal.out)
  assert.match(veil, /\.auth-veil\.reveal\s*\{[^}]*pointer-events:\s*none/)

  // 画布铺满时长 == cover：in .48s / out .4s
  const washMs = scope => {
    const m = veil.match(new RegExp(`\\.auth-veil\\.${scope}\\.started \\.veil-wash\\s*\\{\\s*animation:\\s*wq-wash-in\\s+([\\d.]+)s`))
    assert.ok(m, `${scope} 缺画布铺满`)
    return Math.round(parseFloat(m[1]) * 1000)
  }
  assert.equal(washMs('in'), VEIL_MS.cover.in)
  assert.equal(washMs('out'), VEIL_MS.cover.out)

  // 水波必须在放行路由前涨满：w3(最慢一支) 时长+延迟 <= cover。
  // in 自下而上涨起(wq-rise-3)，out 自上而下漫下(wq-fall-3)。
  const waterMs = (scope, kf) => {
    const m = veil.match(new RegExp(`\\.auth-veil\\.${scope}\\.started \\.w3\\s*\\{[^}]*?${kf}\\s+([\\d.]+)s[^}]*?(?:\\.(\\d+)s)?\\s*both`))
    assert.ok(m, `${scope} 缺 w3 水波动画(${kf})`)
    return Math.round((parseFloat(m[1]) + (m[2] ? parseFloat('0.' + m[2]) : 0)) * 1000)
  }
  assert.ok(waterMs('in', 'wq-rise-3') <= VEIL_MS.cover.in, 'in 水波没在放行前涨满')
  assert.ok(waterMs('out', 'wq-fall-3') <= VEIL_MS.cover.out, 'out 水波没在放行前漫满')
  // 方向：登录是自下而上(rise)，退出是自上而下(fall)
  assert.match(veil, /\.auth-veil\.in\.started \.w1\s*\{[^}]*wq-rise-1/)
  assert.match(veil, /\.auth-veil\.out\.started \.w1\s*\{[^}]*wq-fall-1/)


  // 关键帧只用 transform / opacity：水波靠 translateY，画布/揭罩靠 opacity
  for (const name of ['wq-rise-1', 'wq-rise-2', 'wq-rise-3', 'wq-fall-1', 'wq-fall-2', 'wq-fall-3', 'wq-drain-up', 'wq-drain-down', 'wq-wash-in']) {
    const block = veil.match(new RegExp(`@keyframes ${name}\\s*\\{(?:[^{}]|\\{[^{}]*\\})*\\}`))
    assert.ok(block, `${name} 缺 keyframes`)
    const body = block[0].replace(/^@keyframes\s+\S+\s*\{/, '').replace(/\}$/, '')
    assert.doesNotMatch(body, /filter:|blur\(/, `${name} 引入滤镜`)
    assert.doesNotMatch(body, /\b(?:width|height|top|left|right|bottom|margin|padding)\s*:/, `${name} 动到布局属性`)
  }

  // 遮罩里不许有描边动画 / 整页 blur；也不许出现「白点起点」(veil-seed)
  const veilCss = veil.replace(/\/\*[\s\S]*?\*\//g, '')
  assert.doesNotMatch(veilCss, /stroke-dashoffset|stroke-dasharray/)
  assert.doesNotMatch(veilCss, /filter:/)
  assert.doesNotMatch(veil, /veil-seed/, '又出现了白点起点')

  // 减弱动效覆盖画布与水层
  const reducedIdx = veil.indexOf('prefers-reduced-motion: reduce')
  assert.ok(reducedIdx > 0, '遮罩缺减弱动效')
  const reducedBlock = veil.slice(reducedIdx, veil.indexOf('</style>', reducedIdx))
  assert.match(reducedBlock, /\.auth-veil \.w\b/, '减弱动效未覆盖水层')
  assert.match(reducedBlock, /\.auth-veil \.veil-wash\b/, '减弱动效未覆盖画布')

  // 四段落位/退场仍只准 transform + opacity
  for (const [name, css, keyframe] of [
    ['工作台落位', app, 'wq-app-settle'],
    ['工作台后退', app, 'wq-app-recede'],
    ['登录页推镜', login, 'wq-login-push-scale'],
    ['登录页对焦', login, 'wq-login-land']
  ]) {
    const block = css.match(new RegExp(`@keyframes ${keyframe}\\s*\\{([\\s\\S]*?)\\n\\}`))
    assert.ok(block, `${name} 缺 keyframes`)
    assert.doesNotMatch(block[1], /blur\(|filter:/, `${name} 又给整页加了 blur`)
  }
  assert.doesNotMatch(app + login, /\[data-(?:enter|leave)='(?:push|pull|land)'\][^{]*\{[^}]*will-change:[^}]*filter/)
})
