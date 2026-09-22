# 機房操作日誌系統（Java / WebSphere / DB2 版）

原本的單一 `index.html` + `server.ps1`（PowerShell + JSON 檔）應用，重寫為標準 Java EE 7 WAR：

| 項目 | 原版 | 本版 |
|---|---|---|
| 執行環境 | PowerShell HttpListener | **WebSphere Application Server**（傳統 8.5.5 / 9.0 或 Liberty） |
| 資料 | 單一 JSON 檔 `reports.db` | **DB2** 正規化資料表（每個欄位、簽章、事件皆為獨立欄位 / 資料列） |
| 身分 | Windows 整合驗證 + 自選角色 | **AD LDAPS** 帳密驗證；經辦＝AD 群組、副科／科長＝外部設定檔帳號清單 |
| 權限 / 驗證 | 全在瀏覽器 JS | **全部在伺服器端**（`Permissions`, `LogService`），前端只依 `perm` 旗標顯示 |
| 儲存方式 | 整份 JSON 覆寫、5 秒輪詢 | 每個動作一筆交易（列鎖），輪詢只比對 `updatedAt` |
| 列印 / PDF | 瀏覽器列印 HTML | 伺服器產生列印 HTML **及** 真正的 PDF（OpenPDF，內嵌中文字型） |
| 稽核 | 無 | `AUDIT_LOG`：登入、每個修改、送簽、審核、列印、匯出 |

功能與畫面完整保留：邏輯日（07:00 切換）、日誌資訊 / 開機資訊、系統設備檢查、三班檢查表、批次作業 A1~A32（交班、強制、跨班）、JOB ERROR、重要記錄事項、本班送出 → 整合送簽 → 副科審核 → 科長核准 → 解鎖、項目管理（CRUD + 套用到今日）、歷史日誌、統計、匯出 / 匯入（含舊版 `reports.db` 格式轉入）。

---

## 1. 專案結構

```
machineroom-java/
├── pom.xml                         Maven (Java 8 target, WAR)
├── README.md
├── docs/API.md                     HTTP/JSON API 契約（前後端共同依據）
├── config/
│   ├── app.properties.sample       外部設定檔範本（正式環境複製後修改）
│   └── app.dev.properties          本機開發用（H2 + 假帳號）
├── db/
│   ├── 01_schema_db2.sql           DB2 建表
│   └── 02_seed_db2.sql             預設項目定義（A1~A32、系統設備、三班檢查）
└── src/main/
    ├── java/com/machineroom/
    │   ├── config/AppConfig        外部設定檔載入（自動重讀）
    │   ├── auth/                   LDAPS 驗證、角色對應、Session、IP 限制、CSRF
    │   ├── db/                     DataSource(JNDI)、交易、Schema 初始化
    │   ├── model/                  DailyLog, Task, EquipItem, CheckItem, ...
    │   ├── dao/                    DailyLogDao, DefDao, AuditDao (純 JDBC)
    │   ├── service/                Permissions, LogService, DefService, HistoryService, LegacyImporter
    │   ├── pdf/                    PrintHtml, PdfService, PdfFonts
    │   └── web/                    ApiServlet(路由), LogJson, PrintServlet, PdfServlet
    └── webapp/
        ├── index.jsp               主畫面外殼
        ├── static/app.js, app.css  前端 SPA（無框架、無 CDN）
        └── WEB-INF/web.xml, ibm-web-bnd.xml, views/login.jsp, error.jsp
```

## 2. 建置

需求：JDK 8+（建置時可用 11/17，目標位元組碼為 Java 8）、Maven 3.6+。

```bash
mvn clean package
```

產出 `target/machineroom-log.war`。單元 / 整合測試（H2 in-memory，DB2 相容模式）：

```bash
mvn test
```

### 本機試跑（不需 WAS / DB2）

```bash
mvn jetty:run
```

開啟 <http://localhost:8080/mrlog/>，使用 `config/app.dev.properties` 內的假帳號（`auth.mode=dev`）：
`op001/op001`、`op002/op002`、`op003/op003`（經辦）、`dep001/dep001`（副科）、`chief001/chief001`（科長）、`super/super`（三種角色可選）。
資料存於 `target/devdb/`（H2）。

## 3. DB2 建置

1. 建立資料庫（**必須 UTF-8**）：
   ```sql
   CREATE DATABASE MRLOG USING CODESET UTF-8 TERRITORY TW PAGESIZE 32K;
   ```
