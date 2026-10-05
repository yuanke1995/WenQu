// ==================== Service Worker（PWA：安装能力 + 静态资源离线复用） ====================
// 职责刻意收得很窄：**只缓存带内容 hash 的 /assets/***（JS/CSS/字体等构建产物）。
//
// 为什么不缓存别的东西：
//   ① /s/:token 与 /shared/:token 是免登录分享，token 就在 URL 里——缓存这些页面等于把
//      别人的对话内容落到设备磁盘（这是上一版「刻意不引 SW」的顾虑，本版用范围裁剪消解）。
//   ② HTML（含 index.html 与各路由壳）永不缓存：否则部署新版本后用户会一直拿到旧壳，
//      指向已被清理的旧 assets → 白屏。壳走网络、资源走缓存，才能既快又不会陈旧。
//   ③ API（/api/**）不碰：会话与流式内容永远走网络，不进磁盘。
//
// 内容 hash 是这套策略成立的前提：assets 文件名随内容变化 ⇒ 新版本 = 新 URL = 缓存未命中，
// 天然没有「缓存了旧 JS」的问题；旧文件随 CACHE 版本号变更在 activate 时整体清掉。
const CACHE = 'wq-assets-v1'
// 只有这些路径进缓存（同源 GET）：
const CACHEABLE = /^\/assets\//
const NEVER = /^\/(s|shared|api)\//

self.addEventListener('install', e => {
  // 不预缓存任何东西：首次访问时按需填充，避免安装期拉一堆用不到的 chunk
  self.skipWaiting()
})

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    // 清理旧版本缓存（CACHE 版本号变更时生效）
    const keys = await caches.keys()
    await Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    await self.clients.claim()
  })())
})

self.addEventListener('fetch', e => {
  const req = e.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return          // 跨域（供应商/图片等）不碰
  if (NEVER.test(url.pathname)) return                     // 分享页与 API 明确排除
  if (!CACHEABLE.test(url.pathname)) return                // 只接管 /assets/*

  // 缓存优先：命中直接回，未命中取网络并落缓存；失败（离线）时若有旧副本则回退
  e.respondWith((async () => {
    const cache = await caches.open(CACHE)
    const hit = await cache.match(req)
    if (hit) return hit
    try {
      const res = await fetch(req)
      // 只缓存成功响应；opaque/206 之类不落盘，避免半截文件
      if (res && res.status === 200 && res.type === 'basic') cache.put(req, res.clone())
      return res
    } catch (err) {
      const fallback = await cache.match(req)
      if (fallback) return fallback
      throw err
    }
  })())
})
