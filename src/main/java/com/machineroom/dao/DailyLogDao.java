package com.machineroom.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.machineroom.db.Jdbc;
import com.machineroom.model.CheckItem;
import com.machineroom.model.DailyLog;
import com.machineroom.model.DayType;
import com.machineroom.model.EquipItem;
import com.machineroom.model.ImportantRecord;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.ReviewComment;
import com.machineroom.model.Shift;
import com.machineroom.model.Signature;
import com.machineroom.model.Task;

/** DAILY_LOG and its child tables. Every method runs inside the caller's transaction. */
public final class DailyLogDao {

    private DailyLogDao() {
    }

    /** Light-weight row for the date selector. */
    public static final class DateSummary {
        public LocalDate date;
        public String weekday;
        public ReportStatus status;
        public Map<Shift, String> shiftStatus = new EnumMap<>(Shift.class);
    }

    /** Row of the history table. */
    public static final class HistoryRow {
        public LocalDate date;
        public String weekday;
        public ReportStatus status;
        public int batchDone;
        public int batchTotal;
        public int abnormal;
        public int jobError;
        public int records;
    }

    /** Numbers for the statistics tab. */
    public static final class Stats {
        public int days;
        public int abnormal;
        public int forced;
        public int jobError;
        public int records;
    }

    // ------------------------------------------------------------------ load

