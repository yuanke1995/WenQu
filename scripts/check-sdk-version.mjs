#!/usr/bin/env node
/**
 * B1 出包闸门：两套 SDK 的版本号必须与各自包清单一致，且两端同步。
 * 版本头上报给服务端用于排查「契约改了、客户端还是旧 SDK」，漂了就等于这个信号失效。
 * 用法：node scripts/check-sdk-version.mjs（CI 与本地发布前同一条命令）
 */
import { readFileSync } from 'node:fs'

const read = (p) => readFileSync(new URL(`../${p}`, import.meta.url), 'utf8')
const pick = (text, re, label) => {
  const m = text.match(re)
  if (!m) throw new Error(`未能在 ${label} 里解析出版本号`)
  return m[1]
}

const found = {
  'sdk/js/package.json': pick(read('sdk/js/package.json'), /"version"\s*:\s*"([^"]+)"/, 'package.json'),
  'sdk/js/src/version.ts': pick(read('sdk/js/src/version.ts'), /SDK_VERSION\s*=\s*"([^"]+)"/, 'version.ts'),
  'sdk/python/pyproject.toml': pick(read('sdk/python/pyproject.toml'), /^version\s*=\s*"([^"]+)"/m, 'pyproject.toml'),
  'sdk/python/src/wenqu/_version.py': pick(read('sdk/python/src/wenqu/_version.py'), /__version__\s*=\s*"([^"]+)"/, '_version.py'),
}

const versions = [...new Set(Object.values(found))]
if (versions.length !== 1) {
  for (const [file, v] of Object.entries(found)) console.error(`  ${file}: ${v}`)
  throw new Error('SDK 版本不一致，发布前先同步上面四处')
}
console.log(`SDK 版本一致：${versions[0]}（${Object.keys(found).length} 处）`)
