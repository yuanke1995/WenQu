import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { createSend } from '../src/chat/sendFlow.js'

const setup = ({ text = '', loading = false, goalStreaming = false, model = 'v/m',
                 uploading = false, steerStaged = true } = {}) => {
  const calls = { warns: [], staged: [], steered: [], stream: [], surrender: 0, close: 0, planMode: [], compact: [] }
  const state = {
    text: ref(text),
    images: ref([{ dataUrl: 'data:i' }]),
    files: ref([{ name: 'f', mime: 'text/plain', size: 3, fileId: 'f1' },
                { name: 'bad', mime: 'x', fileId: 'f2', error: true },
                { name: 'up', mime: 'x', uploading: true }]),
    skills: ref(['sk']),
    mentions: ref([{ type: 'kb', id: 'k1', name: '知识库', kbId: 'kb' }]),
    hists: ref([{ messageId: 'h1' }, { messageId: 'h2' }])
  }
  const loadingRef = ref(loading)
  const planOn = ref(true)
  const send = createSend({
    text: state.text, pendingImages: state.images, pendingFiles: state.files,
    pickedSkills: state.skills, pendingMentions: state.mentions, pendingHistoryRefs: state.hists,
    hasUploadingFile: () => uploading,
    loading: loadingRef,
    goalTurnStreaming: ref(goalStreaming),
    surrenderGoalStream: () => { calls.surrender++ },
    stageSteer: p => { calls.staged.push(p); return steerStaged ? { ...p } : null },
    sendSteer: item => { calls.steered.push(item) },
    setPlanMode: v => { calls.planMode.push(v); planOn.value = v },
    planModeOn: planOn,
    effectiveModel: ref(model),
    compactNotice: ref({ n: 1 }),
    liveCompactedSid: ref('s1'),
    deepThinkOn: ref(true),
    closePanels: () => { calls.close++ },
    messages: ref([]),
    streamAnswer: (...a) => calls.stream.push(a),
    notifyWarning: w => calls.warns.push(w)
  })
  return { send, calls, state, loadingRef, planOn }
}

test('上传门禁：有附件在上传时拦下本次发送', async () => {
  const { send, calls } = setup({ text: 'q', uploading: true })
  await send()
  assert.match(calls.warns[0], /附件还在上传中/)
  assert.equal(calls.stream.length, 0)
})

test('空载荷（无字/无图/无可用附件）不发', async () => {
  const { send, calls, state } = setup({ text: '   ' })
  state.images.value = []
  state.files.value = [{ name: 'up', mime: 'x', uploading: true }]
  await send()
  assert.equal(calls.stream.length, 0)
  assert.equal(calls.staged.length, 0)
})

test('生成中：只入队并清空输入，不发流；steerNow 立即送', async () => {
  const { send, calls, state, planOn } = setup({ text: ' 插话 ', loading: true })
  await send({ steerNow: true })
  assert.equal(calls.staged.length, 1)
  const p = calls.staged[0]
  assert.equal(p.text, '插话')
  assert.deepEqual(p.images, ['data:i'])
  assert.deepEqual(p.atts, [{ name: 'f', mime: 'text/plain', fileId: 'f1' }])
  assert.deepEqual(p.attsMeta, [{ name: 'f', mime: 'text/plain', size: 3, paste: false, text: undefined }])
  assert.deepEqual(p.skills, ['sk'])
  assert.deepEqual(p.mentions, [{ type: 'kb', id: 'k1', name: '知识库', kbId: 'kb' }])
  assert.deepEqual(p.historyRefs, [{ messageId: 'h1' }, { messageId: 'h2' }])
  assert.equal(p.deepThink, true)
  assert.equal(p.planMode, true)
  assert.equal(planOn.value, false)
  assert.deepEqual(calls.planMode, [false])
  assert.equal(calls.steered.length, 1)
  assert.equal(state.text.value, '')
  assert.deepEqual(state.images.value, [])
  assert.deepEqual(state.files.value, [])
  assert.deepEqual(state.skills.value, [])
  assert.equal(calls.stream.length, 0)
})

test('生成中入队失败：不清输入框', async () => {
  const { send, calls, state } = setup({ text: '插话', loading: true, steerStaged: false })
  await send({ steerNow: true })
  assert.equal(calls.steered.length, 0)
  assert.equal(state.text.value, '插话')
})

test('目标轮跑动时普通发送先让位', async () => {
  const { send, calls } = setup({ text: 'q', loading: true, goalStreaming: true })
  await send()
  assert.equal(calls.surrender, 1)
})

test('无可用模型：提示配置，不发流', async () => {
  const { send, calls } = setup({ text: 'q', model: '' })
  await send()
  assert.match(calls.warns[0], /未指定模型/)
  assert.equal(calls.stream.length, 0)
})

test('正常发送：清空输入与压缩留痕、消费计划模式、推用户消息并开流', async () => {
  const { send, calls, state, planOn } = setup({ text: ' 问题 ' })
  await send()
  assert.equal(state.text.value, '')
  assert.deepEqual(state.images.value, [])
  assert.deepEqual(state.files.value, [])
  assert.deepEqual(state.skills.value, [])
  assert.equal(calls.close, 1)
  assert.equal(planOn.value, false)
  assert.equal(calls.stream.length, 1)
  const a = calls.stream[0]
  assert.equal(a[0], '问题')
  assert.deepEqual(a[1], ['data:i'])
  assert.equal(a[2], null)
  assert.equal(a[3], true)
  assert.equal(a[5], true)
  assert.deepEqual(a[6], [{ name: 'f', mime: 'text/plain', fileId: 'f1' }])
  assert.deepEqual(a[7], ['sk'])
  assert.equal(a[13], true)
})
