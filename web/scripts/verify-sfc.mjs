// 前端静态校验：不起 Spring、不启 dev server。
// 1) SFC 编译  2) 模块解析（抓 import 路径/导出名错误）  3) CSS 语法
// 4) 项目硬约束（裸 hex / 断点值 / 禁用词）  5) manifest 与图标文件存在性
//
// 用法：node scripts/verify-sfc.mjs [子命令]
//   node scripts/verify-sfc.mjs            全部
//   node scripts/verify-sfc.mjs sfc        仅 SFC 编译
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join, extname, relative, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execSync } from 'node:child_process'
import { parse, compileScript, compileTemplate, compileStyle } from '@vue/compiler-sfc'
import * as esbuild from 'esbuild'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const SRC = join(ROOT, 'src')
const errors = []
const warn = m => errors.push('[warn] ' + m)
const err = m => errors.push('[error] ' + m)

function walk (dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else out.push(p)
  }
  return out
}
const all = walk(SRC)
const vues = all.filter(f => extname(f) === '.vue')
const jss = all.filter(f => ['.js', '.mjs'].includes(extname(f)))
const csss = all.filter(f => extname(f) === '.css')
const rel = f => relative(ROOT, f)

// ==================== 1) SFC 编译 ====================
function checkSfc () {
  for (const f of vues) {
    const src = readFileSync(f, 'utf8')
    const { descriptor, errors: perr } = parse(src, { filename: f })
    if (perr.length) { perr.forEach(e => err(`${rel(f)} parse: ${e.message}`)); continue }
    const id = Buffer.from(f).toString('hex').slice(0, 8)
    try {
      if (descriptor.script || descriptor.scriptSetup) compileScript(descriptor, { id })
      if (descriptor.template) {
        const r = compileTemplate({ source: descriptor.template.content, filename: f, id, scoped: !!descriptor.styles.some(s => s.scoped) })
        if (r.errors.length) r.errors.forEach(e => err(`${rel(f)} template: ${e}`))
      }
      for (const s of descriptor.styles) {
        const r = compileStyle({ source: s.content, filename: f, id, scoped: s.scoped })
        if (r.errors.length) r.errors.forEach(e => err(`${rel(f)} style: ${e}`))
      }
    } catch (e) {
      err(`${rel(f)} compile: ${e.message}`)
    }
  }
  console.log(`SFC 编译：${vues.length} 个 .vue`)
}

// ==================== 2) 模块解析 ====================
const EXTERNAL = ['vue', 'vue-router', 'ant-design-vue', 'ant-design-vue/es/*', 'ant-design-vue/lib/*',
  '@ant-design/icons-vue', 'dayjs', 'markdown-it', 'dompurify', 'highlight.js', 'mermaid', 'echarts',
  '@vue-flow/core', '@vue-flow/background', '@vue-flow/controls', '@vue-flow/minimap']

// .vue 文件交给 esbuild 会走它的默认 loader 失败；这里用插件把每个 .vue 当空模块，
// 目的只是校验**JS 层**的导入路径与导出名（.vue 之间的引用由 SFC 编译那段保证）。
// 注意不能像先前那样自己往 script 前插 vue 的具名导入——组件自身已经 import 了，
// 会报「already been declared」的假错。
const vueStub = {
  name: 'vue-stub',
  setup (build) {
    build.onResolve({ filter: /\.(vue)$/ }, a => ({ path: a.path, namespace: 'vue-stub' }))
    build.onLoad({ filter: /.*/, namespace: 'vue-stub' }, () => ({ contents: 'export default {}', loader: 'js' }))
  }
}

async function buildOne (stdin) {
  await esbuild.build({
    stdin, bundle: true, write: false, format: 'esm', logLevel: 'silent',
    external: EXTERNAL, plugins: [vueStub], outdir: '/out'
  })
}

