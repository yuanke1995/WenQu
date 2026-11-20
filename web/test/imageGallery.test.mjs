import test from 'node:test'
import assert from 'node:assert/strict'
import { galleryFromRendered } from '../src/chat/imageGallery.js'

// 后端 images 池：本轮 17 张命中块配图（正文只引用其中几张）
const pool = Array.from({ length: 17 }, (_, i) => `raw/${i + 1}`)
const resolve = u => `signed:${u}`

test('灯箱只收正文渲染出的图，顺序与正文一致（4 张 vs 池里 17 张）', () => {
  const entries = [
    { seq: 2, src: 'signed:raw/2' },
    { seq: 7, src: 'signed:raw/7' },
    { seq: 9, src: 'signed:raw/9' },
    { seq: 13, src: 'signed:raw/13' }
  ]
  const r = galleryFromRendered(entries, pool, entries[3], resolve)
  assert.deepEqual(r.urls, ['signed:raw/2', 'signed:raw/7', 'signed:raw/9', 'signed:raw/13'])
  assert.equal(r.index, 3) // 点最后一张 → 4/4，不是旧代码的 1/17
})

test('点原生 markdown 图（无 data-seq）按 DOM 下标定位，不再回落 0', () => {
  const entries = [
    { seq: 6, src: 'signed:raw/6' },
    { seq: 0, src: 'https://cdn.example/one.png' },
    { seq: 0, src: 'https://cdn.example/two.png' }
  ]
  const r = galleryFromRendered(entries, pool, entries[2], resolve)
  assert.deepEqual(r.urls, ['signed:raw/6', 'https://cdn.example/one.png', 'https://cdn.example/two.png'])
  assert.equal(r.index, 2)
})

test('越界 seq 与重复图：跳过无效项、去重，点击项丢失时回落首图', () => {
  const entries = [
    { seq: 99, src: '' },           // 池里没有第 99 张 → 这类图 renderMd 本来也不会渲染成 img
    { seq: 1, src: 'signed:raw/1' },
    { seq: 1, src: 'signed:raw/1' }, // 同一张出现两次 → 只留一张
    { seq: 0, src: 'https://cdn.example/x.png' }
  ]
  const r = galleryFromRendered(entries, pool, entries[3], resolve)
  assert.deepEqual(r.urls, ['signed:raw/1', 'https://cdn.example/x.png'])
  assert.equal(r.index, 1)

  const miss = galleryFromRendered(entries, pool, { seq: 5, src: '' }, resolve)
  assert.equal(miss.index, 0)
})

test('整池为空但正文有原生图时仍能预览', () => {
  const entries = [{ seq: 0, src: 'data:image/png;base64,AAA' }]
  assert.deepEqual(galleryFromRendered(entries, [], entries[0], resolve), {
    urls: ['data:image/png;base64,AAA'], index: 0
  })
})
