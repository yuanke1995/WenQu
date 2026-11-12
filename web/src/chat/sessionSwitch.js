// 会话切换编排（actions/编排层）：切到指定会话、按需补拉会话级数据、加载历史并接回在跑的轮、
// 恢复各类待办卡、同步地址栏；失败时区分「拉取失败」与「归属/已删除」两类回退。
// 所有引擎状态与副作用经注入，模块自身不读 store/router。

/**
 * @param ctx.currentSid () => string
 * @param ctx.setCurrentSid sid => void
 * @param ctx.settleSteer 离开上一会话时结算插话队列
 * @param ctx.dropGoalPlaceholderIfOther sid => void（换到别的会话才作废占位气泡）
 * @param ctx.goalPlaceholderFor sid => 占位气泡或 null
 * @param ctx.modelMap ref（会话 → 模型引用）
 * @param ctx.getSessionModel sid => Promise
 * @param ctx.todosMap ref（会话 → 任务清单）
 * @param ctx.getSessionTodos sid => Promise
 * @param ctx.setTodos (sid, todos) => void
 * @param ctx.goalsMap ref（会话 → 目标）
 * @param ctx.loadGoalFor sid => Promise
 * @param ctx.setGoal (sid, doc) => void
 * @param ctx.syncUrl sid => void（保留兄弟 query 参数）
 * @param ctx.deepApprovalOf () => string（当前深链点名的审批 id）
 * @param ctx.getHistory (sid, opts) => Promise
 * @param ctx.buildList (rows, sid) => 视图消息[]
 * @param ctx.historyError ref
 * @param ctx.chatStreams Map（会话 → 在跑的流）
 * @param ctx.messages ref
 * @param ctx.linkPlanLineage list => void
 * @param ctx.ensureTurnTodos (sid, list) => void
 * @param ctx.refreshArtifactSigns () => void
 * @param ctx.attachRunningTurn (sid) => Promise
 * @param ctx.hydrateAsk sid => void
 * @param ctx.hydratePlans sid => void
 * @param ctx.hydrateApprovals (sid, deepId) => void
 * @param ctx.handleDeepLink (sid, id) => void
 * @param ctx.scrollForce () => void
 * @param ctx.loadSessions () => Promise
 * @param ctx.autoPick () => Promise
 */
