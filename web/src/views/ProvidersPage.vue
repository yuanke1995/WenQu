<template>
  <!-- 页面骨架与「智能体 / 技能 / MCP 外部工具」Tab 完全一致：标题栏（页名 sr-only + 说明 + 主操作）+ 带内边距的内容区 -->
  <div class="app-page">
    <!-- 配置引导深链：交代「从哪来、做完回哪去」，保存后自动回跳，用户不必自己找路 -->
    <div v-if="setupMode" class="pv-setup-bar">
      <InfoCircleOutlined />
      <span>正在完成配置引导 · 添加并保存模型后会自动返回对话页</span>
      <button class="app-link-btn" type="button" @click="backToGuide">返回配置引导</button>
    </div>
    <div class="app-page-head pv-page-head">
      <!-- 页名与 Tab 名重复，标题仅保留给读屏器（sr-only），视觉上从统计起头 -->
      <h1 class="app-page-title sr-only">模型供应商</h1>
      <span class="head-count">共 <b>{{ list.length }}</b> 个供应商 · 已启用 <b>{{ enabledCount }}</b> · 模型 <b>{{ modelTotal }}</b> 个</span>
      <template v-if="list.length">
        <!-- 健康度分段筛选：先看「有几家红了」比看总数更有行动价值，数字常驻在标签里 -->
        <a-segmented v-model:value="healthFilter" :options="filterOptions" size="small" />
        <a-select v-model:value="sortKey" size="small" :options="sortOptions" style="width:128px" />
        <a-input v-model:value="searchKw" size="small" allow-clear placeholder="搜索供应商或模型" class="pv-search">
          <template #prefix><search-outlined /></template>
        </a-input>
      </template>
      <div class="pv-head-acts">
        <!-- 图标工具组：矩阵（懒加载对比）与全部重新核验（对可见启用供应商批量探测） -->
        <a-tooltip title="模型矩阵：同名模型跨供应商比价与备份">
          <button class="app-icon-btn" @click="openMatrix"><partition-outlined /></button>
        </a-tooltip>
        <a-tooltip title="全部重新核验（当前筛选可见的启用供应商）">
          <button class="app-icon-btn" :disabled="verifyAllBusy" @click="doVerifyVisible"><reload-outlined :spin="verifyAllBusy" /></button>
        </a-tooltip>
        <button class="app-icon-btn" :class="{ 'pv-view-on': view === 'card' }" title="卡片视图" @click="view = 'card'"><appstore-outlined /></button>
        <button class="app-icon-btn" :class="{ 'pv-view-on': view === 'table' }" title="表格视图" @click="view = 'table'"><table-outlined /></button>
        <button class="app-btn" @click="openCreate">
          <plus-outlined /> 新建供应商
        </button>
        <!-- 批量区（分隔线独立成区，不与常规按钮挤作一堆）。默认收起，点「批量管理」进入批量模式；
             开关放最右：进出模式自身位置不动；列表里的供应商都是自己可管理的，可勾选 -->
        <div v-if="list.length" class="batch-group">
          <template v-if="batchMode">
            <a-checkbox :checked="allChecked" :indeterminate="someChecked" @change="toggleAll">全选</a-checkbox>
            <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchEnabled(true)">启用</button>
            <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchEnabled(false)">停用</button>
            <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchVerify">批量核验</button>
            <button class="app-btn ghost small batch-del" :disabled="!selected.length || batchBusy" @click="doBatchDelete">删除</button>
          </template>
          <button class="app-btn ghost small" :class="{ 'batch-on': batchMode }" @click="toggleBatchMode">{{ batchMode ? '退出管理' : '批量管理' }}</button>
        </div>
      </div>
    </div>

    <div class="app-page-body">
      <!-- 首屏骨架：先见结构再见数据（整页转圈在慢网络下读起来像「卡死」）；后续刷新仍走 spin -->
      <div v-if="firstLoad" class="pv-grid">
        <div v-for="i in 6" :key="i" class="app-card pv-card pv-skeleton">
          <a-skeleton active :paragraph="{ rows: 3 }" :title="{ width: '55%' }" />
        </div>
      </div>
      <a-spin v-else :spinning="loading">
        <!-- 卡片视图 -->
        <div v-if="filteredList.length && view === 'card'" class="pv-grid">
          <div v-for="p in filteredList" :key="p.id" class="app-card pv-card">
            <div class="pv-head">
              <a-checkbox v-if="batchMode && p.manageable" class="pv-check" :checked="selected.includes(p.id)"
                          @change="toggleSelect(p.id)" />
              <ProviderIcon :icon="p.icon" :name="p.name" :size="34" />
              <div class="pv-title">
                <div class="pv-name">
                  {{ p.name }}
                  <!-- 归属提示：只在看到别人的供应商时出现（管理员视角）；自己的不显示避免噪音 -->
                  <a-tag v-if="p.ownerUid && p.ownerUid !== me" color="default" class="pv-owner-tag">
                    归属 {{ p.ownerUid }}
                  </a-tag>
                  <a-tag v-if="!p.enabled" color="default" class="pv-disabled-tag">已停用</a-tag>
                  <!-- 额度不足标记：这是「模型用不了」最常见的真实原因，列表上直接标出来，
                       用户不必先发起一次注定失败的问答去撞出错误 -->
                  <a-tag v-if="p.quotaBlocked" color="error" class="pv-quota-tag">额度不足</a-tag>
                </div>
                <div class="pv-url" :title="p.baseUrl">{{ p.baseUrl }}</div>
              </div>
              <!-- 健康状态点：颜色 + 文字双编码（暗色下同样可辨、读屏有字），悬浮 title 给原因与延迟 -->
              <span class="pv-health" :class="'pv-h-' + health(p).cls" :title="health(p).tip">
                <i class="pv-dot"></i>{{ health(p).label }}
              </span>
              <a-switch v-if="p.manageable" :checked="p.enabled" size="small"
                        @change="v => onToggle(p, v)" />
            </div>
            <!-- 额度不足时的处置条：说清「哪里用不到」+ 直接给查看引用入口。
                 没有它的话用户只知道「这个供应商坏了」，不知道自己的哪些库/配置在依赖它。
                 「重新核验」给充值/换 Key 之后的即时出口，不必等每日兜底任务 -->
            <div v-if="p.quotaBlocked" class="pv-quota-bar">
              <span class="pv-quota-msg" :title="p.quotaMessage || ''">
                {{ p.quotaMessage || '账户余额不足或套餐已到期' }}
              </span>
              <!-- 核验要写权限：别人归属的供应商只给查看引用，不给点了必 403 的按钮 -->
              <button v-if="p.manageable" class="app-link-btn" :disabled="!!verifyingIds[p.id]" @click="doVerify(p)">
                {{ verifyingIds[p.id] ? '核验中…' : '重新核验' }}
              </button>
              <button class="app-link-btn" @click="openRefs(p)">查看引用</button>
            </div>
            <!-- 停用不等于「处理完了」：开关只让模型不再可选，钉着它的地方一处都没解开。
                 弹窗关掉就再没有入口回到那份清单，所以这条提醒常驻在卡片上，直到引用改干净或重新启用 -->
            <div v-if="!p.enabled && p.refCount" class="pv-refs-bar">
              <span class="pv-refs-msg">停用后仍有 <b>{{ p.refCount }}</b> 处在用它的模型</span>
              <button class="app-link-btn" @click="openRefs(p)">查看引用</button>
            </div>
            <!-- 模型速览 chips：登记了什么、什么价，不开「管理模型」也看得见；超过 3 个折叠进矩阵 -->
            <div class="pv-models">
              <a-tag v-for="b in (p.modelBriefs || []).slice(0, 3)" :key="b.modelId"
                     class="pv-type-tag pv-model-chip" :title="chipTitle(b)">
                {{ b.displayName || b.modelId }}<i class="pv-chip-type">{{ TYPE_META[b.modelType]?.label || '其他' }}</i>
              </a-tag>
              <button v-if="(p.modelBriefs || []).length > 3" class="app-link-btn pv-chip-more"
                      title="在模型矩阵中查看全部" @click="openMatrix">+{{ (p.modelBriefs || []).length - 3 }}</button>
              <span v-if="!p.modelCount" class="pv-none">未登记模型</span>
            </div>
            <div class="pv-remark" v-if="p.remark">{{ p.remark }}</div>
            <!-- meta 行：核验新鲜度 + 近 7 天用量/费用，回答「这家现在能不能用、花得多不多」 -->
            <div class="pv-meta">
              <span>上次核验 {{ p.lastVerifiedAt ? relTime(p.lastVerifiedAt) : '从未' }}<template v-if="p.lastVerifiedAt && p.lastVerifyMs != null"> · {{ p.lastVerifyMs }}ms</template></span>
              <span class="pv-meta-sep">｜</span>
              <span>近 7 天 {{ (p.usage7d && p.usage7d.calls) || 0 }} 次</span>
              <span class="pv-meta-sep">｜</span>
              <span>费用 {{ costText(p) }}</span>
            </div>
            <div class="pv-actions">
              <template v-if="p.manageable">
                <button class="app-link-btn" @click="openModels(p)">
                  <database-outlined /> 管理模型（{{ p.modelCount }}）
                </button>
                <!-- 编辑/核验/删除收进「⋯」：主操作只留「管理模型」，低频项不再和它抢视线；
                     删除仍先确认再走 onDelete，确认语义与原行内 popconfirm 一致 -->
                <a-dropdown :trigger="['click']" placement="bottomRight">
                  <button class="app-link-btn pv-more-btn" aria-label="更多操作"><more-outlined /></button>
                  <template #overlay>
                    <a-menu>
                      <a-menu-item key="edit" @click="openEdit(p)">编辑</a-menu-item>
                      <a-menu-item key="verify" :disabled="!!verifyingIds[p.id]" @click="doVerify(p)">
                        {{ verifyingIds[p.id] ? '核验中…' : '重新核验' }}
                      </a-menu-item>
                      <a-menu-divider />
                      <a-menu-item key="del" danger @click="confirmDelete(p)">删除</a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </template>
            </div>
          </div>
        </div>
        <!-- 表格视图：与卡片同一数据源（filteredList），横向扫读多家状态/用量比卡片墙快 -->
        <a-table v-else-if="filteredList.length" :columns="tableCols" :data-source="filteredList"
                 row-key="id" size="small" :pagination="false" :scroll="{ x: 900 }">
          <template #bodyCell="{ column, record: p }">
            <template v-if="column.key === 'prov'">
              <div class="pv-tp-prov">
                <ProviderIcon :icon="p.icon" :name="p.name" :size="22" />
                <span class="pv-tp-name" :title="p.baseUrl">{{ p.name }}</span>
                <a-tag v-if="p.ownerUid && p.ownerUid !== me" color="default" class="pv-type-tag" :title="'归属 ' + p.ownerUid">归属</a-tag>
              </div>
            </template>
            <template v-else-if="column.key === 'health'">
              <span class="pv-health" :class="'pv-h-' + health(p).cls" :title="health(p).tip">
                <i class="pv-dot"></i>{{ health(p).label }}
              </span>
            </template>
            <template v-else-if="column.key === 'models'">
              <b class="pv-tp-count">{{ p.modelCount }}</b>
              <span class="pv-tp-types" :title="typeSummary(p)">{{ typeSummary(p) }}</span>
            </template>
            <template v-else-if="column.key === 'verify'">
              <span class="pm-ctx" :title="p.verifyMessage || health(p).tip">
                {{ p.lastVerifiedAt ? relTime(p.lastVerifiedAt) : '未核验' }}<template v-if="p.lastVerifyMs != null"> · {{ p.lastVerifyMs }}ms</template>
              </span>
            </template>
            <template v-else-if="column.key === 'calls'">{{ (p.usage7d && p.usage7d.calls) || 0 }}</template>
            <template v-else-if="column.key === 'cost'">{{ costText(p) }}</template>
            <template v-else-if="column.key === 'refs'">
              <button v-if="!p.enabled && p.refCount" class="app-link-btn" @click="openRefs(p)">{{ p.refCount }} 处</button>
              <span v-else class="pv-hint">—</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <div v-if="p.manageable" class="pv-row-actions">
                <button class="app-link-btn" @click="openModels(p)">管理模型</button>
                <a-dropdown :trigger="['click']" placement="bottomRight">
                  <button class="app-link-btn pv-more-btn" aria-label="更多操作"><more-outlined /></button>
                  <template #overlay>
                    <a-menu>
                      <a-menu-item key="edit" @click="openEdit(p)">编辑</a-menu-item>
                      <a-menu-item key="verify" :disabled="!!verifyingIds[p.id]" @click="doVerify(p)">
                        {{ verifyingIds[p.id] ? '核验中…' : '重新核验' }}
                      </a-menu-item>
                      <a-menu-divider />
                      <a-menu-item key="del" danger @click="confirmDelete(p)">删除</a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
              <span v-else class="pv-hint">—</span>
            </template>
          </template>
        </a-table>
        <!-- 空态分两种：一家都没有（引导从品牌预设开始新建）vs 被筛选/搜索滤掉（一键清条件）。
             滤空却显示「还没有供应商」会把人引去重建一家本来就存在的供应商 -->
        <div v-else-if="!loading" class="app-card pv-empty">
          <template v-if="!list.length">
            <div class="pv-empty-title">还没有供应商</div>
            <div class="pv-empty-desc">接入一个模型网关（DeepSeek、智谱、自托管 Ollama 等），它登记的模型就能在对话、知识库与工作流里被选用。</div>
            <button class="app-btn primary" style="margin-top:14px" @click="openCreate"><plus-outlined /> 从品牌开始新建</button>
          </template>
          <template v-else>
            <div class="pv-empty-title">没有符合当前筛选的供应商</div>
            <button class="app-link-btn" @click="resetFilters">清除筛选与搜索</button>
          </template>
        </div>
      </a-spin>
    </div>

    <!-- 新建/编辑供应商：品牌快捷区置顶（仅新建，点一下自动填好配置）→ 名称/网关/Key 主流程
         → 图标弱化成一行（网格收进展开）→ 高级折叠；测试连接进 footer 与保存并列，必填项失焦即校验 -->
    <a-modal v-model:open="showEdit" :title="editing ? '编辑供应商' : '新建供应商'" :width="560">
      <!-- 品牌快捷区：把原来藏在图标点击里的预设联动提到第一入口；编辑已有供应商时不显示，避免误点覆盖 -->
      <div v-if="!editing" class="pv-brand-zone">
        <div class="pv-brand-head">
          <span class="pv-brand-label">常用品牌</span>
          <span class="pv-brand-hint">点一下自动填好名称、图标与网关地址，之后可改</span>
        </div>
        <div class="pv-brand-chips">
          <button v-for="b in brandChips" :key="b.key" type="button"
                  class="pv-brand-chip" :class="{ on: pickedBrand === b.key }"
                  @click="onPickBrand(b.key)">
            <ProviderIcon :icon="b.key" :name="b.label" :size="16" />
            {{ b.label }}
          </button>
          <button type="button" class="pv-brand-chip ghost" @click="onPickBrand('')">自定义 ›</button>
        </div>
      </div>
      <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="名称" required :validate-status="nameError ? 'error' : ''" :help="nameError || undefined">
          <a-input v-model:value="form.name" :maxlength="100" placeholder="如：DeepSeek / 智谱GLM / 自托管 Ollama" @blur="nameTouched = true" />
        </a-form-item>
        <a-form-item label="网关地址" required :validate-status="urlError ? 'error' : ''" :help="urlError || undefined">
          <a-input v-model:value="form.baseUrl" placeholder="如 https://api.deepseek.com；…/v1、…/v4 等版本尾缀可自动识别" @blur="urlTouched = true" />
          <div v-if="form.icon === 'ollama' || /^https?:\/\/(localhost|127\.0\.0\.1)/.test(form.baseUrl || '')" class="pv-hint">
            地址由问渠服务器发起访问：localhost 指部署问渠的机器，不是你自己的电脑；Ollama 装在你自己电脑上时，请填它对服务器可达的地址（如 http://192.168.x.x:11434），并让 Ollama 监听 0.0.0.0。
          </div>
        </a-form-item>
        <a-form-item label="API Key">
          <!-- 不用 type=password：浏览器会把这张表单当登录框，往「网关地址」塞账号、在密钥框上弹已保存的密码。
               掩码改由 CSS（-webkit-text-security）实现；不支持该属性的浏览器退回 password 类型 -->
          <a-input v-model:value="form.apiKey" autocomplete="off"
                   :type="apiKeyType" class="pv-secret" :class="{ 'is-masked': !apiKeyVisible }"
                   :placeholder="editing ? '留空以沿用当前 API Key' : '部分自托管服务（Ollama 等）可留空'">
            <template #suffix>
              <span class="pv-secret-eye" @mousedown.prevent @click="apiKeyVisible = !apiKeyVisible">
                <eye-outlined v-if="apiKeyVisible" />
                <eye-invisible-outlined v-else />
              </span>
            </template>
          </a-input>
        </a-form-item>
        <a-form-item label="图标">
          <!-- 弱化成一行：默认自动按名称生成（左侧预览即最终效果），网格与 URL 收进「从品牌库更换」 -->
          <div class="pv-icon-row">
            <ProviderIcon :icon="form.icon" :name="form.name || '?'" :size="22" />
            <span class="pv-icon-row-text">{{ iconRowText }}</span>
            <button class="app-link-btn" type="button" @click="iconExpanded = !iconExpanded">
              {{ iconExpanded ? '收起' : '从品牌库更换' }}
            </button>
          </div>
          <template v-if="iconExpanded">
            <div class="pv-icon-picker" style="margin-top:8px">
              <button v-for="k in iconKeys" :key="k.key" type="button"
                      class="pv-icon-cell" :class="{ active: form.icon === k.key }"
                      :title="k.name" @click="onPickIcon(k.key)">
                <ProviderIcon :icon="k.key" :name="form.name || k.name" :tooltip="k.name" :size="20" />
              </button>
            </div>
            <div class="pv-hint">留空按名称生成；也可贴图片 URL</div>
            <a-input v-model:value="iconUrl" placeholder="自定义图片 URL（可留空，http(s)://…）" style="margin-top:6px" />
          </template>
        </a-form-item>
        <a-collapse ghost class="pv-advanced">
          <a-collapse-panel key="adv" header="高级（路径覆盖，一般留空自动识别）">
            <a-form-item label="补全路径" :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
              <a-input v-model:value="form.completionsPath" placeholder="默认 /v1/chat/completions；智谱 /v4、方舟 /v3（留空自动识别）" />
            </a-form-item>
            <a-form-item label="向量路径" :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
              <a-input v-model:value="form.embeddingsPath" placeholder="默认 /v1/embeddings（留空自动识别）" />
            </a-form-item>
          </a-collapse-panel>
        </a-collapse>
        <a-form-item label="备注">
          <a-input v-model:value="form.remark" :maxlength="255" placeholder="用途/计费说明等" />
        </a-form-item>
      </a-form>
      <template #footer>
        <!-- 测试连接是填完地址后的第一动作，提进 footer 与保存并列；结果就地显示，超长省略 + title 看全文 -->
        <div class="pv-edit-foot">
          <button class="app-link-btn" type="button" :disabled="testing" title="拉取一次模型列表，顺带验证地址与 Key" @click="onTest">
            {{ testing ? '测试中…' : '测试连接' }}
          </button>
          <span v-if="testResult" :class="testResult.ok ? 'pv-test-ok' : 'pv-test-err'" :title="testResult.text">{{ testResult.text }}</span>
          <div class="pv-edit-foot-acts">
            <button class="app-btn" type="button" @click="showEdit = false">取消</button>
            <button class="app-btn primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
          </div>
        </div>
      </template>
    </a-modal>

    <!-- 模型管理 -->
    <a-modal :open="showModels" :title="`管理模型 — ${modelProvider ? modelProvider.name : ''}`"
             :width="960" :footer="null" @cancel="onModelsCancel">
      <div class="pv-models-toolbar">
        <button class="app-btn" :disabled="fetching" @click="onFetch">
          <cloud-download-outlined /> {{ fetching ? '拉取中…' : '从服务拉取' }}
        </button>
        <button class="app-btn" @click="openModelAdd">添加模型</button>
        <span class="pv-hint" style="margin-left:auto">保存后生效</span>
      </div>

      <!-- 拉取候选：搜索 + 按行添加（类型在下方表格可改，无需勾选） -->
      <div v-if="candidates.length" class="pv-candidates">
        <div class="pv-candidates-head">
          <a-input v-model:value="candidateKeyword" size="small" allow-clear
                   placeholder="搜索模型名" class="pv-candidate-search" />
          <span class="pv-hint">{{ filteredCandidates.length }} / {{ candidates.length }}</span>
          <button class="app-link-btn" :disabled="!filteredCandidates.length" @click="importAllCandidates">
            全部导入（{{ filteredCandidates.length }}）
          </button>
          <button class="app-link-btn" @click="candidates = []">收起</button>
        </div>
        <div class="pv-candidate-list">
          <div v-for="c in filteredCandidates" :key="c.modelId" class="pv-candidate">
            <span class="pv-candidate-name" :title="c.modelId">{{ c.modelId }}</span>
            <a-tag :color="TYPE_META[c.guessedType]?.color" class="pv-type-tag">{{ TYPE_META[c.guessedType]?.label }}</a-tag>
            <a-tag v-if="c.guessVisionCapable && c.guessedType === 'chat'" color="blue"
                   title="该模型名识别为「聊天+图片理解」一体，加入后图片理解自动预填为支持" class="pv-type-tag">图</a-tag>
            <a-tag v-if="c.exists" class="pv-type-tag" color="default">已登记</a-tag>
            <button class="app-link-btn" :disabled="c.exists || inModels(c.modelId)" @click="addCandidate(c)">
              {{ c.exists || inModels(c.modelId) ? '已加入' : '添加' }}
            </button>
          </div>
          <div v-if="!filteredCandidates.length" class="pv-hint" style="padding:8px 2px">无匹配模型</div>
        </div>
      </div>

      <!-- 已登记模型：表格只做速览（展示名/能力/窗口输出只读汇总），配置统一进「编辑模型配置」弹窗 -->
      <a-table :columns="modelCols" :data-source="models" row-key="rowKey" size="small" :pagination="false">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'modelId'">
            <div class="pm-mid">
              <div class="pm-name" :title="record.modelId">{{ record.modelId || '（未填写模型名）' }}</div>
              <div v-if="record.displayName" class="pm-display">{{ record.displayName }}</div>
            </div>
          </template>
          <template v-else-if="column.key === 'modelType'">
            <!-- 多个 tag 用 flex 容器承载：a-tag 是 inline-block，裸放单元格内换行会因 line-height < 高度而上下压叠 -->
            <div class="pv-tags">
              <a-tag :color="TYPE_META[record.modelType]?.color" class="pv-type-tag">{{ TYPE_META[record.modelType]?.label }}</a-tag>
              <a-tag v-if="record.isNew || rowChanged(record)" color="default" class="pv-type-tag">待保存</a-tag>
            </div>
          </template>
          <template v-else-if="column.key === 'caps'">
            <div class="pv-tags">
              <a-tag v-for="c in capsOf(record)" :key="c" color="blue" class="pv-type-tag">{{ c }}</a-tag>
              <span v-if="!capsOf(record).length" class="pv-hint">—</span>
            </div>
          </template>
          <template v-else-if="column.key === 'ctx'">
            <span class="pm-ctx" :title="ctxTitle(record)">{{ ctxText(record) }}</span>
          </template>
          <template v-else-if="column.key === 'enabled'">
            <a-switch v-model:checked="record.enabled" size="small" />
          </template>
          <template v-else-if="column.key === 'action'">
            <div class="pv-row-actions">
              <button class="app-link-btn" @click="openModelEdit(record)">编辑</button>
              <!-- 测试按钮即状态：测完原位变为「可达 Nms」（绿）/「失败」（红），悬浮看详情，再点重测 -->
              <a-tooltip :title="testState(record).text || '发一次最小真实调用验证可达性'">
                <button class="app-link-btn" :class="testState(record).ok === true ? 't-ok' : (testState(record).ok === false ? 't-bad' : '')"
                        :disabled="testState(record).loading" @click="doTestModel(record)">
                  {{ testState(record).loading ? '测试中…'
                     : (testState(record).ok === true ? '可达 ' + testState(record).latencyMs + 'ms'
                     : (testState(record).ok === false ? '失败' : '测试')) }}
                </button>
              </a-tooltip>
              <!-- 未点保存前移除都可恢复（关闭弹窗即放弃），无需确认 -->
              <button class="app-link-btn danger" @click="removeRow(record)">移除</button>
            </div>
          </template>
        </template>
      </a-table>

      <div class="pv-models-footer">
        <button class="app-btn primary" :disabled="savingModels" @click="saveModels">
          {{ savingModels ? '保存中…' : `保存（${models.filter(m => m.modelId && m.modelId.trim() && m.enabled !== false).length} 个启用）` }}
        </button>
        <span class="pv-hint">未启用的模型不会出现在选择器中</span>
      </div>
    </a-modal>

    <!-- 添加/编辑模型配置：全宽表单 + ? 悬浮说明 + 高级配置折叠 + 重置表单（对齐 ZCode 的模型配置交互） -->
    <a-modal :open="editOpen" :title="editIsNew ? '添加模型' : '编辑模型配置'" :width="600" :mask-closable="false" @cancel="onEditCancel">
      <template #footer>
        <div class="pm-footer">
          <button class="app-link-btn" type="button" @click="resetEditForm">重置表单</button>
          <div class="pm-footer-actions">
            <button class="app-btn" type="button" @click="onEditCancel">取消</button>
            <button class="app-btn primary" type="button" @click="applyModelEdit">确定</button>
          </div>
        </div>
      </template>
      <template v-if="editForm">
        <div class="pm-form">
          <div class="pm-field">
            <div class="pm-label">
              模型 ID
              <a-tooltip title="调用网关 API 时原样透传的模型名，须与服务商文档一致（如 deepseek-chat）。登记后不可改名——各处绑定按「供应商/模型名」寻址，改名等于换模型。">
                <question-circle-outlined class="pm-q" />
              </a-tooltip>
            </div>
            <a-input v-model:value="editForm.modelId" :disabled="editIdLocked"
                     placeholder="调用 API 原样透传，如 deepseek-chat" @change="onEditModelIdChange" />
          </div>
          <div class="pm-grid">
            <div class="pm-field">
              <div class="pm-label">
                展示名
                <a-tooltip title="模型选择器里展示的名称，留空直接用模型 ID。">
                  <question-circle-outlined class="pm-q" />
                </a-tooltip>
              </div>
              <a-input v-model:value="editForm.displayName" placeholder="留空同模型 ID" />
            </div>
            <div class="pm-field">
              <div class="pm-label">
                模型类型
                <a-tooltip title="决定模型出现在哪些选择器与调用方式：聊天 / 视觉 / OCR 专用 / 向量 / 重排 / 语音 / 全模态。OCR 专用只产出解析文本，不能用于聊天图片理解。">
                  <question-circle-outlined class="pm-q" />
                </a-tooltip>
              </div>
              <a-select v-model:value="editForm.modelType" :options="typeOptions" :popup-match-select-width="false" style="width:100%" />
            </div>
          </div>
          <div class="pm-grid">
            <div class="pm-field">
              <div class="pm-label">
                上下文窗口
                <a-tooltip title="模型一次能处理的最大 token 数（输入 + 输出合计）。对话类模型必填（未声明时保存会被拒）。窗口决定长对话什么时候开始折上下文，也决定输入侧还剩多少：登记小了会提前折、白丢历史，登记大了会在上游提前报超窗。">
                  <question-circle-outlined class="pm-q" />
                </a-tooltip>
              </div>
              <a-input-number v-model:value="editForm.contextWindow" :min="1" :step="1024"
                              placeholder="对话类必填，如 1000000" style="width:100%" @press-enter="applyModelEdit" />
            </div>
            <div class="pm-field">
              <div class="pm-label">
                最大输出 Token
                <a-tooltip title="单次回复最多生成的 token 数（作为 max_tokens 随请求下发），要从窗口里预留：不小于「窗口 × 0.7」时该模型的检索资料将无法填入。留空=不主动限制（按厂商默认）。">
                  <question-circle-outlined class="pm-q" />
                </a-tooltip>
              </div>
              <a-input-number v-model:value="editForm.maxOutput" :min="1" :step="1024"
                              placeholder="留空=不限制（按厂商默认）" style="width:100%" @press-enter="applyModelEdit" />
            </div>
            <div class="pm-field">
              <div class="pm-label">
                输入单价
                <a-tooltip title="费用报表的计价输入：该模型每百万输入 token 的价格（元）。留空=未配置，费用报表对该模型显示「未计价」；0=免费模型。">
                  <question-circle-outlined class="pm-q" />
                </a-tooltip>
              </div>
              <a-input-number v-model:value="editForm.inputPrice" :min="0" :step="0.5" :precision="2"
                              placeholder="元 / 百万 tokens" style="width:100%" />
            </div>
            <div class="pm-field">
              <div class="pm-label">
                输出单价
                <a-tooltip title="费用报表的计价输入：该模型每百万输出 token 的价格（元），通常高于输入价。留空=未配置；0=免费。">
                  <question-circle-outlined class="pm-q" />
                </a-tooltip>
              </div>
              <a-input-number v-model:value="editForm.outputPrice" :min="0" :step="0.5" :precision="2"
                              placeholder="元 / 百万 tokens" style="width:100%" />
            </div>
          </div>
          <div class="pm-hint">
            窗口与最大输出按厂商口径填 token 数（1M 填 1000000）。对话类模型窗口必填，向量/重排/OCR/语音等能力型可留空；最大输出留空=不主动限制（按厂商默认）。
            带进对话的知识资料由检索参数决定，不受这里影响。
            <span v-if="budgetConflict" class="pm-hint-bad">
              最大输出 {{ fmtWindow(editForm.maxOutput) }} 已不小于窗口预算 {{ budgetConflict.toLocaleString('en-US') }} token
              （窗口 {{ fmtWindow(editForm.contextWindow) }} × 0.7），保存会被拒。
            </span>
          </div>

          <!-- 高级配置：能力位 + 思考（仅对话类模型；向量/重排/OCR/语音是能力型类型，无对话能力可配）。默认展开：能力项是登记模型最常核对的信息 -->
          <a-collapse ghost class="pm-advanced" :default-active-key="['adv']">
            <a-collapse-panel key="adv" header="高级配置">
              <template v-if="editCapApplies">
                <div class="pm-field">
                  <div class="pm-label">
                    图片输入
                    <a-tooltip placement="topLeft">
                      <template #title>
                        <div>设置模型能否接收图片输入（多模态）：</div>
                        <div>· 支持：可被选为视觉模型，聊天中可直接读图</div>
                        <div>· 自动：按模型类型与名称智能判定</div>
                        <div>· 不支持：明确禁用，即使模型名带 VL 也不放行</div>
                      </template>
                      <question-circle-outlined class="pm-q" />
                    </a-tooltip>
                  </div>
                  <a-segmented v-model:value="editForm.visionCapable" :options="triOptions" size="small" />
                </div>
                <div v-if="editToolApplies" class="pm-field">
                  <div class="pm-label">
                    工具调用
                    <a-tooltip placement="topLeft">
                      <template #title>
                        <div>是否支持 Function Calling（工具调用）：</div>
                        <div>· 支持：正常下发工具</div>
                        <div>· 自动：按类型判定（聊天 / 全模态默认支持）</div>
                        <div>· 不支持：对话时不下发 tools——部分网关收到 tools 会直接报 400</div>
                      </template>
                      <question-circle-outlined class="pm-q" />
                    </a-tooltip>
                  </div>
                  <a-segmented v-model:value="editForm.toolCapable" :options="triOptions" size="small" />
                </div>
                <div v-if="editChatOnly" class="pm-field">
                  <div class="pm-label">
                    思考模式
                    <a-tooltip placement="topLeft">
                      <template #title>
                        <div>思考能力的开关形态：</div>
                        <div>· 自动：按模型名判定</div>
                        <div>· 不支持：聊天页不出现深度思考入口</div>
                        <div>· 可开关：用户可开 / 关思考</div>
                        <div>· 恒思考：始终思考，无法关闭</div>
                      </template>
                      <question-circle-outlined class="pm-q" />
                    </a-tooltip>
                  </div>
                  <a-segmented v-model:value="editForm.thinking" :options="thinkingOptions" size="small" />
                </div>
                <div class="pm-field">
                  <div class="pm-label">
                    思考强度档位
                    <a-tooltip placement="topLeft">
                      <template #title>
                        <div>勾选该模型真实支持的强度档位（由弱到强），留空 = 只支持思考开关、不支持强度调节。</div>
                        <div>下发哪些字段由下面的「参数方言」决定，默认按模型名判定；每一档实际发什么值可以在「档位参数映射」里覆盖。</div>
                      </template>
                      <question-circle-outlined class="pm-q" />
                    </a-tooltip>
                  </div>
                  <div class="pm-chips">
                    <a-checkable-tag v-for="lv in reasoningLevelOptions" :key="lv.value"
                                     :checked="editLevels.includes(lv.value)"
                                     @change="c => toggleEditLevel(lv.value, c)">{{ lv.label }}</a-checkable-tag>
                  </div>
                </div>
                <div class="pm-field">
                  <div class="pm-label">
                    默认思考强度
                    <a-tooltip title="开启深度思考时默认使用的档位，须在已勾选档位内；留空由网关默认决定。">
                      <question-circle-outlined class="pm-q" />
                    </a-tooltip>
                  </div>
                  <a-select v-model:value="editForm.defaultReasoningLevel" style="width:220px" allow-clear
                            placeholder="自动（由网关默认决定）" :options="defaultLevelOptions" />
                </div>
                <div class="pm-field">
                  <div class="pm-label">
                    参数方言
                    <a-tooltip placement="topLeft">
                      <template #title>
                        <div>决定思考强度用哪些字段下发。默认按模型名判定，判定不准就在这里点名。</div>
                        <div>中转与私有网关常把模型改名，自动判定认不出时一个强度参数都不会发。</div>
                      </template>
                      <question-circle-outlined class="pm-q" />
                    </a-tooltip>
                  </div>
                  <a-select v-model:value="editForm.reasoningParams.dialect" style="width:320px" :options="dialectOptions" />
                  <div class="pm-dialect-now">
                    此刻按 <b>{{ resolvedLabel }}</b> 下发<span v-if="!editForm.reasoningParams.dialect">（按模型名判定）</span>
                  </div>
                </div>
                <div v-if="dialectWarn" class="pm-dialect-warn">{{ dialectWarn }}</div>
                <div v-if="editLevels.length" class="pm-field">
                  <div class="pm-label">
                    档位参数映射
                    <a-tooltip placement="topLeft">
                      <template #title>
                        <div>留空 = 跟随内置表（内置值随各家文档修正会一起变）；填了 = 这台模型按你填的值发。</div>
                        <div>清空某一格即回到跟随；没勾选的档位不出现在这张表里。</div>
                      </template>
                      <question-circle-outlined class="pm-q" />
                    </a-tooltip>
                  </div>
                  <table class="pm-map">
                    <thead>
                      <tr><th>档位</th><th>reasoning_effort</th><th>思考预算 token</th><th>状态</th></tr>
                    </thead>
                    <tbody>
                      <tr v-for="lv in defaultLevelOptions" :key="lv.value">
                        <td>
                          <span class="pm-map-lv">{{ lv.label }}</span>
                          <span class="pm-map-code">{{ lv.value }}</span>
                        </td>
                        <td>
                          <a-input v-model:value="editForm.reasoningParams.effort[lv.value]" size="small"
                                   :placeholder="builtinEffort(lv.value)" :disabled="!dialectHasEffort" />
                        </td>
                        <td>
                          <a-input-number v-model:value="editForm.reasoningParams.budget[lv.value]" size="small"
                                          :min="1" :placeholder="builtinBudget(lv.value)" :disabled="!dialectHasBudget" />
                        </td>
                        <td>
                          <template v-if="overridden(lv.value)">
                            <span class="pm-map-ov">已覆盖</span>
                            <button type="button" class="app-link-btn" @click="clearOverride(lv.value)">回到跟随</button>
                          </template>
                          <span v-else class="pm-map-fl">跟随</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </template>
              <div v-else class="pm-hint">该类型无需配置对话能力</div>
            </a-collapse-panel>
          </a-collapse>
        </div>
      </template>
    </a-modal>

    <!-- 被引用清单：三处共用同一个入口——额度不足、停用之后、删除被拒。
         逐条给出引用类型、名称、后果与跳转，逐处可改而不是只给一句「仍被引用」；
         否则用户只能全局搜模型名碰运气 -->
    <a-modal v-model:open="showRefs" :title="refsTitle"
             :width="660" :footer="null" @cancelled="showRefs = false">
      <a-spin :spinning="refsLoading">
        <template v-if="refsData">
          <div v-if="refsData.error" class="pv-refs-error">{{ refsData.error }}</div>
          <template v-else>
            <!-- 批量替换条：逐处「去修改」之外给一条一次换干净的路——多数场景只是「这家坏了/贵了，
                 整体搬到同源的另一家」。只对可管理供应商给这个入口（后端 replace 同样要求写权限） -->
            <div v-if="(refsData.editableCount || 0) > 0 && refsProvider?.manageable && replaceOptions.length"
                 class="pv-replace-bar">
              <span class="pv-replace-lead">一次替换这 <b>{{ refsData.editableCount }}</b> 处 →</span>
              <a-select v-model:value="replaceTarget" :options="replaceOptions" size="small"
                        class="pv-replace-select" placeholder="目标供应商" :popup-match-select-width="false" />
              <button class="app-btn ghost small" :disabled="replacing || !replaceTarget" @click="doReplaceAllRefs">
                {{ replacing ? '替换中…' : '批量替换' }}
              </button>
            </div>
            <div class="pv-refs-head">
              <template v-if="refsOff">
                开关只是让模型<b>不再可选</b>，不会把已经钉住它的地方解开——下面
                <b>{{ refsData.total || 0 }}</b> 处还在用它，逐处换成别的模型才会真正恢复。
              </template>
              <template v-else>
                共 <b>{{ refsData.total || 0 }}</b> 处引用
                <template v-if="refsData.total">
                  ，其中 <b>{{ refsData.editableCount || 0 }}</b> 处可在下列位置直接修改
                </template>
              </template>
            </div>
          </template>
          <div v-if="!refsData.error && (!refsData.items || !refsData.items.length)" class="pv-refs-empty">
            当前没有任何地方引用它的模型，可直接在这里处理。
          </div>
          <div v-else class="pv-refs-list">
            <div v-for="(r, i) in refsData.items" :key="r.kind + '-' + i" class="pv-ref-item">
              <div class="pv-ref-main">
                <a-tag :color="refKindColor(r.kind)" class="pv-ref-kind">{{ r.label }}</a-tag>
                <span class="pv-ref-name" :title="r.name">{{ r.name }}</span>
              </div>
              <div class="pv-ref-hint" :title="r.hint">{{ r.hint }}</div>
              <a-button v-if="refJumpOf(r)" size="small" class="pv-ref-go" @click="jumpRef(r)">
                去修改
              </a-button>
              <!-- 历史会话这一类没有管理页可跳，但能一次清干净：给按钮而不是只留一句「进每条会话里切」 -->
              <a-button v-else-if="r.kind === 'session' && refsData.sessionOverrideCount"
                        size="small" class="pv-ref-go" :loading="clearing" @click="clearSessionModels">
                清除这 {{ refsData.sessionOverrideCount }} 条会话的选择
              </a-button>
              <span v-else class="pv-ref-note">{{ refNoteOf(r) }}</span>
            </div>
          </div>
          <!-- 反悔的出口：引用改不动、或只是想先让功能恢复，重新启用比逐处改快得多。
               不给这一条，用户只能关掉弹窗回列表找那颗开关 -->
          <div v-if="refsOff && !refsLoading" class="pv-refs-foot">
            <button class="app-link-btn" :disabled="reEnabling" @click="reEnableFromRefs">
              重新启用「{{ refsProvider?.name }}」
            </button>
          </div>
        </template>
      </a-spin>
    </a-modal>

    <!-- 模型矩阵：同名模型在多家供应商的登记对比（比价、备份缺口一眼可见）。
         懒加载：聚合全量供应商×模型，只在点开时拉一次，列表刷新即作废缓存 -->
    <a-modal v-model:open="showMatrix" title="模型矩阵 · 同名模型跨供应商"
             :width="880" :footer="null" @cancelled="showMatrix = false">
      <a-spin :spinning="matrixLoading">
        <div v-if="!matrixData" class="pmx-empty">正在汇总各家登记的模型…</div>
        <div v-else-if="!matrixData.length" class="pmx-empty">
          还没有任何已登记的模型：先在某个供应商的「管理模型」里添加，再回来看跨家对比。
        </div>
        <div v-else class="pmx-list">
          <div class="pmx-row pmx-head-row">
            <span>供应商</span><span>类型</span><span>单价 入/出（元/百万 token）</span><span>窗口</span><span>近7天调用</span><span>近7天费用</span><span>状态</span>
          </div>
          <!-- 后端已按「同源家数由多到少」排序：≥2 家的分组天然置顶，这里只做高亮 -->
          <div v-for="g in matrixData" :key="g.modelId" class="pmx-group" :class="{ 'pmx-same': g.entries.length >= 2 }">
            <div class="pmx-group-head">
              <span class="pmx-model" :title="g.modelId">{{ g.modelId }}</span>
              <a-tag v-if="g.entries.length >= 2" color="purple" class="pv-type-tag">同源 ×{{ g.entries.length }}</a-tag>
            </div>
            <div v-for="e in g.entries" :key="e.ref" class="pmx-row">
              <span class="pmx-prov" :title="e.providerName + (e.modelEnabled ? '' : '（模型已停用）')">
                {{ e.providerName }}<i v-if="!e.modelEnabled" class="pmx-off">模型停用</i>
              </span>
              <a-tag :color="TYPE_META[e.modelType]?.color" class="pv-type-tag">{{ TYPE_META[e.modelType]?.label || '其他' }}</a-tag>
              <span class="pmx-num">{{ entryPriceText(e) }}</span>
              <span class="pmx-num">{{ e.contextWindow ? fmtWindow(e.contextWindow) : '—' }}</span>
              <span class="pmx-num">{{ e.calls7d }}</span>
              <span class="pmx-num">{{ e.cost7d == null ? '未计价' : '¥' + Number(e.cost7d).toFixed(2) }}</span>
              <span class="pv-health" :class="'pv-h-' + entryHealth(e).cls" :title="entryHealth(e).tip">
                <i class="pv-dot"></i>{{ entryHealth(e).label }}
              </span>
            </div>
          </div>
        </div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, DatabaseOutlined, CloudDownloadOutlined, QuestionCircleOutlined, EyeOutlined, EyeInvisibleOutlined, SearchOutlined, ReloadOutlined, AppstoreOutlined, TableOutlined, MoreOutlined, PartitionOutlined, InfoCircleOutlined } from '@ant-design/icons-vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import { BRAND_PATHS, BRAND_BADGES } from '../assets/providerIcons.js'
