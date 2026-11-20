#!/usr/bin/env node
/**
 * SandboxProvider：命令执行域的平台能力。
 *
 * 三种完整实现，不是"有没有就凑合"：
 *   - macOS：sandbox-exec（Seatbelt profile 限制写范围 + 遮罩凭据目录）
 *   - Linux ：bubblewrap（/ 只读、私有 PID 命名空间，roots 可写）
 *   - Windows：受限令牌 + 写能力 SID + Low 完整性（见 win/acl-runner.ps1），
 *     进程树生命周期仍由内部 Job Object 兜住；没有可用 ACL runner 时 **拒跑**（不再裸跑）。
 *
 * 接口：
 *   available()                     能力探测结果 { ok, path, reason }
 *   beforeSpawn(spec, ctx)          返回最终 spawn 规格（POSIX/Windows 在此包裹沙箱）
 *   afterSpawn(child, ctx)          返回 guard（Windows 在此接管 runner 的 stdin）
 *   terminateTree(child, guard)     超时杀整棵树
 *   release(guard)                  正常结束释放约束
 *   classifyFailure({exitCode,stderr})  区分「沙箱正常拒绝」与「沙箱自身故障」
 *
 * 可写根只有一处来源：{@link writableRoots}(ctx)。mac / linux / windows 三套 provider
 * 与文件工具路径围栏（wenqu-agent.mjs 的 resolveInRoot，读的是同一份 ctx.realRoots）
 * 共用它，避免「文件工具认为的边界」和「shell 沙箱认为的边界」各算一套、迟早漂移。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawn as nodeSpawn, spawnSync as nodeSpawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
// 进程树生命周期守护（Job Object）——Windows ACL runner 内部也会用同一套 Job 语义，
// 这个独立脚本保留给「只想兜进程树、不做写收束」的旧路径 / 诊断用。
const WIN_MONITOR = path.join(here, 'win', 'job-monitor.ps1')
// 受限令牌写收束 runner：真正的 Windows 沙箱后端。
const WIN_ACL_RUNNER = path.join(here, 'win', 'acl-runner.ps1')

const UNAVAILABLE_MESSAGE = '当前平台没有可用的命令沙箱（macOS 需要 sandbox-exec，Linux 需要 bwrap，'
  + 'Windows 需要可用的 ACL runner）：工作区内修改档不执行 shell。请改用文件工具；'
  + '确实需要命令时请把会话权限调到完全权限。'

function unavailable (reason) {
  const e = new Error(UNAVAILABLE_MESSAGE + (reason ? ` 原因：${reason}` : ''))
  e.code = 'E_SANDBOX_UNAVAILABLE'
  return e
}

function noopGuard () {
  return { release () {}, terminate () {} }
}

/**
 * 可写根的**唯一真源**：本次调用允许写入的、已规范化的绝对目录。
 * ctx.realRoots 由 callContext() 用 realpath 解析（逐级向上处理软链）后传入；
 * 文件工具围栏（resolveInRoot）读的也是它。这里只做去重与兜底，不再另算边界。
 */
export function writableRoots (ctx) {
  const src = (ctx && Array.isArray(ctx.realRoots) && ctx.realRoots.length)
    ? ctx.realRoots
    : (ctx && Array.isArray(ctx.roots) ? ctx.roots : [])
  const seen = new Set()
  const out = []
  for (const r of src) {
    const s = String(r == null ? '' : r).trim()
    if (!s) continue
    const abs = path.resolve(s)
    if (seen.has(abs)) continue
    seen.add(abs)
    out.push(abs)
  }
  return out
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

// 各 runner 的「拒绝方言」：沙箱正常工作、拦下了越界操作时 stderr 里出现的特征。
const POSIX_DENIAL = {
  'sandbox-exec': [/operation not permitted/i, /deny file-write/i],
  bwrap: [/read-only file system/i, /operation not permitted/i]
}

// 各 runner 的「自身故障方言」：沙箱 runner 在执行命令**之前**就挂了（不是命令被拒）。
const POSIX_RUNNER_FAILURE = {
  'sandbox-exec': [/sandbox-exec:.*(invalid|syntax|profile)/i, / unable to (apply|compile) /i],
  bwrap: [
    /bwrap:.*(creating new namespace|no permissions|setting up uid map|can't mount|failed)/i,
    /^bwrap: (?!read-only)/i
  ]
}

/**
 * 判断一次失败到底是「沙箱拒绝」还是「沙箱/runner 自身故障」还是普通业务失败。
 * 优先级：runner 故障 > 拒绝 > 普通失败。退出码本身不足以定性。
 * @returns {'runner-failure'|'denied'|'failed'}
 */
export function classifyFailure (kind, { exitCode, stderr } = {}) {
  const text = String(stderr || '')
  const failRes = POSIX_RUNNER_FAILURE[kind] || []
  const denyRes = POSIX_DENIAL[kind] || []
  // runner 故障优先：沙箱没起来时不能把「没跑」当成「被拒」。
  if (failRes.some(re => re.test(text))) return 'runner-failure'
  if (denyRes.some(re => re.test(text))) return 'denied'
  // 有非零退出码但没有匹配特征：普通失败；exitCode 缺失且 stderr 空视为启动失败。
  if (typeof exitCode !== 'number') return 'runner-failure'
  return 'failed'
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
      const roots = writableRoots(ctx)
      if (name === 'sandbox-exec') {
        return {
          ...spec,
          cmd: detected,
          args: ['-p', macProfile(roots), spec.cmd, ...spec.args]
        }
      }
      const args = [
        '--die-with-parent', '--ro-bind', '/', '/', '--dev', '/dev',
        // 私有 PID 命名空间：命令看得到自己的后代，看不到（也杀不到）宿主进程。
        '--unshare-pid', '--proc', '/proc'
      ]
      // 只有可写档才给 /tmp；只读档不凭空放开一个可写落点。
      if (ctx.policy === 'workspace-write') args.push('--tmpfs', '/tmp')
      args.push('--chdir', ctx.cwd)
      for (const r of roots) args.push('--bind', r, r)
      for (const p of ['.wenqu', '.ssh', '.aws', '.kube', '.docker']) {
        const abs = path.join(os.homedir(), p)
        if (fs.existsSync(abs)) args.push('--tmpfs', abs)
      }
      args.push(spec.cmd, ...spec.args)
      return { ...spec, cmd: detected, args }
    },
    afterSpawn () { return noopGuard() },
    terminateTree (child, guard, shell) { shell.killTree(child.pid, 'SIGKILL') },
    release () {},
    classifyFailure (info) { return classifyFailure(name, info) },
    writableRoots
  }
}

