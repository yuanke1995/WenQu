// 本轮发送参数（请求体字段，与事件回调同放在 opts 里交给 api）：
// 思考/计划模式/血缘/强度/材料/引用/重发/编辑/插话/作答方/会话级模型。
export const buildTurnRequest = ({
  deepThink, planMode, planBase, reasoningLevel,
  attachments, skills, mentions, historyRefs,
  replaceMsg, replacedMessageId, editMessageId, fromSteer, agentId, model
}) => ({
  deepThink,
  // 计划模式（人在回路）：true=本轮先产出执行计划，经用户批准/编辑后再正式回答
  planMode: planMode || false,
  // 重新计划血缘：旧计划批准 ID，服务端据此拼上一版计划与执行进度
  planBaseId: (planBase && planBase.planBaseId) || '',
  // 思考强度档位（低/中/高/超高/极致）：空串=不指定，后端回落模型登记的默认档位
  reasoningLevel,
  attachments,
  skills,
  mentions,
  // # 历史引用（[{messageId}]，重新生成时随内存消息原样重发）：服务端按会话归属校验后前置内容
  historyRefs: Array.isArray(historyRefs) && historyRefs.length ? historyRefs : [],
  // 重发标记（重新生成/自动重试走 replaceMsg 路径）：后端跳过用户消息重复落库
  regenerate: replaceMsg != null,
  // 被替换的旧回答消息 ID（仅重新生成时非空：自动重试的那一轮还没落库，messageId 为 null）；
  // 后端据此在落库前软删旧行，历史里只留最新一版
  replaceMessageId: replacedMessageId,
  // 编辑重发：被编辑的用户消息 ID（后端软删其旧分支留档，编辑内容作为新分支重新生成）
  editMessageId,
  // 插话续发：这句话是用户**在上一轮回答跑到一半时补的方向**，不是一次新提问。
  // 后端据此跳过用户消息重复落库，并在那句话后面补一句上下文声明——不带它，模型会把重新
  // 发出来的那句当成「用户又问了一遍」起题（短问答必现），插话"改了方向立刻生效"就废了
  fromSteer: !!fromSteer,
  agentId,
  // 会话级模型覆盖：仅用户手动切换时传（空=后端按 个人默认>无 兜底解析，无全局模型默认）
  model
})
