// 整轮「用时」那一条的口径断言（纯逻辑，不启浏览器、不连后端）。
//
// 背景：耗时原先在气泡里报四五处（簇头、组条、每张工具卡、无时间线的兜底列表），一屏六个读数读不出
// 重点，两个口径还互相打架（组条是首尾跨度、单卡是各自跑掉多久）。现在一轮只气泡顶部那一条报一个数，
// 且它同时是整块过程的折叠开关 —— 于是三件事必须钉住：
//   ① 那个数只能是「整轮」的，不许有第二处冒出来（逐行耗时不许复现）；
//   ② 没记录就不报数：存量消息没落库这个数，宁可空着，也不许凑一个「0 秒」或工具跨度冒充；
//   ③ 跑着展开、答完自动收起，但用户点过的那一次永远优先。
// 数字取自真机那几轮（6 分 26 秒 / 35 分 19 秒 / 等卡 8756ms）。
//
// 用法：node scripts/check-turn-duration.mjs（已挂在 npm run check 里）
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
const src = readFileSync(join(fileURLToPath(new URL('..', import.meta.url)), 'src/chat/projections.js'), 'utf8')
const grab = re => { const m = src.match(re); if (!m) throw new Error('抠不出 ' + re); return m[0] }
// 单行箭头函数按整行抠：多行那种 [\s\S]*? 会停在第一个 ) 上，把后半截判据悄悄截掉
const oneLine = name => grab(new RegExp(`^const ${name} = .*$`, 'm'))
const code = [
  /const durZh = ms => \{[\s\S]*?\n\}/,
  /const hasTimelineBlocks = m => \{[\s\S]*?\n\}/,
  /const turnElapsedMs = m => \{[\s\S]*?\n\}/,
  /const turnWaitMs = m => \{[\s\S]*?\n\}/,
  /const resumedStartAt = \(elapsedMs, clientNow\) => \{[\s\S]*?\n\}/,
  /const turnOpen = m => \{[\s\S]*?\n\}/,
  /const turnHead = m => \{[\s\S]*?\n\}/,
  /const turnTitle = m => \{[\s\S]*?\n\}/,
  /const toolCallsView = list => \{[\s\S]*?\n\}/
].map(grab).join('\n') + '\n'
  + ['turnExpandable', 'turnToggleable', 'turnShow', 'turnWarn', 'turnTop', 'turnBottom', 'executedCount'].map(oneLine).join('\n')

