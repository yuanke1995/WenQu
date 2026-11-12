// 工具卡的展示口径：中文名、悬停说明、耗时、运行态、已执行计数。
// 首选后端下发的 label/hint（工具注册中心描述符），下面的表只在载荷没带名字时兜底。

const TOOL_LABELS = {
  searchKnowledge: '检索知识库',
  search_knowledge: '检索知识库',
  presentArtifact: '生成产物',
  deliver_artifact: '交付产物',
  askUser: '向用户提问',
  readSkill: '读取技能正文',
  webSearch: '联网搜索',
  execute: '执行命令',
  read_file: '读取文件',
  write_file: '写入文件',
  edit_file: '编辑文件',
  grep_files: '检索内容',
  glob_files: '按模式查找文件',
  list_files: '列出目录',
  ls: '列出目录'
}

const TOOL_DESCS = {
  searchKnowledge: '在你上传的知识库里检索相关资料片段',
  presentArtifact: '把内容整理成可下载的文件',
  deliver_artifact: '把生成的文件存进会话，可查看下载',
  askUser: '向你提出问题（可多选的题会标出），点选或输入答案后继续回答',
  readSkill: '按需加载某个技能的详细说明',
  webSearch: '上网搜索相关资料，结果会作为引用来源',
  execute: '在隔离的执行环境里跑一条命令，把输出带回来',
  read_file: '读取文件内容',
  write_file: '新建或覆盖文件',
  edit_file: '替换文件里的某段内容',
  grep_files: '按关键词在文件里搜，跨很多文件一起找',
  glob_files: '按文件名规律找文件，比如「所有 .md」',
  list_files: '列出某个目录下有哪些文件',
  ls: '列出某个目录下的文件'
}

// 与 McpClientService 的 clientInfo name 对应；只认现行前缀 w_q_，历史值不做兼容
const MCP_CLIENT_PREFIX = 'w_q_'

export { TOOL_LABELS, TOOL_DESCS }

export const bareToolName = n => n.startsWith(MCP_CLIENT_PREFIX) ? n.slice(MCP_CLIENT_PREFIX.length) : n

export const toolLabel = (n, label) => {
  // 后端下发的展示名优先；但它只是工具裸名时不算展示名（否则英文裸名会顶掉本地映射）
  if (label && label !== n && label !== bareToolName(n)) return label
  if (TOOL_LABELS[n]) return TOOL_LABELS[n]
  const bare = bareToolName(n)
  return TOOL_LABELS[bare] || label || bare
}

export const toolDesc = (n, hint) => (hint || TOOL_DESCS[n] || TOOL_DESCS[bareToolName(n)] || '')

/** 「执行了 N 个操作」只数真执行过的：被守卫挡下（denied）的工具体一次没跑。 */
export const executedCount = list => (Array.isArray(list) ? list : []).filter(t => t && !t.denied).length

export const toolDuration = ms => {
  if (ms < 1000) return ms + 'ms'
  const tenths = Math.round(ms / 100)
  if (tenths < 600) return (tenths / 10).toFixed(1) + 's'
  const secs = Math.round(tenths / 10)
  if (secs < 3600) {
    const s = secs % 60
    return Math.floor(secs / 60) + 'm' + (s ? s + 's' : '')
  }
  const m = Math.round(secs / 60) % 60
  return Math.floor(Math.round(secs / 60) / 60) + 'h' + (m ? m + 'm' : '')
}

/** 是否有正在执行的工具（沙盒命令/MCP 可长时间阻塞）：执行中不显示裸 spin，并实时计时。 */
export const toolRunning = m => Array.isArray(m?.toolCalls) && m.toolCalls.some(t => t.status === 'start')
