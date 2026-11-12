import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, reactive, nextTick } from 'vue'
import { historyRefPreviewText, mentionNamesOf, planHeadTextOf, shouldAutoExpandPlan,
         planTaskTitle, planViewerMessageOf, projectPlanProgress, canOpenPlanAdjust,
         adjustFailureText, planPanelKeydownIntent } from '../src/chat/planPanels.js'
import { usePlanPanels } from '../src/chat/usePlanPanels.js'

const stateText = st => ({ approved: '已批准', pending: '待确认', drafting: '生成中' }[st] || st)

// ---------- 纯判据 ----------

test('历史气泡 chip：压平空白、空占位、20 字截断', () => {
  assert.equal(historyRefPreviewText({ content: '  多行\n  内容  ' }), '多行 内容')
  assert.equal(historyRefPreviewText(null), '历史问答')
  assert.equal(historyRefPreviewText({ content: '   ' }), '历史问答')
  assert.equal(historyRefPreviewText({ content: '短' }), '短')
  const long = '一二三四五六七八九十一二三四五六七八九十一'
  assert.equal(historyRefPreviewText({ content: long }), long.slice(0, 20) + '…')
})

test('@ 资料名：展示名优先，缺失回退 id', () => {
  assert.deepEqual(mentionNamesOf([{ name: '资料A', id: 'd1' }, { id: 'd2' }]), ['资料A', 'd2'])
  assert.deepEqual(mentionNamesOf(null), [])
})

test('计划头文案：待确认/生成中优先，其余用引擎状态文案，空列表为空', () => {
  assert.equal(planHeadTextOf([], stateText), '')
  assert.equal(planHeadTextOf([{ status: 'approved' }], stateText), '已批准')
  assert.equal(planHeadTextOf([{ status: 'approved' }, { status: 'pending' }], stateText), '待确认')
  assert.equal(planHeadTextOf([{ status: 'superseded' }, { status: 'drafting' }], stateText), '生成中')
  assert.equal(planHeadTextOf([{ status: 'weird' }], stateText), 'weird')
})

test('自动展开：状态串变化且含待确认/生成中才顶开', () => {
  assert.equal(shouldAutoExpandPlan('approved,pending', 'approved'), true)
  assert.equal(shouldAutoExpandPlan('drafting', ''), true)
  assert.equal(shouldAutoExpandPlan('approved,pending', 'approved,pending'), false)
  assert.equal(shouldAutoExpandPlan('approved', ''), false)
  assert.equal(shouldAutoExpandPlan('', ''), false)
})

test('计划标题：向前取最近用户提问首行，截断 60 字，找不到用会话标题', () => {
  const msgs = [
    { role: 'user', content: '旧提问' },
    { role: 'ai', content: '答' },
    { role: 'user', content: '  计划对应的提问\n第二行  ' },
    { role: 'ai', content: '', planCard: {} }
  ]
  assert.equal(planTaskTitle(msgs, 3, '兜底'), '计划对应的提问')
  assert.equal(planTaskTitle(msgs, 1, '兜底'), '旧提问')
  assert.equal(planTaskTitle([{ role: 'ai', content: 'x' }], 0, '兜底标题'), '兜底标题')
  const long = '字'.repeat(70)
  assert.equal(planTaskTitle([{ role: 'user', content: long }, { role: 'ai', content: '答' }], 1, '兜底').length, 61)
})

test('抽屉消息反查：按计划卡对象命中；会话不符/无卡/找不到都失效', () => {
  const card = { id: 'c1' }
  const m = { role: 'ai', planCard: card }
  const msgs = [{ role: 'user' }, m]
  assert.equal(planViewerMessageOf(msgs, card, 's1', 's1'), m)
  assert.equal(planViewerMessageOf(msgs, card, 's0', 's1'), null)
  assert.equal(planViewerMessageOf(msgs, null, 's1', 's1'), null)
  assert.equal(planViewerMessageOf(msgs, { id: 'other' }, 's1', 's1'), null)
})

test('进度投影：无进度全 0；有 doing 给计数与步骤标签，无 doing 标签为空', () => {
  assert.deepEqual(projectPlanProgress(null), { done: 0, doing: 0, total: 0, failed: 0, stepLabel: '' })
  const prog = { done: 2, total: 5, failed: 1,
    items: [{ text: '甲', status: 'done' }, { text: '乙', status: 'doing' }, { text: '丙', status: 'todo' }] }
  assert.deepEqual(projectPlanProgress(prog), { done: 2, doing: 1, total: 5, failed: 1, stepLabel: '第 2 步「乙」' })
  const noDoing = { done: 3, total: 3, failed: 0, items: [{ text: '甲', status: 'done' }] }
  assert.equal(projectPlanProgress(noDoing).doing, 0)
  assert.equal(projectPlanProgress(noDoing).stepLabel, '')
})

