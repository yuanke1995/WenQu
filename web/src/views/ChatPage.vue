<template>
  <div class="chat2">
    <!-- 中间：标题栏 + 消息流 + 输入区 -->
    <div class="chat-col">
      <div class="chat-head">
        <span class="chat-title">{{ currentSessionTitle }}</span>
        <span class="head-tip" title="查看免责声明" @click="disclaimerVisible = true">AI 回答可能有误，重要信息请核实</span>
        <!-- 头部动作区：右对齐一组，图标按钮无框安静（此前每个按钮各自 margin-left:auto 散落标题栏中间，视觉突兀） -->
        <div class="head-actions">
          <button class="app-icon-btn" title="分享这段对话（只读链接）" @click="openShare">
            <share-alt-outlined />
          </button>
          <button class="app-icon-btn" title="在本会话中查找（Ctrl/⌘ + F）" @click="openSearch">
            <search-outlined />
          </button>
          <button class="head-quiet-btn" @click="togglePanel">{{ panelOpen ? '隐藏状态' : '状态' }}</button>
        </div>
      </div>

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

      <div class="messages" ref="box" @click="openPreview" @mouseover="refHover" @mouseleave="scheduleCloseRefTip" @scroll="onMessagesScroll">
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
            <h2>有什么可以帮你？</h2>
            <p>智能体与知识库问答，支持图片提问与深度思考</p>
            <!-- 示例问题：点击即发（对齐主流产品空态引导；通用四类：检索/总结/写作/分析） -->
            <div class="welcome-samples">
              <button v-for="q in SAMPLE_QUESTIONS" :key="q.text" class="ws-card" type="button" @click="ask(q.text)">
                <span class="ws-ic">{{ q.icon }}</span>
                <span class="ws-text">
                  <span class="ws-label">{{ q.label }}</span>
                  <span class="ws-q">{{ q.text }}</span>
                </span>
              </button>
            </div>
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
          <div class="msg-block" :class="m.role">
            <div class="bubble" :class="m.role">
              <div v-if="m.role === 'user' && m.images && m.images.length" class="msg-imgs">
                <img v-for="(u, ui) in m.images" :key="ui" :src="resolveImg(u)" class="msg-img"
                     :alt="'上传图片' + (ui + 1)" @click="openPreviewFromMsg(m, ui)" @error="onImgError" />
              </div>
              <div v-if="m.role === 'user' && m.attachments && m.attachments.length" class="msg-files">
                <span v-for="(a, fi) in m.attachments" :key="fi" class="msg-file"
                      :title="(a.mime || '附件') + (a.size ? ' · ' + fmtSize(a.size) : '')">
                  <paper-clip-outlined class="msg-file-ic" />
                  <span class="msg-file-name">{{ a.name }}</span>
                  <span v-if="a.size" class="msg-file-size">{{ fmtSize(a.size) }}</span>
                </span>
              </div>
              <!-- @ 引用（本轮显式指定的知识库/文档）：只对当轮生效，随内存消息展示 -->
              <div v-if="m.role === 'user' && m.mentions && m.mentions.length" class="msg-files">
                <span v-for="(mm, mi) in m.mentions" :key="mi" class="msg-file"
                      :title="mm.type === 'kb' ? '引用的知识库（本轮检索范围）' : '引用的文档（内容直接带入上下文）'">
                  <database-outlined v-if="mm.type === 'kb'" class="msg-file-ic" />
                  <file-text-outlined v-else class="msg-file-ic" />
                  <span class="msg-file-name">{{ mm.name || mm.id }}</span>
                </span>
              </div>
              <!-- 回答归属：会话内首条助手消息、或归属发生变化时才标（同一智能体全程一致则不必重复） -->
              <!-- 显示与否由排障显示开关控制（chat.retrievalDebugEnabled，与「已派遣」提示同一个开关） -->
              <div v-if="showAgentTag(m, i) && debugDisplayVisible" class="agent-tag">
                <robot-outlined class="agent-tag-ic" />
                <span>由「{{ m.agentName }}」回答</span>
              </div>
              <div v-if="m.role === 'ai' && m.thinking" class="think-panel" :class="{ open: m.thinkOpen }">
                <div class="think-head" @click="m.thinkOpen = !m.thinkOpen">
                  <span class="think-title">深度思考</span>
                  <a-spin v-if="m.thinkLoading" size="small" style="margin-left:6px" />
                  <span v-else class="think-badge">已完成</span>
                  <caret-right-outlined class="tl-caret" :class="{ open: m.thinkOpen }" />
                </div>
                <div v-show="m.thinkOpen" class="think-body"><div class="md" v-html="renderMd(m.thinking, [])"></div></div>
              </div>
              <div v-if="hasTimelineBlocks(m)" class="md" :data-msg-index="i">
                <template v-for="(seg, si) in timelineView(m)" :key="si">
                  <div v-if="seg.kind === 'text'" class="tl-text" v-html="renderMd(m.content.slice(seg.from, seg.to), m.images, MD_RICH)"></div>
                  <!-- 过程独白段：区间指向 m.processText（与正文分流），「深度思考」标题行常驻、内容可折叠 -->
                  <div v-else-if="seg.kind === 'process'" class="tl-process-block">
                    <button class="tl-process-head" type="button" @click="toggleProc(m, seg)">
                      <span class="tl-process-title">执行过程</span>
                      <span class="tl-caret" :class="{ open: procOpen(m, seg) }"><caret-right-outlined /></span>
                    </button>
                    <div v-show="procOpen(m, seg)" class="tl-process">{{ procSlice(m, seg) }}</div>
                  </div>
                  <!-- 产物段不再就地渲染：产物统一沉底（时间线数据仍保留 artifact 段以备后续） -->
                  <div v-else class="tl-group">
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
              <!-- streaming（打字光标）仅在正文已有内容时挂：检索/等待阶段正文为空，光标会孤悬成一块 -->
<div v-else class="md" :class="{ streaming: m.loading && !m.failed && !!(m.content && m.content.trim()) }" :data-msg-index="i" v-html="renderMd(m.content, m.images, MD_RICH)"></div>
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
              <!-- 产物统一沉底展示（不再按生成时刻插在时间线中间，避免把回答切碎） -->
              <div v-if="m.role === 'ai' && m.artifacts && m.artifacts.length" class="artifact-list">
                <a v-for="(a, ai) in m.artifacts" :key="ai" class="artifact-item"
                   :href="resolveImg(a.url)" :download="a.filename" target="_blank" :title="'下载 ' + a.filename">
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
              <div v-if="m.role === 'ai' && (m.retrieved || (m.sources && m.sources.length))" class="retrieval-merged">
                <div class="retrieval-line" @click="m.rtOpen = !m.rtOpen">
                  <template v-if="m.retrieved">搜索 {{ m.retrieved.keywords }} 个关键词<template v-if="m.retrieved.refs > 0">，参考 {{ m.retrieved.refs }} 段资料</template><template v-if="m.tokens && m.tokens.hits != null && m.tokens.hits > 0 && m.tokens.hits !== m.retrieved.refs">（{{ m.tokens.hits }} 段填入上下文）</template></template>
                  <template v-else>参考 {{ (m.sources || []).length }} 段资料</template>
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
            <div v-if="m.role === 'ai' && m.failed && !m.loading" class="retry-row">
              <button class="app-btn ghost" :disabled="loading" @click="regenerate(i)"><reload-outlined /> 重试</button>
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
            <!-- 就地编辑框：确认后从这一轮整段重新生成，旧分支软删留档可切回 -->
            <div v-if="m.role === 'user' && editingIdx === i" class="msg-inline-edit">
              <textarea :ref="setEditingRef" v-model="editingText" class="msg-inline-edit-input" rows="3"
                        @keydown.enter.exact.prevent="confirmEdit" @keydown.esc.prevent="cancelEdit" />
              <div class="msg-inline-edit-actions">
                <span class="msg-inline-edit-hint">发送后将从这一轮重新生成，旧回答保留为可切换的版本</span>
                <button class="app-btn ghost small" @click="cancelEdit">取消</button>
                <button class="app-btn small" :disabled="editingBusy || !editingText.trim()" @click="confirmEdit">
                  <send-outlined /> 重新生成
                </button>
              </div>
            </div>
          </div>
        </div>
        <!-- 尾随留白：本轮问题下方补足一屏，使贴底落点=问题置顶（回答长过一屏后归零，恢复正常贴底跟尾） -->
        <div v-if="tailSpacer > 0" class="tail-spacer" :style="{ height: tailSpacer + 'px' }" aria-hidden="true"></div>
        <div v-if="!stickToBottom && messages.length" class="jump-latest" title="回到底部" @click.stop="scrollForce">↓</div>
      </div>

      <!-- 输入区：大圆角卡片（文本上、工具行下） -->
      <div class="input" @dragenter.prevent="onDragEnter" @dragover.prevent @dragleave.prevent="onDragLeave" @drop.prevent="onDropFiles">
        <div v-if="dragOver" class="drop-overlay">松开以添加图片或附件</div>
        <div v-if="pendingFiles.length" class="pending-files">
          <div v-for="(f, fi) in pendingFiles" :key="fi" class="pending-file" :class="{ err: !!f.error }" :title="f.error || f.name">
            <file-text-outlined class="pending-file-ic" />
            <span class="pending-file-name">{{ f.name }}</span>
            <span v-if="f.uploading" class="pending-file-size">上传中…</span>
            <span v-else-if="f.error" class="pending-file-size">上传失败</span>
            <span v-else class="pending-file-size">{{ fmtSize(f.size) }}</span>
            <span class="pending-file-del" @click.stop="removePendingFile(fi)">×</span>
          </div>
        </div>
        <div v-if="pickedSkills.length" class="at-chips">
          <span v-for="n in pickedSkills" :key="n" class="at-chip skill-chip">
            <span class="skill-chip-ava" :style="skillAvaStyle(n)">{{ n.slice(0, 1) }}</span>
            <span class="at-chip-name">{{ n }}</span>
            <span class="at-chip-del" title="移除该技能" @click="toggleSkill(n)">×</span>
          </span>
        </div>
        <div v-if="pendingMentions.length" class="at-chips">
          <span v-for="(mm, mi) in pendingMentions" :key="mm.type + ':' + mm.id" class="at-chip mention-chip"
                :class="'mention-' + mm.type"
                :title="mm.type === 'kb' ? '本轮检索收窄到该知识库' : '该文档内容直接带入本轮上下文'">
            <database-outlined v-if="mm.type === 'kb'" class="at-chip-ic" />
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
        <div class="input-box">
          <!-- @ 引用候选面板（敲 @ 唤起）：kb=收窄检索范围 / doc=强制带入内容 -->
          <div v-if="mentionOpen" class="mention-panel">
            <div class="mention-head">
              <span class="mention-head-tag">@</span>
              <span class="mention-head-word" :class="{ dim: !mentionQuery }">{{ mentionQuery || '输入以筛选知识库或文档' }}</span>
              <button class="app-icon-btn" title="关闭" @click="closeMentionPanel"><close-outlined /></button>
            </div>
            <div class="mention-tabs">
              <button class="mention-tab" :class="{ on: mentionTab === 'kb' }" @click="switchMentionTab('kb')">
                <database-outlined /> 知识库 {{ mentionKbs.length }}
              </button>
              <button class="mention-tab" :class="{ on: mentionTab === 'doc' }" @click="switchMentionTab('doc')">
                <file-text-outlined /> 文档 {{ mentionDocs.length }}
              </button>
            </div>
            <div class="mention-list">
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
              ↑↓ 选择 · Enter 确认 · Esc 关闭　|　@ 知识库 = 本轮检索只在这些库里找；@ 文档 = 该文档内容直接带入本轮上下文
            </div>
          </div>
          <!-- / 快捷命令面板（敲 / 唤起）：模板=往输入框插入常用问法框架；操作=会话级动作立即执行 -->
          <div v-if="slashOpen" class="mention-panel">
            <div class="mention-head">
              <span class="mention-head-tag">/</span>
              <span class="mention-head-word" :class="{ dim: !slashQuery }">{{ slashQuery || '输入以筛选快捷命令' }}</span>
              <button class="app-icon-btn" title="关闭" @click="closeSlashPanel"><close-outlined /></button>
            </div>
            <div class="mention-list">
              <div v-for="(c, ci) in slashFiltered" :key="c.key" class="mention-item"
                   :class="{ hi: slashHi === ci }" @click="runSlashCommand(c)">
                <span class="mention-ava"><component :is="c.icon" /></span>
                <div class="mention-text">
                  <span class="mention-name">{{ c.name }}</span>
                  <span class="mention-desc">{{ c.desc }}</span>
                </div>
                <span class="slash-kind" :class="c.kind === 'tpl' ? 'k-tpl' : 'k-act'">{{ c.kind === 'tpl' ? '模板' : '操作' }}</span>
              </div>
              <div v-if="!slashFiltered.length" class="mention-empty">没有匹配的命令</div>
            </div>
            <div class="mention-foot">↑↓ 选择 · Enter 确认 · Esc 关闭　|　/ 模板 = 插入常用问法框架（可再编辑）；/ 操作 = 立即执行</div>
          </div>
          <!-- # 历史引用面板（敲 # 唤起）：勾选本会话历史问答，随本轮请求前置给模型 -->
          <div v-if="histOpen" class="mention-panel">
            <div class="mention-head">
              <span class="mention-head-tag">#</span>
              <span class="mention-head-word" :class="{ dim: !histQuery }">{{ histQuery || '输入以筛选历史问答' }}</span>
              <button class="app-icon-btn" title="关闭" @click="closeHistPanel"><close-outlined /></button>
            </div>
            <div class="mention-list">
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
          <a-textarea ref="textareaRef" v-model:value="text" placeholder="问点什么？Enter 发送，Shift+Enter 换行（@ 引用资料，/ 快捷命令，# 引用历史问答）"
                      :disabled="loading" :auto-size="{ minRows: 1, maxRows: 6 }" class="input-area"
                      @keydown="onInputKeydown" @input="syncPanelQuery" @click="syncPanelQuery" />
          <div class="input-toolbar">
            <div class="toolbar-left">
              <a-dropdown v-model:open="agentPickerOpen" :trigger="['click']" placement="topLeft">
                <button class="agent-pill" :class="{ on: !!currentAgentId, open: agentPickerOpen, locked: agentLocked }"
                        :title="agentLocked
                          ? `本会话已绑定「${currentAgentName}」，切换智能体会开启新会话`
                          : '选择智能体：按预设覆盖提示词 / 知识库范围 / 能力（模型在右侧选择）'">
                  <AgentAvatar v-if="currentAgent" :agent="currentAgent" :size="16" />
                  <span class="agent-pill-name">{{ currentAgentName }}</span>
                  <lock-outlined v-if="agentLocked" class="agent-pill-lock" />
                  <down-outlined class="agent-pill-caret" />
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
              <a-tooltip title="快捷命令（输入 / 唤起）：常用问法模板与会话操作">
                <button class="app-icon-btn" :class="{ 'toolbar-btn-on': slashOpen }"
                        :disabled="loading" @click="slashOpen ? closeSlashPanel() : openPanelByButton('/')">
                  <thunderbolt-outlined />
                </button>
              </a-tooltip>
              <a-tooltip title="引用历史问答（输入 # 唤起）：把本会话早前的问答指定为本轮上下文">
                <button class="app-icon-btn" :class="{ 'toolbar-btn-on': histOpen || pendingHistoryRefs.length }"
                        :disabled="loading" @click="histOpen ? closeHistPanel() : openPanelByButton('#')">
                  <history-outlined />
                </button>
              </a-tooltip>
              <a-dropdown v-model:open="addMenuOpen" :trigger="['click']" placement="topLeft">
                <button class="app-icon-btn add-btn" :class="{ 'toolbar-btn-on': addMenuOpen || pickedSkills.length }"
                        title="添加附件 / 选用技能">
                  <plus-outlined />
                </button>
                <template #overlay>
                  <div class="add-menu">
                    <div class="add-menu-head"><span>添加</span></div>
                    <div class="add-mi" @click="pickAttachments">
                      <span class="add-mi-ava"><paper-clip-outlined /></span>
                      <div class="add-mi-text">
                        <span class="add-mi-name">附件</span>
                        <span class="add-mi-desc">上传 PDF / Word / Excel / PPT / 文本等文件，解析后供模型阅读（最多 5 个，单个 15MB）</span>
                      </div>
                    </div>
                    <template v-if="skillList.length">
                      <div class="add-menu-sec">技能</div>
                      <div class="add-menu-list">
                        <div v-for="s in skillList" :key="s.name" class="add-mi"
                             :class="{ active: pickedSkills.includes(s.name) }" @click="toggleSkill(s.name)">
                          <span class="add-mi-ava skill-ava" :style="skillAvaStyle(s.name)">{{ s.name.slice(0, 1) }}</span>
                          <div class="add-mi-text">
                            <span class="add-mi-name">{{ s.name }}</span>
                            <span v-if="s.description" class="add-mi-desc">{{ s.description }}</span>
                          </div>
                          <check-outlined v-if="pickedSkills.includes(s.name)" class="agent-mi-check" />
                        </div>
                      </div>
                    </template>
                    <div v-else class="add-mi-empty">还没有可用技能，管理员可在「设置 → 技能」中安装</div>
                    <div class="add-menu-tip">选中技能仅对本轮消息生效</div>
                  </div>
                </template>
              </a-dropdown>
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
                           :width="190" :disabled="loading"
                           @option-hover="onModelOptionHover" @open-change="onModelSelectOpenChange" />
            </div>
            <!-- 两段式停止：生成中第一次 Esc 只「上膛」（按钮切成 esc 键帽，2s 内不按回落），再按一次才停；点击仍是立即停 -->
            <button v-if="loading" class="send-btn stop" :title="escArmed ? '再按一次 Esc 停止生成' : '点击停止生成'" @click="stopNow">
              <span v-if="escArmed" class="esc-cap">esc</span>
              <pause-circle-outlined v-else />
            </button>
            <button v-else class="send-btn" title="发送" :disabled="!canSend" @click="send"><arrow-up-outlined /></button>
          </div>
        </div>
        <input ref="attachInput" type="file" multiple style="display:none" @change="onAttachChange" />
      </div>
    </div>

    <!-- 右侧状态栏（可收起）：运行控制 / 当前智能体 / 产物 / 检索·用量（本轮|会话口径切换）/ 引用来源（有引用才显示）
         宽窄两态共用这一份 DOM：宽屏由 panelOpen 控制（占位列），窄屏由 mPanelOpen 控制
         （浮层/底部 sheet，默认收起）。两态只靠 .as-sheet 类 + CSS 媒体查询区分。 -->
    <aside v-if="panelOpen || mPanelOpen" class="right-panel" :class="{ 'as-sheet': isNarrow }">
      <!-- 窄屏下本栏是覆盖式 sheet，必须给一个显式关闭口：宽屏它是常驻列不需要关，
           窄屏开起来后若只能靠顶栏按钮来回切，用户会觉得「关不掉」。仅窄屏渲染。 -->
      <button v-if="isNarrow" class="rp-sheet-close" type="button" title="关闭" @click="mPanelOpen = false">
        <close-outlined />
      </button>
      <!-- 统计口径切换（持久化）：本轮=最近完成轮明细；会话=全量累计 -->
      <div class="rp-scope-row">
        <span class="rp-scope">
          <span :class="{ on: panelScope === 'round' }" @click="panelScope = 'round'">本轮</span>
          <span :class="{ on: panelScope === 'session' }" @click="panelScope = 'session'">会话</span>
        </span>
      </div>
      <!-- 运行控制：运行中可停止（与发送键/ESC 同一 stop）；最近一轮失败可整轮重试（复用消息流 regenerate） -->
      <div v-if="panelAi && (panelAi.loading || panelAi.failed)" class="rp-card rp-ctrl">
        <button v-if="panelAi.loading" class="rp-ctrl-btn is-stop" @click="stop()"><pause-circle-outlined /> 停止生成</button>
        <button v-else class="rp-ctrl-btn" @click="retryPanelRound()"><reload-outlined /> 重试本轮</button>
      </div>
      <div class="rp-card">
        <div class="rp-label">当前智能体</div>
        <div class="rp-strong rp-agent">
          <AgentAvatar v-if="currentAgent" :agent="currentAgent" :size="18" />
          <robot-outlined v-else class="rp-agent-ic" />
          <span>{{ currentAgentName }}</span>
        </div>
        <!-- 右栏模型行仅展示；上下文容量明细入口统一在输入框工具栏的容量圆环（悬浮弹出） -->
        <div class="rp-row rp-agent-row rp-model-row">
          <span>模型</span>
          <span class="rp-val" :title="effectiveModel">
            <ProviderIcon :icon="effectiveModelIcon" :name="effectiveModelProvider" :size="14" style="margin-right:4px" />
            <span class="rp-val-text">{{ effectiveModelLabel || '—' }}</span>
            <span v-if="modelSourceLabel" class="rp-tag">{{ modelSourceLabel }}</span>
          </span>
        </div>
        <div class="rp-meta">深度思考 {{ deepThinkOn ? '已开启' : '已关闭' }} · 本会话 {{ roundCount }} 轮</div>
      </div>
      <!-- 本会话产物：有文件才显示整卡（空则隐藏，不占版面），点击直接下载（与消息流内下载链接同源）；更多入口跳产物页 -->
      <div v-if="sessionArtifacts.length" class="rp-card">
        <div class="rp-label">产物 · {{ sessionArtifacts.length }} 个</div>
        <a v-for="(a, pi) in sessionArtifacts" :key="pi" class="rp-art"
           :href="resolveImg(a.url)" :download="a.filename" target="_blank"
           :title="'下载 ' + a.filename + (a.description ? '：' + a.description : '')">
          <file-text-outlined class="rp-art-ic" />
          <span class="rp-art-name">{{ a.filename }}</span>
          <download-outlined class="rp-art-dl" />
        </a>
        <div class="rp-art-all" @click="router.push('/artifacts')">在产物页查看全部 ›</div>
      </div>
      <template v-if="panelScope === 'round'">
        <div class="rp-card">
          <div class="rp-label">最近一次检索</div>
          <template v-if="lastRetrieved || lastSources.length">
            <div class="rp-row"><span>检索词 {{ lastRetrieved?.keywords ?? '—' }} 个</span><span v-if="(lastRetrieved?.refs ?? lastSources.length) > 0" class="rp-dim">引用 {{ lastRetrieved?.refs ?? lastSources.length }} 条</span></div>
            <div v-if="lastTokens && lastTokens.hits != null && lastTokens.hits > 0 && lastTokens.hits !== (lastRetrieved?.refs ?? lastSources.length)" class="rp-meta">其中 {{ lastTokens.hits }} 条实际填入上下文（其余为模型中途补充/未入上下文）</div>
            <div v-if="lastRetrieved?.terms?.length" class="rp-terms">{{ lastRetrieved.terms.join('、') }}</div>
            <template v-if="toolSearchQueries(lastAi).length">
              <div class="rp-divider"></div>
              <div class="rp-tool-label">精确检索（模型主动补充）</div>
              <div v-for="(q, qi) in toolSearchQueries(lastAi)" :key="qi" class="rp-terms rp-tool-q">{{ qi + 1 }}. {{ q }}</div>
            </template>
          </template>
          <div v-else class="rp-dim">本轮尚无检索记录</div>
        </div>
        <!-- 本次用量（1.9 Token 消耗可视化）：上下文为实际填充、输出在网关返回 usage 时用实测、否则估算 -->
        <div v-if="lastTokens" class="rp-card">
          <div class="rp-label">本次用量{{ lastTokens.outputIsReal ? '（网关实测）' : '（估算）' }}</div>
          <div class="rp-strong">{{ lastTokens.outputIsReal ? '' : '≈' }}{{ fmtTokens(lastTokens.total) }} tokens</div>
          <!-- 上下文占用条：context/budget，逼近预算变警告色（>80% 警告 / >95% 危险） -->
          <div v-if="lastTokens.budget > 0" class="rp-ctx-bar" :title="'上下文占用 ' + ctxPct + '%'">
            <span class="rp-ctx-fill" :class="ctxLevel" :style="{ width: ctxPct + '%' }"></span>
          </div>
          <div class="rp-row" :style="lastTokens.budget > 0 ? 'margin-top:5px' : ''"><span>上下文 {{ fmtTokens(lastTokens.context) }}<template v-if="lastTokens.budget > 0">（{{ ctxPct }}%）</template></span><span class="rp-dim">预算 {{ fmtTokens(lastTokens.budget) }}</span></div>
          <div class="rp-meta">输出 {{ fmtTokens(lastTokens.output) }}<template v-if="lastTokens.hits > 0"> · 上下文填入 {{ lastTokens.hits }} 块</template></div>
        </div>
      </template>
      <!-- 会话视图：全量累计，只按真实记录的数据统计（c_ai_message 未落库 tokens，历史恢复的轮没有该字段，不冒充 0） -->
      <div v-else class="rp-card">
        <div class="rp-label">会话汇总</div>
        <div class="rp-strong">{{ sessionTokensLabel }}</div>
        <div v-if="sessionTokens.rounds" class="rp-meta">输出 {{ fmtTokens(sessionTokens.output) }} · 按已记录 {{ sessionTokens.rounds }}/{{ roundCount }} 轮累计</div>
        <div v-else-if="roundCount" class="rp-meta">恢复的历史轮次不含用量记录</div>
        <div v-else class="rp-meta">发送问题后统计</div>
        <div class="rp-divider"></div>
        <div class="rp-row"><span>问答 {{ roundCount }} 轮</span><span v-if="sessionArtifacts.length > 0" class="rp-dim">产物 {{ sessionArtifacts.length }} 个</span></div>
        <div v-if="sessionRetrieval.rounds > 0" class="rp-row" style="margin-top:4px"><span>检索 {{ sessionRetrieval.rounds }} 轮<template v-if="sessionRetrieval.refs > 0"> · 引用 {{ sessionRetrieval.refs }} 段</template></span><span v-if="sessionRetrieval.search > 0" class="rp-dim">精确检索 {{ sessionRetrieval.search }} 次</span></div>
        <div v-if="sessionRetrieval.tools > 0" class="rp-meta">工具调用 {{ sessionRetrieval.tools }} 次</div>
      </div>
      <!-- 引用来源：有引用才显示整卡（空则隐藏，不占版面，与产物/沙盒卡同规则） -->
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
    </aside>

    <!-- 引用来源详情弹窗 -->
    <a-modal v-model:open="sourceVisible" :title="sourceTitle" :footer="null" :width="sourceImages.length ? 720 : 560"
             wrap-class-name="source-modal" :keyboard="!previewUrl" :mask-closable="!previewUrl">
      <a-spin v-if="sourceLoading" style="display:block;margin:40px auto" />
      <div v-else class="md src-content" @click="openPreview"
           v-html="renderMd(prepKnowledgeContent(sourceContent || sourceSnippet, sourceImages), sourceImages, MD_RICH)"></div>
      <a v-if="sourceUrl" class="src-origin-link" :href="sourceUrl" target="_blank" rel="noopener">打开原网页</a>
    </a-modal>

    <!-- 引用角标悬浮卡：Teleport 到 body（不被消息区 overflow 裁剪），fixed 定位跟随角标 -->
    <Teleport to="body">
      <div v-if="refTip" class="ref-card" :style="refCardStyle"
           @mouseenter="cancelCloseRefTip" @mouseleave="scheduleCloseRefTip">
        <div class="ref-card-head">
          <span class="ref-card-no">[{{ refTip.ref }}]</span>
          <span class="ref-card-file" :title="refTip.fileName">{{ refTip.fileName }}</span>
          <span v-if="debugDisplayVisible && refTip.score != null" class="ref-card-score" :title="refTip.scoreLabel">
            {{ refTip.scoreLabel }} {{ Number(refTip.score).toFixed(2) }}
          </span>
        </div>
        <div v-if="refTip.title" class="ref-card-title">§ {{ refTip.title }}</div>
        <div class="ref-card-snippet">{{ refTip.snippet }}</div>
        <button v-if="refTip.src" class="ref-card-btn" type="button" @click.stop="refTipOpenSource">查看原文 →</button>
      </div>
    </Teleport>

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

    <!-- 图片灯箱：多图切换 / 滚轮缩放 / 拖动平移 / ESC 关闭 -->
    <div v-if="previewUrl" class="lightbox" @click="closeLightbox" @wheel.prevent="onWheel">
      <img :src="previewUrl" alt="大图预览" @click.stop @error="onImgError" class="lightbox-img"
           :style="{ transform: 'translate(' + offset.x + 'px,' + offset.y + 'px) scale(' + zoom + ')' }"
           @mousedown="onImgMouseDown" @mousemove="onImgMouseMove" @mouseup="onImgMouseUp" @mouseleave="onImgMouseUp" @dblclick="resetView" />
      <button v-if="previewList.length > 1" class="lightbox-prev" :disabled="previewIndex === 0" @click.stop="prevImg">‹</button>
      <button v-if="previewList.length > 1" class="lightbox-next" :disabled="previewIndex === previewList.length - 1" @click.stop="nextImg">›</button>
      <span class="lightbox-close" @click.stop="closeLightbox">×</span>
      <span v-if="previewList.length > 1" class="lightbox-count">{{ previewIndex + 1 }} / {{ previewList.length }}</span>
      <span class="lightbox-tip">滚轮缩放 · 拖动平移 · 双击重置 · ESC 关闭</span>
    </div>

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

    <!-- 上下文容量卡：悬浮输入框工具栏容量圆环弹出（用量/窗口 + 多段占比条 + 分类明细 + 缓存命中率）。
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
            <div class="ctxcap-bar">
              <span v-for="r in ctxCapData.rows" :key="r.key" class="ctxcap-seg"
                    :style="{ width: ctxCapData.pctOf(r) + '%', background: r.color }"
                    :title="r.label + ' ' + ctxCapData.pctOf(r).toFixed(1) + '%'"></span>
            </div>
            <div v-for="r in ctxCapData.rows" :key="r.key" class="ctxcap-row">
              <span class="ctxcap-dot" :style="{ background: r.color }"></span>
              <span class="ctxcap-row-label">{{ r.label }}</span>
              <span class="ctxcap-row-val">{{ ctxCapData.pctOf(r).toFixed(1) }}%</span>
            </div>
            <div v-if="!ctxCapData.rows.length" class="ctxcap-empty">发送问题后显示分类占用</div>
            <div v-if="ctxCapData.cacheRate != null" class="ctxcap-foot">
              <span>缓存命中率</span><span class="ctxcap-row-val">{{ ctxCapData.cacheRate }}%</span>
            </div>
            <div class="ctxcap-tip">窗口：{{ fmtWindow(ctxCapData.window) }} · 用量为本轮真实 prompt token（网关未回传时按估算）</div>
          </template>
          <div v-else class="ctxcap-empty">该模型未登记上下文窗口，请在模型管理中声明</div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAdminSync } from '../utils/auth'
