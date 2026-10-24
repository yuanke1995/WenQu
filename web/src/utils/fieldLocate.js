// ==================== 深链定位：跳到页面后还要把「要改的那一项」指出来 ====================
// 从模型供应商的「查看引用」清单点「去修改」，只把人丢到一整页表单前等于没送到。
// 各落地页（知识库配置弹窗、系统设置、定时任务、工作流、智能体公开发布）共用这一份：
// 每页各写一遍必然走样——有的滚了不亮、有的弹窗还在挂载就去查元素，查不到就静默什么都不做。

const FLASH_MS = 1600
/** 目标可能还在异步挂载（弹窗过渡、列表重拉），按这个间隔重试到超时 */
const POLL_MS = 80
const DEFAULT_TIMEOUT = 2500

/**
 * 定位键的合法形状：配置键（rerank.platformRef）、表单槽名（visionRef）。
 * 键来自 URL query，先卡字符集再进 querySelector——不合法直接当「没有定位要求」，
 * 而不是让一个畸形选择器把整页抛成 SyntaxError。
 */
const SAFE_FIX = /^[\w.-]{1,64}$/

function find (fix) {
  if (!SAFE_FIX.test(String(fix || ''))) return null
  try {
    return document.querySelector(`[data-fix="${fix}"]`)
  } catch {
    return null
  }
}

/** 元素在不在屏幕上（弹窗过渡中、被 v-if 摘掉时取不到矩形） */
const visible = el => !!(el && el.getClientRects().length)

/** 亮一下被定位到的那一项：同一元素连点两次也要能再亮，所以先摘类再强制重排 */
function flash (el) {
  el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  el.classList.remove('wq-flash')
  void el.offsetWidth
  el.classList.add('wq-flash')
  setTimeout(() => el.classList.remove('wq-flash'), FLASH_MS)
}

/**
 * 按 data-fix 找到目标并滚进视口＋亮一下。
 *
 * <p>折叠展开与弹窗过渡期间元素位置一直在动，那时 scrollIntoView 滚的是中间态——
 * 实测系统设置里「展开装着它那一节」后立刻滚，会停在半路（目标还在视口下方 180px）。
 * 所以要求矩形连续两次一致（布局稳了）再动手。
 *
 * @param {string} fix 定位键（与目标元素上的 data-fix 对应）
 * @param {number} timeout 等多久（默认 2.5s，够弹窗过渡、展开一节与一次列表重拉）
 * @returns {Promise<boolean>} 是否定位成功；失败时调用方照常只把页面/弹窗打开
 */
export async function locateField (fix, timeout = DEFAULT_TIMEOUT) {
  if (!fix) return false
  const deadline = Date.now() + timeout
  let last = null
  for (;;) {
    const el = find(fix)
    const box = visible(el) ? el.getBoundingClientRect() : null
    if (box) {
      if (last && Math.abs(box.top - last.top) < 1 && Math.abs(box.height - last.height) < 1) {
        flash(el)
        return true
      }
      last = box
    } else {
      last = null
    }
    if (Date.now() > deadline) return false
    await new Promise(r => setTimeout(r, POLL_MS))
  }
}
