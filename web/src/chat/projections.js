// ==================== 聊天页投影层（纯函数与常量） ====================
// 从 ChatPage.vue（M1 引擎抽取）搬出的「入参之外不读任何 .value」的纯函数与常量：
// 工具名映射、时间线视图、工具卡片、编排卡片、来源分组与各处格式化。
// 模块内唯一的响应式状态是 nowTick（运行中工具的实时耗时 tick），自产自销；
// 组件卸载时由消费方在 onUnmounted 里调 stopTick() 收尾。
import { ref } from 'vue'
import { chatStreams } from '../views/store'
// 工具名小白向展示：标签用「动词短语」而不是术语（如 统计字数 / 查阅官方文档），
// 内置工具与后端 ToolInventoryService.LABELS 同源但口径更口语；MCP 工具名是用户登记的
// server 动态合入的（带 clientInfo 前缀），只映射常用 server 的常用工具（context7 / deepwiki），
// 未映射的去掉前缀原样展示，不臆造翻译。
const TOOL_LABELS = {
  searchKnowledge: '查知识库资料',
  presentArtifact: '生成文件',
  deliver_artifact: '保存生成的文件',
  calculate: '做算术计算',
  currentDateTime: '查看当前时间',
  daysBetween: '算日期相差几天',
  addDays: '推算日期',
  randomNumber: '生成随机数',
  uuid: '生成随机编号',
  unitConvert: '单位换算',
  textStats: '统计字数',
  base64: 'Base64 转码',
  hash: '计算哈希值',
  askUser: '向用户提问',
  readSkill: '读取技能说明',
  webSearch: '联网搜索',
  execute: '运行命令',
  read_file: '读取文件',
  write_file: '写入文件',
  edit_file: '编辑文件',
  ls: '查看文件列表',
  // MCP（context7）：去前缀后按裸名匹配
  resolve_library_id: '查找文档来源',
  query_docs: '查阅官方文档',
  // MCP（deepwiki）
  read_wiki_structure: '查看 Wiki 目录',
  read_wiki_contents: '阅读 Wiki 内容',
  ask_wiki_question: '向 Wiki 提问',
  // deepwiki 私有模式 / 按需注册的工具，公网端点默认不出现，登记私有实例时才会命中
  list_wiki_repos: '查看可用 Wiki 仓库',
  generate_wiki: '生成代码 Wiki'
}
// 悬停一句话说明（这个工具到底在干什么；未收录的不显示 title）
const TOOL_DESCS = {
  searchKnowledge: '在你上传的知识库里检索相关资料片段',
  presentArtifact: '把内容整理成可下载的文件',
  deliver_artifact: '把云端沙盒里生成的文件存进会话，可查看下载',
  calculate: '精确计算算式，避免模型心算出错',
  currentDateTime: '获取今天的日期与时间',
  daysBetween: '计算两个日期之间相差多少天',
  addDays: '从某个日期加/减 N 天，得到新日期',
  randomNumber: '在指定范围内生成一个随机数',
  uuid: '生成一个不会重复的随机编号（UUID）',
  unitConvert: '长度、重量、温度等单位互相换算',
  textStats: '统计文本的字数、行数、段落数等',
  base64: '在文本与 Base64 编码之间互相转换',
  hash: '给文本算一个「指纹」，用于校验内容是否被改过',
  askUser: '向你提出选项问题，点选或输入答案后继续回答',
  readSkill: '按需加载某个技能的详细说明',
  webSearch: '上网搜索相关资料，结果会作为引用来源',
  execute: '在隔离的云端沙盒里执行命令行（不影响本机）',
  read_file: '在云端沙盒里读取文件内容',
  write_file: '在云端沙盒里新建或覆盖文件',
  edit_file: '在云端沙盒里修改文件内容',
  ls: '列出云端沙盒里某个目录下的文件',
  resolve_library_id: '先确定要查阅哪个库的官方文档',
  query_docs: '到对应库的官方文档里查找相关内容',
  read_wiki_structure: '浏览开源项目 Wiki 的目录结构',
  read_wiki_contents: '阅读开源项目 Wiki 的具体内容',
  ask_wiki_question: '就开源项目 Wiki 的内容提问并取回答案',
  list_wiki_repos: '列出当前账号在 DeepWiki 上已建索引的仓库',
  generate_wiki: '为指定仓库生成一份代码 Wiki 文档'
}
// ⚠️ 与 McpClientService 的 clientInfo name 对应。Spring AI 的 SyncMcpToolCallback 用
// clientInfo.name 经 McpToolUtils.prefixedToolName → shorten() 生成前缀：非字母数字段丢弃、
// 小写、用_ 连接、无分隔符。clientInfo.name="wen-qu" ⇒ 前缀 "wen_qu"（2026-10-05 反编译 1.1.8 核实，
// 原注释写的 w_q_ 是错的，导致剥前缀失效、MCP 工具名全部落到「裸名原样展示」）。
// w_q_ 保留兼容：历史记录里若已有按旧口径写入的名字，仍能剥掉。
const MCP_CLIENT_PREFIXES = ['wen_qu', 'w_q_']
const bareToolName = n => {
  const pre = MCP_CLIENT_PREFIXES.find(p => n.startsWith(p))
  return pre ? n.slice(pre.length) : n
}
const toolLabel = n => {
  if (TOOL_LABELS[n]) return TOOL_LABELS[n]
  // MCP 工具记录名带 clientInfo 前缀：先去前缀再试一次映射，未映射的展示裸名
  const bare = bareToolName(n)
  return TOOL_LABELS[bare] || bare
}
const toolDesc = n => TOOL_DESCS[n] || TOOL_DESCS[bareToolName(n)] || ''
const toolCallsView = list => {
  if (!Array.isArray(list)) return []
  return list.filter(t => !(t.status === 'start' && list.some(x => x !== t && x.name === t.name && x.status !== 'start')))
}
// ==================== askUser（向用户提问）问答记录 ====================
// 工具入参 args 为 {questions:[{topic?,question,options}]} 或旧式 {topic?,question,options} JSON 字符串；
// result=批量答案：多问题为与问题下标对齐的 JSON 数组字符串，单问题退化为纯文本
// （超时/忽略/留空的题回一句「（用户未回答这一题…」，本模块渲染成「未作答」，见 askAnswerLabel）。
//
// 两个必须收口的渲染口径（否则会出现「有问无答」的残缺问）：
//  ① answers 非数组 ⇒ 这张卡根本没发出去（超限被拒 / 提问次数用尽 / 无有效问题），
//     result 是一句说明而不是答案——此时按单条说明渲染，绝不按问题逐条铺开空答。
//  ② answers 数组比问题短 ⇒ 历史数据里后端曾静默截断过（现已改为整卡拒绝），只渲染有答案的前 N 问。
// 未作答的题回给模型的是一句处置指令（前缀由后端 buildCombinedAnswer 固定），卡片里收成「未作答」，
// 不把模型口径的长句原样摊给用户
const UNANSWERED_PREFIX = '（用户未回答这一题'
const askAnswerLabel = a => (typeof a === 'string' && a.startsWith(UNANSWERED_PREFIX)) ? '未作答' : a
const askUserView = t => {
  let topic = '', question = '', options = null, questions = null
  try {
    const j = JSON.parse(t?.args || 'null')
    if (j && Array.isArray(j.questions) && j.questions.length) {
      questions = j.questions.map(x => ({
        topic: x.topic || '',
        question: x.question || '',
        options: Array.isArray(x.options) ? x.options : null
      }))
    } else if (j) {
      topic = j.topic || ''
      question = j.question || ''
      options = Array.isArray(j.options) ? j.options : null
    }
  } catch (e) { /* 入参截断/旧数据：仅显示答案 */ }
  const raw = (t && (t.result || t.output)) || ''
  // 多问题答案：尝试解析为 JSON 数组（与 questions 下标对齐）
  let answers = null
  if (questions) {
    try { const p = JSON.parse(raw); if (Array.isArray(p)) answers = p } catch (e) { /* 非数组：按单问处理 */ }
  }
  if (questions) {
    // ① 没发出去的卡：result 是说明文本，按单条展示
    if (!answers) return { topic: '', question: '', options: null, answer: raw, notice: true }
    // ② 截断过的历史数据：只渲染有答案的部分
    const list = questions.slice(0, answers.length).map((q, i) => ({
      topic: q.topic, question: q.question, options: q.options,
      // 多问答案=JSON 数组；单问（questions 仅 1 项）= 纯文本，解析失败回退到 raw
      answer: askAnswerLabel(i < answers.length ? answers[i] : (questions.length === 1 ? raw : ''))
    }))
    return { multi: true, questions: list }
  }
  return { topic, question, options, answer: askAnswerLabel(raw) }
}
/** 耗时文案：<1s 用 ms，1~60s 留一位小数，≥60s 换成「3m46s」，≥60m 再换成「1h2m」——226.0s 这种长小数读不出量级 */
const toolDuration = ms => {
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
// 是否有正在执行的工具（沙盒命令/MCP 可长时间阻塞）：执行中不显示裸 spin，并在工具条实时计时
const toolRunning = m => Array.isArray(m?.toolCalls) && m.toolCalls.some(t => t.status === 'start')
// 是否有「正在运行且可见」的编排分支。必须带 delegated 条件：编排卡片（subagentCard）只渲染
// 委派分支（多视角模式的分支是实现细节，不渲染卡片）。若这里不带 delegated，多视角模式下
// busyOf 会以为「卡片在转圈」而让位，实际卡片根本不渲染 → 气泡整个空白，loading 凭空消失。
// 两处判定必须同源：busyOf 只能把进度让位给「真的正在渲染」的构件。
const subRunning = m => Array.isArray(m?.subagents) && m.subagents.some(b => b.status === 'running' && b.delegated)

// ==================== 气泡级「进行中」提示（单一进度行） ====================
// 一轮回答里同一时刻只出现一处进行中指示，互斥由本函数的优先级链保证，而不是让
// 阶段提示 / 裸 spin / 重试条各自写 v-if 条件（那样每加一种状态就可能再叠一层）。
// 返回 null = 此刻不该有底部进度行：进行中状态已由更具体的构件表达——
//   工具卡片（转圈 + 实时耗时 + 工具名）、编排卡片（每分支转圈 + 进度条）、
//   深度思考面板（转圈 + 「已完成」）、审批卡（批准/拒绝按钮）。
// 返回 { text } = 带文案的进度行；{ spin: true } = 只有转圈（正文已在流出，不必重复写字）。
const busyOf = m => {
  if (!m || !m.loading) return null
  if (m.approval) return null                     // 等用户批准：进度让位给审批卡
  if (m.ask) return null                          // 等用户作答：进度让位给提问卡
  if (m.retrying) return { text: '连接中断，正在自动重试…', warn: true }
  if (toolRunning(m)) return null                 // 工具自己会转圈并计时
  if (subRunning(m)) return null                  // 编排卡片每个分支自带转圈 + 进度条
  // 深度思考面板只有 thinking 非空才渲染（v-if m.thinking）：让位条件与之对齐，
  // 否则 thinkLoading=true 而面板未渲染时进度行同样凭空消失（与 subRunning 同类坑）
  if (m.thinkLoading && m.thinking) return null       // 深度思考面板自己会转圈
  if (m.stage) return { text: stageLabel(m.stage) }   // 后端阶段文案（两档，映射见 stageLabel）
  if (m.content) return { spin: true }            // 正文续写中：一个转圈足够
  return { text: '正在生成回答…' }                // 工具已回、正文未出（含多轮工具之间的空档）
}

// ==================== 阶段文案（两档收敛） ====================
// 一轮回答的进度行只讲两档：准备期统一「正在检索资料…」，生成期「正在生成回答…」。
// 「正在理解问题…」并入检索档——它只占 ~1 秒，独立换词带来的是闪烁而不是信息
// （理解/改写/路由的细节由工具卡、检索行等具体构件表达）。
// 计划模式、执行工作流是独立模式的进程文案，原样保留不并档。
const STAGE_LABELS = { '正在理解问题…': '正在检索资料…' }
const stageLabel = s => (s ? (STAGE_LABELS[s] || s) : '')

// ==================== 时间线（正文与工具交错渲染） ====================
// 正文与工具卡片按事件到达顺序交错渲染。timeline 是段数组：
//   {kind:'text', from, to} → 指向 m.content 的切片区间（不复制文本，done 换正文时自动跟随）；
//   {kind:'tool', tool}     → 引用 m.toolCalls 里的同一对象（done/error 原地改状态，卡片自动更新）。
// 渲染经 timelineView(m) 生成视图：连续工具合并为折叠组，正文保持段落连续。
// 时间线随消息落库（正文区间 + 工具/产物下标），历史恢复时由 restoreTimeline 重建，
// 刷新后仍是「正文—工具—正文」的交错过程视图；旧消息无 timeline 才走底部汇总兜底。
// data-msg-index 仍挂在外层 .md 容器上，引用角标逻辑零改动。

/** 是否值得按时间线渲染：有工具段/产物段/过程段才有交错意义（纯正文段与整段渲染等价） */
const hasTimelineBlocks = m => Array.isArray(m?.timeline) && m.timeline.some(s => s && (s.kind === 'tool' || s.kind === 'artifact' || s.kind === 'process'))

const extendTimelineText = (m, from, to) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  const last = tl[tl.length - 1]
  if (last && last.kind === 'text' && last.to === from) last.to = to
  else tl.push({ kind: 'text', from, to })
}

