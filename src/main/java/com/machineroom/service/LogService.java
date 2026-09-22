package com.machineroom.service;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.machineroom.auth.UserPrincipal;
import com.machineroom.dao.AuditDao;
import com.machineroom.dao.DailyLogDao;
import com.machineroom.dao.DefDao;
import com.machineroom.db.Db;
import com.machineroom.db.Jdbc;
import com.machineroom.model.CheckItem;
import com.machineroom.model.CheckType;
import com.machineroom.model.DailyLog;
import com.machineroom.model.DayType;
import com.machineroom.model.EquipItem;
import com.machineroom.model.EquipType;
import com.machineroom.model.ImportantRecord;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.ReviewComment;
import com.machineroom.model.ScheduleType;
import com.machineroom.model.Shift;
import com.machineroom.model.Signature;
import com.machineroom.model.Task;

/**
 * All operations on a daily log. Every mutation runs in one transaction with the header row locked,
 * re-checks the permission on the server, writes the audit trail and returns the updated log.
 */
public final class LogService {

    private LogService() {
    }

    /** One change applied to a locked, freshly loaded log. */
    private interface Mutation {
        /** @return audit detail text */
        String apply(Connection c, DailyLog log, Permissions perm) throws Exception;
    }

    // ------------------------------------------------------------------ read

    /** Loads a log; today's log is created on first access, other missing dates are 404. */
    public static DailyLog getOrCreate(final LocalDate date, final UserPrincipal p) {
        return Db.tx(c -> {
            DailyLog log = DailyLogDao.load(c, date, false);
            if (log == null) {
                if (!date.equals(LogicalDate.today())) throw ServiceException.notFound("找不到 " + date + " 的日誌");
                log = createInTx(c, date, p.getUsername());
            }
            return log;
        });
    }

    public static DailyLog get(final LocalDate date) {
        DailyLog log = Db.tx(c -> DailyLogDao.load(c, date, false));
        if (log == null) throw ServiceException.notFound("找不到 " + date + " 的日誌");
        return log;
    }

