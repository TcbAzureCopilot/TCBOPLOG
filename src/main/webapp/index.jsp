<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html lang="zh-TW">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<title>機房操作日誌系統</title>
<link rel="stylesheet" href="<%= request.getContextPath() %>/static/app.css?v=1">
</head>
<body>

<div id="mainArea">
  <header>
    <h1 id="appTitle">🖥️ 機房操作日誌系統</h1>
    <div class="header-right">
      <span id="syncStatus">⏳ 連線中…</span>
      <span>👤 <span id="who"></span></span>
      <span id="modeBadge" class="mode-badge mode-fill"></span>
      <form method="post" action="<%= request.getContextPath() %>/logout" id="logoutForm">
        <button type="submit" id="logoutBtn">登出</button>
      </form>
    </div>
  </header>
  <div class="tabs">
    <span class="tab active" data-tab="log">📋 日誌</span>
    <span class="tab hidden" data-tab="stats" id="tab-stats-btn">📊 統計</span>
    <span class="tab" data-tab="admin">⚙️ 項目管理</span>
  </div>
  <div id="marquee">⏳ 載入中...</div>
  <div class="container">

    <!-- ===== 📋 日誌 ===== -->
    <div id="tab-log">
      <div id="dateSelector"></div>
      <div id="crossDayBanner"></div>

      <div class="section" data-collapse-key="docInfo">
        <div class="section-header" data-toggle="docInfo">
          <h2>📅 日誌資訊</h2>
          <div class="right"><span id="docInfoTag"></span><span class="toggle-btn" id="toggle-docInfo">−</span></div>
        </div>
        <div class="section-body"><div id="docInfo" class="doc-info"></div></div>
      </div>

      <div id="shiftBar" class="shift-bar"></div>
      <div id="summary" class="summary-bar"></div>

      <div class="section" data-collapse-key="sysEquip">
        <div class="section-header" data-toggle="sysEquip">
          <h2>🖥️ 系統設備檢查</h2>
          <div class="right"><span id="sysEquipTag"></span><span class="toggle-btn" id="toggle-sysEquip">−</span></div>
        </div>
        <div class="section-body" id="sysEquip"></div>
      </div>

      <div class="section" data-collapse-key="shiftChecks">
        <div class="section-header" data-toggle="shiftChecks">
          <h2>📋 三班檢查表</h2>
          <div class="right"><span id="shiftChecksTag"></span><span class="toggle-btn" id="toggle-shiftChecks">−</span></div>
        </div>
        <div class="section-body"><div class="check-grid" id="shiftChecksBody"></div></div>
      </div>

      <div class="section" data-collapse-key="batch">
        <div class="section-header" data-toggle="batch">
          <h2>⚙️ 批次作業（A1~A32）</h2>
          <div class="right">
            <div class="filter-bar" id="filterBar">
              <label><input type="checkbox" id="filter-mine"> 只看本班</label>
              <label><input type="checkbox" id="filter-pending"> 只看未完成</label>
            </div>
            <span id="batchTag"></span><span class="toggle-btn" id="toggle-batch">−</span>
          </div>
        </div>
        <div class="section-body" style="padding:0">
          <table class="excel">
            <thead><tr>
              <th style="width:45px">序</th><th style="width:55px">班</th>
              <th style="width:230px">作業項目</th><th style="width:80px">條件</th>
              <th style="width:55px">預計</th><th style="width:115px">開始</th>
              <th style="width:115px">結束</th><th style="width:100px">數量</th>
              <th style="width:45px">完成</th><th style="width:45px">異常</th>
              <th>備註</th><th style="width:75px">填寫</th>
              <th style="width:75px">覆核</th><th style="width:90px">操作</th>
            </tr></thead>
            <tbody id="taskRows"></tbody>
          </table>
        </div>
      </div>

      <div class="section" data-collapse-key="joberror">
        <div class="section-header" data-toggle="joberror">
          <h2>🔢 JOB ERROR 統計</h2>
          <div class="right"><span id="jobErrorTag"></span><span class="toggle-btn" id="toggle-joberror">−</span></div>
        </div>
        <div class="section-body">
          <div class="joberror-grid" id="jobError"></div>
          <div class="note">💡 大夜的 JOB ERROR 可隔天早班彙整。任何班別 ERROR &gt; 0 必須填重要記錄事項。</div>
        </div>
      </div>

      <div class="section" data-collapse-key="records">
        <div class="section-header" data-toggle="records">
          <h2>⚠️ 重要記錄事項</h2>
          <div class="right"><span id="recordsTag"></span><span class="toggle-btn" id="toggle-records">−</span></div>
        </div>
        <div class="section-body">
          <div class="records-list" id="recordsList"></div>
          <div class="records-add" id="recordsAdd"><button type="button" class="btn btn-primary btn-mini" id="add-record">➕ 新增事件</button></div>
        </div>
      </div>

      <div id="actionArea" class="actions"></div>
    </div>

    <!-- ===== 📊 統計 ===== -->
    <div id="tab-stats" style="display:none;"><div id="statsContent"></div></div>

    <!-- ===== ⚙️ 項目管理 ===== -->
    <div id="tab-admin" style="display:none;">
      <div class="sub-tabs">
        <div class="sub-tab active" data-subtab="batch">⚙️ 批次作業</div>
        <div class="sub-tab" data-subtab="sysequip">🖥️ 系統設備</div>
        <div class="sub-tab" data-subtab="checks">📋 三班檢查</div>
        <div class="sub-tab" data-subtab="history">📁 歷史日誌</div>
        <div class="sub-tab" data-subtab="data">🗑️ 資料管理</div>
      </div>
      <div id="subtab-batch"></div>
      <div id="subtab-sysequip" style="display:none"></div>
      <div id="subtab-checks" style="display:none"></div>
      <div id="subtab-history" style="display:none"></div>
      <div id="subtab-data" style="display:none"></div>
    </div>

  </div>
</div>
<div id="modalRoot"></div>

<script>window.CTX='<%= request.getContextPath() %>';</script>
<script src="<%= request.getContextPath() %>/static/app.js?v=1"></script>
</body>
</html>
