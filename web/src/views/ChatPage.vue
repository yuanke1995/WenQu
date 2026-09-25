<template>
  <div class="chat2">
    <!-- 中间：标题栏 + 消息流 + 输入区 -->
    <div class="chat-col">
      <div class="chat-head">
        <span class="chat-title">{{ currentSessionTitle }}</span>
        <span class="head-tip" title="查看免责声明" @click="disclaimerVisible = true">AI 回答可能有误，重要信息请核实</span>
        <button class="app-btn ghost head-panel-btn" @click="togglePanel">{{ panelOpen ? '隐藏状态' : '状态' }}</button>
      </div>

      <div class="messages" ref="box" @click="openPreview" @mouseover="refHover" @scroll="onMessagesScroll">
        <div v-if="messages.length === 0" class="welcome">
          <div class="welcome-mark">渠</div>
          <h2>有什么可以帮你？</h2>
          <p>基于知识库回答，支持图片提问与深度思考</p>
          <div class="welcome-tags">
            <span v-for="(q, i) in tips" :key="i" class="welcome-tag" @click="ask(q)">{{ q }}</span>
          </div>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="row" :class="m.role">
          <div class="msg-block" :class="m.role">
            <div class="bubble" :class="m.role">
              <div v-if="m.role === 'user' && m.images && m.images.length" class="msg-imgs">
                <img v-for="(u, ui) in m.images" :key="ui" :src="resolveImg(u)" class="msg-img"
                     :alt="'上传图片' + (ui + 1)" @click="openPreviewFromMsg(m, ui)" @error="onImgError" />
              </div>
              <div v-if="m.role === 'ai' && m.thinking" class="think-panel" :class="{ open: m.thinkOpen }">
                <div class="think-head" @click="m.thinkOpen = !m.thinkOpen">
                  <down-outlined class="think-arrow" />
                  <span class="think-title">深度思考</span>
                  <a-spin v-if="m.thinkLoading" size="small" style="margin-left:6px" />
                  <span v-else class="think-badge">已完成</span>
                </div>
                <div v-show="m.thinkOpen" class="think-body"><div class="md" v-html="renderMd(m.thinking, [])"></div></div>
              </div>
              <div class="md" :data-msg-index="i" v-html="renderMd(m.content, m.images)"></div>
              <div v-if="m.loading && m.stage && !m.content" class="stage-hint"><loading-outlined /> {{ m.stage }}</div>
              <a-spin v-if="m.loading && m.content" size="small" style="margin-top:4px" />
              <div v-if="m.role === 'ai' && m.toolCalls && m.toolCalls.length" class="tool-status-list">
                <div v-for="(t, ti) in toolCallsView(m.toolCalls)" :key="ti" class="tool-status-item" :title="t.args ? ('入参: ' + t.args) : ''">
                  <loading-outlined v-if="t.status === 'start'" spin class="tool-ic tool-ic-run" />
                  <check-outlined v-else-if="t.status === 'done'" class="tool-ic tool-ic-ok" />
                  <close-circle-outlined v-else class="tool-ic tool-ic-err" />
                  <span class="tool-name">{{ toolLabel(t.name) }}</span>
                  <span v-if="t.elapsedMs > 0" class="tool-dur">{{ toolDuration(t.elapsedMs) }}</span>
                  <span v-if="t.status === 'error'" class="tool-fail">失败</span>
                </div>
              </div>
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
              <div v-if="m.role === 'ai' && (m.retrieved || (m.sources && m.sources.length))" class="retrieval-merged">
                <div class="retrieval-line" @click="m.rtOpen = !m.rtOpen">
                  <template v-if="m.retrieved">搜索 {{ m.retrieved.keywords }} 个关键词，参考 {{ m.retrieved.refs }} 段资料<template v-if="m.tokens && m.tokens.hits != null && m.tokens.hits !== m.retrieved.refs">（{{ m.tokens.hits }} 段填入上下文）</template></template>
                  <template v-else>参考 {{ (m.sources || []).length }} 段资料</template>
                  <down-outlined class="rt-arrow" :class="{ open: m.rtOpen }" />
                </div>
                <div v-if="m.rtOpen" class="retrieval-detail">
                  <div v-if="m.retrieved?.terms?.length" class="rt-terms">检索词：{{ (m.retrieved.terms || []).join('、') }}</div>
                  <div v-if="toolSearchQueries(m).length" class="rt-terms rt-tool-terms">
                    <span class="rt-tool-tag">精确检索</span>{{ toolSearchQueries(m).join('；') }}
                  </div>
                  <div v-for="(s, si) in (m.sources || [])" :key="si" class="rt-ref" title="点击查看原文" @click="openSource(s)">
                    <span class="rt-ref-tag">[{{ s.ref }}]</span>{{ (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + (s.title ? ' §' + s.title : '') }}
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
                    <down-outlined class="rt-arrow" :class="{ open: m.saOpen }" />
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
                <span class="related-label">猜你想问：</span>
                <span v-for="(q, qi) in m.related" :key="qi" class="related-tag" @click="ask(q)">{{ q }}</span>
              </div>
            </div>
            <div v-if="m.role === 'ai' && m.failed && !m.loading" class="retry-row">
              <button class="app-btn ghost" :disabled="loading" @click="regenerate(i)"><reload-outlined /> 重试</button>
            </div>
            <div v-if="m.role === 'ai' && !m.loading && (m.messageId || m.time)" class="fb-row">
              <template v-if="m.messageId">
                <a-tooltip title="复制"><button class="app-icon-btn" @click="copyAnswer(i)"><copy-outlined /></button></a-tooltip>
                <a-tooltip :title="m.fb != null ? '已评价' : '有帮助'"><button class="app-icon-btn" :class="{ 'fb-active': m.fb === 1 }" :disabled="m.fb != null" @click="openFeedback(m, 1)"><like-outlined /></button></a-tooltip>
                <a-tooltip :title="m.fb != null ? '已评价' : '没帮助'"><button class="app-icon-btn" :class="{ 'fb-active': m.fb === 0 }" :disabled="m.fb != null" @click="openFeedback(m, 0)"><dislike-outlined /></button></a-tooltip>
                <a-tooltip title="重新生成"><button class="app-icon-btn" :disabled="loading" @click="regenerate(i)"><reload-outlined /></button></a-tooltip>
                <a-dropdown :trigger="['hover']">
                  <button class="app-icon-btn" title="更多"><more-outlined /></button>
                  <template #overlay>
                    <a-menu @click="({ key }) => onMoreAction(key, i)">
                      <a-menu-item v-if="debugEntryVisible" key="debug"><bug-outlined style="margin-right:8px" />检索调试</a-menu-item>
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
            <div v-if="m.retrying" class="retry-tip"><a-spin size="small" /><span>连接中断，正在自动重试…</span></div>
            <div v-if="m.role === 'user'" class="msg-edit-row">
              <a-tooltip title="编辑此问题重新发送" placement="top">
                <edit-outlined class="app-icon-btn" @click="editMessage(i)" />
              </a-tooltip>
              <span v-if="m.time" class="msg-time-inline">{{ fmtMsgTime(m.time) }}</span>
            </div>
          </div>
        </div>
        <div v-if="!stickToBottom && messages.length" class="jump-latest" @click.stop="scrollForce">↓ 回到底部</div>
      </div>

      <!-- 输入区：大圆角卡片（文本上、工具行下） -->
      <div class="input" @dragenter.prevent="onDragEnter" @dragover.prevent @dragleave.prevent="onDragLeave" @drop.prevent="onDropImages">
        <div v-if="dragOver" class="drop-overlay">松开以添加图片</div>
        <div v-if="pendingImages.length" class="pending-imgs">
          <div v-for="(p, pi) in pendingImages" :key="pi" class="pending-img">
            <img :src="p.dataUrl" alt="待发送图片" @click="previewPendingImage(pi)" />
            <span class="pending-del" @click.stop="removePendingImage(pi)">×</span>
          </div>
        </div>
        <div class="input-box">
          <a-textarea ref="textareaRef" v-model:value="text" placeholder="问点什么？Enter 发送，Shift+Enter 换行"
                      :disabled="loading" :auto-size="{ minRows: 1, maxRows: 6 }" class="input-area"
                      @keydown="onInputKeydown" />
          <div class="input-toolbar">
            <div class="toolbar-left">
              <a-dropdown v-model:open="agentPickerOpen" :trigger="['click']" placement="topLeft">
                <button class="agent-pill" :class="{ on: !!currentAgentId, open: agentPickerOpen }"
                        title="选择智能体：按预设覆盖提示词 / 知识库范围 / 能力（模型在右侧选择）">
                  <robot-outlined class="agent-pill-ic" />
                  <span v-if="currentAgentId" class="agent-pill-dot"></span>
                  <span class="agent-pill-name">{{ currentAgentName }}</span>
                  <down-outlined class="agent-pill-caret" />
                </button>
                <template #overlay>
                  <div class="agent-menu">
                    <div class="agent-menu-head">
                      <span>选择智能体</span>
                      <span class="agent-menu-hint">决定这一轮问答用哪套配置</span>
                    </div>
                    <div class="agent-menu-list">
                      <div v-if="!agentList.length" class="agent-mi" :class="{ active: !currentAgentId }" @click="pickAgent('')">
                        <span class="agent-mi-ava"><robot-outlined /></span>
                        <div class="agent-mi-text">
                          <span class="agent-mi-name">默认（全局配置）</span>
                          <span class="agent-mi-desc">沿用系统设置里的模型、提示词与能力开关</span>
                        </div>
                        <check-outlined v-if="!currentAgentId" class="agent-mi-check" />
                      </div>
                      <div v-for="a in agentList" :key="a.id" class="agent-mi"
                           :class="{ active: currentAgentId === a.id }" @click="pickAgent(a.id)">
                        <span class="agent-mi-ava"><robot-outlined /></span>
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
              <a-tooltip title="上传图片（最多 5 张）">
                <button class="app-icon-btn" @click="pickImages"><picture-outlined /></button>
              </a-tooltip>
              <a-tooltip :title="deepThinkOn ? '深度思考：已开启' : '深度思考：已关闭'">
                <button class="app-icon-btn" :class="{ 'toolbar-btn-on': deepThinkOn }" @click="toggleDeepThink"><bulb-outlined /></button>
              </a-tooltip>
            </div>
            <div class="toolbar-right">
              <ModelSelect v-model="currentOverrideModel" type="chat" pill allow-clear
                           :placeholder="effectiveModelLabel || '选择模型'"
                           :width="190" :disabled="loading" />
            </div>
            <button v-if="loading" class="send-btn stop" title="停止生成" @click="stop"><pause-circle-outlined /></button>
            <button v-else class="send-btn" title="发送" :disabled="!canSend" @click="send"><arrow-up-outlined /></button>
          </div>
        </div>
        <input ref="fileInput" type="file" accept="image/*" multiple style="display:none" @change="onFilesChange" />
      </div>
    </div>

    <!-- 右侧状态栏（可收起）：模型 / 本次检索 / 引用来源 -->
    <aside v-if="panelOpen" class="right-panel">
      <div class="rp-card">
        <div class="rp-label">当前智能体</div>
        <div class="rp-strong rp-agent">
          <robot-outlined class="rp-agent-ic" />
          <span>{{ currentAgentName }}</span>
        </div>
        <div class="rp-row rp-agent-row">
          <span>模型</span>
          <span class="rp-val" :title="effectiveModel">
            <ProviderIcon :icon="effectiveModelIcon" :name="effectiveModelProvider" :size="14" style="margin-right:4px" />
            <span class="rp-val-text">{{ effectiveModelLabel || '—' }}</span>
            <span v-if="modelSourceLabel" class="rp-tag">{{ modelSourceLabel }}</span>
          </span>
        </div>
        <div class="rp-meta">深度思考 {{ deepThinkOn ? '已开启' : '已关闭' }} · 本会话 {{ roundCount }} 轮</div>
      </div>
      <div class="rp-card">
        <div class="rp-label">最近一次检索</div>
        <template v-if="lastRetrieved || lastSources.length">
          <div class="rp-row"><span>检索词 {{ lastRetrieved?.keywords ?? '—' }} 个</span><span class="rp-dim">引用 {{ lastRetrieved?.refs ?? lastSources.length }} 条</span></div>
          <div v-if="lastTokens && lastTokens.hits != null && lastTokens.hits !== (lastRetrieved?.refs ?? lastSources.length)" class="rp-meta">其中 {{ lastTokens.hits }} 条实际填入上下文（其余为模型中途补充/未入上下文）</div>
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
        <div class="rp-row"><span>上下文 {{ fmtTokens(lastTokens.context) }}</span><span class="rp-dim">预算 {{ fmtTokens(lastTokens.budget) }}</span></div>
        <div class="rp-meta">输出 {{ fmtTokens(lastTokens.output) }} · 上下文填入 {{ lastTokens.hits }} 块</div>
      </div>
      <div class="rp-card">
        <div class="rp-label">引用来源<template v-if="groupedSources.length"> · {{ groupedSources.length }} 个文档</template></div>
        <template v-if="groupedSources.length">
          <div v-for="g in groupedSources" :key="g.key" class="rp-group">
            <div class="rp-group-head" @click="g.open = !g.open" :title="g.open ? '收起片段' : '展开片段'">
              <file-text-outlined class="rp-src-ic" />
              <span class="rp-src-name">{{ g.fileName }}</span>
              <span class="rp-count">{{ g.items.length }} 段</span>
              <down-outlined class="rp-arrow" :class="{ open: g.open }" />
            </div>
            <div class="rp-group-body" :class="{ open: g.open }">
              <div v-for="(s, si) in g.items" :key="si" class="rp-src rp-src-sub"
                   title="点击查看原文" @click.stop="openSource(s)">
                <span class="rp-src-name">{{ s.title ? '§ ' + s.title : '片段 ' + (si + 1) }}</span>
              </div>
            </div>
          </div>
        </template>
        <div v-else class="rp-dim">暂无引用</div>
      </div>
    </aside>

    <!-- 引用来源详情弹窗 -->
    <a-modal v-model:open="sourceVisible" :title="sourceTitle" :footer="null" :width="sourceImages.length ? 720 : 560"
             wrap-class-name="source-modal" :keyboard="!previewUrl" :mask-closable="!previewUrl">
      <a-spin v-if="sourceLoading" style="display:block;margin:40px auto" />
      <div v-else class="md src-content" @click="openPreview"
           v-html="renderMd(prepKnowledgeContent(sourceContent || sourceSnippet, sourceImages), sourceImages)"></div>
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
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAdminSync } from '../utils/auth'
import { message } from 'ant-design-vue'
import { LoadingOutlined, DownOutlined, CheckOutlined, CloseCircleOutlined, FileTextOutlined, DownloadOutlined,
         ExclamationCircleOutlined, CopyOutlined, LikeOutlined, DislikeOutlined, ReloadOutlined, MoreOutlined,
         DeleteOutlined, BugOutlined, EditOutlined, PictureOutlined, BulbOutlined, PauseCircleOutlined,
         ArrowUpOutlined, RobotOutlined, SettingOutlined } from '@ant-design/icons-vue'
