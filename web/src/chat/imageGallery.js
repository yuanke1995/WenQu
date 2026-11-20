// 灯箱取图：只收「这条正文里真正渲染出来的图片」，按正文 DOM 顺序，
// 不收本轮 images 全局池里正文没引用的图。
// 后端 images 是本轮全部命中块配图的编号清单（imgIndex），正文 [图片N] 往往只引用其中几张；
// 整表交给灯箱会翻出正文里根本没有的图（回答 4 张、灯箱 17 张）。
// 另外点非 [图片N] 的原生 markdown 图片时旧代码拿不到 data-seq → 索引回落 0，
// 明明点的是最后一张却显示 1/17；这里按 DOM 下标定位点击图，顺序与正文完全一致。
// entries: [{ seq, src }] —— seq 来自 renderMd 给 [图片N] 写的 data-seq；0 表示原生图片。
// clicked: 被点中的那个 entry（同一份对象）。零依赖纯函数，便于 node --test 钉住行为。
export const galleryFromRendered = (entries, images, clicked, resolveImg = u => u) => {
  const list = Array.isArray(images) ? images : []
  const urlOf = e => {
    const seq = Number(e && e.seq) || 0
    if (seq >= 1 && seq <= list.length) return resolveImg(list[seq - 1]) || ''
    return (e && e.src) || ''
  }
  const urls = []
  const seen = new Set()
  for (const e of (Array.isArray(entries) ? entries : [])) {
    const url = urlOf(e)
    if (!url || seen.has(url)) continue
    seen.add(url)
    urls.push(url)
  }
  const index = urls.indexOf(urlOf(clicked))
  return { urls, index: index >= 0 ? index : 0 }
}
