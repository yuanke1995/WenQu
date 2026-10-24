<template>
  <!-- 技能（Skills）：把「这类问题该怎么做」固化成能力包，模型按需读取后照做。
       本页是「用户自助安装 + 管理」的入口：内置只能停用，自己装的可以编辑/删除/下载。
       页面骨架对齐「模型供应商」Tab：页头（计数 + 说明 + 工具组）+ 内容区。 -->
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title sr-only">技能</h1>
      <span class="head-count">共 <b>{{ skills.length }}</b> 个 · 生效中 <b>{{ activeCount }}</b></span>
      <span class="head-hint-plain" title="技能用来固化「这类问题该怎么做」：步骤、输出格式、禁忌。模型先看清单，需要时再读正文。自己装的技能只有自己能用">
        固化「这类问题该怎么做」的做法，模型按需读取后照做
      </span>
      <div class="head-r">
        <a-input v-model:value="keyword" class="head-search" size="small" allow-clear placeholder="搜索名称或描述">
          <template #prefix><search-outlined class="head-search-ic" /></template>
        </a-input>
        <a-tooltip title="刷新列表">
          <button class="app-icon-btn" aria-label="刷新技能列表" :disabled="loading" @click="load"><reload-outlined /></button>
        </a-tooltip>
        <button class="app-btn ghost small" @click="openInstall('zip')"><upload-outlined /> 上传 zip</button>
        <button class="app-btn ghost small" @click="openInstall('url')"><link-outlined /> 从 URL 安装</button>
        <button class="app-btn" @click="openCreate"><plus-outlined /> 新建技能</button>
      </div>
    </div>

    <div class="app-page-body">
      <a-spin v-if="loading && !skills.length" style="display:block;margin:48px auto" />

      <div v-else-if="!skills.length" class="app-card key-empty">
        <div class="key-empty-title">还没有技能</div>
        <div class="key-empty-desc">
          技能用来固化「这类问题该怎么做」——步骤、输出格式、禁忌。模型按需读取后照做，不必每次在提问里重复交代。
          新建或安装的技能只有自己能用，别人看不见。
        </div>
        <div class="key-empty-ops">
          <button class="app-btn" @click="openCreate">新建第一个技能</button>
          <button class="app-btn ghost" @click="openInstall('zip')">上传 zip 安装</button>
        </div>
      </div>

      <div v-else-if="!filtered.length" class="app-card key-empty">
        <div class="key-empty-title">没有匹配的技能</div>
        <div class="key-empty-desc">没有名称或描述包含「{{ keyword }}」的技能。</div>
        <button class="app-btn ghost small" @click="keyword = ''">清除搜索</button>
      </div>

      <template v-else>
        <template v-for="g in groups" :key="g.key">
          <div class="res-group-title">{{ g.label }} <span class="res-group-n">{{ g.list.length }}</span></div>
          <div class="skill-tip" v-if="g.key === 'builtin'">
            随版本分发的公共技能，所有人可见。你只能停用（只对自己生效），不能修改或删除。
          </div>
          <div class="skill-tip" v-else-if="g.key === 'workspace'">
            助手在对话里沉淀到当前会话的技能，随会话走；换个会话就看不到了。
          </div>

          <div class="skill-grid">
            <div v-for="s in g.list" :key="s.source + ':' + s.name" class="app-card skill-card">
              <div class="skill-card-head">
                <span class="skill-card-name" :title="s.name">{{ s.name }}</span>
                <span v-if="s.disabled" class="app-pill warn key-tag">{{ s.platformDisabled ? '平台停用' : '我停用了' }}</span>
                <span v-else class="app-pill ok key-tag">生效中</span>
              </div>
              <div class="skill-card-desc" :class="{ 'skill-desc-warn': !s.description }" :title="s.description || ''">
                {{ s.description || '（未填描述：模型不会主动读取它）' }}
              </div>
              <div class="skill-card-meta">
                <span>{{ sourceLabel(s.source) }}</span>
                <span v-if="s.size">{{ fmtSize(s.size) }}</span>
                <span v-if="s.fileCount > 1">{{ s.fileCount }} 个文件</span>
                <span v-if="s.updatedAt">{{ fmtDate(s.updatedAt) }}</span>
              </div>
              <div class="skill-card-foot">
                <button class="app-link-btn" @click="openDetail(s)">查看</button>
                <button v-if="s.editable" class="app-link-btn" @click="openEdit(s)">编辑</button>
                <button v-if="s.disableable" class="app-link-btn" @click="toggle(s)">{{ s.disabled ? '启用' : '停用' }}</button>
                <button v-if="s.deletable" class="app-link-btn" @click="download(s)">下载</button>
                <button v-if="s.editable" class="app-link-btn" @click="openPublish(s)">上架</button>
                <a-popconfirm v-if="s.deletable" title="删除这个技能？" ok-text="删除" cancel-text="取消"
                              @confirm="remove(s)">
                  <button class="app-link-btn danger">删除</button>
                </a-popconfirm>
              </div>
            </div>
          </div>
        </template>

        <p class="skill-foot-hint">
          已停用的技能不会进模型清单，但本轮仍可在输入框里点名使用一次。
          助手在对话里沉淀的技能落在当前会话的沙盒工作区，不占「我的技能」。
        </p>
      </template>
    </div>

    <!-- 新建 -->
    <a-modal v-model:open="createOpen" title="新建技能" :footer="null" :width="760" destroy-on-close>
      <a-form layout="vertical">
        <a-form-item label="技能名" required>
          <a-input v-model:value="createForm.name" placeholder="如：report-helper（中英文/数字/下划线/连字符，1~64 字符）"
                   :maxlength="64" show-count />
        </a-form-item>
        <a-form-item label="描述" required>
          <a-input v-model:value="createForm.description" placeholder="一句话说明什么场景用它——模型靠这句判断要不要读取"
                   :maxlength="1000" show-count />
        </a-form-item>
        <a-form-item label="技能正文（Markdown）">
          <a-textarea v-model:value="createForm.content" :rows="14" class="key-code-area"
                      placeholder="# 做法&#10;&#10;1. 先读 references/guide.md&#10;2. 按口径汇总，输出表格" />
        </a-form-item>
      </a-form>
      <p class="skill-form-hint">只写 Markdown 正文即可，frontmatter（name / description）由平台自动补；技能名之后不可修改。</p>
      <div class="key-modal-foot">
        <button class="app-btn ghost" @click="createOpen = false">取消</button>
        <button class="app-btn" :disabled="creating" @click="submitCreate">{{ creating ? '创建中…' : '创建' }}</button>
      </div>
    </a-modal>

    <!-- 安装：zip / URL -->
    <a-modal v-model:open="installOpen" title="安装技能" :footer="null" :width="720" destroy-on-close>
      <a-tabs v-model:activeKey="installTab">
        <a-tab-pane key="zip" tab="上传 zip">
          <a-upload-dragger class="skill-drop" :before-upload="pickZip" :show-upload-list="false" accept=".zip" :disabled="installing">
            <p class="ant-upload-drag-icon"><inbox-outlined /></p>
            <p class="ant-upload-text">点击或拖拽 zip 到这里</p>
            <p class="ant-upload-hint">
              压缩包根目录或唯一外层目录里要有 SKILL.md；可带 references/、scripts/。
              上限 {{ limits.zipMB || 24 }}MB / {{ limits.files || 120 }} 个文件。
            </p>
          </a-upload-dragger>
          <div v-if="zipFile" class="skill-zip-picked">
            已选择：<b>{{ zipFile.name }}</b>（{{ fmtSize(zipFile.size) }}）
            <button class="app-link-btn" @click="zipFile = null">移除</button>
          </div>
          <a-form layout="vertical" style="margin-top:12px">
            <a-form-item label="技能名（可选）">
              <a-input v-model:value="installForm.name" placeholder="留空则取 SKILL.md frontmatter 里的 name" :maxlength="64" />
            </a-form-item>
            <a-checkbox v-model:checked="installForm.overwrite">同名覆盖（整包替换，旧包里的 references/scripts 会丢失）</a-checkbox>
          </a-form>
          <div class="key-modal-foot">
            <span v-if="uploadPct > 0 && uploadPct < 100" class="skill-upload-pct">上传中 {{ uploadPct }}%</span>
            <button class="app-btn ghost" @click="installOpen = false">取消</button>
            <button class="app-btn" :disabled="installing || !zipFile" @click="submitZip">
              {{ installing ? '安装中…' : '安装' }}
            </button>
          </div>
        </a-tab-pane>

        <a-tab-pane key="url" tab="从 URL 安装">
          <a-alert v-if="!remote.enabled" type="warning" show-icon style="margin-bottom:12px"
                   message="远程安装未开启"
                   description="需要管理员在「系统设置 → 工具调用 → 技能」里配置「允许远程安装的域名」（精确匹配，逗号分隔）。服务端仍会校验公网地址并限制体积。" />
          <a-alert v-else type="info" show-icon style="margin-bottom:12px"
                   :message="'只允许从以下域名安装：' + (remote.hosts || []).join('、')"
                   description="地址必须指向 SKILL.md 原文（GitHub 页面链接请换成 raw 链接），内容须含 name / description 的 frontmatter。" />
          <a-form layout="vertical">
            <a-form-item label="SKILL.md 地址" required>
              <a-input v-model:value="installForm.url" placeholder="https://raw.githubusercontent.com/org/repo/main/skills/demo/SKILL.md"
                       :disabled="!remote.enabled" />
            </a-form-item>
            <a-form-item label="技能名（可选）">
              <a-input v-model:value="installForm.name" placeholder="留空则取文件里 frontmatter 的 name" :maxlength="64"
                       :disabled="!remote.enabled" />
            </a-form-item>
            <a-checkbox v-model:checked="installForm.overwrite">同名覆盖</a-checkbox>
          </a-form>
          <div class="key-modal-foot">
            <button class="app-btn ghost" @click="installOpen = false">取消</button>
            <button class="app-btn" :disabled="installing || !remote.enabled" @click="submitUrl">
              {{ installing ? '安装中…' : '安装' }}
            </button>
          </div>
        </a-tab-pane>
      </a-tabs>
    </a-modal>

    <!-- 查看 -->
    <a-modal v-model:open="detailOpen" :title="'技能：' + (detail.name || '')" :footer="null" :width="820" destroy-on-close>
      <a-spin v-if="detailLoading" style="display:block;margin:36px auto" />
      <template v-else>
        <div class="skill-view-meta">
          <span>{{ sourceLabel(detail.source) }}</span>
          <span v-if="detail.disabled" class="app-pill warn key-tag">{{ detail.platformDisabled ? '平台停用' : '我停用了' }}</span>
          <span v-if="detail.size">{{ fmtSize(detail.size) }}</span>
          <span v-if="detail.fileCount > 1">{{ detail.fileCount }} 个文件</span>
          <span v-if="detail.updatedAt">{{ fmtDate(detail.updatedAt) }}</span>
          <span class="skill-view-toggle">
            <a-switch v-if="detail.disableable" size="small" :checked="!detail.disabled"
                      :loading="toggling" @change="toggleFromDetail" />
            <span class="key-dim">{{ detail.disabled ? '已停用' : '生效中' }}</span>
          </span>
        </div>
        <div class="skill-view-tabs">
          <button class="app-link-btn" :class="{ on: detailTab === 'render' }" @click="detailTab = 'render'">预览</button>
          <button class="app-link-btn" :class="{ on: detailTab === 'raw' }" @click="detailTab = 'raw'">原文</button>
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div v-if="detailTab === 'render'" class="md skill-view-body" v-html="renderMd(detail.content || '（这条技能没有正文）')" />
        <pre v-else class="skill-view-raw">{{ detail.raw || detail.content || '' }}</pre>

        <div v-if="detail.editable && detail.files && detail.files.length" class="skill-files">
          <div class="skill-files-title">附属文件</div>
          <button v-for="f in detail.files" :key="f.path" class="skill-file-row" type="button"
                  :disabled="f.binary" @click="openFile(f)">
            <span class="skill-file-path">{{ f.path }}</span>
            <span class="skill-file-size">{{ fmtSize(f.size) }}</span>
          </button>
        </div>

        <div class="key-modal-foot">
          <button v-if="detail.editable" class="app-btn ghost" @click="openEdit(detail)">编辑</button>
          <button v-if="detail.deletable" class="app-btn ghost" @click="download(detail)">下载 zip</button>
          <button class="app-btn" @click="detailOpen = false">关闭</button>
        </div>
      </template>
    </a-modal>

    <!-- 编辑 -->
    <a-modal v-model:open="editOpen" :title="'编辑技能：' + editForm.name" :footer="null" :width="820" destroy-on-close>
      <a-form layout="vertical">
        <a-form-item label="描述" required>
          <a-input v-model:value="editForm.description" :maxlength="1000" show-count
                   placeholder="一句话说明什么场景用它——模型靠这句判断要不要读取" />
        </a-form-item>
        <a-form-item label="SKILL.md 原文">
          <a-textarea v-model:value="editForm.content" :rows="18" class="key-code-area" />
        </a-form-item>
      </a-form>
      <p class="skill-form-hint">可以改正文与 frontmatter 的 description；<b>name 必须保持「{{ editForm.name }}」</b>，改名会打断智能体上的技能引用。</p>
      <div class="key-modal-foot">
        <button class="app-btn ghost" @click="editOpen = false">取消</button>
        <button class="app-btn" :disabled="saving" @click="submitEdit">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </a-modal>

    <!-- 附属文件预览 -->
    <a-modal v-model:open="fileOpen" :title="fileView.path" :footer="null" :width="760" destroy-on-close>
      <a-spin v-if="fileLoading" style="display:block;margin:28px auto" />
      <pre v-else class="skill-view-raw">{{ fileView.content }}</pre>
    </a-modal>

    <!-- 上架到市场（与市场页共用弹窗） -->
    <MarketPublishModal v-model:open="publishOpen" :preset-name="publishName" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  InboxOutlined, LinkOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, UploadOutlined
} from '@ant-design/icons-vue'
import {
  createSkill, deleteSkill, downloadSkillZip, getSkillDetail, getSkillFile, installSkillFromUrl,
  installSkillZip, listSkills, setSkillDisabled, updateSkill
} from '../api'
import { renderMd } from '../utils/markdown'
import MarketPublishModal from '../components/MarketPublishModal.vue'

