package com.machineroom.model;

import java.time.LocalDateTime;

/** 審核意見 / 退件原因 (LOG_COMMENT). */
public class ReviewComment {
    public int id;
    public String by;
    public String role;       // e.g. 副科, 科長, 副科 退件
    public LocalDateTime time;
    public String text;

    public ReviewComment() {
    }

    public ReviewComment(String by, String role, LocalDateTime time, String text) {
        this.by = by;
        this.role = role;
        this.time = time;
        this.text = text;
    }
}
