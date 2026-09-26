<template>
  <div class="app-page" @dragover.prevent @dragenter.prevent="dragDepth++" @dragleave.prevent="dragDepth = Math.max(0, dragDepth - 1)" @drop.prevent="onDrop">
    <div class="app-page-head">
      <a-breadcrumb>
        <a-breadcrumb-item><a @click="router.push('/knowledge')">知识库</a></a-breadcrumb-item>
        <a-breadcrumb-item>{{ currentKbName }}</a-breadcrumb-item>
      </a-breadcrumb>
      <span class="head-stat">{{ summaryText }}</span>
      <div style="margin-left:auto;display:flex;gap:8px;align-items:center">
        <a-input v-model:value="desc" placeholder="文档描述（可选）" style="width:160px" size="small" allow-clear />
        <button class="app-btn ghost" @click="openGlobalSearch"><search-outlined /> 全局搜索</button>
        <!-- 上传门槛：对当前库有管理权（自己的库，或管理员） -->
        <a-upload v-if="canManageCurrentKb" :before-upload="beforeUpload" :show-upload-list="false" :accept="'.' + uploadCfg.allowedExts.join(',.')" multiple :disabled="uploading">
          <button class="app-btn" :disabled="uploading"><upload-outlined /> {{ uploading ? '上传中…' : '上传文档' }}</button>
        </a-upload>
      </div>
    </div>
    <a-progress v-if="uploading" :percent="uploadPercent" size="small" style="max-width:420px;margin:10px 20px 0" />

    <div class="app-page-body">
      <!-- 拖拽遮罩（仅对可管理的库提示上传） -->
      <div v-if="dragDepth > 0 && canManageCurrentKb" class="drag-mask">
        <div class="drag-mask-tip"><upload-outlined style="font-size:36px" /><div>松开鼠标上传到知识库</div></div>
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
          <span class="col-kb">知识库</span>
          <span class="col-num">片段</span>
          <span class="col-num">命中</span>
          <span class="col-size">大小</span>
          <span class="col-status">状态</span>
          <span class="col-time">上传时间</span>
          <span class="col-act">操作</span>
        </div>
        <a-spin :spinning="loading">
          <div v-for="d in list" :key="d.id" class="doc-row">
            <span class="col-check" v-if="canManageCurrentKb"><a-checkbox :checked="selectedKeys.includes(d.id)" @change="e => toggleSelect(d.id, e)" /></span>
            <span v-else class="col-check"></span>
            <span class="col-name">
              <span class="file-ic" :style="{ background: typeColor(d.fileType).bg, color: typeColor(d.fileType).fg }">{{ (d.fileType || '?').toUpperCase().slice(0, 4) }}</span>
              <span class="file-name" :title="d.fileName + (d.description ? ' · ' + d.description : '')">{{ d.fileName }}<i v-if="d.description" class="file-desc">{{ d.description }}</i></span>
              <span v-if="scopeLabel(d)" class="app-pill warn scope-tag" title="已限制共享范围，点「共享」查看或修改">{{ scopeLabel(d) }}</span>
            </span>
            <span class="col-kb">
              <!-- 移动库 = 写操作：仅对文档有管理权的人开放，其他人显示为纯文本 -->
              <a-select v-if="canManageDoc(d)" :value="kbOf(d)" size="small" style="width:100%" :options="kbSelectOptions"
                        :title="'切换所属知识库：决定哪些助手能检索到该文档'"
                        @change="v => onMoveKb(d, v)" />
              <span v-else class="kb-name-text" :title="kbNameOf(d)">{{ kbNameOf(d) }}</span>
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
              <div v-else-if="d.status === 2" style="min-width:120px">
                <a-progress :percent="d.parseProgress || 0" size="small" style="margin:0" />
                <span class="parse-desc" :title="d.parseDesc || '解析中'">{{ d.parseDesc || '解析中' }}</span>
              </div>
            </span>
            <span class="col-time">{{ fmtTime(d.createTime) }}</span>
            <span class="col-act">
              <template v-if="d.status === 0">
                <button class="app-link-btn" @click="openKb(d)">知识块</button>
                <button class="app-link-btn" @click="openVersions(d)">版本</button>
                <button v-if="canManageDoc(d)" class="app-link-btn" @click="toggleStatus(d, 1)">弃用</button>
              </template>
              <button v-else-if="d.status === 1 && canManageDoc(d)" class="app-link-btn" @click="toggleStatus(d, 0)">启用</button>
              <button v-if="d.status !== 2 && canManageDoc(d)" class="app-link-btn" @click="openShare(d)">共享</button>
              <!-- 源文件下载（个人文件区）：解析中的文档源文件可能正在读写，仅非解析中提供 -->
              <button v-if="d.status !== 2" class="app-link-btn" @click="dlSource(d)">下载</button>
              <button v-if="(d.status === 0 || d.status === 3) && canManageDoc(d)" class="app-link-btn" :disabled="reparsingId === d.id" @click="reparse(d.id)">重解析</button>
              <a-popconfirm v-if="canManageDoc(d)" title="确定删除该文档？知识库将同步移除" @confirm="del(d.id)">
                <button class="app-link-btn danger" :disabled="deletingId === d.id">删除</button>
              </a-popconfirm>
            </span>
          </div>
          <a-empty v-if="!loading && !list.length" description="暂无文档，点击右上角上传 .docx / .pdf / .xlsx" style="padding:40px 0" />
        </a-spin>
      </div>
    </div>

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
        <span v-if="kbView === 'list'" class="kb-stat-tip">点行展开完整内容</span>
      </div>
      <a-spin :spinning="kbLoading">
        <!-- 结构导图（3.3 知识导图 / 3.5 文件元数据导图）：按 titlePath 聚合成章节树，
             每节点带块数/token/图片数，点章节名可直接过滤到该章节的切片 -->
        <div v-if="kbView === 'tree'" class="kb-tree">
          <div class="kb-tree-head">
            <span>章节结构</span>
            <span class="kb-tree-tip">块数 / tokens / 图片 · 点击章节名筛选切片</span>
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
        <a-table :key="kbDocId" :data-source="kbFilteredList" size="small" row-key="id" :pagination="{ pageSize: 20 }"
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
              <span class="kb-tok" :title="'估算值（与后端同口径）：标题+正文'">{{ estimateTokens((record.title || '') + (record.content || '')) }}</span>
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

    <!-- 知识块编辑弹窗：Markdown 工具栏快捷插入 + 左写右看实时预览 + [图片N] 点选插入 + 未保存关闭提醒（与旧版同功能） -->
    <a-modal :open="kbEditVisible" title="编辑知识块" :footer="null" :width="900" :keyboard="!kbImgUrl" @cancel="closeKbEdit">
      <a-form layout="vertical">
        <a-form-item label="标题"><a-input v-model:value="kbEditForm.title" maxlength="200" placeholder="知识块标题" /></a-form-item>
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
               :pagination="gResults.length > 20 ? { pageSize: 20 } : false"
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

    <!-- 共享范围（公共组件：与智能体 / API Key 共用同一套两区表单） -->
    <ShareScopeModal v-model:open="shareVisible" resource-label="文档" read-verb="查看并检索"
                     :share-config="shareTarget.shareConfig" :save-fn="saveShareFn" @saved="fetchList" />

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
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { UploadOutlined, SearchOutlined, DownOutlined } from '@ant-design/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { listDocuments, uploadDocumentsBatch, updateDocumentStatus, reparseDocument, deleteDocument,
         batchDeleteDocuments, batchUpdateDocumentStatus, getDocumentStats, listKnowledgeByDoc, getKnowledgeDetail,
         updateKnowledge, deleteKnowledge, listDocumentVersions, rollbackDocument,
         getRuntimeConfig, batchReparseDocuments, updateKnowledgeStatus, searchKnowledge,
         downloadDocumentSource, updateDocumentShare, listKnowledgeBases, moveDocToKb } from '../api'
