<template>
  <!-- MCP 目录条目上架/编辑（仅管理员，入口在市场 MCP 页签的「新增目录条目」）。
       目录只下发地址与传输类型，永不收凭据；测试连接复用 MCP 页的 /mcp/probe（不落配置）。 -->
  <a-modal :open="open" :title="editItem ? '编辑 MCP 目录条目' : '新增 MCP 目录条目'" :footer="null"
           :width="640" destroy-on-close @cancel="$emit('update:open', false)">
    <a-form layout="vertical">
      <a-form-item label="名称" required>
        <a-input v-model:value="form.name" :maxlength="40" show-count placeholder="如：DeepWiki MCP"
                 :disabled="!!editItem" />
        <p class="mk-form-hint">
          {{ editItem
            ? '名称是目录条目的标识，编辑态不可改；要改名请新增条目并下架旧条目'
            : '最多 40 字，不能包含「/」；同名条目重复保存 = 更新同一条' }}
        </p>
      </a-form-item>
      <a-form-item label="服务地址" required>
        <a-input v-model:value="form.url" placeholder="https://mcp.example.com/mcp" />
        <p class="mk-form-hint">仅 http(s) 地址</p>
      </a-form-item>
      <a-form-item label="传输类型">
        <a-radio-group v-model:value="form.mcpType">
          <a-radio value="streamable">streamable（推荐）</a-radio>
          <a-radio value="sse">SSE</a-radio>
        </a-radio-group>
        <p class="mk-form-hint">streamable：标准 MCP 传输，默认端点 /mcp；SSE：旧版传输，默认端点 /sse</p>
      </a-form-item>
      <a-form-item label="一句话简介" required>
        <a-input v-model:value="form.summary" :maxlength="256" show-count
                 placeholder="列表卡片上的一句话，说明它能做什么" />
      </a-form-item>
      <a-form-item label="详细介绍（可选）">
        <a-textarea v-model:value="form.description" :rows="5"
                    placeholder="什么场景用它、适合谁、安装后需要做什么（Markdown）。安装的人在详情页看到。" />
      </a-form-item>
      <a-form-item label="标签（可选）">
        <a-input v-model:value="form.tags" placeholder="逗号或顿号分隔，如：文档检索, 代码理解, 公开" />
      </a-form-item>
      <a-checkbox v-model:checked="form.requiresAuth" class="mk-auth-check">
        该服务需要认证请求头
      </a-checkbox>
      <p class="mk-form-hint mk-auth-hint">安装后由用户补充；目录不存凭据</p>
      <p v-if="probeResult" class="mk-probe" :class="probeResult.available ? 'ok' : 'bad'">
        {{ probeResult.available
          ? '连接正常，提供 ' + (probeResult.toolCount ?? 0) + ' 个工具'
          : '连接失败：' + (probeResult.error || '原因未知') }}
      </p>
    </a-form>
    <p class="mk-form-hint">编辑只影响后续安装，已安装的不会被改写</p>
    <div class="key-modal-foot">
      <button class="app-btn ghost" :disabled="probing || !form.url" @click="testConn">
        {{ probing ? '测试中…' : '测试连接' }}
      </button>
      <button class="app-btn ghost" @click="$emit('update:open', false)">取消</button>
      <button class="app-btn" :disabled="saving || !form.name || !form.url || !form.summary" @click="submit">
        {{ saving ? '保存中…' : (editItem ? '保存修改' : '保存并上架') }}
      </button>
    </div>
  </a-modal>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getMarketItem, probeMcp, publishMarketItem } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 编辑态传入列表条目（带 url/mcpType/requiresAuth）；新增不传 */
  editItem: { type: Object, default: null }
})
const emit = defineEmits(['update:open', 'published'])

const form = reactive({ name: '', url: '', mcpType: 'streamable', summary: '', description: '', tags: '', requiresAuth: false })
const probing = ref(false)
const probeResult = ref(null)
const saving = ref(false)

watch(() => props.open, async open => {
  if (!open) return
  probeResult.value = null
  Object.assign(form, { name: '', url: '', mcpType: 'streamable', summary: '', description: '', tags: '', requiresAuth: false })
  if (!props.editItem) return
  // 编辑态取详情回填（列表视图没有 description）
  try {
    const r = await getMarketItem(props.editItem.id)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    const d = r.data || {}
    form.name = d.name || ''
    form.url = d.url || ''
    form.mcpType = d.mcpType || 'streamable'
    form.summary = d.summary || ''
    form.description = d.description || ''
    form.tags = (d.tags || []).join(', ')
    form.requiresAuth = !!d.requiresAuth
  } catch (e) {
    message.error('读取条目失败：' + ((e && e.message) || e))
    emit('update:open', false)
  }
})

/** 测试连接：复用 MCP 页「先测再存」的 probe（不落配置、不带任何凭据） */
const testConn = async () => {
  probing.value = true
  probeResult.value = null
  try {
    const r = await probeMcp(form.url.trim(), form.mcpType)
    if (!r || !r.success) throw new Error((r && r.msg) || '测试失败')
    probeResult.value = r.data || { available: false, error: '响应为空' }
  } catch (e) {
    probeResult.value = { available: false, error: (e && e.message) || String(e) }
  } finally {
    probing.value = false
  }
}

const submit = async () => {
  saving.value = true
  try {
    const r = await publishMarketItem({
      type: 'mcp', name: form.name.trim(), url: form.url.trim(),
      mcpType: form.mcpType, requiresAuth: form.requiresAuth,
      summary: form.summary.trim(), description: form.description, tags: form.tags
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '保存失败')
    message.success(props.editItem ? '目录条目已更新' : '已上架到 MCP 目录')
    emit('update:open', false)
    emit('published', r.data)
  } catch (e) {
    message.error('保存失败：' + ((e && e.message) || e))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.mk-modal-sub { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: -6px 0 14px; }
.mk-form-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: 4px 0 0; }
.mk-auth-check { margin-top: 2px; }
.mk-auth-hint { margin-top: 2px; }
.mk-probe { font-size: 12px; line-height: 1.7; margin: 10px 0 0; }
.mk-probe.ok { color: var(--app-ok); }
.mk-probe.bad { color: var(--app-danger); }
.key-modal-foot { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 18px; }
.key-modal-foot .app-btn.ghost:first-child { margin-right: auto; }
</style>
