<template>
  <div class="app-root" :class="{ 'is-narrow': isNarrow }">
    <!-- 左侧边栏：logo / 导航 / 最近会话 / 底部用户区（可折叠为图标条）
         窄屏下由 CSS 整体变为左侧抽屉（position:fixed + translateX），**不改 DOM 结构**——
         会话搜索防抖、分组折叠、批量模式、游标分页、置顶/重命名/删除约 200 行逻辑若另建
         抽屉组件就得复制或抽 composable，两份实现必然漂移。
         collapsed 是 PC 的持久化偏好（用户在桌面折叠过侧栏后窄屏会全是小圆点），窄屏用
         `collapsed && !isNarrow` 挡掉。
         mobileShell（触屏 H5 外壳）下整个退场：菜单/会话列表由 MobileSessionSheet 承担，
         那里的会话行与操作与抽屉同源 store、同一组接口，但形态是移动的——不让 PC 抽屉
         出现在 H5 里（横屏时它还会以常驻栏形态直接摊在左侧）。 -->
    <aside v-if="!mobileShell" class="side" :class="{ collapsed: collapsed && !isNarrow, open: sideOpen }">
      <div class="side-logo">
        <BrandMark v-if="!collapsed" :size="24" />
        <span v-if="!collapsed" class="logo-name">问渠</span>
        <!-- 侧栏的「形态操作」组：编辑 + 折叠，用容器整体靠右（.side-logo-ops margin-left:auto）。
             必须成组包起来，而不是各自 margin-left:auto —— 后者只推走了折叠按钮，
             编辑按钮留在「问渠」旁、像品牌名的后缀（2026-10-08 实测踩过：注释写「紧挨折叠
             按钮」而效果不符，返工过一次）。成组后两者真正相邻且不与品牌名抢位。
             编辑放这里而非底部那排（通知/主题/退出）：那是全局动作区；
             「改这列菜单」是侧栏自身的形态操作，与折叠同属一类。
             也曾试过放导航组末尾：混在菜单列表里像第 11 个功能入口，用户会预期点了跳页
             （实际是切换形态），且紧贴「搜索会话」视觉上像搜索框的附属按钮。
             展开态下这组默认**隐身**、鼠标移到 logo 行才浮现（无边框裸图标，样式见 .side-logo-ops）：
             logo 行平时只留品牌名。隐身不等于丢入口——长按菜单区同样进编辑态
             （见 onNavPressStart），纯触屏则恒显（没有 hover 就没有第二次机会）。
             编辑按钮折叠态不渲染：56px 图标条里既无文字可排也无开关可拨；
             折叠按钮则必须在（折叠态它的图标就是品牌标，是唯一的展开入口），
             但**编辑态里隐身**（.slot-hidden，见样式）：折叠本来就会结束编辑（toggleFold 里
             exitNavEdit），点它等于「取消编辑」，留着只会误点。
             注意是「隐身留位」而不是 v-if 摘掉：摘掉后整组靠右对齐，✓ 会右移 28px
             正好补进折叠图标原来的格子，用户「点别处」一按就退出编辑态，
             看着就像图标自己消失了。留位后进/出编辑态图标都不挪位。
             navEditing 时整组常显（pinned）——正用着的工具条不能因为鼠标移开就消失。 -->
        <div class="side-logo-ops" :class="{ pinned: navEditing }">
          <button v-if="!collapsed" class="app-icon-btn" :class="{ on: navEditing }"
                  :disabled="navEditLoading"
                  :title="navEditing ? '完成侧栏编辑' : '编辑侧栏（调整顺序与显示）'"
                  @click="toggleNavEdit">
            <a-spin v-if="navEditLoading" :spinning="true" size="small" />
            <check-outlined v-else-if="navEditing" />
            <unordered-list-outlined v-else />
          </button>
          <button class="app-icon-btn fold" :class="{ 'slot-hidden': navEditing && !collapsed }"
                  :title="collapsed ? '展开侧边栏' : '折叠侧边栏'" @click="toggleFold">
            <BrandMark v-if="collapsed" :size="24" />
            <menu-fold-outlined v-else />
          </button>
        </div>
      </div>

      <!-- 长按菜单区 0.5s = 直接进编辑态（入口平时隐身，触屏更没有 hover，手势是常驻通道）。
           pointerdown 挂在 nav 这一层而不是逐项绑：菜单项/分组标题/引导入口全覆盖，
           将来加菜单也不用记得补。click.capture 是给长按后那次抬手点击兜底的（必须吞掉）。 -->
      <nav class="side-nav" ref="navEl" @transitionend="onFlipEnd"
           @pointerdown="onNavPressStart" @contextmenu="onNavContextMenu"
           @click.capture="onNavClickCapture">
        <button class="nav-item" :class="{ active: isActive('/chat') && !route.query.sid }" @click="navEditing ? null : newChat()" title="新建对话">
          <plus-outlined />
          <span v-if="!collapsed">新建对话</span>
        </button>
        <!-- 导航数据源：编辑态取 navEditRows（my-layout 的**全集**，含被自己隐藏的），
             非编辑态取 navMenus（/auth/me，已剔除 hidden 的实际渲染集）。
             两者不能混用 —— 用 navMenus 渲染编辑行会让「已隐藏项」从清单里消失，
             开关再也打不回来（抽屉版已踩过一次，换实现后又复现了一次）。
             renderAs：sidebar=可点入口；group=分组标题（不可点）；tab=页内 Tab（宿主页渲染，
             不进侧栏）；hidden=纯权限容器（任何 UI 不渲染）。
             待配置 tag：/chat=聊天模型或默认模型未就绪、/knowledge=向量模型未就绪（tooltip 列缺失项） -->
        <template v-for="m in (showNavEditor ? navEditRows : navMenus)" :key="m.id">
          <!-- 编辑态：整行是编辑控件（拖拽把手 + 显隐开关），不再是导航按钮。
               data-id 是 FLIP 量位置与落点判定的唯一依据，必须挂在行根元素上。 -->
          <div v-if="showNavEditor"
               class="nav-edit-row"
               :data-id="m.id"
               :class="{ off: m.hidden, dragging: editDragId === m.id }"
               @pointerdown="onEditPointerDown(m.id, $event)">
            <span class="nav-edit-grip" aria-hidden="true">⋮⋮</span>
            <component :is="iconOf(m.icon)" class="nav-edit-ic" />
            <span class="nav-edit-name">{{ m.name }}</span>
            <a-switch size="small" :checked="!m.hidden"
                       @pointerdown.stop @click.stop @change="toggleHidden(m.id, $event)" />
          </div>
          <div v-else-if="m.renderAs === 'group'" class="nav-group">{{ m.name }}</div>
          <button v-else class="nav-item"
                  :class="{ active: isActive(m.path) }" @click="router.push(m.path)" :title="menuTitle(m)">
            <component :is="iconOf(m.icon)" />
            <span v-if="!collapsed">{{ m.name }}</span>
            <span v-if="!collapsed && menuPending(m)" class="app-pill nav-pending">待配置</span>
            <i v-if="menuPending(m)" class="nav-dot"></i>
          </button>
        </template>

        <!-- 配置引导入口（导航组末尾——辅助层功能不占 logo 下的黄金位，必配的醒目性
             由菜单「待配置」tag 与对话页欢迎卡承担）：
             必配未完成=琥珀「配置引导·还差 N 项」+ 折叠圆点（待办感）；
             必配完成但进阶项有缺=弱化链接「进阶配置 · N 项可选」（感知但不催办）；
             全部完成=消失，对已配好的存量用户零打扰 -->
        <button v-if="guideVisible" class="nav-item guide-entry" :class="{ adv: guideAdvOnly }"
                @click="guideOpen = true" :title="guideEntryTitle">
          <compass-outlined />
          <span v-if="!collapsed" class="guide-entry-text">{{ guideAdvOnly ? '进阶配置' : '配置引导' }}</span>
          <span v-if="!collapsed && !guideAdvOnly" class="guide-count">还差 {{ pendingCount }} 项</span>
          <span v-else-if="!collapsed" class="guide-count muted">{{ advPendingCount }} 项可选</span>
          <i v-if="pendingCount > 0" class="nav-dot"></i>
        </button>

        <!-- 编辑态操作条：只在编辑态出现（入口在 logo 行，见 .side-logo-ops）。
             单行按钮、不额外占高度；提示语从常驻文案降为 title ——
             两行式版本会把侧栏顶高 43px、连带下方会话列表整体下移，
             而行内的拖拽把手与开关本身已自解释，不必常驻。 -->
        <div v-if="showNavEditor" class="nav-edit-bar" title="拖动行可调整顺序，开关控制显隐">
          <div class="nav-edit-ops">
            <a-popconfirm title="恢复到系统默认顺序，并把隐藏项全部显示出来，确定？"
                          ok-text="恢复" cancel-text="取消" @confirm="resetNavDefault">
              <button class="nav-edit-btn ghost" :disabled="navEditSaving">恢复默认</button>
            </a-popconfirm>
            <!-- 主操作给实心：未改动时是禁用态，一眼能看出「现在按了没反应」是有意的 -->
            <button class="nav-edit-btn primary"
                    :disabled="navEditSaving || !navEditDirty" @click="saveNavLayout">
              {{ navEditSaving ? '保存中' : '保存' }}
            </button>
          </div>
        </div>
      </nav>

      <!-- 会话搜索（防抖走后端 keyword 检索：标题/消息内容模糊匹配）。
           搜索框在「任务 / 工作区」两个分区之上：它过滤的是整棵会话树，不是某一个工作区。 -->
      <div v-if="!collapsed" class="sess-search-wrap">
        <search-outlined class="sess-search-ic" />
        <input ref="searchInputRef" v-model="searchKw" class="sess-search" placeholder="搜索会话…" @input="onSearchInput" />
        <button v-if="searchKw" class="sess-search-clear" title="清除搜索" @click="clearSearch"><close-outlined /></button>
      </div>

      <!-- 新建工作区：侧栏直接建，不必先去「我的产物」页。
           全站只有「工作区」这一个词：侧栏分组、工作区页、弹窗都用它，
           数据模型里的工作区容器（一个工作目录 + 一组会话与产物）不再另起名字。 -->
      <a-modal v-model:open="createOpen" title="新建工作区" :confirm-loading="createSaving"
               ok-text="创建" cancel-text="取消" @ok="doCreateProject">
        <a-input v-model:value="createName" :maxlength="60" placeholder="工作区名，例如：考勤系统改造"
                 @press-enter="doCreateProject" />
        <div class="pj-modal-hint">一个工作区就是一个工作目录加一份约定：之后的对话、文件与产物都归到它名下。</div>
      </a-modal>

      <!-- 工作区重命名：用页内弹窗而不是 Modal.confirm（后者没有输入框） -->
      <a-modal v-model:open="renameOpen" title="重命名工作区" :confirm-loading="renameSaving"
               ok-text="保存" cancel-text="取消" @ok="doRenameProject">
        <a-input v-model:value="renameName" :maxlength="60" placeholder="工作区名"
                 @press-enter="doRenameProject" />
      </a-modal>

      <!-- 批量操作条：进入批量模式才出现，紧贴列表上方，作用于已加载的会话 -->
      <div v-if="!collapsed && batchMode" class="sess-batch-bar">
        <span class="batch-count">已选 {{ batchSel.size }}</span>
        <span class="batch-actions">
          <button class="batch-btn" @click="toggleSelectAll">{{ allSelected ? '取消全选' : (sessionStore.hasMore ? '全选(已加载)' : '全选') }}</button>
          <button class="batch-btn danger" :disabled="!batchSel.size" @click="confirmBatchDelete">删除</button>
          <button class="batch-btn" @click="exitBatchMode">取消</button>
        </span>
      </div>

      <!-- 会话树：会话（不属于任何真实工作区：没挂工作区 + implicit 兜底容器）+ 工作区（用户建的）。
           两个分区都是「标题 (N) → 若干行 → 查看更多 (N)」：行与行的数据加载走 store 的同一套
           工作区分页，区别只在第一个分区没有工作区头（它就是"没归属"的那一堆会话）。
           工作区这一区不做全局「显示更多」——每个工作区各自还剩多少，用户要能看到。 -->
      <div ref="sessionsEl" class="side-sessions">
        <!-- 折叠成 56px 图标条：分区标题没有横向空间，只保留会话圆点（切换入口不能丢） -->
        <template v-if="collapsed">
          <div v-for="s in sessionStore.list" :key="s.id" class="sess-item"
               :class="{ active: isActive('/chat') && route.query.sid === s.id, running: chatStreams.has(s.id) }"
               :title="s.title || '新对话'" @click="openSession(s.id)">
            <span class="sess-dot"></span>
          </div>
          <div v-if="!sessionStore.list.length" class="side-empty">暂无会话</div>
        </template>
        <a-spin v-else-if="sessionStore.loading && !sessionStore.spaces.length" size="small" style="display:block;margin:16px auto" />
        <template v-else>
          <section v-for="sec in sidebarSections" :key="sec.key" class="side-sec">
            <div class="side-sec-head clickable" role="button" :tabindex="0"
                 :aria-expanded="String(!secHidden(sec))"
                 :title="(secHidden(sec) ? '展开：' : '收起：') + sec.title"
                 @click="toggleSection(sec)"
                 @keydown.enter.self.prevent="toggleSection(sec)"
                 @keydown.space.self.prevent="toggleSection(sec)">
              <span class="side-sec-title" :title="sec.key === 'solo' ? '不属于任何工作区的会话' : ''">{{ sec.title }}</span>
              <span class="side-sec-count">({{ sec.count }})</span>
              <!-- 整段折叠的常驻指示：与「已归档」同一个箭头口径（折起 = >，展开 = 转 90° 向下）。
                   它不能跟着 hover 才出现——这一行能不能点，靠的就是这个箭头告知。 -->
              <right-outlined class="group-caret sec-caret" :class="{ open: !secHidden(sec) }" />
              <span v-if="sec.spacesSection" class="side-sec-ops" :class="{ pinned: sessionStore.loading }">
                <a-spin v-if="sessionStore.loading" size="small" />
                <!-- 一键展开/折叠全部工作区：图标随当前态翻转（全展开时才给「折叠」，混合态一律给「展开」，
                     与逐个折叠头的常见口径一致）。整段已收起、或搜索态（store 强制全展开，见 spaceHidden）
                     都不给这个按钮——前者要的是「展开这一段」，后者点了只会在清空搜索后凭空冒出一排折叠的工作区。
                     .stop 必需：这一行现在整体可点，冒泡上去会顺手把整段折叠/展开，一次点击两件事。 -->
                <button v-if="foldableSpaces.length && !secHidden(sec) && !searchKw" class="side-sec-op"
                        :title="allSpacesExpanded ? '折叠全部工作区' : '展开全部工作区'"
                        @click.stop="foldAllSpaces(allSpacesExpanded)">
                  <shrink-outlined v-if="allSpacesExpanded" />
                  <arrows-alt-outlined v-else />
                </button>
                <button class="side-sec-op" title="新建工作区" @click.stop="openCreateSpace"><plus-outlined /></button>
              </span>
            </div>

            <template v-if="!secHidden(sec)">
            <template v-for="row in sec.rows" :key="row.key">
              <!-- 已归档的折叠头：插在活动工作区之后，展开时才拉这些工作区的会话 -->
              <button v-if="row.archivedHead" class="side-sec-sub" @click="toggleArchived">
                <span>已归档 ({{ sessionStore.archived.length }})</span>
                <right-outlined class="group-caret" :class="{ open: sessionStore.archivedOpen }" />
              </button>
              <div v-else class="space" :class="{ archived: row.status === 'archived', bare: sec.bare }">
                <div v-if="!sec.bare" class="space-head" role="button" tabindex="0"
                     :aria-expanded="!spaceHidden(row)"
                     :title="(spaceHidden(row) ? '展开：' : '收起：') + row.name"
                     @click="toggleSpace(row)"
                     @keydown.enter.self.prevent="toggleSpace(row)"
                     @keydown.space.self.prevent="toggleSpace(row)">
                  <span class="space-icon">
                    <folder-open-outlined v-if="!spaceHidden(row)" />
                    <folder-outlined v-else />
                  </span>
                  <span class="space-name">{{ row.name }}</span>
                  <a-spin v-if="row.loading" class="space-spin" size="small" />
                  <a-dropdown v-if="row.selectable" trigger="['click']" placement="bottomRight">
                    <button class="space-op" title="工作区操作" @click.stop><more-outlined /></button>
                    <template #overlay>
                      <a-menu @click="({ key }) => spaceMenu(row, key)">
                        <a-menu-item key="open" :disabled="row.virtual"><folder-open-outlined /> 打开工作区</a-menu-item>
                        <a-menu-item key="rename" :disabled="row.virtual"><edit-outlined /> 重命名</a-menu-item>
                        <a-menu-divider />
                        <a-menu-item v-if="row.status === 'archived'" key="restore"><reload-outlined /> 恢复</a-menu-item>
                        <a-menu-item v-else key="archive"><inbox-outlined /> 归档</a-menu-item>
                        <a-menu-item v-if="!row.isImplicit" key="delete" danger><delete-outlined /> 删除工作区</a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                  <!-- 新建对话：直接落进这个工作区（归档工作区不提供） -->
                  <button v-if="row.selectable && row.status !== 'archived'" class="space-add"
                          title="在此工作区新建对话" @click.stop="newChat(row.id)">
                    <plus-outlined />
                  </button>
                </div>

                <template v-if="sec.bare || !spaceHidden(row)">
                  <div v-if="row.error" class="side-empty err">
                    加载失败
                    <button class="side-inline-btn" @click.stop="reloadSpace(row)">重试</button>
                  </div>
                  <div v-for="s in row.items" :key="s.id"
                       class="sess-item"
                       :class="{ active: !batchMode && isActive('/chat') && route.query.sid === s.id, picked: batchMode && batchSel.has(s.id), running: chatStreams.has(s.id) }"
                       :style="{ '--sess-i': sessRowOrdinal.get(s.id) ?? 0 }"
                       :title="sessTooltip(s)"
                       @click="batchMode ? toggleBatchSel(s.id) : openSession(s.id)">
                    <span v-if="batchMode" class="batch-check" :class="{ on: batchSel.has(s.id) }">
                      <check-outlined v-if="batchSel.has(s.id)" />
                    </span>
                    <pushpin-outlined v-if="s.isPinned === 1" class="sess-pin-flag" />
                    <!-- 这个会话背上有启用的定时任务：不用进工作区页才知道它到点会自己跑 -->
                    <a-tooltip v-if="jobsOfSession(s.id).length" :title="jobsTip(s.id)">
                      <clock-circle-outlined class="sess-clock-flag" />
                    </a-tooltip>
                    <!-- 分享中：只标 enabled=1（链接还开着）。已停止的不标，避免噪音 -->
                    <a-tooltip v-if="s.shared" title="这段对话正在对外分享（只读链接生效中）">
                      <share-alt-outlined class="sess-share-flag" @click.stop="openShareManage(s.id)" />
                    </a-tooltip>
                    <span class="sess-title" :class="{ fav: s.isFavorite === 1 }">
                      <star-filled v-if="s.isFavorite === 1" class="sess-fav-flag" />{{ s.title || '新对话' }}
                    </span>
                    <template v-if="!batchMode">
                      <a-tooltip :title="s.isPinned === 1 ? '取消置顶' : '置顶'">
                        <button class="sess-op" :class="{ on: s.isPinned === 1 }" @click.stop="togglePin(s)"><pushpin-outlined /></button>
                      </a-tooltip>
                      <a-dropdown trigger="['click']" placement="bottomRight">
                        <button class="sess-op" @click.stop><more-outlined /></button>
                        <template #overlay>
                          <a-menu @click="({ key }) => sessionMenu(s, key)">
                            <a-menu-item key="rename"><edit-outlined /> 重命名</a-menu-item>
                            <a-menu-item key="favorite"><star-filled v-if="s.isFavorite === 1" /><star-outlined v-else /> {{ s.isFavorite === 1 ? '取消收藏' : '收藏' }}</a-menu-item>
                            <a-menu-item key="export"><download-outlined /> 导出 Markdown</a-menu-item>
                            <!-- 分享：常驻入口。分享动作本身在聊天页的分享弹窗里（那里才有「链接展示
                                 最新内容」的隐私提示与换新/停止），所以这里只发信号让聊天页打开弹窗。 -->
                            <a-menu-item key="share"><share-alt-outlined /> 分享</a-menu-item>
                            <a-menu-item v-if="s.shared" key="share-manage"><share-alt-outlined /> 分享管理</a-menu-item>
                            <a-menu-item key="batch"><check-square-outlined /> 批量管理</a-menu-item>
                            <a-menu-divider />
                            <a-menu-item key="delete" danger><delete-outlined /> 删除</a-menu-item>
                          </a-menu>
                        </template>
                      </a-dropdown>
                    </template>
                  </div>
                  <div v-if="!row.error && !row.loading && !row.items.length" class="side-empty">
                    {{ searchKw ? '没有匹配的会话' : '暂无会话' }}
                  </div>
                  <div v-if="!row.error && (row.hasMore || row.expanded)" class="space-foot">
                    <button v-if="row.hasMore" class="side-more-btn" :disabled="row.loadingMore" @click="loadSpaceMore(row)">
                      {{ row.loadingMore ? '加载中…' : `查看更多 (${Math.max(0, row.count - row.items.length)})` }}
                    </button>
                    <button v-if="row.expanded" class="side-more-btn muted" @click="collapseSpace(row)">收起</button>
                  </div>
                </template>
              </div>
            </template>
            </template>

            <!-- 整段收起时空态也不留：那一块位置此刻正被「分区标题 (N) >」这一行表达 -->
            <div v-if="sec.spacesSection && !secHidden(sec) && !sec.count && !sessionStore.loading" class="side-empty">
              {{ searchKw ? '没有匹配的会话' : '暂无工作区' }}
              <button v-if="!searchKw" class="side-inline-btn" @click="openCreateSpace">新建工作区</button>
            </div>
          </section>
        </template>
      </div>

      <!-- 重命名会话 -->
      <a-modal v-model:open="renameState.open" title="重命名会话" ok-text="保存" cancel-text="取消" @ok="doRename">
        <a-input v-model:value="renameState.title" :maxlength="50" placeholder="会话标题（≤50 字）"
                 @press-enter="doRename" />
      </a-modal>

      <div class="side-foot">
        <!-- 头像+昵称即「个人设置」入口（此前另有一个与头像语义重复的人形图标，折叠态还挤溢出） -->
        <a-tooltip :title="collapsed ? '个人设置（' + (userName || '未登录') + '）' : '个人设置'" placement="right">
          <button class="foot-user" @click="goProfile">
            <UserAvatar :avatar="userAvatar" :name="userName" :size="22" />
            <span v-if="!collapsed" class="user-name">{{ userName || '未登录' }}</span>
          </button>
        </a-tooltip>
        <!-- 站内通知铃铛：Badge=未读数（30s 轮询），点开拉列表；popover 挂 body，样式走底部非 scoped 块 -->
        <a-popover v-model:open="notifOpen" trigger="click" placement="topRight" overlay-class-name="notif-popover" @open-change="onNotifOpen">
          <template #content>
            <div class="notif-panel">
              <div class="notif-head">
                <span class="notif-title">通知</span>
                <div class="notif-head-actions">
                  <button v-if="unreadCount > 0" class="notif-readall" @click="markAllRead">全部已读</button>
                  <button class="notif-readall" @click="goNotifCenter">查看全部</button>
                </div>
              </div>
              <a-spin v-if="notifLoading" size="small" style="display:block;margin:24px auto" />
              <div v-else-if="!notifItems.length" class="notif-empty">暂无通知</div>
              <div v-else class="notif-list">
                <button v-for="n in notifItems" :key="n.id" class="notif-item" :class="{ unread: !n.readFlag }" @click="openNotif(n)">
                  <component :is="notifIcon(n.type)" class="notif-ic" :class="notifClass(n.type)" />
                  <span class="notif-body">
                    <span class="notif-item-title">{{ n.title }}<span v-if="!n.readFlag" class="notif-dot" /></span>
                    <span v-if="n.content" class="notif-content">{{ n.content }}</span>
                    <span class="notif-time">{{ notifTime(n.createTime) }}</span>
                  </span>
                </button>
              </div>
            </div>
          </template>
          <a-badge :count="unreadCount" :offset="[-4, 4]" size="small" :title="''">
            <a-tooltip title="通知" placement="right">
              <button class="app-icon-btn notif-bell"><bell-outlined /></button>
            </a-tooltip>
          </a-badge>
        </a-popover>
        <!-- 我的侧栏布局的入口已移到 logo 行（紧挨折叠按钮）：改的是这列菜单本身，
             属侧栏自身的形态操作，不该混在底部这排「通知/主题/退出」的全局动作里 -->
        <a-tooltip :title="themeState === 'dark' ? '切换到亮色主题' : '切换到暗色主题'" placement="right">
          <button class="app-icon-btn" @click="toggleTheme">
            <!-- 主题切换：亮色显月亮（点去暗色）、暗色显太阳（点去亮色），替代原先的灯泡 -->
            <svg v-if="themeState === 'dark'" class="theme-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="4"/>
              <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
            </svg>
            <svg v-else class="theme-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
            </svg>
          </button>
        </a-tooltip>
        <a-tooltip title="退出登录" placement="right">
          <button class="app-icon-btn" @click="doLogout"><logout-outlined /></button>
        </a-tooltip>
      </div>
    </aside>

    <!-- 配置引导抽屉：常驻入口点开，清单含向量模型项（scope=all）；跳转前由组件 emit close 关闭 -->
    <a-drawer v-model:open="guideOpen" placement="left" :width="400" title="配置引导">
      <SetupGuide scope="all" variant="plain" @close="guideOpen = false" />
    </a-drawer>

    <!-- 拖拽幽灵行：fixed 定位贴指针，走 transform 合成层。放 body 上是因为
         .side 有 overflow:hidden（折叠/窄屏抽屉态要裁），留在侧栏内幽灵会被切掉一角。
         editDragId 复位即销毁，与占位行的区别是它半透明浮着、原行留虚线框占位。 -->
    <teleport to="body">
      <div v-if="dragRow && ghostBox"
           class="nav-edit-ghost"
           :style="{ left: ghostBox.left + 'px', width: ghostBox.width + 'px', transform: `translateY(${ghostTop}px)` }">
        <span class="nav-edit-grip" aria-hidden="true">⋮⋮</span>
        <component :is="iconOf(dragRow.icon)" class="nav-edit-ic" />
        <span class="nav-edit-name">{{ dragRow.name }}</span>
      </div>
    </teleport>

    <!-- 抽屉遮罩：窄屏点它关闭侧栏。放在 aside 之后（z-index 更低），保证点击穿透到遮罩而非抽屉 -->
    <div v-if="!mobileShell && isNarrow && sideOpen" class="side-mask" @click="sideOpen = false"></div>

    <!-- 主内容区：窄屏/H5 外壳顶部插入移动顶栏（菜单 / 会话标题 / 搜索 / 新建 / 帮助[ / 通知]）
         mobileShell 单独判：手机横屏（约 900px）不是「窄屏」但仍是 H5，顶栏与 H5 菜单必须还在 -->
    <div class="main">
      <MobileTopBar v-if="isNarrow || mobileShell" :notif="mobileShell" :unread="unreadCount"
                    @toggle-side="onMenuEntry" @search="openSideSearch" @new-chat="newChat"
                    @notif="notifSheetOpen = true" />
      <div class="main-body">
        <DesktopOnlyGuard v-if="isNarrow || mobileShell" :path="route.path">
          <router-view />
        </DesktopOnlyGuard>
        <router-view v-else />
      </div>
    </div>

    <!-- 全局帮助入口：右下角悬浮「?」，任意页面就抽屉读手册（帮助中心整页 /help 上不重复出现） -->
    <HelpFab />

    <!-- ==================== H5 外壳（触屏）的菜单 ====================
         就是 /m/chat 左上角那个会话 sheet：会话列表（含置顶/重命名/删除/导出）+ 壳外入口
         （个人设置/帮助中心/我的产物/知识库/主题/退出）。非对话页点菜单按钮或「搜索会话」
         都开它——H5 里「左上角菜单」只有这一种形态，PC 抽屉不再出现。
         桌面与「鼠标用户拖窄窗口」mobileShell 恒 false，DOM 与行为零改动。 -->
    <MobileSessionSheet v-if="mobileShell" ref="shellSheetRef" :open="sessionsOpen"
                        :current-id="route.query.sid || ''"
                        @close="sessionsOpen = false"
                        @select="onSheetSelect" @new-chat="onSheetNewChat"
                        @profile="goShellPage('/profile')" @help="goShellPage('/help')"
                        @artifacts="goShellPage('/artifacts')" @knowledge="goShellPage('/knowledge')" />
    <!-- 通知 sheet：与侧栏铃铛 popover 同一份取数实例（provide 在 script 里） -->
    <MobileNotifSheet v-if="mobileShell" :open="notifSheetOpen" @close="notifSheetOpen = false" />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, provide, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, MessageOutlined, RobotOutlined, FolderOutlined, BarChartOutlined, SettingOutlined, ExperimentOutlined,
         MenuFoldOutlined, DeleteOutlined, DownloadOutlined, TeamOutlined, CompassOutlined,
         LogoutOutlined, UserOutlined, DatabaseOutlined, SafetyOutlined, AppstoreOutlined, FileOutlined,
         FileTextOutlined, SearchOutlined, CloseOutlined, PushpinOutlined, MoreOutlined, EditOutlined, StarFilled, StarOutlined,
         CheckOutlined, CheckSquareOutlined, QuestionCircleOutlined, PieChartOutlined, ShareAltOutlined,
         RightOutlined, DownOutlined, BellOutlined, UnorderedListOutlined,
         ClockCircleOutlined, FolderOpenOutlined, InboxOutlined, ReloadOutlined,
         ArrowsAltOutlined, ShrinkOutlined } from '@ant-design/icons-vue'
