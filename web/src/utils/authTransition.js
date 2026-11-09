// ==================== 登录 ⇄ 工作台 过渡（从品牌标收放）====================
// 登录页与工作台本是两套独立页面，路由一切就是硬切。这里在两者之间的唯一一道
// 边界上盖一层全屏遮罩，并做成同一个镜头的两个方向：
//   · 登录 → 工作台（in）：以品牌标为圆心推镜——源头圆点亮起，三道涟漪与活水线
//     散开，品牌标胀开成「门」，登录页放大失焦；换页后工作台从景深外落位。
//   · 工作台 → 登录（out）：同一套元素的逆放——工作台后退失焦，涟漪收拢、
//     品牌门缩回原点、光井收束；换页后登录页从失焦中对焦落位。
// 只在「登录 ⇄ 工作台」这道边界上出现；站内跳转、首次进入、公开分享页都不触发。
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
  : { cover: { in: 480, out: 400 }, reveal: { in: 480, out: 460 } }

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

/** 盖住整屏：导航前调用，先跑完收放动作再放行路由 */
export async function coverAuthVeil (mode = 'in') {
  gen++
  authVeil.mode = mode
  authVeil.phase = 'cover'
  authVeil.visible = true
  stopEnter()
  // 推镜的圆心取自品牌标；收镜的落点是登录页右上角的源头圆点，位置写在 AuthVeil 样式里
  if (mode === 'in') applyAnchor()
  // in：登录页以品牌标为圆心前推；out：工作台以源头圆点为圆心后退
  document.documentElement.dataset.leave = mode === 'in' ? 'push' : 'pull'
  await wait(VEIL_MS.cover[mode])
}

/** 揭开遮罩：新页面已挂载后调用；in 触发工作台落位，out 触发登录页对焦 */
export async function revealAuthVeil () {
  if (!authVeil.visible || authVeil.phase === 'reveal') return
  const my = gen
  authVeil.phase = 'reveal'
  if (!REDUCED) startEnter(authVeil.mode === 'in' ? 'push' : 'land')
  await wait(VEIL_MS.reveal[authVeil.mode])
  if (my !== gen) return           // 期间又起了新过渡：交给新一代收尾
  authVeil.visible = false
  authVeil.phase = 'idle'
  delete document.documentElement.dataset.leave
  clearVars()
}

// 落位/对焦：属性挂在 <html> 上，app.css（push）与 LoginPage.vue（land）据此挂动画。
// 最长一支 .66s，760ms 后摘属性足够覆盖（摘除只是解除选择器，动画已结束并
// fill: both，不会闪回）。
function startEnter (kind) {
  const root = document.documentElement
  root.dataset.enter = kind
  clearTimeout(enterTimer)
  enterTimer = setTimeout(stopEnter, 760)
}

function stopEnter () {
  clearTimeout(enterTimer)
  try { delete document.documentElement.dataset.enter } catch (e) { /* 非浏览器环境忽略 */ }
}
