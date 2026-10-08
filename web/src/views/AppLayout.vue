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
             折叠态不出现：56px 图标条里既无文字可排也无开关可拨，功能在那里不可用。 -->
        <div v-if="!collapsed" class="side-logo-ops">
          <button class="app-icon-btn" :class="{ on: navEditing }"
                  :title="navEditing ? '完成侧栏编辑' : '编辑侧栏（调整顺序与显示）'"
                  @click="toggleNavEdit">
            <check-outlined v-if="navEditing" />
            <unordered-list-outlined v-else />
          </button>
        </div>
        <button class="app-icon-btn fold" :title="collapsed ? '展开侧边栏' : '折叠侧边栏'" @click="toggleFold">
          <BrandMark v-if="collapsed" :size="24" />
          <menu-fold-outlined v-else />
        </button>
      </div>

      <nav class="side-nav">
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
               判 showNavEditor（含 !collapsed）而非裸 navEditing：折叠态图标条容不下这些控件 -->
          <div v-if="showNavEditor"
               class="nav-edit-row"
               :class="{ off: m.hidden, dragging: editDragId === m.id, over: editOverId === m.id }"
               draggable="true"
               @dragstart="onEditDragStart(m.id, $event)"
               @dragover.prevent="onEditDragOver(m.id, $event)"
               @drop.prevent="onEditDrop(m.id, $event)"
               @dragend="onEditDragEnd"
               @dragleave="onEditDragLeave(m.id)">
            <span class="nav-edit-grip" aria-hidden="true">⋮⋮</span>
            <component :is="iconOf(m.icon)" class="nav-edit-ic" />
            <span class="nav-edit-name">{{ m.name }}</span>
            <a-switch size="small" :checked="!m.hidden"
                       @mousedown.stop @click.stop @change="toggleHidden(m.id, $event)" />
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
            <!-- 主操作给实心：未改动时是禁用态（灰），一眼能看出「现在按了没反应」是有意的 -->
            <button class="nav-edit-btn primary"
                    :disabled="navEditSaving || !navEditDirty" @click="saveNavLayout">
              {{ navEditSaving ? '保存中' : '保存' }}
            </button>
          </div>
        </div>
      </nav>

      <!-- 会话搜索（防抖走后端 keyword 检索：标题/消息内容模糊匹配）；右端内嵌批量管理入口 -->
      <div v-if="!collapsed" class="sess-search-wrap">
        <search-outlined class="sess-search-ic" />
        <input ref="searchInputRef" v-model="searchKw" class="sess-search" placeholder="搜索会话…" @input="onSearchInput" />
        <button v-if="searchKw" class="sess-search-clear" title="清除搜索" @click="clearSearch"><close-outlined /></button>
      </div>
      <!-- 批量操作条：进入批量模式才出现，紧贴列表上方，作用于当前搜索结果 -->
      <div v-if="!collapsed && batchMode" class="sess-batch-bar">
        <span class="batch-count">已选 {{ batchSel.size }}</span>
        <span class="batch-actions">
          <button class="batch-btn" @click="toggleSelectAll">{{ allSelected ? '取消全选' : (sessionStore.hasMore ? '全选(已加载)' : '全选') }}</button>
          <button class="batch-btn danger" :disabled="!batchSel.size" @click="confirmBatchDelete">删除</button>
          <button class="batch-btn" @click="exitBatchMode">取消</button>
        </span>
      </div>
      <!-- 会话列表为游标分页：默认 20 条，点底部「查看更多」每次再渲染 20 条；
           组头数字是后端全量口径，不随加载进度漂移 -->
      <div class="side-sessions">
        <a-spin v-if="sessionStore.loading" size="small" style="display:block;margin:16px auto" />
        <template v-else>
          <!-- 按时间分组展示（后端已按 置顶→更新时间 排序，这里只分桶不改序）；折叠图标条下不显示组头。
               组头可点击折叠/展开（搜索时强制全展开——搜到却看不见是死胡同） -->
          <template v-for="g in groupedSessions" :key="g.label">
            <button v-if="!collapsed" type="button" class="side-label sess-group-label" @click="toggleGroup(g.label)">
              {{ g.label }}
              <span class="group-count">{{ groupTotal(g.label, g.items.length) }}</span>
              <right-outlined class="group-caret" :class="{ open: !groupHidden(g.label) }" />
            </button>
            <template v-if="collapsed || !groupHidden(g.label)">
            <div v-for="s in g.items" :key="s.id"
               class="sess-item" :class="{ active: !batchMode && isActive('/chat') && route.query.sid === s.id, picked: batchMode && batchSel.has(s.id), running: chatStreams.has(s.id) }"
               :style="{ '--sess-i': sessRowOrdinal.get(s.id) ?? 0 }"
               :title="s.title" @click="batchMode && !collapsed ? toggleBatchSel(s.id) : openSession(s.id)">
            <span v-if="collapsed" class="sess-dot"></span>
            <template v-else>
              <!-- 批量模式：行首勾选块，点行即切换选中（不进会话、hover 操作隐藏） -->
              <span v-if="batchMode" class="batch-check" :class="{ on: batchSel.has(s.id) }">
                <check-outlined v-if="batchSel.has(s.id)" />
              </span>
              <pushpin-outlined v-if="s.isPinned === 1" class="sess-pin-flag" />
              <!-- 分享中：只标 enabled=1（链接还开着）。已停止的不标——否则每次停止分享后
                   这个图标就永久留在列表里变成噪音。关闭入口在会话内分享面板 / 个人设置→分享管理 -->
              <a-tooltip v-if="s.shared" title="这段对话正在对外分享（只读链接生效中）">
                <!-- 必须显式传 s.id：直接把函数名当事件处理器，Vue 会把 PointerEvent 当 sid 传进去，
                     跳过去就是 /profile?panel=shares&sid=[object PointerEvent]，深链定位静默失效 -->
                <share-alt-outlined class="sess-share-flag" @click.stop="openShareManage(s.id)" />
              </a-tooltip>
              <span class="sess-title" :class="{ fav: s.isFavorite === 1 }">
                <star-filled v-if="s.isFavorite === 1" class="sess-fav-flag" />{{ s.title || '新对话' }}
              </span>
            </template>
            <template v-if="!collapsed && !batchMode">
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
                         最新内容」的隐私提示与换新/停止），所以这里只发信号让聊天页打开弹窗。
                         侧栏与聊天页是兄弟组件，不能直接调弹窗，信号走 sessionStore.shareOpenTick。 -->
                    <a-menu-item key="share"><share-alt-outlined /> 分享</a-menu-item>
                    <!-- 分享管理：已分享的会话再给一个「回看全部」入口。停用/清除等管理动作在那边
                         （是否生效、访问量都只在分享记录上，列表项里查不到） -->
                    <a-menu-item v-if="s.shared" key="share-manage"><share-alt-outlined /> 分享管理</a-menu-item>
                    <a-menu-item key="batch"><check-square-outlined /> 批量管理</a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="delete" danger><delete-outlined /> 删除</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </template>
          </div>
            </template>
          </template>
          <!-- 增量加载入口（居左，内边距与会话行对齐）：查看更多 (N)=剩余可展示条数；
               点过查看更多后出现「收起」，一键还原首屏 20 条 -->
          <div v-if="(sessionStore.hasMore || sessionStore.expanded) && !collapsed" class="sess-more">
            <a-spin v-if="sessionStore.loadingMore" size="small" />
            <template v-else>
              <button v-if="sessionStore.hasMore" type="button" class="sess-more-btn" @click="loadMoreSessions">
                查看更多<template v-if="sessRemaining > 0"> ({{ sessRemaining }})</template>
              </button>
              <button v-if="sessionStore.expanded" type="button" class="sess-more-btn collapse" @click="collapseSessions">收起</button>
            </template>
          </div>
          <div v-if="!visibleSessionList.length && !collapsed" class="sess-empty">
            {{ searchKw ? '没有匹配的会话' : '暂无会话' }}
            <button v-if="sessionStore.hasMore" type="button" class="sess-empty-more" @click="loadMoreSessions">
              查看更多
            </button>
          </div>
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
import { computed, onMounted, onUnmounted, provide, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, MessageOutlined, RobotOutlined, FolderOutlined, BarChartOutlined, SettingOutlined, ExperimentOutlined,
         MenuFoldOutlined, DeleteOutlined, DownloadOutlined, TeamOutlined, CompassOutlined,
         LogoutOutlined, UserOutlined, DatabaseOutlined, SafetyOutlined, AppstoreOutlined, FileOutlined,
         FileTextOutlined, SearchOutlined, CloseOutlined, PushpinOutlined, MoreOutlined, EditOutlined, StarFilled, StarOutlined,
         CheckOutlined, CheckSquareOutlined, QuestionCircleOutlined, PieChartOutlined, ShareAltOutlined,
         RightOutlined, BellOutlined, UnorderedListOutlined } from '@ant-design/icons-vue'
