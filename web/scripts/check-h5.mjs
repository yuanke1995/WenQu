// 移动端适配的「行为假设」断言集。
//
// 背景：H5 第一版在真机上暴露了 3 个 bug，静态校验（SFC 编译/模块解析/CSS 语法）全绿却没发现：
//   ① 守卫组件的 allowed computed 算了但模板没用它 → /chat 也被拦
//   ② 调的 textareaRef.value.resize() 根本不存在 → 每次窗口变化抛 TypeError
//   ③ v-if="isCoarse" 与 CSS @media(hover:hover) 两套判据 → Surface 上入口被藏没
// 三者的共同形态：**代码写得像对的，但假设没对上游/浏览器/判据做核实**。
// 本脚本把每次踩到的假设固化成断言，核实上游源码或代码结构，不靠「看着对」。
//
// 用法：node scripts/check-h5.mjs
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const read = p => readFileSync(join(ROOT, p), 'utf8')
// 断言要匹配**可执行代码**，必须先剥掉注释 —— 否则「注释里写了这个 API 名字」
// 会被误判成真的调用了它（本脚本第一版就因此误报 2 条）。反之，注释里说明
// 「为什么不能这么写」是有价值的，所以只在匹配时排除，不改源码。
const stripComments = s => s
  .replace(/\/\*[\s\S]*?\*\//g, '')          // 块注释（CSS 与 JSDoc）
  .split('\n').map(l => l.replace(/(^|[^:])\/\/.*$/, '$1')).join('\n')  // 行注释（避开 https://）
let bad = 0
const check = (ok, label, detail = '') => {
  if (!ok) bad++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  →  ' + detail : ''}`)
}

// ---- ① 守卫组件必须自己控 slot：白名单页面要能透出真实内容 ----
{
  const s = read('src/h5/DesktopOnlyGuard.vue')
  check(/<slot\s+v-else\s*\/>/.test(s), '守卫组件用 <slot v-else/> 放行白名单页面',
    /<slot\s+v-else\s*\/>/.test(s) ? '' : '白名单页面也会显示引导卡（登录后落地页 /chat 被拦）')
  check(/v-if="!allowed"/.test(s), '引导卡渲染条件绑定 allowed 判据')
  check(/'\/chat'/.test(s), '白名单含 /chat（登录落地页）')
  // 白名单每加一页，就必须有对应的窄屏适配与断言（check-mshell/check-browser 各一段）
  check(/'\/artifacts'/.test(s), '白名单含 /artifacts（产物查看/下载是真需求）')
  check(/'\/knowledge'/.test(s) && /'\/knowledge'/.test(stripComments(read('src/h5/DesktopOnlyGuard.vue')).match(/ALLOW_PREFIX = (\[[^\]]*\])/)?.[1] || ''),
    '白名单含 /knowledge 且前缀表覆盖 /knowledge/:id/docs')
}

// ---- ② antd Textarea 没有 resize()，必须走 resizableTextArea.instance ----
{
  const antd = join(ROOT, 'node_modules/ant-design-vue/es/input/TextArea.js')
  if (!existsSync(antd)) {
    check(false, 'antd Textarea 源码可读（用于核实 expose）', '文件不存在，无法核实')
  } else {
    const a = stripComments(read('node_modules/ant-design-vue/es/input/TextArea.js'))
    const m = a.match(/expose\(\{[\s\S]*?\}\)/)
    const exposed = m ? m[0] : ''
    // 核实 antd 真实暴露的 API
    const hasResize = /(^|[^.\w])resize\s*[:(]/.test(exposed)
    check(!hasResize, 'antd Textarea 未暴露 resize()（所以不能直接调）',
      hasResize ? 'antd 已暴露 resize，调用路径可简化' : '确认：须走 resizableTextArea.instance.resize()')
    const c = stripComments(read('src/views/ChatPage.vue'))
    const bad1 = /textareaRef\.value\.resize\(\)/.test(c)
    check(!bad1, 'ChatPage 未直调 textareaRef.value.resize()（会抛 TypeError）')
    const good = /resizableTextArea[\s\S]{0,80}instance[\s\S]{0,40}resize/.test(c)
    check(good, 'ChatPage 走 resizableTextArea.instance.resize() 的正确路径')
  }
}

// ---- ③ 同一功能不得混用两套判据（isCoarse 与 @media(hover:hover)） ----
{
  const c = read('src/views/ChatPage.vue')
  // .think-entry 的显示只由 v-if="isCoarse" 决定
  const hasVIf = /v-if="isCoarse"[^>]*class="think-entry"|class="think-entry"[^>]*v-if="isCoarse"/.test(c)
  check(hasVIf, '.think-entry 由 v-if="isCoarse" 控制渲染')
  // 不允许再用 hover:hover 把入口藏掉（那会让 coarse+hover 的设备入口消失）。
  // 判据写成「整个文件的 @media 块里出现 hover:hover 且其后 200 字内提到 think-entry + display:none」——
  // 不能用 `/@media\s*\(hover:hover\)[^{]*\{[^}]*display:\s*none/` 这种跨花括号写法：
  // @media 块里通常有多条规则，第一个 } 就结束匹配，跨不到后面的规则（会漏报）。
  const hvIdx = c.indexOf('(hover: hover)')
  const hidesEntry = hvIdx >= 0 &&
    /think-entry[\s\S]{0,200}display:\s*none/.test(c.slice(hvIdx, hvIdx + 220))
  check(!hidesEntry, '未用 @media(hover:hover) 隐藏 .think-entry（会与 v-if 判据冲突）',
    hidesEntry ? 'Surface 等 coarse+hover 设备上入口被藏没' : '')
  // onModelOptionHover 必须对触屏早退（否则与常驻入口双开）
  check(/const onModelOptionHover[\s\S]{0,200}isCoarse\.value\) return/.test(c),
    'onModelOptionHover 对触屏早退（hover 通路关闭）')
}

