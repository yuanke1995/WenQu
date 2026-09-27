<template>
  <div class="app-page">
    <!-- ==================== 列表视图 ==================== -->
    <template v-if="!editing">
      <div class="app-page-head">
        <h1 class="app-page-title">智能体</h1>
        <span class="ap-count">共 {{ agents.length }} 个</span>
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
              <article v-for="a in sec.members" :key="sec.key + '-' + a.id" class="ap-card" @click="openEdit(a)">
                <div class="ap-card-head">
                  <span class="ap-avatar"><robot-outlined /></span>
                  <span class="ap-name" :title="a.name">{{ a.name }}</span>
                  <span v-if="isDefault(a) || isBuiltin(a)" class="ap-card-tags">
                    <span v-if="isDefault(a)" class="ap-tag-default">默认</span>
                    <span v-if="isBuiltin(a)" class="ap-tag-builtin" title="系统内置，不可删除">内置</span>
                  </span>
                </div>
                <p class="ap-desc" :title="a.description || ''">{{ a.description || '未填写描述' }}</p>
                <div class="ap-chips">
                  <span class="ap-chip">{{ scopeText(a) }}</span>
                  <span v-for="c in capsForcedOn(a)" :key="c" class="ap-chip ap-chip-on">{{ c }}</span>
                  <span v-if="scopeLabel(a)" class="ap-chip ap-chip-warn" title="已限制共享范围，点「共享」查看或修改">{{ scopeLabel(a) }}</span>
                </div>
                <div class="ap-card-foot">
                  <button class="app-link-btn" @click.stop="openEdit(a)">配置</button>
                  <button class="app-link-btn" @click.stop="openShare(a)">共享</button>
                  <button class="app-link-btn" @click.stop="openPublish(a)">发布</button>
                  <!-- 「设为默认」是全局动作（影响所有人下拉的预选），后端仅管理员放行，故对普通用户不显示 -->
                  <button v-if="!isSub(a) && !isDefault(a) && isAdmin" class="app-link-btn" @click.stop="doSetDefault(a.id)">设为默认</button>
                  <!-- 内置智能体不提供删除入口（后端也会拒绝），避免出现"点了报错"的死路 -->
                  <a-popconfirm v-if="!isBuiltin(a)" title="删除该智能体？对话页将不再可选" ok-text="删除" cancel-text="取消" @confirm="doDelete(a.id)">
                    <button class="app-link-btn danger" @click.stop>删除</button>
                  </a-popconfirm>
                  <span v-else class="ap-builtin-hint">系统内置</span>
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
        <h1 class="app-page-title">{{ editingId ? '配置智能体' : '新建智能体' }}</h1>
        <span v-if="editingId" class="ap-count">{{ form.name || '未命名' }}</span>
        <div class="ap-head-r">
          <button class="app-btn ghost small" @click="closeEdit">取消</button>
          <button class="app-btn small" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>

      <div class="app-page-body">
        <!-- 生效摘要：随表单实时变化，改完一眼知道最终结果 -->
        <div class="ap-summary">
          <span class="ap-summary-avatar"><robot-outlined /></span>
          <span class="ap-summary-name">{{ form.name || '未命名智能体' }}</span>
          <span class="ap-summary-sep">·</span>
          <span class="ap-summary-item">{{ summaryScope }}</span>
          <span class="ap-summary-sep">·</span>
          <span class="ap-summary-item" :class="{ 'is-accent': capsTouched }">{{ summaryCaps }}</span>
        </div>

        <a-form layout="vertical" class="ap-form">
          <section class="app-card">
            <h2 class="app-card-title"><idcard-outlined class="ap-sec-ic" />身份</h2>
            <p class="ap-block-hint">对话页下拉里展示的就是名称与描述，写清楚它适合什么场景。</p>
            <p class="ap-block-hint" style="margin:0 0 8px">
              描述还是「自动派遣」的路由依据：用户开启自动派遣时，系统按名称+描述把每条问题派给最合适的智能体——
              各智能体的职责要互不重叠，重叠会导致派错。
            </p>
            <a-form-item label="用途">
              <a-radio-group v-model:value="form.isSubagent">
                <a-radio-button :value="0">主智能体</a-radio-button>
                <a-radio-button :value="1">子智能体</a-radio-button>
              </a-radio-group>
              <div class="ap-block-hint" style="margin: 6px 0 0">
                主智能体可在对话页直接选用；子智能体不能直接选用，只能被主智能体委派去查资料。
              </div>
            </a-form-item>
            <a-form-item label="名称" required>
              <a-input v-model:value="form.name" :maxlength="200" placeholder="如：合同审查助手 / 运维排障 / 产品 FAQ" />
            </a-form-item>
            <a-form-item label="描述" style="margin-bottom:0">
              <a-textarea v-model:value="form.description" :maxlength="500" :rows="2"
                          placeholder="写清职责范围与典型问题（如：负责《操作手册》的界面操作与表单填写问题），自动派遣将按描述把用户问题路由到本智能体" />
            </a-form-item>
          </section>

          <section class="app-card">
            <h2 class="app-card-title"><thunderbolt-outlined class="ap-sec-ic" />提示词</h2>
            <p class="ap-block-hint">智能体不再绑定聊天模型：回答用哪套模型由用户在对话页选择或个人设置默认。</p>
            <a-form-item label="系统提示词" style="margin-bottom:0">
              <a-textarea v-model:value="form.systemPrompt" :rows="6"
                          placeholder="填写后完全替换全局系统提示词；留空沿用全局" />
            </a-form-item>
          </section>

          <section class="app-card">
            <h2 class="app-card-title"><database-outlined class="ap-sec-ic" />知识库范围</h2>
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
            <div class="ap-cap" style="border-top:1px dashed var(--app-line,#e5e6eb);margin-top:10px;padding-top:12px">
              <span class="ap-cap-ic"><safety-outlined /></span>
              <div class="ap-cap-l">
                <div class="ap-cap-name">工具执行确认</div>
                <div class="ap-cap-desc">
                  针对「沙盒执行」与「MCP 外部工具」这两类有副作用的工具：自动执行=模型直接调用；
                  执行前确认=每次调用先暂停等你批准（拒绝/超时后模型会收到未执行提示继续回答）；
                  禁用=不给模型这两类工具。游客分享会话本就不暴露它们。
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
                  达到上限后模型会直接给出最终回答。留空跟随全局（默认 15），0=不限制。
                </div>
              </div>
              <a-input-number v-model:value="form.maxToolSteps" :min="0" :max="50" :step="1" size="small"
                              style="width:120px" placeholder="全局 15" />
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
          </section>

          <section class="app-card" v-if="!form.isSubagent">
            <h2 class="app-card-title"><control-outlined class="ap-sec-ic" />检索参数</h2>
            <p class="ap-block-hint">
              留空即继承「系统设置 → 检索设置」；只填需要为这个智能体单独调整的项
              （例如法律类助手提高相似度阈值保精度、操作手册助手放宽阈值保召回）。
            </p>
            <div class="qp-grid">
              <label v-for="f in QP_FIELDS" :key="f.key" class="qp-item">
                <span class="qp-label">{{ f.label }}</span>
                <a-input v-model:value="form.qp[f.key]" :placeholder="f.ph" allow-clear />
              </label>
              <label class="qp-item">
                <span class="qp-label">重排服务</span>
                <a-segmented v-model:value="form.qpRerank"
                             :options="[{ label: '跟随全局', value: 'inherit' }, { label: '开启', value: 'on' }, { label: '关闭', value: 'off' }]" />
              </label>
            </div>
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

    <!-- 公开分享（/s/{token} 免登录对话 + iframe 嵌入） -->
    <a-modal v-model:open="pubVisible" title="公开发布" :width="560" :footer="null">
      <div class="pub-form">
        <div class="pub-row">
          <a-switch v-model:checked="pubEnabled" @change="savePublish" />
          <span class="pub-row-t">{{ pubEnabled ? '已发布：拿到链接的人可以免登录与该智能体对话' : '已停用：链接暂不可访问（token 保留，重新开启即恢复）' }}</span>
        </div>
        <div class="pub-row">
          <span class="pub-label">游客对话模型</span>
          <ModelSelect v-model="pubModelRef" type="chat" width="100%" inherit-label="跟随我的个人默认模型" />
          <div class="pub-hint">只能选择你自己可用的模型（平台级或个人级）；游客检索知识库按你的可见范围执行</div>
        </div>
        <template v-if="pubToken">
          <div class="pub-row">
            <span class="pub-label">分享链接</span>
            <div class="pub-copy-row">
              <a-input :value="shareUrl" readonly size="small" />
              <button class="app-btn ghost small" @click="copyText(shareUrl, '链接已复制')">复制</button>
              <a :href="shareUrl" target="_blank" rel="noopener" class="app-link-btn">打开</a>
            </div>
          </div>
          <div class="pub-row">
            <span class="pub-label">iframe 嵌入</span>
            <a-textarea :value="iframeSnippet" readonly :rows="3" class="pub-iframe" />
            <button class="app-btn ghost small" style="margin-top:6px" @click="copyText(iframeSnippet, '嵌入代码已复制')">复制嵌入代码</button>
            <div class="pub-hint">粘贴到任意网页；游客能力收窄：沙盒 / 产物 / MCP / 技能执行不对访客暴露</div>
          </div>
          <div class="pub-row">
            <a-popconfirm title="撤销后链接立即失效，重新发布会生成新链接，确定撤销？" @confirm="doRevoke">
              <button class="app-link-btn danger">撤销分享</button>
            </a-popconfirm>
          </div>
        </template>
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
          <!-- 主智能体列 -->
          <g v-for="a in topoData.mains" :key="'m' + a.id" class="ap-node" :class="nodeCls('m' + a.id)"
             @mouseenter="topoHover = 'm' + a.id" @mouseleave="topoHover = ''" @click="openEdit(a); topoOpen = false">
            <rect :x="MAIN_X" :y="topoData.mainPos[String(a.id)]" :width="TOPO.nodeW" :height="TOPO.rowH" rx="8" class="ap-nrect main" />
            <text :x="MAIN_X + 12" :y="topoData.mainPos[String(a.id)] + 22" class="ap-nname">{{ trunc(a.name, MAIN_NAME) }}</text>
            <text :x="MAIN_X + 12" :y="topoData.mainPos[String(a.id)] + 40" class="ap-ndesc">{{ trunc(a.description || '未填写描述', MAIN_LINE) }}</text>
            <text :x="MAIN_X + 12" :y="topoData.mainPos[String(a.id)] + 56" class="ap-nmeta">
              {{ subCountOf(a) ? `委派 ${subCountOf(a)} 个子智能体` : '未委派子智能体' }}
            </text>
          </g>
          <!-- 子智能体列（无人委派 = 灰虚线框） -->
          <g v-for="a in topoData.subs" :key="'s' + a.id" class="ap-node" :class="nodeCls('s' + a.id)"
             @mouseenter="topoHover = 's' + a.id" @mouseleave="topoHover = ''" @click="openEdit(a); topoOpen = false">
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
import { message } from 'ant-design-vue'
import {
  ArrowLeftOutlined, ReloadOutlined, SearchOutlined, RobotOutlined, IdcardOutlined,
  ThunderboltOutlined, DatabaseOutlined, ControlOutlined, StarOutlined, ApartmentOutlined,
  FileSearchOutlined, CalculatorOutlined, FileDoneOutlined, AppstoreOutlined, ApiOutlined,
  SafetyOutlined
} from '@ant-design/icons-vue'
import { listAgents, createAgent, updateAgent, deleteAgent, setAgentDefault, listKnowledgeBases, getConfig,
         listSkills, getMcpStatus, listSubAgents, updateAgentShare,
         getAgentPublish, publishAgent, revokeAgentPublish } from '../api'
