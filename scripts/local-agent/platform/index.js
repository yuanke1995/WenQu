#!/usr/bin/env node
/**
 * 平台能力层入口：整份执行器里**唯一**允许读取 process.platform 的地方。
 *
 * 上层（opExec / 进程管理 / 系统集成 / 浏览器 / SSH）只拿这里构造出的接口，
 * 不再各自写 `if (process.platform === 'win32')`，也不再散落 /bin/sh、ps、npx 等二进制名。
 * 每个能力按 OS 提供完整实现；不支持的平台 fail-loud，而不是静默退化到某一套行为。
 */
import { createShellExecutor } from './shell.js'
import { createSandboxProvider } from './sandbox.js'
import { createProcMonitor } from './procs.js'
import { createSystemIntegration } from './system.js'
import { createGitLocator } from './git.js'
import { createBrowserLocator } from './browser.js'
import { createSshTransport } from './ssh.js'

const SUPPORTED = new Set(['darwin', 'linux', 'win32'])

export function createPlatform (platform = process.platform, deps = {}) {
  if (!SUPPORTED.has(platform)) {
    throw new Error(`不支持的平台：${platform}（问渠本机执行器只实现 darwin / linux / win32）`)
  }
  return Object.freeze({
    name: platform,
    shell: createShellExecutor(platform, deps),
    sandbox: createSandboxProvider(platform, deps),
    procs: createProcMonitor(platform, deps),
    system: createSystemIntegration(platform, deps),
    git: createGitLocator(platform, deps),
    browser: createBrowserLocator(platform, deps),
    ssh: createSshTransport(platform, deps)
  })
}