import { deleteSessionApi, logoutApi, renameSessionApi, pinSession, favoriteSession, batchDeleteSessionsApi,
         getMyMenuLayout, saveMyMenuLayout } from '../api'
import { useNotifications, notifIcon, notifClass, notifTime } from '../chat/useNotifications'
import { themeState, toggleTheme } from '../utils/theme'
import { authUser, ensureAuth, isAdminSync, clearAuth } from '../utils/auth'
import { chatDone, chatReady, defaultReady, embeddingReady, pendingCount, refreshSetupGuide, setupGuide,
         advPendingCount } from '../utils/setupGuide'
import { sessionStore, loadSessions, loadMoreSessions, collapseSessions, visibleSessions, chatStreams } from './store'
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
async function reloadMenus () {
  const info = await ensureAuth(true)
  // 侧栏只渲染 sidebar + group；tab 由宿主页（AgentsHubPage 等）按 parentId 取用，hidden 纯权限容器
  // group 无 path 也保留（title 渲染用 name）；menuPending/menuTitle 对无 path 菜单天然安全（只特判 /chat 与 /knowledge）
  navMenus.value = ((info && info.menus) || [])
    .filter(m => m && (m.renderAs === 'group' || (m.renderAs !== 'tab' && m.renderAs !== 'hidden' && m.path)))
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

const editSig = () => navEditRows.value.map(r => `${r.id}:${r.hidden ? 0 : 1}`).join('|')
const navEditDirty = computed(() => editSig() !== navEditBase.value)
/**
 * 是否真的渲染编辑器 = 在编辑态 **且** 侧栏展开。折叠态下 56px 图标条容不下带文字和开关的
 * 行（实测会横向溢出），所以模板一律判这个派生值而不是裸 navEditing。
 * 主路径是 toggleFold 里调 exitNavEdit 清状态；这里是模板层第二道保险，
 * 防的是将来有别处直接改 collapsed 而绕过那条联动。
 */
const showNavEditor = computed(() => navEditing.value && !collapsed.value)

/**
 * 退出编辑态并丢弃未保存草稿。navMenus 始终是服务端算出的权威结果、从未被本地改过，
 * 所以清掉草稿状态就等于回到编辑前的样子，**不需要**重拉 /auth/me
 * （重拉反而会覆盖用户刚看到的默认态，观感上像"我的改动自己消失了"）。
 *
 * 复用场景：点编辑按钮退出、**折叠侧栏**（见 toggleFold）、保存成功、恢复默认。
 */
function exitNavEdit () {
  navEditing.value = false
  navEditRows.value = []
  navEditBase.value = ''
}

async function toggleNavEdit () {
  if (navEditing.value) { exitNavEdit(); return }
  loadingNavEdit()
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
      exitNavEdit()
      await reloadMenus()   // 重拉生效：菜单树 + 偏好都在 /auth/me 一个响应里
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
      exitNavEdit()
      await reloadMenus()
    } else message.error((res && res.msg) || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { navEditSaving.value = false }
}

/* ---------------- 拖拽排序（原生 HTML5 DnD，仅同级内重排） ----------------
 * 三个必须处理的点（与抽屉版同源，抽屉已下线，逻辑迁到这里）：
 * 1. 拖拽源存 id 而非下标 —— 每次越过一行都 swap 一次，源行会随数组移动，固定下标会算错位置；
 * 2. dragover 必须 preventDefault，否则浏览器拒绝 drop；
 * 3. 拨开关的手势会被父行 dragstart 吃掉（点击即启动拖拽）⇒ 开关上 @mousedown.stop 掐断起点。
 * 刻意不做「拖到别的父节点下」：跨级改的是菜单树结构，而树参与 RBAC 授权判定，
 * 把结构调整混进「调整口味」的手势里太易误操作 —— 跨级请用编辑菜单弹窗的父级选择。 */
const editDragId = ref(null)
const editOverId = ref(null)

function onEditDragStart (id, ev) {
  editDragId.value = id
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move'
    // Firefox 要求 setData 才会真正启动拖拽；纯文本 payload 即可（不会真的用它）
    try { ev.dataTransfer.setData('text/plain', id) } catch (e) { /* ignore */ }
  }
}
function onEditDragOver (id, ev) {
  if (!editDragId.value) return
  ev.dataTransfer.dropEffect = 'move'
  if (editOverId.value !== id) editOverId.value = id
  // 悬停越过中线即换位：拖动项实时让位，视觉上「跟着手走」
  const rect = ev.currentTarget.getBoundingClientRect()
  const crossed = ev.clientY > rect.top + rect.height / 2
  const to = moveEdit(editDragId.value, id, crossed)
  if (to) { editDragId.value = to.id; editOverId.value = to.id }
}
/** 把 from 移到 to 之前（after=false）或之后（after=true）；返回落位后的 id */
function moveEdit (fromId, toId, after) {
  if (fromId === toId) return null
  const list = navEditRows.value
  const fi = list.findIndex(r => r.id === fromId)
  if (fi < 0) return null
  const [item] = list.splice(fi, 1)
  let ti = list.findIndex(r => r.id === toId)
  if (ti < 0) { list.splice(fi, 0, item); return null }
  if (after) ti++
  list.splice(ti, 0, item)
  return { id: fromId }
}
function onEditDrop (toId) {
  if (editDragId.value && editDragId.value !== toId) {
    // drop 已由 dragover 实时换位，这里只需收尾
  }
  onEditDragEnd()
}
function onEditDragLeave (id) { if (editOverId.value === id) editOverId.value = null }
function onEditDragEnd () { editDragId.value = null; editOverId.value = null }