let bad = 0
const ck = (ok, label, got) => { if (!ok) bad++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${got !== undefined ? '  →  ' + got : ''}`) }

// nowTick 由调用方注入：跑着的那一份读数靠它，客户端钟准不准都在这一个入口上
const api = clientNow => new Function('nowTick',
  `${code}; return { durZh, turnElapsedMs, turnOpen, turnShow, turnWarn, turnTop, turnBottom, turnToggleable, turnHead, turnTitle, executedCount, toolCallsView, resumedStartAt }`)
  ({ value: clientNow })

const T0 = 1792080129885
const done = ms => ({ role: 'ai', loading: false, time: T0, turnMs: ms })

// ① 存量消息（改动前落的库，没这个数）：一个字的耗时都不许编
{
  const { turnHead, turnShow } = api(T0 + 5000)
  ck(turnHead({ role: 'ai', loading: false, time: T0 }) === '已完成',
    '没落库用时的历史消息不报数（此前会读成「0 秒」或拿工具跨度凑）', turnHead({ role: 'ai', loading: false }))
  ck(turnShow({ role: 'ai', loading: false, toolCalls: [{ name: 'ls' }] }) === true,
    '没数但有过程可展开时这条仍然在（它是折叠开关，不只是个数字）')
  ck(turnShow({ role: 'ai', loading: false }) === false,
    '既没数又没过程（什么都没跑出来就收尾）不占一行')
}

// ② 中文长格式：这一条独占一行标题，要读出量级，不是行尾 chip 那种 226.0s
{
  const { durZh } = api(T0)
  ck(durZh(40500) === '40.5 秒', '不满一分钟留一位小数', durZh(40500))
  ck(durZh(2000) === '2 秒', '整秒不拖「.0」', durZh(2000))
  ck(durZh(386000) === '6 分 26 秒', '真机那一轮 6 分 26 秒', durZh(386000))
  ck(durZh(2119000) === '35 分 19 秒', '真机那一轮 35 分 19 秒', durZh(2119000))
  ck(durZh(120000) === '2 分', '整分不补「0 秒」', durZh(120000))
  ck(durZh(3661000) === '1 小时 1 分', '过一小时换成小时（否则读成 61 分）', durZh(3661000))
  ck(durZh(0) === '不到 1 秒', '0 也不报成空串', durZh(0))
}

// ③ 收尾那份读后端给的两个数：不受客户端时钟差影响（不是拿两个钟相减）
{
  const { turnElapsedMs, turnHead } = api(T0 + 3 * 3600000)  // 客户端钟快了三小时
  ck(turnElapsedMs(done(386000)) === 386000, '收尾读数＝落库那份，不拿两个钟相减')
  ck(turnHead(done(386000)) === '已完成，用时 6 分 26 秒', '收尾标题', turnHead(done(386000)))
  ck(turnHead({ ...done(386000), errorCard: {} }) === '这一轮中断，用时 6 分 26 秒',
    '中断轮说中断（把半截轮报成「已完成」是在替用户高兴）')
}

// ④ 跑着的时候按本地钟跟着 tick 走：这一轮还没结束，后端那份数根本还不存在
{
  const { turnElapsedMs, turnHead, turnWarn } = api(T0 + 9000)
  const m = { role: 'ai', loading: true, time: T0 }
  ck(turnElapsedMs(m) === 9000, '进行中按开流那一刻起算', turnElapsedMs(m))
  ck(turnHead(m) === '正在溯源，用时 9 秒 …', '进行中标题说「溯源」（问渠的说法）并带省略号', turnHead(m))
  ck(!/深度求索/.test(turnHead(m)), '不借别家的措辞（这一条挂在问渠的回答上）')
  // 这一条是一轮里唯一的进行中提示：自动重试不另起一行，改说在同一句里（异常不能静默）
  const r = { role: 'ai', loading: true, time: T0, retrying: true }
  ck(turnHead(r) === '连接中断，正在自动重试，用时 9 秒 …',
    '重试中把异常说在这一行上（此前是底下再挂一行，两句「正在…」上下同屏）', turnHead(r))
  ck(turnWarn(r) === true && turnWarn(m) === false && turnWarn(done(386000)) === false,
    '警示配色只认「在跑且在重试」：正常在跑与答完都不染')
  ck(turnElapsedMs({ role: 'ai', loading: true }) === 0,
    '开流时刻没记上（接回来的壳）就报 0，不许报 NaN 或负数', String(turnElapsedMs({ role: 'ai', loading: true })))
}

// ⑤ 收尾那份报的是墙钟（机器时间 + 等人那一段）：屏幕上跳着的就是墙钟，答完那一刻不能变小；
//    等过的时长不占标题，只在悬停里交代
{
  const { turnTitle, turnElapsedMs, turnHead } = api(T0)
  const waited = { role: 'ai', loading: false, time: T0, turnMs: 386000, humanWaitMs: 8756 }
  ck(turnElapsedMs(waited) === 394756, '收尾＝机器 6 分 26 秒 + 等卡 8.8 秒（只报机器时间会让数字变小）',
    String(turnElapsedMs(waited)))
  ck(turnHead(waited) === '已完成，用时 6 分 35 秒', '标题读的是相加那份', turnHead(waited))
  ck(/其中等你答题或批准 8\.8 秒/.test(turnTitle(waited)),
    '等过的时间说在悬停里（不然一轮答得慢，账全算在模型头上）', turnTitle(waited))
  ck(turnTitle(done(386000)) === '从发出提问到答完实际用的时间',
    '没等过就不提「等你答题」（无中生有会让用户去找那张没出现过的卡）')
  ck(turnElapsedMs({ role: 'ai', loading: false, tokens: { turnElapsedMs: 386000 } }) === 386000,
    '刷新后读 tokens 里落库的那一份（done 那帧早没了）')
}

// ⑤b 接流起表点：刷新接上的那一轮按服务端「已跑掉的毫秒」换算成本地钟，不从 0 重新起跳。
//    真机 2026-10-22：生成中刷新，「正在溯源，用时 2 秒」从头再跳一遍，已经跑掉的那段不算数了。
//    给时长而不给绝对开轮时刻：nowTick 与本地 time 都在客户端钟上，跨钟相减会把两边的
//    钟偏差直接算进用户眼里（与 ③ 同一条理由）。
{
  const { resumedStartAt, turnElapsedMs, turnHead } = api(T0 + 90000)   // 接上时本地钟
  const resumed = { role: 'ai', loading: true, time: resumedStartAt(88000, T0 + 90000) }
  ck(turnElapsedMs(resumed) === 88000, '接流的这一轮从服务端已跑时长起表（不是从接上那一刻归零）',
    String(turnElapsedMs(resumed)))
  ck(turnHead(resumed) === '正在溯源，用时 1 分 28 秒 …', '刷新后标题接着报真实用时', turnHead(resumed))
  ck(resumedStartAt(0, T0) === T0, '快照没带这个数（旧服务端/空快照）维持从接上起表，不编一个过去时刻')
  ck(resumedStartAt(undefined, T0) === T0 && resumedStartAt(null, T0) === T0, '字段缺失同样不猜')
  ck(resumedStartAt(5000, 1000) === 0, '换算夹到 0：不许报负数秒')
}

// ⑥ 折叠默认态与「什么时候点得动」
{
  const { turnOpen, turnToggleable } = api(T0)
  ck(turnOpen({ loading: true }) === true, '跑着恒展开（要看得见过程长出来）')
  ck(turnOpen({ loading: false }) === false, '答完默认收起（一屏只剩顶上那行总览）')
  ck(turnOpen({ loading: true, _turnOpen: false }) === true,
    '跑着时不认旧开合态：上一版收过、或重新生成复用同一个对象，都不许把这一轮的过程藏起来')
  ck(turnOpen({ loading: false, _turnOpen: true }) === true, '收场后被用户点开过：保持展开（他在读过程）')
  ck(turnToggleable({ loading: true, toolCalls: [{ name: 'ls' }] }) === false,
    '跑着的时候这一条点不动（它只是跟着秒走的状态，收起正在长的过程没有可读的东西）')
  ck(turnToggleable({ loading: false, toolCalls: [{ name: 'ls' }] }) === true, '答完且有过程：给开关')
  ck(turnToggleable({ loading: false, turnMs: 5000 }) === false, '答完但一步没跑：不给假按钮')
}

// ⑦ 躺哪儿：跑着的时候跟在正文最后面（计时器要在眼睛所在的那一行），答完回到回答顶上当总览
{
  const { turnTop, turnBottom } = api(T0)
  const run = { role: 'ai', loading: true, time: T0 }
  const fin = { role: 'ai', loading: false, turnMs: 386000 }
  ck(turnBottom(run) === true && turnTop(run) === false, '正在回答：这一条在正文末尾，不在顶上')
  ck(turnTop(fin) === true && turnBottom(fin) === false, '答完：这一条回到顶上（总览），不留在正文下面')
  ck(turnTop({ role: 'ai', loading: false }) === false && turnBottom({ role: 'ai', loading: false }) === false,
    '既没数又没过程的轮次两处都不出现')
}

// ⑧ 收尾那一条只报用时，不报步数。步数原先挂在这里，但它数的是 toolCalls 那份，而点开展开的是
//    时间线那一份——引用自检改过正文时后端整条时间线作废（timelineJson 返回 null），于是「5 步操作」
//    点开一片空。一个兑不了现的读数不如没有。
{
  const { turnHead, executedCount, toolCallsView } = api(T0)
  const t = n => ({ role: 'ai', loading: false, turnMs: 386000, toolCalls: Array.from({ length: n }, (_, k) => ({ name: 't' + k, status: 'done' })) })
  ck(turnHead(t(5)) === '已完成，用时 6 分 26 秒', '跑了 5 步也不在这条上报步数', turnHead(t(5)))
  ck(turnHead({ ...t(5), errorCard: {} }) === '这一轮中断，用时 6 分 26 秒', '中断轮同样不带步数')
  ck(turnHead(t(5)) === turnHead({ role: 'ai', loading: false, turnMs: 386000 }),
    '一步没跑与跑了五步长得一样（动没动手由那一条的折叠三角说，不由数字说）')
  ck(executedCount(toolCallsView(t(5).toolCalls)) === 5, '「执行了 N 个操作」那份口径照旧数得对')
  ck(executedCount(toolCallsView([{ name: 'a', status: 'done' }, { name: 'b', status: 'done', denied: true }])) === 1,
    '被守卫在执行前挡下的那次不计入（否则「只让它试了 4 次」读成「它试了 5 次」）')
}

// ⑨ done 汇总不许把已经终态的卡再算一遍：「执行了 N 个操作」读的就是这份数组，
//    多 push 一张就是当场翻倍（真机 2 次 writeTodo 数成 4 次），刷新后又变回 2 次
{
  const merge = grab(/const mergeDoneToolCalls = \(m, doneCalls\) => \{[\s\S]*?\n\}\n/)
  // 先跑一次外壳拿到函数本身：直接把 new Function(...) 当函数调，跑的是外壳、合并压根没发生，
  // 断言会假绿（这条踩过）
  const run = new Function(`${merge}; return mergeDoneToolCalls`)()
  // 实时帧上的 args 是 200 字摘要、done 那份才是全文：两份都带 args 且不同值，才测得出「配对了哪一张」
  const live = [{ name: 'writeTodo', status: 'done', args: '摘要1' }, { name: 'writeTodo', status: 'done', args: '摘要2' }]
  const db = [{ name: 'writeTodo', status: 'done', args: '1', elapsedMs: 10 }, { name: 'writeTodo', status: 'done', args: '2', elapsedMs: 20 }]
  const m = { toolCalls: live }
  run(m, db)
  ck(m.toolCalls.length === 2, '两张已终态的同名卡 + done 两份 = 还是两张（此前变成四张，操作数翻倍）', String(m.toolCalls.length))
  ck(m.toolCalls[0].args === '1' && m.toolCalls[1].args === '2',
    '按到达顺序配对，不把后一次的入参并到前一张卡上', JSON.stringify(m.toolCalls.map(x => x.args)))
  ck(m.toolCalls[0].elapsedMs === 10 && m.toolCalls[1].elapsedMs === 20, '终态字段各归各的卡')
  const m2 = { toolCalls: [{ name: 'ls', status: 'start', args: 'a' }] }
  run(m2, [{ name: 'ls', status: 'done', args: 'a', elapsedMs: 5 }])
  ck(m2.toolCalls.length === 1 && m2.toolCalls[0].status === 'done', '还挂在「进行中」的那张照旧就地收尾')
  const m3 = { toolCalls: [] }
  run(m3, [{ name: 'ls', status: 'done' }, { name: 'ls', status: 'done' }])
  ck(m3.toolCalls.length === 2, '实时帧整条没收到时两份都补上（不把两次同名调用合成一张卡）', String(m3.toolCalls.length))
}

console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
process.exit(bad ? 1 : 0)
