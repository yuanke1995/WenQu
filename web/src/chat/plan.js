/**
 * 执行计划的结构化投影（纯函数，无 UI 依赖）。
 *
 * 计划正文是模型自由写的 markdown，卡片和计划视图都需要「目标一句 + 步骤若干」才扫得快。
 * 这里只做**尽力解析**：认得出列表就出步骤，认不出就退回原文摘要——不为了结构去改模型输出，
 * 也不把解析失败当成错误（卡片会自然退化成正文预览）。
 */

const LIST_ITEM = /^(\s*)(?:[-*+]|\d{1,2}[.)、])\s+(.*)$/
// 常见封面词与空段落不当作目标句（否则卡片顶上会出现「执行计划」「目标」这类标签）
const NOT_GOAL = /^(?:执行计划|计划|目标|概述|背景|说明|步骤|执行步骤)[：:]?$/

const stripMd = s => String(s || '')
  .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
  .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
  .replace(/[*_`~]{1,3}/g, '')
  .replace(/\s+/g, ' ')
  .trim()

/** 一行步骤拆成「标题 + 补充」：优先冒号，其次破折号；都没有就整行当标题 */
function stepOf (body) {
  const s = stripMd(body).replace(/^步骤\s*\d+\s*[：:、.]?\s*/, '')
  const hit = s.match(/^(.{2,42}?)[：:]\s*(.+)$/) || s.match(/^(.{2,42}?)\s+[—–-]\s+(.+)$/)
  if (hit) return { title: hit[1].trim().replace(/[。；;，,]$/, ''), detail: hit[2].trim() }
  return { title: s.replace(/[。；;]$/, ''), detail: '' }
}

/**
 * 计划 → { goal, steps, prose }。
 * goal = 第一段非列表、非标题的正文（没有就空串）；steps = 顶层列表项（缩进项并入上一步的 detail）；
 * prose = 被结构化吸收掉的原文（给计划视图留「完整原文」用）。
 */
export function planOutline (plan) {
  const text = String(plan || '')
  const lines = text.split('\n')
  let goal = ''
  const steps = []
  let lastStep = null
  for (const raw of lines) {
    const line = raw.trim()
    if (!line) continue
    const li = raw.match(LIST_ITEM)
    if (li) {
      const indent = li[1].replace(/\t/g, '  ').length
      const body = li[2]
      if (indent >= 2 && lastStep) {
        // 缩进子项：并进上一步的补充说明，不另起一步
        const sub = stripMd(body)
        if (sub) lastStep.detail = lastStep.detail ? lastStep.detail + '；' + sub : sub
        continue
      }
      const step = stepOf(body)
      if (!step.title) continue
      steps.push(step)
      lastStep = step
      continue
    }
    if (/^#{1,6}\s/.test(line)) continue
    if (!goal) {
      const clean = stripMd(line.replace(/^#{1,6}\s*/, ''))
      if (clean && !NOT_GOAL.test(clean)) goal = clean
      continue
    }
    // 目标之后的普通段落：若有步骤，作为最后一步的补充；否则并进目标
    if (lastStep) {
      const add = stripMd(line)
      if (add) lastStep.detail = lastStep.detail ? lastStep.detail + '；' + add : add
    }
  }
  return { goal, steps, prose: text }
}

/** 只取步骤数（空计划=0），右栏与卡脚共用 */
export const planStepCount = plan => planOutline(plan).steps.length

/** 计划里「目标 + 步骤」这段是否短到可以原样铺开（卡片折叠判据的替代：现在一律三段式） */
export const planHasStructure = plan => {
  const o = planOutline(plan)
  return !!(o.goal || o.steps.length)
}

/**
 * 两版计划的步骤差异（够 UI 用即可，不追求逐字 diff）：
 * 同位置标题不同=已修改；新版多出=新增；旧版多出=删除。返回按**当前版**下标标注的集合。
 */
export function diffSteps (prevPlan, curPlan) {
  const prev = planOutline(prevPlan).steps
  const cur = planOutline(curPlan).steps
  const changed = new Set()
  const added = new Set()
  const removed = []
  const n = Math.min(prev.length, cur.length)
  for (let i = 0; i < n; i++) {
    if (prev[i].title !== cur[i].title || (prev[i].detail || '') !== (cur[i].detail || '')) changed.add(i)
  }
  for (let i = n; i < cur.length; i++) added.add(i)
  for (let i = n; i < prev.length; i++) removed.push(prev[i].title)
  return { changed, added, removed, changedCount: changed.size, addedCount: added.size, removedCount: removed.length }
}

/** 版本链：card.versions（旧版）+ card.plan（当前版），每项带 feedback */
export function planChain (card) {
  const vs = Array.isArray(card?.versions) ? card.versions : []
  return [...vs, { plan: card?.plan || '', feedback: card?.feedback || '' }]
}

/**
 * 计划卡是否铺开成三段式（否则收成一行）。
 * live（待确认/生成中）恒展开：那是裁决入口，藏起来等于把「计划要批」摁掉。
 * 批准之后看用户表态：点过就按点的（本轮正在跑也照收），没点过则在执行中自动铺开、跑完收回一行。
 * 「没点过」必须是 undefined 而不是 false，否则自动展开期间点收起会被记成「展开」，那颗折叠键就按不动。
 */
export const planCardExpanded = ({ live, running, choice } = {}) => {
  if (live) return true
  return choice === undefined ? !!running : !!choice
}

/**
 * 计划批准后的执行进度：拿会话任务清单当数据源（清单本来就是这一轮的执行项）。
 * 只有「计划已批准 + 清单存在 + 清单属于这一轮」才返回进度；否则 null——
 * 卡片退回计划步骤预览，绝不按工具调用数去编造「第几步已完成」。
 * 唯一例外：本轮的实时清单正在跑（running）时一律认它——此刻底部常驻清单展示的也是它，
 * 卡内读数不能跑去对另一份快照，否则同屏两个进度数各说各话。
 */
export function planExecProgress (card, todos, turnId, running, snapshot = null) {
  if (!card || card.status !== 'approved') return null
  const items = (todos && Array.isArray(todos.items)) ? todos.items : []
  const matched = items.length && (!!running || !(todos.turnId && turnId && todos.turnId !== turnId))
  if (matched) {
    const mapped = items.map(i => ({
      text: String(i.content || ''),
      status: i.status === 'done' ? 'done'
        : (i.status === 'doing' ? 'doing' : (i.status === 'failed' ? 'failed' : 'wait'))
    }))
    return {
      items: mapped,
      done: mapped.filter(x => x.status === 'done').length,
      failed: mapped.filter(x => x.status === 'failed').length,
      total: mapped.length,
      running: !!running
    }
  }
  // 实时清单不属于这一轮（新一轮的 writeTodo 已把它顶掉）时回退冻结快照：
  // 旧卡仍显示它那一轮走到哪儿，而不是变回「已批准 · 共 N 步」。快照是收尾那一刻的事实，不再转圈。
  if (snapshot && Array.isArray(snapshot.items) && snapshot.items.length) {
    return {
      items: snapshot.items,
      done: Number(snapshot.done) || 0,
      failed: Number(snapshot.failed) || 0,
      total: Number(snapshot.total) || snapshot.items.length,
      running: false
    }
  }
  return null
}

/**
 * 计划卡上的「本轮调整」：只对已批准的计划展示本轮 steer 段。
 * 传进来的 rows 通常就是 turnSteerRows(msg) 的结果（它本身只含 kind='steer'）。
 */
export const planAdjustsOf = (card, rows) =>
  (card && card.status === 'approved' && Array.isArray(rows)) ? rows : []

/** 计划卡当前是第几版（1 基）：版本链 = 已归档各版 + 当前版 */
export const planVersionOf = card =>
  (Array.isArray(card && card.versions) ? card.versions.length : 0) + 1

/** 不进执行详情的记账型工具：它们不是「这一步做了什么」，列出来只会把时间线撑成日志 */
const BOOKKEEPING_TOOLS = new Set(['writeTodo', 'askUser', 'plan_enter', 'plan_write', 'plan_exit'])

/** 工具卡上的步骤下标：后端下发数字，历史回读可能是数字字符串（统一按数认，认不出就是 -1） */
const stepIdxOf = t => {
  const raw = t && t.stepId
  if (raw === undefined || raw === null || raw === '') return -1
  const n = Number(raw)
  return Number.isInteger(n) && n >= 0 ? n : -1
}

/**
 * 执行详情：把工具调用与产物归到步骤上。
 *
 * 归属靠后端随工具卡下发的 `stepId`（= 那一刻任务清单里 doing 的那一项），不是按调用顺序猜。
 * 整轮都没有 stepId（存量轮次 / 模型没写清单）时返回 `grouped:false` + 平铺工具列表，
 * 由界面标成「未按步骤归类」——宁可少一层结构，也不把工具硬塞进错的步骤。
 *
 * @returns {{ grouped:boolean, steps:Array, others:Array, tools:Array, artifacts:Array }}
 */
export function planExecDetail (progress, toolCalls, artifacts) {
  const items = (progress && Array.isArray(progress.items)) ? progress.items : []
  const tools = (Array.isArray(toolCalls) ? toolCalls : [])
    .filter(t => t && t.name && !BOOKKEEPING_TOOLS.has(t.name))
  const arts = (Array.isArray(artifacts) ? artifacts : []).filter(a => a && a.name)
  const grouped = tools.some(t => stepIdxOf(t) >= 0)
  if (!grouped) return { grouped: false, steps: [], others: [], tools, artifacts: arts }

  const steps = items.map((it, i) => ({ index: i, text: it.text, status: it.status, tools: [], artifacts: [], elapsedMs: 0 }))
  const others = []
  for (const t of tools) {
    const i = stepIdxOf(t)
    const s = i >= 0 ? steps[i] : null
    if (s) {
      s.tools.push(t)
      s.elapsedMs += Number(t.elapsedMs) || 0
    } else {
      others.push(t)
    }
  }
  // 产物归属：名字出现在某一步的工具入参或输出里，就挂那一步（读得到才挂，读不到留在本轮产物）
  const pool = arts.slice()
  for (const s of steps) {
    for (let i = pool.length - 1; i >= 0; i--) {
      const name = String(pool[i].name || '')
      if (!name) continue
      const hit = s.tools.some(t => String(t.args || '').includes(name) || String(t.result || '').includes(name))
      if (hit) s.artifacts.unshift(pool.splice(i, 1)[0])
    }
  }
  return { grouped: true, steps, others, tools, artifacts: pool }
}
