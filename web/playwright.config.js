import { defineConfig } from '@playwright/test'

// 冒烟层：只跑“页面能开、能问答、刷新后还在”这一条真实链路。
// 后端全部由 e2e/chat-smoke.spec.js 里的 page.route 假掉，不依赖 8090/线上环境。
export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5199',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
  webServer: {
    // 独立端口，避免和开发中的 5800/5801 打架；不设代理目标，API 全部走 page.route
    command: 'npm run dev:web -- --port 5199 --strictPort',
    url: 'http://localhost:5199',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})