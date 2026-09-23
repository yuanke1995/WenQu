/**
 * 知识库图片的 Blob URL 缓存。
 *
 * <p>为什么需要：知识库图片走「带鉴权头的 fetch → Blob → objectURL」这条路
 * （{@code <img>} 无法自带 Authorization 头，所以不能把图片 URL 直接写进 src）。
 * 而流式回答期间 Markdown 每收到一个增量就整块重建 DOM，<img> 节点随之销毁重建；
 * 若每次重建都重新下载，会产生两个可见后果：
 * <ul>
 *   <li>上一次刚拿到的 objectURL 被 revoke，正在显示的图片立刻变空白，再重新解码显示 —— 画面闪烁。</li>
 *   <li>流还在推进时，下载总被下一次重渲染打断，只有流停住（回答结束）的那一帧
 *       才来得及完成加载 —— 图片看上去是「回答完才一次性出现」。</li>
 * </ul>
 *
 * <p>因此这里按「原始图片 URL」缓存下载好的 objectURL：
 * <ul>
 *   <li>同一地址只发一次网络请求（并发重复请求合并为同一个 Promise）；</li>
 *   <li>命中缓存时<b>同步</b>返回，重渲染后 {@code img.src} 立即指向已就绪的资源，
 *       不经过「空 → 请求 → 解码」的窗口，也就不会闪。</li>
 * </ul>
 *
 * <p>缓存按 LRU 控制在容量上限内，被淘汰时才 revoke 对应的 objectURL。
 */
const MAX_ENTRIES = 64

/** src → objectURL（Map 的插入顺序即 LRU 顺序，末尾为最近使用）。 */
const cache = new Map()

/** src → 进行中的下载 Promise（合并并发请求）。 */
const inflight = new Map()

/**
 * 判定是否为「已完成」的知识库图片代理地址。
 *
 * <p>流式输出过程中 <code>&lt;img src="/api/knowledge/..."&gt;</code> 的 URL 是逐字符到达的，
 * 半截地址同样能通过前缀匹配 —— 若不加行尾约束，URL 每增长一个字符都会被当成一张新图片
 * 去下载，形成请求风暴。故这里要求整段地址（可含查询串）已经闭合。
 */
export const isKbImageProxyPath = (src) =>
  /^\/api\/knowledge\/databases\/[^/]+\/images\/[^"'<>)\s]+$/.test(String(src || ''))

/** 命中缓存返回 objectURL，未命中返回 null；命中会刷新 LRU 顺序。 */
export const getCachedKbImageUrl = (src) => {
  if (!cache.has(src)) return null
  const objectUrl = cache.get(src)
  cache.delete(src)
  cache.set(src, objectUrl)
  return objectUrl
}

/**
 * 取知识库图片的 objectURL：优先缓存，未命中且有并发请求时复用该请求，否则发起下载。
 *
 * @param {string} src 以 {@code /api/knowledge/...} 开头的图片代理地址
 * @param {Record<string, string>} headers 鉴权头
 * @returns {Promise<string>} 可直接赋给 {@code img.src} 的 objectURL
 */
export const loadKbImageUrl = async (src, headers) => {
  const cached = getCachedKbImageUrl(src)
  if (cached) return cached

  const running = inflight.get(src)
  if (running) return running

  const task = (async () => {
    const response = await fetch(src, { headers })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const objectUrl = URL.createObjectURL(await response.blob())
    cache.set(src, objectUrl)
    // 超出容量时淘汰最久未使用的条目，只有真正被淘汰才释放 objectURL。
    while (cache.size > MAX_ENTRIES) {
      const oldest = cache.keys().next().value
      URL.revokeObjectURL(cache.get(oldest))
      cache.delete(oldest)
    }
    return objectUrl
  })()

  inflight.set(src, task)
  try {
    return await task
  } finally {
    inflight.delete(src)
  }
}
