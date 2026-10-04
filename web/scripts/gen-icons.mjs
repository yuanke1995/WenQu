// 生成 apple-touch-icon.png（180×180）——iOS「添加到主屏幕」不认 SVG，必须给 PNG。
// 零依赖：只用 Node 内置 zlib 手写 PNG 编码（IHDR/IDAT/IEND + CRC32），
// 不引 sharp / pngjs / canvas（为一张 180×180 的图标引入二进制依赖不划算）。
//
// 用法：node scripts/gen-icons.mjs   （在 web/ 下执行）
//
// 图形与 public/favicon.svg 同源：蓝渐变底 + 三道活水波线 + 源头圆点。
// 两处与 favicon 的**有意**差异：
//  ① 满幅方形不透明，**不预切圆角**。iOS 会自己给 apple-touch-icon 套一层圆角遮罩，
//     自己再切一次会变成「圆角方块里套圆角方块」的双层白角。
//  ② 无透明留白。图标内容铺满整张画布，避免系统缩放后四周出现空白边。
import { deflateSync } from 'node:zlib'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'

const SIZE = 180
// favicon 的图形只占 64 坐标系里的 3~61（rect x=3 y=3 w=58 h=58），四周各留 3 的透明边。
// 这里保留同样的边距比例（即按 0~64 整幅映射），这样图标形状与网页/标签页完全一致，
// 由 iOS 系统自己裁圆角。若强行把 3~61 拉满整张画布，图形会被放大到出血。
const SS = 3 // 3×3 超采样抗锯齿（波线是曲线，1× 直接画锯齿明显）
const W = SIZE * SS
const U = W / 64 // 64 坐标系的一单位 → 超采样画布像素

// ==================== 品牌图形定义（favicon.svg 的 64 坐标系）====================
const GRAD_FROM = [0x5e, 0x93, 0xf5] // #5E93F5
const GRAD_TO = [0x2a, 0x5f, 0xe0]   // #2A5FE0
const STROKE_W = 5.4                  // 波线线宽
const DOT = { cx: 46, cy: 16.5, r: 3.6 }

const lerp = (a, b, t) => a + (b - a) * t

/** 背景渐变：对齐 favicon.svg 的 linearGradient(x1=14,y1=4 → x2=50,y2=60, userSpaceOnUse) */
function gradColor (x64, y64) {
  const dx = 50 - 14, dy = 60 - 4
  let t = ((x64 - 14) * dx + (y64 - 4) * dy) / (dx * dx + dy * dy)
  t = Math.max(0, Math.min(1, t))
  return [lerp(GRAD_FROM[0], GRAD_TO[0], t) | 0,
          lerp(GRAD_FROM[1], GRAD_TO[1], t) | 0,
          lerp(GRAD_FROM[2], GRAD_TO[2], t) | 0]
}

/** 三次贝塞尔采样成折线（比正弦近似准确，且能直接对上 SVG 的 path） */
function bezier (p0, p1, p2, p3, n) {
  const out = []
  for (let i = 0; i <= n; i++) {
    const t = i / n, u = 1 - t
    out.push([
      u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
      u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]
    ])
  }
  return out
}
function segDist (px, py, a, b) {
  const vx = b[0] - a[0], vy = b[1] - a[1]
  const wx = px - a[0], wy = py - a[1]
  const len2 = vx * vx + vy * vy
  const t = len2 ? Math.max(0, Math.min(1, (wx * vx + wy * vy) / len2)) : 0
  return Math.hypot(wx - t * vx, wy - t * vy)
}

// wave1: M18 25 c 4.5,-5.2 9,-5.2 13.5,0  s 9,5.2 13.5,0
// s(smooth) 的首个控制点是上一控制点关于起点的镜像：reflect(27,19.8 about 31.5,25) = (36,30.2)
const WAVE_PATHS = [
  [[18, 25], [22.5, 19.8], [27, 19.8], [31.5, 25]],
  [[31.5, 25], [36, 30.2], [40.5, 30.2], [45, 25]]
]
const WAVE2 = [
  [[16, 35], [21, 29.4], [26, 29.4], [31, 35]],
  [[31, 35], [36, 40.6], [41, 40.6], [46, 35]]
]
const WAVE3 = [
  [[16, 45], [21, 39.4], [26, 39.4], [31, 45]],
  [[31, 45], [36, 50.6], [41, 50.6], [46, 45]]
]
const N = 48
const POLYS = [WAVE_PATHS, WAVE2, WAVE3].flatMap(curves => curves.flatMap(c => bezier(...c, N)))

/** 点是否落在任一波线的描边范围内（线宽一半 + round cap 即圆头） */
function onWave (x64, y64) {
  const half = STROKE_W / 2
  for (let i = 1; i < POLYS.length; i++) {
    if (segDist(x64, y64, POLYS[i - 1], POLYS[i]) <= half) return true
  }
  return false
}
const onDot = (x64, y64) => Math.hypot(x64 - DOT.cx, y64 - DOT.cy) <= DOT.r

// ==================== 绘制（先在 SS 倍画布上着色，再盒式降采样）====================
const acc = new Float32Array(SIZE * SIZE * 4)
const n = SS * SS
for (let oy = 0; oy < W; oy++) {
  for (let ox = 0; ox < W; ox++) {
    // 像素 → 64 坐标系
    const x64 = (ox + 0.5) / U
    const y64 = (oy + 0.5) / U
    const white = onDot(x64, y64) || onWave(x64, y64)
    const [r, g, b] = white ? [255, 255, 255] : gradColor(x64, y64)
    const o = (Math.floor(oy / SS) * SIZE + Math.floor(ox / SS)) * 4
    acc[o] += r; acc[o + 1] += g; acc[o + 2] += b; acc[o + 3] += 255
  }
}
const rgba = Buffer.alloc(SIZE * SIZE * 4)
for (let i = 0; i < SIZE * SIZE; i++) {
  rgba[i * 4] = Math.min(255, (acc[i * 4] / n) | 0)
  rgba[i * 4 + 1] = Math.min(255, (acc[i * 4 + 1] / n) | 0)
  rgba[i * 4 + 2] = Math.min(255, (acc[i * 4 + 2] / n) | 0)
  rgba[i * 4 + 3] = 255 // 满幅不透明
}

// ==================== PNG 编码 ====================
const CRC_TABLE = (() => {
  const t = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c
  }
  return t
})()
const crc32 = buf => {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
const raw = Buffer.alloc((SIZE * 4 + 1) * SIZE)
for (let y = 0; y < SIZE; y++) {
  raw[y * (SIZE * 4 + 1)] = 0 // filter: None
  rgba.copy(raw, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4)
}
const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(SIZE, 0); ihdr.writeUInt32BE(SIZE, 4)
ihdr[8] = 8; ihdr[9] = 6 // 8bit RGBA
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0))
])
const out = join(fileURLToPath(new URL('..', import.meta.url)), 'public', 'apple-touch-icon.png')
writeFileSync(out, png)
console.log(`已生成 public/apple-touch-icon.png（${SIZE}×${SIZE}，${png.length} 字节）`)
