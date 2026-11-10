<template>
  <!-- 更新说明：装之前先看「这次改了什么」。
       技能=整包覆盖（有损）；应用模板/工作流=新增副本（旧副本保留）。 -->
  <a-modal :open="open" :title="'更新「' + (item.name || '') + '」'" :width="620" :footer="null" destroy-on-close
           @cancel="$emit('update:open', false)">
    <div class="um-head">
      <div class="um-delta">
        已装 <b>{{ fmtVersion(item.installedVersion) || '—' }}</b>
        <span class="um-arrow">→</span>
        更新到 <b>{{ fmtVersion(item.version) }}</b>
      </div>
    </div>

    <a-spin v-if="loading" style="display:block;margin:28px auto" />
    <template v-else>
      <div class="um-sub">这次改了什么</div>
      <div v-if="notes.length" class="um-log">
        <div v-for="(n, i) in notes" :key="i" class="um-log-item">
          <div class="um-log-head">
            <span class="um-ver">{{ fmtVersion(n.version) }}</span>
            <span class="um-date">{{ fmtDate(n.createTime) }}</span>
          </div>
          <div v-if="n.releaseNote" class="um-note">{{ n.releaseNote }}</div>
          <div v-else class="um-note muted">作者没有填写这次更新说明</div>
        </div>
      </div>
      <div v-else class="um-empty">作者没有填写这次更新说明</div>

      <p v-if="!matched" class="um-older">
        已装版本较早，这里只展示最近 {{ Math.min(notes.length, 3) }} 次更新的说明。
      </p>

      <div v-if="isSkill" class="um-warn">
        <warning-outlined class="um-warn-ic" />
        <span>整包替换你装好的同名技能：装好后改过的内容，以及旧版本附带的参考文件与脚本会丢失。</span>
      </div>
      <div v-else class="um-info">
        <info-circle-outlined class="um-info-ic" />
        <span>会生成一份新副本，旧副本保留，不会覆盖你现在用的那份。</span>
      </div>

      <div class="um-foot">
        <button class="app-btn ghost" @click="$emit('update:open', false)">取消</button>
        <button class="app-btn" @click="$emit('confirm', item)">{{ isSkill ? '更新' : '装新版' }}</button>
      </div>
    </template>
  </a-modal>
</template>

<script setup>
import { computed } from 'vue'
import { InfoCircleOutlined, WarningOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  item: { type: Object, default: () => ({}) },
  /** { items, newItems, matched }：后端按内容指纹切出的版本区间 */
  versions: { type: Object, default: () => ({}) },
  loading: { type: Boolean, default: false }
})
defineEmits(['update:open', 'confirm'])

const isSkill = computed(() => props.item?.itemType === 'skill')
const notes = computed(() => {
  const v = props.versions || {}
  const list = Array.isArray(v.newItems) ? v.newItems : []
  // 匹配不到已装版本时展示最近 3 条，避免一次糊一屏历史
  return v.matched ? list : list.slice(0, 3)
})
const matched = computed(() => !!props.versions?.matched)
const fmtVersion = v => {
  const s = (v || '').trim()
  if (!s) return ''
  return /^v/i.test(s) ? s : 'v' + s
}
const fmtDate = v => {
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return ''
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
</script>

<style scoped>
.um-head { padding-right: 24px; }
.um-title { font-size: 15px; font-weight: 600; color: var(--app-text); }
.um-delta { margin-top: 6px; font-size: 12px; color: var(--app-text3); }
.um-delta b { color: var(--app-text); font-weight: 600; }
.um-arrow { margin: 0 6px; color: var(--app-text3); }
.um-sub { margin: 14px 0 6px; font-size: 12px; color: var(--app-text2); font-weight: 600; }
.um-log { border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  padding: 10px 12px; max-height: 260px; overflow-y: auto; }
.um-log-item + .um-log-item { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--app-border); }
.um-log-head { display: flex; align-items: baseline; gap: 8px; }
.um-ver { font-size: 12px; font-weight: 600; color: var(--app-text); }
.um-date { font-size: 11px; color: var(--app-text3); }
.um-note { margin-top: 4px; font-size: 12px; line-height: 1.75; color: var(--app-text2); white-space: pre-wrap; }
.um-note.muted { color: var(--app-text3); }
.um-empty { border: 1px dashed var(--app-border); border-radius: 8px; padding: 14px;
  text-align: center; font-size: 12px; color: var(--app-text3); }
.um-older { margin: 8px 0 0; font-size: 11.5px; color: var(--app-text3); }
.um-warn { display: flex; align-items: flex-start; gap: 7px; margin-top: 12px; padding: 10px 12px;
  border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 8px;
  font-size: 12px; line-height: 1.7; color: var(--app-warn-text); }
.um-warn-ic { flex: none; margin-top: 3px; }
.um-info { display: flex; align-items: flex-start; gap: 7px; margin-top: 12px; padding: 10px 12px;
  border: 1px solid var(--app-accent-border); background: var(--app-accent-weak); border-radius: 8px;
  font-size: 12px; line-height: 1.7; color: var(--app-text2); }
.um-info-ic { flex: none; margin-top: 3px; color: var(--app-accent); }
.um-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
