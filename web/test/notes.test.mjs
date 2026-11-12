import test from 'node:test'
import assert from 'node:assert/strict'
import { groupHasBar, noteText, noteTag, noteOpen, noteGrowing, toggleNote, notePeek, tailNotesTag, tailNotesPeek, tailNotesGrowing, tailOpen, toggleTail } from '../src/chat/notes.js'

const think = { kind: 'think', from: 0, to: 3 }
const proc = { kind: 'process', from: 0, to: 2 }

test('分组条：多工具才出组条', () => {
  assert.equal(groupHasBar({ tools: [{}, {}] }), true)
  assert.equal(groupHasBar({ tools: [{}] }), false)
  assert.equal(groupHasBar(null), false)
})

test('note：思考与独白走各自的切片/标题/展开/提要', () => {
  const m = { thinking: '想', processText: '说' }
  assert.equal(noteText(m, think), '想')
  assert.equal(noteText(m, proc), '说')
  assert.equal(noteTag(think), '深度思考')
  assert.equal(noteTag(proc), '过程说明')
  assert.equal(notePeek(m, think), '想')
  assert.equal(notePeek(m, proc), '说')
})

test('note 展开/生长：思考看 thinkLoading，独白看 loading+末段', () => {
  const m = { loading: true, thinkLoading: true, timeline: [think, proc], thinking: 'x', processText: 'y' }
  assert.equal(noteOpen(m, think), true)
  assert.equal(noteOpen(m, proc), true)
  assert.equal(noteGrowing(m, think), true)
  assert.equal(noteGrowing({ ...m, loading: false }, proc), false)
  const x = {}
  toggleNote(x, think)
  assert.equal(x._thinkOpen[0], true)
  toggleNote(x, proc)
  assert.equal(x._procOpen[0], true)
})

test('tail：类别名、提要、生长、开合', () => {
  assert.equal(tailNotesTag([]), '')
  assert.equal(tailNotesTag([think]), '深度思考')
  assert.equal(tailNotesTag([proc, proc]), '过程说明')
  assert.equal(tailNotesTag([think, proc]), '思考与说明')
  assert.equal(tailNotesPeek({ thinking: 'a', processText: 'b' }, [{ kind: 'think', from: 0, to: 1 }, proc]), 'a')
  assert.equal(tailNotesGrowing({ loading: true, timeline: [proc] }, [proc]), true)
  const m = {}
  const cl = { key: 'c0' }
  toggleTail(m, cl)
  assert.equal(tailOpen(m, cl), true)
})
