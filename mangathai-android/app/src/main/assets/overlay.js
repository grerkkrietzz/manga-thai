// MangaThai mobile: translate every manga image on the page, on the phone.
// Running this script again on the same page toggles the translations.
(() => {
  if (window.__mangathai) {
    window.__mangathai.toggle();
    return;
  }
  const native = window.MangaThaiNative;
  if (!native) throw new Error('ไม่พบตัวเชื่อมแอป ลองโหลดหน้านี้ใหม่ (เมนู ⋮ → โหลดหน้านี้ใหม่)');

  const MIN_SIDE = 300;          // natural pixels: ignore icons, avatars, ads
  const PARALLEL = 2;            // the phone works one page at a time; 2 keeps it busy

  const state = {
    visible: true,
    stopped: false,
    entries: new Map(),          // img element -> entry
    working: 0,
    note: '',
    prefetch: { total: 0, done: 0 },
    chapter: null,
  };

  // ---------- native bridge ----------
  let sequence = 0;
  const waiting = new Map();
  window.__mtDone = (id, ok, payload) => {
    const job = waiting.get(id);
    if (!job) return;
    waiting.delete(id);
    if (ok) {
      try { job.resolve(JSON.parse(payload)); } catch (e) { job.reject(e); }
    } else job.reject(new Error(payload));
  };
  window.__mtStatus = (text) => { state.note = text; report(); };
  function call(method, ...args) {
    return new Promise((resolve, reject) => {
      const id = 'mt' + (++sequence);
      waiting.set(id, { resolve, reject });
      try { native[method](id, ...args); } catch (e) { waiting.delete(id); reject(e); }
    });
  }

  // ---------- UI (shadow DOM keeps page styles out) ----------
  const host = document.createElement('mangathai-layer');
  host.style.cssText = 'all:initial;position:fixed;left:0;top:0;width:0;height:0;z-index:2147483646;pointer-events:none;';
  const root = host.attachShadow({ mode: 'open' });
  // Built with DOM calls (no innerHTML) and CSSOM styles, so sites with a strict
  // Content-Security-Policy or Trusted Types cannot block the overlay.
  const CSS = `
    .page{position:fixed;overflow:hidden;pointer-events:none;}
    .cover{position:absolute;border-radius:8px;}
    .text{position:absolute;display:flex;flex-direction:column;justify-content:center;align-items:stretch;text-align:center;
      box-sizing:border-box;padding:1px 2px;overflow:hidden;
      font-family:"Noto Sans Thai","Sarabun","SamsungOneUIThai","sans-serif";font-weight:600;
      line-height:1.18;word-break:normal;overflow-wrap:anywhere;white-space:normal;}
    .text span{display:block;width:100%;}
    .pill{position:fixed;left:12px;right:104px;bottom:14px;pointer-events:auto;display:flex;gap:6px;align-items:center;
      background:rgba(22,28,39,.94);color:#e9edf6;border:1px solid #3b4c65;border-radius:18px;padding:6px 6px 6px 12px;
      font:13px/1.3 "Noto Sans Thai",sans-serif;box-shadow:0 4px 18px rgba(0,0,0,.35);}
    .pill .msg{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
    .pill b{color:#5dd6be;font-weight:700;}
    .pill button{all:unset;flex:none;background:#293448;border:1px solid #3b4c65;border-radius:14px;padding:6px 10px;}
  `;
  try {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(CSS);
    root.adoptedStyleSheets = [sheet];
  } catch (_) {
    const style = document.createElement('style');
    style.textContent = CSS;
    root.appendChild(style);
  }
  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text) node.textContent = text;
    return node;
  }
  const pages = el('div', 'pages');
  const pill = el('div', 'pill');
  const msg = el('span', 'msg');
  msg.append(el('b', '', 'แปล'), document.createTextNode(' '));
  const detail = el('span', 'detail', 'กำลังเริ่ม…');
  msg.appendChild(detail);
  const toggleButton = el('button', 'toggle', 'ซ่อน');
  const stopButton = el('button', 'stop', 'หยุด');
  const closeButton = el('button', 'close', '✕');
  pill.append(msg, toggleButton, stopButton, closeButton);
  root.append(pages, pill);
  (document.documentElement || document.body).appendChild(host);
  toggleButton.addEventListener('click', toggle);
  stopButton.addEventListener('click', stop);
  closeButton.addEventListener('click', destroy);

  function toggle() {
    state.visible = !state.visible;
    pages.style.display = state.visible ? '' : 'none';
    toggleButton.textContent = state.visible ? 'ซ่อน' : 'แสดง';
    report();
  }

  function stop() {
    state.stopped = !state.stopped;
    stopButton.textContent = state.stopped ? 'ต่อ' : 'หยุด';
    if (!state.stopped) pump();
    report();
  }

  function destroy() {
    state.stopped = true;
    state.chapter = '__closed__';
    clearInterval(scanTimer);
    host.remove();
    delete window.__mangathai;
  }

  /** Forget results (after rules change or cache clear) and translate again. */
  function reset() {
    for (const entry of state.entries.values()) entry.layer.remove();
    state.entries.clear();
    state.chapter = null;
    state.prefetch = { total: 0, done: 0 };
    scan();
  }
  window.__mangathai = { toggle, reset };

  function report() {
    let done = 0, failed = 0, total = 0, waitingCount = 0;
    for (const entry of state.entries.values()) {
      total++;
      if (entry.status === 'done') done++;
      else if (entry.status === 'error') failed++;
      else waitingCount++;
    }
    let text = total ? `${done}/${total} หน้า` : 'กำลังหาภาพมังงะ…';
    if (failed) text += ` • ไม่สำเร็จ ${failed}`;
    const p = state.prefetch;
    if (p.total) text += ` • ทั้งตอน ${p.done}/${p.total}`;
    if (waitingCount && !state.stopped && state.note) text += ` • ${state.note}`;
    if (state.stopped) text += ' • หยุดอยู่';
    if (!state.visible) text += ' • ซ่อน';
    detail.textContent = text;
  }

  function toBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    let text = '';
    for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return btoa(text);
  }

  async function translateImage(img, priority) {
    const src = img.currentSrc || img.src;
    try {
      // blob:, data:, same-origin and CORS-enabled images: send the exact bytes.
      const response = await fetch(src, { credentials: 'include' });
      if (response.ok) return await call('translate', toBase64(await response.arrayBuffer()), priority);
    } catch (_) { /* cross-origin without CORS: let the app download it */ }
    if (/^https?:/i.test(src)) return await call('translateUrl', src, priority, location.href);
    const canvas = document.createElement('canvas');
    canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
    canvas.getContext('2d').drawImage(img, 0, 0);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.95));
    if (!blob) throw new Error('อ่านภาพนี้ไม่ได้');
    return await call('translate', toBase64(await blob.arrayBuffer()), priority);
  }

  // ---------- finding pages ----------
  function scan() {
    prefetchMangaDex();
    for (const img of document.images) {
      const src = img.currentSrc || img.src;
      if (!src || !img.complete || img.naturalWidth < MIN_SIDE || img.naturalHeight < MIN_SIDE) continue;
      const rect = img.getBoundingClientRect();
      if (rect.width < 120 || rect.height < 120) continue;
      const entry = state.entries.get(img);
      if (entry && entry.src === src) continue;
      if (entry) entry.layer.remove();   // same <img>, new page (single-page readers)
      const layer = document.createElement('div');
      layer.className = 'page';
      pages.appendChild(layer);
      state.entries.set(img, { img, src, status: 'queued', result: null, layer, scale: 0 });
    }
    for (const [img, entry] of state.entries) {
      if (!img.isConnected) { entry.layer.remove(); state.entries.delete(img); }
    }
    pump();
    report();
  }

  function distance(img) {
    const rect = img.getBoundingClientRect();
    const view = innerHeight;
    if (rect.bottom > 0 && rect.top < view && rect.width > 0) return 0;   // on screen
    return rect.top >= view ? rect.top - view : -rect.bottom + view * 0.5;
  }

  function pump() {
    if (state.stopped) return;
    while (state.working < PARALLEL) {
      const next = [...state.entries.values()]
        .filter((e) => e.status === 'queued')
        .sort((a, b) => distance(a.img) - distance(b.img))[0];
      if (!next) return;
      translate(next);
    }
  }

  async function translate(entry) {
    state.working++;
    entry.status = 'working';
    const src = entry.src;
    try {
      const result = await translateImage(entry.img, distance(entry.img) === 0 ? 0 : 1);
      if (entry.src !== src) return;               // page changed meanwhile
      entry.result = result;
      entry.status = 'done';
      draw(entry);
    } catch (error) {
      entry.status = 'error';
      entry.error = error.message;
      state.note = error.message;
      console.warn('MangaThai:', error.message);
    } finally {
      state.working--;
      report();
      pump();
    }
  }

  // ---------- drawing ----------
  function percent(value, total) { return (value / total * 100).toFixed(3) + '%'; }

  function draw(entry) {
    const { result, layer } = entry;
    layer.textContent = '';
    for (const bubble of result.bubbles) {
      const [cx, cy, cw, ch] = bubble.cover;
      const cover = document.createElement('div');
      cover.className = 'cover';
      const pad = 2;
      Object.assign(cover.style, {
        left: percent(cx - pad, result.width), top: percent(cy - pad, result.height),
        width: percent(cw + pad * 2, result.width), height: percent(ch + pad * 2, result.height),
        background: bubble.background, boxShadow: `0 0 4px 3px ${bubble.background}`,
      });
      const [bx, by, bw, bh] = bubble.box;
      const text = document.createElement('div');
      text.className = 'text';
      Object.assign(text.style, {
        left: percent(bx, result.width), top: percent(by, result.height),
        width: percent(bw, result.width), height: percent(bh, result.height), color: bubble.color,
      });
      const span = document.createElement('span');
      span.textContent = bubble.thai;
      text.appendChild(span);
      text.dataset.font = bubble.font;
      layer.append(cover, text);
    }
    entry.scale = 0;   // force fitting on the next frame
  }

  function fit(entry, scale) {
    for (const text of entry.layer.querySelectorAll('.text')) {
      const span = text.firstChild;
      // Start a little larger than the English letters, shrink until it fits.
      let size = Math.max(7, Math.min(40, Number(text.dataset.font) * scale * 1.05));
      text.style.fontSize = size + 'px';
      let guard = 60;
      // The span spans the full width and wraps anywhere, so only height can overflow;
      // compare its real height (not scroll size) with the box.
      while (guard-- > 0 && size > 6 &&
             (span.getBoundingClientRect().height > text.clientHeight + 1 || span.scrollWidth > text.clientWidth + 1)) {
        size -= size > 14 ? 1 : 0.5;
        text.style.fontSize = size + 'px';
      }
    }
  }

  // Follow every page as the reader scrolls, pinch-zooms or rotates.
  function frame() {
    if (!host.isConnected) return;
    if (state.visible) {
      for (const entry of state.entries.values()) {
        if (entry.status !== 'done') { entry.layer.style.display = 'none'; continue; }
        const rect = entry.img.getBoundingClientRect();
        const shown = rect.width > 0 && rect.bottom > 0 && rect.top < innerHeight &&
          rect.right > 0 && rect.left < innerWidth && getComputedStyle(entry.img).visibility !== 'hidden';
        entry.layer.style.display = shown ? '' : 'none';
        if (!shown) continue;
        const s = entry.layer.style;
        s.left = rect.left + 'px'; s.top = rect.top + 'px';
        s.width = rect.width + 'px'; s.height = rect.height + 'px';
        const scale = rect.width / entry.result.width;
        if (Math.abs(scale - entry.scale) > entry.scale * 0.03) {
          entry.scale = scale;
          fit(entry, scale);
        }
      }
    }
    requestAnimationFrame(frame);
  }

  // ---------- whole chapter (MangaDex) ----------
  async function prefetchMangaDex() {
    const match = location.hostname.endsWith('mangadex.org') &&
      location.pathname.match(/\/chapter\/([0-9a-f-]{36})(?:\/(\d+))?/i);
    if (!match || state.chapter === match[1] || state.chapter === '__closed__') return;
    state.chapter = match[1];            // a new chapter replaces the old prefetch
    const chapter = match[1];
    const p = state.prefetch = { total: 0, done: 0 };
    try {
      const response = await fetch('https://api.mangadex.org/at-home/server/' + chapter);
      if (!response.ok) throw new Error('MangaDex ' + response.status);
      const json = await response.json();
      // The phone reader shows "data" (original) or "data-saver" depending on the
      // user's setting; prefetch originals, the visible page is always re-checked.
      const urls = json.chapter.data.map((name) => `${json.baseUrl}/data/${json.chapter.hash}/${name}`);
      p.total = urls.length;
      const start = Math.max(0, Math.min(urls.length - 1, (Number(match[2]) || 1) - 1));
      const order = urls.slice(start).concat(urls.slice(0, start));
      for (const url of order) {
        while (state.stopped && host.isConnected && state.chapter === chapter) await new Promise((r) => setTimeout(r, 1000));
        if (!host.isConnected || state.chapter !== chapter) return;
        try { await call('translateUrl', url, 2, location.href); } catch (_) { /* counted, retried when viewed */ }
        p.done++;
        report();
      }
    } catch (error) {
      console.warn('MangaThai: เตรียมทั้งตอนไม่ได้', error.message);
    }
  }

  const scanTimer = setInterval(scan, 800);
  scan();
  requestAnimationFrame(frame);
})();
