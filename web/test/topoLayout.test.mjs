import test from 'node:test'
import assert from 'node:assert/strict'
import { topoWindow, focusedGraph, TOPO_LIST_ROW_H, TOPO_LIST_VIEW_H } from '../src/utils/topoLayout.js'

test('窗口化：顶部时从第 1 行开始，无上占位', () => {
  const w = topoWindow(128, 0)
  assert.equal(w.from, 1)
  assert.equal(w.padTop, 0)
  assert.ok(w.end >= Math.ceil(TOPO_LIST_VIEW_H / TOPO_LIST_ROW_H), '至少铺满一屏')
  assert.equal(w.padBottom, (128 - w.end) * TOPO_LIST_ROW_H)
})

test('窗口化：滚动到中部时上下占位之和补齐总高度', () => {
  const total = 300
  const w = topoWindow(total, 2000)
  assert.ok(w.start > 0, '中部窗口不应从第一行开始')
  assert.equal(w.from, w.start + 1)
  const rendered = (w.end - w.start) * TOPO_LIST_ROW_H
  assert.equal(w.padTop + rendered + w.padBottom, total * TOPO_LIST_ROW_H)
})

test('窗口化：滚到底时下占位为 0、末行等于总数', () => {
  const total = 60
  const bottom = Math.max(0, total * TOPO_LIST_ROW_H - TOPO_LIST_VIEW_H)
  const w = topoWindow(total, bottom)
  assert.equal(w.padBottom, 0)
  assert.equal(w.to, total)
})

test('窗口化：空列表不动占位', () => {
  const w = topoWindow(0, 0)
  assert.deepEqual([w.from, w.to, w.padTop, w.padBottom], [0, 0, 0, 0])
})

test('窗口化：数据骤减时旧滚动位置被夹住，不会出现整屏空白', () => {
  const w = topoWindow(3, 5000)
  assert.equal(w.start, 2)
  assert.equal(w.to, 3)
  assert.equal(w.padTop, 2 * TOPO_LIST_ROW_H)
  assert.equal(w.padBottom, 0)
})

const G = { pad: 16, rowH: 64, rowGap: 14, nodeW: 216, gapG: 64, subW: 248 }

test('聚焦布局：≤8 位子智能体排一列，画布高度随数量增长', () => {
  const items = Array.from({ length: 5 }, (_, i) => ({ key: 's' + i, agent: { id: 's' + i }, miss: false }))
  const g = focusedGraph(items, G)
  assert.ok(new Set(g.nodes.map(n => n.x)).size === 1, '一列')
  assert.equal(g.H, 5 * (G.rowH + G.rowGap) - G.rowGap + G.pad * 2)
  assert.equal(g.edges.length, 5)
  assert.ok(g.nodes.every(n => n.x > g.mainX + G.nodeW), '子节点在主节点右侧')
})

test('聚焦布局：9 位起排两列，画布宽度相应增加', () => {
  const items = Array.from({ length: 9 }, (_, i) => ({ key: 's' + i, agent: { id: 's' + i }, miss: false }))
  const g = focusedGraph(items, G)
  assert.equal(new Set(g.nodes.map(n => n.x)).size, 2, '两列')
  const rowsPerCol = G.rowH + G.rowGap
  assert.ok(g.H <= 5 * rowsPerCol + G.pad * 2, '每列 5 行，高度不随总量暴涨')
  assert.ok(g.W > 2 * G.subW, '两列宽度翻倍')
})

test('聚焦布局：失效引用占位与子节点同列，边标 miss', () => {
  const items = [
    { key: 'a', agent: { id: 'a' }, miss: false },
    { key: 'xgone', id: 'gone', agent: null, miss: true }
  ]
  const g = focusedGraph(items, G)
  assert.equal(g.nodes.length, 2)
  assert.equal(g.edges.filter(e => e.miss).length, 1)
})

test('聚焦布局：空清单也能给出一屏高度（组件据此显示空态）', () => {
  const g = focusedGraph([], G)
  assert.equal(g.nodes.length, 0)
  assert.equal(g.H, G.rowH + G.pad * 2)
  assert.ok(g.mainY >= 0)
})