import { deleteSessionApi, logoutApi, renameSessionApi, pinSession, favoriteSession, batchDeleteSessionsApi,
         getMyMenuLayout, saveMyMenuLayout, setNewSessionProject,
         updateProject, deleteProject, createProject, setProjectStatus } from '../api'
import { useNotifications, notifIcon, notifClass, notifTime } from '../chat/useNotifications'
import { themeState, toggleTheme } from '../utils/theme'
import { authUser, ensureAuth, isAdminSync, clearAuth } from '../utils/auth'
import { chatDone, chatReady, defaultReady, embeddingReady, pendingCount, refreshSetupGuide, setupGuide,
         advPendingCount } from '../utils/setupGuide'
import { sessionStore, loadSessions, loadTasks, loadSpaceMore, collapseSpace, reloadSpace, toggleArchived, visibleSessions, chatStreams, NO_SPACE_KEY } from './store'
import { isNarrow, mobileShell } from '../h5/mobile'
import BrandMark from '../components/BrandMark.vue'
import HelpFab from '../components/HelpFab.vue'
import MobileTopBar from '../h5/MobileTopBar.vue'
import MobileSessionSheet from '../h5/MobileSessionSheet.vue'
import MobileNotifSheet from '../h5/MobileNotifSheet.vue'
import DesktopOnlyGuard from '../h5/DesktopOnlyGuard.vue'
import UserAvatar from '../components/UserAvatar.vue'
import SetupGuide from '../components/SetupGuide.vue'
import { exportSessionMarkdown } from './exportMd'
import './app.css'

