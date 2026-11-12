// ==================== 聊天页投影层（纯函数与常量） ====================
// 「入参之外不读任何 .value」的纯函数与常量：
// 工具名映射、时间线视图、工具卡片、编排卡片、来源分组与各处格式化。
// 模块内唯一的响应式状态是 nowTick（运行中工具的实时耗时 tick），自产自销；
// 组件卸载时由消费方在 onUnmounted 里调 stopTick() 收尾。
import { ref } from 'vue'
import { chatStreams } from '../views/store'
import { fmtTokens } from '../utils/token'
import { fmtSize } from '../utils/format'
import { durZh } from '../utils/duration'
import { turnElapsedMs as elapsedAt, turnPrepMs, turnWaitMs, resumedStartAt, earlierStart, turnTitle as titleAt } from './turnTiming'
// 粘贴文本附件的名字前缀：产地是取材层（./intake.js），这里借用并原样再导出。
// 本模块只用它来「认回」这类附件（isPastedText），并不定义它
import { PASTE_TEXT_BASE } from './intake'
// 对话工具卡展示名的本地兜底：口径与后端一致——书面动宾短语写「它在干什么」
// （检索知识库 / 读取技能正文 / 提交计划待批准），对象用站内已有名词，不用机制名与口语。
// **首选后端下发的名字**：工具卡与审批载荷都带 label/hint 字段（源自工具注册中心的描述符），
// 下面的两张表只在载荷没带名字时用。新增工具不再需要在这里补一行就能显示中文名。
// 工具卡展示口径在 ./tools.js（纯模块），这里只转出。
export { TOOL_LABELS, TOOL_DESCS, bareToolName, toolLabel, toolDesc, executedCount, toolDuration, toolRunning } from './tools.js'

const toolCallsView = list => Array.isArray(list) ? list : []
/** 「执行了 N 个操作」的计数口径：只数真执行过的。
 *  被守卫在执行前挡下的那几条（denied，如本轮工具步数已用完）工具体一次没跑，
 *  卡片仍留一行说明为什么没跑，但计入操作数会把「平台只让它试了 4 次」读成「它试了 8 次还没找到」。
 *  denied 由后端 ToolPipelineFactory 在被守卫挡下时写入工具卡（框架引擎路径同源）。 */
// ==================== askUser（向用户提问）问答记录 ====================
// 工具入参 args 为 {questions:[{topic?,question,options}]} JSON 字符串；
// result=批量答案：多问题为与问题下标对齐的 JSON 数组字符串，单问题退化为纯文本
// （超时/忽略/留空的题回一句「（用户未回答这一题…」，本模块渲染成「未作答」，见 askAnswerLabel）。
//
// 两个必须收口的渲染口径（否则会出现「有问无答」的残缺问）：
//  ① 单问的答案后端退化成纯文本（buildCombinedAnswer 只在多问时回 JSON 数组），不能把「解析不出数组」
//     一律当成「这张卡没发出去」——那样正常作答的单问会连问句一起消失、只剩一句答案。
//     真正没发出去的卡（游客会话 / 提问次数用尽 / 无有效问题 / 本轮中止）回给模型的是一句括号里的
//     处置说明，按单条说明渲染，绝不按问题逐条铺开空答。
//  ② answers 数组比问题短的历史数据：只渲染有答案的前 N 问。
// 未作答的题回给模型的是一句处置指令（前缀由后端 buildCombinedAnswer 固定），卡片里收成「未作答」，
// 不把模型口径的长句原样摊给用户
// 提问卡投影在 ./askUser.js（纯模块），这里只转出。
export { askUserView, askAnswerLabel, askNoticeText } from './askUser.js'

/** 耗时文案：<1s 用 ms，1~60s 留一位小数，≥60s 换成「3m46s」，≥60m 再换成「1h2m」——226.0s 这种长小数读不出量级 */
// 委派/咨询口径在 ./consult.js（纯模块），这里只转出。
export { rememberAgents, consultNameOf, consultChatView, isDispatchTool, dispatchTaskOf, spawnAnchorOf, subagentRunsOf } from './consult.js'

