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
 * 通用 JSON 请求：超时控制 + 401/业务失败统一抛出 Error(message)
 */
async function request(path, { method = 'GET', body, timeout = 30000 } = {}) {
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
      if (res.status === 403) window.dispatchEvent(new CustomEvent('app:forbidden', { detail: data?.msg }))
      throw new Error(data?.msg || `请求失败(${res.status})`)
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
 * 流式聊天（SSE）
 * signal 用于停止生成（外部 AbortController.abort()）
 * deepThink=true 时后端先流式输出思考过程（thinking / thinking_done 事件）
 * idleTimeoutMs：读流空闲看门狗——服务端排队/挂起超过该时长未推任何数据即中断并报错（默认 120s），
 * 避免 UI 永久转圈；收到任意数据自动重置计时。
 */
export function sendQuestion(sessionId, question, images = [], opts = {}) {
  const {
    onToken, onImage, onDone, onError, onThinking, onThinkingDone, onWarn, onStage, onRetrieved, onArtifact, onToolStatus, onSubagent, onSubagentRoute,
    deepThink = false, signal, idleTimeoutMs = 120000, refs = [], agentId = ''
  } = opts
  if (typeof onError !== 'function' || typeof onDone !== 'function') return

  const inner = new AbortController()
  let idleTimedOut = false
  let idleTimer = null
  const armIdle = () => {
    clearTimeout(idleTimer)
    idleTimer = setTimeout(() => {
      idleTimedOut = true
      inner.abort()
    }, idleTimeoutMs)
  }
  const stopIdle = () => clearTimeout(idleTimer)
  if (signal) {
    if (signal.aborted) inner.abort()
    else signal.addEventListener('abort', () => inner.abort())
  }

  fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ sessionId, question, images, deepThink, refs, agentId: agentId || '' }),
    signal: inner.signal
  }).then(res => {
    if (!res.ok) {
      stopIdle()
      if (res.status === 401) window.dispatchEvent(new CustomEvent('app:unauthorized'))
      res.json().then(d => onError(d?.msg || '请求失败: ' + res.status)).catch(() => onError('请求失败: ' + res.status))
      return
    }
    armIdle()
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let ended = false
    const end = err => {
      if (ended) return
      ended = true
      stopIdle()
      if (err) onError(err)
      else onDone()
    }
    const read = () => {
      reader.read().then(({ done, value }) => {
        if (done) { end(); return }
        armIdle() // 收到数据（任意字节）即视为存活
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          // Spring SseEmitter 输出 "data:{...}"（冒号后无空格），需兼容带/不带空格两种
          if (line.startsWith('data:')) {
            try {
              const d = JSON.parse(line.substring(5).trim())
              if (d.type === 'token') { onToken(d.content) }
              else if (d.type === 'stage') { onStage && onStage(d.content) }
              else if (d.type === 'retrieved') { onRetrieved && onRetrieved(d.content) }
              else if (d.type === 'thinking') { onThinking && onThinking(d.content) }
              else if (d.type === 'thinking_done') { onThinkingDone && onThinkingDone(d.content) }
              else if (d.type === 'image') { onImage(d.content) }
              else if (d.type === 'warn') { onWarn && onWarn(d.content) }
              else if (d.type === 'artifact') { onArtifact && onArtifact(d.content) } // content 为 {url,filename,description}
              else if (d.type === 'tool_status') { onToolStatus && onToolStatus(d.content) } // content 为 {name,status,elapsedMs,args,result|error}
              else if (d.type === 'subagent') { onSubagent && onSubagent(d.content) } // content 为 {id,name,status,hits,elapsedMs,delegated,description,digest}
              else if (d.type === 'subagent_route') { onSubagentRoute && onSubagentRoute(d.content) } // content 为 {candidates,picked,names}
              else if (d.type === 'done') { end(); onDone(d.content); return } // content 为 {sources,related,degradations} JSON 字符串
              else if (d.type === 'error') { end(); onError(d.content); return }
            } catch (e) {
              console.warn('[SSE] JSON 解析失败，已忽略该行:', e.message)
            }
          }
        }
        read()
      }).catch(e => {
        // 空闲看门狗超时按"可重试错误"上报；用户主动停止（signal abort）按正常结束处理
        if (idleTimedOut) end('长时间未收到响应，连接已中断，请重试')
        else if (e.name === 'AbortError') end()
        else end('读取失败: ' + e.message)
      })
    }
    read()
  }).catch(e => {
    if (e.name === 'AbortError') {
      if (idleTimedOut) onError('长时间未收到响应，连接已中断，请重试')
      else onDone() // 用户主动停止，按正常结束处理
    } else {
      onError('请求失败: ' + e.message)
    }
  })
}

