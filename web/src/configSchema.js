// 配置字段定义（运行时由后端下发，前端不再持有副本）
//
// 字段/面板/文案/核心项的唯一来源是后端的 src/main/resources/config-schema.json
// （经 GET /api/ai/config/schema 下发）：同一份定义同时驱动前端渲染与后端保存校验。
// 此前这里是一份 78 字段的硬编码数组，靠 scripts/gen_config_schema.js 与后端逐个比对来维持一致——
// 一旦漏改就会出现"设置页少字段"或"提交被后端静默丢弃"（脚本自身的正则还解析不了带点的键名）。
//
// 下面四个容器保持"引用不变"：applyServerSchema() 填充后，所有消费方
// （SettingsPage.vue / SchemaField.vue）无需改造成异步读取。

export const TIPS = {}
export const PANELS = []
export const FIELDS = []
export const CORE_PATHS = new Set()

/**
 * 用后端下发的定义填充容器。必须在渲染任何字段之前调用（设置页加载时调用一次）。
 * 定义为空时**显式失败**：静默渲染空表单会让"设置页空白"变成难排查的问题。
 */
export function applyServerSchema (payload) {
  if (!payload || !Array.isArray(payload.fields) || payload.fields.length === 0) {
    throw new Error('配置字段定义为空：后端 /config/schema 未返回字段')
  }
  FIELDS.length = 0
  FIELDS.push(...payload.fields)
  PANELS.length = 0
  PANELS.push(...(payload.panels || []))
  CORE_PATHS.clear()
  for (const p of payload.corePaths || []) CORE_PATHS.add(p)
  for (const k of Object.keys(TIPS)) delete TIPS[k]
  Object.assign(TIPS, payload.tips || {})
}

/** 字段是否属核心（基础模式可见）；path 缺省时按 group.key 拼 */
export function isCoreField (f) {
  const p = f.path || (f.group + '.' + f.key)
  return CORE_PATHS.has(p)
}

/** 基础模式下可见的面板（含至少一个核心字段的面板） */
export function corePanels () {
  return PANELS.filter(p => FIELDS.some(f => f.panel === p.key && !f.groupedUnder && isCoreField(f)))
}

/** 某面板在基础模式下被隐藏的字段数（用于提示"还有 N 项高级配置"） */
export function hiddenFieldCount (panel) {
  return FIELDS.filter(f => f.panel === panel && !f.groupedUnder && !isCoreField(f)).length
}

/**
 * 按面板取渲染块：分节标题与字段按顺序交织，自定义块由调用方插入。
 * @param coreOnly true=基础模式，只渲染核心字段（该节全被隐藏时不输出标题）
 */
export function blocksOf (panel, coreOnly = false) {
  const p = PANELS.find(x => x.key === panel)
  const blocks = []
  const keep = f => !coreOnly || isCoreField(f)
  for (let i = 0; i < p.sections.length; i++) {
    const fs = FIELDS.filter(f => f.panel === panel && f.section === i && !f.groupedUnder && keep(f))
    if (!fs.length) continue
    blocks.push({ type: 'sub', title: p.sections[i] })
    for (const f of fs) blocks.push({ type: 'field', field: f })
  }
  for (const f of FIELDS.filter(f => f.panel === panel && f.section === -1 && !f.groupedUnder && keep(f))) blocks.unshift({ type: 'field', field: f })
  return blocks
}

/** 由 schema 生成 form 默认值对象（替代手写 form） */
export function buildDefaultForm () {
  const form = {}
  const set = (path, v) => {
    const seg = path.split('.')
    let o = form
    for (let i = 0; i < seg.length - 1; i++) { o[seg[i]] = o[seg[i]] || {}; o = o[seg[i]] }
    o[seg[seg.length - 1]] = v
  }
  for (const f of FIELDS) set(f.path || (f.group + '.' + f.key), f.def)
  return form
}

/** 读取 form 内嵌套路径，如 readForm(form, 'retrieval.rerank.enabled') */
export function readForm (obj, path) {
  return path.split('.').reduce((a, k) => (a == null ? a : a[k]), obj)
}

/** 写入 form 内嵌套路径（中间层级自动创建） */
export function writeForm (obj, path, value) {
  const seg = path.split('.')
  let t = obj
  for (let i = 0; i < seg.length - 1; i++) {
    if (t[seg[i]] == null) t[seg[i]] = {}
    t = t[seg[i]]
  }
  t[seg[seg.length - 1]] = value
}

/** 条件显示：vif 形如 "vision.enabled" 或 "a.b && c.d" */
export function isVisible (field, form) {
  if (!field.vif) return true
  for (const cond of field.vif.split('&&').map(s => s.trim())) {
    const seg = cond.split('.')
    let v = form
    for (const s of seg) { if (v == null) return false; v = v[s] }
    if (!v) return false
  }
  return true
}
