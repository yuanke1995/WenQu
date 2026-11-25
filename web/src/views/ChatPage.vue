<template>
  <div ref="chat2Ref" class="chat2" @mouseenter="railVis = true" @mouseleave="railVis = false; railHoverIdx = -1">
    <!-- 中间：标题栏 + 消息流 + 输入区 -->
    <div class="chat-col">
      <!-- 团队成员会话：宽屏并入标题栏；窄屏此细条提示 -->
      <div v-if="memberViewRoot && isNarrow" style="display:flex;align-items:center;gap:10px;padding:8px 16px;border-bottom:1px solid #e6e9ed;background:#f5f3ff;flex:none;">
        <button style="border:1px solid #ddd6fe;background:#fff;color:#7c3aed;border-radius:8px;padding:4px 10px;font-size:12px;cursor:pointer;" @click="backToRootSession">← 返回主会话</button>
        <span style="font-size:11px;font-weight:650;color:#7c3aed;background:#fff;border:1px solid #ddd6fe;border-radius:5px;padding:1px 7px;">团队成员会话</span>
        <span v-if="memberViewName" style="font-size:12px;color:#5f6570;">{{ memberViewName }}</span>
      </div>
      <!-- PC 标题栏：窄屏隐藏（与移动顶栏形成双顶栏，图里很明显），
           其中的会话查找/分享/状态三个动作改由下面的 MobileChatHead 以图标提供 -->
      <div v-if="!isNarrow" class="chat-head">
        <template v-if="memberViewRoot">
          <button style="border:1px solid #ddd6fe;background:#fff;color:#7c3aed;border-radius:8px;padding:4px 10px;font-size:12px;cursor:pointer;flex:none;" @click="backToRootSession">← 返回主会话</button>
          <span style="font-size:11px;font-weight:650;color:#7c3aed;background:#f5f3ff;border:1px solid #ddd6fe;border-radius:5px;padding:1px 7px;flex:none;">团队成员会话</span>
        </template>
        <span class="chat-title">{{ memberViewRoot ? (memberViewName || '成员会话') : currentSessionTitle }}</span>
        <span v-if="memberViewRoot && memberLiveStatus === 'live'" class="member-live-tag" title="正在直播这一轮：正文与工具步骤实时更新">
          <i class="mlt-dot"></i>直播中
        </span>
        <span v-else-if="memberViewRoot && memberLiveStatus === 'reconnecting'" class="member-live-tag is-warn" title="连接中断，正在重连">
          <i class="mlt-dot"></i>重连中…
        </span>
        <!-- 当前工作区标识：约定每轮注入 system prompt，用户有权在聊天的当场看见它在生效；
             没挂工作区的会话接口仍返回档位等字段，但 projectId 为空——这里按它判断，标识不显示（不编名字）。
             implicit 兜底容器也一样不显示：它没有目录，名字被兜成「会话」，挂出来只会被读成会话标题重复。
             点它直接进工作区页面（目录/记忆/隔离都在那一页） -->
        <button v-if="sessionWorkspace && sessionWorkspace.projectId && !sessionWorkspace.implicit" class="head-ws" :class="{ 'head-ws-archived': sessionWorkspace.archived }"
                :title="wsTitle" @click="router.push('/project/' + sessionWorkspace.projectId)">
          <folder-open-outlined class="head-ws-ic" />
          <span class="head-ws-name">{{ sessionWorkspace.name }}</span>
        </button>
        <!-- 免责声明从标题栏下沉到每条回答的脚注（见 msg-disclaimer）：它该出现在读结论的现场 -->
        <!-- 头部动作区：右对齐一组，图标按钮无框安静 -->
        <div class="head-actions">
          <!-- 顶栏这组「派了谁」两颗药丸口径互斥，别读成重复：
               · 团队 · N（TeamPill）——名册，只在会话开了智能体团队时出现（关着时它无事可做，开关在右栏团队的未开启空态里）；
               · N 位助手 / N 位在跑（SubagentPill）——本轮派遣卡，只在团队关闭或仍有派遣在跑时出现。
               「开着团队且本轮没人在跑」时只剩团队 · N：派遣结果已经收进名册，再挂一颗只会让人问「这是不是同一件事」。
               两边的显隐都由各自组件内部按数据判定（TeamPill 读团队开关，SubagentPill 读 runs），这里只传消息。 -->
          <TeamPill v-if="!memberViewRoot" :session-id="currentSessionId || ''" :msg="processMsg" @open="openTeamPanel" />
          <button v-if="memberViewRoot" type="button" title="停止该成员当前轮" style="height:26px;padding:0 10px;border:1px solid #f3c9c9;background:#fff2f0;color:#cf1322;border-radius:7px;font-size:12px;cursor:pointer;margin-right:4px;"
                  @click="stopNow">停止</button>
          <button class="app-icon-btn" title="在本会话中查找（Ctrl/⌘ + F）" @click="openSearch">
            <search-outlined />
          </button>
          <!-- 分享激活态：图标转琥珀，与侧栏会话行的分享标记同色（链接还开着不进弹窗也得有感知） -->
          <button class="app-icon-btn" :class="{ 'share-on': shareActive }" :title="shareActive ? '这段对话正在对外分享（只读链接生效中）' : '分享这段对话（只读链接）'"
                  :aria-label="shareActive ? '这段对话正在对外分享（只读链接生效中）' : '分享这段对话（只读链接）'"
                  @click="openShare">
            <share-alt-outlined />
          </button>
          <!-- 子智能体药丸：这一轮派出去几位、几位还在跑。它坐在标题栏而不是正文里，是因为
               派遣一跑就是两三分钟，而正文在这期间一直在往下长——跟在正文里的东西要滚到
               最新那一段才看得见，等它跑完才出现的东西等于没给。 -->
          <SubagentPill :msg="processMsg" :session-id="currentSessionId || ''" @focus="focusSpawn" />
          <!-- 过程账本入口收成图标：过程本身已经挂到回答头上，顶栏只留「事后回看」这条路 -->
          <button class="app-icon-btn" title="看这个会话是怎么跑出来的：工具步与耗时、三张卡、产物、停止与失败"
                  @click="openEvents()"><history-outlined /></button>
          <!-- 状态胶囊：把「本轮跑成什么样」放回顶栏；失败时变红，点它开合右栏 -->
          <button class="head-status" :class="headStatusClass" :title="headStatusTitle"
                  @click="headStatusClick">
            <span class="hs-dot" aria-hidden="true"></span>
            <span>{{ headStatusText }}</span>
          </button>
        </div>
      </div>
      <!-- 窄屏动作行：标题归顶栏，这里只放查找/分享/状态三个图标 -->
      <MobileChatHead v-if="isNarrow" :share-active="shareActive" @search="openSearch" @share="openShare" @panel="togglePanel">
        <SubagentPill mobile :msg="processMsg" :session-id="currentSessionId || ''" @focus="focusSpawn" />
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

      <div ref="box" class="messages" @scroll="onMessagesScroll">
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
              <!-- 回答头：身份 + 完成态 + 模型 + 步数，过程入口也挂在这里。
                   它用 CSS sticky 吸在这条回答内，滚到长答中段仍能看状态与复制。 -->
              <AnswerHeader v-if="m.role === 'ai'" :m="m" :msg-index="i"
                            :agent="agentBadgeOf(agentList, m.agentId, m.agentName)"
                            :name="m.agentName || ''"
                            :model-label="m.model ? modelLabelOf(m.model) : ''" />
          </template>
          <template #body="{ m, i }">
              <!-- 整轮状态行已并入回答头（AnswerHeader）：正文从这里直接开始，
                   过程块仍由回答头上的「过程」按钮开合。 -->
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
                    <div v-if="clusterOpen(m, seg)" v-follow-bottom="!!(m.loading && seg.grow)" v-edge-fade
                         class="tl-cluster-live" :class="{ reading: clusterReading(seg) }">
                      <template v-for="(lr, li) in clusterLiveRows(seg)" :key="li">
                        <!-- 头与详情同一盒：头在自己这盒内贴顶吸附（见 .tl-live-step），
                             滚过这一盒（下一行到头）它自然让位，不会盖住后面几步 -->
                        <div v-if="lr.t" class="tl-live-step">
                          <div class="tl-live-row" :data-spawn="spawnAnchorOf(lr.t)"
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
                          <div v-if="lr.t._open && (lr.t.args || liveOutput(lr.t) || lr.t.error || (lr.notes && lr.notes.length))" class="tl-live-detail" @click.stop>
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
                    <NoteBody v-for="(nt, ni) in seg.notes" v-if="groupHasBar(seg)" :key="'note' + ni" :m="m" :note="nt" />
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
                          <NoteTag v-for="(nt, ni) in seg.notes" v-if="ti === 0 && !groupHasBar(seg)" :key="ni" :m="m" :note="nt" />
                          <span class="tl-caret tl-caret-btn" :class="{ open: toolCardOpen(t) }"><caret-right-outlined /></span>
                        </button>
                        <NoteBody v-for="(nt, ni) in seg.notes" v-if="!groupHasBar(seg)" :key="'note' + ni" :m="m" :note="nt" />
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
                          v-model:open="m.rtOpen" :sources="m.sources" :title="retrievalLineTitle(m)"
                          :note="hitsNote(m)" :terms="m.retrieved && m.retrieved.terms"
                          :tool-queries="toolSearchQueries(m)" :question="questionFor(i)"
                          :can-feedback="true" show-snippet />
          </template>
          <template #sources-after="{ m, i }">
              <!-- 只在最新一轮出现（panelAi=最后一条 AI 消息，含进行中）：更早那几轮的建议已被后面的对话取代，
                   每轮都留着会让整页散落着可点的旧问句，翻历史时容易误点重发一个早问过的话题。
                   整行是按钮：编号 + 问题 + 箭头，比灰底小 chip 更像「下一步」 -->
              <div v-if="m.role === 'ai' && m === panelAi && showRelatedSuggestions && m.related && m.related.length" class="related">
                <div class="related-head">
                  <bulb-outlined class="related-ic" />
                  <span class="related-title">接下来可以</span>
                  <span class="related-sub">会作为新一轮提问发送</span>
                </div>
                <button v-for="(q, qi) in m.related" :key="qi" type="button" class="related-row" @click="ask(q)">
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
                    <button class="ver-btn" :disabled="!canSwitchPrev(m)" @click="switchBranch(i, -1)">‹</button>
                    <span class="ver-idx">{{ verLabel(m) }}</span>
                    <button class="ver-btn" :disabled="!canSwitchNext(m)" @click="switchBranch(i, 1)">›</button>
                  </div>
          </template>
          <template #actions-more="{ m, i }">
                <a-dropdown :trigger="['hover', 'click']">
                  <button class="app-icon-btn" title="更多"><more-outlined /></button>
                  <template #overlay>
                    <a-menu @click="({ key }) => onMoreAction(key, i)">
                      <!-- 固化为技能：把这一轮「问题→做法」沉淀成一条可复用的 SKILL.md（弹窗预填、改两行就能用）。
                           收进「更多」菜单，与重新生成等常驻动作分开；只挂在落库的 AI 回答上（#more 插槽本就在 messageId 判据内） -->
                      <a-menu-item key="solidify" title="把这一轮的做法固化为技能"><thunderbolt-outlined style="margin-right:8px" />固化为技能</a-menu-item>
                      <a-menu-item v-if="debugEntryVisible" key="debug"><bug-outlined style="margin-right:8px" />检索调试</a-menu-item>
                      <!-- 加入评测集 = 差评回流固化到检索评测集（/api/ai/eval 仅管理员可用），属调参排障动作：
                           与「检索调试」同一开关（chat.retrievalDebugEnabled）控制，不给普通用户露出必 403 的入口 -->
                      <a-menu-item v-if="debugEntryVisible && m.sources && m.sources.length" key="addEval"><dislike-outlined style="margin-right:8px" />加入评测集</a-menu-item>
                      <a-menu-item v-if="m.turnId" key="events"><history-outlined style="margin-right:8px" />看这一轮的过程</a-menu-item>

                      <a-menu-item key="schedule"><schedule-outlined style="margin-right:8px" />设为定时任务</a-menu-item>
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
            <!-- 免责声明：从标题栏下沉到读结论的现场；点开仍是原来那份完整声明 -->
            <div v-if="m.role === 'ai' && m.content && !m.loading" class="msg-disclaimer"
                 title="查看完整免责声明" @click="disclaimerVisible = true">
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
            <button type="button" class="tb-head" :title="todoOpen ? '收起任务清单' : '展开任务清单'"
                    :aria-label="todoOpen ? '收起任务清单' : '展开任务清单'"
                    @click="todoOpen = !todoOpen">
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
          <a-dropdown v-if="currentSessionId && !memberViewRoot" :trigger="['click']" placement="topLeft">
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
                <a-menu-item v-for="p in permissionPresets" :key="p.code">
                  <div class="perm-mi-t">{{ p.label }}</div>
                  <div class="perm-mi-d">{{ p.summary }}</div>
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <a-dropdown v-if="!memberViewRoot" v-model:open="agentPickerOpen" :trigger="['click']" placement="topLeft">
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
          :run-settings="!isCoarse"
          panel-title="运行设置"
          :panel-sub="runSettingsModelLabel">
            <!-- 运行设置尾栏：与模型列表同一张下拉。设计稿要的就是「模型 + 思考 + 上下文 + 检索」一次改完，
                 不再另开一层悬浮面板 -->
            <template #runSettings>
              <div class="rs-panel" @mousedown.stop @click.stop>
                <div class="rs-sec">
                  <div class="rs-label">思考强度</div>
                  <div class="rs-seg">
                    <button v-for="opt in runSettingsLevels" :key="opt.value" type="button" class="rs-chip"
                            :class="{ on: opt.value === runSettingsLevel }"
                            :disabled="runSettingsCaps.locked || loading"
                            :title="opt.label" @click="setThinkLevel(opt.value, runSettingsModel)">
                      {{ chipLabelOf(opt.value) }}
                    </button>
                  </div>
                  <div class="rs-hint">{{ runSettingsHint }}</div>
                </div>
                <div v-if="runSettingsWindow" class="rs-row">
                  <span class="rs-k">上下文窗口</span><span class="rs-v">{{ runSettingsWindow }}</span>
                </div>
                <div v-if="runSettingsUsage" class="rs-row">
                  <span class="rs-k">本轮占用</span><span class="rs-v">{{ runSettingsUsage }}</span>
                </div>
                <div class="rs-row">
                  <span class="rs-k">本轮检索</span><span class="rs-v" :title="kbScopeText">{{ kbScopeText }}</span>
                </div>
                <div class="rs-foot">
                  <span class="rs-foot-note">思考档按模型记住</span>
                  <button type="button" class="rs-default" :disabled="isRunModelDefault" @click.stop="setRunModelDefault">
                    {{ isRunModelDefault ? '已是默认' : '设为默认' }}
                  </button>
                </div>
              </div>
            </template>
          </ModelSelect>
        </template>
        <!-- 快捷键说明从 placeholder 里下沉到这里：卡片之下、同一内边距列里；
             页面若在外面另起一行，居中基准会和输入卡片错开 -->
        <template #below>
          <div v-if="!isNarrow" class="chat-hints">
            <span><kbd>Enter</kbd> 发送</span>
            <span><kbd>Shift</kbd>+<kbd>Enter</kbd> 换行</span>
            <span><b>@</b> 引用资料</span>
            <span><b>/</b> 命令</span>
            <span><b>#</b> 历史问答</span>
            <span class="chat-hints-sp">生成中按 Enter 可插话</span>
          </div>
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
    <!-- 右栏左缘拖拽条：宽屏两栏此消彼长（消息区 flex:1，右栏定宽）；拖动只改 --rp-w，消息区不会被挤没（有下限）。
         窄屏右栏是浮层/贴底 sheet，盖在消息之上而非占位，拖动语义不成立，故不显示。 -->
    <div v-if="panelOpen && !isNarrow" class="rp-divider" role="separator" aria-orientation="vertical"
         title="拖动调整右栏宽度" @mousedown.prevent="startPanelResize"></div>
    <aside v-if="panelOpen || mPanelOpen" class="right-panel" :class="{ 'as-sheet': isNarrow, 'browser-wide': railTab === 'browser' }">
      <!-- 窄屏下本栏是覆盖式 sheet，必须给一个显式关闭口：宽屏它是常驻列不需要关，
           窄屏开起来后若只能靠顶栏按钮来回切，用户会觉得「关不掉」。仅窄屏渲染。 -->
      <button v-if="isNarrow" class="rp-sheet-close" type="button" title="关闭" @click="mPanelOpen = false">
        <close-outlined />
      </button>
      <!-- 右栏分段：过程 / 来源 / 文件 / 浏览。原先所有卡竖排，本轮最该看的过程会被后面的卡顶出首屏；
           分段把三件事分开，计数与失败数直接标在页签上 -->
      <div class="rp-tabs" role="tablist" aria-label="右栏内容">
        <button type="button" class="rp-tab" :class="{ on: railTab === 'process' }" role="tab"
                :aria-selected="railTab === 'process'" @click="railTab = 'process'">
          过程<span class="rp-tab-n">{{ processSteps.length }}</span>
          <span v-if="processFailCount" class="rp-tab-badge">{{ processFailCount }}</span>
        </button>
        <button type="button" class="rp-tab" :class="{ on: railTab === 'sources' }" role="tab"
                :aria-selected="railTab === 'sources'" @click="railTab = 'sources'">
          来源<span class="rp-tab-n">{{ groupedSources.length }}</span>
        </button>
        <button type="button" class="rp-tab" :class="{ on: railTab === 'files' }" role="tab"
                :aria-selected="railTab === 'files'" @click="railTab = 'files'">
          文件<span class="rp-tab-n">{{ fileTabCount }}</span>
        </button>
        <button type="button" class="rp-tab" :class="{ on: railTab === 'browser' }" role="tab"
                :aria-selected="railTab === 'browser'" @click="railTab = 'browser'">
          浏览<span v-if="browserSnapshots.length" class="rp-tab-n">{{ browserSnapshots.length }}</span>
        </button>
        <button v-if="!memberViewRoot" type="button" class="rp-tab" :class="{ on: railTab === 'team' }" role="tab"
                :aria-selected="railTab === 'team'" @click="railTab = 'team'">
          团队
        </button>
      </div>
      <!-- 用 v-show 保留实例：在「浏览」与过程/来源/文件之间来回切时，侧栏 iframe 的地址与
           已加载页面不会被卸载重建（v-if 会让正在看的页面消失）。整栏关闭仍由上面的 aside v-if 控制。 -->
      <BrowserPanel v-show="railTab === 'browser'" :snapshots="browserSnapshots" />
      <TeamPanel v-if="railTab === 'team' && !memberViewRoot" :session-id="currentSessionId || ''" :session-title="currentSessionTitle" :agent-name="currentAgentName" :agent="agentBadgeOf(agentList, currentAgentId, currentAgentName)" :agents="agentList" :msg="processMsg" />
      <!-- 本轮卡（常驻首位）：跑轮时实时滚动「哪几步、每步在做什么事」，答完收成这一轮的动作清单。
           单步耗时不在这里报（整轮用时归气泡那条状态行），要点「查看全部步骤」进账本弹窗。
           会话里还没有 AI 消息时整卡不渲染（新会话不占位）。其余各卡仍是「有数据才显示」。 -->
      <RightProcessCard v-if="railTab === 'process'" :msg="processMsg"
                        @open-events="openEvents()" @open-hitl="focusHitl"
                        @regenerate="processMsgIndex >= 0 && regenerate(processMsgIndex)" />
      <div v-if="railTab === 'process' && !processMsg" class="rp-empty">还没有可看的过程</div>
      <!-- 引用来源：右栏交互最重的一张卡（点片段看原文 / 角标号定位正文 / hover 高亮角标 / 分组展开），过程卡之后的第一张。
           原先排在末尾，等于把「核查答案」这个最高频动作排到最需要滚动才看得到的位置——
           正文里刚亮起的角标，用户得点开侧栏再往下翻。移动端 sheet 一直把它排第一，这里对齐。
           有引用才显示整卡（空则隐藏，不占版面，与产物卡同规则）。
           卡头可折叠：来源多时（11 个来源就是 11 个分组行）整卡会把下面的卡顶出首屏。
           任务清单在输入框上方常驻（见 #above 的 .todo-bar）。 -->
      <div v-if="railTab === 'sources' && !groupedSources.length" class="rp-empty">本轮没有引用来源</div>
      <div v-if="railTab === 'sources' && groupedSources.length" class="rp-card">
        <button type="button" class="rp-card-head" @click="srcCardOpen = !srcCardOpen">
          <span class="rp-label">引用来源 · {{ groupedSources.length }} 个来源</span>
          <caret-right-outlined class="tl-caret" :class="{ open: srcCardOpen }" />
        </button>
        <div v-if="srcCardOpen">
          <div v-for="g in groupedSources" :key="g.key" class="rp-group">
            <div class="rp-group-head" :title="srcOpenOf(g) ? '收起片段' : '展开片段'" @click="toggleSrc(g)">
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
      <div v-if="railTab === 'files' && filesTabEmpty" class="rp-empty">本轮还没有文件或交付</div>
      <div v-if="railTab === 'files' && deliveryPackages.length" class="rp-card">
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
      <div v-if="railTab === 'files' && (sandboxFiles.length || sandboxErr || sandboxLoading)" class="rp-card">
        <div class="rp-label">{{ sessionWorkspace && sessionWorkspace.isSshAlias ? '远端文件' : (sessionWorkspace && (sessionWorkspace.isLocal || sessionWorkspace.wsEffective === 'local') ? '本机文件' : '会话文件') }} · {{ sandboxCount }} 个{{ sandboxBytes ? ' · ' + sbSize(sandboxBytes) : '' }}</div>
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
                    :title="(row.open ? '收起 ' : '展开 ') + row.dir.path + '/（' + row.dir.count + ' 个文件 · ' + fmtSize(row.dir.size) + '）'"
                    @click="toggleSandboxDir(row.dir.path)">
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
      <!-- 会话后台进程：让它「把写好的前后端跑起来自己看效果」时，起的是 npm run dev 这类<b>不会自己退</b>
           的进程。两个来源合成一张清单：工作区会话的进程跑在你电脑上的本机执行器里，会话栏会话的跑在沙盒容器里；
           同一条会话可能在执行器离线的轮退回沙盒，两边都会出现，所以来源只是行上的一个标记，不拆两张卡。
           真源各自在源头（执行器进程登记 + ps 实况 / 容器 /proc），服务端不留台账：执行器/后端重启都照得到。
           沙盒里挂了共享目录时多个会话共用一个容器，别的会话起走的进程也会列出来，标一句——端口是同一个容器占的。
           本机侧端口可以直接点开（就是你电脑上的 localhost），沙盒容器端口不映射、只说明它占着哪个。 -->
      <div v-if="railTab === 'files' && procCardVisible" class="rp-card">
        <div class="rp-label rp-proc-head"
             title="助手在会话里起的、不会自己退的进程（比如为了自己看效果起的服务）。本机执行器到点只是不再等它（或它自己退成了孤儿），进程照旧活着。">
          <span>后台进程<span class="rp-proc-n">{{ procs.length }}</span><span class="rp-proc-unit">个在跑</span></span>
          <span class="rp-proc-acts">
            <button v-if="procs.length > 1" type="button" class="rp-sb-retry" :disabled="killingAll || !!killingTag"
                    title="停掉本会话起的全部进程树" @click="killAllProcs">{{ killingAll ? '停止中…' : '全部停止' }}</button>
            <button type="button" class="rp-sb-retry" :disabled="procLoading"
                    title="重新扫一遍会话里的进程" @click="loadSandboxProcs">刷新</button>
          </span>
        </div>
        <div v-for="(n, i) in procNotices" :key="'notice-' + n.side" class="rp-proc-note">
          <span>{{ n.message }}</span>
          <button v-if="n.reason === 'error'" type="button" class="rp-sb-retry" @click="loadSandboxProcs">重试</button>
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
            <span class="rp-proc-side" :class="'is-' + (p.side || 'sandbox')"
                  :title="p.side === 'local' ? '起在你电脑上（本机执行器）' : '起在服务器沙盒里'">
              {{ p.side === 'local' ? '本机' : '沙盒' }}</span>
            <span class="rp-proc-cmd-t" :title="p.command">{{ p.command }}</span>
          </div>
          <div class="rp-proc-foot">
            <span class="rp-proc-meta">
              <span>跑了 {{ procAge(p) }}</span>
              <span v-if="p.ports && p.ports.length">
                <template v-if="p.side === 'local'">
                  · 占着端口
                  <template v-for="(port, pi) in p.ports" :key="port">
                    <a :href="'http://localhost:' + port" target="_blank" rel="noopener" class="rp-proc-port"
                       :title="'在浏览器里打开 localhost:' + port">{{ port }}</a><span v-if="pi < p.ports.length - 1"> / </span>
                  </template>
                </template>
                <template v-else> · 占着端口 {{ p.ports.join(' / ') }}（沙盒内部）</template>
              </span>
              <span v-if="p.roots > 1"> · {{ p.roots }} 棵进程树</span>
              <span v-if="!p.own"> · 同工作区别的会话起的</span>
            </span>
            <button v-if="p.killable" type="button" class="rp-proc-kill" :disabled="killingTag === p.tag || killingAll"
                    :title="'停掉它，连同它起走的全部子进程（' + p.pids.length + ' 个）'"
                    @click="killProc(p)">{{ killingTag === p.tag ? '停止中…' : '停止' }}</button>
            <span v-else class="rp-proc-unknown" title="认不出这是哪次会话起的，停不了">来源不明</span>
          </div>
        </div>
      </div>
      <!-- 计划：本会话每轮出过的计划各列一条（继续对话/退回重出后每次重做都算一份），点开右侧完整计划抽屉。
           待确认的计划自动展开并置顶，避免「计划要批」这件事被藏住；历史计划沉底折叠。 -->
      <div v-if="railTab === 'process' && sessionPlans.length" class="rp-card">
        <button type="button" class="rp-card-head rp-plan-head" :title="planOpen ? '收起计划' : '展开计划'"
                @click="planOpen = !planOpen">
          <span class="rp-label rp-label-h">计划 · {{ sessionPlans.length }} 份</span>
          <span class="rp-plan-badge" :class="{ 'is-pending': sessionPlans.some(p => p.status === 'pending') }">
            {{ planHeadText }}
          </span>
          <caret-right-outlined class="tl-caret" :class="{ open: planOpen }" />
        </button>
        <div class="rp-group-body" :class="{ open: planOpen }">
          <div class="rp-group-body-in">
            <button v-for="p in sessionPlans" :key="p.mi" type="button" class="rp-plan-row" :title="p.label + ' · ' + p.steps + ' 步'"
                    @click="openPlanFromRail(p)">
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
        <a-input v-model:value="debugQuestion" placeholder="输入要调试的问题" @press-enter="runDebug" />
        <button class="app-btn" :disabled="debugLoading" @click="runDebug">调试</button>
      </div>
      <a-spin :spinning="debugLoading">
        <template v-if="debugResult">
          <div v-if="debugResult.rewrite" class="dbg-rewrite">
            <span class="dbg-terms-label">追问改写</span>
            <template v-if="debugResult.rewrite.applied">
              <a-tag color="green" size="small">已改写</a-tag>
              <span class="dbg-rw-text">{{ debugResult.rewrite.original }} → {{ debugResult.rewrite.query }}</span>
            </template>
            <template v-else>
              <a-tag size="small">未改写</a-tag>
              <span class="dbg-rw-text">{{ rewriteSkipText(debugResult.rewrite.skipReason) }}</span>
            </template>
          </div>
          <div v-if="debugResult.keywordTerms?.length" class="dbg-terms">
            <span class="dbg-terms-label">检索词元</span>
            <a-tag v-for="(t, ti) in debugResult.keywordTerms" :key="ti" color="blue" style="margin:2px">{{ t }}</a-tag>
          </div>
          <div v-if="debugResult.retrievalDiag?.subQueries?.length" class="dbg-terms">
            <span class="dbg-terms-label">多问识别</span>
            <a-tag size="small">仅诊断，检索未拆分</a-tag>
            <a-tag v-for="(sq, qi) in debugResult.retrievalDiag.subQueries" :key="qi" color="purple" style="margin:2px">{{ sq }}</a-tag>
          </div>
          <a-collapse :bordered="false" :default-active-key="['final']">
            <a-collapse-panel v-for="(s, si) in debugStages" :key="si" :name="si === 4 ? 'final' : String(si)" :header="s.name">
              <div v-for="(it, ii) in s.items" v-if="s.items.length" :key="ii" class="dbg-item">
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
          <div class="thinkp-title">运行设置<span v-if="modelLabelOf(thinkHoverModel)" class="thinkp-model">{{ modelLabelOf(thinkHoverModel) }}</span></div>
          <div v-if="thinkPanelCtxText || thinkPanelCaps.visible" class="thinkp-row-wrap">
            <!-- 上下文窗口只报登记上限：窗口一律按模型登记的最大值走 -->
            <div v-if="thinkPanelCtxText" class="thinkp-row"
                 title="模型登记的上下文窗口：一次能带进的资料与历史总量，也决定长对话什么时候开始折上下文">
              <span class="thinkp-row-label">上下文窗口</span>
              <span class="thinkp-row-val">{{ thinkPanelCtxText }}</span>
            </div>
            <!-- 本轮占用与检索范围只对「正在生效的模型」说：悬浮另一个模型时，别把本轮的用量算到它头上 -->
            <div v-if="ctxCapData.used && thinkHoverModel === effectiveModel" class="thinkp-row"
                 title="本轮结束时的上下文占用 / 模型登记的窗口">
              <span class="thinkp-row-label">上下文占用</span>
              <span class="thinkp-row-val">{{ fmtTokens(ctxCapData.used) }}<template v-if="ctxPctText"> · {{ ctxPctText }}</template></span>
            </div>
            <div v-if="thinkHoverModel === effectiveModel" class="thinkp-row" title="本轮显式 @ 的知识库；没有就是按智能体默认范围">
              <span class="thinkp-row-label">本轮检索</span>
              <span class="thinkp-row-val thinkp-row-ellipsis">{{ kbScopeText }}</span>
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
         ArrowRightOutlined, OrderedListOutlined, LeftOutlined, FolderOpenOutlined, FolderOutlined, ExportOutlined,
         AimOutlined, EyeOutlined, SaveOutlined, ReloadOutlined, BulbOutlined,
         SafetyCertificateOutlined, ScheduleOutlined } from '@ant-design/icons-vue'
