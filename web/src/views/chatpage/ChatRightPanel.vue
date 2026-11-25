<template>
    <aside v-if="store.panelOpen || store.mPanelOpen" class="right-panel" :class="{ 'as-sheet': store.isNarrow, 'browser-wide': store.railTab === 'browser' }">
      <!-- 窄屏下本栏是覆盖式 sheet，必须给一个显式关闭口：宽屏它是常驻列不需要关，
           窄屏开起来后若只能靠顶栏按钮来回切，用户会觉得「关不掉」。仅窄屏渲染。 -->
      <button v-if="store.isNarrow" class="rp-sheet-close" type="button" title="关闭" @click="set('mPanelOpen', false)">
        <close-outlined />
      </button>
      <!-- 右栏分段：过程 / 来源 / 文件 / 浏览。原先所有卡竖排，本轮最该看的过程会被后面的卡顶出首屏；
           分段把三件事分开，计数与失败数直接标在页签上 -->
      <div class="rp-tabs" role="tablist" aria-label="右栏内容">
        <button type="button" class="rp-tab" :class="{ on: store.railTab === 'process' }" role="tab"
                :aria-selected="store.railTab === 'process'" @click="set('railTab', 'process')">
          过程<span class="rp-tab-n">{{ store.processSteps.length }}</span>
          <span v-if="store.processFailCount" class="rp-tab-badge">{{ store.processFailCount }}</span>
        </button>
        <button type="button" class="rp-tab" :class="{ on: store.railTab === 'sources' }" role="tab"
                :aria-selected="store.railTab === 'sources'" @click="set('railTab', 'sources')">
          来源<span class="rp-tab-n">{{ store.groupedSources.length }}</span>
        </button>
        <button type="button" class="rp-tab" :class="{ on: store.railTab === 'files' }" role="tab"
                :aria-selected="store.railTab === 'files'" @click="set('railTab', 'files')">
          文件<span class="rp-tab-n">{{ store.fileTabCount }}</span>
        </button>
        <button type="button" class="rp-tab" :class="{ on: store.railTab === 'browser' }" role="tab"
                :aria-selected="store.railTab === 'browser'" @click="set('railTab', 'browser')">
          浏览<span v-if="store.browserSnapshots.length" class="rp-tab-n">{{ store.browserSnapshots.length }}</span>
        </button>
        <button v-if="!store.memberViewRoot" type="button" class="rp-tab" :class="{ on: store.railTab === 'team' }" role="tab"
                :aria-selected="store.railTab === 'team'" @click="set('railTab', 'team')">
          团队
        </button>
      </div>
      <!-- 用 v-show 保留实例：在「浏览」与过程/来源/文件之间来回切时，侧栏 iframe 的地址与
           已加载页面不会被卸载重建（v-if 会让正在看的页面消失）。整栏关闭仍由上面的 aside v-if 控制。 -->
      <BrowserPanel v-show="store.railTab === 'browser'" :snapshots="store.browserSnapshots" />
      <TeamPanel v-if="store.railTab === 'team' && !store.memberViewRoot" :session-id="store.currentSessionId || ''" :session-title="store.currentSessionTitle" :agent-name="store.currentAgentName" :agent="store.agentBadgeOf(store.agentList, store.currentAgentId, store.currentAgentName)" :agents="store.agentList" :msg="store.processMsg" />
      <!-- 本轮卡（常驻首位）：跑轮时实时滚动「哪几步、每步在做什么事」，答完收成这一轮的动作清单。
           单步耗时不在这里报（整轮用时归气泡那条状态行），要点「查看全部步骤」进账本弹窗。
           会话里还没有 AI 消息时整卡不渲染（新会话不占位）。其余各卡仍是「有数据才显示」。 -->
      <RightProcessCard v-if="store.railTab === 'process'" :msg="store.processMsg"
                        @open-events="store.openEvents()" @open-hitl="store.focusHitl"
                        @regenerate="store.processMsgIndex >= 0 && store.regenerate(store.processMsgIndex)" />
      <div v-if="store.railTab === 'process' && !store.processMsg" class="rp-empty">还没有可看的过程</div>
      <!-- 引用来源：右栏交互最重的一张卡（点片段看原文 / 角标号定位正文 / hover 高亮角标 / 分组展开），过程卡之后的第一张。
           原先排在末尾，等于把「核查答案」这个最高频动作排到最需要滚动才看得到的位置——
           正文里刚亮起的角标，用户得点开侧栏再往下翻。移动端 sheet 一直把它排第一，这里对齐。
           有引用才显示整卡（空则隐藏，不占版面，与产物卡同规则）。
           卡头可折叠：来源多时（11 个来源就是 11 个分组行）整卡会把下面的卡顶出首屏。
           任务清单在输入框上方常驻（见 #above 的 .todo-bar）。 -->
      <div v-if="store.railTab === 'sources' && !store.groupedSources.length" class="rp-empty">本轮没有引用来源</div>
      <div v-if="store.railTab === 'sources' && store.groupedSources.length" class="rp-card">
        <button type="button" class="rp-card-head" @click="store.srcCardOpen = !store.srcCardOpen">
          <span class="rp-label">引用来源 · {{ store.groupedSources.length }} 个来源</span>
          <caret-right-outlined class="tl-caret" :class="{ open: store.srcCardOpen }" />
        </button>
        <div v-if="store.srcCardOpen">
          <div v-for="g in store.groupedSources" :key="g.key" class="rp-group">
            <div class="rp-group-head" :title="store.srcOpenOf(g) ? '收起片段' : '展开片段'" @click="store.toggleSrc(g)">
              <global-outlined v-if="g.icon === 'web'" class="rp-src-ic" />
              <api-outlined v-else-if="g.icon === 'mcp'" class="rp-src-ic" />
              <file-text-outlined v-else class="rp-src-ic" />
              <span class="rp-src-name">{{ g.fileName }}</span>
              <span class="rp-count">{{ g.items.length }} 段</span>
              <caret-right-outlined class="tl-caret" :class="{ open: store.srcOpenOf(g) }" />
            </div>
            <div class="rp-group-body" :class="{ open: store.srcOpenOf(g) }">
              <!-- 0fr→1fr 只对**唯一直接子元素**生效：多个直接子元素会落到 auto 隐式行不参与收缩，故统一包一层 -->
              <div class="rp-group-body-in">
                <div v-for="(s, si) in g.items" :key="si" class="rp-src rp-src-sub"
                     :class="{ hl: store.hoveredRef === s.ref }"
                     :title="s.origin === 'WEB' ? '点击查看来源摘要与原网页' : '点击查看原文'"
                     @click.stop="store.openSource(s)"
                     @mouseenter="store.hoverSource(s)" @mouseleave="store.unhoverSource(s)">
                  <!-- 角标号那枚单独是「在正文里定位」：整行已经拿去开原文了，跳转不能再占同一个点击区，
                       而「[1]」这个号本身就是正文里那个上标，指着它跳最顺眼 -->
                  <span class="rp-src-ref" title="在正文中定位这条引用" @click.stop="store.locateSource(s)">[{{ s.ref }}]</span>
                  <span v-if="s.origin === 'MENTION'" class="rp-src-mine" title="本轮你 @ 引用的资料">引用</span>
                  <span class="rp-src-name">{{ s.title ? '§ ' + s.title : '片段 ' + (si + 1) }}</span>
                  <span v-if="store.debugDisplayVisible && store.fmtSourceScore(s)" class="rp-src-score" :title="store.scoreTitle(s)">{{ store.fmtSourceScore(s) }}</span>
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
      <div v-if="store.railTab === 'files' && store.filesTabEmpty" class="rp-empty">本轮还没有文件或交付</div>
      <div v-if="store.railTab === 'files' && store.deliveryPackages.length" class="rp-card">
        <div class="rp-label">交付 · {{ store.deliveryPackages.length }} 个包</div>
        <button v-for="d in store.deliveryPackages" :key="d.anchorKey || d.name" type="button" class="rp-dlv"
                @click="store.openDelivery(d)">
          <folder-open-outlined class="rp-art-ic" />
          <span class="rp-dlv-main">
            <span class="rp-dlv-name">{{ d.name }}</span>
            <span class="rp-dlv-sub">{{ d.fileCount }} 件 · {{ d.sizeLabel }}<template v-if="d.stateLabel"> · {{ d.stateLabel }}</template></span>
          </span>
          <caret-right-outlined class="rp-dlv-go" />
        </button>
        <div class="rp-art-all" @click="store.router.push('/artifacts')">在产物页查看全部 ›</div>
      </div>
      <!-- 本会话沙盒目录：助手在沙盒里做出、却没交付成产物的文件（产物卡只管已交付的那一份）。
           有文件才显示整卡，与产物卡同一判据；读不到要报错带重试——静默成空列表会被读成「我让它做的文件没了」。
           预览＝签名地址开新标签（与工作区文件栏同一条路、同一批类型判据，服务端只给点得开的条目配 viewUrl）。
           接口给的是摊平的一维清单，这里按相对路径折回目录树：整条路径摊在一行时名字全被截断，
           认不出哪份是成品；折成树之后行内只剩文件名，腾出的位置给大小。 -->
      <div v-if="store.railTab === 'files' && (store.sandboxFiles.length || store.sandboxErr || store.sandboxLoading)" class="rp-card">
        <div class="rp-label">{{ store.sessionWorkspace && store.sessionWorkspace.isSshAlias ? '远端文件' : (store.sessionWorkspace && (store.sessionWorkspace.isLocal || store.sessionWorkspace.wsEffective === 'local') ? '本机文件' : '会话文件') }} · {{ store.sandboxCount }} 个{{ store.sandboxBytes ? ' · ' + store.sbSize(store.sandboxBytes) : '' }}</div>
        <div v-if="store.sandboxLocalOffline" class="rp-sb-hint">本机执行器离线，暂显示云端沙盒文件</div>
        <div v-if="store.sandboxLoading" class="rp-sb-hint">正在读取目录…</div>
        <div v-else-if="store.sandboxErr" class="rp-sb-err">
          <span>{{ store.sandboxErr }}</span>
          <button type="button" class="rp-sb-retry" @click="store.loadSandboxFiles">重试</button>
        </div>
        <template v-for="row in store.sandboxRows" :key="row.key">
          <!-- 目录行：开合钮只占名字那一段，行尾留给「在电脑上打开」——本机工作区的每个目录都是
               用户电脑上真实存在的文件夹，要拿到 Finder 里接着干时不必再绕回工作区页 -->
          <div v-if="row.type === 'dir'" class="rp-sb-dir">
            <button type="button" class="rp-sb-dir-main"
                    :title="(row.open ? '收起 ' : '展开 ') + row.dir.path + '/（' + row.dir.count + ' 个文件 · ' + store.fmtSize(row.dir.size) + '）'"
                    @click="store.toggleSandboxDir(row.dir.path)">
              <span v-for="i in row.depth" :key="i" class="rp-sb-ind" />
              <caret-right-outlined class="tl-caret" :class="{ open: row.open }" />
              <folder-open-outlined v-if="row.open" class="rp-art-ic" />
              <folder-outlined v-else class="rp-art-ic" />
              <span class="rp-sb-name">{{ row.dir.name }}/</span>
            </button>
            <span class="rp-sb-meta">{{ row.dir.count }} 个 · {{ store.sbSize(row.dir.size) }}</span>
            <button v-if="store.sessionWorkspace && store.sessionWorkspace.isLocal" type="button" class="rp-art-act rp-sb-btn"
                    :title="'在电脑上打开「' + row.dir.name + '」文件夹（Finder / 资源管理器）'"
                    @click="store.openLocalFolder(row.dir.path)"><export-outlined /></button>
          </div>
          <div v-else class="rp-art">
            <span v-for="i in row.depth" :key="i" class="rp-sb-ind" />
            <span class="rp-art-name"
                  :title="row.file.path + ' · ' + store.fmtSize(row.file.size) + ' · ' + store.fmtMsgTime(row.file.mtime)">{{ row.file.name }}</span>
            <span class="rp-sb-meta">{{ store.sbSize(row.file.size) }}</span>
            <a v-if="row.file.viewUrl" class="rp-art-act" :href="store.resolveImg(row.file.viewUrl)" target="_blank" rel="noopener"
               title="在新标签页打开"><eye-outlined /></a>
            <button type="button" class="rp-art-act rp-sb-btn" :title="'下载 ' + row.file.name"
                    @click="store.sandboxDownload(row.file)"><download-outlined /></button>
            <button v-if="!store.sandboxTaken.has(row.file.path)" type="button" class="rp-art-act rp-sb-btn"
                    title="存为产物（进「我的产物」，可预览可下载）" @click="store.sandboxTake(row.file)"><save-outlined /></button>
            <span v-else class="rp-sb-taken" title="已存为产物"><check-outlined /></span>
          </div>
        </template>
        <div v-if="store.sandboxRest" class="rp-sb-hint">另有 {{ store.sandboxRest }} 个文件未列出（仅显示最新 {{ store.sandboxFiles.length }} 个）</div>
      </div>
      <!-- 会话后台进程：让它「把写好的前后端跑起来自己看效果」时，起的是 npm run dev 这类<b>不会自己退</b>
           的进程。两个来源合成一张清单：工作区会话的进程跑在你电脑上的本机执行器里，会话栏会话的跑在沙盒容器里；
           同一条会话可能在执行器离线的轮退回沙盒，两边都会出现，所以来源只是行上的一个标记，不拆两张卡。
           真源各自在源头（执行器进程登记 + ps 实况 / 容器 /proc），服务端不留台账：执行器/后端重启都照得到。
           沙盒里挂了共享目录时多个会话共用一个容器，别的会话起走的进程也会列出来，标一句——端口是同一个容器占的。
           本机侧端口可以直接点开（就是你电脑上的 localhost），沙盒容器端口不映射、只说明它占着哪个。 -->
      <div v-if="store.railTab === 'files' && store.procCardVisible" class="rp-card">
        <div class="rp-label rp-proc-head"
             title="助手在会话里起的、不会自己退的进程（比如为了自己看效果起的服务）。本机执行器到点只是不再等它（或它自己退成了孤儿），进程照旧活着。">
          <span>后台进程<span class="rp-proc-n">{{ store.procs.length }}</span><span class="rp-proc-unit">个在跑</span></span>
          <span class="rp-proc-acts">
            <button v-if="store.procs.length > 1" type="button" class="rp-sb-retry" :disabled="store.killingAll || !!store.killingTag"
                    title="停掉本会话起的全部进程树" @click="store.killAllProcs">{{ store.killingAll ? '停止中…' : '全部停止' }}</button>
            <button type="button" class="rp-sb-retry" :disabled="store.procLoading"
                    title="重新扫一遍会话里的进程" @click="store.loadSandboxProcs">刷新</button>
          </span>
        </div>
        <div v-for="n in store.procNotices" :key="'notice-' + n.side" class="rp-proc-note">
          <span>{{ n.message }}</span>
          <button v-if="n.reason === 'error'" type="button" class="rp-sb-retry" @click="store.loadSandboxProcs">重试</button>
        </div>
        <div v-if="store.procErr" class="rp-sb-err">
          <span>{{ store.procErr }}</span>
          <button type="button" class="rp-sb-retry" @click="store.loadSandboxProcs">重试</button>
        </div>
        <div v-for="(p, i) in store.procs" :key="p.tag" class="rp-proc"
             :class="{ 'is-killing': store.killingTag === p.tag }" :style="{ '--proc-i': i }">
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
              <span>跑了 {{ store.procAge(p) }}</span>
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
            <button v-if="p.killable" type="button" class="rp-proc-kill" :disabled="store.killingTag === p.tag || store.killingAll"
                    :title="'停掉它，连同它起走的全部子进程（' + p.pids.length + ' 个）'"
                    @click="store.killProc(p)">{{ store.killingTag === p.tag ? '停止中…' : '停止' }}</button>
            <span v-else class="rp-proc-unknown" title="认不出这是哪次会话起的，停不了">来源不明</span>
          </div>
        </div>
      </div>
      <!-- 计划：本会话每轮出过的计划各列一条（继续对话/退回重出后每次重做都算一份），点开右侧完整计划抽屉。
           待确认的计划自动展开并置顶，避免「计划要批」这件事被藏住；历史计划沉底折叠。 -->
      <div v-if="store.railTab === 'process' && store.sessionPlans.length" class="rp-card">
        <button type="button" class="rp-card-head rp-plan-head" :title="store.planOpen ? '收起计划' : '展开计划'"
                @click="store.planOpen = !store.planOpen">
          <span class="rp-label rp-label-h">计划 · {{ store.sessionPlans.length }} 份</span>
          <span class="rp-plan-badge" :class="{ 'is-pending': store.sessionPlans.some(p => p.status === 'pending') }">
            {{ store.planHeadText }}
          </span>
          <caret-right-outlined class="tl-caret" :class="{ open: store.planOpen }" />
        </button>
        <div class="rp-group-body" :class="{ open: store.planOpen }">
          <div class="rp-group-body-in">
            <button v-for="p in store.sessionPlans" :key="p.mi" type="button" class="rp-plan-row" :title="p.label + ' · ' + p.steps + ' 步'"
                    @click="store.openPlanFromRail(p)">
              <span class="rp-plan-dot" :class="'is-' + p.status"></span>
              <span class="rp-plan-t">{{ p.label }}</span>
              <span class="rp-plan-st" :class="'is-' + p.status"><template v-if="p.vers">v{{ p.vers + 1 }} · </template>{{ store.planStateText(p.status) }}</span>
            </button>
            <div class="rp-plan-note">待确认的计划自动置顶；点任意一行打开同一份计划视图。</div>
          </div>
        </div>
      </div>
    </aside>

</template>

<script setup>
import { inject } from 'vue'
// 父页 provide('chatCtx')：一个 reactive 上下文（含 ref 与动作）
const store = inject('chatCtx')
const set = (k, v) => { store[k] = v }
const setRef = (k, el) => { store[k] = el }
</script>

<style scoped src="./ChatRightPanel.css"></style>
