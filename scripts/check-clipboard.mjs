#!/usr/bin/env node
/**
 * 剪贴板单一入口守卫：业务代码里禁止直连 navigator.clipboard。
 * 非 HTTPS 内网部署下该 API 不存在，直连会静默失效；所有调用必须走 utils/clipboard.js
 * 的双通道实现（writeText → execCommand('copy')）。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const webSrc = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'web', 'src')
const allowed = path.join(webSrc, 'utils', 'clipboard.js')
const problems = []

const visit = dir => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) visit(full)
    else if (/\.(js|vue)$/.test(entry.name) && full !== allowed) {
      fs.readFileSync(full, 'utf8').split('\n').forEach((line, i) => {
        if (line.includes('navigator.clipboard')) problems.push(`${path.relative(process.cwd(), full)}:${i + 1}`)
      })
    }
  }
}
visit(webSrc)

if (problems.length) {
  console.error('[clipboard] 以下位置直连 navigator.clipboard，请改用 utils/clipboard.js：')
  for (const p of problems) console.error(`  - ${p}`)
  process.exit(1)
}
console.log('[clipboard] OK：所有复制调用都走 utils/clipboard.js 双通道')
