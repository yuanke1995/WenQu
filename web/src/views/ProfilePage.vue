<!-- 个人设置。长期记忆面板：框架仍负责写入（MEMORY.md + 日账），
     这里只提供「看得见、改得动、删得掉」的个人治理入口；日账只读，MEMORY.md 可编辑/清空。 -->
<template>
  <div class="app-page">
    <div class="app-page-head">
      <div class="pf-page-head">
        <h1 class="app-page-title">个人设置</h1>
        <p class="pf-page-sub">头像、偏好、记忆与密钥。</p>
      </div>
    </div>
    <div class="pf-body">
      <nav ref="pfNavEl" class="pf-nav" aria-label="个人设置分类">
        <div v-for="g in navGroups" :key="g.label" class="pf-nav-group">
          <div class="pf-nav-group-title">{{ g.label }}</div>
          <button v-for="n in g.items" :key="n.key" class="pf-nav-item"
                  :class="{ active: current === n.key }"
                  :aria-current="current === n.key ? 'page' : null"
                  @click="current = n.key">
            <component :is="n.icon" class="pf-nav-icon" />
            <span class="pf-nav-text">{{ n.label }}</span>
          </button>
        </div>
      </nav>
      <section class="pf-content">
        <!-- 个人资料（自助：昵称与头像可改，登录账号只读） -->
        <div v-if="current === 'profile'" class="app-card pf-card">
          <div class="pf-panel-head">
            <h2 class="app-card-title">个人资料</h2>
          </div>

          <!-- 头像：点预览或「上传图片」都能换图；上传图片与选图标互斥，两者都为空则显示昵称首字 -->
          <div class="pf-identity">
            <button class="pf-avatar-btn" type="button" :disabled="avatarSaving"
                    :aria-label="avatarIsImage ? '更换头像图片' : '上传头像图片'" @click="pickAvatar">
              <UserAvatar :avatar="avatarPreview" :name="nickForm.username" :size="84" />
              <span class="pf-avatar-mask" aria-hidden="true"><CameraOutlined /></span>
            </button>
            <div class="pf-identity-main">
              <div class="pf-identity-name">{{ nickForm.username || '未设置昵称' }}</div>
              <div class="pf-identity-state">{{ avatarStateText }}</div>
              <div class="pf-op-row">
                <button type="button" class="app-btn" :disabled="avatarSaving" @click="pickAvatar">
                  {{ avatarSaving ? '处理中…' : (avatarIsImage ? '更换图片' : '上传图片') }}
                </button>
                <button type="button" class="app-btn ghost" :disabled="!avatarPreview || avatarSaving" @click="clearAvatar">
                  {{ avatarIsImage ? '清除图片' : '恢复昵称首字' }}
                </button>
              </div>
              <p class="pf-sub-hint">PNG / JPEG / GIF / WebP，≤2MB</p>
            </div>
            <input ref="avatarFileEl" type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden
                   :disabled="avatarSaving" @change="onAvatarFile" />
          </div>

          <div class="pf-section">
            <div class="pf-section-head">
              <div>
                <span class="pf-label">或选择一个图标</span>
                <p class="pf-sub-hint">{{ emojiHint }}</p>
              </div>
            </div>
            <div class="pf-emoji-pick" role="group" aria-label="选择头像图标">
              <button v-for="e in AVATAR_EMOJIS" :key="e" class="pf-emoji-opt" type="button"
                      :class="{ on: avatarPreview === e }" :disabled="avatarSaving"
                      :aria-pressed="avatarPreview === e" :aria-label="'使用图标 ' + e"
                      @click="pickEmoji(e)">{{ e }}</button>
            </div>
          </div>

          <div class="pf-section">
            <div class="pf-section-head">
              <div>
                <label class="pf-label" for="pf-nickname">昵称</label>
                <p class="pf-sub-hint">仅展示用，最多 100 字</p>
              </div>
              <span class="pf-meta-right">
                <span v-if="nickDirty" class="pf-dirty">未保存</span>
                <span class="pf-count">{{ nickForm.username.length }}/100</span>
              </span>
            </div>
            <div class="pf-row">
              <a-input id="pf-nickname" v-model:value="nickForm.username" :maxlength="100" allow-clear class="pf-nick-input"
                       placeholder="显示昵称，如 张三" @pressEnter="saveNickname" />
              <button class="app-btn" :disabled="nickSaving || !nickDirty" @click="saveNickname">
                {{ nickSaving ? '保存中…' : '保存' }}
              </button>
            </div>
          </div>

          <div class="pf-section pf-account-row">
            <span class="pf-label">登录账号</span>
            <span class="pf-account-value">{{ authAccount || '—' }}</span>
            <span class="pf-sub-hint">昵称可改，登录账号不可修改</span>
          </div>
        </div>

        <!-- 问答偏好：个人默认聊天模型（选完即存）+ 个人可覆盖配置（config-schema 标 personal 的
             字段经 /user/settings 通道下发，schema 加勾选这里自动跟随，没有「保存」这一步）；
             「自动提炼记忆」开关在「我的记忆」面板 -->
        <div v-else-if="current === 'chat'" class="app-card pf-card">
          <div class="pf-panel-head pf-pref-head">
            <div>
              <h2 class="app-card-title">{{ panelMeta.title }}</h2>
              <p class="pf-panel-sub">{{ panelMeta.hint }}</p>
            </div>
            <span class="pf-autosave">改动即时保存</span>
          </div>

          <!-- 默认聊天模型：决定「用哪个模型」，与下面决定「怎么检索」的个人配置分开 -->
          <div class="pf-grp">
            <div class="pf-grp-head">
              <h3 class="pf-grp-title">默认聊天模型</h3>
            </div>
            <div class="pf-grp-body">
              <div class="pf-row">
                <!-- 用组件声明的 v-model（而非 v-model:value——那是透传到根 a-select 的偶然生效路径） -->
                <ModelSelect v-model="pref[panelMeta.field]" type="chat"
                             :issue="defaultModelIssue"
                             inherit-label="不设默认" :width="isNarrow ? '100%' : 360"
                             :disabled="loading || prefSaving" @change="saveDefaultModel" />
                <span v-if="prefSaving" class="pf-sub-hint pf-saving-tip">生效中…</span>
              </div>
              <p class="pf-grp-hint">{{ panelMeta.tail }}</p>
            </div>
          </div>

          <!-- 个人可覆盖配置：按用途分块。联网搜索先让用户选「哪家」——
               DeepSeek 等预设把地址/模板/取数路径一并填好，普通用户只面对 Key 与条数；
               传输细节收进「高级配置」，自定义入口保留完整能力。值为空=用默认，改了就存。 -->
          <div v-for="g in personalGroups" :key="g.key" class="pf-grp">
            <div class="pf-grp-head">
              <div class="pf-grp-head-main">
                <h3 class="pf-grp-title">
                  {{ g.title }}
                  <span v-if="g.key === 'wsAdv' && wsPreset === 'deepseek'" class="pf-set-badge">已按 DeepSeek 填好</span>
                  <span v-else-if="g.key === 'wsAdv' && wsPreset === 'custom' && wsAdvCustomized" class="pf-set-badge">已自定义</span>
                </h3>
                <p v-if="g.hint" class="pf-grp-hint">{{ g.hint }}</p>
              </div>
              <button v-if="g.collapsible" class="app-link-btn" type="button"
                      :aria-expanded="wsAdvOpen" @click="wsAdvOpen = !wsAdvOpen">
                {{ wsAdvOpen ? '收起' : '展开' }}
              </button>
            </div>
            <p v-if="g.collapsible && !wsAdvOpen" class="pf-ws-adv-summary">{{ wsAdvSummary }}</p>
            <div v-show="!g.collapsible || wsAdvOpen" class="pf-grp-body" :class="{ 'pf-fld-grid': g.collapsible }">
              <div v-if="g.key === 'ws'" class="pf-ws-presets">
                <button v-for="p in WS_PRESETS" :key="p.id" class="pf-ws-preset"
                        :class="{ on: wsPreset === p.id }" type="button"
                        :aria-pressed="wsPreset === p.id" @click="selectWsPreset(p.id)">
                  <span v-if="wsPreset === p.id" class="pf-ws-check">✓</span>
                  <span class="pf-ws-ico" :class="'ws-ico-' + p.id">{{ p.icon }}</span>
                  <span class="pf-ws-name">{{ p.name }}</span>
                  <span class="pf-ws-desc">{{ p.desc }}<br>{{ p.extra }}</span>
                </button>
              </div>
              <div v-if="g.key === 'ws'" class="pf-ws-status">
                <span class="pf-ws-dot"></span>
                <span>当前：<b>{{ wsStatusLabel }}</b></span>
                <span class="pf-ws-sep">·</span>
                <span>{{ wsKeyState }}</span>
                <span class="pf-ws-sep">·</span>
                <span>{{ wsCountLabel }}</span>
              </div>
              <div v-for="f in g.fields" :key="f.path" class="pf-fld"
                   :class="{ 'pf-fld-wide': f.type === 'textarea' || (g.key === 'wsAdv' && f.key === 'baseUrl') }">
                <div class="pf-fld-head">
                  <span class="pf-label">{{ f.label }}</span>
                  <a-tooltip v-if="personal.tips[f.tips]" :title="personal.tips[f.tips]"
                             placement="top" :overlay-inner-style="{ whiteSpace: 'pre-line' }">
                    <question-circle-outlined class="pf-tip-icon" />
                  </a-tooltip>
                  <span v-if="f.type === 'password' && personal.values[f.path] === '********'"
                        class="pf-set-badge">已设置</span>
                </div>
                <!-- switch：个人设置自己渲染（这里没走 SchemaField），必须给真开关而不是文本框，
                     否则用户能在布尔项里随便写字。个人配置落库是字符串 "true"/"false"，用计算属性双向转。 -->
                <a-switch v-if="f.type === 'switch'" v-model:checked="personalSwitch[f.path]"
                          class="pf-ctl" @change="commitPersonalSwitch(f, $event)" />
                <a-input-number v-else-if="f.type === 'number'" v-model:value="personalDrafts[f.path]"
                                :min="f.min" :max="f.max" :step="f.step || 1"
                                :placeholder="personalPlaceholder(f)" class="pf-ctl pf-ctl-sm"
                                @blur="commitPersonal(f)" @pressEnter="commitPersonal(f)" />
                <a-select v-else-if="f.type === 'select'" v-model:value="personalDrafts[f.path]"
                          :options="f.options" class="pf-ctl" allow-clear
                          :style="{ width: g.collapsible ? '100%' : '240px' }"
                          :placeholder="personalPlaceholder(f)" @change="commitPersonal(f)" />
                <!-- password：服务端永不回填真值（values 里是 ******** 掩码），输入框恒空，输入新值才覆盖。
                     不用 type=password：浏览器会把本页当登录页，在 Key 框上弹「保存的密码」并自动填。
                     掩码改由 CSS（-webkit-text-security）实现；不支持该属性的浏览器退回 password 类型 -->
                <a-input v-else-if="f.type === 'password'" v-model:value="personalDrafts[f.path]"
                         :placeholder="personalPlaceholder(f)" class="pf-ctl pf-secret"
                         :class="{ 'is-masked': !secretVisible[f.path] }" :type="secretInputType(f.path)"
                         :style="{ width: g.collapsible ? '100%' : '280px' }"
                         autocomplete="off" @blur="commitPersonal(f)" @pressEnter="commitPersonal(f)">
                  <template #suffix>
                    <span class="pf-secret-eye" @mousedown.prevent @click="toggleSecretVisible(f.path)">
                      <EyeOutlined v-if="secretVisible[f.path]" />
                      <EyeInvisibleOutlined v-else />
                    </span>
                  </template>
                </a-input>
                <a-textarea v-else-if="f.type === 'textarea'" v-model:value="personalDrafts[f.path]"
                            :rows="3" class="pf-ctl pf-ctl-block" :placeholder="personalPlaceholder(f)"
                            @blur="commitPersonal(f)" />
                <a-input v-else v-model:value="personalDrafts[f.path]" class="pf-ctl" allow-clear
                         :style="{ width: g.collapsible ? '100%' : (f.width ? f.width + 'px' : '280px') }"
                         :placeholder="personalPlaceholder(f)" @blur="commitPersonal(f)" @pressEnter="commitPersonal(f)" />
                <p v-if="fieldNote(f) && !(wsPreset === 'deepseek' && g.key === 'wsAdv')" class="pf-fld-note">
                  {{ f.key === 'apiKey' && wsKeyHint ? wsKeyHint : fieldNote(f) }}
                </p>
              </div>
            </div>
          </div>
          <!-- 探测按钮放在整块配置之后：高级配置展开时也仍在最后，符合「填完再测」。
               平台内置且用平台端点时无可测配置，只有自填了自建地址才给按钮 -->
          <div v-if="wsCanProbe" class="pf-grp-foot">
            <button class="app-btn ghost small" type="button" :disabled="wsProbing" @click="probeWebSearch">
              {{ wsProbing ? '测试中…' : '测试连接' }}
            </button>
            <span class="pf-grp-hint">按上方已保存的配置真搜一次（只取 1 条）</span>
            <span v-if="wsProbeHint" class="pf-probe-hint" :class="{ ok: wsProbeOk }">{{ wsProbeHint }}</span>
          </div>
        </div>

        <!-- 通知与推送：两件事——上面一行一个「通知方式」（系统级推送 / 邮件），下面才是类型清单。
             勾选即时落库（与「问答偏好」同一口径）：一排开关底下挂「保存」，改完不点就静默丢失。
             静音是整体语义：后端命中 isMuted 后既不推站内也不发邮件，所以静音行的邮件按钮同步禁用，
             不给「静音但邮件开着」这种永远不生效的假状态。 -->
        <div v-else-if="current === 'notify'" class="app-card pf-card">
          <div class="pf-panel-head pf-pref-head">
            <div>
              <h2 class="app-card-title">通知与推送</h2>
              <p class="pf-panel-sub">收哪些、怎么收；关掉的只停新通知，已有的不受影响。</p>
            </div>
            <span class="pf-autosave">{{ notifySaving ? '保存中…' : '改动即时保存' }}</span>
          </div>

          <!-- 通知方式：两行卡片。开启失败的每种原因（VAPID 未配 / 非 https / 未装到主屏幕 / 浏览器拒绝）
               都是「用户下一步该做什么」，随状态作为副文案出现，不再常驻两段说明 -->
          <div class="np-ways">
            <div class="np-way">
              <span class="np-way-ic" aria-hidden="true"><BellOutlined /></span>
              <div class="np-way-main">
                <div class="np-way-name">
                  系统级通知
                  <span class="np-badge" :class="{ on: pushSubscribedHere }">{{ pushBadge }}</span>
                </div>
                <div class="np-way-sub">{{ pushSubLine }}</div>
              </div>
              <div class="np-way-acts">
                <button v-if="!pushSubscribedHere" class="app-btn small" type="button"
                        :disabled="!pushCanEnable || pushBusy" @click="onEnablePush">允许通知</button>
                <template v-else>
                  <button class="app-btn small" type="button" :disabled="pushBusy" @click="onTestPush">发一条测试</button>
                  <button class="app-btn ghost small" type="button" :disabled="pushBusy" @click="onDisablePush">取消这台设备</button>
                </template>
              </div>
            </div>
            <div class="np-way">
              <span class="np-way-ic" aria-hidden="true"><MailOutlined /></span>
              <div class="np-way-main">
                <div class="np-way-name">
                  邮件提醒
                  <span class="np-badge" :class="{ on: mailEnabled && mailEmail.trim() }">{{ mailBadge }}</span>
                </div>
                <div class="np-way-sub">{{ mailSubLine }}</div>
              </div>
              <div v-if="mailEnabled" class="np-way-acts">
                <a-input v-model:value="mailEmail" size="small" :maxlength="160"
                         placeholder="你的通知邮箱，如 name@company.com" />
                <button class="app-btn ghost small" type="button" :disabled="mailSaving" @click="saveMailEmail">
                  {{ mailSaving ? '保存中…' : '保存' }}
                </button>
              </div>
            </div>
          </div>

          <div class="pf-field">
            <div class="pf-field-head">
              <div class="np-field-main">
                <span class="pf-label">接收哪些通知</span>
                <p class="pf-sub-hint">静音的类型既不发站内，也不发邮件。</p>
              </div>
              <span class="np-head-acts">
                <button v-if="mutedTypes.length" type="button" class="np-chip" :class="{ on: onlyMuted }"
                        :aria-pressed="onlyMuted" @click="onlyMuted = !onlyMuted">
                  <span class="np-chip-box" aria-hidden="true"></span>只看已静音
                </button>
                <span v-if="mutedTypes.length" class="pf-sub-hint">已静音 {{ mutedTypes.length }} 类</span>
                <button v-if="mutedTypes.length" class="app-link-btn" type="button" @click="receiveAll">全部开启</button>
              </span>
            </div>
            <div class="np-groups">
              <div v-for="g in notifGroups" :key="g.key" class="np-group">
                <div class="np-group-head">
                  <span class="np-dot" :class="'tone-' + g.tone"></span>
                  <span class="np-group-title">{{ g.label }}</span>
                  <span class="np-group-count">
                    {{ g.types.length }} 类<template v-if="groupMutedCount(g)"> · 已静音 {{ groupMutedCount(g) }}</template>
                  </span>
                  <span v-if="!groupOpen(g)" class="np-collapsed-tag">· 已收起</span>
                  <div class="np-group-acts">
                    <template v-if="groupOpen(g)">
                      <button class="app-link-btn" type="button" @click="setGroupMuted(g, false)">全开</button>
                      <button class="app-link-btn" type="button" @click="setGroupMuted(g, true)">全关</button>
                    </template>
                    <button v-if="!onlyMuted" class="app-link-btn" type="button"
                            :aria-expanded="groupOpen(g)" @click="toggleGroupCollapse(g.key)">
                      {{ groupOpen(g) ? '收起' : '展开 ▾' }}
                    </button>
                  </div>
                </div>
                <div v-show="groupOpen(g)" class="np-grid">
                  <div v-for="t in g.types" :key="t.type" class="np-item" :class="{ muted: muted(t.type) }">
                    <a-switch :checked="!muted(t.type)" size="small" @change="v => toggleMute(t.type, v)" />
                    <component :is="t.icon" class="np-ic" :class="t.tone" />
                    <span class="np-item-label" :title="t.hint || null">{{ t.label }}</span>
                    <a-tooltip v-if="mailEnabled" :title="mailTip(t)">
                      <span class="np-mail-wrap">
                        <button type="button" class="np-mail" :class="{ on: mailOn(t.type), off: mailLocked(t) }"
                                :disabled="mailLocked(t)" :aria-pressed="mailOn(t.type)"
                                :aria-label="'邮件提醒：' + t.label"
                                @click.stop="toggleMail(t.type, !mailOn(t.type))">
                          <MailOutlined />
                        </button>
                      </span>
                    </a-tooltip>
                  </div>
                </div>
              </div>
              <p v-if="!notifGroups.length" class="pf-sub-hint">没有已静音的类别。</p>
            </div>
          </div>

          <p class="np-foot-note">
            通知保留期由管理员配置；网页推送需要 https 或 localhost，iPhone 需先把本站添加到主屏幕。
          </p>
        </div>

        <!-- 我的记忆：框架负责写，这里负责让用户看得见、改得动、删得掉；
             「自动提炼记忆」生成闸与沉淀内容同面板，边看边管。
             阅读与编辑都按「分类 → 条目」结构化呈现（parse/serialize 见 utils/memoryDoc.js），
             不要求用户懂 Markdown；原文编辑作为高级项保留，兼容手写/异常格式的内容。 -->
        <div v-else-if="current === 'memory'" class="app-card pf-card mem-card">
          <div class="pf-panel-head">
            <h2 class="app-card-title">
              我的记忆
              <span v-if="memOverview.daily && memOverview.daily.length" class="mem-count">
                {{ memOverview.daily.length }} 天日账
              </span>
            </h2>
            <a-select v-model:value="memAgentId" class="mem-agent-select" size="small"
                      :options="memAgentOptions" style="width: 240px" placeholder="用户级记忆"
                      @change="loadMemory" />
            <div class="pf-sub-hint" style="margin-top:4px">
              可切换查看各智能体的专属记忆（该智能体「记忆域」配为专属/叠加时才产生）；用户级记忆是全体智能体共享的那一份。
            </div>
            <p class="pf-panel-sub">
              助手会在问答结束后把值得长期记住的信息整理进下面的「长期记忆」，日账则是每天追加的原始记录。
              日账是只读的，删除后无法恢复。
            </p>
          </div>

          <!-- 长期记忆的生成闸（即时生效，不参与任何保存）：只关「问答后自动提炼」这一步，
               已沉淀的记忆在后续问答里照常被提起。关掉后下一轮起才停（正在跑的那一轮不受影响）。 -->
          <div class="mem-auto-row" style="display:flex;align-items:flex-start;gap:12px">
            <div class="mem-auto-text">
              <div class="mem-auto-title">自动提炼记忆</div>
              <p class="pf-sub-hint">关掉后不再记录新内容</p>
            </div>
            <a-switch :checked="memAutoEnabled" :loading="memAutoSaving" @change="toggleMemAuto" />
          </div>

          <a-spin :spinning="memLoading">
            <div v-if="memOverview.over" class="mem-warn">
              当前约 {{ memOverview.tokens }} token，超过硬上限 {{ memOverview.maxTokens }} token；
              超出部分不会进入模型上下文。
              <button class="app-link-btn" @click="onCompactMemory">按上限裁剪</button>
            </div>

            <div class="mem-toolbar">
              <button class="app-btn" :disabled="memEditing || memSaving" @click="startMemEdit">编辑</button>
              <button class="app-btn ghost" :disabled="!memOverview.content" @click="downloadMemory">下载</button>
              <button class="app-btn ghost mem-danger"
                      :disabled="memClearing || (!memOverview.content && !(memOverview.daily && memOverview.daily.length))"
                      @click="onClearMemory">清除全部</button>
              <span class="mem-meta">
                {{ memOverview.size || 0 }} 字符 · 约 {{ memOverview.tokens || 0 }} token
                / 上限 {{ memOverview.maxTokens || 0 }}
              </span>
            </div>

            <!-- 阅读态：按分类结构化呈现（用户不需要懂 Markdown） -->
            <div v-if="!memEditing" class="mem-view">
              <template v-if="memDocView.sections.length">
                <div v-for="sec in memDocView.sections" :key="sec.id" class="mem-sec">
                  <div class="mem-sec-title">{{ sec.title }}</div>
                  <template v-for="r in secRows(sec)" :key="r.e.id">
                    <div v-if="r.kind === 'sub'" class="mem-sub-title">{{ r.e.title }}</div>
                    <div v-else class="mem-line" :class="{ raw: r.e.kind === 'raw', indent: r.indent }">
                      <span v-if="r.e.kind === 'item'" class="mem-dot">•</span>
                      <span class="mem-line-text">{{ r.e.text }}</span>
                    </div>
                  </template>
                </div>
              </template>
              <p v-else class="mem-view-empty">还没有沉淀任何记忆</p>
            </div>

            <!-- 编辑态：结构化编辑（默认）+ 原文编辑（高级）。改动只在本地，保存后才落盘 -->
            <template v-if="memEditing">
              <p class="pf-sub-hint mem-edit-hint">
                按「分类 → 条目」编辑：点条目文字即可修改；删错了也不要紧，点「取消」可放弃本次全部改动，保存后才会生效。
              </p>
              <div v-if="!memRawMode" class="mem-edit">
                <div v-for="sec in memDoc.sections" :key="sec.id" class="mem-sec">
                  <div class="mem-sec-head">
                    <template v-if="sec._renaming">
                      <a-input v-model:value="sec._draft" size="small" class="mem-sec-input"
                               placeholder="分类名，如 用户偏好" @pressEnter="commitSecRename(sec)" />
                      <button class="app-link-btn" @click="commitSecRename(sec)">确定</button>
                      <button class="app-link-btn" @click="sec._renaming = false">取消</button>
                    </template>
                    <template v-else>
                      <span class="mem-sec-title" title="点击重命名" @click="startSecRename(sec)">{{ sec.title || '未命名分类' }}</span>
                      <div class="mem-sec-acts">
                        <button class="app-link-btn" @click="addEntry(sec)">添加一条</button>
                        <button class="app-link-btn danger" @click="delSection(sec)">删除分类</button>
                      </div>
                    </template>
                  </div>
                  <template v-for="r in secRows(sec)" :key="r.e.id">
                    <div v-if="r.kind === 'sub'" class="mem-sub-head">
                      <template v-if="r.e._renaming">
                        <a-input v-model:value="r.e._draft" size="small" class="mem-sec-input"
                                 placeholder="小节名" @pressEnter="commitSubRename(r.e)" />
                        <button class="app-link-btn" @click="commitSubRename(r.e)">确定</button>
                        <button class="app-link-btn" @click="r.e._renaming = false">取消</button>
                      </template>
                      <template v-else>
                        <span class="mem-sub-title" title="点击重命名" @click="startSubRename(r.e)">{{ r.e.title || '未命名小节' }}</span>
                        <div class="mem-sec-acts">
                          <button class="app-link-btn" @click="addSubItem(r.e)">添加一条</button>
                          <button class="app-link-btn danger" @click="delSub(r.e)">删除小节</button>
                        </div>
                      </template>
                    </div>
                    <div v-else class="mem-line" :class="{ editing: memEditId === r.e.id, raw: r.e.kind === 'raw', indent: r.indent }">
                      <span v-if="r.e.kind === 'item'" class="mem-dot">•</span>
                      <template v-if="memEditId === r.e.id">
                        <a-textarea v-model:value="memEditDraft" class="mem-line-input"
                                    :auto-size="{ minRows: 1, maxRows: 8 }" />
                        <div class="mem-line-acts">
                          <button class="app-link-btn" @click="commitEntryEdit">确定</button>
                          <button class="app-link-btn" @click="memEditId = ''">取消</button>
                        </div>
                      </template>
                      <template v-else>
                        <span class="mem-line-text" @click="startEntryEdit(r.e)">{{ r.e.text }}</span>
                        <div class="mem-line-acts">
                          <button class="app-link-btn" @click="startEntryEdit(r.e)">编辑</button>
                          <button class="app-link-btn danger" @click="removeEntryById(r.e.id)">删除</button>
                        </div>
                      </template>
                    </div>
                  </template>
                </div>
                <button class="app-btn ghost mem-add-sec" @click="addSection">+ 添加分类</button>
              </div>
              <a-textarea v-else v-model:value="memEditText" :rows="16"
                          show-count class="mem-edit-area" placeholder="MEMORY.md 内容（Markdown）" />
              <div class="mem-edit-foot">
                <span class="mem-edit-count" :class="{ over: memEditOver }">
                  {{ memEditLen }} / {{ memOverview.maxChars || 0 }} 字符<template v-if="memEditOver">，已超上限</template>
                </span>
                <button class="app-link-btn" @click="toggleMemRaw">
                  {{ memRawMode ? '切换为可视化编辑' : '切换为原文编辑' }}
                </button>
                <button class="app-btn" :disabled="memSaving || memEditOver" @click="saveMemory">
                  {{ memSaving ? '保存中…' : '保存' }}
                </button>
                <button class="app-btn ghost" :disabled="memSaving" @click="cancelMemEdit">取消</button>
              </div>
            </template>

            <div class="mem-days">
              <div class="mem-days-head">日账</div>
              <div v-if="!memOverview.daily || !memOverview.daily.length" class="pf-sub-hint">还没有日账。</div>
              <div v-for="d in (memOverview.daily || [])" :key="d.date" class="mem-day-row">
                <span class="mem-day-date">{{ d.date }}</span>
                <span class="mem-meta">{{ fmtSize(d.size) }}</span>
                <div class="mem-day-acts">
                  <button class="app-link-btn" @click="viewDaily(d.date)">查看</button>
                  <button class="app-link-btn danger" @click="onDeleteDaily(d.date)">删除</button>
                </div>
              </div>
            </div>
            <p class="pf-sub-hint">
              最后合并：{{ memOverview.lastConsolidatedAt ? fmtTime(memOverview.lastConsolidatedAt) : '尚未合并' }}
              <template v-if="memOverview.lastConsolidatedAt">（后台大约每 30 分钟合并一次）</template>
            </p>
          </a-spin>

          <a-modal v-model:open="memViewOpen" :title="memViewTitle" :footer="null"
                   :width="isNarrow ? '92%' : 720">
            <pre class="mem-md">{{ memViewText }}</pre>
          </a-modal>
        </div>

        <!-- 模型能力均无个人层槽位：向量空间与知识库索引一一对应、归知识库绑定；
             重排（开关+模型）同归知识库/智能体检索设置绑定；
             问答对生成与 GraphRAG 抽取均已回落库主默认聊天模型（上方「个人默认聊天模型」） -->

        <!-- 账号安全 -->
        <div v-else-if="current === 'security'" class="app-card pf-card">
          <div class="pf-panel-head">
            <h2 class="app-card-title">账号安全</h2>
            <p class="pf-panel-sub">改完需用新密码重新登录</p>
          </div>
          <a-form layout="vertical" class="pf-form">
            <a-form-item label="当前密码" required>
              <a-input-password v-model:value="pwdForm.oldPassword" autocomplete="current-password"
                                placeholder="请输入当前密码" @pressEnter="submitPwd" />
            </a-form-item>
            <a-form-item label="新密码" required>
              <a-input-password v-model:value="pwdForm.newPassword" autocomplete="new-password"
                                placeholder="至少 6 位" />
            </a-form-item>
            <a-form-item label="确认新密码" required>
              <a-input-password v-model:value="pwdForm.confirm" autocomplete="new-password"
                                placeholder="再次输入新密码" @pressEnter="submitPwd" />
            </a-form-item>
            <p v-if="pwdError" class="pf-err">{{ pwdError }}</p>
            <button class="app-btn" :disabled="pwdSaving" @click="submitPwd">
              {{ pwdSaving ? '提交中…' : '修改密码' }}
            </button>
          </a-form>
        </div>

        <!-- 分享管理（会话只读分享）：链接发出后就散在各处，这里是统一的回看与停用入口。
             会话内分享面板管"这一条"，这里管"全部"——单条面板无法回答"我一共发出过哪些"。 -->
        <div v-else-if="current === 'shares'" class="app-card pf-card shm-card">
          <div class="pf-panel-head">
            <h2 class="app-card-title">
              分享管理
              <span v-if="shareOnCount" class="mem-count">{{ shareOnCount }} 条生效中</span>
            </h2>
            <p class="pf-panel-sub">
              会话分享生成的是<strong>只读链接</strong>：拿到链接的人可以查看这段对话，不能继续提问。
              链接展示的是会话<strong>最新内容而非快照</strong>——分享后继续在这段对话里聊到敏感内容，链接页也会跟着变。
              停止分享后链接立即失效，记录保留到清理周期结束（与会话保留期一致），也可以随时手动清除。
            </p>
          </div>
          <a-spin :spinning="shareLoading">
            <div v-if="!shares.length" class="mem-empty">
              <div class="mem-empty-ico">🔗</div>
              <p class="mem-empty-title">还没有分享过任何会话</p>
              <p class="pf-sub-hint">在对话页顶栏生成只读链接</p>
            </div>
            <div v-else class="shm-list">
              <div v-for="s in shares" :key="s.sessionId" class="shm-item"
                   :class="{ off: !s.enabled, focus: focusSid === s.sessionId }">
                <div class="shm-main">
                  <div class="shm-title">
                    {{ s.title }}
                    <span v-if="s.orphaned" class="shm-tag warn">会话已删除</span>
                    <span v-else-if="s.enabled" class="shm-tag on">生效中</span>
                    <span v-else class="shm-tag">已停止</span>
                  </div>
                  <div class="shm-meta">
                    分享于 {{ fmtTime(s.createTime) }}
                    <template v-if="s.visitCount">
                      · 已被访问 {{ s.visitCount }} 次<template v-if="s.lastVisitAt">，最近 {{ fmtTime(s.lastVisitAt) }}</template>
                    </template>
                    <template v-else> · 尚未被访问</template>
                  </div>
                  <!-- 已停止的链接不再展示地址栏：给一个打不开的链接不如明确说"已停止" -->
                  <div v-if="s.enabled" class="shm-link">
                    <span class="shm-url">{{ shareUrlOf(s.token) }}</span>
                  </div>
                  <div v-if="s.orphaned" class="shm-note">会话已删除，链接失效</div>
                </div>
                <div class="shm-acts">
                  <template v-if="s.enabled">
                    <button class="app-link-btn" @click="copyShare(s)">复制链接</button>
                    <a class="app-link-btn" :href="shareUrlOf(s.token)" target="_blank" rel="noopener">预览</a>
                    <button class="app-link-btn" @click="regenShare(s)">换新链接</button>
                    <button class="app-link-btn" @click="goSharedSession(s)">查看会话</button>
                    <button class="app-link-btn danger" @click="stopShare(s)">停止分享</button>
                  </template>
                  <template v-else>
                    <span class="shm-off-note">已停止</span>
                    <button v-if="!s.orphaned" class="app-link-btn" @click="restartShare(s)">重新开启</button>
                    <!-- 清除记录：已停用才给。悬空行没有信息价值（标题已退化、内容不可访问），
                         是堆积面板的主要来源，需要一个立即清掉的出口而不必等 30 天保留期 -->
                    <button class="app-link-btn danger" @click="purgeRecord(s)">清除记录</button>
                  </template>
                </div>
              </div>
            </div>
          </a-spin>
        </div>

        <!-- API 密钥（个人资产）：自己的 Key 恒绑自己，开放自己智能体的全能力，不经管理员；
             管理员在这里为机器号代签并看到全平台列表。
             卡片放宽：密钥表格比个人设置其它卡宽（pf-card 的 640px 装不下），akp-card 不限宽 -->
        <ApiKeysPanel v-else-if="current === 'apikeys'" class="app-card akp-card" />
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  BellOutlined, CameraOutlined, EyeInvisibleOutlined, EyeOutlined, KeyOutlined, MailOutlined,
  QuestionCircleOutlined, ReadOutlined, SafetyCertificateOutlined, SettingOutlined,
  ShareAltOutlined, UserOutlined
} from '@ant-design/icons-vue'
import { authUser, clearAuth, ensureAuth } from '../utils/auth'
import { refreshSetupGuide } from '../utils/setupGuide'
import { copyText } from '../utils/clipboard'
import { parseMemoryDoc, serializeMemoryDoc, nextEntryId } from '../utils/memoryDoc'
import { shareUrlOf } from './shareSession'
import { markSessionShared, resetSessionStore } from './store'
import { NOTIF_BUCKETS, NOTIF_BUCKET_ORDER, emitNotifChanged, notifBucket, notifTypesFor } from '../chat/useNotifications'
import { changePasswordApi, getUserPreference, setUserPreference, updateMyProfile, uploadAvatarApi,
         notificationPreferences, notificationSavePreferences, updateMyEmail,
         listMySessionShares, stopShareBySession, purgeShareRecord, enableSessionShare,
         getMemoryOverview, getMemoryContent, saveMemoryMd, compactMemory, clearMyMemory,
         deleteMemoryDaily, listAgents, getUserSettings, saveUserSettings, probeUserWebSearch } from '../api'