// 侧边栏折叠（持久化）
const collapsed = ref(localStorage.getItem('app_sidebar') === '1')
const toggleFold = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem('app_sidebar', collapsed.value ? '1' : '0')
  // 收起时顺带结束侧栏编辑：折叠是用户明确表示「收起这列」，编辑态跟着一起结束。
  // 不修的话编辑行/操作条只判 navEditing 不判 collapsed，会挤进 56px 图标条——
  // 带文字和开关的行在图标条里必然横向溢出（2026-10-08 用户截图：开关悬在条外、
  // 「恢复默认」文字糊成一团）。未保存草稿直接丢弃，语义同「点编辑按钮退出」。
  if (collapsed.value) exitNavEdit()
}

const visibleSessionList = computed(visibleSessions)

// 会话时间字段稳健解析：ISO 字符串为主（Spring 默认序列化），兼容时间戳/数组/对象形态
const sessTime = v => {
  if (!v) return null
  if (typeof v === 'number') return new Date(v)
  if (typeof v === 'string') { const d = new Date(v); return isNaN(d.getTime()) ? null : d }
  if (Array.isArray(v)) return new Date(v[0], (v[1] || 1) - 1, v[2] || 1, v[3] || 0, v[4] || 0, v[5] || 0)
  if (typeof v === 'object' && v.year) return new Date(v.year, (v.monthValue || 1) - 1, v.dayOfMonth || 1, v.hour || 0, v.minute || 0, v.second || 0)
  return null
}
// 按时间分组（后端已按 置顶→更新时间 排序，这里只分桶不改序）：置顶 / 今天 / 7 天内 / 更早；
// 组内保持后端序，空组不渲染；批量全选口径仍是 visibleSessionList（平铺），与分组显示无关
const groupedSessions = computed(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const weekStart = startOfToday - 6 * 86400000
  const groups = [
    { label: '置顶', items: [] },
    { label: '今天', items: [] },
    { label: '7 天内', items: [] },
    { label: '更早', items: [] }
  ]
  for (const s of visibleSessionList.value) {
    if (s.isPinned === 1) { groups[0].items.push(s); continue }
    const d = sessTime(s.updateTime)
    const t = d ? d.getTime() : 0
    if (t >= startOfToday) groups[1].items.push(s)
    else if (t >= weekStart) groups[2].items.push(s)
    else groups[3].items.push(s)
  }
  return groups.filter(g => g.items.length)
})