const b64json = v => Buffer.from(JSON.stringify(v), 'utf8').toString('base64')
const b64str = s => Buffer.from(String(s || ''), 'utf8').toString('base64')

/**
 * Windows：受限令牌 + 写能力 SID + Low 完整性，进程树用 Job Object 兜生命周期。
 *
 * 为什么不再只挂 Job Object：Job 只管「超时杀整棵进程树」，**不限制任何文件写入**，
 * 于是旧实现里「工作区内修改」= 可写全盘。这里改为照 DSH 的做法把命令交给
 * acl-runner.ps1：它给 roots 加写能力 SID 的允许 ACE + Low 完整性标签，用 Low 受限令牌
 * spawn 命令，写到约束外会被 NTFS 直接拒绝；runner 起不来就 fail-closed，绝不裸跑。
 *
 * 诚实边界：Windows 此档仍是 partial（NTFS 硬链接可别名同一文件、读不受限、被其它
 * AppContainer 标记的目录树不可读），与 DSH 的记录一致。
 */
function windowsProvider ({ spawn, spawnSync, runnerPath = WIN_ACL_RUNNER }) {
  let cached = null
  const provider = {
    name: 'windows-acl',
    available () {
      if (cached) return cached
      let r
      try {
        r = spawnSync('powershell.exe', [
          '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', runnerPath, '-SelfTest'
        ], { windowsHide: true, encoding: 'utf8', timeout: 20000 })
      } catch (e) {
        cached = { ok: false, path: null, reason: 'ACL runner 无法启动：' + e.message }
        return cached
      }
      if (!r || r.status !== 0) {
        cached = {
          ok: false,
          path: null,
          reason: '缺少可用的 ACL runner（' + runnerPath + '）或自检失败：'
            + String((r && (r.stderr || r.stdout)) || '').trim().slice(0, 300)
        }
        return cached
      }
      let payload = null
      try {
        const line = String(r.stdout || '').trim().split('\n').filter(Boolean).pop()
        payload = JSON.parse(line)
      } catch { payload = null }
      cached = payload && payload.ok
        ? { ok: true, path: runnerPath, reason: '' }
        : { ok: false, path: null, reason: 'ACL runner 自检未通过：' + String((payload && payload.reason) || '输出无法解析') }
      return cached
    },
    beforeSpawn (spec, ctx) {
      if (ctx.policy === 'danger-full-access') return spec
      const av = provider.available()
      if (!av.ok) throw unavailable(av.reason)
      const roots = writableRoots(ctx)
      return {
        ...spec,
        cmd: 'powershell.exe',
        args: [
          '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', runnerPath,
          '-CwdB64', b64str(ctx.cwd),
          '-RootsB64', b64json(roots),
          '-CommandB64', b64json([spec.cmd, ...spec.args])
        ]
      }
    },
    afterSpawn (child, ctx) {
      if (ctx.policy === 'danger-full-access') return noopGuard()
      // runner 只是受限子进程的父进程；超时由上层对 runner 进程树 taskkill /T /F 兜住，
      // 正常结束 runner 自己会退出，nohup 起的后台孙进程不受影响（与 POSIX 语义一致）。
      return noopGuard()
    },
    terminateTree (child, guard, shell) {
      shell.killTree(child.pid, 'SIGKILL')
    },
    release () {},
    // runner 失败会在 stderr 打 'wenqu-acl-run: <detail>' 且以 127 退出；命令被 NTFS 拒绝
    // 是子进程退出码非零 + 'Access is denied'/'拒绝访问'。两者不可混谈。
    classifyFailure ({ exitCode, stderr } = {}) {
      const text = String(stderr || '')
      if (/wenqu-acl-run:/i.test(text)) return 'runner-failure'
      if (/access is denied|拒绝访问|EACCES|EPERM/i.test(text)) return 'denied'
      if (typeof exitCode !== 'number') return 'runner-failure'
      return 'failed'
    },
    writableRoots
  }
  return provider
}

/**
 * @param {'darwin'|'linux'|'win32'} platformName
 * @param {object} deps 仅供测试注入：accessSync / spawn / spawnSync / runnerPath / monitorPath
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
      runnerPath: deps.runnerPath || WIN_ACL_RUNNER
    })
  }
  throw new Error(`不支持的平台：${platformName}`)
}

export { UNAVAILABLE_MESSAGE, WIN_MONITOR, WIN_ACL_RUNNER }