import ModelSelect from '../components/ModelSelect.vue'
import ApiKeysPanel from '../components/ApiKeysPanel.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { isNarrow } from '../h5/mobile'
// Web Push 的浏览器侧（能力判定 / 订阅 / 测试）：这一屏是它唯一的入口，判定与文案都从这里出
import {
  pushCapability, refreshPushState, enablePush, disablePush, testPush,
  pushPlatform, pushSubscribedHere, pushBusy
} from '../utils/webpush'

const router = useRouter()
const route = useRoute()

// 按用途分组：账号相关在前，偏好居中，数据与集成放最后。图标只做快速扫读，不承担语义。
const navGroups = [
  { label: '账号', items: [
    { key: 'profile', label: '个人资料', icon: UserOutlined },
    { key: 'security', label: '账号安全', icon: SafetyCertificateOutlined }
  ] },
  { label: '偏好', items: [
    { key: 'chat', label: '问答偏好', icon: SettingOutlined },
    { key: 'notify', label: '通知与推送', icon: BellOutlined }
  ] },
  { label: '数据与应用', items: [
    { key: 'memory', label: '我的记忆', icon: ReadOutlined },
    { key: 'shares', label: '分享管理', icon: ShareAltOutlined },
    { key: 'apikeys', label: 'API 密钥', icon: KeyOutlined }
  ] }
]
const navs = navGroups.flatMap(g => g.items)
// ?panel= 深链（配置引导建议项「去配置」直达对应面板）；未知值回落个人资料
const PANEL_KEYS = navs.map(n => n.key)
const current = ref(PANEL_KEYS.includes(route.query.panel) ? route.query.panel : 'profile')
watch(() => route.query.panel, v => { if (PANEL_KEYS.includes(v)) current.value = v })
// 窄屏 Tab 是顶部横滑条：激活项必须滚进可视区（点相邻项浏览器自己看得见，
// 但 ?panel= 深链直达最后一个 Tab 时条停在起点，激活项藏在屏外像「点了没反应」）
const pfNavEl = ref(null)
watch(current, () => nextTick(() => {
  pfNavEl.value?.querySelector('.pf-nav-item.active')
    ?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
}))

