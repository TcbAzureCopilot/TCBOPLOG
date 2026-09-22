package com.machineroom.model;

import java.util.ArrayList;
import java.util.List;

/** One system-equipment check row of a daily log (LOG_EQUIP). */
public class EquipItem {
    public String defId;
    public int seq;
    public String defName;
    public String type;               // EquipType key
    public Shift shift;
    public String time = "";
    public String status = "";        // type=status
    public List<String> statusOptions = new ArrayList<>();
    public String count = "";         // type=dms
    public String notify = "";        // type=ims / text
    public boolean enabled = true;    // type=ims: special operation switched on for the day
    public String enableReason = "";
    public Signature opSign;
    public Signature reviewerSign;

    public boolean isIms() {
        return EquipType.IMS.key().equals(type);
    }

    /** IMS rows count only when enabled. */
    public boolean isActive() {
        return !isIms() || enabled;
    }

    /** Mirrors the original {@code isEquipFilled}: time is mandatory. */
    public boolean isFilled() {
        if (!isActive()) return false;
        if (time == null || time.isEmpty()) return false;
        if (EquipType.DMS.key().equals(type)) return count != null && !count.isEmpty();
        return (status != null && !status.isEmpty()) || (notify != null && !notify.isEmpty());
    }

    public boolean touched() {
        return opSign != null || reviewerSign != null
                || !isEmpty(status) || !isEmpty(notify) || !isEmpty(count) || !isEmpty(time);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