// 分组折叠状态（记忆到 localStorage，默认全展开）；搜索时强制全展开——搜到却看不见是死胡同
const GROUPS_KEY = 'app_sess_groups_collapsed'
const collapsedGroups = reactive(new Set((() => {
  try { return JSON.parse(localStorage.getItem(GROUPS_KEY) || '[]') } catch { return [] }
})()))
const toggleGroup = label => {
  collapsedGroups.has(label) ? collapsedGroups.delete(label) : collapsedGroups.add(label)
  localStorage.setItem(GROUPS_KEY, JSON.stringify([...collapsedGroups]))
}
const groupHidden = label => !searchKw.value && collapsedGroups.has(label)

// ==================== 会话分页：组头全量数字 + 「查看更多」增量加载 ====================
// 组头计数用后端 groupCounts（全量口径，仅统计有消息的会话）；后端未返回时回退已加载条数
const COUNT_KEYS = { 置顶: 'pinned', 今天: 'today', '7 天内': 'week', 更早: 'earlier' }
const groupTotal = (label, loaded) => sessionStore.counts[COUNT_KEYS[label]] ?? loaded
// 「查看更多 (N)」的 N：全量 - 已加载可展示数（两者同口径：仅统计有消息的会话）
const sessRemaining = computed(() => Math.max(0, (sessionStore.total || 0) - visibleSessionList.value.length))
// 会话行在列表里的全序下标：分组只分桶不改序，所以平铺下标就是它渲染成第几行
// （生成中的会话按它取呼吸相位，见 .sess-item.running）
const sessRowOrdinal = computed(() => {
  const m = new Map()
  visibleSessionList.value.forEach((s, i) => m.set(s.id, i))
  return m
})
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
const newChat = () => {
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
let searchTimer = null
const onSearchInput = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadSessions(searchKw.value.trim()), 300)
}
const clearSearch = () => {
  searchKw.value = ''
  clearTimeout(searchTimer)
  loadSessions('')
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
  visibleSessionList.value.length > 0 && visibleSessionList.value.every(s => batchSel.value.has(s.id)))
