/* =====================================================================
 * 機房操作日誌系統 — 瀏覽器端 SPA
 *
 * 設計原則（與原單檔版最大差異）：
 *  - 伺服器擁有所有業務規則。前端「不」判斷權限，只依 log.perm /
 *    每個項目的 perm 旗標決定要不要顯示按鈕、要不要 disable 輸入。
 *  - 沒有整份儲存。每一個變更都呼叫對應的細粒度 API，然後用回傳的
 *    完整日誌整份重繪。
 *  - 每 pollSeconds 秒輪詢 GET /api/log/{date}?ifUpdatedAfter=...；
 *    浮窗開啟中或輸入框有焦點時略過。
 *  - localStorage 只存 UI 便利設定（篩選勾選），不存任何日誌資料。
 * ===================================================================== */
(function(){
"use strict";

/* ===== 常數 ===== */
const CTX = window.CTX || "";
const SHIFTS = ["day","evening","night"];
const shiftLabel  = {day:"早班",evening:"小夜班",night:"大夜班"};
const shiftIcon   = {day:"🌞",evening:"🌒",night:"🌑"};
const shiftTagCls = {day:"tag-day",evening:"tag-evening",night:"tag-night",cross:"tag-cross"};
const shiftTime   = {day:"08:00-16:00",evening:"16:00-24:00",night:"00:00-07:00 + 開機"};
const jobErrorAt  = {day:"16:00",evening:"00:00",night:"07:00"};
const dayTypeLabels = {business:"營業日",holiday:"假日",typhoon:"颱風日"};
const statusColors  = {draft:"#64748b",submitted:"#f59e0b",reviewed:"#3b82f6",approved:"#10b981"};
const statusLabels  = {draft:"填寫中",submitted:"待副科審核",reviewed:"待科長核准",approved:"已核准 🔒"};
const roleNames     = {OPERATOR:"經辦",DEPUTY:"副科",CHIEF:"科長"};
const PORTAL_KEYS = ["網銀","WWW","金控官網","EATM","COEIP"];
const SMS_KEYS    = ["SGLGMVS","SGLGIMS","SGMQLOG"];
const RMF_P = ["PRDA","PRDB"], RMF_F = ["CSA","ECSA","SQA","ESQA"];
const SECTION_KEYS = ["docInfo","sysEquip","shiftChecks","batch","joberror","records"];
const DEFAULT_POLL_SECONDS = 5;
const FULL_REFRESH_MS = 60000;      // 定期整份重抓（延遲/提醒等與時間有關的旗標才會更新）
const FILTER_LS_KEY = "mr.ui.filters";

/* ===== 狀態 ===== */
let me = null;                 // GET /api/me
let dates = [];                // GET /api/dates
let log = null;                // 目前檢視的完整日誌
let currentDate = null;
let defs = null;               // GET /api/defs（項目管理用）
let collapsedByDate = {};      // {date: {sectionKey: bool}}（僅記憶體）
let filters = {mine:true, pending:false};
let historySelected = new Set();
let historyRows = [];
let pendingModals = [];        // 待開的浮窗 queue（JOB ERROR 用）
let pollTimer = null;
let busy = 0;                  // 進行中的變更呼叫數
let serverOffset = 0;          // 伺服器時間 - 本機時間 (ms)
let lastFullRefresh = 0;
let activeTab = "log";
let activeSubtab = "batch";

/* ===== 小工具 ===== */
const $ = id => document.getElementById(id);
function esc(s){
  return String(s === null || s === undefined ? "" : s)
    .replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
}
function pad2(n){ return String(n).padStart(2,"0"); }
function nowServer(){ return new Date(Date.now() + serverOffset); }
function hhmm(d){ return pad2(d.getHours()) + ":" + pad2(d.getMinutes()); }
function addDays(dstr, n){
  const p = String(dstr || "").split("-").map(Number);
  if(p.length !== 3 || p.some(isNaN)) return dstr;
  const d = new Date(p[0], p[1]-1, p[2] + n);
  return d.getFullYear() + "-" + pad2(d.getMonth()+1) + "-" + pad2(d.getDate());
}
function isNextDayTime(t){
  if(!t) return false;
  const h = parseInt(String(t).split(":")[0], 10);
  return h >= 0 && h < 7;
}
function fmtTs(ts){ return ts ? String(ts).slice(0,16).replace("T"," ") : ""; }
function L(path){ return "/api/log/" + encodeURIComponent(currentDate) + path; }
function sigHtml(s, cls){
  if(!s) return '<span class="sig-empty">—</span>';
  return '<span class="sig ' + (cls||"") + '" title="' + esc(fmtTs(s.time)) + '">✓ ' + esc(s.user) +
    (s.integratorEdit ? ' <span class="sig-int" title="整合者修改">✎</span>' : '') + '</span>';
}
function shiftShort(k){ return (shiftLabel[k] || k || "").slice(0,2); }
function downloadUrl(url){
  const a = document.createElement("a");
  a.href = url; a.download = ""; a.style.display = "none";
  document.body.appendChild(a); a.click();
  setTimeout(()=>a.remove(), 1000);
}
function loadFilters(){
  try{
    const raw = localStorage.getItem(FILTER_LS_KEY);
    if(raw){ const o = JSON.parse(raw); if(o && typeof o === "object") return o; }
  }catch(e){}
  return null;
}
function saveFilters(){
  try{ localStorage.setItem(FILTER_LS_KEY, JSON.stringify(filters)); }catch(e){}
}

/* ===== 同步狀態徽章 ===== */
function setSync(text, color){
  const el = $("syncStatus");
  if(!el) return;
  el.textContent = text;
  el.style.background = color || "rgba(255,255,255,0.15)";
}
const SYNC_OK = "rgba(16,185,129,0.5)", SYNC_BUSY = "rgba(59,130,246,0.5)",
      SYNC_UPD = "rgba(139,92,246,0.5)", SYNC_ERR = "rgba(239,68,68,0.6)";

/* ===== API 層 ===== */
async function api(method, path, body, opts){
  opts = opts || {};
  const headers = {"Accept":"application/json", "X-Requested-With":"XMLHttpRequest"};
  const hasBody = body !== undefined && body !== null;
  if(hasBody) headers["Content-Type"] = "application/json";
  let res;
  try{
    res = await fetch(CTX + path, {
      method: method,
      headers: headers,
      credentials: "same-origin",
      cache: "no-store",
      body: hasBody ? JSON.stringify(body) : undefined
    });
  }catch(e){
    if(!opts.silent) alert("⚠ 無法連線伺服器，請稍後再試");
    throw e;
  }
  if(res.status === 401){
    location.href = CTX + "/login";
    throw new Error("unauthenticated");
  }
  if(!res.ok){
    let err = {error: "HTTP " + res.status};
    try{ const j = await res.json(); if(j && typeof j === "object") err = j; }catch(e){}
    const msg = err.error || ("HTTP " + res.status);
    if(!opts.silent){
      alert("⚠ " + msg + (Array.isArray(err.details) && err.details.length ? "\n\n• " + err.details.join("\n• ") : ""));
    }
    const ex = new Error(msg); ex.status = res.status; ex.details = err.details;
    throw ex;
  }
  if(res.status === 204) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

/* 會改變資料的呼叫：成功→用回傳的完整日誌重繪；失敗→重繪還原畫面。
 * 回傳 null 代表失敗（api() 已 alert）。 */
async function mutate(method, path, body){
  busy++;
  setSync("💾 儲存中…", SYNC_BUSY);
  try{
    const r = await api(method, path, body);
    if(r && r.date && r.perm && r.date === currentDate) setLog(r);
    setSync("✅ 已同步 " + hhmm(nowServer()), SYNC_OK);
    return r === null ? {} : r;
  }catch(e){
    setSync("❌ 儲存失敗", SYNC_ERR);
    if(log) render();
    return null;
  }finally{
    busy--;
  }
}

function isLog(x){ return !!(x && x.date && x.perm); }

/* ===== 日誌載入 / 輪詢 ===== */
function setLog(data){
  log = data;
  currentDate = data.date;
  if(!collapsedByDate[currentDate]){
    collapsedByDate[currentDate] = {};
    autoCollapse();          // 第一次看到這一天：已完成的區塊自動收合
  }
  render();
}

async function loadLog(date){
  setSync("⏳ 載入中…", SYNC_BUSY);
  try{
    const d = await api("GET", "/api/log/" + encodeURIComponent(date));
    if(!isLog(d)) throw new Error("bad log");
    setLog(d);
    setSync("✅ 已同步 " + hhmm(nowServer()), SYNC_OK);
    lastFullRefresh = Date.now();
    return true;
  }catch(e){
    setSync("⚠ 載入失敗", SYNC_ERR);
    if(log) renderDateSelector();
    return false;
  }
}

async function refreshDates(){
  try{
    const d = await api("GET", "/api/dates", undefined, {silent:true});
    if(Array.isArray(d)) dates = d;
  }catch(e){}
  if(log) renderDateSelector();
}

function textInputFocused(){
  const ae = document.activeElement;
  if(!ae) return false;
  const tag = ae.tagName;
  if(tag === "TEXTAREA" || tag === "SELECT") return true;
  if(tag === "INPUT"){
    const t = (ae.type || "text").toLowerCase();
    return !(t === "checkbox" || t === "radio" || t === "button" || t === "submit");
  }
  return false;
}
function modalOpen(){ return !!$("modalRoot").firstElementChild; }

function startPolling(){
  const sec = Math.max(2, parseInt(me && me.pollSeconds, 10) || DEFAULT_POLL_SECONDS);
  if(pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(poll, sec * 1000);
}

async function poll(){
  if(!log || busy > 0 || activeTab !== "log") return;
  if(modalOpen() || textInputFocused()) return;
  const date = currentDate;
  const full = (Date.now() - lastFullRefresh) > FULL_REFRESH_MS;
  const q = full ? "" : "?ifUpdatedAfter=" + encodeURIComponent(log.updatedAt || "");
  try{
    const r = await api("GET", "/api/log/" + encodeURIComponent(date) + q, undefined, {silent:true});
    if(!r || r.changed === false) return;
    if(date !== currentDate || busy > 0 || modalOpen() || textInputFocused()) return;
    if(!isLog(r)) return;
    const changed = r.updatedAt !== log.updatedAt;
    if(full) lastFullRefresh = Date.now();
    if(!changed && !full) return;
    setLog(r);
    if(changed) setSync("🔄 已更新", SYNC_UPD);
  }catch(e){
    if(e && e.status === 404) return;
    setSync("⚠ 連線中斷", SYNC_ERR);
  }
}

/* ===== 啟動 ===== */
async function init(){
  bindStatic();
  try{
    me = await api("GET", "/api/me");
  }catch(e){
    $("marquee").textContent = "⚠ 無法連線伺服器";
    return;
  }
  if(me.serverTime){
    const st = new Date(me.serverTime);
    if(!isNaN(st.getTime())) serverOffset = st.getTime() - Date.now();
  }
  const saved = loadFilters();
  filters.mine = saved && typeof saved.mine === "boolean" ? saved.mine : (me.role === "OPERATOR");
  filters.pending = saved && typeof saved.pending === "boolean" ? saved.pending : false;
  $("filter-mine").checked = filters.mine;
  $("filter-pending").checked = filters.pending;

  renderHeader();
  currentDate = me.todayLogical;

  await refreshDates();

  if(me.role === "OPERATOR" && me.pendingIntegration){
    if(confirm("⚠ 發現 " + me.pendingIntegration + " 的日誌已 3 班皆送出但尚未整合送簽。\n\n要切換到該日誌進行整合送簽嗎？")){
      currentDate = me.pendingIntegration;
    }
  }else if(me.role === "DEPUTY" || me.role === "CHIEF"){
    const want = me.role === "DEPUTY" ? "submitted" : "reviewed";
    const pend = dates.find(d => d.status === want);
    if(pend) currentDate = pend.date;
  }

  const ok = await loadLog(currentDate);
  if(!ok && currentDate !== me.todayLogical){
    currentDate = me.todayLogical;
    await loadLog(currentDate);
  }
  if(!log) $("marquee").textContent = "⚠ 日誌載入失敗";
  startPolling();
}

/* ===== 頁面靜態事件綁定（只做一次） ===== */
function bindStatic(){
  // 主分頁
  document.querySelectorAll(".tab").forEach(t => t.addEventListener("click", () => switchTab(t.dataset.tab)));
  // 子分頁
  document.querySelectorAll(".sub-tab").forEach(t => t.addEventListener("click", () => switchSubtab(t.dataset.subtab)));
  // 篩選
  $("filter-mine").addEventListener("change", e => { filters.mine = e.target.checked; saveFilters(); if(log) renderTaskTable(); });
  $("filter-pending").addEventListener("change", e => { filters.pending = e.target.checked; saveFilters(); if(log) renderTaskTable(); });
  $("filterBar").addEventListener("click", e => e.stopPropagation());
  // 區塊收合
  document.querySelectorAll("[data-toggle]").forEach(el => {
    el.addEventListener("click", e => {
      if(e.target.closest(".filter-bar")) return;
      const k = el.dataset.toggle;
      const c = collapsedFor();
      c[k] = !c[k];
      applyCollapse();
    });
  });
  // 新增事件
  $("add-record").addEventListener("click", () => {
    if(!log) return;
    openRecordModal({date: currentDate, title: "➕ 新增重要記錄事項", source: "manual",
      shift: me.shift || null, showTask: true});
  });
  // 委派事件
  $("dateSelector").addEventListener("click", onDateSelectorClick);
  $("docInfo").addEventListener("change", onDocInfoChange);
  $("docInfo").addEventListener("click", onDocInfoClick);
  $("shiftBar").addEventListener("click", onShiftBarClick);
  $("sysEquip").addEventListener("change", onEquipChange);
  $("sysEquip").addEventListener("click", onEquipClick);
  $("shiftChecksBody").addEventListener("change", onCheckChange);
  $("shiftChecksBody").addEventListener("click", onCheckClick);
  $("taskRows").addEventListener("change", onTaskChange);
  $("taskRows").addEventListener("click", onTaskClick);
  $("jobError").addEventListener("change", onJobErrorChange);
  $("recordsList").addEventListener("click", onRecordsClick);
  $("actionArea").addEventListener("click", onActionClick);
  $("tab-admin").addEventListener("click", onAdminClick);
  $("tab-admin").addEventListener("change", onAdminChange);
}

function switchTab(id){
  if(id === "stats" && !(me && (me.role === "DEPUTY" || me.role === "CHIEF"))) return;
  activeTab = id;
  document.querySelectorAll(".tab").forEach(x => x.classList.toggle("active", x.dataset.tab === id));
  ["log","stats","admin"].forEach(k => { $("tab-" + k).style.display = k === id ? "block" : "none"; });
  if(id === "stats") renderStats();
  if(id === "admin") openAdmin();
  if(id === "log" && log) poll();   // 回到日誌頁立即同步一次
}

function switchSubtab(k){
  activeSubtab = k;
  document.querySelectorAll(".sub-tab").forEach(x => x.classList.toggle("active", x.dataset.subtab === k));
  ["batch","sysequip","checks","history","data"].forEach(s => {
    const el = $("subtab-" + s); if(el) el.style.display = s === k ? "block" : "none";
  });
  if(k === "history") renderHistory();
  if(k === "data") renderDataAdmin();
  if(k === "batch" || k === "sysequip" || k === "checks") renderAdminAll();
}

/* ===== 主 render ===== */
function render(){
  if(!log) return;
  const fk = textInputFocused() ? document.activeElement.dataset.fk : null;
  renderHeader();
  renderDateSelector();
  renderCrossDayBanner();
  renderDocInfo();
  renderShiftBar();
  renderSummary();
  renderSysEquip();
  renderShiftChecks();
  renderTaskTable();
  renderJobError();
  renderRecords();
  renderSectionTags();
  renderActions();
  updateMarquee();
  applyCollapse();
  if(fk){
    const el = document.querySelector('[data-fk="' + fk.replace(/"/g,'\\"') + '"]');
    if(el && !el.disabled){ try{ el.focus({preventScroll:true}); }catch(e){} }
  }
}

function renderHeader(){
  if(!me) return;
  document.title = me.appTitle || "機房操作日誌系統";
  $("appTitle").textContent = "🖥️ " + (me.orgName || "") + (me.appTitle || "機房操作日誌系統");
  const who = (me.displayName ? me.displayName + "（" + me.user + "）" : me.user) +
    (roleNames[me.role] ? "・" + roleNames[me.role] : "") + (me.isAdmin ? "・管理" : "");
  $("who").textContent = who;
  const badge = $("modeBadge");
  badge.textContent = modeText();
  badge.className = "mode-badge " + (me.role === "OPERATOR" ? "mode-fill" : "mode-review");
  $("tab-stats-btn").classList.toggle("hidden", !(me.role === "DEPUTY" || me.role === "CHIEF"));
}

function modeText(){
  if(me.role !== "OPERATOR") return "📖 審核模式";
  const s = shiftLabel[me.shift] || "";
  if(log && log.perm && log.perm.mode === "integrate") return "📨 整合檢查（" + s + "）";
  return "✏ " + s;
}

/* ===== 收合 ===== */
function collapsedFor(){
  if(!collapsedByDate[currentDate]) collapsedByDate[currentDate] = {};
  return collapsedByDate[currentDate];
}
function autoCollapse(){
  const c = collapsedFor();
  const p = log.progress || {};
  SECTION_KEYS.forEach(k => {
    if(c[k] === undefined){
      const pk = p[k] || {done:0,total:0};
      c[k] = (pk.total > 0 && pk.done === pk.total);
    }
  });
}
function applyCollapse(){
  const c = collapsedFor();
  document.querySelectorAll("[data-collapse-key]").forEach(el => {
    const k = el.dataset.collapseKey;
    const btn = $("toggle-" + k);
    if(c[k]){ el.classList.add("collapsed"); if(btn) btn.textContent = "+"; }
    else { el.classList.remove("collapsed"); if(btn) btn.textContent = "−"; }
  });
}

/* ===== 區塊進度標籤 ===== */
function progressTag(p, labelFn){
  if(!p || !p.total) return '<span class="progress-tag tag-done">無項目</span>';
  const label = labelFn ? labelFn(p) : (p.done + "/" + p.total);
  if(p.done === 0) return '<span class="progress-tag tag-todo">' + esc(label) + '</span>';
  if(p.done < p.total) return '<span class="progress-tag tag-partial">' + esc(label) + '</span>';
  return '<span class="progress-tag tag-done">' + esc(label) + ' ✓</span>';
}
function renderSectionTags(){
  const p = log.progress || {};
  $("docInfoTag").innerHTML = progressTag(p.docInfo, x => x.done >= x.total ? "已填" : "未填");
  $("sysEquipTag").innerHTML = progressTag(p.sysEquip);
  $("shiftChecksTag").innerHTML = progressTag(p.shiftChecks);
  $("batchTag").innerHTML = progressTag(p.batch);
  $("jobErrorTag").innerHTML = progressTag(p.joberror);
  const rec = p.records || {done:0,total:0};
  $("recordsTag").innerHTML = rec.total > 0
    ? '<span class="progress-tag tag-todo">' + rec.total + ' 筆</span>'
    : '<span class="progress-tag tag-done">無</span>';
}

/* ===== 日期選擇 ===== */
function renderDateSelector(){
  const sel = $("dateSelector");
  const list = dates.slice();
  if(currentDate && log && !list.some(d => d.date === currentDate)){
    list.push({date: currentDate, status: log.status, weekday: log.weekday, isToday: currentDate === me.todayLogical});
  }
  list.sort((a,b) => a.date < b.date ? 1 : (a.date > b.date ? -1 : 0));
  sel.innerHTML = '<span style="font-weight:600;margin-right:6px;color:#475569">📅 邏輯日：</span>' +
    list.map(d =>
      '<button type="button" class="hist-btn' + (d.date === currentDate ? ' active' : '') + '" data-date="' + esc(d.date) + '" title="' +
        esc((statusLabels[d.status] || d.status || "") + (d.weekday ? "・星期" + d.weekday : "")) + '">' +
        '<span class="status-dot" style="background:' + (statusColors[d.status] || "#64748b") + '"></span>' +
        esc(d.date) + (d.isToday ? ' 🌟' : '') + '</button>'
    ).join("") +
    '<button type="button" class="hist-btn" id="goto-today" style="background:#0ea5e9;color:#fff;border-color:#0ea5e9">⏰ 切到今天</button>';
}
function onDateSelectorClick(e){
  const b = e.target.closest("button");
  if(!b) return;
  if(b.id === "goto-today"){ if(currentDate !== me.todayLogical) loadLog(me.todayLogical); return; }
  if(b.dataset.date && b.dataset.date !== currentDate) loadLog(b.dataset.date);
}

function renderCrossDayBanner(){
  const banner = $("crossDayBanner");
  if(!log.perm.isCurrentLogicalDay){
    banner.innerHTML = '<div class="cross-day-banner">📌 您正在檢視/操作的是 ' + esc(currentDate) + ' 的日誌（非當前邏輯日）</div>';
    return;
  }
  const now = nowServer();
  const cutoff = (me.logicalDayCutoffHour === undefined || me.logicalDayCutoffHour === null) ? 7 : +me.logicalDayCutoffHour;
  if(now.getHours() < cutoff){
    banner.innerHTML = '<div class="cross-day-banner">🌙 目前時間 ' + hhmm(now) + '（已過午夜），仍屬於 ' + esc(currentDate) + ' 邏輯日。所有操作會記入此日誌。</div>';
  }else{
    banner.innerHTML = "";
  }
}

/* ===== 日誌資訊 ===== */
function renderDocInfo(){
  const d = log, p = log.perm;
  const isSpecial = d.dayType === "typhoon" || d.dayType === "holiday";
  const cutoff = (me.logicalDayCutoffHour === undefined || me.logicalDayCutoffHour === null) ? 7 : +me.logicalDayCutoffHour;
  const opts = Object.assign({}, dayTypeLabels);
  if(d.dayType && !opts[d.dayType]) opts[d.dayType] = d.dayTypeLabel || d.dayType;
  const item = (label, valueHtml, cls) => '<div class="item ' + (cls||"") + '"><div class="label">' + label + '</div>' + valueHtml + '</div>';

  let html =
    item("📅 邏輯日", '<div class="value">' + esc(d.date) + ' ' + pad2(cutoff) + ':00起</div>') +
    item("星期", '<div class="value">星期' + esc(d.weekday) + '</div>') +
    item("太陽日", '<div class="value">' + esc(d.solarDay) + '</div>') +
    item("日類型",
      '<select id="daytype-sel" data-fk="daytype"' + (p.canChangeDayType ? "" : " disabled") + '>' +
        Object.keys(opts).map(k => '<option value="' + esc(k) + '"' + (d.dayType === k ? " selected" : "") + '>' + esc(opts[k]) + '</option>').join("") +
      '</select>' +
      (d.dayTypeOverride ? '<div class="hint">⚠ ' + esc(d.dayTypeReason || "已變更") + (d.dayTypeChangedBy ? '（' + esc(d.dayTypeChangedBy) + '）' : '') + '</div>' : ''),
      isSpecial ? "special" : "") +
    item("🔑 開機人", '<input type="text" id="boot-user" data-fk="boot-user" value="' + esc(d.bootUser) + '"' + (p.canEditBoot ? "" : " disabled") + ' placeholder="大夜填寫">') +
    item("開機時間", '<input type="time" id="boot-time" data-fk="boot-time" value="' + esc(d.bootTime) + '"' + (p.canEditBoot ? "" : " disabled") + '>') +
    item("填寫", '<div class="value" style="font-size:12px">' +
      (d.bootOpSign ? sigHtml(d.bootOpSign) :
        (p.canSignBoot ? '<button type="button" class="btn btn-primary btn-mini" id="boot-sign">📝</button>' : '<span class="sig-empty">—</span>')) + '</div>') +
    item("覆核", '<div class="value" style="font-size:12px">' +
      (d.bootReviewerSign ? sigHtml(d.bootReviewerSign, "sig-rev") :
        (p.canReviewBoot ? '<button type="button" class="btn btn-success btn-mini" id="boot-review">覆核</button>' : '<span class="sig-empty">—</span>')) + '</div>');
  if(d.versions && d.versions > 1){
    html += item("版本", '<div class="value">v' + esc(d.versions) + '</div>' + (d.unlockReason ? '<div class="hint">🔓 ' + esc(d.unlockReason) + '</div>' : ''), "special");
  }
  $("docInfo").innerHTML = html;
}
async function onDocInfoChange(e){
  const el = e.target;
  if(el.id === "daytype-sel"){
    const v = el.value;
    if(v === log.dayType) return;
    const reason = prompt("變更日類型為「" + (dayTypeLabels[v] || v) + "」的原因：");
    if(!reason){ el.value = log.dayType; return; }
    await mutate("POST", L("/daytype"), {dayType: v, reason: reason});
    return;
  }
  if(el.id === "boot-user" || el.id === "boot-time"){
    await mutate("POST", L("/boot"), {bootUser: $("boot-user").value.trim(), bootTime: $("boot-time").value});
  }
}
async function onDocInfoClick(e){
  const b = e.target.closest("button");
  if(!b) return;
  if(b.id === "boot-sign"){ b.disabled = true; await mutate("POST", L("/boot/sign")); }
  if(b.id === "boot-review"){ b.disabled = true; await mutate("POST", L("/boot/review")); }
}

/* ===== 班別列 ===== */
function taskForShift(t, key){
  return !!t.shouldExecute && (t.assignedShift === key || (t.shift === "cross" && t.handoverEndShift === key));
}
function renderShiftBar(){
  const bar = $("shiftBar");
  const isOp = me.role === "OPERATOR";
  bar.innerHTML = SHIFTS.map(k => {
    let cls = "shift-card" + (isOp ? "" : " static");
    if(isOp && me.shift === k) cls += " active";
    const submitted = log.shiftStatus && log.shiftStatus[k] === "submitted";
    if(submitted) cls += " submitted";
    const sub = log.shiftSubmits ? log.shiftSubmits[k] : null;
    const count = log.tasks.filter(t => taskForShift(t, k)).length;
    const done = log.tasks.filter(t => taskForShift(t, k) && t.done).length;
    return '<div class="' + cls + '" data-shift="' + k + '">' +
      '<div class="name">' + shiftIcon[k] + ' ' + shiftLabel[k] + '</div>' +
      '<div class="time">' + shiftTime[k] + '</div>' +
      '<div class="progress">批次 ' + done + '/' + count + '</div>' +
      (sub ? '<div class="sub">' + esc(sub.user) + (sub.time ? ' ' + esc(fmtTs(sub.time).slice(11)) : '') + '</div>' : '') +
      (submitted ? '<div class="badge">✓ 已送出</div>' : '') +
      '</div>';
  }).join("");
}
async function onShiftBarClick(e){
  if(me.role !== "OPERATOR") return;
  const card = e.target.closest(".shift-card");
  if(!card) return;
  const s = card.dataset.shift;
  if(!s || s === me.shift) return;
  const r = await mutate("POST", "/api/me/shift", {shift: s});
  if(!r) return;
  if(r.user) me = Object.assign({}, me, r); else me.shift = s;
  renderHeader();
  await loadLog(currentDate);
}

/* ===== 摘要列 ===== */
function renderSummary(){
  const s = log.summary || {};
  $("summary").innerHTML =
    '<span class="status-tag s-' + esc(log.status) + '">' + esc(log.statusLabel || statusLabels[log.status] || log.status) + '</span>' +
    '<div class="stat-item"><div class="num">' + esc(s.batchDone||0) + '/' + esc(s.batchTotal||0) + '</div><div class="label">批次完成</div></div>' +
    '<div class="stat-item"><div class="num" style="color:#ef4444">' + esc(s.delayed||0) + '</div><div class="label">延遲</div></div>' +
    '<div class="stat-item"><div class="num" style="color:#f59e0b">' + esc(s.abnormal||0) + '</div><div class="label">異常</div></div>' +
    '<div class="stat-item"><div class="num" style="color:#8b5cf6">' + esc(s.events||0) + '</div><div class="label">事件</div></div>';
}

/* ===== 系統設備檢查 ===== */
function renderSysEquip(){
  let html = '<table class="excel"><thead><tr>' +
    '<th style="width:60px">班別</th><th style="width:75px">時間</th><th>項目</th>' +
    '<th>狀態</th><th style="width:80px">填寫</th><th style="width:80px">覆核</th><th style="width:60px">操作</th>' +
    '</tr></thead><tbody>';
  (log.equip || []).forEach(it => {
    const p = it.perm || {};
    const id = esc(it.defId);
    const imsOff = it.type === "ims" && !it.enabled;
    const editable = !!p.editable && !imsOff;
    const dis = editable ? "" : " disabled";
    let cls = "";
    if(imsOff) cls = "row-special";
    else if(it.opSign && it.reviewerSign) cls = "row-done";
    else if(it.filled && !it.opSign) cls = "row-abn";

    let statusHtml = "";
    if(it.type === "status"){
      const opts = it.statusOptions && it.statusOptions.length ? it.statusOptions : ["正常","異常"];
      statusHtml = '<select' + dis + ' data-eq="' + id + '" data-f="status" data-fk="eq:' + id + ':status"><option value="">--</option>' +
        opts.map(s => '<option value="' + esc(s) + '"' + (it.status === s ? " selected" : "") + '>' + esc(s) + '</option>').join("") + '</select>';
    }else if(it.type === "dms"){
      statusHtml = '<input type="number" min="0" value="' + esc(it.count) + '"' + dis + ' style="width:80px;text-align:center" data-eq="' + id + '" data-f="count" data-fk="eq:' + id + ':count" placeholder="0"> 台';
    }else if(it.type === "ims"){
      statusHtml = '<input type="text" value="' + esc(it.notify) + '"' + dis + ' data-eq="' + id + '" data-f="notify" data-fk="eq:' + id + ':notify" placeholder="通知客服中心">';
    }else{
      statusHtml = '<input type="text" value="' + esc(it.notify) + '"' + dis + ' data-eq="' + id + '" data-f="notify" data-fk="eq:' + id + ':notify" placeholder="輸入內容">';
    }
    const name = esc(it.defName) + (imsOff ? ' <span style="font-size:10px;color:#94a3b8">（特殊作業）</span>' : '');
    let opCol = "";
    if(it.type === "ims" && p.canToggle){
      opCol = '<button type="button" class="btn ' + (it.enabled ? "btn-ghost" : "btn-purple") + ' btn-mini" data-act="toggle" data-eq="' + id + '">' + (it.enabled ? "關閉" : "啟用") + '</button>';
    }
    const fillCell = it.opSign
      ? sigHtml(it.opSign) + (p.canUnsign ? ' <button type="button" class="btn btn-danger btn-mini" data-act="unsign" data-eq="' + id + '">取消簽章</button>' : '')
      : (p.signable ? '<button type="button" class="btn btn-primary btn-mini" data-act="sign" data-eq="' + id + '">📝 簽章</button>' : '<span class="sig-empty">—</span>');
    const revCell = it.reviewerSign
      ? sigHtml(it.reviewerSign, "sig-rev") + (p.canUnreview ? ' <button type="button" class="btn btn-danger btn-mini" data-act="unreview" data-eq="' + id + '">取消覆核</button>' : '')
      : (p.reviewable ? '<button type="button" class="btn btn-success btn-mini" data-act="review" data-eq="' + id + '">✓ 覆核</button>' : '<span class="sig-empty">—</span>');

    html += '<tr class="' + cls + '">' +
      '<td class="center"><span class="shift-tag ' + (shiftTagCls[it.shift] || "") + '">' + esc(shiftShort(it.shift)) + '</span></td>' +
      '<td class="center"><input type="time" value="' + esc(it.time) + '"' + dis + ' data-eq="' + id + '" data-f="time" data-fk="eq:' + id + ':time"></td>' +
      '<td>' + name + '</td>' +
      '<td>' + statusHtml + '</td>' +
      '<td class="center">' + fillCell + '</td>' +
      '<td class="center">' + revCell + '</td>' +
      '<td class="center">' + opCol + '</td></tr>';
  });
  html += '</tbody></table>';
  if(!(log.equip || []).length) html = '<div class="records-empty">尚無系統設備項目</div>';
  $("sysEquip").innerHTML = html;
}
function findEquip(id){ return (log.equip || []).find(x => x.defId === id); }
async function onEquipChange(e){
  const el = e.target;
  const id = el.dataset.eq, f = el.dataset.f;
  if(!id || !f) return;
  let v = el.value;
  if(el.type === "number" && v !== "" && +v < 0){ v = "0"; el.value = "0"; alert("⚠ 不可輸入負數"); }
  await mutate("POST", L("/equip/" + encodeURIComponent(id) + "/field"), {field: f, value: v});
}
async function onEquipClick(e){
  const b = e.target.closest("button[data-act]");
  if(!b) return;
  const id = b.dataset.eq, act = b.dataset.act;
  const it = findEquip(id);
  if(!it) return;
  const base = L("/equip/" + encodeURIComponent(id));
  if(act === "sign"){ b.disabled = true; await mutate("POST", base + "/sign"); }
  else if(act === "unsign"){ if(!confirm("撤回填寫簽章？\n（覆核也會一併清除）")) return; b.disabled = true; await mutate("POST", base + "/unsign"); }
  else if(act === "review"){ b.disabled = true; await mutate("POST", base + "/review"); }
  else if(act === "unreview"){ if(!confirm("撤回覆核簽章？")) return; b.disabled = true; await mutate("POST", base + "/unreview"); }
  else if(act === "toggle"){
    if(!it.enabled){
      const reason = prompt("啟用「" + it.defName + "」的原因：");
      if(!reason) return;
      b.disabled = true; await mutate("POST", base + "/toggle", {reason: reason});
    }else{
      if(!confirm("關閉「" + it.defName + "」（特殊作業）？")) return;
      b.disabled = true; await mutate("POST", base + "/toggle", {reason: ""});
    }
  }
}

/* ===== 三班檢查表 ===== */
function renderShiftChecks(){
  const checks = log.checks || {};
  const colors = {day:"#f59e0b",evening:"#8b5cf6",night:"#475569"};
  let html = "";
  SHIFTS.forEach(sk => {
    const items = checks[sk] || [];
    const done = items.filter(it => it.opSign && it.reviewerSign).length;
    html += '<div class="check-col"><h4 style="color:' + colors[sk] + '">' +
      '<span>' + shiftIcon[sk] + ' ' + shiftLabel[sk] + '</span>' +
      '<span style="font-size:11px;color:#94a3b8">' + done + '/' + items.length + '</span></h4>';
    if(!items.length) html += '<div class="records-empty" style="padding:8px">無項目</div>';
    items.forEach(it => {
      const p = it.perm || {};
      const id = esc(it.defId);
      const editable = !!p.editable;
      const dis = editable ? "" : " disabled";
      const vals = it.values || {};
      const dd = ' data-sk="' + sk + '" data-id="' + id + '"';
      const fk = "ck:" + sk + ":" + id;
      let rowCls = "";
      if(it.opSign && it.reviewerSign) rowCls = "done-row";
      else if(it.filled && !it.opSign) rowCls = "pending-row";

      let content = "";
      if(it.type === "portal"){
        content = '<div class="subitems">' +
          PORTAL_KEYS.map(k => {
            const nm = 'sub_' + sk + '_' + id + '_' + esc(k);
            return '<div class="subitem"><span>' + esc(k) + '</span><span class="sub-radios">' +
              '<label><input type="radio" name="' + nm + '"' + (vals[k] === "正常" ? " checked" : "") + dis + dd + ' data-kind="sub" data-key="' + esc(k) + '" data-v="正常"> 正常</label>' +
              '<label><input type="radio" name="' + nm + '"' + (vals[k] === "異常" ? " checked" : "") + dis + dd + ' data-kind="sub" data-key="' + esc(k) + '" data-v="異常"> 異常</label>' +
              '</span></div>';
          }).join("") + '</div>';
      }else if(it.type === "sms"){
        content = '<div style="padding:4px 8px">' +
          SMS_KEYS.map(k =>
            '<div class="usage-grid"><span>D SMS,SG(' + esc(k) + ')</span>' +
            '<input type="number" min="0" max="100" value="' + esc(vals[k]) + '"' + dis + dd + ' data-kind="num" data-key="' + esc(k) + '" data-fk="' + fk + ':' + esc(k) + '" placeholder="%"></div>'
          ).join("") + '</div>';
      }else if(it.type === "rmf"){
        content = '<div style="padding:4px 8px">' +
          RMF_P.map(pp =>
            '<div class="rmf-grid"><b>' + pp + '</b>' +
            RMF_F.map(f => {
              const key = pp + "." + f;
              return '<span><span style="color:#64748b">' + f + '</span><input type="number" min="0" value="' + esc(vals[key]) + '"' + dis + dd + ' data-kind="num" data-key="' + key + '" data-fk="' + fk + ':' + key + '"></span>';
            }).join("") + '</div>'
          ).join("") + '</div>';
      }else if(it.type === "cabinet"){
        content = '<div style="padding:4px 8px">' +
          '<div class="radio-group">機櫃：' + ["正常","異常"].map(s =>
            '<label><input type="radio" name="ck_' + sk + '_' + id + '"' + (it.status === s ? " checked" : "") + dis + dd + ' data-kind="status" data-v="' + s + '"> ' + s + '</label>').join("") + '</div>' +
          '<div class="radio-group">進出登記簿：' + ["正常","異常"].map(s =>
            '<label><input type="radio" name="entry_' + sk + '_' + id + '"' + (it.entryLog === s ? " checked" : "") + dis + dd + ' data-kind="entry" data-v="' + s + '"> ' + s + '</label>').join("") + '</div>' +
          '</div>';
      }else{
        content = '<div class="radio-group">' +
          ["正常","異常"].map(s =>
            '<label><input type="radio" name="ck_' + sk + '_' + id + '"' + (it.status === s ? " checked" : "") + dis + dd + ' data-kind="status" data-v="' + s + '"> ' + s + '</label>').join("") +
          (it.holidaySkip ? '<label><input type="radio" name="ck_' + sk + '_' + id + '"' + (it.status === "假日不執行" ? " checked" : "") + dis + dd + ' data-kind="status" data-v="假日不執行"> 假日不執行</label>' : "") +
          '</div>';
      }

      const fillCell = it.opSign
        ? sigHtml(it.opSign) + (p.canUnsign ? ' <button type="button" class="review-btn-mini danger" data-act="unsign"' + dd + '>撤回</button>' : '')
        : (p.signable ? '<button type="button" class="review-btn-mini" data-act="sign"' + dd + '>簽名</button>' : '—');
      const revCell = it.reviewerSign
        ? sigHtml(it.reviewerSign, "sig-rev") + (p.canUnreview ? ' <button type="button" class="review-btn-mini danger" data-act="unreview"' + dd + '>撤回</button>' : '')
        : (p.reviewable ? '<button type="button" class="review-btn-mini" data-act="review"' + dd + '>覆核</button>' : '—');

      html += '<div class="check-item ' + rowCls + '">' +
        '<div class="top">' +
          '<span class="name">' + esc(it.name) + (it.holidaySkip ? ' <span style="font-weight:400;color:#94a3b8">(假日可略)</span>' : '') + '</span>' +
          '<input type="time" value="' + esc(it.time) + '"' + dis + dd + ' data-kind="time" data-fk="' + fk + ':time">' +
        '</div>' + content +
        '<div class="sig-row">' +
          '<div class="sig-cell">填寫：' + fillCell + '</div>' +
          '<div class="sig-cell">覆核：' + revCell + '</div>' +
        '</div></div>';
    });
    html += '</div>';
  });
  $("shiftChecksBody").innerHTML = html;
}
function findCheck(sk, id){ return ((log.checks || {})[sk] || []).find(x => x.defId === id); }
async function onCheckChange(e){
  const el = e.target;
  const sk = el.dataset.sk, id = el.dataset.id, kind = el.dataset.kind;
  if(!sk || !id || !kind) return;
  const path = L("/check/" + encodeURIComponent(sk) + "/" + encodeURIComponent(id) + "/field");
  if(kind === "time"){ await mutate("POST", path, {field: "time", value: el.value}); return; }
  if(kind === "entry"){ await mutate("POST", path, {field: "entryLog", value: el.dataset.v}); return; }
  if(kind === "num"){
    let v = el.value;
    if(v !== "" && +v < 0){ v = "0"; el.value = "0"; alert("⚠ 不可輸入負數"); }
    await mutate("POST", path, {field: "value", key: el.dataset.key, value: v});
    return;
  }
  if(kind === "status" || kind === "sub"){
    const v = el.dataset.v, key = el.dataset.key || "";
    const body = kind === "sub" ? {field: "value", key: key, value: v} : {field: "status", value: v};
    const item = findCheck(sk, id);
    const r = await mutate("POST", path, body);
    if(!r) return;
    if(v === "異常"){
      const itemName = (item ? item.name : id) + (key ? " - " + key : "");
      openRecordModal({
        date: currentDate, title: "⚠ 異常事件紀錄 - " + itemName, source: "check", shift: sk, taskCode: itemName,
        onCancel: () => mutate("POST", path, kind === "sub" ? {field: "value", key: key, value: ""} : {field: "status", value: ""})
      });
    }
  }
}
async function onCheckClick(e){
  const b = e.target.closest("button[data-act]");
  if(!b) return;
  const sk = b.dataset.sk, id = b.dataset.id, act = b.dataset.act;
  if(!sk || !id) return;
  const base = L("/check/" + encodeURIComponent(sk) + "/" + encodeURIComponent(id));
  if(act === "sign"){ b.disabled = true; await mutate("POST", base + "/sign"); }
  else if(act === "unsign"){ if(!confirm("撤回填寫簽章？\n（覆核也會一併清除）")) return; b.disabled = true; await mutate("POST", base + "/unsign"); }
  else if(act === "review"){ b.disabled = true; await mutate("POST", base + "/review"); }
  else if(act === "unreview"){ if(!confirm("撤回覆核簽章？")) return; b.disabled = true; await mutate("POST", base + "/unreview"); }
}

/* ===== 批次作業 ===== */
function taskInMine(t){
  const s = me.shift;
  return !!s && (t.assignedShift === s || (t.shift === "cross" && t.handoverEndShift === s));
}
function filteredTasks(){
  return (log.tasks || []).filter(t => {
    if(filters.mine && me.role === "OPERATOR" && me.shift && !taskInMine(t)) return false;
    if(filters.pending && t.done) return false;
    return true;
  });
}
function nextShiftOf(s){
  const order = ["day","evening","night","day"];
  const i = order.indexOf(s);
  return i < 0 ? "day" : order[i+1];
}
function renderTaskTable(){
  const tb = $("taskRows");
  const nd = addDays(currentDate, 1).slice(5);
  const rows = filteredTasks();
  if(!rows.length){
    tb.innerHTML = '<tr><td colspan="14" class="records-empty">（無符合篩選的批次作業）</td></tr>';
    return;
  }
  tb.innerHTML = rows.map(t => {
    const p = t.perm || {};
    const code = esc(t.code);
    const editable = !!p.editable;
    const dis = editable ? "" : " disabled";
    let cls = "";
    if(!t.shouldExecute) cls = "row-skip";
    else if(t.handoverFrom) cls = "row-handover";
    else if(t.forced) cls = "row-force";
    else if(t.abnormal) cls = "row-abn";
    else if(t.done) cls = "row-done";
    else if(t.delayed) cls = "row-delay";

    let shiftDisplay;
    if(t.shift === "cross"){
      const a = t.assignedShift || "night", b = t.handoverEndShift || "day";
      shiftDisplay = '<span class="shift-tag ' + (shiftTagCls[a]||"") + '">' + esc(shiftShort(a)) + '</span>→<span class="shift-tag ' + (shiftTagCls[b]||"") + '">' + esc(shiftLabel[b] ? shiftLabel[b].slice(0,1) : b) + '</span>';
    }else{
      shiftDisplay = '<span class="shift-tag ' + (shiftTagCls[t.assignedShift]||"") + '">' + esc(shiftShort(t.assignedShift)) + '</span>';
    }
    const handoverInfo = t.handoverFrom ? '<div class="handover-hint">↪ 從' + esc(shiftLabel[t.handoverFrom] || t.handoverFrom) + '交來</div>' : "";
    const forceInfo = t.forced ? '<div class="force-hint">⚡ ' + esc(t.forceReason) + (t.forcedBy ? '（' + esc(t.forcedBy) + '）' : '') + '</div>' : "";
    const dc = ' data-code="' + code + '"';
    const startCell = '<input type="time" value="' + esc(t.startTime) + '"' + dis + dc + ' data-f="startTime" data-fk="task:' + code + ':startTime">' +
      (t.startTime && isNextDayTime(t.startTime) ? '<div class="next-day">(' + esc(nd) + ')</div>' : "");
    const endCell = '<input type="time" value="' + esc(t.endTime) + '"' + dis + dc + ' data-f="endTime" data-fk="task:' + code + ':endTime">' +
      (t.endTime && isNextDayTime(t.endTime) ? '<div class="next-day">(' + esc(nd) + ')</div>' : "");
    const qtyCell = t.qtyLabel
      ? '<input type="number" min="0" placeholder="' + esc(t.qtyLabel) + '" value="' + esc(t.qtyValue) + '"' + dis + dc + ' data-f="qtyValue" data-fk="task:' + code + ':qty">'
      : "";

    let ops = "";
    if(p.editable) ops += '<button type="button" class="btn btn-ghost btn-mini" data-act="quick"' + dc + '>填現在</button>';
    if(p.canHandover) ops += '<button type="button" class="btn btn-info btn-mini" data-act="handover"' + dc + '>↪交班</button>';
    if(p.canForce) ops += '<button type="button" class="btn btn-purple btn-mini" data-act="force"' + dc + '>⚡強制</button>';

    return '<tr class="' + cls + '">' +
      '<td class="center code">' + code + '</td>' +
      '<td class="center">' + shiftDisplay + '</td>' +
      '<td>' + esc(t.name) + handoverInfo + forceInfo + '</td>' +
      '<td class="center" style="font-size:11px;color:#64748b">' + esc(t.scheduleLabel || t.schedule) + '</td>' +
      '<td class="center">' + esc(t.plannedStart) + (isNextDayTime(t.plannedStart) ? '<div class="next-day">隔日</div>' : "") + '</td>' +
      '<td>' + startCell + '</td>' +
      '<td>' + endCell + '</td>' +
      '<td>' + qtyCell + '</td>' +
      '<td class="center">' + (t.shouldExecute ? '<input type="checkbox" class="chk-done"' + (t.done ? " checked" : "") + dis + dc + '>' : "—") + '</td>' +
      '<td class="center">' + (t.shouldExecute ? '<input type="checkbox" class="chk-abn"' + (t.abnormal ? " checked" : "") + dis + dc + '>' : "—") + '</td>' +
      '<td><input type="text" value="' + esc(t.remark) + '"' + dis + dc + ' data-f="remark" data-fk="task:' + code + ':remark"></td>' +
      '<td class="center">' + sigHtml(t.opSign) + '</td>' +
      '<td class="center">' + (t.reviewerSign ? sigHtml(t.reviewerSign, "sig-rev") :
        (p.reviewable ? '<button type="button" class="btn btn-success btn-mini" data-act="review"' + dc + '>覆核</button>' : '<span class="sig-empty">—</span>')) + '</td>' +
      '<td class="center">' + ops + '</td></tr>';
  }).join("");
}
function findTask(code){ return (log.tasks || []).find(x => x.code === code); }
function T(code, suffix){ return L("/task/" + encodeURIComponent(code) + suffix); }
async function onTaskChange(e){
  const el = e.target;
  const code = el.dataset.code;
  if(!code) return;
  const t = findTask(code);
  if(!t) return;
  if(el.classList.contains("chk-done")){
    await mutate("POST", T(code, "/done"), {done: el.checked});
    return;
  }
  if(el.classList.contains("chk-abn")){
    if(el.checked){
      const r = await mutate("POST", T(code, "/abnormal"), {abnormal: true});
      if(!r) return;
      openRecordModal({
        date: currentDate, title: "⚠ 異常事件紀錄 - " + code, source: "batch",
        shift: me.shift || t.assignedShift || null, taskCode: code,
        onCancel: () => mutate("POST", T(code, "/abnormal"), {abnormal: false})
      });
    }else{
      await mutate("POST", T(code, "/abnormal"), {abnormal: false});
    }
    return;
  }
  const f = el.dataset.f;
  if(!f) return;
  let v = el.value;
  if(el.type === "number" && v !== "" && +v < 0){ v = "0"; el.value = "0"; alert("⚠ 不可輸入負數"); }
  await mutate("POST", T(code, "/field"), {field: f, value: v});
}
async function onTaskClick(e){
  const b = e.target.closest("button[data-act]");
  if(!b) return;
  const code = b.dataset.code, act = b.dataset.act;
  const t = findTask(code);
  if(!t) return;
  if(act === "quick"){ b.disabled = true; await mutate("POST", T(code, "/quick")); }
  else if(act === "handover"){
    const next = nextShiftOf(t.assignedShift);
    if(!confirm("將 " + code + " 交給「" + shiftLabel[next] + "」？")) return;
    b.disabled = true; await mutate("POST", T(code, "/handover"));
  }
  else if(act === "force"){
    const reason = prompt("強制執行 " + code + " 的原因：");
    if(!reason) return;
    b.disabled = true; await mutate("POST", T(code, "/force"), {reason: reason});
  }
  else if(act === "review"){
    if(!confirm("確認覆核 " + code + "？")) return;
    b.disabled = true; await mutate("POST", T(code, "/review"));
  }
}

/* ===== JOB ERROR ===== */
function jeVal(k){
  const v = log.jobError ? log.jobError[k] : 0;
  if(v === "" || v === null || v === undefined) return null;
  const n = +v; return isNaN(n) ? null : n;
}
function renderJobError(){
  const p = log.perm.canEditJobError || {};
  const total = SHIFTS.reduce((s,k) => s + (jeVal(k) || 0), 0);
  $("jobError").innerHTML = SHIFTS.map(k => {
    const v = jeVal(k);
    const hasError = v > 0;
    const editHint = (k === "night" && me.role === "OPERATOR" && me.shift === "day") ? '<div style="font-size:10px;color:#0ea5e9;margin-top:3px">早班可彙整</div>' : "";
    return '<div class="joberror-card' + (hasError ? " has-error" : "") + '">' +
      '<div class="label">' + shiftIcon[k] + ' ' + shiftLabel[k] + '（' + jobErrorAt[k] + '）</div>' +
      '<input type="number" min="0" value="' + (v === null ? "" : v) + '"' + (p[k] ? "" : " disabled") + ' data-k="' + k + '" data-fk="je:' + k + '" placeholder="0"> 支' +
      editHint + '</div>';
  }).join("") +
  '<div class="joberror-card total' + (total > 0 ? " has-error" : "") + '"><div class="label">合計</div><div class="total-num">' + total + ' 支</div></div>';
}
async function onJobErrorChange(e){
  const el = e.target;
  const k = el.dataset.k;
  if(!k) return;
  let newVal = parseInt(el.value, 10);
  if(isNaN(newVal)) newVal = 0;
  if(newVal < 0){ newVal = 0; el.value = "0"; alert("⚠ 不可輸入負數"); }
  const oldVal = jeVal(k) || 0;
  const diff = newVal - oldVal;
  const date = currentDate;
  const r = await mutate("POST", L("/joberror"), {shift: k, value: newVal});
  if(!r) return;
  if(diff > 0){
    for(let i = 0; i < diff; i++){
      pendingModals.push(() => openRecordModal({
        date: date, title: "🔢 JOB ERROR 事件紀錄 - " + shiftLabel[k], source: "joberror", shift: k,
        taskCode: "JOB ERROR (" + shiftLabel[k] + ")",
        descPlaceholder: "JOB ERROR " + newVal + " 支，原因/處理過程...",
        cancelLabel: "稍後填寫"
      }));
    }
    processPendingModals();
  }
}

/* ===== 重要記錄事項 ===== */
function sourceTag(src){
  if(src === "batch") return '<span class="source">📋 批次異常</span>';
  if(src === "joberror") return '<span class="source">🔢 JOB ERROR</span>';
  if(src === "check") return '<span class="source">📋 檢查異常</span>';
  return '<span class="source">➕ 手動新增</span>';
}
function renderRecords(){
  const list = log.records || [];
  const el = $("recordsList");
  const canManage = !!log.perm.canManageRecords;
  $("recordsAdd").style.display = canManage ? "" : "none";
  if(!list.length){ el.innerHTML = '<div class="records-empty">尚無重要記錄事項</div>'; return; }
  el.innerHTML = list.map(r =>
    '<div class="record-item">' +
      '<div class="time">⏰ ' + esc(r.time || "--:--") + (r.taskCode ? ' ｜ ' + esc(r.taskCode) : "") +
        (r.shift && shiftLabel[r.shift] ? ' ｜ ' + esc(shiftLabel[r.shift]) : "") + sourceTag(r.source) + '</div>' +
      '<div><b>' + esc(r.description) + '</b></div>' +
      '<div class="meta">通知 SP: ' + esc(r.notifySP || "-") + ' ｜ 通知 AP: ' + esc(r.notifyAP || "-") +
        ' ｜ 復原: ' + (r.recoverTime ? esc(r.recoverTime) : '<span style="color:#ef4444;font-weight:600">⚠ 未填</span>') +
        ' ｜ 事件單號: ' + esc(r.ticket || "-") + ' ｜ OP: ' + esc(r.op) + '</div>' +
      (canManage ? '<div class="ops">' +
        '<button type="button" class="btn btn-info btn-mini" data-act="edit" data-id="' + esc(r.id) + '">✏ 補填</button>' +
        '<button type="button" class="btn btn-danger btn-mini" data-act="del" data-id="' + esc(r.id) + '">刪除</button>' +
      '</div>' : "") +
    '</div>'
  ).join("");
}
async function onRecordsClick(e){
  const b = e.target.closest("button[data-act]");
  if(!b) return;
  const id = b.dataset.id;
  const rec = (log.records || []).find(r => String(r.id) === String(id));
  if(!rec) return;
  if(b.dataset.act === "edit"){ openRecordEditModal(rec); return; }
  if(b.dataset.act === "del"){
    if(!confirm("確定刪除這筆事件？")) return;
    b.disabled = true;
    await mutate("DELETE", L("/record/" + encodeURIComponent(id)));
  }
}

/* ===== 浮窗 ===== */
function closeModal(){
  $("modalRoot").innerHTML = "";
  setTimeout(processPendingModals, 100);
}
function processPendingModals(){
  if(!pendingModals.length) return;
  if(modalOpen()) return;
  const next = pendingModals.shift();
  next();
}
function recordFormHtml(o, rec){
  rec = rec || {};
  return '<label>發生時間</label><input type="time" id="r-time" value="' + esc(rec.time !== undefined ? rec.time : hhmm(nowServer())) + '">' +
    (o.showTask ? '<label>相關項目（選填）</label><input id="r-task" value="' + esc(rec.taskCode) + '" placeholder="例：A6 / DMS / 機房進人...">' : '') +
    '<label>狀況描述 *</label><textarea id="r-desc" rows="3" placeholder="' + esc(o.descPlaceholder || "必填") + '">' + esc(rec.description) + '</textarea>' +
    '<label>通知 SP 人員</label><input id="r-sp" value="' + esc(rec.notifySP) + '">' +
    '<label>通知 AP 人員</label><input id="r-ap" value="' + esc(rec.notifyAP) + '">' +
    '<label>復原時間' + (o.recoverRequired ? ' *' : '') + '</label><input type="time" id="r-rec" value="' + esc(rec.recoverTime) + '">' +
    '<label>事件單號</label><input id="r-ticket" value="' + esc(rec.ticket) + '">';
}
function readRecordForm(o){
  return {
    time: $("r-time").value,
    taskCode: o.showTask ? $("r-task").value.trim() : (o.taskCode || ""),
    description: $("r-desc").value.trim(),
    notifySP: $("r-sp").value.trim(),
    notifyAP: $("r-ap").value.trim(),
    recoverTime: $("r-rec").value,
    ticket: $("r-ticket").value.trim()
  };
}
/* o: {date,title,source,shift,taskCode,showTask,descPlaceholder,cancelLabel,onCancel} */
function openRecordModal(o){
  const root = $("modalRoot");
  root.innerHTML = '<div class="modal-bg"><div class="modal">' +
    '<h3>' + esc(o.title) + '</h3>' + recordFormHtml(o) +
    '<div class="modal-actions">' +
      '<button type="button" class="btn btn-ghost" id="r-cancel">' + esc(o.cancelLabel || "取消") + '</button>' +
      '<button type="button" class="btn btn-primary" id="r-save">儲存</button>' +
    '</div></div></div>';
  $("r-cancel").addEventListener("click", () => { closeModal(); if(o.onCancel) o.onCancel(); });
  $("r-save").addEventListener("click", async () => {
    const body = readRecordForm(o);
    if(!body.description){ alert("狀況描述必填"); return; }
    body.source = o.source || "manual";
    body.shift = o.shift || null;
    const btn = $("r-save"); btn.disabled = true;
    const r = await mutate("POST", "/api/log/" + encodeURIComponent(o.date || currentDate) + "/record", body);
    if(!r){ btn.disabled = false; return; }
    closeModal();
  });
  $("r-desc").focus();
}
function openRecordEditModal(rec){
  const root = $("modalRoot");
  const o = {showTask: true, recoverRequired: true};
  root.innerHTML = '<div class="modal-bg"><div class="modal">' +
    '<h3>✏ 補填事件紀錄</h3>' + recordFormHtml(o, rec) +
    '<div class="modal-actions">' +
      '<button type="button" class="btn btn-ghost" id="r-cancel">取消</button>' +
      '<button type="button" class="btn btn-primary" id="r-save">儲存</button>' +
    '</div></div></div>';
  $("r-cancel").addEventListener("click", closeModal);
  $("r-save").addEventListener("click", async () => {
    const body = readRecordForm(o);
    if(!body.description){ alert("狀況描述必填"); return; }
    const btn = $("r-save"); btn.disabled = true;
    const r = await mutate("PUT", L("/record/" + encodeURIComponent(rec.id)), body);
    if(!r){ btn.disabled = false; return; }
    closeModal();
  });
}

/* ===== 簽核 / 動作區 ===== */
function renderActions(){
  const p = log.perm;
  const btn = (act, label, cls) => '<button type="button" class="btn ' + cls + '" data-act="' + act + '">' + label + '</button>';
  let h = "";
  if(p.canSubmitShift) h += btn("submitShift", "📤 本班送出（" + esc(shiftLabel[me.shift] || "") + "）", "btn-info");
  else if(p.shiftSubmitted) h += '<span style="color:#10b981;font-weight:600">✓ 本班已送出</span>';
  if(p.canSubmitAll) h += btn("submitAll", "📨 整合送簽（給副科）", "btn-primary");
  if(p.canRecall) h += btn("recall", "↩ 取回", "btn-warn");
  if(p.canApprove) h += btn("approve", me.role === "CHIEF" ? "✅ 科長核准" : "✅ 副科審核通過", "btn-success");
  if(p.canReject) h += btn("reject", "❌ 退件", "btn-danger");
  if(p.canUnlock) h += btn("unlock", "🔓 解鎖修改", "btn-warn");
  h += '<span class="spacer"></span>';
  h += btn("print", "🖨️ 列印", "btn-ghost") + btn("pdf", "📄 下載 PDF", "btn-ghost");

  const ap = log.approval || {};
  const stamps = [["經辦", ap.operator], ["副科", ap.deputy], ["科長", ap.chief]].filter(x => x[1]);
  const rc = log.reviewComments || [];
  if(stamps.length || rc.length){
    h += '<div class="review-comments">';
    if(stamps.length) h += '<div class="rc-item">📝 簽核：' + stamps.map(x => esc(x[0]) + ' ' + esc(x[1].user) + '（' + esc(fmtTs(x[1].time)) + '）').join('　｜　') + '</div>';
    rc.forEach(c => {
      h += '<div class="rc-item"><span class="rc-meta">' + esc(fmtTs(c.time)) + ' ' + esc(c.role) + ' ' + esc(c.by) + '：</span>' + esc(c.text) + '</div>';
    });
    h += '</div>';
  }
  $("actionArea").innerHTML = h;
}
async function onActionClick(e){
  const b = e.target.closest("button[data-act]");
  if(!b) return;
  const act = b.dataset.act;
  let r;
  switch(act){
    case "submitShift":
      if(!confirm("確定送出「" + (shiftLabel[me.shift] || "本班") + "」的日誌？")) return;
      b.disabled = true;
      r = await mutate("POST", L("/submit-shift"), {shift: me.shift});
      if(r){ alert("✅ " + (shiftLabel[me.shift] || "本班") + " 已送出"); refreshDates(); }
      break;
    case "submitAll":
      if(!confirm("確定整合 " + currentDate + " 的 3 班日誌送簽給副科？")) return;
      b.disabled = true;
      r = await mutate("POST", L("/submit-all"));
      if(r){ alert("✅ 已送簽"); refreshDates(); }
      break;
    case "recall":
      if(!confirm("確定取回？")) return;
      b.disabled = true;
      r = await mutate("POST", L("/recall"));
      if(r) refreshDates();
      break;
    case "approve": {
      const txt = prompt("審核意見（選填）：");
      if(txt === null) return;
      b.disabled = true;
      r = await mutate("POST", L("/approve"), {comment: txt.trim()});
      if(r) refreshDates();
      break;
    }
    case "reject": {
      const reason = prompt("退件原因（必填）：");
      if(!reason || !reason.trim()) return;
      b.disabled = true;
      r = await mutate("POST", L("/reject"), {reason: reason.trim()});
      if(r) refreshDates();
      break;
    }
    case "unlock": {
      const reason = prompt("解鎖原因：");
      if(!reason || !reason.trim()) return;
      b.disabled = true;
      r = await mutate("POST", L("/unlock"), {reason: reason.trim()});
      if(r) refreshDates();
      break;
    }
    case "print":
      window.open(CTX + "/print/" + encodeURIComponent(currentDate), "_blank");
      break;
    case "pdf":
      downloadUrl(CTX + "/pdf/" + encodeURIComponent(currentDate));
      break;
  }
  if(b.disabled && !r) b.disabled = false;
}

/* ===== 跑馬燈 ===== */
function updateMarquee(){
  const msgs = Array.isArray(log.reminders) ? log.reminders : [];
  $("marquee").textContent = msgs.length ? msgs.join("　｜　") : "✅ 一切正常";
}

/* ===== 統計頁 ===== */
async function renderStats(){
  const el = $("statsContent");
  el.innerHTML = '<div class="section"><div class="section-header static"><h2>📊 統計總覽</h2></div><div class="section-body" style="color:#94a3b8">⏳ 載入中...</div></div>';
  let s;
  try{ s = await api("GET", "/api/stats"); }
  catch(e){ el.innerHTML = '<div class="section"><div class="section-body" style="color:#ef4444">⚠ 統計載入失敗</div></div>'; return; }
  s = s || {};
  const card = (label, num, color) =>
    '<div class="stat-card"><div class="num" style="color:' + color + '">' + esc(num === undefined || num === null ? 0 : num) + '</div><div class="label">' + label + '</div></div>';
  el.innerHTML = '<div class="section"><div class="section-header static"><h2>📊 統計總覽</h2></div>' +
    '<div class="section-body"><div class="stats-grid">' +
      card("📅 統計天數", s.days, "#1e40af") +
      card("⚠ 異常", s.abnormal, "#f59e0b") +
      card("🔢 JOB ERROR", s.jobError, "#ef4444") +
      card("📌 重要事件", s.records, "#8b5cf6") +
    '</div>' +
    (s.forced !== undefined ? '<div class="note">⚡ 強制執行：' + esc(s.forced) + ' 次</div>' : '') +
    '</div></div>';
}

/* ===== 項目管理 ===== */
async function loadDefs(force){
  if(defs && !force) return defs;
  defs = await api("GET", "/api/defs");
  return defs;
}
function isAdmin(){ return !!(me && me.isAdmin); }
function labelOf(group, key){
  const l = (defs && defs.labels && defs.labels[group]) || {};
  return l[key] || key || "";
}
function pill(on){ return on ? '<span class="pill pill-on">✓ 啟用中</span>' : '<span class="pill pill-off">⏸ 已停用</span>'; }
function selectHtml(id, map, selected, extra){
  return '<select id="' + id + '" class="adm-input"' + (extra || "") + '>' +
    Object.keys(map).map(k => '<option value="' + esc(k) + '"' + (selected === k ? " selected" : "") + '>' + esc(map[k]) + '</option>').join("") + '</select>';
}
function shiftMap(withCross){
  const m = {day:"早班", evening:"小夜班", night:"大夜班"};
  if(withCross) m.cross = "跨班接力";
  return m;
}
function applyBar(kind){
  if(!isAdmin()) return "";
  const note = kind === "task" ? "（已執行的不動）" : "（已填寫的不動）";
  return '<div class="apply-today-bar"><div class="info">💡 修改後可按右側按鈕套用到今日日誌' + note + '</div>' +
    '<button type="button" class="btn btn-warn" data-act="apply" data-kind="' + kind + '">🔄 套用變更到今日日誌</button></div>';
}
function readonlyNote(){ return isAdmin() ? "" : '<div class="readonly-note">🔒 唯讀：僅系統管理者可新增 / 修改項目定義。</div>'; }

async function openAdmin(){
  try{ await loadDefs(); }
  catch(e){ $("subtab-batch").innerHTML = '<div class="section"><div class="section-body" style="color:#ef4444">⚠ 項目定義載入失敗</div></div>'; return; }
  renderAdminAll();
  if(activeSubtab === "history") renderHistory();
  if(activeSubtab === "data") renderDataAdmin();
}
function renderAdminAll(){
  if(!defs) return;
  renderTaskAdmin();
  renderEquipAdmin();
  renderCheckAdmin();
}

/* --- 批次作業定義 --- */
function renderTaskAdmin(){
  const list = defs.taskDefs || [];
  const sched = (defs.labels && defs.labels.schedule) || {};
  const admin = isAdmin();
  let html = applyBar("task") + readonlyNote();
  if(admin){
    html += '<div class="section"><div class="section-header static"><h2>⚙️ 新增項目</h2></div><div class="section-body">' +
      '<div class="adm-form">' +
        '<label>代號</label><input id="adm-code" class="adm-input" placeholder="A33">' +
        '<label>名稱</label><input id="adm-name" class="adm-input" placeholder="名稱">' +
        '<label>條件</label>' + selectHtml("adm-sched", sched, "daily") +
        '<label>預計時間</label><input id="adm-time" type="time" class="adm-input" value="14:00">' +
        '<label>班別</label>' + selectHtml("adm-shift", shiftMap(true), "day") +
        '<label>結束時間</label><label class="inline"><input type="checkbox" id="adm-hasend"> 需填寫結束時間</label>' +
        '<label>數量欄位名稱</label><input id="adm-qty" class="adm-input" placeholder="例：ATM筆數（不需要則留空）">' +
      '</div>' +
      '<button type="button" class="btn btn-primary" data-act="task-add">➕ 新增</button>' +
      '</div></div>';
  }
  html += '<div class="section"><div class="section-header static"><h2>📋 項目清單（共 ' + list.length + ' 項）</h2></div>' +
    '<div class="section-body" style="padding:0"><table class="excel"><thead><tr>' +
    '<th>代號</th><th>名稱</th><th>條件</th><th>預計</th><th>班別</th><th>結束</th><th>數量</th><th>狀態</th>' + (admin ? '<th style="width:200px">操作</th>' : '') +
    '</tr></thead><tbody>' +
    list.map(t =>
      '<tr class="' + (t.enabled === false ? "row-disabled" : "") + '"><td class="center code">' + esc(t.code) + '</td><td>' + esc(t.name) + '</td>' +
      '<td class="center" style="font-size:12px">' + esc(sched[t.schedule] || t.schedule) + '</td>' +
      '<td class="center">' + esc(t.plannedStart) + '</td>' +
      '<td class="center"><span class="shift-tag ' + (shiftTagCls[t.shift || "day"] || "") + '">' + (t.shift === "cross" ? "跨班" : esc(shiftShort(t.shift || "day"))) + '</span></td>' +
      '<td class="center">' + (t.hasEnd ? "✓" : "—") + '</td>' +
      '<td class="center" style="font-size:12px">' + esc(t.qtyLabel || "—") + '</td>' +
      '<td class="center">' + pill(t.enabled !== false) + '</td>' +
      (admin ? '<td class="center">' +
        '<button type="button" class="btn btn-primary btn-mini" data-act="task-edit" data-code="' + esc(t.code) + '">✏修改</button>' +
        '<button type="button" class="btn ' + (t.enabled !== false ? "btn-warn" : "btn-success") + ' btn-mini" data-act="task-toggle" data-code="' + esc(t.code) + '">' + (t.enabled !== false ? "⏸停用" : "▶啟用") + '</button>' +
        '<button type="button" class="btn btn-danger btn-mini" data-act="task-del" data-code="' + esc(t.code) + '">刪除</button>' +
      '</td>' : '') + '</tr>'
    ).join("") +
    (list.length ? "" : '<tr><td colspan="9" class="records-empty">尚無項目</td></tr>') +
    '</tbody></table></div></div>';
  $("subtab-batch").innerHTML = html;
}
function openTaskEditModal(t){
  const sched = (defs.labels && defs.labels.schedule) || {};
  const root = $("modalRoot");
  root.innerHTML = '<div class="modal-bg"><div class="modal">' +
    '<h3>✏ 修改項目 ' + esc(t.code) + '</h3>' +
    '<label>代號</label><input id="em-code" value="' + esc(t.code) + '">' +
    '<label>名稱</label><input id="em-name" value="' + esc(t.name) + '">' +
    '<label>條件</label>' + selectHtml("em-sched", sched, t.schedule) +
    '<label>預計時間</label><input type="time" id="em-time" value="' + esc(t.plannedStart) + '">' +
    '<label>班別</label>' + selectHtml("em-shift", shiftMap(true), t.shift || "day") +
    '<label class="inline" style="margin-top:12px"><input type="checkbox" id="em-hasend"' + (t.hasEnd ? " checked" : "") + '> 需填寫結束時間</label>' +
    '<label>數量欄位名稱</label><input id="em-qty" value="' + esc(t.qtyLabel || "") + '" placeholder="不需要則留空">' +
    '<div class="modal-actions">' +
      '<button type="button" class="btn btn-ghost" id="em-cancel">取消</button>' +
      '<button type="button" class="btn btn-primary" id="em-save">儲存</button>' +
    '</div></div></div>';
  $("em-cancel").addEventListener("click", closeModal);
  $("em-save").addEventListener("click", async () => {
    const code = $("em-code").value.trim() || t.code;
    const body = {
      code: t.code, name: $("em-name").value.trim() || t.name,
      schedule: $("em-sched").value, plannedStart: $("em-time").value || "00:00",
      shift: $("em-shift").value, hasEnd: $("em-hasend").checked,
      qtyLabel: $("em-qty").value.trim() || null,
      enabled: t.enabled !== false, seq: t.seq
    };
    if(code !== t.code) body.newCode = code;
    $("em-save").disabled = true;
    if(await defsWrite("PUT", "/api/defs/task/" + encodeURIComponent(t.code), body)){ closeModal(); alert("✅ 已更新"); }
    else $("em-save").disabled = false;
  });
}

/* --- 系統設備定義 --- */
function renderEquipAdmin(){
  const list = defs.equipDefs || [];
  const types = (defs.labels && defs.labels.equipType) || {};
  const admin = isAdmin();
  let html = applyBar("equip") + readonlyNote();
  if(admin){
    html += '<div class="section"><div class="section-header static"><h2>➕ 新增系統設備項目</h2></div><div class="section-body">' +
      '<div class="adm-form">' +
        '<label>名稱</label><input id="se-name" class="adm-input" placeholder="例如：DMS 第4次">' +
        '<label>類型</label>' + selectHtml("se-type", types, "status") +
        '<label>班別</label>' + selectHtml("se-shift", shiftMap(false), "day") +
        '<label>預計時間</label><input id="se-time" type="time" class="adm-input">' +
        '<label id="se-opts-label">狀態選項</label><input id="se-opts" class="adm-input" value="正常,異常" placeholder="以逗號分隔，例：正常,假日不執行,異常">' +
      '</div>' +
      '<button type="button" class="btn btn-primary" data-act="equip-add">➕ 新增</button>' +
      '</div></div>';
  }
  html += '<div class="section"><div class="section-header static"><h2>📋 系統設備項目清單（共 ' + list.length + ' 項）</h2></div>' +
    '<div class="section-body" style="padding:0"><table class="excel"><thead><tr>' +
    '<th>名稱</th><th>類型</th><th>班別</th><th>時間</th><th>狀態選項</th><th>狀態</th>' + (admin ? '<th style="width:170px">操作</th>' : '') +
    '</tr></thead><tbody>' +
    list.map(d =>
      '<tr class="' + (d.enabled ? "" : "row-disabled") + '"><td>' + esc(d.name) + '</td>' +
      '<td class="center" style="font-size:12px">' + esc(types[d.type] || d.type) + '</td>' +
      '<td class="center"><span class="shift-tag ' + (shiftTagCls[d.shift] || "") + '">' + esc(shiftShort(d.shift)) + '</span></td>' +
      '<td class="center">' + esc(d.time || "—") + '</td>' +
      '<td class="center" style="font-size:11px">' + (d.type === "status" && Array.isArray(d.statusOptions) ? esc(d.statusOptions.join(" / ")) : "—") + '</td>' +
      '<td class="center">' + pill(!!d.enabled) + '</td>' +
      (admin ? '<td class="center">' +
        '<button type="button" class="btn btn-primary btn-mini" data-act="equip-edit" data-id="' + esc(d.id) + '">✏修改</button>' +
        '<button type="button" class="btn ' + (d.enabled ? "btn-warn" : "btn-success") + ' btn-mini" data-act="equip-toggle" data-id="' + esc(d.id) + '">' + (d.enabled ? "⏸停用" : "▶啟用") + '</button>' +
      '</td>' : '') + '</tr>'
    ).join("") +
    (list.length ? "" : '<tr><td colspan="7" class="records-empty">尚無項目</td></tr>') +
    '</tbody></table></div></div>';
  $("subtab-sysequip").innerHTML = html;
  syncEquipOptsVisibility("se-type", "se-opts", "se-opts-label");
}
function syncEquipOptsVisibility(typeId, optsId, labelId){
  const t = $(typeId), o = $(optsId), l = $(labelId);
  if(!t || !o) return;
  const show = t.value === "status";
  o.style.display = show ? "" : "none";
  if(l) l.style.display = show ? "" : "none";
}
function parseOpts(s){ return String(s || "").split(/[,，]/).map(x => x.trim()).filter(Boolean); }
function openEquipEditModal(d){
  const types = (defs.labels && defs.labels.equipType) || {};
  const root = $("modalRoot");
  root.innerHTML = '<div class="modal-bg"><div class="modal">' +
    '<h3>✏ 修改系統設備項目</h3>' +
    '<label>名稱</label><input id="se-em-name" value="' + esc(d.name) + '">' +
    '<label>類型</label>' + selectHtml("se-em-type", types, d.type) +
    '<label>班別</label>' + selectHtml("se-em-shift", shiftMap(false), d.shift) +
    '<label>預計時間</label><input type="time" id="se-em-time" value="' + esc(d.time || "") + '">' +
    '<label id="se-em-opts-label">狀態選項</label><input id="se-em-opts" value="' + esc((d.statusOptions || ["正常","異常"]).join(",")) + '" placeholder="以逗號分隔">' +
    '<div class="modal-actions">' +
      '<button type="button" class="btn btn-ghost" id="se-em-cancel">取消</button>' +
      '<button type="button" class="btn btn-primary" id="se-em-save">儲存</button>' +
    '</div></div></div>';
  syncEquipOptsVisibility("se-em-type", "se-em-opts", "se-em-opts-label");
  $("se-em-type").addEventListener("change", () => syncEquipOptsVisibility("se-em-type", "se-em-opts", "se-em-opts-label"));
  $("se-em-cancel").addEventListener("click", closeModal);
  $("se-em-save").addEventListener("click", async () => {
    const type = $("se-em-type").value;
    const body = {
      id: d.id, name: $("se-em-name").value.trim() || d.name, type: type,
      shift: $("se-em-shift").value, time: $("se-em-time").value || "",
      statusOptions: type === "status" ? parseOpts($("se-em-opts").value) : null,
      enabled: !!d.enabled, seq: d.seq
    };
    if(type === "status" && !body.statusOptions.length){ alert("狀態選項至少一個"); return; }
    $("se-em-save").disabled = true;
    if(await defsWrite("PUT", "/api/defs/equip/" + encodeURIComponent(d.id), body)){ closeModal(); alert("✅ 已更新"); }
    else $("se-em-save").disabled = false;
  });
}

/* --- 三班檢查定義 --- */
function renderCheckAdmin(){
  const list = defs.checkDefs || [];
  const types = (defs.labels && defs.labels.checkType) || {};
  const admin = isAdmin();
  let html = applyBar("check") + readonlyNote();
  if(admin){
    html += '<div class="section"><div class="section-header static"><h2>➕ 新增三班檢查項目</h2></div><div class="section-body">' +
      '<div class="adm-form">' +
        '<label>名稱</label><input id="sc-name" class="adm-input" placeholder="例如：自訂檢查">' +
        '<label>類型</label>' + selectHtml("sc-type", types, "general") +
        '<label>班別</label>' + selectHtml("sc-shift", shiftMap(false), "day") +
        '<label>預計時間</label><input id="sc-time" type="time" class="adm-input" value="08:00">' +
        '<label>假日不執行</label>' + selectHtml("sc-holiday", {"false":"否（每日執行）","true":"是（可選假日不執行）"}, "false") +
      '</div>' +
      '<button type="button" class="btn btn-primary" data-act="check-add">➕ 新增</button>' +
      '</div></div>';
  }
  html += '<div class="section"><div class="section-header static"><h2>📋 三班檢查項目清單（共 ' + list.length + ' 項）</h2></div>' +
    '<div class="section-body" style="padding:0"><table class="excel"><thead><tr>' +
    '<th>名稱</th><th>類型</th><th>班別</th><th>時間</th><th>假日</th><th>狀態</th>' + (admin ? '<th style="width:170px">操作</th>' : '') +
    '</tr></thead><tbody>' +
    list.map(d =>
      '<tr class="' + (d.enabled ? "" : "row-disabled") + '"><td>' + esc(d.name) + '</td>' +
      '<td class="center" style="font-size:12px">' + esc(types[d.type] || d.type) + '</td>' +
      '<td class="center"><span class="shift-tag ' + (shiftTagCls[d.shift] || "") + '">' + esc(shiftShort(d.shift)) + '</span></td>' +
      '<td class="center">' + esc(d.time || "—") + '</td>' +
      '<td class="center" style="font-size:11px">' + (d.holidaySkip ? "✓ 可選" : "—") + '</td>' +
      '<td class="center">' + pill(!!d.enabled) + '</td>' +
      (admin ? '<td class="center">' +
        '<button type="button" class="btn btn-primary btn-mini" data-act="check-edit" data-id="' + esc(d.id) + '">✏修改</button>' +
        '<button type="button" class="btn ' + (d.enabled ? "btn-warn" : "btn-success") + ' btn-mini" data-act="check-toggle" data-id="' + esc(d.id) + '">' + (d.enabled ? "⏸停用" : "▶啟用") + '</button>' +
      '</td>' : '') + '</tr>'
    ).join("") +
    (list.length ? "" : '<tr><td colspan="7" class="records-empty">尚無項目</td></tr>') +
    '</tbody></table></div></div>';
  $("subtab-checks").innerHTML = html;
}
function openCheckEditModal(d){
  const types = (defs.labels && defs.labels.checkType) || {};
  const root = $("modalRoot");
  root.innerHTML = '<div class="modal-bg"><div class="modal">' +
    '<h3>✏ 修改三班檢查項目</h3>' +
    '<label>名稱</label><input id="sc-em-name" value="' + esc(d.name) + '">' +
    '<label>類型</label>' + selectHtml("sc-em-type", types, d.type) +
    '<label>班別</label>' + selectHtml("sc-em-shift", shiftMap(false), d.shift) +
    '<label>預計時間</label><input type="time" id="sc-em-time" value="' + esc(d.time || "") + '">' +
    '<label>假日不執行</label>' + selectHtml("sc-em-holiday", {"false":"否","true":"是"}, d.holidaySkip ? "true" : "false") +
    '<div class="modal-actions">' +
      '<button type="button" class="btn btn-ghost" id="sc-em-cancel">取消</button>' +
      '<button type="button" class="btn btn-primary" id="sc-em-save">儲存</button>' +
    '</div></div></div>';
  $("sc-em-cancel").addEventListener("click", closeModal);
  $("sc-em-save").addEventListener("click", async () => {
    const body = {
      id: d.id, name: $("sc-em-name").value.trim() || d.name, type: $("sc-em-type").value,
      shift: $("sc-em-shift").value, time: $("sc-em-time").value || "",
      holidaySkip: $("sc-em-holiday").value === "true", enabled: !!d.enabled, seq: d.seq
    };
    $("sc-em-save").disabled = true;
    if(await defsWrite("PUT", "/api/defs/check/" + encodeURIComponent(d.id), body)){ closeModal(); alert("✅ 已更新"); }
    else $("sc-em-save").disabled = false;
  });
}

/* 定義寫入：成功時回傳值是更新後的 /api/defs 內容 */
async function defsWrite(method, path, body){
  try{
    const r = await api(method, path, body);
    if(r && (r.taskDefs || r.equipDefs || r.checkDefs)) defs = r;
    else await loadDefs(true);
    renderAdminAll();
    return true;
  }catch(e){
    return false;
  }
}

async function applyToday(kind){
  const names = {task:"批次作業", equip:"系統設備", check:"三班檢查"};
  const msg = kind === "task"
    ? "套用最新「批次作業」設定到今日日誌？\n\n• 已執行的項目不動\n• 新增的項目會出現\n• 刪除的項目（未執行）會消失"
    : "套用最新「" + names[kind] + "」設定到今日日誌？\n\n• 已填寫的項目不動\n• 新增的項目會出現\n• 停用的項目（未填）會消失";
  if(!confirm(msg)) return;
  try{
    const r = await api("POST", "/api/defs/apply-today", {kind: kind});
    // 回傳的是「今日」完整日誌；只有目前正在看今日時才直接重繪，否則切換到今日時會重新載入
    if(isLog(r) && r.date === currentDate) setLog(r);
    alert("✅ 已套用" + names[kind] + "變更到今日");
  }catch(e){}
}

/* --- 項目管理事件（委派） --- */
async function onAdminClick(e){
  const b = e.target.closest("button[data-act], td.date-link");
  if(!b) return;
  const act = b.dataset.act;
  if(b.classList.contains("date-link")){
    const d = b.dataset.date;
    if(d){ switchTab("log"); await loadLog(d); }
    return;
  }
  if(!act) return;
  if(!defs && act.indexOf("hist-") !== 0 && act.indexOf("data-") !== 0) return;

  switch(act){
    /* 批次作業 */
    case "task-add": {
      const code = $("adm-code").value.trim(), name = $("adm-name").value.trim();
      if(!code || !name){ alert("代號與名稱必填"); return; }
      if((defs.taskDefs || []).some(t => t.code === code)){ alert("代號重複"); return; }
      b.disabled = true;
      const ok = await defsWrite("POST", "/api/defs/task", {
        code: code, name: name, schedule: $("adm-sched").value, plannedStart: $("adm-time").value || "00:00",
        shift: $("adm-shift").value, hasEnd: $("adm-hasend").checked, qtyLabel: $("adm-qty").value.trim() || null, enabled: true
      });
      if(ok) alert("✅ 已新增 " + code); else b.disabled = false;
      break;
    }
    case "task-edit": { const t = (defs.taskDefs || []).find(x => x.code === b.dataset.code); if(t) openTaskEditModal(t); break; }
    case "task-toggle": {
      const t = (defs.taskDefs || []).find(x => x.code === b.dataset.code); if(!t) return;
      if(t.enabled !== false && !confirm("停用「" + t.code + " " + t.name + "」？")) return;
      b.disabled = true;
      await defsWrite("PUT", "/api/defs/task/" + encodeURIComponent(t.code), Object.assign({}, t, {enabled: t.enabled === false}));
      break;
    }
    case "task-del": {
      const t = (defs.taskDefs || []).find(x => x.code === b.dataset.code); if(!t) return;
      if(!confirm("確定刪除 " + t.code + "？")) return;
      b.disabled = true;
      await defsWrite("DELETE", "/api/defs/task/" + encodeURIComponent(t.code));
      break;
    }
    /* 系統設備 */
    case "equip-add": {
      const name = $("se-name").value.trim();
      if(!name){ alert("名稱必填"); return; }
      const type = $("se-type").value;
      const opts = type === "status" ? parseOpts($("se-opts").value) : null;
      if(type === "status" && !opts.length){ alert("狀態選項至少一個"); return; }
      b.disabled = true;
      const ok = await defsWrite("POST", "/api/defs/equip", {
        name: name, type: type, shift: $("se-shift").value, time: $("se-time").value || "", statusOptions: opts, enabled: true
      });
      if(ok) alert("✅ 已新增「" + name + "」"); else b.disabled = false;
      break;
    }
    case "equip-edit": { const d = (defs.equipDefs || []).find(x => x.id === b.dataset.id); if(d) openEquipEditModal(d); break; }
    case "equip-toggle": {
      const d = (defs.equipDefs || []).find(x => x.id === b.dataset.id); if(!d) return;
      if(d.enabled && !confirm("停用「" + d.name + "」？")) return;
      b.disabled = true;
      await defsWrite("POST", "/api/defs/equip/" + encodeURIComponent(d.id) + "/toggle");
      break;
    }
    /* 三班檢查 */
    case "check-add": {
      const name = $("sc-name").value.trim();
      if(!name){ alert("名稱必填"); return; }
      b.disabled = true;
      const ok = await defsWrite("POST", "/api/defs/check", {
        name: name, type: $("sc-type").value, shift: $("sc-shift").value, time: $("sc-time").value || "00:00",
        holidaySkip: $("sc-holiday").value === "true", enabled: true
      });
      if(ok) alert("✅ 已新增「" + name + "」"); else b.disabled = false;
      break;
    }
    case "check-edit": { const d = (defs.checkDefs || []).find(x => x.id === b.dataset.id); if(d) openCheckEditModal(d); break; }
    case "check-toggle": {
      const d = (defs.checkDefs || []).find(x => x.id === b.dataset.id); if(!d) return;
      if(d.enabled && !confirm("停用「" + d.name + "」？")) return;
      b.disabled = true;
      await defsWrite("POST", "/api/defs/check/" + encodeURIComponent(d.id) + "/toggle");
      break;
    }
    case "apply": await applyToday(b.dataset.kind); break;

    /* 歷史日誌 */
    case "hist-add-log": await addHistoryLog(b); break;
    case "hist-select-all": historyRows.forEach(r => historySelected.add(r.date)); renderHistoryList(); break;
    case "hist-deselect-all": historySelected.clear(); renderHistoryList(); break;
    case "hist-export-json": {
      const ds = selectedDates(); if(!ds) return;
      downloadUrl(CTX + "/api/export?dates=" + encodeURIComponent(ds.join(",")));
      break;
    }
    case "hist-print": {
      const ds = selectedDates(); if(!ds) return;
      window.open(CTX + "/print?dates=" + encodeURIComponent(ds.join(",")), "_blank");
      break;
    }
    case "hist-pdf": {
      const ds = selectedDates(); if(!ds) return;
      downloadUrl(CTX + "/pdf?dates=" + encodeURIComponent(ds.join(",")));
      break;
    }
    /* 資料管理 */
    case "data-import": { const f = $("import-file"); if(f) f.click(); break; }
    case "data-goto-history": switchSubtab("history"); break;
  }
}
function onAdminChange(e){
  const el = e.target;
  if(el.id === "se-type") syncEquipOptsVisibility("se-type", "se-opts", "se-opts-label");
  else if(el.id === "hist-year" || el.id === "hist-month" || el.id === "hist-status") fetchHistory();
  else if(el.classList.contains("hist-checkbox")){
    if(el.checked) historySelected.add(el.dataset.date); else historySelected.delete(el.dataset.date);
    renderHistoryList();
  }
  else if(el.id === "import-file") importBackup(el);
}

/* ===== 歷史日誌 ===== */
function renderHistory(){
  const wrap = $("subtab-history");
  const thisYear = parseInt((me.todayLogical || "").slice(0,4), 10) || new Date().getFullYear();
  const years = []; for(let y = thisYear; y >= thisYear - 5; y--) years.push(String(y));
  const prevYear = $("hist-year") ? $("hist-year").value : "";
  const prevMonth = $("hist-month") ? $("hist-month").value : "";
  const prevStatus = $("hist-status") ? $("hist-status").value : "";
  let html = "";
  if(isAdmin()){
    html += '<div class="section"><div class="section-header static"><h2>➕ 手動新增日誌</h2></div><div class="section-body">' +
      '<div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">' +
        '<label style="font-size:13px;color:#475569">📅 日期：</label>' +
        '<input type="date" id="new-log-date" class="adm-input" style="width:auto" value="' + esc(me.todayLogical) + '">' +
        '<button type="button" class="btn btn-primary" data-act="hist-add-log">➕ 新增日誌</button>' +
      '</div>' +
      '<div class="note">💡 用於補登歷史日誌（如颱風假、員工請假補登等）<br>⚠ 已存在的日期會被擋下，不會覆蓋舊資料</div>' +
      '</div></div>';
  }
  html += '<div class="section"><div class="section-header static"><h2>📁 歷史日誌</h2></div><div class="section-body">' +
    '<div class="hist-filter">' +
      '<label>📅 年：</label><select id="hist-year"><option value="">全部</option>' + years.map(y => '<option value="' + y + '"' + (prevYear === y ? " selected" : "") + '>' + y + '</option>').join("") + '</select>' +
      '<label>月：</label><select id="hist-month"><option value="">全部</option>' + Array.from({length:12}, (_,i) => { const m = pad2(i+1); return '<option value="' + m + '"' + (prevMonth === m ? " selected" : "") + '>' + m + '</option>'; }).join("") + '</select>' +
      '<label>狀態：</label><select id="hist-status"><option value="">全部</option>' +
        [["draft","填寫中"],["submitted","待副科"],["reviewed","待科長"],["approved","已核准"]].map(x => '<option value="' + x[0] + '"' + (prevStatus === x[0] ? " selected" : "") + '>' + x[1] + '</option>').join("") + '</select>' +
      '<button type="button" class="btn btn-ghost btn-mini" data-act="hist-select-all">☑ 全選</button>' +
      '<button type="button" class="btn btn-ghost btn-mini" data-act="hist-deselect-all">☐ 全不選</button>' +
    '</div>' +
    '<div id="hist-list-wrap"><div class="hist-empty">⏳ 載入中...</div></div>' +
    '<div class="hist-info-bar"><span>已勾選 <span class="count" id="hist-selected-count">0</span> 份日誌</span>' +
      '<div><button type="button" class="btn btn-info" data-act="hist-export-json">⬇ 合併匯出 JSON</button>' +
      '<button type="button" class="btn btn-primary" data-act="hist-print">📄 合併列印</button>' +
      '<button type="button" class="btn btn-purple" data-act="hist-pdf">📑 合併 PDF</button></div>' +
    '</div></div></div>';
  wrap.innerHTML = html;
  fetchHistory();
}
async function fetchHistory(){
  const q = [];
  const y = $("hist-year") ? $("hist-year").value : "", m = $("hist-month") ? $("hist-month").value : "", s = $("hist-status") ? $("hist-status").value : "";
  if(y) q.push("year=" + encodeURIComponent(y));
  if(m) q.push("month=" + encodeURIComponent(m));
  if(s) q.push("status=" + encodeURIComponent(s));
  try{
    const rows = await api("GET", "/api/history" + (q.length ? "?" + q.join("&") : ""));
    historyRows = Array.isArray(rows) ? rows : [];
  }catch(e){
    historyRows = [];
  }
  renderHistoryList();
}
function renderHistoryList(){
  const wrap = $("hist-list-wrap");
  if(!wrap) return;
  const cnt = $("hist-selected-count");
  if(!historyRows.length){
    wrap.innerHTML = '<div class="hist-empty">📭 沒有符合條件的日誌</div>';
    if(cnt) cnt.textContent = String(historySelected.size);
    return;
  }
  wrap.innerHTML = '<table class="hist-table"><thead><tr><th style="width:40px"></th><th>日期</th><th>星期</th><th>狀態</th><th>批次完成</th><th>異常</th><th>JOB ERR</th><th>事件</th></tr></thead><tbody>' +
    historyRows.map(r => {
      const sel = historySelected.has(r.date);
      return '<tr class="' + (sel ? "selected" : "") + '">' +
        '<td><input type="checkbox" class="hist-checkbox"' + (sel ? " checked" : "") + ' data-date="' + esc(r.date) + '"></td>' +
        '<td class="date-link" data-date="' + esc(r.date) + '" title="切換到此日誌">' + esc(r.date) + '</td>' +
        '<td>星期' + esc(r.weekday) + '</td>' +
        '<td><span class="status-tag" style="padding:2px 8px;font-size:11px;background:' + (statusColors[r.status] || "#64748b") + '">' + esc(r.statusLabel || statusLabels[r.status] || r.status) + '</span></td>' +
        '<td>' + esc(r.batchDone || 0) + '/' + esc(r.batchTotal || 0) + '</td>' +
        '<td>' + (r.abnormal > 0 ? '<span style="color:#f59e0b;font-weight:600">' + esc(r.abnormal) + '</span>' : "-") + '</td>' +
        '<td>' + (r.jobError > 0 ? '<span style="color:#ef4444;font-weight:600">' + esc(r.jobError) + '</span>' : "0") + '</td>' +
        '<td>' + esc(r.records || 0) + '</td></tr>';
    }).join("") + '</tbody></table>';
  if(cnt) cnt.textContent = String(historySelected.size);
}
function selectedDates(){
  if(!historySelected.size){ alert("請先勾選要匯出的日誌"); return null; }
  return Array.from(historySelected).sort();
}
async function addHistoryLog(btn){
  const el = $("new-log-date");
  const d = el ? el.value : "";
  if(!d){ alert("請選擇日期"); return; }
  if(!confirm("確定新增 " + d + " 的日誌？\n\n• 會依目前的項目設定建立空白日誌\n• 已存在的日期會被擋下")) return;
  btn.disabled = true;
  try{
    const r = await api("POST", "/api/log/create", {date: d});
    alert("✅ 已新增 " + d + " 的日誌\n\n你可以從歷史日誌點該日期切換過去填寫");
    if(isLog(r) && r.date === currentDate) setLog(r);
    refreshDates();
    fetchHistory();
  }catch(e){}
  btn.disabled = false;
}

/* ===== 資料管理 ===== */
function renderDataAdmin(){
  const admin = isAdmin();
  $("subtab-data").innerHTML =
    '<div class="section" style="border:2px dashed #cbd5e1">' +
      '<div class="section-header static" style="background:#fef2f2"><h2 style="color:#991b1b">🗑️ 資料管理（謹慎操作）</h2></div>' +
      '<div class="section-body">' +
        '<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">' +
          '<button type="button" class="btn btn-info" data-act="data-goto-history">⬇ 匯出全部</button>' +
          '<span style="font-size:12px;color:#64748b">→ 至「📁 歷史日誌」依篩選條件「全選」後按「⬇ 合併匯出 JSON」即可匯出備份</span>' +
        '</div>' +
        '<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:12px">' +
          (admin
            ? '<button type="button" class="btn btn-warn" data-act="data-import">⬆ 匯入備份</button>' +
              '<input type="file" id="import-file" accept=".json,application/json" style="display:none">' +
              '<span style="font-size:12px;color:#64748b">匯入匯出格式的 JSON；只新增資料庫中不存在的日期，不會覆蓋既有日誌。</span>'
            : '<span style="font-size:12px;color:#94a3b8">🔒 匯入備份僅系統管理者可用。</span>') +
        '</div>' +
        '<div class="note" style="margin-top:14px;padding:10px;background:#f8fafc;border-radius:6px">' +
          '💾 <b>儲存資訊：</b>所有日誌儲存於伺服器資料庫，瀏覽器端不保存任何日誌資料。<br>' +
          '📌 建議定期由「歷史日誌」匯出 JSON 備份。' +
        '</div>' +
      '</div></div>';
}
function importBackup(input){
  const file = input.files && input.files[0];
  if(!file) return;
  const reader = new FileReader();
  reader.onload = async ev => {
    input.value = "";
    let data;
    try{ data = JSON.parse(ev.target.result); }
    catch(err){ alert("⚠ 檔案格式錯誤：不是有效的 JSON"); return; }
    if(!data || typeof data !== "object" || !data.reports || typeof data.reports !== "object"){ alert("⚠ 檔案格式錯誤：缺少 reports"); return; }
    const keys = Object.keys(data.reports);
    if(!confirm("匯入備份？\n\n備份時間：" + (data.exportedAt || "未知") + "\n版本：" + (data.version || "未知") + "\n共 " + keys.length + " 份日誌\n\n（只新增不存在的日期，不會覆蓋既有資料）")) return;
    try{
      const r = await api("POST", "/api/import", data);
      const created = (r && r.created) || [], skipped = (r && r.skipped) || [];
      alert("✅ 匯入完成\n\n新增 " + created.length + " 份：" + (created.join(", ") || "—") + "\n略過 " + skipped.length + " 份（已存在）：" + (skipped.join(", ") || "—"));
      refreshDates();
      if(activeSubtab === "history") fetchHistory();
    }catch(err){}
  };
  reader.readAsText(file, "UTF-8");
}

/* ===== 啟動 ===== */
if(document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
else init();

})();
