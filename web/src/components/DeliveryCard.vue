<template>
  <!--
    交付卡：一组产物 = 一个交付包（入口、体量、分组、批量动作）。
    单件不再降级成一行灰条：与多件共用同一张卡，只按件数切换密度。
    - 单件：左缩略 + 标题/文件名/元信息 + 打开预览（主）/下载（次）；
    - 多件：包名 + 入口 + 清单 + 打开入口 / 下载 ZIP / 查看全部；
    - 分享页（shared）：无登录态，不开预览台、不给 ZIP，产物只能新标签打开/下载；
    - fresh：仅实时产物到达时置位，完成卡播一次入场；历史恢复不播。

    预览判据只有 artifactView 一份；缩略的取数与限量在 utils/artifactThumb。
  -->
  <div v-if="delivery" class="dlv" :class="['tone-' + (delivery.badgeTone || 'none'), { shared, single: isSingle, enter: fresh }]">
    <template v-if="isSingle && one">
      <div class="one-main">
        <a class="one-thumb" :href="oneHref" target="_blank" rel="noopener"
           :download="oneCanPreview ? null : one.filename"
           :title="oneCanPreview ? '预览 ' + one.filename : '下载 ' + one.filename"
           v-bind="pvAttrs(one)">
          <artifact-thumb :file="one" :href-of="hrefOf" :shared="shared" :mobile="mobile" />
        </a>
        <div class="one-info">
          <!-- 动作统一放卡片右上角：多件卡在标题行右侧，单件卡在标题右侧，同一套位置语言 -->
          <div class="one-top">
            <div class="one-title" :title="oneTitle">{{ oneTitle }}</div>
            <div class="one-actions">
              <template v-if="shared">
                <a class="dlv-btn primary one-icon-btn" :href="oneHref" target="_blank" rel="noopener"
                   :title="oneCanPreview ? '打开文件' : '下载'"
                   :aria-label="oneCanPreview ? '打开文件' : '下载'">
                  <export-outlined v-if="oneCanPreview" />
                  <download-outlined v-else />
                </a>
                <a v-if="oneCanPreview" class="dlv-btn ghost one-icon-btn" :href="oneHref"
                   :download="one.filename" target="_blank" rel="noopener" title="下载" aria-label="下载">
                  <download-outlined />
                </a>
              </template>
              <template v-else>
                <a v-if="oneCanPreview" class="dlv-btn primary one-icon-btn" :href="oneHref" target="_blank"
                   rel="noopener" v-bind="pvAttrs(one)" title="打开预览" aria-label="打开预览">
                  <eye-outlined />
                </a>
                <a v-else class="dlv-btn primary one-icon-btn" :href="oneHref" :download="one.filename"
                   target="_blank" rel="noopener" title="下载" aria-label="下载">
                  <download-outlined />
                </a>
                <a v-if="oneCanPreview" class="dlv-btn ghost one-icon-btn" :href="oneHref"
                   :download="one.filename" target="_blank" rel="noopener" title="下载" aria-label="下载">
                  <download-outlined />
                </a>
              </template>
            </div>
          </div>
          <div v-if="oneFileLine" class="one-file" :title="one.filename">{{ one.filename }}</div>
          <div class="one-meta">
            <span class="one-chip">{{ oneExt }}</span>
            <span v-if="oneSize" class="one-chip">{{ oneSize }}</span>
            <span class="one-chip ok">已交付</span>
          </div>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="dlv-head">
        <folder-open-outlined class="dlv-ic" />
        <span class="dlv-name" :title="delivery.name">{{ delivery.name }}</span>
        <span v-if="delivery.versionLabel" class="dlv-ver">{{ delivery.versionLabel }}</span>
        <span v-if="delivery.stateLabel" class="dlv-badge" :class="'tone-' + (delivery.badgeTone || '')">
          {{ delivery.stateLabel }}
        </span>
        <!-- 动作收进标题行：和单件卡一样只占图标位，不再单独压一条动作栏 -->
        <div class="dlv-head-acts">
          <template v-if="shared">
            <a v-if="delivery.entry" class="dlv-icon-btn primary"
               :href="hrefOf(delivery.entry)" target="_blank" rel="noopener"
               :title="entryTip" :aria-label="entryTip">
              <export-outlined v-if="canPreview(delivery.entry)" />
              <download-outlined v-else />
            </a>
            <a v-if="delivery.entry && canPreview(delivery.entry)" class="dlv-icon-btn ghost"
               :href="hrefOf(delivery.entry)" :download="delivery.entry.filename"
               target="_blank" rel="noopener" title="下载入口文件" aria-label="下载入口文件">
              <download-outlined />
            </a>
          </template>
          <template v-else>
            <a v-if="delivery.entry && canPreview(delivery.entry)" class="dlv-icon-btn primary"
               :href="hrefOf(delivery.entry)" target="_blank" rel="noopener"
               v-bind="pvAttrs(delivery.entry)" :title="entryTip" aria-label="打开入口">
              <eye-outlined />
            </a>
            <a v-else-if="delivery.entry" class="dlv-icon-btn primary"
               :href="hrefOf(delivery.entry)" :download="delivery.entry.filename"
               target="_blank" rel="noopener" :title="entryTip" aria-label="下载入口文件">
              <download-outlined />
            </a>
            <button v-if="!shared" type="button" class="dlv-icon-btn ghost"
                    title="下载整包 ZIP" aria-label="下载整包 ZIP" @click="$emit('zip', delivery)">
              <file-zip-outlined />
            </button>
          </template>
        </div>
      </div>

      <div class="dlv-hero">
        <a v-if="delivery.entry && canPreview(delivery.entry)"
           class="dlv-thumb" :href="hrefOf(delivery.entry)" target="_blank" rel="noopener"
           :title="shared ? '打开 ' + delivery.entry.filename : '预览 ' + delivery.entry.filename"
           v-bind="pvAttrs(delivery.entry)">
          <artifact-thumb :file="delivery.entry" :href-of="hrefOf" :shared="shared" :mobile="mobile" />
        </a>
        <div v-else class="dlv-thumb" aria-hidden="true">
          <div class="t-bar"><i /><i /><i /></div>
          <div class="t-body"><i class="t-hi" /><i class="t-l" /><i class="t-l w60" /></div>
        </div>
        <div class="dlv-main">
          <div class="dlv-entry">打开入口 · {{ delivery.entry ? delivery.entry.filename : '暂无入口' }}</div>
          <div class="dlv-desc">
            {{ delivery.fileCount }} 个文件 · {{ delivery.sizeLabel }}<template v-if="delivery.round"> · 第 {{ delivery.round }} 轮交付</template>
          </div>
          <div class="dlv-chips">
            <span v-if="delivery.pageCount" class="dlv-chip">页面 {{ delivery.pageCount }}</span>
            <span v-if="delivery.siteCount" class="dlv-chip">站点文件 {{ delivery.siteCount }}</span>
            <span v-if="delivery.approved" class="dlv-chip ok">目标已完成</span>
            <span v-else-if="delivery.scope === 'goal'" class="dlv-chip warn">未验收</span>
          </div>
        </div>
      </div>

      <div v-if="listAll.length" class="dlv-list">
        <div v-for="f in shownFiles" :key="fileKey(f)" class="dlv-row">
          <file-text-outlined class="dlv-rowic" />
          <span class="dlv-fname" :title="f.description || f.filename">{{ f.filename }}</span>
          <span class="dlv-ftag">{{ f.tag }}<template v-if="f.size"> · {{ fmtSize(f.size) }}</template></span>
          <span class="dlv-facts">
            <a v-if="!shared && canPreview(f)" :href="hrefOf(f)" target="_blank" rel="noopener" v-bind="pvAttrs(f)"
               :title="'预览 ' + f.filename" aria-label="预览"><eye-outlined /></a>
            <a :href="hrefOf(f)" :download="f.filename" target="_blank" rel="noopener"
               :title="'下载 ' + f.filename" aria-label="下载"><download-outlined /></a>
          </span>
        </div>
        <button v-if="listAll.length > 2" type="button" class="dlv-more" @click="onView">
          {{ viewLabel }}
          <down-outlined v-if="shared" class="dlv-caret" :class="{ up: expanded }" />
          <span v-else class="dlv-more-go">›</span>
        </button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import {
  FolderOpenOutlined, FileTextOutlined, DownloadOutlined, ExportOutlined, DownOutlined,
  EyeOutlined, FileZipOutlined
} from '@ant-design/icons-vue'
import { resolveImg } from '../utils/markdown'
import { canPreview, artifactExt, previewKindOf } from '../utils/artifactView'
import { fmtSize } from '../chat/projections'
import ArtifactThumb from './ArtifactThumb.vue'

