// 输入框附件的选取与上传（图片压缩、文件校验、粘贴转附件）。
// 从 useChatEngine 抽出：这块只管「待发送的材料」，与消息流、会话切换、SSE 控制无关。
// 所有外部能力走显式入参，便于单测替换。
import { ref, reactive } from 'vue'

export const createAttachmentPicker = ({
  uploadApi, notify, markLive,
  maxImages, maxFiles, classify, rejectText, REJECT, compressImage,
  pasteFilesOf, pasteNameOf, isLongPaste,
}) => {
  // 抄一份的代价不是多几行，是两页对「最多几张图 / 收哪些格式」慢慢说出不同的话
  const pendingImages = ref([])
  const removePendingImage = i => pendingImages.value.splice(i, 1)
  // ==================== 附件上传（「+」菜单选择/拖入/粘贴 → 先传换 fileId，问答请求只带 fileId） ====================
  // 文件先走 multipart 上传落盘换号，请求体只带 fileId：base64 内联会把 5×15MB 打出 ~100MB 的字符串，
  // 弱网必挂、断线要整包重传、后端也得先把整包读进内存。
  const pendingFiles = ref([])
  /** 输入框内联 chip 的编号源：附件/图片在上传前就得有一个稳定身份（fileId 要等响应回来才有），
   *  否则「这颗 chip 对应数组里哪一条」只能靠文件名猜，重名就串号。 */
  let chipSeq = 0
  /** 上传单个附件换 fileId（上传中就挂进列表，用户能看到进度/失败态，而不是点发送后才知道没传上去）；
   *  list 缺省挂主输入框，编辑卡复用时传自己的列表；extra 是只给本地呈现用的附加字段（如粘贴文本的原文） */
  const uploadOneFile = async (f, list = pendingFiles, extra) => {
    // 先 reactive 再入列：直接改裸对象绕过代理的 set 陷阱，进度/失败态不会重渲染，
    // 卡片会停在「上传中…」直到下一次无关渲染才突然变成体积
    const item = reactive({ chipKey: 'f' + (++chipSeq), name: f.name, size: f.size, mime: f.type || '', fileId: '', uploading: true, error: '', ...(extra || {}) })
    list.value.push(item)
    try {
      const r = await uploadApi(f)
      const d = r?.data || {}
      if (!d.fileId) throw new Error('上传未返回文件标识')
      item.fileId = d.fileId
      markLive(d.fileId)   // 自己刚送上来的文件不必再问服务端在不在（编辑卡打开时少一次多余请求）
      item.name = d.name || item.name
      item.mime = d.mime || item.mime
      if (d.size) item.size = d.size
      item.uploading = false
    } catch (e) {
      item.uploading = false
      item.error = e.message || '上传失败'
      notify.error(`附件上传失败：${f.name} — ${item.error}`)
    }
  }
  /** 是否存在还没传完的附件（发送前拦一道：不然用户以为发出去了，其实附件没带上） */
  const hasUploadingFile = () => pendingFiles.value.some(f => f.uploading)
  /** 统一入口：图片走压缩预览，其余按附件校验后挂起（类型/数量/体积，口径与后端校验一致）；
   *  target 缺省挂主输入框，编辑卡复用时传 { images, files } 两个 ref 列表。
   *  <p>判定本身在 ./intake.js（与分享页共用同一份白名单/限额/文案），这里只负责把判定结果落成
   *  各自的副作用：图片去压缩、附件去上传、拒收就提示。 */
  const addFiles = (files, target, extra) => {
    const imgList = target?.images || pendingImages
    const fileList = target?.files || pendingFiles
    // 本批已接受的张数要自己累加：compressImage 是异步的，列表长度在循环里不会变，
    // 只看 imgList.value.length 的话一次框选 8 张图会全部通过「最多 5 张」那道判断
    let imgAccepted = 0
    // 条数上限被拦的提示一批只报一次：一次框选 8 张图，后 3 张都超「最多 5 张」，
    // 逐个提示会把同一句话叠三层。类型/体积的提示文案带着文件名，不在此列——那是不同的文件。
    const warnedOnce = new Set()
    for (const f of files) {
      const verdict = classify(f, imgList.value.length + imgAccepted, fileList.value.length)
      if (verdict.kind === 'reject') {
        const countLimit = verdict.reason === REJECT.IMG_MAX || verdict.reason === REJECT.FILE_MAX
        if (!countLimit || !warnedOnce.has(verdict.reason)) {
          notify.warning(rejectText(verdict))
          if (countLimit) warnedOnce.add(verdict.reason)
        }
        continue
      }
      if (verdict.kind === 'image') {
        imgAccepted++
        compressImage(f).then(dataUrl => imgList.value.push({ chipKey: 'p' + (++chipSeq), dataUrl }))
          .catch(() => notify.error(`图片处理失败: ${verdict.label}`))
        continue
      }
      uploadOneFile(f, fileList, extra)
    }
  }
  const removePendingFile = i => pendingFiles.value.splice(i, 1)
  /** 把一份**已在服务端**的附件直接挂进输入框（粘贴还原用）：字节本来就在盘上，凭 fileId 带走即可，
   *  重传一遍只是把同一个文件再拷一份落盘。同一 fileId 不重复挂（复制两份粘回来只留一份）。 */
  const attachUploaded = ({ name, mime, size, fileId }) => {
    if (!fileId || pendingFiles.value.some(f => f.fileId === fileId)) return false
    if (pendingFiles.value.length >= maxFiles) { notify.warning(`一次最多上传 ${maxFiles} 个附件`); return false }
    markLive(fileId)
    pendingFiles.value.push(reactive({ chipKey: 'f' + (++chipSeq), name: name || '附件',
      mime: mime || '', size: size || 0, fileId, uploading: false, error: '' }))
    return true
  }
  /** 把一张图挂进输入框（粘贴还原：图片没有服务端凭据，能搬的就是手上这份 data URL） */
  const attachImage = dataUrl => {
    if (!dataUrl || !String(dataUrl).startsWith('data:')) return false
    if (pendingImages.value.length >= maxImages) { notify.warning(`最多上传 ${maxImages} 张图片`); return false }
    pendingImages.value.push(reactive({ chipKey: 'p' + (++chipSeq), dataUrl }))
    return true
  }
  // ==================== 粘贴文件 → 附件 ====================
  /** 粘贴带文件（截图、也带 PDF / Word / 文本等真附件）：与「+」选择、拖入走同一分流口径。
   *  必须 preventDefault——否则浏览器会把文件名当文本插进正文；返回 true 表示已消费，调用方按需截断冒泡 */
  const takePastedFiles = (e, target) => {
    const files = pasteFilesOf(e.clipboardData)
    if (!files.length) return false
    e.preventDefault()
    addFiles(files, target)
    return true
  }
  // ==================== 长文本粘贴 →「粘贴的文本」附件 ====================
  // 整篇文档、大段日志塞进输入框既读不清也发不出去（问题正文有长度上限，超限直接报错）。
  // 超过阈值就把它包成一个 .txt 文件，走与「选择文件」完全相同的管线：卡片呈现、随本轮上传、
  // 内容进上下文。粘贴文本的原文只留在内存（卡片点开看全文用），落库的仍只有名称/类型/体积。
  /** 输入框粘贴事件里取长文本：带文件的粘贴让给文件管线，短文本照常内联可编辑。
   *  返回 true 表示这次粘贴已被消费（原文不再落进输入框），调用方按需要截断冒泡 */
  const takePastedText = (e, target) => {
    const cd = e.clipboardData
    if (!isLongPaste(cd)) return false
    const raw = cd.getData('text/plain')
    const fileList = target?.files || pendingFiles
    e.preventDefault()
    addFiles([new File([raw], pasteNameOf(fileList.value), { type: 'text/plain' })], target,
      { paste: true, text: raw })
    notify.info('粘贴的长文本已转为附件，点卡片可查看全文')
    return true
  }
  // ==================== @ 引用（本轮显式指定知识库/文档/智能体） ====================
  // 语义：kb=本轮检索收窄到该库；doc=该文档内容块强制前置进上下文（不经检索、不受相关性门/去冗余约束）；
  //       agent=临时委派该智能体作答本轮（人设/知识库/工具整轮按它执行，会话绑定不变——落点在后端轮级覆盖）。
  // 交互：输入框敲 @ 唤起候选面板（面板不抢焦点，筛选词实时取「@ 到光标」之间的正文 + ↑↓ 选 + Enter 确认 + 多选），
  //       选中项以 chip 显示在输入框上方。引用字符 @ 本身照常留在输入框里（用户可任意位置输入 @），
  //       确认后会把「@ + 筛选词」这一段从正文里摘掉，不留残渣。

  return {
    pendingImages, removePendingImage, pendingFiles, uploadOneFile, hasUploadingFile,
    addFiles, removePendingFile, attachUploaded, attachImage, takePastedFiles, takePastedText,
  }
}
