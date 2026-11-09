<template>
  <div class="ta-page">
    <div class="ta-card">
      <template v-if="!done">
        <div class="ta-head">
          <div>
            <h1 class="ta-title">申请开通一个独立工作区</h1>
            <p class="ta-desc">
              平台管理员批准后，你会得到独立的工作区与第一个管理员账号。
              你这边的人只能看到自己这边的会话、知识库与用量。
            </p>
          </div>
          <span class="ta-step-hint">第 {{ step + 1 }} / 3 步</span>
        </div>

        <div class="ta-steps">
          <div v-for="(s, i) in steps" :key="s" class="ta-step" :class="{ on: i === step, done: i < step }">
            <i>{{ i < step ? '✓' : i + 1 }}</i><span>{{ s }}</span>
          </div>
        </div>

        <div class="ta-body">
          <div class="ta-form-col">
            <!-- 第 1 步：机构信息 -->
            <template v-if="step === 0">
              <a-form layout="vertical" class="ta-form" @submit.prevent>
                <a-form-item label="机构名称" required>
                  <a-input v-model:value="form.orgName" :maxlength="80" placeholder="会作为工作区显示名，例如「明德科技」" />
                </a-form-item>
                <a-form-item label="工作区标识" :validate-status="idState === 'taken' || idState === 'invalid' ? 'error' : (idState === 'ok' ? 'success' : '')"
                             :help="idHint">
                  <a-input v-model:value="form.desiredId" :maxlength="32" placeholder="例如 mingde"
                           @blur="checkId" @input="onIdInput" />
                  <template #extra>
                    <span>小写字母、数字、- 或 _，2–32 位；开通后不可修改。</span>
                    <span v-if="idState === 'checking'" class="ta-id-state">正在检查…</span>
                    <span v-else-if="idState === 'ok'" class="ta-id-state ok">✓ 可用</span>
                    <span v-else-if="idState === 'taken'" class="ta-id-state bad">已被占用</span>
                    <span v-if="idState === 'taken' && idSuggestions.length" class="ta-id-state">
                      试试：<a v-for="s in idSuggestions" :key="s" class="ta-suggest" @click="useSuggestion(s)">{{ s }}</a>
                    </span>
                  </template>
                </a-form-item>
                <div class="ta-two">
                  <a-form-item label="联系人" required>
                    <a-input v-model:value="form.contactName" :maxlength="60" placeholder="谁负责这件事" />
                  </a-form-item>
                  <a-form-item label="联系邮箱" required extra="开通结果与首个管理员凭据会发到这里">
                    <a-input v-model:value="form.contactEmail" :maxlength="160" placeholder="name@company.com" />
                  </a-form-item>
                </div>
              </a-form>
            </template>

            <!-- 第 2 步：使用场景 -->
            <template v-else-if="step === 1">
              <a-form layout="vertical" class="ta-form" @submit.prevent>
                <div class="ta-two">
                  <a-form-item label="团队规模">
                    <a-select v-model:value="form.scale" :options="scaleOptions" placeholder="用于准备容量，不对外公开" allow-clear />
                  </a-form-item>
                  <a-form-item label="企业域名" extra="用于单点登录与自动开通校验，可留空">
                    <a-input v-model:value="form.domain" :maxlength="190" placeholder="company.com" />
                  </a-form-item>
                </div>
                <a-form-item label="需要单点登录（SSO）">
                  <a-switch v-model:checked="form.ssoRequested" />
                  <span class="ta-inline-hint">需要对接 OIDC / SAML / LDAP 时打开，审批通过后平台会联系你配置</span>
                </a-form-item>
                <a-form-item label="打算用它做什么" required>
                  <a-textarea v-model:value="form.purpose" :rows="4" :maxlength="500" show-count
                              placeholder="例如：给内部 200 人做文档问答与工单助手，需要接公司单点登录" />
                </a-form-item>
                <a-form-item label="企业认证材料" extra="选填，PDF / PNG / JPG / WEBP，单份 ≤ 8MB，最多 5 份">
                  <div class="ta-materials">
                    <div v-for="(m, i) in materials" :key="m.path" class="ta-material">
                      <a :href="m.url" target="_blank" rel="noopener">{{ m.name }}</a>
                      <span class="ta-material-size">{{ fmtSize(m.size) }}</span>
                      <button type="button" class="ta-material-del" @click="materials.splice(i, 1)">移除</button>
                    </div>
                    <label v-if="materials.length < 5" class="ta-upload">
                      <input type="file" accept=".pdf,.png,.jpg,.jpeg,.webp" hidden @change="onUpload" />
                      {{ uploading ? '上传中…' : '＋ 上传材料' }}
                    </label>
                  </div>
                </a-form-item>
              </a-form>
            </template>

            <!-- 第 3 步：确认 -->
            <template v-else>
              <div class="ta-confirm">
                <div class="ta-confirm-row"><span>机构名称</span><b>{{ form.orgName }}</b></div>
                <div class="ta-confirm-row"><span>工作区标识</span><code>{{ form.desiredId || '（由平台管理员定）' }}</code></div>
                <div class="ta-confirm-row"><span>联系人</span><b>{{ form.contactName }}</b></div>
                <div class="ta-confirm-row"><span>联系邮箱</span><b>{{ form.contactEmail }}</b></div>
                <div class="ta-confirm-row"><span>团队规模</span><b>{{ form.scale || '未填' }}</b></div>
                <div class="ta-confirm-row"><span>企业域名</span><b>{{ form.domain || '未填' }}</b></div>
                <div class="ta-confirm-row"><span>单点登录</span><b>{{ form.ssoRequested ? '需要' : '暂不需要' }}</b></div>
                <div class="ta-confirm-row"><span>认证材料</span><b>{{ materials.length ? materials.length + ' 份' : '未上传' }}</b></div>
                <div class="ta-confirm-purpose"><span>用途</span><p>{{ form.purpose }}</p></div>
              </div>
              <p class="ta-note">提交后由平台管理员人工审核；审核结果会发到 {{ form.contactEmail }}。</p>
            </template>

            <div class="ta-actions">
              <a-button v-if="step > 0" @click="step--">上一步</a-button>
              <a-button v-if="step < 2" type="primary" @click="next">下一步</a-button>
              <a-button v-else type="primary" :loading="busy" @click="submit">{{ busy ? '提交中…' : '提交申请' }}</a-button>
              <router-link class="ta-login" to="/login">已有账号？去登录</router-link>
            </div>
          </div>

          <aside class="ta-aside">
            <div class="ta-aside-title">为什么要填这些？</div>
            <div class="ta-aside-item"><b>标识</b><span>地址栏与数据归属的短名，审批时会再核对一次占用。</span></div>
            <div class="ta-aside-item"><b>邮箱</b><span>开通结果与首个管理员凭据会发到这里。</span></div>
            <div class="ta-aside-item"><b>规模与域名</b><span>审批人据此判断容量与是否需要单点登录。</span></div>
            <div class="ta-aside-item"><b>认证材料</b><span>选填；补充营业执照或域名证明可以加快审核。</span></div>
            <div class="ta-aside-foot">提交即表示同意平台服务条款。资料仅用于开通审核。</div>
            <router-link class="ta-aside-link" to="/apply/status">已提交过？查询进度 →</router-link>
          </aside>
        </div>
      </template>

      <template v-else>
        <div class="ta-success">
          <div class="ta-success-badge" :class="{ ok: autoApproved }">{{ autoApproved ? '已开通' : '已提交' }}</div>
          <h1 class="ta-title">{{ autoApproved ? '工作区已自动开通' : '申请已受理' }}</h1>
          <p class="ta-desc">
            申请编号 <code class="ta-id">{{ doneId }}</code>。
            <template v-if="autoApproved && autoEmailSent">工作区地址与管理员凭据已发送到 <b>{{ emailedTo }}</b>。</template>
            <template v-else-if="autoApproved">工作区已自动开通，但凭据邮件未发出；请联系平台管理员获取账号与初始口令。</template>
            <template v-else>平台会联系 <b>{{ emailedTo }}</b>；审核结果会通过邮件通知你。</template>
          </p>
          <div class="ta-success-actions">
            <router-link class="ta-btn primary" :to="{ path: '/apply/status', query: { id: doneId, email: emailedTo } }">查询进度</router-link>
            <a-button @click="reset">再填一份</a-button>
            <router-link class="ta-login" to="/login">去登录</router-link>
          </div>
        </div>
      </template>
    </div>
    <footer class="ta-foot">
      <a href="/" class="ta-brand">问渠 WenQu</a>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { applyTenant, checkTenantId, getTenantResubmitPreview, uploadTenantMaterial } from '../api'

