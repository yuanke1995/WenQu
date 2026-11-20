#!/usr/bin/env node
/**
 * 行尾守卫：.gitattributes 的自动化验收。
 *
 * 为什么需要它：行尾错误不是"风格问题"，而是 Windows 上脚本直接不可执行的问题
 * （CRLF 的 shebang 会变成 `bash\r`）。靠人工记着设置 core.autocrlf 不可靠，
 * 所以在 CI 与本地都跑这一条断言，让策略可执行、可回归。
 *
 * 检查三件事：
 *   1. .gitattributes 存在；
 *   2. 文本文件在索引中必须规范化为 LF（防止提交进 CRLF）；
 *   3. 工作区行尾与 .gitattributes 的声明一致（.bat/.cmd/.ps1 为 CRLF，其余为 LF）。
 *
 * 只读：不修改任何文件。修复提示里给出的 git add --renormalize 由人自行决定何时执行。
 */
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function fail (message) {
  console.error(`[eol] ${message}`)
  process.exit(1)
}

if (!fs.existsSync(path.join(root, '.gitattributes'))) {
  fail('缺少 .gitattributes：Windows 检出会把 .sh/.githooks 变成 CRLF，脚本将无法执行。')
}

const git = spawnSync('git', ['ls-files', '--eol', '-z'], {
  cwd: root,
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024
})
if (git.status !== 0) fail(`git ls-files --eol 执行失败：${git.stderr?.trim() || git.error?.message || git.status}`)

const problems = []
let checked = 0
for (const record of git.stdout.split('\0')) {
  if (!record) continue
  // 形如：i/lf    w/lf    attr/text eol=lf    \tpath/to/file
  const m = /^(i\/\S+)\s+(w\/\S+)\s+(attr\/[^\t]*)\t(.*)$/s.exec(record)
  if (!m) {
    problems.push(`无法解析 git eol 记录：${JSON.stringify(record.slice(0, 100))}`)
    continue
  }
  const [, indexEol, workEol, attr, file] = m
  // 二进制文件（png/zip/...）不参与行尾策略
  if (indexEol === 'i/-text' || indexEol === 'i/none' || /\b-text\b/.test(attr)) continue

  const windowsScript = /\.(bat|cmd|ps1)$/i.test(file)
  const expectedWork = windowsScript ? 'crlf' : 'lf'
  const expectedAttr = windowsScript ? 'eol=crlf' : 'eol=lf'

  if (indexEol !== 'i/lf') {
    problems.push(`${file}: 索引行尾是 ${indexEol}，应为 i/lf（提交前用 git add --renormalize .）`)
  }
  if (!attr.includes(expectedAttr)) {
    problems.push(`${file}: .gitattributes 未声明 ${expectedAttr}（当前 attr=${attr.trim()}）`)
  }
  if (workEol !== `w/${expectedWork}`) {
    problems.push(`${file}: 工作区行尾是 ${workEol}，应为 w/${expectedWork}（重新检出或按 .gitattributes 归一）`)
  }
  checked++
}

if (problems.length) {
  console.error(`[eol] 检查 ${checked} 个文本文件，发现 ${problems.length} 处问题：`)
  for (const p of problems.slice(0, 50)) console.error(`  - ${p}`)
  if (problems.length > 50) console.error(`  …其余 ${problems.length - 50} 处略`)
  process.exit(1)
}
console.log(`[eol] OK：${checked} 个文本文件的索引/工作区行尾均符合 .gitattributes`)
