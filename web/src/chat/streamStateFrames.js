// 状态小帧（纯函数 + 注入）：轮内压缩状态、执行计划清单、目标态与跨轮续跑。
// 都是「整份覆盖」语义；帧异常保留上一份，不清空界面。

export const applyCompactState = (msg, raw, { sid, autoCompactSid, liveCompactedSid, onScroll = () => {} }) => {
  // 轮内自动压缩（框架在推理前把早期对话折进摘要）：那两次摘要模型调用的字全被按 purpose 滤掉，
  // 不带这一帧就是二十多秒纯静默，用户只能读成「卡住了」。
  // 进度说在气泡那条本来就在走的状态行上，不另起一行：一轮里框架可以压好几次。
  try {
    const p = typeof raw === 'string' ? JSON.parse(raw) : (raw || {})
    if (p.phase === 'start') {
      autoCompactSid.value = sid
      msg.compacting = true
    } else {
      autoCompactSid.value = null
      msg.compacting = false
      // 压完了当场就把留痕补上，不等回答落库：否则这条线在输出流开始那一刻撤走、
      // 整轮答完才回来，中间几分钟界面上像「刚才那下什么都没压」
      if (p.status === 'completed') liveCompactedSid.value = sid
    }
    onScroll()
  } catch (e) { /* 帧形状变了就什么也不做：这一行的退场由 loading 兜底，不会挂死 */ }
}

export const applyPlanFrame = (msg, raw) => {
  // 本轮执行计划（后端按配置确定会跑的步骤）：右栏清单逐项点亮的数据源；仅实时，历史轮无此字段
  try {
    const arr = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (Array.isArray(arr) && arr.length) msg.plan = arr
  } catch (e) { /* 忽略 */ }
}

export const applyGoalState = (sid, raw, setGoal) => {
  // 目标态唯一帧（阶段迁移/轮次推进/停因）：整份视图模型覆盖写。
  // 帧是权威：本地乐观态（busy）之外的一切都按这一份对齐
  try {
    const j = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (j && j.goalId) setGoal(sid, { ...j })
  } catch (e) { /* 载荷异常：保留上一份，不清空面板 */ }
}

export const applyGoalTurn = (raw, { currentSid, sid, onNewTurn = () => {} }) => {
  // 跨轮续跑开了新的一轮（后台轮，没有浏览器通道）：自动探测 + 接流，
  // 用户留在页面上就能看见它自己往下跑（不接的话那一轮只在账本里）
  try {
    const j = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (j && j.goalId && currentSid() === sid) onNewTurn()
  } catch (e) { /* 忽略 */ }
}
