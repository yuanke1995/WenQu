import test from 'node:test'
import assert from 'node:assert/strict'
import { createStopTurn } from '../src/chat/stopTurn.js'
import { TURN_STOP_SUFFIX } from '../src/chat/turnStatus.js'

const setup = ({ hasStream = true, resp = { data: { stopped: true } } } = {}) => {
  const calls = { stop: [], warns: [], aborts: 0, deleted: [] }
  const st = {
    msg: { content: '半句回答', toolCalls: [{ name: 'read_file', status: 'start' }] },
    abort: { abort: () => { calls.aborts++ } }
  }
  const streams = {
    get: () => (hasStream ? st : undefined),
    delete: sid => { calls.deleted.push(sid) }
  }
  const stop = createStopTurn({
    currentSid: () => 's1',
    streams,
    stopApi: async sid => { calls.stop.push(sid); if (resp instanceof Error) throw resp; return resp },
    notifyWarning: w => calls.warns.push(w)
  })
  return { stop, calls, st }
}

test('停止：没有在跑的流时直接返回 false，不发请求', async () => {
  const { stop, calls } = setup({ hasStream: false })
  assert.equal(await stop(), false)
  assert.equal(calls.stop.length, 0)
  assert.equal(calls.aborts, 0)
})

test('停止：服务端确认停掉 → 标终止、定格工具卡、掐本地通道', async () => {
  const { stop, calls, st } = setup()
  const r = await stop()
  assert.equal(r, true)
  assert.deepEqual(calls.stop, ['s1'])
  assert.deepEqual(calls.deleted, ['s1'])
  assert.equal(st.serverStopped, true)
  assert.equal(st.msg.stoppedTurn, true)
  assert.ok(st.msg.content.endsWith(TURN_STOP_SUFFIX))
  assert.equal(st.msg.toolCalls[0].status, 'error')
  assert.equal(calls.aborts, 1)
})

test('停止：服务端说这轮不归它管（stopped=false）时不补终止标注', async () => {
  const { stop, st } = setup({ resp: { data: { stopped: false } } })
  assert.equal(await stop(), true)
  assert.equal(st.msg.content, '半句回答')
  assert.equal(st.serverStopped, undefined)
})

test('停止：服务端请求失败只提醒，仍掐本地通道', async () => {
  const { stop, calls } = setup({ resp: new Error('net') })
  assert.equal(await stop(), false)
  assert.match(calls.warns[0], /服务端没能停止本轮/)
  assert.equal(calls.aborts, 1)
})
