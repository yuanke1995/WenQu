import test from 'node:test'
import assert from 'node:assert/strict'
import { switchVersion, regenerate, createBranchSwitcher } from '../src/chat/versionActions.js'

const regenSetup = ({ busy = false, msgs = [], split = async a => ({ ok: a, dead: [] }) } = {}) => {
  const calls = { stream: [], warns: [] }
  const ctx = {
    loading: () => busy,
    messages: () => msgs,
    splitAtts: split,
    notifyWarning: w => calls.warns.push(w),
    streamAnswer: (...args) => calls.stream.push(args)
  }
  return { ctx, calls }
}

test('重新生成：忙时不做任何事', async () => {
  const { ctx, calls } = regenSetup({ busy: true })
  await regenerate(0, ctx)
  assert.equal(calls.stream.length, 0)
})

test('重新生成：找不到上一条提问时提示', async () => {
  const { ctx, calls } = regenSetup({ msgs: [{ role: 'ai' }] })
  await regenerate(0, ctx)
  assert.deepEqual(calls.warns, ['未找到对应的问题'])
})

test('重新生成：快照当前版本并按原提问参数重发', async () => {
  const user = {
    role: 'user', content: '原问题', images: ['data:1', 'http://x'], deepThink: true,
    attachData: [{ id: 'a' }], skills: ['sk'], mentions: [{ id: 'm' }], historyRefs: [{ messageId: 'h1' }], planMode: true
  }
  const ai = { role: 'ai', content: '旧回答', turnMs: 100 }
  const { ctx, calls } = regenSetup({ msgs: [user, ai] })
  await regenerate(1, ctx)
  assert.equal(ai.versions.length, 1)
  assert.equal(ai.versions[0].content, '旧回答')
  assert.equal(ai.vIndex, 0)
  const a = calls.stream[0]
  assert.equal(a[0], '原问题')
  assert.deepEqual(a[1], ['data:1'])
  assert.equal(a[2], ai)
  assert.equal(a[5], true)
  assert.deepEqual(a[6], [{ id: 'a' }])
  assert.deepEqual(a[7], ['sk'])
  assert.deepEqual(a[8], [{ id: 'm' }])
  assert.deepEqual(a[10], [{ messageId: 'h1' }])
  assert.equal(a[13], true)
})

test('重新生成：已有 versions 不再重复快照', async () => {
  const user = { role: 'user', content: 'q' }
  const ai = { role: 'ai', content: 'v2', versions: [{ content: 'v1' }, { content: 'v2' }], vIndex: 1 }
  const { ctx } = regenSetup({ msgs: [user, ai] })
  await regenerate(1, ctx)
  assert.equal(ai.versions.length, 2)
})

test('重新生成：附件过期/查询失败降级重发并提示', async () => {
  const user = { role: 'user', content: 'q', attachData: [{ id: 'dead' }] }
  const ai = { role: 'ai', content: 'a' }
  const expired = regenSetup({ msgs: [user, ai], split: async () => ({ ok: [], dead: [1, 2] }) })
  await regenerate(1, expired.ctx)
  assert.deepEqual(expired.calls.stream[0][6], [])
  assert.match(expired.calls.warns[0], /2 个附件已过保留期/)

  const boom = regenSetup({ msgs: [user, ai], split: async () => { throw new Error('x') } })
  await regenerate(1, boom.ctx)
  assert.deepEqual(boom.calls.stream[0][6], [])
  assert.match(boom.calls.warns[0], /附件状态没查到/)
})

test('重新生成：血缘优先取用户消息标记，其次计划卡来源', async () => {
  const u1 = { role: 'user', content: 'q', planAdjustFrom: { cardId: 'c1' } }
  const a1 = { role: 'ai', content: 'a', planCard: { adjustFrom: { cardId: 'c2' } } }
  const one = regenSetup({ msgs: [u1, a1] })
  await regenerate(1, one.ctx)
  assert.deepEqual(one.calls.stream[0][16], { planBaseId: 'c1' })

  const u2 = { role: 'user', content: 'q' }
  const a2 = { role: 'ai', content: 'a', planCard: { adjustFrom: { cardId: 'c2' } } }
  const two = regenSetup({ msgs: [u2, a2] })
  await regenerate(1, two.ctx)
  assert.deepEqual(two.calls.stream[0][16], { planBaseId: 'c2' })
})

