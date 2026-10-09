<template>
  <!-- 移动壳的消息行：与 PC 版同源的数据（引擎的 messages 数组），不同的呈现——
       流程/正文按时间线交错渲染、工具卡与过程独白可折叠；操作行不靠 hover（触屏没有 hover）：
       点一下气泡把它「选中」，选中态或最新一条才显示操作行。 -->
  <!-- data-row-index 是会话内查找与「新问题置顶」共用的行契约：
       定位按它算出来，不做 "第 N 个子节点" 这种易碎假设；PC 版消息行带同一个属性，
       所以 src/chat/useChatSearch.js 两份壳体共用一套 Range/Highlight 逻辑。 -->
  <div class="mrow" :class="[m.role === 'user' ? 'is-user' : 'is-ai', { active }]"
       :data-row-index="index" :data-role="m.role === 'user' ? 'user' : 'ai'">
    <div class="mrow-inner" @click="onRowTap">
      <!-- ==================== 用户消息 ==================== -->
      <template v-if="m.role === 'user'">
        <div class="ububble">
          <div v-if="m.content" class="u-text">{{ m.content }}</div>
          <div v-if="m.images && m.images.length" class="u-imgs">
            <img v-for="(u, ii) in m.images" :key="ii" :src="resolveImg(u)" alt="图片" @click.stop="$emit('preview', m.images.map(resolveImg), ii)">
          </div>
          <div v-if="m.attachments && m.attachments.length" class="chips">
            <template v-for="(a, ai) in m.attachments" :key="ai">
              <span v-if="isPastedText(a)" class="mp-card" @click.stop="$emit('paste-view', a)">
                <file-text-outlined class="mp-ic" />
                <span class="mp-txt">
                  <span class="mp-name">{{ pasteTitle(a.name) }}</span>
                  <span class="mp-sub">{{ pasteSub(a) }}</span>
                </span>
              </span>
              <span v-else class="chip chip-file">
                <paper-clip-outlined />{{ a.name }}<em v-if="a.size"> · {{ fmtSize(a.size) }}</em>
              </span>
            </template>
          </div>
          <div v-if="m.skills && m.skills.length" class="chips">
            <span v-for="s in m.skills" :key="s" class="chip chip-skill">技能 · {{ s }}</span>
          </div>
          <div v-if="m.mentions && m.mentions.length" class="chips">
            <span v-for="(mm, mi) in m.mentions" :key="mi" class="chip chip-mention">{{ mm.name || mm.id }}</span>
          </div>
        </div>
        <div v-if="showActions" class="u-actions">
          <div v-if="m.variantCount > 1" class="ver-switch">
            <button class="ver-btn" :disabled="(m.variantIndex || 1) <= 1 || variantSwitching" @click.stop="$emit('switch-version', index, -1)">‹</button>
            <span class="ver-idx">{{ m.variantIndex || 1 }}/{{ m.variantCount }}</span>
            <button class="ver-btn" :disabled="(m.variantIndex || 1) >= m.variantCount || variantSwitching" @click.stop="$emit('switch-version', index, 1)">›</button>
          </div>
          <button class="act-btn" title="复制问题" @click.stop="$emit('copy-user', m)"><copy-outlined /></button>
          <button class="act-btn" title="编辑此问题，从这一轮重新生成" @click.stop="$emit('edit', index)"><edit-outlined /></button>
          <span v-if="m.time" class="act-time">{{ fmtMsgTime(m.time) }}</span>
        </div>
      </template>

      <!-- ==================== 助手消息 ==================== -->
      <template v-else>
        <div v-if="showAgentTag && debugDisplay" class="agent-tag">
          <AgentAvatar class="agent-tag-ava" :agent="badgeOf(m.agentId, m.agentName)" :size="14" /> 由「{{ m.agentName }}」回答
        </div>
        <div v-if="m.delegated" class="agent-tag">
          <AgentAvatar class="agent-tag-ava" :agent="badgeOf(m.agentId, m.delegated.name)" :size="14" /> 由「{{ m.delegated.name }}」回答本轮
          <span v-if="m.delegated.description" class="agent-tag-desc">{{ m.delegated.description }}</span>
        </div>
        <div v-if="m.dispatched && debugDisplay" class="agent-tag">
          <thunderbolt-outlined /> 已派遣「{{ m.dispatched.name }}」<span v-if="m.dispatched.fallback">（路由未命中，按默认）</span>
        </div>

        <!-- 深度思考面板：折叠态一行，展开看全文 -->
        <div v-if="m.thinking" class="card think-card">
          <button class="card-head" type="button" @click.stop="m.thinkOpen = !m.thinkOpen">
            <span class="card-title">深度思考</span>
            <span v-if="m.thinkLoading" class="spinner" aria-label="进行中"></span>
            <span v-else class="card-badge">已完成</span>
            <caret-right-outlined class="caret" :class="{ open: m.thinkOpen }" />
          </button>
          <div v-show="m.thinkOpen" class="card-body">
            <AnswerBody class="think-md" :content="m.thinking" :viewer="false" />
          </div>
        </div>

        <!-- 时间线：正文与过程/工具按到达顺序交错 -->
        <div v-if="hasTimelineBlocks(m)" class="md bubble-md" :data-msg-index="index">
          <template v-for="(seg, si) in timelineRows(m)" :key="si">
            <AnswerBody v-if="seg.kind === 'text'" class="tl-text" :content="m.content.slice(seg.from, seg.to)"
                        :images="m.images" :sources="m.sources" :msg-index="index"
                        runnable :session-id="sessionId" :streaming="m.loading"
                        :viewer="false" @citation="onBodyCitation" @preview="onBodyPreview" />
            <div v-else-if="seg.kind === 'cluster'" class="card proc-cluster" :class="{ open: clusterOpen(m, seg) }">
              <button class="card-head" type="button" @click.stop="toggleCluster(m, seg)">
                <span v-if="clusterRunning(seg)" class="spinner"></span>
                <check-outlined v-else-if="!clusterHasError(seg)" class="ic-ok" />
                <close-circle-outlined v-else class="ic-err" />
                <span v-if="clusterTools(seg).length" class="card-title">已执行 {{ clusterTools(seg).length }} 个操作</span>
                <span v-else class="card-title">执行过程 · {{ clusterProcCount(seg) }} 段说明</span>
                <span class="card-dim">· {{ clusterDur(seg) }}</span>
                <caret-right-outlined class="caret" :class="{ open: clusterOpen(m, seg) }" />
              </button>
            </div>
            <div v-else-if="seg.kind === 'process'" class="card proc-card" :class="{ inCluster: seg.inCluster }">
              <button class="card-head" type="button" @click.stop="toggleProc(m, seg)">
                <span class="card-title">{{ seg.inCluster ? '过程说明' : '执行过程' }}</span>
                <caret-right-outlined class="caret" :class="{ open: procOpen(m, seg) }" />
              </button>
              <div v-show="procOpen(m, seg)" class="card-body proc-body">{{ procSlice(m, seg) }}</div>
            </div>
            <div v-else-if="seg.kind === 'group'" class="tl-group" :class="{ inCluster: seg.inCluster }">
              <button v-if="seg.tools.length > 1" class="card-head" type="button" @click.stop="seg.tools[0]._groupOpen = !seg.tools[0]._groupOpen">
                <span v-if="groupRunning(seg)" class="spinner"></span>
                <check-outlined v-else-if="!groupHasError(seg)" class="ic-ok" />
                <close-circle-outlined v-else class="ic-err" />
                <span class="card-title">执行了 {{ seg.tools.length }} 个操作</span>
                <span class="card-dim">· {{ groupDur(seg) }}</span>
                <caret-right-outlined class="caret" :class="{ open: seg.tools[0]._groupOpen }" />
              </button>
              <div v-if="seg.tools.length === 1 || seg.tools[0]._groupOpen" class="tl-group-body">
                <div v-for="(t, ti) in seg.tools" :key="ti" class="card tool-card" :class="{ run: t.status === 'start', err: t.status === 'error' }">
                  <button class="card-head" type="button" @click.stop="t._open = !t._open">
                    <span v-if="t.status === 'start'" class="spinner"></span>
                    <check-outlined v-else-if="t.status === 'done'" class="ic-ok" />
                    <close-circle-outlined v-else class="ic-err" />
                    <span class="card-title">{{ toolLabel(t.name) }}</span>
                    <code v-if="toolBrief(t)" class="tool-brief">{{ toolBrief(t) }}</code>
                    <span v-if="t.attempts > 1" class="card-dim">重试 {{ t.attempts - 1 }} 次</span>
                    <span v-if="t.status === 'start' && t.startAt" class="card-dim">{{ liveToolDur(t.startAt) }}</span>
                    <span v-else-if="t.elapsedMs > 0" class="card-dim">{{ toolDuration(t.elapsedMs) }}</span>
                    <caret-right-outlined class="caret" :class="{ open: t._open }" />
                  </button>
                  <!-- askUser 问答记录：可折叠（默认展开），与 PC 同构 -->
                  <AskRecordCard v-if="t.name === 'askUser' && t.status !== 'start'" :t="t" />
                  <div v-show="t._open" class="card-body">
                    <template v-if="t.args"><div class="io-label">入参</div><pre class="io-pre">{{ prettyIo(t.args) }}</pre></template>
                    <template v-if="t.status === 'start' ? t.output : (t.result || t.output)">
                      <div class="io-label">{{ t.status === 'start' ? '实时输出' : (t.result ? '输出' : '输出（执行期）') }}</div>
                      <pre class="io-pre" :class="{ live: t.status === 'start' }">{{ liveOutput(t) }}</pre>
                    </template>
                    <template v-if="t.error"><div class="io-label">错误</div><pre class="io-pre io-err">{{ t.error }}</pre></template>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </div>
        <!-- 无时间线（整段渲染）。正文为空且在生成中时不渲染气泡壳——
             否则「正在检索/生成」阶段会出现一个空白的圆角框（进度行已表达状态） -->
        <AnswerBody v-else-if="m.content || !m.loading" class="bubble-md" :content="m.content || ''"
                    :images="m.images" :sources="m.sources" :msg-index="index"
                    runnable :session-id="sessionId"
                    :streaming="m.loading && !m.failed"
                    :viewer="false" @citation="onBodyCitation" @preview="onBodyPreview" />

        <!-- 智能体提问（askUser）的「待答」态不在气泡里渲染：移动壳与 PC 同语义，把底部输入卡
             整块替换成提问面板（见 MobileChatPage 的 m-askp）；答复后问答记录以工具卡形态留在本气泡 -->

        <!-- 工具列表兜底（历史消息无时间线时） -->
        <div v-if="m.toolCalls && m.toolCalls.length && !hasTimelineBlocks(m)" class="card">
          <button class="card-head" type="button" @click.stop="m._fbOpen = !m._fbOpen">
            <span v-if="toolRunning(m)" class="spinner"></span>
            <check-outlined v-else-if="!m.toolCalls.some(t => t.status === 'error')" class="ic-ok" />
            <close-circle-outlined v-else class="ic-err" />
            <span class="card-title">执行了 {{ toolCallsView(m.toolCalls).length }} 个操作</span>
            <span class="card-dim">· {{ fallbackDur(m) }}</span>
            <caret-right-outlined class="caret" :class="{ open: m._fbOpen }" />
          </button>
          <div v-show="m._fbOpen" class="card-body">
            <template v-for="(t, ti) in toolCallsView(m.toolCalls)" :key="ti">
              <AskRecordCard v-if="t.name === 'askUser' && t.status !== 'start'" :t="t" />
              <div v-else class="tool-line">
                <check-outlined v-if="t.status === 'done'" class="ic-ok" />
                <close-circle-outlined v-else-if="t.status === 'error'" class="ic-err" />
                <span v-else class="spinner"></span>
                <span class="tool-line-name">{{ toolLabel(t.name) }}</span>
                <span v-if="t.elapsedMs > 0" class="card-dim">{{ toolDuration(t.elapsedMs) }}</span>
              </div>
            </template>
          </div>
        </div>

        <!-- 错误卡：保留半程内容，给分类文案 + 重新生成 -->
        <div v-if="m.errorCard" class="err-card" :class="{ net: m.errorCard.kind === 'interrupted' }">
          <div class="err-head"><close-circle-outlined /> {{ errorBrief(m.errorCard.message, m.errorCard.kind) }}</div>
          <div v-if="m.errorCard.kind === 'interrupted'" class="err-tip">已生成的部分已保存。重新生成会重跑本轮并重新计费。</div>
          <button class="btn-ghost" @click.stop="$emit('retry', index)"><redo-outlined /> 重新生成</button>
        </div>

        <!-- 进度行：一轮同时只出现一处（互斥见 busyOf） -->
        <div v-if="busyOf(m)" class="busy" :class="{ warn: busyOf(m).warn }">
          <span class="spinner" />
          <span v-if="busyOf(m).text">{{ busyOf(m).text }}</span>
        </div>

        <!-- 工具审批（人在回路） -->
        <div v-if="m.approval" class="approval">
          <div class="approval-title"><exclamation-circle-outlined /> 请求执行工具「{{ toolLabel(m.approval.tool) }}」</div>
          <pre v-if="m.approval.args" class="io-pre">{{ m.approval.args }}</pre>
          <div class="approval-actions">
            <button class="btn-primary" :disabled="m.approval.busy" @click.stop="$emit('approve', m, true)">批准执行</button>
            <button class="btn-ghost" :disabled="m.approval.busy" @click.stop="$emit('approve', m, false)">拒绝</button>
          </div>
          <div class="card-dim">未处理将在 {{ Math.round((m.approval.timeoutMs || 120000) / 1000) }} 秒后按拒绝处理</div>
        </div>

        <!-- 产物 -->
        <div v-if="m.artifacts && m.artifacts.length" class="artifacts">
          <a v-for="(a, ai) in m.artifacts" :key="ai" class="artifact" :href="resolveImg(a.url)" :download="a.filename">
            <file-text-outlined /><span class="artifact-name">{{ a.filename }}</span><download-outlined class="artifact-dl" />
          </a>
        </div>

        <!-- 降级/警告/压缩提示 -->
        <div v-if="m.degradations && m.degradations.length" class="warn-bar">
          <span v-for="(d, di) in m.degradations" :key="di">{{ d.msg }}</span>
        </div>
        <div v-if="m.warnMsg" class="warn-bar">{{ m.warnMsg }}</div>
        <div v-if="m.tokens && m.tokens.historyCompressed > 0" class="info-bar">
          已把 {{ m.tokens.historyCompressed }} 轮早期对话压缩为摘要（完整记录仍可在会话中回看）
        </div>

        <!-- 检索行：折叠看检索词与引用片段 -->
        <div v-if="retrievalLineTitle(m) || toolSearchQueries(m).length || (m.sources && m.sources.length)" class="card">
          <button class="card-head" type="button" @click.stop="m.rtOpen = !m.rtOpen">
            <search-outlined class="ic-accent" />
            <span class="card-title">{{ retrievalLineTitle(m) }}</span>
            <caret-right-outlined class="caret" :class="{ open: m.rtOpen }" />
          </button>
          <div v-show="m.rtOpen" class="card-body">
            <div v-if="m.retrieved && m.retrieved.terms && m.retrieved.terms.length" class="rt-terms">检索词：{{ (m.retrieved.terms || []).join('、') }}</div>
            <div v-if="toolSearchQueries(m).length" class="rt-terms">精确检索：{{ toolSearchQueries(m).join('；') }}</div>
            <div v-for="(s, si) in (m.sources || [])" :key="si" class="rt-ref" @click.stop="$emit('source', s)">
              <span class="rt-ref-tag">[{{ s.ref }}]</span>
              <span v-if="s.origin === 'WEB'" class="rt-origin">联网</span>
              <span v-else-if="s.origin === 'MCP'" class="rt-origin">MCP</span>{{ sourceName(s) }}
              <div v-if="s.snippet" class="rt-snip">{{ s.snippet }}</div>
            </div>
          </div>
        </div>

        <!-- 子智能体编排（仅委派模式） -->
        <div v-if="subagentCard(m)" class="card">
          <button class="card-head" type="button" @click.stop="toggleSubagents(m)">
            <robot-outlined class="ic-accent" />
            <span class="card-title">{{ subagentCard(m).title }}</span>
            <span class="card-dim">{{ subagentCard(m).done }}/{{ subagentCard(m).total }} 完成</span>
            <caret-right-outlined class="caret" :class="{ open: m.saOpen }" />
          </button>
          <div v-show="m.saOpen" class="card-body">
            <div v-for="b in subagentCard(m).branches" :key="b.id" class="sa-row">
              <div class="sa-line">
                <span v-if="b.status === 'running'" class="spinner"></span>
                <check-outlined v-else-if="b.status === 'done'" class="ic-ok" />
                <close-circle-outlined v-else class="ic-err" />
                <span class="sa-name">{{ b.name }}</span>
                <span v-if="b.status === 'done'" class="card-dim">{{ b.hits }} 块 · {{ fmtDuration(b.elapsedMs) }}</span>
              </div>
              <div v-if="b.description" class="card-dim">{{ b.description }}</div>
              <div class="sa-bar"><div class="sa-fill" :style="{ width: barWidth(subagentCard(m), b) }" /></div>
              <div v-if="b.digest" class="sa-digest">{{ b.digest }}</div>
            </div>
          </div>
        </div>

        <!-- 追问建议 -->
        <div v-if="m.related && m.related.length" class="related">
          <span class="card-dim">接下来可以：</span>
          <button v-for="(q, qi) in m.related" :key="qi" class="related-tag" type="button" @click.stop="$emit('ask', q)">{{ q }}</button>
        </div>

        <!-- 操作行：选中态或最新一条可见（触屏没有 hover） -->
        <div v-if="showActions" class="ai-actions">
          <div v-if="(m.versions && m.versions.length > 1) || m.variantCount > 1" class="ver-switch">
            <button class="ver-btn" :disabled="!canSwitchPrev(m)" @click.stop="$emit('switch-version', index, -1)">‹</button>
            <span class="ver-idx">{{ verLabel(m) }}</span>
            <button class="ver-btn" :disabled="!canSwitchNext(m)" @click.stop="$emit('switch-version', index, 1)">›</button>
          </div>
          <template v-if="m.messageId">
            <button class="act-btn" title="复制" @click.stop="$emit('copy', m)"><copy-outlined /></button>
            <button class="act-btn" :class="{ on: m.fb === 1 }" :disabled="m.fb != null" title="有帮助" @click.stop="$emit('feedback', m, 1)"><like-outlined /></button>
            <button class="act-btn" :class="{ on: m.fb === 0 }" :disabled="m.fb != null" title="没帮助" @click.stop="$emit('feedback', m, 0)"><dislike-outlined /></button>
            <button class="act-btn" :disabled="loading" title="重新生成" @click.stop="$emit('retry', index)"><reload-outlined /></button>
            <!-- 单轮操作（PC 是 a-dropdown）：导出这轮 / 加入评测集 / 检索调试 / 删除本轮。
                 触屏没有 hover 菜单，收进底部 sheet（MobileRoundSheet） -->
            <button class="act-btn" title="更多操作" @click.stop="$emit('more', index)"><more-outlined /></button>
          </template>
          <span v-if="m.tokens" class="act-time">≈{{ fmtTokens(m.tokens.total) }} tokens</span>
          <span v-if="m.time" class="act-time">{{ fmtMsgTime(m.time) }}</span>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import {
  CaretRightOutlined, CheckOutlined, CloseCircleOutlined, CopyOutlined, EditOutlined, LikeOutlined,
  DislikeOutlined, ReloadOutlined, RedoOutlined, RobotOutlined, ThunderboltOutlined, SearchOutlined,
  FileTextOutlined, DownloadOutlined, PaperClipOutlined, ExclamationCircleOutlined, MoreOutlined,
  QuestionCircleOutlined, CheckCircleOutlined
} from '@ant-design/icons-vue'
import { resolveImg } from '../utils/markdown'
import AnswerBody from '../components/AnswerBody.vue'
import { fmtTokens } from '../utils/token'
import {
  busyOf, hasTimelineBlocks, timelineRows, procOpen, toggleProc, procSlice, toolLabel, toolBrief,
  clusterOpen, toggleCluster, clusterTools, clusterRunning, clusterHasError, clusterDur, clusterProcCount,
  prettyIo, liveOutput, toolDuration, liveToolDur, toolRunning, groupRunning, groupHasError, groupDur,
  fallbackDur, toolCallsView, toolSearchQueries, retrievalLineTitle, subagentCard, barWidth, toggleSubagents, fmtDuration,
  fmtMsgTime, fmtSize, errorBrief, sourceName, canSwitchPrev, canSwitchNext, verLabel, agentBadgeOf,
  pasteTitle, pasteSub, isPastedText
} from '../chat/projections'
import AskRecordCard from '../components/AskRecordCard.vue'
import AgentAvatar from '../components/AgentAvatar.vue'

