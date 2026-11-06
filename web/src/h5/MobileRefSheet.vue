<template>
  <!-- 运行状态 sheet：引用来源（按文档分组）+ 本轮/会话用量 + 本会话产物。
       PC 的右栏在移动端收纳到这里的底部 sheet；内容都来自引擎派生数据，不另算口径。 -->
  <BottomSheet :open="open" title="状态与来源" max-height="78dvh" @close="$emit('close')">
    <div class="rs">
      <!-- ---- 本轮 ----
           气泡里逐步的工具卡一直都在（MobileMsgRow 的过程簇），这一张补的是另一个视角：
           不用上下滚就能看完「走了哪几步、每步做的是什么事、此刻在等谁」。
           判据与 PC 右栏那张卡同一份（roundSummary：卡头那半句、在等谁、失败原因；
           toolBrief：关键入参）——两处不许各写一套。单步耗时两端都不报，整轮用时只在气泡那条状态行。
           「查看完整过程」不在这儿放：顶栏那颗时钟图标就是它的入口，一个动作只留一个入口。 -->
      <template v-if="procSteps.length || procHold">
        <div class="rs-section">本轮<span v-if="procHeadTail" class="rs-n">{{ procHeadTail }}</span></div>
        <div v-for="(t, ti) in procSteps" :key="ti" class="rs-step"
             :class="{ run: isRunStep(t), err: t.status === 'error', off: t.denied }">
          <div class="rs-proc">
            <span class="rs-proc-ic">
              <loading-outlined v-if="isRunStep(t)" spin />
              <check-outlined v-else-if="t.status === 'done'" />
              <close-circle-outlined v-else-if="t.status === 'error'" />
              <pause-outlined v-else />
            </span>
            <span class="rs-proc-name" :title="toolDesc(t.name, t.hint)">{{ toolLabel(t.name, t.label) }}</span>
            <span v-if="t.denied" class="rs-proc-st">被挡下</span>
            <span v-else-if="isRunStep(t)" class="rs-proc-st run">进行中</span>
            <span v-else-if="t.status === 'error'" class="rs-proc-st err">失败</span>
          </div>
          <div v-if="procBrief(t)" class="rs-proc-arg" :title="procBrief(t)">{{ procBrief(t) }}</div>
        </div>
        <div v-if="procHold" class="rs-hold" :class="{ bad: procHold.bad, act: !!procHold.go }"
             :title="procHold.tip" @click="procHold.go && $emit('open-hitl', procHold.kind)">
          <pause-outlined v-if="!procHold.bad" />
          <close-circle-outlined v-else />
          <span>{{ procHold.text }}</span>
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

      <!-- ---- 本轮用量 ----
           每一行都是网关 usage 回传的实测值；名里带「未缓存/其中」的是上一行的子项，缩进摆，
           不并列成能相加的样子（占用 ≠ 累计送入 ≠ 输出，后者含工具往返重发）。
           PC 端这块是工具栏圆环 + 悬浮容量卡，手机收进这一节。 -->
      <div class="rs-section">本轮用量</div>
      <div v-if="!tokens" class="rs-empty">本轮还没有用量记录</div>
      <template v-else>
        <div v-if="ctxCapData.window > 0" class="rs-kv">
          <span>上下文占用</span>
          <b>{{ fmtTokens(ctxCapData.used) }} / {{ fmtWindow(ctxCapData.window) }}（{{ ctxCapData.pct }}%）</b>
        </div>
        <div v-if="ctxCapData.window > 0" class="cap-bar">
          <span class="cap-fill" :class="ctxRingLevel" :style="{ width: ctxCapData.fillPct + '%' }" />
        </div>
        <div v-for="r in ctxCapData.rows" :key="r.label" class="rs-kv" :class="{ 'is-sub': r.sub }">
          <span>{{ r.label }}</span>
          <b><template v-if="r.note">{{ r.note }} · </template>{{ fmtTokens(r.tokens) }}</b>
        </div>
        <div v-if="!ctxCapData.rows.length" class="rs-empty">本轮还没有实测用量（随用量事件下发）</div>
        <div v-if="tokens.hits > 0" class="rs-kv"><span>填入上下文</span><b>{{ tokens.hits }} 段</b></div>
      </template>

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
               :data-pv-kind="previewKindOf(a)"
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
        <div class="rs-section">会话文件<span v-if="sandboxCount" class="rs-n">{{ sandboxCount }} 个{{ sandboxBytes ? ' · ' + sbSize(sandboxBytes) : '' }}</span></div>
        <div v-if="sandboxLoading" class="rs-empty">正在读取目录…</div>
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
                 target="_blank" rel="noopener" title="在新标签页打开" aria-label="在新标签页打开"><eye-outlined /></a>
              <button type="button" class="rs-art-act" :title="'下载 ' + row.file.name"
                      @click="sandboxDownload(row.file)"><download-outlined /></button>
              <button v-if="!sandboxTaken.has(row.file.path)" type="button" class="rs-art-act"
                      title="存为产物（进「我的产物」，可预览可下载）" aria-label="存为产物（进「我的产物」，可预览可下载）"
                      @click="sandboxTake(row.file)"><save-outlined /></button>
              <check-outlined v-else class="rs-art-act rs-sb-taken" title="已存为产物" />
            </span>
          </div>
        </template>
        <div v-if="sandboxRest" class="rs-act-tip">目录里另有 {{ sandboxRest }} 个文件没列出（这里只列最新 {{ sandboxFiles.length }} 个）</div>
      </template>

      <!-- ---- 沙盒后台进程：助手起的、不会自己退的服务（npm run dev 这类） ----
           露脸判据与 PC 右栏那张卡同一条（引擎的 procCardVisible）：一条都没扫到就整段不显示，
           「读不到」才显示并给重试。秒针、跑了多久、停掉几种结果全在引擎那一份里。
           PC 上那些靠 hover 才说得清的（端口是沙盒内部的、来源不明为什么停不了）在触屏上没有第二次
           机会，所以把话直接写进行内。
           类名走 rs-bg* 一段：rs-proc* 已被上面「本轮」那些步 row 占着，同名会让两套样式互相打脏
           （真机 2026-10-26 撞过一次：进程行数比实况多 1，多的那条就是工具步 row）。 -->
      <template v-if="procCardVisible">
        <div class="rs-section">后台进程<span class="rs-n rs-bg-n">{{ procs.length }} 个在跑</span></div>
        <div v-if="procErr" class="rs-sb-err">
          <span>{{ procErr }}</span>
          <button type="button" class="rs-sb-retry" @click="loadSandboxProcs">重试</button>
        </div>
        <div v-for="(p, i) in procs" :key="p.tag" class="rs-bg"
             :class="{ 'is-killing': killingTag === p.tag }" :style="{ '--proc-i': i }">
          <div class="rs-bg-cmd">
            <span class="rs-bg-dot" aria-hidden="true" />
            <span class="rs-bg-cmd-t">{{ p.command }}</span>
          </div>
          <div class="rs-bg-foot">
            <span class="rs-bg-meta">
              <span>跑了 {{ procAge(p) }}</span>
              <span v-if="p.ports && p.ports.length"> · 占着端口 {{ p.ports.join(' / ') }}（会话内部）</span>
              <span v-if="p.roots > 1"> · {{ p.roots }} 棵进程树</span>
              <span v-if="!p.own"> · 同工作区别的会话起的</span>
            </span>
            <button v-if="p.killable" type="button" class="rs-bg-kill" :disabled="killingTag === p.tag"
                    @click="killProc(p)">{{ killingTag === p.tag ? '停止中…' : '停止它' }}</button>
            <span v-else class="rs-bg-unknown">认不出来源，停不了</span>
          </div>
        </div>
        <div class="rs-act-tip">停掉它会连同它起走的全部子进程一起结束，占用的端口随之释放</div>
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
import { fmtSourceScore, fmtWindow,
         toolLabel, toolDesc, toolBrief } from '../chat/projections'
