import test from 'node:test'
import assert from 'node:assert/strict'
import { groupSources, externalOrigin, sourceName, fmtSourceScore, scoreTitle } from '../src/chat/sources.js'

test('来源：联网/MCP 单独成组，库内按 docId 归组，无文档走兜底组', () => {
  const groups = groupSources([
    { origin: 'WEB', siteName: 'example.com' },
    { origin: 'MCP', siteName: 'DeepWiki' },
    { docId: 'd1', fileName: 'a.md' },
    { docId: 'd1', fileName: 'a.md' },
    { docId: 'd2' },
    { fileName: '手动片段' }
  ])
  assert.deepEqual(groups.map(g => g.key), ['__web', '__mcp', 'd1', 'd2', '__manual_手动片段'])
  assert.equal(groups[2].items.length, 2)
  assert.equal(groups[3].fileName, '来源文档不可用')
  assert.equal(groups[4].fileName, '手动片段')
  assert.deepEqual(groupSources(null), [])
})

test('来源：展示名与外部来源判定', () => {
  assert.equal(externalOrigin({ origin: 'WEB' }), true)
  assert.equal(externalOrigin({ origin: 'MCP' }), true)
  assert.equal(externalOrigin({ origin: 'KB' }), false)
  assert.equal(sourceName({ origin: 'WEB', siteName: 'example.com', title: '首页' }), 'example.com §首页')
  assert.equal(sourceName({ origin: 'WEB' }), '联网来源')
  assert.equal(sourceName({ origin: 'MCP' }), 'MCP 来源')
  assert.equal(sourceName({ docId: 'd1', fileName: 'a.md' }), 'a.md')
})

test('来源：分值优先重排，再回落原始分；口径标签区分来源', () => {
  assert.equal(fmtSourceScore({ rerankScore: 0.8123, score: 0.4 }), '0.81')
  assert.equal(fmtSourceScore({ score: 0.5 }), '0.50')
  assert.equal(fmtSourceScore({}), '')
  assert.equal(fmtSourceScore(null), '')
  assert.equal(scoreTitle({ rerankScore: 0.8 }), '重排相关度 0.8')
  assert.equal(scoreTitle({ origin: 'WEB', score: 0.6 }), '服务商相关度 0.6')
  assert.equal(scoreTitle({ origin: 'MCP', score: 0.6 }), '')
  assert.equal(scoreTitle({ score: 0.6 }), '检索融合分 0.6')
})