const props = defineProps({
  m: { type: Object, required: true },
  index: { type: Number, required: true },
  /** 选中态（点气泡切换）：选中或最新一条才显示操作行 */
  active: { type: Boolean, default: false },
  /** 是否最新一条 AI 消息 */
  isLast: { type: Boolean, default: false },
  /** 排障显示开关（归属徽标/派遣提示，engine 的 debugDisplayVisible） */
  debugDisplay: { type: Boolean, default: false },
  /** 沙盒运行等富渲染按钮需要会话上下文 */
  sessionId: { type: String, default: '' },
  /** 本轮是否正在生成（禁用重新生成入口） */
  loading: { type: Boolean, default: false },
  variantSwitching: { type: Boolean, default: false }
})
const emit = defineEmits(['activate', 'preview', 'paste-view', 'source', 'retry', 'edit', 'feedback', 'approve',
  'switch-version', 'ask', 'copy', 'copy-user', 'more'])

const showActions = computed(() => props.active || (props.m.role === 'ai' && props.isLast))
// 归属徽标去重（与 PC 的 showAgentTag 同口径，但简化：只在首条或归属变化时显示）
const showAgentTag = computed(() => {
  const m = props.m
  return m.role === 'ai' && !!m.agentName
})
// 徽标头像数据源：可用智能体清单由引擎持有（同 provide 'wqChat' 的其它 sheet 同一取法）；
// 查不到时 agentBadgeOf 返回 null → AgentAvatar 落回默认机器人，与改动前一致
const engine = inject('wqChat', null)
const badgeOf = (agentId, agentName) => agentBadgeOf(engine?.agentList?.value, agentId, agentName)