import { roundSteps, roundHold, roundHeadTail, roundPhase, isRunStep } from '../chat/roundSummary'
// 产物地址必须过 resolveImg：后端给的是 /ai/artifacts/**，不经媒体前缀在开发态会落到 SPA 回退（下载到 index.html）
import { resolveImg } from '../utils/markdown'
import { canPreview, artifactExt, previewKindOf } from '../utils/artifactView'
import { buildSessionMd, saveMd } from '../views/exportMd'
import { copyText } from '../utils/clipboard'
import { useSessionShare } from '../views/shareSession'

const props = defineProps({ open: { type: Boolean, default: false } })
defineEmits(['close', 'source', 'open-hitl'])

const engine = inject('wqChat')
// 目标模式：与 PC 同一份 sessionGoal（会话级一份，横跨多轮），卡片本体共用 GoalCard——状态词、
// 轮次与「为什么停在这儿」都由它按 projections 那一份算，本 sheet 只负责把卡片摆上去。
const { groupedSources, lastTokens, lastSources, sessionTokens, sessionRetrieval, sessionArtifacts,
  currentSessionId, currentSessionTitle, messages,
  // 容量与实测用量整份吃引擎那一份：与 PC 悬浮卡同一个算法、同一套行。
  // 此前移动壳自持一份分类表与 cap 计算，两端各写各的，同一轮的占用被两把尺量出两个数
  ctxCapData, ctxRingLevel,
  sessionGoal,
  // 沙盒目录：取数、判据、折树、下载与存为产物全吃引擎那一份（与 PC 右栏那张卡同源，
  // 2026-10-25 从 PC 壳下沉），本 sheet 只负责把它摆成触屏形状
  sandboxFiles, sandboxRows, sandboxCount, sandboxBytes, sandboxRest,
  sandboxErr, sandboxLoading, sandboxTaken,
  loadSandboxFiles, toggleSandboxDir, sandboxDownload, sandboxTake, sbSize,
  // 后台进程同样整族吃引擎（2026-10-26 从 PC 壳下沉）：露脸判据、秒针、停止的三种结果只算一份
  procs, procErr, procCardVisible, killingTag, loadSandboxProcs, procAge, killProc,
  // 本轮过程总览的数据源：与 PC 右栏那张卡同一个对象（跑轮时它就是正在流的那条，结束后是上一轮）
  panelAi } = engine

