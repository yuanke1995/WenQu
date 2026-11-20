#!/usr/bin/env node
/**
 * SandboxProvider：命令执行域的平台能力。
 *
 * 三种完整实现，不是"有没有就凑合"：
 *   - macOS：sandbox-exec（Seatbelt profile 限制写范围 + 遮罩凭据目录）
 *   - Linux ：bubblewrap（/ 只读，roots 可写；探测到的真实路径会被复用）
 *   - Windows：Job Object。命令经 cmd.exe 启动后立刻加入 Job：
 *       · 正常退出 → 释放 Job，不杀残留孙进程（保留 nohup / cmd & 的后台进程语义）
 *       · 超时     → TerminateJobObject 连同整棵树一起终止
 *     监控进程只是一个持有 Job 句柄的守护，不接管 stdio，因此命令输出仍由执行器直接读取。
 *
 * 接口：
 *   available()                     能力探测结果 { ok, path, reason }
 *   beforeSpawn(spec, ctx)          返回最终 spawn 规格（POSIX 在此包裹沙箱）
 *   afterSpawn(child, ctx)          返回 guard（Windows 在此挂 Job）
 *   terminateTree(child, guard)     超时杀整棵树
 *   release(guard)                  正常结束释放约束
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawn as nodeSpawn, spawnSync as nodeSpawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const WIN_MONITOR = path.join(here, 'win', 'job-monitor.ps1')

const UNAVAILABLE_MESSAGE = '当前平台没有可用的命令沙箱（macOS 需要 sandbox-exec，Linux 需要 bwrap）：'
  + '工作区内修改档不执行 shell。请改用文件工具；确实需要命令时请把会话权限调到完全权限。'

function unavailable (reason) {
  const e = new Error(UNAVAILABLE_MESSAGE + (reason ? ` 原因：${reason}` : ''))
  e.code = 'E_SANDBOX_UNAVAILABLE'
  return e
}

function noopGuard () {
  return { release () {}, terminate () {} }
}

function quoteProfilePath (p) {
  return String(p).replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

function sensitiveReadPaths () {
  const home = os.homedir()
  return [
    path.join(home, '.wenqu'), path.join(home, '.ssh'), path.join(home, '.aws'),
    path.join(home, '.kube'), path.join(home, '.docker'), path.join(home, '.gnupg')
  ].filter(p => fs.existsSync(p)).map(p => {
    try { return fs.realpathSync.native(p) } catch { return p }
  })
}

function macProfile (roots) {
  const writable = [...roots, '/private/tmp', '/tmp', '/private/var/folders', '/var/folders', '/dev']
  const lines = ['(version 1)', '(allow default)', '(deny file-write*)', '(allow file-write*']
  for (const p of writable) lines.push(`  (subpath "${quoteProfilePath(p)}")`)
  lines.push(')')
  // SBPL 最后一条匹配规则生效：读掩码必须在所有 allow 之后。
  for (const p of sensitiveReadPaths()) lines.push(`(deny file-read* (subpath "${quoteProfilePath(p)}"))`)
  return lines.join('\n')
}

function detectExecutable (candidates, accessSync) {
  for (const p of candidates) {
    try {
      accessSync(p, fs.constants.X_OK)
      return p // 返回探测到的真实路径，杜绝"探测 A、执行 B"
    } catch { /* 继续找 */ }
  }
  return null
}

function posixProvider (platform, detected) {
  const name = platform === 'darwin' ? 'sandbox-exec' : 'bwrap'
  return {
    name,
    available () {
      return detected
        ? { ok: true, path: detected, reason: '' }
        : { ok: false, path: null, reason: platform === 'darwin' ? '缺少 /usr/bin/sandbox-exec' : '缺少 bwrap' }
    },
    beforeSpawn (spec, ctx) {
      if (ctx.policy === 'danger-full-access') return spec
      if (!detected) throw unavailable()
      if (name === 'sandbox-exec') {
        return {
          ...spec,
          cmd: detected,
          args: ['-p', macProfile(ctx.realRoots || []), spec.cmd, ...spec.args]
        }
      }
      const args = [
        '--die-with-parent', '--ro-bind', '/', '/', '--dev', '/dev', '--proc', '/proc',
        '--tmpfs', '/tmp', '--chdir', ctx.cwd
      ]
      for (const r of ctx.realRoots || []) args.push('--bind', r, r)
      for (const p of ['.wenqu', '.ssh', '.aws', '.kube', '.docker']) {
        const abs = path.join(os.homedir(), p)
        if (fs.existsSync(abs)) args.push('--tmpfs', abs)
      }
      args.push(spec.cmd, ...spec.args)
      return { ...spec, cmd: detected, args }
    },
    afterSpawn () { return noopGuard() },
    terminateTree (child, guard, shell) { shell.killTree(child.pid, 'SIGKILL') },
    release () {}
  }
}

function windowsProvider ({ spawn, spawnSync, monitorPath = WIN_MONITOR }) {
  return {
    name: 'job-object',
    available () {
      const r = spawnSync('where', ['powershell.exe'], { windowsHide: true, encoding: 'utf8' })
      return r.status === 0
        ? { ok: true, path: monitorPath, reason: '' }
        : { ok: false, path: null, reason: '缺少 powershell.exe（Job 守护无法启动）' }
    },
    beforeSpawn (spec, ctx) {
      if (ctx.policy === 'danger-full-access') return spec
      if (!this.available().ok) throw unavailable()
      // Windows 不做 pre-spawn 包装：cmd.exe 由 ShellExecutor 决定，Job 在进程起来后挂。
      return spec
    },
    afterSpawn (child, ctx) {
      if (ctx.policy === 'danger-full-access') return noopGuard()
      // 监控进程只持有 Job 句柄；stdio 仍归执行器，命令输出不经它中转。
      const monitor = spawn('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', monitorPath, '-TargetPid', String(child.pid)
      ], { windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'] })
      // 监控进程起不来不能把执行器带崩；能力缺失由 available()/beforeSpawn() 显式报错。
      monitor.on('error', () => {})
      let done = false
      const send = command => {
        if (done || !monitor.stdin) return
        done = true
        try { monitor.stdin.write(command + '\n'); monitor.stdin.end() } catch { /* 监控已退出 */ }
      }
      return {
        release () { send('RELEASE') },
        terminate () { send('KILL') },
        monitor
      }
    },
    terminateTree (child, guard, shell) {
      if (guard && typeof guard.terminate === 'function') guard.terminate()
      else shell.killTree(child.pid, 'SIGKILL')
    },
    release (guard) { if (guard && typeof guard.release === 'function') guard.release() }
  }
}

/**
 * @param {'darwin'|'linux'|'win32'} platformName
 * @param {object} deps 仅供测试注入：accessSync / spawn / spawnSync
 */
export function createSandboxProvider (platformName, deps = {}) {
  const accessSync = deps.accessSync || fs.accessSync
  if (platformName === 'darwin') {
    return posixProvider('darwin', detectExecutable(['/usr/bin/sandbox-exec'], accessSync))
  }
  if (platformName === 'linux') {
    return posixProvider('linux', detectExecutable(['/usr/bin/bwrap', '/bin/bwrap'], accessSync))
  }
  if (platformName === 'win32') {
    return windowsProvider({
      spawn: deps.spawn || nodeSpawn,
      spawnSync: deps.spawnSync || nodeSpawnSync,
      monitorPath: deps.monitorPath || WIN_MONITOR
    })
  }
  throw new Error(`不支持的平台：${platformName}`)
}

export { UNAVAILABLE_MESSAGE, WIN_MONITOR }
