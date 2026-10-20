<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">系统设置</h3>
      <span v-if="dirtyCount" class="dirty-hint">有 {{ dirtyCount }} 项已修改未保存</span>
      <button v-else class="head-hint-plain">修改后点右侧保存生效，悬停参数旁 ? 查看说明</button>
      <button class="app-btn" style="margin-left:auto" :disabled="!dirtyCount" :class="{ dis: !dirtyCount }" @click="save">
        <save-outlined /> 保存配置{{ dirtyCount ? `（${dirtyCount} 项改动）` : '' }}
      </button>
    </div>

    <div v-if="!schemaLoaded" class="app-card" style="margin:16px 20px">
      <span class="head-hint-plain">正在加载配置字段定义…</span>
    </div>
    <div v-else class="set-body">
      <!-- 左侧分组导航 -->
      <nav class="set-nav">
        <span v-for="p in navPanels" :key="p.key" class="set-nav-item" :class="{ active: current === p.key }" @click="current = p.key">
          {{ groupLabel(p.key) }}
        </span>
      </nav>

      <!-- 右侧：当前分组表单。fill 模式（运行状态页签）整块按视口高度铺满，
           滚动交给内部表格，见 .set-panel--fill 的注释 -->
      <section class="set-content" :class="{ 'set-panel--fill': isFillPanel }">
        <a-spin :spinning="loading" :class="{ 'set-spin--fill': isFillPanel }">
          <div class="set-panel-head" :class="{ 'set-spin-fill__item': isFillPanel }">
            <h3 class="app-page-title">{{ currentPanel?.title }}</h3>
            <button v-if="!NO_RESET.includes(activeFormPanel)" class="app-btn ghost" :disabled="resettingKey === activeFormPanel" @click="onResetGroup(activeFormPanel)">
              恢复本组默认
            </button>
          </div>

          <div class="app-card set-card">
            <!-- RAG 参数收进「检索设置」：文档解析 / 图片描述 / 上下文控制并成检索设置里的页签，
                 导航仍只保留一个「检索设置」（与工具调用把「联网搜索」并进去同一个做法）。
                 pane 留空只当页签条，内容在下方按 ragTab 对应的 panel 字段渲染 -->
            <a-tabs v-if="current === 'retrieval'" v-model:activeKey="ragTab" class="sched-tabs">
              <a-tab-pane key="retrieval" tab="检索与重排" />
              <a-tab-pane key="context" tab="上下文" />
              <a-tab-pane key="chunk" tab="文档解析" />
              <a-tab-pane key="vision" tab="图片描述" />
            </a-tabs>
            <!-- 工具相关面板的合并入口：总开关与子工具 / 联网搜索 / 技能——
                 避免左侧导航随工具面板膨胀；技能原本是导航里的独立分组，现在收进本页页签。
                 页签风格与「定时任务」一致（a-tabs），pane 留空只当页签条，
                 内容在下方按 toolTab 对应的 panel 字段渲染 -->
            <a-tabs v-if="current === 'tool'" v-model:activeKey="toolTab" class="sched-tabs">
              <a-tab-pane key="tool" tab="总开关与子工具" />
              <a-tab-pane key="webSearch" tab="联网搜索" />
              <a-tab-pane key="skills" tab="技能" />
            </a-tabs>
            <!-- 定时任务面板内容多（参数表单 + 任务状态 + 执行日志），用页签组织避免一页滚到底。
                 页签风格与「智能体」hub 一致（a-tabs）；pane 留空只当页签条，内容在下方按 maintTab 切换 -->
            <a-tabs v-if="current === 'maintenance'" v-model:activeKey="maintTab" class="sched-tabs">
              <a-tab-pane key="config" tab="参数配置" />
              <a-tab-pane key="status" tab="运行状态" />
              <a-tab-pane key="logs" tab="执行日志" />
            </a-tabs>

            <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }" @submit.prevent>
              <!-- 每个配置面板都按 schema 的分节折叠，默认全展开，点标题收起某一节。
                   「定时任务 → 参数配置」页签**只放不属于任何周期任务的参数**（其余 41 项在
                   「运行状态」表格里就地调整，见那边的展开行）——两个页签曾各存一份同样的旋钮，
                   49 项里 41 项重复，改一处要猜另一处是否跟着变，现在每个参数只有一个家。 -->
              <a-alert v-if="isMaintConfig" type="info" show-icon
                       message="这里只放不属于任何定时任务的通用参数。某个任务自己的间隔与专属参数（保留期、并发、超时等），在「运行状态」页签展开对应任务就地调整。" />
              <!-- 整块与上方页签条之间留一条缝：页签条自身 margin-bottom 是 0，贴着会喘不过气 -->
              <div v-if="cfgGroupsAll.length" class="cfg-fold-wrap">
                <!-- 工具条只在两组以上出现：单组面板里搜索和「全部展开」都只是在重复那一个标题 -->
                <div v-if="showCfgBar" class="cfg-fold-bar">
                  <a-input v-model:value="cfgKeyword" size="small" allow-clear
                           placeholder="搜参数名 / 配置键，如 重试、graphrag." style="width: 240px">
                    <template #prefix><search-outlined class="res-search-ic" /></template>
                  </a-input>
                  <span class="key-dim">{{ cfgGroups.length }} 组 · 共 {{ cfgTotalCount }} 项</span>
                  <button class="app-link-btn" @click="toggleAllFold">{{ allCfgOpen ? '全部收起' : '全部展开' }}</button>
                </div>
                <div v-if="!cfgGroups.length" class="key-usage-list key-dim">
                  没有匹配「{{ cfgKeyword }}」的参数
                </div>
                <div v-for="g in cfgGroups" :key="g.title" class="cfg-fold">
                  <div class="fold-head cfg-fold-head" @click="toggleFold(g.title)">
                    <span class="fold-caret">{{ isFoldOpen(g.title) ? '▾' : '▸' }}</span>
                    <span>{{ g.title }}</span>
                    <span class="cfg-fold-n">{{ g.fields.length }} 项</span>
                  </div>
                  <template v-if="isFoldOpen(g.title)">
                    <template v-for="(blk, i) in g.blocks" :key="i">
                      <!-- 超时档位：一次写齐四个超时，避免单项调整配出自相矛盾的组合 -->
                      <TimeoutPreset v-if="blk.type === 'timeoutPreset'" :form="form" />

                      <!-- 常规字段（SchemaField 全量复用：类型控件/条件显隐/参数说明）。
                           MCP 的管理界面已迁到「智能体」页的个人 Tab；技能停用清单随本页「技能」页签一起渲染 -->
                      <template v-else-if="blk.type === 'field'">
                        <SchemaField :field="blk.field" :form="form" :tips="TIPS" :options="fieldOptions(blk.field)">
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
                </div>
              </div>

              <!-- API 密钥（6.5）：签发 / 列表 / 停用 / 删除 -->
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
                      <a-tooltip :title="record.mcpEnabled ? '可访问平台级 MCP 入口 /ai/mcp' : '未授权 MCP 入口（点击授权）'">
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
                      <button class="app-link-btn" @click="openEditKey(record)">编辑</button>
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

                    <!-- 平台级 MCP 接入点：开关只是授权，这里给的是"粘上就能用"的地址与配置。
                         此前本页只有一个 MCP 开关和一句说明，地址从没露过面——授权了也不知道接哪。
                         刻意不加"有 Key 才显示"的门：地址跟有没有 Key 无关，而还没建 Key 的人
                         恰恰是最该先看清"接哪、怎么接"的人（真机验证时库里 0 个 Key，
                         正是这一条把整块藏没了）。 -->
                    <div class="mcp-entry">
                      <div class="key-usage-label">MCP 接入点（平台级 · 一个地址调用整套能力）</div>
                      <div class="key-code">
                        <code>{{ mcpPlatformUrl }}</code>
                        <button class="key-copy" :title="copiedSample === 'mcpUrl' ? '已复制' : '复制'"
                                @click="copySample('mcpUrl', mcpPlatformUrl)">
                          <check-outlined v-if="copiedSample === 'mcpUrl'" class="key-copy-ok" /><copy-outlined v-else />
                        </button>
                      </div>
                      <div class="key-usage-label">mcp.json（粘贴到 Cursor 的 .cursor/mcp.json 或 Claude Desktop 配置）</div>
                      <div class="key-code">
                        <pre class="key-code-pre">{{ mcpPlatformJson }}</pre>
                        <button class="key-copy" :title="copiedSample === 'mcpJson' ? '已复制' : '复制'"
                                @click="copySample('mcpJson', mcpPlatformJson)">
                          <check-outlined v-if="copiedSample === 'mcpJson'" class="key-copy-ok" /><copy-outlined v-else />
                        </button>
                      </div>
                      <ul class="key-usage-list">
                        <li>需要<strong>该 Key 打开上方「MCP」开关</strong>，且管理员在「系统设置 → MCP 服务（双向）」开启「对外提供 MCP 端点」——总开关同时约束平台级入口与每个智能体自己的端点</li>
                        <li>对外域名访问（不是 localhost）时，还须把前端域名填进「MCP 服务（双向）→ 允许的 Origin」，否则浏览器型客户端会被 403 拒绝（Claude / Cursor 这类本地进程客户端不带 Origin，不受影响）</li>
                        <li>可见范围与 Key 创建者在网页上看到的完全一致；每次调用都落进下方「MCP 调用审计」</li>
                        <li>想让某一个智能体单独对外，改用它的「公开发布 → MCP 端点」标签页，那是另一个地址（<code>/ai/mcp/{token}</code>），凭据是分享 token 而不是 Key</li>
                      </ul>
                    </div>
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

                <!-- 编辑弹窗：名称与有效期都能改（Key 本身改不了——明文只在签发时回显一次） -->
                <a-modal v-model:open="editOpen" title="编辑 API Key" :footer="null" :width="460">
                  <a-form layout="vertical">
                    <a-form-item label="用途名称" required>
                      <a-input v-model:value="editForm.name" placeholder="如：报表系统集成"
                               :maxlength="200" @press-enter="submitEditKey" />
                    </a-form-item>
                    <a-form-item label="有效期">
                      <a-radio-group v-model:value="editForm.expireMode" size="small" button-style="solid">
                        <a-radio-button value="never">长期有效</a-radio-button>
                        <a-radio-button value="30">30 天</a-radio-button>
                        <a-radio-button value="90">90 天</a-radio-button>
                        <a-radio-button value="custom">选日期</a-radio-button>
                      </a-radio-group>
                      <a-date-picker v-if="editForm.expireMode === 'custom'" v-model:value="editForm.expireDate"
                                     style="margin-top:8px" placeholder="选择到期日期"
                                     :disabled-date="d => d && d.valueOf() < Date.now() - 86400000" />
                      <div class="key-field-hint">「30 天 / 90 天」从今天起算；到期后该 Key 立即不可调用，改回长期即恢复</div>
                    </a-form-item>
                  </a-form>
                  <div class="key-modal-foot">
                    <button class="app-btn ghost" @click="editOpen = false">取消</button>
                    <button class="app-btn" @click="submitEditKey">保存</button>
                  </div>
                </a-modal>

                <!-- 共享范围（公共组件：与文档 / 智能体同一套两区表单） -->
                <ShareScopeModal v-model:open="keyShareVisible" resource-label="API Key" read-verb="查看"
                                 :share-config="keyShareTarget.shareConfig" :save-fn="saveKeyShareFn" @saved="loadKeys" />
              </template>

              <!-- 定时任务·运行状态页签：ScheduleCenter 内存快照（统计随重启归零）。
                   暂停/恢复即把间隔配置写 0 / 默认值，复用既有保存链路。
                   间隔与「专属参数」都可就地编辑：这两个旋钮只对本任务生效，是管理员最常调的，
                   放在任务所在行里改，不必再去「参数配置」页签猜哪个参数管哪个任务 -->
              <template v-if="current === 'maintenance' && maintTab === 'status'">
                <div class="key-usage-body sched-pane">
                  <div class="audit-filter">
                    <span class="key-dim">共 {{ scheduleTasks.length }} 个任务 · 点间隔或「调参」可就地改，保存即生效（下一调度节拍约 10s 内）</span>
                    <a-tooltip title="刷新运行状态">
                      <button class="app-icon-btn" aria-label="刷新定时任务状态" :disabled="schedLoading" @click="loadSchedule">
                        <reload-outlined />
                      </button>
                    </a-tooltip>
                  </div>
                  <a-table :data-source="scheduleTasks" size="small" row-key="name" :pagination="false"
                           :loading="schedLoading" :scroll="{ x: 1020, y: tableScrollY }"
                           class="sched-table"
                           :expanded-row-keys="expandedTasks" @expandedRowsChange="onExpandChange">
                    <a-table-column title="任务" key="name" width="190">
                      <template #default="{ record }">
                        <a-tooltip :title="record.desc">
                          <span class="key-name">{{ record.name }}</span>
                        </a-tooltip>
                        <div v-if="record.configKey" class="sched-cfgkey"><code>{{ record.configKey }}</code></div>
                        <div v-else class="sched-cfgkey key-dim">内置节拍</div>
                      </template>
                    </a-table-column>
                    <a-table-column title="间隔" key="interval" width="150">
                      <template #default="{ record }">
                        <!-- 间隔就地编辑：单行内改完即存，不跳页签。内置节拍（无配置键）只读 -->
                        <IntervalEditor
                          v-if="record.editable"
                          :model-value="record.intervalMs"
                          :saving="schedSavingKey === record.configKey"
                          :paused="record.paused"
                          @save="v => saveInterval(record, v)" />
                        <span v-else-if="record.paused" class="key-dim">—</span>
                        <span v-else class="key-dim">{{ fmtInterval(record.intervalMs) }}</span>
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
                        <a-tooltip v-else :title="'间隔为内置节拍（无对应配置项），不可暂停'">
                          <span class="key-dim">内置</span>
                        </a-tooltip>
                        <!-- 暂停代价提示：少数任务停掉后会留下不易察觉的副作用（挂起 run 不落终态、
                             Redis 断线期间配置变更丢失），在列表上直接标出，避免误关自愈机制 -->
                        <a-tooltip v-if="record.pauseRisk && !record.paused" :title="record.pauseRisk">
                          <span class="sched-risk">!</span>
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

                    <!-- 展开行：该任务的专属参数（只影响本任务的旋钮）。
                         做成展开行而非独立列——独立列会把每行撑得很高（文档解析队列扫描有 6 项），
                         且与折叠分组里的同名参数重复。展开后此处即该参数唯一的可调位置。 -->
                    <template #expandedRowRender="{ record }">
                      <div v-if="!taskParamsOf(record).length" class="key-dim sched-no-params">
                        该任务没有专属参数（间隔已在上方可直接调整）
                      </div>
                      <div v-else class="sched-expanded">
                        <div class="cfg-sub sched-expanded-title">
                          「{{ record.name }}」的专属参数
                          <span class="key-dim">（只影响这一个任务，保存后经顶部「保存配置」统一生效）</span>
                        </div>
                        <div class="sched-expanded-grid">
                          <div v-for="f in taskParamsOf(record)" :key="f.path" class="sched-param-row">
                            <a-tooltip :title="paramTip(f)">
                              <span class="sched-param-label">{{ f.label }}</span>
                            </a-tooltip>
                            <SchemaField :field="f" :form="form" :tips="TIPS" bare />
                          </div>
                        </div>
                      </div>
                    </template>
                  </a-table>
                </div>
              </template>

              <!-- 定时任务·执行日志页签：c_ai_schedule_run 分页（只记有信息量的执行：失败/手动触发/有产出；
                   高频扫描任务空跑不落行；超期由清理任务删除） -->
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
                    <a-tooltip title="只记有信息量的执行：失败 / 手动触发 / 有产出。解析队列扫描等高频任务空跑不落行">
                      <span class="key-dim">保留 {{ runLogRetentionDays }} 天</span>
                    </a-tooltip>
                    <button class="app-link-btn danger" :disabled="runLogLoading" @click="confirmClearRunLogs">清空</button>
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

              <!-- 技能停用清单已并入上方「技能」页签；MCP 的管理界面仍在「智能体」页的个人 Tab -->

            </a-form>

            <!-- 清单（面板最底部）：只读。工具清单归「总开关与子工具」页签，技能名册归「技能」页签，
                 各看一份、不再在同一屏里重复。
                 启停统一走上方表单的总开关 / 智能体三态 / 平台停用名单，清单里不再重复放一遍开关 -->
            <div v-if="current === 'tool' && (toolTab === 'tool' || toolTab === 'skills')" class="tool-inv">
              <template v-if="toolTab === 'tool'">
                <div class="tool-inv-head">
                  <span class="cfg-sub">当前注册的工具（{{ toolInvTotal }} 个 · 名称即模型看到的工具名 · 只读清单）</span>
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
                      <span class="tool-inv-state" :class="{ on: g.enabled }"
                            :title="g.enabled ? '本轮可用（仍受总开关、智能体三态与停用名单约束）' : '当前未启用'">
                        {{ g.enabled ? '可用' : '未启用' }}
                      </span>
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
              </template>

              <!-- 技能名册：与工具清单同一套分组/芯片样式，来源（内置 / 我的 / 工作区）即分组。
                   已停用的条目照样列出并打标，否则「停用清单」里的名字在页面上找不到对应项 -->
              <div v-if="toolTab === 'skills'">
                <div class="tool-inv-head">
                  <span class="cfg-sub">当前技能（{{ skillTotal }} 个 · 名称即模型看到的技能名 · 平台停用名册）</span>
                  <a-tooltip title="刷新技能清单">
                    <button class="app-icon-btn" aria-label="刷新技能清单" :disabled="skillLoading" @click="loadSkillOptions(true)">
                      <reload-outlined />
                    </button>
                  </a-tooltip>
                </div>
                <a-spin v-if="skillLoading" size="small" style="display:block;margin:14px auto" />
                <div v-else-if="skillGroups.length" class="tool-inv-groups">
                  <div v-for="g in skillGroups" :key="g.source" class="tool-inv-group">
                    <div class="tool-inv-family">
                      <span class="tool-inv-family-name">{{ g.label }}</span>
                      <span class="skill-inv-n">{{ g.skills.length }} 个</span>
                    </div>
                    <div class="tool-inv-items">
                      <a-tooltip v-for="sk in g.skills" :key="sk.name" :title="skillTip(sk)">
                        <span class="tool-inv-chip" :class="{ 'is-off': sk.disabled }">
                          {{ sk.name }}<span v-if="sk.disabled" class="tool-inv-s">平台停用</span>
                        </span>
                      </a-tooltip>
                    </div>
                  </div>
                </div>
                <div v-else class="key-dim" style="padding:6px 2px">暂无可列出的技能（需登录；后端未返回技能时为空）。</div>
                <div class="key-dim" style="padding:6px 2px">
                  成员自助安装 / 编辑 / 删除 / 个人停用，在「智能体 → 技能」页；这里只维护平台级停用名册。
                </div>
              </div>
            </div>
          </div>
        </a-spin>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick, h } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { SaveOutlined, QuestionCircleOutlined, CopyOutlined, CheckOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { getConfig, getConfigSchema, saveConfig, resetConfig,
         probeConnectivity,
         listApiKeys, createApiKey, setApiKeyDisabled, setApiKeyMcp, deleteApiKey, updateApiKey, updateApiKeyShare,
         getMcpAuditLogs, getMcpAuditSummary,
         getScheduleTasks, triggerScheduleTask, getScheduleRuns, clearScheduleRuns,
         getToolInventory, listAvailableSkills } from '../api'
import ShareScopeModal from './ShareScopeModal.vue'
import SchemaField from '../components/SchemaField.vue'
import IntervalEditor from '../components/IntervalEditor.vue'
import TimeoutPreset from '../components/TimeoutPreset.vue'
import { FIELDS, PANELS, TIPS, blocksOf, buildDefaultForm, readForm, writeForm, applyServerSchema } from '../configSchema'

// 分组导航（沿用旧版锚点短名）
const NAV_LABELS = {
  chat: '对话模型', vision: '图片描述', chunk: '文档解析', retrieval: '检索设置',
  context: '上下文控制', tool: '工具调用', webSearch: '联网搜索',
  ratelimit: '接口限流', maintenance: '定时任务', apiKey: 'API 密钥', skills: '技能',
  oidc: '单点登录', sandbox: '沙盒', debug: '调试设置'
}
// 导航短名优先；未登记的分组回退到 schema 面板标题的中文主干（取「（」前的主体），
// 而不是把英文 key 直接漏到导航上——oidc / sandbox / memory 曾因此显示成裸 key
const groupLabel = key => NAV_LABELS[key]
  || ((PANELS.find(p => p.key === key)?.title || key).split(/[（(]/)[0].trim() || key)
const current = ref('chat')
const currentPanel = computed(() => PANELS.find(p => p.key === current.value))

// 工具族页签：联网搜索与技能并入「工具调用」面板（导航不再单列），后续新工具族继续加页签。
// RAG 页签：文档解析 / 图片描述 / 上下文控制并入「检索设置」，导航不再单列这三个分组。
// activeFormPanel = 当前实际渲染字段所属的 panel key（「恢复本组默认」按它算）
const toolTab = ref('tool')
const ragTab = ref('retrieval')
const activeFormPanel = computed(() => current.value === 'tool'
  ? toolTab.value
  : current.value === 'retrieval' ? ragTab.value : current.value)

/**
 * 字段块 + 「耐心档位」卡：档位只服务对话模型的超时那一节，插在节标题下、第一个超时字段之前。
 * 判据取下一个字段的 backendKey（而不是写死 section 下标）——schema 调整分节顺序时不会插错位置。
 */
const formBlocks = computed(() => {
  const list = blocksOf(activeFormPanel.value, form.value)
  const out = []
  for (let i = 0; i < list.length; i++) {
    out.push(list[i])
    const next = list[i + 1]
    if (list[i].type === 'sub' && next?.type === 'field' && next.field.backendKey === 'chat.sseTimeoutMs') {
      out.push({ type: 'timeoutPreset' })
    }
  }
  return out
})

const navPanels = computed(() => {
  // 联网搜索与技能已并入「工具调用」页签，导航不再单列；
  // 「检索设置」在导航里时，把 RAG 链路的其余三组收进它的页签，导航也不再单列
  const groupRag = PANELS.some(p => p.key === 'retrieval')
  const ragChildren = new Set(['context', 'chunk', 'vision'])
  return PANELS.filter(p => p.key !== 'webSearch' && p.key !== 'skills'
    && (!groupRag || !ragChildren.has(p.key)))
})

// 无「恢复本组默认」的分组（API Key 由数据库管理；调试组的落库键前缀是 chat，
// 按 panel 名重置匹配不到键，且两个调试开关默认即关、重置价值低）
const NO_RESET = ['maintenance', 'apiKey', 'debug']

// 分组顶部说明已整体删除：逐条核对后，它们说的都是分节标题、字段旁的 ? 说明或页内已有的话
// （「关闭后怎样」「≤0=暂停」「重排归知识库」等在对应字段上都有），横幅只会把视线从参数上挪走

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
        // 多选名单（如平台停用工具名单）先判：库里是逗号串、表单里恒为数组，空值也不能例外。
        // 落到下面那条 def 分支会填进空串——控件虽然还能渲染（SchemaField.rawMulti 会兜），
        // 但表单里就出现「有的库是数组、有的库是字符串」两种形态，拼载荷时要靠运气
        if (f.type === 'multiselect') {
          v = String(raw ?? '').split(/[,\n\r，;；]+/).map(s => s.trim()).filter(Boolean)
        } else if (raw === undefined || raw === null || raw === '') {
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
    if (f.type === 'multiselect') {
      // 数组 → 后端那一个逗号串键（顺序即标签顺序）。空数组也要提交成空串：这一步是与
      // 「库里清空」的唯一通路，而脏检测只比 payload 里出现过的键——这里 continue 掉，
      // 删完最后一个标签时按钮就永远是灰的，用户清不掉任何一项（实测踩到）。
      const joined = (Array.isArray(v) ? v : []).map(s => String(s).trim()).filter(Boolean).join(',')
      ;(out[f.group] = out[f.group] || {})[f.submitKey || f.key] = joined
      continue
    }
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
      else {
        message.success(`配置已保存并生效（更新 ${n} 项）`)
        initialPayload.value = buildPayload()
        // 停用清单变了，清单区的「平台停用」标记要跟着变
        if (payload.skill && Object.prototype.hasOwnProperty.call(payload.skill, 'disabled')) loadSkillOptions()
      }
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

// ==================== 恢复本组默认 ====================
const resettingKey = ref('')

/**
 * 面板字段的默认值载荷。技能组不在后端 reset 白名单里（resetDefaults 只认
 * chat/vision/chunk/…/ratelimit），但「技能」页签只有停用清单这一项；
 * 按字段默认值走保存链路，结果与恢复默认一致，也避免按钮点了只弹一句 400。
 */
const panelDefaultPayload = panel => {
  const out = {}
  for (const f of FIELDS.filter(x => x.panel === panel)) {
    const v = f.type === 'multiselect' ? '' : (f.def == null ? '' : String(f.def))
    ;(out[f.group] = out[f.group] || {})[f.submitKey || f.key] = v
  }
  return out
}

const doResetGroup = async (key, label) => {
  resettingKey.value = key
  try {
    const r = key === 'skills'
      ? await saveConfig(panelDefaultPayload('skills'))
      : await resetConfig([key])
    if (r.success) {
      const cnt = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      message.success(`「${label}」已恢复默认（${cnt} 项）`)
      await fetchAndFill()
      // 停用清单被清空后，技能名册里的「平台停用」标记也要跟着消失
      if (key === 'skills') loadSkillOptions()
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
// 这里保留关键词引擎、沙盒 provisioner 等服务地址的探测。
const probeStates = ref({
  keyword: { loading: false, result: null },
  webSearch: { loading: false, result: null }
})
const probeLabels = { keyword: '关键词引擎', webSearch: '联网搜索' }
const PROBE_BY_KEY = {
  'keyword.baseUrl': 'keyword',
  // 联网搜索：按服务商分派真实协议（SearXNG 走 /search?format=json，Tavily/Bocha 走 POST 搜索端点），
  // 挂在 baseUrl 上；provider 本身没有地址、也没有可探测的端点，故不单独挂按钮
  'webSearch.baseUrl': 'webSearch'
}
const probeKey = f => PROBE_BY_KEY[f.group + '.' + f.key] || ''

const doProbe = async group => {
  const s = probeStates.value[group]
  if (!s || s.loading) return
  const f = form.value
  const payload = { group }
  if (group === 'keyword') {
    Object.assign(payload, { baseUrl: f.keyword.baseUrl, apiKey: f.keyword.apiKey })
  } else if (group === 'webSearch') {
    Object.assign(payload, { baseUrl: f.webSearch?.baseUrl, apiKey: f.webSearch?.apiKey })
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
         // 联网搜索：地址、Key、以及**服务商**（换服务商等于换一套协议与端点，旧结论不再成立）
         form.value.webSearch?.baseUrl, form.value.webSearch?.apiKey, form.value.webSearch?.provider],
  () => {
    for (const k of Object.keys(probeStates.value)) probeStates.value[k].result = null
  }
)
// ==================== 枚举变更校验 ====================
// 刻意**没有**「切换到 meilisearch 就探测、不通就静默改回 mysql」的联动（历史上有过，已移除）：
// 服务地址/Key 只有选中 meilisearch 后才可见（schema 的 vif），用户正要在这两个字段里填值，
// 而探测必须走网络——此刻表单里往往还是默认值/空值，探测必然失败；一旦据此回改引擎，
// 字段又立刻消失，用户永远配不成（死锁），且失败原因被静默吞掉。
// 可达性校验改由保存时的后端执行（ConfigService.update 用**本次提交的值**探测，失败带原因报错），
// 「测试连接」按钮则走 /config/probe，同样用表单当前值——先测后存，不必先保存。

// ==================== 重嵌入状态 ====================
// ==================== API 密钥（6.5） ====================
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
const editOpen = ref(false)
const editForm = ref({ id: '', name: '', expireMode: 'never', expireDate: null })

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
/** 有效期选择 → 提交给后端的 expireAt（yyyy-MM-dd；空串=长期）。创建与编辑两个弹窗共用 */
const expirePayload = form => {
  const m = form.expireMode
  if (m === 'never') return ''
  const ymd = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  if (m === 'custom') {
    const d = form.expireDate
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

/**
 * 平台级 MCP 接入点。地址由 window.location.origin 拼出，因此开发态是 vite 的 5800
 * （vite 已把 /ai 代理到后端，curl 实测通），生产态就是前端域名——不需要用户自己去拼。
 *
 * 凭据头用真 Key 前缀 + "..."：完整 Key 明文只在签发时回显一次，这里不可能拿到，
 * 用前缀是为了让粘贴的人一眼知道该换成什么，而不是留一句"你的 Key"让人猜位置。
 */
const mcpPlatformUrl = computed(() => window.location.origin + '/ai/mcp')
const mcpPlatformJson = computed(() => {
  const k = keys.value.find(x => !x.disabled && !x.expired)?.keyPrefix || 'sk-你的Key'
  return JSON.stringify({
    mcpServers: {
      wenqu: { url: mcpPlatformUrl.value, headers: { Authorization: `Bearer ${k}...` } }
    }
  }, null, 2)
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
    const r = await createApiKey({ name: createForm.value.name.trim(), expireAt: expirePayload(createForm.value) })
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
const openEditKey = rec => {
  // 有效期回填：有到期日 → 「选日期」并显示那一天（已过期的也显示，改到未来或切长期才恢复可用）；无 → 长期有效
  const exp = rec.expireAt ? dayjs(String(rec.expireAt).slice(0, 10)) : null
  editForm.value = { id: rec.id, name: rec.name || '', expireMode: exp ? 'custom' : 'never', expireDate: exp }
  editOpen.value = true
}

// ==================== API Key 共享范围（弹窗为公共组件 ShareScopeModal） ====================
const keyShareVisible = ref(false)
const keyShareTarget = ref({ id: '', shareConfig: '' })
const saveKeyShareFn = json => updateApiKeyShare(keyShareTarget.value.id, json)
const openShareKey = rec => {
  keyShareTarget.value = { id: rec.id, shareConfig: rec.shareConfig || '' }
  keyShareVisible.value = true
}
const submitEditKey = async () => {
  const name = editForm.value.name.trim()
  if (!name) { message.warning('名称不能为空'); return }
  if (editForm.value.expireMode === 'custom' && !editForm.value.expireDate) { message.warning('请选择到期日期'); return }
  try {
    const r = await updateApiKey(editForm.value.id, { name, expireAt: expirePayload(editForm.value) })
    if (r.success) { message.success('已保存'); editOpen.value = false; loadKeys() }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
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

// 「平台停用工具名单」（tool.deny，multiselect）的候选：直接用上面这份工具全景，不另开接口。
// 名字与族名都在 option 的 label 里（optionFilterProp=label ⇒ 中文名和工具名都能搜到）；
// 历史值里已不在名册上的名字由控件自己补成标签（见 SchemaField 的 multiOptions）。
const denyOptions = computed(() => toolInv.value.flatMap(g => (g.tools || []).map(t => ({
  value: t.name,
  label: (g.familyLabel ? g.familyLabel + ' · ' : '') + (t.label || t.name)
}))))

// 停用技能是「从名册里选」而不是手写名字：候选来自 /skill/available（内置 + 本人技能；
// 不带会话就不含工作区那一路——仓库本来也不按停用清单过滤工作区技能）。
const skillOptions = ref([])
/** 技能名册（原始条目）：清单区要按来源分组、给停用项打标，不能只留一个下拉候选 */
const skillList = ref([])
const skillLoading = ref(false)
const loadSkillOptions = async (manual = false) => {
  skillLoading.value = true
  try {
    const r = await listAvailableSkills()
    const list = (r && r.data) || []
    skillList.value = list
    const seen = new Set()
    skillOptions.value = list
      .filter(s => s && s.name && s.source !== 'workspace' && s.selectable !== false
        && !seen.has(s.name) && seen.add(s.name))
      .map(s => ({ value: s.name, label: s.name }))
  } catch (e) {
    skillList.value = []
    skillOptions.value = []
    if (manual) message.error(e.message || '技能清单加载失败')
  } finally {
    skillLoading.value = false
  }
}
const SKILL_SOURCE_LABELS = { builtin: '内置技能', user: '我的技能', workspace: '工作区技能' }
const skillTotal = computed(() => skillList.value.length)
/** 按来源分组（内置 → 我的 → 沙盒工作区）；未知来源排在最后，原样显示来源名 */
const skillGroups = computed(() => {
  const order = ['builtin', 'user', 'workspace']
  const groups = new Map()
  for (const s of skillList.value) {
    const source = s.source || 'other'
    if (!groups.has(source)) groups.set(source, [])
    groups.get(source).push(s)
  }
  const rank = source => { const i = order.indexOf(source); return i < 0 ? order.length : i }
  return [...groups.entries()]
    .sort((a, b) => rank(a[0]) - rank(b[0]))
    .map(([source, skills]) => ({ source, label: SKILL_SOURCE_LABELS[source] || source, skills }))
})
/** 技能芯片的悬浮说明：描述 + 停用口径 + 来源 */
const skillTip = s => [
  s.description,
  s.platformDisabled ? '平台已停用（所有成员都用不了）' : (s.userDisabled ? '你已停用（只影响自己）' : ''),
  s.source === 'workspace' ? '沙盒工作区技能（随会话）' : ''
].filter(Boolean).join('；') || s.name
const fieldOptions = f => f.backendKey === 'skill.disabled' ? skillOptions.value : denyOptions.value

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
/** 正在保存间隔的任务配置键（表格内联编辑的 loading 态） */
const schedSavingKey = ref('')

/**
 * 运行状态表格体的最大高度（px，超出则表格内部滚动，表头固定）。
 * 17 个任务行约 58px/行 = 986px，比一屏高 ⇒ 让整页滚的话，用户要滚过整张表
 * 才能看到页头说明，展开某行后要滚两屏。
 *
 * <p>**按容器真实高度算，不按 window.innerHeight 算**——这是本条最关键的一点。
 * `.set-content` 是 flex:1 但没有固定高度，它随内容长高，所以
 * `window.innerHeight - 固定常数` 算出来的值对不上它真实的可视高度
 * （实测：视口 900 时 .set-content.clientHeight 只有 849，差的是页头 51px）。
 * 早先按估值 220 / 实测常数 143 各试一次都判错方向（后者甚至让整页滚动从 48px 涨到 176px）。
 * 现在直接量容器：clientHeight 减去工具条(26) + 表头(≈41) + 面板 padding(≈27) 余量。
 *
 * <p>不用纯 CSS flex 链的原因：多级 flex 里 .sched-pane 的 flex:1 实测压不住
 * （父 .set-card 734px 而它 1085px），改 height:0 又会把表格压成零高、
 * 元素被父层 overflow 裁掉——页面看着不滚了，但末 4 行既看不见也滚不到，是"假优化"。
 */
const isFillPanel = computed(() => current.value === 'maintenance' && maintTab.value === 'status')
/** 表格体之外、同一屏内还要占的高度。实测标定过程：先按 100 取 → 整页差 168px；
 *  改 196 → 差 72px；再补 72（合计 268）恰好归零。构成：面板标题 16 + 页签条 41
 *  + 工具条 26 + 表头 41 + 卡片 padding 48 + 展开行余量。 */
const TABLE_CHROME_H = 268
const tableScrollY = ref(520)
const computeTableScrollY = () => {
  const el = document.querySelector('.set-content')
  // 容器不可用（面板未挂载）时退回窗口估算，下一次 resize / 进入面板会纠正
  const avail = el ? el.clientHeight : window.innerHeight - 51
  tableScrollY.value = Math.max(320, avail - TABLE_CHROME_H)
}
onMounted(() => {
  computeTableScrollY()
  window.addEventListener('resize', computeTableScrollY)
})
onUnmounted(() => window.removeEventListener('resize', computeTableScrollY))
// 切到运行状态时容器才挂载，需重算一次（v-if 渲染时机晚于 watch）
watch(() => current.value, v => { if (v === 'maintenance') nextTick(computeTableScrollY) })
watch(maintTab, v => { if (v === 'status') nextTick(computeTableScrollY) })
const runLogRows = ref([])
const runLogTotal = ref(0)
const runLogPage = ref(1)
const runLogSize = 20
const runLogLoading = ref(false)
const runLogFilter = ref({ taskName: null, success: null })

watch(maintTab, v => {
  // 快照是「运行状态」表格与「参数配置」分组标题（影响哪个任务）共同的数据源，
  // 且参数配置是默认页签 ⇒ 进面板就要拉，不能等到切到运行状态，否则折叠标题上的
  // 「影响：xxx」在首屏是空的（正是"不知道该调哪个参数"的由来）
  if ((v === 'status' || v === 'config') && !scheduleTasks.value.length) loadSchedule()
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

/** 清空全部执行日志：确认后立即删除（不等保留期清理任务），成功后回到第一页重查 */
const confirmClearRunLogs = () => {
  Modal.confirm({
    title: '清空全部执行日志？',
    content: '物理删除 c_ai_schedule_run 全部行，立即生效且不可恢复；保留期内的日志也会一并删除。',
    okText: '清空', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await clearScheduleRuns()
        if (r.success) {
          message.success(`已清空 ${r.data ?? 0} 条执行日志`)
          loadRunLogs(true)
        } else message.error(r.msg || '清空失败')
      } catch (e) { message.error(e.message || '清空失败') }
    }
  })
}

/** 执行日志的保留天数（上方表单 schedule.runLogRetentionDays，随配置加载回显） */
const runLogRetentionDays = computed(() => {
  const v = readForm(form.value, 'schedule.runLogRetentionDays')
  return v == null || v === '' ? 7 : v
})

/** 配置键 → schema 字段定义（按 backendKey 索引，全局唯一） */
const fieldByKey = key => FIELDS.find(f => f.backendKey === key)

/**
 * 该任务在运行状态行内展示的「专属参数」：后端按任务声明的 relatedKeys（只含 schema 可编辑的键）。
 */
const taskParamsOf = record => (record.relatedKeys || [])
  .map(fieldByKey)
  .filter(Boolean)

/** 专属参数的悬浮说明：schema 的 tips 长说明 + note 短备注，口径与「参数配置」页签一致 */
const paramTip = field => {
  const t = (field.tips ? TIPS[field.tips] : '') || ''
  return [t, field.note || ''].filter(Boolean).join('\n') || field.path
}

/**
 * 间隔就地保存（运行状态表格内联编辑）：与「暂停/恢复」同一条链路——写间隔配置键本身。
 * 保存后同步本地表单与基线，否则顶部「N 项已修改未保存」会把这次已生效的改动算成脏项。
 */
const saveInterval = async (record, ms) => {
  const field = fieldByKey(record.configKey)
  if (!field) { message.error('配置定义中找不到 ' + record.configKey); return }
  schedSavingKey.value = record.configKey
  try {
    const r = await saveConfig({ [field.group]: { [field.submitKey || field.key]: String(ms) } })
    if (r.success) {
      message.success(`「${record.name}」间隔已改为 ${fmtInterval(ms)}，下一节拍（约 10s）生效`)
      writeForm(form.value, field.path || (field.group + '.' + field.key), ms)
      initialPayload.value = buildPayload()
      loadSchedule()
    } else message.error(r.msg || '间隔保存失败')
  } catch (e) { message.error(e.message || '间隔保存失败') }
  finally { schedSavingKey.value = '' }
}

/** 暂停/恢复：写间隔配置 0 / 默认值；同步本地表单与基线，避免顶部脏计数误报 */
const toggleTaskPause = record => {
  const field = FIELDS.find(f => f.backendKey === record.configKey)
  if (!field) { message.error('配置定义中找不到 ' + record.configKey); return }
  const pausing = !record.paused
  const defVal = Number(field.def ?? 0)
  // 暂停代价说明（后端随快照下发）：停掉自愈类任务会留下不易察觉的副作用，必须在确认前讲清
  const risk = pausing && record.pauseRisk
  Modal.confirm({
    title: pausing ? `暂停「${record.name}」？` : `恢复「${record.name}」？`,
    content: h('div', [
      h('div', pausing
        ? '把间隔配置写为 0，下一个调度节拍（约 10s）生效；期间任务不再自动触发，仍可手动执行。'
        : `把间隔配置恢复为默认值（${defVal}ms），下一个调度节拍生效。`),
      risk ? h('div', { style: 'margin-top:8px;color:var(--app-warn-text)' }, '⚠ ' + risk) : null
    ]),
    okText: pausing ? '暂停' : '恢复', cancelText: '取消',
    okButtonProps: risk ? { danger: true } : null,
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

// ==================== 参数分组折叠视图（所有配置面板共用） ====================
// 每个面板都按 config-schema.json 的 sections 切组，组标题就是节标题，默认全展开。
// 「定时任务 → 参数配置」页签额外收口成**只放非任务专属的通用参数**：此前两个页签各存一份
// 同样的旋钮，49 项里 41 项两边都有（间隔 20 + 专属参数 27），改一处要猜另一处是否跟着变。
// 现按「一个参数只有一个家」收口：
//   · 任务间隔 → 「运行状态」表格的间隔列，就地编辑；
//   · 任务专属参数（后端 relatedKeys 声明）→ 「运行状态」表格的展开行；
//   · 其余通用参数（GraphRAG、重试、启动复位、缓存 TTL、版本保留…）→ 留在本页签。
// 归属数据源仍是后端 relatedKeys，与运行状态页签同一份，不存在两处说法不一致的可能。
const cfgKeyword = ref('')
/** 记「收起的组」而不是「展开的组」：默认展开时不必在换面板/搜索后重新播种展开列表，
 *  schema 新增的节也天然是展开的 */
const closedFolds = ref([])
const isFoldOpen = title => !closedFolds.value.includes(title)
const isMaintConfig = computed(() => current.value === 'maintenance' && maintTab.value === 'config')

/** 已被「运行状态」页签收走的配置键（间隔键 + 各任务 relatedKeys），参数配置页签一律不再渲染 */
const taskOwnedKeys = computed(() => {
  const s = new Set()
  for (const t of scheduleTasks.value) {
    if (t.configKey) s.add(t.configKey)
    for (const k of t.relatedKeys || []) s.add(k)
  }
  return s
})

/** 当前面板要分组的块。定时任务只在「参数配置」页签出表单（另两个页签各有自己的渲染分支），
 *  且切到运行状态/日志时这里必须返回空——否则 49 个字段会叠在表格下方，页面凭空多出 3000+px 滚动。 */
const cfgBlocks = computed(() => {
  if (current.value === 'maintenance') {
    if (!isMaintConfig.value) return []
    // 快照未到时 owned 为空 ⇒ 会把 41 项任务专属参数全放回本页签，与运行状态页签重复。
    // 等快照到齐再算，宁可先空着（面板顶部本就有 loading 态），也不能闪出一份重复的表单。
    if (!scheduleTasks.value.length) return []
    const owned = taskOwnedKeys.value
    return blocksOf('maintenance', form.value)
      .filter(b => b.type !== 'field' || !owned.has(b.field.backendKey))
  }
  return formBlocks.value
})

/**
 * 按节标题切成组：标题就是 schema 的 section，组内没有字段就整组不出现（点开是空的标题没意义）。
 * 「超时档位」卡不是字段，跟着它所在的那一节走、不计入项数。
 */
const cfgGroupsAll = computed(() => {
  const out = []
  let cur = null
  for (const blk of cfgBlocks.value) {
    if (blk.type === 'sub') {
      cur = { title: blk.title, blocks: [], fields: [] }
      out.push(cur)
    } else {
      // 无节标题的字段（section=-1）归到一张「其他」组；schema 里已把仅有的两处补了节次，
      // 这里留着是兜底——后端 schema 是运行时下发的，比前端更早就可能多出裸字段
      if (!cur) { cur = { title: '其他', blocks: [], fields: [] }; out.push(cur) }
      cur.blocks.push(blk)
      if (blk.type === 'field') cur.fields.push(blk.field)
    }
  }
  return out.filter(g => g.fields.length)
})

/** 搜索同时命中参数名、配置键与分组标题——按分组标题命中时保留整组（用户找的是"GraphRAG"这一类） */
const cfgGroups = computed(() => {
  const gs = cfgGroupsAll.value
  const kw = cfgKeyword.value.trim().toLowerCase()
  if (!kw) return gs
  return gs.filter(g =>
    g.title.toLowerCase().includes(kw) ||
    g.fields.some(f => (f.label || '').toLowerCase().includes(kw) ||
                       (f.backendKey || '').toLowerCase().includes(kw)))
})

const cfgTotalCount = computed(() => cfgGroups.value.reduce((n, g) => n + g.fields.length, 0))
/** 工具条（搜索 + 计数 + 全部展开）只服务多组面板：单组时它只是在重复那一个标题 */
const showCfgBar = computed(() => cfgGroupsAll.value.length > 1)
const allCfgOpen = computed(() => cfgGroups.value.length > 0 &&
  cfgGroups.value.every(g => !closedFolds.value.includes(g.title)))
const toggleFold = title => {
  const i = closedFolds.value.indexOf(title)
  if (i >= 0) closedFolds.value.splice(i, 1)
  else closedFolds.value.push(title)
}
const toggleAllFold = () => {
  closedFolds.value = allCfgOpen.value ? cfgGroups.value.map(g => g.title) : []
}

// ==================== 运行状态表格的展开行（任务专属参数） ====================
// 展开状态按任务名记（不用索引：刷新后行序可能变）。切换面板时清空，避免带着上次的面板状态回来。
const expandedTasks = ref([])
const onExpandChange = keys => { expandedTasks.value = keys.slice() }
watch(() => current.value, () => { expandedTasks.value = [] })

// 搜索时把命中的组一并展开：否则用户搜完看到的是一堆折叠标题，还得再点一次
watch(cfgKeyword, v => { if (v.trim()) closedFolds.value = [] })
// 换面板/换页签后分组会变，收起状态与搜索词整体重置（否则可能停在一堆已不存在的标题上）
watch(activeFormPanel, () => { closedFolds.value = []; cfgKeyword.value = '' })
// 首次进入「定时任务」面板：maintTab 默认就是 config，watch(maintTab) 不会触发，
// 但折叠标题的「影响哪个任务」依赖任务快照 ⇒ 进面板即拉一次
watch(() => current.value, v => {
  if (v === 'maintenance' && !scheduleTasks.value.length) loadSchedule()
})

/** 手动触发一次：异步执行，稍后自动刷一次状态与日志 */const doTrigger = async record => {
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

// 进页即拉一次工具全景：不只是给下方清单区用——「平台停用工具名单」的候选项也来自它，
// 而那个字段在配置表单里，不会触发工具面板的 watch（等切页签才拉会让下拉先空一段）
onMounted(() => { fetchAndFill(); loadToolInventory(); loadSkillOptions() })
</script>

<style scoped>
.dirty-hint { font-size: 12px; color: var(--app-warn-text); background: var(--app-warn-weak); border-radius: 6px; padding: 3px 10px; }
/* .head-hint-plain 已提到 app.css 作为全局共用样式（供应商/知识库/技能/MCP 页也用它） */
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
/* 运行状态页签：整块不再随内容长高，滚动交给内部表格。
   .set-content 是 flex:1 但无固定高度 ⇒ 它跟着内容长高，overflow-y 永不触发；
   所以这里让 .set-content 自身在 fill 模式下不再滚动，由表格体限高来吸收多余高度。 */
.set-panel--fill { overflow: hidden; }
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
/* API 密钥（6.5） */
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
/* 平台级 MCP 接入点：同一张卡里第二、三个 label 之间要留出间距，否则两块代码框贴成一片 */
.mcp-entry { margin-top: 14px; }
.mcp-entry .key-usage-label + .key-code + .key-usage-label { margin-top: 12px; }
/* mcp.json 是多行 pre：.key-code 那套 code 选择器不管 pre，这里补上，并给右上角复制按钮让位 */
.key-code-pre {
  margin: 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px;
  line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--app-text);
}
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
.key-field-hint { margin-top: 6px; font-size: 12px; color: var(--app-text3); line-height: 1.6; }
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
/* 定时任务页签条（风格对齐智能体 hub 的 a-tabs：同样字号/内边距/分割线） */
.sched-tabs { margin: 0 4px; }
.sched-tabs :deep(.ant-tabs-nav) { margin-bottom: 0; }
.sched-tabs :deep(.ant-tabs-tab) { font-size: 13px; padding: 10px 2px; }
.sched-tabs :deep(.ant-tabs-nav::before) { border-color: var(--app-border); }
.sched-tabs :deep(.ant-tabs-content-holder) { display: none; }
.sched-pane { padding: 12px 0 0; }
/* 定时任务·运行状态：表格体高度由 tableScrollY 按容器真实高度限定，表头由 antd 自己固定。
   不要给 th 加 position:sticky——设了 scroll.y 后 antd 会把表头拆成独立的
   .ant-table-header，两份表格出现行高错位（表格里还有一张隐藏的 measure row，
   tr 数量比数据行多一行，Playwright 的可点性检查会被它误判成"被遮挡"）。 */
/* 定时任务·运行状态：展开行里的任务专属参数。两列网格铺开——单列会让 6 项参数拉得很长 */
.sched-expanded { padding: 4px 0 10px; }
.sched-expanded-title { margin-top: 0; }
.sched-expanded-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 4px 20px;
}
.sched-no-params { padding: 6px 2px; }
.sched-param-row { display: flex; align-items: center; gap: 8px; min-height: 26px; }
.sched-param-label { font-size: 12px; color: var(--app-text3); flex: none; max-width: 150px; line-height: 1.3; }
/* 各配置面板共用的分节折叠块（组标题 = config-schema.json 的 section） */
/* 页签条自身不留下边距（它的余量算进了运行状态表格高度），折叠区自己顶开一条缝 */
.cfg-fold-wrap { margin-top: 14px; }
.cfg-fold-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.cfg-fold { border-bottom: 1px solid var(--app-border); }
.cfg-fold:last-child { border-bottom: none; }
.cfg-fold-head { display: flex; align-items: center; gap: 8px; padding: 9px 2px; }
.cfg-fold-n { font-size: 11px; color: var(--app-text3); background: var(--app-panel-2); border-radius: 999px; padding: 1px 8px; }
.sched-cfgkey { font-size: 11px; margin-top: 2px; }
.sched-cfgkey code { font-size: 11px; color: var(--app-text3); }

/* 工具清单（工具调用面板 · 最底部）：运行时注册的全景只读视图 + 族级启用开关，敏=有副作用工具；
   嵌在 set-card 内部，用顶部分隔线与上方表单区分 */
.tool-inv { margin: 14px 0 0; padding: 14px 0 0; border-top: 1px solid var(--app-border); }
.tool-inv-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.tool-inv-group { padding: 10px 0; border-top: 1px solid var(--app-border); }
.tool-inv-group:first-of-type { border-top: none; padding-top: 4px; }
.tool-inv-family { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.tool-inv-family-name { font-weight: 500; }
.tool-inv-state { font-size: 12px; padding: 0 8px; border-radius: 4px;
  background: var(--app-warn-weak); color: var(--app-warn-text); }
.tool-inv-state.on { background: var(--app-ok-weak); color: var(--app-ok); }
.tool-inv-items { display: flex; flex-wrap: wrap; gap: 6px; }
.tool-inv-chip { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px;
  border-radius: 5px; background: var(--app-panel-2); font-size: 12px; cursor: default; }
.tool-inv-chip.sensitive { border: 1px solid var(--app-danger-border); }
.tool-inv-chip.is-off { color: var(--app-text3); background: var(--app-panel); border: 1px dashed var(--app-border); }
.tool-inv-s { font-size: 10px; color: var(--app-danger-text); }
.skill-inv-n { font-size: 11px; color: var(--app-text3); background: var(--app-panel-2); border-radius: 999px; padding: 1px 8px; }
.sched-err {
  display: inline-block; margin-left: 6px; width: 16px; height: 16px; line-height: 16px;
  border-radius: 50%; text-align: center; font-size: 11px; font-weight: 600;
  color: var(--app-danger); background: var(--app-danger-weak); cursor: help;
}
.sched-ok { color: var(--app-ok); }
/* 暂停代价提示标记：停掉该任务会留下不易察觉的副作用（挂起 run 不落终态等），鼠标悬停看说明 */
.sched-risk {
  display: inline-block; margin-left: 4px; width: 14px; height: 14px; line-height: 14px;
  border-radius: 50%; text-align: center; font-size: 10px; font-weight: 600;
  color: var(--app-warn-text); background: var(--app-warn-weak); cursor: help;
}
.sched-bad { color: var(--app-danger); }
.audit-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.audit-pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; justify-content: flex-end; }
</style>
