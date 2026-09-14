<template>
  <div class="v2-page">
    <div class="v2-page-head">
      <h3 class="v2-page-title">系统设置</h3>
      <span v-if="dirtyCount" class="dirty-hint">有 {{ dirtyCount }} 项已修改未保存</span>
      <button v-else class="head-hint-plain">修改后点右侧保存生效，悬停参数旁 ? 查看说明</button>
      <button class="v2-btn" style="margin-left:auto" :disabled="!dirtyCount" :class="{ dis: !dirtyCount }" @click="save">
        <save-outlined /> 保存配置{{ dirtyCount ? `（${dirtyCount} 项改动）` : '' }}
      </button>
    </div>

    <div class="set-body">
      <!-- 左侧分组导航 -->
      <nav class="set-nav">
        <span v-for="p in PANELS" :key="p.key" class="set-nav-item" :class="{ active: current === p.key }" @click="current = p.key">
          {{ groupLabel(p.key) }}
        </span>
      </nav>

      <!-- 右侧：当前分组表单 -->
      <section class="set-content">
        <a-spin :spinning="loading">
          <div class="set-panel-head">
            <h3 class="v2-page-title">{{ currentPanel?.title }}</h3>
            <button v-if="!NO_RESET.includes(current)" class="v2-btn ghost" :disabled="resettingKey === current" @click="onResetGroup(current)">
              恢复本组默认
            </button>
          </div>

          <a-alert v-for="(al, ai) in (PANEL_ALERTS[current] || [])" :key="ai" :type="al.type" show-icon
                   style="margin-bottom:12px" :message="al.msg" />

          <div class="v2-card set-card">
            <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }" @submit.prevent>
              <template v-for="(blk, i) in blocksOf(current)" :key="i">
                <div v-if="blk.type === 'sub'" class="cfg-sub">{{ blk.title }}</div>

                <!-- 向量模型组：索引状态与重嵌入（只读状态 + 手动触发） -->
                <template v-if="current === 'embedding' && blk.type === 'sub' && blk.title.includes('索引状态')">
                  <a-form-item>
                    <template #label>
                      <a-tooltip :title="TIPS.embeddingDimensions" placement="top">当前索引维度 <question-circle-outlined class="tip-icon" /></a-tooltip>
                    </template>
                    <span v-if="embeddingDimensions" style="color:var(--v2-text2)">{{ embeddingDimensions }} 维</span>
                    <span v-else style="color:var(--v2-text3)">未记录（首次重嵌入完成后自动记录）</span>
                  </a-form-item>
                  <a-form-item label="重嵌入状态">
                    <div>
                      <span v-if="reembed.status === 'running'" style="color:var(--v2-accent)">进行中：{{ reembed.done }} / {{ reembed.total }} 块<span v-if="reembed.failed" style="color:var(--v2-danger)">（失败 {{ reembed.failed }}）</span></span>
                      <span v-else-if="reembed.status === 'done'" style="color:var(--v2-ok)">已完成：{{ reembed.done }} 块<span v-if="reembed.failed" style="color:var(--v2-danger)">（失败 {{ reembed.failed }}，可重试补齐）</span></span>
                      <span v-else-if="reembed.status === 'failed'" style="color:var(--v2-danger)">失败：{{ reembed.error }}（已完成 {{ reembed.done }} 块，可重试）</span>
                      <span v-else style="color:var(--v2-text3)">未运行</span>
                      <button class="v2-btn ghost small" :disabled="reembedTriggering" @click="doTriggerReembed">{{ reembedTriggering ? '启动中…' : '手动重嵌入' }}</button>
                      <button class="v2-btn ghost small" @click="refreshReembedStatus">刷新</button>
                    </div>
                    <div v-if="reembed.status !== 'idle'" class="reembed-meta">
                      <span v-if="reembed.newDim">维度：{{ reembed.oldDim || '未知' }} → {{ reembed.newDim }}</span>
                      <span v-if="reembedElapsed" style="margin-left:12px">耗时 {{ reembedElapsed }}</span>
                      <span v-if="reembed.indexed" style="margin-left:12px">索引内 {{ reembed.indexed }} 块<span v-if="reembed.status === 'done' && reembed.indexed < reembed.done" style="color:var(--v2-danger)">（少于成功写入数，建议再跑一次）</span></span>
                    </div>
                  </a-form-item>
                </template>

                <!-- MCP 面板：服务卡片（状态/工具清单/测试/编辑）+ 添加弹窗；下方 JSON 字段保留为高级编辑 -->
                <template v-if="current === 'mcp' && blk.type === 'sub'">
                  <div class="key-bar">
                    <span class="key-stat">
                      共 <b>{{ mcpStatus.servers.length }}</b> 个服务 · 已连接 <b>{{ mcpConnectedCount }}</b>
                      <span v-if="mcpCheckedAt" class="key-dim">· 更新于 {{ mcpCheckedAt }}</span>
                    </span>
                    <div class="key-bar-actions">
                      <button class="v2-btn ghost small" :disabled="mcpLoading" @click="loadMcpStatus">刷新</button>
                      <button class="v2-btn ghost small" :disabled="mcpReloading" @click="doReloadMcp">
                        {{ mcpReloading ? '重连中…' : '全部重连' }}
                      </button>
                      <button class="v2-btn small" @click="openMcpAdd">＋ 添加服务</button>
                    </div>
                  </div>

                  <a-alert v-if="!mcpStatus.enabled" type="warning" show-icon style="margin-bottom:12px"
                           message="MCP 总开关未开启"
                           description="开启后才会连接下方服务、并把它们的工具提供给模型（在下方「MCP 总开关」处开启并保存）。" />

                  <div v-if="!mcpStatus.servers.length" class="key-empty">
                    <div class="key-empty-title">还没有 MCP 服务</div>
                    <div class="key-empty-desc">
                      接入外部 MCP 服务（如时间工具、内部系统查询），它的工具会自动注册给模型，与内置工具一样可被调用。
                    </div>
                    <button class="v2-btn small" @click="openMcpAdd">添加第一个服务</button>
                  </div>

                  <template v-else>
                    <div v-for="s in mcpStatus.servers" :key="s.name" class="mcp-card">
                      <div class="mcp-card-head">
                        <span class="mcp-dot" :class="s.connected ? 'ok' : 'bad'"></span>
                        <span class="mcp-card-name">{{ s.name }}</span>
                        <span v-if="s.connected" class="mcp-state ok">已连接 · {{ s.toolCount }} 个工具</span>
                        <span v-else class="mcp-state bad" :title="s.state">{{ mcpErr(s.state) }}</span>
                        <div class="mcp-card-actions">
                          <button v-if="s.connected" class="v2-link-btn" @click="toggleMcpTools(s)">
                            {{ mcpExpanded === s.name ? '收起工具' : '查看工具' }}
                          </button>
                          <button class="v2-link-btn" @click="openMcpEdit(s)">编辑</button>
                          <a-popconfirm title="从配置中移除该服务？" ok-text="移除" cancel-text="取消" @confirm="removeMcpServer(s)">
                            <button class="v2-link-btn danger">移除</button>
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
                      <button class="v2-btn ghost" :disabled="mcpProbe.loading" @click="testMcpForm">
                        {{ mcpProbe.loading ? '测试中…' : '测试连接' }}
                      </button>
                      <button class="v2-btn" :disabled="mcpSaving" @click="submitMcpForm">
                        {{ mcpSaving ? '保存中…' : (mcpForm.mode === 'add' ? '添加并连接' : '保存并重连') }}
                      </button>
                    </div>
                  </a-modal>
                </template>

                <!-- 常规字段（SchemaField 全量复用：类型控件/条件显隐/参数说明） -->
                <template v-else-if="blk.type === 'field'">
                  <SchemaField :field="blk.field" :form="form" :tips="TIPS" @change="onFieldChange">
                    <template v-if="probeKey(blk.field)" #extra>
                      <button class="v2-btn ghost small probe-btn" :disabled="probeStates[probeKey(blk.field)].loading" @click="doProbe(probeKey(blk.field))">
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
                  <span v-if="cacheStats.count != null" style="color:var(--v2-text2)">
                    已缓存 <b style="color:var(--v2-accent);font-weight:500">{{ cacheStats.count }}</b> 条（上限 {{ form.semanticCache?.maxEntries ?? '—' }}）
                  </span>
                  <span v-else style="color:var(--v2-text3)">统计未加载</span>
                  <a-popconfirm title="清空后缓存重新积累，确定清空？" ok-text="清空" cancel-text="取消" @confirm="doClearCache">
                    <button class="v2-btn danger small" style="margin-left:12px" :disabled="cacheClearing">{{ cacheClearing ? '清空中…' : '清空语义缓存' }}</button>
                  </a-popconfirm>
                  <button class="v2-btn ghost small" style="margin-left:8px" @click="refreshCacheStats">刷新</button>
                </div>
                <div class="reembed-meta">清空后按提问重新积累；知识库变更（解析/删除/回滚/启停用）时后端会自动整体清空，一般无需手动操作。</div>
              </a-form-item>

              <!-- API Key 管理（6.5）：签发 / 列表 / 停用 / 删除 -->
              <template v-if="current === 'apiKey'">
                <!-- 工具栏：概览统计 + 主操作 -->
                <div class="key-bar">
                  <span class="key-stat">
                    共 <b>{{ keys.length }}</b> 个 Key · 生效中 <b>{{ activeKeyCount }}</b>
                    <span v-if="lastUsedKey" class="key-dim">· 最近使用 {{ fmtTs(lastUsedKey.lastUsedAt) }}</span>
                  </span>
                  <div class="key-bar-actions">
                    <button class="v2-btn ghost small" @click="loadKeys">刷新</button>
                    <button class="v2-btn small" @click="openCreateKey">＋ 创建 API Key</button>
                  </div>
                </div>

                <!-- 空态：没有 Key 时给引导，而不是一张空表格 -->
                <div v-if="!keys.length" class="key-empty">
                  <div class="key-empty-title">还没有 API Key</div>
                  <div class="key-empty-desc">
                    创建后，外部系统在请求头带 <code>X-Api-Key</code> 即可调用问答接口，无需平台 token。
                  </div>
                  <button class="v2-btn small" @click="openCreateKey">创建第一个 API Key</button>
                </div>

                <a-table v-else :data-source="keys" size="small" row-key="id" :pagination="false">
                  <a-table-column title="名称" key="name" ellipsis>
                    <template #default="{ record }">
                      <span class="key-name-wrap">
                        <span class="key-name">{{ record.name || '未命名' }}</span>
                        <span v-if="record.disabled" class="v2-pill warn key-tag">已停用</span>
                        <span v-else-if="record.expired" class="v2-pill err key-tag">已过期</span>
                        <span v-else class="v2-pill ok key-tag">生效中</span>
                      </span>
                    </template>
                  </a-table-column>
                  <a-table-column title="Key" key="prefix" width="170">
                    <template #default="{ record }"><span class="key-prefix">{{ record.keyPrefix }}…</span></template>
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
                      <button class="v2-link-btn" @click="openRenameKey(record)">改名</button>
                      <button class="v2-link-btn" @click="toggleKey(record)">{{ record.disabled ? '启用' : '停用' }}</button>
                      <a-popconfirm title="删除该 Key？调用方将立即失效" ok-text="删除" cancel-text="取消" @confirm="delKey(record.id)">
                        <button class="v2-link-btn danger">删除</button>
                      </a-popconfirm>
                    </template>
                  </a-table-column>
                </a-table>
                <!-- 如何使用：拿到 Key 之后怎么调，比堆一段说明文字有用 -->
                <div class="key-usage">
                  <div class="key-usage-head" @click="usageOpen = !usageOpen">
                    <span class="key-usage-caret">{{ usageOpen ? '▾' : '▸' }}</span> 如何使用
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
                      <li><code>X-Api-Key: sk-…</code> 替代平台 token（<code>X-Trusted-Token</code>）</li>
                      <li><code>X-User-Id</code> 仍用于会话隔离：同一个 Key 不同用户带不同值，会话互不串</li>
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
                      <button class="v2-btn ghost" @click="closeCreateKey">取消</button>
                      <button class="v2-btn" :disabled="keyCreating" @click="submitCreateKey">{{ keyCreating ? '创建中…' : '创建' }}</button>
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
                      <button class="v2-btn" @click="closeCreateKey">我已保存，关闭</button>
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
                    <button class="v2-btn ghost" @click="renameOpen = false">取消</button>
                    <button class="v2-btn" @click="submitRenameKey">保存</button>
                  </div>
                </a-modal>
              </template>

              <!-- 技能（Skills）：目录 + SKILL.md 的纯文本能力包，模型按需读取后照做 -->
              <template v-if="current === 'skills'">
                <div class="key-bar">
                  <span class="key-stat">
                    共 <b>{{ skills.length }}</b> 个技能 · 生效中 <b>{{ activeSkillCount }}</b>
                  </span>
                  <div class="key-bar-actions">
                    <button class="v2-btn ghost small" @click="loadSkills">刷新</button>
                    <button class="v2-btn small" @click="openCreateSkill">＋ 新建技能</button>
                  </div>
                </div>
                <div class="skill-dir-tip">
                  技能目录 <code>{{ skillDir || '—' }}</code>：每个子目录放一个 <code>SKILL.md</code> 就是一个技能
                  （frontmatter 写 name / description / version，正文写具体做法），与内置技能同名时用户目录优先。
                  技能只作为文本指令注入，<b>不会执行目录里的任何脚本</b>。
                </div>

                <div v-if="!skills.length" class="key-empty">
                  <div class="key-empty-title">还没有技能</div>
                  <div class="key-empty-desc">
                    技能用来固化「这类问题该怎么做」的做法——步骤、输出格式、禁忌。模型按需读取后照做，不必每次在提问里重复交代。
                  </div>
                  <button class="v2-btn small" @click="openCreateSkill">新建第一个技能</button>
                </div>

                <a-table v-else :data-source="skills" size="small" row-key="dirName" :pagination="false">
                  <a-table-column title="技能" key="name" ellipsis>
                    <template #default="{ record }">
                      <span class="key-name-wrap">
                        <span class="key-name">{{ record.name }}</span>
                        <span v-if="record.source === 'builtin'" class="v2-pill muted key-tag">内置</span>
                        <span v-if="record.disabled" class="v2-pill warn key-tag">已停用</span>
                        <span v-else class="v2-pill ok key-tag">生效中</span>
                      </span>
                    </template>
                  </a-table-column>
                  <a-table-column title="描述" key="desc" ellipsis>
                    <template #default="{ record }">
                      <span :class="{ 'key-dim': true, 'skill-desc-warn': !record.description }">
                        {{ record.description || '（未填描述：模型不会主动读取它）' }}
                      </span>
                    </template>
                  </a-table-column>
                  <a-table-column title="版本" key="version" width="80">
                    <template #default="{ record }"><span class="key-dim">{{ record.version || '—' }}</span></template>
                  </a-table-column>
                  <a-table-column title="哈希" key="hash" width="90">
                    <template #default="{ record }"><span class="key-prefix">{{ record.hash }}</span></template>
                  </a-table-column>
                  <a-table-column title="操作" key="act" width="150">
                    <template #default="{ record }">
                      <button class="v2-link-btn" @click="viewSkill(record)">查看</button>
                      <button class="v2-link-btn" @click="toggleSkill(record)">{{ record.disabled ? '启用' : '停用' }}</button>
                      <a-popconfirm v-if="record.source === 'user'" title="删除该技能？文件将同时删除" ok-text="删除" cancel-text="取消" @confirm="delSkill(record)">
                        <button class="v2-link-btn danger">删除</button>
                      </a-popconfirm>
                    </template>
                  </a-table-column>
                </a-table>

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
                    <button class="v2-btn ghost" @click="skillCreateOpen = false">取消</button>
                    <button class="v2-btn" :disabled="skillCreating" @click="submitCreateSkill">{{ skillCreating ? '创建中…' : '创建' }}</button>
                  </div>
                </a-modal>

                <!-- 查看技能弹窗 -->
                <a-modal v-model:open="skillViewOpen" :title="'技能：' + skillView.name" :footer="null" :width="760">
                  <div class="skill-view-meta">
                    <span>目录 <code>{{ skillView.dirName }}</code></span>
                    <span>版本 {{ skillView.version || '—' }}</span>
                    <span>哈希 <code>{{ skillView.hash }}</code></span>
                    <span>来源 {{ skillView.source === 'builtin' ? '内置' : '用户' }}</span>
                  </div>
                  <div class="skill-view-body md" v-html="renderMd(skillView.content)"></div>
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
import { SaveOutlined, QuestionCircleOutlined, CopyOutlined, CheckOutlined } from '@ant-design/icons-vue'
import { getConfig, saveConfig, resetConfig, checkRerank, checkKeywordEngine, getAnswerCacheStats, clearAnswerCache,
         getReembedStatus, triggerReembed, probeConnectivity,
         listApiKeys, createApiKey, setApiKeyDisabled, deleteApiKey, renameApiKey,
         listSkills, getSkillDetail, createSkill, setSkillDisabled, deleteSkill } from '../../api'
