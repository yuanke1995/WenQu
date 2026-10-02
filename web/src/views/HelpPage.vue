<template>
  <div class="app-page help-page">
    <div class="app-page-head">
      <h3 class="app-page-title">帮助中心</h3>
      <span class="head-hint-plain">官方使用手册（随版本自动同步）；也可以在对话里直接提问，答案会从本手册检索并带引用</span>
    </div>

    <a-spin :spinning="loading">
      <div class="help-body">
        <!-- 左栏：篇目列表（编号即阅读顺序；标题关键字过滤） -->
        <aside class="help-nav">
          <div class="help-search">
            <search-outlined class="help-search-ic" />
            <input v-model="kw" class="help-search-input" placeholder="筛选篇目…" />
          </div>
          <div v-if="!filtered.length" class="help-nav-empty">没有匹配的篇目</div>
          <button v-for="d in filtered" :key="d.id" class="help-nav-item"
                  :class="{ active: activeId === d.id }" @click="select(d)">
            <span class="help-nav-title">{{ d.title }}</span>
            <span v-if="d.status === 3" class="help-nav-badge err">失败</span>
          </button>
        </aside>

        <!-- 右栏：篇目内容（markdown 渲染，与问答同管线） -->
        <article class="help-article-wrap">
          <a-alert v-if="activeDoc && activeDoc.status === 3" type="warning" show-icon
                   :message="'该篇同步失败：' + (activeDoc.failReason || '未知原因')" style="margin-bottom:12px" />
          <a-empty v-if="!loading && !docs.length"
                   description="内置手册尚未同步（通常为向量模型未配置或服务首次启动中），稍后刷新重试" style="padding:60px 0" />
          <div v-else-if="activeHtml" class="help-article md-body" v-html="activeHtml"></div>
          <a-spin v-else-if="contentLoading" style="display:block;margin:60px auto" />
        </article>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { message } from 'ant-design-vue'
import { SearchOutlined } from '@ant-design/icons-vue'
import { listManualDocs, getManualDocContent } from '../api'
import { renderMd, copyCode } from '../utils/markdown'

const docs = ref([])
const activeId = ref('')
const activeDoc = ref(null)
const activeHtml = ref('')
const loading = ref(false)
const contentLoading = ref(false)
const kw = ref('')

const filtered = computed(() => {
  const k = kw.value.trim().toLowerCase()
  if (!k) return docs.value
  return docs.value.filter(d => (d.title || '').toLowerCase().includes(k) || (d.fileName || '').toLowerCase().includes(k))
})

const load = async () => {
  loading.value = true
  try {
    const r = await listManualDocs()
    docs.value = (r && r.data) || []
    if (docs.value.length) await select(docs.value[0])
  } catch (e) {
    message.error(e.message || '手册加载失败')
  } finally {
    loading.value = false
  }
}

const select = async d => {
  activeId.value = d.id
  activeDoc.value = d
  contentLoading.value = true
  try {
    const r = await getManualDocContent(d.id)
    const data = (r && r.data) || {}
    activeDoc.value = { ...d, status: data.status, failReason: data.failReason }
    activeHtml.value = renderMd(data.content || '')
  } catch (e) {
    message.error(e.message || '篇目内容加载失败')
    activeHtml.value = ''
  } finally {
    contentLoading.value = false
  }
}

// 正文渲染出的代码块带复制按钮（与问答页同一套事件委托入口，按钮类名 .code-copy）
const onArticleClick = e => {
  const btn = e.target.closest?.('.code-copy')
  if (btn) copyCode(btn)
}

onMounted(() => {
  load()
  document.addEventListener('click', onArticleClick)
})
onBeforeUnmount(() => document.removeEventListener('click', onArticleClick))
</script>

<style scoped>
.help-page { padding: 4px 2px; }
.help-body {
  display: grid; grid-template-columns: 240px 1fr; gap: 14px; align-items: start;
}
.help-nav {
  position: sticky; top: 12px;
  display: flex; flex-direction: column; gap: 2px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  padding: 10px; max-height: calc(100vh - 120px); overflow-y: auto;
}
.help-search { position: relative; margin-bottom: 8px; }
.help-search-ic { position: absolute; left: 9px; top: 50%; transform: translateY(-50%); color: var(--app-text3); font-size: 12px; }
.help-search-input {
  width: 100%; box-sizing: border-box;
  background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 8px;
  color: var(--app-text); font-size: 12px; padding: 6px 8px 6px 26px; outline: none;
}
.help-search-input:focus { border-color: var(--app-accent); }
.help-nav-item {
  display: flex; align-items: center; gap: 6px; width: 100%; text-align: left;
  background: transparent; border: none; border-radius: 8px; cursor: pointer;
  color: var(--app-text2); font-size: 13px; padding: 7px 9px;
}
.help-nav-item:hover { background: var(--app-panel-2); color: var(--app-text); }
.help-nav-item.active { background: var(--app-accent-weak, var(--app-panel-2)); color: var(--app-accent); font-weight: 500; }
.help-nav-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.help-nav-badge.err { color: var(--app-danger); font-size: 11px; }
.help-nav-empty { color: var(--app-text3); font-size: 12px; text-align: center; padding: 20px 0; }

.help-article-wrap {
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  padding: 22px 26px; min-height: 320px;
}
/* 正文排版：markdown 渲染产物（# 降级为 h2 起），主题色全部走 token */
.help-article { color: var(--app-text); font-size: 14px; line-height: 1.85; max-width: 860px; }
.help-article :deep(h2) { font-size: 20px; margin: 18px 0 10px; color: var(--app-text); }
.help-article :deep(h3) { font-size: 16px; margin: 16px 0 8px; color: var(--app-text); }
.help-article :deep(h4) { font-size: 14px; margin: 14px 0 6px; color: var(--app-text); }
.help-article :deep(p) { margin: 8px 0; }
.help-article :deep(ul), .help-article :deep(ol) { padding-left: 22px; margin: 8px 0; }
.help-article :deep(li) { margin: 4px 0; }
.help-article :deep(strong) { color: var(--app-text); }
.help-article :deep(a) { color: var(--app-accent); }
.help-article :deep(code) {
  background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 4px;
  padding: 1px 5px; font-size: 12.5px; font-family: ui-monospace, Menlo, monospace;
}
.help-article :deep(pre) {
  background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 8px;
  padding: 12px 14px; overflow-x: auto; margin: 10px 0;
}
.help-article :deep(pre code) { background: transparent; border: none; padding: 0; }
.help-article :deep(blockquote) {
  border-left: 3px solid var(--app-accent); background: var(--app-panel-2);
  margin: 10px 0; padding: 8px 14px; border-radius: 0 8px 8px 0; color: var(--app-text2);
}
.help-article :deep(table) { border-collapse: collapse; margin: 10px 0; width: 100%; font-size: 13px; }
.help-article :deep(th), .help-article :deep(td) {
  border: 1px solid var(--app-border); padding: 7px 10px; text-align: left;
}
.help-article :deep(th) { background: var(--app-panel-2); color: var(--app-text); }

/* 窄屏：导航收为顶部横排 */
@media (max-width: 768px) {
  .help-body { grid-template-columns: 1fr; }
  .help-nav { position: static; max-height: 200px; }
}
</style>