// 分组展开态：PC 侧这份状态在视图层（srcOpen/srcOpenOf/toggleSrc 未进引擎），移动壳按同口径本地实现
//（key → 开/合，默认展开；存 reactive 容器而非 computed 内，流式更新 sources 后已收起的组不弹回）
const srcOpen = reactive({})
const srcOpenOf = g => (srcOpen[g.key] !== undefined ? srcOpen[g.key] : true)
const toggleSrc = g => { srcOpen[g.key] = !srcOpenOf(g) }

const groups = computed(() => groupedSources.value)
const sources = computed(() => lastSources.value || [])
const tokens = computed(() => lastTokens.value)
const artifacts = computed(() => sessionArtifacts.value)

// ==================== 本轮 ====================
// 判据与 PC 右栏那张卡（RightProcessCard）共用 roundSummary：并重复卡、卡头那半句、
// 在等谁、失败原因、参数摘要都出自同一份；这里只负责排版。
const procSteps = computed(() => roundSteps(panelAi.value))
const procHold = computed(() => roundHold(panelAi.value))
const procHeadTail = computed(() => roundHeadTail(panelAi.value, roundPhase(panelAi.value)))
const procBrief = t => toolBrief(t)
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
// 收尾那一拍已经拉过）。后台进程同理——起了个服务、又自己退了的，重扫才知道。
watch(() => props.open, v => { if (v) { loadSandboxFiles(); loadSandboxProcs() } })
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
/* 容量填充条：轨道 8px 圆角，填充宽=占用/窗口（与上面那行百分比同一口径，空轨即剩余窗口） */
.cap-bar { display: flex; height: 8px; border-radius: 4px; overflow: hidden; background: var(--app-panel-2); border: 1px solid var(--app-border); }
.cap-fill { height: 100%; border-radius: 4px; background: var(--app-accent); transition: width .25s; }
.cap-fill.warn { background: var(--app-warn); }
.cap-fill.danger { background: var(--app-danger); }
/* 子项（未缓存输入 / 其中思考）缩进一档：它是上一行里拆出来的，不是并列的一项 */
.rs-kv.is-sub { padding-left: 12px; }
.rs-kv.is-sub > span { color: var(--app-text3); }
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
/* 触屏热区：重试那颗 36px，左边是错误文案（不可点）→ 只补命中高度，横向不扩免得文案与钮抢手 */
.rs-sb-retry { position: relative; }
.rs-sb-retry::after {
  content: ''; position: absolute; top: 50%; left: 0; right: 0;
  height: 44px; transform: translateY(-50%);
}
/* ---- 后台进程：命令行独占一行（触屏没有 title 可看，它是这行里唯一认得出「哪个服务」的东西），
   次要信息与「停止它」同一行；多行按整序错相呼吸，不许整列同步（与 PC 那张卡同一口径）。
   前缀 rs-bg*：rs-proc* 是上面「本轮过程」步 row 的类名，两套样式不能共用一个选择器 ---- */
