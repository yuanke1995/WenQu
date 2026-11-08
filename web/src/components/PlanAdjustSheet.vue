<!--
  批准后调整计划的面板（A 插话 / B 停止并重新计划）。

  只负责编辑与二次确认，不发请求：提交 emit 回页面，由页面调引擎。
  - 执行中：默认 A（插话，不中断）；B 需要第二个确认步，把「停在哪、已完成保留、需重新确认」摊开。
  - 已结束：只有 B（没有可停的东西），不出现二次确认。
  - PC 居中 520px；≤720px 走底部 sheet，主按钮吸底。
-->
<template>
  <teleport to="body">
    <div v-if="open" class="pa-mask" @click="onMask">
      <section class="pa-sheet" role="dialog" aria-modal="true" aria-label="调整计划" @click.stop>
        <div class="pa-handle" aria-hidden="true"></div>

        <template v-if="step === 'confirm'">
          <header class="pa-head">
            <h3 class="pa-title">停止并重新计划？</h3>
            <p class="pa-sub">{{ stepText }} · 这一停不可撤销</p>
          </header>
          <div class="pa-body">
            <div v-if="total > 0" class="pa-sum"
                 :style="{ gridTemplateColumns: `repeat(${failed ? 4 : 3}, 1fr)` }">
              <div><b class="ok">{{ done }}</b><span>已完成 · 保留</span></div>
              <div class="run"><b>{{ doing }}</b><span>进行中 · 将停止</span></div>
              <div v-if="failed" class="fail"><b>{{ failed }}</b><span>失败 · 不重试</span></div>
              <div><b>{{ wait }}</b><span>未开始 · 随新计划</span></div>
            </div>
            <ul class="pa-list">
              <li><b>已完成的步骤、产物和执行记录都保留</b>，不会回滚，也不会因为新计划而删除。</li>
              <li><b>本轮在这里停下</b>，当前这一步不会再继续；停止后不能从断点续跑。</li>
              <li><b>新计划要再确认一次</b>才会执行；确认前它只是一份待审阅的计划。</li>
              <li v-if="failed"><b>失败的 {{ failed }} 步不会自动重试</b>；如果仍要完成，请在新意见里写清怎么处理。</li>
            </ul>
            <div class="pa-quote">你的意见：{{ text.trim() }}</div>
            <p v-if="error" class="pa-err">{{ error }}</p>
          </div>
          <footer class="pa-foot">
            <span class="pa-hint">上一步返回编辑，不会停止任何东西</span>
            <div class="pa-acts">
              <button type="button" class="pa-btn ghost" :disabled="busy" @click="emit('close')">取消</button>
              <button type="button" class="pa-btn" :disabled="busy" @click="step = 'edit'">上一步</button>
              <button type="button" class="pa-btn warn" :disabled="busy || !text.trim()" @click="submit">
                {{ busy ? '正在停止…' : '停止并重新计划' }}
              </button>
            </div>
          </footer>
        </template>

        <template v-else>
          <header class="pa-head">
            <h3 class="pa-title">{{ running ? '调整计划' : '重新计划' }}</h3>
            <p class="pa-sub">{{ stepText }}</p>
          </header>
          <div class="pa-body">
            <textarea ref="taEl" v-model="text" class="pa-ta" :maxlength="MAX_TEXT" rows="3"
                      :placeholder="running
                        ? '说清楚要改什么：改哪一步、改成什么、哪些保留'
                        : '说清楚新计划要改什么：哪些沿用、哪些重排'"
                      :disabled="busy"></textarea>

            <div class="pa-modes">
              <button v-if="running" type="button" class="pa-mode" :class="{ on: mode === 'steer' }"
                      :disabled="busy" @click="pick('steer')">
                <span class="pa-radio" aria-hidden="true"></span>
                <span class="pa-mode-main">
                  <span class="pa-mode-t">插话调整 · 不中断 <em class="pa-tag">推荐</em></span>
                  <span class="pa-mode-d">这句话在<b>下一次调模型之前</b>送到正在跑的这一轮。当前步骤不会停，<b>已完成的步骤不会重做</b>；计划正文不变，调整记在本轮执行记录里。</span>
                </span>
              </button>
              <button type="button" class="pa-mode" :class="{ on: mode === 'replan' }"
                      :disabled="busy" @click="pick('replan')">
                <span class="pa-radio" aria-hidden="true"></span>
                <span class="pa-mode-main">
                  <span class="pa-mode-t">{{ running ? '停止并重新计划' : '按新意见重新计划' }}</span>
                  <span class="pa-mode-d">
                    <template v-if="running">在这个位置停下（当前步骤中断）；<b>已完成的步骤与产物保留</b>；按新意见生成新计划，<b>需要你再确认一次</b>。</template>
                    <template v-else>上一轮已经结束（{{ total > 0 ? done + ' / ' + total + ' 完成' : '没有可读的进度' }}），执行记录保留；将生成新计划并重新确认。</template>
                  </span>
                </span>
              </button>
            </div>
            <p class="pa-note">两种方式都不会回滚已完成的步骤；区别只在这里停不停、计划正文改不改。</p>
            <p v-if="failed && !running" class="pa-note">有 {{ failed }} 步失败：失败项不会自动重试，新计划里要写清怎么处理。</p>
            <p v-if="error" class="pa-err">{{ error }}</p>
          </div>
          <footer class="pa-foot">
            <span class="pa-hint">{{ running ? '输入框发送也是插话；这里多给一个「重新计划」选项' : '新计划生成后仍要你确认，不会直接执行' }}</span>
            <div class="pa-acts">
              <button type="button" class="pa-btn" :disabled="busy" @click="emit('close')">取消</button>
              <button type="button" class="pa-btn primary" :disabled="busy || !text.trim()" @click="next">
                {{ busy ? '处理中…' : (mode === 'replan' && running ? '下一步' : (mode === 'steer' ? '确认调整' : '重新生成计划')) }}
              </button>
            </div>
          </footer>
        </template>
      </section>
    </div>
  </teleport>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'

