import test from 'node:test'
import assert from 'node:assert/strict'
import { isPastedText, pasteTitle, pasteSub } from '../src/chat/attachmentCard.js'

test('附件：粘贴文本按前缀或标记识别', () => {
  assert.equal(isPastedText({ name: '粘贴的文本.txt' }), true)
  assert.equal(isPastedText({ name: '普通.md', paste: true }), true)
  assert.equal(isPastedText({ name: '普通.md' }), false)
  assert.equal(isPastedText(null), false)
})

test('附件：粘贴标题去掉 .txt 后缀', () => {
  assert.equal(pasteTitle('粘贴的文本.txt'), '粘贴的文本')
  assert.equal(pasteTitle('普通.md'), '普通.md')
  assert.equal(pasteTitle(null), '')
})

test('附件：副标题优先状态，否则体积 + 字数', () => {
  assert.equal(pasteSub({ uploading: true, size: 10 }), '上传中…')
  assert.equal(pasteSub({ error: true }), '上传失败')
  assert.equal(pasteSub({ expired: true, text: 'x' }), '正文未随历史保留')
  assert.equal(pasteSub({ expired: true }), '已过保留期')
  assert.equal(pasteSub({ unavailable: true }), '不可重发')
  assert.equal(pasteSub({ size: 2048, text: 'abc' }), '2.0 KB · 3 字')
  assert.equal(pasteSub({ size: 0 }), '0 B')
})