/** 新建会话 */
export const newSession = () => request('/session/new', { method: 'POST' })

/** 获取会话历史 */
export const getHistory = sid => request(`/session/${sid}`)

/** 清除会话（Redis 缓存） */
export const clearSession = sid => request(`/session/${sid}`, { method: 'DELETE' })

/** 列出所有会话（支持 keyword 按标题/消息内容模糊搜索） */
export const listSessions = (keyword = '') =>
  request('/sessions' + (keyword ? '?keyword=' + encodeURIComponent(keyword) : ''))

/** 置顶/取消置顶会话 */
export const pinSession = (sid, pinned) =>
  request(`/session/${sid}/pin`, { method: 'PUT', body: JSON.stringify({ pinned }) })

/** 收藏/取消收藏会话 */
export const favoriteSession = (sid, favorite) =>
  request(`/session/${sid}/favorite`, { method: 'PUT', body: JSON.stringify({ favorite }) })

/** 重命名会话 */
export const renameSessionApi = (sid, title) =>
  request(`/session/${sid}/rename`, { method: 'PUT', body: JSON.stringify({ title }) })

/** 删除会话（MySQL 软删除 + Redis 清理） */
export const deleteSessionApi = sid => request(`/session/${sid}`, { method: 'DELETE' })

/** 清空所有会话 */
export const clearAllSessionsApi = () => request('/sessions', { method: 'DELETE' })

