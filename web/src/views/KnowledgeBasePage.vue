<template>
  <div class="kb-page">
    <div class="app-page-head">
      <h3 class="app-page-title">知识库</h3>
      <span class="head-hint-plain">文档的容器：检索按库隔离，检索参数随库（留空继承全局检索设置）</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建知识库
      </button>
    </div>

    <a-spin :spinning="loading">
      <div class="kb-cards">
        <div v-for="kb in list" :key="kb.id" class="app-card kb-card" @click="openDocs(kb)">
          <div class="kb-card-head">
            <database-outlined class="kb-card-ic" />
            <span class="kb-name">{{ kb.name }}</span>
            <a-tag v-if="kb.isDefault === 1" color="blue" style="margin-left:auto">默认</a-tag>
          </div>
          <p class="kb-card-desc" :title="kb.description || ''">{{ kb.description || '暂无描述' }}</p>
          <div class="kb-card-meta">
            <span :class="{ 'kb-zero': !kb.docCount }">{{ kb.docCount }} 个文档</span>
            <span class="kb-meta-sep">·</span>
            <span v-if="kb.embeddingRef" :title="kb.embeddingRef">{{ modelRefInfo(kb.embeddingRef)?.displayName || '自定义向量模型' }}</span>
            <span v-else>跟随全局向量</span>
            <span class="kb-meta-sep">·</span>
            <span v-if="kb.queryParams" class="kb-params" :title="kb.queryParams">检索参数已自定义</span>
            <span v-else class="kb-dim">继承全局检索</span>
          </div>
          <div class="kb-card-actions" @click.stop>
            <button class="app-link-btn" @click="openEdit(kb)">编辑</button>
            <button class="app-link-btn danger" :disabled="kb.isDefault === 1" @click="onDelete(kb)">删除</button>
            <button class="app-link-btn" style="margin-left:auto" @click="openDocs(kb)">文档管理 →</button>
          </div>
        </div>
        <div v-if="!loading && !list.length" class="kb-empty">还没有知识库，点右上角「新建知识库」创建</div>
      </div>
    </a-spin>

    <a-modal v-model:open="showEdit" :title="editing ? '编辑知识库' : '新建知识库'"
             :confirm-loading="saving" @ok="save">
      <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如：操作手册库" />
        </a-form-item>
        <a-form-item label="描述">
          <a-input v-model:value="form.description" placeholder="这个库放什么资料" />
        </a-form-item>
        <a-form-item label="检索参数">
          <a-textarea v-model:value="form.queryParams" :rows="3"
                      placeholder='JSON，如 {"retrieval.vecThreshold":"0.3"}；留空=继承全局检索设置' />
          <div class="kb-hint">只对检索/重排类键生效；留空恢复继承。配错不会放宽范围，只会让结果变少。</div>
        </a-form-item>
        <a-form-item label="向量模型">
          <ModelSelect v-model="form.embeddingRef" type="embedding" inherit-label="跟随全局"
                       :width="320" :admin-tip-visible="true" />
          <div class="kb-hint">绑定后本库文档按此模型向量化与检索（改模型会自动按库重嵌入）；留空跟随系统全局。</div>
        </a-form-item>
        <a-form-item label="设为默认库">
          <a-switch v-model:checked="form.isDefault" />
          <span class="kb-hint" style="margin-left:8px">新建文档默认归属、未指定库时的兜底</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, DatabaseOutlined } from '@ant-design/icons-vue'
import { listKnowledgeBases, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase } from '../api'
import ModelSelect from '../components/ModelSelect.vue'
import { loadModelIndex, modelRefInfo } from '../utils/modelRef'

const router = useRouter()
const openDocs = kb => router.push(`/knowledge/${kb.id}/docs`)

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const showEdit = ref(false)
const editing = ref(null)
const form = ref(blank())

function blank () {
  return { name: '', description: '', queryParams: '', embeddingRef: '', isDefault: false }
}

const load = async () => {
  loading.value = true
  try {
    loadModelIndex().catch(() => {})
    const r = await listKnowledgeBases()
    list.value = (r && r.data) || []
  } catch (e) {
    message.error('知识库列表加载失败')
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  editing.value = null
  form.value = blank()
  showEdit.value = true
}

const openEdit = row => {
  editing.value = row
  form.value = {
    name: row.name || '',
    description: row.description || '',
    queryParams: row.queryParams || '',
    embeddingRef: row.embeddingRef || '',
    isDefault: row.isDefault === 1
  }
  showEdit.value = true
}

const save = async () => {
  if (!form.value.name || !form.value.name.trim()) {
    message.warning('请填写知识库名称')
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.value.name.trim(),
      description: form.value.description || null,
      queryParams: form.value.queryParams || null,
      embeddingRef: form.value.embeddingRef || '',
      isDefault: form.value.isDefault ? 1 : 0
    }
    const r = editing.value
      ? await updateKnowledgeBase(editing.value.id, body)
      : await createKnowledgeBase(body)
    if (r && r.success === false) {
      message.error(r.msg || '保存失败')
      return
    }
    message.success(editing.value ? '已保存' : '已创建')
    showEdit.value = false
    await load()
  } catch (e) {
    message.error('保存失败')
  } finally {
    saving.value = false
  }
}

const onDelete = async row => {
  try {
    const r = await deleteKnowledgeBase(row.id)
    if (r && r.success === false) {
      message.warning(r.msg || '无法删除')
      return
    }
    message.success('已删除')
    await load()
  } catch (e) {
    message.error('删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.kb-page { padding: 4px 2px; }
.kb-cards {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px; align-items: stretch;
}
.kb-card { cursor: pointer; display: flex; flex-direction: column; gap: 8px; transition: border-color .15s, box-shadow .15s; }
.kb-card:hover { border-color: var(--app-accent); box-shadow: 0 4px 16px -6px rgba(46, 107, 230, .25); }
.kb-card-head { display: flex; align-items: center; gap: 8px; min-width: 0; }
.kb-card-ic { color: var(--app-accent); font-size: 16px; flex: none; }
.kb-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kb-card-desc {
  margin: 0; font-size: 12px; color: var(--app-text3); line-height: 1.6; min-height: 38px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.kb-card-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 11px; color: var(--app-text2); }
.kb-meta-sep { color: var(--app-text3); }
.kb-card-actions { display: flex; align-items: center; gap: 10px; border-top: 1px solid var(--app-border); padding-top: 8px; margin-top: auto; }
.kb-dim { color: var(--app-text3); font-size: 11px; }
.kb-params { color: var(--app-ok); font-size: 11px; }
.kb-zero { color: var(--app-text3); }
.kb-empty { grid-column: 1 / -1; text-align: center; color: var(--app-text3); font-size: 12px; padding: 40px 0; }
.kb-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }
</style>
