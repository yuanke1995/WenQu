<template>
  <div class="sh-page">
    <header class="sh-head">
      <span class="sh-badge">对话分享</span>
      <h1 class="sh-title">{{ info.title || '未命名对话' }}</h1>
      <div class="sh-sub">
        只读内容，不能继续提问<span v-if="info.sharedAt"> · 分享于 {{ fmtTime(info.sharedAt) }}</span>
      </div>
    </header>

    <div v-if="loading" class="sh-state">加载中…</div>
    <div v-else-if="error" class="sh-state err">
      <div class="sh-err-t">{{ error }}</div>
      <div class="sh-err-d">链接可能已被停止分享或已失效，请联系分享给你的人。</div>
    </div>
    <div v-else-if="!info.messages.length" class="sh-state">这段对话还没有内容。</div>

    <div v-else class="sh-list">
      <div v-for="(m, i) in info.messages" :key="i" class="sh-row" :class="m.role">
        <div v-if="m.role === 'user'" class="sh-bubble user">{{ m.content }}</div>
        <div v-else class="sh-bubble ai">
          <AnswerBody :content="m.content" :images="m.images" :citations="false" />
          <!-- 产物：模型为本轮问题生成的文件，本就是这段对话的一部分，跟着分享出去。
               下载地址不来自响应里的 url（那是被 1 小时签名保护的地址，分享链接却能挂很久），
               而是按 token 现场换——见下方 artifactHref。 -->
          <div v-if="m.artifacts && m.artifacts.length" class="sh-art-list">
            <a v-for="(a, ai) in m.artifacts" :key="ai" class="sh-art"
               :href="artifactHref(a)" :download="a.filename" target="_blank" rel="noopener"
               :title="'下载 ' + a.filename">
              <file-text-outlined class="sh-art-icon" />
              <span class="sh-art-name">{{ a.filename }}</span>
              <span v-if="a.description" class="sh-art-desc">{{ a.description }}</span>
              <span v-if="fmtSize(a.size)" class="sh-art-size">{{ fmtSize(a.size) }}</span>
              <download-outlined class="sh-art-dl" />
            </a>
          </div>
          <!-- 引用来源只给文档名与章节：知识块全文不外发（链接一旦外流不至于把库内容带出去） -->
          <div v-if="m.sources && m.sources.length" class="sh-src">
            <div class="sh-src-t">引用来源</div>
            <div v-for="(s, si) in m.sources" :key="si" class="sh-src-item">
              <span class="sh-src-ref">[{{ s.ref }}]</span>
              <!-- 外部来源（origin=WEB/MCP，与对话页 externalOrigin 同口径）：没有库内文档名，
                   显示站点名——网页本就公开，不含库内容；MCP 无站点名，给「MCP 来源」 -->
              <span class="sh-src-name">
                <template v-if="external(s)">{{ s.siteName || (s.origin === 'MCP' ? 'MCP 来源' : '联网来源') }}<template v-if="s.title"> § {{ s.title }}</template></template>
                <template v-else>{{ s.fileName || '来源文档不可用' }}<template v-if="s.title"> § {{ s.title }}</template></template>
              </span>
              <!-- 原网页入口：只有外部来源有地址，库内知识块全文不外发故不给链接 -->
              <a v-if="external(s) && s.url" class="sh-src-link" :href="s.url" target="_blank" rel="noopener">打开原网页</a>
            </div>
          </div>
        </div>
      </div>
    </div>

    <footer class="sh-foot">
      <span>内容由 AI 生成，请注意甄别</span>
      <a href="/" class="sh-brand">问渠 WenQu</a>
    </footer>

    <!-- 正文浮层宿主：本页只用得到图片灯箱（点正文配图看大图）。
         角标已用 :citations="false" 关掉，所以来源弹窗与悬浮卡不会被触发。 -->
    <AnswerViewerHost />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getSharedSession } from '../api'
// 正文与图片灯箱与主聊天页同源（mermaid 补画也已下沉到 AnswerBody，不再由本页整容器扫一遍）
import AnswerBody from '../components/AnswerBody.vue'
import AnswerViewerHost from '../components/AnswerViewerHost.vue'

const route = useRoute()
const loading = ref(true)
const error = ref('')
const info = ref({ title: '', messages: [], sharedAt: null })

/** 外部来源（联网 WEB / MCP）：没有库内文档名，展示名用站点名——与对话页 externalOrigin 同口径。
 *  漏判会让联网来源落进「来源文档不可用」分支（fileName 对它们本就为空）。 */
const external = s => s?.origin === 'WEB' || s?.origin === 'MCP'

/**
 * 产物下载地址：按会话内序号拼下载端点，**不复用响应里的 url**。
 * 产物文件由 /ai/artifacts/** 静态映射 + expire+sig 拦截器保护，签名默认只签 1 小时，
 * 而分享链接能挂很久——直接把签名 URL 写进页面会得到"当天能下、隔天全 401"的死链。
 * 这里让后端凭 token 现场返回，链接有效期与分享有效期一致。
 * 走 VITE_API_BASE 前缀：分享页与产物下载同域，但开发态经 vite /proxy 转发，两种环境都要对。
 */