import { fmtWindow } from '../chat/projections'
import { authUser } from '../utils/auth'
import { refreshSetupGuide } from '../utils/setupGuide'
import {
listProviders, createProvider, updateProvider, setProviderEnabled, deleteProvider,
  listProviderModels, saveProviderModels, fetchProviderModels, testProvider,
  listReasoningDialects, guessReasoningDialect,
  batchDeleteProviders, batchSetProvidersEnabled, listProviderReferences,
  clearProviderSessionModels,
  verifyProvider, verifyProvidersBatch, verifyStaleProviders,
  providerMatrix, replaceProviderReferences
} from '../api'

const route = useRoute()
const router = useRouter()

// 配置引导深链：顶部返回条 + 模型保存后自动回跳（from=setup 由引导清单带上）
const setupMode = computed(() => route.query.from === 'setup')
// 建供应商成功后要接着预选的新模型类型（深链落不到已有供应商时用）
const pendingSetupType = ref('')
const backToGuide = async () => {
  await refreshSetupGuide(true)
  router.push('/chat')
}

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

// ---- 被引用清单（额度不足 / 停用之后 / 删除被拒 三处共用的处置入口）----
const showRefs = ref(false)
const refsLoading = ref(false)
const refsProvider = ref(null)
const refsData = ref(null)
// 打开时它是不是已停用：决定这份清单讲「被用在哪里」还是「停用后还剩几处没解开」。
// 每次拉清单都按当时的开关状态重算——弹窗里点「重新启用」后标题会翻回中性口径，
// 因为那时「你刚停用了它」这个前提已经不成立，反悔出口也该跟着撤掉
const refsOff = ref(false)

