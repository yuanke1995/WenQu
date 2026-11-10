<template>
  <!-- 上架到市场：技能 / 应用模板（智能体）/ 工作流三类型共用一个弹窗。
       MarketplacePage 的「上架」与技能页卡片上的「上架」共用这里（后者带 presetName 预选技能）。
       重新上架=更新同一条：必须写清「这次改了什么」，装过的人会在更新说明里看到。 -->
  <a-modal :open="open" title="上架到市场" :footer="null" :width="880" destroy-on-close
           @cancel="$emit('update:open', false)">
    <a-spin v-if="loadingOptions" style="display:block;margin:36px auto" />
    <template v-else>
      <!-- 类型切换永远可见：某个类型没有可上架内容时，仍要能切到别的类型（否则入口被空态挡住） -->
      <div class="mp-type-row">
        <button v-for="t in TYPES" :key="t.key" class="mp-type-btn" type="button"
                :class="{ on: form.type === t.key }" @click="switchType(t.key)">{{ t.label }}</button>
      </div>
      <p v-if="!hasOptions" class="mp-hint">{{ emptyHint }}</p>
      <template v-else>
        <div class="mp-grid">
          <a-form layout="vertical">
            <a-form-item :label="typeMeta.label" required>
              <!-- 选项=名称加粗 + 描述单行截断 + 已上架标注（描述全文拼 label 会变成一堵字墙）。
                   label 用 VNode 且样式内联：下拉与收起态选择器共用一套渲染，也不依赖 scoped 作用域。 -->
              <a-select v-model:value="form.ref" :placeholder="typeMeta.placeholder" show-search
                        option-filter-prop="search" :options="options" />
            </a-form-item>
            <a-form-item label="一句话简介" required>
              <a-input v-model:value="form.summary" :maxlength="256" show-count
                       placeholder="列表卡片上的一句话；留空取该条目自带的描述" />
            </a-form-item>
            <a-form-item label="详细介绍">
              <a-textarea v-model:value="form.description" :rows="4"
                          :placeholder="typeMeta.detailHint" />
            </a-form-item>
            <a-form-item label="标签">
              <a-input v-model:value="form.tags" placeholder="逗号或顿号分隔，如：写作, 周报, 格式" />
            </a-form-item>
            <a-form-item label="版本号">
              <a-input v-model:value="form.version" :maxlength="32"
                       :placeholder="isUpdate ? '留空沿用当前版本' : '如 1.1；留空记为 1（仅展示用）'" />
            </a-form-item>
            <a-form-item v-if="isUpdate" label="这次改了什么" required>
              <a-textarea v-model:value="form.releaseNote" :rows="3" :maxlength="4000" show-count
                          placeholder="装过的人会在更新说明里看到这段话；只改简介或标签请用「编辑信息」" />
            </a-form-item>
          </a-form>
          <div class="mp-preview">
            <div class="mp-preview-label">卡片效果预览</div>
            <div class="mp-card">
              <div class="mp-card-head">
                <span class="mp-type">{{ typeMeta.label }}</span>
                <span class="mp-name">{{ previewName }}</span>
              </div>
              <div class="mp-desc">{{ form.summary.trim() || '一句话简介会显示在这里' }}</div>
              <div class="mp-tags">
                <span v-for="t in previewTags" :key="t" class="mp-tag">{{ t }}</span>
              </div>
              <div class="mp-meta">
                <span>{{ isUpdate ? '更新同一条' : '新上架' }}</span>
                <span>{{ fmtVersion(form.version) || 'v1' }}</span>
              </div>
            </div>
            <p v-if="isUpdate" class="mp-preview-note">这条内容已经在市场里，发布会更新它并生成一条更新说明。</p>
          </div>
        </div>
        <div class="key-modal-foot">
          <button class="app-btn ghost" @click="$emit('update:open', false)">取消</button>
          <button class="app-btn" :disabled="publishing || !form.ref || (isUpdate && !form.releaseNote.trim())"
                  @click="submit">
            {{ publishing ? '发布中…' : (isUpdate ? '发布新版本' : '上架') }}
          </button>
        </div>
      </template>
    </template>
  </a-modal>
</template>

<script setup>
import { computed, h, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  getMarketItem, listAgents, listMarketItems, listSkills, listWorkflows, publishMarketItem
} from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 预选技能名（技能页卡片上的「上架」带过来；非技能入口不传） */
  presetName: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'published'])

