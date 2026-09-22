package com.machineroom.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.machineroom.dao.DailyLogDao.DateSummary;
import com.machineroom.dao.DailyLogDao.HistoryRow;
import com.machineroom.dao.DailyLogDao.Stats;
import com.machineroom.model.CheckDef;
import com.machineroom.model.CheckItem;
import com.machineroom.model.CheckType;
import com.machineroom.model.DailyLog;
import com.machineroom.model.DayType;
import com.machineroom.model.EquipDef;
import com.machineroom.model.EquipItem;
import com.machineroom.model.EquipType;
import com.machineroom.model.ImportantRecord;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.ReviewComment;
import com.machineroom.model.ScheduleType;
import com.machineroom.model.Shift;
import com.machineroom.model.Signature;
import com.machineroom.model.Task;
import com.machineroom.model.TaskDef;
import com.machineroom.service.DefService;
import com.machineroom.service.LogSummary;
import com.machineroom.service.LogicalDate;
import com.machineroom.service.Permissions;
import com.machineroom.service.ServiceException;

/** JSON (Gson tree) mapping that matches docs/API.md exactly. */
public final class LogJson {

    private LogJson() {
    }

    // ------------------------------------------------------------------ primitives

    public static JsonElement sig(Signature s) {
        if (s == null) return JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.addProperty("user", s.user);
        o.addProperty("time", LogicalDate.fmt(s.time));
        o.addProperty("integratorEdit", s.integratorEdit);
        return o;
    }

    private static Signature sigFrom(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        String user = str(o, "user");
        if (user == null || user.isEmpty()) return null;
        return new Signature(user, parseTs(str(o, "time")), bool(o, "integratorEdit", false));
    }