test('本地版本切换：越界钳制、到头不动、切到即应用快照', () => {
  const m = { content: 'v1', versions: [{ content: 'v1' }, { content: 'v2' }], vIndex: 0 }
  switchVersion(0, 1, { messages: () => [m] })
  assert.equal(m.vIndex, 1)
  assert.equal(m.content, 'v2')
  switchVersion(0, 1, { messages: () => [m] })
  assert.equal(m.vIndex, 1)
  switchVersion(0, -1, { messages: () => [m] })
  assert.equal(m.vIndex, 0)
  assert.equal(m.content, 'v1')
})

test('本地版本切换：单版本/缺失消息不动', () => {
  const m = { versions: [{ content: 'x' }], vIndex: 0 }
  switchVersion(0, 1, { messages: () => [m] })
  assert.equal(m.vIndex, 0)
  switchVersion(5, 1, { messages: () => [m] })
})

test('分支切换：本地版本优先，不走接口；跑动中拒绝', async () => {
  const calls = { api: 0, reload: 0, warns: [] }
  const m = { messageId: 'm1', variantCount: 2, versions: [{ content: 'a' }, { content: 'b' }] }
  const busy = createBranchSwitcher({
    messages: () => [m], isLoading: () => true, switchVersion: () => {},
    switchApi: async () => { calls.api++; return {} }, reload: async () => {}, notifyWarning: w => calls.warns.push(w)
  })
  await busy.switchBranch(0, 1)
  assert.equal(calls.api, 0)
  assert.match(calls.warns[0], /正在回答/)

  let local = 0
  const idle = createBranchSwitcher({
    messages: () => [m], isLoading: () => false, switchVersion: () => { local++ },
    switchApi: async () => { calls.api++; return {} }, reload: async () => {}, notifyWarning: () => {}
  })
  await idle.switchBranch(0, 1)
  assert.equal(local, 1)
  assert.equal(calls.api, 0)
})

test('分支切换：持久分支成功重拉历史，失败/异常提示并复位标志', async () => {
  const m = { messageId: 'm1', variantCount: 2 }
  const calls = { api: [], reload: 0, warns: [] }
  const ok = createBranchSwitcher({
    messages: () => [m], isLoading: () => false, switchVersion: () => {},
    switchApi: async (id, d) => { calls.api.push([id, d]); return { success: true } },
    reload: async () => { calls.reload++ }, notifyWarning: w => calls.warns.push(w)
  })
  await ok.switchBranch(0, -1)
  assert.deepEqual(calls.api, [['m1', -1]])
  assert.equal(calls.reload, 1)
  assert.equal(ok.variantSwitching.value, false)

  const bad = createBranchSwitcher({
    messages: () => [m], isLoading: () => false, switchVersion: () => {},
    switchApi: async () => ({ success: false, msg: '不行' }),
    reload: async () => {}, notifyWarning: w => calls.warns.push(w)
  })
  await bad.switchBranch(0, 1)
  assert.deepEqual(calls.warns, ['不行'])

  const boom = createBranchSwitcher({
    messages: () => [m], isLoading: () => false, switchVersion: () => {},
    switchApi: async () => { throw new Error('net') },
    reload: async () => {}, notifyWarning: w => calls.warns.push(w)
  })
  await boom.switchBranch(0, 1)
  assert.equal(calls.warns[1], 'net')
  assert.equal(boom.variantSwitching.value, false)
})

test('分支切换：无持久分支信息时静默', async () => {
  let api = 0
  const s = createBranchSwitcher({
    messages: () => [{ variantCount: 0 }], isLoading: () => false, switchVersion: () => {},
    switchApi: async () => { api++; return {} }, reload: async () => {}, notifyWarning: () => {}
  })
  await s.switchBranch(0, 1)
  assert.equal(api, 0)
})
