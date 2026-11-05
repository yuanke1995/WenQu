#!/usr/bin/env node
/**
 * 问渠本机执行器：只负责执行，不拿知识库、不拿模型 Key。
 *
 * 用法：
 *   node scripts/local-agent/wenqu-agent.mjs --server http://127.0.0.1:8090/ai [--root ~]
 *
 * 首次启动会在终端打印一条链接和一个 6 位码：在浏览器里登录问渠、确认后，令牌自动写入
 * ~/.wenqu/executor.json（0600），之后启动直接复用——令牌不进命令行、shell 历史和环境变量。
 * 授权被撤销或失效时会自动重新走一遍。
 *
 * 可选：
 *   --token <设备令牌>  手工指定（仅兼容旧用法；平时不需要）
 *   --web <页面地址>    授权链接的页面地址，默认按 --server 推断（如 http://<host>）
 *   --ask            写文件/跑命令前在终端逐次确认（默认不问：权限档位已经由页面选择器定好，
 *                    执行器再弹一次终端确认会让浏览器里的这一轮一直等不到返回）
 *   --name <名字>    设备名（默认主机名）
 *   --require-https  强制远程服务器使用 https（默认临时允许 http，只打印明文告警）
 *   --insecure       与 --require-https 同时出现时仍允许 http（应急用；也可用 WENQU_EXECUTOR_INSECURE=1）
 *   --timeout <秒>   单次轮询挂起时长（默认 25）
 *   --data-dir <目录> 轮文件改动快照的本地存储目录（默认 ~/.wenqu/executor；内容不出这台电脑）
 *   --turn-ttl-days <天>    快照保留天数（默认 14）
 *   --turn-max <轮数>       最多保留多少轮（默认 200）
 *   --turn-cache-max-mb <MB> 快照目录容量上限（默认 2048）
 *
 * 约束：
 *   - --root 只是"浏览起点"（默认家目录；--root / 即整盘）；工作区调用只允许落在本次工作区的源文件夹内，
 *     越界一律 E_PATH_OUTSIDE；只有 danger-full-access 档才放开。
 *   - 写文件走临时文件 + rename，避免写一半留下坏文件。
 */
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import readline from 'node:readline'
import { createHash } from 'node:crypto'
import { spawn, execFile } from 'node:child_process'

const argv = process.argv.slice(2)
const arg = (name, dflt) => {
  const i = argv.indexOf('--' + name)
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt
}
const flag = name => argv.includes('--' + name)
const mult = name => {
  const out = []
  argv.forEach((v, i) => {
    if (v === '--' + name && argv[i + 1]) out.push(argv[i + 1])
  })
  return out
}

const SERVER = (arg('server', 'http://127.0.0.1:8090/ai') || '').replace(/\/+$/, '')
const INSECURE = flag('insecure') || /^(1|true|yes)$/i.test(String(process.env.WENQU_EXECUTOR_INSECURE || '').trim())
/** 临时默认允许远程 http（启动时打印明文警告）；显式加 --require-https 才硬性拒绝。 */
const REQUIRE_HTTPS = flag('require-https')
  || /^(1|true|yes)$/i.test(String(process.env.WENQU_EXECUTOR_REQUIRE_HTTPS || '').trim())
const NAME = arg('name', os.hostname())
const WAIT = Math.max(5, Math.min(30, Number(arg('timeout', '25')) || 25))
/** 执行器协议版本：v2 起 poll 会带这个头，服务端据此启用 ack 租约与重投；
 *  旧服务端不认识也无害（ack 404 时按旧协议放行），旧 agent 不带这个头则服务端按旧协议处理。 */
const AGENT_VERSION = '2'

/** 远程 HTTP 目前临时放行，但启动时明确告警：设备令牌与文件内容都在 Bearer/HTTP 体里，
 *  明文过网等于把本机文件读写权交给中间人。正式环境请换 https；
 *  想现在就强制只允许 https，加 --require-https（本机回环地址始终不受限）。 */
function checkTransportSecurity (server) {
  let u
  try { u = new URL(server) } catch {
    console.error(`[问渠] 服务器地址不合法：${server}`)
    process.exit(2)
  }
  const host = (u.hostname || '').toLowerCase()
  const loopback = host === '127.0.0.1' || host === 'localhost' || host === '::1' || host === '[::1]'
  if (u.protocol === 'https:' || loopback) return
  if (REQUIRE_HTTPS && !INSECURE) {
    console.error('[问渠] 已启用 --require-https：远程服务器必须使用 https://。'
      + '如确需明文，请显式加 --insecure。')
    process.exit(2)
  }
  console.warn('[问渠] 当前用 http 连接远程服务器：设备令牌与文件内容会明文过网。'
    + '正式环境请改用 https；可用 --require-https 强制校验。')
}
checkTransportSecurity(SERVER)
const AUTO_YES = flag('yes')
/** 只有显式 --ask 才在终端逐次确认；默认信任服务端下发的权限档位（页面上的选择器才是闸门） */
const ASK = flag('ask')
/** 浏览与执行的起点（默认家目录；`--root /` 即整盘）。
 *  与 DSH 同一口径：宿主把目录树交给界面浏览，选中的目录成为工作区根；
 *  每次调用再单独校验"落在本次工作区根内"——起点的宽窄不代替工作区边界。 */
const ROOT = path.resolve((arg('root', arg('dir', os.homedir())) || os.homedir())
  .replace(/^~(?=$|\/)/, os.homedir()))

const log = (...a) => console.log(`[${new Date().toISOString()}]`, ...a)

// ==================== 设备授权 ====================

const TOKEN_FILE = path.join(os.homedir(), '.wenqu', 'executor.json')
/** 手工令牌（命令行/环境变量）只在显式给出时使用；平时靠本地保存的授权 */
const PROVIDED_TOKEN = arg('token', process.env.WENQU_EXECUTOR_TOKEN || '')
/** 授权链接指向的页面地址：默认从 --server 推断（去掉末尾的 /ai 或 /proxy） */
const WEB = (arg('web', process.env.WENQU_WEB_BASE || '') || SERVER.replace(/\/(ai|proxy)$/i, ''))
  .replace(/\/+$/, '')
let TOKEN = ''

const sleep = ms => new Promise(r => setTimeout(r, ms))
/** 给 fetch 加硬超时：网络挂起时不要把轮询循环/心跳一起拖死。 */
const timeoutSignal = ms => (typeof AbortSignal !== 'undefined' && AbortSignal.timeout
  ? AbortSignal.timeout(ms) : undefined)

function readTokenFile () {
  try { return JSON.parse(fs.readFileSync(TOKEN_FILE, 'utf8')) || {} } catch { return {} }
}

function savedToken () {
  const cfg = readTokenFile()
  const entry = cfg && cfg.servers ? cfg.servers[SERVER] : null
  return entry && entry.token ? entry : null
}

function writeTokenFile (cfg) {
  fs.mkdirSync(path.dirname(TOKEN_FILE), { recursive: true, mode: 0o700 })
  const tmp = `${TOKEN_FILE}.${process.pid}.tmp`
  fs.writeFileSync(tmp, JSON.stringify(cfg, null, 2), { mode: 0o600 })
  fs.renameSync(tmp, TOKEN_FILE)
  // 文件已存在时 writeFileSync 的 mode 不生效，这里兜一把（Windows 上 chmod 不完整，靠目录 ACL）
  try { fs.chmodSync(TOKEN_FILE, 0o600) } catch { /* ignore */ }
}

function saveToken (token, executorId) {
  const cfg = readTokenFile()
  if (!cfg.servers || typeof cfg.servers !== 'object') cfg.servers = {}
  cfg.servers[SERVER] = {
    token,
    executorId: executorId || '',
    name: NAME,
    savedAt: new Date().toISOString()
  }
  writeTokenFile(cfg)
}

function forgetToken () {
  const cfg = readTokenFile()
  if (!cfg.servers || !cfg.servers[SERVER]) return
  delete cfg.servers[SERVER]
  writeTokenFile(cfg)
}

// ==================== 单实例锁 ====================
//
// 同一个服务器只允许一个执行器进程：两个进程用同一令牌会把调用分走，
// 同一轮的 turnStart / write / turnEnd 可能落在不同进程上，快照和顺序都会乱。

let lockFile = ''

function lockPathOf (server) {
  const dir = path.join(os.homedir(), '.wenqu', 'locks')
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
  try { fs.chmodSync(dir, 0o700) } catch { /* ignore */ }
  return path.join(dir, createHash('sha1').update(String(server)).digest('hex').slice(0, 16) + '.lock')
}

function releaseSingleInstanceLock () {
  if (!lockFile) return
  try { fs.unlinkSync(lockFile) } catch { /* 已被清理 */ }
  lockFile = ''
}

function acquireSingleInstanceLock () {
  const file = lockPathOf(SERVER)
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const fd = fs.openSync(file, 'wx', 0o600)
      try {
        fs.writeFileSync(fd, JSON.stringify({
          pid: process.pid, server: SERVER, startedAt: new Date().toISOString()
        }))
      } finally {
        fs.closeSync(fd)
      }
      lockFile = file
      return
    } catch (e) {
      if (e.code !== 'EEXIST') throw e
      let info = null
      try { info = JSON.parse(fs.readFileSync(file, 'utf8')) } catch { /* 损坏锁按过期处理 */ }
      const pid = info && Number(info.pid)
      let alive = false
      if (pid) {
        try { process.kill(pid, 0); alive = true } catch (err) { alive = err.code === 'EPERM' }
      }
      if (alive) {
        log(`同一个服务器已有一个执行器在运行（pid=${pid}），本进程退出。`)
        process.exit(4)
      }
      try { fs.unlinkSync(file) } catch { /* 已被别的进程清掉 */ }
    }
  }
  throw err('E_LOCK', '无法获取执行器单实例锁')
}

process.on('exit', releaseSingleInstanceLock)
process.on('SIGINT', () => { releaseSingleInstanceLock(); process.exit(0) })
process.on('SIGTERM', () => { releaseSingleInstanceLock(); process.exit(0) })

async function postJson (url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
    signal: timeoutSignal(15_000)
  })
  if (!res.ok) {
    const e = new Error(`HTTP ${res.status}`)
    e.status = res.status
    throw e
  }
  const envelope = await res.json()
  if (envelope && envelope.success === false) {
    const e = new Error(envelope.msg || '请求失败')
    e.business = true
    throw e
  }
  return (envelope && envelope.data) || {}
}

// ==================== 心跳 ====================
//
// 长轮询只在取任务前后刷新 last_seen；一条 120s 的命令会把执行器「挂」在服务端
// 的 70s 在线窗口之外，后续调用会被误判离线。心跳独立于轮询，命令执行期间照常发。

let heartbeatTimer = null
let heartbeatWarned = false
let heartbeatUnsupported = false
let legacyServerWarned = false

function startHeartbeat () {
  if (heartbeatTimer) return
  const beat = async () => {
    if (!TOKEN || heartbeatUnsupported) return
    try {
      const res = await fetch(`${SERVER}/api/ai/executor/heartbeat`, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + TOKEN },
        signal: timeoutSignal(5_000)
      })
      if (res.status === 401) {
        if (!heartbeatWarned) log('心跳被拒（设备令牌失效），等待主循环重新授权')
        heartbeatWarned = true
        return
      }
      // 旧服务端没有心跳端点：长轮询本身会刷新在线状态，停发即可（别每 20s 刷一条失败日志）
      if (res.status === 404 || res.status === 405) {
        if (!heartbeatUnsupported) log('服务端不支持心跳（旧版本），在线状态由长轮询维持')
        heartbeatUnsupported = true
        return
      }
      if (!res.ok) log('心跳失败:', res.status)
      else heartbeatWarned = false
    } catch (e) {
      log('心跳失败:', e.message)
    }
  }
  heartbeatTimer = setInterval(beat, 20_000)
  if (heartbeatTimer.unref) heartbeatTimer.unref()
  beat()
}

