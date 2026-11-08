// ==================== 对外地址（分享 / 邀请 / 发布 / MCP / API 文档） ====================
// 这些链接必须能被浏览器之外的人打开，所以不能直接用 location.origin：
// 本地起 web（vite 5800）时它只会得到 http://localhost:5800，发给别人必然打不开。
// 基址直接由后端代理目标推导——本地页面连哪台后端，复制出去的链接就指向哪台。
// VITE_PROXY_TARGET 与 vite.config.js 的代理用的是同一个变量，Vite 会把它注入浏览器端。
// 未设置（默认代理本机 8090）或指向回环地址时，回到浏览器当前 origin（本机自己看）。
// 生产构建不设置该变量，即回落 location.origin（dist 与后端同域，正是想要的）。
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '0.0.0.0', '::1', '[::1]'])

const proxyTargetOrigin = () => {
  const target = String(import.meta.env.VITE_PROXY_TARGET || '').trim()
  if (!target) return ''
  try {
    const u = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(target) ? target : 'http://' + target)
    return LOOPBACK_HOSTS.has(u.hostname) ? '' : u.origin
  } catch (e) { return '' }
}

/** 对外基址：代理目标（非本机）优先，否则浏览器当前 origin */
export const publicOrigin = () => proxyTargetOrigin() || location.origin

/** 站内路径拼成对外绝对地址；已是 http(s)/协议相对地址的原样返回 */
export const publicUrl = path => {
  if (!path) return ''
  if (/^(https?:)?\/\//i.test(path) || path.startsWith('data:')) return path
  return publicOrigin() + (path.startsWith('/') ? path : '/' + path)
}