import ShareScopeModal from './ShareScopeModal.vue'
import ProviderIcon from '../components/ProviderIcon.vue'
import ModelSelect from '../components/ModelSelect.vue'
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
  { key: 'toolSkill', label: '技能 Skills', desc: '限定用哪几个技能（内置技能对所有人按名字生效；我的技能只对我自己生效）', icon: AppstoreOutlined,
    kind: 'list', modeKey: 'skillMode', listKey: 'skills', optionsKey: 'skillOptions',
    gate: ['tool', 'enabled'] },
  { key: 'toolMcp', label: 'MCP 外部工具', desc: '限定连哪几个 MCP 服务（MCP 归个人：只对我自己生效）', icon: ApiOutlined,
    kind: 'list', modeKey: 'mcpMode', listKey: 'mcps', optionsKey: 'mcpOptions',
    gate: ['tool', 'enabled'] }
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
// 多实例能力：跟随全局 / 不使用 / 指定（选「指定」才展开具体项多选）
const SEG_MULTI = [
  { label: '跟随全局', value: 'inherit' },
  { label: '不使用', value: 'none' },
  { label: '指定', value: 'pick' }
]
// 内置工具可选项（value 与后端 BuiltinTools 的 @Tool 方法名一致，按名匹配注册）
const BUILTIN_TOOL_OPTIONS = [
  { value: 'calculate', label: '算术计算' },
  { value: 'currentDateTime', label: '当前日期时间' },
  { value: 'daysBetween', label: '日期相差天数' }
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
const OPTION_REFS = { builtinOptions, skillOptions, mcpOptions }
const optionsOf = c => OPTION_REFS[c.optionsKey]?.value || []

const editing = ref(false)
const editingId = ref('')
const scopeMode = ref('all')
const blankForm = () => ({
  name: '', description: '', systemPrompt: '', knowledgeBaseIds: [], isDefault: false,
  // 开关型：'' = 跟随全局 / '1' = 开启 / '0' = 关闭
  toolKnowledge: '', toolBuiltin: '', toolSkill: '', toolArtifact: '', toolMcp: '',
  // 有副作用工具（沙盒/MCP）执行审批：auto=自动执行 ask=执行前确认 off=禁用
  toolApprovalMode: 'auto',
  // 单轮工具调用步数上限：null=跟随全局（agent.maxToolSteps，默认 15）；0=不限制
  maxToolSteps: null,
  // 多实例能力：模式（inherit/none/pick）+ 选「指定」时的具体项
  builtinMode: 'inherit', builtinTools: [],
  skillMode: 'inherit', skills: [],
  mcpMode: 'inherit', mcps: [],
  // 新增时必须给出默认值：save() 会直接读这两个字段，缺失会让整个保存动作抛错
  isSubagent: 0, subAgentIds: [],
  // 检索参数覆盖：留空 = 继承全局「系统设置 → 检索设置」；非空的项才写入 queryParams
  qp: blankQp(), qpRerank: 'inherit'
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
  if (a.knowledgeDisabled === 1 || a.knowledgeDisabled === true) return '不使用知识库'
  const n = String(a.knowledgeBaseIds || '').split(',').filter(Boolean).length
  if (!n) return '全部知识库'
  return n === 1 ? '限 1 个知识库' : `限 ${n} 个知识库`
}

// ==================== 委派编排视图（拓扑：主智能体 → 子智能体委派关系，纯前端渲染） ====================
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
// 卡片上的共享范围标记：仅非全员时显示（全员=默认，不显示以免噪音）
function scopeLabel (a) {
  if (!a.shareConfig || !String(a.shareConfig).trim()) return ''
  let parsed = null
  try { parsed = JSON.parse(a.shareConfig) } catch (e) { return '' }
  const r = (parsed && parsed.read_scope) || {}
  const lvl = r.access_level || 'global'
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
const pubEnabled = ref(false)
const pubModelRef = ref('')
const pubToken = ref('')
const shareUrl = computed(() => pubToken.value ? window.location.origin + '/s/' + pubToken.value : '')
const iframeSnippet = computed(() => shareUrl.value
  ? `<iframe src="${shareUrl.value}?embed=1" style="width:420px;height:640px;border:1px solid #e5e6eb;border-radius:12px" title="AI 助手"></iframe>`
  : '')

async function openPublish (a) {
  pubAgentId.value = a.id
  pubVisible.value = true
  pubToken.value = ''
  pubEnabled.value = false
  pubModelRef.value = ''
  try {
    const r = await getAgentPublish(a.id)
    if (r && r.success !== false && r.data) {
      pubEnabled.value = !!r.data.enabled
      pubModelRef.value = r.data.modelRef || ''
      pubToken.value = r.data.token || ''
    }
  } catch (e) { message.error(e.message || '分享配置加载失败') }
}

async function savePublish () {
  try {
    const r = await publishAgent(pubAgentId.value, { enabled: pubEnabled.value, modelRef: pubModelRef.value || '' })
    if (r && r.success !== false && r.data) {
      pubToken.value = r.data.token || ''
      message.success(pubEnabled.value ? '已发布，链接可访问' : '已停用')
    } else message.error(r?.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
}

async function doRevoke () {
  try {
    const r = await revokeAgentPublish(pubAgentId.value)
    if (r && r.success !== false) {
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
    const [ar, dr, sr, mr, xr] = await Promise.all([
      listAgents(), listKnowledgeBases(), listSkills(), getMcpStatus(), listSubAgents()
    ])
    if (ar.success && ar.data) agents.value = ar.data
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
    knowledgeBaseIds: kbs,
    isDefault: isDefault(a),
    toolKnowledge: triStr(a.toolKnowledge),
    toolBuiltin: triStr(a.toolBuiltin),
    toolSkill: triStr(a.toolSkill),
    toolArtifact: triStr(a.toolArtifact),
    toolMcp: triStr(a.toolMcp),
    toolApprovalMode: a.toolApprovalMode || 'auto',
    maxToolSteps: a.maxToolSteps == null ? null : Number(a.maxToolSteps),
    builtinMode: modeOf(a.toolBuiltin), builtinTools: splitList(a.builtinTools),
    skillMode: modeOf(a.toolSkill), skills: splitList(a.skills),
    mcpMode: modeOf(a.toolMcp), mcps: splitList(a.mcps),
    isSubagent: (a.isSubagent === 1 || a.isSubagent === true) ? 1 : 0,
    subAgentIds: splitList(a.subAgentIds),
    ...(() => { const r = parseQueryParams(a.queryParams); return { qp: r.qp, qpRerank: r.rr } })()
  }
  // 三档：不使用知识库 > 指定知识库（选了库）> 全部知识库
  scopeMode.value = (a.knowledgeDisabled === 1 || a.knowledgeDisabled === true)
    ? 'none' : (kbs.length ? 'pick' : 'all')
  editing.value = true
}
const closeEdit = () => { editing.value = false }

/** 三态转换：'' → null（跟随全局）；'1' → 1；'0' → 0 */
const tri = v => (v === '' || v == null ? null : Number(v))

const save = async () => {
  const f = form.value
  if (!f.name.trim()) { message.warning('请填写名称'); return }
  const payload = {
    name: f.name.trim(),
    description: f.description.trim(),
    systemPrompt: f.systemPrompt,
    // 「全部知识库」时清空（空 → 后端存 null → 不限制）；「指定知识库」时存逗号串；
    // 「不使用知识库」时置 knowledgeDisabled=1 并清空（两者互斥，后端以开关为准）
    knowledgeBaseIds: scopeMode.value === 'pick' ? (f.knowledgeBaseIds || []).join(',') : '',
    knowledgeDisabled: scopeMode.value === 'none' ? 1 : 0,
    toolKnowledge: tri(f.toolKnowledge),
    toolArtifact: tri(f.toolArtifact),
    toolApprovalMode: f.toolApprovalMode || 'auto',
    maxToolSteps: f.maxToolSteps == null || f.maxToolSteps === '' ? null : Number(f.maxToolSteps),
    isDefault: f.isDefault ? 1 : 0,
    isSubagent: f.isSubagent ? 1 : 0,
    // 子智能体没有委派对象；主智能体一个都没选 → 空串（后端归一为 null → 编排走多视角策略）
    subAgentIds: f.isSubagent ? null : (f.subAgentIds || []).join(','),
    // 检索参数覆盖：留空项不写入 → 继承全局；全空 → 空串 → 后端存 null
    queryParams: buildQueryParams(f.qp, f.qpRerank)
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
.ap-count { font-size: 12px; color: var(--app-text3); }
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
  background: #f1f3f5; color: var(--app-text2);
}

/* 卡片列表 */
.ap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.ap-card {
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 12px;
  padding: 12px 14px; display: flex; flex-direction: column; gap: 9px; cursor: pointer;
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.ap-card:hover {
  border-color: #bcd0f7;
  box-shadow: 0 6px 18px -10px rgba(46, 107, 230, .35);
}
.ap-card-head { display: flex; align-items: center; gap: 8px; }
.ap-avatar {
  width: 24px; height: 24px; border-radius: 7px; flex: none; font-size: 12px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-accent-weak); color: var(--app-accent);
}
.ap-name { font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-card-tags { margin-left: auto; display: inline-flex; align-items: center; gap: 4px; flex: none; }
.ap-tag-default {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: #eaf5ec; color: var(--app-ok);
}
.ap-tag-builtin {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px;
  background: #e8eefc; color: var(--app-accent);
}
.ap-builtin-hint { font-size: 11px; color: var(--app-text3); padding: 0 4px; }
.ap-desc {
  font-size: 12px; color: var(--app-text2); line-height: 1.6; margin: 0; min-height: 32px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.ap-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.ap-chip {
  font-size: 11px; line-height: 1; padding: 4px 7px; border-radius: 6px;
  background: #f1f3f5; color: var(--app-text2); white-space: nowrap;
}
.ap-chip-on { background: var(--app-accent-weak); color: var(--app-accent); }
.ap-chip-warn { background: #faf3e6; color: #a3691b; }
.ap-card-foot {
  display: flex; align-items: center; justify-content: flex-end; gap: 2px;
  border-top: 1px dashed var(--app-border); padding-top: 6px; margin-top: auto;
}

/* 配置视图 */
.ap-summary {
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px; max-width: 820px;
  margin-bottom: 12px; padding: 10px 14px; border-radius: 12px;
  background: linear-gradient(0deg, var(--app-accent-weak), var(--app-accent-weak));
  border: 1px solid #dbe6fb; font-size: 12px; color: var(--app-text2);
}
.ap-summary-avatar {
  width: 22px; height: 22px; border-radius: 6px; flex: none; font-size: 11px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel); color: var(--app-accent);
}
.ap-summary-name { font-size: 13px; font-weight: 500; color: var(--app-text); }
.ap-summary-sep { color: var(--app-text3); }
.ap-summary-item { color: var(--app-text2); }
.ap-summary-item.is-accent { color: var(--app-accent); }

.ap-form { display: flex; flex-direction: column; gap: 12px; max-width: 820px; }
.ap-form :deep(.ant-form-item) { margin-bottom: 12px; }
.ap-block-hint { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: -4px 0 12px; }
/* 检索参数覆盖：两列网格，留空=继承全局 */
.qp-grid { display: grid; grid-template-columns: repeat(2, minmax(220px, 1fr)); gap: 10px 18px; }
.qp-item { display: flex; align-items: center; gap: 10px; }
.qp-label { flex: none; width: 88px; font-size: 12.5px; color: var(--app-text2); }
.ap-pick { margin-top: 12px; }
.ap-sec-ic { font-size: 13px; color: var(--app-text3); }

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
  background: #f1f3f5; color: var(--app-text2); transition: background .15s, color .15s;
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
.ap-cap-global { color: #b6bdc7; }
/* 公开发布弹窗 */
.pub-form { display: flex; flex-direction: column; gap: 14px; padding-top: 4px; }
.pub-row { display: flex; flex-direction: column; gap: 6px; }
.pub-row:first-child { flex-direction: row; align-items: center; gap: 10px; }
.pub-row-t { font-size: 13px; color: var(--app-text2, #4e5460); }
.pub-label { font-size: 12px; font-weight: 600; color: var(--app-text2, #4e5460); }
.pub-copy-row { display: flex; gap: 8px; align-items: center; }
.pub-iframe { font-family: "SF Mono", Menlo, monospace; font-size: 11.5px; }
.pub-hint { font-size: 12px; color: var(--app-text3, #8f959e); }

/* ==================== 委派编排视图（拓扑） ==================== */
.ap-topo-legend { display: flex; flex-wrap: wrap; gap: 14px; align-items: center; font-size: 12px; color: var(--app-text3); margin-bottom: 10px; }
.ap-lg { display: inline-flex; align-items: center; gap: 6px; }
.ap-lg-dot { width: 10px; height: 10px; border-radius: 3px; display: inline-block; flex: none; }
.ap-lg-dot.main { background: #fff; border: 1.5px solid var(--app-accent, #4a7dff); }
.ap-lg-dot.sub { background: #eef3ff; border: 1.5px solid var(--app-accent, #4a7dff); }
.ap-topo-warns { margin-bottom: 10px; }
.ap-topo-warn { font-size: 12px; color: var(--app-danger); padding: 2px 0; }
.ap-topo-empty { padding: 28px 0; text-align: center; font-size: 13px; color: var(--app-text3); }
.ap-topo-scroll { display: flex; justify-content: center; overflow: auto; }
.ap-topo-svg { flex: none; }
.ap-edge { fill: none; stroke: #b9bfcc; stroke-width: 1.5; opacity: .8; transition: opacity .2s, stroke-width .2s; }
.ap-edge.on { stroke: var(--app-accent, #4a7dff); stroke-width: 2; opacity: 1; }
.ap-edge.dim { opacity: .1; }
.ap-edge.miss { stroke: var(--app-danger, #e5484d); stroke-dasharray: 5 4; }
.ap-node { cursor: pointer; }
.ap-node.dim { opacity: .18; }
.ap-nrect { fill: #fff; stroke: var(--app-border, #e3e6ec); stroke-width: 1.2; }
.ap-nrect.sub { fill: #f4f7ff; }
.ap-nrect.orphan { stroke-dasharray: 5 4; stroke: #c2c7d1; }
.ap-phantom { fill: #fff; stroke: var(--app-danger, #e5484d); stroke-dasharray: 4 3; }
.ap-phantom-t { font-size: 14px; font-weight: 700; fill: var(--app-danger, #e5484d); text-anchor: middle; }
.ap-nname { font-size: 13px; font-weight: 600; fill: var(--app-text, #24292f); }
.ap-ndesc { font-size: 11px; fill: var(--app-text3, #8f959e); }
.ap-nmeta { font-size: 11px; fill: var(--app-text3, #8f959e); }
</style>
