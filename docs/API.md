# 機房操作日誌系統 — HTTP / JSON API 契約

所有 JSON 皆為 UTF-8。日期格式 `YYYY-MM-DD`（邏輯日）；時刻格式 `HH:mm`；
時間戳記格式 ISO-8601 `YYYY-MM-DDTHH:mm:ss`（伺服器本地時間）。

## 通用規則

| 項目 | 規則 |
|---|---|
| 認證 | Session cookie（`JSESSIONID`）。未登入呼叫 `/api/*` → `401 {"error":"unauthenticated"}`；頁面則 302 至 `/login`。 |
| CSRF | 所有 `POST / PUT / DELETE` 必須帶 header `X-Requested-With: XMLHttpRequest`，否則 `403`。 |
| 錯誤 | `400 {"error":"訊息","details":["…","…"]}`（驗證失敗，`details` 可省略）、`403 {"error":"…"}`（無權限）、`404`、`409`（狀態衝突）、`500`。 |
| 成功 | 除特別註明外，所有會改變日誌的 API 都回傳**更新後的完整日誌**（同 `GET /api/log/{date}` 格式），前端直接整份重繪。 |
| 簽章物件 | `{"user":"op001","time":"2026-09-21T10:12:33","integratorEdit":false}`；未簽章為 `null`。 |

## 1. 登入 / 身分

| Method | Path | 說明 |
|---|---|---|
| GET | `/login` | 登入頁（JSP）。Query `?error=…` 顯示錯誤。 |
| POST | `/login` | 表單欄位 `username`, `password`, `role`(選填: OPERATOR/DEPUTY/CHIEF), `shift`(選填: day/evening/night)。成功 302 → `/`。 |
| POST | `/logout` | 登出，302 → `/login`。 |
| GET | `/api/me` | 目前身分（見下）。 |
| POST | `/api/me/shift` | body `{"shift":"day"}`，經辦切換目前班別。回 `/api/me` 內容。 |

`GET /api/me` 回應：

```json
{
  "user": "op001",
  "displayName": "王小明",
  "role": "OPERATOR",              // 目前生效角色：OPERATOR | DEPUTY | CHIEF
  "roles": ["OPERATOR","DEPUTY"],   // 此帳號所有可用角色（登入時可選）
  "isAdmin": false,                 // 可否使用「項目管理」CRUD、匯入、手動新增日誌
  "shift": "day",                   // 經辦目前班別；非經辦為 null
  "todayLogical": "2026-09-21",
  "serverTime": "2026-09-21T10:12:33",
  "logicalDayCutoffHour": 7,
  "appTitle": "機房操作日誌系統",
  "orgName": "合作金庫資訊部",
  "pendingIntegration": "2026-09-20" // 昨日 3 班皆送出但尚未整合送簽 → 提示切換；無則 null
}
```

角色語意：
* `OPERATOR` 經辦：填寫、簽章、覆核他人、送出本班、整合送簽、取回。
* `DEPUTY` 副科：審核（submitted → reviewed）、退件、解鎖、統計。
* `CHIEF` 科長：核准（reviewed → approved）、退件、解鎖、統計。
* `isAdmin`：項目管理 CRUD / 套用到今日 / 匯入 / 手動新增日誌。

## 2. 日期清單

`GET /api/dates` → 依角色過濾（經辦：draft + 今日；副科：submitted + 目前檢視；科長：reviewed），
永遠包含今日邏輯日。

```json
[
  {"date":"2026-09-21","weekday":"日","status":"draft","isToday":true,
   "shiftStatus":{"day":"draft","evening":"submitted","night":"draft"}}
]
```

## 3. 日誌（核心）

`GET /api/log/{date}` — 取得完整日誌（不存在且 `date` == 今日 → 自動建立；其他日期不存在 → 404）。
Query `?ifUpdatedAfter=<updatedAt>`：若伺服器 `updatedAt` 相同則回 `{"changed":false}`（輪詢用）。

