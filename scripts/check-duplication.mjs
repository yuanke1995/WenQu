#!/usr/bin/env node
/**
 * 重复实现守卫：把已经收口到单一入口的逻辑挡住，防止复制粘贴回潮。
 *
 * 每条规则对应一次真实的全仓扫描结论，不是凭空的洁癖：
 *   - SHA-256：曾散在 27 个文件里，其中两处失败后降级成 Integer.toHexString(hashCode())，
 *     碰撞概率从 2^-128 掉到 2^-32 且调用方无感。统一走 util/Hashes.java。
 *   - createObjectURL 下载：撤销时机曾分三派（立即 / 1000 / 2000），立即 revoke 会让
 *     Firefox / Safari 的异步取流中断。统一走 utils/download.js 的 downloadBlob。
 *   - 批量勾选五件套：曾在对 6 个运营页逐字复制，退出批量忘清空勾选这类单页 bug 看不出来。
 *     统一走 utils/useBatchSelect.js。
 *
 * 用法：node scripts/check-duplication.mjs
 * 加白名单：文件名加进对应规则的 allow 列表，并写清为什么这次确实是特例。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const javaSrc = path.join(root, 'src', 'main', 'java')
const webSrc = path.join(root, 'web', 'src')

const walk = (dir, out = []) => {
  if (!fs.existsSync(dir)) return out
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, out)
    else out.push(full)
  }
  return out
}

const rules = [
  {
    name: 'sha256',
    files: () => walk(javaSrc).filter(f => f.endsWith('.java')),
    // 允许内联的范围：Hashes 自身；以及「边读边摘要」「先拼盐再摘要」这类不是单纯 hash(字符串) 的场景
    allow: [
      'util/Hashes.java',
      // 流式摘要：DigestInputStream 边读边喂，无法用 hash(String)
      'service/DocumentIngestService.java',
      // 大文件校验流：MessageDigest 在 finally 里比对
      'sandbox/ProvisionerSandboxBackend.java',
      // 签名摘要：先 update(盐) 再 digest(bytes) 的结构与 Hashes 的两种重载都不同构
      'parser/DocxParser.java',
      // Base64url 拼签名，不是十六进制
      'service/SamlService.java',
      // 审计链哈希：失败返回 "HASH_ERROR"，是链上约定的一部分，改动会破坏历史校验
      'service/AuditService.java',
      // 以下为「ID 必须与既有数据保持稳定」的派生键，暂不动以免影响在跑的沙箱/绑定
      'sandbox/ProvisionerSandboxProvider.java',
      'agentx/FrameworkSessions.java',
      'startup/ManualSeedService.java',
      'service/RetrievalEvalRunService.java'
    ],
    test: (file, line) => line.includes('MessageDigest.getInstance("SHA-256")'),
    hint: '改用 com.wenqu.ai.util.Hashes（sha256Hex / sha256HexPrefix / sha256HexSalted）'
  },
  {
    name: 'download-blob',
    files: () => walk(webSrc).filter(f => /\.(js|vue)$/.test(f)),
    allow: [
      // createObjectURL 不等于「下载」：图片压缩预览、blob 预览窗口、iframe 用 blob: 打开
      // 都是合法用途。本规则只拦「造 <a download> 并 click」这一种写法，见 test。
      'utils/download.js',
      // api.js 的 saveBlob 是本规则的实现本体（api.js 不便引 DOM 工具，但口径与 downloadBlob 一致）
      'api.js'
    ],
    // 只命中行内同时出现 createObjectURL 且这是下载写法（靠上下文 a.download / a.click 判定不可靠，
    // 故要求本行或前一行出现 .download = 或 a.click()），下面在 test 里做双行判断。
    test: (file, line, ctx) => {
      if (!line.includes('URL.createObjectURL')) return false
      // 下载写法：createObjectURL 之后不远处一定跟着 a.download = / .click()。取前后各 4 行，
      // 留够「先声明 blob、再建 a、再设 download」这种跨行写法（只看前后 1 行会漏）。
      return /\.download\s*=|\.click\(\)/.test(ctx)
    },
    hint: '改用 utils/download.js 的 downloadBlob(content, filename, type)'
  },
  {
    name: 'batch-select',
    files: () => walk(path.join(webSrc, 'views')).filter(f => /\.vue$/.test(f)),
    allow: [
      // 产物页的选择态 API 与 useBatchSelect 不同构：toggleSelect(id, checked) 接收布尔而非取反，
      // toggleAll 接收事件对象（勾选框 target.checked vs 底部按钮点击），且 selectedIds 被模板直接读。
      // 硬套 composable 需要改模板与两处入口语义，收益不抵风险，故保留独立实现。
      'views/ArtifactsPage.vue'
    ],
    test: (file, line) => /^\s*(const|let)\s+allChecked\s*=/.test(line),
    hint: '改用 utils/useBatchSelect.js 的 useBatchSelect(selectableIds)'
  }
]

const problems = []
for (const rule of rules) {
  for (const file of rule.files()) {
    const rel = path.relative(root, file).replaceAll(path.sep, '/')
    const allowedForRule = rule.allow.some(a => rel.endsWith(a))
    if (allowedForRule) continue
    const lines = fs.readFileSync(file, 'utf8').split('\n')
    lines.forEach((line, i) => {
      const ctx = lines.slice(Math.max(0, i - 4), i + 5).join('\n')
      if (rule.test(file, line, ctx)) problems.push({ rule: rule.name, at: `${rel}:${i + 1}`, hint: rule.hint })
    })
  }
}

if (problems.length) {
  console.error('[duplication] 发现重复实现，请改用单一入口：')
  for (const p of problems) console.error(`  - [${p.rule}] ${p.at}\n       → ${p.hint}`)
  console.error('\n若确属特例，在 scripts/check-duplication.mjs 对应规则的 allow 里登记并写明原因。')
  process.exit(1)
}
console.log('[duplication] OK：SHA-256 / 下载 / 批量勾选均走单一入口')