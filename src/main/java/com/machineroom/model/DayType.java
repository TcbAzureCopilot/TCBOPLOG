package com.machineroom.model;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** 日類型 */
public enum DayType {
    BUSINESS("business", "營業日"),
    HOLIDAY("holiday", "假日"),
    TYPHOON("typhoon", "颱風日");

    private final String key;
    private final String label;

    DayType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() { return key; }
    public String label() { return label; }

    public static DayType fromKey(String key) {
        if (key == null) return null;
        for (DayType d : values()) {
            if (d.key.equalsIgnoreCase(key.trim())) return d;
        }
        return null;
    }

    /** Default classification: Saturday / Sunday are holidays, everything else a business day. */
    public static DayType defaultFor(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) ? HOLIDAY : BUSINESS;
    }
}