```json
{
  "date":"2026-09-21","weekday":"日","solarDay":264,
  "dayType":"business","dayTypeLabel":"營業日","dayTypeOverride":false,"dayTypeReason":"","dayTypeChangedBy":"",
  "bootUser":"","bootTime":"","bootOpSign":null,"bootReviewerSign":null,
  "shiftStatus":{"day":"draft","evening":"draft","night":"draft"},
  "shiftSubmits":{"day":null,"evening":null,"night":null},
  "status":"draft","statusLabel":"填寫中","unlockReason":"","versions":1,
  "approval":{"operator":null,"deputy":null,"chief":null},
  "reviewComments":[{"by":"dep01","role":"副科 退件","time":"2026-09-21T09:00:00","text":"…"}],
  "jobError":{"day":0,"evening":0,"night":0},

  "equip":[
    {"defId":"dms1","defName":"DMS 第1次","type":"dms","shift":"day","time":"",
     "status":"","statusOptions":["正常","異常"],"count":"","notify":"","enabled":true,
     "opSign":null,"reviewerSign":null,"filled":false,
     "perm":{"editable":true,"signable":false,"reviewable":false,"canUnsign":false,"canUnreview":false,"canToggle":false}}
  ],
  "checks":{
    "day":[
      {"defId":"day_portal","name":"PORTAL檢查","type":"portal","shift":"day","time":"",
       "status":"","entryLog":"","holidaySkip":false,
       "values":{"網銀":"","WWW":"","金控官網":"","EATM":"","COEIP":""},
       "opSign":null,"reviewerSign":null,"filled":false,
       "perm":{"editable":true,"signable":false,"reviewable":false,"canUnsign":false,"canUnreview":false}}
    ],
    "evening":[],"night":[]
  },
  "tasks":[
    {"code":"A1","name":"批次前置作業 (JST0000D)","schedule":"business_day","scheduleLabel":"營業日",
     "plannedStart":"17:00","shift":"evening","hasEnd":false,"qtyLabel":null,
     "shouldExecute":true,"assignedShift":"evening","forced":false,"forceReason":"","forcedBy":"",
     "handoverFrom":null,"handoverEndShift":null,
     "done":false,"abnormal":false,"startTime":"","endTime":"","qtyValue":"","remark":"",
     "opSign":null,"reviewerSign":null,"delayed":false,
     "perm":{"editable":false,"reviewable":false,"canHandover":false,"canForce":false}}
  ],
  "records":[
    {"id":12,"seq":1,"time":"03:10","taskCode":"A6","description":"…","notifySP":"","notifyAP":"",
     "recoverTime":"","ticket":"","op":"op001","source":"batch","shift":"night"}
  ],

  "perm":{
    "mode":"fill",                 // fill | integrate | review | readonly
    "isCurrentLogicalDay":true,
    "isIntegrator":false,
    "canChangeDayType":true,
    "canEditBoot":false,"canSignBoot":false,"canReviewBoot":false,
    "canSubmitShift":true,"shiftSubmitted":false,
    "canSubmitAll":false,"canRecall":false,
    "canApprove":false,"canReject":false,"canUnlock":false,
    "canEditJobError":{"day":true,"evening":false,"night":true},
    "canManageRecords":true
  },
  "progress":{
    "docInfo":{"done":0,"total":1},"sysEquip":{"done":0,"total":6},"shiftChecks":{"done":0,"total":21},
    "batch":{"done":0,"total":28},"joberror":{"done":3,"total":3},"records":{"done":0,"total":0}
  },
  "summary":{"batchDone":0,"batchTotal":28,"delayed":0,"abnormal":0,"events":0},
  "reminders":["🟡 A1 待完成","📋 本班檢查未填：PORTAL檢查、電話語音"],
  "updatedAt":"2026-09-21T10:12:33.123"
}
```

`checks[*].values` 的 key 依類型：
* `portal`：`網銀, WWW, 金控官網, EATM, COEIP`（值 `正常|異常|""`）
* `sms`：`SGLGMVS, SGLGIMS, SGMQLOG`（0–100）
* `rmf`：`PRDA.CSA, PRDA.ECSA, PRDA.SQA, PRDA.ESQA, PRDB.CSA, PRDB.ECSA, PRDB.SQA, PRDB.ESQA`
* `general` / `cabinet`：無 values；用 `status`（及 cabinet 的 `entryLog`）。`holidaySkip=true` 的項目 `status` 可為 `假日不執行`。