const onRowTap = () => { if (!props.active) emit('activate', props.index) }

/**
 * 正文点击的复制/沙盒运行/角标/图片四分支已在 chat/answerViewer 的 handleBodyClick 里收口
 * （与 PC 同一份）。移动壳只是把后两分支接到自己的底部抽屉与简化灯箱上 —— 所以这里
 * 只剩两个转发函数，不再自己写一遍委托逻辑。
 */
const onBodyCitation = src => emit('source', src)
const onBodyPreview = (urls, index) => emit('preview', urls, index)
</script>

<style scoped>
/* 顶到容器上沿时留一点呼吸：新问题置顶与查找跳转都走 scrollIntoView({block:'start'})，
   没有 scroll-margin 会让气泡齐刷刷压在顶部边框上（桌面版靠尾随留白天然有这个间隙）。 */
.mrow { display: flex; margin-bottom: 12px; scroll-margin-top: 8px; }
.mrow.is-user { justify-content: flex-end; }
.mrow-inner { max-width: 100%; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.mrow.is-ai .mrow-inner { width: 100%; }

/* ---- 用户消息 ---- */
.ububble {
  align-self: flex-end; max-width: 86%;
  background: var(--app-accent-weak); color: var(--app-text);
  border: 1px solid var(--app-accent-border);
  border-radius: 14px 14px 4px 14px;
  padding: 10px 12px;
}
.u-text { white-space: pre-wrap; word-break: break-word; font-size: 15px; line-height: 1.6; }
.u-imgs { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.u-imgs img { width: 88px; height: 88px; object-fit: cover; border-radius: 8px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.chip { font-size: 12px; padding: 3px 8px; border-radius: 999px; background: var(--app-panel); border: 1px solid var(--app-border); color: var(--app-text2); display: inline-flex; align-items: center; gap: 4px; }
.chip-skill { background: var(--app-info-weak); border-color: var(--app-info-border); color: var(--app-accent); }
.chip-file em { font-style: normal; color: var(--app-text3); }
/* 长文本粘贴卡片：图标块 + 名称与体积/字数两行，点一下看全文（与输入区待发送卡片同形） */
.mp-card {
  display: flex; align-items: center; gap: 8px; max-width: 220px;
  padding: 6px 10px; border-radius: 10px;
  background: var(--app-panel); border: 1px solid var(--app-border);
}
.mp-ic {
  flex: none; width: 30px; height: 30px; border-radius: 7px; font-size: 15px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-accent);
}
.mp-txt { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.mp-name { font-size: 13px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mp-sub { font-size: 11px; color: var(--app-text3); }
.u-actions, .ai-actions {
  display: flex; align-items: center; gap: 2px; flex-wrap: wrap;
  justify-content: flex-end; padding: 0 2px;
}
.ai-actions { justify-content: flex-start; }
.act-btn {
  width: 34px; height: 34px; border: none; background: transparent; border-radius: 8px;
  color: var(--app-text3); font-size: 15px; display: inline-flex; align-items: center; justify-content: center;
  touch-action: manipulation;
}
.act-btn:active { color: var(--app-accent); background: var(--app-accent-weak); }
.act-btn.on { color: var(--app-accent); }
.act-btn:disabled { opacity: .45; }
.act-time { font-size: 11px; color: var(--app-text3); margin-left: 4px; }

/* ---- 助手消息 ---- */
.agent-tag { display: inline-flex; align-items: center; gap: 5px; align-self: flex-start; font-size: 12px; color: var(--app-text3); }
/* 徽标内头像：AgentAvatar 自带圆角块，只保证不被 flex 压扁（与 PC 的 .agent-tag-ava 同口径） */
.agent-tag-ava { flex: none; }
.agent-tag-desc { color: var(--app-text3); opacity: .85; }
.bubble-md {
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 4px 14px 14px 14px; padding: 10px 12px;
  font-size: 15px; line-height: 1.7; min-width: 0; overflow-wrap: anywhere;
}
.tl-text { min-width: 0; }

/* 折叠卡（思考 / 执行过程 / 工具 / 检索 / 编排） */
.card { border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel-2); overflow: hidden; }
.card-head {
  width: 100%; display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 10px 12px; border: none; background: transparent; cursor: pointer; text-align: left;
  color: var(--app-text2); font-size: 13px; min-height: 40px; touch-action: manipulation;
}
.card-title { font-weight: 500; }
.card-dim { font-size: 12px; color: var(--app-text3); }
.card-badge { font-size: 11px; color: var(--app-ok); }
.card-body { padding: 0 12px 10px; font-size: 13px; }
.think-md { font-size: 14px; line-height: 1.7; }
.proc-body { color: var(--app-text2); white-space: pre-wrap; word-break: break-word; }
.caret { margin-left: auto; flex: none; transition: transform .18s; color: var(--app-text3); }
.caret.open { transform: rotate(90deg); }
@media (prefers-reduced-motion: reduce) { .caret { transition: none; } }
.spinner {
  width: 12px; height: 12px; flex: none; border-radius: 50%;
  border: 2px solid var(--app-accent-border); border-top-color: var(--app-accent);
  animation: mspin .8s linear infinite;
}
@keyframes mspin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinner { animation: none; } }
.ic-ok { color: var(--app-ok); }
.ic-err { color: var(--app-danger); }
.ic-accent { color: var(--app-accent); }
/* 过程簇：连续的「独白+工具」收成一张卡（PC 端同构，见 ChatPage.vue .tl-cluster）。
   展开后其内各段由 timelineRows 摊平接着渲染，这里只给簇内段左缩进一格做出层级。 */
.proc-cluster { background: var(--app-panel); }
.tl-group { display: flex; flex-direction: column; gap: 6px; }
.proc-card.inCluster, .tl-group.inCluster { margin-left: 12px; }
.tl-group-body { display: flex; flex-direction: column; gap: 6px; padding: 0 0 2px; }
.tool-card .card-head { padding: 8px 10px; }
.tool-brief { font-family: "SF Mono", Menlo, monospace; font-size: 12px; color: var(--app-text3); background: transparent; overflow: hidden; text-overflow: ellipsis; max-width: 40%; white-space: nowrap; }
.io-label { font-size: 12px; color: var(--app-text3); margin: 8px 0 4px; }
.io-pre { margin: 0; padding: 8px 10px; border-radius: 8px; background: var(--app-code-bg); font-family: "SF Mono", Menlo, monospace; font-size: 12px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow: auto; }
.io-pre.live { border-left: 2px solid var(--app-accent); }
.io-err { color: var(--app-danger-text); }
.tool-line { display: flex; align-items: center; gap: 6px; padding: 4px 0; }
.tool-line-name { font-size: 13px; }

/* 智能体提问（askUser）待答态由 MobileChatPage 的 m-askp 面板承载，气泡内不再渲染提问卡 */

/* askUser 问答记录由共用组件 AskRecordCard 承载（自带折叠头与样式）。
   两处宿主内边距不同，用 :deep 分别覆盖，让折叠头与各自的卡片头左右对齐：
   ① .tool-card 内的直接子节点 → 对齐 .card-head 的 10px 12px；
   ② 兜底分支在 .card-body（已有 12px padding）内 → 只需清零自身外边距。 */
.tool-card :deep(.ask-rec) { --ask-rec-pad: 10px 12px; margin: 0 0 4px; }
.tool-card :deep(.ask-rec-body) { padding: 4px 12px 0; }
.card-body :deep(.ask-rec) { margin: 0 0 8px; }
.card-body :deep(.ask-rec-body) { padding: 4px 0 0; }

.err-card { border: 1px solid var(--app-danger-border); background: var(--app-danger-weak); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.err-card.net { border-color: var(--app-warn-border); background: var(--app-warn-weak); }
.err-head { font-size: 13px; color: var(--app-danger-text); display: flex; align-items: center; gap: 6px; }
.err-card.net .err-head { color: var(--app-warn-text); }
.err-tip { font-size: 12px; color: var(--app-text2); }
.busy { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--app-text2); }
.busy.warn { color: var(--app-warn-text); }

.approval { border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.approval-title { font-size: 13px; font-weight: 600; color: var(--app-warn-text); display: flex; align-items: center; gap: 6px; }
.approval-actions { display: flex; gap: 8px; }
.btn-primary { border: none; border-radius: 8px; background: var(--app-accent); color: #fff; font-size: 13px; padding: 8px 14px; min-height: 36px; touch-action: manipulation; }
.btn-primary:disabled { background: var(--app-accent-disabled); }
.btn-ghost { border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); color: var(--app-text2); font-size: 13px; padding: 8px 14px; min-height: 36px; display: inline-flex; align-items: center; gap: 5px; touch-action: manipulation; }
.btn-ghost:active { color: var(--app-accent); border-color: var(--app-accent); }

.artifacts { display: flex; flex-direction: column; gap: 6px; }
.artifact { display: flex; align-items: center; gap: 8px; padding: 9px 12px; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel); color: var(--app-text2); text-decoration: none; font-size: 13px; }
.artifact-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.artifact-dl { color: var(--app-text3); }

.warn-bar { border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); border-radius: 8px; padding: 8px 10px; font-size: 12px; display: flex; flex-direction: column; gap: 4px; }
.info-bar { border: 1px solid var(--app-info-border); background: var(--app-info-weak); color: var(--app-accent); border-radius: 8px; padding: 8px 10px; font-size: 12px; }

.rt-terms { font-size: 12px; color: var(--app-text3); margin-bottom: 6px; }
.rt-ref { padding: 8px 0; border-top: 1px dashed var(--app-border); font-size: 12px; color: var(--app-text2); cursor: pointer; }
.rt-ref-tag { color: var(--app-accent); font-weight: 600; margin-right: 4px; }
.rt-origin { font-size: 11px; color: var(--app-accent); border: 1px solid var(--app-accent-border); border-radius: 4px; padding: 0 4px; margin-right: 4px; }
.rt-snip { color: var(--app-text3); margin-top: 4px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

.sa-row { padding: 8px 0; border-top: 1px dashed var(--app-border); display: flex; flex-direction: column; gap: 4px; }
.sa-line { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.sa-name { font-weight: 500; }
.sa-bar { height: 5px; border-radius: 3px; background: var(--app-accent-weak); overflow: hidden; }
.sa-fill { height: 100%; background: var(--app-accent); }
.sa-digest { font-size: 12px; color: var(--app-text3); }

.related { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.related-tag { border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-accent); border-radius: 999px; padding: 6px 12px; font-size: 13px; min-height: 34px; touch-action: manipulation; }

.ver-switch { display: inline-flex; align-items: center; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; margin-right: 4px; }
.ver-btn { width: 34px; height: 34px; border: none; background: var(--app-panel); color: var(--app-text2); font-size: 16px; touch-action: manipulation; }
.ver-btn:disabled { opacity: .35; }
.ver-idx { font-size: 12px; color: var(--app-text3); padding: 0 4px; }
</style>
