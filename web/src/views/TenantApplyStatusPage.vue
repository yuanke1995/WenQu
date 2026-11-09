<template>
  <div class="ts-page">
    <div class="ts-card">
      <div class="ts-head">
        <div>
          <h1 class="ts-title">查询开通进度</h1>
          <p class="ts-desc">用申请编号和提交时的邮箱查询。两者缺一不可，避免他人探测。</p>
        </div>
        <router-link class="ts-back" to="/apply">申请开通 →</router-link>
      </div>

      <a-form layout="vertical" class="ts-form" @submit.prevent="query">
        <div class="ts-two">
          <a-form-item label="申请编号" required>
            <a-input v-model:value="form.id" placeholder="提交后拿到的编号" @pressEnter="query" />
          </a-form-item>
          <a-form-item label="提交时的邮箱" required>
            <a-input v-model:value="form.email" placeholder="name@company.com" @pressEnter="query" />
          </a-form-item>
        </div>
        <a-button type="primary" :loading="loading" @click="query">查询</a-button>
      </a-form>

      <template v-if="result">
        <div class="ts-result">
          <div class="ts-result-head">
            <span class="ts-tag" :class="result.status">{{ statusLabel(result.status) }}</span>
            <span class="ts-org">{{ result.orgName }}</span>
            <code class="ts-id">{{ result.id }}</code>
          </div>

          <div class="ts-track">
            <div class="ts-node done"><i>✓</i><div><b>已提交</b><span>{{ fmt(result.createTime) }}</span></div></div>
            <div class="ts-line" :class="{ done: result.status !== 'pending' }"></div>
            <div class="ts-node" :class="{ done: result.status === 'approved', bad: result.status === 'rejected', on: result.status === 'pending' }">
              <i>{{ result.status === 'rejected' ? '×' : (result.status === 'approved' ? '✓' : '●') }}</i>
              <div><b>{{ result.status === 'pending' ? '平台审核中' : (result.status === 'rejected' ? '未通过' : '已通过') }}</b><span>{{ fmt(result.reviewedAt) || '预计 1 个工作日' }}</span></div>
            </div>
            <div class="ts-line" :class="{ done: result.status === 'approved' }"></div>
            <div class="ts-node" :class="{ done: result.status === 'approved' }">
              <i>{{ result.status === 'approved' ? '✓' : '3' }}</i>
              <div><b>开通并交付</b><span>{{ result.deliveredAt ? fmt(result.deliveredAt) : '等待平台交付' }}</span></div>
            </div>
          </div>

          <div v-if="result.status === 'approved'" class="ts-box ok">
            <template v-if="result.deliveredAt">
              工作区 <code>{{ result.openedTenant || '已开通' }}</code> 已开通。工作区地址与管理员凭据已发送到提交时填写的邮箱；
              没收到请联系平台管理员重发。
            </template>
            <template v-else>
              工作区 <code>{{ result.openedTenant || '已开通' }}</code> 已开通。凭据邮件尚未发出，请联系平台管理员获取管理员账号与初始口令。
            </template>
          </div>
          <div v-else-if="result.status === 'rejected'" class="ts-box bad">
            <b>驳回说明：</b>{{ result.reviewNote || '未填写说明' }}
            <div class="ts-box-actions">
              <router-link class="ts-btn" :to="{ path: '/apply', query: { resubmit: result.id, email: form.email } }">修改后重新申请</router-link>
            </div>
          </div>
          <div v-else class="ts-box">
            申请正在审核中。平台会通过邮件通知结果；你也可以稍后再来查。
          </div>
        </div>
      </template>

      <p v-if="error" class="ts-err">{{ error }}</p>
      <p class="ts-foot">已有账号？<router-link to="/login">去登录</router-link></p>
    </div>
    <footer class="ts-page-foot">问渠 WenQu</footer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { getTenantRequestStatus } from '../api'

const route = useRoute()
const form = reactive({ id: String(route.query.id || ''), email: String(route.query.email || '') })
const loading = ref(false)
const result = ref(null)
const error = ref('')

const statusLabel = s => ({ pending: '待审核', approved: '已开通', rejected: '已驳回' }[s] || s)

async function query () {
  error.value = ''
  if (!form.id.trim() || !form.email.trim()) {
    message.error('要同时填申请编号与提交时的邮箱')
    return
  }
  loading.value = true
  try {
    const r = await getTenantRequestStatus(form.id.trim(), form.email.trim())
    if (!r || !r.success) {
      result.value = null
      error.value = (r && r.msg) || '没有匹配的申请，请核对编号与邮箱'
      return
    }
    result.value = r.data || null
  } catch (e) {
    result.value = null
    error.value = e.message || '查询失败，请稍后再试'
  } finally {
    loading.value = false
  }
}

