#!/usr/bin/env node
/**
 * SshTransport：本机 OpenSSH 的参数构造。
 * ControlMaster/ControlPath/ControlPersist 依赖 Unix domain socket，只在 POSIX 下启用；
 * Windows 自带 OpenSSH 不支持连接复用，直接省略这三项，而不是传一个注定失败的路径。
 */
import fs from 'node:fs'
import path from 'node:path'

export function createSshTransport (platformName, deps = {}) {
  const fileSystem = deps.fs || fs
  return {
    name: 'ssh-transport',
    argsFor (alias, command, controlDir) {
      const args = [
        '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'ConnectTimeout=10',
        '-o', 'ServerAliveInterval=15',
        '-o', 'ServerAliveCountMax=3'
      ]
      if (platformName !== 'win32') {
        fileSystem.mkdirSync(controlDir, { recursive: true, mode: 0o700 })
        const control = path.join(controlDir, 'cm-' + String(alias).replace(/[^A-Za-z0-9._-]/g, '_'))
        args.push(
          '-o', 'ControlMaster=auto',
          '-o', 'ControlPath=' + control,
          '-o', 'ControlPersist=60'
        )
      }
      args.push('--', alias, command)
      return args
    }
  }
}
