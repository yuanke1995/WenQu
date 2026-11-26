<template>
      <div :ref="el => setRef('box', el)" class="messages" @scroll="store.onMessagesScroll">
        <!-- 历史没取回来：这是一次失败，不是「这个会话还没有消息」。画成欢迎页会让人以为
             会话被清空了，实际是请求挂了——错误卡占住空态位置，给出原因和重试出口（与移动壳同一判据） -->
        <div v-if="store.historyError" class="hist-err" role="alert">
          <exclamation-circle-outlined class="he-ic" />
          <div class="he-body">
            <div class="he-t">这个会话的历史没有加载出来</div>
            <div class="he-s">{{ store.historyError }}</div>
          </div>
          <button class="app-btn small" type="button" @click="store.retryHistory">重试</button>
        </div>

        <div v-if="store.messages.length === 0 && !store.historyError" class="welcome"
             :class="{ 'welcome-setup': store.setupGuide.loaded && !store.chatDone && !store.guideSnoozed }">
          <BrandMark :size="44" class="welcome-mark" />
          <!-- 引导卡：聊天模型/默认模型未就绪时显示（loaded 门控——首次对账未成功前维持现状，
               宁可晚一拍引导，不给已配好的存量用户闪一帧假引导）。没有可用模型时任何提问都只会弹拦截 toast -->
          <template v-if="store.setupGuide.loaded && !store.chatDone && !store.guideSnoozed">
            <h2>欢迎使用问渠</h2>
            <p>先完成 2 步，即可开始对话</p>
            <div class="welcome-guide"><SetupGuide scope="chat" variant="card" /></div>
          </template>
          <template v-else>
            <WelcomeCopy :variant="store.isNarrow ? 'mobile' : 'pc'" :agent-name="store.currentAgent?.name || ''"
                         :think-on="store.deepThinkOn" :attach-count="store.pendingImages.length + store.pendingFiles.length"
                         :mentions="store.mentionNames" />
          </template>
        </div>

        <!-- 消息列表外壳与公开分享页共用 chat/ChatThread.vue：行/块/气泡布局、四个叶子的
             出现次第（正文 → 降级 → 引用来源 → 动作行）都由它定。本页只往插槽里填登录态专属的东西：
             消息分隔线、@引用/#历史引用、过程块与工具卡、错误卡、计划与审批卡、产物、
             版本切换器与更多菜单、就地编辑卡。次第换不掉，能换的只是「画什么」。 -->
        <ChatThread :messages="store.messages" :editing-index="store.editingIdx" :can-regenerate="() => true"
                    :goal="store.sessionGoal"
                    runnable :session-id="store.currentSessionId" @paste-view="store.openPasteView"
                    @feedback="(m, i, r) => store.openFeedback(m, r)"
                    @regenerate="(m, i) => store.regenerate(i)">
          <template #row-before="{ m, i }">
          <!-- 压缩记录：画在这条提问上方＝本轮开始前框架把早期对话折进了一次摘要，点「查看摘要」读那份全文。
               两个数据源共用这一个位子：落库的轮标记（刷新后还在）＋本轮刚压完的那一档（等回答落库前不空窗） -->
          <MsgDivider v-if="store.compactDividerAt(m, i) || store.liveCompactAt(m, i)" class="cdiv">
            <compress-outlined />
            <span>已压缩上下文</span>
            <button class="app-link-btn" type="button" :disabled="store.compactSummaryLoading"
                    @click="store.openCompactSummary">{{ store.compactSummaryLoading ? '读取中…' : '查看摘要' }}</button>
          </MsgDivider>
          <!-- 模型切换记录：本轮回答与前一条回答的模型展示名不同才出现（发消息产生新回答的那一刻，切换选择器本身不产生记录）。
               两侧名字都点名：只报「换成了谁」会让人读不出从哪儿换的，A → B 才是完整一句话 -->
          <MsgDivider v-if="store.modelSwitchInfo(m, i)" class="msd">
            <swap-outlined />
            <span>模型已切换 {{ store.modelSwitchInfo(m, i).fromLabel }} → {{ store.modelSwitchInfo(m, i).toLabel }}</span>
          </MsgDivider>
          </template>
          <template #user-meta="{ m }">
              <!-- @ 引用（本轮显式指定的知识库/文档/智能体）：消息级常驻标注，刷新/历史回显均保留 -->
              <div v-if="m.role === 'user' && m.mentions && m.mentions.length" class="msg-files">
                <span v-for="(mm, mi) in m.mentions" :key="mi" class="msg-file"
                      :title="mm.type === 'kb' ? '引用的知识库（本轮检索范围）'
                              : (mm.type === 'agent' ? '提及的智能体（本轮由它作答）' : '引用的文档（内容直接带入上下文）')">
                  <database-outlined v-if="mm.type === 'kb'" class="msg-file-ic" />
                  <robot-outlined v-else-if="mm.type === 'agent'" class="msg-file-ic" />
                  <file-text-outlined v-else class="msg-file-ic" />
                  <span class="msg-file-name">{{ mm.name || mm.id }}</span>
                </span>
              </div>
              <!-- # 历史问答引用（本轮显式挑选的会话历史）：消息级常驻标注，刷新/历史回显均保留 -->
              <div v-if="m.role === 'user' && m.historyRefs && m.historyRefs.length" class="msg-files">
                <span v-for="(hr, hi) in m.historyRefs" :key="hi" class="msg-file"
                      :title="'引用的历史问答（' + (hr.role === 'assistant' ? '回答' : '提问') + '）：' + (hr.content || '')">
                  <history-outlined class="msg-file-ic" />
                  <span class="msg-file-name">{{ store.historyRefPreview(hr) }}</span>
                </span>
              </div>
          </template>
          <template #body-before="{ m, i }">
              <!-- 回答头：身份 + 完成态 + 模型 + 步数，过程入口也挂在这里。
                   它用 CSS sticky 吸在这条回答内，滚到长答中段仍能看状态与复制。 -->
              <AnswerHeader v-if="m.role === 'ai'" :m="m" :msg-index="i"
                            :agent="store.agentBadgeOf(store.agentList, m.agentId, m.agentName)"
                            :name="m.agentName || ''"
                            :model-label="m.model ? store.modelLabelOf(m.model) : ''" />
          </template>
          <template #body="{ m, i }">
              <!-- 整轮状态行已并入回答头（AnswerHeader）：正文从这里直接开始，
                   过程块仍由回答头上的「过程」按钮开合。 -->
              <!-- 技能命中不再在回答头顶放芯片（点「看这一轮的过程」账本面板仍可回看 skill_hit 投影） -->
              <!-- 正文 / 过程分离（统一正文流）：正文＝整段 content，工具调用只作过程步骤、不切正文；
                   过程（思考/过程/工具）全收在顶部过程块里。唯一例外是插话：它是用户自己说的话，
                   按时间线里记录的真实位置插在前后两片正文之间，不堆在正文头顶。 -->
              <div v-if="m.role === 'ai'" class="md" :data-msg-index="i">
                <div v-if="store.turnOpen(m) && store.turnProcessRows(m).length" class="tl-turn-process">
                <template v-for="(seg, si) in store.turnProcessRows(m)" :key="si">
                  <!-- 思考段：这一轮模型在想什么，在过程块里占一行。只有正在长的那一段自动展开，其余折叠。
                       被过程簇收进去的那些不走这一支——它们落在帽体清单里所属那一步的展开详情内（见 projections 的 clusterLiveRows） -->
                  <div v-if="seg.kind === 'think'" class="tl-think-block">
                    <button class="tl-think-head" type="button" :aria-expanded="store.thinkSegOpen(m, seg)" @click="store.toggleThinkSeg(m, seg)">
                      <span class="tl-think-title" :class="{ running: store.thinkGrowing(m, seg) }">深度思考</span>
                      <!-- 标题右边挂这一段思考的「开头几个字」：块收着也读得出它在想什么，不必先点开；
                           一行小字、按可用宽度省略，展开时它仍是这段的提要（正文滚长了也当锚点）。
                           标题与提要之间点一颗分隔点，与工具行的「名称 · 摘要」同一套写法。 -->
                      <template v-if="store.thinkPeek(m, seg)">
                        <span class="tl-live-sep" aria-hidden="true"></span>
                        <span class="tl-think-peek" :title="store.thinkPeek(m, seg)">{{ store.thinkPeek(m, seg) }}</span>
                      </template>
                      <caret-right-outlined class="tl-caret" :class="{ open: store.thinkSegOpen(m, seg) }" />
                    </button>
                    <div v-show="store.thinkSegOpen(m, seg)" v-follow-bottom="thinkGrowing(m, seg)" class="tl-think">
                      <AnswerBody :content="store.thinkSlice(m, seg)" :streaming="store.thinkGrowing(m, seg)" />
                    </div>
                  </div>
                  <!-- 过程簇头（DSH ProcessGroupHeader 同构）：无边框一行，行首活动图标（悬停/展开让位给下开三角），
                       文字=做过什么的罗列（收起）或正在做什么（跑动，带扫光）。展开后帽体清单直接长在下面。 -->
                  <div v-else-if="seg.kind === 'cluster'" class="tl-cluster" :class="{ open: store.clusterOpen(m, seg) }">
                    <button class="tl-cluster-bar" type="button" :aria-expanded="store.clusterOpen(m, seg)" @click="store.toggleCluster(m, seg)">
                      <span class="tl-cluster-ic">
                        <span class="tl-ic-a">
                          <close-circle-outlined v-if="store.clusterHasError(seg) && !store.clusterRunning(seg)" class="ic-err" />
                          <component :is="store.clusterIconOf(seg)" v-else />
                        </span>
                        <span class="tl-ic-c"><down-outlined /></span>
                      </span>
                      <span class="tl-cluster-label" :class="{ shimmer: store.clusterRunning(seg) || (m.loading && seg.grow) }">
                        {{ store.clusterLiveText(m, seg) || store.clusterTitle(seg) }}<template v-if="store.clusterRunning(seg) && store.runBoardText(m)"> · {{ store.runBoardText(m) }}</template>
                      </span>
                    </button>
                    <!-- 帽体（DSH 的 .body 同参）：跑轮中自动开并贴底跟随最新一步、用户点开停顶部从头看；
                         min(400px,50vh) 内滚、上下边缘 24px 渐隐。一行一步（图标+动词+2px 点+参数摘要），
                         点行在帽内展开该步详情（入参/输出/错误）——页面高度恒定。 -->
                    <div v-if="store.clusterOpen(m, seg)" v-follow-bottom="!!(m.loading && seg.grow)" v-edge-fade
                         class="tl-cluster-live" :class="{ reading: store.clusterReading(seg) }">
                      <template v-for="(lr, li) in store.clusterLiveRows(seg)" :key="li">
                        <!-- 头与详情同一盒：头在自己这盒内贴顶吸附（见 .tl-live-step），
                             滚过这一盒（下一行到头）它自然让位，不会盖住后面几步 -->
                        <div v-if="lr.t" class="tl-live-step">
                          <div class="tl-live-row" :data-spawn="store.spawnAnchorOf(lr.t)"
                               :class="{ run: lr.t.status === 'start' && !lr.t.denied, err: lr.t.status === 'error', off: lr.t.denied, open: lr.t._open }"
                               @click="lr.t._open = !lr.t._open">
                            <span class="tl-live-ic">
                              <span class="tl-ic-a">
                                <loading-outlined v-if="lr.t.status === 'start' && !lr.t.denied" spin />
                                <check-outlined v-else-if="lr.t.status === 'done'" />
                                <close-circle-outlined v-else-if="lr.t.status === 'error'" class="ic-err" />
                                <pause-circle-outlined v-else />
                              </span>
                              <span class="tl-ic-c"><down-outlined /></span>
                            </span>
                            <span class="tl-live-name" :title="store.toolDesc(lr.t.name, lr.t.hint)">{{ store.toolLabel(lr.t.name, lr.t.label) }}</span>
                            <template v-if="store.toolBrief(lr.t)">
                              <span class="tl-live-sep" aria-hidden="true"></span>
                              <span class="tl-live-brief" :class="{ shimmer: lr.t.status === 'start' && !lr.t.denied }">{{ store.toolBrief(lr.t) }}</span>
                            </template>
                            <span v-if="lr.t.denied" class="tl-live-flag" title="被守卫挡下，没有真正执行">被挡下</span>
                          </div>
                          <!-- 单步详情（点行展开，DSH 的 IN/OUT 卡同构）：分段 grid、每段独立内滚、段间细分割线。
                               这一步之前/之后的思考挂在这里：原先挂在行尾当一颗「深度思考 N 字」标签，
                               被摘要的弹性宽度顶到整行最右、飘在页面空白处（且帽体里没有正文，点开也没东西看）。 -->
                          <div v-if="lr.t._open && (lr.t.args || store.liveOutput(lr.t) || lr.t.error || (lr.notes && lr.notes.length))" class="tl-live-detail" @click.stop>
                            <template v-if="lr.notes && lr.notes.length">
                              <div class="tl-live-think">
                                <div class="tl-live-think-label">思考</div>
                                <div v-for="(nn, ni) in lr.notes" :key="'n' + ni" class="tl-think-seg">
                                  <NoteBody :m="m" :note="nn" always bare />
                                </div>
                              </div>
                              <div v-if="lr.t.args || store.liveOutput(lr.t) || lr.t.error" class="tl-io-div" aria-hidden="true"></div>
                            </template>
                            <div v-if="lr.t.args" class="tl-io-sec">
                              <div class="tl-io-label">入参</div>
                              <div class="tl-io-body">
                                <pre class="tl-io">{{ store.prettyIo(lr.t.args) }}</pre>
                                <ToolSpillButton v-if="lr.t.argsSpillId" :id="lr.t.argsSpillId" :tool="lr.t.name" kind="入参" />
                              </div>
                            </div>
                            <div v-if="lr.t.args && store.liveOutput(lr.t)" class="tl-io-div" aria-hidden="true"></div>
                            <div v-if="store.liveOutput(lr.t)" class="tl-io-sec">
                              <div class="tl-io-label">{{ lr.t.status === 'start' ? '实时输出' : (lr.t.result ? '输出' : '输出（执行期）') }}</div>
                              <div class="tl-io-body">
                                <pre class="tl-io" :class="{ live: lr.t.status === 'start' }">{{ store.liveOutput(lr.t) }}</pre>
                                <ToolSpillButton v-if="lr.t.spillId" :id="lr.t.spillId" :tool="lr.t.name" kind="输出" />
                              </div>
                            </div>
                            <div v-if="(lr.t.args || store.liveOutput(lr.t)) && lr.t.error" class="tl-io-div" aria-hidden="true"></div>
                            <div v-if="lr.t.error" class="tl-io-sec">
                              <div class="tl-io-label">错误</div>
                              <div class="tl-io-body">
                                <pre class="tl-io tl-io-err">{{ lr.t.error }}</pre>
                                <ToolSpillButton v-if="lr.t.spillId" :id="lr.t.spillId" :tool="lr.t.name" kind="输出" />
                              </div>
                            </div>
                            <!-- 咨询另一位助手：平铺行的详情里也挂子对话卡（问它/执行过程/它的回答
                                 +「完整过程」抽屉），两条路径同构 -->
                            <SubAgentChatCard v-if="store.isDispatchTool(lr.t.name) && (lr.t.subChat || (lr.t._steps && lr.t._steps.length))"
                                              :t="lr.t"
                                              :agent="lr.t.subChat ? store.agentBadgeOf(store.agentList, lr.t.subChat.agentId, lr.t.subChat.agentName) : null"
                                              :sources="m.sources"
                                              :session-id="store.currentSessionId || ''" :turn-id="m.turnId || ''" />
                          </div>
                        </div>
                        <!-- 还没换来工具的尾部思考/独白：它就是「当前这一步」，自成一节。
                             转圈只在真的还在长时出现；整组可点开看正文。正文另起一块，不能挤在 flex 行里。 -->
                        <template v-if="!lr.t">
                          <div class="tl-live-row" :class="{ run: store.tailNotesGrowing(m, lr.tailNotes), open: store.tailOpen(m, seg) }">
                            <button type="button" class="tl-live-tail-btn" @click.stop="store.toggleTail(m, seg)">
                              <span class="tl-live-ic">
                                <span class="tl-ic-a">
                                  <loading-outlined v-if="store.tailNotesGrowing(m, lr.tailNotes)" spin />
                                  <check-outlined v-else />
                                </span>
                                <span class="tl-ic-c"><down-outlined /></span>
                              </span>
                              <span class="tl-live-name" :class="{ shimmer: store.tailNotesGrowing(m, lr.tailNotes) }">{{ store.tailNotesTag(lr.tailNotes) }}</span>
                              <!-- 尾行右边同样跟上这一段（组）的开头几个字：帽体收着时也读得出它在想什么 -->
                              <template v-if="store.tailNotesPeek(m, lr.tailNotes)">
                                <span class="tl-live-sep" aria-hidden="true"></span>
                                <span class="tl-live-peek">{{ store.tailNotesPeek(m, lr.tailNotes) }}</span>
                              </template>
                            </button>
                          </div>
                          <div v-if="store.tailOpen(m, seg)" class="tl-live-tail-body">
                            <div v-for="(nn, ni) in lr.tailNotes" :key="'t' + ni" class="tl-think-seg">
                              <NoteBody :m="m" :note="nn" always bare />
                            </div>
                          </div>
                        </template>
                      </template>
                    </div>
                  </div>
                  <!-- 过程独白段（簇外）：区间指向 m.processText（与正文分流），标题行常驻、内容可折叠。
                       被簇收进去的那些不走这一支，并成所属那一步行尾的「过程说明 N 字」 -->
                  <div v-else-if="seg.kind === 'process'" class="tl-process-block">
                    <button class="tl-process-head" type="button" @click="store.toggleProc(m, seg)">
                      <span class="tl-process-title">执行过程</span>
                      <span class="tl-caret" :class="{ open: store.procOpen(m, seg) }"><caret-right-outlined /></span>
                    </button>
                    <div v-show="store.procOpen(m, seg)" class="tl-process">{{ store.procSlice(m, seg) }}</div>
                  </div>
                  <!-- 产物段不在过程块里渲染：产物统一沉底（时间线数据仍保留 artifact 段以备后续） -->
                  <div v-else class="tl-group">
                    <button v-if="store.groupHasBar(seg)" class="tl-group-bar" type="button" @click="seg.tools[0]._groupOpen = !seg.tools[0]._groupOpen">
                      <loading-outlined v-if="store.groupRunning(seg)" spin class="tool-ic tool-ic-run" />
                      <close-circle-outlined v-else-if="store.groupHasError(seg)" class="tool-ic tool-ic-err" />
                      <check-outlined v-else class="tool-ic tool-ic-ok" />
                      <span>执行了 {{ store.executedCount(seg.tools) }} 个操作</span>
                      <span v-if="store.runBoardText(m)" class="run-board">{{ store.runBoardText(m) }}</span>
                      <NoteTag v-for="(nt, ni) in seg.notes" :key="ni" :m="m" :note="nt" />
                      <span class="tl-caret" :class="{ open: seg.tools[0]._groupOpen }"><caret-right-outlined /></span>
                    </button>
                    <!-- 有组条时全文挂在组条下面（批次折叠着也点得开）；没有组条时挂在它所属那张卡下面 -->
                    <template v-if="store.groupHasBar(seg)">
                      <NoteBody v-for="(nt, ni) in seg.notes" :key="'note' + ni" :m="m" :note="nt" />
                    </template>
                    <div v-if="seg.tools.length === 1 || seg.tools[0]._groupOpen" class="tl-group-body" :class="{ solo: seg.tools.length === 1 }">
                      <div v-for="(t, ti) in seg.tools" :key="ti" class="tl-card" :data-spawn="store.spawnAnchorOf(t)"
                           :class="{ run: t.status === 'start', err: t.status === 'error' }">
                        <button class="tl-card-head" type="button" @click="store.toggleToolCard(t)">
                          <loading-outlined v-if="t.status === 'start'" spin class="tool-ic tool-ic-run" />
                          <check-outlined v-else-if="t.status === 'done'" class="tool-ic tool-ic-ok" />
                          <close-circle-outlined v-else class="tool-ic tool-ic-err" />
                          <span class="tool-name" :title="store.toolDesc(t.name, t.hint)">{{ store.toolLabel(t.name, t.label) }}</span>
                          <code v-if="store.toolBrief(t)" class="tl-brief">{{ store.toolBrief(t) }}</code>
                          <!-- 派遣那一支跑到第几步了：子轮的过程帧原样转发过来（subagent_step），
                               只活这一次、不落库，所以刷新后这一行是空的——卡片自己退回「跑了 N 步」 -->
                          <span v-if="t._live && t.status === 'start'" class="tl-live" :title="t._live">{{ t._live }}</span>
                          <span v-if="t.attempts > 1" class="tool-dur">重试 {{ t.attempts - 1 }} 次</span>
                      <!-- 脚本扇出的规模：不点开卡片也能看出"这一次跑了多少个子调用" -->
                      <span v-if="store.fanHasBoard(t.fanOut)" class="tool-dur">{{ store.fanHead(t) }}</span>
                      <span v-if="store.fanRun(t)" class="fan-run">{{ store.fanRun(t) }}</span>
                          <span v-if="t.status === 'error'" class="tool-fail">失败</span>
                          <!-- 这一轮的思考/独白并到步的行尾（不各占一行标题，免得展开态一半高度由它买单）：
                               它本来就长在「想了这些才动手」的那个位置；标签只写类别名，量级展开后自己看 -->
                          <template v-if="ti === 0 && !store.groupHasBar(seg)">
                            <NoteTag v-for="(nt, ni) in seg.notes" :key="ni" :m="m" :note="nt" />
                          </template>
                          <span class="tl-caret tl-caret-btn" :class="{ open: store.toolCardOpen(t) }"><caret-right-outlined /></span>
                        </button>
                        <template v-if="!store.groupHasBar(seg)">
                          <NoteBody v-for="(nt, ni) in seg.notes" :key="'note' + ni" :m="m" :note="nt" />
                        </template>
                        <!-- askUser 问答记录：可折叠（默认展开），刷新/历史恢复同构 -->
                        <AskRecordCard v-if="t.name === 'askUser' && t.status !== 'start'" :t="t" />
                        <!-- 咨询另一位助手：这一问一答在过程块里成卡（那份 subChat 随后端工具卡一起落库，
                             所以刷新/换设备与实时同构）；老消息没这份就不渲染，退回上面的普通工具卡。
                             直播期（subChat 未到、_steps 已有）挂执行中形态：卡内时间线逐步追加 -->
                        <SubAgentChatCard v-if="(t.subChat || (t._steps && t._steps.length)) && store.toolCardOpen(t)" :t="t"
                                          :agent="t.subChat ? store.agentBadgeOf(store.agentList, t.subChat.agentId, t.subChat.agentName) : null"
                                          :sources="m.sources"
                                          :session-id="store.currentSessionId || ''" :turn-id="m.turnId || ''" />
                        <div v-if="t._open" class="tl-card-body">
                      <!-- 脚本扇出清单：一次运行内部调了什么、各多久、有没有被挡下。
                           子调用刻意不各占一张顶层卡片（会被刷成一堵墙），结构就收在这里 -->
                      <template v-if="store.fanHasBoard(t.fanOut)">
                        <div class="tl-io-label">{{ store.fanTitle(t.fanOut) }}</div>
                        <div class="fan-list">
                          <div v-for="(s, si) in t.fanOut.items" :key="si" class="fan-row"
                               :class="{ err: s.status === 'error', run: s.status === 'start' }">
                            <span class="fan-idx">{{ si + 1 }}</span>
                            <span class="fan-name">{{ store.toolLabel(s.name, s.label) }}</span>
                            <span class="fan-dur">{{ store.fanRowState(s) }}</span>
                            <span class="fan-brief">{{ s.brief }}</span>
                          </div>
                          <div v-if="t.fanOut.omitted" class="fan-more">另有 {{ t.fanOut.omitted }} 次未列出</div>
                        </div>
                        <!-- 额度与并发上限：跑着的时候看这条才知道"离撞上限还有多远"，
                             终态那份也带着——刷新后同样能看出这一次占了几成预算 -->
                        <div v-if="store.fanBudget(t.fanOut)" class="fan-budget">{{ store.fanBudget(t.fanOut) }}</div>
                      </template>
                          <template v-if="t.args">
                            <div class="tl-io-label">入参</div>
                            <pre class="tl-io">{{ store.prettyIo(t.args) }}</pre>
                            <ToolSpillButton v-if="t.argsSpillId" :id="t.argsSpillId" :tool="t.name" kind="入参" />
                          </template>
                          <template v-if="store.liveOutput(t)">
                            <div class="tl-io-label">{{ t.status === 'start' ? '实时输出' : (t.result ? '输出' : '输出（执行期）') }}</div>
                            <pre class="tl-io" :class="{ live: t.status === 'start' }">{{ store.liveOutput(t) }}</pre>
                            <ToolSpillButton v-if="t.spillId" :id="t.spillId" :tool="t.name" kind="输出" />
                          </template>
                          <template v-if="t.error">
                            <div class="tl-io-label">错误</div>
                            <pre class="tl-io tl-io-err">{{ t.error }}</pre>
                            <ToolSpillButton v-if="t.spillId" :id="t.spillId" :tool="t.name" kind="输出" />
                          </template>
                        </div>
                      </div>
                    </div>
                  </div>
                </template>
                </div>
                <!-- 正文按时间线还原：无插话＝整段一片，有插话＝在模型真听到那句的位置下刀，
                     气泡长在前后两片之间。切片拼回去仍是完整 content，流式只跟最后一片。 -->
                <template v-for="row in store.turnBodyRows(m)" :key="row.key">
                  <div v-if="row.kind === 'steer'" class="tl-steer">
                  <div class="tl-steer-col">
                  <div v-if="row.images && row.images.length" class="tl-steer-imgs">
                  <img v-for="(src, ii) in row.images" :key="ii" :src="src" alt="插话图片" loading="lazy" />
                  </div>
                  <!-- 随这句插话一起送出去的附件：独立用户气泡被这一段顶掉，文件名与体积不能跟着不见 -->
                  <div v-if="row.attachments && row.attachments.length" class="tl-steer-atts">
                  <span v-for="(a, ai) in row.attachments" :key="ai" class="tl-steer-att" :title="a.name">
                  <file-text-outlined class="tl-steer-att-ic" />
                  <span class="tl-steer-att-name">{{ a.name }}</span>
                  <span v-if="a.size" class="tl-steer-att-size">{{ store.fmtSize(a.size) }}</span>
                  </span>
                  </div>
                  <span class="tl-steer-txt">{{ row.text }}</span>
                  </div>
                  </div>
                  <AnswerBody v-else class="tl-answer" :content="row.content"
                              :images="m.images" :sources="m.sources" :msg-index="i"
                              runnable :session-id="store.currentSessionId"
                              :streaming="!!(m.loading && !m.failed && row.last)" />
                </template>
              </div>
              <!-- 提问与回答同一条排版口径（markdown，breaks:true 所以换行照旧保留）。
                   但**提问走 literal**：图片占位与引用角标都不替换——提问里的 [图片N] / [N]
                   是用户手打的字面量，替换掉会改他的原文、还会让自己的附件多渲染一遍（分享页同此口径） -->
              <AnswerBody v-else :content="store.pendingAnswerText(m.content) || m.content" :msg-index="i"
                          :images="m.role === 'ai' ? m.images : []"
                          :sources="m.role === 'ai' ? m.sources : []"
                          :literal="m.role !== 'ai'"
                          runnable :session-id="store.currentSessionId"
                          :streaming="m.loading && !m.failed" />
          </template>
          <template #body-after="{ m, i }">
              <!-- 错误卡（独立于正文）：回答中断时保留已流出内容，这里给分类文案 + 重试 + 异常详情折叠。
                   只管回答那一行：什么都没产出的轮次错误挂在提问上，见下面 #actions-after 那一条 -->
              <div v-if="m.errorCard && m.role === 'ai'" class="msg-error-card" :class="{ net: m.errorCard.kind === 'interrupted' || m.errorCard.kind === 'unreachable' }">
                <div class="mec-head"><close-circle-outlined class="mec-ic" /> {{ store.errorBrief(m.errorCard.message, m.errorCard.kind) }}</div>
                <div v-if="m.errorCard.kind === 'interrupted'" class="mec-net-tip">
                  已生成的部分已保存。重新生成会重跑本轮并重新计费；若你刚切后台回来，这通常是系统挂起了连接。
                </div>
                <!-- 未送达：一个字都还没生成，别套用「已生成的部分已保存」那套话 -->
                <div v-else-if="m.errorCard.kind === 'unreachable'" class="mec-net-tip">
                  这条提问没有发出去；重新生成会把它重新发送一遍。
                </div>
                <div class="mec-actions">
                  <button class="app-btn ghost small" @click="store.regenerate(i)"><redo-outlined /> 重新生成</button>
                </div>
                <!-- spoken 的 headline 就是原文本身，折叠里再摆一遍同一句话没有信息量 -->
                <details v-if="m.errorCard.kind !== 'spoken'" class="mec-detail">
                  <summary>异常详情</summary>
                  <pre class="mec-raw">{{ m.errorCard.message }}</pre>
                </details>
              </div>
              <!-- 计划模式（人在回路）：执行计划卡（只读）。裁决入口在底部输入框区的计划确认栏
                   （与提问卡同形，见 pendingPlan / planp-*）；卡片本体两端共用 chat/PlanCard.vue，
                   刷新/换设备后由 hydratePendingPlans 按落库记录重建（restored 卡同样可裁决） -->
              <PlanCard v-if="m.planCard" :card="m.planCard" :deep="!!store.sessionGoal" :sid="store.currentSessionId || ''"
                        :pkey="m.planCard.id || i" :progress="store.planExecProgressOf(m)"
                        :exec-detail="store.planExecDetailOf(m)"
                        :countdown="m.planCard.status === 'pending' ? store.planCountdownText : ''"
                        :expired="m.planCard.status === 'pending' && store.planExpired"
                        :artifact-count="(m.artifacts || []).length"
                        :adjusts="store.planAdjustsOfMsg(m)"
                        @open="(v, tab) => store.openPlanViewer(m, i, v, tab)"
                        @adjust="store.openPlanAdjust(m)"
                        @locate-replanned="store.locateReplanned(m)"
                        @locate-source="store.locatePlanSource(m)" />
              <!-- 工具执行审批（人在回路）：智能体开启"执行前确认"后，有副作用工具（沙盒/MCP）执行前需用户批准。
                   通知深链也定位到这一张（引擎 hydratePendingApprovals → 滚动 + 闪一圈），顶部不再另画横幅 -->
              <div v-if="(m.approvals || []).length > 1" class="approval-count">
                本轮有 <b>{{ m.approvals.length }}</b> 项操作等你批准（可分别处理）
              </div>
              <div v-for="a in (m.approvals || [])" :key="a.id" class="approval-card" data-approval-card :data-approval-id="a.id">
                <div class="approval-head">
                  <exclamation-circle-outlined class="approval-warn" />
                  <span class="approval-lead">需要你批准</span>
                  <span v-if="a.subagent && a.subagent.agent" class="approval-src">{{ a.subagent.agent }}</span>
                  <span class="approval-tool" :title="store.toolDesc(a.tool, a.hint)">{{ store.toolLabel(a.tool, a.label) }}</span>
                  <code v-if="a.label && a.label !== a.tool" class="approval-raw">{{ a.tool }}</code>
                  <span class="approval-clock" :class="{ over: store.approvalLeftSecOf(a) <= 0 }">
                    <clock-circle-outlined />
                    {{ store.approvalLeftSecOf(a) > 0 ? '剩 ' + store.approvalLeftSecOf(a) + ' 秒' : '已超时' }}
                  </span>
                </div>
                <!-- 「为什么问」：读过外部内容后的强制确认 / 审批档要求，与守卫同源；没有就不占行 -->
                <p v-if="a.reason" class="approval-why">{{ a.reason }}</p>
                <pre v-if="store.approvalArgsText(a.args)" class="approval-args">{{ store.approvalArgsText(a.args) }}</pre>
                <div class="approval-actions">
                  <span class="approval-hint">{{ store.approvalLeftSecOf(a) > 0 ? '批准后立即执行这一步' : '已超时，本轮将按拒绝继续' }}</span>
                  <button class="app-btn ghost small" :disabled="a.busy || store.approvalLeftSecOf(a) <= 0"
                          @click="store.resolveApproval(m, a, false)">拒绝</button>
                  <!-- 「批准执行」带下拉：主键是默认动作（只批这一步），箭头里多一档「本次会话都允许」——
                       同一工具后续不再弹卡；箭头单独成键，点主键不会误开菜单 -->
                  <span class="approval-approve-split">
                    <button class="app-btn small" :disabled="a.busy || store.approvalLeftSecOf(a) <= 0"
                            @click="store.resolveApproval(m, a, true)">批准执行</button>
                    <a-dropdown :trigger="['click']" placement="bottomRight">
                      <button class="app-btn small approval-caret" type="button"
                              :disabled="a.busy || store.approvalLeftSecOf(a) <= 0"
                              title="更多批准方式" aria-label="更多批准方式"><down-outlined /></button>
                      <template #overlay>
                        <a-menu @click="() => store.resolveApproval(m, a, true, 'session')">
                          <a-menu-item key="session">本次会话都允许</a-menu-item>
                        </a-menu>
                      </template>
                    </a-dropdown>
                  </span>
                </div>
              </div>
              <!-- 智能体提问（askUser）的「待答」态不再渲染在气泡里：桌面壳把底部聊天输入框整块
                   替换成提问面板（见 pendingAsk / askp-*），答复后问答记录以工具卡形态留在本气泡 -->
              <!-- 产物统一沉底展示（不参与过程块与正文的排版）。
                   「预览」＝进预览台（整屏 + 可控视口，见 utils/previewStage）：页面设计档给真实视口，
                   读字档把 Markdown/表格/代码在应用里排好版；锚点的 href 仍是那份签名地址，
                   所以 Ctrl/中键点击照旧开真标签。哪些类型怎么摆是 artifactView 里那一份判据，开不了的只给下载 -->
              <!-- 生成态：本轮还没有产物、模型正在生成/交付文件时先占位；完成由下面的卡原位接管 -->
              <ArtifactPendingCard v-if="m.role === 'ai' && store.artifactPendingOf(m)" :pending="store.artifactPendingOf(m)" />
              <!-- 交付包：单件与多件共用同一张卡（单件左缩略+动作，多件包形态+清单）；
                   流式中的气泡按消息对象锚定自己的包，第一件产物到达即成卡；
                   实时到达的产物播一次入场，历史恢复不播。 -->
              <DeliveryCard v-if="m.role === 'ai' && store.deliveryCardOfMessage(m)"
                            :delivery="store.deliveryCardOfMessage(m)"
                            :fresh="store.freshDeliveryOf(m)"
                            @view="store.openDelivery"
                            @zip="store.downloadDeliveryZip" />
              <!-- 每轮文件改动卡：这一轮在本机工作区里改了哪些文件、各多少行；行点击进预览台 diff 档。
                   停止/中断的轮也可能改过文件——只要服务端对比出来就照挂，刷新后按消息行的 changes 重建 -->
              <TurnChangesCard v-if="m.role === 'ai' && m.changes" :changes="m.changes" :message-id="m.messageId || ''" />
          </template>
          <template #sources="{ m, i }">
              <SourceList v-if="m.role === 'ai' && (store.retrievalLineTitle(m) || store.toolSearchQueries(m).length || (m.sources && m.sources.length))"
                          v-model:open="m.rtOpen" :sources="m.sources" :title="store.retrievalLineTitle(m)"
                          :note="store.hitsNote(m)" :terms="m.retrieved && m.retrieved.terms"
                          :tool-queries="store.toolSearchQueries(m)" :question="store.questionFor(i)"
                          :can-feedback="true" show-snippet />
          </template>
          <template #sources-after="{ m }">
              <!-- 只在最新一轮出现（panelAi=最后一条 AI 消息，含进行中）：更早那几轮的建议已被后面的对话取代，
                   每轮都留着会让整页散落着可点的旧问句，翻历史时容易误点重发一个早问过的话题。
                   整行是按钮：编号 + 问题 + 箭头，比灰底小 chip 更像「下一步」 -->
              <div v-if="m.role === 'ai' && m === store.panelAi && store.showRelatedSuggestions && m.related && m.related.length" class="related">
                <div class="related-head">
                  <bulb-outlined class="related-ic" />
                  <span class="related-title">接下来可以</span>
                  <span class="related-sub">会作为新一轮提问发送</span>
                </div>
                <button v-for="(q, qi) in m.related" :key="qi" type="button" class="related-row" @click="store.ask(q)">
                  <span class="related-no">{{ qi + 1 }}</span>
                  <span class="related-txt" :title="q">{{ q }}</span>
                  <arrow-right-outlined class="related-go" />
                </button>
                <div class="related-foot">这些追问会作为新的用户消息发送，可能重新检索资料或调用工具。</div>
              </div>
          </template>
          <template #actions-prefix="{ m, i }">
                  <!-- 重新生成的多版本切换器：本会话内用内存版本（versions）即时切换；
                       刷新后/编辑重发用持久分支（variantCount）走后端切换，旧版本按保留期留存 -->
                  <div v-if="(m.versions && m.versions.length > 1) || m.variantCount > 1" class="ver-switch"
                       title="这一回答有多个版本，可来回切换（旧版本按保留期清理）">
                    <button class="ver-btn" :disabled="!store.canSwitchPrev(m)" @click="store.switchBranch(i, -1)">‹</button>
                    <span class="ver-idx">{{ store.verLabel(m) }}</span>
                    <button class="ver-btn" :disabled="!store.canSwitchNext(m)" @click="store.switchBranch(i, 1)">›</button>
                  </div>
          </template>
          <template #actions-more="{ m, i }">
                <a-dropdown :trigger="['hover', 'click']">
                  <button class="app-icon-btn" title="更多"><more-outlined /></button>
                  <template #overlay>
                    <a-menu @click="({ key }) => store.onMoreAction(key, i)">
                      <!-- 固化为技能：把这一轮「问题→做法」沉淀成一条可复用的 SKILL.md（弹窗预填、改两行就能用）。
                           收进「更多」菜单，与重新生成等常驻动作分开；只挂在落库的 AI 回答上（#more 插槽本就在 messageId 判据内） -->
                      <a-menu-item key="solidify" title="把这一轮的做法固化为技能"><thunderbolt-outlined style="margin-right:8px" />固化为技能</a-menu-item>
                      <a-menu-item v-if="store.debugEntryVisible" key="debug"><bug-outlined style="margin-right:8px" />检索调试</a-menu-item>
                      <!-- 加入评测集 = 差评回流固化到检索评测集（/api/ai/eval 仅管理员可用），属调参排障动作：
                           与「检索调试」同一开关（chat.retrievalDebugEnabled）控制，不给普通用户露出必 403 的入口 -->
                      <a-menu-item v-if="store.debugEntryVisible && m.sources && m.sources.length" key="addEval"><dislike-outlined style="margin-right:8px" />加入评测集</a-menu-item>
                      <a-menu-item v-if="m.turnId" key="events"><history-outlined style="margin-right:8px" />看这一轮的过程</a-menu-item>

                      <a-menu-item key="schedule"><schedule-outlined style="margin-right:8px" />设为定时任务</a-menu-item>
                      <a-menu-item key="export"><download-outlined style="margin-right:8px" />导出 Markdown</a-menu-item>
                      <a-menu-item key="deleteRound" style="color:#cf1322"><delete-outlined style="margin-right:8px" />删除本轮对话</a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
          </template>
          <template #actions-extra="{ m }">
                  <!-- 那一个数=本轮结束时的上下文占用（与输入框容量圆环同一个分子）。
                       悬停里把三个口径分开报名、一行一个，不并列成能相加的样子：占用 ≠ 累计送入 ≠ 输出。
                       占用为 0 的历史轮整块不显示——不拿别的数凑一个「这一轮用了多少」出来。 -->
                  <a-tooltip v-if="store.usageCtxTokens(m.tokens)" :title="store.usageTip(m.tokens)"
                             :overlay-inner-style="{ whiteSpace: 'pre-line' }">
                    <span class="msg-tokens">上下文{{ m.tokens.outputIsReal ? ' ' : ' ≈' }}{{ store.fmtTokens(store.usageCtxTokens(m.tokens)) }} tokens</span>
                  </a-tooltip>
                  <span v-if="m.time" class="msg-time-inline">{{ store.fmtMsgTime(m.time) }}</span>
          </template>
          <template #actions-after="{ m, i }">
            <!-- 免责声明：从标题栏下沉到读结论的现场；点开仍是原来那份完整声明 -->
            <div v-if="m.role === 'ai' && m.content && !m.loading" class="msg-disclaimer"
                 title="查看完整免责声明" @click="set('disclaimerVisible', true)">
              <InfoCircleOutlined />
              <span>AI 生成内容，关键数字请以数据源为准</span>
            </div>
            <!-- 叫停且本轮没产出任何回答：终止事实记在「这一问」上（不造一条空回答气泡），刷新/换端从库里带回 -->
            <div v-if="m.role === 'user' && m.endReason === 'stopped'" class="msg-stopped"
                 title="已按你的要求停止这一轮，智能体没有产出回答">
              <pause-circle-outlined /> 本轮已停止 · 未产生回答
            </div>
            <!-- 断线超过宽限被系统收束：不是用户点停的，文案不许把系统的决定算到他头上 -->
            <div v-if="m.role === 'user' && m.endReason === 'interrupted'" class="msg-stopped"
                 title="你离开后超过设定时间没人观看，这一轮被自动中断了，没有产出回答">
              <exclamation-circle-outlined /> 本轮已中断 · 未产生回答
            </div>
            <!-- 这一轮被挡下或跑挂了、且什么都没产出：原因挂在「这一问」上（库里带回的那一句，
                 与实时那颗错误卡同一个 errorBrief 口径）。不另摆重新生成按钮——重发入口本就在这一问上 -->
            <div v-if="m.role === 'user' && m.errorCard" class="msg-fail" :title="m.errorCard.message">
              <close-circle-outlined /> {{ store.errorBrief(m.errorCard.message, m.errorCard.kind) }}
            </div>
            <!-- 目标指令回声只撤这一行的动作键：那句「把这件事做到什么算完」是一条指令，
                 不是一等回答的提问，复制/编辑重发对它没有意义（与公开分享页同一口径）。
                 goalPending 那一档是同一条判据：乐观气泡先落地、goalId 要等建目标的 POST 回来才补上
                 （冷启动要几秒），那几秒里若只看 goalCommand，编辑重发会亮着——点下去就把一条目标
                 指令当普通提问重跑一轮。时间照挂——「什么时候定的这个目标」要能在对话里读出来 -->
            <div v-if="m.role === 'user'" class="msg-edit-row">
              <template v-if="!m.goalCommand && !m.goalPending">
                <!-- 编辑重发的分支切换器：这一问有多个版本（历史编辑留下的旧分支）可来回切 -->
                <div v-if="m.variantCount > 1" class="ver-switch"
                     title="这个问题编辑过多个版本，可来回切换（旧版本按保留期清理）">
                  <button class="ver-btn" :disabled="(m.variantIndex || 1) <= 1 || store.variantSwitching" @click="store.switchBranch(i, -1)">‹</button>
                  <span class="ver-idx">{{ m.variantIndex || 1 }}/{{ m.variantCount }}</span>
                  <button class="ver-btn" :disabled="(m.variantIndex || 1) >= m.variantCount || store.variantSwitching" @click="store.switchBranch(i, 1)">›</button>
                </div>
                <a-tooltip title="复制问题" placement="top">
                  <copy-outlined class="app-icon-btn" @click="store.copyUserMessage(m)" />
                </a-tooltip>
                <a-tooltip title="编辑此问题，从这一轮重新生成" placement="top">
                  <edit-outlined class="app-icon-btn" @click="store.editMessage(i)" />
                </a-tooltip>
              </template>
              <span v-if="m.time" class="msg-time-inline">{{ store.fmtMsgTime(m.time) }}</span>
            </div>
            <!-- 就地编辑卡：原位替换该气泡的紧凑右对齐卡。除正文外，图片/附件可增删、@ 引用可移除
                 （随行内容播种自原消息；深度思考/技能/# 历史引用沿用原轮不展示）。
                 确认后从这一轮整段重新生成，旧分支软删留档可切回 -->
            <div v-if="m.role === 'user' && store.editingIdx === i" class="msg-inline-edit">
              <textarea :ref="el => setRef('setEditingRef', el)" :value="store.editingText" @update:value="v => set('editingText', v)" class="msg-inline-edit-input" rows="1"
                        placeholder="编辑这一问题…" @input="store.autosizeEditing" @paste="store.onEditPaste" @keydown="store.onEditKeydown" />
              <div v-if="store.editImgs.length" class="pending-imgs">
                <div v-for="(u, pi) in store.editImgs" :key="pi" class="pending-img">
                  <img :src="store.resolveImg(u.dataUrl)" alt="待发送图片" @click="store.previewEditImage(pi)" />
                  <span class="pending-del" @click.stop="store.removeEditImage(pi)">×</span>
                </div>
              </div>
              <div v-if="store.editAtts.length" class="pending-files">
                <template v-for="(f, fi) in store.editAtts" :key="fi">
                  <div v-if="f.paste" class="pending-file paste-card" :class="{ err: !!f.error, expired: !!f.expired }"
                       :title="f.expired ? '粘贴文本的正文只留在当轮内存里，刷新后不随历史保留' : (f.error || '点击查看粘贴的全文')" @click="store.openPasteView(f)">
                    <span class="paste-card-ic"><file-text-outlined /></span>
                    <span class="paste-card-txt">
                      <span class="paste-card-name">{{ store.pasteTitle(f.name) }}</span>
                      <span class="paste-card-sub">{{ store.pasteSub(f) }}</span>
                    </span>
                    <span class="pending-file-del" @click.stop="store.removeEditAtt(fi)">×</span>
                  </div>
                  <div v-else class="pending-file" :class="{ err: !!f.error, expired: !!f.expired || !!f.unavailable }"
                       :title="f.expired ? (f.name + '：原始文件已过保留期（默认 24h），重发这一问不会带上它；如仍需请重新上传')
                               : f.unavailable ? (f.name + '：这条消息没留下可带回的原始文件，重发这一问不会带上它；如仍需请重新上传')
                               : (f.error || f.name)">
                    <file-text-outlined class="pending-file-ic" />
                    <span class="pending-file-name">{{ f.name }}</span>
                    <span v-if="f.uploading" class="pending-file-size">上传中…</span>
                    <span v-else-if="f.expired" class="pending-file-size">已过保留期</span>
                    <span v-else-if="f.unavailable" class="pending-file-size">不可重发</span>
                    <span v-else-if="f.error" class="pending-file-size">上传失败</span>
                    <span v-else class="pending-file-size">{{ store.fmtSize(f.size) }}</span>
                    <span class="pending-file-del" @click.stop="store.removeEditAtt(fi)">×</span>
                  </div>
                </template>
              </div>
              <div v-if="store.editMentions.length" class="edit-mentions">
                <span v-for="(mm, mi2) in store.editMentions" :key="mm.type + ':' + mm.id" class="at-chip mention-chip"
                      :class="'mention-' + mm.type"
                      :title="mm.type === 'kb' ? '本轮检索收窄到该知识库'
                              : (mm.type === 'agent' ? '本轮会强制咨询这位助手：它带着自己的知识库查一遍，回答仍由当前助手汇总' : '该文档内容直接带入本轮上下文')">
                  <robot-outlined v-if="mm.type === 'agent'" class="at-chip-ic" />
                  <database-outlined v-else-if="mm.type === 'kb'" class="at-chip-ic" />
                  <file-text-outlined v-else class="at-chip-ic" />
                  <span class="at-chip-name">{{ mm.name || mm.id }}</span>
                  <span class="at-chip-del" title="移除该引用" @click="store.removeEditMention(mi2)">×</span>
                </span>
              </div>
              <!-- 技能（编辑卡内可增删）：与主输入框同一份 skillList/toggleSkill -->
              <div v-if="store.editSkills.length" class="edit-mentions">
                <span v-for="n in store.editSkills" :key="n" class="at-chip skill-chip">
                  <span class="skill-chip-ava" :style="store.skillAvaStyle(n)">{{ n.slice(0, 1) }}</span>
                  <span class="at-chip-name">{{ n }}</span>
                  <span class="at-chip-del" title="移除该技能" @click="store.removeEditSkill(n)">×</span>
                </span>
              </div>
              <!-- # 引用的历史问答（编辑卡内可增删）：候选仍是 histPool（同会话已落库的消息） -->
              <div v-if="store.editHistRefs.length" class="edit-mentions">
                <span v-for="(h, hi) in store.editHistRefs" :key="h.messageId" class="at-chip"
                      :title="'引用的历史' + (h.role === 'assistant' ? '回答' : '提问') + (h.digest ? '：' + h.digest : '')">
                  <history-outlined class="at-chip-ic" />
                  <span class="at-chip-name">{{ h.digest || (h.role === 'assistant' ? '回答' : '提问') }}</span>
                  <span class="at-chip-del" title="移除该引用" @click="store.removeEditHistRef(hi)">×</span>
                </span>
              </div>
              <div class="msg-inline-edit-actions">
                <!-- 添加入口与主输入框同一个「+」分层面板（同一块面板，编辑态下 Teleport 进这张卡）：
                     上传附件 / 引用资料（含技能）/ 引用历史问答共用一套候选、一套搜索与键盘导航。
                     快捷命令不进来：那些操作会改会话状态，与"改这一问"无关 -->
                <button class="app-icon-btn add-btn" :class="{ 'toolbar-btn-on': (store.addOpen && store.addOnEdit) || store.editAddCount }"
                        title="添加：上传文件图片 / 引用资料 / 引用历史问答" @click="store.toggleEditAddPanel">
                  <plus-outlined />
                </button>
                <span class="msg-inline-edit-hint"><info-circle-outlined /> 编辑后将从此处重新开始对话，已有产物不会被删除</span>
                <button class="app-btn ghost" @click="store.cancelEdit">取消</button>
                <button class="app-btn" :disabled="store.editingBusy || (!store.editingText.trim() && !store.editImgs.length && !store.editAtts.length)"
                        @click="store.confirmEdit">发送</button>
              </div>
              <div class="add-mount"></div>
              <input :ref="el => setRef('setEditAttachInputRef', el)" type="file" multiple style="display:none" @change="store.onEditAttachChange" />
            </div>
            <!-- 目标终态卡：挂在「设为目标」那条回声下（goalId 认，刷新从历史重建）。
                 删除记录后 sessionGoal 变 null，这张卡随之一并消失 -->
            <GoalTerminalCard v-if="m.role === 'user' && m.goalCommand && store.goalTerminalCard && store.goalTerminalCard.goalId === m.goalCommand"
                              :goal="store.goalTerminalCard" :title="store.goalTerminalTitle" :note="store.goalTerminalNote"
                              :version="store.goalVersionLabel" :has-delivery="!!store.goalDelivery"
                              @view="store.openGoalViewer"
                              @delivery="store.openDelivery(store.goalDelivery)"
                              @edit="store.startGoalInlineEdit"
                              @restart="store.startGoal(store.goalTerminalCard.objective)"
                              @delete="store.runGoalAction('delete')" />
          </template>
        </ChatThread>
        <!-- 手动 /compact 的进行中：只认手动那一路。轮内自动压缩的进度说在气泡那条状态行上
             （一轮里框架可以压好几次，在这儿挂一条每压完撤走、下次又长出来的线，就是反复弹出），
             手动这一路没有正在跑的那一轮、没有状态行可借，当场这一行是它唯一的进度 -->
        <MsgDivider v-if="store.manualCompacting" class="cn-tail">
          <loading-outlined spin />
          <span>正在压缩上下文</span>
        </MsgDivider>
        <!-- 手动压缩当场留下的记录：下一轮回答落库的摘要覆盖点会接上这件事，届时由上面那条持久判据
             画在同一条缝上，所以这里只是提前把线补出来，不会和它重复 -->
        <MsgDivider v-else-if="store.compactNotice && store.compactNotice.sid === store.currentSessionId" class="cn-tail">
          <compress-outlined />
          <span>已压缩上下文</span>
          <button class="app-link-btn" type="button" @click="store.openCompactSummary">查看摘要</button>
        </MsgDivider>
        <!-- 尾随留白：本轮问题下方补足一屏，使贴底落点=问题置顶（回答长过一屏后归零，恢复正常贴底跟尾） -->
        <div v-if="store.tailSpacer > 0" class="tail-spacer" :style="{ height: store.tailSpacer + 'px' }" aria-hidden="true"></div>
        <!-- 回到底部：贴在消息列右下角、输入区卡片（任务清单/目标条/插话队列…）正上方。
             放在消息流末尾 + sticky，卡片长多高都不会被压住；右对齐到 --chat-col 那一条右边 -->
        <div v-if="!store.stickToBottom && store.messages.length" class="jump-latest-wrap">
          <button type="button" class="jump-latest" title="回到底部" @click.stop="store.scrollForce">↓</button>
        </div>
      </div>
