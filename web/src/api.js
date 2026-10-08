/**
 * AI 服务 API 层（零依赖：原生 fetch + XHR）
 * - 常规请求：fetch 封装（超时/401/业务失败统一抛出可读错误）
 * - 上传：XMLHttpRequest（支持进度回调）
 * - SSE：fetch + AbortController（支持停止生成）
 * - 环境配置：VITE_API_BASE 接口前缀
 */
const BASE = import.meta.env.VITE_API_BASE || '/proxy/api/ai'

// 登录令牌（本地登录后写入 localStorage('ai_token')；请求头 Authorization: Bearer <token>）
const authToken = () => {
  try { return localStorage.getItem('ai_token') || '' } catch (e) { return '' }
}

const authHeaders = extra => {
  const h = { 'Content-Type': 'application/json', ...(extra || {}) }
  const bt = authToken()
  if (bt) h['Authorization'] = 'Bearer ' + bt
  return h
}

/**
 * 通用 JSON 请求：超时控制 + 401/业务失败统一抛出 Error(message)（附 status 供调用方分流）
 * silentForbidden: 403 时不派发全局 app:forbidden（归属类拒绝由调用方静默处理的场景）
 */
async function request(path, { method = 'GET', body, timeout = 30000, silentForbidden = false } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: authHeaders(),
      body,
      signal: controller.signal
    })
    let data = null
    try { data = await res.json() } catch (e) { /* 非 JSON 响应 */ }
    if (!res.ok) {
      if (res.status === 401) window.dispatchEvent(new CustomEvent('app:unauthorized'))
      if (res.status === 403 && !silentForbidden) window.dispatchEvent(new CustomEvent('app:forbidden', { detail: data?.msg }))
      const err = new Error(data?.msg || `请求失败(${res.status})`)
      err.status = res.status
      throw err
    }
    if (data && data.success === false) throw new Error(data.msg || '请求失败')
    return data
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('请求超时，请稍后重试')
    throw e
  } finally {
    clearTimeout(timer)
  }
}

/**
 * XHR 上传（multipart，支持进度百分比回调）
 */
function upload(path, formData, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', BASE + path)
    const bt = authToken()
    if (bt) xhr.setRequestHeader('Authorization', 'Bearer ' + bt)
    xhr.timeout = 120000
    xhr.upload.onprogress = e => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      let data = null
      try { data = JSON.parse(xhr.responseText) } catch (e) { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) {
        if (data && data.success === false) reject(new Error(data.msg || '上传失败'))
        else resolve(data)
      } else {
        if (xhr.status === 401) window.dispatchEvent(new CustomEvent('app:unauthorized'))
        reject(new Error(data?.msg || `上传失败(${xhr.status})`))
      }
    }
    xhr.onerror = () => reject(new Error('网络错误'))
    xhr.ontimeout = () => reject(new Error('上传超时'))
    xhr.send(formData)
  })
}

/**
 * ==================== SSE 事件表（登录态与游客分享共用）====================
 * 「事件名 → 回调名」只在这里声明一次。两条链路（sendQuestion / sendShareMessage）各自只管传输与收尾，
 * 派发统一走 dispatchChatEvent。此前它们各写一份 if/else（主链路认 20 种事件、分享链路只认 4 种），
 * 新增事件必然漏一处——游客分享看不到图片与引用就是这么掉的。
 * 终止事件 done / error 刻意不进表：两条链路的收尾语义不同（分享链路的 sessionId 在事件信封上，
 * 且要摘掉 visibilitychange 监听）。
 */
const CHAT_EVENT_CB = {
  token: 'onToken', stage: 'onStage', plan: 'onPlan', usage: 'onUsage',
  retrieved: 'onRetrieved', thinking: 'onThinking', thinking_done: 'onThinkingDone',
  image: 'onImage', warn: 'onWarn', artifact: 'onArtifact',
  tool_status: 'onToolStatus', tool_output: 'onToolOutput', process: 'onProcess',
  subagent: 'onSubagent', subagent_route: 'onSubagentRoute',
  agent_dispatched: 'onAgentDispatched', agent_delegated: 'onAgentDelegated',
  agent_bound: 'onAgentBound', approval_required: 'onApprovalRequired', ask_user: 'onAskUser',
  plan_delta: 'onPlanDelta', plan_approval: 'onPlanApproval', plan_cancelled: 'onPlanCancelled'
}

/** 解析一行 SSE `data:` → 事件对象；心跳注释行/非 JSON/无 type 一律返回 null */
function parseChatEvent (line) {
  if (!line.startsWith('data:')) return null
  try {
    const d = JSON.parse(line.slice(5).trim())
    return d && d.type ? d : null
  } catch (e) {
    console.warn('[SSE] JSON 解析失败，已忽略该行:', e.message)
    return null
  }
}

/**
 * 把一条非终止事件派发给 handlers 上对应的回调（回调没传即忽略——分享页就不关心工具卡与审批）。
 * handlers 直接复用调用方的 options 对象：本表的值就是其中的回调键名，不再另立一份映射字面量。
 */
function dispatchChatEvent (d, handlers) {
  const name = CHAT_EVENT_CB[d.type]
  if (!name) { console.warn('[SSE] 未识别事件类型:', d.type); return false }
  if (typeof handlers[name] === 'function') handlers[name](d.content == null ? '' : d.content)
  return true
}

/**
 * 流式聊天（SSE）
 * signal 用于停止生成（外部 AbortController.abort()）
 * deepThink=true 时后端先流式输出思考过程（thinking / thinking_done 事件）
 * idleTimeoutMs：读流空闲看门狗——服务端排队/挂起超过该时长未推任何数据即中断并报错（默认 120s），
 * 避免 UI 永久转圈；收到任意数据自动重置计时。
 */
