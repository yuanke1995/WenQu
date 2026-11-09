<template>
  <div class="pd-page">
    <div class="pd-card">
      <div class="pd-badge">本机授权</div>
      <h1 class="pd-title">授权这台电脑</h1>
      <p class="pd-desc">
        执行器正在等你在浏览器里确认。确认后，问渠才能读写你在这台电脑上选定的工作区目录；
        不确认就不会留下任何授权。
      </p>

      <div class="pd-code-row">
        <a-input v-model:value="code" class="pd-input" :maxlength="6" placeholder="输入 6 位授权码"
                 autocomplete="off" spellcheck="false"
                 :disabled="state === 'ready' || state === 'approved' || loading"
                 @input="onInput" @press-enter="load" />
        <a-button v-if="state !== 'ready' && state !== 'approved'" :loading="loading" @click="load">查询</a-button>
      </div>

      <div v-if="state === 'ready'" class="pd-device">
        <div class="pd-device-name">{{ info.name }}</div>
        <div class="pd-device-meta">{{ osText }} · 授权码 {{ info.userCode }}</div>
        <div class="pd-device-meta">确认后请回到终端，执行器会自动继续，不用再复制任何令牌。</div>
        <a-button type="primary" size="large" block :loading="approving" @click="approve">确认授权</a-button>
      </div>

      <div v-else-if="state === 'approved'" class="pd-ok">
        <CheckCircleFilled class="pd-ok-icon" />
        <div class="pd-ok-t">已授权</div>
        <p class="pd-desc">若不是你本人发起，请在这台电脑的授权清单里撤销。</p>
      </div>

      <p v-if="error" class="pd-err">{{ error }}</p>
    </div>
    <footer class="pd-foot">
      <span>内容由 AI 生成，请注意甄别</span>
      <a href="/chat" class="pd-brand">问渠 WenQu</a>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { CheckCircleFilled } from '@ant-design/icons-vue'
import { approveExecutorPairing, readExecutorPairing } from '../api'

const route = useRoute()

const code = ref(normalize(route.query.code))
const info = ref({})
const state = ref('idle')
const loading = ref(false)
const approving = ref(false)
const error = ref('')

const OS_NAMES = { darwin: 'macOS', win32: 'Windows', linux: 'Linux' }
const osText = computed(() => OS_NAMES[info.value.os] || info.value.os || '未知系统')

function normalize (v) {
  return String(v || '').trim().toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6)
}

function onInput (e) {
  const v = normalize(e && e.target ? e.target.value : code.value)
  if (v !== code.value) code.value = v
  if (state.value !== 'idle') {
    state.value = 'idle'
    info.value = {}
    error.value = ''
  }
}

async function load () {
  if (code.value.length !== 6) {
    error.value = '请填满 6 位授权码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const r = await readExecutorPairing(code.value)
    info.value = (r && r.data) || {}
    state.value = 'ready'
  } catch (e) {
    error.value = e.message || '这个授权码不可用'
  } finally {
    loading.value = false
  }
}

async function approve () {
  approving.value = true
  error.value = ''
  try {
    await approveExecutorPairing(code.value)
    state.value = 'approved'
  } catch (e) {
    error.value = e.message || '确认失败'
  } finally {
    approving.value = false
  }
}

onMounted(() => {
  if (code.value.length === 6) load()
})
</script>

<style scoped>
.pd-page {
  min-height: 100vh; background: var(--app-bg); color: var(--app-text);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 32px 16px;
}
.pd-card {
  width: 100%; max-width: 480px;
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 14px; padding: 36px 32px 32px;
}
.pd-badge {
  display: inline-block; font-size: 12px; color: var(--app-accent);
  border: 1px solid var(--app-accent); border-radius: 999px; padding: 1px 10px; margin-bottom: 14px;
}
.pd-title { font-size: 20px; font-weight: 600; margin: 0 0 10px; }
.pd-desc { font-size: 13px; color: var(--app-text3); line-height: 1.75; margin: 0 0 20px; }
.pd-code-row { display: flex; gap: 10px; align-items: center; }
.pd-input :deep(input) { font-size: 18px; letter-spacing: 4px; text-align: center; }
.pd-device {
  margin-top: 22px; padding: 16px; border-radius: 10px;
  border: 1px solid var(--app-border); background: var(--app-surface2, rgba(0,0,0,.03));
  display: flex; flex-direction: column; gap: 6px;
}
.pd-device-name { font-size: 15px; font-weight: 600; word-break: break-all; }
.pd-device-meta { font-size: 12px; color: var(--app-text3); line-height: 1.6; }
.pd-device :deep(.ant-btn) { margin-top: 8px; }
.pd-ok { margin-top: 22px; text-align: center; }
.pd-ok-icon { font-size: 40px; color: var(--app-success, #3b8f4e); }
.pd-ok-t { font-size: 17px; font-weight: 600; margin-top: 8px; }
.pd-ok .pd-desc { margin: 8px 0 0; }
.pd-err { margin: 14px 0 0; font-size: 13px; color: #c0392b; line-height: 1.6; }
.pd-foot {
  width: 100%; max-width: 480px; margin-top: 28px; padding-top: 14px;
  border-top: 1px solid var(--app-border);
  display: flex; align-items: center; gap: 12px;
  font-size: 12px; color: var(--app-text3);
}
.pd-brand { margin-left: auto; color: var(--app-accent); text-decoration: none; }
</style>
