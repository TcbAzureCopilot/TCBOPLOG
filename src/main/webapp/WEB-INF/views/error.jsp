<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isErrorPage="true" %>
<!DOCTYPE html>
<html lang="zh-TW">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>發生錯誤</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;}
body{font-family:"Microsoft JhengHei","Segoe UI",sans-serif;background:#f1f5f9;color:#0f172a;line-height:1.5;}
.box{max-width:480px;margin:100px auto;background:#fff;padding:36px;border-radius:14px;box-shadow:0 20px 50px rgba(0,0,0,0.15);text-align:center;}
.box h2{color:#b91c1c;margin-bottom:12px;}
.box .code{font-size:48px;font-weight:700;color:#1e40af;margin-bottom:8px;}
.box p{color:#64748b;font-size:14px;margin-bottom:22px;}
.box a{display:inline-block;padding:10px 22px;background:#1e40af;color:#fff;text-decoration:none;border-radius:8px;font-size:14px;font-weight:600;}
.box a:hover{background:#1e3a8a;}
</style>
</head>
<body>
<div class="box">
  <div class="code">${pageContext.errorData.statusCode}</div>
  <h2>⚠ 發生錯誤</h2>
  <p>${pageContext.errorData.statusCode == 404 ? '找不到您要的頁面或資料。' :
       (pageContext.errorData.statusCode == 403 ? '您沒有權限執行此操作。' :
       (pageContext.errorData.statusCode == 500 ? '系統發生內部錯誤，請稍後再試或聯絡系統管理者。' : '請稍後再試或聯絡系統管理者。'))}</p>
  <a href="${pageContext.request.contextPath}/">↩ 回首頁</a>
</div>
</body>
</html>
