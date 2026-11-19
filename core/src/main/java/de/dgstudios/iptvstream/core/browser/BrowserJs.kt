package de.dgstudios.iptvstream.core.browser

/**
 * Seitenskripte des eingebauten Browsers.
 * [AUTO_FULLSCREEN] stammt aus dem DG Browser (1.5.1): Ein angetipptes Video geht sofort ins Vollbild,
 * notfalls per CSS über die ganze Seite. Die App fragt über DGBridge.autoFs(), ob das gewünscht ist.
 */
object BrowserJs {
    /** Werbeflächen ausblenden (nur Hauptseite) – CSS kommt von DGBridge.css(host). */
    const val HIDE = """
(function(){
  if (window.__dgHide || window.top !== window) return;
  window.__dgHide = 1;
  try {
    var b = window.DGBridge; if (!b || !b.css) return;
    var css = b.css(location.hostname || '');
    if (!css) return;
    var st = document.createElement('style');
    st.setAttribute('data-dg', 'adblock');
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  } catch (e) {}
})();
"""

    /**
     * In jedem Frame: auf „__dgPause“ alle Videos/Töne anhalten und an eingebettete Frames weitergeben
     * (Browser schließen soll nichts im Hintergrund weiterspielen lassen).
     */
    const val PAUSE_LISTENER = """
(function(){
  if (window.__dgPauseL) return;
  window.__dgPauseL = 1;
  window.__dgPauseAll = function(){
    try { document.querySelectorAll('video,audio').forEach(function(m){ try { m.pause(); } catch (e) {} }); } catch (e) {}
    try { for (var i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage('__dgPause', '*'); } catch (e) {} } } catch (e) {}
  };
  window.addEventListener('message', function(ev){ if (ev && ev.data === '__dgPause') window.__dgPauseAll(); }, true);
})();
"""

    /** Hauptseite: alles anhalten (auch wenn das Skript nicht früh eingehängt werden konnte). */
    const val PAUSE_ALL = PAUSE_LISTENER + "window.__dgPauseAll && window.__dgPauseAll();"

    /** Fokussiertes Element (auch in gleich-originigen iframes). */
    private const val ACTIVE = """
function __dgActive(){
  var d = document, a = d.activeElement;
  try { while (a && a.tagName === 'IFRAME' && a.contentDocument) { d = a.contentDocument; a = d.activeElement; } } catch (e) {}
  return a;
}
function __dgEditable(a){
  if (!a) return false;
  if (a.isContentEditable) return true;
  var t = (a.tagName || '').toLowerCase();
  if (t === 'textarea') return !a.readOnly && !a.disabled;
  if (t !== 'input') return false;
  var ty = (a.type || 'text').toLowerCase();
  return ['text','search','email','url','tel','password','number',''].indexOf(ty) >= 0 && !a.readOnly && !a.disabled;
}
"""

    /** „edit“, wenn nach dem Klick ein Eingabefeld den Fokus hat, sonst „none“. */
    const val ACTIVE_INPUT = "(function(){$ACTIVE return __dgEditable(__dgActive()) ? 'edit' : 'none';})()"

    /** Aktueller Text im fokussierten Feld. */
    const val ACTIVE_VALUE = "(function(){$ACTIVE var a = __dgActive(); if (!__dgEditable(a)) return ''; return a.isContentEditable ? (a.innerText || '') : (a.value || '');})()"

    /** Text ins fokussierte Feld schreiben (wie getippt), optional absenden. */
    fun setActiveValue(text: String, submit: Boolean): String {
        val q = org.json.JSONObject.quote(text)
        return """(function(){$ACTIVE
  var a = __dgActive(); if (!__dgEditable(a)) return;
  if (a.isContentEditable) { a.focus(); document.execCommand('selectAll', false, null); document.execCommand('insertText', false, $q); }
  else {
    var proto = a.tagName.toLowerCase() === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var set = Object.getOwnPropertyDescriptor(proto, 'value').set; set.call(a, $q);
    a.dispatchEvent(new Event('input', {bubbles: true}));
    a.dispatchEvent(new Event('change', {bubbles: true}));
  }
  if ($submit) {
    var o = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true};
    var go = a.dispatchEvent(new KeyboardEvent('keydown', o));
    a.dispatchEvent(new KeyboardEvent('keypress', o));
    a.dispatchEvent(new KeyboardEvent('keyup', o));
    if (go && a.form) { try { a.form.requestSubmit ? a.form.requestSubmit() : a.form.submit(); } catch (e) {} }
  }
})();"""
    }

