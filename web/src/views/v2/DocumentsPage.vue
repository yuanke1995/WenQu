<template>
  <div class="v2-page" @dragover.prevent @dragenter.prevent="dragDepth++" @dragleave.prevent="dragDepth = Math.max(0, dragDepth - 1)" @drop.prevent="onDrop">
    <div class="v2-page-head">
      <h3 class="v2-page-title">文档管理</h3>
      <span class="head-stat">{{ summaryText }}</span>
      <div style="margin-left:auto;display:flex;gap:8px;align-items:center">
        <a-input v-model:value="desc" placeholder="文档描述（可选）" style="width:160px" size="small" allow-clear />
        <button class="v2-btn ghost" @click="openGlobalSearch"><search-outlined /> 全局搜索</button>
        <a-upload :before-upload="beforeUpload" :show-upload-list="false" :accept="'.' + uploadCfg.allowedExts.join(',.')" multiple :disabled="uploading">
          <button class="v2-btn" :disabled="uploading"><upload-outlined /> {{ uploading ? '上传中…' : '上传文档' }}</button>
        </a-upload>
      </div>
    </div>
    <a-progress v-if="uploading" :percent="uploadPercent" size="small" style="max-width:420px;margin:10px 20px 0" />

    <div class="v2-page-body">
      <!-- 拖拽遮罩 -->
      <div v-if="dragDepth > 0" class="drag-mask">
        <div class="drag-mask-tip"><upload-outlined style="font-size:36px" /><div>松开鼠标上传到知识库</div></div>
      </div>

      <!-- 批量操作栏 -->
      <div v-if="selectedKeys.length" class="batch-bar">
        <span>已选 {{ selectedKeys.length }} 项：</span>
        <button class="v2-btn ghost" @click="batchStatus(0)">批量启用</button>
        <button class="v2-btn ghost" @click="batchStatus(1)">批量弃用</button>
        <a-popconfirm title="确定删除选中的文档？知识库将同步移除" @confirm="batchDelete">
          <button class="v2-btn danger">批量删除</button>
        </a-popconfirm>
        <a-popconfirm title="对选中文档重新解析+向量化？" @confirm="batchReparse">
          <button class="v2-btn ghost">批量重解析</button>
        </a-popconfirm>
        <button class="v2-link-btn" @click="selectedKeys = []">取消选择</button>
      </div>

      <!-- 文件列表（行式，语析风格） -->
      <div class="v2-card" style="padding:0;overflow:hidden">
        <div class="doc-row head-row">
          <span class="col-check"></span>
          <span class="col-name">文件名</span>
          <span class="col-num">片段</span>
          <span class="col-num">命中</span>
          <span class="col-size">大小</span>
          <span class="col-status">状态</span>
          <span class="col-time">上传时间</span>
          <span class="col-act">操作</span>
        </div>
        <a-spin :spinning="loading">
          <div v-for="d in list" :key="d.id" class="doc-row">
            <span class="col-check"><a-checkbox :checked="selectedKeys.includes(d.id)" @change="e => toggleSelect(d.id, e)" /></span>
            <span class="col-name">
              <span class="file-ic" :style="{ background: typeColor(d.fileType).bg, color: typeColor(d.fileType).fg }">{{ (d.fileType || '?').toUpperCase().slice(0, 4) }}</span>
              <span class="file-name" :title="d.fileName + (d.description ? ' · ' + d.description : '')">{{ d.fileName }}<i v-if="d.description" class="file-desc">{{ d.description }}</i></span>
            </span>
            <span class="col-num">{{ d.chunkCount || 0 }}</span>
            <span class="col-num">{{ d.hitCount || 0 }}</span>
            <span class="col-size">{{ fmtSize(d.fileSize) }}</span>
            <span class="col-status">
              <a-tooltip v-if="d.status === 0 || d.status === 1 || d.status === 3" :title="d.parseDesc || ''">
                <span v-if="d.status === 0" class="v2-pill ok">已入库</span>
                <span v-else-if="d.status === 1" class="v2-pill warn">已弃用</span>
                <span v-else class="v2-pill err" style="cursor:pointer" @click="showFailReason(d)">解析失败</span>
              </a-tooltip>
              <div v-else-if="d.status === 2" style="min-width:120px">
                <a-progress :percent="d.parseProgress || 0" size="small" style="margin:0" />
                <span class="parse-desc" :title="d.parseDesc || '解析中'">{{ d.parseDesc || '解析中' }}</span>
              </div>
            </span>
            <span class="col-time">{{ fmtTime(d.createTime) }}</span>
            <span class="col-act">
              <template v-if="d.status === 0">
                <button class="v2-link-btn" @click="openKb(d)">知识块</button>
                <button class="v2-link-btn" @click="openVersions(d)">版本</button>
                <button class="v2-link-btn" @click="toggleStatus(d, 1)">弃用</button>
              </template>
              <button v-else-if="d.status === 1" class="v2-link-btn" @click="toggleStatus(d, 0)">启用</button>
              <button v-if="d.status === 0 || d.status === 3" class="v2-link-btn" :disabled="reparsingId === d.id" @click="reparse(d.id)">重解析</button>
              <a-popconfirm title="确定删除该文档？知识库将同步移除" @confirm="del(d.id)">
                <button class="v2-link-btn danger" :disabled="deletingId === d.id">删除</button>
              </a-popconfirm>
            </span>
          </div>
          <a-empty v-if="!loading && !list.length" description="暂无文档，点击右上角上传 .docx / .pdf / .xlsx" style="padding:40px 0" />
        </a-spin>
      </div>
    </div>

    <!-- 知识块预览 -->
    <a-modal v-model:open="kbVisible" :title="'知识块预览 · ' + kbDocName" :footer="null" width="820">
      <div style="margin-bottom:10px">
        <a-input-search v-model:value="kbSearch" placeholder="按标题/内容过滤知识块" allow-clear />
      </div>
      <a-spin :spinning="kbLoading">
        <a-table :data-source="kbFilteredList" size="small" row-key="id" :pagination="{ pageSize: 20 }"
                 :locale="{ emptyText: '暂无知识块' }"
                 :custom-row="r => ({ onClick: () => openKbDetail(r) })" style="cursor:pointer">
          <a-table-column title="#" dataIndex="chunkIndex" key="chunkIndex" width="50" />
          <a-table-column title="状态" key="status" width="80">
            <template #default="{ record }">
              <span v-if="(record.status ?? 0) === 0" class="v2-pill ok">生效</span>
              <span v-else class="v2-pill warn">已停用</span>
            </template>
          </a-table-column>
          <a-table-column title="标题" key="title" ellipsis>
            <template #default="{ record }">
              <span>{{ record.title }}</span>
              <a-tag v-if="!record.vectorId" color="orange" style="margin-left:6px;font-size:11px">未向量化</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="内容摘要" key="snippet">
            <template #default="{ record }">{{ (record.content || '').replace(/\s+/g, ' ').slice(0, 80) }}</template>
          </a-table-column>
          <a-table-column title="操作" key="action" width="130">
            <template #default="{ record }">
              <button class="v2-link-btn" @click.stop="openKbEdit(record)">编辑</button>
              <button v-if="(record.status ?? 0) === 0" class="v2-link-btn" @click.stop="toggleKbStatus(record, 1)">停用</button>
              <button v-else class="v2-link-btn" @click.stop="toggleKbStatus(record, 0)">启用</button>
              <a-popconfirm title="确定删除该知识块？向量将同步移除" ok-text="删除" cancel-text="取消" @confirm.stop="delKnowledge(record.id)">
                <button class="v2-link-btn danger" @click.stop>删除</button>
              </a-popconfirm>
            </template>
          </a-table-column>
        </a-table>
      </a-spin>
    </a-modal>

    <!-- 知识块编辑弹窗：Markdown 工具栏快捷插入 + 左写右看实时预览 + [图片N] 点选插入 + 未保存关闭提醒（与旧版同功能） -->
    <a-modal :open="kbEditVisible" title="编辑知识块" :footer="null" width="900" @cancel="closeKbEdit">
      <a-form layout="vertical">
        <a-form-item label="标题"><a-input v-model:value="kbEditForm.title" maxlength="200" placeholder="知识块标题" /></a-form-item>
        <a-form-item label="内容">
          <div class="kb-md-bar">
            <a-space :size="2" wrap>
              <a-button size="small" type="text" title="二级标题" @click="kbLinePrefix('## ')">H2</a-button>
              <a-button size="small" type="text" title="三级标题" @click="kbLinePrefix('### ')">H3</a-button>
              <a-button size="small" type="text" title="加粗（Ctrl/⌘+B）" style="font-weight:700" @click="kbWrap('**', '**', '加粗文字')">B</a-button>
              <a-button size="small" type="text" title="无序列表" @click="kbLinePrefix('- ')">列表</a-button>
              <a-button size="small" type="text" title="有序列表" @click="kbLinePrefix('', true)">1. 列表</a-button>
              <a-button size="small" type="text" title="引用" @click="kbLinePrefix('> ')">引用</a-button>
              <a-button size="small" type="text" title="插入表格模板" @click="kbInsertTable">表格</a-button>
              <a-button size="small" type="text" title="代码块" @click="kbWrap('\n```\n', '\n```\n', '代码')">代码</a-button>
              <a-button size="small" type="text" title="链接" @click="kbWrap('[', '](https://)', '链接文字')">链接</a-button>
            </a-space>
            <a-dropdown :trigger="['click']">
              <a-button size="small" type="text" :disabled="!kbEditImages.length"
                        :title="kbEditImages.length ? '点击插入图片占位符' : '该知识块无关联图片'">
                图片<down-outlined style="font-size:10px;margin-left:3px" />
              </a-button>
              <template #overlay>
                <a-menu @click="e => kbWrap(`[图片${e.key}]`)">
                  <a-menu-item v-for="(u, i) in kbEditImages" :key="i + 1" class="kb-img-item">
                    <img class="kb-img-thumb" :src="resolveImg(u)" @error="onImgError" />图片{{ i + 1 }}
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
          <div class="kb-edit-split">
            <a-textarea ref="kbTaRef" v-model:value="kbEditForm.content" class="kb-edit-ta"
                        placeholder="支持 Markdown 语法，可用上方工具栏快捷插入；右侧为实时预览"
                        @keydown="onTaKeydown" />
            <div class="kb-edit-preview md" v-html="kbEditPreviewHtml"></div>
          </div>
        </a-form-item>
      </a-form>
      <div style="text-align:right">
        <button class="v2-btn ghost" style="margin-right:8px" @click="closeKbEdit">取消</button>
        <button class="v2-btn" :disabled="kbEditSaving" @click="saveKnowledgeEdit">{{ kbEditSaving ? '保存中...' : '保存' }}</button>
      </div>
    </a-modal>

    <!-- 全局搜索 -->
    <a-modal v-model:open="gSearchVisible" title="知识块全局搜索" :footer="null" width="820">
      <div style="display:flex;gap:8px;margin-bottom:12px">
        <a-input-search v-model:value="gSearchKw" placeholder="输入关键词，跨全部文档搜索知识块（含已停用）" enter-button="搜索" :loading="gSearchLoading" @search="doGlobalSearch" />
      </div>
      <a-table :data-source="gResults" size="small" row-key="id"
               :pagination="gResults.length > 20 ? { pageSize: 20 } : false"
               :locale="{ emptyText: '输入关键词后搜索' }"
               :custom-row="r => ({ onClick: () => openGlobalDetail(r) })" style="cursor:pointer">
        <a-table-column title="文档" dataIndex="docName" key="docName" width="160" ellipsis />
        <a-table-column title="标题" key="title" width="150" ellipsis>
          <template #default="{ record }">{{ record.title || '（无标题）' }}</template>
        </a-table-column>
        <a-table-column title="内容摘要" key="snippet" ellipsis>
          <template #default="{ record }">{{ record.snippet }}</template>
        </a-table-column>
      </a-table>
    </a-modal>

    <!-- 知识块详情 -->
    <a-modal v-model:open="kbDetailVisible" :title="kbDetail?.title || '知识块详情'" :footer="null" width="720">
      <div class="md" style="max-height:60vh;overflow-y:auto;font-size:14px;line-height:1.7" v-html="kbDetailHtml"></div>
    </a-modal>

    <!-- 版本管理 -->
    <a-modal v-model:open="verVisible" :title="'版本历史 · ' + verDocName" :footer="null" width="560">
      <a-spin :spinning="verLoading">
        <a-table :data-source="verList" size="small" row-key="version" :pagination="false" :locale="{ emptyText: '暂无版本记录' }">
          <a-table-column title="版本" dataIndex="version" key="version" width="80" />
          <a-table-column title="知识块数" dataIndex="chunkCount" key="chunkCount" width="100" />
          <a-table-column title="创建时间" dataIndex="createTime" key="createTime" />
          <a-table-column title="操作" key="action" width="100">
            <template #default="{ record }">
              <a-popconfirm :title="`确定回滚到 v${record.version}？当前版本将被覆盖`" ok-text="回滚" cancel-text="取消" @confirm="doRollback(record.version)">
                <button class="v2-link-btn">回滚</button>
              </a-popconfirm>
            </template>
          </a-table-column>
        </a-table>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { UploadOutlined, SearchOutlined, DownOutlined } from '@ant-design/icons-vue'
