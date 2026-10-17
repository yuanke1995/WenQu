<script setup>
/**
 * 本轮降级提示条（主对话页与公开分享页共用）。
 *
 * <p>「答了，但打了折扣」的原因都走这一条：模型回退、工作流不收图片/附件、图片被剔除、
 * 无命中、改写失败……此前分享页把后端已经下发的 {@code degradations} 整个丢掉，
 * 于是游客看到一句答非所问、发布者收到投诉，双方都看不出真正原因。
 *
 * <p>文案一律由后端给（{@code {code,msg}}），这里只负责画——同一句话不该在两个页面各写一遍。
 * 注意：降级事件是否随 done 下发受 {@code chat.retrievalDebugEnabled} 开关控制（默认关，
 * 属内部排障信息），所以本组件在默认部署下多数时候不显示，这是既有的产品口径，不是漏渲染。
 */
import { ExclamationCircleOutlined } from '@ant-design/icons-vue'

defineProps({
  /** 降级事件数组：[{code,msg}] */
  items: { type: Array, default: () => [] },
  /** 单条警示文案（warnMsg）：与 degradations 同一外观，二选一出现 */
  warn: { type: String, default: '' }
})
</script>

<template>
  <div v-if="(items && items.length) || warn" class="degradation-bar">
    <template v-if="items && items.length">
      <exclamation-circle-outlined style="margin-right:6px" />
      <span v-for="(d, di) in items" :key="di" class="degradation-item">{{ d.msg }}</span>
    </template>
    <template v-else>{{ warn }}</template>
  </div>
</template>

<style scoped>
.degradation-bar {
  margin-top: 8px; padding: 6px 10px; border-radius: 6px;
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); color: var(--app-warn-text);
  font-size: 12px; line-height: 1.6; display: flex; flex-wrap: wrap; gap: 4px 12px;
}
.degradation-item { display: inline-block; }
</style>
