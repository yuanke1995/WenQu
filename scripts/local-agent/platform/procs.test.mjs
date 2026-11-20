#!/usr/bin/env node
/**
 * ProcMonitor 契约测试：三平台的进程表、端口、命令行与杀树语义都用 fake 命令验证。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { createProcMonitor } from './procs.js'

function execFileOf (responses) {
  return (cmd, args, opts, cb) => {
    const r = responses[cmd]
    if (!r) return cb(Object.assign(new Error('missing command'), { code: 'ENOENT' }), '', '')
    if (r.error) return cb(r.error, '', '')
    cb(null, r.out || '', r.err || '')
  }
}

const PS_OUT = [
  '  100   1 100 1-02:03:04 /usr/bin/node agent.js',
  '  200 100 100 00:05:10 npm run build',
  '  300 200 200 00:00:03 node build.js'
].join('\n')

test('POSIX：ps 解析 pid/ppid/pgid/etime/args 并建立两个索引', async () => {
  const pm = createProcMonitor('linux', { execFile: execFileOf({ ps: { out: PS_OUT } }) })
  const { byPid, byPgid } = await pm.table()
  assert.equal(byPid.size, 3)
  assert.equal(byPid.get(100).pgid, 100)
  assert.equal(byPid.get(100).elapsedSec, 26 * 3600 + 3 * 60 + 4)
  assert.equal(byPid.get(200).elapsedSec, 310)
  assert.deepEqual([...byPgid.get(100)].map(r => r.pid), [100, 200])
  assert.deepEqual([...byPgid.get(200)].map(r => r.pid), [300])
})

test('POSIX：execFile 同步抛错（spawn EPERM）收敛为 E_IO，不让执行器崩溃', async () => {
  const pm = createProcMonitor('linux', { execFile: () => { throw new Error('spawn EPERM') } })
  await assert.rejects(() => pm.table(), e => e.code === 'E_IO')
})

test('POSIX：lsof 解析监听端口', async () => {
  const out = 'p100\nn*:8090\np200\nn*:5173\n'
  const pm = createProcMonitor('darwin', { execFile: execFileOf({ lsof: { out } }) })
  const ports = await pm.listeningPorts([100, 200])
  assert.deepEqual([...ports], [[100, ['8090']], [200, ['5173']]])
})

test('POSIX：无 lsof 且存在 /proc 时用 ss 兜底', async () => {
  const pm = createProcMonitor('linux', {
    execFile: execFileOf({ ss: { out: 'LISTEN 0 128 0.0.0.0:8090 0.0.0.0:* users:(("node",pid=100,fd=18))\n' } }),
    fs: { existsSync: p => p === '/proc' }
  })
  assert.deepEqual([...(await pm.listeningPorts([100]))], [[100, ['8090']]])
})

test('POSIX：commandLineOf 与 kill/killGroup 用 ps 与负 pid', () => {
  const calls = []
  const pm = createProcMonitor('linux', {
    spawnSync: (cmd, args) => { calls.push([cmd, args]); return { stdout: '/usr/bin/node agent.js --server http://x' } },
    kill: (pid, sig) => calls.push(['kill', pid, sig])
  })
  assert.match(pm.commandLineOf(100), /agent\.js/)
  pm.kill(200, 'SIGTERM')
  pm.killGroup(100, 'SIGKILL')
  assert.deepEqual(calls[0], ['ps', ['-p', '100', '-o', 'args=']])
  assert.deepEqual(calls[1], ['kill', 200, 'SIGTERM'])
  assert.deepEqual(calls[2], ['kill', -100, 'SIGKILL'])
})

test('Windows：CIM JSON 转进程表，祖先链折叠出等价于进程组的子树', async () => {
  const json = JSON.stringify([
    { ProcessId: 100, ParentProcessId: 1, CommandLine: 'cmd.exe /c root' },
    { ProcessId: 200, ParentProcessId: 100, CommandLine: 'node child.js' },
    { ProcessId: 300, ParentProcessId: 200, CommandLine: 'node grand.js' },
    { ProcessId: 400, ParentProcessId: 1, CommandLine: 'unrelated.exe' }
  ])
  const pm = createProcMonitor('win32', { execFile: execFileOf({ 'powershell.exe': { out: json } }) })
  const { byPid, byPgid } = await pm.table()
  assert.equal(byPid.get(200).args, 'node child.js')
  assert.deepEqual([...byPgid.get(100)].map(r => r.pid).sort(), [100, 200, 300])
  assert.deepEqual([...byPgid.get(200)].map(r => r.pid).sort(), [200, 300])
  assert.deepEqual([...byPgid.get(400)].map(r => r.pid), [400])
})

test('Windows：netstat 按 pid 取监听端口', async () => {
  const out = [
    '  TCP    0.0.0.0:8090    0.0.0.0:0    LISTENING    100',
    '  TCP    [::1]:5173     [::]:0        LISTENING    200',
    '  TCP    0.0.0.0:80     0.0.0.0:0    ESTABLISHED   100'
  ].join('\n')
  const pm = createProcMonitor('win32', { execFile: execFileOf({ 'netstat.exe': { out } }) })
  assert.deepEqual([...(await pm.listeningPorts([100, 200]))], [[100, ['8090']], [200, ['5173']]])
})

test('Windows：commandLineOf 用 CIM，kill/killGroup 用 taskkill /T', () => {
  const calls = []
  const pm = createProcMonitor('win32', {
    spawnSync: (cmd, args) => {
      calls.push([cmd, args])
      return { status: 0, stdout: 'C:\\app\\agent.mjs --server http://x' }
    }
  })
  assert.match(pm.commandLineOf(100), /agent\.mjs/)
  assert.equal(calls[0][0], 'powershell.exe')
  assert.match(calls[0][1][2], /Win32_Process/)
  pm.kill(200, 'SIGTERM')
  pm.killGroup(100, 'SIGKILL')
  assert.deepEqual(calls[1], ['taskkill', ['/pid', '200', '/T']])
  assert.deepEqual(calls[2], ['taskkill', ['/pid', '100', '/T', '/F']])
})