// ---- ⑧ 状态必须真的绑到模板上（script 有变量 ≠ 模板用它） ----
// 这一条来自真实事故：mPanelOpen 在 script 里定义、CSS 里有 .as-sheet，
// 但模板的 v-if 仍是 panelOpen —— 窄屏点「状态」切换了一个没人读的变量，右栏永远打不开。
// 静态编译与 CSS 校验全都测不出来，只有真机能发现。凡新增「窄屏专属状态」都要过这一关。
{
  const c = stripComments(read('src/views/ChatPage.vue'))
  check(/v-if="panelOpen \|\| mPanelOpen"/.test(c), '右栏模板 v-if 绑定了 mPanelOpen（窄屏可打开）')
  check(/'as-sheet': isNarrow/.test(c), '右栏模板绑定了 as-sheet 类（窄屏变 sheet 形态）')
  check(/v-if="isNarrow"[^>]*class="rp-sheet-close"|class="rp-sheet-close"[^>]*v-if="isNarrow"/.test(c),
    '窄屏 sheet 有显式关闭按钮（覆盖式浮层必须能关）')
}

// ---- ⑨ 窄屏 UI 重构：PC 区块必须真的被条件隐藏，不能只是「加了新组件」 ----
// 本轮真机暴露：顶栏下多出一条 PC 的 .chat-head（双顶栏）、工具条被模型名撑到换行。
// 两者都属「新组件加了但 PC 区块没下线」。断言三处形态切换都到位。
{
  const c = stripComments(read('src/views/ChatPage.vue'))
  // 双顶栏：.chat-head 窄屏必须不渲染
  check(/<div v-if="!isNarrow" class="chat-head">/.test(c), '窄屏隐藏 PC 的 .chat-head（避免双顶栏）')
  check(/<MobileChatHead v-if="isNarrow"/.test(c), '窄屏渲染 MobileChatHead 动作行')
  // 欢迎卡：网格与横滑卡片二选一，不能同时渲染
  check(/<div v-if="!isNarrow" class="welcome-samples">/.test(c), '宽屏用网格示例卡')
  check(/<MobileSampleCards v-else/.test(c), '窄屏用横滑示例卡')
  // 工具条：窄屏必须拿到 nowrap 约束。
  // 用「class 切换」而非包装组件：曾用 <component :is> + 具名 slot 包装，
  // 结果内容全被丢弃（渲染成空的 mib-left/mib-right）—— 具名槽卡位置，漏接就丢内容。
  // 现在只加 as-mobile 类，DOM 结构两种形态完全一致。
  check(/:class="isNarrow \? 'input-toolbar as-mobile' : 'input-toolbar'"/.test(c),
    '工具条用 as-mobile 类切换（不套包装组件）')
  check(/MobileInputBar/.test(c) === false, '未引入 MobileInputBar 包装组件（曾导致工具条内容被丢弃）')
  check(/:compact="isNarrow"/.test(c), '模型选择器窄屏用 compact（省略号、不换行）')
  // 智能体胶囊窄屏只留头像
  check(/v-if="!isNarrow" class="agent-pill-name"/.test(c), '智能体名称窄屏隐藏（只留头像）')
  // placeholder：键盘快捷键提示对触屏无意义且占 3 行
  // placeholder：键盘快捷键提示对触屏无意义且占 3 行。表达式已从模板挪进
  // composerPlaceholder computed（生成中要换文案），断言随之改判结果而不是写法：
  // 窄屏分支里不许出现 Shift+Enter
  const ph = (c.match(/const composerPlaceholder = computed\(\(\) => \{[\s\S]*?\n\}\)/) || [''])[0]
  check(/:placeholder="composerPlaceholder"/.test(c) && !!ph
    && !/isNarrow\.value\s*\?\s*'[^']*Shift\+Enter/.test(ph),
    'placeholder 窄屏换短版（去掉 Enter/Shift+Enter 提示）')
}

