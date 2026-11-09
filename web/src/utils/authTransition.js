// ==================== 登录 ⇄ 工作台 过渡（从品牌标推镜）====================
// 登录页与工作台本是两套独立页面，路由一切就是硬切。这里在两者之间的唯一一道
// 边界上盖一层全屏遮罩，并把「登录 → 工作台」做成一次以品牌标为圆心的推镜：
//   · 推镜（约 0.52s）：源头圆点在品牌标处亮起，三道涟漪与三道活水线散开，
//     品牌标胀开成「门」；登录页以品牌标为圆心放大失焦，卡片带一点前景视差。
//   · 换页：画布在推镜末尾铺满，路由在这一帧完成，看不到硬切。
//   · 落位（约 0.66s）：遮罩整体退去，工作台从 1.06 倍景深外对上焦
//     （动画定义在 app.css，经 html[data-enter='push'] 触发）。
// 只在「登录 → 工作台」出现；站内跳转、首次进入、公开分享页都不触发。
import { reactive } from 'vue'

export const authVeil = reactive({
  visible: false,
  phase: 'idle',        // cover（推镜）→ reveal（落位）
  mode: 'in',           // in：登录 → 工作台；out：工作台 → 登录
  anchor: { x: 0, y: 0 } // 品牌标在屏幕上的中心点（px），推镜与涟漪都从它出发
})

// 时长与 AuthVeil.vue / LoginPage.vue / app.css 里的动画成对：只改一处等于两处不同步。
// reveal 是遮罩自身的停留时长（整层淡出 .42s），工作台的落位动画由 app.css 独立跑完，
// 不跟着遮罩一起等——否则透明遮罩还会多拦几百毫秒的点击。
const REDUCED = typeof window !== 'undefined' && window.matchMedia &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches
export const VEIL_MS = REDUCED
  ? { cover: { in: 60, out: 60 }, reveal: { in: 60, out: 60 } }
  : { cover: { in: 480, out: 320 }, reveal: { in: 480, out: 460 } }

const wait = ms => new Promise(resolve => setTimeout(resolve, ms))

// 代次：一次过渡还没收尾又来了新的（快速退出再登录），旧的那次必须作废
let gen = 0
let enterTimer = 0

/** 找不到品牌标时的落点：屏幕中心（如单点登录回调页，页面上没有品牌标） */
function applyAnchor () {
  const x = window.innerWidth / 2
  const y = window.innerHeight / 2
  let ax = x
  let ay = y
  const nodes = document.querySelectorAll('[data-brand-anchor]')
  for (const el of nodes) {
    const r = el.getBoundingClientRect()
    if (r.width > 0 && r.height > 0 && el.offsetParent !== null) {
      ax = r.left + r.width / 2
      ay = r.top + r.height / 2
      break
    }
  }
  authVeil.anchor.x = ax
  authVeil.anchor.y = ay
  const root = document.documentElement
  root.style.setProperty('--wq-push-x', ax + 'px')
  root.style.setProperty('--wq-push-y', ay + 'px')
  // 卡片比整页更近，按同一圆心换算成卡片自身坐标系里的原点（实现前景视差）
  const card = document.querySelector('.login-card')
  if (card) {
    const cr = card.getBoundingClientRect()
    root.style.setProperty('--wq-card-x', (ax - cr.left) + 'px')
    root.style.setProperty('--wq-card-y', (ay - cr.top) + 'px')
  }
}

function clearVars () {
  const root = document.documentElement
  root.style.removeProperty('--wq-push-x')
  root.style.removeProperty('--wq-push-y')
  root.style.removeProperty('--wq-card-x')
  root.style.removeProperty('--wq-card-y')
}

/** 盖住整屏：导航前调用，先跑完推镜再放行路由 */
export async function coverAuthVeil (mode = 'in') {
  gen++
  authVeil.mode = mode
  authVeil.phase = 'cover'
  authVeil.visible = true
  stopEnter()
  applyAnchor()
  if (mode === 'in') document.documentElement.dataset.leave = 'push'
  else delete document.documentElement.dataset.leave
  await wait(VEIL_MS.cover[mode])
}

/** 揭开遮罩：新页面已挂载后调用；进入工作台的那一次同时触发落位 */
export async function revealAuthVeil () {
  if (!authVeil.visible || authVeil.phase === 'reveal') return
  const my = gen
  authVeil.phase = 'reveal'
  if (authVeil.mode === 'in' && !REDUCED) startEnter()
  await wait(VEIL_MS.reveal[authVeil.mode])
  if (my !== gen) return           // 期间又起了新过渡：交给新一代收尾
  authVeil.visible = false
  authVeil.phase = 'idle'
  delete document.documentElement.dataset.leave
  clearVars()
}

// 工作台落位：属性挂在 <html> 上，app.css 据此给 .app-root 挂动画。
// 落位动画 .66s，760ms 后摘属性足够覆盖（摘除只是解除选择器，动画已结束并
// fill: both，不会闪回）。
function startEnter () {
  const root = document.documentElement
  root.dataset.enter = 'push'
  clearTimeout(enterTimer)
  enterTimer = setTimeout(stopEnter, 760)
}

function stopEnter () {
  clearTimeout(enterTimer)
  try { delete document.documentElement.dataset.enter } catch (e) { /* 非浏览器环境忽略 */ }
}