2. 以應用程式用的 schema 執行 `db/01_schema_db2.sql`，再執行 `db/02_seed_db2.sql`：
   ```bash
   db2 connect to MRLOG
   db2 set current schema MRLOG
   db2 -tvf db/01_schema_db2.sql
   db2 -tvf db/02_seed_db2.sql
   ```
   （`02_seed` 可省略：`db.seedDefaultsIfEmpty=true` 時應用程式啟動發現 `DEF_TASK` 為空會自動載入。）
3. 建立應用程式用 DB 帳號，授與該 schema 的 SELECT / INSERT / UPDATE / DELETE。

資料表：`DAILY_LOG`（表頭、狀態、簽核、JOB ERROR）、`LOG_SHIFT`（三班送出狀態）、`LOG_TASK`（批次）、`LOG_EQUIP`（系統設備）、`LOG_CHECK` + `LOG_CHECK_VALUE`（三班檢查與子項）、`LOG_RECORD`（重要記錄事項）、`LOG_COMMENT`（審核意見 / 退件）、`DEF_TASK` / `DEF_EQUIP` / `DEF_CHECK`（項目定義）、`AUDIT_LOG`。布林欄位為 `SMALLINT 0/1`（相容 DB2 10.5）。

## 4. WebSphere 部署

### 4.1 DataSource
1. **JDBC 提供者**：DB2 Universal JDBC Driver Provider（type 4），`db2jcc4.jar`。
2. **資料來源**：JNDI 名稱 **`jdbc/MachineRoomDS`**（與 `web.xml` / `ibm-web-bnd.xml` 一致；亦可在 `app.properties` 以 `db.jndiName` 改）。
   * 自訂內容 `currentSchema=MRLOG`（若 schema 與帳號不同）。
   * 元件管理的鑑別別名：DB 帳號密碼。
3. 測試連線。

### 4.2 外部設定檔
複製 `config/app.properties.sample` 到伺服器，例如 `/opt/ibm/mrlog/app.properties`（**UTF-8 儲存**），然後告訴應用程式位置（擇一）：

| 方式 | 設定 |
|---|---|
| JVM 自訂內容（建議） | 伺服器 → Java 與程序管理 → 程序定義 → JVM → 自訂內容：`machineroom.config=/opt/ibm/mrlog/app.properties` |
| 環境變數 | `MACHINEROOM_CONFIG=/opt/ibm/mrlog/app.properties` |
| 應用程式環境項目 | 安裝後於「Web 模組的環境項目」設定 `machineroom/config` |
| 預設路徑 | `${user.home}/machineroom/app.properties` |

檔案修改後 **60 秒內自動重新載入**（群組名稱、副科／科長帳號、IP 白名單等不需重啟）。找不到設定檔時系統「失效封閉」：無人能登入，並在 SystemOut.log 記錄 SEVERE。

### 4.3 AD / LDAPS
```properties
auth.mode=ldap
ldap.urls=ldaps://10.1.2.3:636,ldaps://ad2.bank.local:636   # 主機名或 IP，依序嘗試
ldap.upnSuffix=bank.local          # bind 用 user@bank.local
ldap.bindFormat=upn                # 或 netbios → BANK\user
ldap.netbiosDomain=BANK
ldap.baseDn=DC=bank,DC=local
ldap.serviceUser=                  # 選填：查群組用服務帳號；留空用使用者本身連線
ldap.servicePassword=
ldap.nestedGroups=true             # 巢狀群組（LDAP_MATCHING_RULE_IN_CHAIN）
ldap.truststore=                   # 留空 = JVM 預設；否則指定含 AD 憑證鏈的 JKS
ldap.truststorePassword=
```
**憑證**：LDAPS 需信任 AD 憑證。在 WAS 主控台「SSL 憑證與金鑰管理 → 金鑰儲存庫與憑證 → NodeDefaultTrustStore → 簽署者憑證 → 從埠擷取」匯入 AD 的憑證；或把憑證匯入自建 JKS 並設定 `ldap.truststore`。

### 4.4 角色（外部檔設定）
```properties
roles.operatorGroups=CN=MachineRoom-Operators,OU=Groups,DC=bank,DC=local   # 經辦：AD 群組（DN 或 CN），可多個
roles.deputyAccounts=dep001,dep002                                          # 副科：AD 帳號
roles.chiefAccounts=chief001                                                # 科長：AD 帳號
roles.adminAccounts=        # 管理者（項目管理 / 匯入 / 補登日誌）；留空 = 副科 + 科長
roles.adminGroups=
```
* 帳號同時具備多種角色時，登入頁可選擇本次登入的角色（例如科長也在經辦群組）。
* 不在任何角色 → 登入被拒（訊息：未被授權使用本系統）。

