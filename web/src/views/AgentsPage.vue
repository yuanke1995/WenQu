<template>
  <div class="app-page">
    <!-- ==================== 列表视图 ==================== -->
    <template v-if="!editing">
      <div class="app-page-head">
        <!-- 页名与 Tab 名重复，标题仅保留给读屏器（sr-only），视觉上从统计起头 -->
        <h1 class="app-page-title sr-only">智能体</h1>
        <span class="ap-count">共 <b>{{ agents.length }}</b> 个</span>
        <span class="head-hint-plain">一个智能体就是一组对话预设：选好模型、写好角色提示词、圈定知识库范围、按需开启能力；对话时在输入框上方选用，这一轮问答就按它走</span>
        <div class="ap-head-r">
          <a-input v-model:value="keyword" class="ap-search" size="small" allow-clear placeholder="搜索名称或描述">
            <template #prefix><search-outlined class="ap-search-ic" /></template>
          </a-input>
          <a-tooltip title="刷新">
            <button class="app-icon-btn" :disabled="loading" aria-label="刷新列表" @click="reload"><reload-outlined /></button>
          </a-tooltip>
          <a-tooltip title="委派编排视图：主智能体 → 子智能体的委派关系图">
            <button class="app-icon-btn" :disabled="loading || !agents.length" aria-label="委派编排视图" @click="topoOpen = true"><apartment-outlined /></button>
          </a-tooltip>
          <button class="app-btn small" @click="openCreate">新建智能体</button>
          <!-- 批量区（分隔线独立成区，不与搜索/新建挤作一堆）。默认收起，点「批量管理」进入批量模式；
               开关放最右：进出模式自身位置不动；内置智能体不可删，不进勾选范围 -->
          <div v-if="agents.length" class="batch-group">
            <template v-if="batchMode">
              <a-checkbox :checked="allChecked" :indeterminate="someChecked" @change="toggleAll">全选</a-checkbox>
              <button class="app-btn ghost small batch-del" :disabled="!selected.length || batchBusy" @click="doBatchDelete">删除</button>
            </template>
            <button class="app-btn ghost small" :class="{ 'batch-on': batchMode }" @click="toggleBatchMode">{{ batchMode ? '退出管理' : '批量管理' }}</button>
          </div>
        </div>
      </div>

      <div class="app-page-body">
        <div v-if="loading" class="ap-empty"><a-spin size="small" /></div>

        <div v-else-if="!agents.length" class="ap-empty">
          <div class="ap-empty-t">还没有智能体</div>
          <div class="ap-empty-d">
            一个智能体就是一组预设：选好模型、写好角色提示词、圈定知识库范围、按需开启能力。
            对话时在输入框上方切换，这一轮问答就按它的配置走；没配置的维度沿用系统设置。
          </div>
          <button class="app-btn small" @click="openCreate">新建第一个智能体</button>
        </div>

        <div v-else-if="!sections.length" class="ap-empty">
          <div class="ap-empty-t">没有匹配的智能体</div>
          <div class="ap-empty-d">没有名称或描述包含「{{ keyword }}」的智能体。</div>
          <button class="app-btn ghost small" @click="keyword = ''">清除搜索</button>
        </div>

        <!-- 子智能体不再与主智能体混排：主智能体一组，子智能体单独一组 -->
        <div v-else class="ap-groups">
          <section v-for="sec in sections" :key="sec.key" class="ap-group">
            <!-- 只剩主智能体一组时不显示组头，保持与旧版一致 -->
            <div v-if="sections.length > 1 || sec.key !== 'main'" class="ap-group-head">
              <span class="ap-group-title">{{ sec.title }}</span>
              <span v-if="sec.hint" class="ap-group-hint">{{ sec.hint }}</span>
              <span class="ap-group-count">{{ sec.members.length }}</span>
            </div>
            <div class="ap-grid">
              <!-- 卡片点击直达配置：仅可管理的智能体（普通用户看内置问渠/仅可读共享时点卡片不做任何事，
                   否则弹出的编辑框保存必被后端拒绝，是"点了报错"的死路） -->
              <article v-for="a in sec.members" :key="sec.key + '-' + a.id" class="ap-card"
                       :class="{ 'ap-card-readonly': !a.manageable }" @click="a.manageable && openEdit(a)">
                <div class="ap-card-head">
                  <a-checkbox v-if="batchMode && !isBuiltin(a)" class="ap-check" :checked="selected.includes(a.id)"
                              @click.stop @change="toggleSelect(a.id)" />
                  <AgentAvatar :agent="a" :size="24" />
                  <span class="ap-name" :title="a.name">{{ a.name }}</span>
                  <span v-if="isDefault(a) || isBuiltin(a)" class="ap-card-tags">
                    <span v-if="isDefault(a)" class="ap-tag-default">默认</span>
                    <span v-if="isBuiltin(a)" class="ap-tag-builtin" title="系统默认智能体：全员可用，仅管理员可配置">内置</span>
                  </span>
                </div>
                <p class="ap-desc" :title="a.description || ''">{{ a.description || '未填写描述' }}</p>
                <div class="ap-chips">
                  <span class="ap-chip">{{ scopeText(a) }}</span>
                  <!-- M4：绑定了工作流的智能体，回答由工作流产出（卡片上一眼可见） -->
                  <span v-if="a.workflowId" class="ap-chip ap-chip-on" :title="'回答由工作流产出：' + workflowName(a.workflowId)">
                    工作流 {{ workflowName(a.workflowId) }}
                  </span>
                  <!-- 公开发布中：弹窗里改态后同步本行，卡片即时反映，不再"发布完毫无感知" -->
                  <span v-if="a.published === 1" class="ap-chip ap-chip-on"
                        title="公开发布中：拿到链接的人可免登录与该智能体对话，点「发布」可管理链接">已发布</span>
                  <span v-for="c in capsForcedOn(a)" :key="c" class="ap-chip ap-chip-on">{{ c }}</span>
                  <span v-if="!isBuiltin(a) && scopeLabel(a)" class="ap-chip ap-chip-warn" title="已限制共享范围，点「共享」查看或修改">{{ scopeLabel(a) }}</span>
                </div>
                <div class="ap-card-foot">
                  <!-- 管理入口按后端回填的 manageable 收起：普通用户看内置问渠/仅可读的共享智能体时，
                       不给"点了报错"的死路（写路径后端还会按共享范围二次判定） -->
                  <template v-if="a.manageable">
                    <button class="app-link-btn" @click.stop="openEdit(a)">配置</button>
                    <!-- 内置问渠全员可读（可读性走内置豁免，共享范围不参与判定），共享对它不生效，不给死路入口 -->
                    <button v-if="!isBuiltin(a)" class="app-link-btn" @click.stop="openShare(a)">共享</button>
                    <button class="app-link-btn" @click.stop="openPublish(a)">发布</button>
                    <!-- 「设为默认」是全局动作（影响所有人下拉的预选），后端仅管理员放行，故对普通用户不显示 -->
                    <button v-if="!isSub(a) && !isDefault(a) && isAdmin" class="app-link-btn" @click.stop="doSetDefault(a.id)">设为默认</button>
                    <!-- 内置智能体不提供删除入口（后端也会拒绝），避免出现"点了报错"的死路；内置以卡片右上角「内置」标记区分 -->
                    <a-popconfirm v-if="!isBuiltin(a)" title="删除该智能体？对话页将不再可选" ok-text="删除" cancel-text="取消" @confirm="doDelete(a.id)">
                      <button class="app-link-btn danger" @click.stop>删除</button>
                    </a-popconfirm>
                  </template>
                  <span v-else class="ap-readonly-hint">{{ isBuiltin(a) ? '系统默认 · 管理员可配置' : '仅可使用' }}</span>
                </div>
              </article>
            </div>
          </section>
        </div>
      </div>
    </template>

    <!-- ==================== 配置视图（独立整页，不用弹窗） ==================== -->
    <template v-else>
      <div class="app-page-head">
        <button class="app-icon-btn" title="返回列表" aria-label="返回列表" @click="closeEdit"><arrow-left-outlined /></button>
        <!-- 页头标题即生效摘要（纯文本）：「名称·范围·能力」随表单实时变化 -->
        <h1 class="app-page-title ap-head-summary"><span>{{ form.name || '未命名智能体' }}</span><span class="ap-sum-tail">·{{ summaryScope }}·</span><span class="ap-sum-tail" :class="{ 'is-accent': capsTouched }">{{ summaryCaps }}</span></h1>
        <div class="ap-head-r">
          <!-- 历史版本：仅已存在的智能体有（新建尚未落版）；查看每次保存的快照与 diff，可一键回滚 -->
          <button v-if="editingId" class="app-btn ghost small" @click="openVersions">
            <history-outlined style="margin-right:4px" />历史版本
          </button>
          <button class="app-btn ghost small" @click="closeEdit">取消</button>
          <button class="app-btn small" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>

      <div class="app-page-body">
        <a-form layout="vertical" class="ap-form">
          <section class="app-card">
            <h2 class="app-card-title"><idcard-outlined class="ap-sec-ic" />身份</h2>
            <p class="ap-block-hint">对话页下拉里展示的就是名称与描述，写清楚它适合什么场景。</p>
            <p class="ap-block-hint" style="margin:0 0 8px">
              描述还是「自动派遣」的路由依据：用户开启自动派遣时，系统按名称+描述把每条问题派给最合适的智能体——
              各智能体的职责要互不重叠，重叠会导致派错。
            </p>
            <!-- 用途与图标对内置「问渠」是身份锁死项（后端同样 fail-loud 拒绝改动），配置页不再给入口：
                 「用途」改成子智能体会让唯一的默认智能体从对话页消失，「图标」换掉就丢了品牌标 -->
            <a-form-item label="用途" v-if="form.isBuiltin !== 1">
              <a-radio-group v-model:value="form.isSubagent">
                <a-radio-button :value="0">主智能体</a-radio-button>
                <a-radio-button :value="1">子智能体</a-radio-button>
              </a-radio-group>
              <div class="ap-block-hint" style="margin: 6px 0 0">
                主智能体可在对话页直接选用；子智能体不能直接选用，只能被主智能体委派去查资料。
              </div>
            </a-form-item>
            <a-form-item label="名称" required>
              <a-input v-model:value="form.name" :maxlength="200" :disabled="form.isBuiltin === 1"
                       placeholder="如：合同审查助手 / 运维排障 / 产品 FAQ" />
              <div v-if="form.isBuiltin === 1" class="ap-block-hint" style="margin:6px 0 0">
                内置智能体「问渠」是系统默认角色：名称、用途与图标随产品固定，不可修改。
              </div>
            </a-form-item>
            <a-form-item label="图标" v-if="form.isBuiltin !== 1">
              <div class="ap-icon-pick">
                <button v-for="opt in iconOptions" :key="opt.value || 'default'" type="button"
                        class="ap-icon-opt" :class="{ on: (form.icon || '') === opt.value }"
                        :title="opt.label" :aria-pressed="(form.icon || '') === opt.value"
                        @click="form.icon = opt.value">
                  <AgentAvatar :agent="{ icon: opt.value, isBuiltin: 0 }" :size="26" />
                </button>
              </div>
              <div class="ap-block-hint" style="margin:6px 0 0">
                展示在对话页智能体下拉与列表卡片上；不选即默认图标。
              </div>
            </a-form-item>
            <a-form-item label="描述" style="margin-bottom:0">
              <a-textarea v-model:value="form.description" :maxlength="500" :rows="2"
                          placeholder="写清职责范围与典型问题（如：负责《操作手册》的界面操作与表单填写问题），自动派遣将按描述把用户问题路由到本智能体" />
            </a-form-item>
          </section>

          <section class="app-card">
            <h2 class="app-card-title"><thunderbolt-outlined class="ap-sec-ic" />提示词</h2>
            <p class="ap-block-hint">智能体不绑定聊天模型：回答用哪套模型由用户在对话页选择或个人设置默认。</p>
            <a-form-item label="系统提示词" style="margin-bottom:0">
              <a-textarea v-model:value="form.systemPrompt" :rows="6"
                          placeholder="填写后完全替换全局系统提示词；留空沿用全局" />
            </a-form-item>
          </section>

          <section class="app-card">
            <h2 class="app-card-title"><database-outlined class="ap-sec-ic" />知识库范围</h2>
            <!-- 内置「问渠」：全局一只、默认库每人一个，没有可静态绑定的库——固定检索使用者自己的默认库，
                 不给任何选择入口（后端 update 同口径拒绝改动） -->
            <template v-if="form.isBuiltin === 1">
              <div class="ap-kb-fixed"><span class="ap-chip">「问渠」知识库</span></div>
              <p class="ap-block-hint" style="margin:8px 0 0">
                固定使用默认知识库「问渠」：上传到该库的资料就是它的检索范围；不可改为其它库，也不可关闭检索。
              </p>
            </template>
            <template v-else>
              <p class="ap-block-hint">限定这个智能体能检索到的内容：选择它允许使用的「知识库」（文档归属哪个库，在「文档管理」里设置）。</p>
              <a-radio-group v-model:value="scopeMode">
                <a-radio-button value="all">全部知识库</a-radio-button>
                <a-radio-button value="pick">指定知识库</a-radio-button>
                <a-radio-button value="none">不使用知识库</a-radio-button>
              </a-radio-group>
              <div v-if="scopeMode === 'pick'" class="ap-pick">
                <a-select v-model:value="form.knowledgeBaseIds" mode="multiple" :options="kbOptions" allow-clear
                          show-search option-filter-prop="label" :max-tag-count="6" style="width:100%"
                          placeholder="选择允许检索的知识库" />
                <div class="ap-block-hint" style="margin:6px 0 0">
                  已选 {{ form.knowledgeBaseIds.length }} 个知识库；一个都不选则该智能体检索不到任何内容。
                  选定后还可在下方「检索参数」覆盖该库的策略（留空即用知识库自己的配置）。
                </div>
              </div>
              <div v-else-if="scopeMode === 'none'" class="ap-block-hint" style="margin:8px 0 0">
                纯角色智能体：完全不走资料检索，仅凭系统提示词与对话上下文作答。
                适合通用法律顾问、写作助手这类不挂资料的场景；对话中手动 @ 的文档仍会被参考。
              </div>
            </template>
          </section>

          <!-- 检索参数紧跟知识库范围（库范围→库参数），宽屏双栏时与「身份/提示词」同属左栏 -->
          <section class="app-card" v-if="!form.isSubagent">
            <h2 class="app-card-title"><control-outlined class="ap-sec-ic" />检索参数</h2>
            <p class="ap-block-hint">
              留空即继承「系统设置 → 检索设置」；只填需要为这个智能体单独调整的项
              （例如法律类助手提高相似度阈值保精度、操作手册助手放宽阈值保召回）。
            </p>
            <div class="qp-grid">
              <label v-for="f in QP_FIELDS" :key="f.key" class="qp-item">
                <span class="qp-label">{{ f.label }}</span>
                <a-input v-model:value="form.qp[f.key]" :placeholder="qpPh(f)" allow-clear />
              </label>
              <label class="qp-item">
                <span class="qp-label">重排服务</span>
                <!-- 提示独占一行放控件下方：窄栏（格宽 ~284px）下行内放不下会横向溢出、画到隔壁栏卡片上 -->
                <span class="qp-rerank-ctl">
                  <a-segmented v-model:value="form.qpRerank" :options="rerankOptions" />
                  <span v-if="form.qpRerank === 'inherit' && rerankInheritHint" class="qp-inherit">{{ rerankInheritHint }}</span>
                </span>
              </label>
            </div>
          </section>

          <section class="app-card">
            <h2 class="app-card-title"><control-outlined class="ap-sec-ic" />能力</h2>
            <p class="ap-block-hint">
              默认全部沿用系统设置里的开关；只有需要为这个智能体单独破例时，才把某一项改成「开启」或「关闭」。
            </p>
            <div class="ap-caps">
              <div v-for="c in CAPS" :key="c.key" class="ap-cap-block">
                <div class="ap-cap" :class="{ overridden: capOverridden(c) }">
                  <span class="ap-cap-ic"><component :is="c.icon" /></span>
                  <div class="ap-cap-l">
                    <div class="ap-cap-name">
                      {{ c.label }}
                      <span v-if="capOverridden(c)" class="ap-cap-badge">已覆盖</span>
                    </div>
                    <div class="ap-cap-desc">
                      {{ c.desc }}<span v-if="globalText(c)" class="ap-cap-global"> · 全局{{ globalText(c) }}</span>
                    </div>
                  </div>
                  <a-segmented v-if="c.kind === 'switch'" v-model:value="form[c.key]" :options="SEG" size="small" />
                  <a-segmented v-else v-model:value="form[c.modeKey]" :options="SEG_MULTI" size="small" />
                </div>
                <!-- 「指定」模式：展开具体项多选（对齐通用智能体平台的 skills / mcps 资源列表） -->
                <div v-if="needsPick(c)" class="ap-cap-pick">
                  <a-select v-model:value="form[c.listKey]" mode="multiple" :options="optionsOf(c)" allow-clear
                            size="small" style="width:100%"
                            :placeholder="'选择' + c.label + '（一项都不选则等同「不使用」）'" />
                  <div v-if="!optionsOf(c).length" class="ap-pick-empty">
                    当前系统里没有可选项 —— 需先在设置页配置{{ c.label }}
                  </div>
                </div>
              </div>
            </div>
            <!-- 有副作用工具（沙盒/MCP）执行审批（人在回路） -->
            <div class="ap-cap" style="border-top:1px dashed var(--app-border);margin-top:10px;padding-top:12px">
              <span class="ap-cap-ic"><safety-outlined /></span>
              <div class="ap-cap-l">
                <div class="ap-cap-name">工具执行确认</div>
                <div class="ap-cap-desc">
                  针对「沙盒执行」「MCP 外部工具」及「联网搜索」（系统设置开启其「纳入执行审批」后纳入）这几类
                  有副作用的工具：自动执行=模型直接调用；
                  执行前确认=每次调用先暂停等你批准（拒绝/超时后模型会收到未执行提示继续回答）；
                  禁用=不给模型沙盒与 MCP 工具（联网搜索启停由自身开关决定，不受禁用影响）。游客分享会话本就不暴露它们。
                </div>
              </div>
              <a-select v-model:value="form.toolApprovalMode" size="small" style="width:120px" :options="approvalOptions" />
            </div>
            <div class="ap-cap">
              <span class="ap-cap-ic"><thunderbolt-outlined /></span>
              <div class="ap-cap-l">
                <div class="ap-cap-name">单轮工具步数上限</div>
                <div class="ap-cap-desc">
                  一轮问答里全部工具的调用总次数上限，防止模型陷入「调工具→不满意→再调」的失控循环；
                  达到上限后模型会直接给出最终回答。留空跟随全局当前值（默认 {{ globalMaxToolSteps }}），0=不限制。
                </div>
              </div>
              <a-input-number v-model:value="form.maxToolSteps" :min="0" :max="50" :step="1" size="small"
                              style="width:120px" :placeholder="`全局 ${globalMaxToolSteps}`" />
            </div>
          </section>

          <section class="app-card" v-if="!form.isSubagent">
            <h2 class="app-card-title"><apartment-outlined class="ap-sec-ic" />子智能体委派</h2>
            <p class="ap-block-hint">
              选中后，复杂问题会并行交给这些子智能体各自检索——各按自己的知识库范围与角色视角，再汇总作答。
              不选则沿用系统的多视角并行检索。
            </p>
            <a-select v-model:value="form.subAgentIds" mode="multiple" :options="subOptions" allow-clear
                      show-search option-filter-prop="label" :max-tag-count="6" style="width:100%"
                      :placeholder="subOptions.length ? '选择允许委派的子智能体（最多 4 个）'
                        : '还没有子智能体——先在列表新建一个「用途 = 子智能体」的条目'" />
            <!-- M5：委派编排一键转工作流（保存后转换的是已保存的委派关系） -->
            <div v-if="(form.subAgentIds || []).length" class="ap-block-hint" style="margin:8px 0 0;display:flex;align-items:center;gap:10px">
              <span>想把这套并行编排变成可画可跑的工作流？</span>
              <button class="app-btn ghost small" :disabled="toWfLoading" @click="toWorkflow">
                <partition-outlined /> 转成工作流
              </button>
            </div>
          </section>

          <section class="app-card" v-if="!form.isSubagent">
            <h2 class="app-card-title"><apartment-outlined class="ap-sec-ic" />工作流编排（chatflow）</h2>
            <p class="ap-block-hint">
              绑定后该智能体的回答<b>由工作流产出</b>：每轮把你的问题送进图，结束节点的 answer 出参即回答；
              智能体自带的人设/知识库/工具配置不再参与（逻辑由工作流定义）。只列出<b>已发布</b>的工作流。
            </p>
            <a-select v-model:value="form.workflowId" :options="workflowOptions" allow-clear
                      show-search option-filter-prop="label" style="width:100%"
                      placeholder="不绑定——按下面的模型/知识库/工具配置作答" />
          </section>

          <section class="app-card" v-if="!form.isSubagent">
            <h2 class="app-card-title"><star-outlined class="ap-sec-ic" />默认</h2>
            <a-checkbox v-model:checked="form.isDefault">设为默认智能体</a-checkbox>
            <span class="ap-block-hint" style="margin-left:8px">对话页打开时预选它（同一时间只有一个默认）</span>
          </section>
        </a-form>
      </div>
    </template>

    <!-- 共享范围（公共组件：与文档 / API Key 同一套两区表单） -->
    <ShareScopeModal v-model:open="shareVisible" resource-label="智能体" read-verb="使用"
                     :share-config="shareTarget.shareConfig" :save-fn="saveShareFn" @saved="reload" />

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
        <div v-else-if="!verLoading" class="ap-ver-none">暂无历史版本：保存一次配置后即产生第一条版本记录。</div>
      </a-spin>
    </a-drawer>

    <!-- 公开分享（/s/{token} 免登录对话 + iframe 嵌入 + MCP 端点）
         结构：状态卡（一个总开关说清"发布没发布"）→ 游客能力（模型 / MCP）→ 分发方式（Tab 三选一）。
         此前把链接、iframe、MCP 三段形态连同四段说明文字平铺一列，弹窗被撑到一屏半、
         复制按钮被输入框挤成竖排（"复 制"/"打 开"），且看不出三者其实是同一 token 的三种用法。 -->
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
        </div>
        <a-switch v-model:checked="pubEnabled" @change="savePublish" />
      </div>

      <!-- 游客能力：模型归属发布者（只能选自己可用的），MCP 端点是分享的附加形态 -->
      <div class="pub-block">
        <div class="pub-block-h">游客能力</div>
        <div class="pub-field">
          <span class="pub-label">对话模型</span>
          <ModelSelect v-model="pubModelRef" type="chat" width="100%" inherit-label="跟随发布者个人默认模型" @change="savePublish" />
        </div>
        <div class="pub-switch-row">
          <a-switch v-model:checked="pubMcpEnabled" :disabled="!pubEnabled" size="small" @change="savePublish" />
          <div class="pub-switch-t">
            <div class="pub-switch-label">开放 MCP 端点</div>
            <div class="pub-switch-d">开启后同一个 token 兼作 MCP 凭据，Claude / Cursor 等客户端可直连</div>
          </div>
        </div>
        <div v-if="!pubEnabled" class="pub-warn">
          <exclamation-circle-outlined />
          <span>MCP 端点依赖公开分享，且需管理员在「系统设置 → MCP 服务（双向）」开启「对外提供 MCP 端点」</span>
        </div>
      </div>

      <!-- 分发形态：三选一。共享同一个 token，切换不产生新链接 -->
      <div class="pub-block" v-if="pubToken && pubEnabled">
        <div class="pub-block-h">分发方式</div>
        <a-tabs v-model:activeKey="pubTab" size="small" class="pub-tabs">
          <a-tab-pane key="link" tab="分享链接">
            <div class="pub-code-box">
              <code class="pub-code">{{ shareUrl }}</code>
              <!-- 按钮浮在代码框内右上角：此前独占一列，把本就单行的 URL 挤成两行 -->
              <div class="pub-code-act">
                <a :href="shareUrl" target="_blank" rel="noopener" class="pub-icon-btn" title="在新标签打开" aria-label="打开链接">
                  <export-outlined />
                </a>
                <button class="pub-icon-btn" title="复制链接" aria-label="复制链接"
                        @click="copyText(shareUrl, '链接已复制')"><copy-outlined /></button>
              </div>
            </div>
            <div class="pub-hint">发给任何人即可免登录对话。游客检索知识库按你的可见范围执行。</div>
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
              <span class="pub-size-hint">改尺寸会同步进下面的代码，复制走即可</span>
            </div>
            <div class="pub-code-box">
              <pre class="pub-code block">{{ iframeSnippet }}</pre>
              <div class="pub-code-act">
                <button class="pub-icon-btn" title="复制嵌入代码" aria-label="复制嵌入代码"
                        @click="copyText(iframeSnippet, '嵌入代码已复制')"><copy-outlined /></button>
              </div>
            </div>
            <div class="pub-hint">粘贴到任意网页。游客能力收窄：沙盒 / 产物 / MCP / 技能执行不对访客暴露。</div>
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
                粘贴到 Claude Desktop 的 Integrations 或项目根目录 .cursor/mcp.json。
                地址含凭据等同密钥：撤销分享或关掉上面开关立即失效。外部调用同样受限——沙盒 / 产物 / 个人技能与个人 MCP 不暴露。
              </div>
            </template>
            <div v-else class="pub-placeholder">
              <ApiOutlined />
              <div>尚未开放 MCP 端点</div>
              <div class="pub-hint">在「游客能力」里打开开关后，这里会给出可直接粘贴的端点地址与配置文件。</div>
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
          <div class="pub-hint">token 已保留，重新开启上面的总开关即恢复访问，链接不变。</div>
        </div>
      </div>

      <div class="pub-foot">
        <a-popconfirm title="撤销后链接立即失效，重新发布会生成新链接，确定撤销？" ok-text="撤销" cancel-text="取消" @confirm="doRevoke">
          <button class="app-link-btn danger" :disabled="!pubToken">撤销分享</button>
        </a-popconfirm>
        <span class="pub-foot-hint" v-if="pubToken && pubEnabled">链接即凭据，请勿公开到不受控的渠道</span>
      </div>
    </a-modal>

    <!-- 委派编排视图：主智能体 → 子智能体委派关系拓扑（数据全部来自已加载列表，纯前端渲染） -->
    <a-modal v-model:open="topoOpen" title="委派编排视图" :width="900" :footer="null" class="ap-topo-modal">
      <div class="ap-topo-legend">
        <span class="ap-lg"><span class="ap-lg-dot main" />主智能体</span>
        <span class="ap-lg"><span class="ap-lg-dot sub" />子智能体</span>
        <span class="ap-lg"><svg width="30" height="8"><line x1="0" y1="4" x2="30" y2="4" class="ap-edge miss" /></svg>失效引用（子智能体已删除）</span>
        <span class="ap-lg">悬停高亮委派链路 · 点击节点直接进配置</span>
      </div>
      <div v-if="topoData.warnings.length" class="ap-topo-warns">
        <div v-for="(w, wi) in topoData.warnings" :key="wi" class="ap-topo-warn">{{ w }}</div>
      </div>
      <div v-if="!topoData.subs.length && !topoData.edges.length" class="ap-topo-empty">
        还没有委派关系：先新建一个「用途 = 子智能体」的智能体，再到主智能体配置的「子智能体委派」里勾选它。
      </div>
      <div v-else class="ap-topo-scroll">
        <svg :viewBox="`0 0 ${TOPO_W} ${topoData.H}`" :width="TOPO_W" :height="topoData.H" class="ap-topo-svg">
          <!-- 委派边（失效引用：红色虚线 + 右列占位符） -->
          <path v-for="e in topoData.edges" :key="'e' + e.from.id + (e.to ? e.to.id : 'x' + e.toId)"
                :d="edgePath(e)" class="ap-edge" :class="[edgeCls(e), { miss: e.missing }]" />
          <g v-for="e in topoData.edges.filter(x => x.missing)" :key="'x' + e.from.id + e.toId">
            <rect :x="SUB_X" :y="topoData.mainPos[String(e.from.id)] + TOPO.rowH / 2 - 13" width="26" height="26" rx="6" class="ap-phantom" />
            <text :x="SUB_X + 13" :y="topoData.mainPos[String(e.from.id)] + TOPO.rowH / 2 + 5" class="ap-phantom-t">!</text>
          </g>
          <!-- 主智能体列（点击直达配置；不可管理的节点点了不做任何事） -->
          <g v-for="a in topoData.mains" :key="'m' + a.id" class="ap-node" :class="nodeCls('m' + a.id)"
             @mouseenter="topoHover = 'm' + a.id" @mouseleave="topoHover = ''"
             @click="a.manageable ? (openEdit(a), topoOpen = false) : null">
            <rect :x="MAIN_X" :y="topoData.mainPos[String(a.id)]" :width="TOPO.nodeW" :height="TOPO.rowH" rx="8" class="ap-nrect main" />
            <text :x="MAIN_X + 12" :y="topoData.mainPos[String(a.id)] + 22" class="ap-nname">{{ trunc(a.name, MAIN_NAME) }}</text>
            <text :x="MAIN_X + 12" :y="topoData.mainPos[String(a.id)] + 40" class="ap-ndesc">{{ trunc(a.description || '未填写描述', MAIN_LINE) }}</text>
            <text :x="MAIN_X + 12" :y="topoData.mainPos[String(a.id)] + 56" class="ap-nmeta">
              {{ subCountOf(a) ? `委派 ${subCountOf(a)} 个子智能体` : '未委派子智能体' }}
            </text>
          </g>
          <!-- 子智能体列（无人委派 = 灰虚线框；点击同主列，不可管理不响应） -->
          <g v-for="a in topoData.subs" :key="'s' + a.id" class="ap-node" :class="nodeCls('s' + a.id)"
             @mouseenter="topoHover = 's' + a.id" @mouseleave="topoHover = ''"
             @click="a.manageable ? (openEdit(a), topoOpen = false) : null">
            <rect :x="SUB_X" :y="topoData.subPos[String(a.id)]" :width="TOPO.subW" :height="TOPO.rowH" rx="8"
                  class="ap-nrect sub" :class="{ orphan: !topoData.parents[String(a.id)].length }" />
            <text :x="SUB_X + 12" :y="topoData.subPos[String(a.id)] + 22" class="ap-nname">{{ trunc(a.name, SUB_NAME) }}</text>
            <text :x="SUB_X + 12" :y="topoData.subPos[String(a.id)] + 40" class="ap-ndesc">{{ trunc(a.description || '未填写描述', SUB_LINE) }}</text>
            <text :x="SUB_X + 12" :y="topoData.subPos[String(a.id)] + 56" class="ap-nmeta">
              {{ topoData.parents[String(a.id)].length ? `被 ${topoData.parents[String(a.id)].length} 个主智能体委派` : '暂无人委派' }}
            </text>
          </g>
        </svg>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ArrowLeftOutlined, ReloadOutlined, SearchOutlined, IdcardOutlined,
  ThunderboltOutlined, DatabaseOutlined, ControlOutlined, StarOutlined, ApartmentOutlined,
  FileSearchOutlined, CalculatorOutlined, FileDoneOutlined, AppstoreOutlined, ApiOutlined,
  SafetyOutlined, PartitionOutlined, GlobalOutlined, HistoryOutlined, DownOutlined,
  CopyOutlined, ExportOutlined, LinkOutlined, ExclamationCircleOutlined
} from '@ant-design/icons-vue'
import { listAgents, createAgent, updateAgent, deleteAgent, batchDeleteAgents, setAgentDefault, listKnowledgeBases, getConfig,
         listSkills, getMcpStatus, listSubAgents, updateAgentShare, getKbParamDefaults,
         getAgentPublish, publishAgent, revokeAgentPublish, listWorkflows, createWorkflowFromAgent,
         listAgentVersions, rollbackAgent } from '../api'
