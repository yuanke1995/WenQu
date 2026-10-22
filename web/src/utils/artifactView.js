// 产物与工作区文件「怎么预览」的判据只有这一份。
//
// 分档按**用途**，不按「浏览器打不打得开」：
//   page（html/htm）＝页面设计，要看的是真实视口下的布局、断点与首屏——把它塞进固定尺寸的弹窗
//     等于没预览（1080px 不是任何真实设备宽度，86vh 把首屏折掉一截），所以预览台给它可控宽度；
//   md/table/text＝读内容，读字不需要真实视口，取原文在应用里渲染（md 走 markdown-it、csv 走只读
//     表格、其余走高亮围栏）——这三档直接甩给浏览器是假预览：.md 开出来是满屏原始竖线，csv 干脆掉下载；
//   image/pdf＝浏览器原生就能看，预览台只负责把它铺满并给缩放。
//
// 与服务端那份清单（ProjectFileService.TEXT_PREVIEW_EXTS / IMAGE_PREVIEW_EXTS）对账：那一份管「这条地址
// 发什么 Content-Type、超没超体积帽」，这一份管「拿回来之后怎么摆」。ico/avif 两边都不列——服务端
// sniffImageType 会把未知类型兜成 image/jpeg，给个错 Content-Type 的按钮比不给按钮更糊弄人。
//
// 同一份东西在不同页面给出不同按钮集，用户只会当成随机行为，所以六个入口面都只问这一个函数。

export const PV = {
  PAGE: 'page',
  IMAGE: 'image',
  PDF: 'pdf',
  MD: 'md',
  TABLE: 'table',
  TEXT: 'text',
  NONE: 'none'
}

const BY_EXT = {
  htm: PV.PAGE, html: PV.PAGE,
  png: PV.IMAGE, jpg: PV.IMAGE, jpeg: PV.IMAGE, gif: PV.IMAGE, webp: PV.IMAGE, bmp: PV.IMAGE, svg: PV.IMAGE,
  pdf: PV.PDF,
  md: PV.MD, markdown: PV.MD,
  csv: PV.TABLE, tsv: PV.TABLE,
  txt: PV.TEXT, log: PV.TEXT, json: PV.TEXT, yaml: PV.TEXT, yml: PV.TEXT, xml: PV.TEXT, toml: PV.TEXT,
  ini: PV.TEXT, conf: PV.TEXT, properties: PV.TEXT,
  py: PV.TEXT, js: PV.TEXT, mjs: PV.TEXT, ts: PV.TEXT, jsx: PV.TEXT, tsx: PV.TEXT, java: PV.TEXT,
  go: PV.TEXT, rs: PV.TEXT, sql: PV.TEXT, sh: PV.TEXT, bash: PV.TEXT, zsh: PV.TEXT,
  css: PV.TEXT, scss: PV.TEXT, vue: PV.TEXT
}

/** 产物/文件扩展名（小写、不带点）：优先服务端给的 ext，没有就取文件名后缀 */
export const artifactExt = a => {
  if (!a) return ''
  const ext = String(a.ext || '')
  if (ext) return ext.replace(/^\./, '').toLowerCase()
  const name = String(a.filename || a.name || '')
  const i = name.lastIndexOf('.')
  return i > 0 ? name.slice(i + 1).toLowerCase() : ''
}

/** 这一份东西怎么预览；返回 PV.NONE 表示只给下载，别摆「预览」按钮骗人 */
export const previewKindOf = a => (a && BY_EXT[artifactExt(a)]) || PV.NONE

/** 给模板用的布尔判据（previewKindOf 返回的是字符串，'none' 也是真值，别直接放进 v-if） */
export const canPreview = a => previewKindOf(a) !== PV.NONE

/** 预览台要不要给视口档位与缩放——只有页面设计要，读字的档位给了是噪音 */
export const hasViewportTiers = kind => kind === PV.PAGE