`equip[*]` 依 `type`：`status` 用 `status`（選項 `statusOptions`）、`dms` 用 `count`、`ims`/`text` 用 `notify`。
`ims` 需 `enabled=true`（特殊作業啟用）才算有效項目。

### 3.1 日誌資訊

| Method | Path | Body |
|---|---|---|
| POST | `/api/log/{date}/daytype` | `{"dayType":"holiday","reason":"颱風假"}` |
| POST | `/api/log/{date}/boot` | `{"bootUser":"ABC","bootTime":"06:50"}` |
| POST | `/api/log/{date}/boot/sign` | — |
| POST | `/api/log/{date}/boot/review` | — |

### 3.2 批次作業

| Method | Path | Body |
|---|---|---|
| POST | `/api/log/{date}/task/{code}/field` | `{"field":"startTime|endTime|qtyValue|remark","value":"…"}` |
| POST | `/api/log/{date}/task/{code}/done` | `{"done":true}`（勾完成即簽章；取消則清簽章與覆核） |
| POST | `/api/log/{date}/task/{code}/abnormal` | `{"abnormal":true}` |
| POST | `/api/log/{date}/task/{code}/review` | — |
| POST | `/api/log/{date}/task/{code}/handover` | — （交給下一班） |
| POST | `/api/log/{date}/task/{code}/force` | `{"reason":"…"}` |
| POST | `/api/log/{date}/task/{code}/quick` | — （「填現在」：無開始→填開始；否則填結束） |

### 3.3 系統設備

| Method | Path | Body |
|---|---|---|
| POST | `/api/log/{date}/equip/{defId}/field` | `{"field":"time|status|count|notify","value":"…"}` |
| POST | `/api/log/{date}/equip/{defId}/sign` \| `unsign` \| `review` \| `unreview` | — |
| POST | `/api/log/{date}/equip/{defId}/toggle` | `{"reason":"…"}`（IMS 啟用需原因；關閉不需） |

### 3.4 三班檢查

| Method | Path | Body |
|---|---|---|
| POST | `/api/log/{date}/check/{shift}/{defId}/field` | `{"field":"time|status|entryLog|value","key":"網銀","value":"正常"}`（`field=value` 時必帶 `key`） |
| POST | `/api/log/{date}/check/{shift}/{defId}/sign` \| `unsign` \| `review` \| `unreview` | — |

### 3.5 JOB ERROR / 重要記錄事項

| Method | Path | Body |
|---|---|---|
| POST | `/api/log/{date}/joberror` | `{"shift":"night","value":2}` |
| POST | `/api/log/{date}/record` | `{"time":"03:10","taskCode":"A6","description":"…","notifySP":"","notifyAP":"","recoverTime":"","ticket":"","source":"manual|batch|joberror|check","shift":"night"}` |
| PUT | `/api/log/{date}/record/{id}` | 同上（可只帶要改的欄位） |
| DELETE | `/api/log/{date}/record/{id}` | — |

### 3.6 簽核流程

| Method | Path | Body | 狀態變化 |
|---|---|---|---|
| POST | `/api/log/{date}/submit-shift` | `{"shift":"day"}` | shiftStatus[shift] → submitted（驗證失敗 400 + details） |
| POST | `/api/log/{date}/submit-all` | — | draft → submitted（3 班皆送出才可） |
| POST | `/api/log/{date}/recall` | — | submitted → draft（經辦） |
| POST | `/api/log/{date}/approve` | `{"comment":"…"}` | 副科：submitted → reviewed；科長：reviewed → approved |
| POST | `/api/log/{date}/reject` | `{"reason":"…"}` | → draft，清除 approval |
| POST | `/api/log/{date}/unlock` | `{"reason":"…"}` | approved → draft，versions+1，3 班狀態重設 |
| POST | `/api/log/create` | `{"date":"2026-09-10"}` | 手動補登（admin）；已存在 409 |

## 4. 項目管理（定義）— 讀取所有人可；寫入需 `isAdmin`

`GET /api/defs` →

