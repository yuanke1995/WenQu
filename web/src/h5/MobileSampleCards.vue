<template>
  <!-- 窄屏示例问题：横向滑动卡片。
       PC 态是 2×2 网格（4 张卡竖排占满一屏，字被压到 11px）；窄屏改为「一张一张横滑」，
       卡片保持可读的宽高，靠惯性感浏览。这是主流产品的空态引导做法。 -->
  <div class="m-samples">
    <button v-for="q in questions" :key="q.text" class="ms-card" type="button" @click="$emit('ask', q.text)">
      <span class="ms-ic">{{ q.icon }}</span>
      <span class="ms-text">
        <span class="ms-label">{{ q.label }}</span>
        <span class="ms-q">{{ q.text }}</span>
      </span>
    </button>
  </div>
</template>

<script setup>
defineProps({
  questions: { type: Array, required: true }
})
defineEmits(['ask'])
</script>

<style scoped>
/* scroll-snap：松手后卡片停在整位，不会停在半张（半张会让人以为还能继续滑但滑不动） */
.m-samples {
  display: flex; gap: 10px; overflow-x: auto;
  scroll-snap-type: x mandatory;
  -webkit-overflow-scrolling: touch;
  padding: 2px 12px 8px;      /* 左侧留出与标题的视觉对齐，右侧留出可滑动提示 */
  scrollbar-width: none;
}
.m-samples::-webkit-scrollbar { display: none; }  /* 隐藏滚动条：滑动是自明的，不需要条 */
.ms-card {
  flex: none; width: 76%; max-width: 300px;
  scroll-snap-align: start;
  display: flex; align-items: flex-start; gap: 10px; text-align: left;
  padding: 14px; cursor: pointer; touch-action: manipulation;
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: var(--app-radius); color: var(--app-text);
}
.ms-card:active { border-color: var(--app-accent); background: var(--app-accent-weak); }
.ms-ic { font-size: 20px; flex: none; line-height: 1.3; }
.ms-text { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
/* 标签与问题都可读：PC 态在 375px 上被压到 11px，这里恢复 12/14px */
.ms-label { font-size: 12px; color: var(--app-text3); }
.ms-q { font-size: 14px; line-height: 1.5; color: var(--app-text); }
</style>