/** 设备授权：打印链接 + 6 位码，等浏览器确认后把令牌写到本地文件。 */
async function authorize () {
  let start
  try {
    start = await postJson(`${SERVER}/api/ai/executor/device-code`, { name: NAME, os: process.platform })
  } catch (e) {
    // 401/404 不是"连不上"：旧版后端会把未知端点交给登录门禁，返回的就是 401
    if (e.status === 401 || e.status === 404) {
      console.error(`这台服务器（${SERVER}）还没有设备授权入口（HTTP ${e.status}）：` +
        '后端需要升级到支持授权码的版本；临时可在启动时手工给 --token')
    } else {
      console.error(`无法向服务器申请授权（${SERVER}）：${e.message}`)
    }
    return false
  }
  if (!start.deviceCode) {
    console.error('服务器没有返回授权码，请确认后端版本')
    return false
  }
  const link = `${WEB}/pair?code=${encodeURIComponent(start.userCode)}`
  console.log('')
  console.log('  这台电脑还没有授权。请打开下面的链接，登录你的问渠账号并点确认：')
  console.log(`    ${link}`)
  console.log(`  链接打不开时：打开 ${WEB}/pair ，输入授权码 ${start.userCode}`)
  console.log('  授权码 10 分钟内有效，确认后这里会自动继续。')
  console.log('')
  const deadline = Date.now() + Math.max(30, Number(start.expiresIn) || 600) * 1000
  let waiting = false
  while (Date.now() < deadline) {
    await sleep(3000)
    try {
      const data = await postJson(`${SERVER}/api/ai/executor/device-code/token`, { deviceCode: start.deviceCode })
      if (data.status === 'approved' && data.token) {
        saveToken(data.token, data.executorId)
        TOKEN = data.token
        console.log(`已授权：「${data.name || NAME}」加入成功，令牌已保存到 ${TOKEN_FILE}`)
        return true
      }
      if (data.status === 'expired' || data.status === 'consumed') {
        console.error('授权码已过期，请重新启动执行器。')
        return false
      }
      if (!waiting) {
        console.log('等待浏览器确认…')
        waiting = true
      }
    } catch (e) {
      console.error(`查询授权结果失败，稍后重试：${e.message}`)
    }
  }
  console.error('授权码已过期，请重新启动执行器。')
  return false
}

// ==================== 路径围栏 ====================

/** 每次调用的上下文：roots/policy 不能放全局——读操作并行后，甲调用会读到乙调用的根。 */
function callContext (args, deadline) {
  const validRoot = r => r && String(r.path || '').trim()
  const roots = Array.isArray(args.roots)
    ? args.roots.filter(validRoot).map(r => path.resolve(String(r.path)))
    : (args.root && String(args.root).trim() ? [path.resolve(String(args.root))] : [])
  const rootRecords = Array.isArray(args.roots)
    ? args.roots.filter(validRoot)
        .map(r => ({ alias: String(r.alias || ''), path: path.resolve(String(r.path)), primary: !!r.primary }))
    : []
  let primaryRoot = roots[0] || null
  if (Array.isArray(args.roots)) {
    const p = args.roots.find(r => validRoot(r) && r.primary)
    if (p) primaryRoot = path.resolve(String(p.path))
  }
  const mark = p => {
    const abs = path.resolve(String(p || ''))
    try { return fs.realpathSync.native(abs) } catch { return abs }
  }
  return {
    roots,
    rootRecords,
    primaryRoot,
    realRoots: roots.map(mark),
    policy: args.policy === 'danger-full-access' ? 'danger-full-access' : 'workspace-write',
    previousTurnId: String(args.previousTurnId || '').trim(),
    deadline: Number(deadline) || 0
  }
}

const curRoot = ctx => ctx.primaryRoot || ROOT

/** 尽量取真实路径：目标不存在时一路向上找最近的已存在祖先，再把缺失的尾段拼回去。
 *  只规范一层父目录不够——多级新目录的上级仍可能是软链，围栏会被绕出去。 */
function realPathLoose (p) {
  const abs = path.resolve(String(p || ''))
  const tail = []
  let cur = abs
  for (;;) {
    try {
      const real = fs.realpathSync.native(cur)
      return tail.length ? path.join(real, ...tail.reverse()) : real
    } catch { /* 不存在，继续向上 */ }
    const parent = path.dirname(cur)
    if (parent === cur) return abs
    tail.push(path.basename(cur))
    cur = parent
  }
}

function realRootOfBrowse () {
  const abs = path.resolve(ROOT)
  try { return fs.realpathSync.native(abs) } catch { return abs }
}

/** 解析并校验：只允许落在本次工作区的某个源文件夹内。
 *  主目录是相对路径与命令执行的基准；`danger-full-access` 档才允许越出这些目录（与 DSH 的完全权限同义）。
 *  校验按真实路径做——父目录是软链时，词法前缀正确但实际目标可能在根外。 */
function resolveInRoot(p, ctx) {
  const raw = String(p || '').trim()
  if (!raw) throw err('E_IO', '缺少路径')
  const abs = path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(curRoot(ctx), raw)
  if (ctx.policy === 'danger-full-access') return abs
  const real = realPathLoose(abs)
  if (ctx.roots.length === 0) {
    // 没有工作区根（比如「选择目录」的逐层浏览）：只允许落在浏览起点内
    const browseReal = realRootOfBrowse()
    if (real === browseReal || real.startsWith(browseReal + path.sep)) return abs
    throw err('E_PATH_OUTSIDE', `路径不在浏览起点内：${abs}`)
  }
  const inside = ctx.realRoots.some(r => real === r || real.startsWith(r + path.sep))
  if (!inside) throw err('E_PATH_OUTSIDE', `路径不在工作区目录内：${abs}`)
  return abs
}

/** 工作区里的软链一律拒绝：它可能把一次读写引出源文件夹（lexical 前缀是对的，真实路径不是）。 */
async function rejectSymlink(file) {
  const st = await fsp.lstat(file)
  if (st.isSymbolicLink()) throw err('E_PATH_OUTSIDE', `软链接不参与工作区操作：${file}`)
  return st
}

function err(code, message) {
  const e = new Error(message)
  e.code = code
  return e
}

// ==================== 确认 ====================

let rl = null
async function confirm(action, detail, userInitiated) {
  if (AUTO_YES || userInitiated || !ASK) return true
  if (!rl) rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  const ans = await new Promise(resolve => rl.question(`\n[问渠] ${action}\n${detail}\n允许吗？(y/N) `, resolve))
  return /^y(es)?$/i.test(String(ans).trim())
}

// ==================== 操作实现 ====================

async function opList({ path: p }, ctx) {
  // 没有路径 = 列主目录；没有工作区根时列浏览起点（网页「选择目录」逐层浏览用）
  const dir = p ? resolveInRoot(p, ctx) : (ctx.roots.length ? curRoot(ctx) : ROOT)
  const entries = await fsp.readdir(dir, { withFileTypes: true })
  const out = []
  for (const e of entries) {
    const full = path.join(dir, e.name)
    let st = null
    try { st = await fsp.lstat(full) } catch { continue }   // 断链忽略
    // 软链不参与：它可能指向工作区外，列表/预览/删除都不该把它当普通条目
    if (st.isSymbolicLink()) {
      out.push({ path: full, name: e.name, isDir: false, isSymlink: true, size: 0, mtimeMs: 0 })
      continue
    }
    out.push({ path: full, name: e.name, isDir: st.isDirectory(), isSymlink: false, size: st.size, mtimeMs: st.mtimeMs })
  }
  out.sort((a, b) => (a.isDir === b.isDir ? a.name.localeCompare(b.name) : a.isDir ? -1 : 1))
  return { path: dir, entries: out }
}

/** 递归找文件：按 basename 通配（* 和 ?），只回文件。 */
async function opFind({ path: p, pattern }, ctx) {
  const dir = resolveInRoot(p, ctx)
  const re = globToRegExp(pattern || '*')
  const out = []
  const walk = async (d) => {
    if (out.length >= 5000) return
    let entries = []
    try { entries = await fsp.readdir(d, { withFileTypes: true }) } catch { return }
    for (const e of entries) {
      if (out.length >= 5000) return
      const full = path.join(d, e.name)
      if (e.isSymbolicLink()) continue
      if (e.isDirectory()) {
        if (e.name === 'node_modules' || e.name === '.git') continue
        await walk(full)
      } else if (re.test(e.name)) {
        let st = null
        try { st = await fsp.stat(full) } catch { continue }
        out.push({ path: full, isDir: false, size: st.size, mtimeMs: st.mtimeMs })
      }
    }
  }
  await walk(dir)
  out.sort((a, b) => a.path.localeCompare(b.path))
  return { entries: out }
}

function globToRegExp(g) {
  let s = ''
  for (const c of String(g)) {
    if (c === '*') s += '[^/]*'
    else if (c === '?') s += '[^/]'
    else s += c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  }
  return new RegExp('^' + s + '$')
}

/** 递归内容搜索（grep_files 工具）：固定串匹配，跳隐藏/依赖目录与二进制，结果截 200 条。 */
async function opGrep({ path: p, pattern, glob, limit }, ctx) {
  const needle = String(pattern || '')
  if (!needle) throw err('E_INVALID', '缺少搜索内容')
  const base = resolveInRoot(p, ctx)
  const nameRe = glob ? globToRegExp(glob) : null
  const cap = Math.max(1, Math.min(Number(limit) || 200, 1000))
  const matches = []
  let truncated = false
  const SKIP = new Set(['.git', 'node_modules', '__pycache__'])
  const walk = async (dir) => {
    if (truncated) return
    let entries = []
    try { entries = await fsp.readdir(dir, { withFileTypes: true }) } catch { return }
    for (const e of entries) {
      if (truncated) return
      if (e.name.startsWith('.') || SKIP.has(e.name)) continue
      const full = path.join(dir, e.name)
      if (e.isSymbolicLink()) continue
      if (e.isDirectory()) { await walk(full); continue }
      if (!e.isFile()) continue
      if (nameRe && !nameRe.test(e.name)) continue
      let buf = null
      try { buf = await fsp.readFile(full) } catch { continue }
      if (buf.subarray(0, Math.min(8000, buf.length)).includes(0)) continue   // 二进制
      const lines = buf.toString('utf8').split('\n')
      for (let i = 0; i < lines.length; i++) {
        if (lines[i].includes(needle)) {
          matches.push({ path: full, line: i + 1, text: lines[i].slice(0, 500) })
          if (matches.length >= cap) { truncated = true; break }
        }
      }
    }
  }
  await walk(base)
  return { matches, truncated }
}

async function opRead({ path: p, offset, limit, turnId }, ctx) {
  const file = resolveInRoot(p, ctx)
  await rejectSymlink(file)
  const buf = await fsp.readFile(file)
  // 用户改动对账：模型读到最新内容后，这一轮允许覆盖该文件（仍然要过 write 的 expectedHash 之外的围栏）
  await clearDriftAfterRead(turnId, file)
  if (buf.includes(0)) return { content: '', encoding: 'base64', binary: true }
  const text = buf.toString('utf8')
  const lines = text.split('\n')
  const start = Math.max(0, Number(offset) || 0)
  const end = limit && Number(limit) > 0 ? start + Number(limit) : lines.length
  return { content: lines.slice(start, end).join('\n'), encoding: 'utf-8', totalLines: lines.length }
}

async function opReadBytes({ path: p, offset, length }, ctx) {
  const file = resolveInRoot(p, ctx)
  const st = await rejectSymlink(file)
  const start = Math.max(0, Number(offset) || 0)
  const want = Number(length) > 0 ? Math.min(Number(length), st.size - start) : st.size - start
  if (start > st.size) return { contentBase64: '', size: 0, totalBytes: st.size }
  const fh = await fsp.open(file, 'r')
  try {
    const buf = Buffer.alloc(Math.max(0, want))
    await fh.read(buf, 0, buf.length, start)
    return { contentBase64: buf.toString('base64'), size: buf.length, totalBytes: st.size }
  } finally {
    await fh.close()
  }
}

async function opStat({ path: p }, ctx) {
  const file = resolveInRoot(p, ctx)
  const st = await rejectSymlink(file)
  return { path: file, name: path.basename(file), isDir: st.isDirectory(), size: st.size, mtimeMs: st.mtimeMs }
}

