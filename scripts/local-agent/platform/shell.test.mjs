#!/usr/bin/env node
/**
 * ShellExecutor 契约测试：用注入的 fake platform / fake child_process 验证三套实现，
 * 不需要真的在 Windows 上跑。测试的是"能力接口"，不是调用点的业务分支。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { createShellExecutor, POSIX_SHELL, WINDOWS_SHELL } from './shell.js'
import { createPlatform } from './index.js'

const cwd = '/work'
const env = { PATH: '/usr/bin' }

test('POSIX（linux / darwin）用 /bin/sh -c，命令作为单个参数传入', () => {
  for (const platform of ['linux', 'darwin']) {
    const shell = createShellExecutor(platform)
    const spec = shell.spawnSpec("printf 'a b' > out.txt && echo $?", { cwd, env })
    assert.equal(spec.cmd, POSIX_SHELL)
    assert.deepEqual(spec.args, ['-c', "printf 'a b' > out.txt && echo $?"])
    assert.deepEqual(spec.options, { cwd, env, detached: true })
    assert.equal(spec.name, undefined) // spec 只有 cmd/args/options，便于沙箱包装
  }
})

test('Windows 用 cmd.exe /d /s /c，windowsHide，命令仍作为单个参数', () => {
  const shell = createShellExecutor('win32')
  const command = 'echo "a:b" && ping -n 1 127.0.0.1'
  const spec = shell.spawnSpec(command, { cwd: 'C:\\work', env })
  assert.equal(spec.cmd, WINDOWS_SHELL)
  assert.deepEqual(spec.args, ['/d', '/s', '/c', command])
  assert.deepEqual(spec.options, { cwd: 'C:\\work', env, detached: true, windowsHide: true })
})

test('spawnSpec 返回冻结结构，调用点不能就地改写平台决策', () => {
  const spec = createShellExecutor('win32').spawnSpec('echo hi', { cwd })
  assert.ok(Object.isFrozen(spec))
  assert.ok(Object.isFrozen(spec.args))
  assert.ok(Object.isFrozen(spec.options))
})

test('POSIX killTree 按负 pid 对整个进程组发信号', () => {
  const calls = []
  const shell = createShellExecutor('linux', { kill: (pid, sig) => calls.push([pid, sig]) })
  const r = shell.killTree(4321, 'SIGKILL')
  assert.deepEqual(calls, [[-4321, 'SIGKILL']])
  assert.equal(r.strategy, 'process-group')
  assert.equal(r.ok, true)
})

test('Windows killTree 用 taskkill /T，SIGKILL 追加 /F', () => {
  const calls = []
  const fakeSpawn = (cmd, args, opts) => {
    calls.push({ cmd, args, opts })
    return { status: 0, stdout: '', stderr: '' }
  }
  const shell = createShellExecutor('win32', { spawnSync: fakeSpawn })
  assert.equal(shell.killTree(77, 'SIGTERM').strategy, 'taskkill-tree')
  assert.equal(shell.killTree(88, 'SIGKILL').strategy, 'taskkill-tree-force')
  assert.deepEqual(calls[0].args, ['/pid', '77', '/T'])
  assert.deepEqual(calls[1].args, ['/pid', '88', '/T', '/F'])
  assert.equal(calls[0].opts.windowsHide, true)
})

test('Windows killTree 失败时返回 ok=false 而不是抛异常（调用点决定怎么处理）', () => {
  const shell = createShellExecutor('win32', {
    spawnSync: () => ({ status: 128, stdout: '', stderr: 'ERROR: No process found' })
  })
  const r = shell.killTree(999, 'SIGKILL')
  assert.equal(r.ok, false)
  assert.equal(r.status, 128)
  assert.match(r.output, /No process found/)
})

test('createPlatform 只构造一次平台能力，未支持平台 fail-loud', () => {
  const p = createPlatform('darwin')
  assert.equal(p.name, 'darwin')
  assert.equal(p.shell.shellPath, POSIX_SHELL)
  assert.ok(Object.isFrozen(p))
  assert.throws(() => createPlatform('freebsd'), /不支持的平台/)
})
