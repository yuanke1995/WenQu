<template>
  <!-- 编辑展示信息：只改卡片文案，不动内容快照、版本与指纹，因此不会产生新版本、不会触发「有更新」。 -->
  <a-modal :open="open" :title="'编辑「' + (item.name || '') + '」'" :width="560" :footer="null" destroy-on-close
           @cancel="$emit('update:open', false)">
    <div class="mm-head">
      <div class="mm-sub">保存后立即生效，不会生成新版本。</div>
    </div>
    <a-form layout="vertical" class="mm-form">
      <a-form-item label="一句话简介" required>
        <a-input v-model:value="form.summary" :maxlength="256" show-count
                 placeholder="列表卡片上的一句话" />
      </a-form-item>
      <a-form-item label="详细介绍">
        <a-textarea v-model:value="form.description" :rows="5"
                    placeholder="使用场景、用法与前置要求（Markdown），详情页可见。" />
      </a-form-item>
      <a-form-item label="标签">
        <a-input v-model:value="form.tags" placeholder="逗号或顿号分隔，如：写作, 周报, 格式" />
      </a-form-item>
    </a-form>
    <div class="mm-foot">
      <button class="app-btn ghost" :disabled="saving" @click="$emit('update:open', false)">取消</button>
      <button class="app-btn" :disabled="saving || !form.summary.trim()" @click="save">
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </div>
  </a-modal>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { updateMarketItemMeta } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 详情对象（含 summary / description / tags） */
  item: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:open', 'saved'])

const form = reactive({ summary: '', description: '', tags: '' })
const saving = ref(false)

watch(() => props.open, open => {
  if (!open) return
  form.summary = props.item.summary || ''
  form.description = props.item.description || ''
  form.tags = Array.isArray(props.item.tags) ? props.item.tags.join(', ') : (props.item.tags || '')
  saving.value = false
})

const save = async () => {
  if (!form.summary.trim()) { message.warning('请填写一句话简介'); return }
  saving.value = true
  try {
    const r = await updateMarketItemMeta(props.item.id, {
      summary: form.summary.trim(),
      description: form.description,
      tags: form.tags
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '保存失败')
    message.success('已保存')
    emit('update:open', false)
    emit('saved', r.data)
  } catch (e) {
    message.error('保存失败：' + ((e && e.message) || e))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.mm-head { padding-right: 24px; }
.mm-title { font-size: 15px; font-weight: 600; color: var(--app-text); }
.mm-sub { margin-top: 5px; font-size: 12px; color: var(--app-text3); }
.mm-form { margin-top: 14px; }
.mm-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
</style>
