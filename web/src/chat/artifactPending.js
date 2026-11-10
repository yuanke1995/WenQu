// 「本轮正在生成产物」的纯投影：不读响应式状态、不依赖接口，便于单测。
// 数据只来自 tool_status 的 preparing/chars/brief（服务端在模型生成工具入参时提前下发）。

const ARTIFACT_DELIVERY_TOOLS = new Set(['presentArtifact', 'present_artifact', 'deliver_artifact'])
const MCP_CLIENT_PREFIX = 'w_q_'

const bareName = n => {
  const s = String(n || '')
  return s.startsWith(MCP_CLIENT_PREFIX) ? s.slice(MCP_CLIENT_PREFIX.length) : s
}

const parseArgs = raw => {
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try { return JSON.parse(String(raw)) } catch (e) { return null }
}

const fileNameOfPath = p => {
  const s = String(p || '').replace(/\\/g, '/')
  const i = s.lastIndexOf('/')
  return i >= 0 ? s.slice(i + 1) : s
}

/**
 * 这一轮「正在生成产物」的占位信息；没有就返回 null。
 * <p>只认 presentArtifact / deliver_artifact：它们的调用目的就是交付文件，
 * 不会出现「显示了生成态却没交付」的误导。write_file 不算——它可能只是中间产物。
 * <p>进度只报入参字符数（服务端 preparing 帧的 chars），不换算字节、不编百分比。
 */
export const artifactPendingOf = m => {
  if (!m || m.role !== 'ai' || !m.loading) return null
  if (Array.isArray(m.artifacts) && m.artifacts.length) return null
  const list = Array.isArray(m.toolCalls) ? m.toolCalls : []
  for (let i = list.length - 1; i >= 0; i -= 1) {
    const t = list[i]
    if (!t || t.status !== 'start') continue
    const name = bareName(t.name)
    if (!ARTIFACT_DELIVERY_TOOLS.has(name)) continue
    const args = parseArgs(t.args)
    const fromArgs = args && (args.filePath || args.filename || args.path)
    const filename = t.brief || fileNameOfPath(fromArgs || '')
    return {
      kind: name === 'deliver_artifact' ? 'deliver' : 'generate',
      filename: filename ? String(filename).slice(0, 40) : '',
      chars: Number(t.chars) || 0
    }
  }
  return null
}
