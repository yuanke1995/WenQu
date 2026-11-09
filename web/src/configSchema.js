// 配置字段定义（运行时由后端下发，前端不再持有副本）
//
// 字段/面板/文案的唯一来源是后端的 src/main/resources/config-schema.json
// （经 GET /api/ai/config/schema 下发）：同一份定义同时驱动前端渲染与后端保存校验。
// 此前这里是一份 78 字段的硬编码数组，与后端逐个手工比对来维持一致——
// 一旦漏改就会出现"设置页少字段"或"提交被后端静默丢弃"。
// 字段定义 ↔ ConfigService.defaults() 的差集现在由启动对账兜住：错位会打 [Config][对账] WARN，
// 也可查 GET /api/ai/config/trace。
//
// 下面三个容器保持"引用不变"：applyServerSchema() 填充后，所有消费方
// （SettingsPage.vue / SchemaField.vue）无需改造成异步读取。

export const TIPS = {}
export const PANELS = []
export const FIELDS = []

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
  for (const k of Object.keys(TIPS)) delete TIPS[k]
  Object.assign(TIPS, payload.tips || {})
}

/**
 * 工程参数：tier ≥ 3（工程排障）或被 schema 标了 hidden 的字段。
 * hidden 用于「键还得留在 fields 里（提交与校验要认），但不该摆在设置页面上」的字段——
 * 例如超时档卡的四个单项，删出 schema 后档位卡的值就提交不上去了（见 buildPayload）。
 */
export const isEngineField = f => Number(f.tier ?? 2) >= 3 || f.hidden === true

/**
 * 已停用字段（schema 标 deprecated，note 以「【已停用】」开头）：后端已无读取点，
 * 键留在 fields 里只为提交/校验认账与回滚窗口——**任何时候都不该摆上设置页**，
 * 包括铺开工程参数时：排障的人面对一个「改了没人读」的旋钮只会绕远路。
 */
export const isDeprecatedField = f => f.deprecated === true

/**
 * 按面板取渲染块：分节标题与字段按顺序交织，自定义块由调用方插入。
 * @param form 传入时按 vif 过滤不可见字段——某节字段全被隐藏时标题一并隐藏
 *             （如 MCP「安全与限频」挂在「对外提供端点」开关下）
 * @param showEngine 假时收起工程参数。**过滤只准落在这里（渲染层）**：
 *                   buildPayload / dirtyCount / panelDefaultPayload 都以 FIELDS 全集为输入，
 *                   所收起的字段照样回填、照样脏检测、照样被「恢复本组默认」覆盖；
 *                   把过滤下沉到那几处会直接变成「改了没存上」和「重置漏项」。
 */
export function blocksOf (panel, form = null, showEngine = false) {
  const p = PANELS.find(x => x.key === panel)
  // 面板未登记时返回空块而不是读 p.sections 崩掉：panel 由调用方决定（可能是分组 key、
  // 也可能是尚未从后端到达的 key），崩在这里会连带整个设置页白屏并吞掉真实报错
  if (!p) return []
  const blocks = []
  const keep = f => (!form || isVisible(f, form)) && !isDeprecatedField(f) && (showEngine || !isEngineField(f))
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

/** 读取 form 内嵌套路径，如 readForm(form, 'retrieval.vectorWeight') */
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

/** 条件显示：vif 形如 "a.b"、"a.b && c.d" 或 "a.b=v1,v2"（取值命中任一即显示，用于枚举联动） */
export function isVisible (field, form) {
  if (!field.vif) return true
  for (const cond of field.vif.split('&&').map(s => s.trim())) {
    const eq = cond.indexOf('=')
    if (eq > 0) {
      const actual = String(readForm(form, cond.slice(0, eq).trim()) ?? '')
      if (!cond.slice(eq + 1).split(',').map(s => s.trim()).includes(actual)) return false
    } else if (!readForm(form, cond)) return false
  }
  return true
}
