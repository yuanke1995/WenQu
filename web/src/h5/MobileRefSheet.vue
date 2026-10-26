<template>
  <!-- 运行状态 sheet：引用来源（按文档分组）+ 本轮/会话用量 + 本会话产物。
       PC 的右栏在移动端收纳到这里的底部 sheet；内容都来自引擎派生数据，不另算口径。 -->
  <BottomSheet :open="open" title="状态与来源" max-height="78dvh" @close="$emit('close')">
    <div class="rs">
      <!-- ---- 本轮过程总览 ----
           气泡里逐步的工具卡一直都在（MobileMsgRow 的过程簇），这一张补的是另一个视角：
           不用上下滚就能看完「总共走了哪几步、哪一步慢、现在卡在做什么」。展示口径整份复用
           projections（toolCallsView 并重复卡、toolLabel 中文名、toolDuration 耗时、executedCount
           只数真执行过的）——与 PC 右栏那张卡同一把尺，两处不许各写一套。
           「查看完整过程」不在这儿放：顶栏那颗时钟图标就是它的入口，一个动作只留一个入口。 -->
      <template v-if="procSteps.length">
        <div class="rs-section">本轮过程<span class="rs-n">{{ executedCount(procSteps) }} 步{{ procRunning ? ' · 跑轮中' : '' }}</span></div>
        <div v-if="procRunning" class="rs-phase"><i class="rs-phase-dot" />{{ procPhase }}</div>
        <div v-for="(t, ti) in procSteps" :key="ti" class="rs-proc"
             :class="{ run: t.status === 'start' && !t.denied, err: t.status === 'error', off: t.denied }">
          <span class="rs-proc-ic">
            <loading-outlined v-if="t.status === 'start' && !t.denied" spin />
            <check-outlined v-else-if="t.status === 'done'" />
            <close-circle-outlined v-else-if="t.status === 'error'" />
            <pause-outlined v-else />
          </span>
          <span class="rs-proc-name" :title="toolDesc(t.name, t.hint)">{{ toolLabel(t.name, t.label) }}</span>
          <!-- 被挡下的不报耗时：那一步根本没执行，给个数字等于说它跑过了 -->
          <span v-if="t.denied" class="rs-proc-dur off">被挡下</span>
          <span v-else-if="t.status === 'start'" class="rs-proc-dur run">进行中</span>
          <span v-else-if="t.elapsedMs != null" class="rs-proc-dur">{{ toolDuration(Number(t.elapsedMs) || 0) }}</span>
        </div>
      </template>

      <!-- ---- 引用来源 ---- -->
      <div class="rs-section">引用来源<span v-if="sources.length" class="rs-n">{{ sources.length }} 段</span></div>
      <div v-if="!groups.length" class="rs-empty">本轮还没有引用来源</div>
      <div v-for="g in groups" :key="g.key" class="rs-group">
        <button class="rs-group-head" type="button" @click="toggleSrc(g)">
          <file-text-outlined class="rs-ic" />
          <span class="rs-group-name">{{ g.fileName }}</span>
          <span class="rs-n">{{ g.items.length }}</span>
          <caret-right-outlined class="rs-caret" :class="{ open: srcOpenOf(g) }" />
        </button>
        <div v-show="srcOpenOf(g)" class="rs-group-body">
          <button v-for="s in g.items" :key="s.knowledgeId || s.ref" class="rs-item" type="button" @click="$emit('source', s)">
            <span class="rs-ref">[{{ s.ref }}]</span>
            <span class="rs-item-main">
              <span class="rs-item-title">{{ s.title || s.snippet || '（无标题）' }}</span>
              <span v-if="s.snippet" class="rs-snip">{{ s.snippet }}</span>
            </span>
            <span v-if="scoreOf(s)" class="rs-score">{{ scoreOf(s) }}</span>
          </button>
        </div>
      </div>

      <!-- ---- 本轮用量 ---- 三个数各起各的名：占用 ≠ 输出 ≠ 累计送入（后者含工具往返重发，别拿去跟前两个相加） ---- -->
      <div class="rs-section">本轮用量</div>
      <div v-if="!tokens" class="rs-empty">本轮还没有用量记录</div>
      <template v-else>
        <div class="rs-kv"><span>上下文占用</span><b>{{ usageCtx }}<template v-if="cap.window > 0"> / {{ cap.window }}（{{ cap.pct }}%）</template></b></div>
        <div class="rs-kv"><span>输出</span><b>{{ tokens.output ?? '—' }} tokens<template v-if="!tokens.outputIsReal">（估算）</template></b></div>
        <div v-if="tokens.prompt > 0" class="rs-kv"><span>本轮累计送入</span><b>{{ fmtTokens(tokens.prompt) }} tokens</b></div>
        <div v-if="tokens.hits > 0" class="rs-kv"><span>填入上下文</span><b>{{ tokens.hits }} 段</b></div>
        <div v-if="tokens.cached > 0" class="rs-kv"><span>缓存命中</span><b>{{ tokens.cached }} tokens</b></div>
      </template>

      <!-- ---- 上下文容量（PC 是工具栏圆环 + 悬浮容量卡；手机收进这里）----
           门控与标题同 PC：窗口未知时退化为纯构成列表（只给分类，不编造分母） -->
      <div class="rs-section">上下文容量<span v-if="cap.window > 0" class="rs-n">占窗口 {{ cap.pct }}%</span></div>
      <template v-if="cap.window > 0">
        <div class="cap-bar">
          <span v-for="r in cap.rows" :key="r.key" class="cap-seg" :style="{ width: cap.segW(r), background: r.color }" />
        </div>
        <div class="cap-meta">
          {{ fmtWindow(cap.used) }} / {{ fmtWindow(cap.window) }}
        </div>
      </template>
      <div v-for="r in cap.rows" :key="r.key" class="rs-kv">
        <span class="cap-label"><i class="cap-dot" :style="{ background: r.color }" />{{ r.label }}</span>
        <b>{{ fmtTokens(r.tokens) }}</b>
      </div>
      <div v-if="!cap.window && !cap.rows.length" class="rs-empty">本轮还没有容量明细（随用量事件下发）</div>

      <!-- ---- 会话累计 ---- -->
      <div class="rs-section">本会话累计</div>
      <div class="rs-kv"><span>已完成轮次</span><b>{{ sessionTokens.rounds }}</b></div>
      <div v-if="sessionTokens.rounds" class="rs-kv"><span>累计送入</span><b>{{ fmtTokens(sessionTokens.prompt) }} tokens</b></div>
      <div class="rs-kv"><span>检索轮次 / 引用</span><b>{{ sessionRetrieval.rounds }} 轮 · {{ sessionRetrieval.refs }} 段</b></div>

      <!-- ---- 产物 ---- -->
      <template v-if="artifacts.length">
        <div class="rs-section">本会话产物<span class="rs-n">{{ artifacts.length }}</span></div>
        <div v-for="(a, i) in artifacts" :key="i" class="rs-art">
          <file-text-outlined class="rs-ic" />
          <span class="rs-art-name">{{ a.filename }}</span>
          <span class="rs-art-acts">
            <a v-if="canPreview(a)" class="rs-art-act" :href="resolveImg(a.url)"
               target="_blank" rel="noopener" data-pv
               :data-pv-name="a.filename" :data-pv-ext="artifactExt(a)"
               :data-pv-size="a.size" :data-pv-id="a.id"
               title="预览"><eye-outlined />预览</a>
            <a class="rs-art-act" :href="resolveImg(a.url)" :download="a.filename"
               title="下载"><download-outlined />下载</a>
          </span>
        </div>
      </template>

      <!-- ---- 沙盒文件：模型写进沙盒、却没交付成产物的文件 ----
           露脸判据与 PC 右栏那张卡同一条：有文件 / 读失败 / 正在读。读不到必须说出来并给重试——
           静默成空列表会被读成「我让它做的文件没了」。折树也吃引擎那一份：接口给的是摊平的一维
           清单，整条路径摊在一行时名字全被截断，认不出哪份是成品。 -->
      <template v-if="sandboxFiles.length || sandboxErr || sandboxLoading">
        <div class="rs-section">沙盒文件<span v-if="sandboxCount" class="rs-n">{{ sandboxCount }} 个{{ sandboxBytes ? ' · ' + sbSize(sandboxBytes) : '' }}</span></div>
        <div v-if="sandboxLoading" class="rs-empty">正在读取沙盒目录…</div>
        <div v-else-if="sandboxErr" class="rs-sb-err">
          <span>{{ sandboxErr }}</span>
          <button type="button" class="rs-sb-retry" @click="loadSandboxFiles">重试</button>
        </div>
        <template v-for="row in sandboxRows" :key="row.key">
          <button v-if="row.type === 'dir'" type="button" class="rs-sb-dir"
                  @click="toggleSandboxDir(row.dir.path)"
                  :title="(row.open ? '收起 ' : '展开 ') + row.dir.path + '/（' + row.dir.count + ' 个文件）'">
            <span v-for="i in row.depth" :key="i" class="rs-sb-ind" />
            <caret-right-outlined class="rs-caret" :class="{ open: row.open }" />
            <folder-open-outlined v-if="row.open" class="rs-ic" />
            <folder-outlined v-else class="rs-ic" />
            <span class="rs-sb-name">{{ row.dir.name }}/</span>
            <span class="rs-n">{{ row.dir.count }} 个 · {{ sbSize(row.dir.size) }}</span>
          </button>
          <div v-else class="rs-art rs-sb-file">
            <span v-for="i in row.depth" :key="i" class="rs-sb-ind" />
            <span class="rs-art-name" :title="row.file.path">{{ row.file.name }}</span>
            <span class="rs-n rs-sb-size">{{ sbSize(row.file.size) }}</span>
            <span class="rs-art-acts">
              <a v-if="row.file.viewUrl" class="rs-art-act" :href="resolveImg(row.file.viewUrl)"
                 target="_blank" rel="noopener" title="在新标签页打开"><eye-outlined /></a>
              <button type="button" class="rs-art-act" :title="'下载 ' + row.file.name"
                      @click="sandboxDownload(row.file)"><download-outlined /></button>
              <button v-if="!sandboxTaken.has(row.file.path)" type="button" class="rs-art-act"
                      title="存为产物（进「我的产物」，可预览可下载）"
                      @click="sandboxTake(row.file)"><save-outlined /></button>
              <check-outlined v-else class="rs-art-act rs-sb-taken" title="已存为产物" />
            </span>
          </div>
        </template>
        <div v-if="sandboxRest" class="rs-act-tip">目录里另有 {{ sandboxRest }} 个文件没列出（这里只列最新 {{ sandboxFiles.length }} 个）</div>
      </template>

      <!-- ---- 目标（与 PC 摘要条同一份 sessionGoal，同一个 GoalCard 组件；PC 右栏不单列目标卡） ----
           卡片只读：状态、轮次、目标原文与卡住的原因。暂停/继续/改原文/删除都只在输入框上方
           那条摘要条上（一个动作只留一个入口），这里不再摆验收/收尾/另起目标那一套。 -->
      <template v-if="sessionGoal">
        <div class="rs-section">目标</div>
        <GoalCard :card="sessionGoal" />
      </template>

      <!-- ---- 这段对话：分享 / 导出 ----
           刻意排在最后而非最前：生成外发链接是不可逆的外泄动作，误触成本远高于"多滚一下"。 -->
      <div class="rs-section">这段对话</div>
      <div class="rs-act-row">
        <!-- 分享展开成面板时不收走导出：两个动作互相独立，替换掉会让"导出"藏进二级深处 -->
        <button class="rs-act" type="button" @click="toggleShare">
          <share-alt-outlined class="rs-ic" /><span>{{ shareOpen ? '收起分享' : '分享只读链接' }}</span>
        </button>
        <button class="rs-act" type="button" :disabled="exporting" @click="doExport">
          <file-markdown-outlined class="rs-ic" /><span>{{ exporting ? '正在导出…' : '导出 Markdown' }}</span>
        </button>
      </div>
      <!-- 双通道：iOS Safari 与微信内置浏览器对 <a download> 的支持不一致（微信里基本不落盘），
             所以"复制全文"不是降级方案，而是这条路上必须有的一条出口——两条都显式给出，不静默切换。 -->
        <template v-if="built">
          <div class="rs-act-row">
            <button class="rs-act" type="button" @click="saveBuilt"><download-outlined class="rs-ic" /><span>保存文件</span></button>
            <button class="rs-act" type="button" @click="copyBuilt"><copy-outlined class="rs-ic" /><span>复制全文</span></button>
          </div>
          <div class="rs-act-tip">全文 {{ builtSize }} · {{ built.fileName }}；保存文件无反应时用「复制全文」粘贴到备忘录</div>
        </template>
      <template v-if="shareOpen">
        <div v-if="shareBusy" class="rs-empty">正在读取…</div>
        <template v-else-if="shareEnabled">
          <input class="rs-link" :value="shareUrl" readonly @focus="$event.target.select()" />
          <div class="rs-act-row">
            <button class="rs-act" type="button" @click="copyShareLink"><copy-outlined class="rs-ic" /><span>复制链接</span></button>
            <button class="rs-act" type="button" @click="regenShare"><swap-outlined class="rs-ic" /><span>换新链接</span></button>
          </div>
          <div class="rs-act-row">
            <button class="rs-act danger" type="button" @click="stopShare"><stop-outlined class="rs-ic" /><span>停止分享</span></button>
          </div>
          <div class="rs-act-tip">
            拿到链接的人只能查看，不能继续提问<template v-if="visitCount"> · 已被访问 {{ visitCount }} 次</template>
          </div>
        </template>
        <button v-else class="rs-act wide" type="button" @click="genShare">
          <share-alt-outlined class="rs-ic" /><span>生成分享链接</span>
        </button>
        <div class="rs-act-tip">链接展示的是会话最新内容而非快照——后续聊到敏感内容时，分享页也会跟着变。</div>
      </template>
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed, inject, reactive, ref, watch } from 'vue'
import { CaretRightOutlined, FileTextOutlined, DownloadOutlined, ShareAltOutlined, FileMarkdownOutlined, CopyOutlined, SwapOutlined, StopOutlined, EyeOutlined, FolderOutlined, FolderOpenOutlined, SaveOutlined, CheckOutlined, LoadingOutlined, CloseCircleOutlined, PauseOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import BottomSheet from './BottomSheet.vue'
import GoalCard from '../components/GoalCard.vue'
import { fmtTokens } from '../utils/token'
import { fmtSourceScore, fmtWindow, usageCtxTokens,
         toolLabel, toolDesc, toolCallsView, executedCount, toolDuration } from '../chat/projections'
// 产物地址必须过 resolveImg：后端给的是 /ai/artifacts/**，不经媒体前缀在开发态会落到 SPA 回退（下载到 index.html）
import { resolveImg } from '../utils/markdown'
import { canPreview, artifactExt } from '../utils/artifactView'
import { buildSessionMd, saveMd } from '../views/exportMd'
import { copyText } from '../utils/clipboard'
import { useSessionShare } from '../views/shareSession'

const props = defineProps({ open: { type: Boolean, default: false } })
defineEmits(['close', 'source'])

const engine = inject('wqChat')
// 目标模式：与 PC 同一份 sessionGoal（会话级一份，横跨多轮），卡片本体共用 GoalCard——状态词、
// 轮次与「为什么停在这儿」都由它按 projections 那一份算，本 sheet 只负责把卡片摆上去。
const { groupedSources, lastTokens, lastSources, sessionTokens, sessionRetrieval, sessionArtifacts,
  currentSessionId, currentSessionTitle, messages, ctxTokens, modelIndex, effectiveModel,
  sessionGoal,
  // 沙盒目录：取数、判据、折树、下载与存为产物全吃引擎那一份（与 PC 右栏那张卡同源，
  // 2026-10-25 从 PC 壳下沉），本 sheet 只负责把它摆成触屏形状
  sandboxFiles, sandboxRows, sandboxCount, sandboxBytes, sandboxRest,
  sandboxErr, sandboxLoading, sandboxTaken,
  loadSandboxFiles, toggleSandboxDir, sandboxDownload, sandboxTake, sbSize,
  // 本轮过程总览的数据源：与 PC 右栏那张卡同一个对象（跑轮时它就是正在流的那条，结束后是上一轮）
  panelAi } = engine

// 容量构成：与 PC 的 ctxCapData 同一算法（窗口取本轮 tokens.window，回落模型登记窗口；
// 用量取 prompt，无则 context；分类占窗口比例撑填充段）。分类表是 PC 视图层的同款副本——
// 纯呈现映射，两端各自持有（抽到共用单元会把两端一起拖住）。
// 分类按 AgentScope 框架的消息结构（MsgRole + 内容块），与后端 agentx FrameworkCtxParts 同键同序。
const CTX_PART_META = [
  { key: 'system', label: '系统提示词', color: '#722ed1' },
  { key: 'history', label: '历史对话', color: '#1677ff' },
  { key: 'input', label: '本轮提问', color: '#8c8c8c' },
  { key: 'retrieval', label: '检索资料', color: '#52c41a' },
  { key: 'toolOutput', label: '工具结果', color: '#fa8c16' },
  { key: 'thinking', label: '思考内容', color: '#13c2c2' },
  { key: 'toolSchema', label: '工具声明', color: '#eb2f96' }
]
const cap = computed(() => {
  const t = ctxTokens.value
  // 窗口只认 tokens.window（框架按 maxContextTokens 下发），回落模型登记值；
  // 旧的 tokens.budget 回落已删——那是手写引擎时代的检索配额，框架引擎下不存在
  const win = (t && Number(t.window)) || Number(modelIndex.value[effectiveModel.value]?.contextWindow) || 0
  // 用量与 PC 侧同口径：分母取 context（本轮结束时的上下文占用）而非 prompt
  // （各轮输入之和的账单口径）
  const used = t ? (Number(t.context) || Number(t.prompt) || 0) : 0
  const parts = (t && t.parts && typeof t.parts === 'object') ? t.parts : null
  const rows = parts
    ? CTX_PART_META.filter(x => Number(parts[x.key]) > 0).map(x => ({ ...x, tokens: Number(parts[x.key]) }))
    : []
  return {
    window: win, used, rows,
    pct: win > 0 ? Math.min(100, Math.round(used / win * 1000) / 10) : 0,
    segW: r => win > 0 ? Math.min(100, r.tokens / win * 100).toFixed(2) + '%' : '0%'
  }
})

// 分组展开态：PC 侧这份状态在视图层（srcOpen/srcOpenOf/toggleSrc 未进引擎），移动壳按同口径本地实现
//（key → 开/合，默认展开；存 reactive 容器而非 computed 内，流式更新 sources 后已收起的组不弹回）
const srcOpen = reactive({})
const srcOpenOf = g => (srcOpen[g.key] !== undefined ? srcOpen[g.key] : true)
const toggleSrc = g => { srcOpen[g.key] = !srcOpenOf(g) }

const groups = computed(() => groupedSources.value)
const sources = computed(() => lastSources.value || [])
const tokens = computed(() => lastTokens.value)
// 上下文占用（给足原值，不缩成 k）：与下面容量条同一个分子
const usageCtx = computed(() => usageCtxTokens(tokens.value) || '—')
const artifacts = computed(() => sessionArtifacts.value)

// ==================== 本轮过程总览 ====================
// 判据与 PC 右栏那张卡（RightProcessCard）逐条对齐：并重复卡走 toolCallsView、步数只数真执行
// 过的走 executedCount；「在跑的那一步」要显式给状态词，因为 start 帧带的是 elapsedMs=0，
// 按耗时先判会给它报「0ms」，而并发派遣一屏三个 0ms 读起来像三步都跑完了。
const procSteps = computed(() => toolCallsView(panelAi.value && panelAi.value.toolCalls))
const procRunning = computed(() => !!panelAi.value && panelAi.value.loading === true)
const procPhase = computed(() => {
  const m = panelAi.value
  if (!m) return ''
  if (procSteps.value.some(t => t.status === 'start' && !t.denied)) return '执行工具中'
  if (m.thinkLoading || (m.thinking && !m.content)) return '正在思考'
  return '正在输出'
})
const scoreOf = s => (s.rerankScore != null || s.score != null) ? fmtSourceScore(s) : ''

// ==================== 这段对话：分享只读链接 ====================
// 状态机与 PC 标题栏弹窗是同一份（src/views/shareSession.js）：同一会话只有一条分享、
// 换新链接会使旧链接立即失效——两端口径必须一致，否则用户手里的链接就变成薛定谔的有效。
// 裂解在这里选中展开时才装载：多数人进这个 sheet 是看来源，没必要每次都拉一遍分享状态。
const shareOpen = ref(false)
const {
  loading: shareBusy, info: shareInfo, url: shareUrl,
  load: loadShare, enable: enableShare, disable: stopShareApi, copyLink: copyShareLink, reset: resetShare
} = useSessionShare({ sessionId: () => currentSessionId.value })
const shareEnabled = computed(() => !!shareInfo.value.enabled)
const visitCount = computed(() => shareInfo.value.visitCount || 0)

const toggleShare = async () => {
  if (shareOpen.value) { shareOpen.value = false; return }
  if (!messages.value.length) { message.info('当前会话还没有内容可分享'); return }
  shareOpen.value = true
  await loadShare()
}
const genShare = () => enableShare(false)
const regenShare = () => enableShare(true)
const stopShare = async () => { if (await stopShareApi()) shareOpen.value = false }

// ==================== 这段对话：导出 Markdown ====================
// 拼装与 PC 完全共用 buildSessionMd；差别只在落盘这一下——手机端"导出"的真实诉求是
// 把内容带走，files 未必打得开，故ashboard同时给出「保存文件」与「复制全文」两个出口。
const exporting = ref(false)
const built = ref(null)
const builtSize = computed(() => {
  if (!built.value) return ''
  const n = new Blob([built.value.md]).size
  return n > 1024 * 1024 ? (n / 1024 / 1024).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'
})
const doExport = async () => {
  if (exporting.value) return
  if (!messages.value.length) { message.info('当前会话还没有内容可导出'); return }
  exporting.value = true
  try {
    built.value = await buildSessionMd(currentSessionId.value, currentSessionTitle.value)
  } catch (e) {
    message.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}
const saveBuilt = () => saveMd(built.value)
const copyBuilt = () => copyText(built.value?.md, '全文已复制，可粘贴到备忘录')

// 换会话：清掉上一个会话的导出结果与分享面板，否则"保存文件"会落出别人的会话
watch(currentSessionId, () => { built.value = null; shareOpen.value = false; resetShare() })

// 每次打开这张 sheet 都重扫一次沙盒目录：sheet 关着的时候引擎只在「一轮收尾 / 切会话」那两拍拉，
// 而用户点开「沙盒文件」恰恰是要看最新做出来的东西。常驻的 PC 右栏没有这个问题（它一直在屏上，
// 收尾那一拍已经拉过）。
watch(() => props.open, v => { if (v) loadSandboxFiles() })
</script>

<style scoped>
.rs { display: flex; flex-direction: column; gap: 2px; }
.rs-section { font-size: 12px; color: var(--app-text3); padding: 12px 2px 4px; display: flex; align-items: center; gap: 6px; }
.rs-n { font-size: 11px; color: var(--app-text3); }
.rs-empty { font-size: 13px; color: var(--app-text3); padding: 6px 2px 10px; }
.rs-group { border: 1px solid var(--app-border); border-radius: 10px; margin-bottom: 6px; overflow: hidden; }
.rs-group-head { width: 100%; display: flex; align-items: center; gap: 8px; min-height: 46px; padding: 8px 12px; border: none; background: var(--app-panel-2); color: var(--app-text); font-size: 13px; text-align: left; touch-action: manipulation; }
.rs-group-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rs-caret { color: var(--app-text3); transition: transform .18s; }
.rs-caret.open { transform: rotate(90deg); }
@media (prefers-reduced-motion: reduce) { .rs-caret { transition: none; } }
.rs-group-body { display: flex; flex-direction: column; }
.rs-item { display: flex; gap: 8px; align-items: flex-start; padding: 10px 12px; border: none; border-top: 1px dashed var(--app-border); background: transparent; color: var(--app-text2); text-align: left; font-size: 13px; touch-action: manipulation; }
.rs-ref { color: var(--app-accent); font-weight: 600; flex: none; }
.rs-item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.rs-item-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rs-snip { color: var(--app-text3); font-size: 12px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.rs-score { color: var(--app-accent); font-size: 12px; flex: none; }
.rs-ic { color: var(--app-text3); flex: none; }
.rs-kv { display: flex; justify-content: space-between; font-size: 13px; color: var(--app-text2); padding: 7px 2px; border-bottom: 1px dashed var(--app-border); }
.rs-kv b { color: var(--app-text); font-weight: 500; }
/* 容量填充条：轨道 8px 圆角，段宽=该分类占窗口的比例（分段之和即用量，天然与用量口径一致） */
.cap-bar { display: flex; height: 8px; border-radius: 4px; overflow: hidden; background: var(--app-panel-2); border: 1px solid var(--app-border); }
.cap-seg { height: 100%; }
.cap-meta { font-size: 12px; color: var(--app-text3); padding: 6px 2px 2px; }
.cap-label { display: inline-flex; align-items: center; gap: 6px; }
.cap-dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.rs-art { display: flex; align-items: center; gap: 8px; padding: 4px 12px; border: 1px solid var(--app-border); border-radius: 10px; margin-bottom: 6px; color: var(--app-text2); font-size: 13px; }
.rs-art-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rs-art-acts { flex: none; display: flex; align-items: center; }
/* 触屏热区：与消息流里的产物行同一口径（预览/下载各自 ≥44px） */
.rs-art-act { display: inline-flex; align-items: center; gap: 3px; min-height: 44px; padding: 0 6px; color: var(--app-text3); text-decoration: none; font-size: 12px; }
/* 「这段对话」的操作块：44px 触摸热区，危险动作（停止分享）单独占一行不与常用动作挤在一起 */
.rs-act-row { display: flex; gap: 8px; flex-wrap: wrap; }
.rs-act {
  flex: 1; min-width: 0; min-height: 46px; padding: 0 12px;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel);
  color: var(--app-text2); font-size: 14px; touch-action: manipulation;
}
.rs-act:active { background: var(--app-accent-weak); color: var(--app-accent); }
.rs-act:disabled { opacity: .55; }
.rs-act.wide { width: 100%; }
.rs-act.danger { color: var(--app-danger); border-color: var(--app-danger-border); }
.rs-act-danger:active, .rs-act.danger:active { background: var(--app-danger-weak); color: var(--app-danger); }
.rs-act span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rs-act-tip { font-size: 12px; line-height: 1.6; color: var(--app-text3); padding: 8px 2px 0; }
.rs-link {
  width: 100%; box-sizing: border-box; border: 1px solid var(--app-border); border-radius: 10px;
  background: var(--app-panel-2); color: var(--app-text2); font-size: 13px; padding: 10px 12px; outline: none;
}
/* 沙盒文件段：目录行与文件行都按 44px 热区口径；缩进一格 12px，触屏没有 hover 所以
   目录行直接把「几个文件 · 多大」印在行尾，不藏进悬浮提示 */
.rs-sb-dir {
  display: flex; align-items: center; gap: 6px; width: 100%; min-height: 44px;
  margin-bottom: 4px; padding: 0 10px; box-sizing: border-box;
  border: none; border-radius: 10px; background: var(--app-panel-2);
  color: var(--app-text); font-size: 13px; text-align: left; touch-action: manipulation;
}
.rs-sb-dir:active { background: var(--app-accent-weak); }
.rs-sb-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rs-sb-ind { flex: none; width: 12px; }
.rs-sb-file { align-items: center; }
.rs-sb-size { flex: none; }
.rs-sb-taken { color: var(--app-ok); }
.rs-sb-err {
  display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--app-danger);
  padding: 8px 10px; border: 1px solid var(--app-danger-border); border-radius: 10px;
  background: var(--app-danger-weak); margin-bottom: 6px;
}
.rs-sb-err span { flex: 1; min-width: 0; word-break: break-word; }
.rs-sb-retry {
  flex: none; min-height: 36px; padding: 0 12px; border: 1px solid var(--app-border);
  border-radius: 9px; background: var(--app-panel); color: var(--app-text2);
  font-size: 13px; touch-action: manipulation;
}
.rs-sb-retry:active { background: var(--app-accent-weak); color: var(--app-accent); }
/* 本轮过程总览：只读行（不点），行高 34 已够读；在跑的那一行给强调色，一眼看到卡在哪一步 */
.rs-phase { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-accent); padding: 2px 2px 8px; }
.rs-phase-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); animation: rs-blink 1.2s ease-in-out infinite; }
@keyframes rs-blink { 0%, 100% { opacity: 1; } 50% { opacity: .25; } }
@media (prefers-reduced-motion: reduce) { .rs-phase-dot { animation: none; } }
.rs-proc { display: flex; align-items: center; gap: 8px; min-height: 34px; font-size: 13px; padding: 0 2px; border-bottom: 1px dashed var(--app-border); }
.rs-proc-ic { flex: none; width: 14px; text-align: center; font-size: 12px; color: var(--app-text3); }
.rs-proc.run .rs-proc-ic { color: var(--app-accent); }
.rs-proc.err .rs-proc-ic { color: var(--app-danger); }
.rs-proc-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text2); }
.rs-proc.run .rs-proc-name { color: var(--app-text); }
.rs-proc.err .rs-proc-name { color: var(--app-danger); }
.rs-proc.off .rs-proc-name { color: var(--app-text3); }
.rs-proc-dur { flex: none; font-size: 11.5px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.rs-proc-dur.run { color: var(--app-accent); }
.rs-proc-dur.off { color: var(--app-text3); }
</style>
