<template>
  <!-- 行内「⋯」菜单：a-dropdown 会 teleport 到 body，不会被列表的圆角裁切 -->
  <a-dropdown :trigger="['click']" placement="bottomRight">
    <button class="nk-btn" type="button" aria-label="通知操作" @click.stop>
      <MoreOutlined />
    </button>
    <template #overlay>
      <a-menu>
        <a-menu-item v-if="item.readFlag" key="unread" @click="$emit('mark-unread', item)">
          标记未读
        </a-menu-item>
        <a-menu-item key="delete" danger @click="$emit('delete', item)">
          删除
        </a-menu-item>
      </a-menu>
    </template>
  </a-dropdown>
</template>

<script setup>
import { MoreOutlined } from '@ant-design/icons-vue'

defineProps({ item: { type: Object, required: true } })
defineEmits(['mark-unread', 'delete'])
</script>

<style scoped>
.nk-btn {
  width: 22px; height: 22px; border: 0; border-radius: 6px; padding: 0;
  background: transparent; color: var(--app-text3); font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
}
.nk-btn:hover { color: var(--app-accent); background: var(--app-accent-weak); }
</style>
