#!/usr/bin/env node
/**
 * 问渠本机执行器：只负责执行，不拿知识库、不拿模型 Key。
 *
 * 用法：
 *   node scripts/local-agent/wenqu-agent.mjs --server http://127.0.0.1:8090/ai --token <设备令牌> --dir ~/proj
 *
 * 可选：
 *   --yes            写文件/跑命令不再逐次确认（自动化用；默认每次确认）
 *   --name <名字>    设备名（仅日志用）
 *   --timeout <秒>   单次轮询挂起时长（默认 25）
 *
 * 约束：
 *   - 每次调用只允许落在 --dir 指定的目录内（可多次给 --dir）；越界一律 E_PATH_OUTSIDE。
 *   - 写文件走临时文件 + rename，避免写一半留下坏文件。
 */
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import readline from 'node:readline'
import { spawn } from 'node:child_process'

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
const TOKEN = arg('token', process.env.WENQU_EXECUTOR_TOKEN || '')
const NAME = arg('name', os.hostname())
const WAIT = Math.max(5, Math.min(30, Number(arg('timeout', '25')) || 25))
const AUTO_YES = flag('yes')
const ROOTS = (mult('dir').length ? mult('dir') : [process.cwd()])
  .map(d => path.resolve(d.replace(/^~(?=$|\/)/, os.homedir())))

if (!TOKEN) {
  console.error('缺少 --token（在问渠「本机执行器」里添加这台电脑后拿到的设备令牌）')
  process.exit(2)
}

const log = (...a) => console.log(`[${new Date().toISOString()}]`, ...a)

// ==================== 路径围栏 ====================

/** 解析并校验：只允许落在 ROOTS 内；越界抛错，绝不放宽。 */
function resolveInRoot(p) {
  const raw = String(p || payloadRoot() || '').trim()
  if (!raw) throw err('E_IO', '缺少路径')
  const abs = path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(curRoot(), raw)
  const allowed = ROOTS.some(root => abs === root || abs.startsWith(root + path.sep))
  if (!allowed) throw err('E_PATH_OUTSIDE', `路径不在授权目录内：${abs}`)
  return abs
}

let currentRoot = null
const payloadRoot = () => currentRoot
const curRoot = () => currentRoot || ROOTS[0]

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
  const dir = resolveInRoot(p)
  const entries = await fsp.readdir(dir, { withFileTypes: true })
  const out = []
  for (const e of entries) {
    const full = path.join(dir, e.name)
    let st = null
    try { st = await fsp.stat(full) } catch { /* 断链忽略 */ }
    out.push({ path: full, name: e.name, isDir: e.isDirectory(), size: st ? st.size : 0, mtimeMs: st ? st.mtimeMs : 0 })
  }
  return { entries: out }
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

const HANDLERS = {
  list: opList,
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
  log(`授权目录：${ROOTS.join(', ')}`)
  if (AUTO_YES) log('注意：--yes 已开启，写文件与命令不再逐次确认')
  let backoff = 1000
  for (;;) {
    try {
      await once()
      backoff = 1000
    } catch (e) {
      if (e.code === 'E_AUTH') {
        console.error('设备令牌无效，请重新登记这台电脑')
        process.exit(3)
      }
      log('连接失败，稍后重试：', e.message)
      await new Promise(r => setTimeout(r, backoff))
      backoff = Math.min(backoff * 2, 30000)
    }
  }
}

main().catch(e => {
  console.error(e)
  process.exit(1)
})
