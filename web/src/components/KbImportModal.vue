<template>
  <a-modal :open="open" title="导入文档包" :width="640" :footer="null"
           @update:open="v => emit('update:open', v)">
    <div class="imp-tip">
      导入的是「知识库导出」生成的 zip 包（manifest.json + 各文档源文件）。导入后按目标库参数重新解析、分块、向量化。
    </div>

    <div class="imp-row">
      <span class="imp-label">导入到</span>
      <a-select v-model:value="target" :options="targetOptions" style="width:260px"
                :disabled="importing || !!fixedKbId" @change="onTargetChange" />
      <span class="imp-hint">官方内置库不接受导入</span>
    </div>

    <a-upload-dragger :before-upload="onFile" :show-upload-list="false" accept=".zip"
                      :disabled="importing || !target" style="margin-top:10px">
      <p class="ant-upload-drag-icon"><inbox-outlined /></p>
      <p class="ant-upload-text">{{ file ? file.name : '点击或拖拽 zip 文档包到此处' }}</p>
      <p class="ant-upload-hint">单包最多 500 个文档；同名冲突可在下方选择处理方式</p>
    </a-upload-dragger>

    <a-spin :spinning="previewing">
      <div v-if="error" class="imp-error">{{ error }}</div>
      <div v-if="preview" class="imp-preview">
        <div class="imp-summary">
          <span>共 <b>{{ preview.total }}</b> 个文档</span>
          <span v-if="preview.conflicts" class="conflict">同名冲突 <b>{{ preview.conflicts }}</b></span>
          <span v-else class="ok">无同名冲突</span>
          <span class="sp"></span>
          <span class="imp-kb">{{ preview.kbName }}</span>
        </div>
        <div class="imp-list">
          <div v-for="d in preview.documents" :key="d.fileName" class="imp-item">
            <file-outlined class="imp-ic" />
            <span class="imp-name" :title="d.fileName">{{ d.fileName }}</span>
            <span class="imp-type">{{ (d.fileType || 'file').toUpperCase() }}</span>
            <span v-if="d.exists" class="imp-tag warn">已存在</span>
            <span v-if="d.missingSourceFile" class="imp-tag err">源文件缺失</span>
          </div>
        </div>
        <div v-if="preview.conflicts" class="imp-strategy">
          <span class="imp-label">同名冲突</span>
          <a-radio-group v-model:value="strategy" size="small">
            <a-radio-button value="replace">替换</a-radio-button>
            <a-radio-button value="skip">跳过</a-radio-button>
            <a-radio-button value="keepBoth">保留两份</a-radio-button>
          </a-radio-group>
        </div>
      </div>
    </a-spin>

    <div v-if="result" class="imp-result">
      <div class="imp-summary">
        <span class="ok">成功 {{ result.ok }}</span>
        <span v-if="result.skipped">跳过 {{ result.skipped }}</span>
        <span v-if="result.failed" class="conflict">失败 {{ result.failed }}</span>
        <span class="sp"></span>
        <span class="imp-kb">{{ result.strategy }}</span>
      </div>
      <div class="imp-list">
        <div v-for="r in result.results" :key="r.fileName" class="imp-item">
          <check-circle-outlined v-if="r.success && !r.skipped" class="imp-ic ok" />
          <minus-circle-outlined v-else-if="r.skipped" class="imp-ic muted" />
          <close-circle-outlined v-else class="imp-ic err" />
          <span class="imp-name" :title="r.fileName">{{ r.renamedTo || r.fileName }}</span>
          <span class="imp-msg">{{ r.msg || (r.skipped ? '已跳过' : '已提交解析') }}</span>
        </div>
      </div>
    </div>

    <div class="imp-foot">
      <span class="imp-hint">解析在后台队列执行，可在文档页查看进度</span>
      <span class="sp"></span>
      <a-button @click="emit('update:open', false)">{{ result ? '完成' : '取消' }}</a-button>
      <a-button type="primary" :disabled="!preview || !!result" :loading="importing" @click="doImport">
        开始导入
      </a-button>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  CheckCircleOutlined, CloseCircleOutlined, FileOutlined, InboxOutlined, MinusCircleOutlined
} from '@ant-design/icons-vue'
import { importKnowledgeBase, previewKnowledgeBaseImport } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  kbList: { type: Array, default: () => [] },
  defaultKbId: { type: String, default: '' },
  fixedKbId: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'done'])

