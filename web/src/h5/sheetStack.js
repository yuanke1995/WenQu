// ==================== 移动端 sheet 栈（系统返回键与侧滑的接管处） ====================
// 为什么要有这个文件：触屏上「返回」是用户关一层界面的第一直觉，而此前 h5 全目录里
// `popstate` / `pushState` 零命中——安卓返回键与 iOS 侧滑都不关 sheet，直接把用户甩到上一个
// 路由甚至退出页面；sheet 里还摆着会改数据的动作（存为产物、停止分享、删除本轮），退不出去
// 就只能找那个 34px 的 ×。
//
// 模型：**history 里最多只放一条哨兵**，栈里有几层 sheet 由本模块自己记。
// 相比「每开一层 push 一条」那种写法，它天然不怕「关掉不是栈顶的那一层」——
// 那种情形下 history 与栈会永久错位，后面每次返回都慢一层。
//
// 两条关闭路径必须收敛到同一处，否则会各留一条幽灵条目：
//   - 用户按返回 → 浏览器已消费哨兵 → 本模块只关栈顶，不再 back；
//   - 用户点 × / 遮罩 / 页面自己置 open=false → 栈空时补一次 back() 撤销哨兵。
//
// back() 只在**还停在同一个页面**时做：sheet 常因为一次跳转而卸载（点通知 → router.push），
// 那时再 back 会把用户从他刚到达的页面弹回上一页——比留一条没消费的哨兵坏得多。
// 留下的代价只是「返回键有一次像没反应」，这是有意的取舍。
import { ref } from 'vue'

/** 栈里的每一项：{token, close, baseZ}，close 由持有方给（通常是 emit('close')） */
const stack = ref([])
let seq = 0
/** history 里是否压着我们的那条哨兵 */
let armed = false
/** 武装时的地址串：撤销哨兵前用它确认「还停在同一个页面」 */
let armedAt = ''
let undoTimer = null

const here = () => location.pathname + location.search + location.hash

/** 该 sheet 在栈里的下标（-1 = 不在栈里） */
const depthOf = token => stack.value.findIndex(s => s.token === token)

/**
 * 遮罩的 z-index：同层 sheet 靠 DOM 顺序取胜是过去的做法（所有遮罩同为 1100），
 * 两层同时开着时谁盖谁全看渲染顺序。现在按栈里的深度抬，后开的必然盖住先开的。
 */
export const sheetZ = (token, baseZ) => baseZ + Math.max(0, depthOf(token)) * 10

function sync () {
  const need = stack.value.length > 0
  if (need && !armed) {
    armed = true
    armedAt = here()
    try {
      history.pushState({ ...(history.state || {}), wqSheet: 1 }, '')
    } catch (e) {
      // 少数内嵌 webview 会限流 pushState：拿不到哨兵就当返回键不存在，sheet 照常能关
      armed = false
      armedAt = ''
    }
    return
  }
  if (!need && armed) {
    armed = false
    const at = armedAt
    armedAt = ''
    if (at === here()) {
      // 推到下一个宏任务：同一拍里页面可能紧跟着 router.push（点通知直达会话就是这条路径），
      // 那时 here() 已经变了，复查一次就不必把用户弹回去
      clearTimeout(undoTimer)
      undoTimer = setTimeout(() => { if (!stack.value.length && at === here()) history.back() }, 0)
    }
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('popstate', e => {
    // 不是我们武装的那一条：正常路由后退，不拦。
    // 但**退到了自己留下的幽灵哨兵上**要再 back 一次——sheet 开着就跳走（点通知直达会话、
    // 会话 sheet 里点个人设置）时那条哨兵没法撤销，它会让用户白按一次返回。
    // 连锁会自己停：再退到的那条没有 wqSheet 标记。
    if (!armed) {
      if (e && e.state && e.state.wqSheet && !stack.value.length) history.back()
      return
    }
    armed = false
    armedAt = ''
    clearTimeout(undoTimer)
    const top = stack.value[stack.value.length - 1]
    if (!top) return
    // 只关栈顶那一层：它的 open 翻假后会走 removeSheet → sync，栈里还有上层就重新武装
    try { top.close() } catch (e) { /* 持有方已卸载：交给它的 onUnmounted 清栈 */ }
  })
}

/** 登记一层界面，返回 token（持有方留着它做注销与 z-index 计算） */
export function addSheet (close, baseZ = 1100) {
  const token = ++seq
  stack.value = [...stack.value, { token, close, baseZ }]
  sync()
  return token
}

/** 摘掉一层（关闭或卸载都要调），并同步哨兵 */
export function removeSheet (token) {
  if (!stack.value.some(s => s.token === token)) return
  stack.value = stack.value.filter(s => s.token !== token)
  sync()
}

/** 当前层数（测试与调试用） */
export const sheetDepth = () => stack.value.length