import ShareScopeModal from './ShareScopeModal.vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import ModelSelect from '../components/ModelSelect.vue'
import AgentAvatar from '../components/AgentAvatar.vue'
import { ensureAuth, isAdminSync } from '../utils/auth'

// ==================== 能力定义 ====================
// path：该能力在全局配置里的开关路径；gate：还受此总闸制约（关掉总闸时能力不生效）
const CAPS = [
  { key: 'toolKnowledge', label: '知识库检索', desc: '回答过程中可自主检索知识库补充依据', icon: FileSearchOutlined,
    kind: 'switch', path: ['tool', 'knowledgeRetrieval', 'enabled'], gate: ['tool', 'enabled'] },
  // 以下三项是「多实例能力」：除总开关外，还能指定具体用哪几个（对齐通用智能体平台的资源列表语义）
  { key: 'toolBuiltin', label: '内置高频工具', desc: '算术计算 / 当前时间 / 日期相差天数', icon: CalculatorOutlined,
    kind: 'list', modeKey: 'builtinMode', listKey: 'builtinTools', optionsKey: 'builtinOptions',
    path: ['tool', 'builtin', 'enabled'], gate: ['tool', 'enabled'] },
  { key: 'toolArtifact', label: '产物交付', desc: '生成 Markdown / CSV / JSON / HTML 文件并附下载卡片', icon: FileDoneOutlined,
    kind: 'switch', path: ['tool', 'artifact', 'enabled'], gate: ['tool', 'enabled'] },
  // 技能与 MCP 是**个人资产**：这里选中的是「引用串」——个人资源存 {uid}/{name}，
  // 只对该资源的归属人生效（别人的同名资源不会被拿来顶替）；内置技能存裸名，对所有人按名字生效。
  // 两者都没有独立的全局开关（配置里已无 skill.enabled / mcp.enabled），只受「工具调用」总闸制约。
  { key: 'toolSkill', label: '技能 Skills', desc: '限定用哪几个技能（内置技能对所有人生效；你名下的技能只对你生效）', icon: AppstoreOutlined,
    kind: 'list', modeKey: 'skillMode', listKey: 'skills', optionsKey: 'skillOptions',
    gate: ['tool', 'enabled'] },
  { key: 'toolMcp', label: 'MCP 外部工具', desc: '限定连哪几个 MCP 服务（仅对你生效）', icon: ApiOutlined,
    kind: 'list', modeKey: 'mcpMode', listKey: 'mcps', optionsKey: 'mcpOptions',
    gate: ['tool', 'enabled'] },
  { key: 'toolWebsearch', label: '联网搜索', desc: '模型可自主联网检索；命中结果登记为网页来源，与知识库来源共用引用编号', icon: GlobalOutlined,
    kind: 'switch', path: ['webSearch', 'enabled'], gate: ['tool', 'enabled'] }
]
// 开关型三态：''=跟随全局 / '1'=开启 / '0'=关闭（对应后端 tool_* 的 1/0/null）
const SEG = [
  { label: '跟随全局', value: '' },
  { label: '开启', value: '1' },
  { label: '关闭', value: '0' }
]
// 有副作用工具（沙盒/MCP）执行审批三态
const approvalOptions = [
  { value: 'auto', label: '自动执行' },
  { value: 'ask', label: '执行前确认' },
  { value: 'off', label: '禁用' }
]
// 多实例能力：跟随全局 / 指定（选「指定」才展开具体项多选）/ 不使用
const SEG_MULTI = [
  { label: '跟随全局', value: 'inherit' },
  { label: '指定', value: 'pick' },
  { label: '不使用', value: 'none' }
]
// 内置工具可选项（value 与后端 BuiltinTools 的 @Tool 方法名一致，按名匹配注册；
// 中文名与后端 ToolInventoryService.LABELS 保持同步——新增工具两处都要补）
const BUILTIN_TOOL_OPTIONS = [
  { value: 'calculate', label: '算术计算' },
  { value: 'currentDateTime', label: '当前日期时间' },
  { value: 'daysBetween', label: '日期相差天数' },
  { value: 'addDays', label: '日期推算' },
  { value: 'randomNumber', label: '随机数' },
  { value: 'uuid', label: '生成 UUID' },
  { value: 'unitConvert', label: '单位换算' },
  { value: 'textStats', label: '文本统计' },
  { value: 'base64', label: 'Base64 编解码' },
  { value: 'hash', label: '哈希计算' }
]

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
// 全局配置快照（仅管理员可读；普通用户保持 null，能力行不显示「全局开/关」）
const cfg = ref(null)
// 管理员级身份：reload 里 ensureAuth 到位后刷新（localStorage 兜底仅在已拉取过时可靠）
const isAdmin = ref(isAdminSync())

