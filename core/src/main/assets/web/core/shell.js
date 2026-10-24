// Shell shared by every game: connection, joining, the room lobby, game picker, invite QR,
// network switch (Wi-Fi / Internet relay). Games register themselves with PG.register().
//
// Pages: /host#<token> in the host app's WebView, / for players on local Wi-Fi,
// /r/<CODE>/ for players coming through the Render relay.
(function () {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (_) { return null; } },
    set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (_) {} },
  };

  const base = location.pathname.replace(/[^/]*$/, '');
  const isHostPage = location.pathname === '/host';
  const token = isHostPage ? location.hash.slice(1) : '';
  // ids are per server: a relay room and the local server are different rooms
  const K = 'pg.' + (isHostPage ? 'host' : base) + '.';

  const games = {};
  let pid = store.get(K + 'pid');
  let st = null, prev = null;
  let joining = false;
  let clockOffset = 0;
  let menuOpen = false;
  let showInvite = false;
  let rulesOpen = false;
  let showRelayUrl = false;
  let bigQr = false;
  let armed = null;

  // Dev helper: ?autojoin=Name joins without touching the form (used by the desktop 4-pane launcher).
  const autoName = new URLSearchParams(location.search).get('autojoin');
  function autoJoin() {
    if (!autoName || pid || joining) return;
    joining = true;
    send({ t: 'join', name: autoName, pid: '' });
  }

  // ---------- socket ----------
  let ws = null, tries = 0;
  function open() {
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(proto + location.host + base + 'ws' + (isHostPage ? '?host=' + encodeURIComponent(token) : ''));
    ws.onopen = () => {
      tries = 0; status(true);
      if (pid) send({ t: 'resume', pid }); else { autoJoin(); render(); }
    };
    ws.onmessage = e => { let m; try { m = JSON.parse(e.data); } catch (_) { return; } onMessage(m); };
    ws.onclose = () => { status(false); setTimeout(open, Math.min(4000, 400 * ++tries)); };
    ws.onerror = () => { try { ws.close(); } catch (_) {} };
  }
  function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }
  function status(ok) { $('#conn').innerHTML = `<span class="dot ${ok ? '' : 'off'}"></span>${ok ? '' : ' <span class="small muted">зв\'язок…</span>'}`; }
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && ws && ws.readyState > 1) open();
    if (!document.hidden) keepAwake();
  });

  // Keep player screens on during a game (only works over HTTPS, i.e. through the relay).
  let wakeLock = null;
  async function keepAwake() {
    if (isHostPage || !('wakeLock' in navigator) || (wakeLock && !wakeLock.released)) return;
    try { wakeLock = await navigator.wakeLock.request('screen'); } catch (_) {}
  }

  function onMessage(m) {
    if (m.t === 'welcome') { pid = m.pid; store.set(K + 'pid', pid); joining = false; return; }
    if (m.t === 'unknown') { pid = null; store.set(K + 'pid', null); autoJoin(); render(); return; }
    if (m.t !== 'state') return;
    clockOffset = m.now - Date.now();
    if (m.me && m.me.id !== pid) { pid = m.me.id; store.set(K + 'pid', pid); joining = false; }
    prev = st; st = m;
    loadGames();
    const switched = prev && prev.game !== st.game;
    if (switched) { showInvite = false; menuOpen = false; rulesOpen = false; bigQr = false; }
    const g = st.game && games[st.game];
    if (g && g.onState) g.onState(prev && prev.game === st.game ? prev.g : null, st.g, api);
    if (switched) sfx('whoosh');
    else if (prev && !st.game && st.players.length > prev.players.length) sfx('join');
    updateMusic();
    render();
    // a new screen starts at its top, not wherever the game list was scrolled to
    if (switched) window.scrollTo(0, 0);
  }

  // ---------- sound ----------
  // The room has one speaker, the host's phone; players' phones only vibrate.
  // Music follows the game and its phase: a game's `music` maps a phase to 'low' (people are talking over it) or
  // 'off' (someone has to be heard), any other phase plays it normally. Effects come from the games' onState through
  // api.sfx(): only the host page acts on them (it sees the whole game).
  // Browsers only allow audio after a user gesture, so the context is unlocked on the first tap (the app's WebViews
  // allow it straight away).
  let audioCtx = null;
  function unlockAudio() {
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!audioCtx && AC) { audioCtx = new AC(); audioCtx.onstatechange = updateMusic; }
      if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume().then(updateMusic, () => {});
      updateMusic();
    } catch (_) {}
  }
  ['pointerdown', 'keydown', 'touchstart'].forEach(e => document.addEventListener(e, unlockAudio, { passive: true }));
  const soundOn = () => !!(audioCtx && audioCtx.state === 'running');

  const MUSIC_VOL = 0.5, FX_VOL = 0.8, LEVEL = { on: 1, low: 0.3, off: 0 };
  // The shell's own sounds: core/music.mp3 in the lobby (loop seconds: a whole number of bars) and core/sfx/<name>.mp3.
  // A game brings its own as games/<id>/music.mp3 (`musicLoop`) and games/<id>/sfx/<name>.mp3 (`sfx: [names]`);
  // while it is on, its names win over these.
  const LOBBY_LOOP = 0;  // an app with lobby music ships core/music.mp3 and sets this to its loop length
  const CORE_SFX = ['tick', 'tick_urgent', 'time_up', 'win', 'lose', 'correct', 'wrong', 'your_turn', 'reveal',
    'whoosh', 'join', 'vote', 'card', 'suspense'];
  function fxFile(name) {
    const g = st && st.game && games[st.game];
    if (g && g.sfx && g.sfx.indexOf(name) >= 0) return 'games/' + st.game + '/sfx/' + name + '.mp3';
    return CORE_SFX.indexOf(name) >= 0 ? 'core/sfx/' + name + '.mp3' : null;
  }

  const bufs = {};
  function buffer(file) {
    return bufs[file] || (bufs[file] = fetch(base + 'static/' + file)
      .then(r => { if (!r.ok) throw new Error(r.status); return r.arrayBuffer(); })
      .then(b => new Promise((ok, no) => audioCtx.decodeAudioData(b, ok, no))));
  }

  const speaker = () => isHostPage && !!st;
  const sndOn = k => !(st && st.snd && st.snd[k] === false);

  const lastFx = {};
  function playFx(name) {
    const file = fxFile(name);
    if (!soundOn() || !sndOn('sfx') || !file) return;
    // the ticker and a game may both announce the same moment
    const t = Date.now();
    if (t - (lastFx[name] || 0) < 700) return;
    lastFx[name] = t;
    buffer(file).then(b => {
      const s = audioCtx.createBufferSource(), v = audioCtx.createGain();
      s.buffer = b; v.gain.value = FX_VOL;
      s.connect(v); v.connect(audioCtx.destination); s.start();
    }, () => {});
  }

  /** A sound effect for the room, optionally after `delay` ms. Called on every page; only the host page acts on it. */
  function sfx(name, delay) {
    if (!isHostPage || !st) return;
    if (delay) { setTimeout(() => sfx(name), delay); return; }
    playFx(name);
  }

  let track = null;  // { name, gain, vol, src }
  function ramp(g, v, sec) {
    const now = audioCtx.currentTime;
    g.gain.cancelScheduledValues(now);
    g.gain.setValueAtTime(g.gain.value, now);
    g.gain.linearRampToValueAtTime(v, now + sec);
  }
  /** Where the music starts: decoders differ in whether they strip the MP3's leading padding. */
  function leadIn(b) {
    const d = b.getChannelData(0), n = Math.min(d.length, b.sampleRate / 4);
    for (let i = 0; i < n; i++) if (Math.abs(d[i]) > 1e-4) return i / b.sampleRate;
    return 0;
  }
  function updateMusic() {
    if (!audioCtx) return;
    let name = null, vol = 0, loop = 0;
    if (soundOn() && st && speaker() && sndOn('music')) {
      const g = st.game && games[st.game];
      const phase = st.phase || (st.g && st.g.phase) || '';
      loop = st.game ? (g && g.musicLoop) || 0 : LOBBY_LOOP;
      name = !loop ? null : st.game ? 'games/' + st.game + '/music.mp3' : 'core/music.mp3';
      const lv = LEVEL[(g && g.music && g.music[phase]) || 'on'];
      vol = MUSIC_VOL * (lv == null ? 1 : lv);
    }
    if (track && track.name !== name) {
      const old = track; track = null;
      ramp(old.gain, 0, 1.2);
      setTimeout(() => { try { if (old.src) old.src.stop(); old.gain.disconnect(); } catch (_) {} }, 1400);
    }
    if (!name) return;
    if (track) {
      if (track.vol !== vol) { track.vol = vol; if (track.src) ramp(track.gain, vol, 1.5); }
      return;
    }
    const t = track = { name, gain: audioCtx.createGain(), vol, src: null };
    t.gain.gain.value = 0;
    t.gain.connect(audioCtx.destination);
    buffer(name).then(b => {
      if (track !== t) return;
      const s = t.src = audioCtx.createBufferSource();
      const lead = leadIn(b);
      s.buffer = b; s.loop = true;
      s.loopStart = lead; s.loopEnd = Math.min(b.duration, lead + loop);
      s.connect(t.gain);
      s.start(0, lead);
      ramp(t.gain, t.vol, 2.5);
    }, () => {});
  }

  // ---------- icons ----------
  // Line icons, 24x24, drawn in the current text colour (ui.css .ic). No emoji anywhere in the interface.
  const ICONS = {
    file: '<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4M9 12h6M9 16h6"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5 21 21"/>',
    help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 .9-1 1.7M12 17v.5"/>',
    pen: '<path d="m4 20 1-4L16 5l3 3L8 19z"/><path d="m14 7 3 3"/>',
    journal: '<path d="M5 4h12v16H5z"/><path d="M8 4v16M11 9h4M11 13h4"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    star: '<path d="m12 3 2.6 6 6.4.5-4.8 4.2 1.5 6.3L12 16.6 6.3 20l1.5-6.3L3 9.5 9.4 9z"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    back: '<path d="M20 12H5M11 6l-6 6 6 6"/>',
    compare: '<path d="M4 5h7v14H4zM13 5h7v14h-7z"/>',
    chip: '<path d="M7 7h10v10H7zM10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4"/>',
    pin: '<path d="M9 3h6l-1 6 3 3H7l3-3zM12 12v9"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7"/>',
    book: '<path d="M4 5c3-1 6-1 8 1 2-2 5-2 8-1v14c-3-1-6-1-8 1-2-2-5-2-8-1z"/><path d="M12 6v14"/>',
    home: '<path d="m4 11 8-7 8 7M6 10v10h12V10M10 20v-6h4v6"/>',
    refresh: '<path d="M20 12a8 8 0 1 1-2.5-5.8M20 4v5h-5"/>',
    phone: '<path d="M8 3h8v18H8zM11 18h2"/>',
    door: '<path d="M6 3h10v18H6zM16 5l3 1v14l-3 1M13 12h.5"/>',
    music: '<path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/>',
    bell: '<path d="M6 17v-6a6 6 0 0 1 12 0v6l2 2H4zM10 21h4"/>',
    sound: '<path d="M4 10v4h4l5 4V6l-5 4zM16 9c1.5 1.5 1.5 4.5 0 6"/>',
    wifi: '<path d="M3 9c5-5 13-5 18 0M6 12.5c3.5-3.5 8.5-3.5 12 0M9.5 16c1.5-1.5 3.5-1.5 5 0M12 19.5v.1"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/>',
    up: '<path d="M12 20V6M6 12l6-6 6 6M5 20h14"/>',
    power: '<path d="M12 3v9M6.5 6.5a8 8 0 1 0 11 0"/>',
    folder: '<path d="M3 6h6l2 2h10v11H3z"/><path d="M3 11h18"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 4-6 8-6s8 2 8 6"/>',
    lock: '<path d="M6 11h12v9H6zM8 11V8a4 4 0 0 1 8 0v3"/>',
    qr: '<path d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2v6h-4M14 18v2"/>',
  };
  const ico = (name, cls) => `<svg class="ic ${cls || ''}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] || ''}</svg>`;

  const api = {
    $, esc, store, sfx, ico,
    get st() { return st; },
    get g() { return st && st.g; },
    get me() { return st && st.me; },
    get isHost() { return !!(st && st.isHost); },
    get isHostPage() { return isHostPage; },
    get mod() { return st && st.mods && st.game ? st.mods[st.game] : null; },
    now() { return Date.now() + clockOffset; },
    send(d) { send({ t: 'g', d }); },
    sendMod(d) { send({ t: 'mod', game: st && st.game, d }); },
    kick(id) { send({ t: 'kick', pid: id }); },
    nameOf(id) { return esc(((st && st.players.find(p => p.id === id)) || {}).name || '?'); },
    render() { render(); },
    vibrate(ms) { if (!isHostPage && navigator.vibrate) { try { navigator.vibrate(ms); } catch (_) {} } },
  };
  // ---------- shared components (markup for ui.css) ----------
  const fmtClock = ms => { const s = Math.ceil(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
  const FMT = { clock: fmtClock, sec: ms => Math.ceil(ms / 1000) + ' с' };
  const fmtTime = (fmt, ms) => (FMT[fmt] || fmtClock)(ms);

  const ui = {
    /**
     * A secret card the player taps to turn over; its data-act="flip" goes to the game's click().
     * o: { open, tone: CSS colour of the open side, back: HTML of the open side, front: optional closed side }
     */
    flipCard(o) {
      const front = o.front || `<div class="card-ico">${ico('lock', 'lg')}</div><div class="title">Твоя роль</div>
        <div class="what">Торкнись, щоб подивитися. Сховай екран від інших.</div>`;
      return `<div class="flip ${o.open ? 'open' : ''}" data-act="flip" style="--tone:${o.tone || 'var(--accent)'}">
        <div class="face front">${front}</div>
        <div class="face back">${o.back}<div class="muted small">Торкнись, щоб сховати</div></div></div>`;
    },
    /** Three bouncing dots: someone is busy (thinking, typing, choosing). cls: 'sm' for inside a row. */
    typing(cls = '') { return `<span class="typing ${cls}"><i></i><i></i><i></i></span>`; },
    /**
     * Waiting for a person (not for loading): a card with whose move it is and a "typing…" bubble.
     * text: HTML; who: a player's name (shows its initial) or an icon name from ICONS, e.g. 'user' for the host.
     */
    wait(text, who) {
      const ch = [...String(who || '').trim()][0] || '?';
      const face = !who ? '' : ICONS[who] ? `<span class="avatar emoji">${ico(who)}</span>`
        : `<span class="avatar">${esc(ch.toUpperCase())}</span>`;
      return `<div class="card flat wait"><span class="who">${face}${ui.typing()}</span><span class="muted">${text}</span></div>`;
    },
    /**
     * Countdown; one shared ticker keeps every one on screen current.
     * o: { deadline, ms: shown while paused (no deadline), low: ms when it turns red, fmt: 'clock' | 'sec', pre, cls }
     */
    timer(o) {
      const ms = o.deadline ? Math.max(0, o.deadline - api.now()) : (o.ms || 0);
      const live = o.deadline ? ` data-deadline="${o.deadline}" data-low="${o.low || 0}" data-fmt="${o.fmt || 'clock'}" data-pre="${esc(o.pre || '')}"` : '';
      return `<div class="timer ${o.cls || ''} ${ms < (o.low || 0) ? 'low' : ''}"${live}>${esc(o.pre || '')}${fmtTime(o.fmt, ms)}</div>`;
    },
    /** The progress bar under a countdown. o: { deadline, ms, total, low } */
    timerBar(o) {
      const ms = o.deadline ? Math.max(0, o.deadline - api.now()) : (o.ms || 0);
      const live = o.deadline ? ` data-deadline="${o.deadline}" data-low="${o.low || 0}" data-total="${o.total}"` : '';
      return `<div class="bar ${ms < (o.low || 0) ? 'low' : ''}"${live}><i style="width:${o.total ? ms / o.total * 100 : 0}%"></i></div>`;
    },
  };
  api.ui = ui;

  let tickSec = null;
  setInterval(() => {
    let sec = null;
    document.querySelectorAll('[data-deadline]').forEach(el => {
      const ms = Math.max(0, +el.dataset.deadline - api.now());
      el.classList.toggle('low', ms < +el.dataset.low);
      if (el.dataset.total) { if (el.firstElementChild) el.firstElementChild.style.width = (ms / +el.dataset.total * 100) + '%'; }
      else {
        el.textContent = (el.dataset.pre || '') + fmtTime(el.dataset.fmt, ms);
        if (sec == null && ms > 0 && ms < +el.dataset.low) sec = Math.ceil(ms / 1000);
      }
    });
    // A countdown ticks through its last seconds on the host's speaker. Running out ends with the buzzer.
    if (sec !== tickSec) {
      const was = tickSec;
      tickSec = sec;
      const n = sec != null ? (sec <= 3 ? 'tick_urgent' : 'tick') : was === 1 ? 'time_up' : null;
      if (n && isHostPage && st) sfx(n);
    }
  }, 250);

  // ---------- background ----------
  // A game's `bg` is 'still' (games/<id>/bg.webp) or 'loop' (bg.webm / bg.mp4 over that still as the poster); the TV
  // takes the landscape cut (bg-tv.*). The loops are made seamless at build time and kept under the relay's
  // asset limit. With reduced motion only the still is shown. #bg sits under body::before, which dims it.
  let bgKey = null;
  function updateBg() {
    const id = st && st.game;
    const kind = id && games[id] && games[id].bg;
    const key = kind ? id + '/' + kind : '';
    document.body.dataset.phase = (st && (st.phase || (st.g && st.g.phase))) || '';
    if (key === bgKey) return;
    bgKey = key;
    const el = $('#bg');
    document.body.classList.toggle('has-bg', !!kind);
    if (!kind) { el.innerHTML = ''; return; }
    const f = base + 'static/games/' + id + '/bg';
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches || kind !== 'loop';
    el.innerHTML = still ? `<img src="${f}.webp" alt="">`
      : `<video autoplay muted loop playsinline disablepictureinpicture poster="${f}.webp">
           <source src="${f}.webm" type="video/webm"><source src="${f}.mp4" type="video/mp4"></video>`;
    const v = el.querySelector('video');
    if (v) { v.muted = true; v.play().catch(() => {}); }
  }

  window.PG = { register(id, mod) { games[id] = mod; }, api, ui };

  // ---------- games ----------
  // Each game is a folder games/<id>/ (game.js, game.css, rules.html, its sounds and background). The shell loads
  // the code and theme of every game the server lists (the picker shows each card in its game's theme); the rules
  // only when they are opened.
  const loading = {};  // id -> 'loading' | 'ok' | 'fail'
  const libs = {};     // shared files already requested
  function loadGames() {
    (st.games || []).forEach(({ id, assets }) => {
      if (loading[id] || !/^[a-z0-9_-]+$/.test(id)) return;
      loading[id] = 'loading';
      // shared code several games build on (a module's `webAssets`): loaded once, and before the game's own script
      (assets || []).forEach(f => {
        if (libs[f] || !/^[A-Za-z0-9_\/.-]+$/.test(f) || /(^|\/)\./.test(f)) return;
        libs[f] = true;
        if (f.endsWith('.css')) {
          const l = document.createElement('link');
          l.rel = 'stylesheet'; l.href = base + 'static/' + f;
          document.head.appendChild(l);
        } else {
          const sc = document.createElement('script');
          sc.async = false; sc.src = base + 'static/' + f;
          document.body.appendChild(sc);
        }
      });
      const css = document.createElement('link');
      css.rel = 'stylesheet'; css.href = base + 'static/games/' + id + '/game.css';
      document.head.appendChild(css);
      const js = document.createElement('script');
      js.async = false;
      js.src = base + 'static/games/' + id + '/game.js';
      js.onload = () => {
        loading[id] = games[id] ? 'ok' : 'fail';
        const g = games[id];
        // the state arrived before the code: let the game catch up as if it had just started
        if (g && st && st.game === id && g.onState && st.g) g.onState(null, st.g, api);
        updateMusic();
        render();
      };
      js.onerror = () => { loading[id] = 'fail'; render(); };
      document.body.appendChild(js);
    });
  }

  const rules = {};  // id -> HTML, '' when there are none, null while loading
  function rulesOf(id) {
    if (!(id in rules)) {
      rules[id] = null;
      fetch(base + 'static/games/' + id + '/rules.html')
        .then(r => r.ok ? r.text() : '', () => '')
        .then(t => { rules[id] = t; render(); });
    }
    return rules[id];
  }

  // ---------- views ----------
  // The app's own name comes from the server (AppConfig), so the same shell serves every app built on it.
  function appInfo() { return (st && st.app) || { name: '', emoji: '', pick: 'Що граємо?' }; }
  function brand() { return ico('search') + esc(appInfo().name); }

  /** A game is picked but not started yet (its own setup screen): leaving it loses nothing, so no "Точно?". */
  const notStarted = () => !!(st && st.game && (!st.g || st.g.phase === 'LOBBY'));

  function gameInfo(id) { return (st && st.games.find(x => x.id === id)) || null; }

  function joinView() {
    const name = store.get('pg.name') || '';
    return `<div class="stack" style="margin-top:${isHostPage ? 2 : 10}vh">
      ${isHostPage ? inviteCard() : ''}
      <h1>${isHostPage ? 'Як тебе звати?' : 'Заходь у гру'}</h1>
      ${isHostPage ? '' : '<p class="muted">Хост обирає гру, а ти граєш просто в браузері.</p>'}
      <input id="name" type="text" maxlength="20" placeholder="Твоє ім'я" value="${esc(name)}" autocomplete="nickname">
      <button class="big" data-pg="join" ${joining ? 'disabled' : ''}>Грати</button>
    </div>`;
  }

  // Each returns { qr, info }: the QR image (may be empty) and the text that goes with it.
  function localQr() {
    const urls = (st.net && st.net.local) || [];
    if (!urls.length) {
      return { qr: '', info: `<h2>Немає Wi-Fi</h2>
        <p class="muted">Підключи телефон до Wi-Fi або увімкни точку доступу — або перемкнись на «Інтернет».</p>` };
    }
    const u = urls[0];
    return {
      qr: `<img class="qr" src="${base}qr.svg?t=${encodeURIComponent(u.url)}" alt="QR">`,
      info: `<div class="url">${esc(u.url)}</div>
        <p class="muted small">${u.hotspot ? 'Гравці підключаються до точки доступу цього телефону.' : 'Гравці мають бути в тій самій Wi-Fi мережі.'}</p>
        ${urls.length > 1 ? `<div class="muted small">Інші адреси: ${urls.slice(1).map(x => esc(x.url)).join(', ')}</div>` : ''}`,
    };
  }

  function relayQr() {
    const r = st.net.relay;
    let qr = '', info;
    if (r.state === 'ONLINE' && r.join) {
      qr = `<img class="qr" src="${base}qr.svg?t=${encodeURIComponent(r.join)}" alt="QR">`;
      info = `<div class="code">${esc(r.code)}</div>
        <div class="url">${esc(r.join)}</div>
        <p class="muted small">Працює з будь-якої мережі, навіть з мобільного інтернету. Або відкрий ${esc(r.url.replace(/^https?:\/\//, ''))} і введи код.</p>`;
    } else {
      info = `<div class="center" style="padding:12px 0"><span class="spinner"></span></div>
        <p class="muted">${esc(r.message || 'Підключаюсь…')}</p>`;
    }
    info += showRelayUrl
      ? `<div class="row"><input id="relayUrl" type="text" value="${esc(r.url)}" style="flex:1;font-size:14px"><button class="mini" data-pg="saveRelay">OK</button></div>`
      : `<button class="mini ghost" data-pg="editRelay">Адреса сервера</button>`;
    return { qr, info };
  }

  /** QR + network switch. Compact puts a small QR next to the text (lobby), so the game list stays near the top. */
  function inviteCard(compact) {
    if (!st || !st.net) return '';
    const relayOn = st.net.relay.enabled;
    const { qr, info } = relayOn ? relayQr() : localQr();
    const small = compact && !bigQr;
    return `<div class="card invite ${small ? 'compact' : 'center'} stack">
      <div class="seg">
        <button class="${relayOn ? '' : 'on'}" data-pg="net" data-v="local">${ico('wifi')} Wi-Fi</button>
        <button class="${relayOn ? 'on' : ''}" data-pg="net" data-v="relay">${ico('globe')} Інтернет</button>
      </div>
      ${small
        ? `<div class="inv-row">${qr ? `<button class="qr-btn" data-pg="bigQr" aria-label="Збільшити QR">${qr}</button>` : ''}<div class="inv-info stack">${info}</div></div>`
        : `${compact && qr ? `<button class="qr-btn" data-pg="bigQr" aria-label="Зменшити QR">${qr}</button>` : qr}${info}`}
    </div>`;
  }

  function playersCard() {
    const ps = st.players;
    return `<div class="card">
      <div class="chips">${ps.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}${st.me && st.me.id === p.id ? ' <span class="muted small">(ти)</span>' : ''}
        ${st.isHost && !(st.me && st.me.id === p.id) ? `<button class="mini ghost x" data-pg="kick" data-v="${p.id}">${armed === 'kick' + p.id ? 'Точно?' : ico('close')}</button>` : ''}</span>`).join('')
        || '<span class="muted">Чекаємо, поки хтось відсканує QR…</span>'}</div>
    </div>`;
  }

  function lobbyView() {
    let h = `<div class="stack">`;
    if (st.isHost) {
      h += inviteCard(true);
      h += `<h2>Гравці · ${st.players.length}</h2>${playersCard()}`;
      h += `<h2>${esc(appInfo().pick)}</h2><div class="games">${st.games.map(g => {
        const enough = st.players.length >= g.minPlayers;
        return `<button class="game-card" data-pg="select" data-v="${g.id}" data-theme="${g.id}">
          <span class="gico">${ico('folder')}</span>
          <span class="grow"><b>${esc(g.title)}</b><span class="muted small">${esc(g.subtitle)}</span>
          ${g.minPlayers > 1 ? `<span class="small ${enough ? 'muted' : 'c-warn'}">від ${g.minPlayers} гравців</span>` : ''}</span></button>`;
      }).join('')}</div>`;
    } else {
      h += `<h1>Привіт, ${esc(st.me.name)}!</h1>
        ${ui.wait('Хост обирає гру', 'user')}
        <h2>Тут ${st.players.length}</h2>${playersCard()}
        <button class="ghost" data-pg="leave">Вийти</button>`;
    }
    return h + `</div>`;
  }

  /** Side drawer (menu button or the back gesture): everything that is not part of the current screen lives here. */
  function drawerView() {
    const g = st.game && games[st.game];
    const info = st.game ? gameInfo(st.game) : null;
    const host = st.isHost;
    // two taps for anything that interrupts other people
    const conf = (act, label, sure) => armed === act
      ? `<button class="danger" data-pg="${act}">${sure}</button>`
      : `<button class="soft" data-pg="arm" data-v="${act}">${label}</button>`;
    let h = `<div class="dr-head">
      <b>${info ? `${ico('folder')} ${esc(info.title)}` : esc(appInfo().name)}</b>
      <button class="mini ghost" data-pg="menu" aria-label="Закрити">${ico('close')}</button>
    </div><div class="dr-body">`;

    if (info) {
      let s = '';
      if (rulesOf(st.game)) s += `<button class="soft" data-pg="rules">${ico('book')} Правила</button>`;
      if (host && g && g.menu) s += gameMenu(g.menu(api));
      if (host) s += notStarted()
        ? `<button class="soft" data-pg="exitGame">${ico('home')} Головне меню</button>`
        : conf('exitGame', `${ico('home')} Головне меню`, 'Точно? Завершити гру й повернутись');
      if (s) h += `<section><h3>Гра</h3>${s}</section>`;
    }

    h += `<section><h3>Гравці · ${st.players.length}</h3>${playersCard()}`;
    if (host && st.game) {
      h += `<button class="soft" data-pg="invite">${showInvite ? 'Сховати QR' : `${ico('qr')} Запросити гравців`}</button>`;
      if (showInvite) h += inviteCard(false);
    }
    if (!host) h += conf('leave', `${ico('door')} Вийти з кімнати`, 'Точно? Вийти з кімнати');
    h += `</section>`;

    if (host) {
      const sw = (k, label) => `<label class="toggle"><input type="checkbox" class="switch" data-pg="snd" data-v="${k}" ${sndOn(k) ? 'checked' : ''}><span>${label}</span></label>`;
      h += `<section><h3>Звук</h3>
        ${sw('music', `${ico('music')} Фонова музика`)}${sw('sfx', `${ico('bell')} Звукові ефекти`)}</section>`;
    }

    if (isHostPage && window.PGApp) {
      let v = '', nv = '';
      try { v = PGApp.version(); nv = PGApp.updateAvailable(); } catch (_) {}
      h += `<section><h3>Застосунок</h3>${nv
        ? `<button data-pg="update">${ico('up')} Оновити до v${esc(nv)}</button>`
        : `<button class="soft" data-pg="update">${ico('refresh')} Перевірити оновлення</button>`}
        ${conf('quit', `${ico('power')} Вийти`, 'Точно? Гра для всіх закінчиться')}
        ${v ? `<div class="muted small">Версія v${esc(v)}</div>` : ''}</section>`;
    }
    return h + `</div>`;
  }

  /** Shortcuts a game's menu() may list instead of spelling the item out. */
  const MENU_ITEMS = { newGame: { t: 'newGame', label: 'Нова гра' } };

  /**
   * Host-only items of the current game: menu() returns [{ t, label }] or a MENU_ITEMS key. Each one asks "Точно?"
   * on the first tap and sends { t } to the game on the second, then the drawer closes.
   */
  function gameMenu(items) {
    return (items || []).map(it => (typeof it === 'string' ? MENU_ITEMS[it] : it)).filter(Boolean).map(it => {
      const key = 'g:' + it.t;
      return armed === key
        ? `<button class="danger" data-pg="gameAct" data-v="${esc(it.t)}">Точно? ${esc(it.label)}</button>`
        : `<button class="soft" data-pg="arm" data-v="${esc(key)}">${esc(it.label)}</button>`;
    }).join('');
  }

  function setDrawer(open) {
    if (menuOpen === open) return;
    menuOpen = open; armed = null;
    if (!open) showInvite = false;
    render();
  }

  function render() {
    const g = st && st.game ? games[st.game] : null;
    const info = st && st.game ? gameInfo(st.game) : null;
    document.body.dataset.game = document.body.dataset.theme = (st && st.game) || '';
    updateBg();
    if (st && st.app && document.title !== st.app.name) document.title = st.app.name;
    $('#logo').innerHTML = info ? ico('folder') + esc(info.title) : brand();
    $('#hostTag').hidden = !(st && st.isHost);
    const joined = !!(pid && st && st.me);
    $('#menuBtn').hidden = !joined;
    if (!joined) menuOpen = false;
    $('#infoBtn').hidden = !(info && rulesOf(st.game));
    const rulesEl = $('#rules');
    rulesEl.hidden = !(rulesOpen && info && rulesOf(st.game));
    if (!rulesEl.hidden) {
      const html = `<div class="rules-card" role="dialog" aria-modal="true">
        <div class="rules-head"><b>${ico('book')} ${esc(info.title)} — правила</b><button class="mini ghost" data-pg="rules" aria-label="Закрити">${ico('close')}</button></div>
        <div class="rules-body">${rules[st.game]}</div></div>`;
      if (rulesEl._pgHtml !== html) { rulesEl._pgHtml = html; rulesEl.innerHTML = html; }
    }
    $('#extra').innerHTML = g && g.extra ? (g.extra(api) || '') : '';

    const drawer = $('#drawer');
    drawer.classList.toggle('open', menuOpen);
    drawer.setAttribute('aria-hidden', String(!menuOpen));
    document.body.classList.toggle('drawer-open', menuOpen);
    if (menuOpen) keepInput($('#drawerInner'), drawerView());

    let html = '', bar = '';
    const needsJoin = !pid || !st || !st.me;
    if (needsJoin) {
      if (pid && !st) return; // waiting for state after resume
      html = joinView();
    } else if (!st.game) {
      html = lobbyView();
    } else if (!g) {
      html = loading[st.game] === 'fail'
        ? `<div class="card center">Не вдалося завантажити гру. Онови сторінку.</div>`
        : `<div class="center muted" style="margin-top:30vh"><span class="spinner"></span></div>`;
    } else {
      const out = g.render(api) || '';
      if (typeof out === 'string') html = out; else { html = out.html || ''; bar = out.bar || ''; }
    }
    keepInput($('#app'), html);
    keepInput($('#barInner'), bar);
    $('#bar').hidden = !bar;
    document.body.classList.toggle('with-bar', !!bar);

    // a new screen (another game or phase) slides in; re-renders within a screen don't
    const screen = needsJoin ? 'join' : !st.game ? 'lobby' : st.game + '/' + ((st.g && st.g.phase) || '');
    if (screen !== lastScreen) { lastScreen = screen; enterScreen(); }
  }
  let lastScreen = '', enterTimer = 0;
  function enterScreen() {
    const app = $('#app');
    app.classList.remove('enter');
    void app.offsetWidth;
    app.classList.add('enter');
    clearTimeout(enterTimer);
    enterTimer = setTimeout(() => app.classList.remove('enter'), 900);
  }

  /**
   * Re-render by merging the new markup into the DOM instead of replacing it. Elements that are still there keep
   * their state (focus, typed text, running animations); changed classes animate through CSS transitions; only
   * elements that really appeared play their entrance animation. Identical markup leaves the DOM alone, so a state
   * push that changes nothing on this screen (someone else tapped) does not eat a tap.
   *
   * Nodes match by position, tag and first class. Give an element data-k when its identity matters (a different
   * key means a new element, e.g. the next character card should pop in again); an id works as a key too.
   * data-keep leaves an element's children to the game (a canvas it mounts itself).
   */
  function keepInput(container, html) {
    if (container._pgHtml === html) return;
    container._pgHtml = html;
    // typed text survives even if its input had to be re-created
    const saved = {};
    container.querySelectorAll('input[id]').forEach(i => { saved[i.id] = [i, i.value]; });
    const a = document.activeElement;
    const focusId = a && a.id && a.tagName === 'INPUT' && container.contains(a) ? a.id : null;
    const sel = focusId ? [a.selectionStart, a.selectionEnd] : null;
    const t = document.createElement('template');
    t.innerHTML = html;
    patchChildren(container, t.content);
    for (const id in saved) {
      const el = document.getElementById(id);
      if (el && el !== saved[id][0] && !el.dataset.fresh && el.type !== 'checkbox') el.value = saved[id][1];
    }
    if (focusId && document.activeElement !== saved[focusId][0]) {
      const el = document.getElementById(focusId);
      if (el) { el.focus(); try { el.setSelectionRange(sel[0], sel[1]); } catch (_) {} }
    }
  }
  PG.keepInput = keepInput;

  const keyOf = n => (n.nodeType === 1 ? n.getAttribute('data-k') || n.id || '' : '');
  const kindOf = n => (n.nodeType === 1 ? n.nodeName + '.' + ((n.getAttribute('class') || '').trim().split(/\s+/)[0]) : n.nodeName);
  const sameNode = (a, b) => kindOf(a) === kindOf(b) && keyOf(a) === keyOf(b);

  function patchChildren(live, next) {
    let l = live.firstChild;
    for (let n = next.firstChild, after; n; n = after) {
      after = n.nextSibling;
      if (l && sameNode(l, n)) { patchNode(l, n); l = l.nextSibling; continue; }
      let m = l && l.nextSibling;
      while (m && !sameNode(m, n)) m = m.nextSibling;
      if (m) { live.insertBefore(m, l); patchNode(m, n); } // it moved up: the ones in between were removed or moved
      else live.insertBefore(n, l);                        // new
    }
    while (l) { const x = l.nextSibling; live.removeChild(l); l = x; }
  }

  function patchNode(l, n) {
    if (l.nodeType !== 1) {
      if (l.nodeValue !== n.nodeValue) {
        l.nodeValue = n.nodeValue;
        const p = l.parentNode;
        // a score that changed jumps
        if (p && p.classList && p.classList.contains('pts')) { p.classList.remove('bump'); void p.offsetWidth; p.classList.add('bump'); }
      }
      return;
    }
    for (const at of [...l.attributes]) if (!n.hasAttribute(at.name)) l.removeAttribute(at.name);
    for (const at of n.attributes) if (l.getAttribute(at.name) !== at.value) l.setAttribute(at.name, at.value);
    if (l.tagName === 'INPUT') {
      if (l.type === 'checkbox' || l.type === 'radio') l.checked = n.hasAttribute('checked');
      else if (l.dataset.fresh) l.value = n.getAttribute('value') || '';
    }
    if (!l.hasAttribute('data-keep')) patchChildren(l, n);
  }

  /**
   * Back gesture of the host app (called from MainActivity). Peels one layer: rules, drawer, a game's own screen;
   * a game that has not started yet goes straight back to the main menu; otherwise slides the drawer in (the edge swipe "pulls" it out; leaving the app is its «Вийти»). Returns true
   * when it handled it — false only before joining, so the app goes to the background.
   */
  PG.back = function () {
    if (rulesOpen) { rulesOpen = false; render(); return true; }
    if (menuOpen) { setDrawer(false); return true; }
    if (bigQr) { bigQr = false; render(); return true; }
    const g = st && st.game ? games[st.game] : null;
    if (g && g.back && g.back(api)) return true;
    if (st && st.isHost && notStarted()) { shellAct('exitGame'); return true; }
    if (st && st.me) { setDrawer(true); return true; }
    return false;
  };

  // Swipe the drawer back to the right to close it; the panel follows the finger.
  (function swipeToClose() {
    let x0 = null, y0 = 0, dx = 0, horiz = null;
    const panel = () => $('#drawer .drawer-panel');
    document.addEventListener('touchstart', e => {
      if (!menuOpen || !e.target.closest('#drawer')) return;
      x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; dx = 0; horiz = null;
    }, { passive: true });
    document.addEventListener('touchmove', e => {
      if (x0 == null) return;
      const t = e.touches[0];
      if (horiz == null && Math.abs(t.clientX - x0) + Math.abs(t.clientY - y0) > 8) horiz = Math.abs(t.clientX - x0) > Math.abs(t.clientY - y0);
      if (!horiz) return;
      dx = Math.max(0, t.clientX - x0);
      const p = panel(); p.style.transition = 'none'; p.style.transform = `translateX(${dx}px)`;
    }, { passive: true });
    document.addEventListener('touchend', () => {
      if (x0 == null) return;
      x0 = null;
      const p = panel(); p.style.transition = ''; p.style.transform = '';
      if (horiz && dx > 70) setDrawer(false);
    });
  })();

  // ---------- actions ----------
  function join() {
    const input = $('#name');
    const name = (input && input.value || '').trim();
    if (!name) { input && input.focus(); return; }
    store.set('pg.name', name);
    joining = true;
    send({ t: 'join', name, pid: pid || '' });
    keepAwake();
    render();
  }

  function shellAct(act, el) {
    switch (act) {
      case 'join': return join();
      case 'rules': rulesOpen = !rulesOpen; if (rulesOpen) { menuOpen = false; armed = null; } return render();
      case 'menu': return setDrawer(!menuOpen);
      case 'snd': return send({ t: 'snd', [el.dataset.v]: !sndOn(el.dataset.v) });
      case 'bigQr': bigQr = !bigQr; return render();
      case 'arm': armed = el.dataset.v; return render();
      case 'kick': {
        const key = 'kick' + el.dataset.v;
        if (armed !== key) { armed = key; render(); setTimeout(() => { if (armed === key) { armed = null; render(); } }, 3000); return; }
        armed = null; return send({ t: 'kick', pid: el.dataset.v });
      }
      case 'select': return send({ t: 'select', game: el.dataset.v });
      case 'exitGame': armed = null; menuOpen = false; return send({ t: 'exitGame' });
      case 'leave':
        armed = null; menuOpen = false;
        send({ t: 'leave' }); pid = null; store.set(K + 'pid', null);
        st = null;
        return render();
      case 'gameAct': api.send({ t: el.dataset.v }); return setDrawer(false);
      case 'quit': try { window.PGApp && PGApp.quit(); } catch (_) {} return;
      case 'update': setDrawer(false); try { window.PGApp && PGApp.checkUpdate(); } catch (_) {} return;
      case 'invite': showInvite = !showInvite; return render();
      case 'net': return send({ t: 'relay', on: el.dataset.v === 'relay' });
      case 'editRelay': showRelayUrl = true; return render();
      case 'saveRelay': {
        const v = ($('#relayUrl') || {}).value || '';
        showRelayUrl = false;
        send({ t: 'relayUrl', url: v.trim() });
        return render();
      }
    }
  }

  document.addEventListener('click', e => {
    keepAwake();
    if (rulesOpen && e.target.id === 'rules') { rulesOpen = false; return render(); }
    if (menuOpen && e.target.id === 'drawer') return setDrawer(false);
    const el = e.target.closest('[data-pg]');
    if (el) { if (!el.disabled) shellAct(el.dataset.pg, el); return; }
    const g = st && st.game ? games[st.game] : null;
    if (g && g.click) g.click(e, api);
  });

  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && rulesOpen) { rulesOpen = false; return render(); }
    if (e.key === 'Escape' && menuOpen) return setDrawer(false);
    if (e.key === 'Enter' && e.target.id === 'name') return join();
    if (e.key === 'Enter' && e.target.id === 'relayUrl') return shellAct('saveRelay');
    const g = st && st.game ? games[st.game] : null;
    if (g && g.key) g.key(e, api);
  });

  document.addEventListener('DOMContentLoaded', () => {
        // the host app's WebView may play music before the first tap too
    if (isHostPage) unlockAudio();
    open();
  });
})();
