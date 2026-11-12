/**
 * 深度执行两轮 e2e（配 mock 网关）：/chat/deep 建目标 → 第 1 轮计划批准 →
 * goal_note 留缺口触发第 2 轮 → goal_submit 文本证据 → 目标 ACHIEVED。
 *
 * 用法：BASE=http://127.0.0.1:8099/ai TOKEN=<jwt> node scripts/loadtest/goal-e2e.mjs
 */
import crypto from 'node:crypto'

const BASE = process.env.BASE || 'http://127.0.0.1:8099/ai'
const TOKEN = process.env.TOKEN
if (!TOKEN) throw new Error('缺少 TOKEN')

const api = async (path, init = {}) => {
  const r = await fetch(BASE + path, {
    ...init,
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json', ...(init.headers || {}) },
  })
  const text = await r.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: r.status, json, text }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// 先注册 mock 供应商/模型并设为默认聊天模型：目标轮不带 model，按用户默认模型解析
const prov = await api('/api/ai/provider', {
  method: 'POST',
  body: JSON.stringify({ name: 'goalE2E Mock 网关', baseUrl: process.env.MOCK_BASE_URL || 'http://127.0.0.1:9101', apiKey: 'mock-key', enabled: true }),
})
const pid = prov.json?.data?.id ?? prov.json?.data
if (!pid) throw new Error('供应商创建失败：' + prov.text)
const saved = await api(`/api/ai/provider/${pid}/models`, {
  method: 'PUT',
  body: JSON.stringify([{ modelId: 'mock-chat', modelType: 'chat', displayName: 'Mock Chat', thinking: 'auto', contextWindow: 32768, maxOutput: 4096, enabled: true }]),
})
if (!saved.json?.success) throw new Error('模型登记失败：' + saved.text)
const pref = await api('/api/ai/user/preference', { method: 'PUT', body: JSON.stringify({ defaultModel: `${pid}/mock-chat` }) })
if (!pref.json?.success) throw new Error('默认模型设置失败：' + pref.text)
console.log('[goal] model', `${pid}/mock-chat`)

const sessionId = crypto.randomUUID()
const created = await api('/api/ai/chat/deep', {
  method: 'POST',
  body: JSON.stringify({ sessionId, objective: '[[GOAL]] mock 深度执行验收' }),
})
if (!created.json?.success) throw new Error('建目标失败：' + created.text)
const goalId = created.json.data.goalId
console.log('[goal] created', goalId)

let approved = false
for (let i = 0; i < 80 && !approved; i++) {
  await sleep(500)
  const p = await api(`/api/ai/deep/plan-approval/pending?sessionId=${encodeURIComponent(sessionId)}`)
  const id = p.json?.data?.items?.[0]?.planApprovalId
  if (!id) continue
  const r = await api(`/api/ai/deep/plan-approval/${encodeURIComponent(id)}`, {
    method: 'POST', body: JSON.stringify({ approved: true }),
  })
  if (!r.json?.success) throw new Error('批准失败：' + r.text)
  approved = true
  console.log('[goal] plan approved', id)
}
if (!approved) throw new Error('40s 内没有出现计划卡')

let goal = null
for (let i = 0; i < 160; i++) {
  await sleep(500)
  const r = await api(`/api/ai/chat/session/${encodeURIComponent(sessionId)}/deep`)
  goal = r.json?.data
  if (goal && ['ACHIEVED', 'FAILED', 'CANCELLED'].includes(goal.status)) break
}
console.log('[goal] final', JSON.stringify({ status: goal?.status, round: goal?.round, endReason: goal?.endReason, outcome: goal?.outcome }))
if (goal?.status !== 'ACHIEVED') {
  throw new Error('目标未达成：' + JSON.stringify(goal))
}
console.log('✓ 深度执行：计划批准 → 跨轮续跑 → goal_submit 验收通过')
