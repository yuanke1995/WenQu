import test from 'node:test'
import assert from 'node:assert/strict'
import {
  clusterize, markGrowingCluster, clusterOpen, clusterAutoOpen, toggleCluster,
  clusterTools, clusterRunning, clusterHasError, clusterProcCount,
  clusterKinds, clusterTitle, clusterHeadKind, clusterLiveText, clusterLiveRows,
} from '../src/chat/processCluster.js'

const tool = (name, status = 'done') => ({ name, status })
const group = (...tools) => ({ kind: 'group', tools })
const think = () => ({ kind: 'think', from: 0, to: 2 })
const proc = () => ({ kind: 'process', from: 0, to: 2 })
const text = () => ({ kind: 'text', from: 0, to: 2 })

test('聚簇：连续 think/process/group 合簇，text 断簇；单段 think 不包壳，单段 group 包壳', () => {
  const view = clusterize([think(), proc(), text(), group(tool('read_file'))])
  assert.equal(view.length, 3)
  assert.equal(view[0].kind, 'cluster')
  assert.equal(view[0].key, 'c0')
  assert.equal(view[0].segs.length, 2)
  assert.equal(view[1].kind, 'text')
  assert.equal(view[2].kind, 'cluster')
  assert.equal(view[2].segs.length, 1)

  const single = clusterize([think()])
  assert.equal(single[0].kind, 'think')
})

test('生长簇：只有末尾簇标记 grow，且每次重置旧标记', () => {
  const view = clusterize([group(tool('a')), text(), group(tool('b'))])
  markGrowingCluster(view)
  assert.equal(view[0].grow, false)
  assert.equal(view[2].grow, true)
})

test('展开态：用户点过优先，否则跑动中末尾簇自动展开', () => {
  const cl = { key: 'c0', grow: true }
  assert.equal(clusterOpen({ loading: true }, cl), true)
  assert.equal(clusterOpen({ loading: true, _clOpen: { c0: false } }, cl), false)
  assert.equal(clusterOpen({ loading: false }, cl), false)
  assert.equal(clusterAutoOpen({ loading: true }, cl), true)
  assert.equal(clusterAutoOpen({ loading: true, _clOpen: { c0: true } }, cl), false)
  const m = { loading: true, _clOpen: {} }
  toggleCluster(m, cl)
  assert.equal(m._clOpen.c0, false)
})

test('簇内汇总：工具数、运行/失败、过程段数', () => {
  const cl = { segs: [think(), group(tool('search_knowledge', 'start'), tool('read_file', 'error')), proc()] }
  assert.equal(clusterTools(cl).length, 2)
  assert.equal(clusterRunning(cl), true)
  assert.equal(clusterHasError(cl), true)
  assert.equal(clusterProcCount(cl), 2)
  assert.equal(clusterTools(null).length, 0)
})

test('簇头：类别顺序、标题 1/2/3 类、头部图标类别', () => {
  const searchOnly = { segs: [group(tool('search_knowledge'))] }
  assert.deepEqual(clusterKinds(searchOnly), ['search'])
  assert.equal(clusterTitle(searchOnly), '已检索知识库')
  assert.equal(clusterHeadKind(searchOnly), 'search')

  const webOnly = { segs: [group(tool('webSearch'))] }
  assert.deepEqual(clusterKinds(webOnly), ['web'])
  assert.equal(clusterTitle(webOnly), '已联网搜索')
  assert.equal(clusterHeadKind(webOnly), 'web')

  const mixed = { segs: [think(), group(tool('webSearch'), tool('read_file'))] }
  assert.deepEqual(clusterKinds(mixed), ['thinking', 'web', 'tools'])
  assert.equal(clusterTitle(mixed), '已完成分析，已联网搜索，已调用工具等')
  assert.equal(clusterHeadKind(mixed), 'thinking')

  const two = { segs: [group(tool('search_knowledge'), tool('read_file'))] }
  assert.equal(clusterTitle(two), '已检索知识库并调用工具')
  assert.equal(clusterTitle({ segs: [] }), '已完成分析')
})

test('跑动标题：未跑返回 null；单类/多类生成「正在…」', () => {
  assert.equal(clusterLiveText({ loading: false }, { segs: [group(tool('a'))], grow: false }), null)
  assert.equal(clusterLiveText({ loading: true }, { segs: [group(tool('a', 'start'))], grow: true }), '正在调用工具')
  const mixed = { segs: [think(), group(tool('search_knowledge', 'start'), tool('read_file', 'start'))], grow: true }
  assert.equal(clusterLiveText({ loading: true }, mixed), '正在分析并检索知识库等')
})

test('帽体轻量行：思考挂到后一个工具行，末尾思考自成一组', () => {
  const cl = { segs: [think(), group(tool('a'), tool('b')), proc()] }
  const rows = clusterLiveRows(cl)
  assert.equal(rows.length, 3)
  assert.equal(rows[0].idx, 1)
  assert.equal(rows[0].notes.length, 1)
  assert.equal(rows[1].idx, 2)
  assert.equal(rows[1].notes, undefined)
  assert.equal(rows[2].tailNotes.length, 1)
})
