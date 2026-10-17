// 输入框取材的**纯判定层**：把「一个 File 该进哪条管线、被拒时该说什么」从两个调用方里抽出来。
//
// 为什么要有这一层：这条口径原本只活在 useChatEngine（登录态对话页）内部，分享页要用就得再抄一份——
// 而这份口径恰恰是最不该抄的：图片压缩参数、附件扩展名白名单、体积与数量上限、被拒时的文案，
// 每一处都必须与后端 ChatUploadService / ChatController 的校验对齐。抄一份的后果不是多几行代码，
// 是两处慢慢漂移：用户在对话页能传的文件在分享页被拦、或者两页对「最多几张图」说法不一致。
//
// 本模块**不 import antd、不碰 ref、不发请求**：只做判断与压缩，弹出提示与上传由调用方负责。
// 这样它既能被登录态那条重交互管线用，也能被分享页那条轻量管线用，还能被单测直接调。

/** 一次最多几张图（与后端 chat.maxImagesPerMessage 的默认 9 是两回事：前端刻意收得更紧，
 *  因为每张图都要压成 data URL 内联进请求体，5 张 1280px JPEG 已经是几百 KB 的量级） */
export const MAX_IMAGES = 5
/** 一次最多几个文档附件（与后端 chat.maxAttachmentsPerMessage 默认值一致） */
export const MAX_FILES = 5
/** 单个附件体积上限（MB；与后端 chat.maxAttachmentMb 默认值一致） */
export const MAX_FILE_MB = 15

/**
 * 受支持的附件扩展名（与后端 {@code ChatAttachmentService.supportedExt} 的白名单一致）：
 * doc/ppt 老格式未引入解析器，所以不收；其余文本与代码类都按纯文本读。
 */
export const SUPPORTED_EXTS = ['pdf', 'docx', 'xls', 'xlsx', 'pptx', 'txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'log',
  'xml', 'yml', 'yaml', 'html', 'htm', 'java', 'js', 'ts', 'jsx', 'tsx', 'vue', 'py', 'sql', 'sh', 'bat',
  'c', 'h', 'cpp', 'hpp', 'cs', 'go', 'rs', 'rb', 'php', 'css', 'scss', 'less', 'properties', 'ini', 'conf', 'toml']

/** 长文本粘贴转附件的阈值（低于此值照常内联进输入框，用户还能改） */
export const PASTE_TEXT_MIN_CHARS = 800

/** 粘贴文本转成的附件名（与 useChatEngine 的历史行为一致，重名时由调用方补序号） */
export const PASTE_TEXT_BASE = '粘贴的文本'

