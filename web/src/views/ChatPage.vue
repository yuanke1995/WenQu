<template>
  <div class="chat2">
    <!-- 中间：标题栏 + 消息流 + 输入区 -->
    <div class="chat-col">
      <!-- PC 标题栏：窄屏隐藏（与移动顶栏形成双顶栏，图里很明显），
           其中的会话查找/分享/状态三个动作改由下面的 MobileChatHead 以图标提供 -->
      <div v-if="!isNarrow" class="chat-head">
        <span class="chat-title">{{ currentSessionTitle }}</span>
        <span class="head-tip" title="查看免责声明" @click="disclaimerVisible = true">AI 回答可能有误，重要信息请核实</span>
        <!-- 头部动作区：右对齐一组，图标按钮无框安静（此前每个按钮各自 margin-left:auto 散落标题栏中间，视觉突兀） -->
        <div class="head-actions">
          <!-- 分享激活态：图标转琥珀，与侧栏会话行的分享标记同色（链接还开着不进弹窗也得有感知） -->
          <button class="app-icon-btn" :class="{ 'share-on': shareActive }" @click="openShare"
                  :title="shareActive ? '这段对话正在对外分享（只读链接生效中）' : '分享这段对话（只读链接）'">
            <share-alt-outlined />
          </button>
          <button class="app-icon-btn" title="在本会话中查找（Ctrl/⌘ + F）" @click="openSearch">
            <search-outlined />
          </button>
          <button class="head-quiet-btn" @click="togglePanel">{{ panelOpen ? '隐藏状态' : '状态' }}</button>
        </div>
      </div>
      <!-- 窄屏动作行：标题归顶栏，这里只放查找/分享/状态三个图标 -->
      <MobileChatHead v-if="isNarrow" :share-active="shareActive" @search="openSearch" @share="openShare" @panel="togglePanel" />

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

      <!-- 工具审批恢复横幅：点开 tool.approval 通知直达会话时，按 approvalId 重建审批卡
           （刷新丢失的 SSE 卡据此补回；内存态可能已失效，此时 status 为终态并提示） -->
      <div v-if="recoveryApproval" class="approval-recovery">
        <div class="ar-title"><exclamation-circle-outlined /> 工具审批待处理：{{ recoveryApproval.toolName }}</div>
        <pre v-if="recoveryApproval.requestArgs" class="ar-args">{{ recoveryApproval.requestArgs }}</pre>
        <div class="ar-foot">
          <span class="ar-status" :class="'ar-' + recoveryStatusClass">{{ recoveryStatusText }}</span>
          <template v-if="recoveryApproval.status === 'PENDING'">
            <button class="app-btn small" :disabled="recoveryBusy" @click="resolveRecovery(true)">批准执行</button>
            <button class="app-btn ghost small" :disabled="recoveryBusy" @click="resolveRecovery(false)">拒绝</button>
          </template>
          <button class="app-btn ghost small" @click="dismissRecovery">关闭</button>
        </div>
      </div>

      <!-- 智能体提问不走顶部横幅：卡片已落库、等待期间断线不中止本轮，恢复由引擎在会话加载时
           按待答记录重建底部答题面板（hydratePendingAsk），与实时卡同一处、同一套交互。 -->

      <div class="messages" ref="box" @scroll="onMessagesScroll">
        <div v-if="messages.length === 0" class="welcome">
          <BrandMark :size="44" class="welcome-mark" />
          <!-- 引导卡：聊天模型/默认模型未就绪时替代示例问题（loaded 门控——首次对账未成功前维持现状，
               宁可晚一拍引导，不给已配好的存量用户闪一帧假引导）。无模型时点示例只会弹拦截 toast，是死路 -->
          <template v-if="setupGuide.loaded && !chatDone">
            <h2>欢迎使用问渠</h2>
            <p>完成下面的配置即可开始对话</p>
            <div class="welcome-guide"><SetupGuide scope="chat" variant="card" /></div>
          </template>
          <template v-else>
            <WelcomeCopy :variant="isNarrow ? 'mobile' : 'pc'" :agent-name="currentAgent?.name || ''"
                         :think-on="deepThinkOn" :attach-count="pendingImages.length + pendingFiles.length"
                         :mentions="mentionNames" />
            <!-- 示例问题：点击即发（内容走配置 chat.sampleQuestions，个人覆盖 > 系统全局，可自定义可关闭）。
                 宽屏 2×2 网格；窄屏竖排卡片（src/h5/MobileSampleCards.vue，字号恢复 12/14px）。
                 关掉或清空即整块不显示，标题与输入框照常 -->
            <template v-if="sampleQuestions.length">
              <div v-if="!isNarrow" class="welcome-samples">
                <button v-for="(q, i) in sampleQuestions" :key="i" class="ws-card" type="button" @click="ask(q.text)">
                  <span class="ws-text">
                    <span v-if="q.label" class="ws-label">{{ q.label }}</span>
                    <span class="ws-q">{{ q.text }}</span>
                  </span>
                </button>
              </div>
              <MobileSampleCards v-else :questions="sampleQuestions" @ask="ask" />
            </template>
          </template>
        </div>

        <div v-for="(m, i) in messages" :key="i" :data-row-index="i" class="row" :class="m.role">
          <!-- 模型切换记录：本轮回答与前一条回答模型不同时才出现（发消息产生新回答的那一刻，切换选择器本身不产生记录） -->
          <div v-if="modelSwitchInfo(m, i)" class="model-switch-divider">
            <span class="msd-line"></span>
            <span class="msd-text">
              <swap-outlined class="msd-ic" />
              <span>模型已切换 {{ modelSwitchInfo(m, i).fromLabel }} → {{ modelSwitchInfo(m, i).toLabel }}</span>
            </span>
            <span class="msd-line"></span>
          </div>
          <div class="msg-block" :class="[m.role, { editing: editingIdx === i }]">
            <div class="bubble" :class="m.role">
              <div v-if="m.role === 'user' && m.images && m.images.length" class="msg-imgs">
                <img v-for="(u, ui) in m.images" :key="ui" :src="resolveImg(u)" class="msg-img"
                     :alt="'上传图片' + (ui + 1)" @click="openPreviewFromMsg(m, ui)" @error="onImgError" />
              </div>
              <div v-if="m.role === 'user' && m.attachments && m.attachments.length" class="msg-files">
                <template v-for="(a, fi) in m.attachments" :key="fi">
                  <span v-if="isPastedText(a)" class="msg-file paste-card"
                        :title="a.text ? '点击查看粘贴的全文' : '粘贴文本的正文未随历史记录保留'"
                        @click="openPasteView(a)">
                    <span class="paste-card-ic"><file-text-outlined /></span>
                    <span class="paste-card-txt">
                      <span class="paste-card-name">{{ pasteTitle(a.name) }}</span>
                      <span class="paste-card-sub">{{ pasteSub(a) }}</span>
                    </span>
                  </span>
                  <span v-else class="msg-file"
                        :title="(a.mime || '附件') + (a.size ? ' · ' + fmtSize(a.size) : '')">
                    <paper-clip-outlined class="msg-file-ic" />
                    <span class="msg-file-name">{{ a.name }}</span>
                    <span v-if="a.size" class="msg-file-size">{{ fmtSize(a.size) }}</span>
                  </span>
                </template>
              </div>
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
              <!-- 回答归属：会话内首条助手消息、或归属发生变化时才标（同一智能体全程一致则不必重复） -->
              <!-- 显示与否由排障显示开关控制（chat.retrievalDebugEnabled，与「已派遣」提示同一个开关） -->
              <div v-if="showAgentTag(m, i) && debugDisplayVisible" class="agent-tag">
                <AgentAvatar class="agent-tag-ava" :agent="agentBadgeOf(agentList, m.agentId, m.agentName)" :size="14" />
                <span>由「{{ m.agentName }}」回答</span>
              </div>
              <div v-if="m.role === 'ai' && m.thinking" class="think-panel" :class="{ open: m.thinkOpen }">
                <div class="think-head" @click="m.thinkOpen = !m.thinkOpen">
                  <span class="think-title">深度思考</span>
                  <a-spin v-if="m.thinkLoading" size="small" style="margin-left:6px" />
                  <span v-else class="think-badge">已完成</span>
                  <caret-right-outlined class="tl-caret" :class="{ open: m.thinkOpen }" />
                </div>
                <div v-show="m.thinkOpen" class="think-body"><AnswerBody :content="m.thinking" :streaming="m.thinkLoading" /></div>
              </div>
              <div v-if="hasTimelineBlocks(m)" class="md" :data-msg-index="i">
                <template v-for="(seg, si) in timelineRows(m)" :key="si">
                  <AnswerBody v-if="seg.kind === 'text'" class="tl-text" :content="m.content.slice(seg.from, seg.to)"
                              :images="m.images" :sources="m.sources" :msg-index="i"
                              runnable :session-id="currentSessionId" :streaming="m.loading" />
                  <!-- 过程簇头：连续的「独白+工具」收成一行。展开后其内各段由 timelineRows 摊平接着渲染，
                       内部结构与展开前完全一致（时序零损失）；纯独白簇（无工具）退化为「N 段说明」 -->
                  <div v-else-if="seg.kind === 'cluster'" class="tl-cluster" :class="{ open: clusterOpen(m, seg) }">
                    <button class="tl-cluster-bar" type="button" @click="toggleCluster(m, seg)">
                      <loading-outlined v-if="clusterRunning(seg)" spin class="tool-ic tool-ic-run" />
                      <close-circle-outlined v-else-if="clusterHasError(seg)" class="tool-ic tool-ic-err" />
                      <check-outlined v-else class="tool-ic tool-ic-ok" />
                      <span v-if="clusterTools(seg).length">已执行 {{ clusterTools(seg).length }} 个操作</span>
                      <span v-else>执行过程 · {{ clusterProcCount(seg) }} 段说明</span>
                      <span class="tool-dur">· {{ clusterDur(seg) }}</span>
                      <span class="tl-caret" :class="{ open: clusterOpen(m, seg) }"><caret-right-outlined /></span>
                    </button>
                  </div>
                  <!-- 过程独白段：区间指向 m.processText（与正文分流），标题行常驻、内容可折叠。
                       在簇内改称「过程说明」——父子同名会读成套娃（簇头「已执行 N 个操作」/「执行过程」） -->
                  <div v-else-if="seg.kind === 'process'" class="tl-process-block" :class="{ inCluster: seg.inCluster }">
                    <button class="tl-process-head" type="button" @click="toggleProc(m, seg)">
                      <span class="tl-process-title">{{ seg.inCluster ? '过程说明' : '执行过程' }}</span>
                      <span class="tl-caret" :class="{ open: procOpen(m, seg) }"><caret-right-outlined /></span>
                    </button>
                    <div v-show="procOpen(m, seg)" class="tl-process">{{ procSlice(m, seg) }}</div>
                  </div>
                  <!-- 产物段不再就地渲染：产物统一沉底（时间线数据仍保留 artifact 段以备后续） -->
                  <div v-else class="tl-group" :class="{ inCluster: seg.inCluster }">
                    <button v-if="seg.tools.length > 1" class="tl-group-bar" type="button" @click="seg.tools[0]._groupOpen = !seg.tools[0]._groupOpen">
                      <loading-outlined v-if="groupRunning(seg)" spin class="tool-ic tool-ic-run" />
                      <close-circle-outlined v-else-if="groupHasError(seg)" class="tool-ic tool-ic-err" />
                      <check-outlined v-else class="tool-ic tool-ic-ok" />
                      <span>执行了 {{ seg.tools.length }} 个操作</span>
                      <span class="tool-dur">· {{ groupDur(seg) }}</span>
                      <span class="tl-caret" :class="{ open: seg.tools[0]._groupOpen }"><caret-right-outlined /></span>
                    </button>
                    <div v-if="seg.tools.length === 1 || seg.tools[0]._groupOpen" class="tl-group-body" :class="{ solo: seg.tools.length === 1 }">
                      <div v-for="(t, ti) in seg.tools" :key="ti" class="tl-card" :class="{ run: t.status === 'start', err: t.status === 'error' }">
                        <button class="tl-card-head" type="button" @click="t._open = !t._open">
                          <loading-outlined v-if="t.status === 'start'" spin class="tool-ic tool-ic-run" />
                          <check-outlined v-else-if="t.status === 'done'" class="tool-ic tool-ic-ok" />
                          <close-circle-outlined v-else class="tool-ic tool-ic-err" />
                          <span class="tool-name" :title="toolDesc(t.name)">{{ toolLabel(t.name) }}</span>
                          <code v-if="toolBrief(t)" class="tl-brief">{{ toolBrief(t) }}</code>
                          <span v-if="t.attempts > 1" class="tool-dur">重试 {{ t.attempts - 1 }} 次</span>
                          <span v-if="t.status === 'start' && t.startAt" class="tool-dur">{{ liveToolDur(t.startAt) }}</span>
                          <span v-else-if="t.elapsedMs > 0" class="tool-dur">{{ toolDuration(t.elapsedMs) }}</span>
                          <span v-if="t.status === 'error'" class="tool-fail">失败</span>
                          <span class="tl-caret" :class="{ open: t._open }"><caret-right-outlined /></span>
                        </button>
                        <!-- askUser 问答记录：可折叠（默认展开），刷新/历史恢复同构 -->
                        <AskRecordCard v-if="t.name === 'askUser' && t.status !== 'start'" :t="t" />
                        <div v-if="t._open" class="tl-card-body">
                          <template v-if="t.args">
                            <div class="tl-io-label">入参</div>
                            <pre class="tl-io">{{ prettyIo(t.args) }}</pre>
                          </template>
                          <template v-if="t.status === 'start' ? t.output : (t.result || t.output)">
                            <div class="tl-io-label">{{ t.status === 'start' ? '实时输出' : (t.result ? '输出' : '输出（执行期）') }}</div>
                            <pre class="tl-io" :class="{ live: t.status === 'start' }">{{ liveOutput(t) }}</pre>
                          </template>
                          <template v-if="t.error">
                            <div class="tl-io-label">错误</div>
                            <pre class="tl-io tl-io-err">{{ t.error }}</pre>
                          </template>
                        </div>
                      </div>
                    </div>
                  </div>
                </template>
              </div>
              <AnswerBody v-else :content="m.content" :images="m.images" :sources="m.sources" :msg-index="i"
                          runnable :session-id="currentSessionId"
                          :streaming="m.loading && !m.failed" />
              <!-- 错误卡（独立于正文）：回答中断时保留已流出内容，这里给分类文案 + 重试 + 异常详情折叠 -->
              <div v-if="m.errorCard" class="msg-error-card" :class="{ net: m.errorCard.kind === 'interrupted' }">
                <div class="mec-head"><close-circle-outlined class="mec-ic" /> {{ errorBrief(m.errorCard.message, m.errorCard.kind) }}</div>
                <div v-if="m.errorCard.kind === 'interrupted'" class="mec-net-tip">
                  已生成的部分已保存。重新生成会重跑本轮并重新计费；若你刚切后台回来，这通常是系统挂起了连接。
                </div>
                <div class="mec-actions">
                  <button class="app-btn ghost small" @click="regenerate(i)"><redo-outlined /> 重新生成</button>
                </div>
                <details class="mec-detail">
                  <summary>异常详情</summary>
                  <pre class="mec-raw">{{ m.errorCard.message }}</pre>
                </details>
              </div>
              <!-- 气泡级进度行：一轮里同时只显示一处（互斥见 busyOf），工具/深度思考/审批由各自构件表达 -->
              <div v-if="busyOf(m)" class="busy-hint" :class="{ warn: busyOf(m).warn }">
                <loading-outlined spin />
                <span v-if="busyOf(m).text">{{ busyOf(m).text }}</span>
              </div>
              <!-- 自动派遣结果（路由过程对用户可见；每轮可不同） -->
              <!-- 与上方「由 X 回答」归属徽标同属内部排障信息：同一个开关（chat.retrievalDebugEnabled）控制 -->
              <div v-if="m.dispatched && debugDisplayVisible" class="dispatch-chip">
                <thunderbolt-outlined class="dispatch-ic" />
                <span>已派遣「{{ m.dispatched.name }}」</span>
                <span v-if="m.dispatched.fallback" class="dispatch-fallback">（路由未命中，按默认）</span>
                <span v-if="m.dispatched.description" class="dispatch-desc">{{ m.dispatched.description }}</span>
              </div>
              <!-- 会话内 @ 智能体（§4）：本轮由用户 @ 提及的智能体作答。用户主动指令，常显不进排障开关；
                   刷新后按落库归属与会话绑定比对恢复（见 switchSession），前后端口径一致 -->
              <div v-if="m.delegated" class="dispatch-chip delegated-chip">
                <AgentAvatar class="dispatch-ava" :agent="agentBadgeOf(agentList, m.agentId, m.delegated.name)" :size="14" />
                <span>由「{{ m.delegated.name }}」回答本轮</span>
                <span v-if="m.delegated.description" class="dispatch-desc">{{ m.delegated.description }}</span>
              </div>
              <!-- 历史恢复/正文重建回退：无 timeline（无法重建交错点），工具按终态列表折叠展示，卡片可展开看全文 -->
              <div v-if="m.role === 'ai' && m.toolCalls && m.toolCalls.length && !hasTimelineBlocks(m)" class="tool-status-list">
                <button class="tl-group-bar" type="button" @click="m._fbOpen = !m._fbOpen">
                  <loading-outlined v-if="toolRunning(m)" spin class="tool-ic tool-ic-run" />
                  <close-circle-outlined v-else-if="m.toolCalls.some(t => t.status === 'error')" class="tool-ic tool-ic-err" />
                  <check-outlined v-else class="tool-ic tool-ic-ok" />
                  <span>执行了 {{ toolCallsView(m.toolCalls).length }} 个操作</span>
                  <span class="tool-dur">· {{ fallbackDur(m) }}</span>
                  <span class="tl-caret" :class="{ open: m._fbOpen }"><caret-right-outlined /></span>
                </button>
                <div v-if="m._fbOpen" class="tl-group-body">
                  <div v-for="(t, ti) in toolCallsView(m.toolCalls)" :key="ti" class="tl-card" :class="{ run: t.status === 'start', err: t.status === 'error' }">
                    <button class="tl-card-head" type="button" @click="t._open = !t._open">
                      <loading-outlined v-if="t.status === 'start'" spin class="tool-ic tool-ic-run" />
                      <check-outlined v-else-if="t.status === 'done'" class="tool-ic tool-ic-ok" />
                      <close-circle-outlined v-else class="tool-ic tool-ic-err" />
                      <span class="tool-name" :title="toolDesc(t.name)">{{ toolLabel(t.name) }}</span>
                      <code v-if="toolBrief(t)" class="tl-brief">{{ toolBrief(t) }}</code>
                      <span v-if="t.attempts > 1" class="tool-dur">重试 {{ t.attempts - 1 }} 次</span>
                      <span v-if="t.status === 'start' && t.startAt" class="tool-dur">{{ liveToolDur(t.startAt) }}</span>
                      <span v-else-if="t.elapsedMs > 0" class="tool-dur">{{ toolDuration(t.elapsedMs) }}</span>
                      <span v-if="t.status === 'error'" class="tool-fail">失败</span>
                      <span class="tl-caret" :class="{ open: t._open }"><caret-right-outlined /></span>
                    </button>
                    <!-- askUser 问答记录：可折叠（默认展开），刷新/历史恢复同构 -->
                    <AskRecordCard v-if="t.name === 'askUser' && t.status !== 'start'" :t="t" />
                    <div v-if="t._open" class="tl-card-body">
                      <template v-if="t.args">
                        <div class="tl-io-label">入参</div>
                        <pre class="tl-io">{{ prettyIo(t.args) }}</pre>
                      </template>
                      <template v-if="t.status === 'start' ? t.output : (t.result || t.output)">
                        <div class="tl-io-label">{{ t.status === 'start' ? '实时输出' : (t.result ? '输出' : '输出（执行期）') }}</div>
                        <pre class="tl-io" :class="{ live: t.status === 'start' }">{{ liveOutput(t) }}</pre>
                      </template>
                      <template v-if="t.error">
                        <div class="tl-io-label">错误</div>
                        <pre class="tl-io tl-io-err">{{ t.error }}</pre>
                      </template>
                    </div>
                  </div>
                </div>
              </div>
              <!-- 计划模式（人在回路）：执行计划卡——只读展示（流式产出 → 待确认 → 已批准/未批准/已被取代）。
                   确认/继续对话/取消在底部输入框区的计划确认栏（与提问卡同形，见 pendingPlan / planp-*）；
                   长计划收成紧凑预览（渐隐 + 查看完整计划），历史遗留的多版计划收在「修改记录」折叠里；
                   刷新/换设备后由 hydratePendingPlans 按落库记录重建（restored 卡同样可裁决） -->
              <div v-if="m.planCard" class="plan-card" :class="'plan-' + m.planCard.status">
                <div class="plan-card-head">
                  <ordered-list-outlined class="plan-card-ic" />
                  <span class="plan-card-title">执行计划</span>
                  <span v-if="m.planCard.status === 'drafting'" class="plan-state st-drafting">生成中…</span>
                  <span v-else-if="m.planCard.status === 'pending'" class="plan-state st-pending">待确认</span>
                  <span v-else-if="m.planCard.status === 'approved'" class="plan-state st-ok">已批准</span>
                  <span v-else-if="m.planCard.status === 'superseded'" class="plan-state st-superseded">已被取代</span>
                  <span v-else class="plan-state st-rejected">未批准 · 已停止本轮</span>
                  <span v-if="m.planCard.auto" class="plan-auto-tag"
                        title="你没开计划模式，但这条消息适合先出计划——本轮按意图自动开启，确认后才执行">自动开启</span>
                  <button v-if="planChain(m.planCard).length > 1" type="button" class="plan-hist-toggle"
                          :title="m.planCard.histOpen ? '收起修改记录' : '查看各版计划与你的修改意见'"
                          @click="m.planCard.histOpen = !m.planCard.histOpen">
                    已修改 {{ planChain(m.planCard).length - 1 }} 次
                    <up-outlined v-if="m.planCard.histOpen" /><down-outlined v-else />
                  </button>
                </div>
                <div v-if="m.planCard.status !== 'rejected'" class="plan-wrap"
                     :class="{ 'is-clamped': planNeedsFold(m.planCard.plan) && !m.planCard.open }">
                  <div class="md plan-body" v-html="renderMd(m.planCard.plan || '（计划为空）', [])"></div>
                  <button v-if="planNeedsFold(m.planCard.plan)" type="button" class="plan-more" @click="m.planCard.open = !m.planCard.open">
                    {{ m.planCard.open ? '收起' : '查看完整计划' }}
                  </button>
                </div>
                <!-- 修改记录：同轮内每次「退回重出」的版本链（v1 模型原稿 → 你的意见 → v2 …），默认收起不刷屏 -->
                <div v-if="m.planCard.histOpen" class="plan-hist">
                  <div v-for="(v, vi) in planChain(m.planCard)" :key="vi" class="plan-hist-item">
                    <div v-if="v.feedback" class="plan-hist-fb">你的意见：{{ v.feedback }}</div>
                    <div class="plan-hist-no">
                      第 {{ vi + 1 }} 版{{ planVersionTag(m.planCard, vi, planChain(m.planCard).length) }}
                    </div>
                    <div class="plan-wrap" :class="{ 'is-clamped': planNeedsFold(v.plan) && !v.open }">
                      <div class="md plan-body" v-html="renderMd(v.plan || '（计划为空）', [])"></div>
                      <button v-if="planNeedsFold(v.plan)" type="button" class="plan-more" @click="v.open = !v.open">
                        {{ v.open ? '收起' : '查看完整计划' }}
                      </button>
                    </div>
                  </div>
                </div>
              </div>
              <!-- 工具执行审批（人在回路）：智能体开启"执行前确认"后，有副作用工具（沙盒/MCP）执行前需用户批准 -->
              <div v-if="m.approval" class="approval-card">
                <div class="approval-title" :title="toolDesc(m.approval.tool)"><exclamation-circle-outlined /> 智能体请求执行工具「{{ toolLabel(m.approval.tool) }}」</div>
                <pre v-if="m.approval.args" class="approval-args">{{ m.approval.args }}</pre>
                <div class="approval-actions">
                  <button class="app-btn small" :disabled="m.approval.busy" @click="resolveApproval(m, true)">批准执行</button>
                  <button class="app-btn ghost small" :disabled="m.approval.busy" @click="resolveApproval(m, false)">拒绝</button>
                  <span class="approval-hint">未处理将在 {{ Math.round((m.approval.timeoutMs || 120000) / 1000) }} 秒后按拒绝处理</span>
                </div>
              </div>
              <!-- 智能体提问（askUser）的「待答」态不再渲染在气泡里：桌面壳把底部聊天输入框整块
                   替换成提问面板（见 pendingAsk / askp-*），答复后问答记录以工具卡形态留在本气泡 -->
              <!-- 产物统一沉底展示（不再按生成时刻插在时间线中间，避免把回答切碎） -->
              <div v-if="m.role === 'ai' && m.artifacts && m.artifacts.length" class="artifact-list">
                <a v-for="(a, ai) in m.artifacts" :key="ai" class="artifact-item"
                   :href="resolveImg(a.url)" :download="a.filename" :title="'下载 ' + a.filename">
                  <file-text-outlined class="artifact-icon" />
                  <span class="artifact-name">{{ a.filename }}</span>
                  <span v-if="a.description" class="artifact-desc">{{ a.description }}</span>
                  <download-outlined class="artifact-dl" />
                </a>
              </div>
              <div v-if="m.role === 'ai' && m.degradations && m.degradations.length" class="degradation-bar">
                <exclamation-circle-outlined style="margin-right:6px" />
                <span v-for="(d, di) in m.degradations" :key="di" class="degradation-item">{{ d.msg }}</span>
              </div>
              <div v-if="m.role === 'ai' && m.warnMsg" class="degradation-bar">{{ m.warnMsg }}</div>
              <!-- 历史压缩提示（信息条，蓝色区别于黄色警告）：完整记录仍在会话里，往前翻可见 -->
              <div v-if="m.role === 'ai' && m.tokens && m.tokens.historyCompressed > 0" class="ctx-compress-bar">
                <compress-outlined style="margin-right:6px" />
                已把 {{ m.tokens.historyCompressed }} 轮早期对话压缩为摘要（完整记录仍可在会话中回看）
              </div>
              <div v-if="m.role === 'ai' && (retrievalLineTitle(m) || toolSearchQueries(m).length || (m.sources && m.sources.length))" class="retrieval-merged">
                <div class="retrieval-line" @click="m.rtOpen = !m.rtOpen">
                  <template v-if="retrievalLineTitle(m)">{{ retrievalLineTitle(m) }}<template v-if="m.retrieved && m.tokens && m.tokens.hits != null && m.tokens.hits > 0 && m.tokens.hits !== m.retrieved.refs">（{{ m.tokens.hits }} 段填入上下文）</template></template>
                  <caret-right-outlined class="tl-caret" :class="{ open: m.rtOpen }" />
                </div>
                <div v-if="m.rtOpen" class="retrieval-detail">
                  <div v-if="m.retrieved?.terms?.length" class="rt-terms">检索词：{{ (m.retrieved.terms || []).join('、') }}</div>
                  <div v-if="toolSearchQueries(m).length" class="rt-terms rt-tool-terms">
                    <span class="rt-tool-tag">精确检索</span>{{ toolSearchQueries(m).join('；') }}
                  </div>
                  <div v-for="(s, si) in (m.sources || [])" :key="si" class="rt-ref"
                       :title="externalOrigin(s) ? '点击查看来源摘要与原网页' : '点击查看原文'" @click="openSource(s)">
                    <span class="rt-ref-tag">[{{ s.ref }}]</span>
                    <span v-if="s.origin === 'WEB'" class="rt-ref-web">联网</span><span v-else-if="s.origin === 'MCP'" class="rt-ref-web" title="MCP 工具返回的来源">MCP</span>{{ sourceName(s) }}
                    <div v-if="s.snippet" class="rt-snip">{{ s.snippet }}</div>
                  </div>
                </div>
              </div>
              <!-- 子智能体编排（仅【委派模式】显示：分支是有名字有职责的子智能体，信息才有用。
                   多视角模式不显示——那只是把原问题换个问法，是实现细节，对用户没有信息价值） -->
              <div v-if="m.role === 'ai' && subagentCard(m)" class="subagent-panel">
                <div class="subagent-head" @click="toggleSubagents(m)">
                  <span class="subagent-title">
                    <robot-outlined class="sa-head-ic" />
                    {{ subagentCard(m).title }}
                  </span>
                  <span class="subagent-sum">
                    <span v-if="subagentCard(m).routeNote" class="sa-route-note">{{ subagentCard(m).routeNote }}</span>
                    {{ subagentCard(m).done }}/{{ subagentCard(m).total }} 完成
                    <caret-right-outlined class="tl-caret" :class="{ open: m.saOpen }" />
                  </span>
                </div>
                <div v-if="m.saOpen" class="subagent-list">
                  <div v-for="b in subagentCard(m).branches" :key="b.id" class="subagent-row" :class="'st-' + (b.status || 'running')">
                    <div class="sa-row-head">
                      <span class="subagent-state">
                        <a-spin v-if="b.status === 'running'" size="small" />
                        <check-outlined v-else-if="b.status === 'done'" class="sa-ok" />
                        <close-circle-outlined v-else class="sa-err" />
                      </span>
                      <span class="subagent-name">{{ b.name }}</span>
                      <span class="sa-status-tag" :class="'st-' + (b.status || 'running')">
                        {{ b.status === 'running' ? '运行中' : (b.status === 'done' ? '已完成' : '失败') }}
                      </span>
                      <span v-if="b.status === 'done'" class="subagent-hits">{{ b.hits }} 块 · {{ fmtDuration(b.elapsedMs) }}</span>
                    </div>
                    <div v-if="b.description" class="sa-desc">{{ b.description }}</div>
                    <div v-if="subagentCard(m).reasons[b.name]" class="sa-reason">派它：{{ subagentCard(m).reasons[b.name] }}</div>
                    <div class="sa-bar"><div class="sa-bar-fill" :class="'st-' + (b.status || 'running')" :style="{ width: barWidth(subagentCard(m), b) }" /></div>
                    <div v-if="b.digest" class="sa-digest">{{ b.digest }}</div>
                  </div>
                </div>
              </div>
              <!-- 按需委派判定"都不需要咨询"时的说明（否则用户会疑惑为什么没有编排过程） -->
              <div v-if="m.role === 'ai' && !subagentCard(m) && m.subagentRoute && m.subagentRoute.candidates > 0 && m.subagentRoute.picked === 0"
                   class="subagent-skip">
                已从 {{ m.subagentRoute.candidates }} 个候选择手中筛选：本问题无需咨询任何助手，直接作答
              </div>
              <div v-if="m.role === 'ai' && m.related && m.related.length" class="related">
                <span class="related-label">接下来可以：</span>
                <span v-for="(q, qi) in m.related" :key="qi" class="related-tag" @click="ask(q)">{{ q }}</span>
              </div>
            </div>
            <div v-if="m.role === 'ai' && !m.loading && (m.messageId || m.time)" class="fb-row">
              <!-- 重新生成的多版本切换器：本会话内用内存版本（versions）即时切换；
                   刷新后/编辑重发用持久分支（variantCount）走后端切换，旧版本按保留期留存 -->
              <div v-if="(m.versions && m.versions.length > 1) || m.variantCount > 1" class="ver-switch"
                   title="这一回答有多个版本，可来回切换（旧版本按保留期清理）">
                <button class="ver-btn" :disabled="!canSwitchPrev(m)" @click="switchBranch(i, -1)">‹</button>
                <span class="ver-idx">{{ verLabel(m) }}</span>
                <button class="ver-btn" :disabled="!canSwitchNext(m)" @click="switchBranch(i, 1)">›</button>
              </div>
              <template v-if="m.messageId">
                <a-tooltip title="复制"><button class="app-icon-btn" @click="copyAnswer(i)"><copy-outlined /></button></a-tooltip>
                <a-tooltip :title="m.fb != null ? '已评价' : '有帮助'"><button class="app-icon-btn" :class="{ 'fb-active': m.fb === 1 }" :disabled="m.fb != null" @click="openFeedback(m, 1)"><like-outlined /></button></a-tooltip>
                <a-tooltip :title="m.fb != null ? '已评价' : '没帮助'"><button class="app-icon-btn" :class="{ 'fb-active': m.fb === 0 }" :disabled="m.fb != null" @click="openFeedback(m, 0)"><dislike-outlined /></button></a-tooltip>
                <a-tooltip title="重新生成"><button class="app-icon-btn" :disabled="loading" @click="regenerate(i)"><reload-outlined /></button></a-tooltip>
                <a-dropdown :trigger="['hover', 'click']">
                  <button class="app-icon-btn" title="更多"><more-outlined /></button>
                  <template #overlay>
                    <a-menu @click="({ key }) => onMoreAction(key, i)">
                      <a-menu-item v-if="debugEntryVisible" key="debug"><bug-outlined style="margin-right:8px" />检索调试</a-menu-item>
                      <!-- 加入评测集 = 差评回流固化到检索评测集（/api/ai/eval 仅管理员可用），属调参排障动作：
                           与「检索调试」同一开关（chat.retrievalDebugEnabled）控制，不给普通用户露出必 403 的入口 -->
                      <a-menu-item v-if="debugEntryVisible && m.sources && m.sources.length" key="addEval"><dislike-outlined style="margin-right:8px" />加入评测集</a-menu-item>
                      <a-menu-item key="export"><download-outlined style="margin-right:8px" />导出 Markdown</a-menu-item>
                      <a-menu-item key="deleteRound" style="color:#cf1322"><delete-outlined style="margin-right:8px" />删除本轮对话</a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </template>
              <a-tooltip v-if="m.tokens" :title="`上下文 ${m.tokens.context} / 预算 ${m.tokens.budget} · 输出 ${m.tokens.output} tokens${m.tokens.outputIsReal ? '（网关实测）' : '（估算）'}`">
                <span class="msg-tokens">{{ m.tokens.outputIsReal ? '' : '≈' }}{{ fmtTokens(m.tokens.total) }} tokens</span>
              </a-tooltip>
              <span v-if="m.time" class="msg-time-inline">{{ fmtMsgTime(m.time) }}</span>
            </div>
            <!-- 叫停且本轮没产出任何回答：终止事实记在「这一问」上（不造一条空回答气泡），刷新/换端从库里带回 -->
            <div v-if="m.role === 'user' && m.endReason === 'stopped'" class="msg-stopped"
                 title="已按你的要求停止这一轮，智能体没有产出回答">
              <pause-circle-outlined /> 本轮已停止 · 未产生回答
            </div>
            <div v-if="m.role === 'user'" class="msg-edit-row">
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
                  <div v-if="f.paste" class="pending-file paste-card" :class="{ err: !!f.error }"
                       :title="f.error || '点击查看粘贴的全文'" @click="openPasteView(f)">
                    <span class="paste-card-ic"><file-text-outlined /></span>
                    <span class="paste-card-txt">
                      <span class="paste-card-name">{{ pasteTitle(f.name) }}</span>
                      <span class="paste-card-sub">{{ pasteSub(f) }}</span>
                    </span>
                    <span class="pending-file-del" @click.stop="removeEditAtt(fi)">×</span>
                  </div>
                  <div v-else class="pending-file" :class="{ err: !!f.error }" :title="f.error || f.name">
                    <file-text-outlined class="pending-file-ic" />
                    <span class="pending-file-name">{{ f.name }}</span>
                    <span v-if="f.uploading" class="pending-file-size">上传中…</span>
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
                              : (mm.type === 'agent' ? '本轮改由该智能体作答（会话绑定不变）' : '该文档内容直接带入本轮上下文')">
                  <robot-outlined v-if="mm.type === 'agent'" class="at-chip-ic" />
                  <database-outlined v-else-if="mm.type === 'kb'" class="at-chip-ic" />
                  <file-text-outlined v-else class="at-chip-ic" />
                  <span class="at-chip-name">{{ mm.name || mm.id }}</span>
                  <span class="at-chip-del" title="移除该引用" @click="removeEditMention(mi2)">×</span>
                </span>
              </div>
              <div class="msg-inline-edit-actions">
                <button class="app-icon-btn" title="添加图片或附件" @click="pickEditFiles"><paper-clip-outlined /></button>
                <span class="msg-inline-edit-hint"><info-circle-outlined /> 编辑后将从此处重新开始对话，已有产物不会被删除</span>
                <button class="app-btn ghost" @click="cancelEdit">取消</button>
                <button class="app-btn" :disabled="editingBusy || (!editingText.trim() && !editImgs.length && !editAtts.length)"
                        @click="confirmEdit">发送</button>
              </div>
              <input :ref="setEditAttachInputRef" type="file" multiple style="display:none" @change="onEditAttachChange" />
            </div>
          </div>
        </div>
        <!-- 手动压缩结果条（/compact）：纯本地状态、不落库；下一轮发送即退场，
             避免它躺在最新回答下方被读成「本轮压缩了 N 轮」（那是气泡上 ctx-compress-bar 的语义） -->
        <div v-if="compactNotice && compactNotice.sid === currentSessionId" class="ctx-compress-bar compact-notice">
          <compress-outlined class="cn-ic" />
          <span class="cn-text">
            已把 {{ compactNotice.turns }} 轮早期对话压缩为摘要，最近 {{ compactNotice.keepTurns }} 轮保持原样
            <template v-if="compactNotice.partial">（会话很长，本次只压了最早的几批，可再次执行 /compact 继续）</template>
          </span>
          <button v-if="compactNotice.summary" class="app-btn ghost small" @click="openCompactSummary">查看摘要</button>
          <button class="app-icon-btn" title="不再显示" @click="compactNotice = null"><close-outlined /></button>
        </div>
        <!-- 尾随留白：本轮问题下方补足一屏，使贴底落点=问题置顶（回答长过一屏后归零，恢复正常贴底跟尾） -->
        <div v-if="tailSpacer > 0" class="tail-spacer" :style="{ height: tailSpacer + 'px' }" aria-hidden="true"></div>
        <div v-if="!stickToBottom && messages.length" class="jump-latest" title="回到底部" @click.stop="scrollForce">↓</div>
      </div>

      <!-- 输入区：大圆角卡片（文本上、工具行下） -->
      <div class="input" @dragenter.prevent="onDragEnter" @dragover.prevent @dragleave.prevent="onDragLeave" @drop.prevent="onDropFiles">
        <div v-if="dragOver" class="drop-overlay">松开以添加图片或附件</div>
        <div v-if="pendingFiles.length" class="pending-files">
          <template v-for="(f, fi) in pendingFiles" :key="fi">
            <div v-if="f.paste" class="pending-file paste-card" :class="{ err: !!f.error }"
                 :title="f.error || '点击查看粘贴的全文'" @click="openPasteView(f)">
              <span class="paste-card-ic"><file-text-outlined /></span>
              <span class="paste-card-txt">
                <span class="paste-card-name">{{ pasteTitle(f.name) }}</span>
                <span class="paste-card-sub">{{ pasteSub(f) }}</span>
              </span>
              <span class="pending-file-del" @click.stop="removePendingFile(fi)">×</span>
            </div>
            <div v-else class="pending-file" :class="{ err: !!f.error }" :title="f.error || f.name">
              <file-text-outlined class="pending-file-ic" />
              <span class="pending-file-name">{{ f.name }}</span>
              <span v-if="f.uploading" class="pending-file-size">上传中…</span>
              <span v-else-if="f.error" class="pending-file-size">上传失败</span>
              <span v-else class="pending-file-size">{{ fmtSize(f.size) }}</span>
              <span class="pending-file-del" @click.stop="removePendingFile(fi)">×</span>
            </div>
          </template>
        </div>
        <div v-if="pickedSkills.length" class="at-chips">
          <span v-for="n in pickedSkills" :key="n" class="at-chip skill-chip">
            <span class="skill-chip-ava" :style="skillAvaStyle(n)">{{ n.slice(0, 1) }}</span>
            <span class="at-chip-name">{{ n }}</span>
            <span class="at-chip-del" title="移除该技能" @click="toggleSkill(n)">×</span>
          </span>
        </div>
        <div v-if="planModeOn" class="at-chips">
          <span class="at-chip plan-chip" title="每轮先产出执行计划，等你批准或修改后才开始回答">
            <ordered-list-outlined class="at-chip-ic" />
            <span class="at-chip-name">计划模式</span>
            <span class="at-chip-del" title="关闭计划模式" @click="setPlanMode(false)">×</span>
          </span>
          <span class="at-chips-note">每轮先出计划等确认</span>
        </div>
        <div v-if="pendingMentions.length" class="at-chips">
          <span v-for="(mm, mi) in pendingMentions" :key="mm.type + ':' + mm.id" class="at-chip mention-chip"
                :class="'mention-' + mm.type"
                :title="mm.type === 'kb' ? '本轮检索收窄到该知识库'
                        : (mm.type === 'agent' ? '本轮改由该智能体作答（会话绑定不变）' : '该文档内容直接带入本轮上下文')">
            <database-outlined v-if="mm.type === 'kb'" class="at-chip-ic" />
            <robot-outlined v-else-if="mm.type === 'agent'" class="at-chip-ic" />
            <file-text-outlined v-else class="at-chip-ic" />
            <span class="at-chip-name">{{ mm.name || mm.id }}</span>
            <span class="at-chip-del" title="移除该引用" @click="removeMention(mi)">×</span>
          </span>
          <span class="at-chips-note">引用只对本轮生效</span>
        </div>
        <div v-if="pendingHistoryRefs.length" class="at-chips">
          <span v-for="(hr, hi) in pendingHistoryRefs" :key="hr.messageId" class="at-chip hist-chip"
                title="该条历史问答会作为本轮上下文带给模型">
            <history-outlined class="at-chip-ic" />
            <span class="at-chip-name">{{ histChipLabel(hr) || hr.messageId }}</span>
            <span class="at-chip-del" title="移除该引用" @click="removeHistoryRef(hi)">×</span>
          </span>
          <span class="at-chips-note">历史引用只对本轮生效</span>
        </div>
        <div v-if="pendingImages.length" class="pending-imgs">
          <div v-for="(p, pi) in pendingImages" :key="pi" class="pending-img">
            <img :src="p.dataUrl" alt="待发送图片" @click="previewPendingImage(pi)" />
            <span class="pending-del" @click.stop="removePendingImage(pi)">×</span>
          </div>
        </div>
        <div v-if="!pendingAsk && !pendingPlan" class="input-box">
          <!-- 待插话条目：生成中按发送先挂在这里（不直接送达），点「插话」才随下一个工具步送出。
               送出前可改可删；送出后只留状态——后端队列没有撤回接口，给个假删除是骗人 -->
          <div v-if="steersPending.length" class="steer-list">
            <div v-for="(s, si) in steersPending" :key="si" class="steer-item" :class="{ done: s.sent || s.deferred }">
              <span class="steer-ic">↳</span>
              <span class="steer-txt">{{ s.text }}</span>
              <span v-if="steerExtras(s)" class="steer-extra">{{ steerExtras(s) }}</span>
              <span v-if="s.deferred" class="steer-state">本轮结束后自动发出</span>
              <span v-else-if="s.sent" class="steer-state">已插入 · 随下一个工具步生效</span>
              <button v-else class="steer-act primary" title="纯文字随下一个工具步送达模型；带材料的这轮塞不进去，改为本轮结束自动发出"
                      @click="insertSteer(si)">插话</button>
              <button v-if="!s.sent && !s.deferred" class="steer-act" title="取回输入框改" @click="editSteer(si)"><edit-outlined /></button>
              <button v-if="!s.sent && !s.deferred" class="steer-act" title="丢弃这条" @click="dropSteer(si)"><delete-outlined /></button>
            </div>
          </div>
          <!-- @ 引用候选面板（敲 @ 唤起）：kb=收窄检索范围 / doc=强制带入内容 -->
          <div v-if="mentionOpen" class="mention-panel">
            <div class="mention-head">
              <span class="mention-head-tag">@</span>
              <span class="mention-head-word" :class="{ dim: !mentionQuery }">{{ mentionQuery || '输入以筛选知识库、文档或智能体' }}</span>
              <button class="app-icon-btn" title="关闭" @click="closeMentionPanel"><close-outlined /></button>
            </div>
            <div class="mention-tabs">
              <button class="mention-tab" :class="{ on: mentionTab === 'kb' }" @click="switchMentionTab('kb')">
                <database-outlined /> 知识库 {{ mentionKbs.length }}
              </button>
              <button class="mention-tab" :class="{ on: mentionTab === 'doc' }" @click="switchMentionTab('doc')">
                <file-text-outlined /> 文档 {{ mentionDocs.length }}
              </button>
              <button class="mention-tab" :class="{ on: mentionTab === 'agent' }" @click="switchMentionTab('agent')">
                <robot-outlined /> 智能体 {{ mentionAgents.length }}
              </button>
            </div>
            <div ref="mentionListEl" class="mention-list">
              <div v-if="mentionLoading" class="mention-empty">加载中…</div>
              <template v-else-if="mentionTab === 'kb'">
                <div v-for="(k, ki) in mentionKbFiltered" :key="k.id" class="mention-item"
                     :class="{ on: isMentioned('kb', k.id), hi: mentionHi === ki }" @click="pickMentionByClick('kb', k)">
                  <span class="mention-ava"><database-outlined /></span>
                  <div class="mention-text">
                    <span class="mention-name">{{ k.name }}</span>
                    <span class="mention-desc">{{ k.desc || (k.docCount ? k.docCount + ' 篇文档' : '') }}</span>
                  </div>
                  <check-outlined v-if="isMentioned('kb', k.id)" class="mention-check" />
                </div>
                <div v-if="!mentionKbFiltered.length" class="mention-empty">
                  {{ mentionKbs.length ? '没有匹配的知识库' : '没有可见的知识库' }}
                </div>
              </template>
              <template v-else-if="mentionTab === 'agent'">
                <div v-for="(a, ai) in mentionAgentFiltered" :key="a.id" class="mention-item"
                     :class="{ on: isMentioned('agent', a.id), hi: mentionHi === ai }" @click="pickMentionByClick('agent', a)">
                  <AgentAvatar class="mention-ava-agent" :agent="a" :size="22" />
                  <div class="mention-text">
                    <span class="mention-name">{{ a.name }}</span>
                    <span class="mention-desc">{{ a.desc || '本轮改由该智能体作答' }}</span>
                  </div>
                  <check-outlined v-if="isMentioned('agent', a.id)" class="mention-check" />
                </div>
                <div v-if="!mentionAgentFiltered.length" class="mention-empty">
                  {{ mentionAgents.length ? '没有匹配的智能体' : '没有可见的智能体' }}
                </div>
              </template>
              <template v-else>
                <div v-for="(d, di) in mentionDocFiltered" :key="d.id" class="mention-item"
                     :class="{ on: isMentioned('doc', d.id), hi: mentionHi === di }" @click="pickMentionByClick('doc', d)">
                  <span class="mention-ava"><file-text-outlined /></span>
                  <div class="mention-text">
                    <span class="mention-name">{{ d.fileName }}</span>
                    <span class="mention-desc">{{ mentionDocStatus(d) }}</span>
                  </div>
                  <check-outlined v-if="isMentioned('doc', d.id)" class="mention-check" />
                </div>
                <div v-if="!mentionDocFiltered.length" class="mention-empty">
                  {{ mentionDocs.length ? '没有匹配的文档' : '没有可见的文档' }}
                </div>
              </template>
            </div>
            <div class="mention-foot">
              ↑↓ 选择 · Enter 确认 · Esc 关闭　|　@ 知识库 = 本轮检索只在这些库里找；@ 文档 = 该文档内容直接带入本轮上下文；@ 智能体 = 本轮改由它作答（会话绑定不变）
            </div>
          </div>
          <!-- / 快捷命令面板（敲 / 唤起）：模板=往输入框插入常用问法框架；操作=会话级动作立即执行 -->
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
                <span class="slash-kind" :class="c.kind === 'tpl' ? 'k-tpl' : 'k-act'">{{ c.kind === 'tpl' ? '模板' : '操作' }}</span>
              </div>
              <div v-if="!slashFiltered.length" class="mention-empty">没有匹配的命令</div>
            </div>
            <div class="mention-foot">↑↓ 选择 · Enter 确认 · Esc 关闭　|　/ 模板 = 插入常用问法框架（可再编辑）；/ 操作 = 立即执行；命令可附要求（如 /compact 保留结论）</div>
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
            <div class="mention-foot">↑↓ 选择 · Enter 确认 · Esc 关闭　|　# 勾选的历史问答作为本轮上下文带给模型（只对本轮生效，最多 10 条）</div>
          </div>
          <!-- 「+」面板（附件 / 技能 / 历史 / 命令 四页签）：鼠标流入口——勾选与筛选都在面板内完成，
               不往正文插触发字符（那是键盘流 @ / # 的做法）。行式交互与样式复用 .mention-panel / .mention-item -->
          <div v-if="addOpen" class="mention-panel" @keydown.esc.stop="closeAddPanel">
            <div class="mention-head add-head">
              <button v-for="t in ADD_TABS" :key="t.key" class="mention-tab" :class="{ on: addTab === t.key }"
                      type="button" @click="addTab = t.key">
                <component :is="t.icon" /> {{ t.label }}<span v-if="t.count()" class="add-tab-n">{{ t.count() }}</span>
              </button>
              <button class="app-icon-btn add-close" title="关闭" @click="closeAddPanel"><close-outlined /></button>
            </div>
            <!-- 附件：选完即收面板——面板正盖着输入框上方的附件 chip，收起来让用户看到已加上 -->
            <template v-if="addTab === 'file'">
              <div class="mention-list">
                <div class="mention-item" @click="pickAttachments">
                  <span class="mention-ava"><paper-clip-outlined /></span>
                  <div class="mention-text">
                    <span class="mention-name">选择文件</span>
                    <span class="mention-desc">上传后随本轮提问一起发送，解析后供模型阅读</span>
                  </div>
                </div>
                <div class="mention-item" @click="pickAddImages">
                  <span class="mention-ava"><picture-outlined /></span>
                  <div class="mention-text">
                    <span class="mention-name">选择图片</span>
                    <span class="mention-desc">压缩后随本轮提问（支持多选，最多 5 张）</span>
                  </div>
                </div>
              </div>
              <div class="mention-foot">支持 PDF / Word / Excel / PPT 与文本、代码文件；单个不超过 15MB（一次最多 5 个）　|　也可直接把文件拖进来</div>
            </template>
            <!-- 技能 -->
            <template v-else-if="addTab === 'skill'">
              <div class="mention-list">
                <div v-if="!skillList.length" class="mention-empty">还没有可用技能，管理员可在「设置 → 技能」中安装</div>
                <div v-for="s in skillList" :key="s.name" class="mention-item"
                     :class="{ on: pickedSkills.includes(s.name) }" @click="toggleSkill(s.name)">
                  <span class="mention-ava skill-ava" :style="skillAvaStyle(s.name)">{{ (s.name || '技').slice(0, 1) }}</span>
                  <div class="mention-text">
                    <span class="mention-name">{{ s.name }}</span>
                    <span v-if="s.description" class="mention-desc">{{ s.description }}</span>
                  </div>
                  <check-outlined v-if="pickedSkills.includes(s.name)" class="mention-check" />
                </div>
              </div>
              <div class="mention-foot">选用的技能对本轮生效，下一轮自动取消（一次最多 3 个）</div>
            </template>
            <!-- 计划模式：入口开关（持久化）。开启后每轮先产出执行计划，由用户批准/修改/取消后才正式回答。
                 持久模式用开关表达（不套技能/历史那种「本轮勾选」的语汇）：行可点、开关可拨，
                 开启态只变开关自身，不再整行变色/头像实心——那套是「列表里挑中了」的语义 -->
            <template v-else-if="addTab === 'plan'">
              <div class="mention-list">
                <div class="mention-item plan-switch-row" @click="setPlanMode(!planModeOn)">
                  <span class="mention-ava"><ordered-list-outlined /></span>
                  <div class="mention-text">
                    <span class="mention-name">计划模式</span>
                    <span class="mention-desc">每轮先出执行计划，你批准或修改后才开始回答</span>
                  </div>
                  <span class="plan-switch-wrap" @click.stop>
                    <a-switch :checked="planModeOn" size="small" @change="setPlanMode" />
                  </span>
                </div>
              </div>
            </template>
            <!-- 历史引用：勾选不关面板（多选），与键盘流 # 面板同源同一份候选 -->
            <template v-else-if="addTab === 'hist'">
              <div class="add-filter-row">
                <input v-model="addHistQuery" class="add-filter" type="search" placeholder="筛选历史问答…" />
              </div>
              <div class="mention-list">
                <div v-for="m in addHistCandidates" :key="m.messageId" class="mention-item"
                     :class="{ on: isHistPicked(m.messageId) }" @click="toggleHistoryRef(m)">
                  <span class="mention-ava" :class="m.role === 'user' ? 'hist-ava-q' : 'hist-ava-a'">
                    {{ m.role === 'user' ? '问' : '答' }}
                  </span>
                  <div class="mention-text">
                    <span class="mention-name">{{ histItemTitle(m) }}</span>
                    <span class="mention-desc">{{ m.role === 'user' ? '你的提问' : 'AI 的回答' }}{{ m.time ? ' · ' + fmtMsgTime(m.time) : '' }}</span>
                  </div>
                  <check-outlined v-if="isHistPicked(m.messageId)" class="mention-check" />
                </div>
                <div v-if="!addHistCandidates.length" class="mention-empty">
                  {{ histPool.length ? '没有匹配的历史问答' : '本会话还没有可引用的历史问答' }}
                </div>
              </div>
              <div class="mention-foot">勾选的历史问答作为本轮上下文带给模型（只对本轮生效，最多 10 条）　|　也可在输入框输入 # 唤起</div>
            </template>
            <!-- 快捷命令：模板插入输入框可再编辑（收面板），操作立即执行 -->
            <template v-else>
              <div class="mention-list">
                <div v-for="c in slashCommands" :key="c.key" class="mention-item"
                     :class="{ 'mi-off': loading }" @click="runCmdFromAddPanel(c)">
                  <span class="mention-ava"><component :is="c.icon" /></span>
                  <div class="mention-text">
                    <span class="mention-name"><code v-if="c.cmd" class="slash-cmd">/{{ c.cmd }}</code>{{ c.name }}</span>
                    <span class="mention-desc">{{ c.desc }}</span>
                  </div>
                  <span class="slash-kind" :class="c.kind === 'tpl' ? 'k-tpl' : 'k-act'">{{ c.kind === 'tpl' ? '模板' : '操作' }}</span>
                </div>
              </div>
              <div class="mention-foot">模板 = 插入常用问法框架（可再编辑）；操作 = 立即执行　|　也可在输入框输入 / 唤起</div>
            </template>
          </div>
          <a-textarea ref="textareaRef" v-model:value="text" :placeholder="composerPlaceholder"
                      :auto-size="{ minRows: 1, maxRows: 6 }" class="input-area"
                      @paste="onComposerPaste"
                      @keydown="onInputKeydown" @input="syncPanelQuery" @click="syncPanelQuery" />
          <!-- 工具条。窄屏加 as-mobile 类拿「flex-wrap:nowrap」约束 —— 否则模型名一变长
               （deepseek-flash），整条换行会把「思考」按钮压成竖排两字（图里的实际症状）。
               类切换而非两套模板：内部内容两种形态完全相同，不复制。
               刻意不套包装组件：曾用 <component :is> + 具名 slot 包裹，结果内容全被丢弃 -->
          <div :class="isNarrow ? 'input-toolbar as-mobile' : 'input-toolbar'">
            <div class="toolbar-left">
              <a-dropdown v-model:open="agentPickerOpen" :trigger="['click']" placement="topLeft">
                <button class="agent-pill" :class="{ on: !!currentAgentId, open: agentPickerOpen, locked: agentLocked, 'as-icon': isNarrow }"
                        :title="agentLocked
                          ? `本会话已绑定「${currentAgentName}」，切换智能体会开启新会话`
                          : '选择智能体：按预设覆盖提示词 / 知识库范围 / 能力（模型在右侧选择）'">
                  <AgentAvatar v-if="currentAgent" :agent="currentAgent" :size="16" />
                  <!-- 窄屏只留头像：智能体名（如「自动派遣」）+ 模型名会把工具条撑破，
                       完整名称由 title 与下拉面板承载 -->
                  <span v-if="!isNarrow" class="agent-pill-name">{{ currentAgentName }}</span>
                  <lock-outlined v-if="agentLocked" class="agent-pill-lock" />
                  <down-outlined v-if="!isNarrow" class="agent-pill-caret" />
                </button>
                <template #overlay>
                  <div class="agent-menu">
                    <div class="agent-menu-head">
                      <span>选择智能体</span>
                      <span class="agent-menu-hint">决定这一轮问答用哪套配置</span>
                    </div>
                    <div class="agent-menu-list">
                      <div v-if="agentList.length" class="agent-mi" :class="{ active: currentAgentId === AUTO_AGENT }" @click="pickAgent(AUTO_AGENT)">
                        <span class="agent-mi-ava"><thunderbolt-outlined /></span>
                        <div class="agent-mi-text">
                          <span class="agent-mi-name">自动派遣</span>
                          <span class="agent-mi-desc">每轮由模型按各智能体的职责描述，挑最合适的来回答</span>
                        </div>
                        <check-outlined v-if="currentAgentId === AUTO_AGENT" class="agent-mi-check" />
                      </div>
                      <div v-if="!hasDefaultAgent" class="agent-mi" :class="{ active: !currentAgentId }" @click="pickAgent('')">
                        <span class="agent-mi-ava"><robot-outlined /></span>
                        <div class="agent-mi-text">
                          <span class="agent-mi-name">默认（全局配置）</span>
                          <span class="agent-mi-desc">沿用系统设置里的模型、提示词与能力开关</span>
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
              <button class="app-icon-btn add-btn" :class="{ 'toolbar-btn-on': addOpen || pickedSkills.length || planModeOn }"
                      title="添加：附件 / 技能 / 计划模式 / 引用历史问答 / 快捷命令" @click="toggleAddPanel">
                <plus-outlined />
              </button>
            </div>
            <div class="toolbar-right">
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
              <ModelSelect v-model="currentOverrideModel" type="chat" pill allow-clear
                           :placeholder="effectiveModelLabel || '选择模型'"
                           :compact="isNarrow"
                           :width="190"
                           @option-hover="onModelOptionHover" @open-change="onModelSelectOpenChange" />
            </div>
            <!-- 发送键随内容变形：生成中框里有字 → 这一点是「挂一条待插话」（不是打断当前这一步）；
                 没字 → 才是停止本轮。两段式 Esc 停止照旧（第一次上膛、第二次才停；点击仍是立即停） -->
            <button v-if="loading && canSend" class="send-btn"
                    title="挂一条待插话（Enter 同效；点条目上的「插话」才随下一个工具步送出）"
                    @click="sendNow"><arrow-up-outlined /></button>
            <button v-else-if="loading" class="send-btn stop" :title="escArmed ? '再按一次 Esc 停止生成' : '点击停止生成'" @click="stopNow">
              <span v-if="escArmed" class="esc-cap">esc</span>
              <pause-circle-outlined v-else />
            </button>
            <button v-else class="send-btn" title="发送" :disabled="!canSend" @click="sendNow"><arrow-up-outlined /></button>
          </div>
        </div>
        <!-- 智能体提问面板：当前会话有挂起提问时完全替换聊天输入框（模型在等答案，输入框此时不可用）。
             样式对齐「编号选项 + 自定义输入末项 + 键盘导航 + 忽略/提交」的提问卡；答复后面板撤下、
             输入框回归，问答记录以工具卡形态留在气泡原位 -->
        <div v-else-if="pendingAsk" class="input-box askp-box" @keydown="onAskPanelKeydown">
          <div class="askp-head">
            <span class="askp-tag">{{ curAsk.topic || '向用户提问' }}</span>
            <span class="askp-q">{{ curAsk.question }}</span>
            <span v-if="pendingAsk.ask.answered" class="askp-state ok">已提交，模型继续中…</span>
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
                    class="askp-opt" :class="{ sel: pendingAsk.ask.sels[askPage] === oi, 'k-hi': askHi === oi }"
                    :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || askExpired" @click="onPick(oi)">
              <span class="askp-no">{{ oi + 1 }}.</span>
              <span class="askp-kw">{{ askOptionParts(op).kw }}<span v-if="oi === 0" class="askp-rec">（推荐）</span></span>
              <span v-if="askOptionParts(op).rest" class="askp-rest">{{ askOptionParts(op).rest }}</span>
            </button>
            <div class="askp-opt askp-custom-row" :class="{ sel: pendingAsk.ask.sels[askPage] === curAsk.options.length, 'k-hi': askHi === curAsk.options.length }">
              <span class="askp-no">{{ curAsk.options.length + 1 }}.</span>
              <input ref="askCustomRef" v-model="pendingAsk.ask.customs[askPage]" class="askp-input" :maxlength="2000"
                     :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || askExpired"
                     placeholder="输入你的回答…" @blur="commitCustomAt(askPage, false)" @keydown.enter.prevent="commitCustomAt(askPage, true)" />
            </div>
          </div>
          <div class="askp-foot">
            <span class="askp-hint"><info-circle-outlined /> 选完自动跳下一题，全部选完自动提交；没答的题不替你选</span>
            <span class="askp-actions">
              <!-- 停止 ≠ 忽略：忽略是「这一题我不答，你自行继续」，停止是「整轮叫停」。
                   面板顶替了打字框，停止键（在发送位上）此时不存在，故在面板内给一个同义入口 -->
              <button class="app-btn ghost small p-stop" type="button"
                      title="叫停整轮：不再执行任何工具，已生成的内容按「已停止本轮」保留。与「忽略」不同——忽略会让智能体带着「你没答」继续作答"
                      @click="stopNow">停止本轮</button>
              <button class="app-btn ghost small" :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || askExpired" @click="ignoreAsk(pendingAsk)">忽略</button>
              <button class="app-btn small" :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || askExpired" @click="askSubmitAll(pendingAsk)">提交（{{ askAnsweredCount }} / {{ pendingAsk.ask.questions.length }}）</button>
            </span>
          </div>
        </div>
        <!-- 计划确认栏：待批准时整块顶替打字框（模型在等裁决，此刻也没法发新消息），与提问面板同构——
             编号选项：1. 确认（点即按原稿执行）2. 继续对话（光输入框，写要改什么，非空才能送出）。
             不满意就继续对话：意见作为一条用户消息发出，本版计划作废，模型接着对话按意见重做计划；
             计划正文在气泡里只读展示。底部「取消」= 提问面板的「忽略」 -->
        <div v-else class="input-box planp-box">
          <div class="planp-head">
            <span class="planp-tag">执行计划</span>
            <span v-if="planExpired" class="planp-state warn">已超时，本轮将停止</span>
            <span v-else class="planp-state">待确认</span>
            <span v-if="planCountdownText" class="planp-timer" :class="{ warn: planExpired }">
              <clock-circle-outlined /> {{ planCountdownText }}
            </span>
          </div>
          <div class="planp-opts">
            <button type="button" class="planp-opt" :disabled="curPlanCard.busy || planExpired"
                    @click="submitPlanApproval(pendingPlan, 'approve')">
              <span class="planp-no">1.</span>
              <span class="planp-kw">确认</span>
              <span class="planp-rest">直接按此计划开始执行</span>
            </button>
            <div class="planp-opt planp-custom-row">
              <span class="planp-no">2.</span>
              <div class="planp-custom">
                <a-textarea v-model:value="curPlanCard.editText" :bordered="false" :auto-size="{ minRows: 1, maxRows: 8 }" :maxlength="8000"
                            :disabled="curPlanCard.busy || planExpired"
                            placeholder="不满意就继续对话：写下要改什么…" />
              </div>
            </div>
          </div>
          <div class="planp-foot">
            <span class="planp-hint"><info-circle-outlined /> 继续对话会按你的意见重做计划；「确认」才执行；未处理将超时并停止本轮</span>
            <span class="planp-actions">
              <!-- 与提问面板同形：面板顶替打字框时，停止入口只能放在面板里 -->
              <button class="app-btn ghost small p-stop" type="button"
                      title="叫停整轮：不再执行任何工具。与「取消」不同——取消是「不批准这一版计划」，卡片会定格「未批准」"
                      @click="stopNow">停止本轮</button>
              <button class="app-btn ghost small" :disabled="curPlanCard.busy || planExpired" @click="submitPlanApproval(pendingPlan, 'cancel')">取消</button>
              <button class="app-btn small" :disabled="curPlanCard.busy || planExpired || !(curPlanCard.editText || '').trim()"
                      @click="continuePlanConversation(pendingPlan, curPlanCard.editText)">继续对话</button>
            </span>
          </div>
        </div>
        <input ref="attachInput" type="file" multiple style="display:none" @change="onAttachChange" />
        <!-- 图片选择：accept 写显式 MIME 列表而非通配 —— 「斜杠星号」会被 check-h5 的注释剥离器
             当作块注释开头，把后续 400 行当注释吞掉，右栏等三条断言全部误报 -->
        <input ref="addImgInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif,image/bmp" multiple style="display:none" @change="onAddImgChange" />
      </div>
    </div>

    <!-- 右侧状态栏（可收起）：计划 / 运行控制 / 产物 / 检索·用量（本轮|会话口径切换）/ 引用来源（有引用才显示）
         宽窄两态共用这一份 DOM：宽屏由 panelOpen 控制（占位列），窄屏由 mPanelOpen 控制
         （浮层/底部 sheet，默认收起）。两态只靠 .as-sheet 类 + CSS 媒体查询区分。 -->
    <aside v-if="panelOpen || mPanelOpen" class="right-panel" :class="{ 'as-sheet': isNarrow }">
      <!-- 窄屏下本栏是覆盖式 sheet，必须给一个显式关闭口：宽屏它是常驻列不需要关，
           窄屏开起来后若只能靠顶栏按钮来回切，用户会觉得「关不掉」。仅窄屏渲染。 -->
      <button v-if="isNarrow" class="rp-sheet-close" type="button" title="关闭" @click="mPanelOpen = false">
        <close-outlined />
      </button>
      <!-- 运行控制：最近一轮失败可整轮重试（复用消息流 regenerate）；停止生成只在发送键/ESC 提供，右栏不重复。
           失败态提到首位：右栏其余卡都是只读信息，不该挡在一个待操作的动作前面。 -->
      <div v-if="panelAi && panelAi.failed" class="rp-card rp-ctrl">
        <button class="rp-ctrl-btn" @click="retryPanelRound()"><reload-outlined /> 重试本轮</button>
      </div>
      <!-- 任务清单：本会话最近一次 writeTodo 的整表快照（有清单才显示，与产物/计划卡同规则）。
           放在信息卡首位：工具卡只能看出「做过什么」，看不出「还差几步」——后者才是长任务里
           用户真正要瞄一眼的东西 -->
      <div v-if="sessionTodos && sessionTodos.items && sessionTodos.items.length" class="rp-card rp-todo">
        <button type="button" class="rp-card-head rp-todo-head" @click="todoOpen = !todoOpen">
          <span class="rp-label">任务清单</span>
          <span class="rp-todo-progress">{{ todoDone }}/{{ sessionTodos.items.length }}</span>
          <caret-right-outlined class="tl-caret" :class="{ open: todoOpen }" />
        </button>
        <div v-if="todoOpen" class="rp-todo-list">
          <div v-for="(t, ti) in sessionTodos.items" :key="ti" class="rp-todo-item" :class="t.status">
            <span class="rp-todo-box">{{ t.status === 'done' ? '✓' : t.status === 'doing' ? '▸' : '' }}</span>
            <span class="rp-todo-txt">{{ t.content }}</span>
          </div>
        </div>
      </div>
      <!-- 引用来源：右栏交互最重的一张卡（点片段定位正文 / hover 高亮角标 / 分组展开），提到第二位。
           原先排在末尾，等于把「核查答案」这个最高频动作排到最需要滚动才看得到的位置——
           正文里刚亮起的角标，用户得点开侧栏再往下翻。移动端 sheet 一直把它排第一，这里对齐。
           有引用才显示整卡（空则隐藏，不占版面，与产物/沙盒卡同规则）。 -->
      <div v-if="groupedSources.length" class="rp-card">
        <div class="rp-label">引用来源 · {{ groupedSources.length }} 个来源</div>
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
                   title="点击查看原文；有对应角标的来源会在正文中定位"
                   @click.stop="locateSource(s)"
                   @mouseenter="hoverSource(s)" @mouseleave="unhoverSource(s)">
                <span class="rp-src-ref">[{{ s.ref }}]</span>
                <span v-if="s.origin === 'MENTION'" class="rp-src-mine" title="本轮你 @ 引用的资料">引用</span>
                <span class="rp-src-name">{{ s.title ? '§ ' + s.title : '片段 ' + (si + 1) }}</span>
                <span v-if="debugDisplayVisible && fmtSourceScore(s)" class="rp-src-score" :title="scoreTitle(s)">{{ fmtSourceScore(s) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
      <!-- 本会话产物：有文件才显示整卡（空则隐藏，不占版面），点击直接下载（与消息流内下载链接同源）；更多入口跳产物页 -->
      <div v-if="sessionArtifacts.length" class="rp-card">
        <div class="rp-label">产物 · {{ sessionArtifacts.length }} 个</div>
        <a v-for="(a, pi) in sessionArtifacts" :key="pi" class="rp-art"
           :href="resolveImg(a.url)" :download="a.filename"
           :title="'下载 ' + a.filename + (a.description ? '：' + a.description : '')">
          <file-text-outlined class="rp-art-ic" />
          <span class="rp-art-name">{{ a.filename }}</span>
          <download-outlined class="rp-art-dl" />
        </a>
        <div class="rp-art-all" @click="router.push('/artifacts')">在产物页查看全部 ›</div>
      </div>
      <!-- 检索与用量合并成一张卡、两态互斥（v-if/v-else）：
           两者原本被拆成两张卡夹在「来源」和「汇总」之间，但它们服务的都是同一个问题——「这轮为什么这么答、花了多少」。
           合并后口径切换器贴到卡内标题行，只作用于它真正管辖的内容，不再独占栏首让人误以为整栏都跟着切。 -->
      <div class="rp-card">
        <div class="rp-card-head">
          <span class="rp-label rp-label-h">{{ panelScope === 'round' ? '本轮检索与用量' : '会话汇总' }}</span>
          <!-- 统计口径切换（持久化）：本轮=最近完成轮明细；会话=全量累计 -->
          <span class="rp-scope">
            <span :class="{ on: panelScope === 'round' }" @click="panelScope = 'round'">本轮</span>
            <span :class="{ on: panelScope === 'session' }" @click="panelScope = 'session'">会话</span>
          </span>
        </div>
        <template v-if="panelScope === 'round'">
          <template v-if="lastRetrieved || lastSources.length">
            <div class="rp-row"><span v-if="lastRetrieved?.keywords > 0">检索词 {{ lastRetrieved.keywords }} 个</span><span v-if="(lastRetrieved?.refs ?? lastSources.length) > 0" class="rp-dim">引用 {{ lastRetrieved?.refs ?? lastSources.length }} 条</span><span v-if="!(lastRetrieved?.keywords > 0) && (lastRetrieved?.refs ?? lastSources.length) > 0" class="rp-dim">未提取到关键词（纯向量召回）</span><span v-if="!(lastRetrieved?.keywords > 0) && !(lastRetrieved?.refs ?? lastSources.length)" class="rp-dim">本轮未检索到相关资料</span></div>
            <div v-if="lastTokens && lastTokens.hits != null && lastTokens.hits > 0 && lastTokens.hits !== (lastRetrieved?.refs ?? lastSources.length)" class="rp-meta">其中 {{ lastTokens.hits }} 条实际填入上下文（其余为模型中途补充/未入上下文）</div>
            <div v-if="lastRetrieved?.terms?.length" class="rp-terms">{{ lastRetrieved.terms.join('、') }}</div>
            <template v-if="toolSearchQueries(lastAi).length">
              <div class="rp-divider"></div>
              <div class="rp-tool-label">精确检索（模型主动补充）</div>
              <div v-for="(q, qi) in toolSearchQueries(lastAi)" :key="qi" class="rp-terms rp-tool-q">{{ qi + 1 }}. {{ q }}</div>
            </template>
          </template>
          <div v-else class="rp-dim">本轮尚无检索记录</div>
          <!-- 用量（1.9 Token 消耗可视化）：上下文为实际填充、输出在网关返回 usage 时用实测、否则估算 -->
          <template v-if="lastTokens">
            <div class="rp-divider"></div>
            <div class="rp-strong">{{ lastTokens.outputIsReal ? '' : '≈' }}{{ fmtTokens(lastTokens.total) }} tokens</div>
            <!-- 上下文占用条：context/budget，逼近预算变警告色（>80% 警告 / >95% 危险） -->
            <div v-if="lastTokens.budget > 0" class="rp-ctx-bar" :title="'上下文占用 ' + ctxPct + '%'">
              <span class="rp-ctx-fill" :class="ctxLevel" :style="{ width: ctxPct + '%' }"></span>
            </div>
            <div class="rp-row" :style="lastTokens.budget > 0 ? 'margin-top:5px' : ''"><span>上下文 {{ fmtTokens(lastTokens.context) }}<template v-if="lastTokens.budget > 0">（{{ ctxPct }}%）</template></span><span class="rp-dim">预算 {{ fmtTokens(lastTokens.budget) }}</span></div>
            <div class="rp-meta">输出 {{ fmtTokens(lastTokens.output) }}<template v-if="lastTokens.hits > 0"> · 上下文填入 {{ lastTokens.hits }} 块</template><template v-if="!lastTokens.outputIsReal"> · 估算值</template></div>
          </template>
        </template>
        <!-- 会话态：全量累计，只按真实记录的数据统计（c_ai_message 未落库 tokens，历史恢复的轮没有该字段，不冒充 0） -->
        <template v-else>
          <div class="rp-strong">{{ sessionTokensLabel }}</div>
          <div v-if="sessionTokens.rounds" class="rp-meta">输出 {{ fmtTokens(sessionTokens.output) }} · 按已记录 {{ sessionTokens.rounds }}/{{ roundCount }} 轮累计</div>
          <div v-else-if="roundCount" class="rp-meta">恢复的历史轮次不含用量记录</div>
          <div v-else class="rp-meta">发送问题后统计</div>
          <div class="rp-divider"></div>
          <div class="rp-row"><span>问答 {{ roundCount }} 轮</span><span v-if="sessionArtifacts.length > 0" class="rp-dim">产物 {{ sessionArtifacts.length }} 个</span></div>
          <div v-if="sessionRetrieval.rounds > 0" class="rp-row" style="margin-top:4px"><span>检索 {{ sessionRetrieval.rounds }} 轮<template v-if="sessionRetrieval.refs > 0"> · 引用 {{ sessionRetrieval.refs }} 段</template></span><span v-if="sessionRetrieval.search > 0" class="rp-dim">精确检索 {{ sessionRetrieval.search }} 次</span></div>
          <div v-if="sessionRetrieval.tools > 0" class="rp-meta">工具调用 {{ sessionRetrieval.tools }} 次</div>
        </template>
      </div>
      <!-- 计划：本会话每轮出过的计划各列一条（继续对话后每次重做都算一份），点击定位到那一轮气泡。
           默认折叠沉底：它的条数随会话长度无上限增长，而动作只是「跳回历史某一轮」——低频导航不该占顶部固定槽位，
           更不该把上面几张交互卡挤出首屏。有待确认的计划时自动展开，避免「计划要批」这件事被藏住。 -->
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
            <button v-for="p in sessionPlans" :key="p.mi" type="button" class="rp-plan-row" @click="jumpToPlan(p.mi)"
                    :title="p.label">
              <ordered-list-outlined class="rp-plan-ic" />
              <span class="rp-plan-t">{{ p.label }}</span>
              <span class="rp-plan-st" :class="'is-' + p.status">{{ planStateText(p.status) }}</span>
            </button>
          </div>
        </div>
      </div>
    </aside>

    <!-- 正文浮层宿主（来源弹窗 / 角标悬浮卡 / 图片灯箱）：DOM 与状态见 components/AnswerViewerHost.vue
         与 chat/answerViewer.js。整页只挂这一处——每条消息各挂一份会让多图切换互相打架。 -->
    <AnswerViewerHost :debug-visible="debugDisplayVisible" :session-id="currentSessionId" />

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

    <!-- 压缩后的摘要全文：压缩结果条「查看摘要」打开。正文在打开时快照（compactSummaryText），
         否则发送新一轮清掉 compactNotice 后，弹窗内容会跟着变空 -->
    <a-modal v-model:open="compactSummaryOpen" title="早期对话摘要" :footer="null" width="640px">
      <div class="csum-tip">
        以下是后续回答里「早期对话」的呈现形式（约 {{ fmtTokens(compactSummaryTokens) }} tokens）。
        完整问答仍保留在会话中，往上翻可回看。
      </div>
      <pre class="csum-body">{{ compactSummaryText }}</pre>
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
         展示该模型自己的设置：上下文窗口（登记了「最小~最大」区间时可点选档位，默认最大）+ 思考强度
         （档位点选即时写入按模型 localStorage 记忆，生效模型下一轮发送立即生效）；模型不支持思考时只展示窗口信息 -->
    <Teleport to="body">
      <div v-if="thinkPanelVisible" ref="thinkPanelEl" class="think-float"
           :style="{ top: thinkPanelPos.top + 'px', left: thinkPanelPos.left + 'px' }"
           @mouseenter="onThinkPanelEnter" @mouseleave="onThinkPanelLeave"
           @mousedown.stop.prevent>
        <div class="thinkp">
          <div class="thinkp-head">
            <div class="thinkp-title">深度思考</div>
            <div class="thinkp-model">{{ thinkPanelModelLabel }}</div>
          </div>
          <div v-if="thinkPanelCaps.visible" class="thinkp-desc">回答前先逐步推理，适合数学、代码等复杂问题；关闭后响应更快。</div>
          <div v-if="thinkPanelCtxText || thinkPanelCaps.visible" class="thinkp-row-wrap">
            <button v-if="thinkPanelCtxText" class="thinkp-row" type="button"
                    :class="{ expandable: !!thinkPanelCtxRange }"
                    :title="thinkPanelCtxRange
                      ? '上下文窗口档位（默认最大，点选调整）。决定检索资料能塞多少：预算 = 窗口×安全系数−最大输出'
                      : '模型登记的上下文窗口。决定检索资料能塞多少：预算 = 窗口×安全系数−最大输出'"
                    @click="thinkPanelCtxRange && (thinkCtxOpen = !thinkCtxOpen)">
              <span class="thinkp-row-label">上下文窗口</span>
              <span class="thinkp-row-val">{{ thinkPanelCtxText }}</span>
              <down-outlined v-if="thinkPanelCtxRange" class="thinkp-caret" :class="{ open: thinkCtxOpen }" />
            </button>
            <div v-if="thinkCtxOpen && thinkPanelCtxRange" class="thinkp-levels">
              <button v-for="opt in thinkPanelCtxOptions" :key="opt.value" class="thinkp-opt" type="button"
                      :class="{ active: opt.value === thinkPanelCurrentCtx }"
                      @click="setCtxWindow(opt.value, thinkHoverModel)">
                <span>{{ opt.label }}</span>
                <check-outlined v-if="opt.value === thinkPanelCurrentCtx" class="thinkp-opt-check" />
              </button>
            </div>
            <template v-if="thinkPanelCaps.visible">
              <button class="thinkp-row" type="button"
                      :class="{ expandable: !thinkPanelCaps.locked }"
                      @click="!thinkPanelCaps.locked && (thinkLevelsOpen = !thinkLevelsOpen)">
                <span class="thinkp-row-label">思考强度</span>
                <span class="thinkp-row-val">{{ thinkPanelStrengthText }}</span>
                <down-outlined v-if="!thinkPanelCaps.locked" class="thinkp-caret" :class="{ open: thinkLevelsOpen }" />
              </button>
              <div v-if="thinkLevelsOpen && !thinkPanelCaps.locked" class="thinkp-levels">
                <button v-for="opt in thinkPanelLevelOptions" :key="opt.value" class="thinkp-opt" type="button"
                        :class="{ active: opt.value === thinkPanelCurrentLevel }"
                        @click="setThinkLevel(opt.value, thinkHoverModel)">
                  <span>{{ opt.label }}</span>
                  <check-outlined v-if="opt.value === thinkPanelCurrentLevel" class="thinkp-opt-check" />
                </button>
              </div>
            </template>
          </div>
          <div class="thinkp-foot">{{ thinkPanelFoot }}</div>
        </div>
      </div>
    </Teleport>

    <!-- 上下文容量卡：悬浮输入框工具栏容量圆环弹出（用量/窗口 + 占用填充条 + 分类明细 + 缓存命中率）。
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
              {{ fmtWindow(ctxCapData.used) }}/{{ fmtWindow(ctxCapData.window) }}（{{ ctxCapData.pct }}%）
            </div>
          </div>
          <template v-if="ctxCapData.window > 0">
            <!-- 填充宽=用量/窗口（与标题占用率同口径，空轨即剩余窗口），段宽在填充内按构成比分 -->
            <div class="ctxcap-bar">
              <div class="ctxcap-fill" :style="{ width: ctxCapData.fillPct + '%' }">
                <span v-for="r in ctxCapData.rows" :key="r.key" class="ctxcap-seg"
                      :style="{ width: ctxCapData.pctOf(r) + '%', background: r.color }"
                      :title="r.label + ' ' + fmtWindow(r.tokens) + '（占本轮用量 ' + ctxCapData.pctOf(r).toFixed(1) + '%）'"></span>
              </div>
            </div>
            <div v-for="r in ctxCapData.rows" :key="r.key" class="ctxcap-row">
              <span class="ctxcap-dot" :style="{ background: r.color }"></span>
              <span class="ctxcap-row-label">{{ r.label }}</span>
              <span class="ctxcap-row-val">{{ fmtWindow(r.tokens) }} · {{ ctxCapData.pctOf(r).toFixed(1) }}%</span>
            </div>
            <div v-if="!ctxCapData.rows.length" class="ctxcap-empty">发送问题后显示分类占用</div>
            <div v-if="ctxCapData.cacheRate != null" class="ctxcap-foot">
              <span>缓存命中率</span><span class="ctxcap-row-val">{{ ctxCapData.cacheRate }}%</span>
            </div>
            <div class="ctxcap-tip">窗口：{{ fmtWindow(ctxCapData.window) }} · 占比按本轮用量计 · 用量为本轮真实 prompt token（网关未回传时按估算）</div>
          </template>
          <div v-else class="ctxcap-empty">该模型未登记上下文窗口，请在模型管理中声明</div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { LoadingOutlined, DownOutlined, CaretRightOutlined, CheckOutlined, CloseCircleOutlined, FileTextOutlined, DownloadOutlined, GlobalOutlined, ApiOutlined,
         ExclamationCircleOutlined, CopyOutlined, LikeOutlined, DislikeOutlined, ReloadOutlined, MoreOutlined,
         DeleteOutlined, BugOutlined, EditOutlined, PlusOutlined, PaperClipOutlined, BulbOutlined, PauseCircleOutlined,
         CompressOutlined,
         ArrowUpOutlined, RobotOutlined, SettingOutlined, ThunderboltOutlined, LockOutlined, RedoOutlined,
         CloseOutlined, DatabaseOutlined, SearchOutlined, UpOutlined, ShareAltOutlined,
         HistoryOutlined, TranslationOutlined, QuestionCircleOutlined, SwapOutlined, InfoCircleOutlined,
         AppstoreOutlined, PictureOutlined, ClockCircleOutlined,
         OrderedListOutlined, CheckCircleOutlined } from '@ant-design/icons-vue'
import { debugRetrieval, deleteMessageGroup, submitFeedback as apiSubmitFeedback,
         addEvalCase, getSessionShare, enableSessionShare, disableSessionShare } from '../api'
import { renderMd, resolveImg, onImgError } from '../utils/markdown'
import { loadSessions, sessionStore } from './store'
import { exportAnswerMd, exportSessionMarkdown } from './exportMd'
import { fmtTokens } from '../utils/token'
import { chatDone, refreshSetupGuide, setupGuide } from '../utils/setupGuide'
import { copyText } from '../utils/clipboard'
import { useSessionShare } from './shareSession'
import ModelSelect from '../components/ModelSelect.vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import BrandMark from '../components/BrandMark.vue'
import AgentAvatar from '../components/AgentAvatar.vue'
import SetupGuide from '../components/SetupGuide.vue'
import WelcomeCopy from '../components/WelcomeCopy.vue'
import { isNarrow, isCoarse } from '../h5/mobile'
// 窄屏专属 UI 组件（PC 态不渲染，详见 src/h5/README.md）
import MobileChatHead from '../h5/MobileChatHead.vue'
import MobileSampleCards from '../h5/MobileSampleCards.vue'
import AskRecordCard from '../components/AskRecordCard.vue'
// 回答正文与它的浮层宿主：与两个分享页共用同一份实现（详见 src/chat/answerViewer.js 顶部说明）
import AnswerBody from '../components/AnswerBody.vue'
import AnswerViewerHost from '../components/AnswerViewerHost.vue'

// 聊天引擎（M1 引擎抽取）：引擎逻辑见 src/chat/useChatEngine.js，纯函数/常量见 src/chat/projections.js
import { useChatEngine } from '../chat/useChatEngine'
import { useChatSearch } from '../chat/useChatSearch'
// 正文浮层的共享状态：右栏来源联动（hoveredRef / 点角标定位）与灯箱开关要用
import { openImages, previewUrl, openSource, hoveredRef } from '../chat/answerViewer'
import { useApprovalRecovery } from '../chat/useApprovalRecovery'
import { toolLabel, toolDesc, toolCallsView, toolDuration, toolRunning, busyOf, hasTimelineBlocks,
         procOpen, toggleProc, procSlice, timelineRows, toolBrief, prettyIo, liveOutput, groupRunning,
         clusterOpen, toggleCluster, clusterTools, clusterRunning, clusterHasError, clusterDur, clusterProcCount,
         groupHasError, groupDur, fallbackDur, liveToolDur, toolSearchQueries, retrievalLineTitle, subagentCard, barWidth,
         toggleSubagents, fmtDuration, fmtWindow, externalOrigin, sourceName, fmtSourceScore, scoreTitle,
         fmtMsgTime, fmtSize, errorBrief, histItemTitle, verLocal, canSwitchPrev, canSwitchNext, verLabel,
         pasteTitle, pasteSub, isPastedText,
         THINK_LEVEL_ON, levelLabel, stopTick, agentBadgeOf } from '../chat/projections'

const router = useRouter()
const route = useRoute()

// ==================== 工具审批恢复（通知 → 会话）：按 approvalId 重建审批卡 ====================
// 状态机与移动壳共用（src/chat/useApprovalRecovery.js）：两端「批准/拒绝 → 重取状态」口径一致
const { approval: recoveryApproval, busy: recoveryBusy, statusText: recoveryStatusText,
        statusClass: recoveryStatusClass, resolve: resolveRecovery, dismiss: dismissRecovery } = useApprovalRecovery()

// 注：智能体提问不走 approval 那种顶部横幅——卡片已落库且等待作答期间断线不中止本轮，
// 恢复改由引擎在会话加载时按待答记录重建提问卡（hydratePendingAsk），仍挂在底部答题面板原位。

const textareaRef = ref(null)
// ==================== 深度思考面板（宽屏：悬浮模型下拉行弹出；触屏：常驻入口点开底部 sheet） ====================
// 面板内 20 处逻辑（档位/上下文/能力判断）全部经 thinkHoverModel 取「当前模型」。
// 把它做成读写 computed 而非裸 ref：宽屏返回真实悬浮值，触屏返回生效模型 ——
// 20 处消费点一行都不用改，也不会漏掉某一处。
const thinkHoverRef = ref('')            // 宽屏：当前悬浮的模型引用（''=无，面板隐藏）
const thinkPanelPos = ref({ top: 0, left: 0 })
const thinkPanelEl = ref(null)
/** 「思考强度」行的档位子列表展开态：面板隐藏或换模型后复位为收起 */
const thinkLevelsOpen = ref(false)
// 触屏（hover:none）没有「悬浮模型行」这回事，设置面板不可达 —— 由工具条常驻入口
// （.think-entry，仅粗指针渲染）点开底部 sheet。内容复用同一面板，模型取当前生效模型。
const mThinkOpen = ref(false)
const thinkHoverModel = computed({
  get: () => (isCoarse.value ? effectiveModel.value : thinkHoverRef.value),
  // 写入只发生在宽屏的 hover 逻辑里；触屏下若被 scheduleThinkHide 清空，改为关掉 sheet
  set: v => { if (isCoarse.value) mThinkOpen.value = false; else thinkHoverRef.value = v }
})
const thinkPanelVisible = computed(() => (isCoarse.value ? mThinkOpen.value : !!thinkHoverRef.value))
const toggleThinkSheet = () => {
  mThinkOpen.value = !mThinkOpen.value
  if (!mThinkOpen.value) { thinkLevelsOpen.value = false; thinkCtxOpen.value = false }
}
// 触屏下点面板外关闭：面板自身有 @mousedown.stop.prevent，这里挂 document 只收面板外的点击
const onDocPointerDown = e => {
  if (!mThinkOpen.value) return
  if (thinkPanelEl.value && thinkPanelEl.value.contains(e.target)) return
  mThinkOpen.value = false
}
const thinkPanelModelLabel = computed(() => {
  const info = modelIndex.value[thinkHoverModel.value]
  return info ? info.displayName : thinkHoverModel.value
})
const thinkPanelCaps = computed(() => thinkCapsOf(thinkHoverModel.value))
const thinkPanelLevelOptions = computed(() => levelOptionsOf(thinkHoverModel.value))
const thinkPanelCurrentLevel = computed(() => currentLevelOf(thinkHoverModel.value))
const thinkPanelStrengthText = computed(() => {
  if (!deepOnOf(thinkHoverModel.value)) return '未开启'
  const v = thinkPanelCurrentLevel.value
  return (v === THINK_LEVEL_ON) ? '已开启' : levelLabel(v)
})
/** 面板底部提示：不支持思考的模型给一句说明，避免「为什么没有强度行」的疑惑 */
const thinkPanelFoot = computed(() => {
  if (!thinkPanelCaps.value.visible) return '该模型不支持深度思考。'
  return thinkPanelCaps.value.locked
    ? '该模型始终深度思考，强度由管理员在模型库登记。'
    : '强度越高，思考越深入，回答耗时相应增加。'
})
/** 「上下文窗口」行的档位子列表展开态：面板隐藏或换模型后复位为收起（与思考强度行同款） */
const thinkCtxOpen = ref(false)
const thinkPanelCtxRange = computed(() => ctxRangeOf(thinkHoverModel.value))
const thinkPanelCtxOptions = computed(() => ctxWindowOptionsOf(thinkHoverModel.value))
const thinkPanelCurrentCtx = computed(() => effectiveCtxWindowOf(thinkHoverModel.value))
/** 窗口行展示值（K/M 口径）：可调模型显示当前生效档位（未选=最大），不可调显示登记窗口；未登记整行隐藏 */
const thinkPanelCtxText = computed(() => {
  const info = modelIndex.value[thinkHoverModel.value]
  if (!info) return ''
  return fmtWindow(effectiveCtxWindowOf(thinkHoverModel.value) || info.contextWindow)
})

const THINK_PANEL_W = 296      // .thinkp 264 + 面板卡左右内边距 32
const THINK_PANEL_GAP = 10
const THINK_EDGE = 8
const THINK_ENTER_DELAY = 120  // 扫过多行不闪面板
const THINK_LEAVE_DELAY = 200  // 留出移入面板的时间
let thinkEnterTimer = null
let thinkLeaveTimer = null
let thinkPanelHovered = false
let thinkPanelRect = null              // 最近一次悬浮行的位置（面板随内容伸缩后仍按它对齐）
const clearThinkTimers = () => {
  clearTimeout(thinkEnterTimer); clearTimeout(thinkLeaveTimer)
  thinkEnterTimer = thinkLeaveTimer = null
}
const hideThinkPanel = () => {
  clearThinkTimers()
  thinkHoverModel.value = ''
  thinkLevelsOpen.value = false
  thinkCtxOpen.value = false
  thinkPanelHovered = false
  thinkPanelRect = null
}
/** 按悬浮行位置 + 面板实际高度重新收口：面板首选与行顶部对齐，放不下则整体上移。
 *  展开档位列表、换模型后内容高度都会变，须重算（否则面板伸出屏幕底部，思考列表显示不全） */
const repositionThinkPanel = () => {
  const el = thinkPanelEl.value
  if (!el || !thinkPanelRect) return
  const maxTop = window.innerHeight - THINK_EDGE - el.offsetHeight
  const top = Math.max(THINK_EDGE, Math.min(thinkPanelRect.top - 6, maxTop))
  if (top !== thinkPanelPos.value.top) thinkPanelPos.value = { ...thinkPanelPos.value, top }
}
// 监听 thinkHoverRef（裸值）而非 thinkHoverModel（computed）：后者在触屏下等于 effectiveModel，
// 生效模型一变就会触发一次无意义的重定位（触屏面板位置由 CSS 接管，不参与 JS 定位）
watch([thinkHoverRef, thinkLevelsOpen, thinkCtxOpen], () => nextTick(repositionThinkPanel))
/** 悬浮下拉模型行（ModelSelect 透传）：先记下行位置，稍候弹出该模型的设置面板；ref 为空=离开行 */
const onModelOptionHover = (modelRef, rowEl) => {
  // 触屏没有 hover：模型下拉行的 per-item 设置面板不可达，改由工具条常驻入口
  // （.think-entry，点开底部 sheet）承担。此处必须早退，否则触屏点开下拉时
  // hover 通路与常驻入口同时生效，两套状态会打架（面板既被 hover 打开又被 sheet 关闭）
  if (isCoarse.value) return
  if (!modelRef) { scheduleThinkHide(); return }
  const rect = rowEl ? rowEl.getBoundingClientRect() : null
  thinkPanelRect = rect
  clearTimeout(thinkLeaveTimer); thinkLeaveTimer = null
  clearTimeout(thinkEnterTimer)
  thinkEnterTimer = setTimeout(() => {
    thinkEnterTimer = null
    if (thinkHoverRef.value !== modelRef) { thinkLevelsOpen.value = false; thinkCtxOpen.value = false }
    thinkHoverRef.value = modelRef
    if (rect) {
      // 右侧放不下时翻到行左侧；top 先按行顶对齐，渲染后 repositionThinkPanel 按实际高度收口
      const maxLeft = window.innerWidth - THINK_EDGE - THINK_PANEL_W
      const left = rect.right + THINK_PANEL_GAP <= maxLeft
        ? rect.right + THINK_PANEL_GAP
        : Math.max(THINK_EDGE, rect.left - THINK_PANEL_GAP - THINK_PANEL_W)
      thinkPanelPos.value = { top: Math.max(THINK_EDGE, rect.top - 6), left }
    }
  }, THINK_ENTER_DELAY)
}
const scheduleThinkHide = () => {
  clearTimeout(thinkEnterTimer); thinkEnterTimer = null
  if (thinkLeaveTimer || !thinkHoverRef.value) return
  thinkLeaveTimer = setTimeout(() => {
    thinkLeaveTimer = null
    if (!thinkPanelHovered) hideThinkPanel()
  }, THINK_LEAVE_DELAY)
}
const onThinkPanelEnter = () => { thinkPanelHovered = true; clearTimeout(thinkLeaveTimer); thinkLeaveTimer = null }
const onThinkPanelLeave = () => { thinkPanelHovered = false; scheduleThinkHide() }
/** 下拉开合联动：开着时取消挂起的收起；关闭即收面板——但点面板里的档位会触发「点击外部」把下拉收起，
 *  此时指针仍在面板上（thinkPanelHovered=true）不收，保持连续调档 */
const onModelSelectOpenChange = open => {
  if (open) { clearTimeout(thinkLeaveTimer); thinkLeaveTimer = null }
  else scheduleThinkHide()
}
onUnmounted(clearThinkTimers)
/** 底部「管理智能体」：跳到独立的一级页面 */
const goManageAgents = () => {
  agentPickerOpen.value = false
  router.push('/agents')
}
const box = ref(null)
const stickToBottom = ref(true)
const AUTO_SCROLL_MARGIN = 80
// 尾随留白高度（px）：把本轮问题下方补足到一屏，使「贴底」的落点正好是问题置顶。
// 于是「问题拉到最上」与「回答时跟到底部」不再互斥：回答还不足一屏时它长在留白里、
// 问题钉在顶部不动（新内容始终看得见）；长过一屏后留白归零，贴底就自然变成跟着最新内容走。
const tailSpacer = ref(0)
const onMessagesScroll = () => {
  const el = box.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < AUTO_SCROLL_MARGIN
}

// 右侧状态栏：默认展开（持久化），数据全部来自已有消息/配置，不造数
// ⚠️ panelStored 的初值必须**逐字保留**原表达式里的 `window.innerWidth > 1200`：
//   769~1200px 的桌面窗口下右栏是浮层（≤1200 媒体查询），初值必须为收起；
//   若简化为 !isNarrow（阈值 768），1000px 宽的桌面窗口会变成默认展开 —— 那是 PC 行为变更。
//   同理 isNarrow 只是把 ≤768 的窄屏从「占位列」切换为「浮层」，宽屏语义完全不变。
const panelStored = ref(localStorage.getItem('app_panel') !== '0' && window.innerWidth > 1200)
const panelOpen = computed(() => !isNarrow.value && panelStored.value)
const mPanelOpen = ref(false)
const togglePanel = () => {
  if (isNarrow.value) { mPanelOpen.value = !mPanelOpen.value; return }
  panelStored.value = !panelStored.value
  localStorage.setItem('app_panel', panelStored.value ? '1' : '0')
}
// 分组展开态（key → 开/合，默认展开）：**必须存在 computed 外的响应式容器**——
// computed 每次求值都重建分组对象，直接 g.open = !g.open 改的是非响应式临时对象，
// 点击既不触发重渲染、状态也会随重建被冲掉（「收缩不生效」的根因）。
// 存 reactive map 还能跨重算保留：流式更新 sources 后用户已收起的组不弹回
const srcOpen = reactive({})
const srcOpenOf = g => (srcOpen[g.key] !== undefined ? srcOpen[g.key] : true)
const toggleSrc = g => { srcOpen[g.key] = !srcOpenOf(g) }
const ctxCapOpen = ref(false)
const ctxCapPos = ref({ top: 0, left: 0 })
const ctxCapEl = ref(null)
let ctxCapTimer = null
let ctxCapHovered = false
const showCtxCap = el => {
  clearTimeout(ctxCapTimer)
  ctxCapTimer = setTimeout(() => {
    if (!el) return
    const rect = el.getBoundingClientRect()
    const w = 300
    let pos
    if (rect.top >= 360) {
      // 底部工具栏圆环：卡悬在圆环上方、右沿与圆环右沿对齐
      pos = { top: null, bottom: window.innerHeight - rect.top + 10, left: Math.max(8, rect.right - 268) }
    } else {
      // 上方放不下（矮视口）：退回行侧弹出，顶部与行顶对齐
      const left = rect.right + 10 + w <= window.innerWidth - 8
        ? rect.right + 10
        : Math.max(8, rect.left - 10 - w)
      pos = { top: Math.min(rect.top - 6, window.innerHeight - 360), bottom: null, left }
    }
    ctxCapPos.value = pos
    ctxCapOpen.value = true
  }, 150)
}
const hideCtxCap = () => {
  clearTimeout(ctxCapTimer)
  ctxCapTimer = setTimeout(() => { if (!ctxCapHovered) ctxCapOpen.value = false }, 200)
}
const onCtxCapEnter = () => { ctxCapHovered = true; clearTimeout(ctxCapTimer) }
const onCtxCapLeave = () => { ctxCapHovered = false; hideCtxCap() }
// 「在沙盒中运行」按钮的开关不再由本页持有：正文统一走 AnswerBody，runnable 直接写在调用点上
// （问答页是唯一有会话上下文的消费方；其它页面默认不开——没有会话，按钮点了必然失败）。
// ===== 右栏统计口径切换（P0 #4）：本轮=最近完成轮明细；会话=全量累计（选择持久化） =====
const panelScope = ref(localStorage.getItem('app_panel_scope') === 'session' ? 'session' : 'round')
watch(panelScope, v => { try { localStorage.setItem('app_panel_scope', v) } catch (e) { /* 存储不可用忽略 */ } })
// ===== 右栏「计划」：本会话每轮出过的计划各列一条（最新在前，点击定位到那一轮气泡） =====
// 标签取该轮的用户问题——计划卡没有标题，问题最能认出是哪一份；状态口径与气泡里的计划卡一致
const sessionPlans = computed(() => {
  const out = []
  const list = messages.value
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    if (!m || m.role !== 'ai' || !m.planCard) continue
    let label = ''
    for (let j = i - 1; j >= 0; j--) {
      const prev = list[j]
      if (prev && prev.role === 'user') { label = String(prev.content || '').replace(/\s+/g, ' ').trim(); break }
    }
    out.push({ mi: i, label: label || '（无提问）', status: m.planCard.status })
  }
  return out
})
const planStateText = st => ({
  drafting: '生成中', pending: '待确认', approved: '已批准', superseded: '已被取代', rejected: '未批准'
}[st] || st)
const jumpToPlan = mi => {
  const el = document.querySelector('.row[data-row-index="' + mi + '"]')
  if (!el) return
  el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  el.classList.remove('plan-jump')
  void el.offsetWidth
  el.classList.add('plan-jump')
  setTimeout(() => el.classList.remove('plan-jump'), 1500)
}
// 免责声明（与旧版同一份文案）
const disclaimerVisible = ref(false)
const DISCLAIMER_TEXT = `

### 一、内容生成方式

本系统的回答由人工智能模型基于知识库检索结果**自动生成**，仅供学习与工作参考，不构成任何形式的专业建议（包括但不限于法律、医疗、财务、投资建议），亦不代表系统开发方与运营方的官方立场。

### 二、准确性不作保证

AI 生成内容可能存在**错误、遗漏、过时或与实际情况不符**之处。知识库内容可能更新滞后，回答引用的资料版本可能与最新版本存在差异。重要信息（如操作规范、数据口径、流程要求、审批条件等）请以**官方文档、正式通知或相关业务部门的确认为准**。

### 三、引用来源说明

回答中标注的引用来源仅用于帮助定位参考资料。受文档分块与摘要机制影响，展示的片段可能与原文存在出入，完整含义请以知识块原文及原始文档为准。

### 四、使用限制

请勿仅依赖本系统的回答做出对个人或组织有重大影响的决策。因使用或依赖本系统内容而产生的任何直接或间接损失，系统提供方不承担责任。请遵守信息安全相关规定，**不要在提问中输入密码、密钥、客户隐私等敏感信息**。

### 五、反馈与改进

如发现回答有误或内容不当，可通过回答下方的反馈按钮告知我们，帮助我们持续改进。`

// ===== 引用相关度 + 角标联动（P1 #5）=====
// 分值口径：rerankScore=重排模型相关度（重排实际执行才有），否则回落检索融合分 score；都缺则不显示。
// 分值属于调参排障信息，默认不对普通用户露出：显示统一挂 debugDisplayVisible（chat.retrievalDebugEnabled）
const setSupHighlight = (refN, on) => {
  const idx = lastAi.value ? messages.value.indexOf(lastAi.value) : -1
  if (idx < 0) return
  document.querySelectorAll('.md[data-msg-index="' + idx + '"] .ref-sup[data-ref="' + refN + '"]')
    .forEach(el => el.classList.toggle('ref-hl', on))
}
const hoverSource = s => { hoveredRef.value = s.ref; setSupHighlight(s.ref, true) }
const unhoverSource = s => {
  if (hoveredRef.value === s.ref) hoveredRef.value = null
  setSupHighlight(s.ref, false)
}
// 右栏来源点击：正文有对应角标 → 滚动定位并闪烁提示；没有（片段未被回答引用）→ 打开原文弹窗
const locateSource = s => {
  const idx = lastAi.value ? messages.value.indexOf(lastAi.value) : -1
  const el = idx >= 0 ? document.querySelector('.md[data-msg-index="' + idx + '"] .ref-sup[data-ref="' + s.ref + '"]') : null
  if (el) {
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    el.classList.remove('ref-flash')
    void el.offsetWidth // 强制 reflow 重启动画
    el.classList.add('ref-flash')
    setTimeout(() => el.classList.remove('ref-flash'), 1600)
  } else {
    openSource(s)
  }
}

// 回答反馈
const feedbackVisible = ref(false)
const feedbackSubmitting = ref(false)
const feedbackText = ref('')
const feedbackTarget = ref(null)
const openFeedback = (m, rating) => {
  if (m.fb != null && m.fb !== rating) {
    message.warning(`已评价为「${m.fb === 1 ? '有帮助' : '没帮助'}」，同一回答只能选择一项`)
    return
  }
  feedbackTarget.value = { msg: m, rating }
  feedbackText.value = ''
  feedbackVisible.value = true
}
const doSubmitFeedback = async () => {
  const t = feedbackTarget.value
  if (!t || !t.msg.messageId) { message.warning('该回答不可反馈'); return }
  feedbackSubmitting.value = true
  try {
    const r = await apiSubmitFeedback(t.msg.messageId, t.rating, feedbackText.value.trim())
    if (r.success) { t.msg.fb = t.rating; message.success('感谢反馈'); feedbackVisible.value = false }
    else message.error(r.msg || '提交失败')
  } catch (e) { message.error(e.message || '提交失败') }
  finally { feedbackSubmitting.value = false }
}

// 全局快捷键：ESC 两段式停止（防误触：清输入的 Esc 不会误杀生成中的回答）/ 清空输入；粘贴发图
/** 生成中按第一次 Esc 只上膛——按钮切 esc 键帽，2s 内再按才真正停止，超时自动回落 */
const escArmed = ref(false)
let escArmTimer = null
const armEsc = () => {
  escArmed.value = true
  clearTimeout(escArmTimer)
  escArmTimer = setTimeout(() => { escArmed.value = false }, 2000)
}
const disarmEsc = () => {
  escArmed.value = false
  clearTimeout(escArmTimer)
}
const stopNow = () => { disarmEsc(); stop() }
/** 主输入框占位文案：生成中不再禁用输入，所以文案要改口说清"这句是插话、按下去先挂着"；
 *  窄屏不写 Enter/Shift+Enter 提示（触屏没有这两个键） */
const composerPlaceholder = computed(() => {
  if (loading.value) {
    return isNarrow.value
      ? '补一句方向（先挂到上方待插话）'
      : '补一句方向（Enter 先挂到上方待插话，点「插话」才送出）'
  }
  return isNarrow.value
    ? '问点什么？（@ 引用资料 · / 快捷命令）'
    : '问点什么？Enter 发送，Shift+Enter 换行（@ 引用资料，/ 快捷命令，# 引用历史问答）'
})
/** 待插话条目的载荷摘要（与正常提问一样可以带材料，这里只把"带了什么"标出来） */
function steerExtras (s) {
  const bits = []
  if (s.images?.length) bits.push(`${s.images.length} 张图`)
  if (s.atts?.length) bits.push(`+${s.atts.length} 个附件`)
  if (s.skills?.length) bits.push(`技能 ×${s.skills.length}`)
  if (s.mentions?.length) bits.push(`@ ${s.mentions.length}`)
  if (s.historyRefs?.length) bits.push(`# ${s.historyRefs.length}`)
  if (s.planMode) bits.push('计划模式')
  return bits.join(' · ')
}
const onGlobalKeydown = e => {
  if (e.isComposing || e.keyCode === 229) return
  if (previewUrl.value && ['Escape', 'ArrowLeft', 'ArrowRight'].includes(e.key)) return
  if (e.key === 'Escape') {
    if (loading.value) {
      if (escArmed.value) stopNow()
      else armEsc()
      return
    }
    // a-textarea 的 ref 是组件实例而非原生元素：activeElement 比对必须先取其根元素，等值/包含两种形态都兼容
    const taEl = textareaRef.value?.$el ?? textareaRef.value
    if (taEl && (document.activeElement === taEl || taEl.contains?.(document.activeElement))) {
      if (text.value) text.value = ''
      else document.activeElement?.blur?.()
    }
  }
}
const onGlobalPaste = e => onPasteImages(e)
// 窗口高度变化会改变「一屏」的量：重算留白（跟随中的会话顺手回到落点）
// 重测输入框：antd Textarea 内部靠 ResizeObserver 自测高度，但 iOS Safari 在键盘
// 动画期间该观察可能不触发（overflow 计算在键盘态下不稳定），表现为输入框塌成一行。
// 注意调用路径：antd 的 Textarea 只 expose 了 focus/blur/resizableTextArea，**没有 resize()**，
// 直接调 textareaRef.value.resize() 会抛 TypeError。真正的重测入口在其内部实例上：
//   textareaRef.value.resizableTextArea.instance.resize()（与本文件既有的
//   textareaRef.value.resizableTextArea.textArea 取法同一层级）。
// 加 ?. 兜底：万一 antd 换版本改了结构，宁可不重测也不要抛错打断交互。
const retestTextarea = () => {
  const rt = textareaRef.value && textareaRef.value.resizableTextArea
  if (rt && rt.instance && typeof rt.instance.resize === 'function') rt.instance.resize()
}
/* 消息区滚动条槽宽实测 → 写入 --chat-sbw，供 .input 右内边距补偿。
   .messages 是滚动容器、.input 不是，两侧可用宽度天然差一个槽宽，居中基准因此错开半个槽
   —— 表现为「聊天区域和输入框没对齐」。macOS 覆盖式滚动条槽宽为 0（无需补偿），
   Windows/Linux 常规滚动条约 8~15px。必须在挂载后测量：首次布局前 clientWidth 尚未稳定。
   平台/缩放变化时槽宽可能变，故监听 resize 重测。 */
const syncScrollbarGutter = () => {
  const el = box.value
  if (!el) return
  const gutter = el.offsetWidth - el.clientWidth
  const host = el.closest('.chat2') || document.documentElement
  const next = gutter > 0 ? gutter + 'px' : '0px'
  if (host.style.getPropertyValue('--chat-sbw') !== next) host.style.setProperty('--chat-sbw', next)
}
const onWindowResize = () => {
  updateTailSpacer()
  if (stickToBottom.value) scroll()
  if (isNarrow.value) nextTick(retestTextarea)
  syncScrollbarGutter()
}
// 转屏：iOS Safari 从横屏转竖屏时不保证触发 resize 到正确值，单独补一次
const onOrientationChange = () => { setTimeout(onWindowResize, 120) }
onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('paste', onGlobalPaste)
  window.removeEventListener('resize', onWindowResize)
  window.removeEventListener('orientationchange', onOrientationChange)
  document.removeEventListener('pointerdown', onDocPointerDown)
  clearTimeout(escArmTimer)
  stopTick()
})
const focusInput = () => nextTick(() => textareaRef.value?.focus())
const previewPendingImage = pi => {
  openImages(pendingImages.value.map(p => p.dataUrl), pi)
}
const openPreviewFromMsg = (m, index) => {
  openImages((m.images || []).map(resolveImg), index || 0)
}
const dragOver = ref(false)
let dragDepth = 0
const onDragEnter = e => {
  // 生成中也允许拖进来：插话与正常提问同权，材料可以带着（带材料的整条等本轮结束自动发出）
  if (Array.from(e.dataTransfer?.types || []).includes('Files')) {
    dragDepth++
    dragOver.value = true
  }
}
const onDragLeave = () => { if (--dragDepth <= 0) { dragDepth = 0; dragOver.value = false } }
const onDropFiles = e => {
  dragDepth = 0
  dragOver.value = false
  addFiles(Array.from(e.dataTransfer?.files || []))
}
const onPasteImages = e => {
  const imgs = Array.from(e.clipboardData?.files || []).filter(f => f.type.startsWith('image/'))
  if (!imgs.length) return
  e.preventDefault()
  addImageFiles(imgs)
}
// 输入框粘贴：整段长文本转成「粘贴的文本」附件（图片粘贴仍走上面的 window 级管线）。
// 截断冒泡只为避开 window 级监听的重复处理，附件本身已由引擎入列
const onComposerPaste = e => { if (takePastedText(e)) e.stopPropagation() }
// 粘贴卡片点开看全文：原文只留在内存（待发送项与本轮消息），刷新后的历史卡片没有它
const pasteView = reactive({ open: false, name: '', text: '' })
const openPasteView = a => {
  if (!a) return
  if (!a.text) { message.info('这条粘贴文本的正文未随历史记录保留，无法回看'); return }
  pasteView.name = a.name
  pasteView.text = a.text
  pasteView.open = true
}
/** 卡片标题/副标题口径见 chat/projections 的 pasteTitle / pasteSub（两壳共用） */
const attachInput = ref(null)
const pickAttachments = () => {
  if (pendingFiles.value.length >= MAX_FILES) { message.warning(`一次最多上传 ${MAX_FILES} 个附件`); return }
  attachInput.value?.click()
}
/** 选完即收面板：面板正盖着输入框上方的附件 chip，收起来让用户看到已加上（取消选择不关） */
const onAttachChange = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  addFiles(files)
  closeAddPanel()
}
const addImgInput = ref(null)
const pickAddImages = () => addImgInput.value?.click()
const onAddImgChange = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  addFiles(files)
  closeAddPanel()
}
const openMentionPanel = () => {
  addOpen.value = false
  mentionOpen.value = true
  mentionQuery.value = ''
  mentionHi.value = 0
  if (panelTriggerCh !== '@') { panelTriggerPos = -1; panelTriggerCh = '' }
  if (!mentionKbs.value.length && !mentionDocs.value.length) loadMentionCandidates()
  if (!agentList.value.length) loadAgents()   // 智能体候选直接复用下拉的数据（挂载时已开始加载，兜底再拉一次）
}
const closeMentionPanel = () => closeAllPanels()
/** 点面板/输入区之外关闭（面板不遮断输入，所以用 document 级监听而不是遮罩层）；
 *  @ 引用 / / 命令 / # 历史引用 / + 添加四个面板共用同一关闭监听（同一容器、同一交互约定） */
