package com.machineroom.model;

/** Workflow state of a daily log. */
public enum ReportStatus {
    DRAFT("draft", "填寫中"),
    SUBMITTED("submitted", "待副科審核"),
    REVIEWED("reviewed", "待科長核准"),
    APPROVED("approved", "已核准 🔒");

    private final String key;
    private final String label;

    ReportStatus(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() { return key; }
    public String label() { return label; }

    public static ReportStatus fromKey(String key) {
        if (key == null) return null;
        for (ReportStatus s : values()) {
            if (s.key.equalsIgnoreCase(key.trim())) return s;
        }
        return null;
    }
}
