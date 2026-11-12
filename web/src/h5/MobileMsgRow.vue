<template>
  <!-- 移动壳的消息行：与 PC 版同源的数据（引擎的 messages 数组），不同的呈现——
       正文与过程分离：正文只渲染最终答案、永远连续；过程收在顶部可展开的过程块里；操作行不靠 hover（触屏没有 hover）：
       点一下气泡把它「选中」，选中态或最新一条才显示操作行。 -->
  <!-- data-row-index 是会话内查找与「新问题置顶」共用的行契约：
       定位按它算出来，不做 "第 N 个子节点" 这种易碎假设；PC 版消息行带同一个属性，
       所以 src/chat/useChatSearch.js 两份壳体共用一套 Range/Highlight 逻辑。 -->
  <div class="mrow" :class="[m.role === 'user' ? 'is-user' : 'is-ai', { active }]"
       :data-row-index="index" :data-role="m.role === 'user' ? 'user' : 'ai'">
    <div class="mrow-inner" @click="onRowTap">
      <!-- ==================== 用户消息 ==================== -->
      <template v-if="m.role === 'user'">
        <!-- 目标指令回声的身份胶囊：压在气泡上方，不占正文那行（与 PC 共用外壳同一形状、
             与输入框上方那条目标条同源）。/deep 那串字收进悬浮行；状态词只在这一条就是当前目标时才给 -->
        <div v-if="m.goalCommand || m.goalPending" class="cmd-pill"
             :class="cmdState ? 'st-' + cmdState.cls : ''" :title="goalEchoTipOf(goal, m.goalCommand)">
          <aim-outlined class="cmd-ic" /><span>深度</span><span v-if="cmdState"> · {{ cmdState.label }}</span>
        </div>
        <div class="ububble">
          <div v-if="m.content" class="u-text">{{ m.content }}</div>
          <div v-if="m.images && m.images.length" class="u-imgs">
            <!-- 定尺缩略图（.u-imgs img 88×88）+ 延迟加载：手机上翻开一条长会话，
                 不该一上来就把几十张上传图全拉下来 -->
            <img v-for="(u, ii) in m.images" :key="ii" :src="resolveImg(u)" alt="图片"
                 loading="lazy" decoding="async" @click.stop="$emit('preview', m.images.map(resolveImg), ii)">
          </div>
          <div v-if="m.attachments && m.attachments.length" class="chips">
            <template v-for="(a, ai) in m.attachments" :key="ai">
              <span v-if="isPastedText(a)" class="mp-card" @click.stop="$emit('paste-view', a)">
                <file-text-outlined class="mp-ic" />
                <span class="mp-txt">
                  <span class="mp-name">{{ pasteTitle(a.name) }}</span>
                  <span class="mp-sub">{{ pasteSub(a) }}</span>
                </span>
              </span>
              <span v-else class="chip chip-file">
                <paper-clip-outlined />{{ a.name }}<em v-if="a.size"> · {{ fmtSize(a.size) }}</em>
              </span>
            </template>
          </div>
          <div v-if="m.skills && m.skills.length" class="chips">
            <span v-for="s in m.skills" :key="s" class="chip chip-skill">技能 · {{ s }}</span>
          </div>
          <div v-if="m.mentions && m.mentions.length" class="chips">
            <span v-for="(mm, mi) in m.mentions" :key="mi" class="chip chip-mention">{{ mm.name || mm.id }}</span>
          </div>
        </div>
        <!-- 叫停且本轮没产出回答：说明记在「这一问」上，与 PC 端同一口径（不造空回答气泡） -->
        <div v-if="m.endReason === 'stopped'" class="u-stopped">
          <pause-circle-outlined /> 本轮已停止 · 未产生回答
        </div>
        <!-- 断线超过宽限被系统收束：不是用户点停的，文案与图标都要区分开 -->
        <div v-if="m.endReason === 'interrupted'" class="u-stopped">
          <exclamation-circle-outlined /> 本轮已中断 · 未产生回答
        </div>
        <!-- 这一轮被挡下或跑挂了、且什么都没产出：原因挂在「这一问」上（与 PC 端同一份库里的同一句），
             重发入口本就在下面的动作行里，不另摆一颗按钮 -->
        <div v-if="m.errorCard" class="u-stopped u-fail" :title="m.errorCard.message">
          <close-circle-outlined /> {{ errorBrief(m.errorCard.message, m.errorCard.kind) }}
        </div>
        <!-- 目标终态卡：挂在「设为目标」那条回声下（goalId 认），与 PC 同一份组件 -->
        <GoalTerminalCard v-if="terminalGoal" :goal="terminalGoal"
                          :title="goalTerminalTitleOf(terminalGoal)"
                          :note="goalTerminalNoteOf(terminalGoal)"
                          :version="goalVersionLabelOf(terminalGoal)"
                          :has-delivery="!!goalDelivery"
                          @view="$emit('goal-view')"
                          @delivery="$emit('goal-delivery')"
                          @edit="$emit('goal-edit')"
                          @restart="$emit('goal-restart', terminalGoal.objective)"
                          @delete="$emit('goal-delete')" />
        <div v-if="showActions" class="u-actions">
          <template v-if="showUserActs">
            <div v-if="m.variantCount > 1" class="ver-switch">
              <button class="ver-btn" :disabled="(m.variantIndex || 1) <= 1 || variantSwitching" @click.stop="$emit('switch-version', index, -1)">‹</button>
              <span class="ver-idx">{{ m.variantIndex || 1 }}/{{ m.variantCount }}</span>
              <button class="ver-btn" :disabled="(m.variantIndex || 1) >= m.variantCount || variantSwitching" @click.stop="$emit('switch-version', index, 1)">›</button>
            </div>
            <button class="act-btn" title="复制问题" aria-label="复制问题" @click.stop="$emit('copy-user', m)"><copy-outlined /></button>
            <button class="act-btn" title="编辑此问题，从这一轮重新生成" aria-label="编辑此问题，从这一轮重新生成" @click.stop="$emit('edit', index)"><edit-outlined /></button>
          </template>
          <span v-if="m.time" class="act-time">{{ fmtMsgTime(m.time) }}</span>
        </div>
      </template>

      <!-- ==================== 助手消息 ==================== -->
      <template v-else>
        <div v-if="showAgentTag && debugDisplay" class="agent-tag">
          <AgentAvatar class="agent-tag-ava" :agent="badgeOf(m.agentId, m.agentName)" :size="14" /> 由「{{ m.agentName }}」回答
        </div>

        <!-- 整轮用时那一条：它同时是过程块的开关。跑着的时候过程自动展开，答完收起只留正文 -->
        <TurnBar :m="m" mobile />

        <!-- 正文 / 过程分离（统一正文流，与 PC 同构）：正文＝整段 content，工具调用只作过程步骤、
             不切正文；过程全部收在顶部过程块，永远不插进正文 -->
        <div class="md bubble-md" :data-msg-index="index">
          <div v-if="turnOpen(m) && turnProcessRows(m).length" class="tl-turn-process">
          <template v-for="(seg, si) in turnProcessRows(m)" :key="si">
            <!-- 思考段（簇外）：这一轮在想什么就长在哪一行，只有正在长的那一段自动展开。
                 被过程簇收进去的那些不走这一支——落在帽体清单里所属那一步的展开详情内（与 PC 同一份 clusterLiveRows） -->
            <div v-if="seg.kind === 'think'" class="card think-card">
              <button class="card-head" type="button" :aria-expanded="thinkSegOpen(m, seg)" @click.stop="toggleThinkSeg(m, seg)">
                <span class="card-title">深度思考</span>
                <!-- 卡头右侧挂这一段思考的开头几个字（与 PC 同构）：收着也读得出它在想什么，
                     一行、按可用宽度省略，不把 40px 的卡头撑成两行；标题与提要间点一颗分隔点 -->
                <template v-if="thinkPeek(m, seg)">
                  <span class="live-sep" aria-hidden="true"></span>
                  <span class="card-peek">{{ thinkPeek(m, seg) }}</span>
                </template>
                <span v-if="thinkGrowing(m, seg)" class="spinner" aria-label="进行中"></span>
                <caret-right-outlined class="caret" :class="{ open: thinkSegOpen(m, seg) }" />
              </button>
              <div v-show="thinkSegOpen(m, seg)" class="card-body">
                <AnswerBody class="think-md" :content="thinkSlice(m, seg)" :viewer="false"
                            :streaming="thinkGrowing(m, seg)" />
              </div>
            </div>
            <!-- 过程簇头（与 PC 同构，DSH ProcessGroupHeader 同款文案法则）：
                 行首活动图标（展开让位下开三角），文字=做过什么的罗列 / 跑动中带扫光的进行时 -->
            <div v-else-if="seg.kind === 'cluster'" class="card proc-cluster" :class="{ open: clusterOpen(m, seg) }">
              <button class="card-head tl-head" type="button" @click.stop="toggleCluster(m, seg)">
                <span class="tl-cluster-ic">
                  <span class="tl-ic-a">
                    <close-circle-outlined v-if="clusterHasError(seg) && !clusterRunning(seg)" class="ic-err" />
                    <component :is="clusterIconOf(seg)" v-else />
                  </span>
                  <span class="tl-ic-c"><down-outlined /></span>
                </span>
                <span class="card-title" :class="{ shimmer: clusterRunning(seg) || (m.loading && seg.grow) }">
                  {{ clusterLiveText(m, seg) || clusterTitle(seg) }}<template v-if="clusterRunning(seg) && runBoardText(m)"> · {{ runBoardText(m) }}</template>
                </span>
              </button>
              <!-- 帽体：跑轮中自动开并贴底跟随、用户点开停顶部。固定高度裸排清单（180px 内滚、边缘渐隐），
                   与 PC 同构；点行在帽内展开该步详情（入参/输出/错误）。 -->
              <div v-if="clusterOpen(m, seg)" v-follow-bottom="!!(m.loading && seg.grow)" v-edge-fade class="cluster-live">
                <template v-for="(lr, li) in clusterLiveRows(seg)" :key="li">
                  <div v-if="lr.t" class="live-row"
                       :class="{ run: lr.t.status === 'start' && !lr.t.denied, err: lr.t.status === 'error', off: lr.t.denied, open: lr.t._open }"
                       @click.stop="lr.t._open = !lr.t._open">
                    <span class="tl-cluster-ic live-ic">
                      <span class="tl-ic-a">
                        <loading-outlined v-if="lr.t.status === 'start' && !lr.t.denied" spin />
                        <check-outlined v-else-if="lr.t.status === 'done'" />
                        <close-circle-outlined v-else-if="lr.t.status === 'error'" class="ic-err" />
                        <pause-circle-outlined v-else />
                      </span>
                      <span class="tl-ic-c"><down-outlined /></span>
                    </span>
                    <span class="live-name" :title="lr.t.hint">{{ toolLabel(lr.t.name, lr.t.label) }}</span>
                    <template v-if="toolBrief(lr.t)">
                      <span class="live-sep" aria-hidden="true"></span>
                      <span class="live-brief">{{ toolBrief(lr.t) }}</span>
                    </template>
                    <span v-if="lr.t.denied" class="live-flag">被挡下</span>
                  </div>
                  <div v-if="lr.t && lr.t._open && (lr.t.args || liveOutput(lr.t) || lr.t.error || (lr.notes && lr.notes.length))" class="live-detail" @click.stop>
                    <template v-if="lr.notes && lr.notes.length">
                      <div class="live-think">
                        <div class="live-think-label">思考</div>
                        <div v-for="(nn, ni) in lr.notes" :key="'n' + ni" class="think-seg">
                          <NoteBody :m="m" :note="nn" always bare :viewer="false"
                                    @citation="onBodyCitation" @preview="onBodyPreview" />
                        </div>
                      </div>
                      <div v-if="lr.t.args || liveOutput(lr.t) || lr.t.error" class="io-div" aria-hidden="true"></div>
                    </template>
                    <div v-if="lr.t.args" class="io-sec">
                      <div class="io-label">入参</div>
                      <div class="io-body">
                        <pre class="io-pre">{{ prettyIo(lr.t.args) }}</pre>
                        <ToolSpillButton v-if="lr.t.argsSpillId" :id="lr.t.argsSpillId" :tool="lr.t.name" kind="入参" />
                      </div>
                    </div>
                    <div v-if="lr.t.args && liveOutput(lr.t)" class="io-div" aria-hidden="true"></div>
                    <div v-if="liveOutput(lr.t)" class="io-sec">
                      <div class="io-label">{{ lr.t.status === 'start' ? '实时输出' : (lr.t.result ? '输出' : '输出（执行期）') }}</div>
                      <div class="io-body">
                        <pre class="io-pre" :class="{ live: lr.t.status === 'start' }">{{ liveOutput(lr.t) }}</pre>
                        <ToolSpillButton v-if="lr.t.spillId" :id="lr.t.spillId" :tool="lr.t.name" kind="输出" />
                      </div>
                    </div>
                    <div v-if="(lr.t.args || liveOutput(lr.t)) && lr.t.error" class="io-div" aria-hidden="true"></div>
                    <div v-if="lr.t.error" class="io-sec">
                      <div class="io-label">错误</div>
                      <div class="io-body">
                        <pre class="io-pre io-err">{{ lr.t.error }}</pre>
                        <ToolSpillButton v-if="lr.t.spillId" :id="lr.t.spillId" :tool="lr.t.name" kind="输出" />
                      </div>
                    </div>
                  </div>
                  <!-- 尾行：转圈只在真的还在长时出现；整组可点开看正文（与 PC 同一份口径） -->
                  <template v-if="!lr.t">
                    <div class="live-row" :class="{ run: tailNotesGrowing(m, lr.tailNotes), open: tailOpen(m, seg) }">
                      <button type="button" class="live-tail-btn" @click.stop="toggleTail(m, seg)">
                        <span class="tl-cluster-ic live-ic">
                          <span class="tl-ic-a">
                            <loading-outlined v-if="tailNotesGrowing(m, lr.tailNotes)" spin />
                            <check-outlined v-else />
                          </span>
                          <span class="tl-ic-c"><down-outlined /></span>
                        </span>
                        <span class="live-name" :class="{ shimmer: tailNotesGrowing(m, lr.tailNotes) }">{{ tailNotesTag(lr.tailNotes) }}</span>
                        <!-- 尾行右边同样跟上这一组的开头几个字（与 PC 同构），中间点一颗分隔点 -->
                        <template v-if="tailNotesPeek(m, lr.tailNotes)">
                          <span class="live-sep" aria-hidden="true"></span>
                          <span class="live-peek">{{ tailNotesPeek(m, lr.tailNotes) }}</span>
                        </template>
                      </button>
                    </div>
                    <div v-if="tailOpen(m, seg)" class="live-tail-body">
                      <div v-for="(nn, ni) in lr.tailNotes" :key="'t' + ni" class="think-seg">
                        <NoteBody :m="m" :note="nn" always bare :viewer="false"
                                  @citation="onBodyCitation" @preview="onBodyPreview" />
                      </div>
                    </div>
                  </template>
                </template>
              </div>
            </div>
            <!-- 过程独白段（簇外）：标题行常驻、内容可折叠。被簇收进去的落在所属那一步的展开详情里 -->
            <div v-else-if="seg.kind === 'process'" class="card proc-card">
              <button class="card-head" type="button" @click.stop="toggleProc(m, seg)">
                <span class="card-title">执行过程</span>
                <caret-right-outlined class="caret" :class="{ open: procOpen(m, seg) }" />
              </button>
              <div v-show="procOpen(m, seg)" class="card-body proc-body">{{ procSlice(m, seg) }}</div>
            </div>
            <div v-else-if="seg.kind === 'group'" class="tl-group">
              <button v-if="groupHasBar(seg)" class="card-head" type="button" @click.stop="seg.tools[0]._groupOpen = !seg.tools[0]._groupOpen">
                <span v-if="groupRunning(seg)" class="spinner"></span>
                <check-outlined v-else-if="!groupHasError(seg)" class="ic-ok" />
                <close-circle-outlined v-else class="ic-err" />
                <span class="card-title">执行了 {{ executedCount(seg.tools) }} 个操作</span>
                <span v-if="runBoardText(m)" class="run-board">{{ runBoardText(m) }}</span>
                <NoteTag v-for="(nt, ni) in seg.notes" :key="ni" :m="m" :note="nt" />
                <caret-right-outlined class="caret" :class="{ open: seg.tools[0]._groupOpen }" />
              </button>
              <!-- 有组条时全文挂在组条下面（批次折叠着也点得开）；没有组条时挂在它所属那张卡下面 -->
              <NoteBody v-if="groupHasBar(seg)" v-for="(nt, ni) in seg.notes" :key="'note' + ni"
                        :m="m" :note="nt" :viewer="false" @citation="onBodyCitation" @preview="onBodyPreview" />
              <div v-if="seg.tools.length === 1 || seg.tools[0]._groupOpen" class="tl-group-body">
                <div v-for="(t, ti) in seg.tools" :key="ti" class="card tool-card" :data-spawn="spawnAnchorOf(t)"
                     :class="{ run: t.status === 'start', err: t.status === 'error' }">
                  <button class="card-head" type="button" @click.stop="toggleToolCard(t)">
                    <span v-if="t.status === 'start'" class="spinner"></span>
                    <check-outlined v-else-if="t.status === 'done'" class="ic-ok" />
                    <close-circle-outlined v-else class="ic-err" />
                    <span class="card-title">{{ toolLabel(t.name, t.label) }}</span>
                    <code v-if="toolBrief(t)" class="tool-brief">{{ toolBrief(t) }}</code>
                    <!-- 派遣那一支跑到第几步：子轮过程帧原样转发（subagent_step），只活一次不落库 -->
                    <span v-if="t._live && t.status === 'start'" class="tool-live" :title="t._live">{{ t._live }}</span>
                    <span v-if="t.attempts > 1" class="card-dim">重试 {{ t.attempts - 1 }} 次</span>
                    <span v-if="fanHasBoard(t.fanOut)" class="card-dim">{{ fanHead(t) }}</span>
                    <span v-if="fanRun(t)" class="fan-run">{{ fanRun(t) }}</span>
                    <!-- 这一轮的思考/独白并到步的行尾（与 PC 同形）：标签只写类别名，量级展开后自己看 -->
                    <NoteTag v-if="ti === 0 && !groupHasBar(seg)" v-for="(nt, ni) in seg.notes" :key="ni" :m="m" :note="nt" />
                    <caret-right-outlined class="caret" :class="{ open: toolCardOpen(t) }" />
                  </button>
                  <NoteBody v-if="!groupHasBar(seg)" v-for="(nt, ni) in seg.notes" :key="'note' + ni"
                            :m="m" :note="nt" :viewer="false" @citation="onBodyCitation" @preview="onBodyPreview" />
                  <!-- askUser 问答记录：可折叠（默认展开），与 PC 同构 -->
                  <AskRecordCard v-if="t.name === 'askUser' && t.status !== 'start'" :t="t" />
                  <SubAgentChatCard v-if="(t.subChat || (t._steps && t._steps.length)) && toolCardOpen(t)" :t="t" :sources="m.sources"
                                    :session-id="sessionId" :turn-id="m.turnId || ''"
                                    :viewer="false" @citation="onBodyCitation" @preview="onBodyPreview" />
                  <div v-show="t._open" class="card-body">
                    <!-- 脚本扇出清单（P3b 过程下钻）：子调用不各占一张顶层卡片，结构收在这里 -->
                    <template v-if="fanHasBoard(t.fanOut)">
                      <div class="io-label">{{ fanTitle(t.fanOut) }}</div>
                      <div class="fan-list">
                        <div v-for="(s, si) in t.fanOut.items" :key="si" class="fan-row"
                             :class="{ err: s.status === 'error', run: s.status === 'start' }">
                          <span class="fan-idx">{{ si + 1 }}</span>
                          <span class="fan-name">{{ toolLabel(s.name, s.label) }}</span>
                          <span class="fan-dur">{{ fanRowState(s) }}</span>
                          <span class="fan-brief">{{ s.brief }}</span>
                        </div>
                        <div v-if="t.fanOut.omitted" class="card-dim">另有 {{ t.fanOut.omitted }} 次未列出</div>
                      </div>
                      <div v-if="fanBudget(t.fanOut)" class="fan-budget">{{ fanBudget(t.fanOut) }}</div>
                    </template>
                    <template v-if="t.args">
                      <div class="io-label">入参</div><pre class="io-pre">{{ prettyIo(t.args) }}</pre>
                      <ToolSpillButton v-if="t.argsSpillId" :id="t.argsSpillId" :tool="t.name" kind="入参" />
                    </template>
                    <template v-if="liveOutput(t)">
                      <div class="io-label">{{ t.status === 'start' ? '实时输出' : (t.result ? '输出' : '输出（执行期）') }}</div>
                      <pre class="io-pre" :class="{ live: t.status === 'start' }">{{ liveOutput(t) }}</pre>
                      <ToolSpillButton v-if="t.spillId" :id="t.spillId" :tool="t.name" kind="输出" />
                    </template>
                    <template v-if="t.error">
                      <div class="io-label">错误</div><pre class="io-pre io-err">{{ t.error }}</pre>
                      <ToolSpillButton v-if="t.spillId" :id="t.spillId" :tool="t.name" kind="输出" />
                    </template>
                  </div>
                </div>
              </div>
            </div>
          </template>
          </div>
          <!-- 正文按时间线还原：插话气泡长在模型真听到那句的位置，而不是整段正文的头顶（与 PC 同构） -->
          <template v-for="row in turnBodyRows(m)" :key="row.key">
            <div v-if="row.kind === 'steer'" class="tl-steer"><span class="tl-steer-txt">{{ row.text }}</span></div>
            <AnswerBody v-else class="tl-answer" :content="row.content"
                        :images="m.images" :sources="m.sources" :msg-index="index"
                        runnable :session-id="sessionId"
                        :streaming="!!(m.loading && !m.failed && row.last)"
                        :viewer="false" @citation="onBodyCitation" @preview="onBodyPreview" />
          </template>
        </div>

        <!-- 智能体提问（askUser）的「待答」态不在气泡里渲染：移动壳与 PC 同语义，把底部输入卡
             整块替换成提问面板（见 MobileChatPage 的 m-askp）；答复后问答记录以工具卡形态留在本气泡 -->

        <!-- 错误卡：保留半程内容，给分类文案 + 重新生成 -->
        <div v-if="m.errorCard" class="err-card" :class="{ net: m.errorCard.kind === 'interrupted' }">
          <div class="err-head"><close-circle-outlined /> {{ errorBrief(m.errorCard.message, m.errorCard.kind) }}</div>
          <div v-if="m.errorCard.kind === 'interrupted'" class="err-tip">重新生成会重跑本轮并重新计费。</div>
          <button class="btn-ghost" @click.stop="$emit('retry', index)"><redo-outlined /> 重新生成</button>
        </div>


        <!-- 执行计划卡（人在回路）：与 PC 同一份组件；裁决入口在底部确认栏，这里只读留痕。
             .stop：卡在卡片里的「查看完整计划/修改记录」不该顺手把整行选中 -->
        <div v-if="m.planCard" @click.stop>
          <PlanCard :card="m.planCard" :deep="!!goal" :sid="sessionId" :pkey="m.planCard.id || index"
                    :progress="progress" :artifact-count="(m.artifacts || []).length"
                    :adjusts="adjusts"
                    @open="(v, tab) => emit('open-plan', { m, index, version: v, tab })"
                    @adjust="emit('adjust')"
                    @locate-replanned="emit('locate-replanned')"
                    @locate-source="emit('locate-source')" />
        </div>

        <!-- 工具审批（人在回路）：贴着被审批的那一步；通知深链也定位到这一张 -->
        <div v-for="a in (m.approvals || [])" :key="a.id" class="approval" :data-approval-id="a.id">
          <div class="approval-head">
            <exclamation-circle-outlined class="approval-warn" />
            <span class="approval-lead">需要你批准</span>
            <span v-if="a.subagent && a.subagent.agent" class="approval-src">{{ a.subagent.agent }}</span>
            <span class="approval-tool">{{ toolLabel(a.tool, a.label) }}</span>
            <code v-if="a.label && a.label !== a.tool" class="approval-raw">{{ a.tool }}</code>
            <span class="approval-clock" :class="{ over: approvalLeftOf(a) <= 0 }">
              <clock-circle-outlined />
              {{ approvalLeftOf(a) > 0 ? '剩 ' + approvalLeftOf(a) + ' 秒' : '已超时' }}
            </span>
          </div>
          <p v-if="a.reason" class="approval-why">{{ a.reason }}</p>
          <pre v-if="approvalArgsText(a.args)" class="io-pre">{{ approvalArgsText(a.args) }}</pre>
          <div class="approval-actions">
            <span class="approval-hint">{{ approvalLeftOf(a) > 0 ? '批准后立即执行这一步' : '已超时，本轮将按拒绝继续' }}</span>
            <button class="btn-ghost" :disabled="a.busy || approvalLeftOf(a) <= 0" @click.stop="$emit('approve', m, a, false)">拒绝</button>
            <button class="btn-primary" :disabled="a.busy || approvalLeftOf(a) <= 0" @click.stop="$emit('approve', m, a, true)">批准执行</button>
          </div>
        </div>

        <!-- 生成态：本轮还没有产物、正在生成/交付文件时先占位（与 PC 同一份组件） -->
        <ArtifactPendingCard v-if="m.role === 'ai' && pending" :pending="pending" />
        <!-- 交付包：这一轮的产物收进一张卡（与 PC 同一份 DeliveryCard），第一件产物到达即成卡。
             mobile：缩略不跑网页 iframe，只做图片与文本。 -->
        <DeliveryCard v-if="delivery" :delivery="delivery" mobile :fresh="fresh"
                      @view="$emit('delivery-view', delivery)"
                      @zip="$emit('delivery-zip', delivery)" />

        <!-- 每轮文件改动卡：与 PC 气泡同一组件同一数据源（行点击进预览台 diff 档，浮层两端通吃） -->
        <TurnChangesCard v-if="m.role === 'ai' && m.changes" class="tcc-mobile"
                         :changes="m.changes" :message-id="m.messageId || ''" />

        <!-- 降级/警告：与 PC 那份 DegradationBar 同一套判据（同一屏只出一份，失败原因由错误卡说） -->
        <div v-if="degradationItems(m).length" class="warn-bar">
          <span v-for="(d, di) in degradationItems(m)" :key="di">{{ d.msg }}</span>
        </div>
        <div v-else-if="degradationWarn(m)" class="warn-bar">{{ degradationWarn(m) }}</div>

        <!-- 检索行：折叠看检索词与引用片段 -->
        <div v-if="retrievalLineTitle(m) || toolSearchQueries(m).length || (m.sources && m.sources.length)" class="card">
          <button class="card-head" type="button" @click.stop="m.rtOpen = !m.rtOpen">
            <search-outlined class="ic-accent" />
            <span class="card-title">{{ retrievalLineTitle(m) }}</span>
            <caret-right-outlined class="caret" :class="{ open: m.rtOpen }" />
          </button>
          <div v-show="m.rtOpen" class="card-body rt-body">
            <div v-if="m.retrieved && m.retrieved.terms && m.retrieved.terms.length" class="rt-terms">检索词：{{ (m.retrieved.terms || []).join('、') }}</div>
            <div v-if="toolSearchQueries(m).length" class="rt-terms">精确检索：{{ toolSearchQueries(m).join('；') }}</div>
            <div v-for="(s, si) in (m.sources || [])" :key="si" class="rt-ref" @click.stop="$emit('source', s)">
              <span class="rt-ref-tag">[{{ s.ref }}]</span>
              <span v-if="s.origin === 'WEB'" class="rt-origin">联网</span>
              <span v-else-if="s.origin === 'MCP'" class="rt-origin">MCP</span>{{ sourceName(s) }}
              <div v-if="s.snippet" class="rt-snip">{{ s.snippet }}</div>
            </div>
          </div>
        </div>

        <!-- 追问建议：与 PC 同口径，只在最新一轮出现（更早那几轮的建议已被后面的对话取代） -->
        <div v-if="lastRound && m.related && m.related.length" class="related">
          <span class="card-dim">接下来可以：</span>
          <button v-for="(q, qi) in m.related" :key="qi" class="related-tag" type="button" @click.stop="$emit('ask', q)">{{ q }}</button>
        </div>

        <!-- 操作行：选中态或最新一条可见（触屏没有 hover） -->
        <div v-if="showActions" class="ai-actions">
          <div v-if="(m.versions && m.versions.length > 1) || m.variantCount > 1" class="ver-switch">
            <button class="ver-btn" :disabled="!canSwitchPrev(m)" @click.stop="$emit('switch-version', index, -1)">‹</button>
            <span class="ver-idx">{{ verLabel(m) }}</span>
            <button class="ver-btn" :disabled="!canSwitchNext(m)" @click.stop="$emit('switch-version', index, 1)">›</button>
          </div>
          <template v-if="m.messageId">
            <button class="act-btn" title="复制" aria-label="复制" @click.stop="$emit('copy', m)"><copy-outlined /></button>
            <button class="act-btn" :class="{ on: m.fb === 1 }" :disabled="m.fb != null" title="有帮助" aria-label="有帮助" @click.stop="$emit('feedback', m, 1)"><like-outlined /></button>
            <button class="act-btn" :class="{ on: m.fb === 0 }" :disabled="m.fb != null" title="没帮助" aria-label="没帮助" @click.stop="$emit('feedback', m, 0)"><dislike-outlined /></button>
            <button class="act-btn" :disabled="loading" title="重新生成" aria-label="重新生成" @click.stop="$emit('retry', index)"><reload-outlined /></button>
            <!-- 单轮操作（PC 是 a-dropdown）：导出这轮 / 加入评测集 / 检索调试 / 删除本轮。
                 触屏没有 hover 菜单，收进底部 sheet（MobileRoundSheet） -->
            <button class="act-btn" title="更多操作" aria-label="更多操作" @click.stop="$emit('more', index)"><more-outlined /></button>
          </template>
          <span v-if="usageCtxTokens(m.tokens)" class="act-time">上下文{{ m.tokens.outputIsReal ? ' ' : ' ≈' }}{{ fmtTokens(usageCtxTokens(m.tokens)) }} tokens</span>
          <span v-if="m.time" class="act-time">{{ fmtMsgTime(m.time) }}</span>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import {
  CaretRightOutlined, CheckOutlined, CloseCircleOutlined, CopyOutlined, EditOutlined, LikeOutlined,
  DislikeOutlined, ReloadOutlined, RedoOutlined, RobotOutlined, ThunderboltOutlined, SearchOutlined,
  FileTextOutlined, PaperClipOutlined, ExclamationCircleOutlined, MoreOutlined,
  QuestionCircleOutlined, CheckCircleOutlined, PauseCircleOutlined, AimOutlined,
  LoadingOutlined, DownOutlined, ApiOutlined, BulbOutlined, ClockCircleOutlined
} from '@ant-design/icons-vue'
import { resolveImg } from '../utils/markdown'
import { approvalArgsText, approvalLeftSec } from '../chat/approval'
// 跑轮中簇帽体的贴底跟随与上下边缘渐隐（与 PC 共用同一份指令）
import { vFollowBottom, vEdgeFade } from '../chat/followBottom'
import AnswerBody from '../components/AnswerBody.vue'
import { fmtTokens } from '../utils/token'
import {
  procOpen, toggleProc, procSlice,
  turnProcessRows, turnBodyRows, turnOpen,
  thinkSegOpen, toggleThinkSeg, thinkSlice, thinkPeek, thinkGrowing, toolLabel, toolBrief, spawnAnchorOf,
  clusterOpen, toggleCluster, clusterTools, clusterRunning, clusterHasError, clusterProcCount,
  clusterAutoOpen, clusterLiveRows, clusterTitle, clusterLiveText, clusterHeadKind, toolDuration,
  groupHasBar,
  tailNotesTag, tailNotesPeek, tailNotesGrowing, tailOpen, toggleTail,
  prettyIo, liveOutput, groupRunning, groupHasError,
  fanHasBoard, fanHead, fanTitle, fanRowState, fanBudget, fanRun, runBoardText,
  toolCallsView, executedCount, toolSearchQueries, retrievalLineTitle,
  fmtMsgTime, fmtSize, errorBrief, sourceName, canSwitchPrev, canSwitchNext, verLabel, agentBadgeOf,
  pasteTitle, pasteSub, isPastedText, usageCtxTokens, goalEchoStateOf, goalEchoTipOf,
  goalTerminalCardOf, goalTerminalTitleOf, goalTerminalNoteOf, goalVersionLabelOf,
  degradationItems, degradationWarn,
  toolCardOpen, toggleToolCard
} from '../chat/projections'
import AskRecordCard from '../components/AskRecordCard.vue'
import TurnChangesCard from '../components/TurnChangesCard.vue'
import NoteTag from '../components/NoteTag.vue'
import NoteBody from '../components/NoteBody.vue'
import PlanCard from '../components/PlanCard.vue'
import TurnBar from '../components/TurnBar.vue'
import SubAgentChatCard from '../components/SubAgentChatCard.vue'
import ToolSpillButton from '../components/ToolSpillButton.vue'
import GoalTerminalCard from '../components/GoalTerminalCard.vue'
import DeliveryCard from '../components/DeliveryCard.vue'
import ArtifactPendingCard from '../components/ArtifactPendingCard.vue'
import AgentAvatar from '../components/AgentAvatar.vue'

