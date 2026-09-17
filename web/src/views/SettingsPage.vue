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

    <div class="set-body">
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
            <button v-if="!NO_RESET.includes(current)" class="app-btn ghost" :disabled="resettingKey === current" @click="onResetGroup(current)">
              恢复本组默认
            </button>
          </div>

          <a-alert v-for="(al, ai) in (PANEL_ALERTS[current] || [])" :key="ai" :type="al.type" show-icon
                   style="margin-bottom:12px" :message="al.msg" />

          <div class="app-card set-card">
            <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }" @submit.prevent>
              <template v-for="(blk, i) in blocksOf(current, !advMode)" :key="i">
                <div v-if="blk.type === 'sub' && current !== 'skills'" class="cfg-sub">{{ blk.title }}</div>

                <!-- 向量模型组：索引状态与重嵌入（只读状态 + 手动触发） -->
                <template v-if="current === 'embedding' && blk.type === 'sub' && blk.title.includes('索引状态')">
                  <a-form-item>
                    <template #label>
                      <a-tooltip :title="TIPS.embeddingDimensions" placement="top">当前索引维度 <question-circle-outlined class="tip-icon" /></a-tooltip>
                    </template>
                    <span v-if="embeddingDimensions" style="color:var(--app-text2)">{{ embeddingDimensions }} 维</span>
                    <span v-else style="color:var(--app-text3)">未记录（首次重嵌入完成后自动记录）</span>
                  </a-form-item>
                  <a-form-item label="重嵌入状态">
                    <div>
                      <span v-if="reembed.status === 'running'" style="color:var(--app-accent)">进行中：{{ reembed.done }} / {{ reembed.total }} 块<span v-if="reembed.failed" style="color:var(--app-danger)">（失败 {{ reembed.failed }}）</span></span>
                      <span v-else-if="reembed.status === 'done'" style="color:var(--app-ok)">已完成：{{ reembed.done }} 块<span v-if="reembed.failed" style="color:var(--app-danger)">（失败 {{ reembed.failed }}，可重试补齐）</span></span>
                      <span v-else-if="reembed.status === 'failed'" style="color:var(--app-danger)">失败：{{ reembed.error }}（已完成 {{ reembed.done }} 块，可重试）</span>
                      <span v-else style="color:var(--app-text3)">未运行</span>
                      <button class="app-btn ghost small" :disabled="reembedTriggering" @click="doTriggerReembed">{{ reembedTriggering ? '启动中…' : '手动重嵌入' }}</button>
                      <button class="app-btn ghost small" @click="refreshReembedStatus">刷新</button>
                    </div>
                    <div v-if="reembed.status !== 'idle'" class="reembed-meta">
                      <span v-if="reembed.newDim">维度：{{ reembed.oldDim || '未知' }} → {{ reembed.newDim }}</span>
                      <span v-if="reembedElapsed" style="margin-left:12px">耗时 {{ reembedElapsed }}</span>
                      <span v-if="reembed.indexed" style="margin-left:12px">索引内 {{ reembed.indexed }} 块<span v-if="reembed.status === 'done' && reembed.indexed < reembed.done" style="color:var(--app-danger)">（少于成功写入数，建议再跑一次）</span></span>
                    </div>
                  </a-form-item>
                </template>

                <!-- MCP 面板：工具栏（搜索/刷新/重连/添加）+ 总开关行 + 服务卡片 + 高级 JSON 编辑 -->
                <template v-if="current === 'mcp' && blk.type === 'sub'">
                  <div class="key-bar">
                    <div class="key-bar-left">
                      <a-input v-model:value="mcpKeyword" placeholder="搜索服务名称 / 地址" allow-clear size="small" class="res-search">
                        <template #prefix><search-outlined class="res-search-ic" /></template>
                      </a-input>
                      <span class="key-stat">
                        共 <b>{{ mcpCards.length }}</b> 个服务 · 已连接 <b>{{ mcpConnectedCount }}</b>
                        <span v-if="mcpCheckedAt" class="key-dim">· 更新于 {{ mcpCheckedAt }}</span>
                      </span>
                    </div>
                    <div class="key-bar-actions">
                      <a-tooltip title="刷新连接状态">
                        <button class="app-icon-btn" aria-label="刷新连接状态" :disabled="mcpLoading" @click="loadMcpStatus"><reload-outlined /></button>
                      </a-tooltip>
                      <button class="app-btn ghost small" :disabled="mcpReloading" @click="doReloadMcp">
                        {{ mcpReloading ? '重连中…' : '全部重连' }}
                      </button>
                      <button class="app-btn small" @click="openMcpAdd">＋ 添加服务</button>
                    </div>
                  </div>

                  <!-- 总开关：配置项，改动后点右上角「保存配置」生效（与列表增删的即时保存不同） -->
                  <div class="mcp-switch-row">
                    <a-switch v-model:checked="mcpEnabled" size="small" />
                    <span class="mcp-switch-label">MCP 总开关</span>
                    <span class="key-dim">开启后连接下方服务、把工具注册给模型；修改后点右上角「保存配置」生效</span>
                  </div>

                  <a-alert v-if="!mcpStatus.enabled" type="warning" show-icon style="margin-bottom:12px"
                           message="MCP 总开关未开启（或修改尚未保存）"
                           description="开启上方「MCP 总开关」并保存后，才会连接下方服务、并把它们的工具提供给模型。" />
                  <a-alert v-if="mcpJsonError" type="error" show-icon style="margin-bottom:12px"
                           message="原始 JSON 解析失败，服务列表可能显示不全"
                           :description="mcpJsonError + '；请在下方「高级编辑（原始 JSON）」中修正后保存。'" />

                  <div v-if="!mcpCards.length && !mcpJsonError" class="key-empty">
                    <div class="key-empty-title">还没有 MCP 服务</div>
                    <div class="key-empty-desc">
                      接入外部 MCP 服务（如时间工具、内部系统查询），它的工具会自动注册给模型，与内置工具一样可被调用。
                    </div>
                    <button class="app-btn small" @click="openMcpAdd">添加第一个服务</button>
                  </div>
                  <div v-else-if="mcpCards.length && !mcpFilteredCards.length" class="key-empty">
                    <div class="key-empty-title">没有匹配的服务</div>
                    <div class="key-empty-desc">没有名称或地址包含「{{ mcpKeyword }}」的服务。</div>
                    <button class="app-btn ghost small" @click="mcpKeyword = ''">清除搜索</button>
                  </div>

                  <template v-if="mcpFilteredCards.length">
                    <div v-for="s in mcpFilteredCards" :key="s.name" class="mcp-card">
                      <div class="mcp-card-head">
                        <span class="mcp-dot" :class="s.stateCls"></span>
                        <span class="mcp-card-name">{{ s.name }}</span>
                        <span class="mcp-state" :class="s.stateCls" :title="s.runtimeState || ''">{{ s.stateText }}</span>
                        <div class="mcp-card-actions">
                          <button v-if="s.connected" class="app-link-btn" @click="toggleMcpTools(s)">
                            {{ mcpExpanded === s.name ? '收起工具' : '查看工具' }}
                          </button>
                          <button class="app-link-btn" @click="openMcpEdit(s)">编辑</button>
                          <a-popconfirm title="从配置中移除该服务？" ok-text="移除" cancel-text="取消" @confirm="removeMcpServer(s)">
                            <button class="app-link-btn danger">移除</button>
                          </a-popconfirm>
                        </div>
                      </div>
                      <div class="mcp-card-sub">
                        <span class="mcp-type-pill">{{ s.type }}</span>
                        <span class="mcp-url" :title="s.url">{{ s.url }}</span>
                      </div>
                      <div v-if="mcpExpanded === s.name" class="mcp-tools">
                        <div v-for="t in s.tools" :key="t.name" class="mcp-tool">
                          <code>{{ t.name }}</code>
                          <span class="mcp-tool-desc">{{ t.description || '（无描述）' }}</span>
                        </div>
                        <div v-if="!s.tools.length" class="key-dim">该服务未提供任何工具</div>
                      </div>
                    </div>
                  </template>

                  <!-- 原始 JSON：界面操作会自动维护，保留给排障与批量编辑；随全局「保存配置」生效 -->
                  <div class="res-fold">
                    <div class="fold-head" @click="mcpAdvancedOpen = !mcpAdvancedOpen">
                      <span class="fold-caret">{{ mcpAdvancedOpen ? '▾' : '▸' }}</span> 高级编辑（原始 JSON）
                      <span class="key-dim">（一般无需手动编辑）</span>
                    </div>
                    <div v-if="mcpAdvancedOpen" class="mcp-advanced-body">
                      <a-textarea v-model:value="mcpServersJson" :rows="5"
                                  placeholder='[{"name":"时间工具","url":"http://127.0.0.1:8931","type":"streamable"}]' />
                      <div class="key-dim mcp-json-hint">JSON 数组，每项 {name,url,type}；type 可选 streamable/sse；连接失败自动跳过。修改后需保存配置生效。</div>
                    </div>
                  </div>

                  <!-- 添加 / 编辑服务弹窗：先测再存，不用手写 JSON -->
                  <a-modal v-model:open="mcpFormOpen" :title="mcpForm.mode === 'add' ? '添加 MCP 服务' : '编辑 MCP 服务'"
                           :footer="null" :width="580">
                    <a-form layout="vertical">
                      <a-form-item label="名称" required>
                        <a-input v-model:value="mcpForm.name" placeholder="如：时间工具 / 内部系统查询" :maxlength="40" />
                      </a-form-item>
                      <a-form-item label="服务地址" required>
                        <a-input v-model:value="mcpForm.url" placeholder="http://127.0.0.1:8931 或 http://host:port/mcp" />
                      </a-form-item>
                      <a-form-item label="传输类型">
                        <a-radio-group v-model:value="mcpForm.type" size="small" button-style="solid">
                          <a-radio-button value="streamable">streamable（推荐）</a-radio-button>
                          <a-radio-button value="sse">SSE</a-radio-button>
                        </a-radio-group>
                      </a-form-item>
                      <a-form-item v-if="mcpProbe.done" label="测试结果">
                        <div v-if="mcpProbe.available" class="mcp-probe-ok">
                          连接正常，提供 {{ mcpProbe.tools.length }} 个工具
                        </div>
                        <div v-else class="mcp-probe-bad">连接失败：{{ mcpProbe.error }}</div>
                        <div v-if="mcpProbe.available && mcpProbe.tools.length" class="mcp-tools" style="margin-top:6px">
                          <div v-for="t in mcpProbe.tools" :key="t.name" class="mcp-tool">
                            <code>{{ t.name }}</code>
                            <span class="mcp-tool-desc">{{ t.description || '（无描述）' }}</span>
                          </div>
                        </div>
                      </a-form-item>
                    </a-form>
                    <div class="key-modal-foot">
                      <button class="app-btn ghost" :disabled="mcpProbe.loading" @click="testMcpForm">
                        {{ mcpProbe.loading ? '测试中…' : '测试连接' }}
                      </button>
                      <button class="app-btn" :disabled="mcpSaving" @click="submitMcpForm">
                        {{ mcpSaving ? '保存中…' : (mcpForm.mode === 'add' ? '添加并连接' : '保存并重连') }}
                      </button>
                    </div>
                  </a-modal>
                </template>

                <!-- 常规字段（SchemaField 全量复用：类型控件/条件显隐/参数说明）；
                     skills 面板字段收进列表底部「技能设置」折叠区、MCP 开关与 JSON 为面板内自定义呈现 -->
                <template v-else-if="blk.type === 'field' && current !== 'skills' && !MCP_CUSTOM_FIELDS.has(blk.field.path)">
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
                  <!-- 厂商预设：紧跟问答模型名之后 -->
                  <a-form-item v-if="current === 'chat' && blk.field.group === 'chat' && blk.field.key === 'model'">
                    <template #label>
                      <a-tooltip :title="TIPS.chatPreset" placement="top">厂商预设 <question-circle-outlined class="tip-icon" /></a-tooltip>
                    </template>
                    <a-select v-model:value="chatPreset" style="width:340px" :options="chatPresetOptions"
                              placeholder="选择厂商自动填充网关地址与补全路径" @change="onChatPresetChange" />
                  </a-form-item>
                </template>
              </template>

              <!-- 语义缓存运维：运行统计 + 手动清空 -->
              <a-form-item v-if="current === 'semanticCache'" label="缓存状态">
                <div>
                  <span v-if="cacheStats.count != null" style="color:var(--app-text2)">
                    已缓存 <b style="color:var(--app-accent);font-weight:500">{{ cacheStats.count }}</b> 条（上限 {{ form.semanticCache?.maxEntries ?? '—' }}）
                  </span>
                  <span v-else style="color:var(--app-text3)">统计未加载</span>
                  <a-popconfirm title="清空后缓存重新积累，确定清空？" ok-text="清空" cancel-text="取消" @confirm="doClearCache">
                    <button class="app-btn danger small" style="margin-left:12px" :disabled="cacheClearing">{{ cacheClearing ? '清空中…' : '清空语义缓存' }}</button>
                  </a-popconfirm>
                  <button class="app-btn ghost small" style="margin-left:8px" @click="refreshCacheStats">刷新</button>
                </div>
                <div class="reembed-meta">清空后按提问重新积累；知识库变更（解析/删除/回滚/启停用）时后端会自动整体清空，一般无需手动操作。</div>
              </a-form-item>

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

                <a-table v-else :data-source="filteredKeys" size="small" row-key="id" :pagination="false">
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
                      <li>不再使用建议「停用」而非删除：停用可保留审计线索，删除记录即消失</li>
                    </ul>
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

              <!-- 技能（Skills）：目录 + SKILL.md 的纯文本能力包，模型按需读取后照做 -->
              <template v-if="current === 'skills'">
                <div class="key-bar">
                  <div class="key-bar-left">
                    <a-input v-model:value="skillKeyword" placeholder="搜索技能名称 / 描述" allow-clear size="small" class="res-search">
                      <template #prefix><search-outlined class="res-search-ic" /></template>
                    </a-input>
                    <span class="key-stat">共 <b>{{ skills.length }}</b> 个技能 · 生效中 <b>{{ activeSkillCount }}</b></span>
                  </div>
                  <div class="key-bar-actions">
                    <a-tooltip title="刷新列表">
                      <button class="app-icon-btn" aria-label="刷新技能列表" :disabled="skillsLoading" @click="loadSkills"><reload-outlined /></button>
                    </a-tooltip>
                    <button class="app-btn ghost small" @click="openInstallSkill">从 URL 安装</button>
                    <button class="app-btn small" @click="openCreateSkill">＋ 新建技能</button>
                  </div>
                </div>

                <div v-if="!skills.length" class="key-empty">
                  <div class="key-empty-title">还没有技能</div>
                  <div class="key-empty-desc">
                    技能用来固化「这类问题该怎么做」的做法——步骤、输出格式、禁忌。模型按需读取后照做，不必每次在提问里重复交代。
                  </div>
                  <button class="app-btn small" @click="openCreateSkill">新建第一个技能</button>
                </div>
                <div v-else-if="!filteredSkills.length" class="key-empty">
                  <div class="key-empty-title">没有匹配的技能</div>
                  <div class="key-empty-desc">没有名称或描述包含「{{ skillKeyword }}」的技能。</div>
                  <button class="app-btn ghost small" @click="skillKeyword = ''">清除搜索</button>
                </div>

                <!-- 卡片列表：按来源分组（用户 / 内置）；版本与哈希收进「查看」弹窗 -->
                <template v-else>
                  <template v-for="g in skillGroups" :key="g.title">
                    <div class="res-group-title">{{ g.title }} ({{ g.list.length }})</div>
                    <div class="skill-grid">
                      <div v-for="s in g.list" :key="s.dirName" class="skill-card">
                        <div class="skill-card-head">
                          <span class="skill-card-name" :title="s.name">{{ s.name }}</span>
                          <span v-if="s.disabled" class="app-pill warn key-tag">已停用</span>
                          <span v-else class="app-pill ok key-tag">生效中</span>
                        </div>
                        <div class="skill-card-desc" :class="{ 'skill-desc-warn': !s.description }" :title="s.description || ''">
                          {{ s.description || '（未填描述：模型不会主动读取它）' }}
                        </div>
                        <div class="skill-card-foot">
                          <button class="app-link-btn" @click="viewSkill(s)">查看</button>
                          <button class="app-link-btn" @click="toggleSkill(s)">{{ s.disabled ? '启用' : '停用' }}</button>
                          <a-popconfirm v-if="s.source === 'user'" title="删除该技能？文件将同时删除" ok-text="删除" cancel-text="取消" @confirm="delSkill(s)">
                            <button class="app-link-btn danger">删除</button>
                          </a-popconfirm>
                        </div>
                      </div>
                    </div>
                  </template>
                </template>

                <!-- 技能设置：参数字段收进折叠区，默认收起（总开关 / 目录 / 注入与读取限制） -->
                <div class="res-fold">
                  <div class="fold-head" @click="skillCfgOpen = !skillCfgOpen">
                    <span class="fold-caret">{{ skillCfgOpen ? '▾' : '▸' }}</span> 技能设置
                    <span class="key-dim">（总开关{{ skillEnabled ? '已开启' : '未开启' }} · 目录 · 注入与读取限制）</span>
                  </div>
                  <div v-if="skillCfgOpen" class="skill-cfg-body">
                    <div class="skill-dir-tip">
                      技能目录 <code>{{ skillDir || '—' }}</code>：每个子目录放一个 <code>SKILL.md</code> 就是一个技能
                      （frontmatter 写 name / description / version，正文写具体做法），与内置技能同名时用户目录优先。
                      技能只作为文本指令注入，<b>不会执行目录里的任何脚本</b>。
                    </div>
                    <template v-for="(blk, i) in blocksOf('skills')" :key="'sk' + i">
                      <div v-if="blk.type === 'sub'" class="cfg-sub">{{ blk.title }}</div>
                      <SchemaField v-else-if="blk.type === 'field'" :field="blk.field" :form="form" :tips="TIPS" @change="onFieldChange" />
                    </template>
                  </div>
                </div>

                <!-- 新建技能弹窗 -->
                <a-modal v-model:open="skillCreateOpen" title="新建技能" :footer="null" :width="720">
                  <a-form layout="vertical">
                    <a-form-item label="技能名" required>
                      <a-input v-model:value="skillForm.name" placeholder="如：报表字段命名规范（支持中英文、数字、下划线、连字符）" :maxlength="64" />
                    </a-form-item>
                    <a-form-item label="描述" required>
                      <a-input v-model:value="skillForm.description" placeholder="一句话说明什么场景用它——模型靠这句判断要不要读取" :maxlength="200" />
                    </a-form-item>
                    <a-form-item label="技能内容（Markdown）">
                      <a-textarea v-model:value="skillForm.content" :rows="12" />
                    </a-form-item>
                  </a-form>
                  <div class="key-modal-foot">
                    <button class="app-btn ghost" @click="skillCreateOpen = false">取消</button>
                    <button class="app-btn" :disabled="skillCreating" @click="submitCreateSkill">{{ skillCreating ? '创建中…' : '创建' }}</button>
                  </div>
                </a-modal>

                <!-- 查看技能弹窗 -->
                <a-modal v-model:open="skillViewOpen" :title="'技能：' + skillView.name" :footer="null" :width="760">
                  <div class="skill-view-meta">
                    <span>目录 <code>{{ skillView.dirName }}</code></span>
                    <span>版本 {{ skillView.version || '—' }}</span>
                    <span>哈希 <code>{{ skillView.hash }}</code></span>
                    <span>来源 {{ skillView.source === 'builtin' ? '内置' : '用户' }}</span>
                    <span class="skill-view-toggle">
                      <a-switch size="small" :checked="!skillView.disabled" :loading="skillViewToggling" @change="toggleSkillFromView" />
                      <span class="key-dim">{{ skillView.disabled ? '已停用' : '生效中' }}</span>
                    </span>
                  </div>
                  <div class="skill-view-body md" v-html="renderMd(skillView.content)"></div>
                </a-modal>
                <!-- 从 URL 安装技能弹窗 -->
                <a-modal v-model:open="skillInstallOpen" title="从 URL 安装技能" :footer="null" :width="620">
                  <a-form layout="vertical">
                    <a-form-item label="技能文件地址（SKILL.md 原文）" required>
                      <a-input v-model:value="skillInstallForm.url"
                               placeholder="https://…/SKILL.md（GitHub 请用 raw 链接，不要用网页链接）" />
                    </a-form-item>
                    <a-form-item label="技能名（可选）">
                      <a-input v-model:value="skillInstallForm.name"
                               placeholder="留空则用文件 frontmatter 里的 name" :maxlength="64" />
                    </a-form-item>
                  </a-form>
                  <div class="skill-dir-tip" style="margin-bottom:0">
                    安装时会校验：地址必须是 http/https、内容必须带 frontmatter（name + description）。
                    技能内容只作为文本指令保存，<b>不会执行文件里的任何脚本</b>。
                  </div>
                  <div class="key-modal-foot">
                    <button class="app-btn ghost" @click="skillInstallOpen = false">取消</button>
                    <button class="app-btn" :disabled="skillInstalling" @click="doInstallSkill">
                      {{ skillInstalling ? '安装中…' : '安装' }}
                    </button>
                  </div>
                </a-modal>
              </template>

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
import { getConfig, saveConfig, resetConfig, checkRerank, checkKeywordEngine, getAnswerCacheStats, clearAnswerCache,
         getReembedStatus, triggerReembed, probeConnectivity,
         listApiKeys, createApiKey, setApiKeyDisabled, deleteApiKey, renameApiKey, updateApiKeyShare,
         listSkills, getSkillDetail, createSkill, setSkillDisabled, deleteSkill, installSkillFromUrl } from '../api'