const enterBatchMode = () => { batchMode.value = true; batchSel.value = new Set() }
const exitBatchMode = () => { batchMode.value = false; batchSel.value = new Set() }
const toggleBatchSel = id => {
  const next = new Set(batchSel.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  batchSel.value = next
}
const toggleSelectAll = () => {
  batchSel.value = allSelected.value ? new Set() : new Set(visibleSessionList.value.map(s => s.id))
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
  // group 无 path 也保留（title 渲染用 name）；menuPending/menuTitle 对无 path 菜单天然安全（只特判 /chat 与 /knowledge）
  navMenus.value = ((info && info.menus) || [])
    .filter(m => m && (m.renderAs === 'group' || (m.renderAs !== 'tab' && m.renderAs !== 'hidden' && m.path)))
  loadSessions()
  refreshSetupGuide(true)  // 登录即可见的配置引导首拉（此时菜单树已就绪，tag/入口立即可判）
  // 通知未读轮询与 focus/visibility 刷新由 useNotifications 自持（首拉 + 30s 定时 + 卸载清理）
})
</script>

<style scoped>
.pref-section { margin-bottom: 4px; }
.pref-label { font-weight: 600; margin-bottom: 8px; }
.pref-row { display: flex; align-items: center; gap: 8px; }
.pref-hint { font-size: 12px; color: var(--app-text3, var(--app-text3)); margin-top: 6px; }
.pwd-err { color: var(--app-danger); font-size: 12px; margin: 0 0 8px; }
.side {
  width: 200px; flex: none; display: flex; flex-direction: column;
  background: var(--app-panel); border-right: 1px solid var(--app-border);
  padding: 10px 8px; transition: width .18s ease; overflow: hidden;
}
.side.collapsed { width: 56px; }
.side-logo { display: flex; align-items: center; gap: 8px; padding: 2px 6px 12px; }
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
/* 形态操作组：编辑 + 折叠 整体靠右，两者相邻。margin-left:auto 必须落在**容器**上——
   落在单个按钮上只会推走那一个，另一个留在品牌名旁（实测返工过一次）。 */
