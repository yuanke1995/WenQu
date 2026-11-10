<template>
  <div class="app-page" @dragover.prevent @dragenter.prevent="dragDepth++" @dragleave.prevent="dragDepth = Math.max(0, dragDepth - 1)" @drop.prevent="onDrop">
    <div class="app-page-head">
      <a-breadcrumb>
        <a-breadcrumb-item><a @click="router.push('/knowledge')">知识库</a></a-breadcrumb-item>
        <a-breadcrumb-item>{{ currentKbName }}<a-tag v-if="currentKbBuiltin" color="gold" style="margin-left:6px">官方 · 随版本自动同步</a-tag></a-breadcrumb-item>
      </a-breadcrumb>
      <span class="head-stat">{{ summaryText }}</span>
      <!-- 解析队列指示：上传只登记任务，队列逐个执行。让用户看到"在排队"而不是"没反应/丢了" -->
      <a-tooltip v-if="queueText" :title="'上传只登记解析任务，后台队列按并发逐个执行（队列满会直接拒绝新上传，不会先收下再丢）+ 失败任务可点「重解析」重试'">
        <span class="app-pill warn queue-chip">{{ queueText }}</span>
      </a-tooltip>
      <!-- 作废（终态失败）任务：可点击下钻看明细并处理，不是悬空数字 -->
      <a-tooltip v-if="queueStats.dead > 0" title="终态失败的解析任务——点击查看明细，可重解析或清理">
        <span class="app-pill err queue-chip dead-chip" @click="openDeadDrawer">作废 {{ queueStats.dead }}</span>
      </a-tooltip>
      <div class="doc-tools">
        <button v-if="canManageCurrentKb" class="app-btn ghost" @click="probeOpen = true">
          <play-circle-outlined /> 试检索
        </button>
        <button v-if="canManageCurrentKb" class="app-btn ghost" @click="graphModal = true">
          <apartment-outlined /> 图谱
        </button>
        <a-dropdown :trigger="['click']">
          <button class="app-btn ghost"><more-outlined /> 更多</button>
          <template #overlay>
            <a-menu @click="onMoreMenu">
              <a-menu-item key="recycle">回收站<span v-if="recycleCount" class="menu-badge">{{ recycleCount }}</span></a-menu-item>
              <a-menu-item v-if="canConfigKb" key="config">知识库配置</a-menu-item>
              <a-menu-item key="dead" :disabled="!queueStats.dead">作废任务 {{ queueStats.dead }}</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
        <!-- 上传门槛：对当前库有管理权（自己的库，或管理员）。内置库内容随版本同步，
             后端 DocumentController 会明确拒绝，故此处不开放——放开只会让用户点完吃到一句报错 -->
        <button v-if="canUploadToKb" class="app-btn ghost" :disabled="uploading || importing" @click="urlVisible = true">
          <link-outlined /> 网页导入
        </button>
        <button v-if="canUploadToKb" class="app-btn" :disabled="uploading" @click="openUpload">
          <upload-outlined /> {{ uploading ? '上传中…' : '上传文档' }}
        </button>
      </div>
    </div>
    <a-progress v-if="uploading" :percent="uploadPercent" size="small" style="max-width:420px;margin:10px 20px 0" />

    <!-- 网页导入：抓取 URL 正文入库（fileType=url，源文件为 HTML 快照） -->
    <a-modal v-model:open="urlVisible" title="网页导入" :confirm-loading="importing" :width="540" ok-text="抓取并导入" @ok="submitImportUrl">
      <a-alert type="info" show-icon message="每行一个 URL（最多 20 个），抓取正文后按与上传相同的链路分块入库" style="margin-bottom:10px" />
      <a-textarea v-model:value="urlText" :rows="5" placeholder="https://example.com/article" />
      <a-input v-model:value="urlDesc" placeholder="文档描述（可选）" style="margin-top:10px" allow-clear />
      <div style="margin-top:8px;color:var(--app-text3);font-size:12px">
        同名页面重复导入会替换旧内容；仅支持 http/https 公网地址
      </div>
    </a-modal>

    <!-- 上传文档：选文件 + 描述一个弹窗内完成 -->
    <a-modal v-model:open="uploadVisible" title="上传文档" :confirm-loading="uploading" :width="560" ok-text="开始上传" @ok="submitUpload">
      <a-upload-dragger :before-upload="collectPendingFile" :show-upload-list="false"
                        :accept="'.' + uploadCfg.allowedExts.join(',.')" multiple :disabled="uploading">
        <p class="ant-upload-drag-icon"><inbox-outlined /></p>
        <p class="ant-upload-text">点击或拖拽文件到此处</p>
        <p class="ant-upload-hint">支持 {{ uploadCfg.allowedExts.join(' / ') }}，单文件不超过 {{ uploadCfg.maxFileSizeLabel }}，可多选</p>
      </a-upload-dragger>
      <div v-if="pendingFiles.length" class="upload-pending">
        <div v-for="(f, i) in pendingFiles" :key="i" class="upload-pending-row">
          <file-outlined class="upf-ic" />
          <span class="upf-name" :title="f.name">{{ f.name }}</span>
          <span class="upf-size">{{ fmtSize(f.size) }}</span>
          <button class="app-icon-btn" :disabled="uploading" title="移除" @click="pendingFiles.splice(i, 1)"><close-outlined /></button>
        </div>
      </div>
      <a-input v-model:value="uploadDesc" placeholder="文档描述（可选）" style="margin-top:10px" allow-clear />
      <!-- 图片理解模型：高级项默认折叠成一行摘要，点「修改」才展开选择器（小白无感，直接上传即可） -->
      <div class="uv-row">
        <span class="uv-label">图片理解模型</span>
        <span v-if="!visionEditing" class="uv-summary" :title="visionSummary">{{ visionSummary }}</span>
        <button class="app-link-btn uv-toggle" @click="visionEditing = !visionEditing">{{ visionEditing ? '收起' : '修改' }}</button>
      </div>
      <div v-if="visionEditing" class="uv-edit">
        <ModelSelect v-model="uploadVisionRef" type="vision,ocr" width="100%" allow-clear
                     :inherit-label="visionInheritLabel" :placeholder="visionInheritLabel" />
        <div class="uv-hint">{{ visionHint }}</div>
      </div>
    </a-modal>

    <div class="app-page-body">
      <!-- 拖拽遮罩（仅对可管理的库提示上传） -->
      <div v-if="dragDepth > 0 && canManageCurrentKb" class="drag-mask">
        <div class="drag-mask-tip"><upload-outlined style="font-size:36px" /><div>松开鼠标上传到知识库</div></div>
      </div>

      <!-- 状态概览：点击即筛选，替代逐行扫描状态 -->
      <div class="doc-statusbar">
        <span :class="['st-chip', { on: statusFilter === 'all' }]" @click="statusFilter = 'all'">
          <b>全部 {{ statusCounts.all }}</b>
        </span>
        <span :class="['st-chip', { on: statusFilter === 'ok' }]" @click="statusFilter = 'ok'">
          <i class="d ok"></i>已入库 <b>{{ statusCounts.ok }}</b>
        </span>
        <span :class="['st-chip', { on: statusFilter === 'parsing', warn: statusCounts.parsing > 0 }]"
              @click="statusFilter = 'parsing'">
          <i class="d run"></i>解析中 <b>{{ statusCounts.parsing }}</b>
        </span>
        <span :class="['st-chip', { on: statusFilter === 'failed' }]" @click="statusFilter = 'failed'">
          <i class="d err"></i>失败 <b>{{ statusCounts.failed }}</b>
        </span>
        <span :class="['st-chip', { on: statusFilter === 'disabled' }]" @click="statusFilter = 'disabled'">
          <i class="d off"></i>已弃用 <b>{{ statusCounts.disabled }}</b>
        </span>
        <span class="st-chip" @click="openRecycle"><i class="d off"></i>回收站 <b>{{ recycleCount }}</b></span>
        <span class="sp"></span>
        <span v-if="queueText" class="queue-chip"><clock-circle-outlined /> {{ queueText }}</span>
        <span class="st-chip muted"><database-outlined /> {{ chunkSizeText }}</span>
      </div>

      <div class="doc-toolbar">
        <a-input v-model:value="docSearch" placeholder="搜索文件名、标签…" allow-clear style="width:240px">
          <template #prefix><search-outlined style="color:var(--app-text3)" /></template>
        </a-input>
        <span class="filter-field">
          <span class="filter-label">标签：</span>
          <a-select v-model:value="tagFilter" allow-clear :options="tagOptions" :bordered="false"
                    placeholder="全部" style="width:84px" :dropdown-match-select-width="false" />
        </span>
        <span class="sp"></span>
        <button class="app-btn ghost sm" @click="openGlobalSearch"><search-outlined /> 全局搜索</button>
        <button class="app-btn ghost sm" @click="fetchList"><reload-outlined /> 刷新</button>
      </div>

      <!-- 批量操作栏（需要当前库管理权） -->
      <div v-if="selectedKeys.length && canManageCurrentKb" class="batch-bar">
        <span>已选 {{ selectedKeys.length }} 项：</span>
        <button class="app-btn ghost" @click="batchStatus(0)">批量启用</button>
        <button class="app-btn ghost" @click="batchStatus(1)">批量弃用</button>
        <a-popconfirm title="确定删除选中的文档？知识库将同步移除" @confirm="batchDelete">
          <button class="app-btn danger">批量删除</button>
        </a-popconfirm>
        <a-popconfirm title="对选中文档重新解析+向量化？" @confirm="batchReparse">
          <button class="app-btn ghost">批量重解析</button>
        </a-popconfirm>
        <button class="app-link-btn" @click="selectedKeys = []">取消选择</button>
      </div>

      <!-- 文件列表（行式） -->
      <div class="app-card" style="padding:0;overflow:hidden">
        <div class="doc-row head-row">
          <span class="col-check"></span>
          <span class="col-name">文件名</span>
          <span class="col-num">知识块</span>
          <span class="col-num">命中</span>
          <span class="col-size">大小</span>
          <span class="col-status">状态</span>
          <span class="col-time">更新时间</span>
          <span class="col-act">操作</span>
        </div>
        <a-spin :spinning="loading">
          <template v-for="d in shownList" :key="d.id">
            <div :class="['doc-row', { 'row-open': expandedDocId === d.id }]">
              <span class="col-check" v-if="canManageCurrentKb"><a-checkbox :checked="selectedKeys.includes(d.id)" @change="e => toggleSelect(d.id, e)" /></span>
              <span v-else class="col-check"></span>
              <span class="col-name">
                <button class="doc-toggle" :title="expandedDocId === d.id ? '收起详情' : '展开详情'" @click.stop="toggleDoc(d)">
                  <down-outlined :class="{ flip: expandedDocId !== d.id }" />
                </button>
                <span class="file-ic" :style="{ background: typeColor(d.fileType).bg, color: typeColor(d.fileType).fg }">{{ (d.fileType || '?').toUpperCase().slice(0, 4) }}</span>
                <span class="file-name" :title="d.fileName + (d.description ? ' · ' + d.description : '') + (d.sourceUrl ? ' · 来源: ' + d.sourceUrl : '')" @click="toggleDoc(d)">
                  {{ d.fileName }}<i v-if="d.description" class="file-desc">{{ d.description }}</i>
                </span>
                <span v-if="docTags(d).length" class="doc-tags">
                  <a-tag v-for="t in docTags(d)" :key="t" class="doc-tag-chip">{{ t }}</a-tag>
                </span>
              </span>
              <span class="col-num">{{ d.chunkCount || 0 }}</span>
              <span class="col-num">{{ d.hitCount || 0 }}</span>
              <span class="col-size">{{ fmtSize(d.fileSize) }}</span>
              <span class="col-status">
                <a-tooltip v-if="d.status === 0 || d.status === 1 || d.status === 3" :title="chipTip(d)">
                  <span v-if="d.status === 0" class="app-pill ok">已入库</span>
                  <span v-else-if="d.status === 1" class="app-pill warn">已弃用</span>
                  <span v-else class="app-pill err" style="cursor:pointer" @click="showFailReason(d)">解析失败</span>
                </a-tooltip>
                <div v-else-if="d.status === 2" class="parse-cell">
                  <span class="app-pill warn">解析中</span>
                  <span class="parse-bar"><i :style="{ width: (d.parseProgress || 0) + '%' }"></i></span>
                  <span class="parse-desc" :title="d.parseDesc || '解析中'">{{ d.parseDesc || '解析中' }}</span>
                </div>
              </span>
              <span class="col-time">{{ fmtTime(d.updateTime || d.createTime) }}</span>
              <span class="col-act">
                <!-- 行内只留「看内容 / 拿文件 / 换状态」三个高频动作，其余全收进 ⋯。 -->
                <template v-if="d.status === 0">
                  <button class="app-link-btn" @click="openKb(d)">知识块</button>
                  <button v-if="canManageDoc(d)" class="app-link-btn" @click="toggleStatus(d, 1)">弃用</button>
                </template>
                <button v-else-if="d.status === 1 && canManageDoc(d)" class="app-link-btn" @click="toggleStatus(d, 0)">启用</button>
                <!-- 源文件下载（个人文件区）：解析中的文档源文件可能正在读写，仅非解析中提供 -->
                <button v-if="d.status !== 2" class="app-link-btn" @click="dlSource(d)">下载</button>
                <a-dropdown v-if="rowMenu(d).length" :trigger="['click']" placement="bottomRight">
                  <button class="app-icon-btn row-more" type="button" title="更多操作"><more-outlined /></button>
                  <template #overlay>
                    <a-menu @click="info => onRowMenu(d, String(info && info.key))">
                      <a-menu-item v-for="m in rowMenu(d)" :key="m.key" :disabled="m.disabled">
                        <span :class="{ 'row-danger': m.danger }" :title="m.tip">{{ m.label }}</span>
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </span>
            </div>
            <div v-if="expandedDocId === d.id" class="doc-expand">
              <div class="de-meta">
                <div class="de-row">
                  <span class="de-label">描述</span>
                  <span class="de-val">{{ d.description || '暂无描述' }}</span>
                </div>
                <div class="de-row">
                  <span class="de-label">标签</span>
                  <span class="de-val">
                    <template v-if="docTags(d).length">
                      <a-tag v-for="t in docTags(d)" :key="t" class="doc-tag-chip">{{ t }}</a-tag>
                    </template>
                    <span v-else class="de-muted">未设置</span>
                  </span>
                </div>
                <div class="de-row">
                  <span class="de-label">来源</span>
                  <span class="de-val de-ellipsis" :title="d.sourceUrl || '本地上传'">
                    <a v-if="d.sourceUrl" :href="d.sourceUrl" target="_blank" rel="noopener">{{ d.sourceUrl }}</a>
                    <span v-else>本地上传</span>
                  </span>
                </div>
                <div class="de-row">
                  <span class="de-label">解析</span>
                  <span class="de-val">{{ d.status === 2 ? (d.parseDesc || '解析中') : statusText(d.status) }}<span v-if="d.parseProgress && d.status === 2"> · {{ d.parseProgress }}%</span></span>
                </div>
                <div class="de-row de-row-kb">
                  <span class="de-label">归属</span>
                  <span class="de-val de-val-kb">
                    <a-select v-if="canManageDoc(d)" :value="kbOf(d)" size="small"
                              :options="kbSelectOptions" :dropdown-match-select-width="false"
                              :title="'切换所属知识库：决定哪些助手能检索到该文档'"
                              @change="v => onMoveKb(d, v)" />
                    <template v-else>{{ kbNameOf(d) }}</template>
                  </span>
                  <span class="de-links">
                    <button v-if="d.status !== 2" class="app-link-btn" @click="openKb(d)">查看知识块</button>
                    <button v-if="canManageDoc(d)" class="app-link-btn" @click="openTagEdit(d)">编辑标签</button>
                    <button v-if="d.status !== 2" class="app-link-btn" @click="dlSource(d)">下载源文件</button>
                  </span>
                </div>
              </div>
            </div>
          </template>
          <a-empty v-if="!loading && !shownList.length" :description="`暂无匹配文档，点击右上角上传 ${uploadCfg.allowedExts.join(' / ')} 或导入网页`" style="padding:40px 0" />
        </a-spin>
        <div class="doc-foot">
          <span>{{ summaryText }}</span>
          <span class="sp"></span>
          <span class="doc-foot-note">解析完成后自动参与检索</span>
        </div>
      </div>

      <!-- 上传队列：逐文件展示本轮提交与结果；收起后保留右下角悬浮入口，随时可展开 -->
      <button v-if="uploadQueue.length && queueCollapsed" class="uploadq-pill" type="button"
              @click="queueCollapsed = false">
        <upload-outlined />
        上传队列 · {{ uploading ? uploadPercent + '%' : uploadQueue.length + ' 项' }}
        <span v-if="uploading" class="upl-run">处理中</span>
        <span class="upl-open">展开</span>
      </button>
      <div v-else-if="uploadQueue.length" class="uploadq">
        <div class="uq-head">
          <upload-outlined />
          上传队列（{{ uploadQueue.length }}）
          <span class="sp"></span>
          <span class="uq-pct">{{ uploading ? uploadPercent + '%' : '完成' }}</span>
          <button class="app-link-btn uq-close" @click="queueCollapsed = true">收起</button>
        </div>
        <div v-for="(f, i) in uploadQueue" :key="i" class="uq-row">
          <file-outlined class="uq-ic" />
          <div class="uq-main">
            <div class="uq-name" :title="f.name">{{ f.name }}</div>
            <div class="uq-sub">{{ f.status === 'error' ? '提交失败，可重试' : (f.status === 'done' ? '已提交解析，后台处理中' : '上传中…') }}</div>
            <div class="uq-track"><i :style="{ width: (f.progress || 0) + '%' }"></i></div>
          </div>
          <span :class="['uq-state', f.status]">{{ f.status === 'error' ? '失败' : (f.status === 'done' ? '完成' : (f.progress || 0) + '%') }}</span>
        </div>
      </div>
    </div>

    <!-- 回收站：本库软删文档。恢复=原样回来（向量未动，检索即时可用）；彻底删除=物理清除不可恢复 -->
    <a-modal v-model:open="recycleVisible" :title="`回收站 · ${currentKbName || '当前知识库'}`" :footer="null" :width="900">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px">
        <span style="font-size:12px;color:var(--app-text3)">共 {{ recycleRows.length }} 个已删除文档，内容保留中（向量/知识块/源文件不动，可随时恢复）</span>
        <a-popconfirm v-if="recycleRows.length" title="彻底删除本库回收站全部文档？不可恢复" @confirm="doPurgeRecycle">
          <button class="app-btn ghost sm" :disabled="recycleBusy" style="margin-left:auto">清空回收站</button>
        </a-popconfirm>
      </div>
      <!-- 批量操作栏：勾选后才出现，与文档列表同款（清空=整库，批量=选中项） -->
      <div v-if="recycleSelected.length" class="batch-bar">
        <span>已选 {{ recycleSelected.length }} 项：</span>
        <button class="app-btn ghost" :disabled="recycleBusy" @click="doBatchRestore">批量恢复</button>
        <a-popconfirm title="彻底删除选中文档？向量/知识块/源文件物理清除，不可恢复" @confirm="doBatchPurge">
          <button class="app-btn danger" :disabled="recycleBusy">批量彻底删除</button>
        </a-popconfirm>
        <button class="app-link-btn" @click="recycleSelected = []">取消选择</button>
      </div>
      <!-- 列宽走 :columns 声明：a-table-column 上的 width 不进 colgroup -->
      <a-table :data-source="recycleRows" :columns="recycleCols" size="small" row-key="id"
               :loading="recycleLoading" table-layout="fixed"
               :row-selection="{ selectedRowKeys: recycleSelected, onChange: keys => recycleSelected = keys, columnWidth: 44 }"
               :pagination="{ pageSize: 20, size: 'small', hideOnSinglePage: true, showTotal: t => `共 ${t} 条` }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'size'"><span class="rc-nowrap">{{ fmtSize(record.fileSize) }}</span></template>
          <template v-else-if="column.key === 'deletedAt'">
            <span class="rc-nowrap">{{ String(record.deletedAt || '').replace('T', ' ').slice(0, 19) || '—' }}</span>
          </template>
          <template v-else-if="column.key === 'act'">
            <span class="rc-nowrap">
              <button class="app-link-btn" :disabled="recycleBusy" @click="doRestore(record)">恢复</button>
              <a-popconfirm title="彻底删除？向量/知识块/源文件物理清除，不可恢复" @confirm="doPurge(record)">
                <button class="app-link-btn danger" :disabled="recycleBusy" style="margin-left:10px">彻底删除</button>
              </a-popconfirm>
            </span>
          </template>
        </template>
      </a-table>
    </a-modal>

    <!-- 知识块预览。勿加 destroy-on-close：关闭会销毁 Portal，重开时容器被重新追加到 body 末尾，
         而本页弹窗 z-index 同值（zIndexPopupBase），DOM 靠后者在上——会反把「编辑知识块」弹窗盖住。 -->
    <a-modal v-model:open="kbVisible" :title="'知识块预览 · ' + kbDocName" :footer="null" :width="1080" :keyboard="!kbImgUrl">
      <div style="margin-bottom:10px; display:flex; gap:8px; align-items:center">
        <a-input-search v-model:value="kbSearch" placeholder="按标题/内容过滤知识块" allow-clear style="flex:1" />
        <a-radio-group v-model:value="kbView" size="small" button-style="solid">
          <a-radio-button value="list">切片列表</a-radio-button>
          <a-radio-button value="tree">结构导图</a-radio-button>
        </a-radio-group>
      </div>
      <!-- 切片统计（2.9）：块数 / 合计与平均 token / 未向量化块数，随过滤实时变化 -->
      <div class="kb-stat">
        <span>共 <b>{{ kbFilteredList.length }}</b> 块</span>
        <span>合计 <b>{{ fmtTokens(kbStatTokens) }}</b> tokens</span>
        <span>平均 <b>{{ kbFilteredList.length ? Math.round(kbStatTokens / kbFilteredList.length) : 0 }}</b></span>
        <span v-if="kbNoVector" class="kb-stat-warn">未向量化 {{ kbNoVector }} 块</span>
      </div>
      <a-spin :spinning="kbLoading">
        <!-- 结构导图（3.3 知识导图 / 3.5 文件元数据导图）：按 titlePath 聚合成章节树，
             每节点带块数/token/图片数，点章节名可直接过滤到该章节的切片 -->
        <div v-if="kbView === 'tree'" class="kb-tree">
          <div class="kb-tree-head">
            <span>章节结构</span>
          </div>
          <div class="kb-tree-body">
            <div v-for="r in kbTreeRows" :key="r.path" class="kb-tree-row" :style="{ paddingLeft: (r.depth * 16 + 4) + 'px' }">
              <span class="kb-tree-toggle" @click="toggleTreeNode(r.path)">{{ r.hasChild ? (r.open ? '−' : '+') : '·' }}</span>
              <span class="kb-tree-name" :title="r.path" @click="focusTreeNode(r)">{{ r.name }}</span>
              <span class="kb-tree-meta">{{ r.count }} 块 · {{ fmtTokens(r.tokens) }} · {{ r.imgs }} 图</span>
            </div>
            <div v-if="!kbTreeRows.length" class="kb-tree-empty">暂无结构数据（该文档知识块缺少章节路径）</div>
          </div>
        </div>
        <template v-else>
        <!-- 章节筛选标识（结构导图点章节名后按路径精确过滤），可一键清除 -->
        <div v-if="kbPathFilter" class="kb-path-chip">
          章节：<b :title="kbPathFilter">{{ kbPathFilter }}</b>
          <button class="kb-path-chip-x" title="清除章节筛选" @click="kbPathFilter = ''">×</button>
        </div>
        <!-- 表体内部滚动（表头固定）：一页 20 条在矮屏会超出屏幕，高度随视口自适应。
             点行内联展开完整内容（对齐片段卡片：内容直读，不再叠二级弹窗） -->
        <a-table :key="kbDocId" :data-source="kbFilteredList" size="small" row-key="id"
                 :pagination="{ pageSize: 20, size: 'small', hideOnSinglePage: true, showTotal: t => `共 ${t} 条` }"
                 :scroll="{ y: kbScrollY }"
                 :locale="{ emptyText: kbEmptyText }"
                 :expand-row-by-click="true"
                 :expanded-row-keys="kbExpandedKeys"
                 @expand="onKbExpand"
                 style="cursor:pointer">
          <a-table-column title="#" dataIndex="chunkIndex" key="chunkIndex" width="40" />
          <a-table-column title="状态" key="status" width="76">
            <template #default="{ record }">
              <span v-if="(record.status ?? 0) === 0" class="app-pill ok">生效</span>
              <span v-else class="app-pill warn">已停用</span>
            </template>
          </a-table-column>
          <a-table-column title="标题" key="title" ellipsis>
            <template #default="{ record }">
              <span><template v-for="(p, pi) in kbHighlightParts(record.title)" :key="pi"><mark v-if="p.hit" class="kb-hl">{{ p.t }}</mark><template v-else>{{ p.t }}</template></template></span>
              <a-tag v-if="!record.vectorId" color="orange" style="margin-left:6px;font-size:11px">未向量化</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="Token" key="tokens" width="64" align="right">
            <template #default="{ record }">
              <span class="kb-tok" :title="'估算值（前端量级参考）：标题+正文'">{{ estimateTokens((record.title || '') + (record.content || '')) }}</span>
            </template>
          </a-table-column>
          <a-table-column title="内容摘要" key="snippet">
            <template #default="{ record }">
              <div class="kb-snippet"><template v-for="(p, pi) in kbHighlightParts(kbCollapseWs(record.content))" :key="pi"><mark v-if="p.hit" class="kb-hl">{{ p.t }}</mark><template v-else>{{ p.t }}</template></template></div>
            </template>
          </a-table-column>
          <a-table-column title="操作" key="action" width="130">
            <template #default="{ record }">
              <!-- 知识块编辑/停用/删除是文档管理动作：仅对文档有管理权的人可见 -->
              <template v-if="canManageDoc(currentDoc)">
                <button class="app-link-btn" @click.stop="openKbEdit(record)">编辑</button>
                <button v-if="(record.status ?? 0) === 0" class="app-link-btn" @click.stop="toggleKbStatus(record, 1)">停用</button>
                <button v-else class="app-link-btn" @click.stop="toggleKbStatus(record, 0)">启用</button>
                <a-popconfirm title="确定删除该知识块？向量将同步移除" ok-text="删除" cancel-text="取消" @confirm.stop="delKnowledge(record.id)">
                  <button class="app-link-btn danger" @click.stop>删除</button>
                </a-popconfirm>
              </template>
              <span v-else class="key-dim" style="font-size:11px">只读</span>
            </template>
          </a-table-column>
          <!-- 行内展开直读：元信息（章节/Token/图片）+ 完整 Markdown；图片走详情接口签名，先用列表数据即时渲染 -->
          <template #expandedRowRender="{ record }">
            <div class="kb-expand">
              <div class="kb-expand-meta">
                <span v-if="record.titlePath" class="kb-expand-path" :title="record.titlePath">{{ record.titlePath }}</span>
                <span>~{{ estimateTokens((record.title || '') + (record.content || '')) }} tokens</span>
                <span v-if="imgCountOf(record)">{{ imgCountOf(record) }} 图</span>
                <button class="app-link-btn kb-expand-copy" @click.stop="copyKbContent(record)">复制内容</button>
              </div>
              <div class="md kb-expand-md" @click="openKbImgPreview" v-html="kbChunkHtml(record)"></div>
            </div>
          </template>
        </a-table>
        </template>
      </a-spin>
    </a-modal>

    <!-- 知识块编辑弹窗：Markdown 工具栏快捷插入 + 左写右看实时预览 + [图片N] 点选插入 + 未保存关闭提醒 -->
    <a-modal :open="kbEditVisible" title="编辑知识块" :footer="null" :width="900" :keyboard="!kbImgUrl" @cancel="closeKbEdit">
      <a-form layout="vertical">
        <a-form-item label="标题"><a-input v-model:value="kbEditForm.title" :maxlength="200" placeholder="知识块标题" /></a-form-item>
        <a-form-item label="内容">
          <div class="kb-md-bar">
            <a-space :size="2" wrap>
              <a-button size="small" type="text" title="二级标题" @click="kbLinePrefix('## ')">H2</a-button>
              <a-button size="small" type="text" title="三级标题" @click="kbLinePrefix('### ')">H3</a-button>
              <a-button size="small" type="text" title="加粗（Ctrl/⌘+B）" style="font-weight:700" @click="kbWrap('**', '**', '加粗文字')">B</a-button>
              <a-button size="small" type="text" title="无序列表" @click="kbLinePrefix('- ')">列表</a-button>
              <a-button size="small" type="text" title="有序列表" @click="kbLinePrefix('', true)">1. 列表</a-button>
              <a-button size="small" type="text" title="引用" @click="kbLinePrefix('> ')">引用</a-button>
              <a-button size="small" type="text" title="插入表格模板" @click="kbInsertTable">表格</a-button>
              <a-button size="small" type="text" title="代码块" @click="kbWrap('\n```\n', '\n```\n', '代码')">代码</a-button>
              <a-button size="small" type="text" title="链接" @click="kbWrap('[', '](https://)', '链接文字')">链接</a-button>
            </a-space>
            <a-dropdown :trigger="['click']">
              <a-button size="small" type="text" :disabled="!kbEditImages.length"
                        :title="kbEditImages.length ? '点击插入图片占位符' : '该知识块无关联图片'">
                图片<down-outlined style="font-size:10px;margin-left:3px" />
              </a-button>
              <template #overlay>
                <a-menu @click="e => kbWrap(`[图片${e.key}]`)">
                  <a-menu-item v-for="(u, i) in kbEditImages" :key="i + 1" class="kb-img-item">
                    <img class="kb-img-thumb" :src="resolveImg(u)" @error="onImgError" />图片{{ i + 1 }}
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
          <div class="kb-edit-split">
            <a-textarea ref="kbTaRef" v-model:value="kbEditForm.content" class="kb-edit-ta"
                        placeholder="支持 Markdown 语法，可用上方工具栏快捷插入；右侧为实时预览"
                        @keydown="onTaKeydown" />
            <div class="kb-edit-preview md" @click="openKbImgPreview" v-html="kbEditPreviewHtml"></div>
          </div>
        </a-form-item>
      </a-form>
      <div style="text-align:right">
        <button class="app-btn ghost" style="margin-right:8px" @click="closeKbEdit">取消</button>
        <button class="app-btn" :disabled="kbEditSaving" @click="saveKnowledgeEdit">{{ kbEditSaving ? '保存中...' : '保存' }}</button>
      </div>
    </a-modal>

    <!-- 全局搜索 -->
    <a-modal v-model:open="gSearchVisible" title="知识块全局搜索" :footer="null" :width="820">
      <div style="display:flex;gap:8px;margin-bottom:12px">
        <a-input-search v-model:value="gSearchKw" placeholder="输入关键词，跨全部文档搜索知识块（含已停用）" enter-button="搜索" :loading="gSearchLoading" @search="doGlobalSearch" />
      </div>
      <a-table :data-source="gResults" size="small" row-key="id"
               :pagination="{ pageSize: 10, size: 'small', hideOnSinglePage: true, showTotal: t => `共 ${t} 条` }"
               :locale="{ emptyText: '输入关键词后搜索' }"
               :custom-row="r => ({ onClick: () => openKbDetail(r) })" style="cursor:pointer">
        <a-table-column title="文档" dataIndex="docName" key="docName" width="160" ellipsis />
        <a-table-column title="标题" key="title" width="150" ellipsis>
          <template #default="{ record }">{{ record.title || '（无标题）' }}</template>
        </a-table-column>
        <a-table-column title="内容摘要" key="snippet" ellipsis>
          <template #default="{ record }">{{ record.snippet }}</template>
        </a-table-column>
      </a-table>
    </a-modal>

    <!-- 作废（终态失败）解析任务明细：下钻看是哪些任务失败、为何失败，可重解析或清理孤儿任务 -->
    <a-drawer v-model:open="deadDrawerVisible" title="作废的解析任务" width="640" :footer="null">
      <a-spin :spinning="deadLoading">
        <a-empty v-if="!deadLoading && deadList.length === 0" description="没有终态失败的解析任务" />
        <div v-else class="dead-list">
          <div v-for="row in deadList" :key="row.taskId" class="dead-card">
            <div class="dead-card-head">
              <span class="dead-name">{{ row.fileName }}</span>
              <span class="dead-meta">尝试 {{ row.attempt }}/{{ row.maxAttempt }}</span>
            </div>
            <div class="dead-reason">{{ row.error }}</div>
            <div class="dead-foot">
              <span class="dead-time">{{ row.finishTime || '—' }}</span>
              <span class="dead-acts">
                <a-button size="small" type="link" :loading="deadReparsingId === row.taskId" @click="reparseFromDead(row)">重解析</a-button>
                <a-button size="small" type="link" danger :loading="deadDeletingId === row.taskId" @click="deleteDeadTask(row)">删除任务</a-button>
              </span>
            </div>
          </div>
        </div>
      </a-spin>
    </a-drawer>

    <!-- 知识块详情（全局搜索打开）：加载/失败态齐全，失败可重试 -->
    <a-modal v-model:open="kbDetailVisible" :title="kbDetail?.title || '知识块详情'" :footer="null" :width="720" :keyboard="!kbImgUrl">
      <a-spin :spinning="kbDetailLoading">
        <div v-if="kbDetailErr" class="kb-detail-err">
          {{ kbDetailErr }}
          <button class="app-link-btn" @click="openKbDetail(kbDetailRow)">重试</button>
        </div>
        <div v-else class="md" style="max-height:60vh;overflow-y:auto;font-size:14px;line-height:1.7;min-height:80px"
             @click="openKbImgPreview" v-html="kbDetailHtml"></div>
      </a-spin>
    </a-modal>

    <!-- 版本管理 -->
    <a-modal v-model:open="verVisible" :title="'版本历史 · ' + verDocName" :footer="null" :width="560">
      <a-spin :spinning="verLoading">
        <a-table :data-source="verList" size="small" row-key="version" :pagination="false" :locale="{ emptyText: '暂无版本记录' }">
          <a-table-column title="版本" dataIndex="version" key="version" width="80" />
          <a-table-column title="知识块数" dataIndex="chunkCount" key="chunkCount" width="100" />
          <a-table-column title="创建时间" dataIndex="createTime" key="createTime" />
          <a-table-column title="操作" key="action" width="100">
            <template #default="{ record }">
              <a-popconfirm v-if="canManageDoc(currentDoc)" :title="`确定回滚到 v${record.version}？当前版本将被覆盖`" ok-text="回滚" cancel-text="取消" @confirm="doRollback(record.version)">
                <button class="app-link-btn">回滚</button>
              </a-popconfirm>
            </template>
          </a-table-column>
        </a-table>
      </a-spin>
    </a-modal>

    <!-- 文档标签：从行内 ⋯ 打开（即改即存，检索侧直接生效，无需重建索引） -->
    <a-modal v-model:open="tagVisible" :title="'文档标签 · ' + tagDocName" :width="480" ok-text="保存" @ok="saveTags">
      <a-select v-model:value="tagDraft" mode="tags" style="width:100%" :token-separators="[',', '，']"
                placeholder="输入后回车添加标签" />
      <div class="modal-note">供智能体「检索标签收窄」与列表筛选；检索侧即时生效，无需重建索引</div>
    </a-modal>

    <!-- 网页源自动刷新：草稿式编辑，点取消不会把行数据改脏 -->
    <a-modal v-model:open="refreshVisible" :title="'自动刷新网页源 · ' + refreshDocName" :width="480" ok-text="保存" @ok="saveRefresh">
      <div class="rf-row">
        <a-switch v-model:checked="refreshDraft.enabled" />
        <span>开启后按周期重新抓网重建</span>
      </div>
      <template v-if="refreshDraft.enabled">
        <div class="modal-note">刷新周期（5 段：分 时 日 月 周）</div>
        <a-input v-model:value="refreshDraft.cron" placeholder="0 3 * * *" />
        <div class="modal-note">下次刷新：{{ refreshDraft.nextAt || '—' }}</div>
      </template>
    </a-modal>

    <!-- 知识库配置（与知识库列表页同一弹窗组件）：当前库的解析/检索参数就地改，保存后刷新库信息 -->
    <KnowledgeBaseEditModal v-model:open="kbCfgVisible" :kb="currentKb" :fix="kbCfgFix" @saved="fetchKbs" />

    <!-- 知识图谱（与知识库列表页同一弹窗组件）：库详情页的「图谱」入口 -->
    <KnowledgeGraphModal v-model:open="graphModal" :kb="currentKb" />

    <!-- 试检索：只走检索/重排链路，验证本库召回质量 -->
    <KbProbeModal v-model:open="probeOpen" :kb="currentKb" />

    <!-- 图片灯箱（知识块内容里的图片点击放大）：多图切换 / 滚轮缩放 / 拖动平移 / ESC 关闭 -->
    <div v-if="kbImgUrl" class="lightbox" @click="closeKbImg" @wheel.prevent="onKbImgWheel">
      <img :src="kbImgUrl" alt="大图预览" @click.stop @error="onImgError" class="lightbox-img"
           :style="{ transform: 'translate(' + kbImgOffset.x + 'px,' + kbImgOffset.y + 'px) scale(' + kbImgZoom + ')' }"
           @mousedown="onKbImgMouseDown" @mousemove="onKbImgMouseMove" @mouseup="onKbImgMouseUp" @mouseleave="onKbImgMouseUp" @dblclick="resetKbImgView" />
      <button v-if="kbImgList.length > 1" class="lightbox-prev" :disabled="kbImgIndex === 0" @click.stop="kbImgPrev">‹</button>
      <button v-if="kbImgList.length > 1" class="lightbox-next" :disabled="kbImgIndex === kbImgList.length - 1" @click.stop="kbImgNext">›</button>
      <span class="lightbox-close" @click.stop="closeKbImg">×</span>
      <span v-if="kbImgList.length > 1" class="lightbox-count">{{ kbImgIndex + 1 }} / {{ kbImgList.length }}</span>
      <span class="lightbox-tip">滚轮缩放 · 拖动平移 · 双击重置 · ESC 关闭</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch, h } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { UploadOutlined, SearchOutlined, DownOutlined, LinkOutlined, SettingOutlined,
         InboxOutlined, FileOutlined, CloseOutlined, ApartmentOutlined, MoreOutlined,
         PlayCircleOutlined, ClockCircleOutlined, DatabaseOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { listDocuments, uploadDocumentsBatch, updateDocumentStatus, reparseDocument, deleteDocument,
         batchDeleteDocuments, batchUpdateDocumentStatus, getDocumentStats, listKnowledgeByDoc, getKnowledgeDetail,
         updateKnowledge, deleteKnowledge, listDocumentVersions, rollbackDocument,
         getRuntimeConfig, batchReparseDocuments, updateKnowledgeStatus, searchKnowledge,
         downloadDocumentSource, listKnowledgeBases, moveDocToKb, importDocumentFromUrl, refreshConfigDocument,
         getDocumentQueueStats, listAvailableModels, backfillDescriptions, setDocumentTags,
         getDocumentDeadTasks, deleteParseTask,
         listRecycleDocuments, restoreDocument, purgeDocument, purgeRecycleBin,
         batchRestoreRecycled, batchPurgeRecycled } from '../api'
import KnowledgeBaseEditModal from '../components/KnowledgeBaseEditModal.vue'
import KnowledgeGraphModal from '../components/KnowledgeGraphModal.vue'
import KbProbeModal from '../components/KbProbeModal.vue'
import ModelSelect from '../components/ModelSelect.vue'
import { renderMd, prepKnowledgeContent, resolveImg, onImgError, copyCode } from '../utils/markdown'
import { copyText } from '../utils/clipboard'
import { estimateTokens, fmtTokens } from '../utils/token'
import { isAdminSync, ensureAuth } from '../utils/auth'
import { mobileShell } from '../h5/mobile'

// 上传限制（启动时从 /config/public 动态获取）
const MAX_SIZE = 200 * 1024 * 1024
const uploadCfg = ref({ maxFileSize: MAX_SIZE, maxFileSizeLabel: '200MB', allowedExts: ['csv', 'docx', 'markdown', 'md', 'pdf', 'ppt', 'pptx', 'txt', 'xlsx'] })
const loadUploadCfg = async () => {
  try {
    const r = await getRuntimeConfig()
    if (r.success && r.data?.upload) {
      const u = r.data.upload
      if (Number(u.maxFileSize) > 0) uploadCfg.value.maxFileSize = Number(u.maxFileSize)
      if (u.maxFileSizeLabel) uploadCfg.value.maxFileSizeLabel = u.maxFileSizeLabel
      if (Array.isArray(u.allowedExts) && u.allowedExts.length) uploadCfg.value.allowedExts = u.allowedExts
    }
  } catch (e) { /* 保持默认 */ }
}

// 文件类型图标配色
/** 状态芯片悬浮：只展示终态描述（解析完成/解析失败…）。
 *  历史数据可能残留"重新解析中"等过程态描述而状态已是已入库——与状态矛盾的过程态不出悬浮 */
const chipTip = d => {
  const desc = d.parseDesc || ''
  if (d.status === 3) return d.failReason || desc || ''
  return desc.startsWith('解析完成') || desc.startsWith('解析失败') ? desc : ''
}

const typeColor = t => {
  const map = {
    docx: { bg: 'var(--app-info-weak)', fg: 'var(--app-accent)' }, doc: { bg: 'var(--app-info-weak)', fg: 'var(--app-accent)' },
    pdf: { bg: 'var(--app-danger-weak)', fg: 'var(--app-danger-text)' }, xlsx: { bg: 'var(--app-ok-weak)', fg: 'var(--app-ok)' }, xls: { bg: 'var(--app-ok-weak)', fg: 'var(--app-ok)' },
    ppt: { bg: 'var(--app-accent-weak)', fg: 'var(--app-accent)' }, pptx: { bg: 'var(--app-accent-weak)', fg: 'var(--app-accent)' },
    url: { bg: 'var(--app-accent-weak)', fg: 'var(--app-accent)' }
  }
  return map[(t || '').toLowerCase()] || { bg: 'var(--app-panel-2)', fg: 'var(--app-text2)' }
}

const route = useRoute()
const router = useRouter()
// 权限：文档管理对普通用户按"自建自管"开放——自己的库/文档可管理，别人共享的只读
const isAdmin = isAdminSync()
const myUid = ref('')
/** 当前库是否官方内置（内容随版本自动同步，对所有人只读，含管理员） */
const currentKbBuiltin = computed(() => {
  const k = kbases.value.find(x => x.id === currentKbId.value)
  return !!k && k.builtin === 1
})
/** 当前库是否可管理（上传/批量操作的门槛；官方内置库对所有人只读） */
const canManageCurrentKb = computed(() => {
  // 移动壳（H5）只读：这是 DesktopOnlyGuard 对 /knowledge/:id/docs 的承诺（文档列表 + 知识块预览），
  // 上传/导入/批量/删除/重解析/配置/图谱一律不渲染。判据用 mobileShell 而不是 isNarrow：
  // 手机横屏不算「窄屏」但仍是 H5，桌面把窗口拖窄仍该保留 PC 行为。
  // 收在这两个根判定上而不是逐个按钮加 v-if：漏一处就是假文案。
  if (mobileShell.value) return false
  // 官方内置库：文档内容只读（上传/删除篇目都不开放，与后端 DocumentController 一致），
  // 但**「知识库配置」对管理员开放**——检索/解析参数与向量模型是运行时配置，管理员要能改
  // （后端 KnowledgeBaseService.update 的内置库白名单即这三个字段）。
  // 管理员级也要放行：内置库 createdBy=system ⇒ 任何人都拿不到 MANAGE，只判「是否本人创建」
  // 的话管理员也会被误挡在门外。这与后端 requireManage 对内置库走 roleService.isAdminCode 同口径。
  if (currentKbBuiltin.value) return isAdmin
  if (isAdmin) return true
  const k = kbases.value.find(x => x.id === currentKbId.value)
  return !!k && k.createdBy === myUid.value
})
/**
 * 「知识库配置」是否可见：内置库对管理员开放，其余按常规管理权。
 * 与 canManageCurrentKb 拆开的原因：这两个能力的粒度不同——配置是运行时参数（管理员可改），
 * 写入内容则受内置库的只读约束。合成一个判定会让「放开配置」顺带放开「上传」（后端会拒），
 * 或反之把配置也一起挡掉。
 */
const canConfigKb = computed(() => canManageCurrentKb.value)
/** 能否往当前库写入内容：内置库一律不可（内容随版本同步，后端会明确拒绝） */
const canUploadToKb = computed(() => canManageCurrentKb.value && !currentKbBuiltin.value)

/** 单个文档是否可管理（创建者或管理员；后端还会按共享范围二次判定；官方内置库的篇目只读） */
const canManageDoc = d => !mobileShell.value && !currentKbBuiltin.value && (isAdmin || (myUid.value && d.createdBy === myUid.value))
/** 当前所在知识库（路由参数）：列表/上传都限定在该库 */
const currentKbId = computed(() => String(route.params.kbId || ''))
const currentKbName = computed(() => {
  const k = kbases.value.find(x => x.id === currentKbId.value)
  return k ? k.name : '知识库'
})

// ==================== 知识库配置（文档列表页直达） ====================
// 当前库行对象来自 fetchKbs 的列表缓存（含 parseParams/queryParams），弹窗组件据此回填表单
const kbCfgVisible = ref(false)
const currentKb = computed(() => kbases.value.find(x => x.id === currentKbId.value) || null)
// 深链定位键：模型供应商的「查看引用」点「去修改」时带 ?kbcfg=1&fix=<槽位>，
// 要落进这个弹窗里的那一项，而不是把人丢在文档列表页自己找右上角按钮
const kbCfgFix = ref('')
watch(kbCfgVisible, open => { if (!open) kbCfgFix.value = '' })

const applyKbCfgDeepLink = async () => {
  if (route.query.kbcfg !== '1') return
  kbCfgFix.value = typeof route.query.fix === 'string' ? route.query.fix : ''
  // currentKb 要等 fetchKbs 回来；拿不到库行就开不出弹窗（组件按 kb 回填表单）
  for (let i = 0; i < 30 && !currentKb.value; i++) await new Promise(r => setTimeout(r, 100))
  if (!currentKb.value) {
    message.warning('知识库信息没加载出来，请点右上角「知识库配置」重试')
    return
  }
  kbCfgVisible.value = true
  // 参数用完就摘掉：留着的话刷新一次再弹一次，用户会以为改动没存住
  router.replace({ path: route.path, query: { ...route.query, kbcfg: undefined, fix: undefined } })
}

// ==================== 知识图谱（§5：与库列表页共用同一弹窗组件） ====================
const graphModal = ref(false)
const openKbConfig = () => {
  if (!currentKb.value) {
    message.warning('知识库信息尚未加载完成，请稍候重试')
    return
  }
  kbCfgVisible.value = true
}

const list = ref([])
const currentDoc = ref(null)   // 当前打开抽屉（知识块/版本）的文档行：判定行内管理按钮显隐
const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const reparsingId = ref('')
const dragDepth = ref(0)
const selectedKeys = ref([])
const statusFilter = ref('all')
const docSearch = ref('')
const expandedDocId = ref('')
const uploadQueue = ref([])
/** 队列收起态：收起只折叠卡片，右下角保留悬浮入口；新一轮上传自动展开 */
const queueCollapsed = ref(false)
const probeOpen = ref(false)
let pollTimer = null
let pollTicks = 0
/** 解析队列计数（后端 c_ai_parse_task 统计，非 table_rows 估算）；按当前库过滤，别的库在跑不在本页显数 */
const queueStats = ref({ queued: 0, running: 0, dead: 0 })
const queueText = computed(() => {
  const q = queueStats.value
  const total = Number(q.queued || 0) + Number(q.running || 0)
  return total > 0 ? `队列 ${total} 个解析任务` : ''
})
async function fetchQueueStats () {
  try {
    const r = await getDocumentQueueStats(currentKbId.value || route.params.kbId)
    const d = r && r.success ? r.data : null
    if (d) {
      queueStats.value = {
        queued: Number(d.queued || 0),
        running: Number(d.running || 0),
        dead: Number(d.dead || 0)
      }
    }
  } catch (e) { /* 队列统计是附加信息，失败不影响列表主流程 */ }
}
/** 「作废」抽屉：下钻看终态失败任务的明细（文档名/原因/完成时间），可重解析或清理孤儿任务 */
const deadDrawerVisible = ref(false)
const deadLoading = ref(false)
const deadList = ref([])
async function openDeadDrawer () {
  deadDrawerVisible.value = true
  await fetchDeadList()
}
async function fetchDeadList () {
  deadLoading.value = true
  try {
    const r = await getDocumentDeadTasks(currentKbId.value || route.params.kbId)
    deadList.value = (r && r.success ? (r.data || []) : []).map(it => ({
      taskId: it.task_id,
      docId: it.doc_id,
      kbId: it.kb_id,
      fileName: it.file_name || '（文档已删除/缺失）',
      attempt: Number(it.attempt || 0),
      maxAttempt: Number(it.max_attempt || 0),
      error: it.error || '',
      finishTime: it.finish_time || ''
    }))
  } catch (e) { message.error(e.message || '加载作废任务失败') }
  finally { deadLoading.value = false }
}
const deadReparsingId = ref('')
async function reparseFromDead (row) {
  deadReparsingId.value = row.taskId
  try {
    const r = await reparseDocument(row.docId)
    if (r.success) { message.success('已重新提交解析'); await fetchDeadList(); fetchQueueStats() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { deadReparsingId.value = '' }
}
const deadDeletingId = ref('')
async function deleteDeadTask (row) {
  deadDeletingId.value = row.taskId
  try {
    const r = await deleteParseTask(row.taskId)
    if (r.success) { message.success(r.msg || '已删除任务'); await fetchDeadList(); fetchQueueStats() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { deadDeletingId.value = '' }
}
/** 是否还在等队列：有文档在解析中，或队列里还有没消化的任务。
 *  封顶 tick 数——队列长时间不消化（如闸门卡死）时不再空转刷新。 */
function shouldPoll () {
  if (list.value.some(d => d.status === 2)) return true
  if (pollTicks >= 400) return false
  return queueStats.value.queued > 0 || queueStats.value.running > 0
}

const summaryText = computed(() => {
  const total = list.value.length
  const chunks = list.value.reduce((n, d) => n + (d.chunkCount || 0), 0)
  const active = list.value.filter(d => d.status === 0).length
  // 文件区概览（5.4）：源文件总占用，让"存了多少"可见
  const bytes = list.value.reduce((n, d) => n + (d.fileSize || 0), 0)
  return `${total} 个文档 · ${active} 个生效 · ${chunks} 片段 · 占用 ${fmtSize(bytes)}`
})

/** 状态栏右侧的紧凑口径：知识块 + 占用（与效果图「128 块 · 占用 16.2 MB」对齐） */
const chunkSizeText = computed(() => {
  const chunks = list.value.reduce((n, d) => n + (d.chunkCount || 0), 0)
  const bytes = list.value.reduce((n, d) => n + (d.fileSize || 0), 0)
  return `${chunks} 块 · 占用 ${fmtSize(bytes)}`
})

const statusCounts = computed(() => ({
  all: list.value.length,
  ok: list.value.filter(d => d.status === 0).length,
  parsing: list.value.filter(d => d.status === 2).length,
  failed: list.value.filter(d => d.status === 3).length,
  disabled: list.value.filter(d => d.status === 1).length
}))

const statusText = s => ({ 0: '已入库', 1: '已弃用', 2: '解析中', 3: '解析失败' }[Number(s)] || '—')

function toggleDoc (d) {
  expandedDocId.value = expandedDocId.value === d.id ? '' : d.id
}

function onMoreMenu ({ key }) {
  if (key === 'recycle') openRecycle()
  else if (key === 'config') openKbConfig()
  else if (key === 'dead') openDeadDrawer()
}

const toggleSelect = (id, e) => {
  const at = selectedKeys.value.indexOf(id)
  if (e.target.checked) { if (at < 0) selectedKeys.value.push(id) }
  else if (at >= 0) selectedKeys.value.splice(at, 1)
}

onMounted(() => { fetchList(); fetchKbs(); loadUploadCfg(); loadRecycle(); ensureAuth().then(me => { myUid.value = me.user || '' }); window.addEventListener('paste', onPaste); applyKbCfgDeepLink() })
watch(currentKbId, () => { fetchList(); loadRecycle() })
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  window.removeEventListener('paste', onPaste)
  window.removeEventListener('keydown', onKbImgKeydown)
})

// ==================== 知识库归属（文档属于哪个库，决定能被哪些助手检索到） ====================
const kbases = ref([])
const defaultKbId = computed(() => (kbases.value.find(k => k.isDefault === 1) || {}).id || '')
/** 移动目标选项：官方内置库不出现（内容随版本同步，移进去也会被下次同步清掉，后端同样明确拒绝） */
const kbSelectOptions = computed(() =>
  kbases.value.filter(k => k.builtin !== 1)
    .map(k => ({ value: k.id, label: k.name + (k.docCount != null ? `（${k.docCount}）` : '') })))
/** 文档当前所属库：kb_id 为空即默认库（后端把"未指定"视作归入默认库） */
const kbOf = d => d.kbId || defaultKbId.value
async function fetchKbs () {
  try {
    const r = await listKnowledgeBases()
    if (r && r.success !== false) kbases.value = r.data || []
  } catch (e) { /* 拉取失败不影响文档管理主流程，仅归属列留空 */ }
}
/** 文档所属库显示名（只读视角用） */
const kbNameOf = d => (kbases.value.find(k => k.id === (d.kbId || defaultKbId.value)) || {}).name || '默认知识库'
/** 切换文档归属：选默认库时后端存 null（与"未指定"等价） */
const onMoveKb = async (d, v) => {
  const target = v === defaultKbId.value ? null : v
  const name = (kbases.value.find(k => k.id === v) || {}).name || '默认知识库'
  try {
    const r = await moveDocToKb(d.id, target)
    if (r && r.success === false) { message.warning(r.msg || '移动失败'); return }
    message.success('已移动到「' + name + '」')
    await fetchList()
  } catch (e) { message.error('移动失败') }
}

/**
 * 拉取当前库文档列表并合并命中列。
 * kbId 必传语义：不传后端返回全部库的文档，会让别的库文档混进当前库列表。
 */
async function loadDocs (kbId) {
  // 命中统计是管理端点：普通用户不调（命中列显示 0），避免 403 触发全局误报提示
  const statsP = isAdminSync() ? getDocumentStats() : Promise.resolve(null)
  const [r, stats] = await Promise.all([listDocuments(kbId), statsP])
  if (!r.success) return null
  const hitMap = (stats && stats.success && stats.data) ? stats.data : {}
  return (r.data || []).map(d => ({ ...d, hitCount: hitMap[d.id] || 0 }))
}
async function fetchList () {
  loading.value = true
  try {
    const docs = await loadDocs(currentKbId.value || route.params.kbId)
    if (docs) {
      list.value = docs
      fetchQueueStats()
      if (shouldPoll()) startPolling()
      else stopPolling()
    }
  } catch (e) { message.error(e.message || '获取列表失败') }
  finally { loading.value = false }
}
function startPolling () {
  if (pollTimer) return
  pollTicks = 0
  pollTimer = setInterval(async () => {
    try {
      // 轮询必须带当前库 ID：listDocuments() 不传参=全库，会把别的库文档灌进当前库列表
      const docs = await loadDocs(currentKbId.value || route.params.kbId)
      if (!docs) return
      list.value = docs
      fetchQueueStats()
      if (!shouldPoll()) { stopPolling(); return }
      pollTicks++
    } catch (e) { /* 轮询失败忽略 */ }
  }, 3000)
}
function stopPolling () {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function beforeUpload (fileList) {
  submitFiles(Array.isArray(fileList) ? fileList : [fileList], undefined)
  return false   // 拦下 ant Upload 的自动上传，提交由 submitFiles 接管
}

/** 上传提交共用：文件校验 + 批量提交 + 结果提示，返回是否全部提交成功。desc 为 undefined 表示无描述（拖拽/粘贴直传）；visionRef 为 undefined 表示跟随知识库（直传无弹窗，无覆盖入口） */
async function submitFiles (files, desc, visionRef) {
  const bad = files.find(f => {
    const ext = (f.name.split('.').pop() || '').toLowerCase()
    return !uploadCfg.value.allowedExts.includes(ext) || f.size > uploadCfg.value.maxFileSize
  })
  if (bad) {
    message.error(`${bad.name} 不支持或超过 ${uploadCfg.value.maxFileSizeLabel}（支持 ${uploadCfg.value.allowedExts.join('/')}）`)
    return false
  }
  uploading.value = true
  uploadPercent.value = 0
  uploadQueue.value = files.map(f => ({ name: f.name, size: f.size, status: 'uploading', progress: 0 }))
  queueCollapsed.value = false   // 新一轮上传自动展开，否则用户只看到进度百分比却找不到明细
  const rows = []
  try {
    // 逐文件上传：现有批量接口一次只回一个总进度，拆成单文件请求才能给出真实的逐文件进度
    for (let i = 0; i < files.length; i++) {
      const f = files[i]
      try {
        const r = await uploadDocumentsBatch([f], pct => {
          uploadQueue.value[i].progress = pct
          const sum = uploadQueue.value.reduce((n, x) => n + (x.progress || 0), 0)
          uploadPercent.value = Math.round(sum / files.length)
        }, desc, route.params.kbId, visionRef)
        const one = (r.data && r.data[0]) || { success: r.success !== false, msg: r.msg }
        rows.push(one)
        uploadQueue.value[i].status = one.success === false ? 'error' : 'done'
        uploadQueue.value[i].progress = 100
      } catch (e) {
        rows.push({ success: false, msg: e.message || '上传失败' })
        uploadQueue.value[i].status = 'error'
      }
    }
    const failed = rows.filter(x => !x.success)
    uploadPercent.value = 100
    if (failed.length) message.warning(`${files.length - failed.length} 个提交成功，${failed.length} 个失败: ${failed[0].msg || ''}`)
    else message.success(`已提交 ${files.length} 个文档解析`)
    fetchList()
    setTimeout(() => { if (!uploading.value) { uploadQueue.value = []; queueCollapsed.value = false } }, 3200)
    return failed.length === 0
  } catch (e) {
    uploadQueue.value = uploadQueue.value.map(f => ({ ...f, status: 'error' }))
    queueCollapsed.value = false
    message.error(e.message || '上传失败')
    return false
  } finally { uploading.value = false }
}

// ==================== 上传弹窗（选文件 + 描述一体化） ====================
const uploadVisible = ref(false)
const uploadDesc = ref('')
/**
 * 文档级视觉模型覆盖（可选）：对该文档所有图片理解生效；空=跟随知识库双槽位配置。
 * 界面默认折叠成一行摘要（「跟随知识库配置」，小白无感）；点「修改」展开选择器后，
 * 改选出与库 visionRef 不同的模型才是真正的文档级覆盖——库级是 visionRef+ocrRef
 * 双槽位，照值提交会把 OCR 槽一并覆盖，且库配置后续调整不再对本文档生效。
 */
const uploadVisionRef = ref('')
/** 选择器是否展开（高级项默认收起，主流程只剩"拖文件 → 开始上传"） */
const visionEditing = ref(false)
/** 当前库 parse_params 的双槽位引用（visionRef=图片描述模型、ocrRef=扫描件 OCR 模型；
 *  parseParams 为 JSON 字符串，未配/解析失败返回空） */
const currentKbParseRefs = computed(() => {
  const kb = currentKb.value
  if (!kb || !kb.parseParams) return { visionRef: '', ocrRef: '' }
  try {
    const p = JSON.parse(kb.parseParams) || {}
    return { visionRef: p.visionRef || '', ocrRef: p.ocrRef || '' }
  } catch { return { visionRef: '', ocrRef: '' } }
})
const currentKbVisionRef = computed(() => currentKbParseRefs.value.visionRef)
/** 可用模型 ref → displayName（摘要文案解析用；打开弹窗时懒加载，拉不到只影响展示不影响上传） */
const visionModelNames = ref({})
async function loadVisionModelNames () {
  if (Object.keys(visionModelNames.value).length) return
  const map = {}
  try {
    const groups = await listAvailableModels()
    for (const g of groups || []) for (const m of (g.models || [])) if (m.ref) map[m.ref] = m.displayName || ''
  } catch (e) { /* 静默：摘要里退化为不显示/截取模型 id */ }
  visionModelNames.value = map
}
/** 引用 → 展示名（清单里没有时退化为 ref 的模型 id 部分，与 ModelSelect 的兜底一致） */
const visionRefName = r => visionModelNames.value[r] || (r.includes('/') ? r.slice(r.indexOf('/') + 1) : r)
/**
 * 跟随知识库时实际生效的两个槽位（desc=图片描述、ocr=扫描件 OCR；OCR 未单独指定=回落图片描述，
 * 与后端解析期双槽位同口径）。
 */
const kbInheritSlots = computed(() => {
  const desc = currentKbVisionRef.value ? visionRefName(currentKbVisionRef.value) : ''
  const ocr = currentKbParseRefs.value.ocrRef ? visionRefName(currentKbParseRefs.value.ocrRef) : ''
  return { desc, ocr }
})
/** 跟随态一行式文案：两槽位同名只报一个名字，确实分开了才点名两条，免得用户以为只有一个模型 */
const kbInheritModels = computed(() => {
  const { desc, ocr } = kbInheritSlots.value
  if (!ocr || ocr === desc) return desc
  return `图片描述 ${desc || '不处理'}、OCR ${ocr}`
})
/** 折叠摘要：本文档覆盖 > 跟随库（带模型名）> 未配置 */
const visionSummary = computed(() => {
  const chosen = (uploadVisionRef.value || '').trim()
  if (chosen && chosen !== currentKbVisionRef.value) return '本文档指定：' + visionRefName(chosen)
  const models = kbInheritModels.value
  return models ? `跟随知识库配置（${models}）` : '未配置'
})
/** 空值选项文案：库已配=跟随并点名模型；未配=不处理图片（与后端「空=跳过图片描述」语义一致） */
const visionInheritLabel = computed(() => {
  const models = kbInheritModels.value
  return models ? `跟随知识库配置（${models}）` : '不处理图片'
})
/**
 * 展开后的提示行：跟随态点名两个槽位各用哪个模型（选框宽度不够会被省略号截断，这里是完整口径）；
 * 覆盖态只说覆盖范围；未配库说明何时需要选模型。
 */
const visionHint = computed(() => {
  const chosen = (uploadVisionRef.value || '').trim()
  if (chosen && chosen !== currentKbVisionRef.value) return '本文档所有图片理解（插图描述、扫描页文字识别）都用该模型'
  const { desc, ocr } = kbInheritSlots.value
  if (!desc && !ocr) return '知识库暂未配置图片理解模型；如需让扫描件、图片内容可被检索，可在此指定模型'
  let slots
  if (!ocr || ocr === desc) slots = `插图描述与扫描页文字识别都用 ${desc}`
  else if (!desc) slots = `扫描页文字识别用 ${ocr}，插图描述不处理`
  else slots = `插图描述用 ${desc}、扫描页文字识别用 ${ocr}`
  return `当前跟随知识库：${slots}。通常无需修改，改选则本文档两类图片理解都改用它`
})
const pendingFiles = ref([])
/** 打开上传弹窗：视觉模型恢复折叠且不预选（提交非空才落文档级覆盖），摘要所需模型名懒加载 */
function openUpload () {
  uploadVisionRef.value = ''
  visionEditing.value = false
  loadVisionModelNames()
  uploadVisible.value = true
}
/** 弹窗内选择/拖入文件：只收集不提交（确定时统一提交），校验不过直接拦下 */
function collectPendingFile (file) {
  const ext = (file.name.split('.').pop() || '').toLowerCase()
  if (!uploadCfg.value.allowedExts.includes(ext) || file.size > uploadCfg.value.maxFileSize) {
    message.error(`${file.name} 不支持或超过 ${uploadCfg.value.maxFileSizeLabel}（支持 ${uploadCfg.value.allowedExts.join('/')}）`)
    return false
  }
  if (!pendingFiles.value.some(f => f.name === file.name && f.size === file.size)) pendingFiles.value.push(file)
  return false
}
async function submitUpload () {
  if (!pendingFiles.value.length) { message.warning('请先选择要上传的文件'); return }
  const chosen = uploadVisionRef.value?.trim() || ''
  // 与库级配置相同=跟随知识库（不落文档级快照，库后续调整仍对本文档生效）；不同才是文档级覆盖
  const visionRef = chosen && chosen !== currentKbVisionRef.value ? chosen : undefined
  const ok = await submitFiles([...pendingFiles.value], uploadDesc.value?.trim() || undefined, visionRef)
  if (!ok) return   // 失败保留弹窗内容便于重试
  pendingFiles.value = []
  uploadDesc.value = ''
  uploadVisionRef.value = ''
  uploadVisible.value = false
}
const onDrop = e => {
  dragDepth.value = 0
  const files = Array.from(e.dataTransfer?.files || [])
  if (!files.length) return
  const ok = files.filter(f => uploadCfg.value.allowedExts.includes((f.name.split('.').pop() || '').toLowerCase()))
  const skipped = files.length - ok.length
  if (skipped > 0) message.warning(`跳过 ${skipped} 个不支持的文件`)
  if (ok.length) beforeUpload(ok)
}
const onPaste = e => {
  if (!canManageCurrentKb.value) return   // 无当前库管理权时不响应粘贴上传
  const files = Array.from(e.clipboardData?.files || [])
    .filter(f => uploadCfg.value.allowedExts.includes((f.name.split('.').pop() || '').toLowerCase()))
  if (files.length) beforeUpload(files)
}

// ==================== 网页导入（URL 抓取入库，与上传共用解析链路） ====================
const urlVisible = ref(false)
const urlText = ref('')
const urlDesc = ref('')
const importing = ref(false)
async function submitImportUrl () {
  const urls = [...new Set(urlText.value.split(/\s+/).map(s => s.trim()).filter(Boolean))]
  if (!urls.length) { message.warning('请输入至少一个 URL'); return }
  if (urls.length > 20) { message.warning('一次最多导入 20 个 URL'); return }
  importing.value = true
  const ok = []
  const fail = []
  try {
    for (const u of urls) {
      try {
        const r = await importDocumentFromUrl(u, urlDesc.value?.trim() || undefined, route.params.kbId)
        if (r && r.success !== false) ok.push(u)
        else fail.push(u + '：' + (r?.msg || '失败'))
      } catch (e) { fail.push(u + '：' + (e.message || '失败')) }
    }
    if (fail.length) {
      Modal.warning({ title: `导入完成：${ok.length} 成功 / ${fail.length} 失败`,
        content: h('div', { style: 'white-space:pre-wrap;max-height:300px;overflow:auto' }, fail.join('\n')) })
    } else {
      message.success(`已提交 ${ok.length} 个网页解析`)
    }
    if (ok.length) { urlText.value = ''; urlDesc.value = ''; urlVisible.value = false; fetchList() }
  } finally { importing.value = false }
}

async function toggleStatus (record, status) {
  try {
    const r = await updateDocumentStatus(record.id, status)
    if (r.success) fetchList()
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
async function batchStatus (status) {
  if (!selectedKeys.value.length) return
  try {
    const r = await batchUpdateDocumentStatus(selectedKeys.value, status)
    if (r.success) { message.success(`已${status === 0 ? '启用' : '弃用'} ${selectedKeys.value.length} 个文档`); selectedKeys.value = []; fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
async function batchDelete () {
  if (!selectedKeys.value.length) return
  try {
    const r = await batchDeleteDocuments(selectedKeys.value)
    if (r.success) { message.success(`已删除 ${selectedKeys.value.length} 个文档`); selectedKeys.value = []; fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
const batchReparse = async () => {
  try {
    const r = await batchReparseDocuments(selectedKeys.value)
    if (r.success) {
      const failed = (r.data || []).filter(x => !x.success)
      if (failed.length) message.warning(`已提交 ${r.data.length - failed.length} 个，${failed.length} 个失败: ${failed[0].msg || ''}`)
      else message.success(`已提交 ${r.data.length} 个重解析`)
      selectedKeys.value = []
      fetchList()
      startPolling()
    } else message.error(r.msg || '批量重解析失败')
  } catch (e) { message.error(e.message || '批量重解析失败') }
}
async function reparse (id) {
  reparsingId.value = id
  try {
    const r = await reparseDocument(id)
    if (r.success) { message.success('已重新提交解析'); fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { reparsingId.value = '' }
}
const backfillingId = ref('')
/** 补齐图片描述：解析时视觉模型未描述成功的图片后台补描述并回写知识块（后端异步，本页状态由轮询自然刷新） */
async function backfillDesc (id) {
  backfillingId.value = id
  try {
    const r = await backfillDescriptions(id)
    if (r.success) message.success(r.msg || '已提交图片描述补齐任务')
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { backfillingId.value = '' }
}

// ── 文档标签：展示/编辑/筛选（与智能体「检索标签收窄」同一套元数据） ──
const docTags = d => String(d.tags || '').split(',').map(s => s.trim()).filter(Boolean)
const tagFilter = ref('')
const tagOptions = computed(() => {
  const s = new Set()
  for (const d of list.value) for (const t of docTags(d)) s.add(t)
  return [...s].map(t => ({ value: t, label: t }))
})
const shownList = computed(() => {
  let arr = list.value
  if (tagFilter.value) arr = arr.filter(d => docTags(d).includes(tagFilter.value))
  if (statusFilter.value === 'ok') arr = arr.filter(d => d.status === 0)
  else if (statusFilter.value === 'parsing') arr = arr.filter(d => d.status === 2)
  else if (statusFilter.value === 'failed') arr = arr.filter(d => d.status === 3)
  else if (statusFilter.value === 'disabled') arr = arr.filter(d => d.status === 1)
  const q = docSearch.value.trim().toLowerCase()
  if (q) {
    arr = arr.filter(d =>
      String(d.fileName || '').toLowerCase().includes(q) ||
      String(d.description || '').toLowerCase().includes(q) ||
      String(d.tags || '').toLowerCase().includes(q))
  }
  return arr
})
const tagDraft = ref([])
const tagVisible = ref(false)
const tagDoc = ref(null)
const tagDocName = computed(() => tagDoc.value?.fileName || '')
function openTagEdit (d) {
  // 用列表里的实时行对象：轮询刷新会整体换 list，旧引用改了也渲染不出来
  const row = list.value.find(x => String(x.id) === String(d.id)) || d
  tagDoc.value = row
  tagDraft.value = docTags(row)
  tagVisible.value = true
}
/** 保存标签：成功后就地回写行数据（免整表重拉；标签筛选选项随之自动更新） */
async function saveTags () {
  const d = tagDoc.value
  if (!d) return
  const csv = tagDraft.value.map(t => String(t).trim()).filter(Boolean).join(',')
  try {
    const r = await setDocumentTags(d.id, csv)
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    // 按 id 回写当前列表的全部同 id 行：即便期间轮询换过对象，列表也立刻显示新标签
    list.value.forEach(row => { if (String(row.id) === String(d.id)) row.tags = csv })
    tagVisible.value = false
    message.success('已更新标签')
  } catch (e) { message.error(e.message || '保存失败') }
}

// ── 回收站：软删文档的恢复 / 彻底删除 / 清空 ──
const recycleVisible = ref(false)
const recycleRows = ref([])
const recycleLoading = ref(false)
const recycleBusy = ref(false)
const recycleSelected = ref([])
// 文件名不设宽=吃掉剩余宽度并省略号收尾；其余三列定宽，勾选列 44px（见 row-selection.columnWidth）
// fileName 必须有 dataIndex：#bodyCell 没有对应分支时按它回退渲染（只有 key 会得到空单元格）
const recycleCols = [
  { title: '文件名', dataIndex: 'fileName', key: 'fileName', ellipsis: true },
  { title: '大小', dataIndex: 'fileSize', key: 'size', width: 96 },
  { title: '删除时间', dataIndex: 'deletedAt', key: 'deletedAt', width: 140 },
  { title: '操作', key: 'act', width: 132 }
]
const recycleCount = computed(() => recycleRows.value.length)
async function loadRecycle () {
  recycleLoading.value = true
  try {
    const r = await listRecycleDocuments(currentKbId.value)
    if (r.success) { recycleRows.value = r.data || []; recycleSelected.value = [] }
  } catch (e) { /* 静默：徽标与列表保持现状 */ }
  finally { recycleLoading.value = false }
}
function openRecycle () { recycleVisible.value = true; loadRecycle() }
async function doRestore (record) {
  recycleBusy.value = true
  try {
    const r = await restoreDocument(record.id)
    if (r.success) { message.success(`「${record.fileName}」已恢复`); await loadRecycle(); fetchList() }
    else message.error(r.msg || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { recycleBusy.value = false }
}
async function doPurge (record) {
  recycleBusy.value = true
  try {
    const r = await purgeDocument(record.id)
    if (r.success) { message.success(`「${record.fileName}」已彻底删除`); await loadRecycle() }
    else message.error(r.msg || '彻底删除失败')
  } catch (e) { message.error(e.message || '彻底删除失败') }
  finally { recycleBusy.value = false }
}
async function doPurgeRecycle () {
  recycleBusy.value = true
  try {
    const r = await purgeRecycleBin(currentKbId.value)
    if (r.success) { message.success(r.msg || '回收站已清空'); await loadRecycle() }
    else message.error(r.msg || '清空失败')
  } catch (e) { message.error(e.message || '清空失败') }
  finally { recycleBusy.value = false }
}
/** 后端逐项回报 {id,success,msg}：部分失败要报出来，否则用户以为选中项全做完了 */
function reportRecycleBatch (r, ids, verb) {
  const failed = (r.data || []).filter(x => !x.success)
  if (!failed.length) message.success(`已${verb} ${ids.length} 个文档`)
  else message.warning(`已${verb} ${ids.length - failed.length} 个，${failed.length} 个失败：${failed[0].msg || ''}`)
}
async function doBatchRestore () {
  const ids = [...recycleSelected.value]
  if (!ids.length) return
  recycleSelected.value = []
  recycleBusy.value = true
  try {
    const r = await batchRestoreRecycled(ids)
    if (r.success) { reportRecycleBatch(r, ids, '恢复'); await loadRecycle(); fetchList() }
    else message.error(r.msg || '批量恢复失败')
  } catch (e) { message.error(e.message || '批量恢复失败') }
  finally { recycleBusy.value = false }
}
async function doBatchPurge () {
  const ids = [...recycleSelected.value]
  if (!ids.length) return
  recycleSelected.value = []
  recycleBusy.value = true
  try {
    const r = await batchPurgeRecycled(ids)
    if (r.success) { reportRecycleBatch(r, ids, '彻底删除'); await loadRecycle() }
    else message.error(r.msg || '批量彻底删除失败')
  } catch (e) { message.error(e.message || '批量彻底删除失败') }
  finally { recycleBusy.value = false }
}
// ── 网页源自动刷新（行内 ⋯ 打开的弹窗）：草稿式编辑，点取消不会把行数据改脏 ──
const refreshVisible = ref(false)
const refreshDoc = ref(null)
const refreshDraft = ref({ enabled: false, cron: '', nextAt: '' })
const refreshDocName = computed(() => refreshDoc.value?.fileName || '')
function openRefreshEdit (d) {
  refreshDoc.value = d
  refreshDraft.value = { enabled: d.autoRefresh === 1, cron: d.refreshCron || '', nextAt: d.nextRefreshAt || '' }
  refreshVisible.value = true
}
async function saveRefresh () {
  const d = refreshDoc.value
  if (!d) return
  const draft = refreshDraft.value
  if (draft.enabled && !String(draft.cron || '').trim()) {
    message.warning('开启自动刷新需填写 cron（5 段，如 0 3 * * *）'); return
  }
  try {
    const r = await refreshConfigDocument(d.id, draft.enabled ? 1 : 0, draft.enabled ? String(draft.cron).trim() : '')
    if (r.success) { message.success('已更新自动刷新配置'); refreshVisible.value = false; fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
async function del (id) {
  try {
    const r = await deleteDocument(id)
    if (r.success) { message.success('删除成功'); fetchList() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 行内 ⋯ 菜单 ====================
/**
 * 收进 ⋯ 的低频/写动作。判据与行内三个按钮互斥：行内留「看内容 / 拿文件 / 换状态」，
 * 其余全在这里——十个链接排一排会撑破操作列宽，右侧按钮直接被卡片裁掉看不见。
 */
function rowMenu (d) {
  if (!canManageDoc(d)) return []
  const items = []
  if (d.status === 0) items.push({ key: 'versions', label: '版本历史' })
  if (d.status === 0 || d.status === 3) {
    items.push({ key: 'reparse', label: '重新解析', disabled: reparsingId.value === d.id,
                 tip: '重新解析并向量化源文件' })
  }
  if (d.status === 0) {
    items.push({ key: 'backfill', label: '补图片描述', disabled: backfillingId.value === d.id,
                 tip: '后台补描述图片并回写知识块' })
  }
  const n = docTags(d).length
  items.push({ key: 'tags', label: n ? `标签（${n}）` : '标签' })
  if (d.fileType === 'url') items.push({ key: 'refresh', label: d.autoRefresh === 1 ? '自动刷新（开）' : '自动刷新' })
  items.push({ key: 'delete', label: '删除', danger: true, tip: '删除后进入回收站，可恢复或彻底删除' })
  return items
}
function onRowMenu (d, key) {
  if (key === 'versions') openVersions(d)
  else if (key === 'reparse') reparse(d.id)
  else if (key === 'backfill') backfillDesc(d.id)
  else if (key === 'tags') openTagEdit(d)
  else if (key === 'refresh') openRefreshEdit(d)
  else if (key === 'delete') {
    Modal.confirm({
      title: `删除「${d.fileName}」？`, content: '删除后进入回收站，可恢复或彻底删除',
      okText: '删除', okType: 'danger', cancelText: '取消', onOk: () => del(d.id)
    })
  }
}
function showFailReason (record) {
  Modal.info({ title: `解析失败 - ${record.fileName}`, content: record.failReason || '未知原因，可点击"重解析"重试' })
}

// ==================== 知识块 ====================
const kbVisible = ref(false)
const kbLoading = ref(false)
const kbList = ref([])
const kbDocName = ref('')
const kbDocId = ref('')
const kbSearch = ref('')
const kbPathFilter = ref('')
// 知识块列表表体滚动高度：随视口自适应（一页 20 条在矮屏会超出屏幕；下限 200，上限 520）
const kbScrollY = Math.max(200, Math.min(520, window.innerHeight - 400))
// ==================== 结构导图（3.3 知识导图 / 3.5 文件元数据导图） ====================
// 按 titlePath（"报表设计 > 组件 > 文件上传"）聚合成章节树，每节点带块数/token/图片数。
// 纯前端派生，不需要后端接口；元数据来自已加载的切片，故与搜索过滤同步。
const kbView = ref('list')
const kbCollapsed = ref(new Set())
const imgCountOf = r => {
  try {
    const im = typeof r.images === 'string' ? JSON.parse(r.images || '[]') : (r.images || [])
    return Array.isArray(im) ? im.length : 0
  } catch (e) { return 0 }
}
// 章节路径唯一口径：后端只在标题层级≥2 时才写 titlePath（单层标题/MinerU h1 平铺时为空），
// 此时树回退用 title 当路径——建树与点章节名过滤必须同源，否则点过去永远匹配不上
const normPath = r => String(r.titlePath || r.title || '未分类')
    .split('>').map(s => s.trim()).filter(Boolean).join(' > ')
const kbTreeRows = computed(() => {
  const mk = (name, path) => ({ name, path, children: new Map(), own: [], tokens: 0, imgs: 0, count: 0 })
  const root = mk('', '')
  for (const r of kbFilteredList.value) {
    const parts = normPath(r).split(' > ').filter(Boolean)
    let node = root
    let path = ''
    for (const p of parts) {
      path = path ? path + ' > ' + p : p
      if (!node.children.has(p)) node.children.set(p, mk(p, path))
      node = node.children.get(p)
    }
    node.own.push(r)
  }
  // 自底向上聚合：节点自身切片 + 全部子孙
  const agg = n => {
    let tokens = 0, imgs = 0
    for (const c of n.children.values()) { agg(c); tokens += c.tokens; imgs += c.imgs; n.count += c.count }
    for (const r of n.own) {
      tokens += estimateTokens((r.title || '') + (r.content || ''))
      imgs += imgCountOf(r)
    }
    n.tokens = tokens
    n.imgs = imgs
    n.count += n.own.length
    return n
  }
  agg(root)
  const rows = []
  const walk = (n, depth) => {
    const hasChild = n.children.size > 0
    const open = !kbCollapsed.value.has(n.path)
    rows.push({ name: n.name, path: n.path, count: n.count, tokens: n.tokens, imgs: n.imgs, depth, hasChild, open })
    if (hasChild && open) for (const c of n.children.values()) walk(c, depth + 1)
  }
  for (const c of root.children.values()) walk(c, 0)
  return rows
})
const toggleTreeNode = path => {
  const s = new Set(kbCollapsed.value)
  if (s.has(path)) s.delete(path); else s.add(path)
  kbCollapsed.value = s
}
/** 点章节名：回到切片列表并按该章节路径精确过滤（含子孙章节；结构视图只做导览） */
const focusTreeNode = r => {
  kbPathFilter.value = r.path
  kbView.value = 'list'
}

// 切片统计（2.9）：合计 token 与未向量化块数（过滤后口径，随搜索实时变化）
const kbStatTokens = computed(() => kbFilteredList.value
    .reduce((sum, r) => sum + estimateTokens((r.title || '') + (r.content || '')), 0))
const kbNoVector = computed(() => kbFilteredList.value.filter(r => !r.vectorId).length)
const kbFilteredList = computed(() => {
  const path = kbPathFilter.value
  const kw = kbSearch.value.trim().toLowerCase()
  return kbList.value.filter(k => {
    if (path) {
      const p = normPath(k)
      if (p !== path && !p.startsWith(path + ' > ')) return false
    }
    if (!kw) return true
    return (k.title || '').toLowerCase().includes(kw) || (k.content || '').toLowerCase().includes(kw)
  })
})
/** 空态文案：区分「章节筛选无结果 / 搜索无匹配 / 确实没有」 */
const kbEmptyText = computed(() => {
  const hasPath = !!kbPathFilter.value
  const hasKw = !!kbSearch.value.trim()
  if (hasPath && hasKw) return '当前章节筛选与搜索条件下暂无知识块'
  if (hasPath) return '该章节下暂无知识块（可点上方标识清除筛选）'
  if (hasKw) return '没有匹配的知识块'
  return '暂无知识块'
})
const kbDetailVisible = ref(false)
const kbDetail = ref(null)
const kbDetailRow = ref(null)
const kbDetailLoading = ref(false)
const kbDetailErr = ref('')
const kbDetailHtml = computed(() =>
  renderMd(prepKnowledgeContent(kbDetail.value?.content, kbDetail.value?.images), kbDetail.value?.images))

// ==================== 行内展开直读（对齐片段卡片：完整内容不再叠二级弹窗） ====================
const kbExpandedKeys = ref([])
const kbExpandedDetail = ref(null)   // 展开块的详情（含签名图片）；未返回前先用列表数据即时渲染
const onKbExpand = (expanded, record) => {
  if (!expanded) {
    kbExpandedKeys.value = []
    kbExpandedDetail.value = null
    return
  }
  kbExpandedKeys.value = [record.id]   // 单块展开：点其他行自动切换
  kbExpandedDetail.value = null
  getKnowledgeDetail(record.id).then(r => {
    // 过期响应防护：仅当该块仍处于展开态才应用（列表刷新/切换后不覆盖）
    if (r.success && kbExpandedKeys.value[0] === record.id) kbExpandedDetail.value = r.data
  }).catch(() => { /* 详情失败不阻塞：用列表数据渲染（图片未签名时降级） */ })
}
const refreshKbExpandedDetail = () => {
  const id = kbExpandedKeys.value[0]
  if (!id) return
  getKnowledgeDetail(id).then(r => {
    if (r.success && kbExpandedKeys.value[0] === id) kbExpandedDetail.value = r.data
  }).catch(() => {})
}
/** 展开块 HTML：详情已返回用详情（签名图片），否则用列表行数据 */
const kbChunkHtml = record => {
  const d = (kbExpandedDetail.value && kbExpandedDetail.value.id === record.id) ? kbExpandedDetail.value : record
  return renderMd(prepKnowledgeContent(d.content, d.images || []), d.images || [])
}
const copyKbContent = record => copyText(record.content || '', '已复制知识块内容')
const kbCollapseWs = t => String(t || '').replace(/\s+/g, ' ')
/** 搜索词高亮分段（大小写不敏感）：返回 [{t, hit}] 供模板逐段渲染，避免 v-html 注入 */
const kbHighlightParts = text => {
  const t = String(text || '')
  const kw = kbSearch.value.trim()
  if (!kw) return [{ t, hit: false }]
  const lower = t.toLowerCase()
  const k = kw.toLowerCase()
  const parts = []
  let i = 0
  while (i < t.length) {
    const at = lower.indexOf(k, i)
    if (at < 0) { parts.push({ t: t.slice(i), hit: false }); break }
    if (at > i) parts.push({ t: t.slice(i, at), hit: false })
    parts.push({ t: t.slice(at, at + kw.length), hit: true })
    i = at + kw.length
  }
  return parts
}

// ==================== 知识块图片灯箱（与聊天页同款：多图切换 / 滚轮缩放 / 拖动平移 / ESC 关闭） ====================
const kbImgList = ref([])
const kbImgIndex = ref(0)
const kbImgUrl = computed(() => kbImgList.value[kbImgIndex.value] || '')
const kbImgZoom = ref(1)
const kbImgOffset = ref({ x: 0, y: 0 })
const kbImgDrag = ref(null)
const resetKbImgView = () => { kbImgZoom.value = 1; kbImgOffset.value = { x: 0, y: 0 } }
const kbImgPrev = () => { if (kbImgIndex.value > 0) { kbImgIndex.value--; resetKbImgView() } }
const kbImgNext = () => { if (kbImgIndex.value < kbImgList.value.length - 1) { kbImgIndex.value++; resetKbImgView() } }
const closeKbImg = () => { kbImgList.value = []; kbImgIndex.value = 0; resetKbImgView(); kbImgDrag.value = null }
/** 知识块内容点击（事件委托）：图片 → 灯箱（同容器多图可切换）；代码复制按钮 → copyCode */
const openKbImgPreview = e => {
  const t = e.target
  const copyBtn = t && t.closest ? t.closest('.code-copy') : null
  if (copyBtn) { copyCode(copyBtn); return }
  if (t && t.tagName && t.tagName.toLowerCase() === 'img') {
    const mdEl = t.closest('.md')
    const imgs = mdEl ? Array.from(mdEl.querySelectorAll('img')) : [t]
    kbImgList.value = imgs.map(i => i.getAttribute('src'))
    kbImgIndex.value = Math.max(0, imgs.indexOf(t))
    resetKbImgView()
  }
}
const onKbImgWheel = e => {
  let factor = Math.pow(1.08, -e.deltaY / 100)
  if (factor > 1.3) factor = 1.3
  if (factor < 1 / 1.3) factor = 1 / 1.3
  kbImgZoom.value = Math.min(8, Math.max(0.25, kbImgZoom.value * factor))
}
const onKbImgMouseDown = e => {
  if (e.button !== 0) return
  kbImgDrag.value = { startX: e.clientX, startY: e.clientY, ox: kbImgOffset.value.x, oy: kbImgOffset.value.y }
  e.preventDefault()
}
const onKbImgMouseMove = e => {
  if (!kbImgDrag.value) return
  kbImgOffset.value.x = kbImgDrag.value.ox + (e.clientX - kbImgDrag.value.startX)
  kbImgOffset.value.y = kbImgDrag.value.oy + (e.clientY - kbImgDrag.value.startY)
}
const onKbImgMouseUp = () => { kbImgDrag.value = null }
const onKbImgKeydown = e => {
  if (e.key === 'Escape') closeKbImg()
  else if (e.key === 'ArrowLeft') kbImgPrev()
  else if (e.key === 'ArrowRight') kbImgNext()
}
watch(kbImgUrl, v => {
  if (v) window.addEventListener('keydown', onKbImgKeydown)
  else window.removeEventListener('keydown', onKbImgKeydown)
})

// 源文件下载（个人文件区，5.4）：取回上传的原始文件
const dlSource = async d => {
  try {
    await downloadDocumentSource(d.id, d.fileName)
  } catch (e) {
    message.error(e.message || '下载失败')
  }
}

const openKb = async record => {
  currentDoc.value = record
  kbDocName.value = record.fileName
  kbDocId.value = record.id
  kbVisible.value = true
  kbLoading.value = true
  kbList.value = []
  // 切文档重置过滤/展开状态：避免上一次的搜索词、章节筛选、展开行残留
  kbSearch.value = ''
  kbPathFilter.value = ''
  kbView.value = 'list'
  kbCollapsed.value = new Set()
  kbExpandedKeys.value = []
  kbExpandedDetail.value = null
  try {
    const r = await listKnowledgeByDoc(record.id)
    kbList.value = r.success && Array.isArray(r.data) ? r.data : []
  } catch (e) { message.error(e.message || '加载知识块失败') }
  finally { kbLoading.value = false }
}
/** 知识块详情（全局搜索打开）：带加载/失败态，失败可重试 */
const openKbDetail = async row => {
  kbDetailRow.value = row
  kbDetail.value = null
  kbDetailErr.value = ''
  kbDetailLoading.value = true
  kbDetailVisible.value = true
  try {
    const r = await getKnowledgeDetail(row.id)
    if (r.success) kbDetail.value = r.data
    else kbDetailErr.value = r.msg || '加载详情失败'
  } catch (e) { kbDetailErr.value = e.message || '加载详情失败' }
  finally { kbDetailLoading.value = false }
}
const kbEditVisible = ref(false)
const kbEditSaving = ref(false)
const kbEditForm = ref({ id: '', title: '', content: '' })
const kbEditImages = ref([])
const kbEditSnapshot = ref('')      // 打开时的标题+内容快照，用于未保存判断
const kbTaRef = ref(null)
const kbEditDirty = computed(() =>
  kbEditForm.value.title + '\u0000' + kbEditForm.value.content !== kbEditSnapshot.value)
const kbEditPreviewHtml = computed(() =>
  renderMd(prepKnowledgeContent(kbEditForm.value.content, kbEditImages.value), kbEditImages.value))
const openKbEdit = async row => {
  kbEditForm.value = { id: row.id, title: row.title || '', content: row.content || '' }
  kbEditImages.value = []
  kbEditSnapshot.value = kbEditForm.value.title + '\u0000' + kbEditForm.value.content
  kbEditVisible.value = true
  // 图片列表：预览渲染 + 「图片」插入菜单都要用（失败降级为空数组，预览显示 [图片] 原文）
  try {
    const r = await getKnowledgeDetail(row.id)
    if (r.success && Array.isArray(r.data?.images)) kbEditImages.value = r.data.images
  } catch (e) { /* 预览降级为无图，不阻塞编辑 */ }
}
// 关闭（X/遮罩/ESC/取消按钮共用）：有未保存修改先确认，防误关丢稿
const closeKbEdit = () => {
  if (!kbEditDirty.value) { kbEditVisible.value = false; return }
  Modal.confirm({
    title: '放弃未保存的修改？',
    content: '标题或内容已修改但尚未保存',
    okText: '放弃修改', okType: 'danger', cancelText: '继续编辑',
    onOk: () => { kbEditVisible.value = false }
  })
}
// 取 a-textarea 内部原生 textarea（antd 封装层级兜底，保证拿得到 selectionStart）
const kbGetTa = () => {
  const r = kbTaRef.value
  const ta = r?.resizableTextArea?.textArea || r?.textArea
  if (ta) return ta
  const el = r?.$el
  return el ? (el.tagName === 'TEXTAREA' ? el : el.querySelector?.('textarea')) : null
}
// 在光标处插入包裹语法（**xx** / [图片N] / 代码围栏 / 链接）：选中文字进包裹内，无选中插占位文字并选中
const kbWrap = (before, after = '', placeholder = '') => {
  const ta = kbGetTa()
  const v = kbEditForm.value.content
  const s = ta ? ta.selectionStart : v.length
  const e = ta ? ta.selectionEnd : s
  const sel = v.slice(s, e) || placeholder
  kbEditForm.value.content = v.slice(0, s) + before + sel + after + v.slice(e)
  nextTick(() => {
    if (!ta) return
    ta.focus()
    ta.setSelectionRange(s + before.length, s + before.length + sel.length)
  })
}
// 行前缀语法（## / - / > / 1.）：作用于光标所在行（多行选区逐行生效），已有前缀再点一次取消
const kbLinePrefix = (prefix, ordered = false) => {
  const ta = kbGetTa()
  const v = kbEditForm.value.content
  const s = ta ? ta.selectionStart : v.length
  const e = ta ? ta.selectionEnd : s
  const ls = v.lastIndexOf('\n', Math.max(s - 1, 0)) + 1
  let le = v.indexOf('\n', e)
  if (le === -1) le = v.length
  const lines = v.slice(ls, le).split('\n')
  const has = l => ordered ? /^\s*\d+\.\s/.test(l) : l.trimStart().startsWith(prefix)
  const strip = l => ordered
    ? l.replace(/^(\s*)\d+\.\s*/, '$1')
    : l.replace(new RegExp('^(\\s*)' + prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(\\s*)'), '$1')
  const all = lines.every(has)
  const out = lines.map((l, i) => {
    if (all) return strip(l)
    if (ordered) {
      const indent = l.match(/^\s*/)[0]
      const body = l.trimStart().replace(/^\d+\.\s+|^[-*+]\s+|^>\s?/, '')
      return `${indent}${i + 1}. ${body}`
    }
    return has(l) ? l : prefix + l.trimStart().replace(/^\d+\.\s+/, '')
  })
  kbEditForm.value.content = v.slice(0, ls) + out.join('\n') + v.slice(le)
  nextTick(() => {
    if (!ta) return
    ta.focus()
    ta.setSelectionRange(ls, ls + out.join('\n').length)
  })
}
// 插入三列表格模板（markdown 表格需独立成块：前后补空行；插入后选中"列1"便于直接改表头）
const kbInsertTable = () => {
  const ta = kbGetTa()
  const v = kbEditForm.value.content
  const s = ta ? ta.selectionStart : v.length
  const e = ta ? ta.selectionEnd : s
  const head = v.slice(0, s)
  const lead = head === '' || /\n\s*\n$/.test(head) ? '' : /\n$/.test(head) ? '\n' : '\n\n'
  kbEditForm.value.content = head + lead + '| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n|  |  |  |\n\n' + v.slice(e)
  nextTick(() => {
    if (!ta) return
    ta.focus()
    const p = head.length + lead.length + 2
    ta.setSelectionRange(p, p + 2)
  })
}
// 编辑区快捷键：Ctrl/⌘+B 加粗
const onTaKeydown = e => {
  if (!(e.ctrlKey || e.metaKey)) return
  if ((e.key || '').toLowerCase() === 'b') { e.preventDefault(); kbWrap('**', '**', '加粗文字') }
}
const saveKnowledgeEdit = async () => {
  if (!kbEditForm.value.title.trim()) { message.warning('标题不能为空'); return }
  if (!kbEditForm.value.content.trim()) { message.warning('内容不能为空'); return }
  kbEditSaving.value = true
  try {
    const r = await updateKnowledge(kbEditForm.value.id, kbEditForm.value.title.trim(), kbEditForm.value.content)
    if (r.success) {
      message.success('知识块已更新')
      kbEditVisible.value = false
      if (kbVisible.value) {
        const rr = await listKnowledgeByDoc(kbDocId.value)
        kbList.value = rr.success && Array.isArray(rr.data) ? rr.data : []
        refreshKbExpandedDetail()   // 正在展开的块同步刷新（含图片签名）
      }
    } else message.error(r.msg || '更新失败')
  } catch (e) { message.error(e.message || '更新失败') }
  finally { kbEditSaving.value = false }
}
const toggleKbStatus = async (record, status) => {
  try {
    const r = await updateKnowledgeStatus(record.id, status)
    if (r.success) {
      message.success(status === 1 ? '已停用，该知识块不再参与召回' : '已启用，恢复召回')
      kbSearch.value = ''
      const res = await listKnowledgeByDoc(record.docId)
      if (res.success) kbList.value = res.data || []
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}
const delKnowledge = async id => {
  try {
    const r = await deleteKnowledge(id)
    if (r.success) {
      message.success('知识块已删除')
      if (kbVisible.value) {
        const rr = await listKnowledgeByDoc(kbDocId.value)
        kbList.value = rr.success && Array.isArray(rr.data) ? rr.data : []
      }
      if (kbExpandedKeys.value[0] === id) { kbExpandedKeys.value = []; kbExpandedDetail.value = null }
      fetchList()
    } else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// 全局搜索
const gSearchVisible = ref(false)
const gSearchKw = ref('')
const gSearchLoading = ref(false)
const gResults = ref([])
const openGlobalSearch = () => { gSearchVisible.value = true; gSearchKw.value = ''; gResults.value = [] }
const doGlobalSearch = async () => {
  const kw = gSearchKw.value.trim()
  if (!kw) { message.warning('请输入关键词'); return }
  gSearchLoading.value = true
  try {
    const r = await searchKnowledge(kw)
    if (r.success) gResults.value = r.data || []
    else message.error(r.msg || '搜索失败')
  } catch (e) { message.error(e.message || '搜索失败') }
  finally { gSearchLoading.value = false }
}

// 版本管理
const verVisible = ref(false)
const verLoading = ref(false)
const verList = ref([])
const verDocName = ref('')
const verDocId = ref('')
const openVersions = async record => {
  currentDoc.value = record
  verDocName.value = record.fileName
  verDocId.value = record.id
  verVisible.value = true
  verLoading.value = true
  verList.value = []
  try {
    const r = await listDocumentVersions(record.id)
    verList.value = r.success && Array.isArray(r.data) ? r.data : []
  } catch (e) { message.error(e.message || '加载版本失败') }
  finally { verLoading.value = false }
}
const doRollback = async version => {
  try {
    const r = await rollbackDocument(verDocId.value, version)
    if (r.success) { message.success(`已回滚到 v${version}`); verVisible.value = false; fetchList() }
    else message.error(r.msg || '回滚失败')
  } catch (e) { message.error(e.message || '回滚失败') }
}

const fmtSize = s => !s ? '-' : s < 1024 ? s + ' B' : s < 1048576 ? (s / 1024).toFixed(1) + ' KB' : (s / 1048576).toFixed(1) + ' MB'
const fmtTime = t => {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<style scoped>
/* 上传弹窗：待传文件清单 */
.upload-pending { margin-top: 10px; border: 1px solid var(--app-border); border-radius: 6px; max-height: 180px; overflow-y: auto; }
.upload-pending-row { display: flex; align-items: center; gap: 8px; padding: 5px 10px; font-size: 12px; }
.upload-pending-row + .upload-pending-row { border-top: 1px solid var(--app-border); }
.upf-ic { color: var(--app-text3); flex: none; }
.upf-name { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--app-text); }
.upf-size { flex: none; color: var(--app-text3); font-size: 11px; }
/* 上传弹窗：图片理解模型（默认一行摘要，点「修改」展开选择器） */
.uv-row { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.uv-label { flex: none; font-size: 13px; color: var(--app-text2); }
.uv-summary { flex: 1; min-width: 0; font-size: 12px; color: var(--app-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.uv-toggle { flex: none; margin-left: auto; }
.uv-edit { margin-top: 6px; }
.uv-hint { margin-top: 4px; color: var(--app-text3); font-size: 12px; }
.head-stat { font-size: 12px; color: var(--app-text3); }
.batch-bar {
  display: flex; align-items: center; gap: 10px; padding: 8px 12px; margin-bottom: 10px;
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-border); border-radius: 8px;
  font-size: 12px; color: var(--app-accent);
}
.doc-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 14px;
  border-bottom: 1px solid var(--app-panel-2); font-size: 12px; color: var(--app-text2); min-width: 0;
}
.doc-row:last-child { border-bottom: none; }
.doc-foot { display: flex; align-items: center; gap: 10px; padding: 8px 14px; border-top: 1px solid var(--app-border);
  background: var(--app-panel-2); font-size: 10.5px; color: var(--app-text3); }
.doc-foot .sp { flex: 1; }
.doc-foot-note { color: var(--app-text3); }
.doc-row:not(.head-row):hover { background: var(--app-panel-2); }
.head-row { font-size: 11px; color: var(--app-text3); background: var(--app-panel-2); border-bottom: 1px solid var(--app-border); user-select: none; }
.col-check { width: 26px; flex: none; }
.col-name { flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; }
/* 归属列要能读完「库名（文档数）」：常态 220px；行宽不够时先由本列让（可缩到 132px），
   而不是把文件名挤没——下拉按内容自适应宽，让了也不会读不到候选库名。 */
.col-kb { flex: 0 1 220px; min-width: 132px; }
.file-ic {
  width: 34px; height: 24px; border-radius: 5px; font-size: 9px; font-weight: 500; flex: none;
  display: inline-flex; align-items: center; justify-content: center; letter-spacing: .5px;
}
.file-name { color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.file-desc { font-style: normal; color: var(--app-text3); margin-left: 8px; font-size: 11px; }
.col-num { width: 48px; flex: none; text-align: right; }
.col-size { width: 72px; flex: none; text-align: right; }
.col-status { width: 130px; flex: none; }
.col-time { width: 130px; flex: none; }
/* 操作列：行内只有「知识块 / 下载 / 弃用·启用」三个链接 + 一个 ⋯，约 165px（旧值 250 是给十个按钮留的） */
.col-act { width: 168px; flex: none; text-align: right; white-space: nowrap; }
.row-more { vertical-align: middle; }
.row-danger { color: var(--app-danger); }
/* 状态概览：点击即筛选 */
.doc-statusbar { display: flex; align-items: center; gap: 6px; margin-bottom: 9px; flex-wrap: wrap; }
.st-chip { height: 26px; padding: 0 9px; display: inline-flex; align-items: center; gap: 5px; border-radius: 6px;
  border: 1px solid var(--app-border); background: #fff; font-size: 11.5px; color: var(--app-text2); cursor: pointer; }
.st-chip b { font-weight: 600; color: var(--app-text); }
.st-chip:hover { border-color: var(--app-accent-border); }
.st-chip.on { border-color: var(--app-accent-border); background: var(--app-accent-weak); color: var(--app-accent); }
.st-chip.on b { color: var(--app-accent); }
.st-chip.warn { border-color: var(--app-warn-border); background: #fffdf7; color: var(--app-warn-text); }
.st-chip.muted { cursor: default; color: var(--app-text3); }
.st-chip .d { width: 5px; height: 5px; border-radius: 50%; display: inline-block; }
.st-chip .d.ok { background: var(--app-ok); } .st-chip .d.run { background: var(--app-warn); }
.st-chip .d.err { background: var(--app-danger); } .st-chip .d.off { background: #c9cfd6; }
.doc-statusbar .sp { flex: 1; }
.doc-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 9px; }
.doc-toolbar .sp { flex: 1; }
/* 标签筛选：「标签：」是常驻前缀，只有值区域用占位符「全部」，选中后前缀不丢 */
.filter-field { display: inline-flex; align-items: center; height: 32px; padding: 0 6px 0 10px;
  border: 1px solid var(--app-border-strong); border-radius: 6px; background: #fff; }
.filter-label { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.filter-field :deep(.ant-select-selector) { padding-inline: 0 !important; }
.filter-field :deep(.ant-select-selection-item) { color: var(--app-text); }
.filter-field :deep(.ant-select-selection-placeholder) { color: var(--app-text3); }
.menu-badge { margin-left: 6px; font-size: 10px; color: var(--app-accent); background: var(--app-accent-weak);
  border-radius: 999px; padding: 0 5px; }
/* 行内展开 */
.doc-toggle { width: 18px; height: 18px; border: none; background: transparent; color: var(--app-text3);
  display: inline-flex; align-items: center; justify-content: center; padding: 0; flex: none; cursor: pointer; }
.doc-toggle:hover { color: var(--app-accent); }
.doc-toggle .flip { transform: rotate(-90deg); transition: transform .15s; }
.doc-row.row-open { background: var(--app-accent-weak); }
.doc-expand { padding: 10px 16px 12px 60px; background: var(--app-panel-2); border-bottom: 1px solid var(--app-border); }
.de-meta { display: flex; flex-direction: column; gap: 2px; }
.de-row { display: flex; align-items: flex-start; gap: 10px; font-size: 11.5px; line-height: 20px;
  color: var(--app-text2); padding: 2px 0; }
.de-label { width: 42px; flex: none; color: var(--app-text3); }
.de-val { min-width: 0; flex: 1; word-break: break-all; }
.de-val a { color: var(--app-accent); }
.de-muted { color: var(--app-text3); }
.de-ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 归属行：左侧下拉收窄，右侧操作靠右；窄屏时操作整体换行，不挤压下拉 */
.de-row-kb { align-items: center; flex-wrap: wrap; row-gap: 6px; }
.de-val-kb { flex: 0 1 auto; }
.de-val-kb :deep(.ant-select) { width: 170px; max-width: 100%; }
.de-links { margin-left: auto; display: inline-flex; align-items: center; flex-wrap: wrap; gap: 14px; }
.de-links .app-link-btn { font-size: 11.5px; }
/* 上传队列浮层 */
/* 队列收起后的悬浮入口：与展开卡同位置，点一下原样展开 */
.uploadq-pill { position: fixed; right: 24px; bottom: 24px; display: inline-flex; align-items: center; gap: 6px;
  height: 34px; padding: 0 13px; border-radius: 999px; background: #fff; border: 1px solid var(--app-border);
  box-shadow: 0 12px 32px rgba(16,24,40,.14); font-family: inherit; font-size: 12px; color: var(--app-text2);
  cursor: pointer; z-index: 1000; }
.uploadq-pill:hover { color: var(--app-accent); border-color: var(--app-accent-border); }
.upl-run { color: var(--app-warn-text); font-size: 10.5px; }
.upl-open { color: var(--app-accent); font-size: 11px; }
.uploadq { position: fixed; right: 24px; bottom: 24px; width: 320px; background: #fff; border: 1px solid var(--app-border);
  border-radius: 10px; box-shadow: 0 12px 32px rgba(16,24,40,.14); z-index: 1000; overflow: hidden; }
.uq-head { display: flex; align-items: center; gap: 7px; padding: 9px 11px; border-bottom: 1px solid var(--app-border);
  font-size: 11.5px; font-weight: 600; color: var(--app-text2); }
.uq-head .sp { flex: 1; }
.uq-pct { font-size: 10.5px; color: var(--app-text3); font-weight: 400; }
.uq-close { flex: none; font-size: 10.5px; }
.uq-row { display: flex; align-items: center; gap: 8px; padding: 7px 11px; border-bottom: 1px solid var(--app-border); }
.uq-row:last-child { border-bottom: none; }
.uq-ic { color: var(--app-text3); flex: none; }
.uq-main { flex: 1; min-width: 0; }
.uq-name { font-size: 11px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.uq-sub { font-size: 9.5px; color: var(--app-text3); margin-top: 2px; }
.uq-track { height: 3px; border-radius: 999px; background: #eef0f3; margin-top: 4px; overflow: hidden; }
.uq-track i { display: block; height: 100%; background: var(--app-accent); transition: width .2s; }
.uq-state { font-size: 10px; color: var(--app-text3); flex: none; }
.uq-state.done { color: var(--app-ok); }
.uq-state.error { color: var(--app-danger); }
/* 弹窗内的说明行（标签、自动刷新共用） */
.modal-note { margin: 10px 0 6px; font-size: 12px; color: var(--app-text3); }
.rf-row { display: flex; align-items: center; gap: 10px; }
.parse-desc { font-size: 11px; color: var(--app-text3); display: inline-block; max-width: 150px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 解析中：状态徽标 + 细进度条 + 进度文案（与效果图一致，不再用带百分比的 a-progress） */
.parse-cell { display: flex; flex-direction: column; gap: 3px; min-width: 118px; }
.parse-cell .app-pill { align-self: flex-start; }
.parse-bar { display: block; height: 3px; border-radius: 999px; background: #eef0f3; overflow: hidden; }
.parse-bar i { display: block; height: 100%; background: var(--app-accent); transition: width .2s; }
/* 解析队列指示：队列非空时常驻页头/状态栏，点工具栏按钮/刷新不会丢；状态栏用与效果图同款琥珀色胶囊 */
.queue-chip { flex: none; cursor: default; }
.doc-statusbar .queue-chip { display: inline-flex; align-items: center; gap: 5px;
  height: 24px; padding: 0 9px; border-radius: 999px; font-size: 11px; color: var(--app-warn-text);
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); }
/* 作废胶囊：可点击下钻，区别于排队/执行的只读指示 */
.dead-chip { cursor: pointer; }
.dead-chip:hover { filter: brightness(0.96); }
.dead-list { display: flex; flex-direction: column; gap: 10px; }
.dead-card { border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; background: var(--app-panel); }
.dead-card-head { display: flex; align-items: center; gap: 8px; }
.dead-name { flex: 1; min-width: 0; font-size: 13px; color: var(--app-text); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dead-meta { flex: none; font-size: 11px; color: var(--app-text3); }
.dead-reason { margin-top: 6px; font-size: 12px; color: var(--app-danger); line-height: 1.5; word-break: break-all; }
.dead-foot { margin-top: 8px; display: flex; align-items: center; gap: 8px; }
.dead-time { flex: 1; font-size: 11px; color: var(--app-text3); }
.dead-acts { flex: none; }
/* 文档标签：贴在文件名后的小号 chip；标签多时行内收起（悬浮 title 仍全量可见） */
.doc-tags { display: inline-flex; gap: 4px; flex-wrap: wrap; margin-left: 8px; max-width: 260px; overflow: hidden; max-height: 22px; }
.doc-tag-chip { margin-inline-end: 0; font-size: 9.5px; line-height: 15px; padding: 0 4px; border-radius: 3px;
  color: var(--app-text3); background: var(--app-panel-2); border-color: var(--app-border); }
/* 回收站徽标：数量小圆点贴在按钮文字后 */
.recycle-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 16px; height: 16px; padding: 0 4px; margin-left: 6px;
  border-radius: 999px; background: var(--app-danger-weak); color: var(--app-danger);
  font-size: 11px; line-height: 1;
}
/* 回收站表格单元格：定宽列里的「692.0 KB」「恢复/彻底删除」被挤成两行会撑高整行 */
.rc-nowrap { white-space: nowrap; }
.drag-mask {
  position: fixed; inset: 0; z-index: 1000;
  background: rgba(46,107,230,.08);
  display: flex; align-items: center; justify-content: center; pointer-events: none;
}
.drag-mask-tip {
  text-align: center; color: var(--app-accent); font-size: 15px;
  background: var(--app-panel); border: 2px dashed var(--app-accent); border-radius: 12px;
  padding: 24px 40px; display: flex; flex-direction: column; gap: 8px; align-items: center;
}
/* 知识块编辑：Markdown 工具栏 + 左写右看分栏实时预览 */
.kb-md-bar {
  display: flex; justify-content: space-between; align-items: center; gap: 8px;
  margin-bottom: 6px;
}
.kb-edit-split { display: flex; gap: 10px; }
/* 切片统计与 Token 列（2.9） */
.kb-stat { display: flex; flex-wrap: wrap; gap: 14px; margin-bottom: 8px; font-size: 12px; color: var(--app-text3); }
.kb-stat b { color: var(--app-text); font-weight: 500; }
.kb-stat-warn { color: var(--app-warn-text); }
.kb-tok { font-variant-numeric: tabular-nums; color: var(--app-text3); }
/* 结构导图（3.3 / 3.5） */
.kb-tree { border: 1px solid var(--app-border); border-radius: 6px; overflow: hidden; }
.kb-tree-head { display: flex; align-items: baseline; gap: 10px; padding: 6px 10px; background: var(--app-panel-2); border-bottom: 1px solid var(--app-border); font-size: 12px; font-weight: 500; }
.kb-tree-tip { font-weight: 400; color: var(--app-text3); }
.kb-tree-body { max-height: 420px; overflow-y: auto; padding: 4px 0; }
.kb-tree-row { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 3px 10px; }
.kb-tree-row:hover { background: var(--app-bg); }
.kb-tree-toggle { flex: none; width: 12px; color: var(--app-text3); cursor: pointer; user-select: none; }
.kb-tree-name { flex: 1; min-width: 0; cursor: pointer; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kb-tree-name:hover { color: var(--app-accent); }
.kb-tree-meta { flex: none; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.kb-tree-empty { padding: 24px 10px; text-align: center; font-size: 12px; color: var(--app-text3); }
/* 行内展开直读（对齐片段卡片：元信息 + 完整 Markdown 内容） */
.kb-expand { padding: 2px 0 8px 16px; }
.kb-expand-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 12px; color: var(--app-text3); }
.kb-expand-path { max-width: 50%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--app-text2); }
.kb-expand-copy { margin-left: auto; }
.kb-expand-md { font-size: 13px; line-height: 1.75; color: var(--app-text); }
.kb-expand-md :deep(p) { margin: 0 0 8px; }
.kb-expand-md :deep(p:last-child) { margin-bottom: 0; }
.kb-expand-md :deep(h1), .kb-expand-md :deep(h2), .kb-expand-md :deep(h3), .kb-expand-md :deep(h4) { margin: 10px 0 6px; line-height: 1.45; }
.kb-expand-md :deep(h1:first-child), .kb-expand-md :deep(h2:first-child), .kb-expand-md :deep(h3:first-child), .kb-expand-md :deep(h4:first-child) { margin-top: 0; }
.kb-expand-md :deep(ul), .kb-expand-md :deep(ol) { margin: 6px 0; }
.kb-expand-md :deep(li) { margin: 2px 0; }
.kb-expand-md :deep(img) { max-width: 100%; }
.kb-expand-md :deep(table) { margin: 8px 0; }
/* 搜索命中高亮 */
.kb-hl { background: var(--app-warn-weak); color: inherit; padding: 0 1px; border-radius: 2px; }
/* 内容摘要：两行显示（不再固定截断 80 字） */
.kb-snippet { color: var(--app-text2); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
/* 章节筛选标识（结构导图 → 切片列表），可一键清除 */
.kb-path-chip {
  display: inline-flex; align-items: center; gap: 6px; margin-bottom: 8px;
  padding: 3px 10px; font-size: 12px; color: var(--app-accent);
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-border); border-radius: 999px;
}
.kb-path-chip b { font-weight: 600; max-width: 520px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kb-path-chip-x { border: none; background: transparent; cursor: pointer; color: var(--app-text3); font-size: 14px; line-height: 1; padding: 0 2px; }
.kb-path-chip-x:hover { color: var(--app-danger); }
.kb-stat-tip { color: var(--app-text3); }
/* 详情弹窗（全局搜索）失败态 */
.kb-detail-err { padding: 24px 0; text-align: center; color: var(--app-text3); font-size: 13px; }
/* 图片灯箱（知识块图片放大）：多图切换 / 滚轮缩放 / 拖动平移，与聊天页同款 */
.lightbox { position: fixed; inset: 0; background: rgba(0,0,0,.78); display: flex; align-items: center; justify-content: center; z-index: 2000; cursor: zoom-out; overflow: hidden; }
.lightbox-img { max-width: 90vw; max-height: 90vh; border-radius: 4px; cursor: grab; user-select: none; transition: transform .12s ease; }
.lightbox-close { position: fixed; top: 16px; right: 24px; font-size: 36px; color: #fff; cursor: pointer; line-height: 1; opacity: .85; }
.lightbox-close:hover { opacity: 1; }
.lightbox-count { position: fixed; bottom: 44px; left: 50%; transform: translateX(-50%); color: rgba(255,255,255,.75); font-size: 13px; background: rgba(0,0,0,.45); padding: 2px 12px; border-radius: 12px; }
.lightbox-tip { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); color: rgba(255,255,255,.6); font-size: 12px; user-select: none; }
.lightbox-prev, .lightbox-next {
  position: fixed; top: 50%; transform: translateY(-50%);
  width: 44px; height: 44px; border-radius: 50%; border: 1px solid rgba(255,255,255,.35);
  background: rgba(0,0,0,.4); color: #fff; font-size: 26px; line-height: 1; cursor: pointer;
  display: flex; align-items: center; justify-content: center; z-index: 2001; user-select: none;
}
.lightbox-prev { left: 16px; }
.lightbox-next { right: 16px; }
.lightbox-prev:hover:not(:disabled), .lightbox-next:hover:not(:disabled) { background: rgba(0,0,0,.7); }
.lightbox-prev:disabled, .lightbox-next:disabled { opacity: .25; cursor: not-allowed; }
/* 高度自适应：矮视口下压缩分栏，保证标题+工具栏+分栏+按钮完整可见（下限 150 保证 577px 视口恰好放下） */
.kb-edit-ta { flex: 1 1 50%; min-width: 0; height: clamp(150px, calc(100vh - 400px), 380px); resize: none; font-size: 13px; line-height: 1.7; }
.kb-edit-preview {
  flex: 1 1 50%; min-width: 0; height: clamp(150px, calc(100vh - 400px), 380px); overflow-y: auto;
  border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-panel-2);
  padding: 10px 14px; font-size: 14px; line-height: 1.7;
}
/* 图片插入菜单项：缩略图 + 编号 */
.kb-img-item { display: flex; align-items: center; }
.kb-img-thumb {
  width: 42px; height: 26px; object-fit: cover; border-radius: 3px;
  margin-right: 8px; border: 1px solid var(--app-border); background: var(--app-panel-2);
}
</style>

<style scoped>
/* ==================== 窄屏适配 ====================
   低优列按屏宽逐级隐藏，保住「文件名 + 状态 + 操作」这条主线；隐藏比挤压可读。 */
@media (max-width: 900px) {
  .col-kb, .col-time { display: none; }
}
@media (max-width: 640px) {
  .col-num, .col-size, .col-status { display: none; }
  .col-act { width: 158px; }
  .doc-row { gap: 8px; }
}
/* 窄屏（≤768，与全站窄屏基准同口径；/knowledge 已进窄屏白名单，只读浏览）：行重排成两行——
   首行文件名占满，次行状态 pill + 操作右对齐。表头栅格在重排后失去对照意义，整体隐藏；
   多选勾选随批量操作栏一起退出窄屏（行内单条操作仍在，管理动作回桌面做）。 */
.doc-tools { margin-left: auto; display: flex; gap: 8px; align-items: center; }
@media (max-width: 768px) {
  .head-row { display: none; }
  .doc-row { flex-wrap: wrap; row-gap: 6px; padding: 10px 12px; }
  .col-check { display: none; }
  .col-name { flex: 1 1 100%; }
  /* 状态列在 ≤640 被隐藏，这里恢复：解析中/失败是只读浏览最需要看见的状态 */
  .col-status { display: flex; width: auto; flex: 1 1 auto; min-width: 0; }
  .col-act { width: auto; flex: none; margin-left: auto; }
  .file-desc { display: none; }   /* 描述挤进首行只会截断成噪声，悬浮 title 仍在 */
  .doc-row :deep(.ant-checkbox-wrapper) { width: 30px; height: 44px; }
  /* 页头工具组：被 flex 压到 min-content 时四字标签会竖排叠字——整组换行铺开、按钮不换行 */
  .doc-tools { width: 100%; margin-left: 0; flex-wrap: wrap; row-gap: 6px; }
  .doc-tools .app-btn { white-space: nowrap; min-height: 38px; padding: 0 10px; font-size: 12px; }
}
</style>
