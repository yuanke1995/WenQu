<template>
  <!-- 上架技能到市场：选「我的技能」之一打快照上架（同一技能重复上架 = 更新同一条）。
       MarketplacePage 的「上架我的技能」与技能页卡片上的「上架」共用这一个弹窗。 -->
  <a-modal :open="open" title="上架技能到市场" :footer="null" :width="640" destroy-on-close
           @cancel="$emit('update:open', false)">
    <a-spin v-if="loadingSkills" style="display:block;margin:36px auto" />
    <template v-else>
      <p v-if="!mySkills.length" class="mk-form-hint">
        还没有可上架的技能。先在「智能体 → 技能」里新建或安装一个自己的技能，再回来上架
        （内置技能随版本分发人人都有，无需上架）。
      </p>
      <a-form v-else layout="vertical">
        <a-form-item label="技能" required>
          <!-- 选项=名称加粗 + 描述单行截断 + 已上架标注（描述全文拼 label 会变成一堵字墙）。
               label 用 VNode 且样式内联：下拉与收起态选择器共用一套渲染，也不依赖 scoped 作用域
               （插槽方案实测收起态丢样式，描述全文换行堆叠）。搜索按 search 字段命中名称+描述 -->
          <a-select v-model:value="form.name" placeholder="选择要上架的技能（仅列出你自己安装的）" show-search
                    option-filter-prop="search" :options="skillOptions" />
        </a-form-item>
        <a-form-item label="一句话简介" required>
          <a-input v-model:value="form.summary" :maxlength="256" show-count
                   placeholder="列表卡片上的一句话；留空取技能的 description" />
        </a-form-item>
        <a-form-item label="详细介绍（可选）">
          <a-textarea v-model:value="form.description" :rows="5"
                      placeholder="什么场景用它、怎么用、有什么前置要求（Markdown）。安装的人在详情页看到。" />
        </a-form-item>
        <a-form-item label="标签（可选）">
          <a-input v-model:value="form.tags" placeholder="逗号或顿号分隔，如：写作, 周报, 格式" />
        </a-form-item>
        <a-form-item label="版本号（可选）">
          <a-input v-model:value="form.version" :maxlength="32" placeholder="如 1.1；留空取 SKILL.md 里的 version，再没有记为 1" />
        </a-form-item>
      </a-form>
      <p class="mk-form-hint">
        上架的是<b>当前内容的快照</b>：之后你改了自己的技能，市场不会自动跟着变——重新上架一次即可。
        装过的人是否看到「有更新」由内容变化自动判定，与版本号无关；版本号仅用于展示。
      </p>
      <div class="key-modal-foot">
        <button class="app-btn ghost" @click="$emit('update:open', false)">取消</button>
        <button class="app-btn" :disabled="publishing || !form.name" @click="submit">
          {{ publishing ? '上架中…' : '上架' }}
        </button>
      </div>
    </template>
  </a-modal>
</template>

<script setup>
import { computed, h, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { listMarketItems, listSkills, publishSkillToMarket } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 预选技能名（技能页卡片上的「上架」带过来） */
  presetName: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'published'])

const loadingSkills = ref(false)
const mySkills = ref([])
const mineItems = ref([])
const publishing = ref(false)
const form = ref({ name: '', summary: '', description: '', tags: '', version: '' })

/** 已上架条目按技能名索引（item_key=技能名；老响应没吐 itemKey 时退回 name）：选项右侧标注「已上架/已下架」 */
const mineByName = computed(() => {
  const m = {}
  for (const it of mineItems.value) m[it.itemKey || it.name] = it
  return m
})

/** 选项 label（VNode，下拉与收起态共用）：名称 + 单行截断描述 + 已上架标注 */
const optLabel = (s, pub) => h('span', { style: 'display:flex;align-items:center;gap:8px;min-width:0;max-width:100%;' }, [
  h('span', { style: 'flex:none;font-weight:600;' }, s.name),
  h('span', {
    style: 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'
      + 'font-size:12px;color:var(--app-text3);'
  }, s.description || '（无描述）'),
  pub ? h('span', {
    style: 'flex:none;font-size:11px;line-height:18px;padding:0 8px;border-radius:999px;'
      + 'background:var(--app-panel-2);color:' + (pub.online ? 'var(--app-text2)' : 'var(--app-warn-text)') + ';'
  }, pub.online ? '已上架' : '已下架') : null
])

/** options：label 是 VNode 不能直接比字符串，搜索命中走 search 字段（名称+描述） */
const skillOptions = computed(() => mySkills.value.map(s => ({
  value: s.name,
  search: s.name + ' ' + (s.description || ''),
  label: optLabel(s, mineByName.value[s.name])
})))

watch(() => props.open, async open => {
  if (!open) return
  form.value = { name: props.presetName || '', summary: '', description: '', tags: '', version: '' }
  loadingSkills.value = true
  try {
    const [skillRes, mineRes] = await Promise.all([listSkills(), listMarketItems({ mine: true })])
    if (!skillRes || !skillRes.success) throw new Error((skillRes && skillRes.msg) || '读取失败')
    mySkills.value = (skillRes.data?.skills || []).filter(s => s.source === 'user' && !s.platformDisabled)
    mineItems.value = mineRes?.data?.items || []
  } catch (e) {
    message.error('读取我的技能失败：' + ((e && e.message) || e))
    emit('update:open', false)
  } finally {
    loadingSkills.value = false
  }
})

const submit = async () => {
  const f = form.value
  if (!f.name) { message.warning('请选择要上架的技能'); return }
  publishing.value = true
  try {
    const r = await publishSkillToMarket({
      name: f.name, summary: f.summary.trim(), description: f.description,
      tags: f.tags, version: f.version.trim()
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
</style>
