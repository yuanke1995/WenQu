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

// ---- ② 输入框内核：contenteditable 的 Composer（此前是 antd Textarea） ----
{
  const c = stripComments(read('src/views/ChatPage.vue'))
  check(!/textareaRef/.test(c), 'ChatPage 主输入框不再走 antd Textarea 的 ref 通道',
    /textareaRef/.test(c) ? '仍有 textareaRef 残留：那条 resize 路径已经不存在了' : '')
  // 输入区已抽成 chat/ChatInput.vue（与公开分享页共用一份壳）。剪枝通道（DOM→数组）现在穿过两层：
  // ChatPage 接 @update:keys="onKeys"，ChatInput 再把 Composer 的 update:keys 原样上抛。
  // 两层任何一层漏接，Backspace 删掉 chip 后数组里那条就还在——引用还在生效、卡片却没了
  const inputSfc = read('src/chat/ChatInput.vue')
  check(/<ChatInput[\s\S]{0,700}@update:keys="onKeys"/.test(c),
    'ChatPage 装配 ChatInput 且接了 DOM→数组 的剪枝通道')

  // ---- 消息区整块复用：两页都必须装配 chat/ChatThread.vue ----
  // 这一层是「免得两头改」的地基：行/块/气泡布局与四个叶子的次第只有一份。
  // 静态断言它是为了防"回退式重构"——谁哪天把行布局抄回页面里，两侧立刻开始漂，
  // 而症状（两条竖边对不齐、降级条跑到正文上面）只有肉眼能看出来。
  const thread = read('src/chat/ChatThread.vue')
  const shareSfc = read('src/views/ShareChatPage.vue')
  check(/import ChatThread from '\.\.\/chat\/ChatThread\.vue'/.test(c) && /<ChatThread[\s\S]{0,400}:messages="messages"/.test(c),
    'ChatPage 装配 ChatThread（消息行布局只有一份）')
  check(/import ChatThread from '\.\.\/chat\/ChatThread\.vue'/.test(shareSfc) && /<ChatThread[\s\S]{0,400}:messages="messages"/.test(shareSfc),
    '分享页装配 ChatThread（同一份外壳）')
  check(/\.msg-block\s*\{/.test(thread) && /\.bubble\.user\s*\{/.test(thread),
    '行/块/气泡布局只定义在 ChatThread 里')
  check(!/\.msg-block\s*\{/.test(c) && !/^\.bubble\s*\{/m.test(c),
    'ChatPage 不再自己写行/块/气泡布局（抄回来就会两边漂）')
  check(!/\.sc-msg-block\s*\{/.test(shareSfc) && !/\.sc-bubble\s*\{/.test(shareSfc),
    '分享页不再自己写行/块/气泡布局')
  // 叶子只该由 ChatThread 摆位：页面各自摆一遍，次第迟早不一致
  check(/<UserBubble/.test(thread) && /<DegradationBar/.test(thread) && /<AnswerActions/.test(thread),
    '四个叶子由 ChatThread 统一摆位（提问随行材料 / 降级条 / 动作行）')
  check(/<Composer[\s\S]{0,700}@update:keys="k => emit\('update:keys', k\)"/.test(inputSfc),
    'ChatInput 把 Composer 的 update:keys 上抛（少这一句剪枝通道就断在组件里）')
  // antd Textarea 只 expose focus/blur/resizableTextArea，没有 resize()：这条上游事实仍然成立，
  // 页面里剩下的 a-textarea（计划意见框、反馈框）哪天要自测高度，仍须走 resizableTextArea.instance.resize()
  const antd = join(ROOT, 'node_modules/ant-design-vue/es/input/TextArea.js')
  if (!existsSync(antd)) {
    check(false, 'antd Textarea 源码可读（用于核实 expose）', '文件不存在，无法核实')
  } else {
    const a = stripComments(read('node_modules/ant-design-vue/es/input/TextArea.js'))
    const m = a.match(/expose\(\{[\s\S]*?\}\)/)
    const exposed = m ? m[0] : ''
    check(!/(^|[^.\w])resize\s*[:(]/.test(exposed), 'antd Textarea 仍未暴露 resize()（残留 a-textarea 不能直接调）')
  }
}

// ---- ②b 内联 chip 的三条静默失效面（写错不报错、界面直接不对） ----
{
  const cp = read('src/chat/Composer.vue')
  const c = read('src/views/ChatPage.vue')
  // chip 节点是命令式建的，拿不到 scoped 的 data-v 标记：样式不包 :deep() 就一条都不生效
  check(/:deep\(\.cchip\)/.test(cp), 'Composer 的 chip 样式走 :deep()（否则 scoped 选择器匹配不到命令式节点）')
  // 图标靠 data-ic 与 chipOf 返回的 icon 对上；对不上就是没图且不报错（kebab 名写错同罪）
  const provided = new Set([...cp.matchAll(/data-ic="([^"]+)"/g)].map(m => m[1]))
  const vocab = ['file', 'mention', 'kb', 'agent', 'hist', 'plan', 'img']
  const missing = vocab.filter(k => !provided.has(k))
  check(missing.length === 0, 'chip 图标源覆盖 chipOf 用到的每个 icon 名',
    missing.length ? '缺：' + missing.join(', ') : `已核对 ${vocab.length} 个`)
  // kebab 图标标签要靠显式具名导入才解析得到（AntDesignVueResolver 不解析 icons-vue）：
  // 漏 import 不报错、不画图标，看起来只像「UI 丑」
  const tplIcons = [...cp.matchAll(/<([a-z]+(?:-[a-z]+)*)-outlined\s+/g)].map(m => m[1])
  const imported = new Set((cp.match(/import \{([^}]*)\} from '@ant-design\/icons-vue'/)?.[1] || '')
    .split(',').map(s => s.trim()).filter(Boolean))
  const pascal = s => s.replace(/(^|-)([a-z])/g, (m, _x, c) => c.toUpperCase()) + 'Outlined'
  const noImport = [...new Set(tplIcons)].filter(t => !imported.has(pascal(t)))
  check(noImport.length === 0, 'Composer 模板里每个 *-outlined 都有对应的 icons-vue 具名导入',
    noImport.length ? '缺 import：' + noImport.map(pascal).join(', ') : `已核对 ${new Set(tplIcons).size} 个`)
  // 正文与 chip 的边界：序列化必须跳过 chip 节点，否则附件名会被当成正文发给模型
  check(/if \(isChip\(c\)\) continue/.test(stripComments(cp)), 'Composer 序列化跳过 chip（chip 不产出字符）')
  // 复制靠 copy 事件的 setData：ClipboardItem 写文件受 MIME 白名单限制，走错一条路就静默丢附件
  check(/clipboardData\.setData\('text\/html'/.test(stripComments(cp)), '复制走 clipboardData.setData 外发 chip 凭据')
  // 正文为空时「下标 0」对每颗 chip 都成立：锚到 chip 前面＝光标看着跑到整行最左、下一个字打到 chip 左边
  check(/return hit \|\| \(chipTail \? \{ after: chipTail \} : null\)/.test(stripComments(cp)),
    'posToPoint 的零宽退路锚在 chip 串之后（锚回 chip 前面就会把光标丢到句首）')
  check(/syncEmpty\(\)[\s\S]{0,80}parkCaretPastChips\(\)/.test(stripComments(cp)),
    'afterEdit 里补一次 parkCaretPastChips（Chrome 删空正文时把光标丢到编辑区最左）')
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
  // 工具条：窄屏必须拿到 nowrap 约束，否则模型名一变长就把「思考」压成竖排两字。
  // 这条约束随工具条一起进了 chat/ChatInput.vue（它的 ≤768 块里），不再由页面切 as-mobile 类。
  // 历史教训仍要防住：曾用 <component :is> + 具名 slot 包装工具条，
  // 结果内容全被丢弃（渲染成空的 mib-left/mib-right）——具名槽卡位置，漏接就丢内容。
  check(/@media \(max-width: 768px\)[\s\S]*?\.ci-toolbar \{ flex-wrap: nowrap/.test(read('src/chat/ChatInput.vue')),
    '工具条窄屏 nowrap 约束在 ChatInput 的 ≤768 块里')
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
    check(/turnRows\(m\)/.test(s), `${label} 用整块折叠那一步摊平渲染（收起时只出正文行）`)
    check(/inCluster\s*\?\s*'过程说明'\s*:\s*'执行过程'/.test(s),
      `${label} 簇内独白段改称「过程说明」（防父子同名套娃）`)
    check(/toggleCluster\(m,\s*seg\)/.test(s), `${label} 簇头可点开/收起`)
    check(/clusterProcCount\(seg\)/.test(s), `${label} 纯独白簇有退化文案（N 段说明）`)
    // 簇内只有一个工具组 ⇒ 组条与簇头数的是同一批操作（真机两行都报「7 个操作」）：那一层不出
    check(/seg\.tools\.length > 1 && !seg\.flat/.test(s), `${label} 单组簇不再重复出组条（与簇头同一批操作）`)
    check(/seg\.tools\.length === 1 \|\| seg\.flat \|\| seg\.tools\[0\]\._groupOpen/.test(s),
      `${label} 组条不出时卡片直接摊开（不能连卡片一起藏掉）`)
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
  const tr = stripComments(pj).match(/const timelineRows = m => \{[\s\S]*?\n\}/)
  check(!!tr && /groups\.length === 1 && inner\.kind === 'group'/.test(tr[0]),
    'flat 只给「簇内唯一的那个工具组」——两个以上组各数各的子集，组条要留')
}

// ---- ⑧b 耗时只报一处：整轮那一条在气泡顶部，逐行耗时不许复现（两端同构） ----
// 背景：这里曾经是簇头、组条、每张工具卡各挂一个数，一屏六个读数读不出重点，两个口径还互相打架
// （组条是首尾跨度、单卡是各自跑掉多久）。更要命的是那些数只活在 SSE 那一帧、不落库，刷新后
// 每张卡都没数却照样渲染「· 0ms」——把「没记录」显示成「跑了 0 毫秒」。
// 现在一轮只顶部那一条报一个数（后端算好的机器时间，随消息落库），它同时是整块过程的折叠开关。
{
  const pc = stripComments(read('src/views/ChatPage.vue'))
  const h5 = stripComments(read('src/h5/MobileMsgRow.vue'))
  const bar = stripComments(read('src/components/TurnBar.vue'))
  check(/turnShow\(m\)/.test(bar) && /turnHead\(m\)/.test(bar) && /toggleTurn\(m\)/.test(bar)
    && /turnToggleable\(m\)/.test(bar) && /turnTitle\(m\)/.test(bar),
    '那一条的渲染与折叠开关只有一份实现（TurnBar.vue），两端各挂一次')
  check(!/v-if="turnExpandable\(m\)"/.test(bar),
    '开关不只认「有没有过程」——跑着的时候它只是一行计时器，不给折叠（收起正在长的过程没有可读的东西）')
  for (const [label, s] of [['PC', pc], ['移动端', h5]]) {
    const tops = (s.match(/<TurnBar[^>]*!m\.loading[^>]*>/g) || []).length
    const bots = (s.match(/<TurnBar(?![^>]*!m\.loading)[^>]*m\.loading[^>]*>/g) || []).length
    check(tops === 1 && bots === 1,
      `${label} 那一条按「跑着在正文末尾 / 答完在回答顶上」两处各挂一次（实得 顶 ${tops} 底 ${bots}）`)
    // 逐行耗时复现 = 一屏又开始报好几个数，用户点名的就是这件事
    for (const gone of ['clusterDur', 'groupDur', 'fallbackDur', 'liveToolDur', 'toolDuration', 'DUR_SPAN_TITLE']) {
      check(!new RegExp(`${gone}\\(`).test(s), `${label} 不再逐行报耗时（${gone} 已退役）`)
    }
    // 进行中提示也只留这一处：原先气泡底下还挂一行阶段文案，两句「正在…」上下同屏
    check(!/class="busy"|busy-hint/.test(s), `${label} 那一条底下不再另挂一行进行中提示`)
  }
  check(!/toolDuration\(/.test(bar), '那一条自己也不报逐项耗时（只报整轮那一个数）')
  const pj = stripComments(read('src/chat/projections.js'))
  const rows = pj.match(/const turnRows = m => \{[\s\S]*?\n\}/)
  check(!!rows, '折叠判据可定位（turnRows）')
  if (rows) {
    check(/timelineRows\(m\)/.test(rows[0]) && /turnOpen\(m\) \?/.test(rows[0]),
      '展开走原交错时序、收起才裁行（不是反过来把时间线拆成两份）')
    check(/kind === 'text'/.test(rows[0]), '收起时只留正文行（正文各段本就是 content 的连续切片）')
  }
  const open = pj.match(/const turnOpen = m => \{[\s\S]*?\n\}/)
  check(!!open && /_turnOpen/.test(open[0]) && /m\.loading/.test(open[0]),
    '跑着展开、答完收起，且用户点过的那一次优先（否则读过程读到一半被自动收掉）')
  check(/const durZh = ms =>/.test(pj), '中文长格式只有一份（PC 与 H5 共用）')
  // 跨度那套（首尾相减、扣等待账）随逐行耗时一起退役：留着就是两套口径并存，早晚有人接回去
  for (const gone of ['spanOfTools', 'toolEndAt', 'DUR_SPAN_TITLE', 'const clusterDur', 'const groupDur', 'const fallbackDur']) {
    check(!new RegExp(gone).test(pj), `${gone} 已删净（不许与整轮耗时并存两套口径）`)
  }
  // 气泡级进度行那套互斥优先级链随第二行一起退役：它的存在前提（进度行要在多处构件间让位）已经没了
  for (const gone of ['const busyOf', 'subRunning']) {
    check(!new RegExp(gone).test(pj), `${gone} 已删净（进行中提示只由那一条表达，不许再长出第二处）`)
  }
  // 那个数必须落库：只随 done 一帧下发的话，刷新一次「这一轮跑了多久」就读不出来
  check(/turnElapsedMs/.test(pj), '前端读的是落库/done 那一份整轮用时')
  const head = pj.match(/const turnHead = m => \{[\s\S]*?\n\}/)
  check(!!head && /const dur = ms \?/.test(head[0]),
    '没数时标题里不拼「用时」（宁可只说「已完成」，凑出来的 0 会被当成事实读）')
}

// ---- ⑨ 过程回放（事件账本）：两端共用一份，移动壳不许没有 ----
// 背景：逐轮回放落地时只做了 PC（头部「过程」+ 气泡「看这一轮的过程」），移动壳整条入口缺席，
// 而静态检查全绿——少的是一个按钮和一层 sheet，编译不出来。这类「一端有、一端压根没入口」
// 靠断言钉住：渲染与取数只允许有一份实现（components/SessionEventsPanel.vue），两端都只装配它。
{
  const pc = stripComments(read('src/views/ChatPage.vue'))
  const h5 = stripComments(read('src/h5/MobileChatPage.vue'))
  const sheet = read('src/h5/MobileEventsSheet.vue')
  const round = stripComments(read('src/h5/MobileRoundSheet.vue'))
  const panel = read('src/components/SessionEventsPanel.vue')
  check(/SessionEventsPanel/.test(pc), 'PC 壳装配共用面板（不再自带取数）')
  check(!/getSessionEvents\(/.test(pc), 'PC 壳不直接叫账本端点（取数只在面板里）')
  check(/MobileEventsSheet/.test(h5) && /openEvents\(/.test(h5), '移动壳有会话级过程入口')
  check(/SessionEventsPanel/.test(sheet), '移动壳的过程 sheet 复用同一面板')
  check(/\$emit\('events', m\.turnId\)/.test(round) && /v-if="m && m\.turnId"/.test(round),
    '单轮过程入口存在且只在有 turnId 时露出（无记录的不猜轮次）')
  // 取数触发条件：sheet 用 v-if 渲染内容，面板是在 open 已为 true 时才挂上来的——
  // 没有 immediate 就永远不请求，症状是「打开就是空态：这个会话还没有过程记录」
  check(/immediate: true/.test(panel), '面板挂载即取数（sheet 的 v-if 形态）')
  check(/过程记录读取失败/.test(panel) && /ev-retry/.test(panel), '面板失败要说出来并给重试（不许静默空态）')
}

console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
// ---- ⑩ 深度思考按轮就地成段（不许叠回气泡顶上） ----
// 背景：一轮问答常有多次思考（每次调工具/出正文前各一次）。此前它们首尾粘成一块挂在气泡顶上，
// 用户读不出「这一段是在哪一步想的」。改成 think 段进时间线后，最容易复发的三种退化是：
//   ① 只改一端（PC 就地、H5 还在顶上叠一块）——两端各有一份模板，编译都不会报错；
//   ② 后端落了 think 段但前端某处不认这个 kind，思考整块看不见（静默丢段比丢卡片更难发现）；
//   ③ 存量消息没分段、又没兜底，刷新后历史里的思考凭空消失。
{
  const pc = stripComments(read('src/views/ChatPage.vue'))
  const h5 = stripComments(read('src/h5/MobileMsgRow.vue'))
  const eng = stripComments(read('src/chat/useChatEngine.js'))
  const pj = read('src/chat/projections.js')
  for (const [label, s] of [['PC', pc], ['移动端', h5]]) {
    check(/seg\.kind === 'think'/.test(s), `${label} 在时间线里就地渲染思考段`)
    check(/toggleThinkSeg\(m,\s*seg\)/.test(s), `${label} 思考段可点开/收起`)
    check(/thinkSlice\(m,\s*seg\)/.test(s), `${label} 只渲染这一段区间（不整块全文重印）`)
    check(/thinkGrowing\(m,\s*seg\)/.test(s), `${label} 进行中标记挂在正在长的那一段上`)
    check(!/m\.thinkOpen/.test(s), `${label} 不再读写消息级 thinkOpen（折叠态按段存 _thinkOpen）`)
  }
  check(!/class="think-panel"/.test(pc), 'PC 气泡顶上那块整段思考面板已退役（防叠加回潮）')
  // 段格式三处必须同时认 think：视图、历史重建、以及「值不值得走时间线」的门槛
  const tv = stripComments(pj).match(/const timelineView = m => \{[\s\S]*?\n\}/)
  check(!!tv && /seg\.kind === 'think'/.test(tv[0]), 'timelineView 认 think 段（不认就是静默丢段）')
  const rt = stripComments(pj).match(/const restoreTimeline = \(m, segs\) => \{[\s\S]*?\n\}/)
  check(!!rt && /'think'/.test(rt[0]), 'restoreTimeline 认 think 段（刷新后仍是分段视图）')
  const htb = stripComments(pj).match(/const hasTimelineBlocks = m => \{[\s\S]*?\n\}/)
  check(!!htb && /'think'/.test(htb[0]),
    'hasTimelineBlocks 含 think（只有思考、没调工具的回答也走时间线，否则那段无处可渲染）')
  // 存量/兜底：历史、done、接流快照三条路径都要补，少一条就是那条路上看不见思考
  check(/const ensureThinkSegment = m =>/.test(stripComments(pj)), '头部补段的兜底函数在位')
  const nEns = (eng.match(/ensureThinkSegment\(msg\)/g) || []).length
  check(nEns >= 3, `三条取数路径都调兜底补段（实测 ${nEns} 处，少于 3 即有路径看不见思考）`)
  check(/pushTimelineThink\(msg/.test(eng) && /extendTimelineThink\(msg/.test(eng),
    '引擎按段建/续：断过段另起一段，连续增量延伸末段')
  const nClose = (eng.match(/thinkRoundOpen = false/g) || []).length
  check(nClose >= 4,
    `收尾帧/正文/过程/工具四处都断段（实测 ${nClose} 处，少一处就会把下一轮思考接在同一行后面）`)
}

// ---- ⑪ 计划模式（人在回路）两端同构 ----
// 背景：计划卡此前只有 PC 有。引擎的 hydratePendingPlans 两端都把卡片挂进 messages，
// 移动壳却没有任何地方渲染它——现象是「手机上出了一轮计划，界面一个字不显示，只能干等超时」。
// 最容易复发的五种退化都钉在这里：
//   ① 只在某一端加模板（两端各一份文件，编译都不会报错）；
//   ② 挂卡判据与倒计时在两端各算一遍，一端改了另一端静默漂；
//   ③ 移动壳放开了开关却没有确认栏 → 发出一轮「没人能批准」的计划问答；
//   ④ 卡片本体被复制成两份样式，改一处漏一处；
//   ⑤ PC 改用引擎的名字却没加进解构（点到那条分支才 ReferenceError）。
{
  const eng = stripComments(read('src/chat/useChatEngine.js'))
  const pc = stripComments(read('src/views/ChatPage.vue'))
  const h5 = stripComments(read('src/h5/MobileChatPage.vue'))
  const row = stripComments(read('src/h5/MobileMsgRow.vue'))
  const cardPath = 'src/components/PlanCard.vue'
  const card = existsSync(join(ROOT, cardPath)) ? stripComments(read(cardPath)) : ''
  check(!!card, '计划卡是共用组件 src/components/PlanCard.vue（两端各写一份必然改一处漏一处）')
  check(/from '\.\.\/components\/PlanCard\.vue'/.test(pc) && /from '\.\.\/components\/PlanCard\.vue'/.test(row),
    'PC 气泡与移动气泡挂的是同一个 PlanCard')
  // 派生态只许引擎一处定义
  for (const [label, s] of [['PC', pc], ['移动壳', h5], ['消息行', row]]) {
    check(!/const pendingPlan =/.test(s), `${label} 不自算挂卡判据（归引擎）`)
    check(!/const planExpired =/.test(s), `${label} 不自算超时锁（归引擎）`)
    check(!/const planCountdownText =/.test(s), `${label} 不自算倒计时（归引擎）`)
  }
  check(/const pendingPlan = computed/.test(eng) && /const planExpired = computed/.test(eng)
    && /const planCountdownText = computed/.test(eng), '引擎是这三份派生态的定义处')
  const nPend = (eng.match(/const pendingPlan = computed/g) || []).length
  check(nPend === 1, `引擎里 pendingPlan 只有一份（实测 ${nPend} 份）`)
  // 移动壳三件套：开关放行 + 确认栏顶替输入卡 + 三个裁决口都接引擎
  check(/planModeEnabled: true/.test(h5), '移动壳放行计划开关（有确认栏才允许开）')
  check(/v-else-if="pendingPlan"/.test(h5) && /m-planp/.test(h5),
    '移动壳有计划确认栏且顶替输入卡（与提问面板同形）')
  check(/submitPlanApproval\(pendingPlan, 'approve'\)/.test(h5)
    && /continuePlanConversation\(pendingPlan/.test(h5)
    && /submitPlanApproval\(pendingPlan, 'cancel'\)/.test(h5),
    '移动壳确认/继续对话/取消三个裁决口都走引擎同一套动作')
  check(/pendingPlan, curPlanCard, planExpired, planCountdownText/.test(read('src/views/ChatPage.vue')),
    'PC 已把这四个名字加进引擎解构（漏一个要到那条分支才炸）')
  const h5Raw = read('src/h5/MobileChatPage.vue')
  const sheet = stripComments(read('src/h5/MobileAttachSheet.vue'))
  check(/<ordered-list-outlined/.test(sheet) && /OrderedListOutlined/.test(sheet),
    '「+」sheet 里计划开关的图标已显式 import（kebab 名映射不到导入名就什么都不画）')
  check(/planModeOn, setPlanMode/.test(sheet), '「+」sheet 的计划开关吃引擎那份状态（与主壳那颗 chip 同源）')
  // 入口从工具条收进「+」之后，开启态只剩输入框上方那颗 chip——开关与留痕必须同时在场，
  // 缺一半就是「拨了看不见」（工具条那一排装不下：四颗药丸固定要 360px，360px 屏把送出键挤出输入卡）。
  check(/m-chip-plan/.test(h5Raw) && /v-if="planModeOn"/.test(h5Raw),
    '移动壳计划开启态在输入框上方回显 chip（开关已不在工具条上，别把状态说丢）')
  check(!/<ordered-list-outlined/.test(h5Raw), '移动壳工具条不再挂计划药丸（要挪就挪干净，两处都有会撞回去）')
}

// ---- ⑫ 移动壳不许有「点了撞守卫」的入口，知识库只读承诺要说真话 ----
// 背景：DesktopOnlyGuard 白名单之外的页面（/agents 等）在手机上是一张引导卡。
// 两处曾经把用户往那儿送：工作区页的「去画布」用 raw href（整页重载才撞卡），
// 运行列表的「运行详情」用 router.push。另一个方向也错：守卫对 /knowledge 写的是
// 「只读浏览」，可库页的新建/编辑/删除与文档页的上传/批量照旧渲染——文案是假的。
{
  const pjRaw = read('src/views/ProjectPage.vue')
  check(/<a v-if="!mobileShell" class="app-btn ghost pj-block-act" href="\/agents/.test(pjRaw),
    '工作区页「去画布」在移动壳不渲染（raw href 会整页重载撞引导卡）')
  check(/x\.kind === 'workflow' && !mobileShell/.test(pjRaw),
    '工作区页「运行详情」在移动壳不渲染（同上）')
  // 工作区文件的「预览」＝进预览台（utils/previewStage）。锚点必须还是 <a target="_blank">：href 就是
  // 那份真签名地址，Ctrl/中键点击照旧开成真标签——预览台接管的是普通左键，不是把这条路废掉。
  check(/class="pj-act"\s+:href="resolveImg\(f\.viewUrl \|\| f\.artifact\.url\)" target="_blank" rel="noopener"\s+data-pv/.test(pjRaw),
    '工作区页「预览」带 data-pv 且 href 仍是签名地址（左键进预览台，Ctrl/中键开真标签）')
  // 取原文走登录态接口，不复用签名地址：那条地址 1 小时过期 + 响应带 CSP sandbox，
  // 两样都会变成预览台要处理的异常，而用户看到的只是「点开是空白」。
  check(/'data-pv-project': id\.value, 'data-pv-path': f\.path/.test(pjRaw),
    '工作区文件行按「工作区 id + 相对路径」取原文（不 fetch 签名地址）')
  // 同一行既在目录里又有产物记录时必须只传路径那一份：都传会让读字档优先拿产物旧副本顶掉目录当前内容
  check(/f\.path\s*\?\s*\{[^}]*'data-pv-project'/.test(pjRaw),
    '预览台取址按行来源二分（文件行不连带传产物 id）')
  // 没落进工作目录的那一行只有产物记录，此前它写死 viewUrl:'' → 同一份 html 在聊天区能预览、
  // 在这个页只有下载（线上实测）。判据必须继续是 artifactView 那一份，别在这儿重抄扩展名清单。
  check(/v-if="f\.viewUrl \|\| canPreview\(f\.artifact\)"/.test(pjRaw),
    '工作区页产物行走 artifactView 那份判据（与聊天区/产物页同一颗按钮）')
  // 模板里引用了却没 import 是最坏的一种静默：Vue 不报错，v-if 恒假 → 按钮整排消失，
  // 与「这个类型不给预览」看起来一模一样（就是这次线上那次的症状形状）。
  check(/import \{ canPreview, artifactExt \} from '\.\.\/utils\/artifactView'/.test(pjRaw),
    '工作区页 import 了 canPreview 与 artifactExt（漏 import 会让预览按钮静默不渲染）')
  check(!/previewProjectFile|previewState/.test(pjRaw),
    '那条按 projectId+path 取字节的老预览端点没有复活（读原文走 /file/content）')
  // ---- 文件栏可管理：下面每条都是真机点验当场抓到的失效形态，不是假想 ----
  const pjCode = stripComments(pjRaw)
  // a-menu 的 click 载荷是对象（key/keyPath/item/domEvent）。当裸 key 用会得到 "[object Object]"，
  // 三个行内动作全静默失效——菜单能弹开、点了没反应、控制台干净，静态编译也看不出来。
  check(/rowAction\(String\(info && info\.key\)/.test(pjCode), '行菜单按载荷对象的 key 派发（裸 key 那条路已死）')
  check(!/@click="key =>/.test(pjCode), '行菜单不许把 click 载荷当裸 key')
  // #overlay 这个 slot 里多出注释节点，弹层会渲染成空的（下拉框展开了但 0 项）。
  // 所以注释必须待在 <template #overlay> 外面——这条被真机抓到过一次。
  check(/<template #overlay>\s*<a-menu/.test(pjRaw), '#overlay 里第一个节点就是 a-menu')
  // 命中区铺满左侧之后，16px 的框自己得接住那次点击：它带 @click.stop，只把 toggle 挂在外层
  // span 上时，正中间那一下被 stop 掉、点了没反应（用户第一下通常就点在框上）。
  check(/<a-checkbox[^>]*@click\.stop="toggleSel\(f\)"/.test(pjRaw), '勾选框自带 toggleSel')
  check(/askDeletePaths\(sel\)|openMove\(sel\)/.test(pjCode), '批量栏走当前勾选那一批路径')
  const apiCode = stripComments(read('src/api.js'))
  check(/body: JSON\.stringify\(op === 'move' \? \{ paths, op, dir: dest \|\| '' \} : \{ paths, op \}\)/.test(apiCode),
    '批量调用总把 op 带上（move 时 dir 允许是空串＝根目录）')
  const pjCtrl = stripComments(read('../src/main/java/com/wenqu/ai/controller/ProjectController.java'))
  check(/"move"\.equals\(op\)/.test(pjCtrl) && /"delete"\.equals\(op\)/.test(pjCtrl)
    && /op 只能是 move 或 delete/.test(pjCtrl),
    '后端批量按 op 分派、没给 op 就拒——不能靠 dir 留没留猜意图（空串 dir 就是根目录）')
  const pjSvc = stripComments(read('../src/main/java/com/wenqu/ai/service/ProjectFileService.java'))
  check(/STATUS_ARCHIVED\.equals\(p\.getStatus\(\)\)/.test(pjSvc) && /projectTurnMutex\.isBusy\(p\.getId\(\)\)/.test(pjSvc),
    '写侧两道闸在服务层（归档拒写、有轮次在跑时不删不移），不只靠前端 disabled')
  // 两处都要比真实路径：下载/预览那条（resolveIn）与目录那条（existingDir）。
  // 只断言「出现过一次」会被另一处蒙过——阳性对照跑出来过这个盲区。
  const realPathSites = (pjSvc.match(/toRealPath\(\)\.startsWith\(base\.toRealPath\(\)\)/g) || []).length
  check(realPathSites >= 2, '下载口与目录口两处都比真实路径前缀（软链借下载口越界那条路已堵）', '命中 ' + realPathSites + ' 处')
  const kb = stripComments(read('src/views/KnowledgeBasePage.vue'))
  check(/v-if="!mobileShell" class="app-btn"[^>]*@click="openCreate"/.test(kb),
    '库页「新建知识库」在移动壳不渲染（兑现守卫的只读承诺）')
  check(/v-if="!mobileShell && \(isAdmin/.test(kb), '库页编辑/图谱/删除整组动作在移动壳不渲染')
  const dp = stripComments(read('src/views/DocumentsPage.vue'))
  check(/const canManageCurrentKb = computed\(\(\) => \{\s*if \(mobileShell\.value\) return false/.test(dp),
    '文档页把移动壳收进 canManageCurrentKb 根判据（逐按钮加 v-if 必漏）')
  check(/const canManageDoc = d => !mobileShell\.value/.test(dp),
    '文档行内写动作走 canManageDoc，同一判据收口')

  // 产物的「预览」与上面同一待遇：进预览台（utils/previewStage），两端六个面都不例外——
  // 气泡底部产物卡、PC 右栏「产物」卡、H5 消息行、H5「状态与来源」面板、产物页、工作区页。
  // 判据（哪一类怎么摆）只有 utils/artifactView.js 那一份：各页各留一张清单时，
  // 同一份产物在两个页面会给出不同按钮集，用户只会当成随机行为。
  const cp = read('src/views/ChatPage.vue')
  const mr = read('src/h5/MobileMsgRow.vue')
  const rs = read('src/h5/MobileRefSheet.vue')
  const ap = read('src/views/ArtifactsPage.vue')
  for (const [name, src] of [['ChatPage', cp], ['MobileMsgRow', mr], ['MobileRefSheet', rs], ['ArtifactsPage', ap]]) {
    check(/canPreview\(/.test(src), `${name} 用 artifactView 那份可预览判据`)
    check(/data-pv(?![\w-])/.test(src), `${name} 的预览锚点接在预览台上（漏 data-pv＝退回纯开新标签，症状与「这版没做预览台」同形）`)
    check(/target="_blank" rel="noopener"/.test(src), `${name} 保留 href 真签名地址（Ctrl/中键仍可开成真正视口）`)
  }
  check(!/NO_PREVIEW|DOWNLOAD_ONLY|const previewable\s*=|const PREVIEW_EXTS\s*=/
    .test(cp + mr + rs + ap + pjRaw), '六个面都没有第二份「哪些类型怎么预览」清单')
  // 委托与样式只在入口装一次：漏了 main.js 那行 side-effect import，六个面的按钮全部退化成
  // 「只会开新标签」，页面不报错、构建也过——正是上一轮「某页忘了挂委托」那类静默故障的放大版。
  // 判据跑在剥注释后的源码上：只把 import 注释掉同样会静默退化，拿原文判就测不出那种坏法。
  const mainSrc = stripComments(read('src/main.js'))
  check(/import '\.\/utils\/previewStage'/.test(mainSrc), '入口装了预览台的 document 级委托')
  check(/import '\.\/previewStage\.css'/.test(mainSrc), '入口装了预览台样式（漏了会拿到一堆无样式裸元素）')
  // 前端那张表说「能预览」的每一类，服务端必须真发得出内容类型；对不上就是给用户一颗点了掉下载的按钮。
  const avSrc = read('src/utils/artifactView.js')
  const ptSrc = read('../src/main/java/com/wenqu/ai/util/PreviewTypes.java')
  const clientExts = new Set([...avSrc.matchAll(/([a-z0-9]+): PV\.(PAGE|IMAGE|PDF|MD|TABLE|TEXT)/g)].map(m => m[1]))
  const serverExts = new Set(['PAGE_EXTS', 'IMAGE_EXTS', 'READABLE_EXTS', 'PDF_EXTS'].flatMap(s => {
    const m = ptSrc.match(new RegExp(s + '[^=]*=\\s*Set\\.of\\(([^(]*)\\)'))
    if (!m) throw new Error('PreviewTypes 里没找到 ' + s + '——对账判据失效，先去补再看这条为什么红')
    return [...m[1].matchAll(/"([^"]+)"/g)].map(x => x[1])
  }))
  const onlyClient = [...clientExts].filter(x => !serverExts.has(x))
  const onlyServer = [...serverExts].filter(x => !clientExts.has(x))
  check(clientExts.size > 0 && serverExts.size > 0 && !onlyClient.length && !onlyServer.length,
    '前后端两份预览扩展名逐条对账', `前端多 ${onlyClient.join('/') || '无'} / 服务端多 ${onlyServer.join('/') || '无'}`)
  // 取原文的端点必须按 id / 工作区+路径入参：给路径或 URL 等于开放「读任意文件」，与换签那条同口径
  const artCtrl = stripComments(read('../src/main/java/com/wenqu/ai/controller/ArtifactController.java'))
  check(/@GetMapping\("\/\{id\}\/content"\)/.test(artCtrl) && /readContent\(RequestUser\.uid\(\), id\)/.test(artCtrl),
    '产物原文按 id + 登录 uid 取（不接收调用方给的路径）')
  const pjCtrlCode = stripComments(read('../src/main/java/com/wenqu/ai/controller/ProjectController.java'))
  check(/"\/\{id\}\/file\/content"/.test(pjCtrlCode) && /readText\(RequestUser\.uid\(\), id, path\)/.test(pjCtrlCode),
    '工作区原文按工作区 id + 登录 uid 取（越界与归属在 resolveDownload 内判）')
  // 行本身不再是「点整行=下载」的锚：预览与下载必须各自成钮，否则手机上根本点不到预览
  // （阳性对照：拿改动前那份 `<a v-for=… class="artifact-item" … :download=…>` 喂这两条，两条都得红）
  check(/<div v-for="\(a, ai\) in m\.artifacts" :key="ai" class="artifact-item">/.test(cp),
    '气泡产物行是容器不是下载锚（预览/下载各有入口）')
  check(/<div v-for="\(a, pi\) in sessionArtifacts" :key="pi" class="rp-art">/.test(cp),
    '右栏「产物」行同上')
}

// ---- ⑬ 目标摘要条：一条常驻条 + 一处详情面，词汇与判据只有一份 ----
// 背景：目标模式抄的是「常驻在输入框上方那一条」的形状（状态词三档、图标动作、就地编辑），
// 而它的状态词/显隐判据一度在 PC、移动壳、目标卡、「状态与来源」sheet 各写一份——
// 同一条目标在四个地方说成「预算用满 / 已结束 / 受阻」三种话。这里的断言只认结构，不认文案。
{
  const proj = read('src/chat/projections.js')
  const pc = read('src/views/ChatPage.vue')
  const h5 = read('src/h5/MobileChatPage.vue')
  const row = read('src/h5/MobileMsgRow.vue')
  const ref = read('src/h5/MobileRefSheet.vue')
  const card = read('src/components/GoalCard.vue')
  const eng = stripComments(read('src/chat/useChatEngine.js'))

  // 定义处：三档词、显隐、动作可用性都只在 projections 算一次
  check(/const goalPhraseOf = /.test(proj) && /const goalBarVisibleOf = /.test(proj)
    && /const goalCanEditOf = /.test(proj), 'projections 是三档状态词与条子判据的定义处')
  for (const [label, s] of [['PC', pc], ['移动壳', h5], ['目标卡', card], ['状态与来源', ref]]) {
    check(!/const goalPhrase =/.test(s) && !/const goalStateLabel =/.test(s)
      && !/const goalBarVisible =/.test(s) && !/const goalTerminal =/.test(s),
      `${label} 不自算状态词与显隐判据（归 projections 那一份）`)
  }
  check(/const goalStateLabelOf = /.test(proj), '详情面的状态词与条子同源')

  // 两壳都画这条，且绑的是同一个判据
  check(/v-if="goalBarVisible"[^>]*data-goal-bar/.test(pc), 'PC 摘要条绑 goalBarVisible 且带 data-goal-bar')
  check(/v-if="goalBarVisible"[^>]*data-goal-bar/.test(h5), '移动壳有同一条摘要条（不只 PC）')

  // 动作只有一个入口：暂停/继续/编辑/清除都走条子，别处不许再摆一套
  const pcCode = stripComments(pc)
  const h5Code = stripComments(h5)
  const refCode = stripComments(ref)
  check(/runGoalAction\('pause'\)/.test(pcCode) && /runGoalAction\('pause'\)/.test(h5Code),
    '两壳的暂停都走 runGoalAction（不各自调 goalAct）')
  check(!/goalAct\('pause'\)|goalAct\('resume'\)/.test(refCode),
    '「状态与来源」sheet 不再重复摆暂停/继续')
  check(/@click="startGoalInlineEdit"/.test(pcCode) && /@click="startGoalInlineEdit"/.test(h5Code),
    '两壳的编辑键都开就地编辑态（同一批字段）')
  check(!/v-model:open="goalEditOpen"/.test(pc), 'PC 的改订弹窗已退役（编辑只在条子里长开）')

  // 图标 kebab 名与显式 import 必须成对（映射不到就什么都不画，界面看着像「按钮没出现」）
  check(/<pause-circle-outlined/.test(pc) && /PauseCircleOutlined/.test(pc),
    'PC 摘要条的暂停图标已显式 import')
  check(/<play-circle-outlined/.test(h5) && /PlayCircleOutlined/.test(h5),
    '移动壳摘要条的继续图标已显式 import')

  // 命令回声：库里那列、历史那份投影、两壳那枚标记，三处必须同形
  check(/`goal_command`/.test(read('../src/main/resources/schema.sql')),
    'schema.sql 有 goal_command 列（缺列时补列靠它，前端才会读到）')
  check(/private String goalCommand/.test(read('../src/main/java/com/wenqu/ai/model/Message.java')),
    'Message 实体带 goalCommand 字段')
  check(/map\.put\("goalCommand"/.test(read('../src/main/java/com/wenqu/ai/service/SessionService.java')),
    '历史接口把 goalCommand 带出（不落这行就永远读不到）')
  check(/goalCommand: typeof m\.goalCommand === 'string'/.test(eng), '引擎把库里的 goalCommand 映射进气泡')
  // 回声那枚胶囊：形状两端同形、状态词同源、界面上不再露命令字面量
  // 模板切片要按「第一个 <template 到最后一个 </template>」取：ChatThread 是 script 在前、
  // template 在后，按「文件头到 <script」切会得到空串——空串让下面三条断言全部假绿
  const thread = read('src/chat/ChatThread.vue')
  const tplOf = s => s.slice(s.indexOf('<template'), s.lastIndexOf('</template>') + 11)
  const pillOf = s => { const x = /class="cmd-pill"[\s\S]*?<\/div>/.exec(s); return x ? x[0] : '' }
  const threadTpl = tplOf(thread)
  const rowTpl = tplOf(row)
  const threadPill = pillOf(threadTpl)
  const rowPill = pillOf(rowTpl)
  check(threadPill.length > 40 && rowPill.length > 40 && /class="cmd-pill"/.test(threadPill) && /class="cmd-pill"/.test(rowPill),
    '两壳都在气泡上方画那枚身份胶囊（不再压在正文那一行里）')
  check(/goalEchoStateOf/.test(thread) && /goalEchoStateOf/.test(row),
    '两壳的状态词都走 goalEchoStateOf（旧目标的回声不替它编一个状态）')
  check(!/进行中|已暂停|已完成|已结束|核验中|待确认/.test(threadPill) && !/进行中|已暂停|已完成|已结束|核验中|待确认/.test(rowPill),
    '胶囊那段不自己写状态词（词汇只有 goalStateLabelOf 那一份）')
  check(!/>\s*\/goal\s*</.test(threadTpl) && !/>\s*\/goal\s*</.test(rowTpl),
    '/goal 字面量不再露出（收进 goalEchoTipOf 那行悬浮，界面上只留人话）')
  check(/goalPending: true/.test(eng) && /!m\.goalCommand && !m\.goalPending/.test(pcCode)
      && /!props\.m\.goalCommand && !props\.m\.goalPending/.test(row),
    '乐观气泡那几秒也算回声：goalId 未回时不露复制/编辑重发（否则能把目标指令重跑成普通提问）')
  check(/!m\.goalCommand/.test(pcCode) && /!props\.m\.goalCommand/.test(row),
    '回声行撤掉普通消息动作（指令不是一等回答的提问）')
  check(/v-if="m\.role === 'user'" class="msg-edit-row"/.test(pcCode) && /v-if="showActions" class="u-actions"/.test(row),
    '回声行仍挂动作行容器：撤的只是复制/编辑键，时间要看得见（整行撤掉会把「何时定的目标」一起抹掉）')
  check(/echoGoalCommand\(/.test(read('../src/main/java/com/wenqu/ai/service/GoalService.java')),
    '后端建目标时落那句回声（只在前端 push 会刷新即丢）')
}

// ---- ⑭ 个人设置「分享管理」行：窄屏必须纵向堆叠（一行两栏在手机上破版） ----
// 背景：这条卡片是「左正文 + 右操作」的一行两栏，操作区 flex:none 且按内容定宽（五个链接钮
// 约 300px）。390px 视口上正文只剩 2px —— 实测标题一字一行竖排、整卡 599px 高、链接框里
// 只画出一个「h」。原窄屏块里那句 flex-wrap 是空操作（基线规则本来就 wrap），所以静态看着
// "有适配"，真机全是垃圾。断言只认这三条结构，PC 侧（媒体查询外）必须仍是横排。
{
  // 只剥 <style> 之后那份：本文件脚本区有一行注释写了「memory 斜杠星点 md」这种字面量，
  // 其中的注释起始符会让块注释剥离一路吞到下一个结束符（约 570 行样式凭空消失，
  // 断言随之误报——check-h5 的老坑，别再踩）。
  const raw = read('src/views/ProfilePage.vue')
  const s = stripComments(raw.slice(raw.indexOf('<style')))
  const narrow = s.slice(s.indexOf('@media (max-width: 768px)'))
  check(/\.shm-item\s*\{[^}]*flex-direction:\s*column/.test(narrow), '分享卡窄屏改纵向堆叠（正文不再被压成 2px）')
  check(/\.shm-acts\s*\{[^}]*max-width:\s*none/.test(narrow), '窄屏操作区不再按内容定宽（max-width 已放开）')
  check(/\.shm-acts \.app-link-btn\s*\{[^}]*min-height:\s*34px/.test(narrow), '窄屏链接按钮 34px 热区下限')
  // PC 侧基线：横排 + 操作区靠右定宽，窄屏改动不许把它一起改掉
  // （只看 .shm-item 那一条规则本体：.shm-list 也是 column，拿整段基线区判会误报）
  const itemBase = (s.match(/\.shm-item\s*\{[^}]*\}/) || [''])[0]
  check(/display:\s*flex/.test(itemBase) && !/flex-direction/.test(itemBase),
    'PC 基线仍是横排（窄屏只改媒体查询里那份）')
}

process.exit(bad ? 1 : 0)
