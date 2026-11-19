<template>
  <a-modal :open="open" title="SSH 远程主机" :footer="null" :width="720" @cancel="close">
    <div class="sh">
      <div class="sh-head">
        <div class="sh-tip">把远端 POSIX 主机当作工作区后端：文件、命令、终端都直连 SSH。凭据加密保存，出参永不回明文。</div>
        <button class="app-btn" type="button" @click="startCreate">添加主机</button>
      </div>

      <div v-if="hosts.length" class="sh-list">
        <div v-for="h in hosts" :key="h.id" class="sh-row">
          <div class="sh-main">
            <div class="sh-name">
              {{ h.name || '未命名主机' }}
              <span class="sh-badge" :class="h.online ? 'on' : 'off'">{{ h.online ? '可用' : (h.status === 'disabled' ? '已停用' : '未连通') }}</span>
            </div>
            <div class="sh-target">{{ h.username }}@{{ h.host }}:{{ h.port }} · {{ h.authType === 'private_key' ? '私钥' : '密码' }}</div>
            <div class="sh-meta">
              <span v-if="h.hostKeyFingerprint" :title="h.hostKeyFingerprint">指纹 {{ shortFp(h.hostKeyFingerprint) }}</span>
              <span v-else class="warn">未固定指纹（首次连接时记录）</span>
              <span v-if="h.lastError" class="err" :title="h.lastError">{{ h.lastError }}</span>
            </div>
          </div>
          <div class="sh-actions">
            <button class="app-link-btn" type="button" :disabled="testingId === h.id" @click="testSaved(h)">
              {{ testingId === h.id ? '测试中…' : '测试连接' }}
            </button>
            <button class="app-link-btn" type="button" @click="startEdit(h)">编辑</button>
            <button class="app-link-btn danger" type="button" @click="remove(h)">删除</button>
          </div>
        </div>
      </div>
      <div v-else-if="!editing" class="sh-empty">还没有 SSH 主机。点「添加主机」录入一台。</div>

      <div v-if="editing" class="sh-form">
        <div class="sh-form-title">{{ form.id ? '编辑主机' : '添加主机' }}</div>
        <div class="sh-grid">
          <label>显示名</label>
          <a-input v-model:value="form.name" placeholder="如：生产应用服务器" :maxlength="64" />
          <label>主机地址</label>
          <a-input v-model:value="form.host" placeholder="IP 或域名，如 49.232.129.201" />
          <label>端口</label>
          <a-input-number v-model:value="form.port" :min="1" :max="65535" style="width:120px" />
          <label>登录用户</label>
          <a-input v-model:value="form.username" placeholder="如 ubuntu / root" />
          <label>认证方式</label>
          <a-radio-group v-model:value="form.authType">
            <a-radio value="password">密码</a-radio>
            <a-radio value="private_key">私钥</a-radio>
          </a-radio-group>
        </div>

        <template v-if="form.authType === 'password'">
          <div class="sh-grid">
            <label>密码</label>
            <a-input-password v-model:value="form.password" :placeholder="form.id ? '留空=不修改' : '登录密码'" />
          </div>
        </template>
        <template v-else>
          <div class="sh-grid">
            <label>私钥</label>
            <a-textarea v-model:value="form.privateKey" :rows="5"
                        :placeholder="form.id ? '留空=不修改（粘贴 OpenSSH/PEM 私钥全文）' : '粘贴 OpenSSH / PEM 私钥全文'" />
            <label>私钥口令</label>
            <a-input-password v-model:value="form.passphrase" placeholder="没有口令可留空" />
          </div>
        </template>

        <div class="sh-grid">
          <label>主机指纹</label>
          <a-input v-model:value="form.hostKeyFingerprint" placeholder="可留空：首次连接自动记录（TOFU）" />
        </div>

        <div class="sh-form-foot">
          <button class="app-btn ghost" type="button" :disabled="testingDraft" @click="testDraft">
            {{ testingDraft ? '测试中…' : '测试连接' }}
          </button>
          <button class="app-btn" type="button" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button class="app-btn ghost" type="button" @click="editing = false">取消</button>
        </div>
        <div v-if="probe" class="sh-probe">
          连接成功：{{ probe.serverVersion || 'SSH' }} · {{ probe.os }} · home {{ probe.home }}<br />
          指纹：<code>{{ probe.fingerprint }}</code>（{{ probe.algorithm }}）
        </div>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  listSshHosts, createSshHost, updateSshHost, deleteSshHost, testSshHost, testSshHostDraft
} from '../api'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['update:open', 'changed'])

const hosts = ref([])
const editing = ref(false)
const saving = ref(false)
const testingDraft = ref(false)
const testingId = ref('')
const probe = ref(null)
const blank = () => ({
  id: '', name: '', host: '', port: 22, username: '', authType: 'password',
  password: '', privateKey: '', passphrase: '', hostKeyFingerprint: ''
})
const form = reactive(blank())

async function load () {
  try {
    const r = await listSshHosts()
    hosts.value = (r && r.data) || []
  } catch (e) {
    hosts.value = []
    message.warning('读取 SSH 主机失败：' + (e.message || ''))
  }
}

