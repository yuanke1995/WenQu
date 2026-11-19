<template>
  <a-modal :open="open" :title="'终端 · ' + (hostName || hostId)" :footer="null" :width="860" @cancel="close">
    <div class="tm">
      <div class="tm-bar">
        <span class="tm-dot" :class="state"></span>
        <span class="tm-state">{{ stateText }}</span>
        <span class="tm-grow" />
        <button class="app-link-btn" type="button" @click="sendCtrlC">Ctrl+C</button>
        <button class="app-link-btn" type="button" @click="copyAll">复制全部</button>
        <button class="app-link-btn" type="button" @click="clear">清屏</button>
        <button class="app-link-btn" type="button" :disabled="state === 'connecting'" @click="connect">重连</button>
      </div>
      <pre ref="paneEl" class="tm-pane">{{ display }}</pre>
      <div class="tm-input">
        <span class="tm-prompt">$</span>
        <input ref="inputEl" v-model="line" class="tm-line" spellcheck="false" autocomplete="off"
               placeholder="输入命令后回车（远程 PTY；Ctrl+C 中断）"
               @keydown.enter.prevent="submit" />
        <button class="app-btn" type="button" :disabled="state !== 'open'" @click="submit">发送</button>
      </div>
      <div v-if="state === 'error'" class="tm-err">{{ error }}</div>
      <div class="tm-hint">终端只用于交互式操作；批量/可审计命令建议让 agent 的 shell 工具执行。</div>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { issueSshTerminalTicket } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  hostId: { type: String, default: '' },
  hostName: { type: String, default: '' },
  startPath: { type: String, default: '' }
})
const emit = defineEmits(['update:open'])

const paneEl = ref(null)
const inputEl = ref(null)
const line = ref('')
const output = ref('')
const state = ref('closed')
const error = ref('')
let ws = null
let disposed = false

const stateText = computed(() => ({
  connecting: '连接中…', open: '已连接', closed: '未连接', error: '连接失败'
}[state.value] || ''))

function apiBase () {
  return import.meta.env.VITE_API_BASE || '/proxy/api/ai'
}
function wsUrl (ticket) {
  const base = apiBase()
  const suffix = '/ssh-terminal?ticket=' + encodeURIComponent(ticket) + '&cols=140&rows=36'
  if (/^https?:\/\//.test(base)) {
    return base.replace(/^http/, 'ws') + suffix
  }
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return proto + '//' + location.host + base + suffix
}
function clean (s) {
  return String(s || '')
    // OSC / CSI / 常见单字符控制序列
    .replace(/\u001b\][^\u0007]*\u0007/g, '')
    .replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\u001b[@-_]/g, '')
    .replace(/\r/g, '')
}

async function connect () {
  if (!props.hostId) return
  teardown()
  disposed = false
  state.value = 'connecting'
  error.value = ''
  output.value = ''
  try {
    const r = await issueSshTerminalTicket(props.hostId)
    const ticket = r && r.data && r.data.ticket
    if (!ticket) throw new Error('没有拿到终端入场券')
    ws = new WebSocket(wsUrl(ticket))
    ws.onopen = () => {
      state.value = 'open'
      if (props.startPath) send('cd ' + shellQuote(props.startPath) + '\r')
      nextTick(() => inputEl.value && inputEl.value.focus())
    }
    ws.onmessage = ev => {
      let msg
      try { msg = JSON.parse(ev.data) } catch (e) { return }
      if (msg.type === 'output') append(clean(msg.data))
      else if (msg.type === 'error') { error.value = msg.message || '终端错误'; state.value = 'error' }
      else if (msg.type === 'exit') { state.value = 'closed' }
    }
    ws.onerror = () => { state.value = 'error'; error.value = error.value || '终端连接失败' }
    ws.onclose = () => { if (state.value === 'open') state.value = 'closed' }
  } catch (e) {
    state.value = 'error'
    error.value = e.message || '连接失败'
  }
}

function append (text) {
  if (!text) return
  output.value += text
  if (output.value.length > 400000) output.value = output.value.slice(-300000)
  nextTick(() => { if (paneEl.value) paneEl.value.scrollTop = paneEl.value.scrollHeight })
}

function send (data) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return
  ws.send(JSON.stringify({ type: 'input', data }))
}
function submit () {
  if (state.value !== 'open') return
  send(line.value + '\r')
  line.value = ''
}
function sendCtrlC () {
  send('\u0003')
  append('^C\n')
}
function copyAll () {
  try {
    navigator.clipboard.writeText(output.value)
    message.success('已复制')
  } catch (e) { message.warning('复制失败，请手动选择') }
}
function clear () { output.value = '' }

function shellQuote (s) {
  return "'" + String(s || '').replace(/'/g, "'\\''") + "'"
}
function teardown () {
  if (ws) {
    try { ws.close() } catch (e) { /* ignore */ }
    ws = null
  }
}
function close () {
  disposed = true
  teardown()
  state.value = 'closed'
  emit('update:open', false)
}
watch(() => props.open, v => {
  if (v) connect()
  else { teardown(); state.value = 'closed' }
})
</script>

<style scoped>
.tm { display: flex; flex-direction: column; gap: 8px; }
.tm-bar { display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--app-text2); }
.tm-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--app-text3); }
.tm-dot.open { background: #16a34a; }
.tm-dot.connecting { background: #d97706; }
.tm-dot.error { background: #dc2626; }
.tm-grow { flex: 1; }
.tm-pane { margin: 0; height: 420px; overflow: auto; background: #0b1020; color: #d7e0ff;
  border-radius: 10px; padding: 12px 14px; font: 12.5px/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  white-space: pre-wrap; word-break: break-all; }
.tm-input { display: flex; align-items: center; gap: 8px; }
.tm-prompt { color: var(--app-text3); font-family: ui-monospace, monospace; }
.tm-line { flex: 1; border: 1px solid var(--app-border, rgba(0,0,0,.15)); border-radius: 8px;
  padding: 8px 10px; font: 13px ui-monospace, monospace; background: var(--app-surface2, rgba(0,0,0,.03));
  color: var(--app-text); outline: none; }
.tm-line:focus { border-color: var(--app-accent); }
.tm-err { font-size: 12px; color: #b91c1c; }
.tm-hint { font-size: 11px; color: var(--app-text3); }
</style>