// 多实例能力的可选项：内置工具（前端常量）/ 技能 / MCP Server（后两者来自接口）
const builtinOptions = ref(BUILTIN_TOOL_OPTIONS)
const skillOptions = ref([])
const mcpOptions = ref([])
// 可委派的子智能体下拉（4.3：来自 /agent/sub，仅作为主智能体的委派候选，本身不参与对话）
const subOptions = ref([])
/** M4：工作流列表与可绑定选项（只列已发布版本） */
const workflowRows = ref([])
const workflowOptions = ref([])
const OPTION_REFS = { builtinOptions, skillOptions, mcpOptions }
const optionsOf = c => OPTION_REFS[c.optionsKey]?.value || []

const editing = ref(false)
const editingId = ref('')
const scopeMode = ref('all')
const blankForm = () => ({
  name: '', description: '', systemPrompt: '', knowledgeBaseIds: [], isDefault: false,
  // 图标：''=默认展示（内置「问渠」用问渠品牌标，其余机器人）；'wenqu'=品牌标；emoji=表情
  icon: '', isBuiltin: 0,
  // 开关型：'' = 跟随全局 / '1' = 开启 / '0' = 关闭
  toolKnowledge: '', toolBuiltin: '', toolSkill: '', toolArtifact: '', toolMcp: '', toolWebsearch: '',
  // 有副作用工具（沙盒/MCP）执行审批：auto=自动执行 ask=执行前确认 off=禁用
  toolApprovalMode: 'auto',
  // 单轮工具调用步数上限：null=跟随全局当前值（agent.maxToolSteps）；0=不限制
  maxToolSteps: null,
  // 多实例能力：模式（inherit/none/pick）+ 选「指定」时的具体项
  builtinMode: 'inherit', builtinTools: [],
  skillMode: 'inherit', skills: [],
  mcpMode: 'inherit', mcps: [],
  // 新增时必须给出默认值：save() 会直接读这两个字段，缺失会让整个保存动作抛错
  isSubagent: 0, subAgentIds: [],
  // 检索参数覆盖：留空 = 继承全局「系统设置 → 检索设置」；非空的项才写入 queryParams
  qp: blankQp(), qpRerank: 'inherit',
  // M4：绑定的工作流 ID（空 = 不绑定，按模型/知识库/工具配置作答）
  workflowId: null
})
/** 检索参数覆盖：可覆盖的项（值即后端 ConfigService 的完整配置键） */
const QP_FIELDS = [
  { key: 'vectorWeight', label: '向量权重', path: 'retrieval.vectorWeight', ph: '0~1，留空继承全局' },
  { key: 'keywordWeight', label: '关键词权重', path: 'retrieval.keywordWeight', ph: '0~1，留空继承全局' },
  { key: 'vecThreshold', label: '相似度阈值', path: 'retrieval.vecThreshold', ph: '0~1，留空继承全局' },
  { key: 'vectorTopK', label: '向量召回数', path: 'retrieval.vectorTopK', ph: '如 15，留空继承全局' },
  { key: 'keywordLimit', label: '关键词召回数', path: 'retrieval.keywordLimit', ph: '如 20，留空继承全局' }
]
const blankQp = () => ({ vectorWeight: null, keywordWeight: null, vecThreshold: null, vectorTopK: null, keywordLimit: null })
// 「系统设置 → 检索设置」的当前全局值（/kb/param-defaults，普通用户可读）：
// 占位符直接展示继承的是哪个值，而不是让用户去猜/翻设置页。口径同 KnowledgeBasePage 的 numPh/triPh。
const qpDefaults = ref(null)
/** 占位符：有全局值 → 「留空继承全局（当前 X）」；拿不到（接口异常/该项未配置）→ 回退静态提示 */
const qpPh = f => {
  const v = String(qpDefaults.value?.[f.path] ?? '').trim()
  return v === '' ? f.ph : `留空继承全局（当前 ${v}）`
}
/** 重排三态：恒定短 label（控件宽度稳定，不再把全局状态拼进选项文案——长 label 曾把 qp-grid 轨道按
 *  min-content 撑出卡片）。全局当前状态改走 rerankInheritHint 灰字，语义对齐其他项的「留空继承全局（当前 X）」 */