import { renderMd } from '../../utils/markdown'
import SchemaField from '../../components/SchemaField.vue'
import { FIELDS, PANELS, TIPS, blocksOf, buildDefaultForm, readForm, writeForm } from '../../configSchema'
import { getMcpStatus, reloadMcp, probeMcp } from '../../api'

// 分组导航（沿用旧版锚点短名）
const NAV_LABELS = {
  chat: '智能问答模型', vision: '视觉模型', chunk: '文档解析', embedding: '向量模型', retrieval: '检索设置',
  context: '上下文控制', deepReasoning: '深度思考', tool: '工具调用', mcp: 'MCP 外部工具',
  semanticCache: '语义缓存', ratelimit: '接口限流', maintenance: '定时维护', apiKey: 'API Key 管理', skills: '技能 Skills'
}
const groupLabel = key => NAV_LABELS[key] || key
const current = ref('chat')
const currentPanel = computed(() => PANELS.find(p => p.key === current.value))

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
  skills: [{ type: 'info', msg: '技能 = 一段可复用的"做法说明"（步骤/格式/禁忌）。系统提示里只放技能名与描述，模型判断某个问题属于某技能领域时，才去读取它的完整内容——所以技能装得多也不会拖慢每次问答。需要 skill.enabled 总开关开启后生效。' }]
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
const lastUsedKey = computed(() => keys.value
    .filter(k => k.lastUsedAt)
    .sort((a, b) => String(b.lastUsedAt).localeCompare(String(a.lastUsedAt)))[0] || null)