import { message } from 'ant-design-vue'
import { LoadingOutlined, DownOutlined, CaretRightOutlined, CheckOutlined, CloseCircleOutlined, FileTextOutlined, DownloadOutlined, GlobalOutlined, ApiOutlined,
         ExclamationCircleOutlined, CopyOutlined, LikeOutlined, DislikeOutlined, ReloadOutlined, MoreOutlined,
         DeleteOutlined, BugOutlined, EditOutlined, PlusOutlined, PaperClipOutlined, BulbOutlined, PauseCircleOutlined,
         CompressOutlined,
         ArrowUpOutlined, RobotOutlined, SettingOutlined, ThunderboltOutlined, LockOutlined, RedoOutlined,
         CloseOutlined, DatabaseOutlined, SearchOutlined, UpOutlined, ShareAltOutlined,
         HistoryOutlined, TranslationOutlined, QuestionCircleOutlined, SwapOutlined, SendOutlined } from '@ant-design/icons-vue'
import { sendQuestion, newSession, getHistory, deleteSessionApi, submitFeedback as apiSubmitFeedback,
         getKnowledgeDetail, debugRetrieval, deleteMessageGroup, switchMessageVariant, getConfig, getRuntimeConfig, listAvailableAgents,
         listAvailableSkills, getUserPreference, getUserSettings, approveToolCall, addEvalCase,
         listKnowledgeBases, listDocuments, uploadChatAttachment,
         getSessionShare, enableSessionShare, disableSessionShare } from '../api'
import { renderMd, resolveImg, onImgError, copyCode, prepKnowledgeContent, handleMdAction, enhanceDiagrams } from '../utils/markdown'
import { sessionStore, loadSessions, chatStreams, markSessionActive } from './store'
import { exportAnswerMd, exportSessionMarkdown } from './exportMd'
import { fmtTokens } from '../utils/token'
import { loadModelIndex } from '../utils/modelRef'
import { chatDone, refreshSetupGuide, setupGuide } from '../utils/setupGuide'
import ModelSelect from '../components/ModelSelect.vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import BrandMark from '../components/BrandMark.vue'
import AgentAvatar from '../components/AgentAvatar.vue'
import SetupGuide from '../components/SetupGuide.vue'
import { isNarrow, isCoarse } from '../h5/mobile'

const route = useRoute()
const router = useRouter()

// 工具名小白向展示：标签用「动词短语」而不是术语（如 统计字数 / 查阅官方文档），
// 内置工具与后端 ToolInventoryService.LABELS 同源但口径更口语；MCP 工具名是用户登记的
// server 动态合入的（w_q_ 前缀），只映射常用 server 的常用工具（context7 / deepwiki），
// 未映射的去掉前缀原样展示，不臆造翻译。
const TOOL_LABELS = {
  searchKnowledge: '查知识库资料',
  presentArtifact: '生成文件',
  deliver_artifact: '保存生成的文件',
  calculate: '做算术计算',
  currentDateTime: '查看当前时间',
  daysBetween: '算日期相差几天',
  addDays: '推算日期',
  randomNumber: '生成随机数',
  uuid: '生成随机编号',
  unitConvert: '单位换算',
  textStats: '统计字数',
  base64: 'Base64 转码',
  hash: '计算哈希值',
  readSkill: '读取技能说明',
  webSearch: '联网搜索',
  execute: '运行命令',
  read_file: '读取文件',
  write_file: '写入文件',
  edit_file: '编辑文件',
  ls: '查看文件列表',
  // MCP（context7）：去 w_q_ 前缀后按裸名匹配
  resolve_library_id: '查找文档来源',
  query_docs: '查阅官方文档',
  // MCP（deepwiki）
  read_wiki_structure: '查看 Wiki 目录',
  read_wiki_contents: '阅读 Wiki 内容',
  ask_question: '向 Wiki 提问'
}
// 悬停一句话说明（这个工具到底在干什么；未收录的不显示 title）
const TOOL_DESCS = {
  searchKnowledge: '在你上传的知识库里检索相关资料片段',
  presentArtifact: '把内容整理成可下载的文件',
  deliver_artifact: '把云端沙盒里生成的文件存进会话，可查看下载',
  calculate: '精确计算算式，避免模型心算出错',
  currentDateTime: '获取今天的日期与时间',
  daysBetween: '计算两个日期之间相差多少天',
  addDays: '从某个日期加/减 N 天，得到新日期',
  randomNumber: '在指定范围内生成一个随机数',
  uuid: '生成一个不会重复的随机编号（UUID）',
  unitConvert: '长度、重量、温度等单位互相换算',
  textStats: '统计文本的字数、行数、段落数等',
  base64: '在文本与 Base64 编码之间互相转换',
  hash: '给文本算一个「指纹」，用于校验内容是否被改过',
  readSkill: '按需加载某个技能的详细说明',
  webSearch: '上网搜索相关资料，结果会作为引用来源',
  execute: '在隔离的云端沙盒里执行命令行（不影响本机）',
  read_file: '在云端沙盒里读取文件内容',
  write_file: '在云端沙盒里新建或覆盖文件',
  edit_file: '在云端沙盒里修改文件内容',
  ls: '列出云端沙盒里某个目录下的文件',
  resolve_library_id: '先确定要查阅哪个库的官方文档',
  query_docs: '到对应库的官方文档里查找相关内容',
  read_wiki_structure: '浏览开源项目 Wiki 的目录结构',
  read_wiki_contents: '阅读开源项目 Wiki 的具体内容',
  ask_question: '就开源项目 Wiki 的内容提问并取回答案'
}
// ⚠️ 与 McpClientService 的 clientInfo name 对应：wen-qu → w_q_（改名时需同步）
const MCP_CLIENT_PREFIX = 'w_q_'
const bareToolName = n => n.startsWith(MCP_CLIENT_PREFIX) ? n.slice(MCP_CLIENT_PREFIX.length) : n
const toolLabel = n => {
  if (TOOL_LABELS[n]) return TOOL_LABELS[n]
  // MCP 工具记录名带 w_q_ 前缀：先去前缀再试一次映射，未映射的展示裸名
  const bare = bareToolName(n)
  return TOOL_LABELS[bare] || bare
}
const toolDesc = n => TOOL_DESCS[n] || TOOL_DESCS[bareToolName(n)] || ''
const toolCallsView = list => {
  if (!Array.isArray(list)) return []
  return list.filter(t => !(t.status === 'start' && list.some(x => x !== t && x.name === t.name && x.status !== 'start')))
}
const toolDuration = ms => (ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's')
// 是否有正在执行的工具（沙盒命令/MCP 可长时间阻塞）：执行中不显示裸 spin，并在工具条实时计时
const toolRunning = m => Array.isArray(m?.toolCalls) && m.toolCalls.some(t => t.status === 'start')
// 是否有「正在运行且可见」的编排分支。必须带 delegated 条件：编排卡片（subagentCard）只渲染
// 委派分支（多视角模式的分支是实现细节，不渲染卡片）。若这里不带 delegated，多视角模式下
// busyOf 会以为「卡片在转圈」而让位，实际卡片根本不渲染 → 气泡整个空白，loading 凭空消失。
// 两处判定必须同源：busyOf 只能把进度让位给「真的正在渲染」的构件。
const subRunning = m => Array.isArray(m?.subagents) && m.subagents.some(b => b.status === 'running' && b.delegated)

// ==================== 气泡级「进行中」提示（单一进度行） ====================
// 一轮回答里同一时刻只出现一处进行中指示，互斥由本函数的优先级链保证，而不是让
// 阶段提示 / 裸 spin / 重试条各自写 v-if 条件（那样每加一种状态就可能再叠一层）。
// 返回 null = 此刻不该有底部进度行：进行中状态已由更具体的构件表达——
//   工具卡片（转圈 + 实时耗时 + 工具名）、编排卡片（每分支转圈 + 进度条）、
//   深度思考面板（转圈 + 「已完成」）、审批卡（批准/拒绝按钮）。
// 返回 { text } = 带文案的进度行；{ spin: true } = 只有转圈（正文已在流出，不必重复写字）。
const busyOf = m => {
  if (!m || !m.loading) return null
  if (m.approval) return null                     // 等用户批准：进度让位给审批卡
  if (m.retrying) return { text: '连接中断，正在自动重试…', warn: true }
  if (toolRunning(m)) return null                 // 工具自己会转圈并计时
  if (subRunning(m)) return null                  // 编排卡片每个分支自带转圈 + 进度条
  // 深度思考面板只有 thinking 非空才渲染（v-if m.thinking）：让位条件与之对齐，
  // 否则 thinkLoading=true 而面板未渲染时进度行同样凭空消失（与 subRunning 同类坑）
  if (m.thinkLoading && m.thinking) return null       // 深度思考面板自己会转圈
  if (m.stage) return { text: m.stage }           // 后端阶段文案（理解/检索/生成）
  if (m.content) return { spin: true }            // 正文续写中：一个转圈足够
  return { text: '正在生成回答…' }                // 工具已回、正文未出（含多轮工具之间的空档）
}

// ==================== 时间线（正文与工具交错渲染） ====================
// 正文与工具卡片按事件到达顺序交错渲染。timeline 是段数组：
//   {kind:'text', from, to} → 指向 m.content 的切片区间（不复制文本，done 换正文时自动跟随）；
//   {kind:'tool', tool}     → 引用 m.toolCalls 里的同一对象（done/error 原地改状态，卡片自动更新）。
// 渲染经 timelineView(m) 生成视图：连续工具合并为折叠组，正文保持段落连续。
// 时间线随消息落库（正文区间 + 工具/产物下标），历史恢复时由 restoreTimeline 重建，
// 刷新后仍是「正文—工具—正文」的交错过程视图；旧消息无 timeline 才走底部汇总兜底。
// data-msg-index 仍挂在外层 .md 容器上，引用角标逻辑零改动。

/** 是否值得按时间线渲染：有工具段/产物段/过程段才有交错意义（纯正文段与整段渲染等价） */
const hasTimelineBlocks = m => Array.isArray(m?.timeline) && m.timeline.some(s => s && (s.kind === 'tool' || s.kind === 'artifact' || s.kind === 'process'))

const extendTimelineText = (m, from, to) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  const last = tl[tl.length - 1]
  if (last && last.kind === 'text' && last.to === from) last.to = to
  else tl.push({ kind: 'text', from, to })
}

// 过程独白段带区间：指向 m.processText（与正文分流的独立累积），连续追加自动延伸末段
const extendTimelineProcess = (m, from, to) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  const last = tl[tl.length - 1]
  if (last && last.kind === 'process' && last.to === from) last.to = to
  else tl.push({ kind: 'process', from, to })
}

// 过程独白折叠态：键用段起点 from（流式期间 to 随增量增长、from 稳定；timelineView 每帧重算，
// 段对象本身不保状态，与工具卡 _open 存在 toolCalls 对象上同理，这里挂在消息上）。
// 用户点过就认点过的（true/false 写死在 _procOpen 上）；没点过时：流式期间自动展开「正在长的那一段」
// （过程按 token 增量下发，展开才看得见它逐字长出，而不是等闭合标签到达整块蹦出），本轮结束自动收起
// ——与深度思考面板同款（thinkOpen 流式期 true、thinking_done 置 false），历史消息恒折叠。
const procOpen = (m, seg) => {
  const marked = m && m._procOpen ? m._procOpen[seg.from] : undefined
  if (marked === true) return true
  if (marked === false) return false
  if (!m || !m.loading) return false
  const tl = Array.isArray(m.timeline) ? m.timeline : []
  for (let i = tl.length - 1; i >= 0; i--) {
    if (tl[i] && tl[i].kind === 'process') return (Number(tl[i].from) || 0) === (Number(seg.from) || 0)
  }
  return false
}
const toggleProc = (m, seg) => {
  if (!m._procOpen) m._procOpen = {}
  m._procOpen[seg.from] = !procOpen(m, seg)
}

// 历史消息的 processText 可能带前导换行（后端修复前落库的数据，每个 <process> 块标签后的换行
// 原样入通道）：段的起点都是块边界（flush 锚点），显示时剥掉段首换行，免得灰字块顶部空一行；
// 只动显示，不碰区间下标，段内的模型自身换行/空行照常保留。
const procSlice = (m, seg) => (m.processText || '').slice(seg.from, seg.to).replace(/^[\n\r]+/, '')

// 工具段带下标 i：与后端落库口径一致（工具终态在 toolCalls 里的位置），
// 实时态另存对象引用（status 原地更新，卡片自动从转圈变完成）
const pushTimelineTool = (m, tool) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  tl.push({ kind: 'tool', tool, i: Array.isArray(m.toolCalls) ? m.toolCalls.length - 1 : 0 })
}

// 产物段：只存下标（产物清单可能被 done 整体覆盖，存引用会失效），渲染时取 m.artifacts[i]
const pushTimelineArtifact = (m, index) => {
  if (!m) return
  const tl = Array.isArray(m.timeline) ? m.timeline : (m.timeline = [])
  tl.push({ kind: 'artifact', i: index })
}

/** 用后端落库/下发的段数组重建时间线：文本段直接用区间，工具段与产物段按下标取回对象。
 *  下标取不到（数据缺失）就跳过该段，不造数。
 *  相邻过程段合并：过程独白按 token 增量成段，早期落库数据里一段独白被切成上百个碎片段，
 *  逐个渲染就是几十个「执行过程」折叠头。相邻（上一段 to === 本段 from）合并成一段是无损的——
 *  processText 区间本就连贯；中间夹着工具/文本段则不合并，交错顺序如实保留。 */
const restoreTimeline = (m, segs) => {
  const out = []
  for (const s of segs || []) {
    if (!s || !s.kind) continue
    if (s.kind === 'text') {
      out.push({ kind: 'text', from: Number(s.from) || 0, to: Number(s.to) || 0 })
    } else if (s.kind === 'process') {
      const from = Number(s.from) || 0
      const to = Number(s.to) || 0
      const last = out[out.length - 1]
      if (last && last.kind === 'process' && last.to === from) last.to = to
      else out.push({ kind: 'process', from, to })
    } else if (s.kind === 'tool') {
      const t = (m.toolCalls || [])[Number(s.i) || 0]
      if (t) out.push({ kind: 'tool', i: Number(s.i) || 0, tool: t })
    } else if (s.kind === 'artifact') {
      out.push({ kind: 'artifact', i: Number(s.i) || 0 })
    }
  }
  return out
}