const PANELS = {
  chat: {
    field: 'defaultModel', title: '问答偏好',
    hint: '会话未选模型时使用；选择后立即生效。',
    tail: '清空后每次对话需手动选模型；图片需模型支持读图。'
  }
}
const panelMeta = computed(() => PANELS[current.value] || PANELS.chat)

// 个人默认（选完即存，setUserPreference 为部分更新：缺省字段不动；向量/重排/视觉无个人默认，归知识库绑定/检索设置/模型能力位）
const loading = ref(false)
const prefSaving = ref(false)
const pref = ref({ defaultModel: '' })
// 个人默认模型此刻能不能用（后端判：供应商被停用、模型被移出列表）。不参与保存，只驱动界面
const defaultModelIssue = ref('')

// ==================== 通知与推送（系统级推送 + 收哪些类） ====================
// 判定与文案的分工：utils/webpush.js 只回 reason 代码，这里负责把每条 reason 翻成
// 「用户下一步该点什么」——四条失败路径的动作完全不同（找管理员 / 换 https 地址 /
// 装到主屏幕 / 去浏览器站点设置改回允许），混成一句「不支持推送」就是把人往错方向支使。
const REASON_HINT = {
  loading: '正在确认这台设备能不能收…',
  unsupported: '这个浏览器不支持',
  insecure: '当前地址不是安全连接',
  'needs-install': '需先装到主屏幕',
  'not-configured': '平台未配置密钥',
  disabled: '平台总闸未开',
  denied: '浏览器已拒绝本站通知',
  ok: '可以开启'
}
const REASON_WHY = {
  loading: '',
  unsupported: '换较新的浏览器（Chrome / Edge / Safari 16.4 以上）再试；无痕/隐私窗口里也开不了。',
  insecure: '浏览器只允许 https 地址或本机地址订阅推送。现在如果是 http 直连 IP，请改用 https 地址访问本站再回来。',
  'needs-install': 'iPhone / iPad 上 Safari 标签页不支持推送：请先在会话面板底部点「安装到桌面」，再从桌面图标打开本站，然后回到这里开启。',
  'not-configured': '需要管理员先生成一对 VAPID 密钥、配进服务器环境变量（AI_VAPID_PUBLIC_KEY / AI_VAPID_PRIVATE_KEY）并重启。多副本必须同一对。',
  disabled: '需要管理员在「系统设置 → 定时任务 → 站内通知」里打开「推送到浏览器（Web Push）」。',
  denied: '浏览器记住了「不允许」。点地址栏左侧的站点信息图标 → 网站设置 → 通知 → 改成「允许」，再回来点一次。',
  ok: '点「允许通知」后浏览器会弹一次授权框；这台设备之后就能在后台或锁屏时收到通知。手机端要收到，还需要浏览器在后台活着（装到桌面更稳）。'
}
const pushReason = ref('loading')
const pushCanEnable = ref(false)
const pushDeviceCount = computed(() => ((pushPlatform.value && pushPlatform.value.devices) || []).length)
// 状态徽标 + 一行副文案：能干什么、为什么干不了都说在这一行里（详见 REASON_WHY）
const pushBadge = computed(() => {
  if (pushSubscribedHere.value) return '已开启'
  if (pushReason.value === 'loading') return '确认中…'
  return pushCanEnable.value ? '可开启' : '未开启'
})
const pushSubLine = computed(() => {
  if (pushSubscribedHere.value) {
    const n = pushDeviceCount.value
    return n > 1
      ? `这台设备已订阅；名下共 ${n} 台设备，换设备需在那台上各点一次`
      : '这台设备已订阅；换浏览器或换设备需在那台上各点一次'
  }
  return REASON_WHY[pushReason.value] || REASON_HINT[pushReason.value] || ''
})
const refreshCap = async () => {
  const c = await pushCapability()
  pushReason.value = c.reason
  pushCanEnable.value = !!c.ok
}
const onEnablePush = async () => {
  const r = await enablePush()
  if (r.ok) message.success('这台设备已开启系统级通知')
  else if (r.reason === 'failed') message.error('订阅失败：' + (r.error || '浏览器拒绝了这次订阅'))
  else { message.warning(REASON_HINT[r.reason] || '现在还不能开启'); await refreshCap() }
}
const onDisablePush = async () => {
  const r = await disablePush()
  if (r.ok) message.success('已取消这台设备的系统级通知')
  else message.error('取消失败：' + (r.error || '未知错误'))
  await refreshCap()
}
const onTestPush = async () => {
  const r = await testPush()
  if (!r.ok) { message.error(r.error || '测试推送失败'); return }
  const sent = (r.results || []).filter(x => x.result === 'sent').length
  if (sent) message.success(`已发出 ${sent} 条，去通知中心/系统通知栏看`)
  else if (!(r.results || []).length) message.warning('没有已订阅的设备：先点「允许通知」')
  else message.error('推送服务没接受（多半是密钥不配对或推送服务打不通），详情看服务端日志')
}

// 收哪些类（个人静音清单）：从通知中心整块搬来，那边只留跳转入口
const mutedTypes = ref([])
// 邮件提醒：平台通道是否开 / 本人邮箱 / 已勾选的类型
const mailEnabled = ref(false)
const mailEmail = ref('')
const mailTypes = ref([])
const mailSaving = ref(false)
const mailOn = t => mailTypes.value.includes(t)
// 静音命中后后端既不推站内也不发邮件：静音行与「还没填邮箱」时邮件按钮一律禁用，避免假状态
const mailLocked = t => muted(t.type) || !mailEmail.value.trim()
const mailTip = t => muted(t.type)
  ? '这类已静音：站内和邮件都不会发'
  : (mailEmail.value.trim() ? '同时发一封邮件到你的通知邮箱' : '先保存邮箱，邮件才发得出去')
