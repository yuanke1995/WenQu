<template>
  <!-- 消息流里的居中分隔记录：左右细线夹一行弱文本，独占一行、不抢正文的视线。
       「模型已切换」「已压缩上下文」共用这一个组件——它们说的是同一类事：对话在这一点上
       后台发生了什么，值得留痕，但不该长得像一条消息或一个警告。 -->
  <div class="msg-divider" :class="{ touch }">
    <span class="mdv-line"></span>
    <span class="mdv-text"><slot /></span>
    <span class="mdv-line"></span>
  </div>
</template>

<script setup>
defineProps({
  /** 触屏壳：字号放大一档、行内动作热区加大 */
  touch: { type: Boolean, default: false }
})
</script>

<style scoped>
/* 左右内缩由宿主用 --mdv-inset 传入（PC 收到正文列宽，H5 满宽即不传）：
   否则分隔线横贯整个消息区、比正文宽出一倍 */
/* 上下留白由宿主给（组件不设 margin）：这条线在 PC 正文列、H5 整宽、流尾各处的间距都不一样，
   写在组件里就要靠选择器权重去压宿主样式，不如交回调用方 */
.msg-divider {
  flex-basis: 100%; display: flex; align-items: center; gap: 10px;
  padding: 0 var(--mdv-inset, 0px);
  color: var(--app-text3); font-size: 12px;
}
.msg-divider.touch { font-size: 13px; }
.mdv-line { flex: 1; border-top: 1px solid var(--app-border); }
.mdv-text { display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; flex: none; }
.mdv-text :deep(.anticon) { font-size: 12px; opacity: .75; }
.msg-divider.touch .mdv-text :deep(button) { min-height: 30px; padding: 0 8px; font-size: 13px; }
</style>
