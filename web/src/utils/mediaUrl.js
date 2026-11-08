// 后端返回的媒体地址（/ai/images/**、/ai/artifacts/**）解析：
//   开发态：vite dev server 用 /proxy 转发到后端 context-path(/ai)，故需剥掉 /ai 再挂 /proxy；
//   生产态：nginx 已有 location /ai/ 直通后端，路径原样可用。
// 前缀由 VITE_MEDIA_BASE 决定（生产为空串＝原样返回），不可无条件拼 /proxy——
// 线上 /proxy/images/** 会落进 SPA 回退返回 index.html，浏览器拿 HTML 当图片解码必然失败。
// 独立成模块是为了让头像/Logo 这类轻量组件也能复用，不必把 markdown 渲染整条依赖链拉进来。
const MEDIA_BASE = import.meta.env.VITE_MEDIA_BASE ?? ''

/** 图片 URL 兼容（data:/http 原样；相对路径按环境决定是否加前缀） */
export const resolveMediaUrl = u => {
  if (!u) return u
  if (u.startsWith('data:') || u.startsWith('http')) return u
  return MEDIA_BASE ? MEDIA_BASE + u.replace(/^\/ai/, '') : u
}