// 簇头活动图标（与 PC 同位）：类别 → 图标，收起时常显、展开让位给下开三角（触屏无 hover）
const CLUSTER_ICONS = { search: SearchOutlined, tools: ApiOutlined, thinking: BulbOutlined }
const clusterIconOf = seg => CLUSTER_ICONS[clusterHeadKind(seg)] || BulbOutlined

const props = defineProps({
  m: { type: Object, required: true },
  index: { type: Number, required: true },
  /** 选中态（点气泡切换）：选中或最新一条才显示操作行 */
  active: { type: Boolean, default: false },
  /** 是否最新一条 AI 消息 */
  isLast: { type: Boolean, default: false },
  /** 是否最新一轮（最后一条 AI 消息，含进行中）：「接下来可以」只在这一轮渲染，与 PC 同口径 */
  lastRound: { type: Boolean, default: false },
  /** 排障显示开关（归属徽标/派遣提示，engine 的 debugDisplayVisible） */
  debugDisplay: { type: Boolean, default: false },
  /** 沙盒运行等富渲染按钮需要会话上下文 */
  sessionId: { type: String, default: '' },
  /** 本轮是否正在生成（禁用重新生成入口） */
  loading: { type: Boolean, default: false },
  variantSwitching: { type: Boolean, default: false },
  /** 会话当前目标：回声胶囊的状态词只认它那一条 goalId（与 PC 共用外壳同一判据） */
  goal: { type: Object, default: null },
  /** 这一条回答的交付包（父级按 deliveryCardOfMessage 取；null=不支卡） */
  delivery: { type: Object, default: null },
  /** 这一轮正在生成的产物占位（父级按 artifactPendingOf 取；null=不显示） */
  pending: { type: Object, default: null },
  /** 交付卡里是否有刚刚实时到达的产物（入场只播一次） */
  fresh: { type: Boolean, default: false },
  /** 当前目标的交付包（终态卡上的「查看交付」入口用） */
  goalDelivery: { type: Object, default: null },
  /** 审批倒计时基准（引擎每秒推进的 approvalNow）：与 PC 的 approvalLeftSecOf 同一份口径 */
  approvalNow: { type: Number, default: 0 },
  /** 计划卡执行进度（planExecProgress 的产物；无清单/不属于本轮时 null） */
  progress: { type: Object, default: null },
  /** 本轮调整记录（执行中插话） */
  adjusts: { type: Array, default: () => [] }
})
const emit = defineEmits(['activate', 'preview', 'paste-view', 'source', 'retry', 'edit', 'feedback', 'approve',
  'switch-version', 'ask', 'copy', 'copy-user', 'more', 'open-plan',
  'adjust', 'locate-replanned', 'locate-source',
  'goal-view', 'goal-edit', 'goal-restart', 'goal-delete', 'goal-delivery',
  'delivery-view', 'delivery-zip'])

