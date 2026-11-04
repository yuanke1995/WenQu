#!/usr/bin/env node
/**
 * 开发态一键启动：vite + 本机执行器。
 *
 * 为什么能"一起起"：这个脚本跑在你这台机器的 Node 里（页面代码在浏览器沙箱里没有这个权限）。
 * 生产形态没有这个载体——那时靠执行器常驻（开机自启）或桌面壳。
 *
 * 环境变量：
 *   WENQU_EXECUTOR_TOKEN   设备令牌（在问渠「选择工作区目录 → 添加这台电脑」里拿）；不设=只起前端
 *   WENQU_EXECUTOR_SERVER  执行器连的服务器，默认取 VITE_PROXY_TARGET，再退 http://127.0.0.1:8090/ai
 *   WENQU_EXECUTOR_ROOT    浏览起点，默认 ~（--root / 可浏览整盘）
 */
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '..', '..')
const webRoot = path.join(repoRoot, 'web')

const vite = spawn(process.execPath, [path.join(webRoot, 'node_modules', 'vite', 'bin', 'vite.js'), ...process.argv.slice(2)], {
  cwd: webRoot,
  stdio: 'inherit'
})

const token = process.env.WENQU_EXECUTOR_TOKEN
const server = process.env.WENQU_EXECUTOR_SERVER || process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8090/ai'
const root = process.env.WENQU_EXECUTOR_ROOT || '~'

let agent = null
if (token) {
  agent = spawn(process.execPath, [
    path.join(repoRoot, 'scripts', 'local-agent', 'wenqu-agent.mjs'),
    '--server', server,
    '--token', token,
    '--root', root
  ], { cwd: repoRoot, stdio: 'inherit' })
  agent.on('exit', code => {
    if (code && code !== 0) console.log(`[dev] 本机执行器已退出（code=${code}），前端继续运行`)
    agent = null
  })
} else {
  console.log('[dev] 未设置 WENQU_EXECUTOR_TOKEN：本机执行器未随前端启动（前端照常可用）')
}

const stopAgent = () => {
  if (!agent) return
  try { agent.kill('SIGTERM') } catch { /* 已退出 */ }
  agent = null
}

process.on('SIGINT', () => { stopAgent(); try { vite.kill('SIGTERM') } catch { /* ignore */ } process.exit(0) })
process.on('SIGTERM', () => { stopAgent(); try { vite.kill('SIGTERM') } catch { /* ignore */ } process.exit(0) })

vite.on('exit', code => {
  stopAgent()
  process.exit(code == null ? 0 : code)
})
