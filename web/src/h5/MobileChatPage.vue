<template>
  <!-- 移动原生壳（M2）：独立于工作台三栏布局的聊天主线。
       引擎（会话/流式/选择器/附件/引用）与 PC 壳共用同一份 useChatEngine，此处只做移动呈现：
       顶栏 + 全屏消息流 + 贴底输入区 + 底部 sheet 族（会话/模型思考/引用与附件/状态来源）。
       发送靠按钮（移动键盘的回车是换行）、停止直点（无桌面 ESC 两段式）。 -->
  <div class="m-chat">
    <header class="m-bar">
      <button class="m-bar-btn" type="button" title="会话列表" aria-label="会话列表" @click="sessionsOpen = true">
        <menu-outlined />
        <span v-if="loading" class="m-bar-dot" />
      </button>
      <button class="m-bar-title" type="button" title="查看状态与来源" @click="refOpen = true">
        <span class="m-bar-t">{{ currentSessionTitle }}</span>
        <caret-down-outlined class="m-bar-caret" />
      </button>
      <button class="m-bar-btn" type="button" title="在本会话中查找" aria-label="在本会话中查找" @click="openSearch">
        <search-outlined />
      </button>
      <!-- 过程回放：PC 的头部「过程」按钮在移动壳的同位入口。账本只服务事后回看，
           放顶栏而不是收进会话 sheet——「这一轮为什么这么答」是排障动作，不该多跳两层 -->
      <button class="m-bar-btn" type="button" title="看这个会话是怎么跑出来的" aria-label="看这个会话是怎么跑出来的" @click="openEvents()">
        <history-outlined />
      </button>
      <button class="m-bar-btn" type="button" title="新建对话" aria-label="新建对话" @click="createNewSession()">
        <plus-outlined />
      </button>
      <!-- 通知：手机上「平台有新事」的唯一入口（PC 是侧栏 foot 的铃铛 popover）。
           取数实例由本页 provide，角标与 sheet 列表共用同一份未读数 -->
      <button class="m-bar-btn" type="button" title="通知" aria-label="通知" @click="notifOpen = true">
        <bell-outlined />
        <span v-if="notifUnread > 0" class="m-bar-badge">{{ notifUnread > 99 ? '99+' : notifUnread }}</span>
      </button>
    </header>

    <!-- 连通性：本机断网与服务器不通分开说一行（/m/chat 不经工作台外壳，这里自己挂一份） -->
    <NetBanner />

    <!-- 会话内查找：移动壳没有 Ctrl+F，入口在顶栏。行作为 flex:none 插在消息流之上——
         不用 fixed 是刻意的：keyboard.js 已把容器高写成 --app-vh（已扣软键盘），
         键盘弹起时容器自然收缩，查找条不会被盖住也不会悬浮错位。 -->
    <div v-if="searchOpen" class="m-find">
      <search-outlined class="mf-ic" />
      <input ref="searchInputRef" v-model="searchQuery" class="mf-input" type="search"
             placeholder="在本会话中查找…" enterkeyhint="search"
             @keydown.enter.prevent="gotoMatch(1)" />
      <span class="mf-count">
        {{ searchQuery.trim() ? (matchedIdxs.length ? searchPosShown + ' / ' + matchedIdxs.length : '无匹配') : '' }}
      </span>
      <button class="mf-btn" type="button" title="上一个匹配" aria-label="上一个匹配" :disabled="!matchedIdxs.length" @click="gotoMatch(-1)"><up-outlined /></button>
      <button class="mf-btn" type="button" title="下一个匹配" aria-label="下一个匹配" :disabled="!matchedIdxs.length" @click="gotoMatch(1)"><down-outlined /></button>
      <button class="mf-btn" type="button" title="关闭查找" aria-label="关闭查找" @click="closeSearch"><close-outlined /></button>
    </div>

    <!-- 工具审批恢复横幅：点开 tool.approval 通知直达会话时，按 approvalId 重建审批卡
         （随刷新丢失的内联卡据此补回；内存态可能已失效，此时 status 为终态并提示）。
         与 PC 壳顶部横幅是同一份状态机（chat/useApprovalRecovery.js）。 -->
    <div v-if="recApproval" class="m-rec">
      <div class="m-rec-title"><exclamation-circle-outlined /> 工具审批待处理：{{ recApproval.toolName }}</div>
      <pre v-if="recApproval.requestArgs" class="m-rec-args">{{ recApproval.requestArgs }}</pre>
      <div class="m-rec-foot">
        <span class="m-rec-status" :class="'rec-' + recStatusClass">{{ recStatusText }}</span>
        <template v-if="recApproval.status === 'PENDING'">
          <button class="m-rec-btn primary" type="button" :disabled="recBusy" @click="resolveRecovery(true)">批准执行</button>
          <button class="m-rec-btn" type="button" :disabled="recBusy" @click="resolveRecovery(false)">拒绝</button>
        </template>
        <button class="m-rec-btn ghost" type="button" @click="dismissRecovery">关闭</button>
      </div>
    </div>

    <!-- 智能体提问不走顶部横幅（与 PC 壳同口径）：卡片已落库、等待期间断线不中止本轮，
         会话加载时由引擎按待答记录重建底部答题面板；答复后问答记录以工具卡形态留在气泡里。 -->

    <main ref="box" class="m-list" @scroll.passive="onScroll">
      <!-- 历史没取回来：这是一次失败，不是「这个会话还没有消息」。以前这里只有一颗顶栏小圆点
           闪一下就没了，界面接着把欢迎页画给用户看——用户以为会话被清空了，实际是请求挂了。
           错误卡占住空态的位置，给出原因和重试出口。 -->
      <div v-if="historyError" class="m-herr" role="alert">
        <exclamation-circle-outlined class="mhe-ic" />
        <div class="mhe-t">这个会话的历史没有加载出来</div>
        <div class="mhe-s">{{ historyError }}</div>
        <button class="mhe-btn" type="button" @click="retryHistory">重试</button>
      </div>

      <!-- 空态：品牌 + 欢迎语。门控与 PC 欢迎区同一条：聊天/默认模型未就绪时换成配置引导
           （没有可用模型时任何提问都只会弹拦截 toast）；loaded=false（首次对账未成功）维持现状，不闪假引导
           —— 且必须排掉 historyError：那是「没取回来」，不是「没有」 -->
      <div v-if="!messages.length && !historyError" class="m-welcome">
        <BrandMark class="m-brand" />
        <template v-if="setupGuide.loaded && !chatDone">
          <div class="m-welcome-title">欢迎使用问渠</div>
          <div class="m-welcome-sub">完成下面的配置即可开始对话</div>
          <div class="m-setup"><SetupGuide scope="chat" variant="card" /></div>
        </template>
        <WelcomeCopy v-else variant="mobile" :agent-name="engine.currentAgent?.name || ''" :think-on="deepThinkOn"
                     :attach-count="pendingImages.length + pendingFiles.length" :mentions="mentionNames" />
      </div>

      <!-- 键用消息对象本身：m.messageId 在 done 才回填，用「messageId 或下标」会让那颗回答
           在收尾一刻换键重挂；插话插进流中间时同样会整列错位重渲染 -->
      <template v-for="(m, i) in messages" :key="m">
        <!-- 压缩记录：这条提问上方出现分隔线=本轮开始前压过一次早期对话（判据与 PC 同源，在引擎里；
             本轮刚压完、回答还没落库的那一档由 liveCompactAt 顶上，两条共用同一个位子不空窗） -->
        <MsgDivider v-if="compactDividerAt(m, i) || liveCompactAt(m, i)" touch class="m-cdiv">
          <compress-outlined />
          <span>已压缩上下文</span>
          <button class="app-link-btn" type="button" :disabled="compactSummaryLoading"
                  @click="openCompactSummary">{{ compactSummaryLoading ? '读取中…' : '查看摘要' }}</button>
        </MsgDivider>
        <MobileMsgRow
          :m="m" :index="i" :active="activeIdx === i" :is-last="i === messages.length - 1"
          :last-round="m === panelAi"
          :debug-display="debugDisplayVisible" :session-id="currentSessionId || ''" :loading="loading"
          :variant-switching="variantSwitching" :goal="sessionGoal"
          @activate="activeIdx = $event"
          @preview="openPreview"
          @paste-view="openPasteView"
          @source="openSourceDetail"
          @retry="regenerate"
          @edit="startEdit"
          @feedback="submitFeedback"
          @approve="resolveApproval"
          @switch-version="switchBranch"
          @ask="ask"
          @copy="copyAnswer"
          @copy-user="copyUserMessage"
          @more="openRound"
        />
      </template>
    </main>

    <button v-if="!stickToBottom && messages.length" class="m-jump" type="button" title="回到最新" aria-label="回到最新" @click="scrollForce()">
      <arrow-down-outlined />
    </button>

    <!-- 手动 /compact 的进行中：只认手动那一路（与 PC 同一条状态、同一处落点）。
         轮内自动压缩的进度说在气泡那条状态行上，不在这再挂一条会反复弹出的线 -->
    <MsgDivider v-if="manualCompacting" touch class="m-cdiv-tail">
      <loading-outlined spin />
      <span>正在压缩上下文</span>
    </MsgDivider>
    <!-- 手动压缩当场那条（与 PC 同一份状态）：下一轮落库的摘要覆盖点会接上这件事，
         届时上面那条持久分隔线画在同一个缝上，不会重复出现 -->
    <MsgDivider v-else-if="compactNotice && compactNotice.sid === currentSessionId" touch class="m-cdiv-tail">
      <compress-outlined />
      <span>已压缩上下文</span>
      <button class="app-link-btn" type="button" @click="openCompactSummary">查看摘要</button>
    </MsgDivider>

    <!-- ==================== 输入区 ==================== -->
    <footer class="m-composer">
      <!-- 本轮随行内容 chips -->
      <div v-if="hasChips" class="m-chips">
        <!-- 计划模式开启态：工具条上不再有那颗药丸，这一排就是唯一会说「下一轮先出计划」的地方。
             名字与 PC 正文里那颗 chip 同一个词（「计划」），× 即关闭。发送即消费，chip 与这一排
             其它本轮内容一起退场（见引擎 send）。 -->
        <span v-if="planModeOn" class="m-chip m-chip-plan">
          计划<button class="m-chip-del" type="button" title="关闭计划模式" @click="setPlanMode(false)">×</button>
        </span>
        <span v-for="(p, i) in pendingImages" :key="'img' + i" class="m-chip m-chip-img">
          <img :src="p.dataUrl" alt="" @click="openPreview(pendingImages.map(x => x.dataUrl), i)" />
          <button class="m-chip-del" type="button" title="移除" @click="removePendingImage(i)">×</button>
        </span>
        <template v-for="(f, i) in pendingFiles" :key="'f' + i">
          <span v-if="f.paste" class="m-paste" @click="openPasteView(f)">
            <file-text-outlined class="m-paste-ic" />
            <span class="m-paste-txt">
              <span class="m-paste-name">{{ pasteTitle(f.name) }}</span>
              <span class="m-paste-sub">{{ pasteSub(f) }}</span>
            </span>
            <button class="m-chip-del" type="button" title="移除" @click.stop="removePendingFile(i)">×</button>
          </span>
          <span v-else class="m-chip">
            {{ f.uploading ? '上传中…' : f.name }}<button class="m-chip-del" type="button" title="移除" @click="removePendingFile(i)">×</button>
          </span>
        </template>
        <span v-for="s in pickedSkills" :key="'s' + s" class="m-chip m-chip-skill">
          技能 · {{ s }}<button class="m-chip-del" type="button" title="移除" @click="toggleSkill(s)">×</button>
        </span>
        <span v-for="(mm, i) in pendingMentions" :key="'m' + i" class="m-chip m-chip-mention">
          {{ mm.name || mm.id }}<button class="m-chip-del" type="button" title="移除" @click="removeMention(i)">×</button>
        </span>
        <span v-for="(h, i) in pendingHistoryRefs" :key="'h' + i" class="m-chip">
          历史 · {{ (h.digest || h.messageId).slice(0, 8) }}<button class="m-chip-del" type="button" title="移除" @click="removeHistoryRef(i)">×</button>
        </span>
      </div>

      <!-- 子智能体药丸（与 PC 标题栏那一颗同一份组件，触屏形态点开走底部 sheet）：
           放在输入卡正上方而不是顶栏——顶栏那六个图标已经挤满，而这一条只在跑轮的那几分钟里出现，
           是「此刻正在发生什么」，与目标条同一族（目标条也常驻在这儿）。 -->
      <SubagentPill mobile :msg="panelAi" @focus="focusSpawn" />

      <!-- 目标条（常驻摘要条，与 PC 那一条同一套词汇与判据）：横跨多轮的目标不属于哪一颗气泡，
           所以常驻在输入卡正上方。一行说不完的（跑了几轮、卡在哪）压成第二行小字——
           触屏没有 hover，PC 收在悬浮里的东西在这儿必须落在地上。
           用户在这里能做的只有三件事：停/继续、改目标原文（开底部 sheet）、删掉目标。
           验收、收尾这类裁决动作已经全部退役——目标跑到头由助手自己停，不需要人拍板。 -->
      <div v-if="goalBarVisible" class="m-goalbar" data-goal-bar>
        <div class="m-gb-row">
          <!-- 状态药丸读的就是引擎那一份词（只读，不可点）：在跑还是停住，一眼分得开 -->
          <span class="m-gb-pill" :class="'st-' + goalStateClass">
            <aim-outlined class="m-gb-ic" />{{ goalPhrase }}
          </span>
          <span class="m-gb-objective">{{ sessionGoal.objective }}</span>
          <span class="m-gb-acts">
            <button v-if="goalCanPause" type="button" class="m-gb-btn" :disabled="sessionGoal.busy"
                    title="停止目标（可以再继续）" aria-label="停止目标（可以再继续）" @click="runGoalAction('pause')"><pause-circle-outlined /></button>
            <button v-else-if="goalCanResume" type="button" class="m-gb-btn" :disabled="sessionGoal.busy"
                    title="继续目标" aria-label="继续目标" @click="runGoalAction('resume')"><play-circle-outlined /></button>
            <button v-if="goalCanEdit" type="button" class="m-gb-btn"
                    title="编辑目标" aria-label="编辑目标" @click="startGoalInlineEdit">
              <edit-outlined />
            </button>
            <button type="button" class="m-gb-btn" :disabled="sessionGoal.busy"
                    title="删除目标" aria-label="删除目标" @click="runGoalAction('clear')"><close-outlined /></button>
          </span>
        </div>
        <div v-if="goalBarError" class="m-gb-error" role="alert">{{ goalBarError }}</div>
        <div v-else-if="goalBarMeta" class="m-gb-meta">{{ goalBarMeta }}</div>
      </div>

      <!-- 任务清单：本会话最近一次 writeTodo 的整表快照，坐在输入卡正上方，与 PC 同一列同一顺序
           （先看还差几步，再看整件事推到哪；插话队列仍排在它下面——那是「还没发出去的话」，离输入框最近）。
           默认收成一行计数：触屏没有 hover，PC 藏进悬浮的东西在这儿必须落在地上，
           而清单摊开常有十行，会常驻吃掉输入区上方那一屏。显隐与计数判据吃引擎那一份。 -->
      <div v-if="todoBarVisible" class="m-todo" :class="{ open: todoOpen }">
        <button class="mt-head" type="button" @click="todoOpen = !todoOpen"
                :title="todoOpen ? '收起任务清单' : '展开任务清单'">
          <ordered-list-outlined class="mt-ico" />
          <span class="mt-label">任务清单</span>
          <span class="mt-count">{{ todoSummary }}</span>
          <caret-right-outlined class="mt-caret" :class="{ open: todoOpen }" />
        </button>
        <div v-show="todoOpen" class="mt-list">
          <div v-for="(t, ti) in sessionTodos.items" :key="ti" class="mt-item" :class="t.status">
            <!-- 进行中且本轮还在跑：框里换成转圈 + 整行底色 + 「执行中」文字（与 PC 同一套判据）。
                 转圈仍要坐在这个框里：这一列其余行都是方框，裸图标会在框的位置留一个洞。
                 本轮已停时不给转圈——清单是快照，停掉的那一项再转下去就是撒谎。 -->
            <span class="mt-box" :class="{ run: t.status === 'doing' && loading }">
              <loading-outlined v-if="t.status === 'doing' && loading" spin />
              <template v-else>{{ t.status === 'done' ? '✓' : '' }}</template>
            </span>
            <span class="mt-txt">{{ t.content }}</span>
            <span v-if="t.status === 'doing' && loading" class="mt-run-tag">执行中</span>
          </div>
        </div>
      </div>

      <!-- 插话队列：跑着的这一轮里发出去的话先落在这里排队，<b>没点「立即发送」就是还没发出去</b>。
           吃的是引擎那份 steerQueue（与 PC 同一状态、同一组动作），条目里的状态文字也同词。
           刻意不搬 PC 的拖动排序：触屏没有可靠的 HTML5 拖拽，而顺序只影响收尾结算的发出次序，
           入队序已经是对的，做不出「排得更快」就别加一颗拖不动的把手。
           版式是文字一行、动作一行：360px 屏上把三颗 44px 热区挤到文字同一侧会吞掉整句话。 -->
      <div v-if="steerQueue.length" class="m-steer">
        <div v-for="(it, qi) in steerQueue" :key="qi" class="ms-item"
             :class="{ sending: it.sending, deferred: it.deferred, awaiting: it.awaiting }">
          <div class="ms-txt">{{ it.text }}</div>
          <div class="ms-foot">
            <span v-if="it.deferred" class="ms-state">本轮结束后自动发出</span>
            <span v-else-if="it.sending" class="ms-state">发送中…</span>
            <span v-else-if="it.awaiting" class="ms-state">已送出，等这一轮听到</span>
            <template v-else>
              <button class="ms-act ghost" type="button" title="丢弃这条" aria-label="丢弃这条" @click="dropSteer(it)"><delete-outlined /></button>
              <button class="ms-act" type="button" title="取回输入框改" aria-label="取回输入框改" @click="editSteer(it)"><edit-outlined /></button>
              <button class="ms-go" type="button" @click="sendSteer(it)">立即发送</button>
            </template>
          </div>
        </div>
      </div>

      <!-- 智能体提问面板（一卡多问）：当前会话有挂起提问时整块替换输入卡。
           触屏直接点选；逐题翻页、底部一次提交；答复后面板撤下、输入卡回归，问答记录留在气泡原位 -->
      <div v-if="pendingAsk" class="m-input-card m-askp">
        <div class="m-askp-head">
          <span class="m-askp-tag">{{ mCurAsk.topic || '向用户提问' }}</span>
          <span v-if="mAskQueueCount" class="m-askp-state">还有 {{ mAskQueueCount }} 张待答</span>
          <span class="m-askp-q">{{ mCurAsk.question }}</span>
          <span v-if="mCurAsk.multi" class="m-askp-multi">可多选</span>
          <span v-if="mAskExpired" class="m-askp-state warn">已超时</span>
          <span v-if="mAskCountdownText" class="m-askp-timer" :class="{ warn: mAskExpired }">{{ mAskCountdownText }}</span>
          <span v-if="pendingAsk.ask.questions.length > 1" class="m-askp-pager">
            <button class="m-askp-page-btn" type="button" :disabled="mAskPage <= 0 || pendingAsk.ask.busy" @click="mAskPage--">‹</button>
            <span class="m-askp-page-num">{{ mAskPage + 1 }} / {{ pendingAsk.ask.questions.length }}</span>
            <button class="m-askp-page-btn" type="button" :disabled="mAskPage >= pendingAsk.ask.questions.length - 1 || pendingAsk.ask.busy" @click="mAskPage++">›</button>
          </span>
        </div>
        <div class="m-askp-opts">
          <button v-for="(op, oi) in mCurAsk.options" :key="oi" class="m-askp-opt" type="button"
                  :class="{ sel: askOptionPicked(pendingAsk.ask, mAskPage, oi) }"
                  :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || mAskExpired" @click="onPickM(oi)">
            <!-- 与 PC 同一条规则：多选行前方块=勾选框，单选行不画圈（一行只可能留一项，画两遍没意义） -->
            <span v-if="mCurAsk.multi" class="m-askp-tick" :class="{ on: askOptionPicked(pendingAsk.ask, mAskPage, oi) }"></span>
            <span class="m-askp-no">{{ oi + 1 }}.</span>
            <span class="m-askp-kw">{{ askOptionParts(op).kw }}<span v-if="oi === 0" class="m-askp-rec">（推荐）</span></span>
            <span v-if="askOptionParts(op).rest" class="m-askp-rest">{{ askOptionParts(op).rest }}</span>
          </button>
          <div class="m-askp-opt m-askp-custom" :class="{ sel: askCustomPicked(pendingAsk.ask, mAskPage) }">
            <span v-if="mCurAsk.multi" class="m-askp-tick" :class="{ on: askCustomPicked(pendingAsk.ask, mAskPage) }"></span>
            <span class="m-askp-no">{{ mCurAsk.options.length + 1 }}.</span>
            <input v-model="pendingAsk.ask.customs[mAskPage]" class="m-askp-input" :maxlength="2000" enterkeyhint="done"
                   :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || mAskExpired"
                   placeholder="输入你的回答…" @blur="commitCustomM(mAskPage, false)"
                   @keydown.enter.prevent="commitCustomM(mAskPage, true)" />
          </div>
        </div>
        <div class="m-askp-foot">
          <span class="m-askp-hint">{{ mAskHint }}</span>
          <span class="m-askp-actions">
            <button class="m-rec-btn ghost" type="button" :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || mAskExpired" @click="ignoreAsk(pendingAsk)">忽略</button>
            <button class="m-rec-btn primary" type="button" :disabled="pendingAsk.ask.busy || pendingAsk.ask.answered || mAskExpired" @click="askSubmitAll(pendingAsk)">提交（{{ askAnsweredCount(pendingAsk.ask) }} / {{ pendingAsk.ask.questions.length }}）</button>
          </span>
        </div>
      </div>

      <!-- 计划确认栏（人在回路）：待批准期间整块顶替输入卡，与 PC 的 planp-* 同一套引擎状态
          （pendingPlan / curPlanCard / planExpired / planCountdownText 都在 useChatEngine 里，不在此重算）。
           计划正文不在这里重复一份——它随气泡里的执行计划卡呈现（共用 chat/PlanCard.vue）。
           触屏没有 Enter 提交这回事：写完意见点「继续对话」。 -->
      <div v-else-if="pendingPlan" class="m-input-card m-planp">
        <div class="m-planp-head">
          <span class="m-planp-tag">执行计划</span>
          <span v-if="planExpired" class="m-planp-state warn">已超时，本轮将停止</span>
          <span v-else class="m-planp-state">待确认</span>
          <span v-if="planCountdownText" class="m-planp-timer" :class="{ warn: planExpired }">{{ planCountdownText }}</span>
        </div>
        <div class="m-planp-opts">
          <button type="button" class="m-planp-opt" :disabled="curPlanCard.busy || planExpired"
                  @click="submitPlanApproval(pendingPlan, 'approve')">
            <span class="m-planp-no">1.</span>
            <span class="m-planp-kw">确认</span>
            <span class="m-planp-rest">直接按此计划开始执行</span>
          </button>
          <div class="m-planp-opt m-planp-custom">
            <span class="m-planp-no">2.</span>
            <textarea v-model="curPlanCard.editText" class="m-planp-ta" rows="2" :maxlength="8000"
                      :disabled="curPlanCard.busy || planExpired"
                      placeholder="不满意就继续对话：写下要改什么…"></textarea>
          </div>
        </div>
        <div class="m-planp-foot">
          <span class="m-planp-hint">「继续对话」按你的意见重做计划；「确认」才动手；不处理会超时并停止本轮</span>
          <span class="m-planp-actions">
            <button class="m-rec-btn ghost" type="button" :disabled="curPlanCard.busy"
                    @click="stop()">停止本轮</button>
            <button class="m-rec-btn ghost" type="button" :disabled="curPlanCard.busy || planExpired"
                    @click="submitPlanApproval(pendingPlan, 'cancel')">取消</button>
            <button class="m-rec-btn primary" type="button"
                    :disabled="curPlanCard.busy || planExpired || !(curPlanCard.editText || '').trim()"
                    @click="continuePlanConversation(pendingPlan, curPlanCard.editText)">继续对话</button>
          </span>
        </div>
      </div>

      <div v-else class="m-input-card">
        <textarea
          ref="ta"
          v-model="text"
          class="m-ta"
          rows="1"
          placeholder="问点什么？"
          enterkeyhint="enter"
          @paste="onComposerPaste"
          @input="autosize"
        ></textarea>
        <div class="m-tools">
          <button class="m-tool" type="button" title="模型与思考" @click="modelOpen = true">
            <thunderbolt-outlined :class="{ 'm-tool-on': deepThinkOn }" />
            <span class="m-tool-text">{{ effectiveModelLabel }}</span>
          </button>
          <!-- 工具条只留这两颗：模型名要占位，「+」是唯一的收口入口。计划/目标两颗药丸原先把这一排
               撑到 360px 固定宽（药丸缩不动，见 .m-tool 的 min-width），360px 屏上送出键直接画到卡外，
               现在收进「+」的「本轮模式」区，开启态改由输入框上方那颗 chip 说话。 -->
          <button class="m-tool" type="button" title="添加：模式 / 技能 / 引用 / 历史 / 附件" aria-label="添加：模式 / 技能 / 引用 / 历史 / 附件" @click="attachOpen = true">
            <plus-circle-outlined />
          </button>
          <span class="m-tools-sp" />
          <!-- 生成中且框里有字 → 这一点是「插话」（引擎只把它排进队列，不发请求）；没字才是停止本轮。
               与 PC 那颗键同一条判据（ChatInput 的 showStop）：想改方向不该被迫先停止这一轮。 -->
          <button v-if="loading && canSend" class="m-send" type="button"
                  title="插话：先排到上方队列，点那条上的「立即发送」才送进这一轮" aria-label="插话：先排到上方队列，点那条上的「立即发送」才送进这一轮" @click="send()">
            <arrow-up-outlined />
          </button>
          <button v-else-if="loading" class="m-send stop" type="button" title="停止生成" aria-label="停止生成" @click="stop()">
            <pause-circle-outlined />
          </button>
          <button v-else class="m-send" type="button" title="发送" aria-label="发送" :disabled="!canSend" @click="send()">
            <arrow-up-outlined />
          </button>
        </div>
      </div>
    </footer>

    <!-- ==================== sheet 族 ==================== -->
    <MobileSessionSheet :open="sessionsOpen" :current-id="currentSessionId || ''"
                        @close="sessionsOpen = false"
                        @select="onSelectSession"
                        @new-chat="onNewChat"
                        @profile="() => onGoPage('/profile')"
                        @help="() => onGoPage('/help')"
                        @artifacts="() => onGoPage('/artifacts')"
                        @knowledge="() => onGoPage('/knowledge')" />
    <MobileModelSheet :open="modelOpen" @close="modelOpen = false" />
    <MobileAttachSheet :open="attachOpen" @close="attachOpen = false" @goal="onGoalFromSheet" />

    <!-- 技能正文查看：与 PC 端同一个组件（sheet 关掉也留着，读正文不该被「+」面板的生命周期带走） -->
    <SkillContentModal :open="!!skillDoc" :name="skillDoc" :entry="skillDocEntry"
                       :session-id="currentSessionId || ''"
                       @update:open="v => { if (!v) skillDoc = '' }" />
    <MobileRefSheet :open="refOpen" @close="refOpen = false" @source="openSourceDetail" />
    <MobileNotifSheet :open="notifOpen" @close="notifOpen = false" />
    <MobileRoundSheet :open="roundOpen" :index="roundIdx" @close="roundOpen = false" @events="openRoundEvents" />
    <!-- 过程回放（事件账本）：顶栏入口看整个会话，单轮 sheet 里的那一项只看那一轮。
         取数与渲染与 PC 共用 SessionEventsPanel，两端不许各写一遍 -->
    <MobileEventsSheet :open="eventsOpen" :session-id="currentSessionId || ''" :turn-id="eventsTurnId"
                       :message="eventsMsg" :question="eventsQuestion" @close="eventsOpen = false"
                       @citation="openSourceDetail" @preview="openPreview" />

    <!-- ==================== 来源详情 ==================== -->
    <BottomSheet :open="src.visible" :title="src.title" max-height="72dvh" @close="src.visible = false">
      <div class="m-src">
        <a v-if="src.url" class="m-src-url" :href="src.url" target="_blank" rel="noopener">
          <global-outlined /> 打开原网页
        </a>
        <div v-if="src.loading" class="m-src-tip">正在读取原文…</div>
        <div v-if="src.snippet" class="m-src-snippet">{{ src.snippet }}</div>
        <!-- 原文配图按 PC 同口径内联回正文：先给无编号的 [图片] 补上序号，再让 renderMd 按下标还原。
             此前是把图单独排在正文上方 + 正文里留「[图片1]」四个字，读者对不上是哪张。 -->
        <div v-if="src.content" class="md m-src-content"
             v-html="renderMd(prepKnowledgeContent(src.content, src.images), src.images)" />
        <div v-if="!src.loading && !src.content && !src.snippet" class="m-src-tip">该来源没有可展示的内容</div>
      </div>
    </BottomSheet>

    <!-- ==================== 图片查看 ==================== -->
    <Teleport to="body">
      <div v-if="preview.list.length" class="m-lightbox" @click="closePreview">
        <img :src="preview.list[preview.idx]" class="m-lightbox-img" alt="图片" @click.stop />
        <button v-if="preview.list.length > 1" class="m-lb-nav prev" type="button" @click.stop="preview.idx = (preview.idx + preview.list.length - 1) % preview.list.length">‹</button>
        <button v-if="preview.list.length > 1" class="m-lb-nav next" type="button" @click.stop="preview.idx = (preview.idx + 1) % preview.list.length">›</button>
        <button class="m-lb-close" type="button" title="关闭" @click="closePreview">×</button>
        <div v-if="preview.list.length > 1" class="m-lb-count">{{ preview.idx + 1 }} / {{ preview.list.length }}</div>
      </div>
    </Teleport>

    <!-- ==================== 编辑重发（全屏编辑） ==================== -->
    <Teleport to="body">
      <div v-if="edit.open" class="m-edit">
        <header class="m-edit-bar">
          <button class="m-bar-btn" type="button" title="取消" aria-label="取消" @click="closeEdit"><close-outlined /></button>
          <div class="m-edit-title">编辑后从这一轮重新生成</div>
          <button class="m-edit-ok" type="button" :disabled="!edit.text.trim()" @click="confirmEdit">重新生成</button>
        </header>
        <textarea ref="editTa" v-model="edit.text" class="m-edit-ta" placeholder="修改你的问题…"></textarea>
      </div>
    </Teleport>

    <!-- ==================== 早期对话摘要（分隔线「查看摘要」，与 PC 弹窗同一份数据源） ==================== -->
    <BottomSheet :open="compactSummaryOpen" title="早期对话摘要"
                 subtitle="后续回答里的「更早的对话」就按这一份带入"
                 max-height="76dvh" @close="compactSummaryOpen = false">
      <div class="m-csum-stats">
        <div v-if="compactSummary && compactSummary.freed > 0" class="m-csum-stat">
          <span>本次腾出</span><b>约 {{ fmtTokens(compactSummary.freed) }} tokens</b>
        </div>
        <div v-if="compactSummary" class="m-csum-stat">
          <span>摘要规模</span><b>{{ fmtTokens(compactSummary.tokens) }} tokens</b>
        </div>
        <div v-if="compactSummary && compactSummary.keepTurns > 0" class="m-csum-stat">
          <span>逐字保留</span><b>最近 {{ compactSummary.keepTurns }} 次问答</b>
        </div>
      </div>
      <!-- 摘要正文本身是模型给的条目化 Markdown：按 Markdown 渲染，别用 pre 把 - 和 # 原样摊出来 -->
      <div class="md m-csum-md" v-html="renderMd(compactSummary ? compactSummary.text : '')"></div>
      <div class="m-csum-tip">完整问答仍保留在会话中，往上翻可回看；每次压缩都会把更早的对话并入这一份。</div>
      <div class="m-csum-acts">
        <button class="app-btn ghost small" type="button" @click="csumInstrOpen = !csumInstrOpen">
          <edit-outlined /> 带要求重新压缩
        </button>
        <button class="app-btn small" type="button"
                @click="copyText(compactSummary ? compactSummary.text : '', '已复制摘要')">
          <copy-outlined /> 复制摘要
        </button>
      </div>
      <div v-if="csumInstrOpen" class="m-csum-instr">
        <input v-model="csumInstr" class="m-csum-instr-input" maxlength="200" enterkeyhint="done"
               placeholder="这次摘要要保留什么、忽略什么" @keyup.enter="applyRecompact" />
        <button class="app-btn small" type="button" :disabled="compacting" @click="applyRecompact">
          {{ compacting ? '压缩中…' : '重新压缩' }}
        </button>
        <button class="app-btn ghost small" type="button" @click="csumInstrOpen = false">取消</button>
      </div>
    </BottomSheet>

    <!-- ==================== 粘贴文本全文（点粘贴卡片打开） ==================== -->
    <BottomSheet :open="pasteView.open" :title="pasteTitle(pasteView.name)"
                 :subtitle="pasteView.text.length + ' 字'" max-height="70dvh" @close="pasteView.open = false">
      <pre class="mc-sum-body">{{ pasteView.text }}</pre>
      <button class="m-paste-copy" type="button" @click="copyText(pasteView.text, '已复制全文')">
        <copy-outlined /> 复制全文
      </button>
    </BottomSheet>

    <!-- ==================== 反馈 ==================== -->
    <BottomSheet :open="fb.open" :title="fb.rating === 1 ? '这条回答有帮助' : '这条回答没帮助'" max-height="60dvh" @close="fb.open = false">
      <textarea v-model="fb.text" class="m-fb-ta" rows="3" placeholder="补充说明（选填）：哪里好 / 哪里不对？"></textarea>
      <button class="m-fb-ok" type="button" :disabled="fb.busy" @click="doFeedback">提交反馈</button>
    </BottomSheet>

    <!-- ==================== 目标改订（与 PC 条子里的就地编辑同一批字段、同一个保存出口） ====================
         只改「这件事要做到什么」：目标没有验收标准那套东西要维护，也不提供另起一条的口径
         ——换一句就地把原文改掉，保存后从下一轮起按新的继续跑。 -->
    <BottomSheet :open="goalEditing" title="改目标" max-height="78dvh" @close="goalEditing = false">
      <div class="m-goale">
        <div class="m-goale-label">这件事要做到什么</div>
        <textarea v-model="goalEdit.objective" class="m-goale-ta" rows="4" maxlength="4000"
                  placeholder="写清楚要达成什么；助手会自己一轮轮往下推进"></textarea>
        <div class="m-goale-foot">
          <span class="m-goale-hint">保存后从下一轮起按新目标继续</span>
          <button class="m-rec-btn primary" type="button" :disabled="goalEditBusy || !goalEditSaveable"
                  @click="saveGoalEdit">保存</button>
        </div>
      </div>
    </BottomSheet>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, provide, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  MenuOutlined, PlusOutlined, CaretDownOutlined, ArrowUpOutlined, ArrowDownOutlined, PauseCircleOutlined,
  PlayCircleOutlined,
  ThunderboltOutlined, PlusCircleOutlined, CloseOutlined, GlobalOutlined, SearchOutlined, UpOutlined, DownOutlined,
  BellOutlined, ExclamationCircleOutlined, FileTextOutlined, CopyOutlined, HistoryOutlined,
  CompressOutlined, EditOutlined, DeleteOutlined, LoadingOutlined, AimOutlined,
  OrderedListOutlined, CaretRightOutlined
} from '@ant-design/icons-vue'
import { pasteTitle, pasteSub, spawnAnchorOf, openDispatchCard } from '../chat/projections'
import { useChatEngine } from '../chat/useChatEngine'
import { useChatSearch } from '../chat/useChatSearch'
import { useNotifications } from '../chat/useNotifications'
import { useApprovalRecovery } from '../chat/useApprovalRecovery'
import { submitFeedback as apiSubmitFeedback, getKnowledgeDetail } from '../api'
import { renderMd, prepKnowledgeContent, enhanceDiagrams } from '../utils/markdown'
import { fmtTokens } from '../utils/token'
import { preferMobileShell } from './mobile'
import { addSheet, removeSheet } from './sheetStack'
import { startSessionTabSync, stopSessionTabSync } from '../views/store'
import BottomSheet from './BottomSheet.vue'
import MobileMsgRow from './MobileMsgRow.vue'
// 顶栏/输入卡上方的「子智能体」药丸：与 PC 壳共用同一份组件（触屏形态点开走底部 sheet）
import SubagentPill from '../components/SubagentPill.vue'
import MobileSessionSheet from './MobileSessionSheet.vue'
import MobileModelSheet from './MobileModelSheet.vue'
import MobileAttachSheet from './MobileAttachSheet.vue'
import MobileRefSheet from './MobileRefSheet.vue'
import MobileNotifSheet from './MobileNotifSheet.vue'
import MobileRoundSheet from './MobileRoundSheet.vue'
import MobileEventsSheet from './MobileEventsSheet.vue'
import SkillContentModal from '../components/SkillContentModal.vue'
import BrandMark from '../components/BrandMark.vue'
import NetBanner from '../components/NetBanner.vue'
import SetupGuide from '../components/SetupGuide.vue'
import WelcomeCopy from '../components/WelcomeCopy.vue'
import MsgDivider from '../components/MsgDivider.vue'
import { chatDone, refreshSetupGuide, setupGuide } from '../utils/setupGuide'

