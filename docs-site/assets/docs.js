/* ==================== 问渠对外集成文档站 · 交互 ====================
   零依赖、无构建期状态：只做四件事——基址替换、代码复制、侧栏高亮、站内搜索。
   刻意不在页面里写死任何主机名：同一份产物既当独立文档站用，也被应用内「对接文档」页 iframe 嵌入。
*/

(function () {
  'use strict'

  /* ---------------- 1. 基址 ---------------- */
  // 优先级：?base= 查询参数 > 上次记住的 > 当前站点 origin + /ai
  // 为什么不是写死：本地起 web(5800) 时后端在 8090，线上则前后端同域；同一份静态产物要两种都成立。
  var BASE_KEY = 'wenqu.docs.base'

  function normalizeBase(raw) {
    if (!raw) return ''
    var v = String(raw).trim()
    if (!/^https?:\/\//i.test(v)) return ''
    return v.replace(/\/+$/, '')
  }

  function resolveBase() {
    var q = new URLSearchParams(location.search).get('base')
    var fromQuery = normalizeBase(q)
    if (fromQuery) {
      try { localStorage.setItem(BASE_KEY, fromQuery) } catch (e) { /* 隐私模式忽略 */ }
      return fromQuery
    }
    var saved = ''
    try { saved = normalizeBase(localStorage.getItem(BASE_KEY)) } catch (e) { saved = '' }
    if (saved) return saved
    return location.origin + '/ai'
  }

  var BASE = resolveBase()

  function fillBase() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null)
    var nodes = []
    while (walker.nextNode()) {
      if (walker.currentNode.nodeValue.indexOf('__BASE__') >= 0) nodes.push(walker.currentNode)
    }
    nodes.forEach(function (n) { n.nodeValue = n.nodeValue.split('__BASE__').join(BASE) })
    var slots = document.querySelectorAll('[data-base-slot]')
    for (var i = 0; i < slots.length; i++) slots[i].textContent = BASE
  }

  /* ---------------- 2. 复制 ---------------- */
  function copyText(text, btn) {
    var done = function () {
      var old = btn.textContent
      btn.textContent = '已复制'
      btn.classList.add('done')
      setTimeout(function () { btn.textContent = old; btn.classList.remove('done') }, 1400)
    }
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(done, function () { fallback(text, done) })
    } else {
      fallback(text, done)
    }
  }

  function fallback(text, done) {
    var ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy'); done() } catch (e) { /* 浏览器拒绝时静默，不伪造成功 */ }
    document.body.removeChild(ta)
  }

  function bindCopy() {
    document.addEventListener('click', function (e) {
      var btn = e.target.closest ? e.target.closest('.copy') : null
      if (!btn) return
      e.preventDefault()
      var text = btn.getAttribute('data-copy-text')
      if (text == null) {
        var box = btn.closest('.code')
        var pre = box && box.querySelector('pre')
        text = pre ? pre.textContent : ''
      }
      text = text.split('__BASE__').join(BASE)
      copyText(text.replace(/\n+$/, '\n'), btn)
    })
  }

  /* ---------------- 3. 侧栏高亮 ---------------- */
  function bindScrollSpy() {
    var heads = [].slice.call(document.querySelectorAll('article h2[id], article h3[id]'))
    if (!heads.length) return
    var pages = [].slice.call(document.querySelectorAll('.nav-page'))
    var subs = [].slice.call(document.querySelectorAll('.nav-sub'))

    var setActive = function (id) {
      subs.forEach(function (a) { a.classList.toggle('active', a.getAttribute('href') === '#' + id) })
      var head = document.getElementById(id)
      if (!head) return
      var pageId = head.closest('.page') ? head.closest('.page').id : ''
      pages.forEach(function (a) {
        a.classList.toggle('active', a.getAttribute('href') === '#' + pageId)
      })
    }

    var current = ''
    var onScroll = function () {
      var probe = window.innerHeight * 0.28
      var hit = heads[0] ? heads[0].id : ''
      for (var i = 0; i < heads.length; i++) {
        if (heads[i].getBoundingClientRect().top - probe <= 0) hit = heads[i].id
        else break
      }
      if (hit && hit !== current) { current = hit; setActive(hit) }
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
    onScroll()
  }

  /* ---------------- 3.5 锚点跳转：跨页直接落位 ---------------- */
  // 整站是单页（近 3 万像素），原生 smooth 从顶部跳到「错误码」要滚三秒以上，
  // 点完像没反应。规则：1.5 屏以内保留平滑（连续感有用），更远直接落位（用户要的是「到那一页」）。
  function bindAnchorScroll() {
    document.addEventListener('click', function (e) {
      var a = e.target.closest ? e.target.closest('a[href^="#"]') : null
      if (!a) return
      var id = (a.getAttribute('href') || '').slice(1)
      if (!id) return
      var target = document.getElementById(id)
      if (!target) return
      e.preventDefault()
      var margin = parseFloat(getComputedStyle(target).scrollMarginTop) || 0
      var top = Math.max(0, target.getBoundingClientRect().top + window.scrollY - margin)
      var far = Math.abs(top - window.scrollY) > window.innerHeight * 1.5
      if (far) {
        // 注意：scrollTo({behavior:'auto'}) 不是「立即」，它按 CSS 的 scroll-behavior 走，
        // 而本站是 smooth —— 等于没改。必须先临时把根元素的 smooth 摘掉。
        var root = document.documentElement
        var prev = root.style.scrollBehavior
        root.style.scrollBehavior = 'auto'
        window.scrollTo(0, top)
        root.style.scrollBehavior = prev
      } else {
        window.scrollTo({ top: top, behavior: 'smooth' })
      }
      if (history.pushState) history.pushState(null, '', '#' + id)
      else location.hash = '#' + id
      var panel = document.getElementById('doc-search-results')
      if (panel) panel.hidden = true
    })
  }

  /* ---------------- 4. 搜索 ---------------- */
  function bindSearch() {
    var input = document.getElementById('doc-search')
    var panel = document.getElementById('doc-search-results')
    if (!input || !panel) return
    var index = null
    var loading = false
    var active = -1

    function load() {
      if (index || loading) return
      loading = true
      fetch('search-index.json').then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status)
        return r.json()
      }).then(function (j) { index = j }).catch(function () { index = [] })
    }

    function render(items) {
      if (!items.length) {
        panel.innerHTML = '<div class="search-empty">没有匹配的条目。试试「runs」「webhook」「scope」。</div>'
        panel.hidden = false
        return
      }
      panel.innerHTML = items.map(function (it, i) {
        return '<a class="search-item' + (i === active ? ' active' : '') + '" href="#' + it.id + '">' +
          '<span class="si-page">' + it.page + '</span>' + escapeHtml(it.title) +
          '<span class="si-text">' + escapeHtml(it.text) + '</span></a>'
      }).join('')
      panel.hidden = false
    }

    function escapeHtml(s) {
      return String(s).replace(/[&<>"]/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]
      })
    }

    function run() {
      var q = input.value.trim().toLowerCase()
      active = -1
      if (q.length < 1) { panel.hidden = true; panel.innerHTML = ''; return }
      load()
      var src = index || []
      var scored = []
      for (var i = 0; i < src.length; i++) {
        var it = src[i]
        var t = (it.title || '').toLowerCase()
        var b = (it.text || '').toLowerCase()
        if (t.indexOf(q) < 0 && b.indexOf(q) < 0) continue
        scored.push({ it: it, score: (t.indexOf(q) >= 0 ? 0 : 1) * 100 + (t.indexOf(q) === 0 ? -10 : 0) + i * 0.001 })
      }
      scored.sort(function (a, b) { return a.score - b.score })
      render(scored.slice(0, 12).map(function (s) { return s.it }))
    }

    input.addEventListener('focus', load)
    input.addEventListener('input', run)
    input.addEventListener('keydown', function (e) {
      var items = [].slice.call(panel.querySelectorAll('.search-item'))
      if (e.key === 'Escape') { input.value = ''; panel.hidden = true; return }
      if (!items.length) return
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        active = e.key === 'ArrowDown' ? Math.min(active + 1, items.length - 1) : Math.max(active - 1, 0)
        items.forEach(function (a, i) { a.classList.toggle('active', i === active) })
        items[active].scrollIntoView({ block: 'nearest' })
      } else if (e.key === 'Enter') {
        e.preventDefault()
        (items[active] || items[0]).click()
      }
    })
    panel.addEventListener('click', function (e) {
      if (e.target.closest('.search-item')) { panel.hidden = true; input.blur() }
    })
    document.addEventListener('click', function (e) {
      if (!e.target.closest('.search-wrap')) panel.hidden = true
    })
    // ⌘K / Ctrl+K 聚焦搜索
    document.addEventListener('keydown', function (e) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); input.focus(); input.select() }
    })
  }

  /* ---------------- 5. 窄屏抽屉 ---------------- */
  function bindDrawer() {
    var toggle = document.querySelector('.side-toggle')
    var side = document.querySelector('.side')
    if (!toggle || !side) return
    var scrim = null
    function close() {
      side.classList.remove('open')
      if (scrim) { scrim.remove(); scrim = null }
    }
    toggle.addEventListener('click', function () {
      side.classList.add('open')
      scrim = document.createElement('div')
      scrim.className = 'scrim'
      scrim.addEventListener('click', close)
      document.body.appendChild(scrim)
    })
    side.addEventListener('click', function (e) {
      if (e.target.closest('a') && window.innerWidth <= 960) close()
    })
  }

  /* ---------------- 6. 版本下拉（当前只有 v1） ---------------- */
  function bindVersion() {
    var sel = document.getElementById('doc-version')
    if (!sel) return
    sel.addEventListener('change', function () {
      if (sel.value !== 'v1') {
        sel.value = 'v1'
        alert('v2 尚未发布。v1 承诺只增不删，变更见「示例与变更日志」。')
      }
    })
  }

  function ready(fn) {
    if (document.readyState !== 'loading') fn()
    else document.addEventListener('DOMContentLoaded', fn)
  }

  ready(function () {
    fillBase()
    bindCopy()
    bindAnchorScroll()
    bindScrollSpy()
    bindSearch()
    bindDrawer()
    bindVersion()
  })
})()
