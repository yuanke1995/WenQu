<template>
      <!-- 团队成员会话：宽屏并入标题栏；窄屏此细条提示 -->
      <div v-if="store.memberViewRoot && store.isNarrow" style="display:flex;align-items:center;gap:10px;padding:8px 16px;border-bottom:1px solid #e6e9ed;background:#f5f3ff;flex:none;">
        <button style="border:1px solid #ddd6fe;background:#fff;color:#7c3aed;border-radius:8px;padding:4px 10px;font-size:12px;cursor:pointer;" @click="store.backToRootSession">← 返回主会话</button>
        <span style="font-size:11px;font-weight:650;color:#7c3aed;background:#fff;border:1px solid #ddd6fe;border-radius:5px;padding:1px 7px;">团队成员会话</span>
        <span v-if="store.memberViewName" style="font-size:12px;color:#5f6570;">{{ store.memberViewName }}</span>
      </div>
      <!-- PC 标题栏：窄屏隐藏（与移动顶栏形成双顶栏，图里很明显），
           其中的会话查找/分享/状态三个动作改由下面的 MobileChatHead 以图标提供 -->
      <div v-if="!store.isNarrow" class="chat-head">
        <template v-if="store.memberViewRoot">
          <button style="border:1px solid #ddd6fe;background:#fff;color:#7c3aed;border-radius:8px;padding:4px 10px;font-size:12px;cursor:pointer;flex:none;" @click="store.backToRootSession">← 返回主会话</button>
          <span style="font-size:11px;font-weight:650;color:#7c3aed;background:#f5f3ff;border:1px solid #ddd6fe;border-radius:5px;padding:1px 7px;flex:none;">团队成员会话</span>
        </template>
        <span class="chat-title">{{ store.memberViewRoot ? (store.memberViewName || '成员会话') : store.currentSessionTitle }}</span>
        <span v-if="store.memberViewRoot && store.memberLiveStatus === 'live'" class="member-live-tag" title="正在直播这一轮：正文与工具步骤实时更新">
          <i class="mlt-dot"></i>直播中
        </span>
        <span v-else-if="store.memberViewRoot && store.memberLiveStatus === 'reconnecting'" class="member-live-tag is-warn" title="连接中断，正在重连">
          <i class="mlt-dot"></i>重连中…
        </span>
        <!-- 当前工作区标识：约定每轮注入 system prompt，用户有权在聊天的当场看见它在生效；
             没挂工作区的会话接口仍返回档位等字段，但 projectId 为空——这里按它判断，标识不显示（不编名字）。
             implicit 兜底容器也一样不显示：它没有目录，名字被兜成「会话」，挂出来只会被读成会话标题重复。
             点它直接进工作区页面（目录/记忆/隔离都在那一页） -->
        <button v-if="store.sessionWorkspace && store.sessionWorkspace.projectId && !store.sessionWorkspace.implicit" class="head-ws" :class="{ 'head-ws-archived': store.sessionWorkspace.archived }"
                :title="store.wsTitle" @click="store.router.push('/project/' + store.sessionWorkspace.projectId)">
          <folder-open-outlined class="head-ws-ic" />
          <span class="head-ws-name">{{ store.sessionWorkspace.name }}</span>
        </button>
        <!-- 免责声明从标题栏下沉到每条回答的脚注（见 msg-disclaimer）：它该出现在读结论的现场 -->
        <!-- 头部动作区：右对齐一组，图标按钮无框安静 -->
        <div class="head-actions">
          <!-- 顶栏这组「派了谁」两颗药丸口径互斥，别读成重复：
               · 团队 · N（TeamPill）——名册，只在会话开了智能体团队时出现（关着时它无事可做，开关在右栏团队的未开启空态里）；
               · N 位助手 / N 位在跑（SubagentPill）——本轮派遣卡，只在团队关闭或仍有派遣在跑时出现。
               「开着团队且本轮没人在跑」时只剩团队 · N：派遣结果已经收进名册，再挂一颗只会让人问「这是不是同一件事」。
               两边的显隐都由各自组件内部按数据判定（TeamPill 读团队开关，SubagentPill 读 runs），这里只传消息。 -->
          <TeamPill v-if="!store.memberViewRoot" :session-id="store.currentSessionId || ''" :msg="store.processMsg" @open="store.openTeamPanel" />
          <button v-if="store.memberViewRoot" type="button" title="停止该成员当前轮" style="height:26px;padding:0 10px;border:1px solid #f3c9c9;background:#fff2f0;color:#cf1322;border-radius:7px;font-size:12px;cursor:pointer;margin-right:4px;"
                  @click="store.stopNow">停止</button>
          <button class="app-icon-btn" title="在本会话中查找（Ctrl/⌘ + F）" @click="store.openSearch">
            <search-outlined />
          </button>
          <!-- 分享激活态：图标转琥珀，与侧栏会话行的分享标记同色（链接还开着不进弹窗也得有感知） -->
          <button class="app-icon-btn" :class="{ 'share-on': store.shareActive }" :title="store.shareActive ? '这段对话正在对外分享（只读链接生效中）' : '分享这段对话（只读链接）'"
                  :aria-label="store.shareActive ? '这段对话正在对外分享（只读链接生效中）' : '分享这段对话（只读链接）'"
                  @click="store.openShare">
            <share-alt-outlined />
          </button>
          <!-- 子智能体药丸：这一轮派出去几位、几位还在跑。它坐在标题栏而不是正文里，是因为
               派遣一跑就是两三分钟，而正文在这期间一直在往下长——跟在正文里的东西要滚到
               最新那一段才看得见，等它跑完才出现的东西等于没给。 -->
          <SubagentPill :msg="store.processMsg" :session-id="store.currentSessionId || ''" @focus="store.focusSpawn" />
          <!-- 过程账本入口收成图标：过程本身已经挂到回答头上，顶栏只留「事后回看」这条路 -->
          <button class="app-icon-btn" title="看这个会话是怎么跑出来的：工具步与耗时、三张卡、产物、停止与失败"
                  @click="store.openEvents()"><history-outlined /></button>
          <!-- 状态胶囊：把「本轮跑成什么样」放回顶栏；失败时变红，点它开合右栏 -->
          <button class="head-status" :class="store.headStatusClass" :title="store.headStatusTitle"
                  @click="store.headStatusClick">
            <span class="hs-dot" aria-hidden="true"></span>
            <span>{{ store.headStatusText }}</span>
          </button>
        </div>
      </div>
      <!-- 窄屏动作行：标题归顶栏，这里只放查找/分享/状态三个图标 -->
      <MobileChatHead v-if="store.isNarrow" :share-active="store.shareActive" @search="store.openSearch" @share="store.openShare" @panel="store.togglePanel">
        <SubagentPill mobile :msg="store.processMsg" :session-id="store.currentSessionId || ''" @focus="store.focusSpawn" />
      </MobileChatHead>

      <!-- 执行过程（事件账本）：按轮列出这一会话跑过什么——工具步与耗时、审批/提问/计划三张卡、
           产物、子智能体编排、停止与失败。这份记录不参与智能体的回答，只服务事后回看与排障。
           取数与渲染在共用组件里（移动壳的底部 sheet 用同一份，不各写一遍） -->
      <a-modal :open="store.eventsOpen" :title="store.eventsTitle" width="min(880px, 94vw)" :footer="null">
        <SessionEventsPanel :open="store.eventsOpen" :session-id="store.currentSessionId || ''" :turn-id="store.eventsTurn"
                            :message="store.eventsMsg" :question="store.eventsQuestion" />
      </a-modal>

      <!-- 目标过程：右栏抽屉，版本轨 / 用量预算 / 按轮时间线 / 结果；窄屏由组件切全屏 -->
      <GoalViewer :open="store.goalViewerOpen" :session-id="store.currentSessionId || ''" :goal="store.sessionGoal" device="pc"
                  @close="set('goalViewerOpen', false)"
                  @action="store.onGoalViewerAction" @jump="store.jumpToGoalTurn" />

      <!-- 交付抽屉：与目标过程抽屉同款（右侧 420px）。卡片 / 目标条 / 终态卡三处都落到这里，
           关掉仍回到对话原位；清单分组与批量动作只在面板里出现。 -->
      <a-drawer :open="store.deliveryOpen" placement="right" :width="420"
                :body-style="{ padding: '0' }" :title="store.deliveryTarget ? store.deliveryTarget.name : '交付包'">
        <DeliveryPanel :delivery="store.deliveryTarget" @zip="store.downloadDeliveryZip" />
      </a-drawer>

      <!-- 技能正文查看：写入已交给助手，读的这一侧要能审（正文是逐字进提示词的）；移动壳用同一份组件 -->
      <SkillContentModal :open="!!store.skillDoc" :name="store.skillDoc" :entry="store.skillDocEntry"
                         :session-id="store.currentSessionId || ''"
                         @update:open="v => { if (!v) skillDoc = '' }" />

      <!-- 固化为技能：预填本轮「问题→做法」成一条 SKILL.md，三个字段都可改；提交落当前账号的技能目录 -->
      <a-modal :open="store.solidifyOpen" title="固化为技能" width="min(640px, 94vw)"
               :confirm-loading="store.solidifyBusy" @ok="store.submitSolidify">
        <div class="sf-field">
          <div class="sf-label">技能名<span class="sf-sub">汉字/字母/数字/下划线/连字符，≤64 字符</span></div>
          <a-input v-model:value="store.solidifyForm.name" :maxlength="64" placeholder="这类做法叫什么" />
        </div>
        <div class="sf-field">
          <div class="sf-label">描述<span class="sf-sub">模型靠它判断什么时候读取这条技能</span></div>
          <a-textarea v-model:value="store.solidifyForm.description" :rows="2" :maxlength="1000" />
        </div>
        <div class="sf-field">
          <div class="sf-label">正文（SKILL.md）</div>
          <a-textarea v-model:value="store.solidifyForm.content" :rows="10" class="sf-content" />
        </div>
      </a-modal>

      <!-- 会话内查找：按消息导航 + 命中高亮（长会话里定位旧问答） -->
      <div v-if="store.searchOpen" class="chat-search">
        <search-outlined class="cs-ic" />
        <input :ref="el => setRef('searchInputRef', el)" v-model="store.searchQuery" class="cs-input"
               placeholder="在本会话中查找…（Enter 下一个 · Shift+Enter 上一个 · Esc 关闭）"
               @keydown.enter.exact.prevent="store.gotoMatch(1)"
               @keydown.shift.enter.prevent="store.gotoMatch(-1)"
               @keydown.esc="store.closeSearch" />
        <span class="cs-count">
          {{ store.searchQuery.trim() ? (store.matchedIdxs.length ? (store.searchPosShown + ' / ' + store.matchedIdxs.length) : '无匹配') : '' }}
        </span>
        <button class="app-icon-btn" :disabled="!store.matchedIdxs.length" title="上一个匹配" @click="store.gotoMatch(-1)"><up-outlined /></button>
        <button class="app-icon-btn" :disabled="!store.matchedIdxs.length" title="下一个匹配" @click="store.gotoMatch(1)"><down-outlined /></button>
        <button class="app-icon-btn" title="关闭查找" @click="store.closeSearch"><close-outlined /></button>
      </div>

      <!-- 工具审批走对话里的内联卡（见消息区的 .approval-card）：通知深链由引擎按 approvalId
           定位到那一张并闪一圈，不再在顶部另画一张横幅。已处理/已失效只弹一句提示。 -->

      <!-- 智能体提问不走顶部横幅：卡片已落库、等待期间断线不中止本轮，恢复由引擎在会话加载时
           按待答记录重建底部答题面板（hydratePendingAsk），与实时卡同一处、同一套交互。 -->

      <!-- 消息流（历史错误卡 / 欢迎页 / 逐轮气泡 / 回到底部）：DOM 与 scoped 样式见
           chatpage/ChatMessageList.vue；父页通过 provide('chatCtx') 交出上下文 -->
      <ChatMessageList />

      <!-- 输入区：卡片外壳 / 正文与 chip 同流 / 工具条 / 拖拽落点全在 chat/ChatInput.vue，
           与公开分享页共用同一份实现（改一次两页同时生效）。这里只往插槽里填本页自己的东西：
           卡片外的一列（任务清单 / 目标条 / 插话队列）、智能体胶囊、模型与思考、三块面板，
           以及顶替态的两张卡（提问 / 计划确认栏）。 -->
      <!-- 输入区（卡片外壳 / chip / 工具条 / 三块面板 / 顶替态两张卡）：见 chatpage/ChatComposerArea.vue -->
      <ChatComposerArea />
      <!-- 完全权限二次确认：这是一次「把围栏放下」的显式动作，必须看清风险再勾选 -->
      <a-modal :open="store.dangerOpen" title="确认启用完全权限？" :footer="null" :width="480"
               :mask-closable="!store.dangerSaving" @cancel="set('dangerOpen', false)">
        <div class="danger-confirm">
          <p class="danger-lead">
            <ExclamationCircleOutlined class="danger-ic" />
            启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。
          </p>
          <a-checkbox v-model:checked="store.dangerAck" :disabled="store.dangerSaving">
            我已了解风险，并愿意继续
          </a-checkbox>
          <div class="danger-actions">
            <button type="button" class="app-btn ghost" :disabled="store.dangerSaving" @click="set('dangerOpen', false)">取消</button>
            <button type="button" class="app-btn danger" :disabled="!store.dangerAck || store.dangerSaving" @click="store.confirmDanger">
              {{ store.dangerSaving ? '启用中…' : '启用完全权限' }}
            </button>
          </div>
        </div>
      </a-modal>

      <input :ref="el => setRef('attachInput', el)" type="file" multiple style="display:none" @click.stop @change="store.onAttachChange" />

</template>

<script setup>
import { inject } from 'vue'
// 父页 provide('chatCtx')：一个 reactive 上下文（含 ref 与动作）
const store = inject('chatCtx')
const set = (k, v) => { store[k] = v }
const setRef = (k, el) => { store[k] = el }
</script>

<style scoped src="./ChatHeaderBar.css"></style>