import { sendQuestion, newSession, getHistory, deleteSessionApi, submitFeedback as apiSubmitFeedback,
         getKnowledgeDetail, debugRetrieval, getSuggested, deleteMessageGroup, getConfig, listAvailableAgents,
         getUserPreference } from '../api'
import { renderMd, resolveImg, onImgError, copyCode, prepKnowledgeContent } from '../utils/markdown'
import { sessionStore, loadSessions } from './store'
import { exportAnswerMd } from './exportMd'
import { fmtTokens } from '../utils/token'
import { loadModelIndex } from '../utils/modelRef'
import ModelSelect from '../components/ModelSelect.vue'
import ProviderIcon from '../components/ProviderIcon.vue'

const route = useRoute()
const router = useRouter()

// 工具名友好展示（与旧版口径一致）
const TOOL_LABELS = {
  searchKnowledge: '知识库精确检索',
  presentArtifact: '生成文件产物',
  calculate: '算术计算',
  currentDateTime: '获取当前时间',
  daysBetween: '计算日期差'
}
// ⚠️ 与 McpClientService 的 clientInfo name 对应：wen-qu → w_q_（改名时需同步）
const MCP_CLIENT_PREFIX = 'w_q_'
const toolLabel = n => TOOL_LABELS[n] || (n.startsWith(MCP_CLIENT_PREFIX) ? n.slice(MCP_CLIENT_PREFIX.length) : n)
const toolCallsView = list => {
  if (!Array.isArray(list)) return []
  return list.filter(t => !(t.status === 'start' && list.some(x => x !== t && x.name === t.name && x.status !== 'start')))
}
const toolDuration = ms => (ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's')
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
const deepThinkOn = ref(localStorage.getItem('ai_deep_think') === '1')
const toggleDeepThink = () => {
  if (loading.value) return
  deepThinkOn.value = !deepThinkOn.value
  localStorage.setItem('ai_deep_think', deepThinkOn.value ? '1' : '0')
}
const canSend = computed(() => !!(text.value.trim() || pendingImages.value.length))