/** 句末判定：文本尾部（去空白）以句末标点收尾视为「话已说完」；否则视为半句——后面大概率
 * 接「工具结果回来后继续说」的下半句。尾部有未闭合代码块（``` 为奇数个）时强制视为句末：
 * 跨代码块边界的拼接会让后续正文被吞进代码块。 */
const SENTENCE_END_CHARS = '。，、；：！？…—～~.!?;:』」》）〉】]'
const endsSentence = s => {
  const t = String(s).replace(/\s+$/, '')
  if (!t) return true
  if ((t.match(/```/g) || []).length % 2 === 1) return true
  return SENTENCE_END_CHARS.includes(t.slice(-1))
}

/** 时间线渲染视图：把 timeline 段序列转成可渲染序列。
 * 1）连续工具段（中间夹空白文本不算断点）合并为 {kind:'group', tools:[...]} 折叠组；
 * 2）句中工具吸收：模型常在句中发起工具（"先[调工具]检查环境"），半句之后出现的工具延迟渲染，
 *    等出现下一段正文时把两段文本连排渲染（合并区间是 content 的连续切片——工具不往正文写
 *    内容，连排与全量渲染完全等价，零失真），工具并入后续工具游程，组内顺序仍如实保留时序。
 *    每帧重算无状态：打字机期间合并窗口由收尾 flush 照常显示，运行中工具的卡片始终可见。
 * 尾部 timeline 未覆盖的正文兜底补段（与旧拼接逻辑等价）。 */
const timelineView = m => {
  const len = (m.content || '').length
  const content = String(m.content || '')
  const tl = Array.isArray(m.timeline) ? m.timeline : []
  const out = []
  let group = null
  let maxTo = 0
  let mergeFrom = -1 // >=0 表示有一个未闭合的半句在等待后续正文连排
  const absorbed = [] // 半句与下一段正文之间出现的工具（延迟渲染，按到达顺序保真）
  const flushGroup = () => { if (group) { out.push(group); group = null } }
  const flushMerge = () => {
    if (mergeFrom < 0) return
    out.push({ kind: 'text', from: mergeFrom, to: maxTo })
    for (const t of absorbed) { if (!group) group = { kind: 'group', tools: [] }; group.tools.push(t) }
    absorbed.length = 0
    mergeFrom = -1
  }
  for (const seg of tl) {
    if (!seg) continue
    if (seg.kind === 'process') {
      // 过程独白段：区间指向 m.processText（独立累积），原位灰字渲染；
      // 不参与句中吸收/工具分组，直接断开当前合并窗口与工具组
      flushMerge()
      flushGroup()
      const plen = (m.processText || '').length
      const pf = Math.min(Number(seg.from) || 0, plen)
      const pt = Math.min(Number(seg.to) || 0, plen)
      if (pt > pf) out.push({ kind: 'process', from: pf, to: pt })
      continue
    }
    if (seg.kind === 'artifact') {
      // 产物不再按生成时刻就地渲染（统一沉底展示）：跳过该段，且不打断工具组/合并窗口
      continue
    }
    if (seg.kind === 'tool') {
      if (!seg.tool) continue
      if (mergeFrom >= 0) { absorbed.push(seg.tool); continue }
      if (!group) group = { kind: 'group', tools: [] }
      group.tools.push(seg.tool)
      continue
    }
    const from = Math.min(seg.from, len), to = Math.min(seg.to, len)
    if (to <= from || !content.slice(from, to).trim()) continue // 空白段：不渲染、不打断分组
    if (mergeFrom >= 0) {
      maxTo = to // 半句连排：窗口向本段延伸
      if (endsSentence(content.slice(mergeFrom, maxTo))) flushMerge()
      continue
    }
    flushGroup()
    maxTo = to
    if (endsSentence(content.slice(from, to))) {
      out.push({ kind: 'text', from, to })
    } else {
      mergeFrom = from // 半句：开启合并窗口
    }
  }
  flushMerge()
  flushGroup()
  if (len > maxTo) out.push({ kind: 'text', from: maxTo, to: len })
  return out
}

// ---- 工具卡片：标题行直接亮出关键参数（命令/路径/检索词），点击展开看完整入参与输出 ----
const TOOL_BRIEF_KEYS = ['command', 'query', 'path', 'dir', 'filename', 'url', 'kbIds']
const oneLine = (s, n) => { const x = String(s).replace(/\s+/g, ' ').trim(); return x.length > n ? x.slice(0, n) + '…' : x }
const toolBrief = t => {
  if (!t?.args) return ''
  let obj = null
  try { obj = JSON.parse(t.args) } catch (e) { return oneLine(t.args, 56) } // 非 JSON（MCP 兼容）：纯文本截断
  if (!obj || typeof obj !== 'object') return oneLine(t.args, 56)
  for (const k of TOOL_BRIEF_KEYS) {
    const v = obj[k]
    if (typeof v === 'string' && v.trim()) return oneLine(v, 56)
    if (Array.isArray(v) && v.length) return oneLine(JSON.stringify(v), 56)
  }
  for (const k of Object.keys(obj)) { // 兜底：第一个非空字符串字段（跳过 content/newString 等大文本字段）
    if (k === 'content' || k === 'newString') continue
    const v = obj[k]
    if (typeof v === 'string' && v.trim()) return oneLine(v, 56)
  }
  return oneLine(t.args, 48)
}
const prettyIo = s => {
  if (s == null || s === '') return ''
  try { const o = JSON.parse(s); return o && typeof o === 'object' ? JSON.stringify(o, null, 2) : String(s) } catch (e) { return String(s) }
}
// 运行中卡片显示实时输出（tool_output 增量累积，只展示尾部避免无界增长）；结束后显示最终全文
const LIVE_TAIL_CHARS = 4000
const liveOutput = t => {
  if (t.status === 'start') {
    if (!t.output) return ''
    return (t.outputTruncated ? '…（前面输出已省略）\n' : '') + t.output.slice(-LIVE_TAIL_CHARS)
  }
  return t.result != null ? t.result : (t.output || '')
}
const groupRunning = g => g.tools.some(t => t.status === 'start')
const groupHasError = g => g.tools.some(t => t.status === 'error')
const groupDur = g => {
  let ms = 0, running = false
  for (const t of g.tools) {
    if (t.status === 'start') { running = true; if (t.startAt) ms += Math.max(0, nowTick.value - t.startAt) }
    else ms += t.elapsedMs || 0
  }
  return toolDuration(ms) + (running ? '…' : '')
}
const fallbackDur = m => toolDuration(toolCallsView(m.toolCalls).reduce((s, t) => s + (t.elapsedMs || 0), 0))

/** done 汇总的工具终态合并进现有数组（原地改，保住 timeline 里的对象引用与实时到达的顺序） */
const mergeDoneToolCalls = (m, doneCalls) => {
  if (!m) return
  if (!Array.isArray(m.toolCalls)) m.toolCalls = []
  const list = m.toolCalls
  for (const d of doneCalls) {
    const live = [...list].reverse().find(x => x.name === d.name && x.status === 'start')
    if (live) {
      live.status = d.status || 'done'
      live.elapsedMs = d.elapsedMs || live.elapsedMs || 0
      if (d.args) live.args = d.args // done 汇总带全文（≤8KB），覆盖实时 200 字摘要
      if (d.error) live.error = d.error
      if (d.attempts != null) live.attempts = d.attempts
      if (d.result != null) live.result = d.result
    } else {
      list.push({ ...d })
    }
  }
}
// 运行中工具的实时耗时：1s 一跳的 tick 驱动重渲染，让"卡住"变成可见的进行中
const nowTick = ref(Date.now())
let tickTimer = null
const ensureTick = () => {
  if (tickTimer) return
  tickTimer = setInterval(() => {
    nowTick.value = Date.now()
    if (!chatStreams.size) { clearInterval(tickTimer); tickTimer = null }
  }, 1000)
}
onUnmounted(() => { if (tickTimer) clearInterval(tickTimer) })
const liveToolDur = startAt => Math.max(0, Math.round((nowTick.value - startAt) / 1000)) + 's'
// 精确检索工具实际使用的检索词（模型可主动改词做二次检索，与主链路 retrieved 的词不同源）。
// 从 toolCalls 终态记录的 args 派生：实时路径 start 记录带 args（done 合并后保留），历史恢复是 done 记录带 args，两路都覆盖
const toolSearchQueries = m => {
  if (!Array.isArray(m?.toolCalls)) return []
  const qs = []
  for (const t of m.toolCalls) {
    if (t.name !== 'searchKnowledge' || t.status === 'start') continue
    try { const q = JSON.parse(t.args || '{}').query; if (q) qs.push(q) } catch (e) { /* args 非 JSON 时忽略 */ }
  }
  return qs
}

const text = ref('')
const textareaRef = ref(null)
/** 深度思考按模型库登记的能力三态：none=不支持(隐藏) switchable=可开关 always=恒思考(锁定)；
 *  模型不在模型库（遗留裸名）按可开关处理。开关记忆按模型分开存（ai_deep_think: {ref:0|1}，
 *  旧版单个 '1'/'0' 迁移为所有模型的初始默认）。 */
const THINK_CAPS = {
  none: { visible: false, locked: false, on: false },
  switchable: { visible: true, locked: false, on: null }, // on=null → 读按模型记忆
  always: { visible: true, locked: true, on: true }
}
/** 按模型引用取思考能力 / 支持档位（悬浮面板与发送链路共用同一套口径） */
const thinkCapsOf = ref => THINK_CAPS[modelIndex.value[ref]?.thinking || 'switchable'] || THINK_CAPS.switchable
const reasoningLevelsOf = ref => {
  const raw = modelIndex.value[ref]?.reasoningLevels
  return Array.isArray(raw) ? raw : []
}
const deepThinkDefaults = (() => {
  try {
    const raw = localStorage.getItem('ai_deep_think')
    if (raw === '1' || raw === '0') return { __default: raw === '1' } // 旧版全局开关 → 迁移为默认值
    const parsed = raw ? JSON.parse(raw) : {}
    return typeof parsed === 'object' && parsed ? parsed : {}
  } catch (e) { return {} }
})()
const deepThinkMap = ref({ ...deepThinkDefaults })
/** 某模型本轮是否思考：恒思考强制开；否则按模型记忆（等级下拉的「关闭思考」写 0，选档位/开启写 1） */
const deepOnOf = ref => {
  if (thinkCapsOf(ref).locked) return true
  const explicit = deepThinkMap.value[ref]
  if (explicit !== undefined) return explicit === 1
  return deepThinkMap.value.__default === true
}
/** 生效模型本轮是否思考（状态栏展示 + 发送链路取值） */
const deepThinkOn = computed(() => deepOnOf(effectiveModel.value))

// ==================== 思考等级（低/中/高/超高/极致，按模型支持档位给选项） ====================
/** 档位展示名与顺序（与后端 REASONING_LEVEL_LIST 同序，弱→强） */
const REASONING_LEVELS = [
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' },
  { value: 'xhigh', label: '超高' },
  { value: 'max', label: '极致' }
]
/** ON=开启思考（不带强度：模型未登记档位时用），off=关闭思考；其余为具体档位 */
const THINK_LEVEL_ON = '__on__'
const levelLabel = v => REASONING_LEVELS.find(x => x.value === v)?.label || '默认强度'
/** 等级下拉可选项：恒思考锁定为开；可开关模型给「关闭思考」+ 其支持档位；
 *  未登记档位时给「开启思考 / 关闭思考」两项（强度不可选但思考可开，不留死角）。 */
const levelOptionsOf = ref => {
  const caps = thinkCapsOf(ref)
  const levels = reasoningLevelsOf(ref)
  if (caps.locked) return [{ value: levels[0] || THINK_LEVEL_ON, label: '恒思考 · ' + levelLabel(levels[0]) }]
  const opts = [{ value: 'off', label: '关闭思考' }]
  if (levels.length) {
    for (const lv of REASONING_LEVELS) {
      if (levels.includes(lv.value)) opts.push({ value: lv.value, label: '思考 · ' + lv.label })
    }
  } else {
    // 模型未登记强度档位：只给开关，强度交网关默认
    opts.unshift({ value: THINK_LEVEL_ON, label: '开启思考' })
  }
  return opts
}
/** 某模型当前选中：off=不思考；档位/ON=思考；恒思考模型恒为开（其首个支持档位） */
const currentLevelOf = ref => {
  const levels = reasoningLevelsOf(ref)
  if (thinkCapsOf(ref).locked) return levels[0] || THINK_LEVEL_ON
  if (!deepOnOf(ref)) return 'off'
  const explicit = levelMap.value[ref]
  if (explicit && levelOptionsOf(ref).some(o => o.value === explicit)) return explicit
  const modelDefault = modelIndex.value[ref]?.defaultReasoningLevel
  if (modelDefault && levels.includes(modelDefault)) return modelDefault
  return levels[0] || THINK_LEVEL_ON
}
/** 按模型记忆的等级选择（与深度思考开关同一套 per-model 记忆风格） */
const levelMap = ref(readLevels())
function readLevels() {
  try {
    const raw = localStorage.getItem('ai_think_level')
    const parsed = raw ? JSON.parse(raw) : {}
    return (parsed && typeof parsed === 'object') ? parsed : {}
  } catch (e) { return {} }
}
/** 写入某模型的思考强度（面板点档位按悬浮模型写；不传=生效模型）：
 *  等级与开关联动记忆，选档位/开启=开思考、选关闭=关思考，落 localStorage 立即生效 */
const setThinkLevel = (v, modelRef) => {
  const target = modelRef || effectiveModel.value
  if (loading.value || thinkCapsOf(target).locked) return
  const on = v !== 'off'
  deepThinkMap.value = { ...deepThinkMap.value, [target]: on ? 1 : 0 }
  levelMap.value = { ...levelMap.value, [target]: v }
  try {
    localStorage.setItem('ai_deep_think', JSON.stringify(deepThinkMap.value))
    localStorage.setItem('ai_think_level', JSON.stringify(levelMap.value))
  } catch (e) { /* 存储不可用忽略 */ }
}
/** 生效模型的当前档位 / 本轮下发给后端的思考强度（空串=不指定，后端回落模型登记默认档位） */
const currentThinkLevel = computed(() => currentLevelOf(effectiveModel.value))
const reasoningLevelParam = computed(() => {
  if (!deepThinkOn.value) return ''
  const v = currentThinkLevel.value
  return (v && v !== 'off' && v !== THINK_LEVEL_ON) ? v : ''
})

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
// ==================== 上下文窗口档位（模型登记 [最小窗口~窗口] 区间时面板可点选，默认=上限） ====================
/** 可选档位边界（token）：2 的幂序列；区间两端始终入选，落在区间内的幂点全给 */
const CTX_WINDOW_STEPS = [4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608]
/** K/M 口径窗口格式化：按整除自动选进制——2 的幂登记值走 1024 进制（131072→128K、1048576→1M），
 *  十进制登记值走 1000 进制（100000→100K、200000→200K），两边都不整除才落 1024 一位小数 */
const fmtWindow = n => {
  if (!n || n <= 0) return ''
  for (const base of [1048576, 1000000]) {
    if (n >= base) {
      const m = n / base
      if (m % 1 === 0) return m + 'M'
      if (m < 10 && (m * 10) % 1 === 0) return m.toFixed(1) + 'M'
    }
  }
  if (n >= 1024) {
    // 两种进制都整除时（如 128000）优先 1000 进制——官方口径是 128K 而非 125K
    if (n % 1000 === 0) return n / 1000 + 'K'
    if (n % 1024 === 0) return n / 1024 + 'K'
    const k = n / 1024
    return (k >= 100 ? Math.round(k) : Math.round(k * 10) / 10) + 'K'
  }
  return String(n)
}
/** 某模型的窗口可调区间：null=不可调（未登记窗口/下限，或 min≥max）——面板窗口行保持只读 */
const ctxRangeOf = ref => {
  const info = modelIndex.value[ref]
  if (!info) return null
  const max = info.contextWindow
  const min = info.contextWindowMin
  if (!max || max <= 0 || !min || min <= 0 || min >= max) return null
  return { min, max }
}
/** 档位选项：区间内的 2 的幂点 + 两端（下限不在幂上时补头，上限同理补尾） */
const ctxWindowOptionsOf = ref => {
  const range = ctxRangeOf(ref)
  if (!range) return []
  const mids = CTX_WINDOW_STEPS.filter(v => v > range.min && v < range.max)
  return [range.min, ...mids, range.max].map(v => ({ value: v, label: fmtWindow(v) }))
}
/** 按模型记忆的窗口档位选择（与思考强度同一套 per-model localStorage 记忆风格；未记忆=上限） */
const ctxWindowMap = ref((() => {
  try {
    const raw = localStorage.getItem('ai_ctx_window')
    const parsed = raw ? JSON.parse(raw) : {}
    return (parsed && typeof parsed === 'object') ? parsed : {}
  } catch (e) { return {} }
})())
/** 某模型当前生效窗口：记忆值落在区间内用记忆值，否则默认给最大 */
const effectiveCtxWindowOf = ref => {
  const range = ctxRangeOf(ref)
  if (!range) return null
  const v = ctxWindowMap.value[ref]
  return (v >= range.min && v <= range.max) ? v : range.max
}
/** 面板点选窗口档位：写按模型 localStorage 记忆，下一轮发送立即生效 */
const setCtxWindow = (v, modelRef) => {
  const target = modelRef || effectiveModel.value
  if (loading.value || !ctxRangeOf(target)) return
  ctxWindowMap.value = { ...ctxWindowMap.value, [target]: v }
  try {
    localStorage.setItem('ai_ctx_window', JSON.stringify(ctxWindowMap.value))
  } catch (e) { /* 存储不可用忽略 */ }
}
/** 本轮下发后端的窗口档位（token）：可调模型始终显式下发（含默认上限）；不可调/未登记=null，后端走原逻辑 */
const contextWindowParam = computed(() => {
  const range = ctxRangeOf(effectiveModel.value)
  return range ? effectiveCtxWindowOf(effectiveModel.value) : null
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
const canSend = computed(() => !!(text.value.trim() || pendingImages.value.length || pendingFiles.value.length))

// ==================== 技能（输入框「+」菜单选用，仅对本轮生效） ====================
const skillList = ref([])                 // 可用技能（后端 /skill/available：未停用技能精简列表）
const pickedSkills = ref([])              // 本轮选用的技能名（发送后清空）
const addMenuOpen = ref(false)
const SKILL_AVA_COLORS = ['#e8f1ff', '#fff3e0', '#e8f5e9', '#fdeaf3', '#ede7f6', '#e0f4f1']
const loadSkills = async () => {
  try {
    const r = await listAvailableSkills()
    skillList.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
  } catch (e) { /* 接口不可用时静默：菜单只显示附件入口 */ }
}
const toggleSkill = name => {
  const i = pickedSkills.value.indexOf(name)
  if (i >= 0) pickedSkills.value.splice(i, 1)
  else {
    if (pickedSkills.value.length >= 3) { message.warning('一次最多选用 3 个技能'); return }
    pickedSkills.value.push(name)
  }
}
const skillAvaStyle = name => {
  const i = skillList.value.findIndex(s => s.name === name)
  const color = SKILL_AVA_COLORS[(i < 0 ? name.length : i) % SKILL_AVA_COLORS.length]
  return { background: color }
}

// ==================== 智能体（4.1）：会话级绑定（首问锁定，切换=新会话） ====================
const agentList = ref([])                       // 全部主智能体
// 未绑定会话的待选值：会话ID → 选中的智能体（'__auto__'=自动派遣 / id / ''=全局配置；
// '' 在后端有默认智能体时解析为默认智能体，未设默认才走纯系统设置）
const agentMap = ref({})
// 已绑定会话的绑定镜像：会话ID → {agentId, agentName}，来源为后端 c_ai_session 的绑定
// （done 事件即时回填 + 会话列表兜底，刷新页面后仍能判断锁定态）
const sessionAgent = ref({})
const defaultAgentId = ref('')                  // 默认智能体（isDefault），无则空=全局配置
// 是否存在「默认智能体」（isDefault=1）：有则后端把空 agentId（全局配置）解析为它——
// 「默认（全局配置）」与默认智能体是同一套配置，菜单里只留默认智能体一项，避免两行同义的重复项
const hasDefaultAgent = computed(() => agentList.value.some(a => a.isDefault === 1 || a.isDefault === true))
const AUTO_AGENT = '__auto__'                   // 自动派遣哨兵值（请求时转 "auto"，由后端按描述路由）
/** 会话的智能体绑定（后端权威）：本地镜像优先（本轮刚锁定、会话列表尚未刷新），否则取会话列表项 */
const boundAgentOf = sid => {
  if (!sid) return null
  const local = sessionAgent.value[sid]
  if (local) return local
  const s = sessionStore.list.find(x => x.id === sid)
  // 必须用 != null 同时排除 null 与 undefined：后端未绑定时 agent_id 为 NULL，序列化后是 null（不是字段缺失），
  // 若只判 !== undefined 会把「未绑定」误判成「已绑定为不使用智能体」——表现为会话一进来就锁死，
  // 每次点选都走"开启新会话"分支，点一次建一个空会话。
  if (s && s.agentId != null) return { agentId: s.agentId || '', agentName: s.agentName || '' }
  return null
}
const currentAgentId = computed({
  // 新会话默认「自动派遣」：首问由模型按名称+描述挑最合适的智能体（命中后即锁定，后续轮次不再重路由）；
  // 已绑定会话一律回显锁定值（''=该会话绑定为「不使用智能体」）；没有任何智能体时回退默认助手/全局配置
  get: () => {
    const bound = boundAgentOf(currentSessionId.value)
    if (bound) return bound.agentId || ''
    const memo = agentMap.value[currentSessionId.value]
    if (memo !== undefined) return memo
    return agentList.value.length ? AUTO_AGENT : (defaultAgentId.value || '')
  },
  set: v => { agentMap.value = { ...agentMap.value, [currentSessionId.value]: v || '' } }
})
/** 当前会话是否已锁定智能体（含"已绑定为不使用智能体"；锁定后切换=新会话） */
const agentLocked = computed(() => boundAgentOf(currentSessionId.value) !== null)
const isAdmin = ref(isAdminSync())
const agentPickerOpen = ref(false)
/** 当前生效的智能体名（自动派遣/空 = 走对应模式；已锁定会话显示绑定名） */
const currentAgentName = computed(() => {
  const bound = boundAgentOf(currentSessionId.value)
  if (bound) {
    if (!bound.agentId) return '默认（全局配置）'
    const b = agentList.value.find(x => x.id === bound.agentId)
    return (b && b.name) || bound.agentName || '默认（全局配置）'
  }
  if (currentAgentId.value === AUTO_AGENT) return '自动派遣'
  const a = agentList.value.find(x => x.id === currentAgentId.value)
  return a ? a.name : '默认（全局配置）'
})
/** 当前生效的智能体对象（仅用于 agent-pill 头像；自动派遣 / 全局配置 = null，不显示头像） */
const currentAgent = computed(() => {
  const id = currentAgentId.value
  if (!id || id === AUTO_AGENT) return null
  return agentList.value.find(x => x.id === id) || null
})
/** 选中智能体：未绑定会话记录待选值；已绑定会话按主流约定「切换 = 开启新会话」 */
const pickAgent = id => {
  agentPickerOpen.value = false
  if (agentLocked.value) {
    // 人设/知识库/工具集都随智能体变，同一会话中途换人会让上下文串味 —— 换人即换会话
    const cur = currentAgentName.value
    const target = id === AUTO_AGENT
      ? '自动派遣' : (agentList.value.find(a => a.id === id)?.name || '默认（全局配置）')
    message.info(`本会话已绑定「${cur}」，将开启新会话使用「${target}」`)
    createNewSession(id)
    return
  }
  currentAgentId.value = id || ''
  if (id === AUTO_AGENT) message.success('已切换为「自动派遣」')
  else {
    const a = agentList.value.find(x => x.id === id)
    if (a) message.success(`已切换为「${a.name}」`)
  }
}
/**
 * 归属徽标的「去重」判定：会话内首条助手消息、或与上一条助手消息归属不同时才标（同一个人全程一致不必重复）。
 * 是否允许显示由排障显示开关叠加判断（调用处 && debugDisplayVisible），本函数只负责去重，不读配置。
 */
const showAgentTag = (m, i) => {
  if (m.role !== 'ai' || !m.agentName) return false
  for (let k = i - 1; k >= 0; k--) {
    const prev = messages.value[k]
    if (prev.role === 'ai') return prev.agentName !== m.agentName
  }
  return true
}
/** 模型引用 → 展示名（模型库友好名优先，查不到回退原始引用 provider/model） */
const modelLabelOf = ref => modelIndex.value[ref]?.displayName || ref
/**
 * 「模型已切换」分隔记录：本轮回答与上一条助手消息的落库模型不同时，在本条回答上方挂一条居中分隔。
 * 只在发消息产生新回答时出现（切换选择器本身不产生任何记录）；两侧都有模型引用才比对——
 * 旧消息/工作流轮没有 model，无法判定是否切换，不渲染。返回 {fromLabel,toLabel} 或 null。
 */
const modelSwitchInfo = (m, i) => {
  if (m.role !== 'ai' || !m.model) return null
  for (let k = i - 1; k >= 0; k--) {
    const prev = messages.value[k]
    if (prev.role !== 'ai') continue
    if (!prev.model || prev.model === m.model) return null
    return { fromLabel: modelLabelOf(prev.model), toLabel: modelLabelOf(m.model) }
  }
  return null
}
/** 底部「管理智能体」：跳到独立的一级页面 */
const goManageAgents = () => {
  agentPickerOpen.value = false
  router.push('/agents')
}
const loadAgents = async () => {
  try {
    // 走 available 接口：问答用户可读的精简列表（管理端 /agent/list 仅管理员）
    const r = await listAvailableAgents()
    agentList.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
    // 优先默认助手；未设默认时用列表第一个，保证下拉总有可选项
    const def = agentList.value.find(a => a.isDefault === 1 || a.isDefault === true) || agentList.value[0]
    defaultAgentId.value = def ? def.id : ''
    // 当前会话尚未选择时：有候选智能体则默认「自动派遣」，否则落到默认智能体
    if (!(currentSessionId.value in agentMap.value)) {
      agentMap.value = { ...agentMap.value, [currentSessionId.value]: agentList.value.length ? AUTO_AGENT : defaultAgentId.value }
    }
  } catch (e) { /* 接口不可用时静默：选择器回退为「默认（全局配置）」 */ }
}

const currentSessionId = ref(null)
// 「当前会话正在回答」：流式记录按会话各存一条（chatStreams，store.js 模块单例，跨路由存活）。
// 语义变化：以前是页面全局布尔（任何会话在答都不能切会话/新建，点击被静默吞掉），
// 现在只表示"正在看的这个会话在答"——其它会话后台流不影响新建/切换/发送；
// 同一会话内仍互斥（输入/重新生成/停止都作用于当前会话），守卫写法不用动。
const loading = computed(() => chatStreams.has(currentSessionId.value))
// 开始回答即收起悬浮思考面板（loading 声明后才能 watch，getter 在 watch 调用时同步执行）
watch(() => loading.value, v => { if (v) hideThinkPanel() })
const messages = ref([])
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
const previewList = ref([])
const previewIndex = ref(0)
const previewUrl = computed(() => previewList.value[previewIndex.value] || '')
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragState = ref(null)

const currentSessionTitle = computed(() => {
  const s = sessionStore.list.find(x => x.id === currentSessionId.value)
  return s?.title || '新对话'
})
const roundCount = computed(() => messages.value.filter(m => m.role === 'ai' && !m.loading).length)

// ============ 子智能体编排卡片（仅委派模式显示） ============
// 多视角模式（delegated=false）的分支只是把原问题换个问法，属实现细节，不展示；
// 只有主智能体委派了真实子智能体（有名字、有职责）时，卡片才有信息价值。
const fmtDuration = ms => ms == null ? '—' : (ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : ms + 'ms')
function subagentCard (m) {
  const all = (m && Array.isArray(m.subagents)) ? m.subagents : []
  // 只要有任一分支标记了委派，就按委派模式渲染（后端按整轮是否委派置位，全部分支一致）
  const branches = all.filter(b => b && b.delegated)
  if (!branches.length) return null
  const done = branches.filter(b => b.status === 'done').length
  const running = branches.some(b => b.status === 'running')
  const total = branches.length
  const title = running ? `并行咨询 ${total} 个子智能体…` : `已咨询 ${total} 个子智能体`
  // 按需委派信息：从 N 个候选中挑了 M 个（让"挑选过程"可见，解释为什么只有这几个角色）
  const rt = m.subagentRoute
  const routeNote = (rt && rt.candidates > rt.picked)
    ? `从 ${rt.candidates} 个候选中挑选 ${rt.picked} 个相关的`
    : ''
  // 路由「挑选理由」（名称 → 理由；subagent_route 事件与 done payload 下发，旧消息无此字段不展示）
  const reasons = (rt && rt.reasons && typeof rt.reasons === 'object' && !Array.isArray(rt.reasons)) ? rt.reasons : {}
  // 耗时占比条基准：最慢分支（相对值，让"谁拖了后腿"一眼可见；运行中分支随进度增长）
  const maxElapsed = Math.max(1, ...branches.map(b => b.elapsedMs || 0))
  // 运行中默认展开（要看到实时进度），全部完成后默认收起（信息价值下降，不占版面）；
  // 用户手动点过则尊重其选择（saTouched），不再自动改变
  if (m.saOpen === undefined) m.saOpen = running
  return { branches, done, total, running, title, routeNote, reasons, maxElapsed }
}

/** 分支耗时占比条宽度：相对最慢分支的百分比（下限 2% 保证可见） */
function barWidth (card, b) {
  return Math.max(2, Math.round((b.elapsedMs || 0) / card.maxElapsed * 100)) + '%'
}

/** 手动展开/收起编排卡片（标记 saTouched，避免生成完成后被自动收起打断阅读） */
function toggleSubagents (m) {
  m.saOpen = !m.saOpen
  m.saTouched = true
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
// ==================== 模型切换：会话级覆盖 > 智能体 > 个人默认 > 全局 ====================
const modelMap = ref({})            // 会话ID → 用户手动选择的模型引用（按会话记忆；空=跟随）
const currentOverrideModel = computed({
  get: () => modelMap.value[currentSessionId.value] || '',
  set: v => { modelMap.value = { ...modelMap.value, [currentSessionId.value]: v || '' } }
})
const userDefaultModel = ref('')    // 个人默认模型（个人设置，后端 /user/preference）
const modelIndex = ref({})          // 引用 → { displayName, providerName, icon }（展示映射）

/** 实际生效的模型（引用或遗留名）：会话覆盖 > 个人默认（智能体不绑模型、全局兜底已移除；空=未指定，发送时引导选择） */
const effectiveModel = computed(() => {
  return currentOverrideModel.value || userDefaultModel.value
})
/** 生效模型的展示名（引用串在模型库里映射成 友好名；查不到回退原值） */
const effectiveModelLabel = computed(() => {
  const v = effectiveModel.value
  if (!v) return ''
  const info = modelIndex.value[v]
  return info ? info.displayName : v
})
const effectiveModelIcon = computed(() => {
  const info = modelIndex.value[effectiveModel.value]
  return info ? info.icon : ''
})
const effectiveModelProvider = computed(() => {
  const info = modelIndex.value[effectiveModel.value]
  return info ? info.providerName : ''
})
/** 生效模型来源（状态栏打标）：会话指定 / 个人默认；都没有则不显示标签 */
const modelSourceLabel = computed(() => {
  if (currentOverrideModel.value) return '会话指定'
  if (userDefaultModel.value) return '个人默认'
  return ''
})
// 「检索调试」/「加入评测集」菜单项专属（管理端点，普通用户必 403）：只在管理员身份下从 /config 读取
const debugEntryVisible = ref(false)
// 排障显示开关（chat.retrievalDebugEnabled，设置页「检索调试入口」）：
// 统一控制回答气泡上的「由 X 回答」归属徽标、「已派遣 X」路由提示、引用分值（右栏来源列表分数 + 角标悬浮卡分数）。
// 值必须走 /config/public（管理员/普通用户都能读）——这些是"给不给用户看"的显隐，普通用户也要拿到同一个开关值；
// /config 是管理端点，普通用户调它 403 并触发全局 403 提示（角色未授权），故不复用。
const debugDisplayVisible = ref(false)
const lastAi = computed(() => [...messages.value].reverse().find(m => m.role === 'ai' && !m.loading && (m.content || m.sources?.length)))
const lastRetrieved = computed(() => lastAi.value?.retrieved || null)
const lastSources = computed(() => lastAi.value?.sources || [])
// 引用来源按文档分组：先看到"引用了哪几个文档、各几段"，再按需展开看具体片段
// （平铺 N 行时同一文档的片段会重复出现文件名，反而看不出引用了几个来源）
const groupedSources = computed(() => {
  const groups = []
  const byKey = new Map()
  // 外部来源（联网/MCP）单独成组，名称与知识库文档区分——此前它们没有 fileName/docId，
  // 全部落进「手动补充的知识」兜底组，名不副实
  const groupOf = s => {
    if (s.origin === 'WEB') return { key: '__web', fileName: '联网来源', icon: 'web' }
    if (s.origin === 'MCP') return { key: '__mcp', fileName: 'MCP 来源', icon: 'mcp' }
    return {
      key: s.docId || '__manual_' + (s.fileName || 'x'),
      fileName: s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识'),
      icon: 'doc'
    }
  }
  for (const s of lastSources.value) {
    const gi = groupOf(s)
    let g = byKey.get(gi.key)
    if (!g) {
      g = { key: gi.key, fileName: gi.fileName, icon: gi.icon, items: [] }
      byKey.set(gi.key, g)
      groups.push(g)
    }
    g.items.push(s)
  }
  return groups
})
// 分组展开态（key → 开/合，默认展开）：**必须存在 computed 外的响应式容器**——
// computed 每次求值都重建分组对象，直接 g.open = !g.open 改的是非响应式临时对象，
// 点击既不触发重渲染、状态也会随重建被冲掉（「收缩不生效」的根因）。
// 存 reactive map 还能跨重算保留：流式更新 sources 后用户已收起的组不弹回
const srcOpen = reactive({})
const srcOpenOf = g => (srcOpen[g.key] !== undefined ? srcOpen[g.key] : true)
const toggleSrc = g => { srcOpen[g.key] = !srcOpenOf(g) }
// 本次用量（Token 消耗可视化，1.9）：来自 done 事件的 tokens（上下文实际/预算/块数 + 输出估算）
const lastTokens = computed(() => lastAi.value?.tokens || null)
// 流式中的 prompt 侧用量（后端 usage 事件预下发的估算，onUsage 挂在 msg.tokensPreview）：
// 只看最新一轮 AI 消息——该轮生成中且估算已到才返回；否则为 null（回落 lastTokens，
// 与旧口径一致：流式期间显示上一完成轮的用量）。done 的实测 tokens 到达后自然切换为终值。
const liveTokensPreview = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role !== 'ai') continue
    if (m.loading && m.tokensPreview) return m.tokensPreview
    break
  }
  return null
})
// 容量圆环/明细卡数据源：流式估算优先（生成一开始就亮），无估算回落已完成轮的实测 tokens
const ctxTokens = computed(() => liveTokensPreview.value || lastTokens.value)

// ==================== 上下文容量面板（工具栏圆环悬浮：用量/窗口 + 分类占比 + 缓存命中） ====================
/** 分类展示名与配色（与后端 ctxParts 的键一一对应；占比条按此顺序堆叠） */
const CTX_PART_META = [
  { key: 'messages', label: '消息', color: '#1677ff' },
  { key: 'summary', label: '早期摘要', color: '#13c2c2' },
  { key: 'chunks', label: '知识块', color: '#52c41a' },
  { key: 'system', label: '系统提示词', color: '#722ed1' },
  { key: 'toolSchema', label: '系统工具', color: '#fa8c16' },
  { key: 'mcpSchema', label: 'MCP 工具', color: '#eb2f96' },
  { key: 'skill', label: '技能', color: '#a0d911' },
  { key: 'memory', label: '记忆', color: '#2f54eb' },
  { key: 'input', label: '输入', color: '#8c8c8c' },
  { key: 'other', label: '其他', color: '#bfbfbf' }
]
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
/** 容量数据：窗口取模型登记/用户档位（tokens.window 优先，回落 modelIndex），用量取本轮真实 prompt（无则估算）；
 *  数据源走 ctxTokens（流式期间=usage 预下发的估算，完成后=done 的实测 tokens），生成一开始即点亮 */
const ctxCapData = computed(() => {
  const t = ctxTokens.value
  const info = modelIndex.value[effectiveModel.value]
  const window_ = (t && t.window) || info?.contextWindow || 0
  const used = t ? (Number(t.prompt) || Number(t.context) || 0) : 0
  const parts = (t && t.parts && typeof t.parts === 'object') ? t.parts : null
  const rows = parts
    ? CTX_PART_META.filter(m => Number(parts[m.key]) > 0)
        .map(m => ({ ...m, tokens: Number(parts[m.key]) }))
    : []
  const sum = rows.reduce((a, r) => a + r.tokens, 0)
  const pct = window_ > 0 ? Math.min(100, Math.round(used / window_ * 1000) / 10) : 0
  const cached = t && Number(t.cached) > 0 ? Number(t.cached) : 0
  return {
    window: window_, used, pct, rows, sum,
    // 分类占比以分类之和为分母（校准后与 prompt 一致；无 parts 时退化为空列表）
    pctOf: r => sum > 0 ? (r.tokens / sum * 100) : 0,
    cached, cacheRate: (cached > 0 && used > 0) ? Math.round(cached / used * 1000) / 10 : null
  }
})
// 工具栏容量圆环：r=7.5 周长固定，按 pct 撑 stroke-dasharray；告警档位与右栏占用条一致
const CTX_RING_C = 2 * Math.PI * 7.5
const ctxRingDash = computed(() => {
  const p = Math.min(100, Math.max(0, ctxCapData.value.pct))
  return `${(p / 100 * CTX_RING_C).toFixed(2)} ${CTX_RING_C.toFixed(2)}`
})
const ctxRingLevel = computed(() => ctxCapData.value.pct >= 95 ? 'danger' : ctxCapData.value.pct >= 80 ? 'warn' : '')

// ==================== 右栏：运行控制 / 本会话产物（执行过程卡已移除：消息流内已有完整执行明细） ====================
// panelAi = 最后一轮 AI 消息（含进行中）——供「运行控制」卡（停止/重试本轮）定位重发目标，
// 与「最近一次检索/本次用量」用的 lastAi（仅已完成轮）口径不同
const panelAi = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'ai') return messages.value[i]
  }
  return null
})
// 右栏「重试本轮」：与消息流内重试同源——regenerate 向前配对用户问题后整轮重发；
// 工具级单步重试需要后端重执行机制，暂未实现
const retryPanelRound = () => {
  const m = panelAi.value
  if (!m) return
  const idx = messages.value.indexOf(m)
  if (idx >= 0) regenerate(idx)
}
// 回答富渲染开关：问答页是唯一有会话上下文的消费方，故显式开「在沙盒中运行」按钮
// （后端 scope=(sessionId,uid)；其它页面默认不开——没有会话，按钮点了必然失败）
const MD_RICH = { runnable: true }
// 本会话产物：汇总所有轮次的 artifact（历史恢复的消息同样带 artifacts），最新一轮在前
const sessionArtifacts = computed(() => {
  const out = []
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role !== 'ai' || !Array.isArray(m.artifacts)) continue
    for (const a of m.artifacts) if (a && a.url) out.push(a)
  }
  return out
})
// ===== 右栏统计口径切换（P0 #4）：本轮=最近完成轮明细；会话=全量累计（选择持久化） =====
const panelScope = ref(localStorage.getItem('app_panel_scope') === 'session' ? 'session' : 'round')
watch(panelScope, v => { try { localStorage.setItem('app_panel_scope', v) } catch (e) { /* 存储不可用忽略 */ } })
// 会话累计 tokens：只累加消息里真实记录的用量（tokens 随 done 事件下发、未落库，
// 历史恢复的轮没有该字段——不计入，也不冒充 0）
const sessionTokens = computed(() => {
  let total = 0, output = 0, rounds = 0
  for (const m of messages.value) {
    if (m.role !== 'ai' || !m.tokens || typeof m.tokens !== 'object') continue
    rounds++
    total += Number(m.tokens.total) || 0
    output += Number(m.tokens.output) || 0
  }
  return { total, output, rounds }
})
const sessionTokensLabel = computed(() => sessionTokens.value.rounds ? fmtTokens(sessionTokens.value.total) + ' tokens' : '用量未记录')
// 会话累计检索/工具：检索轮数按「该轮有检索行或引用」计；精确检索次数按 searchKnowledge 终态记录计
const sessionRetrieval = computed(() => {
  let rounds = 0, refs = 0, search = 0, tools = 0
  for (const m of messages.value) {
    if (m.role !== 'ai') continue
    const list = toolCallsView(m.toolCalls)
    tools += list.length
    for (const t of list) if (t.name === 'searchKnowledge' && t.status !== 'start') search++
    if (m.retrieved || (Array.isArray(m.sources) && m.sources.length)) {
      rounds++
      refs += Array.isArray(m.sources) ? m.sources.length : 0
    }
  }
  return { rounds, refs, search, tools }
})
// ===== 上下文占用条（P0 #3）：context/budget，>80% 警告 / >95% 危险 =====
const ctxPct = computed(() => {
  const t = lastTokens.value
  if (!t || !t.budget) return 0
  return Math.min(100, Math.max(0, Math.round((t.context / t.budget) * 100)))
})
const ctxLevel = computed(() => (ctxPct.value > 95 ? 'danger' : (ctxPct.value > 80 ? 'warn' : '')))

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

// 引用来源详情弹窗
/** 外部来源（联网/MCP）：有原网页地址，展示站点名+标题，弹窗不给库内原文 */
const externalOrigin = s => s.origin === 'WEB' || s.origin === 'MCP'
/** 来源条目展示名：外部来源（origin=WEB/MCP）用站点名，库内来源用文件名 */
const sourceName = s => externalOrigin(s)
  ? (s.siteName || (s.origin === 'MCP' ? 'MCP 来源' : '联网来源')) + (s.title ? ' §' + s.title : '')
  : (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + (s.title ? ' §' + s.title : '')
const sourceVisible = ref(false)
const sourceTitle = ref('')
const sourceSnippet = ref('')
const sourceImages = ref([])
const sourceContent = ref('')
const sourceLoading = ref(false)
/** 联网来源（origin=WEB）的原网页地址：库内来源为空，此时弹窗不显示「打开原网页」 */
const sourceUrl = ref('')
const openSource = async s => {
  if (!s) return
  sourceUrl.value = ''
  // 外部来源（联网/MCP）没有库内文档可打开：getKnowledgeDetail(knowledgeId) 必然失败，
  // 走这里展示站点/标题/摘要并给出原网页链接（否则用户点角标得到空白弹窗）
  if (externalOrigin(s)) {
    sourceTitle.value = (s.siteName || (s.origin === 'MCP' ? 'MCP 来源' : '联网来源')) + (s.title ? ' §' + s.title : '')
    sourceSnippet.value = s.snippet || '（该来源未提供摘要）'
    sourceImages.value = []
    sourceContent.value = ''
    sourceLoading.value = false
    sourceVisible.value = true
    sourceUrl.value = s.url || ''
    return
  }
  sourceTitle.value = (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + (s.title ? ' §' + s.title : '')
  sourceSnippet.value = s.snippet || '（无原文片段）'
  sourceImages.value = Array.isArray(s.images) ? s.images : []
  sourceContent.value = ''
  sourceLoading.value = true
  sourceVisible.value = true
  try {
    const r = await getKnowledgeDetail(s.knowledgeId)
    if (r.success && r.data) {
      sourceContent.value = r.data.content || ''
      if (Array.isArray(r.data.images)) sourceImages.value = r.data.images
      if (r.data.title) sourceTitle.value = (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + ' §' + r.data.title
    }
  } catch (e) { /* 接口失败回退 snippet */ }
  finally { sourceLoading.value = false }
}

// 引用角标悬浮卡（自绘浮层，替代原生 title——原生 title 有约 1s 延迟、不可样式化、
// 换行不渲染、也放不下相关度）：展示序号 + 来源文件 + 章节 + 片段 + 相关度，
// 「查看原文」跳来源弹窗；同时驱动右栏来源联动（hoveredRef 高亮）。
const refTip = ref(null)
const refTipPos = ref({ left: 12, top: 0, above: false })
let refTipTimer = null
const refCardStyle = computed(() => {
  const w = Math.min(400, Math.max(260, window.innerWidth - 24))
  const left = Math.max(12, Math.min(refTipPos.value.left, window.innerWidth - w - 12))
  return {
    left: left + 'px',
    top: refTipPos.value.top + 'px',
    width: w + 'px',
    transform: refTipPos.value.above ? 'translateY(-100%)' : 'none'
  }
})
const cancelCloseRefTip = () => { if (refTipTimer) { clearTimeout(refTipTimer); refTipTimer = null } }
const closeRefTip = () => { cancelCloseRefTip(); refTip.value = null }
/** 延迟关闭：角标 → 浮层之间有一段空隙，立即关闭会闪 */
const scheduleCloseRefTip = () => {
  cancelCloseRefTip()
  refTipTimer = setTimeout(() => { refTip.value = null }, 160)
}
const showRefTip = (el, msgIdx, n) => {
  const src = messages.value[msgIdx]?.sources?.[n - 1]
  const r = el.getBoundingClientRect()
  const above = r.bottom + 240 > window.innerHeight
  refTipPos.value = { left: r.left, top: above ? r.top - 6 : r.bottom + 6, above }
  if (!src) {
    // 来源未随本轮下发（如工具模式：模型引用的是 searchKnowledge 工具返回的【引用N】，
    // 该结果在工具卡片里而不在 sources）——不静默无响应，给明确说明
    refTip.value = {
      ref: n, fileName: '', title: '',
      snippet: '本轮的引用来源未随消息下发。若本轮调用了「知识库检索」工具，可在工具卡片中查看检索到的原文片段。',
      score: null, scoreLabel: '', src: null
    }
    return
  }
  refTip.value = {
    ref: n,
    // 外部来源（联网/MCP）没有 fileName/docId，用站点名；MCP 无相关度分，标签区留空
    fileName: externalOrigin(src)
      ? (src.siteName || (src.origin === 'MCP' ? 'MCP 来源' : '联网来源'))
      : (src.fileName || (src.docId ? '来源文档不可用' : '手动补充的知识')),
    title: src.title || '',
    snippet: src.snippet || '（无原文片段）',
    score: (src.rerankScore != null ? src.rerankScore : src.score),
    scoreLabel: src.origin === 'WEB' ? '服务商相关度' : (src.rerankScore != null ? '重排相关度' : (src.origin === 'MCP' ? '' : '检索融合分')),
    src
  }
}
const refTipOpenSource = () => {
  const s = refTip.value && refTip.value.src
  closeRefTip()
  if (s) openSource(s)
}

const refHover = e => {
  const t = e.target
  if (!t || !t.classList) return
  if (!t.classList.contains('ref-sup')) {
    if (hoveredRef.value != null) hoveredRef.value = null
    scheduleCloseRefTip()
    return
  }
  cancelCloseRefTip()
  const n = Number(t.dataset.ref)
  hoveredRef.value = n
  const mdEl = t.closest('.md')
  const msgIdx = mdEl ? Number(mdEl.dataset.msgIndex) : -1
  showRefTip(t, msgIdx, n)
}

// ===== 引用相关度 + 角标联动（P1 #5）=====
// 分值口径：rerankScore=重排模型相关度（重排实际执行才有），否则回落检索融合分 score；都缺则不显示。
// 分值属于调参排障信息，默认不对普通用户露出：显示统一挂 debugDisplayVisible（chat.retrievalDebugEnabled）
const hoveredRef = ref(null)
const fmtSourceScore = s => {
  const v = s ? (s.rerankScore != null ? s.rerankScore : s.score) : null
  return (v == null || isNaN(Number(v))) ? '' : Number(v).toFixed(2)
}
const scoreTitle = s => (s && s.rerankScore != null ? '重排相关度 ' + s.rerankScore : '检索融合分 ' + s.score)
// 正文角标高亮（v-html 内容走 DOM class 切换；仅作用于 lastAi 所在消息）
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

// 消息内容点击：代码复制 / 代码块运行与预览 / 引用角标 → 来源弹窗 / 图片 → 灯箱（事件委托）
const openPreview = e => {
  const t = e.target
  const copyBtn = t && t.closest ? t.closest('.code-copy') : null
  if (copyBtn) { copyCode(copyBtn); return }
  // 富渲染按钮（沙盒运行 / 内联预览）：紧跟复制按钮判定，命中即消费
  const mdAct = t && t.closest ? t.closest('.md-act') : null
  if (mdAct && handleMdAction(mdAct, { sessionId: currentSessionId.value })) return
  if (t && t.classList && t.classList.contains('ref-sup')) {
    const mdEl = t.closest('.md')
    const msgIdx = mdEl ? Number(mdEl.dataset.msgIndex) : -1
    const ref = Number(t.dataset.ref)
    const src = messages.value[msgIdx]?.sources?.[ref - 1]
    if (src) openSource(src)
    return
  }
  if (t && t.tagName && t.tagName.toLowerCase() === 'img') {
    const mdEl = t.closest('.md')
    const msgIdx = mdEl ? Number(mdEl.dataset.msgIndex) : -1
    const seq = Number(t.dataset.seq || 0)
    const imgs = messages.value[msgIdx]?.images
    if (Array.isArray(imgs) && imgs.length) {
      previewList.value = imgs.map(resolveImg)
      previewIndex.value = seq > 0 && seq <= imgs.length ? seq - 1 : 0
    } else {
      previewList.value = [t.getAttribute('src')]
      previewIndex.value = 0
    }
    resetView()
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

// 灯箱
const resetView = () => { zoom.value = 1; offset.value = { x: 0, y: 0 } }
const prevImg = () => { if (previewIndex.value > 0) { previewIndex.value--; resetView() } }
const nextImg = () => { if (previewIndex.value < previewList.value.length - 1) { previewIndex.value++; resetView() } }
const closeLightbox = () => { previewList.value = []; previewIndex.value = 0; resetView(); dragState.value = null }
const onWheel = e => {
  let factor = Math.pow(1.08, -e.deltaY / 100)
  if (factor > 1.3) factor = 1.3
  if (factor < 1 / 1.3) factor = 1 / 1.3
  zoom.value = Math.min(8, Math.max(0.25, zoom.value * factor))
}
const onImgMouseDown = e => {
  if (e.button !== 0) return
  dragState.value = { startX: e.clientX, startY: e.clientY, ox: offset.value.x, oy: offset.value.y }
  e.preventDefault()
}
const onImgMouseMove = e => {
  if (!dragState.value) return
  offset.value.x = dragState.value.ox + (e.clientX - dragState.value.startX)
  offset.value.y = dragState.value.oy + (e.clientY - dragState.value.startY)
}
const onImgMouseUp = () => { dragState.value = null }
const onKeydown = e => {
  if (e.key === 'Escape') closeLightbox()
  else if (e.key === 'ArrowLeft') prevImg()
  else if (e.key === 'ArrowRight') nextImg()
}
watch(previewUrl, v => {
  if (v) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
})

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
watch(loading, v => { if (!v) disarmEsc() })
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
const onWindowResize = () => {
  updateTailSpacer()
  if (stickToBottom.value) scroll()
  if (isNarrow.value) nextTick(retestTextarea)
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
})

// ==================== 会话 ====================
// 失效会话回退防重入：回退链路（autoPick 选中的会话）再失败时不级联触发
let sessionRecovering = false
const switchSession = async sid => {
  // 不再被"正在回答"拦截：流式回调改写的是 chatStreams 里的消息对象，切走不影响后台流
  currentSessionId.value = sid
  // 同步 URL query：侧边栏高亮与刷新恢复都依赖 sid 在地址上
  router.replace({ path: '/chat', query: { sid } }).catch(() => {})
  try {
    // 403（会话归属拒绝）由本函数静默回退处理，不触发全局提示
    const r = await getHistory(sid, { silentForbidden: true })
    // 快速连续切换时晚到的历史响应不覆盖当前视图
    if (r.success && Array.isArray(r.data) && currentSessionId.value === sid) {
      const list = r.data
        .filter(m => m && (m.content || (Array.isArray(m.images) && m.images.length)))
        .map(m => {
          const msg = {
            role: m.role === 'user' ? 'user' : 'ai',
            content: String(m.content || ''),
            messageId: m.messageId || m.id || null,
            fb: (m.fb === 0 || m.fb === 1) ? m.fb : null,
            images: Array.isArray(m.images) ? m.images : [],
            attachments: Array.isArray(m.attachments) ? m.attachments : [],
            sources: Array.isArray(m.sources) ? m.sources : [],
            related: [],
            thinking: m.thinking || '',
            thinkOpen: false,
            time: m.createTime ? new Date(m.createTime).getTime() : null,
            artifacts: Array.isArray(m.artifacts) ? m.artifacts : [],
            toolCalls: Array.isArray(m.toolCalls) ? m.toolCalls : [],
            // 过程独白全文（随消息落库）：历史回显时间线 process 段的区间数据源（旧消息无此字段则为空）
            processText: typeof m.processText === 'string' ? m.processText : '',
            // Token 用量（随消息落库）：历史会话的「本次用量/会话累计」回看数据源（旧消息无此字段则为 null）
            tokens: (m.tokens && typeof m.tokens === 'object') ? m.tokens : null,
            // 回答归属（随消息落库的当轮智能体快照）：历史回显「这条是谁答的」，旧消息无此字段则为空
            agentId: typeof m.agentId === 'string' ? m.agentId : '',
            agentName: typeof m.agentName === 'string' ? m.agentName : '',
            // 本轮生效模型引用（随助手消息落库）：「模型已切换」分隔记录的比对数据源，旧消息无此字段则为空
            model: typeof m.model === 'string' ? m.model : '',
            // 分支版本（编辑重发/重新生成的持久化多版本）：‹ n/N › 切换器的数据源，无版本的消息为 null
            variantCount: m.variantCount || null,
            variantIndex: m.variantIndex || null,
            retrieved: (() => { try { return m.retrieved ? JSON.parse(m.retrieved) : null } catch (e) { return null } })(),
            // 编排视图：历史消息的检索状态行含 branches（随 retrieved 持久化），恢复时一并回显编排面板
            subagents: (() => {
              try {
                const r = m.retrieved ? JSON.parse(m.retrieved) : null
                return (r && Array.isArray(r.branches)) ? r.branches : []
              } catch (e) { return [] }
            })(),
            // 按需委派路由结果（同样随 retrieved 持久化）
            subagentRoute: (() => {
              try {
                const r = m.retrieved ? JSON.parse(m.retrieved) : null
                return (r && r.route) ? r.route : null
              } catch (e) { return null }
            })(),
            // 回答时间线（随消息落库）：重建后刷新页仍是「正文—工具—产物」交错的过程视图
            timeline: []
          }
          if (Array.isArray(m.timeline)) msg.timeline = restoreTimeline(msg, m.timeline)
          return msg
        })
      // 该会话正在流式回答：把 live 消息接回视图尾部。流式中的这轮前后端不落库助手消息，
      // getHistory 里没有这条；用户消息在轮开始时已即时落库，顺序正好衔接。
      // （已被中断的轮次后端会把半程回答按截断态兜底落库——那类轮不再有 live 消息，走上面列表正常回显）
      const st = chatStreams.get(sid)
      if (st && st.msg.loading) list.push(st.msg)
      messages.value = list
      // 先按新列表重算尾随留白、等它落屏再贴底：落点是本轮问题置顶
      //（列表短于一屏时留白为 0，落点即内容底）
      nextTick(() => { updateTailSpacer(); scrollForce() })
    } else if (currentSessionId.value === sid) {
      messages.value = []
    }
  } catch (e) {
    if (currentSessionId.value === sid) messages.value = []
    // 非本人/已删除的会话（403/404）：静默清掉地址栏 sid，回退到自己的最近会话或新建
    // （autoPick 与首屏落地同一逻辑）；先刷新列表，避免用陈旧列表再选中已失效会话
    if (!sessionRecovering && (e?.status === 403 || e?.status === 404)
        && String(route.query.sid || '') === String(sid)) {
      sessionRecovering = true
      currentSessionId.value = null
      router.replace({ path: '/chat' }).catch(() => {})
      try {
        await loadSessions()
        await autoPick()
      } catch (err) {
        /* 回退失败则停在空白会话，不再打扰 */
      } finally {
        sessionRecovering = false
      }
    }
  }
}

const creatingSession = ref(false)
/**
 * 新建会话。presetAgentId：由「切换智能体」触发时带上目标智能体，落为新会话的待选值
 * （新会话尚未绑定，首问发出时才由后端锁定）。
 */
const createNewSession = async presetAgentId => {
  if (creatingSession.value) return
  // 空会话复用排除正在流式的会话：列表里的 messageCount 是快照（首条消息 done 后才刷新），
  // 流式中的会话可能仍记 0——命中它会把当前视图清空而不是开新会话
  // 已绑定智能体的空会话不复用：它带着自己的绑定（删掉一轮问答不会解锁），复用会让"切换智能体"看起来没生效
  const emptySid = sessionStore.list.find(s => (s.messageCount ?? 0) === 0
    && !chatStreams.has(s.id) && !boundAgentOf(s.id))?.id
  if (emptySid) {
    if (presetAgentId !== undefined) {
      agentMap.value = { ...agentMap.value, [emptySid]: presetAgentId || '' }
    }
    if (currentSessionId.value !== emptySid) await switchSession(emptySid)
    else messages.value = []
    router.replace({ path: '/chat', query: { sid: emptySid } })
    focusInput()
    return
  }
  creatingSession.value = true
  try {
    const r = await newSession()
    if (r.success && r.data?.sessionId) {
      const sid = r.data.sessionId
      currentSessionId.value = sid
      if (presetAgentId !== undefined) {
        agentMap.value = { ...agentMap.value, [sid]: presetAgentId || '' }
      }
      messages.value = []
      router.replace({ path: '/chat', query: { sid: r.data.sessionId } })
      await loadSessions()
      focusInput()
    }
  } catch (e) {
    message.error('创建会话失败: ' + (e.message || '未知错误'))
  } finally {
    creatingSession.value = false
  }
}

// 路由 query.sid 驱动：只处理"切到某个会话"；sid 清空（新建/删除当前会话）由 tick 信号接管，
// 避免两条链路同时触发 autoPick / createNew 的竞态
watch(() => route.query.sid, sid => {
  if (route.path !== '/chat' || !sid) return
  if (sid !== currentSessionId.value) switchSession(sid)
})
// 侧边栏「新建对话」信号（消费后回写 seen，跨页积累的 tick 只消费一次）
// 注意不依赖 route.query 状态：tick 触发时路由 push 可能尚未完成，条件里查 sid 会偶发落空
// （不再被"正在回答"拦截：当前会话的流转入后台继续跑）
watch(() => sessionStore.newChatTick, async tick => {
  sessionStore.newChatSeen = tick
  if (route.path === '/chat') await createNewSession()
})
// 当前会话被删除 → 自动落到最近会话或新建
watch(() => sessionStore.autoPickTick, async () => {
  if (route.path === '/chat') await autoPick()
})
const autoPick = async () => {
  const first = sessionStore.list.find(s => (s.messageCount ?? 0) > 0)
  if (first) await switchSession(first.id)
  else await createNewSession()
}

const focusInput = () => nextTick(() => textareaRef.value?.focus())

const handleDeleteSession = async sid => {
  try {
    await deleteSessionApi(sid)
    message.success('会话已删除')
    if (sid === currentSessionId.value) {
      const remaining = sessionStore.list.filter(s => s.id !== sid && (s.messageCount ?? 0) > 0)
      if (remaining.length > 0) await switchSession(remaining[0].id)
      else { messages.value = []; currentSessionId.value = null; router.replace('/chat') }
    }
    await loadSessions()
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 图片上传（粘贴，压缩为 dataURL） ====================
const pendingImages = ref([])
const compressImage = file => new Promise((resolve, reject) => {
  const img = new Image()
  const url = URL.createObjectURL(file)
  img.onload = () => {
    const max = 1280
    let { width, height } = img
    if (width > max || height > max) {
      const ratio = Math.min(max / width, max / height)
      width = Math.round(width * ratio); height = Math.round(height * ratio)
    }
    const canvas = document.createElement('canvas')
    canvas.width = width; canvas.height = height
    canvas.getContext('2d').drawImage(img, 0, 0, width, height)
    URL.revokeObjectURL(url)
    resolve(canvas.toDataURL('image/jpeg', 0.85))
  }
  img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片加载失败')) }
  img.src = url
})
const addImageFiles = files => {
  for (const f of files) {
    if (pendingImages.value.length >= 5) { message.warning('最多上传 5 张图片'); break }
    if (!f.type.startsWith('image/')) continue
    compressImage(f).then(dataUrl => pendingImages.value.push({ dataUrl })).catch(() => message.error(`图片处理失败: ${f.name}`))
  }
}
const removePendingImage = i => pendingImages.value.splice(i, 1)
const previewPendingImage = pi => {
  if (!pendingImages.value.length) return
  previewList.value = pendingImages.value.map(p => p.dataUrl)
  previewIndex.value = pi
  resetView()
}
const openPreviewFromMsg = (m, index) => {
  if (!m.images || !m.images.length) return
  previewList.value = m.images.map(resolveImg)
  previewIndex.value = index || 0
  resetView()
}
const dragOver = ref(false)
let dragDepth = 0
const onDragEnter = e => {
  if (!loading.value && Array.from(e.dataTransfer?.types || []).includes('Files')) {
    dragDepth++
    dragOver.value = true
  }
}
const onDragLeave = () => { if (--dragDepth <= 0) { dragDepth = 0; dragOver.value = false } }
const onDropFiles = e => {
  dragDepth = 0
  dragOver.value = false
  if (loading.value) return
  addFiles(Array.from(e.dataTransfer?.files || []))
}
const onPasteImages = e => {
  const imgs = Array.from(e.clipboardData?.files || []).filter(f => f.type.startsWith('image/'))
  if (!imgs.length || loading.value) return
  e.preventDefault()
  addImageFiles(imgs)
}

// ==================== 附件上传（「+」菜单选择/拖入 → 先传换 fileId，问答请求只带 fileId） ====================
// 此前附件以 base64 内联在 /chat 的 JSON body 里：5×15MB 会打出 ~100MB 的字符串，弱网必挂、
// 断线要整包重传、后端也得先把整包读进内存。现在文件先走 multipart 上传落盘换号，请求体只带 id。
const MAX_FILES = 5
const MAX_FILE_MB = 15
// 与后端 ChatAttachmentService 的类型白名单一致（doc/ppt 老格式未引入 scratchpad，不支持）
const SUPPORTED_EXTS = ['pdf', 'docx', 'xls', 'xlsx', 'pptx', 'txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'log',
  'xml', 'yml', 'yaml', 'html', 'htm', 'java', 'js', 'ts', 'jsx', 'tsx', 'vue', 'py', 'sql', 'sh', 'bat',
  'c', 'h', 'cpp', 'hpp', 'cs', 'go', 'rs', 'rb', 'php', 'css', 'scss', 'less', 'properties', 'ini', 'conf', 'toml']
const pendingFiles = ref([])
const attachInput = ref(null)
const pickAttachments = () => {
  addMenuOpen.value = false
  if (pendingFiles.value.length >= MAX_FILES) { message.warning(`一次最多上传 ${MAX_FILES} 个附件`); return }
  attachInput.value?.click()
}
const onAttachChange = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  addFiles(files)
}
const extOf = name => {
  const dot = (name || '').lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}
const fmtSize = n => {
  if (!n && n !== 0) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}
/** 上传单个附件换 fileId（上传中就挂进列表，用户能看到进度/失败态，而不是点发送后才知道没传上去） */
const uploadOneFile = async f => {
  const item = { name: f.name, size: f.size, mime: f.type || '', fileId: '', uploading: true, error: '' }
  pendingFiles.value.push(item)
  try {
    const r = await uploadChatAttachment(f)
    const d = r?.data || {}
    if (!d.fileId) throw new Error('上传未返回文件标识')
    item.fileId = d.fileId
    item.name = d.name || item.name
    item.mime = d.mime || item.mime
    if (d.size) item.size = d.size
    item.uploading = false
  } catch (e) {
    item.uploading = false
    item.error = e.message || '上传失败'
    message.error(`附件上传失败：${f.name} — ${item.error}`)
  }
}
/** 是否存在还没传完的附件（发送前拦一道：不然用户以为发出去了，其实附件没带上） */
const hasUploadingFile = () => pendingFiles.value.some(f => f.uploading)
/** 统一入口：图片走压缩预览，其余按附件校验后挂起（类型/数量/体积，口径与后端校验一致） */
const addFiles = files => {
  for (const f of files) {
    if (f.type.startsWith('image/')) {
      if (pendingImages.value.length >= 5) { message.warning('最多上传 5 张图片'); continue }
      compressImage(f).then(dataUrl => pendingImages.value.push({ dataUrl }))
        .catch(() => message.error(`图片处理失败: ${f.name}`))
      continue
    }
    if (pendingFiles.value.length >= MAX_FILES) { message.warning(`一次最多上传 ${MAX_FILES} 个附件`); break }
    if (!SUPPORTED_EXTS.includes(extOf(f.name))) {
      message.warning(`暂不支持的附件类型：${f.name}（支持 PDF / Word / Excel / PPT / 文本与代码文件）`)
      continue
    }
    if (f.size > MAX_FILE_MB * 1024 * 1024) { message.warning(`单个附件不能超过 ${MAX_FILE_MB}MB：${f.name}`); continue }
    uploadOneFile(f)
  }
}
const removePendingFile = i => pendingFiles.value.splice(i, 1)

// ==================== @ 引用（本轮显式指定知识库/文档） ====================
// 语义：kb=本轮检索收窄到该库；doc=该文档内容块强制前置进上下文（不经检索、不受相关性门/去冗余约束）。
// 交互：输入框敲 @ 唤起候选面板（面板不抢焦点，筛选词实时取「@ 到光标」之间的正文 + ↑↓ 选 + Enter 确认 + 多选），
//       选中项以 chip 显示在输入框上方。引用字符 @ 本身照常留在输入框里（用户可任意位置输入 @），
//       确认后会把「@ + 筛选词」这一段从正文里摘掉，不留残渣。
// 引用只对当轮生效（与会话级智能体绑定不同）：不落库、不跨轮继承，重新生成时随内存消息重发。
const mentionOpen = ref(false)
const mentionTab = ref('kb')
const mentionQuery = ref('')
const mentionLoading = ref(false)
const mentionKbs = ref([])
const mentionDocs = ref([])
const pendingMentions = ref([])
const mentionHi = ref(0)
const MAX_MENTIONS = 10

const isMentioned = (type, id) => pendingMentions.value.some(m => m.type === type && m.id === id)
const toggleMention = (type, item) => {
  const id = item.id
  if (isMentioned(type, id)) {
    pendingMentions.value = pendingMentions.value.filter(m => !(m.type === type && m.id === id))
    return
  }
  if (pendingMentions.value.length >= MAX_MENTIONS) {
    message.warning(`一次最多引用 ${MAX_MENTIONS} 个知识库/文档`)
    return
  }
  pendingMentions.value.push({
    type,
    id,
    name: type === 'kb' ? item.name : item.fileName,
    kbId: type === 'doc' ? (item.kbId || '') : ''
  })
}
const removeMention = i => pendingMentions.value.splice(i, 1)
/** 切库/文档：候选集换了，高亮下标要收敛回合法范围（否则 kb 第 20 条切到 doc 只有 3 条时按 Enter 会选空） */
const switchMentionTab = t => {
  mentionTab.value = t
  const n = t === 'kb' ? mentionKbFiltered.value.length : mentionDocFiltered.value.length
  if (mentionHi.value >= n) mentionHi.value = 0
}

const openMentionPanel = () => {
  mentionOpen.value = true
  mentionQuery.value = ''
  mentionHi.value = 0
  if (panelTriggerCh !== '@') { panelTriggerPos = -1; panelTriggerCh = '' }
  if (!mentionKbs.value.length && !mentionDocs.value.length) loadMentionCandidates()
}
const closeMentionPanel = () => closeAllPanels()

/** 候选懒加载（首次打开面板时拉取）：库按共享范围过滤（/kb/list 已做），文档同理（/document/list 已做） */
const loadMentionCandidates = async () => {
  mentionLoading.value = true
  try {
    const [kbRes, docRes] = await Promise.all([listKnowledgeBases(), listDocuments()])
    mentionKbs.value = (kbRes?.data || []).map(k => ({
      id: k.id, name: k.name, desc: k.description || '', docCount: k.docCount
    }))
    mentionDocs.value = (docRes?.data || []).map(d => ({
      id: d.id, fileName: d.fileName, kbId: d.kbId, status: d.status
    }))
  } catch (e) {
    message.error('引用候选加载失败：' + (e.message || ''))
  } finally {
    mentionLoading.value = false
  }
}

/**
 * 文档可用性提示（与文档页状态口径一致：0=已入库 / 1=已停用 / 2=解析中 / 3=解析失败）。
 * 只有 0 能引用出内容，其余如实说明——不能让用户 @ 了一个还没解析完的文档后以为系统没生效。
 */
const mentionDocStatus = d => {
  if (d.status === 0) return '已入库'
  if (d.status === 1) return '已停用（不参与引用）'
  if (d.status === 2) return '解析中（引用后可能没有内容）'
  if (d.status === 3) return '解析失败（引用不到内容）'
  return ''
}

const mentionMatch = (text, q) => !q || String(text || '').toLowerCase().includes(q.toLowerCase())
// mentionQuery 现在直接来自输入框正文（「@ 到光标」之间），不再有唤起键混进搜索框的问题
const mentionQueryNorm = computed(() => mentionQuery.value.trim())
const mentionKbFiltered = computed(() =>
  mentionKbs.value.filter(k => mentionMatch(k.name, mentionQueryNorm.value) || mentionMatch(k.desc, mentionQueryNorm.value)))
const mentionDocFiltered = computed(() =>
  mentionDocs.value
    .filter(d => mentionMatch(d.fileName, mentionQueryNorm.value))
    .slice(0, 120))

/** 点面板/输入区之外关闭（面板不遮断输入，所以用 document 级监听而不是遮罩层）；
 *  @ 引用 / / 命令 / # 历史引用三个面板共用同一关闭监听（同一容器、同一交互约定） */
const onDocClickForMention = e => {
  if (!mentionOpen.value && !slashOpen.value && !histOpen.value) return
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
  panelTriggerPos = -1
  panelTriggerCh = ''
}
/** 当前打开面板的候选总数与高亮下标读写（三面板结构一致，抽出来避免各写一遍 if/else） */
const panelListSize = () => {
  if (mentionOpen.value) return mentionTab.value === 'kb' ? mentionKbFiltered.value.length : mentionDocFiltered.value.length
  if (slashOpen.value) return slashFiltered.value.length
  if (histOpen.value) return histCandidates.value.length
  return 0
}
const getPanelHi = () => (mentionOpen.value ? mentionHi : slashOpen.value ? slashHi : histOpen.value)
const setPanelHi = v => {
  if (mentionOpen.value) mentionHi.value = v
  else if (slashOpen.value) slashHi.value = v
  else if (histOpen.value) histHi.value = v
}
const movePanelHi = dir => {
  const n = panelListSize()
  if (!n) return
  setPanelHi((getPanelHi().value + dir + n) % n)   // 循环滚动
}
/** Enter 确认高亮项：@ 引用（加 chip）/ / 命令（插入模板或执行）/ # 历史引用（加 chip）。返回是否消费了本次回车 */
const confirmPanelHi = () => {
  const i = getPanelHi().value
  if (mentionOpen.value) {
    const item = mentionTab.value === 'kb' ? mentionKbFiltered.value[i] : mentionDocFiltered.value[i]
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
  if (/\s/.test(q)) { closeAllPanels(); return }
  if (mentionOpen.value) mentionQuery.value = q
  else if (slashOpen.value) slashQuery.value = q
  else if (histOpen.value) histQuery.value = q
  setPanelHi(0)
}

// 鼠标点候选：与 Enter 确认同一条路径（先摘掉触发片段再加 chip），避免两条路行为不一致
const pickMentionByClick = (type, item) => {
  stripTriggerToken()
  toggleMention(type, item)
  closeAllPanels()
}

/** 工具栏按钮唤起：在光标处补一个触发字符，等价于用户手敲 —— 筛选通道只有输入框一条，
 *  不然按钮开的面板没有筛选入口（原先靠面板自带搜索框，那正是「@ 打不进来」的根因）。 */
const openPanelByButton = ch => {
  const el = rawTextarea()
  const pos = el && typeof el.selectionStart === 'number' ? el.selectionStart : text.value.length
  text.value = text.value.slice(0, pos) + ch + text.value.slice(pos)
  panelTriggerPos = pos
  panelTriggerCh = ch
  nextTick(() => {
    const ta = rawTextarea()
    ta?.focus?.()
    ta?.setSelectionRange(pos + 1, pos + 1)
  })
  if (ch === '#') openHistPanel()
  else openSlashPanel()
}

// ==================== / 快捷命令面板（模板插入 + 会话操作） ====================
const slashOpen = ref(false)
const slashQuery = ref('')
const slashHi = ref(0)
// kind: tpl=插入问法框架到输入框（可再编辑）；act=会话级操作，立即执行
// 模板贴合本系统场景：资料类问法配合 @ 文档/知识库使用，检索类问法用于优化提问
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
  { key: 'act-export', kind: 'act', icon: FileTextOutlined, name: '导出会话 Markdown',
    desc: '把当前会话全部问答导出为 .md 文件',
    run: () => { if (!currentSessionId.value) { message.warning('当前没有可导出的会话'); return } exportSessionMarkdown(currentSessionId.value, currentSessionTitle.value) } },
  { key: 'act-new', kind: 'act', icon: PlusOutlined, name: '新建会话',
    desc: '开一个空白会话（当前会话保留在侧边栏）',
    run: () => createNewSession() }
]
const openSlashPanel = () => {
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
const slashFiltered = computed(() => {
  const q = slashQueryNorm.value.toLowerCase()
  if (!q) return slashCommands
  return slashCommands.filter(c => c.name.toLowerCase().includes(q) || c.desc.toLowerCase().includes(q))
})
const runSlashCommand = c => {
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
    stripTriggerToken()
    closeAllPanels()
    c.run?.()
  }
}

// ==================== # 历史引用面板（勾选本会话历史问答 → 本轮上下文） ====================
const MAX_HISTORY_REFS = 10
const histOpen = ref(false)
const histQuery = ref('')
const histHi = ref(0)
const pendingHistoryRefs = ref([])
// 候选池：当前会话已落库的消息（有 messageId=服务端已确认、非流式中、正文非空）。
// 历史回放的消息自带 messageId（getHistory 下发），当场发的消息在 done 事件回填后也可引用
const histPool = computed(() => messages.value.filter(m =>
  m.messageId && !m.loading && m.content && String(m.content).trim()
    && (m.role === 'user' || m.role === 'ai' || m.role === 'assistant')))
const histQueryNorm = computed(() => histQuery.value.trim())
const histCandidates = computed(() => {
  const q = histQueryNorm.value.toLowerCase()
  const pool = histPool.value.filter(m => !q || String(m.content).toLowerCase().includes(q))
  return pool.slice(-60).reverse()   // 最近的在前，最多列 60 条（防超长会话渲染卡顿）
})
const histItemTitle = m => String(m.content).replace(/[#*`>\-\n]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60)
const histItemDigest = histItemTitle
const histChipLabel = h => h.digest || ''
const isHistPicked = id => pendingHistoryRefs.value.some(h => h.messageId === id)
const toggleHistoryRef = m => {
  if (isHistPicked(m.messageId)) {
    pendingHistoryRefs.value = pendingHistoryRefs.value.filter(h => h.messageId !== m.messageId)
    return
  }
  if (pendingHistoryRefs.value.length >= MAX_HISTORY_REFS) {
    message.warning(`一次最多引用 ${MAX_HISTORY_REFS} 条历史消息`)
    return
  }
  pendingHistoryRefs.value.push({ messageId: m.messageId, role: m.role, digest: histItemDigest(m) })
}
const removeHistoryRef = i => pendingHistoryRefs.value.splice(i, 1)
const pickHistoryRefByClick = m => {
  stripTriggerToken()
  toggleHistoryRef(m)
  closeAllPanels()
}
const openHistPanel = () => {
  slashOpen.value = false
  mentionOpen.value = false
  histOpen.value = true
  histQuery.value = ''
  histHi.value = 0
}
const closeHistPanel = () => closeAllPanels()

// ==================== 会话内查找（Ctrl/⌘+F） ====================
// 匹配范围 = 每条消息的正文（用户问题 + 助手回答）。工具输出与思考过程不参与——那些是过程信息，
// 把它们算进结果只会让"找那句话"更难。
// 高亮走 CSS Custom Highlight API：正文是 v-html 渲染的，往里插 <mark> 会与重渲染/缓存打架；
// 浏览器不支持该 API 时降级为"只定位不标黄"，功能仍可用（不静默失效，见 paintSearchHighlight）。
const searchOpen = ref(false)
const searchQuery = ref('')
const searchPos = ref(0)
const searchInputRef = ref(null)
const matchedIdxs = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return []
  const out = []
  messages.value.forEach((m, i) => {
    if ((m.content || '').toLowerCase().includes(q)) out.push(i)
  })
  return out
})
const clearSearchHighlight = () => {
  try {
    window.CSS?.highlights?.delete('chat-search')
    window.CSS?.highlights?.delete('chat-search-current')
  } catch (e) { /* 不支持该 API 时忽略 */ }
}
/** 显示用的序号：切会话/改关键词后匹配数可能变小，这里夹住上界（否则出现 4 / 2 这种读数） */
const searchPosShown = computed(() => {
  const n = matchedIdxs.value.length
  return n ? Math.min(searchPos.value, n - 1) + 1 : 0
})
/** 逐处命中建 Range，并按所属消息分成「全部命中」与「当前这条消息里的命中」两组——
 *  当前项用更醒目的颜色，否则用户不知道该看哪一处。
 *  跳过代码块/工具输出区（代码里命中会亮成一片，且与"找那句话"语义不符）。 */
