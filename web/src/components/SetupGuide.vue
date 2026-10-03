<template>
  <!-- 新手配置引导清单：卡片（对话页欢迎区，scope=chat 只列①②）/ 抽屉（侧栏入口，scope=all 列①②③）两形态。
       未加载成功（loaded=false）一律不渲染——宁可不引导，不误报。 -->
  <div v-if="setupGuide.loaded" class="sg" :class="'sg-' + variant">
    <!-- ① 添加聊天模型 -->
    <div class="sg-item" :class="{ done: chatReady }">
      <span class="sg-badge" :class="{ ok: chatReady }">{{ chatReady ? '✓' : '1' }}</span>
      <div class="sg-main">
        <div class="sg-title">添加聊天模型</div>
        <div class="sg-desc">新建供应商（如 DeepSeek、通义、自托管 Ollama）并登记至少一个「聊天」模型</div>
        <div class="sg-action">
          <span v-if="chatReady" class="sg-done-text">已登记 {{ setupGuide.chatModels.length }} 个聊天模型</span>
          <template v-else>
            <button class="app-btn sg-btn" :disabled="!canManage" @click="goNewProvider">去添加</button>
            <span v-if="!canManage" class="sg-warn-text">请联系管理员开通「智能体」菜单权限</span>
          </template>
        </div>
      </div>
    </div>

    <!-- ② 选择默认聊天模型：内联选择器，选完即保存（后端部分更新语义，不影响其他偏好字段） -->
    <div class="sg-item" :class="{ done: defaultReady }">
      <span class="sg-badge" :class="{ ok: defaultReady }">{{ defaultReady ? '✓' : '2' }}</span>
      <div class="sg-main">
        <div class="sg-title">选择默认聊天模型</div>
        <div class="sg-desc">智能体未指定、会话未手动选择时使用；失效引用视为未设置</div>
        <div class="sg-action">
          <template v-if="chatReady">
            <ModelSelect ref="msRef" :model-value="setupGuide.defaultModel" type="chat" width="100%"
                         :disabled="picking" placeholder="选择默认聊天模型" @change="onPickDefault" />
            <span v-if="defaultReady" class="sg-done-text">已设为默认</span>
          </template>
          <span v-else class="sg-tip-text">先完成第 ① 步，才能选择默认模型</span>
        </div>
      </div>
    </div>

    <!-- ③ 添加向量模型（仅抽屉清单显示）：建知识库的硬依赖 -->
    <div v-if="scope === 'all'" class="sg-item" :class="{ done: embeddingReady }">
      <span class="sg-badge" :class="{ ok: embeddingReady }">{{ embeddingReady ? '✓' : '3' }}</span>
      <div class="sg-main">
        <div class="sg-title">添加向量模型<span class="sg-use">知识库</span></div>
        <div class="sg-desc">建知识库需要：登记至少一个「向量」模型，用于文档入库与检索</div>
        <div class="sg-action">
          <span v-if="embeddingReady" class="sg-done-text">已登记向量模型</span>
          <template v-else>
            <button class="app-btn sg-btn" :disabled="!canManage" @click="goNewProvider">去添加</button>
            <span v-if="!canManage" class="sg-warn-text">请联系管理员开通「智能体」菜单权限</span>
          </template>
        </div>
      </div>
    </div>

    <!-- 「配置了效果更好」进阶建议区已整体下线：视觉/重排/兜底抽取建议项均已退役
         （重排归知识库检索设置绑定；抽取回落库主默认聊天模型）——必配层配完引导即消失 -->

    <div class="sg-note">模型供应商与模型按成员各自配置（谁建归谁），仅本人可用；配置随时可在「个人设置」修改。</div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { setUserPreference } from '../api.js'
import { isAdminSync, menuHasPath } from '../utils/auth.js'
import { chatReady, defaultReady, embeddingReady,
         refreshSetupGuide, setupGuide } from '../utils/setupGuide.js'