const props = defineProps({
  /** deliveryOf() 产出的交付包视图（见 chat/projections.js） */
  delivery: { type: Object, default: null },
  /** 分享页：无登录态，不给预览台与 ZIP，产物只能新标签打开 */
  shared: { type: Boolean, default: false },
  /** H5：缩略不跑网页 iframe */
  mobile: { type: Boolean, default: false },
  /** 实时到达的产物：完成卡播一次入场；历史恢复不置位 */
  fresh: { type: Boolean, default: false },
  /** 产物地址解析：登录态走 resolveImg；分享页按分享 token 现换（见 SharedSessionPage） */
  hrefOf: { type: Function, default: a => resolveImg((a && a.url) || '') }
})
const emit = defineEmits(['view', 'zip'])

const isSingle = computed(() => {
  const d = props.delivery
  return !!d && d.scope !== 'goal' && d.fileCount === 1 && Array.isArray(d.files) && d.files.length === 1
})
const one = computed(() => (isSingle.value ? props.delivery.files[0] : null))
const oneTitle = computed(() => {
  const f = one.value
  if (!f) return ''
  const desc = String(f.description || '').trim()
  return desc || String(f.filename || '')
})
const oneFileLine = computed(() => {
  const f = one.value
  return f && f.description ? String(f.filename || '') : ''
})
const oneExt = computed(() => (artifactExt(one.value || {}) || 'FILE').toUpperCase())
const oneSize = computed(() => fmtSize(one.value && one.value.size))
const oneCanPreview = computed(() => canPreview(one.value || {}))
const oneHref = computed(() => props.hrefOf(one.value || {}))

