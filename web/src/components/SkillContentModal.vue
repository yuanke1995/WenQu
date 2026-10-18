<template>
  <!-- 技能正文查看：技能内容的写入已交给助手（对话里沉淀），读的这一侧必须可审——
       正文是逐字拼进本轮提示词的，这里显示的就是模型真正吃到的那份字节。 -->
  <a-modal v-model:open="visible" :title="name || '技能正文'" :footer="null" width="min(720px, 94vw)"
           :z-index="1200" :style="{ top: '48px' }" destroy-on-close>
    <div v-if="loading" class="sc-loading">
      <a-spin />
      <span class="sc-loading-text">正在读取技能正文…</span>
    </div>

    <!-- 拉取失败不许显示成「这条技能没有内容」：那是读取失败，不是空 -->
    <div v-else-if="error" class="sc-err">
      <p class="sc-err-msg">{{ error }}</p>
      <button class="app-btn" @click="load">重试</button>
    </div>

    <template v-else>
      <div class="sc-meta">
        <span class="sc-src">{{ SOURCE_LABEL[entry.source] || '技能' }}</span>
        <span v-if="entry.disabled" class="sc-off">已停用</span>
        <span v-if="entry.description" class="sc-desc">{{ entry.description }}</span>
      </div>
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div class="md sc-body" v-html="html" />

      <div class="sc-foot">
        <template v-if="entry.source === 'workspace'">
          <p class="sc-hint">这条存在本会话的沙盒工作区里，随会话走。要改它或不再用它，直接在对话里说一声。</p>
        </template>
        <template v-else-if="entry.disabledByPlatform">
          <p class="sc-hint">这条由平台统一停用，个人设置改不动它。本轮仍可在输入框里点名用它一次。</p>
        </template>
        <template v-else>
          <button class="app-btn" :disabled="saving" @click="toggleDisabled">
            {{ entry.disabled ? '恢复使用' : '这条别再用了' }}
          </button>
          <p class="sc-hint">
            {{ entry.disabled
              ? '恢复后下一问起重新进模型的技能清单。'
              : '停用后下一问起不再进模型的技能清单；想临时用一次，仍可在输入框里点名带上。' }}
          </p>
        </template>
      </div>
    </template>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getSkillContent, getUserSettings, saveUserSettings } from '../api'
import { renderMd } from '../utils/markdown'

const props = defineProps({
  open: { type: Boolean, default: false },
  name: { type: String, default: '' },
  sessionId: { type: String, default: '' },
  // 清单里的那一条（来源 / 是否停用 / 停用是否来自平台层）
  entry: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:open', 'changed'])

const SOURCE_LABEL = { builtin: '内置技能', user: '我的技能', workspace: '工作区技能' }
const DISABLED_PATH = 'skill.disabled'

const visible = computed({
  get: () => props.open,
  set: v => emit('update:open', v)
})

const loading = ref(false)
const error = ref('')
const content = ref('')
const saving = ref(false)
const html = computed(() => renderMd(content.value || '（这条技能没有正文）'))

const load = async () => {
  if (!props.name) return
  loading.value = true
  error.value = ''
  content.value = ''
  try {
    const r = await getSkillContent(props.name, props.sessionId)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    content.value = (r.data && r.data.content) || ''
  } catch (e) {
    error.value = '读取这条技能的正文失败：' + (e.message || e)
  } finally {
    loading.value = false
  }
}
watch(() => [props.open, props.name], () => { if (props.open) load() }, { immediate: true })

/** 停用清单是个「一行一条」的文本项：改之前先读回本人当前那份，只动被点的这一条。 */
const toggleDisabled = async () => {
  saving.value = true
  try {
    const r = await getUserSettings()
    const cur = String(((r.data || {}).values || {})[DISABLED_PATH] || '')
    const lines = cur.split(/\r?\n/).map(x => x.trim()).filter(Boolean)
    const target = props.name.trim().toLowerCase()
    const next = props.entry.disabled
        ? lines.filter(x => x.toLowerCase() !== target)
        : (lines.some(x => x.toLowerCase() === target) ? lines : [...lines, props.name.trim()])
    const s = await saveUserSettings({ [DISABLED_PATH]: next.join('\n') })
    if (!s || !s.success) throw new Error((s && s.msg) || '保存失败')
    message.success(props.entry.disabled ? '已恢复，下一问起生效' : '已停用，下一问起生效')
    emit('changed')
  } catch (e) {
    message.error('保存失败：' + (e.message || e))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.sc-loading { display: flex; align-items: center; gap: 10px; padding: 28px 4px; }
.sc-loading-text { font-size: 13px; color: var(--app-text3); }
.sc-err { padding: 18px 2px; }
.sc-err-msg { margin: 0 0 12px; font-size: 13px; color: var(--app-danger-text); }
.sc-meta { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.sc-src { flex: none; font-size: 11px; line-height: 1; padding: 4px 8px; border-radius: 999px;
  background: var(--app-panel-2); color: var(--app-text3); }
.sc-off { flex: none; font-size: 11px; line-height: 1; padding: 4px 8px; border-radius: 999px;
  background: var(--app-danger-weak); color: var(--app-danger-text); }
.sc-desc { font-size: 12px; color: var(--app-text3); }
.sc-body { max-height: 56vh; overflow: auto; padding: 2px 2px 8px; font-size: 13px; }
.sc-foot { display: flex; align-items: center; gap: 12px; margin-top: 12px;
  border-top: 1px solid var(--app-border); padding-top: 12px; }
.sc-hint { margin: 0; font-size: 12px; color: var(--app-text3); }
</style>
