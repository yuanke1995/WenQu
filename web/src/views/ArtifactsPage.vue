<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">我的产物</h1>
      <span class="head-hint-plain">模型在回答里生成的可下载文件（Markdown / CSV / JSON / HTML），按用户归属保存</span>
      <a-input v-model:value="keyword" placeholder="按文件名搜索" allow-clear style="width:200px;margin-left:auto"
               @press-enter="load" @change="onKeywordChange" />
      <button class="app-btn" :disabled="loading" @click="load">
        <reload-outlined /> 刷新
      </button>
    </div>

    <div class="app-page-body">
      <a-spin :spinning="loading">
        <div v-if="!rows.length" class="app-card art-empty">
          <p class="art-empty-title">{{ keyword.trim() ? '没有匹配的产物' : '还没有产物' }}</p>
          <p class="head-hint-plain">
            {{ keyword.trim() ? '换个关键词试试' : '在对话里让 AI「把结果整理成一份清单 / 表格」，生成的文件会出现在这里' }}
          </p>
        </div>

        <div v-else class="art-list">
          <div v-for="r in rows" :key="r.id" class="app-card art-row">
            <file-text-outlined class="art-icon" />
            <div class="art-main">
              <div class="art-line">
                <span class="art-name">{{ r.filename }}</span>
                <span class="art-chip">{{ r.ext || 'file' }}</span>
              </div>
              <div class="art-sub">
                <span>{{ fmtSize(r.size) }}</span>
                <span class="art-dot">·</span>
                <span>{{ fmtTime(r.createTime) }}</span>
                <template v-if="r.description">
                  <span class="art-dot">·</span>
                  <span class="art-desc">{{ r.description }}</span>
                </template>
              </div>
            </div>
            <a :href="r.url" :download="r.filename" class="app-btn ghost small" title="下载">
              <download-outlined /> 下载
            </a>
            <button class="app-btn ghost small art-del" @click="doDelete(r)">
              <delete-outlined />
            </button>
          </div>
        </div>
      </a-spin>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined, FileTextOutlined, DownloadOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { listArtifacts, deleteArtifact } from '../api'

const rows = ref([])
const loading = ref(false)
const keyword = ref('')

const load = async () => {
  loading.value = true
  try {
    const r = await listArtifacts(keyword.value.trim())
    rows.value = (r && r.data) || []
  } catch (e) {
    // 列表失败要说清（不许静默空列表 ⇒ 用户会以为产物丢了）
    message.error('产物列表加载失败：' + (e.message || '请刷新重试'))
    rows.value = []
  } finally {
    loading.value = false
  }
}

// 清空搜索框立即恢复全量（不然用户以为产物少了）
const onKeywordChange = e => {
  if (!e || !e.target || e.target.value === '') load()
}

const fmtSize = n => {
  const v = Number(n) || 0
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(2) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}

const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

const doDelete = row => {
  Modal.confirm({
    title: '删除该产物？',
    content: `将删除「${row.filename}」及其文件，删除后不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteArtifact(row.id)
        if (r && r.success) {
          message.success('已删除')
          await load()
        } else {
          message.error((r && r.msg) || '删除失败')
        }
      } catch (e) {
        message.error('删除失败：' + (e.message || ''))
      }
    }
  })
}

onMounted(load)
</script>

<style scoped>
.art-list { display: flex; flex-direction: column; gap: 8px; }
.art-row { display: flex; align-items: center; gap: 12px; }
.art-icon { font-size: 18px; color: var(--app-accent); }
.art-main { flex: 1; min-width: 0; }
.art-line { display: flex; align-items: center; gap: 8px; }
.art-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-chip {
  font-size: 11px; padding: 1px 6px; border-radius: 4px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.art-sub { margin-top: 2px; font-size: 12px; color: var(--app-text3); display: flex; gap: 4px; min-width: 0; }
.art-desc { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-dot { opacity: 0.6; }
.art-del { color: var(--app-danger, #d4380d); }
.art-empty { text-align: center; padding: 28px 16px; }
.art-empty-title { margin: 0 0 6px; font-weight: 500; }
</style>