async function checkModules () {
  const jss = all.filter(f => ['.js', '.mjs'].includes(extname(f)))
  const vues = all.filter(f => extname(f) === '.vue')
  const inline = []
  for (const f of vues) {
    const { descriptor } = parse(readFileSync(f, 'utf8'), { filename: f })
    if (!descriptor.script && !descriptor.scriptSetup) continue
    const id = Buffer.from(f).toString('hex').slice(0, 8)
    try {
      // compileScript 产出的正是浏览器要跑的代码（含它自动加的 import），是最真实的被测物
      const compiled = compileScript(descriptor, { id })
      inline.push({ file: f, content: compiled.content })
    } catch (e) { /* 1) 已报 */ }
  }
  const run = async (label, stdin) => {
    try { await buildOne(stdin) } catch (e) {
      String(e.message || e).split('\n').slice(0, 5).forEach(l => err(`${label} module: ${l.trim()}`))
    }
  }
  for (const it of inline) {
    await run(rel(it.file), {
      contents: it.content, resolveDir: dirname(it.file), sourcefile: rel(it.file), loader: 'js'
    })
  }
  for (const f of jss) {
    try {
      await esbuild.build({ entryPoints: [f], bundle: true, write: false, format: 'esm', logLevel: 'silent', external: EXTERNAL, plugins: [vueStub], outdir: '/out' })
    } catch (e) {
      String(e.message || e).split('\n').slice(0, 5).forEach(l => err(`${rel(f)} module: ${l.trim()}`))
    }
  }
  console.log(`模块解析：${jss.length} 个 .js + ${inline.length} 个 .vue 内联 script`)
}

// ==================== 3) CSS 语法 ====================
function checkCss () {
  const targets = [...csss]
  for (const f of vues) {
    const { descriptor } = parse(readFileSync(f, 'utf8'), { filename: f })
    for (const s of descriptor.styles) targets.push({ __inline: s.content, __file: f })
  }
  for (const t of targets) {
    const isInline = !!t.__inline
    const src = isInline ? t.__inline : readFileSync(t, 'utf8')
    try {
      esbuild.transform(src, { loader: 'css' })
    } catch (e) {
      err(`${isInline ? rel(t.__file) + ' <style>' : rel(t)} css: ${String(e.message).split('\n')[0]}`)
    }
  }
  console.log(`CSS 语法：${csss.length} 个 .css + ${targets.length - csss.length} 个内联 style`)
}

// ==================== 4) 项目硬约束 ====================
// 品牌固定色白名单：这些是"刻意不随主题变"的语义色，与 --app-* 区分开
const HEX_OK = new Set(['#2a5fe0', '#2e6be6', '#5e93f5', '#1677ff', '#fff', '#ffffff', '#000', '#000000'])

// 禁用词：文案禁用「本地」形容 Ollama 类自托管网关（localhost 指问渠服务器/后端容器视角，
// 不是用户自己的电脑），统一用「自托管」。
// 只在「同一行里 本地 与 网关/Ollama/自托管/模型 同时出现」时才报——
// 「本地镜像」「纯本地档」「本地存储」这类描述前端状态的说法是合法的，全局禁词会误报一片。
const BANNED_LOCAL_GATEWAY = /本地[^\n]{0,20}(网关|Ollama|自托管|模型)|(网关|Ollama|自托管)[^\n]{0,20}本地/

// 存量断点：项目历史上各页面各用各的（640/860/900/1000/1024/1100/1199/1200/1440）。
// 不去动它们，但新代码只允许 768
const LEGACY_BP = new Set([640, 860, 900, 1000, 1024, 1100, 1199, 1200, 1440])

