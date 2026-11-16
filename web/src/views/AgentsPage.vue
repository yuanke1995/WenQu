<template>
  <div class="app-page">
    <!-- ==================== 列表视图 ==================== -->
    <template v-if="!editing">
      <div class="app-page-head">
        <!-- 页名与 Tab 名重复，标题仅保留给读屏器（sr-only），视觉上从筛选起头。
             类型分段器取代「共 N 个」纯计数：计数内嵌在分段里，筛选与清点同一动作完成 -->
        <h1 class="app-page-title sr-only">智能体</h1>
        <div class="ap-filter" role="group" aria-label="按类型筛选">
          <button v-for="f in FILTERS" :key="f.key" type="button" :class="{ on: filter === f.key }"
                  @click="filter = f.key">{{ f.label }} {{ typeCounts[f.key] }}</button>
        </div>
        <a-select v-model:value="sortBy" size="small" class="ap-sort" aria-label="排序方式"
                  :options="[{ value: 'updated', label: '按更新时间' }, { value: 'name', label: '按名称' }]" />
        <div class="ap-head-r">
          <a-input v-model:value="keyword" class="ap-search" size="small" allow-clear placeholder="搜索名称、描述或能力">
            <template #prefix><search-outlined class="ap-search-ic" /></template>
          </a-input>
          <a-tooltip title="刷新">
            <button class="app-icon-btn" :disabled="loading" aria-label="刷新列表" @click="reload"><reload-outlined /></button>
          </a-tooltip>
          <a-tooltip title="委派编排视图：主智能体 → 子智能体的委派关系图">
            <button class="app-icon-btn" :disabled="loading || !agents.length" aria-label="委派编排视图" @click="openTopo()"><apartment-outlined /></button>
          </a-tooltip>
          <button class="app-btn small" @click="openCreateEntry">新建智能体</button>
          <!-- 批量区（分隔线独立成区，不与搜索/新建挤作一堆）。默认收起，点「批量管理」进入批量模式；
               开关放最右：进出模式自身位置不动；内置智能体不可删，不进勾选范围 -->
          <div v-if="agents.length" class="batch-group">
            <template v-if="batchMode">
              <a-checkbox :checked="allChecked" :indeterminate="someChecked" @change="toggleAll">全选</a-checkbox>
              <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchExport">导出</button>
              <button class="app-btn ghost small batch-del" :disabled="!selected.length || batchBusy" @click="doBatchDelete">删除</button>
            </template>
            <button class="app-btn ghost small" :class="{ 'batch-on': batchMode }" @click="toggleBatchMode">{{ batchMode ? '退出管理' : '批量管理' }}</button>
          </div>
        </div>
      </div>

      <div class="app-page-body">
        <!-- 加载态用骨架屏占住卡片网格：整页 spinner 会让列表在慢网络下「闪成空白」，
             骨架保持了版式，用户视线不用重新找位置 -->
        <div v-if="loading" class="ap-grid" aria-busy="true" aria-label="智能体列表加载中">
          <div v-for="i in 6" :key="'sk' + i" class="ap-card ap-skel">
            <a-skeleton active :avatar="{ shape: 'square', size: 32 }" :paragraph="{ rows: 2 }" :title="false" />
          </div>
        </div>

        <div v-else-if="!agents.length" class="ap-empty">
          <div class="ap-empty-t">还没有智能体</div>
          <div class="ap-empty-d">
            一个智能体就是一组预设：写好角色提示词、圈定知识库范围、按需开启能力；模型在对话页选择。
            对话时在输入框上方切换，这一轮问答就按它的配置走；没配置的维度沿用系统设置。
          </div>
          <button class="app-btn small" @click="openCreateEntry">新建第一个智能体</button>
        </div>

        <div v-else-if="!sections.length" class="ap-empty">
          <div class="ap-empty-t">没有匹配的智能体</div>
          <div class="ap-empty-d">没有名称或描述包含「{{ keyword }}」的智能体。</div>
          <button class="app-btn ghost small" @click="keyword = ''">清除搜索</button>
        </div>

        <!-- 子智能体不再与主智能体混排：主智能体一组，子智能体单独一组 -->
        <div v-else class="ap-groups">
          <section v-for="sec in sections" :key="sec.key" class="ap-group">
            <!-- 只剩主智能体一组时不显示组头 -->
            <div v-if="sections.length > 1 || sec.key !== 'main'" class="ap-group-head">
              <span class="ap-group-title">{{ sec.title }}</span>
              <span v-if="sec.hint" class="ap-group-hint">{{ sec.hint }}</span>
              <span class="ap-group-count">{{ sec.members.length }}</span>
            </div>
            <div class="ap-grid">
              <!-- 卡片点击直达配置：仅可管理的智能体（不可管理时点卡片不做任何事，
                   否则弹出的编辑框保存必被后端拒绝，是"点了报错"的死路）。
                   信息架构：徽章与状态点说身份/发布态 → 一行描述 → meta 行说「资料/能力/委派/更新」
                   → 操作收敛为 试一试 + 配置 + ⋯ 菜单（低频与破坏性动作进菜单） -->
              <article v-for="a in sec.members" :key="sec.key + '-' + a.id" class="ap-card"
                       :class="{ 'ap-card-readonly': !a.manageable, 'ap-card-disabled': isDisabledAgent(a) }"
                       @click="a.manageable && openEdit(a)">
                <!-- 状态点：绿=公开链接已发布，灰=未发布。颜色之外配 title 文字，不单靠颜色编码 -->
                <span v-if="showPubDot(a)" class="ap-dot" :class="a.published === 1 ? 'pub' : 'off'"
                      :title="a.published === 1 ? '公开链接已发布：拿到链接的人可免登录对话，点 ⋯ → 发布管理 查看链接' : '公开链接未发布'"></span>
                <div class="ap-card-head">
                  <a-checkbox v-if="batchMode && !isBuiltin(a) && !isPreset(a)" class="ap-check" :checked="selected.includes(a.id)"
                              @click.stop @change="toggleSelect(a.id)" />
                  <AgentAvatar :agent="a" :size="24" />
                  <span class="ap-name" :title="a.name">{{ a.name }}</span>
                  <span v-if="isDefault(a) || isBuiltin(a) || isPreset(a) || isSub(a) || isDisabledAgent(a)" class="ap-card-tags">
                    <span v-if="isDisabledAgent(a)" class="ap-tag-disabled" title="已停用：对话页不可选，已有会话与分享链接不可用；配置与历史版本保留">⏸ 已停用</span>
                    <span v-if="isDefault(a)" class="ap-tag-default" title="默认智能体：对话页预选">★ 默认</span>
                    <span v-if="isBuiltin(a)" class="ap-tag-builtin" title="系统默认智能体：全员可用，仅管理员可配置"><lock-outlined />内置</span>
                    <span v-else-if="isPreset(a)" class="ap-tag-builtin" title="平台内置助手：全员可用，仅平台超级管理员可配置"><lock-outlined />内置</span>
                    <span v-if="isSub(a)" class="ap-tag-sub">子智能体</span>
                  </span>
                </div>
                <p class="ap-desc" :title="a.description || ''">{{ a.description || '未填写描述' }}</p>
                <div class="ap-meta">
                  <span class="ap-meta-i" title="资料范围"><database-outlined />{{ scopeText(a) }}</span>
                  <span v-if="capSummary(a)" class="ap-meta-i" :title="capSummaryTitle(a)"><control-outlined />{{ capSummary(a) }}</span>
                  <span v-if="delegText(a)" class="ap-meta-i ap-meta-link" title="在委派编排视图中查看完整链路" @click.stop="openTopo(a)">
                    <apartment-outlined />{{ delegText(a) }}
                  </span>
                  <span v-if="updatedAgo(a)" class="ap-meta-i ap-meta-time" :title="'配置最后保存于 ' + fmtTime(a.updateTime || a.createTime)"><history-outlined />{{ updatedAgo(a) }}</span>
                </div>
                <div class="ap-card-foot">
                  <!-- 管理入口按后端回填的 manageable 收起：不可管理的智能体不给"点了报错"的死路
                       （写路径后端还会按归属二次判定）。
                       「试一试」直达对话并预选该智能体——配置完立刻验证，不用去对话页手动找人；
                       子智能体不参与直接对话，不给这个入口 -->
                  <button v-if="!isSub(a) && !isDisabledAgent(a)" class="app-btn small ap-btn-try" @click.stop="tryAgent(a)">试一试</button>
                  <template v-if="a.manageable">
                    <button class="app-btn ghost small" @click.stop="openEdit(a)">配置</button>
                    <a-dropdown placement="bottomRight" :trigger="['click']">
                      <button class="ap-more-btn" aria-label="更多操作" @click.stop><more-outlined /></button>
                      <template #overlay>
                        <a-menu>
                          <!-- 停用期间分享链接本就失效：发布管理没有可做的事，先启用再发布 -->
                          <a-menu-item v-if="!isPreset(a) && !isDisabledAgent(a)" key="pub" @click="openPublish(a)">发布管理</a-menu-item>
                          <a-menu-item key="copy" @click="copyAgent(a)">复制</a-menu-item>
                          <a-menu-item key="export" @click="exportAgent(a)">导出 JSON</a-menu-item>
                          <a-menu-item v-if="!isPreset(a)" key="ver" @click="openEdit(a); openVersions()">历史版本</a-menu-item>
                          <!-- 「设为默认」是全局动作（影响所有人下拉的预选），后端仅管理员放行；
                               平台预置是能力型助手，不能设为全员默认 -->
                          <a-menu-item v-if="canSetDefault(a)" key="def" @click="doSetDefault(a.id)">设为默认</a-menu-item>
                          <!-- 停用/启用：生命周期动作，与保存解耦（点一下立即生效）；默认智能体后端拒绝停用 -->
                          <a-menu-item key="disabled" :danger="!isDisabledAgent(a)" @click="toggleDisabled(a)">
                            {{ isDisabledAgent(a) ? '启用' : '停用' }}
                          </a-menu-item>
                          <!-- 内置智能体不提供删除入口（后端也会拒绝），避免出现"点了报错"的死路 -->
                          <a-menu-item v-if="!isBuiltin(a) && !isPreset(a)" key="del" danger @click="confirmDelete(a)">删除</a-menu-item>
                        </a-menu>
                      </template>
                    </a-dropdown>
                  </template>
                  <span v-else class="ap-readonly-hint">{{ isDisabledAgent(a)
                    ? '已停用'
                    : (isBuiltin(a) ? '系统内置 · 管理员可配置' : (isPreset(a) ? '系统内置 · 仅平台管理员可配置' : '仅可使用')) }}</span>
                </div>
              </article>
            </div>
          </section>
        </div>
      </div>
    </template>

    <!-- ==================== 配置视图（独立整页，不用弹窗） ==================== -->
    <template v-else>
      <div class="app-page-head ap-edit-head">
        <button class="app-icon-btn" title="返回列表" aria-label="返回列表" @click="closeEdit"><arrow-left-outlined /></button>
        <div class="ap-head-main">
          <h1 class="app-page-title ap-title">{{ form.name || '未命名智能体' }}</h1>
          <div class="ap-head-chips">
            <span class="ap-head-chip">{{ summaryScope }}</span>
            <span v-if="modeChip" class="ap-head-chip">{{ modeChip }}</span>
            <span v-if="form.disabled === 1" class="ap-head-chip is-danger">已停用</span>
            <span class="ap-head-chip" :class="{ 'is-accent': capsOverriddenCount > 0 }">
              {{ capsOverriddenCount ? `${capsOverriddenCount} 处与系统默认不同` : '能力全部跟随系统默认' }}
            </span>
          </div>
        </div>
        <div class="ap-head-r">
          <a-tooltip v-if="canShowStar" :title="form.isDefault ? '当前默认智能体' : '设为默认智能体（对话页预选）'">
            <button class="app-icon-btn ap-star-btn" :class="{ 'is-default': form.isDefault }"
                    :disabled="form.isDefault" aria-label="设为默认" @click="doSetDefault(editingId)">
              <star-outlined />
            </button>
          </a-tooltip>
          <button v-if="editingId && !editingPreset" class="app-btn ghost small" @click="openEvalTab">
            <experiment-outlined style="margin-right:4px" />评测
          </button>
          <button v-if="editingId && !editingPreset" class="app-btn ghost small" @click="openVersions">
            <history-outlined style="margin-right:4px" />历史版本
          </button>
          <button class="app-btn ghost small" @click="closeEdit">取消</button>
          <button class="app-btn small" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>

      <div class="app-page-body">
        <div class="ap-layout">
          <a-form layout="vertical" class="ap-form">
            <!-- 运行方式 -->
            <section class="app-card">
              <div class="ap-sec-head">
                <h2 class="app-card-title"><apartment-outlined class="ap-sec-ic" />运行方式</h2>
              </div>
              <a-segmented v-model:value="runMode" :options="runModeOptions" class="ap-mode-seg" @change="onRunModeChange" />
              <p class="ap-hint ap-mode-note">{{ runModeHint }}</p>
              <!-- 停用开关：与「保存」解耦，点一下立即生效；新建的智能体默认启用，不露出这一行 -->
              <div v-if="editingId" class="ap-enable-row">
                <div class="ap-enable-main">
                  <b>启用状态</b>
                  <p class="ap-hint">停用后从对话页移除，已有会话、@ 与公开分享链接立即不可用；配置与历史版本保留，可随时启用。</p>
                </div>
                <a-switch :checked="form.disabled !== 1" :loading="disabledBusy"
                          checked-children="启用" un-checked-children="停用" @change="onEnabledSwitch" />
              </div>
            </section>

            <!-- 身份与角色 -->
            <section class="app-card">
              <h2 class="app-card-title"><idcard-outlined class="ap-sec-ic" />身份与角色</h2>
              <div class="ap-id-row">
                <a-popover v-if="!identityLocked" v-model:open="iconOpen" trigger="click"
                           placement="bottomLeft" :overlay-style="{ width: '300px' }">
                  <template #content>
                    <div class="ap-icon-grid">
                      <button v-for="opt in iconOptions" :key="opt.value || 'default'" type="button"
                              class="ap-icon-opt" :class="{ on: (form.icon || '') === opt.value }"
                              :title="opt.label" :aria-pressed="(form.icon || '') === opt.value"
                              @click="form.icon = opt.value; iconOpen = false">
                        <AgentAvatar :agent="{ icon: opt.value, isBuiltin: 0 }" :size="26" />
                      </button>
                    </div>
                  </template>
                  <button type="button" class="ap-avatar-btn" title="更换图标">
                    <AgentAvatar :agent="{ icon: form.icon, isBuiltin: form.isBuiltin }" :size="46" />
                    <span class="ap-avatar-pen"><edit-outlined /></span>
                  </button>
                </a-popover>
                <span v-else class="ap-avatar-btn is-locked" :title="form.isBuiltin === 1 ? '内置「问渠」图标固定' : '平台预置智能体图标固定'">
                  <AgentAvatar :agent="{ icon: form.icon, isBuiltin: form.isBuiltin }" :size="46" />
                </span>
                <div class="ap-id-name">
                  <label class="ap-field-label">名称</label>
                  <a-input v-model:value="form.name" :maxlength="200" :disabled="identityLocked"
                           placeholder="如：合同审查助手 / 运维排障 / 产品 FAQ" />
                  <div v-if="identityLocked" class="ap-hint">{{ form.isBuiltin === 1 ? '名称、用途与图标随产品固定。' : '平台预置助手：名称、图标、用途（子智能体）与系统提示词随产品固定，仅能力配置可改。' }}</div>
                </div>
              </div>

              <a-form-item label="描述" class="ap-field">
                <a-textarea v-model:value="form.description" :maxlength="500" :rows="2"
                            placeholder="写清职责范围与典型问题（如：负责《操作手册》的界面操作与表单填写问题）" />
              </a-form-item>
              <div class="ap-field ap-field-last">
                <div class="ap-prompt-head">
                  <label class="ap-field-label" style="margin:0">系统提示词</label>
                  <!-- 布局三档：编辑=全宽书写（长提示词不用挤半栏），分屏=边写边看，预览=纯阅读；选择记住，下次进来保持 -->
                  <a-radio-group v-if="!identityLocked" v-model:value="promptPane" size="small" class="ap-pane-mode">
                    <a-radio-button value="edit">编辑</a-radio-button>
                    <a-radio-button value="split">分屏</a-radio-button>
                    <a-radio-button value="preview">预览</a-radio-button>
                  </a-radio-group>
                </div>
                <MdPreview v-if="identityLocked" editor-id="ap-system-prompt-view" :model-value="form.systemPrompt"
                           :theme="mdTheme" class="ap-md-editor ap-md-preview" />
                <template v-else>
                  <MdEditor v-if="promptPane !== 'preview'" :key="promptPane" v-model="form.systemPrompt"
                            editor-id="ap-system-prompt" language="zh-CN" :theme="mdTheme" :toolbars="mdToolbars"
                            :no-prettier="true" :footers="[]" :preview="promptPane === 'split'" @on-save="save"
                            placeholder="填写后完全替换全局系统提示词；留空沿用全局" class="ap-md-editor" />
                  <MdPreview v-else editor-id="ap-system-prompt-view" :model-value="form.systemPrompt"
                             :theme="mdTheme" class="ap-md-editor ap-md-preview" />
                </template>
                <div class="ap-hint">{{ identityLocked ? '系统提示词随产品固定，不可修改。' : '支持 Markdown，⌘S 保存' }}</div>
              </div>
            </section>

            <!-- 资料范围 -->
            <section class="app-card">
              <h2 class="app-card-title"><database-outlined class="ap-sec-ic" />资料范围</h2>
              <template v-if="form.isBuiltin === 1">
                <div class="ap-kb-fixed"><span class="ap-chip">「问渠」知识库</span><span class="ap-chip">问渠使用手册</span></div>
                <div class="ap-hint" style="margin-top:8px">
                  固定检索你的默认知识库「问渠」与官方《问渠使用手册》：上传到「问渠」库的资料就是它的检索范围，不可改为其它库。
                </div>
              </template>
              <template v-else>
                <div class="ap-scope-cards">
                  <button v-for="opt in scopeOptions" :key="opt.value" type="button"
                          class="ap-scope-card" :class="{ on: scopeMode === opt.value }"
                          :aria-pressed="scopeMode === opt.value" @click="scopeMode = opt.value">
                    <span class="ap-scope-dot" />
                    <span class="ap-scope-text"><b>{{ opt.label }}</b><small>{{ opt.hint }}</small></span>
                  </button>
                </div>
                <div v-if="scopeMode === 'pick'" class="ap-kb-pick">
                  <a-select v-model:value="form.knowledgeBaseIds" mode="multiple" :options="kbOptions" allow-clear
                            show-search option-filter-prop="label" :max-tag-count="6" style="width:100%"
                            placeholder="选择允许检索的知识库" />
                  <div class="ap-hint">已选 {{ form.knowledgeBaseIds.length }} 个；不选则检索不到内容</div>
                  <a-select v-model:value="form.knowledgeTags" mode="tags" :max-tag-count="8" allow-clear
                            :options="kbTagOptions" style="width:100%;margin-top:8px"
                            placeholder="检索标签收窄（可选）：如 部门A、合同——只检索打了这些标签的文档" />
                  <div class="ap-hint">
                    <template v-if="kbTagFetchFailed">标签候选加载失败，仍可手动输入</template>
                    <template v-else-if="kbTagOptions.length">候选来自所选知识库已打标的文档；也可手动输入新标签</template>
                    <template v-else>所选知识库还没有打过标签的文档（到文档管理页给文档打标）；也可手动输入</template>
                  </div>
                  <div class="ap-filter-row">
                    <a-select v-model:value="form.knowledgeFileTypes" mode="multiple" allow-clear
                              :max-tag-count="3" :options="KB_FILE_TYPE_OPTIONS"
                              style="flex:1;min-width:180px" placeholder="文档类型：全部" />
                    <a-select v-model:value="form.knowledgeUpdatedDays" allow-clear
                              :options="KB_UPDATED_OPTIONS"
                              style="width:148px" placeholder="最近更新：不限" />
                  </div>
                </div>
                <div v-else-if="scopeMode === 'none'" class="ap-hint">
                  纯角色智能体：完全不查资料，仅凭系统提示词与对话上下文作答；对话中手动 @ 的文档仍会被参考。
                </div>
                <div v-else class="ap-hint">检索全部知识库，阈值与权重随库。</div>
              </template>
            </section>

            <!-- 能力 -->
            <section class="app-card">
              <div class="ap-sec-head">
                <h2 class="app-card-title"><control-outlined class="ap-sec-ic" />能力</h2>
                <span class="ap-head-diff" :class="{ diff: capsOverriddenCount > 0 }">
                  <template v-if="capsOverriddenCount">
                    {{ capsOverriddenCount }} 处与系统默认不同
                    <button type="button" class="ap-diff-reset" @click="resetOverrides">恢复跟随</button>
                  </template>
                  <template v-else>全部跟随系统默认</template>
                </span>
              </div>
              <div v-if="form.isBuiltin === 1" class="ap-hint" style="margin-bottom:8px">
                内置问渠的「指定」只列平台内置资源（平台 MCP、内置技能）；系统提示词、HTTP 工具、工作流工具与轮终回调随产品固定。
              </div>
              <div class="ap-cap-list">
                <template v-for="g in CAP_GROUPS" :key="g">
                  <div class="ap-cap-grp-h">{{ g }}</div>
                  <!-- 深度执行：资源级二态（没有「跟随」档），这一格就是唯一判据 -->
                  <div v-if="g === '执行与交付'" class="ap-cap" :class="{ overridden: form.goalEnabled === 0 }">
                    <span class="ap-cap-ic"><aim-outlined /></span>
                    <div class="ap-cap-l">
                      <div class="ap-cap-name">允许深度执行 <span v-if="form.goalEnabled === 0" class="ap-cap-badge">已关闭</span></div>
                      <div class="ap-cap-desc" title="允许把这个助手交出去做长任务：输入框打 /deep 即可把一件事交出去，助手自己跨轮推进、做完就停；关掉时入口与执行面板都不出现。">
                        把长任务交出去：跨轮自动推进，做完就停
                      </div>
                    </div>
                    <div class="ap-seg">
                      <button type="button" :class="{ on: form.goalEnabled === 1 }" @click="form.goalEnabled = 1">开</button>
                      <button type="button" :class="{ on: form.goalEnabled === 0 }" @click="form.goalEnabled = 0">关</button>
                    </div>
                  </div>
                  <template v-for="c in capsOfGroup(g)" :key="c.key">
                    <div class="ap-cap" :class="{ overridden: capOverridden(c) }">
                      <span class="ap-cap-ic"><component :is="c.icon" /></span>
                      <div class="ap-cap-l">
                        <div class="ap-cap-name">
                          {{ c.label }}
                          <span v-if="capOverridden(c)" class="ap-cap-badge">与系统默认不同</span>
                        </div>
                        <div class="ap-cap-desc" :title="(c.detail || c.desc) + (globalText(c) ? '（系统默认' + globalText(c) + '）' : '')">
                          {{ c.desc }}
                        </div>
                      </div>
                      <span v-if="form.isBuiltin === 1 && (c.key === 'toolHttp' || c.key === 'toolWorkflow')"
                            class="ap-seg-lock" :title="'内置问渠不支持' + c.label + '（随产品固定）'">
                        <lock-outlined />随产品固定
                      </span>
                      <div v-else class="ap-seg">
                        <button v-for="s in capSegItems(c)" :key="s.v" type="button"
                                :class="{ on: capSegValue(c) === s.v }" @click="setCapState(c, s.v)">
                          {{ s.label }}<span v-if="s.gv" class="ap-seg-gv">{{ s.gv }}</span>
                        </button>
                      </div>
                    </div>
                    <!-- 「指定」的多选贴在自己那行下面；不要 pooled 到列表尾，否则看起来像挂在最后一行能力上 -->
                    <div v-if="needsPick(c)" class="ap-cap-pick">
                      <a-select v-model:value="form[c.listKey]" mode="multiple" :options="optionsOf(c)" allow-clear
                                size="small" style="width:100%"
                                :placeholder="'选择' + c.label + '（一项都不选则等同「不使用」）'" />
                      <div v-if="c.listKey === 'skills' && form[c.listKey].length" class="ap-pick-docs">
                        已选正文：<button v-for="n in form[c.listKey]" :key="n" class="app-link-btn"
                                      @click="openSkillDoc(n)">{{ String(n).split('/').pop() }}</button>
                      </div>
                      <div v-if="!optionsOf(c).length" class="ap-pick-empty">
                        当前系统里没有可选项 —— 需先在设置页配置{{ c.label }}
                      </div>
                    </div>
                    <!-- HTTP 工具集管理：挂在开关行下方（从属关系一眼可见），开关不开不出现 -->
                    <div v-if="c.key === 'toolHttp' && form.toolHttp === '1' && form.isBuiltin !== 1" class="ap-cap-pick">
                      <HttpToolsEditor v-model="form.httpTools" />
                      <div class="ap-pick-empty" style="margin-top:4px">
                        工具名 = 工具集名_操作名；执行审批跟随「工具执行确认」档位；游客分享与 API Key 白名单外的通道不暴露
                      </div>
                    </div>
                    <!-- 工作流工具管理：同样挂在开关行下方，勾选已发布工作流 -->
                    <div v-if="c.key === 'toolWorkflow' && form.toolWorkflow === '1' && form.isBuiltin !== 1" class="ap-cap-pick">
                      <WorkflowToolsEditor v-model="form.workflowTools" />
                      <div class="ap-pick-empty" style="margin-top:4px">
                        工具名 = wf_工作流名_短id；运行取该工作流当前发布版本的出参；嵌套调用有 2 层防环上限；游客分享与 API Key 白名单外的通道不暴露
                      </div>
                    </div>
                  </template>
                </template>
              </div>
            </section>

            <!-- 高级设置：与能力平级的独立卡片；常用治理常显，专家选项折叠 -->
            <section class="app-card">
              <div class="ap-sec-head">
                <h2 class="app-card-title"><setting-outlined class="ap-sec-ic" />高级设置</h2>
                <span class="ap-hint">治理与执行策略{{ advOverriddenCount ? ` · ${advOverriddenCount} 处与系统默认不同` : ' · 全部跟随系统默认' }}</span>
              </div>
              <div class="ap-adv-zone-h">常用治理</div>
              <div class="ap-adv-grid">
                <div class="ap-adv-field">
                  <label class="ap-field-label">权限档位</label>
                  <a-select v-model:value="form.permissionPreset" :options="presetOptions" allow-clear
                            size="small" :placeholder="presetPlaceholder" style="width:100%" />
                  <div v-if="presetError" class="ap-pick-empty">权限档位名册加载失败 —— <button type="button" class="app-btn ghost small" @click="loadPermissionPresets">重试</button></div>
                  <div v-else class="ap-hint">{{ presetHint() || '只读调研 / 常规 / 全自主' }}</div>
                </div>
                <div class="ap-adv-field">
                  <label class="ap-field-label">工具执行确认</label>
                  <a-select v-model:value="form.toolApprovalMode" :options="approvalOptions" size="small" style="width:100%" />
                  <div class="ap-hint">默认跟随档位，也可单独覆盖。</div>
                </div>
                <div class="ap-adv-field">
                  <label class="ap-field-label">记忆域</label>
                  <a-select v-model:value="form.memoryMode" :options="memoryModeOptions" size="small" style="width:100%" />
                  <div class="ap-hint">专属=独立记忆；叠加=再加用户级记忆</div>
                </div>
                <div v-if="runMode === 'main'" class="ap-adv-field">
                  <label class="ap-field-label">子智能体委派</label>
                  <a-select v-model:value="form.subAgentIds" mode="multiple" :options="visibleSubOptions" allow-clear
                            show-search option-filter-prop="label" :max-tag-count="6" style="width:100%"
                            :placeholder="form.isBuiltin === 1
                              ? (visibleSubOptions.length ? '选择允许委派的平台子智能体' : '内置问渠不绑定个人子智能体')
                              : (subOptions.length ? '选择允许委派的子智能体' : '还没有子智能体 —— 先把运行方式设为「子智能体」')" />
                  <div v-if="(form.subAgentIds || []).length" class="ap-hint" style="display:flex;align-items:center;gap:10px">
                    <span>把这条并行编排变成可画可跑的工作流？</span>
                    <button type="button" class="app-btn ghost small" :disabled="toWfLoading" @click="toWorkflow">
                      <partition-outlined /> 转成工作流
                    </button>
                  </div>
                  <div v-else class="ap-hint">不选则不限制可派遣的助手；选了就只派遣勾选的这几位。</div>
                </div>
              </div>
              <details class="ap-adv-more" :open="advOpen" @toggle="advOpen = $event.target.open">
                <summary class="ap-adv-more-h">
                  专家选项 <span class="ap-adv-count">4 项</span>
                  <down-outlined class="ap-adv-caret" />
                </summary>
                <div class="ap-adv-more-body">
                  <div class="ap-adv-grid">
                    <div class="ap-adv-field ap-adv-full">
                      <label class="ap-field-label">工具黑名单</label>
                      <a-select v-model:value="form.toolDeny" mode="tags" :options="denyOptions" allow-clear
                                size="small" style="width:100%" :max-tag-count="6"
                                placeholder="不禁用任何工具（可下拉选，或直接输入外部工具名）" />
                      <div v-if="toolCatalogError" class="ap-pick-empty">工具名册加载失败 —— <button type="button" class="app-btn ghost small" @click="loadToolCatalog">重试</button></div>
                      <div v-else class="ap-hint">只写要禁用的工具名，其余照旧。</div>
                    </div>
                    <div class="ap-adv-field">
                      <label class="ap-field-label">单轮工具步数</label>
                      <a-input-number v-model:value="form.maxToolSteps" :min="0" :max="1000" :step="1" size="small"
                                      style="width:100%" :placeholder="maxToolStepsPh" />
                      <div class="ap-hint">留空跟随默认，0 = 不限</div>
                    </div>
                    <div class="ap-adv-field">
                      <label class="ap-field-label">检索重排</label>
                      <a-select v-model:value="form.qpRerank" :options="rerankOptions" size="small" style="width:100%" />
                      <div class="ap-hint">重排由知识库绑定的重排模型决定（选了才重排）；低延迟可在此强制关</div>
                    </div>
                    <div class="ap-adv-field ap-adv-full">
                      <label class="ap-field-label">轮终回调</label>
                      <template v-if="form.isBuiltin !== 1">
                        <a-input v-model:value="form.callbackUrl" size="small" allow-clear autocomplete="off"
                                 placeholder="https://宿主系统.example.com/wenqu/callback（每轮结束 POST 结果）" />
                        <a-input-password v-model:value="form.callbackSecret" size="small" class="ap-cb-secret" autocomplete="new-password"
                                          :placeholder="form.callbackSecretSet ? '已配置（重新填写可更换，留空沿用）' : '签名密钥（可选，留空则不带签名头）'" />
                        <div class="ap-hint">每轮结束 POST 签名结果；失败自动重试，内网地址会被拒绝</div>
                      </template>
                      <div v-else class="ap-hint" style="margin-top:4px">内置问渠不提供轮终回调。</div>
                    </div>
                    <div v-if="agentDefaultsError" class="ap-pick-empty ap-adv-full">
                      系统默认值加载失败 —— 上面的「跟随系统」没点名当前值 ——
                      <button type="button" class="app-btn ghost small" @click="loadAgentDefaults">重试</button>
                    </div>
                  </div>
                </div>
              </details>
            </section>
          </a-form>

          <aside class="ap-side">
            <section class="app-card ap-side-card">
              <h3 class="ap-side-title">生效摘要</h3>
              <div class="ap-sum-row"><span>资料</span><b>{{ summaryScope }}</b></div>
              <div class="ap-sum-row"><span>能力</span><b>{{ summaryCapsList }}</b></div>
              <div class="ap-sum-row"><span>执行</span><b>{{ summaryExecution }}</b></div>
              <div class="ap-sum-row"><span>委派</span><b>{{ summaryDelegation }}</b></div>
              <div class="ap-sum-row"><span>默认</span><b>{{ form.isDefault ? '是' : '否' }}</b></div>
              <div v-if="overrideCount" class="ap-diff">
                <span>与系统默认不同 {{ overrideCount }} 处</span>
                <button class="app-link-btn" @click="resetOverrides">恢复跟随</button>
              </div>
            </section>
            <section class="app-card ap-side-card ap-side-tip">
              <h3 class="ap-side-title">这页只放例外</h3>
              <p>「跟随系统」的项不会被写进这个助手。要改系统默认，去「系统设置 → 工具与检索」。</p>
            </section>
            <section v-if="editingId" class="app-card ap-side-card">
              <h3 class="ap-side-title">最近保存</h3>
              <p class="ap-side-meta">{{ recentSavedText }}</p>
              <button class="app-link-btn" @click="openVersions">查看历史版本</button>
            </section>
          </aside>
        </div>
      </div>
    </template>

    <!-- 配置历史版本：每次保存落一版快照（人设/知识范围/工具集等调优字段），可查字段级 diff、一键回滚 -->
    <a-drawer v-model:open="verOpen" title="配置历史版本" :width="'min(680px, 94vw)'" destroy-on-close>
      <p class="ap-block-hint" style="margin-top:0">
        每次保存都会留下一份配置快照。回滚会把历史版本的配置重新应用（并生成一条新的版本记录）——
        历史版本本身不会被改写，回滚动作也可以再回滚。
      </p>
      <a-spin :spinning="verLoading">
        <div v-if="verList.length" class="ap-ver-list">
          <div v-for="v in verList" :key="v.version" class="ap-ver-item" :class="{ cur: v.current }">
            <div class="ap-ver-head" @click="toggleVer(v.version)">
              <span class="ap-ver-no">v{{ v.version }}</span>
              <span v-if="v.current" class="ap-ver-cur">当前</span>
              <span class="ap-ver-reason">{{ v.reason || '保存' }}</span>
              <span class="ap-ver-changes">{{ (v.changes || []).length ? `${v.changes.length} 项变更` : '无变更' }}</span>
              <span class="ap-ver-time">{{ fmtTime(v.createTime) }}</span>
              <down-outlined class="ap-ver-caret" :class="{ open: verExpanded.includes(v.version) }" />
            </div>
            <div v-if="verExpanded.includes(v.version)" class="ap-ver-body">
              <div v-if="!(v.changes || []).length" class="ap-ver-empty">与上一版相比没有字段变化。</div>
              <table v-else class="ap-ver-diff">
                <tbody>
                  <tr v-for="c in v.changes" :key="c.field">
                    <td class="ap-ver-lbl">{{ c.label }}</td>
                    <td class="ap-ver-old" :title="c.from">{{ clip(c.from) || '（空）' }}</td>
                    <td class="ap-ver-arrow">→</td>
                    <td class="ap-ver-new" :title="c.to">{{ clip(c.to) || '（空）' }}</td>
                  </tr>
                </tbody>
              </table>
              <div v-if="!v.identical" class="ap-ver-act">
                <button class="app-btn ghost small" :disabled="verRolling === v.version" @click="doRollback(v)">
                  {{ verRolling === v.version ? '回滚中…' : `回滚到此版本` }}
                </button>
              </div>
              <div v-else class="ap-ver-act ap-ver-same">内容与当前配置一致，无需回滚</div>
            </div>
          </div>
        </div>
        <div v-else-if="!verLoading" class="ap-ver-none">暂无历史版本</div>
      </a-spin>
    </a-drawer>

    <!-- 保存前检查（评测门禁，hint 口径）：有评测集且本次有变更/低于门槛时才弹；无评测集静默直存 -->
    <EvalGateModal v-model:open="gateOpen" :info="gateInfo" :saving="saving" @confirm="onGateConfirm" />

    <!-- 公开分享（/s/{token} 免登录对话 + iframe 嵌入 + MCP 端点）
         结构：状态卡（一个总开关说清"发布没发布"）→ 游客能力（模型 / MCP）→ 分发方式（Tab 三选一）。
         链接、iframe、MCP 三段是同一 token 的三种用法，收进 Tab 里切换。 -->
    <a-modal v-model:open="pubVisible" :width="640" :footer="null" class="ap-pub-modal" destroy-on-close>
      <template #title>
        <div class="pub-title">
          <AgentAvatar :agent="pubAgent" :size="22" />
          <span>公开发布</span>
          <span class="pub-title-sub">{{ pubAgentName || '智能体' }}</span>
        </div>
      </template>

      <!-- 状态卡：发布与否一眼可见，说明只有一句 -->
      <div class="pub-status" :class="pubEnabled ? 'on' : 'off'">
        <div class="pub-status-l">
          <div class="pub-status-t">{{ pubEnabled ? '已发布' : '未发布' }}</div>
          <div class="pub-status-d">
            {{ pubEnabled ? '拿到下方链接的人可免登录与该智能体对话' : '开启后生成一条公开链接；停用时链接保留、立即失效' }}
          </div>
          <!-- 发布出去后唯一能回收的信号：被提问了多少次、最近一次何时（对话内容不对发布者开放） -->
          <div v-if="pubEnabled && pubVisitCount" class="pub-status-stat">
            <history-outlined />
            <span>已被提问 {{ pubVisitCount }} 次<template v-if="pubLastVisitAt"> · 最近 {{ fmtTime(pubLastVisitAt) }}</template></span>
          </div>
        </div>
        <a-switch v-model:checked="pubEnabled" @change="savePublish" />
      </div>

      <!-- 游客能力：模型归属发布者（只能选自己可用的），MCP 端点是分享的附加形态 -->
      <div class="pub-block">
        <div class="pub-block-h">游客能力</div>
        <div class="pub-field" data-fix="pubModelRef">
          <span class="pub-label">对话模型</span>
          <!-- 发布者就是当前登录用户：跟随档位直接点名其个人默认聊天模型 -->
          <ModelSelect v-model="pubModelRef" type="chat" width="100%" inherit-label="跟随发布者个人默认模型"
                       inherit-resolve="personalDefault" @change="savePublish" />
        </div>
        <div class="pub-switch-row">
          <a-switch v-model:checked="pubMcpEnabled" :disabled="!pubEnabled" size="small" @change="savePublish" />
          <div class="pub-switch-t">
            <div class="pub-switch-label">开放 MCP 端点</div>
            <div class="pub-switch-d">开启后同一条链接兼作 MCP 凭据，Claude / Cursor 等客户端可直连，并可挑选对游客开放的 MCP 工具</div>
            <!-- 游客 MCP 授权名单：挂在端点开关之下（从属关系一眼可见），端点关着时不出现。
                 勾选项=智能体配置里实际绑定的 MCP 服务（指定模式列它绑的、
                 跟随全局列全部已启用），与运行期「游客名单 ∩ 智能体具体项选择」的装配口径一致 -->
            <div v-if="pubMcpEnabled && myMcpServerChoices.length" class="pub-mcp-sub">
              <div class="pub-mcp-sub-h">
                <span class="pub-label">游客可用 MCP 工具</span>
                <span class="pub-mcp-sub-act">
                  <button class="app-link-btn" @click="pubGuestMcp = myMcpServerChoices.filter(s => s.connected).map(s => s.name); saveMcpChecks()">全选</button>
                  <button class="app-link-btn" @click="pubGuestMcp = []; saveMcpChecks()">清空</button>
                </span>
              </div>
              <a-checkbox-group v-model:value="pubGuestMcp" class="pub-mcp-checks" @change="saveMcpChecks">
                <a-checkbox v-for="s in myMcpServerChoices" :key="s.name" :value="s.name" :disabled="!s.connected">
                  {{ s.name }}<template v-if="!s.connected">（未连接，暂不可勾）</template>
                </a-checkbox>
              </a-checkbox-group>
              <div class="pub-switch-d">勾选后这些 MCP 服务的工具对访客开放且免逐次审批</div>
              <!-- 档位=禁用是确定翻车：运行期 approvalPolicyAllows 直接不挂 MCP 工具族，游客一个都调不动。
                   只在这种确定场景就地警告，不再无条件念规则 -->
              <div v-if="pubAgentApprovalOff" class="pub-warn">
                <exclamation-circle-outlined />
                <span>该智能体的「工具执行确认」当前是「禁用」：游客一个 MCP 工具都调不动。请到「配置 → 工具执行确认」改成「自动执行」或「执行前确认」</span>
              </div>
            </div>
            <div v-else-if="pubMcpEnabled" class="pub-switch-d" style="margin-top:8px">
              没有可对游客开放的 MCP 工具：该智能体的「MCP 外部工具」为「不使用」或未指定任何服务，或者你名下没有已启用的 MCP 服务
            </div>
          </div>
        </div>
        <div v-if="!pubEnabled" class="pub-warn">
          <exclamation-circle-outlined />
          <span>MCP 端点依赖公开分享，且需管理员在「系统设置 → MCP 服务（双向）」开启「对外提供 MCP 端点」</span>
        </div>
      </div>

      <!-- 游客输入：访客能发什么。默认全关——游客模型由上面那一项选定，看不懂图的模型收到图片
           只会报错或被静默忽略，视觉 token 的成本却记在发布者账上，所以这是「发布者有没有明确同意」
           的问题而不是体验偏好。两个开关分开而不是合成一个：纯文本模型读 PDF 恰恰是最擅长的，
           合成一个会逼发布者做无谓的取舍 -->
      <div class="pub-block">
        <div class="pub-block-h">游客输入</div>
        <div class="pub-switch-row">
          <a-switch v-model:checked="pubGuestImages" :disabled="!pubEnabled" size="small" @change="savePublish" />
          <div class="pub-switch-t">
            <div class="pub-switch-label">允许发图</div>
            <div class="pub-switch-d">访客可粘贴 / 拖入 / 拍照提问；图片压缩到 1280px 后随本轮送出（需模型能看图）</div>
          </div>
        </div>
        <!-- 模型看不懂图时的就地提示：不拦（「跟随个人默认模型」这一档在发布时解析不到具体模型，
             拦了会把正确配置也挡在门外），但要说清楚会发生什么 -->
        <div v-if="pubVisionWarn" class="pub-warn">
          <exclamation-circle-outlined />
          <span v-if="pubVisionWarn === 'model'">当前对话模型不具备图片理解能力，访客发图会被忽略或报错；请换一个支持看图的模型</span>
          <span v-else>对话模型为「跟随发布者个人默认模型」，此处无法判定它能否看图；请确认你的个人默认模型支持图片理解，否则访客发来的图会被忽略</span>
        </div>
        <div class="pub-switch-row">
          <a-switch v-model:checked="pubGuestFiles" :disabled="!pubEnabled" size="small" @change="savePublish" />
          <div class="pub-switch-t">
            <div class="pub-switch-label">允许上传文档附件</div>
            <div class="pub-switch-d">访客可上传 PDF / Word / Excel / 代码等；逐访客隔离落盘，24 小时后自动清理</div>
          </div>
        </div>
        <!-- 调用变量：嵌入页 JS 随轮传业务上下文（如当前用户/单号）。默认关——变量直进模型上下文，
             等于给了宿主页面向提示词塞上下文的通道，发布者显式同意才生效；关闭时传入的变量静默忽略 -->
        <div class="pub-switch-row">
          <a-switch v-model:checked="pubVariables" :disabled="!pubEnabled" size="small" @change="savePublish" />
          <div class="pub-switch-t">
            <div class="pub-switch-label">允许调用变量</div>
            <div class="pub-switch-d">传入的变量注入本轮作答；关闭时忽略</div>
          </div>
        </div>
        <div v-if="!pubEnabled" class="pub-warn">
          <exclamation-circle-outlined />
          <span>这两项依赖公开分享：分享停用后访客入口随之消失</span>
        </div>
      </div>

      <!-- 分发形态：三选一。共享同一个 token，切换不产生新链接 -->
      <div class="pub-block" v-if="pubToken && pubEnabled">
        <div class="pub-block-h">分发方式</div>
        <a-tabs v-model:activeKey="pubTab" size="small" class="pub-tabs">
          <a-tab-pane key="link" tab="分享链接">
            <div class="pub-code-box">
              <code class="pub-code">{{ shareUrl }}</code>
              <!-- 按钮浮在代码框内右上角：不独占一列，单行的 URL 不会被挤成两行 -->
              <div class="pub-code-act">
                <a :href="shareUrl" target="_blank" rel="noopener" class="pub-icon-btn" title="在新标签打开" aria-label="打开链接">
                  <export-outlined />
                </a>
                <button class="pub-icon-btn" title="复制链接" aria-label="复制链接"
                        @click="copyText(shareUrl, '链接已复制')"><copy-outlined /></button>
              </div>
            </div>
            <div class="pub-hint">免登录对话；检索按你的可见范围</div>
          </a-tab-pane>

          <a-tab-pane key="iframe" tab="嵌入网页">
            <div class="pub-size-row">
              <label class="pub-size">
                <span>宽</span>
                <a-input-number v-model:value="pubFrameW" :min="280" :max="1200" :step="20" size="small" />
              </label>
              <label class="pub-size">
                <span>高</span>
                <a-input-number v-model:value="pubFrameH" :min="360" :max="1000" :step="20" size="small" />
              </label>
            </div>
            <div class="pub-code-box">
              <pre class="pub-code block">{{ iframeSnippet }}</pre>
              <div class="pub-code-act">
                <button class="pub-icon-btn" title="复制嵌入代码" aria-label="复制嵌入代码"
                        @click="copyText(iframeSnippet, '嵌入代码已复制')"><copy-outlined /></button>
              </div>
            </div>
            <div class="pub-hint">游客不暴露沙盒 / 产物 / MCP / 技能</div>
          </a-tab-pane>

          <a-tab-pane key="mcp" tab="MCP 端点">
            <template v-if="pubMcpEnabled">
              <div class="pub-sub-label">端点地址</div>
              <div class="pub-code-box">
                <code class="pub-code">{{ mcpUrl }}</code>
                <div class="pub-code-act">
                  <button class="pub-icon-btn" title="复制地址" aria-label="复制 MCP 地址"
                          @click="copyText(mcpUrl, 'MCP 地址已复制')"><copy-outlined /></button>
                </div>
              </div>
              <div class="pub-sub-label">mcp.json（Cursor / Claude Desktop）</div>
              <div class="pub-code-box">
                <pre class="pub-code block">{{ mcpJson }}</pre>
                <div class="pub-code-act">
                  <button class="pub-icon-btn" title="复制 mcp.json" aria-label="复制 mcp.json"
                          @click="copyText(mcpJson, 'mcp.json 已复制')"><copy-outlined /></button>
                </div>
              </div>
              <div class="pub-hint">
                粘贴到 Claude Desktop 的 Integrations 或仓库根目录 .cursor/mcp.json。
                地址含凭据等同密钥：撤销分享或关掉上面开关立即失效。外部调用同样受限——沙盒 / 产物 / 个人技能与个人 MCP 不暴露。
              </div>
            </template>
            <div v-else class="pub-placeholder">
              <ApiOutlined />
              <div>尚未开放 MCP 端点</div>
              <div class="pub-hint">打开「游客能力」后给出端点与配置</div>
            </div>
          </a-tab-pane>
        </a-tabs>
      </div>

      <!-- 停用但 token 保留：给出一句说明，替换掉上面的分发区 -->
      <div class="pub-block" v-else-if="pubToken && !pubEnabled">
        <div class="pub-block-h">分发方式</div>
        <div class="pub-placeholder">
          <link-outlined />
          <div>链接已随分享停用而失效</div>
          <div class="pub-hint">重新开启总开关即恢复，链接不变。</div>
        </div>
      </div>

      <div class="pub-foot">
        <a-popconfirm title="撤销后链接立即失效，重新发布会生成新链接，确定撤销？" ok-text="撤销" cancel-text="取消" @confirm="doRevoke">
          <button class="app-link-btn danger" :disabled="!pubToken">撤销分享</button>
        </a-popconfirm>
        <span class="pub-foot-hint" v-if="pubToken && pubEnabled">链接即凭据，请勿公开到不受控的渠道</span>
      </div>
    </a-modal>

    <!-- 委派编排视图：列表承载规模（搜索 / 筛选 / 窗口化滚动），拓扑只画一位主智能体的链路。
         全量拓扑在几十上百个智能体时是几千像素的长图，链路也缠在一起——图只回答「这一条链路长什么样」，
         规模和检索归列表。 -->
    <a-modal v-model:open="topoOpen" title="委派编排视图" :width="1000" :footer="null" class="ap-topo-modal">
      <div class="ap-topo-head">
        <div class="ap-topo-seg">
          <button type="button" :class="{ on: topoMode === 'list' }" @click="topoMode = 'list'">列表</button>
          <button type="button" :class="{ on: topoMode === 'topo' }" :disabled="!topoFocus" @click="topoMode = 'topo'">拓扑</button>
        </div>
        <div class="ap-topo-sum">
          <span>主智能体 <b>{{ topoSummary.mains }}</b></span>
          <span>子智能体 <b>{{ topoSummary.subs }}</b></span>
          <span :class="{ warn: topoSummary.orphans > 0 }" :title="topoOrphanTitle">未进入清单 <b>{{ topoSummary.orphans }}</b></span>
          <span :class="{ danger: topoSummary.missing > 0 }">失效引用 <b>{{ topoSummary.missing }}</b></span>
        </div>
      </div>

      <!-- 列表：行内直接看委派，点一行进聚焦拓扑 -->
      <template v-if="topoMode === 'list'">
        <div class="ap-topo-bar">
          <input v-model.trim="topoQuery" class="ap-topo-q" type="text" placeholder="搜索主智能体名称 / 描述" />
          <button v-for="f in topoFilters" :key="f.key" type="button" class="ap-topo-chip"
                  :class="{ on: topoFilter === f.key }" @click="topoFilter = f.key">{{ f.label }}<em>{{ f.count }}</em></button>
        </div>
        <div v-if="!topoFiltered.length" class="ap-topo-empty">
          {{ topoRows.length ? '没有匹配的智能体' : '还没有主智能体' }}
        </div>
        <template v-else>
          <div class="ap-topo-thead">
            <span class="c-name">主智能体</span>
            <span class="c-sub">委派子智能体</span>
            <span class="c-st">状态</span>
          </div>
          <div ref="topoListEl" class="ap-topo-rows" @scroll="onTopoScroll">
            <div :style="{ height: topoWin.padTop + 'px' }"></div>
            <div v-for="r in topoWin.rows" :key="r.agent.id" class="ap-topo-row"
                 :class="{ sel: topoFocusId === r.agent.id }" @click="focusTopo(r.agent)">
              <span class="c-name">
                <AgentAvatar :agent="r.agent" :size="24" />
                <span class="ap-topo-who">
                  <b>{{ r.agent.name }}</b>
                  <small>{{ r.agent.description || '未填写描述' }}</small>
                </span>
              </span>
              <span class="c-sub">
                <template v-if="r.subs.length">
                  <span v-for="s in r.subs.slice(0, 3)" :key="s.id" class="ap-topo-tg">{{ s.name }}</span>
                  <span v-if="r.subs.length > 3" class="ap-topo-tg more">+{{ r.subs.length - 3 }}</span>
                </template>
                <span v-else class="ap-topo-unb">不限（全部可派遣）</span>
              </span>
              <span class="c-st">
                <span v-if="r.missingIds.length" class="ap-topo-badge err">失效引用 {{ r.missingIds.length }}</span>
                <span v-else-if="r.subs.length" class="ap-topo-badge ok">已配置</span>
                <span v-else class="ap-topo-badge">默认不限</span>
              </span>
            </div>
            <div :style="{ height: topoWin.padBottom + 'px' }"></div>
          </div>
          <div class="ap-topo-foot">
            显示 {{ topoWin.from }}–{{ topoWin.to }} / {{ topoFiltered.length }}
            <span class="sp"></span>
            <span>滚轮浏览 · 点任意一行看委派链路</span>
          </div>
        </template>
      </template>

      <!-- 聚焦拓扑：画布只随这一位的委派数增长，与智能体总量无关 -->
      <template v-else-if="topoFocus">
        <div class="ap-topo-crumb">
          <button type="button" class="app-link-btn" @click="topoMode = 'list'">← 返回列表</button>
          <span class="sep">/</span>
          <b>{{ topoFocus.agent.name }}</b>
          <span class="sep">/</span>
          <span>{{ topoFocus.subs.length ? `委派 ${topoFocus.subs.length} 个子智能体` : '未配置委派（默认不限）' }}</span>
          <span class="sp"></span>
          <a-select :value="topoFocus.agent.id" :options="topoMainOptions" size="small" show-search
                    option-filter-prop="label" style="width: 200px" @change="v => (topoFocusId = v)" />
          <button v-if="topoFocus.agent.manageable" type="button" class="app-btn small"
                  @click="openEdit(topoFocus.agent); topoOpen = false">编辑委派</button>
        </div>
        <div class="ap-topo-legend">
          <span class="ap-lg"><span class="ap-lg-dot main" />主智能体</span>
          <span class="ap-lg"><span class="ap-lg-dot sub" />子智能体</span>
          <span class="ap-lg"><svg width="30" height="8"><line x1="0" y1="4" x2="30" y2="4" class="ap-edge miss" /></svg>失效引用（子智能体已删除）</span>
          <span class="ap-lg">悬停高亮链路 · 点击节点进配置</span>
        </div>
        <div v-if="!topoGraph.nodes.length" class="ap-topo-empty">
          这一位没有配置委派：清单为空时默认允许派遣全部子智能体。到「高级设置 → 子智能体委派」里勾选即可收窄。
        </div>
        <div v-else class="ap-topo-canvas">
          <svg :viewBox="`0 0 ${topoGraph.W} ${topoGraph.H}`" :width="topoGraph.W" :height="topoGraph.H" class="ap-topo-svg">
            <path v-for="e in topoGraph.edges" :key="e.key" :d="e.d" class="ap-edge" :class="[edgeCls(e), { miss: e.miss }]" />
            <g v-for="n in topoGraph.nodes" :key="'n' + n.key" class="ap-node" :class="nodeCls(n.key)"
               @mouseenter="topoHover = n.key" @mouseleave="topoHover = ''"
               @click="n.agent && n.agent.manageable ? (openEdit(n.agent), topoOpen = false) : null">
              <rect :x="n.x" :y="n.y" :width="TOPO.subW" :height="TOPO.rowH" rx="8"
                    class="ap-nrect" :class="n.miss ? 'miss' : 'sub'" />
              <text class="ap-nname" :x="n.x + 12" :y="n.y + 22">{{ n.miss ? '已删除的子智能体' : trunc(n.agent.name, SUB_NAME) }}</text>
              <text class="ap-ndesc" :x="n.x + 12" :y="n.y + 40">{{ n.miss ? `#${n.id}` : trunc(n.agent.description || '未填写描述', SUB_LINE) }}</text>
              <text class="ap-nmeta" :x="n.x + 12" :y="n.y + 56">{{ n.miss ? '该委派不会生效' : subMeta(n.agent) }}</text>
            </g>
            <g class="ap-node" :class="nodeCls('m')" @mouseenter="topoHover = 'm'" @mouseleave="topoHover = ''"
               @click="topoFocus.agent.manageable ? (openEdit(topoFocus.agent), topoOpen = false) : null">
              <rect :x="topoGraph.mainX" :y="topoGraph.mainY" :width="TOPO.nodeW" :height="TOPO.rowH" rx="8" class="ap-nrect main" />
              <text class="ap-nname" :x="topoGraph.mainX + 12" :y="topoGraph.mainY + 22">{{ trunc(topoFocus.agent.name, MAIN_NAME) }}</text>
              <text class="ap-ndesc" :x="topoGraph.mainX + 12" :y="topoGraph.mainY + 40">{{ trunc(topoFocus.agent.description || '未填写描述', MAIN_LINE) }}</text>
              <text class="ap-nmeta" :x="topoGraph.mainX + 12" :y="topoGraph.mainY + 56">{{ topoFocus.subs.length ? `委派 ${topoFocus.subs.length} 个子智能体` : '委派不限（默认全部）' }}</text>
            </g>
          </svg>
        </div>
        <div v-if="topoSummary.orphans" class="ap-topo-note">
          <span>{{ topoSummary.orphans }} 个子智能体没进入任何委派清单：{{ topoOrphanNames }}</span>
          <span class="sp"></span>
          <span>{{ topoSummary.defaultMains ? '清单为空的主智能体派遣不受限' : '所有主智能体都配了清单，它们暂时派不动' }}</span>
        </div>
      </template>
    </a-modal>

    <!-- 技能正文查看（「指定」旁那几个「已选正文」入口）：本页没有会话上下文，
         给的是内置 + 名下那两份，与下拉看到的是同一份清单 -->
    <SkillContentModal :open="!!skillDoc" :name="skillDoc" :entry="skillDocEntry"
                       @update:open="v => { if (!v) skillDoc = '' }" />

    <!-- 新建智能体：模板先行。内置助手与自己名下的智能体就是现成模板——
         复制带出提示词/资料范围/能力配置，创建后均可修改；导入吃「导出 JSON」生成的文件 -->
    <a-modal v-model:open="createOpen" title="新建智能体" :width="680" :footer="null" class="ap-create-modal" destroy-on-close>
      <p class="ap-block-hint" style="margin-top:0">
        从一个现有智能体复制起步，或从空白创建。复制会带出名称、描述、提示词、资料范围与能力配置，创建后均可修改。
      </p>
      <div class="ap-tpl-grid">
        <button class="ap-tpl" type="button" @click="startBlank">
          <span class="ap-tpl-ic"><plus-outlined /></span>
          <b>空白创建</b><small>从零配置提示词、能力与资料范围</small>
        </button>
        <button v-for="a in copyableAgents" :key="a.id" class="ap-tpl" type="button" @click="copyAgent(a)">
          <span class="ap-tpl-ic"><AgentAvatar :agent="a" :size="24" /></span>
          <b>{{ a.name }}</b><small>{{ a.description || '未填写描述' }}</small>
        </button>
      </div>
      <div class="ap-tpl-foot">
        <button class="app-btn ghost small" type="button" @click="pickImportFile"><import-outlined /> 导入 JSON 配置</button>
        <span class="ap-hint">导入「导出 JSON」或「批量管理 → 导出」生成的文件；载入后可先检查再保存</span>
      </div>
      <input ref="importInput" type="file" accept="application/json,.json" class="ap-import-input" @change="onImportFile" />
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  ArrowLeftOutlined, ReloadOutlined, SearchOutlined, IdcardOutlined,
  DatabaseOutlined, ControlOutlined, StarOutlined, ApartmentOutlined,
  FileSearchOutlined, QuestionCircleOutlined, FileDoneOutlined, AppstoreOutlined, ApiOutlined,
  PartitionOutlined, GlobalOutlined, HistoryOutlined, DownOutlined,
  AimOutlined,
  SettingOutlined, EditOutlined, ExperimentOutlined,
  CopyOutlined, ExportOutlined, LinkOutlined, ExclamationCircleOutlined,
  LockOutlined, MoreOutlined, PlusOutlined, ImportOutlined
} from '@ant-design/icons-vue'
import { listAgents, createAgent, updateAgent, deleteAgent, batchDeleteAgents, setAgentDefault, listKnowledgeBases,
         listDocuments,
         listAvailableSkills, getMcpStatus, listSubAgents, getToolCatalog, getPermissionPresets,
         getAgentConfigDefaults,
         agentEvalGateCheck, agentEvalStartRun } from '../api'