const router = useRouter()

// ==================== 滚动（引擎 hooks 的落点） ====================
const box = ref(null)
const stickToBottom = ref(true)
const AUTO_SCROLL_MARGIN = 80
// 子智能体药丸点一行：滚到回答里那张派遣卡并把它摊开（判据与 PC 壳共用 openDispatchCard）
function focusSpawn ({ run, msg }) {
  const t = run && run.t
  if (!t) return
  openDispatchCard(msg, t)
  const el = box.value && box.value.querySelector('[data-spawn="' + spawnAnchorOf(t) + '"]')
  if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
const onScroll = () => {
  const el = box.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < AUTO_SCROLL_MARGIN
}
const scroll = () => nextTick(() => {
  const el = box.value
  if (el && stickToBottom.value) el.scrollTop = el.scrollHeight
})
const scrollForce = () => nextTick(() => {
  const el = box.value
  if (el) { el.scrollTop = el.scrollHeight; stickToBottom.value = true }
})

const ta = ref(null)
// 输入框自适应高度（上限 132px 后内部滚动）
const autosize = () => {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 132) + 'px'
}

// ==================== sheet 开关（send 时统一收起） ====================
const sessionsOpen = ref(false)
const modelOpen = ref(false)
const attachOpen = ref(false)
const refOpen = ref(false)
const notifOpen = ref(false)
const roundOpen = ref(false)
const roundIdx = ref(-1)
// 任务清单默认收起成一计数行：长任务的清单常有十行，摊开会常驻占掉输入区上方那一屏
const todoOpen = ref(false)
const closeSheets = () => {
  sessionsOpen.value = false; modelOpen.value = false; attachOpen.value = false
  refOpen.value = false; notifOpen.value = false; roundOpen.value = false
}

