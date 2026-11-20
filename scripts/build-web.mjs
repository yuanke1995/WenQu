// 前端生产构建入口：集中控制 Node 老生代堆上限。
// Vite/Rollup 会把整个模块图常驻内存，模块数上万时默认堆（约 2GB）会
// "FATAL ERROR: Reached heap limit"，这里默认提到 4GB。
// 构建机内存不同时用 WENQU_BUILD_HEAP_MB 覆盖，例如：
//   WENQU_BUILD_HEAP_MB=6144 npm run build
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const heapMb = process.env.WENQU_BUILD_HEAP_MB || '4096'
if (!/^\d+$/.test(heapMb) || Number(heapMb) < 128) {
  console.error(`WENQU_BUILD_HEAP_MB 必须是 >=128 的整数，收到: ${heapMb}`)
  process.exit(2)
}

const webDir = path.dirname(fileURLToPath(new URL('../web/package.json', import.meta.url)))
const viteBin = path.join(webDir, 'node_modules', 'vite', 'bin', 'vite.js')

const result = spawnSync(
  process.execPath,
  [`--max-old-space-size=${heapMb}`, viteBin, 'build', ...process.argv.slice(2)],
  { stdio: 'inherit', cwd: webDir, env: process.env }
)
process.exit(result.status ?? 1)
