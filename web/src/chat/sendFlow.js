// 发送主流程（actions 层）：本轮载荷组装、上传门禁、生成中转插话、清空输入框、开新一轮回答。
// 所有状态经 refs 注入，模块自身不依赖 store 与路由。
import { reactive } from 'vue'

export const createSend = ({
  text, pendingImages, pendingFiles, pickedSkills, pendingMentions, pendingHistoryRefs,
  hasUploadingFile, loading, goalTurnStreaming, surrenderGoalStream, stageSteer, sendSteer,
  setPlanMode, planModeOn, effectiveModel, compactNotice, liveCompactedSid, deepThinkOn,
  closePanels, messages, streamAnswer, notifyWarning
}) => async (opts = {}) => {
  const q = text.value.trim()
  const imgs = pendingImages.value.map(p => p.dataUrl)
  // 附件只带 fileId（内容已先上传落盘）；未传完的不带走，并明确拦下这次发送——
  // 默默发出去会让用户以为附件生效了，实际模型根本没看到这份材料
  if (hasUploadingFile()) {
    notifyWarning('附件还在上传中，请稍候再发送')
    return
  }
  const atts = pendingFiles.value
    .filter(f => f.fileId && !f.error)
    .map(f => ({ name: f.name, mime: f.mime, fileId: f.fileId }))
  const attsMeta = pendingFiles.value
    .filter(f => f.fileId && !f.error)
    .map(f => ({ name: f.name, mime: f.mime, size: f.size, paste: !!f.paste, text: f.text }))
  const skills = [...pickedSkills.value]
  // @ 引用（本轮显式指定的知识库/文档）：与问题一起提交，服务端按可见性校验后收窄检索范围/强制前置
  const mentions = pendingMentions.value.map(m => ({ type: m.type, id: m.id, name: m.name, kbId: m.kbId || '' }))
  // # 历史引用（本轮显式指定的会话历史问答）：只带 messageId，内容由服务端按会话归属查库回填
  const historyRefs = pendingHistoryRefs.value.map(h => ({ messageId: h.messageId }))
  if (!q && !imgs.length && !atts.length) return
  // 目标轮在浏览器里跑着时用户说话＝插话（让位一轮）：不能排成 steer（后台目标轮没登记 TurnHandle，
  // steer 必然失败）。先断本地这道呈现通道（不请服务端 stop，否则目标会被记成 stopped 而不是 preempted），
  // 后面的普通发送会让后端把目标轮打断并转 PAUSED/preempted，答完再自动接回。
  if (loading.value && goalTurnStreaming.value) surrenderGoalStream()
  // 生成中再发一句 = 排进插话队列（输入框上方那排 chip），**不发请求**。
  // 主输入框不再因跑着而禁用：想改方向不该被迫先停止本轮。载荷与正常提问完全同权
  // （图片/附件/技能/@/#/模型/思考/计划模式）。发给模型要等用户在 chip 上点「立即发送」，
  // 或用 ⌘/Ctrl+Enter 这一档入队后立刻送。
  if (loading.value) {
    const staged = stageSteer({ text: q, images: imgs, atts, attsMeta, skills, mentions, historyRefs,
                      deepThink: deepThinkOn.value, planMode: planModeOn.value })
    if (!staged) return
    // 计划模式跟着这条插话走（载荷里已带走），开关本身同样当场消费掉
    setPlanMode(false)
    text.value = ''
    closePanels()
    pendingImages.value = []
    pendingFiles.value = []
    pickedSkills.value = []
    pendingMentions.value = []
    pendingHistoryRefs.value = []
    // 清完输入框再送：送不进（本轮已收尾/正等人裁决）时 sendSteer 要把这句放回输入框，
    // 放回首尾留着刚打的下半句会把它拼成一句四不像
    if (opts.steerNow) sendSteer(staged)
    return
  }
  // 无任何可用模型（会话/智能体/个人默认均未配置）时引导配置，不打无谓请求
  if (!effectiveModel.value) {
    notifyWarning('未指定模型：请在右上角选择模型，或在个人设置/智能体中配置默认模型')
    return
  }
  text.value = ''
  closePanels()   // 正文清空后触发字符已失效，显式收起（不依赖 input 回调的副作用）
  // 压缩结果条随下一轮退场：它记录的是「刚才压了什么」，继续对话后仍摆在消息流最下方，
  // 会被读成「本轮压缩了 N 轮」（与气泡上的自动压缩提示条混淆）
  compactNotice.value = null
  // 本轮那条压缩留痕同理：它是「这一轮」的状态，开新一轮就得作废，
  // 否则上一轮压过的线会跟着挂在这一轮的提问上方（持久判据要等落库才接管，中间这段是空的）
  liveCompactedSid.value = null
  pendingImages.value = []
  pendingFiles.value = []
  pickedSkills.value = []
  pendingMentions.value = []
  pendingHistoryRefs.value = []
  const deep = deepThinkOn.value
  const planMode = planModeOn.value
  // 计划模式是本轮模式，不是常驻档位：这一轮的载荷已经带走它，开关当场消费掉，
  // 否则下一句随便的追问也会先被塞进计划闸门（chip 会一直赖在输入框里）。
  // 改计划的每一版由 continuePlanConversation 强制带上，不靠这颗开关留着。
  if (planMode) setPlanMode(false)
  // attachData 留在内存消息上：重新生成/自动重试时可原样重发（历史回放无数据，行为与图片 data: 口径一致）
  const userMsg = reactive({ role: 'user', content: q, images: imgs, attachments: attsMeta, attachData: atts,
                        skills, mentions, historyRefs, deepThink: deep, planMode, time: Date.now(), messageId: null })
  messages.value.push(userMsg)
  // userMsg 传入流式：done 回填本轮用户消息的落库 ID（userMessageId），编辑重发/分支切换从此可用
  streamAnswer(q, imgs, null, messages.value.length === 1, 1, deep, atts, skills, mentions, null, historyRefs, '', userMsg, planMode)
}
