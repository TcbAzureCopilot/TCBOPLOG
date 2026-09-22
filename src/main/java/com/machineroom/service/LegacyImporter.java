package com.machineroom.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.machineroom.model.CheckItem;
import com.machineroom.model.CheckType;
import com.machineroom.model.DailyLog;
import com.machineroom.model.DayType;
import com.machineroom.model.EquipItem;
import com.machineroom.model.EquipType;
import com.machineroom.model.ImportantRecord;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.ReviewComment;
import com.machineroom.model.Shift;
import com.machineroom.model.Signature;
import com.machineroom.model.Task;
import com.machineroom.web.LogJson;

/**
 * Converts the ORIGINAL application's data file / backup format
 * ({@code {"reports": {"2026-07-03": {docInfo, sysEquip, shiftChecks, jobError, importantRecords, report, tasks}}}})
 * into {@link DailyLog}s so existing history can be migrated into DB2 through {@code POST /api/import}.
 */
public final class LegacyImporter {

    private LegacyImporter() {
    }

    /** True when the payload is in the old single-file app's format. */
    public static boolean isLegacy(JsonObject root) {
        JsonElement reports = root.get("reports");
        if (reports == null || !reports.isJsonObject()) return false;
        for (Map.Entry<String, JsonElement> en : reports.getAsJsonObject().entrySet()) {
            return en.getValue().isJsonObject() && en.getValue().getAsJsonObject().has("docInfo");
        }
        return false;
    }