const collectSearchRanges = (q, currentMsgIdx) => {
  const all = [], cur = []
  const root = box.value
  if (!root || !q) return { all, cur }
  const lower = q.toLowerCase()
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: n => {
      const p = n.parentElement
      if (!p || p.closest('pre, code, .code-copy, .tl-io, script, style')) return NodeFilter.FILTER_REJECT
      return NodeFilter.FILTER_ACCEPT
    }
  })
  while (walker.nextNode()) {
    const node = walker.currentNode
    const rowIdx = node.parentElement?.closest('[data-row-index]')?.dataset.rowIndex
    const target = (currentMsgIdx != null && rowIdx === String(currentMsgIdx)) ? cur : null
    const low = (node.nodeValue || '').toLowerCase()
    let from = 0
    for (;;) {
      const at = low.indexOf(lower, from)
      if (at < 0) break
      const r = document.createRange()
      r.setStart(node, at)
      r.setEnd(node, at + q.length)
      all.push(r)
      if (target) target.push(r)
      from = at + q.length
    }
  }
  return { all, cur }
}
const paintSearchHighlight = () => {
  clearSearchHighlight()
  const q = searchQuery.value.trim()
  if (!q) return
  if (!window.CSS?.highlights || typeof window.Highlight !== 'function') return // 不支持：只定位不标黄
  try {
    const list = matchedIdxs.value
    const curMsgIdx = list.length ? list[Math.min(searchPos.value, list.length - 1)] : null
    const { all, cur } = collectSearchRanges(q, curMsgIdx)
    if (all.length) CSS.highlights.set('chat-search', new Highlight(...all))
    if (cur.length) CSS.highlights.set('chat-search-current', new Highlight(...cur))
  } catch (e) { /* Range 失效（渲染中）忽略，下次输入重算 */ }
}
const scrollToMatch = () => {
  const list = matchedIdxs.value
  if (!list.length) return
  const mi = list[Math.min(searchPos.value, list.length - 1)]
  box.value?.querySelector(`[data-row-index="${mi}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
const gotoMatch = delta => {
  const list = matchedIdxs.value
  if (!list.length) return
  searchPos.value = (searchPos.value + delta + list.length) % list.length
  paintSearchHighlight() // 当前项变了：重绘（当前项用更醒目的颜色）
  scrollToMatch()
}
const openSearch = () => {
  if (!messages.value.length) { message.info('当前会话还没有消息'); return }
  searchOpen.value = true
  nextTick(() => searchInputRef.value?.focus?.())
}
const closeSearch = () => {
  searchOpen.value = false
  searchQuery.value = ''
  searchPos.value = 0
  clearSearchHighlight()
}
// 关键词变化 → 回到第一个匹配并重绘高亮（消息渲染是异步的，等一帧再画）
watch(searchQuery, () => {
  searchPos.value = 0
  if (!searchOpen.value) return
  nextTick(() => { paintSearchHighlight(); scrollToMatch() })
})
// 切会话/消息增删后旧 Range 已失效：重算（数组引用变化即触发；流式增量不重算，避免每 token 全量扫描）
watch(messages, () => {
  if (!searchOpen.value || !searchQuery.value.trim()) return
  searchPos.value = 0 // 换会话后从头开始，避免停在上一个会话的偏移上
  nextTick(() => { paintSearchHighlight(); scrollToMatch() })
})
// Mermaid 图表：渲染层只吐占位容器，绘图是异步且懒加载依赖，必须在 DOM 落地后补（v-html 之后）。
// **只在非流式态补图**：流式期间 ```mermaid 围栏是半截代码，画必然失败，每个 token 重试一次纯属浪费；
// 停流后 loading 转 false，本 watch 再触发一次把完整图表画出来。
watch([messages, loading], async () => {
  if (loading.value) return
  await nextTick()
  if (box.value) enhanceDiagrams(box.value).catch(() => { /* 绘图失败已在容器内就地提示，不外抛 */ })
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
const shareVisible = ref(false)
const shareLoading = ref(false)
const shareInfo = ref({ enabled: false, token: '', visitCount: 0 })
const shareUrl = computed(() => (shareInfo.value.token ? `${location.origin}/shared/${shareInfo.value.token}` : ''))

const openShare = async () => {
  if (!messages.value.length) { message.info('当前会话还没有内容可分享'); return }
  shareVisible.value = true
  shareLoading.value = true
  try {
    const r = await getSessionShare(currentSessionId.value)
    shareInfo.value = r?.data || { enabled: false, token: '', visitCount: 0 }
  } catch (e) {
    message.error('读取分享状态失败：' + (e.message || ''))
  } finally {
    shareLoading.value = false
  }
}
/** 生成或换新链接（后端对已开启的分享再次开启会换 token，旧链接立即失效） */
const doEnableShare = async (regenerate = false) => {
  shareLoading.value = true
  try {
    const r = await enableSessionShare(currentSessionId.value)
    shareInfo.value = { ...(r?.data || {}), enabled: true, visitCount: 0 }
    message.success(regenerate ? '已换新链接，旧链接立即失效' : '分享链接已生成')
  } catch (e) {
    message.error('生成失败：' + (e.message || ''))
  } finally {
    shareLoading.value = false
  }
}
const regenerateShareLink = () => doEnableShare(true)
const doDisableShare = async () => {
  shareLoading.value = true
  try {
    await disableSessionShare(currentSessionId.value)
    shareInfo.value = { enabled: false, token: '', visitCount: 0 }
    message.success('已停止分享，链接立即失效')
  } catch (e) {
    message.error('停止失败：' + (e.message || ''))
  } finally {
    shareLoading.value = false
  }
}
const copyShareLink = async () => {
  const url = shareUrl.value
  if (!url) return
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(url)
    else fallbackCopyText(url)
    message.success('链接已复制')
  } catch (e) {
    fallbackCopyText(url)
  }
}
const openSharedPage = () => { if (shareUrl.value) window.open(shareUrl.value, '_blank', 'noopener') }

// ==================== 发送与流式回答（SSE，事件处理与旧版口径一致） ====================
const send = () => {
  const q = text.value.trim()
  const imgs = pendingImages.value.map(p => p.dataUrl)
  // 附件只带 fileId（内容已先上传落盘）；未传完的不带走，并明确拦下这次发送——
  // 默默发出去会让用户以为附件生效了，实际模型根本没看到这份材料
  if (hasUploadingFile()) {
    message.warning('附件还在上传中，请稍候再发送')
    return
  }
  const atts = pendingFiles.value
    .filter(f => f.fileId && !f.error)
    .map(f => ({ name: f.name, mime: f.mime, fileId: f.fileId }))
  const attsMeta = pendingFiles.value
    .filter(f => f.fileId && !f.error)
    .map(f => ({ name: f.name, mime: f.mime, size: f.size }))
  const skills = [...pickedSkills.value]
  // @ 引用（本轮显式指定的知识库/文档）：与问题一起提交，服务端按可见性校验后收窄检索范围/强制前置
  const mentions = pendingMentions.value.map(m => ({ type: m.type, id: m.id, name: m.name, kbId: m.kbId || '' }))
  // # 历史引用（本轮显式指定的会话历史问答）：只带 messageId，内容由服务端按会话归属查库回填
  const historyRefs = pendingHistoryRefs.value.map(h => ({ messageId: h.messageId }))
  if ((!q && !imgs.length && !atts.length) || loading.value) return
  // 无任何可用模型（会话/智能体/个人默认均未配置）时引导配置，不打无谓请求
  if (!effectiveModel.value) {
    message.warning('未指定模型：请在右上角选择模型，或在个人设置/智能体中配置默认模型')
    return
  }
  text.value = ''
  closeAllPanels()   // 正文清空后触发字符已失效，显式收起（不依赖 input 回调的副作用）
  pendingImages.value = []
  pendingFiles.value = []
  pickedSkills.value = []
  pendingMentions.value = []
  pendingHistoryRefs.value = []
  const deep = deepThinkOn.value
  // attachData 留在内存消息上：重新生成/自动重试时可原样重发（历史回放无数据，行为与图片 data: 口径一致）
  const userMsg = reactive({ role: 'user', content: q, images: imgs, attachments: attsMeta, attachData: atts,
                        skills, mentions, historyRefs, deepThink: deep, time: Date.now(), messageId: null })
  messages.value.push(userMsg)
  // userMsg 传入流式：done 回填本轮用户消息的落库 ID（userMessageId），编辑重发/分支切换从此可用
  streamAnswer(q, imgs, null, messages.value.length === 1, 1, deep, atts, skills, mentions, null, historyRefs, '', userMsg)
}
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
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
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

const fmtMsgTime = ts => {
  if (!ts) return ''
  const d = new Date(ts)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) return '今天 ' + hm
  const yest = new Date(now)
  yest.setDate(now.getDate() - 1)
  if (d.toDateString() === yest.toDateString()) return '昨天 ' + hm
  return String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') + ' ' + hm
}

/** 工具执行审批：批准/拒绝当前气泡挂起的工具请求；后端以错误结果回给模型继续回答 */
async function resolveApproval (m, approved) {
  if (!m.approval || m.approval.busy) return
  m.approval.busy = true
  try {
    const r = await approveToolCall(m.approval.id, approved)
    if (r && r.success === false) {
      message.warning(r.msg || '审批提交失败')
      m.approval.busy = false
      return
    }
    m.approval = null
    scroll()
  } catch (e) {
    message.error(e.message || '审批提交失败')
    if (m.approval) m.approval.busy = false
  }
}

const streamAnswer = (question, imgs, replaceMsg, isFirstMessage, autoRetry = 1, deepThink = false,
                      attachments = [], skills = [], mentions = [], prev = null, historyRefs = [],
                      editMessageId = '', editUserMsg = null) => {
  // prev = 自动重试上下文 { sid, agentId, model }：沿用原会话与原选择，不读当前 UI 态
  //（重试定时器触发时用户可能已切到别的会话/换了模型）
  const sid = prev ? prev.sid : currentSessionId.value
  const agentId = prev ? prev.agentId : (currentAgentId.value === AUTO_AGENT ? 'auto' : (currentAgentId.value || ''))
  const model = prev ? prev.model : (currentOverrideModel.value || '')
  // 重新生成：被替换的旧回答 ID 必须在下面 fresh 覆盖之前捕获（fresh 会把 messageId 置空，
  // 覆盖后再读就永远是空 → 后端不软删旧回答 → 刷新后同一问题出现两条答案）
  const replacedMessageId = replaceMsg && replaceMsg.messageId ? replaceMsg.messageId : ''
  // 流式回调统一改写 msg 对象（而非 messages.value[idx]）：切走会话后 messages 数组已换人，
  // 下标会指错位置；对象引用由 chatStreams 持有，切回来时 switchSession 把它接回视图尾部
  // model 先按前端解析的生效引用预填（覆盖>个人默认，与后端 resolveModel 同序）：「模型已切换」
  // 分隔记录在本轮回答一出现就能比对；done 再用后端权威值校正
  const fresh = { role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, retrying: false, thinking: '', thinkOpen: true, thinkLoading: false, stage: '正在思考中…', time: Date.now(), artifacts: [], toolCalls: [], subagents: [], plan: null, timeline: [], errorCard: null, model: model || userDefaultModel.value }
  const msg = replaceMsg ? Object.assign(replaceMsg, fresh, { messageId: null, fb: null }) : reactive(fresh)
  if (!replaceMsg) messages.value.push(msg)
  const viewing = () => currentSessionId.value === sid  // 只有正在看这个会话才滚动/贴底
  // 流式期间：先按新高度重算留白、等它落屏，再贴底——留白让贴底落点停在本轮问题置顶处，
  // 回答长过一屏后留白归零，贴底就自然变成跟着最新内容走（用户上翻仍会解除跟随）
  const liveScroll = () => { if (viewing()) nextTick(() => { updateTailSpacer(); scroll() }) }
  const abort = new AbortController()
  const st = { msg, abort }
  chatStreams.set(sid, st)
  // 首条消息发出即把会话抬进侧栏列表（列表隐藏空会话，等 onDone 才刷新的话长回答期间不可见）
  if (isFirstMessage) markSessionActive(sid, question)
  // 本轮视角归位：留白补足并落屏后贴底，问题正好落在视口最上（与空会话首问同一落点）
  if (viewing()) nextTick(() => { updateTailSpacer(); scrollForce() })
  let full = ''
  let gotToken = false
  // 流式渲染节流：token 只进缓冲 full，每 120ms 批量刷一次 msg.content 与时间线文本区间——
  // 每个 token 都写响应式字段会让整页消息列表重跑渲染管线（renderMd 全量 × 消息数），
  // 长回答越流越卡。done/停止等终态路径 flushNow() 保底：最终态完整、不丢已流出内容。
  let flushTimer = null
  let flushedLen = 0
  const flushNow = (keepScroll = true) => {
    if (flushTimer) { clearTimeout(flushTimer); flushTimer = null }
    if (full.length > flushedLen) {
      extendTimelineText(msg, flushedLen, full.length)
      flushedLen = full.length
    }
    if (msg.content !== full) msg.content = full
    // 正文增长必须在这里推进视角：token 不走 liveScroll（只进缓冲、由这里批量落屏），
    // 少这一下长回答就只跟到最后一个 stage 事件，新内容滚出屏幕看不见。
    // keepScroll=false：调用方（过程独白增量）随后自己会补 liveScroll，避免同一次落屏量两遍 DOM
    if (keepScroll) liveScroll()
  }
  const flushSoon = () => {
    if (flushTimer) return
    // 包一层：flushNow 现带 keepScroll 形参，避免把定时器回调的实参当成它
    flushTimer = setTimeout(() => flushNow(), 120)
  }
  sendQuestion(sid, question, imgs, {
    signal: abort.signal,
    deepThink,
    // 思考强度档位（低/中/高/超高/极致）：空串=不指定，后端回落模型登记的默认档位
    reasoningLevel: reasoningLevelParam.value,
    // 上下文窗口档位（token）：仅模型登记了「最小~最大」区间时下发（null=后端用登记上限/全局默认）
    contextWindow: contextWindowParam.value,
    attachments,
    skills,
    mentions,
    // # 历史引用（[{messageId}]，重新生成时随内存消息原样重发）：服务端按会话归属校验后前置内容
    historyRefs: Array.isArray(historyRefs) && historyRefs.length ? historyRefs : [],
    // 重发标记（重新生成/自动重试走 replaceMsg 路径）：后端跳过用户消息重复落库
    regenerate: replaceMsg != null,
    // 被替换的旧回答消息 ID（仅重新生成时非空：自动重试的那一轮还没落库，messageId 为 null）；
    // 后端据此在落库前软删旧行，历史里只留最新一版
    replaceMessageId: replacedMessageId,
    // 编辑重发：被编辑的用户消息 ID（后端软删其旧分支留档，编辑内容作为新分支重新生成）
    editMessageId,
    agentId,
    // 会话级模型覆盖：仅用户手动切换时传（空=后端按 个人默认>无 兜底解析，全局模型默认已退役）
    model,
    onThinking: t => {
      msg.thinking = (msg.thinking || '') + t
      msg.thinkLoading = true
      liveScroll()
    },
    onThinkingDone: payload => {
      msg.thinkLoading = false
      msg.thinkOpen = false
      try {
        const j = JSON.parse(payload)
        if (j.thinking) msg.thinking = j.thinking
      } catch (e) { /* 兼容旧 payload */ }
    },
    onToken: t => { gotToken = true; full += t; flushSoon(); msg.stage = ''; msg.thinkLoading = false },
    onProcess: t => {
      // 过程独白（<process> 标签内，与正文分流）：累积 processText 并推进时间线过程段（灰字弱化渲染）。
      // 后端按 token 增量下发，这里逐条追加即成流式。先 flushNow 落屏节流中的正文：正文 token 走
      // 120ms 节流而过程事件即时到达，不先刷正文，过程段会被记在尚未落屏的正文之前——实时视图里
      // 灰字块跳到正文上方（done 用落库版时间线校正后又会跳回去，一来一回正是「块突然出现又移位」）
      flushNow(false)
      const prevLen = (msg.processText || '').length
      msg.processText = (msg.processText || '') + t
      extendTimelineProcess(msg, prevLen, msg.processText.length)
      liveScroll()
    },
    onStage: s => { msg.stage = s; liveScroll() },
    onPlan: p => {
      // 本轮执行计划（后端按配置确定会跑的步骤）：右栏清单逐项点亮的数据源；仅实时，历史轮无此字段
      try {
        const arr = typeof p === 'string' ? JSON.parse(p) : p
        if (Array.isArray(arr) && arr.length) msg.plan = arr
      } catch (e) { /* 忽略 */ }
    },
    onUsage: payload => {
      // 用量预下发（usage 事件，生成一开始就到）：prompt 侧估算先行点亮容量圆环/明细卡；
      // done 下发的实测 tokens 仍是终值（落库/累计/输出侧都只认它），此处只挂流式中的估算视图
      try {
        const j = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (j && typeof j === 'object') msg.tokensPreview = j
      } catch (e) { /* 忽略 */ }
    },
    onRetrieved: payload => {
      try {
        const j = JSON.parse(payload)
        msg.retrieved = { keywords: j.keywords || 0, refs: j.refs || 0, terms: j.terms || [] }
        msg.stage = ''
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onApprovalRequired: payload => {
      // 工具执行审批（人在回路）：卡片挂到当前 AI 气泡，批准/拒绝后模型继续走
      try {
        const j = typeof payload === 'string' ? JSON.parse(payload) : payload
        msg.approval = { id: j.approvalId, tool: j.tool, args: j.args, timeoutMs: j.timeoutMs, busy: false }
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onImage: imgs2 => {
      try {
        const parsed = JSON.parse(imgs2)
        msg.images = Array.isArray(parsed) ? parsed : []
      } catch (e) { msg.images = [] }
    },
    onArtifact: payload => {
      try {
        const a = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!a || !a.url) return
        if (!Array.isArray(msg.artifacts)) msg.artifacts = []
        msg.artifacts.push(a)
        // 产物按生成时刻插入时间线（只记下标），刷新后仍在原位而不是堆到气泡底部
        pushTimelineArtifact(msg, msg.artifacts.length - 1)
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onToolStatus: rec => {
      try {
        const t = typeof rec === 'string' ? JSON.parse(rec) : rec
        if (!t || !t.name) return
        if (!Array.isArray(msg.toolCalls)) msg.toolCalls = []
        if (t.status === 'start') {
          const rec = { ...t, startAt: Date.now() }
          msg.toolCalls.push(rec)
          pushTimelineTool(msg, rec)
          // 进入工具阶段：上一句阶段文案（如「正在检索资料…」）已过期，清掉避免工具跑完又复活。
          // 该阶段由工具卡片自己表达（转圈 + 实时耗时），底部进度行不重复描述
          msg.stage = ''
          ensureTick()
        } else {
          const list = msg.toolCalls
          const last = [...list].reverse().find(x => x.name === t.name && x.status === 'start')
          if (last) {
            last.status = t.status
            last.elapsedMs = t.elapsedMs || 0
            if (t.args) last.args = t.args
            if (t.result != null) last.result = t.result
            if (t.error) last.error = t.error
          } else {
            const rec = { ...t }
            list.push(rec)
            pushTimelineTool(msg, rec)
          }
        }
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onToolOutput: payload => {
      try {
        const o = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!o || !o.delta) return
        const list = msg.toolCalls
        if (!Array.isArray(list)) return
        const live = [...list].reverse().find(x => x.name === o.name && x.status === 'start')
        if (!live) return
        const MAX = 65536
        let out = (live.output || '') + o.delta
        if (out.length > MAX) { out = out.slice(out.length - MAX); live.outputTruncated = true }
        live.output = out
      } catch (e) { /* 忽略 */ }
    },
    onSubagent: payload => {
      try {
        const b = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!b || b.id == null) return
        const list = msg.subagents || (msg.subagents = [])
        const i = list.findIndex(x => x.id === b.id)
        if (i >= 0) list[i] = { ...list[i], ...b }
        else list.push(b)
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    onSubagentRoute: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r) return
        msg.subagentRoute = { candidates: r.candidates || 0, picked: r.picked || 0, names: r.names || [], reasons: r.reasons || {} }
      } catch (e) { /* 忽略 */ }
    },
    onAgentDispatched: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r || !r.name) return
        msg.dispatched = {
          name: r.name, description: r.description || '', fallback: r.fallback === true
        }
        liveScroll()
      } catch (e) { /* 忽略 */ }
    },
    // 会话级绑定结果：后端在首问解析并锁定后就下发（不等整轮结束），输入区立刻切锁定态
    onAgentBound: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r || !r.locked) return
        sessionAgent.value = {
          ...sessionAgent.value,
          [sid]: { agentId: r.agentId || '', agentName: r.agentName || '' }
        }
        if (r.agentName) msg.agentName = r.agentName
      } catch (e) { /* 忽略 */ }
    },
    onDone: contentJson => {
      flushNow()   // 终态保底：把缓冲里未刷的正文与时间线刷进响应式（停止生成/最终正文对比都依赖它）
      let sources = [], related = [], messageId = null, degradations = []
      try {
        const p = JSON.parse(contentJson || '{}')
        sources = Array.isArray(p.sources) ? p.sources : []
        related = Array.isArray(p.related) ? p.related : []
        messageId = p.messageId || null
        degradations = Array.isArray(p.degradations) ? p.degradations : []
        // 编辑重发：done 带回本轮用户消息的落库 ID——回填到本地新用户消息上，
        // 该消息的 ‹ n/N › 分支切换器与「再次编辑」从此可用
        if (editUserMsg && p.userMessageId) editUserMsg.messageId = p.userMessageId
        if (p.tokens && typeof p.tokens === 'object') msg.tokens = p.tokens
        // 会话级绑定：首问后本会话即锁定智能体（agentLocked 由后端下发，含"绑定为不使用智能体"）。
        // 本地镜像先于会话列表刷新生效，输入区立刻切到锁定态（切换=新会话）
        if (p.agentLocked) {
          sessionAgent.value = {
            ...sessionAgent.value,
            [sid]: { agentId: p.agentId || '', agentName: p.agentName || '' }
          }
        }
        if (p.agentName) msg.agentName = p.agentName
        // 生效模型以后端权威解析为准（请求未带覆盖时后端回落个人默认，前端预填值在此校正）
        if (typeof p.model === 'string' && p.model) msg.model = p.model
        if (p.thinking) msg.thinking = p.thinking
        msg.thinkLoading = false
        if (typeof p.finalContent === 'string' && p.finalContent !== '') {
          // 最终正文与流式累积不一致（引用自检重建/related 清理等改写了正文）：文本区间失效，
          // 以 done 下发的落库版时间线为准（区间已按最终正文夹取）；后端没给时间线才回退底部汇总
          if (p.finalContent !== msg.content) msg.content = p.finalContent
        }
        if (Array.isArray(p.finalImages)) msg.images = p.finalImages
        if (Array.isArray(p.artifacts) && p.artifacts.length) msg.artifacts = p.artifacts
        // 过程独白以 done 下发的落库版为准（须先于 timeline 恢复赋值：过程段区间指向它）
        if (typeof p.processText === 'string') msg.processText = p.processText
        // done 工具终态原地合并（不整组替换）：保住 timeline 引用与实时到达顺序，终态字段覆盖
        if (Array.isArray(p.toolCalls) && p.toolCalls.length) mergeDoneToolCalls(msg, p.toolCalls)
        // 时间线以落库版为准：本轮视图与刷新后视图同源（工具/产物下标与终态清单一一对应）
        if (Array.isArray(p.timeline) && p.timeline.length) msg.timeline = restoreTimeline(msg, p.timeline)
        else if (msg.content !== full) msg.timeline = []
        // 编排视图：done 下发分支最终状态，覆盖实时 subagent 事件收敛到终态
        if (Array.isArray(p.subagentBranches) && p.subagentBranches.length) msg.subagents = p.subagentBranches
        if (p.subagentRoute) msg.subagentRoute = p.subagentRoute
      } catch (e) { /* 旧版/停止生成：无负载 */ }
      if (msg.content === '') msg.content = '（已停止生成）'
      msg.loading = false
      // 整轮耗时（右栏「生成回答」行的 duration）；历史恢复的消息无此值则不显示
      msg.doneTime = Date.now()
      // 生成完成：编排卡片收起为一行（用户未手动干预时），避免答案出来后还占着版面
      if (msg.saOpen && !msg.saTouched) msg.saOpen = false
      msg.sources = sources
      if (msg.retrieved && Array.isArray(sources)) msg.retrieved.refs = sources.length
      msg.related = related
      msg.messageId = messageId
      msg.degradations = degradations
      // 记录仍指向本轮才清（防止误删同会话新一轮的记录）
      if (chatStreams.get(sid) === st) chatStreams.delete(sid)
      // 收尾同样走 liveScroll：跟随中的会话落到最新，用户已上翻看历史的会话原地不动——
      // 答案写完不该把读者的位置抢走
      liveScroll()
      if (isFirstMessage) loadSessions()
      // 重新生成：把刚完成的这一版追加进版本序列并切到它（气泡底部出现 ‹ n/N › 切换器）。
      // 自动重试（prev 非空）不追加：那是同一版本的重试，不是新版本——否则失败重试一次就多出一版
      if (!prev && Array.isArray(msg.versions) && msg.versions.length) {
        msg.versions.push(snapshotVersion(msg))
        msg.vIndex = msg.versions.length - 1
      }
    },
    onWarn: w => { msg.warnMsg = w; liveScroll() },
    onError: (e, kind) => {
      // 自动重试收紧到「连接压根没建起来」这一种：原条件 `!gotToken` 不区分网络错误与
      // 服务端 5xx/限流 —— 移动端网络抖动频繁，用户会看到「消息发出去，卡 2.5 秒，
      // 自己又跑了一遍」，而服务端其实已受理（可能已扣费）。判定用 navigator.onLine
      // 优先（移动端可靠维护），再退回 fetch 的典型网络错误特征。
      const netDown = (typeof navigator !== 'undefined' && navigator.onLine === false) ||
        /Failed to fetch|NetworkError|ERR_INTERNET|ERR_NETWORK|network ?error/i.test(String(e))
      if (autoRetry > 0 && !gotToken && netDown) {
        msg.retrying = true
        liveScroll()
        setTimeout(() => {
          // 记录仍是本轮且气泡还在流式态才重试；用户已停止/记录已被清理则直接收尾
          if (chatStreams.get(sid) === st && msg.loading) {
            streamAnswer(question, imgs, msg, false, 0, deepThink, attachments, skills, mentions, { sid, agentId, model }, historyRefs)
          } else {
            msg.retrying = false
            if (chatStreams.get(sid) === st) chatStreams.delete(sid)
          }
        }, 2500)
        return
      }
      // 错误不再整体替换正文：flushNow 保留已流出的半程内容与时间线，挂独立错误卡
      // （分类文案 + 重新生成 + 异常详情折叠，对齐主流产品的失败态；此前「😅+裸异常」写进气泡
      //  会冲掉半程内容，长回答生成到 90% 失败时全部丢失）
      flushNow()
      msg.loading = false
      msg.retrying = false
      msg.failed = true
      msg.errorCard = { message: String(e), kind: kind || '', at: Date.now() }
      if (chatStreams.get(sid) === st) chatStreams.delete(sid)
      // 中断类（断线/切后台冻结/连接被掐断）不弹 toast：这类几乎都是移动网络状态问题，
      // toast 会盖住用户真正要点的「重新生成」按钮，而错误卡已经把话说清楚了。
      // 业务错误仍弹 —— 那是需要立即知道的服务端异常。
      if (kind !== 'interrupted') message.error(e)
      // 同上：已上翻看历史的会话原地停留（错误卡与「重新生成」在回答末尾，回到底部按钮足够引导）
      liveScroll()
    }
  })
}

