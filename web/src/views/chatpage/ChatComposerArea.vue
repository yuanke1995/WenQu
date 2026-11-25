<template>
      <!-- 输入区：卡片外壳 / 正文与 chip 同流 / 工具条 / 拖拽落点全在 chat/ChatInput.vue，
           与公开分享页共用同一份实现（改一次两页同时生效）。这里只往插槽里填本页自己的东西：
           卡片外的一列（任务清单 / 目标条 / 插话队列）、智能体胶囊、模型与思考、三块面板，
           以及顶替态的两张卡（提问 / 计划确认栏）。 -->
      <ChatInput
        :ref="el => setRef('composerRef', el)" v-model="store.text" :resolve="store.chipOf" :placeholder="store.composerPlaceholder"
        :sending="store.loading" steerable :can-send="store.canSend" :stop-armed="store.escArmed"
        :send-title="store.goalTurnStreaming ? '插话：先回答你这句，目标随后自动继续' : ''"
        attach-title="添加：上传文件图片 / 引用资料 / 引用历史问答 / 快捷命令"
        :attach-on="(store.addOpen && !store.addOnEdit) || store.pickedSkills.length > 0"
        :replace-box="!!(store.pendingAsk || store.pendingPlan)"
        @attach="store.toggleAddPanel" @send="store.sendNow" @stop="store.stopNow" @files="store.onDropFiles"
        @paste="store.onComposerPaste" @keydown="store.onInputKeydown" @input="store.syncPanelQuery"
        @click="store.syncPanelQuery" @update:keys="onKeys" @chip-click="store.onChipClick" @ready="store.syncChips">
        <template #above>
          <!-- 工作区没准备好（未绑目录 / 执行器离线）：一句人话 + 一个能直接点的动作，不挡对话 -->
          <div v-if="store.workspaceSetupHint" class="local-ws-hint" role="status" aria-live="polite">
            <FolderOpenOutlined class="lwh-ic" />
            <span class="lwh-text">{{ store.workspaceSetupHint }}</span>
            <button v-if="!store.sessionWorkspace?.roots?.length" type="button" class="lwh-act"
                    @click="store.goBindWorkspace">去绑定文件夹</button>
            <button v-else type="button" class="lwh-act" :disabled="store.workspaceRefreshing"
                    @click="store.refreshSessionWorkspace">
              {{ store.workspaceRefreshing ? '检查中…' : '重新检查执行器' }}
            </button>
          </div>
          <!-- 附件/图片/技能/@ 引用/# 历史引用/计划模式全部内联进正文那一条流（见 chat/Composer.vue），
          这里不再各起一行 chip 轨；「引用只对本轮生效」这类说明收进 chip 的悬浮提示。 -->
          <!-- 任务清单：本会话最近一次 writeTodo 的整表快照。坐在输入框正上方、
               目标条之上——先看还差几步，再看整件事推到哪。这一轮挂着计划卡时不露：
               卡的步骤行读的就是这份清单，画两遍是同一份进度说两遍。全 done 后不立刻撤：
               留到下一轮开始给人看最后一眼这份清单（去留判据见 todoBarVisible）。原先挂在右侧
               状态栏，但那一栏 ≤1200 默认收起，长任务里最该常驻的一眼可见的东西不该在折叠栏里；
               提到输入区上方后，提问/计划面板顶替打字框时它也还在（面板顶替的是卡片，不是卡片
               之上这一列）。插话队列仍排在它下面：那是「还没发出去的话」，要离输入框最近。 -->
          <div v-if="store.todoBarVisible"
               class="todo-bar" :class="{ collapsed: !store.todoOpen }">
            <button type="button" class="tb-head" :title="store.todoOpen ? '收起任务清单' : '展开任务清单'"
                    :aria-label="store.todoOpen ? '收起任务清单' : '展开任务清单'"
                    @click="store.todoOpen = !store.todoOpen">
              <ordered-list-outlined class="tb-ico" />
              <span class="tb-label">任务清单</span>
              <span class="tb-count">{{ store.todoSummary }}</span>
              <caret-right-outlined class="tl-caret" :class="{ open: store.todoOpen }" />
            </button>
            <div v-show="store.todoOpen" class="tb-list">
              <div v-for="(t, ti) in store.sessionTodos.items" :key="ti" class="tb-item" :class="t.status">
                <!-- 进行中且本轮还在跑：框里换成转圈 + 整行底色 + 「执行中」文字。三者一起上是因为
                     单独任何一个都不够——14px 的框里换个符号在五条长句子中等同没换，而只有转圈没有
                     文字时用户会以为那是页面在加载。转圈仍要坐在这个框里：这一列其余行都是 14px 方框，
                     裸图标会在框的位置留一个洞，读起来像那一行缺了一格。本轮已停时不给转圈：清单是
                     快照，停掉的那一项再转下去就是撒谎。 -->
                <span class="tb-box" :class="{ run: t.status === 'doing' && store.loading }">
                  <loading-outlined v-if="t.status === 'doing' && store.loading" spin />
                  <template v-else>{{ t.status === 'done' ? '✓' : (t.status === 'failed' ? '✕' : '') }}</template>
                </span>
                <span class="tb-txt">{{ t.content }}</span>
                <span v-if="t.status === 'doing' && store.loading" class="tb-run-tag">执行中</span>
              </div>
            </div>
          </div>
          <!-- 目标条（DSH 式的一行目标 chip）：与任务清单同一族——独立成一张卡浮在输入卡片正上方，
               不进卡片内部。它讲的是「这件事做到哪了」，不是要发出去的话；提问/计划面板顶替打字框时
               卡片内容整块换掉，目标条不该跟着消失。顺序：任务清单 → 目标条 → 插话队列。
               只做三件事：看清目标（原文单行省略，跑了几轮/停因收进进度行）、改目标原文、
               停止(pause)/继续(resume)/删除(clear)。达成或取消后整条撤下（结果只在消息流里看，
               右栏不单列目标卡），其余状态一律只读。就地编辑在第一行长开：原文换成自适应高文本域
               （去框，与计划面板的自定义输入行同一口径），保存/取消用文字按钮——只读行那颗 ✕ 是
               「删除目标」，编辑行不能再拿 ✕ 表示「取消」，同一个字形两种含义会点错。
               进度与动作两行随头部折叠键收起，收起后整条只剩身份行。 -->
          <div v-if="store.goalBarVisible" class="goal-bar" :class="{ editing: store.goalEditing }" data-goal-bar>
            <!-- 取消二次确认就地长开（不弹层）：说清「取消 ≠ 删除」，点错一次也回得来 -->
            <div v-if="store.goalCancelArmed && store.goalOpen" class="gb-confirm">
              <div class="gb-confirm-t"><exclamation-circle-outlined /> 取消这次深度执行？</div>
              <div class="gb-confirm-s">停止自动推进，已经跑出的 <b>{{ store.goalRoundsCount }} 轮</b>、正文与过程都会保留。之后可以在消息流里查看，也可以重新设定目标。</div>
              <div class="gb-confirm-actions">
                <button type="button" class="gb-btn ghost" @click="set('goalCancelArmed', false)">继续推进</button>
                <button type="button" class="gb-btn danger" @click="store.confirmGoalCancel">取消执行</button>
              </div>
            </div>
            <!-- 第一行：身份（胶囊 + 原文 + 版本 + 状态） -->
            <div class="gb-row gb-identity">
              <span class="gb-pill" title="深度执行：助手会自己接着往下推进，直到宣布完成">
                <aim-outlined /><span>深度</span>
              </span>
              <span v-if="!store.goalEditing" class="gb-objective" :title="store.goalBarTitle">{{ store.sessionGoal.objective }}</span>
              <span v-else class="gb-objective one">修改这件事要做到什么</span>
              <span class="gb-ver" :title="'任务原文第 ' + store.goalVersionLabel + ' 版'">{{ store.goalVersionLabel }}</span>
              <span class="gb-state" :class="'st-' + store.goalStateClass" :title="store.goalBarMeta">{{ store.goalStateLabel }}</span>
              <!-- 折叠键只在只读态出：编辑整段必须摊开；收起后整条只剩这一行身份 -->
              <button v-if="!store.goalEditing" type="button" class="gb-fold" :class="{ open: store.goalOpen }"
                      :title="store.goalOpen ? '收起进度与操作' : '展开进度与操作'"
                      :aria-expanded="store.goalOpen ? 'true' : 'false'" aria-label="深度执行进度与操作"
                      @click="store.toggleGoalBar"><caret-right-outlined /></button>
            </div>
            <!-- 编辑态：就地长开，版本轨与生效时点一起说清 -->
            <div v-if="store.goalEditing" class="gb-edit">
              <a-textarea v-model:value="store.goalEdit.objective" class="gb-edit-textarea"
                          :bordered="false" :auto-size="{ minRows: 2, maxRows: 6 }" :maxlength="4000"
                          placeholder="写下这件事要做到什么"
                          title="Enter 保存 · Shift+Enter 换行 · Esc 取消"
                          @keydown="store.onGoalEditKeydown" />
              <div class="gb-edit-hint">
                <span class="gb-vrail"><span>{{ store.goalVersionLabel }}</span><span class="arr">→</span><span class="on">{{ store.goalNextVersionLabel }}</span></span>
                <span>下一轮起按新目标执行 · 已跑的 <b>{{ store.goalRoundsCount }} 轮</b> 保留</span>
                <span class="warn">正在跑的这一轮不受影响</span>
              </div>
              <div class="gb-edit-actions-row">
                <span class="gb-spacer"></span>
                <button type="button" class="gb-btn ghost" :disabled="store.goalEditBusy"
                        title="放弃这次修改，目标原文不变（Esc）" @click="set('goalEditing', false)">取消</button>
                <button type="button" class="gb-btn primary" :disabled="store.goalEditBusy || !store.goalEditSaveable"
                        :title="store.goalEditSaveable ? '保存为新版（Enter）' : '改几个字才能保存'"
                        @click="store.saveGoalEdit">{{ store.goalEditBusy ? '保存中…' : '保存为新版' }}</button>
              </div>
            </div>
            <!-- 第二行：进度（轮次 + 已跑时长 + 预算细条与百分比 + 停因/让位提示 + 过程入口）；
                 与动作行一起随身份行的折叠键收起 -->
            <div v-if="!store.goalEditing && store.goalOpen" class="gb-row gb-progress">
              <span class="gb-round">{{ store.goalRoundLine }}</span>
              <span v-if="store.goalElapsedText" class="gb-meta">{{ store.goalElapsedText }}</span>
              <span v-if="store.goalProgressPercent != null" class="gb-budget" :title="'预算已用 ' + store.goalProgressPercent + '%'">
                <span class="gb-track"><i :style="{ width: store.goalProgressPercent + '%' }" /></span>
                <span>预算 {{ store.goalProgressPercent }}%</span>
              </span>
              <span v-if="store.goalBarMeta" class="gb-hint">{{ store.goalBarMeta }}</span>
              <button v-if="store.goalDelivery" type="button" class="gb-more-link"
                      :title="'打开交付包：' + store.goalDelivery.fileCount + ' 件'"
                      @click="store.openDelivery(store.goalDelivery)">产出 {{ store.goalDelivery.fileCount }} 件 ›</button>
              <button type="button" class="gb-more-link" @click="store.openGoalViewer">查看过程 ›</button>
            </div>
            <!-- 第三行：动作（主行动靠右；取消与修改退到次级位）；删除记录只在终态卡上出现 -->
            <div v-if="!store.goalEditing && store.goalOpen" class="gb-row gb-actions-row">
              <button v-if="store.goalCanCancel" type="button" class="gb-btn text-danger"
                      title="取消执行：结束并留痕，历史与过程都保留（以后还能回看）"
                      @click="store.armGoalCancel">取消执行</button>
              <button v-if="store.goalCanEdit" type="button" class="gb-btn ghost" title="修改任务原文（保存后从这一版继续）"
                      @click="store.startGoalInlineEdit">修改任务</button>
              <span class="gb-spacer"></span>
              <button v-if="store.goalCanPause" type="button" class="gb-btn primary"
                      title="暂停执行：先停在这里，已经跑出来的部分都留着（可以再继续）"
                      @click="store.runGoalAction('pause')">暂停执行</button>
              <button v-else-if="store.goalCanResume" type="button" class="gb-btn primary"
                      :title="store.goalPreempted ? '立即继续：不等这一轮答完，现在就接着推进目标' : '继续执行：接着往下推'"
                      @click="store.runGoalAction('resume')">{{ store.goalPreempted ? '立即继续' : '继续执行' }}</button>
            </div>
            <span v-if="store.goalBarError" class="gb-error" role="alert">{{ store.goalBarError }}</span>
          </div>
          <!-- 插话队列：生成中在输入框里按发送＝先落这里、紧接着立刻插进本轮（与 DSH 同口径）。
          没送达的（这一轮正等人裁决/下一步还没到）留在这里并写明原因，本轮继续或下一轮开跑自动送达；
          送达前仍可改/删。刻意放在输入框正上方：它是"还没发出去的话"，离输入框最近才对。 -->
          <div v-if="store.steerQueue.length" class="steer-queue">
          <div v-for="(it, qi) in store.steerQueue" :key="qi" class="steer-item"
          :class="{ sending: it.sending, deferred: it.deferred, awaiting: it.awaiting, queued: it.queued, dragging: store.steerDragIdx === qi }"
          draggable="true"
          @dragstart="store.onSteerDragStart(qi, $event)"
          @dragover.prevent="store.onSteerDragOver(qi)"
          @dragend="store.onSteerDragEnd"
          @drop.prevent="store.onSteerDrop">
          <span class="steer-grip" title="拖动调整顺序">⠿</span>
          <span class="steer-txt">{{ it.text }}</span>
          <span v-if="store.steerExtras(it)" class="steer-extra">{{ store.steerExtras(it) }}</span>
          <span v-if="it.deferred" class="steer-state">本轮结束后自动发出</span>
          <span v-else-if="it.sending" class="steer-state">发送中…</span>
          <span v-else-if="it.awaiting" class="steer-state">已送出，等这一轮听到</span>
          <span v-else-if="it.queued" class="steer-state">{{ it.lastError || '排队中：等待送达' }}</span>
          <template v-if="!it.deferred && !it.sending && !it.awaiting">
          <button class="steer-act primary" type="button"
          title="立刻发给模型（纯文字在下一次生成前生效 · ⌘/Ctrl+Enter 同效）"
          @click="store.sendSteer(it)">立即发送</button>
          <button class="steer-act" type="button" title="取回输入框改" @click="store.editSteer(it)"><edit-outlined /></button>
          <button class="steer-act" type="button" title="丢弃这条" @click="store.dropSteer(it)"><delete-outlined /></button>
          </template>
          </div>
          </div>
        </template>
        <!-- 顶替态：挂起提问卡 / 待批计划卡整块顶替输入框（卡片样式仍由 ChatInput 提供） -->
        <template #replace>
          <!-- 智能体提问面板：当前会话有挂起提问时完全替换聊天输入框（模型在等答案，输入框此时不可用）。
          样式对齐「编号选项 + 自定义输入末项 + 键盘导航 + 忽略/提交」的提问卡；答复后面板撤下、
          输入框回归，问答记录以工具卡形态留在气泡原位 -->
          <div v-if="store.pendingAsk" class="ci-box askp-box" @keydown="store.onAskPanelKeydown">
          <!-- 顶替态没有输入框，chip 无处可画：待发图片/附件画在卡片里，否则拖入的图既看不见也不会随这张卡走 -->
          <PendingAtts v-if="store.pendingImages.length || store.pendingFiles.length" :images="store.pendingImages" :files="store.pendingFiles"
                       :fmt-size="store.fmtSize" @preview="i => store.openImages(store.pendingImages.map(p => p.dataUrl), i)"
                       @remove-image="store.removePendingImage" @remove-file="store.removePendingFile" />
          <div class="askp-head">
          <span class="askp-tag">{{ store.curAsk.topic || '向用户提问' }}</span>
          <span v-if="store.askQueueCount" class="askp-state" :title="'这一轮还有 ' + store.askQueueCount + ' 张卡要答，这张答完接着来'">还有 {{ store.askQueueCount }} 张待答</span>
          <span class="askp-q">{{ store.curAsk.question }}</span>
          <span v-if="store.curAsk.multi" class="askp-multi">可多选</span>
          <span v-if="store.pendingAsk.ask.answered" class="askp-state ok">已提交，模型继续中…</span>
          <span v-else-if="store.pendingAsk.ask.continued" class="askp-state">本轮已先继续 · 答完作为用户消息送达</span>
          <span v-else-if="store.askExpired" class="askp-state warn">已超时，智能体将自行判断…</span>
          <span v-if="store.askCountdownText" class="askp-timer" :class="{ warn: store.askExpired }">
          <clock-circle-outlined /> {{ store.askCountdownText }}
          </span>
          <span v-if="store.pendingAsk.ask.questions.length > 1" class="askp-pager">
          <button class="askp-page-btn" type="button" :disabled="store.askPage <= 0 || store.pendingAsk.ask.busy" @click="store.askPage--">‹</button>
          <span class="askp-page-num">{{ store.askPage + 1 }} / {{ store.pendingAsk.ask.questions.length }}</span>
          <button class="askp-page-btn" type="button" :disabled="store.askPage >= store.pendingAsk.ask.questions.length - 1 || store.pendingAsk.ask.busy" @click="store.askPage++">›</button>
          </span>
          </div>
          <div class="askp-opts">
          <button v-for="(op, oi) in store.curAsk.options" :key="oi" type="button"
          class="askp-opt" :class="{ sel: store.askOptionPicked(store.pendingAsk.ask, store.askPage, oi), 'k-hi': store.askHi === oi }"
          :disabled="store.pendingAsk.ask.busy || store.pendingAsk.ask.answered || store.askExpired" @click="store.onPick(oi)">
          <!-- 多选行前方块=勾选框（点一下勾上、再点取消）；单选题保持原来的「点选即换」，不额外画圈：
               一行只有一个可选项时，勾选框只是把同一件事画第二遍 -->
          <span v-if="store.curAsk.multi" class="askp-tick" :class="{ on: store.askOptionPicked(store.pendingAsk.ask, store.askPage, oi) }"></span>
          <span class="askp-no">{{ oi + 1 }}.</span>
          <span class="askp-kw">{{ store.askOptionParts(op).kw }}<span v-if="oi === 0" class="askp-rec">（推荐）</span></span>
          <span v-if="store.askOptionParts(op).rest" class="askp-rest">{{ store.askOptionParts(op).rest }}</span>
          </button>
          <div class="askp-opt askp-custom-row" :class="{ sel: store.askCustomPicked(store.pendingAsk.ask, store.askPage), 'k-hi': store.askHi === store.curAsk.options.length }">
          <span v-if="store.curAsk.multi" class="askp-tick" :class="{ on: store.askCustomPicked(store.pendingAsk.ask, store.askPage) }"></span>
          <span class="askp-no">{{ store.curAsk.options.length + 1 }}.</span>
          <input :ref="el => setRef('askCustomRef', el)" v-model="store.pendingAsk.ask.customs[store.askPage]" class="askp-input" :maxlength="2000"
          :disabled="store.pendingAsk.ask.busy || store.pendingAsk.ask.answered || store.askExpired"
          :placeholder="store.askCustomPlaceholder" @blur="store.commitCustomAt(store.askPage, false)" @keydown="store.onAskCustomKeydown" />
          </div>
          </div>
          <div class="askp-foot">
          <span class="askp-hint"><info-circle-outlined /> {{ store.askPanelHint }}</span>
          <span class="askp-actions">
          <!-- 停止 ≠ 忽略：忽略是「这一题我不答，你自行继续」，停止是「整轮叫停」。
          面板顶替了打字框，停止键（在发送位上）此时不存在，故在面板内给一个同义入口 -->
          <button class="app-btn ghost small p-stop" type="button"
          title="叫停整轮：不再执行任何工具，已生成的内容按「已停止本轮」保留。与「忽略」不同——忽略会让智能体带着「你没答」继续作答"
          @click="store.stopNow">停止本轮</button>
          <button v-if="!store.pendingAsk.ask.continued" class="app-btn ghost small" :disabled="store.pendingAsk.ask.busy || store.pendingAsk.ask.answered || store.askExpired" @click="store.ignoreAsk(store.pendingAsk)">忽略</button>
          <button class="app-btn small" :disabled="store.pendingAsk.ask.busy || store.pendingAsk.ask.answered || store.askExpired" @click="store.askSubmitAll(store.pendingAsk)">提交（{{ store.askAnsweredCount(store.pendingAsk.ask) }} / {{ store.pendingAsk.ask.questions.length }}）</button>
          </span>
          </div>
          </div>
          <!-- 计划确认栏：待批准时整块顶替打字框（模型在等裁决，此刻也没法发新消息）。
          版式对齐 plan-mode-ux：头部（执行计划 + 版本 + 状态 + 倒计时）→ 输入行（写意见 + 两个主按钮）→
          脚注（同轮升版说明 + 退出计划 / 转到对话修改 / 停止本轮）。计划正文在气泡里只读展示。 -->
          <div v-else class="ci-box planp-box">
          <PendingAtts v-if="store.pendingImages.length || store.pendingFiles.length" :images="store.pendingImages" :files="store.pendingFiles"
                       :fmt-size="store.fmtSize" @preview="i => store.openImages(store.pendingImages.map(p => p.dataUrl), i)"
                       @remove-image="store.removePendingImage" @remove-file="store.removePendingFile" />
          <div class="planp-head">
          <span class="planp-tag">执行计划</span>
          <span class="planp-ver">v{{ ((store.curPlanCard.versions || []).length) + 1 }}</span>
          <span v-if="store.planExpired" class="planp-state warn">已超时，本轮将停止</span>
          <span v-else class="planp-state">{{ store.curPlanCard.revising ? '修改中…' : '待确认' }}</span>
          <span v-if="store.planCountdownText" class="planp-timer" :class="{ warn: store.planExpired }">
          <clock-circle-outlined /> {{ store.planCountdownText }}
          </span>
          </div>
          <div class="planp-row">
          <a-textarea v-model:value="store.curPlanCard.editText" class="planp-input" :bordered="false"
          :auto-size="{ minRows: 1, maxRows: 6 }" :maxlength="8000"
          :disabled="store.curPlanCard.busy || store.planExpired"
          :placeholder="store.curPlanCard.restored ? '这张卡是恢复的，修改意见会作为新消息发出…' : '不满意？写下要改什么…（Enter 送出）'"
          @keydown="store.onPlanPanelKeydown" />
          <template v-if="!store.curPlanCard.restored">
          <button type="button" class="planp-btn warn"
          :disabled="store.curPlanCard.busy || store.planExpired || !(store.curPlanCard.editText || '').trim()"
          @click="store.revisePlanInPlace(store.pendingPlan, store.curPlanCard.editText)">提修改意见</button>
          <button type="button" class="planp-btn primary" :disabled="store.curPlanCard.busy || store.planExpired"
          @click="store.submitPlanApproval(store.pendingPlan, 'approve')">确认执行</button>
          </template>
          <button v-else type="button" class="planp-btn primary"
          :disabled="store.curPlanCard.busy || store.planExpired || !(store.curPlanCard.editText || '').trim()"
          @click="store.continuePlanConversation(store.pendingPlan, store.curPlanCard.editText)">转到对话修改</button>
          </div>
          <div class="planp-foot">
          <span class="planp-hint">修改会在同一轮生成新版本，不打断对话；「确认执行」后才开始动手</span>
          <span class="planp-links">
          <button v-if="!store.curPlanCard.restored" type="button" class="planp-link"
          :disabled="store.curPlanCard.busy || store.planExpired || !(store.curPlanCard.editText || '').trim()"
          @click="store.continuePlanConversation(store.pendingPlan, store.curPlanCard.editText)">转到对话修改</button>
          <button type="button" class="planp-link" :disabled="store.curPlanCard.busy || store.planExpired"
          @click="store.submitPlanApproval(store.pendingPlan, 'cancel')">退出计划</button>
          <button type="button" class="planp-link quiet" title="叫停整轮：不再执行任何工具"
          @click="store.stopNow">停止本轮</button>
          </span>
          </div>
          </div>
          <!-- 合成点击要 .stop：pickAttachments 里 input.click() 的点击事件会冒泡到 document，
          而 input 挂在计划面板容器下（不在 .ci-box/.mention-panel 内），外点关闭监听会误判成
          「点在面板外」把「+」面板关掉——表现为选择框还开着、背后面板没了，取消选择后面板也回不来 -->
        </template>
        <!-- 卡片内、正文之上：+ 面板 / @ 引用面板 / 斜杠面板 -->
        <!-- / 快捷命令面板（敲 / 唤起）：会话级动作，选中即执行 -->
        <div v-if="store.slashOpen" class="mention-panel">
        <div class="mention-head">
        <span class="mention-head-tag">/</span>
        <span class="mention-head-word" :class="{ dim: !store.slashQuery }">{{ store.slashQuery || '输入以筛选快捷命令' }}</span>
        <button class="app-icon-btn" title="关闭" @click="store.closeSlashPanel"><close-outlined /></button>
        </div>
        <div :ref="el => setRef('slashListEl', el)" class="mention-list">
        <div v-for="(c, ci) in store.slashFiltered" :key="c.key" class="mention-item"
        :class="{ hi: store.slashHi === ci }" @click="store.runSlashCommand(c)">
        <span class="mention-ava"><component :is="c.icon" /></span>
        <div class="mention-text">
        <span class="mention-name"><code v-if="c.cmd" class="slash-cmd">/{{ c.cmd }}</code>{{ c.name }}</span>
        <span class="mention-desc">{{ c.desc }}</span>
        </div>
        </div>
        <div v-if="!store.slashFiltered.length" class="mention-empty">没有匹配的命令</div>
        </div>
        <div class="mention-foot">↑↓ 选择 · Enter 确认 · Esc 关闭</div>
        </div>
        <!-- # 历史引用面板（敲 # 唤起）：勾选本会话历史问答，随本轮请求前置给模型 -->
        <div v-if="store.histOpen" class="mention-panel">
        <div class="mention-head">
        <span class="mention-head-tag">#</span>
        <span class="mention-head-word" :class="{ dim: !store.histQuery }">{{ store.histQuery || '输入以筛选历史问答' }}</span>
        <button class="app-icon-btn" title="关闭" @click="store.closeHistPanel"><close-outlined /></button>
        </div>
        <div :ref="el => setRef('histListEl', el)" class="mention-list">
        <div v-for="(m, hi2) in store.histCandidates" :key="m.messageId" class="mention-item"
        :class="{ on: store.isHistPicked(m.messageId), hi: store.histHi === hi2 }" @click="store.pickHistoryRefByClick(m)">
        <span class="mention-ava" :class="m.role === 'user' ? 'hist-ava-q' : 'hist-ava-a'">
        {{ m.role === 'user' ? '问' : '答' }}
        </span>
        <div class="mention-text">
        <span class="mention-name">{{ store.histItemTitle(m) }}</span>
        <span class="mention-desc">{{ m.role === 'user' ? '你的提问' : 'AI 的回答' }}{{ m.time ? ' · ' + store.fmtMsgTime(m.time) : '' }}</span>
        </div>
        <check-outlined v-if="store.isHistPicked(m.messageId)" class="mention-check" />
        </div>
        <div v-if="!store.histCandidates.length" class="mention-empty">
        {{ store.histPool.length ? '没有匹配的历史问答' : '本会话还没有可引用的历史问答' }}
        </div>
        </div>
        <div class="mention-foot">↑↓ 选择 · Enter 确认 · Esc 关闭 | # 引用历史问答（仅本轮，最多 10 条）</div>
        </div>
        <!-- 「+」面板（分层菜单）：输入框唯一的候选面板。两个入口同一块面板——
        点「+」先出一级轻菜单（上传文件/图片一击直达，引用/历史/命令进二级列表），
        输入框敲 @ 也开这里（直接落在「引用」二级，筛选词实时取「@ 到光标」之间的正文，见 syncPanelQuery）。
        就地编辑卡的「+」也开这里：同一块面板按写入目标（addTarget）决定选中结果落到
        主输入框还是编辑卡的随行内容上，编辑态下整块 Teleport 进卡内的挂载点。
        技能不占一级菜单的行，它是「引用」页的第 4 个子页签——和库/文档/智能体一样只对当轮生效。
        深度执行在这里只占快捷命令列表的一行（/deep），不另设页签——它的开启态也不在工具条上，
        就是正文里那颗「计划」chip（删掉 chip 等于关闭）。
        键盘：↑↓ 选择 · Enter 确认/进入 · Tab 进/退层级 · Esc 关闭（由一级进入的二级先退回一级）。
        行式交互与样式复用 .mention-panel / .mention-item -->
        <Teleport v-if="store.addOpen" :to="store.EDIT_ADD_MOUNT" :disabled="!store.addOnEdit">
        <div class="mention-panel" @keydown="store.onPanelNavKeydown">
        <div class="mention-head add-head">
        <button v-if="store.addTab !== 'root'" class="app-icon-btn add-back" title="返回上一层" @click="store.backToRoot"><left-outlined /></button>
        <span class="add-title">{{ store.addTab === 'root' ? '添加到本轮' : store.ADD_TITLES[store.addTab] }}</span>
        <button class="app-icon-btn add-close" title="关闭" @click="store.closeAddPanel"><close-outlined /></button>
        </div>
        <!-- 引用页的四类候选各自计数（技能=可选清单长度，勾选数看行上的对勾）；子页签只换列表，不重置搜索词 -->
        <div v-if="store.addTab === 'store.ref'" class="mention-tabs add-subtabs">
        <button v-for="s in store.REF_SUB_TABS" :key="s.key" class="mention-tab" :class="{ on: store.mentionTab === s.key }"
        type="button" @click="store.switchRefTab(s.key)">
        <component :is="s.icon" /> {{ s.label }} {{ s.count() }}
        </button>
        </div>
        <div v-if="store.addSearchable" class="add-filter-row">
        <input :ref="el => setRef('addSearchEl', el)" v-model="store.addQuery" class="add-filter" type="search"
        :placeholder="store.addSearchHint" autocomplete="off" />
        </div>
        <div :ref="el => setRef('addListEl', el)" class="mention-list">
        <!-- 一级菜单：上传行点了直接弹系统文件框；其余行进二级。kbd=主输入框里对应的触发符，
        把鼠标流和键盘流接到同一个地方（编辑卡那份不显示）；badge=已选/生效数（这一轮会带上什么） -->
        <template v-if="store.addTab === 'root'">
        <div v-for="(r, ri) in store.addRootRows" :key="r.key" class="mention-item"
        :class="{ hi: store.addHi === ri }" @click="r.run()">
        <span class="mention-ava"><component :is="r.icon" /></span>
        <div class="mention-text">
        <span class="mention-name">{{ r.name }}<span v-if="r.badge()" class="add-root-badge">{{ r.badge() }}</span></span>
        <span class="mention-desc">{{ r.desc }}</span>
        </div>
        <span v-if="r.kbd" class="add-kbd">{{ r.kbd }}</span>
        </div>
        </template>
        <template v-else-if="store.addTab === 'store.ref'">
        <!-- 技能清单不走库/文档那次请求，别被它的「加载中」挡住 -->
        <div v-if="store.mentionLoading && store.mentionTab !== 'skill'" class="mention-empty">加载中…</div>
        <template v-else-if="store.mentionTab === 'kb'">
        <div v-for="(k, ki) in store.addRefList" :key="k.id" class="mention-item"
        :class="{ on: store.isAddMentioned('kb', k.id), hi: store.addHi === ki }" @click="store.confirmRefRow('kb', k)">
        <span class="mention-ava"><database-outlined /></span>
        <div class="mention-text">
        <span class="mention-name">{{ k.name }}</span>
        <span class="mention-desc">{{ k.desc || (k.docCount ? k.docCount + ' 篇文档' : '') }}</span>
        </div>
        <check-outlined v-if="store.isAddMentioned('kb', k.id)" class="mention-check" />
        </div>
        <div v-if="!store.addRefList.length" class="mention-empty">
        {{ store.mentionKbs.length ? '没有匹配的知识库' : '没有可见的知识库' }}
        </div>
        </template>
        <template v-else-if="store.mentionTab === 'agent'">
        <div v-for="(a, ai) in store.addRefList" :key="a.id" class="mention-item"
        :class="{ on: store.isAddMentioned('agent', a.id), hi: store.addHi === ai }" @click="store.confirmRefRow('agent', a)">
        <AgentAvatar class="mention-ava-agent" :agent="a" :size="22" />
        <div class="mention-text">
        <span class="mention-name">{{ a.name }}</span>
        <span v-if="a.desc" class="mention-desc">{{ a.desc }}</span>
        </div>
        <check-outlined v-if="store.isAddMentioned('agent', a.id)" class="mention-check" />
        </div>
        <div v-if="!store.addRefList.length" class="mention-empty">
        {{ store.mentionAgents.length ? '没有匹配的智能体' : '没有可见的智能体' }}
        </div>
        </template>
        <template v-else-if="store.mentionTab === 'skill'">
        <div v-if="!store.skillList.length" class="mention-empty">还没有可用技能</div>
        <div v-for="(s, si) in store.addRefList" :key="s.name" class="mention-item"
        :class="{ on: store.isAddSkillOn(s.name), hi: store.addHi === si, 'mi-off': s.selectable === false }"
        @click="store.confirmRefRow('skill', s)">
        <span class="mention-ava skill-ava" :style="store.skillAvaStyle(s.name)">{{ (s.name || '技').slice(0, 1) }}</span>
        <div class="mention-text">
        <span class="mention-name">{{ s.name }}
        <span v-if="s.disabled" class="skill-flag off">已停用</span>
        <span v-else-if="s.source === 'workspace'" class="skill-flag">工作区·只读</span>
        </span>
        <span v-if="s.description" class="mention-desc">{{ s.description }}</span>
        </div>
        <button class="app-link-btn skill-doc-btn" @click.stop="store.skillDoc = s.name">查看正文</button>
        <check-outlined v-if="store.isAddSkillOn(s.name)" class="mention-check" />
        </div>
        <div v-if="store.skillList.length && !store.addRefList.length" class="mention-empty">没有匹配的技能</div>
        </template>
        <template v-else>
        <div v-for="(d, di) in store.addRefList" :key="d.id" class="mention-item"
        :class="{ on: store.isAddMentioned('doc', d.id), hi: store.addHi === di }" @click="store.confirmRefRow('doc', d)">
        <span class="mention-ava"><file-text-outlined /></span>
        <div class="mention-text">
        <span class="mention-name">{{ d.fileName }}</span>
        <span class="mention-desc">{{ store.mentionDocStatus(d) }}</span>
        </div>
        <check-outlined v-if="store.isAddMentioned('doc', d.id)" class="mention-check" />
        </div>
        <div v-if="!store.addRefList.length" class="mention-empty">
        {{ store.mentionDocs.length ? '没有匹配的文档' : '没有可见的文档' }}
        </div>
        </template>
        </template>
        <!-- 历史引用：勾选不关面板（多选），与键盘流 # 面板同一份交互；
        候选按写入目标取——编辑卡那份要排掉正在编辑的这一问（引用自己没意义） -->
        <template v-else-if="store.addTab === 'hist'">
        <div v-for="(m, mi) in store.addHistCandidates" :key="m.messageId" class="mention-item"
        :class="{ on: store.isAddHistPicked(m.messageId), hi: store.addHi === mi }" @click="store.toggleAddHistoryRef(m)">
        <span class="mention-ava" :class="m.role === 'user' ? 'hist-ava-q' : 'hist-ava-a'">
        {{ m.role === 'user' ? '问' : '答' }}
        </span>
        <div class="mention-text">
        <span class="mention-name">{{ store.histItemTitle(m) }}</span>
        <span class="mention-desc">{{ m.role === 'user' ? '你的提问' : 'AI 的回答' }}{{ m.time ? ' · ' + store.fmtMsgTime(m.time) : '' }}</span>
        </div>
        <check-outlined v-if="store.isAddHistPicked(m.messageId)" class="mention-check" />
        </div>
        <div v-if="!store.addHistCandidates.length" class="mention-empty">
        {{ store.addHistPool.length ? '没有匹配的历史问答' : '本会话还没有可引用的历史问答' }}
        </div>
        </template>
        <!-- 快捷命令：会话级动作，选中即执行（生成中拦截见 runSlashCommand） -->
        <template v-else>
        <div v-for="(c, ci) in store.addCmdList" :key="c.key" class="mention-item"
        :class="{ 'mi-off': store.loading && !c.noGate, hi: store.addHi === ci }" @click="store.runCmdFromAddPanel(c)">
        <span class="mention-ava"><component :is="c.icon" /></span>
        <div class="mention-text">
        <span class="mention-name"><code v-if="c.cmd" class="slash-cmd">/{{ c.cmd }}</code>{{ c.name }}</span>
        <span class="mention-desc">{{ c.desc }}</span>
        </div>
        </div>
        <div v-if="!store.addCmdList.length" class="mention-empty">没有匹配的命令</div>
        </template>
        </div>
        <div class="mention-foot">{{ store.addFootHint }}</div>
        </div>
        </Teleport>
        <!-- 正文与附件/图片/技能/@ 引用/# 历史引用/计划模式在同一条文字流里：
        chip 是文档里的节点，能排在字与字之间，全选复制也就带得上它们（见 chat/Composer.vue） -->
        <template #left-lead>
          <!-- 会话级权限档位：仅可查看 / 工作区内修改 / 完全权限。改的是这条会话，
               下一次工具调用起生效。每条会话都有工作区后端（显式工作区用其目录；「会话」与
               未挂工作区的会话用本机执行器目录，离线时是云端沙盒），所以不按工作区有无隐藏，
               否则用户在没有工作区标识的会话里永远改不了档位。
               胶囊与菜单都只报具体档位，不再有「跟随智能体」这一项：没在本会话选过时，
               报的就是这位智能体自己的档位（由后端下发的档位名册 + 智能体档位算出）。 -->
          <a-dropdown v-if="store.currentSessionId && !store.memberViewRoot" :trigger="['click']" placement="topLeft">
            <button class="perm-pill" :class="{ on: !!store.sessionPermission && store.effectivePermission !== 'autonomous', danger: store.effectivePermission === 'autonomous' }"
                    type="button"
                    :title="'权限：' + store.permissionLabel">
              <ExclamationCircleOutlined v-if="store.effectivePermission === 'autonomous'" />
              <SafetyCertificateOutlined v-else />
              <span v-if="!store.isNarrow" class="perm-pill-name">{{ store.permissionLabel }}</span>
            </button>
            <template #overlay>
              <a-menu class="perm-menu" :selected-keys="[store.effectivePermission]"
                      @click="({ key }) => store.pickPermission(key)">
                <a-menu-item v-for="p in store.permissionPresets" :key="p.code">
                  <div class="perm-mi-t">{{ p.label }}</div>
                  <div class="perm-mi-d">{{ p.summary }}</div>
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <a-dropdown v-if="!store.memberViewRoot" :open="store.agentPickerOpen" :trigger="['click']" placement="topLeft">
          <button class="agent-pill" :class="{ on: !!store.currentAgentId, open: store.agentPickerOpen, 'as-icon': store.isNarrow }"
          title="选择作答的智能体：换人立即生效（下一问起就是它）；不挑就沿用本会话上一问的智能体，新会话用默认助手。模型在右侧选择">
          <AgentAvatar v-if="store.currentAgent" :agent="store.currentAgent" :size="16" />
          <!-- 窄屏只留头像：智能体名 + 模型名会把工具条撑破，完整名称由 title 与下拉面板承载 -->
          <span v-if="!store.isNarrow" class="agent-pill-name">{{ store.currentAgentName }}</span>
          <down-outlined v-if="!store.isNarrow" class="agent-pill-caret" />
          </button>
          <template #overlay>
          <div class="agent-menu">
          <div class="agent-menu-head">
          <span>选择智能体</span>
          <span class="agent-menu-hint">本轮生效</span>
          </div>
          <div class="agent-menu-list">
          <div v-if="!store.hasDefaultAgent" class="agent-mi" :class="{ active: !store.currentAgentId }" @click="store.pickAgent('')">
          <span class="agent-mi-ava"><robot-outlined /></span>
          <div class="agent-mi-text">
          <span class="agent-mi-name">默认（全局配置）</span>
          </div>
          <check-outlined v-if="!store.currentAgentId" class="agent-mi-check" />
          </div>
          <div v-for="a in store.agentList" :key="a.id" class="agent-mi"
          :class="{ active: store.currentAgentId === a.id }" @click="store.pickAgent(a.id)">
          <AgentAvatar :agent="a" :size="26" style="margin-top:1px" />
          <div class="agent-mi-text">
          <span class="agent-mi-name">
          {{ a.name }}
          <span v-if="a.isDefault" class="agent-mi-badge">默认</span>
          </span>
          <span v-if="a.description" class="agent-mi-desc">{{ a.description }}</span>
          </div>
          <check-outlined v-if="store.currentAgentId === a.id" class="agent-mi-check" />
          </div>
          <div v-if="!store.agentList.length" class="agent-mi-empty">还没有智能体，去「管理智能体」新建一个</div>
          </div>
          <div v-if="store.isAdmin" class="agent-menu-foot" @click="store.goManageAgents">
          <setting-outlined />
          <span>管理智能体</span>
          </div>
          </div>
          </template>
          </a-dropdown>
        </template>
        <template #right>
          <!-- 上下文容量圆环：本轮真实 prompt 占窗口比（与右栏容量卡同源数据），悬浮弹明细卡；会话尚无对话（无落库 tokens）时不显示。
          触屏无 hover，额外挂 click/@keydown（role/tabindex 只在触屏下加，避免改变宽屏的无障碍语义与 Tab 序） -->
          <span v-if="store.ctxTokens && store.ctxCapData.window > 0" class="store-ring" :class="store.ctxRingLevel" aria-label="上下文容量"
          :role="store.isCoarse ? 'button' : null" :tabindex="store.isCoarse ? 0 : null"
          @mouseenter="store.showCtxCap($event.currentTarget)" @mouseleave="store.hideCtxCap()"
          @click="store.isCoarse && store.showCtxCap($event.currentTarget)"
          @keydown.enter="store.isCoarse && store.showCtxCap($event.currentTarget)">
          <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
          <circle class="store-ring-bg" cx="10" cy="10" r="7.5" fill="none" stroke-width="2.5" />
          <circle class="store-ring-val" cx="10" cy="10" r="7.5" fill="none" stroke-width="2.5"
          stroke-linecap="round" :stroke-dasharray="store.ctxRingDash" transform="rotate(-90 10 10)" />
          </svg>
          </span>
          <!-- 深度思考设置入口：宽屏挂在模型下拉行上（悬浮哪行弹哪行，见下方 ModelSelect 的 option-hover）；
          触屏无 hover，改由这个常驻按钮点开底部 sheet。
          显示与否只由 v-if="store.isCoarse" 决定，CSS 侧不另设 @media(hover:hover) 规则——
          两者判据不同源会在「coarse 且 hover:hover」的设备上把入口藏没。 -->
          <button v-if="store.isCoarse" class="think-entry" :class="{ on: store.deepThinkOn }" type="button"
          title="深度思考设置" @click="store.toggleThinkSheet">
          <thunderbolt-outlined />
          <span class="think-entry-txt">{{ store.deepThinkOn ? '思考·开' : '思考' }}</span>
          </button>
          <!-- 深度思考设置挂到下拉模型行上：悬浮哪行就弹那个模型的设置面板（Teleport 在模板末尾），
          档位点选即时写入按模型 localStorage 记忆；对生效模型下一轮发送立即生效，
          对其它模型则先记住、选中该模型时生效 -->
          <ModelSelect v-model="store.currentOverrideModel" type="chat" pill
          :issue="store.effectiveModelIssue"
          :placeholder="store.effectiveModelLabel || '选择模型'"
          :display-:ref="el => setRef('effectiveModel', el)"
          :badge="store.pillLevelBadge" :badge-title="store.pillLevelTitle"
          :search="false"
          :compact="store.isNarrow"
          :width="190"
          :run-settings="!store.isCoarse"
          panel-title="运行设置"
          :panel-sub="store.runSettingsModelLabel">
            <!-- 运行设置尾栏：与模型列表同一张下拉。设计稿要的就是「模型 + 思考 + 上下文 + 检索」一次改完，
                 不再另开一层悬浮面板 -->
            <template #runSettings>
              <div class="rs-panel" @mousedown.stop @click.stop>
                <div class="rs-sec">
                  <div class="rs-label">思考强度</div>
                  <div class="rs-seg">
                    <button v-for="opt in store.runSettingsLevels" :key="opt.value" type="button" class="rs-chip"
                            :class="{ on: opt.value === store.runSettingsLevel }"
                            :disabled="store.runSettingsCaps.locked || store.loading"
                            :title="opt.label" @click="store.setThinkLevel(opt.value, store.runSettingsModel)">
                      {{ store.chipLabelOf(opt.value) }}
                    </button>
                  </div>
                  <div class="rs-hint">{{ store.runSettingsHint }}</div>
                </div>
                <div v-if="store.runSettingsWindow" class="rs-row">
                  <span class="rs-k">上下文窗口</span><span class="rs-v">{{ store.runSettingsWindow }}</span>
                </div>
                <div v-if="store.runSettingsUsage" class="rs-row">
                  <span class="rs-k">本轮占用</span><span class="rs-v">{{ store.runSettingsUsage }}</span>
                </div>
                <div class="rs-row">
                  <span class="rs-k">本轮检索</span><span class="rs-v" :title="store.kbScopeText">{{ store.kbScopeText }}</span>
                </div>
                <div class="rs-foot">
                  <span class="rs-foot-note">思考档按模型记住</span>
                  <button type="button" class="rs-default" :disabled="store.isRunModelDefault" @click.stop="store.setRunModelDefault">
                    {{ store.isRunModelDefault ? '已是默认' : '设为默认' }}
                  </button>
                </div>
              </div>
            </template>
          </ModelSelect>
        </template>
        <!-- 快捷键说明从 placeholder 里下沉到这里：卡片之下、同一内边距列里；
             页面若在外面另起一行，居中基准会和输入卡片错开 -->
        <template #below>
          <div v-if="!store.isNarrow" class="chat-hints">
            <span><kbd>Enter</kbd> 发送</span>
            <span><kbd>Shift</kbd>+<kbd>Enter</kbd> 换行</span>
            <span><b>@</b> 引用资料</span>
            <span><b>/</b> 命令</span>
            <span><b>#</b> 历史问答</span>
            <span class="chat-hints-sp">生成中按 Enter 可插话</span>
          </div>
        </template>
      </ChatInput>

</template>

<script setup>
import { inject } from 'vue'
// 父页 provide('chatCtx')：一个 reactive 上下文（含 ref 与动作）
const store = inject('chatCtx')
const set = (k, v) => { store[k] = v }
const setRef = (k, el) => { store[k] = el }
</script>

<style scoped src="./ChatComposerArea.css"></style>