const mailBadge = computed(() => {
  if (!mailEnabled.value) return '未开启'
  return mailEmail.value.trim() ? '已开启' : '待填邮箱'
})
const mailSubLine = computed(() => {
  if (!mailEnabled.value) return '管理员尚未开启邮件通道，站内通知不受影响'
  return mailEmail.value.trim()
    ? '行尾点亮 ✉ 的类型会另发一封邮件'
    : '先保存一个邮箱，勾选的邮件才发得出去'
})
const toggleMail = (t, on) => {
  mailTypes.value = on ? [...new Set([...mailTypes.value, t])] : mailTypes.value.filter(x => x !== t)
  scheduleNotifySave()
}
async function saveMailEmail () {
  mailSaving.value = true
  try {
    await updateMyEmail(mailEmail.value.trim())
    message.success('邮箱已保存')
  } catch (e) {
    message.error('保存失败：' + (e.message || '请重试'))
  } finally { mailSaving.value = false }
}
const muted = t => mutedTypes.value.includes(t)
const toggleMute = (t, on) => {
  mutedTypes.value = on ? mutedTypes.value.filter(x => x !== t) : [...new Set([...mutedTypes.value, t])]
  scheduleNotifySave()
}
// 类型清单按身份收敛：与后端收件人口径一致（管理员 / 平台级 / 企业面），
// 普通用户不该对着一排永远收不到的开关做选择。authUser 未就绪时先按普通用户渲染。
const notifAudience = computed(() => ({
  admin: Boolean(authUser.value && authUser.value.admin),
  superadmin: Boolean(authUser.value && authUser.value.superadmin),
  enterprise: Boolean(authUser.value && authUser.value.profile && authUser.value.profile !== 'standard')
}))
// 按语义桶分组：20+ 个开关平铺读不动，先给「需要你处理 / 异常与失败 / 完成」三组
const notifGroups = computed(() => {
  const visible = notifTypesFor(notifAudience.value)
  return NOTIF_BUCKET_ORDER
    .map(key => {
      const all = visible.filter(t => notifBucket(t.type) === key)
      return {
        key,
        label: NOTIF_BUCKETS[key].label,
        tone: NOTIF_BUCKETS[key].tone,
        // all=整组（「全开/全关」的落点）；types=本次该渲染的行（只看已静音时只留静音项）
        all,
        types: onlyMuted.value ? all.filter(t => muted(t.type)) : all
      }
    })
    .filter(g => g.types.length)
})
const groupMutedCount = g => g.all.filter(t => muted(t.type)).length
// 只看已静音：静音是不可见的操作，给一个审自己的出口；过滤时分组强制展开
const onlyMuted = ref(false)
watch(mutedTypes, list => { if (!list.length) onlyMuted.value = false })
// 分组折叠（「完成」默认收起）：记在本地，下次进页保持；做完的事不必天天占屏
const COLLAPSE_KEY = 'notif.collapsedGroups'
const loadCollapsed = () => {
  try {
    const raw = localStorage.getItem(COLLAPSE_KEY)
    if (raw) {
      const v = JSON.parse(raw)
      if (v && typeof v === 'object' && !Array.isArray(v)) return v
    }
  } catch { /* 本地状态坏了按默认收起 */ }
  return { done: true }
}
const collapsedGroups = ref(loadCollapsed())
const isCollapsed = k => Boolean(collapsedGroups.value[k])
const groupOpen = g => onlyMuted.value || !isCollapsed(g.key)
const toggleGroupCollapse = k => {
  collapsedGroups.value = { ...collapsedGroups.value, [k]: !collapsedGroups.value[k] }
  try { localStorage.setItem(COLLAPSE_KEY, JSON.stringify(collapsedGroups.value)) } catch { /* 存不了就只在本次生效 */ }
}
// 组级全开/全关：落点是整组（g.all），不受「只看已静音」的显示过滤影响
const setGroupMuted = (g, mutedFlag) => {
  const ids = g.all.map(t => t.type)
  mutedTypes.value = mutedFlag
    ? [...new Set([...mutedTypes.value, ...ids])]
    : mutedTypes.value.filter(x => !ids.includes(x))
  scheduleNotifySave()
}
const receiveAll = () => {
  mutedTypes.value = []
  scheduleNotifySave()
}
// 勾选即时落库（防抖）：失败回读服务端，界面以后端为准
const notifySaving = ref(false)
let notifyTimer = null
let notifySeq = 0
function scheduleNotifySave () {
  clearTimeout(notifyTimer)
  notifyTimer = setTimeout(saveNotifyPrefs, 400)
}
async function saveNotifyPrefs () {
  const seq = ++notifySeq
  notifySaving.value = true
  try {
    await notificationSavePreferences(mutedTypes.value, mailTypes.value)
    // 静音变化不影响已收到的通知，但通知中心的筛选计数应与新偏好保持一致
    emitNotifChanged('center')
  } catch (e) {
    message.error('通知偏好保存失败：' + (e.message || '请重试'))
    await loadMutes()
  } finally {
    if (seq === notifySeq) notifySaving.value = false
  }
}
const loadMutes = async () => {
  try {
    const r = await notificationPreferences()
    const d = (r && r.data) || {}
    mutedTypes.value = d.mutedTypes || []
    mailTypes.value = d.mailTypes || []
    mailEnabled.value = Boolean(d.mailEnabled)
    mailEmail.value = d.email || ''
  } catch (e) { message.error('通知偏好读取失败：' + (e.message || '请重试')) }
}

const load = async () => {
  loading.value = true
  try {
    const r = await getUserPreference()
    const d = (r && r.data) || {}
    pref.value = {
      defaultModel: d.defaultModel || ''
    }
    defaultModelIssue.value = d.defaultModelIssue || ''
    // 用户级记忆开关（后端口径：null 视为开）
    memAutoEnabled.value = d.memoryEnabled !== false
  } catch (e) { /* 拉取失败保持空（未设默认） */ }
  finally { loading.value = false }
}

const saveDefaultModel = async v => {
  prefSaving.value = true
  try {
    await setUserPreference({ defaultModel: v || '' })
    message.success(v ? '已设为默认模型' : '已清除默认模型')
  } catch (e) {
    message.error(e.message || '设置失败')
  } finally {
    // 成败都回读服务端：成功要刷新「这个模型还能不能用」的判定，失败要把选择器退回真正生效的那一个
    await load()
    prefSaving.value = false
    refreshSetupGuide(true)  // 个人默认模型变了 → 引导 tag/卡片立即对账
  }
}

// ---- 个人可覆盖配置（问答偏好）：字段清单由 /user/settings 下发（schema 标 personal 的字段），
// 改完即存（失焦/回车/切换），清空=回落默认值。仅本人问答生效。
// switch 类型不渲染：「未设=用默认」与「显式关」两种状态表达不出来，等真出现个人开关字段再定口径
const personal = ref({ fields: [], values: {}, globals: {}, tips: {} })
const personalDrafts = ref({})
const personalSwitch = ref({})
// 密钥框显隐与掩码：掩码走 CSS 而非 type=password（见模板注释），逐个字段独立记显隐
const secretVisible = ref({})
const cssTextSecurity = typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
  && CSS.supports('-webkit-text-security', 'disc')
const secretInputType = path => (secretVisible.value[path] || cssTextSecurity ? 'text' : 'password')
const toggleSecretVisible = path => { secretVisible.value[path] = !secretVisible.value[path] }
// 占位符口径：有全局层的键展示「跟随系统（全局值）」；个人专属键（personalOnly，无全局层，
// globals 回空）展示「默认（内置默认值）」——平台设置页退役后这类键只有个人设置这一个入口。
// 内置默认值为空（地址/Key/模板这类自由文本）时退回字段自带的示例（schema 的 ph），
// 不再拼出「默认（）」这种既没信息又像没加载完的占位符。
const hasGlobalLayer = f => !!(personal.value.globals || {})[f.path]
const hasPersonalValue = f => !!(personal.value.values || {})[f.path]
const personalPlaceholder = f => {
  // password 恒空不回填：占位符区分「已设置（输入新值才覆盖）/ 未设置」
  if (f.type === 'password') {
    return hasPersonalValue(f) ? '已设置（输入新 Key 可覆盖，留空沿用）' : '未设置'
  }
  // 平台内置地址只服务 generic，自定义服务商留空不会回落它——别让「跟随系统」把必填项伪装成可留空
  if (f.path === 'webSearch.baseUrl') {
    if (wsPreset.value === 'custom') return '必填：搜索端点地址（如 https://api.example.com/v1/search）'
    if (wsPreset.value === 'tavily') return '留空用官方端点（https://api.tavily.com/search）'
    if (wsPreset.value === 'bocha') return '留空用博查官方端点'
  }
  if (hasGlobalLayer(f)) return `跟随系统（${personal.value.globals[f.path]}）`
  const def = f.def === null || f.def === undefined ? '' : String(f.def)
  if (def !== '') return `默认（${def}）`
  return f.ph || '未设置'
}

// ---- 问答偏好分组：字段清单仍由 /user/settings 下发，这里只决定「此刻该露哪些、怎么排」----
// 服务商预设：普通用户只选「哪家」，地址/模板/取数路径由预设替他们填好；
// 落库仍是 provider + custom* 原字段，选「自定义」不会失去任何能力。
const DEEPSEEK_SEARCH = {
  'webSearch.provider': 'custom',
  'webSearch.baseUrl': 'https://api.deepseek.com/v1/tools/search',
  'webSearch.customMethod': 'POST',
  'webSearch.customBody': '{"query":"{{query}}","count":{{count}},"need_content":false}',
  'webSearch.customResultsPath': 'results',
  'webSearch.customTitlePath': 'title',
  'webSearch.customUrlPath': 'url',
  'webSearch.customSnippetPath': 'summary',
  'webSearch.customPublishedPath': 'date'
}
const WS_PRESETS = [
  { id: 'generic', name: '平台内置', icon: '◎', desc: 'SearXNG · 免费', extra: '无需 Key' },
  { id: 'deepseek', name: 'DeepSeek', icon: 'D', desc: '官方搜索 API', extra: '按请求计费' },
  { id: 'tavily', name: 'Tavily', icon: 'T', desc: '面向模型的摘要', extra: '海外服务' },
  { id: 'bocha', name: '博查', icon: '博', desc: '国内直连', extra: '中文结果好' },
  { id: 'custom', name: '自定义', icon: '</>', desc: '任意搜索 API', extra: '填模板与路径' }
]
// 当前服务商取草稿（切换即重排、不等保存回读），未设置按运行时口径回落 generic
// （WebSearchService：personalOnly 键没有全局层，空值一律走平台内置 SearXNG）
const wsProvider = computed(() => personalDrafts.value['webSearch.provider'] || 'generic')
// provider=custom 时再按已填的服务地址识别 DeepSeek；改掉地址就自然回到「自定义」
const wsPreset = computed(() => {
  const p = wsProvider.value
  if (p === 'tavily' || p === 'bocha') return p
  if (p !== 'custom') return 'generic'
  const base = String(personalDrafts.value['webSearch.baseUrl'] || '')
  return base.includes('api.deepseek.com/v1/tools/search') ? 'deepseek' : 'custom'
})
const wsStatusLabel = computed(() => ({
  generic: '平台内置（免费）', deepseek: 'DeepSeek 搜索', tavily: 'Tavily', bocha: '博查', custom: '自定义搜索 API'
})[wsPreset.value])
const wsApiKeySet = computed(() => (personal.value.values || {})['webSearch.apiKey'] === '********')
const wsKeyState = computed(() => wsPreset.value === 'generic'
  ? '无需 Key' : (wsApiKeySet.value ? 'Key 已配置' : 'Key 未配置'))
const wsMaxResults = computed(() => {
  // 平台内置是平台管理的选项：固定内置默认值，不读个人设置（用户只选不改）
  if (wsPreset.value === 'generic') {
    const f = (personal.value.fields || []).find(x => x.path === 'webSearch.maxResults')
    return f && f.def !== null && f.def !== undefined ? f.def : 10
  }
  const draft = personalDrafts.value['webSearch.maxResults']
  if (draft !== null && draft !== undefined && draft !== '') return draft
  return (personal.value.values || {})['webSearch.maxResults'] || 10
})
const wsCountLabel = computed(() => wsPreset.value === 'generic'
  ? `每次 ${wsMaxResults.value} 条（固定）`
  : `每次 ${wsMaxResults.value} 条`)
// 平台内置（且没自填自建地址）是平台自己管的服务商，个人无可调配置，不给「测试连接」；
// 一旦填了自建 SearXNG 地址，测的就是用户自己的端点，按钮保留
const wsCanProbe = computed(() => !(wsPreset.value === 'generic'
  && !String(personalDrafts.value['webSearch.baseUrl'] || '').trim()))
// 高级区：预设（DeepSeek）与自定义共用；Tavily/博查/平台内置只留可选的代理/自建地址
const WS_ADV_KEYS = new Set([
  'baseUrl', 'model', 'customMethod', 'customBody', 'customResultsPath',
  'customTitlePath', 'customUrlPath', 'customSnippetPath', 'customPublishedPath'
])
const wsAdvOpen = ref(false)
// GET 模式用 URL 模板（服务地址里带 {{query}}），请求体模板完全不参与渲染——
// 只在 POST 下露出，避免填了不生效还误以为配置好了
const wsMethod = computed(() => String(personalDrafts.value['webSearch.customMethod'] || 'POST').toUpperCase())
const wsFieldVisible = f => {
  if (f.panel !== 'webSearch') return true
  if (f.key === 'provider') return false              // 用上方预设卡片代替下拉
  if (f.key === 'apiKey') return wsProvider.value !== 'generic'
  if (f.key === 'maxResults') return wsPreset.value !== 'generic'   // 平台内置只选不改
  if (f.key === 'customBody') return wsMethod.value === 'POST'
  // {{model}} 不在模板里就不露模型名，省得用户以为它影响搜索
  if (f.key === 'model') return String(personalDrafts.value['webSearch.customBody'] || '').includes('{{model}}')
  return true
}
const wsAdvCustomized = computed(() =>
  (personal.value.fields || []).some(f => WS_ADV_KEYS.has(f.key) && hasPersonalValue(f)))
const wsAdvSummary = computed(() => {
  const base = String(personalDrafts.value['webSearch.baseUrl'] || '')
  if (wsPreset.value === 'deepseek') {
    return 'POST https://api.deepseek.com/v1/tools/search · 结果取 results[] · 摘要取 summary'
  }
  if (wsPreset.value === 'custom') {
    return base || '尚未填写服务地址，展开后填写端点、请求模板与响应取数路径'
  }
  if (wsPreset.value === 'tavily') return base ? `代理地址：${base}` : '使用 Tavily 官方端点（api.tavily.com/search）'
  if (wsPreset.value === 'bocha') return base ? `代理地址：${base}` : '使用博查官方端点'
  return base ? `自建地址：${base}` : '使用平台内置搜索（免费）'
})
const wsAdvHint = computed(() => {
  if (wsPreset.value === 'deepseek') return '通常无需修改；改掉服务地址会转为自定义配置。'
  if (wsPreset.value === 'custom') return '服务地址、请求模板与响应取数路径；适用于能读接口文档的用户。'
  if (wsPreset.value === 'generic') return '可选：自建 SearXNG 或代理地址；留空用平台内置。'
  return '可选：代理或自建网关地址；留空用官方端点。'
})
const wsKeyHint = computed(() => {
  const where = { deepseek: 'DeepSeek 开放平台', tavily: 'Tavily 控制台', bocha: '博查控制台' }[wsPreset.value]
  return where ? `在 ${where}创建 API Key 后粘贴；RSA 加密入库。各服务商共用一把 Key，切换后请确认` : ''
})
const selectWsPreset = async id => {
  // 只有自定义需要立刻看到传输参数；其余预设默认收起，避免技术字段扑面而来
  wsAdvOpen.value = id === 'custom'
  let payload
  if (id === 'custom') {
    wsAdvOpen.value = true
    // 从 DeepSeek 预设切到自定义：清掉预设端点，否则地址仍含 api.deepseek.com，
    // 卡片会继续识别成 DeepSeek，用户会以为「点了没反应」
    payload = { 'webSearch.provider': 'custom' }
    if (wsPreset.value === 'deepseek') payload['webSearch.baseUrl'] = ''
  } else if (id === 'deepseek') {
    payload = { ...DEEPSEEK_SEARCH }
  } else {
    // 切走计费服务商时清掉个人地址：configuredBaseUrl 对任意服务商都生效，
    // 留着上一家的端点会把 Tavily/博查/内置的请求串到别家去
    payload = { 'webSearch.provider': id, 'webSearch.baseUrl': '' }
  }
  try {
    await saveUserSettings(payload)
    await loadPersonal()
  } catch (e) {
    message.error(e.message || '切换服务商失败')
    await loadPersonal()
  }
}
// 字段备注：这一段本来就只在 custom 下露出，「仅 custom 使用」再逐行重复就是噪音；
// 去掉这类从句，只留下确实需要人读的信息（占位符、路径语义、加密口径）。
const fieldNote = f => String(f.note || '')
  .replace(/^仅\s*custom\s*(服务商)?使用\s*[；;]?\s*/i, '')
  .replace(/[；;]\s*仅\s*custom\s*(服务商)?使用\s*$/i, '')
  .trim()