const route = useRoute()
const steps = ['机构信息', '使用场景', '确认提交']
const step = ref(0)
const busy = ref(false)
const uploading = ref(false)

const form = reactive({
  orgName: '', desiredId: '', contactName: '', contactEmail: '',
  purpose: '', scale: '', domain: '', ssoRequested: false
})
const materials = ref([])
const scaleOptions = [
  { value: '1-50', label: '1–50 人' },
  { value: '51-200', label: '51–200 人' },
  { value: '201-1000', label: '201–1000 人' },
  { value: '1000+', label: '1000 人以上' }
]

// ---------- 标识实时校验 ----------
const idState = ref('')       // '' | checking | ok | taken | invalid
const idHint = ref('')
const idSuggestions = ref([])
let idTimer = null
function onIdInput () {
  idState.value = ''
  idHint.value = ''
  clearTimeout(idTimer)
  const v = (form.desiredId || '').trim()
  if (!v) return
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(v)) {
    idState.value = 'invalid'
    idHint.value = '只能用 2–32 位小写字母、数字、- 或 _，且以字母或数字开头'
    return
  }
  idTimer = setTimeout(checkId, 400)
}
async function checkId () {
  const v = (form.desiredId || '').trim().toLowerCase()
  if (!v) return
  if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(v)) {
    idState.value = 'invalid'
    idHint.value = '只能用 2–32 位小写字母、数字、- 或 _，且以字母或数字开头'
    return
  }
  idState.value = 'checking'
  try {
    const r = await checkTenantId(v)
    if (!r || !r.success) { idState.value = ''; return }
    const d = r.data || {}
    idSuggestions.value = d.suggestions || []
    if (d.available) {
      idState.value = 'ok'
      idHint.value = ''
    } else {
      idState.value = d.reason && d.reason.includes('已被占用') ? 'taken' : 'invalid'
      idHint.value = d.reason || '这个标识不可用'
    }
  } catch (e) {
    idState.value = ''
  }
}

