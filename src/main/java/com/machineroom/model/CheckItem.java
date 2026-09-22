package com.machineroom.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** One 三班檢查 row of a daily log (LOG_CHECK + LOG_CHECK_VALUE). */
public class CheckItem {
    public String defId;
    public int seq;
    public String name;
    public String type;               // CheckType key
    public Shift shift;
    public String time = "";
    public String status = "";        // general / cabinet: 正常 | 異常 | 假日不執行
    public String entryLog = "";      // cabinet: 進出登記簿
    public boolean holidaySkip;
    /** Sub-values keyed by {@link CheckType#valueKeys()} (portal / sms / rmf). Insertion ordered. */
    public Map<String, String> values = new LinkedHashMap<>();
    public Signature opSign;
    public Signature reviewerSign;

    public CheckType checkType() {
        CheckType t = CheckType.fromKey(type);
        return t == null ? CheckType.GENERAL : t;
    }

    /** Mirrors the original {@code isCheckFilled}: time is mandatory, then every sub-value / status. */
    public boolean isFilled() {
        if (time == null || time.isEmpty()) return false;
        CheckType t = checkType();
        if (!t.valueKeys().isEmpty()) {
            for (String k : t.valueKeys()) {
                String v = values.get(k);
                if (v == null || v.isEmpty()) return false;
            }
            return true;
        }
        if (t == CheckType.CABINET) return !isEmpty(status) && !isEmpty(entryLog);
        return !isEmpty(status);
    }

    public boolean touched() {
        if (opSign != null || reviewerSign != null) return true;
        if (!isEmpty(status) || !isEmpty(entryLog) || !isEmpty(time)) return true;
        for (String v : values.values()) {
            if (!isEmpty(v)) return true;
        }
        return false;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