const personalGroups = computed(() => {
  const fs = personal.value.fields || []
  const out = []
  const retrieval = fs.filter(f => f.panel !== 'webSearch' && f.panel !== 'browser')
  if (retrieval.length) {
    out.push({
      key: 'retrieval',
      title: '回答检索',
      hint: '每次回答最多带多少条知识块进上下文；越大信息越全，也越费 token。',
      fields: retrieval
    })
  }
  const browser = fs.filter(f => f.panel === 'browser')
  if (browser.length) {
    out.push({
      key: 'browser',
      title: '浏览器操作',
      fields: browser
    })
  }
  const basic = fs.filter(f => f.panel === 'webSearch' && f.key !== 'provider'
    && !WS_ADV_KEYS.has(f.key) && wsFieldVisible(f))
  // 平台内置没有任何可编辑字段，但预设卡片与状态行仍要显示——分组按「有联网搜索字段」判断
  if (fs.some(f => f.panel === 'webSearch')) {
    out.push({
      key: 'ws',
      title: '联网搜索',
      hint: '选一个搜索服务商；不设置时用平台内置（免费）。计费服务商按你的配置搜，费用自理。',
      fields: basic
    })
  }
  // 传输类字段的阅读顺序：地址 → 方法/取数路径 → 请求体 → 其余响应映射；
  // 与两列网格配合，请求体独占一行，长路径字段不挤成半截
  const order = ['baseUrl', 'customMethod', 'customResultsPath', 'customBody', 'model',
    'customTitlePath', 'customUrlPath', 'customSnippetPath', 'customPublishedPath']
  let adv = fs.filter(f => WS_ADV_KEYS.has(f.key) && wsFieldVisible(f))
    .sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key))
  // 平台内置没有可调参数（只选不改）；tavily/博查只留可选的代理/自建地址
  if (wsPreset.value === 'generic') adv = []
  else if (wsPreset.value !== 'deepseek' && wsPreset.value !== 'custom') {
    adv = adv.filter(f => f.key === 'baseUrl')
  }
  if (adv.length) {
    out.push({
      key: 'wsAdv',
      title: '高级配置',
      collapsible: true,
      hint: wsAdvHint.value,
      fields: adv
    })
  }
  return out
})
const loadPersonal = async () => {
  try {
    const r = await getUserSettings()
    const d = (r && r.data) || { fields: [], values: {}, globals: {}, tips: {} }
    personal.value = d
    const drafts = {}
    for (const f of d.fields || []) {
      const raw = (d.values || {})[f.path]
      if (f.type === 'password') drafts[f.path] = null   // 敏感项永不回填（values 里只是掩码）
      else if (raw === undefined || raw === '') drafts[f.path] = null
      else if (f.type === 'number') drafts[f.path] = Number(raw)
      else drafts[f.path] = raw
    }
    personalDrafts.value = drafts
    // 布尔项的展示态：未设置时按 schema 默认值（def）显示，设置过按 "true"/"false" 解析。
    // 与文本草稿分开存，避免 a-switch 的 checked(boolean) 与草稿(string) 互相污染。
    const sw = {}
    for (const f of d.fields || []) {
      if (f.type !== 'switch') continue
      const raw = (d.values || {})[f.path]
      sw[f.path] = raw === undefined || raw === '' ? !!f.def : String(raw) === 'true'
    }
    personalSwitch.value = sw
    // 自定义但还没填地址：直接展开高级区，省得用户对着「已自定义」找不到必填项
    if (wsPreset.value === 'custom' && !drafts['webSearch.baseUrl']) wsAdvOpen.value = true
  } catch (e) { /* 读取失败按无个人字段渲染：这个面板还有默认模型可管，不必整块报错 */ }
}
// 布尔项提交：显式写 "true"/"false" 字符串（后端按 schema 的 switch 类型校验并落 personal 层）。
// 与 commitPersonal 的「清空=默认」语义不同——布尔项关掉就是 false，它恰好等于默认，不需要清空。
const commitPersonalSwitch = async (f, checked) => {
  const normalized = checked ? 'true' : 'false'
  const savedRaw = (personal.value.values || {})[f.path] ?? ''
  if (normalized === String(savedRaw) || (normalized === 'false' && savedRaw === '')) return
  try {
    await saveUserSettings({ [f.path]: normalized })
    message.success('已保存')
    await loadPersonal()
  } catch (e) {
    message.error(e.message || '保存失败')
    await loadPersonal()
  }
}

// 提交单项：清空（null/空串）=回落默认；与已存值相同不提交。成败都回读服务端，输入框以服务端为准
const commitPersonal = async f => {
  const draft = personalDrafts.value[f.path]
  const normalized = draft === null || draft === undefined ? '' : String(draft).trim()
  // password 只在输入了新值时提交：输入框恒空，误触失焦不能把已存的 Key 清掉（要换厂商先切服务商）
  if (f.type === 'password' && normalized === '') return
  const savedRaw = (personal.value.values || {})[f.path] ?? ''
  if (normalized === String(savedRaw)) return
  try {
    await saveUserSettings({ [f.path]: normalized })
    message.success(normalized === '' && !hasGlobalLayer(f) ? '已清除，用默认值' : '已保存')
    await loadPersonal()
  } catch (e) {
    message.error(e.message || '保存失败')
    await loadPersonal()
  }
}

// ---- 个人联网搜索「测试连接」：端点按本人已存配置打真实最小搜索（服务商/Key/模板与运行时同口径） ----
const wsProbing = ref(false)
const wsProbeOk = ref(false)
const wsProbeHint = ref('')
const probeWebSearch = async () => {
  wsProbing.value = true
  wsProbeHint.value = ''
  try {
    const r = await probeUserWebSearch()
    const d = (r && r.data) || {}
    wsProbeOk.value = !!d.available
    wsProbeHint.value = d.available
      ? `可用（${d.latencyMs ?? '?'}ms）：${d.detail || ''}`
      : `不可用：${d.detail || '探测失败'}`
  } catch (e) {
    wsProbeOk.value = false
    wsProbeHint.value = e.message || '探测请求失败'
  } finally {
    wsProbing.value = false
  }
}

// 面板切换才拉（个人设置是常驻页，进页就拉会白跑）；深链直达时 watch 不触发，onMounted 补一次
watch(current, v => { if (v === 'chat') loadPersonal() })

// ---- 账号安全：修改密码（成功后强制重新登录） ----
const pwdSaving = ref(false)
const pwdError = ref('')
const pwdForm = ref({ oldPassword: '', newPassword: '', confirm: '' })
const submitPwd = async () => {
  const f = pwdForm.value
  pwdError.value = ''
  if (!f.oldPassword) { pwdError.value = '请输入当前密码'; return }
  if (!f.newPassword || f.newPassword.length < 6) { pwdError.value = '新密码至少 6 位'; return }
  if (f.newPassword !== f.confirm) { pwdError.value = '两次输入的新密码不一致'; return }
  pwdSaving.value = true
  try {
    const r = await changePasswordApi(f.oldPassword, f.newPassword)
    if (r && r.success) {
      message.success('密码已修改，请重新登录')
      clearAuth()
      resetSessionStore()
      router.push('/login')
    } else {
      message.error((r && r.msg) || '修改失败')
    }
  } catch (e) { message.error(e.message || '修改失败') }
  finally { pwdSaving.value = false }
}