import { debugRetrieval, deleteMessageGroup, submitFeedback as apiSubmitFeedback,
         addEvalCase, getSessionShare, enableSessionShare, disableSessionShare,
         getSessionWorkspace, getPermissionPresets, setSessionPermission,
         openProjectFolder, updateProjectItem, createSkill, setUserPreference } from '../api'
import { renderMd, resolveImg, onImgError } from '../utils/markdown'
import { loadSessions, sessionStore } from './store'
import { exportAnswerMd, exportSessionMarkdown } from './exportMd'
import { fmtTokens } from '../utils/token'
import { chatDone, guideSnoozed, refreshSetupGuide, setupGuide } from '../utils/setupGuide'

/** 追问改写未执行的原因 → 用户能看懂的一句话（界面不出现 no-history 这类开发术语） */
const rewriteSkipText = r => ({
  'no-history': '本会话还没有可参考的前文',
  blank: '检索词为空',
  'too-long': '检索词已足够完整',
  standalone: '检索词已足够独立',
  'no-model': '未取到本轮模型',
  timeout: '改写超时，已按原词检索',
  failed: '改写失败，已按原词检索',
  'empty-output': '改写没有返回可用结果',
  unchanged: '与原检索词一致'
}[r] || '本轮未改写')
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
import AnswerHeader from '../components/AnswerHeader.vue'
import { roundSteps } from '../chat/roundSummary'
import SubAgentChatCard from '../components/SubAgentChatCard.vue'
import MsgDivider from '../components/MsgDivider.vue'
import ToolSpillButton from '../components/ToolSpillButton.vue'
import SessionEventsPanel from '../components/SessionEventsPanel.vue'
// 右栏「过程」卡：跑轮时实时滚动工具步（数据就是引擎那份消息对象，不另取数）
import RightProcessCard from '../components/RightProcessCard.vue'
import TeamPanel from '../components/TeamPanel.vue'
import TeamPill from '../components/TeamPill.vue'
// 右栏「浏览」卡：助手本轮浏览器操作的只读快照镜像（唯一出口是「在新窗口打开」）
import BrowserPanel from '../components/BrowserPanel.vue'
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
import { useHeadStatus } from '../chat/useHeadStatus'
import { useRunSettings } from '../chat/useRunSettings'
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
import { createMemberLive } from '../chat/memberLive'
import { subscribeMemberLive } from '../api'
import { createMemberLiveState } from '../chat/memberLiveState'
import { usePlanPanels } from '../chat/usePlanPanels'
import { useDeepAsk } from '../chat/useDeepAsk'

