#!/usr/bin/env node
/**
 * 对外集成文档站构建（B2 · 单一真源）
 *
 * 真源三处，构建期合并，页面里不许出现第二份：
 *   1. contract/openapi.v1.json   —— 端点清单（由 check-openapi-contract.mjs --update 生成）
 *   2. docs-site/guides/*.md      —— 手写指南（唯一可编辑的正文来源）
 *   3. samples/* + contract/CHANGELOG.md —— 可运行样例与变更记录
 *
 * 产物落在 web/public/docs/：同一份 HTML 既当独立文档站部署，也被应用内「对接文档」页 iframe 嵌入。
 * 因此这一份产物提交入库，CI 用 `--check` 守住「改了真源却没重新构建」的漂移。
 *
 * 用法：
 *   node scripts/build-docs.mjs            # 构建（写盘）
 *   node scripts/build-docs.mjs --check    # 只校验产物是否与真源一致（CI 用）
 *   node scripts/build-docs.mjs --out=dir  # 换输出目录（独立部署用）
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const GUIDE_DIR = path.join(ROOT, 'docs-site', 'guides')
const ASSET_DIR = path.join(ROOT, 'docs-site', 'assets')
const CONTRACT = path.join(ROOT, 'contract', 'openapi.v1.json')
const CHANGELOG = path.join(ROOT, 'contract', 'CHANGELOG.md')
const SAMPLES_DIR = path.join(ROOT, 'samples')

const args = process.argv.slice(2)
const CHECK = args.includes('--check')
const outArg = args.find(a => a.startsWith('--out='))
const OUT_DIR = outArg ? path.resolve(ROOT, outArg.slice(6)) : path.join(ROOT, 'web', 'public', 'docs')

const GROUP_ORDER = ['开始', '参考', '指南', '资源']

/* ==================== 迷你 Markdown 渲染 ====================
   刻意不引第三方依赖：文档站要在任何环境（含 CI 的裸 Node）都能构建，
   而需要的语法就这几种——标题 / 段落 / 列表 / 表格 / 代码块 / 引用 / 分隔线 / 行内格式。
*/
const escapeHtml = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

function inline(raw) {
  let out = escapeHtml(raw)
  const codes = []
  out = out.replace(/`([^`]+)`/g, (_, c) => {
    codes.push(c)
    return `\u0000${codes.length - 1}\u0000`
  })
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, text, href) => {
    const external = /^https?:/i.test(href)
    return `<a href="${href}"${external ? ' target="_blank" rel="noreferrer"' : ''}>${text}</a>`
  })
  out = out.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${codes[Number(i)]}</code>`)
  return out
}

