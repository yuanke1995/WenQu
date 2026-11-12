// 编辑重发的页面态：编辑卡开在哪一轮、卡内随行材料、重发判据与动作出口。
// 纯判据在 editResend.js；取数、提示、输入管线与引擎调用由调用方注入。
import { ref, reactive, computed, nextTick } from 'vue'
import { seedEditRows, editResendPayload, editResendArgs, editKeydownIntent,
         toggleMentionRows, toggleHistRefRows, addSkillRows } from './editResend.js'

/** 服务端图片 URL → data URL；失败抛给调用方，由编辑卡决定保留 URL 还是要提示。 */
const imageUrlToDataUrl = async url => {
  const r = await fetch(url)
  if (!r.ok) throw new Error('HTTP ' + r.status)
  const b = await r.blob()
  return await new Promise((resolve, reject) => {
    const fr = new FileReader()
    fr.onload = () => resolve(fr.result)
    fr.onerror = () => reject(new Error('读取失败'))
    fr.readAsDataURL(b)
  })
}

export const useEditResend = ctx => {
  const notify = ctx.notify
  const isPastedText = ctx.isPastedText
  const histItemDigest = ctx.histItemDigest
  const toDataUrl = ctx.toDataUrl || imageUrlToDataUrl

  const editingIdx = ref(null)
  const editingMessageId = ref('')
  const editingText = ref('')
  const editingBusy = ref(false)
  const editingRef = ref(null)
  const editImgs = ref([])
  const editAtts = ref([])
  const editMentions = ref([])
  const editHistRefs = ref([])
  const editSkills = ref([])
  const editAttachInput = ref(null)
  const setEditingRef = el => { editingRef.value = el }
  const setEditAttachInputRef = el => { editAttachInput.value = el }

  const autosizeEditing = () => {
    const el = editingRef.value
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 200) + 'px'
  }

  /** 带凭据的附件问一次服务端还在不在；期间切了消息就作废这份结果。查不到时不改界面。 */
  const probeEditAtts = async () => {
    const at = editAtts.value
    const withId = at.filter(f => f.fileId && !f.probed)
    if (!withId.length) return
    let split
    try {
      split = await ctx.splitAtts(withId.map(f => ({ name: f.name, mime: f.mime, size: f.size, fileId: f.fileId })))
    } catch (e) {
      notify.warning(`附件状态没查到（${e.message || '请求失败'}），重发时若文件已过期会明确报错`)
      return
    }
    if (editAtts.value !== at) return
    const deadIds = new Set(split.dead.map(d => d.fileId))
    at.forEach(f => {
      if (!f.fileId) return
      f.probed = true
      if (deadIds.has(f.fileId)) f.expired = true
    })
  }

  const editMessage = mi => {
    if (ctx.loading() || editingBusy.value) return
    const m = ctx.messages()[mi]
    if (!m || m.role !== 'user') return
    if (!m.messageId) { notify.warning('该消息尚未落库（流式回答中），请稍后再编辑'); return }
    editingIdx.value = mi
    editingMessageId.value = m.messageId
    editingText.value = m.content
    const seed = seedEditRows(m, { isPastedText, histItemDigest })
    editAtts.value = seed.atts
    probeEditAtts()
    editMentions.value = seed.mentions
    editHistRefs.value = seed.histRefs
    editSkills.value = seed.skills
    // 历史回放的图片只有签名 URL，抓回字节才能随重发带走；抓不回来就保留 URL 供灯箱查看。
    const seedImgs = seed.imgs
    editImgs.value = seedImgs
    seedImgs.forEach((it, i) => {
      if (String(it.dataUrl || '').startsWith('data:')) return
      const before = it.dataUrl
      toDataUrl(ctx.resolveImg(it.dataUrl))
        .then(d => {
          // 写回响应式行（ref 里的元素是代理，不能拿播种时的原对象比较身份），
          // 且只在仍是同一张卡、同一位置仍是当初那个 URL 时回填。
          const row = editImgs.value[i]
          if (editingIdx.value === mi && row && row.dataUrl === before) row.dataUrl = d
        })
        .catch(() => {})
    })
    nextTick(() => { autosizeEditing(); editingRef.value?.focus() })
  }

  const cancelEdit = () => {
    editingIdx.value = null
    editingMessageId.value = ''
    editingText.value = ''
    editImgs.value = []; editAtts.value = []; editMentions.value = []
    editHistRefs.value = []; editSkills.value = []
    if (ctx.addOpen() && ctx.addTarget() === 'edit') ctx.closeAddPanel()
  }

  const pickEditFiles = () => editAttachInput.value?.click()
  const onEditAttachChange = e => {
    const files = Array.from(e.target.files || [])
    e.target.value = ''
    if (!files.length) return
    ctx.addFiles(files, { images: editImgs, files: editAtts })
    ctx.closeAddPanel()
  }
  const editAddCount = computed(() => editImgs.value.length + editAtts.value.length
    + editMentions.value.length + editSkills.value.length + editHistRefs.value.length)

  const toggleEditSkill = n => {
    if (editSkills.value.includes(n)) { removeEditSkill(n); return }
    const r = addSkillRows(editSkills.value, n)
    if (r.error) { notify.warning(r.error); return }
    editSkills.value = r.rows
  }
  const toggleEditHistoryRef = m => {
    const r = toggleHistRefRows(editHistRefs.value, m, ctx.histPool(), ctx.maxHistoryRefs(), histItemDigest)
    if (r.error) { notify.warning(r.error); return }
    editHistRefs.value = r.rows
  }
  const toggleEditMention = (type, item) => {
    const r = toggleMentionRows(editMentions.value, type, item, ctx.maxMentions())
    if (r.error) { notify.warning(r.error); return }
    editMentions.value = r.rows
  }
  const onEditPaste = e => {
    const into = { images: editImgs, files: editAtts }
    if (ctx.takePastedFiles(e, into) || ctx.takePastedText(e, into)) e.stopPropagation()
  }
  const removeEditImage = i => editImgs.value.splice(i, 1)
  const removeEditAtt = i => editAtts.value.splice(i, 1)
  const removeEditMention = i => editMentions.value.splice(i, 1)
  const removeEditSkill = name => { editSkills.value = editSkills.value.filter(n => n !== name) }
  const removeEditHistRef = i => editHistRefs.value.splice(i, 1)

  /** # 引用候选排除正在编辑的这一轮：它马上被软删重建，messageId 不再成立。 */
  const editHistPool = computed(() => {
    const self = (ctx.messages()[editingIdx.value] || {}).messageId
    return ctx.histPool().filter(m => m.messageId !== self)
  })
  const addEditSkill = name => {
    const r = addSkillRows(editSkills.value, name)
    if (r.error) { notify.warning(r.error); return }
    editSkills.value = r.rows
  }
  const addEditHistRef = mid => {
    const pool = ctx.histPool()
    const m = pool.find(x => x.messageId === mid)
    if (!m) return
    const r = toggleHistRefRows(editHistRefs.value, m, pool, ctx.maxHistoryRefs(), histItemDigest)
    if (r.error) { notify.warning(r.error); return }
    editHistRefs.value = r.rows
  }
  const previewEditImage = pi => {
    ctx.openImages(editImgs.value.map(p => ctx.resolveImg(p.dataUrl)), pi)
  }

  const onEditKeydown = e => {
    const intent = editKeydownIntent(e, {
      panelOpen: ctx.addOpen() && ctx.addOnEdit(),
      panelNav: ev => ctx.onPanelNavKeydown(ev)
    })
    if (intent === 'panel') return
    if (intent === 'send') { e.preventDefault(); confirmEdit() }
    else if (intent === 'cancel') { e.preventDefault(); cancelEdit() }
  }

  const confirmEdit = () => {
    const mi = editingIdx.value
    if (mi == null || editingBusy.value) return
    const old = ctx.messages()[mi]
    if (!old || old.role !== 'user') { cancelEdit(); return }
    if (editAtts.value.some(f => f.uploading)) { notify.warning('附件还在上传中，请稍候再发送'); return }
    const payload = editResendPayload({
      text: editingText.value, imgs: editImgs.value, atts: editAtts.value,
      mentions: editMentions.value, histRefs: editHistRefs.value, skills: editSkills.value
    }, old)
    if (payload.empty) { notify.warning('内容不能为空'); return }
    if (payload.unchanged) { cancelEdit(); return }
    if (ctx.loading()) { notify.warning('当前正在回答，请先停止或稍候'); return }
    const targetId = old.messageId
    if (!targetId) { notify.warning('该消息尚未落库，暂不能编辑重发'); return }
    cancelEdit()
    // 旧分支交给后端软删留档，本地截断到该轮之前；切回走分支切换接口重拉历史。
    const nu = reactive(payload.newUser)
    ctx.setMessages([...ctx.messages().slice(0, mi), nu])
    ctx.resend(...editResendArgs(payload, targetId, nu))
  }

  return {
    editingIdx, editingMessageId, editingText, editingBusy, editingRef,
    editImgs, editAtts, editMentions, editHistRefs, editSkills, editAttachInput,
    setEditingRef, setEditAttachInputRef, autosizeEditing, probeEditAtts,
    editMessage, cancelEdit, pickEditFiles, onEditAttachChange, editAddCount,
    toggleEditSkill, toggleEditHistoryRef, toggleEditMention, onEditPaste,
    removeEditImage, removeEditAtt, removeEditMention, editHistPool,
    addEditSkill, removeEditSkill, addEditHistRef, removeEditHistRef,
    previewEditImage, onEditKeydown, confirmEdit
  }
}