// ===== 布局层装配：页面只保留组件注册、共享 DOM ref 与各视图域的接线 =====
const page = {}
const router = useRouter()
const route = useRoute()
// 团队成员会话视图：从「团队」面板点成员打开，顶部提供返回主会话
const memberViewRoot = computed(() => { const v = route.query.teamRoot; return Array.isArray(v) ? v[0] : (v || '') })
const memberViewName = computed(() => { const v = route.query.memberName; return Array.isArray(v) ? v[0] : (v || '') })
function backToRootSession() {
  const sid = memberViewRoot.value
  if (sid) router.push({ path: '/chat', query: { sid } }).catch(() => {})
}
// 输入框与编辑卡的 DOM ref 由布局层持有：命令面板与两个域指向同一份元素。
const composerRef = ref(null)
const editingRef = ref(null)
const goalArmed = ref(false)
const goalStarting = ref(false)
const CLUSTER_ICONS = { search: SearchOutlined, web: GlobalOutlined, tools: ApiOutlined, thinking: BulbOutlined }
// 具名引用：命名空间 import 会把整个图标库与 api 模块拖进本页分包。
const api = { debugRetrieval, deleteMessageGroup, submitFeedback: apiSubmitFeedback,
              addEvalCase, getSessionShare, enableSessionShare, disableSessionShare,
              getSessionWorkspace, getPermissionPresets, setSessionPermission,
              openProjectFolder, updateProjectItem, createSkill }
