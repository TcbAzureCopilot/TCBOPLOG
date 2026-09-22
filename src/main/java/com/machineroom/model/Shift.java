package com.machineroom.model;

import java.time.LocalTime;

/** The three operator shifts. Task definitions may additionally use the pseudo-shift {@link #CROSS_KEY}. */
public enum Shift {
    DAY("day", "早班", "08:00-16:00"),
    EVENING("evening", "小夜班", "16:00-24:00"),
    NIGHT("night", "大夜班", "00:00-07:00 + 開機");

    /** Task-definition value meaning "starts in night shift, finishes in day shift". */
    public static final String CROSS_KEY = "cross";

    private final String key;
    private final String label;
    private final String hours;

    Shift(String key, String label, String hours) {
        this.key = key;
        this.label = label;
        this.hours = hours;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String hours() { return hours; }

    /** Short (2-char) label used in tags: 早班 / 小夜 / 大夜. */
    public String shortLabel() { return label.substring(0, 2); }

    public static Shift fromKey(String key) {
        if (key == null) return null;
        for (Shift s : values()) {
            if (s.key.equalsIgnoreCase(key.trim())) return s;
        }
        return null;
    }

    /** Next shift in the hand-over order day → evening → night → day. */
    public Shift next() {
        switch (this) {
            case DAY: return EVENING;
            case EVENING: return NIGHT;
            default: return DAY;
        }
    }

    /** Shift that is on duty at a wall-clock time. */
    public static Shift forTime(LocalTime t) {
        int h = t.getHour();
        if (h >= 8 && h < 16) return DAY;
        if (h >= 16) return EVENING;
        return NIGHT;
    }
}
