<template>
  <!-- 一轮回答的「整轮用时」那一条（判据全在 projections 的 turn* 那组函数里，两端共用）：
       跑着的时候跟在正文最后面跟着秒走，答完回到回答顶上收成一条总览。
       耗时只在这一处报——原先簇头、组条、每张工具卡各挂一个数，一屏六个读数读不出重点。
       它同时是一轮里唯一的进行中提示：底下不再另挂一行阶段文案（两句「正在…」上下同屏）。 -->
  <div v-if="turnShow(m)" class="turn-bar" :class="{ 'is-m': mobile }">
    <button v-if="turnToggleable(m)" class="tb-btn" type="button" :title="turnTitle(m)" @click.stop="toggleTurn(m)">
      <span class="tb-text" :class="{ running: m.loading, warn: turnWarn(m) }">{{ turnHead(m) }}</span>
      <caret-right-outlined class="tb-caret" :class="{ open: turnOpen(m) }" />
    </button>
    <!-- 两种情况走这一行：底下没过程可展开；或还在跑——跑着的时候它只是一行跟着秒走的状态，
         把正在长的过程收起来没有可读的东西，所以不给三角（点了什么也不会发生） -->
    <div v-else class="tb-plain" :title="turnTitle(m)">
      <span class="tb-text" :class="{ running: m.loading, warn: turnWarn(m) }">{{ turnHead(m) }}</span>
    </div>
  </div>
</template>

<script setup>
import { CaretRightOutlined } from '@ant-design/icons-vue'
import { turnShow, turnOpen, toggleTurn, turnHead, turnTitle, turnToggleable, turnWarn } from '../chat/projections'

// 折叠态必须落在消息对象上（m._turnOpen），不能放组件本地 ref：真正决定过程行渲不渲染的是
// 父级模板里那份摊平结果，本地 ref 管不到它。
defineProps({ m: { type: Object, required: true }, mobile: Boolean })
</script>

<style scoped>
.turn-bar { margin: 0 0 4px; }
.tb-btn, .tb-plain {
  display: inline-flex; align-items: center; gap: 6px; width: fit-content; max-width: 100%;
  padding: 3px 9px; font-size: 12px; color: var(--app-text2); text-align: left;
  background: var(--app-bg); border: 1px solid var(--app-border); border-radius: 8px;
}
.tb-btn { cursor: pointer; user-select: none; }
.tb-btn:hover { border-color: var(--app-accent); color: var(--app-text); }
.tb-plain { color: var(--app-text3); }
/* 触屏形态：整行都是热区（与移动壳其它折叠卡同款），字号与最小高度按触屏口径放大 */
.is-m { margin: 0 0 6px; }
.is-m .tb-btn, .is-m .tb-plain {
  width: 100%; min-height: 40px; flex-wrap: wrap; padding: 10px 12px;
  font-size: 13px; border-radius: 10px; background: var(--app-panel-2); touch-action: manipulation;
}
.tb-caret { flex: none; font-size: 10px; color: var(--app-text3); transition: transform .15s; line-height: 1; }
.tb-caret.open { transform: rotate(90deg); }
.is-m .tb-caret { margin-left: auto; font-size: 12px; }
/* 进行中：标题在主色与次文本之间循环渐变（与思考段标题同一语言）。刻意错开半个周期——
   这一条与它下面正在长的那一行同相呼吸会读成整列一起闪。 */
.tb-text.running { animation: tb-run 1.6s ease-in-out infinite; animation-delay: -.8s; }
@keyframes tb-run { 0%, 100% { color: var(--app-text2); } 50% { color: var(--app-accent); } }
@media (prefers-reduced-motion: reduce) {
  .tb-text.running { animation: none; color: var(--app-accent); }
  .tb-caret { transition: none; }
}
/* 自动重试：同一行改口说异常，静止警示色（呼吸动效只留给正常在跑的状态）。
   排在减动效那条之后——同特异度下后写的赢，否则关掉动效时警示色会被主色盖掉 */
.tb-text.warn { color: var(--app-warn-text); animation: none; }
</style>