    const val AUTO_FULLSCREEN = """
(function(){
  if (window.__dgFs) return;
  window.__dgFs = 1;
  var add = EventTarget.prototype.addEventListener;
  var isTop = window.top === window;
  var lastTap = 0;
  var pseudo = null;      // Element, das gerade per CSS den ganzen Bildschirm füllt
  var saved = [];         // [Element, alter style-Text]
  function bridge() { return window.DGBridge; }
  function now() { return Date.now(); }
  function fsEl() { return document.fullscreenElement || document.webkitFullscreenElement || null; }
  function srcOf(v) {
    var s = v.currentSrc || v.src || '';
    if (!s) { try { var so = v.querySelector('source'); if (so) s = so.src || ''; } catch (e) {} }
    return String(s);
  }
  function want(v) {
    try { var b = bridge(); return !!(b && b.autoFs && b.autoFs(srcOf(v))); } catch (e) { return false; }
  }
  // Ein echter Tipp: im eigenen Rahmen (pointerup/touchend/click) oder laut App (auch Tipps in anderen Rahmen).
  function tapped(ms) {
    if (now() - lastTap < ms) return true;
    try { var b = bridge(); if (b && b.recentTap && b.recentTap(ms)) return true; } catch (e) {}
    return false;
  }
  function videoIn(el) {
    if (!el) return null;
    if (el.tagName === 'VIDEO') return el;
    try { return el.querySelector('video'); } catch (e) { return null; }
  }
  function report() {
    try {
      var v = videoIn(fsEl()) || videoIn(pseudo);
      var b = bridge();
      if (v && b && b.fsVideo && v.videoWidth > 0 && v.videoHeight > 0) b.fsVideo(v.videoWidth, v.videoHeight);
    } catch (e) {}
  }
  // YouTube und Co. haben einen eigenen Player-Rahmen mit Steuerung: den lieber groß machen als das nackte Video.
  function target(v) {
    try {
      var p = v.closest && v.closest('.html5-video-player,#movie_player,.video-js,.jwplayer,.plyr,.vjs-tech,.shaka-video-container,[data-dg-player]');
      if (p && p !== document.body) return p;
    } catch (e) {}
    return v;
  }
  // ---- Ersatz-Vollbild ohne Fullscreen-API: Element per CSS über die ganze Seite legen ----
  function setStyle(el, css) {
    saved.push([el, el.getAttribute('style')]);
    el.setAttribute('style', (el.getAttribute('style') || '') + ';' + css);
  }
  var FILL = 'position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;' +
    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
    'z-index:2147483647!important;background:#000!important;transform:none!important;border:0!important';
  function pseudoOn(el, fromChild, local) {
    if (pseudo) return;
    pseudo = el;
    setStyle(el, FILL);
    var v = videoIn(el);
    if (v && v !== el) setStyle(v, FILL + ';z-index:auto!important;object-fit:contain!important');
    else if (v) setStyle(v, 'object-fit:contain!important');
    // Vorfahren: transform/filter würden position:fixed einsperren, z-index würde es unter fremde Leisten legen.
    // Alles neben dem Player (Kopfzeile, Listen) wird unsichtbar, ohne das Layout der Seite zu ändern.
    var a = el.parentElement, child = el, n = 0;
    while (a && n++ < 40) {
      try {
        var sib = a.children;
        for (var i = 0; i < sib.length; i++) {
          var c = sib[i];
          if (c !== child && c.tagName !== 'SCRIPT' && c.tagName !== 'STYLE') setStyle(c, 'visibility:hidden!important');
        }
        if (a === document.documentElement) break;
        var cs = getComputedStyle(a);
        if (cs.transform !== 'none' || cs.filter !== 'none' || cs.contain !== 'none' || cs.willChange.indexOf('transform') >= 0 || cs.zIndex !== 'auto')
          setStyle(a, 'transform:none!important;filter:none!important;contain:none!important;will-change:auto!important;z-index:2147483647!important');
      } catch (e) {}
      child = a;
      a = a.parentElement;
    }
    setStyle(document.documentElement, 'overflow:hidden!important');
    if (el.tagName === 'IFRAME' || el.tagName === 'FRAME') {
      try { el.contentWindow.postMessage({ __dgFill: 1 }, '*'); } catch (e) {}
    }
    if (local) return;
    if (isTop) {
      try { var b = bridge(); if (b && b.pageFs) b.pageFs(true); } catch (e) {}
      report();
    } else {
      try { parent.postMessage({ __dgPseudo: 1 }, '*'); } catch (e) {}
    }
  }
  function pseudoOff() {
    if (!pseudo) return false;
    for (var i = saved.length - 1; i >= 0; i--) {
      var s = saved[i];
      if (s[1] === null) s[0].removeAttribute('style'); else s[0].setAttribute('style', s[1]);
    }
    saved = []; pseudo = null;
    return true;
  }
  // Von der App (Zurück-Taste) oder von oben: Ersatz-Vollbild überall beenden, echtes Vollbild auch.
  window.__dgFsExit = function() {
    var had = pseudoOff();
    try { if (fsEl()) { (document.exitFullscreen || document.webkitExitFullscreen).call(document); had = true; } } catch (e) {}
    var fr = document.querySelectorAll('iframe,frame');
    for (var i = 0; i < fr.length; i++) { try { fr[i].contentWindow.postMessage({ __dgFsExit: 1 }, '*'); } catch (e) {} }
    if (isTop) { try { var b = bridge(); if (b && b.pageFs) b.pageFs(false); } catch (e) {} }
    return had ? 'exit' : 'none';
  };
  function up() {
    if (isTop) return false;
    try { parent.postMessage({ __dgFs: 1 }, '*'); } catch (e) {}
    return true;
  }
  // Echtes Vollbild versuchen. Geht das nicht (kein Tipp mehr gültig, iframe ohne Erlaubnis), Ersatz-Vollbild.
  function enter(el, fromChild) {
    if (fsEl() || pseudo) return;
    var f = el.requestFullscreen || el.webkitRequestFullscreen;
    var done = false;
    var fallback = function() {
      if (done || fsEl() || pseudo) return;
      done = true;
      if (!fromChild && up()) return;      // erst die Seite darüber fragen
      pseudoOn(el, fromChild);
    };
    if (f) {
      try {
        var p = f.call(el);
        if (p && p.then) p.then(report, fallback);
        // Manche WebViews antworten nie: dann nach kurzer Zeit selbst übernehmen.
        setTimeout(function(){ if (!fsEl()) fallback(); }, 1200);
        return;
      } catch (e) {}
    }
    fallback();
  }
  window.__dgFsEnter = function() {
    var list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++) { if (!list[i].paused) { enter(target(list[i]), false); return 'ok'; } }
    return 'none';
  };
  // Nur click (kommt nach Wischen nicht) und Tasten zählen als Tipp.
  ['click', 'keydown'].forEach(function(t) {
    add.call(window, t, function(e) { if (e.isTrusted !== false) lastTap = now(); }, { capture: true, passive: true });
  });
  function consider(v, tapWindow) {
    if (!v || v.tagName !== 'VIDEO') return;
    if (fsEl() || pseudo) return;
    var active = navigator.userActivation ? navigator.userActivation.isActive : false;
    // Nur nach einem Tipp (Seiten laden oft erst noch), nie bei stummen Vorschauen.
    if (!active && !tapped(tapWindow)) return;
    if (v.muted || v.volume === 0) return;
    var r = v.getBoundingClientRect();
    if (r.width < 120 || r.height < 68) return;
    if (!want(v)) return;
    enter(target(v), false);
  }
  add.call(document, 'play', function(e) {
    var v = e.target;
    if (v && v.tagName === 'VIDEO') v.__dgMuted = v.muted || v.volume === 0;
    consider(v, 15000);
  }, true);
  // YouTube & Co. spielen erst stumm. Der Tipp schaltet nur den Ton an, ein play-Ereignis kommt nicht.
  // Dann gilt: stumm -> laut nach einem Tipp = der Nutzer will dieses Video sehen.
  add.call(document, 'volumechange', function(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    var was = v.__dgMuted === true;
    v.__dgMuted = v.muted || v.volume === 0;
    if (!was || v.__dgMuted || v.paused) return;
    consider(v, 5000);
  }, true);
  add.call(document, 'ended', function(e) {
    if (e.target && e.target.tagName === 'VIDEO' && (pseudo || fsEl()) && !e.target.loop) {
      if (isTop) window.__dgFsExit();
      else { try { window.top.postMessage({ __dgFsExitAll: 1 }, '*'); } catch (x) {} }
    }
  }, true);
  add.call(document, 'fullscreenchange', report, true);
  add.call(document, 'webkitfullscreenchange', report, true);
  add.call(document, 'loadedmetadata', function(e) { if ((fsEl() || pseudo) && (videoIn(fsEl()) === e.target || videoIn(pseudo) === e.target)) report(); }, true);
  add.call(window, 'message', function(e) {
    var d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.__dgKey) {
      if (!isTop && e.source === window.parent) window.__dgKey(String(d.__dgKey));
      return;
    }
    if (d.__dgFill) {
      if (!isTop && e.source === window.parent && !pseudo) {
        var vs = document.querySelectorAll('video'), pv = null;
        for (var k = 0; k < vs.length; k++) { if (!vs[k].paused) { pv = vs[k]; break; } }
        if (!pv && vs.length) pv = vs[0];
        if (pv) pseudoOn(target(pv), true, true);
      }
      return;
    }
    if (d.__dgFsExit) { if (!isTop && e.source === window.parent) window.__dgFsExit(); return; }
    if (d.__dgFsExitAll) { if (isTop) window.__dgFsExit(); return; }
    if (d.__dgFs !== 1 && d.__dgPseudo !== 1) return;
    var frames = document.querySelectorAll('iframe,frame');
    for (var i = 0; i < frames.length; i++) {
      if (frames[i].contentWindow === e.source) {
        if (d.__dgFs === 1) enter(frames[i], true);
        else if (!pseudo && !fsEl()) pseudoOn(frames[i], true);
        return;
      }
    }
  }, true);
  // Tasten der Fernbedienung im Vollbild: an das Video weitergeben, auch in iframes.
  window.__dgKey = function(k) {
    var el = fsEl() || pseudo;
    var v = videoIn(el);
    if (!v && el && (el.tagName === 'IFRAME' || el.tagName === 'FRAME')) {
      try { el.contentWindow.postMessage({ __dgKey: k }, '*'); } catch (e) {}
      return 'frame';
    }
    if (!v) {
      var list = document.querySelectorAll('video');
      for (var i = 0; i < list.length; i++) { if (!list[i].paused) { v = list[i]; break; } }
      if (!v && list.length === 1) v = list[0];
    }
    if (!v) {
      var fr = document.querySelectorAll('iframe');
      for (var j = 0; j < fr.length; j++) { try { fr[j].contentWindow.postMessage({ __dgKey: k }, '*'); } catch (e) {} }
      return 'none';
    }
    try {
      if (k === 'toggle') { if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function(){}); } else v.pause(); }
      else if (k === 'play') { var q = v.play(); if (q && q.catch) q.catch(function(){}); }
      else if (k === 'pause') v.pause();
      else if (k === 'fwd') v.currentTime = Math.min((v.duration || 1e9), v.currentTime + 10);
      else if (k === 'back') v.currentTime = Math.max(0, v.currentTime - 10);
    } catch (e) {}
    return 'ok';
  };
})();
"""

    /** Scrollt das scrollbare Element unter dem Zeiger (oder die Seite). x/y in CSS-Pixeln. */
    fun scrollAt(x: Float, y: Float, dy: Int): String = """
(function(x,y,dy){
  var e = document.elementFromPoint(x,y);
  while (e && e !== document.body && e !== document.documentElement) {
    try {
      var s = getComputedStyle(e);
      if ((s.overflowY === 'auto' || s.overflowY === 'scroll') && e.scrollHeight > e.clientHeight + 2) {
        var before = e.scrollTop; e.scrollBy(0, dy); if (e.scrollTop !== before) return 'el';
      }
    } catch (x) {}
    e = e.parentElement;
  }
  window.scrollBy(0, dy);
  return 'page';
})($x,$y,$dy);
"""
}
