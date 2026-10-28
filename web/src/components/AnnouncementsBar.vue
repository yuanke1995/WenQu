<template>
  <!--
    平台公告横幅（登录后常显）：读本租户发布中的公告，逐条可关闭（sessionStorage 记住本次会话已关），
    danger 级别常驻不可关——停机这种事不接受「我没看见」。放在内容区之上（与断网横幅同级）：
    「平台要停机」是持续状态，不是一条读完即走的通知。
  -->
  <div v-if="shown.length" class="ann-bar">
    <a-alert v-for="a in shown" :key="a.id" :type="typeOf(a.level)" show-icon class="ann-item"
             :message="a.title" :closable="a.level !== 'danger'" @close="dismiss(a)">
      <template v-if="a.content" #description>{{ a.content }}</template>
    </a-alert>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getActiveAnnouncements } from '../api'

const shown = ref([])

const typeOf = level => ({ info: 'info', warn: 'warning', danger: 'error' }[level] || 'info')
const dismissed = a => sessionStorage.getItem('ann-dismissed-' + a.id) === '1'
const dismiss = a => { sessionStorage.setItem('ann-dismissed-' + a.id, '1'); shown.value = shown.value.filter(x => x.id !== a.id) }

onMounted(async () => {
  try {
    const r = await getActiveAnnouncements()
    if (r.success) shown.value = (r.data || []).filter(a => !dismissed(a))
  } catch (e) { /* 公告拿不到不挡人：横幅是增强，不是链路 */ }
})
</script>

<style scoped>
.ann-bar { display: flex; flex-direction: column; gap: 8px; padding: 8px 16px 0; }
.ann-item { margin: 0; }
</style>
