<template>
  <!--
    交付卡：一组产物 = 一个交付包（入口、体量、分组、批量动作）。
    以前每组产物在气泡底部平铺 N 行「预览/下载」，多文件一次交付就把对话刷成文件管理器；
    现在对话里只留这一张卡，清单与逐件动作退到交付面板（PC 抽屉 / H5 底部 sheet）。

    两种模式：
    - 默认（登录态对话）：预览走全局预览台（data-pv 委托，与旧产物行同一套），下载 ZIP 走产物批量接口；
    - shared（分享页）：没有登录态，产物只能新标签打开/下载（地址由父级按分享 token 现换），不开预览台、不给 ZIP；
      「查看全部」就地展开，因为分享页没有抽屉。
  -->
  <div v-if="delivery" class="dlv" :class="['tone-' + (delivery.badgeTone || 'none'), { shared }]">
    <div class="dlv-head">
      <folder-open-outlined class="dlv-ic" />
      <span class="dlv-name" :title="delivery.name">{{ delivery.name }}</span>
      <span v-if="delivery.versionLabel" class="dlv-ver">{{ delivery.versionLabel }}</span>
      <span v-if="delivery.stateLabel" class="dlv-badge" :class="'tone-' + (delivery.badgeTone || '')">
        {{ delivery.stateLabel }}
      </span>
    </div>

    <div class="dlv-hero">
      <div class="dlv-thumb" aria-hidden="true">
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

    <div class="dlv-acts">
      <!-- 主行动永远指向入口：站点/文档先打开，下载是第二步 -->
      <a v-if="delivery.entry && !shared && canPreview(delivery.entry)"
         class="dlv-btn primary" :href="hrefOf(delivery.entry)" target="_blank" rel="noopener" data-pv
         :data-pv-name="delivery.entry.filename" :data-pv-ext="artifactExt(delivery.entry)"
         :data-pv-kind="previewKindOf(delivery.entry)" :data-pv-size="delivery.entry.size"
         :data-pv-id="delivery.entry.id" :title="entryTip">
        <export-outlined /> 打开入口
      </a>
      <a v-else-if="delivery.entry" class="dlv-btn primary" :href="hrefOf(delivery.entry)"
         target="_blank" rel="noopener" :title="entryTip">
        <export-outlined /> 打开入口
      </a>
      <button v-if="!shared" type="button" class="dlv-btn ghost" title="把这一包文件打成 ZIP 下载" @click="$emit('zip', delivery)">
        <download-outlined /> 下载 ZIP
      </button>
      <button v-if="delivery.fileCount > 2" type="button" class="dlv-btn text" @click="onView">
        {{ viewLabel }}
        <down-outlined class="dlv-caret" :class="{ up: expanded }" />
      </button>
    </div>

    <div class="dlv-list">
      <div v-for="f in shownFiles" :key="fileKey(f)" class="dlv-row">
        <file-text-outlined class="dlv-rowic" />
        <span class="dlv-fname" :title="f.filename">{{ f.filename }}</span>
        <span class="dlv-ftag">{{ f.tag }}</span>
        <span class="dlv-facts">
          <a v-if="!shared && canPreview(f)" :href="hrefOf(f)" target="_blank" rel="noopener" data-pv
             :data-pv-name="f.filename" :data-pv-ext="artifactExt(f)"
             :data-pv-kind="previewKindOf(f)" :data-pv-size="f.size" :data-pv-id="f.id"
             :title="'预览 ' + f.filename">预览</a>
          <a :href="hrefOf(f)" :download="f.filename" target="_blank" rel="noopener"
             :title="'下载 ' + f.filename">下载</a>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { FolderOpenOutlined, FileTextOutlined, DownloadOutlined, ExportOutlined, DownOutlined } from '@ant-design/icons-vue'
import { resolveImg } from '../utils/markdown'
import { canPreview, artifactExt, previewKindOf } from '../utils/artifactView'

const props = defineProps({
  /** deliveryOf() 产出的交付包视图（见 chat/projections.js） */
  delivery: { type: Object, default: null },
  /** 分享页：无登录态，不给预览台与 ZIP，产物只能新标签打开 */
  shared: { type: Boolean, default: false },
  /** 产物地址解析：登录态走 resolveImg；分享页按分享 token 现换（见 SharedSessionPage） */
  hrefOf: { type: Function, default: a => resolveImg((a && a.url) || '') }
})
const emit = defineEmits(['view', 'zip'])

/** 分享页没有抽屉：「查看全部」就地展开；对话里则交给面板 */
const expanded = ref(false)
const shownFiles = computed(() => {
  const list = (props.delivery && props.delivery.files) || []
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
const fileKey = f => (f && (f.id || f.url || f.filename)) || ''
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
  margin-left: auto; flex: none;
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

.dlv-hero { display: flex; gap: 12px; padding: 12px 13px 10px; }
.dlv-thumb {
  width: 128px; height: 82px; flex: none;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-panel);
  overflow: hidden;
}
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

.dlv-acts { display: flex; align-items: center; gap: 8px; padding: 0 13px 11px; flex-wrap: wrap; }
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
.dlv-btn.text { background: transparent; color: var(--app-text2); padding: 6px 4px; }
.dlv-btn.text:hover { color: var(--app-accent); }
.dlv-caret { font-size: 11px; transition: transform .15s; }
.dlv-caret.up { transform: rotate(180deg); }

.dlv-list { border-top: 1px dashed var(--app-border); padding: 7px 10px 9px; }
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
.dlv-facts { margin-left: auto; flex: none; display: inline-flex; gap: 9px; color: var(--app-text3); opacity: 0; transition: opacity .12s; }
.dlv-facts a { color: var(--app-text3); text-decoration: none; font-size: 10.5px; }
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
  .dlv-acts { gap: 6px; }
  .dlv-btn { padding: 6px 9px; }
}
</style>