const rerankOptions = [
  { label: '跟随全局', value: 'inherit' },
  { label: '开启', value: 'on' },
  { label: '关闭', value: 'off' }
]
/** 继承时的灰字提示：全局重排开关当前状态（读不到则不显示） */
const rerankInheritHint = computed(() => {
  const v = String(qpDefaults.value?.['rerank.enabled'] ?? '').trim()
  return v === 'true' ? '全局当前：开启' : (v === 'false' ? '全局当前：关闭' : '')
})

/** queryParams(JSON 串) → 表单（未配置的项为 null = 继承全局） */
function parseQueryParams (json) {
  const qp = blankQp()
  let ps = null
  try { ps = json ? JSON.parse(json) : null } catch (e) { ps = null }
  if (ps && typeof ps === 'object') {
    for (const f of QP_FIELDS) {
      const v = ps[f.path]
      if (v !== undefined && v !== null && String(v).trim() !== '') qp[f.key] = v
    }
  }
  // 重排三态：显式配过才显示"已覆盖"
  let rr = 'inherit'
  if (ps && ps['rerank.enabled'] !== undefined && ps['rerank.enabled'] !== null) {
    rr = String(ps['rerank.enabled']) === 'true' ? 'on' : 'off'
  }
  return { qp, rr }
}

