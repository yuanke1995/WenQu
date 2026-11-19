<template>
  <a-modal :open="open" :title="title || '选择远端文件夹'" :footer="null" :width="560" @cancel="close">
    <div class="sb">
      <div class="sb-bar">
        <button class="app-link-btn" type="button" :disabled="!parent" @click="go(parent)">上一级</button>
        <code class="sb-path" :title="path">{{ path || '…' }}</code>
        <button class="app-link-btn" type="button" @click="goHome">主目录</button>
      </div>
      <div class="sb-list">
        <div v-for="d in dirs" :key="d.path" class="sb-row" @click="go(d.path)">
          <span class="sb-ic">📁</span><span class="sb-name">{{ d.name }}</span>
        </div>
        <div v-if="!dirs.length" class="sb-empty">{{ loading ? '加载中…' : '没有子文件夹' }}</div>
      </div>
      <div class="sb-foot">
        <template v-if="mode !== 'alias'">
          <a-input v-model:value="newName" placeholder="新建文件夹名称" style="width:200px" />
          <button class="app-btn ghost" type="button" :disabled="!newName.trim()" @click="createDir">新建文件夹</button>
        </template>
        <span class="sb-grow" />
        <button class="app-btn" type="button" :disabled="!path" @click="pick">选择当前目录</button>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { browseSshHost, mkdirSshHost, browseSshAlias } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  mode: { type: String, default: 'host' }, // host=服务端 SSH 主机；alias=本机 OpenSSH 别名
  hostId: { type: String, default: '' },
  executorId: { type: String, default: '' },
  aliasName: { type: String, default: '' },
  title: { type: String, default: '' },
  startPath: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'picked'])

const path = ref('')
const parent = ref('')
const dirs = ref([])
const loading = ref(false)
const newName = ref('')

async function load (p) {
  if (props.mode === 'alias' ? (!props.executorId || !props.aliasName) : !props.hostId) return
  loading.value = true
  try {
    const r = props.mode === 'alias'
      ? await browseSshAlias(props.executorId, props.aliasName, p || '')
      : await browseSshHost(props.hostId, p || '')
    const d = (r && r.data) || {}
    path.value = d.path || ''
    parent.value = d.parent || ''
    dirs.value = d.dirs || []
  } catch (e) {
    message.error('浏览失败：' + (e.message || ''))
  } finally {
    loading.value = false
  }
}
function go (p) { load(p) }
function goHome () { load('') }
function pick () {
  if (!path.value) return
  emit('picked', path.value)
  close()
}
async function createDir () {
  const name = newName.value.trim()
  if (!name) return
  const target = (path.value.endsWith('/') ? path.value : path.value + '/') + name
  try {
    await mkdirSshHost(props.hostId, target)
    newName.value = ''
    message.success('已创建')
    await load(path.value)
  } catch (e) {
    message.error('新建失败：' + (e.message || ''))
  }
}
const close = () => emit('update:open', false)
watch(() => props.open, v => { if (v) { newName.value = ''; load(props.startPath || '') } }, { immediate: true })
</script>

<style scoped>
.sb { display: flex; flex-direction: column; gap: 10px; }
.sb-bar { display: flex; align-items: center; gap: 10px; }
.sb-path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; color: var(--app-text2); background: var(--app-surface2, rgba(0,0,0,.04)); padding: 4px 8px; border-radius: 6px; }
.sb-list { min-height: 220px; max-height: 320px; overflow: auto; border: 1px solid var(--app-border, rgba(0,0,0,.08)); border-radius: 8px; }
.sb-row { display: flex; align-items: center; gap: 8px; padding: 8px 12px; cursor: pointer; font-size: 13px; }
.sb-row:hover { background: var(--app-surface2, rgba(0,0,0,.04)); }
.sb-ic { font-size: 14px; }
.sb-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sb-empty { padding: 40px; text-align: center; color: var(--app-text3); font-size: 13px; }
.sb-foot { display: flex; align-items: center; gap: 8px; }
.sb-grow { flex: 1; }
</style>
