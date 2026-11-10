/* 问渠智能体管理页升级方案 · 交互逻辑 */
(function () {
  'use strict';

  /* ---------- 常量 ---------- */
  var FRAME_W = 1200, FRAME_H = 820;
  var routeMap = {
    'page-list': 'wenqu.local/agents?tab=agents',
    'page-topo': 'wenqu.local/agents?tab=agents#topology'
  };
  var featureNames = {
    'feature-card': '卡片信息架构重排',
    'feature-filter': '筛选、排序与搜索升级',
    'feature-status': '状态语义统一',
    'feature-micro': '微交互与空状态',
    'feature-a11y': '可访问性与主题',
    'feature-try': '试一试：直达对话闭环',
    'feature-template': '模板化新建 + 复制/导出',
    'feature-batch': '批量操作扩展',
    'feature-topo': '委派关系外显与可编辑编排',
    'feature-usage': '用量与健康度',
    'feature-lifecycle': '草稿 / 发布状态机',
    'feature-share': '团队共享',
    'feature-quick': '快速配置抽屉'
  };

  var tabBtns = document.querySelectorAll('.tab-bar .tab-btn');
  var tabPanes = document.querySelectorAll('.tab-content-wrapper > .tab-pane');
  var wrapper = document.getElementById('tabContentWrapper');
  var indicator = document.getElementById('linkIndicator');

  /* ---------- Tab 切换 ---------- */
  function switchTab(tabId) {
    tabBtns.forEach(function (b) { b.classList.remove('active'); });
    tabPanes.forEach(function (p) { p.classList.remove('active'); });
    var btn = document.querySelector('.tab-bar [data-tab="' + tabId + '"]');
    if (btn) btn.classList.add('active');
    var pane = document.getElementById(tabId);
    if (pane) pane.classList.add('active');
  }
  tabBtns.forEach(function (btn) {
    btn.addEventListener('click', function () { switchTab(btn.dataset.tab); });
  });

  function tabOfFeature(featureId) {
    var el = document.getElementById(featureId);
    if (!el) return null;
    var pane = el.closest('.tab-pane');
    return pane ? pane.id : null;
  }

  /* ---------- 原型 → 文档联动 ---------- */
  function linkToFeature(featureId) {
    var tabId = tabOfFeature(featureId);
    if (!tabId) return;
    switchTab(tabId);
    window.setTimeout(function () {
      var target = document.getElementById(featureId);
      if (!target || !wrapper) return;
      wrapper.scrollTo({
        top: wrapper.scrollTop + target.getBoundingClientRect().top - wrapper.getBoundingClientRect().top - 20,
        behavior: 'smooth'
      });
      document.querySelectorAll('.feature-section').forEach(function (s) { s.classList.remove('highlight'); });
      target.classList.add('highlight');
      window.setTimeout(function () { target.classList.remove('highlight'); }, 2500);
      if (indicator) {
        indicator.textContent = '→ ' + (featureNames[featureId] || featureId);
        indicator.classList.add('show');
        window.setTimeout(function () { indicator.classList.remove('show'); }, 2000);
      }
    }, 100);
  }

  var protoRoot = document.getElementById('prototypeRoot');
  if (protoRoot) {
    protoRoot.addEventListener('click', function (e) {
      // 交互控件优先：自身带 data-link 才联动；否则只执行控件行为，不触发所在容器的联动
      var ctl = e.target.closest('button, .menu, .deleg, .check');
      if (ctl) {
        if (ctl.hasAttribute('data-link')) linkToFeature(ctl.dataset.link);
        return;
      }
      var linkEl = e.target.closest('[data-link]');
      if (linkEl) linkToFeature(linkEl.dataset.link);
    });
  }

  /* ---------- 原型页面导航（相对调用者所在原型根，兼容文档模式嵌入副本） ---------- */
  function protoScope(el) {
    return (el && el.closest('.proto-embed-frame, .browser-frame')) || document.getElementById('browserFrame');
  }

  window.navigate = function (pageId, el) {
    var root = protoScope(el);
    if (!root) return;
    var page = root.querySelector('.page[data-page="' + pageId + '"]');
    if (!page) return; // 嵌入副本里没有目标页面时保持原状，不清空
    root.querySelectorAll('.page').forEach(function (p) { p.classList.remove('active'); });
    page.classList.add('active');
    var addr = root.querySelector('.browser-address');
    if (addr) addr.textContent = routeMap[pageId] || pageId;
  };

  /* ---------- 原型交互：筛选 / 批量 / 弹层 / 菜单 / Toast ---------- */
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('.seg [data-filter]');
    if (!btn) return;
    var seg = btn.closest('.seg');
    seg.querySelectorAll('[data-filter]').forEach(function (b) { b.classList.remove('on'); });
    btn.classList.add('on');
    var f = btn.dataset.filter;
    var scope = protoScope(btn) || document;
    scope.querySelectorAll('.card').forEach(function (card) {
      var types = (card.dataset.type || '').split(/\s+/);
      card.style.display = (f === 'all' || types.indexOf(f) >= 0) ? '' : 'none';
    });
    scope.querySelectorAll('.sec-head').forEach(function (head) {
      var grid = head.nextElementSibling;
      if (!grid || !grid.classList.contains('grid')) return;
      var visible = Array.prototype.some.call(grid.querySelectorAll('.card'), function (c) { return c.style.display !== 'none'; });
      head.style.display = visible ? '' : 'none';
      grid.style.display = visible ? '' : 'none';
    });
  });

  window.toggleBatch = function (el, on) {
    var proto = el && el.closest('.proto');
    if (proto) proto.classList.toggle('batch-on', on);
  };

  window.openModal = function (el) {
    var root = protoScope(el);
    var ov = root && root.querySelector('.overlay');
    if (ov) ov.classList.add('open');
  };
  window.closeModal = function (el) {
    var ov = el && el.closest('.overlay');
    if (ov) ov.classList.remove('open');
  };

  window.toggleMenu = function (btn, e) {
    if (e) e.stopPropagation();
    var menu = btn.parentElement.querySelector('.menu');
    var wasOpen = menu && menu.classList.contains('open');
    document.querySelectorAll('.menu.open').forEach(function (m) { m.classList.remove('open'); });
    if (menu && !wasOpen) menu.classList.add('open');
  };
  document.addEventListener('click', function () {
    document.querySelectorAll('.menu.open').forEach(function (m) { m.classList.remove('open'); });
  });

  var toastTimer = null;
  window.tryIt = function (name, raw, el) {
    var root = protoScope(el);
    var toastEl = root && root.querySelector('.toast');
    if (!toastEl) return;
    toastEl.textContent = raw ? name : '已打开新对话，当前智能体：' + name;
    toastEl.classList.add('show');
    if (toastTimer) window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { toastEl.classList.remove('show'); }, 2200);
  };

  /* ---------- 原型缩放 + 拖拽分隔条 ---------- */
  var handle = document.getElementById('resizeHandle');
  var left = document.querySelector('.left-panel');
  var container = document.querySelector('.main-layout');
  var frame = document.getElementById('browserFrame');

  function scalePrototype() {
    if (!left || !frame) return;
    var pad = 20;
    var panelW = left.clientWidth - pad * 2;
    var panelH = left.clientHeight - pad * 2;
    var scale = Math.min(panelW / FRAME_W, panelH / FRAME_H, 1.2);
    frame.style.transform = 'scale(' + scale + ')';
    frame.style.left = (pad + Math.max(0, (panelW - FRAME_W * scale) / 2)) + 'px';
    frame.style.top = (pad + Math.max(0, (panelH - FRAME_H * scale) / 2)) + 'px';
  }

  if (handle && left && container) {
    var dragging = false;
    handle.addEventListener('mousedown', function () {
      dragging = true; handle.classList.add('active'); document.body.style.userSelect = 'none';
    });
    document.addEventListener('mousemove', function (ev) {
      if (!dragging) return;
      var rect = container.getBoundingClientRect();
      var pct = ((ev.clientX - rect.left) / rect.width) * 100;
      left.style.flex = '0 0 ' + Math.max(25, Math.min(72, pct)) + '%';
      scalePrototype();
    });
    document.addEventListener('mouseup', function () {
      dragging = false; handle.classList.remove('active'); document.body.style.userSelect = '';
    });
  }
  window.addEventListener('resize', scalePrototype);
  window.addEventListener('load', scalePrototype);
  scalePrototype();

  /* ---------- 文档模式：拼装 + 按功能嵌入原型 ---------- */
  function injectFeaturePrototypes(doc) {
    var pages = document.querySelectorAll('#prototypeRoot > .page');
    var used = {};
    doc.querySelectorAll('.feature-section').forEach(function (section) {
      var fid = section.id;
      if (!fid) return;
      var matched = null;
      pages.forEach(function (page) {
        if (used[page.id] || matched) return;
        var hit = (page.getAttribute('data-link') === fid);
        if (!hit) {
          page.querySelectorAll('[data-link]').forEach(function (el) {
            if (el.dataset.link === fid) hit = true;
          });
        }
        if (hit) matched = page;
      });
      if (!matched) return;
      used[matched.id] = true;

      var clone = matched.cloneNode(true);
      clone.removeAttribute('id');
      clone.querySelectorAll('[id]').forEach(function (n) { n.removeAttribute('id'); });
      clone.classList.add('active');
      clone.style.cssText = 'position:absolute;top:0;left:0;right:0;bottom:0;opacity:1;transform:none;display:flex;flex-direction:column;';

      var f = document.createElement('div');
      f.className = 'proto-embed-frame';
      f.appendChild(clone);
      var w = document.createElement('div');
      w.className = 'proto-embed-wrap';
      w.innerHTML = '<div class="proto-embed-label">交互原型（可点击）· ' + (featureNames[fid] || fid) + '</div>';
      w.appendChild(f);
      section.insertBefore(w, section.children[1] || null);
    });
  }

  window.switchMode = function (mode) {
    var modeBtns = document.querySelectorAll('.mode-toggle .mode-btn');
    modeBtns.forEach(function (b) { b.classList.remove('active'); });
    if (mode === 'text') {
      var doc = document.getElementById('textModeDoc');
      if (doc && doc.dataset.built !== '1') {
        var html = '<h1>问渠 · 智能体管理页 UI/UX 与功能升级方案</h1>' +
          '<p class="doc-meta">版本 V1.0 · 状态：评审中 · 日期 2026-11-10 · 范围：/agents?tab=agents 列表页（配置页能力三态改版已完成，不在本方案范围）</p>';
        tabPanes.forEach(function (pane, i) {
          if (i > 0) html += '<hr class="section-divider">';
          html += pane.innerHTML;
        });
        doc.innerHTML = html;
        injectFeaturePrototypes(doc);
        doc.dataset.built = '1';
      }
      document.body.classList.add('text-mode');
      modeBtns[1].classList.add('active');
    } else {
      document.body.classList.remove('text-mode');
      window.scrollTo(0, 0);
      modeBtns[0].classList.add('active');
      scalePrototype();
    }
  };
})();