// 过程独白段带区间：指向 m.processText（与正文分流的独立累积），连续追加自动延伸末段
const extendTimelineProcess = (m, from, to) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  const last = tl[tl.length - 1]
  if (last && last.kind === 'process' && last.to === from) last.to = to
  else tl.push({ kind: 'process', from, to })
}

// 过程独白折叠态：键用段起点 from（流式期间 to 随增量增长、from 稳定；timelineView 每帧重算，
// 段对象本身不保状态，与工具卡 _open 存在 toolCalls 对象上同理，这里挂在消息上）。
// 用户点过就认点过的（true/false 写死在 _procOpen 上）；没点过时：流式期间自动展开「正在长的那一段」
// （过程按 token 增量下发，展开才看得见它逐字长出，而不是等闭合标签到达整块蹦出），本轮结束自动收起
// ——与深度思考面板同款（thinkOpen 流式期 true、thinking_done 置 false），历史消息恒折叠。
const procOpen = (m, seg) => {
  const marked = m && m._procOpen ? m._procOpen[seg.from] : undefined
  if (marked === true) return true
  if (marked === false) return false
  if (!m || !m.loading) return false
  const tl = Array.isArray(m.timeline) ? m.timeline : []
  for (let i = tl.length - 1; i >= 0; i--) {
    if (tl[i] && tl[i].kind === 'process') return (Number(tl[i].from) || 0) === (Number(seg.from) || 0)
  }
  return false
}
const toggleProc = (m, seg) => {
  if (!m._procOpen) m._procOpen = {}
  m._procOpen[seg.from] = !procOpen(m, seg)
}