// ==================== 时间线（正文一条流，工具只作过程步骤） ====================
// timeline 是事件段数组：
//   {kind:'text', from, to}  → 指向 m.content 的切片区间（不复制文本，done 换正文时自动跟随）；
//   {kind:'tool', tool}      → 引用 m.toolCalls 里的同一对象（done/error 原地改状态，卡片自动更新）；
//   {kind:'think'/'process', from, to} → 分别指向 m.thinking / m.processText；
//   {kind:'steer', text, messageId}    → 用户在本轮跑动中补的那句话。
// 渲染分两块：正文＝整段 content（无插话时一次渲染，有插话时按 turnBodyRows 在插话处切片），
// 过程＝思考/过程/工具段（turnProcessRows，收在顶部过程块）。
// 工具调用落在句子中间时，前后两半在 content 里本就紧邻，拼起来就是完整一句——不按工具边界切
// 正文，也不做任何句末标点推断；实时与落库用同一份段数组，刷新前后一致。
// 插话段不是正文，但它在时间线里有**精确位置**（后端 steer() 先 flush 此前正文再插段），
// 正文只在插话处下刀，切片拼回去仍是完整的 content，不改动正文内容本身。
// 时间线随消息落库（正文区间 + 工具/产物下标），历史恢复由 restoreTimeline 重建；旧消息无 timeline
// 时工具按终态清单成组，正文按整段。data-msg-index 挂在外层 .md 容器上，引用角标逻辑零改动。
// 注：插话段同时是一条落库用户消息（sequence 排在回答之前）：历史回显时凡 messageId 已被某条时间线
// 段用掉的，那颗独立气泡就退场（见 useChatEngine 的 steerSegmentIds），一处留痕就够。

// 时间线写入口/恢复在 ./timelineWrite.js（纯模块），这里只转出。
export { extendTimelineText, extendTimelineProcess, pushTimelineThink, extendTimelineThink, pushTimelineTool, pushTimelineArtifact, pushTimelineSteer, restoreTimeline } from './timelineWrite.js'

// 过程独白折叠态：键用段起点 from（流式期间 to 随增量增长、from 稳定；过程块每帧重算，
// 段对象本身不保状态，与工具卡 _open 存在 toolCalls 对象上同理，这里挂在消息上）。
// 用户点过就认点过的（true/false 写死在 _procOpen 上）；没点过时：流式期间自动展开「正在长的那一段」
// （过程按 token 增量下发，展开才看得见它逐字长出，而不是等闭合标签到达整块蹦出），本轮结束自动收起
// ——与思考段同款（thinkGrowing 流式期展开那一段，收场后自动折叠），历史消息恒折叠。
// 思考/过程段的折叠与提要口径在 ./segments.js（纯模块），这里只转出并按需使用。
export { procOpen, toggleProc, procSlice, thinkSlice, thinkPeek, thinkGrowing, thinkSegOpen, toggleThinkSeg, ensureThinkSegment } from './segments.js'
import { procOpen, toggleProc, procSlice, thinkSlice, thinkPeek, thinkGrowing, thinkSegOpen, toggleThinkSeg, ensureThinkSegment, peekText } from './segments.js'

// ==================== 过程簇（连续思考/过程/工具的二次折叠） ====================
/* 为什么需要这一层：模型一轮里常是「思考→工具→思考→工具」交替，而过程块遇 think/process 段
 * 就 flushGroup（它们天然切断工具分组，时序要如实保留），于是一轮过程被切成 3N 个折叠头。
 * 这里在视图层补一道**后置聚簇**：把 out 里连续的 think/process/group 段
 * 包成 cluster，折叠态收敛为 1 行；展开态由 clusterSteps 再摊一次——工具一行一步、思考并到步的
 * 行尾，段与段的先后次序不变（时序仍零损失），但不再是一段过程一行标题。
 *
 * 思考段为什么也要进游程：它和 process 一样是「执行细节」，不是正文。
 * 让它断簇等于每一轮思考都新开一行标题，用户要的「及时合并」恰恰合并不到它身上——
 * 收场后 TurnBar 把整块过程藏起来，才让人产生「输出完才合并」的错觉。
 *
 * 判定边界：
 *   ① 只在「游程内不含任何 text 段」时成立——即这一段过程与正文无交错、纯属执行细节；
 *      任何一段正文都断簇，否则会把「说完一段→查一下→接着说」的交错节奏一并压平。
 *   ② 单段的 think/process 游程不包（单个思考段套簇壳=多一次点击）；单段 group 照包——
 *      单批工具同样要进帽，否则页面照样被撑长。
 *
 * 簇键用序号 c{N}（N = res.length）。序号稳定性论证：时间线只追加不重排，text 段到来时
 * 已 flush 的簇其 res 下标不会变动，新簇一律追加在尾部，故历史键不会错位到别的簇上。
 * （对比用 from 做键不成立：工具段与独白段的下标来自两套独立空间。）*/
