import test from 'node:test'
import assert from 'node:assert/strict'
import { seedEditRows, editResendPayload, editResendArgs, editKeydownIntent,
         toggleMentionRows, toggleHistRefRows, addSkillRows } from '../src/chat/editResend.js'
import { useEditResend } from '../src/chat/useEditResend.js'

const deps = {
  isPastedText: a => !!a.paste,
  histItemDigest: h => '摘要:' + (h.content || h.messageId)
}

test('播种：附件合并展示元数据与重发凭据，只展示项标为不可重发', () => {
  const m = {
    content: '问', messageId: 'u1',
    attachData: [{ name: 'a.txt', mime: 'text/plain', fileId: 'f1' }],
    attachments: [{ name: 'a.txt', mime: 'text/plain', size: 12, paste: true, text: 'x' },
                  { name: 'b.txt', mime: 'text/plain', size: 8 }],
    images: ['https://x/a.png'],
    mentions: [{ type: 'kb', id: 'k1', name: '库' }],
    historyRefs: [{ messageId: 'h1', role: 'user' }, null],
    skills: ['s1']
  }
  const s = seedEditRows(m, deps)
  assert.equal(s.atts.length, 2)
  assert.equal(s.atts[0].fileId, 'f1')
  assert.equal(s.atts[0].size, 12)
  assert.equal(s.atts[0].paste, true)
  assert.equal(s.atts[1].unavailable, true)
  assert.equal(s.atts[1].fileId, '')
  assert.deepEqual(s.imgs, [{ dataUrl: 'https://x/a.png' }])
  assert.deepEqual(s.histRefs, [{ messageId: 'h1', role: 'user', digest: '摘要:h1' }])
  assert.deepEqual(s.skills, ['s1'])
  // 不修改原消息
  assert.deepEqual(m.historyRefs[0], { messageId: 'h1', role: 'user' })
})

test('载荷：只带有效凭据；URL 图保留在卡但不进重发；失效附件不算空', () => {
  const old = { content: '原问', images: ['data:image/png;base64,AA'],
                attachData: [{ name: 'a', mime: 'text/plain', fileId: 'f1' }],
                mentions: [], historyRefs: [], skills: [], deepThink: true, variantCount: 2 }
  const p = editResendPayload({
    text: ' 改后 ', imgs: [{ dataUrl: 'data:image/png;base64,AA' }, { dataUrl: 'https://x/b.png' }],
    atts: [{ name: 'a', mime: 'text/plain', fileId: 'f1', size: 3 },
           { name: '旧', mime: 'text/plain', fileId: '', size: 1, expired: true }],
    mentions: [], histRefs: [], skills: []
  }, old, () => 1234)
  assert.equal(p.text, '改后')
  assert.deepEqual(p.atts, [{ name: 'a', mime: 'text/plain', fileId: 'f1' }])
  assert.deepEqual(p.imgs, ['data:image/png;base64,AA'])
  assert.equal(p.imgsAll.length, 2)
  assert.equal(p.empty, false)
  assert.equal(p.unchanged, false)
  assert.equal(p.deep, true)
  assert.equal(p.newUser.time, 1234)
  assert.equal(p.newUser.variantCount, 3)
  assert.equal(p.newUser.variantIndex, 3)

  const blank = editResendPayload({ text: ' ', imgs: [], atts: [], mentions: [], histRefs: [], skills: [] }, old)
  assert.equal(blank.empty, true)
  const deadOnly = editResendPayload({ text: '', imgs: [], atts: [{ name: 'x', fileId: '', expired: true }],
                                       mentions: [], histRefs: [], skills: [] }, old)
  assert.equal(deadOnly.empty, false)
})

