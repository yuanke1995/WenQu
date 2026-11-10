/*
 * 问渠嵌入挂件加载器（wenqu-embed.js）
 *
 * 任何业务页面加两行代码，就获得一个浮动聊天入口：
 *   <script src="https://<问渠地址>/wenqu-embed.js"></script>
 *   <script>WenquChat.init({ token: '<分享token>' })</script>
 *
 * 设计约束：
 * - 只用分享 token（/s/{token} 游客通道），不接触 API Key——前端存 Key 有泄露风险；
 * - 零依赖、单文件，iframe 的 src 由「脚本自身地址」推导同源根，宿主不需要重复配置站点地址；
 * - token 必须是安全字符集（UUID/短串形态），脏值直接拒绝初始化，不给注入面。
 */
(function (global) {
  'use strict'

  var TOKEN_RE = /^[A-Za-z0-9_-]{6,64}$/
  var COLOR_RE = /^#[0-9a-fA-F]{3,8}$/

  /** 站点根：脚本 src 去掉文件名与 /s/、/wenqu-embed.js 等尾巴后的同源前缀 */
  function siteBase() {
    var el = document.currentScript
    if (!el || !el.src) return location.origin
    try {
      var u = new URL(el.src, location.href)
      // 常见形态：{base}/wenqu-embed.js 或 {base}/embed/wenqu-embed.js
      var path = u.pathname.replace(/\/wenqu-embed\.js.*$/, '').replace(/\/embed$/, '')
      return u.origin + path
    } catch (e) {
      return location.origin
    }
  }

  function css(scope, text) {
    var s = document.createElement('style')
    s.textContent = text
    document.head.appendChild(s)
  }

  function init(opts) {
    opts = opts || {}
    var token = String(opts.token || '')
    if (!TOKEN_RE.test(token)) {
      console.error('[WenquChat] token 形态不合法（应为 6-64 位字母/数字/-/_，即分享链接 /s/ 后面那段）')
      return null
    }
    var base = siteBase()
    var color = COLOR_RE.test(opts.color || '') ? opts.color : '#3b82f6'
    var position = opts.position === 'bottom-left' ? 'left' : 'right'
    var width = Math.min(Math.max(parseInt(opts.width, 10) || 400, 320), 720)
    var height = Math.min(Math.max(parseInt(opts.height, 10) || 600, 420), 900)

    css('wenqu-embed', [
      '.wq-fab{position:fixed;bottom:22px;' + position + ':22px;z-index:2147483000;width:54px;height:54px;',
      'border-radius:50%;border:none;cursor:pointer;background:' + color + ';color:#fff;',
      'box-shadow:0 6px 20px rgba(0,0,0,.25);display:flex;align-items:center;justify-content:center;',
      'font-size:22px;transition:transform .15s ease}',
      '.wq-fab:hover{transform:scale(1.06)}',
      '.wq-panel{position:fixed;bottom:90px;' + position + ':22px;z-index:2147483000;width:' + width + 'px;',
      'height:' + height + 'px;max-height:calc(100vh - 120px);max-width:calc(100vw - 44px);',
      'border-radius:14px;overflow:hidden;box-shadow:0 12px 40px rgba(0,0,0,.28);background:#fff;',
      'display:none;flex-direction:column;border:1px solid rgba(0,0,0,.08)}',
      '.wq-panel.open{display:flex}',
      '.wq-bar{flex:none;height:40px;display:flex;align-items:center;justify-content:space-between;',
      'padding:0 12px;background:' + color + ';color:#fff;font:600 13px/1 system-ui,sans-serif}',
      '.wq-close{border:none;background:transparent;color:#fff;font-size:18px;cursor:pointer;',
      'width:28px;height:28px;border-radius:6px;line-height:1}',
      '.wq-close:hover{background:rgba(255,255,255,.18)}',
      '.wq-frame{flex:1;width:100%;border:0}',
      '@media (max-width:520px){.wq-panel{left:8px;right:8px;bottom:70px;width:auto;height:70vh}}'
    ].join(''))

    var fab = document.createElement('button')
    fab.className = 'wq-fab'
    fab.setAttribute('aria-label', opts.title || '在线咨询')
    fab.textContent = '💬'
    var panel = document.createElement('div')
    panel.className = 'wq-panel'
    var bar = document.createElement('div')
    bar.className = 'wq-bar'
    var barTitle = document.createElement('span')
    barTitle.textContent = opts.title || '在线咨询'
    var closeBtn = document.createElement('button')
    closeBtn.className = 'wq-close'
    closeBtn.setAttribute('aria-label', '关闭')
    closeBtn.textContent = '×'
    bar.appendChild(barTitle)
    bar.appendChild(closeBtn)
    var frame = document.createElement('iframe')
    frame.className = 'wq-frame'
    frame.setAttribute('allow', 'clipboard-write')
    panel.appendChild(bar)
    panel.appendChild(frame)
    document.body.appendChild(fab)
    document.body.appendChild(panel)

    // iframe 懒加载：首次展开才建会话，宿主页面不为一颗浮动按钮付流量
    function src() {
      var q = ['embed=1']
      if (opts.placeholder) q.push('placeholder=' + encodeURIComponent(String(opts.placeholder).slice(0, 50)))
      if (opts.noTool === true) q.push('no-tool=1')
      if (opts.compact === true) q.push('compact=1')
      if (opts.color && COLOR_RE.test(opts.color)) q.push('color=' + encodeURIComponent(opts.color))
      if (opts.autoOpen === true) q.push('autoOpen=1')
      return base + '/s/' + encodeURIComponent(token) + '?' + q.join('&')
    }
    function open() {
      if (!frame.src) frame.src = src()
      panel.classList.add('open')
      fab.textContent = '×'
    }
    function close() {
      panel.classList.remove('open')
      fab.textContent = '💬'
    }
    fab.addEventListener('click', function () {
      panel.classList.contains('open') ? close() : open()
    })
    closeBtn.addEventListener('click', close)

    // 宿主程序化控制：WenquChat.open() / .close() / .toggle()
    var api = {
      open: open,
      close: close,
      toggle: function () { panel.classList.contains('open') ? close() : open() },
      destroy: function () { fab.remove(); panel.remove() }
    }
    if (opts.autoOpen === true) open()
    return api
  }

  global.WenquChat = { init: init }
})(window)
