// 低频信息帧（纯函数 + 注入）：任务清单、用量预览、检索、技能命中、审批卡、脚本并发进度、
// 会话内 @ 智能体的归属改写。这些帧都只做「整份覆盖/去重累积」，不改流式正文缓冲。

export const applyTodoFrame = (sid, raw, setTodos) => {
  // 任务清单整表快照（writeTodo 每次传完整清单）：按会话存，切走再回来仍是最后那份
  try {
    const j = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (j && Array.isArray(j.items)) setTodos(sid, j)
  } catch (e) { /* 载荷异常：保留上一份，不清空界面 */ }
}

export const applyUsageFrame = (msg, raw) => {
  // 用量逐帧下发（usage 事件，第一次模型回包后到、本轮每落地一步再发一份）：prompt 侧占用先行
  // 点亮容量圆环/明细卡；done 下发的实测 tokens 仍是终值（落库/累计/输出侧都只认它），此处只挂
  // 流式中的预览，后到的覆盖先到的
  try {
    const j = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (j && typeof j === 'object') msg.tokensPreview = j
  } catch (e) { /* 忽略 */ }
}

export const applyRetrievedFrame = (msg, raw, onScroll = () => {}) => {
  try {
    const j = JSON.parse(raw)
    msg.retrieved = { keywords: j.keywords || 0, refs: j.refs || 0, terms: j.terms || [] }
    onScroll()
  } catch (e) { /* 忽略 */ }
}

export const applySkillHitFrame = (msg, raw, onScroll = () => {}) => {
  // 本轮命中一条技能（skill_hit 帧，content={name,source}）：picked=用户点名、正文已随本轮注入，
  // model=模型按需读取了正文。按 name 去重累积在消息上，回答的过程区渲染芯片；
  // 同轮先读到、后被点名时升级成 picked 文案（点名是更强的事实，不许被后到的 model 帧盖回去）。
  // 接流重复帧由 api.js 的 seq 水位守卫收口，这里只管合并。
  try {
    const j = typeof raw === 'string' ? JSON.parse(raw) : raw
    const name = String((j && j.name) || '').trim()
    if (!name) return
    if (!Array.isArray(msg.skillHits)) msg.skillHits = []
    const hit = msg.skillHits.find(x => x && x.name === name)
    if (hit) { if (j.source === 'picked') hit.source = 'picked' }
    else msg.skillHits.push({ name, source: j.source === 'picked' ? 'picked' : 'model' })
    onScroll()
  } catch (e) { /* 忽略 */ }
}

export const applyApprovalCardFrame = (msg, raw, onScroll = () => {}) => {
  // 工具执行审批（人在回路）：卡片挂到当前 AI 气泡，批准/拒绝后模型继续走。
  // 多卡：父轮自己的审批与子助手分支上翻的审批可能同时挂起，按 id 去重、同一气泡内堆叠。
  // deadline 让卡片自己走真实倒计时（timeoutMs 是事件下发那一刻的整窗，不能当剩余秒数用）
  try {
    const j = typeof raw === 'string' ? JSON.parse(raw) : raw
    const timeoutMs = Number(j.timeoutMs) || 120000
    const card = { id: j.approvalId, tool: j.tool, label: j.label, hint: j.hint, args: j.args,
      subagent: j.subagent || null, timeoutMs, deadline: Date.now() + timeoutMs, busy: false }
    const list = (Array.isArray(msg.approvals) ? msg.approvals : []).filter(x => x && x.id !== card.id)
    list.push(card)
    msg.approvals = list
    onScroll()
  } catch (e) { /* 忽略 */ }
}

export const applyRunCodeProgress = (msg, raw) => {
  // 脚本内的并发与额度（实时面板）：进度帧挂到"正在跑"的那张 run_code 卡上。
  // 与 tool_output 同一条规矩——接流回来的观众手里没有这张卡（start 记录不入快照），
  // 帧就丢弃，跑完后终态卡片自己带完整清单，不凭空造一张卡。
  try {
    const p = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!p || !p.fanOut) return
    const list = msg.toolCalls
    if (!Array.isArray(list)) return
    const live = [...list].reverse().find(x => x.name === 'run_code' && x.status === 'start')
    if (!live) return
    live.fanOut = p.fanOut
    // 同一份数据在消息上留一份（总览用）：一段脚本跑完由终态清空，免得过程区一直挂着「脚本运行中」
    msg.runBoard = p.fanOut
  } catch (e) { /* 忽略 */ }
}

export const applyAgentDelegated = (msg, raw, onScroll = () => {}) => {
  // 会话内 @ 智能体（§4）：本轮由用户 @ 提及的智能体作答（会话绑定不变），归属同步改写
  // （done 的 delegatedAgentName 会再校正一次，两处同值）
  try {
    const r = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!r || !r.name) return
    msg.agentName = r.name
    if (r.id) msg.agentId = r.id
    onScroll()
  } catch (e) { /* 忽略 */ }
}
