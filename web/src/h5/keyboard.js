// ==================== iOS 软键盘适配（visualViewport） ====================
// 把「视觉视口高度」与「键盘净占用」写成 :root 上的 CSS 变量，由样式侧自行消费。
//
// 为什么用「测量 + CSS 变量」而不是 position:fixed 把输入区顶上去（三个独立的坑）：
//   ① iOS Safari 键盘弹起时**布局视口不变**，只是视觉视口被裁掉 + 页面被顶上去。
//      所以 position:fixed; bottom:0 的元素仍贴在布局视口底部 = 被键盘盖住。
//   ② visualViewport.offsetTop 是「页面被顶上去的量」而非键盘高度，且随用户滚动实时变化，
//      拿它做 translateY 是双倍位移 + 滚动时抖动。
//   ③ transform 会创建新的 containing block，使所有 position:fixed 后代改以该元素为基准。
//      ChatPage 里 .lightbox（灯箱，z-index 2000）等就是 fixed 浮层——
//      输入区一旦 transform，灯箱会变成「只盖住输入区」而不是全屏。
//
// 正确姿势是**收缩容器高度**：ChatPage 的输入区本来就是 .chat-col（column flex）里
// flex:none 的兄弟节点（不是 fixed），.chat-col 一收缩输入区自然上移、消息区自动让位。
// 全程无 fixed、无 transform、无 scrollTo。
//
// 两条变量各管一处，不要重复扣减：
//   --app-vh —— 视觉视口高度（已扣掉键盘）。给 .content-app / .login-wrap 这类满高容器。
//   --kb     —— 键盘净占用。只用于局部 padding 补偿（输入区、登录卡片、底部 sheet）。
// ⚠️ 容器高度已继承 --app-vh（.app-root{height:100%} → .main → .chat2 → .chat-col 一路透传），
//    **绝不可**再写 height:calc(100% - var(--kb))，那会把键盘高度扣两次，
//    消息区在键盘弹起时凭空矮一个键盘的高度。
import { ref, watch } from 'vue'
import { isNarrow } from './mobile'

const vv = window.visualViewport || null

/** 键盘是否处于弹起状态（供需要跳过 hover 延迟等行为的分支使用） */
export const kbOpen = ref(false)

// 低于此值视为误触：iOS Safari 地址栏收放也会改 visualViewport.height（幅度约 60~90px），
// 那个不是键盘，不能据此缩小布局
const KB_MIN = 90

let raf = 0
let lastKb = -1
let lastVh = -1

function apply () {
  raf = 0
  if (!isNarrow.value || !vv) return
  // offsetTop 必须减掉：它是「页面被顶上去的量」，不减会把键盘高度高估一截
  const kb = Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop))
  const vh = Math.round(vv.height)
  // 去重必须同时看 kb 与 vh：浏览器进入/退出全屏、地址栏收放只改 vv.height 而 kb 不变——
  // 只按 kb 去重会让 --app-vh 停在旧值，容器比当前可视区矮一截，
  // 表现为「全屏后输入区悬在半空不贴底」。
  if (kb === lastKb && vh === lastVh) return
  lastKb = kb
  lastVh = vh
  const effective = kb > KB_MIN ? kb : 0
  const root = document.documentElement.style
  root.setProperty('--kb', effective + 'px')
  // 键盘收起时 vv.height 回到 innerHeight，--app-vh 自动归位，满高容器随之恢复
  root.setProperty('--app-vh', vh + 'px')
  kbOpen.value = effective > 0
  // 供 ChatPage 等需要在键盘态下重测 textarea 高度、维持贴底的消费方使用
  window.dispatchEvent(new CustomEvent('app:kb', { detail: { open: kbOpen.value, height: effective } }))
}

function schedule () { if (!raf) raf = requestAnimationFrame(apply) }

export function installKeyboardInset () {
  if (!vv) return // 不支持 visualViewport 的老浏览器：--kb 保持 0，退化为现有行为（不崩即可）
  vv.addEventListener('resize', schedule, { passive: true })
  vv.addEventListener('scroll', schedule, { passive: true })
  // 转屏时 visualViewport 的宽高互换，必须重算。
  // 延后一帧：orientationchange 触发时 vv 上还是转屏前的值
  window.addEventListener('orientationchange', () => setTimeout(schedule, 120), { passive: true })
  // 离开窄屏要清干净，否则 PC 上（或手机横屏宽于 768 时）残留的
  // --kb / --app-vh 会让布局莫名缺一截或停在小屏高度
  watch(isNarrow, v => {
    if (v) { lastKb = -1; lastVh = -1; schedule() } else {
      document.documentElement.style.removeProperty('--kb')
      document.documentElement.style.removeProperty('--app-vh')
      lastKb = -1
      lastVh = -1
    }
  })
  // 挂载即测一次：首屏就有正确的 --app-vh，不必等首个 resize 事件
  //（否则初值落在回退分支，.content-app 的 100vh 与 .m-chat 的 100dvh 各走各的）
  schedule()
}
