import test from 'node:test'
import assert from 'node:assert/strict'
import { applyTurnStart, applyImageFrame, applyDoneCore } from '../src/chat/streamMerge.js'

// done 合并语义行为表：主聊天页与分享页共用 streamMerge 这一份实现（收敛前两页各写一份、
// 且已经分叉的三处），这里钉住的是「同一条流在两块屏幕上必须对出同一份账」。

test('applyDoneCore：后端宣告 bodyRewritten=true 时正文整包换成落库版', () => {
  const msg = { content: '第一句。[1] 第二句。[2]', images: ['/a.png'], sources: [], turnMs: 0 }
  const rewritten = applyDoneCore(msg, {
    bodyRewritten: true,
    finalContent: '第一句。[1] 第二句。',
    finalImages: ['/a.png'],
    sources: [{ ref: 1 }],
    turnWallMs: 5300
  })
  assert.equal(rewritten, true)
  assert.equal(msg.content, '第一句。[1] 第二句。')
  assert.deepEqual(msg.sources, [{ ref: 1 }])
  assert.equal(msg.turnMs, 5300)
})

test('applyDoneCore：bodyRewritten=false 时不回退现场比对——finalContent 与累积一致才不动', () => {
  const msg = { content: '答案' }
  // 后端说没改写：即便本地比对看着不一样（接流回放/丢帧的失真），也以后端标志为准
  const rewritten = applyDoneCore(msg, { bodyRewritten: false, finalContent: '答案' })
  assert.equal(rewritten, false)
  assert.equal(msg.content, '答案')
})

test('applyDoneCore：旧服务端没有 bodyRewritten 键时回退现场比对', () => {
  const msg = { content: '流式累积' }
  const rewritten = applyDoneCore(msg, { finalContent: '落库改写版' })
  assert.equal(rewritten, true)
  assert.equal(msg.content, '落库改写版')

  const same = { content: '一致' }
  assert.equal(applyDoneCore(same, { finalContent: '一致' }), false)
})

test('applyDoneCore：用时优先服务端墙钟，旧载荷退回机器耗时+等人', () => {
  const msg = { content: 'x', time: Date.now() - 9000, humanWaitMs: 0 }
  applyDoneCore(msg, { finalContent: 'x', turnWallMs: 1500 })
  assert.equal(msg.turnMs, 1500)

  const old = { content: 'x', time: Date.now() - 9000, humanWaitMs: 0 }
  applyDoneCore(old, { finalContent: 'x', elapsedMs: 1200 })
  // 本地钟量到约 9 秒 > 机器耗时 1.2 秒：取墙钟扣等待那一份，答完数字不许变小
  assert.ok(old.turnMs > 8000)
})

test('applyImageFrame：只接受全量数组，非法帧不许清空已下发的图', () => {
  const msg = { images: ['/a.png', '/b.png'] }
  applyImageFrame(msg, 'not-json')
  assert.deepEqual(msg.images, ['/a.png', '/b.png'])
  applyImageFrame(msg, JSON.stringify({ url: '/c.png' }))
  assert.deepEqual(msg.images, ['/a.png', '/b.png'])
  applyImageFrame(msg, JSON.stringify(['/a.png', '/b.png', '/c.png']))
  assert.deepEqual(msg.images, ['/a.png', '/b.png', '/c.png'])
})

test('applyTurnStart：基准只认第一份，后帧只校正 prepMs', () => {
  const msg = { time: Date.now(), turnClockFromServer: false }
  const aligned = []
  applyTurnStart(msg, JSON.stringify({ wallMs: 2000, prepMs: 100 }), t => aligned.push(t))
  assert.equal(msg.turnClockFromServer, true)
  assert.equal(msg.turnPrepMs, 100)
  assert.equal(aligned.length, 1)
  const t0 = msg.time
  applyTurnStart(msg, JSON.stringify({ wallMs: 2600, prepMs: 300 }), t => aligned.push(t))
  // 重锚会把在路上的时间从读数里扣掉（秒表往回跳），所以 time 不许被第二帧挪动
  assert.equal(msg.time, t0)
  assert.equal(msg.turnPrepMs, 300)
  assert.equal(aligned.length, 1)
})
