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
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed, inject, reactive } from 'vue'
import { CaretRightOutlined, FileTextOutlined, DownloadOutlined } from '@ant-design/icons-vue'
import BottomSheet from './BottomSheet.vue'
import { fmtTokens } from '../utils/token'
import { fmtSourceScore } from '../chat/projections'

defineProps({ open: { type: Boolean, default: false } })
defineEmits(['close', 'source'])

const engine = inject('wqChat')
const { groupedSources, lastTokens, lastSources, sessionTokens, sessionTokensLabel, sessionRetrieval, sessionArtifacts } = engine

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
</style>
