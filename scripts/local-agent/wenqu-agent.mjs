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

async function opMove({ from, to, replace, userInitiated }) {
  const src = resolveInRoot(from)
  const dst = resolveInRoot(to)
  if (src === dst) return { path: dst }
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

async function opRemove({ path: p, userInitiated }) {
  const target = resolveInRoot(p)
  if (currentRoots.includes(target)) throw err('E_INVALID', '不能删除工作区根目录本身')
  await rejectSymlink(target)
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

async function opWriteChunk({ path: p, offset, contentBase64 }) {
  const file = resolveInRoot(p)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
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

async function opWrite({ path: p, content, userInitiated }, { confirmText } = {}) {
  const file = resolveInRoot(p)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
  const ok = await confirm('写入文件', `${file}\n（${Buffer.byteLength(content || '')} 字节）`, userInitiated)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次写入')
  await writeAtomic(file, Buffer.from(content || '', 'utf8'))
  return { path: file, bytes: Buffer.byteLength(content || '') }
}

async function opWriteBytes({ path: p, contentBase64, userInitiated }) {
  const file = resolveInRoot(p)
  try { await rejectSymlink(file) } catch (e) { if (e.code !== 'ENOENT') throw e }
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
  exec: opExec
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
