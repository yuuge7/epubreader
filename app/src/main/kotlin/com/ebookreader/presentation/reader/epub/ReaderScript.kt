package com.ebookreader.presentation.reader.epub

/**
 * The reader's half of every chapter page, run once the document has loaded. It talks to
 * the app through the `Android` bridge and exposes `window.__reader` for the app to call.
 *
 * Bridge, page to app:
 *  - `tap(zone)` (-1 left third, 0 centre, 1 right third) returns true when the tap
 *    should turn the page; otherwise the app has used it to show or hide its controls.
 *  - `edge(dir)` a page turn ran off the end (1) or the start (-1) of the chapter.
 *  - `swipe(dir)` a horizontal fling in scrolling mode, when swiping is on: 1 forwards.
 *  - `scrollProgress(f, span)` how far into the chapter, 0..1, and how much of it one
 *    screen shows.
 *
 * App to page: `configure`, `setStyle`, `goTo`, `goToAnchor`, `goToOffset`, `turn`,
 * `setAutoScroll`, `setFocus`, `place`, `tagAlignment`, `semantics`.
 *
 * Two layouts, told apart by the stylesheet (--reader-paged): one long scroll per chapter,
 * or screen-wide columns turned through sideways. Positions are a fraction either way.
 *
 * Directions are in reading order, so in a right-to-left book the left third goes forwards.
 */