const onDocClickForMention = e => {
  if (!mentionOpen.value && !slashOpen.value && !histOpen.value && !addOpen.value) return
  const el = e.target
  if (el && el.closest && (el.closest('.mention-panel') || el.closest('.input-box'))) return
  closeAllPanels()
}
onMounted(() => document.addEventListener('click', onDocClickForMention))
onUnmounted(() => document.removeEventListener('click', onDocClickForMention))

// ==================== 三个唤起面板的共用输入通道 ====================
// 起因：原来三个面板各自带一个搜索框，敲 @ 时 preventDefault 吞掉字符 + nextTick 把焦点抢进搜索框。
// 后果是「@ 打不进输入框」，用户无法在任意位置输入该符号（a@b.com、@某人 全部被吞）。
// 现在反过来：**输入框是唯一输入源**——字符正常落下、焦点全程留在输入框，
// 面板只做「展示 + 键盘导航」，筛选词实时取「触发字符到光标」之间的正文（syncPanelQuery）。
// 确认后用 stripTriggerToken 把「触发字符 + 筛选词」这一段从正文里摘掉，不留残渣。

/** 触发字符在正文里的位置与字符本身（keydown 时记录：字符尚未进 DOM，插入后下标即为 pos） */
let panelTriggerPos = -1
let panelTriggerCh = ''

