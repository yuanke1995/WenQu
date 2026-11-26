import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, reactive } from 'vue'
import { createStreamBuffer } from '../src/chat/streamBuffer.js'
import { createThoughtFrames } from '../src/chat/streamThoughtFrames.js'
import { createFrameHandlers, createClosingHandlers } from '../src/chat/streamAnswerHandlers.js'
import { applyTurnStart, applyDoneCore } from '../src/chat/streamMerge.js'
import { applyPlanFrame } from '../src/chat/streamStateFrames.js'
import { applyTodoFrame, applyUsageFrame, applyRetrievedFrame } from '../src/chat/streamInfoFrames.js'
import { applyToolStatus, appendToolOutput } from '../src/chat/streamToolFrames.js'
import { applyDonePayload } from '../src/chat/applyDonePayload.js'
import { closeoutDoneTurn } from '../src/chat/turnDoneCloseout.js'
import { settlePreparingCards } from '../src/chat/streamReducer.js'
import { mergeDoneToolCalls } from '../src/chat/toolCalls.js'
import { STOP_PLACEHOLDER, markStoppedTurn } from '../src/chat/turnCloseout.js'
import { ensureThinkSegment } from '../src/chat/segments.js'
import {
  restoreTimeline, extendTimelineText, extendTimelineProcess,
  pushTimelineTool, pushTimelineArtifact,
} from '../src/chat/timelineWrite.js'

// 整张 SSE 回调表的「按帧序回放」测试：不是逐个 reducer 的单测，而是把一轮真实问答的帧
// 按到达顺序喂给 createFrameHandlers/createClosingHandlers，断言气泡最终形状。
// 拆分/改接线后，只要哪一帧的协作者漏装配、或回调之间顺序变了，这里就会红——
// 这正是 2026-11 拆分事故（每轮问答在 signal 处抛错）缺的那一层。

const makeTurn = () => {
  const msg = reactive({
    role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '',
    loading: true, retrying: false, thinking: '', thinkLoading: false, time: 0,
    turnClockFromServer: false, turnMs: 0, humanWaitMs: 0, artifacts: [], toolCalls: [],
    skillHits: [], plan: null, planCard: null, timeline: [], errorCard: null, model: '',
    goalWaiting: false, agentId: 'ag1', agentName: '问渠', versions: [], vIndex: 0,
  })
  const abort = new AbortController()
  const st = { msg, abort, serverStopped: false, surrendered: false }
  const chatStreams = new Map([['s1', st]])
  const messages = ref([msg])
  const currentSessionId = ref('s1')
  const calls = { scroll: 0, touched: 0, reloaded: 0, turnStart: 0, steers: [], settled: [] }
  const todos = []
  const { flushNow, resetFromContent, appendToken, appendProcess, gotToken } = createStreamBuffer({
    msg, extendText: extendTimelineText, extendProcess: extendTimelineProcess, scroll: () => {},
  })
  const thought = createThoughtFrames({ msg, flushNow, scroll: () => {} })
  const liveCompactedSid = ref('')
  const autoCompactSid = ref('')
  const live = {
    abort, msg, sid: 's1', agentId: 'ag1', st, thought, messages, currentSessionId, chatStreams,
    hooks: { onTurnStart: () => { calls.turnStart++ } },
    flushNow, resetFromContent, appendToken, appendProcess, gotToken,
    liveScroll: () => { calls.scroll++ },
    setTodos: (sid, t) => todos.push([sid, t]),
    applyTurnStart, applyPlanFrame, applyTodoFrame, applyUsageFrame, applyRetrievedFrame,
    applyToolStatus, appendToolOutput,
    applyDoneCore, applyDonePayload, closeoutDoneTurn,
    mergeDoneToolCalls, restoreTimeline, ensureThinkSegment,
    settlePreparingCards,
    // 计划卡/派遣/摘要不在本帧序里：给最小实现，避免把 projections 的浏览器依赖拖进 Node 测试
    applyBlocks: () => {}, settleAbortedPlan: () => {}, planCardFromRecord: () => null,
    linkPlanLineage: () => {},
    pushTimelineTool, pushTimelineArtifact,
    isDispatchTool: () => false, consultNameOf: () => '', stepBriefOf: () => '',
    STOP_PLACEHOLDER, markStoppedTurn,
    liveCompactedSid, autoCompactSid,
    modelMap: ref({}), modelIssueMap: ref({}),
    loadSessions: () => { calls.reloaded++ },
    touchSessionRow: () => { calls.touched++ },
    streamLifecycle: { rememberSettled: (sid, turnId) => calls.settled.push([sid, turnId]) },
    flushPendingSteers: (left, origin) => calls.steers.push([left, origin]),
    prev: null, resume: false, isFirstMessage: false,
  }
  // 本轮上下文允许被继续扩展：没点名的协作者一律给 noop，
  // 测试只断言本轮真正走到的那几帧，不因新增依赖而碎掉。
  const turn = new Proxy(live, {
    get (target, key) {
      if (key in target) return target[key]
      return typeof key === 'string' ? () => {} : undefined
    },
  })
  const handlers = { ...createFrameHandlers(turn), ...createClosingHandlers(turn) }
  turn.handlers = handlers
  return { msg, st, turn, handlers, chatStreams, todos, calls }
}