import EvalGateModal from '../components/agent/EvalGateModal.vue'
import ModelSelect from '../components/ModelSelect.vue'
import AgentAvatar from '../components/AgentAvatar.vue'
import SkillContentModal from '../components/SkillContentModal.vue'
import HttpToolsEditor from '../components/agent/HttpToolsEditor.vue'
import WorkflowToolsEditor from '../components/agent/WorkflowToolsEditor.vue'
import { ensureAuth, isAdminSync } from '../utils/auth'
import { copyText } from '../utils/clipboard'
import { MdEditor, MdPreview } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import { themeState } from '../utils/theme'
import { isDefault, isDisabledAgent, isBuiltin, isPreset, isSub, splitList, triStr, clip, fmtTime } from '../agents/agentBasics'
import { useAgentPublish } from '../agents/useAgentPublish'
import { useDelegationTopo } from '../agents/useDelegationTopo'
import { useAgentVersions } from '../agents/useAgentVersions'

const route = useRoute()
const router = useRouter()

// ==================== 能力定义 ====================
// path：该能力在全局配置里的开关路径；gate：还受此总闸制约（关掉总闸时能力不生效）
// desc 只留一行给紧凑行版式；detail 是悬浮时看的完整解释（不占页面高度）
const CAPS = [
  { key: 'toolKnowledge', group: '检索与知识', label: '知识库检索', desc: '回答时检索选定的知识库', icon: FileSearchOutlined,
    detail: '回答过程中可自主检索知识库补充依据',
    kind: 'switch', gate: ['tool', 'enabled'] },
  { key: 'toolWebsearch', group: '检索与知识', label: '联网搜索', desc: '联网检索，结果登记为网页来源', icon: GlobalOutlined,
    detail: '模型可自主联网检索；命中结果登记为网页来源，与知识库来源共用引用编号',
    kind: 'switch', path: ['webSearch', 'enabled'], gate: ['tool', 'enabled'] },
  { key: 'toolMcp', group: '工具与扩展', label: 'MCP 外部工具', desc: '连接外部 MCP 服务', icon: ApiOutlined,
    detail: '跟随系统=挂你登记的全部已启用 MCP（不含平台内置）；指定=只挂勾选的（可勾平台内置）',
    kind: 'list', modeKey: 'mcpMode', listKey: 'mcps', optionsKey: 'mcpOptions',
    gate: ['tool', 'enabled'] },
  // HTTP 工具（OpenAPI 导入）：工具集是智能体自有配置（不是个人资产引用），开启后在本行下方管理；
  // 没有独立全局开关（httpTool.enabled 已退役），只受「工具调用」总闸制约
  { key: 'toolHttp', group: '工具与扩展', label: 'HTTP 工具', desc: '按 OpenAPI 导入宿主 API 工具集', icon: LinkOutlined,
    detail: '导入 OpenAPI 生成工具，模型可自主调用（游客不暴露）',
    kind: 'switch', gate: ['tool', 'enabled'] },
  // 工作流工具：把已发布工作流挂载为对话工具（智能体自有配置），开启后在本行下方勾选；
  // 没有独立全局开关（workflowTool.enabled 已退役），只受「工具调用」总闸制约
  { key: 'toolWorkflow', group: '工具与扩展', label: '工作流工具', desc: '把已发布工作流挂为对话工具', icon: PartitionOutlined,
    detail: '已发布工作流（≤8 个），模型按需运行；嵌套上限 2 层，游客不暴露',
    kind: 'switch', gate: ['tool', 'enabled'] },
  // 向用户提问：ask_user 族只有 askUser 一项，配置面是一个三态开关；
  // 存量兼容见 effectiveAskUserTri（保存时恒清 builtinTools，见 save）
  { key: 'toolBuiltin', group: '执行与交付', label: '向用户提问', desc: '人在回路：回答中向你提出问题，答后继续', icon: QuestionCircleOutlined,
    detail: '提问卡（可多选的题会标出）；原「内置高频工具」族退役后仅剩此项',
    kind: 'switch', gate: ['tool', 'enabled'] },
  { key: 'toolArtifact', group: '执行与交付', label: '产物交付', desc: '生成可下载的交付文件', icon: FileDoneOutlined,
    detail: '生成 Markdown / CSV / JSON / HTML 文件并附下载卡片',
    kind: 'switch', gate: ['tool', 'enabled'] },
  // 技能是**个人资产**：这里选中的是「引用串」——个人资源存 {uid}/{name}，
  // 只对该资源的归属人生效（别人的同名资源不会被拿来顶替）；内置技能存裸名，对所有人按名字生效。
  // 没有独立的全局开关（配置里已无 skill.enabled），只受「工具调用」总闸制约。
  { key: 'toolSkill', group: '执行与交付', label: '技能 Skills', desc: '按需调用技能，正文逐字进提示词', icon: AppstoreOutlined,
    detail: '跟随系统=使用全部可用技能；指定=只注入勾选的技能',
    kind: 'list', modeKey: 'skillMode', listKey: 'skills', optionsKey: 'skillOptions',
    gate: ['tool', 'enabled'] }
]
// 能力分组（渲染顺序即此顺序；目标模式行单独渲染进「执行与交付」组头部）
const CAP_GROUPS = ['检索与知识', '工具与扩展', '执行与交付']
const capsOfGroup = g => CAPS.filter(c => c.group === g)
// 开关型三态：''=跟随系统默认 / '1'=开启 / '0'=关闭（对应后端 tool_* 的 1/0/null）
// 多实例能力：跟随系统默认 / 指定（选「指定」才展开具体项多选）/ 不使用
// 开关型的「跟随系统」段点名默认在哪一侧（gv 小字），默认值来自管理员侧的「系统设置」。
// 多实例能力不点名总开关开/关：MCP 的「跟随」是"挂个人全部、不含平台内置"，技能是"用全部"，
// 写成「（开）」会被读成"这一族已经挂上了"（2026-11-15 实测踩到）
// 记忆域三态（对外能力第二批）：''=跟随用户级记忆 1=专属 2=叠加；后端认 0/1/2/null
// 唯一不叫「跟随系统」的跟随档：它的默认值是用户自己的全局记忆，不是管理员侧的系统设置
const memoryModeOptions = [
  { label: '跟随用户级记忆', value: '' },
  { label: '专属（独立记忆，不注入用户级记忆）', value: '1' },
  { label: '叠加（独立记忆 + 用户级记忆只读打底）', value: '2' }
]
const globalSuffix = c => {
  if (c.kind === 'list') return ''
  const t = globalText(c)
  return t ? '（' + (t.startsWith('关闭') ? '关' : '开') + '）' : ''
}
// 分段控件：当前值 + 段列表；「跟随系统」段点名系统默认在哪一侧，
// 多实例「指定」段实时点名已选几项——状态、默认值、已选数全部不离开控件本身
const capSegValue = c => (c.kind === 'switch' ? form.value[c.key] : form.value[c.modeKey])
const capSegItems = c => (c.kind === 'switch'
  ? [{ v: '', label: '跟随系统', gv: globalSuffix(c) }, { v: '1', label: '开' }, { v: '0', label: '关' }]
  : [{ v: 'inherit', label: '跟随系统', gv: globalSuffix(c) },
    { v: 'pick', label: '指定', gv: (form.value[c.listKey] || []).length ? `${(form.value[c.listKey] || []).length} 项` : '' },
    { v: 'none', label: '不使用', gv: '' }])
