// ==================== 身份与权限（前端） ====================
// 与后端 AdminGuard 配合：普通用户仅问答；文档/配置/评估/看板为管理员专属。
// 管理员凭据：VITE_ADMIN_TOKEN（构建注入）或运行时存入 localStorage('ai_admin_token')
// （对应后端 AI_ADMIN_TOKEN）；生产多用户也可不配口令，由网关 X-User-Id 白名单判定。
import { getAuthMe } from '../api'

let cached = null // { user, admin } | null（会话内缓存，失效后由 ensureAuth 重拉）

export const isAdminSync = () => {
  if (cached) return cached.admin
  try { return localStorage.getItem('ai_role') === 'admin' } catch (e) { return false }
}

/** 拉取/复用身份信息（含 admin 角色）；失败按普通用户处理（管理入口自然隐藏） */
export async function ensureAuth(force = false) {
  if (cached && !force) return cached
  try {
    cached = await getAuthMe()
    try { localStorage.setItem('ai_role', cached?.admin ? 'admin' : 'user') } catch (e) { /* ignore */ }
  } catch (e) {
    cached = { user: 'anonymous', admin: false }
  }
  return cached
}

/** 设置/清除管理员口令（运行时切换，避免依赖重新构建）；调用后强制刷新身份 */
export function setAdminToken(token) {
  try {
    if (token) localStorage.setItem('ai_admin_token', token)
    else localStorage.removeItem('ai_admin_token')
  } catch (e) { /* ignore */ }
  cached = null
  return ensureAuth(true)
}
