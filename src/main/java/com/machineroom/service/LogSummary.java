package com.machineroom.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.machineroom.model.CheckItem;
import com.machineroom.model.DailyLog;
import com.machineroom.model.DayType;
import com.machineroom.model.EquipItem;
import com.machineroom.model.ImportantRecord;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.Shift;
import com.machineroom.model.Task;

/** Derived numbers shown in the UI: section progress, the summary bar and the marquee reminders. */
public final class LogSummary {

    /** done / total pair. */
    public static final class Progress {
        public final int done;
        public final int total;

        Progress(int done, int total) {
            this.done = done;
            this.total = total;
        }
    }

    private LogSummary() {
    }

    /** Keys: docInfo, sysEquip, shiftChecks, batch, joberror, records. */
    public static Map<String, Progress> progress(DailyLog l) {
        Map<String, Progress> m = new LinkedHashMap<>();
        boolean doc = !isEmpty(l.bootUser) && !isEmpty(l.bootTime);
        m.put("docInfo", new Progress(doc ? 1 : 0, 1));

        int eqTotal = 0, eqDone = 0;
        for (EquipItem e : l.equip) {
            if (!e.isActive()) continue;
            eqTotal++;
            if (e.opSign != null) eqDone++;
        }
        m.put("sysEquip", new Progress(eqDone, eqTotal));

        int scTotal = 0, scDone = 0;
        for (Shift s : Shift.values()) {
            for (CheckItem c : l.checks.get(s)) {
                scTotal++;
                if (c.opSign != null) scDone++;
            }
        }
        m.put("shiftChecks", new Progress(scDone, scTotal));

        int bt = 0, bd = 0;
        for (Task t : l.tasks) {
            if (!t.shouldExecute) continue;
            bt++;
            if (t.done) bd++;
        }
        m.put("batch", new Progress(bd, bt));
        m.put("joberror", new Progress(3, 3));          // job error counts default to 0 → always "filled"
        m.put("records", new Progress(l.records.size(), l.records.size()));
        return m;
    }

    /** batchDone, batchTotal, delayed, abnormal, events */
    public static Map<String, Integer> summary(DailyLog l, Permissions p) {
        int total = 0, done = 0, delayed = 0, abnormal = 0;
        for (Task t : l.tasks) {
            if (!t.shouldExecute) continue;
            total++;
            if (t.done) done++;
            if (t.abnormal) abnormal++;
            if (p.isDelayed(t)) delayed++;
        }
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("batchDone", done);
        m.put("batchTotal", total);
        m.put("delayed", delayed);
        m.put("abnormal", abnormal);
        m.put("events", l.records.size());
        return m;
    }

    /** Marquee messages for the viewing user (the original {@code updateMarquee}). */
    public static List<String> reminders(DailyLog l, Permissions p, int pendingForReviewer) {
        List<String> msgs = new ArrayList<>();
        if (l.dayType == DayType.TYPHOON) msgs.add("🌀 颱風日");
        if (p.isReviewer()) {
            if (pendingForReviewer > 0) msgs.add("📨 待審日誌：" + pendingForReviewer + " 份");
            return msgs;
        }
        Shift my = p.shift();
        if (p.isCurrentLogicalDay() && my != null) {
            if (l.bootOpSign == null && my == Shift.NIGHT) msgs.add("🔑 開機資訊未填");
            for (EquipItem e : l.equip) {
                if (e.shift != my || !e.isActive()) continue;
                if (e.opSign == null) msgs.add("🖥️ " + e.defName + " 未填");
            }
            List<String> undone = new ArrayList<>();
            for (CheckItem c : l.checks.get(my)) {
                if (c.opSign == null && !(c.holidaySkip && l.dayType != DayType.BUSINESS)) undone.add(c.name);
            }
            if (!undone.isEmpty()) msgs.add("📋 本班檢查未填：" + String.join("、", undone));
            for (Task t : l.tasks) {
                if (!t.shouldExecute || t.done || !t.editableBy(my)) continue;
                msgs.add(p.isDelayed(t) ? "🔴 " + t.code + " 延遲" : "🟡 " + t.code + " 待完成");
            }
            if (l.allShiftsSubmitted() && l.status == ReportStatus.DRAFT) msgs.add("📨 3班皆完成，可整合送簽");
        } else if (l.allShiftsSubmitted() && l.status == ReportStatus.DRAFT) {
            msgs.add("📨 " + l.date + " 3 班皆送出，等待整合送簽");
        }
        return msgs;
    }

    /** Validation for 本班送出 (the original {@code submitShift}). Empty list = OK. */
    public static List<String> shiftSubmitErrors(DailyLog l, Shift s) {
        List<String> errs = new ArrayList<>();
        List<String> undone = new ArrayList<>();
        for (Task t : l.tasks) {
            if (t.shouldExecute && t.belongsTo(s) && !t.done) undone.add(t.code);
        }
        if (!undone.isEmpty()) errs.add("批次作業未完成：" + String.join(", ", undone));
        for (EquipItem e : l.equip) {
            if (e.shift != s || !e.isActive()) continue;
            if (e.opSign == null) errs.add("系統設備「" + e.defName + "」未簽章");
        }
        for (CheckItem c : l.checks.get(s)) {
            if (c.holidaySkip && l.dayType != DayType.BUSINESS) continue;
            if (c.opSign == null) errs.add("三班檢查「" + c.name + "」未簽章");
        }
        if (l.jobError.get(s) == null) errs.add("JOB ERROR 未填寫");
        for (ImportantRecord r : l.records) {
            if (ImportantRecord.SRC_JOBERROR.equals(r.source) && r.shift == s && isEmpty(r.recoverTime)) {
                errs.add("重要記錄事項「" + recordTitle(r) + "」復原時間未填");
            }
        }
        return errs;
    }

    /** Validation for 整合送簽 (the original {@code submitAll}). */
    public static List<String> submitAllErrors(DailyLog l) {
        List<String> errs = new ArrayList<>();
        for (Shift s : Shift.values()) {
            Integer je = l.jobError.get(s);
            if (je != null && je > 0) {
                boolean has = false;
                for (ImportantRecord r : l.records) {
                    if (ImportantRecord.SRC_JOBERROR.equals(r.source) && r.shift == s) { has = true; break; }
                }
                if (!has) errs.add(s.label() + " JOB ERROR " + je + " 支，但無對應事件紀錄");
            }
        }
        for (ImportantRecord r : l.records) {
            if (isEmpty(r.recoverTime)) errs.add("重要記錄事項「" + recordTitle(r) + "」復原時間未填");
        }
        return errs;
    }

    private static String recordTitle(ImportantRecord r) {
        if (!isEmpty(r.taskCode)) return r.taskCode;
        String d = r.description == null ? "" : r.description;
        return d.length() > 20 ? d.substring(0, 20) : d;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
