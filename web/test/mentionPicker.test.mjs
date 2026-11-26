import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { createMentionPicker } from '../src/chat/mentionPicker.js'

const setup = ({ kbs = [], docs = [] } = {}) => {
  const notify = { warning: () => {}, error: () => {} }
  return createMentionPicker({
    listKbApi: () => Promise.resolve({ data: kbs }),
    listDocApi: () => Promise.resolve({ data: docs }),
    agentList: ref([]),
    notify
  })
}

test('引用候选屏蔽官方使用手册：内置库与其文档都不进候选', async () => {
  const p = setup({
    kbs: [
      { id: 'kb-default', name: '问渠', builtin: 0, docCount: 3 },
      { id: 'kb-manual', name: '问渠使用手册', builtin: 1, docCount: 16 }
    ],
    docs: [
      { id: 'd1', fileName: '业务资料.pdf', kbId: 'kb-default', status: 0 },
      { id: 'd2', fileName: '01-产品简介.md', kbId: 'kb-manual', status: 0 },
      { id: 'd3', fileName: '16-对接与集成.md', kbId: 'kb-manual', status: 0 }
    ]
  })
  await p.loadMentionCandidates()

  // 内置手册库与其篇目都被挡在候选之外，用户自己的库/文档不受影响
  assert.deepEqual(p.mentionKbs.value.map(k => k.id), ['kb-default'])
  assert.deepEqual(p.mentionDocs.value.map(d => d.id), ['d1'])
  // 页签计数与列表都读过滤后的数据，不会出现「计数 16、列表 0」这类错位
  assert.equal(p.mentionKbFiltered.value.length, 1)
  assert.equal(p.mentionDocFiltered.value.length, 1)
})

test('引用候选兼容：builtin 缺失/字符串、kbId 为空的文档照常保留', async () => {
  const p = setup({
    kbs: [
      { id: 'kb-a', name: 'A', docCount: 0 },                    // builtin 缺失 = 普通库
      { id: 'kb-b', name: 'B', builtin: '1', docCount: 0 },      // 字符串形式的 builtin 同样识别为内置
      { id: 'kb-c', name: 'C', builtin: null, docCount: 0 }      // null = 普通库
    ],
    docs: [
      { id: 'd1', fileName: '历史默认库文档.pdf', kbId: null, status: 0 },
      { id: 'd2', fileName: '内置库篇目.md', kbId: 'kb-b', status: 0 }
    ]
  })
  await p.loadMentionCandidates()

  assert.deepEqual(p.mentionKbs.value.map(k => k.id), ['kb-a', 'kb-c'])
  assert.deepEqual(p.mentionDocs.value.map(d => d.id), ['d1'])
})
