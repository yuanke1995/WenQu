<template>
  <!-- 步骤行尾那一项＝这一轮的思考/独白（方案①：原来它各占一行标题，展开态一半高度是它买单的）。
       用 span 不用 button：它长在别人的按钮里，button 套 button 不是合法 HTML。
       字号走 --note-tag-fs：PC 与移动壳差一档，但样式只这一份，不各写一遍。 -->
  <span class="tl-note-tag" :class="{ grow: noteGrowing(m, note) }"
        role="button" @click.stop="toggleNote(m, note)">{{ noteTag(m, note) }}</span>
</template>

<script setup>
import { noteTag, noteGrowing, toggleNote } from '../chat/projections'

defineProps({ m: { type: Object, required: true }, note: { type: Object, required: true } })
</script>

<style scoped>
.tl-note-tag { flex: none; font-size: var(--note-tag-fs, 11px); color: var(--app-text3);
  white-space: nowrap; }
.tl-note-tag:hover { color: var(--app-accent); }
/* 正在长的那一段：与思考行标题同一套呼吸（一行小标题里不塞转圈图标，既吵又跟正文重复报进度） */
.tl-note-tag.grow { animation: note-grow 1.6s ease-in-out infinite; }
@keyframes note-grow { 0%, 100% { color: var(--app-text2); } 50% { color: var(--app-accent); } }
@media (prefers-reduced-motion: reduce) {
  .tl-note-tag.grow { animation: none; color: var(--app-accent); }
}
</style>
