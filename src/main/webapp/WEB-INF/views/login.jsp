<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html lang="zh-TW">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${appTitle} — 登入</title>
<style>
/* 登入頁自帶樣式（不依賴 /static，避免受驗證過濾器影響） */
*{box-sizing:border-box;margin:0;padding:0;}
body{font-family:"Microsoft JhengHei","Segoe UI",sans-serif;background:#f1f5f9;color:#0f172a;line-height:1.5;}
#loginBox{max-width:400px;margin:80px auto;background:#fff;padding:36px;border-radius:14px;box-shadow:0 20px 50px rgba(0,0,0,0.15);}
#loginBox h2{color:#1e40af;text-align:center;margin-bottom:6px;}
#loginBox .org{text-align:center;color:#64748b;font-size:13px;margin-bottom:18px;}
#loginBox label{display:block;margin:14px 0 6px;font-size:13px;color:#475569;font-weight:500;}
#loginBox label.dim{color:#94a3b8;}
#loginBox input,#loginBox select{width:100%;padding:10px 12px;border-radius:8px;border:1px solid #cbd5e1;font-size:14px;font-family:inherit;background:#fff;}
#loginBox input:focus,#loginBox select:focus{outline:none;border-color:#1e40af;box-shadow:0 0 0 3px rgba(30,64,175,0.1);}
#loginBox input:disabled,#loginBox select:disabled{background:#f1f5f9;color:#94a3b8;}
#loginBox button{width:100%;margin-top:22px;padding:12px;background:#1e40af;color:#fff;font-weight:600;border:none;border-radius:8px;cursor:pointer;font-size:14px;font-family:inherit;}
#loginBox button:hover{background:#1e3a8a;}
.login-error{background:#fee2e2;color:#991b1b;border:1px solid #fca5a5;padding:10px 12px;border-radius:8px;font-size:13px;margin-bottom:6px;}
.dev-hint{margin-top:16px;font-size:11px;color:#94a3b8;text-align:center;background:#f8fafc;padding:8px;border-radius:6px;}
</style>
</head>
<body>

<div id="loginBox">
  <h2>🛡️ ${appTitle}</h2>
  <div class="org">${orgName}</div>

  <div class="login-error" style="${empty error ? 'display:none' : ''}">⚠ ${error}</div>

  <form method="post" action="${pageContext.request.contextPath}/login" autocomplete="off" id="loginForm">
    <label for="username">使用者帳號</label>
    <input id="username" name="username" type="text" required autofocus autocomplete="username">

    <div style="${bypassMode ? 'display:none' : ''}">
      <label for="password">密碼</label>
      <input id="password" name="password" type="password" ${bypassMode ? '' : 'required'} autocomplete="current-password">
    </div>

    <label for="role">角色</label>
    <select id="role" name="role">
      <option value="">自動（依帳號權限）</option>
      <option value="OPERATOR">經辦（填寫 / 覆核）</option>
      <option value="DEPUTY">副科（審核）</option>
      <option value="CHIEF">科長（核准）</option>
    </select>

    <label id="shiftLabel" for="shift">班別（僅經辦）</label>
    <select id="shift" name="shift">
      <option value="day">早班 08:00-16:00</option>
      <option value="evening">小夜班 16:00-24:00</option>
      <option value="night">大夜班 00:00-08:00</option>
    </select>

    <button type="submit" id="loginBtn">登入系統</button>
  </form>

  <div class="dev-hint" style="${devMode ? '' : 'display:none'}">開發模式：帳號密碼相同 (op001/op001, dep001/dep001, chief001/chief001)</div>
  <div class="dev-hint" style="${bypassMode ? '' : 'display:none'}">🔓 試用模式（auth.mode=bypass）：不驗證密碼，任意帳號皆可登入；預設帳號 op001 / op002（經辦）、dep001（副科）、chief001（科長）、admin（全部角色）</div>
</div>

<script>
(function(){
  "use strict";
  var roleSel = document.getElementById("role");
  var shiftSel = document.getElementById("shift");
  var shiftLab = document.getElementById("shiftLabel");

  // 依目前時鐘預設班別：08–15 早班、16–23 小夜、其餘大夜
  var h = new Date().getHours();
  shiftSel.value = (h >= 8 && h <= 15) ? "day" : (h >= 16 && h <= 23) ? "evening" : "night";

  function updateShiftAvail(){
    var enabled = roleSel.value === "" || roleSel.value === "OPERATOR";
    shiftSel.disabled = !enabled;
    shiftLab.className = enabled ? "" : "dim";
  }
  roleSel.addEventListener("change", updateShiftAvail);
  updateShiftAvail();
})();
</script>
</body>
</html>