const route = useRoute()
const router = useRouter()
const isAdmin = ref(isAdminSync())
// 侧栏显示名：读 auth.js 的响应式镜像——个人设置改完昵称 ensureAuth(true) 后这里立即跟着变，无需刷新
const userName = computed(() => {
  const i = authUser.value
  return ((i && (i.username || i.user)) || '')
})
// 头像同理做空安全：身份就绪前 authUser 可能为 null（守卫时序已被 router.js 收口，
// 此处防御模板直读 .avatar 抛 Cannot read properties of null）
const userAvatar = computed(() => {
  const i = authUser.value
  return (i && i.avatar) || ''
})

// 侧边栏菜单：/auth/me 下发的菜单树（顶级渲染为导航项；子级预留，当前侧边栏一层平铺）
const ICONS = {
  MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined, BarChartOutlined,
  ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined, PlusOutlined,
  FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined, QuestionCircleOutlined,
  PieChartOutlined
}
const iconOf = name => ICONS[name] || FileOutlined
// ensureAuth 填充的是模块级缓存（非响应式），故挂载后显式赋值
const navMenus = ref([])

/**
 * 布局保存后重拉 /auth/me：菜单树与个人偏好都在这个响应里，重拉即生效。
 * 不本地改 navMenus —— 那样会与服务端算出的结果漂移（下次刷新又变回去），
 * 看起来保存了其实没保存。
 */
/**
 * 菜单树 → 侧栏可见项。挂载首拉与保存后重拉共用这一份，避免两处 filter 各写一遍。
 * 某一项占不占侧栏位是菜单表的数据：visible=0 的行服务端不下发，tab/hidden 型在此处不渲染。
 * 刻意不为某个路径开前端例外 —— 那样管理员在「权限管理」里把菜单改回显示也没用，只能改代码。
 */
const sidebarMenusOf = menus => (menus || [])
  .filter(m => m && (m.renderAs === 'group' || (m.renderAs !== 'tab' && m.renderAs !== 'hidden' && m.path)))

async function reloadMenus () {
  const info = await ensureAuth(true)
  // 侧栏只渲染 sidebar + group；tab 由宿主页（AgentsHubPage 等）按 parentId 取用，hidden 纯权限容器
  // group 无 path 也保留（title 渲染用 name）；menuPending/menuTitle 对无 path 菜单天然安全
  navMenus.value = sidebarMenusOf(info && info.menus)
}

// ==================== 侧栏就地编辑（个人偏好，独立于角色权限） ====================
// 入口在 logo 行（紧挨折叠按钮），不另开面板：改的就是这列菜单，就地改最省心智。
// 可见性口径与权限是两件事 —— 谁能**看到**哪些菜单 = RBAC；我把看到的**怎么排、藏哪几个** = 这里。
//
// 草稿模型：进入编辑时拉一次服务端状态存进 editRows（权威值），改动只落草稿，
// 点「保存」才提交；点编辑按钮再次退出则丢弃草稿（不重拉 —— 重拉会把未保存的改动覆盖成服务端值，
// 视觉上像"我的改动自己消失了"）。
const navEditing = ref(false)
const navEditRows = ref([])          // [{ id, name, icon, renderAs, hidden }] 草稿
const navEditBase = ref('')          // 进入编辑时的原始签名，用于算脏
const navEditSaving = ref(false)
const navEditLoading = ref(false)

const editSig = () => navEditRows.value.map(r => `${r.id}:${r.hidden ? 0 : 1}`).join('|')
const navEditDirty = computed(() => editSig() !== navEditBase.value)
/**
 * 是否真的渲染编辑器 = 在编辑态 **且** 侧栏展开。折叠态下 56px 图标条容不下带文字和开关的
 * 行（实测会溢出），所以模板一律判这个派生值而不是裸 navEditing。
 * 主路径是 toggleFold 里调 exitNavEdit 把状态清掉；这里是模板层的第二道保险，
 * 防的是将来有别处直接改 collapsed 而绕过那条联动。
 */
const showNavEditor = computed(() => navEditing.value && !collapsed.value)

/**
 * 退出编辑态并丢弃未保存草稿。navMenus 始终是服务端算出的权威结果、从未被本地改过，
 * 所以只要清掉草稿状态就等于回到编辑前的样子，**不需要**重拉 /auth/me
 * （重拉反而会覆盖用户刚看到的默认态，观感上像"我的改动自己消失了"）。
 *
 * 复用场景：点编辑按钮退出、**折叠侧栏**（见 toggleFold）。
 */
function exitNavEdit () {
  navEditing.value = false
  navEditRows.value = []
  navEditBase.value = ''
}

async function toggleNavEdit () {
  if (navEditing.value) { exitNavEdit(); return }
  // 必须**等数据到位再切形态**：早一步就渲染出一个空编辑器，
  // navEditRows 还是[] ⇒ 整列菜单瞬间塌成一行、接口回来后又撑开 —— 视觉上就是「闪一下」。
  // 期间按钮置loading，既挡住连点也让这段等待有交代。
  navEditLoading.value = true
  try { await loadingNavEdit() } finally { navEditLoading.value = false }
  navEditing.value = true
}

/** 进入编辑时载入可调项全集（含已被自己隐藏的——否则开关就再也打不回来） */
async function loadingNavEdit () {
  const r = await getMyMenuLayout()
  const data = (r && r.data) || {}
  const hidden = new Set(data.hidden || [])
  // 行数据取服务端全集（menu_pref 未剔除 hidden）；失败才回退 /auth/me 的 menus
  const src = Array.isArray(data.menus) && data.menus.length
    ? data.menus
    : navMenus.value.filter(m => m.renderAs !== 'tab' && m.renderAs !== 'hidden')
  navEditRows.value = src.map(m => ({
    id: m.id, name: m.name, icon: m.icon, renderAs: m.renderAs || 'sidebar', hidden: hidden.has(m.id)
  }))
  // 已有偏好时把顺序套回来（用户保存过的 order 对应服务端下发的排列，
  // 而 my-layout 的 menus 是「未施加偏好」的原始表序）
  if (Array.isArray(data.order) && data.order.length) {
    const rank = new Map(data.order.map((id, i) => [id, i]))
    navEditRows.value = navEditRows.value
      .map((r, i) => ({ r, k: rank.has(r.id) ? rank.get(r.id) : Number.MAX_SAFE_INTEGER - (navEditRows.value.length - i) }))
      .sort((a, b) => a.k - b.k)
      .map(x => x.r)
  }
  navEditBase.value = editSig()
}

/** 开关回调给的是「新的 checked」，草稿存的是 hidden（取反） */
function toggleHidden (id, checked) {
  const r = navEditRows.value.find(x => x.id === id)
  if (r) r.hidden = !checked
}

