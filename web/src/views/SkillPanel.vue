<template>
  <!-- 技能（Skills）：固化「这类问题该怎么做」的做法说明，模型按需读取后照做。
       技能是个人资产：这里列出的 = 内置技能（随版本分发，可各自停用）+ 你自己新建/安装的技能。
       页面骨架与「模型供应商」Tab 一致：标题栏（标题 + 说明 + 主操作）+ 带内边距的内容区。 -->
  <div class="app-page">
    <div class="app-page-head">
      <!-- 页名与 Tab 名重复，标题仅保留给读屏器（sr-only），视觉上从统计起头 -->
      <h1 class="app-page-title sr-only">技能</h1>
      <span class="head-count">共 <b>{{ skills.length }}</b> 个 · 生效中 <b>{{ activeCount }}</b></span>
      <span class="head-hint-plain">固化「这类问题该怎么做」的做法说明，模型按需读取后照做；技能只属于你自己</span>
      <!-- 页头右侧一组（对齐智能体 Tab）：搜索 + 刷新 + 安装 + 新建 + 批量区（分隔线独立成区） -->
      <div class="head-r">
        <a-input v-model:value="keyword" class="head-search" size="small" allow-clear placeholder="搜索名称或描述">
          <template #prefix><search-outlined class="head-search-ic" /></template>
        </a-input>
        <a-tooltip title="刷新列表">
          <button class="app-icon-btn" aria-label="刷新技能列表" :disabled="loading" @click="load"><reload-outlined /></button>
        </a-tooltip>
        <button class="app-btn ghost small" @click="openInstall">从 URL 安装</button>
        <button class="app-btn" @click="openCreate">
          <plus-outlined /> 新建技能
        </button>
        <!-- 批量区：默认收起，点「批量管理」进入批量模式；开关放最右：进出模式自身位置不动 -->
        <div v-if="skills.length" class="batch-group">
          <template v-if="batchMode">
            <a-checkbox :checked="allChecked" :indeterminate="someChecked" @change="toggleAll">全选</a-checkbox>
            <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchDisabled(false)">启用</button>
            <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchDisabled(true)">停用</button>
            <button class="app-btn ghost small batch-del" :disabled="!selected.length || batchBusy" @click="doBatchDelete">删除</button>
          </template>
          <button class="app-btn ghost small" :class="{ 'batch-on': batchMode }" @click="toggleBatchMode">{{ batchMode ? '退出管理' : '批量管理' }}</button>
        </div>
      </div>
    </div>

    <div class="app-page-body">
      <a-alert v-if="!toolsEnabled" type="warning" show-icon style="margin-bottom:12px"
               message="平台未开启「工具调用」总开关"
               description="技能照常展示，但模型无法调用 readSkill 读取技能正文——清单提示会失效。需管理员在「系统设置 → 工具调用」中开启总开关。" />

      <div v-if="!skills.length" class="app-card key-empty">
        <div class="key-empty-title">还没有技能</div>
        <div class="key-empty-desc">
          技能用来固化「这类问题该怎么做」的做法——步骤、输出格式、禁忌。模型按需读取后照做，不必每次在提问里重复交代。
          你在这里新建的技能只有自己能用，别人看不见。
        </div>
        <button class="app-btn small" @click="openCreate">新建第一个技能</button>
      </div>
      <div v-else-if="!filtered.length" class="app-card key-empty">
        <div class="key-empty-title">没有匹配的技能</div>
        <div class="key-empty-desc">没有名称或描述包含「{{ keyword }}」的技能。</div>
        <button class="app-btn ghost small" @click="keyword = ''">清除搜索</button>
      </div>

      <!-- 卡片列表：按来源分组（我的技能 / 内置） -->
      <template v-else>
        <template v-for="g in groups" :key="g.title">
          <div class="res-group-title">{{ g.title }} ({{ g.list.length }})</div>
          <div class="skill-grid">
            <div v-for="s in g.list" :key="s.dirName" class="app-card skill-card">
              <div class="skill-card-head">
                <a-checkbox v-if="batchMode" class="skill-check" :checked="selected.includes(s.dirName)"
                            @change="toggleSelect(s.dirName)" />
                <span class="skill-card-name" :title="s.name">{{ s.name }}</span>
                <span v-if="s.disabled" class="app-pill warn key-tag">已停用</span>
                <span v-else class="app-pill ok key-tag">生效中</span>
              </div>
              <div class="skill-card-desc" :class="{ 'skill-desc-warn': !s.description }" :title="s.description || ''">
                {{ s.description || '（未填描述：模型不会主动读取它）' }}
              </div>
              <div class="skill-card-foot">
                <button class="app-link-btn" @click="viewSkill(s)">查看</button>
                <button class="app-link-btn" @click="toggleSkill(s)">{{ s.disabled ? '启用' : '停用' }}</button>
                <a-popconfirm v-if="s.source !== 'builtin'" title="删除该技能？" ok-text="删除" cancel-text="取消" @confirm="delSkill(s)">
                  <button class="app-link-btn danger">删除</button>
                </a-popconfirm>
              </div>
            </div>
          </div>
        </template>
      </template>
    </div>

    <!-- 新建技能 -->
    <a-modal v-model:open="createOpen" title="新建技能" :footer="null" :width="720">
      <a-form layout="vertical">
        <a-form-item label="技能名" required>
          <a-input v-model:value="createForm.name" placeholder="如：报表字段命名规范（支持中英文、数字、下划线、连字符）" :maxlength="64" />
        </a-form-item>
        <a-form-item label="描述" required>
          <a-input v-model:value="createForm.description" placeholder="一句话说明什么场景用它——模型靠这句判断要不要读取" :maxlength="200" />
        </a-form-item>
        <a-form-item label="技能内容（Markdown）">
          <a-textarea v-model:value="createForm.content" :rows="12" />
        </a-form-item>
      </a-form>
      <div class="key-modal-foot">
        <button class="app-btn ghost" @click="createOpen = false">取消</button>
        <button class="app-btn" :disabled="creating" @click="submitCreate">{{ creating ? '创建中…' : '创建' }}</button>
      </div>
    </a-modal>

    <!-- 查看技能（含启用/停用开关，改完同步列表） -->
    <a-modal v-model:open="viewOpen" :title="'技能：' + view.name" :footer="null" :width="760">
      <div class="skill-view-meta">
        <span>标识 <code>{{ view.dirName }}</code></span>
        <span>版本 {{ view.version || '—' }}</span>
        <span>哈希 <code>{{ view.hash }}</code></span>
        <span>来源 {{ view.source === 'builtin' ? '内置' : '我的' }}</span>
        <span class="skill-view-toggle">
          <a-switch size="small" :checked="!view.disabled" :loading="viewToggling" @change="toggleFromView" />
          <span class="key-dim">{{ view.disabled ? '已停用' : '生效中' }}</span>
        </span>
      </div>
      <div class="skill-view-body md" v-html="renderMd(view.content)"></div>
    </a-modal>

    <!-- 从 URL 安装 -->
    <a-modal v-model:open="installOpen" title="从 URL 安装技能" :footer="null" :width="620">
      <a-form layout="vertical">
        <a-form-item label="技能文件地址（SKILL.md 原文）" required>
          <a-input v-model:value="installForm.url" placeholder="https://…/SKILL.md（GitHub 请用 raw 链接，不要用网页链接）" />
        </a-form-item>
        <a-form-item label="技能名（可选）">
          <a-input v-model:value="installForm.name" placeholder="留空则用文件 frontmatter 里的 name" :maxlength="64" />
        </a-form-item>
      </a-form>
      <div class="skill-tip" style="margin-bottom:0">
        安装时会校验：地址必须是 http/https、内容必须带 frontmatter（name + description）。
        技能内容只作为文本指令保存，<b>不会执行文件里的任何脚本</b>。
      </div>
      <div class="key-modal-foot">
        <button class="app-btn ghost" @click="installOpen = false">取消</button>
        <button class="app-btn" :disabled="installing" @click="doInstall">{{ installing ? '安装中…' : '安装' }}</button>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SearchOutlined, ReloadOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { listSkills, getSkillDetail, createSkill, setSkillDisabled, deleteSkill, installSkillFromUrl,
         batchDeleteSkills, batchSetSkillsDisabled } from '../api'