// 有副作用工具（沙盒/MCP）执行审批：''=跟随权限档位的建议值（默认）；其余为显式覆盖
// 档位建议值是后端名册里的 approvalDefault（常规档不给建议值=直接执行），不在前端另写一份
const approvalOptions = computed(() => [
  { value: '', label: '跟随档位' + (approvalFollowLabel.value ? `（${approvalFollowLabel.value}）` : '') },
  { value: 'auto', label: '自动执行' },
  { value: 'ask', label: '执行前确认' },
  { value: 'off', label: '禁用' }
])
// 内置工具可选项**来自后端能力名册**（GET /agent/tool-catalog，工具注册中心的描述符）。
// 中文名有唯一来源，避免配置页里工具只剩英文名、或选了却不生效。
// 拉不到时显式报错 + 重试入口，不退回写死的常量表（那会让人以为名册就是这些）。
// ask_user 族只有 askUser 一项，配置面是「向用户提问」开关行，名册仅供黑名单选项用。

// 图标可选集（与后端 c_ai_agent.icon 口径一致）：''=默认机器人图标；'wenqu'=问渠品牌标；其余 emoji 原样存库、原样渲染
const ICON_OPTIONS = [
  { value: '', label: '默认图标' },
  { value: 'wenqu', label: '问渠品牌标' },
  { value: '🤖', label: '机器人' },
  { value: '🧠', label: '知识大脑' },
  { value: '💡', label: '点子' },
  { value: '📚', label: '资料' },
  { value: '⚖️', label: '法律' },
  { value: '📊', label: '报表' },
  { value: '✍️', label: '写作' },
  { value: '🔍', label: '检索' },
  { value: '🛠️', label: '工具' },
  { value: '💬', label: '客服' },
  { value: '🎯', label: '目标' },
  { value: '🧭', label: '导航' },
  { value: '📝', label: '笔记' },
  { value: '🌐', label: '全网' },
  { value: '⚡', label: '效率' }
]