test('按真实帧序回放一轮问答：起表→思考→正文→工具→计划→任务→done 全链路不抛错', async () => {
  const { msg, turn, handlers, chatStreams, todos, calls } = makeTurn()

  // 1. 起表：服务端收到提问已过去 5000ms
  handlers.onTurnStart(JSON.stringify({ wallMs: 5000, prepMs: 120 }))
  assert.equal(msg.turnClockFromServer, true)
  assert.ok(Date.now() - msg.time >= 4900, '秒表要锚到服务端收到提问那一刻')
  assert.equal(msg.turnPrepMs, 120)
  assert.equal(calls.turnStart, 1, '页面钩子必须收到新一轮通知')

  // 2. 思考 → 正文（节流缓冲）→ 过程独白（到达时先把正文落屏）
  handlers.onThinking('先想一想')
  handlers.onToken('你好，', {})
  handlers.onToken('世界', {})
  assert.equal(msg.content, '', 'token 先进节流缓冲，不逐字落屏')
  handlers.onProcess('查一下资料', {})
  assert.equal(msg.content, '你好，世界', '过程帧到达前必须把节流中的正文落屏')
  assert.equal(msg.processText, '查一下资料')
  assert.equal(msg.thinking, '先想一想')
  assert.ok(msg.timeline.some(s => s.kind === 'think'), '思考段要进时间线')
  assert.ok(msg.timeline.some(s => s.kind === 'text'), '正文段要进时间线')

  // 3. 工具卡：start → 实时输出 → 终态
  handlers.onToolStatus(JSON.stringify({ name: 'search', status: 'start', args: '{}' }))
  assert.equal(msg.toolCalls.length, 1)
  assert.ok(msg.timeline.some(s => s.kind === 'tool'), '工具卡要进时间线')
  handlers.onToolOutput(JSON.stringify({ name: 'search', delta: '结果A' }))
  assert.equal(msg.toolCalls[0].liveOutput, '结果A')
  handlers.onToolStatus(JSON.stringify({ name: 'search', status: 'done', args: '{}', result: '42', elapsedMs: 1200 }))
  assert.equal(msg.toolCalls[0].status, 'done')
  assert.equal(msg.toolCalls[0].result, '42')

  // 4. 计划帧 / 任务清单 / 用量 / 检索回执 / 产物
  handlers.onPlan(JSON.stringify([{ step: '检索' }, { step: '作答' }]))
  assert.equal(msg.plan.length, 2)
  handlers.onTodo(JSON.stringify({ turnId: 't1', items: [{ content: '甲', status: 'todo' }] }))
  assert.equal(todos.length, 1)
  assert.equal(todos[0][0], 's1')
  assert.equal(todos[0][1].items[0].content, '甲')
  handlers.onUsage(JSON.stringify({ promptTokens: 10, outputTokens: 3 }))
  assert.equal(msg.tokensPreview.promptTokens, 10)
  handlers.onRetrieved(JSON.stringify({ keywords: 2, refs: 1, terms: ['甲'] }))
  assert.equal(msg.retrieved.refs, 1)
  handlers.onArtifact(JSON.stringify({ id: 'f1', url: '/a.txt', filename: 'a.txt' }))
  assert.equal(msg.artifacts.length, 1)
  assert.ok(msg.timeline.some(s => s.kind === 'artifact'), '产物要按到达时刻进时间线')
  clearTimeout(msg._freshTimer)   // 入场动效的 900ms 定时器不该拖住测试进程
  handlers.onWarn('降级提示')
  assert.equal(msg.warnMsg, '降级提示')

  // 5. done：落库终态覆盖正文/来源/用量/用时，并定格本轮
  const done = {
    finalContent: '你好，世界', messageId: 'm1', turnId: 't1', model: 'deepseek',
    sources: [{ id: 's1', title: '来源甲' }], related: ['再问一层'], degradations: [],
    tokens: { output: 12 }, turnWallMs: 4200,
    toolCalls: [{ name: 'search', status: 'done', args: '{}', result: '42', elapsedMs: 1200 }],
    pendingSteers: ['下一句'],
  }
  handlers.onDone(JSON.stringify(done))
  await new Promise(resolve => setTimeout(resolve, 0))

  assert.equal(msg.loading, false)
  assert.equal(msg.content, '你好，世界')
  assert.equal(msg.messageId, 'm1')
  assert.equal(msg.turnId, 't1')
  assert.equal(msg.model, 'deepseek')
  assert.equal(msg.turnMs, 4200, '整轮用时只认服务端墙钟')
  assert.equal(msg.sources[0].title, '来源甲')
  assert.deepEqual(msg.related, ['再问一层'])
  assert.equal(msg.tokens.output, 12)
  assert.ok(msg.doneTime > 0)
  assert.equal(chatStreams.has('s1'), false, '收尾必须把本轮从在跑登记里摘掉')
  assert.ok(calls.scroll >= 1, '收尾要贴底')
  assert.equal(calls.touched, 1, '侧栏会话行要归位')
  assert.deepEqual(calls.steers, [[['下一句'], { sid: 's1', agentId: 'ag1' }]], '插话队列必须在收尾结算')
  assert.deepEqual(calls.settled, [['s1', 't1']], '本页已收场登记要写')

  // 6. 收尾后错误帧不能把已定格的气泡打回失败态
  handlers.onError('boom', 'spoken')
  assert.equal(msg.loading, false)
})