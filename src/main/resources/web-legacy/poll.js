/* HDSL-web legacy poller — ES5 only.
 *
 * IE11 has XMLHttpRequest but no fetch, no Promise and no ES6 syntax, so this
 * file stays in var/function land and is checked by `es-check es5` in
 * web/package.json. It long-polls the fragment endpoints the server renders
 * (/plain/f/...) and swaps the regions in place: the transcript updates by
 * itself without a page reload, and the send form is replaced only when its
 * markup actually changed, so what is being typed survives every swap.
 * Entering a transcript jumps to #bottom; a later update follows only when the
 * reader was already near the bottom, so reading history is never interrupted.
 *
 * The kick-out signal: a page that started elevated (data-elev="ok") and
 * receives a fragment with data-elev="no" has just lost its developer
 * elevation; the poller swaps that fragment in (the send form becomes the
 * elevation notice) and stops.
 */
(function () {
  var doc = document;
  var win = window;
  if (!win.XMLHttpRequest) {
    return;
  }
  function byId(id) {
    return doc.getElementById(id);
  }
  var seed = byId("frag-transcript") || byId("frag-sessions");
  if (!seed) {
    return;
  }
  var url = seed.getAttribute("data-poll-url");
  if (!url) {
    return;
  }
  var elevatedAtStart = seed.getAttribute("data-elev") === "ok";
  var stopped = false;
  var busy = false;
  var transcript = byId("frag-transcript") !== null;

  function scrollBottom() {
    var root = doc.documentElement;
    var body = doc.body;
    var height = Math.max(body.scrollHeight, root.scrollHeight,
      body.offsetHeight, root.offsetHeight);
    win.scrollTo(0, height);
  }

  function nearBottom() {
    var root = doc.documentElement;
    var body = doc.body;
    var top = win.pageYOffset || root.scrollTop || body.scrollTop || 0;
    var viewport = win.innerHeight || root.clientHeight || body.clientHeight || 0;
    var height = Math.max(body.scrollHeight, root.scrollHeight,
      body.offsetHeight, root.offsetHeight);
    return height - top - viewport < 120;
  }

  if (transcript) {
    /* Entering a conversation always lands on the composer, even when cached
     * images/Markdown have delayed the layout past the browser's #bottom jump. */
    scrollBottom();
    win.setTimeout(scrollBottom, 0);
    if (win.addEventListener) {
      win.addEventListener("load", scrollBottom, false);
    } else if (win.attachEvent) {
      win.attachEvent("onload", scrollBottom);
    }
  }

  function elementsOf(wrapper) {
    var out = [];
    var node = wrapper.firstChild;
    while (node) {
      if (node.nodeType === 1) {
        out.push(node);
      }
      node = node.nextSibling;
    }
    return out;
  }

  function applyFragment(text) {
    var wrapper = doc.createElement("div");
    wrapper.innerHTML = text;
    var parts = elementsOf(wrapper);
    var elev = null;
    var follow = transcript && nearBottom();
    var transcriptChanged = false;
    for (var i = 0; i < parts.length; i++) {
      var next = parts[i];
      if (!next.id) {
        continue;
      }
      if (elev === null && next.getAttribute("data-elev")) {
        elev = next.getAttribute("data-elev");
      }
      var target = byId(next.id);
      if (!target || !target.parentNode) {
        continue;
      }
      if (next.id === "frag-sendform" && target.innerHTML === next.innerHTML) {
        continue;
      }
      target.parentNode.replaceChild(next, target);
      if (next.id === "frag-transcript") {
        transcriptChanged = true;
      }
    }
    if (transcriptChanged && follow) {
      scrollBottom();
    }
    if (elev === "no" && elevatedAtStart) {
      stopped = true;
    }
  }

  function schedule(ms) {
    if (stopped) {
      return;
    }
    win.setTimeout(tick, ms);
  }

  function tick() {
    if (stopped || busy) {
      return;
    }
    var root = byId("frag-transcript") || byId("frag-sessions");
    if (!root) {
      return;
    }
    var rev = root.getAttribute("data-rev") || "";
    busy = true;
    var xhr = new XMLHttpRequest();
    xhr.open("GET", url + "?wait=1&rev=" + encodeURIComponent(rev), true);
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) {
        return;
      }
      busy = false;
      if (xhr.status === 200) {
        applyFragment(xhr.responseText);
        schedule(300);
      } else if (xhr.status === 304) {
        schedule(100);
      } else if (xhr.status === 401 || xhr.status === 403 || xhr.status === 404) {
        stopped = true;
      } else {
        schedule(3000);
      }
    };
    xhr.send(null);
  }

  schedule(500);
})();