```json
{
  "taskDefs":[{"code":"A1","name":"…","schedule":"business_day","plannedStart":"17:00","shift":"evening","hasEnd":false,"qtyLabel":null,"enabled":true,"seq":1}],
  "equipDefs":[{"id":"dms1","name":"DMS 第1次","type":"dms","shift":"day","time":"","statusOptions":["正常","異常"],"enabled":true,"seq":2}],
  "checkDefs":[{"id":"day_portal","name":"PORTAL檢查","type":"portal","shift":"day","time":"","holidaySkip":false,"enabled":true,"seq":1}],
  "labels":{
    "schedule":{"daily":"每日","business_day":"營業日","monthly_first":"每月1日","monthly_first_business":"每月第1營業日","weekly_sunday":"每週日"},
    "equipType":{"status":"狀態（正常/異常）","dms":"DMS（台數）","ims":"IMS（特殊作業）","text":"文字通知"},
    "checkType":{"general":"一般（正常/異常）","cabinet":"機櫃（機櫃+進出登記簿）","portal":"PORTAL（5 子項）","sms":"SMS USAGE（3 個 %）","rmf":"TSO RMF（PRDA + PRDB）"}
  }
}
```

| Method | Path | Body |
|---|---|---|
| POST | `/api/defs/task` | taskDef（`code`,`name` 必填） |
| PUT | `/api/defs/task/{code}` | taskDef（可含 `newCode`） |
| DELETE | `/api/defs/task/{code}` | — |
| POST | `/api/defs/equip` | equipDef（`id` 省略則自動產生） |
| PUT | `/api/defs/equip/{id}` | equipDef |
| POST | `/api/defs/equip/{id}/toggle` | — 啟用/停用 |
| POST | `/api/defs/check` | checkDef |
| PUT | `/api/defs/check/{id}` | checkDef |
| POST | `/api/defs/check/{id}/toggle` | — |
| POST | `/api/defs/apply-today` | `{"kind":"task|equip|check"}` → 套用到今日（已填寫的不動），回今日完整日誌 |

所有寫入回傳更新後的 `GET /api/defs` 內容（`apply-today` 除外）。

## 5. 歷史 / 統計 / 匯出

| Method | Path | 說明 |
|---|---|---|
| GET | `/api/history?year=2026&month=09&status=approved` | `[{"date","weekday","status","statusLabel","batchDone","batchTotal","abnormal","jobError","records"}]`，皆選填 |
| GET | `/api/stats` | `{"days":12,"abnormal":3,"forced":1,"jobError":5,"records":7}` |
| GET | `/api/export?dates=2026-09-01,2026-09-02` | 下載 JSON `{"exportedAt","version":"3.0","reports":{date: 完整日誌}}` |
| POST | `/api/import` | body 同匯出格式；只新增不存在的日期 → `{"created":[…],"skipped":[…]}`（admin） |
| GET | `/print/{date}` | 單日列印用 HTML（含「列印 / 存成 PDF」按鈕，`window.print()`） |
| GET | `/print?dates=a,b,c` | 合併列印 HTML（含目錄、分頁） |
| GET | `/pdf/{date}` | 單日 PDF（`application/pdf`，`Content-Disposition: attachment`） |
| GET | `/pdf?dates=a,b,c` | 合併 PDF |

## 6. 權限規則摘要（伺服器端判定，前端只依 `perm` 顯示）

* **可編輯（isEditableNow）**：經辦 + 日誌 `status=draft` + （今日邏輯日 或 3 班皆已送出「整合階段」）。
* **整合階段（isIntegrator）**：經辦 + draft + 3 班皆已送出 → 可修改所有項目（改到別人簽章的項目時，簽章改為整合者並清除覆核）。
* **批次**：僅本班（或跨班 `cross` 的起訖班）可填；本班已送出即鎖；勾「完成」＝簽章；覆核者不可是填寫者。
* **系統設備 / 三班檢查**：本班項目、本班未送出、尚未簽章（或自己的簽章可撤回）；覆核不可自審。
* **JOB ERROR**：本班可填；早班可彙整大夜。
* **送出本班**：本班批次全完成、本班設備與檢查全簽章、JOB ERROR 已填、本班 JOB ERROR 事件皆填復原時間。
* **整合送簽**：3 班皆送出；每班 JOB ERROR > 0 須有對應事件；所有事件皆填復原時間。
* **副科**：只能審 `submitted`；**科長**：只能核 `reviewed`；兩者皆可退件、皆可解鎖 `approved`。