// ---- 个人资料：昵称（自助修改；登录账号另展示、不可改） ----
const nickSaving = ref(false)
const nickForm = ref({ username: '' })
const originalNickname = ref('')
const authAccount = computed(() => (authUser.value && authUser.value.user) || '')
// 与已保存的昵称比对：未改动或清空时不置灰保存，避免无意义请求
const nickDirty = computed(() => nickForm.value.username.trim() !== originalNickname.value.trim())
const saveNickname = async () => {
  const name = nickForm.value.username.trim()
  if (!name) { message.warning('昵称不能为空'); return }
  if (!nickDirty.value) return
  nickSaving.value = true
  try {
    const r = await updateMyProfile(name)
    if (r && r.success !== false) {
      nickForm.value.username = name
      originalNickname.value = name
      message.success('昵称已保存')
      await ensureAuth(true) // 刷新 /auth/me 缓存 → AppLayout 的 computed userName 立即跟着变
    } else message.error(r?.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { nickSaving.value = false }
}

// ---- 个人资料：头像（emoji 选择 / 图片上传 / 清除；与昵称同源 updateMyProfile，改动后同步身份缓存） ----
const avatarPreview = ref('')
const avatarSaving = ref(false)
const avatarFileEl = ref(null)
const avatarIsImage = computed(() => (avatarPreview.value || '').trim().startsWith('/'))
const avatarStateText = computed(() => {
  const a = (avatarPreview.value || '').trim()
  if (!a) return '当前显示昵称首字'
  return a.startsWith('/') ? '当前显示上传图片' : '当前显示图标'
})
const emojiHint = computed(() => avatarIsImage.value
  ? '点选即生效，会替换当前上传的图片'
  : '点选即生效；不选则显示昵称首字')
// emoji 可选集（比智能体图标集更生活化，便于个人辨识）
const AVATAR_EMOJIS = ['🤖', '🧠', '💡', '📚', '⚖️', '📊', '✍️', '🔍', '🛠️', '💬',
  '🎯', '🧭', '📝', '🌐', '⚡', '🌟', '🍀', '🔥', '🌈', '🐱',
  '🐶', '🦊', '🐼', '🌸', '⚓', '🎨', '🚀', '💎', '☕', '🍎']
const AVATAR_MIMES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
const MAX_AVATAR_BYTES = 2 * 1024 * 1024
// 头像操作只动 avatar 列：昵称传「已保存值」而非输入框草稿，避免换头像时把未保存的昵称一起提交
const savedNickname = () => originalNickname.value || nickForm.value.username
const pickAvatar = () => { if (!avatarSaving.value) avatarFileEl.value?.click() }
const pickEmoji = async (e) => {
  if (avatarSaving.value || avatarPreview.value === e) return
  avatarSaving.value = true
  try {
    const r = await updateMyProfile(savedNickname(), e)
    if (r && r.success !== false) { avatarPreview.value = e; await ensureAuth(true); message.success('头像已更新') }
    else message.error(r?.msg || '保存失败')
  } catch (err) { message.error(err.message || '保存失败') }
  finally { avatarSaving.value = false }
}
const onAvatarFile = async (ev) => {
  const file = ev.target.files && ev.target.files[0]
  ev.target.value = '' // 允许重复选同一文件
  if (!file || avatarSaving.value) return
  // 先在前端拦格式与大小：不满足时直接说清楚，不必白跑一次上传
  if (file.type && !AVATAR_MIMES.includes(file.type)) {
    message.error('仅支持 PNG / JPEG / GIF / WebP 格式的图片')
    return
  }
  if (file.size > MAX_AVATAR_BYTES) {
    message.error('图片超过 2MB，请压缩后再上传')
    return
  }
  avatarSaving.value = true
  try {
    const r = await uploadAvatarApi(file)
    const url = r && r.data && r.data.url
    if (url) { avatarPreview.value = url; await ensureAuth(true); message.success('头像已上传') }
    else message.error('上传失败')
  } catch (err) { message.error(err.message || '上传失败') }
  finally { avatarSaving.value = false }
}
const clearAvatar = async () => {
  if (avatarSaving.value) return
  const wasImage = avatarIsImage.value
  avatarSaving.value = true
  try {
    const r = await updateMyProfile(savedNickname(), '')
    if (r && r.success !== false) {
      avatarPreview.value = ''
      await ensureAuth(true)
      message.success(wasImage ? '已清除上传图片' : '已恢复昵称首字')
    } else message.error(r?.msg || '操作失败')
  } catch (err) { message.error(err.message || '操作失败') }
  finally { avatarSaving.value = false }
}

// ---- 长期记忆：用户级自动提炼开关（仅关生成，不关注入） ----
const memAutoEnabled = ref(true)
const memAutoSaving = ref(false)
const toggleMemAuto = async checked => {
  memAutoSaving.value = true
  try {
    const r = await setUserPreference({ memoryEnabled: checked })
    if (r && r.success !== false) {
      memAutoEnabled.value = checked
    } else { message.error(r?.msg || '保存失败'); await load() }
  } catch (e) {
    message.error(e.message || '保存失败')
    await load() // 回落服务端状态，避免开关与服务端不一致
  } finally { memAutoSaving.value = false }
}

// ---- 我的记忆（个人设置 → 我的记忆） ----
// 数据源是后端 user-data/agentscope/{uid}/MEMORY.md 与 memory/*.md；后端按登录 uid 解析，
// 前端不传用户。日账只读，MEMORY.md 可编辑/清空；清空与会自动写入的后台任务互斥由后端标记实例失效。
// 阅读与编辑都不直接暴露 Markdown：overview.content 经 utils/memoryDoc 解析成「分类 → 条目」，
// 保存时再序列化回同一格式（round-trip 保真，见该文件的注释）；原文编辑仅作高级项保留。
const memLoading = ref(false)
const memSaving = ref(false)
const memClearing = ref(false)
// 记忆域切换（对外能力第二批）：null=用户级记忆；非空=该智能体的专属记忆根
const memAgentId = ref(null)
const memAgentOptions = ref([{ label: '用户级记忆（全体共享）', value: null }])
// 阅读态：直接从 overview.content 解析（内容被后台任务更新时这里跟着变）
const memDocView = computed(() => parseMemoryDoc(memOverview.value.content || ''))
const memEditing = ref(false)
// 原文编辑（高级项）：内容没有 ## 小节结构时直接进原文模式，结构化模式无从下手
const memRawMode = ref(false)
const memDoc = ref({ header: '', sections: [] })
const memEditText = ref('')
const memEditId = ref('')   // 正在编辑的条目 id（含小节内条目）
const memEditDraft = ref('')
const memEditLen = computed(() =>
  memRawMode.value ? (memEditText.value || '').length : serializeMemoryDoc(memDoc.value).length)
const memEditOver = computed(() => memEditLen.value > (memOverview.value.maxChars || 0))
// 进入条目编辑时把焦点放进输入框：点文字即改，不用再点一次文本框
watch(memEditId, async id => {
  if (!id) return
  await nextTick()
  const el = document.querySelector('.mem-line.editing .mem-line-input textarea')
  if (el && el.focus) el.focus()
})
const memViewOpen = ref(false)
const memViewTitle = ref('')
const memViewText = ref('')
const memOverview = ref({
  content: '', size: 0, tokens: 0, maxTokens: 0, maxChars: 0, over: false,
  daily: [], lastConsolidatedAt: ''
})

const fmtSize = n => {
  const v = Number(n) || 0
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB'
  return (v / 1024 / 1024).toFixed(1) + ' MB'
}

const loadMemory = async () => {
  memLoading.value = true
  try {
    const r = await getMemoryOverview(memAgentId.value)
    memOverview.value = (r && r.data) || memOverview.value
  } catch (e) {
    message.error(e.message || '记忆加载失败')
  } finally {
    memLoading.value = false
  }
}

// 智能体下拉：只列自己的主智能体；切换即时重载（专属记忆在该智能体「记忆域」配置后才会产生内容）
const loadMemAgents = async () => {
  try {
    const r = await listAgents()
    const rows = (r && r.data) || {}
    const list = Array.isArray(rows) ? rows : (rows.list || rows.agents || [])
    memAgentOptions.value = [
      { label: '用户级记忆（全体共享）', value: null },
      ...list.filter(a => a && a.id).map(a => ({ label: a.name || a.id, value: a.id }))
    ]
  } catch { /* 下拉失败不影响用户级记忆的查看 */ }
}

// 把小节的 entries 摊平为渲染行：子题头 + 其条目（带缩进标记），阅读态与编辑态共用
const secRows = sec => {
  const rows = []
  for (const e of sec.entries || []) {
    if (e.kind === 'sub') {
      rows.push({ kind: 'sub', e })
      for (const it of e.items || []) rows.push({ kind: 'item', e: it, indent: true })
    } else {
      rows.push({ kind: 'item', e, indent: false })
    }
  }
  return rows
}

const startMemEdit = () => {
  const content = memOverview.value.content || ''
  memDoc.value = parseMemoryDoc(content)
  memEditId.value = ''
  if (content.trim() && !memDoc.value.sections.length) {
    // 没有 ## 小节结构（手写/异常内容）：结构化模式无从下手，直接进原文编辑
    memRawMode.value = true
    memEditText.value = content
  } else {
    memRawMode.value = false
  }
  memEditing.value = true
}
const cancelMemEdit = () => {
  memEditing.value = false
  memRawMode.value = false
  memEditText.value = ''
  memEditId.value = ''
}
// 原文 ↔ 可视化：双向切换时互转内容，转不过去（没有小节结构）就留在原地并提示
const toggleMemRaw = () => {
  if (memRawMode.value) {
    const doc = parseMemoryDoc(memEditText.value)
    if (memEditText.value.trim() && !doc.sections.length) {
      message.warning('当前内容没有可分的小节，已保留在原文编辑')
      return
    }
    memDoc.value = doc
    memRawMode.value = false
  } else {
    memEditText.value = serializeMemoryDoc(memDoc.value)
    memRawMode.value = true
  }
}
const saveMemory = async () => {
  const text = memRawMode.value ? memEditText.value : serializeMemoryDoc(memDoc.value)
  const maxChars = memOverview.value.maxChars || 0
  if (maxChars && text.length > maxChars) {
    message.error('内容超过上限（' + text.length + ' / ' + maxChars + ' 字符），请精简后再保存')
    return
  }
  memSaving.value = true
  try {
    const r = await saveMemoryMd(text, memAgentId.value)
    memOverview.value = (r && r.data) || memOverview.value
    memEditing.value = false
    message.success('记忆已保存')
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    memSaving.value = false
  }
}

// 条目行定位：小节内的条目挂在 sub.items 上，其余挂在 sec.entries 上
const findEntryOwner = id => {
  for (const sec of memDoc.value.sections) {
    for (const e of sec.entries) {
      if (e.id === id) return { list: sec.entries, entry: e }
      if (e.kind === 'sub') {
        const it = (e.items || []).find(x => x.id === id)
        if (it) return { list: e.items, entry: it }
      }
    }
  }
  return null
}
const startEntryEdit = entry => { memEditId.value = entry.id; memEditDraft.value = entry.text }
const commitEntryEdit = () => {
  const o = findEntryOwner(memEditId.value)
  if (o) {
    const t = (memEditDraft.value || '').replace(/[ \t]+$/g, '')
    if (t.trim()) o.entry.text = t
    else o.list.splice(o.list.indexOf(o.entry), 1) // 清空文本 = 删除该条
  }
  memEditId.value = ''
}
const removeEntryById = id => {
  const o = findEntryOwner(id)
  if (o) o.list.splice(o.list.indexOf(o.entry), 1)
}
const addEntry = sec => {
  // 插到第一个子题之前：直接条目是小节的开头部分，加到尾部会跑到子题底下
  const e = { id: nextEntryId(), kind: 'item', text: '' }
  const idx = (sec.entries || []).findIndex(x => x.kind === 'sub')
  if (idx >= 0) sec.entries.splice(idx, 0, e); else sec.entries.push(e)
  startEntryEdit(e)
}
const addSubItem = sub => {
  const it = { id: nextEntryId(), kind: 'item', text: '' }
  if (!sub.items) sub.items = []
  sub.items.push(it)
  startEntryEdit(it)
}

// 分类 / 小节：重命名（点标题）、新增（底部按钮）、删除（有条目时确认）
const startSecRename = sec => { sec._renaming = true; sec._draft = sec.title }
const commitSecRename = sec => { sec.title = (sec._draft || '').trim(); sec._renaming = false }
const startSubRename = sub => { sub._renaming = true; sub._draft = sub.title }
const commitSubRename = sub => { sub.title = (sub._draft || '').trim(); sub._renaming = false }
const addSection = () => {
  memDoc.value.sections.push({ id: nextEntryId(), title: '', entries: [], _renaming: true, _draft: '' })
}
const delSection = sec => {
  const drop = () => {
    const i = memDoc.value.sections.indexOf(sec)
    if (i >= 0) memDoc.value.sections.splice(i, 1)
  }
  if ((sec.entries || []).length) {
    Modal.confirm({
      title: '删除分类「' + (sec.title || '未命名分类') + '」？',
      content: '分类下的所有条目会一并删除。取消编辑可放弃本次全部改动。',
      okText: '删除', okType: 'danger', cancelText: '取消', onOk: drop
    })
  } else drop()
}
const delSub = sub => {
  const sec = memDoc.value.sections.find(s => (s.entries || []).includes(sub))
  const drop = () => {
    if (!sec) return
    const i = sec.entries.indexOf(sub)
    if (i >= 0) sec.entries.splice(i, 1)
  }
  if ((sub.items || []).length) {
    Modal.confirm({
      title: '删除小节「' + (sub.title || '未命名小节') + '」？',
      content: '小节下的所有条目会一并删除。取消编辑可放弃本次全部改动。',
      okText: '删除', okType: 'danger', cancelText: '取消', onOk: drop
    })
  } else drop()
}
const onCompactMemory = async () => {
  memLoading.value = true
  try {
    const r = await compactMemory(memAgentId.value)
    memOverview.value = (r && r.data) || memOverview.value
    message.success('已按上限裁剪')
  } catch (e) {
    message.error(e.message || '裁剪失败')
  } finally {
    memLoading.value = false
  }
}
const onClearMemory = () => {
  Modal.confirm({
    title: '清空全部长期记忆？',
    content: '会删除下面整理的全部记忆和所有日账，无法恢复。已经开始的问答不受影响，但之后的问答不会再记得这些内容。',
    okText: '清空', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      memClearing.value = true
      try {
        const r = await clearMyMemory(memAgentId.value)
        memOverview.value = (r && r.data) || memOverview.value
        memEditing.value = false
        message.success('已清空长期记忆')
      } catch (e) {
        message.error(e.message || '清空失败')
      } finally {
        memClearing.value = false
      }
    }
  })
}
const viewDaily = async date => {
  try {
    const r = await getMemoryContent('memory/' + date + '.md', memAgentId.value)
    memViewTitle.value = '日账 ' + date
    memViewText.value = (r && r.data && r.data.content) || ''
    memViewOpen.value = true
  } catch (e) {
    message.error(e.message || '读取日账失败')
  }
}
const onDeleteDaily = date => {
  Modal.confirm({
    title: '删除 ' + date + ' 的日账？',
    content: '只删除这一天的原始记录，不会回改 MEMORY.md；删除后无法恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteMemoryDaily(date, memAgentId.value)
        memOverview.value = (r && r.data) || memOverview.value
        message.success('日账已删除')
      } catch (e) {
        message.error(e.message || '删除失败')
      }
    }
  })
}
const downloadMemory = () => {
  const text = memOverview.value.content || ''
  if (!text) return
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'MEMORY.md'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

// ---- 分享管理（会话只读链接的统一回看与停用） ----
// 与会话内分享面板（views/shareSession.js）的关系：那边管"这一条"，这里管"全部"。
// 链接拼装复用 shareUrlOf —— 协议只有一处定义，两边各拼一次迟早漂移。
const shares = ref([])
const shareLoading = ref(false)
const shareBusy = ref('')
// 深链定位：侧栏点分享标记进来时高亮并滚到对应那一条（?sid=）
const focusSid = ref('')

const shareOnCount = computed(() => shares.value.filter(s => s.enabled).length)

const fmtTime = v => {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return ''
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const loadShares = async () => {
  shareLoading.value = true
  try {
    const r = await listMySessionShares()
    shares.value = (r && r.data) || []
    // 深链定位的行滚进视野：从侧栏标记点进来时直接看到"就是这条"
    if (focusSid.value) {
      await new Promise(resolve => setTimeout(resolve, 60))
      const el = document.querySelector('.shm-item.focus')
      if (el && el.scrollIntoView) el.scrollIntoView({ block: 'center' })
    }
  } catch (e) { message.error(e.message || '分享列表加载失败') }
  finally { shareLoading.value = false }
}

const copyShare = s => copyText(shareUrlOf(s.token), '链接已复制')
const goSharedSession = s => router.push({ path: '/chat', query: { sid: s.sessionId } })

const stopShare = s => {
  Modal.confirm({
    title: '停止分享这条链接？',
    content: '停止后所有已发出的链接立即失效，拿到链接的人会看到"链接已失效"。可以随时重新开启，但会生成新链接。',
    okText: '停止分享', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await stopShareBySession(s.sessionId)
        if (r && r.success !== false) {
          message.success('已停止分享')
          // 同步侧栏行内图标：本页是「全部」的视角，操作对象同样在侧栏列表里，
          // 不同步的话从本页停掉分享，回对话页看到的还是分享中的图标
          markSessionShared(s.sessionId, false)
          await loadShares()
        } else message.error(r?.msg || '停止失败')
      } catch (e) { message.error(e.message || '停止失败') }
    }
  })
}

/** 换新链接 / 重新开启：同一个后端动作（换新 token 使旧链接立即失效），文案按当前状态区分 */
const regenShare = s => {
  Modal.confirm({
    title: '换一个新链接？',
    content: '此前发出的所有链接会立即失效，新链接需重新分发。',
    okText: '换新链接', cancelText: '取消',
    onOk: () => restartShare(s, '已生成新链接，旧链接立即失效')
  })
}
const restartShare = async (s, okMsg = '已重新开启分享') => {
  if (shareBusy.value) return
  shareBusy.value = s.sessionId
  try {
    const r = await enableSessionShare(s.sessionId)
    if (r && r.success !== false) {
      message.success(okMsg)
      // 换新/重开后该会话的分享标记必然为真，同步侧栏行内图标
      markSessionShared(s.sessionId, true)
      await loadShares()
      focusSid.value = s.sessionId
    } else message.error(r?.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { shareBusy.value = '' }
}

/** 清除记录：物理删除一条已停止的分享记录（与"停止分享"不同，那个只让链接失效） */
const purgeRecord = s => {
  Modal.confirm({
    title: '清除这条分享记录？',
    content: '记录会从分享管理里移除，访问次数等统计一并消失，无法恢复。会话本身不受影响。' +
      (s.orphaned ? '（该会话已删除，此记录已无用途。）' : ''),
    okText: '清除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await purgeShareRecord(s.sessionId)
        if (r && r.success !== false) { message.success('记录已清除'); await loadShares() }
        else message.error(r?.msg || '清除失败')
      } catch (e) { message.error(e.message || '清除失败') }
    }
  })
}

// 面板切换时才拉列表：其余面板不碰这个接口（个人设置是常驻页，每次进都拉会白跑一次）
// 记忆同理——自动提炼是在后台往库里加条目的，切回面板必须重拉，否则看到的是进页那一刻的旧快照
// 「自动提炼记忆」开关也一起重拉：它过去只在进页时读过一次，值在别处变过（另一个页签、探针改完还原、
// 请求超时但其实已落库）就永远显示旧值，看着像「它自己关了」
watch(current, v => {
  if (v === 'shares') {
    focusSid.value = route.query.sid || ''
    loadShares()
  }
  if (v === 'memory') { loadMemory(); loadMemAgents(); load() }
})

onMounted(() => {
  load()
  ensureAuth().then(me => {
    // 身份返回前用户可能已经开始输入：此时保留输入框内容，只把服务端值记为「已保存值」
    if (!nickForm.value.username) nickForm.value.username = me.username || ''
    originalNickname.value = me.username || ''
    avatarPreview.value = me.avatar || ''
  })
  // 深链直达（?panel=xxx）时 watch 不会触发，首拉在这里补齐
  if (current.value === 'shares') loadShares()
  if (current.value === 'memory') loadMemory()
  if (current.value === 'chat') loadPersonal()
  // 通知这一屏的两个数据源都静默拉：静音清单是本页主内容（失败要报），
  // 推送能力只是环境判定（拿不到就当「还不确定」，不打扰）
  loadMutes()
  refreshPushState().then(refreshCap).catch(() => {})
})
// 从别处回到这一屏（切 Tab、通知中心点「通知与推送设置」跳来）重新判一次能力：
// 用户可能刚在浏览器站点设置里把通知改回允许，不重判就会一直显示旧结论
watch(current, v => { if (v === 'notify') refreshPushState().then(refreshCap).catch(() => {}) })
</script>