test('调整准入：仅已批准、非恢复、未被重新计划的卡', () => {
  assert.equal(canOpenPlanAdjust({ status: 'approved' }), true)
  assert.equal(canOpenPlanAdjust(null), false)
  assert.equal(canOpenPlanAdjust({ status: 'pending' }), false)
  assert.equal(canOpenPlanAdjust({ status: 'approved', restored: true }), false)
  assert.equal(canOpenPlanAdjust({ status: 'approved', replannedBy: { cardId: 'x' } }), false)
  assert.equal(canOpenPlanAdjust({ status: 'approved', replannedAt: 123 }), false)
})

test('失败文案：reason→可执行下一步，仅 ended 建议重新计划', () => {
  const cases = {
    ended: '这一轮刚好结束了。要改计划，请改用「停止并重新计划」。',
    'stop-failed': '这一轮没停下来，先不要重新计划。可以稍后再试。',
    busy: '还有一轮在跑，等它结束再调整。',
    restored: '这张计划是恢复的，请回到发起它的设备调整。',
    model: '还没有可用模型，请先在右上角选择模型。',
    empty: '先写下要改什么。'
  }
  for (const [reason, error] of Object.entries(cases)) {
    const r = adjustFailureText(reason)
    assert.equal(r.error, error, reason)
    assert.equal(r.suggestReplan, reason === 'ended', reason)
  }
  assert.equal(adjustFailureText('error').error, '提交失败，请重试。')
  assert.equal(adjustFailureText('state').error, '提交失败，请重试。')
  assert.equal(adjustFailureText(undefined).suggestReplan, false)
})

test('键盘意图：输入法/卡不可用/过期/修饰键/空文本不提交；恢复态标记', () => {
  const card = { editText: '  要改  ', busy: false, restored: false }
  assert.equal(planPanelKeydownIntent({ key: 'Enter', isComposing: true }, card, false), null)
  assert.equal(planPanelKeydownIntent({ key: 'Enter', keyCode: 229 }, card, false), null)
  assert.equal(planPanelKeydownIntent({ key: 'Enter' }, null, false), null)
  assert.equal(planPanelKeydownIntent({ key: 'Enter' }, { ...card, busy: true }, false), null)
  assert.equal(planPanelKeydownIntent({ key: 'Enter' }, card, true), null)
  assert.equal(planPanelKeydownIntent({ key: 'Enter', shiftKey: true }, card, false), null)
  assert.equal(planPanelKeydownIntent({ key: 'Enter', ctrlKey: true }, card, false), null)
  assert.equal(planPanelKeydownIntent({ key: 'a' }, card, false), null)
  assert.equal(planPanelKeydownIntent({ key: 'Enter' }, { ...card, editText: '  ' }, false), null)
  assert.deepEqual(planPanelKeydownIntent({ key: 'Enter' }, card, false), { text: '要改', restored: false })
  assert.deepEqual(planPanelKeydownIntent({ key: 'Enter' }, { ...card, restored: true }, false), { text: '要改', restored: true })
})

// ---------- composable ----------

// 消息流用响应式数组，贴近引擎：计划卡对象在抽屉与消息流里是同一份代理，反查与定位才成立。
const makeCtx = (over = {}) => {
  const card = { id: 'c1', status: 'approved' }
  const msgs = reactive([
    { role: 'user', content: '第一行的提问\n第二行补充' },
    { role: 'ai', content: '回答' },
    { role: 'ai', planCard: card, loading: false }
  ])
  const planMsg = msgs[2]
  const calls = { approvals: [], revises: [], continues: [], adjusts: [], jumps: [], stops: [], infos: [] }
  const prog = { done: 2, total: 5, failed: 1,
    items: [{ text: '甲', status: 'done' }, { text: '乙', status: 'doing' }, { text: '丙', status: 'todo' }] }
  const ctx = {
    messages: () => msgs,
    currentSessionId: ref('s1'),
    currentSessionTitle: ref('会话标题'),
    curPlanCard: ref(card),
    planExpired: ref(false),
    sessionPlans: ref([]),
    pendingPlan: ref(planMsg),
    pendingMentions: ref([{ name: '资料A', id: 'd1' }, { id: 'd2' }]),
    isNarrow: ref(false),
    planStateText: stateText,
    planExecProgressOf: m => (m && m.planCard ? prog : null),
    planAdjustsOfMsg: () => [{ text: '调整1' }],
    submitPlanApproval: (...a) => calls.approvals.push(a),
    revisePlanInPlace: (...a) => calls.revises.push(a),
    continuePlanConversation: async (...a) => { calls.continues.push(a) },
    adjustPlanAfterApproval: async (m, text, mode) => { calls.adjusts.push([m, text, mode]); return { ok: true } },
    jumpToPlan: mi => calls.jumps.push(mi),
    stopNow: () => calls.stops.push(1),
    notify: { info: t => calls.infos.push(t) },
    ...over
  }
  return { ctx, msgs, card, planMsg, prog, calls }
}

