<template>
  <a-modal :open="open" title="选择工作区目录" :footer="null" :width="580"
           @cancel="close">
    <div class="lwd">
      <!-- 这台电脑：执行器设备 -->
      <div class="lwd-row">
        <span class="lwd-k">这台电脑</span>
        <a-select v-if="devices.length" v-model:value="executorId" class="lwd-v"
                  :options="deviceOptions" @change="reload" />
        <span v-else-if="!freshToken" class="lwd-empty">还没有添加这台电脑</span>
        <button v-if="!devices.length && !freshToken" class="app-btn" :disabled="registering" @click="addDevice">
          {{ registering ? '登记中…' : '添加这台电脑' }}
        </button>
        <button v-else-if="devices.length" class="app-link-btn" :disabled="registering" @click="addDevice">再添加一台</button>
      </div>

      <!-- 刚登记的令牌（只显示一次） -->
      <div v-if="freshToken" class="lwd-token">
        <div class="lwd-token-t">设备令牌（只显示这一次）：</div>
        <code class="lwd-token-v">{{ freshToken }}</code>
        <div class="lwd-token-t">在这台电脑上运行：</div>
        <pre class="lwd-cmd">node scripts/local-agent/wenqu-agent.mjs --server {{ serverBase }} --token {{ freshToken }} --root ~</pre>
        <div class="lwd-token-t">要能选整块磁盘就把 --root 换成 /（默认只从家目录开始浏览）。</div>
        <button class="app-btn ghost" @click="refreshDevices">我已经启动了，刷新</button>
      </div>

      <!-- 目录浏览 -->
      <div class="lwd-row lwd-row-dir">
        <span class="lwd-k">目录</span>
        <div class="lwd-path">
          <span class="lwd-path-txt">{{ path || '选择这台电脑上的目录' }}</span>
          <button v-if="path" class="app-link-btn" @click="goUp">上一级</button>
        </div>
      </div>
      <div class="lwd-list">
        <div v-if="!executorId" class="lwd-empty">先从上面添加这台电脑</div>
        <div v-else-if="loading" class="lwd-empty">加载中…</div>
        <div v-else-if="error" class="lwd-empty lwd-err">{{ error }}</div>
        <div v-else-if="!dirs.length" class="lwd-empty">这个目录下没有子目录</div>
        <button v-for="d in dirs" :key="d.path" type="button" class="lwd-dir" @click="enter(d.path)">
          <FolderOutlined />
          <span>{{ d.name }}</span>
        </button>
      </div>
      <div class="lwd-foot">
        <span class="lwd-tip">选中的目录会成为这个工作区的根：读写都只在它里面</span>
        <button class="app-btn" :disabled="!path || !executorId || creating" @click="choose">
          {{ creating ? '创建中…' : '选择这个目录' }}
        </button>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { FolderOutlined } from '@ant-design/icons-vue'
import { browseExecutor, createProject, bindProjectExecutor, listExecutors, registerExecutor } from '../api'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['update:open', 'created'])

const devices = ref([])
const executorId = ref('')
const dirs = ref([])
const path = ref('')
const loading = ref(false)
const error = ref('')
const creating = ref(false)
const registering = ref(false)
const freshToken = ref('')

const serverBase = computed(() => `${location.origin}/ai`)
const deviceOptions = computed(() => devices.value.map(d => ({
  value: d.id,
  label: `${d.name || d.id}（${d.online ? '在线' : '离线'}）`,
  disabled: !d.online
})))

const close = () => emit('update:open', false)

async function refreshDevices () {
  try {
    const r = await listExecutors()
    devices.value = (r && r.data) || []
    if (!executorId.value && devices.value.length) {
      const online = devices.value.find(d => d.online) || devices.value[0]
      executorId.value = online.id
      await reload()
    }
  } catch (e) {
    message.error('取执行器清单失败：' + (e.message || ''))
  }
}