### 4.5 用戶端 IP 限制（選用）
```properties
security.allowedClientIps=10.1.0.0/16,10.2.5.7      # CIDR / 單一 IP；留空不限制
security.trustProxyHeader=true                       # 前面有 IHS / 反向代理時採用 X-Forwarded-For
```

### 4.6 PDF 中文字型
```properties
pdf.fontPaths=C:/Windows/Fonts/msjh.ttc,0;/usr/share/fonts/opentype/noto/NotoSansCJKtc-Regular.otf
```
以 `;` 分隔多個候選路徑，取第一個存在的檔案；TTC 請加 `,索引`。WAS 主機（AIX / Linux）通常沒有中文字型，請複製 `msjh.ttc`（微軟正黑體）或安裝 **TrueType** 版 Noto Sans TC（`NotoSansTC-Regular.ttf`）到伺服器。
**請用 .ttf / .ttc，避免 CFF 格式的 .otf**（例如 `NotoSansCJKtc-Regular.otf`）：OpenPDF 對 CFF 字型做子集嵌入時部分字（如「開」）會變形。找不到字型時 PDF 仍會產生但中文為空白，SystemOut.log 會有 SEVERE 提示。

### 4.7 安裝 WAR
1. 應用程式 → 新增應用程式 → `machineroom-log.war`，Context root 例如 `/mrlog`。
2. 資源參照對應 `jdbc/MachineRoomDS`（`ibm-web-bnd.xml` 已預設綁定）。
3. 類別載入：預設（PARENT_FIRST）即可；WAR 只帶 Gson 與 OpenPDF，與 WAS 內建程式庫無衝突。
4. Session 逾時預設 600 分鐘（`web.xml`）。正式環境走 HTTPS 時，將 `web.xml` 的 `<cookie-config>` 加上 `<secure>true</secure>`。
5. 啟動後檢查 SystemOut.log：`Loaded configuration from …`、`DataSource bound: …`、`PDF font: …`。

### Liberty
`server.xml` 需 `jsp-2.3`、`servlet-3.1`、`jndi-1.0` 功能，並定義 `<dataSource jndiName="jdbc/MachineRoomDS">` 指向 DB2 JCC driver；其餘相同。

## 5. 權限模型（伺服器端）

| 角色 | 能做的事 |
|---|---|
| **經辦 OPERATOR**（AD 群組） | 填寫 / 簽章本班項目；覆核他人簽章（不可自審）；本班送出；3 班皆送出後任一經辦可進入「整合檢查」修改全部並整合送簽；取回 |
| **副科 DEPUTY**（帳號清單） | 審核 `submitted` → `reviewed`；退件；解鎖已核准日誌；統計 |
| **科長 CHIEF**（帳號清單） | 核准 `reviewed` → `approved`；退件；解鎖；統計 |
| **管理者**（預設副科 + 科長） | 項目管理 CRUD、套用到今日、匯入備份、手動補登日誌 |

所有規則在 `service/Permissions.java`，前端不做任何權限判斷；每個 API 呼叫都在交易內重新檢查並寫入 `AUDIT_LOG`（帳號、角色、IP、動作、日期、內容）。

其他安全措施：LDAPS 帳密不落地；登入成功即更換 Session ID；所有修改型 API 必須帶 `X-Requested-With` 標頭（CSRF）；輸出全部經 HTML escape；`Cache-Control: no-store`、`X-Frame-Options`。

## 6. 舊資料移轉

舊版 `data/reports.db`（其實是 JSON）或舊版「匯出全部資料」的 `.json`，可直接在新系統 **項目管理 → 資料管理 → ⬆ 匯入備份** 上傳（需管理者）。系統自動辨識舊格式並轉換（DMS 前/後台數合併為台數、跨班作業指派為大夜→早班），已存在的日期不會被覆蓋。也可用 API：

```bash
curl -b cookie.txt -H "X-Requested-With: XMLHttpRequest" -H "Content-Type: application/json" \
     --data-binary @reports.db https://host/mrlog/api/import
```

## 7. API

完整契約見 [docs/API.md](docs/API.md)。列印 / PDF：

* `GET /mrlog/print/2026-09-21`、`GET /mrlog/print?dates=2026-09-01,2026-09-02`（瀏覽器列印 / 另存 PDF）
* `GET /mrlog/pdf/2026-09-21`、`GET /mrlog/pdf?dates=…`（伺服器產生 PDF 下載）

## 8. TCBOPLOG：Docker 試用版（Spring Boot + 內嵌 H2 + 免認證）