test('折叠态：头部文案随状态变化，出现待确认自动展开且状态串不变不反复弹回', async () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  assert.equal(p.planHeadText.value, '')
  c.ctx.sessionPlans.value = [{ status: 'approved' }]
  assert.equal(p.planHeadText.value, '已批准')
  assert.equal(p.planOpen.value, false)
  c.ctx.sessionPlans.value = [{ status: 'approved' }, { status: 'pending' }]
  await nextTick()
  assert.equal(p.planHeadText.value, '待确认')
  assert.equal(p.planOpen.value, true)
  p.planOpen.value = false
  c.ctx.sessionPlans.value = [{ status: 'approved' }, { status: 'pending' }]
  await nextTick()
  assert.equal(p.planOpen.value, false)
})

test('空态名、气泡预览与意见框占位：随 isNarrow 切换', () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  assert.deepEqual(p.mentionNames.value, ['资料A', 'd2'])
  assert.equal(p.historyRefPreview({ content: '  引用\n内容  ' }), '引用 内容')
  assert.match(p.planEditPlaceholder.value, /Enter 提交/)
  c.ctx.isNarrow.value = true
  assert.match(p.planEditPlaceholder.value, /同一轮内按意见升版/)
  assert.doesNotMatch(p.planEditPlaceholder.value, /Enter 提交/)
})

test('计划抽屉：打开字段、反查/进度/调整记录、裁决动作回引擎', async () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  p.openPlanViewer(c.msgs[2], 2, 3, 'exec')
  assert.equal(p.planViewer.open, true)
  assert.equal(p.planViewer.card.id, c.card.id)
  assert.equal(p.planViewer.sid, 's1')
  assert.equal(p.planViewer.pkey, 'c1')
  assert.equal(p.planViewer.mi, 2)
  assert.equal(p.planViewer.title, '第一行的提问')
  assert.equal(p.planViewer.version, 3)
  assert.equal(p.planViewer.tab, 'exec')
  assert.equal(p.planViewerMsg.value, c.planMsg)
  assert.deepEqual(p.planViewerProgress.value, c.prog)
  assert.equal(p.planViewerAdjusts.value.length, 1)
  p.onViewerApprove()
  assert.equal(c.calls.approvals.length, 1)
  assert.equal(p.planViewer.open, false)
  p.openPlanViewer(c.msgs[2], 2)
  p.onViewerRevise('改这里')
  assert.deepEqual(c.calls.revises[0], [c.planMsg, '改这里'])
  await p.onViewerSupersede('重做')
  assert.deepEqual(c.calls.continues[0], [c.planMsg, '重做'])
  p.onViewerStop()
  assert.equal(c.calls.stops.length, 1)
})

test('抽屉反查与失效：切会话后卡不在当前流里，抽屉收起', async () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  p.openPlanViewer(c.msgs[2], 2)
  assert.equal(p.planMsgOfViewer(), c.planMsg)
  c.ctx.currentSessionId.value = 's2'
  assert.equal(p.planMsgOfViewer(), null)
  assert.equal(p.planViewerMsg.value, null)
  assert.equal(p.planViewerProgress.value, null)
  assert.deepEqual(p.planViewerAdjusts.value, [])
  await nextTick()
  assert.equal(p.planViewer.open, false)
})

test('定位：抽屉定位、按卡 id 定位、血缘/重规划/计划源都走 jumpToPlan', () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  p.openPlanViewer(c.msgs[2], 2)
  p.onViewerLocate()
  assert.deepEqual(c.calls.jumps, [2])
  c.card.adjustFrom = { cardId: 'c1' }
  c.card.replannedBy = { cardId: 'c1' }
  p.openPlanViewer(c.msgs[2], 2)
  p.onViewerLocateSource()
  assert.equal(c.calls.jumps.at(-1), 2)
  p.locateReplanned(c.planMsg)
  assert.equal(c.calls.jumps.at(-1), 2)
  p.locatePlanSource(c.planMsg)
  assert.equal(c.calls.jumps.at(-1), 2)
  const before = c.calls.jumps.length
  p.locatePlanCardById('missing')
  assert.equal(c.calls.jumps.length, before)
})

