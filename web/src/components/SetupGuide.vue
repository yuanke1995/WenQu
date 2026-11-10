<template>
  <!-- 新手配置引导清单：卡片（对话页欢迎区，scope=chat 只列①②）/ 抽屉（侧栏入口，scope=all 列①②③）。
       未加载成功（loaded=false）一律不渲染——宁可不引导，不误报。
       必配（聊天两步）计入进度；向量模型属于可选增强，单独分组、不计入「还差 N 项」。 -->
  <div v-if="visible" class="sg" :class="'sg-' + variant" role="region" aria-label="配置引导">
    <!-- 进度：与侧栏「N / 2」、欢迎卡共用 chatDoneCount -->
    <div class="sg-prog" role="status" aria-live="polite">
      <span>{{ variant === 'card' ? '开始对话需要' : '聊天配置' }}</span>
      <span class="sg-prog-num" :aria-label="`已完成 ${chatDoneCount} 步，共 2 步`">{{ chatDoneCount }} / 2<template v-if="variant === 'plain'"> 已完成</template></span>
    </div>
    <div v-if="variant === 'plain'" class="sg-bar" aria-hidden="true">
      <i :class="{ ok: chatReady }"></i>
      <i :class="{ ok: defaultReady }"></i>
    </div>

    <!-- 抽屉里给必配项一个分组标题，和下面「可选增强 · 知识库」形成层级 -->
    <div v-if="variant === 'plain'" class="sg-group sg-group-first">开始对话需要</div>

    <!-- ① 添加聊天模型：完成后折叠成一行，保留「管理」 -->
    <div v-if="chatReady" class="sg-item done">
      <span class="sg-badge ok" aria-hidden="true">✓</span>
      <div class="sg-main">
        <div class="sg-title"><span class="sr-only">已完成：</span>添加聊天模型</div>
        <div class="sg-done-line">
          <span>{{ chatSummary }}</span>
          <button class="app-link-btn" type="button" @click="goManage">管理</button>
        </div>
      </div>
    </div>
    <div v-else class="sg-item">
      <span class="sg-badge">1</span>
      <div class="sg-main">
        <div class="sg-title">添加聊天模型</div>
        <div class="sg-desc">{{ step1Desc }}</div>
        <div class="sg-actions">
          <button v-if="canManage" class="app-btn sg-btn" type="button" @click="goAddChat">添加聊天模型</button>
          <span v-else class="sg-warn-text">请联系管理员开通「智能体」菜单权限</span>
        </div>
      </div>
    </div>

    <!-- ② 选择默认聊天模型：未解锁时渲染禁用占位（不跳版），完成后折叠成一行 -->
    <div v-if="defaultReady && !editingDefault" class="sg-item done">
      <span class="sg-badge ok" aria-hidden="true">✓</span>
      <div class="sg-main">
        <div class="sg-title"><span class="sr-only">已完成：</span>选择默认聊天模型</div>
        <div class="sg-done-line">
          <span>{{ defaultLabel }}</span>
          <button ref="changeBtn" class="app-link-btn" type="button" @click="startEditDefault">更换</button>
        </div>
      </div>
    </div>
    <div v-else class="sg-item" :class="{ locked: !chatReady }">
      <span class="sg-badge" :class="{ lock: !chatReady }">2</span>
      <div class="sg-main">
        <div class="sg-title">选择默认聊天模型</div>
        <div class="sg-desc">智能体没有单独指定模型时，默认使用这个</div>
        <div class="sg-actions">
          <ModelSelect v-if="chatReady" ref="msRef" :model-value="setupGuide.defaultModel" type="chat"
                       width="100%" :disabled="picking" placeholder="选择默认聊天模型" @change="onPickDefault" />
          <div v-else class="sg-select-disabled" aria-disabled="true">添加聊天模型后即可选择</div>
        </div>
        <!-- 只有一个可用模型时直接给推荐，避免用户在多选里猜；多个模型不擅自推荐 -->
        <div v-if="chatReady && !defaultReady && recommendModel" class="sg-recommend">
          <span>推荐：{{ recommendModel.providerName }} · {{ recommendModel.displayName }}</span>
          <button class="app-link-btn" type="button" :disabled="picking" @click="pickRecommended">设为默认</button>
        </div>
        <div v-if="defaultReady" class="sg-done-text">已设为默认</div>
      </div>
    </div>

    <!-- ③ 添加向量模型：只影响知识库，可选、低强调（仅抽屉清单显示） -->
    <template v-if="scope === 'all'">
      <div class="sg-group">可选增强 · 知识库</div>
      <div v-if="embeddingReady" class="sg-item done">
        <span class="sg-badge ok" aria-hidden="true">✓</span>
        <div class="sg-main">
          <div class="sg-title"><span class="sr-only">已完成：</span>添加向量模型<span class="sg-use">知识库</span></div>
          <div class="sg-done-line">
            <span>已登记 {{ setupGuide.embeddingCount }} 个向量模型</span>
            <button class="app-link-btn" type="button" @click="goManage">管理</button>
          </div>
        </div>
      </div>
      <div v-else class="sg-item optional">
        <span class="sg-badge lock">3</span>
        <div class="sg-main">
          <div class="sg-title">添加向量模型<span class="sg-use">知识库</span></div>
          <div class="sg-desc">在已有供应商下登记一个向量（embedding）模型，即可用文档提问</div>
          <div class="sg-actions">
            <button v-if="canManage" class="app-btn sg-btn ghost" type="button" @click="goAddEmbedding">添加向量模型</button>
            <span v-else class="sg-warn-text">请联系管理员开通「智能体」菜单权限</span>
          </div>
        </div>
      </div>
    </template>

    <!-- 底部：卡片只留「稍后再说」；抽屉补文档入口 -->
    <div class="sg-foot">
      <template v-if="variant === 'card'">
        <span class="sg-foot-hint">配置可以随时在「配置引导」里继续</span>
        <button class="app-link-btn muted" type="button" @click="dismiss">稍后再说</button>
      </template>
      <template v-else>
        <button class="app-link-btn" type="button" @click="goHelp">查看配置文档</button>
        <button class="app-link-btn muted" type="button" @click="dismiss">稍后再说</button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { setUserPreference } from '../api.js'