const panelAnyOpen = () => mentionOpen.value || slashOpen.value || histOpen.value
const closeAllPanels = () => {
  mentionOpen.value = false
  slashOpen.value = false
  histOpen.value = false
  addOpen.value = false
  panelTriggerPos = -1
  panelTriggerCh = ''
}
/** 当前打开面板的候选总数与高亮下标读写（三面板结构一致，抽出来避免各写一遍 if/else） */
const panelListSize = () => {
  if (mentionOpen.value) {
    return mentionTab.value === 'kb' ? mentionKbFiltered.value.length
      : mentionTab.value === 'agent' ? mentionAgentFiltered.value.length : mentionDocFiltered.value.length
  }
  if (slashOpen.value) return slashFiltered.value.length
  if (histOpen.value) return histCandidates.value.length
  return 0
}
// 第三分支必须是 histHi（ref）。曾误写为 histOpen.value（布尔），导致 # 面板 getPanelHi().value
// 为 undefined → 高亮写进 NaN、方向键不动、Enter 落空后把「#」原文当问题发出（鼠标点选不受影响）。
const getPanelHi = () => (mentionOpen.value ? mentionHi : slashOpen.value ? slashHi : histHi)
const setPanelHi = v => {
  if (mentionOpen.value) mentionHi.value = v
  else if (slashOpen.value) slashHi.value = v
  else if (histOpen.value) histHi.value = v
}
// 三个面板的可滚列表（v-if 同时只渲染一个；取用顺序与 getPanelHi 一致）
const mentionListEl = ref(null)
const slashListEl = ref(null)
const histListEl = ref(null)
const panelListEl = () => (mentionOpen.value ? mentionListEl : slashOpen.value ? slashListEl : histListEl).value
/** 高亮滑出列表可视区时把列表滚回来：面板不抢焦点（输入框是唯一输入源），浏览器不会替我们滚，
 *  不管的话 ↑↓ 按过可视区边界就等于「没人被选中」。
 *  手算 scrollTop 而非 scrollIntoView：后者会连祖先滚动容器（对话区）一起滚，方向键一按整页跳位。 */
