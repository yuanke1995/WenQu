<template>
  <!-- 顶栏「子智能体」药丸：一轮里派出去几位、几位还在跑、最长那支跑了多久。
       数据全从最新一条 AI 消息的派遣卡里摊（判据在 projections.subagentRunsOf），本组件不取数、
       不起轮询——刷新与后台轮重连接的是同一个消息对象，药丸自然跟着有数。
       触屏形态（mobile）没有常驻右栏，点开走底部 sheet；PC 走药丸正下方那块浮层。 -->
  <div v-if="runs.length" class="sap" :class="{ 'sap-m': mobile }">
    <button class="sap-pill" :class="{ on: open }" type="button"
            :aria-expanded="open ? 'true' : 'false'" :title="tip" :aria-label="tip" @click="toggle">
      <loading-outlined v-if="runningCount" spin class="sap-ic" />
      <apartment-outlined v-else class="sap-ic sap-ic-mut" />
      <span class="sap-t">{{ runs.length }}</span>
      <template v-if="runningCount">
        <span class="sap-sep">·</span><span class="sap-r">在跑 {{ runningCount }}</span>
      </template>
      <span v-if="lead" class="sap-d">{{ lead }}</span>
    </button>

    <template v-if="open">
      <div v-if="mobile" class="sap-mask" @click="open = false" />
      <div class="sap-pop" :class="{ 'sap-pop-m': mobile }">
        <div class="sap-head">
          <span>这一轮派出去的助手</span>
          <button v-if="mobile" class="sap-x" type="button" title="收起" @click="open = false">
            <close-outlined />
          </button>
        </div>
        <button v-for="(r, ri) in runs" :key="ri" class="sap-row" type="button" @click="pick(r)">
          <span class="sap-ri">
            <loading-outlined v-if="r.running" spin class="sap-ic" />
            <close-circle-outlined v-else-if="r.failed" class="sap-ic sap-ic-err" />
            <check-outlined v-else class="sap-ic sap-ic-ok" />
          </span>
          <span class="sap-main">
            <span class="sap-name">{{ r.name }}</span>
            <span v-if="r.task" class="sap-task" :title="r.task">{{ r.task }}</span>
            <span v-if="r.live" class="sap-step" :title="r.live">{{ r.live }}</span>
          </span>
          <span class="sap-dur" :class="{ run: r.running, bad: r.failed }">{{ durOf(r) }}</span>
        </button>
        <div class="sap-foot">点一行定位到回答里那张卡</div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApartmentOutlined, CheckOutlined, CloseCircleOutlined, CloseOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import { nowTick, holdTick, subagentRunsOf, toolDuration } from '../chat/projections'

const props = defineProps({
  /** 最近一条 AI 消息（含跑轮中那颗）——与右栏「过程」卡同一份数据源 */
  msg: { type: Object, default: null },
  /** 触屏形态：点开走底部 sheet，而不是右上角浮层 */
  mobile: Boolean
})
const emit = defineEmits(['focus'])

const open = ref(false)
const runs = computed(() => subagentRunsOf(props.msg))
const runningCount = computed(() => runs.value.filter(r => r.running).length)
// 药丸只剩图标+数字，「这几个字是什么意思」全部落到这一句悬浮里（触屏没有悬浮，点开浮层的
// 标题「这一轮派出去的助手」就是同一句话的落地版）
const tip = computed(() => (runningCount.value
  ? `这一轮派出 ${runs.value.length} 位助手，${runningCount.value} 位还在跑`
  : `这一轮派出的 ${runs.value.length} 位助手`))
// 药丸上那个跳秒的数：最长（最早起表）那支在跑的。没有在跑的、或在跑的那支没有本地起表点
// （刷新接上来的），就不给数——宁缺一个读数，不拿服务端的墙上时刻凑一个跨钟的差
const lead = computed(() => {
  const starts = runs.value.filter(r => r.running && r.liveStart).map(r => r.liveStart)
  if (!starts.length) return ''
  return toolDuration(Math.max(0, nowTick.value - Math.min(...starts)))
})
const durOf = r => {
  if (!r.running) return r.elapsedMs ? toolDuration(r.elapsedMs) : (r.failed ? '没跑成' : '')
  return r.liveStart ? toolDuration(Math.max(0, nowTick.value - r.liveStart)) : '在跑'
}

// 有支在跑就挂表（与整轮用时那条共用同一个秒表，不各起一个 setInterval）
let releaseTick = null
watch(runningCount, n => {
  if (n > 0 && !releaseTick) releaseTick = holdTick()
  else if (n === 0 && releaseTick) { releaseTick(); releaseTick = null }
}, { immediate: true })
onBeforeUnmount(() => {
  if (releaseTick) releaseTick()
  document.removeEventListener('click', onDocClick, true)
})