test('未改动：六个维度逐一变化都会判为改动', () => {
  const old = { content: '问', images: ['data:1'], attachData: [{ name: 'a', mime: 't', fileId: 'f' }],
                mentions: [{ type: 'kb', id: 'k1' }], historyRefs: [{ messageId: 'h1' }], skills: ['s1'] }
  const base = { text: '问', imgs: [{ dataUrl: 'data:1' }],
                 atts: [{ name: 'a', mime: 't', fileId: 'f' }],
                 mentions: [{ type: 'kb', id: 'k1' }],
                 histRefs: [{ messageId: 'h1' }], skills: ['s1'] }
  assert.equal(editResendPayload(base, old).unchanged, true)
  const dims = [
    { text: '问2' }, { imgs: [{ dataUrl: 'data:2' }] },
    { atts: [{ name: 'a', mime: 't', fileId: 'g' }] },
    { mentions: [{ type: 'kb', id: 'k2' }] },
    { histRefs: [{ messageId: 'h2' }] }, { skills: ['s2'] }
  ]
  for (const d of dims) assert.equal(editResendPayload({ ...base, ...d }, old).unchanged, false, JSON.stringify(d))
})

test('引擎实参：13 个参数位固定，历史引用按 {messageId} 传', () => {
  const p = editResendPayload({ text: 'q', imgs: [{ dataUrl: 'data:1' }], atts: [], mentions: [],
                                histRefs: [{ messageId: 'h1' }, { messageId: 'h2' }], skills: [] },
                              { content: 'old', deepThink: false })
  const nu = { role: 'user' }
  const args = editResendArgs(p, 'edit-1', nu)
  assert.equal(args.length, 13)
  assert.equal(args[0], 'q')
  assert.deepEqual(args[1], ['data:1'])
  assert.equal(args[5], false)
  assert.deepEqual(args[10], [{ messageId: 'h1' }, { messageId: 'h2' }])
  assert.equal(args[11], 'edit-1')
  assert.equal(args[12], nu)
})

test('键盘意图：输入法放行、面板优先、Enter 修饰键换行、Esc 取消', () => {
  assert.equal(editKeydownIntent({ isComposing: true, key: 'Enter' }), null)
  assert.equal(editKeydownIntent({ keyCode: 229, key: 'Enter' }), null)
  assert.equal(editKeydownIntent({ key: 'Enter' }, { panelOpen: true, panelNav: () => true }), 'panel')
  assert.equal(editKeydownIntent({ key: 'Enter' }), 'send')
  assert.equal(editKeydownIntent({ key: 'Enter', shiftKey: true }), null)
  assert.equal(editKeydownIntent({ key: 'Escape' }), 'cancel')
  assert.equal(editKeydownIntent({ key: 'a' }), null)
})

test('@ 引用：智能体单主语替换、其它类型限额、再点移除', () => {
  const rows = [{ type: 'kb', id: 'k1' }, { type: 'agent', id: 'a1' }]
  const replaced = toggleMentionRows(rows, 'agent', { id: 'a2', name: '助手2' }, 3)
  assert.deepEqual(replaced.rows, [{ type: 'kb', id: 'k1' }, { type: 'agent', id: 'a2', name: '助手2', kbId: '' }])
  const limited = toggleMentionRows(replaced.rows, 'doc', { id: 'd1', fileName: '文档', kbId: 'kb9' }, 2)
  assert.match(limited.error, /最多引用 2 个/)
  assert.deepEqual(limited.rows, replaced.rows)
  const added = toggleMentionRows(rows, 'doc', { id: 'd1', fileName: '文档', kbId: 'kb9' }, 5)
  assert.deepEqual(added.rows[2], { type: 'doc', id: 'd1', name: '文档', kbId: 'kb9' })
  assert.equal(toggleMentionRows(added.rows, 'doc', { id: 'd1' }, 5).rows.length, 2)
})

