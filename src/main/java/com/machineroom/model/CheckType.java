package com.machineroom.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 三班檢查項目類型 and the sub-value keys each type carries. */
public enum CheckType {
    GENERAL("general", "一般（正常/異常）", Collections.<String>emptyList()),
    CABINET("cabinet", "機櫃（機櫃+進出登記簿）", Collections.<String>emptyList()),
    PORTAL("portal", "PORTAL（5 子項）", Arrays.asList("網銀", "WWW", "金控官網", "EATM", "COEIP")),
    SMS("sms", "SMS USAGE（3 個 %）", Arrays.asList("SGLGMVS", "SGLGIMS", "SGMQLOG")),
    RMF("rmf", "TSO RMF（PRDA + PRDB）", Arrays.asList(
            "PRDA.CSA", "PRDA.ECSA", "PRDA.SQA", "PRDA.ESQA",
            "PRDB.CSA", "PRDB.ECSA", "PRDB.SQA", "PRDB.ESQA"));

    private final String key;
    private final String label;
    private final List<String> valueKeys;

    CheckType(String key, String label, List<String> valueKeys) {
        this.key = key;
        this.label = label;
        this.valueKeys = valueKeys;
    }

    public String key() { return key; }
    public String label() { return label; }
    /** Sub-value keys (portal / sms / rmf); empty for general / cabinet. */
    public List<String> valueKeys() { return valueKeys; }

    public static CheckType fromKey(String key) {
        if (key == null) return null;
        for (CheckType t : values()) {
            if (t.key.equalsIgnoreCase(key.trim())) return t;
        }
        return null;
    }
}