import ShareScopeModal from './ShareScopeModal.vue'
import { renderMd, prepKnowledgeContent, resolveImg, onImgError, copyCode } from '../utils/markdown'
import { estimateTokens, fmtTokens } from '../utils/token'
import { isAdminSync, ensureAuth } from '../utils/auth'

// 上传限制（启动时从 /config/public 动态获取）
const MAX_SIZE = 200 * 1024 * 1024
const uploadCfg = ref({ maxFileSize: MAX_SIZE, maxFileSizeLabel: '200MB', allowedExts: ['docx', 'pdf', 'xlsx'] })
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
    docx: { bg: '#e6f1fb', fg: '#185fa5' }, doc: { bg: '#e6f1fb', fg: '#185fa5' },
    pdf: { bg: '#fcebeb', fg: '#a32d2d' }, xlsx: { bg: '#eaf3de', fg: '#3b6d11' }, xls: { bg: '#eaf3de', fg: '#3b6d11' }
  }
  return map[(t || '').toLowerCase()] || { bg: '#f1f3f5', fg: '#5f6570' }
}

const route = useRoute()
const router = useRouter()
// 权限：文档管理对普通用户按"自建自管"开放——自己的库/文档可管理，别人共享的只读
const isAdmin = isAdminSync()
const myUid = ref('')
/** 当前库是否可管理（上传/批量操作的门槛） */
const canManageCurrentKb = computed(() => {
  if (isAdmin) return true
  const k = kbases.value.find(x => x.id === currentKbId.value)
  return !!k && k.createdBy === myUid.value
})
/** 单个文档是否可管理（创建者或管理员；后端还会按共享范围二次判定） */
const canManageDoc = d => isAdmin || (myUid.value && d.createdBy === myUid.value)
/** 当前所在知识库（路由参数）：列表/上传都限定在该库 */
const currentKbId = computed(() => String(route.params.kbId || ''))
const currentKbName = computed(() => {
  const k = kbases.value.find(x => x.id === currentKbId.value)
  return k ? k.name : '知识库'
})