/** 递归列出目录（相对路径），跳过隐藏项与依赖目录；条数与深度都设上限。 */
async function opTree({ path: p, maxFiles, maxDepth }, ctx) {
  const dir = resolveInRoot(p, ctx)
  const cap = Math.max(1, Math.min(Number(maxFiles) || 5000, 50000))
  const depthCap = Math.max(1, Math.min(Number(maxDepth) || 8, 32))
  const out = []
  let capped = false
  const walk = async (d, rel, depth) => {
    if (out.length >= cap) { capped = true; return }
    if (depth > depthCap) return
    let entries = []
    try { entries = await fsp.readdir(d, { withFileTypes: true }) } catch { return }
    for (const e of entries) {
      if (out.length >= cap) { capped = true; return }
      if (e.name.startsWith('.') || e.name === 'node_modules' || e.name === '__pycache__' || e.name === '.git') continue
      const full = path.join(d, e.name)
      const next = rel ? rel + '/' + e.name : e.name
      let st = null
      try { st = await fsp.lstat(full) } catch { continue }
      if (st.isSymbolicLink()) continue
      if (st.isDirectory()) {
        out.push({ path: next, name: e.name, isDir: true, size: 0, mtimeMs: st.mtimeMs })
        await walk(full, next, depth + 1)
      } else if (st.isFile()) {
        out.push({ path: next, name: e.name, isDir: false, size: st.size, mtimeMs: st.mtimeMs })
      }
    }
  }
  await walk(dir, '', 1)
  return { path: dir, entries: out, capped }
}