/** 扩展名（小写，不含点）；无扩展名返回空串 */
export const extOf = name => {
  const dot = (name || '').lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

/** 图片判定口径只有这一处：MIME 前缀。剪贴板来的截图 type 齐全，选择的文件也带 */
export const isImageFile = f => !!f && typeof f.type === 'string' && f.type.startsWith('image/')

/**
 * 图片压缩为 JPEG data URL（最长边 1280，质量 0.85）。
 * <p>压缩而不是原样内联有两个原因：原图动辄 3~8MB，base64 后膨胀 4/3 直接顶到后端单张 10MB 上限；
 * 而视觉模型侧能利用的分辨率也用不到 4000px。
 * @returns {Promise<string>} data URL；加载失败时 reject（由调用方决定怎么提示）
 */
export const compressImage = file => new Promise((resolve, reject) => {
  const img = new Image()
  const url = URL.createObjectURL(file)
  img.onload = () => {
    const max = 1280
    let { width, height } = img
    if (width > max || height > max) {
      const ratio = Math.min(max / width, max / height)
      width = Math.round(width * ratio); height = Math.round(height * ratio)
    }
    const canvas = document.createElement('canvas')
    canvas.width = width; canvas.height = height
    canvas.getContext('2d').drawImage(img, 0, 0, width, height)
    URL.revokeObjectURL(url)
    resolve(canvas.toDataURL('image/jpeg', 0.85))
  }
  img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片加载失败')) }
  img.src = url
})

/**
 * 剪贴板里的文件：{@code files} 命中即用；部分浏览器（老 Safari / 微信内置）只把它挂在
 * {@code items} 上，空了再退一路。两路**不合流**，否则同一个文件会入列两次。
 */
export const pasteFilesOf = clipboardData => {
  const files = Array.from(clipboardData?.files || [])
  if (files.length) return files
  return Array.from(clipboardData?.items || [])
    .filter(i => i.kind === 'file').map(i => i.getAsFile()).filter(Boolean)
}

/** 拒收原因码：调用方据此提示（码而不是文案，是为了让"同一句提示"只有一个来源） */
export const REJECT = {
  IMG_MAX: 'imgMax',
  FILE_MAX: 'fileMax',
  FILE_EXT: 'fileExt',
  FILE_SIZE: 'fileSize'
}

/** 默认限额（登录态用；分享页会拿服务端下发的值覆盖——服务端才是权威）。 */
export const DEFAULT_LIMITS = { maxImages: MAX_IMAGES, maxFiles: MAX_FILES, maxFileMb: MAX_FILE_MB }

/**
 * 判定一个待入列文件该走哪条管线。
 * @param {File} f 待判定文件
 * @param {number} imgCount 当前已挂起的图片数（不含本批已接受的）
 * @param {number} fileCount 当前已挂起的附件数
 * @param {{maxImages?:number,maxFiles?:number,maxFileMb?:number}} [limits] 限额覆盖（分享页用服务端下发值）
 * @returns {{kind:'image'|'file',label:string,ext:string}|{kind:'reject',reason:string,label:string,ext:string,limit:number}}
 */
export function classifyIntake (f, imgCount = 0, fileCount = 0, limits) {
  const { maxImages, maxFiles, maxFileMb } = { ...DEFAULT_LIMITS, ...(limits || {}) }
  // 粘贴进来的文件可能没有名字（剪贴板只给了个 blob），提示里不能留一个空位
  const label = (f && f.name) || '未命名文件'
  if (isImageFile(f)) {
    if (imgCount >= maxImages) return { kind: 'reject', reason: REJECT.IMG_MAX, label, ext: '', limit: maxImages }
    return { kind: 'image', label }
  }
  if (fileCount >= maxFiles) return { kind: 'reject', reason: REJECT.FILE_MAX, label, ext: '', limit: maxFiles }
  const ext = extOf(f && f.name)
  if (!SUPPORTED_EXTS.includes(ext)) return { kind: 'reject', reason: REJECT.FILE_EXT, label, ext, limit: 0 }
  if ((f.size || 0) > maxFileMb * 1024 * 1024) {
    return { kind: 'reject', reason: REJECT.FILE_SIZE, label, ext, limit: maxFileMb }
  }
  return { kind: 'file', label, ext }
}

/**
 * 拒收原因的提示文案。措辞与登录态对话页逐字一致——两个页面拦同一个文件，
 * 用户看到的必须是同一句话，否则会被读成"这两个地方规则不一样"。
 * <p>数字一律取自判定结果里的 {@code limit}，不在这里重算：服务端把上限调小之后，
 * 提示里说的和实际执行的必须还是同一个数。
 * @param {{reason:string,label:string,limit:number}} verdict classifyIntake 的返回
 */
export function rejectText (verdict) {
  switch (verdict.reason) {
    case REJECT.IMG_MAX: return `最多上传 ${verdict.limit} 张图片`
    case REJECT.FILE_MAX: return `一次最多上传 ${verdict.limit} 个附件`
    case REJECT.FILE_EXT: return `暂不支持的附件类型：${verdict.label}（支持 PDF / Word / Excel / PPT / 文本与代码文件）`
    case REJECT.FILE_SIZE: return `单个附件不能超过 ${verdict.limit}MB：${verdict.label}`
    default: return `无法添加：${verdict.label}`
  }
}

/** 本次粘贴是否该转成附件：带文件的粘贴让给文件管线；短文本照常内联可编辑 */
export function isLongPaste (clipboardData) {
  if (!clipboardData || pasteFilesOf(clipboardData).length) return false
  const raw = clipboardData.getData('text/plain')
  return !!raw && raw.length >= PASTE_TEXT_MIN_CHARS
}

/** 粘贴文本附件的取名（同一次可连续粘贴多段；重名补序号，避免按名称回填时串号） */
export function pasteNameOf (list) {
  const used = new Set((list || []).map(f => f.name))
  if (!used.has(`${PASTE_TEXT_BASE}.txt`)) return `${PASTE_TEXT_BASE}.txt`
  let n = 2
  while (used.has(`${PASTE_TEXT_BASE} ${n}.txt`)) n++
  return `${PASTE_TEXT_BASE} ${n}.txt`
}
