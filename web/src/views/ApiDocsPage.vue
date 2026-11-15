<!--
  对接文档（在线）：嵌入「对外集成文档站」的同一份构建产物，不再维护第二份内容。

  为什么改成 iframe：B2 的口径是「单一真源」——正文在 docs-site/guides/*.md，端点表来自
  contract/openapi.v1.json，构建期合并成 web/public/docs/index.html。以前这页手写了一份几乎等价的
  长文档，改一处要同步两处，必然漂移。现在这里只剩一个外壳：标题、外链、iframe。

  基址通过 ?base= 传给文档站：本地起 web(5800) 而后端在 8090 时，文档里的 curl 必须是真后端地址，
  不能固化成当前 origin（publicUrl 与 ApiKeysPanel 同源，见 utils/publicUrl.js）。
-->
<template>
  <div class="app-page docs-embed-page">
    <div class="app-page-head">
      <h3 class="app-page-title">对接文档</h3>
      <span class="head-hint-plain">三层接入 / 认证与 scope / run 事件契约 / webhook 与续传</span>
      <a class="app-link-btn docs-open" :href="docsHref" target="_blank" rel="noopener">新窗口打开</a>
    </div>
    <div class="app-page-body docs-embed-body">
      <iframe class="docs-frame" :src="docsSrc" title="问渠对外集成文档"></iframe>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { publicUrl } from '../utils/publicUrl'

// 静态产物随 web 构建落到 dist/docs/，所以用 BASE_URL 而不是写死根路径
const docsHref = import.meta.env.BASE_URL.replace(/\/$/, '') + '/docs/index.html'
// base 只传 origin + /ai（文档站自己会拼接口路径），避免把 publicUrl 的路径拼接规则复制一份
const docsSrc = computed(() => `${docsHref}?base=${encodeURIComponent(publicUrl('/ai'))}`)
</script>

<style scoped>
/* 文档站自带侧栏与滚动，这里不能再套一层卡片内边距：整页只留一个贴边画布 */
.docs-embed-page { display: flex; flex-direction: column; height: 100%; }
.docs-embed-body { flex: 1; min-height: 0; padding: 0; }
.docs-frame {
  width: 100%; height: 100%; min-height: calc(100vh - 132px);
  border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel);
}
.docs-open { margin-left: auto; font-size: 12.5px; }
</style>
