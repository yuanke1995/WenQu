// done 收尾（纯函数 + 注入）：把一整轮回答定格——早产卡/提问卡/审批卡收场、空正文收束语、
// 用量与来源回填、模型降级同步、在跑登记摘除、侧栏归位、多版本追加。
// payload 的解析与合并（plain fields）仍在调用侧，这里只收「收尾动作」。
import { snapshotVersion } from './versions.js'

/**
 * @param msg 本轮回答消息
 * @param ctx.st 本轮流记录（serverStopped/surrendered）
 * @param ctx.sources/related/messageId/degradations done 载荷解析结果
 * @param ctx.prev 自动重试来源（非空表示这是重试，不追加新版本）
 * @param ctx.isFirstMessage 本轮是否是会话首条消息（侧栏需要重拉列表）
 * @param ctx.settlePreparingCards 早产工具卡收场
 * @param ctx.markStoppedTurn 补「已停止本轮」尾注
 * @param ctx.onModelFallback 后端替用户清掉失效模型时同步本地选择
 * @param ctx.dropStream 从在跑登记摘掉本轮（同一条流才摘）
 * @param ctx.scroll 收尾贴底/保位
 * @param ctx.touchRow 侧栏会话行归位
 * @param ctx.reloadSessions 重拉会话列表
 */
export const closeoutDoneTurn = (msg, ctx) => {
  // 早产卡收口：本轮结束仍没等到正式 tool/requested 的那张（入参没生成完/被叫停）——标成未执行，
  // 别让答完之后还挂着一颗永久转圈、且写着「生成中」的行
  ctx.settlePreparingCards(msg, '本轮已结束，这一步没有执行')
  msg.loading = false
  // 叫停那一轮：服务端确认停掉后把「⏹ 已停止本轮」补进正文（与落库那份同形）。
  // abort 可能先于 /chat/stop 的响应收尾，两边都调一次、由 endsWith 去重
  if (ctx.st.serverStopped) {
    msg.stoppedTurn = true
    ctx.markStoppedTurn(msg)
  }
  // 空正文那一支的收束语排在补尾注<b>之后</b>，两者只出一个：排在之前会让尾注那句的「正文为空
  // 就不补」判据被自己刚填的那句骗过，界面上「（已停止生成）」与「已停止本轮」并排。
  // 计划被拒/超时、被「继续对话」取代那两支都不写：卡片角标已经说了同一件事。
  if (msg.content === '' && !msg.planStopped && !msg.planSuperseded && !ctx.st.surrendered) {
    msg.content = ctx.stopPlaceholder
  }
  // 整轮已收口：提问卡（若还在）撤掉——超时/忽略走的是 done 前的 tool_status 终态，此处兜底
  // 审批卡同理：超时后后端按拒绝继续，这张卡不该在答案出来以后还挂着
  msg.ask = null
  msg.askQueue = []
  msg.approvals = []
  // 整轮结束时刻（右栏「生成回答」行的 duration 用它）；历史恢复的消息无此值则不显示
  msg.doneTime = Date.now()
  // 生成完成：编排卡片收起为一行（用户未手动干预时），避免答案出来后还占着版面
  msg.sources = ctx.sources
  if (msg.retrieved && Array.isArray(ctx.sources)) msg.retrieved.refs = ctx.sources.length
  msg.related = ctx.related
  msg.messageId = ctx.messageId
  msg.degradations = ctx.degradations
  // 后端替用户改过模型选择（会话里钉着的那个已停用，已清除并改用他的默认模型）：
  // 本地内存里那份同样作废，否则选择器还挂着已停用的模型
  if (ctx.degradations.some(d => d && d.code === 'modelFallback')) ctx.onModelFallback()
  // 记录仍指向本轮才清（防止误删同会话新一轮的记录）
  ctx.dropStream()
  // 收尾同样走 scroll：跟随中的会话落到最新，用户已上翻看历史的会话原地不动
  ctx.scroll()
  // 侧栏归位：服务端每轮都把会话的 update_time 推到当下，列表又按它倒序，本地这一行要跟着回段首。
  // 只定点改这一行、不整表重拉；首条消息除外——它刚从「隐藏的空会话」变成有消息的行。
  ctx.touchRow()
  if (ctx.isFirstMessage) ctx.reloadSessions()
  // 重新生成：把刚完成的这一版追加进版本序列并切到它（气泡底部出现 ‹ n/N › 切换器）。
  // 自动重试（prev 非空）不追加：那是同一版本的重试，不是新版本——否则失败重试一次就多出一版
  if (!ctx.prev && Array.isArray(msg.versions) && msg.versions.length) {
    msg.versions.push(snapshotVersion(msg))
    msg.vIndex = msg.versions.length - 1
  }
}
