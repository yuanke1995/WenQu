#!/usr/bin/env node
/**
 * 开发态一键启动：vite + 本机执行器。
 *
 * 为什么能"一起起"：这个脚本跑在你这台机器的 Node 里（页面代码在浏览器沙箱里没有这个权限）。
 * 执行器只跑在用户电脑上：线上服务只提供 HTTP 接口，不启动执行器；
 * 别的入口要连线上后端时，把 VITE_PROXY_TARGET 指过去即可，执行器会跟着连同一个 server。
 *
 * 环境变量：
 *   WENQU_EXECUTOR_SERVER  执行器连的服务器，默认取 VITE_PROXY_TARGET，再退 http://127.0.0.1:8090/ai
 *   WENQU_EXECUTOR_ROOT    浏览起点，默认 ~（--root / 可浏览整盘）
 *   WENQU_EXECUTOR_TOKEN   手工令牌，平时不需要——首次启动按终端提示在浏览器里授权即可
 *   WENQU_EXECUTOR_REQUIRE_HTTPS=1  强制远程服务器使用 https（默认临时允许 http，只告警）
 *   WENQU_EXECUTOR_INSECURE=1       与 REQUIRE_HTTPS 同时出现时仍允许 http（应急用）
 *
 * 首次启动时执行器会打印链接 + 6 位码；在浏览器里登录问渠、确认后令牌自动存到
 * ~/.wenqu/executor.json（0600），之后每次启动直接复用。
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '..', '..')
const webRoot = path.join(repoRoot, 'web')

const server = process.env.WENQU_EXECUTOR_SERVER || process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8090/ai'
const root = process.env.WENQU_EXECUTOR_ROOT || '~'
const web = process.env.WENQU_WEB_BASE || 'http://localhost:5800'

/** 本机执行器身份：令牌文件按 server 分键，取出 executorId 注入给 vite，前端据此只列这台电脑的工作区。
 *  首次授权前文件还不存在（vite 与执行器同时起），此时不注入——在页面「这台电脑」里选一次即可。 */
function localExecutorId () {
  try {
    const cfg = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.wenqu', 'executor.json'), 'utf8'))
    const entry = cfg && cfg.servers ? cfg.servers[server] : null
    return entry && entry.executorId ? String(entry.executorId) : ''
  } catch { return '' }
}
const viteEnv = { ...process.env }
const localId = localExecutorId()
if (localId) viteEnv.VITE_LOCAL_EXECUTOR_ID = localId

const vite = spawn(process.execPath, [path.join(webRoot, 'node_modules', 'vite', 'bin', 'vite.js'), ...process.argv.slice(2)], {
  cwd: webRoot,
  stdio: 'inherit',
  env: viteEnv
})
const args = [
  path.join(repoRoot, 'scripts', 'local-agent', 'wenqu-agent.mjs'),
  '--server', server,
  '--web', web,
  '--root', root
]
if (process.env.WENQU_EXECUTOR_TOKEN) args.push('--token', process.env.WENQU_EXECUTOR_TOKEN)
if (/^(1|true|yes)$/i.test(String(process.env.WENQU_EXECUTOR_REQUIRE_HTTPS || '').trim())) args.push('--require-https')
if (/^(1|true|yes)$/i.test(String(process.env.WENQU_EXECUTOR_INSECURE || '').trim())) args.push('--insecure')
let agent = spawn(process.execPath, args, { cwd: repoRoot, stdio: 'inherit' })
agent.on('exit', code => {
  if (code && code !== 0) console.log(`[dev] 本机执行器已退出（code=${code}），前端继续运行`)
  agent = null
})

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
