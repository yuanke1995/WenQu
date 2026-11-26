import { test, expect } from '@playwright/test'

// 整页冒烟：真实浏览器 + 真实前端包，只有后端是假的（page.route 拦截 /proxy/api/ai/**）。
// 覆盖三件过去只能靠人眼的事：
//   1. 页面能开：拆分后 setup 抛错/TDZ/子组件静默消失；
//   2. 问答能跑：SSE 回调表装配正确（token 上屏、done 收尾）；
//   3. 刷新后还在：历史接口回放同一条回答。
// 后端契约（帧名/字段）以 src/chat/eventContract.generated.js 与 api.js 为准。

const SID = 'smoke-session'
const ANSWER = '这是冒烟回答。'
const MODEL_REF = 'mock/m1'

const json = data => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})
const sse = frames => ({ status: 200, contentType: 'text/event-stream', body: frames.map(f => `data:${JSON.stringify(f)}\n\n`).join('') })

const SSE_FRAMES = [
  { type: 'turn_start', content: { wallMs: 0, prepMs: 1 } },
  { type: 'token', content: '这是冒烟' },
  { type: 'token', content: '回答。' },
  {
    type: 'done',
    content: JSON.stringify({
      finalContent: ANSWER,
      messageId: 'm-ai',
      turnId: 't-1',
      model: MODEL_REF,
      sources: [],
      related: [],
      turnWallMs: 1200,
    }),
  },
]

const HISTORY_ROWS = [
  { role: 'user', content: '冒烟提问', messageId: 'm-user' },
  { role: 'ai', content: ANSWER, messageId: 'm-ai', turnId: 't-1', model: MODEL_REF },
]

// 假后端路由表：match(method, path) → fulfill 参数。用表而不是 if 链，既压住圈复杂度，
// 也让“这个冒烟依赖哪些端点”一眼可数。
const ROUTES = [
  // ---- 问答流：这是 smoke 的主角 ----
  { match: (m, p) => m === 'POST' && p === '/chat', reply: () => sse(SSE_FRAMES) },
  {
    match: (m, p) => p.startsWith('/chat/resume'),
    reply: () => sse([{ type: 'snapshot', content: JSON.stringify({ running: false }) }]),
  },
  { match: (m, p) => p.startsWith('/chat/run'), reply: () => json({ running: false }) },

  // ---- 首屏与身份 ----
  {
    match: (m, p) => p === '/auth/me',
    reply: () => json({ user: 'u-smoke', username: 'smoke', role: 'user', admin: false, menus: [] }),
  },
  { match: (m, p) => p === '/project/list', reply: () => json([]) },
  { match: (m, p) => p === '/sessions', reply: () => json({ items: [], nextCursor: '', hasMore: false, total: 0 }) },
  { match: (m, p) => p.startsWith('/session/new'), reply: () => json({ sessionId: SID }) },
  { match: (m, p) => p === `/session/${SID}`, reply: () => json(HISTORY_ROWS) },
  { match: (m, p) => p.endsWith('/model'), reply: () => json({ model: '' }) },
  { match: (m, p) => p.endsWith('/todos'), reply: () => json({ todos: null }) },
  { match: (m, p) => p.endsWith('/workspace'), reply: () => json(null) },
  { match: (m, p) => p.endsWith('/procs'), reply: () => json([]) },

  // ---- 下拉候选与配置 ----
  { match: (m, p) => p === '/agent/available' || p === '/agent/consultable', reply: () => json([]) },
  { match: (m, p) => p.startsWith('/skill/available'), reply: () => json([]) },
  { match: (m, p) => p === '/config/public', reply: () => json({ ui: {} }) },
  { match: (m, p) => p === '/user/preference', reply: () => json({ defaultModel: MODEL_REF, defaultModelIssue: '' }) },
  { match: (m, p) => p === '/user/think-levels', reply: () => json({ levels: {} }) },
  {
    match: (m, p) => p === '/provider/available',
    reply: () => json([{ name: 'Mock', icon: '', models: [{ ref: MODEL_REF, displayName: 'Mock 模型', type: 'chat' }] }]),
  },
]

const apiReply = (method, path) => {
  const hit = ROUTES.find(r => r.match(method, path))
  // 其余（通知、待办角标、沙盒目录等）一律给成功空载荷：冒烟不关心它们的内容
  return hit ? hit.reply() : json(null)
}

test.beforeEach(async ({ page }) => {
  // 登录态：路由守卫与 api.js 都只认 localStorage 里的令牌
  await page.addInitScript(() => localStorage.setItem('ai_token', 'smoke-token'))

  await page.route('**/proxy/api/ai/**', route => {
    const req = route.request()
    const path = new URL(req.url()).pathname.replace('/proxy/api/ai', '')
    return route.fulfill(apiReply(req.method(), path))
  })
})

test('发问 → 流式回答 → done；刷新后历史仍在', async ({ page }) => {
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))

  const prefLoaded = page.waitForResponse(r => r.url().endsWith('/user/preference'))
  await page.goto('/chat')
  await prefLoaded
  // 个人默认模型是“发得出去”的前提（异步回填），等它落地再输入
  await expect(page.locator('.composer-edit')).toBeVisible()

  const editor = page.locator('.composer-edit')
  await editor.fill('冒烟提问')
  await editor.press('Enter')

  // token 上屏 + done 收尾后正文定格（气泡里能按整句找到）
  await expect(page.getByText(ANSWER, { exact: false }).first()).toBeVisible()
  expect(pageErrors, `页面运行期异常：${pageErrors.join(' | ')}`).toEqual([])

  // 刷新：同一条历史回放（ask 卡片/计划卡的恢复路径不参与，这里只验正文）
  await page.reload()
  await expect(page.getByText(ANSWER, { exact: false }).first()).toBeVisible()
  expect(pageErrors, `刷新后页面运行期异常：${pageErrors.join(' | ')}`).toEqual([])
})