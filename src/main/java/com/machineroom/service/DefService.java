package com.machineroom.service;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.machineroom.auth.UserPrincipal;
import com.machineroom.dao.AuditDao;
import com.machineroom.dao.DailyLogDao;
import com.machineroom.dao.DefDao;
import com.machineroom.db.Db;
import com.machineroom.model.CheckDef;
import com.machineroom.model.CheckItem;
import com.machineroom.model.CheckType;
import com.machineroom.model.DailyLog;
import com.machineroom.model.EquipDef;
import com.machineroom.model.EquipItem;
import com.machineroom.model.EquipType;
import com.machineroom.model.ScheduleType;
import com.machineroom.model.Shift;
import com.machineroom.model.Task;
import com.machineroom.model.TaskDef;

/** 項目管理: definition CRUD (administrators) and "apply changes to today's log". */
public final class DefService {

    private DefService() {
    }

    /** All three definition lists. */
    public static final class Defs {
        public List<TaskDef> tasks;
        public List<EquipDef> equips;
        public List<CheckDef> checks;
    }

    public static Defs all() {
        return Db.tx(c -> {
            Defs d = new Defs();
            d.tasks = DefDao.tasks(c);
            d.equips = DefDao.equips(c);
            d.checks = DefDao.checks(c);
            return d;
        });
    }

    private static void requireAdmin(UserPrincipal p) {
        if (!p.isAdmin()) throw ServiceException.forbidden("只有管理者可以修改項目定義");
    }

