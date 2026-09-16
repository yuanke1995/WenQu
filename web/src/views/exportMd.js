// ==================== Markdown 导出（V1 Chat.vue 移植，单轮 + 整会话共用） ====================
// 图片策略：data URL 原样；服务内图片经 fetch 转 base64 内嵌（导出文件自包含）；
// 抓取失败（签名过期/跨域被拦）返回 null → 正文保留 [图片N] 占位。
import { message } from 'ant-design-vue'
import { getHistory } from '../api'
import { resolveImg } from '../utils/markdown'

const imgToDataUri = async u => {
  if (!u) return null
  if (u.startsWith('data:')) return u
  try {
    const r = await fetch(resolveImg(u))
    if (!r.ok) return null
    const blob = await r.blob()
    return await new Promise((res, rej) => {
      const fr = new FileReader()
      fr.onload = () => res(fr.result)
      fr.onerror = rej
      fr.readAsDataURL(blob)
    })
  } catch (e) {
    return null
  }
}

/** 把正文里的 [图片N] 占位替换为内嵌图（取不到图的保留原占位文本） */
const embedMdImages = async (content, imgs) => {
  const uris = []
  for (const u of (imgs || [])) uris.push(await imgToDataUri(u))
  return content.replace(/\[图片\s*(\d+)(?:[：:]([^\]]*))?\]/g, (all, num, desc) => {
    const uri = uris[Number(num) - 1]
    return uri ? `![图片${num}${desc && desc.trim() ? '：' + desc.trim() : ''}](${uri})` : all
  })
}

const downloadMd = (md, fileName) => {
  const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

const fmtTime = ts => {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const safeFileName = title => (title || new Date().toISOString().slice(0, 10)).replace(/[\\/:*?"<>|]/g, '_')

/** 导出该轮问答为 .md（question 为配对的提问消息，可为 null；图片尽量 base64 内嵌） */
export const exportAnswerMd = async ({ answer, question, title }) => {
  if (!answer || !answer.content) { message.warning('该回答无可导出内容'); return }
  const imgs = Array.isArray(answer.images) ? answer.images : []
  const hide = imgs.length ? message.loading('正在导出（含图片抓取转码）…', 0) : null
  try {
    const parts = [`# ${title || 'AI回答'}\n`]
    if (question?.content) parts.push('## 问题\n' + question.content.trim() + '\n')
    const body = await embedMdImages(answer.content.trim(), imgs)
    parts.push('## 回答\n' + body + '\n')
    if (answer.sources && answer.sources.length) {
      parts.push('## 引用来源\n' + answer.sources.map((s, si) =>
        `${si + 1}. ${s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')}${s.title ? ' §' + s.title : ''}`).join('\n') + '\n')
    }
    downloadMd(parts.join('\n'), safeFileName(title) + '.md')
  } finally {
    if (hide) hide()
  }
}

/** 导出整个会话为 .md（按轮次：问题（含用户图）→ 回答（含引用图）→ 引用来源）；无需先打开会话 */
export const exportSessionMarkdown = async (sid, title) => {
  if (!sid) return
  const hide = message.loading('正在导出整个会话（含图片抓取转码）…', 0)
  try {
    const r = await getHistory(sid)
    if (!r.success) { message.error(r.msg || '获取会话历史失败'); return }
    const list = r.data || []
    const rows = list.filter(m => m && ((m.content && m.content.trim()) || (Array.isArray(m.images) && m.images.length)))
    const now = new Date()
    const roundCount = rows.filter(m => m.role !== 'user').length
    const parts = [`# ${title || 'AI对话'}`, '', `> 导出时间：${now.toLocaleString('zh-CN', { hour12: false })} · 共 ${roundCount} 轮问答`, '']
    let qn = 0
    let first = true
    for (const m of rows) {
      if (!first) parts.push('---', '')
      first = false
      if (m.role === 'user') {
        qn++
        parts.push(`## ${qn}. 问题`, '')
        if (m.content && m.content.trim()) parts.push(m.content.trim(), '')
        const uims = Array.isArray(m.images) ? m.images : []
        for (let k = 0; k < uims.length; k++) {
          const uri = await imgToDataUri(uims[k])
          if (uri) parts.push(`![用户图片${k + 1}](${uri})`, '')
        }
      } else {
        const body = await embedMdImages((m.content || '').trim(), Array.isArray(m.images) ? m.images : [])
        parts.push('**回答**', '', body, '')
        if (m.sources && m.sources.length) {
          parts.push('**引用来源**', m.sources.map((s, si) =>
            `${si + 1}. ${s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')}${s.title ? ' §' + s.title : ''}`).join('\n'), '')
        }
        if ((m.messageId || m.id) && (m.fb === 0 || m.fb === 1)) {
          // 保留评价状态，便于回顾哪些回答被认可
          parts.push(`> 评价：${m.fb === 1 ? '有帮助 👍' : '没帮助 👎'}`, '')
        }
        const t = fmtTime(m.createTime ? new Date(m.createTime).getTime() : (m.time || null))
        if (t) parts.push(`> ${t}`, '')
      }
    }
    if (parts.length <= 3) { message.warning('会话为空，无可导出内容'); return }
    downloadMd(parts.join('\n'), safeFileName(title) + '.md')
  } catch (e) {
    message.error(e.message || '导出失败')
  } finally {
    if (hide) hide()
  }
}
