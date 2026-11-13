<script setup>
/**
 * 待发图片/附件条（顶替态专用）。
 *
 * 计划卡/提问卡会把整个输入框换掉（见 chat/ChatInput.vue 的 replaceBox），而图片与附件的 chip
 * 是画在 Composer 正文流里的——那一步 Composer 已被卸载，chip 跟着消失。可拖入与全局粘贴仍在收，
 * 收到的图既看不见、也不属于那张卡：用户会读成「没传上去」，然后一遍遍再拖。
 *
 * 所以顶替态把同一份 pendingImages/pendingFiles 按 chip 的观感画在卡片内部：
 * 看得见、删得掉，卡处理完回到输入框时仍是同一批（数组是唯一事实源，这里只读不写）。
 */
// icons-vue 不在 AntDesignVueResolver 的解析表里，必须显式导入
import { FileTextOutlined } from '@ant-design/icons-vue'

defineProps({
  images: { type: Array, default: () => [] },
  files: { type: Array, default: () => [] },
  fmtSize: { type: Function, default: () => '' }
})
const emit = defineEmits(['remove-image', 'remove-file', 'preview'])
</script>

<template>
  <div class="pa-row">
    <span v-for="(p, i) in images" :key="p.chipKey || 'i' + i" class="pa-chip pa-img"
          title="待发送图片（点击看大图）">
      <img :src="p.dataUrl" alt="待发送图片" @click="emit('preview', i)" />
      <span class="pa-x" title="移除该图片" @click.stop="emit('remove-image', i)">×</span>
    </span>
    <span v-for="(f, i) in files" :key="f.chipKey || 'f' + i" class="pa-chip pa-file"
          :class="{ err: !!f.error }" :title="f.error || f.name">
      <file-text-outlined class="pa-ic" />
      <span class="pa-name">{{ f.name }}</span>
      <span v-if="f.uploading" class="pa-sub">上传中…</span>
      <span v-else-if="f.error" class="pa-sub">上传失败</span>
      <span v-else class="pa-sub">{{ fmtSize(f.size) }}</span>
      <span class="pa-x" title="移除该附件" @click.stop="emit('remove-file', i)">×</span>
    </span>
  </div>
</template>

<style scoped>
.pa-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.pa-chip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 240px;
  padding: 1px 5px 1px 2px; border-radius: 7px; font-size: 12px; line-height: 18px;
  background: var(--app-panel); border: 1px solid var(--app-border); color: var(--app-text);
}
.pa-img { padding: 2px; }
.pa-img img {
  display: block; width: 24px; height: 24px; object-fit: cover; border-radius: 5px;
  border: 1px solid var(--app-border); cursor: zoom-in;
}
.pa-ic { flex: none; font-size: 13px; color: var(--app-accent); }
.pa-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pa-sub { flex: none; font-size: 11px; color: var(--app-text3); }
.pa-file.err { background: var(--app-danger-weak); border-color: var(--app-danger-border); color: var(--app-danger-text); }
.pa-x { flex: none; cursor: pointer; font-size: 13px; line-height: 1; opacity: .6; padding: 0 1px; }
.pa-x:hover { opacity: 1; color: var(--app-danger); }
</style>
