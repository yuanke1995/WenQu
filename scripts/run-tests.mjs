#!/usr/bin/env node
/**
 * 测试收集器：不依赖 shell 展开 glob。
 *
 * Windows 的 cmd.exe 不展开 `test/*.test.mjs`，Node 20 的 --test 也不接受 glob，
 * 于是 `npm test` 在 Windows + Node 20 下会找不到文件。这里由 Node 自己遍历目录，
 * 收集测试文件后显式传给 node --test，三平台行为一致。
 */
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { pathToFileURL } from 'node:url'

const TEST_SUFFIXES = ['.test.mjs', '.test.js']

export function collectTestFiles (dirs, cwd = process.cwd()) {
  const files = []
  const visit = dir => {
    let entries
    try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch (e) {
      const err = new Error(`找不到测试目录：${dir}（${e.message}）`)
      err.code = 'E_TEST_DIR'
      throw err
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) visit(full)
      else if (TEST_SUFFIXES.some(s => entry.name.endsWith(s))) files.push(full)
    }
  }
  for (const d of dirs) visit(path.resolve(cwd, d))
  return files.sort()
}

function main () {
  const dirs = process.argv.slice(2)
  if (!dirs.length) {
    console.error('用法：node scripts/run-tests.mjs <测试目录...>')
    process.exit(2)
  }
  const files = collectTestFiles(dirs)
  if (!files.length) {
    console.error(`[run-tests] 没有在 ${dirs.join(', ')} 下找到测试文件`)
    process.exit(1)
  }
  const r = spawnSync(process.execPath, ['--test', ...files], { stdio: 'inherit' })
  process.exit(r.status == null ? 1 : r.status)
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
if (isMain) main()
