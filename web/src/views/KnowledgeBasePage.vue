<template>
  <div class="kb-page">
    <div class="app-page-head">
      <h3 class="app-page-title">知识库</h3>
      <span class="head-hint-plain">文档的容器：检索按库隔离，检索参数随库（留空继承全局检索设置）</span>
      <button class="app-btn" style="margin-left:auto" @click="openCreate">
        <plus-outlined /> 新建知识库
      </button>
    </div>

    <div class="app-card">
      <a-table :columns="cols" :data-source="list" :loading="loading" row-key="id"
               :pagination="false" size="small">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <span class="kb-name">{{ record.name }}</span>
            <a-tag v-if="record.isDefault === 1" color="blue" style="margin-left:6px">默认</a-tag>
          </template>
          <template v-else-if="column.key === 'docCount'">
            <span :class="{ 'kb-zero': !record.docCount }">{{ record.docCount }} 个文档</span>
          </template>
          <template v-else-if="column.key === 'queryParams'">
            <span v-if="record.queryParams" class="kb-params" :title="record.queryParams">已自定义</span>
            <span v-else class="kb-dim">继承全局</span>
          </template>
          <template v-else-if="column.key === 'action'">
            <button class="app-link-btn" @click="openEdit(record)">编辑</button>
            <button class="app-link-btn danger" :disabled="record.isDefault === 1" @click="onDelete(record)">删除</button>
          </template>
        </template>
      </a-table>
    </div>

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
import { PlusOutlined } from '@ant-design/icons-vue'
import { listKnowledgeBases, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase } from '../api'

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const showEdit = ref(false)
const editing = ref(null)
const form = ref(blank())

const cols = [
  { title: '名称', key: 'name', dataIndex: 'name' },
  { title: '描述', key: 'description', dataIndex: 'description', ellipsis: true },
  { title: '文档', key: 'docCount', dataIndex: 'docCount', width: 110 },
  { title: '检索参数', key: 'queryParams', width: 110 },
  { title: '操作', key: 'action', width: 120 }
]

function blank () {
  return { name: '', description: '', queryParams: '', isDefault: false }
}

const load = async () => {
  loading.value = true
  try {
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
.kb-name { font-weight: 500; }
.kb-dim { color: var(--app-text3); font-size: 12px; }
.kb-params { color: var(--app-ok); font-size: 12px; }
.kb-zero { color: var(--app-text3); }
.kb-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 4px; }
</style>
