#!/usr/bin/env node
/**
 * ShellExecutor：把「用什么解释器、怎么把命令交给它、怎么杀掉整棵树」收敛为一种能力。
 *
 * 改造前的散落写法：danger-full-access 固定 '/bin/sh'、超时用 process.kill(-pid)、
 * Windows 只在 stopBrowser 里单独调 taskkill。结果是 Windows 上 exec 100% 失败。
 *
 * 改造后：
 *   - POSIX：/bin/sh -c <command>，detached 成进程组，按 -pid 发信号；
 *   - Windows：cmd.exe /d /s /c <command>，windowsHide 避免闪控制台，按 taskkill /T 杀进程树。
 *
 * 命令始终作为**单个参数**传给解释器，绝不拼进 shell 包装字符串——这是安全边界，
 * 也是 heredoc/多行命令保真的前提。
 */
import { spawnSync as nodeSpawnSync } from 'node:child_process'

const POSIX_SHELL = '/bin/sh'
const WINDOWS_SHELL = 'cmd.exe'

function baseSpec (cmd, args, options) {
  return Object.freeze({
    cmd,
    args: Object.freeze([...args]),
    options: Object.freeze({ ...options })
  })
}

function posixExecutor (kill) {
  return {
    name: 'posix',
    shellPath: POSIX_SHELL,
    spawnSpec (command, { cwd, env = process.env, detached = true, stdio } = {}) {
      const options = { cwd, env, detached }
      if (stdio) options.stdio = stdio
      return baseSpec(POSIX_SHELL, ['-c', String(command)], options)
    },
    killTree (pid, signal = 'SIGTERM') {
      // detached 的子进程是组长；负 pid 把整棵树一起带走。
      kill(-pid, signal)
      return { ok: true, strategy: 'process-group' }
    }
  }
}

function windowsExecutor (spawnSync) {
  return {
    name: 'win32',
    shellPath: WINDOWS_SHELL,
    spawnSpec (command, { cwd, env = process.env, detached = true, stdio } = {}) {
      const options = { cwd, env, detached, windowsHide: true }
      if (stdio) options.stdio = stdio
      return baseSpec(WINDOWS_SHELL, ['/d', '/s', '/c', String(command)], options)
    },
    killTree (pid, signal = 'SIGTERM') {
      // Windows 没有 POSIX 进程组信号；taskkill /T 沿父子树递归，/F 用于强制终止。
      const args = ['/pid', String(pid), '/T']
      if (signal === 'SIGKILL') args.push('/F')
      const r = spawnSync('taskkill', args, { windowsHide: true, encoding: 'utf8' })
      return {
        ok: r.status === 0,
        status: r.status,
        strategy: signal === 'SIGKILL' ? 'taskkill-tree-force' : 'taskkill-tree',
        output: String(r.stdout || '') + String(r.stderr || '')
      }
    }
  }
}

export function createShellExecutor (platform = process.platform, deps = {}) {
  const kill = deps.kill || ((pid, signal) => process.kill(pid, signal))
  const spawnSync = deps.spawnSync || nodeSpawnSync
  if (platform === 'win32') return windowsExecutor(spawnSync)
  if (platform === 'darwin' || platform === 'linux') return posixExecutor(kill)
  throw new Error(`不支持的平台：${platform}`)
}

export { POSIX_SHELL, WINDOWS_SHELL }