const SOURCE_LABEL = { user: '我的技能', builtin: '内置技能', workspace: '工作区技能' }
const SOURCE_ORDER = ['user', 'builtin', 'workspace']

// ---------------- 上架到市场 ----------------
const publishOpen = ref(false)
const publishName = ref('')
const openPublish = s => {
  publishName.value = s.name
  publishOpen.value = true
}

const skills = ref([])
const remote = ref({ enabled: false, hosts: [] })
const limits = ref({})
const loading = ref(false)
const keyword = ref('')

const activeCount = computed(() => skills.value.filter(s => !s.disabled).length)
const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return skills.value
  return skills.value.filter(s =>
    (s.name || '').toLowerCase().includes(k) || (s.description || '').toLowerCase().includes(k))
})
const groups = computed(() => SOURCE_ORDER
  .map(key => ({ key, label: SOURCE_LABEL[key], list: filtered.value.filter(s => s.source === key) }))
  .filter(g => g.list.length))

const sourceLabel = s => SOURCE_LABEL[s] || s || '技能'
const fmtSize = n => {
  if (!n) return ''
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + 'MB'
  if (n >= 1024) return Math.round(n / 1024) + 'KB'
  return n + 'B'
}
const fmtDate = ts => {
  try { return new Date(ts).toLocaleDateString() } catch (e) { return '' }
}
const errText = e => (e && e.message) || String(e)