import ShareScopeModal from './ShareScopeModal.vue'
import { renderMd } from '../utils/markdown'
import SchemaField from '../components/SchemaField.vue'
import { FIELDS, PANELS, TIPS, blocksOf, buildDefaultForm, readForm, writeForm, corePanels, hiddenFieldCount } from '../configSchema'
import { getMcpStatus, reloadMcp, probeMcp } from '../api'

// 分组导航（沿用旧版锚点短名）
const NAV_LABELS = {
  chat: '智能问答模型', vision: '视觉模型', chunk: '文档解析', embedding: '向量模型', retrieval: '检索设置',
  context: '上下文控制', deepReasoning: '深度思考', tool: '工具调用', mcp: 'MCP 外部工具',
  semanticCache: '语义缓存', ratelimit: '接口限流', maintenance: '定时维护', apiKey: 'API Key 管理', skills: '技能 Skills',
  agent: '并行检索'
}
const groupLabel = key => NAV_LABELS[key] || key
const current = ref('chat')
const currentPanel = computed(() => PANELS.find(p => p.key === current.value))

// 高级设置模式（默认关，持久化）：关=只显示核心项（模型服务 + 回答行为），开=显示全部调优参数。
// 对标成熟产品的设置体系——全局设置保持精简，细粒度参数按需展开，避免设置页被 180+ 项淹没。
const advMode = ref(localStorage.getItem('app_adv_settings') === '1')
const navPanels = computed(() => advMode.value ? PANELS : corePanels())
const hiddenHere = computed(() => advMode.value ? 0 : hiddenFieldCount(current.value))
watch(advMode, v => {
  localStorage.setItem('app_adv_settings', v ? '1' : '0')
  // 关掉高级模式时，若当前分组已不在导航中（纯高级分组），切到第一个核心分组，避免停在空白页
  if (!v && !navPanels.value.some(p => p.key === current.value)) {
    current.value = navPanels.value[0]?.key || current.value
  }
})