import { listDocuments, uploadDocumentsBatch, updateDocumentStatus, reparseDocument, deleteDocument,
         batchDeleteDocuments, batchUpdateDocumentStatus, getDocumentStats, listKnowledgeByDoc, getKnowledgeDetail,
         updateKnowledge, deleteKnowledge, listDocumentVersions, rollbackDocument,
         getRuntimeConfig, batchReparseDocuments, updateKnowledgeStatus, searchKnowledge } from '../../api'
import { renderMd, prepKnowledgeContent, resolveImg, onImgError } from '../../utils/markdown'

// 上传限制（启动时从 /config/public 动态获取）
const MAX_SIZE = 200 * 1024 * 1024
const uploadCfg = ref({ maxFileSize: MAX_SIZE, maxFileSizeLabel: '200MB', allowedExts: ['docx', 'pdf', 'xlsx'] })
const loadUploadCfg = async () => {
  try {
    const r = await getRuntimeConfig()
    if (r.success && r.data?.upload) {
      const u = r.data.upload
      if (Number(u.maxFileSize) > 0) uploadCfg.value.maxFileSize = Number(u.maxFileSize)
      if (u.maxFileSizeLabel) uploadCfg.value.maxFileSizeLabel = u.maxFileSizeLabel
      if (Array.isArray(u.allowedExts) && u.allowedExts.length) uploadCfg.value.allowedExts = u.allowedExts
    }
  } catch (e) { /* 保持默认 */ }
}