const TYPES = [
  { key: 'skill', label: '技能', item: '固化的「做法」说明书' },
  { key: 'agent', label: '应用模板', item: '配好的智能体（人设/知识/工具）' },
  { key: 'workflow', label: '工作流', item: '画好的流程图' }
]

const loadingOptions = ref(false)
const optionsByType = ref({ skill: [], agent: [], workflow: [] })
const mineItems = ref([])
const publishing = ref(false)
const form = ref({ type: 'skill', ref: '', summary: '', description: '', tags: '', version: '', releaseNote: '' })

const typeMeta = computed(() => ({
  skill: {
    label: '技能', placeholder: '选择要上架的技能（仅列出你自己安装的）',
    detailHint: '使用场景、用法与前置要求（Markdown），详情页可见。'
  },
  agent: {
    label: '应用模板', placeholder: '选择要上架的智能体（仅列出你可管理的）',
    detailHint: '用途、适用人群与前置条件（Markdown），详情页可见。'
  },
  workflow: {
    label: '工作流', placeholder: '选择要上架的工作流（仅列出你可管理的）',
    detailHint: '这个流程解决什么问题、输入输出是什么（Markdown）。安装的人在详情页看到。'
  }
})[form.value.type] || {})
const emptyHint = computed(() => '还没有可上架的「' + (TYPES.find(t => t.key === form.value.type)?.item || '内容') + '」。先去准备一个，再回来上架。')
const hasOptions = computed(() => (optionsByType.value[form.value.type] || []).length > 0)
const options = computed(() => optionsByType.value[form.value.type] || [])

/** 已上架条目按「类型:标识」索引：选项右侧标注「已上架/已下架」（重复上架=更新同一条） */
const mineByKey = computed(() => {
  const m = {}
  for (const it of mineItems.value) m[it.itemType + ':' + (it.itemKey || it.name)] = it
  return m
})

const selectedOption = computed(() => options.value.find(o => o.value === form.value.ref) || null)
const isUpdate = computed(() => !!selectedOption.value?.existing)
const previewName = computed(() => selectedOption.value?.name || '内容名称')
const previewTags = computed(() => form.value.tags.split(/[,，、\s]+/).map(t => t.trim()).filter(Boolean).slice(0, 4))
const fmtVersion = v => {
  const s = (v || '').trim()
  if (!s) return ''
  return /^v/i.test(s) ? s : 'v' + s
}

const switchType = t => {
  if (form.value.type === t) return
  form.value = { type: t, ref: '', summary: '', description: '', tags: '', version: '', releaseNote: '' }
}

/** 选项 label（VNode，下拉与收起态共用）：名称 + 单行截断描述 + 已上架标注 */
const optLabel = (name, desc, pub) => h('span', { style: 'display:flex;align-items:center;gap:8px;min-width:0;max-width:100%;' }, [
  h('span', { style: 'flex:none;font-weight:600;' }, name),
  h('span', {
    style: 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'
      + 'font-size:12px;color:var(--app-text3);'
  }, desc || '（无描述）'),
  pub ? h('span', {
    style: 'flex:none;font-size:11px;line-height:18px;padding:0 8px;border-radius:999px;'
      + 'background:var(--app-panel-2);color:' + (pub.online ? 'var(--app-text2)' : 'var(--app-warn-text)') + ';'
  }, pub.online ? '已上架' : '已下架') : null
])

watch(() => props.open, async open => {
  if (!open) return
  form.value = {
    type: 'skill', ref: props.presetName || '',
    summary: '', description: '', tags: '', version: '', releaseNote: ''
  }
  loadingOptions.value = true
  try {
    const [skillRes, agentRes, wfRes, mineRes] = await Promise.all([
      listSkills(), listAgents(), listWorkflows(), listMarketItems({ mine: true, pageSize: 100 })
    ])
    if (!skillRes || !skillRes.success) throw new Error((skillRes && skillRes.msg) || '读取失败')
    const skills = (skillRes.data?.skills || []).filter(s => s.source === 'user' && !s.platformDisabled)
    const agents = (agentRes?.data || []).filter(a => a.manageable === 1 && a.isBuiltin !== 1)
    // /workflow/list 返回 {mine:[], shared:[]}：可上架=本人的或共享管理命中（与后端 requireManage 同口径）
    const wfAll = [...(wfRes?.data?.mine || []), ...(wfRes?.data?.shared || [])]
    const workflows = wfAll.filter(w => w.mine === true || w.myPermission === 'MANAGE')
    // 先落 mineItems（已上架标注依赖它），再构建选项
    mineItems.value = mineRes?.data?.items || []
    const build = (list, type, nameOf, descOf, idOf) => list.map(x => {
      const name = nameOf(x)
      const existing = mineByKey.value[type + ':' + name] || null
      return {
        value: idOf(x), name, existing,
        search: name + ' ' + (descOf(x) || ''),
        label: optLabel(name, descOf(x), existing)
      }
    })
    optionsByType.value = {
      skill: build(skills, 'skill', s => s.name, s => s.description, s => s.name),
      agent: build(agents, 'agent', a => a.name, a => a.description, a => a.id),
      workflow: build(workflows, 'workflow', w => w.name, w => w.description, w => w.id)
    }
    if (form.value.ref) await prefillFromSelection(++prefillSeq)
  } catch (e) {
    message.error('读取可上架清单失败：' + ((e && e.message) || e))
    emit('update:open', false)
  } finally {
    loadingOptions.value = false
  }
})

