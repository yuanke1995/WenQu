// 上下文容量面板 + 右栏产物投影（从 useChatEngine 抽出）。
// 这些都是只读投影：窗口占用圆环、最后一条 AI 消息、本会话产物/token/检索统计，
// 以及产物签名换发（SSE 下发的 URL 签好就不变，页面开久必 401，这里现场重签）。
// 依赖只有六个外部对象，全部走显式入参注入。
import { computed } from 'vue'

export const useContextPanel = ({ ctxTokens, effectiveModel, modelIndex, messages,
                                  refreshApi }) => {
  // ==================== 上下文容量面板（工具栏圆环悬浮：占用/窗口 + 网关实测用量） ====================
  /** 容量数据：窗口取模型登记/用户档位（tokens.window 优先，回落 modelIndex），用量取本轮结束时的
   *  上下文占用（tokens.context，无则 prompt）；数据源走 ctxTokens（流式期间=usage 帧，完成后=done 的
   *  实测 tokens），生成一开始即点亮。
   *  占用为什么优先 context 而不是 prompt：prompt 是各轮请求的输入之和（账单口径，工具调用会让它
   *  逐轮累加、远大于真实占用），而本面板回答的是「窗口被占了多少」。
   *  明细只报网关 usage 回传的实测值，不再按消息结构估分类：估出来的分段每一格都不是实测，
   *  摊平到总数上只会让界面摆出一本算得平的假账。 */
  const ctxCapData = computed(() => {
    const t = ctxTokens.value
    const info = modelIndex.value[effectiveModel.value]
    const num = v => Number(v) || 0
    const window_ = (t && t.window) || info?.contextWindow || 0
    const used = t ? (num(t.context) || num(t.prompt)) : 0
    const prompt = t ? num(t.prompt) : 0
    const cached = t ? num(t.cached) : 0
    const cacheWrite = t ? num(t.cacheWrite) : 0
    const output = t ? num(t.output) : 0
    const reasoning = t ? num(t.reasoning) : 0
    const pct = window_ > 0 ? Math.min(100, Math.round(used / window_ * 1000) / 10) : 0
    // 谁含谁写死在行里（sub=缩进、名里带「其中/未缓存」），免得两行并排被人拿去相加
    const rows = []
    if (prompt > 0) {
      rows.push({ label: '本轮送入', tokens: prompt })
      if (cached > 0) {
        rows.push({ label: '未缓存输入', tokens: Math.max(0, prompt - cached), sub: true })
        rows.push({ label: '缓存读取', tokens: cached, sub: true,
                    note: `命中率 ${Math.round(cached / prompt * 1000) / 10}%` })
      }
      if (cacheWrite > 0) rows.push({ label: '缓存写入', tokens: cacheWrite, sub: true })
    }
    if (output > 0) {
      rows.push({ label: '输出', tokens: output })
      if (reasoning > 0) rows.push({ label: '其中思考', tokens: reasoning, sub: true })
    }
    return {
      window: window_, used, pct, rows,
      // 填充条宽=用量/窗口（不取整：窗口大用量小时 pct 取整会成 0 宽）
      fillPct: window_ > 0 ? Math.min(100, used / window_ * 100) : 0
    }
  })
  // 工具栏容量圆环：r=7.5 周长固定，按 pct 撑 stroke-dasharray；告警档位与右栏占用条一致
  const CTX_RING_C = 2 * Math.PI * 7.5
  const ctxRingDash = computed(() => {
    const p = Math.min(100, Math.max(0, ctxCapData.value.pct))
    return `${(p / 100 * CTX_RING_C).toFixed(2)} ${CTX_RING_C.toFixed(2)}`
  })
  const ctxRingLevel = computed(() => ctxCapData.value.pct >= 95 ? 'danger' : ctxCapData.value.pct >= 80 ? 'warn' : '')
  // ==================== 右栏：本会话产物（消息流内已有完整执行明细） ====================
  // panelAi = 最后一轮 AI 消息（含进行中）——供正文「接下来可以」只挂在最新一轮、移动壳末轮标记定位，
  // 与「本次用量」用的 lastAi（仅已完成轮）口径不同
  const panelAi = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'ai') return messages.value[i]
    }
    return null
  })
  // 本会话产物：汇总所有轮次的 artifact（历史恢复的消息同样带 artifacts），最新一轮在前
  const sessionArtifacts = computed(() => {
    const out = []
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.role !== 'ai' || !Array.isArray(m.artifacts)) continue
      for (const a of m.artifacts) if (a && a.url) out.push(a)
    }
    return out
  })

  // ==================== 产物签名换发 ====================
  // 产物下载 URL 带 HMAC 签名，有效期固定 1 小时。SSE 下发与历史接口
  // 给的那份 URL「签好就不变」，页面开久后点下载必然 401「图片链接无效或已过期」——而同一文件在
  // 「我的产物」页正常，因为那个列表每次进页面都现场重签。这里在会话产物出现/切换时换一批新鲜签名，
  // 就地改写各消息里 artifact 的 url（保持对象引用不变，视图自动更新）。
  // 入参用产物 id 而非 url：服务端只给库里归属自己的产物重签，前端无法构造任意路径。
  let refreshSeq = 0
  const refreshArtifactSigns = async () => {
    const seq = ++refreshSeq
    // 收集本会话全部产物（按 id 去重；无 id 的条目跳过）
    const byId = new Map()
    for (const m of messages.value) {
      if (m.role !== 'ai' || !Array.isArray(m.artifacts)) continue
      for (const a of m.artifacts) if (a && a.id && a.url) byId.set(a.id, a)
    }
    if (!byId.size) return
    try {
      const res = await refreshApi([...byId.keys()])
      // 请求期间可能又切了会话、又出了新产物，丢弃过期结果，避免把 A 会话的签名写进 B 会话
      if (seq !== refreshSeq) return
      const rows = Array.isArray(res?.data) ? res.data : []
      for (const r of rows) {
        const a = byId.get(r?.id)
        if (a && r.url) a.url = r.url
      }
    } catch (e) { /* 换签失败静默降级：仍可沿用旧签名，不阻断浏览 */ }
  }
  // 会话累计用量（手机「状态与来源」面板的「本会话累计」）：只累加消息里真实记录的用量，
  // 历史恢复的轮没有该字段——不计入，也不冒充 0。主数取「累计送入」（本轮内每次模型调用输入之和的
  // 会话总和，账单口径），不取输入+输出之和：再算上输出就是把同一笔钱报两遍。
  const sessionTokens = computed(() => {
    let prompt = 0, rounds = 0
    for (const m of messages.value) {
      if (m.role !== 'ai' || !m.tokens || typeof m.tokens !== 'object') continue
      rounds++
      prompt += Number(m.tokens.prompt) || 0
    }
    return { prompt, rounds }
  })
  // 会话累计检索：检索轮数按「该轮有检索行或引用」计
  const sessionRetrieval = computed(() => {
    let rounds = 0, refs = 0
    for (const m of messages.value) {
      if (m.role !== 'ai') continue
      if (m.retrieved || (Array.isArray(m.sources) && m.sources.length)) {
        rounds++
        refs += Array.isArray(m.sources) ? m.sources.length : 0
      }
    }
    return { rounds, refs }
  })
  // 上下文占用由容量卡的 ctxCapData 回答（context/window，>80% 警告 / >95% 危险）：
  // 框架引擎下没有检索配额那套分配，报「预算」只会给出一个无人认领的数。

  return {
    CTX_RING_C, ctxCapData, ctxRingDash, ctxRingLevel,
    panelAi, sessionArtifacts, sessionRetrieval, sessionTokens, refreshArtifactSigns,
  }
}
