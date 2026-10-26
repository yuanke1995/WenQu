<template>
  <!-- 连通性横幅（两端壳共用）：只在「现在问不了」时占一行，通着的时候整块不渲染。
       两条判据各说一句话——断的是设备的网还是服务器的网，用户要去查的地方不一样。 -->
  <div v-if="text" class="nb" :class="'nb-' + kind" role="status">
    <disconnect-outlined class="nb-ic" />
    <span class="nb-t">{{ text }}</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { DisconnectOutlined } from '@ant-design/icons-vue'
import { isOnline, serverUnreachable } from '../utils/netState'

// 同一颗图标，靠配色分两类：橙=本机没网（可关），红=连不上服务器（不是你能关的）
const kind = computed(() => (!isOnline.value ? 'local' : (serverUnreachable.value ? 'server' : '')))
// 两句都不许写「会自动恢复」这类承诺：这一档的退场时机是「下一次请求拿到回应」，
// 而那是被动发生的（页面各自的轮询、用户的下一次点击），界面不该替它保证时间
const text = computed(() => {
  if (!isOnline.value) return '这台设备当前没有网络，消息发不出去'
  if (serverUnreachable.value) return '连不上服务器，请求没有回应'
  return ''
})
</script>

<style scoped>
.nb {
  flex: none; display: flex; align-items: center; gap: 6px;
  padding: 7px 12px; font-size: 12px; line-height: 1.4;
}
.nb-local { background: var(--app-warn-weak); color: var(--app-warn-text); border-bottom: 1px solid var(--app-warn-border); }
.nb-server { background: var(--app-danger-weak); color: var(--app-danger-text); border-bottom: 1px solid var(--app-danger-border); }
.nb-ic { flex: none; font-size: 13px; }
.nb-t { min-width: 0; }
</style>
