// 目标条 / 过程抽屉 / 交付抽屉的动作判据。三处入口落到同一份包，动作只在这里分派一次。

/** 过程抽屉里的一类动作：改目标走条上就地编辑，取消走二次确认，其余交引擎动作出口。 */
export const goalPanelActionPlan = action => {
  if (action === 'edit') return { closeBefore: true, inlineEdit: true, armCancel: false, run: false, closeAfter: false }
  if (action === 'cancel') return { closeBefore: true, inlineEdit: false, armCancel: true, run: false, closeAfter: false }
  return { closeBefore: false, inlineEdit: false, armCancel: false, run: true, closeAfter: action === 'delete' }
}

/** 从过程抽屉跳某一轮：按 turnId 找回那条回答，再往前找它对应的提问行。 */
export const goalTurnAnchor = (list, turnId) => {
  const rows = list || []
  const aiIdx = rows.findIndex(m => m && m.turnId === turnId)
  if (aiIdx < 0) return null
  let userIdx = aiIdx
  while (userIdx > 0 && rows[userIdx] && rows[userIdx].role !== 'user') userIdx--
  return { aiIdx, userIdx }
}

/** 目标换了或状态变了就得撤掉取消确认态，留着会让下一次「取消」跳过二次确认。 */
export const goalKeyOf = g => (g && g.goalId ? g.goalId + ':' + g.status : '')
