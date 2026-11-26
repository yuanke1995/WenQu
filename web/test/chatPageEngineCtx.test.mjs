import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// ChatPage 的 `g` 是「引擎值 → 各协作者」的唯一入口。这里漏一条不会报错：消费端
// （useChatChrome → useThinkPanel）对缺项都有兜底，只是兜底值是「不可见 / 空」——
// 2026-11 拆分 ChatPage 时就漏过 thinkCapsOf / levelOptionsOf / deepOnOf，
// 表现为运行设置里能选「中」，但模型胶囊上的档位标记静默消失（不报错、无告警）。
test('ChatPage 的引擎值集合覆盖思考档位三件套（模型胶囊标记消费）', () => {
  const src = readFileSync('src/views/ChatPage.vue', 'utf8')
  const m = /const g = \{[\s\S]*?\n\}/.exec(src)
  assert.ok(m, 'ChatPage.vue 里找不到 const g = { ... }')
  const body = m[0]
  for (const key of ['thinkCapsOf', 'levelOptionsOf', 'deepOnOf']) {
    assert.match(body, new RegExp(`(^|[\\s,{])${key}([\\s,}]|$)`, 'm'),
      `ChatPage 的 g 缺少 ${key}：模型胶囊/悬浮面板会拿到兜底值，档位标记静默不显示`)
  }
})