<script setup>
/**
 * 提问消息的随行内容：图片 + 附件（主对话页与公开分享页共用）。
 *
 * <p>分享页此前只渲染 {@code m.content}，游客发了图之后<b>自己的气泡里空空如也</b>——
 * 只看得见自己打的那行字，看不见发出去的图与文件。两处各画一份的后果就是这种漏。
 *
 * <p>为什么把「图片 + 附件」作为共享边界，而 @ 引用 / # 历史引用走插槽：
 * 后两者是登录态独有能力（游客链路根本不产生），且它们的图标与悬浮文案与当轮语义绑定，
 * 放进共享组件只会让组件去猜「这个 type 是什么意思」。{@code #meta} 插槽把这块留给主对话页。
 *
 * <p>附件两种形态都在这一个组件里：长文本粘贴转成的卡片（点开看全文，emit 给页面弹窗）
 * 与真附件的单行 chip。判据用共享的 {@code isPastedText}，不在这里另立一套。
 */
import { FileTextOutlined, PaperClipOutlined } from '@ant-design/icons-vue'
import { fmtSize, isPastedText, pasteTitle, pasteSub } from './projections'
import { openImages } from './answerViewer'
import { resolveImg, onImgError } from '../utils/markdown'

const props = defineProps({
  /** 提问里的图片（data URL 或服务端 URL 都可能：本轮内存里是前者，历史回放是后者） */
  images: { type: Array, default: () => [] },
  /** 本轮的附件元信息 [{name,mime,size,text?}]；没有 size 时 chip 自动省掉体积那一段 */
  attachments: { type: Array, default: () => [] }
})
const emit = defineEmits(['paste-view'])

/** 点缩略图开灯箱：URL 解析与灯箱都是共享实现，两页行为一致 */
function preview (i) {
  openImages((props.images || []).map(resolveImg), i || 0)
}
</script>

<template>
  <div v-if="(images && images.length) || (attachments && attachments.length)" class="ub-media">
    <div v-if="images && images.length" class="msg-imgs">
      <!-- loading=lazy：缩略图是定尺 88×88（不会因延迟加载撑开布局），一屏几十张上传图时
           只取滚到跟前的那几张，其余交给浏览器按需拉 -->
      <img v-for="(u, ui) in images" :key="ui" :src="resolveImg(u)" class="msg-img"
           loading="lazy" decoding="async"
           :alt="'上传图片' + (ui + 1)" @click="preview(ui)" @error="onImgError" />
    </div>
    <div v-if="attachments && attachments.length" class="msg-files">
      <template v-for="(a, fi) in attachments" :key="fi">
        <!-- 长文本粘贴：卡片形态，点开看全文（正文只留在内存，落库的仅名称/类型/体积） -->
        <span v-if="isPastedText(a)" class="msg-file paste-card"
              :title="a.text ? '点击查看粘贴的全文' : '粘贴文本的正文未随历史记录保留'"
              @click="emit('paste-view', a)">
          <span class="paste-card-ic"><file-text-outlined /></span>
          <span class="paste-card-txt">
            <span class="paste-card-name">{{ pasteTitle(a.name) }}</span>
            <span class="paste-card-sub">{{ pasteSub(a) }}</span>
          </span>
        </span>
        <span v-else class="msg-file"
              :title="(a.mime || '附件') + (a.size ? ' · ' + fmtSize(a.size) : '')">
          <paper-clip-outlined class="msg-file-ic" />
          <span class="msg-file-name">{{ a.name }}</span>
          <span v-if="a.size" class="msg-file-size">{{ fmtSize(a.size) }}</span>
        </span>
      </template>
    </div>
    <slot name="meta" />
  </div>
</template>

<style scoped>
/* 图片与附件都在气泡上方一行排开（与气泡本身分属两块：气泡只装文字） */
.ub-media { display: flex; flex-direction: column; }
.msg-imgs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.msg-img {
  width: 88px; height: 88px; object-fit: cover; border-radius: 8px;
  border: 1px solid var(--app-border); cursor: zoom-in; display: block;
}
.msg-files { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.msg-file {
  display: inline-flex; align-items: center; gap: 5px; max-width: 260px;
  padding: 4px 8px; border-radius: 8px; font-size: 12px;
  background: rgba(255, 255, 255, .55); border: 1px solid var(--app-border);
}
.msg-file-ic { flex: none; color: var(--app-accent); font-size: 13px; }
.msg-file-name { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.msg-file-size { flex: none; font-size: 11px; opacity: .65; }

/* 长文本粘贴卡片（气泡内 / 待发送 chip 共用一套观感）：图标块 + 标题与体积两行，点开看全文。
   放在 .msg-file 之后，靠同权重后置覆盖它的单行 chip 尺寸 */
.paste-card {
  display: flex; align-items: center; gap: 10px; max-width: 320px; padding: 8px 10px;
  border-radius: 10px; cursor: pointer;
}
.paste-card-ic {
  flex: none; width: 32px; height: 32px; border-radius: 8px; font-size: 16px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--app-panel); border: 1px solid var(--app-border); color: var(--app-accent);
}
.paste-card-txt { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.paste-card-name { font-size: 13px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.paste-card-sub { font-size: 11px; color: var(--app-text3); }

/* 窄屏缩略图收小：一排能多放两张，也不至于把气泡顶出屏幕 */
@media (max-width: 768px) {
  .msg-img { width: 76px; height: 76px; }
  .msg-file { max-width: 200px; }
}
</style>
