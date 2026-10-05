// ==================== 复制文本（PC 与移动共用） ====================
// 两条路径都必须保留，不是"降级"：
//  1. navigator.clipboard.writeText —— 现代浏览器首选（异步、不污染 DOM）；
//  2. execCommand('copy') —— 微信内置浏览器与非 HTTPS 源下 clipboard API 根本不存在
//     （navigator.clipboard 为 undefined），只走第 1 条会直接失败。移动端的复制需求
//     大多就落在这些环境里，所以第 2 条是真实主路径之一。
import { message } from 'ant-design-vue'

const legacyCopy = txt => {
  const ta = document.createElement('textarea')
  ta.value = txt
  ta.setAttribute('readonly', '')
  ta.style.position = 'absolute'
  ta.style.left = '-9999px'
  document.body.appendChild(ta)
  ta.focus()
  ta.select()
  ta.setSelectionRange(0, txt.length)
  let ok = false
  try { ok = document.execCommand('copy') } catch (e) { ok = false }
  ta.remove()
  return ok
}

/** 写入剪贴板并返回是否成功（不弹提示，给调用方决定是否提示） */
export const writeClipboard = async txt => {
  if (!txt) return false
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(txt); return true } catch (e) { /* 权限/非安全上下文：落到 execCommand */ }
  }
  return legacyCopy(txt)
}

/** 复制并给出结果提示 */
export const copyText = async (txt, okMsg = '已复制到剪贴板') => {
  if (!txt) { message.warning('没有可复制的内容'); return false }
  const ok = await writeClipboard(txt)
  if (ok) message.success(okMsg)
  else message.error('复制失败，请长按选择文本')
  return ok
}
