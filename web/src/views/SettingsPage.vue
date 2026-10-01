<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">系统设置</h3>
      <span v-if="dirtyCount" class="dirty-hint">有 {{ dirtyCount }} 项已修改未保存</span>
      <button v-else class="head-hint-plain">修改后点右侧保存生效，悬停参数旁 ? 查看说明</button>
      <!-- 基础模式只显示常用项（模型服务 + 回答行为）；细粒度调参收进高级模式，避免设置页膨胀 -->
      <label class="adv-toggle" title="默认只显示常用配置；开启后显示全部调优参数">
        <a-switch v-model:checked="advMode" size="small" />
        <span>高级设置</span>
      </label>
      <button class="app-btn" style="margin-left:auto" :disabled="!dirtyCount" :class="{ dis: !dirtyCount }" @click="save">
        <save-outlined /> 保存配置{{ dirtyCount ? `（${dirtyCount} 项改动）` : '' }}
      </button>
    </div>

    <div v-if="!schemaLoaded" class="app-card" style="margin:16px 20px">
      <span class="head-hint-plain">正在加载配置字段定义…</span>
    </div>
    <div v-else class="set-body">
      <!-- 左侧分组导航（基础模式只列含常用项的分组） -->
      <nav class="set-nav">
        <span v-for="p in navPanels" :key="p.key" class="set-nav-item" :class="{ active: current === p.key }" @click="current = p.key">
          {{ groupLabel(p.key) }}
        </span>
      </nav>

      <!-- 右侧：当前分组表单 -->
      <section class="set-content">
        <a-spin :spinning="loading">
          <div class="set-panel-head">
            <h3 class="app-page-title">{{ currentPanel?.title }}</h3>
            <span v-if="!advMode && hiddenHere > 0" class="adv-hidden-hint">
              已隐藏 {{ hiddenHere }} 项高级配置（右上角「高级设置」可查看）
            </span>
            <button v-if="!NO_RESET.includes(activeFormPanel)" class="app-btn ghost" :disabled="resettingKey === activeFormPanel" @click="onResetGroup(activeFormPanel)">
              恢复本组默认
            </button>
          </div>

          <a-alert v-for="(al, ai) in (PANEL_ALERTS[current] || [])" :key="ai" :type="al.type" show-icon
                   style="margin-bottom:12px" :message="al.msg" />

          <div class="app-card set-card">
            <!-- 工具族合并入口：总开关与子工具 / 联网搜索（后续新工具族继续加页签）——
                 避免左侧导航随工具面板膨胀；页签风格与「定时维护」一致（a-tabs），
                 pane 留空只当页签条，内容在下方按 toolTab 对应的 panel 字段渲染 -->
            <a-tabs v-if="current === 'tool'" v-model:activeKey="toolTab" class="sched-tabs">
              <a-tab-pane key="tool" tab="总开关与子工具" />
              <a-tab-pane key="webSearch" tab="联网搜索" />
            </a-tabs>
            <!-- 定时维护面板内容多（参数表单 + 任务状态 + 执行日志），用页签组织避免一页滚到底。
                 页签风格与「智能体」hub 一致（a-tabs）；pane 留空只当页签条，内容在下方按 maintTab 切换 -->
            <a-tabs v-if="current === 'maintenance'" v-model:activeKey="maintTab" class="sched-tabs">
              <a-tab-pane key="config" tab="参数配置" />
              <a-tab-pane key="status" tab="运行状态" />
              <a-tab-pane key="logs" tab="执行日志" />
            </a-tabs>

            <!-- 工具清单（仅「总开关与子工具」页签显示）：运行时注册的工具全景，
                 名称与 @Tool 方法一致、开关与下方表单对应；联网搜索页签不重复展示 -->
            <div v-if="current === 'tool' && toolTab === 'tool'" class="tool-inv">
              <div class="tool-inv-head">
                <span class="cfg-sub">当前注册的工具（{{ toolInvTotal }} 个 · 名称即模型看到的工具名）</span>
                <a-tooltip title="刷新清单">
                  <button class="app-icon-btn" aria-label="刷新工具清单" :disabled="toolInvLoading" @click="loadToolInventory">
                    <reload-outlined />
                  </button>
                </a-tooltip>
              </div>
              <a-spin v-if="toolInvLoading" size="small" style="display:block;margin:14px auto" />
              <div v-else-if="toolInv.length" class="tool-inv-groups">
                <div v-for="g in toolInv" :key="g.family" class="tool-inv-group">
                  <div class="tool-inv-family">
                    <span class="tool-inv-family-name">{{ g.familyLabel }}</span>
                    <span class="tool-inv-state" :class="{ on: g.enabled }">{{ g.enabled ? '可用' : '未启用' }}</span>
                    <code v-if="g.globalKey" class="tool-inv-key">{{ g.globalKey }}</code>
                  </div>
                  <div class="tool-inv-items">
                    <a-tooltip v-for="t in g.tools" :key="t.name" :title="t.description">
                      <span class="tool-inv-chip" :class="{ sensitive: t.sensitive }">
                        {{ t.label }}<span v-if="t.sensitive" class="tool-inv-s">敏</span>
                      </span>
                    </a-tooltip>
                  </div>
                </div>
              </div>
              <div v-else class="key-dim" style="padding:6px 2px">工具清单仅管理员可见（或后端暂未注册任何工具）。</div>
            </div>

            <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }" @submit.prevent>
              <template v-if="current !== 'maintenance' || maintTab === 'config'">
                <template v-for="(blk, i) in blocksOf(activeFormPanel, !advMode)" :key="i">
                  <div v-if="blk.type === 'sub'" class="cfg-sub">{{ blk.title }}</div>

                  <!-- 常规字段（SchemaField 全量复用：类型控件/条件显隐/参数说明）。
                       技能与 MCP 的内容已迁到「智能体」页的个人 Tab，这里只剩技能的两个上下文预算参数 -->
                  <template v-else-if="blk.type === 'field'">
                    <SchemaField :field="blk.field" :form="form" :tips="TIPS" @change="onFieldChange">
                      <template v-if="probeKey(blk.field)" #extra>
                        <button class="app-btn ghost small probe-btn" :disabled="probeStates[probeKey(blk.field)].loading" @click="doProbe(probeKey(blk.field))">
                          {{ probeStates[probeKey(blk.field)].loading ? '测试中…' : '测试连接' }}
                        </button>
                        <a-tooltip v-if="probeStates[probeKey(blk.field)].result" :title="probeStates[probeKey(blk.field)].result.detail">
                          <span class="probe-chip" :class="probeStates[probeKey(blk.field)].result.available ? 'ok' : 'bad'">
                            {{ probeStates[probeKey(blk.field)].result.available ? '可达' : '不可达' }} {{ probeStates[probeKey(blk.field)].result.latencyMs }}ms
                          </span>
                        </a-tooltip>
                      </template>
                    </SchemaField>
                  </template>
                </template>
              </template>

              <!-- API Key 管理（6.5）：签发 / 列表 / 停用 / 删除 -->
              <template v-if="current === 'apiKey'">
                <!-- 工具栏：搜索 + 概览统计 + 主操作 -->
                <div class="key-bar">
                  <div class="key-bar-left">
                    <a-input v-model:value="keyKeyword" placeholder="搜索名称 / Key 前缀" allow-clear size="small" class="res-search">
                      <template #prefix><search-outlined class="res-search-ic" /></template>
                    </a-input>
                    <span class="key-stat">
                      共 <b>{{ keys.length }}</b> 个 Key · 生效中 <b>{{ activeKeyCount }}</b>
                      <span v-if="lastUsedKey" class="key-dim">· 最近使用 {{ fmtTs(lastUsedKey.lastUsedAt) }}</span>
                    </span>
                  </div>
                  <div class="key-bar-actions">
                    <a-tooltip title="刷新列表">
                      <button class="app-icon-btn" aria-label="刷新 Key 列表" :disabled="keysLoading" @click="loadKeys"><reload-outlined /></button>
                    </a-tooltip>
                    <button class="app-btn small" @click="openCreateKey">＋ 创建 API Key</button>
                  </div>
                </div>

                <!-- 空态：没有 Key 时给引导；有 Key 但搜索无匹配时提示清除搜索 -->
                <div v-if="!keys.length" class="key-empty">
                  <div class="key-empty-title">还没有 API Key</div>
                  <div class="key-empty-desc">
                    创建后，外部系统在请求头带 <code>X-Api-Key</code> 即可调用问答接口，无需平台 token。
                  </div>
                  <button class="app-btn small" @click="openCreateKey">创建第一个 API Key</button>
                </div>
                <div v-else-if="!filteredKeys.length" class="key-empty">
                  <div class="key-empty-title">没有匹配的 Key</div>
                  <div class="key-empty-desc">没有名称或前缀包含「{{ keyKeyword }}」的 Key。</div>
                  <button class="app-btn ghost small" @click="keyKeyword = ''">清除搜索</button>
                </div>

                <a-table v-else :data-source="filteredKeys" size="small" row-key="id" :pagination="false" :scroll="{ x: 900 }">
                  <a-table-column title="名称" key="name" ellipsis>
                    <template #default="{ record }">
                      <span class="key-name-wrap">
                        <span class="key-name">{{ record.name || '未命名' }}</span>
                        <span v-if="record.expired" class="app-pill err key-tag">已过期</span>
                      </span>
                    </template>
                  </a-table-column>
                  <a-table-column title="Key" key="prefix" width="170">
                    <template #default="{ record }"><span class="key-prefix">{{ record.keyPrefix }}…</span></template>
                  </a-table-column>
                  <a-table-column title="状态" key="status" width="80">
                    <template #default="{ record }">
                      <a-tooltip :title="record.expired ? '已过期，不可启用' : (record.disabled ? '已停用，点击启用' : '生效中，点击停用')">
                        <a-switch size="small" :checked="!record.disabled" :disabled="!!record.expired"
                                  :loading="keyTogglingId === record.id" @change="toggleKey(record)" />
                      </a-tooltip>
                    </template>
                  </a-table-column>
                  <a-table-column title="MCP" key="mcp" width="86">
                    <template #default="{ record }">
                      <a-tooltip :title="record.mcpEnabled ? '可访问平台级 MCP 入口 /ai/mcp（点击收回）' : '未授权 MCP 入口（点击授权）'">
                        <a-switch size="small" :checked="!!record.mcpEnabled"
                                  :loading="keyMcpTogglingId === record.id" @change="toggleKeyMcp(record)" />
                      </a-tooltip>
                    </template>
                  </a-table-column>
                  <a-table-column title="最近使用" key="lastUsed" width="150">
                    <template #default="{ record }">
                      <span :class="{ 'key-dim': !record.lastUsedAt }">{{ record.lastUsedAt ? fmtTs(record.lastUsedAt) : '从未使用' }}</span>
                    </template>
                  </a-table-column>
                  <a-table-column title="创建时间" key="created" width="150">
                    <template #default="{ record }"><span class="key-dim">{{ fmtTs(record.createTime) }}</span></template>
                  </a-table-column>
                  <a-table-column title="有效期" key="expire" width="110">
                    <template #default="{ record }"><span class="key-dim">{{ record.expireAt ? fmtDate(record.expireAt) : '长期' }}</span></template>
                  </a-table-column>
                  <a-table-column title="操作" key="act" width="150">
                    <template #default="{ record }">
                      <button class="app-link-btn" @click="openShareKey(record)">共享</button>
                      <button class="app-link-btn" @click="openRenameKey(record)">改名</button>
                      <a-popconfirm title="删除该 Key？调用方将立即失效" ok-text="删除" cancel-text="取消" @confirm="delKey(record.id)">
                        <button class="app-link-btn danger">删除</button>
                      </a-popconfirm>
                    </template>
                  </a-table-column>
                </a-table>
                <!-- 如何使用：拿到 Key 之后怎么调，比堆一段说明文字有用 -->
                <div class="key-usage">
                  <div class="fold-head" @click="usageOpen = !usageOpen">
                    <span class="fold-caret">{{ usageOpen ? '▾' : '▸' }}</span> 如何使用
                    <span class="key-dim">（调用方式与权限边界）</span>
                  </div>
                  <div v-if="usageOpen" class="key-usage-body">
                    <div class="key-usage-label">调用问答接口（curl 示例）</div>
                    <div class="key-code">
                      <code>{{ curlSample }}</code>
                      <button class="key-copy" :title="copiedSample === 'curl' ? '已复制' : '复制'"
                              @click="copySample('curl', curlSample)">
                        <check-outlined v-if="copiedSample === 'curl'" class="key-copy-ok" /><copy-outlined v-else />
                      </button>
                    </div>
                    <ul class="key-usage-list">
                      <li><code>X-Api-Key: sk-…</code> 作为访问凭据，无需平台登录令牌</li>
                      <li>会话归属由服务端判定：未带登录令牌的调用共享 anonymous 兼容池，建议调用方各自登录或按 Key 隔离使用</li>
                      <li>权限仅限问答链路（问答 / 会话 / 反馈 / 引用溯源），管理端点一律拒绝</li>
                      <li>打开「MCP」开关后，该 Key 还可作为平台级 MCP 入口 <code>/ai/mcp</code> 的凭据
                        （请求头带 <code>Authorization: Bearer sk-…</code>），供 Claude / Cursor 等客户端调用：检索知识库、提问、列出可见库与智能体</li>
                      <li>不再使用建议「停用」而非删除：停用可保留审计线索，删除记录即消失</li>
                    </ul>
                  </div>
                </div>

                <!-- MCP 调用审计（仅管理员）：外部客户端调用 /ai/mcp 两类端点的每次工具执行 -->
                <div class="key-usage">
                  <div class="fold-head" @click="toggleAudit">
                    <span class="fold-caret">{{ auditOpen ? '▾' : '▸' }}</span> MCP 调用审计
                    <span class="key-dim">（外部客户端经 MCP 端点调用平台能力的全量记录）</span>
                  </div>
                  <div v-if="auditOpen" class="key-usage-body">
                    <div class="audit-summary">
                      <template v-if="auditSummary">
                        <span>累计调用 <b>{{ auditSummary.total }}</b></span>
                        <span>成功 <b class="ok">{{ auditSummary.success }}</b></span>
                        <span>失败 <b class="bad">{{ auditSummary.failed }}</b></span>
                        <span v-if="auditTopTool">最常调用 <b>{{ auditTopTool.toolName }}</b>（{{ auditTopTool.cnt }} 次）</span>
                      </template>
                      <span v-else class="key-dim">暂无汇总</span>
                    </div>
                    <div class="audit-filter">
                      <a-select v-model:value="auditFilter.channel" size="small" style="width: 130px"
                                allow-clear placeholder="全部渠道" @change="loadAudit(true)">
                        <a-select-option value="agent">智能体端点</a-select-option>
                        <a-select-option value="platform">平台级入口</a-select-option>
                      </a-select>
                      <a-select v-model:value="auditFilter.success" size="small" style="width: 100px"
                                allow-clear placeholder="全部结果" @change="loadAudit(true)">
                        <a-select-option :value="1">成功</a-select-option>
                        <a-select-option :value="0">失败</a-select-option>
                      </a-select>
                      <a-input v-model:value="auditFilter.tool" size="small" style="width: 180px"
                               placeholder="工具名（模糊）" allow-clear @press-enter="loadAudit(true)" />
                      <button class="app-btn small" :disabled="auditLoading" @click="loadAudit(true)">查询</button>
                      <a-tooltip title="刷新">
                        <button class="app-icon-btn" aria-label="刷新审计记录" :disabled="auditLoading" @click="loadAudit(true)">
                          <reload-outlined />
                        </button>
                      </a-tooltip>
                    </div>
                    <a-table :data-source="auditRows" size="small" row-key="id" :pagination="false" :loading="auditLoading" :scroll="{ x: 820 }">
                      <a-table-column title="时间" key="time" width="130">
                        <template #default="{ record }"><span class="key-dim">{{ fmtTs(record.createdAt) }}</span></template>
                      </a-table-column>
                      <a-table-column title="渠道" key="channel" width="100">
                        <template #default="{ record }">
                          <span class="audit-chip" :class="record.channel">{{ record.channel === 'agent' ? '智能体端点' : '平台级' }}</span>
                        </template>
                      </a-table-column>
                      <a-table-column title="工具" key="tool" width="180" ellipsis>
                        <template #default="{ record }"><code class="audit-mono">{{ record.toolName }}</code></template>
                      </a-table-column>
                      <a-table-column title="凭据" key="cred" width="150" ellipsis>
                        <template #default="{ record }"><code class="audit-mono">{{ record.credentialRef || '—' }}</code></template>
                      </a-table-column>
                      <a-table-column title="归属" key="owner" width="120" ellipsis>
                        <template #default="{ record }">{{ record.ownerUid || '—' }}</template>
                      </a-table-column>
                      <a-table-column title="调用者 IP" key="ip" width="120" ellipsis>
                        <template #default="{ record }">{{ record.callerIp || '—' }}</template>
                      </a-table-column>
                      <a-table-column title="耗时" key="dur" width="90">
                        <template #default="{ record }">{{ record.durationMs }}ms</template>
                      </a-table-column>
                      <a-table-column title="结果" key="ok">
                        <template #default="{ record }">
                          <a-tooltip v-if="!record.success && record.errorMsg" :title="record.errorMsg">
                            <span class="audit-chip fail">失败</span>
                          </a-tooltip>
                          <span v-else class="audit-chip pass">成功</span>
                        </template>
                      </a-table-column>
                    </a-table>
                    <div class="audit-pager">
                      <span class="key-dim">共 {{ auditTotal }} 条</span>
                      <a-pagination size="small" :current="auditPage" :page-size="auditSize" :total="auditTotal"
                                    :show-size-changer="false" @change="onAuditPage" />
                    </div>
                  </div>
                </div>

                <!-- 创建弹窗：两步式（表单 → 明文仅此一次展示） -->
                <a-modal v-model:open="createOpen" :title="createStep === 'form' ? '创建 API Key' : 'Key 已创建'"
                         :footer="null" :width="580" :mask-closable="false" @cancel="closeCreateKey">
                  <template v-if="createStep === 'form'">
                    <a-form layout="vertical">
                      <a-form-item label="用途名称" required>
                        <a-input v-model:value="createForm.name" placeholder="如：报表系统集成 / 运维脚本 / 定时巡检"
                                 :maxlength="200" @press-enter="submitCreateKey" />
                      </a-form-item>
                      <a-form-item label="有效期">
                        <a-radio-group v-model:value="createForm.expireMode" size="small" button-style="solid">
                          <a-radio-button value="never">长期有效</a-radio-button>
                          <a-radio-button value="30">30 天</a-radio-button>
                          <a-radio-button value="90">90 天</a-radio-button>
                          <a-radio-button value="custom">自定义</a-radio-button>
                        </a-radio-group>
                        <a-date-picker v-if="createForm.expireMode === 'custom'" v-model:value="createForm.expireDate"
                                       style="margin-top:8px" placeholder="选择到期日期"
                                       :disabled-date="d => d && d.valueOf() < Date.now() - 86400000" />
                      </a-form-item>
                    </a-form>
                    <div class="key-modal-foot">
                      <button class="app-btn ghost" @click="closeCreateKey">取消</button>
                      <button class="app-btn" :disabled="keyCreating" @click="submitCreateKey">{{ keyCreating ? '创建中…' : '创建' }}</button>
                    </div>
                  </template>
                  <template v-else>
                    <div class="key-done-warn">
                      请立即复制保存——明文只显示这一次（服务端只存哈希，关闭后无法再查看）
                    </div>
                    <div class="key-done-meta">用途：<b>{{ createdKey.name }}</b></div>
                    <div class="key-done-box">
                      <code>{{ createdKey.apiKey }}</code>
                      <button class="key-copy" :title="copiedKey ? '已复制' : '复制'" @click="copyCreatedKey">
                        <check-outlined v-if="copiedKey" class="key-copy-ok" /><copy-outlined v-else />
                      </button>
                    </div>
                    <div class="key-modal-foot">
                      <button class="app-btn" @click="closeCreateKey">我已保存，关闭</button>
                    </div>
                  </template>
                </a-modal>

                <!-- 改名弹窗 -->
                <a-modal v-model:open="renameOpen" title="重命名 API Key" :footer="null" :width="460">
                  <a-form layout="vertical">
                    <a-form-item label="用途名称" required>
                      <a-input v-model:value="renameForm.name" placeholder="如：报表系统集成"
                               :maxlength="200" @press-enter="submitRenameKey" />
                    </a-form-item>
                  </a-form>
                  <div class="key-modal-foot">
                    <button class="app-btn ghost" @click="renameOpen = false">取消</button>
                    <button class="app-btn" @click="submitRenameKey">保存</button>
                  </div>
                </a-modal>

                <!-- 共享范围（公共组件：与文档 / 智能体同一套两区表单） -->
                <ShareScopeModal v-model:open="keyShareVisible" resource-label="API Key" read-verb="查看"
                                 :share-config="keyShareTarget.shareConfig" :save-fn="saveKeyShareFn" @saved="loadKeys" />
              </template>

              <!-- 定时任务·运行状态页签：ScheduleCenter 内存快照（统计随重启归零）。
                   暂停/恢复即把间隔配置写 0 / 默认值（参数在「参数配置」页签），复用既有保存链路 -->
              <template v-if="current === 'maintenance' && maintTab === 'status'">
                <div class="key-usage-body sched-pane">
                  <div class="audit-filter">
                    <span class="key-dim">共 {{ scheduleTasks.length }} 个任务 · 「暂停」= 间隔配置写 0，恢复 = 写回默认值；间隔等参数在「参数配置」页签调整</span>
                    <a-tooltip title="刷新运行状态">
                      <button class="app-icon-btn" aria-label="刷新定时任务状态" :disabled="schedLoading" @click="loadSchedule">
                        <reload-outlined />
                      </button>
                    </a-tooltip>
                  </div>
                  <a-table :data-source="scheduleTasks" size="small" row-key="name" :pagination="false"
                           :loading="schedLoading" :scroll="{ x: 1020 }">
                    <a-table-column title="任务" key="name" width="190">
                      <template #default="{ record }">
                        <a-tooltip :title="record.desc">
                          <span class="key-name">{{ record.name }}</span>
                        </a-tooltip>
                        <div v-if="record.configKey" class="sched-cfgkey"><code>{{ record.configKey }}</code></div>
                        <div v-else class="sched-cfgkey key-dim">内置节拍</div>
                      </template>
                    </a-table-column>
                    <a-table-column title="间隔" key="interval" width="90">
                      <template #default="{ record }">
                        <span v-if="record.paused" class="key-dim">—</span>
                        <span v-else>{{ fmtInterval(record.intervalMs) }}</span>
                      </template>
                    </a-table-column>
                    <a-table-column title="状态" key="state" width="80">
                      <template #default="{ record }">
                        <span v-if="record.running" class="audit-chip run">执行中</span>
                        <span v-else-if="record.paused" class="audit-chip paused">已暂停</span>
                        <span v-else class="audit-chip idle">待触发</span>
                      </template>
                    </a-table-column>
                    <a-table-column title="上次结果" key="last" width="180">
                      <template #default="{ record }">
                        <template v-if="record.lastFinishedAt">
                          <span class="audit-chip" :class="record.lastSuccess ? 'pass' : 'fail'">
                            {{ record.lastSuccess ? '成功' : '失败' }}
                          </span>
                          <a-tooltip v-if="record.lastError" :title="record.lastError">
                            <span class="sched-err">!</span>
                          </a-tooltip>
                          <div class="key-dim">{{ fmtEpoch(record.lastFinishedAt) }} · {{ fmtDuration(record.lastDurationMs) }}</div>
                        </template>
                        <span v-else class="key-dim">本轮等待中</span>
                      </template>
                    </a-table-column>
                    <a-table-column title="下次预计" key="next" width="100">
                      <template #default="{ record }">
                        <span v-if="record.paused || record.running" class="key-dim">—</span>
                        <span v-else class="key-dim">{{ fmtCountdown(record.nextDueAt, record.now) }}</span>
                      </template>
                    </a-table-column>
                    <a-table-column title="成败" key="cnt" width="76">
                      <template #default="{ record }">
                        <span class="sched-ok">{{ record.successCount }}</span>
                        <span class="key-dim"> / </span>
                        <span :class="{ 'sched-bad': record.failCount > 0 }">{{ record.failCount }}</span>
                      </template>
                    </a-table-column>
                    <a-table-column title="暂停" key="pause" width="70">
                      <template #default="{ record }">
                        <a-tooltip v-if="record.editable"
                                   :title="record.paused ? '已暂停，点击恢复默认间隔' : '暂停该任务（间隔写 0，下个节拍生效）'">
                          <a-switch size="small" :checked="!record.paused"
                                    :loading="schedToggling === record.name" @change="toggleTaskPause(record)" />
                        </a-tooltip>
                        <a-tooltip v-else title="间隔为内置节拍（无对应配置项），不可暂停">
                          <span class="key-dim">内置</span>
                        </a-tooltip>
                      </template>
                    </a-table-column>
                    <a-table-column title="操作" key="act" width="90">
                      <template #default="{ record }">
                        <button class="app-link-btn" :disabled="record.running" @click="doTrigger(record)">
                          {{ record.running ? '执行中…' : '立即执行' }}
                        </button>
                      </template>
                    </a-table-column>
                  </a-table>
                </div>
              </template>

              <!-- 定时任务·执行日志页签：c_ai_schedule_run 分页（每次执行完成落一行，超期由清理任务删除） -->
              <template v-if="current === 'maintenance' && maintTab === 'logs'">
                <div class="key-usage-body sched-pane">
                  <div class="audit-filter">
                    <a-select v-model:value="runLogFilter.taskName" size="small" style="width: 180px"
                              allow-clear placeholder="全部任务" @change="loadRunLogs(true)">
                      <a-select-option v-for="t in scheduleTasks" :key="t.name" :value="t.name">{{ t.name }}</a-select-option>
                    </a-select>
                    <a-select v-model:value="runLogFilter.success" size="small" style="width: 100px"
                              allow-clear placeholder="全部结果" @change="loadRunLogs(true)">
                      <a-select-option :value="1">成功</a-select-option>
                      <a-select-option :value="0">失败</a-select-option>
                    </a-select>
                    <button class="app-btn small" :disabled="runLogLoading" @click="loadRunLogs(true)">查询</button>
                    <a-tooltip title="刷新">
                      <button class="app-icon-btn" aria-label="刷新执行日志" :disabled="runLogLoading" @click="loadRunLogs(true)">
                        <reload-outlined />
                      </button>
                    </a-tooltip>
                    <span class="key-dim">保留 {{ runLogRetentionDays }} 天</span>
                  </div>
                  <a-table :data-source="runLogRows" size="small" row-key="id" :pagination="false"
                           :loading="runLogLoading" :scroll="{ x: 820 }">
                    <a-table-column title="开始时间" key="started" width="140">
                      <template #default="{ record }"><span class="key-dim">{{ fmtTs(record.startedAt) }}</span></template>
                    </a-table-column>
                    <a-table-column title="任务" key="task" width="170" ellipsis>
                      <template #default="{ record }">{{ record.taskName }}</template>
                    </a-table-column>
                    <a-table-column title="触发" key="trigger" width="80">
                      <template #default="{ record }">
                        <span class="key-dim">{{ { startup: '启动', auto: '周期', manual: '手动' }[record.triggerType] || record.triggerType }}</span>
                      </template>
                    </a-table-column>
                    <a-table-column title="耗时" key="dur" width="90">
                      <template #default="{ record }">{{ fmtDuration(record.durationMs) }}</template>
                    </a-table-column>
                    <a-table-column title="结果" key="ok">
                      <template #default="{ record }">
                        <a-tooltip v-if="!record.success && record.errorMsg" :title="record.errorMsg">
                          <span class="audit-chip fail">失败</span>
                        </a-tooltip>
                        <span v-else class="audit-chip pass">成功</span>
                      </template>
                    </a-table-column>
                  </a-table>
                  <div class="audit-pager">
                    <span class="key-dim">共 {{ runLogTotal }} 条</span>
                    <a-pagination size="small" :current="runLogPage" :page-size="runLogSize" :total="runLogTotal"
                                  :show-size-changer="false" @change="onRunLogPage" />
                  </div>
                </div>
              </template>

              <!-- 技能（Skills）与 MCP 的管理界面已迁到「智能体」页的个人 Tab（每人管自己的），
                   此处不再有自定义面板 -->

            </a-form>
          </div>
        </a-spin>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SaveOutlined, QuestionCircleOutlined, CopyOutlined, CheckOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { getConfig, getConfigSchema, saveConfig, resetConfig, checkKeywordEngine,
         probeConnectivity,
         listApiKeys, createApiKey, setApiKeyDisabled, setApiKeyMcp, deleteApiKey, renameApiKey, updateApiKeyShare,
         getMcpAuditLogs, getMcpAuditSummary,
         getScheduleTasks, triggerScheduleTask, getScheduleRuns,
         getToolInventory } from '../api'