<style scoped>
/* ---- 通知方式：两行卡片（系统级推送 / 邮件），状态徽标 + 一句副文案 + 行尾操作 ---- */
.np-ways { margin-top: 12px; border: 1px solid var(--app-border); border-radius: 10px; overflow: hidden; }
.np-way { display: flex; align-items: center; gap: 12px; padding: 10px 12px; }
.np-way + .np-way { border-top: 1px solid var(--app-border); }
.np-way-ic {
  width: 30px; height: 30px; flex: none; font-size: 14px;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--app-border); border-radius: 9px;
  background: var(--app-panel-2); color: var(--app-text2);
}
.np-way-main { min-width: 0; flex: 1; }
.np-way-name { display: flex; align-items: center; gap: 7px; font-size: 13px; font-weight: 600; color: var(--app-text); }
.np-way-sub { margin-top: 3px; font-size: 11px; line-height: 1.55; color: var(--app-text3); }
/* 状态徽标：开启用绿，未开启保持弱灰（红色会让「没开推送」看着像故障） */
.np-badge {
  flex: none; font-size: 10px; font-weight: 400; line-height: 1; padding: 3px 6px;
  border: 1px solid var(--app-border); border-radius: 999px;
  background: var(--app-panel-2); color: var(--app-text3); white-space: nowrap;
}
.np-badge.on { background: var(--app-ok-weak); border-color: #cfe9db; color: #1a7f4b; }
.np-way-acts { flex: none; display: flex; align-items: center; gap: 8px; }
.np-way-acts :deep(.ant-input) { width: 200px; }

/* 收哪些类：开关 + 图标 + 标签成行，行尾是可选的邮件开关；窄屏自动换列 */
.np-groups { display: flex; flex-direction: column; gap: 10px; margin-top: 10px; }
.np-group { border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.np-group-head { display: flex; align-items: center; gap: 8px; padding: 7px 10px; background: var(--app-panel-2); }
/* 分组的语义色小圆点：待处理 / 异常 / 完成一眼分得开，标题本身不必染色 */
.np-dot { width: 6px; height: 6px; border-radius: 50%; flex: none; background: var(--app-text3); }
.np-dot.tone-warn { background: var(--app-warn); }
.np-dot.tone-err { background: var(--app-danger); }
.np-dot.tone-ok { background: var(--app-ok); }
.np-group-title { font-size: 12px; font-weight: 600; color: var(--app-text); }
.np-group-count { font-size: 11px; color: var(--app-text3); }
.np-collapsed-tag { font-size: 11px; color: var(--app-text3); }
.np-group-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.np-group .np-grid { padding: 10px; }
.np-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px 16px; }
.np-item { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--app-text); min-width: 0; }
/* 已静音整行压暗：静音是「我不想收到」，行内控件同步变弱比只看开关更直观 */
.np-item.muted .np-item-label, .np-item.muted .np-ic { opacity: .45; }
/* 标签单行截断：长标签挤走行尾邮件开关会让两列参差不齐 */
.np-item-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.np-field-main { min-width: 0; }
.np-field-main .pf-sub-hint { margin: 2px 0 0; }
.np-head-acts { flex: none; display: inline-flex; align-items: center; gap: 10px; }
/* 「只看已静音」筛选芯片：与链接按钮同排，做成可勾选的胶囊以示「这是一个筛选」 */
.np-chip {
  display: inline-flex; align-items: center; gap: 5px; height: 24px; padding: 0 9px;
  border: 1px solid var(--app-border); border-radius: 999px; background: transparent;
  font: inherit; font-size: 11px; color: var(--app-text2); cursor: pointer;
}
.np-chip:hover { border-color: var(--app-accent-border); color: var(--app-text); }
.np-chip.on { border-color: var(--app-accent-border); background: var(--app-accent-weak); color: var(--app-text); }
.np-chip-box {
  width: 12px; height: 12px; flex: none; position: relative;
  border: 1px solid var(--app-border-strong); border-radius: 3px; background: var(--app-panel);
}
.np-chip.on .np-chip-box { background: var(--app-accent); border-color: var(--app-accent); }
.np-chip.on .np-chip-box::after {
  content: '✓'; position: absolute; inset: 0; color: #fff; font-size: 9px; line-height: 12px; text-align: center;
}
/* 邮件开关：行尾信封图标按钮。与站内开关同形会分不清谁是谁；
   开启态只着色不放大，避免整排出现第二列「主开关」的视觉噪音 */
.np-mail-wrap { margin-left: auto; flex: none; display: inline-flex; align-items: center; justify-content: flex-end; }
.np-mail {
  width: 22px; height: 22px; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid transparent; border-radius: 6px; background: transparent;
  color: var(--app-text3); font-size: 14px; cursor: pointer;
  transition: background .15s, color .15s, border-color .15s;
}
.np-mail:hover { background: var(--app-accent-weak); color: var(--app-text); }
.np-mail.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); }
.np-mail:focus-visible { outline: 2px solid var(--app-accent-border); outline-offset: 1px; }
/* 锁定（已静音 / 未填邮箱）：只压暗，不隐藏——让用户看得到有这一项以及为什么用不了 */
.np-mail.off { opacity: .3; cursor: not-allowed; }
.np-mail.off:hover { background: transparent; color: var(--app-text3); }
.np-ic { font-size: 15px; flex: none; }
.np-ic.ok { color: var(--app-ok); }
.np-ic.err { color: var(--app-danger); }
.np-ic.warn { color: var(--app-warn); }
.np-foot-note { margin: 16px 0 0; padding-top: 12px; border-top: 1px solid var(--app-border); font-size: 11px; line-height: 1.6; color: var(--app-text3); }

.pf-body { flex: 1; min-height: 0; display: flex; }
.pf-page-head { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.pf-page-sub { margin: 0; font-size: 12px; color: var(--app-text3); }
.pf-nav {
  width: 168px; flex: none; border-right: 1px solid var(--app-border); background: var(--app-panel);
  padding: 12px 10px; display: flex; flex-direction: column; gap: 14px; overflow-y: auto;
}
.pf-nav-group { display: flex; flex-direction: column; gap: 2px; }
/* 分组小标题：只做扫读锚点，弱色调、不可点击 */
.pf-nav-group-title {
  padding: 0 10px 5px; font-size: 11px; color: var(--app-text3);
  letter-spacing: .02em; user-select: none;
}
.pf-nav-item {
  position: relative; display: flex; align-items: center; gap: 8px;
  padding: 7px 10px 7px 12px; border-radius: 8px; font-size: 13px;
  color: var(--app-text2); cursor: pointer; border: none; background: transparent; text-align: left;
  transition: background .15s, color .15s;
}
.pf-nav-item:hover { background: var(--app-accent-weak); color: var(--app-text); }
.pf-nav-item.active { background: var(--app-accent-weak); color: var(--app-text); font-weight: 500; }
/* 选中项左侧短竖条：只靠浅底在长列表里不够显眼 */
.pf-nav-item.active::before {
  content: ''; position: absolute; left: 0; top: 7px; bottom: 7px; width: 3px;
  border-radius: 0 3px 3px 0; background: var(--app-accent);
}
.pf-nav-icon { flex: none; font-size: 14px; color: var(--app-text3); transition: color .15s; }
.pf-nav-item:hover .pf-nav-icon, .pf-nav-item.active .pf-nav-icon { color: var(--app-accent); }
.pf-nav-text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pf-content { flex: 1; min-width: 0; overflow-y: auto; padding: 14px 20px 24px; }
.pf-card { max-width: 640px; padding: 18px 20px; }
/* API 密钥面板：表格比其它个人设置卡宽，不限 640 上限（内边距与 pf-card 一致）。
   8 列定宽合计 948 + 名称列，内容框要 1060 才不用横向滚动 */
.akp-card { max-width: 1240px; padding: 18px 20px; }
.pf-panel-head { margin-bottom: 16px; }
.pf-panel-head .app-card-title { margin-bottom: 3px; }
.pf-panel-sub { margin: 0; font-size: 12px; color: var(--app-text3); line-height: 1.6; }
.pf-sub-hint { font-size: 11px; color: var(--app-text3); margin: 10px 0 0; }
.pf-row { display: flex; align-items: center; gap: 10px; }
/* 即时保存的进行中提示：选择器在 PUT 期间只是变灰，不写一句看不出「在等什么」 */
.pf-saving-tip { margin: 0; white-space: nowrap; }
/* 昵称输入框：PC 定宽 280（与保存按钮并排）；窄屏改全宽（见下方 768 块） */
.pf-nick-input { width: 280px; }
/* 表单最大宽度：PC 上限避免长行难读；窄屏取消（见下方 768 块） */
.pf-form { max-width: 360px; }
.pf-err { color: var(--app-danger); font-size: 12px; margin: 0 0 8px; }

/* 头像区：左预览（可直接点击换图）+ 右侧身份、按钮与说明 */
.pf-identity { display: flex; align-items: flex-start; gap: 18px; padding-bottom: 18px; }
.pf-avatar-btn {
  position: relative; flex: none; padding: 0; border: none; border-radius: 50%;
  background: transparent; cursor: pointer; line-height: 0;
}
.pf-avatar-btn:disabled { cursor: default; }
/* 悬浮遮罩：提示头像是可点击的换图入口，同时不改变圆形裁切 */
.pf-avatar-mask {
  position: absolute; inset: 0; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, .4); color: #fff; font-size: 20px;
  opacity: 0; transition: opacity .15s;
}
.pf-avatar-btn:hover .pf-avatar-mask,
.pf-avatar-btn:focus-visible .pf-avatar-mask { opacity: 1; }
.pf-identity-main { min-width: 0; display: flex; flex-direction: column; gap: 8px; padding-top: 2px; }
.pf-identity-name {
  font-size: 15px; font-weight: 600; line-height: 1.2; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pf-identity-state { font-size: 12px; color: var(--app-text3); }
.pf-identity-main .pf-sub-hint { margin: 0; }
.pf-op-row { display: flex; align-items: center; gap: 8px; }
/* ghost 按钮 disabled 时全局样式不降透明度，这里补上，避免「恢复昵称首字」看着仍可点 */
.pf-identity-main .app-btn.ghost:disabled { opacity: .45; color: var(--app-text3); }

/* 分区：图标 / 昵称 / 账号信息各自成块，用分隔线分层级 */
.pf-section { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--app-border); }
.pf-section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.pf-section-head .pf-sub-hint { margin: 2px 0 0; }
.pf-label { font-size: 12px; font-weight: 500; color: var(--app-text); }
.pf-count { flex: none; font-size: 11px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.pf-meta-right { flex: none; display: inline-flex; align-items: center; gap: 8px; }
.pf-dirty {
  font-size: 11px; line-height: 1; color: var(--app-warn-text); background: var(--app-warn-weak);
  border: 1px solid var(--app-warn-border); border-radius: 4px; padding: 3px 6px;
}
.pf-account-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.pf-account-value {
  min-width: 0; max-width: 100%;
  font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 12px;
  color: var(--app-text2); background: var(--app-panel-2);
  border: 1px solid var(--app-border); border-radius: 4px; padding: 2px 7px;
  overflow-wrap: anywhere;
}
.pf-account-row .pf-sub-hint { margin: 0; }

/* 分区间距（通知面板沿用旧类名） */
.pf-field { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--app-border); }
.pf-field-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.pf-field-head .pf-sub-hint { margin: 0; }
/* 个人可覆盖字段的问号说明（与设置页同一份 tips 文案） */
.pf-tip-icon { font-size: 12px; color: var(--app-text3); cursor: help; }

