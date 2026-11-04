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

      <!-- 原生选择器（在宿主那台电脑上弹框）；弹不出来时下面还有逐层浏览兜底 -->
      <div class="lwd-row">
        <span class="lwd-k"></span>
        <button class="app-btn" :disabled="!executorId || picking" @click="pickNative">
          {{ picking ? '等待那台电脑上选择…' : '在这台电脑上选择目录…' }}
        </button>
        <span class="lwd-tip">会在那台电脑上弹出系统的选择框</span>
      </div>
      <!-- 目录浏览：路径条（可点每一段回退） + 上一级 + 文件夹列表 -->
      <div class="lwd-pathbar">
        <button class="lwd-up" type="button" :disabled="!canGoUp" title="上一级" @click="goUp">↑</button>
        <div class="lwd-crumbs">
          <template v-for="(c, i) in crumbs" :key="c.path">
            <span v-if="i" class="lwd-sep">/</span>
            <button type="button" class="lwd-crumb" :title="c.path" @click="enter(c.path)">{{ c.name }}</button>
          </template>
          <span v-if="!path" class="lwd-path-txt">选择这台电脑上的目录</span>
        </div>
        <a-spin v-if="loading" size="small" />
      </div>
      <div class="lwd-list">
        <div v-if="!executorId" class="lwd-empty">先从上面添加这台电脑</div>
        <div v-else-if="error" class="lwd-empty lwd-err">{{ error }}</div>
        <div v-else-if="!dirs.length && !loading" class="lwd-empty">这个目录下没有子目录</div>
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
import { browseExecutor, createProject, bindProjectExecutor, listExecutors, pickExecutorDir, registerExecutor } from '../api'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['update:open', 'created'])

const devices = ref([])
const executorId = ref('')
const dirs = ref([])
const path = ref('')
/** 起点（执行器授权的根）：再往上点会被执行器拒，所以路径条与「上一级」到这儿为止 */
const startPath = ref('')
const loading = ref(false)
const error = ref('')
const creating = ref(false)
const picking = ref(false)
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
    if (!startPath.value || !p) startPath.value = path.value
  } catch (e) {
    error.value = '读取目录失败：' + (e.message || '')
    dirs.value = []
  } finally {
    loading.value = false
  }
}

const reload = () => browse('')

async function pickNative () {
  if (!executorId.value) return
  picking.value = true
  try {
    const r = await pickExecutorDir(executorId.value)
    if (r && r.success === false) {
      message.warning(r.msg || '那台电脑上没有选到目录')
      return
    }
    const picked = (r && r.data && r.data.path) || ''
    if (picked) await browse(picked)
  } catch (e) {
    message.warning('没有选到目录：' + (e.message || ''))
  } finally {
    picking.value = false
  }
}
const enter = p => browse(p)
const canGoUp = computed(() => !!path.value && path.value !== startPath.value)
const crumbs = computed(() => {
  if (!path.value) return []
  const parts = path.value.split('/').filter(Boolean)
  const all = []
  let acc = ''
  for (const part of parts) {
    acc += '/' + part
    all.push({ name: part, path: acc })
  }
  const idx = all.findIndex(c => c.path === startPath.value)
  return idx >= 0 ? all.slice(idx) : all
})
const goUp = () => {
  if (!canGoUp.value) return
  const cut = path.value.replace(/\/+$/, '')
  const up = cut.slice(0, cut.lastIndexOf('/')) || '/'
  browse(up)
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
  startPath.value = ''
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
/* 路径条：上一级 + 可点面包屑 */
.lwd-pathbar { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 8px;
  border: 1px solid var(--app-border, rgba(0,0,0,.08)); background: var(--app-surface2, rgba(0,0,0,.03)); }
.lwd-up { border: 0; background: none; cursor: pointer; color: var(--app-text2); font-size: 14px; padding: 0 4px; }
.lwd-up:disabled { opacity: .3; cursor: default; }
.lwd-crumbs { flex: 1; display: flex; align-items: center; gap: 2px; min-width: 0; overflow: hidden; white-space: nowrap; }
.lwd-crumb { border: 0; background: none; padding: 0 2px; cursor: pointer; font-size: 12px; color: var(--app-text1); }
.lwd-crumb:hover { color: var(--app-accent); text-decoration: underline; }
.lwd-sep { color: var(--app-text3); font-size: 12px; }
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