// 文件类型图标配色
const typeColor = t => {
  const map = {
    docx: { bg: '#e6f1fb', fg: '#185fa5' }, doc: { bg: '#e6f1fb', fg: '#185fa5' },
    pdf: { bg: '#fcebeb', fg: '#a32d2d' }, xlsx: { bg: '#eaf3de', fg: '#3b6d11' }, xls: { bg: '#eaf3de', fg: '#3b6d11' }
  }
  return map[(t || '').toLowerCase()] || { bg: '#f1f3f5', fg: '#5f6570' }
}

const list = ref([])
const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const deletingId = ref('')
const reparsingId = ref('')
const desc = ref('')
const dragDepth = ref(0)
const selectedKeys = ref([])
let pollTimer = null

const summaryText = computed(() => {
  const total = list.value.length
  const chunks = list.value.reduce((n, d) => n + (d.chunkCount || 0), 0)
  const active = list.value.filter(d => d.status === 0).length
  return `${total} 个文档 · ${active} 个生效 · ${chunks} 片段`
})

const toggleSelect = (id, e) => {
  const at = selectedKeys.value.indexOf(id)
  if (e.target.checked) { if (at < 0) selectedKeys.value.push(id) }
  else if (at >= 0) selectedKeys.value.splice(at, 1)
}

