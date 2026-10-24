// Screens of a detective case (shared by every case). A case's own game.js is one line:
//     PG.caseKit('<id>');
// The server side is casekit's CaseSession; the state it sends is `api.g` (see CaseEngine.publicState).
//
// Tabs (bottom bar): Матеріали (documents) · Запит · Питання · Нотатки · Журнал. Documents are loaded into an iframe
// from /static/g/doc/<ID>.html, which the phone serves only once the case has opened them.
(function () {
  'use strict';
  const { esc, store, ui } = PG.api;
  const base = location.pathname.replace(/[^/]*$/, '');

  const TABS = [
    { id: 'docs', ico: '📄', label: 'Матеріали' },
    { id: 'req', ico: '🔎', label: 'Запит' },
    { id: 'q', ico: '❓', label: 'Питання' },
    { id: 'notes', ico: '📝', label: 'Нотатки' },
    { id: 'log', ico: '📜', label: 'Журнал' },
  ];

  const fmtTime = t => new Date(t * 1000).toLocaleTimeString('uk-UA', { hour: '2-digit', minute: '2-digit' });
  const fmtElapsed = s => {
    s = Math.max(0, Math.floor(s));
    const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), c = s % 60;
    return (h ? h + ':' : '') + String(m).padStart(2, '0') + ':' + String(c).padStart(2, '0');
  };
  // «…; ✓ пункт; ✗ пункт» → every item on its own line
  const verdictHtml = text => esc(text).replace(/;? ?([✓✗]) /g, '<br>$1 ');

  function caseKit(id) {
    const K = 'ck.' + base + id + '.';
    const read = (k, d) => { try { const v = store.get(K + k); return v == null ? d : JSON.parse(v); } catch (_) { return d; } };
    const write = (k, v) => store.set(K + k, JSON.stringify(v));

    let tab = 'docs';
    let panes = [null, null];       // open documents: the main viewer and the optional second one ("compare")
    let focus = 0;                  // the viewer the next document goes to
    let compare = false;
    let viewing = false;            // phone: the viewer is on screen instead of the list
    let filter = '';
    let reqType = null;
    let unread = 0;
    let lastSeq = null;
    let seen = new Set(read('seen', []));
    let startedAt = read('start', null);
    let pendingDel = 0;
    let finalDismissed = read('final', false);
    const drafts = {};              // question id -> { field id -> text }
    let reqText = { target: '', details: '' };
    let flash = null;               // the last reply to my own request, shown above the form
    let timerEl = null;

    // ---------- helpers ----------
    const g = () => PG.api.g;
    const me = () => (PG.api.me && PG.api.me.name) || '';
    const docOf = did => (g().documents || []).find(d => d.id === did);

    function speaker(kind) {
      const s = g();
      const key = (s.speaker_by_type && s.speaker_by_type[kind]) || s.default_speaker;
      return (s.speakers && s.speakers[key]) || null;
    }
    function said(sp, html, cls) {
      const img = sp && sp.img ? `<img class="ck-avatar" src="${base}static/games/${id}/${esc(sp.img)}" alt="" title="${esc(sp.name || '')}">` : '';
      return `<div class="ck-said ${cls || ''}">${img}<div class="ck-said-text">${html}</div></div>`;
    }
    function linkDocs(text) {
      const have = new Set((g().documents || []).map(d => d.id));
      return esc(text).replace(/\b([ABCD]\d{2}[a-z]?)\b/g, m => have.has(m) ? `<span class="ck-link" data-act="doc" data-id="${m}">${m}</span>` : m);
    }

    function toast(html, bad) {
      let box = document.getElementById('ck-toasts');
      if (!box) { box = document.createElement('div'); box.id = 'ck-toasts'; document.body.appendChild(box); }
      const el = document.createElement('div');
      el.className = 'ck-toast' + (bad ? ' bad' : '');
      el.innerHTML = html;
      box.appendChild(el);
      setTimeout(() => el.remove(), 6000);
    }

    function openDoc(did, pane) {
      if (!docOf(did)) return;
      const p = pane == null ? focus : pane;
      panes[p] = did;
      tab = 'docs';
      viewing = true;
      seen.add(did);
      write('seen', [...seen]);
    }

    // ---------- events from the server ----------
    function onState(prev, s) {
      if (startedAt != null && s.started_at !== startedAt) {
        // the host started the case over: forget what this device has seen
        seen = new Set(); panes = [null, null]; viewing = false; flash = null; unread = 0;
        write('seen', []); finalDismissed = false; write('final', false);
      }
      if (startedAt !== s.started_at) { startedAt = s.started_at; write('start', startedAt); }
      const ev = s.ev;
      if (ev && ev.seq !== lastSeq) {
        const first = lastSeq === null && !prev;
        lastSeq = ev.seq;
        if (!first) onEvent(ev, s);
      } else if (lastSeq === null) {
        lastSeq = ev ? ev.seq : 0;
      }
    }

    function onEvent(ev) {
      const mine = ev.player === me();
      if (ev.kind === 'reset') { toast('Хост почав справу з нуля'); return; }
      if (!mine && tab !== 'log') unread++;
      if (ev.kind === 'request') {
        if (mine) flash = { ok: ev.ok, text: ev.text, kind: lastReqType };
        else toast(`${esc(ev.player)}: ${ev.ok ? 'новий матеріал у справі' : 'запит без результату'}`, !ev.ok);
        if (ev.ok && ev.new && ev.new.length && mine) openDoc(ev.new[0]);
      } else if (ev.kind === 'answer') {
        toast(`${mine ? '' : esc(ev.player) + ': '}${esc(ev.text)}`.slice(0, 400), !ev.ok);
        if (ev.ok && ev.new && ev.new.length) openDoc(ev.new[0]);
      } else if (ev.kind === 'hint' && !mine) {
        toast(`${esc(ev.player)} взяв(-ла) підказку`);
      }
      if (!mine) PG.api.vibrate(30);
    }
    let lastReqType = null;

    // ---------- views ----------
    function head() {
      const s = g();
      const end = s.finished_at || Date.now() / 1000;
      return `<div class="ck-head">
        <div class="ck-title">${esc(s.title)}</div>
        <div class="ck-sub"><span class="ck-stage">${esc(s.stage_name || '')}</span>
          <span class="ck-stats"><span title="Час розслідування">⏱ <b class="ck-timer" data-t0="${s.started_at}" data-t1="${s.finished_at || ''}">${fmtElapsed(end - s.started_at)}</b></span>
          <span title="Бали">★ <b>${s.score}</b></span></span></div>
      </div>`;
    }

    function docsView() {
      const s = g();
      const q = filter.trim().toLowerCase();
      const groups = {};
      for (const d of s.documents) {
        if (q && !(`${d.id} ${d.title}`.toLowerCase().includes(q))) continue;
        (groups[d.cat] = groups[d.cat] || []).push(d);
      }
      const open = new Set(panes.filter(Boolean));
      const list = Object.keys(s.categories).filter(c => groups[c]).map(c => `
        <div class="ck-group" data-k="grp-${esc(c)}"><h3>${esc(s.categories[c])}</h3>
          ${groups[c].map(d => `<button class="ck-doc ${open.has(d.id) ? 'on' : ''}" data-act="doc" data-id="${esc(d.id)}" data-k="d-${esc(d.id)}">
            <span class="ck-id">${esc(d.id)}</span><span class="ck-dt">${esc(d.title)}</span>${seen.has(d.id) ? '' : '<span class="tag accent">нове</span>'}
          </button>`).join('')}
        </div>`).join('');
      const unseen = s.documents.filter(d => !seen.has(d.id)).length;

      const pane = i => {
        const did = panes[i];
        const d = did && docOf(did);
        if (!d) {
          return `<div class="ck-pane ${i === 1 ? 'second' : ''} ${focus === i && compare ? 'focus' : ''}" data-k="pane${i}">
            <div class="ck-empty muted">${i === 1 ? 'Друге вікно для порівняння.<br>Торкніться тут, потім — документа.' : 'Оберіть документ у списку.'}</div></div>`;
        }
        return `<div class="ck-pane ${i === 1 ? 'second' : ''} ${focus === i && compare ? 'focus' : ''}" data-k="pane${i}" data-act="focus" data-i="${i}">
          <div class="ck-vbar"><button class="mini soft ck-back" data-act="list">← Матеріали</button>
            <b class="ck-vt">${esc(d.id)} · ${esc(d.title)}</b></div>
          <iframe class="ck-frame" title="${esc(d.title)}" src="${base}static/g/doc/${encodeURIComponent(d.id)}.html"></iframe></div>`;
      };

      return `<div class="ck-docs ${viewing ? 'viewing' : ''} ${compare ? 'cmp' : ''}">
        <div class="ck-listcol">
          <div class="ck-listhead"><span class="muted small">${s.documents.length} документів${unseen ? ` · нових: ${unseen}` : ''}</span>
            <button class="mini soft ck-cmpbtn" data-act="compare">${compare ? '✕ Закрити друге вікно' : '⇆ Порівняти'}</button></div>
          <input id="ck-filter" type="text" placeholder="Пошук у назвах…" value="${esc(filter)}" autocomplete="off">
          <div class="ck-list">${list || '<div class="muted small">Нічого не знайдено.</div>'}</div>
        </div>
        <div class="ck-viewcol">${pane(0)}${compare ? pane(1) : ''}</div>
      </div>`;
    }

    function entryHtml(e) {
      if (e.kind === 'request') {
        const q = `${esc(e.type_label)}: <b>${esc(e.target)}</b>${e.details ? ' — ' + esc(e.details) : ''}`;
        return `<div class="ck-entry"><div class="ck-meta">${fmtTime(e.at)} · ${esc(e.player)}</div><div class="ck-q">${q}</div>
          ${said(speaker(e.type), linkDocs(e.text), e.ok ? 'ok' : 'bad')}</div>`;
      }
      if (e.kind === 'answer') {
        const given = Object.values(e.answers || {}).filter(Boolean).map(a => `«${esc(a)}»`).join(' · ');
        return `<div class="ck-entry"><div class="ck-meta">${fmtTime(e.at)} · ${esc(e.player)} · відповідь на питання</div>
          <div class="ck-q">${esc(e.title)}</div>${given ? `<div class="ck-q small muted">${given}</div>` : ''}
          ${said(speaker(), verdictHtml(e.text), e.ok ? 'ok' : 'bad')}</div>`;
      }
      if (e.kind === 'hint') {
        return `<div class="ck-entry"><div class="ck-meta">${fmtTime(e.at)} · ${esc(e.player)} · підказка</div>${said(speaker(), esc(e.text))}</div>`;
      }
      return '';
    }

    // Host only: the paraphrase model (understands requests worded differently from the documents).
    function modelCard() {
      const m = PG.api.isHost && PG.api.mod;
      if (!m) return '';
      const on = (v) => `data-act="model" data-v="${v}"`;
      let body;
      if (m.state === 'READY') {
        body = `<div class="c-good">✓ Розумне розуміння запитів увімкнено</div><button class="mini soft" ${on(0)}>Вимкнути</button>`;
      } else if (m.state === 'DOWNLOADING' || m.state === 'LOADING') {
        body = `<div>${esc(m.message || '…')}</div><div class="bar"><i style="width:${Math.round((m.progress || 0) * 100)}%"></i></div>
          <button class="mini soft" ${on(0)}>Скасувати</button>`;
      } else {
        body = `<div class="muted small">Зараз запити розпізнаються за ключовими словами. Модель дозволяє гравцям формулювати
          своїми словами. На Wi-Fi вмикається саме; завантажується один раз (≈${m.sizeMb || 152} МБ), далі працює на цьому телефоні без інтернету.</div>
          ${m.state === 'ERROR' ? `<div class="c-bad small">${esc(m.message)}</div>` : ''}
          <button class="soft" ${on(1)}>🧠 ${m.state === 'ERROR' ? 'Спробувати ще' : 'Увімкнути'}</button>`;
      }
      return `<div class="card flat stack ck-model" data-k="model">${body}</div>`;
    }

    function reqView() {
      const s = g();
      const types = s.request_types;
      if (!reqType || !types[reqType]) reqType = Object.keys(types)[0];
      const t = types[reqType];
      const recent = s.log.filter(e => e.kind === 'request').slice(-6).reverse();
      return `<div class="stack">
        ${modelCard()}
        <div class="card stack">
          <div class="ck-types">${Object.keys(types).map(k => `<button class="chip ${k === reqType ? 'on' : ''}" data-act="rtype" data-v="${k}">${esc(types[k].label)}</button>`).join('')}</div>
          <label class="ck-field">${esc(t.target)}
            <input id="ck-target" type="text" value="${esc(reqText.target)}" autocomplete="off" maxlength="300"></label>
          ${t.details ? `<label class="ck-field">${esc(t.details)}
            <textarea id="ck-details" rows="3" maxlength="600">${esc(reqText.details)}</textarea></label>` : ''}
          <button class="big" data-act="send">Надіслати запит</button>
          <p class="muted small">Відповідь бачать усі гравці. Номери, адреси й прізвища беріть із документів справи.</p>
        </div>
        ${flash ? `<div class="ck-flash ${flash.ok ? 'ok' : 'bad'}">${said(speaker(reqType), linkDocs(flash.text))}</div>` : ''}
        <h2>Останні запити</h2>
        <div class="ck-history">${recent.map(entryHtml).join('') || '<p class="muted small">Поки що запитів не було.</p>'}</div>
      </div>`;
    }

    function lastVerdict(qid) {
      const e = g().log.slice().reverse().find(x => x.kind === 'answer' && x.question === qid);
      return e && !e.ok ? `<div class="ck-flash bad">${said(speaker(), verdictHtml(e.text))}</div>` : '';
    }

    function qView() {
      const s = g();
      const q = s.question;
      if (!q) {
        return s.finished_at
          ? `<div class="card stack"><h2>Справу розкрито</h2><div class="headline">${esc(s.rank || '')}</div>
              <p class="muted">Бали: ${s.score}. Прочитайте матеріали фіналу в розділі «Матеріали».</p></div>`
          : '<div class="card flat center muted">Зараз питань немає — вивчайте матеріали й надсилайте запити.</div>';
      }
      const hints = s.hints[q.id] || [];
      const d = drafts[q.id] || (drafts[q.id] = {});
      return `<div class="card stack" data-k="q-${esc(q.id)}">
        <h2>${esc(q.title)}</h2>
        ${said(speaker(), esc(q.intro), 'intro')}
        ${q.fields.map(f => `<label class="ck-field">${esc(f.label)}
          ${f.multiline
            ? `<textarea id="ck-f-${esc(f.id)}" data-f="${esc(f.id)}" rows="4" placeholder="${esc(f.placeholder || '')}">${esc(d[f.id] || '')}</textarea>`
            : `<input id="ck-f-${esc(f.id)}" data-f="${esc(f.id)}" type="text" autocomplete="off" placeholder="${esc(f.placeholder || '')}" value="${esc(d[f.id] || '')}">`}
        </label>`).join('')}
        ${lastVerdict(q.id)}
        ${hints.map((h, i) => `<div class="ck-hint">${said(speaker(), `<b>Підказка ${i + 1}.</b> ${esc(h)}`)}</div>`).join('')}
        <div class="row">
          <button class="grow" data-act="answer">Відповісти</button>
          <button class="soft" data-act="hint" ${hints.length >= q.hints_total ? 'disabled' : ''}>Підказка (${hints.length}/${q.hints_total})</button>
        </div>
        <p class="muted small">Невдалих спроб: ${q.attempts}. Кожна помилка і підказка знімає бали.</p>
      </div>`;
    }

    function notesView() {
      const notes = g().notes.slice().sort((a, b) => (b.pinned - a.pinned) || (b.at - a.at));
      return `<div class="stack">
        <div class="card stack">
          <textarea id="ck-note" rows="3" placeholder="Версія, підозра, час, номер… Нотатки бачать усі."></textarea>
          <button data-act="note">Додати нотатку</button>
        </div>
        ${notes.map(n => `<div class="ck-note ${n.pinned ? 'pinned' : ''}" data-k="n-${n.id}">${linkDocs(n.text)}
          <div class="ck-notemeta"><span>${esc(n.player)} · ${fmtTime(n.at)}</span>
            <span><button class="mini soft" data-act="pin" data-id="${n.id}">${n.pinned ? '📌' : '📍'}</button>
              <button class="mini soft" data-act="del" data-id="${n.id}">✕</button></span></div></div>`).join('')
          || '<p class="muted small">Нотаток ще немає. Записуйте версії, часи, номери — вони спільні для всієї групи.</p>'}
      </div>`;
    }

    function logView() {
      const items = g().log.slice().reverse();
      return `<div class="ck-history">${items.map(entryHtml).join('') || '<p class="muted small">Журнал порожній.</p>'}</div>`;
    }

    function finalView() {
      const s = g();
      if (!s.finished_at || finalDismissed) return '';
      return `<div class="ck-final"><div class="card hero stack center">
        <span class="tag accent">справу розкрито</span>
        <h1>${esc(s.rank || 'Справу розкрито')}</h1>
        <p>Бали: ${s.score}</p>
        <p class="muted">У теці з'явилися протокол обшуку, експертиза, допит підозрюваної та лист слідчого.</p>
        <button class="big" data-act="final">До матеріалів</button></div></div>`;
    }

    function render() {
      const s = g();
      if (!s) return '';
      const sec = (t, html) => `<section class="ck-sec ${tab === t ? 'on' : ''}" data-k="sec-${t}">${html}</section>`;
      const html = `<div class="ck" data-k="ck">${head()}
        ${sec('docs', docsView())}${sec('req', reqView())}${sec('q', qView())}${sec('notes', notesView())}${sec('log', logView())}
        ${finalView()}</div>`;
      const dot = t => (t === 'q' && s.question) ? '<i class="ck-dot"></i>' : (t === 'log' && unread ? `<i class="ck-dot n">${unread > 9 ? '9+' : unread}</i>` : '');
      const bar = TABS.map(t => `<button class="ck-tab ${tab === t.id ? 'on' : ''}" data-act="tab" data-v="${t.id}"><b>${t.ico}</b><span>${t.label}</span>${dot(t.id)}</button>`).join('');
      return { html, bar };
    }

    // ---------- input ----------
    function collectAnswers() {
      const out = {};
      document.querySelectorAll('[data-f]').forEach(el => { out[el.dataset.f] = el.value.trim(); });
      return out;
    }

    function click(e, api) {
      const el = e.target.closest('[data-act]');
      if (!el) return;
      const act = el.dataset.act;
      const s = g();
      switch (act) {
        case 'tab':
          tab = el.dataset.v;
          if (tab === 'log') unread = 0;
          if (tab === 'docs') viewing = viewing && !!panes[0];
          return api.render();
        case 'doc':
          // a link inside a note or a reply (data-act on a <span>) works the same
          openDoc(el.dataset.id);
          return api.render();
        case 'list': viewing = false; return api.render();
        case 'focus': focus = +el.dataset.i; if (compare) api.render(); return;
        case 'compare':
          compare = !compare;
          if (!compare) { panes[1] = null; focus = 0; } else focus = 1;
          return api.render();
        case 'model': return api.sendMod({ a: 'model', on: el.dataset.v === '1' });
        case 'rtype': reqType = el.dataset.v; return api.render();
        case 'send': {
          const target = (document.getElementById('ck-target') || {}).value || '';
          const details = (document.getElementById('ck-details') || {}).value || '';
          if (!target.trim()) { toast('Вкажіть, про що запит', true); return; }
          reqText = { target: '', details: '' };
          lastReqType = reqType;
          flash = null;
          api.send({ a: 'request', type: reqType, target, details });
          return api.render();
        }
        case 'answer':
          if (!s.question) return;
          return api.send({ a: 'answer', question: s.question.id, answers: collectAnswers() });
        case 'hint': return s.question && api.send({ a: 'hint', question: s.question.id });
        case 'note': {
          const ta = document.getElementById('ck-note');
          if (ta && ta.value.trim()) { api.send({ a: 'note_add', text: ta.value }); ta.value = ''; }
          return;
        }
        case 'pin': return api.send({ a: 'note_pin', id: +el.dataset.id });
        case 'del': {
          // no system dialog: a second tap within 3 s confirms
          const now = Date.now();
          if (now - pendingDel < 3000) { pendingDel = 0; return api.send({ a: 'note_del', id: +el.dataset.id }); }
          pendingDel = now;
          return toast('Натисніть ✕ ще раз, щоб видалити нотатку');
        }
        case 'final': finalDismissed = true; write('final', true); return api.render();
      }
    }

    // typed text lives in the DOM, but mirror it so a re-render of a re-created field never loses it
    document.addEventListener('input', e => {
      const t = e.target;
      if (!t || !t.id || !PG.api.st || PG.api.st.game !== id) return;
      if (t.id === 'ck-filter') { filter = t.value; PG.api.render(); const f = document.getElementById('ck-filter'); if (f) f.focus(); return; }
      if (t.id === 'ck-target') reqText.target = t.value;
      if (t.id === 'ck-details') reqText.details = t.value;
      if (t.dataset.f && g().question) (drafts[g().question.id] = drafts[g().question.id] || {})[t.dataset.f] = t.value;
    });

    // the investigation clock ticks between state pushes
    setInterval(() => {
      document.querySelectorAll('.ck-timer').forEach(el => {
        const t0 = +el.dataset.t0, t1 = el.dataset.t1 ? +el.dataset.t1 : Date.now() / 1000;
        el.textContent = fmtElapsed(t1 - t0);
      });
    }, 1000);

    return {
      render, click, onState,
      menu: () => [{ t: 'newGame', label: '🔁 Почати справу з нуля' }],
      back: () => {
        if (tab === 'docs' && viewing) { viewing = false; PG.api.render(); return true; }
        if (tab !== 'docs') { tab = 'docs'; PG.api.render(); return true; }
        return false;
      },
    };
  }

  PG.caseKit = id => {
    PG.register(id, caseKit(id));
    // wide screens (tablet, PC): the page may use the room for documents next to the panel
    const st = document.createElement('style');
    st.textContent = `body[data-game=${id}] { max-width: 1180px; }`;
    document.head.appendChild(st);
  };
})();