// ==================== 站内通知（铃铛角标 + 通知 sheet） ====================
// 取数口径与 PC 铃铛共用一份（chat/useNotifications.js）：本页持实例（顶栏角标要用未读数），
// 经 provide 给 MobileNotifSheet —— 各调一次会造出两份未读数，角标与列表会各说各话
const notif = useNotifications()
provide('wqNotif', notif)
const notifUnread = notif.unreadCount

// ==================== 工具审批恢复（通知 → 会话） ====================
// 与 PC 壳顶部横幅同一份状态机：?approval=<id> 到达时重建审批卡（两端「批准/拒绝 → 重取状态」同口径）
const { approval: recApproval, busy: recBusy, statusText: recStatusText, statusClass: recStatusClass,
        resolve: resolveRecovery, dismiss: dismissRecovery } = useApprovalRecovery()

// 注：智能体提问不走这层横幅（与 PC 壳同口径）——卡片已落库、等待期间断线不中止本轮，
// 恢复由引擎在会话加载时按待答记录重建底部答题面板（hydratePendingAsk）。

// ==================== 引擎（与 PC 同一份） ====================
// 刻意不传 focusInput：移动端挂载即弹软键盘是反模式（PC 传它是为了键盘用户开箱可打）。
const engine = useChatEngine({
  chatPath: '/m/chat',
  scrollFollow: scroll,
  scrollForce,
  scrollSoft: scroll,
  closePanels: closeSheets,
  // 移动壳有计划卡了（底部 m-planp + 气泡 PlanCard），开关可以放行；分享页仍不给，
  // 那里没有裁决入口，开了只会发出一轮「没人能批准」的计划问答
  planModeEnabled: true
})
// sheet 族经 provide 取同一实例（useChatEngine 必须单例，不能各调一次）
provide('wqChat', engine)
const {
  text, canSend, send, stop, streamAnswer, messages, loading, currentSessionId, currentSessionTitle,
  panelAi,
  // 运行中插话：引擎的 send 在 loading 时本来就只入队、不发请求（PC 靠 ⌘/Ctrl+Enter 一步送），
  // 触屏没有修饰键可组合，所以「立即送」落在队列条目上那颗主按钮——不引入长按之类的隐藏手势
  steerQueue, sendSteer, editSteer, dropSteer,
  deepThinkOn, effectiveModelLabel, debugDisplayVisible, regenerate, switchBranch, variantSwitching,
  resolveApproval, askSubmitAll, createNewSession, pendingImages, pendingFiles, pickedSkills, pendingMentions,
  // 提问卡选择状态口径与 PC 同一份（单选/多选同一形状），面板只读不自己数
  pickAskOption, commitAskCustom, askOptionPicked, askCustomPicked, askQuestionAnswered,
  askAnsweredCount, askNextUnanswered, askAutoSubmittable, askHintText,
  pendingHistoryRefs, toggleSkill, removePendingImage, removePendingFile, removeMention, removeHistoryRef,
  skillDoc, loadSkills, skillList,
  takePastedFiles, takePastedText,
  ignoreAsk,
  // 计划模式（人在回路）：开关在「添加」sheet 的本轮模式区，开启态回显在输入框上方那颗 chip，
  // 确认栏在本页打字框原位，卡片本体共用 PlanCard
  planModeOn, setPlanMode, submitPlanApproval, continuePlanConversation,
  pendingPlan, curPlanCard, planExpired, planCountdownText,
  // 目标模式：摘要条常驻在输入卡上方，改原文开底部 sheet，目标卡在「状态与来源」sheet。
  // 词汇、显隐、动作可用性与编辑字段全在引擎侧（与 PC 共用一份），本页只画形状。
  // 没有待确认/验收/倒计时那一档——目标建好就直接跑，跑到头由助手自己停。
  sessionGoal, goalStateClass, startGoal,
  goalBarVisible, goalPhrase, goalBarMeta, goalBarError,
  goalCanPause, goalCanResume, goalCanEdit, runGoalAction,
  goalEditing, goalEdit, goalEditBusy, goalEditSaveable,
  startGoalInlineEdit, saveGoalEdit,
  // 任务清单：快照与「何时露/怎么计数」全吃引擎那一份，本页只留折叠开关。
  // 此前移动壳对 sessionTodos 零命中——数据两端都挂，渲染处只有 PC 一份
  sessionTodos, todoSummary, todoBarVisible,
  // 这一会话的历史有没有取回来（与「取回来了、就是空的」是两条判据，见引擎 historyError 注释）
  historyError, retryHistory,
  // 手动压缩上下文（入口在「模型与思考」sheet；分隔线与摘要 sheet 在本页。
  // 「压缩中」态由 compacting 一并表达；流尾那行进度只认手动那一路 manualCompacting）
  compacting, manualCompacting, compactNotice, compactContext, compactDividerAt, liveCompactAt,
  compactSummaryOpen, compactSummary, compactSummaryLoading, openCompactSummary, recompactWith,
  ready
} = engine