export function sendQuestion(sessionId, question, images = [], opts = {}) {
  const {
    // on* 回调不逐个解构：非终止事件由 dispatchChatEvent 按 CHAT_EVENT_CB 直接从 opts 取，
    // 新增事件只改那张表。这里只留本函数收尾要用的两个。
    onDone, onError,
    deepThink = false, reasoningLevel = '', signal, idleTimeoutMs = 120000, agentId = '', model = '', attachments = [], skills = [], mentions = [], historyRefs = [], regenerate = false, replaceMessageId = '',
    contextWindow = null, editMessageId = '', planMode = false
  } = opts
  if (typeof onError !== 'function' || typeof onDone !== 'function') return

  const inner = new AbortController()
  let idleTimedOut = false
  let idleTimer = null
  // 页面隐藏（切后台/切标签）时暂停看门狗。iOS Safari 切后台 30s+ 会冻结 JS 定时器，
  // 回前台时 setTimeout 立即到期 → 误判「长时间未收到响应」而报错。
  // 正确处理是**冻结期间不计超时**（隐藏时停表、回前台重新给满），
  // 而不是放宽 idleTimeoutMs —— 放宽会让真正的服务端卡死迟迟不报。
  let idlePaused = false
  const armIdle = () => {
    clearTimeout(idleTimer)
    if (idlePaused) return          // 隐藏期间不重置计时：冻结时长不该算进超时
    idleTimer = setTimeout(() => {
      idleTimedOut = true
      inner.abort()
    }, idleTimeoutMs)
  }
  const stopIdle = () => clearTimeout(idleTimer)
  // 隐藏时收表，回前台补满整段
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') { idlePaused = true; stopIdle() }
    else if (idlePaused) { idlePaused = false; if (!ended) armIdle() }
  }
  document.addEventListener('visibilitychange', onVisibility)
  if (signal) {
    if (signal.aborted) inner.abort()
    else signal.addEventListener('abort', () => inner.abort())
  }
  // 终态闸门：整条流最多收尾一次。此前 done 事件先调 end()（触发一次无载荷的 onDone）
  // 再调 onDone(content)，**回调被调用两次**——处理逻辑是"重新赋值"所以一直没暴露，
  // 直到按消息状态做增量操作（重新生成的版本序列）出现重复计数。所有终态路径统一走这里。
  let ended = false
  let donePayload = null
  // kind 区分失败性质，供 UI 决定文案与「重试」语义：
  //   'interrupted' —— 断线/读失败/连接被掐断/idle 超时。**后端已把半程回答按截断态落库**
  //                    （RagService.persistPartialAnswer，尾部带「⏹ 中断」标记），
  //                    所以「重试」= 重新生成本轮，后端会软删那条截态回答并挂进同一版本组。
  //   'aborted'     —— 用户主动停止（signal abort），属正常结束。
  //   其他/未传      —— 业务错误（4xx/5xx、模型报错等）。
  // ⚠️ 刻意**不做**自动重连：后端 SseEmitter 是一次性单向通道，无事件序号、无 resume 端点，
  //    重连只能重发整个 POST /chat → 后端视为一轮全新问答 → 重复落库 + 重复计费。
  const end = (err, kind) => {
    if (ended) return
    ended = true
    stopIdle()
    document.removeEventListener('visibilitychange', onVisibility)
    if (err) onError(err, kind)
    else onDone(donePayload)
  }

  fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({
      sessionId, question, images, deepThink,
      // 计划模式（人在回路）：true=本轮先产出执行计划，经用户批准/编辑后再正式回答
      planMode: planMode || undefined,
      // 思考强度档位（低/中/高/超高/极致）：仅在模型库登记了支持档位时由界面给出，空串=用模型默认档位
      reasoningLevel: reasoningLevel || '',
      // 上下文窗口档位（token）：仅在模型登记了「最小窗口~窗口」区间时由界面给出，空=用模型登记上限
      contextWindow: contextWindow || undefined,
      agentId: agentId || '', model: model || '',
      // 重新生成/自动重试的重发标记：后端跳过用户消息重复落库（该问题已随上一轮请求入库）
      regenerate: regenerate || undefined,
      // 重新生成时被替换的旧回答消息 ID：新回答落库前先软删它，历史里只留最新一版
      replaceMessageId: replaceMessageId || undefined,
      // 编辑重发：被编辑的用户消息 ID。后端把该消息起的旧分支软删留档（可切换回看），编辑内容作为新分支重新生成
      editMessageId: editMessageId || undefined,
      // 文档类附件（[{name,mime,data}]，data 为 dataURL，服务端解析文本注入上下文）与本轮指定技能名
      attachments: Array.isArray(attachments) && attachments.length ? attachments : undefined,
      skills: Array.isArray(skills) && skills.length ? skills : undefined,
      // 输入框 @ 引用（[{type:'kb'|'doc'|'agent', id, name}]）：kb 收窄本轮检索范围、doc 强制前置其内容、agent 临时委派本轮作答（服务端校验可见性）
      mentions: Array.isArray(mentions) && mentions.length ? mentions : undefined,
      // 输入框 # 历史引用（[{messageId}]）：服务端按会话归属校验并查库回填内容，前置进本轮上下文
      historyRefs: Array.isArray(historyRefs) && historyRefs.length ? historyRefs : undefined
    }),
    signal: inner.signal
  }).then(res => {
    if (!res.ok) {
      if (res.status === 401) window.dispatchEvent(new CustomEvent('app:unauthorized'))
      res.json().then(d => end(d?.msg || '请求失败: ' + res.status)).catch(() => end('请求失败: ' + res.status))
      return
    }
    armIdle()
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    const read = () => {
      reader.read().then(({ done, value }) => {
        // 流关闭但从未收到 done/error 事件：连接被中间层/服务端提前掐断，
        // 按失败上报（fail-loud），不能假装正常结束把半截回答留在屏上
        if (done) { end(donePayload !== null ? undefined : '连接被提前关闭，回答未正常结束，请重试', 'interrupted'); return }
        armIdle() // 收到数据（任意字节）即视为存活
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          // Spring SseEmitter 输出 "data:{...}"（冒号后无空格），需兼容带/不带空格两种
          const d = parseChatEvent(line)
          if (!d) continue
          if (d.type === 'done') { donePayload = d.content; end(); return } // content 为 {sources,related,finalImages,degradations,…} JSON 字符串
          if (d.type === 'error') { end(d.content); return }
          dispatchChatEvent(d, opts)
        }
        read()
      }).catch(e => {
        // 空闲看门狗超时按"可重试错误"上报；用户主动停止（signal abort）按正常结束处理
        if (idleTimedOut) end('长时间未收到响应，连接已中断，请重试', 'interrupted')
        else if (e.name === 'AbortError') end()
        else end('读取失败: ' + e.message, 'interrupted')
      })
    }
    read()
  }).catch(e => {
    // 同一道闸门收尾：用户主动停止按正常结束（onDone 无载荷 → 前端收尾为「已停止生成」），
    // 空闲超时与其它错误按可重试错误上报。此前这里不走 end()，与内层 reader 的收尾可能各调一次回调
    if (e.name === 'AbortError') end(idleTimedOut ? '长时间未收到响应，连接已中断，请重试' : undefined, idleTimedOut ? 'interrupted' : undefined)
    else end('请求失败: ' + e.message, 'interrupted')
  })
}

/** 新建会话 */
export const newSession = () => request('/session/new', { method: 'POST' })

/** 获取会话历史 */
export const getHistory = (sid, opts) => request(`/session/${sid}`, opts)

/** 清除会话（Redis 缓存） */
export const clearSession = sid => request(`/session/${sid}`, { method: 'DELETE' })

/**
 * 手动压缩会话上下文（/compact）：早期对话并入摘要，为后续提问腾出上下文空间。
 * instruction 为附加要求（可空）；model 传会话当前选择的模型引用（空=后端按个人默认解析）。
 * 长会话后端分批多次调用模型（每批最多 60s，最多 8 批），故超时给到 300s——
 * 默认 30s 会把正常等待误判成超时。超时后已压好的批次已落库，可再次执行接着压。
 */
export const compactSessionApi = (sid, { instruction = '', model = '' } = {}) =>
  request(`/session/${sid}/compact`, { method: 'POST', body: JSON.stringify({ instruction, model }), timeout: 300000 })

// ==================== 会话只读分享（链接持有者可看，不可续聊） ====================
/** 查询分享状态（仅会话所有者）：{enabled, token, visitCount, lastVisitAt} */
export const getSessionShare = sid => request(`/session/${sid}/share`)
/** 开启（或重新生成）分享链接：停止后重新开启会换新 token，旧链接立即失效 */
export const enableSessionShare = sid => request(`/session/${sid}/share`, { method: 'POST' })
/** 停止分享：链接立即失效 */
export const disableSessionShare = sid => request(`/session/${sid}/share`, { method: 'DELETE' })
/** 公开只读页（免登录）：按 token 取会话标题与消息 */
export const getSharedSession = token => request(`/share/session/${encodeURIComponent(token)}`)
/** 我分享过的全部会话（分享管理页）：含 enabled/orphaned/visitCount/lastVisitAt */
export const listMySessionShares = () => request('/session-shares')
/** 按会话停用分享（分享管理页用；会话已删除的悬空记录同样可停，故不走 /session/{id}/share） */
export const stopShareBySession = sid => request(`/session-shares/${encodeURIComponent(sid)}`, { method: 'DELETE' })
/** 彻底清除一条**已停止**的分享记录（生效中的后端会拒 400，须先停止分享） */
export const purgeShareRecord = sid =>
  request(`/session-shares/${encodeURIComponent(sid)}/record`, { method: 'DELETE' })

/**
 * 列出会话（游标分页）：首页不传 cursor；后续页传上一页返回的 nextCursor。
 * keyword 按标题/消息内容模糊搜索（分页同样生效）。
 * 返回 {items, nextCursor, hasMore, groupCounts:{pinned,today,week,earlier}, total}
 */
export const listSessions = (keyword = '', cursor = '', size) => {
  const qs = []
  if (keyword) qs.push('keyword=' + encodeURIComponent(keyword))
  if (cursor) qs.push('cursor=' + encodeURIComponent(cursor))
  if (size) qs.push('size=' + size)
  return request('/sessions' + (qs.length ? '?' + qs.join('&') : ''))
}

/** 置顶/取消置顶会话 */
export const pinSession = (sid, pinned) =>
  request(`/session/${sid}/pin`, { method: 'PUT', body: JSON.stringify({ pinned }) })

/** 收藏/取消收藏会话 */
export const favoriteSession = (sid, favorite) =>
  request(`/session/${sid}/favorite`, { method: 'PUT', body: JSON.stringify({ favorite }) })

/** 重命名会话 */
export const renameSessionApi = (sid, title) =>
  request(`/session/${sid}/rename`, { method: 'PUT', body: JSON.stringify({ title }) })

/** 会话级模型覆盖（聊天页切换模型即存；model 传空串=清除覆盖回到个人默认） */
export const updateSessionModelApi = (sid, model) =>
  request(`/session/${sid}/model`, { method: 'PUT', body: JSON.stringify({ model }) })

/** 读取会话已存的模型覆盖（切到列表未加载的会话时按需恢复选择器） */
export const getSessionModelApi = sid => request(`/session/${sid}/model`)

/** 删除会话（MySQL 软删除 + Redis 清理） */
export const deleteSessionApi = sid => request(`/session/${sid}`, { method: 'DELETE' })

/** 清空所有会话 */
export const clearAllSessionsApi = () => request('/sessions', { method: 'DELETE' })