/** 错误分类文案（原始异常收进「异常详情」折叠；映射常见失败原因给出可行动提示） */
/** 错误分类文案。kind 由 api.js 给出（'interrupted'=断线/超时/连接被掐断），优先于按文本猜测：
 *  中断类在后端已把半程回答按截断态落库，文案要告诉用户「内容没丢」并说明重新生成会重新计费。 */
const errorBrief = (raw, kind) => {
  const s = String(raw || '')
  if (kind === 'interrupted') {
    if (/timeout|timed?\s*out|长时间未收到响应/i.test(s)) return '响应超时：连接已中断'
    if (/提前关闭/.test(s)) return '连接已断开：回答未正常结束'
    return '连接已中断：回答未正常结束'
  }
  if (/AbortError|aborted?/i.test(s)) return '生成已停止'
  if (/timeout|timed?\s*out/i.test(s)) return '请求超时：模型服务响应过慢或网络不稳定，可重试'
  if (/Failed to fetch|NetworkError|network/i.test(s)) return '网络连接失败：请检查网络或代理设置'
  if (/401|Unauthorized/i.test(s)) return '鉴权失败：登录已过期，请重新登录'
  if (/429|rate\s*limit/i.test(s)) return '请求过于频繁：模型服务限流，稍后重试'
  if (/5\d{2}|Bad Gateway|Service Unavailable/i.test(s)) return '模型服务异常：稍后重试，或到设置页检查供应商状态'
  return '生成失败，可重试或更换模型'
}