async function addDevice () {
  registering.value = true
  try {
    const r = await registerExecutor({ name: '我的电脑', os: navigator.platform || '' })
    freshToken.value = (r && r.data && r.data.token) || ''
    await refreshDevices()
  } catch (e) {
    message.error('登记失败：' + (e.message || ''))
  } finally {
    registering.value = false
  }
}

async function browse (p) {
  if (!executorId.value) return
  loading.value = true
  error.value = ''
  try {
    const r = await browseExecutor(executorId.value, p)
    if (r && r.success === false) {
      error.value = r.msg || '执行器没有返回'
      dirs.value = []
      return
    }
    const data = (r && r.data) || {}
    dirs.value = Array.isArray(data.dirs) ? data.dirs : []
    path.value = data.path || p || ''
    if (!data.path && p) path.value = p
  } catch (e) {
    error.value = '读取目录失败：' + (e.message || '')
    dirs.value = []
  } finally {
    loading.value = false
  }
}

const reload = () => browse('')
const enter = p => browse(p)
const goUp = () => {
  const cut = path.value.replace(/\/+$/, '')
  const up = cut.slice(0, cut.lastIndexOf('/'))
  browse(up && up !== cut ? up : '')
}

async function choose () {
  if (!path.value) return
  creating.value = true
  try {
    const name = path.value.replace(/\/+$/, '').split('/').pop() || '本机工作区'
    const r = await createProject(name, undefined, 'local')
    const id = (r && r.data && r.data.id) || ''
    if (!id) throw new Error('创建工作区没有返回 id')
    await bindProjectExecutor(id, executorId.value, path.value)
    message.success(`工作区「${name}」已绑定到这台电脑上的目录`)
    emit('created', id)
    close()
  } catch (e) {
    message.error('绑定失败：' + (e.message || ''))
  } finally {
    creating.value = false
  }
}

watch(() => props.open, open => {
  if (!open) return
  freshToken.value = ''
  devices.value = []
  executorId.value = ''
  dirs.value = []
  path.value = ''
  error.value = ''
  refreshDevices()
})
</script>

<style scoped>
.lwd { display: flex; flex-direction: column; gap: 10px; }
.lwd-row { display: flex; align-items: center; gap: 10px; }
.lwd-k { width: 64px; flex: none; font-size: 12px; color: var(--app-text2); }
.lwd-v { flex: 1; }
.lwd-empty { font-size: 12px; color: var(--app-text3); }
.lwd-err { color: #c0392b; }
.lwd-row-dir { align-items: flex-start; }
.lwd-path { flex: 1; display: flex; align-items: center; gap: 8px; min-width: 0; }
.lwd-path-txt { font-size: 12px; color: var(--app-text1); word-break: break-all; }
.lwd-list { max-height: 240px; overflow: auto; border: 1px solid var(--app-border, rgba(0,0,0,.08)); border-radius: 8px; padding: 4px; }
.lwd-dir { display: flex; align-items: center; gap: 8px; width: 100%; padding: 6px 8px; border: 0; background: none;
  text-align: left; cursor: pointer; border-radius: 6px; font-size: 13px; color: var(--app-text1); }
.lwd-dir:hover { background: var(--app-surface2, rgba(0,0,0,.04)); }
.lwd-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.lwd-tip { font-size: 12px; color: var(--app-text3); }
.lwd-token { border: 1px dashed var(--app-border, rgba(0,0,0,.16)); border-radius: 8px; padding: 10px; font-size: 12px; }
.lwd-token-t { color: var(--app-text2); margin-bottom: 4px; }
.lwd-token-v { display: block; word-break: break-all; margin-bottom: 8px; }
.lwd-cmd { margin: 0 0 8px; padding: 8px; background: var(--app-surface2, rgba(0,0,0,.04)); border-radius: 6px;
  white-space: pre-wrap; word-break: break-all; font-size: 11px; }
</style>