/**
 * 清单里的引用处数；**拉失败时返回 null 而不是 0**。
 * 把「没拉到」当成「没有引用」会让停用报出一句「没有地方在引用它的模型」，
 * 而卡片上那条本该出现的提醒也会一起消失——失败被读成干净。
 */
const refTotalOf = d => (d && !d.error ? (d.total || 0) : null)

const refsTitle = computed(() => {
  const n = refsProvider.value?.name || ''
  const total = refTotalOf(refsData.value)
  return refsOff.value && total ? `「${n}」已停用 · 这 ${total} 处还在用它`
    : `「${n}」的模型被用在哪里`
})

/** 拉一次实时清单（不用列表里的缓存：引用关系随时会变，别人也可能刚改了配置） */
const loadRefs = async p => {
  refsProvider.value = p
  refsOff.value = !p.enabled
  refsData.value = null
  refsLoading.value = true
  // 批量替换默认推荐第一个候选（多数时候就是「还活着的那家同源」），不满意可在下拉里换
  replaceTarget.value = replaceOptions.value[0]?.value || ''
  try {
    const r = await listProviderReferences(p.id)
    refsData.value = (r && r.data) || {}
  } catch (e) {
    refsData.value = { items: [], total: 0, error: '引用清单加载失败，请重试' }
  } finally {
    refsLoading.value = false
  }
  return refsData.value
}

const openRefs = async p => {
  showRefs.value = true
  await loadRefs(p)
}

/** 引用类型的标签配色（按后果分档：直接失败=红，静默降质/个人范围=橙，全平台=紫，可忽略=灰） */
const refKindColor = kind => ({
  kbEmbedding: 'error', kbGraph: 'error', kbParseVision: 'error', kbParseOcr: 'error',
  scheduledJob: 'error', workflow: 'error', agentShare: 'error',
  kbRerank: 'warning', docVision: 'warning', userDefault: 'warning',
  configSlot: 'purple', userConfig: 'default', session: 'default'
}[kind] || 'default')

/**
 * 知识库四类模型槽 → 配置弹窗里的定位键（与 KnowledgeBaseEditModal 上的 data-fix 同名）。
 * 光跳到达标页不算送到：落点页要吃 ?kbcfg=1&fix=<槽位> 自己把弹窗开起来、把那一组展开、亮那一项。
 */
const KB_FIX = {
  kbEmbedding: 'embeddingRef', kbGraph: 'graphModelRef', kbRerank: 'rerankModel',
  kbParseVision: 'visionRef', kbParseOcr: 'ocrRef'
}

/**
 * 逐条引用的跳转目标。**只给真能改的地点**：无管理入口的（历史会话覆盖、已下线入口的遗留行、
 * 只能在重新上传时才清得掉的文档级覆盖）不给按钮，给了跳不过去比不给更让人困惑。
 * 注意这里与后端 `editable` **不是同一个问题**：editable 问「这处算不算还没清干净」（决定删不删得掉），
 * 这里问「能不能一键把人送到改的地方」。文档级覆盖就是两者不一致的那一类——算引用、但给不了按钮，
 * 于是必须在 refNoteOf 里把唯一改法写清楚，不能只留一句「无管理入口」。
 * 个人默认模型与历史个人配置那两类如果 uid 不是自己，是别人的配置，本界面改不了，故只对本人给入口。
 */
const refJumpOf = r => {
  const fix = KB_FIX[r.kind]
  if (fix) return r.id ? { path: `/knowledge/${r.id}/docs`, query: { kbcfg: '1', fix } } : null
  // 系统配置槽位带的是它所属设置面板 key（后端 panelOf 解析），fix 用配置键本身：
  // 开到对的那一组、展开装着它的那一节、再亮那一行
  if (r.kind === 'configSlot') {
    return r.id ? { path: '/settings', query: { panel: r.id, fix: r.name } } : null
  }
  if (r.kind === 'scheduledJob') {
    return r.id ? { path: '/agents', query: { tab: 'scheduled', jobId: r.id } } : null
  }
  if (r.kind === 'workflow') {
    return r.id ? { path: '/agents', query: { tab: 'workflow', wf: r.id, wfedit: '1' } } : null
  }
  if (r.kind === 'agentShare') {
    return r.id ? { path: '/agents', query: { tab: 'agents', agentId: r.id, pub: '1' } } : null
  }
  if (r.kind === 'userDefault' && r.id && r.id === me.value) {
    return { path: '/profile', query: { panel: 'chat' } }
  }
  return null
}
const jumpRef = r => {
  const to = refJumpOf(r)
  if (!to) return
  showRefs.value = false
  router.push(to)
}

