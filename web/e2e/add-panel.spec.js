import { test, expect } from '@playwright/test'

// 复现「+」面板点击无反应：主输入框 + 就地编辑卡两种入口。
// 后端全部 page.route 假掉，只验证前端点击链路。
const SID = 'probe-session'
const MODEL_REF = 'mock/m1'
const json = data => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })

const ANSWER = '这是冒烟回答。'
const HISTORY_ROWS = [
  { role: 'user', content: '冒烟提问', messageId: 'm-user', time: '2026-11-26 20:00:00' },
  { role: 'ai', content: ANSWER, messageId: 'm-ai', turnId: 't-1', model: MODEL_REF },
]
const sse = frames => ({ status: 200, contentType: 'text/event-stream', body: frames.map(f => `data:${JSON.stringify(f)}\n\n`).join('') })
const SSE_FRAMES = [
  { type: 'turn_start', content: { wallMs: 0, prepMs: 1 } },
  { type: 'token', content: '这是冒烟' },
  { type: 'token', content: '回答。' },
  { type: 'done', content: JSON.stringify({ finalContent: ANSWER, messageId: 'm-ai', turnId: 't-1', model: MODEL_REF, sources: [], related: [], turnWallMs: 1200 }) },
]

const ROUTES = [
  { match: (m, p) => m === 'POST' && p === '/chat', reply: () => sse(SSE_FRAMES) },
  { match: (m, p) => p.startsWith('/chat/resume'), reply: () => json({ running: false }) },
  { match: (m, p) => p.startsWith('/chat/run'), reply: () => json({ running: false }) },
  { match: (m, p) => p === '/auth/me', reply: () => json({ user: 'u', username: 'u', role: 'user', admin: false, menus: [] }) },
  { match: (m, p) => p === '/project/list', reply: () => json([]) },
  { match: (m, p) => p === '/sessions', reply: () => json({ items: [], nextCursor: '', hasMore: false, total: 0 }) },
  { match: (m, p) => p.startsWith('/session/new'), reply: () => json({ sessionId: SID }) },
  { match: (m, p) => p === `/session/${SID}`, reply: () => json(HISTORY_ROWS) },
  { match: (m, p) => p.endsWith('/model'), reply: () => json({ model: '' }) },
  { match: (m, p) => p.endsWith('/todos'), reply: () => json({ todos: null }) },
  { match: (m, p) => p.endsWith('/workspace'), reply: () => json(null) },
  { match: (m, p) => p.endsWith('/procs'), reply: () => json([]) },
  { match: (m, p) => p === '/agent/available' || p === '/agent/consultable', reply: () => json([]) },
  { match: (m, p) => p.startsWith('/skill/available'), reply: () => json([]) },
  { match: (m, p) => p === '/kb/list', reply: () => json([]) },
  { match: (m, p) => p === '/document/list', reply: () => json([]) },
  { match: (m, p) => p === '/config/public', reply: () => json({ ui: {} }) },
  { match: (m, p) => p === '/user/preference', reply: () => json({ defaultModel: MODEL_REF, defaultModelIssue: '' }) },
  { match: (m, p) => p === '/user/think-levels', reply: () => json({ levels: {} }) },
  { match: (m, p) => p === '/provider/available', reply: () => json([{ name: 'Mock', icon: '', models: [{ ref: MODEL_REF, displayName: 'Mock', type: 'chat' }] }]) },
]
const apiReply = (method, path) => (ROUTES.find(r => r.match(method, path)) || { reply: () => json(null) }).reply()

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('ai_token', 'probe-token'))
  await page.route('**/proxy/api/ai/**', route => {
    const req = route.request()
    const path = new URL(req.url()).pathname.replace('/proxy/api/ai', '')
    return route.fulfill(apiReply(req.method(), path))
  })
})

async function openChat (page) {
  const prefLoaded = page.waitForResponse(r => r.url().endsWith('/user/preference'))
  await page.goto('/chat')
  await prefLoaded
  await expect(page.locator('.composer-edit')).toBeVisible()
}

test('主输入框「+」面板三行点击均有反应', async ({ page }) => {
  const errors = []
  page.on('pageerror', e => errors.push(e.message))
  await openChat(page)

  await page.locator('.ci-attach').click()
  const panel = page.locator('.mention-panel')
  await expect(panel).toBeVisible()
  await expect(panel.locator('.add-title')).toHaveText('添加到本轮')

  await panel.getByText('引用资料', { exact: true }).click()
  await expect(panel.locator('.add-title')).toHaveText('引用资料')

  await panel.locator('.add-back').click()
  await expect(panel.locator('.add-title')).toHaveText('添加到本轮')

  await panel.getByText('引用历史问答', { exact: true }).click()
  await expect(panel.locator('.add-title')).toHaveText('引用历史问答')

  await panel.locator('.add-back').click()
  const chooser = page.waitForEvent('filechooser', { timeout: 3000 }).catch(() => null)
  await panel.getByText('上传文件', { exact: true }).click()
  expect(await chooser, '点击「上传文件」没有弹出文件选择器').not.toBeNull()

  expect(errors, `页面运行期异常：${errors.join(' | ')}`).toEqual([])
})

test('就地编辑卡「+」面板三行点击均有反应', async ({ page }) => {
  const errors = []
  page.on('pageerror', e => errors.push(e.message))
  await openChat(page)

  // 先发一问，产生可编辑的用户消息
  const editor = page.locator('.composer-edit')
  await editor.fill('冒烟提问')
  await editor.press('Enter')
  await expect(page.getByText(ANSWER, { exact: false }).first()).toBeVisible()

  // 刷新让历史接口回放带 messageId 的用户消息（编辑要求已落库）
  await page.reload()
  await expect(page.getByText(ANSWER, { exact: false }).first()).toBeVisible()

  // 进入就地编辑
  await expect(page.locator('.msg-edit-row')).toBeVisible()
  await page.locator('.msg-edit-row').getByRole('img', { name: 'edit' }).click()
  const edit = page.locator('.msg-inline-edit')
  await expect(edit).toBeVisible()

  await edit.locator('.add-btn').click()
  const panel = edit.locator('.mention-panel')
  await expect(panel).toBeVisible()
  await expect(panel.locator('.add-title')).toHaveText('添加到本轮')

  await panel.getByText('引用资料', { exact: true }).click()
  await expect(panel.locator('.add-title')).toHaveText('引用资料')

  await panel.locator('.add-back').click()
  await expect(panel.locator('.add-title')).toHaveText('添加到本轮')

  await panel.getByText('引用历史问答', { exact: true }).click()
  await expect(panel.locator('.add-title')).toHaveText('引用历史问答')

  await panel.locator('.add-back').click()
  const chooser = page.waitForEvent('filechooser', { timeout: 3000 }).catch(() => null)
  await panel.getByText('上传文件', { exact: true }).click()
  expect(await chooser, '编辑卡点「上传文件」没有弹出文件选择器').not.toBeNull()

  expect(errors, `页面运行期异常：${errors.join(' | ')}`).toEqual([])
})