// 空态引导用：本轮 @ 引用的资料名（欢迎副标题会说明「只在这些资料里找答案」）
const mentionNames = computed(() => pendingMentions.value.map(m => m.name || m.id))

// 插话入队时引擎会把正文清掉，而 textarea 高度只在 @input 那一路重算（程序改值不触发 input 事件）。
// 不补这一下，输入卡会顶着一大块空白继续占位——屏幕上看着像输入框坏了。
// 必须排在引擎解构之后：text 是那里的 const，提前引用会撞暂时性死区、整个 setup 直接抛错。
watch(text, () => nextTick(autosize))

// ==================== 目标模式（移动壳） ====================
// 一句话创建：输入框有正文就直接建目标并开跑（PC 是按钮/命令取正文，同一条口径）。
// 建目标只有「这件事要做到什么」这一件事，没有验收标准草案要审——建完就往下跑。
const startGoalNowM = async () => {
  const obj = String(text.value || '').trim()
  if (!obj) { message.warning('先在输入框写一句这件事要做到什么'); return null }
  // 计划 × 目标互斥（每一轮只能一个模式提问）：定为目标这一句走目标模式，挂着的计划标记当场收掉（静默）
  if (planModeOn.value) setPlanMode(false)
  // 先清框、后建目标（与 PC 同口径）：建目标是一次异步请求（建行 + 框架同步 + 起首轮），
  // 等它回来再清，用户会盯着满框文字以为没发出去
  text.value = ''
  const g = await startGoal(obj)
  // 建失败就把那句还回来（这期间没打新字才还，不覆盖用户新写的东西）
  if (!g && !String(text.value || '').trim()) text.value = obj
  return g
}
/** 「+」→ 本轮模式里那颗「定为目标」：先把 sheet 收掉再走同一条创建口——
 *  接下来要看的是消息流和输入卡上方那条目标条，sheet 压在上面正好挡住。 */
const onGoalFromSheet = async () => {
  attachOpen.value = false
  return startGoalNowM()
}
// 技能正文弹窗：按名字现取清单里的那条（存快照会让停用/恢复后的按钮停在旧状态）
const skillDocEntry = computed(() => skillList.value.find(s => s.name === skillDoc.value) || {})