const MAX_TEXT = 8000

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 这一轮是否正在执行：决定默认档与是否出现「停止」 */
  running: { type: Boolean, default: false },
  /** 当前进度（没有任务清单时 total=0，二次确认不摆假计数） */
  done: { type: Number, default: 0 },
  doing: { type: Number, default: 0 },
  total: { type: Number, default: 0 },
  failed: { type: Number, default: 0 },
  /** 提交中：停止可能耗时，按钮置忙、禁止关闭 */
  busy: { type: Boolean, default: false },
  /** 父层回传的失败原因（本轮刚结束/停止失败等），面板留在原地 */
  error: { type: String, default: '' },
  /** A 送达时本轮刚好结束：父层把默认档切到 B，并给出一键改用的提示 */
  suggestReplan: { type: Boolean, default: false },
  /** 当前在第几步：与执行进度同一口径（只用于副标题） */
  stepLabel: { type: String, default: '' }
})
const emit = defineEmits(['close', 'submit'])

const text = ref('')
const mode = ref('steer')
const step = ref('edit')
const taEl = ref(null)

const wait = computed(() => Math.max(0, props.total - props.done
  - (props.running ? Math.max(1, props.doing) : props.doing) - props.failed))
const stepText = computed(() => {
  if (!props.running) {
    return props.total > 0 ? `上一轮已结束 · ${props.done} / ${props.total} 完成` : '上一轮已结束 · 执行记录保留'
  }
  return props.stepLabel
    ? `${props.stepLabel}执行中 · 已完成 ${props.done} / ${props.total || '?'}`
    : `执行中 · 已完成 ${props.done} / ${props.total || '?'}`
})

/* 打开时按状态复位到正确默认档；草稿保留（提交失败时原文不丢，用户不必重打） */
watch(() => props.open, async open => {
  if (!open) return
  step.value = 'edit'
  // 每次打开按当前状态复位：执行中默认 A（更轻），已结束只有 B
  mode.value = props.running ? 'steer' : 'replan'
  await nextTick()
  taEl.value?.focus?.()
}, { immediate: true })