// ==================== 过程簇（连续过程/工具的二次折叠） ====================
/* 为什么需要这一层：模型一轮里常是「独白→工具→独白→工具」交替，而 timelineView 遇 process 段
 * 就flushGroup（独白天然切断工具分组，时序要如实保留），于是一轮过程被切成 2N 个折叠头
 * （实测「如何设计一个表单」这条回答出了 6 行：执行过程/2 个操作/执行过程/3 个操作/…）。
 * 这里在视图层补一道**后置聚簇**：把 out 里连续的 process/group 段包成 cluster，簇内原序完整保留，
 * 展开态与聚簇前逐段渲染完全一致（时序零损失），折叠态由 2N 行收敛为 1 行。
 *
 * 判定边界（两条都不能越）：
 *   ① 只在「游程内不含任何 text 段」时成立——即这一段过程与正文无交错、纯属执行细节；
 *      任何一段正文都断簇，否则会把「说完一段→查一下→接着说」的交错节奏一并压平。
 *   ② 只有一段时不包——否则单个工具组会被套上两层壳，白白多一次点击。
 *
 * 簇键用序号 c{N}（N = res.length）。序号稳定性论证：时间线只追加不重排，text 段到来时
 * 已 flush 的簇其 res 下标不会变动，新簇一律追加在尾部，故历史键不会错位到别的簇上。
 * （对比用 from 做键不成立：工具段与独白段的下标来自两套独立空间。）*/
const clusterize = out => {
  const res = []
  let run = []
  const flush = () => {
    if (!run.length) return
    if (run.length === 1) res.push(run[0])
    else res.push({ kind: 'cluster', segs: run, key: 'c' + res.length })
    run = []
  }
  for (const seg of out) {
    if (seg && (seg.kind === 'process' || seg.kind === 'group')) { run.push(seg); continue }
    flush()
    res.push(seg)
  }
  flush()
  return res
}

/** 簇内正在长的那一簇（仅末尾簇）标记 grow：流式期自动展开它，本轮结束自动收起，
 *  与 procOpen / thinkOpen 同款约定；用户点过就以用户为准（m._clOpen）。 */
const markGrowingCluster = view => {
  const last = view[view.length - 1]
  if (last && last.kind === 'cluster') last.grow = true
  return view
}

const clusterOpen = (m, cl) => {
  const marked = m && m._clOpen ? m._clOpen[cl.key] : undefined
  if (marked === true) return true
  if (marked === false) return false
  return !!(m && m.loading && cl.grow)
}
const toggleCluster = (m, cl) => {
  if (!m) return
  if (!m._clOpen) m._clOpen = {}
  m._clOpen[cl.key] = !clusterOpen(m, cl)
}

/** 簇内工具（跨多个 group 汇总）：头部的操作数/耗时与状态图标都从这里派生，与 groupDur 同口径 */
const clusterTools = cl => {
  const out = []
  for (const s of cl?.segs || []) if (s && s.kind === 'group' && s.tools) out.push(...s.tools)
  return out
}
const clusterRunning = cl => clusterTools(cl).some(t => t.status === 'start')
const clusterHasError = cl => clusterTools(cl).some(t => t.status === 'error')
const clusterDur = cl => durOfTools(clusterTools(cl))
/** 簇内独白段数：只在簇里一个工具都没有时外露（那时它是唯一可报的信息量） */
const clusterProcCount = cl => (cl?.segs || []).filter(s => s && s.kind === 'process').length