async function opMkdir({ path: p, userInitiated }, ctx) {
  const dir = resolveInRoot(p, ctx)
  const ok = await confirm('新建文件夹', dir, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次新建')
  deadlineGuard(ctx)
  await fsp.mkdir(dir, { recursive: false })
  return { path: dir }
}

async function opMove({ from, to, replace, userInitiated, turnId }, ctx) {
  const src = resolveInRoot(from, ctx)
  const dst = resolveInRoot(to, ctx)
  if (src === dst) return { path: dst }
  // 根目录本身不能作为移动/替换的目标：replace=true 时会把工作区根整个删掉再换掉
  const srcReal = realPathLoose(src)
  const dstReal = realPathLoose(dst)
  if (ctx.roots.includes(src) || ctx.realRoots.includes(srcReal)
      || ctx.roots.includes(dst) || ctx.realRoots.includes(dstReal)) {
    throw err('E_INVALID', '不能移动或替换工作区根目录本身')
  }
  await captureBeforeMutation(turnId, src, ctx)
  const ok = await confirm('移动/重命名', `${src}\n→ ${dst}`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次移动')
  // 确认卡片可能等很久；服务端已超时就不该再动文件
  deadlineGuard(ctx)
  const st = await rejectSymlink(src)
  try { await rejectSymlink(dst) } catch (e) { if (e.code !== 'ENOENT') throw e }
  if (replace) {
    try { await fsp.rm(dst, { recursive: true, force: true }) } catch { /* 不存在 */ }
  } else {
    try { await fsp.lstat(dst); throw err('E_EXISTS', `目标位置已存在：${dst}`) } catch (e) { if (e.code === 'E_EXISTS') throw e }
  }
  if (st.isDirectory()) {
    // 不能把文件夹挪进它自己里面
    if ((dst + path.sep).startsWith(src + path.sep)) throw err('E_INVALID', '不能把文件夹放进它自己里面')
    await fsp.rename(src, dst)
  } else {
    await fsp.mkdir(path.dirname(dst), { recursive: true })
    try {
      await fsp.rename(src, dst)
    } catch (e) {
      if (e.code !== 'EXDEV') throw e
      await fsp.copyFile(src, dst)
      await fsp.unlink(src)
    }
  }
  return { path: dst }
}

async function opRemove({ path: p, userInitiated, turnId }, ctx) {
  const target = resolveInRoot(p, ctx)
  const targetReal = realPathLoose(target)
  if (ctx.roots.includes(target) || ctx.realRoots.includes(targetReal)) {
    throw err('E_INVALID', '不能删除工作区根目录本身')
  }
  await rejectSymlink(target)
  await captureBeforeMutation(turnId, target, ctx)
  const ok = await confirm('删除', target, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次删除')
  deadlineGuard(ctx)
  await fsp.rm(target, { recursive: true, force: false })
  return { removed: true, path: target }
}

/** 在宿主那台电脑上用系统文件管理器打开一个目录（Finder / Explorer / 文件管理器）。 */
async function opOpenPath({ path: p }, ctx) {
  const target = resolveInRoot(p, ctx)
  const platform = process.platform
  const cmd = platform === 'darwin' ? 'open' : (platform === 'win32' ? 'explorer' : 'xdg-open')
  await new Promise((resolve, reject) => {
    execFile(cmd, [target], { timeout: 15000 }, e => {
      if (e) return reject(err('E_IO', '打不开文件夹：' + (e.message || '')))
      resolve()
    })
  })
  return { path: target }
}

async function opWriteChunk({ path: p, offset, contentBase64, turnId }, ctx) {
  const file = resolveInRoot(p, ctx)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
  await captureBeforeMutation(turnId, file, ctx)
  // 与 writeAtomic 同理：上传/分段写也自动补建上级目录（围栏已过）
  await fsp.mkdir(path.dirname(file), { recursive: true }).catch(e => { if (e.code !== 'EEXIST') throw e })
  const buf = Buffer.from(contentBase64 || '', 'base64')
  const off = Math.max(0, Number(offset) || 0)
  const fh = await fsp.open(file, off === 0 ? 'w' : 'r+')
  try {
    await fh.write(buf, 0, buf.length, off)
  } finally {
    await fh.close()
  }
  return { path: file, bytes: buf.length, offset: off }
}

async function opWrite({ path: p, content, expectedHash, userInitiated, turnId }, ctx) {
  const file = resolveInRoot(p, ctx)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
  // write_file 没有 expectedHash：用户上一次答复后改过的文件，模型必须先读再覆盖。
  // 这条与 edit 的 expectedHash 是两回事——edit 有原文可校，write 是整文件替换，没这道就静默丢用户改动。
  await assertNoUserDrift(turnId, file, userInitiated)
  // 读-改-写冲突检测：服务端读到的原文哈希必须仍是盘上的原文。
  // 没有这一道，用户在编辑器里同时改同一文件，模型的整文件写入会静默覆盖。
  if (expectedHash) {
    let current = Buffer.alloc(0)
    try { current = await fsp.readFile(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
    const actual = createHash('sha256').update(current).digest('hex')
    if (actual !== String(expectedHash)) {
      throw err('E_CONFLICT', '文件已被其他人修改，请重新读取后再改')
    }
  }
  await captureBeforeMutation(turnId, file, ctx)
  const ok = await confirm('写入文件', `${file}\n（${Buffer.byteLength(content || '')} 字节）`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次写入')
  deadlineGuard(ctx)
  await writeAtomic(file, Buffer.from(content || '', 'utf8'), expectedHash)
  return { path: file, bytes: Buffer.byteLength(content || '') }
}

async function opWriteBytes({ path: p, contentBase64, userInitiated, turnId }, ctx) {
  const file = resolveInRoot(p, ctx)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
  await captureBeforeMutation(turnId, file, ctx)
  const buf = Buffer.from(contentBase64 || '', 'base64')
  const ok = await confirm('写入文件', `${file}\n（${buf.length} 字节）`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次写入')
  deadlineGuard(ctx)
  await writeAtomic(file, buf)
  return { path: file, bytes: buf.length }
}

async function writeAtomic(file, buf, expectedHash) {
  const dir = path.dirname(file)
  // 写文件自动补建上级目录：框架的文件工具没有 mkdir，模型写「子目录/新文件」时
  // 目录还不存在就直接 ENOENT。父目录已在 resolveInRoot 围栏内，这里建是安全的。
  await fsp.mkdir(dir, { recursive: true })
  const tmp = path.join(dir, `.wenqu-${Date.now()}-${Math.random().toString(16).slice(2)}.tmp`)
  await fsp.writeFile(tmp, buf)
  // 最终一致性再查一次：确认卡片可能等了几分钟，用户在等待期间完全可能改过这个文件。
  if (expectedHash) {
    let current = Buffer.alloc(0)
    try {
      current = await fsp.readFile(file)
    } catch (e) {
      if (e.code !== 'ENOENT') {
        await fsp.unlink(tmp).catch(() => {})
        throw e
      }
    }
    const actual = createHash('sha256').update(current).digest('hex')
    if (actual !== String(expectedHash)) {
      await fsp.unlink(tmp).catch(() => {})
      throw err('E_CONFLICT', '文件已被其他人修改，请重新读取后再改')
    }
  }
  await fsp.rename(tmp, file)
}

// ==================== 命令执行围栏 ====================
//
// 文件工具能按 roots 收束，shell 不能靠字符串分析约束。标准档下用 OS 沙箱：
// macOS = sandbox-exec（写只允许 roots + 临时目录），Linux = bwrap（/ 只读，roots 可写）。
// 没有可用沙箱就拒绝执行——宁可不跑命令，也不裸跑一条能写全盘的 shell。
// danger-full-access 是用户显式选择的完全权限，不套沙箱。

function detectShellSandbox () {
  if (process.platform === 'darwin') {
    try {
      fs.accessSync('/usr/bin/sandbox-exec', fs.constants.X_OK)
      return 'sandbox-exec'
    } catch { /* 没有就不跑 */ }
  }
  if (process.platform === 'linux') {
    for (const p of ['/usr/bin/bwrap', '/bin/bwrap']) {
      try {
        fs.accessSync(p, fs.constants.X_OK)
        return 'bwrap'
      } catch { /* 继续找 */ }
    }
  }
  return null
}

const SHELL_SANDBOX = detectShellSandbox()

function profilePath (p) {
  return String(p).replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

function macShellProfile (roots) {
  const writable = [
    ...roots,
    '/private/tmp', '/tmp',
    '/private/var/folders', '/var/folders',
    '/dev'
  ]
  const lines = ['(version 1)', '(allow default)', '(deny file-write*)', '(allow file-write*']
  for (const p of writable) lines.push(`  (subpath "${profilePath(p)}")`)
  lines.push(')')
  // 注意：sandbox-exec 的 deny 会被 (allow default) 盖住，读隔离做不到（实测）。
  // 这里只承诺“写”围栏；读敏感文件仍须靠 standard 默认审批与 autonomous 分层。
  return lines.join('\n')
}

function linuxShellArgs (roots, cwd, command) {
  const args = ['--die-with-parent', '--ro-bind', '/', '/', '--dev', '/dev', '--proc', '/proc', '--tmpfs', '/tmp', '--chdir', cwd]
  for (const r of roots) args.push('--bind', r, r)
  for (const p of ['.wenqu', '.ssh', '.aws', '.kube', '.docker']) {
    const abs = path.join(os.homedir(), p)
    if (fs.existsSync(abs)) args.push('--tmpfs', abs)
  }
  args.push('/bin/sh', '-c', command)
  return args
}

/** 生成这次命令的 spawn 参数；没有可用沙箱时抛错，由上层回 E_SANDBOX_UNAVAILABLE。 */
function shellSpawn (command, ctx) {
  if (ctx.policy === 'danger-full-access') {
    return { cmd: '/bin/sh', args: ['-c', String(command)] }
  }
  if (SHELL_SANDBOX === 'sandbox-exec') {
    return {
      cmd: '/usr/bin/sandbox-exec',
      args: ['-p', macShellProfile(ctx.realRoots), '/bin/sh', '-c', String(command)]
    }
  }
  if (SHELL_SANDBOX === 'bwrap') {
    return {
      cmd: '/usr/bin/bwrap',
      args: linuxShellArgs(ctx.realRoots, curRoot(ctx), String(command))
    }
  }
  throw err('E_SANDBOX_UNAVAILABLE',
    '当前平台没有可用的命令沙箱（macOS 需要 sandbox-exec，Linux 需要 bwrap）：'
    + '工作区内修改档不执行 shell。请改用文件工具；确实需要命令时请把会话权限调到完全权限。')
}

async function opExec({ command, timeoutSeconds, userInitiated, turnId }, ctx) {
  const ok = await confirm('执行命令', `cwd: ${curRoot(ctx)}\n$ ${command}`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次命令')
  if (turnId) await ensureTurnState(turnId, ctx)
  deadlineGuard(ctx)
  const requested = Math.max(1, Number(timeoutSeconds) || 120)
  const remaining = ctx.deadline ? Math.floor((ctx.deadline - Date.now()) / 1000) : requested
  if (ctx.deadline && remaining <= 0) throw err('E_TIMEOUT', '服务端等待时限已过，命令未执行')
  const seconds = Math.max(1, Math.min(requested, remaining))
  const shell = shellSpawn(command, ctx)
  return await new Promise(resolve => {
    const child = spawn(shell.cmd, shell.args, { cwd: curRoot(ctx), env: process.env })
    let out = ''
    let truncated = false
    const cap = 1024 * 1024
    const onData = d => {
      if (out.length < cap) out += d.toString('utf8')
      if (out.length >= cap) truncated = true
    }
    child.stdout.on('data', onData)
    child.stderr.on('data', onData)
    const timer = setTimeout(() => {
      try { child.kill('SIGKILL') } catch { /* ignore */ }
    }, seconds * 1000)
    child.on('close', code => {
      clearTimeout(timer)
      resolve({ output: out.slice(0, cap), exitCode: typeof code === 'number' ? code : 124, truncated })
    })
    child.on('error', e => {
      clearTimeout(timer)
      resolve({ output: `启动命令失败：${e.message}`, exitCode: 124, truncated: false })
    })
  })
}

// ==================== 每轮文件改动（turn changes） ====================
//
// DSH workspace-changes 同款口径：轮前/轮后各给工作树拍一份 git 快照（私有 object store +
// 私有 index，用户的仓库全程只读），轮末 diff-tree --numstat 得出每个文件的增删行数；
// 快照覆盖不到的路径（非 git 目录、被 ignore 的文件）靠「写类调用改动前整文件捕获」兜底。
// 快照对象与捕获副本全部留在这台电脑的磁盘里（默认 ~/.wenqu/executor/turns，0700），
// 重启执行器后仍能按 turnId 找回；回传给服务端的只有「相对路径 + 行数」，点开某个文件的差异时
// 才由这里现算 hunk。例外：轮首用户改动对账（detectDrift）会主动带回一小段 diff（≤3 文件/200 行），
// 只用于让模型先读再改，服务端不落库。

/** 快照数据的本地根目录；turns/ 下每轮一个目录（state.json + 私有对象 + 改动前副本）。 */
const DATA_DIR = path.resolve(
  (arg('data-dir', process.env.WENQU_EXECUTOR_DATA || '') || path.join(os.homedir(), '.wenqu', 'executor'))
    .replace(/^~(?=$|\/)/, os.homedir()))
const TURN_STORE = path.join(DATA_DIR, 'turns')
/** 保留时长、轮数、总容量：三者先到先清，按最旧淘汰。 */
const TURN_TTL_MS = Math.max(1, Number(arg('turn-ttl-days', '14')) || 14) * 24 * 60 * 60 * 1000
const TURN_MAX_STATES = Math.max(1, Number(arg('turn-max', '200')) || 200)
const TURN_CACHE_MAX_BYTES = Math.max(1, Number(arg('turn-cache-max-mb', '2048')) || 2048) * 1024 * 1024
/** 没有 state.json 的目录过了这个宽限期才清：避免误删另一个执行器进程正在写的轮。 */
const TURN_ORPHAN_GRACE_MS = 60 * 60 * 1000
/** 单文件捕获/读回上限（字节）：超过只列出、不给差异。 */
const TURN_FILE_CAP = 2 * 1024 * 1024
/** 单条 git 命令的超时与输出上限。 */
const TURN_GIT_TIMEOUT_MS = 30_000
const TURN_OUT_CAP = 8 * 1024 * 1024
/** 行比对的预算：超时或改动过密都降级成「整文件替换」，与 DSH 的 diffTimeout 口径一致。 */
const TURN_DIFF_DEADLINE_MS = 100
const TURN_DIFF_LINE_BUDGET = 6000
const TURN_DIFF_MAX_D = 512
/** 一份 summary 最多列出的文件数（total 仍报完整数）。 */
const TURN_MAX_FILES = 200
/** 判二进制：前 8000 字节里有没有 NUL。 */
const BINARY_PROBE_BYTES = 8000

/** turnId → 本轮状态（内存索引；落盘副本在 TURN_STORE 下，进程重启时由 loadTurnStates 读回）。 */
const turnStates = new Map()

function turnStateOf (id) {
  return turnStates.get(String(id || '')) || null
}

/** 状态目录名：turnId 可能带冒号等字符（目标轮），统一转成文件系统安全的名字。 */
function turnDirName (id) {
  return /^[A-Za-z0-9_-]{1,120}$/.test(id) ? id : 'h-' + createHash('sha1').update(String(id)).digest('hex')
}

function turnStatePath (id) {
  return path.join(TURN_STORE, turnDirName(String(id || '')))
}

/** 目录实际占用（对象 + 副本 + state.json），用于容量淘汰。 */
async function dirBytes (dir) {
  let total = 0
  let entries
  try { entries = await fsp.readdir(dir, { withFileTypes: true }) } catch { return 0 }
  for (const e of entries) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) total += await dirBytes(p)
    else if (e.isFile()) { try { total += (await fsp.stat(p)).size } catch { /* 读不到就不计 */ } }
  }
  return total
}

/** 轮状态落盘：state.json 原子写；review 是 Map，存成 [展示路径, 来源] 数组。 */
async function saveTurnState (state) {
  const file = path.join(state.scratch, 'state.json')
  const body = {
    version: 1,
    turnId: state.turnId,
    createdAt: state.createdAt,
    done: !!state.done,
    bytes: state.bytes || 0,
    roots: state.roots,
    drift: [...(state.drift || [])],
    postCaptures: [...(state.postCaptures || [])],
    repos: state.repos.map(r => ({
      root: r.root, gitDir: r.gitDir, repoObjects: r.repoObjects,
      rootPaths: r.rootPaths, tree: r.tree || null, afterTree: r.afterTree || null
    })),
    review: [...state.review.entries()]
  }
  await writeAtomic(file, Buffer.from(JSON.stringify(body), 'utf8'))
  await fsp.chmod(file, 0o600).catch(() => {})
}

/** 状态目录 → 内存状态；格式不认/目录名与 turnId 不符时抛错（由调用方决定是否清理）。 */
async function parseTurnState (dir) {
  const raw = JSON.parse(await fsp.readFile(path.join(dir, 'state.json'), 'utf8'))
  if (!raw || raw.version !== 1 || typeof raw.turnId !== 'string' || !raw.turnId
      || !Array.isArray(raw.review) || !Array.isArray(raw.repos)) {
    throw new Error('轮状态格式不认')
  }
  if (turnDirName(raw.turnId) !== path.basename(dir)) throw new Error('轮状态目录与 turnId 不符')
  const repos = raw.repos.map(r => ({
    root: String(r.root || ''),
    gitDir: String(r.gitDir || ''),
    repoObjects: String(r.repoObjects || ''),
    rootPaths: Array.isArray(r.rootPaths) ? r.rootPaths : [],
    tree: r.tree || null,
    afterTree: r.afterTree || null,
    scratchDir: dir,
    env: {
      GIT_OBJECT_DIRECTORY: path.join(dir, 'objects'),
      GIT_ALTERNATE_OBJECT_DIRECTORIES: String(r.repoObjects || '')
    }
  }))
  const review = new Map()
  for (const row of raw.review) {
    if (Array.isArray(row) && row.length === 2 && typeof row[0] === 'string'
        && row[1] && typeof row[1] === 'object') {
      review.set(row[0], row[1])
    }
  }
  return {
    turnId: raw.turnId,
    scratch: dir,
    roots: Array.isArray(raw.roots) ? raw.roots : [],
    drift: new Set(Array.isArray(raw.drift) ? raw.drift : []),
    // 上一轮写后副本：非 git 源文件夹没有 afterTree，用它兜底做用户改动对账
    postCaptures: new Map(Array.isArray(raw.postCaptures)
      ? raw.postCaptures.filter(r => Array.isArray(r) && r.length === 2) : []),
    repos,
    captures: new Map(),
    review,
    createdAt: Number(raw.createdAt) || 0,
    done: raw.done === true,
    bytes: Number(raw.bytes) || 0
  }
}

/** 启动时把落盘的轮状态读回内存；坏条目清掉，缺 state.json 的目录留一个宽限期。 */
async function loadTurnStates () {
  await fsp.mkdir(TURN_STORE, { recursive: true, mode: 0o700 }).catch(() => {})
  await fsp.chmod(TURN_STORE, 0o700).catch(() => {})
  let names = []
  try { names = await fsp.readdir(TURN_STORE) } catch (e) {
    log('读取轮状态目录失败：', e.message)
    return
  }
  const gitExe = await resolveGitExecutable()
  let restored = 0
  for (const name of names) {
    const dir = path.join(TURN_STORE, name)
    try {
      const st = await parseTurnState(dir)
      st.repos.forEach(r => { r.gitExe = gitExe })
      turnStates.set(st.turnId, st)
      restored++
    } catch (e) {
      if (e.code === 'ENOENT') {
        const mtime = await fsp.stat(dir).then(s => s.mtimeMs).catch(() => 0)
        if (mtime && Date.now() - mtime > TURN_ORPHAN_GRACE_MS) {
          await fsp.rm(dir, { recursive: true, force: true }).catch(() => {})
        }
      } else {
        await fsp.rm(dir, { recursive: true, force: true }).catch(() => {})
        log('轮状态不可用，已清理：', name, e.message)
      }
    }
  }
  // 内存 Map 靠插入顺序淘汰最旧的轮：恢复时按 createdAt 排一遍
  const sorted = [...turnStates.entries()].sort((a, b) => a[1].createdAt - b[1].createdAt)
  turnStates.clear()
  for (const [id, st] of sorted) turnStates.set(id, st)
  if (restored) log(`已恢复 ${restored} 轮文件改动快照（${TURN_STORE}）`)
}

/** 按 turnId 从磁盘重读一份状态（另一个执行器进程可能刚收尾，内存里还没有）。 */
async function readTurnState (id) {
  try {
    const st = await parseTurnState(turnStatePath(id))
    const gitExe = await resolveGitExecutable()
    st.repos.forEach(r => { r.gitExe = gitExe })
    return st
  } catch {
    return null
  }
}

/** 规范化路径拼写：git 回报的是真实路径（macOS 的 /var 会展开成 /private/var），
 * 服务端给的源文件夹路径没展开过——两边不统一，改动就全被「不在工作区内」误滤掉。
 * 文件还不存在时规范其所在目录再拼回文件名。
 */
async function canonicalPath (abs) {
  try {
    return await fsp.realpath(abs)
  } catch { /* 不存在，走父目录 */ }
  try {
    return path.join(await fsp.realpath(path.dirname(abs)), path.basename(abs))
  } catch {
    return abs
  }
}

function dropTurn (id) {
  const st = turnStates.get(id)
  turnStates.delete(id)
  if (st) fsp.rm(st.scratch, { recursive: true, force: true }).catch(() => {})
}

/** 清掉过期与超额的轮状态（TTL / 轮数 / 总容量，按最旧淘汰），落盘目录一并删。
 *  轮数与容量只淘汰已收尾的轮：跨会话可能有正在跑的轮，不能把它连同快照删掉。 */
async function sweepTurns () {
  const now = Date.now()
  for (const [id, st] of [...turnStates]) {
    if (now - st.createdAt > TURN_TTL_MS) dropTurn(id)
  }
  for (const [id, r] of [...callResults]) {
    if (now - r.at > 10 * 60_000) callResults.delete(id)
  }
  for (const st of turnStates.values()) {
    if (!st.bytes) st.bytes = await dirBytes(st.scratch)
  }
  const dropOldestDone = () => {
    for (const [id, st] of turnStates) {
      if (!st.done) continue
      dropTurn(id)
      return true
    }
    return false
  }
  while (turnStates.size > TURN_MAX_STATES) {
    if (!dropOldestDone()) break
  }
  const totalBytes = () => {
    let sum = 0
    for (const st of turnStates.values()) sum += st.bytes || 0
    return sum
  }
  while (totalBytes() > TURN_CACHE_MAX_BYTES && turnStates.size > 1) {
    if (!dropOldestDone()) break
  }
}

function gitEnv (extra) {
  return { GIT_CONFIG_COUNT: '0', GIT_TERMINAL_PROMPT: '0', GIT_OPTIONAL_LOCKS: '0', LC_ALL: 'C', ...extra }
}

/** 跑一条 git 命令到结束；返回 Buffer（cat-file 要读二进制），exitCode 非零是结果不是异常。 */
function gitRun (exe, args, { cwd, env = {}, maxBytes = TURN_OUT_CAP } = {}) {
  return new Promise((resolve, reject) => {
    execFile(exe, args, {
      cwd,
      env: gitEnv(env),
      maxBuffer: maxBytes,
      timeout: TURN_GIT_TIMEOUT_MS,
      windowsHide: true,
      encoding: 'buffer'
    }, (e, stdout, stderr) => {
      if (e && e.killed) return reject(err('E_TIMEOUT', 'git 命令超时：git ' + args.join(' ')))
      if (e && e.code === 'ENOENT') return reject(err('E_NO_GIT', '这台电脑没有可用的 git 命令'))
      resolve({ exitCode: e ? e.code : 0, stdout: stdout || Buffer.alloc(0), stderr: stderr || Buffer.alloc(0) })
    })
  })
}

let gitExePromise = null
/** 解析本机 git 可执行文件；macOS 的 /usr/bin/git 是 Xcode 存根（没装开发者工具会弹安装框），当没有 git。 */
function resolveGitExecutable () {
  if (gitExePromise) return gitExePromise
  gitExePromise = (async () => {
    try {
      const probe = await gitRun('git', ['--version'], {})
      if (probe.exitCode !== 0) return null
    } catch (e) {
      return null
    }
    if (process.platform === 'darwin') {
      const where = await new Promise(resolve => {
        execFile('/usr/bin/which', ['git'], { timeout: 5000 }, (e, out) => resolve(e ? '' : String(out || '').trim()))
      })
      if (where === '/usr/bin/git') {
        const hasTools = await new Promise(resolve => {
          execFile('/usr/bin/xcode-select', ['-p'], { timeout: 5000 }, e => resolve(!e))
        })
        if (!hasTools) return null
      }
    }
    return 'git'
  })()
  return gitExePromise
}

/**
 * 定位一个源文件夹所在的 git 仓库并准备私有 object store。
 * 仓库自己的 objects 以 alternate 只读挂载，快照写的对象全部落进 scratch；
 * 快照只 add 本工作区的源文件夹（仓库比工作区大时也不去读外面的文件）。
 */
async function locateRepo (rootDir, scratch) {
  const gitExe = await resolveGitExecutable()
  if (!gitExe) return null
  let found
  try {
    found = await gitRun(gitExe, ['rev-parse', '--show-toplevel', '--absolute-git-dir', '--git-path', 'objects'], { cwd: rootDir })
  } catch (e) {
    if (e.code === 'E_NO_GIT') return null
    throw e
  }
  if (found.exitCode === 128 && /not a git repository/i.test(String(found.stderr))) return null
  if (found.exitCode !== 0) throw err('E_IO', 'git rev-parse 失败：' + String(found.stderr).trim())
  const lines = String(found.stdout).split('\n').filter(s => s !== '').map(l => path.resolve(rootDir, l))
  const [repoRoot, gitDir, repoObjects] = lines
  const objects = path.join(scratch, 'objects')
  await fsp.mkdir(objects, { recursive: true })
  const env = { GIT_OBJECT_DIRECTORY: objects, GIT_ALTERNATE_OBJECT_DIRECTORIES: repoObjects }
  return { root: repoRoot, gitDir, repoObjects, env, gitExe, rootPaths: [], scratchDir: scratch }
}

/**
 * 把源文件夹里的整棵工作树（含未跟踪、不含被 ignore 的）写成快照 tree。
 * 全程只写私有 object store；用户的 index、refs、工作树一概不动。
 */
async function snapshotTree (repo) {
  const idx = await fsp.mkdtemp(path.join(repo.scratchDir, 'idx-'))
  try {
    const index = path.join(idx, 'index')
    // 还没有任何 index（新 git init）就从空白开始
    await fsp.copyFile(path.join(repo.gitDir, 'index'), index).catch(e => { if (e.code !== 'ENOENT') throw e })
    const env = { ...repo.env, GIT_INDEX_FILE: index }
    // 只收本工作区的源文件夹：pathspec 按字面匹配，文件名里的 * ? [ 不会变成通配
    const spec = repo.rootPaths.some(p => p === repo.root) ? ['.'] : [...repo.rootPaths]
    const pathspec = spec.length === 0 ? [] : ['--', ...spec]
    const args = ['add', '--all', '--ignore-errors', ...pathspec]
    if (spec.length === 0 || !spec.some(p => p === repo.root)) env.GIT_LITERAL_PATHSPECS = '1'
    const added = await gitRun(repo.gitExe, args, { cwd: repo.root, env })
    // exit 1 = --ignore-errors 跳过了读不到的文件，index 仍是完整的
    if (added.exitCode !== 1 && added.exitCode !== 0) throw err('E_IO', 'git add 失败：' + String(added.stderr).trim())
    const wt = await gitRun(repo.gitExe, ['write-tree'], { cwd: repo.root, env })
    if (wt.exitCode !== 0) throw err('E_IO', 'git write-tree 失败：' + String(wt.stderr).trim())
    return String(wt.stdout).trim()
  } finally {
    await fsp.rm(idx, { recursive: true, force: true }).catch(() => {})
  }
}

/** 改动前抓一份整文件，按内容哈希落在 scratch/captures（内容寻址：相同内容共享一份）。 */
async function captureFile (abs, dir) {
  let fh
  try {
    fh = await fsp.open(abs, 'r')
  } catch (e) {
    if (e.code === 'ENOENT') return { kind: 'absent', mtimeMs: 0, size: 0 }
    return null   // 打不开（权限等）：记不了就不记，不拦这次动作
  }
  try {
    const st = await fh.stat()
    if (!st.isFile()) return null
    const probe = Buffer.alloc(Math.min(TURN_FILE_CAP + 1, st.size + 1))
    let length = 0
    while (length < probe.length) {
      const { bytesRead } = await fh.read(probe, length, probe.length - length, length)
      if (bytesRead === 0) break
      length += bytesRead
    }
    if (length > TURN_FILE_CAP) return { kind: 'oversized', mtimeMs: st.mtimeMs, size: st.size }
    const bytes = probe.subarray(0, length)
    const file = path.join(dir, createHash('sha1').update(bytes).digest('hex'))
    await fsp.mkdir(dir, { recursive: true })
    await fsp.writeFile(file, bytes, { flag: 'wx' }).catch(e => { if (e.code !== 'EEXIST') throw e })
    return {
      kind: 'file', file, mtimeMs: st.mtimeMs, size: st.size,
      binary: bytes.subarray(0, Math.min(BINARY_PROBE_BYTES, bytes.length)).includes(0)
    }
  } catch {
    return null
  } finally {
    await fh.close().catch(() => {})
  }
}

/** 两次捕获是否已知内容相同：都 absent 相同；都是 file 且内容哈希相同；大小与改动时刻都没变的大文件也算没动。
 *  捕获文件按内容 SHA-1 命名，跨轮目录不同但哈希相同=内容相同（用户改动对账要用到这一条）。 */
function sameCapture (a, b) {
  if (a.kind === 'absent' || b.kind === 'absent') return a.kind === b.kind
  if (a.kind === 'oversized' || b.kind === 'oversized') {
    return a.kind === b.kind && a.mtimeMs === b.mtimeMs && a.size === b.size
  }
  return a.file === b.file || path.basename(a.file) === path.basename(b.file)
}

/**
 * 本轮写过的文件在收尾时的副本（按内容哈希存在 scratch/captures）。
 * 下一轮对账拿它当非 git 源文件夹的基线：用户改了模型上一轮碰过的文件就能被发现。
 * 内容不在 state.json 里，只存这份副本的引用（file 指向本机 scratch）。
 */
async function snapshotPostCaptures (state) {
  if (!state.postCaptures) state.postCaptures = new Map()
  for (const abs of state.captures.keys()) {
    if (state.postCaptures.has(abs)) continue
    const cap = await captureFile(abs, path.join(state.scratch, 'captures'))
    if (cap) state.postCaptures.set(abs, cap)
  }
}

/** 服务端在写类调用上带了 turnId：本轮第一次碰这个路径前抓一份，供轮末比对（内容不出这台电脑）。 */
const turnStarts = new Map()

/** 首次写（或命令）时还没有轮状态：先补拍一份轮前快照，再允许改动。
 *  这样即使服务端的 turnStart 还在异步队列里，这一轮也不会漏掉第一个改动。 */
async function ensureTurnState (turnId, ctx) {
  const id = String(turnId || '').trim()
  if (!id || turnStates.has(id)) return
  if (!turnStarts.has(id)) {
    // 写类调用可能抢在服务端 turnStart 前：请求里也带了上一轮轮号，补拍快照时照样能做用户改动对账
    const p = opTurnStart({ turnId: id, previousTurnId: ctx && ctx.previousTurnId }, ctx)
      .catch(e => log('自动补轮前快照失败（本轮只走改动前捕获兜底）:', e.message))
      .finally(() => turnStarts.delete(id))
    turnStarts.set(id, p)
  }
  await turnStarts.get(id)
}

async function captureBeforeMutation (turnId, absPath, ctx) {
  if (!turnId) return
  await ensureTurnState(turnId, ctx)
  deadlineGuard(ctx)
  const st = turnStateOf(turnId)
  if (!st) return
  const key = await canonicalPath(absPath)
  if (st.captures.has(key)) return
  const cap = await captureFile(key, path.join(st.scratch, 'captures'))
  if (cap) st.captures.set(key, cap)
}

/**
 * 模型读过某个文件之后，解除「用户上次之后改过它」的覆盖禁令。
 * 只认本轮的 read：上一轮读过的内容不能当最新版（用户正是那一轮之后改的）。
 */
async function clearDriftAfterRead (turnId, absPath) {
  const id = String(turnId || '').trim()
  if (!id) return
  const st = turnStateOf(id)
  if (!st || !st.drift || st.drift.size === 0) return
  try {
    st.drift.delete(await canonicalPath(absPath))
  } catch { /* 规范化失败就不解除：宁可多拦一次 */ }
}

/** write_file 整文件覆盖前：用户在上次答复后改过的文件必须先读。 */
async function assertNoUserDrift (turnId, absPath, userInitiated) {
  if (userInitiated) return
  const id = String(turnId || '').trim()
  if (!id) return
  const st = turnStateOf(id)
  if (!st || !st.drift || st.drift.size === 0) return
  const key = await canonicalPath(absPath)
  if (!st.drift.has(key)) return
  // 用户把文件删了：没有「最新内容」可读，write_file 就是重新创建。解除禁令放行，
  // 否则 read 报 E_NOT_FOUND、永远清不掉，模型这一轮再也写不回这个路径。
  const exists = await fsp.stat(key).then(s => s.isFile()).catch(() => false)
  if (!exists) {
    st.drift.delete(key)
    return
  }
  throw err('E_CONFLICT',
    `用户在你上一次回答之后改过「${safeDisplay(displayOf(st, key))}」，直接整文件覆盖会丢掉这些改动；`
    + '请先读取最新内容，再决定怎么改')
}

/** 错误/卡片里展示的本机路径：剥控制字符并截断，避免文件名本身成为一条注入或撑爆一条错误。 */
function safeDisplay (s) {
  return String(s == null ? '' : s)
    .replace(/[\u0000-\u001F\u007F-\u009F]/g, '')
    .slice(0, 240)
}

/** 展示路径：别名/相对路径；两个源文件夹之外的就给文件名。 */
function displayOf (state, abs) {
  for (const r of state.roots) {
    if (abs === r.path) return r.alias
    if (abs.startsWith(r.path + path.sep)) {
      return r.alias + '/' + path.relative(r.path, abs).split(path.sep).join('/')
    }
  }
  return path.basename(abs)
}

function insideRoots (state, abs) {
  return state.roots.some(r => abs === r.path || abs.startsWith(r.path + path.sep))
}

// ---- 行比对（只服务捕获侧；git 侧的差异让 git 自己出 patch） ----

function toLines (text) {
  if (text == null || text === '') return []
  const arr = String(text).split('\n')
  if (arr[arr.length - 1] === '') arr.pop()
  return arr
}

/** Myers 最短编辑脚本；预算内算不出就抛错，调用方降级成整文件替换。 */
function shortestEdit (a, b) {
  // 掐头去尾，把比对范围缩到真正不同的中段
  let start = 0
  while (start < a.length && start < b.length && a[start] === b[start]) start++
  let endA = a.length
  let endB = b.length
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) { endA--; endB-- }
  const ops = []
  for (let i = 0; i < start; i++) ops.push({ t: 'eq', a: i, b: i })
  const midA = a.slice(start, endA)
  const midB = b.slice(start, endB)
  const N = midA.length
  const M = midB.length
  if (N + M > 0) {
    if (N + M > TURN_DIFF_LINE_BUDGET * 2) throw new Error('coarse')
    const deadline = Date.now() + TURN_DIFF_DEADLINE_MS
    const max = N + M
    const off = max + 1
    const V = new Int32Array(2 * max + 3)
    const trace = []
    let foundD = -1
    for (let d = 0; d <= max; d++) {
      if (d > TURN_DIFF_MAX_D || ((d & 31) === 0 && Date.now() > deadline)) throw new Error('coarse')
      trace.push(V.slice())
      for (let k = -d; k <= d; k += 2) {
        let x
        if (k === -d || (k !== d && V[k - 1 + off] < V[k + 1 + off])) x = V[k + 1 + off]
        else x = V[k - 1 + off] + 1
        let y = x - k
        while (x < N && y < M && midA[x] === midB[y]) { x++; y++ }
        V[k + off] = x
        if (x >= N && y >= M) { foundD = d; break }
      }
      if (foundD >= 0) break
    }
    if (foundD < 0) throw new Error('coarse')
    let x = N
    let y = M
    const path = []
    for (let dd = foundD; dd > 0; dd--) {
      const Vd = trace[dd]
      const k = x - y
      const prevK = (k === -dd || (k !== dd && Vd[k - 1 + off] < Vd[k + 1 + off])) ? k + 1 : k - 1
      const prevX = Vd[prevK + off]
      const prevY = prevX - prevK
      while (x > prevX && y > prevY) { path.push({ t: 'eq', a: --x + start, b: --y + start }) }
      if (x === prevX) { y--; path.push({ t: 'add', b: y + start }) } else { x--; path.push({ t: 'del', a: x + start }) }
      x = prevX
      y = prevY
    }
    while (x > 0 && y > 0) { path.push({ t: 'eq', a: --x + start, b: --y + start }) }
    while (x > 0) { path.push({ t: 'del', a: --x + start }) }
    while (y > 0) { path.push({ t: 'add', b: --y + start }) }
    path.reverse()
    ops.push(...path)
  }
  // 掐掉的公共尾巴也要进脚本：buildHunks 的上下文窗口靠它才能铺到改动之后的行
  for (let j = 0; endA + j < a.length; j++) ops.push({ t: 'eq', a: endA + j, b: endB + j })
  return ops
}

/** 编辑脚本 → 统一 hunk（3 行上下文）与增删行数。 */
function buildHunks (ops, a, b) {
  const seq = []
  let oldNo = 0
  let newNo = 0
  for (const op of ops) {
    if (op.t === 'eq') { oldNo++; newNo++; seq.push({ k: ' ', o: oldNo, n: newNo, text: a[op.a] }) }
    else if (op.t === 'del') { oldNo++; seq.push({ k: '-', o: oldNo, n: 0, text: a[op.a] }) }
    else { newNo++; seq.push({ k: '+', o: 0, n: newNo, text: b[op.b] }) }
  }
  const changedAt = []
  seq.forEach((s, i) => { if (s.k !== ' ') changedAt.push(i) })
  if (!changedAt.length) return { hunks: [], added: 0, deleted: 0 }
  const C = 3
  const groups = []
  let gs = changedAt[0]
  let ge = changedAt[0]
  for (let i = 1; i < changedAt.length; i++) {
    if (changedAt[i] - changedAt[i - 1] <= C * 2) ge = changedAt[i]
    else { groups.push([gs, ge]); gs = ge = changedAt[i] }
  }
  groups.push([gs, ge])
  const hunks = groups.map(([s, e]) => {
    const from = Math.max(0, s - C)
    const to = Math.min(seq.length - 1, e + C)
    const lines = []
    let oldStart = 0
    let newStart = 0
    let oldLines = 0
    let newLines = 0
    for (let i = from; i <= to; i++) {
      const s2 = seq[i]
      lines.push(s2.k + s2.text)
      if (s2.o) { oldLines++; if (!oldStart) oldStart = s2.o }
      if (s2.n) { newLines++; if (!newStart) newStart = s2.n }
    }
    return {
      oldStart: oldStart || 1, oldLines, newStart: newStart || 1, newLines, lines
    }
  })
  let added = 0
  let deleted = 0
  for (const s of seq) { if (s.k === '+') added++; else if (s.k === '-') deleted++ }
  return { hunks, added, deleted }
}

/** 两份全文比对；超预算降级成「删光旧行 + 加光新行」的一个 hunk（coarse=true）。 */
function compareCaptured (beforeText, afterText) {
  const a = toLines(beforeText)
  const b = toLines(afterText)
  try {
    const ops = shortestEdit(a, b)
    return { ...buildHunks(ops, a, b), coarse: false }
  } catch {
    return {
      hunks: [{
        oldStart: 1, oldLines: a.length, newStart: 1, newLines: b.length,
        lines: [...a.map(l => '-' + l), ...b.map(l => '+' + l)]
      }],
      added: b.length, deleted: a.length, coarse: true
    }
  }
}

function capturedTextOf (cap) {
  return cap.kind === 'file' ? fs.readFileSync(cap.file, 'utf8') : null
}

// ---- 三个轮级 op ----

/** 用「当前仓库 env + 额外对象库」跑 git：上一轮的 afterTree 对象在它自己的 scratch 里。 */
function alternateObjectsEnv (repo, extraDirs) {
  const alts = [repo.env.GIT_ALTERNATE_OBJECT_DIRECTORIES, ...extraDirs].filter(Boolean)
  return { ...repo.env, GIT_ALTERNATE_OBJECT_DIRECTORIES: alts.join(path.delimiter) }
}

/**
 * 用户改动对账：当前工作树（本轮 turnStart 的快照 tree）vs 上一轮 afterTree。
 * 只覆盖 git 仓库；非 git 源文件夹没有全量快照，宁可报 supported=false 也不假装没改。
 * 命中的绝对路径记进 state.drift，本轮 write_file 覆盖它之前必须先 read。
 */
async function detectDrift (state, previousTurnId) {
  const id = String(previousTurnId || '').trim()
  if (!id) return { supported: false, files: [] }
  let base = turnStateOf(id)
  if (!base) base = await readTurnState(id)
  if (!base || !Array.isArray(base.repos)) return { supported: false, files: [] }
  const baseObjects = path.join(base.scratch, 'objects')
  const byAbs = new Map()
  let matched = false
  for (const repo of state.repos) {
    if (!repo.tree || !repo.gitExe) continue
    const prev = base.repos.find(r => r.root === repo.root)
    if (!prev || !prev.afterTree) continue
    matched = true
    if (prev.afterTree === repo.tree) continue
    try {
      const env = alternateObjectsEnv(repo, [baseObjects])
      const diff = await gitRun(repo.gitExe,
        ['diff-tree', '-r', '-M', '-z', '--numstat', prev.afterTree, repo.tree],
        { cwd: repo.root, env })
      if (diff.exitCode !== 0) throw err('E_IO', 'git diff-tree 失败：' + String(diff.stderr).trim())
      for (const entry of parseNumstat(String(diff.stdout))) {
        const abs = path.resolve(repo.root, entry.path)
        if (!insideRoots(state, abs)) continue
        byAbs.set(abs, {
          display: displayOf(state, abs),
          added: entry.added, deleted: entry.deleted,
          ...(entry.binary ? { binary: true } : {}),
          repo, env, rel: entry.path, before: prev.afterTree, after: repo.tree
        })
      }
    } catch (e) {
      log('turnStart 用户改动对账失败（这个仓库本轮跳过）:', repo.root, e.message)
    }
  }
  // 非 git 源文件夹的兜底：上一轮写过、且当时抓了「写后副本」的文件，现在再抓一份比。
  // 只覆盖模型上一轮碰过的文件；从没碰过、又不是 git 的文件没有基线，无法发现（不假装发现）。
  const baseCaps = base.postCaptures || new Map()
  for (const [abs, beforeCap] of baseCaps) {
    if (byAbs.has(abs) || !insideRoots(state, abs)) continue
    matched = true
    try {
      const after = await captureFile(abs, path.join(state.scratch, 'captures'))
      if (!after || sameCapture(beforeCap, after)) continue
      const display = displayOf(state, abs)
      if (beforeCap.kind === 'oversized' || after.kind === 'oversized') {
        byAbs.set(abs, { display, added: 0, deleted: 0, oversized: true })
        continue
      }
      if ((beforeCap.kind === 'file' && beforeCap.binary) || (after.kind === 'file' && after.binary)) {
        byAbs.set(abs, { display, added: 0, deleted: 0, binary: true })
        continue
      }
      const counts = compareCaptured(capturedTextOf(beforeCap), capturedTextOf(after))
      byAbs.set(abs, { display, added: counts.added, deleted: counts.deleted, captureDiff: counts })
    } catch (e) {
      log('turnStart 非 git 文件对账失败（跳过）:', abs, e.message)
    }
  }
  if (!matched) return { supported: false, files: [] }
  // 必要时自动读回 diff：只取小文件、总行数有预算；内容留在本机，随 turnStart 结果回传少量派生文本
  const diffs = []
  let diffLines = 0
  for (const row of byAbs.values()) {
    if (diffs.length >= 3 || diffLines >= 160 || row.binary || row.oversized) continue
    if (row.captureDiff) {
      const count = row.captureDiff.hunks.reduce((s, h) => s + h.lines.length, 0)
      if (diffLines + count > 200) continue
      diffs.push({ path: row.display, hunks: row.captureDiff.hunks })
      diffLines += count
      continue
    }
    try {
      const patch = await gitRun(row.repo.gitExe,
        ['diff-tree', '-r', '-M', '-p', '--no-color', '--no-ext-diff', '--no-textconv', '--unified=2',
          row.before, row.after, '--', row.rel],
        { cwd: row.repo.root, env: { ...row.env, GIT_LITERAL_PATHSPECS: '1' } })
      if (patch.exitCode !== 0 && patch.exitCode !== 1) continue
      const parsed = parseUnifiedPatch(patch.stdout)
      if (parsed.binary || !parsed.hunks.length) continue
      const count = parsed.hunks.reduce((s, h) => s + h.lines.length, 0)
      if (diffLines + count > 200) continue
      diffs.push({ path: row.display, hunks: parsed.hunks })
      diffLines += count
    } catch { /* 单文件 diff 失败不影响清单 */ }
  }
  const files = [...byAbs.values()].map(r => ({
    path: r.display, added: r.added, deleted: r.deleted,
    ...(r.binary ? { binary: true } : {}), ...(r.oversized ? { oversized: true } : {})
  }))
  state.drift = new Set(byAbs.keys())
  return {
    supported: true, files: files.slice(0, TURN_MAX_FILES), total: files.length,
    ...(diffs.length ? { diffs } : {})
  }
}

async function opTurnStart ({ turnId, previousTurnId }, ctx) {
  const id = String(turnId || '').trim()
  if (!id) throw err('E_INVALID', '缺少 turnId')
  if (turnStates.has(id)) {
    // 写类调用抢跑时已经补拍过：把当时算出的对账结果原样给服务端，别让晚到的 turnStart 丢掉注入口径
    const st = turnStates.get(id)
    return { started: true, ...(st && st.driftResult ? { drift: st.driftResult } : {}) }
  }
  await sweepTurns()
  // 目录落在持久化根下：进程重启后还能按 turnId 找回这一轮的快照
  const scratch = turnStatePath(id)
  await fsp.mkdir(TURN_STORE, { recursive: true, mode: 0o700 }).catch(() => {})
  await fsp.chmod(TURN_STORE, 0o700).catch(() => {})
  await fsp.rm(scratch, { recursive: true, force: true }).catch(() => {})
  await fsp.mkdir(scratch, { recursive: true, mode: 0o700 })
  await fsp.chmod(scratch, 0o700).catch(() => {})
  const state = {
    turnId: id, scratch,
    roots: await Promise.all(ctx.rootRecords.map(async r => ({ ...r, path: await canonicalPath(r.path) }))),
    repos: [], captures: new Map(), postCaptures: new Map(), review: new Map(), drift: new Set(),
    createdAt: Date.now(), done: false
  }
  turnStates.set(id, state)
  // 同一个仓库被多个源文件夹引用时只留一份（rootPaths 合并）
  const byRoot = new Map()
  for (const r of state.roots) {
    try {
      let repo = byRoot.get(r.path)
      if (repo === undefined) {
        repo = await locateRepo(r.path, scratch)
        if (repo) {
          const dup = state.repos.find(x => x.root === repo.root)
          repo = dup || (state.repos.push(repo), state.repos[state.repos.length - 1])
        }
        byRoot.set(r.path, repo)
      }
      if (repo && !repo.rootPaths.includes(r.path)) repo.rootPaths.push(r.path)
    } catch (e) {
      log('turnStart 定位仓库失败（这个源文件夹只走捕获兜底）:', r.alias, e.message)
    }
  }
  for (const repo of state.repos) {
    try {
      repo.tree = await snapshotTree(repo)
    } catch (e) {
      repo.tree = null
      log('turnStart 快照失败（这个仓库本轮不出对比）:', repo.root, e.message)
    }
  }
  log(`turnStart: ${state.repos.length} 个仓库, git=${state.repos.some(r => r.tree) ? '可用' : '不可用'}`)
  // 用户改动对账排在快照之后：current tree 就是本轮开场时的工作树
  const drift = await detectDrift(state, previousTurnId)
  state.driftResult = drift
  if (drift.supported) log(`turnStart 用户改动: 基线=${previousTurnId} 命中 ${drift.total} 个文件`)
  state.bytes = await dirBytes(scratch)
  try {
    await saveTurnState(state)
  } catch (e) {
    log('轮状态落盘失败（重启后将读不到这轮差异）:', e.message)
  }
  return { started: true, repos: state.repos.filter(r => r.tree).length, drift }
}

async function opTurnEnd ({ turnId }) {
  let state = turnStateOf(turnId)
  if (!state) {
    // 轮中途执行器重启过：落盘状态还在就把对比补完（非 git 目录的改动前副本只在内存里，补不回来）
    state = await readTurnState(String(turnId || ''))
    if (state) turnStates.set(state.turnId, state)
  }
  if (!state) return { supported: false, files: [], total: 0, added: 0, deleted: 0 }
  /** 绝对路径 → 列表行 */
  const listed = new Map()
  for (const repo of state.repos) {
    if (!repo.tree) continue
    try {
      const after = await snapshotTree(repo)
      repo.afterTree = after
      if (repo.tree === after) continue
      const diff = await gitRun(repo.gitExe, ['diff-tree', '-r', '-M', '-z', '--numstat', repo.tree, after],
        { cwd: repo.root, env: repo.env })
      if (diff.exitCode !== 0) throw err('E_IO', 'git diff-tree 失败：' + String(diff.stderr).trim())
      const repoIdx = state.repos.indexOf(repo)
      for (const entry of parseNumstat(String(diff.stdout))) {
        const abs = path.resolve(repo.root, entry.path)
        if (!insideRoots(state, abs)) continue
        listed.set(abs, {
          display: displayOf(state, abs),
          added: entry.added, deleted: entry.deleted,
          ...(entry.binary ? { binary: true } : {}),
          src: { kind: 'git', repoIdx, rel: entry.path, before: repo.tree, after }
        })
      }
    } catch (e) {
      log('turnEnd 仓库对比失败（这个仓库本轮不报改动）:', repo.root, e.message)
    }
  }
  // 快照没覆盖到的捕获路径（非 git 目录 / 被 ignore 的文件）：补拍后照，有变才算改动
  for (const [abs, before] of state.captures) {
    if (listed.has(abs)) continue
    const after = await captureFile(abs, path.join(state.scratch, 'captures'))
    if (!after || sameCapture(before, after)) continue
    const display = displayOf(state, abs)
    if (before.kind === 'oversized' || after.kind === 'oversized') {
      listed.set(abs, { display, added: 0, deleted: 0, oversized: true, src: { kind: 'capture', abs } })
      continue
    }
    if ((before.kind === 'file' && before.binary) || (after.kind === 'file' && after.binary)) {
      listed.set(abs, { display, added: 0, deleted: 0, binary: true, src: { kind: 'capture', abs } })
      continue
    }
    const counts = compareCaptured(capturedTextOf(before), capturedTextOf(after))
    listed.set(abs, {
      display, added: counts.added, deleted: counts.deleted,
      src: { kind: 'capture', abs, beforeCap: before, afterCap: after }
    })
  }
  const all = [...listed.values()].sort((a, b) => String(a.display).localeCompare(String(b.display)))
  const kept = all.slice(0, TURN_MAX_FILES)
  log(`turnEnd: ${all.length} 个文件已更改 +${all.reduce((s, r) => s + r.added, 0)} -${all.reduce((s, r) => s + r.deleted, 0)}`)
  // 写后副本：非 git 源文件夹下一轮对账的基线（git 仓库另有 afterTree，重复时对账会跳过）
  await snapshotPostCaptures(state)
  if (all.length === 0) {
    // 没有改动就没有改动卡；但快照仍要落盘——下一轮的用户改动对账要拿它当基线。
    // 原注释「没人点就清掉」只考虑了展示，忽略了对账也要这一棵 afterTree。
    for (const repo of state.repos) {
      if (repo.tree) repo.afterTree = repo.tree
    }
    state.done = true
    state.bytes = await dirBytes(state.scratch)
    try {
      await saveTurnState(state)
    } catch (e) {
      log('轮状态落盘失败（下一轮对账会跳过）:', e.message)
    }
    return { supported: true, files: [], total: 0, added: 0, deleted: 0 }
  }
  for (const row of all) state.review.set(row.display, row.src)
  state.done = true
  state.bytes = await dirBytes(state.scratch)
  try {
    await saveTurnState(state)
  } catch (e) {
    log('轮状态落盘失败（重启后将读不到这轮差异）:', e.message)
  }
  return {
    supported: true,
    files: kept.map(r => ({
      path: r.display, added: r.added, deleted: r.deleted,
      ...(r.binary ? { binary: true } : {}), ...(r.oversized ? { oversized: true } : {})
    })),
    total: all.length,
    added: all.reduce((s, r) => s + r.added, 0),
    deleted: all.reduce((s, r) => s + r.deleted, 0)
  }
}

/** 读快照 tree 里某个路径的 blob：不存在返回 {exists:false}，超帽返回 oversized。 */
async function readBlob (repo, tree, rel) {
  const ls = await gitRun(repo.gitExe, ['ls-tree', '-z', '-l', tree, '--', rel],
    { cwd: repo.root, env: { ...repo.env, GIT_LITERAL_PATHSPECS: '1' } })
  if (ls.exitCode !== 0) throw err('E_IO', 'git ls-tree 失败：' + String(ls.stderr).trim())
  const entry = String(ls.stdout).split('\0')[0] || ''
  const m = /^\d+ (\S+) ([0-9a-f]+) +(\d+)\t/.exec(entry)
  if (!m || m[1] !== 'blob') return { exists: false }
  if (Number(m[3]) > TURN_FILE_CAP) return { exists: true, oversized: true }
  const cat = await gitRun(repo.gitExe, ['cat-file', 'blob', m[2]], { cwd: repo.root, env: repo.env })
  if (cat.exitCode !== 0) throw err('E_IO', 'git cat-file 失败：' + String(cat.stderr).trim())
  const bytes = cat.stdout
  return {
    exists: true,
    binary: bytes.subarray(0, Math.min(BINARY_PROBE_BYTES, bytes.length)).includes(0),
    text: bytes.toString('utf8')
  }
}

/** git 自己出的 unified patch 解成 hunk（--unified=3），binary 与空侧一并识别。 */
function parseUnifiedPatch (out) {
  const lines = String(out).split('\n')
  const hunks = []
  let before = true
  let after = true
  let cur = null
  for (const line of lines) {
    if (line.startsWith('@@')) {
      if (cur) hunks.push(cur)
      const m = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(line)
      if (!m) { cur = null; continue }
      cur = {
        oldStart: Number(m[1]), oldLines: m[2] === undefined ? 1 : Number(m[2]),
        newStart: Number(m[3]), newLines: m[4] === undefined ? 1 : Number(m[4]),
        lines: []
      }
      continue
    }
    if (cur) {
      if (line.startsWith('+')) cur.lines.push(line)
      else if (line.startsWith('-')) cur.lines.push(line)
      else if (line.startsWith(' ')) cur.lines.push(line)
      // “\ No newline at end of file” 与其他元行跳过
      continue
    }
    if (line.startsWith('--- /dev/null')) before = false
    else if (line.startsWith('+++ /dev/null')) after = false
    else if (line.startsWith('Binary files') || line.startsWith('GIT binary patch')) return { binary: true, hunks: [], before, after }
  }
  if (cur) hunks.push(cur)
  return { binary: false, hunks, before, after }
}

async function opTurnDiff ({ turnId, path: display }) {
  const id = String(turnId || '')
  const key = String(display || '')
  let state = turnStateOf(id)
  let src = state && state.review.get(key)
  if (!src) {
    // 进程重启后内存里没有，或另一个执行器进程刚收尾：从落盘状态补读一次
    const fresh = await readTurnState(id)
    if (fresh) {
      turnStates.set(id, fresh)
      state = fresh
      src = fresh.review.get(key)
    }
  }
  if (!src) throw err('E_NOT_FOUND', '改动详情已过期或不存在')
  if (src.kind === 'git') {
    const repo = state.repos[src.repoIdx]
    if (!repo || !repo.gitExe) throw err('E_NOT_FOUND', '改动详情已过期或不存在')
    const patch = await gitRun(repo.gitExe,
      ['diff-tree', '-r', '-M', '-p', '--no-color', '--no-ext-diff', '--no-textconv', '--unified=3',
        src.before, src.after, '--', src.rel],
      { cwd: repo.root, env: repo.env })
    if (patch.exitCode !== 0 && patch.exitCode !== 1) {
      const detail = String(patch.stderr).trim()
      // 快照对象被清理、仓库已不在：对用户仍报「过期」，不抛 git 噪音
      if (patch.exitCode === 'ENOENT' || /bad object|not a valid object|unable to read|could not parse/i.test(detail)) {
        throw err('E_NOT_FOUND', '改动详情已过期或不存在')
      }
      throw err('E_IO', 'git diff-tree 失败：' + detail)
    }
    const parsed = parseUnifiedPatch(patch.stdout)
    if (parsed.binary) return { kind: 'binary', path: display }
    return {
      kind: 'text', path: display,
      before: parsed.before, after: parsed.after,
      hunks: parsed.hunks
    }
  }
  const readCap = cap => {
    if (cap.kind !== 'file') {
      return cap.kind === 'oversized'
        ? { exists: true, oversized: true }
        : { exists: false }
    }
    try {
      return { exists: true, oversized: false, binary: cap.binary, text: fs.readFileSync(cap.file, 'utf8') }
    } catch {
      throw err('E_NOT_FOUND', '改动详情已过期或不存在')
    }
  }
  const before = readCap(src.beforeCap)
  const after = readCap(src.afterCap)
  if (before.oversized || after.oversized) return { kind: 'oversized', path: display }
  if (before.binary || after.binary) return { kind: 'binary', path: display }
  const r = compareCaptured(before.exists ? before.text : null, after.exists ? after.text : null)
  return {
    kind: 'text', path: display,
    before: before.exists, after: after.exists,
    hunks: r.hunks, ...(r.coarse ? { coarse: true } : {})
  }
}

/** `git diff-tree -r -M -z --numstat` 输出解析：NUL 分隔，改名记录带旧路径。 */
function parseNumstat (output) {
  const queue = output.split('\0')
  if (queue[queue.length - 1] !== '') throw err('E_IO', 'numstat 输出被截断')
  queue.pop()
  const entries = []
  while (queue.length > 0) {
    const record = queue.shift()
    const first = record.indexOf('\t')
    const second = first < 0 ? -1 : record.indexOf('\t', first + 1)
    if (second < 0) throw err('E_IO', 'numstat 记录不完整')
    const added = record.slice(0, first)
    const deleted = record.slice(first + 1, second)
    let rel = record.slice(second + 1)
    let oldRel
    if (rel === '') {
      oldRel = queue.shift()
      rel = queue.shift()
      if (oldRel === undefined || rel === undefined) throw err('E_IO', 'numstat 改名记录不完整')
    }
    const binary = added === '-'
    entries.push({
      path: rel, ...(oldRel === undefined ? {} : { oldRel }),
      added: binary ? 0 : Number(added), deleted: binary ? 0 : Number(deleted), binary
    })
  }
  return entries
}

// ==================== 轮询循环 ====================

/** 在宿主这台机器上弹系统原生的目录选择框，返回选中的绝对路径。
 *  这就是 DSH 的 native 选择器：远程浏览器够不到宿主桌面时，才退回逐层浏览。 */
async function opPickDir() {
  const platform = process.platform
  let cmd
  let args
  if (platform === 'darwin') {
    cmd = 'osascript'
    args = ['-e', 'POSIX path of (choose folder with prompt "选择工作区目录")']
  } else if (platform === 'win32') {
    cmd = 'powershell'
    args = ['-NoProfile', '-Command',
      'Add-Type -AssemblyName System.Windows.Forms; $f = New-Object System.Windows.Forms.FolderBrowserDialog; if ($f.ShowDialog() -eq "OK") { $f.SelectedPath }']
  } else {
    cmd = 'zenity'
    args = ['--file-selection', '--directory', '--title=选择工作区目录']
  }
  const selected = await new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: 300000 }, (e, stdout) => {
      if (e) {
        // osascript 用户取消时退出码 1、stderr 里带 "User canceled"
        if (/cancel/i.test(String(e.message || '')) || e.code === 1) return reject(err('E_CANCELLED', '没有选择目录'))
        return reject(err('E_PICK_UNAVAILABLE', '这台电脑弹不出选择框：' + (e.message || '')))
      }
      resolve(String(stdout || '').trim())
    })
  })
  const picked = selected.replace(/\/+$/, '') || '/'
  if (!picked) throw err('E_CANCELLED', '没有选择目录')
  // 原生选择框本身就是用户授权：不再拿 --root 去卡它（--root 只约束逐层浏览的起点）
  return { path: picked }
}

const HANDLERS = {
  list: opList,
  pickDir: opPickDir,
  find: opFind,
  read: opRead,
  readBytes: opReadBytes,
  stat: opStat,
  tree: opTree,
  openPath: opOpenPath,
  mkdir: opMkdir,
  move: opMove,
  remove: opRemove,
  write: opWrite,
  writeBytes: opWriteBytes,
  writeChunk: opWriteChunk,
  exec: opExec,
  grep: opGrep,
  turnStart: opTurnStart,
  turnEnd: opTurnEnd,
  turnDiff: opTurnDiff
}

async function postResult(id, ok, data, error) {
  const body = ok ? { id, ok: true, data } : { id, ok: false, error }
  // 回传失败重试：服务端在等这条结果，一次网络抖动不能让它白等到超时。
  // 401 不重试——令牌失效由主循环重新授权，继续打只会刷日志。
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(`${SERVER}/api/ai/executor/result`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + TOKEN },
        body: JSON.stringify(body),
        signal: timeoutSignal(10_000)
      })
      if (res.ok) return true
      if (res.status === 401) return false
      log('回传失败:', res.status, await res.text().catch(() => ''))
    } catch (e) {
      log('回传异常:', e.message)
    }
    await sleep(300 * (attempt + 1))
  }
  return false
}

