package com.machineroom.model;

import java.time.LocalDateTime;

/** 重要記錄事項 (LOG_RECORD). */
public class ImportantRecord {
    public static final String SRC_MANUAL = "manual";
    public static final String SRC_BATCH = "batch";
    public static final String SRC_JOBERROR = "joberror";
    public static final String SRC_CHECK = "check";

    public int id;
    public int seq;
    public String time = "";
    public String taskCode = "";
    public String description = "";
    public String notifySP = "";
    public String notifyAP = "";
    public String recoverTime = "";
    public String ticket = "";
    public String op = "";
    public String source = SRC_MANUAL;
    public Shift shift;
    public LocalDateTime createdAt;

    public static boolean validSource(String s) {
        return SRC_MANUAL.equals(s) || SRC_BATCH.equals(s) || SRC_JOBERROR.equals(s) || SRC_CHECK.equals(s);
    }
}