// ==================== 重新生成多版本（同一问题的多次回答可来回切换） ====================
// 语义：v1 是首次回答；每次「重新生成」把新完成的回答追加为新版本，气泡底部出现 ‹ 1/2 › 切换器。
// 版本只存在于当前会话内存里：历史接口按「一题一答」返回，重新生成时后端会把旧回答软删（见
// replaceMessageId），所以刷新后看到的是最后一版——想保留哪一版就切到哪一版再刷新是不成立的，
// 这一点在切换器上有提示，不做假承诺。
const snapshotVersion = m => ({
  content: m.content || '',
  sources: m.sources || [],
  related: m.related || [],
  thinking: m.thinking || '',
  timeline: m.timeline || [],
  artifacts: m.artifacts || [],
  toolCalls: m.toolCalls || [],
  subagents: m.subagents || [],
  plan: m.plan || null,
  processText: m.processText || '',
  degradations: m.degradations || [],
  retrieved: m.retrieved || null,
  doneTime: m.doneTime || null,
  model: m.model || ''
})
const applyVersion = (m, v) => {
  m.content = v.content
  m.sources = v.sources
  m.related = v.related
  m.thinking = v.thinking
  m.timeline = v.timeline
  m.artifacts = v.artifacts
  m.toolCalls = v.toolCalls
  m.subagents = v.subagents
  m.plan = v.plan
  m.processText = v.processText
  m.degradations = v.degradations
  m.retrieved = v.retrieved
  m.doneTime = v.doneTime
  // 模型随版本走：切回旧版本时分隔记录按那一版当时用的模型比对，不串到最新一轮的模型
  m.model = v.model || ''
}
const switchVersion = (mi, delta) => {
  const m = messages.value[mi]
  if (!m || !Array.isArray(m.versions) || m.versions.length < 2) return
  const cur = m.vIndex || 0
  const ni = Math.max(0, Math.min(m.versions.length - 1, cur + delta))
  if (ni === cur) return
  m.vIndex = ni
  applyVersion(m, m.versions[ni])
}
// 切换器双数据源的边界判定/文案：内存 versions（本会话重新生成）优先，其次持久 variant（历史恢复/编辑重发）
const verLocal = m => Array.isArray(m.versions) && m.versions.length > 1
const canSwitchPrev = m => verLocal(m) ? (m.vIndex || 0) > 0 : (m.variantIndex || 1) > 1
const canSwitchNext = m => verLocal(m)
  ? (m.vIndex || 0) < m.versions.length - 1
  : (m.variantIndex || 1) < (m.variantCount || 1)
