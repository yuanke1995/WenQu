#!/usr/bin/env node
/** 跨平台定位本机 Chromium（Playwright 缓存或系统安装），供移动端 QA 脚本使用。 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

function playwrightCache () {
  if (process.platform === 'win32') {
    return path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'ms-playwright')
  }
  if (process.platform === 'darwin') return path.join(os.homedir(), 'Library', 'Caches', 'ms-playwright')
  return path.join(os.homedir(), '.cache', 'ms-playwright')
}

function cacheCandidates () {
  const out = []
  let dirs
  try { dirs = fs.readdirSync(playwrightCache()) } catch { return out }
  const versions = dirs.filter(d => /^chromium-\d+$/.test(d)).sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]))
  for (const v of versions) {
    const base = path.join(playwrightCache(), v)
    if (process.platform === 'win32') out.push(path.join(base, 'chrome-win', 'chrome.exe'), path.join(base, 'chrome-win64', 'chrome.exe'))
    else if (process.platform === 'darwin') {
      for (const d of ['chrome-mac', 'chrome-mac-arm64']) {
        out.push(path.join(base, d, 'Chromium.app', 'Contents', 'MacOS', 'Chromium'))
        out.push(path.join(base, d, 'Google Chrome for Testing.app', 'Contents', 'MacOS', 'Google Chrome for Testing'))
      }
    } else out.push(path.join(base, 'chrome-linux', 'chrome'), path.join(base, 'chrome-linux64', 'chrome'))
  }
  return out
}

function systemCandidates () {
  if (process.platform === 'win32') return []
  return ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome']
}

export function findChromium () {
  return [...cacheCandidates(), ...systemCandidates()].find(p => {
    try { fs.accessSync(p); return true } catch { return false }
  }) || ''
}

export const tmpPath = name => path.join(os.tmpdir(), name)