const target = ref('')
const file = ref(null)
const preview = ref(null)
const previewing = ref(false)
const importing = ref(false)
const strategy = ref('replace')
const result = ref(null)
const error = ref('')

const targetOptions = computed(() => (props.kbList || [])
  .filter(k => k.builtin !== 1 && k.archived !== 1)
  .map(k => ({ value: k.id, label: k.name + (k.embeddingRef ? '' : '（未绑定向量模型）') })))

watch(() => props.open, v => {
  if (!v) return
  target.value = props.fixedKbId || props.defaultKbId || targetOptions.value[0]?.value || ''
  file.value = null
  preview.value = null
  result.value = null
  error.value = ''
  strategy.value = 'replace'
})

async function onFile (f) {
  if (!target.value) { message.warning('请先选择目标知识库'); return false }
  file.value = f
  result.value = null
  await loadPreview()
  return false
}

async function loadPreview () {
  if (!file.value || !target.value) return
  previewing.value = true
  error.value = ''
  try {
    const r = await previewKnowledgeBaseImport(target.value, file.value)
    preview.value = r.data || null
    if (preview.value?.conflicts) strategy.value = 'replace'
  } catch (e) {
    preview.value = null
    error.value = e.message || '读取导出包失败'
  } finally {
    previewing.value = false
  }
}

function onTargetChange () {
  result.value = null
  if (file.value) loadPreview()
}

async function doImport () {
  if (!preview.value || !file.value || !target.value) return
  importing.value = true
  try {
    const r = await importKnowledgeBase(target.value, file.value, strategy.value)
    if (r.success === false) { message.error(r.msg || '导入失败'); return }
    result.value = r.data || null
    message.success(`导入完成：成功 ${result.value?.ok || 0}/${result.value?.total || 0}`)
    emit('done', target.value)
  } catch (e) {
    message.error(e.message || '导入失败')
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.imp-tip { font-size: 11.5px; color: var(--app-text3); line-height: 1.7; background: var(--app-panel-2);
  border: 1px solid var(--app-border); border-radius: 6px; padding: 8px 10px; }
.imp-row { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.imp-label { font-size: 12px; color: var(--app-text2); }
.imp-hint { font-size: 11px; color: var(--app-text3); }
.imp-error { margin-top: 10px; color: var(--app-danger); font-size: 12px; }
.imp-preview, .imp-result { margin-top: 12px; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.imp-summary { display: flex; align-items: center; gap: 12px; padding: 8px 10px; background: var(--app-panel-2);
  font-size: 11.5px; color: var(--app-text3); }
.imp-summary b { color: var(--app-text); }
.imp-summary .conflict { color: var(--app-warn-text); }
.imp-summary .ok { color: var(--app-ok); }
.imp-summary .sp, .imp-foot .sp { flex: 1; }
.imp-kb { margin-left: auto; color: var(--app-text2); }
.imp-list { max-height: 230px; overflow-y: auto; }
.imp-item { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-top: 1px solid var(--app-border);
  font-size: 11.5px; }
.imp-ic { color: var(--app-text3); flex: none; }
.imp-ic.ok { color: var(--app-ok); }
.imp-ic.err { color: var(--app-danger); }
.imp-ic.muted { color: var(--app-text3); }
.imp-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text); }
.imp-type { font-size: 9px; color: var(--app-text3); background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 3px; padding: 0 4px; }
.imp-tag { font-size: 9.5px; border-radius: 3px; padding: 1px 5px; }
.imp-tag.warn { color: var(--app-warn-text); background: var(--app-warn-weak); }
.imp-tag.err { color: var(--app-danger); background: #fff2f0; }
.imp-msg { font-size: 10.5px; color: var(--app-text3); max-width: 220px; overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap; }
.imp-strategy { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-top: 1px solid var(--app-border);
  background: #fffdf7; }
.imp-foot { display: flex; align-items: center; gap: 8px; margin-top: 14px; }
</style>
