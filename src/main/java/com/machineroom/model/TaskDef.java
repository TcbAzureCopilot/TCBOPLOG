package com.machineroom.model;

/** 批次作業定義 (DEF_TASK). Simple data holder. */
public class TaskDef {
    public String code;
    public int seq;
    public String name;
    public String schedule;       // ScheduleType key
    public String plannedStart;   // HH:mm
    public String shift;          // day | evening | night | cross
    public boolean hasEnd;
    public String qtyLabel;       // null when the task has no quantity column
    public boolean enabled = true;
    /** True when an API body explicitly carried {@code enabled} (otherwise updates keep the stored value). */
    public transient boolean enabledSpecified;

    public boolean isCross() {
        return Shift.CROSS_KEY.equals(shift);
    }
}