/* 本轮在面板打开期间结束：A 不再可用，自动切到 B（不让用户点一个必然失败的选项） */
watch(() => props.running, v => {
  if (!v && mode.value === 'steer') mode.value = 'replan'
  if (!v) step.value = 'edit'
})
/* A 送达时本轮刚好结束：切到 B，原文保留，用户只需再点一次确认 */
watch(() => props.suggestReplan, v => {
  if (!v) return
  step.value = 'edit'
  mode.value = 'replan'
})

function pick (m) {
  if (props.busy) return
  mode.value = m
}

function next () {
  if (props.busy || !text.value.trim()) return
  if (mode.value === 'replan' && props.running) { step.value = 'confirm'; return }
  submit()
}
function submit () {
  if (props.busy || !text.value.trim()) return
  emit('submit', { text: text.value.trim(), mode: mode.value })
}

function onMask () {
  if (props.busy) return
  emit('close')
}

/* 背景滚动锁：与 PlanViewer 叠加时，各自保存/还原上一次的值，关闭后不会把聊天页锁死 */
let prevOverflow = ''
watch(() => props.open, open => {
  if (open) {
    prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = prevOverflow || ''
  }
}, { immediate: true })

function onKey (e) {
  if (!props.open) return
  if (e.key === 'Escape') {
    e.stopPropagation()
    if (props.busy) return
    if (step.value === 'confirm') step.value = 'edit'
    else emit('close')
  }
}
window.addEventListener('keydown', onKey, true)
onUnmounted(() => {
  window.removeEventListener('keydown', onKey, true)
  document.body.style.overflow = prevOverflow || ''
})
</script>