import ShareScopeModal from './ShareScopeModal.vue'
import SchemaField from '../components/SchemaField.vue'
import { FIELDS, PANELS, TIPS, blocksOf, buildDefaultForm, readForm, writeForm, corePanels, hiddenFieldCount, applyServerSchema } from '../configSchema'

// 分组导航（沿用旧版锚点短名）
const NAV_LABELS = {
  chat: '智能问答模型', vision: '视觉模型', chunk: '文档解析', embedding: '向量模型', retrieval: '检索设置',
  context: '上下文控制', deepReasoning: '深度思考', tool: '工具调用', webSearch: '联网搜索',
  ratelimit: '接口限流', maintenance: '定时维护', apiKey: 'API Key 管理', skills: '技能（预算）',
  agent: '并行检索', oidc: '单点登录', sandbox: '沙盒', memory: '长期记忆'
}
// 导航短名优先；未登记的分组回退到 schema 面板标题的中文主干（取「（」前的主体），
// 而不是把英文 key 直接漏到导航上——oidc / sandbox / memory 曾因此显示成裸 key
const groupLabel = key => NAV_LABELS[key]
  || ((PANELS.find(p => p.key === key)?.title || key).split(/[（(]/)[0].trim() || key)
const current = ref('chat')
const currentPanel = computed(() => PANELS.find(p => p.key === current.value))

// 工具族页签：联网搜索并入「工具调用」面板（导航不再单列），后续新工具族继续加页签。
// activeFormPanel = 当前实际渲染字段所属的 panel key（「恢复本组默认」/高级隐藏计数都按它算）
const toolTab = ref('tool')
const activeFormPanel = computed(() => current.value === 'tool' ? toolTab.value : current.value)

// 高级设置模式（默认关，持久化）：关=只显示核心项（模型服务 + 回答行为），开=显示全部调优参数。
// 对标成熟产品的设置体系——全局设置保持精简，细粒度参数按需展开，避免设置页被 180+ 项淹没。
const advMode = ref(localStorage.getItem('app_adv_settings') === '1')
const navPanels = computed(() => {
  const list = advMode.value ? PANELS : corePanels()
  // 联网搜索已并入「工具调用」页签，导航不再单列
  return list.filter(p => p.key !== 'webSearch')
})
const hiddenHere = computed(() => advMode.value ? 0 : hiddenFieldCount(activeFormPanel.value))
watch(advMode, v => {
  localStorage.setItem('app_adv_settings', v ? '1' : '0')
  // 关掉高级模式时，若当前分组已不在导航中（纯高级分组），切到第一个核心分组，避免停在空白页
  if (!v && !navPanels.value.some(p => p.key === current.value)) {
    current.value = navPanels.value[0]?.key || current.value
  }
})

// 无「恢复本组默认」的分组（API Key 由数据库管理；技能的预算项恢复默认意义不大且与个人技能无关）
const NO_RESET = ['embedding', 'maintenance', 'apiKey']

// 分组顶部说明（与旧版文案一致）
const PANEL_ALERTS = {
  chat: [{ type: 'info', msg: '问答模型支持跨厂商热切换：修改网关地址/API Key/模型名保存即生效免重启，API Key 以 RSA 加密入库。' }],
  vision: [{ type: 'info', msg: '视觉模型用于文档图片与用户图片的描述识别；关闭后图片仅展示、内容不进检索与引用。' }],
  chunk: [{ type: 'info', msg: '上传大小上限保存即生效；分块/图片上限只对重新解析/新上传文档生效，超限按保护策略截断入库。' }],
  embedding: [{ type: 'warning', msg: '向量模型热切换说明：不同模型的向量数学上不可迁移。保存时会先探测新配置并校验维度，通过后自动重建索引并后台全量重嵌入。重嵌入期间向量检索自动降级关键词路，服务不中断。' }],
  retrieval: [
    { type: 'info', msg: '融合分 = 向量权重×向量相似度 + 关键词权重×命中率。保存后立即生效。' },
    { type: 'info', msg: '重排：OpenAI 兼容 /v1/rerank 服务。未启动或不可用时自动回退融合分排序，不影响正常问答。' }
  ],
  context: [{ type: 'info', msg: '预算 = min(模型窗口×安全系数−输出限制, 成本上限)，知识块按相关度降序累积填充，超出自动裁剪。保存后立即生效。' }],
  deepReasoning: [{ type: 'info', msg: '深度思考：AI 先流式展示思维链，思考末尾输出检索计划（精化 query + 子问题）多路并行检索合并后回答。失败自动降级。' }],
  ratelimit: [{ type: 'info', msg: 'Redis 固定窗口计数，按用户（匿名按 IP）限频，超限返回 429。限频设为 0 表示不限流；Redis 不可用时自动放行。' }],
  maintenance: [{ type: 'info', msg: '后台定时任务参数，保存即生效。周期填 ≤0 表示暂停该任务；清理类任务只删超期数据。' }],
  apiKey: [{ type: 'info', msg: '给外部系统发放调用问答能力的密钥：调用方在请求头带 X-Api-Key 即可（免平台 token）。Key 权限固定为问答链路，管理端点一律拒绝。' }],
  // 技能与 MCP 的内容已迁到「智能体」页的个人 Tab（每人管自己的），这里只剩上下文预算参数
  skills: [{ type: 'info', msg: '技能内容与启停由每个人在「智能体 → 技能 Skills」里自己管理（内置技能随版本分发，可各自停用）。这里只保留两项预算参数：技能清单注入系统提示的字符上限、单个技能全文读取的字符上限——防止技能过多或过长吃掉上下文预算。' }],
  agent: [{ type: 'info', msg: '并行检索：把一个问题拆成多个检索视角并行执行（各自检索 + 提炼要点）再汇总，改善复杂问题"召回不全"。若当前智能体已配置子智能体，则改为委派子智能体执行——各按自己的知识库范围与角色视角检索。基于 Spring AI Alibaba 的 StateGraph 编排，失败会自动降级为原有单路检索，不影响问答可用性。' }],
}

const loading = ref(false)
const saving = ref(false)
// 字段定义是否已从后端到达：定义到位前不渲染表单（blocksOf/currentPanel 都依赖字段数组，
// 空定义下渲染会直接抛错）。容器本身仍是 configSchema.js 里的常量引用，填充后即可用。
const schemaLoaded = ref(false)

// ==================== 表单状态与回填 ====================
// 初始为空壳：字段定义来自后端 ⇒ 只有拿到定义后才能 buildDefaultForm()；
// keyword 预置空对象是给下方的探测结果清理 watch 兜底（避免加载期取属性报错）
const form = ref({ keyword: {} })

const fetchAndFill = async () => {
  loading.value = true
  try {
    // 定义先行：渲染字段、构建表单默认值、以及后续提交校验提示都基于它
    const rs = await getConfigSchema()
    if (!rs || !rs.success || !rs.data) throw new Error((rs && rs.msg) || '配置字段定义加载失败')
    applyServerSchema(rs.data)
    form.value = buildDefaultForm()
    schemaLoaded.value = true
    const r = await getConfig()
    if (r.success && r.data) {
      const d = r.data
      for (const f of FIELDS) {
        const raw = d[f.group]?.[f.submitKey || f.key]?.value
        let v
        if (raw === undefined || raw === null || raw === '') {
          v = f.type === 'switch' ? false : (f.type === 'number' ? f.def : (f.def ?? ''))
        } else if (f.type === 'number') v = Number(raw)
        else if (f.type === 'switch') v = raw === 'true' || raw === true
        else v = raw
        if (f.factor) v = Math.round(Number(v) / f.factor)
        writeForm(form.value, f.path, v)
      }
      initialPayload.value = buildPayload()
    }
  } catch (e) { message.error(e.message || '加载配置失败') }
  finally { loading.value = false }
}

// ==================== 提交载荷与脏检测（与旧版同一管线） ====================
const buildPayload = () => {
  const out = {}
  for (const f of FIELDS) {
    let v = readForm(form.value, f.path)
    if (typeof v === 'string') {
      v = v.trim()
      if (/apikey/i.test(f.key) && v.startsWith('****')) continue
    } else if (typeof v === 'number' || typeof v === 'boolean') {
      v = String(v)
    } else if (v === undefined || v === null) {
      continue
    }
    if (f.factor) v = String(Math.round(Number(v) * f.factor))
    ;(out[f.group] = out[f.group] || {})[f.submitKey || f.key] = v
  }
  return out
}
const initialPayload = ref(null)
const dirtyPayload = computed(() => {
  const base = initialPayload.value
  if (!base) return {}
  const cur = buildPayload()
  const out = {}
  for (const g of Object.keys(cur)) {
    const cb = base[g] || {}
    const diff = {}
    for (const k of Object.keys(cur[g] || {})) {
      if (JSON.stringify(cur[g][k]) !== JSON.stringify(cb[k])) diff[k] = cur[g][k]
    }
    if (Object.keys(diff).length) out[g] = diff
  }
  return out
})
const dirtyCount = computed(() =>
  Object.values(dirtyPayload.value).reduce((n, g) => n + Object.keys(g).length, 0))

const save = async () => {
  const payload = dirtyPayload.value
  if (!Object.keys(payload).length) { message.info('没有需要保存的改动'); return }
  saving.value = true
  try {
    const r = await saveConfig(payload)
    if (r.success) {
      const n = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      if (n === 0) message.warning('没有可保存的配置项（后端未识别提交的键），请检查后重试')
      else { message.success(`配置已保存并生效（更新 ${n} 项）`); initialPayload.value = buildPayload() }
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

// ==================== 恢复本组默认 ====================
const resettingKey = ref('')
const doResetGroup = async (key, label) => {
  resettingKey.value = key
  try {
    const r = await resetConfig([key])
    if (r.success) {
      const cnt = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      message.success(`「${label}」已恢复默认（${cnt} 项）`)
      await fetchAndFill()
    } else message.error(r.msg || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { resettingKey.value = '' }
}
const onResetGroup = key => {
  const label = groupLabel(key)
  Modal.confirm({
    title: `恢复「${label}」为默认值？`,
    content: '该组当前的自定义值会被覆盖为出厂默认（模型 API Key 与向量模型组不受影响）。',
    okText: '恢复', cancelText: '取消',
    onOk: () => doResetGroup(key, label)
  })
}

// ==================== 测试连接（先测后存） ====================
// 模型网关的连通性测试已随 baseUrl/API Key 迁至「模型供应商」页（先测后存）；
// 这里保留关键词引擎探测。
const probeStates = ref({
  keyword: { loading: false, result: null },
  ocrMineru: { loading: false, result: null },
  ocrPp: { loading: false, result: null }
})
const probeLabels = { keyword: '关键词引擎', ocrMineru: 'MinerU 服务', ocrPp: 'PP-StructureV3 服务' }
const PROBE_BY_KEY = {
  'keyword.baseUrl': 'keyword',
  'parse.ocrMineruUri': 'ocrMineru',
  'parse.ocrPpUri': 'ocrPp'
}
const probeKey = f => PROBE_BY_KEY[f.group + '.' + f.key] || ''

const doProbe = async group => {
  const s = probeStates.value[group]
  if (!s || s.loading) return
  const f = form.value
  const payload = { group }
  if (group === 'keyword') {
    Object.assign(payload, { baseUrl: f.keyword.baseUrl, apiKey: f.keyword.apiKey })
  } else if (group === 'ocrMineru') {
    Object.assign(payload, { baseUrl: f.parse?.ocrMineruUri })
  } else if (group === 'ocrPp') {
    Object.assign(payload, { baseUrl: f.parse?.ocrPpUri })
  }
  s.loading = true
  s.result = null
  try {
    const r = await probeConnectivity(payload)
    const d = r?.data || {}
    s.result = { available: !!d.available, latencyMs: d.latencyMs ?? 0, detail: d.detail || (r?.msg || '（无详情）') }
    if (!s.result.available) message.error(`${probeLabels[group]}不可达：${s.result.detail}`)
  } catch (e) {
    s.result = { available: false, latencyMs: 0, detail: e.message || '请求失败' }
    message.error(`${probeLabels[group]}探测失败：${s.result.detail}`)
  } finally { s.loading = false }
}

// 被探测项改动后清空旧探测结果
watch(
  () => [form.value.keyword?.baseUrl, form.value.keyword?.apiKey,
         form.value.parse?.ocrMineruUri, form.value.parse?.ocrPpUri],
  () => {
    for (const k of Object.keys(probeStates.value)) probeStates.value[k].result = null
  }
)

// ==================== 枚举变更校验 ====================
const onFieldChange = (field, v) => {
  if (field.group === 'keyword' && field.key === 'engine') onKeywordEngineChange(v)
}
const onKeywordEngineChange = async val => {
  if (val !== 'meilisearch') return
  try {
    const r = await checkKeywordEngine()
    if (r.success && r.data?.available) {
      message.success('Meilisearch 服务正常。保存后请执行全量重建（/api/ai/search-index/reindex）再提问')
    } else {
      form.value.keyword.engine = 'mysql'
      message.error('Meilisearch 不可用：请先启动服务，或检查服务地址')
    }
  } catch (e) {
    form.value.keyword.engine = 'mysql'
    message.error('Meilisearch 校验失败：' + (e.message || '服务不可用'))
  }
}

// ==================== 重嵌入状态 ====================
// ==================== API Key 管理（6.5） ====================
const keys = ref([])
const keysLoading = ref(false)
const keyKeyword = ref('')
const keyTogglingId = ref('')
const keyMcpTogglingId = ref('')
const keyCreating = ref(false)
const usageOpen = ref(false)
const copiedSample = ref('')
const copiedKey = ref(false)
// 创建走两步式弹窗：form（填名称/有效期）→ done（明文仅此一次展示）
const createOpen = ref(false)
const createStep = ref('form')
const createForm = ref({ name: '', expireMode: 'never', expireDate: null })
const createdKey = ref({ name: '', apiKey: '' })
const renameOpen = ref(false)
const renameForm = ref({ id: '', name: '' })

const activeKeyCount = computed(() => keys.value.filter(k => !k.disabled && !k.expired).length)
const filteredKeys = computed(() => {
  const kw = keyKeyword.value.trim().toLowerCase()
  if (!kw) return keys.value
  return keys.value.filter(k =>
    String(k.name || '').toLowerCase().includes(kw) || String(k.keyPrefix || '').toLowerCase().includes(kw))
})
const lastUsedKey = computed(() => keys.value
    .filter(k => k.lastUsedAt)
    .sort((a, b) => String(b.lastUsedAt).localeCompare(String(a.lastUsedAt)))[0] || null)
const fmtTs = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')
const fmtDate = s => (s ? String(s).slice(0, 10) : '长期')

const loadKeys = async () => {
  keysLoading.value = true
  try {
    const r = await listApiKeys()
    if (r.success && Array.isArray(r.data)) keys.value = r.data
  } catch (e) { /* 拉取失败不打扰，保留上次列表 */ }
  finally { keysLoading.value = false }
}
const copyText = async t => {
  try { await navigator.clipboard.writeText(t); return true } catch (e) { return false }
}
/** 有效期选择 → 提交给后端的 expireAt（yyyy-MM-dd；空串=长期） */
const expirePayload = () => {
  const m = createForm.value.expireMode
  if (m === 'never') return ''
  const ymd = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  if (m === 'custom') {
    const d = createForm.value.expireDate
    if (!d) return ''
    return d.format ? d.format('YYYY-MM-DD') : ymd(new Date(d))
  }
  const d = new Date()
  d.setDate(d.getDate() + Number(m))
  return ymd(d)
}
/** 示例随第一个 Key 的前缀变化，让用户一眼看到"在哪儿带" */
const curlSample = computed(() => {
  const k = keys.value[0]?.keyPrefix || 'sk-你的Key'
  return `curl -X POST http://<你的服务地址>/ai/api/ai/chat \\
  -H "Content-Type: application/json" \\
  -H "X-Api-Key: ${k}..." \\
  -d '{"question":"如何创建评分组件？"}'`
})
const openCreateKey = () => {
  createForm.value = { name: '', expireMode: 'never', expireDate: null }
  createdKey.value = { name: '', apiKey: '' }
  copiedKey.value = false
  createStep.value = 'form'
  createOpen.value = true
}
const closeCreateKey = () => {
  createOpen.value = false
  if (createStep.value === 'done') loadKeys()
}
const submitCreateKey = async () => {
  // 空名不静默兜底成「未命名 Key」——直接提示，避免签出一堆分不清用途的 Key
  if (!createForm.value.name.trim()) { message.warning('请先填写用途名称'); return }
  if (createForm.value.expireMode === 'custom' && !createForm.value.expireDate) { message.warning('请选择到期日期'); return }
  keyCreating.value = true
  try {
    const r = await createApiKey({ name: createForm.value.name.trim(), expireAt: expirePayload() })
    if (r.success) {
      createdKey.value = { name: r.data?.name || createForm.value.name.trim(), apiKey: r.data?.apiKey || '' }
      createStep.value = 'done'
      copiedKey.value = await copyText(createdKey.value.apiKey)
    } else message.error(r.msg || '创建失败')
  } catch (e) { message.error(e.message || '创建失败') }
  finally { keyCreating.value = false }
}
const copyCreatedKey = async () => {
  const ok = await copyText(createdKey.value.apiKey)
  copiedKey.value = ok
  if (!ok) message.warning('浏览器未授权剪贴板，请手动选中复制')
}
const copySample = async (k, text) => {
  const ok = await copyText(text)
  copiedSample.value = ok ? k : ''
  if (!ok) message.warning('浏览器未授权剪贴板，请手动选中复制')
}
const openRenameKey = rec => {
  renameForm.value = { id: rec.id, name: rec.name || '' }
  renameOpen.value = true
}

// ==================== API Key 共享范围（弹窗为公共组件 ShareScopeModal） ====================
const keyShareVisible = ref(false)
const keyShareTarget = ref({ id: '', shareConfig: '' })
const saveKeyShareFn = json => updateApiKeyShare(keyShareTarget.value.id, json)
const openShareKey = rec => {
  keyShareTarget.value = { id: rec.id, shareConfig: rec.shareConfig || '' }
  keyShareVisible.value = true
}
const submitRenameKey = async () => {
  const name = renameForm.value.name.trim()
  if (!name) { message.warning('名称不能为空'); return }
  try {
    const r = await renameApiKey(renameForm.value.id, name)
    if (r.success) { message.success('已改名'); renameOpen.value = false; loadKeys() }
    else message.error(r.msg || '改名失败')
  } catch (e) { message.error(e.message || '改名失败') }
}
const toggleKey = async rec => {
  if (keyTogglingId.value) return
  keyTogglingId.value = rec.id
  try {
    const r = await setApiKeyDisabled(rec.id, !rec.disabled)
    if (r.success) { message.success(rec.disabled ? '已启用' : '已停用'); loadKeys() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { keyTogglingId.value = '' }
}
/** MCP 列：授权 / 收回该 Key 访问平台级 MCP 入口（/ai/mcp）的资格（与「停用」是两件事） */
const toggleKeyMcp = async rec => {
  if (keyMcpTogglingId.value) return
  keyMcpTogglingId.value = rec.id
  try {
    const r = await setApiKeyMcp(rec.id, !rec.mcpEnabled)
    if (r.success) { message.success(rec.mcpEnabled ? '已收回 MCP 入口' : '已授权 MCP 入口'); loadKeys() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { keyMcpTogglingId.value = '' }
}
const delKey = async id => {
  try {
    const r = await deleteApiKey(id)
    if (r.success) { message.success('已删除'); loadKeys() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// 切到 API Key 面板时自动拉一次最新列表（数据可能在别处改过）
watch(current, k => {
  if (k === 'apiKey') loadKeys()
  // 工具面板：清单是运行时注册的，进面板时拉最新（后端加过新工具后不用重启前端）
  if (k === 'tool') loadToolInventory()
})

// ==================== 工具清单（管理员视角：本平台注册了哪些 @Tool） ====================
const toolInv = ref([])
const toolInvLoading = ref(false)
const toolInvTotal = computed(() => toolInv.value.reduce((n, g) => n + (g.tools?.length || 0), 0))
const loadToolInventory = async () => {
  toolInvLoading.value = true
  try {
    const r = await getToolInventory()
    if (r.success) {
      toolInv.value = r.data || []
    } else {
      toolInv.value = []
      message.warning(r.msg || '工具清单加载失败（该接口仅管理员可用）')
    }
  } catch (e) {
    // 非管理员 403：清单区显示为空，面板其余功能不受影响
    toolInv.value = []
  } finally {
    toolInvLoading.value = false
  }
}

// ==================== MCP 调用审计（仅管理员，后端 requireAdmin 兜底） ====================
const auditOpen = ref(false)
const auditLoading = ref(false)
const auditRows = ref([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditSize = 20
const auditSummary = ref(null)
const auditFilter = ref({ channel: null, success: null, tool: '' })
const auditTopTool = computed(() => {
  const list = auditSummary.value?.byTool || []
  return list.length ? list[0] : null
})

/** 首次展开时懒加载（带汇总）；resetPage=true 表示筛选变化回第一页 */
const loadAudit = async (resetPage = false) => {
  if (resetPage) auditPage.value = 1
  auditLoading.value = true
  try {
    // 明细与汇总分开请求：任何一个 403/失败都不能连累另一个（Promise.all 会整体 throw）
    const r = await getMcpAuditLogs({ ...auditFilter.value, page: auditPage.value, size: auditSize })
    if (r.success && r.data) {
      auditRows.value = r.data.rows || []
      auditTotal.value = r.data.total || 0
    } else message.error(r.msg || '审计明细加载失败')
    if (!auditSummary.value) {
      const s = await getMcpAuditSummary()
      if (s.success && s.data) auditSummary.value = s.data
    }
  } catch (e) { message.error(e.message || '审计数据加载失败') }
  finally { auditLoading.value = false }
}
const toggleAudit = () => {
  auditOpen.value = !auditOpen.value
  if (auditOpen.value && !auditSummary.value && !auditLoading.value) loadAudit(true)
}
const onAuditPage = p => { auditPage.value = p; loadAudit(false) }

// ==================== 定时任务管理（ScheduleCenter 快照 + 执行日志） ====================
// 状态/统计来自内存快照（重启归零），执行历史来自 c_ai_schedule_run（按保留期清理）。
// 面板用页签组织（参数配置 / 运行状态 / 执行日志）避免一页滚到底；后两个页签首次进入时懒加载。
// 暂停/恢复不另设端点：任务间隔即配置键，写 0 暂停、写默认值恢复，复用既有保存链路（校验/广播一致）。
const maintTab = ref('config')
const scheduleTasks = ref([])
const schedLoading = ref(false)
const schedToggling = ref('')
const runLogRows = ref([])
const runLogTotal = ref(0)
const runLogPage = ref(1)
const runLogSize = 20
const runLogLoading = ref(false)
const runLogFilter = ref({ taskName: null, success: null })

watch(maintTab, v => {
  if (v === 'status' && !scheduleTasks.value.length) loadSchedule()
  if (v === 'logs') loadRunLogs(true)
})

const loadSchedule = async () => {
  schedLoading.value = true
  try {
    const r = await getScheduleTasks()
    if (r.success && r.data) scheduleTasks.value = r.data
    else message.error(r.msg || '定时任务状态加载失败')
  } catch (e) { message.error(e.message || '定时任务状态加载失败') }
  finally { schedLoading.value = false }
}

const loadRunLogs = async (resetPage = false) => {
  if (resetPage) runLogPage.value = 1
  runLogLoading.value = true
  try {
    const r = await getScheduleRuns({
      taskName: runLogFilter.value.taskName, success: runLogFilter.value.success,
      page: runLogPage.value, size: runLogSize
    })
    if (r.success && r.data) {
      runLogRows.value = r.data.rows || []
      runLogTotal.value = r.data.total || 0
    } else message.error(r.msg || '执行日志加载失败')
  } catch (e) { message.error(e.message || '执行日志加载失败') }
  finally { runLogLoading.value = false }
}
const onRunLogPage = p => { runLogPage.value = p; loadRunLogs(false) }

/** 执行日志的保留天数（上方表单 schedule.runLogRetentionDays，随配置加载回显） */
const runLogRetentionDays = computed(() => {
  const v = readForm(form.value, 'schedule.runLogRetentionDays')
  return v == null || v === '' ? 7 : v
})

/** 暂停/恢复：写间隔配置 0 / 默认值；同步本地表单与基线，避免顶部脏计数误报 */
const toggleTaskPause = record => {
  const field = FIELDS.find(f => f.backendKey === record.configKey)
  if (!field) { message.error('配置定义中找不到 ' + record.configKey); return }
  const pausing = !record.paused
  const defVal = Number(field.def ?? 0)
  Modal.confirm({
    title: pausing ? `暂停「${record.name}」？` : `恢复「${record.name}」？`,
    content: pausing
      ? '把间隔配置写为 0，下一个调度节拍（约 10s）生效；期间任务不再自动触发，仍可手动执行。'
      : `把间隔配置恢复为默认值（${defVal}ms），下一个调度节拍生效。`,
    okText: pausing ? '暂停' : '恢复', cancelText: '取消',
    onOk: async () => {
      schedToggling.value = record.name
      try {
        const target = pausing ? 0 : defVal
        const r = await saveConfig({ [field.group]: { [field.submitKey || field.key]: String(target) } })
        if (r.success) {
          message.success(pausing ? `「${record.name}」已暂停` : `「${record.name}」已恢复（间隔 ${defVal}ms）`)
          writeForm(form.value, field.path || (field.group + '.' + field.key), target)
          initialPayload.value = buildPayload()
          loadSchedule()
        } else message.error(r.msg || '操作失败')
      } catch (e) { message.error(e.message || '操作失败') }
      finally { schedToggling.value = '' }
    }
  })
}

/** 手动触发一次：异步执行，稍后自动刷一次状态与日志 */
const doTrigger = async record => {
  try {
    const r = await triggerScheduleTask(record.name)
    if (r.success && r.data?.accepted) {
      message.success(`「${record.name}」已提交执行`)
      loadSchedule()
      setTimeout(() => {
        if (schedOpen.value) loadSchedule()
        if (runLogOpen.value) loadRunLogs(false)
      }, 3000)
    } else {
      message.warning(r.data?.reason || r.msg || '触发被拒绝（上一轮可能还在执行）')
    }
  } catch (e) { message.error(e.message || '触发失败') }
}

const fmtInterval = ms => {
  if (ms == null) return '—'
  if (ms <= 0) return '已暂停'
  if (ms % 86400000 === 0) return (ms / 86400000) + ' 天'
  if (ms % 3600000 === 0) return (ms / 3600000) + ' 小时'
  if (ms % 60000 === 0) return (ms / 60000) + ' 分钟'
  if (ms % 1000 === 0) return (ms / 1000) + ' 秒'
  return ms + ' ms'
}
/** 任务快照里的时间是纪元毫秒（后端快照统一毫秒，倒计时按同一锚点算）；fmtTs 只认 ISO 串，两者别混用 */
const fmtEpoch = ms => {
  if (!ms) return '—'
  const d = new Date(ms)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
const fmtDuration = ms => {
  if (ms == null) return '—'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's'
  return Math.floor(ms / 60000) + 'm' + Math.round((ms % 60000) / 1000) + 's'
}
const fmtCountdown = (due, serverNow) => {
  if (!due) return '—'
  const left = due - (serverNow || Date.now())
  if (left <= 0) return '即将触发'
  if (left < 60000) return Math.ceil(left / 1000) + ' 秒内'
  if (left < 3600000) return Math.ceil(left / 60000) + ' 分钟内'
  if (left < 86400000) return Math.ceil(left / 3600000) + ' 小时内'
  return Math.ceil(left / 86400000) + ' 天内'
}

onMounted(fetchAndFill)
</script>

<style scoped>
.dirty-hint { font-size: 12px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 6px; padding: 3px 10px; }
/* .head-hint-plain 已提到 app.css 作为全局共用样式（供应商/知识库/技能/MCP 页也用它） */
/* 高级设置开关 + 隐藏项提示 */
.adv-toggle {
  display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text3);
  cursor: pointer; user-select: none; margin-left: 10px;
}
.adv-toggle:hover { color: var(--app-accent); }
.adv-hidden-hint {
  font-size: 11px; color: var(--app-text3); background: var(--app-accent-weak);
  border-radius: 999px; padding: 3px 10px;
}
.app-btn.dis { background: var(--app-accent-disabled); cursor: not-allowed; }
.set-body { flex: 1; min-height: 0; display: flex; }
.set-nav {
  width: 150px; flex: none; border-right: 1px solid var(--app-border); background: var(--app-panel);
  padding: 10px 8px; display: flex; flex-direction: column; gap: 2px; overflow-y: auto;
}
.set-nav-item { padding: 7px 10px; border-radius: 8px; font-size: 12px; color: var(--app-text2); cursor: pointer; }
.set-nav-item:hover { background: var(--app-accent-weak); }
.set-nav-item.active { background: var(--app-accent-weak); color: var(--app-text); font-weight: 500; }
.set-content { flex: 1; min-width: 0; overflow-y: auto; padding: 14px 20px 24px; }
.set-panel-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.set-card { padding: 18px 20px 6px; }
.cfg-sub { font-size: 12px; font-weight: 500; color: var(--app-text3); margin: 14px 0 2px; padding-bottom: 4px; border-bottom: 1px dashed var(--app-border); }
.tip-icon { color: var(--app-text3); font-size: 12px; cursor: help; }
.app-btn.small { padding: 3px 10px; font-size: 11px; border-radius: 6px; margin-left: 10px; }
.app-btn.small + .app-btn.small { margin-left: 8px; }
.probe-btn { margin-left: 8px; }
.probe-chip { margin-left: 8px; font-size: 11px; border-radius: 999px; padding: 3px 9px; cursor: help; }
.probe-chip.ok { color: var(--app-ok); background: var(--app-ok-weak); }
.probe-chip.bad { color: var(--app-danger); background: var(--app-danger-weak); }
/* API Key 管理（6.5） */
.key-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.key-bar-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.key-bar-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; flex: none; }
.key-stat { font-size: 12px; color: var(--app-text2); }
.key-stat b { color: var(--app-text); font-weight: 600; }
.key-dim { color: var(--app-text3); font-size: 12px; }
/* 搜索框（三面板统一肩部工具栏用） */
.res-search { width: 220px; }
.res-search :deep(.ant-input-affix-wrapper) { border-radius: 8px; }
.res-search :deep(.ant-input-prefix) { margin-right: 6px; }
.res-search-ic { color: var(--app-text3); font-size: 12px; }
/* 名称与状态标签同一行：inline-flex 垂直居中（inline-block 的基线对齐会让标签高低不齐） */
.key-name-wrap { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; }
.key-name { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.key-tag { font-size: 11px; flex: none; line-height: 18px; }
.key-prefix { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: var(--app-text2); }
/* 空态 */
.key-empty { text-align: center; padding: 36px 20px; border: 1px dashed var(--app-border); border-radius: 8px; }
.key-empty-title { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.key-empty-desc { font-size: 12px; color: var(--app-text3); margin-bottom: 14px; line-height: 1.7; }
/* 如何使用 */
.key-usage { margin-top: 16px; border-top: 1px solid var(--app-border); padding-top: 10px; }
/* 通用折叠头（如何使用 / 高级编辑 / 技能设置） */
.res-fold { margin-top: 14px; border-top: 1px solid var(--app-border); padding-top: 10px; }
.fold-head { font-size: 12px; font-weight: 500; cursor: pointer; user-select: none; }
.fold-head:hover { color: var(--app-accent); }
.fold-caret { display: inline-block; width: 12px; color: var(--app-text3); }
.key-usage-body { padding: 10px 0 0 12px; }
.key-usage-label { font-size: 12px; color: var(--app-text2); margin-bottom: 6px; }
.key-code { position: relative; background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 6px; padding: 10px 34px 10px 12px; }
.key-code code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--app-text); }
.key-copy {
  position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border: none; border-radius: 5px;
  background: transparent; color: var(--app-text3); cursor: pointer; font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
}
.key-copy:hover { background: var(--app-accent-weak); color: var(--app-accent); }
.key-copy-ok { color: var(--app-ok); }
.key-usage-list { margin: 10px 0 0; padding-left: 18px; font-size: 12px; color: var(--app-text2); line-height: 1.9; }
.key-usage-list code { background: var(--app-panel-2); padding: 1px 5px; border-radius: 4px; font-size: 11px; }
/* 弹窗内 */
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.key-done-warn {
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); color: var(--app-warn-text);
  border-radius: 6px; padding: 8px 10px; font-size: 12px; margin-bottom: 12px; line-height: 1.6;
}
.key-done-meta { font-size: 12px; color: var(--app-text2); margin-bottom: 8px; }
.key-done-box {
  position: relative; background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 6px;
  padding: 12px 36px 12px 12px;
}
.key-done-box code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; word-break: break-all; color: var(--app-text); }
/* MCP 调用审计 */
.audit-summary { display: flex; gap: 16px; font-size: 12px; color: var(--app-text2); margin-bottom: 10px; flex-wrap: wrap; }
.audit-summary b { font-weight: 600; color: var(--app-text); }
.audit-summary b.ok { color: var(--app-ok); }
.audit-summary b.bad { color: var(--app-danger); }
.audit-filter { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.audit-chip { font-size: 11px; border-radius: 999px; padding: 2px 8px; white-space: nowrap; }
.audit-chip.agent { color: var(--app-accent); background: var(--app-accent-weak); }
.audit-chip.platform { color: var(--app-warn-text); background: var(--app-warn-weak); }
.audit-chip.pass { color: var(--app-ok); background: var(--app-ok-weak); }
.audit-chip.fail { color: var(--app-danger); background: var(--app-danger-weak); }
/* 定时任务卡片：状态徽标与辅助信息 */
.audit-chip.run { color: var(--app-accent); background: var(--app-accent-weak); }
.audit-chip.paused { color: var(--app-warn-text); background: var(--app-warn-weak); }
.audit-chip.idle { color: var(--app-text3); background: var(--app-border, rgba(127,127,127,.15)); }
/* 定时维护页签条（风格对齐智能体 hub 的 a-tabs：同样字号/内边距/分割线） */
.sched-tabs { margin: 0 4px; }
.sched-tabs :deep(.ant-tabs-nav) { margin-bottom: 0; }
.sched-tabs :deep(.ant-tabs-tab) { font-size: 13px; padding: 10px 2px; }
.sched-tabs :deep(.ant-tabs-nav::before) { border-color: var(--app-border); }
.sched-tabs :deep(.ant-tabs-content-holder) { display: none; }
.sched-pane { padding: 12px 0 0; }
.sched-cfgkey { font-size: 11px; margin-top: 2px; }
.sched-cfgkey code { font-size: 11px; color: var(--app-text3); }

/* 工具清单（工具调用面板 · 页签条下方）：运行时注册的全景只读视图，敏=有副作用工具；
   嵌在 set-card 内部，用底部分隔线与下方表单区分（不再是独立卡片） */
.tool-inv { margin: 0 0 14px; padding: 0 0 14px; border-bottom: 1px solid var(--app-border); }
.tool-inv-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.tool-inv-group { padding: 10px 0; border-top: 1px solid var(--app-border); }
.tool-inv-group:first-of-type { border-top: none; padding-top: 4px; }
.tool-inv-family { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.tool-inv-family-name { font-weight: 500; }
.tool-inv-state { font-size: 12px; padding: 0 8px; border-radius: 4px;
  background: var(--app-warn-weak); color: var(--app-warn-text); }
.tool-inv-state.on { background: var(--app-ok-weak); color: var(--app-ok); }
.tool-inv-key { font-size: 11px; color: var(--app-text3); }
.tool-inv-items { display: flex; flex-wrap: wrap; gap: 6px; }
.tool-inv-chip { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px;
  border-radius: 5px; background: var(--app-panel-2); font-size: 12px; cursor: default; }
.tool-inv-chip.sensitive { border: 1px solid var(--app-danger-border); }
.tool-inv-s { font-size: 10px; color: var(--app-danger-text); }
.sched-err {
  display: inline-block; margin-left: 6px; width: 16px; height: 16px; line-height: 16px;
  border-radius: 50%; text-align: center; font-size: 11px; font-weight: 600;
  color: var(--app-danger); background: var(--app-danger-weak); cursor: help;
}
.sched-ok { color: var(--app-ok); }
.sched-bad { color: var(--app-danger); }
.audit-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.audit-pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; justify-content: flex-end; }
</style>