/** 渲染行= timelineView 的扁平化：簇头一行，簇展开时紧跟其内各段（标记 inCluster）。
 *  用「摊平 + 原分支复用」而不是在模板里嵌套 v-for，是为了不复制一份工具组/独白渲染代码——
 *  两份模板必然漂移（PC 与 H5 已经各有第三份了）。折叠即不产出内段，无需 v-show 维持其状态。 */
const timelineRows = m => {
  const out = []
  for (const s of timelineView(m)) {
    out.push(s)
    if (s.kind === 'cluster' && clusterOpen(m, s)) for (const inner of s.segs) out.push({ ...inner, inCluster: true })
  }
  return out
}

// 历史消息的 processText 可能带前导换行（后端修复前落库的数据，每个 <process> 块标签后的换行
// 原样入通道）：段的起点都是块边界（flush 锚点），显示时剥掉段首换行，免得灰字块顶部空一行；
// 只动显示，不碰区间下标，段内的模型自身换行/空行照常保留。
const procSlice = (m, seg) => (m.processText || '').slice(seg.from, seg.to).replace(/^[\n\r]+/, '')

// 工具段带下标 i：与后端落库口径一致（工具终态在 toolCalls 里的位置），
// 实时态另存对象引用（status 原地更新，卡片自动从转圈变完成）
const pushTimelineTool = (m, tool) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  tl.push({ kind: 'tool', tool, i: Array.isArray(m.toolCalls) ? m.toolCalls.length - 1 : 0 })
}

// 产物段：只存下标（产物清单可能被 done 整体覆盖，存引用会失效），渲染时取 m.artifacts[i]
const pushTimelineArtifact = (m, index) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  tl.push({ kind: 'artifact', i: index })
}

/** 用后端落库/下发的段数组重建时间线：文本段直接用区间，工具段与产物段按下标取回对象。
 *  下标取不到（数据缺失）就跳过该段，不造数。
 *  相邻过程段合并：过程独白按 token 增量成段，早期落库数据里一段独白被切成上百个碎片段，
 *  逐个渲染就是几十个「执行过程」折叠头。相邻（上一段 to === 本段 from）合并成一段是无损的——
 *  processText 区间本就连贯；中间夹着工具/文本段则不合并，交错顺序如实保留。 */
const restoreTimeline = (m, segs) => {
  const out = []
  for (const s of segs || []) {
    if (!s || !s.kind) continue
    if (s.kind === 'text') {
      out.push({ kind: 'text', from: Number(s.from) || 0, to: Number(s.to) || 0 })
    } else if (s.kind === 'process') {
      const from = Number(s.from) || 0
      const to = Number(s.to) || 0
      const last = out[out.length - 1]
      if (last && last.kind === 'process' && last.to === from) last.to = to
      else out.push({ kind: 'process', from, to })
    } else if (s.kind === 'tool') {
      const t = (m.toolCalls || [])[Number(s.i) || 0]
      if (t) out.push({ kind: 'tool', i: Number(s.i) || 0, tool: t })
    } else if (s.kind === 'artifact') {
      out.push({ kind: 'artifact', i: Number(s.i) || 0 })
    }
  }
  return out
}

/** 句末判定：文本尾部（去空白）以句末标点收尾视为「话已说完」；否则视为半句——后面大概率
 * 接「工具结果回来后继续说」的下半句。尾部有未闭合代码块（``` 为奇数个）时强制视为句末：
 * 跨代码块边界的拼接会让后续正文被吞进代码块。 */
