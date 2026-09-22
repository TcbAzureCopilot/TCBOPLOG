package com.machineroom.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.machineroom.config.AppConfig;

/**
 * 邏輯日: the operational day starts at {@code app.logicalDayCutoffHour} (default 07:00).
 * The night shift (00:00–06:59) therefore books into the previous calendar date.
 */
public final class LogicalDate {

    private static final String[] WEEKDAY = {"日", "一", "二", "三", "四", "五", "六"};
    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    public static final DateTimeFormatter TS_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    public static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private LogicalDate() {
    }

    public static LocalDate today(LocalDateTime now, int cutoffHour) {
        return now.getHour() < cutoffHour ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    public static LocalDate today() {
        return today(LocalDateTime.now(), AppConfig.get().logicalDayCutoffHour());
    }

    /** 星期 name: 日 一 二 三 四 五 六 */
    public static String weekday(LocalDate d) {
        return WEEKDAY[d.getDayOfWeek().getValue() % 7];
    }

    /** 太陽日 = day of year */
    public static int solarDay(LocalDate d) {
        return d.getDayOfYear();
    }

    /** Times 00:00–06:59 belong to the next calendar day of a logical day. */
    public static boolean isNextDayTime(String hhmm) {
        if (hhmm == null || hhmm.length() < 2) return false;
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            return h >= 0 && h < 7;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Empty is allowed ("not filled"); otherwise strictly HH:mm with 00–23 / 00–59 (24:00 is rejected). */
    public static boolean isValidTime(String hhmm) {
        if (hhmm == null || hhmm.isEmpty()) return true;
        if (!hhmm.matches("\\d{2}:\\d{2}")) return false;
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3, 5));
        return h <= 23 && m <= 59;
    }

    public static LocalDate parse(String s) {
        if (s == null) throw ServiceException.badRequest("日期格式錯誤");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ServiceException.badRequest("日期格式錯誤：" + s);
        }
    }

    public static String nowHm() {
        return LocalTime.now().format(HM);
    }

    public static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }

    public static String fmtMs(LocalDateTime t) {
        return t == null ? null : t.format(TS_MS);
    }
}
