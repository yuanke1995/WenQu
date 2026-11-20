import test from 'node:test'
import assert from 'node:assert/strict'
import {
  WAVE_VIEWBOX, WAVE_STROKE, WAVE_AMPLITUDE, WAVE_ROWS, WAVE_PATHS,
  WAVE_START_X, WAVE_BUMP_W, WAVE_EXTENT_X,
} from '../src/chat/waveGlyph.js'

// 这一组钉的是「状态行那枚记号必须还是 logo 的三道波线」：
// 曾经退回成两条线，14px 下读作「≈」，和品牌标对不上。

test('状态行记号：三道波线，与品牌标同数', () => {
  assert.equal(WAVE_PATHS.length, 3)
  assert.equal(WAVE_ROWS.length, 3)
})

test('三道共用一条波形，只差基线 y —— 与 BrandMark 的 y=25/35/45 同构', () => {
  const body = d => d.replace(/^M[\d.]+ [\d.]+/, '')
  assert.equal(new Set(WAVE_PATHS.map(body)).size, 1, '三条波线的曲率必须一样，只平移')
  WAVE_PATHS.forEach((d, i) => {
    assert.ok(d.startsWith(`M${WAVE_START_X} ${WAVE_ROWS[i]}`), `第 ${i + 1} 道的基线应在 y=${WAVE_ROWS[i]}`)
    // 一段 c + 一段 s = 两个波峰（logo 里那三条就是这个形状），不是单峰符号
    assert.match(d, /c[\d.]+-[\d.]+ [\d.]+-[\d.]+ [\d.]+ 0s[\d.]+ [\d.]+ [\d.]+ 0$/, `第 ${i + 1} 道应是两峰波形`)
  })
})

test('行距要留得下缝：相邻两道之间 > 2×振幅 + 线宽（否则 14px 糊成一坨）', () => {
  const spacing = WAVE_ROWS[1] - WAVE_ROWS[0]
  for (let i = 2; i < WAVE_ROWS.length; i++) {
    // 用浮点容差：4.2 与 4.200000000000001 是同一条等距
    assert.ok(Math.abs(WAVE_ROWS[i] - WAVE_ROWS[i - 1] - spacing) < 1e-6, '三道等距')
  }
  assert.ok(spacing > 2 * WAVE_AMPLITUDE + WAVE_STROKE, `行距 ${spacing} 不足以让线宽 ${WAVE_STROKE} 分开`)
})

test('整枚记号落在视窗内：上下不裁切，水平居中', () => {
  const top = WAVE_ROWS[0] - WAVE_AMPLITUDE - WAVE_STROKE / 2
  const bottom = WAVE_ROWS[WAVE_ROWS.length - 1] + WAVE_AMPLITUDE + WAVE_STROKE / 2
  assert.ok(top > 0 && bottom < WAVE_VIEWBOX, `竖向外扩 ${top}..${bottom} 超出了 ${WAVE_VIEWBOX}`)
  assert.ok(WAVE_EXTENT_X.left >= 0 && WAVE_EXTENT_X.right <= WAVE_VIEWBOX, '横向超出视窗会被裁掉')
  const mid = (WAVE_EXTENT_X.left + WAVE_EXTENT_X.right) / 2
  assert.ok(Math.abs(mid - WAVE_VIEWBOX / 2) < 0.2, `水平应居中，实际中线 ${mid}`)
  assert.ok(WAVE_BUMP_W > 0 && WAVE_START_X > 0)
})