// 过程聚簇在 ./processCluster.js（纯模块），这里只转出并供 turnProcessRows 使用。
export { CLUSTER_RUN, clusterize, markGrowingCluster, clusterOpen, clusterAutoOpen, toggleCluster, clusterTools, clusterRunning, clusterHasError, clusterProcCount, clusterKinds, clusterTitle, clusterHeadKind, clusterLiveText, clusterLiveRows, CLUSTER_KIND_DONE, CLUSTER_KIND_LIVE } from './processCluster.js'
import { clusterize, markGrowingCluster } from './processCluster.js'

/** 展开态的簇＝「一行一步」轻量清单（clusterLiveRows）：思考段与过程独白段不再各占一行标题，
 *  落进它之后第一个工具那一行的展开详情（那里本来就是「想了这些才动手」的那个位置）。
 *  末尾挂不出去的段（正在想、还没调工具）自成一节：它正是「当前这一步」，不能因为还没有工具就看不见。
 */

/** 这一组要不要出自己的组条（＝它是并行批次）。组条在不在，决定行尾那一项挂在组条上
 *  还是挂在首张卡上——判据只写这一处，两端模板共用。 */
// 分组条与行尾思考/独白口径在 ./notes.js（纯模块），这里只转出。
export { groupHasBar, noteText, noteTag, noteOpen, noteGrowing, toggleNote, notePeek, tailNotesTag, tailNotesPeek, tailNotesGrowing, tailOpen, toggleTail } from './notes.js'

// ==================== 「整轮用时」那一条（跑着在正文末尾，答完在回答顶上） ====================
// 一轮回答的耗时只报这一处：多处各挂一个数会一屏六个读数、互相打架（组条是首尾跨度、
// 单卡是各自跑掉多久）。整块过程收在这一条底下——跑的时候它跟在正文后面跟着秒走，
// 答完回到回答顶上收成一条总览，点开才回到交错时序。
/** 整轮用时（中文长格式）：35 分 19 秒 / 6 分 26 秒 / 40.5 秒。它现在独占一行标题，
 *  要的是读出量级，不是 toolDuration 那种给行尾小 chip 用的紧凑写法（226.0s 读不出是几分）。 */
/** 跑动中的秒表读 nowTick（模块内唯一的响应式状态），纯计算在 ./turnTiming.js。 */
const turnElapsedMs = m => elapsedAt(m, nowTick.value)
const turnTitle = m => titleAt(m, nowTick.value)

// 折叠/可见性判据在 ./turnFold.js（纯模块），这里只转出并按 nowTick 包装耗时。
export { turnOpen, toggleTurn, turnExpandable, turnWarn } from './turnFold.js'
import { turnOpen, toggleTurn, turnExpandable, turnWarn, turnShow as foldShow, turnToggleable as foldToggleable } from './turnFold.js'

// 终态标注与识别在 ./turnStatus.js（纯模块），这里只转出并供本模块内部使用。
export { TURN_STOP_SUFFIX, TURN_TRUNC_SUFFIX, TURN_BUDGET_SUFFIX, TURN_TIMEOUT_SUFFIX, turnMarker } from './turnStatus.js'
import { turnMarker } from './turnStatus.js'

const turnShow = m => foldShow(m, turnElapsedMs(m))
const turnToggleable = m => foldToggleable(m, turnElapsedMs(m))

/** 收尾只报用时，不报步数：步数数的是 toolCalls，展开出来的却是时间线那一份。引用自检改过正文时
 *  整条时间线作废（后端 timelineJson 返回 null），那句「5 步操作」点开会是一片空——一个兑不了现的数。 */
// 状态行标题在 ./turnSummary.js（纯模块），这里只按 nowTick 包装耗时。
import { turnHead as headOf, turnTop as topOf } from './turnSummary.js'
const turnHead = m => headOf(m, turnElapsedMs(m))
const turnTop = m => topOf(m, turnElapsedMs(m))

/** 悬停说明：口径只在这一处描述，PC 与 H5 共用（两处各写一遍必然写歪） */
// 工具段带下标 i：与后端落库口径一致（工具终态在 toolCalls 里的位置），
// 实时态另存对象引用（status 原地更新，卡片自动从转圈变完成）
// blocks → 现有视图字段的投影独立成纯模块（./blocks.js），这里只做转发，
// 保持 ChatPage / ShareChatPage 的既有 import 路径不变。
export { applyBlocks } from './blocks'