    public static List<DailyLog> convert(JsonObject root) {
        List<DailyLog> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("reports").entrySet()) {
            if (!en.getValue().isJsonObject()) continue;
            try {
                out.add(convertOne(en.getKey(), en.getValue().getAsJsonObject()));
            } catch (RuntimeException e) {
                throw ServiceException.badRequest("舊格式資料 " + en.getKey() + " 轉換失敗：" + e.getMessage());
            }
        }
        return out;
    }

    private static DailyLog convertOne(String dateKey, JsonObject r) {
        JsonObject doc = obj(r, "docInfo");
        JsonObject rep = obj(r, "report");
        DailyLog l = new DailyLog();
        l.date = LogicalDate.parse(s(doc, "date") != null ? s(doc, "date") : dateKey);
        l.weekday = LogicalDate.weekday(l.date);
        l.solarDay = LogicalDate.solarDay(l.date);
        DayType dt = DayType.fromKey(s(doc, "dayType"));
        l.dayType = dt == null ? DayType.defaultFor(l.date) : dt;
        l.dayTypeOverride = b(doc, "dayTypeOverride");
        l.dayTypeReason = se(doc, "dayTypeReason");
        l.dayTypeChangedBy = se(doc, "dayTypeChangedBy");
        l.bootUser = se(doc, "bootUser");
        l.bootTime = se(doc, "bootTime");
        l.bootOpSign = sig(doc.get("bootOpSign"));
        l.bootReviewerSign = sig(doc.get("bootReviewerSign"));
        JsonObject ss = obj(doc, "shiftStatus"), sub = obj(doc, "shiftSubmits"), je = obj(r, "jobError");
        for (Shift sh : Shift.values()) {
            l.shiftStatus.put(sh, "submitted".equals(s(ss, sh.key())) ? DailyLog.SHIFT_SUBMITTED : DailyLog.SHIFT_DRAFT);
            l.shiftSubmits.put(sh, sig(sub.get(sh.key())));
            l.jobError.put(sh, i(je, sh.key()));
        }
        ReportStatus st = ReportStatus.fromKey(s(rep, "status"));
        l.status = st == null ? ReportStatus.DRAFT : st;
        l.unlockReason = se(rep, "unlockReason");
        l.versions = Math.max(1, i(rep, "versions"));
        JsonObject ap = obj(rep, "approval");
        l.approvalOperator = sig(ap.get("operator"));
        l.approvalDeputy = sig(ap.get("deputy"));
        l.approvalChief = sig(ap.get("chief"));
        for (JsonElement e : arr(rep, "reviewComments")) {
            JsonObject c = e.getAsJsonObject();
            l.reviewComments.add(new ReviewComment(se(c, "by"), se(c, "role"), LogJson.parseTs(s(c, "time")), se(c, "text")));
        }

        // sysEquip: {dms:[...], custom:{id:{...}}}
        JsonObject eq = obj(r, "sysEquip");
        int seq = 0;
        for (JsonElement e : arr(eq, "dms")) {
            EquipItem it = equip(e.getAsJsonObject(), ++seq);
            if (it != null) l.equip.add(it);
        }
        for (Map.Entry<String, JsonElement> en : obj(eq, "custom").entrySet()) {
            if (!en.getValue().isJsonObject()) continue;
            JsonObject x = en.getValue().getAsJsonObject();
            if (!x.has("defId")) x.addProperty("defId", en.getKey());
            EquipItem it = equip(x, ++seq);
            if (it != null) l.equip.add(it);
        }

        JsonObject checks = obj(r, "shiftChecks");
        for (Shift sh : Shift.values()) {
            int cs = 0;
            for (JsonElement e : arr(checks, sh.key())) {
                JsonObject x = e.getAsJsonObject();
                CheckItem ci = new CheckItem();
                ci.defId = s(x, "defId") != null ? s(x, "defId") : sh.key() + "_" + cs;
                ci.seq = ++cs;
                ci.name = se(x, "name");
                ci.type = CheckType.fromKey(s(x, "type")) == null ? CheckType.GENERAL.key() : s(x, "type");
                ci.shift = sh;
                ci.time = se(x, "time");
                ci.status = se(x, "status");
                ci.entryLog = se(x, "entryLog");
                ci.holidaySkip = b(x, "holidaySkip");
                ci.opSign = sig(x.get("opSign"));
                ci.reviewerSign = sig(x.get("reviewerSign"));
                CheckType t = ci.checkType();
                JsonObject subItems = obj(x, "subItems"), sms = obj(x, "sms"), rmf = obj(x, "rmf");
                for (String k : t.valueKeys()) {
                    String v = "";
                    if (t == CheckType.PORTAL) v = se(subItems, k);
                    else if (t == CheckType.SMS) v = se(sms, k);
                    else if (t == CheckType.RMF) {
                        String[] pk = k.split("\\.");
                        v = se(obj(rmf, pk[0]), pk[1]);
                    }
                    ci.values.put(k, v);
                }
                l.checks.get(sh).add(ci);
            }
        }

        int ts = 0;
        for (JsonElement e : arr(r, "tasks")) {
            JsonObject x = e.getAsJsonObject();
            Task t = new Task();
            t.code = se(x, "code");
            if (t.code.isEmpty()) continue;
            t.seq = ++ts;
            t.name = se(x, "name");
            t.schedule = se(x, "schedule");
            t.plannedStart = se(x, "plannedStart");
            t.shift = s(x, "shift") == null ? Shift.DAY.key() : s(x, "shift");
            t.hasEnd = b(x, "hasEnd");
            t.qtyLabel = s(x, "qty");
            t.shouldExecute = b(x, "shouldExecute");
            String as = s(x, "assignedShift");
            if (t.isCross()) {
                t.assignedShift = Shift.NIGHT;
                t.handoverEndShift = Shift.DAY;
            } else {
                t.assignedShift = Shift.fromKey(as) == null ? Shift.DAY : Shift.fromKey(as);
            }
            t.forced = b(x, "forced");
            t.forceReason = se(x, "forceReason");
            t.forcedBy = se(x, "forcedBy");
            t.handoverFrom = Shift.fromKey(s(x, "handoverFrom"));
            t.done = b(x, "done");
            t.abnormal = b(x, "abnormal");
            t.startTime = se(x, "startTime");
            t.endTime = se(x, "endTime");
            t.qtyValue = se(x, "qtyValue");
            t.remark = se(x, "remark");
            t.opSign = sig(x.get("opSign"));
            t.reviewerSign = sig(x.get("reviewerSign"));
            l.tasks.add(t);
        }

        int rs = 0;
        for (JsonElement e : arr(r, "importantRecords")) {
            JsonObject x = e.getAsJsonObject();
            ImportantRecord rec = new ImportantRecord();
            rec.seq = ++rs;
            rec.time = se(x, "time");
            rec.taskCode = se(x, "taskCode");
            rec.description = se(x, "description");
            rec.notifySP = se(x, "notifySP");
            rec.notifyAP = se(x, "notifyAP");
            rec.recoverTime = se(x, "recoverTime");
            rec.ticket = se(x, "ticket");
            rec.op = se(x, "op").isEmpty() ? "import" : se(x, "op");
            String src = s(x, "source");
            rec.source = ImportantRecord.validSource(src) ? src : ImportantRecord.SRC_MANUAL;
            rec.shift = Shift.fromKey(s(x, "shift"));
            if (rec.description.isEmpty()) rec.description = "(無描述)";
            l.records.add(rec);
        }
        return l;
    }

    private static EquipItem equip(JsonObject x, int seq) {
        EquipItem it = new EquipItem();
        it.defId = s(x, "defId");
        it.type = s(x, "type");
        it.shift = Shift.fromKey(s(x, "shift"));
        if (it.defId == null || it.shift == null || EquipType.fromKey(it.type) == null) return null;
        it.seq = seq;
        it.defName = s(x, "defName") != null ? s(x, "defName") : se(x, "name");
        it.time = se(x, "time");
        it.status = se(x, "status");
        for (JsonElement o : arr(x, "statusOptions")) it.statusOptions.add(o.getAsString());
        if (EquipType.STATUS.key().equals(it.type) && it.statusOptions.isEmpty()) {
            it.statusOptions.add("正常");
            it.statusOptions.add("異常");
        }
        // old versions stored DMS as before/after; newer as count
        String count = se(x, "count");
        if (count.isEmpty()) {
            int before = parseInt(se(x, "before")), after = parseInt(se(x, "after"));
            if (before + after > 0) count = String.valueOf(before + after);
        }
        it.count = count;
        it.notify = se(x, "notify");
        it.enabled = it.isIms() ? b(x, "enabled") : true;
        it.opSign = sig(x.get("opSign"));
        it.reviewerSign = sig(x.get("reviewerSign"));
        return it;
    }

    private static Signature sig(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        String user = s(o, "user");
        if (user == null || user.isEmpty()) return null;
        // approval times in the old app were locale strings ("2026/7/3 下午5:09:09") → unparsable → null
        return new Signature(user, LogJson.parseTs(s(o, "time")), b(o, "integratorEdit"));
    }

    private static JsonObject obj(JsonObject p, String k) {
        JsonElement e = p.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arr(JsonObject p, String k) {
        JsonElement e = p.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String s(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    private static String se(JsonObject o, String k) {
        String v = s(o, k);
        return v == null ? "" : v;
    }

    private static boolean b(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return false;
        if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        return "true".equalsIgnoreCase(e.getAsString());
    }

    private static int i(JsonObject o, String k) {
        return parseInt(se(o, k));
    }

    private static int parseInt(String v) {
        try {
            return v == null || v.trim().isEmpty() ? 0 : (int) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
