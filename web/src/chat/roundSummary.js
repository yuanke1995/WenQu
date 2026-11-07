// 右栏「本轮」卡与移动端「状态与来源」里那一段共用的判定（两端不许各写一套）：
// 卡头那半句说什么、此刻在等谁、每一步的参数摘要。
// 口径来源：单步耗时不在面向用户的界面报（整轮用时只有气泡那条状态行一份），
// 每一步要说的是「做了什么事」——取 toolBrief 的关键入参（命令/检索词/路径/文件名/问了谁）。
import { toolLabel, toolBrief, toolCallsView } from './projections'

const oneLine = s => String(s || '').replace(/\s+/g, ' ').trim()

/** 这一步在跑吗：被守卫挡下的不算（它根本没执行） */
export const isRunStep = t => t.status === 'start' && !t.denied

/** 卡片主体那份步骤清单（并掉重复卡） */
export const roundSteps = m => toolCallsView(m && m.toolCalls)

/**
 * 此刻有没有等人拍板：工具批准 > 提问 > 计划；都不在等时，才让位给失败步那一句原因。
 * @returns {null|{kind:string,text:string,tip:string,go?:string,bad?:boolean}}
 */
export function roundHold (m) {
  if (!m) return null
  const approvals = Array.isArray(m.approvals) ? m.approvals : []
  if (approvals.length) {
    const a = approvals[0]
    const brief = toolBrief({ name: a.tool, label: a.label, args: a.args })
    const who = a.subagent && a.subagent.agent ? '「' + a.subagent.agent + '」' : ''
    const more = approvals.length > 1 ? '（共 ' + approvals.length + ' 项）' : ''
    return { kind: 'approval', text: '等你批准' + who + '这一步' + (brief ? ' · ' + brief : '') + more, tip: '点到那条回答下的审批卡', go: 'hitl' }
  }
  const q = m.ask
  if (q && !q.answered) {
    const n = Array.isArray(q.questions) ? q.questions.length : 0
    const first = n ? oneLine(q.questions[0] && q.questions[0].question) : ''
    return {
      kind: 'ask',
      text: '等你作答' + (n > 1 ? ' · 共 ' + n + ' 个问题' : (first ? ' · ' + first : '')),
      tip: '点到输入框处的提问面板', go: 'hitl'
    }
  }
  if (m.planCard && m.planCard.status === 'pending') {
    return { kind: 'plan', text: '等你批准这一轮的计划', tip: '点到输入框处的计划面板', go: 'hitl' }
  }
  const f = roundSteps(m).find(t => t.status === 'error')
  if (f) {
    // 只取原因的第一行：工具返回常常是整段说明，一行摘要里塞不下，多行拼起来还会把冒号后的重点挤掉
    const why = oneLine(String(f.result || f.error || f.hint || '').split(/\r?\n/)[0])
    return {
      kind: 'failed',
      text: toolLabel(f.name, f.label) + '失败' + (why ? '：' + why : ''),
      tip: '点开查看全部步骤里的完整原因', bad: true
    }
  }
  return null
}

/**
 * 卡头那半句只说气泡状态行没说过的东西：气泡报整轮用时，这里报此刻在做哪件事、在等谁。
 * 答完且不等待时返回空串——「已完成，用时 …」是气泡那一行的话，两处各写一遍必然写歪。
 */
export function roundHeadTail (m, phaseText) {
  const hold = roundHold(m)
  if (hold && !hold.bad) return hold.text.split(' · ')[0]
  if (m && m.loading === true) {
    const run = roundSteps(m).find(isRunStep)
    return run ? '正在' + toolLabel(run.name, run.label) : (phaseText || '')
  }
  if (hold && hold.bad) return '有一步没跑成'
  return ''
}

/** 跑轮中但一步都没在跑时的那句阶段说明：先思考、否则在输出 */
export function roundPhase (m) {
  if (!m) return ''
  if (m.thinkLoading || (m.thinking && !m.content)) return '正在思考'
  return '正在输出'
}