const BASE = import.meta.env.VITE_API_BASE || '/proxy/api/ai'
const artifactHref = a => `${BASE}/share/session/${encodeURIComponent(String(route.params.token || ''))}/artifact?seq=${a.seq}`

/** 字节数 → 可读体积（分享页只做展示，够用即可；未知大小不显示） */
const fmtSize = n => {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return ''
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB'
  return (v / 1024 / 1024).toFixed(1) + ' MB'
}

const fmtTime = ts => {
  if (!ts) return ''
  const d = new Date(ts)
  if (isNaN(d.getTime())) return String(ts).slice(0, 10)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

onMounted(async () => {
  try {
    const r = await getSharedSession(String(route.params.token || ''))
    info.value = r?.data || { title: '', messages: [] }
  } catch (e) {
    error.value = e.message || '分享链接无效或已停止访问'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.sh-page {
  min-height: 100vh; background: var(--app-bg); color: var(--app-text);
  display: flex; flex-direction: column; align-items: center; padding: 32px 16px 0;
}
.sh-head { width: 100%; max-width: 820px; margin-bottom: 18px; }
.sh-badge {
  display: inline-block; font-size: 11px; padding: 2px 8px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent); margin-bottom: 8px;
}
.sh-title { font-size: 20px; font-weight: 600; margin: 0 0 6px; line-height: 1.4; }
.sh-sub { font-size: 12px; color: var(--app-text3); }
.sh-state { width: 100%; max-width: 820px; padding: 40px 0; text-align: center; color: var(--app-text3); font-size: 13px; }
.sh-state.err { color: var(--app-text2); }
.sh-err-t { font-size: 15px; color: var(--app-danger-text); margin-bottom: 6px; }
.sh-err-d { font-size: 12px; color: var(--app-text3); }
.sh-list { width: 100%; max-width: 820px; display: flex; flex-direction: column; gap: 18px; }
.sh-row { display: flex; }
.sh-row.user { justify-content: flex-end; }
.sh-bubble { max-width: 88%; font-size: 14px; line-height: 1.75; }
.sh-bubble.user {
  background: var(--app-accent); color: #fff; padding: 9px 13px; border-radius: 12px 12px 2px 12px;
  white-space: pre-wrap; word-break: break-word;
}
.sh-bubble.ai {
  background: var(--app-panel); border: 1px solid var(--app-border);
  padding: 12px 14px; border-radius: 12px 12px 12px 2px; width: 100%;
}
/* 产物卡：与对话页 .artifact-item 同一形态（图标 + 文件名 + 说明 + 下载箭头），
   但用 --app-* token 以适配分享页的独立配色；文件名单行省略，窄屏不撑破 */
.sh-art-list { margin-top: 12px; display: flex; flex-direction: column; gap: 6px; }
.sh-art {
  display: flex; align-items: center; gap: 7px;
  padding: 7px 9px; border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-bg); color: var(--app-text2);
  text-decoration: none; font-size: 12px; min-width: 0;
}
.sh-art:hover { border-color: var(--app-accent); background: var(--app-accent-weak); }
.sh-art-icon { color: var(--app-accent); flex: none; }
.sh-art-name { font-weight: 500; color: var(--app-accent); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sh-art-desc { color: var(--app-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.sh-art-size { color: var(--app-text3); font-size: 11px; flex: none; margin-left: auto; }
.sh-art-dl { color: var(--app-text3); flex: none; }

.sh-src { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--app-border); }
.sh-src-t { font-size: 11px; color: var(--app-text3); margin-bottom: 6px; }
.sh-src-item { display: flex; gap: 6px; align-items: baseline; font-size: 12px; color: var(--app-text2); padding: 2px 0; }
.sh-src-ref { flex: none; color: var(--app-accent); }
.sh-src-name { min-width: 0; word-break: break-word; }
.sh-src-link { flex: none; color: var(--app-accent); text-decoration: none; font-size: 12px; }
.sh-src-link:hover { text-decoration: underline; }
.sh-foot {
  width: 100%; max-width: 820px; margin: 28px 0 20px; padding-top: 14px;
  border-top: 1px solid var(--app-border);
  display: flex; align-items: center; gap: 12px;
  font-size: 12px; color: var(--app-text3);
}
.sh-brand { margin-left: auto; color: var(--app-accent); text-decoration: none; }

/* ==================== 移动端窄屏（h5）====================
   只读会话分享页（/shared/:token），分享链接常在手机/微信里打开。
   布局是 max-width 居中栏，天然不破版；这里只收内边距、放大触控热区、让开安全区。 */
@media (max-width: 768px) {
  .sh-page { padding: calc(16px + var(--sat, 0px)) 12px 0; }
  .sh-head { margin-bottom: 14px; }
  .sh-title { font-size: 17px; }
  .sh-list { gap: 12px; }
  .sh-bubble { max-width: 92%; }
  .sh-bubble.ai { padding: 10px 12px; }
  /* 产物行是分享页的主要交互目标，放到 44px 触摸热区；文件名仍单行省略不换行 */
  .sh-art { padding: 11px 10px; }
  .sh-art-desc { display: none; }
  /* 页脚链接是主要交互，44px 触摸热区 */
  .sh-foot { margin: 20px 0 16px; padding-top: 12px; }
  .sh-brand { display: inline-flex; align-items: center; min-height: 44px; }
}
</style>