internal val ReaderScript = """
(function () {
  if (window.__reader) return;
  var R = window.__reader = {
    swipe: false, smooth: true, lang: null, a11y: false, focus: 'off',
    restoring: false, restoreRun: 0, lastReported: -1, lastSpan: -1, paged: false,
    autoSpeed: 0, autoRun: 0, touching: false, rulerY: 0.35
  };

  function scroller() { return document.scrollingElement || document.documentElement; }
  // The screen's size in CSS pixels. Not innerWidth/innerHeight: with content wider than
  // the screen (paged mode) the WebView reports the zoomed-out layout viewport there. Nor
  // the root's clientHeight: chapters have no doctype, and in quirks mode that is the
  // whole document's height.
  function viewW() { var v = window.visualViewport; return v ? v.width * v.scale : window.innerWidth; }
  function viewH() { var v = window.visualViewport; return v ? v.height * v.scale : window.innerHeight; }
  function rtl() { return getComputedStyle(document.documentElement).direction === 'rtl' ||
    (!!document.body && getComputedStyle(document.body).direction === 'rtl'); }
  function selecting() { var s = window.getSelection(); return !!s && !s.isCollapsed; }

  // ── Layout ───────────────────────────────────────────────────────────────

  function refreshMode() {
    R.paged = getComputedStyle(document.documentElement).getPropertyValue('--reader-paged').trim() === '1';
  }
  refreshMode();

  // One page is exactly the width of the body, which paged mode sets to the viewport's.
  function pageWidth() {
    var w = document.body ? document.body.getBoundingClientRect().width : 0;
    return w > 0 ? w : viewW();
  }
  function sign() { return rtl() ? -1 : 1; }
  function pageCount() {
    var w = pageWidth();
    return Math.max(1, Math.ceil(scroller().scrollWidth / w - 0.02));
  }
  function pageIndex() { return Math.max(0, Math.round(sign() * window.scrollX / pageWidth())); }
  function scrollToPage(i, smooth) {
    var n = pageCount();
    i = Math.min(Math.max(i, 0), n - 1);
    window.scrollTo({ left: sign() * i * pageWidth(), top: 0, behavior: smooth && R.smooth ? 'smooth' : 'auto' });
  }
  // The page a box on screen is on, by its middle.
  function pageOfRect(rect) {
    var w = pageWidth(), x = (rect.left + rect.right) / 2 + window.scrollX;
    return sign() > 0 ? Math.floor(x / w) : Math.max(0, Math.ceil(-x / w - 0.000001));
  }

  function maxScroll() { return Math.max(scroller().scrollHeight - viewH(), 0); }
  function size() { return R.paged ? scroller().scrollWidth : scroller().scrollHeight; }

  function fraction() {
    if (R.paged) { var n = pageCount(); return n > 1 ? pageIndex() / (n - 1) : 0; }
    var m = maxScroll();
    return m > 0 ? Math.min(window.scrollY / m, 1) : 0;
  }
  // How much of the chapter one screen shows.
  function span() {
    if (R.paged) return 1 / pageCount();
    var h = scroller().scrollHeight;
    return h > 0 ? Math.min(viewH() / h, 1) : 1;
  }

  function report() {
    // While a position is being restored the document is still growing as images and
    // fonts load; reporting those positions would overwrite the saved one.
    if (R.restoring) return;
    var f = fraction(), s = span();
    // Throttled: a raw scroll stream re-renders the app's chrome dozens of times a second.
    if (Math.abs(f - R.lastReported) < 0.002 && Math.abs(s - R.lastSpan) < 0.002) return;
    R.lastReported = f;
    R.lastSpan = s;
    Android.scrollProgress(f, s);
    updateFocus();
  }
  window.addEventListener('scroll', report, { passive: true });
  window.addEventListener('resize', function () {
    // A rotation: keep the same text in view, in whatever layout the new size gets.
    var range = anchor();
    setTimeout(function () { keepAt(range, Math.max(R.lastReported, 0)); }, 60);
  });

  R.configure = function (config) {
    for (var key in config) R[key] = config[key];
    // Hyphenation needs a language. XHTML often declares only xml:lang, which an HTML
    // document ignores, so it is copied over, or the book's own language is used.
    var html = document.documentElement;
    if (!html.getAttribute('lang')) {
      var lang = html.getAttribute('xml:lang') || R.lang;
      if (lang) html.setAttribute('lang', lang);
    }
    html.classList.toggle('__still', !R.smooth);
    R.setFocus(R.focus);
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

  // Screen readers navigate by headings. Many books set theirs as styled paragraphs
  // ("p class=chapter-title"), which a screen reader cannot tell from body text; those
  // are given the heading role. Pictures with no description are marked as decoration,
  // or their file names are read out.
  R.semantics = function () {
    if (!document.body) return;
    var headingClass = /(^|[\s_-])(chapter|title|heading|head|subtitle|subhead|h[1-6])($|[\s_-]|\d)/i;
    var candidates = document.body.querySelectorAll('p[class], div[class]');
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (!headingClass.test(el.className)) continue;
      if (el.closest('h1,h2,h3,h4,h5,h6,[role=heading]')) continue;
      if (el.querySelector('p,div,h1,h2,h3,h4,h5,h6')) continue;
      var text = (el.textContent || '').trim();
      if (!text || text.length > 120) continue;
      el.setAttribute('role', 'heading');
      el.setAttribute('aria-level', /sub/i.test(el.className) ? '3' : '2');
    }
    var images = document.body.querySelectorAll('img:not([alt])');
    for (var k = 0; k < images.length; k++) images[k].setAttribute('alt', '');
  };

  // A one-character range at the first line of text on screen: the thing to keep in
  // place while the text reflows around it.
  function anchor() {
    if (!document.caretRangeFromPoint) return null;
    var x = R.paged ? 24 : viewW() / 2;
    for (var dy = 4; dy < 240; dy += 12) {
      var r = document.caretRangeFromPoint(x, dy + (R.paged ? 20 : 0));
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
  function rectOf(range) {
    var rects = range.getClientRects();
    return rects.length ? rects[0] : null;
  }

  // Brings the text of range back to where the eye was, after the layout changed.
  function keepAt(range, f, before) {
    refreshMode();
    var rect = range ? rectOf(range) : null;
    if (R.paged) {
      if (rect) scrollToPage(pageOfRect(rect), false);
      else scrollToPage(Math.round(f * (pageCount() - 1)), false);
    } else if (rect && before !== undefined && before !== null) {
      window.scrollBy(0, rect.top - before);
    } else if (rect) {
      window.scrollBy(0, rect.top - 8);
    } else {
      window.scrollTo(0, maxScroll() * f);
    }
    R.lastReported = -1;
    report();
  }

  R.setStyle = function (css) {
    var el = document.getElementById('$READER_STYLE_ID');
    if (!el) return;
    var range = anchor();
    var wasPaged = R.paged;
    var r0 = range ? rectOf(range) : null;
    var before = r0 && !wasPaged ? r0.top : null;
    var f = fraction();
    el.textContent = css;
    refreshMode();
    // Leaving paged mode: the sideways offset would otherwise stay.
    if (wasPaged && !R.paged) window.scrollTo(0, window.scrollY);
    function keep() { keepAt(range, f, R.paged === wasPaged ? before : null); }
    keep();
    // A new face lays the text out again once it has loaded.
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(keep);
    restartAuto();
  };

  // onPageFinished fires before images and web fonts have finished loading, so the
  // document is still short and a single scroll lands earlier than intended. Keep
  // re-applying the fraction until the size stops changing.
  // Each call supersedes the last: an older loop still waiting on images must not land
  // on its own, stale target after the newer one.
  R.goTo = function (target) {
    var run = ++R.restoreRun;
    R.restoring = true;
    var lastSize = -1, stable = 0, ticks = 0;
    function finish() {
      if (run !== R.restoreRun || !R.restoring) return;
      R.restoring = false;
      R.lastReported = -1;
      report();
    }
    function apply() {
      if (run !== R.restoreRun || !R.restoring) return;
      refreshMode();
      var current = size();
      if (R.paged) scrollToPage(Math.round(target * (pageCount() - 1)), false);
      else window.scrollTo(0, maxScroll() * target);
      if (current === lastSize) { stable++; } else { lastSize = current; stable = 0; }
      ticks++;
      if (stable < 3 && ticks < 40) { setTimeout(apply, 100); } else { finish(); }
    }
    // Any deliberate touch wins over the restore.
    document.addEventListener('touchstart', finish, { passive: true, once: true });
    apply();
  };

  function showElement(el, atTop) {
    if (!el) return;
    var rect = el.getBoundingClientRect();
    if (R.paged) { scrollToPage(pageOfRect(rect), false); return; }
    window.scrollBy(0, atTop ? rect.top - 8 : rect.top - viewH() / 4);
  }

  R.goToAnchor = function (id) {
    R.restoreRun++; R.restoring = false;
    var el = document.getElementById(id) || document.getElementsByName(id)[0];
    if (!el) return;
    showElement(el, true);
    R.lastReported = -1;
    report();
  };

  // A page is a screenful less a line and a half, so the last line read stays in view
  // at the top; in paged mode, the next column. At either end of the chapter the turn
  // goes to the neighbouring chapter.
  R.turn = function (dir) {
    refreshMode();
    if (R.paged) {
      var i = pageIndex(), n = pageCount();
      if (dir > 0 && i >= n - 1) { Android.edge(1); return; }
      if (dir < 0 && i <= 0) { Android.edge(-1); return; }
      scrollToPage(i + dir, true);
      return;
    }
    var max = maxScroll(), y = window.scrollY;
    if (dir > 0 && y >= max - 2) { Android.edge(1); return; }
    if (dir < 0 && y <= 2) { Android.edge(-1); return; }
    var line = document.body ? parseFloat(getComputedStyle(document.body).lineHeight) : 0;
    if (!(line > 0)) line = 24;
    var view = viewH();
    var step = Math.max(view - line * 1.5, view / 2);
    var to = Math.min(Math.max(y + dir * step, 0), max);
    window.scrollTo({ top: to, behavior: R.smooth ? 'smooth' : 'auto' });
  };

  // ── Text ranges ──────────────────────────────────────────────────────────
  // Highlights, search hits, bookmarks and read-aloud all address the chapter by
  // character offsets into its text nodes taken in document order (scripts and styles
  // left out). Marks split text nodes but never change the text, so offsets stay valid
  // while marks come and go.

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

  function onScreen(r) {
    return r.bottom > 4 && r.top < viewH() && r.right > 0 && r.left < viewW();
  }

  function bringIntoView(el) {
    if (!el) return;
    var r = el.getBoundingClientRect();
    if (onScreen(r)) return;
    if (R.paged) { scrollToPage(pageOfRect(r), false); return; }
    window.scrollBy(0, r.top - viewH() / 3);
  }

  // Lower-case, accents off, keeping a map back to the original offsets: "Élève" is
  // found by "eleve", the way the library's search index matched it.
  var Accents = new RegExp('[' + String.fromCharCode(0x300) + '-' + String.fromCharCode(0x36f) + ']', 'g');
  var SoftHyphen = String.fromCharCode(0xad);
  function fold(text) {
    var out = [], map = [];
    for (var i = 0; i < text.length; i++) {
      var f = text[i].normalize('NFD').replace(Accents, '').toLowerCase();
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

  // Shows the text at offset: a little way down the screen, or at its top for a bookmark.
  R.goToOffset = function (offset, atTop) {
    R.restoreRun++; R.restoring = false;
    var nodes = textNodes();
    for (var i = nodes.list.length - 1; i >= 0; i--) {
      if (nodes.list[i].start <= offset) {
        var range = document.createRange();
        var node = nodes.list[i].node;
        var at = Math.min(offset - nodes.list[i].start, node.nodeValue.length);
        range.setStart(node, at);
        range.setEnd(node, Math.min(at + 1, node.nodeValue.length));
        var rect = range.getBoundingClientRect();
        if (!rect || (rect.top === 0 && rect.height === 0)) rect = node.parentNode.getBoundingClientRect();
        if (R.paged) scrollToPage(pageOfRect(rect), false);
        else window.scrollBy(0, atTop ? rect.top - 8 : rect.top - viewH() / 4);
        R.lastReported = -1;
        report();
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
        if (onScreen(rects[k])) {
          // The first character of the node that is on screen, not the node's start.
          if (rects.length > 1 && document.caretRangeFromPoint) {
            var c = document.caretRangeFromPoint(rects[k].left + 1, rects[k].top + rects[k].height / 2);
            if (c && c.startContainer === n) return nodes.list[i].start + c.startOffset;
          }
          return nodes.list[i].start;
        }
      }
    }
    return 0;
  };

  // The text from the top of the page on, for read-aloud to find its starting sentence.
  R.firstVisibleText = function () {
    return chapterText(textNodes()).substr(R.firstVisibleOffset(), 200);
  };

  // This place, for a bookmark: where it is and the first words there.
  R.place = function () {
    var offset = R.firstVisibleOffset();
    var text = chapterText(textNodes()).substr(offset, 160).replace(/\s+/g, ' ').trim();
    return { offset: offset, text: text, fraction: fraction() };
  };

  // ── Read aloud ───────────────────────────────────────────────────────────

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
      if (c === SoftHyphen) continue;
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

  // ── Auto-scroll ──────────────────────────────────────────────────────────
  // Scrolling: the page moves up at pxPerSecond. Paged: a page turns each time that
  // speed would have scrolled a screenful. A finger on the page holds it; at the end of
  // the chapter the app is told, and opens the next one already scrolling.

  R.setAutoScroll = function (pxPerSecond) {
    R.autoSpeed = pxPerSecond > 0 ? pxPerSecond : 0;
    restartAuto();
  };

  function restartAuto() {
    var run = ++R.autoRun;
    if (!(R.autoSpeed > 0)) return;
    refreshMode();
    if (R.paged) {
      var wait = function () {
        if (run !== R.autoRun) return;
        var seconds = Math.max(viewH() / R.autoSpeed, 2);
        setTimeout(function () {
          if (run !== R.autoRun) return;
          if (!R.touching && !selecting()) R.turn(1);
          if (R.paged) wait();
        }, seconds * 1000);
      };
      wait();
      return;
    }
    var last = null, pos = window.scrollY, set = pos;
    function step(now) {
      if (run !== R.autoRun) return;
      if (last !== null && !R.touching && !selecting() && !R.restoring) {
        // Somebody scrolled by hand: carry on from there.
        if (Math.abs(window.scrollY - set) > 3) pos = window.scrollY;
        pos += R.autoSpeed * Math.min(now - last, 100) / 1000;
        if (pos >= maxScroll()) {
          window.scrollTo(0, maxScroll());
          R.autoRun++;
          Android.edge(1);
          return;
        }
        window.scrollTo(0, pos);
        set = window.scrollY;
      } else {
        pos = window.scrollY; set = pos;
      }
      last = now;
      requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  // ── Reading focus ────────────────────────────────────────────────────────
  // A line across the screen at R.rulerY of its height: a band to read along (ruler),
  // or the paragraphs off it dimmed (dim). A tab at the right edge drags it.

  var ruler = null, grip = null, focused = [];
  R.setFocus = function (mode) {
    R.focus = mode || 'off';
    var html = document.documentElement;
    html.classList.toggle('__dim', R.focus === 'dim');
    if (R.focus === 'off') {
      if (ruler) { ruler.remove(); grip.remove(); ruler = grip = null; }
      clearFocus();
      return;
    }
    if (!ruler) {
      ruler = document.createElement('div');
      grip = document.createElement('div');
      ruler.setAttribute('aria-hidden', 'true');
      grip.setAttribute('aria-hidden', 'true');
      ruler.style.cssText = 'position:fixed;left:0;right:0;pointer-events:none;z-index:2147483646;' +
        'background:var(--reader-ruler);border-top:1px solid var(--reader-accent);border-bottom:1px solid var(--reader-accent);';
      grip.style.cssText = 'position:fixed;right:0;width:22px;height:44px;margin-top:-22px;z-index:2147483647;' +
        'border-radius:12px 0 0 12px;background:var(--reader-accent);opacity:.55;touch-action:none;';
      html.appendChild(ruler);
      html.appendChild(grip);
      grip.addEventListener('touchstart', function (e) { e.stopPropagation(); }, { passive: true });
      grip.addEventListener('touchmove', function (e) {
        e.preventDefault(); e.stopPropagation();
        R.rulerY = Math.min(Math.max(e.touches[0].clientY / viewH(), 0.08), 0.92);
        placeRuler();
      }, { passive: false });
      grip.addEventListener('click', function (e) { e.stopPropagation(); e.preventDefault(); });
    }
    placeRuler();
  };

  function lineHeight() {
    var line = document.body ? parseFloat(getComputedStyle(document.body).lineHeight) : 0;
    return line > 0 ? line : 28;
  }

  function placeRuler() {
    if (!ruler) return;
    var y = R.rulerY * viewH(), h = lineHeight() * 1.25;
    ruler.style.top = (y - h / 2) + 'px';
    ruler.style.height = h + 'px';
    ruler.style.display = R.focus === 'ruler' ? 'block' : 'none';
    grip.style.top = y + 'px';
    updateFocus();
  }

  function clearFocus() {
    for (var i = 0; i < focused.length; i++) focused[i].classList.remove('__focus');
    focused = [];
  }

  var focusQueued = false;
  function updateFocus() {
    if (R.focus !== 'dim' || focusQueued) return;
    focusQueued = true;
    requestAnimationFrame(function () {
      focusQueued = false;
      clearFocus();
      var y = R.rulerY * viewH();
      var columns = R.paged && document.body ? (parseInt(getComputedStyle(document.body).columnCount, 10) || 1) : 1;
      var w = viewW();
      for (var c = 0; c < columns; c++) {
        var x = (c + 0.5) * w / columns;
        var stack = document.elementsFromPoint(x, y);
        for (var k = 0; k < stack.length; k++) {
          var block = stack[k].closest && stack[k].closest('p,li,h1,h2,h3,h4,h5,h6,dd,dt,pre,figcaption,td,th');
          if (block && document.body.contains(block)) {
            block.classList.add('__focus');
            focused.push(block);
            break;
          }
        }
      }
    });
  }

  // ── Touch ────────────────────────────────────────────────────────────────

  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || selecting()) return;
    var t = e.target;
    var hl = t && t.closest && t.closest('mark.__hl');
    if (hl) { Android.highlightTapped(parseInt(hl.getAttribute('data-hl'), 10)); return; }
    if (t && t.closest && t.closest('a[href], button, input, select, textarea, summary, label, audio, video')) return;
    // With a screen reader, taps are its own: the app offers turning and controls as actions.
    if (R.a11y) return;
    var x = e.clientX / viewW();
    var zone = x < 1 / 3 ? -1 : (x > 2 / 3 ? 1 : 0);
    if (Android.tap(zone)) R.turn(rtl() ? -zone : zone);
  });

  var touch = null;
  document.addEventListener('touchstart', function (e) {
    R.touching = true;
    touch = e.touches.length === 1
      ? { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() }
      : null;
  }, { passive: true });
  document.addEventListener('touchcancel', function () { R.touching = false; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    R.touching = e.touches.length > 0;
    var s = touch; touch = null;
    if (!s || e.changedTouches.length !== 1) return;
    var p = e.changedTouches[0], dx = p.clientX - s.x, dy = p.clientY - s.y;
    if (Date.now() - s.t > 700) return;
    if (Math.abs(dx) < Math.max(48, viewW() * 0.12) || Math.abs(dx) < Math.abs(dy) * 2) return;
    // Zoomed in, a sideways drag is panning across the page.
    if (window.visualViewport && window.visualViewport.scale > 1.01) return;
    if (selecting()) return;
    var dir = dx < 0 ? 1 : -1;
    if (rtl()) dir = -dir;
    // Pages turn with a swipe; a scrolling chapter changes chapter, if the reader wants that.
    if (R.paged) R.turn(dir);
    else if (R.swipe) Android.swipe(dir);
  }, { passive: true });
})();
""".trimIndent()