const icons = { CompressOutlined, AimOutlined, FileTextOutlined, PlusOutlined,
                PaperClipOutlined, BookOutlined, HistoryOutlined, ThunderboltOutlined,
                DatabaseOutlined, RobotOutlined, AppstoreOutlined }

// 浏览器能力只读镜像：SSE 事件 browser_snapshot → onBrowserSnapshot 回调（引擎经 hooks 转来）。
// 同一轮最多保留最近 20 条、最新在前；turn_start / 切会话时清空旧快照。
const browserSnapshots = ref([])
const MAX_BROWSER_SNAPSHOTS = 20
const clearBrowserSnapshots = () => { browserSnapshots.value = [] }
const onBrowserSnapshot = payload => {
  let snap = payload
  if (typeof snap === 'string') {
    try { snap = JSON.parse(snap) } catch (e) { return }
  }
  if (!snap || typeof snap !== 'object') return
  browserSnapshots.value = [snap, ...browserSnapshots.value].slice(0, MAX_BROWSER_SNAPSHOTS)
}

// 右栏宽度拖拽：宽屏两栏此消彼长，拖动只改 --rp-w（作用在 .chat2 上）。
// 上限 = 视口宽的 2/3（同时消息区至少留 360px），下限 280。宽度存 localStorage。
const RP_W_KEY = 'wenqu.rpWidth'
const RP_MIN = 280, CHAT_MIN = 360
const rpMax = () => Math.max(RP_MIN, Math.min(window.innerWidth * 2 / 3, window.innerWidth - CHAT_MIN))
const rpWidth = ref(Math.max(RP_MIN, Math.min(rpMax(), Number(localStorage.getItem(RP_W_KEY)) || 420)))
watch(rpWidth, v => { localStorage.setItem(RP_W_KEY, String(v)); applyRpWidth() })
const chat2Ref = ref(null)
const applyRpWidth = () => chat2Ref.value?.style.setProperty('--rp-w', rpWidth.value + 'px')
onMounted(() => { rpWidth.value = Math.max(RP_MIN, Math.min(rpMax(), rpWidth.value)); applyRpWidth() })
const startPanelResize = e => {
  const startX = e.clientX, startW = rpWidth.value
  const onMove = ev => rpWidth.value = Math.max(RP_MIN, Math.min(rpMax(), startW - (ev.clientX - startX)))
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
  }
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