// ==================== 智能体（4.1）：对话页下拉切换，按会话记忆 ====================
const agentList = ref([])                       // 全部智能体
const agentMap = ref({})                        // 会话ID → 选中的智能体ID（按会话记忆）
const defaultAgentId = ref('')                  // 默认智能体（isDefault），无则空=全局配置
const currentAgentId = computed({
  // 空值一律回落到默认助手：有了具名助手后，界面不再提供「不使用任何助手」的选项
  get: () => agentMap.value[currentSessionId.value] || defaultAgentId.value || '',
  set: v => { agentMap.value = { ...agentMap.value, [currentSessionId.value]: v || '' } }
})
const isAdmin = ref(isAdminSync())
const agentPickerOpen = ref(false)
/** 当前生效的智能体名（空 = 走全局配置） */
const currentAgentName = computed(() => {
  const a = agentList.value.find(x => x.id === currentAgentId.value)
  return a ? a.name : '默认（全局配置）'
})
/** 选中智能体：写入当前会话记忆并给出即时反馈 */
const pickAgent = id => {
  currentAgentId.value = id || ''
  agentPickerOpen.value = false
  const a = agentList.value.find(x => x.id === id)
  if (a) message.success(`已切换为「${a.name}」`)
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
    // 当前会话尚未选择时，落到默认智能体
    if (!(currentSessionId.value in agentMap.value)) {
      agentMap.value = { ...agentMap.value, [currentSessionId.value]: defaultAgentId.value }
    }
  } catch (e) { /* 接口不可用时静默：选择器回退为「默认（全局配置）」 */ }
}

