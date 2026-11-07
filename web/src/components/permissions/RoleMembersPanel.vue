<template>
  <div class="rmp">
    <div class="rmp-head">
      <span>角色成员 <b>{{ rows.length }}</b></span>
      <span v-if="loadError" class="rmp-err">{{ loadError }} · <button class="app-link-btn" @click="load">重试</button></span>
      <span class="rmp-spacer"></span>
      <button class="app-link-btn" @click="$emit('open-members')">去成员管理</button>
    </div>

    <a-spin :spinning="loading">
      <div v-if="!loading && !rows.length" class="rmp-empty">
        {{ loadError ? '成员数据读取失败，请重试' : '该角色还没有成员' }}
      </div>
      <div v-else class="rmp-list">
        <div class="rmp-row rmp-th">
          <span>账号</span><span>昵称</span><span>授予来源</span><span>状态</span><span>操作</span>
        </div>
        <div v-for="m in rows" :key="m.uid" class="rmp-row">
          <span class="rmp-uid"><i class="rmp-av">{{ initial(m.uid) }}</i><code>{{ m.uid }}</code></span>
          <span>{{ m.username || '—' }}</span>
          <span>
            <span class="app-pill" :class="m.grantedBy === 'idp' ? 'violet' : 'muted'">
              {{ m.grantedBy === 'idp' ? '认证源' : '手动' }}
            </span>
          </span>
          <span>
            <span class="app-pill" :class="m.status === 0 ? 'muted' : 'ok'">{{ m.status === 0 ? '停用' : '正常' }}</span>
          </span>
          <span><button class="app-link-btn" @click="$emit('effective', m.uid)">有效权限</button></span>
        </div>
      </div>
    </a-spin>

    <div class="rmp-tip">认证源授予的角色只读，需在认证源的组映射里调整。</div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getRoleMembers } from '../../api'

const props = defineProps({ roleCode: { type: String, default: '' } })
defineEmits(['effective', 'open-members'])

const loading = ref(false)
const loadError = ref('')
const rows = ref([])

function initial (uid) {
  return String(uid || '?').slice(0, 1).toUpperCase()
}

async function load () {
  if (!props.roleCode) return
  loading.value = true
  loadError.value = ''
  try {
    const r = await getRoleMembers(props.roleCode)
    if (r && r.success === false) throw new Error(r.msg || '成员读取失败')
    rows.value = (r && r.data) || []
  } catch (e) {
    loadError.value = e.message || '成员读取失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.roleCode, load, { immediate: true })
</script>

<style scoped>
.rmp { display: flex; flex-direction: column; min-height: 0; }
.rmp-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 12px; color: var(--app-text2); }
.rmp-head b { color: var(--app-text); }
.rmp-err { font-size: 11px; color: var(--app-danger); }
.rmp-spacer { flex: 1; }
.rmp-list { border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.rmp-row { display: grid; grid-template-columns: 1.1fr .9fr .8fr .6fr .7fr; align-items: center; gap: 8px; padding: 7px 10px; border-bottom: 1px solid var(--app-border); font-size: 11.5px; }
.rmp-row:last-child { border-bottom: 0; }
.rmp-th { background: var(--app-panel-2); color: var(--app-text3); font-size: 10.5px; }
.rmp-uid { display: flex; align-items: center; gap: 6px; min-width: 0; }
.rmp-uid code { font-size: 10px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rmp-av { width: 19px; height: 19px; border-radius: 50%; background: var(--app-accent); color: #fff; font-style: normal; font-size: 9px; display: inline-flex; align-items: center; justify-content: center; flex: none; }
.rmp-empty { padding: 28px 0; text-align: center; font-size: 12px; color: var(--app-text3); }
.rmp-tip { margin-top: 6px; font-size: 11px; color: var(--app-text3); }
</style>