// ==================== 智能体提问面板（替换输入卡） ====================
// 与 PC 壳同语义：存在挂起提问时输入卡整块换成提问面板，答复后回归、问答记录留在气泡原位。
// 触屏没有 Tab/↑↓ 键盘导航，直接点选即可。
const pendingAsk = computed(() => {
  const list = messages.value
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    if (m && m.role === 'ai' && m.ask) return m
  }
  return null
})
// 排在当前卡之后的待答卡（与 PC 同一份队列语义：并行调用能同一轮挂出两张提问卡）
const mAskQueueCount = computed(() => ((pendingAsk.value && pendingAsk.value.askQueue) || []).length)
// 一卡多问：当前翻页 + 当前题 + 超时倒计时（已答计数走引擎 askAnsweredCount，与 PC 同一份判据）
const mAskPage = ref(0)
const mCurAsk = computed(() => {
  const a = pendingAsk.value?.ask
  if (!a || !a.questions.length) return { topic: '', question: '', options: [], multi: false }
  return a.questions[Math.min(mAskPage.value, a.questions.length - 1)] || { topic: '', question: '', options: [], multi: false }
})
// 底部那句提示：措辞与判据都在引擎那一份（askHintText），与 PC 一字不差；「已提交」态是移动壳自己多带的一句
const mAskHint = computed(() => {
  const a = pendingAsk.value?.ask
  if (!a) return ''
  if (a.answered) return '已提交，模型继续中…'
  return askHintText(a, mAskPage.value)
})
const mAskNow = ref(Date.now())
let mAskTimer = null
const mAskRemaining = computed(() => {
  const a = pendingAsk.value?.ask
  if (!a || !a.deadline) return 0
  return Math.max(0, a.deadline - mAskNow.value)
})
const mAskExpired = computed(() => {
  const a = pendingAsk.value?.ask
  return !!(a && a.deadline && !a.answered && mAskRemaining.value <= 0)
})
const mAskCountdownText = computed(() => {
  const a = pendingAsk.value?.ask
  if (!a || !a.deadline || a.answered) return ''
  const s = Math.ceil(mAskRemaining.value / 1000)
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return mm + ':' + ss
})
watch(() => pendingAsk.value?.ask, (a) => {
  mAskPage.value = 0
  // 挂卡先对表：后台标签页的 1s 心跳会被节流到分钟级，不校准则首屏倒计时按旧值算多出几分钟
  if (a && a.deadline) {
    mAskNow.value = Date.now()
    if (!mAskTimer) mAskTimer = setInterval(() => { mAskNow.value = Date.now() }, 1000)
  }
  else if (mAskTimer) { clearInterval(mAskTimer); mAskTimer = null }
}, { flush: 'post' })
watch(mAskExpired, (exp) => { if (exp && mAskTimer) { clearInterval(mAskTimer); mAskTimer = null } })
// 「关键词：说明」拆两段（关键词加粗、说明弱化）；无冒号时整句作关键词
const askOptionParts = (op) => {
  const s = String(op || '')
  const i = s.search(/[：:]/)
  return i > 0 ? { kw: s.slice(0, i), rest: s.slice(i + 1).replace(/^[：:]\s*/, '') } : { kw: s, rest: '' }
}
// 选完本题后跳到下一道未答题；都答完则停在末题（等待自动提交）。「答了没」只问引擎那一份判据
const advanceM = (page) => {
  const a = pendingAsk.value?.ask
  if (!a) return
  const next = askNextUnanswered(a, page)
  mAskPage.value = next >= 0 ? next : a.questions.length - 1
}
// 点一个候选：单选=记录并自动跳下一题；多选=就地勾上/取消、留在本题（触屏同样不跳题，
// 勾完一个就翻页会让用户以为本题已答完）
const onPickM = (oi) => {
  const m = pendingAsk.value
  if (!m || !m.ask || m.ask.busy || m.ask.answered || mAskExpired.value) return
  pickAskOption(m, mAskPage.value, oi)
  if (mCurAsk.value.multi) return
  advanceM(mAskPage.value)
}
// 确认某题自定义答案：回车确认并跳下一题；失焦只确认不跳题（避免打字后点选项时记错题）。
// 多选题不跳题——自定义那行在多选里只是其中一个勾选项
const commitCustomM = (page, doAdvance) => {
  const m = pendingAsk.value
  if (!m || !m.ask || m.ask.busy || m.ask.answered || mAskExpired.value) return
  commitAskCustom(m, page)
  const q = m.ask.questions[page]
  if (doAdvance && q && !q.multi && askQuestionAnswered(m.ask, page)) advanceM(page)
}
// 整卡都是单选且都答过 → 自动提交（提交后 busy/answered 置位，条件不再满足，只触发一次）；
// 含多选题的卡永不自动提交，等用户点提交
watch(() => {
  const a = pendingAsk.value?.ask
  return !!(a && !a.busy && !a.answered && !mAskExpired.value && askAutoSubmittable(a))
}, (done) => { if (done) askSubmitAll(pendingAsk.value) })

// ==================== 手动压缩上下文（/compact 的移动入口） ====================
// 移动壳没有 / 命令面板，入口落在「模型与思考」sheet 的上下文区（与窗口档位同处一块）；
// 压缩记录以分隔线留在对话流里（判据、摘要面板数据源都在引擎，与 PC 同一份）
const csumInstrOpen = ref(false)
const csumInstr = ref('')
const applyRecompact = async () => {
  // 压完了才收起输入行：没得压/失败的原因引擎已就地提示，输入留着方便改一句再试
  if (await recompactWith(csumInstr.value.trim())) csumInstrOpen.value = false
}

// 工具条那颗模型名不再在 JS 里硬截：过去截到 10 字让药丸宽度恒定，也把 CSS 那颗省略号
// 变成从不生效的死代码（缩不动的是药丸本身，见 .m-tool 的 min-width）。宽度交给 CSS 收。
const hasChips = computed(() => planModeOn.value || pendingImages.value.length || pendingFiles.value.length
  || pickedSkills.value.length || pendingMentions.value.length || pendingHistoryRefs.value.length)

const ask = q => { text.value = q; nextTick(send) }

// ==================== 交互细节 ====================
const activeIdx = ref(null)
const preview = reactive({ list: [], idx: 0 })
const openPreview = (list, idx) => { preview.list = list || []; preview.idx = idx || 0 }
const closePreview = () => { preview.list = []; preview.idx = 0 }

// 输入框粘贴：文件与图片挂成待发送附件，整段长文本转成「粘贴的文本」附件（短粘贴照常内联，可继续编辑）
const onComposerPaste = e => { if (takePastedFiles(e)) return; takePastedText(e) }
// 粘贴卡片点开看全文：正文只在内存里（随问答落库的是名称/体积），
// 刷新后的历史卡片没有它 —— 明确说明，而不是点一下没反应
const pasteView = reactive({ open: false, name: '', text: '' })
const openPasteView = a => {
  if (!a) return
  if (!a.text) { message.info('这条粘贴文本的正文未随历史记录保留，无法回看'); return }
  pasteView.name = a.name
  pasteView.text = a.text
  pasteView.open = true
}

const copyText = async (txt, okMsg = '已复制到剪贴板') => {
  if (!txt) { message.warning('没有可复制的内容'); return }
  try {
    if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(txt); message.success(okMsg) }
    else { message.warning('当前环境不支持自动复制，请长按选择文本') }
  } catch (e) { message.warning('复制失败，请长按选择文本') }
}
const copyAnswer = m => copyText((m?.content || '').trim())
const copyUserMessage = m => copyText((m?.content || '').trim())

// 会话切换：引擎的 switchSession 会同步 URL 与历史（含流式中会话的活流接回）
const onSelectSession = async sid => {
  sessionsOpen.value = false
  if (sid !== currentSessionId.value) await engine.switchSession(sid)
}
const onNewChat = async () => { sessionsOpen.value = false; await createNewSession() }

// 壳外页面跳转（个人设置/帮助中心）：这些页面本就在窄屏白名单里（DesktopOnlyGuard），
// 移动壳过去只需给入口——落在 AppLayout 的 H5 外壳形态（顶栏标题随路由，左上角菜单仍是
// 这张会话 sheet，见 AppLayout 的 mobileShell 分支），不会掉回 PC 抽屉
const onGoPage = path => { closeSheets(); router.push(path).catch(() => {}) }

// 单轮操作（消息操作行的「⋯」）：先标选中（操作行保持可见，看得出在操作哪一条），再开 sheet。
// 目标下标记在 roundIdx 上、sheet 只读它——不读 activeIdx：sheet 关掉后用户上翻，选中态变化不该影响已关闭的 sheet
const openRound = i => { activeIdx.value = i; roundIdx.value = i; roundOpen.value = true }

// ==================== 过程回放（事件账本） ====================
// 顶栏入口=这个会话最近几轮跑了什么；单轮 sheet 的入口=只看那一轮（带消息的 turnId）。
// 从单轮过来先收掉操作 sheet：两层面板叠着没有意义，用户此刻要看的是过程不是操作项
const eventsOpen = ref(false)
const eventsTurnId = ref('')
// 对照视图的那一半：从单轮 sheet 进来时带上了那条回答与它的问题
const eventsMsg = ref(null)
const eventsQuestion = ref('')
const openEvents = (turnId, msg, question) => {
  eventsTurnId.value = typeof turnId === 'string' ? turnId : ''
  eventsMsg.value = eventsTurnId.value ? (msg || null) : null
  eventsQuestion.value = eventsTurnId.value ? (question || '') : ''
  eventsOpen.value = true
}
const openRoundEvents = turnId => {
  roundOpen.value = false
  const mi = roundIdx.value
  const ai = messages.value[mi]
  let q = ''
  for (let i = mi - 1; i >= 0; i--) {
    const r = messages.value[i]?.role
    if (r === 'user') { q = messages.value[i].content || ''; break }
    if (r === 'assistant' || r === 'ai') break
  }
  openEvents(turnId, ai, q)
}

// ==================== 会话内查找 ====================
// 与 PC 的 Ctrl/⌘+F 是同一份实现（src/chat/useChatSearch.js）：行契约统一为 [data-row-index]
// （MobileMsgRow 已带），高亮共用全局 app.css 的 ::highlight(chat-search)。
// 差异只在入口——手机没有组合键，顶栏给一个按钮。
const {
  searchOpen, searchQuery, searchPos, searchInputRef, matchedIdxs, searchPosShown,
  openSearch, closeSearch, gotoMatch
} = useChatSearch({
  boxRef: box,
  messages: () => messages.value
})

// ==================== 新问题置顶（移动等价实现） ====================
// 桌面版靠「尾随留白」把贴底落点抬到最后一条问题的顶部（ChatPage.vue 的 updateTailSpacer）。
// 移动端刻意不做留白：一屏只放得下 1-2 条，补足往往需要近一屏的空白，用户上翻回看历史时
// 会在内容尾拖着一大块空白，观感像"消息丢了"；且那套算法每次重渲染都要 getBoundingClientRect
// 反推一次，手机上更容易掉帧。
// 等价做法：用户消息出现的那一刻直接把它送到容器顶端 —— 效果一致，且不依赖测量。
let lastPinnedIdx = -1
const pinLatestQuestion = async () => {
  const list = messages.value
  const idx = list.length - 1
  const m = list[idx]
  if (!m || m.role !== 'user' || idx === lastPinnedIdx) return
  lastPinnedIdx = idx
  await nextTick()
  box.value?.querySelector(`[data-row-index="${idx}"]`)?.scrollIntoView({ block: 'start' })
  // 发新问题即宣告"我要看这一轮"：答案流出后继续跟随（否则上翻过的用户会看不到半截回答）
  stickToBottom.value = true
}
watch(() => messages.value.length, pinLatestQuestion)

// 切走会话/清空选中：操作行回到「最新一条可见」的默认态，滚动恢复跟随
watch(currentSessionId, () => {
  activeIdx.value = null
  stickToBottom.value = true
  lastPinnedIdx = -1
  searchPos.value = 0   // 换会话后查找从头开始，避免停在上一个会话的偏移上
})

// 来源详情（移动端用 sheet 展示，替代 PC 的弹窗）
const src = reactive({ visible: false, title: '', snippet: '', content: '', images: [], url: '', loading: false })
const openSourceDetail = async s => {
  if (!s) return
  src.visible = true
  src.url = ''
  src.images = []
  src.content = ''
  src.loading = false
  const isExternal = s.origin === 'WEB' || s.origin === 'MCP'
  if (isExternal) {
    src.title = (s.siteName || (s.origin === 'MCP' ? 'MCP 来源' : '联网来源')) + (s.title ? ' §' + s.title : '')
    src.snippet = s.snippet || '（该来源未提供摘要）'
    src.url = s.url || ''
    return
  }
  src.title = (s.fileName || (s.docId ? '来源文档不可用' : '手动补充的知识')) + (s.title ? ' §' + s.title : '')
  src.snippet = s.snippet || '（无原文片段）'
  src.images = Array.isArray(s.images) ? s.images : []
  src.loading = true
  try {
    const r = await getKnowledgeDetail(s.knowledgeId)
    if (r.success && r.data) {
      src.content = r.data.content || ''
      if (Array.isArray(r.data.images)) src.images = r.data.images
      if (r.data.title) src.title = (s.fileName || '来源') + ' §' + r.data.title
    }
  } catch (e) { /* 接口失败回退 snippet */ } finally { src.loading = false }
}

// 反馈：移动端用 sheet（可选补充说明）
const fb = reactive({ open: false, rating: 1, text: '', busy: false, msg: null })
const submitFeedback = (m, rating) => {
  if (m.fb != null) { message.warning('同一回答只能评价一次'); return }
  fb.msg = m; fb.rating = rating; fb.text = ''; fb.open = true
}
const doFeedback = async () => {
  const m = fb.msg
  if (!m || !m.messageId) { message.warning('该回答不可反馈'); return }
  fb.busy = true
  try {
    const r = await apiSubmitFeedback(m.messageId, fb.rating, fb.text.trim())
    if (r.success) { m.fb = fb.rating; message.success('感谢反馈'); fb.open = false }
    else message.error(r.msg || '提交失败')
  } catch (e) { message.error(e.message || '提交失败') } finally { fb.busy = false }
}

