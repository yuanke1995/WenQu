// 服务端 blocks → 现有视图字段的确定性投影。
// 独立成纯模块：不依赖 Vue / 页面 store，便于单测，也避免聊天页状态被测试脚本拉起。

/** 用服务端 blocks 重建历史/终态视图：块自带全文，这里只做一次确定性展开，
 *  还原成现有组件读取的 content/timeline/toolCalls/artifacts。
 *  有 blocks 后不再按末尾正文反推答案。 */
export const applyBlocks = (m, payload) => {
  if (!m || !payload || !Array.isArray(payload.blocks)) return m
  let content = ''
  let thinking = ''
  let processText = ''
  const toolCalls = []
  const artifacts = []
  const timeline = []
  for (const b of payload.blocks) {
    if (!b || !b.kind) continue
    if (b.kind === 'text') {
      const text = typeof b.text === 'string' ? b.text : ''
      if (!text) continue
      const from = content.length
      content += text
      timeline.push({ kind: 'text', from, to: content.length, channel: b.channel || '' })
    } else if (b.kind === 'notice') {
      // 终尾标随正文落在 content 里，notice 块不重复拼第二遍。
      // 完整块留在 m.blocks 上供页面取用。
      continue
    } else if (b.kind === 'think') {
      const text = typeof b.text === 'string' ? b.text : ''
      if (!text) continue
      const from = thinking.length
      thinking += text
      timeline.push({ kind: 'think', from, to: thinking.length })
    } else if (b.kind === 'process') {
      const text = typeof b.text === 'string' ? b.text : ''
      if (!text) continue
      const from = processText.length
      processText += text
      // 相邻过程段合并（与 restoreTimeline 同口径）：快照/收尾可能把一段独白切碎
      const last = timeline[timeline.length - 1]
      if (last && last.kind === 'process' && last.to === from) last.to = processText.length
      else timeline.push({ kind: 'process', from, to: processText.length })
    } else if (b.kind === 'tool') {
      const { kind, id, ...tool } = b
      toolCalls.push(tool)
      timeline.push({ kind: 'tool', i: toolCalls.length - 1, tool })
    } else if (b.kind === 'artifact') {
      const { kind, id, artifactId, ...artifact } = b
      // 产物真实 id 在块里叫 artifactId（"id" 是块号，不能当产物 id 用）
      if (artifactId) artifact.id = artifactId
      artifacts.push(artifact)
      timeline.push({ kind: 'artifact', i: artifacts.length - 1 })
    } else if (b.kind === 'steer') {
      const text = typeof b.text === 'string' ? b.text : ''
      if (!text) continue
      const seg = { kind: 'steer', text, messageId: String(b.messageId || '') }
      if (Array.isArray(b.images) && b.images.length) seg.images = b.images
      if (Array.isArray(b.attachments) && b.attachments.length) seg.attachments = b.attachments
      timeline.push(seg)
    }
  }
  // 产物按 id/URL 合并：接流快照的 blocks 在本轮收尾前不含 artifact block，直接覆盖会把
  // 已经产出的产物卡清空；收尾时 blocks 完整，则仍以 blocks 为准，只保留它尚未覆盖到的后续产物。
  const keyOfArtifact = a => String(
    (a && a.id) || String((a && a.url) || '').split('?')[0] || (a && a.filename) || ''
  )
  const existingArtifacts = Array.isArray(m.artifacts) ? m.artifacts : []
  const seenArtifacts = new Set(artifacts.map(keyOfArtifact).filter(Boolean))
  for (const a of existingArtifacts) {
    const k = keyOfArtifact(a)
    if (!k || seenArtifacts.has(k)) continue
    artifacts.push(a)
  }
  m.content = content
  m.thinking = thinking
  m.processText = processText
  m.toolCalls = toolCalls
  m.artifacts = artifacts
  m.timeline = timeline
  m.blocks = payload
  return m
}
