<template>
  <!--
    交付面板：交付包的事实面（PC 右抽屉 / H5 底部 sheet 共用一份）。
    卡片回答「这是什么、从哪打开、验没验」，这里回答「还有哪些文件、各自多重」并给批量动作。
    分组与「入口」判据全部来自 deliveryOf()，别在两端各写一套。
  -->
  <div v-if="delivery" class="dp">
    <section class="dp-sec">
      <div class="dp-label">主入口</div>
      <div class="dp-entry">
        <div class="dp-thumb" aria-hidden="true">
          <div class="t-bar"><i /><i /><i /></div>
          <div class="t-body"><i class="t-hi" /><i class="t-l" /><i class="t-l w60" /></div>
        </div>
        <div class="dp-entry-main">
          <div class="dp-entry-name">{{ delivery.entry ? delivery.entry.filename : '暂无入口' }}</div>
          <div class="dp-entry-sub">{{ delivery.fileCount }} 个文件 · {{ delivery.sizeLabel }}<template v-if="delivery.round"> · 第 {{ delivery.round }} 轮</template></div>
        </div>
        <a v-if="delivery.entry && canPreview(delivery.entry)" class="dp-btn primary"
           :href="hrefOf(delivery.entry)" target="_blank" rel="noopener" data-pv
           :data-pv-name="delivery.entry.filename" :data-pv-ext="artifactExt(delivery.entry)"
           :data-pv-kind="previewKindOf(delivery.entry)" :data-pv-size="delivery.entry.size"
           :data-pv-id="delivery.entry.id" title="单页预览：入口文件可看，站内相对链接暂不可用">
          打开入口
        </a>
        <a v-else-if="delivery.entry" class="dp-btn primary" :href="hrefOf(delivery.entry)"
           target="_blank" rel="noopener" :download="delivery.entry.filename">打开入口</a>
      </div>
    </section>

    <section class="dp-sec">
      <div class="dp-label">交付信息</div>
      <div class="dp-fact"><span>文件</span><b>{{ delivery.fileCount }} 件 · {{ delivery.sizeLabel }}</b></div>
      <div v-if="delivery.round" class="dp-fact"><span>交付轮次</span><b>第 {{ delivery.round }} 轮</b></div>
      <div v-if="delivery.versionLabel" class="dp-fact"><span>版本</span><b>{{ delivery.versionLabel }}</b></div>
      <div v-if="delivery.stateLabel" class="dp-fact">
        <span>目标状态</span>
        <b :class="'st-' + (delivery.badgeTone || 'none')">{{ delivery.stateLabel }}</b>
      </div>
    </section>

    <section class="dp-sec">
      <div class="dp-label">文件 <span class="dp-n">{{ delivery.fileCount }}</span></div>
      <div v-for="g in delivery.groups" :key="g.key" class="dp-grp">
        <button type="button" class="dp-gh" @click="toggle(g)">
          <span class="dp-gname">{{ g.label }}</span>
          <span class="dp-n">{{ g.files.length }}</span>
          <span class="spacer" />
          <span class="dp-toggle">{{ closedOf(g) ? '展开' : '收起' }}</span>
          <caret-right-outlined class="dp-caret" :class="{ open: !closedOf(g) }" />
        </button>
        <div v-show="!closedOf(g)" class="dp-gbody">
          <div v-for="f in g.files" :key="fileKey(f)" class="dp-row">
            <file-text-outlined class="dp-ic" />
            <span class="dp-fname" :title="f.filename">{{ f.filename }}</span>
            <span class="dp-size">{{ fmtSize(f.size) }}</span>
            <span class="dp-facts">
              <a v-if="canPreview(f)" :href="hrefOf(f)" target="_blank" rel="noopener" data-pv
                 :data-pv-name="f.filename" :data-pv-ext="artifactExt(f)"
                 :data-pv-kind="previewKindOf(f)" :data-pv-size="f.size" :data-pv-id="f.id"
                 :title="'预览 ' + f.filename">预览</a>
              <a :href="hrefOf(f)" :download="f.filename" target="_blank" rel="noopener"
                 :title="'下载 ' + f.filename">下载</a>
            </span>
          </div>
        </div>
      </div>
    </section>

    <div class="dp-foot">
      <button type="button" class="dp-btn primary" @click="$emit('zip', delivery)">
        <download-outlined /> 下载全部（ZIP）
      </button>
      <router-link class="dp-btn ghost" to="/artifacts">在产物页查看</router-link>
    </div>
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { DownloadOutlined, FileTextOutlined, CaretRightOutlined } from '@ant-design/icons-vue'
import { resolveImg } from '../utils/markdown'
import { fmtSize } from '../chat/projections'
import { canPreview, artifactExt, previewKindOf } from '../utils/artifactView'

const props = defineProps({
  /** deliveryOf() 产出的交付包视图 */
  delivery: { type: Object, default: null },
  /** 产物地址解析（与卡片同一条口径；分享页若复用面板则需要传） */
  hrefOf: { type: Function, default: a => resolveImg((a && a.url) || '') }
})
defineEmits(['zip'])