async function saveNavLayout () {
  navEditSaving.value = true
  try {
    const res = await saveMyMenuLayout({
      order: navEditRows.value.map(r => r.id),
      hidden: navEditRows.value.filter(r => r.hidden).map(r => r.id)
    })
    if (res && res.success) {
      message.success('侧栏已更新')
      // 先重拉再退编辑态：反过来做的话，退编辑瞬间渲染的是**旧顺序**的 navMenus，
      // 等接口回来才换成新顺序 —— 顺序先倒退一次再跳回来，就是保存后那一下「闪」。
      // 菜单树与偏好都在 /auth/me 一个响应里，重拉即生效。
      await reloadMenus()
      exitNavEdit()
    } else message.error((res && res.msg) || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { navEditSaving.value = false }
}

async function resetNavDefault () {
  navEditSaving.value = true
  try {
    const res = await saveMyMenuLayout({ reset: true })
    if (res && res.success) {
      message.success('已恢复默认侧栏')
      await reloadMenus()
      exitNavEdit()
    } else message.error((res && res.msg) || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { navEditSaving.value = false }
}

/* ---------------- 拖拽排序 ----------------
 * 为什么不用原生 HTML5 DnD：
 * 1. 拖影是浏览器在 dragstart 拍的**静态快照**，行在脚下让位后快照不会更新 ——
 *    屏幕上同时出现「快照」和「真实行」两份，一个跟着鼠标、一个在被重排，
 *    视觉上就是拖拽过程中的「闪一下」（首版就是这么写的，实测很明显）；
 * 2. dragover 每帧触发数次，每次越过中线就 splice 一次数组，列表反复重排，
 *    而且没有过渡 ⇒ 位移是瞬跳而非滑动。
 * 改为指针事件自绘：一个 fixed 的幽灵行跟手（transform 合成层，不触发布局），
 * 原行留作虚线占位，其他行用 FLIP（先量后移，差值补transform 再放行）滑到新位。
 * 三点注意：
 * 1. 阈值 4px 才起拖 —— 否则一次普通点击会被判成拖拽（抖一下就取消，用户观感更差）；
 * 2. 监听挂window 而非元素：指针移出侧栏、或在松手前划出浏览器窗口也不丢事件；
 * 3. 开关上 @pointerdown.stop 掐断起点，否则拨开关会顺带把行拖走。 */
const editDragId = ref(null)
const ghostTop = ref(0)       // 幽灵行 top（fixed，跟指针）
const ghostBox = ref(null)    // { left, width } 按下瞬间量，用于幽灵行对齐原行
const navEl = ref(null)
let grabOffset = 0            // 指针按下点距行顶的距离：抓住哪一块就按哪一块拖
let dragging = false

/** id → 行元素（编辑态才存在，用于量位置） */
function rowEl (id) {
  if (!navEl.value) return null
  return navEl.value.querySelector(`.nav-edit-row[data-id="${id}"]`)
}
/** 拖动行当前所在位置（数组重排会带着它跑，落点判定必须用实时位置而非按下时的） */
function dragRowTop () {
  const el = rowEl(editDragId.value)
  return el ? el.getBoundingClientRect().top : 0
}

function onEditPointerDown (id, ev) {
  if (ev.button != null && ev.button !== 0) return
  const el = rowEl(id)
  if (!el) return
  grabOffset = ev.clientY - el.getBoundingClientRect().top
  editDragId.value = id
  dragging = false
  ghostBox.value = { left: el.getBoundingClientRect().left, width: el.getBoundingClientRect().width }
  window.addEventListener('pointermove', onEditPointerMove)
  window.addEventListener('pointerup', onEditPointerUp)
  window.addEventListener('pointercancel', onEditPointerUp)
}
function onEditPointerMove (ev) {
  if (!editDragId.value) return
  // 未达阈值前不动手：普通点击不该被拖拽取消动作搅一下
  if (!dragging && Math.abs(ev.clientY - (dragRowTop() + grabOffset)) < 4) return
  dragging = true
  ghostTop.value = ev.clientY - grabOffset
  reorderToPointer(ev.clientY)
}
function onEditPointerUp () {
  window.removeEventListener('pointermove', onEditPointerMove)
  window.removeEventListener('pointerup', onEditPointerUp)
  window.removeEventListener('pointercancel', onEditPointerUp)
  editDragId.value = null
  ghostBox.value = null
  dragging = false
  // 兜底：动画中途松手/被cancel 时清掉残留的transform，否则下次 FLIP 会带着旧偏移起跑
  clearFlipOffsets()
}
onUnmounted(onEditPointerUp)

/**
 * 落点判定：第一个「中线在指针下方」的非拖动行之前；都不在则排到末尾。
 * 以中线为界而不是行顶/行底 —— 行高与指针命中区不对称，用顶/底会让「拖过一半」
 * 的手感明显偏早或偏晚。逐行量getBoundingClientRect（含正在滑动的动画位置），
 * 因此动画进行中也不会跳。
 */
function reorderToPointer (y) {
  const list = navEditRows.value
  const fi = list.findIndex(r => r.id === editDragId.value)
  if (fi < 0) return
  const rest = list.filter(r => r.id !== editDragId.value)
  let idx = rest.length
  for (let i = 0; i < rest.length; i++) {
    const el = rowEl(rest[i].id)
    if (!el) continue
    const r = el.getBoundingClientRect()
    if (y < r.top + r.height / 2) { idx = i; break }
  }
  const next = rest.slice(0, idx).concat([list[fi]], rest.slice(idx))
  // 位置没变就别动数组：一次赋值就是一次 patch，dragover 级别的高频调用下这是主要开销来源
  if (next.every((r, i) => r.id === list[i].id)) return
  const before = captureRects()
  navEditRows.value = next
  playFlip(before)
}

/** FLIP：量下移动前的各行 top */
function captureRects () {
  const map = new Map()
  if (!navEl.value) return map
  navEl.value.querySelectorAll('.nav-edit-row').forEach(el => {
    map.set(el.dataset.id, el.getBoundingClientRect().top)
  })
  return map
}
/**
 * FLIP 的 F/L：补transform 让行「先留在原处」，再放行让它滑到新位。
 * 强制读一次 offsetHeight 触发重排——不打断这个节奏，补上的 transform 会被
 * 连同新位置一起算掉，动画就退化成瞬移（等于没做）。
 * 拖动行自己不走 FLIP（它由指针直接驱动，且dragging 类禁用 transition）。
 */
function playFlip (before) {
  nextTick(() => {
    if (!navEl.value) return
    navEl.value.querySelectorAll('.nav-edit-row').forEach(el => {
      const b = before.get(el.dataset.id)
      if (b == null) return
      const dy = b - el.getBoundingClientRect().top
      if (!dy) return
      el.style.transition = 'none'
      el.style.transform = `translateY(${dy}px)`
      void el.offsetHeight
      el.style.transition = ''
      el.style.transform = ''
    })
  })
}
function onFlipEnd (e) {
  if (e.target && e.target.style) {
    e.target.style.transform = ''
    e.target.style.transition = ''
  }
}
function clearFlipOffsets () {
  if (!navEl.value) return
  navEl.value.querySelectorAll('.nav-edit-row').forEach(el => {
    if (el.style.transform) { el.style.transform = ''; el.style.transition = '' }
  })
}

/** 幽灵行数据：与占位行同源，改名/显隐立刻反映在指针下 */
const dragRow = computed(() =>
  editDragId.value ? navEditRows.value.find(r => r.id === editDragId.value) || null : null)

// 侧边栏折叠（持久化）
const collapsed = ref(localStorage.getItem('app_sidebar') === '1')
const toggleFold = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem('app_sidebar', collapsed.value ? '1' : '0')
  // 收起时顺带结束侧栏编辑：折叠是用户明确表示「收起这列」，编辑态跟着一起结束。
  // 不这么做的话，编辑行/操作条只判 navEditing 不判 collapsed，会挤进 56px 图标条——
  // 带文字和开关的行在图标条里必然溢出（实测：开关悬在条外、「恢复默认」文字糊成一团）。
  // 未保存的草稿直接丢弃，语义同「点编辑按钮退出」。
  // 编辑态里折叠按钮已隐身（模板 .slot-hidden），所以这条联动是第二道保险：
  // 防的是将来别处直接改 collapsed 而绕过按钮。
  if (collapsed.value) exitNavEdit()
}

/* ---------------- 长按菜单区 = 直接进侧栏编辑 ----------------
 * 展开态下「编辑侧栏」按钮平时隐身（鼠标移到 logo 行才浮出），触屏更是连 hover 都没有，
 * 所以再给一条常驻的手势通道：按住菜单区 0.5s 直接进编辑态。
 * 四条边界，缺一条都会误伤日常操作：
 * 1. 长按触发后必须**吞掉紧随其后的那次 click**：松手时指针下还是原来那一项，
 *    不吞就变成「想改顺序，结果被带去别的页面」（按在「新建对话」上更直接，会新开一轮对话）；
 * 2. 移动超过 8px、指针抬起/取消、侧栏内滚动都判为「不是长按」——拖拽与滚动优先；
 * 3. 编辑态内不再起计时：那片区域的手势已被编辑行的拖拽排序占满；
 * 4. 折叠态也认这个手势：先展开再进编辑态——56px 图标条里放不下带文字和开关的编辑行。
 * 监听挂 window 而非 nav（同拖拽排序的取舍）：指针划出侧栏、或在栏外松手也收得到。
 */
const LONG_PRESS_MS = 500
const LONG_PRESS_SLOP = 8
let lpTimer = null
let lpFrom = null          // 按下点，用于判定「手抖还是拖动」
let lpSwallow = false      // 长按已触发 → 下一次 click 要吞掉

function onNavPressStart (ev) {
  // 新手势开始：上一轮长按若没换来 click（指针移出侧栏才松手），标记到此为止，
  // 否则它会吃掉这一次正常点击
  lpSwallow = false
  if (showNavEditor.value) return
  if (ev.button != null && ev.button !== 0) return   // 右键/中键不是长按，别抢右键菜单
  clearNavPress()
  lpFrom = { x: ev.clientX, y: ev.clientY }
  lpTimer = setTimeout(fireNavLongPress, LONG_PRESS_MS)
  window.addEventListener('pointermove', onNavPressMove)
  window.addEventListener('pointerup', clearNavPress)
  window.addEventListener('pointercancel', clearNavPress)
  window.addEventListener('scroll', clearNavPress, true)
}
function onNavPressMove (ev) {
  if (!lpFrom) return
  if (Math.abs(ev.clientX - lpFrom.x) > LONG_PRESS_SLOP ||
      Math.abs(ev.clientY - lpFrom.y) > LONG_PRESS_SLOP) clearNavPress()
}
function clearNavPress () {
  clearTimeout(lpTimer)
  lpTimer = null
  lpFrom = null
  window.removeEventListener('pointermove', onNavPressMove)
  window.removeEventListener('pointerup', clearNavPress)
  window.removeEventListener('pointercancel', clearNavPress)
  window.removeEventListener('scroll', clearNavPress, true)
}
function fireNavLongPress () {
  clearNavPress()
  lpSwallow = true
  if (navEditLoading.value) return   // 正在拉布局：再触发一次只会叠一次请求
  if (collapsed.value) toggleFold()  // 图标条里长按：先把侧栏展开，编辑 UI 才有地方放
  if (!navEditing.value) toggleNavEdit()
}
/** capture 阶段拦截长按后那一次 click：要拦在目标之前——编辑行里开关的 @click.stop 也在其内 */
function onNavClickCapture (ev) {
  if (!lpSwallow) return
  lpSwallow = false
  ev.preventDefault()
  ev.stopPropagation()
}
/** 触屏长按会顺手唤起系统菜单/文本选择（按住期间 lpTimer 还在），一律掐掉；
 *  桌面右键因为没起计时所以不受影响 */
function onNavContextMenu (ev) {
  if (lpTimer || lpSwallow) ev.preventDefault()
}
onUnmounted(clearNavPress)

const allVisibleSessions = computed(visibleSessions)
// 会话行在摊平列表里的全序下标：生成中的会话按它取呼吸相位（见 .sess-item.running）。
// 分组只改渲染位置、不改顺序，所以平铺下标就是它的相位序号。
const sessRowOrdinal = computed(() => {
  const m = new Map()
  sessionStore.list.forEach((s, i) => m.set(s.id, i))
  return m
})

// ==================== 工作区分组 ====================
/** 工作区折叠状态（按工作区 key 记忆，默认全展开）；搜索时强制全展开——搜到却看不见是死胡同 */
const SPACES_KEY = 'app_space_collapsed'
const collapsedSpaces = reactive(new Set((() => {
  try { return JSON.parse(localStorage.getItem(SPACES_KEY) || '[]') } catch (e) { return [] }
})()))
const spaceHidden = space => !searchKw.value && collapsedSpaces.has(space.key)
const persistCollapsedSpaces = () => {
  try { localStorage.setItem(SPACES_KEY, JSON.stringify([...collapsedSpaces])) } catch (e) { /* 忽略 */ }
}
const toggleSpace = space => {
  // 搜索态强制全展开（搜到却看不见是死胡同）：此时点名字不写折叠状态，
  // 否则清空搜索后会突然发现这个工作区被折叠了，而用户从没看到它折起来过
  if (searchKw.value) return
  if (collapsedSpaces.has(space.key)) collapsedSpaces.delete(space.key)
  else collapsedSpaces.add(space.key)
  persistCollapsedSpaces()
}

// 分区数据：第一段「会话」= 不属于任何真实工作区的会话（没挂工作区 + implicit 兜底容器，
// 后端用 __nospace__ 一次过滤）；第二段「工作区」= 用户新建的工作区 + 已归档。
// 两段共用同一套行模板，bare 控制有没有工作区头。
const ARCHIVED_HEAD = { key: '__archived_head__', archivedHead: true }
const soloSpace = computed(() => sessionStore.spaces.find(s => s.key === NO_SPACE_KEY) || null)
const projectSpaces = computed(() => sessionStore.spaces.filter(s => s.key !== NO_SPACE_KEY))
const sidebarSections = computed(() => {
  const out = []
  const solo = soloSpace.value
  // 搜索态 0 命中的「会话」段不占位置；非搜索态即使没有没挂工作区会话也保留空态
  // （对应基准稿里的「任务 (0) / 暂无任务」：位置稳定，用户才形成肌肉记忆）
  const soloVisible = Boolean(solo) && (!searchKw.value || solo.count > 0)
  if (soloVisible) {
    out.push({ key: 'solo', title: '会话', count: solo.count, rows: [solo], bare: true, spacesSection: false })
  }
  const rows = [...projectSpaces.value]
  // 搜索态不显示归档（搜索的是会话，不是翻旧工作区），也就省掉归档工作区的懒加载
  if (!searchKw.value && sessionStore.archived.length) {
    rows.push(ARCHIVED_HEAD)
    if (sessionStore.archivedOpen) rows.push(...sessionStore.archived)
  }
  // 搜索态只剩「会话」有命中时，不再额外渲染一块「工作区 (0) / 没有匹配」——
  // 两块空态上下叠着，会让人以为工作区加载失败
  if (!searchKw.value || rows.length || !soloVisible) {
    out.push({ key: 'spaces', title: '工作区', count: projectSpaces.value.length, rows, bare: false, spacesSection: true })
  }
  return out
})

/** 表头「展开/折叠全部」的作用域 = 此刻列在「工作区」段里的工作区行
    （归档头不是工作区；已归档没展开时那些工作区也不在内——只管看得见的这一批） */
const foldableSpaces = computed(() => {
  const sec = sidebarSections.value.find(s => s.key === 'spaces')
  return sec ? sec.rows.filter(r => !r.archivedHead) : []
})
const allSpacesExpanded = computed(() =>
  foldableSpaces.value.length > 0 && foldableSpaces.value.every(s => !spaceHidden(s)))
function foldAllSpaces (collapse) {
  for (const s of foldableSpaces.value) {
    if (collapse) collapsedSpaces.add(s.key)
    else collapsedSpaces.delete(s.key)
  }
  persistCollapsedSpaces()
}

/** 分区整段的收起状态（表头那一行本身是开关，「会话」「工作区」各记各的）。
    与单个工作区的折叠是两件事：整段收起=连工作区名一起藏掉，单个收起=只藏这个工作区下的会话。
    搜索态强制展开（同 spaceHidden 的理由：搜到却看不见是死胡同）。 */
const SEC_FOLD_KEY = 'app_side_section_folded'
const foldedSections = reactive(new Set((() => {
  try { return JSON.parse(localStorage.getItem(SEC_FOLD_KEY) || '[]') } catch (e) { return [] }
})()))
const secHidden = sec => foldedSections.has(sec.key) && !searchKw.value
function toggleSection (sec) {
  // 同 toggleSpace：搜索态点表头不写折叠状态，否则清空搜索后凭空多出一段收起的分区
  if (searchKw.value) return
  if (foldedSections.has(sec.key)) foldedSections.delete(sec.key)
  else foldedSections.add(sec.key)
  try { localStorage.setItem(SEC_FOLD_KEY, JSON.stringify([...foldedSections])) } catch (e) { /* 忽略 */ }
}

// 会话行上的时钟标记：只标启用中的任务（停用的提示「它会自己跑」是假的）
const jobsBySession = computed(() => {
  const map = {}
  for (const j of sessionStore.tasks) {
    if (!j.sessionId || !j.enabled) continue
    ;(map[j.sessionId] = map[j.sessionId] || []).push(j)
  }
  return map
})
const jobsOfSession = sid => jobsBySession.value[sid] || []
const jobsTip = sid => jobsOfSession(sid).map(j => `${j.name}（${j.cron}）`).join('\n')

/** 会话行悬停提示：标题太长被截断时补全文（所属工作区已由分组头表达，不再重复） */
const sessTooltip = s => s.title || '新对话'
const isActive = p => route.path === p
// 窄屏抽屉开合。不落 localStorage：抽屉是「当前这一秒的临时状态」，
// 刷新后默认关着才是符合预期的（记住它会让下次进来莫名其妙开着一个遮罩）
const sideOpen = ref(false)

// ==================== H5 外壳（触屏）的菜单 ====================
// 就是 /m/chat 那个会话 sheet（会话列表 + 壳外入口）。AppLayout 没有 chat 引擎，
// sheet 内部对「删除会话」做了回退（直接调接口），落点差异见 MobileSessionSheet 注释
const sessionsOpen = ref(false)
const notifSheetOpen = ref(false)
const shellSheetRef = ref(null)
// 顶栏菜单按钮：触屏开 H5 sheet，桌面窄窗口仍开抽屉
const onMenuEntry = () => {
  if (mobileShell.value) sessionsOpen.value = true
  else sideOpen.value = !sideOpen.value
}
// 选会话 / 新建 / 壳外页面：落点与移动壳一致——/chat 由路由守卫改写成 /m/chat（sid 随 query 透传）
const onSheetSelect = sid => {
  sessionsOpen.value = false
  router.push({ path: '/chat', query: { sid } }).catch(() => {})
}
const onSheetNewChat = () => { sessionsOpen.value = false; newChat() }
const goShellPage = path => { sessionsOpen.value = false; router.push(path).catch(() => {}) }

// 路由变化即关抽屉/关 sheet：窄屏下选完会话就该看到对话内容，而不是隔着一层遮罩。
// 转宽屏也必须关——遮罩是 fixed 的，留着会把整个宽屏盖住
watch(() => route.fullPath, () => {
  if (isNarrow.value) sideOpen.value = false
  sessionsOpen.value = false
  notifSheetOpen.value = false
})
watch(isNarrow, v => { if (!v) sideOpen.value = false })

// 顶栏「搜索」按钮：打开菜单并把光标送进搜索框。只打开的话用户的意图（找会话）没有一步到位，
// 还得再点一次输入框。抽屉是 transform 动画，等它到位再聚焦，否则聚焦动作发生在
// 元素还在屏外时会被浏览器忽略（H5 sheet 的聚焦节拍由 sheet 自己等，见其 focusSearch）
const searchInputRef = ref(null)
const openSideSearch = () => {
  if (mobileShell.value) {
    sessionsOpen.value = true
    if (shellSheetRef.value) shellSheetRef.value.focusSearch()
    return
  }
  sideOpen.value = true
  setTimeout(() => searchInputRef.value && searchInputRef.value.focus(), 260)
}

// 导航跳转。**仅窄屏**在 /chat 内切换会话时用 replace：
// 手机返回键是主导航，若每次切换会话都 push，A→B→C 连点三次后要按 3 次返回才能离开对话页。
// 宽屏保持 push —— 桌面有侧栏点选，用户几乎不用返回键，且 push 保留了「从别的页进对话」
// 与「对话内换会话」在历史里的可区分性，改了反而是行为变更。
const goChat = to => {
  const done = (isNarrow.value && route.path === '/chat') ? router.replace(to) : router.push(to)
  done.catch(() => {})
}
/**
 * 新建对话。projectId 可选：工作区右侧的 + 传该工作区 id；顶栏「新建对话」不传 = 不挂工作区。
 * 落点写进 api 的新会话目标（newSession() 唯一入口在聊天引擎里），再发跨页信号。
 */
const newChat = (projectId = '') => {
  setNewSessionProject(projectId)
  sessionStore.newChatTick++
  goChat('/chat')
}
const openSession = sid => goChat({ path: '/chat', query: { sid } })

// 菜单「分享」：切到该会话并让聊天页弹出分享面板。
// 信号必须**在路由切换之后**再发：聊天页是拿到路由才 loadHistory，弹窗的前置条件是
// messages 非空（同页 openShare 的守卫），先发信号会在历史到达前就被挡掉。
// 目标就是当前会话时 router.push 是 no-op（不产生新历史记录），行为与切会话一致。
const requestShareOpen = s => {
  if (!s || !s.id) return
  goChat({ path: '/chat', query: { ...route.query, sid: s.id } })
  sessionStore.shareOpenTick++
}

// 整会话导出 Markdown（无需先打开会话）
const exportSessionMd = s => {
  if (s && s.id) exportSessionMarkdown(s.id, s.title || 'AI对话')
}

// ==================== 会话管理（重命名/置顶/收藏/搜索：后端接口既有，此处接线 UI） ====================
const togglePin = async s => {
  try {
    await pinSession(s.id, s.isPinned !== 1)
    await loadSessions()
  } catch (e) { message.error(e.message || '操作失败') }
}
const toggleFavorite = async s => {
  try {
    await favoriteSession(s.id, s.isFavorite !== 1)
    await loadSessions()
  } catch (e) { message.error(e.message || '操作失败') }
}
const renameState = reactive({ open: false, id: '', title: '' })
const renameSession = s => {
  renameState.open = true
  renameState.id = s.id
  renameState.title = s.title || ''
}
const doRename = async () => {
  const t = renameState.title.trim()
  if (!t) { message.warning('标题不能为空'); return }
  try {
    await renameSessionApi(renameState.id, t)
    message.success('已重命名')
    renameState.open = false
    await loadSessions()
  } catch (e) { message.error(e.message || '重命名失败') }
}
const sessionMenu = (s, key) => {
  if (key === 'rename') renameSession(s)
  else if (key === 'favorite') toggleFavorite(s)
  else if (key === 'export') exportSessionMd(s)
  else if (key === 'share') requestShareOpen(s)
  else if (key === 'share-manage') openShareManage(s.id)
  else if (key === 'batch') enterBatchMode()
  else if (key === 'delete') confirmDelete(s)
}
const confirmDelete = s => {
  Modal.confirm({
    title: '删除该会话？',
    content: '会话与消息记录会被删除，不可恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: () => delSession(s.id)
  })
}
// 会话搜索（300ms 防抖走后端 keyword 检索；空串恢复全量）
const searchKw = ref('')
// ==================== 工作区操作 ====================
// 侧栏把会话按工作区收束；工作区落到数据模型就是后端一行 Project 容器（一个工作目录 + 一组会话与产物），
// 界面上只叫「工作区」。新建 / 重命名 / 归档 / 删除都在各自的 ⋯ 菜单里，不再做一个全局下拉。
const createOpen = ref(false)
const createName = ref('')
const createSaving = ref(false)
const renameOpen = ref(false)
const renameTarget = ref(null)
const renameName = ref('')
const renameSaving = ref(false)

const openCreateSpace = () => { createName.value = ''; createOpen.value = true }
const openRenameSpace = space => {
  renameTarget.value = space
  renameName.value = space.name || ''
  renameOpen.value = true
}

const spaceMenu = (space, key) => {
  if (!space) return
  if (key === 'open') {
    if (space.id) router.push('/project/' + space.id).catch(() => {})
    return
  }
  if (key === 'rename') { openRenameSpace(space); return }
  if (key === 'archive' || key === 'restore') {
    const next = key === 'archive' ? 'archived' : 'active'
    setProjectStatus(space.id, next).then(async () => {
      // 归档后这个工作区不再有「新建对话」入口；如果它正是新会话的落点，清掉，
      // 否则下一次「新建对话」会悄悄落进一个已经收起的工作区
      if (next === 'archived') setNewSessionProject('')
      message.success(next === 'archived' ? '已归档' : '已恢复')
      await loadSessions(undefined, { refreshProjects: true })
    }).catch(e => message.error('操作失败：' + (e.message || '请重试')))
    return
  }
  if (key === 'delete') {
    Modal.confirm({
      title: '删除这个工作区？',
      content: '对话与产物仍能看到，但它的文件将再也打不开，工作区约定也会一起丢失。此操作不可撤销。',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteProject(space.id)
          message.success('已删除')
          // 删掉的正是「下次新对话」的目标工作区 → 清掉，否则新会话会带一个不存在的 projectId
          setNewSessionProject('')
          await loadSessions(undefined, { refreshProjects: true })
        } catch (e) {
          message.error('删除失败：' + (e.message || '请重试'))
        }
      }
    })
  }
}

const doCreateProject = async () => {
  const name = (createName.value || '').trim()
  if (!name) {
    message.warning('请填写工作区名')
    return
  }
  createSaving.value = true
  try {
    const r = await createProject(name)
    const created = (r && r.data && r.data.id) || ''
    createOpen.value = false
    message.success('工作区已创建')
    await loadSessions(undefined, { refreshProjects: true })
    // 建完直接落到它的工作区页：第一件事就是写约定（那里有三步引导）
    if (created) {
      setNewSessionProject(created)
      router.push('/project/' + created)
    }
  } catch (e) {
    message.error('创建失败：' + (e.message || '请重试'))
  } finally {
    createSaving.value = false
  }
}

const doRenameProject = async () => {
  const p = renameTarget.value
  const name = (renameName.value || '').trim()
  if (!p || !name) {
    message.warning('工作区名不能为空')
    return
  }
  renameSaving.value = true
  try {
    await updateProject(p.id, { name })
    message.success('已重命名')
    renameOpen.value = false
    await loadSessions(undefined, { refreshProjects: true })
  } catch (e) {
    message.error('重命名失败：' + (e.message || '请重试'))
  } finally {
    renameSaving.value = false
  }
}
let searchTimer = null
// 会话区滚动容器：换搜索词（或清空）后回到顶部，否则用户停在上一屏的位置，
// 看到的是「搜索结果之外」的旧区域，像是搜索没生效
const sessionsEl = ref(null)
const scrollSessionsTop = () => { if (sessionsEl.value) sessionsEl.value.scrollTop = 0 }
const onSearchInput = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(async () => {
    await loadSessions(searchKw.value.trim())
    scrollSessionsTop()
  }, 300)
}
const clearSearch = () => {
  searchKw.value = ''
  clearTimeout(searchTimer)
  loadSessions('')
  scrollSessionsTop()
}
onUnmounted(() => clearTimeout(searchTimer))