const SENTENCE_END_CHARS = '。，、；：！？…—～~.!?;:』」》）〉】]'
const endsSentence = s => {
  const t = String(s).replace(/\s+$/, '')
  if (!t) return true
  if ((t.match(/```/g) || []).length % 2 === 1) return true
  return SENTENCE_END_CHARS.includes(t.slice(-1))
}

/** 时间线渲染视图：把 timeline 段序列转成可渲染序列。
 * 1）连续工具段（中间夹空白文本不算断点）合并为 {kind:'group', tools:[...]} 折叠组；
 * 2）句中工具吸收：模型常在句中发起工具（"先[调工具]检查环境"），半句之后出现的工具延迟渲染，
 *    等出现下一段正文时把两段文本连排渲染（合并区间是 content 的连续切片——工具不往正文写
 *    内容，连排与全量渲染完全等价，零失真），工具并入后续工具游程，组内顺序仍如实保留时序。
 *    每帧重算无状态：打字机期间合并窗口由收尾 flush 照常显示，运行中工具的卡片始终可见。
 * 尾部 timeline 未覆盖的正文兜底补段（与旧拼接逻辑等价）。 */
const timelineView = m => {
  const len = (m.content || '').length
  const content = String(m.content || '')
  const tl = Array.isArray(m.timeline) ? m.timeline : []
  const out = []
  let group = null
  let maxTo = 0
  let mergeFrom = -1 // >=0 表示有一个未闭合的半句在等待后续正文连排
  const absorbed = [] // 半句与下一段正文之间出现的工具（延迟渲染，按到达顺序保真）
  const flushGroup = () => { if (group) { out.push(group); group = null } }
  const flushMerge = () => {
    if (mergeFrom < 0) return
    out.push({ kind: 'text', from: mergeFrom, to: maxTo })
    for (const t of absorbed) { if (!group) group = { kind: 'group', tools: [] }; group.tools.push(t) }
    absorbed.length = 0
    mergeFrom = -1
  }
  for (const seg of tl) {
    if (!seg) continue
    if (seg.kind === 'process') {
      // 过程独白段：区间指向 m.processText（独立累积），原位灰字渲染；
      // 不参与句中吸收/工具分组，直接断开当前合并窗口与工具组
      flushMerge()
      flushGroup()
      const plen = (m.processText || '').length
      const pf = Math.min(Number(seg.from) || 0, plen)
      const pt = Math.min(Number(seg.to) || 0, plen)
      if (pt > pf) out.push({ kind: 'process', from: pf, to: pt })
      continue
    }
    if (seg.kind === 'artifact') {
      // 产物不再按生成时刻就地渲染（统一沉底展示）：跳过该段，且不打断工具组/合并窗口
      continue
    }
    if (seg.kind === 'tool') {
      if (!seg.tool) continue
      if (mergeFrom >= 0) { absorbed.push(seg.tool); continue }
      if (!group) group = { kind: 'group', tools: [] }
      group.tools.push(seg.tool)
      continue
    }
    const from = Math.min(seg.from, len), to = Math.min(seg.to, len)
    if (to <= from || !content.slice(from, to).trim()) continue // 空白段：不渲染、不打断分组
    if (mergeFrom >= 0) {
      maxTo = to // 半句连排：窗口向本段延伸
      if (endsSentence(content.slice(mergeFrom, maxTo))) flushMerge()
      continue
    }
    flushGroup()
    maxTo = to
    if (endsSentence(content.slice(from, to))) {
      out.push({ kind: 'text', from, to })
    } else {
      mergeFrom = from // 半句：开启合并窗口
    }
  }
  flushMerge()
  flushGroup()
  if (len > maxTo) out.push({ kind: 'text', from: maxTo, to: len })
  return markGrowingCluster(clusterize(out))
}

// ---- 工具卡片：标题行直接亮出关键参数（命令/路径/检索词），点击展开看完整入参与输出 ----
const TOOL_BRIEF_KEYS = ['command', 'query', 'path', 'dir', 'filename', 'url', 'kbIds']
const oneLine = (s, n) => { const x = String(s).replace(/\s+/g, ' ').trim(); return x.length > n ? x.slice(0, n) + '…' : x }
const toolBrief = t => {
  if (!t?.args) return ''
  let obj = null
  try { obj = JSON.parse(t.args) } catch (e) { return oneLine(t.args, 56) } // 非 JSON（MCP 兼容）：纯文本截断
  if (!obj || typeof obj !== 'object') return oneLine(t.args, 56)
  for (const k of TOOL_BRIEF_KEYS) {
    const v = obj[k]
    if (typeof v === 'string' && v.trim()) return oneLine(v, 56)
    if (Array.isArray(v) && v.length) return oneLine(JSON.stringify(v), 56)
  }
  for (const k of Object.keys(obj)) { // 兜底：第一个非空字符串字段（跳过 content/newString 等大文本字段）
    if (k === 'content' || k === 'newString') continue
    const v = obj[k]
    if (typeof v === 'string' && v.trim()) return oneLine(v, 56)
  }
  return oneLine(t.args, 48)
}
const prettyIo = s => {
  if (s == null || s === '') return ''
  try { const o = JSON.parse(s); return o && typeof o === 'object' ? JSON.stringify(o, null, 2) : String(s) } catch (e) { return String(s) }
}
// 运行中卡片显示实时输出（tool_output 增量累积，只展示尾部避免无界增长）；结束后显示最终全文
const LIVE_TAIL_CHARS = 4000
const liveOutput = t => {
  if (t.status === 'start') {
    if (!t.output) return ''
    return (t.outputTruncated ? '…（前面输出已省略）\n' : '') + t.output.slice(-LIVE_TAIL_CHARS)
  }
  return t.result != null ? t.result : (t.output || '')
}
const groupRunning = g => g.tools.some(t => t.status === 'start')
const groupHasError = g => g.tools.some(t => t.status === 'error')
// 一组工具的累计耗时（运行中的按实时 tick 计，其余用终态值）。过程簇跨多个 group 汇总，
// 与 groupDur 共用这一份口径——两处各写一遍必然出现「簇头耗时 ≠ 内部各组之和」。
const durOfTools = tools => {
  let ms = 0, running = false
  for (const t of tools) {
    if (t.status === 'start') { running = true; if (t.startAt) ms += Math.max(0, nowTick.value - t.startAt) }
    else ms += t.elapsedMs || 0
  }
  return toolDuration(ms) + (running ? '…' : '')
}
const groupDur = g => durOfTools(g.tools)
const fallbackDur = m => toolDuration(toolCallsView(m.toolCalls).reduce((s, t) => s + (t.elapsedMs || 0), 0))

/** done 汇总的工具终态合并进现有数组（原地改，保住 timeline 里的对象引用与实时到达的顺序） */
const mergeDoneToolCalls = (m, doneCalls) => {
  if (!m) return
  if (!Array.isArray(m.toolCalls)) m.toolCalls = []
  const list = m.toolCalls
  for (const d of doneCalls) {
    const live = [...list].reverse().find(x => x.name === d.name && x.status === 'start')
    if (live) {
      live.status = d.status || 'done'
      live.elapsedMs = d.elapsedMs || live.elapsedMs || 0
      if (d.args) live.args = d.args // done 汇总带全文（≤8KB），覆盖实时 200 字摘要
      if (d.error) live.error = d.error
      if (d.attempts != null) live.attempts = d.attempts
      if (d.result != null) live.result = d.result
      // 外溢留存标识必须跟着搬：实时流里的 tool_status 只是短摘要副本（不带 spillId），
      // 全文是 done 这份才有——漏搬的后果是「历史刷新后卡片有『查看完整输出』、当场没有」
      if (d.spillId) live.spillId = d.spillId
      if (d.argsSpillId) live.argsSpillId = d.argsSpillId
    } else {
      list.push({ ...d })
    }
  }
}
// 运行中工具的实时耗时：1s 一跳的 tick 驱动重渲染，让"卡住"变成可见的进行中
const nowTick = ref(Date.now())
let tickTimer = null
const ensureTick = () => {
  if (tickTimer) return
  tickTimer = setInterval(() => {
    nowTick.value = Date.now()
    if (!chatStreams.size) { clearInterval(tickTimer); tickTimer = null }
  }, 1000)
}
// 组件卸载时停表（纯模块挂不了 onUnmounted，由消费方在 onUnmounted 里调用）
const stopTick = () => { if (tickTimer) { clearInterval(tickTimer); tickTimer = null } }
const liveToolDur = startAt => toolDuration(Math.max(0, nowTick.value - startAt))
// 精确检索工具实际使用的检索词（模型可主动改词做二次检索，与主链路 retrieved 的词不同源）。
// 从 toolCalls 终态记录的 args 派生：实时路径 start 记录带 args（done 合并后保留），历史恢复是 done 记录带 args，两路都覆盖
const toolSearchQueries = m => {
  if (!Array.isArray(m?.toolCalls)) return []
  const qs = []
  for (const t of m.toolCalls) {
    if (t.name !== 'searchKnowledge' || t.status === 'start') continue
    try { const q = JSON.parse(t.args || '{}').query; if (q) qs.push(q) } catch (e) { /* args 非 JSON 时忽略 */ }
  }
  return qs
}
// 检索状态行文案（PC + 移动壳共用一份判据，两端不许各自内联拼「搜索 N 个关键词」）。
// 关键词数为 0 时整段不输出：提问被停用词表与单字规则滤净时（"怎么用"/"为什么呢"/"1+1" 这类）
// jieba 提不出主词元，但向量召回照跑、refs 照有，这个 0 对用户是噪音，看着像检索没生效。
// 返回 '' = 本轮没有任何可展示的检索信息，调用方据此整行不渲染（只剩一个空箭头更糟）。
const retrievalLineTitle = m => {
  const r = m?.retrieved || null
  const keywords = r ? (r.keywords || 0) : 0
  const refs = r ? (r.refs || 0) : (Array.isArray(m?.sources) ? m.sources.length : 0)
  let s = keywords > 0 ? `搜索 ${keywords} 个关键词` : ''
  if (refs > 0) s += (s ? '，' : '') + `参考 ${refs} 段资料`
  // 主链路没检索出内容、但模型主动做过精确检索：展开后仍有检索词可看，给个不空的标题
  if (!s && toolSearchQueries(m).length) s = '精确检索'
  return s
}
/** 深度思考按模型库登记的能力三态：none=不支持(隐藏) switchable=可开关 always=恒思考(锁定)；
 *  模型不在模型库（遗留裸名）按可开关处理。开关记忆按模型分开存（ai_deep_think: {ref:0|1}，
 *  旧版单个 '1'/'0' 迁移为所有模型的初始默认）。 */
const THINK_CAPS = {
  none: { visible: false, locked: false, on: false },
  switchable: { visible: true, locked: false, on: null }, // on=null → 读按模型记忆
  always: { visible: true, locked: true, on: true }
}
// ==================== 回答归属徽标的智能体头像 ====================
/**
 * 「由「X」回答」徽标左侧的图标数据源：从可用智能体列表里按 id（优先）或名称回查该条消息的归属智能体，
 * 交给 components/AgentAvatar.vue 渲染（品牌标 / emoji / 默认机器人三态由它统一解析，不在视图里各写一套）。
 *
 * 为什么按 id 与名称双路：历史消息只保证有 agentName（落库快照），实时流式轮才有 agentId；
 * 而「@ 提及的智能体作答」这类委派轮，agentId 指向被委派者、与列表项对得上，历史恢复时按名称也能命中。
 *
 * 查不到（智能体已被删除/不可见、旧消息只有名字对不上）返回 null —— AgentAvatar 缺省即默认机器人，
 * 与改动前的写死 <robot-outlined> 表现一致，不出现空洞。
 */
const agentBadgeOf = (agents, agentId, agentName) => {
  const list = Array.isArray(agents) ? agents : []
  if (agentId) {
    const byId = list.find(a => a && a.id === agentId)
    if (byId) return byId
  }
  if (agentName) return list.find(a => a && a.name === agentName) || null
  return null
}
// ==================== 思考等级（低/中/高/超高/极致，按模型支持档位给选项） ====================
/** 档位展示名与顺序（与后端 REASONING_LEVEL_LIST 同序，弱→强） */
const REASONING_LEVELS = [
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' },
  { value: 'xhigh', label: '超高' },
  { value: 'max', label: '极致' }
]
/** ON=开启思考（不带强度：模型未登记档位时用），off=关闭思考；其余为具体档位 */
const THINK_LEVEL_ON = '__on__'
const levelLabel = v => REASONING_LEVELS.find(x => x.value === v)?.label || '默认强度'
// ==================== 上下文窗口档位（模型登记 [最小窗口~窗口] 区间时面板可点选，默认=上限） ====================
/** 可选档位边界（token）：2 的幂序列；区间两端始终入选，落在区间内的幂点全给 */
const CTX_WINDOW_STEPS = [4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608]
/** K/M 口径窗口格式化：按整除自动选进制——2 的幂登记值走 1024 进制（131072→128K、1048576→1M），
 *  十进制登记值走 1000 进制（100000→100K、200000→200K），两边都不整除才落 1024 一位小数 */
const fmtWindow = n => {
  if (!n || n <= 0) return ''
  for (const base of [1048576, 1000000]) {
    if (n >= base) {
      const m = n / base
      if (m % 1 === 0) return m + 'M'
      if (m < 10 && (m * 10) % 1 === 0) return m.toFixed(1) + 'M'
    }
  }
  if (n >= 1024) {
    // 两种进制都整除时（如 128000）优先 1000 进制——官方口径是 128K 而非 125K
    if (n % 1000 === 0) return n / 1000 + 'K'
    if (n % 1024 === 0) return n / 1024 + 'K'
    const k = n / 1024
    return (k >= 100 ? Math.round(k) : Math.round(k * 10) / 10) + 'K'
  }
  return String(n)
}
// ============ 子智能体编排卡片（仅委派模式显示） ============
// 多视角模式（delegated=false）的分支只是把原问题换个问法，属实现细节，不展示；
// 只有主智能体委派了真实子智能体（有名字、有职责）时，卡片才有信息价值。
const fmtDuration = ms => ms == null ? '—' : toolDuration(ms)
function subagentCard (m) {
  const all = (m && Array.isArray(m.subagents)) ? m.subagents : []
  // 只要有任一分支标记了委派，就按委派模式渲染（后端按整轮是否委派置位，全部分支一致）
  const branches = all.filter(b => b && b.delegated)
  if (!branches.length) return null
  const done = branches.filter(b => b.status === 'done').length
  const running = branches.some(b => b.status === 'running')
  const total = branches.length
  const title = running ? `并行咨询 ${total} 个子智能体…` : `已咨询 ${total} 个子智能体`
  // 按需委派信息：从 N 个候选中挑了 M 个（让"挑选过程"可见，解释为什么只有这几个角色）
  const rt = m.subagentRoute
  const routeNote = (rt && rt.candidates > rt.picked)
    ? `从 ${rt.candidates} 个候选中挑选 ${rt.picked} 个相关的`
    : ''
  // 路由「挑选理由」（名称 → 理由；subagent_route 事件与 done payload 下发，旧消息无此字段不展示）
  const reasons = (rt && rt.reasons && typeof rt.reasons === 'object' && !Array.isArray(rt.reasons)) ? rt.reasons : {}
  // 耗时占比条基准：最慢分支（相对值，让"谁拖了后腿"一眼可见；运行中分支随进度增长）
  const maxElapsed = Math.max(1, ...branches.map(b => b.elapsedMs || 0))
  // 运行中默认展开（要看到实时进度），全部完成后默认收起（信息价值下降，不占版面）；
  // 用户手动点过则尊重其选择（saTouched），不再自动改变
  if (m.saOpen === undefined) m.saOpen = running
  return { branches, done, total, running, title, routeNote, reasons, maxElapsed }
}

/** 分支耗时占比条宽度：相对最慢分支的百分比（下限 2% 保证可见） */
function barWidth (card, b) {
  return Math.max(2, Math.round((b.elapsedMs || 0) / card.maxElapsed * 100)) + '%'
}

/** 手动展开/收起编排卡片（标记 saTouched，避免生成完成后被自动收起打断阅读） */
function toggleSubagents (m) {
  m.saOpen = !m.saOpen
  m.saTouched = true
}
/** 分组内核：入参 sources 数组，返回按 key 分好的组（外部来源 WEB/MCP 单独成组） */
const groupSources = sources => {
  const groups = []
  const byKey = new Map()
  // 外部来源（联网/MCP）单独成组，名称与知识库文档区分——此前它们没有 fileName/docId，
  // 全部落进「手动补充的知识」兜底组，名不副实
  const groupOf = s => {
    if (s.origin === 'WEB') return { key: '__web', fileName: '联网来源', icon: 'web' }
    if (s.origin === 'MCP') return { key: '__mcp', fileName: 'MCP 来源', icon: 'mcp' }
    return {
      key: s.docId || '__manual_' + (s.fileName || 'x'),
      fileName: s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识'),
      icon: 'doc'
    }
  }
  for (const s of sources) {
    const gi = groupOf(s)
    let g = byKey.get(gi.key)
    if (!g) {
      g = { key: gi.key, fileName: gi.fileName, icon: gi.icon, items: [] }
      byKey.set(gi.key, g)
      groups.push(g)
    }
    g.items.push(s)
  }
  return groups
}
// 引用来源详情弹窗
/** 外部来源（联网/MCP）：有原网页地址，展示站点名+标题，弹窗不给库内原文 */
const externalOrigin = s => s.origin === 'WEB' || s.origin === 'MCP'
/** 来源条目展示名：外部来源（origin=WEB/MCP）用站点名，库内来源用文件名 */
const sourceName = s => externalOrigin(s)
  ? (s.siteName || (s.origin === 'MCP' ? 'MCP 来源' : '联网来源')) + (s.title ? ' §' + s.title : '')
  : (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + (s.title ? ' §' + s.title : '')
const fmtSourceScore = s => {
  const v = s ? (s.rerankScore != null ? s.rerankScore : s.score) : null
  return (v == null || isNaN(Number(v))) ? '' : Number(v).toFixed(2)
}
const scoreTitle = s => (s && s.rerankScore != null ? '重排相关度 ' + s.rerankScore : '检索融合分 ' + s.score)
const fmtSize = n => {
  if (!n && n !== 0) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}
/** 长文本粘贴转成的附件名（不带扩展名部分；实际文件名补 .txt）。
 *  落库元信息只保留名称/类型/体积，所以刷新后靠这个前缀把卡片形态认回来 */
const PASTE_TEXT_BASE = '粘贴的文本'
/** 一条附件元信息是否来自长文本粘贴 */
const isPastedText = a => !!a && (a.paste === true || String(a.name || '').startsWith(PASTE_TEXT_BASE))
/** 粘贴文本卡片的标题：去掉 .txt 后缀（文件名就叫「粘贴的文本」，扩展名没有信息量） */
const pasteTitle = name => String(name || '').replace(/\.txt$/i, '')
/** 卡片副标题：体积 + 字数；上传中/失败时换成状态文案（两壳共用一份口径） */
const pasteSub = f => {
  if (!f) return ''
  if (f.uploading) return '上传中…'
  if (f.error) return '上传失败'
  return [fmtSize(f.size), f.text ? f.text.length + ' 字' : ''].filter(Boolean).join(' · ')
}
const histItemTitle = m => String(m.content).replace(/[#*`>\-\n]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60)
const histItemDigest = histItemTitle
const fmtMsgTime = ts => {
  if (!ts) return ''
  const d = new Date(ts)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) return '今天 ' + hm
  const yest = new Date(now)
  yest.setDate(now.getDate() - 1)
  if (d.toDateString() === yest.toDateString()) return '昨天 ' + hm
  return String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') + ' ' + hm
}
/** 错误分类文案（原始异常收进「异常详情」折叠；映射常见失败原因给出可行动提示） */
/** 错误分类文案。kind 由 api.js 给出（'interrupted'=断线/超时/连接被掐断），优先于按文本猜测：
 *  中断类在后端已把半程回答按截断态落库，文案要告诉用户「内容没丢」并说明重新生成会重新计费。 */
const errorBrief = (raw, kind) => {
  const s = String(raw || '')
  if (kind === 'interrupted') {
    if (/timeout|timed?\s*out|长时间未收到响应/i.test(s)) return '响应超时：连接已中断'
    if (/提前关闭/.test(s)) return '连接已断开：回答未正常结束'
    return '连接已中断：回答未正常结束'
  }
  if (/AbortError|aborted?/i.test(s)) return '生成已停止'
  // 额度不足必须在 429 分支**之前**判：网关的额度耗尽常返回 429 + insufficient_quota，
  // 先命中「限流」就会给出「等一会就好」的误导建议——而额度问题等多久都不会自己好
  if (/额度不足|余额不足|套餐已到期|配额耗尽|insufficient.?quota|billing|payment required|arrearage|out of credit|insufficient balance/i.test(s)) {
    return '模型服务额度不足：请到「模型供应商」充值，或改用其他模型'
  }
  if (/timeout|timed?\s*out/i.test(s)) return '请求超时：模型服务响应过慢或网络不稳定，可重试'
  if (/Failed to fetch|NetworkError|network/i.test(s)) return '网络连接失败：请检查网络或代理设置'
  if (/401|Unauthorized/i.test(s)) return '鉴权失败：登录已过期，请重新登录'
  if (/429|rate\s*limit/i.test(s)) return '请求过于频繁：模型服务限流，稍后重试'
  if (/5\d{2}|Bad Gateway|Service Unavailable/i.test(s)) return '模型服务异常：稍后重试，或到设置页检查供应商状态'
  return '生成失败，可重试或更换模型'
}
// ==================== 重新生成多版本（同一问题的多次回答可来回切换） ====================
// 语义：v1 是首次回答；每次「重新生成」把新完成的回答追加为新版本，气泡底部出现 ‹ 1/2 › 切换器。
// 版本只存在于当前会话内存里：历史接口按「一题一答」返回，重新生成时后端会把旧回答软删（见
// replaceMessageId），所以刷新后看到的是最后一版——想保留哪一版就切到哪一版再刷新是不成立的，
// 这一点在切换器上有提示，不做假承诺。
const snapshotVersion = m => ({
  content: m.content || '',
  sources: m.sources || [],
  related: m.related || [],
  thinking: m.thinking || '',
  timeline: m.timeline || [],
  artifacts: m.artifacts || [],
  toolCalls: m.toolCalls || [],
  subagents: m.subagents || [],
  plan: m.plan || null,
  processText: m.processText || '',
  degradations: m.degradations || [],
  retrieved: m.retrieved || null,
  doneTime: m.doneTime || null,
  model: m.model || ''
})
const applyVersion = (m, v) => {
  m.content = v.content
  m.sources = v.sources
  m.related = v.related
  m.thinking = v.thinking
  m.timeline = v.timeline
  m.artifacts = v.artifacts
  m.toolCalls = v.toolCalls
  m.subagents = v.subagents
  m.plan = v.plan
  m.processText = v.processText
  m.degradations = v.degradations
  m.retrieved = v.retrieved
  m.doneTime = v.doneTime
  // 模型随版本走：切回旧版本时分隔记录按那一版当时用的模型比对，不串到最新一轮的模型
  m.model = v.model || ''
}
// 切换器双数据源的边界判定/文案：内存 versions（本会话重新生成）优先，其次持久 variant（历史恢复/编辑重发）
const verLocal = m => Array.isArray(m.versions) && m.versions.length > 1
const canSwitchPrev = m => verLocal(m) ? (m.vIndex || 0) > 0 : (m.variantIndex || 1) > 1
const canSwitchNext = m => verLocal(m)
  ? (m.vIndex || 0) < m.versions.length - 1
  : (m.variantIndex || 1) < (m.variantCount || 1)
const verLabel = m => verLocal(m)
  ? `${(m.vIndex || 0) + 1}/${m.versions.length}`
  : `${m.variantIndex || 1}/${m.variantCount || 1}`

// ==================== 空态示例问题（新会话空态的引导卡，点一下即按这条提问） ====================
// 生效文案来自 /config/public 的 ui.sampleQuestions（个人覆盖 > 系统全局；关掉开关或清空即空串）。
// 内置这份只在配置没取到时兜底——空态刚打开就什么都没有，比多展示四条更容易被当成坏了。
const DEFAULT_SAMPLE_QUESTIONS = [
  { label: '🔍 知识检索', text: '帮我查一下问渠怎么上传文档' },
  { label: '📝 总结提炼', text: '帮我总结一份文档的核心要点' },
  { label: '✍️ 辅助写作', text: '帮我起草一份项目周报的框架' },
  { label: '📊 对比分析', text: '帮我对比一下两个方案的优缺点' }
]
// 一行一条；写成「标签｜问题」时前段作小标题（全/半角竖线都认），只写问题则无标签。
// 空行、只有标签没有问题的行丢弃——配置里多敲一个回车不该渲染出一张空卡
const parseSampleQuestions = raw => String(raw || '').split('\n')
  .map(line => line.trim())
  .filter(Boolean)
  .map(line => {
    const i = line.search(/[|｜]/)
    if (i < 0) return { label: '', text: line }
    const text = line.slice(i + 1).trim()
    return text ? { label: line.slice(0, i).trim(), text } : null
  })
  .filter(Boolean)
export {
  TOOL_LABELS, TOOL_DESCS, MCP_CLIENT_PREFIXES, bareToolName, toolLabel, toolDesc, toolCallsView,
  toolDuration, toolRunning, subRunning, busyOf, stageLabel, hasTimelineBlocks, extendTimelineText, askUserView,
  extendTimelineProcess, procOpen, toggleProc, procSlice, pushTimelineTool, pushTimelineArtifact,
  restoreTimeline, SENTENCE_END_CHARS, endsSentence, timelineView, timelineRows, TOOL_BRIEF_KEYS, oneLine,
  clusterize, clusterOpen, toggleCluster, clusterTools, clusterRunning, clusterHasError, clusterDur, clusterProcCount,
  toolBrief, prettyIo, LIVE_TAIL_CHARS, liveOutput, groupRunning, groupHasError, groupDur,
  fallbackDur, mergeDoneToolCalls, nowTick, ensureTick, stopTick, liveToolDur, toolSearchQueries,
  retrievalLineTitle,
  THINK_CAPS, REASONING_LEVELS, THINK_LEVEL_ON, levelLabel, CTX_WINDOW_STEPS, fmtWindow,
  fmtDuration, subagentCard, barWidth, toggleSubagents, groupSources, externalOrigin, sourceName,
  fmtSourceScore, scoreTitle, fmtSize, histItemTitle, histItemDigest, fmtMsgTime, errorBrief, agentBadgeOf,
  pasteTitle, pasteSub, isPastedText, PASTE_TEXT_BASE,
  snapshotVersion, applyVersion, verLocal, canSwitchPrev, canSwitchNext, verLabel,
  DEFAULT_SAMPLE_QUESTIONS, parseSampleQuestions
}