const verLabel = m => verLocal(m)
  ? `${(m.vIndex || 0) + 1}/${m.versions.length}`
  : `${m.variantIndex || 1}/${m.variantCount || 1}`

const regenerate = mi => {
  if (loading.value) return
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      const imgs = (messages.value[i].images || []).filter(u => u.startsWith('data:'))
      const deep = !!messages.value[i].deepThink
      // 附件/技能随内存消息重发（历史回放的消息无 attachData，则不带附件重试）
      const atts = Array.isArray(messages.value[i].attachData) ? messages.value[i].attachData : []
      const skills = Array.isArray(messages.value[i].skills) ? messages.value[i].skills : []
      // @ 引用随内存消息重发（历史回放无该数据则不重发；引用只对当轮检索生效）
      const mentions = Array.isArray(messages.value[i].mentions) ? messages.value[i].mentions : []
      // # 历史引用同口径：重新生成保留原引用（该轮答案本就是基于这些历史得出的）
      const historyRefs = Array.isArray(messages.value[i].historyRefs) ? messages.value[i].historyRefs : []
      // 多版本：首次重新生成前把当前回答快照为 v1（后续版本在 done 时追加）。已有 versions 说明
      // 这条消息本就是多版本序列（当前展示的必然在序列里），无需再快照
      const ai = messages.value[mi]
      if (!Array.isArray(ai.versions) || !ai.versions.length) {
        ai.versions = [snapshotVersion(ai)]
        ai.vIndex = 0
      }
      // 传消息对象（不是下标）：流式状态已按会话拆分，replace 走对象身份
      streamAnswer(messages.value[i].content, imgs, ai, false, 1, deep, atts, skills, mentions, null, historyRefs)
      return
    }
  }
  message.warning('未找到对应的问题')
}

