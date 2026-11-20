#!/usr/bin/env node
/**
 * 问渠统一编排器：一个入口，三平台同一套命令。
 *
 * 改造前：wenqu.sh 是 bash + brew services，Windows 没有对等入口；dev-stack.sh 写死
 * /opt/homebrew、/Applications/Docker.app。改造后所有平台差异都在这里（进程调用、解释器选择、
 * compose 路径），wenqu.sh / wenqu.bat / wenqu.ps1 只是薄封装。
 *
 * 用法见 `node scripts/wenqu.mjs --help`。
 */
import fs from 'node:fs'
import path from 'node:path'
import net from 'node:net'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(SCRIPT_DIR, '..')
const WIN = process.platform === 'win32'
const GROUPS = ['dr', 'services', 'loadtest', 'mac', 'win']
const UNITS = ['mysql', 'redis', 'meili', 'searxng', 'sandbox', 'ocr']

const DESCRIPTIONS = {
  'dr/tenant-dr.py': '备份与按租户恢复（list/inventory/backup/restore/verify）',
  'services/mcp-test-server.py': '最小 MCP 测试服务(:8931)',
  'loadtest/mock-gateway.mjs': '压测 mock 网关(:9101)',
  'loadtest/loadtest.mjs': '100 路并发压测',
  'mac/check-enterprise.py': '企业级基底自检（脚本名保留分组，逻辑已跨平台）',
  'mac/check-agent-runtime.py': '智能体运行时验收（脚本名保留分组，逻辑已跨平台）',
  'mac/dev-stack.sh': '本机开发栈（薄封装 → wenqu infra）',
  'mac/start-backend.sh': '启动后端 jar',
  'win/start-backend.bat': '启动后端 jar (Windows)'
}

/** compose 单元：mysql/redis 用根 compose 并指定 service，避免连带启动 app。 */
const COMPOSE = {
  mysql: { file: 'docker-compose.yml', service: 'mysql', container: 'wenqu-mysql', port: 3306, desc: 'MySQL 数据库 (docker compose)' },
  redis: { file: 'docker-compose.yml', service: 'redis-stack', container: 'wenqu-redis-stack', port: 6379, desc: 'Redis Stack（向量库）(docker compose)' },
  meili: { file: 'deploy/meilisearch/docker-compose.yml', container: 'wenqu-meilisearch', port: 7700, desc: 'Meilisearch 关键词检索 (docker compose)' },
  searxng: { file: 'deploy/searxng/docker-compose.yml', container: 'wenqu-searxng', port: 8888, desc: 'SearXNG 联网搜索 (docker compose)' },
  sandbox: { file: 'deploy/sandbox-provisioner/docker-compose.yml', container: 'wenqu-sandbox-provisioner', port: 8002, desc: '代码沙盒 provisioner (docker compose)' },
  ocr: { file: 'deploy/ocr/docker-compose.yml', container: 'wenqu-mineru', port: 30011, desc: 'MinerU OCR (docker compose)' }
}

function run (cmd, args, opts = {}) {
  return spawnSync(cmd, args, { cwd: ROOT, stdio: 'inherit', windowsHide: true, ...opts })
}

function capture (cmd, args) {
  return spawnSync(cmd, args, { cwd: ROOT, encoding: 'utf8', windowsHide: true })
}

function listGroup (group) {
  const dir = path.join(SCRIPT_DIR, group)
  console.log(`▸ ${group}/`)
  if (!fs.existsSync(dir)) return
  for (const name of fs.readdirSync(dir).sort()) {
    const rel = `${group}/${name}`
    console.log(`    ${name.padEnd(42)} ${DESCRIPTIONS[rel] || ''}`)
  }
}

function listAll () {
  console.log('问渠脚本清单 (scripts/)')
  console.log('用法: node scripts/wenqu.mjs [group] [name] [args] | run <name> [args]')
  console.log('      node scripts/wenqu.mjs infra [up|down|restart|logs|status] [name|all]')
  console.log('')
  for (const g of GROUPS) { listGroup(g); console.log('') }
}

function findRel (name) {
  const visit = dir => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (['node_modules', '.venv', '__pycache__'].includes(entry.name)) continue
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        const found = visit(full)
        if (found) return found
      } else if (entry.name === name) {
        return path.relative(SCRIPT_DIR, full).split(path.sep).join('/')
      }
    }
    return null
  }
  return visit(SCRIPT_DIR)
}

