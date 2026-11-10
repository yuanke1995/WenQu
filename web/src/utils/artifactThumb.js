// 产物卡「首屏缩略」的取数与排队口径。
//
// 三件事只在这里做，ArtifactsPage 与对话产物卡共用：
//   1) 文本原文 → 缩略行（Markdown 标记在这个尺寸只会变成噪音，先洗掉）
//   2) 表单形 JSON 的识别（只判断结构、不渲染真实表单，避免第二套表单口径）
//   3) 用量闸：网页 iframe 同屏最多 2 帧、文本拉取并发 3 且按产物 id 缓存。
//
// 为什么必须限量：对话是长列表，每轮都可能带产物；不限量时一屏能同时跑十几份网页文档，
// 文本也会把同一批接口打满。产物文件不可变，按 id 缓存不会拿到旧内容。

import { artifactContent } from '../api'
import { pickPreviewLines, looksLikeFormJson } from './artifactPreview'

const FRAME_LIMIT = 2
const TEXT_LIMIT = 3

// ==================== 网页缩略槽位 ====================
const frameOrder = []
const frameActive = new Set()
const frameListeners = new Set()

const reconcileFrames = () => {
  const next = new Set(frameOrder.slice(0, FRAME_LIMIT))
  let changed = next.size !== frameActive.size
  if (!changed) {
    for (const id of next) {
      if (!frameActive.has(id)) { changed = true; break }
    }
  }
  frameActive.clear()
  next.forEach(id => frameActive.add(id))
  if (!changed) return
  for (const fn of [...frameListeners]) {
    try { fn() } catch (e) { /* 单个订阅者出错不影响其它卡 */ }
  }
}

/** 这张卡进入/离开视口：进入排进队列，前 2 名拿到 iframe 槽位；离开立即释放 */
export const setFrameVisible = (id, visible) => {
  if (!id) return
  const has = frameOrder.includes(id)
  if (visible && !has) frameOrder.push(id)
  if (!visible && has) {
    const i = frameOrder.indexOf(id)
    if (i >= 0) frameOrder.splice(i, 1)
  }
  reconcileFrames()
}

/** 当前这张卡是否拿到了 iframe 槽位 */
export const isFrameActive = id => !!id && frameActive.has(id)

/** 槽位变化通知：组件据此重算自己是否该挂 iframe */
export const onFrameSlots = fn => {
  frameListeners.add(fn)
  return () => frameListeners.delete(fn)
}

// ==================== 文本缩略 ====================
const textCache = new Map()
const textQueue = []
let textBusy = 0

const normalizeText = raw => ({
  raw,
  lines: pickPreviewLines(raw),
  form: looksLikeFormJson(raw)
})

const pumpText = () => {
  while (textBusy < TEXT_LIMIT && textQueue.length) {
    const job = textQueue.shift()
    textBusy += 1
    artifactContent(job.id)
      .then(res => job.resolve(normalizeText((res && res.data && res.data.content) || '')))
      .catch(() => {
        // 失败不进缓存：鉴权尚未就绪/网络抖动时缓存空结果，会让这张卡到刷新前永远是徽标。
        // 也不自动重试——组件下次挂载（切会话/刷新）再取一次即可。
        textCache.delete(job.id)
        job.resolve({ raw: '', lines: [], form: false })
      })
      .finally(() => { textBusy -= 1; pumpText() })
  }
}

/**
 * 取一份产物的文本缩略。登录态才可用（分享页没有 artifact id，调用方应直接降级）。
 * 失败不重试：长会话里反复打同一个失败请求只会更糟，刷新后缓存自然重置。
 */
export const loadThumbText = id => {
  if (!id) return Promise.resolve({ raw: '', lines: [], form: false })
  const hit = textCache.get(id)
  if (hit) return hit
  const p = new Promise(resolve => {
    textQueue.push({ id, resolve })
    pumpText()
  })
  textCache.set(id, p)
  return p
}
