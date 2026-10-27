<template>
  <div class="ta-page">
    <div class="ta-card">
      <template v-if="!done">
        <h1 class="ta-title">申请开通一个独立工作区</h1>
        <p class="ta-desc">
          填完这份表格会生成一条待处理的开通申请。平台管理员批准后，你会得到一个独立的工作区
          和第一个管理员账号——你这边的人只能看到自己这边的会话、知识库与用量，
          与平台上其他机构互不相干。
        </p>
        <a-form layout="vertical" class="ta-form" @submit.prevent="submit">
          <a-form-item label="机构名称" required>
            <a-input v-model:value="form.orgName" :maxlength="80" placeholder="会作为工作区显示名，例如「明德科技」" />
          </a-form-item>
          <a-form-item label="想用的标识" extra="选填。地址栏与数据里用的短名，只能小写字母、数字、- 或 _；没想好可以留空，由平台管理员定">
            <a-input v-model:value="form.desiredId" :maxlength="32" placeholder="例如 mingde" />
          </a-form-item>
          <a-form-item label="联系人">
            <a-input v-model:value="form.contactName" :maxlength="60" placeholder="谁负责这件事" />
          </a-form-item>
          <a-form-item label="联系邮箱" required extra="批准结果与第一个账号都会交给这个人，请填能收到邮件的地址">
            <a-input v-model:value="form.contactEmail" :maxlength="160" placeholder="name@company.com" />
          </a-form-item>
          <a-form-item label="打算用它做什么" required>
            <a-textarea v-model:value="form.purpose" :rows="4" :maxlength="500" show-count
                        placeholder="例如：给内部 200 人做文档问答与工单助手，需要接公司单点登录" />
          </a-form-item>
          <div class="ta-actions">
            <a-button type="primary" :loading="busy" @click="submit">{{ busy ? '提交中…' : '提交申请' }}</a-button>
            <a class="ta-login" href="/login">已有账号？去登录</a>
          </div>
        </a-form>
        <p class="ta-note">提交后不会立刻开通：批准由平台管理员人工完成，因为开通意味着建出一个能看见本机构全部数据的管理员账号。</p>
      </template>

      <template v-else>
        <h1 class="ta-title">申请已提交</h1>
        <p class="ta-desc">
          编号 <code class="ta-id">{{ doneId }}</code>。平台管理员会联系
          <b>{{ emailedTo }}</b>；批准后你会收到工作区标识与第一个管理员账号（初始口令只显示一次，登录后请立刻改掉）。
        </p>
        <div class="ta-actions">
          <a-button @click="reset">再填一份</a-button>
          <a class="ta-login" href="/login">去登录</a>
        </div>
      </template>
    </div>
    <footer class="ta-foot">
      <a href="/" class="ta-brand">问渠 WenQu</a>
    </footer>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { applyTenant } from '../api'

const form = reactive({ orgName: '', desiredId: '', contactName: '', contactEmail: '', purpose: '' })
const busy = ref(false)
const done = ref(false)
const doneId = ref('')
const emailedTo = ref('')

async function submit () {
  if (!form.orgName.trim()) { message.error('要填机构名称'); return }
  if (!form.contactEmail.trim()) { message.error('要填联系邮箱'); return }
  if (!form.purpose.trim()) { message.error('要说一句打算用它做什么，审批要看这条'); return }
  busy.value = true
  try {
    const r = await applyTenant({
      orgName: form.orgName.trim(),
      desiredId: form.desiredId.trim(),
      contactName: form.contactName.trim(),
      contactEmail: form.contactEmail.trim(),
      purpose: form.purpose.trim()
    })
    if (!r || !r.success) { message.error((r && r.msg) || '提交失败，请稍后再试'); return }
    doneId.value = (r.data && r.data.id) || ''
    emailedTo.value = form.contactEmail.trim()
    done.value = true
  } catch (e) {
    message.error(e.message || '提交失败，请稍后再试')
  } finally {
    busy.value = false
  }
}

function reset () {
  form.orgName = ''
  form.desiredId = ''
  form.contactName = ''
  form.contactEmail = ''
  form.purpose = ''
  done.value = false
}
</script>

<style scoped>
.ta-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 48px 16px;
  background: var(--app-bg, #f6f7f9);
}
.ta-card {
  width: 100%;
  max-width: 560px;
  padding: 28px 28px 22px;
  border-radius: 12px;
  background: var(--app-panel, #fff);
  border: 1px solid var(--app-border, #eceef1);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}
.ta-title { margin: 0 0 8px; font-size: 20px; font-weight: 600; color: var(--app-text, #1f2329); }
.ta-desc { margin: 0 0 18px; font-size: 13px; line-height: 1.7; color: var(--app-text-2, #66707a); }
.ta-form :deep(.ant-form-item) { margin-bottom: 14px; }
.ta-actions { display: flex; align-items: center; gap: 14px; margin-top: 6px; }
.ta-login { font-size: 13px; color: var(--app-text-2, #66707a); }
.ta-note { margin: 16px 0 0; font-size: 12px; line-height: 1.7; color: var(--app-text-3, #98a1ab); }
.ta-id { padding: 1px 6px; border-radius: 4px; background: var(--app-bg-2, #f2f3f5); font-size: 12px; }
.ta-foot { font-size: 12px; color: var(--app-text-3, #98a1ab); }
.ta-brand { color: inherit; }
</style>