/** 表单 → queryParams(JSON 串)；全部留空返回空串（后端存 null = 全部继承全局设置） */
function buildQueryParams (qp, rr) {
  const o = {}
  for (const f of QP_FIELDS) {
    const v = qp ? qp[f.key] : null
    if (v !== null && v !== undefined && String(v).trim() !== '') o[f.path] = String(v).trim()
  }
  if (rr === 'on' || rr === 'off') o['rerank.enabled'] = rr === 'on' ? 'true' : 'false'
  return Object.keys(o).length ? JSON.stringify(o) : ''
}

const form = ref(blankForm())

// 图标可选集：问渠品牌标为内置「问渠」专属（后端同样强制），非内置智能体的选项里不出现。
// 内置问渠整块「图标」已隐藏，这里恒走过滤分支
const iconOptions = computed(() => ICON_OPTIONS.filter(o => o.value !== 'wenqu'))

const isDefault = a => a.isDefault === 1 || a.isDefault === true
/** 系统内置（如默认「知识库助手」）：不可删除，卡片上以「内置」标记区分 */
const isBuiltin = a => a.isBuiltin === 1 || a.isBuiltin === true
/** 子智能体：不直接参与对话，只能被主智能体委派 */
const isSub = a => a.isSubagent === 1 || a.isSubagent === true
/** 逗号串 → 数组（具体项） */
const splitList = v => (v ? String(v).split(',').filter(Boolean) : [])
const matchKw = a => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return true
  return String(a.name || '').toLowerCase().includes(kw) || String(a.description || '').toLowerCase().includes(kw)
}
/**
 * 列表分组：主智能体一组，子智能体单独一组，互不混排。
 * 搜索关键词同时作用于所有组，过滤后为空的组不显示。
 */
const sections = computed(() => {
  const secs = []
  const mains = agents.value.filter(a => !isSub(a) && matchKw(a))
  if (mains.length) {
    secs.push({ key: 'main', title: '主智能体', hint: '可在对话页直接选用', members: mains })
  }
  const subs = agents.value.filter(a => isSub(a) && matchKw(a))
  if (subs.length) {
    secs.push({ key: 'sub', title: '子智能体', hint: '不直接参与对话，供主智能体并行委派', members: subs })
  }
  return secs
})
const scopeText = a => {
  // 内置「问渠」：固定检索使用者的默认库「问渠」（每人一个，运行时按使用者解析，无静态绑定）
  if (isBuiltin(a)) return '「问渠」知识库'
  if (a.knowledgeDisabled === 1 || a.knowledgeDisabled === true) return '不使用知识库'
  const n = String(a.knowledgeBaseIds || '').split(',').filter(Boolean).length
  if (!n) return '全部知识库'
  return n === 1 ? '限 1 个知识库' : `限 ${n} 个知识库`
}

// ==================== 委派编排视图（拓扑：主智能体 → 子智能体委派关系，纯前端渲染） ====================
/** 绑定工作流时卡片上显示的名字（工作流已删除/不可见则显示 id，不谎报） */
const workflowName = id => {
  const w = workflowRows.value.find(x => String(x.id) === String(id))
  return w ? (w.name || id) : `#${id}` + '（不可用）'
}
const topoOpen = ref(false)
const topoHover = ref('')   // 悬停的节点 key（'m'+id / 's'+id，用于高亮相关委派链路）
const TOPO = { pad: 16, rowH: 64, rowGap: 14, nodeW: 216, gapG: 80, subW: 248 }
const TOPO_W = TOPO.pad + TOPO.nodeW + TOPO.gapG + TOPO.subW + TOPO.pad
const MAIN_X = TOPO.pad
const SUB_X = TOPO.pad + TOPO.nodeW + TOPO.gapG

const topoData = computed(() => {
  const mains = agents.value.filter(a => !isSub(a))
  const subs = agents.value.filter(isSub)
  const subById = new Map(subs.map(a => [String(a.id), a]))
  const edges = []
  const warnings = []
  for (const m of mains) {
    for (const sid of splitList(m.subAgentIds)) {
      const sub = subById.get(String(sid))
      if (sub) edges.push({ from: m, to: sub, missing: false })
      else {
        // fail-loud：悬空引用直接在图上标红并给出文字警示（静默画不出来会让人误以为配置生效了）
        edges.push({ from: m, to: null, toId: String(sid), missing: true })
        warnings.push(`「${m.name}」引用的子智能体 (#${sid}) 不存在或已删除——该委派不会生效`)
      }
    }
  }
  // 反向关系：每个子智能体被哪些主智能体委派（孤儿检测）
  const parents = {}
  for (const s of subs) parents[String(s.id)] = []
  for (const e of edges) if (!e.missing) parents[String(e.to.id)].push(e.from)
  const orphanCount = subs.filter(s => !parents[String(s.id)].length).length
  if (orphanCount) warnings.push(`${orphanCount} 个子智能体还没被任何主智能体委派（图中灰虚线框）`)
  // 两列布局，各自按行数垂直居中
  const colH = n => Math.max(n, 0) * (TOPO.rowH + TOPO.rowGap) - TOPO.rowGap
  const rows = Math.max(mains.length, subs.length, 1)
  const H = colH(rows) + TOPO.pad * 2
  const yOf = (i, count) => Math.round(TOPO.pad + (H - TOPO.pad * 2 - colH(count)) / 2) + i * (TOPO.rowH + TOPO.rowGap)
  const mainPos = {}
  const subPos = {}
  mains.forEach((a, i) => { mainPos[String(a.id)] = yOf(i, mains.length) })
  subs.forEach((a, i) => { subPos[String(a.id)] = yOf(i, subs.length) })
  return { mains, subs, edges, warnings, H, mainPos, subPos, parents }
})

/**
 * 按显示宽度截断：全角 = 1 字宽、半角 ≈ 0.55。
 * 纯字数截断不可靠——中文按字号折算一行只装得下十几字，超宽文本会溢出节点矩形、
 * 被 SVG 视口右缘裁掉（首版"显示不全"的根因）。maxFull = 该行可容纳的全角字数。
 */
const trunc = (s, maxFull) => {
  const t = String(s == null ? '' : s)
  let w = 0
  for (let i = 0; i < t.length; i++) {
    w += t.charCodeAt(i) > 255 ? 1 : 0.55
    if (w > maxFull) return t.slice(0, i) + '…'
  }
  return t
}
// 每行可容纳的全角字数（节点宽 - 左右内边距 12×2，按字号折算：名称 13px / 描述与元信息 11px）
const MAIN_NAME = Math.floor((TOPO.nodeW - 24) / 13)
const MAIN_LINE = Math.floor((TOPO.nodeW - 24) / 11)
const SUB_NAME = Math.floor((TOPO.subW - 24) / 13)
const SUB_LINE = Math.floor((TOPO.subW - 24) / 11)
const subCountOf = a => splitList(a.subAgentIds).length

// ==================== M5：委派编排一键转工作流 ====================
import { useRoute, useRouter } from 'vue-router'
const route = useRoute()
const router = useRouter()
const toWfLoading = ref(false)
/**
 * 把该智能体已保存的委派编排（subAgentIds 并行委派）转成工作流：
 * 后端按库里的委派关系生成「start 扇出 subagent → template 聚合 → llm 总结 → end」
 * 并创建草稿工作流；成功后跳到「工作流」Tab，列表首行（更新时间倒序）就是它。
 */
const toWorkflow = async () => {
  if (!form.id) {
    message.warning('请先保存智能体（委派关系以已保存的为准），再转换工作流')
    return
  }
  toWfLoading.value = true
  try {
    const r = await createWorkflowFromAgent(form.id)
    if (r && r.success) {
      message.success(`已按已保存的委派关系创建工作流「${r.data.name}」，可在画布继续调整后发布`)
      router.replace({ query: { ...route.query, tab: 'workflow' } })
    } else {
      message.error((r && r.msg) || '转换失败')
    }
  } catch (e) {
    message.error('转换失败：' + (e.message || ''))
  } finally {
    toWfLoading.value = false
  }
}

/** 边路径：主节点右缘中点 → 子节点左缘中点（贝塞尔）；失效引用画到右列同高的占位符 */
function edgePath (e) {
  const y1 = topoData.value.mainPos[String(e.from.id)] + TOPO.rowH / 2
  const x1 = MAIN_X + TOPO.nodeW
  const y2 = e.missing ? y1 : topoData.value.subPos[String(e.to.id)] + TOPO.rowH / 2
  const x2 = e.missing ? SUB_X + 13 : SUB_X
  const mx = (x1 + x2) / 2
  return `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`
}