const list = ref([])
const currentDoc = ref(null)   // 当前打开抽屉（知识块/版本）的文档行：判定行内管理按钮显隐
const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const deletingId = ref('')
const reparsingId = ref('')
const desc = ref('')
const dragDepth = ref(0)
const selectedKeys = ref([])
let pollTimer = null

const summaryText = computed(() => {
  const total = list.value.length
  const chunks = list.value.reduce((n, d) => n + (d.chunkCount || 0), 0)
  const active = list.value.filter(d => d.status === 0).length
  // 文件区概览（5.4）：源文件总占用，让"存了多少"可见
  const bytes = list.value.reduce((n, d) => n + (d.fileSize || 0), 0)
  return `${total} 个文档 · ${active} 个生效 · ${chunks} 片段 · 占用 ${fmtSize(bytes)}`
})

const toggleSelect = (id, e) => {
  const at = selectedKeys.value.indexOf(id)
  if (e.target.checked) { if (at < 0) selectedKeys.value.push(id) }
  else if (at >= 0) selectedKeys.value.splice(at, 1)
}

onMounted(() => { fetchList(); fetchKbs(); loadUploadCfg(); ensureAuth().then(me => { myUid.value = me.user || '' }); window.addEventListener('paste', onPaste) })
watch(currentKbId, () => { fetchList() })
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  window.removeEventListener('paste', onPaste)
  window.removeEventListener('keydown', onKbImgKeydown)
})

// ==================== 知识库归属（文档属于哪个库，决定能被哪些助手检索到） ====================
const kbases = ref([])
const defaultKbId = computed(() => (kbases.value.find(k => k.isDefault === 1) || {}).id || '')
const kbSelectOptions = computed(() =>
  kbases.value.map(k => ({ value: k.id, label: k.name + (k.docCount != null ? `（${k.docCount}）` : '') })))
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

