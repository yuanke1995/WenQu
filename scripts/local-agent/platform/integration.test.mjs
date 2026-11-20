#!/usr/bin/env node
/**
 * SystemIntegration / GitLocator / BrowserLocator / SshTransport 的契约测试。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { createSystemIntegration } from './system.js'
import { createGitLocator } from './git.js'
import { createBrowserLocator } from './browser.js'
import { createSshTransport } from './ssh.js'

function execFileOf (responses) {
  return (cmd, args, opts, cb) => {
    const r = responses[cmd]
    if (!r) return cb(Object.assign(new Error('missing'), { code: 'ENOENT' }), '', '')
    if (r.error) return cb(r.error, '', r.err || '')
    cb(null, r.out || '', r.err || '')
  }
}

test('expandHome：POSIX 只认 /，Windows 同时认反斜杠与 %USERPROFILE%', () => {
  assert.equal(createSystemIntegration('linux', { homedir: () => '/home/me' }).expandHome('~/work'), '/home/me/work')
  assert.equal(createSystemIntegration('win32', { homedir: () => 'C:\\Users\\me' }).expandHome('~\\work'), 'C:\\Users\\me\\work')
  assert.equal(createSystemIntegration('win32', { homedir: () => 'C:\\Users\\me' }).expandHome('%USERPROFILE%/work'), 'C:\\Users\\me/work')
})

test('Windows 目录选择：CANCEL 结构化返回，不再把盘根当工作区', async () => {
  const sys = createSystemIntegration('win32', {
    execFile: execFileOf({ 'powershell.exe': { out: 'CANCEL\n' } })
  })
  assert.deepEqual(await sys.pickDirectory(), { cancelled: true, path: '' })
})

test('Windows 目录选择：OK:<path> 解析并去掉尾部反斜杠', async () => {
  const sys = createSystemIntegration('win32', {
    execFile: execFileOf({ 'powershell.exe': { out: 'OK:C:\\Users\\me\\work\\\n' } })
  })
  assert.deepEqual(await sys.pickDirectory(), { cancelled: false, path: 'C:\\Users\\me\\work' })
})

test('macOS 目录选择：退出码 1 = 用户取消', async () => {
  const sys = createSystemIntegration('darwin', {
    execFile: execFileOf({ osascript: { error: Object.assign(new Error('User canceled'), { code: 1 }) } })
  })
  assert.deepEqual(await sys.pickDirectory(), { cancelled: true, path: '' })
})

test('Windows openPath 用 explorer 且忽略退出码', async () => {
  const calls = []
  const sys = createSystemIntegration('win32', {
    spawn: (cmd, args, opts) => { calls.push([cmd, args, opts]); return { on () {}, unref () {} } }
  })
  await sys.openPath('C:\\work')
  assert.equal(calls[0][0], 'explorer')
  assert.deepEqual(calls[0][1], ['C:\\work'])
  assert.equal(calls[0][2].windowsHide, true)
})

test('Linux openPath 探测 xdg-open', async () => {
  const calls = []
  const sys = createSystemIntegration('linux', {
    spawnSync: (cmd, args) => ({ status: args[0] === 'xdg-open' ? 0 : 1, stdout: args[0] + '\n' }),
    execFile: (cmd, args, opts, cb) => { calls.push([cmd, args]); cb(null, '', '') }
  })
  await sys.openPath('/work')
  assert.deepEqual(calls[0], ['xdg-open', ['/work']])
})

test('Windows 权限收紧调用 icacls（POSIX 下是 chmod）', () => {
  const calls = []
  const win = createSystemIntegration('win32', {
    env: { USERNAME: 'me' },
    spawnSync: (cmd, args) => { calls.push([cmd, args]); return { status: 0, stdout: '' } },
    fs: { mkdirSync () {} }
  })
  win.secureDirSync('C:\\work')
  assert.equal(calls[0][0], 'icacls')
  assert.deepEqual(calls[0][1].slice(1), ['/inheritance:r', '/grant:r', 'me:(F)'])
})

test('GitLocator：macOS 的 Xcode 存根当没有 git', async () => {
  const execFile = execFileOf({
    git: { out: 'git version 2.39' },
    '/usr/bin/which': { out: '/usr/bin/git\n' },
    '/usr/bin/xcode-select': { error: new Error('no developer tools') }
  })
  assert.equal(await createGitLocator('darwin', { execFile }).locate(), null)
})

test('GitLocator：Windows 返回 where 解析到的绝对路径', async () => {
  const execFile = execFileOf({
    git: { out: 'git version 2.39' },
    'where.exe': { out: 'C:\\Program Files\\Git\\cmd\\git.exe\n' }
  })
  assert.equal(await createGitLocator('win32', { execFile }).locate(), 'C:\\Program Files\\Git\\cmd\\git.exe')
})

test('BrowserLocator：Windows 渠道查 LOCALAPPDATA，启动用 node + npx-cli.js', () => {
  const existing = new Set(['C:\\Users\\me\\AppData\\Local\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Users\\me\\AppData\\Local\\npm\\npx.cmd',
    'C:\\Users\\me\\AppData\\Local\\npm\\node_modules\\npm\\bin\\npx-cli.js'])
  const fs = {
    accessSync (p) { if (!existing.has(p)) throw new Error('missing') },
    mkdirSync () {}, writeFileSync () {}
  }
  const browser = createBrowserLocator('win32', {
    fs,
    env: { LOCALAPPDATA: 'C:\\Users\\me\\AppData\\Local' },
    homedir: () => 'C:\\Users\\me',
    execPath: 'C:\\Program Files\\nodejs\\node.exe',
    spawnSync: () => ({ status: 0, stdout: 'C:\\Users\\me\\AppData\\Local\\npm\\npx.cmd\n' })
  })
  assert.equal(browser.channel(), 'msedge')
  const launch = browser.launcher(5800, true)
  assert.equal(launch.cmd, 'C:\\Program Files\\nodejs\\node.exe')
  assert.equal(launch.args[0], 'C:\\Users\\me\\AppData\\Local\\npm\\node_modules\\npm\\bin\\npx-cli.js')
  assert.ok(launch.args.includes('--browser'))
  assert.equal(launch.options.windowsHide, true)
  assert.equal(launch.options.detached, false)
})

test('SshTransport：POSIX 带 ControlMaster，Windows 省略且不建 control 目录', () => {
  const made = []
  const fs = { mkdirSync: p => made.push(p) }
  const posix = createSshTransport('linux', { fs }).argsFor('wenqu', 'uptime', '/tmp/wq-ssh')
  assert.ok(posix.includes('ControlMaster=auto'))
  assert.ok(posix.some(a => a.startsWith('ControlPath=')))
  assert.deepEqual(posix.slice(-3), ['--', 'wenqu', 'uptime'])
  const win = createSshTransport('win32', { fs }).argsFor('wenqu', 'uptime', 'C:\\tmp\\wq-ssh')
  assert.ok(!win.some(a => a.startsWith('Control')))
  assert.deepEqual(made, ['/tmp/wq-ssh'])
})
