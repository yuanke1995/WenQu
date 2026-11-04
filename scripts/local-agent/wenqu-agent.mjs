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
 *   --yes            写文件/跑命令不再逐次确认（自动化用；默认每次确认）
 *   --name <名字>    设备名（默认主机名）
 *   --timeout <秒>   单次轮询挂起时长（默认 25）
 *
 * 约束：
 *   - --root 只是"浏览起点"（默认家目录；--root / 即整盘）；每次调用只允许落在本次工作区根内，越界一律 E_PATH_OUTSIDE。
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

/** 解析并校验：只允许落在本次工作区 root 内，且 root 必须在授权目录里；越界一律拒绝。
 *  判据从"授权名单"收紧到"本次 root"——同一台机器给多个目录时，A 工作区摸不到 B 目录。 */
function resolveInRoot(p) {
  const raw = String(p || payloadRoot() || '').trim()
  if (!raw) throw err('E_IO', '缺少路径')
  const abs = path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(curRoot(), raw)
  const inStartRoot = abs === ROOT || abs.startsWith(ROOT + path.sep)
  const withinRoot = !currentRoot || abs === currentRoot || abs.startsWith(currentRoot + path.sep)
  if (!inStartRoot || !withinRoot) throw err('E_PATH_OUTSIDE', `路径不在授权目录内：${abs}`)
  return abs
}

let currentRoot = null
const payloadRoot = () => currentRoot
const curRoot = () => currentRoot || ROOT

function err(code, message) {
  const e = new Error(message)
  e.code = code
  return e
}

// ==================== 确认 ====================

let rl = null
async function confirm(action, detail) {
  if (AUTO_YES) return true
  if (!rl) rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  const ans = await new Promise(resolve => rl.question(`\n[问渠] ${action}\n${detail}\n允许吗？(y/N) `, resolve))
  return /^y(es)?$/i.test(String(ans).trim())
}

// ==================== 操作实现 ====================

async function opList({ path: p }) {
  // 没有路径 = 列起点目录本身（网页打开对话框时直接看到家目录里的内容）
  const dir = p ? resolveInRoot(p) : ROOT
  const entries = await fsp.readdir(dir, { withFileTypes: true })
  const out = []
  for (const e of entries) {
    const full = path.join(dir, e.name)
    let st = null
    try { st = await fsp.stat(full) } catch { /* 断链忽略 */ }
    out.push({ path: full, name: e.name, isDir: e.isDirectory(), size: st ? st.size : 0, mtimeMs: st ? st.mtimeMs : 0 })
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
  const buf = await fsp.readFile(file)
  if (buf.includes(0)) return { content: '', encoding: 'base64', binary: true }
  const text = buf.toString('utf8')
  const lines = text.split('\n')
  const start = Math.max(0, Number(offset) || 0)
  const end = limit && Number(limit) > 0 ? start + Number(limit) : lines.length
  return { content: lines.slice(start, end).join('\n'), encoding: 'utf-8', totalLines: lines.length }
}

async function opReadBytes({ path: p }) {
  const file = resolveInRoot(p)
  const buf = await fsp.readFile(file)
  return { contentBase64: buf.toString('base64'), size: buf.length }
}

async function opWrite({ path: p, content }, { confirmText } = {}) {
  const file = resolveInRoot(p)
  const ok = await confirm('写入文件', `${file}\n（${Buffer.byteLength(content || '')} 字节）`)
  if (!ok) throw err('E_DENIED', '用户拒绝了这次写入')
  await writeAtomic(file, Buffer.from(content || '', 'utf8'))
  return { path: file, bytes: Buffer.byteLength(content || '') }
}

async function opWriteBytes({ path: p, contentBase64 }) {
  const file = resolveInRoot(p)
  const buf = Buffer.from(contentBase64 || '', 'base64')
  const ok = await confirm('写入文件', `${file}\n（${buf.length} 字节）`)
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

async function opExec({ command, timeoutSeconds }) {
  const ok = await confirm('执行命令', `cwd: ${curRoot()}\n$ ${command}`)
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
  write: opWrite,
  writeBytes: opWriteBytes,
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
  currentRoot = call.args && call.args.root ? path.resolve(call.args.root) : null
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
    log(`${call.op} 失败: ${e.code || 'E_IO'} ${e.message}`)
    await postResult(call.id, false, null, { code: e.code || 'E_IO', message: e.message })
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