const delSession = async sid => {
  try {
    // 删除流式中的会话：先中止其后台流（连接断开后后端在下次 SSE 发送失败时取消本轮，
    // 不再往已删除的会话落库回答），并移除记录防止切回时把死消息接回视图
    const st = chatStreams.get(sid)
    if (st) {
      chatStreams.delete(sid)
      st.abort.abort()
    }
    await deleteSessionApi(sid)
    message.success('会话已删除')
    await loadSessions()
    if (route.query.sid === sid) {
      router.push('/chat').catch(() => {})
      sessionStore.autoPickTick++
    }
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 批量删除（后端 /sessions/batch-delete 既有：逐条校验归属、软删、返回成功数） ====================
const batchMode = ref(false)
const batchSel = ref(new Set())
const allSelected = computed(() =>
  allVisibleSessions.value.length > 0 && allVisibleSessions.value.every(s => batchSel.value.has(s.id)))
const enterBatchMode = () => { batchMode.value = true; batchSel.value = new Set() }
const exitBatchMode = () => { batchMode.value = false; batchSel.value = new Set() }
const toggleBatchSel = id => {
  const next = new Set(batchSel.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  batchSel.value = next
}
const toggleSelectAll = () => {
  batchSel.value = allSelected.value ? new Set() : new Set(allVisibleSessions.value.map(s => s.id))
}
const confirmBatchDelete = () => {
  const ids = [...batchSel.value]
  if (!ids.length) return
  Modal.confirm({
    title: `删除选中的 ${ids.length} 个会话？`,
    content: '会话与消息记录会被删除，不可恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: () => doBatchDelete(ids)
  })
}
const doBatchDelete = async ids => {
  try {
    // 与单个删除一致：先中止选中会话的后台流，防止往已删除的会话落库回答
    ids.forEach(sid => {
      const st = chatStreams.get(sid)
      if (st) { chatStreams.delete(sid); st.abort.abort() }
    })
    const res = await batchDeleteSessionsApi(ids)
    message.success(`已删除 ${res?.deleted ?? ids.length} 个会话`)
    exitBatchMode()
    await loadSessions(searchKw.value.trim())
    // 当前打开的会话在被删列表里 → 回对话页重新挑会话
    if (route.query.sid && ids.includes(route.query.sid)) {
      router.push('/chat').catch(() => {})
      sessionStore.autoPickTick++
    }
  } catch (e) { message.error(e.message || '批量删除失败') }
}

// 个人设置：独立页（默认模型三类 + 修改密码）
const goProfile = () => router.push('/profile')

// 分享管理（个人设置内的面板）：链接发出去后散在各处，这里是统一的回看/停用入口。
// 带 sid 定位到具体那一条，便于从侧栏分享标记直接跳到"就是这条"。
const openShareManage = sid =>
  router.push({ path: '/profile', query: sid ? { panel: 'shares', sid } : { panel: 'shares' } })

// ==================== 新手配置引导（侧栏入口 + 菜单 tag + 抽屉） ====================
// 状态口径见 utils/setupGuide.js：入口/抽屉=任一项未完成；/chat tag=①②任一未完成；
// /knowledge tag=③未完成。loaded=false（首次对账未成功）一律不显示，避免给存量用户误报。
// 两层清单：必配（pendingCount，琥珀待办）与进阶（advPendingCount，「配置了效果更好」弱化建议）。
const guideOpen = ref(false)
const guideVisible = computed(() =>
  setupGuide.loaded && (pendingCount.value > 0 || advPendingCount.value > 0))
// 必配全部完成后入口切弱化形态：不再琥珀计数、无折叠圆点，避免"永远有待办"的催办感
const guideAdvOnly = computed(() => pendingCount.value === 0 && advPendingCount.value > 0)
const guideEntryTitle = computed(() =>
  guideAdvOnly.value ? '进阶配置 — 配置后效果更好（可选）' : '配置引导')
// 聊天侧缺失项文案（tooltip 用）
const chatPendingText = computed(() => {
  const parts = []
  if (!chatReady.value) parts.push('添加聊天模型')
  if (!defaultReady.value) parts.push('设置默认聊天模型')
  return parts.join('、')
})
// 未完成对账（loaded=false）时一律按「不显示」处理：模块级状态刷新后初始为空，
// 此时 chatModels/embeddingCount 全空会被误读成「真的没配」，导致 tag 闪一下
const menuPending = m => !setupGuide.loaded ? false
  : (m.path === '/chat' && !chatDone.value) || (m.path === '/knowledge' && !embeddingReady.value)
const menuTitle = m => {
  if (!setupGuide.loaded) return m.name
  if (m.path === '/chat' && !chatDone.value) return `${m.name} — 还差：${chatPendingText.value}`
  if (m.path === '/knowledge' && !embeddingReady.value) return `${m.name} — 还差：添加向量模型（建知识库需要）`
  return m.name
}
// 导航回来 TTL 对账（15s 内不重复请求；配置动作后的 force 对账在 Providers/Profile 页内做）
watch(() => route.path, () => { refreshSetupGuide() })

// 退出登录：令牌无状态，清本地令牌并回登录页
const doLogout = async () => {
  try { await logoutApi() } catch (e) { /* 忽略：服务端不维护会话 */ }
  clearAuth()
  message.success('已退出登录')
  router.replace('/login')
}

// ==================== 站内通知（铃铛） ====================
// 取数口径（未读数 30s 轮询 / 列表 / 乐观置已读）与移动壳通知 sheet 共用同一份
// src/chat/useNotifications.js（后端触发面见该文件注释）；本页只管呈现（popover）
// 与「点开跳哪」的路由语义——移动壳跳 /m/chat，两端落点不同、读状态口径必须相同。
const notifOpen = ref(false)
const { items: notifItems, loading: notifLoading, unreadCount,
        loadList: loadNotifs, markRead, markAllRead: markAllReadApi } = useNotifications()
const onNotifOpen = open => { if (open) loadNotifs() }
// H5 外壳的通知 sheet（顶栏铃铛）经 provide 取同一份实例——各调一次 useNotifications
// 会造出两份未读数，角标与列表会各说各话（与移动壳 provide 给 MobileNotifSheet 同口径）
provide('wqNotif', { items: notifItems, loading: notifLoading, unreadCount, loadList: loadNotifs, markRead, markAllRead: markAllReadApi })
const markAllRead = () => markAllReadApi()
// 点击通知：置已读（乐观更新，失败回滚在共用单元里），再按 ref 跳转（审批类带二级目标直达可裁决位置）
const openNotif = async n => {
  markRead(n)
  if (n.type === 'workflow.approval' && n.refId && n.refSub) {
    router.push({ path: '/agents', query: { tab: 'workflow', wf: n.refId, run: n.refSub } })
  } else if (n.type === 'tool.approval' && n.refId && n.refSub) {
    goChat({ path: '/chat', query: { sid: n.refId, approval: n.refSub } })
  } else if (n.refType === 'kb' && n.refId) router.push(`/knowledge/${n.refId}/docs`)
  else if (n.refType === 'workflow' && n.refId) router.push({ path: '/agents', query: { tab: 'workflow' } })
  else if (n.refType === 'session' && n.refId) goChat({ path: '/chat', query: { sid: n.refId } })
  else if (n.refType === 'provider' && n.refId) router.push({ path: '/agents', query: { tab: 'providers' } })
  notifOpen.value = false
}

// 铃铛 → 通知中心页
const goNotifCenter = () => { notifOpen.value = false; router.push('/notifications') }
onMounted(async () => {
  const info = await ensureAuth(true)
  isAdmin.value = Boolean(info && info.admin)
  // 侧栏只渲染 sidebar + group；tab 由宿主页（AgentsHubPage 等）按 parentId 取用，hidden 纯权限容器
  navMenus.value = sidebarMenusOf(info && info.menus)
  loadSessions()
  loadTasks(true)  // 「任务」分区 + 会话行时钟标记（与工作区取数并行，不互相阻塞）
  refreshSetupGuide(true)  // 登录即可见的配置引导首拉（此时菜单树已就绪，tag/入口立即可判）
  // 通知未读轮询与 focus/visibility 刷新由 useNotifications 自持（首拉 + 30s 定时 + 卸载清理）
})
// 从智能体工作台（定时任务）回来时任务区要跟着变。节流 5s：切会话、切页不重复打这个接口，
// 但在定时任务页新建/停用后回到对话页，回来这一次一定是新的。
let tasksRefreshedAt = Date.now()
watch(() => route.fullPath, () => {
  if (Date.now() - tasksRefreshedAt < 5000) return
  tasksRefreshedAt = Date.now()
  loadTasks(true)
})
</script>

<style scoped>
.pref-section { margin-bottom: 4px; }
.pref-label { font-weight: 600; margin-bottom: 8px; }
.pref-row { display: flex; align-items: center; gap: 8px; }
.pref-hint { font-size: 12px; color: var(--app-text3, var(--app-text3)); margin-top: 6px; }
.pwd-err { color: var(--app-danger); font-size: 12px; margin: 0 0 8px; }
.pj-modal-hint { margin-top: var(--app-sp-2); font-size: var(--app-fs-sm); color: var(--app-label-tertiary); }
.sess-clock-flag { font-size: 11px; color: var(--app-label-tertiary); flex-shrink: 0; }
.side {
  /* 232px：工作区分组需要「图标 + 名字 + 余量 + 两个操作位」排在一行，
     200px 下名字会被挤成省略号、查看更多也要换行。 */
  width: 232px; flex: none; display: flex; flex-direction: column;
  background: var(--app-panel); border-right: 1px solid var(--app-border);
  padding: 10px 8px; transition: width .18s ease; overflow: hidden;
}
.side.collapsed { width: 56px; }
.side-logo { position: relative; display: flex; align-items: center; gap: 8px; padding: 2px 6px 12px; }
/* 折叠态：logo 与收起按钮总宽超出 56px 会被 overflow:hidden 裁掉按钮 → 折叠态只渲染一个
   按钮，图标即问渠品牌标（点击展开侧边栏），比裸的展开箭头更能表明"这是谁家的栏" */
.side.collapsed .side-logo { justify-content: center; padding: 2px 0 12px; }
/* 折叠态导航图标对齐到侧边栏中轴（实测导航图标左偏 4px） */
.side.collapsed .nav-item { justify-content: center; padding-left: 0; padding-right: 0; }
/* 折叠态：底部改为竖排（头像=个人设置入口 + 主题 + 退出），沿侧边栏中轴对齐——
   横排 3 个 26px 图标在 ~56px 图标条里放不下，此前直接溢出 */
.side.collapsed .side-foot { flex-direction: column; gap: 6px; padding: 8px 0 6px; }
.side.collapsed .side-foot .app-icon-btn { margin-left: 0 !important; }
/* 品牌标用 BrandMark 组件（SVG 自带圆角与品牌渐变，明暗主题通用）；此处只留占位规则 */
.logo-name { font-weight: 500; font-size: 13px; white-space: nowrap; }
/* 形态操作组：编辑 + 折叠，整体靠右（margin-left:auto 必须落在**容器**上——
   落在单个按钮上只会推走那一个，另一个留在品牌名旁，实测返工过一次）。 */
.side-logo-ops { margin-left: auto; display: flex; align-items: center; gap: 2px; }
.side-logo-ops .app-icon-btn.on { color: var(--app-accent); background: var(--app-accent-weak); }
/* auto 由 .side-logo-ops 承担（展开态才渲染该容器），折叠态下无右侧元素可推 */
.fold { margin-left: 0; }
/* 编辑态：折叠按钮隐身但**留位**（visibility:hidden 保留占位）。
   不用 v-if 摘掉——摘掉后整组靠右对齐，✓ 会右移 28px 正好补进折叠图标原来的格子，
   用户的「点一下别处」很容易落在那格上，一按就退出编辑态（看着像图标自己消失了）。
   visibility:hidden 同时摘掉点击与 Tab 焦点，不留任何可误触的热区。 */
.fold.slot-hidden { visibility: hidden; pointer-events: none; }
/* 折叠态：容器里只剩折叠按钮（图标=品牌标），居中交给 .side-logo 的 justify-content，
   这里必须把 margin-left:auto 撤掉——auto 在只有一个子项时会把它推到右边缘，居中失效 */
.side.collapsed .side-logo-ops { margin-left: 0; }

/* ---- 展开态的悬浮形态：默认隐身，鼠标移到 logo **这一行**才浮现 ----
   触发区刻意只给这一行（不是整个侧栏）：鼠标扫过会话列表时不该有东西在顶上闪。
   绝对定位 ⇒ 不占 logo 行的排版空间（那一行平时只有品牌名），
   只改 opacity 不改 display ⇒ 浮出过程没有布局抖动。
   无边框无底色：就是两个裸图标浮在行尾，不加「小工具条」那种外框。
   只在**真有悬停能力**的环境隐身：触屏点了才亮一次，藏起来就等于没有入口（见下方 any-pointer 兜底）。 */
@media (hover: hover) and (pointer: fine) {
  .side:not(.collapsed) .side-logo-ops {
    position: absolute; top: 1px; right: 0; z-index: 3;
    opacity: 0; pointer-events: none;
    transition: opacity .16s ease;
  }
  /* 显形三条路，选择器都必须带上 .side:not(.collapsed) 前缀：
     隐身规则的 specificity 是 (0,3,0)，写成 .side-logo-ops.pinned 这种 (0,2,0) 会被它压住——
     编辑态里鼠标一移开图标就消失，连「保存」都点不到（实测踩过，见 check-sidebar-ops.cjs）。
     · hover：鼠标移到 logo 行；
     · :has(:focus-visible)：**键盘** Tab 到按钮（pointer-events:none 挡不住焦点，
       看不见的按钮等于没入口）。这里刻意不用 :focus-within —— 鼠标点完按钮后焦点仍留在它身上
       （Chrome 里 click 就等价于 focus），:focus-within 会把整组钉在屏幕上：点「✓」退出编辑态后
       图标一直常驻，直到用户再点别处才消失，看着就像"退出没生效"（2026-10-16 实测踩过）；
       :focus-visible 只在键盘导航时命中，鼠标点击不触发。
     · pinned：编辑态，用户正在用的工具条不能因为鼠标移开就消失。 */
  .side:not(.collapsed) .side-logo:hover .side-logo-ops,
  .side:not(.collapsed) .side-logo-ops:has(:focus-visible),
  .side:not(.collapsed) .side-logo-ops.pinned { opacity: 1; pointer-events: auto; }
}
/* 粗指针（触屏、含带触摸屏的笔记本）：恒显且退回常规文档流，形态与改动前一致。
   写在 hover 块之后：两者同时命中时（触摸屏笔记本）由这里覆盖。 */
@media (any-pointer: coarse) {
  /* 触屏没有 hover：工作区行的 ⋯ 必须常显，否则等于没有操作入口 */
  .space-op,
  .space-add { opacity: 1; }
  .side:not(.collapsed) .side-logo-ops {
    position: static; opacity: 1; pointer-events: auto;
  }
}

/* 编辑态行：与 .nav-item 同宽同高，肉眼只多出把手和开关 */
.nav-edit-row {
  display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 8px;
  cursor: grab; user-select: none;
  border: 1px solid transparent;
  /* 让位动画（FLIP 脚本写 transform，这里只给过渡）。占位行 .dragging 关掉——
     它由指针直接驱动，再叠一层过渡会跟手变成"追着手指跑"的滞后感。 */
  transition: transform .16s cubic-bezier(.2, .8, .25, 1), background .15s, opacity .15s, border-color .15s;
  touch-action: none;  /* 触屏：否则 pointermove 会被滚动手势吃掉，拖不动 */
}
.nav-edit-row:hover { background: var(--app-panel-2); }
.nav-edit-row.off { opacity: .5; }
/* 拖起后原行留作虚线占位：高度不能塌，否则上下行会补跳一段（列表看起来"抽动"）。
   内容整体降透明，让用户明确"拿起来的是幽灵，原位是坑"。 */
.nav-edit-row.dragging {
  cursor: grabbing;
  border-style: dashed; border-color: var(--app-accent);
  background: var(--app-accent-weak);
  transition: background .15s, opacity .15s, border-color .15s;
  opacity: .45;
}
/* 把手：默认浅灰，hover 整行时加深 —— 平时不抢眼，但要让人看出这行能拖 */
.nav-edit-grip {
  flex: none; font-size: 11px; line-height: 1; letter-spacing: -2px;
  color: var(--app-text3); opacity: .55; transition: opacity .12s, color .12s;
}
.nav-edit-row:hover .nav-edit-grip { opacity: 1; color: var(--app-text2); }
.nav-edit-ic { flex: none; font-size: 14px; color: var(--app-text2); }
.nav-edit-name { flex: 1 1 auto; min-width: 0; font-size: 13px; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 操作条：单行按钮，接替入口行、与它等高 ⇒ 进/出编辑态侧栏总高不变，
   下方会话列表不会被顶下去（提示语降为 title，不占垂直空间）。
   white-space:nowrap 是硬要求：一换行按钮就变竖排单字（200px 侧栏实测过）。 */
.nav-edit-bar {
  display: flex; align-items: center; gap: 6px;
  margin-top: 4px; padding: 0;
}
.nav-edit-ops { display: flex; align-items: center; gap: 6px; width: 100%; }
.nav-edit-btn {
  flex: 1 1 0; min-width: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--app-border); border-radius: 6px;
  padding: 5px 6px; font-size: 12px; line-height: 1.2;
  cursor: pointer; white-space: nowrap; background: transparent; color: var(--app-text2);
}
.nav-edit-btn:hover:not(:disabled) { color: var(--app-accent); border-color: var(--app-accent); }
.nav-edit-btn.primary { background: var(--app-accent); border-color: var(--app-accent); color: #fff; }
.nav-edit-btn.primary:hover:not(:disabled) { background: #4a80ef; color: #fff; }
.nav-edit-btn:disabled { opacity: .5; cursor: not-allowed; }
/* 禁用的主按钮不涂实心底色：浅蓝实心看着像"能点但没反应"，
   改成虚线边 + 弱化文字才读得出「此刻按不了」 */
.nav-edit-btn.primary:disabled {
  background: transparent; border-style: dashed; border-color: var(--app-border);
  color: var(--app-text3); opacity: 1;
}

/* user-select/callout 全关：长按进入编辑态是这一区域的手势，
   鼠标按住时顺手选中一片文字、或触屏弹出系统菜单，都会把它打断 */
.side-nav {
  display: flex; flex-direction: column; gap: 1px;
  user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;
}
/* 分组标题（renderAs=group）：不可点、无 hover，仅视觉归类。
   折叠态缩成一条细线——标题只剩占位会让图标条更乱，藏掉更干净 */
.nav-group {
  margin: 6px 9px 1px; padding: 0; font-size: 11px; color: var(--app-text3);
  letter-spacing: .05em; white-space: nowrap; user-select: none;
}
.side.collapsed .nav-group { height: 1px; margin: 6px 8px; padding: 0; overflow: hidden; background: var(--app-border); }
.nav-item {
  display: flex; align-items: center; gap: 9px; border: none; background: transparent;
  padding: 6px 9px; border-radius: 8px; font-size: 13px; color: var(--app-text2);
  cursor: pointer; text-align: left; white-space: nowrap; transition: background .15s, color .15s;
  position: relative; /* 折叠态/窄屏的待配置圆点绝对定位于行内右上角 */
}
.nav-item:hover { background: var(--app-accent-weak); color: var(--app-text); }
.nav-item.active { background: var(--app-accent-weak); color: var(--app-text); font-weight: 500; }

/* ==================== 新手配置引导：菜单 tag + 折叠圆点 + 侧栏入口 ==================== */
/* 展开态行尾琥珀 pill（复用 .app-pill 形态，配色走主题变量以兼容暗色） */
.nav-item .app-pill.nav-pending {
  margin-left: auto; flex: none;
  font-size: 10px; padding: 2.5px 7px;
  color: var(--app-warn-text); background: var(--app-warn-weak);
}
/* 折叠态：span 文本被隐藏，用 <i> 圆点提示待配置（span 会被隐藏规则吞掉）。
   窄屏抽屉是展开形态（宽度够放文字与计数），不需要圆点，故只在 .collapsed 下生效 */
.nav-dot {
  display: none; position: absolute; top: 6px; right: 9px;
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--app-warn); flex: none;
}
.side.collapsed .nav-dot { display: block; }
/* 侧栏引导入口（导航组末尾）：与上方导航留分组间距；必配态主色弱底 + 琥珀计数角标 */
.guide-entry { margin-top: 6px; background: var(--app-accent-weak); }
.guide-entry-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.guide-count {
  flex: none; font-size: 10px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  color: var(--app-warn-text); background: var(--app-warn-weak);
}
/* 进阶弱化态：链接式（无弱底、灰字），hover 才提亮——感知得到但不催办 */
.guide-entry.adv { background: transparent; color: var(--app-text3); }
.guide-entry.adv:hover { background: var(--app-accent-weak); color: var(--app-text2); }
.guide-count.muted { color: var(--app-text3); background: var(--app-panel-2); }

/* ==================== 会话树：分区 / 工作区 / 会话行 ==================== */
/* 分区标题：小号、加字距、计数紧跟其后。参考的是「任务 (0)」「工作区 (2)」那种目录式表头，
   而不是旧版灰色小标题 + 组内数字角标（视觉重量太轻，撑不起分区）。 */
.side-sec { display: flex; flex-direction: column; padding: 0 2px; }
.side-sec + .side-sec { margin-top: 2px; }
.side-sec-head { display: flex; align-items: center; gap: 4px; padding: 9px 6px 3px; min-height: 28px; }
.side-sec-title { font-size: 11px; font-weight: 600; color: var(--app-text3); letter-spacing: .04em; }
.side-sec-count { font-size: 11px; color: var(--app-text3); opacity: .8; }
.side-sec-ops { margin-left: auto; display: inline-flex; align-items: center; gap: 2px; }
.side-sec-op {
  flex: none; width: 20px; height: 20px; border: none; border-radius: 6px;
  background: transparent; color: var(--app-text3); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; font-size: 11px;
  transition: color .15s, background .15s;
}
.side-sec-op:hover { color: var(--app-accent); background: var(--app-accent-weak); }
.side-sec-sub {
  display: flex; align-items: center; gap: 6px; width: 100%;
  border: none; background: transparent; cursor: pointer; text-align: left;
  margin-top: 2px; padding: 7px 8px; border-radius: 6px;
  font-size: 11px; color: var(--app-text3);
  transition: color .15s, background .15s;
}
.side-sec-sub:hover { color: var(--app-text2); background: var(--app-panel-2); }
.group-caret { margin-left: auto; font-size: 9px; transition: transform .15s; }
.group-caret.open { transform: rotate(90deg); }
/* 分区表头（会话 / 工作区）整段可点：光标 + 文字提亮给到可点感，不给整行底色
   （这一行的上下 padding 是 9/3 不对称的分区留白，铺底色会明显偏上）。
   箭头紧跟计数、不 .group-caret 那样靠右——它属于「会话 (187)」这个标题，
   右侧那一组是操作区，两者分开才不会让人以为箭头是某个按钮的图标。 */
.side-sec-head.clickable { cursor: pointer; }
.side-sec-head.clickable:hover .side-sec-title,
.side-sec-head.clickable:hover .side-sec-count { color: var(--app-text2); }
.sec-caret { margin-left: 0; color: var(--app-text3); }

/* 工作区：一行「图标 + 名字（点名字展开/收起） + ⋯ + 新建对话」。
   不再放折叠箭头：名字本身就是折叠开关（与基准稿一致），右侧两个图标各管一件事。 */
.space { display: flex; flex-direction: column; }
.space-head {
  display: flex; align-items: center; gap: 6px; min-height: 30px;
  padding: 5px 4px 5px 6px; border-radius: 7px; cursor: pointer;
  transition: background .12s;
}
.space-head:hover { background: var(--app-accent-weak); }
.space-icon {
  flex: none; width: 20px; height: 20px; border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-accent-weak); color: var(--app-accent); font-size: 11px;
}
.space-name {
  flex: 1; min-width: 0; font-size: 12.5px; font-weight: 500; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.space-spin { flex: none; margin-right: 2px; }
/* ⋯ 与 ＋：触屏/窄屏没有 hover，走这里的常显档（压到 .8 不与工作区名抢注意力）；
   有鼠标的环境下默认隐身、移到这一行才浮现（见下方 hover 媒体查询）。
   只改 opacity 不改 display ⇒ 工作区名的可用宽度两种状态一致，不会一动就重排。 */
.space-op, .space-add {
  flex: none; padding: 0; border: none; background: transparent;
  color: var(--app-text2); cursor: pointer; opacity: .8;
  display: inline-flex; align-items: center; justify-content: center;
  transition: opacity .12s, color .12s, background .12s, border-color .12s;
}
.space-op { width: 18px; height: 18px; border-radius: 5px; font-size: 12px; }
/* + 用一圈细边的小圆扣，和 ⋯ 区分开（一个管管理，一个管新建） */
.space-add {
  width: 16px; height: 16px; border-radius: 50%; font-size: 9px;
  border: 1px solid var(--app-border);
}
.space-head:hover .space-op,
.space-head:hover .space-add { opacity: 1; }
.space-op:hover { color: var(--app-accent); background: var(--app-accent-weak); }
.space-add:hover { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); }
/* ---- 有悬停能力的环境：表头操作组与工作区行右侧图标一律「平时隐身、hover 这一行显形」 ----
   与 logo 行 .side-logo-ops 同一套口径（触发区只给所在那一行，鼠标扫过列表时不该有东西在闪）。
   :has(:focus-visible) 兜键盘：Tab 到按钮才显形，看不见的按钮等于没入口；
   刻意不用 :focus-within —— 鼠标点完焦点会留在按钮上，会把图标一直钉在屏幕上（logo 行实测踩过）。
   .pinned 给表头：整栏刷新时的转圈是「这里在发生什么」的提示，不能跟着隐身。
   写在 .space-op/.side-sec-ops 基础规则之后：媒体查询不加 specificity，顺序决定谁生效。 */
@media (hover: hover) and (pointer: fine) {
  .side-sec-ops,
  .space-op,
  .space-add { opacity: 0; }
  .side-sec-head:hover .side-sec-ops,
  .side-sec-ops:has(:focus-visible),
  .side-sec-ops.pinned,
  .space-head:hover .space-op,
  .space-head:hover .space-add,
  .space-head:has(:focus-visible) .space-op,
  .space-head:has(:focus-visible) .space-add { opacity: 1; }
}
/* 已归档工作区整体压暗：仍可点进去，但不该与"正在推进"的工作区抢注意力 */
.space.archived .space-head { opacity: .72; }
/* 27 而不是 33：按钮自带 6px 左内边距（给 hover 弱底留呼吸），加上它才等于会话行的 33px 文字缩进 */
.space-foot { display: flex; align-items: center; gap: 4px; padding-left: 27px; }

/* 批量操作条：独立一行贴列表上方，主题色弱底提示「处于批量模式」 */
.sess-batch-bar {
  display: flex; align-items: center; justify-content: space-between;
  margin: 0 2px 6px; padding: 5px 8px; border-radius: 7px;
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-weak);
  font-size: 11px; color: var(--app-text2);
}
.batch-count { white-space: nowrap; }
.batch-actions { display: inline-flex; align-items: center; gap: 7px; }
.batch-btn { border: none; background: transparent; color: var(--app-accent); cursor: pointer; font-size: 11px; padding: 0; }
.batch-btn.danger { color: var(--app-danger); }
.batch-btn:disabled { color: var(--app-text3); cursor: not-allowed; }
/* 批量模式行首勾选块（选中填主题色 + 白勾） */
.batch-check {
  width: 14px; height: 14px; border-radius: 4px; flex: none; margin-right: 6px;
  border: 1px solid var(--app-border); background: var(--app-panel);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 9px; color: #fff;
}
.batch-check.on { background: var(--app-accent); border-color: var(--app-accent); }
.side-sessions { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; }
/* 会话行缩进 33px：与工作区名的左缘对齐（6 + 20 + 7），一眼能看出它属于哪个工作区。
   首段「会话」没有工作区头，但保持同样的缩进，两个分区的行在同一列上。 */
.sess-item {
  display: flex; align-items: center; gap: 3px;
  padding: 5px 6px 5px 33px; border-radius: 7px; min-height: 27px;
  font-size: 12.5px; color: var(--app-text2); cursor: pointer; min-width: 0;
  transition: background .12s;
}
.sess-item:hover { background: var(--app-panel-2); }
/* 当前行：弱底 + 左缘主题色窄条（比纯底色多一层方位感）；批量选中行共用弱底（无窄条） */
.sess-item.active { background: var(--app-accent-weak); color: var(--app-text); box-shadow: inset 2px 0 0 var(--app-accent); }
.sess-item.picked { background: var(--app-accent-weak); color: var(--app-text); }
.sess-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; min-width: 0; }
.sess-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-text3); margin: 0 auto; }
.sess-item.active .sess-dot { background: var(--app-accent); }
/* 折叠成图标条：圆点居中，行不再左缩进 */
.side.collapsed .sess-item { padding: 6px 0; justify-content: center; }

