// 工具卡标题摘要、入参/输出格式、运行中实时输出。纯函数，不读 .value。
import { consultNameOf, isDispatchTool } from './consult.js'
import { toolLabel } from './tools.js'

export const TOOL_BRIEF_KEYS = ['command', 'query', 'path', 'dir', 'filename', 'url', 'kbIds', 'message']
const SKILL_READ_TOOL_NAMES = new Set(['load_skill_through_path', 'read_skill', 'use_skill'])

const bareSkillNameOf = id => {
  const s = String(id || '').trim()
  if (!s) return ''
  const i = s.lastIndexOf('_/')
  if (i > 0) return s.slice(0, i)
  const j = s.lastIndexOf('_')
  return j > 0 ? s.slice(0, j) : s
}

export const oneLine = (s, n) => {
  const x = String(s).replace(/\s+/g, ' ').trim()
  return x.length > n ? x.slice(0, n) + '…' : x
}

export const toolBrief = t => {
  // 早产卡：入参还在生成，只能报「正在生成多少字」；后端边生成边捞出的 brief 优先
  if (t && t.preparing) {
    const n = Number(t.chars) || 0
    const state = n > 0 ? '正在生成 ' + n.toLocaleString() + ' 字' : '正在生成内容'
    return t.brief ? oneLine(t.brief, 40) + ' · ' + state : state
  }
  if (!t?.args) return ''
  let obj = null
  try { obj = JSON.parse(t.args) } catch (e) { return oneLine(t.args, 56) } // 非 JSON（MCP 兼容）：纯文本截断
  // 派遣助手：标题行亮出「问的是谁」
  if (isDispatchTool(t.name)) {
    const who = consultNameOf(t)
    if (who) return oneLine(who, 56)
  }
  if (!obj || typeof obj !== 'object') return oneLine(t.args, 56)
  if (SKILL_READ_TOOL_NAMES.has(t.name)) {
    const nm = bareSkillNameOf(obj.skillId || obj.skill_id || obj.skill_name || obj.skillName || obj.name)
    if (nm) return oneLine(nm, 56)
  }
  for (const k of TOOL_BRIEF_KEYS) {
    const v = obj[k]
    if (typeof v === 'string' && v.trim()) return oneLine(v, 56)
    if (Array.isArray(v) && v.length) return oneLine(JSON.stringify(v), 56)
  }
  for (const k of Object.keys(obj)) { // 兜底：第一个非空字符串字段（跳过 content/newString 等大文本）
    if (k === 'content' || k === 'newString') continue
    const v = obj[k]
    if (typeof v === 'string' && v.trim()) return oneLine(v, 56)
  }
  return oneLine(t.args, 48)
}

export const prettyIo = s => {
  if (s == null || s === '') return ''
  try {
    const o = JSON.parse(s)
    return o && typeof o === 'object' ? JSON.stringify(o, null, 2) : String(s)
  } catch (e) { return String(s) }
}

export const LIVE_TAIL_CHARS = 4000

/** 运行中只展示尾部（避免无界增长）；结束后展示落库的最终全文。 */
export const liveOutput = t => {
  if (t.status === 'start') {
    if (t.preparing) {
      const n = Number(t.chars) || 0
      return n > 0 ? '正在生成内容…（已写入 ' + n.toLocaleString() + ' 字）' : '正在生成内容…'
    }
    if (!t.liveOutput) return ''
    return (t.outputTruncated ? '…（前面输出已省略）\n' : '') + t.liveOutput.slice(-LIVE_TAIL_CHARS)
  }
  return t.result != null ? t.result : ''
}

/** 分支里那一步的一行文案：工具名 + 关键参数，与主管线工具卡同一把尺。 */
export const stepBriefOf = c => {
  if (!c || !c.name) return ''
  const brief = toolBrief(c)
  const label = toolLabel(c.name, c.label)
  return brief ? label + ' · ' + brief : label
}