/** 批量删除会话（按 ID 列表，软删除） */
export const batchDeleteSessionsApi = ids => request('/sessions/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })

/** 文档列表 */
export const listDocuments = kbId => request('/document/list' + (kbId ? '?kbId=' + encodeURIComponent(kbId) : ''))

// ==================== 知识库（检索作用域） ====================
/** 知识库列表（含每个库的文档数） */
export const listKnowledgeBases = () => request('/kb/list')

// ==================== 帮助中心（官方内置手册：读只读 / 同步需管理员） ====================
/** 手册篇目列表（官方内置库的只读视图） */
export const listManualDocs = () => request('/manual/documents')
/** 单篇内容（Markdown 原文） */
export const getManualDocContent = id => request(`/manual/documents/${encodeURIComponent(id)}/content`)
/** 手动同步官方手册（仅管理员）：逐篇重块向量化，会消耗向量模型额度；内容未变则跳过不产生调用。
 *  超时同工作流「同步调试运行」口径给到 5 分钟——十余篇逐块向量化可能跑很久，
 *  但不宜给满 10 分钟：超长等待期间用户无从判断是卡住还是在跑。 */
export const syncManual = () => request('/manual/sync', { method: 'POST', timeout: 300000 })

// 知识库参数默认值（检索/解析参数的当前生效默认值：个人设置 > 系统全局）：新建库模板预填 + 表单占位符展示。
// 普通用户可读（/config 是管理端点会 403），知识库已对普通用户开放自建。
export const getKbParamDefaults = () => request('/kb/param-defaults')
// 知识库参数问号文案（backendKey → 人话解释；普通用户可读，与系统设置页字段说明同一份定义源）
export const getKbParamTips = () => request('/kb/param-tips')

// 我的产物（模型在回答中生成的可下载文件，按用户归属；url 为可直接下载的签名地址）
export const listArtifacts = keyword =>
  request('/artifact/list' + (keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''))
export const deleteArtifact = id => request(`/artifact/${id}`, { method: 'DELETE' })
// 批量删除：逐条校验归属，返回 { deleted, skipped }（skipped = 不存在/无权的 id）
export const deleteArtifactsBatch = ids =>
  request('/artifact/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })

// 定时任务（定时执行智能体；个人资产，每人管自己的）
export const listScheduledJobs = () => request('/scheduled/list')
export const createScheduledJob = body => request('/scheduled', { method: 'POST', body: JSON.stringify(body) })
export const updateScheduledJob = (id, body) =>
  request(`/scheduled/${id}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteScheduledJob = id => request(`/scheduled/${id}`, { method: 'DELETE' })
export const toggleScheduledJob = (id, enabled) =>
  request(`/scheduled/${id}/enabled`, { method: 'PUT', body: JSON.stringify({ enabled }) })
export const runScheduledJob = id => request(`/scheduled/${id}/run`, { method: 'POST' })
export const listScheduledRuns = (id, limit = 20) => request(`/scheduled/${id}/runs?limit=${limit}`)
// 批量：逐条执行、部分成功是批量固有语义，返回 {succeeded:[id], failed:[{id,name,error}]}
export const batchDeleteScheduledJobs = ids =>
  request('/scheduled/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })
export const batchToggleScheduledJobs = (ids, enabled) =>
  request('/scheduled/batch-enabled', { method: 'POST', body: JSON.stringify({ ids, enabled }) })

// ---------- 工作流（DSL 唯一真源；画布只是编辑器） ----------
/** 列表：分栏返回 {mine:[我创建的], shared:[共享给我的]}；每条带 myPermission（MANAGE/READ）与 mine */
export const listWorkflows = () => request('/workflow/list')
export const getWorkflow = id => request(`/workflow/${id}`)
export const createWorkflow = body => request('/workflow', { method: 'POST', body: JSON.stringify(body) })
export const updateWorkflow = (id, body) => request(`/workflow/${id}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteWorkflow = id => request(`/workflow/${id}`, { method: 'DELETE' })
/** 校验 + 编译 dry-run：返回 {errors:[], compiled, compileError?, nodeCount, edgeCount} */
export const validateWorkflowDsl = dsl => request('/workflow/validate', { method: 'POST', body: JSON.stringify({ dsl }) })
/** 同步调试运行（LLM/检索耗时可达分钟级，放宽超时）；失败不抛——返回 status=failed 的 run */
export const runWorkflow = (id, inputs) =>
  request(`/workflow/${id}/run`, { method: 'POST', body: JSON.stringify({ inputs }), timeout: 300000 })
/** 第 2 期：异步调试运行——创建即返回 status=queued 的 run，配合 subscribeWorkflowRun 边跑边亮 */
export const runWorkflowAsync = (id, inputs) =>
  request(`/workflow/${id}/run`, { method: 'POST', body: JSON.stringify({ inputs, async: true }) })
/** 第 2 期：取消排队中的运行（仅 queued 可取消；已开始/已结束报错） */
export const cancelWorkflowRun = (id, runId) =>
  request(`/workflow/${id}/run/${runId}/cancel`, { method: 'POST' })
/**
 * 第 2 期：订阅运行进度流（SSE over fetch——EventSource 不能带鉴权头，故用 fetch + reader）。
 * 事件：snapshot（订阅瞬间状态 + 已产生 trace）/ trace（节点完成）/ status（状态迁移）/ done（终态 run 全量）。
 * @returns abort 函数（组件卸载 / 开始新一轮运行前调用）
 */
export function subscribeWorkflowRun (workflowId, runId, onEvent, onError) {
  const controller = new AbortController()
  fetch(`${BASE}/workflow/${workflowId}/run/${runId}/stream`, {
    headers: { Authorization: 'Bearer ' + authToken(), Accept: 'text/event-stream' },
    signal: controller.signal
  }).then(res => {
    if (!res.ok || !res.body) {
      onError && onError(new Error('进度流连接失败(' + res.status + ')'))
      return
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let eventName = ''
    const read = () => {
      reader.read().then(({ done, value }) => {
        if (done) { onEvent && onEvent('close', null); return }
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const raw of lines) {
          const line = raw.replace(/\r$/, '')
          if (!line) { eventName = ''; continue }          // 空行 = 一个事件结束
          if (line.startsWith(':')) continue               // 心跳注释行
          if (line.startsWith('event:')) { eventName = line.substring(6).trim(); continue }
          if (line.startsWith('data:')) {
            const txt = line.substring(5).trim()
            let payload = null
            try { payload = txt ? JSON.parse(txt) : null } catch (e) { payload = null }
            onEvent && onEvent(eventName || 'message', payload)
          }
        }
        read()
      }).catch(e => { if (e.name !== 'AbortError') onError && onError(e) })
    }
    read()
  }).catch(e => { if (e.name !== 'AbortError') onError && onError(e) })
  return () => controller.abort()
}
export const listWorkflowRuns = id => request(`/workflow/${id}/run/list`)
export const getWorkflowRun = (id, runId) => request(`/workflow/${id}/run/${runId}`)
// M5 模板库：内置模板清单（选用即把 dsl 作为新建入参）
export const listWorkflowTemplates = () => request('/workflow/templates')
// M5 委派编排转工作流：一键把智能体的 subAgentIds 编排转成工作流
export const createWorkflowFromAgent = agentId => request(`/workflow/from-agent/${agentId}`, { method: 'POST' })
// M5 失败检查点续跑：failed/timeout 且带快照的运行从失败点续跑（同步返回终态 run）
export const resumeWorkflowRun = (id, runId) =>
  request(`/workflow/${id}/run/${runId}/resume`, { method: 'POST', timeout: 300000 })
/** 人工审核：待审批信息（prompt/超时/挂起时刻），无则 null */
export const getWorkflowPendingApproval = (id, runId) => request(`/workflow/${id}/run/${runId}/approval`)
/** 人工审核：裁决并恢复续跑（同步跑完，返回终态 run） */
export const resolveWorkflowApproval = (id, runId, approved) =>
  request(`/workflow/${id}/run/${runId}/approval`, { method: 'POST', body: JSON.stringify({ approved }), timeout: 300000 })
// ---------- M4：发布与版本 ----------
/** 发布当前草稿为新版本（返回 {id, version, publishedAt, status}）；草稿校验不过会被拒 */
export const publishWorkflow = (id, note) =>
  request(`/workflow/${id}/publish`, { method: 'POST', body: JSON.stringify({ note: note || '' }) })
/** 下线（已发布 → 草稿）：API 触发与智能体绑定随即不可用 */
export const unpublishWorkflow = id => request(`/workflow/${id}/unpublish`, { method: 'POST' })
/** 版本历史（新→旧）：[{version, note, publishedBy, publishedAt, current}] */
export const listWorkflowVersions = id => request(`/workflow/${id}/versions`)
/** 回滚到指定版本：以该版本 DSL 再发一版（返回 {version, rolledBackTo}） */
export const rollbackWorkflow = (id, version) => request(`/workflow/${id}/rollback/${version}`, { method: 'POST' })
// ---------- 第 1 期：共享范围 + 定时触发 + 终态回调 ----------
/** 设置共享范围：shareConfig 为 v2 JSON（空串=清空回落私有）；仅创建者/管理范围可改 */
export const shareWorkflow = (id, shareConfig) =>
  request(`/workflow/${id}/share`, { method: 'POST', body: JSON.stringify({ shareConfig }) })
/** 自动化配置（定时 + 回调）：{scheduleEnabled, cron, timezone, nextRunAt, callbackUrl, callbackSecretSet, published} */
export const getWorkflowAutomation = id => request(`/workflow/${id}/automation`)
/** 保存自动化配置：body {scheduleEnabled, cron, timezone, callbackUrl, callbackSecret} */
export const saveWorkflowAutomation = (id, body) =>
  request(`/workflow/${id}/automation`, { method: 'PUT', body: JSON.stringify(body) })
/** 立即触发一次定时运行（跑已发布版本，同步返回终态 run） */
export const runWorkflowScheduleNow = id =>
  request(`/workflow/${id}/schedule/run-now`, { method: 'POST', timeout: 300000 })
// ---------- 批量操作：逐条执行、部分成功是批量固有语义，返回 {succeeded:[id], failed:[{id,name,error}]} ----------
export const batchDeleteWorkflows = ids => request('/workflow/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })
export const batchPublishWorkflows = (ids, note = '') => request('/workflow/batch-publish', { method: 'POST', body: JSON.stringify({ ids, note }) })
export const batchUnpublishWorkflows = ids => request('/workflow/batch-unpublish', { method: 'POST', body: JSON.stringify({ ids }) })
// ---------- 第 3 期：工作流凭据（http 节点 {{credential:名称}} 引用；个人资产，值加密落库、出参脱敏） ----------
export const listCredentials = () => request('/credential/list')
export const createCredential = body => request('/credential', { method: 'POST', body: JSON.stringify(body) })
export const updateCredential = (id, body) => request(`/credential/${id}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteCredential = id => request(`/credential/${id}`, { method: 'DELETE' })
/** 新建知识库：{name(必填), embeddingRef(必填), description, icon, queryParams, parseParams, graphEnabled, graphModelRef}；
 *  isDefault 由系统管理（默认库每人一个、系统懒创建），提交会被忽略 */
export const createKnowledgeBase = body => request('/kb', { method: 'POST', body: JSON.stringify(body) })
/** 编辑知识库：仅更新 body 中出现的字段；queryParams 传空串表示恢复继承全局；isDefault 同上（会被忽略） */
export const updateKnowledgeBase = (id, body) => request(`/kb/${id}`, { method: 'PUT', body: JSON.stringify(body) })
/** 删除知识库（默认库或库内仍有文档时后端会拒绝并返回原因） */
export const deleteKnowledgeBase = id => request(`/kb/${id}`, { method: 'DELETE' })
/** 移动文档到知识库；kbId 传空表示移回默认库 */
export const moveDocToKb = (docId, kbId) => request(`/kb/doc/${docId}`, { method: 'PUT', body: JSON.stringify({ kbId: kbId || null }) })

/**
 * 下载文档源文件（个人文件区：取回上传的原始文件）。
 * 走 fetch 带鉴权头取 blob（直接 a[href] 无法带 Authorization/管理员口令），再触发浏览器下载。
 */
export async function downloadDocumentSource (id, fileName) {
  const r = await fetch(`${BASE}/document/${id}/source`, { headers: authHeaders() })
  if (!r.ok) {
    let msg = '下载失败'
    try { const j = await r.json(); if (j && j.msg) msg = j.msg } catch (e) { /* 非 JSON 响应保留默认文案 */ }
    throw new Error(msg)
  }
  const blob = await r.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName || 'document'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

// ==================== 工具清单（管理员：已注册 @Tool 全景，设置页·工具调用面板展示） ====================
export const getToolInventory = () => request('/tools')

// ==================== MCP 外部工具（个人资产：每人管自己登记的 Server） ====================
/** retryBroken=true：先对未连上的服务补一次重连再出列表（MCP 页点「刷新」用）；
 *  verifyOnline=true：对已连接服务做在线校验（远程 listTools，慢）——只有 MCP 管理页要"此刻真实状态"
 *  时才传；只需要服务清单/最近状态的调用方（如智能体页填充下拉）别传，否则页面加载会被远程 MCP
 *  的握手延迟拖住（context7 单请求 1~3.5s+，远端挂起要吃满 60s 超时）。默认后端只读本地已知状态 */
export const getMcpStatus = (retry, verifyOnline, withTools) => {
  const qs = []
  if (retry) qs.push('retryBroken=true')
  if (verifyOnline) qs.push('verifyOnline=true')
  // withTools：只补工具清单不校验状态（工作流画布 mcp 节点要列工具；纯本地档 tools 恒为空）
  if (withTools) qs.push('withTools=true')
  return request('/mcp/status' + (qs.length ? '?' + qs.join('&') : ''))
}
export const reloadMcp = () => request('/mcp/reload', { method: 'POST' })
export const probeMcp = (url, type) => request('/mcp/probe', { method: 'POST', body: JSON.stringify({ url, type }) })
export const addMcpServer = body => request('/mcp/servers', { method: 'POST', body: JSON.stringify(body) })
export const updateMcpServer = (id, body) => request('/mcp/servers/' + id, { method: 'PUT', body: JSON.stringify(body) })
export const setMcpServerEnabled = (id, enabled) =>
  request(`/mcp/servers/${id}/enabled`, { method: 'POST', body: JSON.stringify({ enabled }) })
export const deleteMcpServer = id => request('/mcp/servers/' + id, { method: 'DELETE' })
// 批量（只碰自己的）：返回 {succeeded:[id], failed:[{id,name,error}]}；改完连接池统一重建
export const batchDeleteMcpServers = ids =>
  request('/mcp/servers/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })
export const batchSetMcpServersEnabled = (ids, enabled) =>
  request('/mcp/servers/batch-enabled', { method: 'POST', body: JSON.stringify({ ids, enabled }) })

/** MCP 调用审计（仅管理员）：外部客户端调用 /ai/mcp 端点的每次工具执行 */
export const getMcpAuditLogs = ({ channel, tool, success, page, size } = {}) => {
  const q = new URLSearchParams()
  if (channel) q.set('channel', channel)
  if (tool) q.set('tool', tool)
  if (success !== undefined && success !== null && success !== '') q.set('success', String(success))
  q.set('page', String(page || 1))
  q.set('size', String(size || 20))
  return request('/mcp/audit/logs?' + q.toString())
}
export const getMcpAuditSummary = () => request('/mcp/audit/summary')

/** 定时任务管理（仅管理员，设置页·定时任务）：ScheduleCenter 运行快照 / 手动触发 / 执行日志 */
export const getScheduleTasks = () => request('/schedule/tasks')
export const triggerScheduleTask = name =>
  request('/schedule/tasks/trigger', { method: 'POST', body: JSON.stringify({ name }) })
export const getScheduleRuns = ({ taskName, success, page, size } = {}) => {
  const q = new URLSearchParams()
  if (taskName) q.set('taskName', taskName)
  if (success !== undefined && success !== null && success !== '') q.set('success', String(success))
  q.set('page', String(page || 1))
  q.set('size', String(size || 20))
  return request('/schedule/runs?' + q.toString())
}
export const clearScheduleRuns = () => request('/schedule/runs', { method: 'DELETE' })

// ---------- P1 执行 Trace 与采样池（运营闭环；管理员） ----------
export const listTraces = ({ kind, page, size, keyword, hasCitation, rating, days, agentId } = {}) => {
  const q = new URLSearchParams()
  if (kind) q.set('kind', kind)
  if (keyword) q.set('keyword', keyword)
  if (hasCitation !== undefined && hasCitation !== null && hasCitation !== '') q.set('hasCitation', String(hasCitation))
  if (rating !== undefined && rating !== null && rating !== '') q.set('rating', String(rating))
  if (days !== undefined && days !== null) q.set('days', String(days))
  if (agentId) q.set('agentId', agentId)
  q.set('page', String(page || 1))
  q.set('size', String(size || 20))
  return request('/trace/list?' + q.toString())
}
export const getChatTrace = id => request(`/trace/chat/${id}`)
export const getWorkflowTrace = runId => request(`/trace/workflow/${runId}`)
export const listTracePool = ({ status, page, size } = {}) => {
  const q = new URLSearchParams()
  if (status) q.set('status', status)
  q.set('page', String(page || 1))
  q.set('size', String(size || 20))
  return request('/trace/pool?' + q.toString())
}
/** 标注并回流评测集：knowledgeIds = 期望命中的知识块（至少 1 个） */
export const labelTraceSample = (id, knowledgeIds, note) =>
  request(`/trace/pool/${id}/label`, { method: 'POST', body: JSON.stringify({ knowledgeIds, note: note || '' }) })
export const dismissTraceSample = id => request(`/trace/pool/${id}/dismiss`, { method: 'POST' })
export const runTraceSampling = () => request('/trace/sample/run', { method: 'POST' })
export const getTraceStats = () => request('/trace/stats')

// ==================== 技能（Skills）（个人资产：每人管自己的技能） ====================
export const listSkills = () => request('/skill/list')
export const getSkillDetail = name => request('/skill/detail?name=' + encodeURIComponent(name))
export const createSkill = body => request('/skill', { method: 'POST', body: JSON.stringify(body) })
export const installSkillFromUrl = (url, name) =>
  request('/skill/install', { method: 'POST', body: JSON.stringify({ url, name }) })
export const setSkillDisabled = (name, disabled) =>
  request(`/skill/${encodeURIComponent(name)}/disabled`, { method: 'PUT', body: JSON.stringify({ disabled }) })
export const deleteSkill = name => request(`/skill/${encodeURIComponent(name)}`, { method: 'DELETE' })
// 内置技能改写（仅管理员）：内容全局生效（所有人读到同一份），删除改写行即恢复随版本分发的默认内容
export const updateBuiltinSkill = (name, content) =>
  request(`/skill/${encodeURIComponent(name)}/builtin`, { method: 'PUT', body: JSON.stringify({ content }) })
export const resetBuiltinSkill = name =>
  request(`/skill/${encodeURIComponent(name)}/builtin`, { method: 'DELETE' })
// 批量：ids 为技能名（dirName）清单；返回 {succeeded:[name], failed:[{id,name,error}]}
export const batchDeleteSkills = ids =>
  request('/skill/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })
export const batchSetSkillsDisabled = (ids, disabled) =>
  request('/skill/batch-disabled', { method: 'POST', body: JSON.stringify({ ids, disabled }) })

// ==================== API 密钥（对外开放问答能力） ====================
export const listApiKeys = () => request('/api-key/list')
export const createApiKey = body => request('/api-key', { method: 'POST', body: JSON.stringify(body) })
export const setApiKeyDisabled = (id, disabled) =>
  request(`/api-key/${id}/disabled`, { method: 'PUT', body: JSON.stringify({ disabled }) })
/** 授权 / 收回该 Key 访问平台级 MCP 入口（/ai/mcp）的资格 */
export const setApiKeyMcp = (id, mcpEnabled) =>
  request(`/api-key/${id}/mcp`, { method: 'PUT', body: JSON.stringify({ mcpEnabled }) })
export const renameApiKey = (id, name) =>
  request(`/api-key/${id}/name`, { method: 'PUT', body: JSON.stringify({ name }) })
export const deleteApiKey = id => request(`/api-key/${id}`, { method: 'DELETE' })

// ==================== 智能体 Agent 配置（4.1：模型/知识库/工具/提示词） ====================
/** 对话页下拉：问答用户可读的精简列表（仅 id/name/description/model/isDefault） */
export const listAvailableAgents = () => request('/agent/available')
/** 对话页技能菜单：未停用技能精简列表（问答用户可读） */
export const listAvailableSkills = () => request('/skill/available')
export const listAgents = () => request('/agent/list')
export const listSubAgents = () => request('/agent/sub')
export const createAgent = body => request('/agent', { method: 'POST', body: JSON.stringify(body) })
export const updateAgent = (id, body) => request(`/agent/${id}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteAgent = id => request(`/agent/${id}`, { method: 'DELETE' })
// 批量删除：逐条判权（内置智能体不可删），返回 {succeeded:[id], failed:[{id,name,error}]}
export const batchDeleteAgents = ids =>
  request('/agent/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })
export const setAgentDefault = id => request(`/agent/${id}/default`, { method: 'POST' })
// 配置版本管理：每次保存落一版快照（人设/知识范围/工具集等调优字段），可查 diff、一键回滚
/** 版本列表（新→旧）：每版 {version,reason,operator,createTime,config,changes:[{field,label,from,to}],current,identical} */
export const listAgentVersions = id => request(`/agent/${id}/versions`)
/** 回滚到历史版本（把该版配置重新应用并落一版新快照，历史不被改写） */
export const rollbackAgent = (id, version) =>
  request(`/agent/${id}/rollback`, { method: 'POST', body: JSON.stringify({ version }) })

// ==================== 资源共享范围（文档 / 智能体 / API Key 同构，空串=清空回落私有：仅自己） ====================
export const updateAgentShare = (id, shareConfig) =>
  request(`/agent/${id}/share`, { method: 'PUT', body: JSON.stringify({ shareConfig: shareConfig || '' }) })
export const updateApiKeyShare = (id, shareConfig) =>
  request(`/api-key/${id}/share`, { method: 'PUT', body: JSON.stringify({ shareConfig: shareConfig || '' }) })

// ==================== 智能体公开分享（发布管理 + 游客通道） ====================
/** 查询智能体的公开分享配置（未发布返回 enabled=false） */
export const getAgentPublish = id => request(`/agent/${id}/publish`)
/** 发布/更新公开分享（enabled + modelRef，modelRef 空=回退发布者个人默认模型） */
export const publishAgent = (id, body) =>
  request(`/agent/${id}/publish`, { method: 'POST', body: JSON.stringify(body) })
/** 撤销公开分享（删行，链接立即失效；重新发布生成新 token） */
export const revokeAgentPublish = id => request(`/agent/${id}/publish`, { method: 'DELETE' })

/** 游客：分享信息（免登录） */
export const getShareInfo = token => request(`/share/${token}/info`)
/** 游客：自己会话的最近历史（刷新恢复用） */
export const getShareHistory = (token, sessionId, visitorId) =>
  request(`/share/${token}/history?sessionId=${encodeURIComponent(sessionId)}&visitorId=${encodeURIComponent(visitorId)}`)
/** 游客：自己在该分享链接下的历史会话（{items:[{id,title,updateTime,messageCount}]}，按更新时间倒序） */
export const listShareSessions = (token, visitorId) =>
  request(`/share/${token}/sessions?visitorId=${encodeURIComponent(visitorId)}`)
/** 游客：清除自己在该链接下的全部会话（公用电脑上问完就抹掉，删了找不回） */
export const clearShareSessions = (token, visitorId) =>
  request(`/share/${token}/sessions?visitorId=${encodeURIComponent(visitorId)}`, { method: 'DELETE' })

/**
 * 游客流式对话（SSE，免登录）：token 即凭据；服务端按发布者身份检索、游客受限工具集。
 * 事件派发与登录态主链路共用 CHAT_EVENT_CB（图片/引用/工具状态等一律不再各认一份）；
 * 差别只在终止事件：done 的信封上带 sessionId（首轮由服务端建会话，前端要存下来续聊）。
 */
export function sendShareMessage(token, payload, opts = {}) {
  const { onDone, onError, signal, idleTimeoutMs = 120000 } = opts
  if (typeof onDone !== 'function' || typeof onError !== 'function') return
  const controller = new AbortController()
  let idleTimer = null
  let idleTimedOut = false
  // 与主链路同因：iOS Safari 切后台会冻结定时器，回前台 setTimeout 立即到期 → 误报超时。
  // 隐藏时停表、回前台补满，而不是放宽阈值
  let idlePaused = false
  const armIdle = () => {
    clearTimeout(idleTimer)
    if (idlePaused) return
    idleTimer = setTimeout(() => { idleTimedOut = true; controller.abort() }, idleTimeoutMs)
  }
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') { idlePaused = true; clearTimeout(idleTimer) }
    else if (idlePaused) { idlePaused = false; armIdle() }
  }
  document.addEventListener('visibilitychange', onVisibility)
  // 统一收尾：摘监听 + 停表。分享链路有 5 个出口（HTTP 失败 / error 事件 / done 事件 /
  // 流读尽 / 读失败），逐个出口清监听必然漏（漏一个就是每次对话泄漏一个监听器）
  const settle = () => { clearTimeout(idleTimer); document.removeEventListener('visibilitychange', onVisibility) }
  if (signal) {
    if (signal.aborted) controller.abort()
    else signal.addEventListener('abort', () => controller.abort())
  }
  fetch(`${BASE}/share/${encodeURIComponent(token)}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal: controller.signal
  }).then(res => {
    if (!res.ok || !res.body) {
      settle()
      res.json().then(d => onError(d?.msg || '请求失败: ' + res.status)).catch(() => onError('请求失败: ' + res.status))
      return
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    const handleEvent = raw => {
      const d = parseChatEvent(raw.split('\n').find(l => l.startsWith('data:')) || '')
      if (!d) return
      armIdle()
      if (d.type === 'done') { settle(); onDone(d); return }
      if (d.type === 'error') { settle(); onError(d.content || '回答失败'); return }
      dispatchChatEvent(d, opts)
    }
    const pump = () => reader.read().then(({ done, value }) => {
      if (done) { settle(); return }
      buf += decoder.decode(value, { stream: true })
      armIdle() // 收到数据（任意字节，含 :keepalive 注释行）即视为存活——与主链路看门狗同语义，否则整轮心跳对分享链路无效
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        handleEvent(buf.slice(0, idx))
        buf = buf.slice(idx + 2)
      }
      return pump()
    })
    armIdle()
    pump().catch(e => {
      settle()
      onError(idleTimedOut ? '回答超时，请重试' : (e.message || '连接中断'))
    })
  }).catch(e => {
    settle()
    if (e.name === 'AbortError') onError('已停止生成')
    else onError(e.message || '网络错误')
  })
}

/** 聊天附件上传（onProgress 接收 0-100 百分比）：返回 {fileId,name,mime,size}，问答请求只带 fileId */
export function uploadChatAttachment(file, onProgress) {
  const fd = new FormData()
  fd.append('file', file)
  return upload('/chat/attachment', fd, onProgress)
}

/** 上传文档（onProgress 接收 0-100 百分比；visionRef 可选=文档级视觉模型覆盖，空=跟随知识库） */
export function uploadDocument(file, description, onProgress, kbId, visionRef) {
  const fd = new FormData()
  fd.append('file', file)
  if (description) fd.append('description', description)
  if (kbId) fd.append('kbId', kbId)
  if (visionRef) fd.append('visionRef', visionRef)
  return upload('/document/upload', fd, onProgress)
}

/** 批量上传（onProgress 接收 0-100 百分比；description/visionRef 可选，应用到所有文件） */
export function uploadDocumentsBatch(files, onProgress, description, kbId, visionRef) {
  const fd = new FormData()
  files.forEach(f => fd.append('file', f))
  if (description) fd.append('description', description)
  if (kbId) fd.append('kbId', kbId)
  if (visionRef) fd.append('visionRef', visionRef)
  return upload('/document/upload/batch', fd, onProgress)
}

/** 网页 URL 导入（后端抓取 HTML 快照入库，fileType=url；抓取耗时较长，超时放宽到 60s） */
export const importDocumentFromUrl = (url, description, kbId) => {
  const q = new URLSearchParams({ url })
  if (description) q.append('description', description)
  if (kbId) q.append('kbId', kbId)
  return request('/document/import-url?' + q.toString(), { method: 'POST', timeout: 60000 })
}

/** 批量重解析 */
export const batchReparseDocuments = ids =>
  request('/document/batch/reparse', { method: 'POST', body: JSON.stringify({ ids }) })

/** 工具执行审批（人在回路）：裁决 approval_required 事件下发的请求；仅本轮用户本人可批 */
export const approveToolCall = (approvalId, approved) =>
  request(`/tool-approval/${encodeURIComponent(approvalId)}`, { method: 'POST', body: JSON.stringify({ approved }) })

/** 智能体提问（人在回路）：回答 ask_user 事件下发的结构化提问；仅本轮用户本人可答，答案作为工具结果回给模型。
 *  answers 为与问题下标对齐的答案数组（一卡多问批量提交）；单问题也可传单元素数组 */
export const answerAgentAsk = (askId, answers) =>
  request(`/ask-user/${encodeURIComponent(askId)}`, { method: 'POST', body: JSON.stringify({ answers }) })

/** 忽略智能体提问：不作答，让智能体带着「这一题用户没回答」继续推进（与超时同语义） */
export const ignoreAgentAsk = askId =>
  request(`/ask-user/${encodeURIComponent(askId)}`, { method: 'POST', body: JSON.stringify({ ignore: true }) })

/** 待答提问（卡片恢复入口）：列本人该会话仍挂起的 askUser 提问卡。
 *  返回 {items:[{askId,questions,timeoutMs,remainingMs,createdAt,expired,live}]}；
 *  live=false 表示唤醒句柄已随进程重启消失，作答送不到智能体 */
export const listPendingAsks = sessionId =>
  request(`/ask-user/pending?sessionId=${encodeURIComponent(sessionId)}`)

/** 执行计划裁决（计划模式，人在回路）：批准可带编辑后的计划全文（空串=按模型原稿执行）；
 *  拒绝=本轮终止。仅本轮用户本人可裁决 */
export const resolvePlanApproval = (planApprovalId, approved, plan = '') =>
  request(`/plan-approval/${encodeURIComponent(planApprovalId)}`, { method: 'POST', body: JSON.stringify({ approved, plan }) })

/** 待批准计划（卡片恢复入口）：列本人该会话仍挂起的执行计划批准卡。
 *  返回 {items:[{planApprovalId,plan,timeoutMs,remainingMs,createdAt,expired,live}]}；
 *  live=false 表示唤醒句柄已随进程重启消失，批准送不到模型 */
export const listPendingPlans = sessionId =>
  request(`/plan-approval/pending?sessionId=${encodeURIComponent(sessionId)}`)

/** 知识块级启停用（status: 0=生效 1=停用，停用后不参与召回） */
export const updateKnowledgeStatus = (id, status) =>
  request(`/knowledge/${id}/status`, { method: 'PUT', body: JSON.stringify({ status }) })

/** 跨文档全局搜索知识块（含已停用，管理端排查用） */
export const searchKnowledge = keyword =>
  request('/knowledge/search?keyword=' + encodeURIComponent(keyword))

/** 文档启停用：status 0 生效 / 1 弃用 */
export const updateDocumentStatus = (id, status) =>
  request(`/document/${id}/status`, { method: 'PUT', body: JSON.stringify({ status }) })

/** 文档重解析 */
export const reparseDocument = id =>
  request(`/document/${id}/reparse`, { method: 'POST' })
export const refreshConfigDocument = (id, autoRefresh, refreshCron) =>
  request(`/document/${id}/refresh-config?autoRefresh=${autoRefresh}${refreshCron ? `&refreshCron=${encodeURIComponent(refreshCron)}` : ''}`, { method: 'PUT' })

/** 文档解析队列状态（排队 / 执行 / 作废计数）：上传只登记任务，解析由后台队列推进。
 *  kbId 传当前库时只统计该库的任务（页头指标不串库），不传=全平台 */
export const getDocumentQueueStats = (kbId) => request('/document/queue/stats' + (kbId ? `?kbId=${encodeURIComponent(kbId)}` : ''))

/** 删除文档 */
export const deleteDocument = id => request(`/document/${id}`, { method: 'DELETE' })

export const updateDocumentShare = (id, shareConfig) =>
  request(`/document/${id}/share`, { method: 'PUT', body: JSON.stringify({ shareConfig: shareConfig || '' }) })
export const listDepartments = () => request('/department/list')
export const listUsers = () => request('/user/list')
export const createDepartment = body => request('/department', { method: 'POST', body: JSON.stringify(body) })
export const updateDepartment = (id, body) => request(`/department/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteDepartment = id => request(`/department/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const createUser = body => request('/user', { method: 'POST', body: JSON.stringify(body) })
export const updateUser = (uid, body) => request(`/user/${encodeURIComponent(uid)}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteUser = uid => request(`/user/${encodeURIComponent(uid)}`, { method: 'DELETE' })

// ==================== RBAC 权限管理（菜单 / 接口 / 角色） ====================
export const listMenus = () => request('/menu/list')
export const createMenu = body => request('/menu', { method: 'POST', body: JSON.stringify(body) })
export const updateMenu = (id, body) => request(`/menu/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteMenu = id => request(`/menu/${encodeURIComponent(id)}`, { method: 'DELETE' })
/** 我的侧栏布局：读 {customized, order, hidden} / 存 {order, hidden, reset}。纯个人偏好，不影响他人 */
export const getMyMenuLayout = () => request('/menu/my-layout')
export const saveMyMenuLayout = body => request('/menu/my-layout', { method: 'PUT', body: JSON.stringify(body) })
export const listApis = () => request('/api/list')
export const createApi = body => request('/api', { method: 'POST', body: JSON.stringify(body) })
export const updateApi = (id, body) => request(`/api/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteApi = id => request(`/api/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const listRoles = () => request('/role/list')
export const listRoleOptions = () => request('/role/options')
export const createRole = body => request('/role', { method: 'POST', body: JSON.stringify(body) })
export const updateRole = (code, body) => request(`/role/${encodeURIComponent(code)}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteRole = code => request(`/role/${encodeURIComponent(code)}`, { method: 'DELETE' })
export const getRolePermissions = code => request(`/role/${encodeURIComponent(code)}/permissions`)
export const saveRolePermissions = (code, menuIds, apiIds) =>
  request(`/role/${encodeURIComponent(code)}/permissions`, { method: 'PUT', body: JSON.stringify({ menuIds, apiIds }) })

/** 提交回答反馈（messageId 关联；rating 1=有帮助 0=没帮助） */
export const submitFeedback = (messageId, rating, feedbackText) =>
  request('/feedback', {
    method: 'POST',
    body: JSON.stringify({ messageId, rating, feedbackText: feedbackText || null })
  })

/** 看板统计 */
export const getAnalytics = () => request('/analytics/summary')

/** 差评样本列表（反馈回流：问题/回答摘要/反馈说明/引用块） */
export const getBadCases = () => request('/analytics/badcases')

// ---------- 使用统计（个人用量；统计卡全时段，range 只影响趋势/模型占比） ----------
export const getUsageStats = range => request(`/stats/usage?range=${range === 7 ? 7 : 30}`)
// 费用报表（管理）：全员台账×模型单价，按用户/按模型两维度；range=7|30
export const getUsageCost = range => request(`/stats/cost?range=${range === 7 ? 7 : 30}`)

// ==================== 用户长期记忆（个人设置页管理；提取与注入在后端自动完成） ====================
/** 我的记忆列表（按更新时间倒序；含来源与被注入次数） */
export const listMyMemories = () => request('/memory')
/** 手动添加记忆（category: fact/instruction/project，默认 fact） */
export const addMyMemory = (content, category) =>
  request('/memory', { method: 'POST', body: JSON.stringify({ content, category }) })
/** 编辑记忆内容（仅本人） */
export const updateMyMemory = (id, content) =>
  request(`/memory/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ content }) })
/** 删除记忆（仅本人；自动提取的也可删） */
export const deleteMyMemory = id => request(`/memory/${encodeURIComponent(id)}`, { method: 'DELETE' })

/** 追加单条评估 case（差评回流：问题 → 引用过的知识块） */
export const addEvalCase = (question, knowledgeIds) =>
  request('/eval/case', { method: 'POST', body: JSON.stringify({ question, knowledgeIds }) })

/** 知识块详情（引用溯源全文） */
export const getKnowledgeDetail = id => request(`/knowledge/${id}`)

/** 批量删除文档 */
export const batchDeleteDocuments = ids =>
  request('/document/batch/delete', { method: 'POST', body: JSON.stringify({ ids }) })

/** 批量启停用文档 */
export const batchUpdateDocumentStatus = (ids, status) =>
  request('/document/batch/status', { method: 'POST', body: JSON.stringify({ ids, status }) })

/** 文档命中次数统计（{docId: count}） */
export const getDocumentStats = () => request('/document/stats')

/** 模型配置：获取全量（分组 + editable 标记） */
export const getConfig = () => request('/config')

// 配置字段定义：设置页渲染与后端保存校验的唯一定义源（config-schema.json 后端下发，前端不再内置副本）
export const getConfigSchema = () => request('/config/schema')

/** 前端运行时配置（文档上传上限/支持格式等，与后端一致） */
export const getRuntimeConfig = () => request('/config/public')

/** 探测重排服务是否可用（设置页开启前校验） */
export const checkRerank = () => request('/config/rerank/check')

/** 探测 Meilisearch 是否可用（设置页切换关键词引擎前校验） */
export const checkKeywordEngine = () => request('/config/keyword/check')

/**
 * 通用连通性探测（设置页各地址旁的「测试连接」按钮）。
 * 用表单里尚未保存的值真实探测，实现"先测后存"；group=chat|vision|embedding|rerank|keyword|sandbox。
 * 返回 { available, latencyMs, detail }。
 */
export const probeConnectivity = payload =>
  request('/config/probe', { method: 'POST', body: JSON.stringify(payload), timeout: 20000 })

/** 关键词索引运维：引擎状态/索引统计 */
export const getSearchIndexStats = () => request('/search-index/stats')

/** 关键词索引运维：全量重建（后台执行） */
export const reindexSearchIndex = () => request('/search-index/reindex', { method: 'POST' })

/** 模型配置：保存可编辑项 {"chat":{...},"vision":{...}} */
export const saveConfig = payload => request('/config', { method: 'PUT', body: JSON.stringify(payload) })

/** 恢复指定配置分组为默认值（不触碰 embedding 组与各模型 API Key） */
export const resetConfig = groups =>
  request('/config/reset', { method: 'POST', body: JSON.stringify({ groups }) })

/** 删除一轮对话（按对话组：回答 ID + 其前面的用户问题一起软删除） */
export const deleteMessageGroup = assistantMessageId =>
  request(`/message-group/${assistantMessageId}`, { method: 'DELETE' })

// 切换消息分支版本（编辑重发/重新生成的历史版本回看）：messageId=当前可见版本的消息 ID，delta=-1|1
// 后端把当前分支软删留档、按快照恢复目标版本；返回 {restored}，前端随后重拉会话历史刷新视图
export const switchMessageVariant = (messageId, delta) =>
  request('/message-group/switch', { method: 'POST', body: JSON.stringify({ messageId, delta }) })

/** 撤销删除一轮对话（撤销期内恢复软删消息） */
export const undoDeleteMessageGroup = assistantMessageId =>
  request('/message-group/undo', { method: 'POST', body: JSON.stringify({ messageId: assistantMessageId }) })

/** 按文档列出知识块（知识块预览） */
export const listKnowledgeByDoc = docId => request('/knowledge/list?docId=' + encodeURIComponent(docId))

/** 获取无命中问题列表（按频次降序） */
export const getUnmatchedQuestions = () => request('/knowledge/unmatched')

/** 新增知识块（手动补充知识库缺口） */
export const createKnowledge = (title, content, docId) =>
  request('/knowledge', {
    method: 'POST',
    body: JSON.stringify({ title, content, docId: docId || null })
  })

/** 编辑知识块（重新向量化） */
export const updateKnowledge = (id, title, content) =>
  request(`/knowledge/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ title, content })
  })

/** 删除知识块 */
export const deleteKnowledge = id => request(`/knowledge/${id}`, { method: 'DELETE' })

/** 文档版本列表 */
export const listDocumentVersions = id => request(`/document/${id}/versions`)

/** 回滚文档到指定版本 */
export const rollbackDocument = (id, version) =>
  request(`/document/${id}/rollback`, { method: 'POST', body: JSON.stringify({ version }) })

/** 检索调试：分步查看关键词/向量/合并/重排/最终上下文/被排除 */
export const debugRetrieval = question =>
  request('/debug/retrieval', { method: 'POST', body: JSON.stringify({ question }) })

/** 检索评估：从历史问答回放生成评估集 */
export const evalGenerate = maxCases =>
  request('/eval/generate', { method: 'POST', body: JSON.stringify({ maxCases }) })

/** 检索评估：读取当前评估集 */
export const getEvalSet = () => request('/eval/set')

/** 检索评估：批量参数组对比运行 */
export const runEvaluation = payload =>
  request('/eval/run', { method: 'POST', body: JSON.stringify(payload) })

/** 检索评估：最近一次自动体检报告（无则 null） */
export const getEvalLastReport = () => request('/eval/last-report')

/** 检索评估：立即自动体检（按线上参数全量跑，耗时数十秒） */
export const runEvalAutoCheck = () => request('/eval/run-auto', { method: 'POST', timeout: 300000 })

/** 身份与权限：返回 { user, admin }——admin=true 时前端展示文档/看板/评估/设置等管理入口 */
export const getAuthMe = () => request('/auth/me')

// ---- 登录鉴权（本地登录） ----
export const getFirstRun = () => request('/auth/first-run')
export const loginApi = (identifier, password) =>
  request('/auth/login', { method: 'POST', body: JSON.stringify({ identifier, password }) })
export const initializeAdmin = (uid, username, password) =>
  request('/auth/initialize', { method: 'POST', body: JSON.stringify({ uid, username, password }) })
export const logoutApi = () => request('/auth/logout', { method: 'POST' })

// ---- 单点登录（OIDC）：登录页探测 → 取授权地址 → 回调页兑换一次性 code ----
// 三者都在「还没有本系统登录令牌」阶段调用（后端已放行登录门禁）。
export const getOidcConfig = () => request('/auth/oidc/config')
export const getOidcLoginUrl = redirectPath =>
  request(`/auth/oidc/login-url?redirectPath=${encodeURIComponent(redirectPath || '/')}`)
export const exchangeOidcCode = code =>
  request('/auth/oidc/exchange-code', { method: 'POST', body: JSON.stringify({ code }) })
export const changePasswordApi = (oldPassword, newPassword) =>
  request('/auth/password', { method: 'POST', body: JSON.stringify({ oldPassword, newPassword }) })
export const resetUserPassword = (uid, password) =>
  request(`/user/${encodeURIComponent(uid)}/password`, { method: 'PUT', body: JSON.stringify({ password }) })

// ---- 模型供应商（供应商管理页 + 各处模型选择器） ----
export const listProviders = () => request('/provider')
export const createProvider = body => request('/provider', { method: 'POST', body: JSON.stringify(body) })
export const updateProvider = (id, body) => request(`/provider/${id}`, { method: 'PUT', body: JSON.stringify(body) })
export const setProviderEnabled = (id, enabled) =>
  request(`/provider/${id}/enabled`, { method: 'PUT', body: JSON.stringify({ enabled }) })
export const deleteProvider = id => request(`/provider/${id}`, { method: 'DELETE' })
// 批量：逐条判权（仅自己登记的可管理），返回 {succeeded:[id], failed:[{id,name,error}]}
export const batchDeleteProviders = ids =>
  request('/provider/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })
export const batchSetProvidersEnabled = (ids, enabled) =>
  request('/provider/batch-enabled', { method: 'POST', body: JSON.stringify({ ids, enabled }) })
export const listProviderModels = id => request(`/provider/${id}/models`)
export const saveProviderModels = (id, models) =>
  request(`/provider/${id}/models`, { method: 'PUT', body: JSON.stringify(models) })
/** 远程拉取网关模型列表（候选，不入库）；编辑已存供应商时 apiKey 可传掩码（后端用库中真实 Key） */
export const fetchProviderModels = (baseUrl, apiKey, providerId) =>
  request('/provider/models/fetch', { method: 'POST', body: JSON.stringify({ baseUrl, apiKey, providerId }), timeout: 20000 })
/** 供应商连通性测试（先测后存）；modelType 决定探测方式（chat/vision/embedding/rerank/audio/omni）。
 *  探测成功会顺带清除该供应商的「额度不足」标记（用户充值/换 Key 后测一次即恢复可选）。 */
export const testProvider = body =>
  request('/provider/test', { method: 'POST', body: JSON.stringify(body), timeout: 20000 })
/** 供应商被引用清单：定位「这个模型在哪些地方被用着」。
 *  返回 {total, editableCount, hasBlocking, byKind, items:[{kind,label,name,id,hint,editable}]} */
export const listProviderReferences = id => request(`/provider/${encodeURIComponent(id)}/references`)
/** 可用模型清单（登录即可用；type 过滤如 chat/vision/embedding/rerank，期望 chat 时全模态 omni 一并入选）：[{providerId,name,icon,models:[{ref,modelId,displayName,type}]}]。
 *  此处解包信封直接返回数组（ModelSelect/modelRef 两处消费方都按数组用，漏解包会静默变空态） */
export const listAvailableModels = async (type = '') => {
  const r = await request('/provider/available' + (type ? '?type=' + encodeURIComponent(type) : ''))
  return (r && r.success && Array.isArray(r.data)) ? r.data : []
}

// ---- 个人偏好（个人设置：默认模型 / 记忆开关 / 昵称） ----
export const getUserPreference = () => request('/user/preference')
export const setUserPreference = payload =>
  request('/user/preference', {
    method: 'PUT',
    body: JSON.stringify(typeof payload === 'string' ? { defaultModel: payload } : (payload || {}))
  })
// 自助修改个人资料（仅本人；uid 不可改）：username/avatar 各自缺省=不修改，avatar 空串=清除头像
export const updateMyProfile = (username, avatar) => {
  const body = { username }
  if (avatar !== undefined) body.avatar = avatar
  return request('/user/profile', { method: 'PUT', body: JSON.stringify(body) })
}
// 上传头像（multipart；仅本人）：返回 { url }（/ai/images/avatar/...）
// 走 upload()（XHR）而非 request()：request() 强制 Content-Type: application/json 会破坏 multipart 解析
export const uploadAvatarApi = file => {
  const fd = new FormData()
  fd.append('file', file)
  return upload('/user/avatar', fd)
}

// ---- 个人对话偏好（个人设置 → 对话偏好；键为 config-schema.json 标记 personal 的字段） ----
/** 读取：{fields, tips, values（本人个人值）, globals（系统全局值，界面「跟随系统」参照）} */
export const getUserSettings = () => request('/user/settings')
/** 保存：扁平的 {配置键: 值}；空串=清除该项回落全局 */
export const saveUserSettings = values =>
  request('/user/settings', { method: 'PUT', body: JSON.stringify(values || {}) })

// ---- 沙盒工作区浏览（右栏「沙盒」卡）：只读、不创建容器；下载走字节流（带令牌 fetch 后本地保存） ----
export const sandboxState = sessionId =>
  request(`/sandbox/state?sessionId=${encodeURIComponent(sessionId || '')}`, { timeout: 15000 })
export const sandboxTree = (sessionId, path) =>
  request(`/sandbox/tree?sessionId=${encodeURIComponent(sessionId || '')}${path ? '&path=' + encodeURIComponent(path) : ''}`, { timeout: 20000 })
/** 下载沙盒文件：带 Authorization 的 fetch → Blob（浏览器直接 window.open 不带令牌，会 401） */
export const sandboxDownload = async (sessionId, path, filename) => {
  const res = await fetch(`${BASE}/sandbox/download?sessionId=${encodeURIComponent(sessionId || '')}&path=${encodeURIComponent(path || '')}`,
    { headers: { Authorization: 'Bearer ' + authToken() } })
  if (res.status === 401) window.dispatchEvent(new CustomEvent('app:unauthorized'))
  if (!res.ok) throw new Error(`下载失败(${res.status})`)
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || (path.includes('/') ? path.slice(path.lastIndexOf('/') + 1) : path) || 'download'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
/** 回答内代码块「在沙盒中运行」：与 execute 工具同一条物理通道；语言白名单/超时上限由后端把关 */
export const sandboxRun = (sessionId, language, code) =>
  request('/sandbox/run', { method: 'POST', timeout: 90000, body: JSON.stringify({ sessionId, language, code }) })

// ---------- P1 GraphRAG（知识图谱；管理员） ----------
export const graphBuild = kbId => request(`/graph/${kbId}/build`, { method: 'POST' })
export const graphStatus = kbId => request(`/graph/${kbId}/status`)
export const graphTriples = (kbId, page = 1, size = 20) => request(`/graph/${kbId}/triples?page=${page}&size=${size}`)
export const graphClear = kbId => request(`/graph/${kbId}`, { method: 'DELETE' })
// ---------- §5 图谱可视化（聚合视图 / 实体溯源 / 搜索定位） ----------
export const graphViewData = (kbId, limit = 300) => request(`/graph/${kbId}/view?limit=${limit}`)
export const graphEntityDetail = (kbId, entityId) => request(`/graph/${kbId}/entity/${entityId}`)
export const graphNeighbor = (kbId, entityId) => request(`/graph/${kbId}/neighbor/${entityId}`)
export const graphSearchEntity = (kbId, q) => request(`/graph/${kbId}/search?q=${encodeURIComponent(q)}`)
export const graphChunk = (kbId, chunkId) => request(`/graph/${kbId}/chunk/${chunkId}`)

// ---------- 站内通知（铃铛；解析/工作流/网页源刷新等异步事件的用户可感知面） ----------
// list 支持筛选/游标分页：{limit, type, unreadOnly, cursor}；返回 {items,nextCursor,hasMore,total,unreadCount}
export const notificationList = ({ limit = 50, type, unreadOnly, cursor = 0 } = {}) => {
  const q = new URLSearchParams()
  q.set('limit', String(limit))
  if (type) q.set('type', type)
  if (unreadOnly) q.set('unreadOnly', 'true')
  if (cursor) q.set('cursor', String(cursor))
  return request(`/notification/list?${q.toString()}`)
}
export const notificationUnreadCount = () => request('/notification/unread-count')
export const notificationCounts = () => request('/notification/counts')
export const notificationMarkRead = ids => request('/notification/read', { method: 'POST', body: JSON.stringify({ ids }) })
export const notificationMarkAllRead = () => request('/notification/read-all', { method: 'POST' })
export const notificationDelete = ids => request('/notification/delete', { method: 'POST', body: JSON.stringify({ ids }) })
export const notificationClear = scope => request('/notification/clear', { method: 'POST', body: JSON.stringify({ scope }) })
export const notificationPreferences = () => request('/notification/preferences')
export const notificationSavePreferences = mutedTypes => request('/notification/preferences', { method: 'PUT', body: JSON.stringify({ mutedTypes }) })
// 工具审批恢复：按 approvalId 取本人审批记录（tool.approval 通知点击后重建审批卡）
// （此前误写 /chat/tool-approval/，后端实际映射 /api/ai/tool-approval/{id}，恢复查询一直 404）
export const getToolApproval = id => request(`/tool-approval/${encodeURIComponent(id)}`)