function startCreate () {
  Object.assign(form, blank())
  probe.value = null
  editing.value = true
}
function startEdit (h) {
  Object.assign(form, blank(), {
    id: h.id, name: h.name || '', host: h.host || '', port: h.port || 22,
    username: h.username || '', authType: h.authType || 'password',
    hostKeyFingerprint: h.hostKeyFingerprint || '', password: '', privateKey: '', passphrase: ''
  })
  probe.value = null
  editing.value = true
}

function payload () {
  return {
    name: form.name, host: form.host, port: form.port, username: form.username,
    authType: form.authType,
    password: form.password || null,
    privateKey: form.privateKey || null,
    passphrase: form.passphrase || null,
    hostKeyFingerprint: form.hostKeyFingerprint || null
  }
}

async function testDraft () {
  testingDraft.value = true
  probe.value = null
  try {
    const r = await testSshHostDraft(payload())
    probe.value = (r && r.data) || null
    if (probe.value && !form.hostKeyFingerprint) form.hostKeyFingerprint = probe.value.fingerprint
    message.success('连接成功')
  } catch (e) {
    message.error('连接失败：' + (e.message || ''))
  } finally {
    testingDraft.value = false
  }
}

async function save () {
  if (!form.host || !form.host.trim()) return message.warning('请填写主机地址')
  if (!form.username || !form.username.trim()) return message.warning('请填写登录用户')
  if (!form.id && form.authType === 'password' && !form.password) return message.warning('请填写密码')
  if (!form.id && form.authType === 'private_key' && !form.privateKey) return message.warning('请粘贴私钥')
  saving.value = true
  try {
    const r = form.id ? await updateSshHost(form.id, payload()) : await createSshHost(payload())
    const saved = r && r.data
    message.success(form.id ? '已保存' : '已添加')
    editing.value = false
    await load()
    emit('changed', saved)
  } catch (e) {
    message.error('保存失败：' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function testSaved (h) {
  testingId.value = h.id
  try {
    const r = await testSshHost(h.id)
    const d = (r && r.data) || {}
    message.success('连接成功：' + (d.serverVersion || 'SSH') + ' · ' + (d.os || '') + ' · ' + (d.home || ''))
    await load()
  } catch (e) {
    message.error('连接失败：' + (e.message || ''))
    await load()
  } finally {
    testingId.value = ''
  }
}

async function remove (h) {
  try {
    await deleteSshHost(h.id)
    message.success('已删除')
    await load()
    emit('changed')
  } catch (e) {
    message.error('删除失败：' + (e.message || ''))
  }
}

function shortFp (fp) {
  return fp && fp.length > 24 ? fp.slice(0, 18) + '…' : fp
}

const close = () => emit('update:open', false)
watch(() => props.open, v => { if (v) { editing.value = false; probe.value = null; load() } }, { immediate: true })
</script>

<style scoped>
.sh { display: flex; flex-direction: column; gap: 12px; }
.sh-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.sh-tip { font-size: 12px; color: var(--app-text3); line-height: 1.5; }
.sh-list { display: flex; flex-direction: column; gap: 8px; max-height: 300px; overflow: auto; }
.sh-row { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid var(--app-border, rgba(0,0,0,.08)); border-radius: 10px; }
.sh-main { flex: 1; min-width: 0; }
.sh-name { font-size: 14px; font-weight: 600; color: var(--app-text); display: flex; align-items: center; gap: 8px; }
.sh-badge { font-size: 11px; padding: 0 7px; line-height: 18px; border-radius: 9px; }
.sh-badge.on { color: #0a7a3d; background: rgba(22,163,74,.12); }
.sh-badge.off { color: var(--app-text3); background: rgba(0,0,0,.06); }
.sh-target { font-size: 12px; color: var(--app-text2); margin-top: 2px; }
.sh-meta { font-size: 11px; color: var(--app-text3); margin-top: 3px; display: flex; gap: 12px; flex-wrap: wrap; }
.sh-meta .warn { color: #b45309; }
.sh-meta .err { color: #b91c1c; max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sh-actions { display: flex; gap: 10px; flex-shrink: 0; }
.sh-actions .danger { color: #b91c1c; }
.sh-empty { padding: 28px; text-align: center; color: var(--app-text3); font-size: 13px; border: 1px dashed var(--app-border, rgba(0,0,0,.15)); border-radius: 10px; }
.sh-form { border-top: 1px solid var(--app-border, rgba(0,0,0,.08)); padding-top: 12px; }
.sh-form-title { font-size: 13px; font-weight: 600; margin-bottom: 10px; }
.sh-grid { display: grid; grid-template-columns: 90px 1fr; align-items: center; gap: 8px 10px; margin-bottom: 8px; }
.sh-grid label { font-size: 12px; color: var(--app-text2); }
.sh-form-foot { display: flex; gap: 10px; justify-content: flex-end; margin-top: 12px; }
.sh-probe { margin-top: 10px; font-size: 12px; color: var(--app-text2); background: rgba(22,163,74,.08); padding: 8px 10px; border-radius: 8px; line-height: 1.6; word-break: break-all; }
.sh-probe code { font-size: 11px; }
</style>
