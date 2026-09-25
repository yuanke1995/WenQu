<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">个人设置</h1>
    </div>
    <div class="pf-body">
      <nav class="pf-nav">
        <button v-for="n in navs" :key="n.key" class="pf-nav-item"
                :class="{ active: current === n.key }" @click="current = n.key">
          {{ n.label }}
        </button>
      </nav>
      <section class="pf-content">
        <!-- 各类型个人默认模型面板 -->
        <div v-if="current === 'chat' || current === 'vision' || current === 'rerank'" class="app-card pf-card">
          <h2 class="app-card-title">{{ panelMeta.title }}</h2>
          <p class="pf-hint">{{ panelMeta.hint }}</p>
          <div class="pf-row">
            <ModelSelect v-model:value="pref[panelMeta.field]" :type="current"
                         inherit-label="跟随全局" :width="360" :disabled="loading" />
            <button class="app-btn" :disabled="saving" @click="save">保存</button>
          </div>
          <p class="pf-sub-hint">{{ panelMeta.tail }}</p>
        </div>

        <!-- 向量模型：仅说明，不提供个人默认（全库向量空间须一致） -->
        <div v-else-if="current === 'embedding'" class="app-card pf-card">
          <h2 class="app-card-title">向量模型</h2>
          <p class="pf-hint">向量模型决定整个知识库的向量空间，必须全局统一，不提供个人默认。</p>
          <p class="pf-sub-hint">更换向量模型需要全量重嵌入，请由管理员在「系统设置 → 向量模型」中调整。</p>
        </div>

        <!-- 账号安全 -->
        <div v-else-if="current === 'security'" class="app-card pf-card">
          <h2 class="app-card-title">修改密码</h2>
          <a-form layout="vertical" style="max-width:360px">
            <a-form-item label="当前密码" required>
              <a-input-password v-model:value="pwdForm.oldPassword" placeholder="请输入当前密码" @pressEnter="submitPwd" />
            </a-form-item>
            <a-form-item label="新密码" required>
              <a-input-password v-model:value="pwdForm.newPassword" placeholder="至少 6 位" />
            </a-form-item>
            <a-form-item label="确认新密码" required>
              <a-input-password v-model:value="pwdForm.confirm" placeholder="再次输入新密码" @pressEnter="submitPwd" />
            </a-form-item>
            <p v-if="pwdError" class="pf-err">{{ pwdError }}</p>
            <button class="app-btn" :disabled="pwdSaving" @click="submitPwd">修改密码</button>
            <span class="pf-sub-hint" style="margin-left:10px">修改成功后需重新登录</span>
          </a-form>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { clearAuth } from '../utils/auth'
import { changePasswordApi, getUserPreference, setUserPreference } from '../api'
import ModelSelect from '../components/ModelSelect.vue'

const router = useRouter()

const navs = [
  { key: 'chat', label: '聊天模型' },
  { key: 'vision', label: '视觉模型' },
  { key: 'rerank', label: '重排模型' },
  { key: 'embedding', label: '向量模型' },
  { key: 'security', label: '账号安全' }
]
const current = ref('chat')

const PANELS = {
  chat: {
    field: 'defaultModel', title: '聊天模型',
    hint: '个人默认聊天模型：智能体未指定、会话未手动选择时使用。',
    tail: '清空（选「跟随全局」）后每次对话需手动选择模型。'
  },
  vision: {
    field: 'defaultVisionModel', title: '视觉模型',
    hint: '对话中上传图片的理解走此模型；留空跟随系统全局。',
    tail: '文档入库时的图片描述始终使用系统全局模型。'
  },
  rerank: {
    field: 'defaultRerankModel', title: '重排模型',
    hint: '对话检索的重排走此模型；留空跟随系统全局。',
    tail: '检索调试与评估始终使用系统全局模型。'
  }
}
const panelMeta = computed(() => PANELS[current.value] || PANELS.chat)

// 三类个人默认（全量保存：任一面板保存都提交三元组当前值）
const loading = ref(false)
const saving = ref(false)
const pref = ref({ defaultModel: '', defaultVisionModel: '', defaultRerankModel: '' })

const load = async () => {
  loading.value = true
  try {
    const r = await getUserPreference()
    const d = (r && r.data) || {}
    pref.value = {
      defaultModel: d.defaultModel || '',
      defaultVisionModel: d.defaultVisionModel || '',
      defaultRerankModel: d.defaultRerankModel || ''
    }
  } catch (e) { /* 拉取失败保持空（跟随全局） */ }
  finally { loading.value = false }
}

const save = async () => {
  saving.value = true
  try {
    await setUserPreference(pref.value)
    message.success('个人默认模型已保存')
  } catch (e) {
    message.error(e.message || '保存失败')
    await load() // 回落服务端状态，避免本地与服务端不一致
  } finally { saving.value = false }
}

// ---- 账号安全：修改密码（成功后强制重新登录） ----
const pwdSaving = ref(false)
const pwdError = ref('')
const pwdForm = ref({ oldPassword: '', newPassword: '', confirm: '' })
const submitPwd = async () => {
  const f = pwdForm.value
  pwdError.value = ''
  if (!f.oldPassword) { pwdError.value = '请输入当前密码'; return }
  if (!f.newPassword || f.newPassword.length < 6) { pwdError.value = '新密码至少 6 位'; return }
  if (f.newPassword !== f.confirm) { pwdError.value = '两次输入的新密码不一致'; return }
  pwdSaving.value = true
  try {
    const r = await changePasswordApi(f.oldPassword, f.newPassword)
    if (r && r.success) {
      message.success('密码已修改，请重新登录')
      clearAuth()
      router.push('/login')
    } else {
      message.error((r && r.msg) || '修改失败')
    }
  } catch (e) { message.error(e.message || '修改失败') }
  finally { pwdSaving.value = false }
}

onMounted(load)
</script>

<style scoped>
.pf-body { flex: 1; min-height: 0; display: flex; }
.pf-nav {
  width: 150px; flex: none; border-right: 1px solid var(--app-border); background: var(--app-panel);
  padding: 10px 8px; display: flex; flex-direction: column; gap: 2px; overflow-y: auto;
}
.pf-nav-item { padding: 7px 10px; border-radius: 8px; font-size: 12px; color: var(--app-text2); cursor: pointer; border: none; background: transparent; text-align: left; }
.pf-nav-item:hover { background: var(--app-accent-weak); }
.pf-nav-item.active { background: var(--app-accent-weak); color: var(--app-text); font-weight: 500; }
.pf-content { flex: 1; min-width: 0; overflow-y: auto; padding: 14px 20px 24px; }
.pf-card { max-width: 640px; padding: 18px 20px; }
.pf-hint { font-size: 12px; color: var(--app-text2); margin: 0 0 12px; }
.pf-sub-hint { font-size: 11px; color: var(--app-text3); margin: 10px 0 0; }
.pf-row { display: flex; align-items: center; gap: 10px; }
.pf-err { color: var(--app-danger); font-size: 12px; margin: 0 0 8px; }
</style>