.rs-bg { padding: 8px 0; border-top: 1px solid var(--app-border); }
.rs-bg.is-killing { opacity: .55; }
.rs-bg-n { margin-left: 2px; }
.rs-bg-cmd { display: flex; align-items: center; gap: 6px; min-width: 0; }
.rs-bg-cmd-t { flex: 1; min-width: 0; font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 12px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rs-bg-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent);
  animation: rs-bg-pulse 1.8s ease-in-out infinite; animation-delay: calc(var(--proc-i, 0) * -0.43s); }
@keyframes rs-bg-pulse { 0%, 100% { opacity: .3 } 50% { opacity: 1 } }
.rs-bg.is-killing .rs-bg-dot { animation: none; background: var(--app-text3); }
.rs-bg-foot { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
.rs-bg-meta { flex: 1; min-width: 0; font-size: 12px; color: var(--app-text3); line-height: 1.5;
  font-variant-numeric: tabular-nums; }
.rs-bg-kill {
  flex: none; min-height: 44px; padding: 0 14px; border: 1px solid var(--app-border); border-radius: 10px;
  background: var(--app-panel); color: var(--app-text2); font-size: 14px; touch-action: manipulation;
}
.rs-bg-kill:active { color: var(--app-warn-text); border-color: var(--app-warn-text); }
.rs-bg-kill:disabled { opacity: .6; }
.rs-bg-unknown { flex: none; font-size: 12px; color: var(--app-text3); }
@media (prefers-reduced-motion: reduce) { .rs-bg-dot { animation: none; } }
/* 本轮：一步两行（动作名一行、它做的事一行），只读行不点；在跑的那一行给强调色，
   一眼看到卡在哪一步。单步耗时不报——整轮用时只在气泡那条状态行。 */
@keyframes rs-blink { 0%, 100% { opacity: 1; } 50% { opacity: .25; } }
.rs-step { border-bottom: 1px dashed var(--app-border); padding: 4px 2px; }
.rs-proc { display: flex; align-items: center; gap: 8px; min-height: 26px; font-size: 13px; }
.rs-proc-ic { flex: none; width: 14px; text-align: center; font-size: 12px; color: var(--app-text3); }
.rs-step.run .rs-proc-ic { color: var(--app-accent); }
.rs-step.err .rs-proc-ic { color: var(--app-danger); }
.rs-proc-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text2); }
.rs-step.run .rs-proc-name { color: var(--app-text); }
.rs-step.err .rs-proc-name { color: var(--app-danger); }
.rs-step.off .rs-proc-name { color: var(--app-text3); }
.rs-proc-st { flex: none; font-size: 11.5px; color: var(--app-text3); }
.rs-proc-st.run { color: var(--app-accent); }
.rs-proc-st.err { color: var(--app-danger); }
/* 参数单独一行：触屏一行可用宽比 PC 右栏宽得多，仍与 PC 同一份 toolBrief，不另写口径 */
.rs-proc-arg { font-size: 12px; color: var(--app-text2); padding: 0 0 0 22px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rs-hold { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--app-warn-text); padding: 8px 2px 2px; }
.rs-hold.bad { color: var(--app-danger); }
.rs-hold.act { cursor: pointer; }
</style>