/** 取到调用后立刻 ack：服务端据此确认投递成功；没 ack 的调用 15s 后会重投。 */
async function postAck(id) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await fetch(`${SERVER}/api/ai/executor/ack`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + TOKEN },
        body: JSON.stringify({ id }),
        signal: timeoutSignal(5_000)
      })
      if (res.ok) {
        // 只有服务端确认“等待方还活着、调用未超时”才允许执行。
        // 服务重启后 owner 的等待方租约过期，ack 会返回 accepted=false，执行器据此跳过，
        // 不会在没人等结果的时候把文件改了。
        const body = await res.json().catch(() => null)
        return !!(body && body.accepted === true)
      }
      // 旧服务端没有 /ack（升级过渡期）：按旧协议直接执行。新服务端一定返回 JSON accepted。
      if (res.status === 404 || res.status === 405) {
        if (!legacyServerWarned) log('服务端不支持 ack（旧版本），按旧协议执行；升级服务端后自动启用租约')
        legacyServerWarned = true
        return true
      }
      if (res.status === 401) return false
    } catch { /* 网络抖动，重试一次 */ }
    await sleep(200)
  }
  return false
}

/** 已执行调用的结果缓存：服务端若因网络原因重投同一个 callId，直接回结果，不重复写盘/跑命令。 */
const callResults = new Map()
const MAX_CALL_RESULTS = 500
/** 只有改动型/轮级结果值得缓存幂等回放；read/grep/tree 这些重跑一次没有副作用，也常有几 MB。 */
const CACHEABLE_OPS = new Set(['write', 'writeBytes', 'writeChunk', 'mkdir', 'move', 'remove', 'exec', 'turnStart', 'turnEnd'])