import { renderMd } from '../utils/markdown'

const skills = ref([])
const toolsEnabled = ref(true)   // 平台「工具调用」总开关（管理员控制）：影响 readSkill 能否被模型调用
const loading = ref(false)
const keyword = ref('')
const creating = ref(false)
const createOpen = ref(false)
const createForm = ref({ name: '', description: '', content: '' })
const viewOpen = ref(false)
const viewToggling = ref(false)
const view = ref({ name: '', dirName: '', version: '', hash: '', source: '', content: '', disabled: false })
const installOpen = ref(false)
const installing = ref(false)
const installForm = ref({ url: '', name: '' })

const activeCount = computed(() => skills.value.filter(s => !s.disabled).length)
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return skills.value
  return skills.value.filter(s =>
    String(s.name || '').toLowerCase().includes(kw) || String(s.description || '').toLowerCase().includes(kw))
})

// ---- 批量操作：全选作用于当前搜索过滤后的可见项；内置技能可停用、删除时后端逐条拒绝 ----
const selected = ref([])
const batchBusy = ref(false)
// 批量模式默认关闭：卡片不显示勾选框，点「批量管理」才进入（退出即清空勾选）
const batchMode = ref(false)
const toggleBatchMode = () => {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = []
}
const selectableIds = computed(() => filtered.value.map(s => s.dirName))
const allChecked = computed(() =>
  selectableIds.value.length > 0 && selectableIds.value.every(id => selected.value.includes(id)))