function fmt (s) {
  if (!s) return ''
  return String(s).replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  if (form.id && form.email) query()
})
</script>

<style scoped>
.ts-page {
  min-height: 100vh; display: flex; flex-direction: column; align-items: center; gap: 16px;
  padding: 48px 16px; background: var(--app-bg, #f6f7f9);
}
.ts-card {
  width: 100%; max-width: 620px; padding: 28px 30px 24px; border-radius: 12px;
  background: var(--app-panel, #fff); border: 1px solid var(--app-border, #eceef1);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}
.ts-head { display: flex; align-items: flex-start; gap: 14px; }
.ts-title { margin: 0 0 6px; font-size: 21px; font-weight: 650; color: var(--app-text, #1f2329); }
.ts-desc { margin: 0; font-size: 13px; line-height: 1.7; color: var(--app-text-2, #66707a); }
.ts-back { margin-left: auto; font-size: 12px; color: var(--app-accent, #2e6be6); white-space: nowrap; }
.ts-form { margin: 20px 0 4px; }
.ts-two { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.ts-result { margin-top: 20px; border-top: 1px solid var(--app-border, #e6e9ed); padding-top: 18px; }
.ts-result-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; }
.ts-org { font-size: 15px; font-weight: 650; color: var(--app-text, #1f2329); }
.ts-id { font-size: 11px; color: var(--app-text-3, #98a1ab); background: var(--app-bg-2, #f2f3f5); padding: 1px 6px; border-radius: 4px; }
.ts-tag { font-size: 11px; font-weight: 650; padding: 2px 9px; border-radius: 9px; }
.ts-tag.pending { background: var(--app-warn-weak, #faf3e6); color: var(--app-warn-text, #a3691b); }
.ts-tag.approved { background: var(--app-ok-weak, #eaf5ec); color: var(--app-ok, #3b8f4e); }
.ts-tag.rejected { background: var(--app-danger-weak, #fff2f0); color: var(--app-danger, #d4552e); }
.ts-track { display: flex; align-items: flex-start; gap: 8px; }
.ts-node { display: flex; align-items: flex-start; gap: 7px; flex: none; max-width: 150px; }
.ts-node i {
  width: 20px; height: 20px; border-radius: 50%; border: 1px solid var(--app-border-strong, #d8dde3);
  background: #fff; color: var(--app-text-3, #98a1ab); display: inline-flex; align-items: center; justify-content: center;
  font-style: normal; font-size: 10px; flex: none;
}
.ts-node b { display: block; font-size: 12px; color: var(--app-text, #1f2329); font-weight: 600; }
.ts-node span { display: block; font-size: 11px; color: var(--app-text-3, #98a1ab); margin-top: 2px; line-height: 1.5; }
.ts-node.done i { background: var(--app-ok-weak, #eaf5ec); border-color: #a5d6b0; color: var(--app-ok, #3b8f4e); }
.ts-node.on i { background: var(--app-accent-weak, #eef2f7); border-color: var(--app-accent, #2e6be6); color: var(--app-accent, #2e6be6); }
.ts-node.bad i { background: var(--app-danger-weak, #fff2f0); border-color: #ffccc7; color: var(--app-danger, #d4552e); }
.ts-line { flex: 1; height: 1px; background: var(--app-border, #e6e9ed); margin-top: 10px; min-width: 12px; }
.ts-line.done { background: #a5d6b0; }
.ts-box { margin-top: 16px; border-radius: 8px; padding: 12px 14px; font-size: 12.5px; line-height: 1.7; background: var(--app-panel-2, #fafbfc); border: 1px solid var(--app-border, #e6e9ed); color: var(--app-text-2, #66707a); }
.ts-box.ok { background: var(--app-ok-weak, #eaf5ec); border-color: #cfe8d5; color: var(--app-ok, #3b8f4e); }
.ts-box.bad { background: var(--app-danger-weak, #fff2f0); border-color: #ffccc7; color: var(--app-danger, #d4552e); }
.ts-box-actions { margin-top: 8px; }
.ts-btn { display: inline-flex; align-items: center; height: 28px; padding: 0 12px; border-radius: 6px; background: var(--app-accent, #2e6be6); color: #fff; font-size: 12px; text-decoration: none; }
.ts-err { margin-top: 14px; font-size: 12.5px; color: var(--app-danger, #d4552e); }
.ts-foot { margin: 18px 0 0; font-size: 12px; color: var(--app-text-3, #98a1ab); }
.ts-page-foot { font-size: 12px; color: var(--app-text-3, #98a1ab); }
@media (max-width: 620px) {
  .ts-card { padding: 22px 18px; }
  .ts-two { grid-template-columns: 1fr; }
  .ts-track { flex-direction: column; }
  .ts-line { width: 1px; height: 16px; margin: 0 0 0 9px; }
  .ts-node { max-width: none; }
}
</style>