.side-logo-ops { margin-left: auto; display: flex; align-items: center; gap: 2px; }
.side-logo-ops .app-icon-btn.on { color: var(--app-accent); background: var(--app-accent-weak); }
.fold { margin-left: 0; }
.side.collapsed .fold { margin-left: 0; }

/* 编辑态行：与 .nav-item 同宽同高，肉眼只多出把手和开关 */
.nav-edit-row {
  display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 8px;
  cursor: grab; user-select: none;
  border: 1px solid transparent;
}
.nav-edit-row:hover { background: var(--app-panel-2); }
.nav-edit-row.off { opacity: .5; }
.nav-edit-row.dragging { opacity: .35; cursor: grabbing; }
.nav-edit-row.over { border-color: var(--app-accent); box-shadow: inset 0 0 0 1px var(--app-accent); }
/* 把手：默认浅灰，hover 整行时加深 —— 平时不抢眼，但要让人看出这行能拖 */
.nav-edit-grip {
  flex: none; font-size: 11px; line-height: 1; letter-spacing: -2px;
  color: var(--app-text3); opacity: .55; transition: opacity .12s, color .12s;
}
.nav-edit-row:hover .nav-edit-grip { opacity: 1; color: var(--app-text2); }
.nav-edit-ic { flex: none; font-size: 14px; color: var(--app-text2); }
.nav-edit-name { flex: 1 1 auto; min-width: 0; font-size: 13px; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 操作条：单行按钮，不额外占高度（两行式会把侧栏顶高 43px、连带会话列表整体下移）；
   提示语降为 title。white-space:nowrap 是硬要求：一换行按钮就变竖排单字（200px 实测过）。 */
.nav-edit-bar { display: flex; align-items: center; gap: 6px; margin-top: 4px; }
.nav-edit-ops { display: flex; align-items: center; gap: 6px; width: 100%; }
/* white-space:nowrap 是硬要求：一旦换行按钮就变竖排单字 */
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

.side-nav { display: flex; flex-direction: column; gap: 2px; }
/* 分组标题（renderAs=group）：不可点、无 hover，仅视觉归类。
   折叠态缩成一条细线——标题只剩占位会让图标条更乱，藏掉更干净 */
.nav-group {
  margin: 8px 9px 2px; padding: 0; font-size: 11px; color: var(--app-text3);
  letter-spacing: .05em; white-space: nowrap; user-select: none;
}
.side.collapsed .nav-group { height: 1px; margin: 6px 8px; padding: 0; overflow: hidden; background: var(--app-border); }
.nav-item {
  display: flex; align-items: center; gap: 9px; border: none; background: transparent;
  padding: 7px 9px; border-radius: 8px; font-size: 13px; color: var(--app-text2);
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
.guide-entry { margin-top: 10px; background: var(--app-accent-weak); }
.guide-entry-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.guide-count {
  flex: none; font-size: 10px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  color: var(--app-warn-text); background: var(--app-warn-weak);
}
/* 进阶弱化态：链接式（无弱底、灰字），hover 才提亮——感知得到但不催办 */
.guide-entry.adv { background: transparent; color: var(--app-text3); }
.guide-entry.adv:hover { background: var(--app-accent-weak); color: var(--app-text2); }
.guide-count.muted { color: var(--app-text3); background: var(--app-panel-2); }

.side-label { margin: 14px 8px 4px; font-size: 11px; color: var(--app-text3); }
/* 分组头可点折叠：全宽按钮化，箭头指示状态（展开=向下），右侧淡显条数 */
.sess-group-label {
  /* 不写 width:calc(100%-12px)：百分比宽 + margin 在滚动容器里会撑出横向溢出（出现横向滚动条），
     用 flex 列布局默认拉伸 + margin 收窄即可 */
  display: flex; align-items: center; gap: 4px;
  border: none; background: transparent; cursor: pointer; padding: 2px 3px; margin: 14px 6px 4px;
  border-radius: 6px; font-size: 11px; color: var(--app-text3); text-align: left;
  transition: color .15s, background .15s;
}
.sess-group-label:hover { color: var(--app-text2); background: var(--app-panel-2); }
/* 箭头靠右：文字在左，条数紧贴文字，箭头居行尾指示展开/折叠 */
.group-caret { margin-left: auto; font-size: 9px; transition: transform .15s; }
.group-caret.open { transform: rotate(90deg); }
.group-count { font-size: 10px; color: var(--app-text3); opacity: .8; }
/* 列表内时间分组组头：比页级标签更贴紧（首组上方由搜索框间距兜底） */
.sess-group-label { margin: 10px 8px 3px; }
/* 批量管理入口在每条会话「更多」菜单里（搜索框不再内嵌入口） */
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
.side-sessions { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 1px; }
.sess-item {
  display: flex; align-items: center; padding: 6px 9px; border-radius: 8px;
  font-size: 12px; color: var(--app-text2); cursor: pointer; min-width: 0;
  transition: background .12s;
}
.sess-item:hover { background: var(--app-panel-2); }
/* 当前行：弱底 + 左缘主题色窄条（比纯底色多一层方位感）；批量选中行共用弱底（无窄条） */
.sess-item.active { background: var(--app-accent-weak); color: var(--app-text); box-shadow: inset 2px 0 0 var(--app-accent); }
.sess-item.picked { background: var(--app-accent-weak); color: var(--app-text); }
.sess-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; min-width: 0; }
.sess-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-text3); margin: 0 auto; }
.sess-item.active .sess-dot { background: var(--app-accent); }
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
  position: relative; display: flex; align-items: center; margin: 6px 2px 6px;
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
.sess-op.on { opacity: 1; color: var(--app-accent); }
.sess-pin-flag { color: var(--app-accent); font-size: 10px; flex: none; margin-right: 3px; }
/* 分享标记：与置顶/收藏同级常显（不是 hover 才出现的操作按钮）——它是"这段对话正在对外"
   的状态告知，藏起来就失去了感知意义。琥珀色区别于置顶的强调色，避免两者混淆 */