import ModelSelect from './ModelSelect.vue'

/**
 * 配置引导清单（复用组件）：三项「提醒级」清单——添加聊天模型 / 设默认聊天模型 / 添加向量模型。
 * variant=card：对话页欢迎区卡片（scope=chat，只列①②）；variant=plain：侧栏抽屉清单（scope=all，含③）。
 * emit close：跳转模型供应商页前由宿主关闭抽屉。
 */
const props = defineProps({
  variant: { type: String, default: 'plain' },   // 'card' | 'plain'
  scope: { type: String, default: 'all' }        // 'chat'（①②）| 'all'（①②③）
})
const emit = defineEmits(['close'])

const router = useRouter()
// 权限兜底：业务菜单可能未含「智能体」（自定义角色），管理员例外（谁建归谁，管理员也要自己登记）
const canManage = computed(() => Boolean(isAdminSync() || menuHasPath('/agents')))

const goNewProvider = () => {
  emit('close')
  router.push('/agents?tab=providers&action=new-provider')
}

// ② 选完即存：setUserPreference 为部分更新（缺省字段不动），不会误清其他偏好字段
const picking = ref(false)
const onPickDefault = async v => {
  picking.value = true
  try {
    await setUserPreference({ defaultModel: v || '' })
    message.success(v ? '已设为默认聊天模型' : '已清除默认聊天模型')
    await refreshSetupGuide(true)
  } catch (e) {
    message.error(e.message || '设置失败')
  } finally {
    picking.value = false
  }
}

// ModelSelect 有自己的模块级 10s 缓存：刚在供应商页登记完模型就回来时，缓存的还是空列表，
// 会把 ② 的下拉卡成「暂无可用模型」——挂载时强制刷新一次（引导只在未完成期存在，多一次请求可接受）
const msRef = ref(null)
onMounted(() => { nextTick(() => msRef.value && msRef.value.refresh()) })

// 抽屉开着时模型在别处被登记（chatModels 变化）→ 同步重刷下拉，避免新旧列表错位
watch(() => setupGuide.chatModels.length, () => { if (msRef.value) msRef.value.refresh() })
</script>

<style scoped>
.sg { display: flex; flex-direction: column; gap: 4px; }
.sg-item { display: flex; gap: 10px; padding: 10px 4px; }
.sg-badge {
  width: 20px; height: 20px; border-radius: 50%; flex: none; margin-top: 1px;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 600; color: var(--app-warn-text);
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border);
}
.sg-badge.ok { color: var(--app-ok); background: var(--app-ok-weak); border-color: transparent; }
.sg-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.sg-title { font-size: 13px; font-weight: 600; color: var(--app-text); display: flex; align-items: center; gap: 6px; }
.sg-use {
  font-size: 10px; font-weight: 500; padding: 1px 6px; border-radius: 999px;
  color: var(--app-accent); background: var(--app-accent-weak);
}
.sg-desc { font-size: 12px; color: var(--app-text3); line-height: 1.5; }
.sg-action { margin-top: 5px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.sg-btn { font-size: 12px; padding: 4px 12px; }
.sg-done-text { font-size: 12px; color: var(--app-ok); }
.sg-tip-text { font-size: 12px; color: var(--app-text3); }
.sg-warn-text { font-size: 12px; color: var(--app-warn-text); }
.sg-action :deep(.ant-select) { max-width: 300px; }
.sg-note {
  margin-top: 6px; padding-top: 10px; border-top: 1px dashed var(--app-border);
  font-size: 11px; color: var(--app-text3); line-height: 1.6;
}
/* 卡片形态（对话页欢迎区）：弱边框浅底，宽度与示例问题栅格对齐 */
.sg-card { gap: 0; padding: 6px 14px 12px; border: 1px solid var(--app-border); border-radius: var(--app-radius); background: var(--app-panel-2); text-align: left; max-width: 560px; }
</style>