import { canManageProviders, chatReady, chatDoneCount, defaultReady, embeddingReady,
         guideSnoozed, snoozeGuide, refreshSetupGuide, setupGuide } from '../utils/setupGuide.js'
import ModelSelect from './ModelSelect.vue'

/**
 * 配置引导清单（复用组件）：聊天两步为必配并计入进度，向量模型为可选增强。
 * variant=card：对话页欢迎区卡片（scope=chat，只列①②）；variant=plain：侧栏抽屉清单（scope=all，含③）。
 * emit close：跳转模型供应商页且由宿主关闭抽屉。
 */
const props = defineProps({
  variant: { type: String, default: 'plain' },   // 'card' | 'plain'
  scope: { type: String, default: 'all' }        // 'chat'（①②）| 'all'（①②③）
})
const emit = defineEmits(['close'])

const router = useRouter()
const canManage = canManageProviders

// 「稍后再说」：24 小时内收起欢迎卡（状态在 setupGuide.js，欢迎区的标题也一起隐藏）；
// 侧栏入口保留，用户仍可主动进入
const visible = computed(() => setupGuide.loaded && !(props.variant === 'card' && guideSnoozed.value))
const dismiss = () => {
  snoozeGuide()
  emit('close')
}

// ---------- 文案：空态按「有没有供应商」分支，避免所有情况都说同一句 ----------
const step1Desc = computed(() => {
  if (!setupGuide.providersKnown) return '连接一个供应商（DeepSeek、通义、Ollama…），并登记至少一个聊天模型'
  if (!setupGuide.providers.length) return '还没有可用的供应商：先新建一个模型网关，再登记聊天模型'
  return `已有 ${setupGuide.providers.length} 个供应商，直接为其中一个登记「聊天」模型`
})
const chatSummary = computed(() => {
  const names = [...new Set(setupGuide.chatModels.map(m => m.providerName).filter(Boolean))]
  return `${names.join('、') || '已登记'} · ${setupGuide.chatModels.length} 个模型`
})
const defaultLabel = computed(() => {
  const m = setupGuide.chatModels.find(x => x.ref === setupGuide.defaultModel)
  return m ? `${m.providerName} · ${m.displayName}` : '已设为默认'
})
// 只有一个可用模型时给推荐并支持一键设为默认；多个模型不擅自替用户做选择
const recommendModel = computed(() =>
  setupGuide.chatModels.length === 1 ? setupGuide.chatModels[0] : null)
const pickRecommended = () => { if (recommendModel.value) onPickDefault(recommendModel.value.ref) }

// ---------- 跳转：深链带上类型与来源，落地页直接打开对应表单并可返回 ----------
const leave = to => {
  emit('close')
  router.push(to)
}
const goAddChat = () => leave('/agents?tab=providers&action=new-provider&type=chat&from=setup')
const goAddEmbedding = () => leave('/agents?tab=providers&action=add-model&type=embedding&from=setup')
const goManage = () => leave('/agents?tab=providers')
const goHelp = () => leave('/help')