function slugify(text, used) {
  let base = String(text)
    .replace(/[`*]/g, '')
    .toLowerCase()
    .replace(/[^\w\u4e00-\u9fa5-]+/g, '-')
    .replace(/^-+|-+$/g, '')
  if (!base) base = 'sec'
  let id = base
  let n = 2
  while (used.has(id)) id = `${base}-${n++}`
  used.add(id)
  return id
}

function codeBlock(lang, body) {
  const label = lang || 'text'
  return `<div class="code">
  <div class="code-top"><span class="code-lang">${escapeHtml(label)}</span><span class="spacer"></span>` +
    `<button class="copy" type="button">复制</button></div>
  <pre><code>${escapeHtml(body)}</code></pre>
</div>`
}

function tableBlock(rows) {
  const head = rows[0]
  const body = rows.slice(2).filter(r => r.length)
  const cells = (line, tag) => {
    const parts = line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|')
    return parts.map(c => `<${tag}>${inline(c.trim())}</${tag}>`).join('')
  }
  const out = ['<div class="tbl-wrap"><table>', '<thead><tr>' + cells(head, 'th') + '</tr></thead>', '<tbody>']
  body.forEach(r => out.push('<tr>' + cells(r, 'td') + '</tr>'))
  out.push('</tbody></table></div>')
  return out.join('\n')
}

/** 把一段 Markdown 渲染成 HTML，并把标题登记进 ctx.headings（供目录与搜索用） */
function renderMarkdown(md, ctx) {
  const lines = md.replace(/\r\n/g, '\n').split('\n')
  const html = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    if (!line.trim()) { i++; continue }

    // 代码块
    const fence = line.match(/^```(\S*)\s*$/)
    if (fence) {
      const lang = fence[1]
      const buf = []
      i++
      while (i < lines.length && !/^```\s*$/.test(lines[i])) buf.push(lines[i++])
      i++ // 跳过结束围栏
      html.push(codeBlock(lang, buf.join('\n')))
      continue
    }

    // 标题（支持 ##/###/#### 与显式锚点 `{#id}`）
    const heading = line.match(/^(#{2,4})\s+(.*)$/)
    if (heading) {
      const level = heading[1].length
      let text = heading[2].trim()
      let explicit = ''
      const anchor = text.match(/\s*\{#([\w-]+)\}\s*$/)
      if (anchor) { explicit = anchor[1]; text = text.replace(/\s*\{#[\w-]+\}\s*$/, '') }
      const id = explicit || slugify(text, ctx.used)
      ctx.used.add(id)
      ctx.headings.push({ level, text, id, page: ctx.pageId, pageTitle: ctx.pageTitle })
      const tag = `h${level}`
      html.push(`<${tag} id="${id}"><a class="anchor" href="#${id}" aria-hidden="true">#</a>${inline(text)}</${tag}>`)
      i++
      continue
    }

    // 表格（第二行必须是分隔行）
    if (/^\|/.test(line) && /^\|[\s:|-]+\|?\s*$/.test((lines[i + 1] || '').trim())) {
      const rows = []
      while (i < lines.length && /^\|/.test(lines[i])) rows.push(lines[i++])
      html.push(tableBlock(rows))
      continue
    }

    // 引用（> 提示条）
    if (/^>\s?/.test(line)) {
      const buf = []
      while (i < lines.length && /^>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, ''))
      const text = buf.join('\n')
      // 只有显式标注才染成警示色：「注意 / 警告 / 危险」写在引用第一句开头。
      // 不用宽泛关键词猜——「不能改绑别人」这种正常句子不该被染成警告。
      const warn = /^\*\*(注意|警告|危险)/.test(text.trim()) ? ' class="warn"' : ''
      html.push(`<blockquote${warn}>${renderMarkdown(text, { ...ctx, headings: [], used: new Set() })}</blockquote>`)
      continue
    }

    // 列表（两级：顶格与缩进 2+ 空格）
    if (/^\s*([-*]|\d+\.)\s+/.test(line)) {
      const res = renderList(lines, i)
      html.push(res.html)
      i = res.next
      continue
    }

    if (/^---+$/.test(line.trim())) { html.push('<hr>'); i++; continue }

    // 段落
    const buf = []
    while (i < lines.length && lines[i].trim() &&
      !/^```/.test(lines[i]) && !/^#{2,4}\s/.test(lines[i]) && !/^\|/.test(lines[i]) &&
      !/^>\s?/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i]) && !/^---+$/.test(lines[i].trim())) {
      buf.push(lines[i++])
    }
    html.push(`<p>${inline(buf.join(' '))}</p>`)
  }
  return html.join('\n')
}

function renderList(lines, start) {
  const isBullet = s => /^\s*([-*]|\d+\.)\s+/.test(s)
  const indentOf = s => s.match(/^(\s*)/)[1].length
  const textOf = s => s.replace(/^\s*([-*]|\d+\.)\s+/, '').trim()

  const ordered = /^\s*\d+\.\s+/.test(lines[start])
  const tag = ordered ? 'ol' : 'ul'
  const items = []
  let i = start

  while (i < lines.length && isBullet(lines[i]) && indentOf(lines[i]) < 2) {
    items.push({ text: textOf(lines[i]), children: [] })
    i++
    // 该项后面：缩进子项并进 children；纯缩进续行并进正文；空行跳过；顶格项则结束本项
    for (;;) {
      if (i >= lines.length) break
      const raw = lines[i]
      if (!raw.trim()) { i++; continue }
      if (isBullet(raw)) {
        if (indentOf(raw) >= 2) { items[items.length - 1].children.push(textOf(raw)); i++; continue }
        break
      }
      if (indentOf(raw) >= 2) { items[items.length - 1].text += ' ' + raw.trim(); i++; continue }
      break
    }
  }

  const html = [`<${tag}>`]
  for (const item of items) {
    const nested = item.children.length
      ? `<ul>${item.children.map(c => `<li>${inline(c)}</li>`).join('')}</ul>`
      : ''
    html.push(`<li>${inline(item.text)}${nested}</li>`)
  }
  html.push(`</${tag}>`)
  return { html: html.join('\n'), next: i }
}

/* ==================== 读入真源 ==================== */
function parseFrontmatter(raw) {
  const m = raw.match(/^---\n([\s\S]*?)\n---\n?/)
  if (!m) return { meta: {}, body: raw }
  const meta = {}
  for (const line of m[1].split('\n')) {
    const kv = line.match(/^([\w-]+):\s*(.*)$/)
    if (kv) meta[kv[1]] = kv[2].trim()
  }
  return { meta, body: raw.slice(m[0].length) }
}

function loadGuides() {
  if (!fs.existsSync(GUIDE_DIR)) {
    throw new Error(`缺少指南目录：${path.relative(ROOT, GUIDE_DIR)}`)
  }
  return fs.readdirSync(GUIDE_DIR)
    .filter(f => f.endsWith('.md'))
    .sort()
    .map(file => {
      const raw = fs.readFileSync(path.join(GUIDE_DIR, file), 'utf8')
      const { meta, body } = parseFrontmatter(raw)
      const id = file.replace(/^\d+-/, '').replace(/\.md$/, '')
      return {
        file, id, body,
        title: meta.title || id,
        group: meta.group || '指南',
        order: Number(meta.order || 999),
        lead: meta.lead || ''
      }
    })
    .sort((a, b) => a.order - b.order)
}

function loadContract() {
  const spec = JSON.parse(fs.readFileSync(CONTRACT, 'utf8'))
  const endpoints = (spec.endpoints || []).slice().sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method))
  return { version: spec.version || 'v1', endpoints }
}

const API_GROUPS = [
  { key: 'me', label: '自检与契约' },
  { key: 'chat', label: '问答（OpenAI 兼容）' },
  { key: 'responses', label: '问答（Responses 形状）' },
  { key: 'agents', label: '运行原语 · 建 run' },
  { key: 'runs', label: '运行原语 · 查询与控制' },
  { key: 'retrieval', label: '知识检索' },
  { key: 'kbs', label: '知识库管理' },
  { key: 'embeddings', label: '向量化' },
  { key: 'models', label: '模型清单' },
  { key: 'workflows', label: '工作流' }
]

function apiGroupOf(ep) {
  const rest = ep.path.replace('/api/ai/v1/', '')
  const head = rest.split('/')[0]
  return head === 'openapi.json' ? 'me' : head
}

/** 每个账点给一条能直接粘进终端的 curl（占位符 __BASE__ 由 docs.js 按当前基址替换） */
function curlOf(ep) {
  const url = '__BASE__' + ep.path
  // 单行：Windows cmd/PowerShell 不认 `\` 续行；PowerShell 下把单引号换双引号即可。
  const body = ep.method === 'POST' ? ` -H "Content-Type: application/json" -d '{}'` : ''
  return `curl -X ${ep.method} ${url} -H "X-Api-Key: sk-你的Key"${body}`
}

function renderApiReference(contract) {
  const html = []
  html.push(`<p>共 <strong>${contract.endpoints.length}</strong> 个已登记端点，契约版本 <code>${escapeHtml(contract.version)}</code>。` +
    `这张表由 <code>contract/openapi.v1.json</code> 在构建期生成，与后端开放契约注册表同源（<code>@OpenApi</code> + Spring 路由），` +
    `不手写、不做第二份。运行时机器可读版本：<code>GET __BASE__/api/ai/v1/openapi.json</code>。</p>`)
  html.push(`<p>示例为单行 curl，bash/zsh/cmd 都可直接粘贴；PowerShell 请把单引号换成双引号，或使用 <code>Invoke-RestMethod</code>。</p>`)

  const byGroup = new Map()
  for (const ep of contract.endpoints) {
    const g = apiGroupOf(ep)
    if (!byGroup.has(g)) byGroup.set(g, [])
    byGroup.get(g).push(ep)
  }
  const ordered = [
    ...API_GROUPS.filter(g => byGroup.has(g.key)),
    ...[...byGroup.keys()].filter(k => !API_GROUPS.some(g => g.key === k)).sort()
      .map(k => ({ key: k, label: k }))
  ]

  for (const group of ordered) {
    html.push(`<h3 id="api-${group.key}"><a class="anchor" href="#api-${group.key}" aria-hidden="true">#</a>${escapeHtml(group.label)}</h3>`)
    html.push('<div class="tbl-wrap"><table>')
    html.push('<thead><tr><th style="width:64px">方法</th><th style="width:330px">路径</th><th style="width:112px">scope</th><th>说明</th><th style="width:76px">curl</th></tr></thead>')
    html.push('<tbody>')
    for (const ep of byGroup.get(group.key)) {
      const method = ep.method.toLowerCase()
      const curl = curlOf(ep).replace(/&/g, '&amp;').replace(/"/g, '&quot;')
      html.push('<tr>' +
        `<td><span class="method ${method}">${ep.method}</span></td>` +
        `<td><code>${escapeHtml(ep.path)}</code></td>` +
        `<td>${ep.scope ? `<span class="scope">${escapeHtml(ep.scope)}</span>` : '<span class="scope">任意有效 Key</span>'}</td>` +
        `<td>${escapeHtml(ep.summary || '')}${ep.keyAccessible ? '' : '<span class="pill-token">令牌通道</span>'}</td>` +
        `<td><button class="copy" type="button" data-copy-text="${curl}">复制</button></td>` +
        '</tr>')
    }
    html.push('</tbody></table></div>')
  }

  html.push(`<blockquote><p>表的排序与分组是构建脚本按路径前缀归的，不是人工排版。<strong>新增端点</strong>要先标注 <code>@OpenApi</code>、` +
    `再跑 <code>node scripts/check-openapi-contract.mjs --update</code> 更新快照与变更日志，然后重新构建本站——三步顺序反了 CI 会拦。</p></blockquote>`)
  html.push('<p class="foot-note">成功体的形状不统一：<code>v1/runs/*</code> 与 <code>v1/me</code> 是裸对象，<code>v1/retrieval</code>、' +
    '<code>v1/workflows/*</code> 是 <code>{success,data,msg}</code> 平台包络。双端 SDK 已各自抹平，手写 curl 时别按同一种解。</p>')
  return html.join('\n')
}

function renderSamples() {
  const html = []
  html.push('<p>样例是<strong>能启动的宿主</strong>，不是片段：每个目录都有 README、可执行入口与失败路径说明。' +
    '代码片段会随契约漂移，能跑的宿主不会——所以本站的示例一律指向这里，不在页面里重抄一遍。</p>')

  if (fs.existsSync(SAMPLES_DIR)) {
    const dirs = fs.readdirSync(SAMPLES_DIR, { withFileTypes: true })
      .filter(d => d.isDirectory()).map(d => d.name).sort()
    html.push('<div class="tbl-wrap"><table><thead><tr><th style="width:190px">目录</th><th>说明</th></tr></thead><tbody>')
    for (const dir of dirs) {
      const readme = path.join(SAMPLES_DIR, dir, 'README.md')
      let desc = ''
      if (fs.existsSync(readme)) {
        const text = fs.readFileSync(readme, 'utf8')
        const para = text.split('\n').map(l => l.trim())
          .find(l => l && !l.startsWith('#') && !l.startsWith('|') && !l.startsWith('```'))
        desc = para ? para.slice(0, 120) : ''
      }
      html.push(`<tr><td><code>samples/${dir}/</code></td><td>${escapeHtml(desc)}</td></tr>`)
    }
    html.push('</tbody></table></div>')
  }

  html.push('<blockquote><p>样例里的 SDK 走仓库内引用（<code>file:</code> / <code>-e</code>）：SDK 还没发包。' +
    '装样例依赖前必须先构建 <code>sdk/js</code>，否则 <code>dist/</code> 不存在。</p></blockquote>')

  if (fs.existsSync(CHANGELOG)) {
    const raw = fs.readFileSync(CHANGELOG, 'utf8')
    html.push('<h3 id="changelog"><a class="anchor" href="#changelog" aria-hidden="true">#</a>契约变更日志</h3>')
    html.push('<p>下列内容原文来自 <code>contract/CHANGELOG.md</code>：<strong>条目由脚本在 <code>--update</code> 时生成草稿，' +
      '「为什么改」由人工补</strong>。承诺是只增不删，所以这里不会出现「端点被移除」的条目——真出现了说明违反了承诺。</p>')
    html.push(renderMarkdown(raw.replace(/^#\s+.*$/m, ''), { headings: [], used: new Set(), pageId: 'changelog', pageTitle: '变更日志' }))
  }
  return html.join('\n')
}

/* ==================== 标题 / 分节（供导航与搜索） ==================== */
const stripTags = s => s
  // 代码块整段丢弃：搜索命中的应该是说明文字，不是某段 curl 里的字母
  .replace(/<div class="code">[\s\S]*?<\/pre>\s*<\/div>/g, ' ')
  .replace(/<a class="anchor"[\s\S]*?<\/a>/g, ' ')
  .replace(/<[^>]+>/g, ' ')
  .replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
  .replace(/\s+/g, ' ')
  .trim()

/** 从渲染后的 HTML 里取 h2/h3（先生成后扫，生成页与手写页走同一条路） */
function collectHeadings(html) {
  const out = []
  const re = /<h([23]) id="([^"]+)">([\s\S]*?)<\/h[23]>/g
  let m
  while ((m = re.exec(html))) {
    const text = stripTags(m[3].replace(/<a class="anchor"[\s\S]*?<\/a>/g, ''))
    out.push({ level: Number(m[1]), id: m[2], text })
  }
  return out
}

/** 按 h2/h3 切段，得到「小节 → 纯文本」用于搜索索引 */
function splitSections(html) {
  const parts = html.split(/<h[23] id="([^"]+)">/)
  const out = []
  for (let i = 1; i < parts.length; i += 2) {
    out.push({ id: parts[i], text: stripTags(parts[i + 1] || '').slice(0, 200) })
  }
  return out
}

/* ==================== 组装页面 ==================== */
function build() {
  const guides = loadGuides()
  const contract = loadContract()

  const pages = guides.map(g => ({ ...g }))

  // 生成页：API 参考插在「参考」组，样例与变更日志放「资源」组
  const apiPage = {
    id: 'api-reference', group: '参考', order: 40, title: 'API 参考（自动生成）',
    lead: '端点清单由契约快照生成：路径、方法、scope、可达性都不是手写的。'
  }
  const samplePage = {
    id: 'samples-changelog', group: '资源', order: 90, title: '示例与变更日志',
    lead: '能跑的宿主，加上契约改了什么、为什么改。'
  }
  const all = [...pages, apiPage, samplePage]

  const nav = []
  const searchIndex = []

  // 先渲染再拼 nav：导航与搜索索引都从渲染结果里读标题，生成页与手写页走同一条路
  const rendered = new Map()
  for (const page of all) {
    let body
    if (page.id === 'api-reference') {
      body = renderApiReference(contract)
    } else if (page.id === 'samples-changelog') {
      body = renderSamples()
    } else {
      // 渲染期仍需 used 集合保证锚点唯一（页面内 slug 去重）
      body = renderMarkdown(page.body, { headings: [], used: new Set(), pageId: page.id, pageTitle: page.title })
    }
    const headings = collectHeadings(body)
    rendered.set(page.id, { body, headings })

    // 每页一条标题级条目，每个小节一条，搜索能落到具体段落
    searchIndex.push({ id: page.id, page: page.group, title: page.title, text: stripTags(String(page.lead || '')).slice(0, 140) })
    const byId = new Map(headings.map(h => [h.id, h.text]))
    for (const sec of splitSections(body)) {
      searchIndex.push({ id: sec.id, page: page.group, title: byId.get(sec.id) || page.title, text: sec.text })
    }
  }

  // 侧栏：按组拼，组内按 order
  for (const group of GROUP_ORDER) {
    const pagesInGroup = all.filter(p => p.group === group).sort((a, b) => a.order - b.order)
    if (!pagesInGroup.length) continue
    nav.push(`<div class="nav-group"><div class="nav-group-t">${escapeHtml(group)}</div>`)
    for (const page of pagesInGroup) {
      const { headings } = rendered.get(page.id)
      const subs = headings.filter(h => h.level === 2)
      nav.push(`<a class="nav-page" href="#${page.id}">${escapeHtml(page.title)}</a>`)
      if (subs.length) {
        nav.push('<div class="nav-subs">' + subs.map(h =>
          `<a class="nav-sub" href="#${h.id}">${escapeHtml(h.text)}</a>`).join('') + '</div>')
      }
    }
    nav.push('</div>')
  }

  const sections = all.map(page => {
    const { body } = rendered.get(page.id)
    return `<section class="page" id="${page.id}">
  <div class="page-kicker">${escapeHtml(page.group)}</div>
  <h1>${escapeHtml(page.title)}</h1>
  ${page.lead ? `<p class="page-lead">${inline(page.lead)}</p>` : ''}
${body}
</section>`
  }).join('\n')

  const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>问渠 · 对外集成文档 ${escapeHtml(contract.version)}</title>
<meta name="description" content="问渠开放 API 的三层接入、认证与 scope、run 事件契约、webhook 验签、断线续传与错误码。">
<link rel="stylesheet" href="assets/docs.css">
</head>
<body>
<a class="skip" href="#doc-article">跳到正文</a>
<div class="layout">
  <aside class="side">
    <div class="side-head">
      <span class="brand">问渠 · 开发者</span>
      <span class="badge">${escapeHtml(contract.version)} · 只增不删</span>
    </div>
    <div class="search-wrap">
      <input id="doc-search" type="search" placeholder="搜索文档（⌘K）" autocomplete="off" aria-label="搜索文档">
      <div id="doc-search-results" class="search-results" hidden></div>
    </div>
    <nav id="doc-nav">
${nav.join('\n      ')}
    </nav>
    <div class="side-foot">
      构建自 <code>contract/openapi.v1.json</code> + <code>docs-site/guides/*.md</code><br>
      未发布的东西不写进承诺：SDK 发包、v2 都还没有。
    </div>
  </aside>
  <main>
    <div class="topbar">
      <button class="side-toggle" type="button" aria-label="目录">≡</button>
      <span class="base-pill">地址 <code data-base-slot>…</code></span>
      <select id="doc-version" aria-label="契约版本">
        <option value="v1" selected>${escapeHtml(contract.version)}</option>
        <option value="v2" disabled>v2（未发布）</option>
      </select>
      <span class="topbar-spacer"></span>
      <a class="topbar-link" href="#errors">错误码</a>
      <a class="topbar-link" href="#api-reference">API 参考</a>
    </div>
    <article id="doc-article">
${sections}
<p class="foot-note">本站为构建产物，请勿直接编辑：改 <code>docs-site/guides/*.md</code> 或后端 <code>@OpenApi</code> 后重新构建。
产物同时被应用内「对接文档」页嵌入，两处显示的永远是同一份。</p>
    </article>
  </main>
</div>
<script src="assets/docs.js"></script>
</body>
</html>
`

  return {
    'index.html': html,
    'search-index.json': JSON.stringify(searchIndex, null, 1) + '\n',
    'assets/docs.css': fs.readFileSync(path.join(ASSET_DIR, 'docs.css'), 'utf8'),
    'assets/docs.js': fs.readFileSync(path.join(ASSET_DIR, 'docs.js'), 'utf8')
  }
}

/* ==================== 写盘 / 校验 ==================== */
function main() {
  let files
  try {
    files = build()
  } catch (e) {
    console.error(`[docs] 构建失败：${e.message}`)
    process.exit(1)
  }

  const names = Object.keys(files)

  if (CHECK) {
    const stale = []
    for (const name of names) {
      const target = path.join(OUT_DIR, name)
      if (!fs.existsSync(target)) { stale.push(`${name}（缺失）`); continue }
      if (fs.readFileSync(target, 'utf8') !== files[name]) stale.push(name)
    }
    if (stale.length) {
      console.error('[docs] 文档站产物与真源不一致：' + stale.join('、'))
      console.error('[docs] 跑 `node scripts/build-docs.mjs` 重新构建并提交 web/public/docs/')
      process.exit(1)
    }
    console.log(`[docs] 产物一致（${names.length} 个文件，${OUT_DIR.includes('web') ? 'web/public/docs' : OUT_DIR}）`)
    return
  }

  for (const name of names) {
    const target = path.join(OUT_DIR, name)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, files[name])
  }

  const kb = (Buffer.byteLength(files['index.html']) / 1024).toFixed(1)
  console.log(`[docs] 已生成 ${names.length} 个文件 → ${path.relative(ROOT, OUT_DIR) || '.'}（index.html ${kb} KB）`)
}

main()
