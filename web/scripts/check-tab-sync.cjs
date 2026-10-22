// 跨页签「会话列表喊话」的静态契约校验（纯读源码，跑在 npm run check 里，不需要浏览器）。
// 起因：侧栏那份列表是每个页签各持一份内存快照，A 页签新建/改名/删除会话时 B 页签完全不知道。
// 补上通道后，这条链散在三个文件里（utils/tabSync.js 通道 · api.js 喊话 · views/store.js 补丁），
// 任何一处漂了都不报错——最坏的一种是「喊了但补丁不认识那个字段」：本页签看着一切正常，
// 另一个页签永远慢半拍，用户只能自己刷新。这里把四处对齐关系钉死：
//   1) 通道互斥：BroadcastChannel 与 localStorage 退路只能注册一条，否则补丁跑两遍；
//   2) 补丁白名单：k='row' 喊话里带的字段，必须都在 applyRemoteRow 的白名单（或 projectId 那条归属分支）里；
//   3) 词汇表：发出去的 k、认识的 k、tabSync.js 文件头写的 k 三份必须一模一样；
//   4) 挂载：改动侧栏行的接口逐个要求走 said()，壳层两端各要求真的订阅一次。
// 阳性对照（改坏了必须报红，别信「永远绿」的守卫）：
//   WQ_TABSYNC_ROOT=/tmp/wq-tabsync-probe node scripts/check-tab-sync.cjs
'use strict'
const fs = require('fs')
const path = require('path')

const WEB = process.env.WQ_TABSYNC_ROOT || path.join(__dirname, '..')
const failures = []
const passes = []

const read = rel => {
  const full = path.join(WEB, rel)
  if (!fs.existsSync(full)) {
    failures.push(`找不到 ${rel}——跨页签通道被改名或删除，本校验本身失效`)
    return ''
  }
  return fs.readFileSync(full, 'utf8')
}

/** 从 `{ k: 'xxx', a, b }` 这类字面量里取对象体（括号配平，跳过字符串里的括号） */
function objectBodies (src, openerRe) {
  const out = []
  let m
  const re = new RegExp(openerRe, 'g')
  while ((m = re.exec(src))) {
    const start = src.indexOf('{', m.index)
    if (start < 0) break
    let depth = 0, quote = null
    let i = start
    for (; i < src.length; i++) {
      const c = src[i]
      if (quote) {
        if (c === '\\') { i++; continue }
        if (c === quote) quote = null
        continue
      }
      if (c === "'" || c === '"' || c === '`') { quote = c; continue }
      if (c === '{') depth++
      else if (c === '}') { depth--; if (!depth) break }
    }
    out.push({ text: src.slice(start, i + 1), at: m.index })
    re.lastIndex = i
  }
  return out
}

/** 对象字面量的键清单：按顶层逗号切段，段首标识符即键（`{ k: 'row', sid }` 里的 sid 是简写，也算键） */
function objectKeys (body) {
  const inner = body.slice(1, -1)
  const segs = []
  let depth = 0, quote = null, cur = ''
  for (const c of inner) {
    if (quote) { cur += c; if (c === quote) quote = null; continue }
    if (c === "'" || c === '"' || c === '`') { quote = c; cur += c; continue }
    if ('([{'.includes(c)) depth++
    else if (')]}'.includes(c)) depth--
    if (c === ',' && depth === 0) { segs.push(cur); cur = ''; continue }
    cur += c
  }
  if (cur.trim()) segs.push(cur)
  const keys = []
  for (const s of segs) {
    const seg = s.trim()
    if (!seg || seg.startsWith('...')) continue
    const m = seg.match(/^([A-Za-z0-9_]+)\s*:?/)
    if (m) keys.push(m[1])
  }
  return keys
}

const TAB = read('src/utils/tabSync.js')
const API = read('src/api.js')
const STORE = read('src/views/store.js')
if (!TAB || !API || !STORE) report()

/**
 * 只剥「行注释 + 说明行」：整行 // 掉、行尾 // 之后砍掉（引号内的 // 不动），
 * 以及 /** 与 * 开头的文档行。被注释掉的调用（// startSessionTabSync()）在源码扫描里
 * 会被当成「还挂着」，那是这条链最容易出现的假绿。
 * 刻意不做跨行块注释状态机——那会重演 check-h5 的 image/* 吞代码事故。
 */
function code (src) {
  return src.split('\n').filter(l => {
    const t = l.trim()
    return !(t.startsWith('/*') || t.startsWith('*') || t.startsWith('*/') || t.startsWith('//'))
  }).map(l => {
    let quote = null, out = ''
    for (let i = 0; i < l.length; i++) {
      const c = l[i]
      if (quote) { out += c; if (c === quote && l[i - 1] !== '\\') quote = null; continue }
      if (c === "'" || c === '"' || c === '`') { quote = c; out += c; continue }
      if (c === '/' && l[i + 1] === '/') break
      out += c
    }
    return out
  }).join('\n')
}

