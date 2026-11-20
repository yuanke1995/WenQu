#!/usr/bin/env node
/**
 * SandboxProvider 契约测试：三套实现的关键语义都在 fake 依赖下断言，
 * 不依赖开发机上是否真的装了 sandbox-exec / bwrap / PowerShell。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {
  createSandboxProvider, writableRoots, classifyFailure, WIN_MONITOR, WIN_ACL_RUNNER
} from './sandbox.js'

const spec = { cmd: 'INTERPRETER', args: ['-c', 'echo hi'], options: { cwd: '/work', detached: true } }
const ctx = {
  policy: 'workspace-write',
  cwd: '/work',
  realRoots: ['/work', '/tmp/project']
}
const allow = () => {}
const deny = () => { throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' }) }
const okRunner = () => ({ status: 0, stdout: '{"ok":true,"reason":""}\n' })
const deadRunner = () => ({ status: 1, stdout: '', stderr: 'missing' })

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

test('Linux：探测 /bin/bwrap 就执行 /bin/bwrap，且带私有 PID 命名空间', () => {
  const accessSync = p => { if (p === '/bin/bwrap') return; throw new Error('missing') }
  const provider = createSandboxProvider('linux', { accessSync })
  assert.equal(provider.available().path, '/bin/bwrap')
  const wrapped = provider.beforeSpawn(spec, ctx)
  assert.equal(wrapped.cmd, '/bin/bwrap')
  assert.ok(wrapped.args.includes('--chdir'))
  assert.ok(wrapped.args.includes('--die-with-parent'))
  // 抄 DSH 的好做法：进程可见性隔离（私有 PID 命名空间）。
  assert.ok(wrapped.args.includes('--unshare-pid'))
  // 可写档才给 /tmp。
  assert.ok(wrapped.args.includes('--tmpfs') && wrapped.args.includes('/tmp'))
  assert.ok(wrapped.args.includes('/work'))
  assert.deepEqual(wrapped.args.slice(-3), ['INTERPRETER', '-c', 'echo hi'])
})

test('Linux 只读档不凭空放开可写 /tmp', () => {
  const provider = createSandboxProvider('linux', { accessSync: allow })
  const wrapped = provider.beforeSpawn({ ...spec, options: {} }, { ...ctx, policy: 'read-only' })
  const tmpIdx = wrapped.args.indexOf('/tmp')
  // read-only 下不应出现 `--tmpfs /tmp`
  assert.ok(!(tmpIdx > 0 && wrapped.args[tmpIdx - 1] === '--tmpfs'))
})

test('可写根单一真源：去重、跳过空项、realRoots 优先', () => {
  assert.deepEqual(writableRoots({ realRoots: ['/a', '/a', '/b'] }), ['/a', '/b'])
  assert.deepEqual(writableRoots({ realRoots: [], roots: ['/x'] }), ['/x'])
  assert.deepEqual(writableRoots({}), [])
  assert.deepEqual(writableRoots(null), [])
})

test('失败分流：沙箱拒绝 vs runner 故障 vs 普通失败', () => {
  assert.equal(classifyFailure('bwrap', { exitCode: 1, stderr: 'bwrap: read-only file system' }), 'denied')
  assert.equal(classifyFailure('bwrap', { exitCode: 1, stderr: "bwrap: Can't mount /: Permission denied" }), 'runner-failure')
  assert.equal(classifyFailure('sandbox-exec', { exitCode: 1, stderr: 'sandbox-exec: invalid profile' }), 'runner-failure')
  assert.equal(classifyFailure('bwrap', { exitCode: 2, stderr: 'some user error' }), 'failed')
  assert.equal(classifyFailure('bwrap', { exitCode: null, stderr: '' }), 'runner-failure')
})

test('无可用沙箱：抛 E_SANDBOX_UNAVAILABLE，上层才回工具卡', () => {
  const provider = createSandboxProvider('linux', { accessSync: deny })
  assert.equal(provider.available().ok, false)
  assert.throws(() => provider.beforeSpawn(spec, ctx), e => e.code === 'E_SANDBOX_UNAVAILABLE')
})

test('完全权限档不套沙箱', () => {
  for (const platform of ['darwin', 'linux', 'win32']) {
    const provider = createSandboxProvider(platform, { accessSync: deny, spawnSync: deadRunner })
    assert.equal(provider.beforeSpawn(spec, { ...ctx, policy: 'danger-full-access' }), spec)
  }
})

test('Windows：ACL runner 自检通过则包裹成受限令牌 runner，命令以 base64 传入', () => {
  const provider = createSandboxProvider('win32', { spawnSync: okRunner, runnerPath: 'C:\\wq\\acl-runner.ps1' })
  assert.equal(provider.available().ok, true)
  const wrapped = provider.beforeSpawn(spec, ctx)
  assert.equal(wrapped.cmd, 'powershell.exe')
  assert.deepEqual(wrapped.args.slice(0, 5), ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'C:\\wq\\acl-runner.ps1'])
  const ci = wrapped.args.indexOf('-CommandB64')
  assert.ok(ci > 0)
  const decoded = JSON.parse(Buffer.from(wrapped.args[ci + 1], 'base64').toString('utf8'))
  assert.deepEqual(decoded, ['INTERPRETER', '-c', 'echo hi'])
  // 超时按 runner 进程树 taskkill
  const calls = []
  provider.terminateTree({ pid: 42 }, {}, { killTree: (...a) => calls.push(a) })
  assert.deepEqual(calls, [[42, 'SIGKILL']])
})

test('Windows：ACL runner 不可用时标准档 fail-loud（绝不裸跑）', () => {
  const provider = createSandboxProvider('win32', { spawnSync: deadRunner, runnerPath: 'C:\\wq\\acl-runner.ps1' })
  assert.equal(provider.available().ok, false)
  assert.throws(() => provider.beforeSpawn(spec, ctx), e => e.code === 'E_SANDBOX_UNAVAILABLE')
})

test('Windows：ACL runner 脚本含受限令牌 + 写能力 SID + 功能性自检', () => {
  const ps = fs.readFileSync(WIN_ACL_RUNNER, 'utf8')
  const code = ps.split('\n').filter(l => !l.trimStart().startsWith('#')).join('\n')
  assert.match(code, /CreateRestrictedToken/)
  assert.match(code, /WRITE_RESTRICTED/)
  assert.match(code, /TokenIntegrityLevel/)
  assert.match(code, /setintegritylevel/)
  assert.match(code, /SelfTest/)
  // 自检必须真的验证「写约束外失败」，否则就是 fail-open。
  assert.match(code, /wroteOutside/)
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