const scrollPanelHiIntoView = () => {
  const list = panelListEl()
  const item = list && list.querySelector('.mention-item.hi')
  if (!item) return
  const lr = list.getBoundingClientRect()
  const ir = item.getBoundingClientRect()
  if (ir.top < lr.top) list.scrollTop += ir.top - lr.top
  else if (ir.bottom > lr.bottom) list.scrollTop += ir.bottom - lr.bottom
}
const movePanelHi = dir => {
  const n = panelListSize()
  if (!n) return
  setPanelHi((getPanelHi().value + dir + n) % n)   // 循环滚动
  nextTick(scrollPanelHiIntoView)
}
/** Enter 确认高亮项：@ 引用（加 chip）/ / 命令（插入模板或执行）/ # 历史引用（加 chip）。返回是否消费了本次回车 */
const confirmPanelHi = () => {
  const i = getPanelHi().value
  if (mentionOpen.value) {
    const item = mentionTab.value === 'kb' ? mentionKbFiltered.value[i]
      : mentionTab.value === 'agent' ? mentionAgentFiltered.value[i] : mentionDocFiltered.value[i]
    if (!item) return false
    stripTriggerToken()
    toggleMention(mentionTab.value, item)
    closeAllPanels()
    return true
  }
  if (slashOpen.value) {
    const c = slashFiltered.value[i]
    if (!c) return false
    // 不能先 stripTriggerToken：它会把 panelTriggerPos 清成 -1，runSlashCommand 就退回"追加到末尾"，
    // 在句中唤起时会跳到全文末尾。/ 面板由 runSlashCommand 自己负责处理触发片段（tpl 就地替换、act 摘除）。
    runSlashCommand(c)
    return true
  }
  if (histOpen.value) {
    const m = histCandidates.value[i]
    if (!m) return false
    stripTriggerToken()
    toggleHistoryRef(m)
    closeAllPanels()
    return true
  }
  return false
}
/** 摘掉「触发字符 + 其后已输入的筛选词」：引用已由 chip 承载，正文里不该残留这段触发文本。
 *  光标随后落回摘除处，用户可继续在原位打字。 */
