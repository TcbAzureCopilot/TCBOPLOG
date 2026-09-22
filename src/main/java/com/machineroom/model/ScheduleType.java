package com.machineroom.model;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** When a batch task is expected to run. */
public enum ScheduleType {
    DAILY("daily", "每日"),
    BUSINESS_DAY("business_day", "營業日"),
    MONTHLY_FIRST("monthly_first", "每月1日"),
    MONTHLY_FIRST_BUSINESS("monthly_first_business", "每月第1營業日"),
    WEEKLY_SUNDAY("weekly_sunday", "每週日");

    private final String key;
    private final String label;

    ScheduleType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() { return key; }
    public String label() { return label; }

    public static ScheduleType fromKey(String key) {
        if (key == null) return null;
        for (ScheduleType s : values()) {
            if (s.key.equalsIgnoreCase(key.trim())) return s;
        }
        return null;
    }

    public static String labelOf(String key) {
        ScheduleType s = fromKey(key);
        return s == null ? key : s.label;
    }

    /** Mirrors the original {@code shouldExecuteFor}: typhoon days run nothing. */
    public static boolean shouldExecute(String scheduleKey, LocalDate date, DayType dayType) {
        DayType dt = dayType != null ? dayType : DayType.defaultFor(date);
        if (dt == DayType.TYPHOON) return false;
        ScheduleType s = fromKey(scheduleKey);
        if (s == null) return false;
        boolean biz = dt == DayType.BUSINESS;
        boolean sunday = date.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean first = date.getDayOfMonth() == 1;
        switch (s) {
            case DAILY: return true;
            case BUSINESS_DAY: return biz;
            case MONTHLY_FIRST: return first;
            case MONTHLY_FIRST_BUSINESS: return first && biz;
            case WEEKLY_SUNDAY: return sunday;
            default: return false;
        }
    }
}
