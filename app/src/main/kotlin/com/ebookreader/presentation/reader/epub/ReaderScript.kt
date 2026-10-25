package com.ebookreader.presentation.reader.epub

/**
 * The reader's half of every chapter page, run once the document has loaded. It talks to
 * the app through the `Android` bridge and exposes `window.__reader` for the app to call.
 *
 * Bridge, page to app:
 *  - `tap(zone)` (-1 left third, 0 centre, 1 right third) returns true when the tap
 *    should turn the page; otherwise the app has used it to show or hide its controls.
 *  - `edge(dir)` a page turn ran off the end (1) or the start (-1) of the chapter.
 *  - `swipe(dir)` a horizontal fling, when swiping is on: 1 forwards, -1 back.
 *  - `scrollProgress(f)` how far down the chapter, 0..1.
 *
 * App to page: `configure`, `setStyle`, `goTo`, `goToAnchor`, `tagAlignment`.
 *
 * Directions are in reading order, so in a right-to-left book the left third goes forwards.
 */
internal val ReaderScript = """
(function () {
  if (window.__reader) return;
  var R = window.__reader = {
    swipe: false, smooth: true, lang: null,
    restoring: false, restoreRun: 0, lastReported: -1
  };

  function scroller() { return document.scrollingElement || document.documentElement; }
  function maxScroll() { return Math.max(scroller().scrollHeight - window.innerHeight, 0); }
  function fraction() { var m = maxScroll(); return m > 0 ? Math.min(window.scrollY / m, 1) : 0; }
  function rtl() { return !!document.body && getComputedStyle(document.body).direction === 'rtl'; }
  function selecting() { var s = window.getSelection(); return !!s && !s.isCollapsed; }

  function report() {
    // While a position is being restored the document is still growing as images and
    // fonts load; reporting those positions would overwrite the saved one.
    if (R.restoring) return;
    var f = fraction();
    // Throttled: a raw scroll stream re-renders the app's chrome dozens of times a second.
    if (Math.abs(f - R.lastReported) < 0.002) return;
    R.lastReported = f;
    Android.scrollProgress(f);
  }
  window.addEventListener('scroll', report, { passive: true });

  R.configure = function (config) {
    for (var key in config) R[key] = config[key];
    // Hyphenation needs a language. XHTML often declares only xml:lang, which an HTML
    // document ignores, so it is copied over, or the book's own language is used.
    var html = document.documentElement;
    if (!html.getAttribute('lang')) {
      var lang = html.getAttribute('xml:lang') || R.lang;
      if (lang) html.setAttribute('lang', lang);
    }
  };

  // Paragraphs the book sets flush or justified take the reader's alignment. Centred and
  // right-aligned ones (titles, verse, signatures) keep the book's.
  // Read every alignment first and tag afterwards: tagging as it goes would dirty the
  // style and force a recalculation for each block read after it.
  R.tagAlignment = function () {
    if (!document.body) return;
    var blocks = document.body.querySelectorAll('p, li, dd, dt, blockquote, div');
    var flush = [];
    for (var i = 0; i < blocks.length; i++) {
      var a = getComputedStyle(blocks[i]).textAlign;
      if (a === 'start' || a === 'left' || a === 'justify' || a === 'auto' || a === '-webkit-auto') {
        flush.push(blocks[i]);
      }
    }
    for (var j = 0; j < flush.length; j++) flush[j].classList.add('$READER_ALIGN_CLASS');
  };

  // A one-character range at the first line of text on screen: the thing to keep in
  // place while the text reflows around it.
  function anchor() {
    if (!document.caretRangeFromPoint) return null;
    for (var dy = 4; dy < 240; dy += 12) {
      var r = document.caretRangeFromPoint(window.innerWidth / 2, dy);
      if (r && r.startContainer && r.startContainer.nodeType === 3) {
        var len = r.startContainer.length;
        if (len === 0) continue;
        var start = Math.min(r.startOffset, len - 1);
        r.setStart(r.startContainer, start);
        r.setEnd(r.startContainer, start + 1);
        return r;
      }
    }
    return null;
  }
  function topOf(range) {
    var rects = range.getClientRects();
    return rects.length ? rects[0].top : null;
  }

  R.setStyle = function (css) {
    var el = document.getElementById('$READER_STYLE_ID');
    if (!el) return;
    var range = anchor();
    var before = range ? topOf(range) : null;
    var f = fraction();
    el.textContent = css;
    function keep() {
      var after = range ? topOf(range) : null;
      if (before !== null && after !== null) window.scrollBy(0, after - before);
      else window.scrollTo(0, maxScroll() * f);
    }
    keep();
    // A new face lays the text out again once it has loaded.
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(keep);
  };

  // onPageFinished fires before images and web fonts have finished loading, so the
  // document is still short and a single scrollTo lands earlier than intended. Keep
  // re-applying the fraction until the height stops changing.
  // Each call supersedes the last: an older loop still waiting on images must not land
  // on its own, stale target after the newer one.
  R.goTo = function (target) {
    var run = ++R.restoreRun;
    R.restoring = true;
    var lastHeight = -1, stable = 0, ticks = 0;
    function finish() {
      if (run !== R.restoreRun || !R.restoring) return;
      R.restoring = false;
      R.lastReported = -1;
      report();
    }
    function apply() {
      if (run !== R.restoreRun || !R.restoring) return;
      var height = scroller().scrollHeight;
      window.scrollTo(0, maxScroll() * target);
      if (height === lastHeight) { stable++; } else { lastHeight = height; stable = 0; }
      ticks++;
      if (stable < 3 && ticks < 40) { setTimeout(apply, 100); } else { finish(); }
    }
    // Any deliberate touch wins over the restore.
    document.addEventListener('touchstart', finish, { passive: true, once: true });
    apply();
  };

  R.goToAnchor = function (id) {
    var el = document.getElementById(id) || document.getElementsByName(id)[0];
    if (!el) return;
    el.scrollIntoView(true);
    window.scrollBy(0, -8);
  };

  // A page is a screenful less a line and a half, so the last line read stays in view
  // at the top. At either end of the chapter the turn goes to the neighbouring chapter.
  R.turn = function (dir) {
    var max = maxScroll(), y = window.scrollY;
    if (dir > 0 && y >= max - 2) { Android.edge(1); return; }
    if (dir < 0 && y <= 2) { Android.edge(-1); return; }
    var line = document.body ? parseFloat(getComputedStyle(document.body).lineHeight) : 0;
    if (!(line > 0)) line = 24;
    var view = window.innerHeight;
    var step = Math.max(view - line * 1.5, view / 2);
    var to = Math.min(Math.max(y + dir * step, 0), max);
    window.scrollTo({ top: to, behavior: R.smooth ? 'smooth' : 'auto' });
  };

  // ── Text ranges ──────────────────────────────────────────────────────────
  // Highlights, search hits and read-aloud all address the chapter by character offsets
  // into its text nodes taken in document order (scripts and styles left out). Marks
  // split text nodes but never change the text, so offsets stay valid while marks come
  // and go.

  function textNodes() {
    var list = [], total = 0;
    if (!document.body) return { list: list, length: 0 };
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        for (var p = node.parentNode; p; p = p.parentNode) {
          if (p.nodeName === 'SCRIPT' || p.nodeName === 'STYLE') return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    while (walker.nextNode()) {
      var n = walker.currentNode;
      list.push({ node: n, start: total });
      total += n.nodeValue.length;
    }
    return { list: list, length: total };
  }

  function chapterText(nodes) {
    var parts = [];
    for (var i = 0; i < nodes.list.length; i++) parts.push(nodes.list[i].node.nodeValue);
    return parts.join('');
  }

  // Wraps [start, end) in elements made by make(), one per text node it crosses.
  function wrap(start, end, make) {
    var nodes = textNodes(), first = null;
    for (var i = 0; i < nodes.list.length; i++) {
      var item = nodes.list[i], node = item.node, len = node.nodeValue.length;
      var from = Math.max(start, item.start), to = Math.min(end, item.start + len);
      if (from >= to) continue;
      var piece = node;
      if (from > item.start) piece = piece.splitText(from - item.start);
      if (to < item.start + len) piece.splitText(to - from);
      var el = make();
      piece.parentNode.insertBefore(el, piece);
      el.appendChild(piece);
      if (!first) first = el;
    }
    return first;
  }

  function unwrap(selector) {
    var marks = document.querySelectorAll(selector);
    var parents = [];
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i], parent = m.parentNode;
      while (m.firstChild) parent.insertBefore(m.firstChild, m);
      parent.removeChild(m);
      parents.push(parent);
    }
    for (var j = 0; j < parents.length; j++) parents[j].normalize();
  }

  function mark(className, attrs) {
    return function () {
      var el = document.createElement('mark');
      el.className = className;
      for (var k in attrs) el.setAttribute(k, attrs[k]);
      return el;
    };
  }

  function bringIntoView(el) {
    if (!el) return;
    var r = el.getBoundingClientRect();
    if (r.top < 0 || r.bottom > window.innerHeight) {
      window.scrollBy(0, r.top - window.innerHeight / 3);
    }
  }

  // Lower-case, accents off, keeping a map back to the original offsets: "Élève" is
  // found by "eleve", the way the library's search index matched it.
  function fold(text) {
    var out = [], map = [];
    for (var i = 0; i < text.length; i++) {
      var f = text[i].normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
      for (var k = 0; k < f.length; k++) { out.push(f[k]); map.push(i); }
    }
    return { text: out.join(''), map: map };
  }

  // The chapter's text as the app's own search counts it: whitespace collapsed (see
  // speechIndex) and folded, each character mapped back to the page's text. Matches are
  // counted the same way too, without overlaps, so "the 3rd match" is the same match.
  var findIndex = null, ttsIndex = null;
  function searchIndex() {
    if (findIndex) return findIndex;
    var flat = speechIndex(), f = fold(flat.flat);
    return (findIndex = { text: f.text, map: f.map.map(function (i) { return flat.map[i]; }) });
  }

  // The occurrence-th match of query, marked and scrolled to. Falls back to the first
  // match, then to the first word, since the index matches words rather than phrases.
  R.findText = function (query, occurrence) {
    unwrap('mark.__rs');
    var folded = searchIndex();
    function find(q, n) {
      var idx = -1, from = 0;
      for (var k = 0; k <= n; k++) {
        idx = folded.text.indexOf(q, from);
        if (idx < 0) return -1;
        from = idx + q.length;
      }
      return idx;
    }
    var q = fold(query.trim()).text.replace(/\s+/g, ' ');
    var at = find(q, occurrence || 0);
    if (at < 0) at = find(q, 0);
    if (at < 0) { q = q.split(' ')[0]; at = q ? find(q, 0) : -1; }
    if (at < 0) return false;
    var start = folded.map[at], end = folded.map[at + q.length - 1] + 1;
    var el = wrap(start, end, mark('__rs', {}));
    bringIntoView(el);
    // The marker is a pointer, not a highlight: it goes once the reader moves on.
    setTimeout(function () { unwrap('mark.__rs'); }, 6000);
    return true;
  };

  // Highlights: [{id, start, end, color}], repainted whole whenever they change.
  R.paintHighlights = function (list) {
    unwrap('mark.__hl');
    for (var i = 0; i < list.length; i++) {
      var h = list[i];
      wrap(h.start, h.end, mark('__hl __hl' + h.color, { 'data-hl': h.id }));
    }
  };

  R.goToOffset = function (offset) {
    var nodes = textNodes();
    for (var i = nodes.list.length - 1; i >= 0; i--) {
      if (nodes.list[i].start <= offset) {
        var range = document.createRange();
        var node = nodes.list[i].node;
        range.setStart(node, Math.min(offset - nodes.list[i].start, node.nodeValue.length));
        var rect = range.getBoundingClientRect();
        if (!rect || (rect.top === 0 && rect.height === 0)) rect = node.parentNode.getBoundingClientRect();
        window.scrollBy(0, rect.top - window.innerHeight / 4);
        return;
      }
    }
  };

  function offsetOf(container, offset, nodes) {
    if (container.nodeType === 3) {
      for (var i = 0; i < nodes.list.length; i++) {
        if (nodes.list[i].node === container) return nodes.list[i].start + offset;
      }
      return -1;
    }
    // An element boundary: the offset of the first text at or after it.
    var target = container.childNodes[offset] || null;
    for (var j = 0; j < nodes.list.length; j++) {
      var n = nodes.list[j].node;
      if (target ? (target === n || target.contains(n) || (target.compareDocumentPosition(n) & 4))
                 : container.contains(n) === false && (container.compareDocumentPosition(n) & 4)) {
        return nodes.list[j].start;
      }
    }
    return nodes.length;
  }

  // The current selection as offsets and text, or null; then the selection is cleared.
  R.selection = function () {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) return null;
    var range = sel.getRangeAt(0), nodes = textNodes();
    var start = offsetOf(range.startContainer, range.startOffset, nodes);
    var end = offsetOf(range.endContainer, range.endOffset, nodes);
    if (start < 0 || end <= start) return null;
    var text = chapterText(nodes).substring(start, end);
    return { start: start, end: end, text: text };
  };

  R.clearSelection = function () {
    var sel = window.getSelection();
    if (sel) sel.removeAllRanges();
  };

  // ── Read aloud ───────────────────────────────────────────────────────────

  // Where the reader's eye is: the offset of the first text on screen.
  R.firstVisibleOffset = function () {
    var nodes = textNodes();
    for (var i = 0; i < nodes.list.length; i++) {
      var n = nodes.list[i].node;
      if (!n.nodeValue.trim()) continue;
      var range = document.createRange();
      range.selectNodeContents(n);
      var rects = range.getClientRects();
      for (var k = 0; k < rects.length; k++) {
        if (rects[k].bottom > 4) return nodes.list[i].start;
      }
    }
    return 0;
  };

  // The text from the top of the page on, for read-aloud to find its starting sentence.
  R.firstVisibleText = function () {
    return chapterText(textNodes()).substr(R.firstVisibleOffset(), 200);
  };

  // Marks the sentence being read aloud. Read-aloud splits the chapter's text itself,
  // with whitespace collapsed, so the sentence is found by its words: in the page's text
  // with whitespace collapsed the same way, after the sentence marked before it.
  var ttsFrom = 0;
  // The chapter's text with whitespace collapsed, and where each character of it is in the
  // page's text. Built once: marks split text nodes but never change the text.
  function speechIndex() {
    if (ttsIndex) return ttsIndex;
    var raw = chapterText(textNodes()), hay = [], map = [], gap = false;
    for (var i = 0; i < raw.length; i++) {
      var c = raw[i];
      if (c === '\u00ad') continue;
      if (/\s/.test(c)) { gap = hay.length > 0; continue; }
      if (gap) { hay.push(' '); map.push(i); gap = false; }
      hay.push(c); map.push(i);
    }
    return (ttsIndex = { flat: hay.join(''), map: map });
  }
  R.speakFind = function (text) {
    unwrap('mark.__tts');
    var needle = text.replace(/\s+/g, ' ').trim();
    if (!needle) return;
    var index = speechIndex();
    var at = index.flat.indexOf(needle, ttsFrom);
    if (at < 0) at = index.flat.indexOf(needle);
    if (at < 0) return;
    ttsFrom = at + needle.length;
    bringIntoView(wrap(index.map[at], index.map[at + needle.length - 1] + 1, mark('__tts', {})));
  };

  R.speakClear = function () { unwrap('mark.__tts'); ttsFrom = 0; };

  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || selecting()) return;
    var t = e.target;
    var hl = t && t.closest && t.closest('mark.__hl');
    if (hl) { Android.highlightTapped(parseInt(hl.getAttribute('data-hl'), 10)); return; }
    if (t && t.closest && t.closest('a[href], button, input, select, textarea, summary, label, audio, video')) return;
    var x = e.clientX / window.innerWidth;
    var zone = x < 1 / 3 ? -1 : (x > 2 / 3 ? 1 : 0);
    if (Android.tap(zone)) R.turn(rtl() ? -zone : zone);
  });

  var touch = null;
  document.addEventListener('touchstart', function (e) {
    touch = e.touches.length === 1
      ? { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() }
      : null;
  }, { passive: true });
  document.addEventListener('touchend', function (e) {
    var s = touch; touch = null;
    if (!s || !R.swipe || e.changedTouches.length !== 1) return;
    var p = e.changedTouches[0], dx = p.clientX - s.x, dy = p.clientY - s.y;
    if (Date.now() - s.t > 700) return;
    if (Math.abs(dx) < Math.max(48, window.innerWidth * 0.12) || Math.abs(dx) < Math.abs(dy) * 2) return;
    // Zoomed in, a sideways drag is panning across the page.
    if (window.visualViewport && window.visualViewport.scale > 1.01) return;
    if (selecting()) return;
    var dir = dx < 0 ? 1 : -1;
    Android.swipe(rtl() ? -dir : dir);
  }, { passive: true });
})();
""".trimIndent()
