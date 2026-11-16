import { ref, computed } from 'vue'
import { message } from 'ant-design-vue'
import { getAgentPublish, publishAgent, revokeAgentPublish } from '../api'
import { loadModelIndex } from '../utils/modelRef'
import { publicUrl } from '../utils/publicUrl'
import { locateField } from '../utils/fieldLocate'
import { triStr, splitList } from './agentBasics'

/**
 * 智能体「公开发布」弹窗的全部状态与动作（/s/{token} 免登录对话 + iframe 嵌入 + MCP 端点）。
 * 从 AgentsPage.vue 搬出，逻辑逐字不变；对外导出的名字与原来一致，模板无需改动。
 *
 * @param agents        智能体列表 ref（保存/撤销后就地回写卡片上的「已发布」标记）
 * @param route/router  深链 ?agentId=&pub=1 定位到本弹窗后清参
 *
 * myMcpServers 由本 composable 持有并导出：页面 reload 拿到 /mcp/status 后写入它，
 * 发布弹窗 openPublish 再按被发布智能体的配置收窄——两边共用同一个 ref，不是两份。
 */
export function useAgentPublish ({ agents, route, router }) {
  const pubVisible = ref(false)
  const pubAgentId = ref('')
  const pubAgentName = ref('')
  /** 弹窗标题栏的智能体头像：与列表卡片共用 AgentAvatar 的 icon 解析口径（品牌标 / emoji / 默认机器人） */
  const pubAgent = ref({ name: '', icon: '', isBuiltin: 0 })
  const pubEnabled = ref(false)
  const pubMcpEnabled = ref(false)
  /** 被发布智能体的「工具执行确认」是否为「禁用」（openPublish 时取一次）。
   *  禁用档在运行期 approvalPolicyAllows 处直接不挂 MCP 工具族（ToolAssemblyService 精确匹配 "off"，
   *  跟随档位''/auto/ask 都不算），勾了名单也一个工具都调不动——这是确定翻车的组合，就地警告 */
  const pubAgentApprovalOff = ref(false)
  const pubModelRef = ref('')
  const pubToken = ref('')
  /** 游客输入开关（默认关；发布者显式打开才给访客多模态入口） */
  const pubGuestImages = ref(false)
  const pubGuestFiles = ref(false)
  /** 调用变量开关（默认关 fail-closed；嵌入页随轮传业务上下文，发布者显式同意才注入） */
  const pubVariables = ref(false)
  /** 游客可用 MCP server 勾选（server 名数组；空=关闭。MCP 是发布者个人资产，按 server 粒度亮出） */
  const pubGuestMcp = ref([])
  /** 发布者名下全部已启用的 MCP server（页面 reload 拿到 /mcp/status 后写入；具体勾选项在 openPublish 按智能体配置收窄） */
  const myMcpServers = ref([])
  /** 勾选项：发布者自己已启用、且落在**被发布智能体的 MCP 配置范围内**的 server（openPublish 时按智能体算；
   *  未连接的也列出，标注即可）。运行期 McpToolProvider 本来就拿「游客名单 ∩ 智能体具体项选择」装配，
   *  勾选列表与智能体配置对齐后，这里勾到的每一项都是真能挂上的，不会出现「勾了但智能体根本没配」的死选项 */
  const myMcpServerChoices = ref([])
  /**
   * 被发布智能体的 MCP 具体项范围 → 裸服务名数组。
   *   toolMcp=0（不使用）           → []（整族不挂，无可勾）
   *   toolMcp=1（指定）             → agent.mcps 引用串解析成名字。引用是 {uid}/{name} 精确绑定（个人资产），
   *                                   裸名是存量配置的弱匹配——与后端 ResourceRefs.resolve 同一套口径
   *   toolMcp=null（跟随全局）      → null（智能体未指定具体项，运行期挂发布者全部已启用 server）
   */
  const agentMcpScopeNames = a => {
    const tri = triStr(a.toolMcp)
    if (tri === '0') return []
    if (tri === '1') {
      return splitList(a.mcps).map(r => {
        const slash = r.indexOf('/')
        return slash < 0 ? r : r.slice(slash + 1)
      }).filter(n => n)
    }
    return null
  }
  /** 模型目录（ref → 展示信息，含 visionCapable）：只用来判断"选中的模型能不能看图"。
   *  loadModelIndex 自带 10s TTL 缓存且失败静默，拿不到就按"能看"放过——宁可少提示一句，
   *  也不要因为一次取数失败就在发布弹窗里报一个假警 */
  const modelIndex = ref({})
  /** 选中模型能否看图（三态已由后端归一成布尔）。空 modelRef=跟随个人默认模型，此时无从判定 */
  const pubModelVisionOk = computed(() => !pubModelRef.value || !!modelIndex.value[pubModelRef.value]?.visionCapable)
  /**
   * 「允许发图」要不要就地提示，以及提示哪一句：
   *   'model'   —— 选定的模型看不懂图，是**确定的问题**（访客发图会被忽略或报错）
   *   'inherit' —— 跟随个人默认模型，此处**无从判定**，但同样得提醒去确认
   *   ''        —— 不必提示
   * <p>为什么分成两档而不是「判不出来就不提示」：判不出来的那一档恰恰最容易出事——
   * 发布者以为自己选了默认模型就万事俱备，访客发出去的图却被静默忽略。
   * 提示要说出「我判不了，你去确认」，而不是假装没问题（这正是本函数存在的理由）。
   */
  const pubVisionWarn = computed(() => {
    if (!pubEnabled.value || !pubGuestImages.value) return ''
    if (!pubModelRef.value) return 'inherit'
    return pubModelVisionOk.value ? '' : 'model'
  })
  /** 发布后的回收信号（visit_count 每次游客发起对话 +1） */
  const pubVisitCount = ref(0)
  const pubLastVisitAt = ref('')
  /** 分发方式当前页签（三种形态共用一个 token，切页签不产生新链接） */
  const pubTab = ref('link')
  /** iframe 嵌入尺寸：可调，并实时反映进代码 */
  const pubFrameW = ref(420)
  const pubFrameH = ref(640)
  const shareUrl = computed(() => pubToken.value ? publicUrl('/s/' + pubToken.value) : '')
  // MCP 端点（/ai/mcp/{token}）：与网页分享同一 token，token 即凭据
  const mcpUrl = computed(() => pubToken.value ? publicUrl('/ai/mcp/' + pubToken.value) : '')
  const mcpJson = computed(() => mcpUrl.value
    ? JSON.stringify({ mcpServers: { [pubAgentName.value || 'wenqu']: { url: mcpUrl.value } } }, null, 2)
    : '')
  const iframeSnippet = computed(() => shareUrl.value
    ? `<iframe src="${shareUrl.value}?embed=1" style="width:${pubFrameW.value}px;height:${pubFrameH.value}px;border:1px solid #e5e6eb;border-radius:12px" title="${pubAgentName.value || 'AI 助手'}"></iframe>`
    : '')

  /**
   * ?agentId=&pub=1 深链：模型供应商「查看引用」里那条「游客对话模型」，能改它的地方就是
   * 这个公开发布弹窗（游客模型选择器在里面）。停在智能体列表上等于让人自己找那张卡再自己找入口。
   */
  const applyPubDeepLink = () => {
    const id = typeof route.query.agentId === 'string' ? route.query.agentId : ''
    if (!id || !route.query.pub) return
    router.replace({ path: route.path, query: { ...route.query, agentId: undefined, pub: undefined } })
    const a = agents.value.find(x => x.id === id)
    if (!a) {
      message.warning('这位智能体不在你的列表里（可能已删除，或你没有管理权）')
      return
    }
    openPublish(a)
    locateField('pubModelRef')
  }

  async function openPublish (a) {
    pubAgentId.value = a.id
    pubAgentName.value = a.name || ''
    pubAgent.value = { name: a.name || '', icon: a.icon || '', isBuiltin: a.isBuiltin || 0 }
    pubVisible.value = true
    pubToken.value = ''
    pubEnabled.value = false
    pubMcpEnabled.value = false
    pubAgentApprovalOff.value = (a.toolApprovalMode || '') === 'off'
    pubModelRef.value = ''
    pubGuestImages.value = false
    pubGuestFiles.value = false
    pubVariables.value = false
    pubGuestMcp.value = []
    pubTab.value = 'link'
    pubVisitCount.value = 0
    pubLastVisitAt.value = ''
    // 勾选项跟随被发布智能体的 MCP 配置走：指定模式只列它绑定的那几个，不使用则一项不列，
    // 跟随全局（未指定具体项）才亮出发布者全部已启用 server——运行期装配就是按这个交集来的
    const mcpScope = agentMcpScopeNames(a)
    myMcpServerChoices.value = mcpScope
      ? myMcpServers.value.filter(s => mcpScope.includes(s.name))
      : myMcpServers.value.slice()
    // 模型目录用于「允许发图」那条就地提示；不 await：它是 10s TTL 的展示索引，
    // 晚到一步只影响那行提示什么时候出现，不该拖住弹窗打开
    loadModelIndex().then(ix => { modelIndex.value = ix || {} }).catch(() => {})
    try {
      const r = await getAgentPublish(a.id)
      if (r && r.success !== false && r.data) {
        pubEnabled.value = !!r.data.enabled
        pubMcpEnabled.value = !!r.data.mcpEnabled
        pubGuestImages.value = !!r.data.guestImages
        pubGuestFiles.value = !!r.data.guestFiles
        pubVariables.value = !!r.data.variables
        // 已存名单先按本次勾选项收口：智能体配置改过（或 server 被删）后，名单里的残留名
        // 运行期装配时交集为空、勾了也白勾——回显就剪掉，下次保存自然把库里那份一起清掉
        const allowNames = new Set(myMcpServerChoices.value.map(s => s.name))
        pubGuestMcp.value = Array.isArray(r.data.guestMcpServers)
          ? r.data.guestMcpServers.filter(n => allowNames.has(n))
          : []
        pubModelRef.value = r.data.modelRef || ''
        pubToken.value = r.data.token || ''
        pubVisitCount.value = r.data.visitCount || 0
        pubLastVisitAt.value = r.data.lastVisitAt || ''
      }
    } catch (e) { message.error(e.message || '分享配置加载失败') }
  }

  /** 大动作（总开关 / 端点开关 / 模型 / 游客输入开关）保存后给一句确认 */
  async function savePublish () { return doSave(true) }
  /** 勾选名单的细粒度改动走静默保存：勾三个服务弹三个「已发布」，toast 就成了噪音，
   *  勾选框本身已是反馈。全选/清空也走这里 */
  async function saveMcpChecks () { return doSave(false) }

  async function doSave (toast) {
    // MCP 端点是分享的附加形态：分享停用后链接与端点一并失效，前端同步关掉避免发布出无效组合
    if (!pubEnabled.value) pubMcpEnabled.value = false
    // 游客输入同理依赖分享本身：链接都停了就无所谓"访客能不能发图"，收了才不会回显出
    // 「未发布 + 已允许发图」这种自相矛盾的状态（后端也归一成关，两边口径一致）
    if (!pubEnabled.value) { pubGuestImages.value = false; pubGuestFiles.value = false; pubVariables.value = false; pubGuestMcp.value = [] }
    try {
      const r = await publishAgent(pubAgentId.value, {
        enabled: pubEnabled.value,
        mcpEnabled: pubMcpEnabled.value,
        guestImages: pubGuestImages.value,
        guestFiles: pubGuestFiles.value,
        variables: pubVariables.value,
        guestMcpServers: pubGuestMcp.value.slice(),
        modelRef: pubModelRef.value || ''
      })
      if (r && r.success !== false && r.data) {
        pubToken.value = r.data.token || ''
        pubMcpEnabled.value = !!r.data.mcpEnabled
        // 以后端归一后的值为准（停用分享时它会把两个能力位一起压成 false）
        pubGuestImages.value = !!r.data.guestImages
        pubGuestFiles.value = !!r.data.guestFiles
        pubVariables.value = !!r.data.variables
        if (Array.isArray(r.data.guestMcpServers)) pubGuestMcp.value = r.data.guestMcpServers.slice()
        // 同步列表行：卡片上的「已发布」标记即时反映，不用重拉列表
        const row = agents.value.find(x => x.id === pubAgentId.value)
        if (row) row.published = pubEnabled.value ? 1 : 0
        if (toast) message.success(pubEnabled.value ? '已发布，链接可访问' : '已停用')
      } else message.error(r?.msg || '保存失败')
    } catch (e) { message.error(e.message || '保存失败') }
  }

  async function doRevoke () {
    try {
      const r = await revokeAgentPublish(pubAgentId.value)
      if (r && r.success !== false) {
        const row = agents.value.find(x => x.id === pubAgentId.value)
        if (row) row.published = 0
        pubVisible.value = false
        message.success('已撤销分享')
      } else message.error(r?.msg || '撤销失败')
    } catch (e) { message.error(e.message || '撤销失败') }
  }

  return {
    pubVisible, pubAgentId, pubAgentName, pubAgent, pubEnabled, pubMcpEnabled,
    pubAgentApprovalOff, pubModelRef, pubToken, pubGuestImages, pubGuestFiles,
    pubVariables, pubGuestMcp, myMcpServerChoices, myMcpServers, modelIndex,
    pubModelVisionOk, pubVisionWarn, pubVisitCount, pubLastVisitAt, pubTab,
    pubFrameW, pubFrameH, shareUrl, mcpUrl, mcpJson, iframeSnippet,
    applyPubDeepLink, openPublish, savePublish, saveMcpChecks, doRevoke
  }
}