    public static boolean exists(Connection c, LocalDate d) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM DAILY_LOG WHERE LOG_DATE=?")) {
            Jdbc.setDate(ps, 1, d);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Loads a complete log. With {@code forUpdate} the header row is locked so concurrent
     * operators editing the same day are serialised per transaction.
     */
    public static DailyLog load(Connection c, LocalDate d, boolean forUpdate) throws SQLException {
        DailyLog log;
        String sql = "SELECT * FROM DAILY_LOG WHERE LOG_DATE=?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Jdbc.setDate(ps, 1, d);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                log = readHeader(rs);
            }
        }
        loadShifts(c, log);
        loadTasks(c, log);
        loadEquip(c, log);
        loadChecks(c, log);
        loadRecords(c, log);
        loadComments(c, log);
        return log;
    }

    private static DailyLog readHeader(ResultSet rs) throws SQLException {
        DailyLog l = new DailyLog();
        l.date = Jdbc.getDate(rs, "LOG_DATE");
        l.weekday = rs.getString("WEEKDAY");
        l.solarDay = rs.getInt("SOLAR_DAY");
        l.dayType = DayType.fromKey(rs.getString("DAY_TYPE"));
        l.dayTypeOverride = Jdbc.getBool(rs, "DAY_TYPE_OVERRIDE");
        l.dayTypeReason = Jdbc.getStrOrEmpty(rs, "DAY_TYPE_REASON");
        l.dayTypeChangedBy = Jdbc.getStrOrEmpty(rs, "DAY_TYPE_CHANGED_BY");
        l.bootUser = Jdbc.getStrOrEmpty(rs, "BOOT_USER");
        l.bootTime = Jdbc.getStrOrEmpty(rs, "BOOT_TIME");
        l.bootOpSign = Signature.of(rs.getString("BOOT_OP_USER"), Jdbc.getTs(rs, "BOOT_OP_TIME"));
        l.bootReviewerSign = Signature.of(rs.getString("BOOT_REV_USER"), Jdbc.getTs(rs, "BOOT_REV_TIME"));
        l.status = ReportStatus.fromKey(rs.getString("STATUS"));
        l.unlockReason = Jdbc.getStrOrEmpty(rs, "UNLOCK_REASON");
        l.versions = rs.getInt("VERSION_NO");
        l.approvalOperator = Signature.of(rs.getString("APPR_OP_USER"), Jdbc.getTs(rs, "APPR_OP_TIME"));
        l.approvalDeputy = Signature.of(rs.getString("APPR_DEPUTY_USER"), Jdbc.getTs(rs, "APPR_DEPUTY_TIME"));
        l.approvalChief = Signature.of(rs.getString("APPR_CHIEF_USER"), Jdbc.getTs(rs, "APPR_CHIEF_TIME"));
        l.jobError.put(Shift.DAY, rs.getInt("JOB_ERR_DAY"));
        l.jobError.put(Shift.EVENING, rs.getInt("JOB_ERR_EVENING"));
        l.jobError.put(Shift.NIGHT, rs.getInt("JOB_ERR_NIGHT"));
        l.createdAt = Jdbc.getTs(rs, "CREATED_AT");
        l.createdBy = rs.getString("CREATED_BY");
        l.updatedAt = Jdbc.getTs(rs, "UPDATED_AT");
        l.updatedBy = rs.getString("UPDATED_BY");
        return l;
    }

    private static void loadShifts(Connection c, DailyLog l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM LOG_SHIFT WHERE LOG_DATE=?")) {
            Jdbc.setDate(ps, 1, l.date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Shift s = Shift.fromKey(rs.getString("SHIFT"));
                    if (s == null) continue;
                    l.shiftStatus.put(s, rs.getString("STATUS"));
                    l.shiftSubmits.put(s, Signature.of(rs.getString("SUBMIT_USER"), Jdbc.getTs(rs, "SUBMIT_TIME")));
                }
            }
        }
    }

    private static void loadTasks(Connection c, DailyLog l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM LOG_TASK WHERE LOG_DATE=? ORDER BY SEQ, CODE")) {
            Jdbc.setDate(ps, 1, l.date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Task t = new Task();
                    t.code = rs.getString("CODE");
                    t.seq = rs.getInt("SEQ");
                    t.name = rs.getString("NAME");
                    t.schedule = rs.getString("SCHEDULE");
                    t.plannedStart = rs.getString("PLANNED_START");
                    t.shift = rs.getString("SHIFT");
                    t.hasEnd = Jdbc.getBool(rs, "HAS_END");
                    t.qtyLabel = rs.getString("QTY_LABEL");
                    t.shouldExecute = Jdbc.getBool(rs, "SHOULD_EXECUTE");
                    t.assignedShift = Shift.fromKey(rs.getString("ASSIGNED_SHIFT"));
                    t.forced = Jdbc.getBool(rs, "FORCED");
                    t.forceReason = Jdbc.getStrOrEmpty(rs, "FORCE_REASON");
                    t.forcedBy = Jdbc.getStrOrEmpty(rs, "FORCED_BY");
                    t.handoverFrom = Shift.fromKey(rs.getString("HANDOVER_FROM"));
                    t.handoverEndShift = Shift.fromKey(rs.getString("HANDOVER_END_SHIFT"));
                    t.done = Jdbc.getBool(rs, "DONE");
                    t.abnormal = Jdbc.getBool(rs, "ABNORMAL");
                    t.startTime = Jdbc.getStrOrEmpty(rs, "START_TIME");
                    t.endTime = Jdbc.getStrOrEmpty(rs, "END_TIME");
                    t.qtyValue = Jdbc.getStrOrEmpty(rs, "QTY_VALUE");
                    t.remark = Jdbc.getStrOrEmpty(rs, "REMARK");
                    t.opSign = Signature.of(rs.getString("OP_USER"), Jdbc.getTs(rs, "OP_TIME"), Jdbc.getBool(rs, "OP_INTEGRATOR"));
                    t.reviewerSign = Signature.of(rs.getString("REV_USER"), Jdbc.getTs(rs, "REV_TIME"));
                    l.tasks.add(t);
                }
            }
        }
    }

    private static void loadEquip(Connection c, DailyLog l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM LOG_EQUIP WHERE LOG_DATE=? ORDER BY SEQ, DEF_ID")) {
            Jdbc.setDate(ps, 1, l.date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EquipItem e = new EquipItem();
                    e.defId = rs.getString("DEF_ID");
                    e.seq = rs.getInt("SEQ");
                    e.defName = rs.getString("DEF_NAME");
                    e.type = rs.getString("ITEM_TYPE");
                    e.shift = Shift.fromKey(rs.getString("SHIFT"));
                    e.time = Jdbc.getStrOrEmpty(rs, "CHECK_TIME");
                    e.status = Jdbc.getStrOrEmpty(rs, "STATUS_VAL");
                    e.statusOptions = DefDao.splitOptions(rs.getString("STATUS_OPTIONS"));
                    e.count = Jdbc.getStrOrEmpty(rs, "COUNT_VAL");
                    e.notify = Jdbc.getStrOrEmpty(rs, "NOTIFY_VAL");
                    e.enabled = Jdbc.getBool(rs, "ENABLED");
                    e.enableReason = Jdbc.getStrOrEmpty(rs, "ENABLE_REASON");
                    e.opSign = Signature.of(rs.getString("OP_USER"), Jdbc.getTs(rs, "OP_TIME"));
                    e.reviewerSign = Signature.of(rs.getString("REV_USER"), Jdbc.getTs(rs, "REV_TIME"));
                    l.equip.add(e);
                }
            }
        }
    }

    private static void loadChecks(Connection c, DailyLog l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM LOG_CHECK WHERE LOG_DATE=? ORDER BY SHIFT, SEQ, DEF_ID")) {
            Jdbc.setDate(ps, 1, l.date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CheckItem ci = new CheckItem();
                    ci.defId = rs.getString("DEF_ID");
                    ci.seq = rs.getInt("SEQ");
                    ci.name = rs.getString("NAME");
                    ci.type = rs.getString("ITEM_TYPE");
                    ci.shift = Shift.fromKey(rs.getString("SHIFT"));
                    ci.time = Jdbc.getStrOrEmpty(rs, "CHECK_TIME");
                    ci.status = Jdbc.getStrOrEmpty(rs, "STATUS_VAL");
                    ci.entryLog = Jdbc.getStrOrEmpty(rs, "ENTRY_LOG");
                    ci.holidaySkip = Jdbc.getBool(rs, "HOLIDAY_SKIP");
                    ci.opSign = Signature.of(rs.getString("OP_USER"), Jdbc.getTs(rs, "OP_TIME"));
                    ci.reviewerSign = Signature.of(rs.getString("REV_USER"), Jdbc.getTs(rs, "REV_TIME"));
                    for (String k : ci.checkType().valueKeys()) ci.values.put(k, "");
                    if (ci.shift != null) l.checks.get(ci.shift).add(ci);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT SHIFT, DEF_ID, VAL_KEY, VAL FROM LOG_CHECK_VALUE WHERE LOG_DATE=?")) {
            Jdbc.setDate(ps, 1, l.date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Shift s = Shift.fromKey(rs.getString("SHIFT"));
                    if (s == null) continue;
                    CheckItem ci = l.check(s, rs.getString("DEF_ID"));
                    if (ci != null) ci.values.put(rs.getString("VAL_KEY"), Jdbc.getStrOrEmpty(rs, "VAL"));
                }
            }
        }
    }

    private static void loadRecords(Connection c, DailyLog l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM LOG_RECORD WHERE LOG_DATE=? ORDER BY SEQ, ID")) {
            Jdbc.setDate(ps, 1, l.date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ImportantRecord r = new ImportantRecord();
                    r.id = rs.getInt("ID");
                    r.seq = rs.getInt("SEQ");
                    r.time = Jdbc.getStrOrEmpty(rs, "EVENT_TIME");
                    r.taskCode = Jdbc.getStrOrEmpty(rs, "TASK_CODE");
                    r.description = Jdbc.getStrOrEmpty(rs, "DESCRIPTION");
                    r.notifySP = Jdbc.getStrOrEmpty(rs, "NOTIFY_SP");
                    r.notifyAP = Jdbc.getStrOrEmpty(rs, "NOTIFY_AP");
                    r.recoverTime = Jdbc.getStrOrEmpty(rs, "RECOVER_TIME");
                    r.ticket = Jdbc.getStrOrEmpty(rs, "TICKET");
                    r.op = rs.getString("OP_USER");
                    r.source = rs.getString("SRC");
                    r.shift = Shift.fromKey(rs.getString("SHIFT"));
                    r.createdAt = Jdbc.getTs(rs, "CREATED_AT");
                    l.records.add(r);
                }
            }
        }
    }

    private static void loadComments(Connection c, DailyLog l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM LOG_COMMENT WHERE LOG_DATE=? ORDER BY COMMENT_TIME, ID")) {
            Jdbc.setDate(ps, 1, l.date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ReviewComment rc = new ReviewComment();
                    rc.id = rs.getInt("ID");
                    rc.by = rs.getString("BY_USER");
                    rc.role = rs.getString("ROLE_NAME");
                    rc.time = Jdbc.getTs(rs, "COMMENT_TIME");
                    rc.text = rs.getString("COMMENT_TEXT");
                    l.reviewComments.add(rc);
                }
            }
        }
    }

    // ------------------------------------------------------------------ insert (new log / import)

    public static void insert(Connection c, DailyLog l) throws SQLException {
        Jdbc.update(c, "INSERT INTO DAILY_LOG (LOG_DATE,WEEKDAY,SOLAR_DAY,DAY_TYPE,DAY_TYPE_OVERRIDE,DAY_TYPE_REASON,DAY_TYPE_CHANGED_BY,"
                        + "BOOT_USER,BOOT_TIME,BOOT_OP_USER,BOOT_OP_TIME,BOOT_REV_USER,BOOT_REV_TIME,STATUS,UNLOCK_REASON,VERSION_NO,"
                        + "APPR_OP_USER,APPR_OP_TIME,APPR_DEPUTY_USER,APPR_DEPUTY_TIME,APPR_CHIEF_USER,APPR_CHIEF_TIME,"
                        + "JOB_ERR_DAY,JOB_ERR_EVENING,JOB_ERR_NIGHT,CREATED_AT,CREATED_BY,UPDATED_AT,UPDATED_BY) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                l.date, l.weekday, l.solarDay, l.dayType.key(), l.dayTypeOverride, l.dayTypeReason, l.dayTypeChangedBy,
                l.bootUser, l.bootTime, user(l.bootOpSign), time(l.bootOpSign), user(l.bootReviewerSign), time(l.bootReviewerSign),
                l.status.key(), l.unlockReason, l.versions,
                user(l.approvalOperator), time(l.approvalOperator), user(l.approvalDeputy), time(l.approvalDeputy),
                user(l.approvalChief), time(l.approvalChief),
                nz(l.jobError.get(Shift.DAY)), nz(l.jobError.get(Shift.EVENING)), nz(l.jobError.get(Shift.NIGHT)),
                l.createdAt, l.createdBy, l.updatedAt, l.updatedBy);
        for (Shift s : Shift.values()) {
            Signature sub = l.shiftSubmits.get(s);
            Jdbc.update(c, "INSERT INTO LOG_SHIFT (LOG_DATE,SHIFT,STATUS,SUBMIT_USER,SUBMIT_TIME) VALUES (?,?,?,?,?)",
                    l.date, s.key(), l.shiftStatus.get(s), user(sub), time(sub));
        }
        for (Task t : l.tasks) insertTask(c, l.date, t);
        for (EquipItem e : l.equip) insertEquip(c, l.date, e);
        for (Shift s : Shift.values()) {
            for (CheckItem ci : l.checks.get(s)) insertCheck(c, l.date, ci);
        }
        for (ImportantRecord r : l.records) insertRecord(c, l.date, r);
        for (ReviewComment rc : l.reviewComments) insertComment(c, l.date, rc);
    }

    private static void insertTask(Connection c, LocalDate d, Task t) throws SQLException {
        Jdbc.update(c, "INSERT INTO LOG_TASK (LOG_DATE,CODE,SEQ,NAME,SCHEDULE,PLANNED_START,SHIFT,HAS_END,QTY_LABEL,SHOULD_EXECUTE,"
                        + "ASSIGNED_SHIFT,FORCED,FORCE_REASON,FORCED_BY,HANDOVER_FROM,HANDOVER_END_SHIFT,DONE,ABNORMAL,"
                        + "START_TIME,END_TIME,QTY_VALUE,REMARK,OP_USER,OP_TIME,OP_INTEGRATOR,REV_USER,REV_TIME) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                d, t.code, t.seq, t.name, t.schedule, t.plannedStart, t.shift, t.hasEnd, t.qtyLabel, t.shouldExecute,
                key(t.assignedShift), t.forced, t.forceReason, t.forcedBy, key(t.handoverFrom), key(t.handoverEndShift),
                t.done, t.abnormal, t.startTime, t.endTime, t.qtyValue, t.remark,
                user(t.opSign), time(t.opSign), t.opSign != null && t.opSign.integratorEdit, user(t.reviewerSign), time(t.reviewerSign));
    }

    private static void insertEquip(Connection c, LocalDate d, EquipItem e) throws SQLException {
        Jdbc.update(c, "INSERT INTO LOG_EQUIP (LOG_DATE,DEF_ID,SEQ,DEF_NAME,ITEM_TYPE,SHIFT,CHECK_TIME,STATUS_VAL,STATUS_OPTIONS,"
                        + "COUNT_VAL,NOTIFY_VAL,ENABLED,ENABLE_REASON,OP_USER,OP_TIME,REV_USER,REV_TIME) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                d, e.defId, e.seq, e.defName, e.type, e.shift.key(), e.time, e.status, DefDao.joinOptions(e.statusOptions),
                e.count, e.notify, e.enabled, e.enableReason, user(e.opSign), time(e.opSign), user(e.reviewerSign), time(e.reviewerSign));
    }

    private static void insertCheck(Connection c, LocalDate d, CheckItem ci) throws SQLException {
        Jdbc.update(c, "INSERT INTO LOG_CHECK (LOG_DATE,SHIFT,DEF_ID,SEQ,NAME,ITEM_TYPE,CHECK_TIME,STATUS_VAL,ENTRY_LOG,HOLIDAY_SKIP,"
                        + "OP_USER,OP_TIME,REV_USER,REV_TIME) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                d, ci.shift.key(), ci.defId, ci.seq, ci.name, ci.type, ci.time, ci.status, ci.entryLog, ci.holidaySkip,
                user(ci.opSign), time(ci.opSign), user(ci.reviewerSign), time(ci.reviewerSign));
        insertCheckValues(c, d, ci);
    }

    private static void insertCheckValues(Connection c, LocalDate d, CheckItem ci) throws SQLException {
        for (Map.Entry<String, String> en : ci.values.entrySet()) {
            Jdbc.update(c, "INSERT INTO LOG_CHECK_VALUE (LOG_DATE,SHIFT,DEF_ID,VAL_KEY,VAL) VALUES (?,?,?,?,?)",
                    d, ci.shift.key(), ci.defId, en.getKey(), en.getValue());
        }
    }

    // ------------------------------------------------------------------ targeted updates

    public static void saveHeader(Connection c, DailyLog l) throws SQLException {
        Jdbc.update(c, "UPDATE DAILY_LOG SET DAY_TYPE=?,DAY_TYPE_OVERRIDE=?,DAY_TYPE_REASON=?,DAY_TYPE_CHANGED_BY=?,"
                        + "BOOT_USER=?,BOOT_TIME=?,BOOT_OP_USER=?,BOOT_OP_TIME=?,BOOT_REV_USER=?,BOOT_REV_TIME=?,"
                        + "STATUS=?,UNLOCK_REASON=?,VERSION_NO=?,APPR_OP_USER=?,APPR_OP_TIME=?,APPR_DEPUTY_USER=?,APPR_DEPUTY_TIME=?,"
                        + "APPR_CHIEF_USER=?,APPR_CHIEF_TIME=?,JOB_ERR_DAY=?,JOB_ERR_EVENING=?,JOB_ERR_NIGHT=?,UPDATED_AT=?,UPDATED_BY=? "
                        + "WHERE LOG_DATE=?",
                l.dayType.key(), l.dayTypeOverride, l.dayTypeReason, l.dayTypeChangedBy,
                l.bootUser, l.bootTime, user(l.bootOpSign), time(l.bootOpSign), user(l.bootReviewerSign), time(l.bootReviewerSign),
                l.status.key(), l.unlockReason, l.versions, user(l.approvalOperator), time(l.approvalOperator),
                user(l.approvalDeputy), time(l.approvalDeputy), user(l.approvalChief), time(l.approvalChief),
                nz(l.jobError.get(Shift.DAY)), nz(l.jobError.get(Shift.EVENING)), nz(l.jobError.get(Shift.NIGHT)),
                l.updatedAt, l.updatedBy, l.date);
    }

    public static void saveShift(Connection c, DailyLog l, Shift s) throws SQLException {
        Signature sub = l.shiftSubmits.get(s);
        Jdbc.update(c, "UPDATE LOG_SHIFT SET STATUS=?,SUBMIT_USER=?,SUBMIT_TIME=? WHERE LOG_DATE=? AND SHIFT=?",
                l.shiftStatus.get(s), user(sub), time(sub), l.date, s.key());
    }

    public static void saveTask(Connection c, LocalDate d, Task t) throws SQLException {
        Jdbc.update(c, "UPDATE LOG_TASK SET SHOULD_EXECUTE=?,ASSIGNED_SHIFT=?,FORCED=?,FORCE_REASON=?,FORCED_BY=?,HANDOVER_FROM=?,"
                        + "HANDOVER_END_SHIFT=?,DONE=?,ABNORMAL=?,START_TIME=?,END_TIME=?,QTY_VALUE=?,REMARK=?,"
                        + "OP_USER=?,OP_TIME=?,OP_INTEGRATOR=?,REV_USER=?,REV_TIME=? WHERE LOG_DATE=? AND CODE=?",
                t.shouldExecute, key(t.assignedShift), t.forced, t.forceReason, t.forcedBy, key(t.handoverFrom),
                key(t.handoverEndShift), t.done, t.abnormal, t.startTime, t.endTime, t.qtyValue, t.remark,
                user(t.opSign), time(t.opSign), t.opSign != null && t.opSign.integratorEdit, user(t.reviewerSign), time(t.reviewerSign),
                d, t.code);
    }

    public static void saveEquip(Connection c, LocalDate d, EquipItem e) throws SQLException {
        Jdbc.update(c, "UPDATE LOG_EQUIP SET CHECK_TIME=?,STATUS_VAL=?,COUNT_VAL=?,NOTIFY_VAL=?,ENABLED=?,ENABLE_REASON=?,"
                        + "OP_USER=?,OP_TIME=?,REV_USER=?,REV_TIME=? WHERE LOG_DATE=? AND DEF_ID=?",
                e.time, e.status, e.count, e.notify, e.enabled, e.enableReason,
                user(e.opSign), time(e.opSign), user(e.reviewerSign), time(e.reviewerSign), d, e.defId);
    }

    public static void saveCheck(Connection c, LocalDate d, CheckItem ci) throws SQLException {
        Jdbc.update(c, "UPDATE LOG_CHECK SET CHECK_TIME=?,STATUS_VAL=?,ENTRY_LOG=?,OP_USER=?,OP_TIME=?,REV_USER=?,REV_TIME=? "
                        + "WHERE LOG_DATE=? AND SHIFT=? AND DEF_ID=?",
                ci.time, ci.status, ci.entryLog, user(ci.opSign), time(ci.opSign), user(ci.reviewerSign), time(ci.reviewerSign),
                d, ci.shift.key(), ci.defId);
        Jdbc.update(c, "DELETE FROM LOG_CHECK_VALUE WHERE LOG_DATE=? AND SHIFT=? AND DEF_ID=?", d, ci.shift.key(), ci.defId);
        insertCheckValues(c, d, ci);
    }

    /** Replaces all task rows (apply-today). */
    public static void replaceTasks(Connection c, LocalDate d, List<Task> tasks) throws SQLException {
        Jdbc.update(c, "DELETE FROM LOG_TASK WHERE LOG_DATE=?", d);
        for (Task t : tasks) insertTask(c, d, t);
    }

    public static void replaceEquip(Connection c, LocalDate d, List<EquipItem> items) throws SQLException {
        Jdbc.update(c, "DELETE FROM LOG_EQUIP WHERE LOG_DATE=?", d);
        for (EquipItem e : items) insertEquip(c, d, e);
    }

    public static void replaceChecks(Connection c, LocalDate d, Map<Shift, List<CheckItem>> checks) throws SQLException {
        Jdbc.update(c, "DELETE FROM LOG_CHECK_VALUE WHERE LOG_DATE=?", d);
        Jdbc.update(c, "DELETE FROM LOG_CHECK WHERE LOG_DATE=?", d);
        for (Shift s : Shift.values()) {
            for (CheckItem ci : checks.get(s)) insertCheck(c, d, ci);
        }
    }

    public static int insertRecord(Connection c, LocalDate d, ImportantRecord r) throws SQLException {
        String sql = "INSERT INTO LOG_RECORD (LOG_DATE,SEQ,EVENT_TIME,TASK_CODE,DESCRIPTION,NOTIFY_SP,NOTIFY_AP,RECOVER_TIME,TICKET,"
                + "OP_USER,SRC,SHIFT,CREATED_AT) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            Jdbc.bind(ps, d, r.seq, r.time, r.taskCode, r.description, r.notifySP, r.notifyAP, r.recoverTime, r.ticket,
                    r.op, r.source, key(r.shift), r.createdAt == null ? Jdbc.now() : r.createdAt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) r.id = rs.getInt(1);
            }
        }
        return r.id;
    }

    public static void updateRecord(Connection c, LocalDate d, ImportantRecord r) throws SQLException {
        Jdbc.update(c, "UPDATE LOG_RECORD SET EVENT_TIME=?,TASK_CODE=?,DESCRIPTION=?,NOTIFY_SP=?,NOTIFY_AP=?,RECOVER_TIME=?,TICKET=? "
                        + "WHERE LOG_DATE=? AND ID=?",
                r.time, r.taskCode, r.description, r.notifySP, r.notifyAP, r.recoverTime, r.ticket, d, r.id);
    }

    public static void deleteRecord(Connection c, LocalDate d, int id) throws SQLException {
        Jdbc.update(c, "DELETE FROM LOG_RECORD WHERE LOG_DATE=? AND ID=?", d, id);
    }

    public static int nextRecordSeq(Connection c, LocalDate d) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(SEQ),0)+1 FROM LOG_RECORD WHERE LOG_DATE=?")) {
            Jdbc.setDate(ps, 1, d);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static void insertComment(Connection c, LocalDate d, ReviewComment rc) throws SQLException {
        Jdbc.update(c, "INSERT INTO LOG_COMMENT (LOG_DATE,BY_USER,ROLE_NAME,COMMENT_TIME,COMMENT_TEXT) VALUES (?,?,?,?,?)",
                d, rc.by, rc.role, rc.time == null ? Jdbc.now() : rc.time, rc.text);
    }

    /** Bumps UPDATED_AT / UPDATED_BY (the polling stamp). */
    public static void touch(Connection c, DailyLog l, String user) throws SQLException {
        l.updatedAt = Jdbc.now();
        l.updatedBy = user;
        Jdbc.update(c, "UPDATE DAILY_LOG SET UPDATED_AT=?, UPDATED_BY=? WHERE LOG_DATE=?", l.updatedAt, l.updatedBy, l.date);
    }

    // ------------------------------------------------------------------ lists

    public static List<DateSummary> dateSummaries(Connection c) throws SQLException {
        List<DateSummary> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT LOG_DATE, WEEKDAY, STATUS FROM DAILY_LOG ORDER BY LOG_DATE DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                DateSummary s = new DateSummary();
                s.date = Jdbc.getDate(rs, "LOG_DATE");
                s.weekday = rs.getString("WEEKDAY");
                s.status = ReportStatus.fromKey(rs.getString("STATUS"));
                for (Shift sh : Shift.values()) s.shiftStatus.put(sh, DailyLog.SHIFT_DRAFT);
                out.add(s);
            }
        }
        if (out.isEmpty()) return out;
        Map<LocalDate, DateSummary> byDate = new java.util.HashMap<>();
        for (DateSummary s : out) byDate.put(s.date, s);
        try (PreparedStatement ps = c.prepareStatement("SELECT LOG_DATE, SHIFT, STATUS FROM LOG_SHIFT");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                DateSummary s = byDate.get(Jdbc.getDate(rs, "LOG_DATE"));
                Shift sh = Shift.fromKey(rs.getString("SHIFT"));
                if (s != null && sh != null) s.shiftStatus.put(sh, rs.getString("STATUS"));
            }
        }
        return out;
    }

    public static List<HistoryRow> history(Connection c, Integer year, Integer month, ReportStatus status) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT L.LOG_DATE, L.WEEKDAY, L.STATUS, (L.JOB_ERR_DAY + L.JOB_ERR_EVENING + L.JOB_ERR_NIGHT) AS JE,"
                + " (SELECT COUNT(*) FROM LOG_TASK T WHERE T.LOG_DATE=L.LOG_DATE AND T.SHOULD_EXECUTE=1) AS BT,"
                + " (SELECT COUNT(*) FROM LOG_TASK T WHERE T.LOG_DATE=L.LOG_DATE AND T.SHOULD_EXECUTE=1 AND T.DONE=1) AS BD,"
                + " (SELECT COUNT(*) FROM LOG_TASK T WHERE T.LOG_DATE=L.LOG_DATE AND T.SHOULD_EXECUTE=1 AND T.ABNORMAL=1) AS AB,"
                + " (SELECT COUNT(*) FROM LOG_RECORD R WHERE R.LOG_DATE=L.LOG_DATE) AS RC"
                + " FROM DAILY_LOG L WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (year != null) { sql.append(" AND YEAR(L.LOG_DATE)=?"); params.add(year); }
        if (month != null) { sql.append(" AND MONTH(L.LOG_DATE)=?"); params.add(month); }
        if (status != null) { sql.append(" AND L.STATUS=?"); params.add(status.key()); }
        sql.append(" ORDER BY L.LOG_DATE DESC");
        List<HistoryRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            Jdbc.bind(ps, params.toArray());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HistoryRow h = new HistoryRow();
                    h.date = Jdbc.getDate(rs, "LOG_DATE");
                    h.weekday = rs.getString("WEEKDAY");
                    h.status = ReportStatus.fromKey(rs.getString("STATUS"));
                    h.jobError = rs.getInt("JE");
                    h.batchTotal = rs.getInt("BT");
                    h.batchDone = rs.getInt("BD");
                    h.abnormal = rs.getInt("AB");
                    h.records = rs.getInt("RC");
                    out.add(h);
                }
            }
        }
        return out;
    }

    public static Stats stats(Connection c) throws SQLException {
        Stats s = new Stats();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) AS D, COALESCE(SUM(JOB_ERR_DAY + JOB_ERR_EVENING + JOB_ERR_NIGHT),0) AS JE FROM DAILY_LOG");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            s.days = rs.getInt("D");
            s.jobError = rs.getInt("JE");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(ABNORMAL),0) AS AB, COALESCE(SUM(FORCED),0) AS FO FROM LOG_TASK");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            s.abnormal = rs.getInt("AB");
            s.forced = rs.getInt("FO");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM LOG_RECORD");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            s.records = rs.getInt(1);
        }
        return s;
    }

    public static List<LocalDate> allDates(Connection c) throws SQLException {
        List<LocalDate> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT LOG_DATE FROM DAILY_LOG ORDER BY LOG_DATE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(Jdbc.getDate(rs, "LOG_DATE"));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static String user(Signature s) { return s == null ? null : s.user; }
    private static LocalDateTime time(Signature s) { return s == null ? null : s.time; }
    private static String key(Shift s) { return s == null ? null : s.key(); }
    private static int nz(Integer i) { return i == null ? 0 : i; }
}