function runFile (rel, args) {
  const file = path.join(SCRIPT_DIR, rel)
  if (!fs.existsSync(file)) throw new Error(`未找到脚本: ${rel}`)
  const ext = path.extname(file).toLowerCase()
  let cmd
  let cmdArgs
  if (ext === '.sh') { cmd = 'bash'; cmdArgs = [file] }
  else if (ext === '.py') { cmd = WIN ? 'python' : 'python3'; cmdArgs = [file] }
  else if (ext === '.js' || ext === '.mjs') { cmd = process.execPath; cmdArgs = [file] }
  else if (ext === '.bat' && WIN) { cmd = 'cmd.exe'; cmdArgs = ['/c', file] }
  else if (ext === '.ps1' && WIN) { cmd = 'powershell.exe'; cmdArgs = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', file] }
  else throw new Error(`不支持的脚本类型或未在当前平台提供入口: ${rel}`)
  const r = run(cmd, [...cmdArgs, ...args])
  process.exit(r.status == null ? 1 : r.status)
}

function portOpen (port) {
  return new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port })
    const done = v => { socket.destroy(); resolve(v) }
    socket.setTimeout(600)
    socket.on('connect', () => done(true))
    socket.on('error', () => done(false))
    socket.on('timeout', () => done(false))
  })
}

async function unitState (unit) {
  const spec = COMPOSE[unit]
  const ps = capture('docker', ['ps', '--format', '{{.Names}}'])
  if (ps.status === 0 && String(ps.stdout || '').split('\n').map(s => s.trim()).includes(spec.container)) {
    return 'running(docker)'
  }
  return (await portOpen(spec.port)) ? 'running' : 'stopped'
}

/** compose 必填环境变量：提前给出明确错误，不让用户去看 docker 的一长串报错。 */
const REQUIRED_ENV = {
  mysql: ['DB_PASSWORD'],
  meili: ['AI_MEILI_KEY']
}

function composeArgs (unit, action, opts = {}) {
  const spec = COMPOSE[unit]
  const args = ['compose', '-f', spec.file, action]
  if (opts.detached) args.push('-d')
  if (opts.follow) args.push('-f')
  if (spec.service) args.push(spec.service)
  return args
}

function infraList () {
  return (async () => {
    console.log('问渠中间件 (infra) — 全部由 docker compose 管理')
    console.log('用法: node scripts/wenqu.mjs infra [up|down|restart|logs|status] [name|all]')
    console.log('')
    console.log('    NAME      PORT      STATE             说明')
    for (const n of UNITS) {
      const state = await unitState(n)
      console.log(`    ${n.padEnd(9)} ${String(COMPOSE[n].port).padEnd(8)} ${state.padEnd(18)} ${COMPOSE[n].desc}`)
    }
  })()
}

async function infraDispatch (action, target) {
  const names = target === 'all' ? UNITS : [target]
  if (!names.every(n => UNITS.includes(n))) throw new Error(`未知中间件: ${target}（可选: ${UNITS.join(' ')}）`)
  for (const n of names) {
    if (action === 'status') {
      console.log(`    ${n.padEnd(9)} ${String(COMPOSE[n].port).padEnd(8)} ${await unitState(n)}`)
      continue
    }
    if (action === 'up' || action === 'down') {
      if (action === 'up') {
        const missing = (REQUIRED_ENV[n] || []).filter(k => !process.env[k])
        if (missing.length) throw new Error(`启动 ${n} 前请设置环境变量：${missing.join(', ')}`)
      }
      console.log(`▶ ${action} ${n}`)
      const r = run('docker', composeArgs(n, action, { detached: action === 'up' }))
      if (r.status !== 0) process.exitCode = r.status || 1
      continue
    }
    if (action === 'restart') {
      const missing = (REQUIRED_ENV[n] || []).filter(k => !process.env[k])
      if (missing.length) throw new Error(`重启 ${n} 前请设置环境变量：${missing.join(', ')}`)
      console.log(`↻ restart ${n}`)
      run('docker', composeArgs(n, 'down'))
      run('docker', composeArgs(n, 'up', { detached: true }))
      continue
    }
    if (action === 'logs') {
      const r = run('docker', composeArgs(n, 'logs', { follow: true }))
      process.exit(r.status == null ? 1 : r.status)
    }
  }
}

async function main () {
  const [cmd, ...args] = process.argv.slice(2)
  if (!cmd || ['-h', '--help', 'help'].includes(cmd)) return listAll()
  if (cmd === 'infra') {
    if (!args.length) return infraList()
    const action = args[0]
    if (!['up', 'down', 'restart', 'logs', 'status'].includes(action)) throw new Error(`未知 infra 动作: ${action}`)
    return infraDispatch(action, args[1] || 'all')
  }
  if (cmd === 'run') {
    if (!args.length) throw new Error('用法: wenqu run <脚本名> [args...]')
    const [name, ...rest] = args
    const rel = findRel(name)
    if (!rel) throw new Error(`未找到: ${name}`)
    return runFile(rel, rest)
  }
  if (GROUPS.includes(cmd)) {
    if (!args.length) return listGroup(cmd)
    const [name, ...rest] = args
    return runFile(`${cmd}/${name}`, rest)
  }
  const rel = findRel(cmd)
  if (!rel) throw new Error(`未知分组或脚本: ${cmd}（用 --help 查看清单）`)
  return runFile(rel, args)
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
if (isMain) main().catch(e => {
  console.error(`[wenqu] ${e.message}`)
  process.exit(1)
})

export { COMPOSE, DESCRIPTIONS, GROUPS, UNITS, composeArgs, findRel, portOpen }
