// @ts-check
// @ 引用选取（输入框敲 @ 唤起候选）：知识库/文档/智能体三类候选的加载、多选、上限与筛选。
// 引用只对当轮生效（不落库、不跨轮继承，重新生成时随内存消息重发）；
// 智能体委派是单主语——一轮只能由一个智能体作答，再选一个=替换而不是累加。
// 从 useChatEngine 抽出，依赖只有四个：候选接口、智能体清单、通知入口。
import { ref, computed } from 'vue'

export const MAX_MENTIONS = 10

export const createMentionPicker = ({ listKbApi, listDocApi, agentList, notify }) => {
  const mentionOpen = ref(false)
  const mentionTab = ref('kb')
  const mentionQuery = ref('')
  const mentionLoading = ref(false)
  const mentionKbs = ref([])
  const mentionDocs = ref([])
  const pendingMentions = ref([])
  const mentionHi = ref(0)

  const isMentioned = (type, id) => pendingMentions.value.some(m => m.type === type && m.id === id)
  const toggleMention = (type, item) => {
    const id = item.id
    if (isMentioned(type, id)) {
      pendingMentions.value = pendingMentions.value.filter(m => !(m.type === type && m.id === id))
      return
    }
    // 智能体委派是单主语：一轮只能由一个智能体作答，再选一个=替换（不是多选累加）。
    // 总量仍守 MAX_MENTIONS（后端 chat.maxMentionsPerMessage 同限，超了会 400）
    if (type === 'agent') {
      const hadAgent = pendingMentions.value.some(m => m.type === 'agent')
      if (!hadAgent && pendingMentions.value.length >= MAX_MENTIONS) {
        notify.warning(`一次最多引用 ${MAX_MENTIONS} 个知识库/文档/智能体`)
        return
      }
      pendingMentions.value = pendingMentions.value.filter(m => m.type !== 'agent')
    } else if (pendingMentions.value.length >= MAX_MENTIONS) {
      notify.warning(`一次最多引用 ${MAX_MENTIONS} 个知识库/文档/智能体`)
      return
    }
    pendingMentions.value.push({
      type,
      id,
      name: type === 'kb' ? item.name : (type === 'agent' ? item.name : item.fileName),
      kbId: type === 'doc' ? (item.kbId || '') : ''
    })
  }
  const removeMention = i => pendingMentions.value.splice(i, 1)
  /** 切库/文档/智能体：候选集换了，高亮下标要收敛回合法范围（否则 kb 第 20 条切到 doc 只有 3 条时按 Enter 会选空） */
  const switchMentionTab = t => {
    mentionTab.value = t
    const n = t === 'kb' ? mentionKbFiltered.value.length
      : t === 'agent' ? mentionAgentFiltered.value.length : mentionDocFiltered.value.length
    if (mentionHi.value >= n) mentionHi.value = 0
  }

  /** 候选懒加载（首次打开面板时拉取）：库按共享范围过滤（/kb/list 已做），文档同理（/document/list 已做） */
  const loadMentionCandidates = async () => {
    mentionLoading.value = true
    try {
      const [kbRes, docRes] = await Promise.all([listKbApi(), listDocApi()])
      mentionKbs.value = (kbRes?.data || []).map(k => ({
        id: k.id, name: k.name, desc: k.description || '', docCount: k.docCount
      }))
      mentionDocs.value = (docRes?.data || []).map(d => ({
        id: d.id, fileName: d.fileName, kbId: d.kbId, status: d.status
      }))
    } catch (e) {
      notify.error('引用候选加载失败：' + (e.message || ''))
    } finally {
      mentionLoading.value = false
    }
  }

  /**
   * 文档可用性提示（与文档页状态口径一致：0=已入库 / 1=已停用 / 2=解析中 / 3=解析失败）。
   * 只有 0 能引用出内容，其余如实说明——不能让用户 @ 了一个还没解析完的文档后以为系统没生效。
   */
  const mentionDocStatus = d => {
    if (d.status === 0) return '已入库'
    if (d.status === 1) return '已停用（不参与引用）'
    if (d.status === 2) return '解析中（引用后可能没有内容）'
    if (d.status === 3) return '解析失败（引用不到内容）'
    return ''
  }

  const mentionMatch = (text, q) => !q || String(text || '').toLowerCase().includes(q.toLowerCase())
  // mentionQuery 现在直接来自输入框正文（「@ 到光标」之间），不再有唤起键混进搜索框的问题
  const mentionQueryNorm = computed(() => mentionQuery.value.trim())
  const mentionKbFiltered = computed(() =>
    mentionKbs.value.filter(k => mentionMatch(k.name, mentionQueryNorm.value) || mentionMatch(k.desc, mentionQueryNorm.value)))
  // 智能体候选复用对话页下拉的同一份可读主智能体列表（available 口径：不含子智能体、按共享范围过滤）
  const mentionAgents = computed(() =>
    agentList.value.map(a => ({ id: a.id, name: a.name, desc: a.description || '', icon: a.icon, isBuiltin: a.isBuiltin })))
  const mentionAgentFiltered = computed(() =>
    mentionAgents.value.filter(a => mentionMatch(a.name, mentionQueryNorm.value) || mentionMatch(a.desc, mentionQueryNorm.value)))
  const mentionDocFiltered = computed(() =>
    mentionDocs.value
      .filter(d => mentionMatch(d.fileName, mentionQueryNorm.value))
      .slice(0, 120))

  return {
    mentionOpen, mentionTab, mentionQuery, mentionLoading, mentionKbs, mentionDocs,
    pendingMentions, mentionHi, MAX_MENTIONS, isMentioned, toggleMention, removeMention,
    switchMentionTab, loadMentionCandidates, mentionDocStatus, mentionMatch, mentionQueryNorm,
    mentionKbFiltered, mentionAgents, mentionAgentFiltered, mentionDocFiltered,
  }
}