// 无「恢复本组默认」的分组（API Key/技能由文件与数据库管理，与配置默认值无关）
const NO_RESET = ['embedding', 'maintenance', 'apiKey', 'skills']

// 分组顶部说明（与旧版文案一致）
const PANEL_ALERTS = {
  chat: [{ type: 'info', msg: '问答模型支持跨厂商热切换：修改网关地址/API Key/模型名保存即生效免重启，API Key 以 RSA 加密入库。' }],
  vision: [{ type: 'info', msg: '视觉模型用于文档图片与用户图片的描述识别；关闭后图片仅展示、内容不进检索与引用。' }],
  chunk: [{ type: 'info', msg: '上传大小上限保存即生效；分块/图片上限只对重新解析/新上传文档生效，超限按保护策略截断入库。' }],
  embedding: [{ type: 'warning', msg: '向量模型热切换说明：不同模型的向量数学上不可迁移。保存时会先探测新配置并校验维度，通过后自动重建索引并后台全量重嵌入；任务开始即清空语义缓存。重嵌入期间向量检索自动降级关键词路，服务不中断。' }],
  retrieval: [
    { type: 'info', msg: '融合分 = 向量权重×向量相似度 + 关键词权重×命中率 + 标题命中奖励。保存后立即生效。' },
    { type: 'info', msg: '重排：OpenAI 兼容 /v1/rerank 服务。未启动或不可用时自动回退融合分排序，不影响正常问答。' }
  ],
  context: [{ type: 'info', msg: '预算 = min(模型窗口×安全系数−输出限制, 成本上限)，知识块按相关度降序累积填充，超出自动裁剪。保存后立即生效。' }],
  deepReasoning: [{ type: 'info', msg: '深度思考：AI 先流式展示思维链，思考末尾输出检索计划（精化 query + 子问题）多路并行检索合并后回答。失败自动降级。' }],
  semanticCache: [{ type: 'info', msg: '命中相似问题（≥阈值）时直接复用历史回答：省检索与 LLM 成本、秒级返回。知识库变更时自动整体清空，不会用过期答案。' }],
  ratelimit: [{ type: 'info', msg: 'Redis 固定窗口计数，按用户（匿名按 IP）限频，超限返回 429。限频设为 0 表示不限流；Redis 不可用时自动放行。' }],
  maintenance: [{ type: 'info', msg: '后台定时任务参数，保存即生效。周期填 ≤0 表示暂停该任务；清理类任务只删超期数据。' }],
  apiKey: [{ type: 'info', msg: '给外部系统发放调用问答能力的密钥：调用方在请求头带 X-Api-Key 即可（免平台 token）。Key 权限固定为问答链路，管理端点一律拒绝。' }],
  skills: [{ type: 'info', msg: '技能 = 一段可复用的"做法说明"（步骤/格式/禁忌）。系统提示里只放技能名与描述，模型判断某个问题属于某技能领域时，才去读取它的完整内容——所以技能装得多也不会拖慢每次问答。需在下方「技能设置」中开启总开关后生效。' }],
  agent: [{ type: 'info', msg: '并行检索：把一个问题拆成多个检索视角并行执行（各自检索 + 提炼要点）再汇总，改善复杂问题"召回不全"。若当前智能体已配置子智能体，则改为委派子智能体执行——各按自己的知识库范围与角色视角检索。基于 Spring AI Alibaba 的 StateGraph 编排，失败会自动降级为原有单路检索，不影响问答可用性。' }],
}