    /** Creates a log for an arbitrary date (manual back-fill by an administrator). */
    public static DailyLog create(final LocalDate date, final UserPrincipal p) {
        if (!p.isAdmin()) throw ServiceException.forbidden("只有管理者可以手動新增日誌");
        DailyLog log = Db.tx(c -> {
            if (DailyLogDao.exists(c, date)) throw ServiceException.conflict(date + " 的日誌已存在");
            DailyLog l = createInTx(c, date, p.getUsername());
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "LOG_CREATE", date, "manual");
            return l;
        });
        return log;
    }

    static DailyLog createInTx(Connection c, LocalDate date, String user) throws Exception {
        DailyLog l = LogFactory.newLog(date, DayType.defaultFor(date), DefDao.tasks(c), DefDao.equips(c), DefDao.checks(c), user);
        DailyLogDao.insert(c, l);
        return l;
    }

    /** Yesterday's log if all three shifts are submitted but it was never integrated. */
    public static LocalDate pendingIntegration(final UserPrincipal p) {
        if (!p.isOperator()) return null;
        final LocalDate y = LogicalDate.today().minusDays(1);
        return Db.tx(c -> {
            DailyLog l = DailyLogDao.load(c, y, false);
            return l != null && l.status == ReportStatus.DRAFT && l.allShiftsSubmitted() ? y : null;
        });
    }

    // ------------------------------------------------------------------ mutation plumbing

    private static DailyLog mutate(final LocalDate date, final UserPrincipal p, final String action, final Mutation m) {
        return Db.tx(c -> {
            DailyLog log = DailyLogDao.load(c, date, true);
            if (log == null) throw ServiceException.notFound("找不到 " + date + " 的日誌");
            Permissions perm = new Permissions(log, p, LogicalDate.today(), LocalDateTime.now());
            String detail = m.apply(c, log, perm);
            DailyLogDao.touch(c, log, p.getUsername());
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), action, date, detail);
            return log;
        });
    }

    private static void require(boolean ok, String msg) {
        if (!ok) throw ServiceException.forbidden(msg);
    }

    private static String reqTime(String v, String label) {
        String s = v == null ? "" : v.trim();
        if (!LogicalDate.isValidTime(s)) throw ServiceException.badRequest(label + "時間格式須為 HH:mm");
        return s;
    }

    private static String reqNonNegative(String v, String label) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) return s;
        try {
            if (Integer.parseInt(s) < 0) throw ServiceException.badRequest(label + "不可為負數");
        } catch (NumberFormatException e) {
            throw ServiceException.badRequest(label + "須為整數");
        }
        return s;
    }

    private static String limit(String v, int max) {
        if (v == null) return "";
        String s = v.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String reqText(String v, String label, int max) {
        String s = limit(v, max);
        if (s.isEmpty()) throw ServiceException.badRequest(label + "必填");
        return s;
    }

    // ------------------------------------------------------------------ header

    public static DailyLog changeDayType(LocalDate date, UserPrincipal p, final String dayTypeKey, final String reason) {
        return mutate(date, p, "DAYTYPE", (c, log, perm) -> {
            require(perm.canChangeDayType(), "目前不可變更日類型");
            DayType dt = DayType.fromKey(dayTypeKey);
            if (dt == null) throw ServiceException.badRequest("日類型不正確");
            if (dt == log.dayType) return "unchanged";
            String r = reqText(reason, "變更原因", 500);
            log.dayType = dt;
            log.dayTypeOverride = true;
            log.dayTypeReason = r;
            log.dayTypeChangedBy = p.getUsername();
            for (Task t : log.tasks) {
                if (!t.forced) {
                    t.shouldExecute = ScheduleType.shouldExecute(t.schedule, log.date, dt);
                    DailyLogDao.saveTask(c, log.date, t);
                }
            }
            DailyLogDao.saveHeader(c, log);
            return dt.key() + " / " + r;
        });
    }

    public static DailyLog setBoot(LocalDate date, UserPrincipal p, final String bootUser, final String bootTime) {
        return mutate(date, p, "BOOT", (c, log, perm) -> {
            require(perm.canEditBoot(), "目前不可填寫開機資訊");
            log.bootUser = limit(bootUser, 64);
            log.bootTime = reqTime(bootTime, "開機");
            DailyLogDao.saveHeader(c, log);
            return log.bootUser + " " + log.bootTime;
        });
    }

    public static DailyLog signBoot(LocalDate date, UserPrincipal p) {
        return mutate(date, p, "BOOT_SIGN", (c, log, perm) -> {
            require(perm.canEditBoot(), "目前不可簽章開機資訊");
            if (log.bootUser.isEmpty() || log.bootTime.isEmpty()) throw ServiceException.badRequest("開機人 + 時間必填");
            log.bootOpSign = Signature.now(p.getUsername());
            DailyLogDao.saveHeader(c, log);
            return null;
        });
    }

    public static DailyLog reviewBoot(LocalDate date, UserPrincipal p) {
        return mutate(date, p, "BOOT_REVIEW", (c, log, perm) -> {
            require(perm.canReviewBoot(), "不可覆核開機資訊");
            log.bootReviewerSign = Signature.now(p.getUsername());
            DailyLogDao.saveHeader(c, log);
            return null;
        });
    }

    // ------------------------------------------------------------------ tasks

    private static Task task(DailyLog log, String code) {
        Task t = log.task(code);
        if (t == null) throw ServiceException.notFound("找不到批次作業 " + code);
        return t;
    }

    /** During integration, editing another operator's entry re-signs it as the integrator and drops the review. */
    private static void integratorResign(Permissions perm, Task t) {
        if (perm.isIntegrator() && t.opSign != null && !Signature.isBy(t.opSign, perm.user().getUsername())) {
            t.opSign = Signature.integrator(perm.user().getUsername());
            t.reviewerSign = null;
        }
    }

    public static DailyLog setTaskField(LocalDate date, UserPrincipal p, final String code, final String field, final String value) {
        return mutate(date, p, "TASK_FIELD", (c, log, perm) -> {
            Task t = task(log, code);
            require(perm.canEditTask(t), "目前不可編輯 " + code);
            switch (field == null ? "" : field) {
                case "startTime": t.startTime = reqTime(value, "開始"); break;
                case "endTime": t.endTime = reqTime(value, "結束"); break;
                case "qtyValue": t.qtyValue = reqNonNegative(value, "數量"); break;
                case "remark": t.remark = limit(value, 1000); break;
                default: throw ServiceException.badRequest("不支援的欄位：" + field);
            }
            integratorResign(perm, t);
            DailyLogDao.saveTask(c, log.date, t);
            return code + "." + field + "=" + value;
        });
    }

    public static DailyLog setTaskDone(LocalDate date, UserPrincipal p, final String code, final boolean done) {
        return mutate(date, p, "TASK_DONE", (c, log, perm) -> {
            Task t = task(log, code);
            require(perm.canEditTask(t), "目前不可編輯 " + code);
            if (done) {
                List<String> errs = new ArrayList<>();
                if (t.startTime.isEmpty()) errs.add("開始時間");
                if (t.abnormal && t.remark.trim().isEmpty()) errs.add("異常說明");
                if (!errs.isEmpty()) throw ServiceException.badRequest("需先填寫", errs);
                t.done = true;
                t.opSign = perm.isIntegrator() && t.opSign != null && !Signature.isBy(t.opSign, p.getUsername())
                        ? Signature.integrator(p.getUsername()) : Signature.now(p.getUsername());
            } else {
                t.done = false;
                t.opSign = null;
                t.reviewerSign = null;
            }
            DailyLogDao.saveTask(c, log.date, t);
            return code + " done=" + done;
        });
    }

    public static DailyLog setTaskAbnormal(LocalDate date, UserPrincipal p, final String code, final boolean abnormal) {
        return mutate(date, p, "TASK_ABNORMAL", (c, log, perm) -> {
            Task t = task(log, code);
            require(perm.canEditTask(t), "目前不可編輯 " + code);
            t.abnormal = abnormal;
            integratorResign(perm, t);
            DailyLogDao.saveTask(c, log.date, t);
            return code + " abnormal=" + abnormal;
        });
    }

    public static DailyLog reviewTask(LocalDate date, UserPrincipal p, final String code) {
        return mutate(date, p, "TASK_REVIEW", (c, log, perm) -> {
            Task t = task(log, code);
            require(perm.canReviewTask(t), "不可覆核 " + code);
            t.reviewerSign = Signature.now(p.getUsername());
            DailyLogDao.saveTask(c, log.date, t);
            return code;
        });
    }

    public static DailyLog handoverTask(LocalDate date, UserPrincipal p, final String code) {
        return mutate(date, p, "TASK_HANDOVER", (c, log, perm) -> {
            Task t = task(log, code);
            require(perm.canHandoverTask(t), "不可交班 " + code);
            t.handoverFrom = perm.shift();
            t.assignedShift = perm.shift().next();
            DailyLogDao.saveTask(c, log.date, t);
            return code + " → " + t.assignedShift.key();
        });
    }

    public static DailyLog forceTask(LocalDate date, UserPrincipal p, final String code, final String reason) {
        return mutate(date, p, "TASK_FORCE", (c, log, perm) -> {
            Task t = task(log, code);
            require(perm.canForceTask(t), "不可強制執行 " + code);
            t.forceReason = reqText(reason, "強制執行原因", 500);
            t.shouldExecute = true;
            t.forced = true;
            t.forcedBy = p.getUsername();
            t.assignedShift = perm.shift();
            DailyLogDao.saveTask(c, log.date, t);
            return code + " / " + t.forceReason;
        });
    }

    public static DailyLog quickTime(LocalDate date, UserPrincipal p, final String code) {
        return mutate(date, p, "TASK_QUICK", (c, log, perm) -> {
            Task t = task(log, code);
            require(perm.canEditTask(t), "目前不可編輯 " + code);
            String now = LogicalDate.nowHm();
            if (t.startTime.isEmpty()) t.startTime = now; else t.endTime = now;
            integratorResign(perm, t);
            DailyLogDao.saveTask(c, log.date, t);
            return code + " " + now;
        });
    }

    // ------------------------------------------------------------------ equipment

    private static EquipItem equip(DailyLog log, String defId) {
        EquipItem e = log.equip(defId);
        if (e == null) throw ServiceException.notFound("找不到系統設備項目 " + defId);
        return e;
    }

    public static DailyLog setEquipField(LocalDate date, UserPrincipal p, final String defId, final String field, final String value) {
        return mutate(date, p, "EQUIP_FIELD", (c, log, perm) -> {
            EquipItem e = equip(log, defId);
            require(perm.canEditEquip(e), "目前不可編輯「" + e.defName + "」");
            switch (field == null ? "" : field) {
                case "time": e.time = reqTime(value, "檢查"); break;
                case "status": {
                    String v = limit(value, 50);
                    if (!v.isEmpty() && !e.statusOptions.contains(v)) throw ServiceException.badRequest("狀態值不在選項內");
                    e.status = v;
                    break;
                }
                case "count":
                    if (!EquipType.DMS.key().equals(e.type)) throw ServiceException.badRequest("此項目無台數欄位");
                    e.count = reqNonNegative(value, "台數");
                    break;
                case "notify": e.notify = limit(value, 500); break;
                default: throw ServiceException.badRequest("不支援的欄位：" + field);
            }
            DailyLogDao.saveEquip(c, log.date, e);
            return defId + "." + field + "=" + value;
        });
    }

    public static DailyLog signEquip(LocalDate date, UserPrincipal p, final String defId) {
        return mutate(date, p, "EQUIP_SIGN", (c, log, perm) -> {
            EquipItem e = equip(log, defId);
            require(perm.canSignEquip(e), "「" + e.defName + "」尚未填妥或不可簽章");
            e.opSign = Signature.now(p.getUsername());
            DailyLogDao.saveEquip(c, log.date, e);
            return defId;
        });
    }

    public static DailyLog unsignEquip(LocalDate date, UserPrincipal p, final String defId) {
        return mutate(date, p, "EQUIP_UNSIGN", (c, log, perm) -> {
            EquipItem e = equip(log, defId);
            require(perm.canUnsignEquip(e), "不可撤回「" + e.defName + "」的簽章");
            e.opSign = null;
            e.reviewerSign = null;
            DailyLogDao.saveEquip(c, log.date, e);
            return defId;
        });
    }

    public static DailyLog reviewEquip(LocalDate date, UserPrincipal p, final String defId) {
        return mutate(date, p, "EQUIP_REVIEW", (c, log, perm) -> {
            EquipItem e = equip(log, defId);
            require(perm.canReviewEquip(e), "不可覆核「" + e.defName + "」");
            e.reviewerSign = Signature.now(p.getUsername());
            DailyLogDao.saveEquip(c, log.date, e);
            return defId;
        });
    }

    public static DailyLog unreviewEquip(LocalDate date, UserPrincipal p, final String defId) {
        return mutate(date, p, "EQUIP_UNREVIEW", (c, log, perm) -> {
            EquipItem e = equip(log, defId);
            require(perm.canUnreviewEquip(e), "不可撤回「" + e.defName + "」的覆核");
            e.reviewerSign = null;
            DailyLogDao.saveEquip(c, log.date, e);
            return defId;
        });
    }

    public static DailyLog toggleEquip(LocalDate date, UserPrincipal p, final String defId, final String reason) {
        return mutate(date, p, "EQUIP_TOGGLE", (c, log, perm) -> {
            EquipItem e = equip(log, defId);
            require(perm.canToggleIms(e), "「" + e.defName + "」不可切換");
            if (!e.enabled) {
                e.enableReason = reqText(reason, "啟用原因", 500);
                e.enabled = true;
            } else {
                e.enabled = false;
            }
            DailyLogDao.saveEquip(c, log.date, e);
            return defId + " enabled=" + e.enabled + (e.enabled ? " / " + e.enableReason : "");
        });
    }

    // ------------------------------------------------------------------ shift checks

    private static CheckItem check(DailyLog log, String shiftKey, String defId) {
        Shift s = Shift.fromKey(shiftKey);
        if (s == null) throw ServiceException.badRequest("班別不正確");
        CheckItem ci = log.check(s, defId);
        if (ci == null) throw ServiceException.notFound("找不到檢查項目 " + defId);
        return ci;
    }

    public static DailyLog setCheckField(LocalDate date, UserPrincipal p, final String shiftKey, final String defId,
                                         final String field, final String key, final String value) {
        return mutate(date, p, "CHECK_FIELD", (c, log, perm) -> {
            CheckItem ci = check(log, shiftKey, defId);
            require(perm.canEditCheck(ci), "目前不可編輯「" + ci.name + "」");
            CheckType t = ci.checkType();
            String v = limit(value, 50);
            switch (field == null ? "" : field) {
                case "time": ci.time = reqTime(value, "檢查"); break;
                case "status":
                    if (!v.isEmpty() && !"正常".equals(v) && !"異常".equals(v) && !("假日不執行".equals(v) && ci.holidaySkip)) {
                        throw ServiceException.badRequest("狀態值不正確");
                    }
                    ci.status = v;
                    break;
                case "entryLog":
                    if (t != CheckType.CABINET) throw ServiceException.badRequest("此項目無進出登記簿欄位");
                    if (!v.isEmpty() && !"正常".equals(v) && !"異常".equals(v)) throw ServiceException.badRequest("狀態值不正確");
                    ci.entryLog = v;
                    break;
                case "value":
                    if (key == null || !t.valueKeys().contains(key)) throw ServiceException.badRequest("子項目不正確：" + key);
                    if (t == CheckType.PORTAL) {
                        if (!v.isEmpty() && !"正常".equals(v) && !"異常".equals(v)) throw ServiceException.badRequest("狀態值不正確");
                    } else {
                        v = reqNonNegative(v, key);
                        if (t == CheckType.SMS && !v.isEmpty() && Integer.parseInt(v) > 100) throw ServiceException.badRequest(key + " 須為 0–100");
                    }
                    ci.values.put(key, v);
                    break;
                default: throw ServiceException.badRequest("不支援的欄位：" + field);
            }
            DailyLogDao.saveCheck(c, log.date, ci);
            return shiftKey + "/" + defId + "." + field + (key != null ? "[" + key + "]" : "") + "=" + value;
        });
    }

    public static DailyLog signCheck(LocalDate date, UserPrincipal p, final String shiftKey, final String defId) {
        return mutate(date, p, "CHECK_SIGN", (c, log, perm) -> {
            CheckItem ci = check(log, shiftKey, defId);
            require(perm.canSignCheck(ci), "「" + ci.name + "」尚未填妥或不可簽名");
            ci.opSign = Signature.now(p.getUsername());
            DailyLogDao.saveCheck(c, log.date, ci);
            return shiftKey + "/" + defId;
        });
    }

    public static DailyLog unsignCheck(LocalDate date, UserPrincipal p, final String shiftKey, final String defId) {
        return mutate(date, p, "CHECK_UNSIGN", (c, log, perm) -> {
            CheckItem ci = check(log, shiftKey, defId);
            require(perm.canUnsignCheck(ci), "不可撤回「" + ci.name + "」的簽名");
            ci.opSign = null;
            ci.reviewerSign = null;
            DailyLogDao.saveCheck(c, log.date, ci);
            return shiftKey + "/" + defId;
        });
    }

    public static DailyLog reviewCheck(LocalDate date, UserPrincipal p, final String shiftKey, final String defId) {
        return mutate(date, p, "CHECK_REVIEW", (c, log, perm) -> {
            CheckItem ci = check(log, shiftKey, defId);
            require(perm.canReviewCheck(ci), "不可覆核「" + ci.name + "」");
            ci.reviewerSign = Signature.now(p.getUsername());
            DailyLogDao.saveCheck(c, log.date, ci);
            return shiftKey + "/" + defId;
        });
    }

    public static DailyLog unreviewCheck(LocalDate date, UserPrincipal p, final String shiftKey, final String defId) {
        return mutate(date, p, "CHECK_UNREVIEW", (c, log, perm) -> {
            CheckItem ci = check(log, shiftKey, defId);
            require(perm.canUnreviewCheck(ci), "不可撤回「" + ci.name + "」的覆核");
            ci.reviewerSign = null;
            DailyLogDao.saveCheck(c, log.date, ci);
            return shiftKey + "/" + defId;
        });
    }

    // ------------------------------------------------------------------ job error / records

    public static DailyLog setJobError(LocalDate date, UserPrincipal p, final String shiftKey, final int value) {
        return mutate(date, p, "JOBERROR", (c, log, perm) -> {
            Shift s = Shift.fromKey(shiftKey);
            if (s == null) throw ServiceException.badRequest("班別不正確");
            require(perm.canEditJobError(s), "目前不可填寫 " + s.label() + " JOB ERROR");
            if (value < 0) throw ServiceException.badRequest("不可輸入負數");
            log.jobError.put(s, value);
            DailyLogDao.saveHeader(c, log);
            return s.key() + "=" + value;
        });
    }

    public static DailyLog addRecord(LocalDate date, UserPrincipal p, final ImportantRecord in) {
        return mutate(date, p, "RECORD_ADD", (c, log, perm) -> {
            require(perm.canManageRecords(), "目前不可新增重要記錄事項");
            ImportantRecord r = new ImportantRecord();
            copyRecordFields(in, r, true);
            r.source = ImportantRecord.validSource(in.source) ? in.source : ImportantRecord.SRC_MANUAL;
            r.shift = in.shift;
            r.op = p.getUsername();
            r.seq = DailyLogDao.nextRecordSeq(c, log.date);
            r.createdAt = Jdbc.now();
            DailyLogDao.insertRecord(c, log.date, r);
            log.records.add(r);
            return r.source + " " + r.taskCode + " " + limit(r.description, 100);
        });
    }

    public static DailyLog updateRecord(LocalDate date, UserPrincipal p, final int id, final ImportantRecord in) {
        return mutate(date, p, "RECORD_UPDATE", (c, log, perm) -> {
            require(perm.canManageRecords(), "目前不可修改重要記錄事項");
            ImportantRecord r = log.record(id);
            if (r == null) throw ServiceException.notFound("找不到事件 #" + id);
            copyRecordFields(in, r, false);
            DailyLogDao.updateRecord(c, log.date, r);
            return "#" + id;
        });
    }

    public static DailyLog deleteRecord(LocalDate date, UserPrincipal p, final int id) {
        return mutate(date, p, "RECORD_DELETE", (c, log, perm) -> {
            require(perm.canManageRecords(), "目前不可刪除重要記錄事項");
            ImportantRecord r = log.record(id);
            if (r == null) throw ServiceException.notFound("找不到事件 #" + id);
            DailyLogDao.deleteRecord(c, log.date, id);
            log.records.remove(r);
            return "#" + id + " " + limit(r.description, 100);
        });
    }

    /** Null fields in {@code in} mean "leave unchanged" on update; on create they become "". */
    private static void copyRecordFields(ImportantRecord in, ImportantRecord r, boolean create) {
        if (create || in.time != null) r.time = reqTime(in.time, "發生");
        if (create || in.taskCode != null) r.taskCode = limit(in.taskCode, 120);
        if (create || in.description != null) r.description = reqText(in.description, "狀況描述", 2000);
        if (create || in.notifySP != null) r.notifySP = limit(in.notifySP, 200);
        if (create || in.notifyAP != null) r.notifyAP = limit(in.notifyAP, 200);
        if (create || in.recoverTime != null) r.recoverTime = reqTime(in.recoverTime, "復原");
        if (create || in.ticket != null) r.ticket = limit(in.ticket, 100);
    }

    // ------------------------------------------------------------------ workflow

    public static DailyLog submitShift(LocalDate date, UserPrincipal p, final String shiftKey) {
        return mutate(date, p, "SUBMIT_SHIFT", (c, log, perm) -> {
            Shift s = Shift.fromKey(shiftKey);
            if (s == null) throw ServiceException.badRequest("班別不正確");
            require(s == perm.shift(), "只能送出自己的班別");
            require(perm.canSubmitShift(), "目前不可送出本班");
            List<String> errs = LogSummary.shiftSubmitErrors(log, s);
            if (!errs.isEmpty()) throw ServiceException.badRequest("無法送出，請先完成以下項目", errs);
            log.shiftStatus.put(s, DailyLog.SHIFT_SUBMITTED);
            log.shiftSubmits.put(s, Signature.now(p.getUsername()));
            DailyLogDao.saveShift(c, log, s);
            return s.key();
        });
    }

    public static DailyLog submitAll(LocalDate date, UserPrincipal p) {
        return mutate(date, p, "SUBMIT_ALL", (c, log, perm) -> {
            require(perm.canSubmitAll(), "3 班皆送出後才可整合送簽");
            List<String> errs = LogSummary.submitAllErrors(log);
            if (!errs.isEmpty()) throw ServiceException.badRequest("無法送簽", errs);
            log.status = ReportStatus.SUBMITTED;
            log.approvalOperator = Signature.now(p.getUsername());
            DailyLogDao.saveHeader(c, log);
            return null;
        });
    }

    public static DailyLog recall(LocalDate date, UserPrincipal p) {
        return mutate(date, p, "RECALL", (c, log, perm) -> {
            require(perm.canRecall(), "目前不可取回");
            log.status = ReportStatus.DRAFT;
            log.approvalOperator = null;
            DailyLogDao.saveHeader(c, log);
            return null;
        });
    }

    public static DailyLog approve(LocalDate date, UserPrincipal p, final String comment) {
        return mutate(date, p, "APPROVE", (c, log, perm) -> {
            require(perm.canApprove(), "目前狀態不可審核 / 核准");
            String txt = limit(comment, 2000);
            if (!txt.isEmpty()) {
                ReviewComment rc = new ReviewComment(p.getUsername(), p.getActiveRole().label(), Jdbc.now(), txt);
                DailyLogDao.insertComment(c, log.date, rc);
                log.reviewComments.add(rc);
            }
            if (p.isDeputy()) {
                log.status = ReportStatus.REVIEWED;
                log.approvalDeputy = Signature.now(p.getUsername());
            } else {
                log.status = ReportStatus.APPROVED;
                log.approvalChief = Signature.now(p.getUsername());
            }
            DailyLogDao.saveHeader(c, log);
            return log.status.key() + (txt.isEmpty() ? "" : " / " + txt);
        });
    }

    public static DailyLog reject(LocalDate date, UserPrincipal p, final String reason) {
        return mutate(date, p, "REJECT", (c, log, perm) -> {
            require(perm.canReject(), "目前狀態不可退件");
            String txt = reqText(reason, "退件原因", 2000);
            ReviewComment rc = new ReviewComment(p.getUsername(), p.getActiveRole().label() + " 退件", Jdbc.now(), txt);
            DailyLogDao.insertComment(c, log.date, rc);
            log.reviewComments.add(rc);
            log.status = ReportStatus.DRAFT;
            log.approvalOperator = null;
            log.approvalDeputy = null;
            log.approvalChief = null;
            DailyLogDao.saveHeader(c, log);
            return txt;
        });
    }

    public static DailyLog unlock(LocalDate date, UserPrincipal p, final String reason) {
        return mutate(date, p, "UNLOCK", (c, log, perm) -> {
            require(perm.canUnlock(), "只有副科 / 科長可解鎖已核准的日誌");
            log.unlockReason = reqText(reason, "解鎖原因", 500);
            log.status = ReportStatus.DRAFT;
            log.versions++;
            log.approvalOperator = null;
            log.approvalDeputy = null;
            log.approvalChief = null;
            for (Shift s : Shift.values()) {
                log.shiftStatus.put(s, DailyLog.SHIFT_DRAFT);
                log.shiftSubmits.put(s, null);
                DailyLogDao.saveShift(c, log, s);
            }
            DailyLogDao.saveHeader(c, log);
            return "v" + log.versions + " / " + log.unlockReason;
        });
    }
}
