import test from 'node:test'
import assert from 'node:assert/strict'
import { askUserView, askAnswerLabel, askNoticeText } from '../src/chat/askUser.js'

test('提问卡：单问纯文本答案收敛成一条', () => {
  const v = askUserView({ args: JSON.stringify({ questions: [{ question: '选哪个？', options: ['A', 'B'] }] }), result: 'A' })
  assert.equal(v.many, true)
  assert.equal(v.questions[0].question, '选哪个？')
  assert.deepEqual(v.questions[0].options, ['A', 'B'])
  assert.equal(v.questions[0].answer, 'A')
})

test('提问卡：多问按答案数组对齐，未答显示「未作答」', () => {
  const v = askUserView({
    args: JSON.stringify({ questions: [{ question: 'q1' }, { question: 'q2', multi: 'true' }] }),
    result: JSON.stringify(['a1', '（用户未回答这一题，已跳过）']),
  })
  assert.equal(v.questions.length, 2)
  assert.equal(v.questions[1].answer, '未作答')
  assert.equal(v.questions[1].multi, true)
})

test('提问卡：入参截断时只显示答案', () => {
  const v = askUserView({ args: '{"questions":[{"quest', result: '答案' })
  assert.equal(v.notice, true)
  assert.equal(v.answer, '答案')
})

test('提问卡：系统说明按 notice 展示，不配问句', () => {
  const v = askUserView({ args: JSON.stringify({ questions: [{ question: 'q1' }, { question: 'q2' }] }), result: '（提问卡未发出）' })
  assert.equal(v.notice, true)
  assert.equal(v.answer, '（提问卡未发出）')
  assert.equal(askNoticeText('（系统说明）'), true)
  assert.equal(askNoticeText('（用户未回答这一题）'), false)
})

test('提问卡：未答前缀统一成「未作答」', () => {
  assert.equal(askAnswerLabel('（用户未回答这一题，已跳过）'), '未作答')
  assert.equal(askAnswerLabel('正常答案'), '正常答案')
})

test('答题口径：已答数、下一题、答案拼接', async () => {
  const mod = await import('../src/chat/askUser.js')
  const a = {
    questions: [{ options: ['A', 'B'] }, { options: ['C', 'D'], multi: true }, {}],
    picks: [[1], [0], []], customs: ['', '补充', ''], customOn: [false, true, false],
  }
  assert.equal(mod.askQuestionAnswered(a, 0), true)
  assert.equal(mod.askQuestionAnswered(a, 2), false)
  assert.equal(mod.askAnsweredCount(a), 2)
  assert.equal(mod.askNextUnanswered(a, 0), 2)
  assert.equal(mod.askAnswerText(a, 0), 'B')
  assert.equal(mod.askAnswerText(a, 1), 'C；补充')
  assert.equal(mod.askAutoSubmittable(a), false)
  const single = { questions: [{ options: ['A'] }, { options: ['B'] }], picks: [[0], [0]], customs: ['', ''], customOn: [false, false] }
  assert.equal(mod.askAutoSubmittable(single), true)
})

test('勾选与自定义：单选互斥、多选 toggle 且可与自定义并存', async () => {
  const mod = await import('../src/chat/askUser.js')
  const single = { questions: [{ options: ['A', 'B'] }], picks: [[]], customs: [''], customOn: [false] }
  mod.pickAskOption({ ask: single }, 0, 1)
  assert.deepEqual(single.picks[0], [1])
  assert.equal(mod.askOptionPicked(single, 0, 1), true)
  single.customs[0] = '自定义'
  mod.commitAskCustom({ ask: single }, 0)
  assert.deepEqual(single.picks[0], [])
  assert.equal(single.customOn[0], true)
  assert.equal(mod.askCustomPicked(single, 0), true)

  const multi = { questions: [{ options: ['A', 'B'], multi: true }], picks: [[]], customs: [''], customOn: [false] }
  mod.pickAskOption({ ask: multi }, 0, 0)
  mod.pickAskOption({ ask: multi }, 0, 1)
  assert.deepEqual(multi.picks[0], [0, 1])
  mod.pickAskOption({ ask: multi }, 0, 0)
  assert.deepEqual(multi.picks[0], [1])
  multi.customs[0] = '补充'
  mod.commitAskCustom({ ask: multi }, 0)
  assert.equal(multi.customOn[0], true)
  assert.deepEqual(multi.picks[0], [1])
})

test('提示文案：单选/含多选/多选三档', async () => {
  const mod = await import('../src/chat/askUser.js')
  assert.match(mod.askHintText({ questions: [{ options: ['A'] }] }, 0), /自动提交/)
  assert.match(mod.askHintText({ questions: [{ options: ['A'] }, { options: ['B'], multi: true }] }, 0), /要点提交/)
  assert.match(mod.askHintText({ questions: [{ options: ['A'], multi: true }] }, 0), /可勾多个/)
})

test('迟到作答：DSH answer_to_pending_question 同形；空答案＝跳过；气泡配对展示', async () => {
  const { continuedAnswerPayload, pendingAnswerText } = await import('../src/chat/askUser.js')
  const a = {
    id: 'call_1',
    questions: [{ question: '用哪种录入方式？', options: ['弹窗子表', '表内子表'] }, { question: '还要别的吗？' }],
    picks: [[0], []], customs: ['', ''], customOn: [false, false]
  }
  const payload = continuedAnswerPayload(a)
  const j = JSON.parse(payload)
  assert.equal(j.kind, 'answer_to_pending_question')
  assert.equal(j.tool, 'askUser')
  assert.equal(j.callId, 'call_1')
  assert.equal(j.questions.length, 2)
  assert.deepEqual(j.answers, [{ selected: '弹窗子表' }, { selected: '' }])

  const text = pendingAnswerText(payload)
  assert.match(text, /「用哪种录入方式？」：弹窗子表/)
  assert.match(text, /「还要别的吗？」：（跳过）/)
  assert.equal(pendingAnswerText('普通一句话'), null)
  assert.equal(pendingAnswerText('{"kind":"别的"}'), null)
})
