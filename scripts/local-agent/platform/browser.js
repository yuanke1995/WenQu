#!/usr/bin/env node
/**
 * BrowserLocator：浏览器渠道探测 + @playwright/mcp 启动规格。
 *
 * Windows 的关键改造：不能 spawn('npx')——npm 在 Windows 生成的是 npx.cmd，
 * Node 的 spawn 不开 shell 时找不到 .cmd。这里解析 npx.cmd 旁边的 npx-cli.js，
 * 用当前 node 可执行文件直接跑它，彻底避开 shell 与 PATHEXT。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync as nodeSpawnSync } from 'node:child_process'
import { exists, resolveExecutable } from './system.js'

const MCP_PACKAGE = '@playwright/mcp@0.0.84'

function dirs (d) {
  if (d.platform === 'darwin') return ['/Applications']
  if (d.platform === 'linux') return ['/usr/bin', '/usr/local/bin', '/opt/google/chrome']
  const roots = [d.env.PROGRAMFILES, d.env['PROGRAMFILES(X86)'], d.env.LOCALAPPDATA].filter(Boolean)
  return roots
}

export function createBrowserLocator (platformName, deps = {}) {
  const d = {
    platform: platformName,
    fs: deps.fs || fs,
    env: deps.env || process.env,
    homedir: deps.homedir || os.homedir,
    spawnSync: deps.spawnSync || nodeSpawnSync,
    execPath: deps.execPath || process.execPath
  }
  const pathMod = platformName === 'win32' ? path.win32 : path
  const fileExists = p => exists(d.fs, p)

  function channel () {
    if (platformName === 'darwin') {
      if (fileExists('/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge')) return 'msedge'
      if (fileExists('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')) return 'chrome'
      return 'chromium'
    }
    if (platformName === 'linux') {
      if (fileExists('/usr/bin/google-chrome')) return 'chrome'
      if (fileExists('/usr/bin/microsoft-edge')) return 'msedge'
      if (fileExists('/usr/bin/chromium') || fileExists('/usr/bin/chromium-browser')) return 'chromium'
      return 'chromium'
    }
    for (const root of dirs(d)) {
      if (fileExists(pathMod.join(root, 'Microsoft', 'Edge', 'Application', 'msedge.exe'))) return 'msedge'
      if (fileExists(pathMod.join(root, 'Google', 'Chrome', 'Application', 'chrome.exe'))) return 'chrome'
    }
    return 'chromium'
  }

  function npxCli () {
    const r = d.spawnSync('where', ['npx'], { encoding: 'utf8', windowsHide: true })
    const first = String(r.status === 0 ? (r.stdout || '') : '').split('\n').map(s => s.trim()).find(Boolean)
    if (!first) {
      const e = new Error('找不到 npx：请确认 Node.js/npm 已加入 PATH')
      e.code = 'E_BROWSER_UNAVAILABLE'
      throw e
    }
    const cli = pathMod.join(pathMod.dirname(first), 'node_modules', 'npm', 'bin', 'npx-cli.js')
    if (!fileExists(cli)) {
      const e = new Error(`找不到 npx-cli.js（${cli}）：请修复本机 npm 安装`)
      e.code = 'E_BROWSER_UNAVAILABLE'
      throw e
    }
    return cli
  }

  function launcher (port, headless) {
    const base = ['--yes', MCP_PACKAGE, '--host', '127.0.0.1', '--port', String(port),
      '--isolated', '--no-webmcp', '--allowed-hosts', '*']
    if (headless) base.push('--headless')
    const chosen = channel()
    let args
    if (chosen !== 'chromium') {
      args = [...base, '--browser', chosen]
    } else {
      const cfg = pathMod.join(d.homedir, '.wenqu', 'browser-mcp-config.json')
      d.fs.mkdirSync(path.dirname(cfg), { recursive: true })
      d.fs.writeFileSync(cfg, JSON.stringify({ browser: { browserName: 'chromium' } }))
      args = [...base, '--config', cfg]
    }
    const options = { stdio: ['ignore', 'ignore', 'inherit'], detached: platformName !== 'win32' }
    if (platformName === 'win32') {
      options.windowsHide = true
      return { cmd: d.execPath, args: [npxCli(), ...args], options, channel: chosen }
    }
    return { cmd: resolveExecutable(d.spawnSync, ['npx']) || 'npx', args, options, channel: chosen }
  }

  return { name: 'browser-locator', channel, launcher }
}