/** 终态卡只挂在那条「设为目标」的回声下：同一个 goalId 且当前目标已到终态 */
const terminalGoal = computed(() => {
  if (props.m.role !== 'user' || !props.m.goalCommand || !props.goal) return null
  if (props.goal.goalId !== props.m.goalCommand) return null
  return goalTerminalCardOf(props.goal) ? props.goal : null
})

const approvalLeftOf = card => approvalLeftSec(card, props.approvalNow)

// 操作行的出现条件（触屏没有 hover）：选中那条、或最后一条回答。
// 目标指令回声撤的只是里面的动作键（见 showUserActs），行本身照挂——时间要看得见
const showActions = computed(() => props.active || (props.m.role === 'ai' && props.isLast))
// 目标指令回声不挂复制/编辑重发：那句「把这件事做到什么算完」是一条指令，
// 不是一等回答的提问（与 PC 端 msg-edit-row 撤动作同一条判据）。
// goalPending 是同一条判据的空窗补丁：乐观气泡先落地、goalId 要等建目标的响应回来才补上
const showUserActs = computed(() => !props.m.goalCommand && !props.m.goalPending)
// 回声胶囊的状态词（身份词「目标」恒在，状态词只在这一条就是当前目标时才给）
const cmdState = computed(() => goalEchoStateOf(props.goal, props.m.goalCommand))
// 归属徽标去重（与 PC 的 showAgentTag 同口径，但简化：只在首条或归属变化时显示）
const showAgentTag = computed(() => {
  const m = props.m
  return m.role === 'ai' && !!m.agentName
})
// 徽标头像数据源：可用智能体清单由引擎持有（同 provide 'wqChat' 的其它 sheet 同一取法）；
// 查不到时 agentBadgeOf 返回 null → AgentAvatar 落回默认机器人，与改动前一致
const engine = inject('wqChat', null)
const badgeOf = (agentId, agentName) => agentBadgeOf(engine?.agentList?.value, agentId, agentName)

