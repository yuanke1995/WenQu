<template>
  <!-- 上架到市场：技能 / 应用模板（智能体）/ 工作流三类型共用一个弹窗。
       MarketplacePage 的「上架」与技能页卡片上的「上架」共用这里（后者带 presetName 预选技能）。 -->
  <a-modal :open="open" title="上架到市场" :footer="null" :width="640" destroy-on-close
           @cancel="$emit('update:open', false)">
    <a-spin v-if="loadingOptions" style="display:block;margin:36px auto" />
    <template v-else>
      <p v-if="!hasOptions" class="mk-form-hint">{{ emptyHint }}</p>
      <template v-else>
        <div class="mk-type-row">
          <button v-for="t in TYPES" :key="t.key" class="mk-type-btn" type="button"
                  :class="{ on: form.type === t.key }" @click="switchType(t.key)">{{ t.label }}</button>
        </div>
        <a-form layout="vertical">
          <a-form-item :label="typeMeta.label" required>
            <!-- 选项=名称加粗 + 描述单行截断 + 已上架标注（描述全文拼 label 会变成一堵字墙）。
                 label 用 VNode 且样式内联：下拉与收起态选择器共用一套渲染，也不依赖 scoped 作用域。
                 搜索走 search 字段命中名称+描述 -->
            <a-select v-model:value="form.ref" :placeholder="typeMeta.placeholder" show-search
                      option-filter-prop="search" :options="options" />
          </a-form-item>
          <a-form-item label="一句话简介" required>
            <a-input v-model:value="form.summary" :maxlength="256" show-count
                     placeholder="列表卡片上的一句话；留空取该条目自带的描述" />
          </a-form-item>
          <a-form-item label="详细介绍（可选）">
            <a-textarea v-model:value="form.description" :rows="5"
                        :placeholder="typeMeta.detailHint" />
          </a-form-item>
          <a-form-item label="标签（可选）">
            <a-input v-model:value="form.tags" placeholder="逗号或顿号分隔，如：写作, 周报, 格式" />
          </a-form-item>
          <a-form-item label="版本号（可选）">
            <a-input v-model:value="form.version" :maxlength="32" placeholder="如 1.1；留空记为 1（仅展示用）" />
          </a-form-item>
        </a-form>
        <p class="mk-form-hint" v-html="typeHint"></p>
        <div class="key-modal-foot">
          <button class="app-btn ghost" @click="$emit('update:open', false)">取消</button>
          <button class="app-btn" :disabled="publishing || !form.ref" @click="submit">
            {{ publishing ? '上架中…' : '上架' }}
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
  listAgents, listMarketItems, listSkills, listWorkflows, publishMarketItem
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
  { key: 'workflow', label: '工作流', item: '画好的流程图（DSL）' }
]

const loadingOptions = ref(false)
const optionsByType = ref({ skill: [], agent: [], workflow: [] })
const mineItems = ref([])
const publishing = ref(false)
const form = ref({ type: 'skill', ref: '', summary: '', description: '', tags: '', version: '' })

const typeMeta = computed(() => ({
  skill: {
    label: '技能', placeholder: '选择要上架的技能（仅列出你自己安装的）',
    detailHint: '什么场景用它、怎么用、有什么前置要求（Markdown）。安装的人在详情页看到。',
    hint: '上架的是<b>当前内容的快照</b>：之后你改了自己的技能，市场不会自动跟着变——重新上架一次即可。装过的人是否看到「有更新」由内容变化自动判定，与版本号无关。'
  },
  agent: {
    label: '智能体', placeholder: '选择要上架的智能体（仅列出你可管理的）',
    detailHint: '这个助手是干什么的、适合谁用、有什么前置条件（Markdown）。安装的人在详情页看到。',
    hint: '上架<b>配置快照</b>（人设/知识范围/工具开关/技能与 MCP 引用）；绑定了工作流时连同工作流一起带上。别人安装=fork 成他自己的智能体，引用按他的权限自动裁剪并逐项提示。<b>子智能体委派关系不随模板走</b>。'
  },
  workflow: {
    label: '工作流', placeholder: '选择要上架的工作流（仅列出你可管理的）',
    detailHint: '这个流程解决什么问题、输入输出是什么（Markdown）。安装的人在详情页看到。',
    hint: '上架<b>当前画布的 DSL 快照</b>；别人安装=复制一份成为他的草稿，与你的原稿互不影响。'
  }
})[form.value.type] || {})
const typeHint = computed(() => typeMeta.value.hint || '')
const emptyHint = computed(() => '还没有可上架的「' + (TYPES.find(t => t.key === form.value.type)?.item || '物品') + '」。先去准备一个，再回来上架。')
const hasOptions = computed(() => (optionsByType.value[form.value.type] || []).length > 0)
const options = computed(() => optionsByType.value[form.value.type] || [])

