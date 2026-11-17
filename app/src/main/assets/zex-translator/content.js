// ZexBrowse 页面翻译内容脚本（内置扩展，无界面注入）。
// 通道：browser.runtime.connectNative("zex.translator") ↔ BrowserViewModel 的消息委托。
// 设计要点：
//   1) 覆盖式翻译：译文直接替换原文文本节点（与 Edge/Chrome 内置翻译观感一致），原文留存在内存映射里供“还原”。
//   2) 按批送出：整页文本切成多批（每批 ≤24 段 / ≤1600 字符），原生逐批翻译并回填，长页面也能尽快看到译文。
//   3) 完成回执带“真正替换的节点数”：只有确实替换过文本，原生才会把该标签标成“已翻译”，
//      避免出现“菜单显示还原原文，页面却还是英文”的假成功。
//   4) 增量补译：开启翻译后挂 MutationObserver，页面新插入 / 展开出来的正文（懒加载、折叠展开）自动补译。
//   5) 翻译请求全部由原生侧完成（走应用内代理 + Google/腾讯/微软），内容脚本只负责收集与回写，规避 CORS。
(function () {
  // 内容脚本里 browser 是沙箱全局变量（不在 window 上），必须直接引用；
  // 原生应用名必须是点分标识符（^\w+(\.\w+)*$），连字符会被 schema 校验拒绝。
  var messenger = (typeof browser !== "undefined" && browser) || (typeof chrome !== "undefined" && chrome) || null;
  if (!messenger || !messenger.runtime || typeof messenger.runtime.connectNative !== "function") return;
  var port;
  try {
    port = messenger.runtime.connectNative("zex.translator");
  } catch (e) { return; }
  if (!port) return;
  try { port.postMessage({ cmd: "hello" }); } catch (e) { }

  // 不翻译的元素：结构类、交互控件与媒体
  var SKIP_SEL = "script,style,noscript,svg,code,pre,textarea,select,option,input,button,iframe,template,video,audio,canvas,math,head,title,link,meta";
  // 单个文本节点的长度上限，超出按句子边界切分后再逐段替换（保持原文顺序）
  var SEG_MAX = 420;
  // 单次请求的段数与字符预算：批越大，请求数越少（Google 批量端点一次能吃几十段）
  var BATCH_ITEMS = 45;
  var BATCH_CHARS = 4800;

  var active = false;            // 是否处于“已翻译”状态
  var applied = [];              // 已经替换过的节点记录 {node, orig}
  var queues = {};               // 批次序号 -> 待回填记录数组 [{node, orig, segs}]
  var nextSeq = 0;               // 批次序号发号器
  var claimed = null;            // WeakSet：已提交/已替换的节点，避免重复收集
  var observer = null;
  var observerTimer = 0;

  // 一轮翻译的进度：pending = 还没回填的批次数；runApplied = 本轮真正替换的节点数
  var pending = 0;
  var runApplied = 0;
  var doneSent = true;
  // 待发送队列 + 发送节拍器：批次串行送出（每 450ms 一批），避免一次性打出几十个请求被 Google 限流
  // 待发送队列 + 发送节拍器：允许 3 批同时在途（批与批间隔 60ms），比一批一等的串行快一个量级
  var sendQueue = [];
  var sendTimer = 0;
  var SEND_GAP = 30;
  var MAX_INFLIGHT = 5;
  var inFlight = 0;
  // 视口内还没翻完的节点数：归零就可以先回 done（用户已看到中文），其余段落继续在后台翻完
  var inViewSet = new Set();
  var inViewLeft = 0;

  function isSkipParent(el) {
    return el && el.closest && el.closest(SKIP_SEL);
  }

  function isEditable(el) {
    return el && el.closest && el.closest('[contenteditable="true"],[contenteditable=""],input,textarea');
  }

  // 只翻“看起来是正文”的文本：至少含一个字母/假名/谚文，且不是纯数字符号
  var LETTER_RE = /[A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u3040-\u30FF\uAC00-\uD7AF]/;

  // 逐段判断是否需要翻译（替代原来只看 <html lang> 的整页判断）：
  // 中文（汉字为主）跳过，拉丁字母为主 / 日文假名 / 韩文 / 其它字母文字都翻。
  // 这样「中文界面的英文结果页」（Google 搜索、GitHub 等）不会再被整页判成无需翻译。
  function needsTranslate(t) {
    if (/[\u3040-\u30FF\uAC00-\uD7AF]/.test(t)) return true;   // 日文假名 / 韩文
    var han = (t.match(/[\u4e00-\u9fff]/g) || []).length;
    var latin = (t.match(/[A-Za-z]/g) || []).length;
    if (latin < 4) return false;                              // 太短 / 没有拉丁字母
    if (han * 3 >= latin) return false;                       // 汉字为主 → 已经是中文
    if (!/\s/.test(t) && t.length > 24) return false;         // 纯链接 / 长串标识符
    if (/^\s*(https?:\/\/|www\.)/i.test(t)) return false;     // 网址
    return true;
  }

  function acceptable(node) {
    var p = node.parentElement;
    if (!p) return false;
    if (isSkipParent(p) || isEditable(p)) return false;
    var t = node.nodeValue || "";
    if (t.trim().length < 2) return false;
    if (!LETTER_RE.test(t)) return false;
    return needsTranslate(t);
  }

  function splitSegs(t) {
    if (t.length <= SEG_MAX) return [t];
    var sentences = t.match(/[^.!?。！？]+[.!?。！？]*\s*/g) || [t];
    var parts = [], cur = "";
    sentences.forEach(function (s) {
      if (cur && (cur + " " + s).length > SEG_MAX) { parts.push(cur); cur = s; }
      else cur = cur ? cur + " " + s : s;
    });
    if (cur) parts.push(cur);
    return parts.length ? parts : [t];
  }

  // 收集 root 下可作为翻译目标的文本节点（跳过已处理的）；pred 可替换过滤条件（用于诊断/统计）
  function collectNodes(root, pred) {
    pred = pred || acceptable;
    var out = [];
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        if (claimed && claimed.has(n)) return NodeFilter.FILTER_REJECT;
        return pred(n) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    while (walker.nextNode()) {
      if (out.length >= 1200) break;
      out.push(walker.currentNode);
    }
    return out;
  }

  // 只做「结构上能不能翻」的粗筛（不含语言判断），用来统计候选量
  function structuralOk(n) {
    var p = n.parentElement;
    if (!p) return false;
    if (isSkipParent(p) || isEditable(p)) return false;
    var t = n.nodeValue || "";
    if (t.trim().length < 2) return false;
    return LETTER_RE.test(t);
  }

  function markNode(n) { if (claimed) claimed.add(n); }

  // 是否在「当前视口 + 上下各半屏」范围内：只翻看得见的内容，滚动到哪翻到哪（和 Edge 行为一致）
  function isVisible(n) {
    var p = n.parentElement;
    if (!p || !p.getBoundingClientRect) return true;
    var r = p.getBoundingClientRect();
    if (!r) return true;
    if (r.width === 0 && r.height === 0) return false;
    var vh = window.innerHeight || 800;
    return r.bottom > -vh * 0.5 && r.top < vh * 1.5;
  }

  function collectVisible() {
    if (!document.body) return [];
    return collectNodes(document.body).filter(isVisible);
  }

  // 把节点放进待发送队列（真正的分批发送交给 pump）
  function enqueue(nodes) {
    if (!claimed) claimed = new WeakSet();
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (claimed.has(n)) continue;
      sendQueue.push(n);
    }
    pump();
  }

  // 节拍发送：最多 3 批同时在途，每发一批隔 SEND_GAP 再发下一批，回填后再补位
  function pump() {
    if (!active) return;
    if (sendTimer) return;
    if (!sendQueue.length) { maybeDone(); return; }
    if (inFlight >= MAX_INFLIGHT) return;   // 窗口满了，等回填来腾位
    var group = [], texts = [], chars = 0;
    while (sendQueue.length && group.length < BATCH_ITEMS) {
      var n = sendQueue.shift();
      if (!n || (claimed && claimed.has(n))) continue;
      var raw = n.nodeValue || "";
      if (chars + raw.length > BATCH_CHARS && group.length > 0) { sendQueue.unshift(n); break; }
      var segs = splitSegs(raw.trim());
      group.push({ node: n, orig: raw, segs: segs });
      segs.forEach(function (s) { texts.push(s); });
      markNode(n);
      chars += raw.length;
    }
    if (!group.length) { maybeDone(); return; }
    var seq = nextSeq++;
    queues[seq] = group;
    pending++;
    inFlight++;
    try { port.postMessage({ cmd: "more", texts: texts, seq: seq }); }
    catch (e) { pending--; inFlight--; }
    sendTimer = setTimeout(function () { sendTimer = 0; pump(); }, SEND_GAP);
  }

  // 所有批次回填完毕（且这一轮还没发过回执）时，把“真正替换了多少节点”报给原生。
  // 视口内先翻完就先回 done（用户已经看到中文），屏幕外的段落继续在后台翻，不再等滚动。
  function maybeDone() {
    if (doneSent) return;
    if (inViewLeft > 0) return;
    if (runApplied === 0 && (pending > 0 || sendQueue.length > 0 || sendTimer)) return;
    doneSent = true;
    try { port.postMessage({ cmd: "done", applied: runApplied }); } catch (e) { }
  }

  // 启动一次整页翻译：先回 collect（原生据此把标签标成“正在翻译”），随后按节拍分批 more
  function start() {
    // 页面还在解析（body 里还没有内容）时不能判「无需翻译」，否则用户点早了会看到"该页面无需翻译"的假结论。
    // 只看 body 是否有内容：不能看 readyState，Google 这类页面会因为常驻资源长期停在 loading。
    if (!document.body || document.body.childElementCount === 0) {
      return {
        cmd: "collect", total: -1, notReady: true,
        top: window === window.top, kids: document.body ? document.body.childElementCount : -1
      };
    }
    // 不再用 <html lang> 一刀切：中文页面里的英文标题/摘要（Google 搜索结果、GitHub 等）
    // 也要翻，是否要翻逐段由 needsTranslate 判定；真的全中文时 total 会是 0。
    active = true;
    pending = 0;
    runApplied = 0;
    doneSent = false;
    sendQueue = [];
    inFlight = 0;
    if (sendTimer) { clearTimeout(sendTimer); sendTimer = 0; }
    if (!claimed) claimed = new WeakSet();
    // 整页一次性收全：视口内的排在队首（先让当前屏变成中文），屏幕外的排在后面后台继续翻，
    // 这样不用等用户滚动，停留多久都能把整页翻完。
    var cand = collectNodes(document.body, structuralOk);   // 结构上可翻的候选（不含语言判断）
    var nodes = cand.filter(function (n) { return needsTranslate(n.nodeValue || ""); });
    var langNow = (document.documentElement.getAttribute("lang") || "").toLowerCase();
    try { port.postMessage({ cmd: "diag", lang: langNow, cand: cand.length, pick: nodes.length }); } catch (e) { }
    var inView = [], rest = [];
    nodes.forEach(function (n) { (isVisible(n) ? inView : rest).push(n); });
    inViewSet = new Set(inView);
    inViewLeft = inView.length;
    attachObserver();
    attachScroll();
    if (!nodes.length) {
      doneSent = true;
      return { cmd: "collect", total: 0 };
    }
    // 中文页面里的零星英文（logo、版权行、品牌词）不值得翻：中文页 + 待翻段太少 → 当作无需翻译，
    // 免得出「翻译完成但页面没变化」的错觉；中英混排的页面（英文结果、英文正文）段数多，照常翻。
    var lang = (document.documentElement.getAttribute("lang") || "").toLowerCase();
    if (lang.indexOf("zh") === 0 && nodes.length < 5) {
      doneSent = true;
      active = false;
      return { cmd: "collect", total: 0 };
    }
    sendQueue = inView.concat(rest);
    // 下一轮事件循环再发批次，保证 collect 先到达原生
    setTimeout(function () { if (active) pump(); }, 0);
    return { cmd: "collect", total: nodes.length };
  }

  // 节点失效（SPA 重新渲染导致原节点脱离文档）时，按原文本在文档里找回同名文本节点
  function revive(it) {
    if (it.node && it.node.parentNode && document.body && document.body.contains(it.node)) return it.node;
    var found = null;
    try {
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
        acceptNode: function (n) {
          if (!acceptable(n)) return NodeFilter.FILTER_REJECT;
          return (n.nodeValue || "") === it.orig ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
        }
      });
      found = walker.nextNode();
    } catch (e) { found = null; }
    if (found) { it.node = found; markNode(found); }
    return found;
  }

  // 回填译文：按批次序号取出对应记录，把每个节点的译文段拼起来替换原文
  function apply(texts, seq) {
    // 已经「还原」过就不再回填：避免还原后迟到的批次又把页面翻成中文
    if (!active) { delete queues[seq]; return; }
    var batch = queues[seq];
    if (!batch) return;
    delete queues[seq];
    var idx = 0;
    var count = 0;
    batch.forEach(function (it) {
      var joined = [];
      var ok = true;
      for (var k = 0; k < it.segs.length; k++) {
        var v = texts[idx++];
        if (typeof v !== "string" || !v) { ok = false; break; }
        joined.push(v);
      }
      if (!ok) { decInView(it.node); return; }
      var node = revive(it);
      if (!node) { decInView(it.node); return; }
      try {
        node.nodeValue = joined.join(" ");
        // 记下译文文本，还原时用于兜底找回（节点被页面重建过也能翻回去）
        applied.push({ node: node, orig: it.orig, trans: node.nodeValue });
        count++;
        decInView(it.node);
      } catch (e) { decInView(it.node); }
    });
    runApplied += count;
    if (pending > 0) pending--;
    if (inFlight > 0) inFlight--;
    maybeDone();
    pump();   // 腾出窗口位，立刻补发下一批
  }

  // 视口内的节点处理完（无论成功失败）就从待翻集合里去掉，保证 inViewLeft 一定会归零
  function decInView(node) {
    if (!node || !inViewSet.size) return;
    if (inViewSet.has(node)) { inViewSet.delete(node); inViewLeft--; }
  }

  function restore() {
    // 空白差异（换行/多空格）不影响匹配：译文本就是逐段 join 出来的
    function norm(s) { return (s || "").replace(/\s+/g, " ").trim(); }
    applied.forEach(function (it) {
      try { if (it.node && it.node.nodeValue !== it.orig) it.node.nodeValue = it.orig; } catch (e) { }
    });
    // 兜底：页面重渲染换掉了文本节点时，用「译文 -> 原文」映射把还留着译文的节点翻回去
    try {
      if (document.body) {
        var byTrans = {};
        applied.forEach(function (it) { if (it && it.trans) byTrans[norm(it.trans)] = it.orig; });
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
        var nodes = [];
        while (walker.nextNode()) nodes.push(walker.currentNode);
        nodes.forEach(function (n) {
          var k = norm(n.nodeValue);
          if (k && Object.prototype.hasOwnProperty.call(byTrans, k)) n.nodeValue = byTrans[k];
        });
      }
    } catch (e) { }
    applied = [];
    queues = {};
    active = false;
    claimed = new WeakSet();
    pending = 0;
    runApplied = 0;
    doneSent = true;
    sendQueue = [];
    inFlight = 0;
    inViewSet = new Set();
    inViewLeft = 0;
    if (sendTimer) { clearTimeout(sendTimer); sendTimer = 0; }
    detachObserver();
    detachScroll();
  }

  // 增量补译：页面新插入节点或折叠内容展开时，自动收集并送出
  function attachObserver() {
    if (observer || !document.body) return;
    observer = new MutationObserver(function (records) {
      var fresh = [];
      records.forEach(function (r) {
        if (r.type !== "childList") return;
        for (var i = 0; i < r.addedNodes.length; i++) {
          var nd = r.addedNodes[i];
          if (nd.nodeType === 1) fresh = fresh.concat(collectNodes(nd));
          else if (nd.nodeType === 3 && acceptable(nd) && !(claimed && claimed.has(nd))) fresh.push(nd);
        }
      });
      if (!fresh.length) return;
      clearTimeout(observerTimer);
      observerTimer = setTimeout(function () { if (active) enqueue(fresh); }, 500);
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  function detachObserver() {
    if (observer) { observer.disconnect(); observer = null; }
    if (observerTimer) { clearTimeout(observerTimer); observerTimer = 0; }
  }

  // 滚动补译：长页面滚到哪里就翻到哪里（滚动停止 700ms 后收集当前视口内还没翻的文本）
  var scrollBound = false;
  var scrollTimer = 0;
  function onScroll() {
    if (!active) return;
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(function () {
      if (!active) return;
      enqueue(collectVisible());
    }, 700);
  }
  function attachScroll() {
    if (scrollBound) return;
    scrollBound = true;
    try { window.addEventListener("scroll", onScroll, { passive: true }); } catch (e) { scrollBound = false; }
  }
  function detachScroll() {
    if (!scrollBound) return;
    scrollBound = false;
    if (scrollTimer) { clearTimeout(scrollTimer); scrollTimer = 0; }
    try { window.removeEventListener("scroll", onScroll); } catch (e) { }
  }

  port.onMessage.addListener(function (msg) {
    try {
      var cmd = msg && msg.cmd;
      if (cmd === "collect") port.postMessage(start());
      else if (cmd === "apply" || cmd === "applyMore") apply(msg.texts || [], msg.seq);
      else if (cmd === "restore") { restore(); port.postMessage({ cmd: "restore" }); }
    } catch (e) { }
  });
})();
