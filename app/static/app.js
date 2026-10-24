"use strict";

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};

let ws = null;
let state = null;
let name = new URLSearchParams(location.search).get("name") || store.get("dg_name", "");
let seen = new Set(store.get("dg_seen", []));
let panes = [{ doc: null }];
let focusedPane = 0;
let questionKey = "";

/* ---------- підключення ---------- */
function connect() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${location.host}/ws?name=${encodeURIComponent(name)}`);
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === "state") {
      const first = !state;
      state = msg.state;
      render(first);
      if (msg.event) onEvent(msg.event);
    }
  };
  ws.onclose = () => { toast("Зв'язок із сервером втрачено. Перепідключення…", true); setTimeout(connect, 2000); };
}
const send = (obj) => ws && ws.readyState === 1 && ws.send(JSON.stringify(obj));

/* ---------- події ---------- */
function onEvent(ev) {
  const me = ev.player === name;
  if (!me && ["request", "answer", "hint"].includes(ev.kind) && !(isMobile() && mtab === "log")) { logUnread++; updateLogBadge(); }
  if (ev.kind === "join" && !me) toast(`${esc(ev.player)} приєднується до групи`);
  if (ev.kind === "request") {
    if (me) showRequestResult(ev);
    else toast(`${esc(ev.player)}: ${ev.ok ? "новий матеріал у справі" : "запит без результату"}`, !ev.ok);
    if (ev.ok && ev.new && ev.new.length && me) openDoc(ev.new[0]);
  }
  if (ev.kind === "answer") {
    toast(`${me ? "" : esc(ev.player) + ": "}${esc(ev.text)}`, !ev.ok);
    if (ev.ok && ev.new && ev.new.length) openDoc(ev.new[0]);
  }
  if (ev.kind === "hint" && !me) toast(`${esc(ev.player)} взяв(-ла) підказку`);
  if (ev.kind === "reset") { store.set("dg_seen", []); seen = new Set(); store.set("dg_final_seen", false); location.reload(); }
}

/* ---------- рендер ---------- */
function render(first) {
  $("#case-title").textContent = state.title;
  $("#stage-name").textContent = state.stage_name;
  $("#score").textContent = state.score;
  $("#online").innerHTML = state.online.map((p) => `<span class="who">${esc(p)}</span>`).join("");
  $("#online-drawer").innerHTML = $("#online").innerHTML;
  renderDocs();
  renderRequestTypes(first);
  renderHistory();
  renderLog();
  renderNotes();
  renderQuestion();
  if (first) {
    const startDoc = state.documents.find((d) => !seen.has(d.id)) || state.documents[0];
    if (startDoc) openDoc(startDoc.id);
  }
  if (state.finished_at && !store.get("dg_final_seen", false)) {
    $("#final-rank").textContent = state.rank || "Справу розкрито";
    $("#final-score").textContent = `Бали: ${state.score}`;
    $("#final").classList.remove("hidden");
  }
}

function renderDocs() {
  const filter = $("#doc-filter").value.trim().toLowerCase();
  const groups = {};
  for (const d of state.documents) {
    if (filter && !(`${d.id} ${d.title}`.toLowerCase().includes(filter))) continue;
    (groups[d.cat] ||= []).push(d);
  }
  const current = new Set(panes.map((p) => p.doc));
  $("#doc-count").textContent = `(${state.documents.length})`;
  const unseen = state.documents.filter((d) => !seen.has(d.id)).length;
  $("#menu-badge").textContent = unseen;
  $("#menu-badge").classList.toggle("hidden", !unseen);
  $("#doc-list").innerHTML = Object.keys(state.categories).filter((c) => groups[c]).map((c) => `
    <div class="doc-group"><h3>${esc(state.categories[c])}</h3>
      ${groups[c].map((d) => `
        <div class="doc-item ${current.has(d.id) ? "active" : ""}" data-doc="${esc(d.id)}" title="Додано: ${esc(d.by)}">
          <span class="id">${esc(d.id)}</span><span class="t">${esc(d.title)}</span>
          ${seen.has(d.id) ? "" : '<span class="new">нове</span>'}
        </div>`).join("")}
    </div>`).join("");
}

function renderRequestTypes(first) {
  if (!first) return;
  const sel = $("#req-type");
  sel.innerHTML = Object.entries(state.request_types).map(([k, v]) => `<option value="${k}">${esc(v.label)}</option>`).join("");
  updateRequestLabels();
}

function updateRequestLabels() {
  const t = state.request_types[$("#req-type").value];
  $("#req-target-label").textContent = t.target;
  $("#req-details-label").textContent = t.details || "Деталі";
  $("#req-details-wrap").classList.toggle("hidden", !t.details);
}

function linkDocs(text) {
  const have = new Set(state.documents.map((d) => d.id));
  return esc(text).replace(/\b([ABCD]\d{2}[a-z]?)\b/g, (m) => have.has(m) ? `<span class="doc-link" data-doc="${m}">${m}</span>` : m);
}

const fmtTime = (t) => new Date(t * 1000).toLocaleTimeString("uk-UA", { hour: "2-digit", minute: "2-digit" });

// Хто «говорить» у відповідях: експертизи коментує судмедексперт Квітка, решту — слідчий Стасюк.
const SPEAKERS = {
  stasiuk: { src: "/static/avatars/stasiuk.jpg", name: "кап. Стасюк О.М., слідчий" },
  kvitka: { src: "/static/avatars/kvitka.jpg", name: "Квітка Л.П., судмедексперт" },
};
const speakerFor = (type) => (type === "EXP" ? "kvitka" : "stasiuk");
// «…; ✓ пункт; ✗ пункт» → кожен пункт з нового рядка
const verdictHtml = (text) => esc(text).replace(/;? ?([✓✗]) /g, "<br>$1 ");
function said(who, html, cls = "") {
  const sp = SPEAKERS[who];
  return `<div class="said ${cls}"><img class="avatar" src="${sp.src}" alt="" title="${esc(sp.name)}"><div class="said-text">${html}</div></div>`;
}

function entryHtml(e) {
  if (e.kind === "request") {
    const q = `${esc(e.type_label)}: <b>${esc(e.target)}</b>${e.details ? " — " + esc(e.details) : ""}`;
    return `<div class="entry"><div class="meta">${fmtTime(e.at)} · ${esc(e.player)}</div><div class="q">${q}</div>
      ${said(speakerFor(e.type), linkDocs(e.text), `a ${e.ok ? "ok" : "bad"}`)}</div>`;
  }
  if (e.kind === "answer") {
    const given = Object.values(e.answers || {}).filter(Boolean).map((a) => `«${esc(a)}»`).join(" · ");
    return `<div class="entry"><div class="meta">${fmtTime(e.at)} · ${esc(e.player)} · відповідь на питання</div>
      <div class="q">${esc(e.title)}</div>${given ? `<div class="q small muted">${given}</div>` : ""}
      ${said("stasiuk", verdictHtml(e.text), `a ${e.ok ? "ok" : "bad"}`)}</div>`;
  }
  if (e.kind === "hint") {
    return `<div class="entry"><div class="meta">${fmtTime(e.at)} · ${esc(e.player)} · підказка</div>${said("stasiuk", esc(e.text), "a")}</div>`;
  }
  return "";
}

function renderHistory() {
  const items = state.log.filter((e) => e.kind === "request").slice(-8).reverse();
  $("#req-history").innerHTML = items.map(entryHtml).join("") || '<p class="muted small">Поки що запитів не було.</p>';
}

function renderLog() {
  $("#log").innerHTML = state.log.slice().reverse().map(entryHtml).join("") || '<p class="muted small">Журнал порожній.</p>';
}

function renderNotes() {
  const notes = state.notes.slice().sort((a, b) => (b.pinned - a.pinned) || (b.at - a.at));
  $("#notes").innerHTML = notes.map((n) => `
    <div class="note ${n.pinned ? "pinned" : ""}">${linkDocs(n.text)}
      <div class="note-meta"><span>${esc(n.player)} · ${fmtTime(n.at)}</span>
        <span><button data-pin="${n.id}" title="Закріпити">${n.pinned ? "📌" : "📍"}</button><button data-del="${n.id}" title="Видалити">✕</button></span>
      </div></div>`).join("") || '<p class="muted small">Нотаток ще немає. Записуйте версії, часи, номери — вони спільні для всієї групи.</p>';
}

function renderQuestion() {
  const q = state.question;
  $("#q-dot").classList.toggle("hidden", !q);
  $("#q-dot-m").classList.toggle("hidden", !q);
  const hints = q ? (state.hints[q.id] || []) : [];
  const key = q ? `${q.id}|${q.attempts}|${hints.length}` : `none|${state.stage}`;
  if (key === questionKey) return;
  questionKey = key;
  const box = $("#question-box");
  const drafts = q ? store.get(`dg_draft_${q.id}`, {}) : {};
  if (!q) {
    box.innerHTML = state.finished_at
      ? `<div class="card question"><h2>Справу розкрито</h2><p class="intro">${esc(state.rank || "")} · бали: ${state.score}</p>
         <p class="muted">Прочитайте матеріали фіналу: протокол обшуку, експертизу, допит підозрюваної та лист слідчого.</p></div>`
      : '<p class="muted">Зараз питань немає.</p>';
    return;
  }
  box.innerHTML = `
    <form class="card question" id="q-form">
      <h2>${esc(q.title)}</h2>
      ${said("stasiuk", esc(q.intro), "intro")}
      ${q.fields.map((f) => `
        <label class="q-field">${esc(f.label)}
          ${f.multiline
            ? `<textarea name="${esc(f.id)}" rows="4" placeholder="${esc(f.placeholder || "")}">${esc(drafts[f.id] || "")}</textarea>`
            : `<input name="${esc(f.id)}" autocomplete="off" placeholder="${esc(f.placeholder || "")}" value="${esc(drafts[f.id] || "")}">`}
        </label>`).join("")}
      ${lastVerdict(q.id)}
      ${hints.map((h, i) => `<div class="hint">${said("stasiuk", `<b>Підказка ${i + 1}.</b> ${esc(h)}`)}</div>`).join("")}
      <div class="actions">
        <button type="submit">Відповісти</button>
        <button type="button" class="secondary" id="q-hint" ${hints.length >= q.hints_total ? "disabled" : ""}>Підказка (${hints.length}/${q.hints_total})</button>
      </div>
      <p class="muted small">Невдалих спроб: ${q.attempts}. Кожна помилка і підказка знімає бали.</p>
    </form>`;
}

/* ---------- документи ---------- */
function openDoc(id, paneIdx = focusedPane) {
  const doc = state.documents.find((d) => d.id === id);
  if (!doc) return;
  panes[paneIdx].doc = id;
  const pane = $$(".pane")[paneIdx];
  $("iframe", pane).src = `/doc/${encodeURIComponent(id)}`;
  $(".pane-title", pane).textContent = `${doc.id} · ${doc.title}`;
  $('[data-act="newtab"]', pane).href = `/doc/${encodeURIComponent(id)}`;
  $(".pane-empty", pane).classList.add("hidden");
  seen.add(id);
  store.set("dg_seen", [...seen]);
  renderDocs();
}

function setFocus(i) {
  focusedPane = i;
  $$(".pane").forEach((p, j) => p.classList.toggle("focused", j === i && panes.length > 1));
}

function toggleSplit() {
  const viewer = $("#viewer");
  if (panes.length === 1) {
    const clone = $(".pane").cloneNode(true);
    clone.dataset.pane = "1";
    $("iframe", clone).src = "about:blank";
    $(".pane-title", clone).textContent = "Друге вікно: оберіть документ ліворуч";
    $(".pane-empty", clone).classList.remove("hidden");
    $(".pane-empty", clone).innerHTML = "Друге вікно для порівняння.<br>Клацніть тут, потім — на документ.";
    $('[data-act="split"]', clone).textContent = "✕ Закрити";
    viewer.appendChild(clone);
    panes.push({ doc: null });
    setFocus(1);
  } else {
    $$(".pane")[1].remove();
    panes.pop();
    setFocus(0);
  }
  $('[data-act="split"]', $$(".pane")[0]).textContent = panes.length > 1 ? "⇆ Порівняння" : "⇆ Порівняти";
  renderDocs();
}

/* ---------- запит / відповідь ---------- */
function showRequestResult(ev) {
  const box = $("#req-result");
  box.className = `result ${ev.ok ? "ok" : "bad"}`;
  box.innerHTML = said(speakerFor($("#req-type").value), linkDocs(ev.text));
}

function collectAnswers(form) {
  const answers = {};
  for (const f of state.question.fields) answers[f.id] = (form.elements[f.id]?.value || "").trim();
  return answers;
}

// Остання невдала відповідь групи на це питання: що Стасюка переконало, а що ні.
function lastVerdict(qid) {
  const e = state.log.slice().reverse().find((x) => x.kind === "answer" && x.question === qid);
  if (!e || e.ok) return "";
  return `<div class="result bad">${said("stasiuk", verdictHtml(e.text))}</div>`;
}

// Чернетки відповідей зберігаються локально — щоб не зникали при оновленні стану чи сторінки.
document.addEventListener("input", (e) => {
  const form = e.target.closest("#q-form");
  if (!form || !state.question) return;
  store.set(`dg_draft_${state.question.id}`, collectAnswers(form));
});

/* ---------- дрібниці ---------- */
function toast(html, bad = false) {
  const el = document.createElement("div");
  el.className = `toast ${bad ? "bad" : ""}`;
  el.innerHTML = html;
  $("#toasts").appendChild(el);
  setTimeout(() => el.remove(), 6000);
}

function tick() {
  if (!state) return;
  const end = state.finished_at || Date.now() / 1000;
  const s = Math.max(0, Math.floor(end - state.started_at));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  $("#timer").textContent = (h ? h + ":" : "") + String(m).padStart(2, "0") + ":" + String(sec).padStart(2, "0");
}

/* ---------- вкладки панелі та мобільна навігація ---------- */
const isMobile = () => window.matchMedia("(max-width: 860px)").matches;
let mtab = "doc";
let logUnread = 0;

function setTab(t) {
  $$(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === t));
  $$(".tab").forEach((el) => el.classList.toggle("hidden", el.id !== `tab-${t}`));
}
function updateLogBadge() {
  $("#log-badge").textContent = logUnread > 9 ? "9+" : logUnread;
  $("#log-badge").classList.toggle("hidden", !logUnread);
}
function setMTab(t) {
  mtab = t;
  document.body.dataset.mtab = t;
  $$("#bottom-nav button").forEach((b) => b.classList.toggle("active", b.dataset.mtab === t));
  if (t !== "doc") setTab(t);
  if (t === "log") { logUnread = 0; updateLogBadge(); }
  closeDrawer();
}
function openDrawer() { if (isMobile()) document.body.classList.add("drawer-open"); }
function closeDrawer() { document.body.classList.remove("drawer-open"); }
function toggleDrawer() { document.body.classList.contains("drawer-open") ? closeDrawer() : openDrawer(); }
document.body.dataset.mtab = "doc";

function applyHash() {
  const h = decodeURIComponent(location.hash.slice(1));
  if (!h) return;
  if (h === "drawer") { setMTab("doc"); openDrawer(); }
  else if (h.startsWith("tab=")) setMTab(h.slice(4));
}
window.addEventListener("hashchange", applyHash);
window.addEventListener("resize", () => { if (!isMobile()) closeDrawer(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeDrawer(); });

// Свайп: від лівого краю відкриває шторку, вліво по відкритій шторці закриває.
// Дотики всередині iframe документа до сторінки не доходять — тому над ним уздовж лівого краю лежить смужка.
document.body.insertAdjacentHTML("beforeend", '<div class="edge-swipe" aria-hidden="true"></div>');
let touch = null;
document.addEventListener("touchstart", (e) => {
  const t = e.touches[0];
  const open = document.body.classList.contains("drawer-open");
  // зона 40 px: крайні ~20 px на Android забирає системний жест «назад»
  touch = isMobile() && e.touches.length === 1 && (open || t.clientX < 40) ? { x: t.clientX, y: t.clientY, open } : null;
}, { passive: true });
document.addEventListener("touchend", (e) => {
  if (!touch) return;
  const t = e.changedTouches[0], dx = t.clientX - touch.x, dy = t.clientY - touch.y;
  if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) {
    if (!touch.open && dx > 0) openDrawer();
    else if (touch.open && dx < 0) closeDrawer();
  }
  touch = null;
}, { passive: true });

/* ---------- обробники ---------- */
document.addEventListener("click", (e) => {
  const navBtn = e.target.closest("#bottom-nav [data-mtab]");  // не body[data-mtab] — там лише поточна вкладка
  if (navBtn) { setMTab(navBtn.dataset.mtab); return; }
  if (e.target.closest("#menu-btn")) { toggleDrawer(); return; }
  if (e.target.id === "backdrop") { closeDrawer(); return; }
  const docEl = e.target.closest("[data-doc]");
  if (docEl) { openDoc(docEl.dataset.doc); if (isMobile()) { closeDrawer(); setMTab("doc"); } return; }
  const tab = e.target.closest(".tabs button");
  if (tab) { setTab(tab.dataset.tab); return; }
  if (e.target.closest('[data-act="split"]')) { toggleSplit(); return; }
  const pane = e.target.closest(".pane");
  if (pane) setFocus(Number(pane.dataset.pane));
  if (e.target.dataset.pin) send({ action: "note_pin", id: e.target.dataset.pin });
  if (e.target.dataset.del && confirmDelete()) send({ action: "note_del", id: e.target.dataset.del });
  if (e.target.id === "q-hint") send({ action: "hint", question: state.question.id });
  if (e.target.id === "final-close") { store.set("dg_final_seen", true); $("#final").classList.add("hidden"); }
});

// Видалення нотатки — без системного діалогу: друге натискання протягом 3 с підтверджує.
let pendingDelete = 0;
function confirmDelete() {
  const now = Date.now();
  if (now - pendingDelete < 3000) { pendingDelete = 0; return true; }
  pendingDelete = now;
  toast("Натисніть ✕ ще раз, щоб видалити нотатку");
  return false;
}

document.addEventListener("submit", (e) => {
  e.preventDefault();
  if (e.target.id === "q-form") {
    send({ action: "answer", question: state.question.id, answers: collectAnswers(e.target) });
  }
});

$("#request-form").addEventListener("submit", (e) => {
  e.preventDefault();
  send({ action: "request", type: $("#req-type").value, target: $("#req-target").value, details: $("#req-details").value });
  $("#req-result").className = "result";
  $("#req-result").textContent = "Запит надіслано…";
});
$("#req-type").addEventListener("change", updateRequestLabels);
$("#note-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const t = $("#note-text").value.trim();
  if (t) { send({ action: "note_add", text: t }); $("#note-text").value = ""; }
});
$("#doc-filter").addEventListener("input", renderDocs);

$("#login-form").addEventListener("submit", (e) => {
  e.preventDefault();
  name = $("#login-name").value.trim();
  if (!name) return;
  store.set("dg_name", name);
  $("#login").classList.add("hidden");
  connect();
});

if (name) { $("#login").classList.add("hidden"); connect(); }
else { $("#login-name").focus(); }
setInterval(tick, 1000);
applyHash();