/** 悬停高亮：与悬停节点相连的边/节点保持醒目，其余淡化（一眼看清单条委派链路） */
const hoverRelated = computed(() => {
  if (!topoHover.value) return null
  const set = new Set([topoHover.value])
  for (const e of topoData.value.edges) {
    if ('m' + e.from.id === topoHover.value || (e.to && 's' + e.to.id === topoHover.value)) {
      set.add('m' + e.from.id)
      if (e.to) set.add('s' + e.to.id)
    }
  }
  return set
})
const edgeCls = e => {
  if (!hoverRelated.value) return ''
  const rel = hoverRelated.value.has('m' + e.from.id) && (!e.to || hoverRelated.value.has('s' + e.to.id))
  return rel ? 'on' : 'dim'
}
const nodeCls = key => (hoverRelated.value && !hoverRelated.value.has(key) ? 'dim' : '')

// ==================== 共享范围（弹窗为公共组件 ShareScopeModal） ====================
const shareVisible = ref(false)
const shareTarget = ref({ id: '', shareConfig: '' })
const saveShareFn = json => updateAgentShare(shareTarget.value.id, json)
// 卡片上的共享范围标记：仅显式共享时显示（未配置=私有即默认，不显示以免噪音）
function scopeLabel (a) {
  if (!a.shareConfig || !String(a.shareConfig).trim()) return ''
  let parsed = null
  try { parsed = JSON.parse(a.shareConfig) } catch (e) { return '' }
  const r = (parsed && parsed.read_scope) || {}
  const lvl = r.access_level || 'global'
  if (lvl === 'global') return '全员共享'
  if (lvl === 'department') {
    const n = Array.isArray(r.department_ids) ? r.department_ids.length : 0
    return n ? '限 ' + n + ' 个部门' : '部门可见'
  }
  if (lvl === 'user') {
    const n = Array.isArray(r.user_uids) ? r.user_uids.length : 0
    return n ? '限 ' + n + ' 人' : '指定人可见'
  }
  return ''
}
function openShare (a) {
  shareTarget.value = { id: a.id, shareConfig: a.shareConfig || '' }
  shareVisible.value = true
}

// ==================== 公开发布（/s/{token} 免登录对话 + iframe 嵌入） ====================
const pubVisible = ref(false)
const pubAgentId = ref('')
const pubAgentName = ref('')
/** 弹窗标题栏的智能体头像：与列表卡片共用 AgentAvatar 的 icon 解析口径（品牌标 / emoji / 默认机器人） */
const pubAgent = ref({ name: '', icon: '', isBuiltin: 0 })
const pubEnabled = ref(false)
const pubMcpEnabled = ref(false)
const pubModelRef = ref('')
const pubToken = ref('')
/** 分发方式当前页签（三种形态共用一个 token，切页签不产生新链接） */
const pubTab = ref('link')
/** iframe 嵌入尺寸：此前 420×640 写死在代码里，用户想改只能复制出去手改。做成可调并实时反映进代码 */
const pubFrameW = ref(420)
const pubFrameH = ref(640)
const shareUrl = computed(() => pubToken.value ? window.location.origin + '/s/' + pubToken.value : '')
// MCP 端点（/ai/mcp/{token}）：与网页分享同一 token，token 即凭据
const mcpUrl = computed(() => pubToken.value ? window.location.origin + '/ai/mcp/' + pubToken.value : '')
const mcpJson = computed(() => mcpUrl.value
  ? JSON.stringify({ mcpServers: { [pubAgentName.value || 'wenqu']: { url: mcpUrl.value } } }, null, 2)
  : '')
const iframeSnippet = computed(() => shareUrl.value
  ? `<iframe src="${shareUrl.value}?embed=1" style="width:${pubFrameW.value}px;height:${pubFrameH.value}px;border:1px solid #e5e6eb;border-radius:12px" title="${pubAgentName.value || 'AI 助手'}"></iframe>`
  : '')

async function openPublish (a) {
  pubAgentId.value = a.id
  pubAgentName.value = a.name || ''
  pubAgent.value = { name: a.name || '', icon: a.icon || '', isBuiltin: a.isBuiltin || 0 }
  pubVisible.value = true
  pubToken.value = ''
  pubEnabled.value = false
  pubMcpEnabled.value = false
  pubModelRef.value = ''
  pubTab.value = 'link'
  try {
    const r = await getAgentPublish(a.id)
    if (r && r.success !== false && r.data) {
      pubEnabled.value = !!r.data.enabled
      pubMcpEnabled.value = !!r.data.mcpEnabled
      pubModelRef.value = r.data.modelRef || ''
      pubToken.value = r.data.token || ''
    }
  } catch (e) { message.error(e.message || '分享配置加载失败') }
}

async function savePublish () {
  // MCP 端点是分享的附加形态：分享停用后链接与端点一并失效，前端同步关掉避免发布出无效组合
  if (!pubEnabled.value) pubMcpEnabled.value = false
  try {
    const r = await publishAgent(pubAgentId.value, {
      enabled: pubEnabled.value,
      mcpEnabled: pubMcpEnabled.value,
      modelRef: pubModelRef.value || ''
    })
    if (r && r.success !== false && r.data) {
      pubToken.value = r.data.token || ''
      pubMcpEnabled.value = !!r.data.mcpEnabled
      // 同步列表行：卡片上的「已发布」标记即时反映，不用重拉列表
      const row = agents.value.find(x => x.id === pubAgentId.value)
      if (row) row.published = pubEnabled.value ? 1 : 0
      message.success(pubEnabled.value ? '已发布，链接可访问' : '已停用')
    } else message.error(r?.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
}

async function doRevoke () {
  try {
    const r = await revokeAgentPublish(pubAgentId.value)
    if (r && r.success !== false) {
      const row = agents.value.find(x => x.id === pubAgentId.value)
      if (row) row.published = 0
      pubVisible.value = false
      message.success('已撤销分享')
    } else message.error(r?.msg || '撤销失败')
  } catch (e) { message.error(e.message || '撤销失败') }
}

async function copyText (text, tip) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(tip || '已复制')
  } catch (e) { message.error('复制失败，请手动选择复制') }
}
/** 卡片上只标出「显式开启」的能力——跟随全局的不占位置 */
const capsForcedOn = a => CAPS.filter(c => a[c.key] === 1).map(c => c.label)

// ==================== 全局配置快照（用于显示每项的全局状态） ====================
/**
 * 取全局配置项的 value。
 * 配置快照是「组 → 键」两层，且**组内键名是含点的扁平字符串**（`tool` 组里就是
 * `knowledgeRetrieval.enabled` / `artifact.enabled`），并不是嵌套对象——所以按
 * ['tool','knowledgeRetrieval','enabled'] 逐层下钻查不到。逐层走，某一层缺失时把
 * 「从该层起的剩余段」用 '.' 拼起来当扁平键直接取（消费掉整段）。
 */
const rawOf = path => {
  if (!cfg.value || !Array.isArray(path) || !path.length) return undefined
  let node = cfg.value
  for (let i = 0; i < path.length; i++) {
    if (node == null) return undefined
    const direct = node[path[i]]
    if (direct === undefined) {
      const flat = node[path.slice(i).join('.')]
      return flat === undefined ? undefined : flat?.value
    }
    node = direct
  }
  return node?.value
}
const isOn = path => { const v = rawOf(path); return v === 'true' || v === true }
/** 单轮工具步数上限的当前全局值（用于 placeholder 与描述，避免写死 15 与系统设置不一致） */
const globalMaxToolSteps = computed(() => {
  const v = rawOf(['agent', 'maxToolSteps'])
  if (v === undefined || v === null || v === '') return 15
  const n = Number(v)
  return Number.isFinite(n) ? n : 15
})
/**
 * 能力行的「全局开/关」提示。
 * cfg 为空 = 拿不到全局快照（普通用户无权读 /config）→ 返回空串，模板据此不显示该提示；
 * 之前无条件回落到 isOn()=false 会显示成「关闭」，与真实全局状态相反。
 */
const globalText = c => {
  if (!cfg.value) return ''
  if (c.gate && !isOn(c.gate)) return '关闭（工具总开关未开）'
  // 无独立全局开关的能力（技能 / MCP：内容归个人，只受工具总闸制约）
  if (!c.path) return '开启'
  const v = rawOf(c.path)
  if (v === undefined || v === null || v === '') return ''
  return isOn(c.path) ? '开启' : '关闭'
}

// ==================== 生效摘要（实时随表单变化） ====================
const summaryScope = computed(() => {
  if (form.value.isBuiltin === 1) return '「问渠」知识库'
  if (scopeMode.value === 'none') return '不使用知识库'
  if (scopeMode.value !== 'pick') return '全部文档'
  const n = (form.value.knowledgeBaseIds || []).length
  return n ? `限 ${n} 篇文档` : '未选文档（检索不到内容）'
})
/** 能力当前状态：开关型看三态值，多实例看模式（'1'/'pick'=开，'0'/'none'=关，其余=跟随全局） */
const capState = c => (c.kind === 'switch' ? form.value[c.key] : form.value[c.modeKey])
const capOn = c => { const v = capState(c); return v === '1' || v === 'pick' }
const capOff = c => { const v = capState(c); return v === '0' || v === 'none' }
/** 是否被本智能体显式覆盖（驱动「已覆盖」徽标与图标变色） */
const capOverridden = c => capOn(c) || capOff(c)
/** 多实例能力是否处于「指定」模式（决定要不要展开具体项多选） */
const needsPick = c => c.kind === 'list' && form.value[c.modeKey] === 'pick'

const capsTouched = computed(() => CAPS.some(c => capOverridden(c)))
const summaryCaps = computed(() => {
  const on = CAPS.filter(c => capOn(c)).length
  const off = CAPS.filter(c => capOff(c)).length
  if (!on && !off) return '能力跟随全局'
  const parts = []
  if (on) parts.push(`${on} 项开启`)
  if (off) parts.push(`${off} 项关闭`)
  return parts.join(' / ')
})

