<template>
  <!-- 窄屏示例问题：竖向排列，一行一张卡。
       PC 态是 2×2 网格；窄屏竖排四张，字号保持可读（PC 卡在 375px 上会被压到 11px）。 -->
  <div class="m-samples">
    <button v-for="(q, i) in questions" :key="i" class="ms-card" type="button" @click="$emit('ask', q.text)">
      <span class="ms-text">
        <span v-if="q.label" class="ms-label">{{ q.label }}</span>
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
/* 竖向堆叠：四张卡一行一张，手机上一屏内可全部看到 */
.m-samples {
  display: flex; flex-direction: column; gap: 10px;
  width: 100%; max-width: 560px; margin: 0 auto; box-sizing: border-box;
  padding: 2px 12px 8px;      /* 横向内边距与欢迎区节奏对齐；使用方列表已加内边距时会取消 */
}
.ms-card {
  display: flex; align-items: flex-start; gap: 10px; text-align: left;
  padding: 14px; cursor: pointer; touch-action: manipulation;
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: var(--app-radius); color: var(--app-text);
}
.ms-card:active { border-color: var(--app-accent); background: var(--app-accent-weak); }
.ms-text { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
/* 标签与问题都可读：PC 态在 375px 上被压到 11px，这里恢复 12/14px */
.ms-label { font-size: 12px; color: var(--app-text3); }
.ms-q { font-size: 14px; line-height: 1.5; color: var(--app-text); }
</style>
