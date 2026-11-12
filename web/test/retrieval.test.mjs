import test from 'node:test'
import assert from 'node:assert/strict'
import { toolSearchQueries, retrievalLineTitle } from '../src/chat/retrieval.js'

test('检索词：只认终态的 searchKnowledge，args 非 JSON 忽略', () => {
  const m = { toolCalls: [
    { name: 'searchKnowledge', status: 'done', args: '{"query":"问渠"}' },
    { name: 'searchKnowledge', status: 'start', args: '{"query":"未完成"}' },
    { name: 'searchKnowledge', status: 'done', args: 'bad-json' },
    { name: 'read_file', status: 'done', args: '{"query":"不算"}' },
  ] }
  assert.deepEqual(toolSearchQueries(m), ['问渠'])
  assert.deepEqual(toolSearchQueries(null), [])
})

test('检索行：关键词+引用；无关键词但有精确检索给兜底标题；全空不输出', () => {
  assert.equal(retrievalLineTitle({ retrieved: { keywords: 3, refs: 5 } }), '搜索 3 个关键词，参考 5 段资料')
  assert.equal(retrievalLineTitle({ retrieved: { keywords: 0, refs: 2 } }), '参考 2 段资料')
  assert.equal(retrievalLineTitle({ sources: [{}, {}] }), '参考 2 段资料')
  assert.equal(retrievalLineTitle({ retrieved: { keywords: 0, refs: 0 }, toolCalls: [{ name: 'searchKnowledge', status: 'done', args: '{"query":"x"}' }] }), '精确检索')
  assert.equal(retrievalLineTitle({}), '')
})
