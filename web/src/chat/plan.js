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
 * 计划批准后的执行进度：拿会话任务清单当数据源（清单本来就是这一轮的执行项）。
 * 只有「计划已批准 + 清单存在 + 清单属于这一轮」才返回进度；否则 null——
 * 卡片退回计划步骤预览，绝不按工具调用数去编造「第几步已完成」。
 */
export function planExecProgress (card, todos, turnId, running) {
  if (!card || card.status !== 'approved') return null
  const items = (todos && Array.isArray(todos.items)) ? todos.items : []
  if (!items.length) return null
  if (todos.turnId && turnId && todos.turnId !== turnId) return null
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
