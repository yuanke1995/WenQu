#!/usr/bin/env node
/**
 * SystemIntegration：与宿主桌面/文件系统的交互。
 *
 * 覆盖：打开目录、原生目录选择、~ 展开、敏感文件/目录权限、临时目录。
 * 改造重点：
 *   - Windows 的 explorer 成功时也常返回非 0，必须忽略退出码而不是报 E_IO；
 *   - Windows 目录选择"取消"时 PowerShell 正常退出且 stdout 为空，必须结构化返回 cancelled，
 *     否则会把盘根 '/' 当成用户选的工作区；
 *   - chmod 0o600/0o700 在 Windows 无效，改为 icacls 显式收紧到当前用户。
 */
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { execFile as nodeExecFile, spawn as nodeSpawn, spawnSync as nodeSpawnSync } from 'node:child_process'

function run (execFile, cmd, args, timeout = 15000) {
  return new Promise(resolve => {
    try {
      execFile(cmd, args, { timeout, windowsHide: true }, (e, stdout, stderr) => {
        resolve({ ok: !e, code: e && e.code, out: String(stdout || ''), err: String(stderr || ''), message: e ? e.message : '' })
      })
    } catch (e) {
      resolve({ ok: false, out: '', err: '', message: e.message })
    }
  })
}

function exists (fileSystem, p) {
  try { fileSystem.accessSync(p); return true } catch { return false }
}

function resolveExecutable (spawnSync, candidates) {
  const tool = process.platform === 'win32' ? 'where' : 'which'
  for (const name of candidates) {
    try {
      const r = spawnSync(tool, [name], { encoding: 'utf8', windowsHide: true })
      if (r.status === 0 && String(r.stdout || '').trim()) return name
    } catch { /* 继续找 */ }
  }
  return null
}

function common (deps) {
  return {
    fs: deps.fs || fs,
    fsp: deps.fsp || fsp,
    execFile: deps.execFile || nodeExecFile,
    spawn: deps.spawn || nodeSpawn,
    spawnSync: deps.spawnSync || nodeSpawnSync,
    homedir: deps.homedir || os.homedir,
    env: deps.env || process.env
  }
}

function posixSystem (platformName, d) {
  return {
    name: platformName,
    tmpDir: () => os.tmpdir(),
    expandHome (p) {
      return String(p == null ? '' : p).replace(/^~(?=$|[\\/])/, d.homedir())
    },
    async openPath (p) {
      const cmd = platformName === 'darwin' ? 'open' : resolveExecutable(d.spawnSync, ['xdg-open', 'gio'])
      if (!cmd) {
        const e = new Error('这台电脑没有可用的文件管理器打开命令（需要 xdg-open 或 gio）')
        e.code = 'E_IO'
        throw e
      }
      const args = cmd === 'gio' ? ['open', p] : [p]
      const r = await run(d.execFile, cmd, args)
      if (!r.ok) {
        const e = new Error('打不开文件夹：' + (r.message || r.err))
        e.code = 'E_IO'
        throw e
      }
    },
    async pickDirectory () {
      const cmd = platformName === 'darwin' ? 'osascript' : 'zenity'
      const args = platformName === 'darwin'
        ? ['-e', 'POSIX path of (choose folder with prompt "选择工作区目录")']
        : ['--file-selection', '--directory', '--title=选择工作区目录']
      const r = await run(d.execFile, cmd, args, 300000)
      if (!r.ok) {
        if (r.code === 1 || /cancel/i.test(r.message) || /cancel/i.test(r.err)) return { cancelled: true, path: '' }
        const e = new Error('这台电脑弹不出选择框：' + (r.message || r.err))
        e.code = 'E_PICK_UNAVAILABLE'
        throw e
      }
      const picked = r.out.trim().replace(/\/+$/, '')
      return picked ? { cancelled: false, path: picked } : { cancelled: true, path: '' }
    },
    secureDirSync (p) {
      d.fs.mkdirSync(p, { recursive: true, mode: 0o700 })
      try { d.fs.chmodSync(p, 0o700) } catch { /* 已由 mkdir mode 保证 */ }
    },
    secureFileSync (p) {
      try { d.fs.chmodSync(p, 0o600) } catch { /* 新建时已带 mode */ }
    },
    async secureDir (p) {
      await d.fsp.mkdir(p, { recursive: true, mode: 0o700 })
      await d.fsp.chmod(p, 0o700).catch(() => {})
    },
    async secureFile (p) {
      await d.fsp.chmod(p, 0o600).catch(() => {})
    }
  }
}

function windowsSystem (d) {
  const grantCurrentUser = p => {
    const user = d.env.USERNAME || d.env.USER || ''
    if (!user) return { ok: true, skipped: true }
    // /inheritance:r 去掉继承的宽泛 ACL，只给当前用户完全控制。
    const r = d.spawnSync('icacls', [p, '/inheritance:r', '/grant:r', `${user}:(F)`], {
      encoding: 'utf8', windowsHide: true
    })
    return { ok: r.status === 0, status: r.status, output: String(r.stdout || '') + String(r.stderr || '') }
  }
  return {
    name: 'win32',
    tmpDir: () => os.tmpdir(),
    expandHome (p) {
      let out = String(p == null ? '' : p).replace(/^~(?=$|[\\/])/, d.homedir())
      out = out.replace(/%USERPROFILE%/gi, d.homedir())
      return out
    },
    async openPath (p) {
      // explorer.exe 打开成功也常返回退出码 1，不能用退出码判失败；只要能 spawn 就算成功。
      const child = d.spawn('explorer', [p], { windowsHide: true, detached: true, stdio: 'ignore' })
      child.on('error', () => {}) // 进程自身的失败不冒泡成未捕获异常
      child.unref()
    },
    async pickDirectory () {
      const script = 'Add-Type -AssemblyName System.Windows.Forms; '
        + '$f = New-Object System.Windows.Forms.FolderBrowserDialog; '
        + 'if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK -and $f.SelectedPath -ne "") '
        + '{ "OK:" + $f.SelectedPath } else { "CANCEL" }'
      const r = await run(d.execFile, 'powershell.exe', ['-NoProfile', '-Command', script], 300000)
      const out = r.out.trim()
      if (!r.ok || out === 'CANCEL' || !out.startsWith('OK:')) return { cancelled: true, path: '' }
      return { cancelled: false, path: out.slice(3).replace(/[\\/]+$/, '') }
    },
    secureDirSync (p) {
      d.fs.mkdirSync(p, { recursive: true })
      grantCurrentUser(p)
    },
    secureFileSync (p) { grantCurrentUser(p) },
    async secureDir (p) {
      await d.fsp.mkdir(p, { recursive: true })
      grantCurrentUser(p)
    },
    async secureFile (p) { grantCurrentUser(p) }
  }
}

export function createSystemIntegration (platformName, deps = {}) {
  const d = common(deps)
  if (platformName === 'win32') return windowsSystem(d)
  if (platformName === 'darwin' || platformName === 'linux') return posixSystem(platformName, d)
  throw new Error(`不支持的平台：${platformName}`)
}

export { exists, resolveExecutable }