const stripTriggerToken = () => {
  if (panelTriggerPos < 0) return
  const from = panelTriggerPos
  const el = rawTextarea()
  const caret = el && typeof el.selectionStart === 'number' ? el.selectionStart : text.value.length
  const to = Math.max(caret, from + 1)
  text.value = text.value.slice(0, from) + text.value.slice(to)
  nextTick(() => {
    const ta = rawTextarea()
    if (!ta) return
    ta.focus?.()
    ta.setSelectionRange(from, from)
  })
  panelTriggerPos = -1
  panelTriggerCh = ''
}

/** a-textarea 的 ref 是组件实例，取内部原生 textarea（光标位置只能从原生元素读） */
const rawTextarea = () => textareaRef.value?.resizableTextArea?.textArea || textareaRef.value?.$el || null

/** 输入框内容/光标变化时同步筛选词与高亮：
 *  - 光标在触发字符之前 → 用户把触发符删了或移开了，收起面板
 *  - 触发字符被改成别的字符 → 同上
 *  - 筛选词里出现空白 → 这个"词"已经结束（@知识库 后面又打了字），收起面板
 *  高亮重置到第一条：候选集变了，原下标已无意义。 */
const syncPanelQuery = () => {
  if (!panelAnyOpen() || panelTriggerPos < 0) return
  const el = rawTextarea()
  if (!el) return
  const caret = typeof el.selectionStart === 'number' ? el.selectionStart : 0
  if (caret <= panelTriggerPos || text.value[panelTriggerPos] !== panelTriggerCh) {
    closeAllPanels()
    return
  }
  const q = text.value.slice(panelTriggerPos + 1, caret)
  // 空白 = 这个"词"已经结束，收起面板；例外是 / 命令的带参写法（/compact 保留结论）——
  // 别名打完整后敲的空格是在给命令传要求，此时收起面板就等于把参数那半句丢掉
  if (/\s/.test(q) && !(slashOpen.value && slashArgPending(q))) { closeAllPanels(); return }
  if (mentionOpen.value) mentionQuery.value = q
  else if (slashOpen.value) slashQuery.value = q
  else if (histOpen.value) histQuery.value = q
  setPanelHi(0)
  nextTick(scrollPanelHiIntoView)   // 高亮回到第一条后列表也跟着回去，否则筛完第一条停在可视区外
}

// 鼠标点候选：与 Enter 确认同一条路径（先摘掉触发片段再加 chip），避免两条路行为不一致
const pickMentionByClick = (type, item) => {
  stripTriggerToken()
  toggleMention(type, item)
  closeAllPanels()
}

// ==================== / 快捷命令面板（模板插入 + 会话操作） ====================
const slashOpen = ref(false)
const slashQuery = ref('')
const slashHi = ref(0)
// kind: tpl=插入问法框架到输入框（可再编辑）；act=会话级操作，立即执行
// 模板贴合本系统场景：资料类问法配合 @ 文档/知识库使用，检索类问法用于优化提问
// cmd=英文别名（打 /compact 直接命中，与主流 CLI 同一习惯），带别名的 act 命令可跟参数：「别名 + 空格 + 要求」
const slashCommands = [
  { key: 'tpl-summary', kind: 'tpl', icon: FileTextOutlined, name: '总结资料',
    desc: '生成总结问法框架，配合 @ 文档使用', tpl: '请总结以下内容的要点与结论：\n' },
  { key: 'tpl-translate', kind: 'tpl', icon: TranslationOutlined, name: '翻译内容',
    desc: '中英互译框架，保留专业术语', tpl: '请把以下内容翻译成英文，保留专业术语：\n' },
  { key: 'tpl-polish', kind: 'tpl', icon: EditOutlined, name: '润色改写',
    desc: '让文字更通顺、专业', tpl: '请润色以下文字，使其更通顺、专业：\n' },
  { key: 'tpl-explain', kind: 'tpl', icon: BulbOutlined, name: '通俗解释',
    desc: '用大白话解释概念并举例', tpl: '请用通俗的语言解释以下概念，并给出例子：\n' },
  { key: 'tpl-compare', kind: 'tpl', icon: CopyOutlined, name: '多维对比',
    desc: '多维度对比分析并给出建议', tpl: '请从多个维度对比分析以下内容，最后给出选择建议：\n' },
  { key: 'tpl-optimize', kind: 'tpl', icon: QuestionCircleOutlined, name: '优化提问',
    desc: '把问题改写为更适合知识库检索的表述', tpl: '请帮我优化下面这个问题的表述，使其更适合用于知识库检索：\n' },
  { key: 'act-compact', kind: 'act', cmd: 'compact', icon: CompressOutlined, name: '压缩上下文',
    desc: '早期对话压缩为摘要，腾出上下文空间（可附要求）',
    run: args => compactContext(args) },
  { key: 'act-export', kind: 'act', icon: FileTextOutlined, name: '导出会话 Markdown',
    desc: '把当前会话全部问答导出为 .md 文件',
    run: () => { if (!currentSessionId.value) { message.warning('当前没有可导出的会话'); return } exportSessionMarkdown(currentSessionId.value, currentSessionTitle.value) } },
  { key: 'act-new', kind: 'act', icon: PlusOutlined, name: '新建会话',
    desc: '开一个空白会话（当前会话保留在侧边栏）',
    run: () => createNewSession() }
]
const openSlashPanel = () => {
  addOpen.value = false
  histOpen.value = false
  mentionOpen.value = false
  slashOpen.value = true
  slashQuery.value = ''
  slashHi.value = 0
  if (panelTriggerCh !== '/') { panelTriggerPos = -1; panelTriggerCh = '' }
}
const closeSlashPanel = () => closeAllPanels()
// slashQuery 直接来自输入框正文（「/ 到光标」之间），不再需要剥唤起键
const slashQueryNorm = computed(() => slashQuery.value.trim())
/** / 命令的带参判定（/compact 保留结论）：别名打完整后再出现的空白，是在给命令传要求，不是在结束筛选 */
const slashArgPending = q => {
  const s = String(q || '').trimStart().toLowerCase()
  return slashCommands.some(c => c.cmd && s.startsWith(c.cmd.toLowerCase() + ' '))
}
/** 命令参数：筛选词里「别名 + 空格」之后的部分（原文切片，保留用户输入的大小写与用词） */
const slashArgsOf = c => {
  const cmd = (c.cmd || '').toLowerCase()
  if (!cmd) return ''
  const raw = slashQuery.value.trim()
  return raw.toLowerCase().startsWith(cmd) ? raw.slice(cmd.length).trim() : ''
}
const slashFiltered = computed(() => {
  const q = slashQueryNorm.value.toLowerCase()
  if (!q) return slashCommands
  return slashCommands.filter(c => {
    const cmd = (c.cmd || '').toLowerCase()
    // 别名：前缀补全（/com → 压缩上下文），或「别名 + 空格 + 参数」仍命中该命令
    if (cmd && (cmd.startsWith(q) || q.startsWith(cmd + ' '))) return true
    return c.name.toLowerCase().includes(q) || c.desc.toLowerCase().includes(q)
  })
})
const runSlashCommand = c => {
  // 参数先取：下面 stripTriggerToken/closeAllPanels 会把触发片段与筛选词清掉，取晚了就只能拿到空串
  const args = slashArgsOf(c)
  slashOpen.value = false
  if (c.kind === 'tpl') {
    // 模板替换掉「/ + 筛选词」这段触发文本（而不是追加到末尾），否则会留下 "/总结\n请总结…" 的残渣。
    // 未由键盘唤起（工具栏按钮点开）时 panelTriggerPos 为 -1，退回原有的追加行为。
    if (panelTriggerPos >= 0) {
      const from = panelTriggerPos
      const el0 = rawTextarea()
      const caret = el0 && typeof el0.selectionStart === 'number' ? el0.selectionStart : text.value.length
      const to = Math.max(caret, from + 1)
      text.value = text.value.slice(0, from) + c.tpl + text.value.slice(to)
      panelTriggerPos = -1
      panelTriggerCh = ''
      nextTick(() => {
        const ta = textareaRef.value
        if (!ta) return
        ta.focus?.()
        const el = ta.resizableTextArea?.textArea
        const at = from + c.tpl.length
        if (el) el.setSelectionRange(at, at)
      })
      return
    }
    // 追加而非替换：输入框已有内容时不吞掉用户已打的字；光标落到末尾方便接着补内容
    text.value = (text.value ? text.value.replace(/\s+$/, '') + '\n' : '') + c.tpl
    nextTick(() => {
      const ta = textareaRef.value
      if (!ta) return
      ta.focus?.()
      const el = ta.resizableTextArea?.textArea
      if (el) el.setSelectionRange(el.value.length, el.value.length)
    })
  } else {
    // act（立即执行的动作）不往正文插任何东西，触发片段必须摘掉，否则留下 "/新建会话" 残渣
    // （带参的命令同理："/compact 保留结论"整段都要摘走，参数已在上方取到）
    stripTriggerToken()
    closeAllPanels()
    c.run?.(args)
  }
}
// ==================== # 历史引用面板（勾选本会话历史问答 → 本轮上下文） ====================
const histOpen = ref(false)
const histQuery = ref('')
const histHi = ref(0)
const histQueryNorm = computed(() => histQuery.value.trim())
/** 历史候选口径（# 面板与「+」面板共用）：筛选 + 最近在前 + 最多列 60 条（防超长会话渲染卡顿） */
const histListOf = q => {
  const s = String(q || '').toLowerCase()
  const pool = histPool.value.filter(m => !s || String(m.content).toLowerCase().includes(s))
  return pool.slice(-60).reverse()
}
const histCandidates = computed(() => histListOf(histQueryNorm.value))
const histChipLabel = h => h.digest || ''
const pickHistoryRefByClick = m => {
  stripTriggerToken()
  toggleHistoryRef(m)
  closeAllPanels()
}
const openHistPanel = () => {
  addOpen.value = false
  slashOpen.value = false
  mentionOpen.value = false
  histOpen.value = true
  histQuery.value = ''
  histHi.value = 0
}
const closeHistPanel = () => closeAllPanels()

// ==================== 「+」面板（附件 / 技能 / 历史 / 命令 四页签） ====================
// 鼠标流入口：@ / # 由输入框敲字符唤起（触发面板的筛选通道依赖输入框正文），这条不插触发字符、
// 筛选用面板内输入框。两条流互斥 —— 开一个就收起另一个。
const addOpen = ref(false)
const addTab = ref('file')
const addHistQuery = ref('')
const addHistCandidates = computed(() => histListOf(addHistQuery.value))
const ADD_TABS = [
  { key: 'file', label: '附件', icon: PaperClipOutlined, count: () => pendingFiles.value.length + pendingImages.value.length },
  { key: 'skill', label: '技能', icon: AppstoreOutlined, count: () => pickedSkills.value.length },
  { key: 'plan', label: '计划', icon: OrderedListOutlined, count: () => planModeOn.value ? 1 : 0 },
  { key: 'hist', label: '历史', icon: HistoryOutlined, count: () => pendingHistoryRefs.value.length },
  { key: 'cmd', label: '命令', icon: ThunderboltOutlined, count: () => 0 }
]
const closeAddPanel = () => { addOpen.value = false }
const toggleAddPanel = () => {
  if (addOpen.value) { closeAddPanel(); return }
  closeAllPanels()          // 可能与 @ / / # 面板同屏，先收起它们
  addHistQuery.value = ''   // 每次打开从干净筛选开始（页签停留在上次看的那页）
  addOpen.value = true
}
/** 命令页点选：模板插入输入框（可再编辑）、操作立即执行；两者都收起面板。
 *  生成中「操作」与触发面板同口径禁用——那些命令会改会话状态，不能和流式回答打架 */
const runCmdFromAddPanel = c => {
  if (loading.value && c.kind === 'act') { message.info('生成中，请等这轮回答结束后再执行'); return }
  closeAddPanel()
  runSlashCommand(c)
}
/** 发送前收起「+」面板：它浮在输入区上方，留着会挡住回答 */
const sendNow = () => { closeAddPanel(); send() }
// ==================== 会话内查找（Ctrl/⌘+F） ====================
// 逻辑主体在 src/chat/useChatSearch.js：移动壳顶栏的查找入口用同一份。
// 两端靠消息行的 [data-row-index] 契约对齐（PC 内联模板与 MobileMsgRow 均已带），
// 高亮本体共用全局 app.css 的 ::highlight(chat-search) —— Custom Highlight API 不改 DOM，
// 正文是 v-html 渲染的，插 <mark> 会与重渲染/渲染缓存互相打架。
// messages / currentSessionId 用 getter 传入：它们来自下方引擎解构（const 声明在本调用点之后）。
const {
  searchOpen, searchQuery, searchPos, searchInputRef, matchedIdxs, searchPosShown,
  openSearch, closeSearch, gotoMatch, paintHighlight: paintSearchHighlight, scrollToMatch
} = useChatSearch({
  boxRef: box,
  messages: () => messages.value
})
// Ctrl/⌘+F 打开会话内查找（在聊天页拦下浏览器原生查找，与主流产品一致）；Esc 关闭
const onSearchHotkey = e => {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')) {
    e.preventDefault()
    searchOpen.value ? searchInputRef.value?.focus?.() : openSearch()
  } else if (e.key === 'Escape' && searchOpen.value) {
    closeSearch()
  }
}
onMounted(() => document.addEventListener('keydown', onSearchHotkey))
onUnmounted(() => document.removeEventListener('keydown', onSearchHotkey))

// ==================== 会话分享（只读链接） ====================
// 分享的是"这段对话"：链接持有者可看不可续聊。链接展示的是会话**最新内容**（后端按 token 实时读，
// 不是快照）——所以弹窗里必须讲清楚，否则用户后续聊到敏感内容时不会意识到分享页也跟着变了。
// 状态机落到 src/views/shareSession.js：移动「状态与来源」sheet 用同一份（同一会话只有一条分享、
// 换链接会使旧链接失效，两端必须同口径），这里只留 PC 的显示态与预览动作。
const shareVisible = ref(false)
const {
  loading: shareLoading, info: shareInfo, url: shareUrl,
  load: loadShare, enable: enableShare, disable: stopShare, copyLink: copyShareLink
} = useSessionShare({ sessionId: () => currentSessionId.value })

// 顶栏/移动头分享图标的激活态：与侧栏会话行的分享标记**同源**（sessionStore.list[].shared，
// 后端会话列表按 enabled=1 下发）。开启与停止分享的每个入口（PC 弹窗、移动 sheet、个人设置→
// 分享管理）都 markSessionShared 回写过这个字段，所以顶栏与侧栏天然同态、也不为图标多发一次请求；
// 页面刷新后由 loadSessions 的权威值兜底。当前会话不在已加载页里时同样查不到（= 侧栏也看不到它）。
const shareActive = computed(() =>
  sessionStore.list.some(s => s.id === currentSessionId.value && !!s.shared))

const openShare = async () => {
  if (!messages.value.length) { message.info('当前会话还没有内容可分享'); return }
  shareVisible.value = true
  await loadShare()
}

// 侧栏会话菜单「分享」：切会话后打开这个弹窗（菜单在 AppLayout，是本页的兄弟组件，
// 只能靠 store 信号通信）。**必须等历史到位再开** —— 菜单是「先跳路由再发信号」，
// 此刻 messages 还是上一条会话的或空的；直接调 openShare 会被 messages.length 守卫挡掉。
// 轮询上限 2s：异常情况下（历史 403/空）也退出，不能无限等 —— 之后用户可点顶栏图标手动开。
const consumeShareOpen = async () => {
  for (let i = 0; i < 40 && !messages.value.length; i++) await new Promise(r => setTimeout(r, 50))
  await openShare()
}
watch(() => sessionStore.shareOpenTick, () => { if (route.path === '/chat') consumeShareOpen() })
/** 生成或换新链接（后端对已开启的分享再次开启会换 token，旧链接立即失效）。
 *  刻意包一层而非直接指过去：模板 @click 会把 MouseEvent 当第一个实参传进来，
 *  形参取 regenerate 时「生成」会被当成「换新」（!!event === true）。 */
