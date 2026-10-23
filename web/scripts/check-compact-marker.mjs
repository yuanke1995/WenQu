// 「压缩留痕」这条跨栈契约的静态校验：写侧、读侧、状态帧三处任何一处漂了就判红。
// 起因：那条「已压缩上下文」分隔线整条静默失效过一阵——自研压缩交回 AgentScope 时（3fc1d519）
// 写侧（往消息行 tokens 里落覆盖点）被删了，读侧（前端按 tokens 判定画不画线）留在原地读一个
// 再没人写的键。不报错、不抛异常，表现是「正在压缩上下文」闪一下就没，刷新后整条会话查无压缩痕迹。
// 这条链没有类型系统管得到（跨进程靠一个 JSON 键名对上），只能靠这里盯着。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const JAVA = path.join(WEB, '..', 'src', 'main', 'java', 'com', 'wenqu', 'ai')

const read = p => (fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : '')
const engine = read(path.join(JAVA, 'agentx', 'AgentscopeChatEngine.java'))
const bus = read(path.join(JAVA, 'service', 'ChatRunBus.java'))
const web = read(path.join(WEB, 'src', 'chat', 'useChatEngine.js'))
const proj = read(path.join(WEB, 'src', 'chat', 'projections.js'))
const pc = read(path.join(WEB, 'src', 'views', 'ChatPage.vue'))
const h5 = read(path.join(WEB, 'src', 'h5', 'MobileChatPage.vue'))

const failures = []
const check = (name, pass, why) => {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${pass ? '' : `  —— ${why}`}`)
  if (!pass) failures.push(name)
}
if (!engine || !web) {
  console.log('SKIP  没读到后端引擎或前端引擎源码，跳过')
  process.exit(0)
}

// 1) 写侧：收尾必须把轮标记落进 tokens
check('后端把轮标记落进消息行 tokens', /tokens\.put\("compacted"/.test(engine),
  '「已压缩上下文」线唯一的数据源，写侧一删前端就永远读不到')
// 2) 读侧：前端按同一个键名判定
check('前端按 tokens.compacted 判定画线', /tokens\?\.compacted|tokens\.compacted/.test(web),
  '键名与写侧必须逐字相同（跨进程只靠这个名字对上）')
// 3) 起止帧要带成败状态：压完了线原地换成留痕，压砸了一条线都不许留
check('compaction/end 帧带 status', /"phase",\s*"end"[\s\S]{0,200}"status"/.test(engine),
  '不带状态，前端就只能无条件报喜')
check('前端按 status=completed 才补留痕', /status\s*===\s*'completed'/.test(web),
  '否则失败的压缩也会画出一条线')
// 4) 进度帧进接流镜像：中途刷新的人只拿快照重建，不镜像就是「生成中却一个字没有」
check('compact_state 在接流镜像名单里', /MIRROR_TYPES[\s\S]{0,200}"compact_state"/.test(bus),
  '压缩那二十多秒里刷新，界面上会没有任何进度说明')
// 5) 收尾把轮标记补进内存那份 tokens：usage/done 两帧都不带 compacted（非用量键会让容量面板
//    读到一本空账），只落在库那份里 —— 不补，提问上方那条线在答完那一刻就消失、刷新才回来
check('收尾给本轮 tokens 补 compacted', /liveCompactedSid\.value === sid[\s\S]{0,160}compacted: 1/.test(web),
  '留痕从「当场」交到「落库判据」之间断档：答完即消失，用户读成压没压过要看运气')
// 6) 自动压缩的进度说在状态行上（本轮唯一那一条），不另起一条流尾分隔线：
//    一轮里框架可以压好几次，另起的那条每压完撤走、下次又长出来，就是反复弹出
check('状态行认压缩态', /m\.compacting\s*\?\s*'正在压缩上下文'/.test(proj),
  '不认，那二十多秒在界面上又是纯静默')
// 7) 流尾那条进度线只认手动那一路（两端同契约）；拿 compacting 当判据就把自动那一路又接回来
for (const [name, src] of [['PC', pc], ['H5', h5]]) {
  check(`${name} 流尾进度线只认 manualCompacting`,
    /v-if="manualCompacting"/.test(src) && !/v-if="compacting"[^>]*class="[^"]*(cn-tail|m-cdiv-tail)"/.test(src),
    '自动压缩也点亮它，就会和提问上方的留痕同时挂着、并随每次压缩反复出现')
}
// 8) 分隔线的列宽只能收进内边距：这三条是 .row 的 flex 项，组件靠 flex-basis:100% 独占一行，
//    给项本身设 max-width 会把基准宽压到列宽，宽屏上线与消息块挤进同一条 flex 行
check('分隔线列宽走 padding 不设 max-width',
  /\.cdiv, \.msd, \.cn-tail \{[^}]*padding-inline:/.test(pc) && !/\.cdiv, \.msd, \.cn-tail \{[^}]*max-width/.test(pc),
  '宽屏下那条线探进左侧留白、气泡被推到右缘外（2434 视口实测偏 430px）')

console.log(failures.length ? `\n${failures.length} 条失败` : '全部通过')
process.exit(failures.length ? 1 : 0)
