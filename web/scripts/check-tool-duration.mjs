// 工具卡「耗时」口径的行为断言（纯逻辑，不启浏览器、不连后端）。
//
// 背景：簇头/组头原先是各张卡耗时之和，而工具是并发派发的——相加既比用户实际等的时间长（重叠）
// 又比它短（两次调用之间的思考不落在任何一张卡里）；elapsedMs 又只活在 SSE 那一帧、不落库，
// 刷新后每张卡都没数，簇头照样渲染「· 0ms」。改成首尾真实跨度后，这七条形态都必须钉住：
// 数据全部取自真机那两轮（并发 ls 两把、writeTodo/ls 重叠、plan_exit 等 8756ms、残缺 start 卡）。
//
// 用法：node scripts/check-tool-duration.mjs（已挂在 npm run check 里）
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
const src = readFileSync(join(fileURLToPath(new URL('..', import.meta.url)), 'src/chat/projections.js'), 'utf8')
const grab = re => { const m = src.match(re); if (!m) throw new Error('抠不出 ' + re); return m[0] }
const code = [
  /const toolDuration = ms => \{[\s\S]*?\n\}/,
  /const toolEndAt = t => \{[\s\S]*?\n\}/,
  /const spanOfTools = tools => \{[\s\S]*?\n\}/
].map(grab).join('\n')

let bad = 0
const ck = (ok, label, got) => { if (!ok) bad++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${got !== undefined ? '  →  ' + got : ''}`) }

// 客户端钟比服务端慢一小时：所有「进行中」的读数都必须抵掉这个偏移
const OFF = -3600000
const run = (tools, clientNow) => {
  const fn = new Function('nowTick', `${code}; return spanOfTools`)({ value: clientNow })
  return fn(tools)
}
const T0 = 1792080129885

// ① 老数据（只有 elapsedMs、没有服务端起点）：整块不显示，绝不出现 0ms
ck(run([{ name: 'searchKnowledge', status: 'done', elapsedMs: 842 }], T0) === '',
  '老数据没有 startedAtMs 时不报数（此前显示「· 0ms」）')

// ② 并发：真机那条 ls（两张卡各 1ms、同一毫秒起）——跨度 1ms，求和口径会是 2ms
ck(run([
  { name: 'ls', status: 'done', startedAtMs: T0, elapsedMs: 1 },
  { name: 'ls', status: 'done', startedAtMs: T0, elapsedMs: 1 }
], T0) === '1ms', '并发只算一次（求和口径读成 2ms）')

// ③ 重叠窗口取首尾：真机 writeTodo(157508,+2232) 与 ls(158075,+1665)，跨度 2.2s，求和口径是 3.9s
ck(run([
  { name: 'writeTodo', status: 'done', startedAtMs: 1792080157508, elapsedMs: 2232 },
  { name: 'ls', status: 'done', startedAtMs: 1792080158075, elapsedMs: 1665 }
], T0) === '2.2s', '重叠窗口取首尾（求和口径是 3.9s）')

// ④ 等待要从跨度里减掉：真机 plan_exit（等 8756ms、实际跑 556ms）夹在两把工具之间
ck(run([
  { name: 'plan_write', status: 'done', startedAtMs: 1792080143666, elapsedMs: 405 },
  { name: 'plan_exit', status: 'done', startedAtMs: 1792080145713, elapsedMs: 556, waitedMs: 8756 },
  { name: 'writeTodo', status: 'done', startedAtMs: 1792080157508, elapsedMs: 2232 }
], T0) === '7.3s', '等人那 8.8s 已从跨度里出去（不减会读成 16.1s）')

// ⑤ 进行中的卡跟着 tick 长并带省略号，且读数不受客户端时钟差影响
ck(run([{ name: 'run_code', status: 'start', startedAtMs: T0, startAt: T0 + OFF }], T0 + 5000 + OFF) === '5.0s…',
  '进行中按服务端钟跳秒（时钟差被收到帧那一刻的偏移抵掉）')

// ⑥ 历史里那种停在 start 没等到终态的残缺记录：终点未知，既不撑成一整天也不谎称 0ms
ck(run([{ name: 'askUser', status: 'start', startedAtMs: T0 }], T0 + 86400000) === '',
  '只有残缺 start 卡时不显示跨度')
ck(run([
  { name: 'ls', status: 'done', startedAtMs: T0, elapsedMs: 1 },
  { name: 'writeTodo', status: 'done', startedAtMs: T0 + 2000, elapsedMs: 3000 },
  { name: 'askUser', status: 'start', startedAtMs: T0 + 900000 }
], T0 + 86400000) === '5.0s', '残缺卡不拖跨度、不当终点（否则读成 15m0s）')

// ⑦ 跨多个 group 的簇：首尾之间含思考间隙，仍然只算一次墙钟
ck(run([
  { name: 'a', status: 'done', startedAtMs: T0, elapsedMs: 500 },
  { name: 'b', status: 'done', startedAtMs: T0 + 8000, elapsedMs: 500 }
], T0) === '8.5s', '两次调用之间的思考时间算进跨度（求和口径只有 1.0s）')

console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
process.exit(bad ? 1 : 0)