// ---- ④ mobile.js 顶层执行：matchMedia 缺失必须降级，不能白屏 ----
{
  const m = stripComments(read('src/h5/mobile.js'))
  const guarded = /try\s*\{[^}]*matchMedia/.test(m) && /const mq = q =>/.test(m)
  check(guarded, 'matchMedia 调用有 try/catch + 兜底（本模块在 main.js 顶层 import，抛错即白屏）')
  check(/navigator\.maxTouchPoints/.test(m) === false || /Boolean\(\(navigator\.maxTouchPoints/.test(m),
    'maxTouchPoints 缺失有兜底')
}

// ---- ⑤ 键盘适配：不得用 fixed/transform 顶位（会破坏 fixed 灯箱） ----
{
  const k = read('src/h5/keyboard.js')
  check(/--app-vh/.test(k) && /--kb/.test(k), '键盘适配写 CSS 变量（--app-vh / --kb）')
  // 关键：不能在 .chat-col 上再扣一次 --kb（重复扣会让消息区矮一个键盘）
  const c = read('src/views/ChatPage.vue')
  const double = /\.chat-col[^{]*\{[^}]*var\(--kb/.test(c)
  check(!double, '.chat-col 未再扣 --kb（--app-vh 已透传，重复扣会让消息区矮一个键盘）')
  check(!/window\.scrollTo\(0,\s*0\)/.test(c), '未写 window.scrollTo(0,0)（会与 Safari 聚焦滚动打架）')
}

// ---- ⑥ 分享页也必须窄屏适配（分享链接大量在微信/手机打开） ----
{
  for (const [f, label] of [['src/views/ShareChatPage.vue', '免登录分享对话页 /s/:token'],
                            ['src/views/SharedSessionPage.vue', '只读会话分享页 /shared/:token']]) {
    const s = read(f)
    check(/@media\s*\(max-width:\s*768px\)/.test(s), `${label} 有窄屏适配`)
  }
}

// ---- ⑦ PWA：viewport-fit 与 safe-area 变量必须成对 ----
{
  const html = read('index.html')
  const css = read('src/views/app.css')
  check(/viewport-fit=cover/.test(html), 'index.html 启用 viewport-fit=cover')
  check(/--sat:\s*env\(safe-area-inset-top/.test(css), 'app.css 定义 --sat 变量')
  check(/apple-mobile-web-app-status-bar-style/.test(html), '声明状态栏样式（与 --sat 成对）')
  const mf = join(ROOT, 'public/manifest.webmanifest')
  check(existsSync(mf), 'manifest.webmanifest 存在')
  if (existsSync(mf)) {
    const icons = (JSON.parse(read('public/manifest.webmanifest')).icons || []).map(i => i.src)
    const missing = icons.filter(i => !existsSync(join(ROOT, 'public', i.replace(/^\//, ''))))
    check(!missing.length, 'manifest 引用的图标文件均存在', missing.join(','))
  }
}

// ---- ⑧ 时间线渲染构件必须 PC/H5 同构（过程簇这一层最容易只改一端） ----
// 背景：连续「独白+工具」收成过程簇（clusterize）时，簇头、簇内标题改名（执行过程→过程说明）、
// 折叠态判定（clusterOpen）在两端各有一份实现。漏改一端 ⇒移动端要么不聚簇、要么文案父子同名套娃，
// 而静态编译全绿（只少一个 v-else-if 分支而已），只有真机翻聊天记录才发现。
{
  const pc = stripComments(read('src/views/ChatPage.vue'))
  const h5 = stripComments(read('src/h5/MobileMsgRow.vue'))
  for (const [label, s] of [['PC', pc], ['移动端', h5]]) {
    check(/seg\.kind === 'cluster'/.test(s), `${label} 渲染过程簇头（cluster 分支存在）`)
    check(/timelineRows\(m\)/.test(s), `${label} 用timelineRows 摊平渲染（非旧 timelineView）`)
    check(/inCluster\s*\?\s*'过程说明'\s*:\s*'执行过程'/.test(s),
      `${label} 簇内独白段改称「过程说明」（防父子同名套娃）`)
    check(/toggleCluster\(m,\s*seg\)/.test(s), `${label} 簇头可点开/收起`)
    check(/clusterProcCount\(seg\)/.test(s), `${label} 纯独白簇有退化文案（N 段说明）`)
  }
  // 聚簇边界：不得跨正文段打包，也不得给单段套壳（否则单个工具组要点两次）
  const pj = read('src/chat/projections.js')
  const cz = stripComments(pj).match(/const clusterize = out => \{[\s\S]*?\n\}/)
  check(!!cz, 'clusterize 实现可定位')
  if (cz) {
    const src = cz[0]
    check(/seg\.kind === 'process'\s*\|\|\s*seg\.kind === 'group'/.test(src),
      '只把 process/group 段纳入游程（text 段天然断簇）')
    check(/run\.length === 1\s*\)\s*res\.push\(run\[0\]\)/.test(src),
      '单段不包壳（run.length === 1 直通）')
  }
}

console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
process.exit(bad ? 1 : 0)