test('批准后调整面板：准入、进度投影、running 随消息、切会话作废', async () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  p.openPlanAdjust(c.planMsg, true)
  assert.equal(p.planAdjust.open, true)
  assert.equal(p.planAdjust.fromViewer, true)
  assert.equal(p.planAdjust.done, 2)
  assert.equal(p.planAdjust.doing, 1)
  assert.equal(p.planAdjust.total, 5)
  assert.equal(p.planAdjust.failed, 1)
  assert.equal(p.planAdjust.stepLabel, '第 2 步「乙」')
  assert.equal(p.planAdjust.running, false)
  await nextTick()
  p.planAdjust.m.loading = true
  await nextTick()
  assert.equal(p.planAdjust.running, true)
  const c2 = makeCtx()
  c2.card.replannedAt = 1
  const p2 = usePlanPanels(c2.ctx)
  p2.openPlanAdjust(c2.planMsg)
  assert.equal(p2.planAdjust.open, false)
  c.ctx.currentSessionId.value = 's2'
  await nextTick()
  assert.equal(p.planAdjust.open, false)
  assert.equal(p.planAdjust.m, null)
})

test('关闭调整面板：busy 时挡住，否则清空挂的消息与错误', () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  p.openPlanAdjust(c.planMsg)
  p.planAdjust.busy = true
  p.closePlanAdjust()
  assert.equal(p.planAdjust.open, true)
  p.planAdjust.busy = false
  p.closePlanAdjust()
  assert.equal(p.planAdjust.open, false)
  assert.equal(p.planAdjust.m, null)
})

test('提交调整成功：收口、fromViewer 关抽屉、按 mode/running 提示', async () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  p.openPlanAdjust(c.planMsg, true)
  p.openPlanViewer(c.msgs[2], 2)
  await p.submitPlanAdjust({ text: '改', mode: 'replan' })
  assert.equal(p.planAdjust.open, false)
  assert.equal(p.planAdjust.busy, false)
  assert.equal(p.planViewer.open, false)
  assert.deepEqual(c.calls.adjusts[0], [c.planMsg, '改', 'replan'])
  assert.equal(c.calls.infos.at(-1), '正在按新意见重新计划')

  const c2 = makeCtx()
  const p2 = usePlanPanels(c2.ctx)
  p2.openPlanAdjust(c2.planMsg)
  await p2.submitPlanAdjust({ text: '插话', mode: 'steer' })
  assert.equal(c2.calls.infos.at(-1), '已送到正在执行的一轮，下一步开始前生效')

  const c3 = makeCtx()
  c3.planMsg.loading = true
  const p3 = usePlanPanels(c3.ctx)
  p3.openPlanAdjust(c3.planMsg)
  await p3.submitPlanAdjust({ text: '改', mode: 'replan' })
  assert.equal(c3.calls.infos.at(-1), '已停止本轮，正在按新意见重新计划')
})

test('提交调整失败：按 reason 给文案，ended 建议重新计划，异常兜底，busy 复位', async () => {
  for (const reason of ['ended', 'stop-failed', 'busy', 'restored', 'model', 'empty', 'weird']) {
    const c = makeCtx()
    c.ctx.adjustPlanAfterApproval = async () => ({ ok: false, reason })
    const p = usePlanPanels(c.ctx)
    p.openPlanAdjust(c.planMsg)
    await p.submitPlanAdjust({ text: 'x', mode: 'steer' })
    assert.equal(p.planAdjust.open, true)
    assert.equal(p.planAdjust.busy, false)
    assert.equal(p.planAdjust.suggestReplan, reason === 'ended', reason)
    if (reason === 'ended') assert.match(p.planAdjust.error, /停止并重新计划/)
  }
  const c = makeCtx()
  c.ctx.adjustPlanAfterApproval = async () => { throw new Error('boom') }
  const p = usePlanPanels(c.ctx)
  p.openPlanAdjust(c.planMsg)
  await p.submitPlanAdjust({ text: 'x', mode: 'steer' })
  assert.equal(p.planAdjust.error, '提交失败，请重试。')
  assert.equal(p.planAdjust.busy, false)
})

test('意见框键盘：正常修订、恢复态转对话、过期/组合/空文本不提交', () => {
  const c = makeCtx()
  const p = usePlanPanels(c.ctx)
  let prevented = 0
  const ev = extra => ({ key: 'Enter', preventDefault: () => { prevented++ }, ...extra })
  p.onPlanPanelKeydown(ev())
  assert.equal(c.calls.revises.length, 0)
  c.ctx.curPlanCard.value = { editText: '  改这里  ', busy: false, restored: false }
  p.onPlanPanelKeydown(ev())
  assert.deepEqual(c.calls.revises[0], [c.planMsg, '改这里'])
  assert.equal(prevented, 1)
  p.onPlanPanelKeydown(ev({ isComposing: true }))
  assert.equal(prevented, 1)
  c.ctx.planExpired.value = true
  p.onPlanPanelKeydown(ev())
  assert.equal(c.calls.revises.length, 1)
  c.ctx.planExpired.value = false
  c.ctx.curPlanCard.value = { editText: '改', restored: true }
  p.onPlanPanelKeydown(ev())
  assert.deepEqual(c.calls.continues[0], [c.planMsg, '改'])
  assert.equal(prevented, 2)
})