async function fetchList () {
  loading.value = true
  try {
    // 命中统计是管理端点：普通用户不调（命中列显示 0），避免 403 触发全局误报提示
    const statsP = isAdminSync() ? getDocumentStats() : Promise.resolve(null)
    const [r, stats] = await Promise.all([listDocuments(route.params.kbId), statsP])
    if (r.success) {
      const hitMap = (stats && stats.success && stats.data) ? stats.data : {}
      list.value = (r.data || []).map(d => ({ ...d, hitCount: hitMap[d.id] || 0 }))
      if (list.value.some(d => d.status === 2)) startPolling()
      else stopPolling()
    }
  } catch (e) { message.error(e.message || '获取列表失败') }
  finally { loading.value = false }
}
function startPolling () {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      const r = await listDocuments()
      if (r.success) {
        list.value = r.data || []
        if (!list.value.some(d => d.status === 2)) stopPolling()
      }
    } catch (e) { /* 轮询失败忽略 */ }
  }, 3000)
}
function stopPolling () {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function beforeUpload (fileList) {
  const files = Array.isArray(fileList) ? fileList : [fileList]
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
  try {
    const r = await uploadDocumentsBatch(files, pct => { uploadPercent.value = pct }, desc.value?.trim() || undefined, route.params.kbId)
    if (r.success) {
      const failed = (r.data || []).filter(x => !x.success)
      if (failed.length) message.warning(`${files.length - failed.length} 个提交成功，${failed.length} 个失败: ${failed[0].msg || ''}`)
      else message.success(`已提交 ${files.length} 个文档解析`)
      desc.value = ''
      fetchList()
    } else message.error(r.msg || '上传失败')
  } catch (e) { message.error(e.message || '上传失败') }
  finally { uploading.value = false }
  return false
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

async function toggleStatus (record, status) {
  try {
    const r = await updateDocumentStatus(record.id, status)
    if (r.success) { message.success(status === 0 ? '已启用' : '已弃用'); fetchList() }
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
async function del (id) {
  deletingId.value = id
  try {
    const r = await deleteDocument(id)
    if (r.success) { message.success('删除成功'); fetchList() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
  finally { deletingId.value = '' }
}
function showFailReason (record) {
  Modal.info({ title: `解析失败 - ${record.fileName}`, content: record.failReason || '未知原因，可点击"重解析"重试' })
}

// ==================== 共享范围（弹窗为公共组件 ShareScopeModal） ====================
const shareVisible = ref(false)
const shareTarget = ref({ id: '', shareConfig: '' })
// 保存回调透传给组件：组件只负责表单与校验，打哪个接口由调用方决定
const saveShareFn = json => updateDocumentShare(shareTarget.value.id, json)

// 文档行的共享范围标记：仅非全员时显示（全员=默认，不显示以免噪音）
function scopeLabel (d) {
  if (!d.shareConfig || !String(d.shareConfig).trim()) return ''
  let cfg = null
  try { cfg = JSON.parse(d.shareConfig) } catch (e) { return '' }
  const r = (cfg && cfg.read_scope) || {}
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

function openShare (d) {
  shareTarget.value = { id: d.id, shareConfig: d.shareConfig || '' }
  shareVisible.value = true
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
const kbTreeRows = computed(() => {
  const mk = (name, path) => ({ name, path, children: new Map(), own: [], tokens: 0, imgs: 0, count: 0 })
  const root = mk('', '')
  for (const r of kbFilteredList.value) {
    const parts = String(r.titlePath || r.title || '未分类').split('>').map(s => s.trim()).filter(Boolean)
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
      const p = k.titlePath || ''
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
const copyKbContent = async record => {
  try {
    await navigator.clipboard.writeText(record.content || '')
    message.success('已复制知识块内容')
  } catch (e) { message.warning('浏览器未授权剪贴板，请手动选中复制') }
}
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
.head-stat { font-size: 12px; color: var(--app-text3); }
.batch-bar {
  display: flex; align-items: center; gap: 10px; padding: 8px 12px; margin-bottom: 10px;
  background: var(--app-accent-weak); border: 1px solid #c9d9f5; border-radius: 8px;
  font-size: 12px; color: var(--app-accent);
}
.doc-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 14px;
  border-bottom: 1px solid #f1f3f5; font-size: 12px; color: var(--app-text2); min-width: 0;
}
.doc-row:last-child { border-bottom: none; }
.doc-row:not(.head-row):hover { background: #fafbfc; }
.head-row { font-size: 11px; color: var(--app-text3); background: #fafbfc; border-bottom: 1px solid var(--app-border); user-select: none; }
.col-check { width: 26px; flex: none; }
.col-name { flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; }
.col-kb { width: 150px; flex: none; }
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
.col-act { width: 250px; flex: none; text-align: right; white-space: nowrap; }
.parse-desc { font-size: 11px; color: var(--app-text3); display: inline-block; max-width: 150px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 共享范围列表标记（弹窗两区表单已抽为公共组件 ShareScopeModal） */
.scope-tag { flex: none; }
.drag-mask {
  position: fixed; inset: 0; z-index: 1000;
  background: rgba(46,107,230,.08);
  display: flex; align-items: center; justify-content: center; pointer-events: none;
}
.drag-mask-tip {
  text-align: center; color: var(--app-accent); font-size: 15px;
  background: #fff; border: 2px dashed var(--app-accent); border-radius: 12px;
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
.kb-stat-warn { color: #d46b08; }
.kb-tok { font-variant-numeric: tabular-nums; color: var(--app-text3); }
/* 结构导图（3.3 / 3.5） */
.kb-tree { border: 1px solid var(--app-border); border-radius: 6px; overflow: hidden; }
.kb-tree-head { display: flex; align-items: baseline; gap: 10px; padding: 6px 10px; background: #fafbfc; border-bottom: 1px solid var(--app-border); font-size: 12px; font-weight: 500; }
.kb-tree-tip { font-weight: 400; color: var(--app-text3); }
.kb-tree-body { max-height: 420px; overflow-y: auto; padding: 4px 0; }
.kb-tree-row { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 3px 10px; }
.kb-tree-row:hover { background: #f7f8fa; }
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
.kb-hl { background: #fff1b8; color: inherit; padding: 0 1px; border-radius: 2px; }
/* 内容摘要：两行显示（不再固定截断 80 字） */
.kb-snippet { color: var(--app-text2); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
/* 章节筛选标识（结构导图 → 切片列表），可一键清除 */
.kb-path-chip {
  display: inline-flex; align-items: center; gap: 6px; margin-bottom: 8px;
  padding: 3px 10px; font-size: 12px; color: var(--app-accent);
  background: var(--app-accent-weak); border: 1px solid #c9d9f5; border-radius: 999px;
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
  border: 1px solid var(--app-border); border-radius: 6px; background: #fafbfc;
  padding: 10px 14px; font-size: 14px; line-height: 1.7;
}
/* 图片插入菜单项：缩略图 + 编号 */
.kb-img-item { display: flex; align-items: center; }
.kb-img-thumb {
  width: 42px; height: 26px; object-fit: cover; border-radius: 3px;
  margin-right: 8px; border: 1px solid #eee; background: #fafafa;
}
</style>