// ==================== 数据加载 ====================
const reload = async () => {
  loading.value = true
  try {
    // 身份先就位再决定要不要拉全局快照：/config 是**管理端点**，普通用户调用会 403
    // （与本仓库既有约定一致：ChatPage / KnowledgeBasePage 同样只让管理员拉）
    const me = await ensureAuth()
    isAdmin.value = me.admin
    // /kb/param-defaults 普通用户可读（检索参数占位符展示全局当前值用），可安全进 Promise.all
    const [ar, dr, sr, mr, xr, pd, wr] = await Promise.all([
      listAgents(), listKnowledgeBases(), listSkills(), getMcpStatus(), listSubAgents(), getKbParamDefaults(),
      // 工作流是个人资产端点（非管理端点），普通用户可安全并行拉取；失败只影响绑定下拉
      listWorkflows().catch(() => null)
    ])
    // /workflow/list 的 data 是分栏对象 { mine:[], shared:[] }（非数组），必须摊平再用，
    // 否则下面 .filter/.find 直接抛 "is not a function"（WorkflowPanel 同源数据按 mine/shared 解）
    const wd = (wr && wr.success && wr.data) || {}
    workflowRows.value = [...(wd.mine || []), ...(wd.shared || [])]
    if (pd && pd.success && pd.data) qpDefaults.value = pd.data
    if (ar.success && ar.data) agents.value = ar.data
    // 批量勾选与现存列表对账：已被删掉的 id 从选中集合里清掉（避免批量操作撞「不存在」）
    const alive = new Set(agents.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
    if (dr.success && dr.data) {
      const list = Array.isArray(dr.data) ? dr.data : (dr.data.list || [])
      kbOptions.value = list.map(k => ({
        value: k.id,
        label: k.name + (k.docCount != null ? `（${k.docCount} 个文档）` : '')
      }))
    }
    // 技能与 MCP Server 的可选项（供「指定」模式下的多选）
    // /skill/list 返回的是 { skills: [...] }（不是数组）——按数组判定会让「指定技能」永远没有可选项
    // value 存**引用串**而非裸名：个人技能/MCP 是个人资产，{uid}/{name} 才能精确绑定到归属人，
    // 避免别人用同名但内容不同的资源时被张冠李戴；内置技能后端给的 ref 就是裸名（人人都有）。
    if (sr && sr.success && sr.data) {
      const list = Array.isArray(sr.data.skills) ? sr.data.skills : []
      skillOptions.value = list.filter(s => !s.disabled).map(s => ({
        value: s.ref || s.name,
        label: s.name + (s.source === 'builtin' ? '（内置）' : '（我的）')
      }))
    }
    if (mr && mr.success && mr.data) {
      mcpOptions.value = (mr.data.servers || []).map(s => ({
        value: s.ref || s.name,
        label: s.name + (s.connected ? '' : '（未连接）')
      }))
    }
    // 可委派的子智能体
    if (xr && xr.success && Array.isArray(xr.data)) {
      subOptions.value = xr.data.map(s => ({ value: s.id, label: s.name }))
    }
    // M4：可绑定的工作流（只列已发布的——绑定未发布会在后端被拒，不如下拉里就不给）
    workflowOptions.value = workflowRows.value
      .filter(w => w.status === 'published')
      .map(w => ({ value: w.id, label: (w.name || w.id) + (w.publishedVersion != null ? `（v${w.publishedVersion}）` : '') }))
    // 全局能力总闸：仅管理员可读（普通用户拿不到时，能力行不显示「全局开/关」提示，而不是谎报关闭）
    if (me.admin) {
      try {
        const cr = await getConfig()
        if (cr && cr.success) cfg.value = cr.data
      } catch (e) { /* 管理员也可能拉失败：保持不显示，页面其余部分照常可用 */ }
    }
  } catch (e) { message.error(e.message || '加载失败') }
  finally { loading.value = false }
}

// ==================== 列表操作 ====================
const triStr = v => (v === null || v === undefined ? '' : String(v))

const openCreate = () => {
  editingId.value = ''
  form.value = blankForm()
  scopeMode.value = 'all'
  editing.value = true
}
/** 总开关三态 → 多实例能力的模式：'none' 不使用 / 'pick' 指定 / 'inherit' 跟随全局 */
const modeOf = tri => {
  if (tri === 0 || tri === '0') return 'none'
  if (tri === 1 || tri === '1') return 'pick'
  return 'inherit'
}

const openEdit = a => {
  editingId.value = a.id
  const kbs = splitList(a.knowledgeBaseIds)
  form.value = {
    name: a.name || '',
    description: a.description || '',
    systemPrompt: a.systemPrompt || '',
    // 内置标记驱动「名称不可修改」；图标未配时内置按问渠品牌标预选（默认问渠的图标）
    isBuiltin: isBuiltin(a) ? 1 : 0,
    icon: a.icon || (isBuiltin(a) ? 'wenqu' : ''),
    knowledgeBaseIds: kbs,
    isDefault: isDefault(a),
    toolKnowledge: triStr(a.toolKnowledge),
    toolBuiltin: triStr(a.toolBuiltin),
    toolSkill: triStr(a.toolSkill),
    toolArtifact: triStr(a.toolArtifact),
    toolMcp: triStr(a.toolMcp),
    toolWebsearch: triStr(a.toolWebsearch),
    toolApprovalMode: a.toolApprovalMode || 'auto',
    maxToolSteps: a.maxToolSteps == null ? null : Number(a.maxToolSteps),
    builtinMode: modeOf(a.toolBuiltin), builtinTools: splitList(a.builtinTools),
    skillMode: modeOf(a.toolSkill), skills: splitList(a.skills),
    mcpMode: modeOf(a.toolMcp), mcps: splitList(a.mcps),
    isSubagent: (a.isSubagent === 1 || a.isSubagent === true) ? 1 : 0,
    subAgentIds: splitList(a.subAgentIds),
    // M4：绑定的工作流（未绑定 → null；下拉里只列已发布的工作流）
    workflowId: a.workflowId || null,
    ...(() => { const r = parseQueryParams(a.queryParams); return { qp: r.qp, qpRerank: r.rr } })()
  }
  // 三档：不使用知识库 > 指定知识库（选了库）> 全部知识库
  scopeMode.value = (a.knowledgeDisabled === 1 || a.knowledgeDisabled === true)
    ? 'none' : (kbs.length ? 'pick' : 'all')
  editing.value = true
}
const closeEdit = () => { editing.value = false }

// ==================== 配置历史版本（每次保存落一版快照，可查 diff、一键回滚） ====================
const verOpen = ref(false)
const verLoading = ref(false)
const verList = ref([])
// 展开的版本号集合（默认展开最新一版，正是"最近一次改了什么"）
const verExpanded = ref([])
const verRolling = ref(null)

const openVersions = async () => {
  verOpen.value = true
  await loadVersions()
}

const loadVersions = async () => {
  if (!editingId.value) return
  verLoading.value = true
  try {
    const r = await listAgentVersions(editingId.value)
    if (r.success) {
      verList.value = r.data || []
      verExpanded.value = verList.value.length ? [verList.value[0].version] : []
    } else {
      verList.value = []
      message.error(r.msg || '版本列表加载失败')
    }
  } catch (e) {
    verList.value = []
    message.error(e.message || '版本列表加载失败')
  } finally {
    verLoading.value = false
  }
}

const toggleVer = version => {
  verExpanded.value = verExpanded.value.includes(version)
    ? verExpanded.value.filter(v => v !== version)
    : [...verExpanded.value, version]
}

/** diff 值预览：长文本（提示词/描述）压成单行短串，完整值走 title 悬浮 */
const clip = s => {
  const t = String(s ?? '')
  return t.length > 80 ? t.slice(0, 80) + '…' : t
}

const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 19) : '')