同一份程式碼的第二種包裝，給「先用看看」用：不需要 WebSphere、DB2、AD。

```
tcboplog/                     Spring Boot 2.7 模組（覆蓋 machineroom-log.war：JSP / 前端 / 程式庫全部沿用）
  pom.xml
  src/main/java/com/machineroom/boot/TcbOpLogApplication.java   內嵌 Tomcat 註冊 filter/servlet、H2 DataSource
  config/app.properties       image 內建設定（auth.mode=bypass、H2 放 /data）
Dockerfile                    multi-stage：maven 建置 → eclipse-temurin 17 JRE + 文泉驛正黑（PDF 中文）
docker-compose.yml
```

### 建置與啟動
```bash
cd machineroom-java
docker build -t tcboplog .
docker run -d --name tcboplog -p 8080:8080 -v tcboplog-data:/data tcboplog
# 或
docker compose up -d
```
開啟 <http://localhost:8080/>。資料庫檔案在 volume `tcboplog-data`（`/data/tcboplog.mv.db`），刪掉 volume 即重置。

### 搬到其他機器（Docker 或 Podman）
image 是標準 OCI 格式，任何有 Docker / Podman 的機器都能跑；**注意 CPU 架構**：x86_64 伺服器用 amd64 版，Apple Silicon / ARM 用 arm64 版。
```bash
# 建置（在有 buildx 的機器；Apple Silicon 也可交叉建 amd64）
docker buildx build --platform linux/amd64 --load -t tcboplog:amd64 .
docker save tcboplog:amd64 | gzip > tcboplog-amd64.tar.gz

# 目標機器 — Docker
docker load < tcboplog-amd64.tar.gz
docker run -d --name tcboplog -p 8080:8080 -v tcboplog-data:/data tcboplog:amd64

# 目標機器 — Podman（rootless 亦可；RHEL/SELinux 掛設定檔時加 :Z）
podman load < tcboplog-amd64.tar.gz
podman run -d --name tcboplog -p 8080:8080 -v tcboplog-data:/data localhost/tcboplog:amd64
```
`dist/` 目錄已有建好的 `tcboplog-amd64.tar.gz`、`tcboplog-arm64.tar.gz` 與 `SHA256SUMS`。

### 免認證（bypass）
image 內建設定 `auth.mode=bypass`：登入頁**不需要密碼**，輸入任何帳號都可進入。
* 內建帳號：`op001` / `op002` / `op003`（經辦）、`dep001`（副科）、`chief001`（科長）、`admin`（三種角色皆可選）。
* 未列在 `bypass.users` 的帳號會得到 `bypass.defaultRoles`（預設三種角色都有）。
* `bypass.autoLogin=op001` → 開頁面直接進入，不顯示登入表單；登出後到 `/login?select=1` 可換帳號。

### 改設定 / 改接 AD
把 [tcboplog/config/app.properties](tcboplog/config/app.properties) 複製出來修改後掛載進去（格式與 WebSphere 版相同）：
```bash
docker run -d -p 8080:8080 -v tcboplog-data:/data \
  -v /path/app.properties:/app/config/app.properties:ro tcboplog
```
把 `auth.mode` 改成 `ldap` 並填 `ldap.* / roles.*` 就是正式的 AD 驗證；`pdf.fontPaths` 可指向掛載的 `msjh.ttc`（`/app/fonts/msjh.ttc,0` 已在候選清單）。

### 不用 Docker 直接跑
```bash
mvn -DskipTests install                 # 先安裝核心 WAR 到本機 repo
mvn -DskipTests -f tcboplog/pom.xml package
MACHINEROOM_CONFIG=tcboplog/config/app.properties java -jar tcboplog/target/tcboplog.war
```
（本機跑請把 `db.url` 改成本機路徑、`pdf.fontPaths` 改成本機字型。）

**注意**：bypass 模式沒有任何身分驗證，只可用於隔離的試用環境；`AUDIT_LOG` 仍會記錄操作但帳號是自報的。

## 9. 與原版行為的差異（刻意）

* 邏輯日切換固定為 `app.logicalDayCutoffHour`（預設 07:00）；原版程式碼判斷用 01:00 但畫面文字寫 07:00，本版以畫面文字為準。
* 跨班作業（A9）：大夜可填開始時間、早班可填結束並負責完成；原版只有早班能填。
* 系統設備 / 三班檢查在整合階段也可覆核（原版只能當日）。
* 「清除全部資料」功能移除（DB 資料由 DBA 管理）；匯入只新增不覆蓋。
* JOB ERROR 預設 0 視為已填（與原版實際行為一致）。
