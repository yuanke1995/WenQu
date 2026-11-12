// 附件卡片的口径：粘贴文本识别、标题去后缀、副标题（体积 + 字数 / 状态文案）。
// PC 与 H5 共用同一份，避免同一张附件卡在两端显示两套字数口径。
import { fmtSize } from '../utils/format.js'
import { PASTE_TEXT_BASE } from './intake.js'

/** 一条附件元信息是否来自长文本粘贴（落库只保留名称/类型/体积，靠前缀认回卡片形态）。 */
export const isPastedText = a => !!a && (a.paste === true || String(a.name || '').startsWith(PASTE_TEXT_BASE))

/** 粘贴文本卡片的标题：去掉 .txt 后缀（文件名就叫「粘贴的文本」，扩展名没有信息量）。 */
export const pasteTitle = name => String(name || '').replace(/\.txt$/i, '')

/** 卡片副标题：体积 + 字数；上传中/失败/过期时换成状态文案。 */
export const pasteSub = f => {
  if (!f) return ''
  if (f.uploading) return '上传中…'
  if (f.error) return '上传失败'
  if (f.expired) return f.text ? '正文未随历史保留' : '已过保留期'
  if (f.unavailable) return '不可重发'
  return [fmtSize(f.size), f.text ? f.text.length + ' 字' : ''].filter(Boolean).join(' · ')
}