/* ---- 问答偏好：按用途分块（默认模型 / 检索 / 联网搜索），块内一行一项，块间用分隔线 ---- */
.pf-grp + .pf-grp { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--app-border); }
.pf-grp-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.pf-grp-head-main { min-width: 0; }
.pf-grp-title { margin: 0; font-size: 13px; font-weight: 600; color: var(--app-text); display: flex; align-items: center; gap: 6px; }
.pf-grp-hint { margin: 3px 0 0; font-size: 11px; line-height: 1.6; color: var(--app-text3); }
.pf-grp-body { display: flex; flex-direction: column; gap: 12px; }
.pf-grp-foot { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.pf-grp-foot .pf-grp-hint { margin: 0; }
.pf-fld { min-width: 0; }
.pf-fld-head { display: flex; align-items: center; gap: 5px; margin-bottom: 5px; }
.pf-fld-note { margin: 5px 0 0; font-size: 11px; line-height: 1.55; color: var(--app-text3); }
/* 高级配置：两列排（窄屏回落单列），长字段（地址/请求体）吃满整行 */
.pf-fld-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 16px; }
.pf-ctl { max-width: 100%; }
.pf-ctl-sm { width: 160px; }
.pf-ctl-block { width: 100%; }
.pf-secret.is-masked :deep(input) { -webkit-text-security: disc; }
.pf-secret-eye { display: inline-flex; color: var(--app-text3); cursor: pointer; }
.pf-secret-eye:hover { color: var(--app-text2); }
/* ---- 联网搜索：服务商预设卡片（普通用户只选哪家，地址/模板/取数路径由预设填好） ---- */
.pf-ws-presets { display: grid; grid-template-columns: repeat(auto-fit, minmax(104px, 1fr)); gap: 8px; }
.pf-ws-preset {
  position: relative; min-width: 0; padding: 10px 9px 9px; text-align: left; font: inherit; color: inherit;
  border: 1px solid var(--app-border); border-radius: 9px; background: var(--app-panel);
  cursor: pointer; -webkit-appearance: none; appearance: none;
  transition: border-color .15s, background .15s, box-shadow .15s;
}
.pf-ws-preset:hover { border-color: var(--app-accent-border); background: var(--app-info-weak); }
.pf-ws-preset.on { border-color: var(--app-accent); background: var(--app-info-weak); box-shadow: inset 0 0 0 1px var(--app-accent); }
.pf-ws-ico {
  display: flex; width: 22px; height: 22px; margin-bottom: 7px; border-radius: 6px;
  align-items: center; justify-content: center; color: #fff; background: #8a94a3;
  font-size: 11px; font-weight: 700; line-height: 1;
}
.ws-ico-deepseek { background: #4d6bfe; }
.ws-ico-tavily { background: #1f2329; }
.ws-ico-bocha { background: #2f7dff; }
.ws-ico-custom { background: #5f6570; font-size: 9px; }
.pf-ws-name { display: block; font-size: 12px; font-weight: 600; line-height: 1.3; }
.pf-ws-desc { display: block; margin-top: 2px; font-size: 10.5px; line-height: 1.4; color: var(--app-text3); }
.pf-ws-check {
  position: absolute; top: 7px; right: 7px; width: 14px; height: 14px; border-radius: 50%;
  background: var(--app-accent); color: #fff; font-size: 9px; line-height: 1;
  display: flex; align-items: center; justify-content: center;
}
/* 状态行：当前哪家 · Key 是否已配 · 每次几条，替代逐个字段猜 */
.pf-ws-status {
  display: flex; align-items: center; gap: 7px; flex-wrap: wrap; padding: 7px 10px;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  font-size: 11px; color: var(--app-text2);
}
.pf-ws-status b { color: var(--app-text); font-weight: 600; }
.pf-ws-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--app-ok); flex: none; }
.pf-ws-sep { color: var(--app-border-strong); }
/* 高级配置收起时给一行摘要：不用展开也知道现在按什么在跑 */
.pf-ws-adv-summary {
  margin: 0; font-size: 10.5px; line-height: 1.6; color: var(--app-text3);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; word-break: break-all;
}
/* 请求体模板这类长文本在高级区独占一行 */
.pf-fld-wide { grid-column: 1 / -1; }
/* 状态胶囊：已设置 / 已自定义 */
.pf-set-badge {
  flex: none; font-size: 10px; line-height: 1; color: var(--app-text3);
  background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 999px; padding: 2px 6px;
}
/* 探测结果：成功为绿，失败沿用弱灰（错误原因本身已经说明了问题，不必整行飘红） */
.pf-probe-hint { font-size: 11px; color: var(--app-text3); }
.pf-probe-hint.ok { color: var(--app-ok, #2e7d32); }
/* 无「保存」按钮的即时保存说明：放在面板头右侧，替用户回答「改完要不要点保存」 */
.pf-pref-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.pf-autosave { flex: none; font-size: 11px; color: var(--app-text3); white-space: nowrap; }

/* 图标集：10 列定宽网格（30 个正好三行、不留残行）；去掉逐格边框与白底，只靠 hover / 选中环表达状态 */
.pf-emoji-pick { display: grid; grid-template-columns: repeat(10, minmax(0, 1fr)); gap: 6px; }
.pf-emoji-opt {
  aspect-ratio: 1 / 1; min-width: 0; padding: 0; font-size: 20px; line-height: 1;
  border: none; border-radius: 9px; background: transparent; cursor: pointer; color: inherit;
  display: inline-flex; align-items: center; justify-content: center;
  transition: background .15s, box-shadow .15s, transform .12s;
}
.pf-emoji-opt:hover { background: var(--app-accent-weak); }
.pf-emoji-opt:active { transform: scale(.92); }
.pf-emoji-opt.on { background: var(--app-accent-weak); box-shadow: inset 0 0 0 1.5px var(--app-accent); }
.pf-emoji-opt:disabled { cursor: not-allowed; opacity: .5; }
.pf-emoji-opt.on:disabled { opacity: 1; }
/* 长期记忆：卡片比其他面板略宽（长句记忆要完整换行展示，640 略挤） */
.mem-card { max-width: 720px; }
/* 标题旁条数胶囊 */
.mem-count { font-size: 11px; font-weight: 400; line-height: 1; color: var(--app-text3); background: var(--app-panel-2); padding: 3px 8px; border-radius: 999px; }
/* 危险动作（清除全部 / 行内删除） */
.mem-danger { color: var(--app-danger); }
.mem-danger:hover:not(:disabled) { background: var(--app-danger-weak); }

/* ---- 分享管理 ---- */
.shm-card { max-width: 760px; }
.shm-list { display: flex; flex-direction: column; gap: 8px; }
.shm-item {
  border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px;
  display: flex; align-items: flex-start; gap: 12px;
}
/* 已停止：整条压暗，让"生效中"的几条在长列表里一眼可辨（这是本页要回答的首要问题） */
.shm-item.off { opacity: .68; }
/* 深链定位：从侧栏分享标记点进来时短暂标一下"就是这条" */
.shm-item.focus { border-color: var(--app-accent); background: var(--app-accent-weak); }
.shm-main { flex: 1; min-width: 0; }
.shm-title {
  font-size: 13px; color: var(--app-text); font-weight: 500;
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
.shm-tag {
  font-size: 11px; line-height: 1; padding: 3px 6px; border-radius: 4px;
  background: var(--app-panel-2); color: var(--app-text3); font-weight: 400; white-space: nowrap;
}
.shm-tag.on { background: #e8f5ee; color: #1a7f4b; }
.shm-tag.warn { background: #fdeceb; color: #b0322f; }
.shm-meta { font-size: 11px; color: var(--app-text3); margin-top: 4px; }
.shm-link { margin-top: 6px; }
/* 链接单行截断：URL 很长，全量换行会把这一行撑得很高 */
.shm-url {
  display: block; font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 11px;
  color: var(--app-text3); background: var(--app-bg); border: 1px solid var(--app-border);
  border-radius: 4px; padding: 4px 6px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.shm-note { font-size: 11px; color: var(--app-text3); margin-top: 6px; }
.shm-acts {
  flex: none; display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  justify-content: flex-end; max-width: 320px;
}
.shm-off-note { font-size: 11px; color: var(--app-text3); }
.mem-meta { flex: none; font-size: 11px; color: var(--app-text3); }
/* 编辑态：文本域 + 底部按钮行（计数靠左、按钮靠右，互不重叠） */
.mem-edit-foot { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 8px; }
.mem-edit-count { margin-right: auto; font-size: 11px; color: var(--app-text3); }
/* 空态 */
.mem-empty { text-align: center; padding: 26px 0 18px; }
.mem-empty-ico { font-size: 26px; line-height: 1; }
.mem-empty-title { font-size: 13px; color: var(--app-text2); margin: 8px 0 2px; }
.mem-empty .pf-sub-hint { margin: 0; }
/* 自动提炼开关行：左说明右开关（位于「我的记忆」面板顶部） */
.mem-auto-row { padding: 10px 12px; margin-bottom: 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); }
.mem-auto-text { flex: 1; min-width: 0; }
.mem-auto-title { font-size: 13px; color: var(--app-text); font-weight: 500; margin-bottom: 2px; }
.mem-auto-row .pf-sub-hint { margin: 0; }

/* ---- 我的记忆 ---- */
.mem-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.mem-warn {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-bottom: 12px; padding: 9px 12px; border-radius: 8px;
  background: var(--app-warn-weak); color: var(--app-warn-text);
  border: 1px solid var(--app-warn-border); font-size: 12px; line-height: 1.5;
}
.mem-warn .app-link-btn { color: var(--app-warn-text); text-decoration: underline; }
.mem-md {
  margin: 0; padding: 12px 14px; max-height: 360px; overflow: auto;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 12px; line-height: 1.65;
  color: var(--app-text); white-space: pre-wrap; word-break: break-word;
}
.mem-edit-area { width: 100%; font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 12px; }
/* 阅读态：结构化呈现（分类 + 条目，不要求用户懂 Markdown） */
.mem-view {
  padding: 12px 14px; max-height: 480px; overflow: auto;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
}
.mem-view-empty { margin: 0; font-size: 12px; color: var(--app-text3); }
.mem-sec + .mem-sec { margin-top: 14px; }
.mem-sec-title { font-size: 13px; font-weight: 600; color: var(--app-text); }
.mem-sub-title { margin: 10px 0 2px; font-size: 12px; font-weight: 500; color: var(--app-text2); }
.mem-line { display: flex; align-items: flex-start; gap: 8px; padding: 3px 0; font-size: 12px; line-height: 1.65; color: var(--app-text); }
.mem-line.indent { padding-left: 16px; }
.mem-dot { flex: none; color: var(--app-text3); }
.mem-line-text { min-width: 0; white-space: pre-wrap; word-break: break-word; }
/* 原文段（非 '- ' 行，如裁剪说明）：等宽 + 弱化，表明不是普通条目 */
.mem-line.raw .mem-line-text { font-family: var(--app-font-mono, ui-monospace, monospace); color: var(--app-text2); }
/* 编辑态：结构化编辑 */
.mem-edit { padding: 4px 14px 12px; border: 1px solid var(--app-border); border-radius: 8px; }
.mem-edit .mem-sec { margin-top: 12px; }
.mem-edit .mem-sec:first-child { margin-top: 8px; }
.mem-edit-hint { margin: 0 0 8px; }
.mem-sec-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mem-sec-head .mem-sec-title { cursor: pointer; border-radius: 4px; }
.mem-sec-head .mem-sec-title:hover { color: var(--app-accent); }
.mem-sec-input { max-width: 280px; }
.mem-sec-acts { margin-left: auto; display: inline-flex; align-items: center; gap: 10px; white-space: nowrap; }
.mem-edit .mem-line { padding: 2px 0; }
.mem-edit .mem-line-text { cursor: pointer; border-radius: 4px; }
.mem-edit .mem-line-text:hover { background: var(--app-accent-weak); }
/* 行内动作：默认隐藏，hover 到该行或正在编辑时才出现（触屏无 hover，见 768 块常显） */
.mem-line-acts { margin-left: auto; display: inline-flex; align-items: center; gap: 10px; white-space: nowrap; opacity: 0; transition: opacity .15s; }
.mem-line:hover .mem-line-acts, .mem-line.editing .mem-line-acts { opacity: 1; }
.mem-line-input { flex: 1; min-width: 0; }
.mem-add-sec { margin-top: 12px; }
.mem-edit-count.over { color: var(--app-danger); }
.mem-days { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--app-border); }
.mem-days-head { font-size: 12px; font-weight: 500; color: var(--app-text); margin-bottom: 6px; }
.mem-day-row {
  display: flex; align-items: center; gap: 10px; padding: 7px 0;
  border-bottom: 1px dashed var(--app-border);
}
.mem-day-row:last-child { border-bottom: none; }
.mem-day-date { font-size: 12px; color: var(--app-text2); font-family: var(--app-font-mono, ui-monospace, monospace); }
.mem-day-acts { margin-left: auto; display: inline-flex; align-items: center; gap: 8px; }

/* ==================== 窄屏（手机）适配 ====================
   这一页是手机上的白名单页（改昵称/头像/密码是刚需），窄屏必须真能用：
   左列 Tab 竖排 + 定宽卡片 + 并排表单在 412px 上会把内容挤到破版。
   这里把结构改成移动端设置页的常规形态：顶部横滑 Tab + 全宽卡片 + 换行表单。 */
@media (max-width: 768px) {
  /* 页头大标题与移动顶栏重复（顶栏已随路由显示「个人设置」），隐藏省一截首屏；
     scoped 只作用于本组件，不影响 /help 等同样用 .app-page-head 的页面 */
  .app-page-head { display: none; }

  /* Tab：左列竖排 → 顶部横滑胶囊（竖排会吃掉 1/3 宽度）。
     分组容器改成 contents，让各分组下的按钮直接参与横向排列；分组小标题在手机上隐藏 */
  .pf-body { flex-direction: column; }
  .pf-nav {
    width: auto; flex-direction: row; gap: 6px;
    border-right: none; border-bottom: 1px solid var(--app-border);
    padding: 8px 10px; overflow-x: auto; overflow-y: hidden;
    scrollbar-width: none; -webkit-overflow-scrolling: touch;
  }
  .pf-nav::-webkit-scrollbar { display: none; }
  .pf-nav-group { display: contents; }
  .pf-nav-group-title { display: none; }
  .pf-nav-item {
    flex: none; white-space: nowrap; min-height: 36px; padding: 8px 14px;
    border: 1px solid var(--app-border); border-radius: 999px; font-size: 13px;
    display: inline-flex; align-items: center; touch-action: manipulation;
  }
  .pf-nav-item::before { display: none; }
  .pf-nav-item.active { border-color: var(--app-accent-border); color: var(--app-accent); }

  /* 内容区与卡片：全宽（640/720 上限在 412px 上只会让右侧留白不齐） */
  .pf-content { padding: 12px 12px calc(24px + var(--sab, 0px)); }
  .pf-card, .mem-card, .shm-card { max-width: none; padding: 14px; }
  .akp-card { max-width: none; padding: 14px; }
  /* 高级配置的单行点路径在窄屏两列会截断，回落单列 */
  .pf-fld-grid { grid-template-columns: 1fr; }

  /* 表单行换行：昵称输入 + 保存按钮并排会挤压（保存两字被压成竖排） */
  .pf-row { flex-wrap: wrap; }
  .pf-nick-input { width: 100%; }
  .pf-form { max-width: none; }

  /* 头像区纵向居中：84px 头像 + 按钮行 + 说明横向放不下（「恢复昵称首字」会被挤出视口） */
  .pf-identity { flex-direction: column; align-items: center; gap: 12px; }
  .pf-identity-main { align-items: center; width: 100%; text-align: center; padding-top: 0; }
  .pf-op-row { flex-wrap: wrap; justify-content: center; }
  .pf-identity-main .pf-sub-hint { text-align: center; }
  .pf-section-head { align-items: flex-start; }

  /* emoji 网格 8 列：10 列时每格约 35px，手指容易点偏 */
  .pf-emoji-pick { grid-template-columns: repeat(8, minmax(0, 1fr)); gap: 4px; }
  .pf-emoji-opt { font-size: 20px; }

  /* 触控热区下限 */
  .pf-nav-item, .pf-emoji-opt, .app-btn { min-height: 34px; }
  /* 通知开关是「小号」控件：44px 热区把 16px 高的开关撑成一块蓝色色块。
     热区交给整行（.np-item 撑高），开关本体保持原尺寸 */
  .np-item { min-height: 40px; }
  .np-item :deep(.ant-switch) { min-width: 0; min-height: 0; }
  .np-mail-wrap { min-width: 34px; min-height: 34px; }
  /* 通知方式：窄屏两行堆叠，操作（按钮 / 邮箱输入）换到第二行 */
  .np-way { flex-wrap: wrap; }
  .np-way-acts { width: 100%; }
  .np-way-acts :deep(.ant-input) { flex: 1 1 160px; width: auto; min-width: 0; }
  .np-way-acts .app-btn { white-space: nowrap; }
  /* 列表头：说明与筛选芯片在窄屏各占一行（并排会把说明挤成三行） */
  .pf-field-head { flex-wrap: wrap; }
  .np-head-acts { width: 100%; flex-wrap: wrap; row-gap: 6px; }
  /* 记忆页的添加行：输入框占满整行（原 min-width 200 与分类框并排会溢出） */
  .mem-add-row { gap: 8px; }
  .mem-add-input { min-width: 100%; }
  .mem-add-cat { flex: 1; width: auto; }
  /* 分享管理：一行两栏（左正文 + 右操作）在手机上必然破版——操作区是 flex:none
     且按内容定宽（五个链接钮约 300px），336px 的行里正文只剩 2px，
     窄屏改成纵向：正文占满整行，操作另起一行左对齐。 */
  .shm-item { flex-direction: column; align-items: stretch; gap: 8px; }
  .shm-acts { max-width: none; justify-content: flex-start; row-gap: 2px; column-gap: 14px;
    border-top: 1px dashed var(--app-border); padding-top: 6px; }
  /* 链接式小按钮原本 18px 高，手指点不准（本页其它可点元素已统一 34px 下限） */
  .shm-acts .app-link-btn { min-height: 34px; padding: 6px 2px; }
  /* 我的记忆：按钮行换行，统计信息独占一行 */
  .mem-toolbar { gap: 6px; }
  .mem-toolbar .mem-meta { width: 100%; }
  .mem-md { max-height: 300px; }
  /* 触屏没有 hover：结构化编辑的行内「编辑/删除」常显 */
  .mem-line-acts { opacity: 1; }
}

/* H5 P2：≤1024 触屏/窄桌面统一主操作热区 44px */
@media (max-width: 1024px) {
  button, .pf-nav-item, .pf-emoji-opt, .app-btn, .app-link-btn { min-width: 44px; min-height: 44px; }
}
</style>