/**
 * 不给跳转按钮的那几类，说清「为什么改不了、怎么办」——
 * 统一写「无管理入口」等于把用户指到一扇没把手的门：既然点不动，得说明替代路径或能不能不管。
 */
const refNoteOf = r => {
  if (r.kind === 'session') return '逐条进该会话切一次模型即可覆盖；点右侧按钮可一次清干净'
  if (r.kind === 'userConfig') return '个人设置入口已下线，这一行是历史存量，不影响当前问答，可不管'
  if (r.kind === 'userDefault') return `属 ${r.name} 的个人设置，需其本人修改`
  if (r.kind === 'configSlot') return '该配置项在设置页里没有可改的入口'
  // 文档级图片理解模型只在上传那一刻选过，之后界面上没有再改它的地方——
  // 与其给一个打不开的按钮，不如把唯一能清掉它的动作说清楚
  if (r.kind === 'docVision') {
    return r.id ? '上传后没有单独改它的入口：重新上传同名文件、不选图片理解模型，覆盖即清掉回到跟随知识库'
      : '以上未列全，逐库看文档的图片理解设置'
  }
  return '无管理入口'
}

// ---- 一次清除历史会话里指向本供应商的模型选择 ----
// 为什么给它一个按钮：停用供应商或把模型移出列表，都不会让历史会话里钉着的那串值自己消失。
// 用户改完这里能看见的所有引用，回到聊天页仍会被那句「模型已停用」挡住，而且一条条会话点过去才清得掉。
const clearing = ref(false)
const clearSessionModels = async () => {
  const p = refsProvider.value
  if (!p) return
  clearing.value = true
  try {
    const r = await clearProviderSessionModels(p.id)
    const cleared = (r && r.data && r.data.cleared) || 0
    message.success(cleared ? `已清除 ${cleared} 条会话的模型选择，它们回到跟随各自的默认模型` : '没有会话还在用这个供应商的模型')
    // 清完重查一次清单：那条「历史会话」应当消失。不重查用户读成「点了没生效」
    const d = await loadRefs(p)
    const total = refTotalOf(d)
    if (total !== null) p.refCount = total
  } catch (e) {
    message.error(`清除失败：${e?.message || '请稍后重试'}`)
  } finally {
    clearing.value = false
  }
}

/** 弹窗里就地反悔：重新启用它，并刷新这份清单（会话那条应随启用一起消失） */
const reEnabling = ref(false)
const reEnableFromRefs = async () => {
  const p = refsProvider.value
  if (!p) return
  reEnabling.value = true
  try {
    await setProviderEnabled(p.id, true)
    p.enabled = true
    p.refCount = 0
    message.success('已重新启用，它的模型回到可选清单')
    await loadRefs(p)
  } catch (e) {
    message.error(e.message || '启用失败')
  } finally {
    reEnabling.value = false
  }
}

// ---- 引用批量替换：逐处「去修改」之外给一条一次换干净的路 ----
// 十几处引用逐个点跳转改到人手酸，而多数场景只是「这家坏了/贵了，整体搬到同源的另一家」。
// 后端按同名模型逐处换，目标没登记该模型的逐处失败给原因——不做静默换别的模型这种事。
const replaceTarget = ref('')
const replacing = ref(false)
/** 候选：启用、可管理、不是自己（后端 replace 同样要求 provider:write，只列改得动的） */
const replaceOptions = computed(() => {
  const cur = refsProvider.value
  return list.value
    .filter(p => p.enabled && p.manageable && (!cur || p.id !== cur.id))
    .map(p => ({ value: p.id, label: p.name }))
})

const doReplaceAllRefs = async () => {
  const p = refsProvider.value
  const target = replaceTarget.value
  if (!p || !target) return
  replacing.value = true
  try {
    const r = await replaceProviderReferences(p.id, target)
    const d = (r && r.data) || {}
    const replaced = d.replaced || []
    const failed = d.failed || []
    if (replaced.length) message.success(`已替换 ${replaced.length} 处引用`)
    if (failed.length) {
      // 失败逐条列出来：多半是目标供应商没登记同名模型，吞成一句数字会让人以为「替换没生效」
      Modal.warning({
        title: `${failed.length} 处没换掉`,
        content: failed.map(f => `「${f.name}」：${f.error || '目标供应商未登记同名启用模型'}`).join('；'),
        okText: '知道了'
      })
    }
    if (!replaced.length && !failed.length) message.info('没有可替换的引用')
    await loadRefs(p)
    await load()   // 卡片上的剩余引用数随之回落
  } catch (e) {
    message.error(`替换失败：${e?.message || '请稍后重试'}`)
  } finally {
    replacing.value = false
  }
}

// 当前登录人 uid（归属提示只用它判断「这是不是别人的供应商」）
const me = computed(() => authUser.value?.user || '')

// ==================== 健康判定（状态点 / 筛选 / 排序 / 矩阵共用一份口径） ====================
/**
 * 单一判定函数：卡片状态点、表格状态列、矩阵行、分段筛选都读它，避免四处口径漂移。
 * 优先级：停用 > 额度 > 核验——额度不足是被动撞错定性的「模型用不了」，
 * 一次免费的 /models 可达核验不能把它抹掉（后端同取舍，见 ProviderVerifyService）。
 * cls 三分：bad=红（异常）、ok=绿、idle=灰（停用/未核验），筛选按 cls 归类。
 */
function health(p) {
  if (!p.enabled) return { key: 'idle', cls: 'idle', label: '已停用', tip: '已停用：它的模型不再可选' }
  if (p.quotaBlocked) return { key: 'blocked', cls: 'bad', label: '额度不足', tip: p.quotaMessage || '账户余额不足或套餐已到期' }
  const s = p.verifyStatus
  if (s === 'expired') return { key: 'expired', cls: 'bad', label: '额度不足', tip: p.verifyMessage || '核验检出额度耗尽' }
  if (s === 'unreachable') return { key: 'unreachable', cls: 'bad', label: '不可达', tip: p.verifyMessage || '无法连接网关' }
  if (s === 'rejected') return { key: 'rejected', cls: 'bad', label: '网关拒绝', tip: p.verifyMessage || 'API Key 可能已失效或被吊销' }
  if (s === 'ok') return { key: 'ok', cls: 'ok', label: '可达', tip: `上次核验 ${p.lastVerifyMs ?? '—'}ms` }
  return { key: 'never', cls: 'idle', label: '未核验', tip: '打开页面时会自动核验陈旧项；也可在卡片「⋯」里立即核验' }
}

