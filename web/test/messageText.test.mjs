import test from 'node:test'
import assert from 'node:assert/strict'
import { histItemTitle, histItemDigest, fmtMsgTime, errorBrief } from '../src/chat/messageText.js'

test('历史标题：剥 Markdown 标记、压空白、截 60 字', () => {
  assert.equal(histItemTitle({ content: '# 标题\n\n**正文** 内容' }), '标题 正文 内容')
  assert.equal(histItemTitle({ content: 'a'.repeat(80) }).length, 60)
  assert.equal(histItemTitle(null), '')
  assert.equal(histItemDigest({ content: 'x' }), 'x')
})

test('消息时间：今天/昨天/更早三档', () => {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 9, 5)
  assert.equal(fmtMsgTime(today.getTime()), '今天 09:05')
  const yest = new Date(today); yest.setDate(today.getDate() - 1)
  assert.equal(fmtMsgTime(yest.getTime()), '昨天 09:05')
  const old = new Date(today); old.setDate(today.getDate() - 5)
  const mm = String(old.getMonth() + 1).padStart(2, '0')
  const dd = String(old.getDate()).padStart(2, '0')
  assert.equal(fmtMsgTime(old.getTime()), `${mm}-${dd} 09:05`)
  assert.equal(fmtMsgTime(null), '')
  assert.equal(fmtMsgTime('not-a-date'), '')
})

test('错误文案：spoken/interrupted/unreachable 优先，额度先于限流，其余按文本分类', () => {
  assert.equal(errorBrief('该充值了', 'spoken'), '该充值了')
  assert.equal(errorBrief('', 'spoken'), '生成失败，可重试或更换模型')
  assert.equal(errorBrief('Read timeout', 'interrupted'), '响应超时：连接已中断')
  assert.equal(errorBrief('连接提前关闭', 'interrupted'), '连接已断开：回答未正常结束')
  assert.equal(errorBrief('whatever', 'interrupted'), '连接已中断：回答未正常结束')
  // 未送达：不能沿用「回答断在半路」那套文案
  assert.equal(errorBrief('请求失败: Failed to fetch', 'unreachable'), '没能连上服务器：这条提问没有发出去，请重试')
  assert.equal(errorBrief('insufficient_quota 429'), '模型服务额度不足：请到「模型供应商」充值，或改用其他模型')
  assert.equal(errorBrief('429 rate limit'), '请求过于频繁：模型服务限流，稍后重试')
  assert.equal(errorBrief('401 Unauthorized'), '鉴权失败：登录已过期，请重新登录')
  assert.equal(errorBrief('502 Bad Gateway'), '模型服务异常：稍后重试，或到「模型供应商」检查该供应商状态')
  assert.equal(errorBrief('AbortError'), '生成已停止')
  assert.equal(errorBrief('unknown'), '生成失败，可重试或更换模型')
})
