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

test('CSS 时长与 VEIL_MS 成对，reveal 阶段只跑整层淡出', () => {
  const veil = readFileSync('src/components/AuthVeil.vue', 'utf8')
  const app = readFileSync('src/views/app.css', 'utf8')
  const login = readFileSync('src/views/LoginPage.vue', 'utf8')

  const fade = veil.match(/\.auth-veil\.reveal\s*\{[^}]*?animation:\s*veil-fade-out\s+([\d.]+)s/)
  assert.ok(fade, 'reveal 缺整层淡出动画')
  assert.equal(Math.round(parseFloat(fade[1]) * 1000), VEIL_MS.reveal.in)
  assert.equal(VEIL_MS.reveal.in, VEIL_MS.reveal.out)

  const outWash = veil.match(/\.auth-veil\.out\.started \.veil-wash\s*\{[^}]*?animation:\s*veil-wash-in-out\s+([\d.]+)s/)
  assert.ok(outWash, 'out 缺画布铺满动画')
  assert.equal(Math.round(parseFloat(outWash[1]) * 1000), VEIL_MS.cover.out)
  assert.match(veil, /\.auth-veil\.in\.started \.veil-wash\s*\{[^}]*?veil-wash-in \.48s/)
  assert.equal(VEIL_MS.cover.in, 480)

  // 画布停在铺满态，其余装饰冻结；否则它们会和新页面落位动画同时跑，掉帧就在这里
  assert.match(veil, /\.auth-veil\.reveal\.started \.veil-wash\s*\{[^}]*animation:\s*none;[^}]*opacity:\s*1/)
  const pauseIdx = veil.indexOf('animation-play-state: paused')
  assert.ok(pauseIdx > 0, 'reveal 未暂停装饰动画')
  const pauseBlock = veil.slice(veil.lastIndexOf('\n\n', pauseIdx), veil.indexOf('}', pauseIdx) + 1)
  for (const sel of ['veil-streams', 'veil-streams path', 'veil-ripples i', 'veil-gate', 'veil-seed', 'veil-glow']) {
    assert.ok(pauseBlock.includes(`.auth-veil.reveal.started .${sel}`), `reveal 未暂停 .${sel}`)
  }
  // 与 .in.started / .out.started 同特异性（都带 .started），必须排在它们后面才生效
  assert.ok(pauseIdx > veil.indexOf('.auth-veil.in.started .veil-glow'))
  assert.ok(pauseIdx > veil.indexOf('.auth-veil.out.started .veil-glow'))
  assert.match(veil, /\.auth-veil\.reveal\s*\{[^}]*pointer-events:\s*none/)

  // Safari/Edge 下 stroke-dashoffset 与滤镜动画每帧都要重绘：遮罩里也只用 transform/opacity
  // （注释里会提到这些属性，先把注释摘掉再看代码）
  const veilCss = veil.replace(/\/\*[\s\S]*?\*\//g, '')
  assert.doesNotMatch(veilCss, /stroke-dashoffset|stroke-dasharray/)
  for (const name of ['stream-draw', 'gate-open']) {
    const block = veil.match(new RegExp(`@keyframes ${name}\\s*\\{([\\s\\S]*?)\\n\\}`))
    assert.ok(block, `${name} 缺 keyframes`)
    assert.doesNotMatch(block[1], /filter:|blur\(/, `${name} 又引入了滤镜动画`)
  }
  assert.doesNotMatch(veil, /\.veil-glow\s*\{[^}]*filter:/)

  // 淡入从 55% 才开始，画布是一次渐显而不是最后 100ms 闪一下；两段 keyframes 同口径
  assert.equal((veil.match(/0%,\s*55%\s*\{\s*opacity:\s*0/g) || []).length, 2)

  // 整页 blur 会逐帧重栅格化整屏内容，是掉帧大头：四段落位/退场只准用 transform + opacity
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