// 引擎先装配：所有视图域的 getter 都能安全读取；视图回调经 page 惰性取值。
const engine = useChatEngine({
  chatPath: '/chat',
  planModeEnabled: true,
  onBrowserSnapshot,
  onTurnStart: () => clearBrowserSnapshots(),
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
  currentAgentName, currentAgent, pickAgent, modelLabelOf, modelSwitchInfo, loadAgents,
  // 会话与消息
  currentSessionId, loading, messages, currentSessionTitle,
  // 模型
  currentOverrideModel, userDefaultModel, modelIndex, effectiveModel, effectiveModelLabel, effectiveModelIcon,
  effectiveModelProvider, debugEntryVisible, debugDisplayVisible, showRelatedSuggestions, effectiveModelIssue,
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
  // 后台进程那一族同样吃引擎（本机执行器 + 沙盒两路）：本页只留这张卡的摆法
  procs, procNotices, procErr, procLoading, procCardVisible, killingTag, killingAll,
  loadSandboxProcs, procAge, killProc, killAllProcs,
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

// 切会话即清空上一会话的浏览器快照（新会话/重新进入都不该看到上一轮的浏览器动作）
watch(currentSessionId, () => clearBrowserSnapshots())

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

// 顶栏状态胶囊 + 右栏分段（见 ../chat/useHeadStatus）
const {
  processSteps, processFailCount, headStatusClass, headStatusText, headStatusTitle,
  railTab, fileTabCount, filesTabEmpty, processMsgIndex, headStatusClick
} = useHeadStatus({
  processMsg: () => processMsg.value, roundSteps,
  panelOpen: () => panelOpen.value, mPanelOpen: () => mPanelOpen.value,
  isNarrow: () => isNarrow.value, togglePanel: (...a) => togglePanel(...a),
  deliveryPackages, sandboxCount, procs, sandboxErr, sandboxLoading, procCardVisible, messages
})

// 顶栏团队胶囊 → 打开右栏「团队」分段（宽屏占位列 / 窄屏 sheet）
function openTeamPanel() {
  try {
    railTab.value = 'team'
    if (isNarrow.value) mPanelOpen.value = true
    else panelOpen.value = true
  } catch (e) { /* 忽略 */ }
}

// 成员会话页里「团队」分段不适用（团队是根会话的概念）：进入时自动让位到「过程」
watch(memberViewRoot, (v) => {
  try { if (v && railTab.value === 'team') railTab.value = 'process' } catch (e) { /* 忽略 */ }
})

// ===== 成员轮直播：与主问答同一条事件契约，只换了出口 =====
// 成员轮由框架唤醒在后台起跑、没有请求线程，原先页面只能等跑完读历史（一次性蹦出结果）。
// 后端在该轮起跑时开了直播台，这里进页面就挂观众席：帧按主问答同一套回调拼成一条「直播态」气泡，
// 轮一结束就按历史重载——落库那份是权威，直播只是让人看得见它正在长出来。
const memberLiveState = createMemberLiveState({
  appendMessage: m => {
    const list = messages.value || []
    if (!list.includes(m)) messages.value = [...list, m]
  },
  // 只重载正在看的这一条：换了成员/离开之后才回来的收尾，重载会把用户正在看的别处冲掉
  reload: sid => {
    if (currentSessionId.value === sid) engine.switchSession(sid).catch(() => {})
  },
  onScroll: () => page.chrome?.scroll?.(),
  setTodos: () => {}
})
// 直播连接状态：成员页标题旁那颗小标用它说「直播中 / 重连中」——观众要知道自己看的是实时还是回放
const memberLiveStatus = ref('') // '' | 'live' | 'reconnecting' | 'done' | 'closed'
const memberLive = createMemberLive({
  subscribe: (sid, opts) => subscribeMemberLive(sid, opts),
  onFrame: (cb, content) => memberLiveState.onFrame(cb, content),
  onSnapshot: (snap, sid) => memberLiveState.applySnapshot(snap, sid),
  onEnd: why => memberLiveState.finish(why),
  onStatus: st => { memberLiveStatus.value = st },
  setTimer: (fn, ms) => setTimeout(fn, ms),
  clearTimer: t => clearTimeout(t),
  onWarn: text => { try { message.warning(text) } catch (e) { /* 提示失败不影响直播 */ } }
})
// 进成员会话页就挂上，换成员/离开就摘掉：一条成员会话同一时刻只需要一个观众
watch([memberViewRoot, currentSessionId], ([root, sid]) => {
  if (root && sid) memberLive.start(sid)
  else memberLive.stop()
}, { immediate: true })
onUnmounted(() => memberLive.stop())

// 运行设置（见 ../chat/useRunSettings）
const {
  runSettingsModel, runSettingsModelLabel, runSettingsCaps, runSettingsLevels,
  runSettingsLevel, runSettingsHint, runSettingsWindow, isRunModelDefault,
  setRunModelDefault, ctxPctText, runSettingsUsage, kbScopeText
} = useRunSettings({
  currentOverrideModel, effectiveModel, effectiveModelLabel, userDefaultModel,
  modelIndex, pendingMentions, ctxCapData,
  modelLabelOf, thinkCapsOf, levelOptionsOf, currentLevelOf, fmtWindow, fmtTokens,
  setUserPreference, message
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
  pickAgent, switchBranch, regenerate, retryHistory
}

/** 执行详情：工具/产物按 stepId 归到步骤上；没有归属数据时卡片只报状态。 */
const planExecDetailOf = m => planExecDetail(planExecProgressOf(m), m && m.toolCalls, (m && m.artifacts) || [])

/** 帽体里有没有把某一步摊开在读：有则顶部渐隐要撤——贴顶吸附的行头正落在 24px 渐隐带里，
 *  不撤会被整行抹成透明（见 .tl-cluster-live.reading） */
const clusterReading = seg => clusterLiveRows(seg).some(r => r.t && r.t._open)

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
  submitSolidify, exportRound, deleteRound,
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

<style scoped src="./ChatPage.css"></style>