const loading = ref(false)
const currentSessionId = ref(null)
const messages = ref([])
const box = ref(null)
const stickToBottom = ref(true)
const AUTO_SCROLL_MARGIN = 80
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
const abortController = ref(null)

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
  // 运行中默认展开（要看到实时进度），全部完成后默认收起（信息价值下降，不占版面）；
  // 用户手动点过则尊重其选择（saTouched），不再自动改变
  if (m.saOpen === undefined) m.saOpen = running
  return { branches, done, total, running, title, routeNote }
}

/** 手动展开/收起编排卡片（标记 saTouched，避免生成完成后被自动收起打断阅读） */
function toggleSubagents (m) {
  m.saOpen = !m.saOpen
  m.saTouched = true
}

// 右侧状态栏：默认展开（持久化），数据全部来自已有消息/配置，不造数
const panelOpen = ref(localStorage.getItem('app_panel') !== '0')
const togglePanel = () => {
  panelOpen.value = !panelOpen.value
  localStorage.setItem('app_panel', panelOpen.value ? '1' : '0')
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
const debugEntryVisible = ref(false)
const lastAi = computed(() => [...messages.value].reverse().find(m => m.role === 'ai' && !m.loading && (m.content || m.sources?.length)))
const lastRetrieved = computed(() => lastAi.value?.retrieved || null)
const lastSources = computed(() => lastAi.value?.sources || [])
// 引用来源按文档分组：先看到"引用了哪几个文档、各几段"，再按需展开看具体片段
// （平铺 N 行时同一文档的片段会重复出现文件名，反而看不出引用了几个来源）
const groupedSources = computed(() => {
  const groups = []
  const byKey = new Map()
  for (const s of lastSources.value) {
    const key = s.docId || ('__manual_' + (s.fileName || 'x'))
    let g = byKey.get(key)
    if (!g) {
      g = {
        key,
        fileName: s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识'),
        items: [],
        open: true
      }
      byKey.set(key, g)
      groups.push(g)
    }
    g.items.push(s)
  }
  return groups
})
// 本次用量（Token 消耗可视化，1.9）：来自 done 事件的 tokens（上下文实际/预算/块数 + 输出估算）
const lastTokens = computed(() => lastAi.value?.tokens || null)

// 推荐问题（DB 配置，失败回退内置默认）
const FALLBACK_TIPS = ['系统有哪些功能？', '如何创建一个新表单？', '字段验证怎么设置？', '什么是填报周期？']
const tips = ref(FALLBACK_TIPS)

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
const sourceVisible = ref(false)
const sourceTitle = ref('')
const sourceSnippet = ref('')
const sourceImages = ref([])
const sourceContent = ref('')
const sourceLoading = ref(false)
const openSource = async s => {
  if (!s) return
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

// 引用角标悬浮提示：悬停时从 sources 取标题/片段写入原生 title（含精确检索工具来源，数据 done 后可用；
// 原生 title 零依赖，点击角标仍走引用弹窗看完整原文）
const refHover = e => {
  const t = e.target
  if (!t || !t.classList || !t.classList.contains('ref-sup') || t.dataset.tipSet) return
  const mdEl = t.closest('.md')
  const msgIdx = mdEl ? Number(mdEl.dataset.msgIndex) : -1
  const src = messages.value[msgIdx]?.sources?.[Number(t.dataset.ref) - 1]
  t.title = src
    ? `[${t.dataset.ref}] ${(src.fileName || (src.docId ? '来源文档不可用' : '手动补充的知识'))}${src.title ? ' §' + src.title : ''}\n${src.snippet || '（无原文片段）'}`
    : `[${t.dataset.ref}] 来源信息加载中`
  t.dataset.tipSet = '1'
}

// 消息内容点击：代码复制 / 引用角标 → 来源弹窗 / 图片 → 灯箱（事件委托）
const openPreview = e => {
  const t = e.target
  const copyBtn = t && t.closest ? t.closest('.code-copy') : null
  if (copyBtn) { copyCode(copyBtn); return }
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

// 全局快捷键：ESC 停止生成 / 清空输入；粘贴发图
const onGlobalKeydown = e => {
  if (e.isComposing || e.keyCode === 229) return
  if (previewUrl.value && ['Escape', 'ArrowLeft', 'ArrowRight'].includes(e.key)) return
  if (e.key === 'Escape') {
    if (loading.value) { stop(); return }
    if (document.activeElement === textareaRef.value) {
      if (text.value) text.value = ''
      else textareaRef.value?.blur()
    }
  }
}
const onGlobalPaste = e => onPasteImages(e)
onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('paste', onGlobalPaste)
})

// ==================== 会话 ====================
const switchSession = async sid => {
  if (loading.value) return
  currentSessionId.value = sid
  // 同步 URL query：侧边栏高亮与刷新恢复都依赖 sid 在地址上
  router.replace({ path: '/chat', query: { sid } }).catch(() => {})
  try {
    const r = await getHistory(sid)
    if (r.success && Array.isArray(r.data)) {
      messages.value = r.data
        .filter(m => m && (m.content || (Array.isArray(m.images) && m.images.length)))
        .map(m => ({
          role: m.role === 'user' ? 'user' : 'ai',
          content: String(m.content || ''),
          messageId: m.messageId || m.id || null,
          fb: (m.fb === 0 || m.fb === 1) ? m.fb : null,
          images: Array.isArray(m.images) ? m.images : [],
          sources: Array.isArray(m.sources) ? m.sources : [],
          related: [],
          thinking: m.thinking || '',
          thinkOpen: false,
          time: m.createTime ? new Date(m.createTime).getTime() : null,
          artifacts: Array.isArray(m.artifacts) ? m.artifacts : [],
          toolCalls: Array.isArray(m.toolCalls) ? m.toolCalls : [],
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
          })()
        }))
      scrollForce()
    } else {
      messages.value = []
    }
  } catch (e) {
    messages.value = []
  }
}