function rememberCall (op, id, ok, data, error) {
  if (!CACHEABLE_OPS.has(op)) return
  // 只缓存小结果：exec 输出最多 1MB，超过 256KB 也不常驻。
  let size = 0
  try { size = JSON.stringify(data == null ? error : data).length } catch { size = 0 }
  if (size > 256 * 1024) {
    // 大结果不缓存原文，但留一个“已执行”标记：重复投递绝不能再跑一次有副作用的命令
    callResults.set(id, {
      ok: false, data: null,
      error: { code: 'E_DUPLICATE', message: '该调用已执行过，结果过大未缓存' },
      at: Date.now(), size: 0
    })
    return
  }
  callResults.set(id, { ok, data, error, at: Date.now(), size })
  let total = 0
  for (const r of callResults.values()) total += r.size || 0
  while (callResults.size > MAX_CALL_RESULTS || total > 32 * 1024 * 1024) {
    const first = callResults.keys().next().value
    const r = callResults.get(first)
    total -= (r && r.size) || 0
    callResults.delete(first)
    if (callResults.size === 0) break
  }
}

function cachedCall (id) {
  return callResults.get(id) || null
}

/** 服务端截止时间：自动补轮快照等步骤过后再查一次，
 *  避免服务端已经报超时、执行器稍后还去写文件。 */
