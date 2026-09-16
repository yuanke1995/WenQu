// ==================== 身份与权限（前端） ====================
// 主路径：本地登录 → 持有 JWT（localStorage），请求头 Authorization: Bearer <token>。
// 兼容路径：VITE_ADMIN_TOKEN / localStorage('ai_admin_token') 作为管理员口令应急入口；
//           生产多用户也可由网关按 X-User-Id 白名单判定（AI_ADMIN_USERS）。
import { getAuthMe } from '../api'

const TOKEN_KEY = 'ai_token'
let cached = null // { user, username, role, admin } | null（会话内缓存，失效后由 ensureAuth 重拉）

export const getToken = () => {
  try { return localStorage.getItem(TOKEN_KEY) || '' } catch (e) { return '' }
}
export const isLoggedIn = () => Boolean(getToken())
export const setToken = token => {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch (e) { /* ignore */ }
}
export function clearAuth () {
  setToken('')
  cached = null
  try { localStorage.removeItem('ai_role') } catch (e) { /* ignore */ }
}

export const isAdminSync = () => {
  if (cached) return cached.admin
  try { return localStorage.getItem('ai_role') === 'admin' } catch (e) { return false }
}

/** 拉取/复用身份信息（含 admin 角色）；失败按未登录处理 */
export async function ensureAuth (force = false) {
  if (cached && !force) return cached
  try {
    const body = await getAuthMe()
    // api.js request() 返回整个 ResultJson（success/code/msg/data）——身份在 data 里
    const info = (body && typeof body === 'object' && body.data) || body || {}
    cached = {
      user: info.user || 'anonymous',
      username: info.username || '',
      role: info.role || 'user',
      admin: Boolean(info.admin)
    }
    try { localStorage.setItem('ai_role', cached.admin ? 'admin' : 'user') } catch (e) { /* ignore */ }
  } catch (e) {
    cached = { user: 'anonymous', username: '', role: 'user', admin: false }
  }
  return cached
}

/** 设置/清除管理员口令（应急入口，避免依赖重新构建）；调用后强制刷新身份 */
export function setAdminToken (token) {
  try {
    if (token) localStorage.setItem('ai_admin_token', token)
    else localStorage.removeItem('ai_admin_token')
  } catch (e) { /* ignore */ }
  cached = null
  return ensureAuth(true)
}