.sess-share-flag { color: var(--app-warn); font-size: 10px; flex: none; margin-right: 3px; cursor: pointer; }
.sess-fav-flag { color: var(--app-warn); font-size: 10px; margin-right: 3px; }
.sess-title.fav { color: var(--app-text); }
.sess-empty { font-size: 12px; color: var(--app-text3); text-align: center; padding: 16px 0; }
/* 游标分页：底部增量加载入口——居左、内边距与 .sess-item 对齐（6px 9px），文本随行首对齐；
   展开后「查看更多」旁出现「收起」（还原首屏），蓝=主操作、灰=次级操作 */
.sess-more { display: flex; align-items: center; gap: 10px; padding: 2px 0 4px; }
.sess-more-btn {
  border: none; background: none; cursor: pointer;
  font-size: 12px; color: var(--app-accent); padding: 6px 9px; border-radius: 8px;
}
.sess-more-btn:hover { background: var(--app-accent-weak); }
.sess-more-btn.collapse { color: var(--app-text2); }
.sess-empty-more {
  display: block; margin: 6px auto 0; border: none; background: none; cursor: pointer;
  font-size: 12px; color: var(--app-accent); padding: 2px 8px; border-radius: 4px;
}
.sess-empty-more:hover { background: var(--app-accent-weak); }

.side-foot {
  display: flex; align-items: center; gap: 4px; padding: 8px 6px 2px;
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
  .sess-op { opacity: 1; }
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
