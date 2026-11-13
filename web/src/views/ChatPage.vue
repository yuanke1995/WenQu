<template>
  <div class="chat2" @mouseenter="railVis = true" @mouseleave="railVis = false; railHoverIdx = -1">
    <!-- 中间：标题栏 + 消息流 + 输入区 -->
    <div class="chat-col">
      <!-- PC 标题栏：窄屏隐藏（与移动顶栏形成双顶栏，图里很明显），
           其中的会话查找/分享/状态三个动作改由下面的 MobileChatHead 以图标提供 -->
      <div v-if="!isNarrow" class="chat-head">
        <span class="chat-title">{{ currentSessionTitle }}</span>
        <!-- 当前工作区标识：约定每轮注入 system prompt，用户有权在聊天的当场看见它在生效；
             没挂工作区的会话接口仍返回档位等字段，但 projectId 为空——这里按它判断，标识不显示（不编名字）。
             点它直接进工作区页面（目录/记忆/隔离都在那一页） -->
        <button v-if="sessionWorkspace && sessionWorkspace.projectId" class="head-ws" :class="{ 'head-ws-archived': sessionWorkspace.archived }"
                :title="wsTitle" @click="router.push('/project/' + sessionWorkspace.projectId)">
          <folder-open-outlined class="head-ws-ic" />
          <span class="head-ws-name">{{ sessionWorkspace.name }}</span>
        </button>
        <span class="head-tip" title="查看免责声明" @click="disclaimerVisible = true">AI 回答可能有误，重要信息请核实</span>
        <!-- 头部动作区：右对齐一组，图标按钮无框安静 -->
        <div class="head-actions">
          <button class="app-icon-btn" title="在本会话中查找（Ctrl/⌘ + F）" @click="openSearch">
            <search-outlined />
          </button>
          <!-- 分享激活态：图标转琥珀，与侧栏会话行的分享标记同色（链接还开着不进弹窗也得有感知） -->
          <button class="app-icon-btn" :class="{ 'share-on': shareActive }" @click="openShare"
                  :title="shareActive ? '这段对话正在对外分享（只读链接生效中）' : '分享这段对话（只读链接）'"
                  :aria-label="shareActive ? '这段对话正在对外分享（只读链接生效中）' : '分享这段对话（只读链接）'">
            <share-alt-outlined />
          </button>
          <!-- 子智能体药丸：这一轮派出去几位、几位还在跑。它坐在标题栏而不是正文里，是因为
               派遣一跑就是两三分钟，而正文在这期间一直在往下长——跟在正文里的东西要滚到
               最新那一段才看得见，等它跑完才出现的东西等于没给。 -->
          <SubagentPill :msg="processMsg" @focus="focusSpawn" />
          <button class="head-quiet-btn" title="看这个会话是怎么跑出来的：工具步与耗时、三张卡、产物、停止与失败"
                  @click="openEvents()">过程</button>
          <button class="head-quiet-btn" @click="togglePanel">{{ panelOpen ? '隐藏状态' : '状态' }}</button>
        </div>
      </div>
      <!-- 窄屏动作行：标题归顶栏，这里只放查找/分享/状态三个图标 -->
      <MobileChatHead v-if="isNarrow" :share-active="shareActive" @search="openSearch" @share="openShare" @panel="togglePanel">
        <SubagentPill mobile :msg="processMsg" @focus="focusSpawn" />
      </MobileChatHead>

      <!-- 执行过程（事件账本）：按轮列出这一会话跑过什么——工具步与耗时、审批/提问/计划三张卡、
           产物、子智能体编排、停止与失败。这份记录不参与智能体的回答，只服务事后回看与排障。
           取数与渲染在共用组件里（移动壳的底部 sheet 用同一份，不各写一遍） -->
      <a-modal v-model:open="eventsOpen" :title="eventsTitle" width="min(880px, 94vw)" :footer="null">
        <SessionEventsPanel :open="eventsOpen" :session-id="currentSessionId || ''" :turn-id="eventsTurn"
                            :message="eventsMsg" :question="eventsQuestion" />
      </a-modal>

      <!-- 目标过程：右栏抽屉，版本轨 / 用量预算 / 按轮时间线 / 结果；窄屏由组件切全屏 -->
      <GoalViewer :open="goalViewerOpen" :session-id="currentSessionId || ''" :goal="sessionGoal" device="pc"
                  @close="goalViewerOpen = false"
                  @action="onGoalViewerAction" @jump="jumpToGoalTurn" />

      <!-- 交付抽屉：与目标过程抽屉同款（右侧 420px）。卡片 / 目标条 / 终态卡三处都落到这里，
           关掉仍回到对话原位；清单分组与批量动作只在面板里出现。 -->
      <a-drawer v-model:open="deliveryOpen" placement="right" :width="420"
                :body-style="{ padding: '0' }" :title="deliveryTarget ? deliveryTarget.name : '交付包'">
        <DeliveryPanel :delivery="deliveryTarget" @zip="downloadDeliveryZip" />
      </a-drawer>

      <!-- 技能正文查看：写入已交给助手，读的这一侧要能审（正文是逐字进提示词的）；移动壳用同一份组件 -->
      <SkillContentModal :open="!!skillDoc" :name="skillDoc" :entry="skillDocEntry"
                         :session-id="currentSessionId || ''"
                         @update:open="v => { if (!v) skillDoc = '' }" />

      <!-- 固化为技能：预填本轮「问题→做法」成一条 SKILL.md，三个字段都可改；提交落当前账号的技能目录 -->
      <a-modal v-model:open="solidifyOpen" title="固化为技能" width="min(640px, 94vw)"
               :confirm-loading="solidifyBusy" @ok="submitSolidify">
        <div class="sf-field">
          <div class="sf-label">技能名<span class="sf-sub">汉字/字母/数字/下划线/连字符，≤64 字符</span></div>
          <a-input v-model:value="solidifyForm.name" :maxlength="64" placeholder="这类做法叫什么" />
        </div>
        <div class="sf-field">
          <div class="sf-label">描述<span class="sf-sub">模型靠它判断什么时候读取这条技能</span></div>
          <a-textarea v-model:value="solidifyForm.description" :rows="2" :maxlength="1000" />
        </div>
        <div class="sf-field">
          <div class="sf-label">正文（SKILL.md）</div>
          <a-textarea v-model:value="solidifyForm.content" :rows="10" class="sf-content" />
        </div>
      </a-modal>

      <!-- 会话内查找：按消息导航 + 命中高亮（长会话里定位旧问答） -->
      <div v-if="searchOpen" class="chat-search">
        <search-outlined class="cs-ic" />
        <input ref="searchInputRef" v-model="searchQuery" class="cs-input"
               placeholder="在本会话中查找…（Enter 下一个 · Shift+Enter 上一个 · Esc 关闭）"
               @keydown.enter.exact.prevent="gotoMatch(1)"
               @keydown.shift.enter.prevent="gotoMatch(-1)"
               @keydown.esc="closeSearch" />
        <span class="cs-count">
          {{ searchQuery.trim() ? (matchedIdxs.length ? (searchPosShown + ' / ' + matchedIdxs.length) : '无匹配') : '' }}
        </span>
        <button class="app-icon-btn" :disabled="!matchedIdxs.length" title="上一个匹配" @click="gotoMatch(-1)"><up-outlined /></button>
        <button class="app-icon-btn" :disabled="!matchedIdxs.length" title="下一个匹配" @click="gotoMatch(1)"><down-outlined /></button>
        <button class="app-icon-btn" title="关闭查找" @click="closeSearch"><close-outlined /></button>
      </div>

      <!-- 工具审批走对话里的内联卡（见消息区的 .approval-card）：通知深链由引擎按 approvalId
           定位到那一张并闪一圈，不再在顶部另画一张横幅。已处理/已失效只弹一句提示。 -->

      <!-- 智能体提问不走顶部横幅：卡片已落库、等待期间断线不中止本轮，恢复由引擎在会话加载时
           按待答记录重建底部答题面板（hydratePendingAsk），与实时卡同一处、同一套交互。 -->

      <div class="messages" ref="box" @scroll="onMessagesScroll">
        <!-- 历史没取回来：这是一次失败，不是「这个会话还没有消息」。画成欢迎页会让人以为
             会话被清空了，实际是请求挂了——错误卡占住空态位置，给出原因和重试出口（与移动壳同一判据） -->
        <div v-if="historyError" class="hist-err" role="alert">
          <exclamation-circle-outlined class="he-ic" />
          <div class="he-body">
            <div class="he-t">这个会话的历史没有加载出来</div>
            <div class="he-s">{{ historyError }}</div>
          </div>
          <button class="app-btn small" type="button" @click="retryHistory">重试</button>
        </div>

        <div v-if="messages.length === 0 && !historyError" class="welcome"
             :class="{ 'welcome-setup': setupGuide.loaded && !chatDone && !guideSnoozed }">
          <BrandMark :size="44" class="welcome-mark" />
          <!-- 引导卡：聊天模型/默认模型未就绪时显示（loaded 门控——首次对账未成功前维持现状，
               宁可晚一拍引导，不给已配好的存量用户闪一帧假引导）。没有可用模型时任何提问都只会弹拦截 toast -->
          <template v-if="setupGuide.loaded && !chatDone && !guideSnoozed">
            <h2>欢迎使用问渠</h2>
            <p>先完成 2 步，即可开始对话</p>
            <div class="welcome-guide"><SetupGuide scope="chat" variant="card" /></div>
          </template>
          <template v-else>
            <WelcomeCopy :variant="isNarrow ? 'mobile' : 'pc'" :agent-name="currentAgent?.name || ''"
                         :think-on="deepThinkOn" :attach-count="pendingImages.length + pendingFiles.length"
                         :mentions="mentionNames" />
          </template>
        </div>

        <!-- 消息列表外壳与公开分享页共用 chat/ChatThread.vue：行/块/气泡布局、四个叶子的
             出现次第（正文 → 降级 → 引用来源 → 动作行）都由它定。本页只往插槽里填登录态专属的东西：
             消息分隔线、@引用/#历史引用、过程块与工具卡、错误卡、计划与审批卡、产物、
             版本切换器与更多菜单、就地编辑卡。次第换不掉，能换的只是「画什么」。 -->
        <ChatThread :messages="messages" :editing-index="editingIdx" :can-regenerate="() => true"
                    :goal="sessionGoal"
                    runnable :session-id="currentSessionId" @paste-view="openPasteView"
                    @feedback="(m, i, r) => openFeedback(m, r)"
                    @regenerate="(m, i) => regenerate(i)">
          <template #row-before="{ m, i }">
          <!-- 压缩记录：画在这条提问上方＝本轮开始前框架把早期对话折进了一次摘要，点「查看摘要」读那份全文。
               两个数据源共用这一个位子：落库的轮标记（刷新后还在）＋本轮刚压完的那一档（等回答落库前不空窗） -->
          <MsgDivider v-if="compactDividerAt(m, i) || liveCompactAt(m, i)" class="cdiv">
            <compress-outlined />
            <span>已压缩上下文</span>
            <button class="app-link-btn" type="button" :disabled="compactSummaryLoading"
                    @click="openCompactSummary">{{ compactSummaryLoading ? '读取中…' : '查看摘要' }}</button>
          </MsgDivider>
          <!-- 模型切换记录：本轮回答与前一条回答的模型展示名不同才出现（发消息产生新回答的那一刻，切换选择器本身不产生记录）。
               两侧名字都点名：只报「换成了谁」会让人读不出从哪儿换的，A → B 才是完整一句话 -->
          <MsgDivider v-if="modelSwitchInfo(m, i)" class="msd">
            <swap-outlined />
            <span>模型已切换 {{ modelSwitchInfo(m, i).fromLabel }} → {{ modelSwitchInfo(m, i).toLabel }}</span>
          </MsgDivider>
          </template>
          <template #user-meta="{ m, i }">
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
                  <span class="msg-file-name">{{ historyRefPreview(hr) }}</span>
                </span>
              </div>
          </template>
          <template #body-before="{ m, i }">
              <div v-if="showAgentTag(m, i) && debugDisplayVisible" class="agent-tag">
                <AgentAvatar class="agent-tag-ava" :agent="agentBadgeOf(agentList, m.agentId, m.agentName)" :size="14" />
                <span>由「{{ m.agentName }}」回答</span>
              </div>
          </template>
          <template #body="{ m, i }">
              <!-- 整轮用时那一条：它同时是过程块的开关。跑着的时候过程自动展开，答完收起只留正文 -->
              <TurnBar v-if="m.role === 'ai'" :m="m" />
              <!-- 技能命中不再在回答头顶放芯片（点「看这一轮的过程」账本面板仍可回看 skill_hit 投影） -->
              <!-- 正文 / 过程分离（统一正文流）：正文＝整段 content，工具调用只作过程步骤、不切正文；
                   过程（思考/过程/工具）全收在顶部过程块里。唯一例外是插话：它是用户自己说的话，
                   按时间线里记录的真实位置插在前后两片正文之间，不堆在正文头顶。 -->
              <div v-if="m.role === 'ai'" class="md" :data-msg-index="i">
                <div v-if="turnOpen(m) && turnProcessRows(m).length" class="tl-turn-process">
                <template v-for="(seg, si) in turnProcessRows(m)" :key="si">
                  <!-- 思考段：这一轮模型在想什么，在过程块里占一行。只有正在长的那一段自动展开，其余折叠。
                       被过程簇收进去的那些不走这一支——它们落在帽体清单里所属那一步的展开详情内（见 projections 的 clusterLiveRows） -->
                  <div v-if="seg.kind === 'think'" class="tl-think-block">
                    <button class="tl-think-head" type="button" :aria-expanded="thinkSegOpen(m, seg)" @click="toggleThinkSeg(m, seg)">
                      <span class="tl-think-title" :class="{ running: thinkGrowing(m, seg) }">深度思考</span>
                      <!-- 标题右边挂这一段思考的「开头几个字」：块收着也读得出它在想什么，不必先点开；
                           一行小字、按可用宽度省略，展开时它仍是这段的提要（正文滚长了也当锚点）。
                           标题与提要之间点一颗分隔点，与工具行的「名称 · 摘要」同一套写法。 -->
                      <template v-if="thinkPeek(m, seg)">
                        <span class="tl-live-sep" aria-hidden="true"></span>
                        <span class="tl-think-peek" :title="thinkPeek(m, seg)">{{ thinkPeek(m, seg) }}</span>
                      </template>
                      <caret-right-outlined class="tl-caret" :class="{ open: thinkSegOpen(m, seg) }" />
                    </button>
                    <div v-show="thinkSegOpen(m, seg)" v-follow-bottom="thinkGrowing(m, seg)" class="tl-think">
                      <AnswerBody :content="thinkSlice(m, seg)" :streaming="thinkGrowing(m, seg)" />
                    </div>
                  </div>
                  <!-- 过程簇头（DSH ProcessGroupHeader 同构）：无边框一行，行首活动图标（悬停/展开让位给下开三角），
                       文字=做过什么的罗列（收起）或正在做什么（跑动，带扫光）。展开后帽体清单直接长在下面。 -->
                  <div v-else-if="seg.kind === 'cluster'" class="tl-cluster" :class="{ open: clusterOpen(m, seg) }">
                    <button class="tl-cluster-bar" type="button" :aria-expanded="clusterOpen(m, seg)" @click="toggleCluster(m, seg)">
                      <span class="tl-cluster-ic">
                        <span class="tl-ic-a">
                          <close-circle-outlined v-if="clusterHasError(seg) && !clusterRunning(seg)" class="ic-err" />
                          <component :is="clusterIconOf(seg)" v-else />
                        </span>
                        <span class="tl-ic-c"><down-outlined /></span>
                      </span>
                      <span class="tl-cluster-label" :class="{ shimmer: clusterRunning(seg) || (m.loading && seg.grow) }">
                        {{ clusterLiveText(m, seg) || clusterTitle(seg) }}<template v-if="clusterRunning(seg) && runBoardText(m)"> · {{ runBoardText(m) }}</template>
                      </span>
                    </button>
                    <!-- 帽体（DSH 的 .body 同参）：跑轮中自动开并贴底跟随最新一步、用户点开停顶部从头看；
                         min(400px,50vh) 内滚、上下边缘 24px 渐隐。一行一步（图标+动词+2px 点+参数摘要），
                         点行在帽内展开该步详情（入参/输出/错误）——页面高度恒定。 -->
                    <div v-if="clusterOpen(m, seg)" v-follow-bottom="!!(m.loading && seg.grow)" v-edge-fade class="tl-cluster-live">
                      <template v-for="(lr, li) in clusterLiveRows(seg)" :key="li">
                        <div v-if="lr.t" class="tl-live-row"
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
                          <span class="tl-live-name" :title="toolDesc(lr.t.name, lr.t.hint)">{{ toolLabel(lr.t.name, lr.t.label) }}</span>
                          <template v-if="toolBrief(lr.t)">
                            <span class="tl-live-sep" aria-hidden="true"></span>
                            <span class="tl-live-brief" :class="{ shimmer: lr.t.status === 'start' && !lr.t.denied }">{{ toolBrief(lr.t) }}</span>
                          </template>
                          <span v-if="lr.t.denied" class="tl-live-flag" title="被守卫挡下，没有真正执行">被挡下</span>
                        </div>
                        <!-- 单步详情（点行展开，DSH 的 IN/OUT 卡同构）：分段 grid、每段独立内滚、段间细分割线。
                             这一步之前/之后的思考挂在这里：原先挂在行尾当一颗「深度思考 N 字」标签，
                             被摘要的弹性宽度顶到整行最右、飘在页面空白处（且帽体里没有正文，点开也没东西看）。 -->
                        <div v-if="lr.t && lr.t._open && (lr.t.args || liveOutput(lr.t) || lr.t.error || (lr.notes && lr.notes.length))" class="tl-live-detail" @click.stop>
                          <template v-if="lr.notes && lr.notes.length">
                            <div class="tl-live-think">
                              <div class="tl-live-think-label">思考</div>
                              <div v-for="(nn, ni) in lr.notes" :key="'n' + ni" class="tl-think-seg">
                                <NoteBody :m="m" :note="nn" always bare />
                              </div>
                            </div>
                            <div v-if="lr.t.args || liveOutput(lr.t) || lr.t.error" class="tl-io-div" aria-hidden="true"></div>
                          </template>
                          <div v-if="lr.t.args" class="tl-io-sec">
                            <div class="tl-io-label">入参</div>
                            <div class="tl-io-body">
                              <pre class="tl-io">{{ prettyIo(lr.t.args) }}</pre>
                              <ToolSpillButton v-if="lr.t.argsSpillId" :id="lr.t.argsSpillId" :tool="lr.t.name" kind="入参" />
                            </div>
                          </div>
                          <div v-if="lr.t.args && liveOutput(lr.t)" class="tl-io-div" aria-hidden="true"></div>
                          <div v-if="liveOutput(lr.t)" class="tl-io-sec">
                            <div class="tl-io-label">{{ lr.t.status === 'start' ? '实时输出' : (lr.t.result ? '输出' : '输出（执行期）') }}</div>
                            <div class="tl-io-body">
                              <pre class="tl-io" :class="{ live: lr.t.status === 'start' }">{{ liveOutput(lr.t) }}</pre>
                              <ToolSpillButton v-if="lr.t.spillId" :id="lr.t.spillId" :tool="lr.t.name" kind="输出" />
                            </div>
                          </div>
                          <div v-if="(lr.t.args || liveOutput(lr.t)) && lr.t.error" class="tl-io-div" aria-hidden="true"></div>
                          <div v-if="lr.t.error" class="tl-io-sec">
                            <div class="tl-io-label">错误</div>
                            <div class="tl-io-body">
                              <pre class="tl-io tl-io-err">{{ lr.t.error }}</pre>
                              <ToolSpillButton v-if="lr.t.spillId" :id="lr.t.spillId" :tool="lr.t.name" kind="输出" />
                            </div>
                          </div>
                          <!-- 咨询另一位助手：平铺行的详情里也挂子对话卡（问它/执行过程/它的回答
                               +「完整过程」抽屉），两条路径同构 -->
                          <SubAgentChatCard v-if="isDispatchTool(lr.t.name) && (lr.t.subChat || (lr.t._steps && lr.t._steps.length))"
                                            :t="lr.t"
                                            :agent="lr.t.subChat ? agentBadgeOf(agentList, lr.t.subChat.agentId, lr.t.subChat.agentName) : null"
                                            :sources="m.sources"
                                            :session-id="currentSessionId || ''" :turn-id="m.turnId || ''" />
                        </div>
                        <!-- 还没换来工具的尾部思考/独白：它就是「当前这一步」，自成一节。
                             转圈只在真的还在长时出现；整组可点开看正文。正文另起一块，不能挤在 flex 行里。 -->
                        <template v-if="!lr.t">
                          <div class="tl-live-row" :class="{ run: tailNotesGrowing(m, lr.tailNotes), open: tailOpen(m, seg) }">
                            <button type="button" class="tl-live-tail-btn" @click.stop="toggleTail(m, seg)">
                              <span class="tl-live-ic">
                                <span class="tl-ic-a">
                                  <loading-outlined v-if="tailNotesGrowing(m, lr.tailNotes)" spin />
                                  <check-outlined v-else />
                                </span>
                                <span class="tl-ic-c"><down-outlined /></span>
                              </span>
                              <span class="tl-live-name" :class="{ shimmer: tailNotesGrowing(m, lr.tailNotes) }">{{ tailNotesTag(lr.tailNotes) }}</span>
                              <!-- 尾行右边同样跟上这一段（组）的开头几个字：帽体收着时也读得出它在想什么 -->
                              <template v-if="tailNotesPeek(m, lr.tailNotes)">
                                <span class="tl-live-sep" aria-hidden="true"></span>
                                <span class="tl-live-peek">{{ tailNotesPeek(m, lr.tailNotes) }}</span>
                              </template>
                            </button>
                          </div>
                          <div v-if="tailOpen(m, seg)" class="tl-live-tail-body">
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
                    <button class="tl-process-head" type="button" @click="toggleProc(m, seg)">
                      <span class="tl-process-title">执行过程</span>
                      <span class="tl-caret" :class="{ open: procOpen(m, seg) }"><caret-right-outlined /></span>
                    </button>
                    <div v-show="procOpen(m, seg)" class="tl-process">{{ procSlice(m, seg) }}</div>
                  </div>
                  <!-- 产物段不在过程块里渲染：产物统一沉底（时间线数据仍保留 artifact 段以备后续） -->
                  <div v-else class="tl-group">
                    <button v-if="groupHasBar(seg)" class="tl-group-bar" type="button" @click="seg.tools[0]._groupOpen = !seg.tools[0]._groupOpen">
                      <loading-outlined v-if="groupRunning(seg)" spin class="tool-ic tool-ic-run" />
                      <close-circle-outlined v-else-if="groupHasError(seg)" class="tool-ic tool-ic-err" />
                      <check-outlined v-else class="tool-ic tool-ic-ok" />
                      <span>执行了 {{ executedCount(seg.tools) }} 个操作</span>
                      <span v-if="runBoardText(m)" class="run-board">{{ runBoardText(m) }}</span>
                      <NoteTag v-for="(nt, ni) in seg.notes" :key="ni" :m="m" :note="nt" />
                      <span class="tl-caret" :class="{ open: seg.tools[0]._groupOpen }"><caret-right-outlined /></span>
                    </button>
                    <!-- 有组条时全文挂在组条下面（批次折叠着也点得开）；没有组条时挂在它所属那张卡下面 -->
                    <NoteBody v-if="groupHasBar(seg)" v-for="(nt, ni) in seg.notes" :key="'note' + ni" :m="m" :note="nt" />
                    <div v-if="seg.tools.length === 1 || seg.tools[0]._groupOpen" class="tl-group-body" :class="{ solo: seg.tools.length === 1 }">
                      <div v-for="(t, ti) in seg.tools" :key="ti" class="tl-card" :data-spawn="spawnAnchorOf(t)"
                           :class="{ run: t.status === 'start', err: t.status === 'error' }">
                        <button class="tl-card-head" type="button" @click="toggleToolCard(t)">
                          <loading-outlined v-if="t.status === 'start'" spin class="tool-ic tool-ic-run" />
                          <check-outlined v-else-if="t.status === 'done'" class="tool-ic tool-ic-ok" />
                          <close-circle-outlined v-else class="tool-ic tool-ic-err" />
                          <span class="tool-name" :title="toolDesc(t.name, t.hint)">{{ toolLabel(t.name, t.label) }}</span>
                          <code v-if="toolBrief(t)" class="tl-brief">{{ toolBrief(t) }}</code>
                          <!-- 派遣那一支跑到第几步了：子轮的过程帧原样转发过来（subagent_step），
                               只活这一次、不落库，所以刷新后这一行是空的——卡片自己退回「跑了 N 步」 -->
                          <span v-if="t._live && t.status === 'start'" class="tl-live" :title="t._live">{{ t._live }}</span>
                          <span v-if="t.attempts > 1" class="tool-dur">重试 {{ t.attempts - 1 }} 次</span>
                      <!-- 脚本扇出的规模：不点开卡片也能看出"这一次跑了多少个子调用" -->
                      <span v-if="fanHasBoard(t.fanOut)" class="tool-dur">{{ fanHead(t) }}</span>
                      <span v-if="fanRun(t)" class="fan-run">{{ fanRun(t) }}</span>
                          <span v-if="t.status === 'error'" class="tool-fail">失败</span>
                          <!-- 这一轮的思考/独白并到步的行尾（不各占一行标题，免得展开态一半高度由它买单）：
                               它本来就长在「想了这些才动手」的那个位置；标签只写类别名，量级展开后自己看 -->
                          <NoteTag v-if="ti === 0 && !groupHasBar(seg)" v-for="(nt, ni) in seg.notes" :key="ni" :m="m" :note="nt" />
                          <span class="tl-caret tl-caret-btn" :class="{ open: toolCardOpen(t) }"><caret-right-outlined /></span>
                        </button>
                        <NoteBody v-if="!groupHasBar(seg)" v-for="(nt, ni) in seg.notes" :key="'note' + ni" :m="m" :note="nt" />
                        <!-- askUser 问答记录：可折叠（默认展开），刷新/历史恢复同构 -->
                        <AskRecordCard v-if="t.name === 'askUser' && t.status !== 'start'" :t="t" />
                        <!-- 咨询另一位助手：这一问一答在过程块里成卡（那份 subChat 随后端工具卡一起落库，
                             所以刷新/换设备与实时同构）；老消息没这份就不渲染，退回上面的普通工具卡。
                             直播期（subChat 未到、_steps 已有）挂执行中形态：卡内时间线逐步追加 -->
                        <SubAgentChatCard v-if="(t.subChat || (t._steps && t._steps.length)) && toolCardOpen(t)" :t="t"
                                          :agent="t.subChat ? agentBadgeOf(agentList, t.subChat.agentId, t.subChat.agentName) : null"
                                          :sources="m.sources"
                                          :session-id="currentSessionId || ''" :turn-id="m.turnId || ''" />
                        <div v-if="t._open" class="tl-card-body">
                      <!-- 脚本扇出清单：一次运行内部调了什么、各多久、有没有被挡下。
                           子调用刻意不各占一张顶层卡片（会被刷成一堵墙），结构就收在这里 -->
                      <template v-if="fanHasBoard(t.fanOut)">
                        <div class="tl-io-label">{{ fanTitle(t.fanOut) }}</div>
                        <div class="fan-list">
                          <div v-for="(s, si) in t.fanOut.items" :key="si" class="fan-row"
                               :class="{ err: s.status === 'error', run: s.status === 'start' }">
                            <span class="fan-idx">{{ si + 1 }}</span>
                            <span class="fan-name">{{ toolLabel(s.name, s.label) }}</span>
                            <span class="fan-dur">{{ fanRowState(s) }}</span>
                            <span class="fan-brief">{{ s.brief }}</span>
                          </div>
                          <div v-if="t.fanOut.omitted" class="fan-more">另有 {{ t.fanOut.omitted }} 次未列出</div>
                        </div>
                        <!-- 额度与并发上限：跑着的时候看这条才知道"离撞上限还有多远"，
                             终态那份也带着——刷新后同样能看出这一次占了几成预算 -->
                        <div v-if="fanBudget(t.fanOut)" class="fan-budget">{{ fanBudget(t.fanOut) }}</div>
                      </template>
                          <template v-if="t.args">
                            <div class="tl-io-label">入参</div>
                            <pre class="tl-io">{{ prettyIo(t.args) }}</pre>
                            <ToolSpillButton v-if="t.argsSpillId" :id="t.argsSpillId" :tool="t.name" kind="入参" />
                          </template>
                          <template v-if="liveOutput(t)">
                            <div class="tl-io-label">{{ t.status === 'start' ? '实时输出' : (t.result ? '输出' : '输出（执行期）') }}</div>
                            <pre class="tl-io" :class="{ live: t.status === 'start' }">{{ liveOutput(t) }}</pre>
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
                <template v-for="row in turnBodyRows(m)" :key="row.key">
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
                  <span v-if="a.size" class="tl-steer-att-size">{{ fmtSize(a.size) }}</span>
                  </span>
                  </div>
                  <span class="tl-steer-txt">{{ row.text }}</span>
                  </div>
                  </div>
                  <AnswerBody v-else class="tl-answer" :content="row.content"
                              :images="m.images" :sources="m.sources" :msg-index="i"
                              runnable :session-id="currentSessionId"
                              :streaming="!!(m.loading && !m.failed && row.last)" />
                </template>
              </div>
              <!-- 提问与回答同一条排版口径（markdown，breaks:true 所以换行照旧保留）。
                   但**提问走 literal**：图片占位与引用角标都不替换——提问里的 [图片N] / [N]
                   是用户手打的字面量，替换掉会改他的原文、还会让自己的附件多渲染一遍（分享页同此口径） -->
              <AnswerBody v-else :content="pendingAnswerText(m.content) || m.content" :msg-index="i"
                          :images="m.role === 'ai' ? m.images : []"
                          :sources="m.role === 'ai' ? m.sources : []"
                          :literal="m.role !== 'ai'"
                          runnable :session-id="currentSessionId"
                          :streaming="m.loading && !m.failed" />
          </template>
          <template #body-after="{ m, i }">
              <!-- 错误卡（独立于正文）：回答中断时保留已流出内容，这里给分类文案 + 重试 + 异常详情折叠。
                   只管回答那一行：什么都没产出的轮次错误挂在提问上，见下面 #actions-after 那一条 -->
              <div v-if="m.errorCard && m.role === 'ai'" class="msg-error-card" :class="{ net: m.errorCard.kind === 'interrupted' || m.errorCard.kind === 'unreachable' }">
                <div class="mec-head"><close-circle-outlined class="mec-ic" /> {{ errorBrief(m.errorCard.message, m.errorCard.kind) }}</div>
                <div v-if="m.errorCard.kind === 'interrupted'" class="mec-net-tip">
                  已生成的部分已保存。重新生成会重跑本轮并重新计费；若你刚切后台回来，这通常是系统挂起了连接。
                </div>
                <!-- 未送达：一个字都还没生成，别套用「已生成的部分已保存」那套话 -->
                <div v-else-if="m.errorCard.kind === 'unreachable'" class="mec-net-tip">
                  这条提问没有发出去；重新生成会把它重新发送一遍。
                </div>
                <div class="mec-actions">
                  <button class="app-btn ghost small" @click="regenerate(i)"><redo-outlined /> 重新生成</button>
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
              <PlanCard v-if="m.planCard" :card="m.planCard" :deep="!!sessionGoal" :sid="currentSessionId || ''"
                        :pkey="m.planCard.id || i" :progress="planExecProgressOf(m)"
                        :exec-detail="planExecDetailOf(m)"
                        :countdown="m.planCard.status === 'pending' ? planCountdownText : ''"
                        :expired="m.planCard.status === 'pending' && planExpired"
                        :artifact-count="(m.artifacts || []).length"
                        :adjusts="planAdjustsOfMsg(m)"
                        @open="(v, tab) => openPlanViewer(m, i, v, tab)"
                        @adjust="openPlanAdjust(m)"
                        @locate-replanned="locateReplanned(m)"
                        @locate-source="locatePlanSource(m)" />
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
                  <span class="approval-tool" :title="toolDesc(a.tool, a.hint)">{{ toolLabel(a.tool, a.label) }}</span>
                  <code v-if="a.label && a.label !== a.tool" class="approval-raw">{{ a.tool }}</code>
                  <span class="approval-clock" :class="{ over: approvalLeftSecOf(a) <= 0 }">
                    <clock-circle-outlined />
                    {{ approvalLeftSecOf(a) > 0 ? '剩 ' + approvalLeftSecOf(a) + ' 秒' : '已超时' }}
                  </span>
                </div>
                <!-- 「为什么问」：读过外部内容后的强制确认 / 审批档要求，与守卫同源；没有就不占行 -->
                <p v-if="a.reason" class="approval-why">{{ a.reason }}</p>
                <pre v-if="approvalArgsText(a.args)" class="approval-args">{{ approvalArgsText(a.args) }}</pre>
                <div class="approval-actions">
                  <span class="approval-hint">{{ approvalLeftSecOf(a) > 0 ? '批准后立即执行这一步' : '已超时，本轮将按拒绝继续' }}</span>
                  <button class="app-btn ghost small" :disabled="a.busy || approvalLeftSecOf(a) <= 0"
                          @click="resolveApproval(m, a, false)">拒绝</button>
                  <!-- 「批准执行」带下拉：主键是默认动作（只批这一步），箭头里多一档「本次会话都允许」——
                       同一工具后续不再弹卡；箭头单独成键，点主键不会误开菜单 -->
                  <span class="approval-approve-split">
                    <button class="app-btn small" :disabled="a.busy || approvalLeftSecOf(a) <= 0"
                            @click="resolveApproval(m, a, true)">批准执行</button>
                    <a-dropdown :trigger="['click']" placement="bottomRight">
                      <button class="app-btn small approval-caret" type="button"
                              :disabled="a.busy || approvalLeftSecOf(a) <= 0"
                              title="更多批准方式" aria-label="更多批准方式"><down-outlined /></button>
                      <template #overlay>
                        <a-menu @click="() => resolveApproval(m, a, true, 'session')">
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
              <ArtifactPendingCard v-if="m.role === 'ai' && artifactPendingOf(m)" :pending="artifactPendingOf(m)" />
              <!-- 交付包：单件与多件共用同一张卡（单件左缩略+动作，多件包形态+清单）；
                   流式中的气泡按消息对象锚定自己的包，第一件产物到达即成卡；
                   实时到达的产物播一次入场，历史恢复不播。 -->
              <DeliveryCard v-if="m.role === 'ai' && deliveryCardOfMessage(m)"
                            :delivery="deliveryCardOfMessage(m)"
                            :fresh="freshDeliveryOf(m)"
                            @view="openDelivery"
                            @zip="downloadDeliveryZip" />
              <!-- 每轮文件改动卡：这一轮在本机工作区里改了哪些文件、各多少行；行点击进预览台 diff 档。
                   停止/中断的轮也可能改过文件——只要服务端对比出来就照挂，刷新后按消息行的 changes 重建 -->
              <TurnChangesCard v-if="m.role === 'ai' && m.changes" :changes="m.changes" :message-id="m.messageId || ''" />
          </template>
          <template #sources="{ m, i }">
              <SourceList v-if="m.role === 'ai' && (retrievalLineTitle(m) || toolSearchQueries(m).length || (m.sources && m.sources.length))"
                          :sources="m.sources" :title="retrievalLineTitle(m)" :note="hitsNote(m)"
                          :terms="m.retrieved && m.retrieved.terms" :tool-queries="toolSearchQueries(m)"
                          :question="questionFor(i)" :can-feedback="true"
                          show-snippet v-model:open="m.rtOpen" />
          </template>
          <template #sources-after="{ m, i }">
              <!-- 只在最新一轮出现（panelAi=最后一条 AI 消息，含进行中）：更早那几轮的建议已被后面的对话取代，
                   每轮都留着会让整页散落着可点的旧问句，翻历史时容易误点重发一个早问过的话题 -->
              <div v-if="m.role === 'ai' && m === panelAi && m.related && m.related.length" class="related">
                <span class="related-label">接下来可以：</span>
                <span v-for="(q, qi) in m.related" :key="qi" class="related-tag" @click="ask(q)">{{ q }}</span>
              </div>
          </template>
          <template #actions-prefix="{ m, i }">
                  <!-- 重新生成的多版本切换器：本会话内用内存版本（versions）即时切换；
                       刷新后/编辑重发用持久分支（variantCount）走后端切换，旧版本按保留期留存 -->
                  <div v-if="(m.versions && m.versions.length > 1) || m.variantCount > 1" class="ver-switch"
                       title="这一回答有多个版本，可来回切换（旧版本按保留期清理）">
                    <button class="ver-btn" :disabled="!canSwitchPrev(m)" @click="switchBranch(i, -1)">‹</button>
                    <span class="ver-idx">{{ verLabel(m) }}</span>
                    <button class="ver-btn" :disabled="!canSwitchNext(m)" @click="switchBranch(i, 1)">›</button>
                  </div>
          </template>
          <template #actions-more="{ m, i }">
                <!-- 固化为技能：把这一轮「问题→做法」沉淀成一条可复用的 SKILL.md（弹窗预填、改两行就能用）。
                     只挂在落库的 AI 回答上（#more 插槽本就在 messageId 判据内），分享页不渲染这一档 -->
                <a-tooltip title="把这一轮的做法固化为技能">
                  <button class="app-icon-btn" @click="openSolidify(i)"><thunderbolt-outlined /></button>
                </a-tooltip>
                <a-dropdown :trigger="['hover', 'click']">
                  <button class="app-icon-btn" title="更多"><more-outlined /></button>
                  <template #overlay>
                    <a-menu @click="({ key }) => onMoreAction(key, i)">
                      <a-menu-item v-if="debugEntryVisible" key="debug"><bug-outlined style="margin-right:8px" />检索调试</a-menu-item>
                      <!-- 加入评测集 = 差评回流固化到检索评测集（/api/ai/eval 仅管理员可用），属调参排障动作：
                           与「检索调试」同一开关（chat.retrievalDebugEnabled）控制，不给普通用户露出必 403 的入口 -->
                      <a-menu-item v-if="debugEntryVisible && m.sources && m.sources.length" key="addEval"><dislike-outlined style="margin-right:8px" />加入评测集</a-menu-item>
                      <a-menu-item v-if="m.turnId" key="events"><history-outlined style="margin-right:8px" />看这一轮的过程</a-menu-item>

                      <a-menu-item key="export"><download-outlined style="margin-right:8px" />导出 Markdown</a-menu-item>
                      <a-menu-item key="deleteRound" style="color:#cf1322"><delete-outlined style="margin-right:8px" />删除本轮对话</a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
          </template>
          <template #actions-extra="{ m, i }">
                  <!-- 那一个数=本轮结束时的上下文占用（与输入框容量圆环同一个分子）。
                       悬停里把三个口径分开报名、一行一个，不并列成能相加的样子：占用 ≠ 累计送入 ≠ 输出。
                       占用为 0 的历史轮整块不显示——不拿别的数凑一个「这一轮用了多少」出来。 -->
                  <a-tooltip v-if="usageCtxTokens(m.tokens)" :title="usageTip(m.tokens)"
                             :overlay-inner-style="{ whiteSpace: 'pre-line' }">
                    <span class="msg-tokens">上下文{{ m.tokens.outputIsReal ? ' ' : ' ≈' }}{{ fmtTokens(usageCtxTokens(m.tokens)) }} tokens</span>
                  </a-tooltip>
                  <span v-if="m.time" class="msg-time-inline">{{ fmtMsgTime(m.time) }}</span>
          </template>
          <template #actions-after="{ m, i }">
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
              <close-circle-outlined /> {{ errorBrief(m.errorCard.message, m.errorCard.kind) }}
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
                  <button class="ver-btn" :disabled="(m.variantIndex || 1) <= 1 || variantSwitching" @click="switchBranch(i, -1)">‹</button>
                  <span class="ver-idx">{{ m.variantIndex || 1 }}/{{ m.variantCount }}</span>
                  <button class="ver-btn" :disabled="(m.variantIndex || 1) >= m.variantCount || variantSwitching" @click="switchBranch(i, 1)">›</button>
                </div>
                <a-tooltip title="复制问题" placement="top">
                  <copy-outlined class="app-icon-btn" @click="copyUserMessage(m)" />
                </a-tooltip>
                <a-tooltip title="编辑此问题，从这一轮重新生成" placement="top">
                  <edit-outlined class="app-icon-btn" @click="editMessage(i)" />
                </a-tooltip>
              </template>
              <span v-if="m.time" class="msg-time-inline">{{ fmtMsgTime(m.time) }}</span>
            </div>
            <!-- 就地编辑卡：原位替换该气泡的紧凑右对齐卡。除正文外，图片/附件可增删、@ 引用可移除
                 （随行内容播种自原消息；深度思考/技能/# 历史引用沿用原轮不展示）。
                 确认后从这一轮整段重新生成，旧分支软删留档可切回 -->
            <div v-if="m.role === 'user' && editingIdx === i" class="msg-inline-edit">
              <textarea :ref="setEditingRef" v-model="editingText" class="msg-inline-edit-input" rows="1"
                        placeholder="编辑这一问题…" @input="autosizeEditing" @paste="onEditPaste" @keydown="onEditKeydown" />
              <div v-if="editImgs.length" class="pending-imgs">
                <div v-for="(u, pi) in editImgs" :key="pi" class="pending-img">
                  <img :src="resolveImg(u.dataUrl)" alt="待发送图片" @click="previewEditImage(pi)" />
                  <span class="pending-del" @click.stop="removeEditImage(pi)">×</span>
                </div>
              </div>
              <div v-if="editAtts.length" class="pending-files">
                <template v-for="(f, fi) in editAtts" :key="fi">
                  <div v-if="f.paste" class="pending-file paste-card" :class="{ err: !!f.error, expired: !!f.expired }"
                       :title="f.expired ? '粘贴文本的正文只留在当轮内存里，刷新后不随历史保留' : (f.error || '点击查看粘贴的全文')" @click="openPasteView(f)">
                    <span class="paste-card-ic"><file-text-outlined /></span>
                    <span class="paste-card-txt">
                      <span class="paste-card-name">{{ pasteTitle(f.name) }}</span>
                      <span class="paste-card-sub">{{ pasteSub(f) }}</span>
                    </span>
                    <span class="pending-file-del" @click.stop="removeEditAtt(fi)">×</span>
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
                    <span v-else class="pending-file-size">{{ fmtSize(f.size) }}</span>
                    <span class="pending-file-del" @click.stop="removeEditAtt(fi)">×</span>
                  </div>
                </template>
              </div>
              <div v-if="editMentions.length" class="edit-mentions">
                <span v-for="(mm, mi2) in editMentions" :key="mm.type + ':' + mm.id" class="at-chip mention-chip"
                      :class="'mention-' + mm.type"
                      :title="mm.type === 'kb' ? '本轮检索收窄到该知识库'
                              : (mm.type === 'agent' ? '本轮会强制咨询这位助手：它带着自己的知识库查一遍，回答仍由当前助手汇总' : '该文档内容直接带入本轮上下文')">
                  <robot-outlined v-if="mm.type === 'agent'" class="at-chip-ic" />
                  <database-outlined v-else-if="mm.type === 'kb'" class="at-chip-ic" />
                  <file-text-outlined v-else class="at-chip-ic" />
                  <span class="at-chip-name">{{ mm.name || mm.id }}</span>
                  <span class="at-chip-del" title="移除该引用" @click="removeEditMention(mi2)">×</span>
                </span>
              </div>
              <!-- 技能（编辑卡内可增删）：与主输入框同一份 skillList/toggleSkill -->
              <div v-if="editSkills.length" class="edit-mentions">
                <span v-for="n in editSkills" :key="n" class="at-chip skill-chip">
                  <span class="skill-chip-ava" :style="skillAvaStyle(n)">{{ n.slice(0, 1) }}</span>
                  <span class="at-chip-name">{{ n }}</span>
                  <span class="at-chip-del" title="移除该技能" @click="removeEditSkill(n)">×</span>
                </span>
              </div>
              <!-- # 引用的历史问答（编辑卡内可增删）：候选仍是 histPool（同会话已落库的消息） -->
              <div v-if="editHistRefs.length" class="edit-mentions">
                <span v-for="(h, hi) in editHistRefs" :key="h.messageId" class="at-chip"
                      :title="'引用的历史' + (h.role === 'assistant' ? '回答' : '提问') + (h.digest ? '：' + h.digest : '')">
                  <history-outlined class="at-chip-ic" />
                  <span class="at-chip-name">{{ h.digest || (h.role === 'assistant' ? '回答' : '提问') }}</span>
                  <span class="at-chip-del" title="移除该引用" @click="removeEditHistRef(hi)">×</span>
                </span>
              </div>
              <div class="msg-inline-edit-actions">
                <!-- 添加入口与主输入框同一个「+」分层面板（同一块面板，编辑态下 Teleport 进这张卡）：
                     上传附件 / 引用资料（含技能）/ 引用历史问答共用一套候选、一套搜索与键盘导航。
                     快捷命令不进来：那些操作会改会话状态，与"改这一问"无关 -->
                <button class="app-icon-btn add-btn" :class="{ 'toolbar-btn-on': (addOpen && addOnEdit) || editAddCount }"
                        title="添加：上传文件图片 / 引用资料 / 引用历史问答" @click="toggleEditAddPanel">
                  <plus-outlined />
                </button>
                <span class="msg-inline-edit-hint"><info-circle-outlined /> 编辑后将从此处重新开始对话，已有产物不会被删除</span>
                <button class="app-btn ghost" @click="cancelEdit">取消</button>
                <button class="app-btn" :disabled="editingBusy || (!editingText.trim() && !editImgs.length && !editAtts.length)"
                        @click="confirmEdit">发送</button>
              </div>
              <div class="add-mount"></div>
              <input :ref="setEditAttachInputRef" type="file" multiple style="display:none" @change="onEditAttachChange" />
            </div>
            <!-- 目标终态卡：挂在「设为目标」那条回声下（goalId 认，刷新从历史重建）。
                 删除记录后 sessionGoal 变 null，这张卡随之一并消失 -->
            <GoalTerminalCard v-if="m.role === 'user' && m.goalCommand && goalTerminalCard && goalTerminalCard.goalId === m.goalCommand"
                              :goal="goalTerminalCard" :title="goalTerminalTitle" :note="goalTerminalNote"
                              :version="goalVersionLabel" :has-delivery="!!goalDelivery"
                              @view="openGoalViewer"
                              @delivery="openDelivery(goalDelivery)"
                              @edit="startGoalInlineEdit"
                              @restart="startGoal(goalTerminalCard.objective)"
                              @delete="runGoalAction('delete')" />
          </template>
        </ChatThread>
        <!-- 手动 /compact 的进行中：只认手动那一路。轮内自动压缩的进度说在气泡那条状态行上
             （一轮里框架可以压好几次，在这儿挂一条每压完撤走、下次又长出来的线，就是反复弹出），
             手动这一路没有正在跑的那一轮、没有状态行可借，当场这一行是它唯一的进度 -->
        <MsgDivider v-if="manualCompacting" class="cn-tail">
          <loading-outlined spin />
          <span>正在压缩上下文</span>
        </MsgDivider>
        <!-- 手动压缩当场留下的记录：下一轮回答落库的摘要覆盖点会接上这件事，届时由上面那条持久判据
             画在同一条缝上，所以这里只是提前把线补出来，不会和它重复 -->
        <MsgDivider v-else-if="compactNotice && compactNotice.sid === currentSessionId" class="cn-tail">
          <compress-outlined />
          <span>已压缩上下文</span>
          <button class="app-link-btn" type="button" @click="openCompactSummary">查看摘要</button>
        </MsgDivider>
        <!-- 尾随留白：本轮问题下方补足一屏，使贴底落点=问题置顶（回答长过一屏后归零，恢复正常贴底跟尾） -->
        <div v-if="tailSpacer > 0" class="tail-spacer" :style="{ height: tailSpacer + 'px' }" aria-hidden="true"></div>
        <!-- 回到底部：贴在消息列右下角、输入区卡片（任务清单/目标条/插话队列…）正上方。
             放在消息流末尾 + sticky，卡片长多高都不会被压住；右对齐到 --chat-col 那一条右边 -->
        <div v-if="!stickToBottom && messages.length" class="jump-latest-wrap">
          <button type="button" class="jump-latest" title="回到底部" @click.stop="scrollForce">↓</button>
        </div>
      </div>

      <!-- 输入区：卡片外壳 / 正文与 chip 同流 / 工具条 / 拖拽落点全在 chat/ChatInput.vue，
           与公开分享页共用同一份实现（改一次两页同时生效）。这里只往插槽里填本页自己的东西：
           卡片外的一列（任务清单 / 目标条 / 插话队列）、智能体胶囊、模型与思考、三块面板，
           以及顶替态的两张卡（提问 / 计划确认栏）。 -->
      <ChatInput
        ref="composerRef" v-model="text" :resolve="chipOf" :placeholder="composerPlaceholder"
        :sending="loading" steerable :can-send="canSend" :stop-armed="escArmed"
        :send-title="goalTurnStreaming ? '插话：先回答你这句，目标随后自动继续' : ''"
        attach-title="添加：上传文件图片 / 引用资料 / 引用历史问答 / 快捷命令"
        :attach-on="(addOpen && !addOnEdit) || pickedSkills.length > 0"
        :replace-box="!!(pendingAsk || pendingPlan)"
        @attach="toggleAddPanel" @send="sendNow" @stop="stopNow" @files="onDropFiles"
        @paste="onComposerPaste" @keydown="onInputKeydown" @input="syncPanelQuery"
        @click="syncPanelQuery" @update:keys="onKeys" @chip-click="onChipClick" @ready="syncChips">
        <template #above>
          <!-- 工作区没准备好（未绑目录 / 执行器离线）：一句人话 + 一个能直接点的动作，不挡对话 -->
          <div v-if="workspaceSetupHint" class="local-ws-hint" role="status" aria-live="polite">
            <FolderOpenOutlined class="lwh-ic" />
            <span class="lwh-text">{{ workspaceSetupHint }}</span>
            <button v-if="!sessionWorkspace?.roots?.length" type="button" class="lwh-act"
                    @click="goBindWorkspace">去绑定文件夹</button>
            <button v-else type="button" class="lwh-act" :disabled="workspaceRefreshing"
                    @click="refreshSessionWorkspace">
              {{ workspaceRefreshing ? '检查中…' : '重新检查执行器' }}
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
          <div v-if="todoBarVisible"
               class="todo-bar" :class="{ collapsed: !todoOpen }">
            <button type="button" class="tb-head" @click="todoOpen = !todoOpen"
                    :title="todoOpen ? '收起任务清单' : '展开任务清单'"
                    :aria-label="todoOpen ? '收起任务清单' : '展开任务清单'">
              <ordered-list-outlined class="tb-ico" />
              <span class="tb-label">任务清单</span>
              <span class="tb-count">{{ todoSummary }}</span>
              <caret-right-outlined class="tl-caret" :class="{ open: todoOpen }" />
            </button>
            <div v-show="todoOpen" class="tb-list">
              <div v-for="(t, ti) in sessionTodos.items" :key="ti" class="tb-item" :class="t.status">
                <!-- 进行中且本轮还在跑：框里换成转圈 + 整行底色 + 「执行中」文字。三者一起上是因为
                     单独任何一个都不够——14px 的框里换个符号在五条长句子中等同没换，而只有转圈没有
                     文字时用户会以为那是页面在加载。转圈仍要坐在这个框里：这一列其余行都是 14px 方框，
                     裸图标会在框的位置留一个洞，读起来像那一行缺了一格。本轮已停时不给转圈：清单是
                     快照，停掉的那一项再转下去就是撒谎。 -->
                <span class="tb-box" :class="{ run: t.status === 'doing' && loading }">
                  <loading-outlined v-if="t.status === 'doing' && loading" spin />
                  <template v-else>{{ t.status === 'done' ? '✓' : (t.status === 'failed' ? '✕' : '') }}</template>
                </span>
                <span class="tb-txt">{{ t.content }}</span>
                <span v-if="t.status === 'doing' && loading" class="tb-run-tag">执行中</span>
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
          <div v-if="goalBarVisible" class="goal-bar" :class="{ editing: goalEditing }" data-goal-bar>
            <!-- 取消二次确认就地长开（不弹层）：说清「取消 ≠ 删除」，点错一次也回得来 -->
            <div v-if="goalCancelArmed && goalOpen" class="gb-confirm">
              <div class="gb-confirm-t"><exclamation-circle-outlined /> 取消这次深度执行？</div>
              <div class="gb-confirm-s">停止自动推进，已经跑出的 <b>{{ goalRoundsCount }} 轮</b>、正文与过程都会保留。之后可以在消息流里查看，也可以重新设定目标。</div>
              <div class="gb-confirm-actions">
                <button type="button" class="gb-btn ghost" @click="goalCancelArmed = false">继续推进</button>
                <button type="button" class="gb-btn danger" @click="confirmGoalCancel">取消执行</button>
              </div>
            </div>
            <!-- 第一行：身份（胶囊 + 原文 + 版本 + 状态） -->
            <div class="gb-row gb-identity">
              <span class="gb-pill" title="深度执行：助手会自己接着往下推进，直到宣布完成">
                <aim-outlined /><span>深度</span>
              </span>
              <span v-if="!goalEditing" class="gb-objective" :title="goalBarTitle">{{ sessionGoal.objective }}</span>
              <span v-else class="gb-objective one">修改这件事要做到什么</span>
              <span class="gb-ver" :title="'任务原文第 ' + goalVersionLabel + ' 版'">{{ goalVersionLabel }}</span>
              <span class="gb-state" :class="'st-' + goalStateClass" :title="goalBarMeta">{{ goalStateLabel }}</span>
              <!-- 折叠键只在只读态出：编辑整段必须摊开；收起后整条只剩这一行身份 -->
              <button v-if="!goalEditing" type="button" class="gb-fold" :class="{ open: goalOpen }"
                      :title="goalOpen ? '收起进度与操作' : '展开进度与操作'"
                      :aria-expanded="goalOpen ? 'true' : 'false'" aria-label="深度执行进度与操作"
                      @click="toggleGoalBar"><caret-right-outlined /></button>
            </div>
            <!-- 编辑态：就地长开，版本轨与生效时点一起说清 -->
            <div v-if="goalEditing" class="gb-edit">
              <a-textarea v-model:value="goalEdit.objective" class="gb-edit-textarea"
                          :bordered="false" :auto-size="{ minRows: 2, maxRows: 6 }" :maxlength="4000"
                          placeholder="写下这件事要做到什么"
                          title="Enter 保存 · Shift+Enter 换行 · Esc 取消"
                          @keydown="onGoalEditKeydown" />
              <div class="gb-edit-hint">
                <span class="gb-vrail"><span>{{ goalVersionLabel }}</span><span class="arr">→</span><span class="on">{{ goalNextVersionLabel }}</span></span>
                <span>下一轮起按新目标执行 · 已跑的 <b>{{ goalRoundsCount }} 轮</b> 保留</span>
                <span class="warn">正在跑的这一轮不受影响</span>
              </div>
              <div class="gb-edit-actions-row">
                <span class="gb-spacer"></span>
                <button type="button" class="gb-btn ghost" :disabled="goalEditBusy"
                        title="放弃这次修改，目标原文不变（Esc）" @click="goalEditing = false">取消</button>
                <button type="button" class="gb-btn primary" :disabled="goalEditBusy || !goalEditSaveable"
                        :title="goalEditSaveable ? '保存为新版（Enter）' : '改几个字才能保存'"
                        @click="saveGoalEdit">{{ goalEditBusy ? '保存中…' : '保存为新版' }}</button>
              </div>
            </div>
            <!-- 第二行：进度（轮次 + 已跑时长 + 预算细条与百分比 + 停因/让位提示 + 过程入口）；
                 与动作行一起随身份行的折叠键收起 -->
            <div v-if="!goalEditing && goalOpen" class="gb-row gb-progress">
              <span class="gb-round">{{ goalRoundLine }}</span>
              <span v-if="goalElapsedText" class="gb-meta">{{ goalElapsedText }}</span>
              <span v-if="goalProgressPercent != null" class="gb-budget" :title="'预算已用 ' + goalProgressPercent + '%'">
                <span class="gb-track"><i :style="{ width: goalProgressPercent + '%' }" /></span>
                <span>预算 {{ goalProgressPercent }}%</span>
              </span>
              <span v-if="goalBarMeta" class="gb-hint">{{ goalBarMeta }}</span>
              <button v-if="goalDelivery" type="button" class="gb-more-link"
                      :title="'打开交付包：' + goalDelivery.fileCount + ' 件'"
                      @click="openDelivery(goalDelivery)">产出 {{ goalDelivery.fileCount }} 件 ›</button>
              <button type="button" class="gb-more-link" @click="openGoalViewer">查看过程 ›</button>
            </div>
            <!-- 第三行：动作（主行动靠右；取消与修改退到次级位）；删除记录只在终态卡上出现 -->
            <div v-if="!goalEditing && goalOpen" class="gb-row gb-actions-row">
              <button v-if="goalCanCancel" type="button" class="gb-btn text-danger"
                      title="取消执行：结束并留痕，历史与过程都保留（以后还能回看）"
                      @click="armGoalCancel">取消执行</button>
              <button v-if="goalCanEdit" type="button" class="gb-btn ghost" title="修改任务原文（保存后从这一版继续）"
                      @click="startGoalInlineEdit">修改任务</button>
              <span class="gb-spacer"></span>
              <button v-if="goalCanPause" type="button" class="gb-btn primary"
                      title="暂停执行：先停在这里，已经跑出来的部分都留着（可以再继续）"
                      @click="runGoalAction('pause')">暂停执行</button>
              <button v-else-if="goalCanResume" type="button" class="gb-btn primary"
                      :title="goalPreempted ? '立即继续：不等这一轮答完，现在就接着推进目标' : '继续执行：接着往下推'"
                      @click="runGoalAction('resume')">{{ goalPreempted ? '立即继续' : '继续执行' }}</button>
            </div>
            <span v-if="goalBarError" class="gb-error" role="alert">{{ goalBarError }}</span>
          </div>
          <!-- 插话队列：生成中在输入框里按发送＝先落这里、紧接着立刻插进本轮（与 DSH 同口径）。
          没送达的（这一轮正等人裁决/下一步还没到）留在这里并写明原因，本轮继续或下一轮开跑自动送达；
          送达前仍可改/删。刻意放在输入框正上方：它是"还没发出去的话"，离输入框最近才对。 -->
          <div v-if="steerQueue.length" class="steer-queue">
          <div v-for="(it, qi) in steerQueue" :key="qi" class="steer-item"
          :class="{ sending: it.sending, deferred: it.deferred, awaiting: it.awaiting, queued: it.queued, dragging: steerDragIdx === qi }"
          draggable="true"
          @dragstart="onSteerDragStart(qi, $event)"
          @dragover.prevent="onSteerDragOver(qi)"
          @dragend="onSteerDragEnd"
          @drop.prevent="onSteerDrop">
          <span class="steer-grip" title="拖动调整顺序">⠿</span>
          <span class="steer-txt">{{ it.text }}</span>
          <span v-if="steerExtras(it)" class="steer-extra">{{ steerExtras(it) }}</span>
          <span v-if="it.deferred" class="steer-state">本轮结束后自动发出</span>
          <span v-else-if="it.sending" class="steer-state">发送中…</span>
          <span v-else-if="it.awaiting" class="steer-state">已送出，等这一轮听到</span>
          <span v-else-if="it.queued" class="steer-state">{{ it.lastError || '排队中：等待送达' }}</span>
          <template v-if="!it.deferred && !it.sending && !it.awaiting">
          <button class="steer-act primary" type="button"
          title="立刻发给模型（纯文字在下一次生成前生效 · ⌘/Ctrl+Enter 同效）"
          @click="sendSteer(it)">立即发送</button>
          <button class="steer-act" type="button" title="取回输入框改" @click="editSteer(it)"><edit-outlined /></button>
          <button class="steer-act" type="button" title="丢弃这条" @click="dropSteer(it)"><delete-outlined /></button>
          </template>
          </div>
          </div>
        </template>
        <!-- 顶替态：挂起提问卡 / 待批计划卡整块顶替输入框（卡片样式仍由 ChatInput 提供） -->
        <template #replace>
          <!-- 智能体提问面板：当前会话有挂起提问时完全替换聊天输入框（模型在等答案，输入框此时不可用）。
          样式对齐「编号选项 + 自定义输入末项 + 键盘导航 + 忽略/提交」的提问卡；答复后面板撤下、
          输入框回归，问答记录以工具卡形态留在气泡原位 -->
          <div v-if="pendingAsk" class="ci-box askp-box" @keydown="onAskPanelKeydown">
          <!-- 顶替态没有输入框，chip 无处可画：待发图片/附件画在卡片里，否则拖入的图既看不见也不会随这张卡走 -->
          <PendingAtts v-if="pendingImages.length || pendingFiles.length" :images="pendingImages" :files="pendingFiles"
                       :fmt-size="fmtSize" @preview="i => openImages(pendingImages.map(p => p.dataUrl), i)"
                       @remove-image="removePendingImage" @remove-file="removePendingFile" />
          <div class="askp-head">
          <span class="askp-tag">{{ curAsk.topic || '向用户提问' }}</span>
          <span v-if="askQueueCount" class="askp-state" :title="'这一轮还有 ' + askQueueCount + ' 张卡要答，这张答完接着来'">还有 {{ askQueueCount }} 张待答</span>
          <span class="askp-q">{{ curAsk.question }}</span>
          <span v-if="curAsk.multi" class="askp-multi">可多选</span>
          <span v-if="pendingAsk.ask.answered" class="askp-state ok">已提交，模型继续中…</span>
          <span v-else-if="pendingAsk.ask.continued" class="askp-state">本轮已先继续 · 答完作为用户消息送达</span>
          <span v-else-if="askExpired" class="askp-state warn">已超时，智能体将自行判断…</span>
          <span v-if="askCountdownText" class="askp-timer" :class="{ warn: askExpired }">
          <clock-circle-outlined /> {{ askCountdownText }}
          </span>
          <span v-if="pendingAsk.ask.questions.length > 1" class="askp-pager">
          <button class="askp-page-btn" type="button" :disabled="askPage <= 0 || pendingAsk.ask.busy" @click="askPage--">‹</button>
          <span class="askp-page-num">{{ askPage + 1 }} / {{ pendingAsk.ask.questions.length }}</span>
          <button class="askp-page-btn" type="button" :disabled="askPage >= pendingAsk.ask.questions.length - 1 || pendingAsk.ask.busy" @click="askPage++">›</button>
          </span>
          </div>
          <div class="askp-opts">
          <button v-for="(op, oi) in curAsk.options" :key="oi" type="button"
          class="askp-opt" :class="{ sel: askOptionPicked(pendingAsk.ask, askPage, oi), 'k-hi': askHi === oi }"
          :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || askExpired" @click="onPick(oi)">
          <!-- 多选行前方块=勾选框（点一下勾上、再点取消）；单选题保持原来的「点选即换」，不额外画圈：
               一行只有一个可选项时，勾选框只是把同一件事画第二遍 -->
          <span v-if="curAsk.multi" class="askp-tick" :class="{ on: askOptionPicked(pendingAsk.ask, askPage, oi) }"></span>
          <span class="askp-no">{{ oi + 1 }}.</span>
          <span class="askp-kw">{{ askOptionParts(op).kw }}<span v-if="oi === 0" class="askp-rec">（推荐）</span></span>
          <span v-if="askOptionParts(op).rest" class="askp-rest">{{ askOptionParts(op).rest }}</span>
          </button>
          <div class="askp-opt askp-custom-row" :class="{ sel: askCustomPicked(pendingAsk.ask, askPage), 'k-hi': askHi === curAsk.options.length }">
          <span v-if="curAsk.multi" class="askp-tick" :class="{ on: askCustomPicked(pendingAsk.ask, askPage) }"></span>
          <span class="askp-no">{{ curAsk.options.length + 1 }}.</span>
          <input ref="askCustomRef" v-model="pendingAsk.ask.customs[askPage]" class="askp-input" :maxlength="2000"
          :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || askExpired"
          :placeholder="askCustomPlaceholder" @blur="commitCustomAt(askPage, false)" @keydown="onAskCustomKeydown" />
          </div>
          </div>
          <div class="askp-foot">
          <span class="askp-hint"><info-circle-outlined /> {{ askPanelHint }}</span>
          <span class="askp-actions">
          <!-- 停止 ≠ 忽略：忽略是「这一题我不答，你自行继续」，停止是「整轮叫停」。
          面板顶替了打字框，停止键（在发送位上）此时不存在，故在面板内给一个同义入口 -->
          <button class="app-btn ghost small p-stop" type="button"
          title="叫停整轮：不再执行任何工具，已生成的内容按「已停止本轮」保留。与「忽略」不同——忽略会让智能体带着「你没答」继续作答"
          @click="stopNow">停止本轮</button>
          <button v-if="!pendingAsk.ask.continued" class="app-btn ghost small" :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || askExpired" @click="ignoreAsk(pendingAsk)">忽略</button>
          <button class="app-btn small" :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || askExpired" @click="askSubmitAll(pendingAsk)">提交（{{ askAnsweredCount(pendingAsk.ask) }} / {{ pendingAsk.ask.questions.length }}）</button>
          </span>
          </div>
          </div>
          <!-- 计划确认栏：待批准时整块顶替打字框（模型在等裁决，此刻也没法发新消息）。
          版式对齐 plan-mode-ux：头部（执行计划 + 版本 + 状态 + 倒计时）→ 输入行（写意见 + 两个主按钮）→
          脚注（同轮升版说明 + 退出计划 / 转到对话修改 / 停止本轮）。计划正文在气泡里只读展示。 -->
          <div v-else class="ci-box planp-box">
          <PendingAtts v-if="pendingImages.length || pendingFiles.length" :images="pendingImages" :files="pendingFiles"
                       :fmt-size="fmtSize" @preview="i => openImages(pendingImages.map(p => p.dataUrl), i)"
                       @remove-image="removePendingImage" @remove-file="removePendingFile" />
          <div class="planp-head">
          <span class="planp-tag">执行计划</span>
          <span class="planp-ver">v{{ ((curPlanCard.versions || []).length) + 1 }}</span>
          <span v-if="planExpired" class="planp-state warn">已超时，本轮将停止</span>
          <span v-else class="planp-state">{{ curPlanCard.revising ? '修改中…' : '待确认' }}</span>
          <span v-if="planCountdownText" class="planp-timer" :class="{ warn: planExpired }">
          <clock-circle-outlined /> {{ planCountdownText }}
          </span>
          </div>
          <div class="planp-row">
          <a-textarea v-model:value="curPlanCard.editText" class="planp-input" :bordered="false"
          :auto-size="{ minRows: 1, maxRows: 6 }" :maxlength="8000"
          :disabled="curPlanCard.busy || planExpired"
          :placeholder="curPlanCard.restored ? '这张卡是恢复的，修改意见会作为新消息发出…' : '不满意？写下要改什么…（Enter 送出）'"
          @keydown="onPlanPanelKeydown" />
          <template v-if="!curPlanCard.restored">
          <button type="button" class="planp-btn warn"
          :disabled="curPlanCard.busy || planExpired || !(curPlanCard.editText || '').trim()"
          @click="revisePlanInPlace(pendingPlan, curPlanCard.editText)">提修改意见</button>
          <button type="button" class="planp-btn primary" :disabled="curPlanCard.busy || planExpired"
          @click="submitPlanApproval(pendingPlan, 'approve')">确认执行</button>
          </template>
          <button v-else type="button" class="planp-btn primary"
          :disabled="curPlanCard.busy || planExpired || !(curPlanCard.editText || '').trim()"
          @click="continuePlanConversation(pendingPlan, curPlanCard.editText)">转到对话修改</button>
          </div>
          <div class="planp-foot">
          <span class="planp-hint">修改会在同一轮生成新版本，不打断对话；「确认执行」后才开始动手</span>
          <span class="planp-links">
          <button v-if="!curPlanCard.restored" type="button" class="planp-link"
          :disabled="curPlanCard.busy || planExpired || !(curPlanCard.editText || '').trim()"
          @click="continuePlanConversation(pendingPlan, curPlanCard.editText)">转到对话修改</button>
          <button type="button" class="planp-link" :disabled="curPlanCard.busy || planExpired"
          @click="submitPlanApproval(pendingPlan, 'cancel')">退出计划</button>
          <button type="button" class="planp-link quiet" title="叫停整轮：不再执行任何工具"
          @click="stopNow">停止本轮</button>
          </span>
          </div>
          </div>
          <!-- 合成点击要 .stop：pickAttachments 里 input.click() 的点击事件会冒泡到 document，
          而 input 挂在计划面板容器下（不在 .ci-box/.mention-panel 内），外点关闭监听会误判成
          「点在面板外」把「+」面板关掉——表现为选择框还开着、背后面板没了，取消选择后面板也回不来 -->
        </template>
        <!-- 卡片内、正文之上：+ 面板 / @ 引用面板 / 斜杠面板 -->
        <!-- / 快捷命令面板（敲 / 唤起）：会话级动作，选中即执行 -->
        <div v-if="slashOpen" class="mention-panel">
        <div class="mention-head">
        <span class="mention-head-tag">/</span>
        <span class="mention-head-word" :class="{ dim: !slashQuery }">{{ slashQuery || '输入以筛选快捷命令' }}</span>
        <button class="app-icon-btn" title="关闭" @click="closeSlashPanel"><close-outlined /></button>
        </div>
        <div ref="slashListEl" class="mention-list">
        <div v-for="(c, ci) in slashFiltered" :key="c.key" class="mention-item"
        :class="{ hi: slashHi === ci }" @click="runSlashCommand(c)">
        <span class="mention-ava"><component :is="c.icon" /></span>
        <div class="mention-text">
        <span class="mention-name"><code v-if="c.cmd" class="slash-cmd">/{{ c.cmd }}</code>{{ c.name }}</span>
        <span class="mention-desc">{{ c.desc }}</span>
        </div>
        </div>
        <div v-if="!slashFiltered.length" class="mention-empty">没有匹配的命令</div>
        </div>
        <div class="mention-foot">↑↓ 选择 · Enter 确认 · Esc 关闭</div>
        </div>
        <!-- # 历史引用面板（敲 # 唤起）：勾选本会话历史问答，随本轮请求前置给模型 -->
        <div v-if="histOpen" class="mention-panel">
        <div class="mention-head">
        <span class="mention-head-tag">#</span>
        <span class="mention-head-word" :class="{ dim: !histQuery }">{{ histQuery || '输入以筛选历史问答' }}</span>
        <button class="app-icon-btn" title="关闭" @click="closeHistPanel"><close-outlined /></button>
        </div>
        <div ref="histListEl" class="mention-list">
        <div v-for="(m, hi2) in histCandidates" :key="m.messageId" class="mention-item"
        :class="{ on: isHistPicked(m.messageId), hi: histHi === hi2 }" @click="pickHistoryRefByClick(m)">
        <span class="mention-ava" :class="m.role === 'user' ? 'hist-ava-q' : 'hist-ava-a'">
        {{ m.role === 'user' ? '问' : '答' }}
        </span>
        <div class="mention-text">
        <span class="mention-name">{{ histItemTitle(m) }}</span>
        <span class="mention-desc">{{ m.role === 'user' ? '你的提问' : 'AI 的回答' }}{{ m.time ? ' · ' + fmtMsgTime(m.time) : '' }}</span>
        </div>
        <check-outlined v-if="isHistPicked(m.messageId)" class="mention-check" />
        </div>
        <div v-if="!histCandidates.length" class="mention-empty">
        {{ histPool.length ? '没有匹配的历史问答' : '本会话还没有可引用的历史问答' }}
        </div>
        </div>
        <div class="mention-foot">↑↓ 选择 · Enter 确认 · Esc 关闭　|　# 引用历史问答（仅本轮，最多 10 条）</div>
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
        <Teleport v-if="addOpen" :to="EDIT_ADD_MOUNT" :disabled="!addOnEdit">
        <div class="mention-panel" @keydown="onPanelNavKeydown">
        <div class="mention-head add-head">
        <button v-if="addTab !== 'root'" class="app-icon-btn add-back" title="返回上一层" @click="backToRoot"><left-outlined /></button>
        <span class="add-title">{{ addTab === 'root' ? '添加到本轮' : ADD_TITLES[addTab] }}</span>
        <button class="app-icon-btn add-close" title="关闭" @click="closeAddPanel"><close-outlined /></button>
        </div>
        <!-- 引用页的四类候选各自计数（技能=可选清单长度，勾选数看行上的对勾）；子页签只换列表，不重置搜索词 -->
        <div v-if="addTab === 'ref'" class="mention-tabs add-subtabs">
        <button v-for="s in REF_SUB_TABS" :key="s.key" class="mention-tab" :class="{ on: mentionTab === s.key }"
        type="button" @click="switchRefTab(s.key)">
        <component :is="s.icon" /> {{ s.label }} {{ s.count() }}
        </button>
        </div>
        <div v-if="addSearchable" class="add-filter-row">
        <input ref="addSearchEl" v-model="addQuery" class="add-filter" type="search"
        :placeholder="addSearchHint" autocomplete="off" />
        </div>
        <div ref="addListEl" class="mention-list">
        <!-- 一级菜单：上传行点了直接弹系统文件框；其余行进二级。kbd=主输入框里对应的触发符，
        把鼠标流和键盘流接到同一个地方（编辑卡那份不显示）；badge=已选/生效数（这一轮会带上什么） -->
        <template v-if="addTab === 'root'">
        <div v-for="(r, ri) in addRootRows" :key="r.key" class="mention-item"
        :class="{ hi: addHi === ri }" @click="r.run()">
        <span class="mention-ava"><component :is="r.icon" /></span>
        <div class="mention-text">
        <span class="mention-name">{{ r.name }}<span v-if="r.badge()" class="add-root-badge">{{ r.badge() }}</span></span>
        <span class="mention-desc">{{ r.desc }}</span>
        </div>
        <span v-if="r.kbd" class="add-kbd">{{ r.kbd }}</span>
        </div>
        </template>
        <template v-else-if="addTab === 'ref'">
        <!-- 技能清单不走库/文档那次请求，别被它的「加载中」挡住 -->
        <div v-if="mentionLoading && mentionTab !== 'skill'" class="mention-empty">加载中…</div>
        <template v-else-if="mentionTab === 'kb'">
        <div v-for="(k, ki) in addRefList" :key="k.id" class="mention-item"
        :class="{ on: isAddMentioned('kb', k.id), hi: addHi === ki }" @click="confirmRefRow('kb', k)">
        <span class="mention-ava"><database-outlined /></span>
        <div class="mention-text">
        <span class="mention-name">{{ k.name }}</span>
        <span class="mention-desc">{{ k.desc || (k.docCount ? k.docCount + ' 篇文档' : '') }}</span>
        </div>
        <check-outlined v-if="isAddMentioned('kb', k.id)" class="mention-check" />
        </div>
        <div v-if="!addRefList.length" class="mention-empty">
        {{ mentionKbs.length ? '没有匹配的知识库' : '没有可见的知识库' }}
        </div>
        </template>
        <template v-else-if="mentionTab === 'agent'">
        <div v-for="(a, ai) in addRefList" :key="a.id" class="mention-item"
        :class="{ on: isAddMentioned('agent', a.id), hi: addHi === ai }" @click="confirmRefRow('agent', a)">
        <AgentAvatar class="mention-ava-agent" :agent="a" :size="22" />
        <div class="mention-text">
        <span class="mention-name">{{ a.name }}</span>
        <span v-if="a.desc" class="mention-desc">{{ a.desc }}</span>
        </div>
        <check-outlined v-if="isAddMentioned('agent', a.id)" class="mention-check" />
        </div>
        <div v-if="!addRefList.length" class="mention-empty">
        {{ mentionAgents.length ? '没有匹配的智能体' : '没有可见的智能体' }}
        </div>
        </template>
        <template v-else-if="mentionTab === 'skill'">
        <div v-if="!skillList.length" class="mention-empty">还没有可用技能</div>
        <div v-for="(s, si) in addRefList" :key="s.name" class="mention-item"
        :class="{ on: isAddSkillOn(s.name), hi: addHi === si, 'mi-off': s.selectable === false }"
        @click="confirmRefRow('skill', s)">
        <span class="mention-ava skill-ava" :style="skillAvaStyle(s.name)">{{ (s.name || '技').slice(0, 1) }}</span>
        <div class="mention-text">
        <span class="mention-name">{{ s.name }}
        <span v-if="s.disabled" class="skill-flag off">已停用</span>
        <span v-else-if="s.source === 'workspace'" class="skill-flag">工作区·只读</span>
        </span>
        <span v-if="s.description" class="mention-desc">{{ s.description }}</span>
        </div>
        <button class="app-link-btn skill-doc-btn" @click.stop="skillDoc = s.name">查看正文</button>
        <check-outlined v-if="isAddSkillOn(s.name)" class="mention-check" />
        </div>
        <div v-if="skillList.length && !addRefList.length" class="mention-empty">没有匹配的技能</div>
        </template>
        <template v-else>
        <div v-for="(d, di) in addRefList" :key="d.id" class="mention-item"
        :class="{ on: isAddMentioned('doc', d.id), hi: addHi === di }" @click="confirmRefRow('doc', d)">
        <span class="mention-ava"><file-text-outlined /></span>
        <div class="mention-text">
        <span class="mention-name">{{ d.fileName }}</span>
        <span class="mention-desc">{{ mentionDocStatus(d) }}</span>
        </div>
        <check-outlined v-if="isAddMentioned('doc', d.id)" class="mention-check" />
        </div>
        <div v-if="!addRefList.length" class="mention-empty">
        {{ mentionDocs.length ? '没有匹配的文档' : '没有可见的文档' }}
        </div>
        </template>
        </template>
        <!-- 历史引用：勾选不关面板（多选），与键盘流 # 面板同一份交互；
        候选按写入目标取——编辑卡那份要排掉正在编辑的这一问（引用自己没意义） -->
        <template v-else-if="addTab === 'hist'">
        <div v-for="(m, mi) in addHistCandidates" :key="m.messageId" class="mention-item"
        :class="{ on: isAddHistPicked(m.messageId), hi: addHi === mi }" @click="toggleAddHistoryRef(m)">
        <span class="mention-ava" :class="m.role === 'user' ? 'hist-ava-q' : 'hist-ava-a'">
        {{ m.role === 'user' ? '问' : '答' }}
        </span>
        <div class="mention-text">
        <span class="mention-name">{{ histItemTitle(m) }}</span>
        <span class="mention-desc">{{ m.role === 'user' ? '你的提问' : 'AI 的回答' }}{{ m.time ? ' · ' + fmtMsgTime(m.time) : '' }}</span>
        </div>
        <check-outlined v-if="isAddHistPicked(m.messageId)" class="mention-check" />
        </div>
        <div v-if="!addHistCandidates.length" class="mention-empty">
        {{ addHistPool.length ? '没有匹配的历史问答' : '本会话还没有可引用的历史问答' }}
        </div>
        </template>
        <!-- 快捷命令：会话级动作，选中即执行（生成中拦截见 runSlashCommand） -->
        <template v-else>
        <div v-for="(c, ci) in addCmdList" :key="c.key" class="mention-item"
        :class="{ 'mi-off': loading && !c.noGate, hi: addHi === ci }" @click="runCmdFromAddPanel(c)">
        <span class="mention-ava"><component :is="c.icon" /></span>
        <div class="mention-text">
        <span class="mention-name"><code v-if="c.cmd" class="slash-cmd">/{{ c.cmd }}</code>{{ c.name }}</span>
        <span class="mention-desc">{{ c.desc }}</span>
        </div>
        </div>
        <div v-if="!addCmdList.length" class="mention-empty">没有匹配的命令</div>
        </template>
        </div>
        <div class="mention-foot">{{ addFootHint }}</div>
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
          <a-dropdown v-if="currentSessionId" :trigger="['click']" placement="topLeft">
            <button class="perm-pill" :class="{ on: !!sessionPermission && effectivePermission !== 'autonomous', danger: effectivePermission === 'autonomous' }"
                    type="button"
                    :title="'权限：' + permissionLabel">
              <ExclamationCircleOutlined v-if="effectivePermission === 'autonomous'" />
              <SafetyCertificateOutlined v-else />
              <span v-if="!isNarrow" class="perm-pill-name">{{ permissionLabel }}</span>
            </button>
            <template #overlay>
              <a-menu class="perm-menu" :selected-keys="[effectivePermission]"
                      @click="({ key }) => pickPermission(key)">
                <a-menu-item v-for="p in permissionPresets" :key="p.code" :disabled="p.available === false">
                  <div class="perm-mi-t">{{ p.label }}</div>
                  <div class="perm-mi-d">{{ p.available === false ? p.unavailableReason : p.summary }}</div>
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <a-dropdown v-model:open="agentPickerOpen" :trigger="['click']" placement="topLeft">
          <button class="agent-pill" :class="{ on: !!currentAgentId, open: agentPickerOpen, 'as-icon': isNarrow }"
          title="选择作答的智能体：换人立即生效（下一问起就是它）；不挑就沿用本会话上一问的智能体，新会话用默认助手。模型在右侧选择">
          <AgentAvatar v-if="currentAgent" :agent="currentAgent" :size="16" />
          <!-- 窄屏只留头像：智能体名 + 模型名会把工具条撑破，完整名称由 title 与下拉面板承载 -->
          <span v-if="!isNarrow" class="agent-pill-name">{{ currentAgentName }}</span>
          <down-outlined v-if="!isNarrow" class="agent-pill-caret" />
          </button>
          <template #overlay>
          <div class="agent-menu">
          <div class="agent-menu-head">
          <span>选择智能体</span>
          <span class="agent-menu-hint">本轮生效</span>
          </div>
          <div class="agent-menu-list">
          <div v-if="!hasDefaultAgent" class="agent-mi" :class="{ active: !currentAgentId }" @click="pickAgent('')">
          <span class="agent-mi-ava"><robot-outlined /></span>
          <div class="agent-mi-text">
          <span class="agent-mi-name">默认（全局配置）</span>
          </div>
          <check-outlined v-if="!currentAgentId" class="agent-mi-check" />
          </div>
          <div v-for="a in agentList" :key="a.id" class="agent-mi"
          :class="{ active: currentAgentId === a.id }" @click="pickAgent(a.id)">
          <AgentAvatar :agent="a" :size="26" style="margin-top:1px" />
          <div class="agent-mi-text">
          <span class="agent-mi-name">
          {{ a.name }}
          <span v-if="a.isDefault" class="agent-mi-badge">默认</span>
          </span>
          <span v-if="a.description" class="agent-mi-desc">{{ a.description }}</span>
          </div>
          <check-outlined v-if="currentAgentId === a.id" class="agent-mi-check" />
          </div>
          <div v-if="!agentList.length" class="agent-mi-empty">还没有智能体，去「管理智能体」新建一个</div>
          </div>
          <div v-if="isAdmin" class="agent-menu-foot" @click="goManageAgents">
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
          <span v-if="ctxTokens && ctxCapData.window > 0" class="ctx-ring" :class="ctxRingLevel" aria-label="上下文容量"
          :role="isCoarse ? 'button' : null" :tabindex="isCoarse ? 0 : null"
          @mouseenter="showCtxCap($event.currentTarget)" @mouseleave="hideCtxCap()"
          @click="isCoarse && showCtxCap($event.currentTarget)"
          @keydown.enter="isCoarse && showCtxCap($event.currentTarget)">
          <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
          <circle class="ctx-ring-bg" cx="10" cy="10" r="7.5" fill="none" stroke-width="2.5" />
          <circle class="ctx-ring-val" cx="10" cy="10" r="7.5" fill="none" stroke-width="2.5"
          stroke-linecap="round" :stroke-dasharray="ctxRingDash" transform="rotate(-90 10 10)" />
          </svg>
          </span>
          <!-- 深度思考设置入口：宽屏挂在模型下拉行上（悬浮哪行弹哪行，见下方 ModelSelect 的 option-hover）；
          触屏无 hover，改由这个常驻按钮点开底部 sheet。
          显示与否只由 v-if="isCoarse" 决定，CSS 侧不另设 @media(hover:hover) 规则——
          两者判据不同源会在「coarse 且 hover:hover」的设备上把入口藏没。 -->
          <button v-if="isCoarse" class="think-entry" :class="{ on: deepThinkOn }" type="button"
          title="深度思考设置" @click="toggleThinkSheet">
          <thunderbolt-outlined />
          <span class="think-entry-txt">{{ deepThinkOn ? '思考·开' : '思考' }}</span>
          </button>
          <!-- 深度思考设置挂到下拉模型行上：悬浮哪行就弹那个模型的设置面板（Teleport 在模板末尾），
          档位点选即时写入按模型 localStorage 记忆；对生效模型下一轮发送立即生效，
          对其它模型则先记住、选中该模型时生效 -->
          <ModelSelect v-model="currentOverrideModel" type="chat" pill
          :issue="effectiveModelIssue"
          :placeholder="effectiveModelLabel || '选择模型'"
          :display-ref="effectiveModel"
          :badge="pillLevelBadge" :badge-title="pillLevelTitle"
          :search="false"
          :compact="isNarrow"
          :width="190"
          @option-hover="onModelOptionHover" @open-change="onModelSelectOpenChange" />
        </template>
      </ChatInput>
      <!-- 完全权限二次确认：这是一次「把围栏放下」的显式动作，必须看清风险再勾选 -->
      <a-modal v-model:open="dangerOpen" title="确认启用完全权限？" :footer="null" :width="480"
               :mask-closable="!dangerSaving" @cancel="dangerOpen = false">
        <div class="danger-confirm">
          <p class="danger-lead">
            <ExclamationCircleOutlined class="danger-ic" />
            启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。
          </p>
          <a-checkbox v-model:checked="dangerAck" :disabled="dangerSaving">
            我已了解风险，并愿意继续
          </a-checkbox>
          <div class="danger-actions">
            <button type="button" class="app-btn ghost" :disabled="dangerSaving" @click="dangerOpen = false">取消</button>
            <button type="button" class="app-btn danger" :disabled="!dangerAck || dangerSaving" @click="confirmDanger">
              {{ dangerSaving ? '启用中…' : '启用完全权限' }}
            </button>
          </div>
        </div>
      </a-modal>

      <input ref="attachInput" type="file" multiple style="display:none" @click.stop @change="onAttachChange" />
    </div>

    <!-- 左侧问题锚点轨：一条刻度 = 一轮提问。不常驻——鼠标进聊天区才淡入（离开即收）；
         刻度默认 14px 短条，悬浮或滚到当前轮才伸长到 24px 并亮主色。点击把那一轮滚到视区顶部。
         预览概览卡在轨外的 .qa-pop（fixed 弹层，轨的 overflow 滚动会裁掉轨内悬浮卡），
         由 showAnchor 按触发刻度的屏幕坐标定位；至少两轮才出现，窄屏不渲染。 -->
    <nav v-if="!isNarrow && qaAnchors.length >= 2" class="qa-rail" :class="{ vis: railVis }"
         aria-label="问答锚点" @mouseleave="railHoverIdx = -1">
      <div v-for="a in qaAnchors" :key="a.idx" class="qa-item"
           :class="{ on: railActiveIdx === a.idx }"
           @mouseenter="showAnchor(a, $event)" @click="gotoAnchor(a)">
        <span class="qa-tick"></span>
      </div>
    </nav>
    <!-- 预览概览卡（轨外弹层）：问题深色一行 + 回答摘录灰字；pointer-events:none 不挡鼠标 -->
    <div v-if="railHoverAnchor" class="qa-pop" :style="railPopStyle" role="tooltip">
      <div class="qa-q">{{ anchorQ(railHoverAnchor) }}</div>
      <div v-if="anchorA(railHoverAnchor)" class="qa-a">{{ anchorA(railHoverAnchor) }}</div>
    </div>

    <!-- 右侧状态栏（可收起）：引用来源 / 产物 / 计划（各自有条件才显示）
         宽窄两态共用这一份 DOM：宽屏由 panelOpen 控制（占位列），窄屏由 mPanelOpen 控制
         （浮层/底部 sheet，默认收起）。两态只靠 .as-sheet 类 + CSS 媒体查询区分。 -->
    <aside v-if="panelOpen || mPanelOpen" class="right-panel" :class="{ 'as-sheet': isNarrow }">
      <!-- 窄屏下本栏是覆盖式 sheet，必须给一个显式关闭口：宽屏它是常驻列不需要关，
           窄屏开起来后若只能靠顶栏按钮来回切，用户会觉得「关不掉」。仅窄屏渲染。 -->
      <button v-if="isNarrow" class="rp-sheet-close" type="button" title="关闭" @click="mPanelOpen = false">
        <close-outlined />
      </button>
      <!-- 本轮卡（常驻首位）：跑轮时实时滚动「哪几步、每步在做什么事」，答完收成这一轮的动作清单。
           单步耗时不在这里报（整轮用时归气泡那条状态行），要点「查看全部步骤」进账本弹窗。
           会话里还没有 AI 消息时整卡不渲染（新会话不占位）。其余各卡仍是「有数据才显示」。 -->
      <RightProcessCard :msg="processMsg" @open-events="openEvents()" @open-hitl="focusHitl" />
      <!-- 引用来源：右栏交互最重的一张卡（点片段看原文 / 角标号定位正文 / hover 高亮角标 / 分组展开），过程卡之后的第一张。
           原先排在末尾，等于把「核查答案」这个最高频动作排到最需要滚动才看得到的位置——
           正文里刚亮起的角标，用户得点开侧栏再往下翻。移动端 sheet 一直把它排第一，这里对齐。
           有引用才显示整卡（空则隐藏，不占版面，与产物卡同规则）。
           卡头可折叠：来源多时（11 个来源就是 11 个分组行）整卡会把下面的卡顶出首屏。
           任务清单在输入框上方常驻（见 #above 的 .todo-bar）。 -->
      <div v-if="groupedSources.length" class="rp-card">
        <button type="button" class="rp-card-head" @click="srcCardOpen = !srcCardOpen">
          <span class="rp-label">引用来源 · {{ groupedSources.length }} 个来源</span>
          <caret-right-outlined class="tl-caret" :class="{ open: srcCardOpen }" />
        </button>
        <div v-if="srcCardOpen">
          <div v-for="g in groupedSources" :key="g.key" class="rp-group">
            <div class="rp-group-head" @click="toggleSrc(g)" :title="srcOpenOf(g) ? '收起片段' : '展开片段'">
              <global-outlined v-if="g.icon === 'web'" class="rp-src-ic" />
              <api-outlined v-else-if="g.icon === 'mcp'" class="rp-src-ic" />
              <file-text-outlined v-else class="rp-src-ic" />
              <span class="rp-src-name">{{ g.fileName }}</span>
              <span class="rp-count">{{ g.items.length }} 段</span>
              <caret-right-outlined class="tl-caret" :class="{ open: srcOpenOf(g) }" />
            </div>
            <div class="rp-group-body" :class="{ open: srcOpenOf(g) }">
              <!-- 0fr→1fr 只对**唯一直接子元素**生效：多个直接子元素会落到 auto 隐式行不参与收缩，故统一包一层 -->
              <div class="rp-group-body-in">
                <div v-for="(s, si) in g.items" :key="si" class="rp-src rp-src-sub"
                     :class="{ hl: hoveredRef === s.ref }"
                     :title="s.origin === 'WEB' ? '点击查看来源摘要与原网页' : '点击查看原文'"
                     @click.stop="openSource(s)"
                     @mouseenter="hoverSource(s)" @mouseleave="unhoverSource(s)">
                  <!-- 角标号那枚单独是「在正文里定位」：整行已经拿去开原文了，跳转不能再占同一个点击区，
                       而「[1]」这个号本身就是正文里那个上标，指着它跳最顺眼 -->
                  <span class="rp-src-ref" title="在正文中定位这条引用" @click.stop="locateSource(s)">[{{ s.ref }}]</span>
                  <span v-if="s.origin === 'MENTION'" class="rp-src-mine" title="本轮你 @ 引用的资料">引用</span>
                  <span class="rp-src-name">{{ s.title ? '§ ' + s.title : '片段 ' + (si + 1) }}</span>
                  <span v-if="debugDisplayVisible && fmtSourceScore(s)" class="rp-src-score" :title="scoreTitle(s)">{{ fmtSourceScore(s) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <!-- 本会话产物：有文件才显示整卡（空则隐藏，不占版面）。预览＝进预览台（与气泡内产物卡、产物页
           同一判据同一待遇），下载走同一份签名地址；更多入口跳产物页 -->
      <!-- 本会话交付：按交付包列（目标跨轮一个包、其余按轮一个包），点开进交付抽屉；
           不再把每件产物摊成一行。 -->
      <div v-if="deliveryPackages.length" class="rp-card">
        <div class="rp-label">交付 · {{ deliveryPackages.length }} 个包</div>
        <button v-for="d in deliveryPackages" :key="d.anchorKey || d.name" type="button" class="rp-dlv"
                @click="openDelivery(d)">
          <folder-open-outlined class="rp-art-ic" />
          <span class="rp-dlv-main">
            <span class="rp-dlv-name">{{ d.name }}</span>
            <span class="rp-dlv-sub">{{ d.fileCount }} 件 · {{ d.sizeLabel }}<template v-if="d.stateLabel"> · {{ d.stateLabel }}</template></span>
          </span>
          <caret-right-outlined class="rp-dlv-go" />
        </button>
        <div class="rp-art-all" @click="router.push('/artifacts')">在产物页查看全部 ›</div>
      </div>
      <!-- 本会话沙盒目录：助手在沙盒里做出、却没交付成产物的文件（产物卡只管已交付的那一份）。
           有文件才显示整卡，与产物卡同一判据；读不到要报错带重试——静默成空列表会被读成「我让它做的文件没了」。
           预览＝签名地址开新标签（与工作区文件栏同一条路、同一批类型判据，服务端只给点得开的条目配 viewUrl）。
           接口给的是摊平的一维清单，这里按相对路径折回目录树：整条路径摊在一行时名字全被截断，
           认不出哪份是成品；折成树之后行内只剩文件名，腾出的位置给大小。 -->
      <div v-if="sandboxFiles.length || sandboxErr || sandboxLoading" class="rp-card">
        <div class="rp-label">{{ sessionWorkspace && (sessionWorkspace.isLocal || sessionWorkspace.wsEffective === 'local') ? '本机文件' : '会话文件' }} · {{ sandboxCount }} 个{{ sandboxBytes ? ' · ' + sbSize(sandboxBytes) : '' }}</div>
        <div v-if="sandboxLocalOffline" class="rp-sb-hint">本机执行器离线，暂显示云端沙盒文件</div>
        <div v-if="sandboxLoading" class="rp-sb-hint">正在读取目录…</div>
        <div v-else-if="sandboxErr" class="rp-sb-err">
          <span>{{ sandboxErr }}</span>
          <button type="button" class="rp-sb-retry" @click="loadSandboxFiles">重试</button>
        </div>
        <template v-for="row in sandboxRows" :key="row.key">
          <!-- 目录行：开合钮只占名字那一段，行尾留给「在电脑上打开」——本机工作区的每个目录都是
               用户电脑上真实存在的文件夹，要拿到 Finder 里接着干时不必再绕回工作区页 -->
          <div v-if="row.type === 'dir'" class="rp-sb-dir">
            <button type="button" class="rp-sb-dir-main"
                    @click="toggleSandboxDir(row.dir.path)"
                    :title="(row.open ? '收起 ' : '展开 ') + row.dir.path + '/（' + row.dir.count + ' 个文件 · ' + fmtSize(row.dir.size) + '）'">
              <span v-for="i in row.depth" :key="i" class="rp-sb-ind" />
              <caret-right-outlined class="tl-caret" :class="{ open: row.open }" />
              <folder-open-outlined v-if="row.open" class="rp-art-ic" />
              <folder-outlined v-else class="rp-art-ic" />
              <span class="rp-sb-name">{{ row.dir.name }}/</span>
            </button>
            <span class="rp-sb-meta">{{ row.dir.count }} 个 · {{ sbSize(row.dir.size) }}</span>
            <button v-if="sessionWorkspace && sessionWorkspace.isLocal" type="button" class="rp-art-act rp-sb-btn"
                    :title="'在电脑上打开「' + row.dir.name + '」文件夹（Finder / 资源管理器）'"
                    @click="openLocalFolder(row.dir.path)"><export-outlined /></button>
          </div>
          <div v-else class="rp-art">
            <span v-for="i in row.depth" :key="i" class="rp-sb-ind" />
            <span class="rp-art-name"
                  :title="row.file.path + ' · ' + fmtSize(row.file.size) + ' · ' + fmtMsgTime(row.file.mtime)">{{ row.file.name }}</span>
            <span class="rp-sb-meta">{{ sbSize(row.file.size) }}</span>
            <a v-if="row.file.viewUrl" class="rp-art-act" :href="resolveImg(row.file.viewUrl)" target="_blank" rel="noopener"
               title="在新标签页打开"><eye-outlined /></a>
            <button type="button" class="rp-art-act rp-sb-btn" :title="'下载 ' + row.file.name"
                    @click="sandboxDownload(row.file)"><download-outlined /></button>
            <button v-if="!sandboxTaken.has(row.file.path)" type="button" class="rp-art-act rp-sb-btn"
                    title="存为产物（进「我的产物」，可预览可下载）" @click="sandboxTake(row.file)"><save-outlined /></button>
            <span v-else class="rp-sb-taken" title="已存为产物"><check-outlined /></span>
          </div>
        </template>
        <div v-if="sandboxRest" class="rp-sb-hint">另有 {{ sandboxRest }} 个文件未列出（仅显示最新 {{ sandboxFiles.length }} 个）</div>
      </div>
      <!-- 沙盒后台进程：让它「把写好的前后端跑起来自己看效果」时，起的是 npm run dev 这类<b>不会自己退</b>
           的进程；而沙盒那条执行到点只是「不再等它」，进程照旧活着、端口照旧占着，界面上却什么都看不见。
           这里给一个看得见、能一键停掉的地方。清单读的是容器实况（带归属标记的进程），不是服务端台账：
           后端重启、换副本都照得到；沙盒被空闲回收（默认 60 分钟）后整张清单自然清空。
           挂了共享目录的工作区里多个会话共用一个容器，所以同工作区别的会话起走的进程也会出现在这里，
           标一句「别的会话起的」——端口是被同一个容器占的，看不见就停不掉。 -->
      <div v-if="procCardVisible" class="rp-card">
        <div class="rp-label rp-proc-head"
             title="助手在会话里起的、不会自己退的进程（比如为了自己看效果起的服务）。到点没跑完只是不再等它，进程照旧活着。">
          <span>后台进程<span class="rp-proc-n">{{ procs.length }}</span><span class="rp-proc-unit">个在跑</span></span>
          <button type="button" class="rp-sb-retry" :disabled="procLoading"
                  title="重新扫一遍会话里的进程" @click="loadSandboxProcs">刷新</button>
        </div>
        <div v-if="procErr" class="rp-sb-err">
          <span>{{ procErr }}</span>
          <button type="button" class="rp-sb-retry" @click="loadSandboxProcs">重试</button>
        </div>
        <div v-for="(p, i) in procs" :key="p.tag" class="rp-proc"
             :class="{ 'is-killing': killingTag === p.tag }" :style="{ '--proc-i': i }">
          <!-- 命令行独占一行：它和「跑了多久/端口」挤同一行时，右栏一窄就被 flex 压成零宽，
               于是这张卡最该认得出的东西（这是哪个服务）整个消失 -->
          <div class="rp-proc-cmd">
            <span class="rp-proc-dot" aria-hidden="true" />
            <span class="rp-proc-cmd-t" :title="p.command">{{ p.command }}</span>
          </div>
          <div class="rp-proc-foot">
            <span class="rp-proc-meta">
              <span>跑了 {{ procAge(p) }}</span>
              <span v-if="p.ports && p.ports.length" title="会话内部的端口，外部浏览器打不开；这里只说明它占着哪个">
                · 占着端口 {{ p.ports.join(' / ') }}</span>
              <span v-if="p.roots > 1"> · {{ p.roots }} 棵进程树</span>
              <span v-if="!p.own"> · 同工作区别的会话起的</span>
            </span>
            <button v-if="p.killable" type="button" class="rp-proc-kill" :disabled="killingTag === p.tag"
                    :title="'停掉它，连同它起走的全部子进程（' + p.pids.length + ' 个）'"
                    @click="killProc(p)">{{ killingTag === p.tag ? '停止中…' : '停止' }}</button>
            <span v-else class="rp-proc-unknown" title="认不出这是哪次会话起的，停不了">来源不明</span>
          </div>
        </div>
      </div>
      <!-- 计划：本会话每轮出过的计划各列一条（继续对话/退回重出后每次重做都算一份），点开右侧完整计划抽屉。
           待确认的计划自动展开并置顶，避免「计划要批」这件事被藏住；历史计划沉底折叠。 -->
      <div v-if="sessionPlans.length" class="rp-card">
        <button type="button" class="rp-card-head rp-plan-head" @click="planOpen = !planOpen"
                :title="planOpen ? '收起计划' : '展开计划'">
          <span class="rp-label rp-label-h">计划 · {{ sessionPlans.length }} 份</span>
          <span class="rp-plan-badge" :class="{ 'is-pending': sessionPlans.some(p => p.status === 'pending') }">
            {{ planHeadText }}
          </span>
          <caret-right-outlined class="tl-caret" :class="{ open: planOpen }" />
        </button>
        <div class="rp-group-body" :class="{ open: planOpen }">
          <div class="rp-group-body-in">
            <button v-for="p in sessionPlans" :key="p.mi" type="button" class="rp-plan-row" @click="openPlanFromRail(p)"
                    :title="p.label + ' · ' + p.steps + ' 步'">
              <span class="rp-plan-dot" :class="'is-' + p.status"></span>
              <span class="rp-plan-t">{{ p.label }}</span>
              <span class="rp-plan-st" :class="'is-' + p.status"><template v-if="p.vers">v{{ p.vers + 1 }} · </template>{{ planStateText(p.status) }}</span>
            </button>
            <div class="rp-plan-note">待确认的计划自动置顶；点任意一行打开同一份计划视图。</div>
          </div>
        </div>
      </div>
    </aside>

    <!-- 正文浮层宿主（来源弹窗 / 角标悬浮卡 / 图片灯箱）：DOM 与状态见 components/AnswerViewerHost.vue
         与 chat/answerViewer.js。整页只挂这一处——每条消息各挂一份会让多图切换互相打架。 -->
    <AnswerViewerHost :debug-visible="debugDisplayVisible" :session-id="currentSessionId" />

    <!-- 完整计划 / 版本轨 / 裁决：卡片与右栏的「查看完整计划」都落到这一处抽屉（H5 由移动壳挂同款） -->
    <PlanViewer :open="planViewer.open" :card="planViewer.card" :sid="planViewer.sid"
                :pkey="planViewer.pkey" :title="planViewer.title" :version="planViewer.version"
                :initial-tab="planViewer.tab" :msg="planViewerMsg" :progress="planViewerProgress"
                :adjusts="planViewerAdjusts" :countdown="planCountdownText" :expired="planExpired"
                @close="closePlanViewer" @approve="onViewerApprove"
                @revise="onViewerRevise" @supersede="onViewerSupersede" @locate="onViewerLocate"
                @stop="onViewerStop" @adjust="onViewerAdjust" @locate-source="onViewerLocateSource" />

    <!-- 批准后调整计划：A 插话（不中断）/ B 停止并重新计划；与移动壳共用同一组件 -->
    <PlanAdjustSheet :open="planAdjust.open" :running="planAdjust.running"
                     :done="planAdjust.done" :doing="planAdjust.doing" :total="planAdjust.total"
                     :failed="planAdjust.failed" :step-label="planAdjust.stepLabel"
                     :busy="planAdjust.busy" :error="planAdjust.error"
                     :suggest-replan="planAdjust.suggestReplan"
                     @close="closePlanAdjust" @submit="submitPlanAdjust" />

    <!-- 会话分享（只读链接） -->
    <a-modal v-model:open="shareVisible" title="分享这段对话" :footer="null" width="520px">
      <a-spin :spinning="shareLoading">
        <template v-if="shareInfo.enabled">
          <div class="share-link-row">
            <input class="share-link" :value="shareUrl" readonly @focus="$event.target.select()" />
            <button class="app-btn" @click="copyShareLink"><copy-outlined /> 复制</button>
          </div>
          <div class="share-meta">
            链接展示的是这段对话的<strong>最新内容</strong>（后续继续提问也会一并出现在分享页）；
            拿到链接的人只能查看，不能继续提问<span v-if="shareInfo.visitCount"> · 已被访问 {{ shareInfo.visitCount }} 次</span>
          </div>
          <div class="share-warn">
            分享内容包含完整问答与引用来源的文档名/章节。请确认其中没有不适合外发的内容。
          </div>
          <div class="share-actions">
            <button class="app-btn ghost" @click="openSharedPage">预览</button>
            <button class="app-btn ghost" @click="regenerateShareLink">换一个新链接</button>
            <button class="app-btn ghost danger" @click="doDisableShare">停止分享</button>
          </div>
        </template>
        <template v-else>
          <p class="share-intro">
            生成一个只读链接，拿到链接的人可以查看这段对话的问答与引用来源，<strong>不能继续提问</strong>。
          </p>
          <p class="share-intro">链接随时可以停止；停止后重新生成会换新链接，旧链接立即失效。</p>
          <button class="app-btn" @click="doEnableShare"><share-alt-outlined /> 生成分享链接</button>
        </template>
      </a-spin>
    </a-modal>

    <!-- 回答反馈弹窗 -->
    <a-modal v-model:open="feedbackVisible" title="反馈" :footer="null" :width="440">
      <a-textarea v-model:value="feedbackText" placeholder="可选：告诉我们哪里不满意" :rows="3" />
      <button class="app-btn" style="margin-top:12px" :disabled="feedbackSubmitting" @click="doSubmitFeedback">提交反馈</button>
    </a-modal>

    <!-- 免责声明 -->
    <a-modal v-model:open="disclaimerVisible" title="免责声明" :footer="null" :width="560">
      <div class="md" style="max-height:60vh;overflow-y:auto" v-html="renderMd(DISCLAIMER_TEXT, [])"></div>
    </a-modal>

    <!-- 检索调试弹窗 -->
    <a-modal v-model:open="debugVisible" title="检索调试（为什么这么答）" :footer="null" :width="780">
      <div style="display:flex;gap:8px;margin-bottom:12px">
        <a-input v-model:value="debugQuestion" placeholder="输入要调试的问题" @pressEnter="runDebug" />
        <button class="app-btn" :disabled="debugLoading" @click="runDebug">调试</button>
      </div>
      <a-spin :spinning="debugLoading">
        <template v-if="debugResult">
          <div v-if="debugResult.keywordTerms?.length" class="dbg-terms">
            <span class="dbg-terms-label">检索词元</span>
            <a-tag v-for="(t, ti) in debugResult.keywordTerms" :key="ti" color="blue" style="margin:2px">{{ t }}</a-tag>
          </div>
          <a-collapse :bordered="false" :default-active-key="['final']">
            <a-collapse-panel v-for="(s, si) in debugStages" :key="si" :name="si === 4 ? 'final' : String(si)" :header="s.name">
              <div v-if="s.items.length" class="dbg-item" v-for="(it, ii) in s.items" :key="ii">
                <div class="dbg-head">
                  <span class="dbg-title">{{ it.title }}</span>
                  <a-tag v-if="it.docName" size="small">{{ it.docName }}</a-tag>
                  <a-tag color="blue" size="small">{{ it.tag }}</a-tag>
                </div>
                <div class="dbg-snippet">{{ it.snippet }}</div>
              </div>
              <a-empty v-else description="无命中" />
            </a-collapse-panel>
          </a-collapse>
        </template>
      </a-spin>
    </a-modal>

    <!-- 早期对话摘要：分隔线「查看摘要」打开。内容在打开那一刻快照下来（见引擎 openCompactSummary），
         否则发送下一轮会清掉当场结果、弹窗跟着变空 -->
    <a-modal v-model:open="compactSummaryOpen" :footer="null" width="720px">
      <template #title>
        <div class="csum-head">
          <span class="csum-title">早期对话摘要</span>
        </div>
      </template>
      <div class="csum-stats">
        <div v-if="compactSummary && compactSummary.freed > 0" class="csum-stat">
          <span class="cs-k">本次腾出</span><span class="cs-v">约 {{ fmtTokens(compactSummary.freed) }} tokens</span>
        </div>
        <div v-if="compactSummary" class="csum-stat">
          <span class="cs-k">摘要规模</span><span class="cs-v">{{ fmtTokens(compactSummary.tokens) }} tokens</span>
        </div>
        <div v-if="compactSummary && compactSummary.keepTurns > 0" class="csum-stat">
          <span class="cs-k">逐字保留</span><span class="cs-v">最近 {{ compactSummary.keepTurns }} 次问答</span>
        </div>
      </div>
      <!-- 摘要正文本身是模型给的条目化 Markdown：按 Markdown 渲染，别用 pre 把 - 和 # 原样摊出来 -->
      <div class="md csum-md" v-html="renderMd(compactSummary ? compactSummary.text : '')"></div>
      <div class="csum-foot">
        <span class="csum-tip">完整问答仍保留，可往上回看</span>
        <div class="csum-acts">
          <button class="app-btn ghost small" type="button" @click="csumInstrOpen = !csumInstrOpen">
            <edit-outlined /> 带要求重新压缩
          </button>
          <button class="app-btn small" type="button" @click="copyText(compactSummary ? compactSummary.text : '', '已复制摘要')">
            <copy-outlined /> 复制摘要
          </button>
        </div>
      </div>
      <!-- 这一次摘要要保留什么、忽略什么：交给后端并进框架的摘要提示词，只作用这一次压缩，不留到之后的轮 -->
      <div v-if="csumInstrOpen" class="csum-instr">
        <input v-model="csumInstr" class="csum-instr-input" maxlength="200"
               placeholder="这次摘要要保留什么、忽略什么" @keyup.enter="applyRecompact" />
        <button class="app-btn small" type="button" :disabled="compacting" @click="applyRecompact">
          {{ compacting ? '压缩中…' : '重新压缩' }}
        </button>
        <button class="app-btn ghost small" type="button" @click="csumInstrOpen = false">取消</button>
      </div>
    </a-modal>

    <!-- 粘贴文本卡片点开看全文：正文只留在本轮内存里（随问答落库的是名称/体积），
         所以刷新后回看历史卡片会明确提示不可预览，而不是点了没反应 -->
    <a-modal v-model:open="pasteView.open" :title="pasteTitle(pasteView.name)" :footer="null" width="640px">
      <div class="paste-view-bar">
        <span class="paste-view-meta">{{ pasteView.text.length }} 字</span>
        <button class="app-btn ghost" @click="copyText(pasteView.text, '已复制全文')"><copy-outlined /> 复制全文</button>
      </div>
      <pre class="csum-body">{{ pasteView.text }}</pre>
    </a-modal>

    <!-- 深度思考面板卡：悬浮模型下拉行时在该行右侧弹出（Teleport 到 body + fixed 定位，随悬浮行开合）。
         展示该模型自己的设置：上下文窗口（只报登记上限，不可调）+ 思考强度（档位豆平铺，
         点选即时写入按模型 localStorage 记忆，生效模型下一轮发送立即生效）；模型不支持思考时只展示窗口信息 -->
    <Teleport to="body">
      <div v-if="thinkPanelVisible" ref="thinkPanelEl" class="think-float"
           :style="{ top: thinkPanelPos.top + 'px', left: thinkPanelPos.left + 'px' }"
           @mouseenter="onThinkPanelEnter" @mouseleave="onThinkPanelLeave"
           @mousedown.stop.prevent>
        <div class="thinkp">
          <div class="thinkp-title">深度思考</div>
          <div v-if="thinkPanelCtxText || thinkPanelCaps.visible" class="thinkp-row-wrap">
            <!-- 上下文窗口只报登记上限：窗口一律按模型登记的最大值走 -->
            <div v-if="thinkPanelCtxText" class="thinkp-row"
                 title="模型登记的上下文窗口：一次能带进的资料与历史总量，也决定长对话什么时候开始折上下文">
              <span class="thinkp-row-label">上下文窗口</span>
              <span class="thinkp-row-val">{{ thinkPanelCtxText }}</span>
            </div>
            <template v-if="thinkPanelCaps.visible">
              <!-- 恒思考：强度由管理员在模型库登记，没有可选项，保持只读一行 -->
              <div v-if="thinkPanelCaps.locked" class="thinkp-row">
                <span class="thinkp-row-label">思考强度</span>
                <span class="thinkp-row-val">恒思考 · {{ thinkPanelStrengthText }}</span>
              </div>
              <!-- 可切换：档位平铺成一行直接点（与触屏 sheet 同款形态），不再「点行展开列表」；
                   「关闭」也是一颗档位豆，关掉思考在这张面板里始终有出口 -->
              <template v-else>
                <div class="thinkp-sub">思考强度</div>
                <div class="thinkp-chips">
                  <button v-for="opt in thinkPanelLevelOptions" :key="opt.value" class="thinkp-chip" type="button"
                          :class="{ on: opt.value === thinkPanelCurrentLevel }"
                          :title="opt.label" @click="setThinkLevel(opt.value, thinkHoverModel)">
                    {{ chipLabelOf(opt.value) }}
                  </button>
                </div>
              </template>
            </template>
          </div>
          <div class="thinkp-foot">{{ thinkPanelFoot }}</div>
        </div>
      </div>
    </Teleport>

    <!-- 上下文容量卡：悬浮输入框工具栏容量圆环弹出（占用/窗口 + 占用填充条 + 网关实测用量）。
         数据来自本轮落库的 tokens（刷新/切回会话仍在），窗口取用户所选档位或模型登记值 -->
    <Teleport to="body">
      <div v-if="ctxCapOpen" ref="ctxCapEl" class="ctxcap-float"
           :style="{ top: ctxCapPos.top == null ? 'auto' : ctxCapPos.top + 'px',
                     bottom: ctxCapPos.bottom == null ? 'auto' : ctxCapPos.bottom + 'px',
                     left: ctxCapPos.left + 'px' }"
           @mouseenter="onCtxCapEnter" @mouseleave="onCtxCapLeave">
        <div class="ctxcap">
          <div class="ctxcap-head">
            <div class="ctxcap-title">上下文容量</div>
            <div v-if="ctxCapData.window > 0" class="ctxcap-num">
              {{ fmtTokens(ctxCapData.used) }}/{{ fmtWindow(ctxCapData.window) }}（{{ ctxCapData.pct }}%）
            </div>
          </div>
          <template v-if="ctxCapData.window > 0">
            <!-- 填充宽=用量/窗口（与标题占用率同口径，空轨即剩余窗口） -->
            <div class="ctxcap-bar">
              <div class="ctxcap-fill" :class="ctxRingLevel" :style="{ width: ctxCapData.fillPct + '%' }"></div>
            </div>
            <!-- 每一行都是网关 usage 回传的实测值；名里带「未缓存/其中」的是上一行的子项，
                 缩进摆，不并列成能相加的样子 -->
            <div v-for="r in ctxCapData.rows" :key="r.label" class="ctxcap-row" :class="{ 'is-sub': r.sub }">
              <span class="ctxcap-row-label">{{ r.label }}</span>
              <span class="ctxcap-row-val">
                <template v-if="r.note">{{ r.note }} · </template>{{ fmtTokens(r.tokens) }}
              </span>
            </div>
            <div v-if="!ctxCapData.rows.length" class="ctxcap-empty">本轮还没有实测用量（随用量事件下发）</div>
            <!-- 手动压缩上下文的常驻入口：压完本卡数字不动，
                 腾出的量在 toast 里报，占用率要到下一轮落库才反映在这里 -->
            <div class="ctxcap-foot">
              <button class="app-btn ghost ctxcap-compact" type="button"
                      :disabled="compacting || loading"
                      :title="compacting || loading ? '' : '把较早的对话并入摘要，为后续提问腾出空间；完整记录仍可回看'"
                      @click="compactContext()">
                <compress-outlined />
                <span>{{ compacting ? '压缩中…' : '压缩上下文' }}</span>
              </button>
              <div v-if="loading && !compacting" class="ctxcap-tip">本轮结束后可压缩</div>
            </div>
          </template>
          <div v-else class="ctxcap-empty">该模型未登记上下文窗口，请在「模型供应商」里声明</div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch, shallowRef, h } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { LoadingOutlined, DownOutlined, CaretRightOutlined, CheckOutlined, CloseCircleOutlined, FileTextOutlined, DownloadOutlined, GlobalOutlined, ApiOutlined,
         ExclamationCircleOutlined, CopyOutlined, LikeOutlined, DislikeOutlined, MoreOutlined,
         DeleteOutlined, BugOutlined, EditOutlined, PlusOutlined, PaperClipOutlined, PauseCircleOutlined,
         PlayCircleOutlined,
         CompressOutlined,
         RobotOutlined, SettingOutlined, ThunderboltOutlined, LockOutlined, RedoOutlined,
         CloseOutlined, DatabaseOutlined, SearchOutlined, UpOutlined, ShareAltOutlined,
         HistoryOutlined, SwapOutlined, InfoCircleOutlined,
         AppstoreOutlined, ClockCircleOutlined, BookOutlined,
         OrderedListOutlined, LeftOutlined, FolderOpenOutlined, FolderOutlined, ExportOutlined,
         AimOutlined, EyeOutlined, SaveOutlined, ReloadOutlined, BulbOutlined,
         SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { debugRetrieval, deleteMessageGroup, submitFeedback as apiSubmitFeedback,
         addEvalCase, getSessionShare, enableSessionShare, disableSessionShare,
         getSessionWorkspace, getPermissionPresets, setSessionPermission,
         openProjectFolder, updateProjectItem, createSkill } from '../api'
import { renderMd, resolveImg, onImgError } from '../utils/markdown'
import { loadSessions, sessionStore } from './store'
import { exportAnswerMd, exportSessionMarkdown } from './exportMd'
import { fmtTokens } from '../utils/token'
import { chatDone, guideSnoozed, refreshSetupGuide, setupGuide } from '../utils/setupGuide'
import { copyText } from '../utils/clipboard'
import { localExecutorId } from '../utils/localExecutor'
import { useSessionShare } from './shareSession'
import ModelSelect from '../components/ModelSelect.vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import BrandMark from '../components/BrandMark.vue'
import AgentAvatar from '../components/AgentAvatar.vue'
import SetupGuide from '../components/SetupGuide.vue'
import WelcomeCopy from '../components/WelcomeCopy.vue'
import { isNarrow, isCoarse } from '../h5/mobile'
// 输入区外壳（卡片/正文与 chip 同流/工具条/拖拽落点）：与公开分享页共用一份，避免两头各写一套
import ChatInput from '../chat/ChatInput.vue'
// 消息列表外壳（与公开分享页同一份）：行/块/气泡布局 + 提问随行材料 + 降级条 + 动作行 + 兜底正文。
// 本页只保留 SourceList 的直接引用——#sources 插槽给的是带检索词与原文片段的版本
import ChatThread from '../chat/ChatThread.vue'
import SourceList from '../chat/SourceList.vue'
// 定高内滚块（深度思考正文、过程簇里的思考/独白）的贴底跟随，与 NoteBody 共用一份指令；
// 跑轮中簇帽体的上下边缘渐隐（ef-top/ef-bot 切 class）用同一文件里的 vEdgeFade
import { vFollowBottom, vEdgeFade } from '../chat/followBottom'
// 窄屏专属 UI 组件（PC 态不渲染，详见 src/h5/README.md）
import MobileChatHead from '../h5/MobileChatHead.vue'
import AskRecordCard from '../components/AskRecordCard.vue'
import NoteTag from '../components/NoteTag.vue'
import NoteBody from '../components/NoteBody.vue'
import TurnBar from '../components/TurnBar.vue'
import SubAgentChatCard from '../components/SubAgentChatCard.vue'
import MsgDivider from '../components/MsgDivider.vue'
import ToolSpillButton from '../components/ToolSpillButton.vue'
import SessionEventsPanel from '../components/SessionEventsPanel.vue'
// 右栏「过程」卡：跑轮时实时滚动工具步（数据就是引擎那份消息对象，不另取数）
import RightProcessCard from '../components/RightProcessCard.vue'
// 顶栏「子智能体」药丸：派遣在跑时给一个不随正文滚走的位置（PC 浮层 / 触屏底部 sheet 同一份组件）
import SubagentPill from '../components/SubagentPill.vue'
import SkillContentModal from '../components/SkillContentModal.vue'
// 目标终态卡（挂在「设为目标」那条回声下）与目标过程抽屉：PC 与移动壳共用同一份
import GoalTerminalCard from '../components/GoalTerminalCard.vue'
import GoalViewer from '../components/GoalViewer.vue'
// 交付包：对话内一张卡 + 右侧抽屉（清单/批量动作），与 H5 共用同一份判据
import DeliveryCard from '../components/DeliveryCard.vue'
import DeliveryPanel from '../components/DeliveryPanel.vue'
// 产物生成态占位：只在「本轮还没有产物、正在生成/交付文件」时出现
import ArtifactPendingCard from '../components/ArtifactPendingCard.vue'
// 回答正文与它的浮层宿主：与两个分享页共用同一份实现（详见 src/chat/answerViewer.js 顶部说明）
import AnswerBody from '../components/AnswerBody.vue'
import AnswerViewerHost from '../components/AnswerViewerHost.vue'

// 聊天引擎：引擎逻辑见 src/chat/useChatEngine.js，纯函数/常量见 src/chat/projections.js
import { useChatEngine } from '../chat/useChatEngine'
import { useChatSearch } from '../chat/useChatSearch'
import Composer from '../chat/Composer.vue'
import PendingAtts from '../chat/PendingAtts.vue'
import PlanCard from '../components/PlanCard.vue'
import PlanViewer from '../components/PlanViewer.vue'
import PlanAdjustSheet from '../components/PlanAdjustSheet.vue'
import { planStepCount, planExecDetail } from '../chat/plan'
import { pendingAnswerText } from '../chat/askUser'
import { chipLabelOf } from '../chat/thinking'
import { useThinkPanel } from '../chat/useThinkPanel'
import { buildQaAnchors } from '../chat/chatAnchors'
import { buildSessionPlans, planStateText } from '../chat/planPanel'
import { useSourceFocus } from '../chat/useSourceFocus'
import { useChatScroll } from '../chat/useChatScroll'
import { useChatPanels } from '../chat/useChatPanels'
import { parseDeepCommand } from '../chat/deepCommand'
import TurnChangesCard from '../components/TurnChangesCard.vue'
import { splitAtts } from '../chat/attachments'
import { useEditResend } from '../chat/useEditResend'
import { useGoalPanels } from '../chat/useGoalPanels'
import { useSessionWorkspace } from '../chat/useSessionWorkspace'
import { SUPPORTED_EXTS, extOf } from '../chat/intake'
// 正文浮层的共享状态：右栏来源联动（hoveredRef / 点角标定位）与灯箱开关要用
import { openImages, previewUrl, openSource, hoveredRef } from '../chat/answerViewer'
import { approvalArgsText } from '../chat/approval'
import { toolLabel, toolDesc, toolCallsView, executedCount, toolDuration,
         procOpen, toggleProc, procSlice, thinkSegOpen, toggleThinkSeg, thinkSlice, thinkPeek, thinkGrowing,
         turnProcessRows, turnBodyRows, turnOpen,
         toolBrief, prettyIo, liveOutput, groupRunning,
         clusterOpen, toggleCluster, clusterTools, clusterRunning, clusterHasError, clusterProcCount,
         clusterAutoOpen, clusterLiveRows, clusterTitle, clusterLiveText, clusterHeadKind,
         groupHasBar,
         tailNotesTag, tailNotesPeek, tailNotesGrowing, tailOpen, toggleTail,
         groupHasError, toolSearchQueries, retrievalLineTitle,
         fanHasBoard, fanHead, fanTitle, fanRowState, fanBudget, fanRun, runBoardText,
         fmtWindow, externalOrigin, sourceName, fmtSourceScore, scoreTitle,
         fmtMsgTime, fmtSize, errorBrief, histItemTitle, histItemDigest, verLocal, canSwitchPrev, canSwitchNext, verLabel,
         pasteTitle, pasteSub, isPastedText,
         THINK_LEVEL_ON, levelLabel, stopTick, agentBadgeOf, isDispatchTool,
         usageCtxTokens, usageTip, oneLine, toolCardOpen, toggleToolCard, spawnAnchorOf, openDispatchCard } from '../chat/projections'
import { useChatChrome } from '../chat/useChatChrome'
import { useCommandPanels } from '../chat/useCommandPanels'
import { useChatActions } from '../chat/useChatActions'
import { usePlanPanels } from '../chat/usePlanPanels'
import { useDeepAsk } from '../chat/useDeepAsk'

// ===== 布局层装配：页面只保留组件注册、共享 DOM ref 与各视图域的接线 =====
const page = {}
const router = useRouter()
const route = useRoute()
// 输入框与编辑卡的 DOM ref 由布局层持有：命令面板与两个域指向同一份元素。
const composerRef = ref(null)
const editingRef = ref(null)
const goalArmed = ref(false)
const goalStarting = ref(false)
const CLUSTER_ICONS = { search: SearchOutlined, tools: ApiOutlined, thinking: BulbOutlined }
// 具名引用：命名空间 import 会把整个图标库与 api 模块拖进本页分包。
const api = { debugRetrieval, deleteMessageGroup, submitFeedback: apiSubmitFeedback,
              addEvalCase, getSessionShare, enableSessionShare, disableSessionShare,
              getSessionWorkspace, getPermissionPresets, setSessionPermission,
              openProjectFolder, updateProjectItem, createSkill }
const icons = { CompressOutlined, AimOutlined, FileTextOutlined, PlusOutlined,
                PaperClipOutlined, BookOutlined, HistoryOutlined, ThunderboltOutlined,
                DatabaseOutlined, RobotOutlined, AppstoreOutlined }

// 引擎先装配：所有视图域的 getter 都能安全读取；视图回调经 page 惰性取值。
const engine = useChatEngine({
  chatPath: '/chat',
  planModeEnabled: true,
  scrollFollow: () => nextTick(() => { page.chrome?.updateTailSpacer?.(); page.chrome?.scroll?.() }),
  scrollForce: () => nextTick(() => { page.chrome?.updateTailSpacer?.(); page.chrome?.scrollForce?.() }),
  scrollSoft: () => page.chrome?.scroll?.(),
  revealApproval: id => page.chrome?.revealApprovalCard?.(id),
  closePanels: () => page.panels?.closeAllPanels?.(),
  focusInput: () => page.chrome?.focusInput?.()
})

const {
  // 输入与发送
  text, canSend, send, stop, steerQueue, sendSteer, editSteer, dropSteer, moveSteer,
  streamAnswer, resolveApproval, askSubmitAll, ignoreAsk,
  // 提问卡的选择状态口径全在引擎（单选/多选同一形状）：面板只读这些，不自己数选中态
  pickAskOption, commitAskCustom, askOptionPicked, askCustomPicked, askQuestionAnswered,
  askAnsweredCount, askNextUnanswered, askAutoSubmittable, askHintText,
  // 计划模式（人在回路）：斜杠命令开关 + 计划卡裁决动作
  planModeOn, setPlanMode, submitPlanApproval, revisePlanInPlace, continuePlanConversation,
  pendingPlan, curPlanCard, planExpired, planCountdownText,
  // 批准后调整（A/B）与执行进度/调整记录：判据与取数都在引擎，页面只接线
  adjustPlanAfterApproval, planExecProgressOf, planAdjustsOfMsg,
  // 工具审批卡倒计时（真实剩余秒数；到点按钮锁住）
  approvalLeftSecOf,
  // 目标模式：平台总闸 goalModeOn + 会话级目标态 + 全部动作。
  // 摘要条与就地编辑的词汇、显隐、可用性都在引擎侧（与移动壳共用一份），本页只画形状
  sessionGoal, goalModeOn, startGoal, runGoalAction,
  goalTerminal, goalBarVisible, goalStateLabel, goalStateClass, goalRoundLine,
  goalBarTitle, goalBarMeta, goalBarError,
  goalCanPause, goalCanResume, goalCanEdit, goalCanCancel, goalCanClearRecord,
  goalPreempted, goalVersionLabel, goalProgressPercent,
  goalTerminalCard, goalTerminalTitle, goalTerminalNote, goalTurnStreaming,
  goalDelivery, deliveryPackages, deliveryCardOfMessage, downloadDeliveryZip, artifactPendingOf, freshDeliveryOf,
  goalEditing, goalEdit, goalEditBusy, goalEditSaveable,
  startGoalInlineEdit, saveGoalEdit,
  // 思考能力 / 档位（悬浮面板消费）
  thinkCapsOf, reasoningLevelsOf, deepOnOf, levelOptionsOf, currentLevelOf, setThinkLevel, deepThinkOn,
  // 技能
  skillList, pickedSkills, toggleSkill, skillAvaStyle, loadSkills, skillDoc,
  // 智能体
  agentList, hasDefaultAgent, currentAgentId, isAdmin, agentPickerOpen,
  currentAgentName, currentAgent, pickAgent, showAgentTag, modelSwitchInfo, loadAgents,
  // 会话与消息
  currentSessionId, loading, messages, currentSessionTitle,
  // 模型
  currentOverrideModel, modelIndex, effectiveModel, effectiveModelLabel, effectiveModelIcon,
  effectiveModelProvider, debugEntryVisible, debugDisplayVisible, effectiveModelIssue,
  // 检索 / 用量 / 来源
  lastAi, groupedSources, ctxTokens, ctxCapData, ctxRingDash,
  ctxRingLevel, panelAi, sessionArtifacts, sessionTodos,
  // 清单的计数与显隐判据吃引擎那一份，PC 与移动壳共用。
  todoSummary, todoBarVisible,
  // 这一会话的历史有没有取回来（与「取回来了、就是空的」是两条判据，见引擎 historyError 注释）
  historyError, retryHistory,
  // 沙盒目录同样整族吃引擎：取数、判据、折树、下载与存为产物
  sandboxFiles, sandboxRows, sandboxCount, sandboxBytes, sandboxRest,
  sandboxErr, sandboxLocalOffline, sandboxLoading, sandboxTaken, sessionSandboxActive,
  loadSandboxFiles, toggleSandboxDir, sandboxDownload, sandboxTake, sbSize,
  // 后台进程那一族同样吃引擎：本页只留这张卡的摆法
  procs, procErr, procLoading, procCardVisible, killingTag, loadSandboxProcs, procAge, killProc,
  // 图片与附件（输入框里的 chip 删除走编辑区 → onKeys 剪枝；顶替态没有编辑区，由待发条按下标移除）
  pendingImages, removePendingImage, MAX_FILES, pendingFiles, removePendingFile, hasUploadingFile, addFiles,
  takePastedFiles, takePastedText, attachUploaded, attachImage,
  // @ 引用（面板与筛选词在「+」面板一节，高亮下标用本页自己的 addHi）
  mentionTab, mentionQuery, mentionLoading, mentionKbs, mentionDocs, pendingMentions,
  isMentioned, toggleMention, removeMention, loadMentionCandidates, MAX_MENTIONS,
  mentionDocStatus, mentionKbFiltered, mentionAgents, mentionAgentFiltered, mentionDocFiltered,
  // # 历史引用（上限常量在编辑卡里同样要用到）
  pendingHistoryRefs, histPool, isHistPicked, toggleHistoryRef, removeHistoryRef, MAX_HISTORY_REFS,
  // 会话生命周期 / 重新生成 / 分支 / 初始化
  switchSession, createNewSession, workspaceEpoch, regenerate, variantSwitching, switchBranch, ready,
  // 手动压缩上下文（/compact：命令面板、分隔线与摘要弹窗）
  compacting, manualCompacting, compactNotice, compactContext,
  compactSummaryOpen, compactSummary, compactSummaryLoading, openCompactSummary, recompactWith,
  // 「已压缩上下文」分隔线的判据（引擎里，与 H5 共用）：持久那份按消息行标记，另一档补本轮压完到落库之间
  compactDividerAt, liveCompactAt
} = engine

// 目标条的进度读数：轮次之外再给「已跑多久 / 预算占多少」，让条子一屏读完
const goalElapsedText = computed(() => {
  const b = sessionGoal.value && sessionGoal.value.budget
  const ms = Number(b && b.ms) || 0
  if (ms <= 0) return ''
  const s = Math.round(ms / 1000)
  if (s < 60) return `已跑 ${s} 秒`
  const m = Math.floor(s / 60)
  const r = s % 60
  if (m < 60) return `已跑 ${m} 分${r ? ' ' + r + ' 秒' : ''}`
  return `已跑 ${Math.floor(m / 60)} 小时${m % 60 ? ' ' + (m % 60) + ' 分' : ''}`
})
const goalRoundsCount = computed(() => {
  const g = sessionGoal.value
  return Number(g && g.budget && g.budget.rounds) || Number(g && g.round) || 0
})
const goalNextVersionLabel = computed(() => {
  const n = Number(sessionGoal.value && sessionGoal.value.objectiveVersion) || 0
  return 'v' + (n + 2)
})

const ask = q => { text.value = q; nextTick(send) }

// 共享上下文：引擎值一律惰性 getter，动作/工具直接传，跨域引用经 page 惰性取值。
const g = {
  notify: message, message,
  router, route, sessionStore, loadSessions,
  nextTick, now: () => Date.now(),
  setTimeout, clearTimeout, setInterval, clearInterval,
  win: window, doc: document, window, document, storage: localStorage,
  isNarrow, isCoarse,
  api, apiSubmitFeedback, updateProjectItem,
  copyText, exportAnswerMd, exportSessionMarkdown, parseDeepCommand,
  resolveImg, openImages, previewUrl, openSource, hoveredRef,
  fmtSize, fmtWindow, oneLine, histItemDigest, histItemTitle, isPastedText,
  pasteTitle, pasteSub, histChipLabel: histItemTitle,
  clusterHeadKind, spawnAnchorOf, openDispatchCard, stopTick,
  buildQaAnchors, buildSessionPlans, planStepCount, planStateText,
  CLUSTER_ICONS, BulbOutlined, icons,
  MAX_FILES, MAX_MENTIONS, MAX_HISTORY_REFS,
  useThinkPanel, useChatScroll, useChatPanels, useSourceFocus,
  useChatSearch, useSessionShare, refreshSetupGuide,
  messages: () => messages.value,
  loading: () => loading.value,
  text: () => text.value,
  currentSessionId: () => currentSessionId.value,
  currentSessionTitle: () => currentSessionTitle.value,
  currentLevelOf, currentOverrideModel: () => currentOverrideModel.value,
  effectiveModel: () => effectiveModel.value,
  lastAi: () => lastAi.value,
  modelIndex: () => modelIndex.value,
  goalBarVisible: () => goalBarVisible.value,
  goalModeOn: () => goalModeOn.value,
  goalTurnStreaming: () => goalTurnStreaming.value,
  sessionGoal: () => sessionGoal.value,
  goalTerminal: () => goalTerminal.value,
  goalEditing: () => goalEditing.value,
  goalEditBusy: () => goalEditBusy.value,
  planModeOn: () => planModeOn.value,
  pendingPlan: () => pendingPlan.value,
  curPlanCard: () => curPlanCard.value,
  planExpired: () => planExpired.value,
  pendingFiles: () => pendingFiles.value,
  pendingImages: () => pendingImages.value,
  pendingMentions: () => pendingMentions.value,
  pickedSkills: () => pickedSkills.value,
  pendingHistoryRefs: () => pendingHistoryRefs.value,
  histPool: () => histPool.value,
  mentionQuery: () => mentionQuery.value,
  mentionTab: () => mentionTab.value,
  mentionKbs: () => mentionKbs.value,
  mentionDocs: () => mentionDocs.value,
  mentionAgents: () => mentionAgents.value,
  mentionKbFiltered: () => mentionKbFiltered.value,
  mentionDocFiltered: () => mentionDocFiltered.value,
  mentionAgentFiltered: () => mentionAgentFiltered.value,
  agentList: () => agentList.value,
  agentPickerOpen: () => agentPickerOpen.value,
  skillList: () => skillList.value,
  compactContext: () => compactContext.value,
  askAutoSubmittable: () => askAutoSubmittable.value,
  askHintText: () => askHintText.value,
  askNextUnanswered: () => askNextUnanswered.value,
  askQuestionAnswered: () => askQuestionAnswered.value,
  pickedSkillsRef: pickedSkills,
  // 引擎动作
  send, stop, streamAnswer, moveSteer, setPlanMode, setThinkLevel,
  addFiles, attachImage, attachUploaded, takePastedFiles, takePastedText,
  toggleMention, toggleSkill, toggleHistoryRef, isMentioned, isHistPicked,
  loadAgents, loadSkills, loadMentionCandidates, createNewSession,
  submitPlanApproval, revisePlanInPlace, continuePlanConversation,
  adjustPlanAfterApproval, planExecProgressOf, planAdjustsOfMsg,
  askSubmitAll, commitAskCustom, pickAskOption, ignoreAsk,
  startGoal, saveGoalEdit, startGoalInlineEdit, recompactWith, ready,
  thinkCapsOf, levelOptionsOf, deepOnOf, currentLevelOf,
  pickAgent, switchBranch, regenerate, retryHistory
}

/** 执行详情：工具/产物按 stepId 归到步骤上；没有归属数据时卡片只报状态。 */
const planExecDetailOf = m => planExecDetail(planExecProgressOf(m), m && m.toolCalls, (m && m.artifacts) || [])

// ---- 编辑重发：先建，命令面板要复用它的编辑态列表 ----
const {
  editingIdx, editingMessageId, editingText, editingBusy,
  editImgs, editAtts, editMentions, editHistRefs,
  editSkills, editAttachInput, setEditingRef, setEditAttachInputRef,
  autosizeEditing, probeEditAtts, editMessage, cancelEdit,
  pickEditFiles, onEditAttachChange, editAddCount, toggleEditSkill,
  toggleEditHistoryRef, toggleEditMention, onEditPaste, removeEditImage,
  removeEditAtt, removeEditMention, editHistPool, addEditSkill,
  removeEditSkill, addEditHistRef, removeEditHistRef, previewEditImage,
  onEditKeydown, confirmEdit
} = (page.editResend = useEditResend({
    messages: () => messages.value,
    setMessages: list => { messages.value = list },
    loading: () => loading.value,
    histPool: () => histPool.value,
    addOpen: () => page.panels?.addOpen.value ?? false,
    addTarget: () => page.panels?.addTarget.value ?? '',
    addOnEdit: () => page.panels?.addOnEdit.value ?? false,
    closeAddPanel: () => page.panels?.closeAddPanel(),
    onPanelNavKeydown: e => page.panels?.onPanelNavKeydown(e),
    maxMentions: () => MAX_MENTIONS,
    maxHistoryRefs: () => MAX_HISTORY_REFS,
    resend: (...args) => streamAnswer(...args),
    notify: message, editingRef,
    isPastedText, histItemDigest, splitAtts, resolveImg, openImages,
    addFiles: (files, into) => addFiles(files, into),
    takePastedFiles: (e, into) => takePastedFiles(e, into),
    takePastedText: (e, into) => takePastedText(e, into)
  }))

// ---- 命令面板：先于页面壳层，壳层要写它的开合与高亮 ref ----
const {
  panelTriggerPos, panelTriggerCh, panelAnyOpen, closeAllPanels,
  panelListSize, getPanelHi, setPanelHi, slashListEl,
  histListEl, addListEl, panelListEl, scrollPanelHiIntoView,
  movePanelHi, confirmPanelHi, stripTriggerToken, composerCaret,
  focusPanelOwner, syncPanelQuery, onPanelNavKeydown, slashOpen,
  slashQuery, slashHi, slashCommands, openSlashPanel,
  closeSlashPanel, slashQueryNorm, slashAliasesOf, slashArgPending,
  slashArgsOf, slashFiltered,
  runSlashCommand, histOpen, histQuery, histHi,
  histQueryNorm, histListOf, histCandidates,
  histChipLabel, pickHistoryRefByClick, openHistPanel, closeHistPanel,
  addOpen, addTab, addHi, addSearchEl,
  addHistQuery, addCmdQuery, addTarget, addOnEdit,
  EDIT_ADD_MOUNT, addStore, addRootRows, addHistPool,
  addHistCandidates, isAddSkillOn, toggleAddSkill, isAddMentioned,
  toggleAddMention, isAddHistPicked, toggleAddHistoryRef, ADD_TITLES,
  REF_SUB_TABS, addSearchable, addQuery, addSearchHint,
  addFootHint, addSkillList, addCmdList, addRefList,
  addListSize, closeAddPanel, ensureRefCandidates, focusAddSearch,
  addFromRoot, backToRoot, switchAddTab, switchRefTab,
  confirmRefRow, confirmAddHi, openAddPanel, toggleAddPanel,
  toggleEditAddPanel, runCmdFromAddPanel
} = (page.panels = useCommandPanels({
    ...g,
    composerRef, editingRef,
    mentionQuery, mentionTab, histPool,
    pendingFiles, pendingImages, pendingMentions, pickedSkills, pendingHistoryRefs,
    editAtts: page.editResend.editAtts,
    editImgs: page.editResend.editImgs,
    editMentions: page.editResend.editMentions,
    editSkills: page.editResend.editSkills,
    editHistRefs: page.editResend.editHistRefs,
    editHistPool: page.editResend.editHistPool,
    editingIdx: page.editResend.editingIdx,
    toggleEditMention: page.editResend.toggleEditMention,
    toggleEditSkill: page.editResend.toggleEditSkill,
    toggleEditHistoryRef: page.editResend.toggleEditHistoryRef,
    pickEditFiles: page.editResend.pickEditFiles,
    focusInput: () => page.chrome?.focusInput?.(),
    pickAttachments: () => page.chrome?.pickAttachments?.(),
    armGoal: (...a) => page.actions?.armGoal?.(...a),
    startGoalNow: (...a) => page.deepask?.startGoalNow?.(...a),
    goalEntryVisible: () => page.deepask?.goalEntryVisible.value ?? false,
    goalArmed: () => goalArmed.value,
    notify: message
  }))

// ---- 页面壳层：思考/滚动/浮层/来源联动四组 hook 与 chip 同步 ----
const {
  clusterIconOf, questionFor, inlineKeys, box,
  thinkHoverRef, thinkPanelPos, thinkPanelEl, mThinkOpen,
  thinkHoverModel, thinkPanelVisible, toggleThinkSheet, onDocPointerDown,
  thinkPanelCaps, thinkPanelLevelOptions, thinkPanelCurrentLevel, thinkPanelStrengthText,
  thinkPanelFoot, pillLevelRef, pillLevelBadge, pillLevelTitle,
  thinkPanelCtxText, onModelOptionHover, scheduleThinkHide, onThinkPanelEnter,
  onThinkPanelLeave, onModelSelectOpenChange, goManageAgents, stickToBottom,
  tailSpacer, onMessagesScroll, scroll, scrollForce,
  updateTailSpacer, railVis, railHoverIdx, railPopStyle,
  railHoverAnchor, showAnchor, gotoAnchor, railActiveIdx,
  updateRailActive, panelStoredRaw, panelStored, panelOpen,
  mPanelOpen, togglePanel, processMsg, focusSpawn,
  focusHitl, revealApprovalCard, eventsOpen, eventsTurn,
  eventsMsg, eventsQuestion, eventsTitle, openEvents,
  srcOpenOf, toggleSrc, ctxCapOpen, ctxCapPos,
  ctxCapEl, showCtxCap, hideCtxCap, onCtxCapEnter,
  onCtxCapLeave, sessionPlans, jumpToPlan, openPlanFromRail,
  disclaimerVisible, DISCLAIMER_TEXT, hoverSource, unhoverSource,
  locateSource, jumpToPlanRow, feedbackVisible, feedbackSubmitting,
  feedbackText, feedbackTarget, openFeedback, doSubmitFeedback,
  escArmed, escArmTimer, armEsc, disarmEsc,
  stopNow, composerPlaceholder, onGlobalKeydown, onGlobalPaste,
  syncScrollbarGutter, onWindowResize, onOrientationChange, focusInput,
  openPreviewFromMsg, onDropFiles, onComposerPaste, pasteView,
  openPasteView, attachInput, pickAttachments, onAttachChange,
  openMentionPanel, OVERLAY_ROOT_CLASSES, hitsOverlay, onDocClickForMention,
  qaAnchors, anchorQ, anchorA, GOAL_KEY,
  fileKeyOf, imgKeyOf, menKeyOf, skillKeyOf,
  histKeyOf, MENTION_TIP, desiredKeys, chipOf,
  onKeys, syncChips, onChipClick, restoreWenquChips,
  todoOpen, srcCardOpen, goalOpen
} = (page.chrome = useChatChrome({
    ...g, composerRef,
    text, pendingFiles, pendingImages, pendingMentions, pickedSkills,
    pendingHistoryRefs, histPool, agentPickerOpen,
    goalArmed, goalStarting, mentionQuery,
    slashOpen: page.panels.slashOpen,
    histOpen: page.panels.histOpen,
    addOpen: page.panels.addOpen,
    addTab: page.panels.addTab,
    addTarget: page.panels.addTarget,
    addHi: page.panels.addHi,
    addFromRoot: page.panels.addFromRoot,
    closeAddPanel: page.panels.closeAddPanel,
    closeAllPanels: page.panels.closeAllPanels,
    ensureRefCandidates: page.panels.ensureRefCandidates,
    openPlanViewer: (...a) => page.plan?.openPlanViewer?.(...a),
    openSlashPanel: page.panels.openSlashPanel,
    openHistPanel: page.panels.openHistPanel,
    notify: message
  }))

// ---- 页面动作：发送、插话、查找、分享、复制、固化与调试 ----
const {
  inlineSlash, sendNow, steerDragIdx, steerDragOver,
  onSteerDragStart, onSteerDragOver, onSteerDragEnd, onSteerDrop,
  steerExtras, searchOpen, searchQuery, searchPos,
  searchInputRef, matchedIdxs, searchPosShown, openSearch,
  closeSearch, gotoMatch, paintSearchHighlight, scrollToMatch,
  onSearchHotkey, onDocEscKeydown, shareVisible, shareLoading,
  shareInfo, shareUrl, loadShare, enableShare,
  stopShare, copyShareLink, shareActive, openShare,
  consumeShareOpen, doEnableShare, regenerateShareLink, doDisableShare,
  openSharedPage, onInputKeydown, hitsNote, copyAnswer,
  copyUserMessage, onMoreAction, addToEval, solidifyOpen,
  solidifyBusy, solidifyForm, gotoSkillsPage, solidifySlug,
  openSolidify, submitSolidify, exportRound, deleteRound,
  debugVisible, debugLoading, debugQuestion, debugResult,
  openDebug, runDebug, debugStages
} = (page.actions = useChatActions({
    ...g,
    box: page.chrome.box,
    focusInput: page.chrome.focusInput,
    openEvents: page.chrome.openEvents,
    openMentionPanel: page.chrome.openMentionPanel,
    hitsOverlay: page.chrome.hitsOverlay,
    closeAddPanel: page.panels.closeAddPanel,
    closeAllPanels: page.panels.closeAllPanels,
    backToRoot: page.panels.backToRoot,
    composerCaret: page.panels.composerCaret,
    confirmAddHi: page.panels.confirmAddHi,
    confirmPanelHi: page.panels.confirmPanelHi,
    focusPanelOwner: page.panels.focusPanelOwner,
    movePanelHi: page.panels.movePanelHi,
    openSlashPanel: page.panels.openSlashPanel,
    openHistPanel: page.panels.openHistPanel,
    onPanelNavKeydown: page.panels.onPanelNavKeydown,
    setPanelTrigger: (pos, ch) => { panelTriggerPos.value = pos; panelTriggerCh.value = ch },
    addOpen: () => page.panels.addOpen.value,
    addTab: () => page.panels.addTab.value,
    addHi: () => page.panels.addHi.value,
    addFromRoot: () => page.panels.addFromRoot.value,
    slashOpen: () => page.panels.slashOpen.value,
    histOpen: () => page.panels.histOpen.value,
    goalArmed: () => goalArmed.value,
    goalStarting: () => goalStarting.value,
    setGoalArmed: v => { goalArmed.value = !!v },
    setText: v => { text.value = v },
    startGoalNow: (...a) => page.deepask?.startGoalNow?.(...a),
    foreignWorkspace: () => page.workspace?.foreignWorkspace.value ?? false
  }))

// ---- 目标条 / 交付抽屉 ----
const {
  goalCancelArmed, goalViewerOpen, deliveryOpen, deliveryTarget,
  armGoalCancel, confirmGoalCancel, openGoalViewer, openDelivery,
  onGoalViewerAction, jumpToGoalTurn
} = (page.goalPanels = useGoalPanels({
    goal: () => sessionGoal.value,
    currentSessionId,
    messages: () => messages.value,
    runGoalAction: a => runGoalAction(a),
    startGoalInlineEdit: () => startGoalInlineEdit(),
    scrollToRow: i => {
      const el = box.value?.querySelector(`[data-row-index="${i}"]`)
      el?.scrollIntoView({ block: 'start', behavior: 'smooth' })
    }
  }))

// 目标条折叠：收起时只留身份行（原文/版本/状态），进度与动作键藏起来；
// 收起同时撤掉取消确认，避免确认条孤零零挂在收起的行下面
function toggleGoalBar() {
  goalOpen.value = !goalOpen.value
  if (!goalOpen.value) goalCancelArmed.value = false
}
// 编辑入口不止条子上的按钮（过程抽屉里也能改）：进编辑态一律先摊开，
// 否则文本域会长在一行收起的条子下面
watch(goalEditing, v => { if (v) goalOpen.value = true })
// 折叠是「这一条目标」的本地偏好：换到另一条目标（新会话/重设目标）时回到展开，
// 免得另一次执行一上来就被上一条的折叠连坐
watch(() => sessionGoal.value && sessionGoal.value.goalId, (id, prev) => {
  if (id && id !== prev) goalOpen.value = true
})

// ---- 会话工作区与权限档位 ----
const {
  sessionWorkspace, permissionPresets, sessionPermission, permissionSaving,
  dangerOpen, dangerAck, dangerSaving, workspaceRefreshing,
  effectivePermission, permissionLabel, wsTitle, foreignWorkspace,
  workspaceSetupHint, skillDocEntry, loadPermissionPresets, applyPermission,
  pickPermission, confirmDanger, loadSessionWorkspace, goBindWorkspace,
  openLocalFolder, refreshSessionWorkspace
} = (page.workspace = useSessionWorkspace({
    currentSessionId,
    workspaceEpoch,
    agent: () => currentAgent.value,
    skillList: () => skillList.value,
    skillDoc: () => skillDoc.value,
    localExecutorId: () => localExecutorId(),
    api: { getSessionWorkspace, getPermissionPresets, setSessionPermission, openProjectFolder },
    notify: message,
    router
  }))

// ---- 计划视图与批准后调整 ----
const {
  planOpen, planHeadText, mentionNames, historyRefPreview,
  planViewer, openPlanViewer, closePlanViewer, onViewerApprove,
  onViewerRevise, onViewerSupersede, onViewerLocate, planMsgOfViewer,
  planTaskTitleOf, planViewerMsg, planViewerProgress, onViewerStop,
  planViewerAdjusts, onViewerAdjust, onViewerLocateSource, locatePlanCardById,
  planAdjust, openPlanAdjust, closePlanAdjust, submitPlanAdjust,
  locateReplanned, locatePlanSource, planEditPlaceholder, onPlanPanelKeydown
} = (page.plan = usePlanPanels({
    messages: () => messages.value,
    currentSessionId, currentSessionTitle, curPlanCard, planExpired,
    pendingPlan, pendingMentions, isNarrow,
    sessionPlans: page.chrome.sessionPlans,
    planStateText, planExecProgressOf, planAdjustsOfMsg,
    submitPlanApproval, revisePlanInPlace, continuePlanConversation,
    adjustPlanAfterApproval,
    jumpToPlan: page.chrome.jumpToPlan,
    stopNow: page.chrome.stopNow,
    notify: message
  }))

// ---- 深度执行、提问面板与初始化 ----
const {
  goalEntryVisible, startGoalNow, onGoalEditKeydown, pendingAsk,
  askQueueCount, askPage, askCustomRef, askNow,
  askHi, askCustomPlaceholder, askTimer, curAsk,
  askPanelHint, askRemaining, askExpired, askCountdownText,
  askOptionParts, focusAskRow, onPick, commitCustomAt,
  advanceAfterSelect, onAskCustomKeydown, onAskPanelKeydown, csumInstrOpen,
  csumInstr, applyRecompact, initialize
} = (page.deepask = useDeepAsk({
    ...g,
    text, goalEditing, goalStarting, goalEditBusy,
    editingIdx: page.editResend.editingIdx,
    editingMessageId: page.editResend.editingMessageId,
    cancelEdit: page.editResend.cancelEdit,
    searchOpen: page.actions.searchOpen,
    searchQuery: page.actions.searchQuery,
    searchPos: page.actions.searchPos,
    paintSearchHighlight: page.actions.paintSearchHighlight,
    scrollToMatch: page.actions.scrollToMatch,
    onGlobalKeydown: page.chrome.onGlobalKeydown,
    onGlobalPaste: page.chrome.onGlobalPaste,
    onDocPointerDown: page.chrome.onDocPointerDown,
    onWindowResize: page.chrome.onWindowResize,
    onOrientationChange: page.chrome.onOrientationChange,
    syncScrollbarGutter: page.chrome.syncScrollbarGutter,
    hideThinkPanel: page.chrome.hideThinkPanel,
    disarmEsc: page.chrome.disarmEsc,
    focusInput: page.chrome.focusInput,
    loadPermissionPresets: page.workspace.loadPermissionPresets,
    notify: message
  }))

</script>

<style scoped>
/* --chat-col：消息列与输入卡片共用的居中列宽。两侧必须同宽同基准，否则宽屏下
   两条竖边对不齐（.msg-block 与输入卡片各写一个 max-width，改一处忘另一处就会漂）。
   --chat-sbw：消息区滚动条实测槽宽（onMounted 起由 syncScrollbarGutter 写入）。
   .messages 是滚动容器、.input 不是，两侧可用宽度天然差一个槽宽 → 居中基准错开半个滚动条。
   两侧内边距都按它补偿，差值归零。 */
.chat2 {
  --chat-col: 860px; --chat-sbw: 0px;
  display: flex; height: 100%; min-width: 0; background: var(--app-panel); position: relative;
}
.chat-col { flex: 1; min-width: 0; display: flex; flex-direction: column; }

/* -- 左侧问题锚点轨 --------------------------------------------------
   不常驻：默认 opacity:0 + pointer-events:none，鼠标进聊天区（.chat2）才淡入。
   刻度列本身超长可滚（长会话几十轮不能顶出屏），滚动条藏掉；预览卡渲染在轨外
   （.qa-pop，fixed 定位）——overflow 容器会把轨内悬浮卡横向裁掉。 */
.qa-rail {
  position: absolute; left: 8px; top: 50%; transform: translateY(-50%);
  display: flex; flex-direction: column; gap: 10px; z-index: 6;
  max-height: min(60vh, 480px); overflow-y: auto; padding: 4px 0;
  scrollbar-width: none;
  opacity: 0; pointer-events: none; transition: opacity .18s ease;
}
.qa-rail.vis { opacity: 1; pointer-events: auto; }
.qa-rail::-webkit-scrollbar { display: none; }
.qa-item { position: relative; display: flex; align-items: center; width: 10px; height: 14px; cursor: pointer; }
.qa-tick {
  flex: none; width: 6px; height: 3px; border-radius: 2px;
  background: var(--app-border); transition: width .15s, background .15s;
}
/* 悬浮才加长（+变深）；当前轮激活只点亮（变深）不加长——长度是「被指到」的反馈 */
.qa-item:hover .qa-tick { width: 10px; background: var(--app-text3); }
.qa-item.on .qa-tick { background: var(--app-text3); }
/* 预览概览卡：fixed 弹层，坐标由 showAnchor 按触发刻度算（贴刻度右侧、垂直居中、顶底夹回视口） */
.qa-pop {
  position: fixed; z-index: 40; width: 320px; padding: 10px 12px; pointer-events: none;
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 10px; box-shadow: var(--app-shadow);
}
.qa-q {
  font-size: 13px; font-weight: 500; color: var(--app-text); line-height: 1.5; word-break: break-word;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.qa-a {
  margin-top: 4px; font-size: 12px; color: var(--app-text3); line-height: 1.55; word-break: break-word;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}

.chat-head {
  display: flex; align-items: center; gap: 12px; padding: 10px 20px;
  border-bottom: 1px solid var(--app-border); flex: none; background: var(--app-panel);
}
.chat-title { font-size: 13px; font-weight: 500; max-width: 40%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 当前工作区标识：安静的一颗 chip（图标 + 工作区名），点它进工作区页。
   不用强调色描边——它是状态不是动作（与侧栏工作区行的存在感同级，不抢标题）。 */
.head-ws {
  display: inline-flex; align-items: center; gap: 5px; flex: none;
  max-width: 220px; height: 22px; padding: 0 8px;
  border: 1px solid var(--app-border); border-radius: 999px;
  background: transparent; cursor: pointer; font: inherit; font-size: 12px; color: var(--app-text2);
}
.head-ws:hover { color: var(--app-accent); border-color: var(--app-accent); }
.head-ws-ic { font-size: 12px; flex: none; }
.head-ws-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.head-ws-archived { opacity: 0.65; }
.head-ws-archived .head-ws-name { text-decoration: line-through; }
.head-tip { font-size: 11px; color: var(--app-text3); cursor: pointer; user-select: none; }
.head-tip:hover { color: var(--app-accent); }
/* 会话内查找条：标题栏与消息流之间的一条，随查找开关出现/消失 */
.chat-search {
  display: flex; align-items: center; gap: 6px; flex: none;
  /* 与正文列（--chat-col=860px）同宽并居中，否则查找条会比消息正文宽出一截 */
  margin: 8px auto 0; padding: 5px 8px; width: 100%; box-sizing: border-box;
  max-width: var(--chat-col);
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: var(--app-radius-sm); box-shadow: var(--app-shadow-sm);
}
.cs-ic { color: var(--app-text3); font-size: 13px; flex: none; }
.cs-input {
  flex: 1; min-width: 0; border: none; outline: none; background: transparent;
  font-size: 13px; color: var(--app-text); padding: 3px 0;
}
.cs-input::placeholder { color: var(--app-text3); }
.cs-count {
  flex: none; min-width: 56px; text-align: right;
  font-size: 12px; color: var(--app-text3); font-variant-numeric: tabular-nums;
}
/* 会话分享弹窗 */
.share-link-row { display: flex; gap: 8px; align-items: center; }
.share-link {
  flex: 1; min-width: 0; padding: 7px 10px; font-size: 13px;
  border: 1px solid var(--app-border); border-radius: var(--app-radius-sm);
  background: var(--app-panel-2); color: var(--app-text);
}
.share-meta { margin-top: 10px; font-size: 12px; color: var(--app-text2); line-height: 1.7; }
.share-warn {
  margin-top: 10px; padding: 8px 10px; font-size: 12px; line-height: 1.6;
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border);
  color: var(--app-warn-text); border-radius: var(--app-radius-sm);
}
.share-actions { margin-top: 14px; display: flex; gap: 8px; }
.share-intro { font-size: 13px; color: var(--app-text2); line-height: 1.8; margin: 0 0 10px; }
/* 头部动作区：右对齐一组；分享/查找用全局 app-icon-btn（无框，悬停显 accent-weak 底色） */
.head-actions { margin-left: auto; display: flex; align-items: center; gap: 2px; }
/* 子智能体药丸与左边那组图标之间留一口气：它俩语义不同（状态 vs 动作），贴在一起读成一排按钮 */
.head-actions .sap { margin-left: 6px; }
.head-actions .app-icon-btn { font-size: 15px; }
/* 分享激活态：琥珀（--app-warn）= 侧栏会话行分享标记同色。选择器比全局 .app-icon-btn:hover
   更高权，悬停时不褪回强调蓝——激活是状态、不该被 hover 盖掉（底色仍随 hover 变） */
.head-actions .app-icon-btn.share-on { color: var(--app-warn); }
.head-quiet-btn {
  border: none; background: transparent; cursor: pointer; padding: 4px 8px;
  border-radius: 6px; font-size: 12px; color: var(--app-text3);
  transition: color .15s, background .15s;
}
.head-quiet-btn:hover { color: var(--app-accent); background: var(--app-accent-weak); }

.messages { flex: 1; overflow-y: auto; padding: 20px 32px 8px; scrollbar-gutter: stable; }
.welcome { text-align: center; padding: 72px 20px 40px; }
/* 历史取数失败卡：中性面板 + 只有图标用危险色（坏的是这一次请求，不是这条会话） */
.hist-err {
  display: flex; align-items: center; gap: 10px;
  max-width: 560px; margin: 16px auto 4px; padding: 12px 14px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
}
.he-ic { font-size: 18px; color: var(--app-danger); flex: none; }
.he-body { flex: 1; min-width: 0; }
.he-t { font-size: 13px; font-weight: 600; color: var(--app-text); }
.he-s { font-size: 12px; color: var(--app-text3); margin-top: 2px; word-break: break-word; }
/* 品牌标 = BrandMark 组件（自带圆角与品牌渐变），留出与标题的间距 */
.welcome-mark { display: block; margin: 0 auto; }
.welcome h2 { margin: 14px 0 6px; font-size: 16px; font-weight: 500; }
.welcome p { color: var(--app-text3); margin: 0 0 18px; }
/* 配置态欢迎区：比常规空态略靠上，避免引导卡在小屏被压到折线以下 */
.welcome-setup { padding-top: 56px; }

/* 欢迎区引导卡容器：与首屏正文同宽，卡片自身在 SetupGuide.vue 定义（604px 上限） */
.welcome-guide { max-width: 620px; margin: 18px auto 0; }

/* 消息错误卡：独立于正文气泡（半程内容保留在上方），分类文案 + 重试 + 详情折叠 */
.msg-error-card {
  margin-top: 8px; border: 1px solid var(--app-danger-border); border-radius: 8px;
  background: var(--app-danger-weak); padding: 9px 12px;
}
.mec-head { font-size: 13px; color: var(--app-danger-text); font-weight: 500; display: flex; align-items: center; gap: 6px; }
.mec-ic { font-size: 13px; }
/* 中断（断线/切后台冻结）不是服务端故障：用警告色而非危险色，避免用户误以为回答作废 */
.msg-error-card.net { border-color: var(--app-warn-border); background: var(--app-warn-weak); }
.msg-error-card.net .mec-head { color: var(--app-warn-text); }
.mec-net-tip { margin-top: 6px; font-size: 12px; line-height: 1.6; color: var(--app-text2); }
.mec-actions { margin-top: 7px; display: flex; gap: 8px; }
.mec-detail { margin-top: 7px; }
.mec-detail summary { font-size: 11px; color: var(--app-text3); cursor: pointer; user-select: none; }
.mec-raw {
  margin: 6px 0 0; font-size: 11px; color: var(--app-text2); white-space: pre-wrap; word-break: break-all;
  background: rgba(0, 0, 0, .03); border-radius: 6px; padding: 7px 9px; max-height: 160px; overflow: auto;
}

/* 行 / 消息块 / 气泡的样式已随布局进 chat/ChatThread.vue（与公开分享页共用一份）。
   本页只保留"就地编辑"那两条：它们约束的是本页插进去的编辑卡 */

/* 宽度与 .msg-block / 输入卡片同源（--chat-col），否则三处各写 860 极易漂移 */
.pending-imgs { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 auto 8px; max-width: var(--chat-col); }
.pending-img { position: relative; }
.pending-img img { width: 60px; height: 60px; object-fit: cover; border-radius: 8px; border: 1px solid var(--app-border); cursor: zoom-in; }
.pending-del { position: absolute; top: -6px; right: -6px; width: 18px; height: 18px; border-radius: 50%;
  background: rgba(0,0,0,.55); color: #fff; font-size: 12px; line-height: 18px; text-align: center; cursor: pointer; }
.pending-del:hover { background: var(--app-danger); }

/* ==================== 过程块：思考 / 过程 / 工具 ====================
 * 过程与正文彻底分开：连续工具合并折叠组，卡片标题行亮出关键参数，展开看完整入参/输出。
 * 这一段只出现在顶部过程块里，不参与正文排版。 */
.tool-status-list { margin-top: 8px; }
/* 过程独白段（<process> 标签分流）：在过程块里自成一行，与正文分开 */
.tl-process-block { margin: 2px 0; }
/* 顶部过程块（统一正文流）：过程与正文彻底分开，展开时整块出现在正文上方 */
.tl-turn-process { margin: 2px 0 10px; }
/* 插话段：用户在这一轮还在跑时补的那句，长在模型真听到它的那个位置（前后两片正文之间）。
   右对齐＋与用户气泡同一形状，一眼认得出是自己说的话；不随过程块收起，始终看得见。 */
.tl-steer { display: flex; justify-content: flex-end; margin: 8px 0; }
.tl-steer-col { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; max-width: 78%; }
.tl-steer-imgs { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; }
.tl-steer-imgs img { max-width: 132px; max-height: 132px; border-radius: 10px; border: 1px solid var(--app-border); object-fit: cover; }
/* 随插话送出的文档附件：与输入框里的附件卡同一观感，靠右对齐在插话气泡上方 */
.tl-steer-atts { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; }
.tl-steer-att {
  display: inline-flex; align-items: center; gap: 5px; max-width: 260px;
  padding: 4px 8px; border-radius: 8px; font-size: 12px;
  background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text);
}
.tl-steer-att-ic { flex: none; font-size: 13px; color: var(--app-accent); }
.tl-steer-att-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tl-steer-att-size { flex: none; font-size: 11px; color: var(--app-text3); }
.tl-steer-txt {
  max-width: 100%; padding: 9px 14px; border-radius: 12px; line-height: 1.65;
  background: var(--app-panel-2); color: var(--app-text);
  white-space: pre-wrap; word-break: break-word;
}
.tl-process-head { display: inline-flex; align-items: center; gap: 5px; border: 0; background: none; padding: 2px 4px; margin: 0 0 2px -4px; border-radius: 4px; cursor: pointer; user-select: none; font-size: 12px; color: var(--app-text3); }
.tl-process-head:hover { color: var(--app-text2); background: var(--app-panel-2); }
.tl-process-title { font-weight: 500; color: var(--app-text2); }
.tl-process { margin: 2px 0; padding: 2px 10px; border-left: 2px solid var(--app-border); color: var(--app-text3); font-size: 12.5px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
/* 思考段：一轮思考一行，长在它发生的位置，避免多轮思考首尾粘成一块读不出时序。
   头部与过程独白同款窄条，展开体保留 markdown 排版。 */
.tl-think-block { margin: 2px 0; }
.tl-think-head { display: flex; align-items: center; gap: 6px; width: calc(100% + 4px); max-width: calc(100% + 4px); min-width: 0; border: 0; background: none; padding: 2px 4px; margin: 0 0 2px -4px; border-radius: 4px; cursor: pointer; user-select: none; font-size: 12px; color: var(--app-text3); text-align: left; }
.tl-think-head:hover { color: var(--app-text2); background: var(--app-panel-2); }
.tl-think-title { flex: none; font-weight: 500; color: var(--app-text2); }
/* 标题右边那句「开头几个字」：一行小字、按可用宽度省略——它是这一段思考的提要，
   不换行也不把标题行撑高（块收着时读得出它在想什么，展开后正文滚长了也还能当锚点）。 */
.tl-think-peek { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 400; color: var(--app-text3); }
.tl-think-head:hover .tl-think-peek { color: var(--app-text2); }
/* 思考进行中的加载态：标题在主色与次文本之间循环渐变，与会话列表 running 标题同一语言。
   原先这里挂一个三点转圈图标——一行小标题里塞图标既吵，又和它下面正在流的正文重复报进度。
   关动效时退化为常亮主色：状态还在，只是不闪。 */
.tl-think-title.running { animation: tl-think-running 1.6s ease-in-out infinite; }
@keyframes tl-think-running { 0%, 100% { color: var(--app-text2); } 50% { color: var(--app-accent); } }
@media (prefers-reduced-motion: reduce) { .tl-think-title.running { animation: none; color: var(--app-accent); } }
.tl-think { margin: 2px 0; padding: 2px 10px; border-left: 2px solid var(--app-border); color: var(--app-text3); font-size: 12.5px; line-height: 1.7; max-height: 320px; overflow-y: auto; }
.tl-think :deep(.md > p) { margin: 4px 0; }
/* 过程簇头（DSH ProcessGroupHeader 同款）：无底无框一行文字，行首 16px 活动图标位——
   收起时常显活动图标，悬停/展开让位给下开三角（同一叠位换 opacity）。跑动中文字扫光。 */
.tl-cluster { margin: 3px 0; min-width: 0; }
.tl-cluster-bar {
  display: flex; align-items: center; gap: 6px; max-width: 100%;
  padding: 0; border: 0; background: none;
  color: var(--app-text3); font: inherit; font-size: 14px; line-height: 24px;
  text-align: left; cursor: pointer; user-select: none; transition: color .1s ease;
}
.tl-cluster-bar:hover { color: var(--app-text2); }
.tl-cluster.open .tl-cluster-bar { padding-bottom: 8px; }
/* 活动图标与收起箭头同叠位：收起时恒显活动图标（悬停不换——图标是这一步的身份，不是开关），
   真正展开后才让位给朝上的收起箭头 */
.tl-cluster-ic { position: relative; flex: none; display: inline-flex; align-items: center; justify-content: center; width: 16px; height: 16px; color: inherit; }
.tl-cluster-ic .tl-ic-a, .tl-cluster-ic .tl-ic-c { position: absolute; inset: 0; display: inline-flex; align-items: center; justify-content: center; font-size: 13px; transition: opacity .1s ease, transform .1s ease; }
.tl-cluster-ic .tl-ic-c { opacity: 0; }
.tl-cluster.open .tl-cluster-bar .tl-ic-a { opacity: 0; }
.tl-cluster.open .tl-cluster-bar .tl-ic-c { opacity: 1; transform: rotate(180deg); }
.tl-cluster-ic .ic-err { color: var(--app-danger); }
.tl-cluster-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 扫光文字（DSH TextShimmer 简化版）：次要灰底上一道高光横扫，替代转圈/呼吸的「活着」表达 */
.shimmer, .tl-live-name.shimmer {
  color: transparent; background: linear-gradient(105deg, var(--app-text3) 0%, var(--app-text3) 40%, var(--app-text) 50%, var(--app-text3) 60%, var(--app-text3) 100%);
  background-size: 220% 100%; -webkit-background-clip: text; background-clip: text;
  animation: wq-shimmer 1.8s linear infinite;
}
@keyframes wq-shimmer { from { background-position: 120% 0; } to { background-position: -120% 0; } }
@media (prefers-reduced-motion: reduce) {
  .shimmer, .tl-live-name.shimmer { animation: none; color: var(--app-text3); background: none; }
  .tl-cluster-ic .tl-ic-a, .tl-cluster-ic .tl-ic-c { transition: none; }
}
/* 跑轮中的簇帽体（DSH 的 .body 同款）：固定高度内滚的裸排清单——无卡底无边框无缩进，
   行与行 4px 间距；DSH 同参 min(400px,50vh)，H5 在移动壳里收成 180px。
   一行一步贴底跟随最新一步；上下边缘 24px 渐隐由 vEdgeFade 按「还能不能滚」切 ef-top/ef-bot。 */
.tl-cluster-live {
  max-height: min(400px, 50vh); overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable;
  padding: 2px 0 0;
}
.tl-cluster-live > * + * { margin-top: 4px; }
.tl-cluster-live.ef-top { mask-image: linear-gradient(to bottom, transparent 0, #000 24px, #000 100%); }
.tl-cluster-live.ef-bot { mask-image: linear-gradient(to bottom, #000 0, #000 calc(100% - 24px), transparent 100%); }
.tl-cluster-live.ef-top.ef-bot { mask-image: linear-gradient(to bottom, transparent 0, #000 24px, #000 calc(100% - 24px), transparent 100%); }
/* 步骤行（DSH ToolRow 折叠态同款）：24px 一行——[16px 图标位] 6 [13px 动词] 8 [2px 点] 8 [13px 摘要省略]。
   整行可点展开详情；悬停只加深文字色（状态图标保持原样——✓/错误图标是每行的身份，hover 不动它），
   行真正展开后图标位才让位给上收箭头。 */
.tl-live-row { display: flex; align-items: center; gap: 6px; min-height: 24px; font-size: 13px; color: var(--app-text3); cursor: pointer; transition: color .1s ease; }
.tl-live-row:hover { color: var(--app-text2); }
.tl-live-ic { position: relative; flex: none; display: inline-flex; align-items: center; justify-content: center; width: 16px; height: 16px; color: inherit; }
.tl-live-ic .tl-ic-a, .tl-live-ic .tl-ic-c { position: absolute; inset: 0; display: inline-flex; align-items: center; justify-content: center; font-size: 13px; transition: opacity .1s ease; }
.tl-live-ic .tl-ic-c { opacity: 0; }
/* 帽体尾行（还没换来工具的思考/独白）：整行可点开看正文，展开后图标位让位给上收箭头。
   转圈只在真的还在长时出现——写死 loading 会让答完的这一轮永远转下去。 */
.tl-live-tail-btn { display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0;
  border: 0; background: none; padding: 0; margin: 0; font: inherit; color: inherit; text-align: left; cursor: pointer; }
.tl-live-row.open .tl-live-ic .tl-ic-a { opacity: 0; }
.tl-live-row.open .tl-live-ic .tl-ic-c { opacity: 1; transform: rotate(180deg); }
.tl-live-tail-body { margin: 2px 0 4px 22px; max-height: 260px; overflow-y: auto; }
.tl-live-ic .ic-err { color: var(--app-danger); }
.tl-live-name { flex: none; color: inherit; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 帽体尾行的提要：与步骤摘要同一套省略口径，一行、按可用宽度收 */
.tl-live-peek { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: inherit; opacity: .9; }
.tl-live-row.run .tl-live-name { color: var(--app-text2); }
/* 标题与摘要之间那颗 2×2 分隔点（DSH .sep）：摘要缺席时整颗跟着缺席 */
.tl-live-sep { flex: none; width: 2px; height: 2px; border-radius: 1px; margin: 0 2px; background: currentColor; opacity: .55; }
.tl-live-brief { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: inherit; }
/* 失败行：摘要读成错误色（DSH errorSummary 同语言）；被挡下行尾一面小旗 */
.tl-live-row.err .tl-live-brief, .tl-live-row.err .tl-live-name { color: var(--app-danger); }
.tl-live-flag { flex: none; font-size: 11px; color: var(--app-text3); }
/* 单步详情（DSH 的 IN/OUT 卡同构）：细边框圆角卡，分段 grid——左列段名、右列内容，
   每段独立内滚 150px 上限（长入参不埋短输出），段与段之间 0.5px 细分割线，段名贴段顶随滚。 */
.tl-live-detail { margin: 4px 0 4px 4px; border: 0.5px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); overflow: hidden; }
.tl-live-detail .tl-io-sec { display: grid; grid-template-columns: max-content 1fr; column-gap: 14px; align-items: baseline; padding: 10px 14px; max-height: 150px; overflow-y: auto; }
.tl-live-detail .tl-io-label { position: sticky; top: 0; align-self: start; font-size: 10.5px; color: var(--app-text3); }
.tl-live-detail .tl-io-body { min-width: 0; }
.tl-live-detail .tl-io-div { height: 0.5px; background: var(--app-border); }
.tl-live-detail .tl-io { margin: 0; max-height: none; overflow: visible; }
/* 这一步前后的思考：整段成文的话走 markdown 排版，不与 IN/OUT 抢两列 grid；
   只留一层滚动（内容自己不再内滚，见 NoteBody 的 bare） */
.tl-live-think { padding: 10px 14px; max-height: 220px; overflow-y: auto; }
.tl-live-think-label { margin: 0 0 4px; font-size: 10.5px; color: var(--app-text3); }
/* 多段连排时给段界：展开后看得出每一段从哪开始 */
.tl-think-seg + .tl-think-seg { margin-top: 8px; padding-top: 8px; border-top: 0.5px solid var(--app-border); }
/* 簇内＝一行一步的紧凑清单：卡片去描边去卡底、行首补序号、整簇共一条左轨。
   簇外的工具组仍是原来的描边卡——那是一步插在正文之间，不是一串清单，收掉描边反而读不出边界。 */
.tl-group.inCluster { margin: 0 0 0 12px; }
.tl-group.inCluster .tl-group-body { margin: 0; padding-left: 10px; border-left: 2px solid var(--app-border); }
.tl-group.inCluster .tl-card { border: 0; background: none; border-radius: 5px; width: 100%; }
.tl-group.inCluster .tl-card-head { padding: 3px 6px; }
.tl-group.inCluster .tl-card-head:hover { background: var(--app-panel-2); color: var(--app-text); }
.tl-group.inCluster .tl-caret-btn { margin-left: auto; }
/* 行首序号：与簇头那个「已执行 N 个操作」同一把尺，一眼数得出跑到第几步、也认得出被跳过的步号 */
.tl-idx { flex: none; width: 18px; font-size: 10.5px; color: var(--app-text3);
  font-variant-numeric: tabular-nums; text-align: right; }
/* 簇尾挂不出去的那一段（正在想、还没调工具）自成一节：行壳与工具卡同款，只是没有工具名 */
.tl-note-head { display: flex; align-items: center; gap: 6px; width: 100%; padding: 3px 6px; margin: 0;
  border: 0; background: none; border-radius: 5px; font-size: 12px; color: var(--app-text2); cursor: pointer; }
.tl-note-head:hover { background: var(--app-panel-2); }
.tl-group { margin: 3px 0; }
.tl-group-bar {
  display: inline-flex; align-items: center; gap: 6px; width: fit-content; max-width: 100%;
  padding: 3px 9px; font-size: 12px; color: var(--app-text2); text-align: left;
  background: var(--app-bg); border: 1px solid var(--app-border); border-radius: 8px;
  cursor: pointer; user-select: none;
}
.tl-group-bar:hover { border-color: var(--app-accent); color: var(--app-text); }
/* 簇内的并行批次条：与它下面那些行同一种壳（带描边会在清单里戳出一颗药丸） */
.tl-group.inCluster .tl-group-bar { border: 0; background: none; padding: 3px 6px; margin: 0 -6px; border-radius: 5px; }
.tl-group.inCluster .tl-group-bar:hover { border-color: transparent; background: var(--app-panel-2); }
/* 折叠三角唯一样式（CaretRightOutlined）：10px 灰、行尾、展开旋转 90°（▶→▼）；
 * 深度思考头/执行过程/工具组条/工具卡片/检索行/子智能体头/引用来源组共用，不再各写一套 */
.tl-caret { flex: none; font-size: 10px; color: var(--app-text3); transition: transform .15s; line-height: 1; }
.tl-caret.open { transform: rotate(90deg); }
.tl-group-body {
  margin: 6px 0 2px 12px; padding-left: 10px; border-left: 2px solid var(--app-border);
  display: flex; flex-direction: column; gap: 6px; max-width: 100%;
}
.tl-group-body.solo { margin: 4px 0 2px; padding-left: 0; border-left: none; }
/* 簇内只有一个工具组时组条不出（与簇头数同一批操作）：卡片直接摊在簇头下，不再多缩一层 */
.tl-group.flat .tl-group-body { margin-left: 0; }
.tl-card { border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); width: fit-content; max-width: 100%; }
.tl-card.err { border-color: color-mix(in srgb, var(--app-danger) 45%, transparent); }
.tl-card-head {
  display: flex; align-items: center; gap: 6px; padding: 4px 9px;
  font-size: 12px; color: var(--app-text2); background: none; border: none; text-align: left;
  cursor: pointer; user-select: none; max-width: 100%;
}
.tl-card-head:hover { color: var(--app-text); }
.tl-brief {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px;
  color: var(--app-accent); background: var(--app-accent-weak);
  padding: 1px 6px; border-radius: 4px; max-width: 420px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.tl-card-body { padding: 0 10px 8px; max-width: 100%; }
/* 派遣那一支正在跑的第几步：跟在助手名后面，弱一档的主色，不抢工具名。
   卡片是 width:fit-content，这一行必须自己截断，否则一条长路径会把整张卡顶宽 */
.tl-live {
  flex: none; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 11px; color: var(--app-accent); opacity: .9;
}
.tl-io-label { font-size: 11px; color: var(--app-text3); margin: 6px 0 2px; }
.tl-io {
  margin: 0; padding: 6px 8px; border-radius: 6px; background: var(--app-bg);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px;
  line-height: 1.6; color: var(--app-text); max-height: 240px; overflow: auto;
  white-space: pre-wrap; word-break: break-all; min-width: 320px; max-width: 100%;
}
.tl-io-err { color: var(--app-danger); }
.tl-io.live { border: 1px solid color-mix(in srgb, var(--app-accent) 35%, transparent); background: var(--app-accent-weak); }
/* 执行过程（事件账本）的样式随组件走：web/src/components/SessionEventsPanel.vue */
.tool-ic { font-size: 13px; }
.tool-ic-run { color: var(--app-accent); }
.tool-ic-ok { color: var(--app-ok); }
.tool-ic-err { color: var(--app-danger); }
.tool-name { font-weight: 500; color: var(--app-text2); }
.tool-dur { color: var(--app-text3); }
.tool-fail { color: var(--app-danger); }

/* 手动压缩当场那条分隔线（在 v-for 外面，与上面 .row 里那几条同形） */
.cn-tail { margin-block: -8px 12px; }

/* 摘要弹窗 */
.csum-head { display: flex; align-items: baseline; gap: 10px; }
.csum-title { font-size: 15px; font-weight: 600; color: var(--app-text); }
.csum-sub { font-size: 12px; color: var(--app-text3); }
.csum-stats {
  display: flex; flex-wrap: wrap; gap: 6px 18px; margin-bottom: 10px;
  padding: 8px 10px; border-radius: 8px; background: var(--app-panel-2);
}
.csum-stat { display: inline-flex; align-items: baseline; gap: 6px; font-size: 12px; }
.cs-k { color: var(--app-text3); }
.cs-v { color: var(--app-text); font-weight: 500; }
/* 摘要正文按 Markdown 渲染（模型产出的就是条目化列表），不再用 pre 把 - 和 # 原样摊出来 */
.csum-md {
  max-height: 46vh; overflow: auto; margin: 0; padding: 10px 12px; border-radius: 8px;
  background: var(--app-panel-2); border: 1px solid var(--app-border);
  font-size: 13px; line-height: 1.7;
}
.csum-foot { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.csum-tip { flex: 1; min-width: 0; font-size: 12px; color: var(--app-text3); line-height: 1.6; }
.csum-acts { flex: none; display: inline-flex; gap: 8px; }
.csum-instr { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.csum-instr-input {
  flex: 1; min-width: 0; padding: 6px 10px; font-size: 12px;
  border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel); color: var(--app-text);
}
.csum-instr-input:focus { border-color: var(--app-accent); }
/* 粘贴全文等纯文本弹层仍用这份 pre 容器 */
.csum-body {
  margin: 0; max-height: 52vh; overflow: auto; padding: 10px 12px; border-radius: 8px;
  background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text2);
  font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word;
}

/* 上下文容量卡（悬浮右栏模型行）：与深度思考面板同一套 fixed 定位约定 */
.ctxcap-float { position: fixed; z-index: 1060; }
  /* 窄屏 sheet 的关闭口（仅窄屏渲染，见模板）：右上角 34×34 触控热区 */
  .rp-sheet-close {
  position: absolute; top: 6px; right: 8px; z-index: 2;
  width: 34px; height: 34px; border: none; background: transparent; cursor: pointer;
  color: var(--app-text3); font-size: 14px; touch-action: manipulation;
  display: flex; align-items: center; justify-content: center; border-radius: 8px;
}
.rp-sheet-close:hover { color: var(--app-accent); background: var(--app-accent-weak); }
.ctxcap {
  width: 268px; padding: 12px 14px; border-radius: 12px;
  background: var(--app-panel); border: 1px solid var(--app-border);
  box-shadow: 0 10px 32px -8px rgba(16, 24, 40, .18);
}
.ctxcap-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 8px; }
.ctxcap-title { font-size: 13px; font-weight: 600; }
.ctxcap-num { font-size: 12px; color: var(--app-text3); }
.ctxcap-bar { display: flex; height: 6px; border-radius: 3px; overflow: hidden; background: var(--app-accent-weak); }
.ctxcap-fill { height: 100%; border-radius: 3px; background: var(--app-accent); transition: width .25s; }
.ctxcap-fill.warn { background: var(--app-warn); }
.ctxcap-fill.danger { background: var(--app-danger); }
.ctxcap-row { display: flex; align-items: baseline; gap: 6px; margin-top: 6px; font-size: 12px; }
/* 子项（未缓存输入 / 其中思考）缩进一档、名字弱一档：一眼看出它是上一行里拆出来的，不是并列的一项 */
.ctxcap-row.is-sub { padding-left: 12px; }
.ctxcap-row.is-sub .ctxcap-row-label { color: var(--app-text3); }
.ctxcap-row-label { color: var(--app-text2); }
.ctxcap-row-val { margin-left: auto; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.ctxcap-empty { margin-top: 6px; font-size: 12px; color: var(--app-text3); }
.ctxcap-tip { margin-top: 6px; font-size: 11px; color: var(--app-text3); line-height: 1.5; }
/* 卡底操作区：与上面的实测用量行隔一条线，按钮铺满卡宽（268px 里摆一个小按钮不居中会像漏了一半） */
.ctxcap-foot { margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--app-border); }
.ctxcap-compact { width: 100%; justify-content: center; }

/* 上下文容量圆环（输入框右下、模型选择器左侧）：与右栏模型行共用同一张容量卡，>80% 警告 / >95% 危险 */
/* 下移 1px：1x 屏实测圆环几何中心比「中」档位标记高 1px（圆环与标记盒居中基准不同：
   标记墨迹略低于盒中心、圆环墨迹即盒中心），补齐后与标记同一条视觉中线 */
.ctx-ring { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; margin-right: 4px; border-radius: 50%; cursor: default; transition: background .15s; transform: translateY(1px); }
.ctx-ring:hover { background: var(--app-panel-2); }
.ctx-ring-bg { stroke: var(--app-border); }
.ctx-ring-val { stroke: var(--app-accent); transition: stroke-dasharray .25s; }
.ctx-ring.warn .ctx-ring-val { stroke: var(--app-warn); }
.ctx-ring.danger .ctx-ring-val { stroke: var(--app-danger); }


/* 脚本扇出清单：一行一次子调用，窄栏时摘要自己换行，不横向溢出 */
.fan-list { display: flex; flex-direction: column; gap: 2px; margin: 2px 0 8px; font-size: 12px; }
.fan-row { display: grid; grid-template-columns: 20px 96px 60px 1fr; gap: 6px; align-items: baseline; }
.fan-row.err .fan-name, .fan-row.err .fan-brief { color: var(--app-danger); }
.fan-idx { color: var(--app-text3); font-variant-numeric: tabular-nums; text-align: right; }
.fan-name { color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fan-dur { color: var(--app-text3); font-variant-numeric: tabular-nums; }
.fan-brief { color: var(--app-text3); overflow-wrap: anywhere; }
.fan-more { color: var(--app-text3); font-size: 11px; }
/* 在途那条（实时面板）：还没有结果可摘要，用主色标出"正在跑"而不是留一行空白 */
.fan-row.run .fan-name { color: var(--app-accent); }
.fan-row.run .fan-dur { color: var(--app-accent); }
/* 额度/并发上限/交付数一行：清单之下、入参之上，弱文本不抢正文 */
.fan-budget { color: var(--app-text3); font-size: 11px; margin: 2px 0 8px; }
/* 运行未完成的那三种收尾（超时/沙盒故障/退出码非 0）：警示色，挂在卡片头上不点开也看得见 */
.fan-run { color: var(--app-warn); font-size: 11px; }
/* 并发水位总览：脚本跑着的时候挂在过程簇头上，一处看清"跑了多少、在途几条" */
.run-board { color: var(--app-accent); font-size: 11px; }

.related { margin-top: 10px; display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.related-label { font-size: 12px; color: var(--app-text3); }
.related-tag {
  font-size: 11px; color: var(--app-ok); background: var(--app-ok-weak); border-radius: 999px;
  padding: 3px 10px; cursor: pointer;
}
.related-tag:hover { background: var(--app-ok-weak-hover); }

/* 重新生成的多版本切换器：与操作图标同一行，弱化呈现 */
.ver-switch {
  display: inline-flex; align-items: center; gap: 1px; margin-right: 10px;
  font-size: 12px; color: var(--app-text3); user-select: none;
}
.ver-btn {
  border: none; background: transparent; cursor: pointer; color: var(--app-text2);
  font-size: 15px; line-height: 1; padding: 0 5px; border-radius: 4px;
}
.ver-btn:hover:not(:disabled) { background: var(--app-panel-2); color: var(--app-accent); }
.ver-btn:disabled { opacity: .45; cursor: not-allowed; }
.ver-idx { font-variant-numeric: tabular-nums; }
/* 本轮被叫停且没产出回答：跟在问题下方一行安静说明（与时间戳同一弱化口径），
   不造空回答气泡——「这一问没有回答」是那一问的事实，不是一条回答的内容 */
.msg-stopped {
  display: flex; align-items: center; gap: 4px; justify-content: flex-end;
  margin-top: 2px; font-size: 11px; color: var(--app-text3); user-select: none;
}
/* 这一问失败了（被挡下或跑挂、且什么都没产出）：占与「本轮已停止」同一行位，
   但那是平静的事实、这是要人看见的红色；文案可能很长，截一行，全文在悬停说明里 */
.msg-fail {
  display: flex; align-items: center; gap: 4px; justify-content: flex-end;
  margin-top: 2px; font-size: 11px; color: var(--app-danger-text); user-select: none;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.msg-edit-row {
  position: absolute; top: calc(100% + 2px); left: 0; right: 0; height: 24px; z-index: 1;
  display: flex; align-items: center; justify-content: flex-end; gap: 6px;
  opacity: 0; transition: opacity .15s;
}
.msg-block:hover .msg-edit-row { opacity: 1; }
/* 操作行是绝对定位、不占流：按钮底边到气泡下沿 27px，比默认的 20px 行间距还多 7px，
   下一轮开头那条细线（生成中的状态行顶线、流尾压缩记录）正好从操作行中间穿过。
   主对话页只在用户行把这段间距让到 28px，让线落到操作行下面（分享页没有这行，不受影响） */
.messages :deep(.row.user) { margin-bottom: 28px; }
/* 就地编辑卡：原位替换该用户气泡（右对齐紧凑卡，对齐主流产品「编辑=原地重写这一问」），
   不再全宽悬浮盖住回答。起步单行随内容长高；图片/附件/@ 引用 chips 随行展示，可增删 */
.msg-block.user.editing > .bubble, .msg-block.user.editing > .msg-edit-row { display: none; }
.msg-inline-edit {
  width: min(560px, 100%);
  background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 14px;
  padding: 10px 12px 10px 14px; box-shadow: 0 6px 20px rgba(0, 0, 0, .06);
  display: flex; flex-direction: column; gap: 8px;
}
.msg-inline-edit-input {
  width: 100%; resize: none; overflow-y: auto; max-height: 200px;
  border: none; padding: 0; background: transparent; color: var(--app-text);
  font: inherit; line-height: 1.65; outline: none;
}
.msg-inline-edit-input::placeholder { color: var(--app-text3); }
.msg-inline-edit .pending-imgs, .msg-inline-edit .pending-files { margin: 0; max-width: none; }
.edit-mentions { display: flex; flex-wrap: wrap; gap: 6px; }
/* 编辑卡里的「+」面板：主输入框那份靠浮层（bottom:100% 往上弹），这张卡可能停在消息流
   中间，往上弹会被视口裁掉，所以改成静态流——面板直接长在卡里、把卡往下撑开。
   挂载点用 display:contents 挂空不占位（不留一条 gap），有面板时它直接成为卡的一个 flex 项 */
.msg-inline-edit .add-mount { display: contents; }
.msg-inline-edit .mention-panel { position: static; box-shadow: none; }
.msg-inline-edit-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.msg-inline-edit-hint {
  margin-right: auto; display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; color: var(--app-text3); min-width: 0;
}
.msg-inline-edit-actions .app-btn { border-radius: 999px; padding: 5px 14px; }
.msg-inline-edit-actions .app-btn.ghost { background: var(--app-panel); }
.msg-time-inline { font-size: 11px; color: var(--app-text3); margin-left: 8px; white-space: nowrap; user-select: none; }
.msg-tokens { font-size: 11px; color: var(--app-text3); white-space: nowrap; cursor: default; }
/* 回到底部：与消息列/输入卡同宽同基准（--chat-col），右对齐、贴在卡片正上方。
   wrapper 铺满列宽只为拿对齐基准，pointer-events 关掉免得挡住正文末尾那 28px 的点击 */
.jump-latest-wrap {
  position: sticky; bottom: 12px; z-index: 5;
  box-sizing: border-box; max-width: var(--chat-col); margin: 0 auto 4px;
  padding-right: 16px; display: flex; justify-content: flex-end;
  pointer-events: none;
}
.jump-latest {
  width: 28px; height: 28px; line-height: 26px; text-align: center;
  padding: 0; background: var(--app-panel); color: var(--app-text); font-size: 14px;
  border: 1px solid var(--app-border); border-radius: 999px; cursor: pointer; user-select: none;
  box-shadow: var(--app-shadow-sm); transition: border-color .15s, box-shadow .15s;
  pointer-events: auto;
}
.jump-latest:hover { border-color: var(--app-border-strong); box-shadow: var(--app-shadow); }

/* 任务清单（输入框正上方、目标条之上）：本会话最近一次 writeTodo 的整表快照。
   与输入卡片同宽同基准（--chat-col），独立成一张卡：它说的是「这件事推到哪了」，不是要发出去的话，
   所以不进卡片内部——那里已被目标条/正文/工具条占满，且提问与计划面板顶替打字框时整块会换掉。
   列表区有最高上限（约五行），超出只在卡内滚：十项清单就是十行长句，不设帽会把输入区顶出屏幕，
   用户为了打字还得先滚页面——那比原先藏在右栏更糟。卡头不参与滚动，收起/展开入口要一直在。 */
.todo-bar {
  box-sizing: border-box; max-width: var(--chat-col); margin: 0 auto 8px;
  padding: 0 12px; border: 1px solid var(--app-border); border-radius: 14px;
  background: var(--app-panel); box-shadow: var(--app-shadow-sm);
}
.tb-head {
  display: flex; align-items: center; gap: 8px; width: 100%; padding: 9px 6px;
  background: none; border: none; cursor: pointer; text-align: left;
}
.todo-bar:not(.collapsed) .tb-head { border-bottom: 1px solid var(--app-border); }
.tb-ico { flex: none; font-size: 14px; color: var(--app-text3); }
.tb-label { flex: none; font-size: 13px; font-weight: 600; color: var(--app-text); }
.tb-count {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; font-variant-numeric: tabular-nums; color: var(--app-text3);
}
/* 列表两侧各留 6px：.tb-item 用 -6px 负 margin 把高亮带外扩，而 overflow-y:auto 会把
   overflow-x 一起变成裁剪，裁切线就是这个内边距盒——不给这 6px，色带两端被切平、圆角消失，
   左边缘还会正好压在勾选框上（卡头同宽内缩 6px，图标与勾选框才仍然对齐）。 */
.tb-list { max-height: 168px; overflow-y: auto; padding: 4px 6px 8px; }
.tb-item {
  display: flex; align-items: flex-start; gap: 6px; font-size: 12.5px; line-height: 1.5;
  padding: 4px 6px; margin: 0 -6px; border-radius: 7px;
}
.tb-item.doing { background: var(--app-accent-weak); }
.tb-box { flex: none; display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 14px; margin-top: 2px; border: 1px solid var(--app-border); border-radius: 4px; font-size: 10px; line-height: 1; color: var(--app-panel); }
.tb-box.run { border-color: var(--app-accent); color: var(--app-accent); font-size: 11px; }
.tb-run-tag { flex: none; margin-left: 2px; font-size: 11px; line-height: 18px; color: var(--app-accent); }
.tb-item.done .tb-box { background: var(--app-ok); border-color: var(--app-ok); }
.tb-item.done .tb-txt { color: var(--app-text3); text-decoration: line-through; }
.tb-item.doing .tb-txt { color: var(--app-accent); font-weight: 500; }
.tb-item.failed .tb-box { background: #fff1f0; border-color: var(--app-danger, #cf1322); color: var(--app-danger, #cf1322); }
.tb-item.failed .tb-txt { color: var(--app-danger, #cf1322); }
.tb-txt { min-width: 0; word-break: break-word; }

/* 插话队列（输入框正上方）：生成中按发送先落到这里排队，点「立即发送」才真发给模型。
   没点之前是纯本地的，所以「删除/修改」是白拿的反悔窗口；拖动可排序。
   与 @引用/技能 chip 同一带、同宽居中——它是"还没发出去的话"，视觉上属于输入区而不是消息流。 */
.steer-queue {
  display: flex; flex-direction: column; gap: 6px;
  padding: 2px 4px 6px; max-width: var(--chat-col); margin: 0 auto;
}
.steer-item {
  display: flex; align-items: center; gap: 8px; min-width: 0;
  padding: 5px 8px 5px 6px; font-size: 13px; color: var(--app-text);
  border: 1px solid var(--app-accent); border-radius: 10px;
  background: var(--app-accent-weak);
}
.steer-item.deferred { border-color: var(--app-border); background: var(--app-panel); color: var(--app-text2); }
.steer-item.sending { opacity: .6; }
.steer-item.dragging { opacity: .4; }
.steer-grip { flex: none; cursor: grab; color: var(--app-text3); font-size: 13px; line-height: 1; }
.steer-grip:active { cursor: grabbing; }
.steer-txt { flex: 1 1 auto; min-width: 0; white-space: pre-wrap; word-break: break-word; }
.steer-extra, .steer-state { flex: none; font-size: 11px; color: var(--app-text3); }
.steer-act {
  flex: none; display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel);
  padding: 2px 8px; font-size: 12px; color: var(--app-text); cursor: pointer;
}
.steer-act:hover { background: var(--app-panel-2); }
.steer-act.primary { border-color: var(--app-accent); color: var(--app-accent); }
/* 就地编辑卡里的引用标签（主输入框那一套已改成正文内联 chip，见 chat/Composer.vue）；
   × 可整体移除（@ 引用与技能 chip 共用） */
.at-chip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 260px;
  padding: 2px 6px; border-radius: 6px; font-size: 12px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.at-chip-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.at-chip-del { flex: none; cursor: pointer; font-size: 13px; line-height: 1; opacity: .65; padding: 0 1px; }
.at-chip-del:hover { opacity: 1; color: var(--app-danger); }
/* @ 引用 chip：知识库/文档/智能体用图标与底色区隔（技能 chip 沿用 .skill-chip 原样） */
.at-chip-ic { font-size: 12px; flex: none; }
.mention-chip.mention-doc { background: var(--app-warn-weak); color: var(--app-warn-text); }
/* @ 智能体 chip（§4 轮级委派）：沿用默认主色弱底，与 kb（同主色系）靠图标区分 */
.mention-chip.mention-agent { border: 1px solid var(--app-accent); }
/* # 历史引用 chip：info 弱底与 kb（主色）/doc（warn）区分 */
.at-chip.hist-chip { background: var(--app-info-weak); color: var(--app-accent); border: 1px solid var(--app-info-border); }

/* @ 引用候选面板：贴输入框上沿（不遮断正文输入，点面板外关闭） */
.mention-panel {
  position: absolute; left: 0; right: 0; bottom: calc(100% + 8px); z-index: 40;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 12px;
  box-shadow: var(--app-shadow-lg); display: flex; flex-direction: column; overflow: hidden;
}
.mention-head {
  display: flex; align-items: center; gap: 4px;
  padding: 8px 8px 8px 12px; border-bottom: 1px solid var(--app-border);
}
/* 触发符 + 实时筛选词的只读回显（输入源是输入框，这里只做状态展示，不能再是可编辑框） */
.mention-head-tag {
  flex: none; font-size: 13px; font-weight: 600; color: var(--app-accent);
  background: var(--app-accent-weak); border-radius: 4px; padding: 0 5px; line-height: 18px;
}
.mention-head-word {
  flex: 1; min-width: 0; font-size: 13px; color: var(--app-text);
  padding: 4px 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mention-head-word.dim { color: var(--app-text3); }
/* 上下各 8px：药丸上沿不能压住表头那条分隔线（原来顶边距是 0，正好贴死） */
.mention-tabs { display: flex; gap: 4px; padding: 8px 10px 0; }
.mention-tab {
  display: inline-flex; align-items: center; gap: 4px; border: none; cursor: pointer;
  background: transparent; color: var(--app-text3); font-size: 12px;
  padding: 3px 9px; border-radius: 999px;
}
.mention-tab:hover { background: var(--app-panel-2); color: var(--app-text2); }
/* 带英文别名的命令（/compact）：别名在前，中文名在后——用户照着敲就能命中 */
.slash-cmd { margin-right: 6px; font-size: 12px; color: var(--app-accent); }
.mention-tab.on { background: var(--app-accent-weak); color: var(--app-accent); }
.mention-list { overflow-y: auto; padding: 6px; max-height: 264px; }
.mention-item { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 8px; cursor: pointer; }
.mention-item:hover { background: var(--app-panel-2); }
.mention-item.on { background: var(--app-accent-weak); }
/* 生成中不可用的行（「+」面板命令页）：输入框整体禁用，这里给同样的视觉反馈 */
.mention-item.mi-off { opacity: .55; cursor: not-allowed; }
.mention-item.mi-off:hover { background: transparent; }
/* 键盘 ↑↓ 所在项：面板不再抢焦点，高亮是用户「现在按 Enter 会选中谁」的唯一视觉线索 */
.mention-item.hi { background: var(--app-panel-2); box-shadow: inset 0 0 0 1px var(--app-accent-border, var(--app-accent)); }
.mention-ava {
  width: 24px; height: 24px; border-radius: 6px; flex: none; font-size: 13px;
  background: var(--app-panel-2); color: var(--app-text2);
  display: flex; align-items: center; justify-content: center;
}
.mention-item.on .mention-ava { background: var(--app-accent); color: #fff; }
.mention-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.mention-name { font-size: 13px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mention-desc { font-size: 11px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mention-check { color: var(--app-accent); flex: none; }
.mention-empty { padding: 18px 8px; text-align: center; font-size: 12px; color: var(--app-text3); }
.mention-foot { padding: 7px 12px; font-size: 11px; color: var(--app-text3); border-top: 1px solid var(--app-border); }
/* @ 智能体候选：头像自带配色块（AgentAvatar），只对齐基线不套底色 */
.mention-ava-agent { flex: none; margin-top: 1px; }
/* # 历史引用面板：问/答头像按角色着色（问=主色、答=弱底），一眼分清引用的是问题还是回答 */
.mention-ava.hist-ava-q { background: var(--app-accent-weak); color: var(--app-accent); font-weight: 600; }
.mention-ava.hist-ava-a { font-weight: 600; }
/* ==================== 深度思考设置（悬浮下拉模型行弹出面板卡，参考模型详情面板形态） ====================
   面板 Teleport 到 body 以 fixed 定位：位置按悬浮的模型行实时给定（thinkPanelPos），
   z-index 1060 高于 antd 下拉（1050），叠在下拉上方且不被裁剪 */
.think-float {
  position: fixed; z-index: 1060; padding: 14px 16px;
  max-height: calc(100vh - 16px); overflow-y: auto;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 14px;
  box-shadow: 0 10px 32px -8px rgba(16, 24, 40, .18);
}
/* 面板卡：标题/描述 + 「思考强度」标签值行（点开展开档位列表）+ 底部提示 */
.thinkp { width: 264px; }
.thinkp-title { font-size: 14px; font-weight: 600; color: var(--app-text); }
.thinkp-row-wrap { margin-top: 12px; border-top: 1px solid var(--app-border); }
.thinkp-row {
  display: flex; align-items: center; gap: 8px; width: 100%; padding: 10px 0;
  border: none; background: transparent; font-size: 13px; text-align: left;
}
.thinkp-row-label { color: var(--app-text); }
.thinkp-row-val { margin-left: auto; color: var(--app-text3); }
/* 思考强度：标签一行 + 档位豆一行（与触屏 sheet 同款形态，替掉旧的「点行展开列表」） */
.thinkp-sub { padding: 10px 0 0; font-size: 13px; color: var(--app-text); }
.thinkp-chips { display: flex; flex-wrap: wrap; gap: 5px; padding: 8px 0 10px; }
.thinkp-chip {
  padding: 3px 8px; border: 1px solid var(--app-border); border-radius: 999px;
  background: transparent; color: var(--app-text2); font-size: 12px; cursor: pointer;
}
.thinkp-chip:hover { background: var(--app-panel-2); }
.thinkp-chip.on {
  border-color: var(--app-accent-border); background: var(--app-accent-weak);
  color: var(--app-accent); font-weight: 500;
}
.thinkp-foot { margin-top: 2px; padding-top: 10px; border-top: 1px solid var(--app-border); font-size: 12px; line-height: 1.6; color: var(--app-text3); }
.toolbar-btn-on { color: var(--app-accent) !important; background: var(--app-accent-weak) !important; }
.model-name {
  margin-left: auto; font-size: 11px; color: var(--app-text3); margin-right: 8px; user-select: none;
  max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 智能体胶囊：ZCode 幽灵风格——平时只有灰字+小箭头，hover/展开才出现浅灰底 */
.agent-pill {
  display: inline-flex; align-items: center; gap: 5px; max-width: 260px; height: 28px;
  padding: 0 8px 0 12px; border-radius: 999px; border: none;
  background: transparent; color: var(--app-text3); font-size: 13px; font-weight: 400; cursor: pointer;
  transition: background .15s, color .15s;
}
.agent-pill:hover, .agent-pill.open { background: var(--app-panel-2); color: var(--app-text); }
.agent-pill.on .agent-pill-name { color: var(--app-text); }
.agent-pill-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-pill-caret { font-size: 12px; opacity: .55; flex: none; }
.agent-pill.locked { cursor: pointer; }
.agent-pill-lock { font-size: 11px; opacity: .6; flex: none; }
/* 回答归属：弱化呈现，不与正文抢注意力 */
.agent-tag {
  display: inline-flex; align-items: center; gap: 5px; margin-bottom: 6px;
  font-size: 12px; color: var(--app-text2);
  border: 1px solid var(--app-border); border-radius: 999px; padding: 2px 10px;
}
/* 徽标内的智能体头像：AgentAvatar 自带圆角块，这里只保证不被 flex 压扁 */
.agent-tag-ava { flex: none; }
/* 三条消息分隔线（压缩点 / 模型切换 / 流尾当场那条）共用的宽度约定：列宽收进**内边距**，
   不给这条 flex 项本身设 max-width——组件靠 flex-basis:100% 独占一行，而 max-width 会把
   100% 的基准宽压到 860，于是行内容器一旦宽过两倍列宽，分隔线就和消息块挤进同一条 flex 行：
   线探进左侧留白、气泡被推到右缘外、还被拉成整条消息那么高。
   收进 padding 后基准宽仍是满行（照旧独占一行），墨迹两端与正文列对齐。 */
.cdiv, .msd, .cn-tail { padding-inline: max(0px, calc((100% - var(--chat-col)) / 2)); }
/* 模型切换那条贴气泡更紧（组件默认 6px 上下等距偏松） */
.msd { margin-block: 2px 10px; }
/* 压缩那条在这条提问上方，上下都要跟前后两轮拉开 */
.cdiv { margin-block: 6px 12px; }

/* 智能体下拉面板（自绘：每项能放下描述与模型差异） */
.agent-menu {
  min-width: 340px; max-width: 420px; background: var(--app-panel);
  border: 1px solid var(--app-border); border-radius: 14px; padding: 6px;
  box-shadow: 0 10px 32px -8px rgba(16, 24, 40, .18);
}
.agent-menu-head { display: flex; align-items: baseline; gap: 8px; padding: 8px 10px 8px; }
.agent-menu-head > span:first-child { font-size: 12px; font-weight: 500; color: var(--app-text); }
.agent-menu-hint { font-size: 11px; color: var(--app-text3); }
.agent-menu-list { max-height: 320px; overflow-y: auto; }
.agent-mi { display: flex; align-items: flex-start; gap: 10px; padding: 8px 10px; border-radius: 10px; cursor: pointer; }
.agent-mi:hover { background: var(--app-panel-2); }
.agent-mi.active { background: var(--app-accent-weak); }
/* 头像徽标：给每行一个视觉锚点，选中态随主色 */
.agent-mi-ava {
  flex: none; width: 26px; height: 26px; border-radius: 8px; margin-top: 1px;
  display: inline-flex; align-items: center; justify-content: center; font-size: 13px;
  background: var(--app-panel-2); color: var(--app-text3);
  transition: background .15s, color .15s;
}
.agent-mi.active .agent-mi-ava { background: var(--app-accent-weak); color: var(--app-accent); }
.agent-mi-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.agent-mi-name {
  display: flex; align-items: center; gap: 6px; font-size: 13px; line-height: 20px; color: var(--app-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.agent-mi.active .agent-mi-name { color: var(--app-accent); font-weight: 500; }
.agent-mi-badge {
  font-size: 10px; line-height: 1; padding: 2px 5px; border-radius: 4px; font-weight: 400; flex: none;
  background: var(--app-ok-weak); color: var(--app-ok);
}
.agent-mi-desc {
  font-size: 11px; color: var(--app-text3); line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.agent-mi-check { color: var(--app-accent); font-size: 12px; flex: none; margin-top: 5px; }
.agent-mi-empty { padding: 14px 10px; font-size: 12px; color: var(--app-text3); text-align: center; }
.agent-menu-foot {
  display: flex; align-items: center; gap: 6px; margin-top: 4px; padding: 8px 9px;
  border-top: 1px solid var(--app-border); border-radius: 0 0 8px 8px;
  font-size: 12px; color: var(--app-accent); cursor: pointer;
}
.agent-menu-foot:hover { background: var(--app-accent-weak); }

/* 输入框「+」面板：分层菜单。一级=轻菜单（无页签无徽标），二级=原列表 + 头部返回键 + 搜索框
   （外壳与行式交互复用 .mention-panel / .mention-item）。
   .add-kbd = 一级菜单行尾的触发符提示（@ # /，把鼠标流和键盘流接到同一处）；
   .add-root-badge = 已选/生效数（实心主色，回答"这轮会带上什么"） */
.add-head { gap: 6px; }
.add-back { flex: none; }
.add-title { font-size: 13px; font-weight: 600; color: var(--app-text2); }
.add-close { margin-left: auto; }
.add-kbd {
  flex: none; font-size: 11px; line-height: 1; padding: 3px 7px; border-radius: 4px;
  border: 1px solid var(--app-border); background: var(--app-panel-2); color: var(--app-text3);
}
.add-root-badge {
  margin-left: 6px; font-size: 10px; line-height: 15px; padding: 0 5px; border-radius: 999px;
  background: var(--app-accent); color: #fff; font-weight: 400; vertical-align: 1px;
}
/* 引用页的子页签：比头部低一级（弱底小块），否则两行同样式的药丸看不出谁包着谁 */
.add-subtabs .mention-tab { background: var(--app-panel-2); }
.add-subtabs .mention-tab.on { background: var(--app-accent-weak); }
.add-filter-row { padding: 8px 10px 0; }
.add-filter {
  width: 100%; box-sizing: border-box; padding: 6px 10px;
  border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel-2); color: var(--app-text); font-size: 13px; outline: none;
}
.add-filter:focus { border-color: var(--app-accent-border, var(--app-accent)); }
.skill-ava { font-size: 12px; font-weight: 600; }
/* 技能行的来源与停用小标、正文入口（「+」面板的技能分页） */
.skill-flag { margin-left: 6px; padding: 3px 6px; border-radius: 999px; font-size: 11px; line-height: 1;
  background: var(--app-panel-2); color: var(--app-text3); }
.skill-flag.off { background: var(--app-danger-weak); color: var(--app-danger-text); }
.skill-doc-btn { flex: none; font-size: 12px; }

/* 固化为技能弹窗：字段竖排，标签行把「为什么填这个」说小字里，正文给等宽编辑区 */
.sf-field { margin-bottom: 12px; }
.sf-label { font-size: 12px; font-weight: 500; color: var(--app-text); margin-bottom: 5px; }
.sf-sub { margin-left: 8px; font-weight: 400; color: var(--app-text3); font-size: 11px; }
.sf-content { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; line-height: 1.6; }

/* 待发送附件条 + 技能选中标签 */
.pending-files { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 auto 8px; max-width: var(--chat-col); }
.pending-file.err { background: var(--app-danger-weak); border-color: var(--app-danger-border); color: var(--app-danger-text); }
/* 历史附件：原始文件已过保留期，只能看、不能随编辑重发——用中性灰弱化，
   不套用 err 的红色（那表示上传失败，是需要用户处理的事；这里是既成事实） */
.pending-file.expired { opacity: .6; }
.pending-file.expired .pending-file-ic { color: var(--app-text3); }
.pending-file {
  display: inline-flex; align-items: center; gap: 6px; max-width: 280px;
  padding: 5px 8px; border-radius: 8px; font-size: 12px;
  background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text);
}
.pending-file-ic { flex: none; color: var(--app-accent); font-size: 14px; }
.pending-file-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pending-file-size { flex: none; font-size: 11px; color: var(--app-text3); }
.pending-file-del {
  flex: none; cursor: pointer; font-size: 14px; line-height: 1; color: var(--app-text3);
  padding: 0 1px; border-radius: 4px;
}
.pending-file-del:hover { color: var(--app-danger); }
.skill-chip-ava {
  flex: none; width: 16px; height: 16px; border-radius: 4px; font-size: 10px; font-weight: 600;
  display: inline-flex; align-items: center; justify-content: center; background: rgba(255, 255, 255, .7);
}


.paste-view-bar { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.paste-view-meta { font-size: 12px; color: var(--app-text3); }


/* 右侧状态栏 */
.right-panel {
  width: 230px; flex: none; border-left: 1px solid var(--app-border); background: var(--app-panel-2);
  /* 底部预留：全局帮助 FAB 悬浮在视口右下角，让卡片不被压住 */
  padding: 12px 10px 56px; display: flex; flex-direction: column; gap: 10px; overflow-y: auto;
}
.rp-card { background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px 12px; }
.rp-label { font-size: 11px; color: var(--app-text3); margin-bottom: 4px; }
.rp-src { display: flex; align-items: center; gap: 6px; padding: 4px 0; cursor: pointer; }
/* 引用来源：按文档分组 + 片段可折叠（0fr→1fr 高度动画，与全站展开节奏一致） */
.rp-group { margin-bottom: 2px; }
.rp-group-head { display: flex; align-items: center; gap: 6px; padding: 4px 0; cursor: pointer; }
.rp-count { margin-left: auto; flex: none; font-size: 11px; color: var(--app-text3); }
.rp-group-body { display: grid; grid-template-rows: 0fr; transition: grid-template-rows .22s cubic-bezier(0.16, 1, 0.3, 1); }
.rp-group-body.open { grid-template-rows: 1fr; }
/* 网格行高动画的两个必要条件：唯一子容器 + min-height:0（否则子项按内容撑开，收不到 0 高） */
.rp-group-body-in { min-height: 0; overflow: hidden; }
.rp-src-sub { padding: 3px 0 3px 20px; font-size: 11.5px; color: var(--app-text2); }
@media (prefers-reduced-motion: reduce) { .rp-group-body, .tl-caret, .gb-toggle .anticon { transition: none; } }
.rp-src:hover .rp-src-name { color: var(--app-accent); }
.rp-src-ic { color: var(--app-accent); font-size: 12px; flex: none; }
.rp-src-name { font-size: 12px; color: var(--app-text2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 右栏产物卡 */
.rp-art { display: flex; align-items: center; gap: 6px; padding: 4px 0; }
/* 右栏交付包一行：名称 + 件数/体量 + 箭头；点开右侧交付抽屉（与卡片目标条同一份） */
.rp-dlv { display: flex; align-items: center; gap: 7px; width: 100%; padding: 6px 0; border: 0; background: transparent; text-align: left; cursor: pointer; }
.rp-dlv:hover .rp-dlv-name { color: var(--app-accent); }
.rp-dlv-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.rp-dlv-name { font-size: 12px; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rp-dlv-sub { font-size: 10.5px; color: var(--app-text3); }
.rp-dlv-go { flex: none; font-size: 10px; color: var(--app-text3); }
.rp-art:hover .rp-art-name { color: var(--app-accent); }
.rp-art-ic { color: var(--app-accent); font-size: 12px; flex: none; }
.rp-art-name { flex: 1; min-width: 0; font-size: 12px; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rp-art-act { flex: none; font-size: 11px; color: var(--app-text3); text-decoration: none; }
.rp-art-act:hover { color: var(--app-accent); }
.rp-art-all { margin-top: 4px; font-size: 11px; color: var(--app-accent); cursor: pointer; }
.rp-art-all:hover { text-decoration: underline; }
/* 沙盒文件卡：行内动作沿用 .rp-art-act 的字号与配色，只是那两枚是按钮不是链接（没有 href 可给） */
.rp-sb-btn { background: none; border: 0; padding: 0; cursor: pointer; line-height: 1; }
.rp-sb-hint { font-size: 11px; color: var(--app-text3); padding: 4px 0; }
.rp-sb-err { display: flex; align-items: center; gap: 8px; font-size: 11px; color: var(--app-warn-text); padding: 4px 0; }
.rp-sb-retry { background: none; border: 0; padding: 0; cursor: pointer; font-size: 11px; color: var(--app-accent); }
.rp-sb-retry:hover { text-decoration: underline; }
.rp-sb-taken { flex: none; font-size: 11px; color: var(--app-accent); }
/* 后台进程卡：标题行右侧挂「刷新」（与左对齐的标题分两头，才不显得那一排是两条标题） */
.rp-proc-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
/* 条数用主色徽标：这张卡只在真扫到东西（或扫失败）时才露脸，见 procCardVisible */
.rp-proc-n { display: inline-block; min-width: 16px; margin-left: 5px; padding: 0 5px; border-radius: 8px;
  text-align: center; background: var(--app-accent-weak); color: var(--app-accent); font-variant-numeric: tabular-nums; }
/* 徽标后面那个单位词：只留数字会读成「后台进程 2」这么个名字 */
.rp-proc-unit { margin-left: 5px; color: var(--app-text3); font-weight: 400; }
.rp-proc { padding: 6px 0; border-top: 1px solid var(--app-border); }
.rp-proc.is-killing { opacity: .55; }
/* 第一行只有命令行（它必须独占一行：与次要信息同行时，右栏一窄就被压成零宽，最该认得出的东西消失） */
.rp-proc-cmd { display: flex; align-items: center; gap: 6px; min-width: 0; }
.rp-proc-cmd-t { flex: 1; min-width: 0; font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 11px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 在跑的状态点：多行按整序错相呼吸，不许整列同步（与侧栏「生成中」同一口径） */
.rp-proc-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent);
  animation: proc-pulse 1.8s ease-in-out infinite; animation-delay: calc(var(--proc-i, 0) * -0.43s); }
@keyframes proc-pulse { 0%, 100% { opacity: .3 } 50% { opacity: 1 } }
.rp-proc.is-killing .rp-proc-dot { animation: none; background: var(--app-text3); }
/* 第二行：信息贴左（等宽数字，走秒时不抖行）、动作贴右 */
.rp-proc-foot { display: flex; align-items: center; gap: 8px; margin-top: 3px; }
/* 次要信息可换行：窄栏里 nowrap + 省略号会把「占着端口 8097」整段吃掉，
   而那正是用户判断「这是不是我要关的那个服务」的第二条线索 */
.rp-proc-meta { flex: 1; min-width: 0; font-size: 11px; color: var(--app-text3); line-height: 16px;
  font-variant-numeric: tabular-nums; }
.rp-proc-kill { flex: none; background: none; border: 1px solid var(--app-border); border-radius: 4px;
  padding: 1px 9px; font-size: 11px; color: var(--app-text2); cursor: pointer; line-height: 18px; }
.rp-proc-kill:hover:not(:disabled) { color: var(--app-warn-text); border-color: var(--app-warn-text); }
.rp-proc-kill:disabled { opacity: .7; cursor: default; }
.rp-proc-unknown { flex: none; font-size: 11px; color: var(--app-text3); }
@media (prefers-reduced-motion: reduce) { .rp-proc-dot { animation: none; } }
/* 目录行：整行是 flex，开合钮只占名字那一段（行尾还要放「在电脑上打开」），计数与合计体积贴右。
   button 要自带重置，antd reset 不清底色边框 */
.rp-sb-dir { display: flex; align-items: center; gap: 6px; width: 100%; }
.rp-sb-dir-main { display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0; padding: 4px 0;
  background: none; border: 0; cursor: pointer; font: inherit; text-align: left; }
.rp-sb-dir:hover .rp-sb-name { color: var(--app-accent); }
.rp-sb-name { flex: 1; min-width: 0; font-size: 12px; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rp-sb-meta { flex: none; font-size: 10.5px; color: var(--app-text3); }
/* 层级向导线：一层一格（12px），线画在这一格左缘＝上级那个箭头的列上。
   上下各外扩 4px 盖住行内边距，逐行连成一条——只靠缩进时「深一段后突然浅一行」读不出归谁，线在就一眼看出。 */
.rp-sb-ind { flex: none; width: 12px; align-self: stretch; margin: -4px -6px -4px 0; border-left: 1px solid var(--app-border); }

/* 右栏计划卡：本会话出过的计划列表（问题为标签 + 版本/步数 + 状态角标；点击打开完整计划抽屉） */
.rp-plan-row {
  display: flex; align-items: center; gap: 6px; width: 100%; text-align: left;
  padding: 4px 6px; margin: 0 -6px; border: none; background: none; border-radius: 6px;
  font-size: 12px; color: var(--app-text2); cursor: pointer;
}
.rp-plan-row:hover { background: var(--app-panel-2); }
.rp-plan-row:hover .rp-plan-t { color: var(--app-accent); }
.rp-plan-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--app-text3); }
.rp-plan-dot.is-pending { background: var(--app-warn); }
.rp-plan-dot.is-approved { background: var(--app-ok); }
.rp-plan-t { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rp-plan-st { flex: none; font-size: 10.5px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.rp-plan-st.is-pending { color: var(--app-warn-text); font-weight: 600; }
.rp-plan-st.is-approved { color: var(--app-ok); }
.rp-plan-note { padding: 7px 10px 9px; font-size: 10.5px; line-height: 1.6; color: var(--app-text3); }
/* 定位闪烁：跳过去的那一轮先亮一下（与引用角标 ref-flash 同一手法） */
.row.plan-jump { animation: planJumpFlash 1.5s ease; }
@keyframes planJumpFlash { 0% { background: var(--app-accent-weak); } 100% { background: transparent; } }

/* 卡头：引用来源与计划两张卡的折叠头（button 需要自带重置，antd reset 不清 button 底色边框） */
.rp-card-head { display: flex; align-items: center; gap: 6px; width: 100%; background: none; border: 0; padding: 0; text-align: left; cursor: pointer; font: inherit; }
/* 卡头里的 label 一律清掉自带的 margin-bottom（它是给卡片静态头用的），
   否则文字块带下边距参与 flex 居中，旁边的箭头会对不齐 */
.rp-card-head .rp-label { margin-bottom: 0; }
/* 箭头统一起到卡片最右（与「过程」卡 rpc-caret 的 margin-left:auto 同一口径），
   不然折叠头收起时箭头贴着文案，两张卡并排看形态对不上 */
.rp-card-head .tl-caret { margin-left: auto; }
/* 计划卡头：整行可点开合，摘要徽标在中间，箭头在尾 */
.rp-plan-head { width: 100%; background: none; border: 0; padding: 0; text-align: left; cursor: pointer; font: inherit; }
.rp-plan-head:hover .rp-label-h { color: var(--app-text2); }
.rp-plan-badge {
  flex: 1; min-width: 0; font-size: 11px; color: var(--app-text3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: right;
}
.rp-plan-badge.is-pending { color: var(--app-warn-text); font-weight: 500; }

/* 引用相关度 + 角标联动（右栏 ↔ 正文） */
/* 行首角标号＝「在正文里定位」那颗按钮：加宽命中区但不占版面（负 margin 抵掉自身内缩），
   悬停才变主色带下划线——平时它得跟正文角标一个长相，不能先抢了整行的视觉 */
.rp-src-ref { flex: none; font-size: 11px; color: var(--app-text3); cursor: pointer;
  padding: 2px 4px; margin: -2px -4px -2px 0; border-radius: 4px; }
.rp-src-ref:hover { color: var(--app-accent); text-decoration: underline; background: var(--app-accent-weak); }
.rp-src-mine {
  flex: none; font-size: 10px; line-height: 1.5; padding: 0 4px; border-radius: 4px;
  background: var(--app-warn-weak); color: var(--app-warn-text);
}
.rp-src-score { flex: none; font-size: 11px; color: var(--app-accent); }
.rp-src-sub .rp-src-name { flex: 1; min-width: 0; }
.rp-src-sub.hl { background: var(--app-accent-weak); border-radius: 4px; }

/* 检索调试面板 */
.dbg-item { padding: 6px 8px; margin-bottom: 6px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-panel-2); }
.dbg-terms { padding: 8px 10px; margin-bottom: 10px; border: 1px solid var(--app-info-border); border-radius: 6px; background: var(--app-info-weak); }
.dbg-terms-label { font-size: 12px; color: var(--app-text3); margin-right: 6px; }
.dbg-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.dbg-title { font-weight: 500; font-size: 13px; }
.dbg-snippet { margin-top: 3px; font-size: 12px; color: var(--app-text3); word-break: break-all; }

/* 工具执行审批（人在回路）：贴着被审批的那一步，通知深链也落在这里 */
.approval-count { margin-top: 8px; font-size: 12px; color: var(--app-warn-text); }
.approval-count b { font-weight: 700; }
.approval-src { flex: 0 0 auto; font-size: 10.5px; color: var(--app-text2); background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 5px; padding: 1px 6px; white-space: nowrap; }
.approval-card { margin-top: 8px; border: 1px solid var(--app-warn-border); border-left: 3px solid #dda23f; background: var(--app-warn-weak); border-radius: 8px; padding: 10px 12px; max-width: 640px; }
.approval-card.flash { animation: approvalFlash 1.6s ease-out; }
@keyframes approvalFlash {
  0% { box-shadow: 0 0 0 0 rgba(199, 124, 20, .42); }
  28% { box-shadow: 0 0 0 5px rgba(199, 124, 20, .18); }
  100% { box-shadow: 0 0 0 0 rgba(199, 124, 20, 0); }
}
.approval-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.approval-warn { color: #b07818; flex: 0 0 auto; }
.approval-lead { font-size: 13px; font-weight: 600; color: var(--app-warn-text); white-space: nowrap; }
.approval-tool { font-size: 13px; font-weight: 600; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.approval-raw { font-size: 11px; color: var(--app-text3); font-family: "SF Mono", Menlo, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 34%; }
.approval-clock { flex: 0 0 auto; margin-left: auto; display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px; color: var(--app-warn-text); background: var(--app-panel); border: 1px solid var(--app-warn-border); border-radius: 5px; padding: 1px 7px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.approval-clock.over { color: var(--app-text3); background: var(--app-panel-2); border-color: var(--app-border); }
/* 「为什么问」：读得懂的一句话，弱化到参数块之上，不和命令正文抢视线 */
.approval-why { margin: 8px 0 0; font-size: 12px; line-height: 1.6; color: var(--app-warn-text); }
.approval-args { margin: 8px 0 0; background: var(--app-panel); border: 1px solid var(--app-warn-border); border-radius: 6px; padding: 8px; font-size: 12px; font-family: "SF Mono", Menlo, monospace; white-space: pre-wrap; word-break: break-all; max-height: 140px; overflow-y: auto; }
.approval-why + .approval-args { margin-top: 6px; }
.approval-actions { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.approval-hint { font-size: 12px; color: var(--app-text3); margin-right: auto; }
/* 「批准执行」+ 下拉箭头：两种按钮拼成一颗，主键仍是默认动作（只批这一步） */
.approval-approve-split { display: inline-flex; align-items: stretch; }
.approval-approve-split > .app-btn:first-child { border-radius: 8px 0 0 8px; }
.approval-approve-split .app-btn.approval-caret { border-radius: 0 8px 8px 0; padding: 6px 6px; border-left: 1px solid rgba(255, 255, 255, .32); }
.approval-approve-split .app-btn.approval-caret:disabled { border-left-color: rgba(255, 255, 255, .18); }

/* ==================== 计划模式：执行计划卡 ==================== */
/* 卡片样式随组件走（两端共用），见 chat/PlanCard.vue；这里只留页面内的计划相关角标 */
.plan-chip { border-color: var(--app-warn-border); background: var(--app-warn-weak); }

/* askUser 问答记录由共用组件 AskRecordCard 承载（自带折叠头与样式） */

/* ==================== 智能体提问面板（替换聊天输入框） ====================
   挂起提问时整块顶替 composer：编号选项（关键词加粗 + 说明弱化、首项带「推荐」）、
   自定义输入作末项、底部键盘提示 + 忽略/提交。键盘高亮用 .sel（Tab/↑↓ 移动）。 */
.askp-box { padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
.askp-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.askp-tag { flex: none; font-size: 12px; line-height: 1; padding: 5px 9px; border-radius: 999px; background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text2); }
/* 题面是唯一可伸缩项（basis 0 + grow）：题干长时在自己的盒内折行，倒计时/状态徽标/翻页器
   永远钉在首行右侧——否则长题干占满首行，margin-left:auto 会把倒计时单独挤到第二行，
   右对齐孤零零一行。短题干时仍与徽标同一行，观感不变。 */
.askp-q { flex: 1 1 0; min-width: 0; font-size: 13.5px; font-weight: 600; color: var(--app-text); }
/* 「可多选」= 这一题可以勾不止一个候选（不是「这张卡有几个问句」，那由翻页 ‹1/2› 说）。
   画在题面旁边，用户勾第二个之前就得知道勾第一个不会被顶掉 */
.askp-multi { flex: none; font-size: 11px; line-height: 1.5; padding: 1px 6px; border-radius: 999px; background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text2); }
/* 多选行的勾选框：纯 CSS 画勾（不引图标组件、不靠字体里的 ✓），未勾=空框，勾上=底色加白勾 */
.askp-tick { flex: none; width: 14px; height: 14px; margin-top: 3px; border: 1.5px solid var(--app-border); border-radius: 3px; background: var(--app-panel); display: inline-flex; align-items: center; justify-content: center; box-sizing: border-box; }
.askp-tick.on { background: var(--app-accent, var(--app-ok)); border-color: var(--app-accent, var(--app-ok)); }
.askp-tick.on::after { content: ''; display: block; width: 7px; height: 3px; margin-top: -2px; border-left: 2px solid #fff; border-bottom: 2px solid #fff; transform: rotate(-45deg); }
.askp-custom-row .askp-tick { margin-top: 2px; }
.askp-state.ok { font-size: 12px; color: var(--app-ok); }
.askp-state.warn { font-size: 12px; color: var(--app-warn); }
.askp-timer { flex: none; display: inline-flex; align-items: center; gap: 4px; margin-left: auto; font-size: 12px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.askp-timer.warn { color: var(--app-warn); }
.askp-pager { flex: none; margin-left: 8px; display: inline-flex; align-items: center; gap: 2px; }
.askp-page-btn { width: 22px; height: 22px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--app-border); background: var(--app-panel-2); color: var(--app-text2); border-radius: 6px; cursor: pointer; font-size: 14px; line-height: 1; padding: 0; }
.askp-page-btn:hover:not(:disabled) { color: var(--app-text); border-color: var(--app-accent, var(--app-ok)); }
.askp-page-btn:disabled { opacity: 0.4; cursor: default; }
.askp-page-num { min-width: 34px; text-align: center; font-size: 12px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
.askp-opts { display: flex; flex-direction: column; gap: 2px; }
.askp-opt { display: flex; align-items: flex-start; gap: 8px; width: 100%; text-align: left; background: none; border: none; border-radius: 6px; padding: 7px 8px; font-size: 13px; line-height: 1.55; color: var(--app-text); cursor: pointer; }
.askp-opt:hover { background: var(--app-panel-2); }
.askp-opt.sel { background: var(--app-panel-2); box-shadow: inset 2px 0 0 var(--app-accent, var(--app-ok)); }
.askp-opt.k-hi { background: var(--app-panel-2); }
.askp-opt:disabled { opacity: 0.55; cursor: default; }
.askp-no { flex: none; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.askp-kw { font-weight: 600; }
.askp-rec { font-weight: 600; color: var(--app-ok); }
.askp-rest { color: var(--app-text2); margin-left: 8px; flex: 1; min-width: 0; }
.askp-custom-row { cursor: text; }
.askp-input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; font-size: 13px; color: var(--app-text); padding: 0; }
.askp-input::placeholder { color: var(--app-text3); }
.askp-foot { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.askp-hint { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text3); }
.askp-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }

/* ==================== 计划确认栏（替换聊天输入框） ====================
   版式对齐 plan-mode-ux：头部（执行计划 + 版本 + 状态 + 倒计时）→ 输入行（写意见 + 两个主按钮）→
   脚注（同轮升版说明 + 退出计划 / 转到对话修改 / 停止本轮）。
   计划正文在气泡里只读展示，裁决收在这一条常驻栏里，读与批不再分两段滚动。 */
.planp-box { padding: 11px 13px; display: flex; flex-direction: column; gap: 8px; border-color: var(--app-warn-border); background: var(--app-warn-weak); }
.planp-head { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
.planp-tag { flex: none; font-size: 10.5px; line-height: 1; padding: 4px 8px; border-radius: 999px; background: var(--app-panel); border: 1px solid var(--app-warn-border); color: var(--app-warn-text); }
.planp-ver { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 9.5px; line-height: 16px; padding: 0 5px; border-radius: 999px; border: 1px solid var(--app-warn-border); background: var(--app-panel); color: var(--app-warn-text); }
.planp-state { font-size: 11.5px; font-weight: 600; color: var(--app-warn-text); }
.planp-state.warn { color: var(--app-warn); }
.planp-timer { flex: none; display: inline-flex; align-items: center; gap: 4px; margin-left: auto; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10.5px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
.planp-timer.warn { color: var(--app-warn); }
.planp-row { display: flex; align-items: flex-end; gap: 7px; }
.planp-input.ant-input { flex: 1; min-width: 0; font-size: 12.5px; line-height: 1.6; padding: 5px 9px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); resize: none; }
.planp-input.ant-input:focus { border-color: var(--app-accent); box-shadow: 0 0 0 2px var(--app-accent-weak); }
.planp-input.ant-input:disabled { background: var(--app-panel-2); color: var(--app-text3); }
.planp-btn { flex: none; height: 30px; padding: 0 12px; border-radius: 7px; border: 1px solid var(--app-border); background: var(--app-panel); font-size: 12px; color: var(--app-text2); white-space: nowrap; cursor: pointer; }
.planp-btn.warn { color: var(--app-warn-text); border-color: var(--app-warn-border); background: var(--app-panel); font-weight: 600; }
.planp-btn.primary { color: #fff; border-color: var(--app-accent); background: var(--app-accent); font-weight: 600; }
.planp-btn:hover:not(:disabled) { filter: brightness(.97); }
.planp-btn:disabled { opacity: .5; cursor: default; }
.planp-foot { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.planp-hint { font-size: 10.5px; line-height: 1.6; color: var(--app-text3); }
.planp-links { margin-left: auto; display: inline-flex; align-items: center; gap: 4px; }
.planp-link { border: 0; background: none; padding: 0 4px; font-size: 10.5px; color: var(--app-warn-text); cursor: pointer; }
.planp-link.quiet { color: var(--app-text3); }
.planp-link:hover:not(:disabled) { text-decoration: underline; }
.planp-link:disabled { opacity: .5; cursor: default; }
/* 面板内的「停止本轮」（提问面板共用）：与发送位停止键同一危险弱化口径，但做成静默描边——
   面板的主行动是「回答 / 批准」，停止是退出路径，不该抢视觉焦点 */
.p-stop { color: var(--app-danger-text); border-color: var(--app-danger-border); }
.p-stop:hover:not(:disabled) { background: var(--app-danger-weak); border-color: var(--app-danger-border); }
@media (max-width: 640px) {
  .planp-row { flex-wrap: wrap; }
  .planp-input.ant-input { flex: 1 1 100%; }
  .planp-btn { flex: 1; }
  .planp-links { margin-left: 0; }
}
/* ==================== 目标模式 ==================== */
/* 目标条与任务清单同一族：独立成一张卡浮在输入卡片正上方（--chat-col 同宽同基准、圆角 14px）。
   版式对齐 goal-mode-ux：身份 / 进度 / 动作三段。
   ① 身份：深度 pill、原文（最多两行）、版本、状态胶囊；
   ② 进度：轮次、已跑时长、预算细条与百分比、停因/让位提示、过程入口；
   ③ 动作：取消与修改在左，主行动（暂停/继续/立即继续）靠右。
   token、详细预算只进详情抽屉；就地编辑整段长开，带版本轨与生效时点。 */
.goal-bar {
  box-sizing: border-box; max-width: var(--chat-col);
  margin: 0 auto 8px; padding: 10px 12px 11px;
  display: flex; flex-direction: column; gap: 8px;
  min-width: 0;
  border: 1px solid var(--app-border); border-radius: 14px;
  background: var(--app-panel); box-shadow: var(--app-shadow-sm);
}
.gb-row { display: flex; align-items: center; gap: 8px; min-width: 0; }
.goal-bar.editing .gb-row { align-items: flex-start; }
/* 目标 pill：身份锚点；编辑态保留，说明这条展开的是「深度执行的那条目标」 */
.gb-pill {
  flex: none; display: inline-flex; align-items: center; gap: 4px;
  font-size: 11.5px; line-height: 20px; padding: 0 8px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent); white-space: nowrap;
  border: 1px solid var(--app-accent-border);
}
.gb-pill .anticon { font-size: 12px; }
/* 原文最多两行；编辑态收成一行（下面是文本域，重复铺两遍原文没有信息量） */
.gb-objective {
  flex: 1; min-width: 0; overflow: hidden;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  font-size: 13px; font-weight: 600; line-height: 1.45; color: var(--app-text);
}
.gb-objective.one { display: block; white-space: nowrap; text-overflow: ellipsis; }
/* 版本：改过目标原文就换一版；只报第几版，不抢原文与状态的注意力 */
.gb-ver {
  flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 9.5px; line-height: 18px; padding: 0 5px;
  border-radius: 4px; border: 1px solid var(--app-border); color: var(--app-text3); white-space: nowrap;
}
/* 只读状态胶囊：色点 + 词；进行中的点轻呼吸，静态色点分不出「在跑」和「挂着」 */
.gb-state { flex: none; display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; line-height: 20px; padding: 0 8px; border-radius: 999px; color: var(--app-text3); white-space: nowrap; border: 1px solid transparent; }
.gb-state::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: currentColor; flex: none; }
.gb-state.st-running { color: var(--app-accent); background: var(--app-accent-weak); border-color: var(--app-accent-border); }
.gb-state.st-running::before { animation: gb-breathe 1.8s ease-in-out infinite; }
@keyframes gb-breathe { 0%, 100% { opacity: 1; } 50% { opacity: .3; } }
.gb-state.st-paused { color: var(--app-warn-text); background: var(--app-warn-weak); border-color: var(--app-warn-border); }
.gb-state.st-achieved { color: var(--app-success, #067647); background: var(--app-success-weak, #ecfdf3); border-color: var(--app-ok-border, #cfe4d3); }
.gb-state.st-failed { color: var(--app-danger); background: var(--app-danger-weak); border-color: var(--app-danger-border, #efc8c0); }
.gb-state.st-cancelled { color: var(--app-text3); background: var(--app-panel-2); border-color: var(--app-border); }
/* 折叠键：行尾一枚 10px 三角，展开时转 90°（与 .tl-caret 同一口径）；
   热区放到 22px 见方，10px 字形直接当按钮点不准 */
.gb-fold {
  flex: none; display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; padding: 0;
  border: none; border-radius: 6px; background: transparent; color: var(--app-text3); cursor: pointer;
}
.gb-fold .anticon { font-size: 10px; line-height: 1; transition: transform .15s; }
.gb-fold.open .anticon { transform: rotate(90deg); }
.gb-fold:hover { background: var(--app-panel-2); color: var(--app-text2); }
/* 进度行 */
.gb-progress { color: var(--app-text3); flex-wrap: wrap; }
.gb-round { flex: none; font-size: 11px; line-height: 18px; padding: 0 6px; border-radius: 6px; border: 1px solid var(--app-border); color: var(--app-text2); white-space: nowrap; font-variant-numeric: tabular-nums; }
.gb-meta { flex: none; font-size: 11px; line-height: 18px; color: var(--app-text3); white-space: nowrap; font-variant-numeric: tabular-nums; }
.gb-budget { flex: none; display: inline-flex; align-items: center; gap: 6px; font-size: 10.5px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.gb-track { flex: none; width: 64px; height: 4px; border-radius: 999px; background: var(--app-border); overflow: hidden; }
.gb-track i { display: block; height: 100%; border-radius: 999px; background: var(--app-accent); transition: width .25s ease; }
.gb-hint { flex: 1; min-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; line-height: 18px; color: var(--app-text3); }
.gb-more-link { flex: none; padding: 0; border: none; background: transparent; font-size: 11.5px; line-height: 18px; color: var(--app-accent); cursor: pointer; white-space: nowrap; }
.gb-more-link:hover { text-decoration: underline; }
/* 动作行：取消与修改在左，主行动靠右 */
.gb-actions-row { gap: 6px; }
.gb-spacer { flex: 1; }
.gb-btn {
  height: 28px; display: inline-flex; align-items: center; gap: 5px; padding: 0 11px;
  border: 1px solid transparent; border-radius: 7px;
  font-size: 11.5px; line-height: 1; cursor: pointer; white-space: nowrap;
}
.gb-btn.primary { background: var(--app-accent); color: #fff; font-weight: 600; }
.gb-btn.primary:hover:not(:disabled) { background: var(--app-accent-hover); }
/* 禁用态照发送键那条口径：浅蓝底压白字对比度太低，看着像按钮坏了 —— 改灰底灰字让它「退后」 */
.gb-btn.primary:disabled { background: var(--app-border); color: var(--app-text3); cursor: default; }
.gb-btn.ghost { background: transparent; color: var(--app-text2); border-color: var(--app-border); }
.gb-btn.ghost:hover:not(:disabled) { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); }
.gb-btn.ghost:disabled { opacity: .55; cursor: default; }
.gb-btn.text-danger { background: transparent; color: var(--app-text3); border-color: transparent; padding: 0 4px; }
.gb-btn.text-danger:hover:not(:disabled) { color: var(--app-danger); background: var(--app-danger-weak); }
.gb-btn.danger { background: var(--app-danger); color: #fff; }
.gb-btn.danger:hover:not(:disabled) { filter: brightness(.94); }
/* 取消二次确认：就地长开（不弹层），标题带警示图标，正文说清会留下什么 */
.gb-confirm {
  display: flex; flex-direction: column; gap: 6px;
  padding: 10px 12px; border-radius: 10px;
  background: var(--app-danger-weak); border: 1px solid var(--app-danger-border, var(--app-border));
}
.gb-confirm-t { display: flex; align-items: center; gap: 6px; font-size: 12.5px; line-height: 18px; font-weight: 600; color: var(--app-text); }
.gb-confirm-t .anticon { color: var(--app-danger); }
.gb-confirm-s { font-size: 11.5px; line-height: 1.65; color: var(--app-text2); }
.gb-confirm-s b { color: var(--app-text); }
.gb-confirm-actions { display: flex; justify-content: flex-end; gap: 8px; }
/* 就地编辑：整段长开，文本框 + 版本轨 + 生效时点 + 保存/取消 */
.gb-edit { display: flex; flex-direction: column; gap: 8px; padding: 10px 12px; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel-2); }
.gb-edit-textarea.ant-input { font-size: 13px; line-height: 1.6; padding: 0; min-height: 0; background: transparent; color: var(--app-text); resize: none; }
.gb-edit-textarea.ant-input::placeholder { color: var(--app-text3); opacity: 1; }
.gb-edit-hint { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 10.5px; line-height: 1.6; color: var(--app-text3); }
.gb-edit-hint b { color: var(--app-accent); font-weight: 600; }
.gb-edit-hint .warn { color: var(--app-warn-text); }
.gb-vrail { display: inline-flex; align-items: center; gap: 3px; }
.gb-vrail span { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 9.5px; padding: 1px 5px; border-radius: 4px; border: 1px solid var(--app-border); color: var(--app-text3); }
.gb-vrail span.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 700; }
.gb-vrail .arr { border: none; color: var(--app-text3); }
.gb-edit-actions-row { display: flex; align-items: center; gap: 8px; }
.gb-error { font-size: 12px; line-height: 18px; color: var(--app-danger); word-break: break-word; }

/* 窄屏：状态词让位给原文与动作键（触屏点右栏看状态） */
@media (max-width: 768px) {
  .gb-row { gap: 6px; }
  .gb-state, .gb-ver, .gb-round { display: none; }
  .gb-track { width: 56px; }
}

/* ==================== 响应式：窄屏适配 ====================
   ≤1200：状态栏由占位列改浮层（消息是主内容，需要时点「状态」按钮展开）。
   ≤768：手机窄屏 —— 收内边距（32px 在 375px 屏上占 17% 宽）、放大触控热区、
        键盘避让、把 hover 浮层改底部 sheet。 */
@media (max-width: 1200px) {
  /* 状态栏让位给消息区（消息是主内容；需要时用户可点「状态」按钮，面板改浮层由按钮控制） */
  .right-panel { position: absolute; right: 12px; top: 56px; bottom: 12px; z-index: 30;
    width: 260px; border: 1px solid var(--app-border); border-radius: var(--app-radius);
    box-shadow: var(--app-shadow-lg); background: var(--app-panel); }
  /* 窄屏输入卡片会贴到右缘，为右下角全局帮助 FAB 让出角落（否则压住发送键一侧）。
     让位量交给输入区组件叠加到它自己的右内边距上（--ci-right-extra），
     而不是在这里覆盖 padding-right —— 卡片与拖拽框的几何现在只有组件那一份。 */
  .chat-col { --ci-right-extra: 56px; }
}
@media (max-width: 768px) {
  /* 内边距从 32px 收到 10px，与 .chat-head 的横向节奏一致 */
  .messages { padding: 10px 10px 6px; overscroll-behavior-y: contain; }
  .chat-head { padding: 6px 10px; padding-top: calc(6px + var(--sat, 0px)); gap: 6px; }
  .chat-title { max-width: 42%; }
  .head-tip { display: none; }
  .welcome { padding: 40px 12px 24px; }
  /* 右栏浮层在窄屏近乎全屏：改成真正的底部 sheet 形态（圆角在上、留出底部安全区）。
     用 left/right 归零 + bottom:0 覆盖 1200 块给的 right:12px/top:56px 定位 */
  .right-panel.as-sheet {
    top: auto; left: 0; right: 0; bottom: 0; width: auto;
    max-height: 76dvh; border-radius: 16px 16px 0 0;
    padding-bottom: var(--sab, 0px);
    animation: sheet-up .22s cubic-bezier(.16, 1, .3, 1);
  }
  /* @ 引用 / 斜杠命令 / 历史引用三个面板共用 .mention-panel。
     原为 bottom:calc(100% + 8px) 向上弹 —— 键盘弹起后输入框上方空间常小于面板高（≈380px），
     会直接溢出屏幕。窄屏一律改底部 sheet（面板本身无 fixed 后代，用 fixed 安全） */
  .mention-panel {
    position: fixed; left: 0; right: 0; bottom: 0; top: auto;
    max-height: 72dvh; border-radius: 16px 16px 0 0;
    padding-bottom: calc(var(--sab, 0px) + var(--kb, 0px));
    animation: sheet-up .22s cubic-bezier(.16, 1, .3, 1);
  }
  .mention-list { max-height: 42dvh; }  /* 原 264px 固定值：矮屏不够用，改视口比例 */
  .mention-item { padding: 10px 8px; }   /* 触控热区 */
  .mention-foot { display: none; }       /* 窄屏省一条说明文字，省 30px 高度 */
  @keyframes sheet-up { from { transform: translateY(100%); } to { transform: none; } }

  /* ---- 工具条窄屏形态：整条不换行 ----
     症状：模型名 deepseek-flash 把「思考」按钮压成竖排两字。
     成因：工具条是 flex 但没设 nowrap，子项宽度总和超过容器时浏览器换行，
     而按钮内的文字被压窄后按字符断行。
     修法：nowrap 已内建在 chat/ChatInput.vue 的 ≤768 块里（工具条本身归它）。
     这里只补 PC 态没有的收窄项：智能体胶囊只留头像、模型 compact。 */
  /* 智能体胶囊窄屏只留头像：固定 34×34 圆形，触控热区达标且不占横向空间 */
  .agent-pill.as-icon { width: 34px; height: 34px; padding: 0; justify-content: center; border-radius: 50%; }
  .agent-pill.as-icon .agent-pill-lock { display: none; }  /* 锁标记在窄屏无空间表达，靠下拉里的文案 */
  /* 思考按钮不折行：nowrap 是必须的，否则「思考」两字会被压成上下两行 */
  .think-entry-txt { white-space: nowrap; }
}

/* ==================== 触屏（hover:none）专属 ====================
   判据用「能否 hover」而非宽度：iPad 窄屏分屏 <768px 但有鼠标 hover，桌面窗口缩到
   375px 仍 hover:hover —— 只有这条媒体查询能准确区分「手指操作」与「鼠标操作」。 */
@media (hover: none) {
  /* 引用角标悬浮卡：hover 专属，触屏永不可达。角标本身可点（openPreview → openSource），
     所以直接物理隐藏，少一层交互 */
  .ref-card { display: none; }
  /* 深度思考面板 / 容量卡：桌面是 fixed 贴边浮层（靠 JS getBoundingClientRect 定位），
     触屏改底部 sheet —— 贴边浮层在窄屏会盖住输入区，且其 JS 定位分支仍按宽屏算 */
  .think-float, .ctxcap-float {
    top: auto !important; left: 0 !important; bottom: 0 !important;
    width: auto; max-width: none; max-height: 62dvh; overflow-y: auto;
    border-radius: 16px 16px 0 0; z-index: 1060;
    padding-bottom: var(--sab, 0px);
    animation: sheet-up .22s cubic-bezier(.16, 1, .3, 1);
  }
  /* 灯箱提示文案：滚轮缩放/拖动平移/双击重置/ESC 全是桌面操作，触屏只支持点空白关闭 */
  .lightbox-tip { display: none; }
  /* 触屏热区下限 34px（触控设计规范建议 44px，但工具条空间有限，34 是可点与不挤的折中） */
  .ctx-ring { min-width: 34px; min-height: 34px; }
  .think-entry {
    display: inline-flex; align-items: center; gap: 4px;
    min-height: 34px; padding: 0 10px; cursor: pointer;
    border: 1px solid var(--app-border); border-radius: 999px;
    background: var(--app-panel); color: var(--app-text2); font-size: 12px;
    touch-action: manipulation;
  }
  .think-entry.on { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }
}
/* 宽度不作为 .think-entry 的显示条件 —— 显示与否只由 v-if="isCoarse" 决定。
   判据必须只有 isCoarse 一个：在 Surface 之类「(pointer:coarse) 与 (hover:hover) 同时成立」的设备上，
   若再用 CSS 按 hover 隐藏，按钮会被渲染出来又被藏掉，而 hover 通路已被 onModelOptionHover
   的 isCoarse 早退堵死 —— 深度思考入口会彻底消失。 */
/* 工作区未准备好的提示：一行、可操作，不占对话区；没有目录时给「去绑定」，离线时给「重新检查」 */
.local-ws-hint { display: flex; width: fit-content; max-width: min(100%, 620px);
  align-items: center; justify-content: center; gap: 8px; margin: 0 auto 8px;
  padding: 6px 10px; border: 1px solid #f0c36d; border-radius: 8px;
  background: #fffbe6; color: #8a5a00; font-size: 12px; line-height: 1.5; }
.local-ws-hint .lwh-ic { flex: none; font-size: 14px; color: #d48806; }
.local-ws-hint .lwh-text { flex: 1; min-width: 0; }
.local-ws-hint .lwh-act { flex: none; border: 1px solid #d48806; border-radius: 6px;
  background: #fff; color: #ad6800; font-size: 12px; padding: 2px 8px; cursor: pointer; }
.local-ws-hint .lwh-act:hover:not(:disabled) { background: #fff7e6; }
.local-ws-hint .lwh-act:disabled { opacity: .6; cursor: default; }

/* 会话级权限药丸：坐在工具条最左，与智能体药丸同形但语义不同（一个换谁答，一个换能干什么） */
.perm-pill { display: inline-flex; align-items: center; gap: 5px; height: 30px; padding: 0 9px;
  border: 1px solid var(--app-border); border-radius: 999px; background: var(--app-surface);
  color: var(--app-text2); font-size: 12px; cursor: pointer; white-space: nowrap; }
.perm-pill:hover { border-color: var(--app-accent-border); color: var(--app-text); }
.perm-pill.on { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.perm-pill.danger { color: #d4380d; border-color: #ff7875; background: #fff1f0; }
.perm-pill.danger:hover { color: #a8071a; border-color: #ff4d4f; }
.danger-confirm { display: flex; flex-direction: column; gap: 14px; }
.danger-lead { display: flex; gap: 8px; margin: 0; padding: 10px 12px; border-radius: 8px;
  background: #fff7e6; color: var(--app-text); font-size: 13px; line-height: 1.75; }
.danger-ic { color: #fa8c16; margin-top: 3px; flex: none; }
.danger-actions { display: flex; justify-content: flex-end; gap: 10px; }
.perm-menu { max-width: 320px; }
.perm-mi-t { font-size: 13px; color: var(--app-text); line-height: 1.5; }
.perm-mi-d { font-size: 12px; color: var(--app-text3); line-height: 1.5; white-space: normal; }

/* 无障碍：系统开启「减少动态效果」时不播 sheet 上滑 */
@media (prefers-reduced-motion: reduce) {
  .right-panel.as-sheet, .mention-panel, .think-float, .ctxcap-float { animation: none; }
}

/* H5 P2：≤1024 触屏/窄桌面补齐聊天壳内共用控件热区（项目页内嵌同源） */
@media (max-width: 1024px) {
  .ci-attach, .ci-send, .head-quiet-btn { min-width: 44px; min-height: 44px; }
}
</style>
