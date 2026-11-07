/**
 * 权限域展示工具：业务域分组、中文名回退、三态统计、菜单树。
 * <p>分组按 code 前缀（业务域）而不是 Controller module——同一业务动作跨 Controller
 * 复用时仍落在一组里。目录里的 name 由后端扫描器回填中文名，缺失时回落编码。</p>
 */

/** code 前缀 → 业务域名。未收录的回落前缀本身，保持可读且不吞信息。 */
export const DOMAIN_LABELS = {
  agent: '智能体',
  'api-endpoint': '接口管理',
  apikey: 'API 密钥',
  artifact: '产物',
  audit: '审计',
  chat: '对话',
  config: '系统配置',
  'content-safety': '内容安全',
  credential: '凭据',
  department: '部门',
  'desc-cache': '描述缓存',
  document: '文档',
  eval: '检索评估',
  graph: '知识图谱',
  identity: '认证源',
  kb: '知识库',
  knowledge: '知识库',
  manual: '手册',
  market: '市场',
  mcp: 'MCP 工具',
  menu: '菜单',
  ocr: '解析引擎',
  ops: '运维',
  project: '工作区',
  provider: '模型供应商',
  quota: '配额',
  'retrieval-debug': '检索调试',
  role: '角色与权限',
  sandbox: '沙箱',
  schedule: '定时任务',
  scheduled: '定时任务',
  'search-index': '搜索索引',
  self: '个人设置',
  'service-account': '服务账号',
  session: '会话',
  skill: '技能',
  stats: '使用统计',
  tenant: '租户',
  trace: '链路追踪',
  user: '成员',
  webpush: '消息推送',
  workflow: '工作流'
}

/** 授权点编码 → 业务域键 */
export function domainKey (code) {
  const s = String(code || '')
  const i = s.indexOf(':')
  return i > 0 ? s.slice(0, i) : (s || 'other')
}

/** 业务域键 → 展示名 */
export function domainLabel (key) {
  return DOMAIN_LABELS[key] || (key === 'other' ? '其他' : key)
}

/** 授权点可读名（目录 name 与 code 同形时视为「未回填」，返回空） */
export function permissionName (item) {
  const name = item && item.name
  return name && name !== item.code ? String(name) : ''
}

/** 授权点主标题：中文名优先，缺失回落编码 */
export function permissionTitle (item) {
  return permissionName(item) || String((item && item.code) || '')
}

/** 按业务域聚合授权点目录，组内按编码排序，组间按中文名排序 */
export function buildPermissionGroups (catalog) {
  const by = new Map()
  for (const p of (catalog || [])) {
    if (!p || !p.code) continue
    const key = domainKey(p.code)
    if (!by.has(key)) by.set(key, [])
    by.get(key).push(p)
  }
  return [...by.entries()]
    .map(([key, items]) => ({
      key,
      label: domainLabel(key),
      items: items.slice().sort((a, b) => String(a.code).localeCompare(String(b.code))),
      ownedCount: items.length
    }))
    .sort((a, b) => a.label.localeCompare(b.label, 'zh'))
}

/** 三态状态统计：{ allow, deny, none } */
export function permissionStats (state) {
  let allow = 0
  let deny = 0
  for (const value of Object.values(state || {})) {
    if (value === 'allow') allow++
    else if (value === 'deny') deny++
  }
  return { allow, deny }
}

/** 平铺菜单 → 树（孤儿节点当顶级；与权限页既有 buildTree 同口径） */
export function buildMenuTree (list) {
  const byId = new Map((list || []).map(m => [m.id, { ...m, children: undefined }]))
  const roots = []
  for (const node of byId.values()) {
    const parent = node.parentId ? byId.get(node.parentId) : null
    if (parent && parent.id !== node.id) {
      parent.children = parent.children || []
      parent.children.push(node)
    } else roots.push(node)
  }
  return roots
}

/** 菜单子树（含自身）id 集合 */
export function menuSubtreeIds (menuList, menuId) {
  const children = new Map()
  for (const m of (menuList || [])) {
    const pid = m.parentId || ''
    if (!children.has(pid)) children.set(pid, [])
    children.get(pid).push(m.id)
  }
  const out = new Set([menuId])
  const stack = [menuId]
  while (stack.length) {
    const cur = stack.pop()
    for (const child of (children.get(cur) || [])) {
      if (!out.has(child)) { out.add(child); stack.push(child) }
    }
  }
  return out
}

/** 数据范围文案 */
export function scopeLabel (scopeType) {
  return {
    own: '本人创建的',
    department: '本部门创建的（可读）',
    department_and_children: '本部门及下级创建的（可读）',
    all: '全部资源（只读）'
  }[scopeType] || '本人创建的'
}