const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const agents = ref([])
const kbOptions = ref([])
// 全局默认值（/agent/config-defaults，登录用户都能读）：所有「跟随默认」的控件都要在触发器上
// 点名它跟随的到底是什么值，避免界面只剩「跟随系统默认」五个字——要么猜，要么显式覆盖一份
// （覆盖一旦写下就不再跟随管理员后续调整）
const agentDefaults = ref(null)
const agentDefaultsError = ref(false)
// 管理员级身份：reload 里 ensureAuth 到位后刷新（localStorage 兜底仅在已拉取过时可靠）
const isAdmin = ref(isAdminSync())

// 工具黑名单选项（名册全族，含检索/联网/产物/技能/清单/沙盒；外部 MCP 工具靠手输）
const denyOptions = ref([])
// 名册加载失败标记：拉不动时不能给一个空下拉就完事（用户会读成「平台就这些工具」）
const toolCatalogError = ref(false)
const loadToolCatalog = async () => {
  try {
    const r = await getToolCatalog()
    const list = (r && r.success && Array.isArray(r.data)) ? r.data : null
    if (!list) { toolCatalogError.value = true; return }
    toolCatalogError.value = false
    // 工具黑名单的选项=名册<b>全族</b>（族名做前缀：不同族可能有同名工具，裸英文名分不清）。
    // 外部 MCP 工具没有静态声明、进不了名册，所以控件用 tags 模式允许手输名字——
    // 后端保存时会逐个核对名字存在（认不出直接拒），手输错不会静默不生效。
    denyOptions.value = list.map(t => ({
      value: t.name,
      label: (t.familyLabel ? t.familyLabel + ' · ' : '') + (t.label || t.cardLabel || t.name)
    }))
  } catch (e) { toolCatalogError.value = true }
}
// 权限档位可选项（来自 /agent/permission-presets）：档位表由后端 PermissionPreset 下发，这里不抄第二份——
// 抄了就等于界面上能选一个后端不给的档位（表现是「选了不生效且没任何提示」）。
const presetOptions = ref([])
const presetError = ref(false)
// 档位名册原样留着：下拉只吃 code/label，而「跟随档位」要点名这一档建议的审批值、
// 「跟随平台默认」要点名平台默认是哪一档——那两列（approvalDefault / platformDefault）后端已下发，
// 只是不进 options 对象，否则每个选项都带一份用不上的字段
const presetRaw = ref([])
const loadPermissionPresets = async () => {
  try {
    const r = await getPermissionPresets()
    const list = (r && r.success && Array.isArray(r.data)) ? r.data : null
    if (!list) { presetError.value = true; return }
    presetError.value = false
    presetRaw.value = list
    presetOptions.value = list.map(p => ({
      value: p.code,
      label: p.label,
      title: p.summary
    }))
  } catch (e) { presetError.value = true }
}
/** 当前生效的档位（没选就是平台默认档）对应的名册条目 */
const effectivePreset = computed(() => {
  const code = form.value.permissionPreset || defaultPresetCode.value
  return presetRaw.value.find(p => p.code === code) || null
})
/** 平台默认档（后端在名册里标出，含配置项写错时的回落，前端不再抄一份判定） */
const defaultPresetCode = computed(() => presetRaw.value.find(p => p.platformDefault)?.code || '')
const defaultPresetLabel = computed(() => presetRaw.value.find(p => p.platformDefault)?.label || '')
/** 留空即跟随平台默认档：点名是哪一档，用户不用去猜管理员把默认调成了什么 */
const presetPlaceholder = computed(() =>
  '跟随平台默认' + (defaultPresetLabel.value ? `（${defaultPresetLabel.value}）` : ''))
/** 审批三态的中文名（与 approvalOptions 同一份词表，档位建议值就是这个码） */
const APPROVAL_LABELS = { auto: '自动执行', ask: '执行前确认', off: '禁用' }
/** 「跟随档位」实际是什么：档位给了建议值就点名，常规档不给建议值=直接执行 */
const approvalFollowLabel = computed(() => {
  const p = effectivePreset.value
  if (!p) return ''
  return APPROVAL_LABELS[p.approvalDefault] || '自动执行'
})
/** 全局默认值（各「跟随默认」控件的点名来源） */
const loadAgentDefaults = async () => {
  try {
    const r = await getAgentConfigDefaults()
    if (!r || !r.success || !r.data) { agentDefaultsError.value = true; return }
    agentDefaults.value = r.data
    agentDefaultsError.value = false
  } catch (e) { agentDefaultsError.value = true }
}
// 当前选中的档位说明（后端下发的那句 summary，不在前端另写一份）
const presetHint = () => {
  const p = presetOptions.value.find(x => x.value === form.value.permissionPreset)
  return p ? (p.title || '') : ''
}
const skillOptions = ref([])
// 技能清单原样留着：「指定」下拉旁要给每条已选技能一个「看正文」的出口，靠这份数据取来源与停用态
const skillRaw = ref([])
const skillDoc = ref('')
const SKILL_SOURCE_SUFFIX = { builtin: '（内置）', user: '（我的）' }
const applySkillList = list => {
  skillRaw.value = list
  const seen = new Set()
  skillOptions.value = list
    // 工作区技能随会话走，钉进智能体配置会留下一个换个会话就解析不出来的名字
    .filter(s => s.selectable !== false)
    .filter(s => {
      const v = s.ref || s.name
      if (seen.has(v)) return false
      seen.add(v)
      return true
    })
    .map(s => ({
      value: s.ref || s.name,
      label: s.name + (SKILL_SOURCE_SUFFIX[s.source] || ''),
      source: s.source
    }))
}
/** 停用/恢复之后就地重拉这份清单（不整页 reload：那会把正在编辑的表单所在的面板闪成加载态） */
const reloadSkillOptions = () => listAvailableSkills()
    .then(r => { if (r && r.success && Array.isArray(r.data)) applySkillList(r.data) })
    .catch(() => {})
/** 「指定」里存的是引用串（个人技能带 {uid}/ 前缀），取回裸名；条目按名字现取，快照会停在改动前那份 */
const openSkillDoc = refStr => { skillDoc.value = String(refStr || '').split('/').pop() }
const skillDocEntry = computed(() => skillRaw.value.find(s => s.name === skillDoc.value) || {})
const mcpOptions = ref([])
// 可委派的子智能体下拉（4.3：来自 /agent/sub，仅作为主智能体的委派候选，本身不参与对话）
const subOptions = ref([])
/** 工作流工具与可委派选项共用本页数据 */
const OPTION_REFS = { skillOptions, mcpOptions }

const editing = ref(false)
const editingId = ref('')
// 正在编辑的是平台预置助手（全平台共享单行）：名称/图标/用途/提示词随产品固定，只有配置项可改
const editingPreset = ref(false)
// 侧栏「最近保存」：列表接口带回的更新时间（新建时为空，不显示该卡）
const editingUpdatedAt = ref('')
const scopeMode = ref('all')
// 编辑视图的运行方式：main=问答助手 / sub=可被委派。
// 它只影响「哪些配置参与」与保存时写回 isSubagent，不新增后端字段。
const runMode = ref('main')
/** 身份字段锁定：内置「问渠」与平台预置行的名称/图标/用途/提示词随产品固定 */
const identityLocked = computed(() => form.value.isBuiltin === 1 || editingPreset.value)
// 「专家选项」折叠（原生 details 的 open 态），默认收起
const advOpen = ref(false)
const iconOpen = ref(false)
const scopeOptions = [
  { value: 'all', label: '全部知识库', hint: '能检索所有资料' },
  { value: 'pick', label: '指定知识库', hint: '只查下面这几个' },
  { value: 'none', label: '不使用', hint: '仅凭提示词作答' }
]
/** 检索文档类型（值与 c_ai_document.file_type 一致，后端保存期按同一白名单校验） */
const KB_FILE_TYPE_OPTIONS = [
  { value: 'pdf', label: 'PDF' },
  { value: 'docx', label: 'Word' },
  { value: 'xlsx', label: 'Excel' },
  { value: 'pptx', label: 'PPT' },
  { value: 'url', label: '网页' },
  { value: 'txt', label: '纯文本' },
  { value: 'md', label: 'Markdown' },
  { value: 'csv', label: 'CSV' }
]
/** 最近更新（按文档更新时间；与后端 updatedWithinDays 同义） */
const KB_UPDATED_OPTIONS = [
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 90, label: '近 90 天' },
  { value: 365, label: '近一年' }
]
const blankForm = () => ({
  name: '', description: '', systemPrompt: '', knowledgeBaseIds: [], knowledgeTags: [],
  // 检索文档条件：类型多选 + 最近更新天数（null=不限）；保存时合成 knowledgeFilter JSON
  knowledgeFileTypes: [], knowledgeUpdatedDays: null,
  isDefault: false,
  // 启停状态（0=启用 1=停用）：配置页的开关走独立的「立即生效」接口，不随保存提交
  disabled: 0,
  // 图标：''=默认展示（内置「问渠」用问渠品牌标，其余机器人）；'wenqu'=品牌标；emoji=表情
  icon: '', isBuiltin: 0,
  // 开关型：'' = 跟随系统默认 / '1' = 开启 / '0' = 关闭
  toolKnowledge: '', toolBuiltin: '', toolSkill: '', toolArtifact: '', toolMcp: '', toolWebsearch: '',
  toolHttp: '',  // HTTP 工具集（数组，HttpToolsEditor 双向绑定；保存整包提交、后端归一+凭据加密）
  httpTools: [],
  toolWorkflow: '',  // 工作流工具集（数组，WorkflowToolsEditor 双向绑定；保存整包提交、后端归一校验）
  workflowTools: [],
  // 轮终回调（v2 对外能力第二批）：URL 明文；密钥不回显（callbackSecretSet 只表「已配置」）
  callbackUrl: '', callbackSecret: '', callbackSecretSet: false,
  memoryMode: '',  // ''=跟随用户级记忆 / '1'=专属 / '2'=叠加（对外能力第二批·agent 级记忆）
  // 深度执行（资源级二态）：1=开 0=关。平台级总闸已去掉，这一格是唯一判据。
  goalEnabled: 1,
  // 有副作用工具（沙盒/MCP）执行审批：''=跟随档位（默认） auto=自动执行 ask=执行前确认 off=禁用
  toolApprovalMode: '',
  // 权限档位：null=跟随平台默认档（standard）；可选项来自 /agent/permission-presets
  permissionPreset: null,
  // 工具黑名单：本轮要拿掉的具体工具名（多选，选项=能力名册全族）；空=不禁用
  toolDeny: [],
  // 单轮工具调用步数上限：null=跟随系统默认当前值（agent.maxToolSteps）；0=不限制
  maxToolSteps: null,
  // 多实例能力：模式（inherit/none/pick）+ 选「指定」时的具体项
  skillMode: 'inherit', skills: [],
  mcpMode: 'inherit', mcps: [],
  // 新增时必须给出默认值：save() 会直接读这两个字段，缺失会让整个保存动作抛错
  isSubagent: 0, subAgentIds: [],
  // 检索开关覆盖：只有「强制关」一种覆盖语义（重排=库绑了模型才重排，智能体只能关、不能开；
  // 阈值/权重/召回量归知识库，不在这里配）
  qpRerank: 'inherit'
})
/** 重排两态：跟随（库绑了模型才重排）/ 强制关 */
const rerankOptions = computed(() => [
  { label: '跟随（绑了模型才重排）', value: 'inherit' },
  { label: '强制关', value: 'off' }
])

/**
 * queryParams(JSON 串) → 重排两态。仅显式 "false" 是强制关；存量 JSON 里的 "true"
 * （旧「开启」语义）视为跟随——重排已无全局开关可强制开启。阈值/权重等旧键即使残留
 * 也不再读取（后端同样忽略，保存时清除）——读进来只会让用户以为这里还能覆盖库参数。
 */
function parseQueryParams (json) {
  let ps = null
  try { ps = json ? JSON.parse(json) : null } catch (e) { ps = null }
  if (ps && ps['rerank.enabled'] !== undefined && ps['rerank.enabled'] !== null) {
    return String(ps['rerank.enabled']) === 'false' ? 'off' : 'inherit'
  }
  return 'inherit'
}

/** 重排两态 → queryParams(JSON 串)；跟随返回空串（后端存 null = 跟随） */
function buildQueryParams (rr) {
  if (rr === 'off') return JSON.stringify({ 'rerank.enabled': 'false' })
  return ''
}

const form = ref(blankForm())

// 「检索标签收窄」候选 = 所选知识库内文档已打的标签（打标在文档管理页做）。
// 标签是多选下拉里唯一能选的数据源，不拉候选就是一个永远「暂无数据」的空框。
// 走已有的 /document/list?kbId=（线上已在运行），候选不依赖后端发版。
const kbTagOptions = ref([])
const kbTagFetchFailed = ref(false)
let kbTagSeq = 0
async function loadKbTagOptions (ids) {
  const seq = ++kbTagSeq
  const picked = (ids || []).filter(Boolean)
  if (!picked.length) { kbTagOptions.value = []; kbTagFetchFailed.value = false; return }
  const rs = await Promise.all(picked.map(id => listDocuments(id).catch(() => null)))
  if (seq !== kbTagSeq) return
  const tags = new Set()
  let failed = true
  for (const r of rs) {
    if (!r || !r.success || !Array.isArray(r.data)) continue
    failed = false
    for (const d of r.data) for (const t of String(d.tags || '').split(',')) if (t.trim()) tags.add(t.trim())
  }
  kbTagOptions.value = [...tags].sort().map(t => ({ value: t, label: t }))
  kbTagFetchFailed.value = failed
}
watch(() => [scopeMode.value, (form.value.knowledgeBaseIds || []).join(',')], ([mode, csv]) => {
  if (mode !== 'pick') { kbTagSeq++; kbTagOptions.value = []; return }
  loadKbTagOptions(csv ? csv.split(',') : [])
}, { immediate: true })

