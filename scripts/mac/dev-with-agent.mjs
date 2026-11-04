#!/usr/bin/env node
/**
 * 开发态一键启动：vite + 本机执行器。
 *
 * 为什么能"一起起"：这个脚本跑在你这台机器的 Node 里（页面代码在浏览器沙箱里没有这个权限）。
 * 生产形态没有这个载体——那时靠执行器常驻（开机自启）或桌面壳。
 *
 * 环境变量：
 *   WENQU_EXECUTOR         设为 0 时只起前端，不拉执行器（默认随前端一起起）
 *   WENQU_EXECUTOR_SERVER  执行器连的服务器，默认取 VITE_PROXY_TARGET，再退 http://127.0.0.1:8090/ai
 *   WENQU_EXECUTOR_ROOT    浏览起点，默认 ~（--root / 可浏览整盘）
 *   WENQU_EXECUTOR_TOKEN   手工令牌，仅兼容旧用法；平时不需要——首次启动按终端提示在浏览器里授权即可
 *
 * 首次启动时执行器会打印链接 + 6 位码；在浏览器里登录问渠、确认后令牌自动存到
 * ~/.wenqu/executor.json（0600），之后每次启动直接复用。
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

const server = process.env.WENQU_EXECUTOR_SERVER || process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8090/ai'
const root = process.env.WENQU_EXECUTOR_ROOT || '~'
const web = process.env.WENQU_WEB_BASE || 'http://localhost:5800'
const disabled = ['0', 'false', 'off'].includes(String(process.env.WENQU_EXECUTOR || '').toLowerCase())

let agent = null
if (disabled) {
  console.log('[dev] WENQU_EXECUTOR=0：本机执行器未随前端启动（前端照常可用）')
} else {
  const args = [
    path.join(repoRoot, 'scripts', 'local-agent', 'wenqu-agent.mjs'),
    '--server', server,
    '--web', web,
    '--root', root
  ]
  if (process.env.WENQU_EXECUTOR_TOKEN) args.push('--token', process.env.WENQU_EXECUTOR_TOKEN)
  agent = spawn(process.execPath, args, { cwd: repoRoot, stdio: 'inherit' })
  agent.on('exit', code => {
    if (code && code !== 0) console.log(`[dev] 本机执行器已退出（code=${code}），前端继续运行`)
    agent = null
  })
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