    private static String req(String v, String label, int max) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) throw ServiceException.badRequest(label + "必填");
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String optTime(String v) {
        String s = v == null ? "" : v.trim();
        if (!LogicalDate.isValidTime(s)) throw ServiceException.badRequest("時間格式須為 HH:mm");
        return s;
    }

    private static Shift reqShift(String key) {
        Shift s = Shift.fromKey(key);
        if (s == null) throw ServiceException.badRequest("班別不正確");
        return s;
    }

    // ------------------------------------------------------------------ tasks

    private static void validateTask(TaskDef t) {
        t.code = req(t.code, "代號", 16);
        t.name = req(t.name, "名稱", 200);
        if (ScheduleType.fromKey(t.schedule) == null) throw ServiceException.badRequest("條件不正確");
        t.plannedStart = optTime(t.plannedStart);
        if (t.plannedStart.isEmpty()) t.plannedStart = "00:00";
        if (!Shift.CROSS_KEY.equals(t.shift) && Shift.fromKey(t.shift) == null) throw ServiceException.badRequest("班別不正確");
        if (t.qtyLabel != null && t.qtyLabel.trim().isEmpty()) t.qtyLabel = null;
    }

    public static Defs createTask(final UserPrincipal p, final TaskDef t) {
        requireAdmin(p);
        validateTask(t);
        Db.tx(c -> {
            if (DefDao.task(c, t.code) != null) throw ServiceException.conflict("代號重複：" + t.code);
            t.seq = DefDao.nextSeq(c, "DEF_TASK");
            t.enabled = true;
            DefDao.insertTask(c, t);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_TASK_ADD", null, t.code + " " + t.name);
            return null;
        });
        return all();
    }

    public static Defs updateTask(final UserPrincipal p, final String code, final TaskDef t) {
        requireAdmin(p);
        Db.tx(c -> {
            TaskDef old = DefDao.task(c, code);
            if (old == null) throw ServiceException.notFound("找不到 " + code);
            if (t.code == null || t.code.trim().isEmpty()) t.code = old.code;
            validateTask(t);
            if (!t.code.equals(old.code) && DefDao.task(c, t.code) != null) throw ServiceException.conflict("代號重複：" + t.code);
            t.seq = old.seq;
            if (!t.enabledSpecified) t.enabled = old.enabled;      // the UI toggles enabled via PUT
            DefDao.updateTask(c, old.code, t);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_TASK_UPDATE", null, code + " → " + t.code + " " + t.name);
            return null;
        });
        return all();
    }

    public static Defs deleteTask(final UserPrincipal p, final String code) {
        requireAdmin(p);
        Db.tx(c -> {
            if (DefDao.task(c, code) == null) throw ServiceException.notFound("找不到 " + code);
            DefDao.deleteTask(c, code);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_TASK_DELETE", null, code);
            return null;
        });
        return all();
    }

    // ------------------------------------------------------------------ equipment

    private static void validateEquip(EquipDef e) {
        e.name = req(e.name, "名稱", 200);
        if (EquipType.fromKey(e.type) == null) throw ServiceException.badRequest("類型不正確");
        e.shift = reqShift(e.shift == null ? null : e.shift.key());
        e.time = optTime(e.time);
        if (EquipType.STATUS.key().equals(e.type)) {
            if (e.statusOptions == null || e.statusOptions.isEmpty()) e.statusOptions = DefDao.defaultStatusOptions();
        } else {
            e.statusOptions = new ArrayList<>();
        }
    }

    public static Defs createEquip(final UserPrincipal p, final EquipDef e) {
        requireAdmin(p);
        validateEquip(e);
        Db.tx(c -> {
            if (e.id == null || e.id.trim().isEmpty()) e.id = "custom_" + System.currentTimeMillis();
            e.id = e.id.trim();
            if (DefDao.equip(c, e.id) != null) throw ServiceException.conflict("代號重複：" + e.id);
            e.seq = DefDao.nextSeq(c, "DEF_EQUIP");
            e.enabled = true;
            DefDao.insertEquip(c, e);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_EQUIP_ADD", null, e.id + " " + e.name);
            return null;
        });
        return all();
    }

    public static Defs updateEquip(final UserPrincipal p, final String id, final EquipDef e) {
        requireAdmin(p);
        Db.tx(c -> {
            EquipDef old = DefDao.equip(c, id);
            if (old == null) throw ServiceException.notFound("找不到 " + id);
            e.id = old.id;
            e.seq = old.seq;
            e.enabled = old.enabled;
            validateEquip(e);
            DefDao.updateEquip(c, e);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_EQUIP_UPDATE", null, id + " " + e.name);
            return null;
        });
        return all();
    }

    public static Defs toggleEquip(final UserPrincipal p, final String id) {
        requireAdmin(p);
        Db.tx(c -> {
            EquipDef old = DefDao.equip(c, id);
            if (old == null) throw ServiceException.notFound("找不到 " + id);
            DefDao.setEquipEnabled(c, id, !old.enabled);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_EQUIP_TOGGLE", null, id + " enabled=" + !old.enabled);
            return null;
        });
        return all();
    }

    // ------------------------------------------------------------------ checks

    private static void validateCheck(CheckDef d) {
        d.name = req(d.name, "名稱", 200);
        if (CheckType.fromKey(d.type) == null) throw ServiceException.badRequest("類型不正確");
        d.shift = reqShift(d.shift == null ? null : d.shift.key());
        d.time = optTime(d.time);
    }

    public static Defs createCheck(final UserPrincipal p, final CheckDef d) {
        requireAdmin(p);
        validateCheck(d);
        Db.tx(c -> {
            if (d.id == null || d.id.trim().isEmpty()) d.id = "custom_sc_" + System.currentTimeMillis();
            d.id = d.id.trim();
            if (DefDao.check(c, d.id) != null) throw ServiceException.conflict("代號重複：" + d.id);
            d.seq = DefDao.nextSeq(c, "DEF_CHECK");
            d.enabled = true;
            DefDao.insertCheck(c, d);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_CHECK_ADD", null, d.id + " " + d.name);
            return null;
        });
        return all();
    }

    public static Defs updateCheck(final UserPrincipal p, final String id, final CheckDef d) {
        requireAdmin(p);
        Db.tx(c -> {
            CheckDef old = DefDao.check(c, id);
            if (old == null) throw ServiceException.notFound("找不到 " + id);
            d.id = old.id;
            d.seq = old.seq;
            d.enabled = old.enabled;
            validateCheck(d);
            DefDao.updateCheck(c, d);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_CHECK_UPDATE", null, id + " " + d.name);
            return null;
        });
        return all();
    }

    public static Defs toggleCheck(final UserPrincipal p, final String id) {
        requireAdmin(p);
        Db.tx(c -> {
            CheckDef old = DefDao.check(c, id);
            if (old == null) throw ServiceException.notFound("找不到 " + id);
            DefDao.setCheckEnabled(c, id, !old.enabled);
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_CHECK_TOGGLE", null, id + " enabled=" + !old.enabled);
            return null;
        });
        return all();
    }

    // ------------------------------------------------------------------ apply to today

    /**
     * Rebuilds today's rows of one kind from the current definitions, keeping every row that has
     * already been touched (filled, signed …) — the original "套用變更到今日日誌".
     */
    public static DailyLog applyToday(final UserPrincipal p, final String kind) {
        requireAdmin(p);
        final LocalDate today = LogicalDate.today();
        return Db.tx(c -> {
            DailyLog log = DailyLogDao.load(c, today, true);
            if (log == null) log = LogService.createInTx(c, today, p.getUsername());
            DailyLog fresh = LogFactory.newLog(today, log.dayType, DefDao.tasks(c), DefDao.equips(c), DefDao.checks(c), p.getUsername());
            switch (kind == null ? "" : kind) {
                case "task": {
                    List<Task> merged = new ArrayList<>();
                    for (Task nt : fresh.tasks) {
                        Task old = log.task(nt.code);
                        merged.add(old != null && old.touched() ? old : nt);
                    }
                    for (Task old : log.tasks) {
                        if (old.touched() && !containsTask(merged, old.code)) merged.add(old);
                    }
                    renumberTasks(merged);
                    log.tasks = merged;
                    DailyLogDao.replaceTasks(c, today, merged);
                    break;
                }
                case "equip": {
                    List<EquipItem> merged = new ArrayList<>();
                    for (EquipItem ne : fresh.equip) {
                        EquipItem old = log.equip(ne.defId);
                        merged.add(old != null && old.touched() ? old : ne);
                    }
                    for (EquipItem old : log.equip) {
                        if (old.touched() && !containsEquip(merged, old.defId)) merged.add(old);
                    }
                    for (int i = 0; i < merged.size(); i++) merged.get(i).seq = i + 1;
                    log.equip = merged;
                    DailyLogDao.replaceEquip(c, today, merged);
                    break;
                }
                case "check": {
                    Map<Shift, List<CheckItem>> mergedAll = new EnumMap<>(Shift.class);
                    for (Shift s : Shift.values()) {
                        List<CheckItem> merged = new ArrayList<>();
                        for (CheckItem ni : fresh.checks.get(s)) {
                            CheckItem old = log.check(s, ni.defId);
                            merged.add(old != null && old.touched() ? old : ni);
                        }
                        for (CheckItem old : log.checks.get(s)) {
                            if (old.touched() && !containsCheck(merged, old.defId)) merged.add(old);
                        }
                        for (int i = 0; i < merged.size(); i++) merged.get(i).seq = i + 1;
                        mergedAll.put(s, merged);
                    }
                    log.checks = mergedAll;
                    DailyLogDao.replaceChecks(c, today, mergedAll);
                    break;
                }
                default:
                    throw ServiceException.badRequest("kind 須為 task / equip / check");
            }
            DailyLogDao.touch(c, log, p.getUsername());
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "DEF_APPLY_TODAY", today, kind);
            return log;
        });
    }

    private static void renumberTasks(List<Task> tasks) {
        for (int i = 0; i < tasks.size(); i++) tasks.get(i).seq = i + 1;
    }

    private static boolean containsTask(List<Task> l, String code) {
        for (Task t : l) if (t.code.equalsIgnoreCase(code)) return true;
        return false;
    }

    private static boolean containsEquip(List<EquipItem> l, String id) {
        for (EquipItem e : l) if (e.defId.equals(id)) return true;
        return false;
    }

    private static boolean containsCheck(List<CheckItem> l, String id) {
        for (CheckItem ci : l) if (ci.defId.equals(id)) return true;
        return false;
    }
}
