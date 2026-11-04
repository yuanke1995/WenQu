<template>
  <!-- 行尾那一项点开后的全文，长在它所属那一步的下面。思考走 markdown（它本来就是一段成文的话），
       过程独白保持纯文本（模型原话，不做二次排版）。
       always：帽体里挂在步骤详情卡内的那几段不是「点标签才展开」的一项（帽体里已无标签），
       它随详情卡一起进出，故不受 noteOpen 的折叠态约束。 -->
  <div v-show="always || noteOpen(m, note)" v-follow-bottom="noteGrowing(m, note)"
       class="tl-note-body" :class="{ bare }">
    <AnswerBody v-if="note.kind === 'think'" :content="noteText(m, note)" :viewer="viewer"
                :streaming="noteGrowing(m, note)" @citation="$emit('citation', $event)" @preview="$emit('preview', $event)" />
    <div v-else class="tl-note-plain">{{ noteText(m, note) }}</div>
  </div>
</template>

<script setup>
import AnswerBody from './AnswerBody.vue'
import { vFollowBottom } from '../chat/followBottom'
import { noteText, noteOpen, noteGrowing } from '../chat/projections'

defineProps({
  m: { type: Object, required: true },
  note: { type: Object, required: true },
  // 与 AnswerBody 同口径：PC 走共用浮层，移动壳改外抛给底部抽屉
  viewer: { type: Boolean, default: true },
  // 常显：显隐由宿主自己管（如帽体里挂在步骤详情卡内的那几段）
  always: { type: Boolean, default: false },
  // 裸排：宿主已经给了缩进与滚动容器（步骤详情卡/帽体尾行），不再套一层左边框与内滚
  bare: { type: Boolean, default: false },
})
defineEmits(['citation', 'preview'])
</script>

<style scoped>
/* 左边距对齐到工具名那一列（行首序号 + 图标 + 间距），字号与左边距都留给宿主调档 */
.tl-note-body { margin: 0 6px 4px var(--note-body-ml, 31px); padding: 2px 10px;
  border-left: 2px solid var(--app-border); color: var(--app-text3);
  font-size: var(--note-body-fs, 12.5px); line-height: 1.7; max-height: 320px; overflow-y: auto; }
.tl-note-body :deep(.md > p) { margin: 4px 0; }
.tl-note-plain { white-space: pre-wrap; word-break: break-word; }
/* 裸排：缩进、边框、内滚都由宿主容器给，避免出现两层嵌套滚动条 */
.tl-note-body.bare { margin: 0; padding: 0; border-left: 0; max-height: none; overflow: visible; }
</style>