/** 已上架条目按「类型:标识」索引：选项右侧标注「已上架/已下架」（重复上架=更新同一条） */
const mineByKey = computed(() => {
  const m = {}
  for (const it of mineItems.value) m[it.itemType + ':' + (it.itemKey || it.name)] = it
  return m
})

const switchType = t => {
  if (form.value.type === t) return
  form.value = { type: t, ref: '', summary: '', description: '', tags: '', version: '' }
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
    summary: '', description: '', tags: '', version: ''
  }
  loadingOptions.value = true
  try {
    const [skillRes, agentRes, wfRes, mineRes] = await Promise.all([
      listSkills(), listAgents(), listWorkflows(), listMarketItems({ mine: true })
    ])
    if (!skillRes || !skillRes.success) throw new Error((skillRes && skillRes.msg) || '读取失败')
    const skills = (skillRes.data?.skills || []).filter(s => s.source === 'user' && !s.platformDisabled)
    const agents = (agentRes?.data || []).filter(a => a.manageable === 1 && a.isBuiltin !== 1)
    // /workflow/list 返回 {mine:[], shared:[]}：可上架=本人的或共享管理命中（与后端 requireManage 同口径）
    const wfAll = [...(wfRes?.data?.mine || []), ...(wfRes?.data?.shared || [])]
    const workflows = wfAll.filter(w => w.mine === true || w.myPermission === 'MANAGE')
    // 先落 mineItems（已上架标注依赖它），再构建选项
    mineItems.value = mineRes?.data?.items || []
    const build = (list, type, nameOf, descOf, idOf) => list.map(x => ({
      value: idOf(x),
      search: nameOf(x) + ' ' + (descOf(x) || ''),
      label: optLabel(nameOf(x), descOf(x), mineByKey.value[type + ':' + nameOf(x)])
    }))
    optionsByType.value = {
      skill: build(skills, 'skill', s => s.name, s => s.description, s => s.name),
      agent: build(agents, 'agent', a => a.name, a => a.description, a => a.id),
      workflow: build(workflows, 'workflow', w => w.name, w => w.description, w => w.id)
    }
  } catch (e) {
    message.error('读取可上架清单失败：' + ((e && e.message) || e))
    emit('update:open', false)
  } finally {
    loadingOptions.value = false
  }
})

const submit = async () => {
  const f = form.value
  if (!f.ref) { message.warning('请先选择要上架的' + (typeMeta.value.label || '物品')); return }
  publishing.value = true
  try {
    const r = await publishMarketItem({
      type: f.type, ref: f.ref, summary: f.summary.trim(),
      description: f.description, tags: f.tags, version: f.version.trim()
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '上架失败')
    message.success('已上架到市场，可在「市场 → 我的上架」里管理')
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
.mk-form-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: -6px 0 0; }
.key-modal-foot { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 18px; }
.mk-type-row { display: flex; gap: 8px; margin-bottom: 14px; }
.mk-type-btn { border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text2);
  border-radius: 999px; padding: 4px 14px; font-size: 12px; cursor: pointer; }
.mk-type-btn.on { border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 600; }
</style>