onMounted(() => { fetchList(); loadUploadCfg(); window.addEventListener('paste', onPaste) })
onUnmounted(() => { if (pollTimer) clearInterval(pollTimer); window.removeEventListener('paste', onPaste) })

async function fetchList () {
  loading.value = true
  try {
    const [r, stats] = await Promise.all([listDocuments(), getDocumentStats()])
    if (r.success) {
      const hitMap = (stats && stats.success && stats.data) ? stats.data : {}
      list.value = (r.data || []).map(d => ({ ...d, hitCount: hitMap[d.id] || 0 }))
      if (list.value.some(d => d.status === 2)) startPolling()
      else stopPolling()
    }
  } catch (e) { message.error(e.message || '获取列表失败') }
  finally { loading.value = false }
}
function startPolling () {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      const r = await listDocuments()
      if (r.success) {
        list.value = r.data || []
        if (!list.value.some(d => d.status === 2)) stopPolling()
      }
    } catch (e) { /* 轮询失败忽略 */ }
  }, 3000)
}
function stopPolling () {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function beforeUpload (fileList) {
  const files = Array.isArray(fileList) ? fileList : [fileList]
  const bad = files.find(f => {
    const ext = (f.name.split('.').pop() || '').toLowerCase()
    return !uploadCfg.value.allowedExts.includes(ext) || f.size > uploadCfg.value.maxFileSize
  })
  if (bad) {
    message.error(`${bad.name} 不支持或超过 ${uploadCfg.value.maxFileSizeLabel}（支持 ${uploadCfg.value.allowedExts.join('/')}）`)
    return false
  }
  uploading.value = true
  uploadPercent.value = 0
  try {
    const r = await uploadDocumentsBatch(files, pct => { uploadPercent.value = pct }, desc.value?.trim() || undefined)
    if (r.success) {
      const failed = (r.data || []).filter(x => !x.success)
      if (failed.length) message.warning(`${files.length - failed.length} 个提交成功，${failed.length} 个失败: ${failed[0].msg || ''}`)
      else message.success(`已提交 ${files.length} 个文档解析`)
      desc.value = ''
      fetchList()
    } else message.error(r.msg || '上传失败')
  } catch (e) { message.error(e.message || '上传失败') }
  finally { uploading.value = false }
  return false
}
const onDrop = e => {
  dragDepth.value = 0
  const files = Array.from(e.dataTransfer?.files || [])
  if (!files.length) return
  const ok = files.filter(f => uploadCfg.value.allowedExts.includes((f.name.split('.').pop() || '').toLowerCase()))
  const skipped = files.length - ok.length
  if (skipped > 0) message.warning(`跳过 ${skipped} 个不支持的文件`)
  if (ok.length) beforeUpload(ok)
}
const onPaste = e => {
  const files = Array.from(e.clipboardData?.files || [])
    .filter(f => uploadCfg.value.allowedExts.includes((f.name.split('.').pop() || '').toLowerCase()))
  if (files.length) beforeUpload(files)
}

async function toggleStatus (record, status) {
  try {
    const r = await updateDocumentStatus(record.id, status)
    if (r.success) { message.success(status === 0 ? '已启用' : '已弃用'); fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
async function batchStatus (status) {
  if (!selectedKeys.value.length) return
  try {
    const r = await batchUpdateDocumentStatus(selectedKeys.value, status)
    if (r.success) { message.success(`已${status === 0 ? '启用' : '弃用'} ${selectedKeys.value.length} 个文档`); selectedKeys.value = []; fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
async function batchDelete () {
  if (!selectedKeys.value.length) return
  try {
    const r = await batchDeleteDocuments(selectedKeys.value)
    if (r.success) { message.success(`已删除 ${selectedKeys.value.length} 个文档`); selectedKeys.value = []; fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
const batchReparse = async () => {
  try {
    const r = await batchReparseDocuments(selectedKeys.value)
    if (r.success) {
      const failed = (r.data || []).filter(x => !x.success)
      if (failed.length) message.warning(`已提交 ${r.data.length - failed.length} 个，${failed.length} 个失败: ${failed[0].msg || ''}`)
      else message.success(`已提交 ${r.data.length} 个重解析`)
      selectedKeys.value = []
      fetchList()
      startPolling()
    } else message.error(r.msg || '批量重解析失败')
  } catch (e) { message.error(e.message || '批量重解析失败') }
}
async function reparse (id) {
  reparsingId.value = id
  try {
    const r = await reparseDocument(id)
    if (r.success) { message.success('已重新提交解析'); fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { reparsingId.value = '' }
}
async function del (id) {
  deletingId.value = id
  try {
    const r = await deleteDocument(id)
    if (r.success) { message.success('删除成功'); fetchList() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
  finally { deletingId.value = '' }
}
function showFailReason (record) {
  Modal.info({ title: `解析失败 - ${record.fileName}`, content: record.failReason || '未知原因，可点击"重解析"重试' })
}

// ==================== 知识块 ====================
const kbVisible = ref(false)
const kbLoading = ref(false)
const kbList = ref([])
const kbDocName = ref('')
const kbDocId = ref('')
const kbSearch = ref('')
const kbFilteredList = computed(() => {
  const kw = kbSearch.value.trim().toLowerCase()
  if (!kw) return kbList.value
  return kbList.value.filter(k =>
    (k.title || '').toLowerCase().includes(kw) || (k.content || '').toLowerCase().includes(kw))
})
const kbDetailVisible = ref(false)
const kbDetail = ref(null)
const kbDetailHtml = computed(() =>
  renderMd(prepKnowledgeContent(kbDetail.value?.content, kbDetail.value?.images), kbDetail.value?.images))
const openKb = async record => {
  kbDocName.value = record.fileName
  kbDocId.value = record.id
  kbVisible.value = true
  kbLoading.value = true
  kbList.value = []
  try {
    const r = await listKnowledgeByDoc(record.id)
    kbList.value = r.success && Array.isArray(r.data) ? r.data : []
  } catch (e) { message.error(e.message || '加载知识块失败') }
  finally { kbLoading.value = false }
}
const openKbDetail = async row => {
  kbDetail.value = null
  kbDetailVisible.value = true
  try {
    const r = await getKnowledgeDetail(row.id)
    if (r.success) kbDetail.value = r.data
    else message.error(r.msg || '加载详情失败')
  } catch (e) { message.error(e.message || '加载详情失败') }
}
const kbEditVisible = ref(false)
const kbEditSaving = ref(false)
const kbEditForm = ref({ id: '', title: '', content: '' })
const kbEditImages = ref([])
const kbEditSnapshot = ref('')      // 打开时的标题+内容快照，用于未保存判断
const kbTaRef = ref(null)
const kbEditDirty = computed(() =>
  kbEditForm.value.title + '\u0000' + kbEditForm.value.content !== kbEditSnapshot.value)
const kbEditPreviewHtml = computed(() =>
  renderMd(prepKnowledgeContent(kbEditForm.value.content, kbEditImages.value), kbEditImages.value))
const openKbEdit = async row => {
  kbEditForm.value = { id: row.id, title: row.title || '', content: row.content || '' }
  kbEditImages.value = []
  kbEditSnapshot.value = kbEditForm.value.title + '\u0000' + kbEditForm.value.content
  kbEditVisible.value = true
  // 图片列表：预览渲染 + 「图片」插入菜单都要用（失败降级为空数组，预览显示 [图片] 原文）
  try {
    const r = await getKnowledgeDetail(row.id)
    if (r.success && Array.isArray(r.data?.images)) kbEditImages.value = r.data.images
  } catch (e) { /* 预览降级为无图，不阻塞编辑 */ }
}
// 关闭（X/遮罩/ESC/取消按钮共用）：有未保存修改先确认，防误关丢稿
const closeKbEdit = () => {
  if (!kbEditDirty.value) { kbEditVisible.value = false; return }
  Modal.confirm({
    title: '放弃未保存的修改？',
    content: '标题或内容已修改但尚未保存',
    okText: '放弃修改', okType: 'danger', cancelText: '继续编辑',
    onOk: () => { kbEditVisible.value = false }
  })
}
// 取 a-textarea 内部原生 textarea（antd 封装层级兜底，保证拿得到 selectionStart）
const kbGetTa = () => {
  const r = kbTaRef.value
  const ta = r?.resizableTextArea?.textArea || r?.textArea
  if (ta) return ta
  const el = r?.$el
  return el ? (el.tagName === 'TEXTAREA' ? el : el.querySelector?.('textarea')) : null
}
// 在光标处插入包裹语法（**xx** / [图片N] / 代码围栏 / 链接）：选中文字进包裹内，无选中插占位文字并选中
const kbWrap = (before, after = '', placeholder = '') => {
  const ta = kbGetTa()
  const v = kbEditForm.value.content
  const s = ta ? ta.selectionStart : v.length
  const e = ta ? ta.selectionEnd : s
  const sel = v.slice(s, e) || placeholder
  kbEditForm.value.content = v.slice(0, s) + before + sel + after + v.slice(e)
  nextTick(() => {
    if (!ta) return
    ta.focus()
    ta.setSelectionRange(s + before.length, s + before.length + sel.length)
  })
}
// 行前缀语法（## / - / > / 1.）：作用于光标所在行（多行选区逐行生效），已有前缀再点一次取消
const kbLinePrefix = (prefix, ordered = false) => {
  const ta = kbGetTa()
  const v = kbEditForm.value.content
  const s = ta ? ta.selectionStart : v.length
  const e = ta ? ta.selectionEnd : s
  const ls = v.lastIndexOf('\n', Math.max(s - 1, 0)) + 1
  let le = v.indexOf('\n', e)
  if (le === -1) le = v.length
  const lines = v.slice(ls, le).split('\n')
  const has = l => ordered ? /^\s*\d+\.\s/.test(l) : l.trimStart().startsWith(prefix)
  const strip = l => ordered
    ? l.replace(/^(\s*)\d+\.\s*/, '$1')
    : l.replace(new RegExp('^(\\s*)' + prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(\\s*)'), '$1')
  const all = lines.every(has)
  const out = lines.map((l, i) => {
    if (all) return strip(l)
    if (ordered) {
      const indent = l.match(/^\s*/)[0]
      const body = l.trimStart().replace(/^\d+\.\s+|^[-*+]\s+|^>\s?/, '')
      return `${indent}${i + 1}. ${body}`
    }
    return has(l) ? l : prefix + l.trimStart().replace(/^\d+\.\s+/, '')
  })
  kbEditForm.value.content = v.slice(0, ls) + out.join('\n') + v.slice(le)
  nextTick(() => {
    if (!ta) return
    ta.focus()
    ta.setSelectionRange(ls, ls + out.join('\n').length)
  })
}
// 插入三列表格模板（markdown 表格需独立成块：前后补空行；插入后选中"列1"便于直接改表头）
const kbInsertTable = () => {
  const ta = kbGetTa()
  const v = kbEditForm.value.content
  const s = ta ? ta.selectionStart : v.length
  const e = ta ? ta.selectionEnd : s
  const head = v.slice(0, s)
  const lead = head === '' || /\n\s*\n$/.test(head) ? '' : /\n$/.test(head) ? '\n' : '\n\n'
  kbEditForm.value.content = head + lead + '| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n|  |  |  |\n\n' + v.slice(e)
  nextTick(() => {
    if (!ta) return
    ta.focus()
    const p = head.length + lead.length + 2
    ta.setSelectionRange(p, p + 2)
  })
}
// 编辑区快捷键：Ctrl/⌘+B 加粗
const onTaKeydown = e => {
  if (!(e.ctrlKey || e.metaKey)) return
  if ((e.key || '').toLowerCase() === 'b') { e.preventDefault(); kbWrap('**', '**', '加粗文字') }
}
const saveKnowledgeEdit = async () => {
  if (!kbEditForm.value.title.trim()) { message.warning('标题不能为空'); return }
  if (!kbEditForm.value.content.trim()) { message.warning('内容不能为空'); return }
  kbEditSaving.value = true
  try {
    const r = await updateKnowledge(kbEditForm.value.id, kbEditForm.value.title.trim(), kbEditForm.value.content)
    if (r.success) {
      message.success('知识块已更新')
      kbEditVisible.value = false
      if (kbVisible.value) {
        const rr = await listKnowledgeByDoc(kbDocId.value)
        kbList.value = rr.success && Array.isArray(rr.data) ? rr.data : []
      }
    } else message.error(r.msg || '更新失败')
  } catch (e) { message.error(e.message || '更新失败') }
  finally { kbEditSaving.value = false }
}
const toggleKbStatus = async (record, status) => {
  try {
    const r = await updateKnowledgeStatus(record.id, status)
    if (r.success) {
      message.success(status === 1 ? '已停用，该知识块不再参与召回' : '已启用，恢复召回')
      kbSearch.value = ''
      const res = await listKnowledgeByDoc(record.docId)
      if (res.success) kbList.value = res.data || []
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
const delKnowledge = async id => {
  try {
    const r = await deleteKnowledge(id)
    if (r.success) {
      message.success('知识块已删除')
      if (kbVisible.value) {
        const rr = await listKnowledgeByDoc(kbDocId.value)
        kbList.value = rr.success && Array.isArray(rr.data) ? rr.data : []
      }
      fetchList()
    } else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// 全局搜索
const gSearchVisible = ref(false)
const gSearchKw = ref('')
const gSearchLoading = ref(false)
const gResults = ref([])
const openGlobalSearch = () => { gSearchVisible.value = true; gSearchKw.value = ''; gResults.value = [] }
const doGlobalSearch = async () => {
  const kw = gSearchKw.value.trim()
  if (!kw) { message.warning('请输入关键词'); return }
  gSearchLoading.value = true
  try {
    const r = await searchKnowledge(kw)
    if (r.success) gResults.value = r.data || []
    else message.error(r.msg || '搜索失败')
  } catch (e) { message.error(e.message || '搜索失败') }
  finally { gSearchLoading.value = false }
}
const openGlobalDetail = async row => {
  kbDetail.value = null
  kbDetailVisible.value = true
  try {
    const r = await getKnowledgeDetail(row.id)
    if (r.success) kbDetail.value = r.data
    else message.error(r.msg || '加载详情失败')
  } catch (e) { message.error(e.message || '加载详情失败') }
}

// 版本管理
const verVisible = ref(false)
const verLoading = ref(false)
const verList = ref([])
const verDocName = ref('')
const verDocId = ref('')
const openVersions = async record => {
  verDocName.value = record.fileName
  verDocId.value = record.id
  verVisible.value = true
  verLoading.value = true
  verList.value = []
  try {
    const r = await listDocumentVersions(record.id)
    verList.value = r.success && Array.isArray(r.data) ? r.data : []
  } catch (e) { message.error(e.message || '加载版本失败') }
  finally { verLoading.value = false }
}
const doRollback = async version => {
  try {
    const r = await rollbackDocument(verDocId.value, version)
    if (r.success) { message.success(`已回滚到 v${version}`); verVisible.value = false; fetchList() }
    else message.error(r.msg || '回滚失败')
  } catch (e) { message.error(e.message || '回滚失败') }
}

const fmtSize = s => !s ? '-' : s < 1024 ? s + ' B' : s < 1048576 ? (s / 1024).toFixed(1) + ' KB' : (s / 1048576).toFixed(1) + ' MB'
const fmtTime = t => {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<style scoped>
.head-stat { font-size: 12px; color: var(--v2-text3); }
.batch-bar {
  display: flex; align-items: center; gap: 10px; padding: 8px 12px; margin-bottom: 10px;
  background: var(--v2-accent-weak); border: 1px solid #c9d9f5; border-radius: 8px;
  font-size: 12px; color: var(--v2-accent);
}
.doc-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 14px;
  border-bottom: 1px solid #f1f3f5; font-size: 12px; color: var(--v2-text2); min-width: 0;
}
.doc-row:last-child { border-bottom: none; }
.doc-row:not(.head-row):hover { background: #fafbfc; }
.head-row { font-size: 11px; color: var(--v2-text3); background: #fafbfc; border-bottom: 1px solid var(--v2-border); user-select: none; }
.col-check { width: 26px; flex: none; }
.col-name { flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; }
.file-ic {
  width: 34px; height: 24px; border-radius: 5px; font-size: 9px; font-weight: 500; flex: none;
  display: inline-flex; align-items: center; justify-content: center; letter-spacing: .5px;
}
.file-name { color: var(--v2-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.file-desc { font-style: normal; color: var(--v2-text3); margin-left: 8px; font-size: 11px; }
.col-num { width: 48px; flex: none; text-align: right; }
.col-size { width: 72px; flex: none; text-align: right; }
.col-status { width: 130px; flex: none; }
.col-time { width: 130px; flex: none; }
.col-act { width: 250px; flex: none; text-align: right; white-space: nowrap; }
.parse-desc { font-size: 11px; color: var(--v2-text3); display: inline-block; max-width: 150px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.drag-mask {
  position: fixed; inset: 0; z-index: 1000;
  background: rgba(46,107,230,.08);
  display: flex; align-items: center; justify-content: center; pointer-events: none;
}
.drag-mask-tip {
  text-align: center; color: var(--v2-accent); font-size: 15px;
  background: #fff; border: 2px dashed var(--v2-accent); border-radius: 12px;
  padding: 24px 40px; display: flex; flex-direction: column; gap: 8px; align-items: center;
}
/* 知识块编辑：Markdown 工具栏 + 左写右看分栏实时预览 */
.kb-md-bar {
  display: flex; justify-content: space-between; align-items: center; gap: 8px;
  margin-bottom: 6px;
}
.kb-edit-split { display: flex; gap: 10px; }
.kb-edit-ta { flex: 1 1 50%; min-width: 0; height: 380px; resize: none; font-size: 13px; line-height: 1.7; }
.kb-edit-preview {
  flex: 1 1 50%; min-width: 0; height: 380px; overflow-y: auto;
  border: 1px solid var(--v2-border); border-radius: 6px; background: #fafbfc;
  padding: 10px 14px; font-size: 14px; line-height: 1.7;
}
/* 图片插入菜单项：缩略图 + 编号 */
.kb-img-item { display: flex; align-items: center; }
.kb-img-thumb {
  width: 42px; height: 26px; object-fit: cover; border-radius: 3px;
  margin-right: 8px; border: 1px solid #eee; background: #fafafa;
}
</style>
