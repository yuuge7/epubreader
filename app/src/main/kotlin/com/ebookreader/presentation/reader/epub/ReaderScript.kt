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

  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || selecting()) return;
    var t = e.target;
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