// 编辑重发（全屏编辑，正文可改；随行的技能/@/历史/深度思考沿用原轮）
const edit = reactive({ open: false, idx: null, mid: '', text: '' })
const startEdit = mi => {
  const m = messages.value[mi]
  if (!m || m.role !== 'user') return
  if (loading.value) { message.warning('当前正在回答，请先停止或稍候'); return }
  if (!m.messageId) { message.warning('该消息尚未落库，暂不能编辑重发'); return }
  edit.idx = mi; edit.mid = m.messageId; edit.text = m.content || ''; edit.open = true
}
const closeEdit = () => { edit.open = false; edit.idx = null; edit.mid = ''; edit.text = '' }
// 编辑态按下标定位，而切会话/新建/切分支/删当前会话都会整体替换消息列表。不绑回原消息
// 就会让全屏编辑开在另一个会话的第 N 条上，确认时把新分支建在错的会话里
watch(messages, () => {
  if (edit.idx == null) return
  if ((messages.value[edit.idx] || {}).messageId !== edit.mid) closeEdit()
})

// ==================== 两个全屏覆盖层接进 sheet 栈 ====================
// 图片查看与全屏编辑不是 BottomSheet（整屏、无抓手，形状不同），但同样得吃系统返回键，
// 所以按同一套 API 各登记一层。baseZ 与它们各自的 CSS 对齐（1300 / 1250），
// 保证「后开的在上面」与「后开的先关」是同一个顺序——事件 sheet 里点图就是这种叠法。
let lbToken = 0
watch(() => preview.list.length, n => {
  if (n && !lbToken) lbToken = addSheet(closePreview, 1300)
  else if (!n && lbToken) { removeSheet(lbToken); lbToken = 0 }
})
let editToken = 0
watch(() => edit.open, v => {
  if (v && !editToken) editToken = addSheet(closeEdit, 1250)
  else if (!v && editToken) { removeSheet(editToken); editToken = 0 }
})
const confirmEdit = () => {
  const mi = edit.idx
  const old = mi != null ? messages.value[mi] : null
  const txt = edit.text.trim()
  if (!old || old.role !== 'user') { closeEdit(); return }
  if (!txt) { message.warning('内容不能为空'); return }
  if (txt === old.content) { closeEdit(); return }   // 无改动：不产生新分支
  if (loading.value) { message.warning('当前正在回答，请先停止或稍候'); return }
  const editMessageId = old.messageId
  if (!editMessageId) { message.warning('该消息尚未落库，暂不能编辑重发'); return }
  closeEdit()
  const skills = Array.isArray(old.skills) ? old.skills : []
  const historyRefs = Array.isArray(old.historyRefs) ? old.historyRefs : []
  const deep = !!old.deepThink
  const atts = Array.isArray(old.attachData) ? old.attachData : []
  const attsMeta = Array.isArray(old.attachments) ? old.attachments : []
  const mentionsNew = Array.isArray(old.mentions) ? old.mentions : []
  const imgsAll = old.images || []
  const imgs = imgsAll.filter(u => String(u).startsWith('data:'))
  messages.value = messages.value.slice(0, mi)
  const verN = (old.variantCount || 1) + 1
  const nu = reactive({
    role: 'user', content: txt, images: imgsAll, attachments: attsMeta, attachData: atts,
    skills, mentions: mentionsNew, historyRefs, deepThink: deep,
    time: Date.now(), messageId: null, variantCount: verN, variantIndex: verN
  })
  messages.value.push(nu)
  streamAnswer(txt, imgs, null, false, 1, deep, atts, skills, mentionsNew, null, historyRefs, editMessageId, nu)
}

// 流式结束后补画 mermaid（v-html 之后；流式期间围栏是半截代码，画必然失败）
watch([messages, loading], async () => {
  if (loading.value) return
  await nextTick()
  if (box.value) enhanceDiagrams(box.value).catch(() => {})
})

// 设备形态自检：从移动壳被拖大到桌面宽度（或触屏变鼠标）时回到 PC 布局。
// 只在 Android/iPad 分屏等真实场景兜底；桌面用户永远不会进到这里（守卫已拦）。
const onViewportChange = () => { if (!preferMobileShell()) router.replace({ path: '/chat' }).catch(() => {}) }

onMounted(async () => {
  window.addEventListener('resize', onViewportChange, { passive: true })
  // 移动壳不挂 AppLayout 工作台外壳，跨页签订阅得自己挂一份（幂等）：
  // 不挂的话同一个浏览器里 PC 页签改了列表，手机形态这一栏要刷新才跟得上
  startSessionTabSync()
  refreshSetupGuide()   // 空态引导卡状态（TTL 15s 去重；PC 由 AppLayout/ChatPage 触发，移动壳不经那边）
  await ready()
  autosize()
})
onUnmounted(() => {
  window.removeEventListener('resize', onViewportChange)
  stopSessionTabSync()
  // 全屏层随页面走：栈里不能留死条目，否则下一次返回会去关一个已经不存在的界面
  if (lbToken) { removeSheet(lbToken); lbToken = 0 }
  if (editToken) { removeSheet(editToken); editToken = 0 }
})
</script>

<style scoped>
/* 满高容器：--app-vh 由 h5/keyboard.js 写（视觉视口高，已扣软键盘）——
   键盘弹起时容器收缩，输入区自然上移，不会出现「fixed 贴在布局视口底部被键盘盖住」。 */
.m-chat {
  position: relative;   /* .m-jump 的定位基准 */
  height: 100vh; height: var(--app-vh, 100dvh);
  display: flex; flex-direction: column;
  background: var(--app-bg); color: var(--app-text);
  overflow: hidden;
  /* 正文列宽上限：平板横屏（iPad 1024×768 也是触屏且宽 ≤1024，落进移动壳）不封顶就会把
     一行正文拉到 1000px 宽，眼睛回不了行。手机取 100% —— 这条对 ≤720px 的屏零影响。 */
  --m-col: min(100%, 720px);
}
.m-list > *, .m-composer > *, .m-find, .m-rec { width: var(--m-col); margin-inline: auto }

/* ---- 顶栏（安全区让开刘海）---- */
.m-bar {
  flex: none; display: flex; align-items: center; gap: 4px;
  padding: 6px 8px; padding-top: calc(6px + var(--sat, 0px));
  background: var(--app-panel); border-bottom: 1px solid var(--app-border);
}
.m-bar-btn {
  width: 40px; height: 40px; flex: none; border: none; border-radius: 10px; background: transparent;
  color: var(--app-text2); font-size: 18px; display: inline-flex; align-items: center; justify-content: center;
  position: relative; touch-action: manipulation;
}
.m-bar-btn:active { background: var(--app-accent-weak); color: var(--app-accent); }
.m-bar-dot { position: absolute; top: 8px; right: 8px; width: 7px; height: 7px; border-radius: 50%; background: var(--app-accent); }
/* 未读角标：数字形态（PC 铃铛是 a-badge，这里自绘保持移动壳零 antd 组件依赖） */
.m-bar-badge {
  position: absolute; top: 3px; right: 3px; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--app-danger); color: #fff;
  font-size: 10px; line-height: 16px; font-weight: 600; text-align: center;
}
.m-bar-title {
  flex: 1; min-width: 0; display: inline-flex; align-items: center; justify-content: center; gap: 4px;
  border: none; background: transparent; color: var(--app-text); font-size: 15px; font-weight: 600;
  min-height: 40px; touch-action: manipulation;
}
.m-bar-t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 70%; }
.m-bar-caret { font-size: 11px; color: var(--app-text3); }

/* ---- 会话内查找条 ---- */
.m-find {
  flex: none; display: flex; align-items: center; gap: 6px;
  padding: 6px 8px; background: var(--app-panel); border-bottom: 1px solid var(--app-border);
}
.mf-ic { color: var(--app-text3); flex: none; font-size: 13px; }
.mf-input {
  flex: 1; min-width: 0; min-height: 36px; box-sizing: border-box;
  border: 1px solid var(--app-border); border-radius: 9px; background: var(--app-panel-2);
  color: var(--app-text); font-size: 16px; padding: 0 10px; outline: none;
}
.mf-input:focus { border-color: var(--app-accent-border); }
.mf-count { flex: none; font-size: 12px; color: var(--app-text3); min-width: 40px; text-align: right; }
.mf-btn {
  width: 34px; height: 34px; flex: none; border: none; border-radius: 9px; background: transparent;
  color: var(--app-text2); font-size: 12px; display: inline-flex; align-items: center; justify-content: center;
  touch-action: manipulation;
}
.mf-btn:disabled { color: var(--app-text3); opacity: .5; }
.mf-btn:active { background: var(--app-accent-weak); color: var(--app-accent); }

/* ---- 工具审批恢复横幅（?approval= 深链）---- */
.m-rec {
  flex: none; margin: 8px 10px 0; padding: 10px 12px;
  border: 1px solid var(--app-warn-border); background: var(--app-warn-weak); border-radius: 10px;
  display: flex; flex-direction: column; gap: 8px;
}
.m-rec-title { font-size: 13px; font-weight: 600; color: var(--app-warn-text); display: flex; align-items: center; gap: 6px; }
.m-rec-args {
  margin: 0; background: var(--app-panel); border: 1px solid var(--app-warn-border); border-radius: 8px;
  padding: 8px; font-size: 12px; font-family: "SF Mono", Menlo, monospace;
  white-space: pre-wrap; word-break: break-all; max-height: 120px; overflow-y: auto; color: var(--app-text2);
}
.m-rec-foot { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.m-rec-status { font-size: 12px; margin-right: auto; }
.m-rec-status.rec-pending { color: var(--app-warn-text); }
.m-rec-status.rec-ok { color: var(--app-ok); }
.m-rec-status.rec-err { color: var(--app-danger); }
.m-rec-btn {
  min-height: 36px; padding: 0 14px; border: 1px solid var(--app-border); border-radius: 9px;
  background: var(--app-panel); color: var(--app-text2); font-size: 13px; touch-action: manipulation;
}
.m-rec-btn:disabled { opacity: .55; }
.m-rec-btn.primary { background: var(--app-accent); border-color: var(--app-accent); color: #fff; }
.m-rec-btn.primary:disabled { background: var(--app-accent-disabled); border-color: var(--app-accent-disabled); }
.m-rec-btn.ghost { border-style: dashed; color: var(--app-text3); }

/* ---- 消息流 ---- */
.m-list {
  flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior-y: contain;
  -webkit-overflow-scrolling: touch;
  padding: 12px 12px 8px;
}
.m-welcome { display: flex; flex-direction: column; align-items: center; padding: 20px 0 8px; }
/* 历史取数失败卡：整卡中性（坏的是这一次请求，不是这条会话），只有图标用危险色。
   重试按 44px 热区口径——它是这块屏幕上唯一该被点中的东西。 */
.m-herr {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  margin: 20px auto 8px; padding: 18px 16px 20px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 12px;
  text-align: center;
}
.mhe-ic { font-size: 22px; color: var(--app-danger); }
.mhe-t { font-size: 15px; font-weight: 600; color: var(--app-text); }
.mhe-s { font-size: 12px; color: var(--app-text3); line-height: 1.6; word-break: break-word; }
.mhe-btn {
  width: 100%; margin-top: 10px; min-height: 44px; border: none; border-radius: 12px;
  background: var(--app-accent); color: #fff; font-size: 15px; touch-action: manipulation;
}
.mhe-btn:active { opacity: .82; }
/* 示例卡组件自带 12px 横向内边距（PC 窄屏布局用语）；在列表已加内边距时取消，避免双层缩进 */
.m-brand { font-size: 40px; }
.m-welcome-title { margin-top: 12px; font-size: 19px; font-weight: 600; }
.m-welcome-sub { margin: 6px 0 16px; font-size: 13px; color: var(--app-text3); text-align: center; }
/* 配置引导卡：SetupGuide 是 PC 宽度设计（max-width 560px），容器内收边并左对齐文字 */
.m-setup { width: 100%; max-width: 460px; padding: 0 4px; box-sizing: border-box; text-align: left; }
.m-jump {
  position: absolute; right: 14px; bottom: calc(150px + var(--sab, 0px) + var(--kb, 0px));
  width: 40px; height: 40px; border-radius: 50%; border: 1px solid var(--app-border);
  background: var(--app-panel); color: var(--app-text2); font-size: 16px; box-shadow: var(--app-shadow);
  display: inline-flex; align-items: center; justify-content: center; touch-action: manipulation; z-index: 5;
}

/* ---- 上下文压缩：流内分隔线 + 摘要 sheet ---- */
.m-cdiv { margin: 2px 0 8px; }
.m-cdiv-tail { flex: none; margin: 0 12px 6px; }
.m-csum-stats {
  display: flex; flex-wrap: wrap; gap: 4px 16px; margin-bottom: 8px;
  padding: 8px 10px; border-radius: 8px; background: var(--app-panel-2);
}
.m-csum-stat { display: inline-flex; align-items: baseline; gap: 6px; font-size: 12px; }
.m-csum-stat span { color: var(--app-text3); }
.m-csum-stat b { color: var(--app-text); font-weight: 500; }
/* 摘要正文按 Markdown 渲染（模型产出的就是条目化列表） */
.m-csum-md {
  max-height: 40dvh; overflow: auto; padding: 10px 12px; border-radius: 8px;
  background: var(--app-panel-2); border: 1px solid var(--app-border); font-size: 13px; line-height: 1.7;
}
.m-csum-tip { margin: 8px 0; font-size: 12px; color: var(--app-text3); line-height: 1.6; }
.m-csum-acts { display: flex; gap: 8px; }
.m-csum-instr { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.m-csum-instr-input {
  flex: 1; min-width: 0; padding: 8px 10px; font-size: 13px;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); color: var(--app-text);
}
/* 粘贴全文 sheet 仍用这份 pre 容器 */
.mc-sum-body {
  margin: 0; max-height: 52dvh; overflow: auto; padding: 10px 12px; border-radius: 8px;
  background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text2);
  font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word;
}

/* ---- 输入区 ---- */
.m-composer {  /* 键盘弹起（--kb>0）时容器已按视觉视口收缩，安全区不再叠加——max() 让两者互斥 */
  flex: none; padding: 6px 10px calc(8px + max(0px, var(--sab, 0px) - var(--kb, 0px)));
  background: var(--app-panel); border-top: 1px solid var(--app-border);
}
.m-chips { display: flex; flex-wrap: wrap; gap: 6px; padding: 2px 2px 8px; }
.m-chip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 100%;
  font-size: 12px; color: var(--app-text2); background: var(--app-panel-2);
  border: 1px solid var(--app-border); border-radius: 999px; padding: 4px 6px 4px 10px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.m-chip-skill { background: var(--app-info-weak); border-color: var(--app-info-border); color: var(--app-accent); }
.m-chip-mention { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); }
/* 计划模式开启态：警示色——它改变的是这一轮要不要先出计划，不是「又一个开关」，
   与底部计划确认栏的「待确认」标同一色源（原来收在工具条药丸上时也是这个色） */