const doEnableShare = () => enableShare(false)
const regenerateShareLink = () => enableShare(true)
const doDisableShare = () => stopShare()
const openSharedPage = () => { if (shareUrl.value) window.open(shareUrl.value, '_blank', 'noopener') }
// 输入框回车发送（Enter 发送，Shift+Enter 换行；输入法组合中不发送）
const onInputKeydown = e => {
  if (e.isComposing || e.keyCode === 229) return   // 输入法组合中（中文候选未上屏）一律放行
  // 面板打开时由输入框接管导航：↑↓ 移动高亮、Enter 确认、Esc 关闭
  if (mentionOpen.value || slashOpen.value || histOpen.value) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      movePanelHi(e.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      closeAllPanels()
      return
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      // 有高亮候选才由 Enter 确认；没有候选（筛空了）时 Enter 仍走发送，避免"打不中就发不出去"
      if (confirmPanelHi()) { e.preventDefault(); return }
    }
  }
  // 「+」面板不参与键盘导航：Esc 收起它，Enter 照常发送
  if (addOpen.value && e.key === 'Escape') {
    e.preventDefault()
    closeAddPanel()
    return
  }
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendNow()
    return
  }
  // @ / # 唤起候选面板：**不拦下字符**——它是普通文本，用户要在任意位置都能打出来。
  // 唤起后焦点留在输入框，筛选词由 syncPanelQuery 从「触发字符到光标」之间的正文实时取。
  // 触发位置仍限「行首 / 空白后」，避免邮箱 a@b.com、URL 里的 / 误开面板。
  if ((e.key === '@' || e.key === '#' || e.key === '/') && !e.ctrlKey && !e.metaKey && !e.altKey) {
    const ta = e.target
    const pos = ta && typeof ta.selectionStart === 'number' ? ta.selectionStart : 0
    const before = text.value.slice(0, pos)
    if (!before || /[\s（(【[]$/.test(before)) {
      // 记录触发字符即将落在的位置（keydown 时字符还没进 DOM，插入后下标即 pos）
      panelTriggerPos = pos
      panelTriggerCh = e.key
      if (e.key === '@') openMentionPanel()
      else if (e.key === '#') openHistPanel()
      else openSlashPanel()
    }
    return
  }
}
const copyAnswer = async mi => {
  const m = messages.value[mi]
  if (!m || !m.content) { message.warning('该回答无可复制内容'); return }
  await copyText(m.content.trim())
}
/** 复制用户的问题（长会话里把问题转走/复问用；此前只有回答能复制） */
const copyUserMessage = m => {
  const txt = (m?.content || '').trim()
  if (!txt) { message.warning('该消息无可复制内容'); return }
  copyText(txt, '已复制')
}

// 删除本轮对话（回答 + 同组问题一起软删除）；导出 Markdown（该轮问答）
const onMoreAction = (key, mi) => {
  if (key === 'debug') openDebug(mi)
  else if (key === 'addEval') addToEval(mi)
  else if (key === 'deleteRound') deleteRound(mi)
  else if (key === 'export') exportRound(mi)
}
/** 差评回流一键固化：本轮「问题 → 引用的知识块」追加进检索评测集（后端按问题去重） */
const addToEval = async mi => {
  const m = messages.value[mi]
  const kids = [...new Set((m.sources || []).map(s => s.knowledgeId).filter(Boolean))]
  if (!kids.length) { message.warning('本轮没有可固化的知识块引用'); return }
  let question = null
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') { question = messages.value[i]; break }
    if (messages.value[i].role === 'assistant' || messages.value[i].role === 'ai') break
  }
  if (!question?.content) { message.warning('找不到本轮对应的提问'); return }
  try {
    const r = await addEvalCase(question.content, kids)
    if (r && r.success !== false) {
      if (r.data?.added === false) message.info(r.data.reason || '评估集已存在相同问题')
      else message.success(`已加入评测集（期望块 ${r.data?.expected ?? kids.length} 个，可在检索评估页跑回归）`)
    } else message.error(r?.msg || '加入评测集失败')
  } catch (e) { message.error(e.message || '加入评测集失败') }
}
// 单轮导出：向 mi 前配对最近的用户提问（遇更早回答即停）
const exportRound = mi => {
  const m = messages.value[mi]
  if (!m || !m.content) { message.warning('该回答无可导出内容'); return }
  let question = null
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') { question = messages.value[i]; break }
    if (messages.value[i].role === 'assistant' || messages.value[i].role === 'ai') break
  }
  exportAnswerMd({ answer: m, question, title: currentSessionTitle.value })
}
const deleteRound = async mi => {
  const mid = messages.value[mi]?.messageId
  if (!mid) { message.warning('该轮对话不可删除'); return }
  try {
    const r = await deleteMessageGroup(mid)
    if (r.success) {
      // 本地移除该回答与其前面的用户问题
      let from = mi
      if (mi > 0 && messages.value[mi - 1]?.role === 'user') from = mi - 1
      messages.value.splice(from, mi - from + 1)
      message.success('已删除本轮对话')
      loadSessions()
    } else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// 检索调试
const debugVisible = ref(false)
const debugLoading = ref(false)
const debugQuestion = ref('')
const debugResult = ref(null)
const openDebug = mi => {
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') { debugQuestion.value = messages.value[i].content; break }
  }
  debugVisible.value = true
  runDebug()
}
const runDebug = async () => {
  const q = debugQuestion.value.trim()
  if (!q) { message.warning('请输入问题'); return }
  debugLoading.value = true
  debugResult.value = null
  try {
    const r = await debugRetrieval(q)
    if (r.success) debugResult.value = r.data
    else message.error(r.msg || '调试失败')
  } catch (e) { message.error(e.message || '调试失败') }
  finally { debugLoading.value = false }
}
const debugStages = computed(() => {
  const d = debugResult.value
  if (!d) return []
  const map = (items, tagFn) => (items || []).map(it => ({
    title: it.title || '（无标题）',
    docName: it.docName || '',
    snippet: it.snippet || '',
    tag: tagFn(it)
  }))
  return [
    { name: `关键词命中（${(d.keywordHits || []).length}）`, items: map(d.keywordHits, it => '命中率 ' + (it.hitRate ?? 0)) },
    { name: `向量命中（${(d.vectorHits || []).length}）`, items: map(d.vectorHits, it => '相似度 ' + (it.score ?? 0)) },
    { name: `合并后（${(d.merged || []).length}）`, items: map(d.merged, it => '分 ' + (it.score ?? 0)) },
    { name: `重排后（${(d.reranked || []).length}）` + (d.rerankApplied ? '' : `（${d.rerankSkipReason || '未重排'}）`), items: map(d.reranked, it => '分 ' + (it.score ?? 0)) },
    { name: `最终上下文（${(d.finalContext || []).length}/8）`, items: map(d.finalContext, it => '分 ' + (it.score ?? 0)) },
    { name: `被排除（${(d.excluded || []).length}）`, items: map(d.excluded, it => '分 ' + (it.score ?? 0)) }
  ]
})

// ==================== 消息编辑重发（从该轮重新生成，旧分支可回看切换） ====================
// 就地编辑：点「编辑」后编辑卡原位替换该气泡（右对齐紧凑卡），确认后从这一轮整段重新生成——
// 被编辑消息及其后的旧分支由后端软删留档（variant_group/variant_tail），可随时切回。
// 编辑重发与直接回填输入框（旧行为，发起新一轮）是两个入口：这里走 editMessageId 分支链路。
const editingIdx = ref(null)
const editingText = ref('')
const editingBusy = ref(false)
const editingRef = ref(null)
// 随行内容：从原消息播种，编辑期内可增删（图片/附件与主输入框同构同管线；@ 引用只可移除；
// 深度思考/技能/# 历史引用不进编辑卡，沿用原轮）。历史回放的图片只剩服务端 URL，重发时按 data: 过滤
const editImgs = ref([])      // [{ dataUrl }]
const editAtts = ref([])      // [{ name, mime, size, fileId, uploading, error }]，attachData+attachments 合并态
const editMentions = ref([])  // [{ type, id, name, kbId }]
const editAttachInput = ref(null)
// v-for 内的模板 ref 走函数式（ref 属性在循环里会聚集成数组，取值麻烦）
const setEditingRef = el => { editingRef.value = el }
const setEditAttachInputRef = el => { editAttachInput.value = el }

// 输入区自适应高度：起步单行随内容长高，超过 200px 内部滚动（与紧凑卡外形匹配）
const autosizeEditing = () => {
  const el = editingRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

const editMessage = mi => {
  if (loading.value || editingBusy.value) return
  const m = messages.value[mi]
  if (!m || m.role !== 'user') return
  if (!m.messageId) { message.warning('该消息尚未落库（流式回答中），请稍后再编辑'); return }
  // 已有编辑框在别处打开：切换目标
  editingIdx.value = mi
  editingText.value = m.content
  editImgs.value = (m.images || []).map(u => ({ dataUrl: u }))
  // attachData（重发载荷 {name,mime,fileId}）与 attachments（展示 {name,mime,size}）合并成一份可编辑列表
  const metaByName = new Map((m.attachments || []).map(a => [a.name, a]))
  editAtts.value = (m.attachData || []).map(a => {
    const meta = metaByName.get(a.name) || {}
    // paste/text 只在展示侧（本轮内存消息）有：不带回来编辑卡就退化成普通文件条，也点不开全文
    return { size: meta.size, paste: isPastedText(meta), text: meta.text, uploading: false, error: '', ...a }
  })
  editMentions.value = [...(m.mentions || [])]
  nextTick(() => { autosizeEditing(); editingRef.value?.focus() })
}
const cancelEdit = () => {
  editingIdx.value = null
  editingText.value = ''
  editImgs.value = []; editAtts.value = []; editMentions.value = []
}

// ---- 编辑卡的随行内容操作：管线完全复用主输入框，只换目标列表 ----
const pickEditFiles = () => editAttachInput.value?.click()
const onEditAttachChange = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  addFiles(files, { images: editImgs, files: editAtts })
}
const onEditPaste = e => {
  const imgs = Array.from(e.clipboardData?.files || []).filter(f => f.type.startsWith('image/'))
  if (!imgs.length) {
    if (takePastedText(e, { files: editAtts })) e.stopPropagation()
    return
  }
  e.preventDefault()
  e.stopPropagation()   // 截住冒泡：window 级粘贴监听会把图挂到主输入框去
  addImageFiles(imgs, editImgs)
}
const removeEditImage = i => editImgs.value.splice(i, 1)
const removeEditAtt = i => editAtts.value.splice(i, 1)
const removeEditMention = i => editMentions.value.splice(i, 1)
const previewEditImage = pi => {
  openImages(editImgs.value.map(p => resolveImg(p.dataUrl)), pi)
}
// 键盘：Enter 发送 / Shift+Enter 换行 / Esc 取消；输入法组合中的 Enter/Esc 是候选操作，放行给 IME
const onEditKeydown = e => {
  if (e.isComposing || e.keyCode === 229) return
  if (e.key === 'Enter') {
    if (!e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) { e.preventDefault(); confirmEdit() }
    return
  }
  if (e.key === 'Escape') { e.preventDefault(); cancelEdit() }
}

const confirmEdit = () => {
  const mi = editingIdx.value
  if (mi == null || editingBusy.value) return
  const old = messages.value[mi]
  const txt = editingText.value.trim()
  if (!old || old.role !== 'user') { cancelEdit(); return }
  if (editAtts.value.some(f => f.uploading)) { message.warning('附件还在上传中，请稍候再发送'); return }
  // 附件只带 fileId（与主输入框 send 同口径），没传完/失败的丢弃
  const okAtts = editAtts.value.filter(f => f.fileId && !f.error)
  const atts = okAtts.map(f => ({ name: f.name, mime: f.mime, fileId: f.fileId }))
  const attsMeta = okAtts.map(f => ({ name: f.name, mime: f.mime, size: f.size, paste: !!f.paste, text: f.text }))
  const imgsAll = editImgs.value.map(p => p.dataUrl)
  // 只有本会话的 data: 图能重发（历史回放的图只剩服务端 URL，与重新生成的口径一致）
  const imgs = imgsAll.filter(u => u.startsWith('data:'))
  const mentionsNew = [...editMentions.value]
  if (!txt && !imgs.length && !atts.length) { message.warning('内容不能为空'); return }
  // 什么都没改=没有分支语义，直接收起，不打无谓的重发（文本/图片/附件/@ 引用任一变了才重发）
  const listEq = (a, b) => a.length === b.length && a.every((x, k) => x === b[k])
  const attEq = (a, b) => a.length === b.length && a.every((x, k) => x.name === b[k].name && x.mime === b[k].mime && x.fileId === b[k].fileId)
  const mentionEq = (a, b) => a.length === b.length && a.every((x, k) => x.type === b[k].type && x.id === b[k].id)
  if (txt === old.content && listEq(imgsAll, old.images || [])
      && attEq(atts, Array.isArray(old.attachData) ? old.attachData : [])
      && mentionEq(mentionsNew, Array.isArray(old.mentions) ? old.mentions : [])) { cancelEdit(); return }
  if (loading.value) { message.warning('当前正在回答，请先停止或稍候'); return }
  const editMessageId = old.messageId
  if (!editMessageId) { message.warning('该消息尚未落库，暂不能编辑重发'); return }
  cancelEdit()
  // 本地视图截断到该轮之前：旧分支整体交给后端软删留档，本地不再渲染（切回走分支切换接口+重拉历史）
  const skills = Array.isArray(old.skills) ? old.skills : []
  const historyRefs = Array.isArray(old.historyRefs) ? old.historyRefs : []
  const deep = !!old.deepThink
  messages.value = messages.value.slice(0, mi)
  // 新分支的用户消息：档位/技能/# 历史引用沿用原轮，正文/图片/附件/@ 引用以编辑后的为准；
  // 版本计数 +1（旧值缺省=首次编辑即 2 版）
  const verN = (old.variantCount || 1) + 1
  const nu = reactive({
    role: 'user', content: txt,
    images: imgsAll, attachments: attsMeta, attachData: atts,
    skills, mentions: mentionsNew, historyRefs, deepThink: deep,
    time: Date.now(), messageId: null,
    variantCount: verN, variantIndex: verN
  })
  messages.value.push(nu)
  streamAnswer(txt, imgs, null, false, 1, deep, atts, skills, mentionsNew, null, historyRefs, editMessageId, nu)
}
const ask = q => { text.value = q; nextTick(send) }

// 贴底自动滚动（上翻回看历史暂停跟随）
const scroll = () => nextTick(() => {
  if (box.value && stickToBottom.value) {
    box.value.scrollTop = box.value.scrollHeight
    stickToBottom.value = true
  }
})
const scrollForce = () => nextTick(() => {
  if (box.value) {
    box.value.scrollTop = box.value.scrollHeight
    stickToBottom.value = true
  }
})
// 尾随留白：问题顶到内容底的距离补到一屏（含容器上内边距），贴底落点即问题置顶。
// 量的是「问题顶→内容底」，与留白自身无关，故可在留白生效时重复计算而不自激。
const updateTailSpacer = () => {
  const el = box.value
  if (!el) return
  const users = el.querySelectorAll('.row.user')
  const q = users[users.length - 1]
  if (!q) { tailSpacer.value = 0; return }
  const padTop = parseFloat(getComputedStyle(el).paddingTop) || 0
  const qTop = q.getBoundingClientRect().top - el.getBoundingClientRect().top
  // 减的是 DOM 上留白的实际高度（不是 ref：ref 会先于渲染领先一两拍，那样量出的内容底偏小，
  // 留白算大、贴底就一点点越过落点，且渲染越重越明显）
  const sp = el.querySelector('.tail-spacer')
  const spH = sp ? sp.getBoundingClientRect().height : 0
  const tail = (el.scrollHeight - spH) - (el.scrollTop + qTop)
  tailSpacer.value = Math.max(0, Math.round(el.clientHeight - padTop - tail))
}

// ==================== 聊天引擎（M1 引擎抽取：逻辑主体在 src/chat/useChatEngine.js） ====================
// 接缝：滚动/收面板/聚焦经 hooks 回到视图（保持原 DOM 行为），路由落点 chatPath='/chat'。
// 引擎调用必须放在 scroll/updateTailSpacer/closeAllPanels/focusInput 这些 const 之后（TDZ）。
const {
  // 输入与发送
  text, canSend, send, stop, steersPending, insertSteer, editSteer, dropSteer,
  streamAnswer, resolveApproval, pickAskOption, commitAskCustom, askSubmitAll, ignoreAsk,
  // 计划模式（人在回路）：「+」面板开关 + 计划卡裁决动作
  planModeOn, setPlanMode, submitPlanApproval, continuePlanConversation,
  // 思考能力 / 档位（悬浮面板消费）
  thinkCapsOf, reasoningLevelsOf, deepOnOf, levelOptionsOf, currentLevelOf, setThinkLevel, deepThinkOn,
  // 上下文窗口档位（面板点选 + 发送载荷）
  ctxRangeOf, ctxWindowOptionsOf, effectiveCtxWindowOf, setCtxWindow,
  // 技能
  skillList, pickedSkills, toggleSkill, skillAvaStyle,
  // 智能体
  agentList, hasDefaultAgent, AUTO_AGENT, currentAgentId, agentLocked, isAdmin, agentPickerOpen,
  currentAgentName, currentAgent, pickAgent, showAgentTag, modelSwitchInfo, loadAgents,
  // 会话与消息
  currentSessionId, loading, messages, currentSessionTitle, roundCount,
  // 模型
  currentOverrideModel, modelIndex, effectiveModel, effectiveModelLabel, effectiveModelIcon,
  effectiveModelProvider, debugEntryVisible, debugDisplayVisible,
  // 空态示例问题（配置驱动：个人覆盖 > 系统全局，可关可自定义）
  sampleQuestions,
  // 检索 / 用量 / 来源
  lastAi, lastRetrieved, lastSources, groupedSources, lastTokens, ctxTokens, ctxCapData, ctxRingDash,
  ctxRingLevel, panelAi, retryPanelRound, sessionArtifacts, sessionTokens, sessionTokensLabel, sessionTodos,
  sessionRetrieval, ctxPct, ctxLevel,
  // 图片与附件
  pendingImages, addImageFiles, removePendingImage, MAX_FILES, pendingFiles, hasUploadingFile, addFiles,
  removePendingFile, takePastedText,
  // @ 引用
  mentionOpen, mentionTab, mentionQuery, mentionLoading, mentionKbs, mentionDocs, pendingMentions,
  mentionHi, isMentioned, toggleMention, removeMention, switchMentionTab, loadMentionCandidates,
  mentionDocStatus, mentionKbFiltered, mentionAgents, mentionAgentFiltered, mentionDocFiltered,
  // # 历史引用
  pendingHistoryRefs, histPool, isHistPicked, toggleHistoryRef, removeHistoryRef,
  // 会话生命周期 / 重新生成 / 分支 / 初始化
  switchSession, createNewSession, regenerate, variantSwitching, switchBranch, ready,
  // 手动压缩上下文（/compact：命令面板与结果条）
  compactNotice, compactContext
} = useChatEngine({
  chatPath: '/chat',
  // 计划模式（人在回路）只在 PC 壳开放入口：移动壳没有计划卡 UI，引擎侧按此开关兜底拦下
  // （localStorage 桌面/移动同源共享，防止桌面开的开关让移动端发出没人能批准的计划问答）
  planModeEnabled: true,
  scrollFollow: () => nextTick(() => { updateTailSpacer(); scroll() }),
  scrollForce: () => nextTick(() => { updateTailSpacer(); scrollForce() }),
  scrollSoft: () => scroll(),
  closePanels: closeAllPanels,
  focusInput
})

// 右栏「任务清单」卡：有清单就默认展开（用户开这个功能就是为了看见还剩几步，收着没意义）
const todoOpen = ref(true)
const todoDone = computed(() => (sessionTodos.value?.items || []).filter(i => i.status === 'done').length)

// ===== 右栏「计划」折叠态 =====
// **必须声明在 useChatEngine 解构之后**：下面的 watch 会立即求值 sessionPlans 的 getter，
// 而 sessionPlans 依赖引擎解构出的 messages。若这段排在解构之前，getter 会在
// messages 的 const 还未初始化时被调用，抛 TDZ「Cannot access 'S' before initialization」
// （表现为 ChatPage 渲染函数内报错）。
// 原先 sessionPlans 独占一格时无事，正因为没有任何东西在 setup 阶段急切读它；
// 加了这个 watch 才暴露出来。
//
// 计划卡默认折叠（沉底），但**有待确认计划时自动展开**：
// 计划模式在等人批准，藏起来等于让「模型在等你裁决」这件事消失在侧栏底部——
// 这是少数「折叠起来会出事」的状态，故用 watch 顶开而非让用户自己发现。
const planOpen = ref(false)
const planHeadText = computed(() => {
  const list = sessionPlans.value
  if (list.some(p => p.status === 'pending')) return '待确认'
  if (list.some(p => p.status === 'drafting')) return '生成中'
  return list[0] ? planStateText(list[0].status) : ''
})
watch(() => sessionPlans.value.map(p => p.status).join(','),
  (cur, prev) => {
    // 只在「出现了待确认/生成中」时自动展开；用户手动收起后不反复弹回（除非状态真的又变成待确认）
    if (cur === prev) return
    if (/pending|drafting/.test(cur)) planOpen.value = true
  })

// 空态引导用：本轮 @ 引用的资料名（副标题会说明「只在这些资料里找答案」）
const mentionNames = computed(() => pendingMentions.value.map(m => m.name || m.id))

// # 历史问答引用气泡预览（历史回显用）：截断内容做 chip 文案
const historyRefPreview = hr => {
  const c = (hr && hr.content) || ''
  const plain = c.replace(/\s+/g, ' ').trim()
  if (!plain) return '历史问答'
  return plain.length > 20 ? plain.slice(0, 20) + '…' : plain
}

// ==================== 计划确认栏（替换聊天输入框） ====================
// 待批准期间打字框整块顶替为确认栏（模型在等裁决，此刻也没法发新消息），与提问面板同构：
// 编号选项 = 1. 确认（点即按原稿执行）/ 2. 继续对话（写对计划的修改要求，非空才能送）。
// 继续对话=意见作为一条用户消息发出（同一会话继续对话）：本版计划作废（已被取代），
// 新一轮完整走一遍（重新检索），模型带着上一版计划按意见重做——可反复，直到「确认」才执行。
// 计划正文同时在气泡里只读展示。底部「取消」= 提问面板的「忽略」。
const pendingPlan = computed(() => {
  const list = messages.value
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    if (m && m.role === 'ai' && m.planCard && m.planCard.status === 'pending') return m
  }
  return null
})
const curPlanCard = computed(() => pendingPlan.value?.planCard || null)
// 倒计时心跳（仅待确认卡挂 deadline 时走表，与提问卡的 askNow 同一套节奏）
const planNow = ref(Date.now())
let planTimer = null
watch(() => messages.value.some(m => m.planCard && m.planCard.status === 'pending' && m.planCard.deadline), v => {
  if (v && !planTimer) planTimer = setInterval(() => { planNow.value = Date.now() }, 1000)
  if (!v && planTimer) { clearInterval(planTimer); planTimer = null }
})
onUnmounted(() => { if (planTimer) clearInterval(planTimer) })
// 已过 deadline（后端超时收尾的 plan_cancelled 马上到）：操作锁住，避免批准必然失败的那一下
const planExpired = computed(() => {
  const c = curPlanCard.value
  return !!(c && c.deadline && planNow.value >= c.deadline)
})
// 倒计时文本（内部用：planNow 触发重算；格式与提问卡一致 mm:ss）
const planCountdownText = computed(() => {
  const c = curPlanCard.value
  if (!c || !c.deadline) return ''
  const s = Math.max(0, Math.ceil((c.deadline - planNow.value) / 1000))
  return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0')
})
// —— 修改记录（同轮内屡次退回重出的版本链）与长计划折叠 ——
// 版本链 = 已被改掉的各版（c.versions，feedback=产出该版的意见）+ 当前版（c.plan 在卡片主体，链尾也列一份）
const planChain = c => {
  if (!c) return []
  const list = Array.isArray(c.versions) ? c.versions.slice() : []
  list.push({ plan: c.plan || '', feedback: c.feedback || '' })
  return list
}
// 版本行尾标：首版=模型原稿；末版=终态/当前版；中间版不标
const planVersionTag = (c, vi, total) => {
  if (vi === 0) return ' · 模型原稿'
  if (vi !== total - 1) return ''
  if (c.status === 'approved') return ' · 已批准'
  if (c.status === 'rejected') return ' · 未批准'
  return ' · 当前版'
}
// 长计划紧凑预览（参考 zcode 形态：渐隐 + 「查看完整计划」）；短计划原样铺开、不出按钮
const planNeedsFold = plan => {
  const s = String(plan || '')
  if (!s.trim()) return false
  return s.split('\n').filter(x => x.trim()).length > 5 || s.length > 200
}

// ==================== 智能体提问面板（替换聊天输入框） ====================
// 当前会话存在挂起中的提问时，底部聊天输入框整块替换为提问面板（模型在等答案，此刻也没法发新消息）；
// 答复后工具终态到达（msg.ask 清空），面板撤下、输入框回归，问答记录以工具卡形态留在气泡原位。
// 键盘：Tab/↑↓ 在「选项+自定义输入行」间循环，回车/空格确认（输入框内回车=提交自定义答案）。
const pendingAsk = computed(() => {
  const list = messages.value
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    if (m && m.role === 'ai' && m.ask) return m
  }
  return null
})
const askPage = ref(0)              // 当前翻页（0 基）：一卡多问逐题展示
const askCustomRef = ref(null)
const askNow = ref(Date.now())      // 倒计时心跳（仅挂起中有 deadline 时走表）
const askHi = ref(null)             // 键盘高亮行（0..n-1=选项，n=自定义）；null=不高亮（推荐项不预选）
let askTimer = null
// 当前展示的问题（随翻页切换）；越界时回退到最后一题
const curAsk = computed(() => {
  const a = pendingAsk.value?.ask
  if (!a || !a.questions.length) return { topic: '', question: '', options: [] }
  const p = Math.min(askPage.value, a.questions.length - 1)
  return a.questions[p] || { topic: '', question: '', options: [] }
})
// 已答（已确认）题数：只看 sels（选项点选或自定义已确认）。自定义「已输入但未确认」不算，
// 否则自动提交会在用户刚敲第一个字时就触发。
const askAnsweredCount = computed(() => {
  const a = pendingAsk.value?.ask
  if (!a) return 0
  return a.sels.filter(s => s != null).length
})
// 剩余毫秒 / 是否已超时 / 倒计时文本（mm:ss）
const askRemaining = computed(() => {
  const a = pendingAsk.value?.ask
  if (!a || !a.deadline) return 0
  return Math.max(0, a.deadline - askNow.value)
})
const askExpired = computed(() => {
  const a = pendingAsk.value?.ask
  return !!(a && a.deadline && !a.answered && askRemaining.value <= 0)
})
const askCountdownText = computed(() => {
  const a = pendingAsk.value?.ask
  if (!a || !a.deadline || a.answered) return ''
  const s = Math.ceil(askRemaining.value / 1000)
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return mm + ':' + ss
})
watch(() => pendingAsk.value?.ask, (a) => {
  askPage.value = 0
  askHi.value = null
  // 仅在有 deadline 时启心跳；归零后停止（由 onToolStatus 收卡，或用户已提交）
  if (a && a.deadline) {
    // 挂卡的这一刻就先对表：后台标签页里 1s 心跳会被节流到分钟级，不校准则首屏倒计时
    // 按上一次 askNow 的旧值算，能比真实剩余多出好几分钟（2026-10-07 真机复测见到 11:32 / 10:00）
    askNow.value = Date.now()
    if (!askTimer) askTimer = setInterval(() => { askNow.value = Date.now() }, 1000)
  } else if (askTimer) {
    clearInterval(askTimer); askTimer = null
  }
  nextTick(() => { const el = askCustomRef.value; if (el && el.focus) el.focus() })
}, { flush: 'post' })
// 手动翻页 / 自动跳题后清掉键盘高亮，避免高亮行号与新题不符
watch(askPage, () => { askHi.value = null })
watch(askExpired, (exp) => { if (exp && askTimer) { clearInterval(askTimer); askTimer = null } })
// 选项展示拆分：「关键词：说明」→ 关键词加粗 + 说明弱化；无冒号时整句作为关键词
const askOptionParts = (op) => {
  const s = String(op || '')
  const i = s.search(/[：:]/)
  return i > 0 ? { kw: s.slice(0, i), rest: s.slice(i + 1).replace(/^[：:]\s*/, '') } : { kw: s, rest: '' }
}
// 高亮移动时同步焦点：落到自定义输入行→聚焦（可直接打字），落到选项行→失焦（避免误输入）
const focusAskRow = () => {
  const n = (curAsk.value.options || []).length
  nextTick(() => {
    const el = askCustomRef.value
    if (!el) return
    if (askHi.value === n) el.focus()
    else if (document.activeElement === el) el.blur()
  })
}
// 点选某题的一个选项：记录选择 → 自动跳到下一道未答题
const onPick = (oi) => {
  const m = pendingAsk.value
  if (!m || !m.ask || m.ask.busy || m.ask.answered || askExpired.value) return
  pickAskOption(m, askPage.value, oi)
  askHi.value = null
  advanceAfterSelect(askPage.value)
}
// 确认某题自定义答案：回车（doAdvance=true）确认并跳下一题；失焦只确认不跳题，
// 避免「先打字再点某个选项」时 blur 先触发跳题、导致随后的 click 记到别的题上
const commitCustomAt = (page, doAdvance) => {
  const m = pendingAsk.value
  if (!m || !m.ask || m.ask.busy || m.ask.answered || askExpired.value) return
  commitAskCustom(m, page)
  if (doAdvance && m.ask.sels[page] != null) {
    askHi.value = null
    advanceAfterSelect(page)
  }
}
// 选完本题后跳到下一道未答题；都答完则停在末题（等待自动提交）
const advanceAfterSelect = (page) => {
  const a = pendingAsk.value?.ask
  if (!a) return
  const next = a.questions.findIndex((q, i) => i > page && a.sels[i] == null)
  if (next >= 0) { askPage.value = next; return }
  const firstUnanswered = a.questions.findIndex((q, i) => a.sels[i] == null)
  askPage.value = firstUnanswered >= 0 ? firstUnanswered : a.questions.length - 1
}
// 全部题都确认过 → 自动提交（只触发一次：提交后 answered/busy 置位，watch 条件不再满足）
watch(() => {
  const a = pendingAsk.value?.ask
  return a && !a.busy && !a.answered && !askExpired.value && a.questions.length > 0
    && a.sels.filter(s => s != null).length === a.questions.length
}, (done) => { if (done) askSubmitAll(pendingAsk.value) })
const onAskPanelKeydown = (e) => {
  const a = pendingAsk.value?.ask
  if (!a || a.busy || a.answered || askExpired.value) return
  const total = (curAsk.value.options || []).length + 1   // 最后一格=自定义输入行
  const inInput = e.target && e.target.tagName === 'INPUT'
  const cur = askHi.value == null ? (a.sels[askPage.value] == null ? -1 : a.sels[askPage.value]) : askHi.value
  if (e.key === 'ArrowDown' || (e.key === 'Tab' && !e.shiftKey)) {
    askHi.value = (cur + 1) % total
    focusAskRow()
    e.preventDefault()
  } else if (e.key === 'ArrowUp' || (e.key === 'Tab' && e.shiftKey)) {
    askHi.value = (cur - 1 + total) % total
    focusAskRow()
    e.preventDefault()
  } else if (e.key === 'Enter' || (e.key === ' ' && !inInput)) {
    // 回车/空格=确认当前高亮项（点选或自定义），确认后自动跳下一题
    if (askHi.value == null) { e.preventDefault(); return }
    if (askHi.value < (curAsk.value.options || []).length) onPick(askHi.value)
    else commitCustomAt(askPage.value)
    e.preventDefault()
  }
}