// 派遣都收场了就自动收起：留着浮层挡着正文，而它已经不会再变了
watch(runningCount, n => { if (n === 0) open.value = false })
// 换会话（消息对象整份换掉）时收起，别把上一轮的名单带过去
watch(() => props.msg, () => { open.value = false })

function toggle () { open.value = !open.value }
// 点外面收起（PC）：这块浮层没有别的退出键。capture 阶段挂——同一颗点击正在冒泡，
// 挂 bubble 会把自己刚打开的那一下当成「点在外面」立刻关掉
watch(open, v => {
  if (v && !props.mobile) document.addEventListener('click', onDocClick, true)
  else document.removeEventListener('click', onDocClick, true)
})
function onDocClick (e) {
  if (e.target && e.target.closest && e.target.closest('.sap')) return
  open.value = false
}
function pick (r) {
  // 带上 msg：定位要先替这一条消息把过程整条/所在簇/所在组条摊开，光给卡片对象够不着那三层
  emit('focus', { run: r, msg: props.msg })
  // 两端都收起：点这一行就是要看那张卡，浮层留在原位会挡住刚滚过来的那一段
  open.value = false
}
</script>

<style scoped>
.sap { position: relative; display: inline-flex; flex: none; }
.sap-pill {
  display: inline-flex; align-items: center; gap: 5px; height: 24px; padding: 0 9px;
  font: inherit; font-size: 12px; color: var(--app-text2); white-space: nowrap;
  background: transparent; border: none; border-radius: 999px;
  cursor: pointer; user-select: none;
}
/* 无框安静，与标题栏那一排图标按钮同一套（.app-icon-btn：悬浮/展开都只给一层淡底） */
.sap-pill:hover { color: var(--app-text); background: var(--app-accent-weak); }
.sap-pill.on { color: var(--app-text); background: var(--app-accent-weak); }
.sap-ic { flex: none; font-size: 12px; color: var(--app-accent); }
.sap-ic-mut { color: var(--app-text3); }
.sap-ic-ok { color: var(--app-ok); }
.sap-ic-err { color: var(--app-danger); }
.sap-t { font-weight: 500; color: var(--app-text); font-variant-numeric: tabular-nums; }
.sap-sep { color: var(--app-border-strong); }
.sap-r { color: var(--app-text2); }
.sap-d { color: var(--app-accent); font-variant-numeric: tabular-nums; }

.sap-pop {
  position: absolute; top: 30px; right: 0; z-index: 30; width: 300px; max-width: 78vw;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  box-shadow: var(--app-shadow-lg); overflow: hidden;
}
.sap-head {
  display: flex; align-items: center; padding: 8px 10px; font-size: 11px; color: var(--app-text3);
  border-bottom: 1px solid var(--app-border);
}
.sap-x { margin-left: auto; width: 24px; height: 24px; border: 0; background: transparent; color: var(--app-text3); cursor: pointer; }
.sap-row {
  display: flex; align-items: flex-start; gap: 8px; width: 100%; padding: 7px 10px;
  font: inherit; text-align: left; background: none; border: 0; border-bottom: 1px solid var(--app-panel-2); cursor: pointer;
}
.sap-row:hover { background: var(--app-panel-2); }
.sap-ri { flex: none; width: 14px; padding-top: 2px; }
.sap-main { display: flex; flex-direction: column; gap: 1px; flex: 1; min-width: 0; }
.sap-name { font-size: 12.5px; font-weight: 500; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sap-task, .sap-step { font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sap-task { color: var(--app-text3); }
.sap-step { color: var(--app-accent); }
.sap-dur { flex: none; padding-top: 1px; font-size: 11.5px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.sap-dur.run { color: var(--app-accent); }
.sap-dur.bad { color: var(--app-danger); }
.sap-foot { padding: 6px 10px; font-size: 11px; color: var(--app-text3); background: var(--app-panel-2); }

/* 触屏：整行热区放大，名单从底部升起（窄屏没有常驻右栏，浮层放不下） */
.sap-pop-m {
  position: fixed; top: auto; left: 0; right: 0; bottom: 0; width: auto; max-width: none;
  border: 0; border-radius: 14px 14px 0 0; padding-bottom: env(safe-area-inset-bottom);
}
.sap-pop-m .sap-row { padding: 11px 14px; border-bottom: 1px solid var(--app-border); }
.sap-pop-m .sap-head { font-size: 12px; padding: 12px 14px; }
.sap-mask { position: fixed; inset: 0; z-index: 29; background: rgba(15, 19, 26, .45); }
.sap-m .sap-pill { height: 28px; }
</style>