const TAB_CODE = code(TAB)
const API_CODE = code(API)
const STORE_CODE = code(STORE)

// ---------- 1) 通道互斥 ----------
{
  const fnBody = name => {
    const at = TAB_CODE.indexOf(`export function ${name} (`)
    if (at < 0) { failures.push(`tabSync.js 里找不到 ${name}()`); return '' }
    const start = TAB_CODE.indexOf('{', at)
    let depth = 0
    for (let i = start; i < TAB_CODE.length; i++) {
      if (TAB_CODE[i] === '{') depth++
      else if (TAB_CODE[i] === '}' && --depth === 0) return TAB_CODE.slice(start, i + 1)
    }
    return ''
  }
  const announceBody = fnBody('announce')
  const onBody = fnBody('onAnnounce')
  // BroadcastChannel 分支必须在 storage 注册之前 return 掉
  const blockOf = (body, tag) => {
    const at = body.indexOf('if (channel)')
    if (at < 0) { failures.push(`tabSync.js ${tag}：没有 if (channel) 分支，通道选择逻辑丢了`); return '' }
    const start = body.indexOf('{', at)
    let depth = 0
    for (let i = start; i < body.length; i++) {
      if (body[i] === '{') depth++
      else if (body[i] === '}' && --depth === 0) return body.slice(start, i + 1)
    }
    return ''
  }
  const chAnnounce = blockOf(announceBody, 'announce')
  const chOn = blockOf(onBody, 'onAnnounce')
  if (chAnnounce && !/channel\.postMessage/.test(chAnnounce)) failures.push("announce()：BroadcastChannel 分支没走 postMessage")
  if (chAnnounce && !/return/.test(chAnnounce)) failures.push("announce()：BroadcastChannel 发成功后没 return，会再往 localStorage 写一份（两条通道同时用＝补丁跑两遍）")
  if (chOn && !/return\s*\(\)\s*=>/.test(chOn)) failures.push("onAnnounce()：BroadcastChannel 分支没返回退订函数，storage 监听会被一起注册（两条通道同时用＝补丁跑两遍）")
  if (chOn && /addEventListener\('storage'/.test(chOn)) failures.push("onAnnounce()：BroadcastChannel 分支里又注册了 storage 监听（两条通道同时用）")
  if (onBody && !/window\.addEventListener\('storage'/.test(onBody)) failures.push("onAnnounce()：没有 storage 退路——老 Safari 没有 BroadcastChannel，跨页签同步会整个静默失效")
  if (passes) passes.push('通道互斥：BroadcastChannel 与 storage 退路只走一条')
}

// ---------- 2) 补丁白名单 ----------
const PATCH_FIELDS = (() => {
  const m = STORE_CODE.match(/function applyRemoteRow \([\s\S]*?for \(const k of \[([^\]]*)\]/)
  if (!m) { failures.push('store.js applyRemoteRow 里找不到补丁白名单数组（for (const k of [...])）'); return new Set() }
  return new Set(m[1].split(',').map(s => s.trim().replace(/^['"]|['"]$/g, '')).filter(Boolean))
})()
if (PATCH_FIELDS.size) {
  // 归属迁移是另一条分支，projectId 不在白名单数组里但确实被处理
  const allowed = new Set([...PATCH_FIELDS, 'sid', 'projectId', 'ids'])
  const stray = new Set()
  for (const src of [API_CODE, STORE_CODE]) {
    for (const { text } of objectBodies(src, "\\{\\s*k:\\s*'row'")) {
      for (const name of objectKeys(text)) {
        if (name !== 'k' && !allowed.has(name)) stray.add(name)
      }
    }
  }
  if (stray.size) {
    failures.push(`k='row' 喊话带了补丁白名单不认的字段：${[...stray].join(', ')}——本页签改得动，别的页签照单全收时被静默丢掉（要嘛加进 applyRemoteRow 白名单，要嘛别喊）`)
  } else {
    passes.push(`row 补丁字段全覆盖（白名单 ${PATCH_FIELDS.size} 项：${[...PATCH_FIELDS].join('/')}）`)
  }
}

// ---------- 3) 词汇表三处对齐 ----------
{
  const emitted = new Set()
  for (const src of [API_CODE, STORE_CODE]) {
    for (const m of src.matchAll(/\bk:\s*'([a-z_]+)'/g)) emitted.add(m[1])
  }
  const handled = new Set()
  for (const m of STORE_CODE.matchAll(/m\.k === '([a-z_]+)'/g)) handled.add(m[1])
  const docLine = (TAB.match(/消息形如 \{ k: ([^}]*)\}/) || [])[1] || ''
  const documented = new Set((docLine.match(/'([a-z_]+)'/g) || []).map(s => s.replace(/'/g, '')))
  const unhandled = [...emitted].filter(k => !handled.has(k))
  const dead = [...handled].filter(k => !emitted.has(k))
  const notDoc = [...emitted].filter(k => !documented.has(k))
  const docGhost = [...documented].filter(k => !emitted.has(k))
  if (!emitted.size) failures.push('源码里一个 k= 喊话都扫不到——白名单校验失去依据')
  if (unhandled.length) failures.push(`喊了但 store.js 没有对应分支的 k：${unhandled.join(', ')}（现在只会落到「列表大概变了」整表兜底，定点补丁形同虚设）`)
  if (dead.length) failures.push(`store.js 认识但没人喊的 k：${dead.join(', ')}——通常是某侧改了名`)
  if (notDoc.length) failures.push(`tabSync.js 文件头词汇表缺：${notDoc.join(', ')}`)
  if (docGhost.length) failures.push(`tabSync.js 文件头写了但代码里不存在的 k：${docGhost.join(', ')}——按注释找实现会找不到`)
  if (!unhandled.length && !dead.length && !notDoc.length && !docGhost.length) {
    passes.push(`k 词汇表三处一致（${[...emitted].join(' / ')}）`)
  }
}

// ---------- 4) 改动侧栏行的接口必须喊 ----------
{
  // 每一项都是「侧栏那一行会变」的写接口：漏一个，那个入口就是「另一个页签要刷新页面才看得见」。
  // 刻意不在表内的：updateSessionModelApi（模型覆盖不进侧栏）、产物与知识库的写接口（不在会话列表上）。
  const required = ['enableSessionShare', 'disableSessionShare', 'stopShareBySession', 'pinSession', 'favoriteSession',
    'renameSessionApi', 'moveSessionProject', 'deleteSessionApi', 'batchDeleteSessionsApi',
    'createProject', 'deleteProject', 'updateProject', 'setProjectStatus']
  const silent = []
  for (const name of required) {
    const at = API_CODE.indexOf(`export const ${name} =`)
    if (at < 0) { silent.push(`${name}(接口已不存在)`); continue }
    const next = API_CODE.indexOf('\nexport const ', at + 1)
    const body = API_CODE.slice(at, next < 0 ? API_CODE.length : next)
    if (!/said\(/.test(body)) silent.push(name)
  }
  if (silent.length) failures.push(`侧栏写接口没挂广播：${silent.join(', ')}`)
  else passes.push(`侧栏写接口 ${required.length} 个全部挂上广播`)
  // 本地喊话是 store 自己发的两个时刻（接口层看不到 sid）：
  //   a. 已有行被就地更新（首条消息落库时补标题/计数）；b. 新会话抬进列表——只有它带 projectId，
  // 少一个就是那个时刻在别的页签永远不出现。逐个点名，别用「有没有 announce 过」蒙过去。
  const rowEmits = (STORE_CODE.match(/announce\(\{ k: 'row'/g) || []).length
  if (rowEmits < 2) failures.push(`store.js 本地 row 喊话只剩 ${rowEmits} 处（应为 2：就地更新 + 新会话抬进列表）——新建会话在别人页签里不再出现`)
  else if (!/announce\(\{ k: 'row'[^)]*\bprojectId/.test(STORE_CODE)) failures.push("store.js 的 row 喊话里没有带 projectId 的那一条——新会话抬进哪个工作区喊不出去")
  else passes.push(`store 本地 row 喊话 ${rowEmits} 处齐全（就地更新 + 带归属的新会话）`)
}

// ---------- 5) 订阅真的挂了（两端） ----------
{
  if (!/export function startSessionTabSync/.test(STORE_CODE) || !/export function stopSessionTabSync/.test(STORE_CODE)) {
    failures.push('store.js 不再导出 startSessionTabSync / stopSessionTabSync')
  }
  for (const [rel, label] of [['src/views/AppLayout.vue', 'PC 壳层'], ['src/h5/MobileChatPage.vue', 'H5 壳层']]) {
    const src = code(read(rel))
    if (!src) continue
    if (!/startSessionTabSync\(\)/.test(src)) failures.push(`${label} 没调 startSessionTabSync()——整套跨页签同步在该端是死的`)
    if (!/stopSessionTabSync\(\)/.test(src)) failures.push(`${label} 没调 stopSessionTabSync()——离开页面后监听还挂着`)
  }
  if (!/from '\.\/utils\/tabSync'/.test(API_CODE) && !/from '\.\.\/utils\/tabSync'/.test(API_CODE)) {
    failures.push("api.js 没 import utils/tabSync——广播整条断")
  } else passes.push('两端壳层订阅 + api.js 广播引用都在')
}

function report () {
  for (const p of passes) console.log('PASS  ' + p)
  for (const f of failures) console.error('FAIL  ' + f)
  if (failures.length) {
    console.error(`\n跨页签喊话校验未通过（${failures.length} 项）`)
    process.exit(1)
  }
  console.log('PASS  跨页签喊话契约对齐（通道 / 补丁字段 / k 词汇表 / 写接口 / 挂载）')
}
report()