const onRowTap = () => { if (!props.active) emit('activate', props.index) }

/**
 * 正文点击的复制/沙盒运行/角标/图片四分支已在 chat/answerViewer 的 handleBodyClick 里收口
 * （与 PC 同一份）。移动壳只是把后两分支接到自己的底部抽屉与简化灯箱上 —— 所以这里
 * 只剩两个转发函数，不再自己写一遍委托逻辑。
 */
const onBodyCitation = src => emit('source', src)
const onBodyPreview = (urls, index) => emit('preview', urls, index)
</script>

<style scoped>
/* 顶到容器上沿时留一点呼吸：新问题置顶与查找跳转都走 scrollIntoView({block:'start'})，
   没有 scroll-margin 会让气泡齐刷刷压在顶部边框上（桌面版靠尾随留白天然有这个间隙）。 */
.mrow { display: flex; margin-bottom: 12px; scroll-margin-top: 8px; }
.mrow.is-user { justify-content: flex-end; }
.mrow-inner { max-width: 100%; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.mrow.is-ai .mrow-inner { width: 100%; }

/* ---- 用户消息 ---- */
.ububble {
  align-self: flex-end; max-width: 86%;
  background: var(--app-accent-weak); color: var(--app-text);
  border: 1px solid var(--app-accent-border);
  border-radius: 14px 14px 4px 14px;
  padding: 10px 12px;
}
.u-text { white-space: pre-wrap; word-break: break-word; font-size: 15px; line-height: 1.6; }
/* 目标指令回声的身份胶囊（与 PC 共用外壳同一形状、与输入框上方那条目标条同源）：
   坐在气泡上方，`.mrow-inner` 的 gap 已经管间距，这里不再另加 margin */
.cmd-pill {
  align-self: flex-end; display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; line-height: 20px; padding: 0 8px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent); font-weight: 500; white-space: nowrap;
}
.cmd-ic { flex: none; font-size: 12px; }
/* 已暂停：用户自己踩的刹车，用警示色而不是「已终结」的红（与目标条 .gb-state 同一档一色） */
.cmd-pill.st-paused { background: var(--app-warn-weak); color: var(--app-warn-text); }
.cmd-pill.st-achieved { background: var(--app-ok-weak); color: var(--app-ok); }
.cmd-pill.st-ended { background: var(--app-danger-weak); color: var(--app-danger-text); }
.u-imgs { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.u-imgs img { width: 88px; height: 88px; object-fit: cover; border-radius: 8px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.chip { font-size: 12px; padding: 3px 8px; border-radius: 999px; background: var(--app-panel); border: 1px solid var(--app-border); color: var(--app-text2); display: inline-flex; align-items: center; gap: 4px; }
.chip-skill { background: var(--app-info-weak); border-color: var(--app-info-border); color: var(--app-accent); }
.chip-file em { font-style: normal; color: var(--app-text3); }
/* 长文本粘贴卡片：图标块 + 名称与体积/字数两行，点一下看全文（与输入区待发送卡片同形） */
.mp-card {
  display: flex; align-items: center; gap: 8px; max-width: 220px;
  padding: 6px 10px; border-radius: 10px;
  background: var(--app-panel); border: 1px solid var(--app-border);
}
.mp-ic {
  flex: none; width: 30px; height: 30px; border-radius: 7px; font-size: 15px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-accent);
}
.mp-txt { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.mp-name { font-size: 13px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mp-sub { font-size: 11px; color: var(--app-text3); }
.u-actions, .ai-actions {
  display: flex; align-items: center; gap: 2px; flex-wrap: wrap;
  justify-content: flex-end; padding: 0 2px;
}
/* 本轮被叫停且没产出回答：问题下方一行安静说明（与时间戳同一弱化口径） */
.u-stopped {
  display: flex; align-items: center; gap: 4px; justify-content: flex-end;
  margin-top: 2px; font-size: 11px; color: var(--app-text3); user-select: none;
}
/* 失败那一行占同一位置，但要人看见：红色 + 最多两行（触屏比 PC 窄，一句指引常常放不下） */
.u-fail { color: var(--app-danger-text); text-align: right; }
.ai-actions { justify-content: flex-start; }
.act-btn {
  width: 34px; height: 34px; border: none; background: transparent; border-radius: 8px;
  color: var(--app-text3); font-size: 15px; display: inline-flex; align-items: center; justify-content: center;
  touch-action: manipulation;
}
.act-btn:active { color: var(--app-accent); background: var(--app-accent-weak); }
.act-btn.on { color: var(--app-accent); }
.act-btn:disabled { opacity: .45; }
.act-time { font-size: 11px; color: var(--app-text3); margin-left: 4px; }

/* ---- 助手消息 ---- */
.agent-tag { display: inline-flex; align-items: center; gap: 5px; align-self: flex-start; font-size: 12px; color: var(--app-text3); }
/* 徽标内头像：AgentAvatar 自带圆角块，只保证不被 flex 压扁（与 PC 的 .agent-tag-ava 同口径） */
.agent-tag-ava { flex: none; }
.agent-tag-desc { color: var(--app-text3); opacity: .85; }
.bubble-md {
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 4px 14px 14px 14px; padding: 10px 12px;
  font-size: 15px; line-height: 1.7; min-width: 0; overflow-wrap: anywhere;
}
/* 顶部过程块（统一正文流）：过程与正文彻底分开，展开时整块出现在正文上方 */
.tl-turn-process { margin: 0 0 10px; }
/* 插话段：右对齐、与用户气泡同一形状；长在正文流里模型听到它的那一处，不随过程块收起 */
.tl-steer { display: flex; justify-content: flex-end; margin: 8px 0; }
.tl-steer-txt {
  max-width: 82%; padding: 8px 12px; border-radius: 12px; line-height: 1.6; font-size: 15px;
  background: var(--app-panel-2); color: var(--app-text);
  white-space: pre-wrap; word-break: break-word;
}

/* 折叠卡（思考 / 执行过程 / 工具 / 检索 / 编排） */
.card { border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel-2); overflow: hidden; }
.card-head {
  width: 100%; display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 10px 12px; border: none; background: transparent; cursor: pointer; text-align: left;
  color: var(--app-text2); font-size: 13px; min-height: 40px; touch-action: manipulation;
}
.card-title { font-weight: 500; }
/* 思考卡：卡头不换行——标题右侧那一行「开头几个字」按宽度省略，长概要不能把 40px 的卡头
   撑成两行（与 PC 的 .tl-think-peek 同一套口径） */
.think-card .card-head { flex-wrap: nowrap; }
.think-card .card-title { flex: none; }
.card-peek { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 400; font-size: 12px; color: var(--app-text3); }
.card-dim { font-size: 12px; color: var(--app-text3); }
.card-body { padding: 0 12px 10px; font-size: 13px; }
.think-md { font-size: 14px; line-height: 1.7; }
.proc-body { color: var(--app-text2); white-space: pre-wrap; word-break: break-word; }
.caret { margin-left: auto; flex: none; transition: transform .18s; color: var(--app-text3); }
.caret.open { transform: rotate(90deg); }
@media (prefers-reduced-motion: reduce) { .caret { transition: none; } }
.spinner {
  width: 12px; height: 12px; flex: none; border-radius: 50%;
  border: 2px solid var(--app-accent-border); border-top-color: var(--app-accent);
  animation: mspin .8s linear infinite;
}
@keyframes mspin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinner { animation: none; } }
.ic-ok { color: var(--app-ok); }
.ic-err { color: var(--app-danger); }
.ic-accent { color: var(--app-accent); }
/* 过程簇（与 PC 同构，DSH 文案法则）：簇头=活动图标+罗列文字（触屏无 hover，展开让位下开三角）；
   帽体=固定高度裸排清单（触屏口径 180px），行与行 4px 间距。 */
.proc-cluster { background: var(--app-panel); }
.proc-cluster .tl-head { color: var(--app-text3); }
.proc-cluster .card-title { font-weight: 400; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 活动图标与收起箭头同叠位：收起时恒显活动图标（触屏无悬停），展开后让位给朝上的收起箭头 */
.tl-cluster-ic { position: relative; flex: none; display: inline-flex; align-items: center; justify-content: center; width: 16px; height: 16px; color: inherit; }
.tl-cluster-ic .tl-ic-a, .tl-cluster-ic .tl-ic-c { position: absolute; inset: 0; display: inline-flex; align-items: center; justify-content: center; font-size: 13px; transition: opacity .1s ease, transform .1s ease; }
.tl-cluster-ic .tl-ic-c { opacity: 0; }
.proc-cluster.open .tl-cluster-ic .tl-ic-a, .live-row.open .live-ic .tl-ic-a { opacity: 0; }
.proc-cluster.open .tl-cluster-ic .tl-ic-c, .live-row.open .live-ic .tl-ic-c { opacity: 1; transform: rotate(180deg); }
.tl-cluster-ic .ic-err { color: var(--app-danger); }
/* 扫光文字（与 PC 同一份动效参数） */
.shimmer, .live-name.shimmer {
  color: transparent; background: linear-gradient(105deg, var(--app-text3) 0%, var(--app-text3) 40%, var(--app-text) 50%, var(--app-text3) 60%, var(--app-text3) 100%);
  background-size: 220% 100%; -webkit-background-clip: text; background-clip: text;
  animation: wq-shimmer 1.8s linear infinite;
}
@keyframes wq-shimmer { from { background-position: 120% 0; } to { background-position: -120% 0; } }
@media (prefers-reduced-motion: reduce) { .shimmer, .live-name.shimmer { animation: none; color: var(--app-text3); background: none; } }
/* 跑轮中的簇帽体：固定高度裸排清单（与 PC 同构，帽按触屏口径收成 180px；无内嵌卡壳）。
   一行一步贴底跟随最新一步；上下 20px 渐隐由 vEdgeFade 切 ef-top/ef-bot。 */
.cluster-live {
  max-height: 180px; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable;
  margin: 0 12px 10px; padding: 2px 0 0;
}
.cluster-live > * + * { margin-top: 4px; }
.cluster-live.ef-top { mask-image: linear-gradient(to bottom, transparent 0, #000 20px, #000 100%); }
.cluster-live.ef-bot { mask-image: linear-gradient(to bottom, #000 0, #000 calc(100% - 20px), transparent 100%); }
.cluster-live.ef-top.ef-bot { mask-image: linear-gradient(to bottom, transparent 0, #000 20px, #000 calc(100% - 20px), transparent 100%); }
/* 步骤行（与 PC 同款）：图标位+动词+2px 点+摘要；触屏热区 32px */
.live-row { display: flex; align-items: center; gap: 6px; min-height: 32px; font-size: 13px; color: var(--app-text3); cursor: pointer; }
.live-ic { color: inherit; }
.live-name { flex: none; color: inherit; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 帽体尾行的提要：一行小字、按可用宽度省略（与 PC 的 .tl-live-peek 同一套口径） */
.live-peek { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: inherit; opacity: .9; }
.live-row.run .live-name { color: var(--app-text2); }
.live-sep { flex: none; width: 2px; height: 2px; border-radius: 1px; margin: 0 2px; background: currentColor; opacity: .55; }
.live-brief { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: inherit; }
.live-row.err .live-brief, .live-row.err .live-name { color: var(--app-danger); }
.live-flag { flex: none; font-size: 11px; color: var(--app-text3); }
/* 单步详情（与 PC 同款 IN/OUT 卡）：细边框圆角、分段 grid 独立内滚、段间细分割线 */
.live-detail { margin: 4px 0 4px 4px; border: 0.5px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); overflow: hidden; }
.live-detail .io-sec { display: grid; grid-template-columns: max-content 1fr; column-gap: 12px; align-items: baseline; padding: 8px 12px; max-height: 150px; overflow-y: auto; }
.live-detail .io-label { position: sticky; top: 0; align-self: start; margin: 0; font-size: 11px; color: var(--app-text3); }
.live-detail .io-body { min-width: 0; }
.live-detail .io-div { height: 0.5px; background: var(--app-border); }
.live-detail .io-pre { margin: 0; max-height: none; overflow: visible; }
/* 这一步前后的思考 / 簇尾挂不出去的那一组：都只留一层滚动（内容走 NoteBody 的 bare 裸排） */
.live-think { padding: 8px 12px; max-height: 180px; overflow-y: auto; }
.live-think-label { margin: 0 0 4px; font-size: 11px; color: var(--app-text3); }
.live-tail-btn { display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0;
  border: 0; background: none; padding: 0; margin: 0; font: inherit; color: inherit; text-align: left; cursor: pointer; }
.live-tail-body { margin: 2px 0 4px 22px; max-height: 220px; overflow-y: auto; }
/* 多段连排时给段界：展开后看得出每一段从哪开始 */
.think-seg + .think-seg { margin-top: 6px; padding-top: 6px; border-top: 0.5px solid var(--app-border); }
.tl-group { display: flex; flex-direction: column; gap: 6px; }
.tl-group.inCluster { margin-left: 12px; }
.tl-group.inCluster .tl-group-body { gap: 0; padding: 0 0 0 8px; border-left: 2px solid var(--app-border); }
.tl-group.inCluster .tool-card { border: 0; background: transparent; border-radius: 8px; }
.tl-group.inCluster .tool-card .card-head { padding: 6px 8px; }
.tl-group.inCluster .tool-card:active { background: var(--app-panel-2); }
.tl-group.inCluster .card-body { padding: 0 8px 8px; }
.tl-group-body { display: flex; flex-direction: column; gap: 6px; padding: 0 0 2px; }
/* 行首序号：与簇头那个「已执行 N 个操作」同一把尺 */
.tl-idx { flex: none; width: 18px; font-size: 11px; color: var(--app-text3);
  font-variant-numeric: tabular-nums; text-align: right; }
/* 行尾那一项与它的全文由共用组件带着（NoteTag / NoteBody），这里只把触屏那一档尺寸递过去 */
.bubble { --note-tag-fs: 12px; --note-body-fs: 14px; --note-body-ml: 34px; }
/* 簇尾那一段（还没调工具）自成一节：行壳与工具卡同款，只是没有工具名 */
.note-head {
  width: 100%; display: flex; align-items: center; gap: 6px; padding: 6px 8px; border: none;
  background: transparent; cursor: pointer; text-align: left; color: var(--app-text2);
  font-size: 13px; min-height: 40px; touch-action: manipulation;
}
.tool-card .card-head { padding: 8px 10px; }
.tool-brief { font-family: "SF Mono", Menlo, monospace; font-size: 12px; color: var(--app-text3); background: transparent; overflow: hidden; text-overflow: ellipsis; max-width: 40%; white-space: nowrap; }
/* 派遣那一支正在跑的第几步：卡头那行本来就会折行，这一条给它自己的主色 */
.tool-live { font-size: 12px; color: var(--app-accent); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.io-label { font-size: 12px; color: var(--app-text3); margin: 8px 0 4px; }
.io-pre { margin: 0; padding: 8px 10px; border-radius: 8px; background: var(--app-code-bg); font-family: "SF Mono", Menlo, monospace; font-size: 12px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow: auto; }
.io-pre.live { border-left: 2px solid var(--app-accent); }
.io-err { color: var(--app-danger-text); }
.tool-line { display: flex; align-items: center; gap: 6px; padding: 4px 0; }
.tool-line-name { font-size: 13px; }

/* 智能体提问（askUser）待答态由 MobileChatPage 的 m-askp 面板承载，气泡内不再渲染提问卡 */

/* askUser 问答记录由共用组件 AskRecordCard 承载（自带折叠头与样式）。
   两处宿主内边距不同，用 :deep 分别覆盖，让折叠头与各自的卡片头左右对齐：
   ① .tool-card 内的直接子节点 → 对齐 .card-head 的 10px 12px；
   ② 兜底分支在 .card-body（已有 12px padding）内 → 只需清零自身外边距。 */
.tool-card :deep(.ask-rec) { --ask-rec-pad: 10px 12px; margin: 0 0 4px; }
.tool-card :deep(.ask-rec-body) { padding: 4px 12px 0; }
.card-body :deep(.ask-rec) { margin: 0 0 8px; }
.card-body :deep(.ask-rec-body) { padding: 4px 0 0; }

/* 咨询子对话卡同理：两处宿主内边距不同，让它与各自的卡片头左右对齐 */
.tool-card :deep(.sa-chat) { --sa-chat-pad: 0 12px 10px; }
.card-body :deep(.sa-chat) { --sa-chat-pad: 0 0 10px; }

.err-card { border: 1px solid var(--app-danger-border); background: var(--app-danger-weak); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.err-card.net { border-color: var(--app-warn-border); background: var(--app-warn-weak); }
.err-head { font-size: 13px; color: var(--app-danger-text); display: flex; align-items: center; gap: 6px; }
.err-card.net .err-head { color: var(--app-warn-text); }
.err-tip { font-size: 12px; color: var(--app-text2); }

.approval { border: 1px solid var(--app-warn-border); border-left: 3px solid #dda23f; background: var(--app-warn-weak); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; align-items: stretch; }
.approval.flash { animation: approvalFlash 1.6s ease-out; }
@keyframes approvalFlash {
  0% { box-shadow: 0 0 0 0 rgba(199, 124, 20, .42); }
  28% { box-shadow: 0 0 0 5px rgba(199, 124, 20, .18); }
  100% { box-shadow: 0 0 0 0 rgba(199, 124, 20, 0); }
}
.approval-head { display: flex; align-items: center; gap: 5px; min-width: 0; }
.approval-src { flex: 0 0 auto; font-size: 10px; color: var(--app-text2); background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 5px; padding: 1px 5px; white-space: nowrap; }
.approval-warn { color: #b07818; flex: 0 0 auto; }
.approval-lead { font-size: 13px; font-weight: 600; color: var(--app-warn-text); white-space: nowrap; }
.approval-tool { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.approval-raw { font-size: 10.5px; color: var(--app-text3); font-family: "SF Mono", Menlo, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 34%; }
.approval-clock { flex: 0 0 auto; margin-left: auto; display: inline-flex; align-items: center; gap: 4px; font-size: 11px; color: var(--app-warn-text); background: var(--app-panel); border: 1px solid var(--app-warn-border); border-radius: 5px; padding: 1px 6px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.approval-clock.over { color: var(--app-text3); background: var(--app-panel-2); border-color: var(--app-border); }
/* 「为什么问」：一句话，读得懂，弱化在参数块之上 */
.approval-why { margin: 0; font-size: 11.5px; line-height: 1.6; color: var(--app-warn-text); }
.approval-actions { display: flex; align-items: center; gap: 8px; width: 100%; }
.approval-hint { font-size: 11.5px; color: var(--app-text3); margin-right: auto; }
.btn-primary { border: none; border-radius: 8px; background: var(--app-accent); color: #fff; font-size: 13px; padding: 8px 14px; min-height: 36px; touch-action: manipulation; }
.btn-primary:disabled { background: var(--app-accent-disabled); }
.btn-ghost { border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); color: var(--app-text2); font-size: 13px; padding: 8px 14px; min-height: 36px; display: inline-flex; align-items: center; gap: 5px; touch-action: manipulation; }
.btn-ghost:active { color: var(--app-accent); border-color: var(--app-accent); }

.warn-bar { border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); border-radius: 8px; padding: 8px 10px; font-size: 12px; display: flex; flex-direction: column; gap: 4px; }

.rt-terms { font-size: 12px; color: var(--app-text3); margin-bottom: 6px; }
.rt-ref { padding: 8px 0; border-top: 1px dashed var(--app-border); font-size: 12px; color: var(--app-text2); cursor: pointer; }
/* 来源多时限高内部滚动：窄屏一屏本就不高，十几条片段会把下面的追问建议整个推出视野 */
.rt-body { max-height: 46dvh; overflow-y: auto; overscroll-behavior: contain; }
.rt-ref-tag { color: var(--app-accent); font-weight: 600; margin-right: 4px; }
.rt-origin { font-size: 11px; color: var(--app-accent); border: 1px solid var(--app-accent-border); border-radius: 4px; padding: 0 4px; margin-right: 4px; }
.rt-snip { color: var(--app-text3); margin-top: 4px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

/* 脚本扇出清单（P3b）与分支回报全文（P3a）：窄屏一行一次子调用，摘要自己换行 */
.fan-list { display: flex; flex-direction: column; gap: 3px; margin: 2px 0 6px; font-size: 12px; }
.fan-row { display: grid; grid-template-columns: 18px 84px 56px 1fr; gap: 6px; align-items: baseline; }
.fan-row.err .fan-name, .fan-row.err .fan-brief { color: var(--app-danger); }
/* 在途那条（实时面板）：还没有结果可摘要，用主色标出"正在跑" */
.fan-row.run .fan-name, .fan-row.run .fan-dur { color: var(--app-accent); }
/* 额度/并发上限/交付数一行 */
.fan-budget { color: var(--app-text3); font-size: 11px; margin: 3px 0 6px; }
.fan-run { color: var(--app-warn); font-size: 11px; }
.run-board { color: var(--app-accent); font-size: 11px; }
.fan-idx { color: var(--app-text3); text-align: right; font-variant-numeric: tabular-nums; }
.fan-name { color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fan-dur { color: var(--app-text3); font-variant-numeric: tabular-nums; }
.fan-brief { color: var(--app-text3); overflow-wrap: anywhere; }

.related { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.related-tag { border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-accent); border-radius: 999px; padding: 6px 12px; font-size: 13px; min-height: 34px; touch-action: manipulation; }

.ver-switch { display: inline-flex; align-items: center; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; margin-right: 4px; }
.ver-btn { width: 34px; height: 34px; border: none; background: var(--app-panel); color: var(--app-text2); font-size: 16px; touch-action: manipulation; }
/* 触屏热区：操作行、版本切换 ‹ ›、推荐标签都是**密集成排**的钮，只补高度不补宽度——
   横向对扩会让后渲染的那颗盖住前一颗的外侧半区，点上去没反应。视觉尺寸一律不动。 */
.act-btn, .ver-btn, .related-tag { position: relative; }
.act-btn::after, .ver-btn::after, .related-tag::after {
  content: ''; position: absolute; top: 50%; left: 0; right: 0;
  height: 44px; transform: translateY(-50%);
}
.ver-btn:disabled { opacity: .35; }
.ver-idx { font-size: 12px; color: var(--app-text3); padding: 0 4px; }
</style>
