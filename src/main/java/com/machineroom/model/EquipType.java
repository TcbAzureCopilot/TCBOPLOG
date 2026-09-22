package com.machineroom.model;

/** 系統設備檢查項目類型 */
public enum EquipType {
    STATUS("status", "狀態（正常/異常）"),
    DMS("dms", "DMS（台數）"),
    IMS("ims", "IMS（特殊作業）"),
    TEXT("text", "文字通知");

    private final String key;
    private final String label;

    EquipType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() { return key; }
    public String label() { return label; }

    public static EquipType fromKey(String key) {
        if (key == null) return null;
        for (EquipType t : values()) {
            if (t.key.equalsIgnoreCase(key.trim())) return t;
        }
        return null;
    }
}