const doRollback = v => {
  Modal.confirm({
    title: `回滚到 v${v.version}`,
    content: `将把 v${v.version} 的配置（名称/提示词/知识库范围/工具集等）重新应用到当前智能体，`
      + '并生成一条新的版本记录。历史版本不会被改写，本次回滚也可以再回滚。',
    okText: '确认回滚',
    cancelText: '取消',
    onOk: async () => {
      verRolling.value = v.version
      try {
        const r = await rollbackAgent(editingId.value, v.version)
        if (r.success) {
          message.success(`已回滚到 v${v.version}`)
          // 回滚改的是后端配置：把列表与表单都刷成回滚后的状态，避免表单里残留旧值被下次「保存」又写回去
          await reload()
          const a = agents.value.find(x => x.id === editingId.value)
          if (a) openEdit(a)
          await loadVersions()
        } else {
          message.error(r.msg || '回滚失败')
        }
      } catch (e) {
        message.error(e.message || '回滚失败')
      } finally {
        verRolling.value = null
      }
    }
  })
}

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
    // 知识库范围：内置「问渠」固定检索使用者的默认库（后端同口径锁死、拒绝改动），与图标同理不带字段；
    // 其余智能体——「全部知识库」清空（空 → 后端存 null → 不限制）、「指定知识库」存逗号串、
    // 「不使用知识库」置 knowledgeDisabled=1 并清空（两者互斥，后端以开关为准）
    ...(f.isBuiltin === 1 ? {} : {
      knowledgeBaseIds: scopeMode.value === 'pick' ? (f.knowledgeBaseIds || []).join(',') : '',
      knowledgeDisabled: scopeMode.value === 'none' ? 1 : 0
    }),
    toolKnowledge: tri(f.toolKnowledge),
    toolArtifact: tri(f.toolArtifact),
    toolWebsearch: tri(f.toolWebsearch),
    toolApprovalMode: f.toolApprovalMode || 'auto',
    maxToolSteps: f.maxToolSteps == null || f.maxToolSteps === '' ? null : Number(f.maxToolSteps),
    isDefault: f.isDefault ? 1 : 0,
    isSubagent: f.isSubagent ? 1 : 0,
    // 子智能体没有委派对象；主智能体一个都没选 → 空串（后端归一为 null → 编排走多视角策略）
    subAgentIds: f.isSubagent ? null : (f.subAgentIds || []).join(','),
    // 检索参数覆盖：留空项不写入 → 继承全局；全空 → 空串 → 后端存 null
    queryParams: buildQueryParams(f.qp, f.qpRerank),
    // 工作流绑定：空/未选 → 空串（后端归一为 null = 解绑）；非空必须是已发布工作流（后端校验）
    workflowId: f.isSubagent ? '' : (f.workflowId || '')
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
  saving.value = true
  try {
    const r = editingId.value ? await updateAgent(editingId.value, payload) : await createAgent(payload)
    if (r.success) {
      message.success(editingId.value ? '已保存' : '已创建')
      editing.value = false
      await reload()
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
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
/** 可勾选集合 = 当前搜索过滤后的非内置智能体（两组：主 + 子） */
const selectableIds = computed(() =>
  sections.value.flatMap(sec => sec.members.filter(a => !isBuiltin(a)).map(a => a.id)))
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

const doSetDefault = async id => {
  try {
    const r = await setAgentDefault(id)
    if (r.success) { message.success('已设为默认'); await reload() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

onMounted(reload)
onMounted(async () => { })
</script>

<style scoped>
/* 批量操作区（页头最右，分隔线与搜索/新建划清界限）：开关恒在最右、进出模式位置不动 */
.batch-group { display: flex; align-items: center; gap: 8px; padding-left: 12px; border-left: 1px solid var(--app-border); }
.batch-on { color: var(--app-accent); border-color: var(--app-accent); }
.batch-del { color: var(--app-danger); }
.ap-check { flex: none; }
.ap-count { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.ap-count b { color: var(--app-text); font-weight: 600; }
.ap-head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.ap-search { width: 200px; }
.ap-search-ic { color: var(--app-text3); }

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
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 12px;
  padding: 12px 14px; display: flex; flex-direction: column; gap: 9px; cursor: pointer;
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.ap-card:hover {
  border-color: var(--app-accent-border);
  box-shadow: 0 6px 18px -10px rgba(46, 107, 230, .35);
}
.ap-card-head { display: flex; align-items: center; gap: 8px; }
.ap-name { font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-card-tags { margin-left: auto; display: inline-flex; align-items: center; gap: 4px; flex: none; }
.ap-tag-default {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-ok-weak); color: var(--app-ok);
}
.ap-tag-builtin {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.ap-desc {
  font-size: 12px; color: var(--app-text2); line-height: 1.6; margin: 0; min-height: 32px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.ap-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.ap-chip {
  font-size: 11px; line-height: 1; padding: 4px 7px; border-radius: 6px;
  background: var(--app-panel-2); color: var(--app-text2); white-space: nowrap;
}
.ap-chip-on { background: var(--app-accent-weak); color: var(--app-accent); }
.ap-chip-warn { background: var(--app-warn-weak); color: var(--app-warn-text); }
.ap-card-foot {
  display: flex; align-items: center; justify-content: flex-end; gap: 2px;
  border-top: 1px dashed var(--app-border); padding-top: 6px; margin-top: auto;
}
/* 不可管理时占位提示（内置问渠=系统默认，共享只读=仅可使用） */
.ap-readonly-hint { margin-right: auto; font-size: 12px; color: var(--app-text3); }
/* 不可管理的卡片：无点击态暗示（cursor 保持默认，区别于可点击进入配置的卡片） */
.ap-card-readonly { cursor: default; }

/* 配置视图：宽屏双栏瀑布式（>1440px 双栏，中屏回落单列居中，小屏吃满）。
   卡片整卡不跨栏（break-inside:avoid），multicol 自动配平两栏高度；
   卡片语义分组：左≈身份/提示词/知识库/检索参数（检索域），右≈能力/委派/工作流/默认（行为域） */
/* 页头标题即生效摘要（纯文本）：名称是主文案，范围/能力弱化跟随其后；能力被本智能体覆盖时高亮。
   超长单行截断，不挤右侧操作按钮 */
.ap-head-summary { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-sum-tail { color: var(--app-text2); font-weight: 400; }
.ap-sum-tail.is-accent { color: var(--app-accent); font-weight: 500; }

/* multicol 不作用于 flex 容器——栏内卡片纵向排布靠 margin，不用 flex/gap */
.ap-form { columns: 620px 2; column-gap: 12px; max-width: 1320px; margin: 0 auto; }
.ap-form > section { break-inside: avoid; -webkit-column-break-inside: avoid; margin: 0 0 12px; }
.ap-form :deep(.ant-form-item) { margin-bottom: 12px; }
.ap-block-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: -4px 0 12px; }
/* 中屏回落单列（620px 柱宽下容器不足自动 1 栏），限宽 900 保证单列可读；小屏吃满宽度 */
@media (max-width: 1440px) { .ap-form { max-width: 900px; } }
@media (max-width: 768px) { .ap-form { max-width: none; } }
/* 检索参数覆盖：两列网格，留空=继承全局 */
.qp-grid { display: grid; grid-template-columns: repeat(2, minmax(220px, 1fr)); gap: 10px 18px; }
.qp-item { display: flex; align-items: center; gap: 10px; }
.qp-label { flex: none; width: 88px; font-size: 12.5px; color: var(--app-text2); }
/* 重排服务的控件列：分段控件 + 下方全局状态灰字（竖排，任何栏宽都不会横向溢出格子） */
.qp-rerank-ctl { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; min-width: 0; }
.qp-inherit { font-size: 11px; color: var(--app-text3); }
.ap-pick { margin-top: 12px; }
/* 内置「问渠」的知识库范围为固定展示（无选择入口）：单行芯片，与选择器同起点对齐 */
.ap-kb-fixed { margin-top: 4px; }
.ap-sec-ic { font-size: 13px; color: var(--app-text3); }
/* 图标选择器：一排可选头像块，选中态用品牌色描边 + 光圈 */
.ap-icon-pick { display: flex; flex-wrap: wrap; gap: 6px; }
.ap-icon-opt {
  width: 34px; height: 34px; border-radius: 9px; padding: 0;
  border: 1px solid var(--app-border); background: var(--app-panel); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: border-color .15s, box-shadow .15s;
}
.ap-icon-opt:hover { border-color: var(--app-accent-border); }
.ap-icon-opt.on { border-color: var(--app-accent); box-shadow: 0 0 0 2px var(--app-accent-weak); }

/* 能力行：图标块 + 名称/描述 + 三态控件；只在「已覆盖」时才高亮 */
.ap-caps { display: flex; flex-direction: column; }
.ap-cap-block { border-top: 1px dashed var(--app-border); }
.ap-cap-block:first-child { border-top: none; }
.ap-cap { display: flex; align-items: center; gap: 12px; padding: 10px 0; }
.ap-cap-block:first-child .ap-cap { padding-top: 0; }
/* 「指定」模式展开的具体项多选：与上方图标块左对齐 */
.ap-cap-pick { padding: 0 0 12px 42px; }
.ap-pick-empty { font-size: 11px; color: var(--app-text3); margin-top: 5px; }
.ap-cap-ic {
  width: 30px; height: 30px; border-radius: 8px; flex: none; font-size: 14px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel-2); color: var(--app-text2); transition: background .15s, color .15s;
}
.ap-cap.overridden .ap-cap-ic { background: var(--app-accent-weak); color: var(--app-accent); }
.ap-cap-l { flex: 1; min-width: 0; }
.ap-cap-name { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.ap-cap.overridden .ap-cap-name { font-weight: 500; }
.ap-cap-badge {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.ap-cap-desc { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin-top: 2px; }
.ap-cap-global { color: var(--app-text3); }
/* ==================== 公开发布弹窗 ====================
   结构：标题栏（智能体头像+名）→ 状态卡（总开关）→ 游客能力（模型 / MCP）→ 分发方式（Tab）→ 底部撤销。
   此前是一列平铺的 pub-row，输入框把「复制」「打开」两个按钮挤成竖排单字；现改为
   「代码块 + 右侧图标按钮」一行到底，按钮 flex:none 不再被压缩。 */
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

.pub-field { display: flex; flex-direction: column; gap: 6px; }
.pub-label { font-size: 12px; font-weight: 600; color: var(--app-text2); }
.pub-switch-row { display: flex; align-items: flex-start; gap: 10px; margin-top: 12px; }
.pub-switch-row > .ant-switch { margin-top: 1px; flex: none; }
.pub-switch-label { font-size: 13px; color: var(--app-text); }
.pub-switch-d { font-size: 12px; color: var(--app-text3); margin-top: 2px; line-height: 1.6; }
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
/* 代码框：占满整行，复制/打开按钮**浮在框内**右上角。
   此前按钮与代码框并排（flex 两列），链接这种单行内容被挤到两行、按钮还拉成一条竖条。 */
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

/* ==================== 委派编排视图（拓扑） ==================== */
.ap-topo-legend { display: flex; flex-wrap: wrap; gap: 14px; align-items: center; font-size: 12px; color: var(--app-text3); margin-bottom: 10px; }
.ap-lg { display: inline-flex; align-items: center; gap: 6px; }
.ap-lg-dot { width: 10px; height: 10px; border-radius: 3px; display: inline-block; flex: none; }
.ap-lg-dot.main { background: var(--app-panel); border: 1.5px solid var(--app-accent, var(--app-accent-hover)); }
.ap-lg-dot.sub { background: var(--app-accent-weak); border: 1.5px solid var(--app-accent, var(--app-accent-hover)); }
.ap-topo-warns { margin-bottom: 10px; }
.ap-topo-warn { font-size: 12px; color: var(--app-danger); padding: 2px 0; }
.ap-topo-empty { padding: 28px 0; text-align: center; font-size: 13px; color: var(--app-text3); }
.ap-topo-scroll { display: flex; justify-content: center; overflow: auto; }
.ap-topo-svg { flex: none; }
.ap-edge { fill: none; stroke: var(--app-text3); stroke-width: 1.5; opacity: .8; transition: opacity .2s, stroke-width .2s; }
.ap-edge.on { stroke: var(--app-accent, var(--app-accent-hover)); stroke-width: 2; opacity: 1; }
.ap-edge.dim { opacity: .1; }
.ap-edge.miss { stroke: var(--app-danger, var(--app-danger)); stroke-dasharray: 5 4; }
.ap-node { cursor: pointer; }
.ap-node.dim { opacity: .18; }
.ap-nrect { fill: #fff; stroke: var(--app-border, var(--app-border)); stroke-width: 1.2; }
.ap-nrect.sub { fill: var(--app-accent-weak); }
.ap-nrect.orphan { stroke-dasharray: 5 4; stroke: var(--app-text3); }
.ap-phantom { fill: #fff; stroke: var(--app-danger, var(--app-danger)); stroke-dasharray: 4 3; }
.ap-phantom-t { font-size: 14px; font-weight: 700; fill: var(--app-danger, var(--app-danger)); text-anchor: middle; }
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
</style>
