// ==================== 登录 ⇄ 工作台 过渡（品牌遮罩 + 汇聚进场）====================
// 登录页与工作台本是两套独立页面，路由一切就是硬切，观感上像两个产品。这里在两者
// 之间的唯一一道边界上盖一层全屏遮罩，并让工作台在遮罩揭开的同一刻从四边向中心汇聚：
//   · 盖住（约 0.32s）：登录页被 backdrop 滤镜模糊，品牌画布 + 活水波纹漫起；
//   · 换页：路由在完全遮住的瞬间完成，用户看不到硬切的那一帧；
//   · 揭开（约 0.46s）：画布先退、模糊后收，同时侧栏自左 / 输入区自下 / 顶栏自上
//     一起向中心合拢（汇聚动画定义在 app.css，经 html[data-enter='converge'] 触发）。
// 只在「登录 → 工作台」出现；站内跳转、首次进入、公开分享页都不触发。
import { reactive } from 'vue'

export const authVeil = reactive({
  visible: false,
  phase: 'idle',        // cover（盖住）→ reveal（揭开）
  mode: 'in'            // in：登录 → 工作台；out：工作台 → 登录
})

// 时长与 AuthVeil.vue / app.css 里的动画成对：只改一处等于两处不同步
const REDUCED = typeof window !== 'undefined' && window.matchMedia &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches
export const VEIL_MS = REDUCED
  ? { cover: 60, reveal: 60 }
  : { cover: 320, reveal: 460 }

const wait = ms => new Promise(resolve => setTimeout(resolve, ms))

// 代次：一次过渡还没收尾又来了新的（快速退出再登录），旧的那次必须作废
let gen = 0
let convergeTimer = 0

/** 盖住整屏：导航前调用，等遮罩完全不透明（此时页面已被模糊遮住）再放行路由 */
export async function coverAuthVeil (mode = 'in') {
  gen++
  authVeil.mode = mode
  authVeil.phase = 'cover'
  authVeil.visible = true
  stopConverge()
  await wait(VEIL_MS.cover)
}

/** 揭开遮罩：新页面已挂载后调用；进入工作台的那一次同时触发四边汇聚 */
export async function revealAuthVeil () {
  if (!authVeil.visible || authVeil.phase === 'reveal') return
  const my = gen
  authVeil.phase = 'reveal'
  if (authVeil.mode === 'in' && !REDUCED) startConverge()
  await wait(VEIL_MS.reveal)
  if (my !== gen) return           // 期间又起了新过渡：交给新一代收尾
  authVeil.visible = false
  authVeil.phase = 'idle'
}

// 汇聚进场：属性挂在 <html> 上，app.css 据此给侧栏/输入区/顶栏/欢迎区挂动画。
// 最长一支是输入区 .54s + .02s 延迟，760ms 后摘属性足够覆盖（摘除只是解除选择器，
// 动画已结束并 fill: both，不会闪回）。
function startConverge () {
  const root = document.documentElement
  root.dataset.enter = 'converge'
  clearTimeout(convergeTimer)
  convergeTimer = setTimeout(stopConverge, 760)
}

function stopConverge () {
  clearTimeout(convergeTimer)
  try { delete document.documentElement.dataset.enter } catch (e) { /* 非浏览器环境忽略 */ }
}