const loading = ref(false)
const saving = ref(false)

// ==================== 表单状态与回填 ====================
const form = ref({ ...buildDefaultForm(),
  intent: { enabled: false, timeoutMillis: 3000, model: '', prompt: '', chatPrompt: '' } })

const fetchAndFill = async () => {
  loading.value = true
  try {
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
      const em = d.embedding || {}
      embeddingDimensions.value = em.dimensions?.value || ''
      refreshCacheStats()
      refreshReembedStatus()
      loadMcpStatus()
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
      refreshReembedStatus()
      if (payload.mcp) loadMcpStatus()   // 改了 MCP 配置：按新配置重连后刷新状态
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
const probeStates = ref({
  chat: { loading: false, result: null },
  vision: { loading: false, result: null },
  embedding: { loading: false, result: null },
  rerank: { loading: false, result: null },
  keyword: { loading: false, result: null }
})
const probeLabels = { chat: '对话模型', vision: '视觉模型', embedding: '向量模型', rerank: '重排服务', keyword: '关键词引擎' }
const PROBE_BY_KEY = {
  'chat.baseUrl': 'chat', 'vision.baseUrl': 'vision', 'embedding.baseUrl': 'embedding',
  'keyword.baseUrl': 'keyword', 'rerank.baseUrl': 'rerank'
}
const probeKey = f => PROBE_BY_KEY[f.group + '.' + f.key] || ''

const doProbe = async group => {
  const s = probeStates.value[group]
  if (!s || s.loading) return
  const f = form.value
  const payload = { group }
  if (group === 'chat') {
    Object.assign(payload, { baseUrl: f.chat.baseUrl, apiKey: f.chat.apiKey, model: f.chat.model, path: f.chat.completionsPath })
  } else if (group === 'vision') {
    Object.assign(payload, { baseUrl: f.vision.baseUrl, apiKey: f.vision.apiKey, model: f.vision.model })
  } else if (group === 'embedding') {
    Object.assign(payload, { baseUrl: f.embedding.baseUrl, apiKey: f.embedding.apiKey, model: f.embedding.model, path: f.embedding.embeddingsPath })
  } else if (group === 'rerank') {
    Object.assign(payload, { baseUrl: f.retrieval.rerank.baseUrl, model: f.retrieval.rerank.model })
  } else if (group === 'keyword') {
    Object.assign(payload, { baseUrl: f.keyword.baseUrl, apiKey: f.keyword.apiKey })
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
  () => [
    form.value.chat.baseUrl, form.value.chat.completionsPath, form.value.chat.model, form.value.chat.apiKey,
    form.value.vision.baseUrl, form.value.vision.model, form.value.vision.apiKey,
    form.value.embedding.baseUrl, form.value.embedding.embeddingsPath, form.value.embedding.model, form.value.embedding.apiKey,
    form.value.retrieval.rerank.baseUrl, form.value.retrieval.rerank.model,
    form.value.keyword.baseUrl, form.value.keyword.apiKey
  ],
  () => {
    for (const k of Object.keys(probeStates.value)) probeStates.value[k].result = null
  }
)

// ==================== 枚举变更校验 / 厂商预设 ====================
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

const chatPreset = ref('custom')
const chatPresets = {
  deepseek: { baseUrl: 'https://api.deepseek.com', completionsPath: '/v1/chat/completions' },
  zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas', completionsPath: '/v4/chat/completions' },
  dashscope: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode', completionsPath: '/v1/chat/completions' },
  moonshot: { baseUrl: 'https://api.moonshot.cn', completionsPath: '/v1/chat/completions' },
  ark: { baseUrl: 'https://ark.cn-beijing.volces.com/api', completionsPath: '/v3/chat/completions' },
  hunyuan: { baseUrl: 'https://api.hunyuan.cloud.tencent.com', completionsPath: '/v1/chat/completions' },
  qianfan: { baseUrl: 'https://qianfan.baidubce.com', completionsPath: '/v2/chat/completions' },
  minimax: { baseUrl: 'https://api.minimax.chat', completionsPath: '/v1/chat/completions' },
  siliconflow: { baseUrl: 'https://api.siliconflow.cn', completionsPath: '/v1/chat/completions' },
  ollama: { baseUrl: 'http://localhost:11434', completionsPath: '/v1/chat/completions' }
}
const chatPresetOptions = [
  { value: 'custom', label: '自定义 / 保持现状' },
  { value: 'deepseek', label: 'DeepSeek（api.deepseek.com）' },
  { value: 'zhipu', label: '智谱 GLM（open.bigmodel.cn）' },
  { value: 'dashscope', label: '阿里百炼 Qwen（dashscope）' },
  { value: 'moonshot', label: 'Kimi 月之暗面（moonshot）' },
  { value: 'ark', label: '豆包/火山方舟（volces.com）' },
  { value: 'hunyuan', label: '腾讯混元（hunyuan）' },
  { value: 'qianfan', label: '百度千帆 v2（qianfan）' },
  { value: 'minimax', label: 'MiniMax（minimax.chat）' },
  { value: 'siliconflow', label: 'SiliconFlow 硅基流动（多模型聚合）' },
  { value: 'ollama', label: '本地 Ollama（localhost:11434）' }
]
const onChatPresetChange = val => {
  const p = chatPresets[val]
  if (!p) return
  form.value.chat.baseUrl = p.baseUrl
  form.value.chat.completionsPath = p.completionsPath
  message.info('已填充网关地址与补全路径，请补齐 API Key 与模型名后保存')
}

// ==================== 语义缓存统计与清空 ====================
const cacheStats = ref({ count: null })
const cacheClearing = ref(false)
const refreshCacheStats = () => {
  getAnswerCacheStats().then(r => { if (r.success) cacheStats.value = r.data }).catch(() => {})
}
const doClearCache = async () => {
  cacheClearing.value = true
  try {
    const r = await clearAnswerCache()
    if (r.success) { cacheStats.value = { count: 0 }; message.success('答案缓存已清空') }
    else message.error(r.msg || '清空失败')
  } catch (e) { message.error(e.message || '清空失败') }
  finally { cacheClearing.value = false }
}

// ==================== 重嵌入状态 ====================
const embeddingDimensions = ref('')
const reembed = ref({ status: 'idle', total: 0, done: 0, failed: 0, error: null, oldDim: 0, newDim: 0, indexed: 0 })
const reembedTriggering = ref(false)
const reembedElapsed = computed(() => {
  const s = reembed.value
  if (!s.startTime) return ''
  const end = s.status === 'running' ? Date.now() : (s.endTime || 0)
  if (!end || end < s.startTime) return ''
  const sec = Math.round((end - s.startTime) / 1000)
  return sec < 60 ? `${sec} 秒` : `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`
})
let reembedTimer = null
const refreshReembedStatus = async () => {
  try {
    const r = await getReembedStatus()
    if (r.success) reembed.value = r.data || { status: 'idle' }
    if (reembed.value.status === 'running') {
      if (!reembedTimer) reembedTimer = setInterval(refreshReembedStatus, 3000)
    } else if (reembedTimer) {
      clearInterval(reembedTimer); reembedTimer = null
    }
  } catch (e) { /* 静默 */ }
}
// ==================== MCP 外部工具：连接状态与重连 ====================
const mcpStatus = ref({ enabled: false, servers: [] })
const mcpLoading = ref(false)
const mcpReloading = ref(false)
const mcpCheckedAt = ref('')
/** 失败态去掉 "failed:" 前缀，只给用户看原因；未知态原样显示 */
const mcpErr = st => (st || '').startsWith('failed:') ? (st || '').slice(7) : (st || '未知')

// —— 面板内自定义呈现：搜索 / 总开关行 / 高级 JSON 折叠（后两者仍写回 form，随全局保存生效） ——
const mcpKeyword = ref('')
const mcpAdvancedOpen = ref(false)
/** MCP 总开关与 JSON 在面板内自定义渲染，字段循环里跳过（避免重复出现） */
const MCP_CUSTOM_FIELDS = new Set(['mcp.enabled', 'mcp.servers'])
const mcpEnabled = computed({
  get: () => !!readForm(form.value, 'mcp.enabled'),
  set: v => writeForm(form.value, 'mcp.enabled', v)
})
const mcpServersJson = computed({
  get: () => readForm(form.value, 'mcp.servers') || '',
  set: v => writeForm(form.value, 'mcp.servers', v)
})
/** 原始 JSON 解析错误文案（空串=正常）；列表据此给出可读提示，不静默清空 */
const mcpJsonError = computed(() => {
  const raw = readForm(form.value, 'mcp.servers')
  if (!raw || !String(raw).trim()) return ''
  try {
    return Array.isArray(JSON.parse(raw)) ? '' : 'mcp.servers 不是 JSON 数组'
  } catch (e) { return 'mcp.servers 不是合法 JSON（' + (e.message || '格式错误') + '）' }
})
/** 配置里的服务数组（容错解析，供展示合并；增删改仍走 currentMcpServers） */
const mcpConfiguredServers = computed(() => {
  const raw = readForm(form.value, 'mcp.servers')
  if (!raw || !String(raw).trim() || mcpJsonError.value) return []
  try { return (JSON.parse(raw) || []).filter(x => x && x.name) } catch (e) { return [] }
})
/** 展示列表 = 配置 JSON ∪ 运行时状态：总开关关闭时运行时为空，仍按配置展示并标「未启用」 */
const mcpCards = computed(() => {
  const runtime = new Map((mcpStatus.value.servers || []).map(s => [s.name, s]))
  const cards = []
  for (const c of mcpConfiguredServers.value) {
    const r = runtime.get(c.name)
    runtime.delete(c.name)
    cards.push({
      name: c.name,
      url: c.url || (r && r.url) || '',
      type: c.type || (r && r.type) || 'streamable',
      connected: !!(r && r.connected),
      runtimeState: r ? r.state : (mcpStatus.value.enabled ? 'unknown' : 'disabled'),
      tools: (r && r.tools) || [],
      toolCount: (r && r.toolCount) || 0
    })
  }
  // 防御：运行时存在但配置里没有的条目也展示（JSON 尚未回填等边界场景）
  for (const r of runtime.values()) {
    cards.push({ name: r.name, url: r.url || '', type: r.type || 'streamable', connected: !!r.connected,
                 runtimeState: r.state, tools: r.tools || [], toolCount: r.toolCount || 0 })
  }
  return cards.map(s => {
    if (s.connected) return { ...s, stateText: `已连接 · ${s.toolCount} 个工具`, stateCls: 'ok' }
    if (s.runtimeState === 'disabled') return { ...s, stateText: '未启用', stateCls: 'muted' }
    if (!s.runtimeState || s.runtimeState === 'unknown') return { ...s, stateText: '未连接', stateCls: 'bad' }
    return { ...s, stateText: mcpErr(s.runtimeState), stateCls: 'bad' }
  })
})
const mcpFilteredCards = computed(() => {
  const kw = mcpKeyword.value.trim().toLowerCase()
  if (!kw) return mcpCards.value
  return mcpCards.value.filter(s =>
    String(s.name || '').toLowerCase().includes(kw) || String(s.url || '').toLowerCase().includes(kw))
})
const applyMcp = d => {
  mcpStatus.value = { enabled: !!d?.enabled, servers: d?.servers || [] }
  mcpCheckedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}
const loadMcpStatus = async () => {
  mcpLoading.value = true
  try {
    const r = await getMcpStatus()
    if (r.success) applyMcp(r.data)
  } catch (e) { /* 状态拉取失败不打扰配置操作，保持上次状态 */ }
  finally { mcpLoading.value = false }
}
// ==================== API Key 管理（6.5） ====================
const keys = ref([])
const keysLoading = ref(false)
const keyKeyword = ref('')
const keyTogglingId = ref('')
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
const delKey = async id => {
  try {
    const r = await deleteApiKey(id)
    if (r.success) { message.success('已删除'); loadKeys() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 技能（Skills，4.5） ====================
const skills = ref([])
const skillDir = ref('')
const skillsLoading = ref(false)
const skillKeyword = ref('')
const skillCfgOpen = ref(false)
const skillCreating = ref(false)
const skillCreateOpen = ref(false)
const skillViewOpen = ref(false)
const skillView = ref({ name: '', dirName: '', version: '', hash: '', source: '', content: '', disabled: false })
const skillViewToggling = ref(false)
const skillForm = ref({ name: '', description: '', content: '' })
const activeSkillCount = computed(() => skills.value.filter(s => !s.disabled).length)
const skillEnabled = computed(() => !!readForm(form.value, 'skill.enabled'))
const filteredSkills = computed(() => {
  const kw = skillKeyword.value.trim().toLowerCase()
  if (!kw) return skills.value
  return skills.value.filter(s =>
    String(s.name || '').toLowerCase().includes(kw) || String(s.description || '').toLowerCase().includes(kw))
})
/** 列表按来源分组：用户技能在前，内置技能在后（空组不渲染） */
const skillGroups = computed(() => [
  { title: '用户技能', list: filteredSkills.value.filter(s => s.source === 'user') },
  { title: '内置技能', list: filteredSkills.value.filter(s => s.source !== 'user') }
].filter(g => g.list.length))
const loadSkills = async () => {
  skillsLoading.value = true
  try {
    const r = await listSkills()
    if (r.success && r.data) {
      skills.value = r.data.skills || []
      skillDir.value = r.data.dir || ''
    }
  } catch (e) { /* 拉取失败不打扰，保留上次列表 */ }
  finally { skillsLoading.value = false }
}
/** 新建技能时预填的骨架：直接给出"适用场景/做法/禁止"三段，比空白框好写 */
const skillTemplate = () => '# 技能标题\n\n## 适用场景\n\n用户问到……时使用本技能。\n\n## 做法\n\n1. 先……\n2. 再……\n\n## 禁止\n\n- 不要……\n'
const openCreateSkill = () => {
  skillForm.value = { name: '', description: '', content: skillTemplate() }
  skillCreateOpen.value = true
}
const submitCreateSkill = async () => {
  if (!skillForm.value.name.trim()) { message.warning('请填写技能名'); return }
  // 描述不是可有可无：模型是靠这句判断要不要读技能，空描述等于装了不生效
  if (!skillForm.value.description.trim()) { message.warning('请填写描述——模型靠它判断何时读取技能'); return }
  skillCreating.value = true
  try {
    const r = await createSkill({
      name: skillForm.value.name.trim(),
      description: skillForm.value.description.trim(),
      content: skillForm.value.content
    })
    if (r.success) { message.success('技能已创建'); skillCreateOpen.value = false; loadSkills() }
    else message.error(r.msg || '创建失败')
  } catch (e) { message.error(e.message || '创建失败') }
  finally { skillCreating.value = false }
}
const viewSkill = async rec => {
  try {
    const r = await getSkillDetail(rec.dirName)
    if (r.success) { skillView.value = r.data; skillViewOpen.value = true }
    else message.error(r.msg || '读取失败')
  } catch (e) { message.error(e.message || '读取失败') }
}
const toggleSkill = async rec => {
  try {
    const r = await setSkillDisabled(rec.dirName, !rec.disabled)
    if (r.success) { message.success(rec.disabled ? '已启用' : '已停用'); loadSkills() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
/** 查看弹窗头部开关：切换后同步弹窗内状态与列表 */
const toggleSkillFromView = async checked => {
  const rec = skillView.value
  if (!rec.dirName) return
  skillViewToggling.value = true
  try {
    const r = await setSkillDisabled(rec.dirName, !checked)
    if (r.success) {
      skillView.value = { ...rec, disabled: !checked }
      message.success(checked ? '已启用' : '已停用')
      loadSkills()
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { skillViewToggling.value = false }
}
const delSkill = async rec => {
  try {
    const r = await deleteSkill(rec.dirName)
    if (r.success) { message.success('已删除'); loadSkills() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}
// 从 URL 安装（4.5 收尾）：适合从团队仓库/GitHub raw 一处安装、多台机器复用
const skillInstallOpen = ref(false)
const skillInstalling = ref(false)
const skillInstallForm = ref({ url: '', name: '' })
const openInstallSkill = () => {
  skillInstallForm.value = { url: '', name: '' }
  skillInstallOpen.value = true
}
const doInstallSkill = async () => {
  if (!skillInstallForm.value.url.trim()) { message.warning('请填写技能文件地址'); return }
  skillInstalling.value = true
  try {
    const r = await installSkillFromUrl(skillInstallForm.value.url.trim(), skillInstallForm.value.name.trim())
    if (r.success) {
      message.success('已安装技能「' + (r.data?.name || '') + '」')
      skillInstallOpen.value = false
      loadSkills()
    } else message.error(r.msg || '安装失败')
  } catch (e) { message.error(e.message || '安装失败') }
  finally { skillInstalling.value = false }
}

// ==================== MCP 服务卡片：添加 / 编辑 / 移除 / 测试连接 ====================
// 服务的增删改都落到 mcp.servers 配置（一个 JSON 数组），保存后自动重连并刷新状态——
// 用户不必再手写 JSON，也不需要"保存配置 + 手动重连"两步操作。
const mcpExpanded = ref('')
const mcpFormOpen = ref(false)
const mcpSaving = ref(false)
const mcpForm = ref({ mode: 'add', origin: '', name: '', url: '', type: 'streamable' })
const mcpProbe = ref({ loading: false, done: false, available: false, error: '', tools: [] })
const mcpConnectedCount = computed(() => mcpCards.value.filter(s => s.connected).length)

const toggleMcpTools = s => { mcpExpanded.value = mcpExpanded.value === s.name ? '' : s.name }
const openMcpAdd = () => {
  mcpForm.value = { mode: 'add', origin: '', name: '', url: '', type: 'streamable' }
  mcpProbe.value = { loading: false, done: false, available: false, error: '', tools: [] }
  mcpFormOpen.value = true
}
const openMcpEdit = s => {
  mcpForm.value = { mode: 'edit', origin: s.name, name: s.name, url: s.url || '', type: s.type || 'streamable' }
  mcpProbe.value = { loading: false, done: false, available: false, error: '', tools: [] }
  mcpFormOpen.value = true
}
const testMcpForm = async () => {
  if (!mcpForm.value.url.trim()) { message.warning('请先填写服务地址'); return }
  mcpProbe.value = { loading: true, done: false, available: false, error: '', tools: [] }
  try {
    const r = await probeMcp(mcpForm.value.url.trim(), mcpForm.value.type)
    const d = r.success ? r.data : null
    mcpProbe.value = { loading: false, done: true, available: !!(d && d.available),
      error: (d && d.error) || '未知错误', tools: (d && d.tools) || [] }
  } catch (e) {
    mcpProbe.value = { loading: false, done: true, available: false, error: e.message || '测试失败', tools: [] }
  }
}
/** 当前配置里的服务数组（从 mcp.servers 字段解析；非法 JSON 给可读提示，不静默清空） */
const currentMcpServers = () => {
  const raw = readForm(form.value, 'mcp.servers')
  if (!raw || !String(raw).trim()) return []
  const arr = JSON.parse(raw)
  if (!Array.isArray(arr)) throw new Error('mcp.servers 不是 JSON 数组，请先在下方高级编辑里修正')
  return arr
}
/** 保存服务列表（只提交 mcp 两项，不动用户其它未保存改动）→ 重连 → 刷新状态 */
const persistMcpServers = async list => {
  const json = JSON.stringify(list)
  const r = await saveConfig({ mcp: { servers: json, enabled: 'true' } })
  if (!r.success) { message.error(r.msg || '保存失败'); return false }
  writeForm(form.value, 'mcp.servers', json)
  writeForm(form.value, 'mcp.enabled', true)
  mcpStatus.value = { ...mcpStatus.value, enabled: true }
  // 基线同步：否则脏检测会把刚存下的值当成"待保存"再提交一次
  if (initialPayload.value) {
    const base = JSON.parse(JSON.stringify(initialPayload.value))
    base.mcp = { ...(base.mcp || {}), servers: json, enabled: 'true' }
    initialPayload.value = base
  }
  await doReloadMcp()
  return true
}
const submitMcpForm = async () => {
  const name = mcpForm.value.name.trim()
  const url = mcpForm.value.url.trim()
  if (!name) { message.warning('请填写名称'); return }
  if (!url) { message.warning('请填写服务地址'); return }
  mcpSaving.value = true
  try {
    let list
    try { list = currentMcpServers() } catch (e) { message.error(e.message); return }
    const item = { name, url, type: mcpForm.value.type }
    if (mcpForm.value.mode === 'edit') {
      const at = list.findIndex(x => x && x.name === mcpForm.value.origin)
      if (at >= 0) list[at] = item; else list.push(item)
    } else {
      if (list.some(x => x && x.name === name)) { message.warning('已存在同名服务，请换个名称'); return }
      if (list.length >= 10) { message.warning('最多接入 10 个 MCP 服务'); return }
      list.push(item)
    }
    if (await persistMcpServers(list)) {
      message.success(mcpForm.value.mode === 'add' ? '已添加并连接' : '已保存并重连')
      mcpFormOpen.value = false
    }
  } finally { mcpSaving.value = false }
}
const removeMcpServer = async s => {
  let list
  try { list = currentMcpServers() } catch (e) { message.error(e.message); return }
  if (await persistMcpServers(list.filter(x => !x || x.name !== s.name))) message.success('已移除')
}

// 切到 MCP 面板时自动拉一次最新状态（配置可能在别处改过）；API Key / 技能面板同理
watch(current, k => {
  if (k === 'mcp') loadMcpStatus()
  if (k === 'apiKey') loadKeys()
  if (k === 'skills') loadSkills()
})

const doReloadMcp = async () => {
  mcpReloading.value = true
  try {
    const r = await reloadMcp()
    if (r.success) {
      applyMcp(r.data)
      const bad = (r.data?.servers || []).filter(s => !s.connected).length
      if (bad) message.warning(`已重连，${bad} 个服务仍未连上（见状态详情）`)
      else message.success('已重连')
    } else message.error(r.msg || '重连失败')
  } catch (e) { message.error(e.message || '重连失败') }
  finally { mcpReloading.value = false }
}

const doTriggerReembed = async () => {
  reembedTriggering.value = true
  try {
    const r = await triggerReembed()
    if (r.success) { message.success('全量重嵌入任务已启动，期间检索自动降级关键词路'); refreshReembedStatus() }
    else message.error(r.msg || '触发失败')
  } catch (e) { message.error(e.message || '触发失败') }
  finally { reembedTriggering.value = false }
}

onMounted(fetchAndFill)
onUnmounted(() => {
  if (reembedTimer) { clearInterval(reembedTimer); reembedTimer = null }
})
</script>

<style scoped>
.dirty-hint { font-size: 12px; color: #a3691b; background: #faf3e6; border-radius: 6px; padding: 3px 10px; }
.head-hint-plain { font-size: 12px; color: var(--app-text3); background: transparent; border: none; }
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
.app-btn.dis { background: #c6d4f2; cursor: not-allowed; }
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
.probe-chip.ok { color: var(--app-ok); background: #eaf5ec; }
.probe-chip.bad { color: var(--app-danger); background: #fbecea; }
.reembed-meta { margin-top: 6px; color: var(--app-text3); font-size: 12px; line-height: 1.8; }
/* MCP 总开关行 + 高级 JSON 折叠 */
.mcp-switch-row {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; margin-bottom: 12px;
  background: #f8f9fb; border: 1px solid var(--app-border); border-radius: 8px;
}
.mcp-switch-label { font-size: 12px; font-weight: 500; flex: none; }
.mcp-advanced-body { padding-top: 8px; }
.mcp-json-hint { margin-top: 6px; line-height: 1.7; }
/* MCP 服务卡片 */
.mcp-card { border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; margin-bottom: 8px; }
.mcp-card-head { display: flex; align-items: center; gap: 8px; }
.mcp-card-name { font-size: 13px; font-weight: 500; max-width: 40%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-card-head .mcp-state { font-size: 12px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-card-actions { margin-left: auto; flex: none; }
.mcp-card-sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; color: var(--app-text3); }
.mcp-card-sub .mcp-url { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-type-pill { flex: none; font-size: 11px; padding: 0 6px; border-radius: 3px; background: #f1f3f5; color: var(--app-text3); }
.mcp-tools { margin-top: 8px; border-top: 1px dashed var(--app-border); padding-top: 6px; }
.mcp-tool { display: flex; gap: 8px; font-size: 12px; padding: 2px 0; align-items: baseline; }
.mcp-tool code { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; background: #f2f3f5; padding: 1px 5px; border-radius: 4px; }
.mcp-tool-desc { min-width: 0; color: var(--app-text3); }
.mcp-probe-ok { font-size: 12px; color: var(--app-ok); }
.mcp-probe-bad { font-size: 12px; color: var(--app-danger); word-break: break-all; }
/* 旧版单行状态（保留：无卡片渲染时不会用到，但样式不删以免其它页面引用报缺失） */
.mcp-empty { color: var(--app-text3); font-size: 12px; margin-bottom: 6px; }
.mcp-srv { display: flex; align-items: center; gap: 8px; font-size: 12px; padding: 3px 0; }
.mcp-dot { flex: none; width: 7px; height: 7px; border-radius: 50%; }
.mcp-dot.ok { background: var(--app-ok); }
.mcp-dot.bad { background: var(--app-danger); }
.mcp-dot.muted { background: var(--app-text3); }
.mcp-name { flex: none; font-weight: 500; max-width: 140px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-url { flex: 1; min-width: 0; color: var(--app-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-type { flex: none; color: var(--app-text3); }
.mcp-state { flex: none; }
.mcp-state.ok { color: var(--app-ok); }
.mcp-state.muted { color: var(--app-text3); }
.mcp-state.bad { color: var(--app-danger); max-width: 320px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-checked { margin-left: 8px; font-size: 11px; color: var(--app-text3); }
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
.key-code { position: relative; background: #f6f7f9; border: 1px solid var(--app-border); border-radius: 6px; padding: 10px 34px 10px 12px; }
.key-code code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--app-text); }
.key-copy {
  position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border: none; border-radius: 5px;
  background: transparent; color: var(--app-text3); cursor: pointer; font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
}
.key-copy:hover { background: var(--app-accent-weak); color: var(--app-accent); }
.key-copy-ok { color: var(--app-ok); }
.key-usage-list { margin: 10px 0 0; padding-left: 18px; font-size: 12px; color: var(--app-text2); line-height: 1.9; }
.key-usage-list code { background: #f2f3f5; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
/* 弹窗内 */
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.key-done-warn {
  background: #fff7e6; border: 1px solid #ffd591; color: #d46b08;
  border-radius: 6px; padding: 8px 10px; font-size: 12px; margin-bottom: 12px; line-height: 1.6;
}
.key-done-meta { font-size: 12px; color: var(--app-text2); margin-bottom: 8px; }
.key-done-box {
  position: relative; background: #f6f7f9; border: 1px solid var(--app-border); border-radius: 6px;
  padding: 12px 36px 12px 12px;
}
.key-done-box code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; word-break: break-all; color: var(--app-text); }
/* 技能（Skills） */
.skill-dir-tip {
  font-size: 12px; color: var(--app-text3); line-height: 1.8; margin-bottom: 12px;
  background: #f8f9fb; border: 1px solid var(--app-border); border-radius: 6px; padding: 8px 10px;
}
.skill-dir-tip code { background: #eef0f3; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.skill-desc-warn { color: #a3691b; }
/* 技能卡片网格 + 分组标题 */
.res-group-title {
  margin: 14px 0 8px; font-size: 11px; font-weight: 600;
  color: var(--app-text3); letter-spacing: .4px; user-select: none;
}
.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }
.skill-card {
  border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px;
  display: flex; flex-direction: column; gap: 6px; min-width: 0;
  transition: border-color .15s, background .15s;
}
.skill-card:hover { border-color: #d5dce8; background: #fafbfc; }
.skill-card-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.skill-card-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.skill-card-desc {
  font-size: 12px; color: var(--app-text2); line-height: 1.55; min-height: 2.6em;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.skill-card-foot { display: flex; align-items: center; justify-content: flex-end; gap: 2px; border-top: 1px dashed var(--app-border); padding-top: 4px; }
.skill-cfg-body { padding-top: 6px; }
.skill-view-toggle { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; }
.skill-view-meta { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: var(--app-text3); margin-bottom: 10px; }
.skill-view-meta code { background: #f2f3f5; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.skill-view-body { max-height: 56vh; overflow-y: auto; border: 1px solid var(--app-border); border-radius: 6px; padding: 12px 14px; }
</style>
