import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createFrameHandlers, guardTurn } from '../src/chat/streamAnswerHandlers.js'

// 2026-11 拆分 useChatEngine 时，SSE 回调表被外提到 streamAnswerHandlers.js，但工厂仍按
// (deps, turn) 两份对象解构，而 openTurn 已经改成只把轮内字段放进 deps。结果每个字段都是
// undefined：装配期不报错，用户一按回车就在 `signal: abort.signal` 处抛
// 「Cannot read properties of undefined (reading 'signal')」，整轮问答起不来。
// 这里做两道对账：静态（deps 必须覆盖工厂解构的每个键）+ 运行期（关键回调真的取到本轮值）。

const ENGINE = readFileSync('src/chat/useChatEngine.js', 'utf8')
const HANDLERS = readFileSync('src/chat/streamAnswerHandlers.js', 'utf8')

/** openTurn 里 `const deps = { ... }` 的简写键集合（注释行先剔掉）。 */
const depsKeys = () => {
  const m = /const deps = \{([\s\S]*?)\n {2}\}/.exec(ENGINE)
  assert.ok(m, 'useChatEngine 的 openTurn 里找不到 deps 对象字面量')
  const body = m[1].replace(/\/\/[^\n]*/g, '')
  return new Set([...body.matchAll(/([A-Za-z_$][\w$]*)\s*(?=,|\n)/g)].map(x => x[1]))
}

/** streamAnswerHandlers 的两个工厂里 `const { ... } = turn` 解构出的全部名字。 */
const destructuredFromTurn = () => {
  const names = new Set()
  for (const m of HANDLERS.matchAll(/const \{([\s\S]*?)\} = turn/g)) {
    for (const n of m[1].split(/[,\n]/).map(s => s.trim()).filter(Boolean)) names.add(n)
  }
  return names
}

test('openTurn 的 deps 必须覆盖回调工厂解构的每个键', () => {
  const keys = depsKeys()
  const wanted = destructuredFromTurn()
  assert.ok(wanted.size > 50, `解构结果异常（只有 ${wanted.size} 个键），正则可能失配`)
  const missing = [...wanted].filter(k => !keys.has(k)).sort()
  assert.deepEqual(missing, [], `deps 缺少这些键，落到回调里全是 undefined：${missing.join(', ')}`)
})

test('本轮上下文只有一份：signal 取本轮 abort，快照回放用装配后回填的 handlers', () => {
  const abort = { signal: 'sig-1' }
  const tokens = []
  const processes = []
  let replayed = null
  const turn = {
    abort,
    buildTurnRequest: () => ({}),
    reasoningLevelParam: { value: 0 },
    msg: { content: '', thinkLoading: true },
    sid: 's1',
    thought: { setOpen () {}, closeRound () {} },
    currentSessionId: { value: null },
    messages: { value: [] },
    reduceTurnSnapshot: () => true,
    resetFromContent: () => {},
    replaySnapshotExtras: (m, snap, handlers) => { replayed = handlers },
    linkPlanLineage: () => {},
    dropSteeredUserRows: list => list,
    liveScroll: () => {},
    appendToken: (t, ev) => tokens.push([t, ev]),
    appendProcess: (t, ev) => processes.push([t, ev]),
    handlers: null,
  }
  const handlers = createFrameHandlers(turn)

  assert.equal(handlers.signal, abort.signal, 'signal 必须来自本轮 AbortController')
  handlers.onToken('甲', { i: 1 })
  handlers.onProcess('乙', { i: 2 })
  assert.deepEqual(tokens, [['甲', { i: 1 }]], 'onToken 必须走本轮 appendToken（createStreamBuffer 提供）')
  assert.deepEqual(processes, [['乙', { i: 2 }]], 'onProcess 必须走本轮 appendProcess')

  const lateBound = { onToolOutput () {} }
  turn.handlers = lateBound
  handlers.onSnapshot(JSON.stringify({}))
  assert.equal(replayed, lateBound, 'onSnapshot 必须惰性读取装配后回填的 handlers，而不是装配时的 null')
})
test('DEV 护栏：键缺失/值为 undefined 都在开流第一帧抛，而不是等某帧到达才炸', () => {
  const turn = guardTurn({ abort: { signal: 'sig' }, msg: {}, handlers: null })
  assert.equal(turn.abort.signal, 'sig')
  assert.equal(turn.handlers, null, 'null 是合法值（装配完才回填），不算漏装配')
  assert.throws(() => turn.flushPendingSteers, /未装配的字段「flushPendingSteers」/)
  const broken = guardTurn({ flushPendingSteers: undefined })
  assert.throws(() => broken.flushPendingSteers, /装配成了 undefined/)
  assert.throws(() => guardTurn({})['什么字段'], /未装配/)
})