export const createSessionSwitch = (ctx) => {
  // 同一 sid 只允许一个 getHistory 在飞，重复调用直接复用它的结果。
  // 为什么需要：刷新落地时会连着调两次同一个 sid——一次来自首屏/地址栏，一次来自
  // finishResumedShell（接流气泡收场后按历史重显）。两次都 await getHistory，谁先返回谁写
  // messages.value，晚返回的那份就会用**旧快照**覆盖掉已经接回视图的 live 气泡，
  // 症状是「正在回答时刷一下，正在跑的那一条连同它上面的提问一起没了」。
  const historyLoads = new Map()
  let recovering = false

  const switchSession = async sid => {
    // 真正换会话先结算上一会话的插话队列：队列是模块级单例，不清会以「上一会话那轮的话」
    // 挂在新区块的输入框上；同会话重铺（计划/提问卡 restored 后的重拉）不清
    if (ctx.currentSid() !== sid) ctx.settleSteer()
    // 不再被"正在回答"拦截：流式回调改写的是 chatStreams 里的消息对象，切走不影响后台流
    ctx.setCurrentSid(sid)
    // 换到别的会话：上一会话那颗占位气泡随旧数组一起作废（留着会在错误的气泡上撤/交接）。
    // 同一会话的重铺不清——清了那一行的秒表就从头跳一遍
    ctx.dropGoalPlaceholderIfOther(sid)
    // 该会话已存的模型覆盖：列表已加载的会话由 sessionStore.list 的 watch 回填；
    // 列表外的会话（通知/分享深链进老会话，或侧栏尚未翻到）在这里按需拉一次——
    // 否则选择器会显示个人默认，而后端仍按会话已存模型作答，界面与真实不符
    if (sid && !(sid in ctx.modelMap.value)) {
      ctx.getSessionModel(sid).then(r => {
        // 迟到的响应不覆盖界面上已改的选择（用户在这期间又切了模型）
        if (r && r.success && !(sid in ctx.modelMap.value)) {
          ctx.modelMap.value = { ...ctx.modelMap.value, [sid]: r.data?.model || '' }
        }
      }).catch(() => {})
    }
    // 任务清单：该会话没拉过才捞一次（输入框上方那条清单据此回显；正在跑的那轮由 todo 事件实时覆盖）
    if (sid && !(sid in ctx.todosMap.value)) {
      ctx.getSessionTodos(sid).then(r => {
        if (r && r.success && r.data?.todos && !(sid in ctx.todosMap.value)) ctx.setTodos(sid, r.data.todos)
      }).catch(() => {})
    }
    // 目标模式：该会话没拉过才捞一次（摘要条、右栏「目标」面板与目标卡共用这一份）。
    // 拉不到必须让界面知道——空态掩盖失败会让「设了目标但面板什么都没有」看起来像没设过
    if (sid && !(sid in ctx.goalsMap.value)) {
      ctx.loadGoalFor(sid).then(doc => {
        if (ctx.currentSid() !== sid || (sid in ctx.goalsMap.value)) return
        ctx.setGoal(sid, doc)
      }).catch(() => {})
    }
    // 同步 URL query：侧边栏高亮与刷新恢复都依赖 sid 在地址上。必须保留兄弟参数：
    // ?approval=<id>（工具审批通知深链）直接写 query:{sid} 会被抹掉
    ctx.syncUrl(sid)
    // 同一会话已在加载：复用它的结果，别再发一次请求、也别让两份快照互相覆盖
    if (historyLoads.has(sid)) return historyLoads.get(sid)
    ctx.historyError.value = ''
    const task = (async () => {
      try {
        // 403（会话归属拒绝）由本函数静默回退处理，不触发全局提示
        const r = await ctx.getHistory(sid, { silentForbidden: true })
        // 快速连续切换时晚到的历史响应不覆盖当前视图
        if (r.success && Array.isArray(r.data) && ctx.currentSid() === sid) {
          const list = ctx.buildList(r.data, sid)
          // 该会话正在流式回答：把 live 消息接回视图尾部。流式中的这轮前后端不落库助手消息，
          // getHistory 里没有这条；用户消息在轮开始时已即时落库，顺序正好衔接。
          const st = ctx.chatStreams.get(sid)
          if (st && st.msg.loading) list.push(st.msg)
          // 目标排队那颗占位气泡原样带回去：同一会话的历史重铺会把整表换掉，
          // 换掉就等于把那一行撤了再挂一颗新的——秒表从 0 重跳，用户读成计时坏了
          const ph = ctx.goalPlaceholderFor(sid)
          if (!st && ph && !list.includes(ph)) list.push(ph)
          // 插话在库里同时是两样东西：一条独立用户消息＋当轮时间线里的一段。界面只认后面那个，
          // 这条用户行就此退场——一句不许显示两遍。没被任何时间线用掉的那些照旧显示。
          ctx.messages.value = ctx.dropSteered(list)
          // 计划血缘与历史轮次进度：两张卡互相指认 + 按 turnId 回捞旧轮清单
          ctx.linkPlanLineage(ctx.messages.value)
          ctx.ensureTurnTodos(sid, ctx.messages.value)
          // 取到了就清掉失败标记——哪怕这一会话真的空着，那也是「查过确实没有」，不是失败
          ctx.historyError.value = ''
          // 产物换发新鲜签名（fire-and-forget）：历史接口给的 URL 签好就不变，1 小时后过期即 401
          ctx.refreshArtifactSigns()
          // 断线重连接流：这个会话若还有一轮在跑，把它接回来。必须先探再恢复卡片——
          // 待作答的提问卡要挂在接回来的气泡上，否则同一轮会出现两颗进行中气泡
          if (!st) await ctx.attachRunningTurn(sid)
          const live = ctx.chatStreams.get(sid)
          // 提问卡恢复（fire-and-forget）：等待作答期间断线不再中止本轮
          if (!live || !live.msg.ask) ctx.hydrateAsk(sid)
          // 计划批准卡恢复（同上）：计划等待期间断线不中止本轮
          if (!live || !live.msg.planCard) ctx.hydratePlans(sid)
          // 工具审批卡恢复：接流形态下这是第四张卡。深链带了 approval 时只挂它点名的那一张
          const deepApproval = ctx.deepApprovalOf()
          if (!live || !(live.msg.approvals || []).length || deepApproval) ctx.hydrateApprovals(sid, deepApproval)
          // 通知深链点名的审批：落到对话里那张卡上并闪一圈。放在历史与接流之后才能对上一轮
          if (deepApproval) void ctx.handleDeepLink(sid, deepApproval)
          // 先按新列表重算尾随留白、等它落屏再贴底：落点是本轮问题置顶
          ctx.scrollForce()
        } else if (ctx.currentSid() === sid) {
          ctx.messages.value = []
          // 接口回成功但结构不对（r.success=false 且不是 403/404）：这也是「没拿到」，不许当成「没有」
          ctx.historyError.value = r?.msg || '这个会话的历史没取回来'
        }
      } catch (e) {
        if (ctx.currentSid() === sid) ctx.messages.value = []
        // 「拉取失败」与「这个会话确实是空的」必须是两个判据：空态不许掩盖失败，
        // 断网/500/超时要露错误卡，而不是摊开一张欢迎页让人读成「我聊过的东西没了」。
        // 403/404 不算失败：那是「这条会话不归你/已删除」，走下面那条静默回退。
        if (ctx.currentSid() === sid && e?.status !== 403 && e?.status !== 404) {
          ctx.historyError.value = e?.message || '历史加载失败，请重试'
        }
        // 非本人/已删除的会话（403/404）：静默清掉地址栏 sid，回退到自己的最近会话或新建
        // （autoPick 与首屏落地同一逻辑）；先刷新列表，避免用陈旧列表再选中已失效会话
        if (!recovering && (e?.status === 403 || e?.status === 404)
            && String(ctx.urlSid()) === String(sid)) {
          recovering = true
          ctx.setCurrentSid(null)
          ctx.replaceHome()
          try {
            await ctx.loadSessions()
            await ctx.autoPick()
          } catch (err) {
            /* 回退失败则停在空白会话，不再打扰 */
          } finally {
            recovering = false
          }
        }
      } finally {
        // 本次在途加载结束：清掉登记，让下一次 switchSession 能正常重新拉历史
        historyLoads.delete(sid)
      }
    })()
    historyLoads.set(sid, task)
    return task
  }
  return { switchSession, historyLoads }
}
