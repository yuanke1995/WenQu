<template>
    <Teleport to="body">
      <div v-if="store.thinkPanelVisible" :ref="el => setRef('thinkPanelEl', el)" class="think-float"
           :style="{ top: store.thinkPanelPos.top + 'px', left: store.thinkPanelPos.left + 'px' }"
           @mouseenter="store.onThinkPanelEnter" @mouseleave="store.onThinkPanelLeave"
           @mousedown.stop.prevent>
        <div class="thinkp">
          <div class="thinkp-title">运行设置<span v-if="store.modelLabelOf(store.thinkHoverModel)" class="thinkp-model">{{ store.modelLabelOf(store.thinkHoverModel) }}</span></div>
          <div v-if="store.thinkPanelCtxText || store.thinkPanelCaps.visible" class="thinkp-row-wrap">
            <!-- 上下文窗口只报登记上限：窗口一律按模型登记的最大值走 -->
            <div v-if="store.thinkPanelCtxText" class="thinkp-row"
                 title="模型登记的上下文窗口：一次能带进的资料与历史总量，也决定长对话什么时候开始折上下文">
              <span class="thinkp-row-label">上下文窗口</span>
              <span class="thinkp-row-val">{{ store.thinkPanelCtxText }}</span>
            </div>
            <!-- 本轮占用与检索范围只对「正在生效的模型」说：悬浮另一个模型时，别把本轮的用量算到它头上 -->
            <div v-if="store.ctxCapData.used && store.thinkHoverModel === store.effectiveModel" class="thinkp-row"
                 title="本轮结束时的上下文占用 / 模型登记的窗口">
              <span class="thinkp-row-label">上下文占用</span>
              <span class="thinkp-row-val">{{ store.fmtTokens(store.ctxCapData.used) }}<template v-if="store.ctxPctText"> · {{ store.ctxPctText }}</template></span>
            </div>
            <div v-if="store.thinkHoverModel === store.effectiveModel" class="thinkp-row" title="本轮显式 @ 的知识库；没有就是按智能体默认范围">
              <span class="thinkp-row-label">本轮检索</span>
              <span class="thinkp-row-val thinkp-row-ellipsis">{{ store.kbScopeText }}</span>
            </div>
            <template v-if="store.thinkPanelCaps.visible">
              <!-- 恒思考：强度由管理员在模型库登记，没有可选项，保持只读一行 -->
              <div v-if="store.thinkPanelCaps.locked" class="thinkp-row">
                <span class="thinkp-row-label">思考强度</span>
                <span class="thinkp-row-val">恒思考 · {{ store.thinkPanelStrengthText }}</span>
              </div>
              <!-- 可切换：档位平铺成一行直接点（与触屏 sheet 同款形态），不再「点行展开列表」；
                   「关闭」也是一颗档位豆，关掉思考在这张面板里始终有出口 -->
              <template v-else>
                <div class="thinkp-sub">思考强度</div>
                <div class="thinkp-chips">
                  <button v-for="opt in store.thinkPanelLevelOptions" :key="opt.value" class="thinkp-chip" type="button"
                          :class="{ on: opt.value === store.thinkPanelCurrentLevel }"
                          :title="opt.label" @click="store.setThinkLevel(opt.value, store.thinkHoverModel)">
                    {{ store.chipLabelOf(opt.value) }}
                  </button>
                </div>
              </template>
            </template>
          </div>
          <div class="thinkp-foot">{{ store.thinkPanelFoot }}</div>
        </div>
      </div>
    </Teleport>

    <!-- 上下文容量卡：悬浮输入框工具栏容量圆环弹出（占用/窗口 + 占用填充条 + 网关实测用量）。
         数据来自本轮落库的 tokens（刷新/切回会话仍在），窗口取用户所选档位或模型登记值 -->
    <Teleport to="body">
      <div v-if="store.ctxCapOpen" :ref="el => setRef('ctxCapEl', el)" class="ctxcap-float"
           :style="{ top: store.ctxCapPos.top == null ? 'auto' : store.ctxCapPos.top + 'px',
                     bottom: store.ctxCapPos.bottom == null ? 'auto' : store.ctxCapPos.bottom + 'px',
                     left: store.ctxCapPos.left + 'px' }"
           @mouseenter="store.onCtxCapEnter" @mouseleave="store.onCtxCapLeave">
        <div class="ctxcap">
          <div class="ctxcap-head">
            <div class="ctxcap-title">上下文容量</div>
            <div v-if="store.ctxCapData.window > 0" class="ctxcap-num">
              {{ store.fmtTokens(store.ctxCapData.used) }}/{{ store.fmtWindow(store.ctxCapData.window) }}（{{ store.ctxCapData.pct }}%）
            </div>
          </div>
          <template v-if="store.ctxCapData.window > 0">
            <!-- 填充宽=用量/窗口（与标题占用率同口径，空轨即剩余窗口） -->
            <div class="ctxcap-bar">
              <div class="ctxcap-fill" :class="store.ctxRingLevel" :style="{ width: store.ctxCapData.fillPct + '%' }"></div>
            </div>
            <!-- 每一行都是网关 usage 回传的实测值；名里带「未缓存/其中」的是上一行的子项，
                 缩进摆，不并列成能相加的样子 -->
            <div v-for="r in store.ctxCapData.rows" :key="r.label" class="ctxcap-row" :class="{ 'is-sub': r.sub }">
              <span class="ctxcap-row-label">{{ r.label }}</span>
              <span class="ctxcap-row-val">
                <template v-if="r.note">{{ r.note }} · </template>{{ store.fmtTokens(r.tokens) }}
              </span>
            </div>
            <div v-if="!store.ctxCapData.rows.length" class="ctxcap-empty">本轮还没有实测用量（随用量事件下发）</div>
            <!-- 手动压缩上下文的常驻入口：压完本卡数字不动，
                 腾出的量在 toast 里报，占用率要到下一轮落库才反映在这里 -->
            <div class="ctxcap-foot">
              <button class="app-btn ghost ctxcap-compact" type="button"
                      :disabled="store.compacting || store.loading"
                      :title="store.compacting || store.loading ? '' : '把较早的对话并入摘要，为后续提问腾出空间；完整记录仍可回看'"
                      @click="store.compactContext()">
                <compress-outlined />
                <span>{{ store.compacting ? '压缩中…' : '压缩上下文' }}</span>
              </button>
              <div v-if="store.loading && !store.compacting" class="ctxcap-tip">本轮结束后可压缩</div>
            </div>
          </template>
          <div v-else class="ctxcap-empty">该模型未登记上下文窗口，请在「模型供应商」里声明</div>
        </div>
      </div>
    </Teleport>

</template>

<script setup>
import { CompressOutlined } from '@ant-design/icons-vue'
import { inject } from 'vue'
// 父页 provide('chatCtx')：一个 reactive 上下文（含 ref 与动作）
const store = inject('chatCtx')
const set = (k, v) => { store[k] = v }
const setRef = (k, el) => { store[k] = el }
</script>

<style scoped src="./ChatOverlays.css"></style>