// ==================== 手动压缩摘要弹窗（/compact 结果条「查看摘要」） ====================
// 打开时快照摘要与用量：发送下一轮会清掉 compactNotice，弹窗若直接读它会当场变空
const compactSummaryOpen = ref(false)
const compactSummaryText = ref('')
const compactSummaryTokens = ref(0)
const openCompactSummary = () => {
  compactSummaryText.value = compactNotice.value?.summary || ''
  compactSummaryTokens.value = compactNotice.value?.summaryTokens || 0
  compactSummaryOpen.value = true
}

// 开始回答即收起悬浮思考面板（loading 声明后才能 watch，getter 在 watch 调用时同步执行）
watch(() => loading.value, v => { if (v) hideThinkPanel() })
watch(loading, v => { if (!v) disarmEsc() })
// 切会话/消息增删后旧 Range 已失效：重算（数组引用变化即触发；流式增量不重算，避免每 token 全量扫描）
watch(messages, () => {
  if (!searchOpen.value || !searchQuery.value.trim()) return
  searchPos.value = 0 // 换会话后从头开始，避免停在上一个会话的偏移上
  nextTick(() => { paintSearchHighlight(); scrollToMatch() })
})
// Mermaid 图表的补画已随正文下沉到 AnswerBody（每个 .md 容器画自己那份，且同样只在非流式态画：
// 流式期间 ```mermaid 围栏是半截代码，画必然失败，每个 token 重试一次纯属浪费）。

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('paste', onGlobalPaste)
  window.addEventListener('resize', onWindowResize)
  window.addEventListener('orientationchange', onOrientationChange)
  // 触屏深度思考 sheet 的「点外部关闭」。用 pointerdown 而非 click：touch 设备上
  // click 在 touchend 之后才派发，面板内的 @mousedown.stop.prevent 拦不住它
  document.addEventListener('pointerdown', onDocPointerDown)
  refreshSetupGuide()  // 欢迎区引导卡状态（TTL 去重：AppLayout 挂载时已 force 过，通常直接复用）
  await ready()
  // ready 后会话已加载、消息区进入稳态布局，此时测出的滚动条槽宽才准
  nextTick(syncScrollbarGutter)
})
</script>

<style scoped>
/* --chat-col：消息列与输入卡片共用的居中列宽。两侧必须同宽同基准，否则宽屏下
   两条竖边对不齐（.msg-block 与 .input-box 各写一个 max-width，改一处忘另一处就会漂）。
   --chat-sbw：消息区滚动条实测槽宽（onMounted 起由 syncScrollbarGutter 写入）。
   .messages 是滚动容器、.input 不是，两侧可用宽度天然差一个槽宽 → 居中基准错开半个滚动条。
   两侧内边距都按它补偿，差值归零。 */