// ==================== 正文 / 过程分离（统一正文流） ====================
/* 正文＝整段 content；工具调用只作过程步骤，不切正文。
 * 模型会在工具调用处把一句话劈成两段（「我会先查一下最」→ 工具 → 「近 30 天…」），
 * 但工具不往 content 写字符，两半在 content 里本就紧邻，拼起来就是完整一句——
 * 不按轮次、工具边界或句末标点推断答案起点，答案永远是 content 的全部。
 * 过程＝思考/过程/工具段，按发生顺序聚簇渲染在过程块里，永远不插进正文。
 * 运行中与落库用同一份段数组（后端给每个非空 text 段标 channel=answer），刷新前后一致。 */

// 正文分段与插话位置在 ./turnBody.js（纯模块），这里只转出。
export { turnAnswerText, turnSteerRows, turnBodyRows } from './turnBody.js'

// 过程块投影在 ./turnProcess.js（纯模块），这里只转出并供折叠态使用。
export { turnProcessRows } from './turnProcess.js'
import { turnProcessRows } from './turnProcess.js'

// 工具摘要/实时输出在 ./toolBrief.js（纯模块），这里只转出。
export { TOOL_BRIEF_KEYS, oneLine, toolBrief, prettyIo, LIVE_TAIL_CHARS, liveOutput, stepBriefOf } from './toolBrief.js'

// ==================== 工具卡折叠：头部那颗开关到底管到哪些块 ====================
/* 普通卡：头部只管下面的入参/输出原文（默认收起，点开才见）。
 * 派遣卡（带 subChat）：一问一答就挂在头部正下方、且默认展开——它是这张卡的正文；
 * 若头部只管原文，长回答的卡点头部等于没反应（原文区远在回答末尾、多半在视口外），
 * 用户读成「展开/收缩坏了」，而且角标收着、底下却挂着一坨展开内容，开关在说谎。
 * 口径：派遣卡的折叠态默认算「开」（子对话可见），首次点击是收起；显式点开（true）那一份
 * 连入参/输出原文一起给——默认给排版好的正文，「展开」给全量，两层不打架。 */
// 工具卡开合与定位在 ./toolCard.js（纯模块），这里只转出。
export { toolCardOpen, toggleToolCard, openDispatchCard } from './toolCard.js'

/**
 * run_code 的扇出看板：实时帧与终态清单同一个形状（后端 buildFanOut 一处组），
 * 所以这几条判据只写一遍，PC 与移动壳的模板都走它们。
 * 字段：total 已完成条数、inFlight 在途条数、items 清单、omitted 未列出数、
 * used/max 本轮额度、parallel 并发上限、delivered 脚本交回的产物数。
 */
// 扇出看板/运行水位在 ./runBoard.js（纯模块），这里只转出。
export { fanHasBoard, fanHead, fanTitle, fanRowState, fanRun, runBoardText, fanBudget, groupRunning, groupHasError } from './runBoard.js'


// 工具终态合并在 ./toolCalls.js（纯模块），这里只转出。
export { mergeDoneToolCalls } from './toolCalls.js'

// 秒针与挂表生命周期在 ./chatTick.js（模块），这里只转出。
export { nowTick, ensureTick, alignTick, holdTick, stopTick } from './chatTick.js'
import { nowTick } from './chatTick.js'

// 检索词与状态行在 ./retrieval.js（纯模块），这里只转出。
export { toolSearchQueries, retrievalLineTitle } from './retrieval.js'

/** 深度思考按模型库登记的能力三态：none=不支持(隐藏) switchable=可开关 always=恒思考(锁定)；
 *  模型不在模型库（遗留裸名）按可开关处理。开关记忆按模型分开存（ai_deep_think: {ref:0|1}，
 *  __default 是所有模型的初始默认）。 */
// 归属徽标在 ./agentBadge.js（纯模块），这里只转出。
export { agentBadgeOf } from './agentBadge.js'

// 思考能力/档位在 ./thinking.js（纯模块），这里只转出。
export { THINK_CAPS, REASONING_LEVELS, THINK_LEVEL_ON, levelLabel } from './thinking.js'

/** K/M 口径窗口格式化：按整除自动选进制——2 的幂登记值走 1024 进制（131072→128K、1048576→1M），
 *  十进制登记值走 1000 进制（100000→100K、200000→200K），两边都不整除才落 1024 一位小数 */
