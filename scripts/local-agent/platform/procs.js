#!/usr/bin/env node
/**
 * ProcMonitor：本机进程的可观测面与终止面。
 *
 * 改造前散落：ps -A、lsof、ss、/proc/<pid>/environ、process.kill(-pid)、spawnSync('ps')。
 * Windows 一个都没有，导致「后台进程」卡整体失效、单实例锁无法接管。
 *
 * 改造后统一为一组接口：
 *   table()                 { byPid, byPgid }        // Windows 用祖先链折叠出等价的"进程组"
 *   taggedPids()            Map<tag, pid[]>          // Linux 读 /proc environ；其余为空
 *   listeningPorts(pids)    Map<pid, port[]>         // lsof/ss 与 netstat 各自实现
 *   commandLineOf(pid)      string                   // 单实例锁接管用
 *   kill(pid, signal)       / killGroup(pgid, signal)
 *
 * Windows 没有 pgid：由 table() 把每个进程挂到它所有祖先的组下，
 * 上层登记的 pgid（启动时的直接子进程 pid）因此天然代表整棵子树。
 */
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import { execFile as nodeExecFile, spawnSync as nodeSpawnSync } from 'node:child_process'

const TAG_VAR = 'WQ_PROC_TAG'

function capture (execFile, cmd, args, timeoutMs = 8000) {
  return new Promise(resolve => {
    try {
      execFile(cmd, args, { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (e, stdout) => {
        resolve({ ok: !e, out: String(stdout || ''), message: e ? e.message : '' })
      })
    } catch (e) {
      // execFile 在 spawn 失败（EPERM/ENOENT）时可能同步抛出，不能让它穿透为未捕获异常。
      resolve({ ok: false, out: '', message: e.message })
    }
  })
}

function parseElapsed (s) {
  let rest = String(s || '').trim()
  let days = 0
  const dash = rest.indexOf('-')
  if (dash > 0) {
    days = Number(rest.slice(0, dash)) || 0
    rest = rest.slice(dash + 1)
  }
  const p = rest.split(':').map(v => Number(v))
  if (!p.length || p.some(v => !Number.isFinite(v))) return 0
  const sec = p.length === 3 ? p[0] * 3600 + p[1] * 60 + p[2]
    : p.length === 2 ? p[0] * 60 + p[1] : p[0]
  return days * 86400 + sec
}

function indexTable (rows) {
  const byPid = new Map()
  const byPgid = new Map()
  for (const row of rows) {
    byPid.set(row.pid, row)
    if (!byPgid.has(row.pgid)) byPgid.set(row.pgid, [])
    byPgid.get(row.pgid).push(row)
  }
  return { byPid, byPgid }
}

function posixMonitor ({ execFile, spawnSync, fileSystem, fileSystemPromise, kill }) {
  async function table () {
    const r = await capture(execFile, 'ps', ['-A', '-o', 'pid=,ppid=,pgid=,etime=,args='])
    if (!r.ok) {
      const e = new Error('读不到本机进程表：' + (r.message || 'ps 执行失败'))
      e.code = 'E_IO'
      throw e
    }
    const rows = []
    for (const line of r.out.split('\n')) {
      const m = /^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(.*)$/.exec(line)
      if (!m) continue
      rows.push({
        pid: Number(m[1]), ppid: Number(m[2]), pgid: Number(m[3]),
        elapsedSec: parseElapsed(m[4]), args: m[5]
      })
    }
    return indexTable(rows)
  }

  async function taggedPids () {
    const out = new Map()
    let names
    try { names = await fileSystemPromise.readdir('/proc') } catch { return out }
    for (const n of names) {
      if (!/^\d+$/.test(n)) continue
      let buf
      try { buf = await fileSystemPromise.readFile(`/proc/${n}/environ`) } catch { continue }
      const m = /(?:^|\0)WQ_PROC_TAG=([^\0]+)/.exec(buf.toString('utf8'))
      if (!m) continue
      const arr = out.get(m[1]) || []
      arr.push(Number(n))
      out.set(m[1], arr)
    }
    return out
  }

  async function listeningPorts (pids) {
    const out = new Map()
    if (!pids.length) return out
    const args = ['-nP', '-Fpn', '-a', '-iTCP', '-sTCP:LISTEN']
    for (const pid of pids) args.push('-p', String(pid))
    const lsof = await capture(execFile, 'lsof', args, 6000)
    if (lsof.ok && lsof.out) {
      let cur = null
      for (const line of lsof.out.split('\n')) {
        if (line.startsWith('p')) cur = Number(line.slice(1))
        else if (line.startsWith('n') && cur) {
          const port = /:(\d+)$/.exec(line.slice(1))
          if (!port) continue
          const arr = out.get(cur) || []
          if (!arr.includes(port[1])) arr.push(port[1])
          out.set(cur, arr)
        }
      }
      return out
    }
    // ss 只在有 /proc 的 Linux 上有意义；mac 已由 lsof 覆盖。
    if (!fileSystem.existsSync('/proc')) return out
    const ss = await capture(execFile, 'ss', ['-ltnp'], 6000)
    if (!ss.ok) return out
    for (const line of ss.out.split('\n')) {
      const idm = /pid=(\d+)/.exec(line)
      const cols = line.trim().split(/\s+/)
      const port = /:(\d+)$/.exec(cols.length >= 4 ? cols[3] : '')
      if (!idm || !port) continue
      const pid = Number(idm[1])
      const arr = out.get(pid) || []
      if (!arr.includes(port[1])) arr.push(port[1])
      out.set(pid, arr)
    }
    return out
  }

  function commandLineOf (pid) {
    try {
      return String(nodeSpawnSyncSafe(spawnSync, 'ps', ['-p', String(pid), '-o', 'args=']).stdout || '')
    } catch { return '' }
  }

  return {
    name: 'posix',
    table,
    taggedPids,
    listeningPorts,
    commandLineOf,
    async emptyTaggedPids () { return new Map() },
    kill (pid, signal) {
      kill(pid, signal)
      return { ok: true, strategy: 'signal' }
    },
    killGroup (pgid, signal) {
      kill(-pgid, signal)
      return { ok: true, strategy: 'process-group' }
    }
  }
}

function nodeSpawnSyncSafe (spawnSync, cmd, args) {
  return spawnSync(cmd, args, { encoding: 'utf8' })
}

function windowsMonitor ({ execFile, spawnSync }) {
  const ps = script => spawnSync('powershell.exe', ['-NoProfile', '-Command', script], {
    encoding: 'utf8', windowsHide: true, maxBuffer: 8 * 1024 * 1024
  })

  async function table () {
    const r = await capture(execFile, 'powershell.exe', [
      '-NoProfile', '-Command',
      'Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,CommandLine | ConvertTo-Json -Compress'
    ])
    if (!r.ok) {
      const e = new Error('读不到本机进程表：' + (r.message || 'PowerShell 执行失败'))
      e.code = 'E_IO'
      throw e
    }
    let parsed
    try { parsed = JSON.parse(r.out) } catch { parsed = null }
    const raw = !parsed ? [] : (Array.isArray(parsed) ? parsed : [parsed])
    const rows = raw
      .filter(x => x && Number.isFinite(Number(x.ProcessId)))
      .map(x => ({
        pid: Number(x.ProcessId),
        ppid: Number(x.ParentProcessId) || 0,
        pgid: 0, // 下方按祖先链重建
        elapsedSec: 0,
        args: String(x.CommandLine || '')
      }))
    const byPid = new Map(rows.map(r => [r.pid, r]))
    const byPgid = new Map()
    // 每个进程都归到它所有祖先的组里：登记里的 pgid = 命令的直接子进程 pid。
    for (const row of rows) {
      const chain = new Set()
      let cur = row
      let hops = 0
      while (cur && hops++ < 128 && !chain.has(cur.pid)) {
        chain.add(cur.pid)
        if (!byPgid.has(cur.pid)) byPgid.set(cur.pid, [])
        byPgid.get(cur.pid).push(row)
        cur = byPid.get(cur.ppid)
      }
    }
    return { byPid, byPgid }
  }

  async function listeningPorts (pids) {
    const out = new Map()
    if (!pids.length) return out
    const r = await capture(execFile, 'netstat.exe', ['-ano', '-p', 'TCP'], 6000)
    if (!r.ok) return out
    for (const line of r.out.split('\n')) {
      const m = /^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)/i.exec(line)
      if (!m) continue
      const pid = Number(m[2])
      if (!pids.includes(pid)) continue
      const arr = out.get(pid) || []
      if (!arr.includes(m[1])) arr.push(m[1])
      out.set(pid, arr)
    }
    return out
  }

  function commandLineOf (pid) {
    try {
      const r = ps(`(Get-CimInstance Win32_Process -Filter 'ProcessId=${Number(pid)}').CommandLine`)
      return r.status === 0 ? String(r.stdout || '').trim() : ''
    } catch { return '' }
  }

  function taskkill (pid, signal) {
    const args = ['/pid', String(pid), '/T']
    if (signal === 'SIGKILL') args.push('/F')
    const r = spawnSync('taskkill', args, { windowsHide: true, encoding: 'utf8' })
    return { ok: r.status === 0, status: r.status, strategy: signal === 'SIGKILL' ? 'taskkill-force' : 'taskkill' }
  }

  return {
    name: 'win32',
    table,
    taggedPids: async () => new Map(),
    listeningPorts,
    commandLineOf,
    kill: (pid, signal) => taskkill(pid, signal),
    killGroup: (pgid, signal) => taskkill(pgid, signal)
  }
}

/**
 * @param {'darwin'|'linux'|'win32'} platformName
 * @param {object} deps 仅供测试注入：execFile / spawnSync / fs / fsp / kill
 */
export function createProcMonitor (platformName, deps = {}) {
  const common = {
    execFile: deps.execFile || nodeExecFile,
    spawnSync: deps.spawnSync || nodeSpawnSync,
    fileSystem: deps.fs || fs,
    fileSystemPromise: deps.fsp || fsp,
    kill: deps.kill || ((pid, signal) => process.kill(pid, signal))
  }
  if (platformName === 'win32') return windowsMonitor(common)
  if (platformName === 'darwin' || platformName === 'linux') return posixMonitor(common)
  throw new Error(`不支持的平台：${platformName}`)
}

export { TAG_VAR }