.chat2 {
  --chat-col: 860px; --chat-sbw: 0px;
  display: flex; height: 100%; min-width: 0; background: var(--app-panel); position: relative;
}
.chat-col { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.chat-head {
  display: flex; align-items: center; gap: 12px; padding: 10px 20px;
  border-bottom: 1px solid var(--app-border); flex: none; background: var(--app-panel);
}
.chat-title { font-size: 13px; font-weight: 500; max-width: 40%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.head-tip { font-size: 11px; color: var(--app-text3); cursor: pointer; user-select: none; }
.head-tip:hover { color: var(--app-accent); }
/* 会话内查找条：标题栏与消息流之间的一条，随查找开关出现/消失 */
.chat-search {
  display: flex; align-items: center; gap: 6px; flex: none;
  margin: 8px 16px 0; padding: 5px 8px;
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
/* 品牌标 = BrandMark 组件（自带圆角与品牌渐变），留出与标题的间距 */
.welcome-mark { display: block; margin: 0 auto; }
.welcome h2 { margin: 14px 0 6px; font-size: 16px; font-weight: 500; }
.welcome p { color: var(--app-text3); margin: 0 0 18px; }

/* 空态示例问题卡片：2×2 网格，点击即发 */
.welcome-samples { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; max-width: 560px; margin: 0 auto; text-align: left; }
/* 欢迎区引导卡容器：与示例问题栅格同宽，卡片自身在 SetupGuide.vue 定义 */
.welcome-guide { max-width: 560px; margin: 18px auto 0; }
.ws-card {
  display: flex; align-items: flex-start; gap: 9px; text-align: left;
  border: 1px solid var(--app-border); border-radius: var(--app-radius); background: var(--app-panel);
  padding: 11px 12px; cursor: pointer; transition: border-color .15s, box-shadow .15s;
}
.ws-card:hover { border-color: var(--app-accent); box-shadow: 0 2px 10px rgba(0, 0, 0, .06); }
.ws-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
/* 小标题（配置里的「标签｜问题」前段，可自带 emoji）：12px 与窄屏卡片同口径 */
.ws-label { font-size: 12px; color: var(--app-text3); }
.ws-q { font-size: 13px; color: var(--app-text2); }

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

.row { display: flex; flex-wrap: wrap; margin-bottom: 20px; justify-content: center; }
.msg-block {
  position: relative; display: flex; flex-direction: column; min-width: 0;
  /* 上限与 .input-box 同为 --chat-col。原来这里是 min(94%, 860px)：窄屏时 94% 生效，
     消息列比输入卡片窄一截，两侧竖边再次错开。横向留白已由 .messages 的 padding 负责，
     这里只需与输入卡片同宽即可（容器不够宽时 width:100% 自然收敛）。 */
  max-width: var(--chat-col); width: 100%;
}
.msg-block.user { align-items: flex-end; }
.msg-block.ai { align-items: flex-start; }
.bubble { width: 100%; line-height: 1.65; }
.bubble.user { background: var(--app-panel-2); border-radius: 12px; padding: 9px 14px; width: fit-content; max-width: 100%; }
.bubble.user :deep(.md > p) { margin: 0; }
.bubble.ai { background: transparent; padding: 0; }

.msg-imgs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.msg-img { width: 88px; height: 88px; object-fit: cover; border-radius: 8px; border: 1px solid var(--app-border); cursor: zoom-in; }
/* 宽度与 .msg-block / .input-box 同源（--chat-col），否则三处各写 860 极易漂移 */
.pending-imgs { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 auto 8px; max-width: var(--chat-col); }
.pending-img { position: relative; }
.pending-img img { width: 60px; height: 60px; object-fit: cover; border-radius: 8px; border: 1px solid var(--app-border); cursor: zoom-in; }
.pending-del { position: absolute; top: -6px; right: -6px; width: 18px; height: 18px; border-radius: 50%;
  background: rgba(0,0,0,.55); color: #fff; font-size: 12px; line-height: 18px; text-align: center; cursor: pointer; }
.pending-del:hover { background: var(--app-danger); }

.think-panel { margin: 4px 0 8px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); overflow: hidden; }
.think-head { display: flex; align-items: center; gap: 6px; padding: 6px 10px; cursor: pointer; user-select: none; font-size: 12px; color: var(--app-text3); }
.think-head:hover { background: var(--app-panel-2); }
.think-title { font-weight: 500; color: var(--app-text2); }
.think-badge { font-size: 11px; color: var(--app-text3); }
.think-body { padding: 0 10px 8px; border-top: 1px dashed var(--app-border); color: var(--app-text2); font-size: 12px; line-height: 1.7; max-height: 300px; overflow-y: auto; }
.think-body :deep(.md > p) { margin: 4px 0; }

/* ==================== 工具执行卡片 / 折叠组 ====================
 * 正文与工具交错：连续工具合并折叠组（组条窄条化，不把文章剁开）；
 * 卡片标题行亮出关键参数（命令/路径/检索词），展开看完整入参/输出（toolCalls 存 ≤8KB 全文）。 */
.tool-status-list { margin-top: 8px; }
/* 正文段贴工具块：压掉 v-html 里 markdown 段落的上下 margin，保持「句间插标签」的连续读感 */
.tl-text :deep(*:first-child) { margin-top: 0; }
.tl-text :deep(*:last-child) { margin-bottom: 0; }
/* 过程独白段（<process> 标签分流）：灰字弱化 + 左侧细线，与正文区分但不打断交错过程视图 */
.tl-process-block { margin: 2px 0; }
.tl-process-head { display: inline-flex; align-items: center; gap: 5px; border: 0; background: none; padding: 2px 4px; margin: 0 0 2px -4px; border-radius: 4px; cursor: pointer; user-select: none; font-size: 12px; color: var(--app-text3); }
.tl-process-head:hover { color: var(--app-text2); background: var(--app-panel-2); }
.tl-process-title { font-weight: 500; color: var(--app-text2); }
.tl-process { margin: 2px 0; padding: 2px 10px; border-left: 2px solid var(--app-border); color: var(--app-text3); font-size: 12.5px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
/* 过程簇头：把连续的「独白+工具」收成一行（此前一轮过程会散成 6 行折叠头）。
   展开后其内各段由 timelineRows 摊平接着渲染，视觉上是一棵树、DOM 上是同级兄弟。 */
.tl-cluster { margin: 3px 0; }
.tl-cluster-bar {
  display: inline-flex; align-items: center; gap: 6px; width: fit-content; max-width: 100%;
  padding: 3px 9px; font-size: 12px; color: var(--app-text2); text-align: left;
  background: var(--app-bg); border: 1px solid var(--app-border); border-radius: 8px;
  cursor: pointer; user-select: none;
}
.tl-cluster-bar:hover { border-color: var(--app-accent); color: var(--app-text); }
.tl-cluster-bar span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 簇内段左缩进一格：展开后能看出「这些行同属上面那个簇」，层级关系一眼可辨 */
.tl-process-block.inCluster, .tl-group.inCluster { margin-left: 12px; }
.tl-group { margin: 3px 0; }
.tl-group-bar {
  display: inline-flex; align-items: center; gap: 6px; width: fit-content; max-width: 100%;
  padding: 3px 9px; font-size: 12px; color: var(--app-text2); text-align: left;
  background: var(--app-bg); border: 1px solid var(--app-border); border-radius: 8px;
  cursor: pointer; user-select: none;
}
.tl-group-bar:hover { border-color: var(--app-accent); color: var(--app-text); }
/* 折叠三角唯一样式（CaretRightOutlined）：10px 灰、行尾、展开旋转 90°（▶→▼）；
 * 深度思考头/执行过程/工具组条/工具卡片/检索行/子智能体头/引用来源组共用，不再各写一套 */
.tl-caret { flex: none; font-size: 10px; color: var(--app-text3); transition: transform .15s; line-height: 1; }
.tl-caret.open { transform: rotate(90deg); }
.tl-group-body {
  margin: 6px 0 2px 12px; padding-left: 10px; border-left: 2px solid var(--app-border);
  display: flex; flex-direction: column; gap: 6px; max-width: 100%;
}
.tl-group-body.solo { margin: 4px 0 2px; padding-left: 0; border-left: none; }
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
.tl-io-label { font-size: 11px; color: var(--app-text3); margin: 6px 0 2px; }
.tl-io {
  margin: 0; padding: 6px 8px; border-radius: 6px; background: var(--app-bg);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px;
  line-height: 1.6; color: var(--app-text); max-height: 240px; overflow: auto;
  white-space: pre-wrap; word-break: break-all; min-width: 320px; max-width: 100%;
}
.tl-io-err { color: var(--app-danger); }
.tl-io.live { border: 1px solid color-mix(in srgb, var(--app-accent) 35%, transparent); background: var(--app-accent-weak); }
.tool-ic { font-size: 13px; }
.tool-ic-run { color: var(--app-accent); }
.tool-ic-ok { color: var(--app-ok); }
.tool-ic-err { color: var(--app-danger); }
.tool-name { font-weight: 500; color: var(--app-text2); }
.tool-dur { color: var(--app-text3); }
.tool-fail { color: var(--app-danger); }

.artifact-list { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
/* 时间线里的产物卡片（生成时刻穿插，非底部汇总）：上下留白与正文段落对齐 */
.artifact-item {
  display: inline-flex; align-items: center; gap: 6px; max-width: 100%;
  padding: 6px 10px; border: 1px solid var(--app-border); border-radius: 8px;
  font-size: 12px; color: var(--app-text); text-decoration: none; background: var(--app-panel-2);
}
.artifact-item:hover { border-color: var(--app-accent); background: var(--app-accent-weak); }
.artifact-icon { color: var(--app-accent); }
.artifact-name { font-weight: 500; color: var(--app-accent); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.artifact-desc { color: var(--app-text3); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.artifact-dl { color: var(--app-text3); margin-left: auto; }

.degradation-bar {
  margin-top: 8px; padding: 6px 10px; border-radius: 6px;
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); color: var(--app-warn-text);
  font-size: 12px; line-height: 1.6; display: flex; flex-wrap: wrap; gap: 4px 12px;
}
.degradation-item { display: inline-block; }

/* 历史压缩信息条（蓝色，区别于黄色警告）：说明早期对话已并入摘要，完整记录仍在会话中 */
.ctx-compress-bar {
  margin-top: 8px; padding: 6px 10px; border-radius: 6px;
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-border); color: var(--app-accent);
  font-size: 12px; line-height: 1.6; display: flex; align-items: center;
}
/* 手动压缩结果条（/compact）：消息流尾部独立一条，与消息气泡同宽居中收边 */
.compact-notice { margin: 12px 24px 0; gap: 8px; }
.cn-ic { flex: none; }
.cn-text { flex: 1; min-width: 0; }
.compact-notice .app-icon-btn { color: inherit; opacity: .75; }
.compact-notice .app-icon-btn:hover { opacity: 1; }
/* 摘要弹窗 */
.csum-tip { margin-bottom: 10px; font-size: 12px; color: var(--app-text3); line-height: 1.6; }
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
.ctxcap-fill { display: flex; height: 100%; }
.ctxcap-seg { display: block; height: 100%; }
.ctxcap-row { display: flex; align-items: center; gap: 6px; margin-top: 6px; font-size: 12px; }
.ctxcap-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.ctxcap-row-label { color: var(--app-text2); }
.ctxcap-row-val { margin-left: auto; color: var(--app-text3); }
.ctxcap-empty { margin-top: 6px; font-size: 12px; color: var(--app-text3); }
.ctxcap-foot { display: flex; margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--app-border); font-size: 12px; color: var(--app-text2); }
.ctxcap-tip { margin-top: 6px; font-size: 11px; color: var(--app-text3); line-height: 1.5; }

/* 上下文容量圆环（输入框右下、模型选择器左侧）：与右栏模型行共用同一张容量卡，>80% 警告 / >95% 危险 */
.ctx-ring { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; margin-right: 4px; border-radius: 50%; cursor: default; transition: background .15s; }
.ctx-ring:hover { background: var(--app-panel-2); }
.ctx-ring-bg { stroke: var(--app-border); }
.ctx-ring-val { stroke: var(--app-accent); transition: stroke-dasharray .25s; }
.ctx-ring.warn .ctx-ring-val { stroke: var(--app-warn); }
.ctx-ring.danger .ctx-ring-val { stroke: var(--app-danger); }

.retrieval-merged { margin-top: 8px; width: 100%; }
.retrieval-line { display: flex; align-items: center; gap: 2px; font-size: 12px; color: var(--app-text3); user-select: none; cursor: pointer; }
.retrieval-line:hover { color: var(--app-accent); }
.retrieval-detail {
  font-size: 12px; color: var(--app-text2); background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 8px; padding: 8px 10px; margin: 4px 0 2px; line-height: 1.6;
}
.rt-terms { margin-bottom: 6px; }
/* 末行不留尾部外边距：右侧信息卡的小字同样只给 margin-top（.rp-terms），
   卡片内末行若带 margin-bottom，多出的间距会落在 padding 内侧，
   把单行文字顶得偏上（上 13px / 下 19px）、卡片下沿多出等量空白 */
.retrieval-detail > :last-child { margin-bottom: 0; }
.rt-tool-terms { color: var(--app-accent); }
.rt-tool-tag {
  display: inline-block; font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent); margin-right: 6px; vertical-align: 1px;
}
.rt-ref { padding: 3px 0; border-top: 1px dashed var(--app-border); cursor: pointer; }
.rt-ref:hover { color: var(--app-accent); }
.rt-ref-tag { color: var(--app-accent); margin-right: 4px; }
/* 联网来源标记：与库内来源区分开，用户有权知道这条依据是网上的还是库里的 */
.rt-ref-web { margin: 0 6px; padding: 0 5px; border-radius: 4px; background: var(--app-panel-2);
  color: var(--app-text3); font-size: 11px; }
.rt-snip { color: var(--app-text3); margin-top: 2px; }

/* 子智能体编排卡片（仅委派模式；对齐通用智能体平台的子任务卡片：名字+状态+任务描述+要点结果） */
.subagent-panel {
  margin-top: 8px; border: 1px solid var(--app-border); border-radius: var(--app-radius);
  background: var(--app-panel); overflow: hidden; width: 100%;
}
.subagent-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 12px; background: var(--app-accent-weak); font-size: 12px;
  color: var(--app-text2); cursor: pointer; user-select: none;
}
.subagent-head:hover { color: var(--app-accent); }
.subagent-title { font-weight: 500; color: var(--app-text); display: inline-flex; align-items: center; gap: 6px; }
.sa-head-ic { color: var(--app-accent); }
.subagent-sum { font-size: 11px; color: var(--app-text3); display: inline-flex; align-items: center; gap: 5px; }
.sa-route-note {
  font-size: 10px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent); margin-right: 4px;
}
.subagent-list { border-top: 1px solid var(--app-border); }
.subagent-row { padding: 8px 12px; border-top: 1px dashed var(--app-border); font-size: 12px; }
.subagent-row:first-child { border-top: none; }
.sa-row-head { display: flex; align-items: center; gap: 8px; }
.subagent-state { display: inline-flex; align-items: center; width: 16px; flex: none; }
.subagent-state .sa-ok { color: var(--app-ok); }
.subagent-state .sa-err { color: var(--app-danger); }
.subagent-name { font-weight: 500; color: var(--app-text); }
.sa-status-tag {
  font-size: 10px; line-height: 1; padding: 2px 7px; border-radius: 999px; flex: none;
  background: var(--app-panel-2); color: var(--app-text3);
}
.sa-status-tag.st-running { background: var(--app-accent-weak); color: var(--app-accent); }
.sa-status-tag.st-done { background: var(--app-ok-weak); color: var(--app-ok); }
.sa-status-tag.st-failed { background: var(--app-danger-weak); color: var(--app-danger); }
.subagent-hits { margin-left: auto; font-size: 11px; color: var(--app-text3); }
/* 路由挑选理由与分支耗时占比条（运行时可视化增强） */
.sa-reason { margin-top: 3px; font-size: 11px; color: var(--app-text3); }
.sa-bar { margin-top: 5px; height: 3px; border-radius: 2px; background: var(--app-border); overflow: hidden; }
.sa-bar-fill { height: 100%; border-radius: 2px; transition: width .4s ease; }
.sa-bar-fill.st-done { background: var(--app-ok); }
.sa-bar-fill.st-running { background: var(--app-accent); }
.sa-bar-fill.st-failed { background: var(--app-danger); }
.sa-desc { margin-top: 4px; font-size: 11px; color: var(--app-text3); line-height: 1.5; }
.sa-digest {
  margin-top: 5px; font-size: 11.5px; color: var(--app-text2); line-height: 1.6;
  padding: 6px 9px; background: var(--app-panel-2); border-radius: 5px; white-space: pre-wrap;
}
/* 按需委派判定"无需咨询任何助手"时的说明行 */
.subagent-skip {
  margin-top: 8px; font-size: 11.5px; color: var(--app-text3); line-height: 1.5;
  padding: 6px 10px; background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: var(--app-radius);
}

.related { margin-top: 10px; display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.related-label { font-size: 12px; color: var(--app-text3); }
.related-tag {
  font-size: 11px; color: var(--app-ok); background: var(--app-ok-weak); border-radius: 999px;
  padding: 3px 10px; cursor: pointer;
}
.related-tag:hover { background: var(--app-ok-weak-hover); }
.busy-hint { margin-top: 6px; font-size: 13px; color: var(--app-accent); display: flex; align-items: center; gap: 6px; }
/* 自动重试属异常状态：沿用原重试条的醒目底色，与正常阶段文案区分 */
.busy-hint.warn {
  margin-top: 8px; color: var(--app-warn-text); background: var(--app-warn-weak); border: 1px solid var(--app-warn-border);
  border-radius: 6px; padding: 4px 10px; width: fit-content;
}
.dispatch-chip {
  margin-top: 6px; display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap;
  font-size: 12px; color: var(--app-text2);
  border: 1px solid var(--app-border); border-radius: 999px; padding: 3px 10px;
  background: color-mix(in srgb, var(--app-accent) 6%, transparent);
}
.dispatch-ic { color: var(--app-accent); font-size: 12px; }
.dispatch-ava { flex: none; }  /* 委派徽标内的智能体头像，同 .agent-tag-ava */
.dispatch-fallback { color: var(--app-text3); }
.dispatch-desc { color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 420px; }
/* @ 智能体委派徽标（§4）：与派遣徽标同族，但这是用户指令——常显，配色加重以示区别 */
.delegated-chip { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); font-weight: 500; }

.fb-row { margin-top: 8px; display: flex; align-items: center; gap: 2px; }
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
.fb-row :deep(.fb-active) { color: var(--app-accent); }
/* 本轮被叫停且没产出回答：跟在问题下方一行安静说明（与时间戳同一弱化口径），
   不造空回答气泡——「这一问没有回答」是那一问的事实，不是一条回答的内容 */
.msg-stopped {
  display: flex; align-items: center; gap: 4px; justify-content: flex-end;
  margin-top: 2px; font-size: 11px; color: var(--app-text3); user-select: none;
}
.msg-edit-row {
  position: absolute; top: calc(100% + 2px); left: 0; right: 0; height: 24px; z-index: 1;
  display: flex; align-items: center; justify-content: flex-end; gap: 6px;
  opacity: 0; transition: opacity .15s;
}
.msg-block:hover .msg-edit-row { opacity: 1; }
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
.msg-inline-edit-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.msg-inline-edit-hint {
  margin-right: auto; display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; color: var(--app-text3); min-width: 0;
}
.msg-inline-edit-actions .app-btn { border-radius: 999px; padding: 5px 14px; }
.msg-inline-edit-actions .app-btn.ghost { background: var(--app-panel); }
.msg-time-inline { font-size: 11px; color: var(--app-text3); margin-left: 8px; white-space: nowrap; user-select: none; }
.msg-tokens { font-size: 11px; color: var(--app-text3); white-space: nowrap; cursor: default; }
.jump-latest {
  position: sticky; bottom: 12px; z-index: 5; width: 28px; height: 28px; line-height: 26px; text-align: center;
  margin: 0 auto 4px; padding: 0; background: var(--app-panel); color: var(--app-text); font-size: 14px;
  border: 1px solid var(--app-border); border-radius: 999px; cursor: pointer; user-select: none;
  box-shadow: var(--app-shadow-sm); transition: border-color .15s, box-shadow .15s;
}
.jump-latest:hover { border-color: var(--app-border-strong); box-shadow: var(--app-shadow); }

.input {
  position: relative; flex: none;
  /* 右内边距额外补一个滚动条槽宽：.messages 常驻槽（scrollbar-gutter: stable）比 .input 少一截，
     不补则消息列的居中基准比输入卡片左移半个槽 → 两侧竖边错开，肉眼可见。
     槽宽由 syncScrollbarGutter 实测写入 --chat-sbw（macOS 覆盖式滚动条为 0，Windows/Linux 为 8~15px）。 */
  padding: 10px calc(32px + var(--chat-sbw)) 14px 32px;
}
.drop-overlay {
  position: absolute; inset: 6px calc(32px + var(--chat-sbw)) 6px 32px; z-index: 6; pointer-events: none;
  background: rgba(46,107,230,.06); border: 2px dashed var(--app-accent); border-radius: 14px;
  display: flex; align-items: center; justify-content: center;
  color: var(--app-accent); font-size: 14px; font-weight: 500;
}
/* 待插话条目（输入框上方）：一条一行，右侧「插话 / 编辑 / 删除」。
   刻意压在输入框之上而不是消息流里——它是"这轮还没说出去的话"，不是会话内容 */
.steer-list {
  display: flex; flex-direction: column; gap: 4px; margin: 0 0 8px;
}
.steer-item {
  display: flex; align-items: center; gap: 8px; min-width: 0;
  padding: 4px 6px 4px 10px; border-radius: 10px;
  background: var(--app-accent-weak); border: 1px solid var(--app-border); font-size: 13px;
}
.steer-item.done { background: transparent; }
.steer-ic { color: var(--app-text3); flex: none; }
.steer-txt { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.steer-extra { flex: none; font-size: 11px; color: var(--app-text3); }
.steer-state { flex: none; font-size: 11px; color: var(--app-text3); }
.steer-act {
  flex: none; display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel);
  padding: 2px 8px; font-size: 12px; color: var(--app-text); cursor: pointer;
}
.steer-act:hover { background: var(--app-accent-weak); }
.steer-act.primary { border-color: var(--app-accent); color: var(--app-accent); }
.input-box {
  position: relative; max-width: var(--chat-col); margin: 0 auto;
  border: 1px solid var(--app-border); border-radius: 16px; background: var(--app-panel);
  padding: 10px 12px 8px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 8px 20px -10px rgba(16, 24, 40, .10);
  transition: border-color .2s, box-shadow .2s;
}
.input-box:focus-within {
  border-color: var(--app-accent);
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 10px 26px -10px rgba(46, 107, 230, .30);
}
/* 已选引用标签：输入卡片正上方一行，与卡片同宽居中——缺 max-width 会贴窗口左缘，宽屏下像「跑出输入区」；
   × 可整体移除（@ 引用与技能 chip 共用） */
.at-chips { display: flex; flex-wrap: wrap; gap: 6px; padding: 2px 4px 6px; max-width: var(--chat-col); margin: 0 auto; }
.at-chip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 260px;
  padding: 2px 6px; border-radius: 6px; font-size: 12px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.at-chip-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.at-chip-del { flex: none; cursor: pointer; font-size: 13px; line-height: 1; opacity: .65; padding: 0 1px; }
.at-chip-del:hover { opacity: 1; color: var(--app-danger); }
/* @ 引用 chip：知识库/文档/智能体用图标与底色区隔（技能 chip 沿用 .skill-chip 原样） */
.at-chips-note { font-size: 11px; color: var(--app-text3); align-self: center; margin-left: 2px; }
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
.mention-tabs { display: flex; gap: 4px; padding: 8px 10px 2px; }
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
/* / 命令面板：右侧类型徽标（模板=可再编辑 / 操作=立即执行），与勾选态视觉区分 */
.slash-kind {
  flex: none; font-size: 11px; padding: 1px 8px; border-radius: 999px;
  background: var(--app-panel-2); color: var(--app-text3);
}
.slash-kind.k-tpl { background: var(--app-accent-weak); color: var(--app-accent); }
.slash-kind.k-act { background: var(--app-warn-weak); color: var(--app-warn-text); }
.input-area { resize: none; padding: 6px 4px; font-size: 14px; line-height: 1.6; border: none; background: transparent; }
.input-area:focus { border: none; box-shadow: none; }
.input-toolbar { display: flex; align-items: center; gap: 6px; margin-top: 6px; }
.toolbar-left { display: flex; align-items: center; gap: 2px; min-width: 0; }
.toolbar-right { margin-left: auto; display: flex; align-items: center; gap: 2px; }
/* ==================== 深度思考设置（悬浮下拉模型行弹出面板卡，参考模型详情面板形态） ====================
   面板 Teleport 到 body 以 fixed 定位：位置按悬浮的模型行实时给定（thinkPanelPos），
   z-index 1060 高于 antd 下拉（1050），叠在下拉上方且不被裁剪 */
.think-float {
  position: fixed; z-index: 1060; padding: 14px 16px;
  max-height: calc(100vh - 16px); overflow-y: auto;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 14px;
  box-shadow: 0 10px 32px -8px rgba(16, 24, 40, .18);
}
/* 面板头部的模型名胶囊：明确这份设置属于哪个模型（按模型分别记忆） */
.thinkp-model {
  margin-top: 6px; width: fit-content; max-width: 100%;
  padding: 2px 10px; border-radius: 999px; background: var(--app-panel-2);
  font-size: 12px; color: var(--app-text2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 面板卡：标题/描述 + 「思考强度」标签值行（点开展开档位列表）+ 底部提示 */
.thinkp { width: 264px; }
.thinkp-title { font-size: 14px; font-weight: 600; color: var(--app-text); }
.thinkp-desc { margin-top: 4px; font-size: 12px; line-height: 1.6; color: var(--app-text3); }
.thinkp-row-wrap { margin-top: 12px; border-top: 1px solid var(--app-border); }
.thinkp-row {
  display: flex; align-items: center; gap: 8px; width: 100%; padding: 10px 0;
  border: none; background: transparent; font-size: 13px; text-align: left;
}
.thinkp-row.expandable { cursor: pointer; }
.thinkp-row-label { color: var(--app-text); }
.thinkp-row-val { margin-left: auto; color: var(--app-text3); }
.thinkp-caret { font-size: 11px; color: var(--app-text3); transition: transform .2s; }
.thinkp-caret.open { transform: rotate(180deg); }
.thinkp-levels { display: flex; flex-direction: column; gap: 2px; padding-bottom: 10px; }
.thinkp-opt {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 7px 10px; border: none; border-radius: 8px; background: transparent;
  font-size: 13px; color: var(--app-text); text-align: left; cursor: pointer;
}
.thinkp-opt:hover { background: var(--app-panel-2); }
.thinkp-opt.active { color: var(--app-accent); font-weight: 500; }
.thinkp-opt-check { font-size: 12px; }
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
/* 模型切换记录（— ⇄ 模型已切换 A → B —）：居中分隔条独占一行，弱化呈现；flex-basis:100%
   配合 .row 的 flex-wrap 把气泡挤到下一行。
   左右内缩到正文列宽（.msg-block 的 min(94%,860px)）：分隔条盒子仍占满一行，
   靠 padding 收窄可见部分，否则分隔线横贯整个消息区、比正文宽出一倍 */
.model-switch-divider {
  flex-basis: 100%; display: flex; align-items: center; gap: 10px;
  padding: 0 calc((100% - min(94%, 860px)) / 2);
  margin: 2px 0 10px; color: var(--app-text3);
}
.model-switch-divider .msd-line { flex: 1; border-top: 1px solid var(--app-border); }
.model-switch-divider .msd-text { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; white-space: nowrap; }
.model-switch-divider .msd-ic { font-size: 12px; opacity: .75; }

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

/* 输入框「+」面板：页签行 + 面板内筛选输入（外壳与行式交互复用 .mention-panel / .mention-item） */
.add-head { gap: 6px; }
.add-tab-n {
  font-size: 10px; line-height: 15px; padding: 0 5px; border-radius: 999px;
  background: var(--app-accent); color: #fff;
}
.add-close { margin-left: auto; }
.add-filter-row { padding: 8px 10px 0; }
.add-filter {
  width: 100%; box-sizing: border-box; padding: 6px 10px;
  border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel-2); color: var(--app-text); font-size: 13px; outline: none;
}
.add-filter:focus { border-color: var(--app-accent-border, var(--app-accent)); }
.skill-ava { font-size: 12px; font-weight: 600; }

/* 待发送附件条 + 技能选中标签 */
.pending-files { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 auto 8px; max-width: var(--chat-col); }
.pending-file.err { background: var(--app-danger-weak); border-color: var(--app-danger-border); color: var(--app-danger-text); }
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

/* 气泡内附件标签（历史回显） */
.msg-files { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.msg-file {
  display: inline-flex; align-items: center; gap: 5px; max-width: 260px;
  padding: 4px 8px; border-radius: 8px; font-size: 12px;
  background: rgba(255, 255, 255, .55); border: 1px solid var(--app-border);
}
.msg-file-ic { flex: none; color: var(--app-accent); font-size: 13px; }
.msg-file-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.msg-file-size { flex: none; font-size: 11px; opacity: .65; }

/* 长文本粘贴卡片（待发送 / 编辑卡 / 气泡内共用）：图标块 + 标题与体积两行，点开看全文。
   放在 .pending-file / .msg-file 之后，靠同权重后置覆盖它们的单行 chip 尺寸；背景仍随基础类走主题 */
.paste-card {
  display: flex; align-items: center; gap: 10px; max-width: 320px; padding: 8px 10px;
  border-radius: 10px; cursor: pointer;
}
.paste-card-ic {
  flex: none; width: 32px; height: 32px; border-radius: 8px; font-size: 16px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel); border: 1px solid var(--app-border); color: var(--app-accent);
}
.paste-card-txt { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.paste-card-name { font-size: 13px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.paste-card-sub { font-size: 11px; color: var(--app-text3); }
.paste-view-bar { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.paste-view-meta { font-size: 12px; color: var(--app-text3); }

.send-btn {
  width: 30px; height: 30px; border-radius: 50%; border: none;
  background: var(--app-accent); color: #fff; font-size: 15px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; transition: background .2s;
}
.send-btn:hover:not(:disabled) { background: var(--app-accent-hover); }
/* 禁用态：浅蓝底（--app-accent-disabled）+ 白图标，对比度太低，图标几乎看不见，
   看着像按钮坏了。改灰底灰图标 —— 禁用就该"退后"而不是"变淡"（分享页同步）。 */
.send-btn:disabled { background: var(--app-border); color: var(--app-text3); cursor: not-allowed; }
/* 停止态：弱化危险色（浅红底+危险色内容，与 app-pill.err/右栏停止按钮同一视觉语言），不做实心红圆 */
.send-btn.stop {
  background: var(--app-danger-weak); color: var(--app-danger-text);
  border: 1px solid var(--app-danger-border);
}
.send-btn.stop:hover:not(:disabled) { background: var(--app-danger-border); }
/* 上膛态：按钮内嵌 esc 字样（生成中第一次 Esc 后出现，再按一次才停止），无键帽描边 */
.send-btn .esc-cap { font-size: 10px; font-weight: 600; line-height: 1; letter-spacing: .5px; }

/* 右侧状态栏 */
.right-panel {
  width: 230px; flex: none; border-left: 1px solid var(--app-border); background: var(--app-panel-2);
  /* 底部预留：全局帮助 FAB 悬浮在视口右下角，让卡片不被压住 */
  padding: 12px 10px 56px; display: flex; flex-direction: column; gap: 10px; overflow-y: auto;
}
.rp-card { background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px 12px; }
.rp-label { font-size: 11px; color: var(--app-text3); margin-bottom: 4px; }
.rp-strong { font-size: 14px; font-weight: 500; word-break: break-all; }
.rp-meta { font-size: 11px; color: var(--app-text3); margin-top: 4px; }
.rp-row { display: flex; justify-content: space-between; font-size: 12px; color: var(--app-text2); }
.rp-dim { font-size: 11px; color: var(--app-text3); }
.rp-terms { font-size: 11px; color: var(--app-text3); margin-top: 5px; line-height: 1.6; word-break: break-all; }
.rp-divider { border-top: 1px dashed var(--app-border); margin: 8px 0 6px; }
.rp-tool-label { font-size: 11px; color: var(--app-accent); font-weight: 500; }
.rp-tool-q { color: var(--app-text2); margin-top: 3px; }
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
@media (prefers-reduced-motion: reduce) { .rp-group-body, .tl-caret { transition: none; } }
.rp-src:hover .rp-src-name { color: var(--app-accent); }
.rp-src-ic { color: var(--app-accent); font-size: 12px; flex: none; }
.rp-src-name { font-size: 12px; color: var(--app-text2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 右栏卡片头部（沙盒等折叠卡共用） */
.rp-exec-head { display: flex; align-items: center; justify-content: space-between; cursor: pointer; }
.rp-exec-sum { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; color: var(--app-text3); }
.rp-exec-dur { flex: none; font-size: 11px; color: var(--app-text3); }

/* 右栏产物卡 */
.rp-art { display: flex; align-items: center; gap: 6px; padding: 4px 0; text-decoration: none; }
.rp-art:hover .rp-art-name { color: var(--app-accent); }
.rp-art-ic { color: var(--app-accent); font-size: 12px; flex: none; }
.rp-art-name { flex: 1; min-width: 0; font-size: 12px; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rp-art-dl { flex: none; font-size: 11px; color: var(--app-text3); }
.rp-art-all { margin-top: 4px; font-size: 11px; color: var(--app-accent); cursor: pointer; }
.rp-art-all:hover { text-decoration: underline; }

/* 右栏计划卡：本会话出过的计划列表（问题为标签 + 状态角标；点击定位到那一轮并闪一下） */
.rp-plan-row {
  display: flex; align-items: center; gap: 6px; width: 100%; text-align: left;
  padding: 4px 6px; margin: 0 -6px; border: none; background: none; border-radius: 6px;
  font-size: 12px; color: var(--app-text2); cursor: pointer;
}
.rp-plan-row:hover { background: var(--app-panel-2); }
.rp-plan-row:hover .rp-plan-t { color: var(--app-accent); }
.rp-plan-ic { flex: none; font-size: 12px; color: var(--app-text3); }
/* 右栏任务清单卡：进度比条目先说话（「3/7」一眼看出还剩几步），条目状态用方框符号而不是颜色，
   暗色主题下颜色对比不稳 */
.rp-todo-head { display: flex; align-items: center; gap: 8px; width: 100%; background: none; border: none; cursor: pointer; }
.rp-todo-progress { margin-left: auto; font-variant-numeric: tabular-nums; font-size: 11px; color: var(--app-text3); }
.rp-todo-list { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; }
.rp-todo-item { display: flex; align-items: flex-start; gap: 6px; font-size: 12px; line-height: 1.5; }
.rp-todo-box { flex: none; width: 14px; height: 14px; margin-top: 2px; border: 1px solid var(--app-border); border-radius: 4px; font-size: 10px; line-height: 12px; text-align: center; color: var(--app-panel); }
.rp-todo-item.done .rp-todo-box { background: var(--app-ok); border-color: var(--app-ok); }
.rp-todo-item.done .rp-todo-txt { color: var(--app-text3); text-decoration: line-through; }
.rp-todo-item.doing .rp-todo-box { border-color: var(--app-accent); color: var(--app-accent); }
.rp-todo-item.doing .rp-todo-txt { color: var(--app-accent); }
.rp-todo-txt { min-width: 0; word-break: break-word; }
.rp-plan-t { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rp-plan-st { flex: none; font-size: 11px; color: var(--app-text3); }
.rp-plan-st.is-pending { color: var(--app-warn-text); }
.rp-plan-st.is-approved { color: var(--app-ok); }
/* 定位闪烁：跳过去的那一轮先亮一下（与引用角标 ref-flash 同一手法） */
.row.plan-jump { animation: planJumpFlash 1.5s ease; }
@keyframes planJumpFlash { 0% { background: var(--app-accent-weak); } 100% { background: transparent; } }

/* 右栏统计口径切换 + 上下文占用条 */
/* 口径切换器现在贴在「检索与用量」卡的标题行右侧（不再独占栏首） */
.rp-card-head { display: flex; align-items: center; gap: 6px; }
.rp-card-head .rp-label-h { margin-bottom: 0; }
.rp-card-head .rp-scope { margin-left: auto; flex: none; }
.rp-scope { display: inline-flex; border: 1px solid var(--app-border); border-radius: 7px; overflow: hidden; background: var(--app-panel); }
.rp-scope span { padding: 3px 10px; font-size: 11px; color: var(--app-text3); cursor: pointer; user-select: none; }
.rp-scope span.on { background: var(--app-accent-weak); color: var(--app-accent); font-weight: 500; }
/* 计划卡头：整行可点开合，摘要徽标在中间，箭头在尾 */
.rp-plan-head { width: 100%; background: none; border: 0; padding: 0; text-align: left; cursor: pointer; font: inherit; }
.rp-plan-head:hover .rp-label-h { color: var(--app-text2); }
.rp-plan-badge {
  flex: 1; min-width: 0; font-size: 11px; color: var(--app-text3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: right;
}
.rp-plan-badge.is-pending { color: var(--app-warn-text); font-weight: 500; }
.rp-ctx-bar { height: 6px; border-radius: 3px; background: var(--app-accent-weak); overflow: hidden; margin-top: 6px; }
.rp-ctx-fill { display: block; height: 100%; background: var(--app-accent); transition: width .3s; }
.rp-ctx-fill.warn { background: #d9861f; }
.rp-ctx-fill.danger { background: var(--app-danger); }
@media (prefers-reduced-motion: reduce) { .rp-ctx-fill { transition: none; } }

/* 引用相关度 + 角标联动（右栏 ↔ 正文） */
.rp-src-ref { flex: none; font-size: 11px; color: var(--app-text3); }
.rp-src-mine {
  flex: none; font-size: 10px; line-height: 1.5; padding: 0 4px; border-radius: 4px;
  background: var(--app-warn-weak); color: var(--app-warn-text);
}
.rp-src-score { flex: none; font-size: 11px; color: var(--app-accent); }
.rp-src-sub .rp-src-name { flex: 1; min-width: 0; }
.rp-src-sub.hl { background: var(--app-accent-weak); border-radius: 4px; }

/* 右栏运行控制（重试本轮） */
.rp-ctrl { display: flex; }
.rp-ctrl-btn {
  flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 5px;
  padding: 6px 0; border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel); color: var(--app-text2); font-size: 12px; cursor: pointer;
}
.rp-ctrl-btn:hover { color: var(--app-accent); border-color: var(--app-accent); }

/* 检索调试面板 */
.dbg-item { padding: 6px 8px; margin-bottom: 6px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-panel-2); }
.dbg-terms { padding: 8px 10px; margin-bottom: 10px; border: 1px solid var(--app-info-border); border-radius: 6px; background: var(--app-info-weak); }
.dbg-terms-label { font-size: 12px; color: var(--app-text3); margin-right: 6px; }
.dbg-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.dbg-title { font-weight: 500; font-size: 13px; }
.dbg-snippet { margin-top: 3px; font-size: 12px; color: var(--app-text3); word-break: break-all; }

/* 工具执行审批（人在回路） */
.approval-card { margin-top: 8px; border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 8px; padding: 10px 12px; max-width: 640px; }
.approval-title { font-size: 13px; font-weight: 600; color: var(--app-warn-text); display: flex; align-items: center; gap: 6px; }
.approval-args { margin: 8px 0 0; background: var(--app-panel); border: 1px solid var(--app-warn-border); border-radius: 6px; padding: 8px; font-size: 12px; font-family: "SF Mono", Menlo, monospace; white-space: pre-wrap; word-break: break-all; max-height: 140px; overflow-y: auto; }
.approval-actions { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.approval-hint { font-size: 12px; color: var(--app-text3); }

/* 工具审批恢复横幅（通知 → 会话，重建刷新丢失的 SSE 审批卡） */
.approval-recovery { margin: 10px 0 0; border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 8px; padding: 10px 12px; max-width: 720px; }

/* ==================== 计划模式：执行计划卡 ==================== */
/* 生命周期能从卡片自身状态读出来：drafting 生成中（蓝）→ pending 待确认（琥珀，同审批卡语义）
   → approved 已批准（绿）/ rejected 未批准（灰弱化收尾） */
.plan-card { margin-top: 8px; border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 8px; padding: 10px 12px; max-width: 640px; }
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
/* 「按意图自动开启」角标：这条计划不是用户手动开出来的 */
.plan-auto-tag { flex: none; font-size: 11px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent); font-weight: 500; }
.plan-body { margin-top: 8px; font-size: 13px; }
/* 长计划紧凑预览（参考 zcode 形态）：限高 + 底部渐隐，居中「查看完整计划」展开/收起；
   短计划不加限高、不出按钮，与改动前逐字一致 */
.plan-wrap { position: relative; }
.plan-wrap.is-clamped .plan-body {
  max-height: 118px; overflow: hidden;
  -webkit-mask-image: linear-gradient(to bottom, #000 46%, transparent 96%);
  mask-image: linear-gradient(to bottom, #000 46%, transparent 96%);
}
.plan-wrap.is-clamped .plan-more { position: absolute; left: 50%; bottom: 2px; transform: translateX(-50%); }
.plan-more { display: inline-flex; align-items: center; gap: 4px; margin: 8px auto 0; padding: 3px 12px;
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
.plan-chip { border-color: var(--app-warn-border); background: var(--app-warn-weak); }
/* 「+」面板：计划模式开关行——持久模式用开关表达（行可点、开关可拨），
   开启态只变开关自身，不再整行变色/头像实心（那是「本轮勾选」的语汇）。
   开关外包一层 span 挡冒泡：a-switch 的 click 是组件事件（payload 非 DOM 事件），
   直接挂 @click.stop 会抛 stopPropagation is not a function，也让行点击与开关点击互相抵消 */
.plan-switch-wrap { flex: none; display: inline-flex; }

.ar-title { font-size: 13px; font-weight: 600; color: var(--app-warn-text); display: flex; align-items: center; gap: 6px; }
.ar-args { margin: 8px 0 0; background: var(--app-panel); border: 1px solid var(--app-warn-border); border-radius: 6px; padding: 8px; font-size: 12px; font-family: "SF Mono", Menlo, monospace; white-space: pre-wrap; word-break: break-all; max-height: 160px; overflow-y: auto; }
.ar-foot { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.ar-status { font-size: 12px; margin-right: auto; }
.ar-status.ar-pending { color: var(--app-warn-text); }
.ar-status.ar-ok { color: var(--app-ok); }

/* askUser 问答记录由共用组件 AskRecordCard 承载（自带折叠头与样式） */

/* ==================== 智能体提问面板（替换聊天输入框） ====================
   挂起提问时整块顶替 composer：编号选项（关键词加粗 + 说明弱化、首项带「推荐」）、
   自定义输入作末项、底部键盘提示 + 忽略/提交。键盘高亮用 .sel（Tab/↑↓ 移动）。 */
.askp-box { padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
.askp-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.askp-tag { flex: none; font-size: 12px; line-height: 1; padding: 5px 9px; border-radius: 999px; background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text2); }
.askp-q { font-size: 13.5px; font-weight: 600; color: var(--app-text); }
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
   待批准期间打字框整块顶替，与提问面板（askp-*）同形同节奏：编号选项（1. 确认 2. 继续对话），
   自定义项内嵌光文本域（写对计划的修改要求）；底部提示 + 取消/继续对话。
   继续对话=把意见作为一条用户消息发出（同一会话继续），本版计划作废，新一轮按意见重做计划 */
.planp-box { padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
.planp-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.planp-tag { flex: none; font-size: 12px; line-height: 1; padding: 5px 9px; border-radius: 999px; background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text2); }
.planp-state { font-size: 12px; color: var(--app-text2); }
.planp-state.warn { color: var(--app-warn); }
.planp-timer { flex: none; display: inline-flex; align-items: center; gap: 4px; margin-left: auto; font-size: 12px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.planp-timer.warn { color: var(--app-warn); }
.planp-opts { display: flex; flex-direction: column; gap: 2px; }
.planp-opt { display: flex; align-items: flex-start; gap: 8px; width: 100%; text-align: left; background: none; border: none; border-radius: 6px; padding: 7px 8px; font-size: 13px; line-height: 1.55; color: var(--app-text); cursor: pointer; }
.planp-opt:hover { background: var(--app-panel-2); }
.planp-opt:disabled { opacity: 0.55; cursor: default; }
.planp-no { flex: none; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.planp-kw { font-weight: 600; }
.planp-rest { color: var(--app-text2); margin-left: 8px; flex: 1; min-width: 0; }
/* 自定义项整行不可点（只有输入框可编辑）：去掉悬停反馈，避免读成「点这行就选中」。
   输入框留空，提示语全在 placeholder（与提问卡的自定义输入行同形：编号 + 光输入框） */
.planp-custom-row { cursor: default; }
.planp-custom-row:hover { background: none; }
.planp-custom { flex: 1; min-width: 0; }
/* 行内光文本域：与提问卡自定义输入行同形——文字（placeholder）与上行关键词同列、首行同基线；
   去边框/内边距/最小高度（边框底色由 :bordered="false" 承担），高度交给 autoSize */
.planp-custom .ant-input {
  font-size: 13px; line-height: 1.55;
  display: block; padding: 0; min-height: 0; resize: none;
}
.planp-foot { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.planp-hint { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text3); }
.planp-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }
/* 面板内的「停止本轮」（提问/计划两面板共用）：与发送位停止键同一危险弱化口径，但做成静默描边——
   面板的主行动是「回答 / 批准」，停止是退出路径，不该抢视觉焦点 */
.p-stop { color: var(--app-danger-text); border-color: var(--app-danger-border); }
.p-stop:hover:not(:disabled) { background: var(--app-danger-weak); border-color: var(--app-danger-border); }
.ar-status.ar-err { color: var(--app-danger); }

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
     让位量在 .input 的右内边距上叠加（含滚动条槽），不能再单独覆盖 padding-right，
     否则会把上面为对齐做的补偿冲掉。 */
  .input { padding-right: calc(56px + var(--chat-sbw)); }
  /* 拖拽高亮框跟随卡片右缘（与 .input 同侧内边距） */
  .drop-overlay { right: calc(56px + var(--chat-sbw)); }
}
@media (max-width: 768px) {
  /* 内边距从 32px 收到 10px，与 .chat-head 的横向节奏一致 */
  .messages { padding: 10px 10px 6px; overscroll-behavior-y: contain; }
  /* padding 用简写：同时重置 1200 块的 padding-right:56px（窄屏 FAB 已隐藏）。
     简写会把滚动条槽一起抹掉，所以再用长写补回来 —— 否则窄屏又不对齐。 */
  .input { padding: 8px calc(10px + var(--chat-sbw)) calc(10px + var(--sab, 0px)) 10px; }
  .drop-overlay { right: calc(10px + var(--chat-sbw)); left: 10px; }
  .chat-head { padding: 6px 10px; padding-top: calc(6px + var(--sat, 0px)); gap: 6px; }
  .chat-title { max-width: 42%; }
  .head-tip { display: none; }
  .welcome { padding: 40px 12px 24px; }
  .welcome-samples { grid-template-columns: 1fr; max-width: 100%; }
  .bubble { max-width: 100%; }
  /* 触摸热区：antd 的 28~32px 控件在手指上偏小 */
  .send-btn { width: 34px; height: 34px; }
  .input-toolbar .ant-btn, .input-toolbar .app-icon-btn { min-width: 34px; min-height: 34px; }
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
     症状（真机 375px）：模型名 deepseek-flash 把「思考」按钮压成竖排两字。
     成因：.input-toolbar 是 flex 但**没设 nowrap**，子项宽度总和超过容器时浏览器换行，
     而按钮内的文字被压窄后按字符断行。
     修法：给 .input-toolbar 加 as-mobile 类并设 flex-wrap:nowrap（见 768 块）。
     这里只补 PC 态没有的收窄项：智能体胶囊只留头像、模型 compact。 */
  .input-toolbar.as-mobile { flex-wrap: nowrap; gap: 2px; }
  .input-toolbar.as-mobile .toolbar-left,
  .input-toolbar.as-mobile .toolbar-right { min-width: 0; }
  .input-toolbar.as-mobile .toolbar-right { margin-left: auto; }
  /* 智能体胶囊窄屏只留头像：固定 34×34 圆形，触控热区达标且不占横向空间 */
  .agent-pill.as-icon { width: 34px; height: 34px; padding: 0; justify-content: center; border-radius: 50%; }
  .agent-pill.as-icon .agent-pill-lock { display: none; }  /* 锁标记在窄屏无空间表达，靠下拉里的文案 */
  /* 思考按钮不折行：nowrap 是必须的，否则「思考」两字会被压成上下两行 */
  .think-entry-txt { white-space: nowrap; }
  /* 发送键在窄屏略缩，给工具条腾出宽度（仍 34px，触控达标） */
  .input-toolbar.as-mobile .send-btn { flex: none; }
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
   此前这里写了 @media (hover:hover){ display:none }，与 v-if 判据不同源：
   在 Surface 之类「(pointer:coarse) 与 (hover:hover) 同时成立」的设备上，
   按钮会被渲染出来又被这条 CSS 藏掉，而 hover 通路已被 onModelOptionHover
   的 isCoarse 早退堵死 —— 深度思考入口彻底消失。判据必须只有 isCoarse 一个。 */
/* 无障碍：系统开启「减少动态效果」时不播 sheet 上滑 */
@media (prefers-reduced-motion: reduce) {
  .right-panel.as-sheet, .mention-panel, .think-float, .ctxcap-float { animation: none; }
}
</style>
