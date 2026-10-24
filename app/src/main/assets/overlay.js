(() => {
  let canvas = document.getElementById('comments');
  let renderer = null;
  let loadSerial = 0;
  let incoming = null;
  const cancelled = Symbol("cancelled");
  const yieldFrame = () => new Promise(resolve => requestAnimationFrame(resolve));
  let renderedKeys = new Set();
  let renderConfig = '';
  let epoch = null;
  let ids = new Map();
  let users = new Map();
  let anchor = { position: 0, received: 0, speed: 0, enabled: false };
  let lastReport = 0;
  let nextDraw = 0;
  let frameInterval = 1000 / 60;
  const report = (name, value) => {
    if (window.AndroidRenderer && typeof window.AndroidRenderer[name] === 'function') window.AndroidRenderer[name](value);
  };
  const playbackTime = () => anchor.position + (performance.now() - anchor.received) * anchor.speed;
  const clear = () => canvas.getContext('2d')?.clearRect(0, 0, canvas.width, canvas.height);
  const discardRenderer = async instance => {
    if (!instance || instance.destroyed) return;
    try {
      if (!Array.isArray(instance.comments) || !instance.timeline || !instance.collision) { instance.destroy(); return; }
      let deadline = performance.now() + 4;
      const comments = instance.comments;
      instance.comments = [];
      for (const comment of comments) {
        comment.destroy?.();
        if (performance.now() >= deadline) { await yieldFrame(); deadline = performance.now() + 4; }
      }
      for (const table of [instance.timeline, ...Object.values(instance.collision)]) {
        for (const key in table) {
          delete table[key];
          if (performance.now() >= deadline) { await yieldFrame(); deadline = performance.now() + 4; }
        }
      }
      instance.destroy();
    } catch (error) { report('error', String(error?.stack || error)); }
  };
  const commandsFor = (raw, body, options, owner = false) => {
    const commands = Array.isArray(raw.commands) ? raw.commands.filter(c => typeof c === 'string') : [];
    const art = owner || /[\r\n]/.test(body) || commands.some(c => /^(?:ca|patissier|ender|full|nico:ignoreScale)$/i.test(c));
    const fixed = commands.some(c => /^(?:ue|shita)$/i.test(c));
    const mail = art ? [...commands] : ['nico:opacity:1', ...commands.filter(c => !/^nico:opacity:/i.test(c))];
    if (options.colorStyle && options.colorStyle !== 'original') mail.unshift(options.colorStyle);
    if (!art && options.fontStyle && options.fontStyle !== 'default' && !commands.some(c => /^(?:defont|gothic|mincho|nico:flash)$/i.test(c))) mail.push(options.fontStyle);
    const speed = Math.max(.25, Math.min(1.5, Number(options.speed) || 1));
    if (!art && !fixed && speed !== 1) {
      const specified = commands.map(c => /^[@＠]([0-9.]+)/.exec(c)).filter(Boolean).at(-1);
      const seconds = specified && Number(specified[1]) > 0 ? Number(specified[1]) : 3;
      mail.push('@' + Math.min(120, Math.max(.01, (seconds + 1) / speed - 1)));
    }
    return { mail, art, fixed };
  };
  const normalized = (comments, options, repeats = new Map()) => {
    const result = [];
    const densityStep = Math.max(1, Math.round(1 / (options.renderMode === 'battery' ? .5 : (Number(options.density) || 1))));
    comments.forEach((comment, index) => {
      if (!Number.isFinite(comment.at) || comment.at < 0 || typeof comment.body !== 'string') return;
      let raw = {};
      try { raw = comment.raw ? JSON.parse(comment.raw) : {}; } catch (_) {}
      if (!raw || typeof raw !== 'object') raw = {};
      const { mail, art, fixed } = commandsFor(raw, comment.body, options, comment.fork === 'owner');
      const identity = JSON.stringify([comment.at, comment.body, comment.raw ?? null, comment.fork || 'main']);
      const occurrence = repeats.get(identity) || 0;
      repeats.set(identity, occurrence + 1);
      const key = identity + ':' + occurrence;
      if (!ids.has(key)) ids.set(key, ids.size);
      if (!art && !fixed && ids.get(key) % densityStep !== 0) return;
      const author = raw.userId ?? raw.user_id;
      const user = JSON.stringify([raw.source || '', author == null || author === '' ? 'anonymous:' + key : author]);
      if (!users.has(user)) users.set(user, users.size);
      const posted = Date.parse(raw.postedAt || '2026-01-01T00:00:00Z');
      const owner = comment.fork === 'owner';
      if (owner && comment.body.startsWith('/') && !mail.includes('invisible')) mail.push('invisible');
      result.push({ key, value: {
        id: ids.get(key), vpos: Math.floor(comment.at / 10), content: comment.body,
        date: Number.isFinite(posted) && posted >= 0 ? Math.floor(posted / 1000) : 1767225600,
        date_usec: Number.isFinite(posted) ? (posted % 1000) * 1000 : 0,
        owner, premium: Boolean(raw.isPremium), mail, user_id: users.get(user),
        layer: Number.isSafeInteger(raw.layer) ? raw.layer : owner ? -2 : -1,
        ignoreScale: Boolean(raw.ignoreScale), is_my_post: Boolean(raw.isMyPost)
      } });
    });
    return result;
  };
  const presentation = options => {
    const position = ['top', 'center', 'bottom', 'custom'].includes(options.position) ? options.position : 'center';
    canvas.style.top = position === 'top' ? '0' : position === 'bottom' ? 'auto' : '50%';
    canvas.style.bottom = position === 'bottom' ? '0' : 'auto';
    canvas.style.transform = position === 'center' ? 'translateY(-50%)' : 'none';
    if (position === 'custom') {
      const y = Number.isFinite(Number(options.customY)) ? Math.max(0, Math.min(1, Number(options.customY))) : .5;
      canvas.style.top = `${y * 100}%`; canvas.style.bottom = 'auto'; canvas.style.transform = `translateY(-${y * 100}%)`;
    }
    const interval = 1000 / Math.max(15, Math.min(options.renderMode === 'battery' ? 30 : options.renderMode === 'balanced' ? 60 : 144, Number(options.fps) || 60));
    if (interval !== frameInterval) nextDraw = performance.now() + interval;
    frameInterval = interval;
    canvas.style.opacity = String(Math.max(0, Math.min(1, Number(options.opacity))));
  };
  const nativeLoad = (comments, options) => {
    const threads = new Map();
    for (const [index, comment] of comments.entries()) {
      let raw = {};
      try { raw = typeof comment.raw === 'string' ? JSON.parse(comment.raw) : comment.raw || {}; } catch (_) {}
      const fork = comment.fork || 'main';
      if (!threads.has(fork)) threads.set(fork, {id: fork, fork, comments: []});
      const {mail} = commandsFor(raw, comment.body, options, fork === 'owner');
      threads.get(fork).comments.push({
        id: String(raw.id ?? index), no: Number.isSafeInteger(raw.no) ? raw.no : index,
        vposMs: comment.at, body: comment.body, commands: mail,
        userId: String(raw.userId ?? raw.user_id ?? `anonymous:${index}`), isPremium: Boolean(raw.isPremium),
        score: Number.isFinite(raw.score) ? raw.score : 0, postedAt: raw.postedAt || '2026-01-01T00:00:00Z',
        nicoruCount: Number.isSafeInteger(raw.nicoruCount) ? raw.nicoruCount : 0, nicoruId: raw.nicoruId ?? null,
        source: String(raw.source || ''), isMyPost: Boolean(raw.isMyPost)
      });
    }
    const nextCanvas = canvas.cloneNode(false);
    const next = new NiconiComments(nextCanvas, [...threads.values()], {
      format: 'v1', mode: 'default', keepCA: true, lazy: true, scale: options.size / 24,
      config: {contextStrokeOpacity: .62, contextFillLiveOpacity: 1, commentDrawRange: 1920, commentDrawPadding: 0,
        collisionRange: {left: 0, right: 1920}, fpsInterval: 1000 / options.fps}
    });
    if (anchor.enabled) next.drawCanvas(Math.max(0, playbackTime()) / 10, true);
    const previous = renderer;
    canvas.replaceWith(nextCanvas); canvas = nextCanvas; renderer = next;
    epoch = options.epoch ?? ''; renderedKeys = new Set(); renderConfig = 'native-v1';
    presentation(options); discardRenderer(previous); report('loaded', comments.length);
  };
  const bulkLoad = async (comments, options, serial) => {
    let pending = null;
    const check = () => { if (serial !== loadSerial) throw cancelled; };
    let deadline = performance.now() + 4;
    const breathe = async () => { check(); await yieldFrame(); check(); deadline = performance.now() + 4; };
    const lifetime = value => {
      let seconds = 3;
      for (const command of value.mail) {
        const match = /^[@＠]([0-9.]+)$/.exec(command);
        if (match && Number.isFinite(Number(match[1])) && Number(match[1]) > 0) seconds = Number(match[1]);
      }
      return Math.min((NiconiComments.internal?.utils?.MAX_COMMENT_LONG ?? 12000) * 10, seconds * 1000) + 1250;
    };
    const place = async (target, entries, keys, urgentOnly = false) => {
      const items = entries.map(entry => ({ entry, at: entry.value.vpos * 10, until: entry.value.vpos * 10 + lifetime(entry.value), done: false })).sort((a, b) => a.at - b.at);
      const longest = items.reduce((max, item) => Math.max(max, item.until - item.at), 0);
      let background = 0, remaining = items.length;
      const lowerBound = time => {
        let lo = 0, hi = items.length;
        while (lo < hi) { const mid = (lo + hi) >>> 1; if (items[mid].at < time) lo = mid + 1; else hi = mid; }
        return lo;
      };
      while (remaining > 0) {
        check();
        const time = Math.max(0, playbackTime()), batch = [];
        const upcoming = lowerBound(time - 1000);
        for (let i = upcoming; i < items.length && items[i].at <= time + 5000 && batch.length < 4; i++) {
          if (!items[i].done) { items[i].done = true; batch.push(items[i].entry); }
        }
        if (batch.length < 4) {
          for (let i = lowerBound(time - longest); i < upcoming && batch.length < 4; i++) {
            if (!items[i].done && items[i].until > time) { items[i].done = true; batch.push(items[i].entry); }
          }
        }
        if (batch.length === 0 && urgentOnly) break;
        if (!urgentOnly) {
          while (background < items.length && batch.length < 4) {
            const item = items[background++];
            if (!item.done) { item.done = true; batch.push(item.entry); }
          }
        }
        if (batch.length === 0) break;
        target.addComments(...batch.map(entry => entry.value));
        batch.forEach(entry => keys.add(entry.key)); remaining -= batch.length;
        if (performance.now() >= deadline) await breathe();
      }
      return items.filter(item => !item.done).map(item => item.entry);
    };
    try {
      const nextEpoch = options.epoch ?? '';
      const sameEpoch = epoch === nextEpoch;
      if (!sameEpoch) { ids = new Map(); users = new Map(); }
      const entries = [], repeats = new Map(), nextKeys = new Set();
      for (let i = 0; i < comments.length; i += 32) {
        const batch = normalized(comments.slice(i, i + 32), options, repeats);
        for (const entry of batch) { entries.push(entry); nextKeys.add(entry.key); }
        if (performance.now() >= deadline) await breathe();
      }
      const nextConfig = JSON.stringify([options.size, options.speed, options.density, options.colorStyle, options.fontStyle, options.renderMode]);
      let additions = [];
      let retainsAll = true, regroupArt = false;
      for (const entry of entries) {
        if (!renderedKeys.has(entry.key)) {
          additions.push(entry);
          if (entry.value.mail.some(c => /^(?:ca|patissier|ender|full)$/i.test(c)) || (entry.value.content.match(/[\r\n]/g) || []).length > 2) regroupArt = true;
        }
        if (performance.now() >= deadline) await breathe();
      }
      for (const key of renderedKeys) {
        if (!nextKeys.has(key)) { retainsAll = false; break; }
        if (performance.now() >= deadline) await breathe();
      }
      let append = renderer && sameEpoch && renderConfig === nextConfig && retainsAll;
      if (append && regroupArt) {
        const group = NiconiComments.internal?.utils?.changeCALayer;
        if (!group || !renderer.ctx?.config || !Array.isArray(renderer.comments)) append = false;
        else {
          const authors = new Set();
          for (const { value: c } of entries) {
            if (c.owner || c.mail.some(m => /^(?:ca|patissier|ender|full)$/i.test(m)) || (c.content.match(/[\r\n]/g) || []).length > 2) authors.add(c.user_id);
            if (performance.now() >= deadline) await breathe();
          }
          const art = entries.filter(e => authors.has(e.value.user_id)).map(e => e.value);
          art.sort((a, b) => a.vpos - b.vpos || a.date - b.date || a.date_usec - b.date_usec);
          const grouped = group(art, renderer.ctx.config);
          const oldById = new Map(), layers = new Map(), groupedById = new Map();
          let nextLayer = 0;
          for (const c of renderer.comments) {
            oldById.set(c.comment.id, c); nextLayer = Math.max(nextLayer, c.layer + 1);
            if (performance.now() >= deadline) await breathe();
          }
          for (const c of grouped) {
            groupedById.set(c.id, c);
            const old = oldById.get(c.id);
            if (old && c.layer >= 0) {
              if (old.layer < 0 || (layers.has(c.layer) && layers.get(c.layer) !== old.layer)) { append = false; break; }
              layers.set(c.layer, old.layer);
            } else if (old && old.layer >= 0) { append = false; break; }
            if (performance.now() >= deadline) await breathe();
          }
          if (append) {
            const groupedAdditions = [], ignored = [];
            for (const entry of additions) {
              if (!authors.has(entry.value.user_id)) groupedAdditions.push(entry);
              else {
                const c = groupedById.get(entry.value.id);
                if (!c) ignored.push(entry.key);
                else {
                  if (c.layer >= 0 && !layers.has(c.layer)) layers.set(c.layer, nextLayer++);
                  groupedAdditions.push({ key: entry.key, value: { ...c, layer: c.layer >= 0 ? layers.get(c.layer) : c.layer } });
                }
              }
              if (performance.now() >= deadline) await breathe();
            }
            check(); additions = groupedAdditions;
            ignored.forEach(key => renderedKeys.add(key));
          }
        }
      }
      const target = append ? renderer : null;
      if (append) {
        const art = additions.filter(({ value: c }) => c.owner || c.layer >= 0 || c.mail.some(m => /^(?:ca|patissier|ender|full)$/i.test(m)) || (c.content.match(/[\r\n]/g) || []).length > 2);
        const artIds = new Set(art.map(entry => entry.value.id));
        art.sort((a, b) => a.value.vpos - b.value.vpos || a.value.date - b.value.date || a.value.date_usec - b.value.date_usec);
        for (let i = 0; i < art.length; i += 4) {
          check();
          const batch = art.slice(i, i + 4);
          target.addComments(...batch.map(entry => entry.value));
          batch.forEach(entry => renderedKeys.add(entry.key));
          if (performance.now() >= deadline) await breathe();
        }
        await place(target, additions.filter(entry => !artIds.has(entry.value.id)), renderedKeys);
        presentation(options);
      } else {
        const artAuthors = new Set();
        for (const entry of entries) {
          const c = entry.value;
          if (c.owner || c.mail.some(m => /^(?:ca|patissier|ender|full)$/i.test(m)) || (c.content.match(/[\r\n]/g) || []).length > 2) artAuthors.add(c.user_id);
          if (performance.now() >= deadline) await breathe();
        }
        const art = [], artKeys = new Set(), ordinary = [];
        for (const entry of entries) {
          if (artAuthors.has(entry.value.user_id)) { art.push(entry.value); artKeys.add(entry.key); }
          else ordinary.push(entry);
          if (performance.now() >= deadline) await breathe();
        }
        const nextCanvas = canvas.cloneNode(false);
        nextCanvas.getContext('2d');
        const group = NiconiComments.internal?.utils?.changeCALayer;
        pending = new NiconiComments(nextCanvas, group ? [] : art, {
          format: 'formatted', mode: 'default', keepCA: true, lazy: true, scale: options.size / 24,
          config: { contextStrokeOpacity: .62, contextFillLiveOpacity: 1, commentDrawRange: 1920, commentDrawPadding: 0, collisionRange: { left: 0, right: 1920 }, fpsInterval: 1000 / options.fps }
        });
        await breathe();
        if (group) {
          art.sort((a, b) => a.vpos - b.vpos || a.date - b.date || a.date_usec - b.date_usec);
          const grouped = group(art, pending.ctx.config);
          for (let i = 0; i < grouped.length; i += 4) {
            check(); pending.addComments(...grouped.slice(i, i + 4));
            if (performance.now() >= deadline) await breathe();
          }
        }
        const deferred = await place(pending, ordinary, artKeys, true);
        check();
        if (anchor.enabled) {
          const getPosX = NiconiComments.internal?.utils?.getPosX;
          if (getPosX) {
            for (const c of pending.comments) {
              check();
              const x = getPosX(c.comment, Math.max(0, playbackTime()) / 10, pending.ctx.config, false);
              const y = c.loc === 'shita' ? pending.ctx.config.canvasHeight - c.posY - c.height : c.posY;
              if (!c.invisible && x < pending.ctx.config.canvasWidth && x + c.width > 0 && y < pending.ctx.config.canvasHeight && y + c.height > 0) c.getTextImage?.();
              if (performance.now() >= deadline) await breathe();
            }
          }
          pending.drawCanvas(Math.max(0, playbackTime()) / 10, true);
        }
        const previous = renderer;
        canvas.replaceWith(nextCanvas); canvas = nextCanvas; renderer = pending; pending = null;
        epoch = nextEpoch; renderConfig = nextConfig; renderedKeys = artKeys;
        presentation(options);
        discardRenderer(previous);
        const published = renderer;
        await place(published, deferred, renderedKeys);
      }
      check(); report('loaded', comments.length);
    } catch (error) {
      discardRenderer(pending);
      if (error !== cancelled) report('error', String(error?.stack || error));
    }
  };
  window.addEventListener('error', e => report('error', JSON.stringify({
    message: e.message || 'Script error.', source: e.filename || '', line: e.lineno || 0,
    column: e.colno || 0, stack: e.error?.stack || ''
  })));
  window.addEventListener('unhandledrejection', e => report('error', String(e.reason?.stack || e.reason || 'Unhandled promise rejection')));
  window.commentOverlay = {
    setViewport(bounds) {
      const root = document.documentElement;
      const measured = () => {
        const rect = document.getElementById('commentViewport').getBoundingClientRect();
        report('viewport', JSON.stringify({left:rect.left,top:rect.top,width:rect.width,height:rect.height,screenWidth:innerWidth,screenHeight:innerHeight}));
      };
      if (!bounds || !['left','top','width','height'].every(key => Number.isFinite(bounds[key])) ||
          bounds.left < 0 || bounds.top < 0 || bounds.width <= 0 || bounds.height <= 0 ||
          bounds.left + bounds.width > 1.001 || bounds.top + bounds.height > 1.001) {
        root.removeAttribute('data-video-viewport');
        measured();
        return;
      }
      for (const key of ['left','top','width','height']) root.style.setProperty('--video-' + key, bounds[key] * 100 + '%');
      root.setAttribute('data-video-viewport','');
      measured();
    },
    beginUpdate(token, options) { loadSerial++; incoming = { token, options, comments: [] }; },
    pushUpdate(token, comments) { if (incoming?.token === token) incoming.comments.push(...comments); },
    commitUpdate(token) { if (incoming?.token !== token) return; const update = incoming; incoming = null; return this.load(update.comments, update.options); },
    load(comments, options) {
      const serial = ++loadSerial;
      if (options.nativeV1) {
        try { nativeLoad(comments, options); } catch (error) { report('error', String(error?.stack || error)); }
        return;
      }
      if (comments.length > 128) return bulkLoad(comments, options, serial);
      const previousIds = ids, previousUsers = users;
      try {
        const nextEpoch = options.epoch ?? '';
        const sameEpoch = epoch === nextEpoch;
        if (!sameEpoch) { ids = new Map(); users = new Map(); }
        const entries = normalized(comments, options);
        const nextKeys = new Set(entries.map(e => e.key));
        const nextConfig = JSON.stringify([options.size, options.speed, options.density, options.colorStyle, options.fontStyle, options.renderMode]);
        const additions = entries.filter(e => !renderedKeys.has(e.key)).map(e => e.value);
        const regroupArt = additions.some(c => c.mail.some(command => /^(?:ca|patissier|ender|full)$/i.test(command)) || (c.content.match(/[\r\n]/g) || []).length > 2);
        const append = renderer && sameEpoch && renderConfig === nextConfig && !regroupArt && [...renderedKeys].every(key => nextKeys.has(key));
        if (append) {
          for (let i = 0; i < additions.length; i += 512) renderer.addComments(...additions.slice(i, i + 512));
          if (additions.length && anchor.enabled) renderer.drawCanvas(Math.max(0, playbackTime()) / 10, true);
        } else {
          const nextCanvas = renderer ? canvas.cloneNode(false) : canvas;
          nextCanvas.getContext('2d');
          const nextRenderer = new NiconiComments(nextCanvas, entries.map(e => e.value), {
            format: 'formatted', mode: 'default', keepCA: true, scale: options.size / 24,
            config: {
              contextStrokeOpacity: 0.62, contextFillLiveOpacity: 1,
              commentDrawRange: 1920, commentDrawPadding: 0,
              collisionRange: { left: 0, right: 1920 }, fpsInterval: 1000 / options.fps
            }
          });
          if (sameEpoch && anchor.enabled) nextRenderer.drawCanvas(Math.max(0, playbackTime()) / 10, true);
          const previous = renderer;
          if (nextCanvas !== canvas) { canvas.replaceWith(nextCanvas); canvas = nextCanvas; }
          renderer = nextRenderer;
          discardRenderer(previous);
        }
        epoch = nextEpoch; renderedKeys = nextKeys; renderConfig = nextConfig;
        presentation(options);
        report('loaded', comments.length);
      } catch (error) { ids = previousIds; users = previousUsers; report('error', String(error?.stack || error)); }
    },
    sync(position, speed, enabled) {
      anchor = { position, speed, enabled, received: performance.now() };
      if (!enabled) clear();
    },
    drawAt(milliseconds) {
      try { renderer?.drawCanvas(Math.max(0, milliseconds) / 10); }
      catch (error) { report('error', String(error?.stack || error)); }
    }
  };
  function frame(now) {
    if (renderer && anchor.enabled && now + 0.01 >= nextDraw) {
      nextDraw = Math.max(nextDraw + frameInterval, now);
      window.commentOverlay.drawAt(anchor.position + (now - anchor.received) * anchor.speed);
      if (now - lastReport > 1000) { report('frame', Math.round(playbackTime())); lastReport = now; }
    }
    requestAnimationFrame(frame);
  }
  nextDraw = performance.now() + frameInterval;
  requestAnimationFrame(frame);
  report('ready', '');
})();
