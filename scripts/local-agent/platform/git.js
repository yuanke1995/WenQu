#!/usr/bin/env node
/**
 * GitLocator：三平台定位可用的 git。
 * macOS 的 /usr/bin/git 是 Xcode 存根（未装开发者工具时弹安装框），必须当"没有 git"。
 * Windows 用 where 解析真实路径；POSIX 继续走 PATH。
 */
import { execFile as nodeExecFile } from 'node:child_process'

function call (execFile, cmd, args, timeout = 5000) {
  return new Promise(resolve => {
    try {
      execFile(cmd, args, { timeout, windowsHide: true }, (e, stdout) => resolve({
        ok: !e, out: String(stdout || '').trim(), code: e && e.code
      }))
    } catch (e) {
      resolve({ ok: false, out: '', code: e.code })
    }
  })
}

export function createGitLocator (platformName, deps = {}) {
  const execFile = deps.execFile || nodeExecFile
  return {
    name: 'git-locator',
    async locate () {
      const probe = await call(execFile, 'git', ['--version'])
      if (!probe.ok) return null
      if (platformName === 'darwin') {
        const where = await call(execFile, '/usr/bin/which', ['git'])
        if (where.ok && where.out === '/usr/bin/git') {
          const tools = await call(execFile, '/usr/bin/xcode-select', ['-p'])
          if (!tools.ok) return null
        }
      }
      if (platformName === 'win32') {
        const where = await call(execFile, 'where.exe', ['git'])
        if (where.ok && where.out) return where.out.split('\n')[0].trim()
      }
      return 'git'
    }
  }
}
