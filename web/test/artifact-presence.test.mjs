import test from 'node:test'
import assert from 'node:assert/strict'
import { artifactPendingOf } from '../src/chat/artifactPending.js'
import { pickPreviewLines, looksLikeFormJson } from '../src/utils/artifactPreview.js'

const msg = patch => ({
  role: 'ai',
  loading: true,
  artifacts: [],
  toolCalls: [],
  ...patch
})

test('artifactPendingOf：presentArtifact 生成入参时给出文件名与已接收字数', () => {
  const p = artifactPendingOf(msg({
    toolCalls: [{
      name: 'presentArtifact',
      status: 'start',
      preparing: true,
      brief: 'form.json',
      chars: 12300
    }]
  }))
  assert.deepEqual(p, { kind: 'generate', filename: 'form.json', chars: 12300 })
})

test('artifactPendingOf：deliver_artifact 从路径末段取文件名', () => {
  const p = artifactPendingOf(msg({
    toolCalls: [{
      name: 'deliver_artifact',
      status: 'start',
      args: JSON.stringify({ filePath: '/tmp/out/审批表.xlsx' })
    }]
  }))
  assert.equal(p.kind, 'deliver')
  assert.equal(p.filename, '审批表.xlsx')
})

test('artifactPendingOf：只认交付类工具，普通工具不占位', () => {
  const p = artifactPendingOf(msg({
    toolCalls: [{ name: 'write_file', status: 'start', preparing: true, chars: 900 }]
  }))
  assert.equal(p, null)
})

test('artifactPendingOf：已有产物 / 已结束 / 非回答消息都不出占位', () => {
  const base = [{
    name: 'presentArtifact', status: 'start', preparing: true, brief: 'a.md', chars: 600
  }]
  assert.equal(artifactPendingOf(msg({ artifacts: [{ id: 1 }], toolCalls: base })), null)
  assert.equal(artifactPendingOf(msg({ loading: false, toolCalls: base })), null)
  assert.equal(artifactPendingOf(msg({ role: 'user', toolCalls: base })), null)
  assert.equal(artifactPendingOf(msg({
    toolCalls: [{ name: 'presentArtifact', status: 'done' }]
  })), null)
})

test('pickPreviewLines：去掉 Markdown 标记与分隔线，保留标题重字', () => {
  const lines = pickPreviewLines('## 字段\n\n| --- | --- |\n**姓名**：张三\n- 性别', 4)
  assert.deepEqual(lines.map(l => l.text), ['字段', '姓名：张三', '性别'])
  assert.equal(lines[0].head, true)
})

test('looksLikeFormJson：只认带 BasicTable/matrix 的表单结构', () => {
  const form = JSON.stringify({
    name: '工作申请表',
    components: [{ name: 'BasicTable', instance: { matrix: [] } }]
  })
  assert.equal(looksLikeFormJson(form), true)
  assert.equal(looksLikeFormJson('{"a":1}'), false)
  assert.equal(looksLikeFormJson('{"components":[{"name":"BasicInput"}]}'), false)
  // 截断的半截 JSON 不能抛错，交给文本行兜底
  assert.equal(looksLikeFormJson('{"components":[{"name":"BasicTable"'), false)
})