const fmtTs = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')
const fmtDate = s => (s ? String(s).slice(0, 10) : '长期')

const loadKeys = async () => {
  try {
    const r = await listApiKeys()
    if (r.success && Array.isArray(r.data)) keys.value = r.data
  } catch (e) { /* 拉取失败不打扰，保留上次列表 */ }
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
  -H "X-User-Id: user-001" \\
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
  try {
    const r = await setApiKeyDisabled(rec.id, !rec.disabled)
    if (r.success) { message.success(rec.disabled ? '已启用' : '已停用'); loadKeys() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
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
const skillCreating = ref(false)
const skillCreateOpen = ref(false)
const skillViewOpen = ref(false)
const skillView = ref({ name: '', dirName: '', version: '', hash: '', source: '', content: '' })
const skillForm = ref({ name: '', description: '', content: '' })
const activeSkillCount = computed(() => skills.value.filter(s => !s.disabled).length)
const loadSkills = async () => {
  try {
    const r = await listSkills()
    if (r.success && r.data) {
      skills.value = r.data.skills || []
      skillDir.value = r.data.dir || ''
    }
  } catch (e) { /* 拉取失败不打扰，保留上次列表 */ }
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
const delSkill = async rec => {
  try {
    const r = await deleteSkill(rec.dirName)
    if (r.success) { message.success('已删除'); loadSkills() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== MCP 服务卡片：添加 / 编辑 / 移除 / 测试连接 ====================
// 服务的增删改都落到 mcp.servers 配置（一个 JSON 数组），保存后自动重连并刷新状态——
// 用户不必再手写 JSON，也不需要"保存配置 + 手动重连"两步操作。
const mcpExpanded = ref('')
const mcpFormOpen = ref(false)
const mcpSaving = ref(false)
const mcpForm = ref({ mode: 'add', origin: '', name: '', url: '', type: 'streamable' })
const mcpProbe = ref({ loading: false, done: false, available: false, error: '', tools: [] })
const mcpConnectedCount = computed(() => mcpStatus.value.servers.filter(s => s.connected).length)

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
.head-hint-plain { font-size: 12px; color: var(--v2-text3); background: transparent; border: none; }
.v2-btn.dis { background: #c6d4f2; cursor: not-allowed; }
.set-body { flex: 1; min-height: 0; display: flex; }
.set-nav {
  width: 150px; flex: none; border-right: 1px solid var(--v2-border); background: var(--v2-panel);
  padding: 10px 8px; display: flex; flex-direction: column; gap: 2px; overflow-y: auto;
}
.set-nav-item { padding: 7px 10px; border-radius: 8px; font-size: 12px; color: var(--v2-text2); cursor: pointer; }
.set-nav-item:hover { background: var(--v2-accent-weak); }
.set-nav-item.active { background: var(--v2-accent-weak); color: var(--v2-text); font-weight: 500; }
.set-content { flex: 1; min-width: 0; overflow-y: auto; padding: 14px 20px 24px; }
.set-panel-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.set-card { padding: 18px 20px 6px; }
.cfg-sub { font-size: 12px; font-weight: 500; color: var(--v2-text3); margin: 14px 0 2px; padding-bottom: 4px; border-bottom: 1px dashed var(--v2-border); }
.tip-icon { color: var(--v2-text3); font-size: 12px; cursor: help; }
.v2-btn.small { padding: 3px 10px; font-size: 11px; border-radius: 6px; margin-left: 10px; }
.v2-btn.small + .v2-btn.small { margin-left: 8px; }
.probe-btn { margin-left: 8px; }
.probe-chip { margin-left: 8px; font-size: 11px; border-radius: 999px; padding: 3px 9px; cursor: help; }
.probe-chip.ok { color: var(--v2-ok); background: #eaf5ec; }
.probe-chip.bad { color: var(--v2-danger); background: #fbecea; }
.reembed-meta { margin-top: 6px; color: var(--v2-text3); font-size: 12px; line-height: 1.8; }
/* MCP 服务卡片 */
.mcp-card { border: 1px solid var(--v2-border); border-radius: 8px; padding: 10px 12px; margin-bottom: 8px; }
.mcp-card-head { display: flex; align-items: center; gap: 8px; }
.mcp-card-name { font-size: 13px; font-weight: 500; max-width: 40%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-card-head .mcp-state { font-size: 12px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-card-actions { margin-left: auto; flex: none; }
.mcp-card-sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; color: var(--v2-text3); }
.mcp-card-sub .mcp-url { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-type-pill { flex: none; font-size: 11px; padding: 0 6px; border-radius: 3px; background: #f1f3f5; color: var(--v2-text3); }
.mcp-tools { margin-top: 8px; border-top: 1px dashed var(--v2-border); padding-top: 6px; }
.mcp-tool { display: flex; gap: 8px; font-size: 12px; padding: 2px 0; align-items: baseline; }
.mcp-tool code { flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; background: #f2f3f5; padding: 1px 5px; border-radius: 4px; }
.mcp-tool-desc { min-width: 0; color: var(--v2-text3); }
.mcp-probe-ok { font-size: 12px; color: var(--v2-ok); }
.mcp-probe-bad { font-size: 12px; color: var(--v2-danger); word-break: break-all; }
/* 旧版单行状态（保留：无卡片渲染时不会用到，但样式不删以免其它页面引用报缺失） */
.mcp-empty { color: var(--v2-text3); font-size: 12px; margin-bottom: 6px; }
.mcp-srv { display: flex; align-items: center; gap: 8px; font-size: 12px; padding: 3px 0; }
.mcp-dot { flex: none; width: 7px; height: 7px; border-radius: 50%; }
.mcp-dot.ok { background: var(--v2-ok); }
.mcp-dot.bad { background: var(--v2-danger); }
.mcp-name { flex: none; font-weight: 500; max-width: 140px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-url { flex: 1; min-width: 0; color: var(--v2-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-type { flex: none; color: var(--v2-text3); }
.mcp-state { flex: none; }
.mcp-state.ok { color: var(--v2-ok); }
.mcp-state.bad { color: var(--v2-danger); max-width: 320px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcp-checked { margin-left: 8px; font-size: 11px; color: var(--v2-text3); }
/* API Key 管理（6.5） */
.key-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.key-bar-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
.key-stat { font-size: 12px; color: var(--v2-text2); }
.key-stat b { color: var(--v2-text); font-weight: 600; }
.key-dim { color: var(--v2-text3); font-size: 12px; }
/* 名称与状态标签同一行：inline-flex 垂直居中（inline-block 的基线对齐会让标签高低不齐） */
.key-name-wrap { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; }
.key-name { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.key-tag { font-size: 11px; flex: none; line-height: 18px; }
.key-prefix { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: var(--v2-text2); }
/* 空态 */
.key-empty { text-align: center; padding: 36px 20px; border: 1px dashed var(--v2-border); border-radius: 8px; }
.key-empty-title { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.key-empty-desc { font-size: 12px; color: var(--v2-text3); margin-bottom: 14px; line-height: 1.7; }
/* 如何使用 */
.key-usage { margin-top: 16px; border-top: 1px solid var(--v2-border); padding-top: 10px; }
.key-usage-head { font-size: 12px; font-weight: 500; cursor: pointer; user-select: none; }
.key-usage-head:hover { color: var(--v2-accent); }
.key-usage-caret { display: inline-block; width: 12px; color: var(--v2-text3); }
.key-usage-body { padding: 10px 0 0 12px; }
.key-usage-label { font-size: 12px; color: var(--v2-text2); margin-bottom: 6px; }
.key-code { position: relative; background: #f6f7f9; border: 1px solid var(--v2-border); border-radius: 6px; padding: 10px 34px 10px 12px; }
.key-code code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--v2-text); }
.key-copy {
  position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border: none; border-radius: 5px;
  background: transparent; color: var(--v2-text3); cursor: pointer; font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
}
.key-copy:hover { background: var(--v2-accent-weak); color: var(--v2-accent); }
.key-copy-ok { color: var(--v2-ok); }
.key-usage-list { margin: 10px 0 0; padding-left: 18px; font-size: 12px; color: var(--v2-text2); line-height: 1.9; }
.key-usage-list code { background: #f2f3f5; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
/* 弹窗内 */
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.key-done-warn {
  background: #fff7e6; border: 1px solid #ffd591; color: #d46b08;
  border-radius: 6px; padding: 8px 10px; font-size: 12px; margin-bottom: 12px; line-height: 1.6;
}
.key-done-meta { font-size: 12px; color: var(--v2-text2); margin-bottom: 8px; }
.key-done-box {
  position: relative; background: #f6f7f9; border: 1px solid var(--v2-border); border-radius: 6px;
  padding: 12px 36px 12px 12px;
}
.key-done-box code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; word-break: break-all; color: var(--v2-text); }
/* 技能（Skills） */
.skill-dir-tip {
  font-size: 12px; color: var(--v2-text3); line-height: 1.8; margin-bottom: 12px;
  background: #f8f9fb; border: 1px solid var(--v2-border); border-radius: 6px; padding: 8px 10px;
}
.skill-dir-tip code { background: #eef0f3; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.skill-desc-warn { color: #a3691b; }
.skill-view-meta { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: var(--v2-text3); margin-bottom: 10px; }
.skill-view-meta code { background: #f2f3f5; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.skill-view-body { max-height: 56vh; overflow-y: auto; border: 1px solid var(--v2-border); border-radius: 6px; padding: 12px 14px; }
</style>