/** 相对时间：核验时间要一眼读出「新不新」，超过 7 天回落成日期 */
const relTime = v => {
  if (!v) return '从未'
  const t = typeof v === 'number' ? v : Date.parse(String(v))
  if (!Number.isFinite(t)) return String(v)
  const d = Date.now() - t
  if (d < 60e3) return '刚刚'
  if (d < 3600e3) return Math.floor(d / 60e3) + '分钟前'
  if (d < 86400e3) return Math.floor(d / 3600e3) + '小时前'
  if (d < 7 * 86400e3) return Math.floor(d / 86400e3) + '天前'
  const dt = new Date(t)
  return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`
}

/** 近 7 天费用文案：cost=null 表示该家模型全部未计价——显示「未计价」而不是 ¥0（0 会被读成「没花钱」） */
const costText = p => {
  const u = p.usage7d
  if (!u || u.cost == null) return '未计价'
  return '¥' + Number(u.cost).toFixed(2)
}

/** 模型 chip 悬浮：单价（元/百万 token）+ 窗口，不开「管理模型」也能比价 */
const chipTitle = b => {
  const price = (b.inputPrice == null && b.outputPrice == null)
    ? '未计价'
    : `¥${b.inputPrice ?? 0}/¥${b.outputPrice ?? 0} 每百万 token`
  return `${b.modelId} · ${TYPE_META[b.modelType]?.label || b.modelType} · ${price}`
    + (b.contextWindow ? ` · 窗口 ${fmtWindow(b.contextWindow)}` : '')
}

// ---- 列表工具栏状态：筛选 / 排序 / 搜索 / 视图 ----
const healthFilter = ref('all')   // all | ok | bad | off（off=停用或未核验灰档）
const sortKey = ref('health')     // health | usage | name
const searchKw = ref('')
// 视图选择记 localStorage：这是个人浏览习惯，不该每次进页都回到默认
const view = ref(localStorage.getItem('wenqu.provider.view') === 'table' ? 'table' : 'card')
watch(view, v => { try { localStorage.setItem('wenqu.provider.view', v) } catch (e) { /* 隐私模式写不进就算了 */ } })
// 首屏用骨架屏而不是整页转圈：先见结构再见数据；后续刷新仍走 a-spin
const firstLoad = ref(true)

const filterCounts = computed(() => {
  const c = { all: list.value.length, ok: 0, bad: 0, off: 0 }
  for (const p of list.value) {
    const cls = health(p).cls
    c[cls === 'bad' ? 'bad' : cls === 'ok' ? 'ok' : 'off']++   // idle（停用/未核验）归「已停用」档
  }
  return c
})
const filterOptions = computed(() => [
  { value: 'all', label: `全部 ${filterCounts.value.all}` },
  { value: 'ok', label: `正常 ${filterCounts.value.ok}` },
  { value: 'bad', label: `异常 ${filterCounts.value.bad}` },
  { value: 'off', label: `已停用 ${filterCounts.value.off}` }
])
const sortOptions = [
  { value: 'health', label: '健康度优先' },
  { value: 'usage', label: '近7天用量' },
  { value: 'name', label: '名称' }
]
const HEALTH_RANK = { bad: 0, ok: 1, idle: 2 }

/** 筛选 + 搜索 + 排序后的展示列表（卡片视图与表格视图同一数据源） */
const filteredList = computed(() => {
  let arr = list.value
  const f = healthFilter.value
  if (f !== 'all') {
    arr = arr.filter(p => {
      const cls = health(p).cls
      return f === 'ok' ? cls === 'ok' : f === 'bad' ? cls === 'bad' : cls === 'idle'
    })
  }
  const kw = (searchKw.value || '').trim().toLowerCase()
  if (kw) {
    arr = arr.filter(p => (p.name || '').toLowerCase().includes(kw)
      || (p.modelBriefs || []).some(b => (b.modelId || '').toLowerCase().includes(kw)
        || (b.displayName || '').toLowerCase().includes(kw)))
  }
  const s = sortKey.value
  if (s === 'name') return [...arr].sort((a, b) => (a.name || '').localeCompare(b.name || '', 'zh'))
  if (s === 'usage') return [...arr].sort((a, b) => ((b.usage7d && b.usage7d.calls) || 0) - ((a.usage7d && a.usage7d.calls) || 0))
  // 健康度优先：异常置顶（Array.sort 稳定，同档保持后端 sort_order 序）
  return [...arr].sort((a, b) => HEALTH_RANK[health(a).cls] - HEALTH_RANK[health(b).cls])
})
const resetFilters = () => { healthFilter.value = 'all'; searchKw.value = '' }

/** 表格视图「模型」列的类型简述：数量之外的第二信息只给类型分布，够速览不抢宽 */
const typeSummary = p => typeChips(p).map(t => `${t.label}${t.count}`).join(' · ') || '—'

// ==================== 主动核验 ====================
const verifyingIds = ref({})    // id → true：单家核验在途，防连点重复打网关
const verifyAllBusy = ref(false)

const setVerifying = (id, on) => {
  const next = { ...verifyingIds.value }
  if (on) next[id] = true; else delete next[id]
  verifyingIds.value = next
}

/** 单家核验：结果就地写回行（expired 同步登记额度标记，与后端被动通道一致），不整页刷新 */
const doVerify = async p => {
  if (verifyingIds.value[p.id]) return
  setVerifying(p.id, true)
  try {
    const r = await verifyProvider(p.id)
    const d = (r && r.data) || {}
    p.verifyStatus = d.verifyStatus ?? null
    p.lastVerifyMs = d.lastVerifyMs ?? null
    p.verifyMessage = d.verifyMessage ?? null
    p.lastVerifiedAt = new Date().toISOString()
    if (d.verifyStatus === 'expired') {
      p.quotaBlocked = true
      p.quotaMessage = d.verifyMessage || p.quotaMessage || '账户余额不足或套餐已到期'
    }
    const h = health(p)
    if (h.cls === 'ok') message.success(`「${p.name}」可达（${p.lastVerifyMs ?? '—'}ms）`)
    else message.warning(`「${p.name}」${h.label}：${h.tip}`)
  } catch (e) {
    message.error(`核验失败：${e?.message || '请稍后重试'}`)
  } finally {
    setVerifying(p.id, false)
  }
}

/** 工具栏「全部重新核验」：对当前筛选可见的启用供应商批量探测（不是全量，用户在看哪档就核哪档） */
const doVerifyVisible = async () => {
  const targets = filteredList.value.filter(p => p.enabled && p.manageable)
  if (!targets.length) { message.info('当前视图没有可核验的启用供应商'); return }
  verifyAllBusy.value = true
  try {
    const r = await verifyProvidersBatch(targets.map(p => p.id))
    const rows = (r && r.data) || []
    await load()
    const bad = rows.filter(x => x.verifyStatus && x.verifyStatus !== 'ok')
    if (!bad.length) message.success(`已核验 ${rows.length} 家，全部可达`)
    else message.warning(`已核验 ${rows.length} 家：${bad.length} 家异常（${bad.map(b => b.name).join('、')}）`)
  } catch (e) {
    message.error(`核验失败：${e?.message || '请稍后重试'}`)
  } finally {
    verifyAllBusy.value = false
  }
}

/** 批量管理模式下的「批量核验」：只核勾选的；异常逐条列出来，不汇总成一个数字吞掉原因 */
const doBatchVerify = async () => {
  if (!selected.value.length) return
  batchBusy.value = true
  try {
    const r = await verifyProvidersBatch([...selected.value])
    const rows = (r && r.data) || []
    await load()
    const bad = rows.filter(x => x.verifyStatus && x.verifyStatus !== 'ok')
    if (!bad.length) message.success(`已核验 ${rows.length} 家，全部可达`)
    else Modal.warning({
      title: `核验完成：${rows.length} 家中 ${bad.length} 家异常`,
      content: bad.map(b => `「${b.name}」：${b.verifyMessage || b.verifyStatus}`).join('；'),
      okText: '知道了'
    })
  } catch (e) {
    message.error('批量核验失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

// ==================== 模型矩阵（同名模型跨供应商对比） ====================
const showMatrix = ref(false)
const matrixLoading = ref(false)
// 懒加载 + 缓存一次：矩阵要聚合全部供应商×模型，开页不该付这个代价；
// 列表每次 load()（增删改/核验后都会走到）把缓存作废，避免读到旧对比
const matrixData = ref(null)
const loadMatrix = async () => {
  if (matrixData.value) return
  matrixLoading.value = true
  try {
    const r = await providerMatrix()
    matrixData.value = (r && r.data) || []
  } catch (e) {
    message.error(`矩阵加载失败：${e?.message || '请稍后重试'}`)
    matrixData.value = []
  } finally {
    matrixLoading.value = false
  }
}
const openMatrix = async () => { showMatrix.value = true; await loadMatrix() }
/** 矩阵条目 → 状态点：后端已带上 providerEnabled/quotaBlocked/verifyStatus，复用卡片同一判定 */
const entryHealth = e => health({
  enabled: e.providerEnabled,
  quotaBlocked: e.quotaBlocked,
  quotaMessage: null,
  verifyStatus: e.verifyStatus,
  lastVerifyMs: e.lastVerifyMs,
  verifyMessage: null
})
const entryPriceText = e => (e.inputPrice == null && e.outputPrice == null)
  ? '未计价' : `¥${e.inputPrice ?? 0}/¥${e.outputPrice ?? 0}`

// ---- 批量操作：列表里的供应商都是自己可管理的，可勾选 ----
const selected = ref([])
const batchBusy = ref(false)
// 批量模式默认关闭：卡片不显示勾选框，点「批量管理」才进入（退出即清空勾选）
const batchMode = ref(false)
const toggleBatchMode = () => {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = []
}
const selectableIds = computed(() => list.value.filter(p => p.manageable).map(p => p.id))
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
    message.success(`已${verb} ${okCount} 个供应商`)
    return
  }
  Modal.warning({
    title: `${verb}完成：成功 ${okCount} 个，失败 ${failed.length} 个`,
    content: failed.map(f => `「${f.name || f.id}」：${f.error}`).join('；'),
    okText: '知道了'
  })
}

const doBatchEnabled = async on => {
  batchBusy.value = true
  try {
    const r = await batchSetProvidersEnabled([...selected.value], on)
    reportBatch(r, on ? '启用' : '停用')
    await load()
    if (!on) reportLeftoverRefs(r)
  } catch (e) {
    message.error((on ? '批量启用' : '批量停用') + '失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

/**
 * 批量停用后补一遍引用账：一次点掉十个供应商，最容易漏的就是「这几个还有人在用」。
 * 不逐个弹清单（弹十次只会让人直接关掉页面），只汇总哪几个还剩几处——
 * 每张卡片上已经有常驻的「查看引用」条，点进去逐处改。
 */
const reportLeftoverRefs = r => {
  const done = new Set(((r && r.data && r.data.succeeded) || []))
  const left = list.value.filter(p => done.has(p.id) && !p.enabled && p.refCount)
  if (!left.length) return
  Modal.warning({
    title: `其中 ${left.length} 个停用后仍有地方在用它`,
    content: left.map(p => `「${p.name}」还剩 ${p.refCount} 处`).join('；')
      + '。对应卡片上已标出「查看引用」，点进去逐处换成别的模型才会真正恢复。',
    okText: '知道了'
  })
}

const doBatchDelete = () => {
  Modal.confirm({
    title: `删除选中的 ${selected.value.length} 个供应商？`,
    content: '供应商及其已登记的模型会一并删除；仍被引用的条目会失败并逐条给出原因。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      batchBusy.value = true
      try {
        const r = await batchDeleteProviders([...selected.value])
        reportBatch(r, '删除')
        await load()
      } catch (e) {
        message.error('批量删除失败：' + (e.message || ''))
      } finally {
        batchBusy.value = false
      }
    }
  })
}

// ---- 编辑弹窗 ----
const showEdit = ref(false)
const editing = ref(null)
const form = ref(blank())
// API Key 的显隐与掩码：掩码走 CSS 而非 type=password（见模板注释）
const apiKeyVisible = ref(false)
const cssTextSecurity = typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
  && CSS.supports('-webkit-text-security', 'disc')
const apiKeyType = computed(() => (apiKeyVisible.value || cssTextSecurity ? 'text' : 'password'))

// 品牌快捷区与图标弱化行状态：pickedBrand=当前点选的品牌（再点一次取消）；iconExpanded=图标网格展开
const pickedBrand = ref('')
const iconExpanded = ref(false)
// 必填项失焦校验：点保存才报错太晚，blur 即标红（保存时仍兜底校验）
const nameTouched = ref(false)
const urlTouched = ref(false)
const nameError = computed(() => (nameTouched.value && !(form.value.name || '').trim()) ? '请填写供应商名称' : '')
const urlError = computed(() => (urlTouched.value && !(form.value.baseUrl || '').trim()) ? '请填写网关地址' : '')

function blank() {
  return { name: '', icon: '', baseUrl: '', apiKey: '', completionsPath: '', embeddingsPath: '', remark: '' }
}

// 图标候选：官方 SVG + 品牌徽标（'custom' 后端迁移兜底值按名称自动生成，等价留空）
// 悬浮提示用候选自己的名字（ICON_LABELS/预设里的友好名），不跟表单已填的供应商名走
const ICON_LABELS = {
  anthropic: 'Anthropic',
  googlegemini: 'Gemini',
  alibabacloud: '阿里云',
  huggingface: 'Hugging Face',
  vllm: 'vLLM',
  mistralai: 'Mistral AI',
  modelscope: '魔搭 ModelScope',
  lmstudio: 'LM Studio',
}
const iconKeys = computed(() => {
  const label = k => BRAND_PRESETS[k]?.name || ICON_LABELS[k] || BRAND_BADGES[k]?.name || k
  const keys = [{ key: '', name: '自动（按名称）' }]
  // 'gemini' 只是渲染兜底别名（=googlegemini），枚举时跳过，否则选择器出现两个相同格子
  for (const k of Object.keys(BRAND_PATHS)) if (k !== 'gemini') keys.push({ key: k, name: label(k) })
  for (const [k, b] of Object.entries(BRAND_BADGES)) keys.push({ key: k, name: b.name || k })
  return keys
})

// 内置厂商预设：选中图标后自动填充网关地址（版本尾缀写在地址里，后端 normalize 自动识别；
// gemini 的 OpenAI 兼容层路径特殊，显式给 completions/embeddings 路径）
const BRAND_PRESETS = {
  deepseek:    { name: 'DeepSeek',    baseUrl: 'https://api.deepseek.com' },
  zhipu:       { name: '智谱GLM',     baseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
  qwen:        { name: '通义千问',     baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  moonshot:    { name: 'Kimi',        baseUrl: 'https://api.moonshot.cn' },
  doubao:      { name: '豆包',         baseUrl: 'https://ark.cn-beijing.volces.com/api/v3' },
  hunyuan:     { name: '腾讯混元',     baseUrl: 'https://api.hunyuan.cloud.tencent.com' },
  qianfan:     { name: '百度千帆',     baseUrl: 'https://qianfan.baidubce.com/v2' },
  minimax:     { name: 'MiniMax',     baseUrl: 'https://api.minimax.chat' },
  siliconflow: { name: 'SiliconFlow', baseUrl: 'https://api.siliconflow.cn' },
  openai:      { name: 'OpenAI',      baseUrl: 'https://api.openai.com' },
  openrouter:  { name: 'OpenRouter',  baseUrl: 'https://openrouter.ai/api/v1' },
  ollama:      { name: 'Ollama（自托管）', baseUrl: 'http://localhost:11434' },
  gemini:      { name: 'Gemini',      baseUrl: 'https://generativelanguage.googleapis.com',
                 completionsPath: '/v1beta/openai/chat/completions',
                 embeddingsPath: '/v1beta/openai/embeddings' },
}
// 判断当前地址是否为某个预设值（选过预设后又换品牌时应覆盖；手填的自定义地址不覆盖）
const KNOWN_PRESET_URLS = new Set(Object.values(BRAND_PRESETS).map(p => p.baseUrl.replace(/\/+$/, '')))

const onPickIcon = key => {
  form.value.icon = key
  // 与品牌快捷区保持同亮：点中的图标是预设品牌时，对应胶囊高亮；非预设/清空则脱离选中态
  pickedBrand.value = BRAND_PRESETS[key] ? key : ''
  const preset = BRAND_PRESETS[key]
  if (!preset) return
  // 名称：留空才填，不覆盖用户输入
  if (!form.value.name || !form.value.name.trim()) {
    form.value.name = preset.name
  }
  // 地址：为空、或当前值本身是预设地址时才覆盖；手填的自定义地址保持不动
  const cur = (form.value.baseUrl || '').trim().replace(/\/+$/, '')
  if (!cur || KNOWN_PRESET_URLS.has(cur)) {
    form.value.baseUrl = preset.baseUrl
    form.value.completionsPath = preset.completionsPath || ''
    form.value.embeddingsPath = preset.embeddingsPath || ''
  }
}

/** 品牌快捷区候选：新建弹窗第一入口，预设 key 与图标库一一对应，点选即带出真实品牌图标 */
const brandChips = ['deepseek', 'zhipu', 'qwen', 'moonshot', 'doubao', 'openai', 'ollama']
  .map(k => ({ key: k, label: BRAND_PRESETS[k].name }))

/**
 * 品牌胶囊点选。与 onPickIcon 的差异在「换品牌」：名称若仍是上一个品牌的预设名
 * （即用户没手改过）就跟着换，不认「已填过名称就不动」——不然从 DeepSeek 换到智谱，
 * 地址换过去了名称还写着 DeepSeek。手改过的名称任何时候都不覆盖。
 * 再点一次取消 / 点「自定义」：只清选中态，填过的内容保留（用户可能已继续手改）。
 */
const onPickBrand = key => {
  if (!key || pickedBrand.value === key) {
    pickedBrand.value = ''
    return
  }
  const prev = pickedBrand.value
  pickedBrand.value = key
  onPickIcon(key)
  const preset = BRAND_PRESETS[key]
  if (!preset) return
  const name = (form.value.name || '').trim()
  if (!name || (prev && name === BRAND_PRESETS[prev].name)) {
    form.value.name = preset.name
  }
}

/** 图标弱化行的文案：说清当前图标从哪来（按名称自动 / 品牌预设 / 自定义 URL） */
const iconRowText = computed(() => {
  const v = form.value.icon
  if (!v) return '留空按名称自动生成'
  if (/^https?:\/\//i.test(v)) return '已用自定义图片 URL'
  return '已选：' + (BRAND_PRESETS[v]?.name || ICON_LABELS[v] || BRAND_BADGES[v]?.name || v)
})

// 图标输入框只承载自定义图片 URL：内置品牌 key 由上方图标格选择（form.icon 统一存 key 或 URL），
// 否则 key 原样显示在文本框里会被误认成第二个名称
const iconUrl = computed({
  get: () => (/^https?:\/\//i.test(form.value.icon || '') ? form.value.icon : ''),
  set: v => {
    form.value.icon = (v || '').trim()
    if (form.value.icon) pickedBrand.value = ''   // 贴自定义 URL 即脱离品牌预设选中态
  }
})

const typeOptions = [
  { label: '聊天', value: 'chat' },
  { label: '视觉', value: 'vision' },
  { label: 'OCR 专用', value: 'ocr' },
  { label: '向量', value: 'embedding' },
  { label: '重排', value: 'rerank' },
  { label: '语音', value: 'audio' },
  { label: '全模态', value: 'omni' },
  { label: '其他', value: 'other' },
]

const TYPE_META = {
  chat: { label: '聊天', color: 'blue' },
  vision: { label: '视觉', color: 'geekblue' },
  ocr: { label: 'OCR', color: 'volcano' },
  embedding: { label: '向量', color: 'purple' },
  rerank: { label: '重排', color: 'cyan' },
  audio: { label: '语音', color: 'orange' },
  omni: { label: '全模态', color: 'gold' },
  other: { label: '其他', color: 'default' },
}

function typeChips(p) {
  const counts = p.typeCounts || {}
  return Object.keys(TYPE_META)
    .filter(k => counts[k])
    .map(k => ({ key: k, ...TYPE_META[k], count: counts[k] }))
}

/** 页头统计：启用中的供应商数 / 跨供应商的已登记模型总数 */
const enabledCount = computed(() => list.value.filter(p => p.enabled).length)
const modelTotal = computed(() => list.value.reduce((n, p) => n + (p.modelCount || 0), 0))

const load = async () => {
  loading.value = true
  try {
    const r = await listProviders()
    list.value = (r && r.data) || []
    // 勾选与现存列表对账：已被删掉的 id 从选中集合里清掉（避免批量操作撞「不存在」）
    const alive = new Set(list.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
    // 列表是模型/核验/引用的唯一真源：任何增删改后矩阵缓存都作废，下次打开矩阵重新拉一次
    matrixData.value = null
  } catch (e) {
    message.error(e.message || '供应商列表加载失败')
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  editing.value = null
  form.value = blank()
  testResult.value = null
  apiKeyVisible.value = false
  pickedBrand.value = ''
  iconExpanded.value = false
  nameTouched.value = false
  urlTouched.value = false
  showEdit.value = true
}

const openEdit = p => {
  editing.value = p
  apiKeyVisible.value = false
  form.value = {
    name: p.name || '',
    icon: p.icon || '',
    baseUrl: p.baseUrl || '',
    // 不回填掩码：留空提交，后端保留库中已存 Key
    apiKey: '',
    completionsPath: p.completionsPath || '',
    embeddingsPath: p.embeddingsPath || '',
    remark: p.remark || ''
  }
  testResult.value = null
  // 编辑态没有品牌快捷区，但胶囊选中态仍要归零，图标网格默认收起
  pickedBrand.value = ''
  iconExpanded.value = false
  nameTouched.value = false
  urlTouched.value = false
  showEdit.value = true
}

const save = async () => {
  if (!form.value.name || !form.value.name.trim()) {
    nameTouched.value = true   // 行内校验同步亮红，不只弹一句 toast
    message.warning('请填写供应商名称')
    return
  }
  if (!form.value.baseUrl || !form.value.baseUrl.trim()) {
    urlTouched.value = true
    message.warning('请填写网关地址')
    return
  }
  saving.value = true
  try {
    const body = { ...form.value }
    let r
    if (editing.value) r = await updateProvider(editing.value.id, body)
    else r = await createProvider(body)
    message.success(editing.value ? '已保存' : '已创建')
    showEdit.value = false
    await load()
    refreshSetupGuide(true)
    // 新建供应商一气呵成：接口返回 Provider 实体（r.data.id）时自动打开其「管理模型」弹窗，
    // 「新建 → 拉取模型 → 保存」不断链，减少小白找入口的断点
    const newId = !editing.value && r && r.data && r.data.id
    if (newId) {
      const created = list.value.find(p => p.id === newId)
      if (created) {
        await openModels(created)
        // 从配置引导来的（建供应商是为了登记模型）：直接弹出新增表单并预选类型
        if (pendingSetupType.value) {
          openModelAdd(pendingSetupType.value)
          pendingSetupType.value = ''
        }
      }
    }
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/**
 * 启停开关。停用不是终点：开关只让模型「不再可选」，钉着它的地方一处都不会自己解开。
 * 所以停用成功后立刻把还挂在上面的位置摊开（有引用就弹清单、没引用才报一句干净），
 * 而不是等用户回到聊天页撞一句「所在的供应商已停用」。
 */
const onToggle = async (p, v) => {
  try {
    await setProviderEnabled(p.id, v)
    p.enabled = v
    if (v) {
      p.refCount = 0
      return
    }
    const d = await loadRefs(p)
    const n = refTotalOf(d)
    if (n === null) {
      // 清单没拉到就不报「没有地方在用」：重拉一次列表，让卡片上的常驻提醒按服务端口径补回来
      message.warning('已停用，但引用清单没拉到，稍后可在卡片上点「查看引用」重试')
      await load()
      return
    }
    p.refCount = n
    if (n) showRefs.value = true
    else message.success('已停用，没有地方在引用它的模型')
  } catch (e) {
    message.error(e.message || '操作失败')
  }
}

const onDelete = async p => {
  // 确认交互由调用方承担（卡片/表格的「⋯」菜单走 Modal.confirm，文案与原先行内 popconfirm 一致）
  try {
    await deleteProvider(p.id)
    message.success('已删除')
    await load()
    refreshSetupGuide(true)  // 删掉模型后引导清单/tag 需要回升（对账）
  } catch (e) {
    // 仍被引用时后端会列出引用类型。一次性 toast 读完就没了，等于把人挡在「删不掉、
    // 又找不到地方改」；这里留住原因并直接给清单入口
    Modal.confirm({
      title: `删不掉：「${p.name}」仍被引用`,
      content: e.message || '该供应商登记的模型还在被使用',
      okText: '查看引用', cancelText: '知道了',
      onOk: () => openRefs(p)
    })
  }
}

/** 「⋯」菜单里的删除：确认语义与原先的 a-popconfirm 完全一致，只是入口从卡片底部收进菜单 */
const confirmDelete = p => {
  Modal.confirm({
    title: '确定删除该供应商？其已登记的模型会一并删除。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: () => onDelete(p)
  })
}

// ---- 表格视图列（数据源与卡片视图同为 filteredList）----
const tableCols = [
  { title: '供应商', key: 'prov', width: 220 },
  { title: '状态', key: 'health', width: 108 },
  { title: '模型', key: 'models', width: 170 },
  { title: '最近核验', key: 'verify', width: 168 },
  { title: '近7天调用', key: 'calls', width: 96 },
  { title: '预估费用', key: 'cost', width: 96 },
  { title: '引用', key: 'refs', width: 88 },
  { title: '操作', key: 'action', width: 150 }
]

/**
 * 测试连接：优先拉一次 /v1/models（顺带验证地址与 Key）；
 * 网关未实现 /v1/models 时回退「最小补全/向量探测」——用该供应商第一个已登记模型真实调用一次。
 */
const onTest = async () => {
  if (!form.value.baseUrl || !form.value.baseUrl.trim()) {
    message.warning('请先填写网关地址')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const r = await fetchProviderModels(form.value.baseUrl.trim(), form.value.apiKey || '', editing.value?.id)
    const n = (r && r.data && r.data.length) || 0
    testResult.value = { ok: true, text: `连接成功，网关返回 ${n} 个模型` }
  } catch (e) {
    // /v1/models 不通：已保存的供应商改用模型级探测（第一个启用的模型，优先聊天类型）
    const firstModel = await firstRegisteredModel(editing.value?.id)
    if (firstModel) {
      try {
        const tr = await testProvider({
          providerId: editing.value.id,
          baseUrl: form.value.baseUrl.trim(),
          apiKey: '',
          model: firstModel.modelId,
          modelType: firstModel.modelType,
          path: firstModel.modelType === 'embedding'
            ? (form.value.embeddingsPath || editing.value.embeddingsPath || '')
            : (form.value.completionsPath || editing.value.completionsPath || '')
        })
        const d = (tr && tr.data) || {}
        testResult.value = d.available
          ? { ok: true, text: `连接成功（模型 ${firstModel.modelId} 探测可达 ${d.latencyMs}ms）` }
          : { ok: false, text: `网关已连通，但模型 ${firstModel.modelId} 探测失败：${d.detail || '（无详情）'}` }
      } catch (e2) {
        testResult.value = { ok: false, text: e2.message || '测试失败' }
      }
    } else {
      testResult.value = {
        ok: false,
        text: (e.message || '连接失败') + '；网关可能未实现 /v1/models——新供应商可先保存，在「管理模型」里手动添加模型后用行内「测试」探测'
      }
    }
  } finally {
    testing.value = false
  }
}

/** 取供应商第一个启用模型（优先 chat 类型），供 /v1/models 不通时的探测回退 */
const firstRegisteredModel = async providerId => {
  if (!providerId) return null
  try {
    const r = await listProviderModels(providerId)
    const ms = ((r && r.data) || []).filter(m => m.enabled !== false)
    return ms.find(m => m.modelType === 'chat') || ms[0] || null
  } catch (e) {
    return null
  }
}

// ---- 模型管理弹窗 ----
const showModels = ref(false)
const modelProvider = ref(null)
const models = ref([])
const candidates = ref([])
const candidateKeyword = ref('')
const fetching = ref(false)
const savingModels = ref(false)
let rowSeq = 0

// 表格只做速览：模型名（含展示名副行）/ 类型 / 能力 / 窗口输出，配置进「编辑模型配置」弹窗
// 窗口列宽度按最宽单值（如 1M / 977K）预留，窄了会折行（列里只会是单值或「未声明」）
const modelCols = [
  { title: '模型名', key: 'modelId' },
  { title: '类型', key: 'modelType', width: 112 },
  { title: '能力', key: 'caps', width: 130 },
  { title: '窗口 / 输出', key: 'ctx', width: 158 },
  { title: '启用', key: 'enabled', width: 56 },
  { title: '操作', key: 'action', width: 172 }
]

// 思考能力选项（auto=按模型名判定）；仅对聊天模型有意义，其他类型存了也不生效
const thinkingOptions = [
  { label: '自动', value: 'auto' },
  { label: '不支持', value: 'none' },
  { label: '可开关', value: 'switchable' },
  { label: '恒思考', value: 'always' }
]

/** 图片理解仅对话类类型有意义（向量/重排/OCR/语音没有图片理解可言），其余类型禁选并按自动保存 */
const visionApplies = record => ['chat', 'vision', 'omni'].includes(record.modelType)

// ==================== 模型配置表单（添加/编辑共用弹窗） ====================
const editOpen = ref(false)
/** 是否新增模式（编辑已有行时模型 ID 锁定：各处绑定按「供应商/模型名」寻址，改名等于换模型） */
const editIsNew = ref(false)
const editIdLocked = ref(false)
/** 编辑中的行副本（改完写回原行，不直接动表格数据，取消即放弃） */
const editForm = ref(null)
/** 编辑目标行（重置表单 / 写回定位用；新增模式为空） */
const editSourceKey = ref('')
/** 编辑副本的档位集合（勾选项，与 record.reasoningLevels 数组同步） */
const editLevels = ref([])
/** 打开弹窗时的表单快照：关闭（X/Esc/取消）时对比，有改动先确认再丢弃——
 *  修改过的值静默丢失会表现为「配置老是不生效」 */
const editBaseline = ref('')
const editSnapshot = () => editForm.value
  ? JSON.stringify([editForm.value, [...(editLevels.value || [])]])
  : ''
/** 关闭编辑弹窗（X / Esc / 取消共用）：表单相对打开时有改动 → 先确认再丢弃 */
const onEditCancel = () => {
  if (editForm.value && editSnapshot() !== editBaseline.value) {
    Modal.confirm({
      title: '有未应用的修改',
      content: '关闭将放弃本次修改。注意：点「确定」只是写回下方列表，还需点「保存（N 个启用）」才会真正生效。',
      okText: '放弃修改并关闭',
      okButtonProps: { danger: true },
      cancelText: '继续编辑',
      onOk: () => { editOpen.value = false }
    })
    return
  }
  editOpen.value = false
}

/** 能力三态选项（图片输入/工具调用共用）：auto=按类型与模型名自动判定 */
const triOptions = [
  { label: '自动', value: 'auto' },
  { label: '支持', value: '1' },
  { label: '不支持', value: '0' }
]
/** 思考强度档位（由弱到强，与后端 REASONING_LEVEL_LIST 同序） */
const reasoningLevelOptions = [
  { label: '低', value: 'low' },
  { label: '中', value: 'medium' },
  { label: '高', value: 'high' },
  { label: '超高', value: 'xhigh' },
  { label: '极致', value: 'max' }
]
/** 默认强度下拉：只列已勾选档位（后端会校验默认档位须在支持档位内，前端先约束避免报错） */
const defaultLevelOptions = computed(() => {
  const picked = editLevels.value
  return picked.length
      ? reasoningLevelOptions.filter(l => picked.includes(l.value))
      : []
})
// ==================== 思考参数映射（方言点名 + 档位值覆盖） ====================
/** 后端方言目录：下拉、内置 effort/budget 占位值都从这里取——收敛表是各家文档调研来的，会随版本修正，前端抄一份就对不上账 */
const dialectCatalog = ref([])
const loadDialectCatalog = async () => {
  try {
    const r = await listReasoningDialects()
    dialectCatalog.value = (r && r.data) || []
  } catch (e) {
    dialectCatalog.value = []
  }
}
/** 不点名时后端按模型名判定的结果（编辑中改模型名即重算） */
const autoDialect = ref('')
let dialectGuessSeq = 0
const refreshAutoDialect = async modelId => {
  const seq = ++dialectGuessSeq
  autoDialect.value = ''
  if (!modelId) return
  try {
    const r = await guessReasoningDialect(modelId)
    if (seq === dialectGuessSeq) autoDialect.value = (r && r.data && r.data.value) || ''
  } catch (e) {
    // 取不到就退回「未判定」显示，不打断编辑
  }
}
const dialectOptions = computed(() => [
  { value: '', label: '自动（按模型名判定）' },
  ...dialectCatalog.value.map(d => ({ value: d.value, label: d.label }))
])
/** 映射对象的读取形状（v-model 直接落在 editForm.reasoningParams 上，这里只出快照） */
const paramsShape = p => {
  const src = p && typeof p === 'object' ? p : {}
  return { dialect: src.dialect || '', effort: { ...(src.effort || {}) }, budget: { ...(src.budget || {}) } }
}
const editParams = computed(() => paramsShape(editForm.value && editForm.value.reasoningParams))
const resolvedDialect = computed(() => editParams.value.dialect || autoDialect.value || 'NONE')
const resolvedEntry = computed(() => dialectCatalog.value.find(d => d.value === resolvedDialect.value) || null)
const resolvedLabel = computed(() => (resolvedEntry.value && resolvedEntry.value.label) || '未判定')
const dialectHasEffort = computed(() => !!(resolvedEntry.value && resolvedEntry.value.effort))
const dialectHasBudget = computed(() => !!(resolvedEntry.value && resolvedEntry.value.budget))
const builtinEffort = lv => {
  const e = resolvedEntry.value && resolvedEntry.value.effort
  return e && e[lv] ? e[lv] : '该方言无 effort'
}
const builtinBudget = lv => {
  const b = resolvedEntry.value && resolvedEntry.value.budget
  return b && b[lv] ? String(b[lv]) : '不发预算'
}
const overridden = lv => {
  const p = editParams.value
  const e = String(p.effort[lv] == null ? '' : p.effort[lv]).trim()
  const b = p.budget[lv]
  return !!e || (b !== null && b !== undefined && b !== '')
}
const clearOverride = lv => {
  const f = editForm.value
  if (!f || !f.reasoningParams) return
  delete f.reasoningParams.effort[lv]
  delete f.reasoningParams.budget[lv]
}
/** 无强度语义却勾了档位：档位发不出去，界面先说清，别等保存才发现 */
const dialectWarn = computed(() => resolvedDialect.value === 'NONE' && editLevels.value.length
  ? '此刻判定为「无强度语义」，勾选的档位不会下发——用上面「参数方言」点名网关真实那一家。' : '')
/** 保存与改动快照共用一份口径：只留已勾选档位的覆盖，全空=null（=完全跟随内置表） */
const paramsPayload = m => {
  const p = paramsShape(m.reasoningParams)
  const keep = Array.isArray(m.reasoningLevels) ? m.reasoningLevels : []
  const effort = {}
  const budget = {}
  for (const lv of keep) {
    const e = String(p.effort[lv] == null ? '' : p.effort[lv]).trim()
    if (e) effort[lv] = e
    const b = Number(p.budget[lv])
    if (b > 0) budget[lv] = b
  }
  const out = {}
  if (p.dialect) out.dialect = p.dialect
  if (Object.keys(effort).length) out.effort = effort
  if (Object.keys(budget).length) out.budget = budget
  return Object.keys(out).length ? out : null
}

/** 类型门控（与后端 saveModels 的归一口径一致）：图片/档位=对话类，工具=聊天/全模态，思考模式=聊天 */
const editCapApplies = computed(() => visionApplies(editForm.value || {}))
const editToolApplies = computed(() => ['chat', 'omni'].includes(editForm.value?.modelType))
const editChatOnly = computed(() => editForm.value?.modelType === 'chat')
/** 与后端 ModelRegistryService.CONTEXT_SAFETY_FACTOR 同一个系数，改那边要改这里 */
const CONTEXT_SAFETY_FACTOR = 0.7
/** 与后端 saveModels 同式的冲突判据：输出吃不掉「窗口 × 安全系数」时检索资料塞不进，返回那份预算 */
const budgetConflict = computed(() => {
  const f = editForm.value
  if (!f || f.contextWindow == null || f.maxOutput == null) return null
  const budget = Math.floor(f.contextWindow * CONTEXT_SAFETY_FACTOR)
  return f.maxOutput >= budget ? budget : null
})

const blankEditForm = () => ({
  modelId: '', displayName: '', modelType: 'chat',
  visionCapable: 'auto', toolCapable: 'auto', thinking: 'auto',
  defaultReasoningLevel: undefined, contextWindow: null, maxOutput: null,
  inputPrice: null, outputPrice: null,
  reasoningParams: { dialect: '', effort: {}, budget: {} }
})

const openModelAdd = (type = 'chat') => {
  editIsNew.value = true
  editIdLocked.value = false
  editSourceKey.value = ''
  editForm.value = { ...blankEditForm(), modelType: type }
  editLevels.value = []
  editBaseline.value = editSnapshot()
  editOpen.value = true
}

const openModelEdit = record => {
  editIsNew.value = false
  editIdLocked.value = !record.isNew   // 未保存的新行仍可改模型名
  editSourceKey.value = record.rowKey
  editForm.value = {
    modelId: record.modelId,
    displayName: record.displayName || '',
    modelType: record.modelType || 'chat',
    visionCapable: record.visionCapable || 'auto',
    toolCapable: record.toolCapable || 'auto',
    thinking: record.thinking || 'auto',   // 登记原值（auto 保持自动，不写死解析结果）
    defaultReasoningLevel: record.defaultReasoningLevel || undefined,
    contextWindow: record.contextWindow ?? null,
    maxOutput: record.maxOutput ?? null,
    inputPrice: record.inputPrice ?? null,
    outputPrice: record.outputPrice ?? null,
    reasoningParams: paramsShape(record.reasoningParams)
  }
  editLevels.value = Array.isArray(record.reasoningLevels) ? [...record.reasoningLevels] : []
  // 已存行后端已带回自动判定结果，先显示着，等模型名一改再重算
  autoDialect.value = record.reasoningDialectAuto || ''
  editBaseline.value = editSnapshot()
  editOpen.value = true
}

/** 重置表单：编辑回到该行打开时的值，新增清空 */
const resetEditForm = () => {
  if (!editIsNew.value) {
    const src = models.value.find(m => m.rowKey === editSourceKey.value)
    if (src) { openModelEdit(src); return }
  }
  editForm.value = blankEditForm()
  editLevels.value = []
}

/** 新增时按模型家族预填窗口/输出（两项都已填就不覆盖） */
const onEditModelIdChange = () => {
  const f = editForm.value
  if (editIsNew.value && f && f.contextWindow == null && f.maxOutput == null) {
    const g = guessCtx(f.modelId)
    if (g) Object.assign(f, g)
  }
}

const toggleEditLevel = (lv, checked) => {
  const on = typeof checked === 'boolean' ? checked : !editLevels.value.includes(lv)
  const next = on
      ? [...editLevels.value, lv].sort(
          (a, b) => reasoningLevelOptions.findIndex(x => x.value === a) - reasoningLevelOptions.findIndex(x => x.value === b))
      : editLevels.value.filter(x => x !== lv)
  editLevels.value = next
  // 默认强度必须落在支持档位内：取消勾选时同步清空，避免留下自相矛盾的配置
  if (editForm.value.defaultReasoningLevel && !next.includes(editForm.value.defaultReasoningLevel)) {
    editForm.value.defaultReasoningLevel = undefined
  }
}

/** 确定：校验后写回待保存列表（新增则插入一行「待保存」），统一由「保存（N 个启用）」入库 */
const applyModelEdit = () => {
  const f = editForm.value
  if (!f) return
  const modelId = (f.modelId || '').trim()
  if (!modelId) {
    message.warning('请填写模型 ID')
    return
  }
  const dup = models.value.find(m => m.modelId === modelId && m.rowKey !== (editIsNew.value ? '' : editSourceKey.value))
  if (dup) {
    message.warning(`模型 ${modelId} 已在本供应商登记`)
    return
  }
  // 窗口无全局兜底：对话类模型必须声明窗口（后端保存同样强制），能力型类型才可留空
  if (['chat', 'vision', 'omni'].includes(f.modelType) && f.contextWindow == null) {
    message.warning('对话类模型必须声明上下文窗口')
    return
  }
  if (budgetConflict.value) {
    message.warning(`最大输出必须小于窗口预算 ${budgetConflict.value.toLocaleString('en-US')} token（窗口 × 0.7）`)
    return
  }
  const cap = visionApplies(f)
  const norm = {
    modelId,
    displayName: (f.displayName || '').trim(),
    modelType: f.modelType || 'chat',
    // 类型不适用时归一为自动（与保存时的归一口径一致，避免禁选态残留旧值）
    visionCapable: cap ? (f.visionCapable || 'auto') : 'auto',
    toolCapable: ['chat', 'omni'].includes(f.modelType) ? (f.toolCapable || 'auto') : 'auto',
    thinking: f.modelType === 'chat' ? (f.thinking || 'auto') : 'auto',
    reasoningLevels: cap ? [...editLevels.value] : [],
    defaultReasoningLevel: cap ? (f.defaultReasoningLevel || null) : null,
    reasoningParams: cap ? paramsPayload({ reasoningParams: f.reasoningParams, reasoningLevels: editLevels.value }) : null,
    contextWindow: f.contextWindow ?? null,
    maxOutput: f.maxOutput ?? null,
    inputPrice: f.inputPrice ?? null,
    outputPrice: f.outputPrice ?? null
  }
  if (editIsNew.value) {
    models.value.push({
      rowKey: 'new-' + (++rowSeq), id: undefined, isNew: true, enabled: true,
      visionResolved: false, toolCapableResolved: true, ...norm
    })
  } else {
    const target = models.value.find(m => m.rowKey === editSourceKey.value)
    if (!target) return
    if (target.modelType !== norm.modelType) clearTestState(target)  // 类型影响探测口径，旧结果作废
    Object.assign(target, norm)
  }
  editOpen.value = false
}

// ==================== 表格速览（只读汇总） ====================
/** 能力标签：对话类模型展示图片/思考速览（已登记行用后端解析结果，新行只显示显式标记） */
const capsOf = r => {
  if (!visionApplies(r)) return []
  const out = []
  if (r.visionResolved) out.push('图片')
  // 只标"会被判为不能调工具"这一侧：对话类模型默认都能调，逐个标「工具」是噪声；
  // 而私有化网关不认 function calling 时，这个标记就是"为什么它不咨询其他助手"的第一条线索
  if (r.toolCapableResolved === false) out.push('无工具')
  const t = r.isNew ? r.thinking : (r.thinkingParsed || r.thinking)
  if (t && t !== 'none' && t !== 'auto') {
    out.push(r.reasoningLevels?.length ? `思考·${r.reasoningLevels.length}档` : '思考')
  }
  return out
}
const ctxText = r => {
  if (r.contextWindow == null && r.maxOutput == null) return '未声明'
  const win = r.contextWindow ? fmtWindow(r.contextWindow) : '默认'
  return `${win} / ${r.maxOutput ? fmtWindow(r.maxOutput) : '默认'}`
}
const ctxTitle = r =>
  `上下文窗口 ${String(r.contextWindow ?? '默认')} / 最大输出 ${r.maxOutput ?? '默认'} token（窗口对话类必填，对话页按此上限取窗口）`

/** 每行模型的连通性测试状态：rowKey → {loading, ok, latencyMs, text} */
const testStates = ref({})
const testState = record => testStates.value[record.rowKey] || { loading: false, ok: null, latencyMs: 0, text: '' }

// 常见模型家族的窗口/输出预填建议：纯表单便利（官方数值随版本会变，保存前请核对），运行时不读它。
// 窗口一律填十进制 1000000 而不是 2 的 20 次方：厂商写「1M」时两种口径都有，填小了只是提前折一次上下文，
// 填大了会在长轮中途撞上上游的超窗报错。output 留空=不下发 max_tokens，按厂商默认。
const MODEL_PRESET_HINTS = [
  { kw: 'deepseek', window: 1000000, output: 384000 },
  { kw: 'qwen', window: 1000000 },
  { kw: 'glm', window: 1000000, output: 131072 },
  { kw: 'gpt', window: 1000000 },
  { kw: 'claude', window: 1000000 },
  { kw: 'gemini', window: 1000000 },
  { kw: 'kimi', window: 1000000 },
  { kw: 'moonshot', window: 1000000 }
]
const guessCtx = modelId => {
  const id = (modelId || '').toLowerCase()
  const hit = MODEL_PRESET_HINTS.find(p => id.includes(p.kw))
  return hit ? { contextWindow: hit.window, maxOutput: hit.output ?? null } : null
}

/** 新增行填写模型名后按家族预填窗口/输出（两项任一已填/手改过就不覆盖） */
const applyCtxPreset = record => {
  if (!record.isNew || record.contextWindow != null || record.maxOutput != null) return
  const g = guessCtx(record.modelId)
  if (g) Object.assign(record, g)
}

/** 模型名/类型改动后旧测试结果失效，清除 */
const clearTestState = record => {
  if (testStates.value[record.rowKey]) {
    const next = { ...testStates.value }
    delete next[record.rowKey]
    testStates.value = next
  }
}

/** 单模型连通性测试：chat/vision/omni 发最小补全、embedding 真实向量一次、rerank 探服务、audio 探网关可达（后端分派） */
const doTestModel = async record => {
  const p = modelProvider.value
  if (!p || !record.modelId || !record.modelId.trim()) {
    message.warning('请先填写模型名')
    return
  }
  testStates.value = {
    ...testStates.value,
    [record.rowKey]: { loading: true, ok: null, latencyMs: 0, text: '' }
  }
  const key = record.rowKey
  const done = st => { testStates.value = { ...testStates.value, [key]: st } }
  try {
    const r = await testProvider({
      providerId: p.id,
      baseUrl: p.baseUrl,
      // apiKey 传空：后端用库中真实 Key（避免掩码误判）
      apiKey: '',
      model: record.modelId.trim(),
      modelType: record.modelType || 'chat',
      // embedding 探测需要向量路径，其余走补全路径
      path: record.modelType === 'embedding' ? (p.embeddingsPath || '') : (p.completionsPath || '')
    })
    const d = (r && r.data) || {}
    done({
      loading: false,
      ok: !!d.available,
      latencyMs: d.latencyMs ?? 0,
      text: d.available ? `可用（${d.detail || ''}）` : (d.detail || '探测失败')
    })
  } catch (e) {
    done({ loading: false, ok: false, latencyMs: 0, text: e.message || '测试失败' })
  }
}

/** 候选按关键字过滤（不区分大小写） */
const filteredCandidates = computed(() => {
  const kw = (candidateKeyword.value || '').trim().toLowerCase()
  if (!kw) return candidates.value
  return candidates.value.filter(c => c.modelId.toLowerCase().includes(kw))
})

const inModels = modelId =>
  models.value.some(m => m.modelId === modelId)

const openModels = async p => {
  modelProvider.value = p
  candidates.value = []
  candidateKeyword.value = ''
  showModels.value = true
  try {
    const r = await listProviderModels(p.id)
    models.value = ((r && r.data) || []).map(m => ({
      rowKey: 'db-' + m.id, id: m.id,
      modelId: m.modelId, displayName: m.displayName || '',
      modelType: m.modelType || 'chat',
      thinking: m.thinkingRaw || 'auto',            // 登记原值（编辑回填用，避免 auto 被解析结果写死）
      thinkingParsed: m.thinking || 'auto',         // 解析结果（auto 已按模型名判定，速览标签用）
      visionCapable: m.visionCapable == null ? 'auto' : String(m.visionCapable),
      visionResolved: !!m.visionResolved,           // 图片能力解析结果（速览标签用）
      toolCapableResolved: m.toolCapableResolved !== false,  // 工具能力解析结果（登记值未填=放行，这里回显实际结论）
      toolCapable: m.toolCapable == null ? 'auto' : String(m.toolCapable),
      reasoningLevels: Array.isArray(m.reasoningLevels) ? [...m.reasoningLevels] : [],
      defaultReasoningLevel: m.defaultReasoningLevel || null,
      reasoningParams: m.reasoningParams || null,
      reasoningDialectAuto: m.reasoningDialectAuto || '',
      contextWindow: m.contextWindow ?? null,
      maxOutput: m.maxOutput ?? null,
      inputPrice: m.inputPrice ?? null, outputPrice: m.outputPrice ?? null,
      remark: m.remark || '',
      enabled: m.enabled !== false, isNew: false
    }))
    modelsBaseline.value = Object.fromEntries(models.value.map(m => [m.rowKey, rowSnapshot(m)]))
  } catch (e) {
    message.error(e.message || '模型列表加载失败')
    models.value = []
  }
}

// ---- 未保存改动守卫：本弹窗是「暂存区」语义（确定只写回列表，保存才入库），------------
// ---- 关闭即丢弃暂存改动，必须先问一声，否则就是「配置改了老是不生效」 ---------------
/** 行的可比快照（与保存 payload 的字段口径一致） */
const rowSnapshot = m => JSON.stringify({
  modelId: m.modelId, displayName: m.displayName || '', modelType: m.modelType || 'chat',
  thinking: m.modelType === 'chat' ? (m.thinking || 'auto') : 'auto',
  visionCapable: visionApplies(m) ? (m.visionCapable || 'auto') : 'auto',
  toolCapable: visionApplies(m) ? (m.toolCapable || 'auto') : 'auto',
  reasoningLevels: visionApplies(m) ? (m.reasoningLevels || []) : [],
  defaultReasoningLevel: visionApplies(m) ? (m.defaultReasoningLevel || null) : null,
  reasoningParams: visionApplies(m) ? paramsPayload(m) : null,
  contextWindow: m.contextWindow || null,
  maxOutput: m.maxOutput || null,
  inputPrice: m.inputPrice ?? null, outputPrice: m.outputPrice ?? null,
  remark: m.remark || '', enabled: m.enabled !== false
})
/** 打开弹窗时从库里拉到的行基线（rowKey → 快照） */
const modelsBaseline = ref({})
/** 某行相对库中登记是否有未保存改动（新行恒为改动） */
const rowChanged = record => {
  if (record.isNew) return true
  const base = modelsBaseline.value[record.rowKey]
  return base === undefined || base !== rowSnapshot(record)
}
/** 暂存区是否有未保存改动：改过的行 + 被移除的行 */
const stagingDirty = computed(() => {
  if (models.value.some(rowChanged)) return true
  const keys = new Set(models.value.filter(m => !m.isNew).map(m => m.rowKey))
  return Object.keys(modelsBaseline.value).some(k => !keys.has(k))
})
/** 关闭管理弹窗（X / Esc / 点遮罩共用） */
const onModelsCancel = () => {
  if (stagingDirty.value) {
    Modal.confirm({
      title: '有未保存的模型配置',
      content: '关闭弹窗会丢弃这些修改。点「确定」的改动只是写回了列表，需点「保存（N 个启用）」才会生效。',
      okText: '仍要关闭',
      okButtonProps: { danger: true },
      cancelText: '留下来保存',
      onOk: () => { showModels.value = false }
    })
    return
  }
  showModels.value = false
}

const onFetch = async () => {
  fetching.value = true
  try {
    // 已存供应商：apiKey 传空，由后端用库中真实 Key 解密后拉取
    const r = await fetchProviderModels(modelProvider.value.baseUrl, '', modelProvider.value.id)
    candidates.value = ((r && r.data) || []).map(c => ({
      modelId: c.modelId, guessedType: c.guessedType || 'chat',
      guessVisionCapable: !!c.guessVisionCapable, exists: !!c.exists
    }))
    candidateKeyword.value = ''
    if (!candidates.value.length) message.info('网关未返回任何模型')
  } catch (e) {
    message.warning(e.message || '拉取失败（网关可能未实现 /v1/models，可手动添加）')
  } finally {
    fetching.value = false
  }
}

/** 候选加入待保存列表（类型用后端自动分类结果，加入后可在下方表格修改） */
const addCandidate = c => {
  if (inModels(c.modelId)) return
  const row = {
    rowKey: 'new-' + (++rowSeq), modelId: c.modelId, displayName: '', thinking: 'auto',
    modelType: c.guessedType, visionCapable: c.guessVisionCapable ? '1' : 'auto',
    toolCapable: 'auto', reasoningLevels: [], defaultReasoningLevel: null,
    contextWindow: null, maxOutput: null, enabled: true, isNew: true
  }
  applyCtxPreset(row)
  models.value.push(row)
}

const importAllCandidates = () => {
  let added = 0
  for (const c of filteredCandidates.value) {
    if (inModels(c.modelId)) continue
    addCandidate(c)
    added++
  }
  message.success(added ? `已加入 ${added} 个待保存模型` : '没有可导入的新模型')
}

const removeRow = async record => {
  // 未点「保存」前移除都可恢复（关闭弹窗即放弃改动），无需确认
  models.value = models.value.filter(m => m.rowKey !== record.rowKey)
}

const saveModels = async () => {
  const payload = models.value
    .filter(m => m.modelId && m.modelId.trim())
    .map(m => ({
      modelId: m.modelId.trim(),
      displayName: (m.displayName || '').trim() || null,
      modelType: m.modelType || 'chat',
      thinking: m.modelType === 'chat' ? (m.thinking || 'auto') : 'auto',
      // 非对话类类型归一为自动（禁选态下也可能残留旧值，保存时兜底）
      visionCapable: visionApplies(m) ? (m.visionCapable || 'auto') : 'auto',
      // 工具调用/思考强度仅对话类有意义
      toolCapable: visionApplies(m) ? (m.toolCapable || 'auto') : 'auto',
      reasoningLevels: visionApplies(m) ? (m.reasoningLevels || []) : [],
      defaultReasoningLevel: visionApplies(m) ? (m.defaultReasoningLevel || null) : null,
      reasoningParams: visionApplies(m) ? paramsPayload(m) : null,
      contextWindow: m.contextWindow || null,
      maxOutput: m.maxOutput || null,
      // 单价/备注必须随全量同步带上：缺了会被后端归一为未配置，每次保存把单价清空
      inputPrice: m.inputPrice ?? null,
      outputPrice: m.outputPrice ?? null,
      remark: m.remark ?? null,
      enabled: m.enabled !== false
    }))
  savingModels.value = true
  try {
    await saveProviderModels(modelProvider.value.id, payload)
    message.success(setupMode.value ? '模型已保存，返回配置引导' : '模型已保存')
    showModels.value = false
    await load()
    refreshSetupGuide(true)  // 登记模型后立即对账引导状态（返回对话页 tag/清单即时正确）
    // 从配置引导进来的：保存即完成一步，直接回去，不用用户再自己找路
    if (setupMode.value) router.push('/chat')
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    savingModels.value = false
  }
}

/** 深链处理：/agents?tab=providers&action=new-provider|add-model&type=chat|embedding&from=setup
 *  type=chat：找还没登记聊天模型的供应商；type=embedding：优先找已有聊天模型、缺向量模型的供应商。
 *  找到 → 直接打开「管理模型」并弹出「添加模型」并预选类型；找不到 → 先建供应商。
 *  处理完 replace 清掉 action：本页在 a-tabs destroy-inactive-tab-pane 下切 Tab 会重挂载，
 *  不清参数会反复自动弹窗；from=setup 保留，用于顶部返回条与保存后回跳。 */
const pickProviderFor = type => {
  const usable = list.value.filter(p => p && p.enabled !== false)
  const has = (p, k) => Boolean(p.typeCounts && p.typeCounts[k])
  // 全模态（omni）本质也是对话模型，与后端 /provider/available 的 chat 口径一致
  const hasChat = p => has(p, 'chat') || has(p, 'omni')
  const lacks = p => type === 'chat' ? !hasChat(p) : !has(p, type)
  if (type === 'embedding') {
    // 同一个网关通常同时提供聊天与向量：优先已有聊天模型的那家
    return usable.find(p => hasChat(p) && lacks(p)) || usable.find(lacks) || null
  }
  return usable.find(lacks) || null
}
/** 深链落地：按 type 选供应商并打开新增表单；没有落点时先建供应商 */
const handleSetupAction = async () => {
  const action = route.query.action
  if (action !== 'new-provider' && action !== 'add-model') return
  const type = route.query.type === 'embedding' ? 'embedding' : 'chat'
  const fromSetup = route.query.from === 'setup'
  router.replace({ path: '/agents', query: { tab: 'providers', ...(fromSetup ? { from: 'setup' } : {}) } })
  const target = pickProviderFor(type)
  if (target) {
    await openModels(target)
    openModelAdd(type)
  } else {
    pendingSetupType.value = type
    openCreate()
  }
}

// 模型名一改就重算「不点名时按谁下发」：判定规则只在后端有一份，界面不能猜
watch(() => editForm.value && editForm.value.modelId, v => refreshAutoDialect((v || '').trim()))

onMounted(async () => {
  loadDialectCatalog()
  await load()
  handleSetupAction()
  firstLoad.value = false
  // 陈旧核验 fire-and-forget：等它回来才放行首屏，骨架屏就白做了。
  // 有实际核验结果（从未核验/超新鲜窗口的启用供应商）才补一次 load()，
  // 让状态点从「未核验」翻成绿/红；失败静默，用户仍可在卡片「⋯」里手动核验
  verifyStaleProviders().then(r => {
    if (((r && r.data) || []).length) load()
  }).catch(() => {})
})
</script>

<style scoped>
/* 页头统计（与技能 / MCP 面板同款）：数字加重、小字说明 */
.head-count { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.head-count b { color: var(--app-text); font-weight: 600; }
/* 批量操作区（页头最右，分隔线与常规按钮划清界限）：开关恒在最右、进出模式位置不动 */
.batch-group { display: flex; align-items: center; gap: 8px; padding-left: 12px; border-left: 1px solid var(--app-border); }
.batch-on { color: var(--app-accent); border-color: var(--app-accent); }
.batch-del { color: var(--app-danger); }
.pv-check { flex: none; }
/* 内边距/滚动由 .app-page-body 提供（与智能体/技能/MCP 同一套骨架），此处只放网格与卡片细节 */
.pv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
/* 卡片改纵向 flex：同一行里各卡内容行数不同（额度条/备注有无），操作按钮统一沉到卡片底部对齐 */
.pv-card { padding: 14px 16px; display: flex; flex-direction: column; }
.pv-head { display: flex; align-items: center; gap: 12px; }
.pv-title { flex: 1; min-width: 0; }
.pv-name { font-weight: 600; display: flex; align-items: center; gap: 6px; }
.pv-disabled-tag { font-size: 11px; line-height: 16px; }
.pv-owner-tag { font-size: 11px; line-height: 16px; }
.pv-url {
  font-size: 12px; color: var(--app-text3, var(--app-text3));
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-models { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 4px; }
.pv-type-tag { font-size: 11px; line-height: 20px; margin-inline-end: 0; }
/* 单元格内一组 tag：flex 换行 + gap，纵向不压叠 */
.pv-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.pv-none { color: var(--app-text3, var(--app-text3)); font-size: 12px; }
.pv-remark { margin-top: 6px; font-size: 12px; color: var(--app-text3, var(--app-text3)); }
.pv-actions { margin-top: auto; padding-top: 10px; display: flex; gap: 4px; }
.pv-empty { text-align: center; padding: 40px 20px; }
.pv-empty-title { font-weight: 600; margin-bottom: 6px; }
.pv-empty-desc { color: var(--app-text3, var(--app-text3)); font-size: 13px; max-width: 520px; margin: 0 auto; }
.pv-hint { font-size: 12px; color: var(--app-text3, var(--app-text3)); }
/* ==================== 额度不足标记与被引用清单 ====================
   样式一律走 --app-* token，亮/暗主题自动跟随（暗色下 --app-danger-weak 是深棕底而非浅粉）。 */
.pv-quota-tag { font-size: 11px; line-height: 18px; margin-inline-end: 0; }
.pv-quota-bar {
  margin-top: 8px; padding: 7px 9px;
  display: flex; align-items: center; gap: 8px;
  border: 1px solid var(--app-danger-border); border-radius: 8px;
  background: var(--app-danger-weak);
}
.pv-quota-msg {
  flex: 1; min-width: 0; font-size: 12px; color: var(--app-danger-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 停用后仍有人在用的常驻提醒条：琥珀档（额度不足是「坏了」=红，这一条是「你停用完了但没改干净」） */
.pv-refs-bar {
  margin-top: 8px; padding: 7px 9px;
  display: flex; align-items: center; gap: 8px;
  border: 1px solid var(--app-warn-border); border-radius: 8px;
  background: var(--app-warn-weak);
}
.pv-refs-msg {
  flex: 1; min-width: 0; font-size: 12px; color: var(--app-warn-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-refs-error {
  padding: 14px 0; text-align: center; font-size: 13px; color: var(--app-danger-text);
}
.pv-refs-head { font-size: 13px; color: var(--app-text2, var(--app-text2)); margin-bottom: 10px; }
.pv-refs-empty {
  padding: 18px 0; text-align: center; font-size: 13px;
  color: var(--app-text3, var(--app-text3));
}
.pv-refs-list { max-height: 46vh; overflow-y: auto; display: flex; flex-direction: column; gap: 6px; }
.pv-ref-item {
  padding: 8px 10px; border: 1px solid var(--app-border, var(--app-border));
  border-radius: 8px; background: var(--app-panel-2);
  display: grid; grid-template-columns: 1fr auto; align-items: center;
  column-gap: 10px; row-gap: 2px;
}
.pv-ref-main { display: flex; align-items: center; gap: 6px; min-width: 0; }
.pv-ref-kind { font-size: 11px; line-height: 18px; margin-inline-end: 0; flex: none; }
.pv-ref-name {
  font-size: 13px; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-ref-hint {
  grid-column: 1; font-size: 11px; color: var(--app-text3, var(--app-text3));
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-ref-go { grid-column: 2; grid-row: 1 / span 2; flex: none; }
.pv-ref-note {
  grid-column: 2; grid-row: 1 / span 2; font-size: 11px; color: var(--app-text3, var(--app-text3));
}
/* 弹窗底部的反悔出口：与上面的清单分开，避免读成「这也是要改的一处」 */
.pv-refs-foot {
  margin-top: 12px; padding-top: 10px; display: flex; align-items: center; gap: 8px;
  border-top: 1px solid var(--app-border, var(--app-border));
}
.pv-refs-foot button[disabled] { opacity: .55; cursor: not-allowed; }
.pv-refs-foot-hint { font-size: 11px; color: var(--app-text3, var(--app-text3)); }
/* API Key 的掩码与眼睛按钮：掩码靠 CSS，浏览器就不会把它识别成密码框 */
.pv-secret.is-masked :deep(input) { -webkit-text-security: disc; }
.pv-secret-eye { display: inline-flex; color: var(--app-text3, var(--app-text3)); cursor: pointer; }
.pv-secret-eye:hover { color: var(--app-text2, var(--app-text2)); }
.pv-icon-picker {
  display: flex; flex-wrap: wrap; gap: 4px;
  max-height: 132px; overflow-y: auto; padding: 2px;
}
.pv-icon-cell {
  width: 30px; height: 30px; border-radius: 6px;
  border: 1px solid transparent; background: transparent;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer;
}
.pv-icon-cell:hover { background: #f0f2f5; }
.pv-icon-cell.active { border-color: var(--app-accent); background: #e6f4ff; }
/* ==================== 新建/编辑弹窗：品牌快捷区 / 图标弱化行 / footer ==================== */
/* 品牌快捷区：弹窗第一入口，点一下自动填名称、图标与网关地址（弱底弱框，不抢必填字段的视线） */
.pv-brand-zone {
  margin-bottom: 16px; padding: 10px 12px;
  border-radius: 8px; background: var(--app-panel-2);
}
.pv-brand-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.pv-brand-label { font-size: 13px; font-weight: 600; color: var(--app-text); }
.pv-brand-hint { font-size: 12px; color: var(--app-text3); }
.pv-brand-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.pv-brand-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 12px 3px 6px; border-radius: 999px;
  border: 1px solid var(--app-border); background: var(--app-panel);
  color: var(--app-text); font-size: 12px; cursor: pointer;
}
.pv-brand-chip:hover { border-color: var(--app-accent); }
.pv-brand-chip.on { border-color: var(--app-accent); color: var(--app-accent); background: var(--app-accent-weak); }
.pv-brand-chip.ghost { padding: 3px 12px; background: transparent; color: var(--app-text3); }
/* 图标弱化成一行：虚线框示意「这是可选项」，左侧预览即当前生效图标 */
.pv-icon-row {
  display: flex; align-items: center; gap: 8px;
  padding: 5px 10px; border: 1px dashed var(--app-border); border-radius: 8px;
}
.pv-icon-row-text {
  flex: 1; min-width: 0; font-size: 12px; color: var(--app-text3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* footer：测试连接贴左与保存并列，结果就地显示（超长省略，title 看全文） */
.pv-edit-foot { display: flex; align-items: center; gap: 8px; }
.pv-edit-foot > span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-edit-foot-acts { margin-left: auto; display: flex; gap: 8px; flex: none; }
.pv-test-ok { color: var(--app-ok, var(--app-ok)); font-size: 12px; margin-left: 8px; }
.pv-test-err { color: var(--app-danger); font-size: 12px; margin-left: 8px; }
.pv-row-actions { display: flex; align-items: center; gap: 10px; white-space: nowrap; }
.app-link-btn.t-ok { color: var(--app-ok, var(--app-ok)); }
.app-link-btn.t-bad { color: var(--app-danger); }
.pv-probe-chip.ok { color: var(--app-ok, var(--app-ok)); background: #f6ffed; }
.pv-probe-chip.bad { color: var(--app-danger); background: #fff1f0; }
.pv-advanced { margin: 0 0 8px; }
.pv-models-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.pv-candidates {
  border: 1px solid var(--app-code-inline-bg); border-radius: 8px;
  padding: 8px 10px; margin-bottom: 10px;
}
.pv-candidates-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 13px; }
.pv-candidate-search { width: 220px; }
.pv-candidate-list { max-height: 240px; overflow-y: auto; }
.pv-candidate {
  display: flex; align-items: center; gap: 8px;
  padding: 3px 2px; font-size: 13px;
}
.pv-candidate:hover { background: var(--app-panel-2); }
.pv-candidate-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-models-footer { margin-top: 12px; display: flex; align-items: center; gap: 10px; }
.app-btn.primary { background: var(--app-accent); color: #fff; }

/* ==================== 模型管理表格速览列 ==================== */
.pm-mid { min-width: 0; }
.pm-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pm-display { font-size: 12px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pm-ctx { font-size: 12px; color: var(--app-text2); font-variant-numeric: tabular-nums; white-space: nowrap; }

/* ==================== 模型配置表单弹窗（对齐 ZCode：全宽字段 + ? 提示 + 高级折叠） ==================== */
.pm-form { padding-top: 2px; }
.pm-field { margin-bottom: 14px; }
.pm-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; }
.pm-label {
  display: flex; align-items: center; gap: 4px;
  font-size: 13px; color: var(--app-text2); margin-bottom: 6px;
}
.pm-q { color: var(--app-text3); font-size: 13px; cursor: help; }
.pm-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: -4px 0 12px; }
.pm-hint-bad { color: var(--app-danger); }
.pm-advanced { margin: 2px 0 0; }
.pm-advanced :deep(.ant-collapse-header) { padding: 8px 0; font-size: 13px; color: var(--app-text2); }
.pm-advanced :deep(.ant-collapse-content-box) { padding: 4px 0 0 !important; }
.pm-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.pm-chips :deep(.ant-tag-checkable) {
  margin: 0; padding: 1px 10px; font-size: 12px; line-height: 20px;
  border: 1px solid var(--app-border); border-radius: 6px; color: var(--app-text3);
}
.pm-chips :deep(.ant-tag-checkable-checked) {
  border-color: var(--app-accent); color: var(--app-accent); background: light-dark(#e6f4ff, #1e2636);
}
/* 思考参数映射：方言解析结果 + 档位覆盖表 */
.pm-dialect-now { font-size: 12px; color: var(--app-text3); margin-top: 6px; }
.pm-dialect-now b { color: var(--app-text2); font-weight: 500; }
.pm-dialect-warn {
  font-size: 12px; line-height: 1.6; margin: -6px 0 14px; padding: 7px 10px; border-radius: 6px;
  color: var(--app-danger-text); background: var(--app-danger-weak, light-dark(#fff2f0, #33201c));
  border: 1px solid var(--app-danger-border, light-dark(#ffccc7, #5a342a));
}
.pm-map { width: 100%; border-collapse: collapse; border: 1px solid var(--app-border); border-radius: 8px; }
.pm-map th {
  font-size: 12px; font-weight: 400; color: var(--app-text2); text-align: left;
  padding: 6px 8px; background: var(--app-panel-2); border-bottom: 1px solid var(--app-border);
}
.pm-map td { padding: 6px 8px; border-bottom: 1px solid var(--app-border); vertical-align: middle; }
.pm-map tr:last-child td { border-bottom: none; }
.pm-map-lv { font-size: 13px; color: var(--app-text); }
.pm-map-code { display: block; font-size: 11px; color: var(--app-text3); }
.pm-map-ov { font-size: 11px; color: var(--app-accent); margin-right: 4px; }
.pm-map-fl { font-size: 11px; color: var(--app-text3); }
.pm-map :deep(.ant-input-affix-wrapper-sm), .pm-map :deep(.ant-input-sm) { font-size: 12px; }
.pm-map :deep(.ant-input-number-sm) { width: 100%; font-size: 12px; }
.pm-footer { display: flex; align-items: center; }
.pm-footer-actions { margin-left: auto; display: flex; gap: 8px; }

/* ==================== 列表页骨架升级：工具栏 / 状态点 / 模型 chips / meta 行 ==================== */
/* 配置引导深链条：说明来意 + 返回出口，不占页头高度 */
.pv-setup-bar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin: 10px 20px 0; padding: 8px 12px; border-radius: 8px;
  border: 1px solid var(--app-accent-border); background: var(--app-info-weak);
  color: var(--app-text2); font-size: 12px;
}
.pv-setup-bar .app-link-btn { margin-left: auto; font-size: 12px; }
/* 页头容纳筛选与工具组后控件变多：允许提前换行（全局兜底只在 ≤768px 生效，这里更宽就折） */
.pv-page-head { flex-wrap: wrap; row-gap: 8px; }
.pv-search { width: 200px; flex: 0 1 200px; min-width: 130px; }
.pv-head-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.pv-view-on { color: var(--app-accent); background: var(--app-accent-weak); }

/* 健康状态点：颜色 + 文字双编码（暗色自适应、读屏有字），圆点只是装饰，语义在文字 */
.pv-health { display: inline-flex; align-items: center; gap: 5px; flex: none; font-size: 11px; white-space: nowrap; }
.pv-dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; flex: none; }
.pv-h-ok { color: var(--app-ok); }
.pv-h-bad { color: var(--app-danger); }
.pv-h-idle { color: var(--app-text3); }

/* 模型速览 chip：类型是 chip 内的浅色后缀，不与名称抢宽 */
.pv-model-chip {
  max-width: 100%; display: inline-flex; align-items: center; gap: 4px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-chip-type { font-style: normal; font-size: 10px; color: var(--app-text3); flex: none; }
.pv-chip-more { flex: none; }

/* meta 行沉到卡片底部：margin-top:auto 从 .pv-actions 挪到 meta，虚线正好落在小字与操作之间 */
.pv-meta { margin-top: auto; padding-top: 10px; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; font-size: 11px; color: var(--app-text3); }
.pv-meta-sep { color: var(--app-border); }
.pv-actions { margin-top: 0; border-top: 1px dashed var(--app-border); }
.pv-more-btn { display: inline-flex; align-items: center; }

/* 首屏骨架卡：轮廓与真卡一致，数据到位时不跳版 */
.pv-skeleton { min-height: 150px; }

/* 表格视图单元 */
.pv-tp-prov { display: flex; align-items: center; gap: 8px; min-width: 0; }
.pv-tp-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-tp-count { margin-right: 6px; font-variant-numeric: tabular-nums; }
.pv-tp-types { font-size: 11px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 引用弹窗的批量替换条：压在清单上方，与逐条「去修改」分区（accent 弱底提示这是「一次做完」的快捷路） */
.pv-replace-bar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-bottom: 10px; padding: 8px 10px; border-radius: 8px;
  border: 1px solid var(--app-accent); background: var(--app-accent-weak);
}
.pv-replace-lead { font-size: 12px; color: var(--app-text2); }
.pv-replace-lead b { color: var(--app-text); }
.pv-replace-select { width: 200px; }

/* 模型矩阵：粘性表头 + 按 modelId 分组，同源 ≥2 家整组弱底高亮 */
.pmx-list { max-height: 60vh; overflow-y: auto; }
.pmx-row {
  display: grid; grid-template-columns: 1.4fr .7fr 1.3fr .7fr .7fr .7fr .8fr;
  align-items: center; gap: 8px; padding: 5px 10px; font-size: 12px;
}
.pmx-head-row { position: sticky; top: 0; z-index: 1; background: var(--app-panel); color: var(--app-text2); font-size: 11px; border-bottom: 1px solid var(--app-border); }
.pmx-group { border-bottom: 1px solid var(--app-border); }
.pmx-group:last-child { border-bottom: none; }
.pmx-group.pmx-same { background: var(--app-accent-weak); }
.pmx-group-head { display: flex; align-items: center; gap: 8px; padding: 7px 10px 3px; }
.pmx-model { font-weight: 600; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pmx-prov { display: flex; align-items: center; gap: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pmx-off { font-style: normal; font-size: 10px; color: var(--app-text3); border: 1px solid var(--app-border); border-radius: 4px; padding: 0 3px; flex: none; }
.pmx-num { font-variant-numeric: tabular-nums; color: var(--app-text2); white-space: nowrap; }
.pmx-empty { padding: 28px 0; text-align: center; font-size: 13px; color: var(--app-text3); }
</style>