function useSuggestion (s) {
  form.desiredId = s
  checkId()
}

// ---------- 材料上传 ----------
async function onUpload (e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  uploading.value = true
  try {
    const r = await uploadTenantMaterial(file)
    if (!r || !r.success) { message.error((r && r.msg) || '上传失败'); return }
    materials.value.push(r.data)
  } catch (err) {
    message.error(err.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

// ---------- 分步 ----------
function next () {
  if (step.value === 0) {
    if (!form.orgName.trim()) { message.error('要填机构名称'); return }
    if (!form.contactName.trim()) { message.error('要填联系人'); return }
    if (!/^[^@\s]{1,64}@[^@\s]{1,190}$/.test(form.contactEmail.trim())) { message.error('联系邮箱格式不对'); return }
    if (form.desiredId && idState.value === 'taken') { message.error('这个标识已被占用，换一个或留空'); return }
    if (form.desiredId && idState.value === 'invalid') { message.error('工作区标识格式不对'); return }
  }
  if (step.value === 1 && !form.purpose.trim()) { message.error('要说一句打算用它做什么，审批要看这条'); return }
  step.value++
}

async function submit () {
  if (busy.value) return
  if (idState.value === 'checking') { message.error('标识还在校验，请稍候'); return }
  busy.value = true
  try {
    const r = await applyTenant({
      orgName: form.orgName.trim(),
      desiredId: form.desiredId.trim().toLowerCase(),
      contactName: form.contactName.trim(),
      contactEmail: form.contactEmail.trim(),
      purpose: form.purpose.trim(),
      scale: form.scale || '',
      domain: form.domain.trim(),
      ssoRequested: form.ssoRequested ? 1 : 0,
      materials: materials.value.length ? JSON.stringify(materials.value.map(m => ({ name: m.name, path: m.path, size: m.size }))) : '',
      resubmitOf: route.query.resubmit ? String(route.query.resubmit) : ''
    })
    if (!r || !r.success) { message.error((r && r.msg) || '提交失败，请稍后再试'); return }
    doneId.value = (r.data && r.data.id) || ''
    emailedTo.value = form.contactEmail.trim()
    autoApproved.value = !!(r.data && r.data.autoApproved)
    autoEmailSent.value = !!(r.data && r.data.emailSent)
    done.value = true
  } catch (e) {
    message.error(e.message || '提交失败，请稍后再试')
  } finally {
    busy.value = false
  }
}

// ---------- 提交结果 ----------
const done = ref(false)
const doneId = ref('')
const emailedTo = ref('')
const autoApproved = ref(false)
const autoEmailSent = ref(false)

function reset () {
  step.value = 0
  Object.assign(form, {
    orgName: '', desiredId: '', contactName: '', contactEmail: '',
    purpose: '', scale: '', domain: '', ssoRequested: false
  })
  materials.value = []
  idState.value = ''
  idHint.value = ''
  autoEmailSent.value = false
  done.value = false
}

function fmtSize (n) {
  if (n == null) return ''
  if (n < 1024) return n + ' B'
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1048576).toFixed(1) + ' MB'
}

// 驳回后重提：带 ?resubmit=<id>&email=<email>，预填原单信息
onMounted(async () => {
  const id = route.query.resubmit
  const email = route.query.email
  if (!id || !email) return
  try {
    const r = await getTenantResubmitPreview(String(id), String(email))
    if (!r || !r.success) return
    const d = r.data || {}
    Object.assign(form, {
      orgName: d.orgName || '', desiredId: d.desiredId || '', contactName: d.contactName || '',
      contactEmail: d.contactEmail || '', purpose: d.purpose || '', scale: d.scale || '',
      domain: d.domain || '', ssoRequested: !!d.ssoRequested
    })
    if (d.materials) {
      if (Array.isArray(d.materials)) {
        materials.value = d.materials
      } else {
        try {
          const arr = JSON.parse(d.materials)
          materials.value = Array.isArray(arr) ? arr : []
        } catch (e) { /* 旧数据格式不对就留空 */ }
      }
    }
    message.info('已带回上一单的信息，修改后重新提交')
  } catch (e) { /* 预填失败不阻断填写 */ }
})
</script>

<style scoped>
.ta-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 44px 16px;
  background: var(--app-bg, #f6f7f9);
}
.ta-card {
  width: 100%;
  max-width: 880px;
  padding: 30px 32px 26px;
  border-radius: 12px;
  background: var(--app-panel, #fff);
  border: 1px solid var(--app-border, #eceef1);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}
.ta-head { display: flex; align-items: flex-start; gap: 16px; }
.ta-title { margin: 0 0 8px; font-size: 21px; font-weight: 650; color: var(--app-text, #1f2329); }
.ta-desc { margin: 0; font-size: 13px; line-height: 1.7; color: var(--app-text-2, #66707a); }
.ta-step-hint { margin-left: auto; font-size: 12px; color: var(--app-text-3, #98a1ab); white-space: nowrap; }
.ta-steps { display: flex; align-items: center; gap: 10px; margin: 20px 0 22px; }
.ta-step { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text-3, #98a1ab); }
.ta-step i {
  width: 20px; height: 20px; border-radius: 50%; border: 1px solid var(--app-border-strong, #d8dde3);
  display: inline-flex; align-items: center; justify-content: center; font-style: normal; font-size: 11px; background: #fff;
}
.ta-step.on { color: var(--app-accent, #2e6be6); font-weight: 600; }
.ta-step.on i { background: var(--app-accent, #2e6be6); border-color: var(--app-accent, #2e6be6); color: #fff; }
.ta-step.done i { background: var(--app-ok-weak, #eaf5ec); border-color: #a5d6b0; color: var(--app-ok, #3b8f4e); }
.ta-step + .ta-step::before { content: ""; width: 28px; height: 1px; background: var(--app-border, #e6e9ed); margin-right: 4px; }
.ta-body { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(0, 0.9fr); gap: 24px; }
.ta-form :deep(.ant-form-item) { margin-bottom: 14px; }
.ta-two { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.ta-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
.ta-login { font-size: 12px; color: var(--app-text-2, #66707a); margin-left: auto; }
.ta-note { margin: 10px 0 0; font-size: 12px; line-height: 1.7; color: var(--app-text-3, #98a1ab); }
.ta-id-state { margin-left: 6px; }
.ta-id-state.ok { color: var(--app-ok, #3b8f4e); }
.ta-id-state.bad { color: var(--app-danger, #d4552e); }
.ta-suggest { margin-left: 6px; }
.ta-aside {
  border: 1px solid var(--app-border, #e6e9ed); border-radius: 10px; background: var(--app-panel-2, #fafbfc);
  padding: 16px 16px 14px; align-self: start; font-size: 12px;
}
.ta-aside-title { font-weight: 650; color: var(--app-text, #1f2329); margin-bottom: 10px; }
.ta-aside-item { margin-bottom: 10px; line-height: 1.6; }
.ta-aside-item b { display: block; color: var(--app-text, #1f2329); font-size: 12px; }
.ta-aside-item span { color: var(--app-text-2, #66707a); }
.ta-aside-foot { border-top: 1px solid var(--app-border, #e6e9ed); padding-top: 10px; color: var(--app-text-3, #98a1ab); line-height: 1.6; }
.ta-aside-link { display: inline-block; margin-top: 10px; color: var(--app-accent, #2e6be6); font-size: 12px; }
.ta-inline-hint { margin-left: 10px; font-size: 12px; color: var(--app-text-3, #98a1ab); }
.ta-materials { display: flex; flex-direction: column; gap: 6px; }
.ta-material { display: flex; align-items: center; gap: 10px; padding: 7px 10px; border: 1px solid var(--app-border, #e6e9ed); border-radius: 7px; background: #fff; }
.ta-material a { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text, #1f2329); }
.ta-material-size { font-size: 11px; color: var(--app-text-3, #98a1ab); }
.ta-material-del { border: 0; background: none; color: var(--app-danger, #d4552e); font-size: 12px; cursor: pointer; }
.ta-upload {
  display: inline-flex; align-items: center; justify-content: center; height: 32px; padding: 0 12px;
  border: 1px dashed var(--app-border-strong, #d8dde3); border-radius: 7px; cursor: pointer;
  font-size: 12px; color: var(--app-text-2, #66707a); background: #fff;
}
.ta-confirm { border: 1px solid var(--app-border, #e6e9ed); border-radius: 10px; overflow: hidden; }
.ta-confirm-row { display: flex; gap: 16px; padding: 10px 14px; border-bottom: 1px solid var(--app-border, #e6e9ed); font-size: 13px; }
.ta-confirm-row span { width: 92px; flex: none; color: var(--app-text-3, #98a1ab); }
.ta-confirm-row b { color: var(--app-text, #1f2329); font-weight: 600; }
.ta-confirm-purpose { display: flex; gap: 16px; padding: 10px 14px; font-size: 13px; }
.ta-confirm-purpose span { width: 92px; flex: none; color: var(--app-text-3, #98a1ab); }
.ta-confirm-purpose p { margin: 0; color: var(--app-text, #1f2329); line-height: 1.7; }
.ta-success { text-align: center; padding: 18px 0 8px; }
.ta-success-badge { display: inline-block; font-size: 11px; font-weight: 650; color: var(--app-warn-text, #a3691b); background: var(--app-warn-weak, #faf3e6); border-radius: 9px; padding: 2px 9px; margin-bottom: 10px; }
.ta-success-badge.ok { color: var(--app-ok, #3b8f4e); background: var(--app-ok-weak, #eaf5ec); }
.ta-success-actions { display: flex; align-items: center; justify-content: center; gap: 10px; margin-top: 18px; flex-wrap: wrap; }
.ta-btn { display: inline-flex; align-items: center; height: 32px; padding: 0 14px; border-radius: 6px; font-size: 13px; text-decoration: none; }
.ta-btn.primary { background: var(--app-accent, #2e6be6); color: #fff; }
.ta-id { padding: 1px 6px; border-radius: 4px; background: var(--app-bg-2, #f2f3f5); font-size: 12px; }
.ta-foot { font-size: 12px; color: var(--app-text-3, #98a1ab); }
.ta-brand { color: inherit; }
@media (max-width: 760px) {
  .ta-card { padding: 22px 18px; }
  .ta-body { grid-template-columns: 1fr; }
  .ta-aside { order: -1; }
  .ta-two { grid-template-columns: 1fr; }
  .ta-step + .ta-step::before { width: 10px; }
}
</style>
