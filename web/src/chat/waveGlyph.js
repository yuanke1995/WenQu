// @ts-check
// 跑动状态行前的那枚「活水」记号 = 问渠品牌标 BrandMark.vue 里那三道波线。
// logo 在 64 视窗画 y=25/35/45 三条整幅波（stroke 5.4），照 1/4 缩到 16 视窗后线厚与
// 行距几乎是 1:2，14px 下会糊成一团；所以波形照抄（两个波峰、同向起伏），只把行距放到
// 4.4、线宽收到 1.3、波长拉宽到 5.6，保住「三道」的辨识度。
// 判据：14px 下每一道之间要留得下 0.8 个单位的空隙（见 test/traceWave.test.mjs）。
// 改这里等于改状态行的记号——别再退回两条线，那是「≈」，不是 logo。
export const WAVE_VIEWBOX = 16
export const WAVE_STROKE = 1.3
/** 单侧起伏高度 */
export const WAVE_AMPLITUDE = 1.15
/** 第一个波峰的起点 x；两个波峰等宽，整枚记号因此水平居中 */
export const WAVE_START_X = 2.4
/** 单个波峰的横向宽度 */
export const WAVE_BUMP_W = 5.6
/** 三道波线的基线 y（自上而下，等距 4.4） */
export const WAVE_ROWS = [4, 8.2, 12.4]

export const WAVE_PATHS = WAVE_ROWS.map(y => {
  const kx = WAVE_BUMP_W / 3
  return `M${WAVE_START_X} ${y}c${kx.toFixed(2)}-${WAVE_AMPLITUDE} ${(kx * 2).toFixed(2)}-${WAVE_AMPLITUDE} ${WAVE_BUMP_W} 0`
    + `s${kx.toFixed(2)} ${WAVE_AMPLITUDE} ${WAVE_BUMP_W} 0`
})

/** 整枚记号的横向范围（含线宽），供测试检查不裁切 */
export const WAVE_EXTENT_X = {
  left: WAVE_START_X - WAVE_STROKE / 2,
  right: WAVE_START_X + WAVE_BUMP_W * 2 + WAVE_STROKE / 2,
}