#!/usr/bin/env node
/**
 * SandboxProvider 契约测试：三套实现的关键语义都在 fake 依赖下断言，
 * 不依赖开发机上是否真的装了 sandbox-exec / bwrap / PowerShell。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createSandboxProvider, WIN_MONITOR } from './sandbox.js'

const spec = { cmd: 'INTERPRETER', args: ['-c', 'echo hi'], options: { cwd: '/work', detached: true } }
const ctx = {
  policy: 'workspace-write',
  cwd: '/work',
  realRoots: ['/work', '/tmp/project']
}
const allow = () => {}
const deny = () => { throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' }) }

test('macOS：使用探测到的 sandbox-exec 真实路径，profile 只放开 roots 与临时目录', () => {
  const provider = createSandboxProvider('darwin', { accessSync: allow })
  const a = provider.available()
  assert.equal(a.ok, true)
  assert.equal(a.path, '/usr/bin/sandbox-exec')
  const wrapped = provider.beforeSpawn(spec, ctx)
  assert.equal(wrapped.cmd, a.path)
  assert.equal(wrapped.args[0], '-p')
  assert.match(wrapped.args[1], /\(version 1\)/)
  assert.match(wrapped.args[1], /\(subpath "\/work"\)/)
  assert.match(wrapped.args[1], /\(subpath "\/tmp\/project"\)/)
  assert.deepEqual(wrapped.args.slice(2), ['INTERPRETER', '-c', 'echo hi'])
  assert.equal(wrapped.options, spec.options) // options 原样透传
})

test('Linux：探测 /bin/bwrap 就执行 /bin/bwrap，不再写死 /usr/bin/bwrap', () => {
  const accessSync = p => { if (p === '/bin/bwrap') return; throw new Error('missing') }
  const provider = createSandboxProvider('linux', { accessSync })
  assert.equal(provider.available().path, '/bin/bwrap')
  const wrapped = provider.beforeSpawn(spec, ctx)
  assert.equal(wrapped.cmd, '/bin/bwrap')
  assert.ok(wrapped.args.includes('--chdir'))
  assert.ok(wrapped.args.includes('--die-with-parent'))
  assert.ok(wrapped.args.includes('/work'))
  assert.deepEqual(wrapped.args.slice(-3), ['INTERPRETER', '-c', 'echo hi'])
})

test('无可用沙箱：抛 E_SANDBOX_UNAVAILABLE，上层才回工具卡', () => {
  const provider = createSandboxProvider('linux', { accessSync: deny })
  assert.equal(provider.available().ok, false)
  assert.throws(() => provider.beforeSpawn(spec, ctx), e => e.code === 'E_SANDBOX_UNAVAILABLE')
})

test('完全权限档不套沙箱', () => {
  for (const platform of ['darwin', 'linux', 'win32']) {
    const provider = createSandboxProvider(platform, { accessSync: deny })
    assert.equal(provider.beforeSpawn(spec, { ...ctx, policy: 'danger-full-access' }), spec)
  }
})

test('Windows：不包装 spawn 规格，进程起来后用 Job 守护；RELEASE/KILL 走 stdin 指令', () => {
  const calls = []
  const fakeMonitor = { on () {}, stdin: { write: s => calls.push(['write', s]), end: () => calls.push(['end']) } }
  const fakeSpawn = (cmd, args, opts) => {
    calls.push(['spawn', cmd, args, opts])
    return fakeMonitor
  }
  const provider = createSandboxProvider('win32', {
    spawn: fakeSpawn,
    spawnSync: () => ({ status: 0 }),
    monitorPath: 'C:\\wq\\job-monitor.ps1'
  })
  assert.equal(provider.available().name, undefined) // available 只暴露 ok/path/reason
  assert.equal(provider.beforeSpawn(spec, ctx), spec)

  const guard = provider.afterSpawn({ pid: 1234 }, ctx)
  const spawnCall = calls.find(c => c[0] === 'spawn')
  assert.equal(spawnCall[1], 'powershell.exe')
  assert.deepEqual(spawnCall[2].slice(0, 5), ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'C:\\wq\\job-monitor.ps1'])
  assert.deepEqual(spawnCall[2].slice(5), ['-TargetPid', '1234'])
  assert.equal(spawnCall[3].windowsHide, true)

  guard.release()
  assert.deepEqual(calls.filter(c => c[0] === 'write'), [['write', 'RELEASE\n']])

  // guard 是一次性的：RELEASE 与 KILL 互斥，各自用新 guard 验证。
  const guard2 = provider.afterSpawn({ pid: 1235 }, ctx)
  guard2.terminate()
  assert.deepEqual(calls.filter(c => c[0] === 'write').at(-1), ['write', 'KILL\n'])

  const shellCalls = []
  provider.terminateTree({ pid: 1235 }, guard2, { killTree: (...a) => shellCalls.push(a) })
  assert.deepEqual(calls.filter(c => c[0] === 'write').at(-1), ['write', 'KILL\n'])
  assert.equal(shellCalls.length, 0) // Windows 不再走 POSIX 信号
})

test('Windows 缺 PowerShell 时不可用，标准档 fail-loud', () => {
  const provider = createSandboxProvider('win32', {
    spawn: () => ({ on () {}, stdin: { write () {}, end () {} } }),
    spawnSync: () => ({ status: 1 })
  })
  assert.equal(provider.available().ok, false)
  assert.throws(() => provider.beforeSpawn(spec, ctx), e => e.code === 'E_SANDBOX_UNAVAILABLE')
})

test('POSIX 超时仍由 ShellExecutor 按进程组杀树', () => {
  const provider = createSandboxProvider('linux', { accessSync: allow })
  const shellCalls = []
  provider.terminateTree({ pid: 99 }, provider.afterSpawn({}, ctx), { killTree: (...a) => shellCalls.push(a) })
  assert.deepEqual(shellCalls, [[99, 'SIGKILL']])
})

test('Windows Job 守护脚本：不设置 KILL_ON_JOB_CLOSE，提供 TerminateJobObject', () => {
  const ps = fs.readFileSync(WIN_MONITOR, 'utf8')
  const codeLines = ps.split('\n').filter(l => !l.trimStart().startsWith('#')).join('\n')
  assert.match(codeLines, /CreateJobObject/)
  assert.match(codeLines, /AssignProcessToJobObject/)
  assert.match(codeLines, /TerminateJobObject/)
  // LimitFlags 必须保持 0（不设 KILL_ON_JOB_CLOSE），否则正常退出会误杀后台进程。
  assert.doesNotMatch(codeLines, /LimitFlags\s*=\s*(?!0\b)/)
})
