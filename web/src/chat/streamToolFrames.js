// 工具状态帧（纯函数 + 注入）：start/progress/终态的卡片合并与实时输出累积。
// 卡片对象挂在 msg.toolCalls 上，时间线写入口与秒表由调用侧注入。

/**
 * @param msg 本轮回答消息
 * @param raw 帧载荷（字符串或对象）
 * @param ctx.thinkRoundEnd () => void 断思考段（工具事件是「这一段想完了」的信号）
 * @param ctx.ensureTick 秒表保活
 * @param ctx.liveScroll 跟随滚动
 * @param ctx.isDispatchTool 派遣工具判定（本地起表点）
 * @param ctx.pushTimelineTool (msg, card) => void 新卡进时间线
 * @param ctx.retireAskCard 回答终态时撤掉提问卡
 */
export const applyToolStatus = (msg, raw, ctx) => {
  try {
    const t = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!t || !t.name) return
    // 工具事件也是「这一段思考想完了」的信号：后端在 tool/requested 前会补收尾帧，
    // 这里再断一次是防漏（漏断会把下一轮思考接在同一行后面，正是用户要摆脱的叠加）
    ctx.thinkRoundEnd()
    if (!Array.isArray(msg.toolCalls)) msg.toolCalls = []
    if (t.status === 'start') {
      // askUser 的「进行中」由提问卡（m.ask）表达，不渲染通用工具卡——否则一问两卡
      if (t.name === 'askUser') return
      if (t.preparing) {
        // 早产卡：模型还在生成入参（后端从 model/chunk 的 tool_use 增量提前下发），
        // 先把「这一步在跑」亮出来。进度帧就地更新同一张卡，正式 tool/requested 到达时升级，不推第二张。
        const prep = [...msg.toolCalls].reverse().find(x => x.name === t.name && x.status === 'start' && x.preparing)
        if (prep) {
          prep.chars = Number(t.chars) || 0
          if (t.brief) prep.brief = t.brief
          if (t.label) prep.label = t.label
          if (t.hint) prep.hint = t.hint
          if (t.startedAtMs) prep.startedAtMs = t.startedAtMs
        } else {
          const card = { ...t, preparing: true }
          msg.toolCalls.push(card)
          ctx.pushTimelineTool(msg, card)
        }
        ctx.ensureTick()
        ctx.liveScroll()
        return
      }
      // 正式卡：同名早产卡原地升级（补入参、摘掉进度字段）——否则一次调用会渲染成两张，
      // 一张停在「生成中」永远转圈
      const prep = [...msg.toolCalls].reverse().find(x => x.name === t.name && x.status === 'start' && x.preparing)
      if (prep) {
        prep.preparing = false
        delete prep.chars
        delete prep.brief
        if (t.args) prep.args = t.args
        if (t.startedAtMs) prep.startedAtMs = t.startedAtMs
        if (t.label) prep.label = t.label
        if (t.hint) prep.hint = t.hint
        if (t.stepId != null) prep.stepId = t.stepId
        ctx.liveScroll()
        return
      }
      // 幂等兜底：同一次调用可能被快照与增量各给一遍（后端按事件序号过滤，这里再守一道）。
      // 重复的 start 会多出一颗转圈卡片，要等 done 才被终态盖掉——刷新那一刻最容易撞见
      if (msg.toolCalls.some(x => x.status === 'start' && x.name === t.name && (x.args || '') === (t.args || ''))) return
      const card = { ...t }
      // 派遣那一张另挂一个**本地**起表点，供顶栏那颗药丸跳秒：服务端随卡带的 startedAtMs 是
      // 服务端墙上时刻，拿它减本地秒表会把两边的钟偏差算进用户眼里。刷新接上来的那一支走的是
      // 快照那条路（整份 toolCalls 被换掉），拿不到这个锚点 ⇒ 药丸上只写「在跑」不写秒数。
      if (ctx.isDispatchTool(t.name)) card._liveStart = Date.now()
      msg.toolCalls.push(card)
      ctx.pushTimelineTool(msg, card)
      // 用时不在卡片上报，一轮只气泡顶部那一条说一个数
      ctx.ensureTick()
    } else {
      // askUser 已出终态（用户已答，或忽略后按「未作答」收尾）：问答记录卡接管展示，撤掉提问卡。
      // askContinued 例外：前台等待到点、按 pending 送回了模型，这张卡仍可答——撤了就等于把超时当成用户跳过
      if (t.name === 'askUser' && msg.ask) {
        if (t.askContinued) { msg.ask.continued = true; msg.ask.deadline = 0 } else ctx.retireAskCard(msg)
      }
      if (t.name === 'run_code') msg.runBoard = null
      const list = msg.toolCalls
      // 终态帧认领 start 卡要连同入参一起对：并发派遣时三位助手三张同名卡，只按名字认
      // 会把甲那一份的耗时/入参/子对话盖到乙那张上（卡片换了主人、药丸跟着数错）。
      // 后端有 toolCallId 那把尺，但它没往 SSE 帧里带；args 是这一帧自己带的那一份，够用。
      const sameArgs = x => !x.args || !t.args || String(x.args) === String(t.args)
      const last = [...list].reverse().find(x => x.name === t.name && x.status === 'start' && sameArgs(x))
      if (last) {
        last.status = t.status
        last.elapsedMs = t.elapsedMs || 0
        // 起点以服务端那份为准：跨度是在同一个钟上减的，混进前端收到帧的时刻就会被时钟差与网络排队带歪
        // 等待账同理：漏了它，簇头就会把用户读卡答题那几分钟也算进机器时间
        if (t.startedAtMs) last.startedAtMs = t.startedAtMs
        if (t.waitedMs) last.waitedMs = t.waitedMs
        if (t.args) last.args = t.args
        if (t.result != null) last.result = t.result
        if (t.error) last.error = t.error
        // 咨询的「子对话」只存在于终态那一份（start 那会儿还没有回答）：这里逐字段挑着合并，
        // 漏一行就是实时看不到、刷新才冒出来——两条路必须同构
        if (t.subChat) last.subChat = t.subChat
      } else {
        const card = { ...t }
        list.push(card)
        ctx.pushTimelineTool(msg, card)
      }
    }
    ctx.liveScroll()
  } catch (e) { /* 忽略 */ }
}

/** 工具实时输出（本地累积，与后端落库的 result/output 不同名）：超过上限保留尾部 */
export const appendToolOutput = (msg, raw) => {
  try {
    const o = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!o || !o.delta) return
    const list = msg.toolCalls
    if (!Array.isArray(list)) return
    const live = [...list].reverse().find(x => x.name === o.name && x.status === 'start')
    if (!live) return
    const MAX = 65536
    // 字段名带 live 前缀：这是**前端本地**的增量累积，与后端落库的 result/output 是两回事。
    // 不带前缀会与后端字段撞名，读侧分不清「本地累积」与「历史字段」。
    let out = (live.liveOutput || '') + o.delta
    if (out.length > MAX) { out = out.slice(out.length - MAX); live.outputTruncated = true }
    live.liveOutput = out
  } catch (e) { /* 忽略 */ }
}
