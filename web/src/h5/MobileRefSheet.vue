<template>
  <!-- 运行状态 sheet：引用来源（按文档分组）+ 本轮/会话用量 + 本会话产物。
       PC 的右栏在移动端收纳到这里的底部 sheet；内容都来自引擎派生数据，不另算口径。 -->
  <BottomSheet :open="open" title="状态与来源" max-height="78dvh" @close="$emit('close')">
    <div class="rs">
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

      <!-- ---- 本轮用量 ---- -->
      <div class="rs-section">本轮用量</div>
      <div v-if="!tokens" class="rs-empty">本轮还没有用量记录</div>
      <template v-else>
        <div class="rs-kv"><span>上下文</span><b>{{ tokens.context ?? '—' }} / {{ tokens.budget ?? '—' }}</b></div>
        <div class="rs-kv"><span>输出</span><b>{{ tokens.output ?? '—' }} tokens<template v-if="tokens.outputIsReal === false">（估算）</template></b></div>
        <div class="rs-kv"><span>合计</span><b>{{ fmtTokens(tokens.total) }} tokens</b></div>
        <div v-if="tokens.hits > 0" class="rs-kv"><span>填入上下文</span><b>{{ tokens.hits }} 段</b></div>
        <div v-if="tokens.cached > 0" class="rs-kv"><span>缓存命中</span><b>{{ tokens.cached }} tokens</b></div>
      </template>

      <!-- ---- 会话累计 ---- -->
      <div class="rs-section">本会话累计</div>
      <div class="rs-kv"><span>已完成轮次</span><b>{{ sessionTokens.rounds }}</b></div>
      <div class="rs-kv"><span>Token 合计</span><b>{{ sessionTokensLabel }}</b></div>
      <div class="rs-kv"><span>检索轮次 / 引用</span><b>{{ sessionRetrieval.rounds }} 轮 · {{ sessionRetrieval.refs }} 段</b></div>

      <!-- ---- 产物 ---- -->
      <template v-if="artifacts.length">
        <div class="rs-section">本会话产物<span class="rs-n">{{ artifacts.length }}</span></div>
        <a v-for="(a, i) in artifacts" :key="i" class="rs-art" :href="a.url" :download="a.filename" target="_blank">
          <file-text-outlined class="rs-ic" />
          <span class="rs-art-name">{{ a.filename }}</span>
          <download-outlined class="rs-ic" />
        </a>
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
import { CaretRightOutlined, FileTextOutlined, DownloadOutlined, ShareAltOutlined, FileMarkdownOutlined, CopyOutlined, SwapOutlined, StopOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import BottomSheet from './BottomSheet.vue'
import { fmtTokens } from '../utils/token'
import { fmtSourceScore } from '../chat/projections'
import { buildSessionMd, saveMd } from '../views/exportMd'
import { copyText } from '../utils/clipboard'
import { useSessionShare } from '../views/shareSession'

defineProps({ open: { type: Boolean, default: false } })
defineEmits(['close', 'source'])

const engine = inject('wqChat')
const { groupedSources, lastTokens, lastSources, sessionTokens, sessionTokensLabel, sessionRetrieval, sessionArtifacts,
  currentSessionId, currentSessionTitle, messages } = engine

// 分组展开态：PC 侧这份状态在视图层（srcOpen/srcOpenOf/toggleSrc 未进引擎），移动壳按同口径本地实现
//（key → 开/合，默认展开；存 reactive 容器而非 computed 内，流式更新 sources 后已收起的组不弹回）
const srcOpen = reactive({})
const srcOpenOf = g => (srcOpen[g.key] !== undefined ? srcOpen[g.key] : true)
const toggleSrc = g => { srcOpen[g.key] = !srcOpenOf(g) }

const groups = computed(() => groupedSources.value)
const sources = computed(() => lastSources.value || [])
const tokens = computed(() => lastTokens.value)
const artifacts = computed(() => sessionArtifacts.value)
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
.rs-art { display: flex; align-items: center; gap: 8px; padding: 11px 12px; border: 1px solid var(--app-border); border-radius: 10px; margin-bottom: 6px; color: var(--app-text2); text-decoration: none; font-size: 13px; }
.rs-art-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
</style>