/** 批量删除会话（按 ID 列表，软删除） */
export const batchDeleteSessionsApi = ids => request('/sessions/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) })

/** 文档列表 */
export const listDocuments = () => request('/document/list')

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

// ==================== MCP 外部工具（设置页管理界面） ====================
export const getMcpStatus = () => request('/mcp/status')
export const reloadMcp = () => request('/mcp/reload', { method: 'POST' })
export const probeMcp = (url, type) => request('/mcp/probe', { method: 'POST', body: JSON.stringify({ url, type }) })

// ==================== 技能（Skills） ====================
export const listSkills = () => request('/skill/list')
export const getSkillDetail = name => request('/skill/detail?name=' + encodeURIComponent(name))
export const createSkill = body => request('/skill', { method: 'POST', body: JSON.stringify(body) })
export const installSkillFromUrl = (url, name) =>
  request('/skill/install', { method: 'POST', body: JSON.stringify({ url, name }) })
export const setSkillDisabled = (name, disabled) =>
  request(`/skill/${encodeURIComponent(name)}/disabled`, { method: 'PUT', body: JSON.stringify({ disabled }) })
export const deleteSkill = name => request(`/skill/${encodeURIComponent(name)}`, { method: 'DELETE' })

// ==================== API Key 管理（对外开放问答能力） ====================
export const listApiKeys = () => request('/api-key/list')
export const createApiKey = body => request('/api-key', { method: 'POST', body: JSON.stringify(body) })
export const setApiKeyDisabled = (id, disabled) =>
  request(`/api-key/${id}/disabled`, { method: 'PUT', body: JSON.stringify({ disabled }) })
export const renameApiKey = (id, name) =>
  request(`/api-key/${id}/name`, { method: 'PUT', body: JSON.stringify({ name }) })
export const deleteApiKey = id => request(`/api-key/${id}`, { method: 'DELETE' })

// ==================== 智能体 Agent 配置（4.1：模型/知识库/工具/提示词） ====================
/** 对话页下拉：问答用户可读的精简列表（仅 id/name/description/model/isDefault） */
export const listAvailableAgents = () => request('/agent/available')
export const listAgents = () => request('/agent/list')
export const listSubAgents = () => request('/agent/sub')
export const createAgent = body => request('/agent', { method: 'POST', body: JSON.stringify(body) })
export const updateAgent = (id, body) => request(`/agent/${id}`, { method: 'PUT', body: JSON.stringify(body) })
export const deleteAgent = id => request(`/agent/${id}`, { method: 'DELETE' })
export const setAgentDefault = id => request(`/agent/${id}/default`, { method: 'POST' })

// ==================== 资源共享范围（文档 / 智能体 / API Key 同构，空串=清空回落全局） ====================
export const updateAgentShare = (id, shareConfig) =>
  request(`/agent/${id}/share`, { method: 'PUT', body: JSON.stringify({ shareConfig: shareConfig || '' }) })
export const updateApiKeyShare = (id, shareConfig) =>
  request(`/api-key/${id}/share`, { method: 'PUT', body: JSON.stringify({ shareConfig: shareConfig || '' }) })

/** 上传文档（onProgress 接收 0-100 百分比） */
export function uploadDocument(file, description, onProgress) {
  const fd = new FormData()
  fd.append('file', file)
  if (description) fd.append('description', description)
  return upload('/document/upload', fd, onProgress)
}

/** 批量上传（onProgress 接收 0-100 百分比；description 可选，应用到所有文件） */
export function uploadDocumentsBatch(files, onProgress, description) {
  const fd = new FormData()
  files.forEach(f => fd.append('file', f))
  if (description) fd.append('description', description)
  return upload('/document/upload/batch', fd, onProgress)
}

/** 批量重解析 */
export const batchReparseDocuments = ids =>
  request('/document/batch/reparse', { method: 'POST', body: JSON.stringify({ ids }) })

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

/** 前端运行时配置（文档上传上限/支持格式等，与后端一致） */
export const getRuntimeConfig = () => request('/config/public')

/** 探测重排服务是否可用（设置页开启前校验） */
export const checkRerank = () => request('/config/rerank/check')

/** 探测 Meilisearch 是否可用（设置页切换关键词引擎前校验） */
export const checkKeywordEngine = () => request('/config/keyword/check')

/**
 * 通用连通性探测（设置页各地址旁的「测试连接」按钮）。
 * 用表单里尚未保存的值真实探测，实现"先测后存"；group=chat|vision|embedding|rerank|keyword。
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

/** 向量模型全量重嵌入：任务状态（status/total/done/failed） */
export const getReembedStatus = () => request('/config/embedding/reindex')

/** 向量模型全量重嵌入：手动触发（切换向量模型后后端自动触发，失败可重试） */
export const triggerReembed = () => request('/config/embedding/reindex', { method: 'POST' })

/** 推荐问题列表（欢迎页展示，DB 配置） */
export const getSuggested = () => request('/suggested')

/** 加入推荐问题（看板热门问题一键加入；返回更新后列表） */
export const addSuggested = question =>
  request('/suggested', { method: 'POST', body: JSON.stringify({ question }) })

/** 删除一轮对话（按对话组：回答 ID + 其前面的用户问题一起软删除） */
export const deleteMessageGroup = assistantMessageId =>
  request(`/message-group/${assistantMessageId}`, { method: 'DELETE' })

/** 撤销删除一轮对话（撤销期内恢复软删消息） */
export const undoDeleteMessageGroup = assistantMessageId =>
  request('/message-group/undo', { method: 'POST', body: JSON.stringify({ messageId: assistantMessageId }) })

/** 答案缓存统计（条数/阈值/开关） */
export const getAnswerCacheStats = () => request('/answer-cache')

/** 清空答案缓存 */
export const clearAnswerCache = () => request('/answer-cache', { method: 'DELETE' })

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
export const changePasswordApi = (oldPassword, newPassword) =>
  request('/auth/password', { method: 'POST', body: JSON.stringify({ oldPassword, newPassword }) })
export const resetUserPassword = (uid, password) =>
  request(`/user/${encodeURIComponent(uid)}/password`, { method: 'PUT', body: JSON.stringify({ password }) })