function checkConventions () {
  // 这两项是「代码风格」检查，存量代码里早有历史遗留（如 md.css 的一批 highlight 配色）。
  // 全量扫会把 150 条存量噪声刷出来，反而让人忽略新问题 —— 故只检查**相对 HEAD 有改动**的文件。
  const changed = changedFiles()
  if (!changed.size) { console.log('项目约束：无改动文件，跳过（风格检查只看增量）'); return }
  const scope = all.filter(f => changed.has(f))
  for (const f of scope) {
    const src = readFileSync(f, 'utf8')
    src.split('\n').forEach((line, i) => {
      if (BANNED_LOCAL_GATEWAY.test(line)) {
        warn(`${rel(f)}:${i + 1} 用「本地」形容自托管网关，应统一为「自托管」：${line.trim().slice(0, 80)}`)
      }
    })
    // 断点值：新增代码统一 768（存量 640/860/900/1000/1024/1100/1199/1200/1440 不动）
    const mq = [...src.matchAll(/@media[^{]*?max-width:\s*(\d+)px/g)].map(m => +m[1])
    for (const v of new Set(mq)) {
      if (!LEGACY_BP.has(v) && v !== 768) warn(`${rel(f)} 出现非常规断点 ${v}px（新增请用 768）`)
    }
  }
  // 裸 hex：app.css 是 token 定义处本身，跳过；其余改动文件里**新增的**色值应走 var(--app-*)
  for (const f of scope) {
    if (f.endsWith('app.css')) continue
    const base = baselineOf(f)
    for (const m of readFileSync(f, 'utf8').matchAll(/#[0-9a-fA-F]{3,8}\b/g)) {
      if (HEX_OK.has(m[0].toLowerCase())) continue
      // 与 HEAD 版本比：存量色值（如 App.vue 的 antd token 字面量、md.css 的 highlight 配色）
      // 留在原地不动，只报这次新引入的
      if (base && base.includes(m[0])) continue
      warn(`${rel(f)} 新增裸色值 ${m[0]}（应走 var(--app-*)）`)
    }
  }
  console.log(`项目约束：${scope.length} 个改动文件的断点值 / 裸色值 / 禁用词`)
}

// 文件在 HEAD 里的内容；不存在（新增文件）或读失败则返回 null。
// 用 -q 抑制 git 对「路径不在 HEAD」的 stderr 噪声
function baselineOf (f) {
  try {
    return execSync(`git show -q HEAD:${relative(ROOT + '/..', f)}`, { encoding: 'utf8', maxBuffer: 8 << 20, stdio: ['ignore', 'pipe', 'ignore'] })
  } catch (e) { return null }
}

// 相对 HEAD 有改动的文件（web 目录内）
function changedFiles () {
  const out = new Set()
  let raw = ''
  try { raw = execSync('git diff --name-only HEAD -- .', { cwd: ROOT, encoding: 'utf8' }) } catch (e) { return out }
  for (const line of raw.split('\n')) {
    const p = line.trim()
    if (!p) continue
    // git 报的是相对 web/ 的路径（.git 在上一级，这里 cwd=ROOT=web）
    const abs = join(ROOT, p.replace(/^web\//, ''))
    if (existsSync(abs)) out.add(abs)
  }
  return out
}

// ==================== 5) PWA 资源 ====================
function checkPwa () {
  const mf = join(ROOT, 'public', 'manifest.webmanifest')
  if (!existsSync(mf)) { warn('缺少 public/manifest.webmanifest'); return }
  let m
  try { m = JSON.parse(readFileSync(mf, 'utf8')) } catch (e) { err(`manifest JSON 解析失败: ${e.message}`); return }
  for (const ic of m.icons || []) {
    const p = join(ROOT, 'public', ic.src.replace(/^\//, ''))
    if (!existsSync(p)) err(`manifest 引用的图标不存在: ${ic.src}`)
  }
  // index.html 引用的本地产物
  const html = readFileSync(join(ROOT, 'index.html'), 'utf8')
  for (const m2 of html.matchAll(/(?:href|content)="(\/[\w.-]+\.(?:png|svg|webmanifest))"/g)) {
    if (!existsSync(join(ROOT, 'public', m2[1].replace(/^\//, '')))) err(`index.html 引用不存在: ${m2[1]}`)
  }
  // viewport-fit=cover 与 --sat 补偿必须成对
  if (html.includes('viewport-fit=cover') !== src_hasSat()) {
    err('viewport-fit=cover 与 app.css 的 --sat 定义不成对')
  }
  console.log('PWA：manifest / 图标 / index.html 引用')
}
const src_hasSat = () => readFileSync(join(SRC, 'views', 'app.css'), 'utf8').includes('--sat:')

// ==================== 入口 ====================
const only = process.argv[2]
const run = { sfc: checkSfc, css: checkCss, conv: checkConventions, pwa: checkPwa }
if (only) {
  if (only === 'module') await checkModules()
  else if (run[only]) run[only]()
  else { console.error('未知子命令，可选：sfc module css conv pwa'); process.exit(2) }
} else {
  checkSfc()
  await checkModules()
  checkCss()
  checkConventions()
  checkPwa()
}
console.log('')
if (errors.length) {
  console.log(`发现 ${errors.length} 条问题：`)
  errors.forEach(e => console.log('  ' + e))
  process.exit(1)
}
console.log('校验通过')
