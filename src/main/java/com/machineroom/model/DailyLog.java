package com.machineroom.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A whole day's log: header (DAILY_LOG) plus all child rows. */
public class DailyLog {

    public static final String SHIFT_DRAFT = "draft";
    public static final String SHIFT_SUBMITTED = "submitted";

    public LocalDate date;
    public String weekday;            // 日 一 二 三 四 五 六
    public int solarDay;              // day of year
    public DayType dayType = DayType.BUSINESS;
    public boolean dayTypeOverride;
    public String dayTypeReason = "";
    public String dayTypeChangedBy = "";

    public String bootUser = "";
    public String bootTime = "";
    public Signature bootOpSign;
    public Signature bootReviewerSign;

    public Map<Shift, String> shiftStatus = new EnumMap<>(Shift.class);
    public Map<Shift, Signature> shiftSubmits = new EnumMap<>(Shift.class);

    public ReportStatus status = ReportStatus.DRAFT;
    public String unlockReason = "";
    public int versions = 1;
    public Signature approvalOperator;
    public Signature approvalDeputy;
    public Signature approvalChief;
    public List<ReviewComment> reviewComments = new ArrayList<>();

    public Map<Shift, Integer> jobError = new EnumMap<>(Shift.class);

    public List<EquipItem> equip = new ArrayList<>();
    public Map<Shift, List<CheckItem>> checks = new EnumMap<>(Shift.class);
    public List<Task> tasks = new ArrayList<>();
    public List<ImportantRecord> records = new ArrayList<>();

    public LocalDateTime createdAt;
    public String createdBy;
    public LocalDateTime updatedAt;
    public String updatedBy;

    public DailyLog() {
        for (Shift s : Shift.values()) {
            shiftStatus.put(s, SHIFT_DRAFT);
            shiftSubmits.put(s, null);
            jobError.put(s, 0);
            checks.put(s, new ArrayList<CheckItem>());
        }
    }

    public boolean isShiftSubmitted(Shift s) {
        return SHIFT_SUBMITTED.equals(shiftStatus.get(s));
    }

    public boolean allShiftsSubmitted() {
        for (Shift s : Shift.values()) {
            if (!isShiftSubmitted(s)) return false;
        }
        return true;
    }

    public Task task(String code) {
        for (Task t : tasks) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        return null;
    }

    public EquipItem equip(String defId) {
        for (EquipItem e : equip) {
            if (e.defId.equals(defId)) return e;
        }
        return null;
    }

    public CheckItem check(Shift shift, String defId) {
        for (CheckItem c : checks.get(shift)) {
            if (c.defId.equals(defId)) return c;
        }
        return null;
    }

    public ImportantRecord record(int id) {
        for (ImportantRecord r : records) {
            if (r.id == id) return r;
        }
        return null;
    }

    public int jobErrorTotal() {
        int t = 0;
        for (Integer v : jobError.values()) t += v == null ? 0 : v;
        return t;
    }
}