// 窗口与用量口径在 ./usage.js（纯模块，可直接 Node 测试），这里只转出。
export { fmtWindow, usageCtxTokens, usageTip } from './usage.js'

/** 分组内核：入参 sources 数组，返回按 key 分好的组（外部来源 WEB/MCP 单独成组） */
// 引用来源的口径在 ./sources.js（纯模块，可直接 Node 测试），这里只转出。
export { groupSources, externalOrigin, sourceName, fmtSourceScore, scoreTitle } from './sources.js'

/** 长文本粘贴转成的附件名（不带扩展名部分；实际文件名补 .txt）。
 *  落库元信息只保留名称/类型/体积，所以刷新后靠这个前缀把卡片形态认回来。
 *  字面量定义在 ./intake.js（取材侧才是它的产地），本模块只借用（见顶部 import），
 *  并照旧由文件末尾的 export 块对外提供——同一个字符串写两处，改一处忘一处就会出现
 *  「粘贴卡片认不回来」而代码看起来完全正常 */
// 附件卡片口径在 ./attachments.js（纯模块，可直接 Node 测试），这里只转出。
export { isPastedText, pasteTitle, pasteSub } from './attachmentCard.js'

// 消息文本（历史标题/时间/错误文案）在 ./messageText.js（纯模块），这里只转出。
export { histItemTitle, histItemDigest, fmtMsgTime, errorBrief } from './messageText.js'

/** 「这一轮没答成」那几条降级 code（后端 emitRunFailure 同时走 degrade 与 error 帧两条通道）。
 *  数据一条不删、照旧落库照旧下发；这里只决定「同一件事在这一屏画几遍」。 */
// 降级/警示口径在 ./degradation.js（纯模块），这里只转出。
export { degradationItems, degradationWarn } from './degradation.js'

// ==================== 重新生成多版本（同一问题的多次回答可来回切换） ====================
// 语义：v1 是首次回答；每次「重新生成」把新完成的回答追加为新版本，气泡底部出现 ‹ 1/2 › 切换器。
// 版本只存在于当前会话内存里：历史接口按「一题一答」返回，重新生成时后端会把旧回答软删（见
// replaceMessageId），所以刷新后看到的是最后一版——想保留哪一版就切到哪一版再刷新是不成立的，
// 这一点在切换器上有提示，不做假承诺。
// 多版本快照/切换口径在 ./versions.js（纯模块），这里只转出。
export { snapshotVersion, applyVersion, verLocal, canSwitchPrev, canSwitchNext, verLabel } from './versions.js'

import {
  goalPreemptedOf, goalTerminalOf, goalBarVisibleOf, goalPhraseOf, goalStateLabelOf, goalStateClassOf, goalRoundLineOf, goalBlockReasonOf, goalBarTitleOf, goalBarMetaOf, goalCanPauseOf, goalCanResumeOf, goalCanEditOf, goalCanCancelOf, goalCanClearRecordOf, goalVersionLabelOf, goalProgressPercentOf, goalTerminalCardOf, goalTerminalTitleOf, goalTerminalNoteOf, goalOutcomeLineOf, goalEchoStateOf, goalEchoTipOf
} from './deepState.js'

// ==================== 交付包（产物清单的聚合口径） ====================
// 聚合逻辑在 ./delivery.js（纯模块，可直接 Node 测试）；这里只转出，消费方导入路径不变。
export {
  deliveryTagOf, deliveryEntryOf, deliverySubjectOf, deliveryOf, deliveryCardOf
} from './delivery.js'

export { artifactPendingOf } from './artifactPending'


export {
  toolCallsView,
  turnHead, turnShow, turnToggleable, turnTitle, turnElapsedMs, turnWaitMs, turnPrepMs,
  resumedStartAt, earlierStart,
  turnTop,
  fmtSize,
  PASTE_TEXT_BASE,
  goalTerminalOf, goalBarVisibleOf, goalPhraseOf, goalStateLabelOf, goalStateClassOf,
  goalBlockReasonOf, goalBarTitleOf, goalBarMetaOf, goalRoundLineOf,
  goalCanPauseOf, goalCanResumeOf, goalCanEditOf, goalCanCancelOf, goalCanClearRecordOf,
  goalPreemptedOf, goalVersionLabelOf, goalProgressPercentOf,
  goalTerminalCardOf, goalTerminalTitleOf, goalTerminalNoteOf,
  goalOutcomeLineOf, goalEchoStateOf, goalEchoTipOf
}