test('# 引用与技能：限额、候选不在池内不添加、摘要重算', () => {
  const pool = [{ messageId: 'h1', role: 'user', content: '一' }, { messageId: 'h2', role: 'ai', content: '二' }]
  const one = toggleHistRefRows([], { messageId: 'h1' }, pool, 2, deps.histItemDigest)
  assert.deepEqual(one.rows, [{ messageId: 'h1', role: 'user', digest: '摘要:一' }])
  const full = toggleHistRefRows(one.rows.concat({ messageId: 'h2' }), { messageId: 'h3' }, pool, 2, deps.histItemDigest)
  assert.match(full.error, /最多引用 2 条/)
  assert.equal(toggleHistRefRows(one.rows, { messageId: 'h1' }, pool, 2, deps.histItemDigest).rows.length, 0)
  assert.equal(toggleHistRefRows([], { messageId: 'xx' }, pool, 2, deps.histItemDigest).rows.length, 0)
  assert.match(addSkillRows(['a', 'b', 'c'], 'd').error, /最多选用 3 个/)
  assert.deepEqual(addSkillRows(['a'], 'a').rows, ['a'])
  assert.deepEqual(addSkillRows(['a'], 'b').rows, ['a', 'b'])
})

const makeCtx = (over = {}) => {
  let msgs = [{ role: 'user', content: '原问', messageId: 'u1', images: [], attachData: [],
                attachments: [], mentions: [], historyRefs: [], skills: [] }]
  let loading = false
  let addOpen = false
  const warnings = []
  const resends = []
  const closed = []
  const ctx = {
    messages: () => msgs,
    setMessages: v => { msgs = v },
    loading: () => loading,
    histPool: () => [{ messageId: 'h1', role: 'user', content: '历史问' }],
    addOpen: () => addOpen,
    addTarget: () => 'edit',
    addOnEdit: () => false,
    closeAddPanel: () => closed.push(1),
    onPanelNavKeydown: () => false,
    maxMentions: () => 5,
    maxHistoryRefs: () => 10,
    resend: (...args) => resends.push(args),
    notify: { warning: t => warnings.push(t), error: t => warnings.push(t) },
    isPastedText: deps.isPastedText,
    histItemDigest: deps.histItemDigest,
    splitAtts: async () => ({ dead: [] }),
    resolveImg: u => u,
    openImages: () => {},
    toDataUrl: async () => 'data:image/png;base64,ZZ',
    addFiles: () => {},
    takePastedFiles: () => false,
    takePastedText: () => false,
    ...over
  }
  return { ctx, get msgs () { return msgs }, setLoading: v => { loading = v },
           setAddOpen: v => { addOpen = v }, warnings, resends, closed }
}

test('编辑卡：播种后探测失效附件；异步抓图只在仍编辑同一轮时回填', async () => {
  const c = makeCtx({
    splitAtts: async () => ({ dead: [{ fileId: 'f1' }] }),
    toDataUrl: async () => 'data:image/png;base64,ZZ'
  })
  c.ctx.messages = () => [{ role: 'user', content: '原问', messageId: 'u1',
                            attachData: [{ name: 'a', mime: 't', fileId: 'f1' }],
                            images: ['https://x/a.png'], mentions: [], historyRefs: [], skills: [] }]
  const e = useEditResend(c.ctx)
  e.editMessage(0)
  assert.equal(e.editingIdx.value, 0)
  assert.equal(e.editingText.value, '原问')
  await new Promise(r => setTimeout(r, 0))
  assert.equal(e.editAtts.value[0].expired, true)
  assert.equal(e.editAtts.value[0].probed, true)
  assert.equal(e.editImgs.value[0].dataUrl, 'data:image/png;base64,ZZ')
  e.cancelEdit()
  assert.equal(e.editingIdx.value, null)
  assert.equal(e.editAtts.value.length, 0)
})

test('编辑卡：未落库 / 回答中挡住；取消时收掉挂在本卡的加号面板', () => {
  const c = makeCtx()
  c.ctx.messages = () => [{ role: 'user', content: '问', messageId: '' }]
  const e = useEditResend(c.ctx)
  e.editMessage(0)
  assert.match(c.warnings[0], /尚未落库/)
  c.setLoading(true)
  c.ctx.messages = () => [{ role: 'user', content: '问', messageId: 'u1' }]
  e.editMessage(0)
  assert.equal(e.editingIdx.value, null)
  c.setLoading(false)
  e.editMessage(0)
  c.setAddOpen(true)
  e.cancelEdit()
  assert.equal(c.closed.length, 1)
})