/** 预览台的委托属性：只在「登录态 + 该类型可预览」时挂，避免给出点不开的入口 */
const pvAttrs = f => {
  if (props.shared || !canPreview(f || {})) return {}
  return {
    'data-pv': '',
    'data-pv-name': f.filename,
    'data-pv-ext': artifactExt(f),
    'data-pv-kind': previewKindOf(f),
    'data-pv-size': f.size,
    'data-pv-id': f.id
  }
}

/** 分享页没有抽屉：「查看全部」就地展开；对话里则交给面板 */
const expanded = ref(false)
/** 清单只列「除入口之外」的文件：入口已经在 hero 里，再列一遍就是同名两行 */
const listAll = computed(() => {
  const d = props.delivery
  const files = (d && d.files) || []
  const entryKey = d && d.entry ? fileKey(d.entry) : ''
  return files.filter(f => fileKey(f) !== entryKey)
})
const shownFiles = computed(() => {
  const list = listAll.value
  return (props.shared && expanded.value) ? list : list.slice(0, 2)
})
const viewLabel = computed(() => {
  const d = props.delivery
  if (!d) return ''
  if (props.shared) return expanded.value ? '收起清单' : `查看全部 ${d.fileCount} 件`
  return `查看全部 ${d.fileCount} 件`
})
const onView = () => { if (props.shared) expanded.value = !expanded.value; else emit('view', props.delivery) }
const entryTip = computed(() => {
  const name = props.delivery && props.delivery.entry ? props.delivery.entry.filename : ''
  if (props.shared) return '打开 ' + name + '（新标签页，下载地址随分享有效期）'
  return canPreview((props.delivery && props.delivery.entry) || {})
    ? '单页预览：入口文件可看，站内相对链接暂不可用（整站预览在 P2）'
    : '下载入口文件'
})
// 分享页的产物只有 seq，没有 id/url；仅按文件名做键会把同名文件当成同一件
const fileKey = f => {
  if (!f) return ''
  if (f.id != null) return 'id:' + f.id
  if (f.seq != null) return 'seq:' + f.seq
  return f.url ? 'url:' + f.url : 'name:' + (f.filename || '')
}
</script>

<style scoped>
/* 交付卡：气泡内宽、浅底白卡；状态色只落在徽标/描边，不整卡换色 */
.dlv {
  margin-top: 9px;
  max-width: 640px;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  background: var(--app-panel);
  overflow: hidden;
  box-shadow: var(--app-shadow-sm, 0 2px 8px rgba(16, 24, 40, .05));
}
.dlv.tone-warn { border-color: var(--app-warn-border); }
.dlv.enter { animation: dlv-in .18s cubic-bezier(.2, .7, .3, 1) both; }
@keyframes dlv-in {
  from { opacity: 0; transform: translateY(7px); }
  to { opacity: 1; transform: translateY(0); }
}
@media (prefers-reduced-motion: reduce) {
  .dlv.enter { animation: none; }
}