const creatingSession = ref(false)
const createNewSession = async () => {
  if (creatingSession.value) return
  const emptySid = sessionStore.list.find(s => (s.messageCount ?? 0) === 0)?.id
  if (emptySid) {
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
      currentSessionId.value = r.data.sessionId
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
watch(() => sessionStore.newChatTick, async tick => {
  sessionStore.newChatSeen = tick
  if (route.path === '/chat' && !loading.value) await createNewSession()
})
// 当前会话被删除 → 自动落到最近会话或新建
watch(() => sessionStore.autoPickTick, async () => {
  if (route.path === '/chat' && !loading.value) await autoPick()
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

// ==================== 图片上传（选择/拖入/粘贴，压缩为 dataURL） ====================
const pendingImages = ref([])
const fileInput = ref(null)
const pickImages = () => {
  if (pendingImages.value.length >= 5) { message.warning('最多上传 5 张图片'); return }
  fileInput.value?.click()
}
const onFilesChange = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  addImageFiles(files)
}
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
const onDropImages = e => {
  dragDepth = 0
  dragOver.value = false
  if (loading.value) return
  addImageFiles(Array.from(e.dataTransfer?.files || []))
}
const onPasteImages = e => {
  const imgs = Array.from(e.clipboardData?.files || []).filter(f => f.type.startsWith('image/'))
  if (!imgs.length || loading.value) return
  e.preventDefault()
  addImageFiles(imgs)
}

// ==================== 发送与流式回答（SSE，事件处理与旧版口径一致） ====================
const send = () => {
  const q = text.value.trim()
  const imgs = pendingImages.value.map(p => p.dataUrl)
  if ((!q && !imgs.length) || loading.value) return
  // 无任何可用模型（会话/智能体/个人默认均未配置）时引导配置，不打无谓请求
  if (!effectiveModel.value) {
    message.warning('未指定模型：请在右上角选择模型，或在个人设置/智能体中配置默认模型')
    return
  }
  text.value = ''
  pendingImages.value = []
  const deep = deepThinkOn.value
  messages.value.push({ role: 'user', content: q, images: imgs, deepThink: deep, time: Date.now() })
  streamAnswer(q, imgs, null, messages.value.length === 1, 1, deep)
}
// 输入框回车发送（Enter 发送，Shift+Enter 换行；输入法组合中不发送）
const onInputKeydown = e => {
  if (e.key === 'Enter' && !e.shiftKey) {
    if (e.isComposing || e.keyCode === 229) return   // 输入法组合中（中文候选未上屏）不发送
    e.preventDefault()
    send()
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

const streamAnswer = (question, imgs, replaceIdx, isFirstMessage, autoRetry = 1, deepThink = false) => {
  const idx = replaceIdx ?? messages.value.length
  if (replaceIdx == null) {
    messages.value.push({ role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, thinking: '', thinkOpen: true, thinkLoading: false, stage: '正在思考中…', time: Date.now(), artifacts: [], toolCalls: [], subagents: [] })
  } else {
    messages.value[replaceIdx] = { role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '', loading: true, messageId: null, fb: null, thinking: '', thinkOpen: true, thinkLoading: false, stage: '正在思考中…', time: Date.now(), artifacts: [], toolCalls: [], subagents: [] }
  }
  loading.value = true
  scrollForce()
  abortController.value = new AbortController()
  let full = ''
  let gotToken = false
  sendQuestion(currentSessionId.value, question, imgs, {
    signal: abortController.value.signal,
    deepThink,
    agentId: currentAgentId.value,
    // 会话级模型覆盖：仅用户手动切换时传（空=后端按 智能体>个人默认>全局 链路解析）
    model: currentOverrideModel.value || '',
    onThinking: t => {
      const m = messages.value[idx]
      m.thinking = (m.thinking || '') + t
      m.thinkLoading = true
      scroll()
    },
    onThinkingDone: payload => {
      const m = messages.value[idx]
      m.thinkLoading = false
      m.thinkOpen = false
      try {
        const j = JSON.parse(payload)
        if (j.thinking) m.thinking = j.thinking
      } catch (e) { /* 兼容旧 payload */ }
    },
    onToken: t => { gotToken = true; full += t; messages.value[idx].content = full; messages.value[idx].stage = ''; messages.value[idx].thinkLoading = false; scroll() },
    onStage: s => { messages.value[idx].stage = s; scroll() },
    onRetrieved: payload => {
      try {
        const j = JSON.parse(payload)
        messages.value[idx].retrieved = { keywords: j.keywords || 0, refs: j.refs || 0, terms: j.terms || [] }
        messages.value[idx].stage = ''
        scroll()
      } catch (e) { /* 忽略 */ }
    },
    onImage: imgs2 => {
      try {
        const parsed = JSON.parse(imgs2)
        messages.value[idx].images = Array.isArray(parsed) ? parsed : []
      } catch (e) { messages.value[idx].images = [] }
    },
    onArtifact: payload => {
      try {
        const a = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!a || !a.url) return
        if (!Array.isArray(messages.value[idx].artifacts)) messages.value[idx].artifacts = []
        messages.value[idx].artifacts.push(a)
        scroll()
      } catch (e) { /* 忽略 */ }
    },
    onToolStatus: rec => {
      try {
        const t = typeof rec === 'string' ? JSON.parse(rec) : rec
        if (!t || !t.name) return
        if (!Array.isArray(messages.value[idx].toolCalls)) messages.value[idx].toolCalls = []
        if (t.status === 'start') {
          messages.value[idx].toolCalls.push({ ...t })
        } else {
          const list = messages.value[idx].toolCalls
          const last = [...list].reverse().find(x => x.name === t.name && x.status === 'start')
          if (last) {
            last.status = t.status
            last.elapsedMs = t.elapsedMs || 0
            if (t.error) last.error = t.error
          } else {
            list.push({ ...t })
          }
        }
        scroll()
      } catch (e) { /* 忽略 */ }
    },
    onSubagent: payload => {
      try {
        const b = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!b || b.id == null) return
        const list = messages.value[idx].subagents || (messages.value[idx].subagents = [])
        const i = list.findIndex(x => x.id === b.id)
        if (i >= 0) list[i] = { ...list[i], ...b }
        else list.push(b)
        scroll()
      } catch (e) { /* 忽略 */ }
    },
    onSubagentRoute: payload => {
      try {
        const r = typeof payload === 'string' ? JSON.parse(payload) : payload
        if (!r) return
        messages.value[idx].subagentRoute = { candidates: r.candidates || 0, picked: r.picked || 0, names: r.names || [] }
      } catch (e) { /* 忽略 */ }
    },
    onDone: contentJson => {
      let sources = [], related = [], messageId = null, degradations = []
      try {
        const p = JSON.parse(contentJson || '{}')
        sources = Array.isArray(p.sources) ? p.sources : []
        related = Array.isArray(p.related) ? p.related : []
        messageId = p.messageId || null
        degradations = Array.isArray(p.degradations) ? p.degradations : []
        if (p.tokens && typeof p.tokens === 'object') messages.value[idx].tokens = p.tokens
        if (p.thinking) messages.value[idx].thinking = p.thinking
        messages.value[idx].thinkLoading = false
        if (typeof p.finalContent === 'string' && p.finalContent !== '') messages.value[idx].content = p.finalContent
        if (Array.isArray(p.finalImages)) messages.value[idx].images = p.finalImages
        if (Array.isArray(p.artifacts) && p.artifacts.length) messages.value[idx].artifacts = p.artifacts
        if (Array.isArray(p.toolCalls) && p.toolCalls.length) messages.value[idx].toolCalls = p.toolCalls
        // 编排视图：done 下发分支最终状态，覆盖实时 subagent 事件收敛到终态
        if (Array.isArray(p.subagentBranches) && p.subagentBranches.length) messages.value[idx].subagents = p.subagentBranches
        if (p.subagentRoute) messages.value[idx].subagentRoute = p.subagentRoute
      } catch (e) { /* 旧版/停止生成：无负载 */ }
      if (messages.value[idx].content === '') messages.value[idx].content = '（已停止生成）'
      messages.value[idx].loading = false
      // 生成完成：编排卡片收起为一行（用户未手动干预时），避免答案出来后还占着版面
      if (messages.value[idx].saOpen && !messages.value[idx].saTouched) messages.value[idx].saOpen = false
      messages.value[idx].sources = sources
      if (messages.value[idx].retrieved && Array.isArray(sources)) messages.value[idx].retrieved.refs = sources.length
      messages.value[idx].related = related
      messages.value[idx].messageId = messageId
      messages.value[idx].degradations = degradations
      loading.value = false
      abortController.value = null
      scrollForce()
      if (isFirstMessage) loadSessions()
    },
    onWarn: w => { messages.value[idx].warnMsg = w; scroll() },
    onError: e => {
      if (autoRetry > 0 && !gotToken) {
        messages.value[idx].retrying = true
        scroll()
        setTimeout(() => {
          if (idx < messages.value.length && messages.value[idx]?.role === 'ai' && messages.value[idx]?.loading) {
            streamAnswer(question, imgs, idx, false, 0, deepThink)
          } else {
            if (messages.value[idx]) messages.value[idx].retrying = false
            loading.value = false
            abortController.value = null
          }
        }, 2500)
        return
      }
      messages.value[idx].content = '😅 ' + e
      messages.value[idx].loading = false
      messages.value[idx].retrying = false
      messages.value[idx].failed = true
      loading.value = false
      abortController.value = null
      message.error(e)
      scrollForce()
    }
  })
}

const regenerate = mi => {
  if (loading.value) return
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      const imgs = (messages.value[i].images || []).filter(u => u.startsWith('data:'))
      const deep = !!messages.value[i].deepThink
      streamAnswer(messages.value[i].content, imgs, mi, false, 1, deep)
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
const fallbackCopyText = txt => {
  try {
    const ta = document.createElement('textarea')
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
  else if (key === 'deleteRound') deleteRound(mi)
  else if (key === 'export') exportRound(mi)
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

// 编辑问题重新发送
const editMessage = mi => {
  const m = messages.value[mi]
  if (!m || m.role !== 'user') return
  text.value = m.content
  const localImgs = (m.images || []).filter(u => u.startsWith('data:'))
  if (localImgs.length) pendingImages.value = [...pendingImages.value, ...localImgs.map(u => ({ dataUrl: u }))]
  nextTick(() => textareaRef.value?.focus())
  message.info('已回填到输入框，修改后按 Enter 发送')
}

const stop = () => {
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
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

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('paste', onGlobalPaste)
  await loadSessions()
  loadAgents()       // 智能体下拉候选（不阻塞首屏）
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
  getSuggested().then(r => {
    if (r.success && Array.isArray(r.data) && r.data.length) tips.value = r.data
  }).catch(() => {})
  getConfig().then(r => {
    if (!r.success) return
    debugEntryVisible.value = r.data?.chat?.retrievalDebugEnabled?.value === 'true'
  }).catch(() => {})
  getUserPreference().then(r => {
    userDefaultModel.value = (r && r.data && r.data.defaultModel) || ''
  }).catch(() => {})
  loadModelIndex().then(idx => { modelIndex.value = idx || {} }).catch(() => {})
})
</script>

<style scoped>
.chat2 { display: flex; height: 100%; min-width: 0; background: var(--app-panel); }
.chat-col { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.chat-head {
  display: flex; align-items: center; gap: 12px; padding: 10px 20px;
  border-bottom: 1px solid var(--app-border); flex: none; background: var(--app-panel);
}
.chat-title { font-size: 13px; font-weight: 500; max-width: 40%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.head-tip { font-size: 11px; color: var(--app-text3); cursor: pointer; user-select: none; }
.head-tip:hover { color: var(--app-accent); }
.head-panel-btn { margin-left: auto; padding: 4px 12px; }

.messages { flex: 1; overflow-y: auto; padding: 20px 32px 8px; }
.welcome { text-align: center; padding: 72px 20px 40px; }
.welcome-mark {
  width: 44px; height: 44px; border-radius: 12px; background: var(--app-text); color: #fff;
  font-size: 20px; display: inline-flex; align-items: center; justify-content: center;
}
.welcome h2 { margin: 14px 0 6px; font-size: 16px; font-weight: 500; }
.welcome p { color: var(--app-text3); margin: 0 0 18px; }
.welcome-tags { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; }
.welcome-tag {
  font-size: 12px; color: var(--app-text2); background: var(--app-bg);
  border: 1px solid var(--app-border); border-radius: 999px; padding: 5px 14px; cursor: pointer;
}
.welcome-tag:hover { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); }

.row { display: flex; margin-bottom: 20px; justify-content: center; }
.msg-block { position: relative; display: flex; flex-direction: column; min-width: 0; max-width: min(94%, 860px); width: 100%; }
.msg-block.user { align-items: flex-end; }
.msg-block.ai { align-items: flex-start; }
.bubble { width: 100%; line-height: 1.65; }
.bubble.user { background: #f2f4f7; border-radius: 12px; padding: 9px 14px; width: fit-content; max-width: 100%; }
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

.think-panel { margin: 4px 0 8px; border: 1px solid var(--app-border); border-radius: 8px; background: #fafbfc; overflow: hidden; }
.think-head { display: flex; align-items: center; gap: 6px; padding: 6px 10px; cursor: pointer; user-select: none; font-size: 12px; color: var(--app-text3); }
.think-head:hover { background: #f2f4f7; }
.think-arrow { font-size: 10px; transition: transform .2s; }
.think-panel.open .think-arrow { transform: rotate(180deg); }
.think-title { font-weight: 500; color: var(--app-text2); }
.think-badge { font-size: 11px; color: var(--app-text3); }
.think-body { padding: 0 10px 8px; border-top: 1px dashed var(--app-border); color: var(--app-text2); font-size: 12px; line-height: 1.7; max-height: 300px; overflow-y: auto; }
.think-body :deep(.md > p) { margin: 4px 0; }

.tool-status-list { margin-top: 8px; display: flex; flex-direction: column; gap: 3px; }
.tool-status-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; width: fit-content; }
.tool-ic { font-size: 13px; }
.tool-ic-run { color: var(--app-accent); }
.tool-ic-ok { color: var(--app-ok); }
.tool-ic-err { color: var(--app-danger); }
.tool-name { font-weight: 500; color: var(--app-text2); }
.tool-dur { color: var(--app-text3); }
.tool-fail { color: var(--app-danger); }

.artifact-list { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.artifact-item {
  display: inline-flex; align-items: center; gap: 6px; max-width: 100%;
  padding: 6px 10px; border: 1px solid var(--app-border); border-radius: 8px;
  font-size: 12px; color: var(--app-text); text-decoration: none; background: #fafbfc;
}
.artifact-item:hover { border-color: var(--app-accent); background: var(--app-accent-weak); }
.artifact-icon { color: var(--app-accent); }
.artifact-name { font-weight: 500; color: var(--app-accent); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.artifact-desc { color: var(--app-text3); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.artifact-dl { color: var(--app-text3); margin-left: auto; }

.degradation-bar {
  margin-top: 8px; padding: 6px 10px; border-radius: 6px;
  background: #faf3e6; border: 1px solid #f0dfb6; color: #a3691b;
  font-size: 12px; line-height: 1.6; display: flex; flex-wrap: wrap; gap: 4px 12px;
}
.degradation-item { display: inline-block; }

.retrieval-merged { margin-top: 8px; width: 100%; }
.retrieval-line { font-size: 12px; color: var(--app-text3); user-select: none; cursor: pointer; }
.retrieval-line:hover { color: var(--app-accent); }
.rt-arrow { font-size: 10px; margin-left: 2px; transition: transform .15s; }
.rt-arrow.open { transform: rotate(180deg); }
.retrieval-detail {
  font-size: 12px; color: var(--app-text2); background: #fafbfc; border: 1px solid var(--app-border);
  border-radius: 8px; padding: 8px 10px; margin: 4px 0 2px;
}
.rt-terms { margin-bottom: 6px; }
.rt-tool-terms { color: var(--app-accent); }
.rt-tool-tag {
  display: inline-block; font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent); margin-right: 6px; vertical-align: 1px;
}
.rt-ref { padding: 3px 0; border-top: 1px dashed var(--app-border); cursor: pointer; }
.rt-ref:hover { color: var(--app-accent); }
.rt-ref-tag { color: var(--app-accent); margin-right: 4px; }
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
  background: #f1f3f5; color: var(--app-text3);
}
.sa-status-tag.st-running { background: var(--app-accent-weak); color: var(--app-accent); }
.sa-status-tag.st-done { background: #eaf5ec; color: var(--app-ok); }
.sa-status-tag.st-failed { background: #fdeceb; color: var(--app-danger); }
.subagent-hits { margin-left: auto; font-size: 11px; color: var(--app-text3); }
.sa-desc { margin-top: 4px; font-size: 11px; color: var(--app-text3); line-height: 1.5; }
.sa-digest {
  margin-top: 5px; font-size: 11.5px; color: var(--app-text2); line-height: 1.6;
  padding: 6px 9px; background: #f8f9fa; border-radius: 5px; white-space: pre-wrap;
}
/* 按需委派判定"无需咨询任何助手"时的说明行 */
.subagent-skip {
  margin-top: 8px; font-size: 11.5px; color: var(--app-text3); line-height: 1.5;
  padding: 6px 10px; background: #f8f9fa; border: 1px solid var(--app-border); border-radius: var(--app-radius);
}

.related { margin-top: 10px; display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.related-label { font-size: 12px; color: var(--app-text3); }
.related-tag {
  font-size: 11px; color: var(--app-ok); background: #eaf5ec; border-radius: 999px;
  padding: 3px 10px; cursor: pointer;
}
.related-tag:hover { background: #ddefe0; }
.stage-hint { margin-top: 6px; font-size: 13px; color: var(--app-accent); display: flex; align-items: center; gap: 6px; }

.fb-row { margin-top: 8px; display: flex; align-items: center; gap: 2px; }
.fb-row :deep(.fb-active) { color: var(--app-accent); }
.retry-row { margin-top: 8px; }
.retry-tip {
  margin-top: 8px; display: flex; align-items: center; gap: 6px; color: #a3691b;
  font-size: 12px; background: #faf3e6; border: 1px solid #f0dfb6; border-radius: 6px;
  padding: 4px 10px; width: fit-content;
}
.msg-edit-row {
  position: absolute; top: calc(100% + 2px); left: 0; right: 0; height: 24px; z-index: 1;
  display: flex; align-items: center; justify-content: flex-end; gap: 6px;
  opacity: 0; transition: opacity .15s;
}
.msg-block:hover .msg-edit-row { opacity: 1; }
.msg-time-inline { font-size: 11px; color: var(--app-text3); margin-left: 8px; white-space: nowrap; user-select: none; }
.msg-tokens { font-size: 11px; color: var(--app-text3); white-space: nowrap; cursor: default; }
.jump-latest {
  position: sticky; bottom: 12px; z-index: 5; width: fit-content; margin: 0 auto 4px;
  background: var(--app-accent); color: #fff; font-size: 12px; padding: 4px 16px;
  border-radius: 999px; cursor: pointer; user-select: none;
}

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
/* @ 引用候选浮层：贴在输入框上方，与输入卡片同宽 */
.at-panel {
  position: absolute; left: 0; right: 0; bottom: calc(100% + 6px); z-index: 20;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  box-shadow: 0 6px 20px rgba(16, 24, 40, .1); padding: 4px; max-height: 260px; overflow-y: auto;
}
.at-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.at-item.active { background: var(--app-accent-weak); }
.at-ic {
  flex: none; width: 18px; height: 18px; border-radius: 4px; background: var(--app-accent-weak);
  color: var(--app-accent); font-size: 10px; display: flex; align-items: center; justify-content: center;
}
.at-name { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.at-meta { flex: none; font-size: 11px; color: var(--app-text3); }
.at-tip { padding: 5px 8px 3px; font-size: 11px; color: var(--app-text3); border-top: 1px solid var(--app-border); margin-top: 2px; }
/* 已选引用标签：输入框内顶部一行，× 可整体移除 */
.at-chips { display: flex; flex-wrap: wrap; gap: 6px; padding: 2px 4px 6px; }
.at-chip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 260px;
  padding: 2px 6px; border-radius: 6px; font-size: 12px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.at-chip-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.at-chip-del { flex: none; cursor: pointer; font-size: 13px; line-height: 1; opacity: .65; padding: 0 1px; }
.at-chip-del:hover { opacity: 1; color: var(--app-danger); }
.input-area { resize: none; padding: 6px 4px; font-size: 14px; line-height: 1.6; border: none; background: transparent; }
.input-area:focus { border: none; box-shadow: none; }
.input-toolbar { display: flex; align-items: center; gap: 6px; margin-top: 6px; }
.toolbar-left { display: flex; align-items: center; gap: 2px; min-width: 0; }
.toolbar-right { margin-left: auto; display: flex; align-items: center; gap: 2px; }
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
.agent-pill:hover, .agent-pill.open { background: #f2f3f5; color: var(--app-text); }
.agent-pill.on .agent-pill-ic, .agent-pill.on .agent-pill-name { color: var(--app-text); }
.agent-pill-ic { font-size: 14px; flex: none; }
.agent-pill-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); flex: none; }
.agent-pill-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-pill-caret { font-size: 12px; opacity: .55; flex: none; }

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
.agent-mi:hover { background: #f5f7fa; }
.agent-mi.active { background: var(--app-accent-weak); }
/* 头像徽标：给每行一个视觉锚点，选中态随主色 */
.agent-mi-ava {
  flex: none; width: 26px; height: 26px; border-radius: 8px; margin-top: 1px;
  display: inline-flex; align-items: center; justify-content: center; font-size: 13px;
  background: #eef1f5; color: var(--app-text3);
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
  background: #eaf5ec; color: var(--app-ok);
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

/* 状态栏：当前智能体卡片 */
.rp-agent { display: flex; align-items: center; gap: 6px; }
.rp-agent-ic { font-size: 13px; color: var(--app-accent); flex: none; }
.rp-agent-row { margin-top: 6px; align-items: baseline; }
.rp-val {
  display: inline-flex; align-items: center; gap: 5px; min-width: 0; max-width: 78%;
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
.send-btn:hover:not(:disabled) { background: #4a80ef; }
.send-btn:disabled { background: #c6d4f2; cursor: not-allowed; }
.send-btn.stop { background: var(--app-danger); }

/* 右侧状态栏 */
.right-panel {
  width: 230px; flex: none; border-left: 1px solid var(--app-border); background: #fafbfc;
  padding: 12px 10px; display: flex; flex-direction: column; gap: 10px; overflow-y: auto;
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
.rp-arrow { flex: none; font-size: 10px; color: var(--app-text3); transition: transform .2s cubic-bezier(0.16, 1, 0.3, 1); }
.rp-arrow.open { transform: rotate(180deg); }
.rp-group-body { display: grid; grid-template-rows: 0fr; overflow: hidden; transition: grid-template-rows .22s cubic-bezier(0.16, 1, 0.3, 1); }
.rp-group-body.open { grid-template-rows: 1fr; }
.rp-src-sub { padding: 3px 0 3px 20px; font-size: 11.5px; color: var(--app-text2); }
@media (prefers-reduced-motion: reduce) { .rp-group-body, .rp-arrow { transition: none; } }
.rp-src:hover .rp-src-name { color: var(--app-accent); }
.rp-src-ic { color: var(--app-accent); font-size: 12px; flex: none; }
.rp-src-name { font-size: 12px; color: var(--app-text2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 来源弹窗内容 */
.src-content { max-height: 55vh; overflow-y: auto; line-height: 1.7; font-size: 14px; padding-right: 6px; }

/* 检索调试面板 */
.dbg-item { padding: 6px 8px; margin-bottom: 6px; border: 1px solid var(--app-border); border-radius: 6px; background: #fafbfc; }
.dbg-terms { padding: 8px 10px; margin-bottom: 10px; border: 1px solid #d6e4ff; border-radius: 6px; background: #f0f6ff; }
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
</style>
