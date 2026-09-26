<template>
  <!-- 技能（Skills）：固化「这类问题该怎么做」的做法说明，模型按需读取后照做。
       技能是个人资产：这里列出的 = 内置技能（随版本分发，可各自停用）+ 你自己新建/安装的技能。 -->
  <div class="skill-panel">
    <div class="key-bar">
      <div class="key-bar-left">
        <a-input v-model:value="keyword" placeholder="搜索技能名称 / 描述" allow-clear size="small" class="res-search">
          <template #prefix><search-outlined class="res-search-ic" /></template>
        </a-input>
        <span class="key-stat">共 <b>{{ skills.length }}</b> 个技能 · 生效中 <b>{{ activeCount }}</b></span>
      </div>
      <div class="key-bar-actions">
        <a-tooltip title="刷新列表">
          <button class="app-icon-btn" aria-label="刷新技能列表" :disabled="loading" @click="load"><reload-outlined /></button>
        </a-tooltip>
        <button class="app-btn ghost small" @click="openInstall">从 URL 安装</button>
        <button class="app-btn small" @click="openCreate">＋ 新建技能</button>
      </div>
    </div>

    <a-alert v-if="!toolsEnabled" type="warning" show-icon style="margin-bottom:12px"
             message="平台未开启「工具调用」总开关"
             description="技能照常展示，但模型无法调用 readSkill 读取技能正文——清单提示会失效。需管理员在「系统设置 → 工具调用」中开启总开关。" />

    <div v-if="!skills.length" class="key-empty">
      <div class="key-empty-title">还没有技能</div>
      <div class="key-empty-desc">
        技能用来固化「这类问题该怎么做」的做法——步骤、输出格式、禁忌。模型按需读取后照做，不必每次在提问里重复交代。
        你在这里新建的技能只有自己能用，别人看不见。
      </div>
      <button class="app-btn small" @click="openCreate">新建第一个技能</button>
    </div>
    <div v-else-if="!filtered.length" class="key-empty">
      <div class="key-empty-title">没有匹配的技能</div>
      <div class="key-empty-desc">没有名称或描述包含「{{ keyword }}」的技能。</div>
      <button class="app-btn ghost small" @click="keyword = ''">清除搜索</button>
    </div>

    <!-- 卡片列表：按来源分组（我的技能 / 内置） -->
    <template v-else>
      <template v-for="g in groups" :key="g.title">
        <div class="res-group-title">{{ g.title }} ({{ g.list.length }})</div>
        <div class="skill-grid">
          <div v-for="s in g.list" :key="s.dirName" class="skill-card">
            <div class="skill-card-head">
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
import { message } from 'ant-design-vue'
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { listSkills, getSkillDetail, createSkill, setSkillDisabled, deleteSkill, installSkillFromUrl } from '../api'
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
.key-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.key-bar-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.key-bar-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; flex: none; }
.key-stat { font-size: 12px; color: var(--app-text2); }
.key-stat b { color: var(--app-text); font-weight: 600; }
.key-dim { color: var(--app-text3); font-size: 12px; }
.res-search { width: 220px; }
.res-search :deep(.ant-input-affix-wrapper) { border-radius: 8px; }
.res-search-ic { color: var(--app-text3); font-size: 12px; }
.key-empty { text-align: center; padding: 36px 20px; border: 1px dashed var(--app-border); border-radius: 8px; }
.key-empty-title { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.key-empty-desc { font-size: 12px; color: var(--app-text3); margin-bottom: 14px; line-height: 1.7; }
.key-tag { font-size: 11px; flex: none; line-height: 18px; }
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.res-group-title { font-size: 12px; font-weight: 500; color: var(--app-text3); margin: 12px 0 6px; }

.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }
.skill-card { border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; background: #fff; }
.skill-card:hover { border-color: #d5dce8; background: #fafbfc; }
.skill-card-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.skill-card-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.skill-card-desc { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: 6px 0 8px; height: 38px; overflow: hidden; }
.skill-desc-warn { color: #a3691b; }
.skill-card-foot { display: flex; align-items: center; justify-content: flex-end; gap: 2px; border-top: 1px dashed var(--app-border); padding-top: 4px; }
.skill-tip { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin-bottom: 10px; }
.skill-view-meta { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: var(--app-text3); margin-bottom: 10px; }
.skill-view-meta code { background: #f2f3f5; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.skill-view-toggle { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; }
.skill-view-body { max-height: 56vh; overflow-y: auto; border: 1px solid var(--app-border); border-radius: 6px; padding: 12px 14px; }
</style>
