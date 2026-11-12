// 历史回显的行映射（纯函数）：后端消息行 → 视图消息。
// 计划卡重建、时间线恢复、显示块投影与思考分段补写都经依赖注入，模块自身不读任何响应式状态。

/** 插话在库里同时是一条用户消息和回答时间线里的一段：时间线已经用掉的那个 messageId，
 *  对应的独立用户行必须退场，否则新页签会看到“用户气泡 + 回答内插话”两份。
 *  历史回显与接流快照共用这一条判据。 */
export const dropSteeredUserRows = list => {
  const ids = new Set()
  for (const m of list || []) {
    if (!m || m.role !== 'ai') continue
    for (const seg of (m.timeline || [])) {
      if (seg && seg.kind === 'steer' && seg.messageId) ids.add(String(seg.messageId))
    }
  }
  if (!ids.size) return list
  return list.filter(m => !(m.role === 'user' && m.messageId && ids.has(String(m.messageId))))
}

/**
 * @param rows 后端历史行
 * @param sid 会话 ID（计划卡重建需要）
 * @param deps { planCardFromRecord, restoreTimeline, applyBlocks, ensureThinkSegment }
 */
export const buildHistoryList = (rows, sid, { planCardFromRecord, restoreTimeline, applyBlocks, ensureThinkSegment }) => {
  const list = (rows || [])
    // 带 plan 快照的行即使正文为空也要留：被「继续对话」取代的那轮正文就是空的（状态只写在
    // 计划卡角标上），只按正文过滤会让整张计划卡随刷新一起蒸发
    .filter(m => m && (m.content || (Array.isArray(m.images) && m.images.length) || m.plan))
    .map(m => {
      const msg = {
        role: m.role === 'user' ? 'user' : 'ai',
        content: String(m.content || ''),
        messageId: m.messageId || m.id || null,
        // 本轮身份（随消息落库）：气泡「过程」按它取这一轮的事件账本；存量消息无此字段则无入口
        turnId: typeof m.turnId === 'string' ? m.turnId : '',
        fb: (m.fb === 0 || m.fb === 1) ? m.fb : null,
        images: Array.isArray(m.images) ? m.images : [],
        attachments: Array.isArray(m.attachments) ? m.attachments : [],
        // 附件重发凭据：落库的 attachments 现在带 fileId，据此还原 attachData——刷新或切走会话再回来
        // 才还能把原附件带走（不带它，「这一端没拿到号」会被界面说成「文件已失效」，而文件其实还在盘上）。
        // 存量行没这个字段 → 空数组，按带不走处理，不假装能重发。
        attachData: (Array.isArray(m.attachments) ? m.attachments : [])
          .filter(a => a && a.fileId).map(a => ({ name: a.name, mime: a.mime, fileId: a.fileId })),
        sources: Array.isArray(m.sources) ? m.sources : [],
        // 「接下来可以」推荐（随消息落库后回显）：刷新后整块还在
        related: Array.isArray(m.related) ? m.related : [],
        // 本轮降级事件（随消息落库后回显）：刷新后降级条整块还在
        degradations: Array.isArray(m.degradations) ? m.degradations : [],
        // 本轮警示条（同上）：沙盒基建异常这类当场看得见的异常，刷新后必须还在
        warnMsg: typeof m.warnMsg === 'string' ? m.warnMsg : '',
        // @ 引用（随用户消息常驻落库）：刷新/历史回显保留引用标注，重新生成/编辑重发据此原样带出
        mentions: Array.isArray(m.mentions) ? m.mentions : [],
        // # 历史引用（同上）
        historyRefs: Array.isArray(m.historyRefs) ? m.historyRefs : [],
        // 本轮终止原因（叫停且该轮没产出回答时记在用户提问上）：刷新后据此在问题下补一行说明
        endReason: m.endReason || '',
        // 本轮失败原因（挂在回答上，什么都没产出时挂在那一问上）：随消息落库，刷新后仍读得出
        // 「这一轮断过」，否则用户面对的是一轮凭空消失的回答
        failMsg: typeof m.failMsg === 'string' ? m.failMsg : '',
        // 目标指令回声（记在用户提问上）：气泡挂 /goal 命令标注、不挂普通消息动作
        goalCommand: typeof m.goalCommand === 'string' ? m.goalCommand : '',
        thinking: m.thinking || '',
        time: m.createTime ? new Date(m.createTime).getTime() : null,
        artifacts: Array.isArray(m.artifacts) ? m.artifacts : [],
        toolCalls: Array.isArray(m.toolCalls) ? m.toolCalls : [],
        // 计划批准卡（随助手消息落库）：历史按轮重建气泡里的计划卡（已批准/未批准 + 末版正文 + 修改记录），
        // 有多少轮计划就回显多少张，刷新/切会话后仍在
        planCard: (m.plan && typeof m.plan === 'object' && m.plan.plan) ? planCardFromRecord(m.plan, undefined, sid) : null,
        // 过程独白全文（随消息落库）：历史回显时间线 process 段的区间数据源（旧消息无此字段则为空）
        processText: typeof m.processText === 'string' ? m.processText : '',
        // Token 用量（随消息落库）：历史会话的「本次用量/会话累计」回看数据源（旧消息无此字段则为 null）
        tokens: (m.tokens && typeof m.tokens === 'object') ? m.tokens : null,
        // 回答归属（随消息落库的当轮智能体快照）：历史回显「这条是谁答的」，旧消息无此字段则为空
        agentId: typeof m.agentId === 'string' ? m.agentId : '',
        agentName: typeof m.agentName === 'string' ? m.agentName : '',
        // 本轮生效模型引用（随助手消息落库）：「模型已切换」分隔记录的比对数据源，旧消息无此字段则为空
        model: typeof m.model === 'string' ? m.model : '',
        // 分支版本（编辑重发/重新生成的持久化多版本）：‹ n/N › 切换器的数据源，无版本的消息为 null
        variantCount: m.variantCount || null,
        variantIndex: m.variantIndex || null,
        // 每轮文件改动（随助手消息落库）：改动卡刷新后仍在的数据源，旧消息无此字段则为 null
        changes: (m.changes && typeof m.changes === 'object') ? m.changes : null,
        retrieved: (() => { try { return m.retrieved ? JSON.parse(m.retrieved) : null } catch (e) { return null } })(),
        // 回答时间线（随消息落库）：重建后刷新页仍是「正文—工具—产物」交错的过程视图
        timeline: [],
        // 显示块投影（新消息才有）：答案/过程/工具/产物的权威顺序与归属，优先于 timeline
        blocks: (m.blocks && typeof m.blocks === 'object') ? m.blocks : null
      }
      if (msg.blocks) {
        applyBlocks(msg, msg.blocks)
      } else if (Array.isArray(m.timeline)) {
        msg.timeline = restoreTimeline(msg, m.timeline)
      }
      // 失败轮的错误卡按库里那句重建：实时那颗长在同一行上（有回答挂回答、零产出挂那一问），
      // kind 记 'spoken'——它是服务端说过的那句业务错误，原样显示，不再按文本猜一遍
      if (msg.failMsg) msg.errorCard = { message: msg.failMsg, kind: 'spoken', at: msg.time || 0 }
      // 存量消息没落思考分段：补一段在头部，别让它从界面上消失
      ensureThinkSegment(msg)
      return msg
    })
  return list
}
