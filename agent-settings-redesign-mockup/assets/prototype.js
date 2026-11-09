// 智能体配置页改版视觉稿 · 交互原型脚本
(function () {
  var proto = document.getElementById('capProto');
  if (!proto) return;

  // ---- 差异计数（卡头 pill 与右侧摘要同口径） ----
  function refreshDiff() {
    var n = proto.querySelectorAll('.row.overridden').length;
    var num = document.getElementById('diffNum');
    var all = document.getElementById('diffAll');
    document.getElementById('diffCount').textContent = n;
    num.classList.toggle('show', n > 0);
    all.style.display = n > 0 ? 'none' : '';
  }

  // ---- 勾选区计数 ----
  function refreshPickCount(wrap) {
    var pick = wrap.querySelector('.pick');
    if (!pick) return;
    var c = pick.querySelectorAll('.chip.on').length;
    var label = pick.querySelector('[data-pickcount]');
    if (label) label.textContent = c;
    var segBtn = wrap.querySelector('.seg button[data-v="pick"]');
    if (segBtn) {
      var gv = segBtn.querySelector('[data-pickn]');
      if (gv) gv.textContent = c + ' 项';
    }
  }

  // ---- 分段控件 ----
  proto.querySelectorAll('.row-wrap').forEach(function (wrap) {
    var row = wrap.querySelector('.row');
    var seg = row.querySelector('.seg');
    var kind = row.dataset.kind;
    var pick = wrap.querySelector('.pick');

    seg.addEventListener('click', function (e) {
      var btn = e.target.closest('button[data-v]');
      if (!btn) return;
      seg.querySelectorAll('button').forEach(function (b) { b.classList.toggle('on', b === btn); });
      var v = btn.dataset.v;
      // 覆盖态：非「跟随全局」即为覆盖
      row.classList.toggle('overridden', v !== 'inherit');
      if (pick) {
        var showPick = (kind === 'multi' && v === 'pick') || (kind === 'switch' && v === 'on');
        pick.classList.toggle('show', showPick);
        if (showPick) refreshPickCount(wrap);
      }
      refreshDiff();
    });

    // chips 点选
    if (pick) {
      pick.querySelector('.chips').addEventListener('click', function (e) {
        var chip = e.target.closest('.chip');
        if (!chip || chip.textContent.indexOf('+') === 0) return;
        chip.classList.toggle('on');
        refreshPickCount(wrap);
      });
    }
  });

  // ---- 恢复跟随 ----
  document.getElementById('diffReset').addEventListener('click', function () {
    proto.querySelectorAll('.row-wrap').forEach(function (wrap) {
      var row = wrap.querySelector('.row');
      var seg = row.querySelector('.seg');
      seg.querySelectorAll('button').forEach(function (b, i) { b.classList.toggle('on', i === 0); });
      row.classList.remove('overridden');
      var pick = wrap.querySelector('.pick');
      if (pick) pick.classList.remove('show');
    });
    refreshDiff();
  });

  // ---- 高级设置 · 专家选项折叠：原生 <details>，无需脚本 ----

  refreshDiff();
})();