    /** Accepts ISO local date-time (with or without millis) and ISO instant ("…Z"), returns local time. */
    public static LocalDateTime parseTs(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.endsWith("Z")) return java.time.Instant.parse(s).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public static String strOrEmpty(JsonObject o, String key) {
        String s = str(o, key);
        return s == null ? "" : s;
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        String s = e.getAsString();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    public static int intVal(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try {
            String s = e.getAsString().trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            throw ServiceException.badRequest(key + " 須為整數");
        }
    }

    private static JsonObject obj(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arr(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    // ------------------------------------------------------------------ daily log → JSON

    /** Full log. {@code perm} may be null (export): perm / progress / reminders are then omitted. */
    public static JsonObject toJson(DailyLog l, Permissions perm, int pendingForReviewer) {
        JsonObject o = new JsonObject();
        o.addProperty("date", l.date.toString());
        o.addProperty("weekday", l.weekday);
        o.addProperty("solarDay", l.solarDay);
        o.addProperty("dayType", l.dayType.key());
        o.addProperty("dayTypeLabel", l.dayType.label());
        o.addProperty("dayTypeOverride", l.dayTypeOverride);
        o.addProperty("dayTypeReason", l.dayTypeReason);
        o.addProperty("dayTypeChangedBy", l.dayTypeChangedBy);
        o.addProperty("bootUser", l.bootUser);
        o.addProperty("bootTime", l.bootTime);
        o.add("bootOpSign", sig(l.bootOpSign));
        o.add("bootReviewerSign", sig(l.bootReviewerSign));

        JsonObject ss = new JsonObject(), sub = new JsonObject(), je = new JsonObject();
        for (Shift s : Shift.values()) {
            ss.addProperty(s.key(), l.shiftStatus.get(s));
            sub.add(s.key(), sig(l.shiftSubmits.get(s)));
            je.addProperty(s.key(), l.jobError.get(s) == null ? 0 : l.jobError.get(s));
        }
        o.add("shiftStatus", ss);
        o.add("shiftSubmits", sub);
        o.addProperty("status", l.status.key());
        o.addProperty("statusLabel", l.status.label());
        o.addProperty("unlockReason", l.unlockReason);
        o.addProperty("versions", l.versions);
        JsonObject ap = new JsonObject();
        ap.add("operator", sig(l.approvalOperator));
        ap.add("deputy", sig(l.approvalDeputy));
        ap.add("chief", sig(l.approvalChief));
        o.add("approval", ap);
        JsonArray comments = new JsonArray();
        for (ReviewComment rc : l.reviewComments) {
            JsonObject c = new JsonObject();
            c.addProperty("by", rc.by);
            c.addProperty("role", rc.role);
            c.addProperty("time", LogicalDate.fmt(rc.time));
            c.addProperty("text", rc.text);
            comments.add(c);
        }
        o.add("reviewComments", comments);
        o.add("jobError", je);

        JsonArray equip = new JsonArray();
        for (EquipItem e : l.equip) equip.add(equipJson(e, perm));
        o.add("equip", equip);

        JsonObject checks = new JsonObject();
        for (Shift s : Shift.values()) {
            JsonArray a = new JsonArray();
            for (CheckItem ci : l.checks.get(s)) a.add(checkJson(ci, perm));
            checks.add(s.key(), a);
        }
        o.add("checks", checks);

        JsonArray tasks = new JsonArray();
        for (Task t : l.tasks) tasks.add(taskJson(t, perm));
        o.add("tasks", tasks);

        JsonArray records = new JsonArray();
        for (ImportantRecord r : l.records) records.add(recordJson(r));
        o.add("records", records);

        if (perm != null) {
            o.add("perm", permJson(perm));
            JsonObject prog = new JsonObject();
            for (Map.Entry<String, LogSummary.Progress> en : LogSummary.progress(l).entrySet()) {
                JsonObject p = new JsonObject();
                p.addProperty("done", en.getValue().done);
                p.addProperty("total", en.getValue().total);
                prog.add(en.getKey(), p);
            }
            o.add("progress", prog);
            JsonObject sum = new JsonObject();
            for (Map.Entry<String, Integer> en : LogSummary.summary(l, perm).entrySet()) sum.addProperty(en.getKey(), en.getValue());
            o.add("summary", sum);
            JsonArray rem = new JsonArray();
            for (String m : LogSummary.reminders(l, perm, pendingForReviewer)) rem.add(m);
            o.add("reminders", rem);
        }
        o.addProperty("updatedAt", LogicalDate.fmtMs(l.updatedAt));
        o.addProperty("updatedBy", l.updatedBy);
        return o;
    }

    private static JsonObject equipJson(EquipItem e, Permissions perm) {
        JsonObject o = new JsonObject();
        o.addProperty("defId", e.defId);
        o.addProperty("defName", e.defName);
        o.addProperty("type", e.type);
        o.addProperty("shift", e.shift.key());
        o.addProperty("time", e.time);
        o.addProperty("status", e.status);
        JsonArray opts = new JsonArray();
        for (String s : e.statusOptions) opts.add(s);
        o.add("statusOptions", opts);
        o.addProperty("count", e.count);
        o.addProperty("notify", e.notify);
        o.addProperty("enabled", e.enabled);
        o.addProperty("enableReason", e.enableReason);
        o.add("opSign", sig(e.opSign));
        o.add("reviewerSign", sig(e.reviewerSign));
        o.addProperty("filled", e.isFilled());
        if (perm != null) {
            JsonObject p = new JsonObject();
            p.addProperty("editable", perm.canEditEquip(e));
            p.addProperty("signable", perm.canSignEquip(e));
            p.addProperty("reviewable", perm.canReviewEquip(e));
            p.addProperty("canUnsign", perm.canUnsignEquip(e));
            p.addProperty("canUnreview", perm.canUnreviewEquip(e));
            p.addProperty("canToggle", perm.canToggleIms(e));
            o.add("perm", p);
        }
        return o;
    }

    private static JsonObject checkJson(CheckItem c, Permissions perm) {
        JsonObject o = new JsonObject();
        o.addProperty("defId", c.defId);
        o.addProperty("name", c.name);
        o.addProperty("type", c.type);
        o.addProperty("shift", c.shift.key());
        o.addProperty("time", c.time);
        o.addProperty("status", c.status);
        o.addProperty("entryLog", c.entryLog);
        o.addProperty("holidaySkip", c.holidaySkip);
        JsonObject vals = new JsonObject();
        for (Map.Entry<String, String> en : c.values.entrySet()) vals.addProperty(en.getKey(), en.getValue());
        o.add("values", vals);
        o.add("opSign", sig(c.opSign));
        o.add("reviewerSign", sig(c.reviewerSign));
        o.addProperty("filled", c.isFilled());
        if (perm != null) {
            JsonObject p = new JsonObject();
            p.addProperty("editable", perm.canEditCheck(c));
            p.addProperty("signable", perm.canSignCheck(c));
            p.addProperty("reviewable", perm.canReviewCheck(c));
            p.addProperty("canUnsign", perm.canUnsignCheck(c));
            p.addProperty("canUnreview", perm.canUnreviewCheck(c));
            o.add("perm", p);
        }
        return o;
    }

    private static JsonObject taskJson(Task t, Permissions perm) {
        JsonObject o = new JsonObject();
        o.addProperty("code", t.code);
        o.addProperty("name", t.name);
        o.addProperty("schedule", t.schedule);
        o.addProperty("scheduleLabel", ScheduleType.labelOf(t.schedule));
        o.addProperty("plannedStart", t.plannedStart);
        o.addProperty("shift", t.shift);
        o.addProperty("hasEnd", t.hasEnd);
        o.addProperty("qtyLabel", t.qtyLabel);
        o.addProperty("shouldExecute", t.shouldExecute);
        o.addProperty("assignedShift", t.assignedShift == null ? null : t.assignedShift.key());
        o.addProperty("forced", t.forced);
        o.addProperty("forceReason", t.forceReason);
        o.addProperty("forcedBy", t.forcedBy);
        o.addProperty("handoverFrom", t.handoverFrom == null ? null : t.handoverFrom.key());
        o.addProperty("handoverEndShift", t.handoverEndShift == null ? null : t.handoverEndShift.key());
        o.addProperty("done", t.done);
        o.addProperty("abnormal", t.abnormal);
        o.addProperty("startTime", t.startTime);
        o.addProperty("endTime", t.endTime);
        o.addProperty("qtyValue", t.qtyValue);
        o.addProperty("remark", t.remark);
        o.add("opSign", sig(t.opSign));
        o.add("reviewerSign", sig(t.reviewerSign));
        if (perm != null) {
            o.addProperty("delayed", perm.isDelayed(t));
            JsonObject p = new JsonObject();
            p.addProperty("editable", perm.canEditTask(t));
            p.addProperty("reviewable", perm.canReviewTask(t));
            p.addProperty("canHandover", perm.canHandoverTask(t));
            p.addProperty("canForce", perm.canForceTask(t));
            o.add("perm", p);
        }
        return o;
    }

    public static JsonObject recordJson(ImportantRecord r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id);
        o.addProperty("seq", r.seq);
        o.addProperty("time", r.time);
        o.addProperty("taskCode", r.taskCode);
        o.addProperty("description", r.description);
        o.addProperty("notifySP", r.notifySP);
        o.addProperty("notifyAP", r.notifyAP);
        o.addProperty("recoverTime", r.recoverTime);
        o.addProperty("ticket", r.ticket);
        o.addProperty("op", r.op);
        o.addProperty("source", r.source);
        o.addProperty("shift", r.shift == null ? null : r.shift.key());
        return o;
    }

    private static JsonObject permJson(Permissions p) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", p.mode());
        o.addProperty("isCurrentLogicalDay", p.isCurrentLogicalDay());
        o.addProperty("isIntegrator", p.isIntegrator());
        o.addProperty("canChangeDayType", p.canChangeDayType());
        o.addProperty("canEditBoot", p.canEditBoot());
        o.addProperty("canSignBoot", p.canSignBoot());
        o.addProperty("canReviewBoot", p.canReviewBoot());
        o.addProperty("canSubmitShift", p.canSubmitShift());
        o.addProperty("shiftSubmitted", p.myShiftSubmitted());
        o.addProperty("canSubmitAll", p.canSubmitAll());
        o.addProperty("canRecall", p.canRecall());
        o.addProperty("canApprove", p.canApprove());
        o.addProperty("canReject", p.canReject());
        o.addProperty("canUnlock", p.canUnlock());
        JsonObject je = new JsonObject();
        for (Shift s : Shift.values()) je.addProperty(s.key(), p.canEditJobError(s));
        o.add("canEditJobError", je);
        o.addProperty("canManageRecords", p.canManageRecords());
        return o;
    }

    // ------------------------------------------------------------------ JSON → daily log (import, v3 format)

    public static DailyLog fromJson(JsonObject o) {
        DailyLog l = new DailyLog();
        l.date = LogicalDate.parse(str(o, "date"));
        l.weekday = LogicalDate.weekday(l.date);
        l.solarDay = LogicalDate.solarDay(l.date);
        DayType dt = DayType.fromKey(str(o, "dayType"));
        l.dayType = dt == null ? DayType.defaultFor(l.date) : dt;
        l.dayTypeOverride = bool(o, "dayTypeOverride", false);
        l.dayTypeReason = strOrEmpty(o, "dayTypeReason");
        l.dayTypeChangedBy = strOrEmpty(o, "dayTypeChangedBy");
        l.bootUser = strOrEmpty(o, "bootUser");
        l.bootTime = strOrEmpty(o, "bootTime");
        l.bootOpSign = sigFrom(o.get("bootOpSign"));
        l.bootReviewerSign = sigFrom(o.get("bootReviewerSign"));
        JsonObject ss = obj(o, "shiftStatus"), sub = obj(o, "shiftSubmits"), je = obj(o, "jobError");
        for (Shift s : Shift.values()) {
            String st = str(ss, s.key());
            l.shiftStatus.put(s, DailyLog.SHIFT_SUBMITTED.equals(st) ? DailyLog.SHIFT_SUBMITTED : DailyLog.SHIFT_DRAFT);
            l.shiftSubmits.put(s, sigFrom(sub.get(s.key())));
            l.jobError.put(s, intVal(je, s.key(), 0));
        }
        ReportStatus rs = ReportStatus.fromKey(str(o, "status"));
        l.status = rs == null ? ReportStatus.DRAFT : rs;
        l.unlockReason = strOrEmpty(o, "unlockReason");
        l.versions = Math.max(1, intVal(o, "versions", 1));
        JsonObject ap = obj(o, "approval");
        l.approvalOperator = sigFrom(ap.get("operator"));
        l.approvalDeputy = sigFrom(ap.get("deputy"));
        l.approvalChief = sigFrom(ap.get("chief"));
        for (JsonElement e : arr(o, "reviewComments")) {
            JsonObject c = e.getAsJsonObject();
            l.reviewComments.add(new ReviewComment(strOrEmpty(c, "by"), strOrEmpty(c, "role"), parseTs(str(c, "time")), strOrEmpty(c, "text")));
        }
        int seq = 0;
        for (JsonElement e : arr(o, "equip")) {
            JsonObject x = e.getAsJsonObject();
            EquipItem it = new EquipItem();
            it.defId = strOrEmpty(x, "defId");
            it.seq = ++seq;
            it.defName = strOrEmpty(x, "defName");
            it.type = strOrEmpty(x, "type");
            it.shift = Shift.fromKey(str(x, "shift"));
            if (it.defId.isEmpty() || it.shift == null || EquipType.fromKey(it.type) == null) continue;
            it.time = strOrEmpty(x, "time");
            it.status = strOrEmpty(x, "status");
            for (JsonElement opt : arr(x, "statusOptions")) it.statusOptions.add(opt.getAsString());
            it.count = strOrEmpty(x, "count");
            it.notify = strOrEmpty(x, "notify");
            it.enabled = bool(x, "enabled", !it.isIms());
            it.enableReason = strOrEmpty(x, "enableReason");
            it.opSign = sigFrom(x.get("opSign"));
            it.reviewerSign = sigFrom(x.get("reviewerSign"));
            l.equip.add(it);
        }
        JsonObject checks = obj(o, "checks");
        for (Shift s : Shift.values()) {
            int cs = 0;
            for (JsonElement e : arr(checks, s.key())) {
                JsonObject x = e.getAsJsonObject();
                CheckItem ci = new CheckItem();
                ci.defId = strOrEmpty(x, "defId");
                ci.seq = ++cs;
                ci.name = strOrEmpty(x, "name");
                ci.type = strOrEmpty(x, "type");
                ci.shift = s;
                if (ci.defId.isEmpty() || CheckType.fromKey(ci.type) == null) continue;
                ci.time = strOrEmpty(x, "time");
                ci.status = strOrEmpty(x, "status");
                ci.entryLog = strOrEmpty(x, "entryLog");
                ci.holidaySkip = bool(x, "holidaySkip", false);
                JsonObject vals = obj(x, "values");
                for (String k : ci.checkType().valueKeys()) ci.values.put(k, strOrEmpty(vals, k));
                ci.opSign = sigFrom(x.get("opSign"));
                ci.reviewerSign = sigFrom(x.get("reviewerSign"));
                l.checks.get(s).add(ci);
            }
        }
        int ts = 0;
        for (JsonElement e : arr(o, "tasks")) {
            JsonObject x = e.getAsJsonObject();
            Task t = new Task();
            t.code = strOrEmpty(x, "code");
            if (t.code.isEmpty()) continue;
            t.seq = ++ts;
            t.name = strOrEmpty(x, "name");
            t.schedule = strOrEmpty(x, "schedule");
            t.plannedStart = strOrEmpty(x, "plannedStart");
            t.shift = strOrEmpty(x, "shift");
            t.hasEnd = bool(x, "hasEnd", false);
            t.qtyLabel = str(x, "qtyLabel");
            t.shouldExecute = bool(x, "shouldExecute", true);
            t.assignedShift = Shift.fromKey(str(x, "assignedShift"));
            if (t.assignedShift == null) t.assignedShift = t.isCross() ? Shift.NIGHT : Shift.DAY;
            t.forced = bool(x, "forced", false);
            t.forceReason = strOrEmpty(x, "forceReason");
            t.forcedBy = strOrEmpty(x, "forcedBy");
            t.handoverFrom = Shift.fromKey(str(x, "handoverFrom"));
            t.handoverEndShift = Shift.fromKey(str(x, "handoverEndShift"));
            if (t.isCross() && t.handoverEndShift == null) t.handoverEndShift = Shift.DAY;
            t.done = bool(x, "done", false);
            t.abnormal = bool(x, "abnormal", false);
            t.startTime = strOrEmpty(x, "startTime");
            t.endTime = strOrEmpty(x, "endTime");
            t.qtyValue = strOrEmpty(x, "qtyValue");
            t.remark = strOrEmpty(x, "remark");
            t.opSign = sigFrom(x.get("opSign"));
            t.reviewerSign = sigFrom(x.get("reviewerSign"));
            l.tasks.add(t);
        }
        int rs2 = 0;
        for (JsonElement e : arr(o, "records")) {
            ImportantRecord r = recordFrom(e.getAsJsonObject(), true);
            r.seq = ++rs2;
            r.op = strOrEmpty(e.getAsJsonObject(), "op");
            if (r.op.isEmpty()) r.op = "import";
            l.records.add(r);
        }
        return l;
    }

    /** Record from an API body. With {@code create=false} absent fields stay null (= unchanged). */
    public static ImportantRecord recordFrom(JsonObject o, boolean create) {
        ImportantRecord r = new ImportantRecord();
        r.time = create ? strOrEmpty(o, "time") : str(o, "time");
        r.taskCode = create ? strOrEmpty(o, "taskCode") : str(o, "taskCode");
        r.description = create ? strOrEmpty(o, "description") : str(o, "description");
        r.notifySP = create ? strOrEmpty(o, "notifySP") : str(o, "notifySP");
        r.notifyAP = create ? strOrEmpty(o, "notifyAP") : str(o, "notifyAP");
        r.recoverTime = create ? strOrEmpty(o, "recoverTime") : str(o, "recoverTime");
        r.ticket = create ? strOrEmpty(o, "ticket") : str(o, "ticket");
        String src = str(o, "source");
        r.source = ImportantRecord.validSource(src) ? src : ImportantRecord.SRC_MANUAL;
        r.shift = Shift.fromKey(str(o, "shift"));
        return r;
    }

    // ------------------------------------------------------------------ definitions

    public static JsonObject defsJson(DefService.Defs d) {
        JsonObject o = new JsonObject();
        JsonArray tasks = new JsonArray();
        for (TaskDef t : d.tasks) {
            JsonObject x = new JsonObject();
            x.addProperty("code", t.code);
            x.addProperty("name", t.name);
            x.addProperty("schedule", t.schedule);
            x.addProperty("plannedStart", t.plannedStart);
            x.addProperty("shift", t.shift);
            x.addProperty("hasEnd", t.hasEnd);
            x.addProperty("qtyLabel", t.qtyLabel);
            x.addProperty("enabled", t.enabled);
            x.addProperty("seq", t.seq);
            tasks.add(x);
        }
        o.add("taskDefs", tasks);
        JsonArray equips = new JsonArray();
        for (EquipDef e : d.equips) {
            JsonObject x = new JsonObject();
            x.addProperty("id", e.id);
            x.addProperty("name", e.name);
            x.addProperty("type", e.type);
            x.addProperty("shift", e.shift.key());
            x.addProperty("time", e.time);
            JsonArray opts = new JsonArray();
            for (String s : e.statusOptions) opts.add(s);
            x.add("statusOptions", opts);
            x.addProperty("enabled", e.enabled);
            x.addProperty("seq", e.seq);
            equips.add(x);
        }
        o.add("equipDefs", equips);
        JsonArray checks = new JsonArray();
        for (CheckDef c : d.checks) {
            JsonObject x = new JsonObject();
            x.addProperty("id", c.id);
            x.addProperty("name", c.name);
            x.addProperty("type", c.type);
            x.addProperty("shift", c.shift.key());
            x.addProperty("time", c.time);
            x.addProperty("holidaySkip", c.holidaySkip);
            x.addProperty("enabled", c.enabled);
            x.addProperty("seq", c.seq);
            checks.add(x);
        }
        o.add("checkDefs", checks);

        JsonObject labels = new JsonObject();
        JsonObject sched = new JsonObject();
        for (ScheduleType s : ScheduleType.values()) sched.addProperty(s.key(), s.label());
        labels.add("schedule", sched);
        JsonObject et = new JsonObject();
        for (EquipType t : EquipType.values()) et.addProperty(t.key(), t.label());
        labels.add("equipType", et);
        JsonObject ct = new JsonObject();
        for (CheckType t : CheckType.values()) ct.addProperty(t.key(), t.label());
        labels.add("checkType", ct);
        o.add("labels", labels);
        return o;
    }

    public static TaskDef taskDefFrom(JsonObject o) {
        TaskDef t = new TaskDef();
        t.code = str(o, "newCode") != null ? str(o, "newCode") : str(o, "code");
        t.name = str(o, "name");
        t.schedule = str(o, "schedule");
        t.plannedStart = str(o, "plannedStart");
        t.shift = str(o, "shift");
        t.hasEnd = bool(o, "hasEnd", false);
        t.qtyLabel = str(o, "qtyLabel");
        t.enabledSpecified = o.has("enabled") && !o.get("enabled").isJsonNull();
        t.enabled = bool(o, "enabled", true);
        return t;
    }

    public static EquipDef equipDefFrom(JsonObject o) {
        EquipDef e = new EquipDef();
        e.id = str(o, "id");
        e.name = str(o, "name");
        e.type = str(o, "type");
        e.shift = Shift.fromKey(str(o, "shift"));
        e.time = str(o, "time");
        JsonElement opts = o.get("statusOptions");
        if (opts != null && opts.isJsonArray()) {
            for (JsonElement x : opts.getAsJsonArray()) {
                String s = x.getAsString().trim();
                if (!s.isEmpty()) e.statusOptions.add(s);
            }
        } else if (opts != null && opts.isJsonPrimitive()) {
            for (String s : opts.getAsString().split("[,，|]")) {
                if (!s.trim().isEmpty()) e.statusOptions.add(s.trim());
            }
        }
        return e;
    }

    public static CheckDef checkDefFrom(JsonObject o) {
        CheckDef c = new CheckDef();
        c.id = str(o, "id");
        c.name = str(o, "name");
        c.type = str(o, "type");
        c.shift = Shift.fromKey(str(o, "shift"));
        c.time = str(o, "time");
        c.holidaySkip = bool(o, "holidaySkip", false);
        return c;
    }

    // ------------------------------------------------------------------ lists

    public static JsonArray datesJson(List<DateSummary> list, LocalDate today) {
        JsonArray a = new JsonArray();
        for (DateSummary s : list) {
            JsonObject o = new JsonObject();
            o.addProperty("date", s.date.toString());
            o.addProperty("weekday", s.weekday);
            o.addProperty("status", s.status.key());
            o.addProperty("statusLabel", s.status.label());
            o.addProperty("isToday", s.date.equals(today));
            JsonObject ss = new JsonObject();
            for (Shift sh : Shift.values()) ss.addProperty(sh.key(), s.shiftStatus.get(sh));
            o.add("shiftStatus", ss);
            a.add(o);
        }
        return a;
    }

    public static JsonArray historyJson(List<HistoryRow> rows) {
        JsonArray a = new JsonArray();
        for (HistoryRow h : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("date", h.date.toString());
            o.addProperty("weekday", h.weekday);
            o.addProperty("status", h.status.key());
            o.addProperty("statusLabel", h.status.label());
            o.addProperty("batchDone", h.batchDone);
            o.addProperty("batchTotal", h.batchTotal);
            o.addProperty("abnormal", h.abnormal);
            o.addProperty("jobError", h.jobError);
            o.addProperty("records", h.records);
            a.add(o);
        }
        return a;
    }

    public static JsonObject statsJson(Stats s) {
        JsonObject o = new JsonObject();
        o.addProperty("days", s.days);
        o.addProperty("abnormal", s.abnormal);
        o.addProperty("forced", s.forced);
        o.addProperty("jobError", s.jobError);
        o.addProperty("records", s.records);
        return o;
    }
}