</template>

<script setup>
import AnswerBody from '../../components/AnswerBody.vue'
import AnswerHeader from '../../components/AnswerHeader.vue'
import ArtifactPendingCard from '../../components/ArtifactPendingCard.vue'
import AskRecordCard from '../../components/AskRecordCard.vue'
import BrandMark from '../../components/BrandMark.vue'
import ChatThread from '../../chat/ChatThread.vue'
import DeliveryCard from '../../components/DeliveryCard.vue'
import GoalTerminalCard from '../../components/GoalTerminalCard.vue'
import MsgDivider from '../../components/MsgDivider.vue'
import NoteBody from '../../components/NoteBody.vue'
import NoteTag from '../../components/NoteTag.vue'
import PlanCard from '../../components/PlanCard.vue'
import SetupGuide from '../../components/SetupGuide.vue'
import SourceList from '../../chat/SourceList.vue'
import SubAgentChatCard from '../../components/SubAgentChatCard.vue'
import ToolSpillButton from '../../components/ToolSpillButton.vue'
import TurnChangesCard from '../../components/TurnChangesCard.vue'
import WelcomeCopy from '../../components/WelcomeCopy.vue'
import { ArrowRightOutlined, BugOutlined, BulbOutlined, CaretRightOutlined, CheckOutlined, ClockCircleOutlined, CloseCircleOutlined, CompressOutlined, CopyOutlined, DatabaseOutlined, DeleteOutlined, DislikeOutlined, DownOutlined, DownloadOutlined, EditOutlined, ExclamationCircleOutlined, FileTextOutlined, HistoryOutlined, InfoCircleOutlined, LoadingOutlined, MoreOutlined, PauseCircleOutlined, PlusOutlined, RedoOutlined, RobotOutlined, ScheduleOutlined, SwapOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import { inject } from 'vue'
import { vFollowBottom, vEdgeFade } from '../../chat/followBottom'
// 父页 provide('chatCtx')：一个 reactive 上下文（含 ref 与动作）。
// 与其它 chatpage/*.vue 同一套取法——不要再改成 required prop：
// 父页是 <ChatMessageList /> 无参渲染，改成 prop 会让 store 变 undefined，整块消息流读不出东西。
const store = inject('chatCtx')
const set = (k, v) => { store[k] = v }
const setRef = (k, el) => { store[k] = el }
</script>

<style scoped src="./ChatMessageList.css"></style>