/** 分组展开态：只存例外，流式重算交付包时不丢用户手动收起的那组 */
const openMap = reactive({})
const closedOf = g => (openMap[g.key] !== undefined ? openMap[g.key] : g.closed)
const toggle = g => { openMap[g.key] = !closedOf(g) }
const fileKey = f => (f && (f.id || f.url || f.filename)) || ''
</script>

<style scoped>
.dp { display: flex; flex-direction: column; height: 100%; }
.dp-sec { padding: 12px 16px 0; }
.dp-sec:first-child { padding-top: 14px; }
.dp-label {
  font-size: 11px;
  color: var(--app-text3);
  letter-spacing: .04em;
  margin-bottom: 6px;
}
.dp-n { color: var(--app-text2); font-family: var(--app-mono, ui-monospace, Menlo, monospace); font-size: 10.5px; }
.dp-entry {
  display: flex; align-items: center; gap: 9px;
  border: 1px solid var(--app-accent-border);
  background: var(--app-accent-weak);
  border-radius: 10px;
  padding: 9px 10px;
}
.dp-thumb {
  width: 58px; height: 38px; flex: none;
  border: 1px solid var(--app-border);
  border-radius: 5px;
  background: var(--app-panel);
  overflow: hidden;
}
.dp-thumb .t-bar {
  height: 9px; padding: 0 3px;
  display: flex; align-items: center; gap: 2px;
  background: var(--app-panel-2);
  border-bottom: 1px solid var(--app-border);
}
.dp-thumb .t-bar i { width: 3px; height: 3px; border-radius: 50%; background: var(--app-border-2, #c9cfd8); }
.dp-thumb .t-body { padding: 3px 4px; }
.dp-thumb .t-body i {
  display: block; height: 3px; margin-bottom: 2px;
  border-radius: 2px;
  background: var(--app-panel-2);
  border: 1px solid var(--app-border);
}
.dp-thumb .t-body .t-hi { background: var(--app-accent-weak); border-color: var(--app-accent-border); width: 45%; }
.dp-thumb .t-body .w60 { width: 60%; }
.dp-entry-main { min-width: 0; flex: 1; }
.dp-entry-name { font-size: 12px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dp-entry-sub { font-size: 10.5px; color: var(--app-text2); margin-top: 1px; }
.dp-entry .dp-btn.primary { flex: none; margin-left: auto; }

.dp-btn {
  display: inline-flex; align-items: center; gap: 5px;
  border-radius: 8px;
  padding: 6px 11px;
  font-size: 11.5px;
  border: 1px solid transparent;
  white-space: nowrap;
  text-decoration: none;
}
.dp-btn.primary { background: var(--app-accent); color: #fff; }
.dp-btn.primary:hover { text-decoration: none; filter: brightness(1.04); }
.dp-btn.ghost { background: var(--app-panel); border-color: var(--app-border-2, var(--app-border)); color: var(--app-text2); }
.dp-btn.ghost:hover { border-color: var(--app-accent); color: var(--app-accent); }

.dp-fact {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12px;
  color: var(--app-text2);
  padding: 4px 0;
}
.dp-fact b { font-weight: 500; color: var(--app-text); }
.dp-fact b.st-ok { color: var(--app-ok); }
.dp-fact b.st-warn { color: var(--app-warn-text); }
.dp-fact b.st-live { color: var(--app-accent); }
.dp-fact b.st-muted { color: var(--app-text3); }

.dp-grp { margin-top: 8px; }
.dp-gh {
  width: 100%;
  display: flex; align-items: center; gap: 6px;
  border: 0; background: transparent;
  padding: 5px 2px;
  border-bottom: 1px solid var(--app-border);
  font-size: 11.5px;
  color: var(--app-text2);
  cursor: pointer;
}
.dp-gname { color: var(--app-text); font-weight: 500; }
.dp-toggle { font-size: 10.5px; color: var(--app-text3); }
.dp-caret { font-size: 10px; color: var(--app-text3); transition: transform .15s; }
.dp-caret.open { transform: rotate(90deg); }
.dp-gbody { padding: 2px 0 4px; }
.dp-row {
  display: flex; align-items: center; gap: 7px;
  padding: 6px 2px;
  border-bottom: 1px dashed var(--app-border);
  font-size: 11.5px;
}
.dp-row:last-child { border-bottom: 0; }
.dp-ic { color: var(--app-accent); flex: none; font-size: 13px; }
.dp-fname {
  color: var(--app-accent);
  min-width: 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.dp-size { flex: none; font-size: 10px; color: var(--app-text3); }
.dp-facts { margin-left: auto; flex: none; display: inline-flex; gap: 10px; opacity: 0; transition: opacity .12s; }
.dp-facts a { color: var(--app-text3); font-size: 10.5px; text-decoration: none; }
.dp-facts a:hover { color: var(--app-accent); }
.dp-row:hover .dp-facts { opacity: 1; }
@media (hover: none) { .dp-facts { opacity: 1; } }

.dp-foot {
  margin-top: auto;
  padding: 12px 16px 4px;
  display: flex; gap: 8px;
}
.dp-foot .dp-btn.primary { flex: 1; justify-content: center; }
.dp-foot .dp-btn.ghost { flex: none; }
.dp-tip { padding: 6px 16px 14px; font-size: 10px; color: var(--app-text3); line-height: 1.6; }
</style>
