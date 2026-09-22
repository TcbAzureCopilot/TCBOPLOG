package com.machineroom.model;

/** One batch task row of a daily log (LOG_TASK): the definition snapshot plus the day's execution data. */
public class Task {
    // snapshot of the definition at creation time
    public String code;
    public int seq;
    public String name;
    public String schedule;
    public String plannedStart;
    public String shift;              // day | evening | night | cross
    public boolean hasEnd;
    public String qtyLabel;

    // execution
    public boolean shouldExecute;
    public Shift assignedShift;
    public boolean forced;
    public String forceReason = "";
    public String forcedBy = "";
    public Shift handoverFrom;
    public Shift handoverEndShift;    // for cross tasks: the shift that finishes it (day)
    public boolean done;
    public boolean abnormal;
    public String startTime = "";
    public String endTime = "";
    public String qtyValue = "";
    public String remark = "";
    public Signature opSign;
    public Signature reviewerSign;

    public boolean isCross() {
        return Shift.CROSS_KEY.equals(shift);
    }

    /**
     * The shift responsible for completing this task: the finishing shift (day) for a cross task,
     * otherwise the assigned shift. Used for shift-card counts and "本班送出" validation.
     */
    public Shift ownerShift() {
        return isCross() && handoverEndShift != null ? handoverEndShift : assignedShift;
    }

    /** True when {@code s} is responsible for completing this task (see {@link #ownerShift()}). */
    public boolean belongsTo(Shift s) {
        return s != null && s == ownerShift();
    }

    /** True when {@code s} may fill this task: the starting shift or the finishing shift of a cross task. */
    public boolean editableBy(Shift s) {
        if (s == null) return false;
        return s == assignedShift || (isCross() && s == handoverEndShift);
    }

    public boolean touched() {
        return !isEmpty(startTime) || !isEmpty(endTime) || done || abnormal || !isEmpty(remark) || !isEmpty(qtyValue) || opSign != null;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