const load = async () => {
  loading.value = true
  try {
    const r = await listSkills()
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    skills.value = Array.isArray(r.data?.skills) ? r.data.skills : []
    remote.value = r.data?.remoteInstall || { enabled: false, hosts: [] }
    limits.value = r.data?.limits || {}
  } catch (e) {
    message.error('加载技能失败：' + errText(e))
  } finally {
    loading.value = false
  }
}

// ---------------- 新建 ----------------
const createOpen = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', description: '', content: '' })
const openCreate = () => {
  createForm.value = { name: '', description: '', content: '' }
  createOpen.value = true
}
const submitCreate = async () => {
  const f = createForm.value
  if (!f.name.trim()) { message.warning('请填写技能名'); return }
  if (!f.description.trim()) { message.warning('请填写描述——模型靠它判断何时读取'); return }
  if (!f.content.trim()) { message.warning('请填写技能正文'); return }
  creating.value = true
  try {
    const r = await createSkill({
      name: f.name.trim(), description: f.description.trim(), content: f.content, overwrite: false
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '创建失败')
    message.success('已创建技能「' + (r.data?.name || f.name) + '」')
    createOpen.value = false
    await load()
  } catch (e) {
    message.error('创建失败：' + errText(e))
  } finally {
    creating.value = false
  }
}

// ---------------- 安装 ----------------
const installOpen = ref(false)
const installTab = ref('zip')
const installing = ref(false)
const uploadPct = ref(0)
const zipFile = ref(null)
const installForm = ref({ url: '', name: '', overwrite: false })
const openInstall = tab => {
  installTab.value = tab || 'zip'
  installForm.value = { url: '', name: '', overwrite: false }
  zipFile.value = null
  uploadPct.value = 0
  installOpen.value = true
}
/** before-upload 返回 false：只接管文件，不走组件自带上传（安装按钮统一提交） */
const pickZip = file => {
  if (file.size > (limits.value.zipMB || 24) * 1024 * 1024) {
    message.warning('技能包超过 ' + (limits.value.zipMB || 24) + 'MB 上限')
    return false
  }
  zipFile.value = file
  return false
}
const submitZip = async () => {
  if (!zipFile.value) { message.warning('请选择 zip 文件'); return }
  installing.value = true
  uploadPct.value = 0
  try {
    const r = await installSkillZip(zipFile.value,
      { name: installForm.value.name.trim(), overwrite: installForm.value.overwrite },
      p => { uploadPct.value = p })
    if (!r || !r.success) throw new Error((r && r.msg) || '安装失败')
    message.success('已安装技能「' + (r.data?.name || '') + '」')
    installOpen.value = false
    await load()
  } catch (e) {
    message.error('安装失败：' + errText(e))
  } finally {
    installing.value = false
    uploadPct.value = 0
  }
}
const submitUrl = async () => {
  if (!installForm.value.url.trim()) { message.warning('请填写技能文件地址'); return }
  installing.value = true
  try {
    const r = await installSkillFromUrl({
      url: installForm.value.url.trim(),
      name: installForm.value.name.trim(),
      overwrite: installForm.value.overwrite
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '安装失败')
    message.success('已安装技能「' + (r.data?.name || '') + '」')
    installOpen.value = false
    await load()
  } catch (e) {
    message.error('安装失败：' + errText(e))
  } finally {
    installing.value = false
  }
}

// ---------------- 查看 ----------------
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({})
const detailTab = ref('render')
const toggling = ref(false)
const openDetail = async s => {
  detailOpen.value = true
  detailLoading.value = true
  detailTab.value = 'render'
  detail.value = { ...s }
  try {
    const r = await getSkillDetail(s.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    detail.value = r.data || s
  } catch (e) {
    message.error('读取技能失败：' + errText(e))
  } finally {
    detailLoading.value = false
  }
}
const toggleFromDetail = async checked => {
  const s = detail.value
  if (!s.name) return
  toggling.value = true
  try {
    const r = await setSkillDisabled(s.name, !checked)
    if (!r || !r.success) throw new Error((r && r.msg) || '操作失败')
    detail.value = { ...s, disabled: !checked, userDisabled: !checked }
    message.success(checked ? '已启用' : '已停用（只对你生效）')
    await load()
  } catch (e) {
    message.error('操作失败：' + errText(e))
  } finally {
    toggling.value = false
  }
}

// ---------------- 编辑 ----------------
const editOpen = ref(false)
const saving = ref(false)
const editForm = ref({ name: '', description: '', content: '' })
const openEdit = async s => {
  try {
    const r = await getSkillDetail(s.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    editForm.value = {
      name: r.data.name,
      description: r.data.description || '',
      content: r.data.raw || r.data.content || ''
    }
    editOpen.value = true
  } catch (e) {
    message.error('读取技能失败：' + errText(e))
  }
}
const submitEdit = async () => {
  const f = editForm.value
  if (!f.description.trim()) { message.warning('请填写描述'); return }
  if (!f.content.trim()) { message.warning('SKILL.md 不能为空'); return }
  saving.value = true
  try {
    const r = await updateSkill(f.name, { description: f.description.trim(), content: f.content })
    if (!r || !r.success) throw new Error((r && r.msg) || '保存失败')
    message.success('已保存，下一轮生效')
    editOpen.value = false
    if (detailOpen.value) await openDetail({ name: f.name })
    await load()
  } catch (e) {
    message.error('保存失败：' + errText(e))
  } finally {
    saving.value = false
  }
}

// ---------------- 启停 / 删除 / 下载 ----------------
const toggle = async s => {
  try {
    const r = await setSkillDisabled(s.name, !s.disabled)
    if (!r || !r.success) throw new Error((r && r.msg) || '操作失败')
    message.success(r.msg || (s.disabled ? '已启用' : '已停用'))
    await load()
  } catch (e) {
    message.error('操作失败：' + errText(e))
  }
}
const remove = async s => {
  try {
    const r = await deleteSkill(s.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '删除失败')
    message.success('已删除')
    await load()
  } catch (e) {
    message.error('删除失败：' + errText(e))
  }
}
const download = async s => {
  try {
    await downloadSkillZip(s.name)
  } catch (e) {
    message.error('下载失败：' + errText(e))
  }
}

// ---------------- 附属文件预览 ----------------
const fileOpen = ref(false)
const fileLoading = ref(false)
const fileView = ref({ path: '', content: '' })
const openFile = async f => {
  if (f.binary) { message.info('二进制文件不支持在线预览，请下载技能包查看'); return }
  fileView.value = { path: f.path, content: '' }
  fileOpen.value = true
  fileLoading.value = true
  try {
    const r = await getSkillFile(detail.value.name, f.path)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    fileView.value = { path: f.path, content: r.data.content || '' }
  } catch (e) {
    fileView.value = { path: f.path, content: '读取失败：' + errText(e) }
  } finally {
    fileLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.head-count { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.head-count b { color: var(--app-text); font-weight: 600; }
.head-search { width: 210px; }
.head-search-ic { color: var(--app-text3); font-size: 12px; }
.key-dim { color: var(--app-text3); font-size: 12px; }
.key-empty { text-align: center; padding: 40px 20px; }
.key-empty-title { font-weight: 600; margin-bottom: 6px; }
.key-empty-desc { font-size: 13px; color: var(--app-text3); line-height: 1.7; max-width: 560px; margin: 0 auto 14px; }
.key-empty-ops { display: flex; justify-content: center; gap: 8px; }
.key-tag { font-size: 11px; flex: none; line-height: 18px; }
.key-modal-foot { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 18px; }
.key-code-area textarea { font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace); font-size: 12px; line-height: 1.7; }

.res-group-title { font-size: 13px; font-weight: 600; margin: 0 0 10px; }
.res-group-n { font-size: 11px; font-weight: 400; color: var(--app-text3); background: var(--app-panel-2); border-radius: 999px; padding: 1px 8px; margin-left: 4px; }
.skill-tip { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: -4px 0 10px; }
.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; margin-bottom: 18px; }
.skill-card:hover { border-color: var(--app-accent-border); }
.skill-card-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.skill-card-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.skill-card-desc { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: 6px 0 6px; height: 38px; overflow: hidden; }
.skill-desc-warn { color: var(--app-warn-text); }
.skill-card-meta { display: flex; gap: 10px; font-size: 11px; color: var(--app-text3); margin-bottom: 8px; }
.skill-card-foot { display: flex; align-items: center; justify-content: flex-end; gap: 2px; border-top: 1px dashed var(--app-border); padding-top: 4px; }
.skill-foot-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: 4px 0 0; }
.skill-form-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: -6px 0 0; }
.skill-zip-picked { font-size: 12px; color: var(--app-text2); margin-top: 10px; display: flex; align-items: center; gap: 8px; }
/* antd 给拖拽区写死 height 100%：在自动高度的 Tab 面板里，容器高先按「拖拽区=内容高」算出，
   再把 100% 解成这个值，于是拖拽区吃掉整块面板、下面的技能名与安装按钮被挤出弹窗。 */
.skill-drop :deep(.ant-upload-drag) { height: auto; }
.skill-upload-pct { margin-right: auto; font-size: 12px; color: var(--app-text3); }

.skill-view-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; font-size: 12px; color: var(--app-text3); margin-bottom: 8px; }
.skill-view-toggle { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; }
.skill-view-tabs { display: flex; gap: 14px; border-bottom: 1px solid var(--app-border); padding-bottom: 6px; margin-bottom: 8px; }
.skill-view-tabs .app-link-btn { color: var(--app-text3); }
.skill-view-tabs .app-link-btn.on { color: var(--app-accent); font-weight: 600; }
.skill-view-body { max-height: 52vh; overflow-y: auto; border: 1px solid var(--app-border); border-radius: 6px; padding: 12px 14px; }
.skill-view-raw { max-height: 52vh; overflow: auto; border: 1px solid var(--app-border); border-radius: 6px; padding: 12px 14px;
  background: var(--app-panel-2); font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; margin: 0; }
.skill-files { margin-top: 12px; border-top: 1px dashed var(--app-border); padding-top: 10px; }
.skill-files-title { font-size: 12px; font-weight: 600; color: var(--app-text2); margin-bottom: 6px; }
.skill-file-row { display: flex; align-items: center; gap: 10px; width: 100%; border: 0; background: transparent;
  padding: 4px 2px; cursor: pointer; font-size: 12px; color: var(--app-text2); text-align: left; }
.skill-file-row:hover { color: var(--app-accent); }
.skill-file-row:disabled { cursor: not-allowed; opacity: .55; }
.skill-file-path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.skill-file-size { flex: none; color: var(--app-text3); font-size: 11px; }
</style>