test('重发：未改动只收卡；改动后截断旧分支并以固定实参开流', () => {
  const c = makeCtx()
  const e = useEditResend(c.ctx)
  e.editMessage(0)
  e.confirmEdit()
  assert.equal(c.resends.length, 0)
  assert.equal(e.editingIdx.value, null)

  e.editMessage(0)
  e.editingText.value = '改后'
  const before = c.msgs.length
  e.confirmEdit()
  assert.equal(c.resends.length, 1)
  assert.equal(c.resends[0][0], '改后')
  assert.equal(c.resends[0][11], 'u1')
  assert.equal(c.msgs.length, before)
  assert.equal(c.msgs[0].variantCount, 2)
  assert.equal(c.msgs[0].content, '改后')
})

test('重发：上传中 / 空内容 / 回答中各有明确提示，不发流', () => {
  const c = makeCtx()
  const e = useEditResend(c.ctx)
  e.editMessage(0)
  e.editAtts.value = [{ name: 'a', mime: 't', fileId: 'f1', uploading: true }]
  e.editingText.value = '改后'
  e.confirmEdit()
  assert.match(c.warnings.at(-1), /附件还在上传中/)
  assert.equal(c.resends.length, 0)

  e.editAtts.value = []
  e.editingText.value = '   '
  e.confirmEdit()
  assert.match(c.warnings.at(-1), /内容不能为空/)

  e.editingText.value = '改后'
  c.setLoading(true)
  e.confirmEdit()
  assert.match(c.warnings.at(-1), /当前正在回答/)
  assert.equal(c.resends.length, 0)
})

test('编辑卡键盘：面板消费导航；Enter 重发、Esc 取消', () => {
  const c = makeCtx({ onPanelNavKeydown: () => true })
  const e = useEditResend(c.ctx)
  e.editMessage(0)
  c.ctx.addOnEdit = () => true
  c.setAddOpen(true)
  let prevented = 0
  e.onEditKeydown({ key: 'Enter', preventDefault: () => { prevented++ } })
  assert.equal(c.resends.length, 0)
  c.ctx.addOnEdit = () => false
  e.editingText.value = '改后'
  e.onEditKeydown({ key: 'Enter', preventDefault: () => { prevented++ } })
  assert.equal(c.resends.length, 1)
  assert.equal(prevented, 1)

  const esc = useEditResend(makeCtx().ctx)
  esc.editMessage(0)
  esc.onEditKeydown({ key: 'Escape', preventDefault: () => { prevented++ } })
  assert.equal(esc.editingIdx.value, null)
  assert.equal(prevented, 2)
})

test('编辑卡随行材料：智能体替换、限额提示、候选排除本轮', () => {
  const c = makeCtx()
  const e = useEditResend(c.ctx)
  e.editMessage(0)
  e.toggleEditMention('agent', { id: 'a1', name: '甲' })
  e.toggleEditMention('agent', { id: 'a2', name: '乙' })
  assert.deepEqual(e.editMentions.value.map(x => x.id), ['a2'])
  e.toggleEditSkill('s1'); e.toggleEditSkill('s2'); e.toggleEditSkill('s3')
  e.toggleEditSkill('s4')
  assert.match(c.warnings.at(-1), /最多选用 3 个/)
  assert.equal(e.editSkills.value.length, 3)
  e.toggleEditHistoryRef({ messageId: 'h1' })
  assert.equal(e.editHistRefs.value.length, 1)
  assert.equal(e.editAddCount.value, 3 + 1 + 1)
  assert.equal(e.editHistPool.value.length, 1)
})
