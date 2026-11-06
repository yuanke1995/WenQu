// 服务端 blocks → 现有视图字段的确定性投影。
// 独立成纯模块：不依赖 Vue / 页面 store，便于单测，也避免聊天页状态被测试脚本拉起。

/** 用服务端 blocks 重建历史/终态视图：块自带全文，这里只做一次确定性展开，
 *  还原成现有组件读取的 content/timeline/toolCalls/artifacts。
 *  没有 blocks 的存量消息仍走 restoreTimeline；有 blocks 后不再按末尾正文反推答案。 */
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
      // 双写窗口内终尾标仍在正文里（旧客户端只读 content），这里不再拼第二遍。
      // 块本身随 m.blocks 留在页面上，P6 停写尾标后改由它渲染，不再靠整串文案 endsWith。
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
      const { kind, id, ...artifact } = b
      artifacts.push(artifact)
      timeline.push({ kind: 'artifact', i: artifacts.length - 1 })
    } else if (b.kind === 'steer') {
      const text = typeof b.text === 'string' ? b.text : ''
      if (text) timeline.push({ kind: 'steer', text, messageId: String(b.messageId || '') })
    }
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