/* 本机还在生成的会话（chatStreams 持有它的流）：标题在主色与次文本之间呼吸。
   不用转圈图标：行右端是 hover 操作位、行首已排着置顶/分享标记，再插一个会打架；
   也不用 background-clip 流光：那会让 text-overflow 的省略号一起透明，长标题看不出被截断。
   只改 color，省略号与收藏星标跟着一起呼吸，深浅主题同源。
   相位按行的全序下标错开（--sess-i 由模板注入，负延迟即从动画中段起步）：
   同时在跑的会话是各自轮流亮，而不是整列一起眨眼。步长取 0.37s 而非整拍的 0.4s——
   波长 1.6/0.37≈4.3 行，错开几行才撞一次相位，同列表里的两行几乎不会同步。 */
.sess-item.running { --sess-pulse-delay: calc(var(--sess-i, 0) * -0.37s); }
.sess-item.running .sess-title {
  color: var(--app-accent);
  animation: sess-running 1.6s ease-in-out infinite;
  animation-delay: var(--sess-pulse-delay, 0s);
}
.sess-item.running .sess-dot {
  background: var(--app-accent);
  animation: sess-running-dot 1.6s ease-in-out infinite;
  animation-delay: var(--sess-pulse-delay, 0s);
}
@keyframes sess-running { 0%, 100% { color: var(--app-text2); } 50% { color: var(--app-accent); } }
@keyframes sess-running-dot { 0%, 100% { background: var(--app-text3); } 50% { background: var(--app-accent); } }
@media (prefers-reduced-motion: reduce) {
  /* 关动效时退化为常亮主色：状态还在，只是不闪 */
  .sess-item.running .sess-title,
  .sess-item.running .sess-dot { animation: none; }
}
.sess-del { color: var(--app-text3); opacity: 0; flex: none; margin-left: 4px; font-size: 12px; }
.sess-item:hover .sess-del { opacity: 1; }
.sess-del:hover { color: var(--app-danger); }
.sess-export { color: var(--app-text3); opacity: 0; flex: none; margin-left: 4px; font-size: 12px; }
.sess-item:hover .sess-export { opacity: 1; }
.sess-export:hover { color: var(--app-accent); }