const someChecked = computed(() => selected.value.length > 0 && !allChecked.value)
const toggleSelect = id => {
  selected.value = selected.value.includes(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id]
}
const toggleAll = () => { selected.value = allChecked.value ? [] : [...selectableIds.value] }

/** 批量结果汇报：全成功走 message；有失败逐条弹 Modal 列出原因（不静默吞） */
const reportBatch = (r, verb) => {
  const data = (r && r.data) || {}
  const okCount = (data.succeeded || []).length
  const failed = data.failed || []
  if (!failed.length) {
    message.success(`已${verb} ${okCount} 个技能`)
    return
  }
  Modal.warning({
    title: `${verb}完成：成功 ${okCount} 个，失败 ${failed.length} 个`,
    content: failed.map(f => `「${f.name || f.id}」：${f.error}`).join('；'),
    okText: '知道了'
  })
}

const doBatchDisabled = async disabled => {
  batchBusy.value = true
  try {
    const r = await batchSetSkillsDisabled([...selected.value], disabled)
    reportBatch(r, disabled ? '停用' : '启用')
    await load()
  } catch (e) {
    message.error((disabled ? '批量停用' : '批量启用') + '失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

const doBatchDelete = () => {
  Modal.confirm({
    title: `删除选中的 ${selected.value.length} 个技能？`,
    content: '技能删除后不再注入清单；内置技能不可删（后端逐条拒绝并给出原因）。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      batchBusy.value = true
      try {
        const r = await batchDeleteSkills([...selected.value])
        reportBatch(r, '删除')
        await load()
      } catch (e) {
        message.error('批量删除失败：' + (e.message || ''))
      } finally {
        batchBusy.value = false
      }
    }
  })
}
/** 我的技能在前，内置技能在后（内置不可删、只能停用） */
const groups = computed(() => [
  { title: '我的技能', list: filtered.value.filter(s => s.source !== 'builtin') },
  { title: '内置技能', list: filtered.value.filter(s => s.source === 'builtin') }
].filter(g => g.list.length))

const load = async () => {
  loading.value = true
  try {
    const r = await listSkills()
    if (r.success && r.data) {
      skills.value = r.data.skills || []
      // 批量勾选与现存列表对账：已被删掉的 id 从选中集合里清掉（避免批量操作撞「不存在」）
      const alive = new Set(skills.value.map(x => x.dirName))
      selected.value = selected.value.filter(id => alive.has(id))
      toolsEnabled.value = r.data.toolsEnabled !== false
    }
  } catch (e) { message.error(e.message || '技能列表加载失败') }
  finally { loading.value = false }
}
const skillTemplate = () => '# 技能标题\n\n## 适用场景\n\n用户问到……时使用本技能。\n\n## 做法\n\n1. 先……\n2. 再……\n\n## 禁止\n\n- 不要……\n'
const openCreate = () => {
  createForm.value = { name: '', description: '', content: skillTemplate() }
  createOpen.value = true
}
const submitCreate = async () => {
  if (!createForm.value.name.trim()) { message.warning('请填写技能名'); return }
  // 描述不是可有可无：模型是靠这句判断要不要读技能，空描述等于装了不生效
  if (!createForm.value.description.trim()) { message.warning('请填写描述——模型靠它判断何时读取技能'); return }
  creating.value = true
  try {
    const r = await createSkill({
      name: createForm.value.name.trim(),
      description: createForm.value.description.trim(),
      content: createForm.value.content
    })
    if (r.success) { message.success('技能已创建'); createOpen.value = false; load() }
    else message.error(r.msg || '创建失败')
  } catch (e) { message.error(e.message || '创建失败') }
  finally { creating.value = false }
}
const viewSkill = async rec => {
  try {
    const r = await getSkillDetail(rec.dirName)
    if (r.success) { view.value = r.data; viewOpen.value = true }
    else message.error(r.msg || '读取失败')
  } catch (e) { message.error(e.message || '读取失败') }
}
const toggleSkill = async rec => {
  try {
    const r = await setSkillDisabled(rec.dirName, !rec.disabled)
    if (r.success) { message.success(rec.disabled ? '已启用' : '已停用'); load() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
const toggleFromView = async checked => {
  const rec = view.value
  if (!rec.dirName) return
  viewToggling.value = true
  try {
    const r = await setSkillDisabled(rec.dirName, !checked)
    if (r.success) {
      view.value = { ...rec, disabled: !checked }
      message.success(checked ? '已启用' : '已停用')
      load()
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { viewToggling.value = false }
}
const delSkill = async rec => {
  try {
    const r = await deleteSkill(rec.dirName)
    if (r.success) { message.success('已删除'); load() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}
const openInstall = () => {
  installForm.value = { url: '', name: '' }
  installOpen.value = true
}
const doInstall = async () => {
  if (!installForm.value.url.trim()) { message.warning('请填写技能文件地址'); return }
  installing.value = true
  try {
    const r = await installSkillFromUrl(installForm.value.url.trim(), installForm.value.name.trim())
    if (r.success) {
      message.success('已安装技能「' + (r.data?.name || '') + '」')
      installOpen.value = false
      load()
    } else message.error(r.msg || '安装失败')
  } catch (e) { message.error(e.message || '安装失败') }
  finally { installing.value = false }
}

onMounted(load)
</script>

<style scoped>
/* 页头右侧工具组（对齐智能体 Tab）：批量操作 + 搜索 + 刷新 + 安装 + 新建 */
.head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.head-count { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.head-count b { color: var(--app-text); font-weight: 600; }
.head-search { width: 200px; }
.head-search-ic { color: var(--app-text3); font-size: 12px; }
.batch-group { display: flex; align-items: center; gap: 8px; padding-left: 12px; border-left: 1px solid var(--app-border); }
.batch-on { color: var(--app-accent); border-color: var(--app-accent); }
.batch-del { color: var(--app-danger); }
.skill-check { flex: none; }
.key-dim { color: var(--app-text3); font-size: 12px; }
/* 空状态：与「模型供应商」一致——白卡片居中，不用虚线框 */
.key-empty { text-align: center; padding: 40px 20px; }
.key-empty-title { font-weight: 600; margin-bottom: 6px; }
.key-empty-desc { font-size: 13px; color: var(--app-text3); line-height: 1.7; max-width: 520px; margin: 0 auto 14px; }
.key-tag { font-size: 11px; flex: none; line-height: 18px; }
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.res-group-title { font-size: 13px; font-weight: 600; margin: 0 0 10px; }
.res-group-title ~ .res-group-title { margin-top: 16px; }

.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
/* 尺寸/圆角/边框复用 .app-card，与模型供应商卡片同规格 */
.skill-card:hover { border-color: var(--app-accent-border); }
.skill-card-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.skill-card-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.skill-card-desc { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: 6px 0 8px; height: 38px; overflow: hidden; }
.skill-desc-warn { color: var(--app-warn-text); }
.skill-card-foot { display: flex; align-items: center; justify-content: flex-end; gap: 2px; border-top: 1px dashed var(--app-border); padding-top: 4px; }
.skill-tip { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin-bottom: 10px; }
.skill-view-meta { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: var(--app-text3); margin-bottom: 10px; }
.skill-view-meta code { background: var(--app-panel-2); padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.skill-view-toggle { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; }
.skill-view-body { max-height: 56vh; overflow-y: auto; border: 1px solid var(--app-border); border-radius: 6px; padding: 12px 14px; }
</style>