/**
 * 内置「问渠」是全租户共享行：只能引用平台内置资源。
 * 个人 MCP / 个人技能带归属人，写进共享行后其他成员用不了，属于假生效。
 */
const optionsOf = c => {
  const all = OPTION_REFS[c.optionsKey]?.value || []
  if (form.value.isBuiltin !== 1) return all
  if (c.key === 'toolMcp') return all.filter(o => o.scope === 'platform')
  if (c.key === 'toolSkill') return all.filter(o => o.source === 'builtin')
  return all
}
/** 子智能体同理：内置问渠只列平台级（内置/平台预置）子智能体，个人子智能体不写进共享行 */
const visibleSubOptions = computed(() => {
  if (form.value.isBuiltin !== 1) return subOptions.value
  const platformIds = new Set(agents.value
    .filter(a => a.isBuiltin === 1 || a.isBuiltin === true || a.createdBy === 'system')
    .map(a => a.id))
  return subOptions.value.filter(o => platformIds.has(o.value))
})

// 系统提示词 md 编辑器（md-editor-v3）：提示词一般是 markdown（组件契约由 npm run spec 生成），
// 主体工作是「整份粘贴 + 校对」，不是从零写作——工具栏只留基础排版与撤销，图片上传/图表/公式
// 这些会把 base64 或大段语法塞进纯文本契约的项一律不要；预览与全屏保留（356 行长文档要能看能铺开）。
const mdToolbars = ['bold', 'underline', 'italic', '-', 'strikeThrough', 'quote', 'unorderedList', 'orderedList',
  '-', 'code', 'codeRow', 'table', '-', 'revoke', 'next', '=', 'preview', 'previewOnly', 'pageFullscreen', 'fullscreen']
const mdTheme = computed(() => (themeState.value === 'dark' ? 'dark' : 'light'))

// 布局偏好跟人走：记 localStorage，下次打开这页保持（编辑=全宽书写，分屏=边写边看，预览=纯阅读）。
// :key 带 promptPane 是因为编辑器的 preview 等只认初始值，换档重挂一次最稳（内容在 v-model 里不丢）。
const PROMPT_PANE_KEY = 'wenqu-agent-prompt-pane'
const promptPane = ref(['edit', 'split', 'preview'].includes(localStorage.getItem(PROMPT_PANE_KEY))
  ? localStorage.getItem(PROMPT_PANE_KEY) : 'split')
watch(promptPane, v => localStorage.setItem(PROMPT_PANE_KEY, v))

// 运行方式：决定页面里哪些配置参与，以及保存时 isSubagent 怎么写。
const runModeOptions = computed(() => [
  { label: '问答助手', value: 'main', disabled: editingPreset.value },
  { label: '可被委派', value: 'sub', disabled: form.value.isBuiltin === 1 }
])
const runModeHint = computed(() => {
  if (editingPreset.value) return '平台预置助手的用途随产品固定：全平台一份，作为子智能体供主智能体委派。'
  if (runMode.value === 'sub') return '不能直接选用，只能被主智能体委派去查资料或办事。'
  return '对话页可直接选用；人设、资料与能力都按本页配置生效。'
})
const modeChip = computed(() => runMode.value === 'sub' ? '可被委派' : '')
const onRunModeChange = v => {
  // 平台预置固定为子智能体；内置「问渠」固定为主智能体——即便分段控件被绕过也不让它改身份
  if (editingPreset.value) { runMode.value = 'sub'; form.value.isSubagent = 1; return }
  if (v === 'sub' && form.value.isBuiltin === 1) { runMode.value = 'main'; return }
  form.value.isSubagent = v === 'sub' ? 1 : 0
}
// 页头星标：已存在的非子智能体、且管理员才有「设为默认」能力（与列表卡片同一判据）；
// 平台预置是能力型助手，不能设为全员默认
const canShowStar = computed(() => {
  if (!editingId.value || !isAdmin.value || form.value.isSubagent || form.value.disabled === 1) return false
  const a = agents.value.find(x => String(x.id) === String(editingId.value))
  return !!a && !isPreset(a)
})

// 图标可选集：问渠品牌标为内置「问渠」专属（后端同样强制），非内置智能体的选项里不出现。
// 内置问渠整块「图标」已隐藏，这里恒走过滤分支
const iconOptions = computed(() => ICON_OPTIONS.filter(o => o.value !== 'wenqu'))

// 判定/转换纯函数见 ../agents/agentBasics（本页与各 composable 共用同一口径）
/** 搜索覆盖卡片上能看到的一切维度：名称、描述、能力摘要、资料范围、工作流名、MCP/技能/知识库名 */
const matchKw = a => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return true
  if (String(a.name || '').toLowerCase().includes(kw) || String(a.description || '').toLowerCase().includes(kw)) return true
  const hay = [
    capSummary(a), scopeText(a),
    ...splitList(a.mcps).map(r => String(r).split('/').pop()),
    ...splitList(a.skills).map(r => String(r).split('/').pop()),
    ...splitList(a.knowledgeBaseIds).map(id => kbOptions.value.find(k => String(k.value) === String(id))?.label || '')
  ].join(' ').toLowerCase()
  return hay.includes(kw)
}

// ==================== 列表筛选与排序 ====================
const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'main', label: '主智能体' },
  { key: 'sub', label: '子智能体' },
  { key: 'builtin', label: '内置' }
]
const filter = ref('all')
const sortBy = ref('updated')
const matchFilter = a => filter.value === 'all'
  || (filter.value === 'main' && !isSub(a))
  || (filter.value === 'sub' && isSub(a))
  || (filter.value === 'builtin' && (isBuiltin(a) || isPreset(a)))
/** 分段上的计数按全量算（不随搜索缩水）：它是「这类有几个」的清点，不是搜索结果统计 */
const typeCounts = computed(() => ({
  all: agents.value.length,
  main: agents.value.filter(a => !isSub(a)).length,
  sub: agents.value.filter(a => isSub(a)).length,
  builtin: agents.value.filter(a => isBuiltin(a) || isPreset(a)).length
}))
const sortAgents = list => {
  const arr = list.slice()
  if (sortBy.value === 'name') arr.sort((x, y) => String(x.name || '').localeCompare(String(y.name || ''), 'zh'))
  else arr.sort((x, y) => String(y.updateTime || y.createTime || '').localeCompare(String(x.updateTime || x.createTime || '')))
  return arr
}
/**
 * 列表分组：主智能体一组，子智能体单独一组，互不混排。
 * 类型筛选 + 搜索同时作用于所有组，过滤后为空的组不显示。
 */
const sections = computed(() => {
  const pool = agents.value.filter(a => matchFilter(a) && matchKw(a))
  const secs = []
  const mains = sortAgents(pool.filter(a => !isSub(a)))
  if (mains.length) {
    secs.push({ key: 'main', title: '主智能体', hint: '可在对话页直接选用', members: mains })
  }
  const subs = sortAgents(pool.filter(isSub))
  if (subs.length) {
    secs.push({ key: 'sub', title: '子智能体', hint: '不直接参与对话，供主智能体并行委派', members: subs })
  }
  return secs
})
const scopeText = a => {
  // 内置「问渠」：固定检索使用者的默认库「问渠」＋官方使用手册库（默认库每人一个，运行时按使用者解析，无静态绑定）
  if (isBuiltin(a)) return '「问渠」知识库＋使用手册'
  if (a.knowledgeDisabled === 1 || a.knowledgeDisabled === true) return '不使用知识库'
  const n = String(a.knowledgeBaseIds || '').split(',').filter(Boolean).length
  if (!n) return '全部知识库'
  return n === 1 ? '限 1 个知识库' : `限 ${n} 个知识库`
}

// ==================== 卡片 meta 行（资料 / 能力 / 委派 / 更新一眼可辨） ====================
/**
 * 能力摘要：只列显式开启的项（跟随系统的不占位置）；MCP 与技能给数量。
 * 一项都没有覆盖时明说「能力跟随系统」——空白会被读成「没能力」，而不是「全跟随」。
 */