/* ============ 单件：左缩略 + 信息 + 两个动作 ============ */
.one-main { display: flex; align-items: stretch; gap: 12px; padding: 10px 12px; }
.one-thumb {
  width: 112px; height: 84px; flex: none;
  border: 1px solid var(--app-border); border-radius: 8px;
  overflow: hidden; display: block; background: var(--app-panel-2);
  text-decoration: none;
}
.one-thumb:hover { border-color: var(--app-accent-border); }
.one-info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
/* 标题 + 动作同一行：动作固定在卡片右上角，与多件包的标题行动作同一位置 */
.one-top { display: flex; align-items: center; gap: 8px; }
.one-title {
  flex: 1; min-width: 0;
  font-size: 13px; font-weight: 650; line-height: 1.45; color: var(--app-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.one-file {
  margin-top: 2px;
  font-family: var(--app-mono, ui-monospace, Menlo, monospace);
  font-size: 10.5px; color: var(--app-text3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
/* chips 压到缩略图底部，和右上角动作拉开层次 */
.one-meta { margin-top: auto; padding-top: 6px; display: flex; align-items: center; gap: 5px; flex-wrap: wrap; }
.one-chip {
  font-size: 9.5px; color: var(--app-text3);
  background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 999px; padding: 0 6px; line-height: 1.7;
}
.one-chip.ok { color: var(--app-ok); background: var(--app-ok-weak); border-color: var(--app-ok-border, #cfe4d3); }
.one-actions { flex: none; display: inline-flex; align-items: center; gap: 6px; }
.one-actions .dlv-btn {
  padding: 4px 9px; font-size: 11px; line-height: 1.25; border-radius: 7px;
  min-height: 24px;
}
.one-actions .dlv-btn:focus-visible { outline: 2px solid var(--app-accent-border); outline-offset: 2px; }
.one-actions .one-icon-btn { padding: 4px 7px; }
.one-actions .one-icon-btn .anticon { font-size: 13px; }

/* ============ 多件：包形态 ============ */
.dlv-head {
  display: flex; align-items: center; gap: 8px;
  padding: 9px 13px;
  border-bottom: 1px solid var(--app-border);
  background: var(--app-panel-2);
}
.dlv-ic { color: var(--app-accent); flex: none; font-size: 15px; }
.dlv-name {
  font-size: 13px; font-weight: 600; min-width: 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.dlv-ver {
  flex: none;
  font-family: var(--app-mono, ui-monospace, Menlo, monospace);
  font-size: 10px;
  color: var(--app-text3);
  border: 1px solid var(--app-border);
  border-radius: 4px;
  padding: 0 4px;
}
.dlv-badge {
  flex: none;
  font-size: 10.5px;
  border-radius: 999px;
  padding: 1px 8px;
  color: var(--app-accent);
  background: var(--app-accent-weak);
  border: 1px solid var(--app-accent-border);
}
.dlv-badge.tone-ok { color: var(--app-ok); background: var(--app-ok-weak); border-color: var(--app-ok-border, #cfe4d3); }
.dlv-badge.tone-warn { color: var(--app-warn-text); background: var(--app-warn-weak); border-color: var(--app-warn-border); }
.dlv-badge.tone-muted { color: var(--app-text3); background: var(--app-panel-2); border-color: var(--app-border); }

/* 包级动作：图标按钮收在标题行右端，与单件卡同一套尺寸 */
.dlv-head-acts { margin-left: auto; flex: none; display: inline-flex; align-items: center; gap: 5px; }
.dlv-icon-btn {
  width: 26px; height: 24px; flex: none; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 7px; border: 1px solid transparent; background: transparent;
  color: var(--app-text2); cursor: pointer; text-decoration: none;
}
.dlv-icon-btn .anticon { font-size: 13px; }
.dlv-icon-btn.primary { background: var(--app-accent); color: #fff; }
.dlv-icon-btn.primary:hover { filter: brightness(1.04); }
.dlv-icon-btn.ghost { background: var(--app-panel); border-color: var(--app-border-2, var(--app-border)); }
.dlv-icon-btn.ghost:hover { border-color: var(--app-accent); color: var(--app-accent); }
.dlv-icon-btn:focus-visible { outline: 2px solid var(--app-accent-border); outline-offset: 2px; }

.dlv-hero { display: flex; gap: 12px; padding: 12px 13px 10px; }
.dlv-thumb {
  width: 128px; height: 82px; flex: none;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-panel);
  overflow: hidden;
  display: block;
}
a.dlv-thumb { text-decoration: none; }
a.dlv-thumb:hover { border-color: var(--app-accent-border); }
.t-bar { height: 13px; background: var(--app-panel-2); border-bottom: 1px solid var(--app-border); display: flex; align-items: center; gap: 3px; padding: 0 5px; }
.t-bar i { width: 4px; height: 4px; border-radius: 50%; background: var(--app-border-2, #c9cfd8); }
.t-body { padding: 8px; }
.t-body i { display: block; height: 5px; border-radius: 2px; background: var(--app-panel-2); margin-bottom: 4px; border: 1px solid var(--app-border); }
.t-body .t-hi { background: var(--app-accent-weak); border-color: var(--app-accent-border); width: 45%; }
.t-body .w60 { width: 60%; }
.dlv-main { min-width: 0; flex: 1; }
.dlv-entry { font-size: 13px; font-weight: 600; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dlv-desc { margin-top: 2px; font-size: 11.5px; color: var(--app-text2); line-height: 1.65; }
.dlv-chips { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 7px; }
.dlv-chip {
  font-size: 10px;
  color: var(--app-text3);
  background: var(--app-panel-2);
  border: 1px solid var(--app-border);
  border-radius: 999px;
  padding: 1px 7px;
}
.dlv-chip.ok { color: var(--app-ok); background: var(--app-ok-weak); border-color: var(--app-ok-border, #cfe4d3); }
.dlv-chip.warn { color: var(--app-warn-text); background: var(--app-warn-weak); border-color: var(--app-warn-border); }

.dlv-btn {
  display: inline-flex; align-items: center; gap: 5px;
  border-radius: 8px;
  padding: 6px 11px;
  font-size: 11.5px;
  border: 1px solid transparent;
  white-space: nowrap;
  text-decoration: none;
}
.dlv-btn.primary { background: var(--app-accent); color: #fff; }
.dlv-btn.primary:hover { text-decoration: none; filter: brightness(1.04); }
.dlv-btn.ghost { background: var(--app-panel); border-color: var(--app-border-2, var(--app-border)); color: var(--app-text2); }
.dlv-btn.ghost:hover { border-color: var(--app-accent); color: var(--app-accent); }
.dlv-caret { font-size: 11px; transition: transform .15s; }
.dlv-caret.up { transform: rotate(180deg); }

.dlv-list { border-top: 1px dashed var(--app-border); padding: 7px 10px 9px; }
.dlv-more {
  display: flex; align-items: center; gap: 3px;
  margin: 2px 0 0 auto; padding: 4px 6px;
  background: transparent; border: 0; border-radius: 6px;
  font: inherit; font-size: 10.5px; color: var(--app-text2); cursor: pointer;
}
.dlv-more:hover { color: var(--app-accent); background: var(--app-accent-weak); }
.dlv-more-go { font-size: 12px; line-height: 1; }
.dlv-row {
  display: flex; align-items: center; gap: 8px;
  padding: 5px 6px;
  border-radius: 6px;
  font-size: 11.5px;
}
.dlv-row:hover { background: var(--app-accent-weak); }
.dlv-rowic { color: var(--app-accent); flex: none; font-size: 13px; }
.dlv-fname {
  color: var(--app-accent);
  font-weight: 500;
  min-width: 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.dlv-ftag { font-size: 10px; color: var(--app-text3); flex: none; }
.dlv-facts { margin-left: auto; flex: none; display: inline-flex; align-items: center; gap: 8px; color: var(--app-text3); opacity: 0; transition: opacity .12s; }
.dlv-facts a { display: inline-flex; align-items: center; color: var(--app-text3); text-decoration: none; }
.dlv-facts .anticon { font-size: 12px; }
.dlv-facts a:hover { color: var(--app-accent); }
.dlv-row:hover .dlv-facts { opacity: 1; }
/* 触屏没有 hover：动作常驻（分享页同样常驻），热区靠行高与间距撑开 */
@media (hover: none) {
  .dlv-facts { opacity: 1; }
}
.dlv.shared .dlv-facts { opacity: 1; }

@media (max-width: 768px) {
  .dlv { max-width: 100%; }
  .dlv-thumb { width: 104px; height: 70px; }
  .one-thumb { width: 92px; height: 70px; }
  .one-main { gap: 10px; padding: 9px 11px; }
  .one-actions .dlv-btn { padding: 5px 9px; min-height: 28px; }
  .one-actions .one-icon-btn { padding: 5px 8px; }
  .dlv-icon-btn { width: 28px; height: 28px; }
  .dlv-btn { padding: 6px 9px; }
}
</style>