const copyAnswer = async mi => {
  const m = messages.value[mi]
  if (!m || !m.content) { message.warning('该回答无可复制内容'); return }
  const txt = m.content.trim()
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(txt); message.success('已复制到剪贴板') }
    catch (e) { fallbackCopyText(txt) }
  } else fallbackCopyText(txt)
}
/** 复制用户的问题（长会话里把问题转走/复问用；此前只有回答能复制） */
const copyUserMessage = m => {
  const txt = (m?.content || '').trim()
  if (!txt) { message.warning('该消息无可复制内容'); return }
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(txt).then(() => message.success('已复制')).catch(() => fallbackCopyText(txt))
  } else fallbackCopyText(txt)
}
const fallbackCopyText = txt => {
  try {    const ta = document.createElement('textarea')
    ta.value = txt
    ta.setAttribute('readonly', '')
    ta.style.position = 'absolute'
    ta.style.left = '-9999px'
    document.body.appendChild(ta)
    ta.focus(); ta.select(); ta.setSelectionRange(0, txt.length)
    const ok = document.execCommand('copy')
    ta.remove()
    if (ok) message.success('已复制到剪贴板')
    else message.error('复制失败，请手动复制')
  } catch (err) { message.error('复制失败，请手动复制') }
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
// 就地编辑：点「编辑」在该气泡下展开编辑框，确认后从这一轮整段重新生成——
// 被编辑消息及其后的旧分支由后端软删留档（variant_group/variant_tail），可随时切回。
// 编辑重发与直接回填输入框（旧行为，发起新一轮）是两个入口：这里走 editMessageId 分支链路。
const editingIdx = ref(null)
const editingText = ref('')
const editingBusy = ref(false)
const editingRef = ref(null)
// v-for 内的模板 ref 走函数式（ref 属性在循环里会聚集成数组，取值麻烦）
const setEditingRef = el => { editingRef.value = el }

const editMessage = mi => {
  if (loading.value || editingBusy.value) return
  const m = messages.value[mi]
  if (!m || m.role !== 'user') return
  if (!m.messageId) { message.warning('该消息尚未落库（流式回答中），请稍后再编辑'); return }
  // 已有编辑框在别处打开：切换目标
  editingIdx.value = mi
  editingText.value = m.content
  nextTick(() => editingRef.value?.focus())
}
const cancelEdit = () => { editingIdx.value = null; editingText.value = '' }

const confirmEdit = () => {
  const mi = editingIdx.value
  if (mi == null || editingBusy.value) return
  const old = messages.value[mi]
  const txt = editingText.value.trim()
  if (!old || old.role !== 'user') { cancelEdit(); return }
  if (!txt) { message.warning('内容不能为空'); return }
  if (txt === old.content) { cancelEdit(); return }  // 内容没变=原样重发，没有分支语义，直接收起
  if (loading.value) { message.warning('当前正在回答，请先停止或稍候'); return }
  const editMessageId = old.messageId
  if (!editMessageId) { message.warning('该消息尚未落库，暂不能编辑重发'); return }
  cancelEdit()
  // 本地视图截断到该轮之前：旧分支整体交给后端软删留档，本地不再渲染（切回走分支切换接口+重拉历史）
  const imgs = (old.images || []).filter(u => u.startsWith('data:'))
  const atts = Array.isArray(old.attachData) ? old.attachData : []
  const skills = Array.isArray(old.skills) ? old.skills : []
  const mentions = Array.isArray(old.mentions) ? old.mentions : []
  const historyRefs = Array.isArray(old.historyRefs) ? old.historyRefs : []
  const deep = !!old.deepThink
  messages.value = messages.value.slice(0, mi)
  // 新分支的用户消息：继承原消息的图片/附件/引用与档位；版本计数 +1（旧值缺省=首次编辑即 2 版）
  const verN = (old.variantCount || 1) + 1
  const nu = reactive({
    role: 'user', content: txt,
    images: Array.isArray(old.images) ? [...old.images] : [],
    attachments: Array.isArray(old.attachments) ? old.attachments : [],
    attachData: atts, skills, mentions, historyRefs, deepThink: deep,
    time: Date.now(), messageId: null,
    variantCount: verN, variantIndex: verN
  })
  messages.value.push(nu)
  streamAnswer(txt, imgs, null, false, 1, deep, atts, skills, mentions, null, historyRefs, editMessageId, nu)
}

// ==================== 分支版本切换（持久化多版本：编辑重发 + 重新生成刷新后仍可切） ====================
// 两种数据源统一到一个切换器：
// - m.versions（本会话内存里的重新生成版本）：纯本地切换，零请求；
// - m.variantCount/variantIndex（后端持久分支，编辑重发或刷新后的历史恢复）：调切换接口让后端
//   「当前分支软删留档、目标分支按快照恢复」，随后重拉会话历史刷新整个视图（分支尾部整段变化）。
const variantSwitching = ref(false)
const switchBranch = async (mi, delta) => {
  const m = messages.value[mi]
  if (!m || variantSwitching.value) return
  // 本轮正在回答时不允许切分支：流式那轮的父消息可能正被切走的分支持有，落库会错挂
  if (loading.value) { message.warning('当前正在回答，请先停止或稍候'); return }
  // 本地内存版本优先（重新生成的即时多版本）
  if (Array.isArray(m.versions) && m.versions.length > 1) { switchVersion(mi, delta); return }
  if (!m.messageId || !m.variantCount) return
  variantSwitching.value = true
  try {
    const r = await switchMessageVariant(m.messageId, delta)
    if (r.success !== false) {
      // 重拉历史：切回的分支整段尾部都在后端恢复，本地 splice 造不出版本视图
      await switchSession(currentSessionId.value)
    } else {
      message.warning(r.msg || '切换失败')
    }
  } catch (e) {
    message.warning(e?.message || '切换失败')
  } finally {
    variantSwitching.value = false
  }
}

const stop = () => {
  // 只停当前会话的流；其它会话的后台流不受影响
  const st = chatStreams.get(currentSessionId.value)
  if (st) {
    chatStreams.delete(currentSessionId.value)
    st.abort.abort()  // abort → api.js 按正常结束回调 onDone（气泡收尾为「已停止生成」）
  }
}

// 空态示例问题（通用四类，不绑定具体知识库——点击即发，检索链路自动走当前智能体/全局库）
const SAMPLE_QUESTIONS = [
  { icon: '🔍', label: '知识检索', text: '帮我查一下系统操作手册里的登录步骤' },
  { icon: '📝', label: '总结提炼', text: '把这篇文档的核心要点总结成 5 条' },
  { icon: '✍️', label: '辅助写作', text: '帮我起草一份项目周报的框架' },
  { icon: '📊', label: '对比分析', text: '对比一下方案 A 和方案 B 的优劣' }
]

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

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('paste', onGlobalPaste)
  window.addEventListener('resize', onWindowResize)
  window.addEventListener('orientationchange', onOrientationChange)
  // 触屏深度思考 sheet 的「点外部关闭」。用 pointerdown 而非 click：touch 设备上
  // click 在 touchend 之后才派发，面板内的 @mousedown.stop.prevent 拦不住它
  document.addEventListener('pointerdown', onDocPointerDown)
  await loadSessions()
  refreshSetupGuide()  // 欢迎区引导卡状态（TTL 去重：AppLayout 挂载时已 force 过，通常直接复用）
  loadAgents()       // 智能体下拉候选（不阻塞首屏）
  loadSkills()       // 技能菜单候选（输入框「+」菜单，不阻塞首屏）
  const sid = route.query.sid
  if (sid) {
    await switchSession(sid)
  } else if (sessionStore.newChatTick > sessionStore.newChatSeen) {
    // 其它页面点过「新建对话」后跳转过来：消费该信号，直接进空会话
    sessionStore.newChatSeen = sessionStore.newChatTick
    await createNewSession()
  } else {
    await autoPick()
  }
  focusInput()
  // 检索调试入口是管理员调参（chat.retrievalDebugEnabled，配置里标 debug）：只有管理员才拉 /config
  // ——/config 是管理端点，普通用户调它会 403，触发全局 403 提示（角色未授权），故只对管理员读
  if (isAdminSync()) {
    getConfig().then(r => {
      if (!r.success) return
      debugEntryVisible.value = r.data?.chat?.retrievalDebugEnabled?.value === 'true'
    }).catch(() => {})
  }
  // 同一个开关的「显示」语义走公开端点：归属徽标/派遣提示/引用分值的显隐对所有人生效（含普通用户）
  getRuntimeConfig().then(r => {
    if (!r.success) return
    debugDisplayVisible.value = r.data?.ui?.debugEntry === true
  }).catch(() => {})
  getUserPreference().then(r => {
    userDefaultModel.value = (r && r.data && r.data.defaultModel) || ''
  }).catch(() => {})
  loadModelIndex().then(idx => { modelIndex.value = idx || {} }).catch(() => {})
})
</script>

<style scoped>
.chat2 { display: flex; height: 100%; min-width: 0; background: var(--app-panel); position: relative; }
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
/* 流式打字光标：贴在正文末尾（.md::after），长回答中段能看出"在出字"而不是卡住了 */
.md.streaming::after {
  content: '▍';
  color: var(--app-accent);
  animation: caret-blink 1s steps(2, start) infinite;
}
@keyframes caret-blink { 50% { opacity: 0; } }
/* 头部动作区：右对齐一组；分享/查找用全局 app-icon-btn（无框，悬停显 accent-weak 底色） */
.head-actions { margin-left: auto; display: flex; align-items: center; gap: 2px; }
.head-actions .app-icon-btn { font-size: 15px; }
.head-quiet-btn {
  border: none; background: transparent; cursor: pointer; padding: 4px 8px;
  border-radius: 6px; font-size: 12px; color: var(--app-text3);
  transition: color .15s, background .15s;
}
.head-quiet-btn:hover { color: var(--app-accent); background: var(--app-accent-weak); }

.messages { flex: 1; overflow-y: auto; padding: 20px 32px 8px; }
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
.ws-ic { font-size: 16px; line-height: 1.3; flex: none; }
.ws-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ws-label { font-size: 11px; color: var(--app-text3); }
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
.msg-block { position: relative; display: flex; flex-direction: column; min-width: 0; max-width: min(94%, 860px); width: 100%; }
.msg-block.user { align-items: flex-end; }
.msg-block.ai { align-items: flex-start; }
.bubble { width: 100%; line-height: 1.65; }
.bubble.user { background: var(--app-panel-2); border-radius: 12px; padding: 9px 14px; width: fit-content; max-width: 100%; }
.bubble.user :deep(.md > p) { margin: 0; }
.bubble.ai { background: transparent; padding: 0; }

.msg-imgs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.msg-img { width: 88px; height: 88px; object-fit: cover; border-radius: 8px; border: 1px solid var(--app-border); cursor: zoom-in; }
.pending-imgs { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 auto 8px; max-width: 860px; }
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
.src-origin-link { display: inline-block; margin-top: 12px; color: var(--app-accent); font-size: 13px; }
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
.dispatch-fallback { color: var(--app-text3); }
.dispatch-desc { color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 420px; }

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
.retry-row { margin-top: 8px; }
.msg-edit-row {
  position: absolute; top: calc(100% + 2px); left: 0; right: 0; height: 24px; z-index: 1;
  display: flex; align-items: center; justify-content: flex-end; gap: 6px;
  opacity: 0; transition: opacity .15s;
}
.msg-block:hover .msg-edit-row { opacity: 1; }
/* 就地编辑框：悬浮在用户气泡下方（与 msg-edit-row 同一挂点层），编辑期间覆盖式展示 */
.msg-inline-edit {
  position: absolute; top: calc(100% + 26px); left: 0; right: 0; z-index: 3;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  padding: 8px; box-shadow: 0 6px 20px rgba(0, 0, 0, .1);
  display: flex; flex-direction: column; gap: 8px;
}
.msg-inline-edit-input {
  width: 100%; resize: vertical; min-height: 64px; max-height: 240px;
  border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 10px;
  background: var(--app-panel-2); color: var(--app-text); font: inherit; line-height: 1.6; outline: none;
}
.msg-inline-edit-input:focus { border-color: var(--app-accent); }
.msg-inline-edit-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.msg-inline-edit-hint { margin-right: auto; font-size: 11px; color: var(--app-text3); }
.msg-time-inline { font-size: 11px; color: var(--app-text3); margin-left: 8px; white-space: nowrap; user-select: none; }
.msg-tokens { font-size: 11px; color: var(--app-text3); white-space: nowrap; cursor: default; }
.jump-latest {
  position: sticky; bottom: 12px; z-index: 5; width: 28px; height: 28px; line-height: 26px; text-align: center;
  margin: 0 auto 4px; padding: 0; background: var(--app-panel); color: var(--app-text); font-size: 14px;
  border: 1px solid var(--app-border); border-radius: 999px; cursor: pointer; user-select: none;
  box-shadow: var(--app-shadow-sm); transition: border-color .15s, box-shadow .15s;
}
.jump-latest:hover { border-color: var(--app-border-strong); box-shadow: var(--app-shadow); }

.input { position: relative; padding: 10px 32px 14px; flex: none; }
.drop-overlay {
  position: absolute; inset: 6px 32px; z-index: 6; pointer-events: none;
  background: rgba(46,107,230,.06); border: 2px dashed var(--app-accent); border-radius: 14px;
  display: flex; align-items: center; justify-content: center;
  color: var(--app-accent); font-size: 14px; font-weight: 500;
}
.input-box {
  position: relative; max-width: 860px; margin: 0 auto;
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
.at-chips { display: flex; flex-wrap: wrap; gap: 6px; padding: 2px 4px 6px; max-width: 860px; margin: 0 auto; }
.at-chip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 260px;
  padding: 2px 6px; border-radius: 6px; font-size: 12px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.at-chip-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.at-chip-del { flex: none; cursor: pointer; font-size: 13px; line-height: 1; opacity: .65; padding: 0 1px; }
.at-chip-del:hover { opacity: 1; color: var(--app-danger); }
/* @ 引用 chip：知识库/文档用图标与底色区隔（技能 chip 沿用 .skill-chip 原样） */
.at-chips-note { font-size: 11px; color: var(--app-text3); align-self: center; margin-left: 2px; }
.at-chip-ic { font-size: 12px; flex: none; }
.mention-chip.mention-doc { background: var(--app-warn-weak); color: var(--app-warn-text); }
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
.mention-tab.on { background: var(--app-accent-weak); color: var(--app-accent); }
.mention-list { overflow-y: auto; padding: 6px; max-height: 264px; }
.mention-item { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 8px; cursor: pointer; }
.mention-item:hover { background: var(--app-panel-2); }
.mention-item.on { background: var(--app-accent-weak); }
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
.agent-tag-ic { font-size: 12px; opacity: .8; }
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

/* 输入框「+」菜单：附件 + 技能（视觉沿用智能体菜单的行式布局） */
.add-menu {
  min-width: 340px; max-width: 420px; background: var(--app-panel);
  border: 1px solid var(--app-border); border-radius: 14px; padding: 6px;
  box-shadow: 0 10px 32px -8px rgba(16, 24, 40, .18);
}
.add-menu-head { padding: 8px 10px 6px; font-size: 12px; font-weight: 500; color: var(--app-text); }
.add-menu-sec { padding: 8px 10px 2px; font-size: 11px; color: var(--app-text3); }
.add-menu-list { max-height: 260px; overflow-y: auto; }
.add-mi { display: flex; align-items: flex-start; gap: 10px; padding: 8px 10px; border-radius: 10px; cursor: pointer; }
.add-mi:hover { background: var(--app-panel-2); }
.add-mi.active { background: var(--app-accent-weak); }
.add-mi-ava {
  flex: none; width: 26px; height: 26px; border-radius: 8px; margin-top: 1px;
  display: inline-flex; align-items: center; justify-content: center; font-size: 13px;
  background: var(--app-panel-2); color: var(--app-text3);
}
.add-mi.active .add-mi-ava { background: var(--app-accent-weak); color: var(--app-accent); }
.skill-ava { font-size: 12px; font-weight: 600; }
.add-mi-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.add-mi-name {
  font-size: 13px; line-height: 20px; color: var(--app-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.add-mi.active .add-mi-name { color: var(--app-accent); font-weight: 500; }
.add-mi-desc {
  font-size: 11px; color: var(--app-text3); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.add-mi-empty { padding: 12px 10px; font-size: 12px; color: var(--app-text3); text-align: center; }
.add-menu-tip { padding: 7px 10px 4px; border-top: 1px solid var(--app-border); margin-top: 4px; font-size: 11px; color: var(--app-text3); }

/* 待发送附件条 + 技能选中标签 */
.pending-files { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 auto 8px; max-width: 860px; }
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

/* 状态栏：当前智能体卡片 */
.rp-agent { display: flex; align-items: center; gap: 6px; }
.rp-agent-ic { font-size: 13px; color: var(--app-accent); flex: none; }
.rp-agent-row { margin-top: 6px; align-items: baseline; flex-wrap: wrap; }
.rp-val {
  display: inline-flex; align-items: center; gap: 5px; min-width: 0;
  max-width: 100%; flex-wrap: wrap;
  font-size: 12px; color: var(--app-text);
}
.rp-val-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rp-tag {
  font-size: 10px; line-height: 1; padding: 2px 5px; border-radius: 4px; flex: none;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.send-btn {
  width: 30px; height: 30px; border-radius: 50%; border: none;
  background: var(--app-accent); color: #fff; font-size: 15px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; transition: background .2s;
}
.send-btn:hover:not(:disabled) { background: var(--app-accent-hover); }
.send-btn:disabled { background: var(--app-accent-disabled); cursor: not-allowed; }
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

/* 右栏统计口径切换 + 上下文占用条 */
.rp-scope-row { display: flex; justify-content: flex-end; }
.rp-scope { display: inline-flex; border: 1px solid var(--app-border); border-radius: 7px; overflow: hidden; background: var(--app-panel); }
.rp-scope span { padding: 3px 10px; font-size: 11px; color: var(--app-text3); cursor: pointer; user-select: none; }
.rp-scope span.on { background: var(--app-accent-weak); color: var(--app-accent); font-weight: 500; }
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
.md .ref-sup.ref-hl { background: var(--app-accent-weak); border-radius: 3px; }
.md .ref-sup.ref-flash { animation: ref-flash 1.5s ease; }
@keyframes ref-flash { 0% { background: var(--app-accent-weak); } 100% { background: transparent; } }
@media (prefers-reduced-motion: reduce) { .md .ref-sup.ref-flash { animation: none; } }

/* 右栏运行控制（停止 / 重试本轮） */
.rp-ctrl { display: flex; }
.rp-ctrl-btn {
  flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 5px;
  padding: 6px 0; border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-panel); color: var(--app-text2); font-size: 12px; cursor: pointer;
}
.rp-ctrl-btn:hover { color: var(--app-accent); border-color: var(--app-accent); }
.rp-ctrl-btn.is-stop { color: var(--app-danger); border-color: var(--app-danger-border); }
.rp-ctrl-btn.is-stop:hover { color: var(--app-danger); border-color: var(--app-danger); background: var(--app-danger-weak); }

/* 来源弹窗内容 */
.src-content { max-height: 55vh; overflow-y: auto; line-height: 1.7; font-size: 14px; padding-right: 6px; }

/* 检索调试面板 */
.dbg-item { padding: 6px 8px; margin-bottom: 6px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-panel-2); }
.dbg-terms { padding: 8px 10px; margin-bottom: 10px; border: 1px solid var(--app-info-border); border-radius: 6px; background: var(--app-info-weak); }
.dbg-terms-label { font-size: 12px; color: var(--app-text3); margin-right: 6px; }
.dbg-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.dbg-title { font-weight: 500; font-size: 13px; }
.dbg-snippet { margin-top: 3px; font-size: 12px; color: var(--app-text3); word-break: break-all; }

/* 灯箱 */
.lightbox { position: fixed; inset: 0; background: rgba(0,0,0,.78); display: flex; align-items: center; justify-content: center; z-index: 2000; cursor: zoom-out; overflow: hidden; }
.lightbox-img { max-width: 90vw; max-height: 90vh; border-radius: 4px; cursor: grab; user-select: none; transition: transform .12s ease; }
.lightbox-close { position: fixed; top: 16px; right: 24px; font-size: 36px; color: #fff; cursor: pointer; line-height: 1; opacity: .85; }
.lightbox-close:hover { opacity: 1; }
.lightbox-count { position: fixed; bottom: 44px; left: 50%; transform: translateX(-50%); color: rgba(255,255,255,.75); font-size: 13px; background: rgba(0,0,0,.45); padding: 2px 12px; border-radius: 12px; }
.lightbox-tip { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); color: rgba(255,255,255,.6); font-size: 12px; user-select: none; }
.lightbox-prev, .lightbox-next {
  position: fixed; top: 50%; transform: translateY(-50%);
  width: 44px; height: 44px; border-radius: 50%; border: 1px solid rgba(255,255,255,.35);
  background: rgba(0,0,0,.4); color: #fff; font-size: 26px; line-height: 1; cursor: pointer;
  display: flex; align-items: center; justify-content: center; z-index: 2001; user-select: none;
}
.lightbox-prev { left: 16px; }
.lightbox-next { right: 16px; }
.lightbox-prev:hover:not(:disabled), .lightbox-next:hover:not(:disabled) { background: rgba(0,0,0,.7); }
.lightbox-prev:disabled, .lightbox-next:disabled { opacity: .25; cursor: not-allowed; }
/* 工具执行审批（人在回路） */
.approval-card { margin-top: 8px; border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 8px; padding: 10px 12px; max-width: 640px; }
.approval-title { font-size: 13px; font-weight: 600; color: var(--app-warn-text); display: flex; align-items: center; gap: 6px; }
.approval-args { margin: 8px 0 0; background: var(--app-panel); border: 1px solid var(--app-warn-border); border-radius: 6px; padding: 8px; font-size: 12px; font-family: "SF Mono", Menlo, monospace; white-space: pre-wrap; word-break: break-all; max-height: 140px; overflow-y: auto; }
.approval-actions { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.approval-hint { font-size: 12px; color: var(--app-text3); }

/* 引用角标悬浮卡（自绘，替代原生 title；Teleport 到 body 故用 fixed 定位） */
.ref-card {
  position: fixed; z-index: 3000; background: var(--app-panel);
  border: 1px solid var(--app-border); border-radius: var(--app-radius);
  box-shadow: var(--app-shadow-lg); padding: 10px 12px;
}
.ref-card-head { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.ref-card-no { color: var(--app-accent); font-size: 12px; font-weight: 600; flex: none; }
.ref-card-file {
  font-size: 12px; color: var(--app-text); font-weight: 500; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ref-card-score { font-size: 11px; color: var(--app-text3); margin-left: auto; flex: none; }
.ref-card-title { font-size: 12px; color: var(--app-text2); margin-bottom: 4px; }
.ref-card-snippet {
  font-size: 12px; color: var(--app-text2); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden;
}
.ref-card-btn {
  margin-top: 8px; padding: 0; border: none; background: transparent;
  color: var(--app-accent); font-size: 12px; cursor: pointer;
}
.ref-card-btn:hover { text-decoration: underline; }

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
     下面 768 块用 padding 简写重置了这一条 —— 窄屏下 FAB 已在窄屏隐藏（见 app.css），
     不需要再让位 */
  .input { padding-right: 56px; }
  /* 拖拽高亮框跟随卡片右缘（与 .input 同侧内边距） */
  .drop-overlay { right: 56px; }
}
@media (max-width: 768px) {
  /* 内边距从 32px 收到 10px，与 .chat-head 的横向节奏一致 */
  .messages { padding: 10px 10px 6px; overscroll-behavior-y: contain; }
  /* padding 用简写：同时重置 1200 块的 padding-right:56px（窄屏 FAB 已隐藏） */
  .input { padding: 8px 10px calc(10px + var(--sab, 0px)); }
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