/** 重新上架时把已有卡片文案带回来，避免发布时把详细介绍清空 */
let prefillSeq = 0
async function prefillFromSelection(seq) {
  const opt = selectedOption.value
  if (!opt) return
  const ex = opt.existing
  if (!ex) {
    form.value.summary = ''
    form.value.description = ''
    form.value.tags = ''
    form.value.version = ''
    form.value.releaseNote = ''
    return
  }
  form.value.summary = ex.summary || ''
  form.value.tags = Array.isArray(ex.tags) ? ex.tags.join(', ') : (ex.tags || '')
  form.value.version = ex.version || ''
  form.value.releaseNote = ''
  form.value.description = ''
  try {
    const r = await getMarketItem(ex.id)
    if (seq !== prefillSeq) return
    form.value.description = r?.data?.description || ''
  } catch (e) { /* 详情拉不到时保留空，发布前用户可自行补 */ }
}
watch(() => form.value.ref, () => { prefillFromSelection(++prefillSeq) })

const submit = async () => {
  const f = form.value
  if (!f.ref) { message.warning('请先选择要上架的' + (typeMeta.value.label || '内容')); return }
  if (isUpdate.value && !f.releaseNote.trim()) { message.warning('请写清楚这次改了什么'); return }
  publishing.value = true
  try {
    const r = await publishMarketItem({
      type: f.type, ref: f.ref, summary: f.summary.trim(),
      description: f.description, tags: f.tags, version: f.version.trim(),
      releaseNote: f.releaseNote.trim()
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '上架失败')
    message.success(isUpdate.value ? '已发布新版本' : '已上架到市场，可在「市场 → 我的上架」里管理')
    emit('update:open', false)
    emit('published', r.data)
  } catch (e) {
    message.error('上架失败：' + ((e && e.message) || e))
  } finally {
    publishing.value = false
  }
}
</script>

<style scoped>
.mp-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: -6px 0 0; }
.key-modal-foot { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 8px; }
.mp-type-row { display: flex; gap: 8px; margin-bottom: 14px; }
.mp-type-btn { border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text2);
  border-radius: 999px; padding: 4px 14px; font-size: 12px; cursor: pointer; }
.mp-type-btn.on { border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 600; }
.mp-grid { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 20px; }
.mp-preview-label { font-size: 11px; color: var(--app-text3); margin-bottom: 8px; }
.mp-card { border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel-2);
  padding: 12px 14px; }
.mp-card-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.mp-type { flex: none; font-size: 10.5px; line-height: 17px; padding: 0 7px; border-radius: 999px;
  background: var(--app-panel); color: var(--app-text2); }
.mp-name { min-width: 0; font-size: 13px; font-weight: 500; color: var(--app-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mp-desc { margin-top: 7px; min-height: 38px; font-size: 12px; line-height: 19px; color: var(--app-text3);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.mp-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 7px; min-height: 18px; }
.mp-tag { font-size: 10.5px; line-height: 18px; padding: 0 7px; border-radius: 999px;
  background: var(--app-panel); color: var(--app-text2); }
.mp-meta { display: flex; align-items: center; gap: 10px; margin-top: 8px; padding-top: 7px;
  border-top: 1px dashed var(--app-border); font-size: 11px; color: var(--app-text3); }
.mp-preview-note { margin: 8px 0 0; font-size: 11px; line-height: 1.7; color: var(--app-text3); }
</style>
