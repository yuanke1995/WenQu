<template>
  <!-- 执行计划卡（人在回路）：只读展示一轮计划的生命周期——
       流式产出 drafting → 待确认 pending → 已批准 / 未批准 / 已被取代。
       裁决动作不在这里：打字框整块顶替成确认栏（PC 的 planp-*、移动壳的 m-planp-*），
       两端共用引擎里的 pendingPlan / curPlanCard / planExpired / planCountdownText。 -->
  <div class="plan-card" :class="'plan-' + card.status">
    <div class="plan-card-head">
      <ordered-list-outlined class="plan-card-ic" />
      <span class="plan-card-title">执行计划</span>
      <span v-if="card.status === 'drafting'" class="plan-state st-drafting">生成中…</span>
      <span v-else-if="card.status === 'pending'" class="plan-state st-pending">待确认</span>
      <span v-else-if="card.status === 'approved'" class="plan-state st-ok">已批准</span>
      <span v-else-if="card.status === 'superseded'" class="plan-state st-superseded">已被取代</span>
      <span v-else class="plan-state st-rejected">未批准 · 已停止本轮</span>
      <button v-if="chain.length > 1" type="button" class="plan-hist-toggle"
              :title="card.histOpen ? '收起修改记录' : '查看各版计划与你的修改意见'"
              @click="card.histOpen = !card.histOpen">
        已修改 {{ chain.length - 1 }} 次
        <up-outlined v-if="card.histOpen" /><down-outlined v-else />
      </button>
    </div>
    <div v-if="card.status !== 'rejected'" class="plan-wrap"
         :class="{ 'is-clamped': needsFold(card.plan) && !card.open }">
      <div class="md plan-body" v-html="renderMd(card.plan || '（计划为空）', [])"></div>
      <button v-if="needsFold(card.plan)" type="button" class="plan-more" @click="card.open = !card.open">
        {{ card.open ? '收起' : '查看完整计划' }}
      </button>
    </div>
    <!-- 修改记录：同轮内每次「退回重出」的版本链（v1 模型原稿 → 你的意见 → v2 …），默认收起不刷屏 -->
    <div v-if="card.histOpen" class="plan-hist">
      <div v-for="(v, vi) in chain" :key="vi" class="plan-hist-item">
        <div v-if="v.feedback" class="plan-hist-fb">你的意见：{{ v.feedback }}</div>
        <div class="plan-hist-no">第 {{ vi + 1 }} 版{{ versionTag(vi) }}</div>
        <div class="plan-wrap" :class="{ 'is-clamped': needsFold(v.plan) && !v.open }">
          <div class="md plan-body" v-html="renderMd(v.plan || '（计划为空）', [])"></div>
          <button v-if="needsFold(v.plan)" type="button" class="plan-more" @click="v.open = !v.open">
            {{ v.open ? '收起' : '查看完整计划' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { OrderedListOutlined, UpOutlined, DownOutlined } from '@ant-design/icons-vue'
import { renderMd } from '../utils/markdown'

const props = defineProps({
  /** 引擎挂在这一轮助手消息上的计划卡记录：{plan,status,versions,feedback,open,histOpen} */
  card: { type: Object, required: true }
})

// 版本链 = 已被改掉的各版（card.versions，feedback=产出该版的意见）+ 当前版（主体，链尾也列一份）
const chain = computed(() => {
  const c = props.card
  const list = Array.isArray(c?.versions) ? c.versions.slice() : []
  list.push({ plan: c?.plan || '', feedback: c?.feedback || '' })
  return list
})
// 行尾标：首版=模型原稿；末版=终态/当前版；中间版不标
const versionTag = vi => {
  const c = props.card
  const total = chain.value.length
  if (vi === 0) return ' · 模型原稿'
  if (vi !== total - 1) return ''
  if (c.status === 'approved') return ' · 已批准'
  if (c.status === 'rejected') return ' · 未批准'
  return ' · 当前版'
}
// 长计划紧凑预览：短计划原样铺开、不出按钮
const needsFold = plan => {
  const s = String(plan || '')
  if (!s.trim()) return false
  return s.split('\n').filter(x => x.trim()).length > 5 || s.length > 200
}
</script>

<style scoped>
/* 生命周期能从卡片自身状态读出来：drafting 生成中（蓝）→ pending 待确认（琥珀，同审批卡语义）
   → approved 已批准（绿）/ rejected 未批准（灰弱化收尾） */
.plan-card { margin-top: 8px; border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 8px; padding: 10px 12px; }
.plan-card.plan-approved { border-color: var(--app-ok-border, var(--app-border)); background: var(--app-panel); opacity: .92; }
.plan-card.plan-rejected { border-color: var(--app-border); background: var(--app-panel); opacity: .75; }
/* 已被取代（用户提出修改、继续对话后本版作废）：弱化收尾，但正文照常可读（它是修改记录的一部分） */
.plan-card.plan-superseded { border-color: var(--app-border); background: var(--app-panel); opacity: .82; }
.plan-card-head { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--app-warn-text); }
.plan-card.plan-approved .plan-card-head, .plan-card.plan-rejected .plan-card-head,
.plan-card.plan-superseded .plan-card-head { color: var(--app-text2); }
.plan-state { font-weight: 500; font-size: 12px; }
.plan-state.st-drafting { color: var(--app-text3); }
.plan-state.st-pending { color: var(--app-warn-text); }
.plan-state.st-ok { color: var(--app-ok); }
.plan-state.st-rejected { color: var(--app-text3); }
.plan-state.st-superseded { color: var(--app-text3); }
.plan-body { margin-top: 8px; font-size: 13px; }
/* 长计划紧凑预览：限高 + 底部渐隐，居中「查看完整计划」展开/收起；
   短计划不加限高、不出按钮，与改动前逐字一致 */
.plan-wrap { position: relative; }
.plan-wrap.is-clamped .plan-body {
  max-height: 118px; overflow: hidden;
  -webkit-mask-image: linear-gradient(to bottom, #000 46%, transparent 96%);
  mask-image: linear-gradient(to bottom, #000 46%, transparent 96%);
}
.plan-wrap.is-clamped .plan-more { position: absolute; left: 50%; bottom: 2px; transform: translateX(-50%); }
/* 展开态的按钮留在正文下方同处居中：inline-flex 是行内级盒，margin:auto 不生效，
   会和收起态（绝对定位居中）错开一个身位，故用 flex + fit-content 变块级盒 */
.plan-more { display: flex; width: fit-content; align-items: center; gap: 4px; margin: 8px auto 0; padding: 3px 12px;
  border: 1px solid var(--app-border); border-radius: 999px; background: var(--app-panel);
  color: var(--app-text2); font-size: 12px; line-height: 1.4; cursor: pointer; }
.plan-more:hover { color: var(--app-accent); border-color: var(--app-accent); }
/* 「修改记录」折叠：卡头右侧入口 + 版本链（每版正文 + 产出它的意见），默认收起不刷屏 */
.plan-hist-toggle { flex: none; margin-left: auto; display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 8px; border: 1px solid var(--app-border); border-radius: 999px;
  background: var(--app-panel); color: var(--app-text2); font-size: 11px; line-height: 1.5; cursor: pointer; }
.plan-hist-toggle:hover { color: var(--app-accent); border-color: var(--app-accent); }
.plan-hist { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--app-border); display: flex; flex-direction: column; gap: 8px; }
.plan-hist-item { padding: 7px 9px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-panel); }
.plan-hist-no { font-size: 12px; font-weight: 600; color: var(--app-text2); }
.plan-hist-fb { font-size: 12px; color: var(--app-text2); background: var(--app-panel-2); border-radius: 5px; padding: 4px 8px; margin-bottom: 6px; word-break: break-word; }
.plan-hist-item .plan-body { margin-top: 6px; font-size: 12.5px; }
.plan-hist-item .plan-wrap.is-clamped .plan-body { max-height: 96px; }
</style>