// ② 选完即存：setUserPreference 为部分更新（缺省字段不动），不会误清其他偏好字段
const picking = ref(false)
const editingDefault = ref(false)
const changeBtn = ref(null)
const startEditDefault = () => {
  editingDefault.value = true
  nextTick(() => msRef.value && msRef.value.refresh())
}
const onPickDefault = async v => {
  picking.value = true
  try {
    await setUserPreference({ defaultModel: v || '' })
    message.success(v ? '已设为默认聊天模型' : '已清除默认聊天模型')
    await refreshSetupGuide(true)
    editingDefault.value = false
    // 选完后选择器会折叠成一行：焦点跟着落到「更换」，键盘用户不会丢失位置
    nextTick(() => changeBtn.value && changeBtn.value.focus())
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
.sg { display: flex; flex-direction: column; gap: 2px; }
/* 卡片形态（对话页欢迎区）：弱边框浅底，宽度与示例问题栅格对齐 */
.sg-card {
  gap: 0; padding: 0; border: 1px solid var(--app-border); border-radius: var(--app-radius);
  background: var(--app-panel-2); text-align: left; max-width: 604px;
}
.sg-prog {
  display: flex; align-items: center; justify-content: space-between;
  padding: 9px 14px; font-size: 11px; font-weight: 600; letter-spacing: .04em;
  color: var(--app-text3);
}
.sg-card .sg-prog { border-bottom: 1px dashed var(--app-border); }
.sg-prog-num { font-size: 11px; letter-spacing: 0; font-variant-numeric: tabular-nums; }
.sg-bar { display: flex; gap: 4px; padding: 0 2px 10px; }
.sg-bar i { height: 3px; flex: 1; border-radius: 2px; background: #e9ecf0; }
.sg-bar i.ok { background: var(--app-ok); }
.sg-item { display: flex; gap: 10px; padding: 12px 14px; }
.sg-card .sg-item + .sg-item { border-top: 1px solid var(--app-border); }
.sg-card .sg-group + .sg-item { border-top: 1px solid var(--app-border); }
.sg-plain .sg-item { padding: 10px 2px; }
.sg-item.done { padding: 9px 14px; }
.sg-plain .sg-item.done { padding: 9px 2px; }
.sg-plain .sg-item + .sg-item { border-top: 1px dashed var(--app-border); }
.sg-item.locked { background: var(--app-panel-2); }
.sg-card .sg-item.locked { background: var(--app-panel-2); }
.sg-badge {
  width: 20px; height: 20px; border-radius: 50%; flex: none; margin-top: 1px;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 600; color: var(--app-warn-text);
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border);
}
.sg-badge.lock { color: var(--app-text3); background: #f1f3f5; border-color: var(--app-border); }
.sg-badge.ok { color: var(--app-ok); background: var(--app-ok-weak); border-color: transparent; }
.sg-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.sg-title { font-size: 13px; font-weight: 600; color: var(--app-text); display: flex; align-items: center; gap: 6px; }
.sg-use {
  font-size: 10px; font-weight: 500; padding: 1px 6px; border-radius: 999px;
  color: var(--app-text3); background: #f1f3f5;
}
.sg-desc { font-size: 12px; color: var(--app-text3); line-height: 1.5; }
.sg-actions { margin-top: 6px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.sg-actions :deep(.ant-select) { max-width: 360px; }
.sg-btn { font-size: 12px; padding: 4px 12px; }
.sg-done-line {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: 12px; color: var(--app-text3);
}
.sg-done-text { font-size: 12px; color: var(--app-ok); margin-top: 4px; }
.sg-warn-text { font-size: 12px; color: var(--app-warn-text); }
.sg-select-disabled {
  height: 32px; display: flex; align-items: center; padding: 0 10px;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  font-size: 12.5px; color: var(--app-text3); width: 100%; max-width: 360px;
}
.sg-recommend {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-top: 7px; font-size: 11.5px; color: var(--app-text3);
}
.sg-group {
  padding: 10px 14px 2px; font-size: 10.5px; font-weight: 600; letter-spacing: .06em;
  color: var(--app-text3);
}
.sg-plain .sg-group { padding: 10px 2px 2px; }
.sg-group-first { padding-top: 2px; }
.sg-card .sg-group { border-top: 1px dashed var(--app-border); margin-top: 2px; }
.sg-item.optional { background: transparent; }
.sg-item.optional .sg-title { color: var(--app-text2); font-weight: 500; }
.sg-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap;
  padding: 9px 14px; border-top: 1px dashed var(--app-border);
  font-size: 11.5px; color: var(--app-text3);
}
.sg-plain .sg-foot { padding: 12px 2px 0; }
.sg-foot-hint { font-size: 11.5px; }
.app-link-btn.muted { color: var(--app-text3); }
</style>