function deadlineGuard (ctx) {
  if (ctx.deadline && Date.now() >= ctx.deadline) {
    throw err('E_TIMEOUT', '服务端等待时限已过，本次调用未执行')
  }
}

// 读操作可以并行（不同工作区互不影响、长命令不再堵住预览/搜索）；
// 写操作仍严格串行——同一轮 turnStart/write/turnEnd 的顺序和快照一致性不能赌。
const READ_OPS = new Set(['list', 'find', 'grep', 'read', 'readBytes', 'stat', 'tree'])
const READ_CONCURRENCY = 4
const MAX_INFLIGHT = 16
let readActive = 0
const readWaiters = []
let writeChain = Promise.resolve()
const inFlightCalls = new Map()

async function withReadSlot (fn) {
  if (readActive < READ_CONCURRENCY) {
    readActive++
  } else {
    // 槽位由完成者直接转交（它不会在 finally 里递减），这里不能再 ++，
    // 否则「刚被唤醒」和「新来的」会同时认为自己拿到了空位，实际并发超限。
    await new Promise(resolve => readWaiters.push(resolve))
  }
  try {
    return await fn()
  } finally {
    const next = readWaiters.shift()
    if (next) next()
    else readActive--
  }
}

function runSerial (fn) {
  const p = writeChain.then(fn, fn)
  writeChain = p.catch(() => {})
  return p
}