.m-chip-plan { background: var(--app-warn-weak); border-color: var(--app-warn-border); color: var(--app-warn-text); }
.m-chip-img { padding: 0 0 0 0; border: none; background: transparent; position: relative; }
.m-chip-img img { width: 46px; height: 46px; object-fit: cover; border-radius: 8px; display: block; }
.m-chip-del {
  width: 26px; height: 26px; flex: none; border: none; border-radius: 50%; background: transparent;
  color: var(--app-text3); font-size: 15px; line-height: 1; touch-action: manipulation;
}
.m-chip-img .m-chip-del { position: absolute; top: -6px; right: -6px; background: var(--app-panel); box-shadow: var(--app-shadow-sm); }
/* 长文本粘贴卡片：图标块 + 名称与体积/字数两行，点一下看全文（普通附件仍是单行 chip） */
.m-paste {
  display: flex; align-items: center; gap: 8px; max-width: 250px;
  padding: 6px 2px 6px 8px; border-radius: 10px;
  background: var(--app-panel-2); border: 1px solid var(--app-border);
}
.m-paste-ic {
  flex: none; width: 30px; height: 30px; border-radius: 7px; font-size: 15px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel); border: 1px solid var(--app-border); color: var(--app-accent);
}
.m-paste-txt { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.m-paste-name { font-size: 13px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-paste-sub { font-size: 11px; color: var(--app-text3); }
.m-paste-copy {
  width: 100%; margin-top: 10px; min-height: 44px; border: 1px solid var(--app-border);
  border-radius: 12px; background: var(--app-panel-2); color: var(--app-text); font-size: 14px;
  touch-action: manipulation;
}
.m-input-card {
  border: 1px solid var(--app-border); border-radius: 14px; background: var(--app-panel-2);
  padding: 6px 8px 6px;
}
.m-input-card:focus-within { border-color: var(--app-accent-border); }

/* ==================== 智能体提问面板（替换输入卡） ==================== */
.m-askp { padding: 12px; display: flex; flex-direction: column; gap: 10px; }
.m-askp-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.m-askp-tag { flex: none; font-size: 12px; line-height: 1; padding: 5px 9px; border-radius: 999px; background: var(--app-panel); border: 1px solid var(--app-border); color: var(--app-text2); }
.m-askp-q { font-size: 14px; font-weight: 600; color: var(--app-text); }
/* 「可多选」与勾选框与 PC 的 askp-multi / askp-tick 同一套语义（触屏框更大一号）：
   勾上=这一项进答案，再点取消；单选题不画框，一行只可能留一项 */
.m-askp-multi { flex: none; font-size: 11px; line-height: 1.5; padding: 1px 6px; border-radius: 999px; background: var(--app-panel); border: 1px solid var(--app-border); color: var(--app-text2); }
.m-askp-tick { flex: none; width: 17px; height: 17px; margin-top: 2px; border: 1.5px solid var(--app-border); border-radius: 4px; background: var(--app-panel); display: inline-flex; align-items: center; justify-content: center; box-sizing: border-box; }
.m-askp-tick.on { background: var(--app-accent, var(--app-ok)); border-color: var(--app-accent, var(--app-ok)); }
.m-askp-tick.on::after { content: ''; display: block; width: 8px; height: 4px; margin-top: -2px; border-left: 2px solid #fff; border-bottom: 2px solid #fff; transform: rotate(-45deg); }
.m-askp-state.warn { font-size: 12px; color: var(--app-warn); }
.m-askp-timer { flex: none; display: inline-flex; align-items: center; gap: 4px; margin-left: auto; font-size: 12px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.m-askp-timer.warn { color: var(--app-warn); }
.m-askp-pager { flex: none; margin-left: 8px; display: inline-flex; align-items: center; gap: 2px; }
.m-askp-page-btn { width: 24px; height: 24px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text2); border-radius: 6px; font-size: 15px; line-height: 1; padding: 0; }
.m-askp-page-btn:disabled { opacity: 0.4; }
.m-askp-page-num { min-width: 36px; text-align: center; font-size: 12px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
.m-askp-opts { display: flex; flex-direction: column; gap: 2px; }
.m-askp-opt { display: flex; align-items: flex-start; gap: 8px; width: 100%; text-align: left; background: none; border: none; border-radius: 8px; padding: 9px 8px; font-size: 14px; line-height: 1.55; color: var(--app-text); touch-action: manipulation; }
.m-askp-opt.sel { background: var(--app-panel); box-shadow: inset 2px 0 0 var(--app-accent, var(--app-ok)); }
.m-askp-opt:active { background: var(--app-panel); }
.m-askp-opt:disabled { opacity: 0.55; }
.m-askp-no { flex: none; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.m-askp-kw { font-weight: 600; }
.m-askp-rec { font-weight: 600; color: var(--app-ok); }
/* —— 计划确认栏（与提问面板同形，顶替输入卡）：正文在气泡的执行计划卡里，这里只放裁决 —— */
.m-planp { padding: 12px; display: flex; flex-direction: column; gap: 10px; }
.m-planp-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.m-planp-tag { flex: none; font-size: 12px; line-height: 1; padding: 5px 9px; border-radius: 999px; background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); color: var(--app-warn-text); }
.m-planp-state { font-size: 12px; color: var(--app-warn-text); }
.m-planp-state.warn { color: var(--app-warn); }
.m-planp-timer { flex: none; margin-left: auto; font-size: 12px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.m-planp-timer.warn { color: var(--app-warn); }
.m-planp-opts { display: flex; flex-direction: column; gap: 6px; }
.m-planp-opt { display: flex; align-items: flex-start; gap: 8px; width: 100%; text-align: left; background: none; border: none; border-radius: 8px; padding: 10px 8px; min-height: 44px; font-size: 14px; line-height: 1.55; color: var(--app-text); touch-action: manipulation; }
.m-planp-opt:active { background: var(--app-panel); }
.m-planp-opt:disabled { opacity: 0.55; }
.m-planp-no { flex: none; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.m-planp-kw { font-weight: 600; }
.m-planp-rest { color: var(--app-text2); }
.m-planp-custom { border: 1px solid var(--app-border); background: var(--app-panel); align-items: flex-start; }
.m-planp-ta { flex: 1; min-width: 0; border: none; outline: none; background: none; resize: none; font: inherit; font-size: 14px; line-height: 1.55; color: var(--app-text); padding: 0; }
.m-planp-ta:disabled { color: var(--app-text3); }
.m-planp-foot { display: flex; flex-direction: column; gap: 8px; }
.m-planp-hint { font-size: 12px; color: var(--app-text3); }
.m-planp-actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
/* 目标条（常驻摘要条，与 PC 那一条同一套词汇与判据）：压在输入卡正上方，两行——
   第一行状态药丸＋目标原文（单行截断）＋三颗图标动作，第二行是 PC 收在悬浮里的那些数（触屏没有 hover）。 */
.m-goalbar {
  display: flex; flex-direction: column; gap: 2px;
  border: 0; border-radius: 12px; background: var(--app-panel);
  padding: 6px 6px 6px 10px; margin-bottom: 8px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 8px 20px -10px rgba(16, 24, 40, .10);
}
.m-gb-row { display: flex; align-items: center; gap: 8px; min-width: 0; }
/* 状态药丸是只读的：状态词由引擎那一份给出，颜色只表达「在跑 / 停住」，不接受点按
   （验收、收尾这些裁决动作已经退役，别把状态显示做成按钮） */
.m-gb-pill {
  flex: none; display: inline-flex; align-items: center; gap: 4px;
  font-size: 11.5px; line-height: 1; padding: 4px 8px; border-radius: 999px;
  background: var(--app-panel-2); border: 1px solid var(--app-border); color: var(--app-text2);
  white-space: nowrap;
}
.m-gb-pill.st-running { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); }
.m-gb-pill.st-paused { background: var(--app-warn-weak); border-color: var(--app-warn-border); color: var(--app-warn-text); }
.m-gb-ic { flex: none; font-size: 12px; }
.m-gb-objective { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; line-height: 22px; color: var(--app-text2); }
.m-gb-acts { flex: none; display: flex; align-items: center; gap: 2px; }
/* 触屏热区按 36px 起（PC 是 28px 圆形幽灵键，这里不照抄尺寸只照抄语义） */
.m-gb-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; padding: 0; border: none; border-radius: 50%;
  background: transparent; color: var(--app-text3); font-size: 15px;
}
.m-gb-btn:active:not(:disabled) { background: var(--app-panel-2); color: var(--app-text2); }
.m-gb-btn:disabled { opacity: .4; }
.m-gb-meta { font-size: 11.5px; line-height: 16px; color: var(--app-text3); font-variant-numeric: tabular-nums; padding-left: 2px; }
.m-gb-error { font-size: 11.5px; line-height: 16px; color: var(--app-danger); padding-left: 2px; word-break: break-word; }

/* 任务清单（输入卡正上方）：快照与判据都吃引擎那一份，这里只管触屏版式。
   刻意不搬 PC 那对「条目负 margin 外扩＋列表内缩 6px」的写法——滚动容器会把外扩裁掉
   （高亮带被切成直角），而触屏本来没有 hover 需要加宽命中区。
   展开区封顶约四行、超出在卡内滚：十项清单就是十行长句，不设帽会把输入区顶出屏幕。 */
.m-todo {
  border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-panel);
  padding: 0 6px; margin-bottom: 8px;
}
.mt-head {
  display: flex; align-items: center; gap: 8px; width: 100%; min-height: 44px;
  padding: 0 4px; background: none; border: none; text-align: left; touch-action: manipulation;
}
.m-todo.open .mt-head { border-bottom: 1px solid var(--app-border); }
.mt-ico { flex: none; font-size: 14px; color: var(--app-text3); }
.mt-label { flex: none; font-size: 13px; font-weight: 600; color: var(--app-text); }
.mt-count {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; font-variant-numeric: tabular-nums; color: var(--app-text3);
}
.mt-caret { flex: none; font-size: 11px; color: var(--app-text3); transition: transform .18s; }
.mt-caret.open { transform: rotate(90deg); }
@media (prefers-reduced-motion: reduce) { .mt-caret { transition: none; } }
.mt-list { max-height: 172px; overflow-y: auto; -webkit-overflow-scrolling: touch; padding: 4px 4px 8px; }
.mt-item {
  display: flex; align-items: flex-start; gap: 6px; font-size: 12.5px; line-height: 1.5;
  padding: 5px 6px; border-radius: 7px;
}
.mt-item.doing { background: var(--app-accent-weak); }
.mt-box {
  flex: none; display: inline-flex; align-items: center; justify-content: center;
  width: 14px; height: 14px; margin-top: 2px; border: 1px solid var(--app-border);
  border-radius: 4px; font-size: 10px; line-height: 1; color: var(--app-panel);
}
.mt-box.run { border-color: var(--app-accent); color: var(--app-accent); font-size: 11px; }
.mt-item.done .mt-box { background: var(--app-ok); border-color: var(--app-ok); }
.mt-item.done .mt-txt { color: var(--app-text3); text-decoration: line-through; }
.mt-item.doing .mt-txt { color: var(--app-accent); font-weight: 500; }
.mt-txt { min-width: 0; word-break: break-word; }
.mt-run-tag { flex: none; margin-left: 2px; font-size: 11px; line-height: 18px; color: var(--app-accent); }

/* 插话队列（移动端形态）：虚线边＝「这句还没发出去」，点了之后转实线并换成状态文字。
   动作钮按 44px 热区口径（与 PC 窄屏那颗发送键同一标准，仓库里 share/mshell 都按 44 断言）。 */
.m-steer { display: flex; flex-direction: column; gap: 6px; margin-bottom: 8px; }
.ms-item {
  border: 1px dashed var(--app-border-strong); border-radius: 12px;
  background: var(--app-panel); padding: 6px 6px 6px 10px;
}
.ms-item.sending, .ms-item.deferred, .ms-item.awaiting { border-style: solid; }
.ms-txt {
  font-size: 13px; line-height: 1.45; color: var(--app-text); word-break: break-word;
  display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden;
}
.ms-foot { display: flex; align-items: center; justify-content: flex-end; gap: 4px; margin-top: 4px; }
.ms-state { flex: 1; min-width: 0; font-size: 11.5px; line-height: 16px; color: var(--app-text3); text-align: right; }
.ms-go {
  flex: none; min-height: 44px; padding: 0 14px; border: none; border-radius: 10px;
  background: var(--app-accent); color: #fff; font-size: 13px; touch-action: manipulation;
}
.ms-go:active { background: var(--app-accent-hover); }
.ms-act {
  flex: none; width: 44px; height: 44px; border: none; border-radius: 10px;
  background: var(--app-panel-2); color: var(--app-text2); font-size: 15px;
  display: inline-flex; align-items: center; justify-content: center; touch-action: manipulation;
}
.ms-act.ghost { background: transparent; color: var(--app-text3); }
.ms-act:active { background: var(--app-accent-weak); color: var(--app-accent); }

/* 目标改订 sheet（PC 在条子里就地长开，移动壳开底部 sheet）：只有一个目标原文输入框——
   没有验收标准要维护，也没有「另起一条」这条岔路 */
.m-goale { display: flex; flex-direction: column; gap: 8px; }
.m-goale-label { font-size: 12.5px; font-weight: 600; color: var(--app-text2); margin-top: 4px; }
.m-goale-ta { width: 100%; box-sizing: border-box; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel); color: var(--app-text); font: inherit; font-size: 15px; line-height: 1.55; padding: 8px 10px; resize: none; }
.m-goale-foot { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.m-goale-hint { flex: 1; font-size: 12px; color: var(--app-text3); }
.m-askp-rest { color: var(--app-text2); margin-left: 8px; flex: 1; min-width: 0; }
.m-askp-custom { align-items: center; }
.m-askp-input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; font-size: 15px; color: var(--app-text); padding: 0; }
.m-askp-input::placeholder { color: var(--app-text3); }
.m-askp-foot { display: flex; align-items: center; gap: 8px; }
.m-askp-hint { font-size: 12px; color: var(--app-text3); margin-right: auto; }
.m-askp-actions { display: flex; align-items: center; gap: 8px; }
.m-ta {
  width: 100%; box-sizing: border-box; border: none; background: transparent; resize: none;
  color: var(--app-text); font-size: 16px; line-height: 1.5; padding: 6px 4px;
  max-height: 132px; outline: none; font-family: inherit;
}
.m-tools { display: flex; align-items: center; gap: 6px; padding: 2px 0 0; }
/* min-width:0 是这一排不溢出的唯一保证：药丸里的文字是 nowrap，不写这条则 flex-shrink 整个失效
   （每颗都按全文宽占位），max-width 与省略号沦为摆设。实测四颗药丸固定要 360px，
   360px 屏上送出键画到输入卡外 29px、390px 屏上仍溢 8px。 */
.m-tool {
  display: inline-flex; align-items: center; gap: 5px; min-height: 36px; padding: 0 10px;
  border: 1px solid var(--app-border); border-radius: 999px; background: var(--app-panel);
  color: var(--app-text2); font-size: 13px; touch-action: manipulation;
  flex: 0 1 auto; min-width: 0; max-width: 62%;
}
.m-tool-text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m-tool-on { color: var(--app-accent); }
.m-tools-sp { flex: 1; }
.m-send {
  width: 42px; height: 42px; flex: none; border: none; border-radius: 50%;
  background: var(--app-accent); color: #fff; font-size: 18px;
  display: inline-flex; align-items: center; justify-content: center; touch-action: manipulation;
}
/* 禁用态：浅蓝底 + 白图标对比度太低，图标几乎看不见（看着像按钮坏了）。
   改灰底灰图标，与 PC 端 .send-btn / 分享页 .sc-send 同一口径。 */
.m-send:disabled { background: var(--app-border); color: var(--app-text3); }
.m-send.stop { background: var(--app-danger-weak); color: var(--app-danger-text); border: 1px solid var(--app-danger-border); }

/* ---- 来源详情 ---- */
.m-src { display: flex; flex-direction: column; gap: 10px; }
.m-src-url { display: inline-flex; align-items: center; gap: 6px; color: var(--app-accent); font-size: 14px; text-decoration: none; }
.m-src-tip { font-size: 13px; color: var(--app-text3); }
.m-src-snippet { font-size: 13px; color: var(--app-text2); background: var(--app-panel-2); border-radius: 8px; padding: 10px; line-height: 1.7; }
.m-src-content { font-size: 14px; line-height: 1.75; }

/* ---- 图片查看 ---- */
.m-lightbox {
  position: fixed; inset: 0; z-index: 1300; background: rgba(0, 0, 0, .9);
  display: flex; align-items: center; justify-content: center;
}
.m-lightbox-img { max-width: 96vw; max-height: 86vh; border-radius: 4px; }
.m-lb-close { position: fixed; top: calc(10px + var(--sat, 0px)); right: 12px; width: 40px; height: 40px; border: none; border-radius: 50%; background: rgba(255, 255, 255, .14); color: #fff; font-size: 22px; touch-action: manipulation; }
.m-lb-nav { position: fixed; top: 50%; transform: translateY(-50%); width: 44px; height: 44px; border: 1px solid rgba(255, 255, 255, .35); border-radius: 50%; background: rgba(0, 0, 0, .4); color: #fff; font-size: 24px; touch-action: manipulation; }
.m-lb-nav.prev { left: 10px; }
.m-lb-nav.next { right: 10px; }
.m-lb-count { position: fixed; bottom: calc(20px + var(--sab, 0px)); left: 50%; transform: translateX(-50%); color: rgba(255, 255, 255, .8); font-size: 13px; background: rgba(0, 0, 0, .5); border-radius: 999px; padding: 3px 12px; }

/* ---- 全屏编辑 ---- */
.m-edit {
  position: fixed; inset: 0; z-index: 1250; background: var(--app-bg);
  display: flex; flex-direction: column;
}
.m-edit-bar {
  flex: none; display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; padding-top: calc(6px + var(--sat, 0px));
  background: var(--app-panel); border-bottom: 1px solid var(--app-border);
}
.m-edit-title { flex: 1; font-size: 13px; color: var(--app-text3); text-align: center; }
.m-edit-ok {
  min-height: 38px; padding: 0 14px; border: none; border-radius: 10px;
  background: var(--app-accent); color: #fff; font-size: 14px; touch-action: manipulation;
}
.m-edit-ok:disabled { background: var(--app-accent-disabled); }
.m-edit-ta {
  flex: 1; margin: 12px; padding: 12px; box-sizing: border-box;
  border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-panel);
  color: var(--app-text); font-size: 16px; line-height: 1.7; outline: none; resize: none; font-family: inherit;
}

/* ---- 反馈 ---- */
.m-fb-ta {
  width: 100%; box-sizing: border-box; border: 1px solid var(--app-border); border-radius: 10px;
  background: var(--app-panel-2); color: var(--app-text); font-size: 16px; padding: 10px; outline: none; resize: none; font-family: inherit;
}
.m-fb-ok { width: 100%; margin-top: 10px; min-height: 44px; border: none; border-radius: 12px; background: var(--app-accent); color: #fff; font-size: 15px; touch-action: manipulation; }
.m-fb-ok:disabled { background: var(--app-accent-disabled); }

/* ==================== 触屏热区补齐 ====================
   这些控件此前 24–42px，手指点不准；但**视觉尺寸一律不动**——把框改大会把同一排挤爆
   （工具条那一排曾因 .m-tool 缺 min-width:0 把发送键画到卡外过）。所以只加一层透明伪元素
   把命中区补到 44：手指打的是这块透明区域，看起来还是原来那颗小钮。
   两档分开，不能一刀切：
   - 孤立或左右留白的钮 → 双向补（发送、图片查看关闭、回到底部）；
   - 密集成排的钮（顶栏六颗、目标条四颗、提问卡翻页 ‹ ›、查找条上下颗）→ **只补高度**。
     横向对扩会互相挡点击：后渲染的那颗的透明区会盖住前一颗的外侧半区，点上去没反应。
   chip 的 × 单独处理：向右扩，向左只让出 8px，否则会盖住同一颗 chip 上的图片预览入口。
   真机判据用 elementFromPoint 逐颗量——getBoundingClientRect 是布局盒，量不出命中区。 */
.m-bar-btn, .m-bar-title, .mf-btn, .m-chip-del, .m-tool, .m-send, .m-gb-btn,
.m-askp-page-btn, .m-lb-close, .m-rec-btn, .m-jump { position: relative; }
.m-bar-btn::after, .m-bar-title::after, .mf-btn::after, .m-tool::after,
.m-gb-btn::after, .m-askp-page-btn::after, .m-rec-btn::after {
  content: ''; position: absolute; top: 50%; left: 0; right: 0;
  height: 44px; transform: translateY(-50%);
}
.m-send::after, .m-lb-close::after, .m-jump::after {
  content: ''; position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
  width: 44px; height: 44px;
}
.m-chip-del::after {
  content: ''; position: absolute; top: 50%; right: -6px; transform: translateY(-50%);
  width: 40px; height: 44px;
}
</style>