<style scoped>
.pa-mask { position: fixed; inset: 0; z-index: 1200; background: rgba(16, 24, 40, .32); display: flex; align-items: center; justify-content: center; padding: 24px; }
.pa-sheet { width: 520px; max-width: 100%; max-height: 86vh; display: flex; flex-direction: column;
  background: var(--app-panel, #fff); border: 1px solid var(--app-border-strong, #d8dde3); border-radius: 14px;
  box-shadow: 0 22px 60px rgba(16, 24, 40, .24); overflow: hidden; animation: pa-in .18s ease-out; }
@keyframes pa-in { from { transform: translateY(8px) scale(.985); opacity: .7; } to { transform: none; opacity: 1; } }
.pa-handle { display: none; }

.pa-head { padding: 15px 18px 11px; border-bottom: 1px solid var(--app-border); }
.pa-title { margin: 0; font-size: 16px; line-height: 1.4; font-weight: 650; color: var(--app-text); }
.pa-sub { margin: 5px 0 0; font-size: 11.5px; color: var(--app-text3); }

.pa-body { padding: 14px 18px; overflow-y: auto; display: flex; flex-direction: column; gap: 11px; }
.pa-ta { width: 100%; box-sizing: border-box; resize: vertical; min-height: 74px; max-height: 160px;
  border: 1px solid var(--app-border); border-radius: 8px; padding: 9px 11px; font: inherit;
  font-size: 12.5px; line-height: 1.65; color: var(--app-text); background: var(--app-panel); }
.pa-ta:focus { outline: none; border-color: var(--app-accent); }
.pa-ta:disabled { background: var(--app-panel-2); }

.pa-modes { display: flex; flex-direction: column; gap: 7px; }
.pa-mode { display: flex; align-items: flex-start; gap: 9px; width: 100%; text-align: left;
  border: 1px solid var(--app-border); border-radius: 9px; padding: 9px 11px; background: var(--app-panel);
  cursor: pointer; font: inherit; }
.pa-mode:hover { border-color: var(--app-accent-border); }
.pa-mode.on { border-color: var(--app-accent-border); background: var(--app-accent-weak); box-shadow: inset 0 0 0 1px var(--app-accent-border); }
.pa-mode:disabled { cursor: default; opacity: .7; }
.pa-radio { flex: none; width: 12px; height: 12px; margin-top: 3px; border-radius: 50%;
  border: 1px solid var(--app-border-strong); background: var(--app-panel); }
.pa-mode.on .pa-radio { border-color: var(--app-accent); background: var(--app-accent); }
.pa-mode-main { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.pa-mode-t { font-size: 12.5px; font-weight: 650; color: var(--app-text); }
.pa-tag { font-style: normal; font-size: 9.5px; font-weight: 500; color: var(--app-accent);
  border: 1px solid var(--app-accent-border); background: var(--app-panel); border-radius: 4px; padding: 0 5px; margin-left: 4px; vertical-align: 1px; }
.pa-mode-d { font-size: 11px; line-height: 1.65; color: var(--app-text3); }
.pa-mode-d b { color: var(--app-text2); }
.pa-note { margin: 0; font-size: 10.5px; line-height: 1.6; color: var(--app-text3); }
.pa-err { margin: 0; font-size: 11.5px; line-height: 1.6; color: var(--app-danger, #cf1322); }

.pa-sum { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.pa-sum div { border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 10px; background: var(--app-panel-2); }
.pa-sum b { display: block; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 17px; color: var(--app-text); }
.pa-sum b.ok { color: var(--app-ok); }
.pa-sum .run b { color: var(--app-warn-text); }
.pa-sum .fail b { color: var(--app-danger, #cf1322); }
.pa-sum span { font-size: 10.5px; color: var(--app-text3); }
.pa-list { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 5px; }
.pa-list li { font-size: 12px; line-height: 1.7; color: var(--app-text2); }
.pa-list b { color: var(--app-text); }
.pa-quote { border-left: 2px solid var(--app-accent); background: var(--app-panel-2); border-radius: 0 6px 6px 0;
  padding: 7px 10px; font-size: 11.5px; line-height: 1.65; color: var(--app-text3); white-space: pre-wrap; overflow-wrap: anywhere; }

.pa-foot { border-top: 1px solid var(--app-border); background: var(--app-panel-2);
  padding: 11px 18px calc(11px + env(safe-area-inset-bottom, 0px)); display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.pa-hint { font-size: 10.5px; color: var(--app-text3); min-width: 0; flex: 1 1 220px; }
.pa-acts { margin-left: auto; display: flex; gap: 7px; align-items: center; }
.pa-btn { border: 1px solid var(--app-border); background: var(--app-panel); border-radius: 7px; padding: 6px 12px;
  min-height: 30px; font-size: 12.5px; color: var(--app-text2); cursor: pointer; white-space: nowrap; }
.pa-btn:hover:not(:disabled) { border-color: var(--app-accent); color: var(--app-accent); }
.pa-btn.primary { background: var(--app-accent); border-color: var(--app-accent); color: #fff; }
.pa-btn.primary:hover:not(:disabled) { background: var(--app-accent-hover); border-color: var(--app-accent-hover); color: #fff; }
.pa-btn.warn { border-color: var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); font-weight: 600; }
.pa-btn:disabled { opacity: .45; cursor: not-allowed; }

@media (max-width: 720px) {
  .pa-mask { padding: 0; align-items: flex-end; }
  .pa-sheet { width: 100%; max-width: none; max-height: 78dvh; border-left: 0; border-right: 0; border-bottom: 0;
    border-radius: 16px 16px 0 0; animation: pa-up .22s ease-out; }
  @keyframes pa-up { from { transform: translateY(18px); opacity: .75; } to { transform: none; opacity: 1; } }
  .pa-handle { display: block; width: 36px; height: 4px; border-radius: 99px; background: var(--app-border-strong); margin: 7px auto 0; }
  .pa-head { padding: 9px 14px 9px; }
  .pa-title { font-size: 14.5px; }
  .pa-body { padding: 11px 14px; }
  .pa-foot { padding: 10px 14px calc(12px + env(safe-area-inset-bottom, 0px)); }
  .pa-hint { flex: 1 1 100%; }
  .pa-acts { margin-left: 0; width: 100%; justify-content: flex-end; }
  .pa-btn { min-height: 38px; }
  .pa-btn.primary { flex: 2; }
}
</style>
