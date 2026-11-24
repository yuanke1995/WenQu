<template>
  <!-- 只在团队开启时出现：关着的时候顶栏这颗药丸没有任何可做的事（开关在右栏团队的「未开启」空态里），
       会跟旁边「N 位助手」读成同一个意思——见 ChatPage 顶部动作区注释。 -->
  <button
    v-if="sessionId && enabled"
    class="tp-pill on"
    type="button"
    :title="tip"
    @click="$emit('open')"
  >
    <span class="tp-dot" :class="{ run: running }"></span>
    <svg class="tp-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="9" cy="8" r="3"/><path d="M3 20c0-3 2.7-5 6-5s6 2 6 5"/><circle cx="17" cy="9" r="2"/><path d="M16 20c0-2 1.5-3.5 3.5-3.5S23 18 23 20"/>
    </svg>
    <span class="tp-txt">{{ count ? `团队 · ${count}` : '团队' }}</span>
  </button>
</template>

<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import * as api from '../api'
import { dispatchArgsOf, dispatchMemberOf } from '../chat/consult'

// 两种口径都摆在同一颗药丸上，因为它们问的是不同的事：
//   · 团队 · N —— 名册（后端 team members，跨轮持久）。开着团队的会话才有，就是「这个会话实际有哪些成员、谁在跑」。
//   · 本轮派遣 —— 当前这条 AI 消息里的派遣卡。团队关着时它才是唯一口径；开着时作为「这一轮新增/在跑」的名细，
//     也能原地定位到那张派遣卡（成员会话在另一条会话里，回看子轮得从卡片点进去）。
const props = defineProps({
  sessionId: { type: String, default: '' },
  /** 最近一条 AI 消息（与「过程」卡、子智能体药丸同一份数据源），用来摊本轮派遣 */
  msg: { type: Object, default: null }
})
defineEmits(['open'])

const enabled = ref(false)
const count = ref(0)
const running = ref(false)

// 本轮派遣：按成员名去重（同一位本轮问了两件事只算一位），按第一次出现排序
const roundRuns = computed(() => {
  const list = props.msg && Array.isArray(props.msg.toolCalls) ? props.msg.toolCalls : []
  const out = []
  const seen = new Set()
  list.forEach(t => {
    const name = t && (t.name === 'agent_spawn' || t.name === 'consultAgent') ? dispatchMemberOf(t) : ''
    if (!name || seen.has(name)) return
    seen.add(name)
    out.push({ name, running: t.status === 'start', task: dispatchArgsOf(t).task || '' })
  })
  return out
})
const roundRunCount = computed(() => roundRuns.value.filter(r => r.running).length)

const tip = computed(() => {
  const head = enabled.value
    ? `智能体团队：${count.value} 位成员${running.value ? '（有在跑的）' : ''} · 点击查看成员 / 任务 / 消息`
    : '智能体团队：这一轮派出去的助手，点开看名细'
  if (!roundRuns.value.length) return enabled.value ? head : head + '（团队未开启，派出去的是临时子轮）'
  const r = roundRunCount.value
  return head + `\n本轮派出 ${roundRuns.value.length} 位${r ? `，${r} 位还在跑` : '（已收场）'}`
})

async function load() {
  if (!props.sessionId) { enabled.value = false; count.value = 0; running.value = false; return }
  try {
    const on = await api.getTeamEnabled(props.sessionId)
    enabled.value = !!(on && on.enabled)
  } catch (e) { enabled.value = false }
  if (!enabled.value) { count.value = 0; running.value = false; return }
  try {
    const res = await api.getTeamMembers(props.sessionId)
    const list = Array.isArray(res) ? res : []
    count.value = list.length
    running.value = list.some(m => m.state === 'RUNNING' || m.state === 'WAITING')
  } catch (e) { /* 忽略 */ }
}

watch(() => props.sessionId, () => load(), { immediate: true })
let timer = null
function onTeamEvent() { load() }
onMounted(() => {
  load()
  timer = window.setInterval(load, 20000)
  window.addEventListener('wq:team-event', onTeamEvent)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  window.removeEventListener('wq:team-event', onTeamEvent)
})
</script>

<style scoped>
.tp-pill{display:inline-flex;align-items:center;gap:6px;height:28px;padding:0 11px;border-radius:999px;font-size:12px;font-weight:650;line-height:1;transition:background .15s,border-color .15s,color .15s;cursor:pointer}
.tp-pill.on{border:1px solid #ddd6fe;background:#f5f3ff;color:#7c3aed}
.tp-pill.on:hover{background:#ede9fe}
.tp-dot{width:6px;height:6px;border-radius:50%;flex:none;background:#c8ced6}
.tp-pill.on .tp-dot{background:#3b8f4e}
.tp-dot.run{background:#3b8f4e;box-shadow:0 0 0 3px rgba(59,143,78,.16)}
.tp-ico{width:13px;height:13px;flex:none}
.tp-txt{white-space:nowrap}
</style>