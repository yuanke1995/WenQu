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
 *   --timeout <秒>   单次轮询挂起时长（默认 25）
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
const NAME = arg('name', os.hostname())
const WAIT = Math.max(5, Math.min(30, Number(arg('timeout', '25')) || 25))
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

async function postJson (url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {})
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

/** 本次调用的根目录集合（工作区可以绑定多个源文件夹，首个为主目录）。 */
let currentRoots = []
/** 同一批 roots 的完整记录（alias/path/primary）：每轮改动快照要用别名拼展示路径。 */
let currentRootRecords = []
let primaryRoot = null
let currentPolicy = 'workspace-write'
const curRoot = () => primaryRoot || ROOT

/** 解析并校验：只允许落在本次工作区的某个源文件夹内。
 *  主目录是相对路径与命令执行的基准；`danger-full-access` 档才允许越出这些目录（与 DSH 的完全权限同义）。 */
function resolveInRoot(p) {
  const raw = String(p || '').trim()
  if (!raw) throw err('E_IO', '缺少路径')
  const abs = path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(curRoot(), raw)
  if (currentPolicy === 'danger-full-access') return abs
  if (currentRoots.length === 0) {
    // 没有工作区根（比如「选择目录」的逐层浏览）：只允许落在浏览起点内
    if (abs === ROOT || abs.startsWith(ROOT + path.sep)) return abs
    throw err('E_PATH_OUTSIDE', `路径不在浏览起点内：${abs}`)
  }
  const inside = currentRoots.some(r => abs === r || abs.startsWith(r + path.sep))
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

async function opList({ path: p }) {
  // 没有路径 = 列主目录；没有工作区根时列浏览起点（网页「选择目录」逐层浏览用）
  const dir = p ? resolveInRoot(p) : (currentRoots.length ? curRoot() : ROOT)
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
async function opFind({ path: p, pattern }) {
  const dir = resolveInRoot(p)
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

async function opRead({ path: p, offset, limit }) {
  const file = resolveInRoot(p)
  await rejectSymlink(file)
  const buf = await fsp.readFile(file)
  if (buf.includes(0)) return { content: '', encoding: 'base64', binary: true }
  const text = buf.toString('utf8')
  const lines = text.split('\n')
  const start = Math.max(0, Number(offset) || 0)
  const end = limit && Number(limit) > 0 ? start + Number(limit) : lines.length
  return { content: lines.slice(start, end).join('\n'), encoding: 'utf-8', totalLines: lines.length }
}

async function opReadBytes({ path: p, offset, length }) {
  const file = resolveInRoot(p)
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

async function opStat({ path: p }) {
  const file = resolveInRoot(p)
  const st = await rejectSymlink(file)
  return { path: file, name: path.basename(file), isDir: st.isDirectory(), size: st.size, mtimeMs: st.mtimeMs }
}

/** 递归列出目录（相对路径），跳过隐藏项与依赖目录；条数与深度都设上限。 */
async function opTree({ path: p, maxFiles, maxDepth }) {
  const dir = resolveInRoot(p)
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

async function opMkdir({ path: p, userInitiated }) {
  const dir = resolveInRoot(p)
  const ok = await confirm('新建文件夹', dir, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次新建')
  await fsp.mkdir(dir, { recursive: false })
  return { path: dir }
}

async function opMove({ from, to, replace, userInitiated, turnId }) {
  const src = resolveInRoot(from)
  const dst = resolveInRoot(to)
  if (src === dst) return { path: dst }
  await captureBeforeMutation(turnId, src)
  const ok = await confirm('移动/重命名', `${src}\n→ ${dst}`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次移动')
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

async function opRemove({ path: p, userInitiated, turnId }) {
  const target = resolveInRoot(p)
  if (currentRoots.includes(target)) throw err('E_INVALID', '不能删除工作区根目录本身')
  await rejectSymlink(target)
  await captureBeforeMutation(turnId, target)
  const ok = await confirm('删除', target, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次删除')
  await fsp.rm(target, { recursive: true, force: false })
  return { removed: true, path: target }
}

/** 在宿主那台电脑上用系统文件管理器打开一个目录（Finder / Explorer / 文件管理器）。 */
async function opOpenPath({ path: p }) {
  const target = resolveInRoot(p)
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

async function opWriteChunk({ path: p, offset, contentBase64, turnId }) {
  const file = resolveInRoot(p)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
  await captureBeforeMutation(turnId, file)
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

async function opWrite({ path: p, content, userInitiated, turnId }, { confirmText } = {}) {
  const file = resolveInRoot(p)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
  await captureBeforeMutation(turnId, file)
  const ok = await confirm('写入文件', `${file}\n（${Buffer.byteLength(content || '')} 字节）`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次写入')
  await writeAtomic(file, Buffer.from(content || '', 'utf8'))
  return { path: file, bytes: Buffer.byteLength(content || '') }
}

async function opWriteBytes({ path: p, contentBase64, userInitiated, turnId }) {
  const file = resolveInRoot(p)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
  await captureBeforeMutation(turnId, file)
  const buf = Buffer.from(contentBase64 || '', 'base64')
  const ok = await confirm('写入文件', `${file}\n（${buf.length} 字节）`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次写入')
  await writeAtomic(file, buf)
  return { path: file, bytes: buf.length }
}

async function writeAtomic(file, buf) {
  const dir = path.dirname(file)
  const tmp = path.join(dir, `.wenqu-${Date.now()}-${Math.random().toString(16).slice(2)}.tmp`)
  await fsp.writeFile(tmp, buf)
  await fsp.rename(tmp, file)
}

async function opExec({ command, timeoutSeconds, userInitiated }) {
  const ok = await confirm('执行命令', `cwd: ${curRoot()}\n$ ${command}`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次命令')
  const seconds = Math.max(1, Number(timeoutSeconds) || 120)
  return await new Promise(resolve => {
    const child = spawn('/bin/sh', ['-c', String(command)], { cwd: curRoot(), env: process.env })
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
// 快照对象与捕获副本全部留在这台电脑的临时目录里，内容永不出机器——回传给服务端的只有
// 「相对路径 + 行数」，点开某个文件的差异时才由这里现算 hunk。

/** 每轮快照/捕获的保留时长：之后服务端还能取差异，超时即清（TTL 扫描在轮询循环里做）。 */
const TURN_TTL_MS = 24 * 60 * 60 * 1000
/** 最多同时保留多少轮的状态；超出的最旧一轮先清。 */
const TURN_MAX_STATES = 40
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

/** turnId → 本轮状态。scratch 目录在本机临时目录下（0700），永不回传内容。 */
const turnStates = new Map()

function turnStateOf (id) {
  return turnStates.get(String(id || '')) || null
}

/**
 * 规范化路径拼写：git 回报的是真实路径（macOS 的 /var 会展开成 /private/var），
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

/** 清掉过期与超额的轮状态：服务端崩溃没收尾的那几轮，靠这里兜底删 scratch。 */
function sweepTurns () {
  const now = Date.now()
  for (const [id, st] of turnStates) {
    if (now - st.createdAt > TURN_TTL_MS) {
      turnStates.delete(id)
      fsp.rm(st.scratch, { recursive: true, force: true }).catch(() => {})
    }
  }
  while (turnStates.size > TURN_MAX_STATES) {
    const oldest = turnStates.keys().next().value
    const st = turnStates.get(oldest)
    turnStates.delete(oldest)
    if (st) fsp.rm(st.scratch, { recursive: true, force: true }).catch(() => {})
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
  return { root: repoRoot, gitDir, env, gitExe, rootPaths: [], scratchDir: scratch }
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

/** 两次捕获是否已知内容相同：都 absent 相同；都是 file 且哈希同文件相同；大小与改动时刻都没变的大文件也算没动。 */
function sameCapture (a, b) {
  if (a.kind === 'absent' || b.kind === 'absent') return a.kind === b.kind
  if (a.kind === 'oversized' || b.kind === 'oversized') {
    return a.kind === b.kind && a.mtimeMs === b.mtimeMs && a.size === b.size
  }
  return a.file === b.file
}

/** 服务端在写类调用上带了 turnId：本轮第一次碰这个路径前抓一份，供轮末比对（内容不出这台电脑）。 */
async function captureBeforeMutation (turnId, absPath) {
  const st = turnStateOf(turnId)
  if (!st) return
  const key = await canonicalPath(absPath)
  if (st.captures.has(key)) return
  const cap = await captureFile(key, path.join(st.scratch, 'captures'))
  if (cap) st.captures.set(key, cap)
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

async function opTurnStart ({ turnId }) {
  const id = String(turnId || '').trim()
  if (!id) throw err('E_INVALID', '缺少 turnId')
  if (turnStates.has(id)) return { started: true }
  sweepTurns()
  const scratch = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenqu-turns-'))
  await fsp.chmod(scratch, 0o700).catch(() => {})
  const state = {
    turnId: id, scratch,
    roots: await Promise.all(currentRootRecords.map(async r => ({ ...r, path: await canonicalPath(r.path) }))),
    repos: [], captures: new Map(), review: new Map(), createdAt: Date.now()
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
  return { started: true, repos: state.repos.filter(r => r.tree).length }
}

async function opTurnEnd ({ turnId }) {
  const state = turnStateOf(turnId)
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
  for (const row of all) state.review.set(row.display, row.src)
  const kept = all.slice(0, TURN_MAX_FILES)
  log(`turnEnd: ${all.length} 个文件已更改 +${all.reduce((s, r) => s + r.added, 0)} -${all.reduce((s, r) => s + r.deleted, 0)}`)
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
  const state = turnStateOf(turnId)
  const src = state && state.review.get(String(display || ''))
  if (!src) throw err('E_NOT_FOUND', '改动详情已过期或不存在')
  if (src.kind === 'git') {
    const repo = state.repos[src.repoIdx]
    if (!repo) throw err('E_NOT_FOUND', '改动详情已过期或不存在')
    const patch = await gitRun(repo.gitExe,
      ['diff-tree', '-r', '-M', '-p', '--no-color', '--no-ext-diff', '--no-textconv', '--unified=3',
        src.before, src.after, '--', src.rel],
      { cwd: repo.root, env: repo.env })
    if (patch.exitCode !== 0 && patch.exitCode !== 1) {
      throw err('E_IO', 'git diff-tree 失败：' + String(patch.stderr).trim())
    }
    const parsed = parseUnifiedPatch(patch.stdout)
    if (parsed.binary) return { kind: 'binary', path: display }
    return {
      kind: 'text', path: display,
      before: parsed.before, after: parsed.after,
      hunks: parsed.hunks
    }
  }
  const readCap = cap => cap.kind === 'file'
    ? { exists: true, oversized: false, binary: cap.binary, text: fs.readFileSync(cap.file, 'utf8') }
    : (cap.kind === 'oversized' ? { exists: true, oversized: true } : { exists: false })
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
  turnStart: opTurnStart,
  turnEnd: opTurnEnd,
  turnDiff: opTurnDiff
}

async function postResult(id, ok, data, error) {
  const body = ok ? { id, ok: true, data } : { id, ok: false, error }
  const res = await fetch(`${SERVER}/api/ai/executor/result`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + TOKEN },
    body: JSON.stringify(body)
  })
  if (!res.ok) log('回传失败:', res.status, await res.text().catch(() => ''))
}

async function once() {
  sweepTurns()
  const res = await fetch(`${SERVER}/api/ai/executor/poll?wait=${WAIT}`, {
    headers: { Authorization: 'Bearer ' + TOKEN }
  })
  if (res.status === 401) throw err('E_AUTH', '设备令牌无效或已被撤销')
  if (res.status === 204) return
  if (!res.ok) throw err('E_IO', `轮询失败 HTTP ${res.status}`)
  const call = await res.json()
  const args = call.args || {}
  currentRoots = Array.isArray(args.roots)
    ? args.roots.map(r => path.resolve(String(r.path || ''))).filter(Boolean)
    : (args.root ? [path.resolve(String(args.root))] : [])
  currentRootRecords = Array.isArray(args.roots)
    ? args.roots.filter(r => r && r.path)
        .map(r => ({ alias: String(r.alias || ''), path: path.resolve(String(r.path)), primary: !!r.primary }))
    : []
  primaryRoot = currentRoots[0] || null
  if (Array.isArray(args.roots)) {
    const p = args.roots.find(r => r && r.primary && r.path)
    if (p) primaryRoot = path.resolve(String(p.path))
  }
  currentPolicy = args.policy === 'danger-full-access' ? 'danger-full-access' : 'workspace-write'
  const handler = HANDLERS[call.op]
  if (!handler) {
    await postResult(call.id, false, null, { code: 'E_UNSUPPORTED', message: '不支持的调用：' + call.op })
    return
  }
  const started = Date.now()
  try {
    const data = await handler(call.args || {})
    log(`${call.op} 完成 ${Date.now() - started}ms`, call.args && call.args.path ? call.args.path : '')
    await postResult(call.id, true, data, null)
  } catch (e) {
    const code = e.code === 'ENOENT' ? 'E_NOT_FOUND' : (e.code || 'E_IO')
    log(`${call.op} 失败: ${code} ${e.message}`)
    await postResult(call.id, false, null, { code, message: e.message })
  }
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