const capSummaryParts = a => {
  const parts = []
  for (const c of CAPS) {
    if (a[c.key] !== 1) continue
    if (c.key === 'toolMcp') {
      const n = splitList(a.mcps).length
      if (n) parts.push(`MCP ${n}`)
    } else if (c.key === 'toolSkill') {
      const n = splitList(a.skills).length
      if (n) parts.push(`技能 ${n}`)
    } else parts.push(c.label)
  }
  if (a.goalEnabled === 0) parts.push('深度执行关')
  return parts
}
const capSummary = a => {
  const parts = capSummaryParts(a)
  if (!parts.length) return '能力跟随系统'
  return parts.length > 3 ? parts.slice(0, 3).join(' · ') + ` 等 ${parts.length} 项` : parts.join(' · ')
}
const capSummaryTitle = a => {
  const parts = capSummaryParts(a)
  return parts.length ? '显式开启的能力：' + parts.join('、') : '能力全部跟随系统默认'
}
/** 委派关系外显：主卡说委派了几个，子卡说被谁委派（数据来自已加载列表，与拓扑视图同源） */
const parentsMap = computed(() => {
  const map = {}
  for (const m of agents.value) {
    if (isSub(m)) continue
    for (const sid of splitList(m.subAgentIds)) (map[String(sid)] = map[String(sid)] || []).push(m.name || m.id)
  }
  return map
})
const delegText = a => {
  if (isSub(a)) {
    const ps = parentsMap.value[String(a.id)] || []
    if (!ps.length) return '暂无人委派'
    return ps.length === 1 ? `被「${ps[0]}」委派` : `被「${ps[0]}」等 ${ps.length} 位委派`
  }
  const n = splitList(a.subAgentIds).length
  return n ? `委派 ${n} 个子智能体` : ''
}
/** 相对时间：卡片只回答「多久没动过」，精确时刻在 title 悬浮里 */
const fmtAgo = t => {
  if (!t) return ''
  const d = new Date(String(t).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return ''
  const s = (Date.now() - d.getTime()) / 1000
  if (s < 60) return '刚刚'
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`
  if (s < 86400 * 30) return `${Math.floor(s / 86400)} 天前`
  if (s < 86400 * 365) return `${Math.floor(s / (86400 * 30))} 个月前`
  return `${Math.floor(s / (86400 * 365))} 年前`
}
const updatedAgo = a => {
  const r = fmtAgo(a.updateTime || a.createTime)
  return r ? `${r}更新` : ''
}
/** 状态点只给「发布与否在自己手里」的卡片：子智能体不参与直接对话，发布语义不适用 */
// 平台预置不支持公开发布，不显示发布状态点（灰点会被读成「还没发，可以发」）
const showPubDot = a => a.manageable && !isSub(a) && !isPreset(a) && !isDisabledAgent(a)
const canSetDefault = a => !isSub(a) && !isDefault(a) && isAdmin.value && !isPreset(a) && !isDisabledAgent(a)
/** 试一试：直达对话页并预选该智能体（对话页消费 ?agent= 后摘掉参数） */
const tryAgent = a => router.push({ path: '/chat', query: { agent: String(a.id) } })

// ==================== 复制 / 导出 / 导入（前端整包搬运，创建仍走既有保存链路） ====================
/** 导出只带配置面字段：id/发布态/管理权等运行时信息带出去只会误导导入方 */
const EXPORT_KEYS = ['name', 'icon', 'description', 'systemPrompt', 'knowledgeBaseIds', 'knowledgeTags', 'knowledgeFilter', 'knowledgeDisabled',
  'toolKnowledge', 'toolBuiltin', 'toolArtifact', 'toolWebsearch', 'toolHttp', 'httpTools',
  'toolWorkflow', 'workflowTools', 'toolMcp', 'mcps', 'toolSkill', 'skills',
  'goalEnabled', 'toolApprovalMode', 'permissionPreset', 'toolDeny', 'maxToolSteps', 'memoryMode',
  'callbackUrl', 'queryParams', 'isSubagent', 'subAgentIds']
const agentExportConfig = a => Object.fromEntries(EXPORT_KEYS.filter(k => a[k] !== undefined).map(k => [k, a[k]]))
const downloadJson = (obj, filename) => {
  const blob = new Blob([JSON.stringify(obj, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const el = document.createElement('a')
  el.href = url
  el.download = filename
  el.click()
  URL.revokeObjectURL(url)
}
const exportAgent = a => downloadJson({ wenquAgentExport: 1, exportedAt: new Date().toISOString(), config: agentExportConfig(a) },
  `智能体-${a.name || a.id}.json`)
const doBatchExport = () => {
  const rows = agents.value.filter(x => selected.value.includes(x.id))
  if (!rows.length) return
  downloadJson({ wenquAgentExport: 1, exportedAt: new Date().toISOString(),
    agents: rows.map(a => agentExportConfig(a)) }, '智能体配置导出.json')
  message.success(`已导出 ${rows.length} 个智能体配置`)
}
/** 复制 = 以该智能体的配置开一张新建表单：保存即创建，公开链接与默认位不随副本走 */
const copyAgent = a => {
  createOpen.value = false
  openEdit(a)
  editingId.value = ''
  editingPreset.value = false
  editingUpdatedAt.value = ''
  form.value.name = `${a.name || '未命名'}-副本`
  form.value.isDefault = false
  form.value.disabled = 0
  // 内置标记驱动表单的名称/图标锁定与保存裁剪——复制产物是普通新建智能体，必须清掉；
  // 问渠品牌标是内置「问渠」专属图标，副本改回默认图标
  form.value.isBuiltin = 0
  if (isBuiltin(a) && form.value.icon === 'wenqu') form.value.icon = ''
}
const confirmDelete = a => Modal.confirm({
  title: '删除该智能体？',
  content: `「${a.name}」将从对话页可选项中移除；如有公开链接同时失效，配置历史版本一并删除。`,
  okText: '删除', okType: 'danger', cancelText: '取消',
  onOk: () => doDelete(a.id)
})

// ==================== 新建入口：模板先行 ====================
const createOpen = ref(false)
const importInput = ref(null)
const openCreateEntry = () => { createOpen.value = true }
const startBlank = () => { createOpen.value = false; openCreate() }
/** 可复制 = 有管理权的智能体（含内置：管理员能把它当模板，产物是自己的普通智能体） */
const copyableAgents = computed(() => agents.value.filter(a => a.manageable))
const pickImportFile = () => importInput.value && importInput.value.click()
const onImportFile = ev => {
  const f = ev.target.files && ev.target.files[0]
  ev.target.value = ''
  if (!f) return
  const rd = new FileReader()
  rd.onload = () => {
    let data = null
    try { data = JSON.parse(String(rd.result)) } catch (e) { message.error('不是有效的 JSON 文件'); return }
    const cfg = data && data.wenquAgentExport === 1
      ? (data.config || (Array.isArray(data.agents) ? data.agents[0] : null))
      : null
    if (!cfg || !cfg.name) { message.error('文件里没有可识别的智能体配置（请用「导出 JSON」或「批量导出」生成的文件）'); return }
    createOpen.value = false
    // 导入走与复制同一条路径：把配置载入新建表单，用户检查后再保存
    openEdit({ ...cfg, isBuiltin: 0 })
    editingId.value = ''
    editingPreset.value = false
    editingUpdatedAt.value = ''
    form.value.isDefault = false
    form.value.disabled = 0
    message.info('配置已载入，确认后点「保存」即创建为新智能体')
  }
  rd.readAsText(f)
}

// ==================== 委派编排视图（见 ../agents/useDelegationTopo） ====================
const {
  topoOpen, topoMode, topoQuery, topoFilter, topoFocusId, topoHover, topoListEl, topoScrollTop, TOPO,
  topoMains, topoSubs, topoRows, topoParentCount, topoSummary, topoOrphanNames, topoOrphanTitle,
  topoFilters, topoFiltered, onTopoScroll, topoWin, topoFocus, topoMainOptions, openTopo, focusTopo,
  topoGraph, subMeta, trunc, MAIN_NAME, MAIN_LINE, SUB_NAME, SUB_LINE, toWfLoading, toWorkflow, edgeCls, nodeCls
} = useDelegationTopo({ agents, editingId, route, router })

// ==================== 公开发布（见 ../agents/useAgentPublish） ====================
const {
  pubVisible, pubAgentId, pubAgentName, pubAgent, pubEnabled, pubMcpEnabled,
  pubAgentApprovalOff, pubModelRef, pubToken, pubGuestImages, pubGuestFiles,
  pubVariables, pubGuestMcp, myMcpServerChoices, myMcpServers, modelIndex,
  pubModelVisionOk, pubVisionWarn, pubVisitCount, pubLastVisitAt, pubTab,
  pubFrameW, pubFrameH, shareUrl, mcpUrl, mcpJson, iframeSnippet,
  applyPubDeepLink, openPublish, savePublish, saveMcpChecks, doRevoke
} = useAgentPublish({ agents, route, router })


// ==================== 全局默认值（用于把每个「跟随默认」点名成具体值） ====================
/**
 * 取某个全局键的当前值。
 * 端点下发的是「配置键 → 值」的扁平表，所以路径按 '.' 拼接后直接取
 * （['tool','enabled'] → `tool.enabled`）——与配置项在后端的键名逐字一致，不再有两套寻址形状。
 */
const rawOf = path => {
  if (!agentDefaults.value || !Array.isArray(path) || !path.length) return undefined
  const flat = agentDefaults.value[path.join('.')]
  return flat === undefined ? undefined : String(flat)
}
const isOn = path => rawOf(path) === 'true'
/**
 * 单轮工具步数上限的当前全局值（'' = 没拿到）。
 * 拿不到时不能回落到种子值 15——管理员把它调成 50 之后，placeholder 说「跟随全局（15）」
 * 就是当着用户的面谎报，而这一格的表现是"步数到 15 就停"这种要回头查半天的坑。
 */
const globalMaxToolSteps = computed(() => {
  const v = rawOf(['agent', 'maxToolSteps'])
  if (v === undefined || v === null || v === '') return ''
  const n = Number(v)
  return Number.isFinite(n) ? n : ''
})
/** 步数选框的占位文案：拿得到值就点名，拿不到就只说跟随系统默认 */
const maxToolStepsPh = computed(() =>
  globalMaxToolSteps.value === '' ? '跟随系统默认' : `跟随系统默认（${globalMaxToolSteps.value}）`)
/**
 * 能力行的「全局开/关」提示。
 * 拿不到默认值（端点失败）→ 返回空串，模板据此不显示该提示；
 * 之前无条件回落到 isOn()=false 会显示成「关闭」，与真实全局状态相反。
 */
const globalText = c => {
  if (!agentDefaults.value) return ''
  if (c.gate && !isOn(c.gate)) return '关闭（工具总开关未开）'
  // 无独立全局开关的能力（技能 / MCP：内容归个人，只受工具总闸制约）
  if (!c.path) return '开启'
  const v = rawOf(c.path)
  if (v === undefined || v === null || v === '') return ''
  return isOn(c.path) ? '开启' : '关闭'
}

// ==================== 生效摘要（实时随表单变化） ====================
/** 能力当前状态：开关型看三态值，多实例看模式（'1'/'pick'=开，'0'/'none'=关，其余=跟随全局） */
const capState = c => (c.kind === 'switch' ? form.value[c.key] : form.value[c.modeKey])
const capOn = c => { const v = capState(c); return v === '1' || v === 'pick' }
const capOff = c => { const v = capState(c); return v === '0' || v === 'none' }
/**
 * 开关型能力「跟随系统」的取值（'1'/'0'；'' = 默认值没拿到，端点失败或无此配置键）。
 * 与能力行「跟随系统」段的小字同口径（globalText 为唯一来源），保证徽标和它旁边写的默认值不自相矛盾。
 */
const capFollowTri = c => {
  const t = globalText(c)
  if (!t) return ''
  return t.startsWith('关闭') ? '0' : '1'
}
/**
 * 是否与系统默认不同：比生效值，不比「库里有没有显式值」。
 * 显式 1 而系统默认也是开 = 同一结果，不算不同；默认值拿不到时退回「显式即算」，
 * 宁可多报一处，也不能把真覆盖漏掉。多实例能力没有可比的单一全局值（跟随=全挂/全部技能），
 * 指定与不使用都算显式收窄。
 */
const capOverridden = c => {
  const v = capState(c)
  if (c.kind !== 'switch') return capOn(c) || capOff(c)
  if (v !== '1' && v !== '0') return false
  const d = capFollowTri(c)
  return !d || d !== v
}
/** 多实例能力是否处于「指定」模式（决定要不要展开具体项多选） */
const needsPick = c => c.kind === 'list' && form.value[c.modeKey] === 'pick'
/** 能力行的状态控件：开关型写三态列，多实例写模式列 */
const setCapState = (c, v) => {
  if (c.kind === 'switch') form.value[c.key] = v
  else form.value[c.modeKey] = v
}
const capsOverriddenCount = computed(() => CAPS.filter(c => capOverridden(c)).length + (form.value.goalEnabled === 0 ? 1 : 0))

const recentSavedText = computed(() => {
  const t = fmtTime(editingUpdatedAt.value)
  return t ? t.slice(0, 16) : '尚未保存'
})
const summaryScope = computed(() => {
  if (form.value.isBuiltin === 1) return '「问渠」知识库'
  if (scopeMode.value === 'none') return '不使用知识库'
  if (scopeMode.value !== 'pick') return '全部知识库'
  const n = (form.value.knowledgeBaseIds || []).length
  return n ? `指定 ${n} 个知识库` : '未选知识库'
})
/** 能力摘要只列与系统默认不同的项（与徽标同一判据）；没有任何差异时说明其余跟随全局 */
const summaryCapsList = computed(() => {
  const goal = form.value.goalEnabled === 0 ? '深度执行关闭' : '深度执行'
  const parts = []
  for (const c of CAPS) {
    if (!capOverridden(c)) continue
    if (capOn(c)) {
      if (c.kind === 'list' && form.value[c.modeKey] === 'pick') {
        const n = (form.value[c.listKey] || []).length
        // 「指定」但一项没选，保存时会按「不使用」落库，摘要里不能算开启
        if (!n) continue
        parts.push(`${c.label}（${n} 个）`)
      } else parts.push(c.label)
    } else if (capOff(c)) {
      parts.push(`${c.label} 关闭`)
    }
  }
  if (!parts.length) return `${goal} · 其余跟随系统默认`
  return [goal, ...parts].join(' · ')
})
const presetLabelOf = () => {
  if (!form.value.permissionPreset) return defaultPresetLabel.value ? `平台默认档（${defaultPresetLabel.value}）` : '平台默认档'
  return presetOptions.value.find(x => x.value === form.value.permissionPreset)?.label || form.value.permissionPreset
}
const approvalLabelOf = () => {
  const v = form.value.toolApprovalMode || ''
  return approvalOptions.value.find(x => x.value === v)?.label || '跟随档位'
}
const summaryExecution = computed(() => {
  return `${presetLabelOf()} · ${approvalLabelOf()}`
})
const summaryDelegation = computed(() => {
  if (runMode.value === 'sub') return '被委派：跑完整问答，能办事'
  const n = (form.value.subAgentIds || []).length
  return n ? `${n} 个子智能体` : '不限'
})
/** 高级设置的覆盖数（与右侧摘要同口径：记忆域/轮终回调历史上不计入差异数，维持不变） */
const advOverriddenCount = computed(() => {
  let n = 0
  if (form.value.qpRerank !== 'inherit') n++
  if (form.value.permissionPreset) n++
  if (form.value.toolApprovalMode) n++
  if ((form.value.toolDeny || []).length) n++
  if (form.value.maxToolSteps != null && form.value.maxToolSteps !== '') n++
  if (runMode.value === 'main' && (form.value.subAgentIds || []).length) n++
  return n
})
/** 与系统默认不同的显式覆盖数：能力 + 高级设置（不含资料范围/提示词等本页主配置） */
const overrideCount = computed(() => capsOverriddenCount.value + advOverriddenCount.value)
/** 一键把「能力 + 高级设置」恢复为跟随系统默认；资料范围与提示词不动 */
const resetOverrides = () => {
  Modal.confirm({
    title: '恢复跟随系统默认？',
    content: '会清空本页的能力覆盖、执行确认、工具黑名单、步数与委派设置，全部改回跟随系统默认；资料范围与提示词不受影响。',
    okText: '恢复',
    cancelText: '取消',
    onOk: () => {
      form.value.goalEnabled = 1
      for (const c of CAPS) {
        if (c.kind === 'switch') form.value[c.key] = ''
        else { form.value[c.modeKey] = 'inherit'; form.value[c.listKey] = [] }
      }
      form.value.qpRerank = 'inherit'
      form.value.permissionPreset = null
      form.value.toolApprovalMode = ''
      form.value.toolDeny = []
      form.value.maxToolSteps = null
      form.value.subAgentIds = []
      form.value.memoryMode = ''
    }
  })
}


// ==================== 数据加载 ====================
/** 只重拉智能体清单、不翻 loading：启停这类原地动作后整页会闪一次骨架屏，看不出改了什么又丢了位置 */
const refreshAgentsQuiet = async () => {
  try {
    const ar = await listAgents()
    if (!ar || !ar.success || !Array.isArray(ar.data)) return
    agents.value = ar.data
    // 与 reload 同一口径：已被删掉的 id 从批量勾选集合里清掉
    const alive = new Set(agents.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
  } catch (e) { /* 静默：列表停在旧数据，下次刷新会补齐 */ }
}

const reload = async () => {
  loading.value = true
  try {
    // 身份先就位：本页多处按管理员身份决定给不给入口（设为默认、删除内置等）
    const me = await ensureAuth()
    isAdmin.value = me.admin
    // 全局默认值走 /agent/config-defaults（登录用户可读），不再按身份分叉拉管理端的 /config
    const [ar, dr, sr, mr, xr] = await Promise.all([
      listAgents(), listKnowledgeBases(), listAvailableSkills().catch(() => null), getMcpStatus(), listSubAgents(),
      // 能力名册：结果写进 denyOptions / toolCatalogError，失败不阻断本页其余加载
      loadToolCatalog(),
      // 权限档位名册：同上，失败写 presetError，界面上给重试入口
      loadPermissionPresets(),
      // 全局默认值：同上，失败写 agentDefaultsError，界面上给重试入口
      loadAgentDefaults()
    ])
    if (ar.success && ar.data) agents.value = ar.data
    // 批量勾选与现存列表对账：已被删掉的 id 从选中集合里清掉（避免批量操作撞「不存在」）
    const alive = new Set(agents.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
    if (dr.success && dr.data) {
      const list = Array.isArray(dr.data) ? dr.data : (dr.data.list || [])
      kbOptions.value = list.map(k => ({
        value: k.id,
        // 选中后的标签只留库名：带文档数的长标签会把多选输入框撑成两三行
        label: k.name,
        title: k.docCount != null ? `${k.name}（${k.docCount} 个文档）` : k.name
      }))
    }
    // 技能与 MCP Server 的可选项（供「指定」模式下的多选）
    // /skill/available 直接给数组（只读清单，管理动作已由框架技能工具承担）
    // value 存**引用串**而非裸名：个人技能是个人资产，{uid}/{name} 才能精确绑定到归属人，
    // 避免别人用同名但内容不同的资源时被张冠李戴；内置技能后端给的 ref 就是裸名（人人都有）。
    if (sr && sr.success && Array.isArray(sr.data)) applySkillList(sr.data)
    if (mr && mr.success && mr.data) {
      mcpOptions.value = (mr.data.servers || []).map(s => ({
        value: s.ref || s.name,
        label: s.name + (s.connected ? '' : '（未连接）'),
        scope: s.scope
      }))
      // 发布弹窗「游客可用 MCP 工具」的候选池：只留已启用的 server（未启用的后端发布校验也不认）；
      // 具体勾选项等 openPublish 拿到智能体自己的 MCP 配置后再收窄
      myMcpServers.value = (mr.data.servers || [])
        .filter(s => s.enabled)
        .map(s => ({ name: s.name, connected: !!s.connected }))
    }
    // 可委派的子智能体
    if (xr && xr.success && Array.isArray(xr.data)) {
      subOptions.value = xr.data.map(s => ({
        value: s.id,
        label: s.name
      }))
    }
  } catch (e) { message.error(e.message || '加载失败') }
  finally { loading.value = false }
}

// ==================== 列表操作 ====================
const openCreate = () => {
  editingId.value = ''
  editingPreset.value = false
  editingUpdatedAt.value = ''
  form.value = blankForm()
  scopeMode.value = 'all'
  runMode.value = 'main'
  advOpen.value = false
  iconOpen.value = false
  editing.value = true
}
/** 总开关三态 → 多实例能力的模式：'none' 不使用 / 'pick' 指定 / 'inherit' 跟随系统默认 */
const modeOf = tri => {
  if (tri === 0 || tri === '0') return 'none'
  if (tri === 1 || tri === '1') return 'pick'
  return 'inherit'
}
/**
 * 向用户提问的存量兼容：builtinTools 里不含 askUser（空串=不使用）时，
 * 即使 toolBuiltin=1 实际也不挂载
 * （挂载判据按名单过滤）——读作「关」，别让界面显示开着而提问卡永远弹不出来。
 */
const effectiveAskUserTri = a => {
  const t = triStr(a.toolBuiltin)
  if (t === '1' && a.builtinTools != null && String(a.builtinTools).split(',').indexOf('askUser') < 0) return '0'
  return t
}

const openEdit = a => {
  editingId.value = a.id
  // 平台预置行（created_by=system 且非内置问渠）：身份字段锁定，只有能力配置可改
  editingPreset.value = isPreset(a)
  editingUpdatedAt.value = a.updateTime || a.createTime || ''
  const sub = a.isSubagent === 1 || a.isSubagent === true
  runMode.value = sub ? 'sub' : 'main'
  advOpen.value = false
  iconOpen.value = false
  const kbs = splitList(a.knowledgeBaseIds)
  const kf = parseFilter(a.knowledgeFilter)
  form.value = {
    name: a.name || '',
    description: a.description || '',
    systemPrompt: a.systemPrompt || '',
    // 内置标记驱动「名称不可修改」；图标未配时内置按问渠品牌标预选（默认问渠的图标）
    isBuiltin: isBuiltin(a) ? 1 : 0,
    icon: a.icon || (isBuiltin(a) ? 'wenqu' : ''),
    knowledgeBaseIds: kbs,
    knowledgeTags: splitList(a.knowledgeTags),
    knowledgeFileTypes: Array.isArray(kf.fileTypes) ? kf.fileTypes : [],
    knowledgeUpdatedDays: kf.updatedWithinDays || null,
    isDefault: isDefault(a),
    disabled: isDisabledAgent(a) ? 1 : 0,
    toolKnowledge: triStr(a.toolKnowledge),
    toolBuiltin: effectiveAskUserTri(a),
    toolSkill: triStr(a.toolSkill),
    toolArtifact: triStr(a.toolArtifact),
    toolMcp: triStr(a.toolMcp),
    toolWebsearch: triStr(a.toolWebsearch),
    toolHttp: triStr(a.toolHttp),
    // HTTP 工具集：回显是脱敏后的 JSON（auth.value 已清空），编辑保存时留空 = 沿用旧凭据
    httpTools: parseJsonArray(a.httpTools),
    toolWorkflow: triStr(a.toolWorkflow),
    workflowTools: parseJsonArray(a.workflowTools),
    callbackUrl: a.callbackUrl || '',
    callbackSecret: '',
    callbackSecretSet: !!a.callbackSecretSet,
    memoryMode: a.memoryMode == null ? '' : String(a.memoryMode),
    // 目标模式：库里 NULL=开（schema 默认），/agent/list 原样下发可空——这里归一成 1/0，
    // 否则 null 既不等于 1 也不等于 0，开/关两档都不亮（没回显）
    goalEnabled: a.goalEnabled === 0 ? 0 : 1,
    toolApprovalMode: a.toolApprovalMode || '',
    permissionPreset: a.permissionPreset || null,
    toolDeny: splitList(a.toolDeny),
    maxToolSteps: a.maxToolSteps == null ? null : Number(a.maxToolSteps),
    skillMode: modeOf(a.toolSkill), skills: splitList(a.skills),
    mcpMode: modeOf(a.toolMcp), mcps: splitList(a.mcps),
    isSubagent: sub ? 1 : 0,
    subAgentIds: splitList(a.subAgentIds),
    // 检索开关（只有重排三态；旧版存量的阈值/权重键由后端忽略并在下次保存时清除）
    qpRerank: parseQueryParams(a.queryParams)
  }
  if (form.value.isBuiltin === 1) {
    // 安全出口与提示词随产品固定：表单强制清空，保存时不会把管理员资产/外部出口写进共享行
    form.value.toolHttp = ''
    form.value.httpTools = []
    form.value.toolWorkflow = ''
    form.value.workflowTools = []
    form.value.callbackUrl = ''
    form.value.callbackSecret = ''
    form.value.callbackSecretSet = false
  }
  // 三档：不使用知识库 > 指定知识库（选了库）> 全部知识库
  scopeMode.value = (a.knowledgeDisabled === 1 || a.knowledgeDisabled === true)
    ? 'none' : (kbs.length ? 'pick' : 'all')
  editing.value = true
}
const closeEdit = () => { editing.value = false; editingPreset.value = false }

/** HTTP 工具集 JSON 串 → 编辑器数组（坏数据当空配置，重新导入即可修复） */
const parseJsonArray = s => {
  if (!s) return []
  try { const v = JSON.parse(s); return Array.isArray(v) ? v : [] } catch { return [] }
}
/** 检索文档条件 JSON 串 → 对象（坏数据当空条件） */
const parseFilter = s => {
  if (!s) return {}
  try { const v = JSON.parse(s); return v && typeof v === 'object' && !Array.isArray(v) ? v : {} } catch { return {} }
}
/** 表单里的两个条件 → knowledgeFilter JSON 串（空对象返回空串=后端存 null） */
const buildKnowledgeFilter = f => {
  const out = {}
  if ((f.knowledgeFileTypes || []).length) out.fileTypes = f.knowledgeFileTypes
  if (f.knowledgeUpdatedDays) out.updatedWithinDays = Number(f.knowledgeUpdatedDays)
  return Object.keys(out).length ? JSON.stringify(out) : ''
}

// ==================== 配置历史版本（见 ../agents/useAgentVersions） ====================
const { verOpen, verLoading, verList, verExpanded, verRolling, openVersions, toggleVer, doRollback }
  = useAgentVersions({ editingId, agents, reload, openEdit })

/** 三态转换：'' → null（跟随全局）；'1' → 1；'0' → 0 */
const tri = v => (v === '' || v == null ? null : Number(v))

const save = async () => {
  const f = form.value
  if (!f.name.trim()) { message.warning('请填写名称'); return }
  const payload = {
    name: f.name.trim(),
    // 图标：空串 → 后端归一为 null（默认展示）；'wenqu'=问渠品牌标；emoji 原样存。
    // 内置「问渠」的图标已锁死（配置页无入口、后端拒绝改动），此处不带该字段，避免每次保存都写一遍
    ...(f.isBuiltin === 1 ? {} : { icon: f.icon || '' }),
    description: f.description.trim(),
    systemPrompt: f.systemPrompt,
    // 知识库范围：内置「问渠」固定检索使用者的默认库＋官方使用手册（后端同口径锁死、拒绝改动），与图标同理不带字段；
    // 其余智能体——「全部知识库」清空（空 → 后端存 null → 不限制）、「指定知识库」存逗号串、
    // 「不使用知识库」置 knowledgeDisabled=1 并清空（两者互斥，后端以开关为准）
    ...(f.isBuiltin === 1 ? {} : {
      knowledgeBaseIds: scopeMode.value === 'pick' ? (f.knowledgeBaseIds || []).join(',') : '',
      knowledgeTags: scopeMode.value === 'pick' ? (f.knowledgeTags || []).join(',') : '',
      knowledgeFilter: scopeMode.value === 'pick' ? buildKnowledgeFilter(f) : '',
      knowledgeDisabled: scopeMode.value === 'none' ? 1 : 0
    }),
    toolKnowledge: tri(f.toolKnowledge),
    // 向用户提问（原内置工具行）：开关三态即可；builtinTools 恒清 null——ask_user 族只剩 askUser，
    // 旧的「指定名单」若残留（尤其空串），挂载判据会把提问卡永远钉死（见 effectiveAskUserTri）
    toolBuiltin: tri(f.toolBuiltin),
    builtinTools: null,
    toolArtifact: tri(f.toolArtifact),
    toolWebsearch: tri(f.toolWebsearch),
    // HTTP 工具（v1 对外能力第一批）：整包提交工具集（auth.value 留空=沿用旧凭据，后端按 id 保留密文）
    toolHttp: tri(f.toolHttp),
    httpTools: f.httpTools || [],
    // 工作流工具（v2 对外能力第二批）：整包提交工作流 id 数组，后端校验存在性/发布态
    toolWorkflow: tri(f.toolWorkflow),
    workflowTools: f.workflowTools || [],
    // 轮终回调（v2 对外能力第二批）：密钥留空=沿用旧密文（后端按空跳过重加密）
    callbackUrl: (f.callbackUrl || '').trim() || null,
    callbackSecret: f.callbackSecret || '',
    // 记忆域（对外能力第二批）：'' → null=跟随全局；1/2 原样
    memoryMode: f.memoryMode === '' || f.memoryMode == null ? null : Number(f.memoryMode),
    // 目标模式：二态始终带上（关掉后要能落 0）
    goalEnabled: f.goalEnabled,
    // 执行确认留空 → null = 跟随权限档位的建议值（只读调研→逐项确认、全自主→直接执行）
    toolApprovalMode: f.toolApprovalMode || null,
    // 权限档位：留空 → null（= 跟随平台默认档，不是「关闭档位」）；必须始终带上这个键，
    // 否则清空档位时后端收不到字段、旧值原地留着（后端按 body 出现的字段才写列）
    permissionPreset: f.permissionPreset || null,
    // 工具黑名单：始终带这个键（清空后要能落回 null，后端按 body 出现的字段才写列）
    toolDeny: (f.toolDeny || []).join(','),
    maxToolSteps: f.maxToolSteps == null || f.maxToolSteps === '' ? null : Number(f.maxToolSteps),
    // 「设为默认」是页头的独立星标动作，保存不带 isDefault：否则每次保存都会把默认位清掉
    isSubagent: runMode.value === 'sub' ? 1 : 0,
    // 子智能体没有委派对象；问答助手一个都没选 → 空串（后端归一为 null → 编排走多视角策略）。
    // 子智能体不参与委派，但仍保留已存名单：切回问答助手时不用重新配
    subAgentIds: runMode.value === 'sub' ? null : (f.subAgentIds || []).join(','),
    // 检索开关覆盖：跟随全局 → 空串 → 后端存 null；开启/关闭写 rerank.enabled
    queryParams: buildQueryParams(f.qpRerank)
  }
  // 多实例能力：模式 →（总开关三态 + 具体项）
  //   指定 → 开关置 1 + 项列表；一项都没选则等同「不使用」
  //   不使用 → 开关置 0 并清空列表
  //   跟随全局 → 两者都置 null（后端按 null 判定继承）
  for (const c of CAPS.filter(x => x.kind === 'list')) {
    const mode = f[c.modeKey]
    const picked = (f[c.listKey] || []).join(',')
    if (mode === 'pick' && picked) {
      payload[c.key] = 1
      payload[c.listKey] = picked
    } else if (mode === 'pick' || mode === 'none') {
      payload[c.key] = 0
      payload[c.listKey] = null
    } else {
      payload[c.key] = null
      payload[c.listKey] = null
    }
  }
  // 保存前门禁（评测线，hint 口径）：有评测集且「有变更未评测 / 低于门槛」时先弹检查弹窗；
  // 无评测集、查询失败、非编辑态一律静默直存——门禁永远不能挡住既有保存路径
  if (editingId.value) {
    try {
      const g = await agentEvalGateCheck(editingId.value, payload)
      if (g.success && g.data?.hasSuite) {
        const info = g.data
        if ((info.changes?.length || 0) > 0 || info.gate === 'below') {
          gateInfo.value = info
          gatePayload.value = payload
          gateOpen.value = true
          return
        }
      }
    } catch (e) { /* 门禁查询失败不拦保存 */ }
  }
  await doSaveNow(payload)
}

/** 保存主体（门禁弹窗的两个动作与直接保存共用同一条路径） */
const doSaveNow = async payload => {
  saving.value = true
  try {
    const r = editingId.value ? await updateAgent(editingId.value, payload) : await createAgent(payload)
    if (r.success) {
      message.success(editingId.value ? '已保存' : '已创建')
      editing.value = false
      await reload()
      return true
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
  return false
}

// ==================== 保存前检查（评测门禁） ====================
const gateOpen = ref(false)
const gateInfo = ref(null)
const gatePayload = ref(null)

/** 弹窗动作：save=仍然保存；saveAndRun=保存成功后立即以 gate 触发一次回归并跳评测详情 */
const onGateConfirm = async action => {
  const payload = gatePayload.value
  gateOpen.value = false
  gateInfo.value = null
  if (!payload) return
  const agentId = editingId.value
  const ok = await doSaveNow(payload)
  gatePayload.value = null
  if (ok && action === 'saveAndRun' && agentId) {
    try {
      const r = await agentEvalStartRun(agentId, { trigger: 'gate' })
      if (r.success) {
        message.success('回归已启动，正在转到评测详情')
        router.push({ path: '/agents', query: { tab: 'eval', agent: agentId } })
      } else message.error(r.msg || '回归启动失败（配置已保存）')
    } catch (e) { message.error(e.message || '回归启动失败（配置已保存）') }
  }
}

/** 页头「评测」：跳到评测页签的该智能体详情 */
const openEvalTab = () => {
  if (!editingId.value) return
  router.push({ path: '/agents', query: { tab: 'eval', agent: editingId.value } })
}

const doDelete = async id => {
  try {
    const r = await deleteAgent(id)
    if (r.success) { message.success('已删除'); await reload() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 批量操作：内置智能体不可删，不进勾选范围 ====================
const selected = ref([])
const batchBusy = ref(false)
// 批量模式默认关闭：卡片不显示勾选框，点「批量管理」才进入（退出即清空勾选）
const batchMode = ref(false)
const toggleBatchMode = () => {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = []
}
/** 可勾选集合 = 当前搜索过滤后的非内置智能体（两组：主 + 子；平台预置同样不可删） */
const selectableIds = computed(() =>
  sections.value.flatMap(sec => sec.members.filter(a => !isBuiltin(a) && !isPreset(a)).map(a => a.id)))
const allChecked = computed(() =>
  selectableIds.value.length > 0 && selectableIds.value.every(id => selected.value.includes(id)))
const someChecked = computed(() => selected.value.length > 0 && !allChecked.value)
const toggleSelect = id => {
  selected.value = selected.value.includes(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id]
}
const toggleAll = () => { selected.value = allChecked.value ? [] : [...selectableIds.value] }

/** 批量结果汇报：全成功走 message；有失败逐条弹 Modal 列出原因（不静默吞） */
const reportBatch = (r, verb) => {
  const data = (r && r.data) || {}
  const okCount = (data.succeeded || []).length
  const failed = data.failed || []
  if (!failed.length) {
    message.success(`已${verb} ${okCount} 个智能体`)
    return
  }
  Modal.warning({
    title: `${verb}完成：成功 ${okCount} 个，失败 ${failed.length} 个`,
    content: failed.map(f => `「${f.name || f.id}」：${f.error}`).join('；'),
    okText: '知道了'
  })
}

const doBatchDelete = () => {
  Modal.confirm({
    title: `删除选中的 ${selected.value.length} 个智能体？`,
    content: '对话页将不再可选；内置智能体不可删（后端逐条拒绝并给出原因）。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      batchBusy.value = true
      try {
        const r = await batchDeleteAgents([...selected.value])
        reportBatch(r, '删除')
        await reload()
      } catch (e) {
        message.error('批量删除失败：' + (e.message || ''))
      } finally {
        batchBusy.value = false
      }
    }
  })
}

// ==================== 停用 / 启用（生命周期动作，点一下立即生效，不随表单保存） ====================
const disabledBusy = ref(false)

const doToggleDisabled = async (id, disabled) => {
  disabledBusy.value = true
  try {
    const r = await updateAgent(id, { disabled })
    if (r.success) {
      message.success(disabled ? '已停用' : '已启用')
      if (editing.value && String(editingId.value) === String(id)) form.value.disabled = disabled
      await refreshAgentsQuiet()
      return true
    }
    message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { disabledBusy.value = false }
  return false
}

/** 列表卡片菜单：停用前确认，并先挡住默认智能体（后端同样会拒绝，避免白跑一趟） */
const toggleDisabled = a => {
  if (isDisabledAgent(a)) { doToggleDisabled(a.id, 0); return }
  if (isDefault(a)) {
    message.warning('「' + a.name + '」是当前默认智能体，不能停用：请先把其它智能体设为默认')
    return
  }
  Modal.confirm({
    title: '停用该智能体？',
    content: `「${a.name}」将从对话页可选项中移除，已有会话、@ 与公开分享链接立即不可用；配置与历史版本保留，可随时重新启用。`,
    okText: '停用', okType: 'danger', cancelText: '取消',
    onOk: () => doToggleDisabled(a.id, 1)
  })
}

/** 配置页的启用开关：打开=启用，关闭=停用（关闭同样要确认） */
const onEnabledSwitch = checked => {
  const next = checked ? 0 : 1
  if (!editingId.value) { form.value.disabled = next; return }
  if (next === 0) { doToggleDisabled(editingId.value, 0); return }
  if (form.value.isDefault) {
    form.value.disabled = 0
    message.warning('当前默认智能体不能停用：请先把其它智能体设为默认')
    return
  }
  Modal.confirm({
    title: '停用该智能体？',
    content: `「${form.value.name || '未命名智能体'}」将从对话页可选项中移除，已有会话、@ 与公开分享链接立即不可用；配置与历史版本保留，可随时重新启用。`,
    okText: '停用', okType: 'danger', cancelText: '取消',
    onOk: () => doToggleDisabled(editingId.value, 1)
  })
}

const doSetDefault = async id => {
  try {
    const r = await setAgentDefault(id)
    if (r.success) {
      message.success('已设为默认')
      if (editing.value) form.value.isDefault = true
      await reload()
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

onMounted(async () => { await reload(); applyPubDeepLink() })
onMounted(async () => { })
</script>

<style scoped>
/* 批量操作区（页头最右，分隔线与搜索/新建划清界限）：开关恒在最右、进出模式位置不动 */
.batch-group { display: flex; align-items: center; gap: 8px; padding-left: 12px; border-left: 1px solid var(--app-border); }
.batch-on { color: var(--app-accent); border-color: var(--app-accent); }
.batch-del { color: var(--app-danger); }
.ap-check { flex: none; }
.ap-head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.ap-search { width: 200px; }
.ap-search-ic { color: var(--app-text3); }

/* 类型分段筛选：计数内嵌在分段里，与「按更新时间/名称」排序并排 */
.ap-filter { display: flex; border: 1px solid var(--app-border-strong); border-radius: 7px; overflow: hidden; background: var(--app-panel); flex: none; }
.ap-filter button { border: 0; background: transparent; font: inherit; font-size: 12px; color: var(--app-text2); padding: 5px 11px; cursor: pointer; border-left: 1px solid var(--app-border); white-space: nowrap; }
.ap-filter button:first-child { border-left: 0; }
.ap-filter button:hover { background: var(--app-interactive-hover); }
.ap-filter button.on { background: var(--app-accent); color: #fff; }
.ap-sort { width: 108px; flex: none; }

.ap-empty { padding: 48px 20px; text-align: center; }
.ap-empty-t { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.ap-empty-d { font-size: 12px; color: var(--app-text2); line-height: 1.7; max-width: 460px; margin: 0 auto 14px; }

/* 分组列表：主智能体一组，子智能体按委派它的主智能体成组 */
.ap-groups { display: flex; flex-direction: column; gap: 24px; }
.ap-group { display: flex; flex-direction: column; gap: 10px; }
.ap-group-head { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.ap-group-title { font-size: 13px; font-weight: 600; flex: none; }
.ap-group-hint {
  font-size: 12px; color: var(--app-text3); min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ap-group-count {
  flex: none; font-size: 11px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  background: var(--app-panel-2); color: var(--app-text2);
}

/* 卡片列表 */
.ap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.ap-card {
  position: relative;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 12px;
  padding: 12px 14px; display: flex; flex-direction: column; gap: 8px; cursor: pointer;
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.ap-card:hover {
  border-color: var(--app-accent-border);
  box-shadow: 0 6px 18px -10px rgba(46, 107, 230, .35);
}
.ap-card-head { display: flex; align-items: center; gap: 8px; padding-right: 14px; }
.ap-name { font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-card-tags { margin-left: auto; display: inline-flex; align-items: center; gap: 4px; flex: none; }
/* 徽章三重编码：颜色 + 图形（星/锁）+ 文字，不单靠颜色区分 */
.ap-tag-default {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-warn-weak); color: var(--app-warn-text);
}
.ap-tag-builtin {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-panel-2); color: var(--app-text2);
  display: inline-flex; align-items: center; gap: 3px;
}
.ap-tag-builtin .anticon { font-size: 9px; }
.ap-tag-sub {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-info-weak); color: var(--app-accent);
}
/* 已停用：红色系 + 文字，不单靠颜色编码；卡片整体降饱和但仍可点进配置启用 */
.ap-tag-disabled {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-danger-weak, rgba(220, 38, 38, .1));
  color: var(--app-danger, #dc2626);
}
.ap-card-disabled { opacity: .68; }
.ap-card-disabled:hover { opacity: 1; }
/* 发布状态点：绿=已发布、灰=未发布；悬停 title 说明入口在哪 */
.ap-dot { position: absolute; top: 13px; right: 13px; width: 8px; height: 8px; border-radius: 50%; }
.ap-dot.pub { background: var(--app-ok); box-shadow: 0 0 0 3px var(--app-ok-weak); }
.ap-dot.off { background: var(--app-text3); opacity: .5; }
.ap-desc {
  font-size: 12px; color: var(--app-text2); line-height: 1.6; margin: 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
/* meta 行：资料 / 能力 / 委派 / 更新——扫视时真正用来「判断该用哪个」的信息 */
.ap-meta {
  display: flex; flex-wrap: wrap; gap: 4px 10px;
  font-size: 11px; color: var(--app-text3);
  border-top: 1px dashed var(--app-border); padding-top: 7px;
}
.ap-meta-i { display: inline-flex; align-items: center; gap: 4px; min-width: 0; white-space: nowrap; max-width: 100%; overflow: hidden; text-overflow: ellipsis; }
.ap-meta-i .anticon { font-size: 11px; flex: none; }
.ap-meta-i.is-accent { color: var(--app-accent); }
.ap-meta-link { color: var(--app-accent); cursor: pointer; }
.ap-meta-link:hover { text-decoration: underline; text-underline-offset: 2px; }
.ap-meta-time { margin-left: auto; }
.ap-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.ap-chip {
  font-size: 11px; line-height: 1; padding: 4px 7px; border-radius: 6px;
  background: var(--app-panel-2); color: var(--app-text2); white-space: nowrap;
}
.ap-chip-on { background: var(--app-accent-weak); color: var(--app-accent); }
.ap-chip-warn { background: var(--app-warn-weak); color: var(--app-warn-text); }
/* 操作收敛：高频（试一试/配置）是按钮，低频与破坏性进 ⋯ 菜单 */
.ap-card-foot {
  display: flex; align-items: center; gap: 6px;
  border-top: 1px dashed var(--app-border); padding-top: 8px; margin-top: auto;
}
.ap-btn-try { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-info-weak); }
.ap-btn-try:hover { background: var(--app-accent); color: #fff; border-color: var(--app-accent); }
.ap-more-btn {
  margin-left: auto; width: 26px; height: 26px; border: 0; border-radius: 6px; flex: none;
  background: transparent; color: var(--app-text3); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; font-size: 14px;
}
.ap-more-btn:hover { background: var(--app-panel-2); color: var(--app-text); }
/* 不可管理时占位提示（内置问渠=系统默认，仅管理员可配置） */
.ap-readonly-hint { margin-left: auto; font-size: 12px; color: var(--app-text3); }
/* 不可管理的卡片：无点击态暗示（cursor 保持默认，区别于可点击进入配置的卡片） */
.ap-card-readonly { cursor: default; }
/* 骨架屏卡片：占位不响应 hover，与真实卡片同版式 */
.ap-skel { cursor: default; gap: 10px; }
.ap-skel:hover { border-color: var(--app-border); box-shadow: none; transform: none; }

/* ==================== 配置视图：主栏 + 右栏生效摘要 ==================== */
.ap-edit-head { align-items: center; }
.ap-head-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.ap-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 15px; }
.ap-head-chips { display: flex; align-items: center; gap: 6px; min-width: 0; overflow: hidden; }
.ap-head-chip {
  font-size: 10.5px; line-height: 1; color: var(--app-text2); background: var(--app-panel-2);
  border-radius: 999px; padding: 3px 8px; white-space: nowrap; max-width: 240px;
  overflow: hidden; text-overflow: ellipsis;
}
.ap-head-chip.is-accent { color: var(--app-accent); background: var(--app-accent-weak); }
.ap-head-chip.is-danger { color: var(--app-danger, #dc2626); background: var(--app-danger-weak, rgba(220, 38, 38, .1)); }
.ap-star-btn { color: var(--app-text3); }
.ap-star-btn.is-default { color: var(--app-warn); }
.ap-star-btn:disabled { cursor: default; opacity: 1; }

.ap-layout {
  display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 16px;
  max-width: 1220px; margin: 0 auto; align-items: start;
}
.ap-form { display: flex; flex-direction: column; gap: 12px; min-width: 0; max-width: none; margin: 0; }
.ap-form > section { margin: 0; }
/* 系统提示词 md 编辑器：高度跟视口走（长提示词在页内就铺得开，不用全屏才能写）；
   编辑器自带浅/深配色，只把圆角对齐本页卡片 */
.ap-md-editor { height: clamp(460px, 62vh, 860px); border-radius: 10px; --md-bk-color: var(--app-panel-2, #fff); }
.ap-md-editor.md-editor-dark { --md-bk-color: var(--app-panel-2, #1e1e1e); }
.ap-prompt-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ap-pane-mode { flex: none; }
.ap-md-preview { overflow: auto; }
/* 全屏纯编辑是主书写面：限一栏舒适行宽、字号提一档，别一行排满整幅屏幕 */
.ap-md-editor.md-editor-fullscreen :deep(.md-editor-input) { max-width: 960px; margin: 0 auto; font-size: 15px; line-height: 1.8; }
/* 库默认 word-break:break-all + 16px：窄列里长 code 名（extraValidationUrl）会被按字母切碎成竖排。
   改按词换行 + 收字号，让非全屏的半幅预览也能凑合读；全屏（右上角按钮）才是校对长契约的主战场 */
.ap-md-editor :deep(.md-editor-preview) { word-break: break-word; font-size: 13.5px; }
.ap-md-editor :deep(.md-editor-preview th), .ap-md-editor :deep(.md-editor-preview td) { word-break: break-word; }
.ap-form :deep(.ant-form-item) { margin-bottom: 0; }
.ap-form :deep(.ant-form-item-label) { padding-bottom: 4px; }
.ap-form :deep(.ant-form-item-label > label) { font-size: 12px; color: var(--app-text2); height: auto; }
.ap-sec-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; min-width: 0; }
.ap-sec-head .app-card-title { margin-bottom: 0; }
.ap-hint { font-size: 11.5px; color: var(--app-text3); line-height: 1.6; }
.ap-block-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: -4px 0 12px; }
.ap-field { margin-top: 12px; }
.ap-field-last { margin-bottom: 0; }
.ap-field-label { display: block; font-size: 12px; color: var(--app-text2); margin: 0 0 5px; }

/* 运行方式 */
.ap-mode-seg { width: fit-content; }
.ap-mode-seg :deep(.ant-segmented-item-label) { min-height: 26px; line-height: 26px; padding: 0 16px; font-size: 12px; }
.ap-mode-note { margin: 9px 0 0; }
/* 启用状态行：与运行方式同卡，开关靠右 */
.ap-enable-row {
  display: flex; align-items: flex-start; gap: 16px;
  margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--app-border);
}
.ap-enable-main { flex: 1; min-width: 0; font-size: 12px; }
.ap-enable-main .ap-hint { margin: 4px 0 0; }
.ap-mode-extra { margin-top: 12px; display: flex; flex-direction: column; gap: 6px; }

/* 身份与角色 */
.ap-id-row { display: flex; align-items: flex-start; gap: 12px; }
.ap-id-name { flex: 1; min-width: 0; }
.ap-avatar-btn {
  position: relative; width: 50px; height: 50px; padding: 0; border-radius: 12px;
  border: 1px solid var(--app-border); background: var(--app-panel); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: border-color .15s, box-shadow .15s;
}
.ap-avatar-btn:hover { border-color: var(--app-accent-border); box-shadow: 0 0 0 2px var(--app-accent-weak); }
.ap-avatar-btn.is-locked { cursor: default; }
.ap-avatar-btn.is-locked:hover { border-color: var(--app-border); box-shadow: none; }
.ap-avatar-pen {
  position: absolute; right: -5px; bottom: -5px; width: 18px; height: 18px; border-radius: 50%;
  background: var(--app-panel); border: 1px solid var(--app-border-strong);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 9px; color: var(--app-text2);
}
.ap-icon-grid { display: flex; flex-wrap: wrap; gap: 6px; width: 268px; }
.ap-icon-grid .ap-icon-opt { width: 32px; height: 32px; }
/* 图标选择器：一排可选头像块，选中态用品牌色描边 + 光圈 */
.ap-icon-opt {
  width: 34px; height: 34px; border-radius: 9px; padding: 0;
  border: 1px solid var(--app-border); background: var(--app-panel); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: border-color .15s, box-shadow .15s;
}
.ap-icon-opt:hover { border-color: var(--app-accent-border); }
.ap-icon-opt.on { border-color: var(--app-accent); box-shadow: 0 0 0 2px var(--app-accent-weak); }

/* 资料范围 */
.ap-scope-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.ap-scope-card {
  display: flex; align-items: flex-start; gap: 8px; text-align: left;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel);
  padding: 9px 10px; cursor: pointer; font: inherit;
  transition: border-color .15s, background .15s, box-shadow .15s;
}
.ap-scope-card:hover { border-color: var(--app-accent-border); }
.ap-scope-card.on {
  border-color: var(--app-accent); background: var(--app-info-weak);
  box-shadow: 0 0 0 1px rgba(46, 107, 230, .06) inset;
}
.ap-scope-dot {
  width: 14px; height: 14px; border: 1px solid var(--app-border-strong); border-radius: 50%;
  flex: none; margin-top: 2px; background: var(--app-panel);
}
.ap-scope-card.on .ap-scope-dot { border: 4px solid var(--app-accent); }
.ap-scope-text { min-width: 0; }
.ap-scope-text b { display: block; font-size: 12.5px; font-weight: 500; line-height: 1.4; color: var(--app-text); }
.ap-scope-text small { display: block; font-size: 10.5px; color: var(--app-text3); line-height: 1.4; margin-top: 1px; }
.ap-kb-pick { margin-top: 10px; }
.ap-kb-pick .ap-hint { margin-top: 5px; }
.ap-filter-row { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
/* 内置「问渠」的知识库范围为固定展示（无选择入口）：单行芯片，与选择器同起点对齐 */
.ap-kb-fixed { margin-top: 4px; }
.ap-sec-ic { font-size: 13px; color: var(--app-text3); }

/* 能力行：分组头 + 图标块 + 名称/描述 + 分段控件；只在「与系统默认不同」时整行高亮 */
.ap-cap-list { display: flex; flex-direction: column; }
.ap-cap-grp-h { font-size: 11px; font-weight: 600; color: var(--app-text3); letter-spacing: .05em; padding: 10px 0 2px; }
.ap-cap { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 7px; transition: background .15s; }
.ap-cap:hover { background: var(--app-panel-2); }
.ap-cap.overridden { background: var(--app-accent-weak); box-shadow: inset 2px 0 0 var(--app-accent); }
.ap-cap.overridden:hover { background: var(--app-accent-weak); }
.ap-cap-ic {
  width: 28px; height: 28px; border-radius: 7px; flex: none; font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel-2); color: var(--app-text2); transition: background .15s, color .15s;
}
.ap-cap.overridden .ap-cap-ic { background: var(--app-accent-weak); color: var(--app-accent); }
.ap-cap-l { flex: 1; min-width: 0; }
.ap-cap-name { display: flex; align-items: center; gap: 6px; font-size: 12.5px; line-height: 1.4; }
.ap-cap.overridden .ap-cap-name { font-weight: 500; }
.ap-cap-badge {
  font-size: 9.5px; line-height: 1; padding: 2px 5px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.ap-cap-desc {
  font-size: 11.5px; color: var(--app-text3); line-height: 1.45; margin-top: 1px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
/* 分段控件：当前态一眼可见；「跟随系统」段用小字点名默认在哪一侧 */
.ap-seg { display: flex; flex: none; border: 1px solid var(--app-border-strong); border-radius: 6px; overflow: hidden; background: var(--app-panel); }
.ap-seg button { border: 0; background: transparent; font: inherit; font-size: 11.5px; color: var(--app-text2); padding: 4px 10px; cursor: pointer; border-left: 1px solid var(--app-border); display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
.ap-seg button:first-child { border-left: 0; }
.ap-seg button:hover { background: var(--app-interactive-hover); }
.ap-seg button.on { background: var(--app-accent); color: #fff; font-weight: 500; }
.ap-seg button.on:hover { background: var(--app-accent-hover); }
.ap-seg-gv { font-size: 10px; opacity: .8; }
/* 内置问渠不可配置的能力：与分段控件同高的锁定胶囊，不再用裸文字占位 */
.ap-seg-lock {
  display: inline-flex; align-items: center; gap: 4px; flex: none;
  height: 24px; padding: 0 9px; box-sizing: border-box;
  border: 1px solid var(--app-border); border-radius: 6px;
  background: var(--app-panel-2); color: var(--app-text3);
  font-size: 11.5px; line-height: 1; white-space: nowrap;
}
.ap-seg-lock svg { font-size: 12px; }
/* 卡头唯一差异指示：行内徽标只作定位，计数与「恢复跟随」集中在这里（与右侧摘要同口径）。
   零差异与有差异共用同一盒模型（始终占位 + 透明边框），切换开关时卡头高度不变，下方内容不跳动 */
.ap-head-diff {
  margin-left: auto; display: inline-flex; align-items: center; height: 22px; box-sizing: border-box;
  padding: 0 10px; border: 1px solid transparent; border-radius: 999px;
  font-size: 11px; line-height: 1; color: var(--app-text3); white-space: nowrap;
}
.ap-head-diff.diff { color: var(--app-accent); background: var(--app-accent-weak); border-color: var(--app-accent-border); }
.ap-diff-reset { border: 0; background: transparent; color: var(--app-accent); font: inherit; font-size: 11px; cursor: pointer; text-decoration: underline; text-underline-offset: 2px; margin-left: 6px; }
/* 「指定」模式展开的具体项多选：跟在自己那行能力下面，与上方图标块左对齐 */
.ap-cap-pick { padding: 2px 0 10px 38px; }
.ap-pick-empty { font-size: 11px; color: var(--app-text3); margin-top: 5px; }
.ap-pick-docs { margin-top: 5px; font-size: 11px; color: var(--app-text3); }
.ap-pick-docs .app-link-btn { font-size: 11px; margin-right: 6px; }

/* 高级设置：独立卡片；常用治理常显，专家选项折叠（原生 details，caret 随 open 旋转） */
.ap-adv-zone-h { font-size: 11px; font-weight: 600; color: var(--app-text3); letter-spacing: .05em; margin: 0 0 8px; }
.ap-adv-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 11px 12px; }
.ap-adv-field { min-width: 0; }
.ap-adv-full { grid-column: 1 / -1; }
.ap-adv-field .ap-hint { margin-top: 3px; }
.ap-adv-more { margin-top: 12px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); }
.ap-adv-more-h { display: flex; align-items: center; gap: 8px; padding: 9px 12px; cursor: pointer; font-size: 12px; font-weight: 500; color: var(--app-text2); list-style: none; user-select: none; }
.ap-adv-more-h::-webkit-details-marker { display: none; }
.ap-adv-more-h:hover { color: var(--app-text); }
.ap-adv-count {
  font-size: 10px; line-height: 1; color: var(--app-text2); background: var(--app-panel);
  border: 1px solid var(--app-border); border-radius: 999px; padding: 3px 7px; flex: none;
}
.ap-adv-caret { margin-left: auto; flex: none; color: var(--app-text3); transition: transform .15s; }
.ap-adv-more[open] .ap-adv-caret { transform: rotate(180deg); }
.ap-adv-more-body { padding: 2px 12px 12px; }

/* 右栏：生效摘要 + 说明（宽屏吸附在顶部） */
.ap-side { position: sticky; top: 16px; display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.ap-side-card { margin: 0; padding: 14px 15px; }
.ap-side-title { margin: 0 0 8px; font-size: 12.5px; font-weight: 600; color: var(--app-text); }
.ap-sum-row {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 10px;
  padding: 7px 0; border-bottom: 1px dashed var(--app-border); font-size: 11.5px; line-height: 1.5;
}
.ap-sum-row:last-of-type { border-bottom: 0; }
.ap-sum-row span { color: var(--app-text3); flex: none; }
.ap-sum-row b { font-weight: 400; color: var(--app-text); text-align: right; min-width: 0; }
.ap-diff {
  margin-top: 8px; background: var(--app-accent-weak); border: 1px solid var(--app-accent-border);
  border-radius: 7px; padding: 8px 9px; display: flex; align-items: center; justify-content: space-between;
  gap: 8px; font-size: 11px; color: var(--app-accent);
}
.ap-side-tip { background: linear-gradient(180deg, var(--app-panel), var(--app-panel-2)); }
.ap-side-tip p { margin: 0; font-size: 11px; line-height: 1.65; color: var(--app-text2); }
.ap-side-meta { margin: 0 0 6px; font-size: 11.5px; color: var(--app-text2); }

@media (max-width: 1200px) {
  .ap-layout { grid-template-columns: minmax(0, 1fr); }
  .ap-side { display: none; }
}
@media (max-width: 768px) {
  .ap-scope-cards { grid-template-columns: 1fr; }
  .ap-adv-grid { grid-template-columns: 1fr; }
  .ap-head-chips { display: none; }
  .ap-cap-desc { white-space: normal; }
}

/* ==================== 公开发布弹窗 ====================
   结构：标题栏（智能体头像+名）→ 状态卡（总开关）→ 游客能力（模型 / MCP）→ 分发方式（Tab）→ 底部撤销。
   结构用「代码块 + 右侧图标按钮」一行到底，按钮 flex:none 不被压缩，
   避免输入框把「复制」「打开」挤成竖排单字。 */
.pub-title { display: flex; align-items: center; gap: 8px; font-size: 15px; font-weight: 600; }
.pub-title-sub {
  font-size: 12px; font-weight: 400; color: var(--app-text3);
  padding-left: 8px; border-left: 1px solid var(--app-border);
  max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pub-block { margin-top: 16px; }
.pub-block-h {
  font-size: 11px; font-weight: 600; color: var(--app-text3);
  letter-spacing: .04em; margin-bottom: 8px;
}

/* 状态卡：色底随开关切换，「发没发」这件事一眼可见 */
.pub-status {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  border: 1px solid var(--app-border); border-radius: var(--app-radius); padding: 12px 14px;
  background: var(--app-panel-2);
}
.pub-status.on { background: var(--app-ok-weak); border-color: var(--app-ok-weak-hover); }
.pub-status-t { font-size: 13px; font-weight: 600; color: var(--app-text); }
.pub-status.on .pub-status-t { color: var(--app-ok); }
.pub-status-d { font-size: 12px; color: var(--app-text3); margin-top: 2px; line-height: 1.6; }
.pub-status-stat {
  display: flex; align-items: center; gap: 6px; margin-top: 6px;
  font-size: 12px; color: var(--app-text2);
}

.pub-field { display: flex; flex-direction: column; gap: 6px; }
.pub-label { font-size: 12px; font-weight: 600; color: var(--app-text2); }
.pub-switch-row { display: flex; align-items: flex-start; gap: 10px; margin-top: 12px; }
.pub-switch-row > .ant-switch { margin-top: 1px; flex: none; }
.pub-switch-label { font-size: 13px; color: var(--app-text); }
.pub-switch-d { font-size: 12px; color: var(--app-text3); margin-top: 2px; line-height: 1.6; }
/* 游客 MCP 勾选组：挂在端点开关文字列之下的内嵌块，从属关系一眼可见 */
.pub-mcp-sub {
  margin-top: 8px; padding: 10px 12px;
  border: 1px solid var(--app-border); border-radius: var(--app-radius-sm);
  background: var(--app-panel-2);
}
.pub-mcp-sub-h { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.pub-mcp-sub-act { flex: none; display: flex; gap: 2px; }
.pub-mcp-sub .pub-switch-d { margin-top: 6px; }
.pub-mcp-sub .pub-warn { margin-top: 8px; }
/* 游客 MCP 勾选组：竖排防长名挤压，勾选行紧凑。
   align-self: stretch 是必须的——antd 给 .ant-checkbox-group 注入了 align-self:center，
   不覆盖的话勾选框会缩成内容宽、居中漂在弹窗中间 */
.pub-mcp-checks { display: flex; flex-direction: column; gap: 4px; align-self: stretch; margin-top: 6px; }
.pub-mcp-checks .ant-checkbox-wrapper { font-size: 13px; color: var(--app-text); align-items: center; }
.pub-warn {
  display: flex; align-items: flex-start; gap: 7px; margin-top: 10px;
  font-size: 12px; line-height: 1.6; color: var(--app-warn-text);
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border);
  border-radius: var(--app-radius-sm); padding: 8px 10px;
}
.pub-warn .anticon { flex: none; margin-top: 2px; }

/* 分发方式：三种形态共享同一 token，页签切换避免一次铺满一屏半 */
.pub-tabs :deep(.ant-tabs-nav) { margin-bottom: 10px; }
.pub-sub-label { font-size: 12px; color: var(--app-text2); margin: 10px 0 5px; }
.pub-sub-label:first-child { margin-top: 0; }
/* iframe 尺寸：改完实时反映进代码，用户不必复制出去手改 style */
.pub-size-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.pub-size { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text2); }
.pub-size :deep(.ant-input-number) { width: 88px; }
.pub-size-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; }
/* 代码框：占满整行，复制/打开按钮**浮在框内**右上角，不另起一列把单行的链接挤成两行 */
.pub-code-box { position: relative; }
.pub-code {
  display: block; padding: 8px 10px; border-radius: var(--app-radius-sm);
  background: var(--app-code-bg); border: 1px solid var(--app-border);
  font-family: "SF Mono", Menlo, Consolas, monospace; font-size: 11.5px; line-height: 1.6;
  color: var(--app-text2); word-break: break-all; white-space: pre-wrap;
  /* 右上角给悬浮按钮腾出位置：否则长链接会绕到按钮底下被遮住 */
  padding-right: 76px;
}
.pub-code.block { white-space: pre-wrap; }
/* 悬浮动作区：贴框内右上角，默认半透明，hover 显形 —— 不占布局宽度 */
.pub-code-act {
  position: absolute; top: 4px; right: 4px;
  display: flex; gap: 4px; opacity: .55; transition: opacity .15s;
}
.pub-code-box:hover .pub-code-act, .pub-code-act:focus-within { opacity: 1; }
.pub-icon-btn {
  width: 28px; height: 28px;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--app-border); border-radius: 6px;
  background: var(--app-panel); color: var(--app-text2); cursor: pointer; font-size: 13px;
  transition: color .15s, border-color .15s, background .15s;
}
.pub-icon-btn:hover { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.pub-hint { font-size: 12px; color: var(--app-text3); line-height: 1.65; margin-top: 8px; }

/* 占位（未开启 MCP / 已停用）：给出下一步，而不是白屏或空文本框 */
.pub-placeholder {
  display: flex; flex-direction: column; align-items: center; gap: 6px; text-align: center;
  padding: 20px 12px; border: 1px dashed var(--app-border-strong); border-radius: var(--app-radius);
  font-size: 13px; color: var(--app-text2);
}
.pub-placeholder > .anticon { font-size: 20px; color: var(--app-text3); }
.pub-placeholder .pub-hint { margin-top: 0; max-width: 380px; }

.pub-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  margin-top: 18px; padding-top: 12px; border-top: 1px solid var(--app-border);
}
.pub-foot-hint { font-size: 11.5px; color: var(--app-text3); }

/* ==================== 委派编排视图（列表 + 聚焦拓扑） ==================== */
.ap-topo-head { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; }
.ap-topo-seg { display: flex; background: var(--app-hover, #f2f3f7); border-radius: 8px; padding: 2px; flex: none; }
.ap-topo-seg button { border: 0; background: transparent; color: var(--app-text2); font-size: 12.5px; padding: 4px 14px;
  border-radius: 6px; cursor: pointer; }
.ap-topo-seg button.on { background: var(--app-panel, #fff); color: var(--app-text); font-weight: 600;
  box-shadow: 0 1px 3px rgba(0,0,0,.1); }
.ap-topo-seg button:disabled { color: var(--app-text3); cursor: not-allowed; }
.ap-topo-sum { display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 12px; color: var(--app-text3); }
.ap-topo-sum b { color: var(--app-text); font-weight: 600; margin-left: 2px; }
.ap-topo-sum .warn b { color: var(--app-warn, #b25e09); }
.ap-topo-sum .danger b { color: var(--app-danger); }

.ap-topo-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 10px; }
.ap-topo-q { width: 260px; height: 28px; padding: 0 10px; font-size: 12.5px; color: var(--app-text);
  background: var(--app-panel, #fff); border: 1px solid var(--app-border); border-radius: 8px; outline: none; }
.ap-topo-q:focus { border-color: var(--app-accent); }
.ap-topo-chip { border: 1px solid var(--app-border); background: var(--app-panel, #fff); color: var(--app-text2);
  border-radius: 999px; padding: 3px 11px; font-size: 12px; cursor: pointer; }
.ap-topo-chip em { font-style: normal; color: var(--app-text3); margin-left: 5px; }
.ap-topo-chip.on { border-color: var(--app-accent); background: var(--app-accent-weak); color: var(--app-accent); font-weight: 600; }
.ap-topo-chip.on em { color: var(--app-accent); }

.ap-topo-thead, .ap-topo-row { display: grid; grid-template-columns: 320px 1fr 150px; align-items: center; gap: 10px; }
.ap-topo-thead { padding: 7px 12px; font-size: 12px; font-weight: 600; color: var(--app-text2);
  background: var(--app-hover, #fafbfd); border-top: 1px solid var(--app-border); border-bottom: 1px solid var(--app-border); }
/* 行高必须与脚本里的 TOPO_ROW_H 一致（窗口化滚动按它算偏移） */
.ap-topo-rows { height: 432px; overflow-y: auto; border-bottom: 1px solid var(--app-border); }
.ap-topo-row { height: 52px; padding: 0 12px; border-bottom: 1px solid var(--app-border); cursor: pointer; }
.ap-topo-row:hover { background: var(--app-hover, #f7f8fb); }
.ap-topo-row.sel { background: var(--app-accent-weak); box-shadow: inset 3px 0 0 var(--app-accent); }
.ap-topo-who { display: flex; flex-direction: column; min-width: 0; line-height: 1.35; }
.ap-topo-who b { font-size: 13px; font-weight: 600; color: var(--app-text); overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.ap-topo-who small { font-size: 11.5px; color: var(--app-text3); overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.ap-topo-row .c-name { display: flex; align-items: center; gap: 9px; min-width: 0; }
.ap-topo-row .c-sub { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; min-width: 0; }
.ap-topo-tg { background: var(--app-accent-weak); border: 1px solid var(--app-border); color: var(--app-text2);
  border-radius: 6px; padding: 0 7px; font-size: 11.5px; }
.ap-topo-tg.more { background: transparent; border-style: dashed; color: var(--app-text3); }
.ap-topo-unb { font-size: 12px; color: var(--app-text3); }
.ap-topo-badge { font-size: 11.5px; color: var(--app-text3); }
.ap-topo-badge.ok { color: var(--app-accent); }
.ap-topo-badge.err { color: var(--app-danger); border: 1px dashed currentColor; border-radius: 999px; padding: 1px 8px; }
.ap-topo-foot { display: flex; align-items: center; gap: 10px; padding-top: 9px; font-size: 12px; color: var(--app-text3); }
.ap-topo-foot .sp { flex: 1; }

.ap-topo-crumb { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12.5px;
  color: var(--app-text2); margin-bottom: 8px; }
.ap-topo-crumb b { color: var(--app-text); font-size: 13.5px; }
.ap-topo-crumb .sep { color: var(--app-text3); }
.ap-topo-crumb .sp { flex: 1; }
.ap-topo-legend { display: flex; flex-wrap: wrap; gap: 14px; align-items: center; font-size: 12px; color: var(--app-text3); margin-bottom: 8px; }
.ap-lg { display: inline-flex; align-items: center; gap: 6px; }
.ap-lg-dot { width: 10px; height: 10px; border-radius: 3px; display: inline-block; flex: none; }
.ap-lg-dot.main { background: var(--app-panel); border: 1.5px solid var(--app-accent, var(--app-accent-hover)); }
.ap-lg-dot.sub { background: var(--app-accent-weak); border: 1.5px solid var(--app-accent, var(--app-accent-hover)); }
.ap-topo-empty { padding: 28px 0; text-align: center; font-size: 13px; color: var(--app-text3); }
/* 画布内部滚动：委派数再多也把弹窗高度钉住 */
.ap-topo-canvas { max-height: 560px; overflow: auto; border: 1px solid var(--app-border); border-radius: 10px;
  background: var(--app-panel, #fff); }
.ap-topo-svg { display: block; flex: none; }
.ap-topo-note { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 10px; padding: 8px 12px;
  font-size: 12px; border-radius: 8px; color: var(--app-warn, #b25e09);
  background: var(--app-warn-weak, #fff7e6); border: 1px solid var(--app-warn-border, #ffe1a8); }
.ap-topo-note .sp { flex: 1; }
.ap-edge { fill: none; stroke: var(--app-text3); stroke-width: 1.5; opacity: .8; transition: opacity .2s, stroke-width .2s; }
.ap-edge.on { stroke: var(--app-accent, var(--app-accent-hover)); stroke-width: 2; opacity: 1; }
.ap-edge.dim { opacity: .1; }
.ap-edge.miss { stroke: var(--app-danger, var(--app-danger)); stroke-dasharray: 5 4; }
.ap-node { cursor: pointer; }
.ap-node.dim { opacity: .18; }
.ap-nrect { fill: #fff; stroke: var(--app-border, var(--app-border)); stroke-width: 1.2; }
.ap-nrect.sub { fill: var(--app-accent-weak); }
.ap-nrect.miss { fill: #fff; stroke: var(--app-danger); stroke-dasharray: 5 4; }
.ap-nname { font-size: 13px; font-weight: 600; fill: var(--app-text, var(--app-text)); }
.ap-ndesc { font-size: 11px; fill: var(--app-text3, var(--app-text3)); }
.ap-nmeta { font-size: 11px; fill: var(--app-text3, var(--app-text3)); }

/* ==================== 配置历史版本抽屉 ==================== */
.ap-ver-list { display: flex; flex-direction: column; gap: 8px; }
.ap-ver-item { border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.ap-ver-item.cur { border-color: var(--app-accent); }
.ap-ver-head { display: flex; align-items: center; gap: 8px; padding: 10px 12px; cursor: pointer; user-select: none; }
.ap-ver-head:hover { background: var(--app-hover, #fafafa); }
.ap-ver-no { font-weight: 600; font-size: 13px; color: var(--app-text); flex: none; }
.ap-ver-cur { font-size: 11px; color: var(--app-accent); border: 1px solid var(--app-accent);
  border-radius: 4px; padding: 0 5px; flex: none; }
.ap-ver-reason { font-size: 12px; color: var(--app-text2); flex: none; }
.ap-ver-changes { font-size: 12px; color: var(--app-text3); }
.ap-ver-time { font-size: 12px; color: var(--app-text3); margin-left: auto; flex: none; }
.ap-ver-caret { color: var(--app-text3); transition: transform .2s; }
.ap-ver-caret.open { transform: rotate(180deg); }
.ap-ver-body { padding: 0 12px 12px; border-top: 1px dashed var(--app-border); }
.ap-ver-empty { font-size: 12px; color: var(--app-text3); padding: 10px 0 0; }
.ap-ver-diff { width: 100%; border-collapse: collapse; margin-top: 8px; table-layout: fixed; }
.ap-ver-diff td { font-size: 12px; padding: 5px 6px; vertical-align: top; word-break: break-all; }
.ap-ver-lbl { width: 26%; color: var(--app-text2); }
.ap-ver-old { width: 34%; color: var(--app-text3); text-decoration: line-through; }
.ap-ver-arrow { width: 16px; text-align: center; color: var(--app-text3); }
.ap-ver-new { width: 34%; color: var(--app-text); }
.ap-ver-act { margin-top: 10px; }
.ap-ver-same { font-size: 12px; color: var(--app-text3); }
.ap-ver-none { font-size: 12px; color: var(--app-text3); padding: 16px 0; }

/* ==================== 新建智能体：模板选择弹层 ==================== */
.ap-tpl-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 10px;
  max-height: 420px; overflow-y: auto;
}
.ap-tpl {
  text-align: left; border: 1px solid var(--app-border); border-radius: 10px;
  background: var(--app-panel); padding: 12px; cursor: pointer; font: inherit;
  display: flex; flex-direction: column; gap: 5px;
  transition: border-color .15s, background .15s;
}
.ap-tpl:hover { border-color: var(--app-accent-border); background: var(--app-info-weak); }
.ap-tpl-ic {
  display: inline-flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; border-radius: 8px; background: var(--app-panel-2); color: var(--app-text2);
}
.ap-tpl b { font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-tpl small {
  font-size: 11px; color: var(--app-text3); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.ap-tpl-foot {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--app-border);
}
.ap-import-input { display: none; }
</style>