async function executeCall (call) {
  const cached = cachedCall(call.id)
  if (cached) {
    await postResult(call.id, cached.ok, cached.data, cached.error)
    return
  }
  const handler = HANDLERS[call.op]
  if (!handler) {
    await postResult(call.id, false, null, { code: 'E_UNSUPPORTED', message: '不支持的调用：' + call.op })
    return
  }
  const args = call.args || {}
  const started = Date.now()
  try {
    const ctx = callContext(args, call.deadline)
    deadlineGuard(ctx)
    const data = await handler(args, ctx)
    log(`${call.op} 完成 ${Date.now() - started}ms`, args.path ? args.path : '')
    rememberCall(call.op, call.id, true, data, null)
    await postResult(call.id, true, data, null)
  } catch (e) {
    const code = e.code === 'ENOENT' ? 'E_NOT_FOUND' : (e.code || 'E_IO')
    log(`${call.op} 失败: ${code} ${e.message}`)
    rememberCall(call.op, call.id, false, null, { code, message: e.message })
    await postResult(call.id, false, null, { code, message: e.message })
  }
}

function dispatchCall (call) {
  if (inFlightCalls.has(call.id)) return inFlightCalls.get(call.id)
  const run = () => executeCall(call)
  const queued = READ_OPS.has(call.op) ? withReadSlot(run) : runSerial(run)
  const tracked = queued.finally(() => inFlightCalls.delete(call.id))
  tracked.catch(() => { /* executeCall 内部已回传错误；这里只防未处理拒绝 */ })
  inFlightCalls.set(call.id, tracked)
  return tracked
}

async function once() {
  // 在途调用有上限：服务端队列是有界的，执行器也不能无限往下吞
  if (inFlightCalls.size >= MAX_INFLIGHT) {
    await Promise.race([...inFlightCalls.values()])
  }
  // 清理只在没有在途调用时做：turnDiff/turnEnd 可能在读同一份轮状态
  if (inFlightCalls.size === 0) await sweepTurns()
  const res = await fetch(`${SERVER}/api/ai/executor/poll?wait=${WAIT}`, {
    headers: {
      Authorization: 'Bearer ' + TOKEN,
      'X-Wenqu-Executor-Version': AGENT_VERSION
    },
    // 服务端长轮询最多 WAIT 秒；多留 10s 余量，避免网络挂起时主循环永远停在这里
    signal: timeoutSignal((WAIT + 10) * 1000)
  })
  if (res.status === 401) throw err('E_AUTH', '设备令牌无效或已被撤销')
  if (res.status === 204) return
  if (!res.ok) throw err('E_IO', `轮询失败 HTTP ${res.status}`)
  const call = await res.json()
  const ackOk = await postAck(call.id)
  if (!ackOk) {
    log('未获执行许可（等待方已失效或调用已超时），跳过:', call.id)
    // 明确告诉服务端“本次没有执行”，让等待方立刻拿到错误，而不是一直挂到超时
    await postResult(call.id, false, null, {
      code: 'E_ACK_FAILED',
      message: '执行器未获得执行许可（等待方已失效或调用已超时）'
    })
    return
  }
  dispatchCall(call)
}

async function main() {
  log(`执行器启动：${SERVER}  设备=${NAME}`)
  log(`浏览起点：${ROOT}`)
  if (AUTO_YES) log('注意：--yes 已开启，写文件与命令不再逐次确认')
  if (PROVIDED_TOKEN) {
    TOKEN = PROVIDED_TOKEN
    log('使用命令行/环境变量里的设备令牌（仅兼容旧用法；推荐去掉，让它自行授权）')
  } else {
    const saved = savedToken()
    if (saved) {
      TOKEN = saved.token
      log(`已读取本机保存的授权（${TOKEN_FILE}）`)
    }
  }
  if (!TOKEN && !(await authorize())) process.exit(2)

  acquireSingleInstanceLock()
  startHeartbeat()

  await loadTurnStates()
  await sweepTurns()

  let backoff = 1000
  for (;;) {
    try {
      await once()
      backoff = 1000
    } catch (e) {
      if (e.code === 'E_AUTH') {
        if (PROVIDED_TOKEN) {
          console.error('设备令牌无效或已被撤销')
          process.exit(3)
        }
        log('本机保存的授权已失效，重新走一次授权流程')
        forgetToken()
        TOKEN = ''
        if (!(await authorize())) process.exit(2)
        continue
      }
      log('连接失败，稍后重试：', e.message)
      await sleep(backoff)
      backoff = Math.min(backoff * 2, 30000)
    }
  }
}

main().catch(e => {
  console.error(e)
  process.exit(1)
})