/* 会话搜索框（列表顶部，防抖走后端检索）
   视觉与导航项同一语言：无边框弱底胶囊，聚焦时才浮现边框——
   此前常驻边框+纯白底，夹在无边框导航项之间显得格格不入 */
.sess-search-wrap {
  position: relative; display: flex; align-items: center; margin: 6px 2px 2px;
  height: 30px; padding: 0 2px;
  border: 1px solid transparent; border-radius: 8px; background: var(--app-panel-2);
}
.sess-search-wrap:focus-within { border-color: var(--app-accent); background: var(--app-bg, #fff); }
.sess-search-ic { color: var(--app-text3); font-size: 11px; margin-left: 7px; flex: none; }
.sess-search {
  flex: 1; min-width: 0; border: none; outline: none; background: transparent;
  font-size: 12px; padding: 5px 6px 5px 5px; color: var(--app-text);
}
.sess-search::placeholder { color: var(--app-text3); }
.sess-search-clear { border: none; background: transparent; color: var(--app-text3); cursor: pointer; padding: 2px 6px; font-size: 10px; }
.sess-search-clear:hover { color: var(--app-text); }

/* 会话项操作区（hover 出现）：置顶快捷按钮 + 更多菜单（重命名/收藏/导出/删除） */
.sess-op {
  border: none; background: transparent; color: var(--app-text3); opacity: 0;
  flex: none; margin-left: 4px; font-size: 12px; cursor: pointer; padding: 0 1px;
  display: inline-flex; align-items: center;
}
.sess-item:hover .sess-op { opacity: 1; }
.sess-op:hover { color: var(--app-accent); }
/* 置顶/收藏的快捷按钮在触屏上没有 hover，常显（≤1024 的媒体查询也会兜一层） */
.sess-op.on { opacity: 1; color: var(--app-accent); }
.sess-pin-flag { color: var(--app-accent); font-size: 10px; flex: none; margin-right: 3px; }
/* 分享标记：与置顶/收藏同级常显（不是 hover 才出现的操作按钮）——它是"这段对话正在对外"
   的状态告知，藏起来就失去了感知意义。琥珀色区别于置顶的强调色，避免两者混淆 */
.sess-share-flag { color: var(--app-warn); font-size: 10px; flex: none; margin-right: 3px; cursor: pointer; }
.sess-fav-flag { color: var(--app-warn); font-size: 10px; margin-right: 3px; }
.sess-title.fav { color: var(--app-text); }

/* 空态：左对齐并缩进到会话行文字处，和行一起读，而不是页面中央的一行灰字 */
.side-empty { padding: 2px 8px 6px; font-size: 11.5px; color: var(--app-text3); line-height: 1.6; }
.space .side-empty { padding-left: 33px; }
/* 首段「会话」没有工作区头：空态与分区标题左对齐，而不是像会话行那样再缩进一次 */
.space.bare .side-empty { padding-left: 8px; }
.side-empty.err { color: var(--app-danger); }
.side-inline-btn {
  border: none; background: none; cursor: pointer; padding: 0 2px;
  color: var(--app-accent); font-size: 11.5px;
}
.side-inline-btn:hover { text-decoration: underline; }

/* 「查看更多 (N)」：静默认的次级动作，hover 才提亮；不用主题色实心/粗体抢注意力。
   静置色走弱文本档：会话行本身是 .text2，同色同字号时这行会读成「第 5 条会话」而不是列表收尾 */
.side-more-btn {
  border: none; background: none; cursor: pointer;
  font-size: 11.5px; color: var(--app-text3); padding: 3px 6px; border-radius: 6px;
}
.side-more-btn:hover { color: var(--app-accent); background: var(--app-accent-weak); }
.side-more-btn:disabled { color: var(--app-text3); opacity: .6; cursor: default; background: none; }
.side-more-btn.muted { color: var(--app-text3); }

.side-foot {
  display: flex; align-items: center; gap: 4px; padding: 6px 6px 2px;
  border-top: 1px solid var(--app-border);
}
/* 头像+昵称 = 个人设置入口（点击进 /profile），占满剩余宽度把右侧两个图标推到行尾 */
.foot-user {
  flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px;
  border: none; background: transparent; cursor: pointer; padding: 3px 4px;
  border-radius: 6px; text-align: left;
  transition: background .15s;
}
.foot-user:hover { background: var(--app-accent-weak); }
.user-name { font-size: 12px; color: var(--app-text2); min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pwd-err { margin: 4px 0 0; font-size: 12px; color: var(--app-danger); }

/* .main 恒为纵向 flex 容器，.main-body 吃掉剩余高度。
   看似多包一层，但这一层是必需的：窄屏要在 .main 顶部插入 MobileTopBar，
   若让页面直接做 .main 的子节点，就得给每个页面都加「跳过顶栏」的偏移。
   关键是 .main-body 必须**在宽屏也有高度**（flex:1 + min-height:0）——
   各页面靠 height:100% 逐级继承（.app-root → .main → 页面），一旦这层塌成 auto，
   页面高度就断链、内容会被压扁。 */
.main { flex: 1; min-width: 0; height: 100%; display: flex; flex-direction: column; }
.main-body { flex: 1; min-height: 0; }

/* ==================== 响应式：窄屏适配 ====================
   ≤768：左侧栏整体变左侧抽屉（.side 变 fixed + translateX 滑出），主内容区顶部插入
        MobileTopBar。DOM 结构与 PC 完全一致，靠 CSS 与 :class 切换。
   ≤1024：会话操作图标常显（触屏无 hover）。 */
@media (max-width: 1024px) {
  .sess-op,
  .space-op,
  .space-add { opacity: 1; }
}
@media (max-width: 768px) {
  /* 抽屉：脱离文档流（fixed），所以 .app-root 保持 flex-direction: row 无需改。
     min(84vw, 320px)：窄屏留出右侧一条可点区域关抽屉，宽屏不超常规抽屉宽度。 */
  .side {
    position: fixed; inset: 0 auto 0 0; z-index: 40;
    width: min(84vw, 320px); height: 100dvh;
    padding-top: var(--sat, 0px);
    padding-bottom: var(--sab, 0px);
    border-right: none; box-shadow: var(--app-shadow-lg);
    /* 用 transform 而非 left：left 动画会每帧触发布局，transform 走合成层 */
    transform: translateX(-100%);
    transition: transform .24s cubic-bezier(.16, 1, .3, 1);
  }
  .side.open { transform: none; }
  /* 抽屉内的安全区补偿：底部 .side-foot 贴底区需要额外留白，否则退出按钮压住 Home Indicator */
  .side .side-foot { padding-bottom: calc(8px + var(--sab, 0px)); }
  .side-mask {
    position: fixed; inset: 0; z-index: 39;
    background: rgba(0, 0, 0, .38);
    animation: side-fade .2s ease;
  }
  @keyframes side-fade { from { opacity: 0; } to { opacity: 1; } }
  /* .main / .main-body 的纵向 flex 结构在**宽屏样式里已声明**（见上），此处不重复 ——
     窄屏只是多了一个顶栏，结构不变。绝不能把它只写在窄屏块里：
     那样宽屏下 .main-body 无高度，各页面的 height:100% 继承链会断在这里。 */
}
</style>

<!-- 站内通知面板样式：popover 内容 teleport 到 body，scoped 特性丢失，必须用非 scoped 块；
     全部类挂在 .notif-popover 下防全局泄漏 -->
<style>
/* 拖拽幽灵行：teleport 到 body，视觉规格必须与 .nav-edit-row 逐项对齐
   （padding/gap/字号/图标尺寸），否则「拿起来的那份」在手上会变形。
   变量走同一套主题变量，明暗主题自动跟随。
   pointer-events:none 是必须的：幽灵压着指针，不让事件穿透就会挡住下面所有行，
   拖到列表外时pointermove 直接断流。 */
.nav-edit-ghost {
  position: fixed; top: 0; left: 0; z-index: 3000;
  display: flex; align-items: center; gap: 6px; padding: 6px 8px;
  border-radius: 8px; pointer-events: none;
  background: var(--app-panel); border: 1px solid var(--app-accent);
  box-shadow: var(--app-shadow-lg, 0 6px 20px rgba(0, 0, 0, .16));
  opacity: .96; user-select: none;
  transition: none;              /* 幽灵自己由指针驱动，任何过渡都会变成滞后 */
  will-change: transform;
}
.nav-edit-ghost .nav-edit-grip {
  flex: none; font-size: 11px; line-height: 1; letter-spacing: -2px;
  color: var(--app-text3); opacity: .55;
}
.nav-edit-ghost .nav-edit-ic { flex: none; font-size: 14px; color: var(--app-text2); }
.nav-edit-ghost .nav-edit-name {
  flex: 1 1 auto; min-width: 0; font-size: 13px; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.notif-popover .ant-popover-inner { padding: 10px; border-radius: var(--app-radius); }
.notif-popover .notif-panel { width: 320px; }
.notif-popover .notif-head { display: flex; align-items: center; justify-content: space-between; padding: 2px 4px 8px; border-bottom: 1px solid var(--app-border); }
.notif-popover .notif-title { font-weight: 600; font-size: 13px; color: var(--app-text); }
.notif-popover .notif-readall { border: none; background: none; color: var(--app-accent); cursor: pointer; font-size: 12px; padding: 0; }
.notif-popover .notif-readall:hover { color: var(--app-accent-hover); }
.notif-popover .notif-head-actions { display: flex; align-items: center; gap: 12px; }
.notif-popover .notif-head-actions .notif-readall + .notif-readall { position: relative; }
.notif-popover .notif-head-actions .notif-readall + .notif-readall::before { content: ''; position: absolute; left: -6px; top: 2px; bottom: 2px; width: 1px; background: var(--app-border); }
.notif-popover .notif-empty { padding: 30px 0; text-align: center; color: var(--app-text3); font-size: 12px; }
.notif-popover .notif-list { max-height: 380px; overflow-y: auto; scrollbar-width: thin; }
.notif-popover .notif-item { display: flex; gap: 10px; align-items: flex-start; width: 100%; border: none; background: none; text-align: left; padding: 10px 8px; border-radius: 8px; cursor: pointer; }
.notif-popover .notif-item:hover { background: var(--app-panel-2); }
.notif-popover .notif-item.unread { background: var(--app-accent-weak); }
.notif-popover .notif-item.unread:hover { background: var(--app-panel-2); }
.notif-popover .notif-ic { font-size: 16px; margin-top: 2px; flex: none; }
.notif-popover .notif-ic.ok { color: var(--app-ok); }
.notif-popover .notif-ic.err { color: var(--app-danger); }
.notif-popover .notif-ic.warn { color: var(--app-warn); }
.notif-popover .notif-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.notif-popover .notif-item-title { font-size: 13px; color: var(--app-text); line-height: 1.4; }
.notif-popover .notif-item.unread .notif-item-title { font-weight: 600; }
.notif-popover .notif-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--app-accent); margin-left: 6px; vertical-align: middle; }
.notif-popover .notif-content { font-size: 12px; color: var(--app-text2); line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; word-break: break-all; }
.notif-popover .notif-time { font-size: 11px; color: var(--app-text3); }
</style>
